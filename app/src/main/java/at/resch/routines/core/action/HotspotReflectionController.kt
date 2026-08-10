package at.resch.routines.core.action

import android.content.Context
import android.os.Build
import at.resch.routines.domain.model.ActionResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Test-Seam für den reflexionsbasierten, **root-freien** Hotspot-Pfad.
 *
 * Die einzige Strategie der [ToggleHotspotActionExecutor]-Kette, die ganz ohne
 * `su` auskommt, hängt an Framework-Reflection auf `@hide`/`@SystemApi`-Methoden.
 * Damit diese Logik auf der JVM (ohne Android-Framework) mockbar bleibt, wird sie
 * hinter dieses Interface gezogen — gleiche Philosophie wie die übrigen
 * Prozess-/Intent-Seams.
 *
 * **MUSS NIE werfen** — immer [ActionResult.Success]/[ActionResult.Failure].
 */
interface HotspotReflectionController {
    /**
     * Schaltet den **System**-Hotspot (mit der im Gerät hinterlegten AP-/SSID-
     * Konfiguration) ohne Root, via Reflection auf
     * `TetheringManager` (API 30+) bzw. `ConnectivityManager.startTethering`
     * (API 24-29). Benötigt `WRITE_SETTINGS`. **Wirft nicht** — Rückgabe
     * Success/Failure.
     */
    suspend fun setHotspotEnabled(enabled: Boolean): ActionResult
}

/**
 * Sicherer Default, wenn kein produktiver Controller injiziert wurde: degradiert
 * graceful, statt zu werfen oder zu crashen. Der defaulted Konstruktor-Parameter
 * von [ToggleHotspotActionExecutor] nutzt dieses Objekt, damit named-arg-
 * Konstruktion (Tests) ohne Reflection-Abhängigkeit kompiliert.
 */
object NoOpHotspotReflectionController : HotspotReflectionController {
    override suspend fun setHotspotEnabled(enabled: Boolean): ActionResult =
        ActionResult.Failure("Reflection-Controller nicht konfiguriert")
}

/**
 * Produktive Reflection-Bridge auf die nicht-öffentlichen Tethering-APIs.
 *
 * ## Sehr wichtige Versions-Caveats (best effort, von Unit-Tests **nicht** abgedeckt)
 * Dieser Pfad ist absichtlich hinter [HotspotReflectionController] isoliert, weil
 * er auf fragiler Reflection beruht:
 * - **API 30+**: `context.getSystemService(TETHERING_SERVICE)` →
 *   `android.net.TetheringManager.startTethering(TetheringRequest, Executor,
 *   StartTetheringCallback)` bzw. `stopTethering(int)`. Beides `@SystemApi`/`@hide`.
 * - **API 24-29**: nur **disable** via `stopTethering(int)` (kein Callback nötig).
 *   **enable ist hier nicht möglich**: `OnStartTetheringCallback` ist eine
 *   *abstrakte Klasse*, kein Interface → [Proxy.newProxyInstance] wirft
 *   `IllegalArgumentException`; außerdem braucht Non-Root-Tethering auf ≤29 eine
 *   System-Signatur. Der enable-Zweig liefert daher sofort einen klaren Failure.
 *   `TETHERING_WIFI = 0`. Beides `@SystemApi`/`@hide`.
 * - Ab **API 28** greifen die **Non-SDK-Restriktionen** (graylist/blacklist) —
 *   der Zugriff kann ohne Vorwarnung mit `NoSuchMethodException`/
 *   `SecurityException` scheitern und variiert je ROM/Patchlevel.
 * - Selbst bei erfolgreichem Aufruf benötigt der System-Hotspot ggf.
 *   System-/Carrier-Signatur — der Callback liefert dann onTetheringFailed.
 *
 * Deshalb: Alles in try/catch, jeder Fehler → [ActionResult.Failure] mit cause.
 */
