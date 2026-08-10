package at.resch.routines.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.resch.routines.domain.model.Trigger
import at.resch.routines.ui.theme.RoutinesTheme
import at.resch.routines.ui.viewmodel.ActionDraft
import at.resch.routines.ui.viewmodel.EditorUiState
import at.resch.routines.ui.viewmodel.MacroEditorViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

// ---------------------------------------------------------------------------
// Katalog: Trigger-Typen und Action-Typen
// ---------------------------------------------------------------------------

/**
 * Alle bekannten Trigger-Typen als Dropdown-Optionen.
 * Param-tragende Trigger (BatteryLevel, TimeSchedule) verwenden Standardwerte im Dropdown
 * und erhalten unterhalb des Dropdowns eigene Eingabefelder.
 */
private val TRIGGER_OPTIONS: List<Pair<Trigger, String>> = listOf(
    Trigger.OnStartup to "Beim Gerätestart",
    Trigger.SimCardDataConnected to "Mobile Daten verbunden",
    Trigger.BatteryLevel(level = 20) to "Akkustand",
    Trigger.TimeSchedule(intervalMinutes = 15) to "Zeitplan",
    Trigger.Interval(intervalSeconds = 60) to "Intervall",
    Trigger.WifiSsid(ssid = "") to "WLAN-SSID",
    Trigger.BluetoothDevice(deviceName = "") to "Bluetooth-Gerät"
)

/**
 * Liefert das Dropdown-Label für einen Trigger-Typ unabhängig von den aktuellen Param-Werten.
 * Wird im Dropdown benötigt, um den selektierten Eintrag auch bei geänderten Params
 * korrekt zu highlighten.
 */
private fun Trigger.triggerTypeLabel(): String = when (this) {
    is Trigger.OnStartup -> "Beim Gerätestart"
    is Trigger.SimCardDataConnected -> "Mobile Daten verbunden"
    is Trigger.BatteryLevel -> "Akkustand"
    is Trigger.TimeSchedule -> "Zeitplan"
    is Trigger.Interval -> "Intervall"
    is Trigger.WifiSsid -> "WLAN-SSID"
    is Trigger.BluetoothDevice -> "Bluetooth-Gerät"
}

/** Alle bekannten Action-Typen (inkl. wait). */
private val ACTION_TYPE_OPTIONS: List<String> = listOf(
    "log",
    "wait",
    "execute_shell_script",
    "toggle_hotspot",
    "fire_app_intent",
    "show_notification",
    "http_request",
    "toggle_dnd",
    "set_volume_profile",
    "set_brightness"
)

/**
 * Param-Definitionen pro Action-Typ.
 * Triple: (paramKey, Beschriftung, KeyboardType)
 */
private fun paramFieldsForActionType(type: String): List<Triple<String, String, KeyboardType>> =
    when (type) {
        "log" -> listOf(
            Triple("message", "Nachricht", KeyboardType.Text)
        )
        "wait" -> listOf(
            Triple("durationMs", "Wartezeit in Millisekunden", KeyboardType.Number)
        )
        "execute_shell_script" -> listOf(
            Triple("command", "Befehl", KeyboardType.Text),
            Triple("runAsRoot", "Als Root ausführen (true/false)", KeyboardType.Text),
            Triple("timeoutMs", "Timeout in ms (optional)", KeyboardType.Number)
        )
        "toggle_hotspot" -> listOf(
            Triple("enabled", "Aktiviert (true/false)", KeyboardType.Text)
        )
        "fire_app_intent" -> listOf(
            Triple("package", "Paketname", KeyboardType.Text),
            Triple("action", "Intent-Action (optional)", KeyboardType.Text),
            Triple("data", "Intent-Data (optional)", KeyboardType.Text),
            Triple("className", "Klassenname (optional)", KeyboardType.Text)
        )
        "show_notification" -> listOf(
            Triple("title", "Titel", KeyboardType.Text),
            Triple("message", "Nachricht", KeyboardType.Text)
        )
        "http_request" -> listOf(
            Triple("url", "URL (erforderlich)", KeyboardType.Uri),
            Triple("method", "Methode (Standard: GET)", KeyboardType.Text),
            Triple("body", "Body (optional)", KeyboardType.Text)
        )
        else -> emptyList()
    }

