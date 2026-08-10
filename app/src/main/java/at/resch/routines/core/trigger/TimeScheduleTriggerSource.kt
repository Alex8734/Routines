package at.resch.routines.core.trigger

import at.resch.routines.core.SystemEvent
import at.resch.routines.core.TriggerSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Test-Seam um das Warten zwischen Ticks. Erlaubt es, in Tests die echte
 * `kotlinx.coroutines.delay` durch eine virtuelle Zeit / Fake zu ersetzen.
 */
fun interface DelayProvider {
    suspend fun wait(millis: Long)
}

/**
 * [TriggerSource] für `"time_schedule"`. Reiner Coroutine-Ticker: emittiert
 * alle `intervalMillis` ein [SystemEvent.TimeTick], solange der [scope] aktiv ist.
 *
 * Bewusst KEIN AlarmManager (vermeidet `SCHEDULE_EXACT_ALARM`) und keine
 * Persistenz — übersteht keinen Prozess-Tod. Exakte/persistente Planung ist ein
 * späteres Enhancement (siehe `Trigger.TimeSchedule`-Doku).
 *
 * Hinweis zur Registry-Anbindung: Da alle `time_schedule`-Makros denselben
 * [TRIGGER_ID] teilen, läuft in dieser Batch *eine* Quelle mit einem festen
 * Default-Intervall. Pro-Makro-Intervalle würden mehrere Quelle-Instanzen
 * erfordern — ebenfalls späteres Enhancement.
 */
class TimeScheduleTriggerSource(
    /** Tick-Intervall in Millisekunden. Default: 15 Minuten. */
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    /** Verzögerungs-Seam — Default ist `kotlinx.coroutines.delay`. */
    private val delayProvider: DelayProvider = DelayProvider { millis -> delay(millis) }
) : TriggerSource {

    override val triggerId: String = TRIGGER_ID

    private var job: Job? = null

    override fun start(scope: CoroutineScope, emit: suspend (SystemEvent) -> Unit) {
        if (job?.isActive == true) return // idempotent
        job = scope.launch {
            while (isActive) {
                delayProvider.wait(intervalMillis)
                emit(SystemEvent.TimeTick)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        /** Trigger-Diskriminator (= `@SerialName` von `Trigger.TimeSchedule`). */
        const val TRIGGER_ID = "time_schedule"

        /** Default-Tick: 15 Minuten. */
        const val DEFAULT_INTERVAL_MILLIS = 15L * 60L * 1000L
    }
}
