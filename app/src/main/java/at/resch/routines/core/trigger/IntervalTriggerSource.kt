package at.resch.routines.core.trigger

import at.resch.routines.core.SystemEvent
import at.resch.routines.core.TriggerSource
import at.resch.routines.domain.model.Trigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * [TriggerSource] für `"interval"` — die **pro-Makro** konfigurierbare Variante
 * eines periodischen Triggers (Hauptanwendungsfall: zyklischer REST-Request über
 * den `http_request`-Executor).
 *
 * Im Gegensatz zur bewusst simplen [TimeScheduleTriggerSource] (eine Quelle,
 * fixes Intervall) betreibt diese Quelle **je unterschiedlichem Intervallwert
 * einen eigenen Ticker-Job**. Welche Intervalle gebraucht werden, kommt reaktiv
 * über [intervals] herein (Set der [Trigger.Interval] aller aktiven Makros); bei
 * jeder Änderung wird die Ticker-Map reconciled: neue Werte → Job starten,
 * weggefallene Werte → Job canceln.
 *
 * **Klemmung — bewusst asymmetrisch:** Der Map-Key und der im Event
 * ([SystemEvent.IntervalTick]) mitgegebene Wert ist immer der **Original-Wert
 * aus dem Makro**. Geklemmt (auf [Trigger.Interval.MIN_INTERVAL_SECONDS]) wird
 * ausschließlich die tatsächliche Wartedauer. Damit bleibt der
 * Gleichheitsvergleich in [SystemEvent.IntervalTick.matches] konsistent: ein
 * Makro mit `intervalSeconds = 1` matcht seinen eigenen Tick, wird real aber nur
 * alle 5 Sekunden ausgeführt (Schutz vor Dauerfeuer).
 *
 * Bewusst **frei von Android-Framework-Abhängigkeiten** (kein `Context`) — nur
 * der Flow und der [DelayProvider]-Seam, damit die Quelle als reiner
 * JVM-Unit-Test mit virtueller Zeit testbar ist.
 */
class IntervalTriggerSource(
    /** Benötigte Intervall-Konfigurationen aller aktiven Makros. */
    private val intervals: Flow<Set<Trigger.Interval>>,
    /** Verzögerungs-Seam — Default ist `kotlinx.coroutines.delay`. */
    private val delayProvider: DelayProvider = DelayProvider { millis -> delay(millis) }
) : TriggerSource {

    override val triggerId: String = TRIGGER_ID

    /** Job, der [intervals] collectet und die Ticker reconciled. */
    private var collectorJob: Job? = null

    /** Laufende Ticker, keyed über den **Original**-`intervalSeconds` des Makros. */
    private val tickers: MutableMap<Int, Job> = mutableMapOf()

    override fun start(scope: CoroutineScope, emit: suspend (SystemEvent) -> Unit) {
        if (collectorJob?.isActive == true) return // idempotent
        collectorJob = scope.launch {
            intervals.collect { configs -> reconcile(configs, scope, emit) }
        }
    }

    /**
     * Gleicht die laufenden Ticker mit dem aktuellen Bedarf ab.
     * Mehrere Makros mit demselben Intervallwert teilen sich einen Ticker; bei
     * abweichendem `runOnStart` gewinnt der erste (die Sofort-Emission ist ohnehin
     * nur ein einmaliger Bonus-Tick).
     */
    private fun reconcile(
        configs: Set<Trigger.Interval>,
        scope: CoroutineScope,
        emit: suspend (SystemEvent) -> Unit
    ) {
        val needed = configs.associateBy { it.intervalSeconds }

        // Weggefallene Intervalle stoppen.
        val obsolete = tickers.keys - needed.keys
        obsolete.forEach { seconds ->
            tickers.remove(seconds)?.cancel()
        }

        // Neu hinzugekommene Intervalle starten.
        needed.forEach { (seconds, config) ->
            if (tickers[seconds]?.isActive == true) return@forEach
            tickers[seconds] = startTicker(config, scope, emit)
        }
    }

    /**
     * Startet einen Ticker-Job für genau eine Intervall-Konfiguration.
     * Gewartet wird mit dem geklemmten Wert, emittiert wird der Original-Wert.
     */
    private fun startTicker(
        config: Trigger.Interval,
        scope: CoroutineScope,
        emit: suspend (SystemEvent) -> Unit
    ): Job {
        val originalSeconds = config.intervalSeconds
        val clampedMillis = clampToMillis(originalSeconds)
        return scope.launch {
            if (config.runOnStart) {
                emit(SystemEvent.IntervalTick(originalSeconds))
            }
            while (isActive) {
                delayProvider.wait(clampedMillis)
                emit(SystemEvent.IntervalTick(originalSeconds))
            }
        }
    }

    override fun stop() {
        collectorJob?.cancel()
        collectorJob = null
        tickers.values.forEach { it.cancel() }
        tickers.clear()
    }

    companion object {
        /** Trigger-Diskriminator (= `@SerialName` von `Trigger.Interval`). */
        const val TRIGGER_ID = "interval"

        /**
         * Klemmt [intervalSeconds] auf [Trigger.Interval.MIN_INTERVAL_SECONDS]
         * hoch und rechnet in Millisekunden um. Nur für die Wartedauer — nie für
         * den Event-Wert oder den Map-Key.
         */
        internal fun clampToMillis(intervalSeconds: Int): Long =
            max(intervalSeconds, Trigger.Interval.MIN_INTERVAL_SECONDS) * 1000L
    }
}