/**
 * Kurze, menschenlesbare Zusammenfassung einer Aktion für die kompakte Zusammenfassungszeile.
 * Zeigt den Typ + den wichtigsten Parameter.
 */
private fun ActionDraft.summaryText(): String = when (type) {
    "log" -> "Log: ${params["message"] ?: "—"}"
    "wait" -> "Warten: ${params["durationMs"] ?: "—"} ms"
    "execute_shell_script" -> buildString {
        append("Shell: ${params["command"] ?: "—"}")
        if (params["runAsRoot"] == "true") append(" (root)")
    }
    "toggle_hotspot" -> "Hotspot umschalten"
    "fire_app_intent" -> "App starten: ${params["package"] ?: "—"}"
    "show_notification" -> "Benachrichtigung: ${params["title"] ?: "—"}"
    "http_request" -> "HTTP ${params["method"] ?: "GET"}: ${params["url"] ?: "—"}"
    else -> type
}

// ---------------------------------------------------------------------------
// Einstiegspunkt (ViewModel-gebunden)
// ---------------------------------------------------------------------------

/**
 * Einstiegspunkt für den Editor-Screen.
 * Erzeugt keinen eigenen State — delegiert alles an [MacroEditorContent].
 *
 * @param viewModel Injiziertes ViewModel mit Editor-Zustand.
 * @param onNavigateBack Callback für Zurück-Navigation (nach Speichern oder Abbrechen).
 */
@Composable
fun MacroEditorScreen(
    viewModel: MacroEditorViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Nach erfolgreichem Speichern zurück navigieren
    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) {
            viewModel.onSaveHandled()
            onNavigateBack()
        }
    }

    MacroEditorContent(
        uiState = uiState,
        onNameChange = viewModel::onNameChange,
        onEnabledChange = viewModel::onEnabledChange,
        onTriggerChange = viewModel::onTriggerChange,
        onBatteryLevelChange = viewModel::onBatteryLevelChange,
        onBatteryModeChange = viewModel::onBatteryModeChange,
        onTimeScheduleIntervalChange = viewModel::onTimeScheduleIntervalChange,
        onIntervalSecondsChange = viewModel::onIntervalSecondsChange,
        onIntervalRunOnStartChange = viewModel::onIntervalRunOnStartChange,
        onWifiSsidChange = viewModel::onWifiSsidChange,
        onWifiModeChange = viewModel::onWifiModeChange,
        onBluetoothNameChange = viewModel::onBluetoothNameChange,
        onBluetoothModeChange = viewModel::onBluetoothModeChange,
        onAddAction = viewModel::onAddAction,
        onRemoveAction = viewModel::onRemoveAction,
        onActionTypeChange = viewModel::onActionTypeChange,
        onActionParamChange = viewModel::onActionParamChange,
        onMoveAction = viewModel::onMoveAction,
        onRawJsonChange = viewModel::onRawJsonChange,
        onSave = { selectedTab ->
            if (selectedTab == 1) viewModel.saveFromJson() else viewModel.saveFromForm()
        },
        onNavigateBack = onNavigateBack
    )
}

// ---------------------------------------------------------------------------
// Zustandsloser Haupt-Compose-Baum
// ---------------------------------------------------------------------------

