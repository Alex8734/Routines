package at.resch.routines.core.shell

/**
 * Strukturiertes Ergebnis einer Shell-Ausführung.
 *
 * @property exitCode Exit-Code des Prozesses. [TIMEOUT_EXIT_CODE] markiert einen
 *   abgebrochenen (getöteten) Prozess wegen Timeout.
 * @property stdout Gesammelte Standard-Ausgabe (getrimmt).
 * @property stderr Gesammelte Fehler-Ausgabe (getrimmt).
 */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    /** Erfolg = Exit-Code 0. */
    val isSuccess: Boolean get() = exitCode == 0

    companion object {
        /** Sentinel-Exit-Code für einen wegen Timeout getöteten Prozess. */
        const val TIMEOUT_EXIT_CODE = -1
    }
}
