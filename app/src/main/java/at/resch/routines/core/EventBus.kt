package at.resch.routines.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Zentraler Ereignis-Bus. [TriggerSource]s (Produzenten) speisen [SystemEvent]s
 * ein, der [MacroEvaluator] (Konsument) lauscht.
 *
 * **Buffering-Entscheidung:**
 * - `replay = 0`: Events sind flüchtige Trigger ("jetzt ist Boot passiert"). Ein
 *   spät hinzukommender Kollektor soll **nicht** alte Events nochmal auslösen —
 *   das würde Makros doppelt/verspätet feuern. Ausnahme `Startup`: der Service
 *   stellt sicher, dass der Evaluator läuft, *bevor* das Startup-Event emittiert
 *   wird (siehe MacroForegroundService), daher braucht es kein Replay.
 * - `extraBufferCapacity = 64`: gibt den Produzenten Puffer, sodass [tryEmit]
 *   praktisch nie fehlschlägt und [emit] nicht suspendet, falls der Evaluator
 *   kurz beschäftigt ist (z. B. lange Action). Großzügig, da Events klein sind.
 * - `onBufferOverflow` = Default (SUSPEND): bei extrem vollem Puffer wird der
 *   Produzent gedrosselt statt Events still zu verlieren.
 */
class EventBus {

    private val _events = MutableSharedFlow<SystemEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )

    /** Read-only-Strom für Konsumenten (Evaluator). */
    val events: SharedFlow<SystemEvent> = _events.asSharedFlow()

    /**
     * Nicht-suspendierende Emission für Callback-Kontexte (z. B. BroadcastReceiver).
     * Gibt `false` zurück, falls der Puffer voll war und nichts emittiert wurde.
     */
    fun tryEmit(event: SystemEvent): Boolean = _events.tryEmit(event)

    /** Suspendierende Emission für Coroutine-Kontexte (Trigger-Sources). */
    suspend fun emit(event: SystemEvent) = _events.emit(event)
}