/**
 * Zustandsloser (stateless) Editor-Screen.
 * Alle Mutationen gehen über die Callbacks nach oben zum ViewModel.
 * Kann isoliert mit Fake-Daten getestet und previewed werden.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroEditorContent(
    uiState: EditorUiState,
    onNameChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onTriggerChange: (Trigger) -> Unit,
    onBatteryLevelChange: (String) -> Unit = {},
    onBatteryModeChange: (String) -> Unit = {},
    onTimeScheduleIntervalChange: (String) -> Unit = {},
    onIntervalSecondsChange: (String) -> Unit = {},
    onIntervalRunOnStartChange: (Boolean) -> Unit = {},
    onWifiSsidChange: (String) -> Unit = {},
    onWifiModeChange: (String) -> Unit = {},
    onBluetoothNameChange: (String) -> Unit = {},
    onBluetoothModeChange: (String) -> Unit = {},
    onAddAction: () -> Unit,
    onRemoveAction: (String) -> Unit,
    onActionTypeChange: (String, String) -> Unit,
    onActionParamChange: (String, String, String) -> Unit,
    onMoveAction: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> },
    onRawJsonChange: (String) -> Unit,
    onSave: (selectedTab: Int) -> Unit,
    onNavigateBack: () -> Unit
) {
    // Lokaler Tab-Zustand — gehört zur UI-Interaktion, nicht zum Domain-State
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (uiState.name.isBlank()) "Neues Makro" else uiState.name
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Zurück"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onSave(selectedTab) }) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Speichern"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // Tab-Leiste: Visueller Builder | Expert-Scripting
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Visueller Builder") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Expert-Scripting") }
                    )
                }

                when (selectedTab) {
                    0 -> VisualBuilderTab(
                        uiState = uiState,
                        onNameChange = onNameChange,
                        onEnabledChange = onEnabledChange,
                        onTriggerChange = onTriggerChange,
                        onBatteryLevelChange = onBatteryLevelChange,
                        onBatteryModeChange = onBatteryModeChange,
                        onTimeScheduleIntervalChange = onTimeScheduleIntervalChange,
                        onIntervalSecondsChange = onIntervalSecondsChange,
                        onIntervalRunOnStartChange = onIntervalRunOnStartChange,
                        onWifiSsidChange = onWifiSsidChange,
                        onWifiModeChange = onWifiModeChange,
                        onBluetoothNameChange = onBluetoothNameChange,
                        onBluetoothModeChange = onBluetoothModeChange,
                        onAddAction = onAddAction,
                        onRemoveAction = onRemoveAction,
                        onActionTypeChange = onActionTypeChange,
                        onActionParamChange = onActionParamChange,
                        onMoveAction = onMoveAction
                    )
                    1 -> ExpertScriptingTab(
                        rawJson = uiState.rawJson,
                        jsonError = uiState.jsonError,
                        onRawJsonChange = onRawJsonChange
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tab 1: Visueller Builder
// ---------------------------------------------------------------------------

/**
 * Visueller Builder — Formular-basierte Bearbeitung des Makros.
 * Die Action-Liste erlaubt Drag-to-Reorder via Drag-Handle (linke Seite jeder Zeile)
 * und Akkordeon-Expand durch Antippen der Zusammenfassungszeile.
 *
 * Mutations-Callbacks gehen direkt an das ViewModel.
 */
