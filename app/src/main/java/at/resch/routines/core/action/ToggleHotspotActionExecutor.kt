package at.resch.routines.core.action

import android.os.Build
import at.resch.routines.core.cache.CapabilityCache
import at.resch.routines.core.shell.RootChecker
import at.resch.routines.core.shell.ShellExecutor
import at.resch.routines.domain.model.Action
import at.resch.routines.domain.model.ActionResult
import kotlinx.coroutines.delay

/**
 * Transaktions-Codes für die `IWifiManager`-Soft-AP-Methoden, die per
 * `service call wifi <code>` direkt aufgerufen werden.
 *
 * @property startCode `IWifiManager.startSoftAp(WifiConfiguration)` —
 *   `TRANSACTION_startSoftAp` (inkl. `FIRST_CALL_TRANSACTION`-Offset).
 * @property stopCode `IWifiManager.stopSoftAp()` — `TRANSACTION_stopSoftAp`.
 *
 * ## ACHTUNG — build-/versions-spezifisch
 * Diese Codes ergeben sich aus der Reihenfolge der Methoden in `IWifiManager$Stub`
 * und verschieben sich potenziell pro Android-Version, Security-Patch und ROM-Fork.
 * Es gibt **keine** stabile, geräteübergreifende Quelle. Nur API 29 ist auf einem
 * echten Gerät verifiziert (Samsung J5 / LineageOS / Android 10):
 * `startSoftAp = 42 (0x2a)`, `stopSoftAp = 43 (0x2b)`.
 */
data class SoftApServiceCallCodes(val startCode: Int, val stopCode: Int)

/**
 * Executor für `toggle_hotspot` — schaltet den WLAN-Tethering-Hotspot an/aus.
 *
 * Param-Vertrag:
 * - `enabled` : String "true"/"false", default `true` — Ziel-Zustand.
 * - `ssid`    : Optional, nur von der Strategie `cmd_softap_root` (A11+) genutzt.
 *   Default-Konstante [DEFAULT_SSID].
 *
 * ## Designziel
 * **Wichtigstes Ziel:** die im Gerät hinterlegte System-Hotspot-Konfiguration
 * (die in den Einstellungen gesetzte SSID/Passphrase) wiederverwenden.
 *
 * ## Fallback-Kette (deklarierte = Ausführungs-Reihenfolge, erste erfolgreiche gewinnt)
 * 1. `tethering_reflection` (**kein Root**) — Reflection-Bridge
 *    ([HotspotReflectionController]). Steht **zuerst**, weil ohne `su`-Popup.
 *    Auf ≤ API 29 für *enable* ohnehin aussichtslos (System-Signatur nötig), siehe
 *    [ReflectionHotspotController]; schlägt dort sauber fehl → Kette läuft weiter.
 * 2. `service_call_root` (**Root**) — `service call wifi <startCode> i32 0` /
 *    `service call wifi <stopCode>`. Ruft `startSoftAp(null)`/`stopSoftAp()` direkt
 *    auf. **Das ist der auf Android 10 (API 29) verifiziert funktionierende Pfad.**
 *    `i32 0` = null WifiConfiguration ⇒ es wird die hinterlegte System-Config genutzt.
 * 3. `cmd_softap_root` (**Root**) — A11+: `cmd wifi start-softap <ssid> open` /
 *    `cmd wifi stop-softap`. Saubere Alternative *nur ab API 30*; auf A10 existiert
 *    das Kommando nicht (non-zero exit) → Failure → Kette läuft weiter.
 *
 * ### Warum `service_call` vor `cmd_softap`?
 * `service_call_root` ist der verifiziert funktionierende A10-Pfad; `cmd wifi
 * start-softap` existiert erst ab A11. Auf A11+ darf `cmd_softap` ruhig nachrücken
 * (saubere offizielle Alternative). Diese Reihenfolge weicht bewusst von der
 * früheren Doku ab.
 *
 * ### Entfernt: `svc wifi tether enable|disable`
 * `svc wifi` kennt **nur** `enable|disable`, **nicht** `tether`. Es gibt nur die
 * Usage aus und beendet sich mit **exit 0** → die alte `svc_tether_root`-Strategie
 * meldete *immer* falschen Erfolg (No-Op) und vergiftete den [CapabilityCache].
 * Deshalb ersatzlos gestrichen. **Lehre: exit 0 ist kein gültiges Hotspot-Erfolgssignal.**
 *
 * ## Erfolgserkennung — der eigentliche Fix
 * `service call` (und auch viele ROM-Varianten) liefern **exit 0 selbst bei No-Op**.
 * Daher gilt für jede Root-Strategie: nach dem Absetzen wird der **echte**
 * System-Zustand via [verifyHotspotActive] geprüft (`dumpsys connectivity`).
 * Nur wenn der tatsächliche AP-Zustand dem Ziel entspricht → Success.
 *
 * Ohne Root und ohne erlaubten Non-Root-Pfad → klare [ActionResult.Failure]
 * (nie eine Exception, siehe [StrategyActionExecutor]).
 *
 * @param rootChecker Geteilter Root-Status-Checker (DI).
 * @param capabilityCache Merkt die erfolgreiche Strategie pro Gerät (DI).
 * @param shellExecutor Test-Seam (default = zustandslose [ShellExecutor]).
 * @param reflectionController Test-Seam für den root-freien Reflection-Pfad
 *   (default = [NoOpHotspotReflectionController], degradiert graceful).
 * @param serviceCallCodes Liefert die [SoftApServiceCallCodes] für die laufende
 *   Android-Version (null = unbekannt → Strategie meldet sauber Failure).
 *   Injizierbar für Tests.
 */
