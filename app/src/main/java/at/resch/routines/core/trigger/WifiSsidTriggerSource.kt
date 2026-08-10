package at.resch.routines.core.trigger

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
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
 * Test-Seam um den Framework-Zugriff auf die aktuell verbundene WLAN-SSID.
 * Liefert einen [Flow] der verbundenen SSID (oder `null`, wenn nicht verbunden /
 * unbekannt). Erlaubt MockK-Tests ohne echtes Gerät / Broadcast.
 */
interface WifiSsidProvider {
    /**
     * Emittiert bei jeder WLAN-Statusänderung die aktuell verbundene SSID, oder
     * `null`, wenn kein WLAN verbunden ist bzw. die SSID nicht ermittelt werden
     * kann.
     */
    val connectedSsid: Flow<String?>
}

/**
 * Produktiv-Implementierung über den Broadcast
 * [WifiManager.NETWORK_STATE_CHANGED_ACTION]. Bei jedem Event wird die aktuelle
 * SSID über [WifiManager.getConnectionInfo] gelesen und die umschließenden
 * Anführungszeichen entfernt. Der Initialwert wird einmalig beim Registrieren
 * emittiert.
 *
 * **Permission-Caveat:** Das Auslesen der SSID erfordert auf API 27+
 * `ACCESS_FINE_LOCATION` **und** aktivierte Standortdienste. Fehlt eine der
 * beiden Voraussetzungen, liefert das Framework den Platzhalter
 * [WifiManager.UNKNOWN_SSID] (`"<unknown ssid>"`) statt der echten SSID — in
 * diesem Fall emittieren wir bewusst `null` (graceful, kein Crash). Der Broadcast
 * `NETWORK_STATE_CHANGED_ACTION` selbst benötigt keine Runtime-Permission.
 */
class BroadcastWifiSsidProvider(
    context: Context
) : WifiSsidProvider {

    private val appContext = context.applicationContext

    override val connectedSsid: Flow<String?> = callbackFlow {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                trySend(currentSsid())
            }
        }
        val filter = IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        appContext.registerReceiver(receiver, filter)
        // Aktuellen Stand einmalig beim Registrieren liefern.
        trySend(currentSsid())

        awaitClose { appContext.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    /**
     * Liest die aktuell verbundene SSID aus dem [WifiManager] und normalisiert
     * sie: umschließende Anführungszeichen werden entfernt; leere SSID oder der
     * Platzhalter [WifiManager.UNKNOWN_SSID] werden zu `null`.
     */
    @Suppress("DEPRECATION")
    private fun currentSsid(): String? {
        val wifiManager = appContext.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val raw = wifiManager.connectionInfo?.ssid ?: return null
        if (raw.isEmpty() || raw == WifiManager.UNKNOWN_SSID) return null
        val stripped = raw.removeSurrounding("\"")
        if (stripped.isEmpty() || stripped == WifiManager.UNKNOWN_SSID) return null
        return stripped
    }
}

/**
 * [TriggerSource] für `"wifi_ssid"`. Beobachtet die verbundene WLAN-SSID über
 * einen [WifiSsidProvider] und emittiert bei jeder Änderung
 * [SystemEvent.WifiSsidChanged].
 *
 * Die eigentliche Match-Entscheidung (connected/disconnected, SSID-Vergleich)
 * liegt im Event ([SystemEvent.WifiSsidChanged.matches]) — diese Quelle kennt die
 * Trigger-SSIDs bewusst nicht und feuert für *alle* wifi_ssid-Makros generisch.
 */
class WifiSsidTriggerSource(
    private val provider: WifiSsidProvider
) : TriggerSource {

    override val triggerId: String = TRIGGER_ID

    private var job: Job? = null

    override fun start(scope: CoroutineScope, emit: suspend (SystemEvent) -> Unit) {
        if (job?.isActive == true) return // idempotent
        job = scope.launch {
            provider.connectedSsid
                .collect { ssid -> emit(SystemEvent.WifiSsidChanged(ssid)) }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        /** Trigger-Diskriminator (= `@SerialName` von `Trigger.WifiSsid`). */
        const val TRIGGER_ID = "wifi_ssid"
    }
}