@Composable
private fun VisualBuilderTab(
    uiState: EditorUiState,
    onNameChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onTriggerChange: (Trigger) -> Unit,
    onBatteryLevelChange: (String) -> Unit,
    onBatteryModeChange: (String) -> Unit,
    onTimeScheduleIntervalChange: (String) -> Unit,
    onIntervalSecondsChange: (String) -> Unit,
    onIntervalRunOnStartChange: (Boolean) -> Unit,
    onWifiSsidChange: (String) -> Unit,
    onWifiModeChange: (String) -> Unit,
    onBluetoothNameChange: (String) -> Unit,
    onBluetoothModeChange: (String) -> Unit,
    onAddAction: () -> Unit,
    onRemoveAction: (String) -> Unit,
    onActionTypeChange: (String, String) -> Unit,
    onActionParamChange: (String, String, String) -> Unit,
    onMoveAction: (fromIndex: Int, toIndex: Int) -> Unit
) {
    // Akkordeon-Zustand: nur eine ID ist zur gleichen Zeit expandiert (rein lokal)
    var expandedActionId by remember { mutableStateOf<String?>(null) }

    val lazyListState = rememberLazyListState()
    // rememberReorderableLazyListState gibt einen Callback mit from/to als
    // ReorderableCollectionItemInfo. Wir nutzen .key (die Action-ID als String)
    // um die korrekte Action zu identifizieren, ohne fragile Index-Offsets.
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
        val toKey = to.key as? String ?: return@rememberReorderableLazyListState
        val actions = uiState.actions
        val fromIndex = actions.indexOfFirst { it.id == fromKey }
        val toIndex = actions.indexOfFirst { it.id == toKey }
        if (fromIndex >= 0 && toIndex >= 0) {
            onMoveAction(fromIndex, toIndex)
        }
    }

    LazyColumn(
        state = lazyListState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // Name-Feld
        item(key = "name") {
            OutlinedTextField(
                value = uiState.name,
                onValueChange = onNameChange,
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        // Aktiviert-Switch
        item(key = "enabled") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Makro aktiviert",
                    style = MaterialTheme.typography.bodyLarge
                )
                Switch(
                    checked = uiState.enabled,
                    onCheckedChange = onEnabledChange
                )
            }
        }

        // Trigger-Dropdown
        item(key = "trigger") {
            TriggerDropdown(
                selectedTrigger = uiState.trigger,
                onTriggerSelected = onTriggerChange
            )
        }

        // Trigger-Param-Felder (nur für param-tragende Trigger sichtbar)
        item(key = "trigger_params") {
            TriggerParamFields(
                trigger = uiState.trigger,
                onBatteryLevelChange = onBatteryLevelChange,
                onBatteryModeChange = onBatteryModeChange,
                onTimeScheduleIntervalChange = onTimeScheduleIntervalChange,
                onIntervalSecondsChange = onIntervalSecondsChange,
                onIntervalRunOnStartChange = onIntervalRunOnStartChange,
                onWifiSsidChange = onWifiSsidChange,
                onWifiModeChange = onWifiModeChange,
                onBluetoothNameChange = onBluetoothNameChange,
                onBluetoothModeChange = onBluetoothModeChange
            )
        }

        // Abschnitt: Aktionen
        item(key = "actions_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Aktionen",
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = onAddAction) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Aktion hinzufügen"
                    )
                }
            }
        }

        // Action-Liste mit Drag-to-Reorder und Akkordeon-Expand
        if (uiState.actions.isEmpty()) {
            item(key = "actions_empty") {
                Text(
                    text = "Keine Aktionen definiert. Tippe auf + um eine Aktion hinzuzufügen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(items = uiState.actions, key = { it.id }) { draft ->
                ReorderableItem(reorderableState, key = draft.id) { isDragging ->
                    val isExpanded = expandedActionId == draft.id
                    val currentIndex = uiState.actions.indexOfFirst { it.id == draft.id }

                    ActionDraftCard(
                        draft = draft,
                        isExpanded = isExpanded,
                        isDragging = isDragging,
                        isFirst = currentIndex == 0,
                        isLast = currentIndex == uiState.actions.lastIndex,
                        dragHandleModifier = Modifier.draggableHandle(),
                        onToggleExpand = {
                            expandedActionId = if (isExpanded) null else draft.id
                        },
                        onRemove = { onRemoveAction(draft.id) },
                        onTypeChange = { newType -> onActionTypeChange(draft.id, newType) },
                        onParamChange = { key, value -> onActionParamChange(draft.id, key, value) },
                        onMoveUp = {
                            if (currentIndex > 0) onMoveAction(currentIndex, currentIndex - 1)
                        },
                        onMoveDown = {
                            if (currentIndex < uiState.actions.lastIndex)
                                onMoveAction(currentIndex, currentIndex + 1)
                        }
                    )
                }
            }
        }
    }
}

