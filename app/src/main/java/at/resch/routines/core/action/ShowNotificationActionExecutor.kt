package at.resch.routines.core.action

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import at.resch.routines.core.ActionExecutor
import at.resch.routines.domain.model.Action
import at.resch.routines.domain.model.ActionResult
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test-Seam um den Notification-Versand. Kapselt Channel-Anlage + Posting,
 * sodass der Executor ohne echten [NotificationManagerCompat] mockbar ist.
 */
interface NotificationPoster {
    /**
     * Postet eine Notification mit [title]/[message]. Wirft, wenn das Posten
     * fehlschlägt (z. B. fehlende Runtime-Permission ab API 33) — der Executor
     * fängt das in ein [ActionResult.Failure].
     */
    fun post(title: String, message: String)
}

/**
 * Produktiv-Implementierung über [NotificationManagerCompat]. Verwendet einen
 * **eigenen** User-Notifications-Channel, getrennt vom Foreground-Channel der
 * Engine (`routines_engine`).
 */
class ContextNotificationPoster(
    context: Context
) : NotificationPoster {

    private val appContext = context.applicationContext
    private val managerCompat = NotificationManagerCompat.from(appContext)

    override fun post(title: String, message: String) {
        ensureChannel()
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        // Kann SecurityException werfen, wenn POST_NOTIFICATIONS nicht gewährt —
        // bewusst nicht hier gefangen, sondern im Executor → Failure.
        managerCompat.notify(nextId.getAndIncrement(), notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Makro-Benachrichtigungen",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Vom User über show_notification ausgelöste Benachrichtigungen"
        }
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    companion object {
        /** Getrennt vom Engine-Foreground-Channel `routines_engine`. */
        const val CHANNEL_ID = "routines_user_notifications"
        private val nextId = AtomicInteger(1000)
    }
}

/**
 * Executor für `show_notification` — zeigt dem User eine Benachrichtigung.
 *
 * Param-Vertrag:
 * - `title`   : String, optional (default leer) — Titel.
 * - `message` : String, optional (default leer) — Text.
 *
 * Posting-Fehler (Permission fehlt / wirft) → [ActionResult.Failure]. Wirft nie.
 */
class ShowNotificationActionExecutor(
    private val poster: NotificationPoster
) : ActionExecutor {

    override val type: String = "show_notification"

    override suspend fun execute(action: Action): ActionResult {
        val title = action.params["title"].orEmpty()
        val message = action.params["message"].orEmpty()
        return try {
            poster.post(title, message)
            ActionResult.Success("Notification gepostet: '$title'")
        } catch (e: Exception) {
            Log.w(TAG, "show_notification fehlgeschlagen", e)
            ActionResult.Failure(
                "show_notification: Posten fehlgeschlagen (Permission fehlt?)", e
            )
        }
    }

    companion object {
        private const val TAG = "ShowNotificationAction"
    }
}
