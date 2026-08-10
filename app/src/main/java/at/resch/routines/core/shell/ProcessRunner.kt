package at.resch.routines.core.shell

/**
 * Test-Seam um den eigentlichen Prozess-Start. Kapselt [ProcessBuilder] /
 * [Process], damit [ShellExecutor] ohne echten Prozess (und ohne Gerät) mit
 * MockK unit-getestet werden kann.
 *
 * Implementierungen starten den übergebenen Kommandozeilen-Vektor und liefern
 * ein [Process]-Handle zurück (stdout/stderr werden zusammengeführt? Nein —
 * getrennt, damit der Executor sie unterscheiden kann).
 */
interface ProcessRunner {
    /**
     * Startet den Prozess für die übergebene [command]-Argumentliste
     * (z. B. `["sh", "-c", "echo hi"]` bzw. `["su", "-c", "..."]`).
     *
     * @throws java.io.IOException wenn der Prozess nicht gestartet werden kann
     *   (z. B. `su` nicht vorhanden).
     */
    fun start(command: List<String>): Process
}

/**
 * Produktiv-Implementierung über [ProcessBuilder]. stdout und stderr bleiben
 * getrennt (kein `redirectErrorStream`).
 */
class RealProcessRunner : ProcessRunner {
    override fun start(command: List<String>): Process =
        ProcessBuilder(command)
            .redirectErrorStream(false)
            .start()
}