/**
 * Dropdown für den Trigger-Typ mit menschenlesbaren deutschen Beschriftungen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TriggerDropdown(
    selectedTrigger: Trigger,
    onTriggerSelected: (Trigger) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    // Für param-tragende Trigger (BatteryLevel, TimeSchedule) matchen wir per Typ-Label,
    // nicht per Objekt-Gleichheit, damit das Dropdown beim Ändern der Params nicht kollabiert.
    val selectedLabel = selectedTrigger.triggerTypeLabel()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("Trigger") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            TRIGGER_OPTIONS.forEach { (trigger, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onTriggerSelected(trigger)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * Zeigt Eingabefelder für die Parameter param-tragender Trigger.
 * Für param-lose Trigger (OnStartup, SimCardDataConnected) wird nichts gerendert.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TriggerParamFields(
    trigger: Trigger,
    onBatteryLevelChange: (String) -> Unit,
    onBatteryModeChange: (String) -> Unit,
    onTimeScheduleIntervalChange: (String) -> Unit,
    onIntervalSecondsChange: (String) -> Unit = {},
    onIntervalRunOnStartChange: (Boolean) -> Unit = {},
    onWifiSsidChange: (String) -> Unit = {},
    onWifiModeChange: (String) -> Unit = {},
    onBluetoothNameChange: (String) -> Unit = {},
    onBluetoothModeChange: (String) -> Unit = {}
) {
    when (trigger) {
        is Trigger.BatteryLevel -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Schwellwert-Eingabe
                OutlinedTextField(
                    value = trigger.level.toString(),
                    onValueChange = onBatteryLevelChange,
                    label = { Text("Akkustand-Schwellwert (0–100 %)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                // Modus-Dropdown (unter/über)
                var modeExpanded by remember { mutableStateOf(false) }
                val modeLabel = when (trigger.mode) {
                    Trigger.BatteryLevel.MODE_ABOVE -> "über"
                    else -> "unter"
                }
                ExposedDropdownMenuBox(
                    expanded = modeExpanded,
                    onExpandedChange = { modeExpanded = it }
                ) {
                    OutlinedTextField(
                        value = modeLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Modus") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = modeExpanded,
                        onDismissRequest = { modeExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("unter") },
                            onClick = {
                                onBatteryModeChange(Trigger.BatteryLevel.MODE_BELOW)
                                modeExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("über") },
                            onClick = {
                                onBatteryModeChange(Trigger.BatteryLevel.MODE_ABOVE)
                                modeExpanded = false
                            }
                        )
                    }
                }
            }
        }
        is Trigger.TimeSchedule -> {
            OutlinedTextField(
                value = trigger.intervalMinutes.toString(),
                onValueChange = onTimeScheduleIntervalChange,
                label = { Text("Intervall in Minuten") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
        }
        is Trigger.Interval -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Intervall-Eingabe in Sekunden
                OutlinedTextField(
                    value = trigger.intervalSeconds.toString(),
                    onValueChange = onIntervalSecondsChange,
                    label = { Text("Intervall (Sekunden)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    supportingText = {
                        Text("Mindestens ${Trigger.Interval.MIN_INTERVAL_SECONDS} Sekunden — kleinere Werte werden von der Engine automatisch angehoben.")
                    }
                )
                // Sofort-Start-Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Sofort beim Start ausführen",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Switch(
                        checked = trigger.runOnStart,
                        onCheckedChange = onIntervalRunOnStartChange
                    )
                }
            }
        }
        is Trigger.WifiSsid -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // SSID-Eingabe
                OutlinedTextField(
                    value = trigger.ssid,
                    onValueChange = onWifiSsidChange,
                    label = { Text("WLAN-SSID") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                // Modus-Dropdown (verbunden/getrennt)
                var modeExpanded by remember { mutableStateOf(false) }
                val modeLabel = if (trigger.mode == Trigger.WifiSsid.MODE_DISCONNECTED) "getrennt" else "verbunden"
                ExposedDropdownMenuBox(
                    expanded = modeExpanded,
                    onExpandedChange = { modeExpanded = it }
                ) {
                    OutlinedTextField(
                        value = modeLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Modus") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = modeExpanded,
                        onDismissRequest = { modeExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("verbunden") },
                            onClick = {
                                onWifiModeChange(Trigger.WifiSsid.MODE_CONNECTED)
                                modeExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("getrennt") },
                            onClick = {
                                onWifiModeChange(Trigger.WifiSsid.MODE_DISCONNECTED)
                                modeExpanded = false
                            }
                        )
                    }
                }
            }
        }
        is Trigger.BluetoothDevice -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Gerätename-Eingabe
                OutlinedTextField(
                    value = trigger.deviceName,
                    onValueChange = onBluetoothNameChange,
                    label = { Text("Gerätename") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                // Modus-Dropdown (verbunden/getrennt)
                var modeExpanded by remember { mutableStateOf(false) }
                val modeLabel = if (trigger.mode == Trigger.BluetoothDevice.MODE_DISCONNECTED) "getrennt" else "verbunden"
                ExposedDropdownMenuBox(
                    expanded = modeExpanded,
                    onExpandedChange = { modeExpanded = it }
                ) {
                    OutlinedTextField(
                        value = modeLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Modus") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = modeExpanded,
                        onDismissRequest = { modeExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("verbunden") },
                            onClick = {
                                onBluetoothModeChange(Trigger.BluetoothDevice.MODE_CONNECTED)
                                modeExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("getrennt") },
                            onClick = {
                                onBluetoothModeChange(Trigger.BluetoothDevice.MODE_DISCONNECTED)
                                modeExpanded = false
                            }
                        )
                    }
                }
            }
        }
        // Param-lose Trigger: keine Felder
        is Trigger.OnStartup,
        is Trigger.SimCardDataConnected -> Unit
    }
}

// ---------------------------------------------------------------------------
// Action-Karte: Zusammenfassung + Akkordeon-Expand + Drag-Handle
// ---------------------------------------------------------------------------

/**
 * Eine einzelne Action-Karte im visuellen Builder.
 *
 * Im kollabierten Zustand zeigt sie:
 *  - Links: Drag-Handle (⠿ / DragHandle-Icon) zum Verschieben per Drag-Geste
 *  - Mitte: kurze menschenlesbare Zusammenfassung ([ActionDraft.summaryText])
 *  - Rechts: Löschen-Button
 *
 * Beim Antippen der Zusammenfassungszeile klappt die Konfigurationsansicht
 * auf (Typ-Dropdown + Param-Felder), beim erneuten Antippen wieder zu.
 *
 * @param isDragging  True während des aktiven Drag-Vorgangs (für visuelles Feedback).
 * @param isFirst / [isLast]  Steuern, ob Pfeil-Hoch / Pfeil-Runter als zusätzliche
 *        Reorder-Fallback-Buttons ausgeblendet werden (erste Zeile hat kein Hoch,
 *        letzte kein Runter).
 * @param dragHandleModifier  Modifier mit `.draggableHandle()` vom ReorderableItem-Scope.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionDraftCard(
    draft: ActionDraft,
    isExpanded: Boolean,
    isDragging: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    dragHandleModifier: Modifier,
    onToggleExpand: () -> Unit,
    onRemove: () -> Unit,
    onTypeChange: (String) -> Unit,
    onParamChange: (String, String) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isDragging) 8.dp else 2.dp
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isDragging)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {

            // ── Zusammenfassungszeile (immer sichtbar) ──────────────────────
            Surface(
                onClick = onToggleExpand,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Drag-Handle links (Menu-Icon als visueller Griff; DragHandle wäre in extended icons)
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = "Aktion verschieben",
                        modifier = dragHandleModifier
                            .padding(horizontal = 4.dp)
                            .size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Pfeil hoch/runter als barrierefreie Fallback-Reorder-Buttons
                    // (immer vorhanden, aber ausgeblendet für erste/letzte Position)
                    IconButton(
                        onClick = onMoveUp,
                        enabled = !isFirst,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowUp,
                            contentDescription = "Aktion nach oben",
                            modifier = Modifier.size(18.dp),
                            tint = if (isFirst)
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onMoveDown,
                        enabled = !isLast,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Aktion nach unten",
                            modifier = Modifier.size(18.dp),
                            tint = if (isLast)
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Zusammenfassungstext (wächst auf verfügbare Breite)
                    Text(
                        text = draft.summaryText(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    // Löschen-Button rechts
                    IconButton(onClick = onRemove) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Aktion entfernen",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // ── Expandierter Konfigurationsbereich (Akkordeon) ──────────────
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Typ-Dropdown
                    ActionTypeDropdown(
                        selectedType = draft.type,
                        onTypeSelected = onTypeChange,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Param-Felder für den gewählten Aktionstyp
                    val paramFields = paramFieldsForActionType(draft.type)
                    paramFields.forEach { (key, label, keyboardType) ->
                        OutlinedTextField(
                            value = draft.params[key] ?: "",
                            onValueChange = { onParamChange(key, it) },
                            label = { Text(label) },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                            // Befehle und Nachrichten können mehrzeilig sein; Zahlen und Flags einzeilig
                            singleLine = keyboardType != KeyboardType.Text
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dropdown für den Action-Typ.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionTypeDropdown(
    selectedType: String,
    onTypeSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedType,
            onValueChange = {},
            readOnly = true,
            label = { Text("Aktionstyp") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            ACTION_TYPE_OPTIONS.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type) },
                    onClick = {
                        onTypeSelected(type)
                        expanded = false
                    }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tab 2: Expert-Scripting
// ---------------------------------------------------------------------------

/**
 * Expert-Tab: großes mehrzeiliges Textfeld für den rohen JSON-String.
 * Zeigt einen Inline-Fehlermarker wenn das JSON ungültig ist.
 * Nimmt den rawJson-Wert aus dem ViewModel-State und gibt Tipp-Eingaben zurück.
 */
