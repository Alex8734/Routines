package at.resch.routines.core

import android.util.Log
import at.resch.routines.data.MacroJson
import at.resch.routines.domain.model.Action
import at.resch.routines.domain.model.ActionResult
import at.resch.routines.domain.model.MacroScript
import kotlinx.serialization.SerializationException

/**
 * Führt Makros aus. Zwei Einstiegspunkte:
 * - [parse] : roher `configuration`-JSON-String → [MacroScript] (oder `null`).
 * - [execute] : führt die [MacroScript.actions] **sequentiell** mit
 *   **Fehlerisolation pro Action** aus.
 *
 * Dispatch erfolgt über eine Registry [executors] (Map type→[ActionExecutor]).
 * Unbekannte Action-Typen erzeugen [ActionResult.Failure] statt eines Absturzes.
 * Eine geworfene Exception eines Executors wird ebenfalls in Failure übersetzt.
 *
 * Konstruktor-injizierbar (QA: Liste von Executoren mockbar). Nutzt zentrale
 * [MacroJson]-Instanz.
 */
class MacroEngine(
    executors: List<ActionExecutor>
) {

    /** Registry: Action-Typ → zuständiger Executor. */
    private val registry: Map<String, ActionExecutor> = executors.associateBy { it.type }

    /**
     * Parst rohen JSON → [MacroScript]. Gibt `null` bei ungültigem/leerem JSON
     * (graceful), damit der Aufrufer den Fehler nicht als Crash erlebt.
     */
    fun parse(configuration: String): MacroScript? =
        try {
            MacroJson.decodeFromString<MacroScript>(configuration)
        } catch (e: SerializationException) {
            Log.w(TAG, "Ungültiges Makro-JSON: ${e.message}")
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Ungültiges Makro-JSON (Argument): ${e.message}")
            null
        }

    /**
     * Führt die Actions des [macro] sequentiell aus. Jede Action ist isoliert:
     * eine fehlgeschlagene Action stoppt die Schleife **nicht**, ihr Ergebnis
     * wird als [ActionResult.Failure] in die Ergebnisliste aufgenommen.
     *
     * @return Liste der Ergebnisse in Action-Reihenfolge.
     */
    suspend fun execute(macro: MacroScript): List<ActionResult> {
        Log.d(TAG, "Führe Makro '${macro.id}' mit ${macro.actions.size} Action(s) aus")
        return macro.actions.map { action -> executeAction(action) }
    }

    /** Convenience: parst und führt in einem Schritt aus. `null` wenn Parse scheitert. */
    suspend fun parseAndExecute(configuration: String): List<ActionResult>? =
        parse(configuration)?.let { execute(it) }

    private suspend fun executeAction(action: Action): ActionResult {
        val executor = registry[action.type]
            ?: return ActionResult.Failure(reason = "Unbekannter Action-Typ: '${action.type}'")
                .also { Log.w(TAG, it.reason) }

        return try {
            executor.execute(action)
        } catch (e: Exception) {
            Log.e(TAG, "Action '${action.type}' warf Exception", e)
            ActionResult.Failure(reason = "Action '${action.type}' fehlgeschlagen", cause = e)
        }
    }

    companion object {
        private const val TAG = "MacroEngine"
    }
}
