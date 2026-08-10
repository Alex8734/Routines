package at.resch.routines.core.trigger

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import at.resch.routines.core.SystemEvent
import at.resch.routines.core.TriggerSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Test-Seam um den Framework-Zugriff auf den Akkustand. Liefert einen [Flow]
 * von Akku-Prozentwerten (0–100). Erlaubt MockK-Tests ohne echtes Gerät /
 * Sticky-Broadcast.
 */
interface BatteryLevelProvider {
    /** Emittiert bei jeder Akku-Änderung den aktuellen Stand in Prozent (0–100). */
    val batteryLevelPercent: Flow<Int>
}

/**
 * Produktiv-Implementierung über den Sticky-Broadcast `ACTION_BATTERY_CHANGED`.
 * Benötigt keine Permission (Sticky-Broadcast). Der Initialwert wird über den
 * Sticky-Intent direkt beim Registrieren geliefert.
 */
class BroadcastBatteryLevelProvider(
    context: Context
) : BatteryLevelProvider {

    private val appContext = context.applicationContext

    override val batteryLevelPercent: Flow<Int> = callbackFlow {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                intent?.let { trySend(percentOf(it)) }
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        // registerReceiver mit Sticky-Filter liefert den letzten Intent sofort zurück.
        val sticky = appContext.registerReceiver(receiver, filter)
        sticky?.let { trySend(percentOf(it)) }

        awaitClose { appContext.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    /** Rechnet level/scale des Battery-Intents auf 0–100 Prozent um. */
    private fun percentOf(intent: Intent): Int {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return -1
        return (level * 100) / scale
    }
}

/**
 * [TriggerSource] für `"battery_level"`. Beobachtet den Akkustand über einen
 * [BatteryLevelProvider] und emittiert bei jeder Änderung [SystemEvent.BatteryChanged].
 *
 * Die eigentliche Schwellwert-Entscheidung (below/above) liegt im Event
 * ([SystemEvent.BatteryChanged.matches]) — diese Quelle kennt die Trigger-
 * Schwellen bewusst nicht und feuert für *alle* battery_level-Makros generisch.
 * Ungültige Stände (`-1`) werden verworfen.
 */
class BatteryTriggerSource(
    private val provider: BatteryLevelProvider
) : TriggerSource {

    override val triggerId: String = TRIGGER_ID

    private var job: Job? = null

    override fun start(scope: CoroutineScope, emit: suspend (SystemEvent) -> Unit) {
        if (job?.isActive == true) return // idempotent
        job = scope.launch {
            provider.batteryLevelPercent
                .collect { percent ->
                    if (percent in 0..100) emit(SystemEvent.BatteryChanged(percent))
                }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        /** Trigger-Diskriminator (= `@SerialName` von `Trigger.BatteryLevel`). */
        const val TRIGGER_ID = "battery_level"
    }
}