@Composable
private fun ExpertScriptingTab(
    rawJson: String,
    jsonError: String?,
    onRawJsonChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "JSON-Konfiguration",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "Bearbeite das Makro direkt als JSON. Gültige Eingaben werden sofort im Visuellen Builder übernommen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(4.dp))

        OutlinedTextField(
            value = rawJson,
            onValueChange = onRawJsonChange,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            label = { Text("Makro-JSON") },
            // Fehlermarker: Rahmen und Fehlertext in Fehlerfarbe
            isError = jsonError != null,
            supportingText = jsonError?.let { error ->
                {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            // Monospace-Schrift für lesbares JSON
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace
            ),
            minLines = 10
        )
    }
}

// ---------------------------------------------------------------------------
// Previews — Fake-Daten, kein Repository nötig
// ---------------------------------------------------------------------------

@Preview(showBackground = true, name = "Editor – Visueller Builder (Tab 1)")
@Composable
private fun PreviewVisualBuilder() {
    val fakeState = EditorUiState(
        name = "Start-Routine",
        enabled = true,
        trigger = Trigger.OnStartup,
        actions = listOf(
            ActionDraft(id = "a1", type = "log", params = mapOf("message" to "Hallo Welt")),
            ActionDraft(id = "a2", type = "wait", params = mapOf("durationMs" to "2000")),
            ActionDraft(id = "a3", type = "execute_shell_script", params = mapOf("command" to "ls /"))
        ),
        rawJson = """{"id":"1","name":"Start-Routine","enabled":true,"trigger":{"type":"on_startup"},"actions":[]}"""
    )
    RoutinesTheme {
        MacroEditorContent(
            uiState = fakeState,
            onNameChange = {},
            onEnabledChange = {},
            onTriggerChange = {},
            onAddAction = {},
            onRemoveAction = {},
            onActionTypeChange = { _, _ -> },
            onActionParamChange = { _, _, _ -> },
            onMoveAction = { _, _ -> },
            onRawJsonChange = {},
            onSave = {},
            onNavigateBack = {}
        )
    }
}

