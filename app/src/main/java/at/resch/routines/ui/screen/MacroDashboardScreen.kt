package at.resch.routines.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.resch.routines.domain.model.Action
import at.resch.routines.domain.model.MacroScript
import at.resch.routines.domain.model.Trigger
import at.resch.routines.ui.theme.RoutinesTheme
import at.resch.routines.ui.viewmodel.DashboardUiState
import at.resch.routines.ui.viewmodel.MacroDashboardViewModel

/**
 * Einstiegspunkt für den Dashboard-Screen.
 * Erzeugt das ViewModel per Factory und delegiert State an [MacroDashboardContent].
 *
 * @param onEditMacro Callback zum Öffnen eines vorhandenen Makros im Editor (per ID).
 * @param onNewMacro Callback zum Öffnen des Editors für ein neues Makro.
 */
@Composable
fun MacroDashboardScreen(
    viewModel: MacroDashboardViewModel,
    onEditMacro: (MacroScript) -> Unit = {},
    onNewMacro: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    MacroDashboardContent(
        uiState = uiState,
        onToggle = viewModel::toggleEnabled,
        onEditMacro = onEditMacro,
        onNewMacro = onNewMacro
    )
}

/**
 * Zustandsloser (stateless) Haupt-Compose-Baum.
 * Kann isoliert getestet und previewed werden ohne echtes ViewModel.
 *
 * @param onEditMacro Callback zum Öffnen eines vorhandenen Makros im Editor.
 * @param onNewMacro Callback zum Öffnen des Editors für ein neues Makro (FAB).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroDashboardContent(
    uiState: DashboardUiState,
    onToggle: (MacroScript) -> Unit,
    onEditMacro: (MacroScript) -> Unit = {},
    onNewMacro: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Routines") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewMacro) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Neues Makro erstellen"
                )
            }
        }
    ) { innerPadding ->
        when (uiState) {
            is DashboardUiState.Loading -> LoadingState(modifier = Modifier.padding(innerPadding))
            is DashboardUiState.Empty -> EmptyState(modifier = Modifier.padding(innerPadding))
            is DashboardUiState.Success -> MacroList(
                macros = uiState.macros,
                onToggle = onToggle,
                onEdit = onEditMacro,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}

/** Zeigt einen Ladeindikator solange der erste DB-Emit noch aussteht. */
@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

/** Leerer Zustand: keine Makros in der DB. */
@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Keine Makros vorhanden",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Erstelle dein erstes Makro mit dem Editor.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Scrollbare Liste aller Makros. */
@Composable
private fun MacroList(
    macros: List<MacroScript>,
    onToggle: (MacroScript) -> Unit,
    onEdit: (MacroScript) -> Unit = {},
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items = macros, key = { it.id }) { macro ->
            MacroRow(macro = macro, onToggle = onToggle, onEdit = onEdit)
        }
    }
}

/**
 * Eine Zeile im Dashboard: Name, Trigger-Zusammenfassung, Enable/Disable-Switch.
 * Klick auf die Karte öffnet den Editor für dieses Makro.
 * Stateless — bekommt alles von oben.
 */
@Composable
private fun MacroRow(
    macro: MacroScript,
    onToggle: (MacroScript) -> Unit,
    onEdit: (MacroScript) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onEdit(macro) },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (macro.enabled)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = macro.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (macro.enabled)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = macro.trigger.humanReadable(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(
                checked = macro.enabled,
                onCheckedChange = { onToggle(macro) },
                modifier = Modifier.padding(start = 12.dp)
            )
        }
    }
}

/** Menschenlesbare Beschriftung für jeden bekannten Trigger-Typ (Deutsch, kompakt). */
private fun Trigger.humanReadable(): String = when (this) {
    is Trigger.OnStartup -> "Beim Gerätestart"
    is Trigger.SimCardDataConnected -> "Mobile Daten verbunden"
    is Trigger.BatteryLevel -> {
        val modusLabel = if (mode == Trigger.BatteryLevel.MODE_ABOVE) "über" else "unter"
        "Akkustand $modusLabel $level%"
    }
    is Trigger.TimeSchedule -> "Alle $intervalMinutes Min."
    is Trigger.WifiSsid ->
        "WLAN ${if (mode == Trigger.WifiSsid.MODE_DISCONNECTED) "getrennt von" else "verbunden mit"} '$ssid'"
    is Trigger.BluetoothDevice ->
        "Bluetooth ${if (mode == Trigger.BluetoothDevice.MODE_DISCONNECTED) "getrennt:" else "verbunden:"} '$deviceName'"
}

// ---------------------------------------------------------------------------
// Previews — arbeiten mit Fake-Daten, kein echtes Repository noetig
// ---------------------------------------------------------------------------

@Preview(showBackground = true, name = "Dashboard – Ladeliste")
@Composable
private fun PreviewLoading() {
    RoutinesTheme {
        MacroDashboardContent(
            uiState = DashboardUiState.Loading,
            onToggle = {},
            onEditMacro = {},
            onNewMacro = {}
        )
    }
}

@Preview(showBackground = true, name = "Dashboard – Leer")
@Composable
private fun PreviewEmpty() {
    RoutinesTheme {
        MacroDashboardContent(
            uiState = DashboardUiState.Empty,
            onToggle = {},
            onEditMacro = {},
            onNewMacro = {}
        )
    }
}

@Preview(showBackground = true, name = "Dashboard – Mit Makros")
@Composable
private fun PreviewSuccess() {
    val fakeMacros = listOf(
        MacroScript(
            id = "1",
            name = "Start-Routine",
            enabled = true,
            trigger = Trigger.OnStartup,
            actions = listOf(Action("log", mapOf("message" to "gestartet")))
        ),
        MacroScript(
            id = "2",
            name = "Daten-Routine",
            enabled = false,
            trigger = Trigger.SimCardDataConnected,
            actions = emptyList()
        ),
        MacroScript(
            id = "3",
            name = "Akku-Warnung",
            enabled = true,
            trigger = Trigger.BatteryLevel(level = 15, mode = Trigger.BatteryLevel.MODE_BELOW),
            actions = listOf(Action("show_notification", mapOf("title" to "Akku niedrig", "message" to "Unter 15%")))
        ),
        MacroScript(
            id = "4",
            name = "Stündlicher Ping",
            enabled = true,
            trigger = Trigger.TimeSchedule(intervalMinutes = 60),
            actions = listOf(Action("http_request", mapOf("url" to "https://example.com/ping", "method" to "GET")))
        )
    )
    RoutinesTheme {
        MacroDashboardContent(
            uiState = DashboardUiState.Success(fakeMacros),
            onToggle = {},
            onEditMacro = {},
            onNewMacro = {}
        )
    }
}
