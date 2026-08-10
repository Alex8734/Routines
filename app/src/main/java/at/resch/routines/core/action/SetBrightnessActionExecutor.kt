package at.resch.routines.core.action

import android.content.Context
import android.provider.Settings
import android.util.Log
import at.resch.routines.core.ActionExecutor
import at.resch.routines.domain.model.Action
import at.resch.routines.domain.model.ActionResult

/**
 * Test-Seam um das Setzen der Display-Helligkeit. Kapselt den
 * [Settings.System]-Zugriff, sodass der Executor ohne echtes Framework-Objekt
 * mockbar ist.
 */
interface BrightnessController {
    /**
     * Setzt die manuelle Helligkeit auf [level0to255] (System-Skala 0..255).
     * Wirft eine [SecurityException], wenn `WRITE_SETTINGS` fehlt — der Executor
     * fängt das in ein [ActionResult.Failure].
     */
    fun setBrightness(level0to255: Int)
}

/**
 * Produktiv-Implementierung über [Settings.System]. Schaltet zunächst auf den
 * manuellen Helligkeits-Modus und schreibt dann den absoluten Wert (0..255).
 */
class ContextBrightnessController(
    context: Context
) : BrightnessController {

    private val appContext = context.applicationContext

    override fun setBrightness(level0to255: Int) {
        // WRITE_SETTINGS ist eine Special-Permission (Runtime-Grant via
        // Settings.System.canWrite) — ohne sie früh und klar abbrechen.
        if (!Settings.System.canWrite(appContext)) {
            throw SecurityException("WRITE_SETTINGS nicht gewährt")
        }
        val resolver = appContext.contentResolver
        // Auto-Modus würde den manuell gesetzten Wert sofort überschreiben.
        Settings.System.putInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, level0to255)
    }
}

/**
 * Executor für `set_brightness` — setzt die Display-Helligkeit.
 *
 * Param-Vertrag:
 * - `level` : Int **Prozent 0..100**, Pflicht. Fehlt er oder ist er kein Int →
 *   [ActionResult.Failure] OHNE Controller-Aufruf. Wert wird auf 0..100
 *   geklemmt und via `(percent * 255) / 100` auf die System-Skala 0..255
 *   gemappt.
 *
 * Fehlende `WRITE_SETTINGS` (Seam wirft) → [ActionResult.Failure]. Wirft nie.
 */
class SetBrightnessActionExecutor(
    private val controller: BrightnessController
) : ActionExecutor {

    override val type: String = "set_brightness"

    override suspend fun execute(action: Action): ActionResult {
        val percent = action.params["level"]?.toIntOrNull()
            ?: return ActionResult.Failure(
                "set_brightness: 'level' (0..100) fehlt oder ungültig"
            )
        val clamped = percent.coerceIn(0, 100)
        val mapped = (clamped * 255) / 100
        return try {
            controller.setBrightness(mapped)
            ActionResult.Success("Helligkeit: $clamped%")
        } catch (e: Exception) {
            Log.w(TAG, "set_brightness fehlgeschlagen", e)
            ActionResult.Failure(
                "set_brightness: Setzen fehlgeschlagen (WRITE_SETTINGS fehlt?)", e
            )
        }
    }

    companion object {
        private const val TAG = "SetBrightnessAction"
    }
}
