package at.resch.routines.core

import android.util.Log
import at.resch.routines.domain.model.Action
import at.resch.routines.domain.model.ActionResult

/**
 * Default-Executor für den Action-Typ `"log"`. Macht die Engine schon jetzt
 * end-to-end lauffähig, ohne System-/Root-Aktionen (die kommen in Phase 3).
 * Loggt die `message`-Param und gibt [ActionResult.Success] zurück.
 */
class LogActionExecutor : ActionExecutor {

    override val type: String = "log"

    override suspend fun execute(action: Action): ActionResult {
        val message = action.params["message"] ?: "(keine message)"
        Log.d(TAG, "log-action: $message")
        return ActionResult.Success(output = message)
    }

    companion object {
        private const val TAG = "LogActionExecutor"
    }
}