@Preview(showBackground = true, name = "Editor – Expert-Tab mit JSON-Fehler")
@Composable
private fun PreviewExpertTabWithError() {
    val fakeState = EditorUiState(
        name = "Daten-Routine",
        enabled = false,
        trigger = Trigger.SimCardDataConnected,
        actions = emptyList(),
        rawJson = """{"id":"2","name":"Daten-Routine","enabled":false,KAPUTT}""",
        jsonError = "Ungültiges JSON — bitte korrigieren"
    )
    RoutinesTheme {
        MacroEditorContent(
            uiState = fakeState,
            onNameChange = {},
            onEnabledChange = {},
            onTriggerChange = {},
            onAddAction = {},
            onRemoveAction = {},
            onActionTypeChange = { _, _ -> },
            onActionParamChange = { _, _, _ -> },
            onMoveAction = { _, _ -> },
            onRawJsonChange = {},
            onSave = {},
            onNavigateBack = {}
        )
    }
}

@Preview(showBackground = true, name = "Editor – Ladeindikator")
@Composable
private fun PreviewLoading() {
    RoutinesTheme {
        MacroEditorContent(
            uiState = EditorUiState(isLoading = true),
            onNameChange = {},
            onEnabledChange = {},
            onTriggerChange = {},
            onAddAction = {},
            onRemoveAction = {},
            onActionTypeChange = { _, _ -> },
            onActionParamChange = { _, _, _ -> },
            onMoveAction = { _, _ -> },
            onRawJsonChange = {},
            onSave = {},
            onNavigateBack = {}
        )
    }
}

@Preview(showBackground = true, name = "Editor – Leere Aktion fire_app_intent")
@Composable
private fun PreviewFireAppIntent() {
    val fakeState = EditorUiState(
        name = "Intent-Makro",
        enabled = true,
        trigger = Trigger.OnStartup,
        actions = listOf(
            ActionDraft(
                id = "a1",
                type = "fire_app_intent",
                params = mapOf("package" to "com.example.app", "action" to "android.intent.action.VIEW")
            )
        ),
        rawJson = ""
    )
    RoutinesTheme {
        MacroEditorContent(
            uiState = fakeState,
            onNameChange = {},
            onEnabledChange = {},
            onTriggerChange = {},
            onAddAction = {},
            onRemoveAction = {},
            onActionTypeChange = { _, _ -> },
            onActionParamChange = { _, _, _ -> },
            onMoveAction = { _, _ -> },
            onRawJsonChange = {},
            onSave = {},
            onNavigateBack = {}
        )
    }
}

