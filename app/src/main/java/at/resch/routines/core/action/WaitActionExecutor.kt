package at.resch.routines.core.action

import android.util.Log
import at.resch.routines.core.ActionExecutor
import at.resch.routines.domain.model.Action
import at.resch.routines.domain.model.ActionResult
import kotlinx.coroutines.delay

/**
 * Executor für `wait`. Pausiert die Makro-Ausführung.
 *
 * Weil [at.resch.routines.core.MacroEngine.execute] die Actions **sequentiell**
 * über eine suspend-`execute`-Funktion abarbeitet, pausiert ein `delay(...)`
 * hier echt zwischen vorheriger und nachfolgender Action.
 *
 * Param-Vertrag:
 * - `durationMs` : String (Long, ms), **erforderlich** — Wartezeit in
 *   Millisekunden. Fehlend / nicht parsbar / negativ → [ActionResult.Failure]
 *   (kein Crash, graceful degradation).
 *
 * Bewusst **kein** injizierter Dispatcher: `delay` respektiert den Scheduler der
 * laufenden Coroutine, daher springt QAs `runTest`-Virtual-Time darüber hinweg.
 *
 * Die Wartezeit wird auf [MAX_DURATION_MS] (1 h) gedeckelt, damit ein fehlerhaft
 * konfiguriertes Makro nicht versehentlich „ewig" blockiert.
 */
class WaitActionExecutor : ActionExecutor {

    override val type: String = "wait"

    override suspend fun execute(action: Action): ActionResult {
        val raw = action.params["durationMs"]
        val durationMs = raw?.toLongOrNull()
        if (durationMs == null || durationMs < 0L) {
            return ActionResult.Failure(
                "wait: Param 'durationMs' fehlt oder ist keine nicht-negative Zahl (war: '$raw')"
            )
        }

        val effective = durationMs.coerceAtMost(MAX_DURATION_MS)
        Log.d(TAG, "wait-action: warte ${effective}ms")
        delay(effective)
        return ActionResult.Success(output = "waited ${effective}ms")
    }

    companion object {
        private const val TAG = "WaitActionExecutor"

        /** Obergrenze der Wartezeit (1 Stunde), schützt vor Fehl-Konfiguration. */
        private const val MAX_DURATION_MS = 60L * 60L * 1000L
    }
}
