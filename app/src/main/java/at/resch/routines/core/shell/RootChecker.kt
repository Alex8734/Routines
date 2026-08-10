package at.resch.routines.core.shell

import java.io.File

/**
 * Test-Seam für die Root-Erkennung. Trennt die beiden Wege —
 * (1) bekannte `su`-Binärpfade prüfen, (2) `su` tatsächlich ausführen — von der
 * Caching-Logik, damit [RootChecker] mit MockK ohne Gerät testbar ist.
 */
interface RootProbe {
    /** `true`, wenn an einem der bekannten Pfade eine `su`-Binärdatei existiert. */
    fun suBinaryExists(): Boolean

    /**
     * Führt einen minimalen `su`-Befehl aus (z. B. `su -c id`) und liefert `true`,
     * wenn er mit Exit-Code 0 durchläuft (= Root tatsächlich gewährt).
     */
    suspend fun canExecuteSu(): Boolean
}

/**
 * Produktiv-Probe: prüft gängige su-Pfade und nutzt [ShellExecutor] für den
 * tatsächlichen Ausführungs-Test.
 */
class DefaultRootProbe(
    private val shellExecutor: ShellExecutor = ShellExecutor()
) : RootProbe {

    override fun suBinaryExists(): Boolean =
        SU_PATHS.any { runCatching { File(it).exists() }.getOrDefault(false) }

    override suspend fun canExecuteSu(): Boolean =
        runCatching {
            shellExecutor.execute("id", ShellExecutor.Mode.ROOT, timeoutMs = 5_000).isSuccess
        }.getOrDefault(false)

    companion object {
        /** Häufige Pfade von `su`-Binaries (Magisk, SuperSU, Custom-ROMs). */
        val SU_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/system/sbin/su",
            "/vendor/bin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su"
        )
    }
}

/**
 * Erkennt, ob das Gerät gerootet ist und die App Root nutzen kann. Das Ergebnis
 * wird gecached (Root-Status ändert sich zur Laufzeit faktisch nicht).
 *
 * Erkennungs-Strategie: erst billiger Pfad-Check, dann — nur falls eine
 * Binärdatei existiert — echter `su`-Ausführungstest (verhindert teure
 * `su`-Aufrufe samt Berechtigungs-Popup auf ungerooteten Geräten).
 *
 * Die [probe] ist injizierbar → in Tests mockbar (present/absent).
 */
class RootChecker(
    private val probe: RootProbe = DefaultRootProbe()
) {

    @Volatile
    private var cached: Boolean? = null

    /** Liefert (gecached), ob Root verfügbar ist. */
    suspend fun isRootAvailable(): Boolean {
        cached?.let { return it }
        val result = if (!probe.suBinaryExists()) {
            false
        } else {
            probe.canExecuteSu()
        }
        cached = result
        return result
    }

    /** Verwirft den Cache (z. B. nach Root-Grant durch den User). */
    fun invalidate() {
        cached = null
    }
}
