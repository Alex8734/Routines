package at.resch.routines.core

import android.util.Log
import at.resch.routines.data.MacroRepository
import kotlinx.coroutines.flow.first

/**
 * Bindeglied zwischen [EventBus] und [MacroEngine].
 *
 * Lauscht auf [EventBus.events], holt bei jedem [SystemEvent] die aktuell
 * **aktiven** Makros (`enabled == true`) aus dem [MacroRepository], matcht ihren
 * [at.resch.routines.domain.model.Trigger] gegen das Event (via
 * [SystemEvent.matches]) und dispatcht jedes passende Makro an die [MacroEngine].
 *
 * Konstruktor-injiziert (QA: Repository & Engine mockbar, Bus mit Turbine fütterbar).
 */
class MacroEvaluator(
    private val eventBus: EventBus,
    private val repository: MacroRepository,
    private val engine: MacroEngine
) {

    /**
     * Startet die Endlos-Kollektion. Läuft bis der umgebende Scope gecancelt wird
     * (der [at.resch.routines.core.MacroForegroundService] besitzt diesen Scope).
     * Suspendiert; vom Aufrufer in einer Coroutine zu starten.
     */
    suspend fun start() {
        Log.d(TAG, "MacroEvaluator gestartet — lausche auf EventBus")
        eventBus.events.collect { event -> onEvent(event) }
    }

    /** Verarbeitet ein einzelnes Event (separat testbar). */
    suspend fun onEvent(event: SystemEvent) {
        // Snapshot der aktiven, parsbaren Makros zum Event-Zeitpunkt.
        val activeMacros = repository.observeAll().first()
            .filter { it.enabled }

        val matching = activeMacros.filter { event.matches(it.trigger) }
        if (matching.isEmpty()) {
            Log.d(TAG, "Event $event — kein passendes Makro")
            return
        }

        Log.d(TAG, "Event $event matcht ${matching.size} Makro(s)")
        matching.forEach { macro ->
            engine.execute(macro)
        }
    }

    companion object {
        private const val TAG = "MacroEvaluator"
    }
}