@Preview(showBackground = true, name = "Editor – Akkustand-Trigger mit Params")
@Composable
private fun PreviewBatteryTrigger() {
    val fakeState = EditorUiState(
        name = "Akku-Routine",
        enabled = true,
        trigger = Trigger.BatteryLevel(level = 20, mode = Trigger.BatteryLevel.MODE_BELOW),
        actions = listOf(
            ActionDraft(
                id = "a1",
                type = "show_notification",
                params = mapOf("title" to "Akku niedrig", "message" to "Bitte laden")
            )
        ),
        rawJson = ""
    )
    RoutinesTheme {
        MacroEditorContent(
            uiState = fakeState,
            onNameChange = {},
            onEnabledChange = {},
            onTriggerChange = {},
            onAddAction = {},
            onRemoveAction = {},
            onActionTypeChange = { _, _ -> },
            onActionParamChange = { _, _, _ -> },
            onMoveAction = { _, _ -> },
            onRawJsonChange = {},
            onSave = {},
            onNavigateBack = {}
        )
    }
}

@Preview(showBackground = true, name = "Editor – Zeitplan-Trigger + HTTP-Request-Aktion")
@Composable
private fun PreviewTimeScheduleAndHttpRequest() {
    val fakeState = EditorUiState(
        name = "Ping-Routine",
        enabled = true,
        trigger = Trigger.TimeSchedule(intervalMinutes = 30),
        actions = listOf(
            ActionDraft(
                id = "a1",
                type = "http_request",
                params = mapOf("url" to "https://example.com/ping", "method" to "GET")
            )
        ),
        rawJson = ""
    )
    RoutinesTheme {
        MacroEditorContent(
            uiState = fakeState,
            onNameChange = {},
            onEnabledChange = {},
            onTriggerChange = {},
            onAddAction = {},
            onRemoveAction = {},
            onActionTypeChange = { _, _ -> },
            onActionParamChange = { _, _, _ -> },
            onMoveAction = { _, _ -> },
            onRawJsonChange = {},
            onSave = {},
            onNavigateBack = {}
        )
    }
}

@Preview(showBackground = true, name = "Editor – Intervall-Trigger + HTTP-Request-Aktion")
@Composable
private fun PreviewIntervalAndHttpRequest() {
    val fakeState = EditorUiState(
        name = "Polling-Routine",
        enabled = true,
        trigger = Trigger.Interval(intervalSeconds = 30, runOnStart = true),
        actions = listOf(
            ActionDraft(
                id = "a1",
                type = "http_request",
                params = mapOf("url" to "https://example.com/status", "method" to "GET")
            )
        ),
        rawJson = ""
    )
    RoutinesTheme {
        MacroEditorContent(
            uiState = fakeState,
            onNameChange = {},
            onEnabledChange = {},
            onTriggerChange = {},
            onAddAction = {},
            onRemoveAction = {},
            onActionTypeChange = { _, _ -> },
            onActionParamChange = { _, _, _ -> },
            onMoveAction = { _, _ -> },
            onRawJsonChange = {},
            onSave = {},
            onNavigateBack = {}
        )
    }
}

@Preview(showBackground = true, name = "Editor – Wait-Aktion")
@Composable
private fun PreviewWaitAction() {
    val fakeState = EditorUiState(
        name = "Warte-Routine",
        enabled = true,
        trigger = Trigger.OnStartup,
        actions = listOf(
            ActionDraft(id = "a1", type = "log", params = mapOf("message" to "Start")),
            ActionDraft(id = "a2", type = "wait", params = mapOf("durationMs" to "5000")),
            ActionDraft(id = "a3", type = "log", params = mapOf("message" to "Ende"))
        ),
        rawJson = ""
    )
    RoutinesTheme {
        MacroEditorContent(
            uiState = fakeState,
            onNameChange = {},
            onEnabledChange = {},
            onTriggerChange = {},
            onAddAction = {},
            onRemoveAction = {},
            onActionTypeChange = { _, _ -> },
            onActionParamChange = { _, _, _ -> },
            onMoveAction = { _, _ -> },
            onRawJsonChange = {},
            onSave = {},
            onNavigateBack = {}
        )
    }
}
