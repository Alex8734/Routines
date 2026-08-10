package at.resch.routines.core.cache

import android.content.SharedPreferences

/**
 * Merkt sich pro Action-[type] die zuletzt **erfolgreiche** Strategie-ID, damit
 * ROM-/gerätespezifische Fallback-Ketten beim nächsten Mal direkt mit der zuvor
 * funktionierenden Strategie starten können (Capability-Cache aus dem
 * Compatibility-Contract).
 *
 * Bewusst minimal gehalten und über das Interface injizierbar → in Tests trivial
 * mockbar, ohne `Context`/`SharedPreferences`.
 */
interface CapabilityCache {

    /**
     * Liefert die zuvor gemerkte, bevorzugte Strategie-ID für [type] oder `null`,
     * falls noch keine erfolgreiche Strategie bekannt ist.
     */
    fun preferredStrategy(type: String): String?

    /**
     * Merkt [strategyId] als bevorzugte Strategie für [type] (z. B. nach einem
     * erfolgreichen Ausführungs-Versuch).
     */
    fun remember(type: String, strategyId: String)
}

/**
 * Produktiv-Implementierung auf Basis von [SharedPreferences].
 *
 * Es wird **[SharedPreferences]** injiziert (nicht `Context`), damit der Cache
 * ohne Android-Framework mockbar bleibt. Einträge werden mit [KEY_PREFIX]
 * präfixiert, um Kollisionen mit fremden Keys derselben Prefs-Datei zu vermeiden.
 * [remember] schreibt asynchron via `apply()`.
 */
class SharedPreferencesCapabilityCache(
    private val prefs: SharedPreferences
) : CapabilityCache {

    override fun preferredStrategy(type: String): String? =
        prefs.getString(KEY_PREFIX + type, null)

    override fun remember(type: String, strategyId: String) {
        prefs.edit().putString(KEY_PREFIX + type, strategyId).apply()
    }

    companion object {
        /** Prefix für alle Cache-Keys, um Kollisionen zu vermeiden. */
        const val KEY_PREFIX = "cap_"
    }
}
