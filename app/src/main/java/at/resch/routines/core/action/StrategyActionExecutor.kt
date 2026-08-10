package at.resch.routines.core.action

import android.util.Log
import at.resch.routines.core.ActionExecutor
import at.resch.routines.core.cache.CapabilityCache
import at.resch.routines.core.shell.RootChecker
import at.resch.routines.domain.model.Action
import at.resch.routines.domain.model.ActionResult

/**
 * Eine einzelne, isoliert testbare Ausführungs-Strategie aus der Fallback-Kette
 * eines [StrategyActionExecutor].
 *
 * @property id Stabile, gerätedauerhafte Kennung (wird im [CapabilityCache]
 *   gemerkt). Innerhalb eines Executors eindeutig.
 * @property requiresRoot `true`, wenn die Strategie Root benötigt; wird
 *   übersprungen, falls kein Root verfügbar ist.
 */
interface Strategy {
    val id: String
    val requiresRoot: Boolean

    /**
     * Führt diese Strategie für [action] aus. Sollte **nicht** werfen, darf es
     * aber — [StrategyActionExecutor] fängt Exceptions je Strategie ab und fährt
     * mit der nächsten fort.
     */
    suspend fun attempt(action: Action): ActionResult
}

/**
 * Bequemer Default-`Strategy`-Träger, damit Subklassen Strategien knapp als
 * Lambda deklarieren können (`strategy("svc_user", requiresRoot = false) { … }`).
 */
private class LambdaStrategy(
    override val id: String,
    override val requiresRoot: Boolean,
    private val block: suspend (Action) -> ActionResult
) : Strategy {
    override suspend fun attempt(action: Action): ActionResult = block(action)
}

/**
 * Basis für Compatibility-Contract-konforme Executoren: kapselt die generische
 * Fallback-Ketten-Logik (Template-Method), Subklassen liefern nur die geordnete
 * [strategies]-Liste.
 *
 * ## Ablauf von [execute]
 * 1. Strategie-Liste bauen (geordnet: kein-Root → Root-modern → Root-legacy …).
 * 2. **Cache-Hit:** Liefert [CapabilityCache.preferredStrategy] eine ID aus der
 *    Liste, wird diese Strategie zuerst probiert (sofern nicht Root-only ohne
 *    verfügbaren Root). Erfolg → [ActionResult.Success].
 * 3. Andernfalls (oder bei Cache-Miss/-Fehlschlag) Iteration in deklarierter
 *    Reihenfolge; bereits probierte Cache-Strategie wird übersprungen. Root-only
 *    Strategien werden bei fehlendem Root übersprungen. [RootChecker.isRootAvailable]
 *    wird **lazy** und höchstens einmal ausgewertet.
 * 4. Erste erfolgreiche Strategie → [CapabilityCache.remember] + Success.
 * 5. Schlägt alles fehl → **eine** aggregierte [ActionResult.Failure], die jede
 *    versuchte Strategie-ID samt Grund auflistet (bzw. klar sagt, dass nur
 *    Root-Strategien existierten und kein Root verfügbar war).
 *
 * Wirft nie: unerwartete Exceptions einer Strategie werden zu deren Failure.
 */
