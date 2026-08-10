package at.resch.routines.core.shell

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Führt Shell-Kommandos aus — als normaler User oder via Root (`su -c`).
 *
 * Der eigentliche Prozess-Start steckt hinter [ProcessRunner] (Test-Seam), die
 * blockierende I/O läuft auf einem injizierbaren [dispatcher] (default
 * [Dispatchers.IO]). Wirft **nicht**: ein nicht startbarer Prozess (z. B. kein
 * `su`) wird als [ShellResult] mit Fehler-Exit-Code/-stderr zurückgegeben, damit
 * Aufrufer graceful degradieren können.
 */
class ShellExecutor(
    private val processRunner: ProcessRunner = RealProcessRunner(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /** Ausführungs-Modus. */
    enum class Mode { USER, ROOT }

    /**
     * Führt [command] aus.
     *
     * @param command Das auszuführende Shell-Kommando (eine zusammenhängende
     *   Kommandozeile, wird an `sh -c` bzw. `su -c` übergeben).
     * @param mode [Mode.USER] → `sh -c`, [Mode.ROOT] → `su -c`.
     * @param timeoutMs Maximale Laufzeit in ms; bei Überschreitung wird der
     *   Prozess getötet und [ShellResult.TIMEOUT_EXIT_CODE] zurückgegeben.
     *   `null`/<=0 → kein Timeout.
     */
    suspend fun execute(
        command: String,
        mode: Mode = Mode.USER,
        timeoutMs: Long? = null
    ): ShellResult = withContext(dispatcher) {
        val argv = when (mode) {
            Mode.USER -> listOf("sh", "-c", command)
            Mode.ROOT -> listOf("su", "-c", command)
        }

        val process: Process = try {
            processRunner.start(argv)
        } catch (e: Exception) {
            // Prozess gar nicht startbar (z. B. su fehlt, kein Root).
            return@withContext ShellResult(
                exitCode = ShellResult.TIMEOUT_EXIT_CODE,
                stdout = "",
                stderr = "Prozessstart fehlgeschlagen: ${e.message}"
            )
        }

        try {
            if (timeoutMs != null && timeoutMs > 0) {
                val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    // Timeout → Prozess hart beenden, gesammelte Ausgabe mitgeben.
                    val partialOut = readStream(process) { it.inputStream }
                    val partialErr = readStream(process) { it.errorStream }
                    process.destroyForcibly()
                    return@withContext ShellResult(
                        exitCode = ShellResult.TIMEOUT_EXIT_CODE,
                        stdout = partialOut,
                        stderr = if (partialErr.isBlank())
                            "Timeout nach ${timeoutMs}ms" else partialErr
                    )
                }
            } else {
                process.waitFor()
            }

            val stdout = readStream(process) { it.inputStream }
            val stderr = readStream(process) { it.errorStream }
            ShellResult(
                exitCode = process.exitValue(),
                stdout = stdout,
                stderr = stderr
            )
        } catch (e: Exception) {
            process.destroyForcibly()
            ShellResult(
                exitCode = ShellResult.TIMEOUT_EXIT_CODE,
                stdout = "",
                stderr = "Ausführung fehlgeschlagen: ${e.message}"
            )
        }
    }

    private inline fun readStream(
        process: Process,
        selector: (Process) -> java.io.InputStream
    ): String =
        try {
            selector(process).bufferedReader().readText().trim()
        } catch (_: Exception) {
            ""
        }
}
