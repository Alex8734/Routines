package at.resch.routines.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import at.resch.routines.data.MacroJsonPretty
import at.resch.routines.data.MacroRepository
import at.resch.routines.domain.model.Action
import at.resch.routines.domain.model.MacroScript
import at.resch.routines.domain.model.Trigger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.util.UUID

// ---------------------------------------------------------------------------
// UI-State
// ---------------------------------------------------------------------------

/**
 * Repräsentiert den Zustand eines einzelnen Action-Eintrags im Editor.
 * Params werden als Mutable-Map gehalten, damit einzelne Felder gezielt
 * aktualisiert werden können ohne die ganze Liste neu zu erstellen.
 */
data class ActionDraft(
    val id: String = UUID.randomUUID().toString(), // nur für LazyColumn-Key
    val type: String = "log",
    val params: Map<String, String> = emptyMap()
)

/** Vollständiger Editor-Zustand, den der Screen observiert. */
data class EditorUiState(
    /** Aktuell bearbeiteter Name des Makros. */
    val name: String = "",
    /** Aktiviert/Deaktiviert-Status. */
    val enabled: Boolean = true,
    /** Ausgewählter Trigger. */
    val trigger: Trigger = Trigger.OnStartup,
    /** Liste der Action-Entwürfe im visuellen Builder. */
    val actions: List<ActionDraft> = emptyList(),
    /** Roher JSON-Text im Expert-Tab; immer synchron mit dem Formular-Modell (außer bei Fehlern). */
    val rawJson: String = "",
    /** Fehlermeldung bei ungültigem JSON (nur Expert-Tab). null = kein Fehler. */
    val jsonError: String? = null,
    /** True, solange das initiale Laden per ID noch läuft. */
    val isLoading: Boolean = false,
    /** True, nach erfolgreichem Speichern. Screen kann dann zurück navigieren. */
    val isSaved: Boolean = false
)

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

/**
 * ViewModel für den MacroEditor-Screen.
 *
 * Bidirektionale Synchronisation:
 *  - Formular → JSON: Jede Formular-Änderung baut ein [MacroScript] und serialisiert
 *    es via [MacroJson] zum [EditorUiState.rawJson]. Kein eigenes Parsing.
 *  - JSON → Formular: Tipp-Eingabe im Expert-Tab ruft [MacroRepository.parseOrNull] auf.
 *    Bei gültigem Ergebnis wird das Formular aktualisiert; bei ungültigem JSON bleibt
 *    das Formular unverändert und [EditorUiState.jsonError] wird gesetzt.
 *
 * Kein Business-Logik-Code hier: Serialisierung delegiert an [MacroJson],
 * Validierung/Parsing an [MacroRepository.parseOrNull].
 */