abstract class StrategyActionExecutor(
    private val rootChecker: RootChecker,
    private val capabilityCache: CapabilityCache
) : ActionExecutor {

    /** Geordnete Fallback-Kette für [action]. Erste erfolgreiche gewinnt. */
    protected abstract fun strategies(action: Action): List<Strategy>

    /**
     * DSL-Helfer für Subklassen: erzeugt eine [Strategy] aus einem Lambda.
     */
    protected fun strategy(
        id: String,
        requiresRoot: Boolean,
        attempt: suspend (Action) -> ActionResult
    ): Strategy = LambdaStrategy(id, requiresRoot, attempt)

    final override suspend fun execute(action: Action): ActionResult {
        val strategies = strategies(action)
        if (strategies.isEmpty()) {
            return ActionResult.Failure("Keine Strategie für Action-Typ '$type' definiert.")
        }

        // Lazy, höchstens einmal ausgewerteter Root-Status.
        var rootKnown = false
        var rootAvailable = false
        suspend fun rootAvailable(): Boolean {
            if (!rootKnown) {
                rootAvailable = rootChecker.isRootAvailable()
                rootKnown = true
            }
            return rootAvailable
        }

        val attempts = mutableListOf<Pair<String, ActionResult.Failure>>()
        var anyRootSkipped = false
        var anyNonRootTried = false

        // 2) Cache-Hit zuerst probieren.
        val preferredId = capabilityCache.preferredStrategy(type)
        val cached = preferredId?.let { id -> strategies.firstOrNull { it.id == id } }
        if (cached != null) {
            if (cached.requiresRoot && !rootAvailable()) {
                anyRootSkipped = true
                Log.d(TAG, "[$type] gemerkte Strategie '${cached.id}' übersprungen (kein Root)")
            } else {
                Log.d(TAG, "[$type] starte mit gemerkter Strategie '${cached.id}'")
                if (!cached.requiresRoot) anyNonRootTried = true
                when (val result = runStrategy(cached, action)) {
                    is ActionResult.Success -> return result // bleibt gemerkt
                    is ActionResult.Failure -> attempts += cached.id to result
                }
            }
        }

        // 3) Übrige Strategien in deklarierter Reihenfolge.
        for (strategy in strategies) {
            if (strategy === cached) continue // bereits probiert
            if (strategy.requiresRoot && !rootAvailable()) {
                anyRootSkipped = true
                Log.d(TAG, "[$type] Strategie '${strategy.id}' übersprungen (kein Root)")
                continue
            }
            if (!strategy.requiresRoot) anyNonRootTried = true
            when (val result = runStrategy(strategy, action)) {
                is ActionResult.Success -> {
                    // 4) Erste erfolgreiche Strategie merken.
                    capabilityCache.remember(type, strategy.id)
                    return result
                }
                is ActionResult.Failure -> attempts += strategy.id to result
            }
        }

        // 5) Aggregierte Failure.
        return aggregateFailure(attempts, anyRootSkipped, anyNonRootTried)
    }

    /** Führt eine Strategie aus, fängt unerwartete Exceptions als Failure ab. */
    private suspend fun runStrategy(strategy: Strategy, action: Action): ActionResult {
        Log.d(TAG, "[$type] Strategie '${strategy.id}' wird versucht (requiresRoot=${strategy.requiresRoot})")
        val result = try {
            strategy.attempt(action)
        } catch (e: Exception) {
            Log.w(TAG, "[$type] Strategie '${strategy.id}' warf eine Exception", e)
            return ActionResult.Failure(
                reason = "Strategie '${strategy.id}' warf ${e.javaClass.simpleName}: ${e.message}",
                cause = e
            )
        }
        when (result) {
            is ActionResult.Success ->
                Log.d(TAG, "[$type] Strategie '${strategy.id}' erfolgreich" +
                    (result.output?.let { ": $it" } ?: ""))
            is ActionResult.Failure ->
                Log.d(TAG, "[$type] Strategie '${strategy.id}' fehlgeschlagen: ${result.reason}")
        }
        return result
    }

    private fun aggregateFailure(
        attempts: List<Pair<String, ActionResult.Failure>>,
        anyRootSkipped: Boolean,
        anyNonRootTried: Boolean
    ): ActionResult.Failure {
        if (attempts.isEmpty() && anyRootSkipped && !anyNonRootTried) {
            val reason = "Action '$type' fehlgeschlagen: alle Strategien benötigen Root, " +
                "aber kein Root verfügbar."
            Log.w(TAG, reason)
            return ActionResult.Failure(reason)
        }
        val details = attempts.joinToString(separator = "; ") { (id, failure) ->
            "[$id] ${failure.reason}"
        }
        val rootHint = if (anyRootSkipped) " (Root-Strategien mangels Root übersprungen)" else ""
        val lastCause = attempts.lastOrNull()?.second?.cause
        val reason = "Action '$type' fehlgeschlagen — alle Strategien erschöpft$rootHint: $details"
        Log.w(TAG, reason)
        return ActionResult.Failure(reason = reason, cause = lastCause)
    }

    companion object {
        private const val TAG = "StrategyActionExecutor"
    }
}
