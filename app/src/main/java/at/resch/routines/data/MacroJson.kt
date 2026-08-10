package at.resch.routines.data

import kotlinx.serialization.json.Json

/**
 * Zentrale, projektweit einzige [Json]-Instanz für das Makro-Script-Format.
 *
 * - `ignoreUnknownKeys = true`: Vorwärtskompatibilität — neuere/zukünftige Felder
 *   (z. B. `schemaVersion`) im JSON crashen ältere Parser nicht.
 * - Der Klassen-Diskriminator bleibt der kotlinx-Default `"type"`, weil das
 *   Trigger-Schema bewusst darauf aufbaut (siehe `Trigger`-Doku).
 *
 * Engine und Repository MÜSSEN diese Instanz wiederverwenden — keine Ad-hoc-
 * `Json {}`-Objekte, damit das Serialisierungsverhalten überall identisch ist.
 */
val MacroJson: Json = Json {
    ignoreUnknownKeys = true
    // classDiscriminator bleibt Default "type" — nicht überschreiben.
}

/**
 * Zweite [Json]-Instanz NUR für die UI-Anzeige: identische Semantik wie
 * [MacroJson] (gleicher Diskriminator-Default `"type"`, `ignoreUnknownKeys`),
 * aber mit eingerücktem Pretty-Print für den visuellen Editor.
 *
 * **Wichtig:** Engine und Repository persistieren weiterhin mit dem kompakten
 * [MacroJson]. Diese Instanz dient ausschließlich der Darstellung/Bearbeitung im
 * UI-Editor und darf NICHT für die Persistenz verwendet werden (vermeidet
 * unnötig aufgeblähte DB-Einträge / Diffs).
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
val MacroJsonPretty: Json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    prettyPrintIndent = "  " // ExperimentalSerializationApi
    // classDiscriminator bleibt Default "type" — wie bei MacroJson.
}