class MacroEditorViewModel(
    private val repository: MacroRepository,
    private val initialMacroId: String?
) : ViewModel() {

    /** Interner Zustand der Makro-ID — neue Makros erhalten eine frische UUID. */
    private var currentMacroId: String = initialMacroId ?: UUID.randomUUID().toString()

    private val _uiState = MutableStateFlow(EditorUiState(isLoading = initialMacroId != null))
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    init {
        if (initialMacroId != null) {
            // Vorhandenes Makro aus der DB laden
            viewModelScope.launch {
                val macro = repository.getById(initialMacroId)
                if (macro != null) {
                    applyMacroToState(macro)
                } else {
                    // ID nicht gefunden → neues leeres Makro
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        } else {
            // Neues Makro: JSON-Vorschau initialisieren
            regenerateJson()
        }
    }

    // -----------------------------------------------------------------------
    // Formular-Mutationen (Visual Builder → State)
    // -----------------------------------------------------------------------

    fun onNameChange(value: String) {
        _uiState.update { it.copy(name = value) }
        regenerateJson()
    }

    fun onEnabledChange(value: Boolean) {
        _uiState.update { it.copy(enabled = value) }
        regenerateJson()
    }

    fun onTriggerChange(trigger: Trigger) {
        _uiState.update { it.copy(trigger = trigger) }
        regenerateJson()
    }

    /**
     * Setzt den Akkustand-Schwellwert für Trigger.BatteryLevel.
     * Ungültige/leere Eingaben fallen auf den Standardwert 20 zurück.
     */
    fun onBatteryLevelChange(levelStr: String) {
        val level = levelStr.toIntOrNull()?.coerceIn(0, 100) ?: 20
        val current = _uiState.value.trigger
        val mode = if (current is Trigger.BatteryLevel) current.mode else Trigger.BatteryLevel.MODE_BELOW
        _uiState.update { it.copy(trigger = Trigger.BatteryLevel(level = level, mode = mode)) }
        regenerateJson()
    }

    /**
     * Setzt den Modus (below/above) für Trigger.BatteryLevel.
     */
    fun onBatteryModeChange(mode: String) {
        val current = _uiState.value.trigger
        val level = if (current is Trigger.BatteryLevel) current.level else 20
        _uiState.update { it.copy(trigger = Trigger.BatteryLevel(level = level, mode = mode)) }
        regenerateJson()
    }

    /**
     * Setzt das Intervall in Minuten für Trigger.TimeSchedule.
     * Ungültige/leere Eingaben fallen auf den Standardwert 15 zurück.
     */
    fun onTimeScheduleIntervalChange(intervalStr: String) {
        val interval = intervalStr.toIntOrNull()?.coerceAtLeast(1) ?: 15
        _uiState.update { it.copy(trigger = Trigger.TimeSchedule(intervalMinutes = interval)) }
        regenerateJson()
    }

    /**
     * Setzt die SSID für Trigger.WifiSsid.
     * Ignoriert den Aufruf wenn der aktuelle Trigger kein WifiSsid ist.
     */
    fun onWifiSsidChange(ssid: String) {
        val current = _uiState.value.trigger as? Trigger.WifiSsid ?: return
        _uiState.update { it.copy(trigger = Trigger.WifiSsid(ssid = ssid, mode = current.mode)) }
        regenerateJson()
    }

    /**
     * Setzt den Modus (connected/disconnected) für Trigger.WifiSsid.
     * Ignoriert den Aufruf wenn der aktuelle Trigger kein WifiSsid ist.
     */
    fun onWifiModeChange(mode: String) {
        val current = _uiState.value.trigger as? Trigger.WifiSsid ?: return
        _uiState.update { it.copy(trigger = Trigger.WifiSsid(ssid = current.ssid, mode = mode)) }
        regenerateJson()
    }

    /**
     * Setzt den Gerätenamen für Trigger.BluetoothDevice.
     * Ignoriert den Aufruf wenn der aktuelle Trigger kein BluetoothDevice ist.
     */
    fun onBluetoothNameChange(deviceName: String) {
        val current = _uiState.value.trigger as? Trigger.BluetoothDevice ?: return
        _uiState.update { it.copy(trigger = Trigger.BluetoothDevice(deviceName = deviceName, mode = current.mode)) }
        regenerateJson()
    }

    /**
     * Setzt den Modus (connected/disconnected) für Trigger.BluetoothDevice.
     * Ignoriert den Aufruf wenn der aktuelle Trigger kein BluetoothDevice ist.
     */
    fun onBluetoothModeChange(mode: String) {
        val current = _uiState.value.trigger as? Trigger.BluetoothDevice ?: return
        _uiState.update { it.copy(trigger = Trigger.BluetoothDevice(deviceName = current.deviceName, mode = mode)) }
        regenerateJson()
    }

    fun onAddAction() {
        _uiState.update { state ->
            state.copy(actions = state.actions + ActionDraft())
        }
        regenerateJson()
    }

    fun onRemoveAction(actionId: String) {
        _uiState.update { state ->
            state.copy(actions = state.actions.filter { it.id != actionId })
        }
        regenerateJson()
    }

    /**
     * Verschiebt eine Aktion in der Liste von [fromIndex] nach [toIndex].
     * Die Engine führt Aktionen in Listenreihenfolge aus — diese Funktion
     * synchronisiert die neue Reihenfolge direkt in [EditorUiState.actions]
     * und ruft danach [regenerateJson] auf, damit [EditorUiState.rawJson]
     * die neue Ausführungsreihenfolge widerspiegelt.
     */
    fun onMoveAction(fromIndex: Int, toIndex: Int) {
        _uiState.update { state ->
            if (fromIndex < 0 || toIndex < 0 ||
                fromIndex >= state.actions.size || toIndex >= state.actions.size ||
                fromIndex == toIndex
            ) return@update state
            val mutable = state.actions.toMutableList()
            val item = mutable.removeAt(fromIndex)
            mutable.add(toIndex, item)
            state.copy(actions = mutable)
        }
        regenerateJson()
    }

    fun onActionTypeChange(actionId: String, newType: String) {
        _uiState.update { state ->
            state.copy(actions = state.actions.map { draft ->
                if (draft.id == actionId) draft.copy(type = newType, params = emptyMap()) else draft
            })
        }
        regenerateJson()
    }

    fun onActionParamChange(actionId: String, paramKey: String, paramValue: String) {
        _uiState.update { state ->
            state.copy(actions = state.actions.map { draft ->
                if (draft.id == actionId) {
                    val updatedParams = draft.params.toMutableMap().apply {
                        if (paramValue.isBlank()) remove(paramKey) else put(paramKey, paramValue)
                    }
                    draft.copy(params = updatedParams)
                } else draft
            })
        }
        regenerateJson()
    }

    // -----------------------------------------------------------------------
    // Expert-Tab: JSON → Formular
    // -----------------------------------------------------------------------

    /**
     * Wird bei jeder Tipp-Eingabe im Roh-JSON-Textfeld aufgerufen.
     * Validierung via [MacroRepository.parseOrNull] — kein eigenes Parsing in der UI.
     */
    fun onRawJsonChange(json: String) {
        val parsed = repository.parseOrNull(json)
        if (parsed != null) {
            // Gültiges JSON → Formular aktualisieren, Fehler zurücksetzen
            _uiState.update { state ->
                state.copy(
                    rawJson = json,
                    jsonError = null,
                    name = parsed.name,
                    enabled = parsed.enabled,
                    trigger = parsed.trigger,
                    actions = parsed.actions.map { action ->
                        ActionDraft(
                            id = UUID.randomUUID().toString(),
                            type = action.type,
                            params = action.params
                        )
                    }
                )
            }
            // ID aus dem JSON übernehmen, falls vorhanden
            currentMacroId = parsed.id
        } else {
            // Ungültiges JSON → nur Text und Fehlermeldung setzen, Formular unberührt
            _uiState.update { state ->
                state.copy(
                    rawJson = json,
                    jsonError = "Ungültiges JSON — bitte korrigieren"
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // Speichern
    // -----------------------------------------------------------------------

    /** Speichert aus dem Visual Builder (serialisiert Domain-Objekt). */
    fun saveFromForm() {
        val macro = buildMacroFromState() ?: return
        viewModelScope.launch {
            repository.save(macro)
            _uiState.update { it.copy(isSaved = true) }
        }
    }

    /**
     * Speichert aus dem Expert-Tab (rohen JSON-String direkt persistieren).
     * Schlägt fehl wenn das JSON ungültig ist (jsonError != null).
     */
    fun saveFromJson() {
        val state = _uiState.value
        if (state.jsonError != null) return // Kein Speichern bei Fehler
        viewModelScope.launch {
            repository.saveRaw(currentMacroId, state.rawJson)
            _uiState.update { it.copy(isSaved = true) }
        }
    }

    /** Setzt isSaved zurück (nach Navigation). */
    fun onSaveHandled() {
        _uiState.update { it.copy(isSaved = false) }
    }

    // -----------------------------------------------------------------------
    // Hilfsfunktionen (private)
    // -----------------------------------------------------------------------

    /** Wandelt den aktuellen Formular-State in ein [MacroScript] um. */
    private fun buildMacroFromState(): MacroScript? {
        val state = _uiState.value
        return MacroScript(
            id = currentMacroId,
            name = state.name,
            enabled = state.enabled,
            trigger = state.trigger,
            actions = state.actions.map { draft ->
                Action(type = draft.type, params = draft.params)
            }
        )
    }

    /**
     * Serialisiert das aktuelle Formular-Modell zu eingerücktem JSON und schreibt es
     * in [EditorUiState.rawJson]. Verwendet [MacroJsonPretty] für lesbares Pretty-Print
     * im Editor-Tab. Persistenz nutzt weiterhin kompaktes MacroJson in [MacroRepository].
     */
    private fun regenerateJson() {
        val macro = buildMacroFromState() ?: return
        val json = MacroJsonPretty.encodeToString(macro)
        _uiState.update { state ->
            state.copy(rawJson = json, jsonError = null, isLoading = false)
        }
    }

    /** Wendet ein geladenes [MacroScript] auf den UI-State an. */
    private fun applyMacroToState(macro: MacroScript) {
        currentMacroId = macro.id
        _uiState.update { state ->
            state.copy(
                isLoading = false,
                name = macro.name,
                enabled = macro.enabled,
                trigger = macro.trigger,
                actions = macro.actions.map { action ->
                    ActionDraft(
                        id = UUID.randomUUID().toString(),
                        type = action.type,
                        params = action.params
                    )
                }
            )
        }
        regenerateJson()
    }

    // -----------------------------------------------------------------------
    // Factory (manuelles DI, kein Hilt)
    // -----------------------------------------------------------------------

    class Factory(
        private val repository: MacroRepository,
        private val macroId: String?
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MacroEditorViewModel(repository, macroId) as T
    }
}
