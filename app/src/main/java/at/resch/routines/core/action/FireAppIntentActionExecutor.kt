package at.resch.routines.core.action

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import at.resch.routines.core.ActionExecutor
import at.resch.routines.domain.model.Action
import at.resch.routines.domain.model.ActionResult

/**
 * Test-Seam um die Framework-Abhängigkeiten (PackageManager-Auflösung +
 * `startActivity`). Erlaubt MockK-Tests ohne echten [Context].
 */
interface IntentLauncher {
    /**
     * Liefert den Launch-Intent einer App (oder `null`, wenn die App nicht
     * installiert ist / keinen Launcher hat). Analog zu
     * `PackageManager.getLaunchIntentForPackage`.
     */
    fun launchIntentFor(packageName: String): Intent?

    /**
     * Prüft, ob der [intent] von einer Komponente aufgelöst werden kann.
     */
    fun canResolve(intent: Intent): Boolean

    /**
     * Startet die durch [intent] beschriebene Activity. Setzt nötige Flags
     * (NEW_TASK) selbst, da der Aufruf aus einem nicht-Activity-Kontext erfolgt.
     */
    fun startActivity(intent: Intent)
}

/** Produktiv-Implementierung über einen Application-[Context]. */
class ContextIntentLauncher(
    private val context: Context
) : IntentLauncher {

    override fun launchIntentFor(packageName: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(packageName)

    override fun canResolve(intent: Intent): Boolean =
        intent.resolveActivity(context.packageManager) != null

    override fun startActivity(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

/**
 * Executor für `fire_app_intent` — startet eine andere App / einen Intent.
 *
 * Param-Vertrag:
 * - `package`   : String, **erforderlich** — Ziel-Package.
 * - `action`    : String, optional — explizite Intent-Action (z. B.
 *                 `android.intent.action.VIEW`). Wenn gesetzt, wird ein expliziter
 *                 Intent gebaut statt des Launch-Intents.
 * - `data`      : String (URI), optional — Daten-URI für den Intent.
 * - `className` : String, optional — vollqualifizierte Komponente (Activity).
 *
 * Logik:
 * - Nur `package` → Launch-Intent der App (Hauptactivity).
 * - `action`/`className`/`data` → expliziter Intent, auf `package` beschränkt.
 *
 * Nicht installiert / nicht auflösbar → [ActionResult.Failure] (kein Crash).
 */
class FireAppIntentActionExecutor(
    private val launcher: IntentLauncher
) : ActionExecutor {

    override val type: String = "fire_app_intent"

    override suspend fun execute(action: Action): ActionResult {
        val pkg = action.params["package"]
        if (pkg.isNullOrBlank()) {
            return ActionResult.Failure("fire_app_intent: Param 'package' fehlt")
        }

        val actionName = action.params["action"]?.takeIf { it.isNotBlank() }
        val dataUri = action.params["data"]?.takeIf { it.isNotBlank() }
        val className = action.params["className"]?.takeIf { it.isNotBlank() }

        val intent: Intent = if (actionName == null && className == null && dataUri == null) {
            // Reiner App-Start.
            launcher.launchIntentFor(pkg)
                ?: return ActionResult.Failure(
                    "fire_app_intent: App '$pkg' nicht installiert oder ohne Launcher-Activity"
                )
        } else {
            // Expliziter Intent, auf das Package beschränkt.
            Intent().apply {
                setPackage(pkg)
                actionName?.let { this.action = it }
                dataUri?.let { this.data = runCatching { Uri.parse(it) }.getOrNull() }
                className?.let { setClassName(pkg, it) }
            }.also {
                if (!launcher.canResolve(it)) {
                    return ActionResult.Failure(
                        "fire_app_intent: Intent für '$pkg' nicht auflösbar " +
                            "(action=$actionName, class=$className, data=$dataUri)"
                    )
                }
            }
        }

        return try {
            launcher.startActivity(intent)
            ActionResult.Success("Intent an '$pkg' gefeuert")
        } catch (e: Exception) {
            Log.w(TAG, "startActivity fehlgeschlagen", e)
            ActionResult.Failure("fire_app_intent: Start fehlgeschlagen für '$pkg'", e)
        }
    }

    companion object {
        private const val TAG = "FireAppIntentAction"
    }
}
