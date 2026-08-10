---
name: project-architecture
description: Routines app structure, key domain/data types, and UI layer boundaries
metadata:
  type: project
---

Package root: `at.resch.routines`

**Domain model** (`domain/model/MacroScript.kt`):
- `MacroScript(id, name, enabled, trigger: Trigger, actions: List<Action>)`
- `sealed class Trigger`: `OnStartup`, `SimCardDataConnected` (param-less data objects); param-carrying data classes: `BatteryLevel(level: Int, mode: String)` (@SerialName "battery_level"), `TimeSchedule(intervalMinutes: Int)` (@SerialName "time_schedule"), `Interval(intervalSeconds: Int, runOnStart: Boolean = false)` (@SerialName "interval", companion `MIN_INTERVAL_SECONDS=5`), `WifiSsid(ssid: String, mode: String)` (@SerialName "wifi_ssid"), `BluetoothDevice(deviceName: String, mode: String)` (@SerialName "bluetooth_device")
- `Trigger.BatteryLevel`/`WifiSsid`/`BluetoothDevice` companions: mode constants (`MODE_BELOW`/`MODE_ABOVE` or `MODE_CONNECTED`/`MODE_DISCONNECTED`)
- `Action(type: String, params: Map<String,String>)`
- Whenever a new param-carrying `Trigger` subtype is added to the domain model, two **exhaustive `when(trigger)`** blocks in `ui/` must get a new branch or the build breaks: `TriggerParamFields` in `MacroEditorScreen.kt` and `Trigger.humanReadable()` in `MacroDashboardScreen.kt`. Also add to `TRIGGER_OPTIONS` list + `triggerTypeLabel()` in `MacroEditorScreen.kt`, and thread the new onXChange callback through `MacroEditorScreen` → `MacroEditorContent` (default `= {}`) → `VisualBuilderTab` (no default) → `TriggerParamFields` (default `= {}`).

**Repository** (`data/MacroRepository.kt`):
- Constructor takes `MacroDao` — get via `AppDatabase.getInstance(ctx).macroDao()`
- `observeAll(): Flow<List<MacroScript>>` — skips unparseable entries gracefully
- `observeAllRaw(): Flow<List<MacroEntity>>` — raw JSON for editor screen
- `save(macro: MacroScript)` — serializes to JSON and upserts
- `saveRaw(id, configuration)` — for expert/import path
- `getById(id): MacroScript?`
- `deleteById(id)`
- `parseOrNull(json): MacroScript?` — central parse helper

**Theme**: `ui/theme/` — `RoutinesTheme`, `Color.kt`, `Type.kt` — use these, don't create new ones.

**DB**: `AppDatabase.getInstance(context)` singleton — Room, single entity `MacroEntity`.

**Navigation** (nav-compose 2.9.0, already in libs.versions.toml + build.gradle):
- Routes defined in private `Routes` object in `MainActivity.kt`
- `dashboard` (start), `editor?macroId={macroId}` (macroId nullable, absent = new macro)
- `NavHost` in `RoutinesNavHost` composable; ViewModels instantiated per-destination via `viewModel(factory=...)`

**Screens built**:
- `MacroDashboardScreen` — list, toggle, FAB (new macro), click row (edit macro)
- `MacroEditorScreen` — two-tab editor (Visual Builder + Expert Scripting)

**ViewModels**:
- `MacroDashboardViewModel` — `DashboardUiState` sealed interface, `toggleEnabled`, `Factory(repository)`
- `MacroEditorViewModel` — `EditorUiState` + `ActionDraft`, bidirectional form↔JSON sync, `Factory(repository, macroId?)`

**Serialization**: `data/MacroJson.kt` exports `MacroJson` (compact, persistence) and `MacroJsonPretty` (prettyPrint=true, 2-space indent, UI display only). ViewModel uses `MacroJsonPretty.encodeToString` in `regenerateJson()` for the Expert tab display. Validation via `repository.parseOrNull` (uses compact MacroJson, but parses pretty JSON fine).

**Action types supported**: `log`, `wait` (durationMs: Number), `execute_shell_script`, `toggle_hotspot`, `fire_app_intent`, `show_notification` (title+message), `http_request` (url, method, body). Core team added `WaitActionExecutor` with same `durationMs` key.

**Trigger param handlers in ViewModel** (`MacroEditorViewModel.kt`): `onBatteryLevelChange(String)`, `onBatteryModeChange(String)`, `onTimeScheduleIntervalChange(String)` — default invalid input to 20/MODE_BELOW/15 respectively. `onWifiSsidChange`/`onWifiModeChange`, `onBluetoothNameChange`/`onBluetoothModeChange`, `onIntervalSecondsChange`/`onIntervalRunOnStartChange` — these three pairs use `as? Trigger.X ?: return` guards (no-op if current trigger isn't that type) rather than defaulting, since they only make sense when that trigger is already selected. `onIntervalSecondsChange` defaults invalid/empty input to 60. All call `regenerateJson()`.

**Action list UI pattern** (MacroEditor Visual Builder tab):
- Accordion: `remember { mutableStateOf<String?>(null) }` tracks `expandedActionId`. Tapping summary row toggles expand; only one row open at a time.
- Summary row shows type-specific text via `ActionDraft.summaryText()` extension fun (pure, no business logic).
- Drag-to-reorder: `sh.calvin.reorderable:reorderable:2.4.3` (on mavenCentral, already cached). Wrap `LazyColumn` with `rememberReorderableLazyListState` + `ReorderableItem`. `.draggableHandle()` Modifier applied to the Menu icon (not DragHandle — that's in extended icons, not core). Up/down arrow buttons also present as accessible fallback.
- `onMoveAction(fromIndex: Int, toIndex: Int)` on ViewModel — reorders `EditorUiState.actions` list and calls `regenerateJson()`.

**Icons available in `material-icons-core`**: `Add`, `ArrowBack`, `Check`, `Clear`, `Close`, `Delete`, `Edit`, `KeyboardArrowDown`, `KeyboardArrowUp`, `Menu`, `MoreVert`, `Search`, `Settings`. DragHandle is in *extended* icons — use `Icons.Filled.Menu` as drag handle stand-in.

**Strict rule**: No business logic, JSON handling, or parsing in `ui/`. Call repository methods only. All UI code goes in `app/src/main/java/at/resch/routines/ui/`.

Why: Layer separation mandate from CLAUDE.md — System Implementer owns core/, UI Designer owns ui/ only.
How to apply: Any JSON work (serialize, parse, validate) → delegate to repository or domain layer, never do it in ViewModel or Screen.
