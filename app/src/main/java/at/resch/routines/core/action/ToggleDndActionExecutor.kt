package at.resch.routines.core.action

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import at.resch.routines.core.ActionExecutor
import at.resch.routines.domain.model.Action
import at.resch.routines.domain.model.ActionResult

/**
 * Test-Seam um das Schalten des "Bitte nicht stören"-Modus (DND). Kapselt den
 * [NotificationManager]-Zugriff, sodass der Executor ohne echtes Framework-Objekt
 * mockbar ist.
 */
interface DndController {
    /**
     * Aktiviert ([enabled] = true) bzw. deaktiviert DND. Wirft eine
     * [SecurityException], wenn der Notification-Policy-Zugriff fehlt — der
     * Executor fängt das in ein [ActionResult.Failure].
     */
    fun setEnabled(enabled: Boolean)
}

/**
 * Produktiv-Implementierung über den [NotificationManager]. Der DND-Zustand wird
 * über den Interruption-Filter gesetzt: `INTERRUPTION_FILTER_NONE` (alles
 * stumm) für an, `INTERRUPTION_FILTER_ALL` (normal) für aus.
 */
class ContextDndController(
    context: Context
) : DndController {

    private val appContext = context.applicationContext

    override fun setEnabled(enabled: Boolean) {
        val nm = appContext.getSystemService(NotificationManager::class.java)
        // Policy-Access ist eine Special-Permission (Runtime-Grant via Settings).
        // Ohne sie schlägt setInterruptionFilter fehl → früh und klar abbrechen.
        if (nm == null || !nm.isNotificationPolicyAccessGranted) {
            throw SecurityException("Notification Policy Access nicht gewährt")
        }
        nm.setInterruptionFilter(
            if (enabled) NotificationManager.INTERRUPTION_FILTER_NONE
            else NotificationManager.INTERRUPTION_FILTER_ALL
        )
    }
}

/**
 * Executor für `toggle_dnd` — schaltet den "Bitte nicht stören"-Modus.
 *
 * Param-Vertrag:
 * - `enabled` : Boolean, optional (default `true`) — Ziel-Zustand. Wird strikt
 *   geparst (`toBooleanStrictOrNull`); unparsebare Werte fallen auf `true`.
 *
 * Fehlender Policy-Access (Seam wirft) → [ActionResult.Failure]. Wirft nie.
 */
class ToggleDndActionExecutor(
    private val controller: DndController
) : ActionExecutor {

    override val type: String = "toggle_dnd"

    override suspend fun execute(action: Action): ActionResult {
        val enabled = action.params["enabled"]?.toBooleanStrictOrNull() ?: true
        return try {
            controller.setEnabled(enabled)
            ActionResult.Success("DND ${if (enabled) "an" else "aus"}")
        } catch (e: Exception) {
            Log.w(TAG, "toggle_dnd fehlgeschlagen", e)
            ActionResult.Failure(
                "toggle_dnd: Schalten fehlgeschlagen (Policy-Access fehlt?)", e
            )
        }
    }

    companion object {
        private const val TAG = "ToggleDndAction"
    }
}
