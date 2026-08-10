package at.resch.routines.core

import at.resch.routines.domain.model.Trigger

/**
 * Laufzeit-Systemereignisse, die durch den [EventBus] fließen und vom
 * [MacroEvaluator] gegen die [Trigger] aktiver Makros gematcht werden.
 *
 * **Verortung:** Bewusst in `core/` (nicht `domain/`). [SystemEvent] ist ein
 * reines Laufzeit-/Engine-Konstrukt — es wird weder persistiert noch von der UI
 * editiert. Die persistierte, vom User editierbare Gegenseite ist [Trigger] im
 * Domain-Layer. Die Abbildung Event→Trigger geschieht über [matches].
 *
 * **Erweiterbarkeit:** Neue Ereignistypen (Phase 4: battery, geofence, wifi …)
 * werden als zusätzliche Unterklassen ergänzt; jede liefert über [matches] selbst
 * die Regel, welcher [Trigger] auf sie passt. Der Evaluator bleibt unverändert.
 */
sealed interface SystemEvent {

    /**
     * Entscheidet, ob dieses Ereignis den gegebenen [trigger] auslöst.
     * Die Logik lebt am Event, damit der [MacroEvaluator] generisch bleibt
     * (Open/Closed: neuer Event-Typ = neue Klasse, kein Evaluator-Edit).
     */
    fun matches(trigger: Trigger): Boolean

    /** Gerät wurde hochgefahren → mappt auf [Trigger.OnStartup] / `"on_startup"`. */
    data object Startup : SystemEvent {
        override fun matches(trigger: Trigger): Boolean = trigger is Trigger.OnStartup
    }

    /**
     * Mobile-Daten-Verbindung wurde hergestellt → mappt auf
     * [Trigger.SimCardDataConnected] / `"sim_card_data_connected"`.
     */
    data object CellularDataConnected : SystemEvent {
        override fun matches(trigger: Trigger): Boolean = trigger is Trigger.SimCardDataConnected
    }

    /**
     * Akkustand hat sich geändert ([level] in Prozent, 0–100). Matcht
     * [Trigger.BatteryLevel], wenn die Schwellwert-Bedingung erfüllt ist:
     * - `mode == "below"` → [level] `< trigger.level`
     * - `mode == "above"` → [level] `> trigger.level`
     * - unbekannter Mode → kein Match.
     *
     * Die Schwellwert-Logik lebt bewusst hier (am Event), damit der Evaluator
     * generisch bleibt und die Quelle bei jeder Akku-Änderung emittieren kann.
     */
    data class BatteryChanged(val level: Int) : SystemEvent {
        override fun matches(trigger: Trigger): Boolean {
            if (trigger !is Trigger.BatteryLevel) return false
            return when (trigger.mode) {
                Trigger.BatteryLevel.MODE_BELOW -> level < trigger.level
                Trigger.BatteryLevel.MODE_ABOVE -> level > trigger.level
                else -> false
            }
        }
    }

    /**
     * Periodischer Zeit-Tick der [TimeScheduleTriggerSource]. Matcht jeden
     * [Trigger.TimeSchedule] — das Intervall wird in der Quelle abgebildet
     * (eine Quelle-Instanz pro Intervall), nicht hier nachgeprüft.
     */
    data object TimeTick : SystemEvent {
        override fun matches(trigger: Trigger): Boolean = trigger is Trigger.TimeSchedule
    }

    /**
     * Die aktuell verbundene WLAN-SSID hat sich geändert. [ssid] ist non-null,
     * wenn das Gerät aktuell mit diesem WLAN verbunden ist; `null` bedeutet
     * WLAN getrennt bzw. SSID unbekannt (z. B. fehlende Location-Permission auf
     * API 27+ → Framework liefert `<unknown ssid>`).
     *
     * Matcht [Trigger.WifiSsid]:
     * - `mode == "connected"`    → [ssid] `== trigger.ssid`
     * - `mode == "disconnected"` → [ssid] `== null`
     * - unbekannter Mode → kein Match.
     *
     * Die Match-Logik lebt bewusst hier (am Event), damit der Evaluator generisch
     * bleibt und die Quelle bei jeder SSID-Änderung generisch emittieren kann.
     */
    data class WifiSsidChanged(val ssid: String?) : SystemEvent {
        override fun matches(trigger: Trigger): Boolean {
            if (trigger !is Trigger.WifiSsid) return false
            return when (trigger.mode) {
                Trigger.WifiSsid.MODE_CONNECTED -> ssid == trigger.ssid
                Trigger.WifiSsid.MODE_DISCONNECTED -> ssid == null
                else -> false
            }
        }
    }

    /**
     * Der Verbindungsstatus eines Bluetooth-Geräts hat sich geändert.
     * [deviceName] ist der Anzeigename (kann `null` sein, wenn er ohne
     * `BLUETOOTH_CONNECT` auf API 31+ nicht lesbar war), [connected] gibt an, ob
     * das Gerät verbunden (`true`) oder getrennt (`false`) wurde.
     *
     * Matcht [Trigger.BluetoothDevice], wenn der Gerätename übereinstimmt und der
     * Trigger-Mode zum [connected]-Status passt:
     * - `mode == "connected"`    erfordert [connected] `== true`
     * - `mode == "disconnected"` erfordert [connected] `== false`
     * - unbekannter Mode → kein Match.
     */
    data class BluetoothDeviceChanged(
        val deviceName: String?,
        val connected: Boolean
    ) : SystemEvent {
        override fun matches(trigger: Trigger): Boolean {
            if (trigger !is Trigger.BluetoothDevice) return false
            if (deviceName != trigger.deviceName) return false
            return when (trigger.mode) {
                Trigger.BluetoothDevice.MODE_CONNECTED -> connected
                Trigger.BluetoothDevice.MODE_DISCONNECTED -> !connected
                else -> false
            }
        }
    }
}