class ToggleHotspotActionExecutor(
    rootChecker: RootChecker,
    capabilityCache: CapabilityCache,
    private val shellExecutor: ShellExecutor = ShellExecutor(),
    private val reflectionController: HotspotReflectionController = NoOpHotspotReflectionController,
    private val serviceCallCodes: () -> SoftApServiceCallCodes? = { defaultSoftApServiceCallCodes() }
) : StrategyActionExecutor(rootChecker, capabilityCache) {

    override val type: String = "toggle_hotspot"

    override fun strategies(action: Action): List<Strategy> {
        val enabled = action.params["enabled"]?.toBooleanStrictOrNull() ?: true
        val state = if (enabled) "enable" else "disable"

        return listOf(
            // 1) Non-Root zuerst: kein su-Popup, nutzt System-Config.
            strategy(id = "tethering_reflection", requiresRoot = false) {
                reflectionController.setHotspotEnabled(enabled)
            },
            // 2) Root, System-Config, version-spezifische Transaktions-Codes.
            //    Auf A10 verifiziert funktionierend.
            strategy(id = "service_call_root", requiresRoot = true) {
                // Idempotenz-Vorab-Check: ist der AP schon im Zielzustand, NICHT erneut
                // `startSoftAp` aufrufen — das würde den laufenden AP neu starten
                // (Übergangszustand → Verify scheitert). Echtes No-Op → Success.
                alreadyInState("service_call_root", state, enabled)?.let { return@strategy it }

                val codes = serviceCallCodes()
                    ?: return@strategy ActionResult.Failure(
                        "Kein bekannter service-call-Code für API ${Build.VERSION.SDK_INT}"
                    )
                val command = if (enabled) {
                    // startSoftAp(null) — i32 0 = null WifiConfiguration = System-Config.
                    "service call wifi ${codes.startCode} i32 0"
                } else {
                    // stopSoftAp() — keine Argumente.
                    "service call wifi ${codes.stopCode}"
                }
                val result = shellExecutor.execute(command, ShellExecutor.Mode.ROOT, TIMEOUT_MS)
                // service call: exit 0 auch bei No-Op → das Parcel-Boolean ist nur ein
                // *Sekundär*-Signal. Wahrheitsquelle ist der real verifizierte AP-Zustand
                // ([verifyOrFallback]). Wichtig für Idempotenz: `startSoftAp(null)` liefert
                // `false`, wenn der AP **bereits** läuft (Doppel-Trigger) — dann ist das Ziel
                // trotzdem erreicht und die Verifikation meldet korrekt Success.
                val parcelTrue = result.isSuccess && result.stdout.contains(PARCEL_TRUE_TOKEN)
                verifyOrFallback(
                    id = "service_call_root",
                    state = state,
                    desired = enabled,
                    commandSucceeded = parcelTrue
                )
            },
            // 3) Root, A11+: cmd wifi start-softap. Eigene Custom-SSID (open).
            strategy(id = "cmd_softap_root", requiresRoot = true) {
                alreadyInState("cmd_softap_root", state, enabled)?.let { return@strategy it }

                val ssid = action.params["ssid"]?.takeIf { it.isNotBlank() } ?: DEFAULT_SSID
                val command =
                    if (enabled) "cmd wifi start-softap $ssid open" else "cmd wifi stop-softap"
                val result = shellExecutor.execute(command, ShellExecutor.Mode.ROOT, TIMEOUT_MS)
                if (!result.isSuccess) {
                    return@strategy ActionResult.Failure(
                        "cmd_softap_root exit=${result.exitCode}" +
                            if (result.stderr.isNotBlank()) ", stderr=${result.stderr}" else ""
                    )
                }
                verifyOrFallback(
                    id = "cmd_softap_root",
                    state = state,
                    desired = enabled,
                    commandSucceeded = true
                )
            }
        )
    }

    /**
     * Gemeinsame Verify-und-Mappe-Logik für alle Root-Strategien, die *behaupten*
     * erfolgreich gewesen zu sein. Prüft den **tatsächlichen** AP-Zustand via
     * [verifyHotspotActive] und entscheidet:
     * - Verify == [desired] → [ActionResult.Success].
     * - Verify != null und != [desired] → [ActionResult.Failure] (Kommando abgesetzt,
     *   aber Zustand stimmt nicht — verhindert den falschen Erfolg, der den Bug auslöste).
     * - Verify == null (unbekannt / nicht parsebar) → **Fallback** auf das
     *   strategie-eigene Erfolgssignal ([commandSucceeded]). So regressieren wir nicht
     *   auf Geräten, deren `dumpsys`-Ausgabe anders aussieht. Dokumentiert als
     *   bewusster Kompromiss (best effort).
     */
    private suspend fun verifyOrFallback(
        id: String,
        state: String,
        desired: Boolean,
        commandSucceeded: Boolean
    ): ActionResult {
        val actual = verifyHotspotActive(desired)
        return when {
            actual == desired -> ActionResult.Success("Hotspot $state ($id, verifiziert)")
            actual != null -> ActionResult.Failure(
                "$id: Befehl abgesetzt, aber AP-Zustand=${if (actual) "aktiv" else "inaktiv"}"
            )
            // Unbekannt → strategie-eigenes Signal.
            commandSucceeded -> ActionResult.Success(
                "Hotspot $state ($id, Zustand nicht verifizierbar)"
            )
            else -> ActionResult.Failure("$id: Kommando-Signal negativ, Zustand unbekannt")
        }
    }

    /**
     * Verifiziert den **tatsächlichen** System-Hotspot-Zustand via Root-`dumpsys`
     * und **pollt** dabei: `startSoftAp`/`stopSoftAp` sind **asynchron** — das
     * Kommando kehrt sofort mit `true` zurück, aber das Wi-Fi-Tether-Interface
     * wechselt erst 1-2 s später nach `TetheredState`/`AvailableState`. Ein
     * einmaliges Lesen direkt nach dem Befehl liest daher noch den alten Zustand
     * (das war der Bug: „Befehl abgesetzt, aber AP-Zustand=inaktiv", obwohl der AP
     * kurz darauf hochkam).
     *
     * Pollt bis zu [VERIFY_ATTEMPTS]× im Abstand [VERIFY_POLL_INTERVAL_MS] und
     * kehrt **sofort** zurück, sobald [desired] erreicht ist. Rückgabe ist der
     * **tatsächliche** AP-Zustand (nicht „erreicht ja/nein"), damit [verifyOrFallback]
     * weiter `actual == desired` prüfen kann:
     * - `true`  → AP aktiv (getethered),
     * - `false` → AP inaktiv,
     * - `null`  → nie parsebar ("unbekannt").
     * Sobald [desired] beobachtet wird, wird sofort zurückgekehrt; sonst der zuletzt
     * definitiv beobachtete Zustand (oder `null`).
     *
     * Da der App das `DUMP`-Permission fehlt, läuft `dumpsys` zwingend in
     * [ShellExecutor.Mode.ROOT]. Best effort: Ausführungs-/Parse-Fehler zählen als
     * "unbekannt" für diesen Versuch.
     */
    private suspend fun verifyHotspotActive(desired: Boolean): Boolean? {
        var lastKnown: Boolean? = null
        repeat(VERIFY_ATTEMPTS) { attempt ->
            val state = readHotspotState()
            if (state != null) {
                lastKnown = state
                // Ziel erreicht → tatsächlichen Zustand (== desired) sofort zurückgeben.
                if (state == desired) return state
            }
            if (attempt < VERIFY_ATTEMPTS - 1) delay(VERIFY_POLL_INTERVAL_MS)
        }
        // Ziel nie erreicht → letzter definiter Zustand (oder null = unbekannt).
        return lastKnown
    }

    /**
     * **Einmaliges** Lesen des realen AP-Zustands via Root-`dumpsys connectivity`:
     * `true` = aktiv (TetheredState), `false` = inaktiv (AvailableState), `null` =
     * nicht parsebar/Fehler. Baustein für [verifyHotspotActive] (Polling) und den
     * Idempotenz-Vorab-Check [alreadyInState].
     */
    private suspend fun readHotspotState(): Boolean? {
        // WICHTIG: NICHT das volle `dumpsys connectivity` (>64 KB) holen — der
        // [ShellExecutor] draint stdout erst nach `waitFor`, sodass die Pipe vollläuft,
        // der Prozess blockiert und jeder Aufruf in den Timeout rennt (~5 s statt ~0,1 s).
        // Server-seitig per grep auf EINE kurze Zeile reduzieren → kein Deadlock.
        val result = shellExecutor.execute(
            STATE_PROBE_COMMAND,
            ShellExecutor.Mode.ROOT,
            VERIFY_TIMEOUT_MS
        )
        if (!result.isSuccess) return null
        val out = result.stdout
        return when {
            // Aktiv: Wi-Fi-Iface (wlanN) ist im TetheredState.
            TETHERED_WIFI_REGEX.containsMatchIn(out) -> true
            // Inaktiv: Wi-Fi-Iface ist im AvailableState (bereit, nicht getethered).
            AVAILABLE_WIFI_REGEX.containsMatchIn(out) -> false
            // Weder noch parsebar → unbekannt.
            else -> null
        }
    }

    /**
     * Idempotenz-Vorab-Check für Root-Strategien: liest den aktuellen Zustand
     * **einmal** und liefert [ActionResult.Success], falls der AP bereits exakt im
     * Zielzustand [desired] ist — dann darf das eigentliche Kommando **nicht**
     * laufen (ein erneutes `startSoftAp`/`stop` würde den AP neu starten und einen
     * Übergangszustand erzeugen, der die Verifikation scheitern lässt). Bei
     * abweichendem oder unbekanntem Zustand → `null` (Aufrufer fährt normal fort).
     */
    private suspend fun alreadyInState(id: String, state: String, desired: Boolean): ActionResult? =
        if (readHotspotState() == desired) {
            ActionResult.Success("Hotspot $state ($id, bereits im Zielzustand)")
        } else {
            null
        }

    companion object {
        private const val TIMEOUT_MS = 10_000L
        private const val VERIFY_TIMEOUT_MS = 5_000L

        /**
         * Polling für [verifyHotspotActive]: der async Soft-AP-Hochlauf braucht
         * typ. 1-2 s. [VERIFY_ATTEMPTS] × [VERIFY_POLL_INTERVAL_MS] ≈ 6 s Obergrenze,
         * mit Sofort-Rückkehr sobald der Zielzustand erreicht ist.
         */
        private const val VERIFY_ATTEMPTS = 10
        private const val VERIFY_POLL_INTERVAL_MS = 600L

        /** Default-SSID der Custom-Soft-AP-Strategie (A11+). */
        private const val DEFAULT_SSID = "Routines"

        /**
         * Das Reply-Word für `true` in einem `Parcel.writeNoException()` +
         * `writeInt(1)`-Reply, wie `service call` es als Hex-Dump ausgibt.
         */
        private const val PARCEL_TRUE_TOKEN = "00000001"

        /**
         * Billige Zustands-Sonde: reduziert `dumpsys connectivity` server-seitig auf
         * **eine** kurze Zeile (`wlanN - TetheredState`/`AvailableState`), um den
         * Pipe-Deadlock bei großer Ausgabe zu vermeiden (siehe [readHotspotState]).
         * `grep -m1` beendet früh; bei keinem Match exit≠0 → Zustand "unbekannt".
         */
        private const val STATE_PROBE_COMMAND =
            "dumpsys connectivity 2>/dev/null | grep -m1 -E \"wlan[0-9]+ - (Tethered|Available)State\""

        /** `wlan0 - TetheredState` etc. → Wi-Fi-Iface ist aktiv getethered. */
        private val TETHERED_WIFI_REGEX = Regex("""wlan\d+ - TetheredState""")

        /** `wlan0 - AvailableState` → Wi-Fi-Iface bereit, aber nicht getethered. */
        private val AVAILABLE_WIFI_REGEX = Regex("""wlan\d+ - AvailableState""")

        /**
         * Verifizierte [SoftApServiceCallCodes] je [Build.VERSION.SDK_INT].
         *
         * ## ACHTUNG — nur API 29 ist auf echtem Gerät verifiziert
         * Die Codes sind build-spezifisch (siehe [SoftApServiceCallCodes]). Für alle
         * nicht verifizierten Versionen wird bewusst `null` zurückgegeben → die
         * `service_call_root`-Strategie meldet dann sauber Failure und die
         * Fallback-Kette läuft weiter, statt ein falsches Kommando abzusetzen.
         *
         * - **API 29 (Android 10):** `startSoftAp = 42`, `stopSoftAp = 43`
         *   (verifiziert: Samsung J5 / LineageOS).
         * - alle anderen: `null` (unbekannt).
         */
        private fun defaultSoftApServiceCallCodes(): SoftApServiceCallCodes? =
            when (Build.VERSION.SDK_INT) {
                29 -> SoftApServiceCallCodes(startCode = 42, stopCode = 43)
                else -> null
            }
    }
}
