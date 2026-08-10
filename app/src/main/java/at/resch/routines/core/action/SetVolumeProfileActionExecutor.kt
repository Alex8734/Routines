package at.resch.routines.core.action

import android.content.Context
import android.media.AudioManager
import android.util.Log
import at.resch.routines.core.ActionExecutor
import at.resch.routines.domain.model.Action
import at.resch.routines.domain.model.ActionResult

/**
 * Mögliche Lautstärke-Profile, gemappt auf die Ringer-Modi des [AudioManager].
 */
enum class VolumeProfile { NORMAL, VIBRATE, SILENT }

/**
 * Test-Seam um das Setzen des Ringer-Modus. Kapselt den [AudioManager]-Zugriff,
 * sodass der Executor ohne echtes Framework-Objekt mockbar ist.
 */
interface VolumeController {
    /**
     * Setzt den Ringer-Modus passend zum [profile]. Kann eine
     * [SecurityException] werfen (SILENT/VIBRATE benötigen je nach ROM
     * DND-Policy-Zugriff) — der Executor fängt das in ein [ActionResult.Failure].
     */
    fun setProfile(profile: VolumeProfile)
}

/**
 * Produktiv-Implementierung über den [AudioManager]. Mappt das [VolumeProfile]
 * auf den jeweiligen `RINGER_MODE_*`.
 */
class ContextVolumeController(
    context: Context
) : VolumeController {

    private val appContext = context.applicationContext

    override fun setProfile(profile: VolumeProfile) {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // setRingerMode kann ab API 23 bei SILENT/VIBRATE eine SecurityException
        // werfen, wenn DND-Policy-Zugriff fehlt — bewusst nicht hier gefangen,
        // sondern im Executor → Failure.
        am.ringerMode = when (profile) {
            VolumeProfile.NORMAL -> AudioManager.RINGER_MODE_NORMAL
            VolumeProfile.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
            VolumeProfile.SILENT -> AudioManager.RINGER_MODE_SILENT
        }
    }
}

/**
 * Executor für `set_volume_profile` — schaltet das Lautstärke-/Klingel-Profil.
 *
 * Param-Vertrag:
 * - `profile` : String, optional (case-insensitive) — `normal` | `vibrate` |
 *   `silent`. Fehlt der Param komplett → Default `NORMAL`. Ist er vorhanden,
 *   aber unparsebar → [ActionResult.Failure] OHNE Controller-Aufruf.
 *
 * Policy-bedingte Fehler (Seam wirft) → [ActionResult.Failure]. Wirft nie.
 */
class SetVolumeProfileActionExecutor(
    private val controller: VolumeController
) : ActionExecutor {

    override val type: String = "set_volume_profile"

    override suspend fun execute(action: Action): ActionResult {
        val raw = action.params["profile"]
        val profile = if (raw == null) {
            VolumeProfile.NORMAL
        } else {
            when (raw.trim().lowercase()) {
                "normal" -> VolumeProfile.NORMAL
                "vibrate" -> VolumeProfile.VIBRATE
                "silent" -> VolumeProfile.SILENT
                else -> return ActionResult.Failure(
                    "Ungültiges Profil: '$raw' (erlaubt: normal|vibrate|silent)"
                )
            }
        }
        return try {
            controller.setProfile(profile)
            ActionResult.Success("Lautstärke-Profil: $profile")
        } catch (e: Exception) {
            Log.w(TAG, "set_volume_profile fehlgeschlagen", e)
            ActionResult.Failure(
                "set_volume_profile: Schalten fehlgeschlagen (Policy-Access fehlt?)", e
            )
        }
    }

    companion object {
        private const val TAG = "SetVolumeProfileAction"
    }
}