class ReflectionHotspotController(
    private val context: Context
) : HotspotReflectionController {

    override suspend fun setHotspotEnabled(enabled: Boolean): ActionResult = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            toggleViaTetheringManager(enabled)
        } else {
            toggleViaConnectivityManager(enabled)
        }
    } catch (e: Throwable) {
        ActionResult.Failure(
            "Hotspot-Reflection fehlgeschlagen: ${e.javaClass.simpleName}: ${e.message}",
            cause = e
        )
    }

    /** API 30+: TetheringManager.startTethering / stopTethering. */
    private suspend fun toggleViaTetheringManager(enabled: Boolean): ActionResult {
        val tm = context.getSystemService("tethering")
            ?: return ActionResult.Failure("TetheringManager nicht verfügbar")
        val tmClass = tm.javaClass

        if (!enabled) {
            val stop = tmClass.getMethod("stopTethering", Int::class.javaPrimitiveType)
            stop.invoke(tm, TETHERING_WIFI)
            return ActionResult.Success("Hotspot disable (tethering_reflection)")
        }

        // TetheringRequest via Builder: new TetheringRequest.Builder(TETHERING_WIFI).build()
        val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
        val builder = builderClass
            .getConstructor(Int::class.javaPrimitiveType)
            .newInstance(TETHERING_WIFI)
        val request = builderClass.getMethod("build").invoke(builder)
        val requestClass = Class.forName("android.net.TetheringManager\$TetheringRequest")

        val callbackClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")

        return suspendWithTimeout { cont ->
            val handler = InvocationHandler { _, method, args ->
                when (method.name) {
                    "onTetheringStarted" ->
                        cont(ActionResult.Success("Hotspot enable (tethering_reflection)"))
                    "onTetheringFailed" -> {
                        val code = args?.firstOrNull()
                        cont(ActionResult.Failure("onTetheringFailed code=$code"))
                    }
                }
                null
            }
            val callback = Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass),
                handler
            )
            val executor = Executor { it.run() }
            val start = tmClass.getMethod(
                "startTethering",
                requestClass,
                Executor::class.java,
                callbackClass
            )
            start.invoke(tm, request, executor, callback)
        }
    }

    /**
     * API 24-29: ConnectivityManager.stopTethering (enable nicht möglich).
     *
     * **enable** ist über diesen Pfad **nicht** umsetzbar:
     * `ConnectivityManager.OnStartTetheringCallback` ist eine **abstrakte Klasse**,
     * keine Interface — [Proxy.newProxyInstance] unterstützt aber ausschließlich
     * Interfaces und wirft sonst `IllegalArgumentException`. Zudem benötigt
     * Non-Root-Tethering auf ≤ API 29 ohnehin eine System-Signatur (selbst uid 0
     * wurde `getWifiApConfiguration` verweigert) → dieser Pfad ist für *enable*
     * grundsätzlich aussichtslos. Daher sofortiger, klarer Failure (kein Proxy-Versuch).
     *
     * **disable** dagegen ist machbar: `stopTethering(int)` braucht keinen Callback.
     */
    private fun toggleViaConnectivityManager(enabled: Boolean): ActionResult {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            ?: return ActionResult.Failure("ConnectivityManager nicht verfügbar")
        val cmClass = cm.javaClass

        if (!enabled) {
            val stop = cmClass.getMethod("stopTethering", Int::class.javaPrimitiveType)
            stop.invoke(cm, TETHERING_WIFI)
            return ActionResult.Success("Hotspot disable (tethering_reflection)")
        }

        return ActionResult.Failure(
            "Non-root Hotspot-Start auf API ${Build.VERSION.SDK_INT} nicht möglich " +
                "(OnStartTetheringCallback ist abstrakt; benötigt System-Signatur)"
        )
    }

    /**
     * Überbrückt den async Tethering-Callback in eine Coroutine mit Timeout.
     * Liefert bei Timeout eine Failure, statt hängen zu bleiben.
     */
    private suspend fun suspendWithTimeout(
        register: (resume: (ActionResult) -> Unit) -> Unit
    ): ActionResult = withTimeoutOrNull(CALLBACK_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val resumeOnce = object {
                var done = false
                fun resume(r: ActionResult) {
                    if (!done) {
                        done = true
                        if (cont.isActive) cont.resume(r)
                    }
                }
            }
            try {
                register { resumeOnce.resume(it) }
            } catch (e: Throwable) {
                resumeOnce.resume(
                    ActionResult.Failure(
                        "Tethering-Aufruf fehlgeschlagen: ${e.javaClass.simpleName}: ${e.message}",
                        cause = e
                    )
                )
            }
        }
    } ?: ActionResult.Failure("Tethering-Reflection Timeout nach ${CALLBACK_TIMEOUT_MS}ms")

    companion object {
        /** `TetheringManager.TETHERING_WIFI` / `ConnectivityManager.TETHERING_WIFI`. */
        private const val TETHERING_WIFI = 0
        private const val CALLBACK_TIMEOUT_MS = 10_000L
    }
}
