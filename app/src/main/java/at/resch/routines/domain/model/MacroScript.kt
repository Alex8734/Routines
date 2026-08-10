package at.resch.routines.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Domain-Modell eines Makros. Single source of truth ist der rohe JSON-String
 * in der Room-DB (siehe [at.resch.routines.data.MacroEntity]); dieses Modell ist
 * die geparste Repräsentation, mit der Engine, Evaluator und UI arbeiten.
 *
 * Beispiel-JSON:
 * ```json
 * {
 *   "id": "macro_001",
 *   "name": "Start-Routine",
 *   "enabled": true,
 *   "trigger": { "type": "on_startup" },
 *   "actions": [
 *     { "type": "log", "params": { "message": "Gerät gestartet" } }
 *   ]
 * }
 * ```
 */
@Serializable
data class MacroScript(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val trigger: Trigger,
    val actions: List<Action>
)

/**
 * Polymorpher Trigger-Vertrag. Der JSON-Diskriminator ist das Feld `"type"`
 * (kotlinx-serialization Default), die [SerialName]-Werte definieren die
 * stabilen Trigger-IDs. Es gibt bewusst **keine** zusätzliche `type`-Property —
 * sie würde mit dem Klassen-Diskriminator kollidieren.
 */
@Serializable
sealed class Trigger {

    @Serializable
    @SerialName("on_startup")
    data object OnStartup : Trigger()

    @Serializable
    @SerialName("sim_card_data_connected")
    data object SimCardDataConnected : Trigger()

    /**
     * Feuert abhängig vom Akkustand. Im Gegensatz zu den param-losen `data object`-
     * Triggern trägt dieser Trigger Konfiguration und ist daher eine `data class`.
     *
     * - [level] : Schwellwert in Prozent (0–100).
     * - [mode]  : `"below"` → feuert, wenn der Akkustand **unter** [level] liegt;
     *             `"above"` → feuert, wenn der Akkustand **über** [level] liegt.
     *             Andere Werte matchen nie (defensive Auswertung in `matches`).
     */
    @Serializable
    @SerialName("battery_level")
    data class BatteryLevel(
        val level: Int,
        val mode: String = MODE_BELOW
    ) : Trigger() {
        companion object {
            const val MODE_BELOW = "below"
            const val MODE_ABOVE = "above"
        }
    }

    /**
     * Einfacher periodischer Zeitplan: feuert alle [intervalMinutes] Minuten,
     * solange die Engine läuft.
     *
     * Bewusst minimal für diese Batch: kein Cron, keine persistente/exakte
     * Planung über AlarmManager (würde `SCHEDULE_EXACT_ALARM` erfordern). Die
     * Quelle ist ein reiner Coroutine-Ticker — übersteht keinen Prozess-Tod
     * zwischen den Ticks. Exakte/persistente Planung ist ein späteres Enhancement.
     */
    @Serializable
    @SerialName("time_schedule")
    data class TimeSchedule(
        val intervalMinutes: Int
    ) : Trigger()

    /**
     * Periodischer Intervall-Trigger: feuert alle [intervalSeconds] Sekunden,
     * solange die Engine läuft. Im Gegensatz zu [TimeSchedule] wird das Intervall
     * **pro Makro** ausgewertet — die Quelle betreibt je Intervallwert einen
     * eigenen Ticker, das Event trägt den Intervallwert und matcht nur Makros mit
     * genau diesem Wert.
     *
     * - [intervalSeconds] : Abstand zwischen zwei Ausführungen in Sekunden.
     *   Werte unterhalb von [MIN_INTERVAL_SECONDS] werden von der Quelle auf
     *   dieses Minimum angehoben (Schutz vor Dauerfeuer, z. B. bei HTTP-Polling).
     * - [runOnStart]      : `true` → feuert zusätzlich einmal sofort beim Start
     *   der Quelle (praktisch für Polling, damit nicht erst ein volles Intervall
     *   gewartet wird). Default `false`.
     *
     * Wie [TimeSchedule] ein reiner Coroutine-Ticker: kein AlarmManager, keine
     * Persistenz über Prozess-Tod hinweg. Läuft im Foreground-Service-Scope.
     */
    @Serializable
    @SerialName("interval")
    data class Interval(
        val intervalSeconds: Int,
        val runOnStart: Boolean = false
    ) : Trigger() {
        companion object {
            /** Untergrenze, auf die zu kleine/ungültige Intervalle geklemmt werden. */
            const val MIN_INTERVAL_SECONDS = 5
        }
    }

    /**
     * Feuert abhängig von der aktuell verbundenen WLAN-SSID.
     *
     * - [ssid] : Name (SSID) des WLANs, auf das reagiert werden soll.
     * - [mode] : `"connected"`    → feuert, wenn das Gerät mit genau dieser
     *            [ssid] verbunden ist;
     *            `"disconnected"` → feuert, wenn das WLAN getrennt ist (kein
     *            WLAN verbunden bzw. SSID unbekannt). Andere Werte matchen nie
     *            (defensive Auswertung in `matches`).
     */
    @Serializable
    @SerialName("wifi_ssid")
    data class WifiSsid(
        val ssid: String,
        val mode: String = MODE_CONNECTED
    ) : Trigger() {
        companion object {
            const val MODE_CONNECTED = "connected"
            const val MODE_DISCONNECTED = "disconnected"
        }
    }

    /**
     * Feuert abhängig vom Verbindungsstatus eines Bluetooth-Geräts.
     *
     * - [deviceName] : Anzeigename des Bluetooth-Geräts (Framework-`name`).
     * - [mode]       : `"connected"`    → feuert, wenn das Gerät verbindet;
     *                  `"disconnected"` → feuert, wenn das Gerät trennt.
     *                  Andere Werte matchen nie (defensive Auswertung in `matches`).
     */
    @Serializable
    @SerialName("bluetooth_device")
    data class BluetoothDevice(
        val deviceName: String,
        val mode: String = MODE_CONNECTED
    ) : Trigger() {
        companion object {
            const val MODE_CONNECTED = "connected"
            const val MODE_DISCONNECTED = "disconnected"
        }
    }
}

/**
 * Eine auszuführende Action. Bewusst nicht-polymorph: [type] ist eine schlichte
 * String-ID, [params] ein generischer String-Map-Bag. Konkrete Executoren
 * interpretieren die Params (Phase 3+).
 */
@Serializable
data class Action(
    val type: String,
    val params: Map<String, String> = emptyMap()
)
