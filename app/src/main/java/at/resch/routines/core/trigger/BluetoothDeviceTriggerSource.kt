package at.resch.routines.core.trigger

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import at.resch.routines.core.SystemEvent
import at.resch.routines.core.TriggerSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * Ein einzelnes Bluetooth-Verbindungsereignis: [deviceName] ist der Anzeigename
 * des Geräts (kann `null` sein, siehe Permission-Caveat unten), [connected]
 * unterscheidet Connect (`true`) von Disconnect (`false`).
 */
data class BluetoothConnectionEvent(
    val deviceName: String?,
    val connected: Boolean
)

/**
 * Test-Seam um den Framework-Zugriff auf Bluetooth-Verbindungsereignisse.
 * Liefert einen [Flow] von [BluetoothConnectionEvent]. Erlaubt MockK-Tests ohne
 * echtes Gerät / Broadcast.
 */
interface BluetoothConnectionProvider {
    /** Emittiert bei jedem Connect/Disconnect eines Bluetooth-Geräts ein Event. */
    val connectionEvents: Flow<BluetoothConnectionEvent>
}

/**
 * Produktiv-Implementierung über die ACL-Broadcasts
 * `android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED` /
 * `ACTION_ACL_DISCONNECTED`. Das beteiligte Gerät wird über
 * `EXTRA_DEVICE` ausgelesen.
 *
 * **Hinweis zur Namensauflösung:** Um eine Kollision mit dem Domain-Trigger
 * [at.resch.routines.domain.model.Trigger.BluetoothDevice] zu vermeiden, wird die
 * Framework-Klasse hier bewusst voll-qualifiziert als
 * `android.bluetooth.BluetoothDevice` referenziert (kein Import).
 *
 * **Permission-Caveat:** Das Auslesen von `name` erfordert auf API 31+
 * (`Build.VERSION_CODES.S`) die Runtime-Permission `BLUETOOTH_CONNECT`. Fehlt
 * sie, kann der Zugriff eine `SecurityException` werfen — wir kapseln das per
 * [runCatching] und emittieren dann `deviceName = null` (graceful, kein Crash).
 * Auf älteren API-Levels genügt die Manifest-Permission `BLUETOOTH`. Die
 * ACL-Broadcasts selbst benötigen keine zusätzliche Runtime-Permission.
 */
class BroadcastBluetoothConnectionProvider(
    context: Context
) : BluetoothConnectionProvider {

    private val appContext = context.applicationContext

    override val connectionEvents: Flow<BluetoothConnectionEvent> = callbackFlow {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                val connected = when (action) {
                    android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED -> true
                    android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
                    else -> return
                }
                val device = intent.getParcelableExtra<android.bluetooth.BluetoothDevice>(
                    android.bluetooth.BluetoothDevice.EXTRA_DEVICE
                )
                // .name kann ohne BLUETOOTH_CONNECT (API 31+) eine
                // SecurityException werfen → graceful null.
                val name = runCatching { device?.name }.getOrNull()
                trySend(BluetoothConnectionEvent(name, connected))
            }
        }
        val filter = IntentFilter().apply {
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        appContext.registerReceiver(receiver, filter)

        awaitClose { appContext.unregisterReceiver(receiver) }
    }
}

/**
 * [TriggerSource] für `"bluetooth_device"`. Beobachtet Bluetooth-Connect/
 * Disconnect über einen [BluetoothConnectionProvider] und emittiert je Ereignis
 * [SystemEvent.BluetoothDeviceChanged].
 *
 * Die eigentliche Match-Entscheidung (Gerätename-Vergleich, connected/
 * disconnected) liegt im Event ([SystemEvent.BluetoothDeviceChanged.matches]) —
 * diese Quelle kennt die Trigger-Geräte bewusst nicht und feuert für *alle*
 * bluetooth_device-Makros generisch.
 */
class BluetoothDeviceTriggerSource(
    private val provider: BluetoothConnectionProvider
) : TriggerSource {

    override val triggerId: String = TRIGGER_ID

    private var job: Job? = null

    override fun start(scope: CoroutineScope, emit: suspend (SystemEvent) -> Unit) {
        if (job?.isActive == true) return // idempotent
        job = scope.launch {
            provider.connectionEvents
                .collect { event ->
                    emit(SystemEvent.BluetoothDeviceChanged(event.deviceName, event.connected))
                }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        /** Trigger-Diskriminator (= `@SerialName` von `Trigger.BluetoothDevice`). */
        const val TRIGGER_ID = "bluetooth_device"
    }
}
