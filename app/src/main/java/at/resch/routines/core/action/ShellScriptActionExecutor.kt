package at.resch.routines.core.action

import android.util.Log
import at.resch.routines.core.ActionExecutor
import at.resch.routines.core.shell.ShellExecutor
import at.resch.routines.domain.model.Action
import at.resch.routines.domain.model.ActionResult

/**
 * Executor für `execute_shell_script`. Führt ein Shell-Kommando aus —
 * optional als Root.
 *
 * Param-Vertrag:
 * - `command`   : String, **erforderlich** — die auszuführende Kommandozeile.
 * - `runAsRoot` : String "true"/"false", default `false` — Ausführung via `su`.
 * - `timeoutMs` : String (Long), optional — Timeout in ms; ungültig/leer → kein Timeout.
 *
 * Erfolg (Exit-Code 0) → [ActionResult.Success] mit stdout.
 * Nicht-0-Exit / Timeout / Startfehler → [ActionResult.Failure] mit klarer
 * Begründung (stderr). Wirft nie (graceful degradation).
 */
class ShellScriptActionExecutor(
    private val shellExecutor: ShellExecutor = ShellExecutor()
) : ActionExecutor {

    override val type: String = "execute_shell_script"

    override suspend fun execute(action: Action): ActionResult {
        val command = action.params["command"]
        if (command.isNullOrBlank()) {
            return ActionResult.Failure("execute_shell_script: Param 'command' fehlt")
        }

        val runAsRoot = action.params["runAsRoot"]?.toBooleanStrictOrNull() ?: false
        val timeoutMs = action.params["timeoutMs"]?.toLongOrNull()
        val mode = if (runAsRoot) ShellExecutor.Mode.ROOT else ShellExecutor.Mode.USER

        val result = shellExecutor.execute(command, mode, timeoutMs)

        return if (result.isSuccess) {
            ActionResult.Success(output = result.stdout)
        } else {
            val reason = buildString {
                append("Shell-Kommando fehlgeschlagen (exit=${result.exitCode}")
                append(if (runAsRoot) ", root)" else ")")
                if (result.stderr.isNotBlank()) append(": ${result.stderr}")
            }
            Log.w(TAG, reason)
            ActionResult.Failure(reason)
        }
    }

    companion object {
        private const val TAG = "ShellScriptAction"
    }
}
