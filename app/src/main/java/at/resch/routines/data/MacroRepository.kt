package at.resch.routines.data

import at.resch.routines.domain.model.MacroScript
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString

/**
 * Repository über dem [MacroDao]. Mappt zwischen [MacroEntity] (roher JSON-String
 * in der DB) und dem geparsten Domain-Modell [MacroScript].
 *
 * Konstruktor-injiziert für Testbarkeit (QA mockt das DAO). Nutzt die zentrale
 * [MacroJson]-Instanz; keine eigenen Json-Objekte.
 */
class MacroRepository(
    private val dao: MacroDao
) {

    /** Roh-Strom aller Konfigurationen aus der DB (für reine JSON-Konsumenten/UI-Editor). */
    fun observeAllRaw(): Flow<List<MacroEntity>> = dao.observeAll()

    /**
     * Strom aller **erfolgreich parsbaren** Makros als Domain-Modell.
     * Defekte/ungültige JSON-Einträge werden übersprungen (graceful degradation),
     * damit ein einzelnes kaputtes Makro nicht den ganzen Evaluator-Stream killt.
     */
    fun observeAll(): Flow<List<MacroScript>> =
        dao.observeAll().map { entities ->
            entities.mapNotNull { parseOrNull(it.configuration) }
        }

    /** Lädt ein einzelnes Makro geparst, oder `null` wenn nicht vorhanden/ungültig. */
    suspend fun getById(id: String): MacroScript? =
        dao.getById(id)?.let { parseOrNull(it.configuration) }

    /** Persistiert ein Domain-Makro, indem es zu JSON serialisiert und upserted wird. */
    suspend fun save(macro: MacroScript) {
        val json = MacroJson.encodeToString(macro)
        dao.upsert(MacroEntity(macroId = macro.id, configuration = json))
    }

    /** Persistiert rohen JSON-String direkt (Expert-/Import-Pfad der UI). */
    suspend fun saveRaw(id: String, configuration: String) {
        dao.upsert(MacroEntity(macroId = id, configuration = configuration))
    }

    suspend fun deleteById(id: String) = dao.deleteById(id)

    /**
     * Parst einen rohen JSON-String → [MacroScript] über die zentrale [MacroJson],
     * oder `null` bei ungültigem JSON. Zentrale Parse-Hilfe für Engine & UI.
     */
    fun parseOrNull(configuration: String): MacroScript? =
        runCatching { MacroJson.decodeFromString<MacroScript>(configuration) }.getOrNull()
}
