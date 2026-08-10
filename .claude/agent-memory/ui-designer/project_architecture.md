---
name: project-architecture
description: Routines app structure, key domain/data types, and UI layer boundaries
metadata:
  type: project
---

Package root: `at.resch.routines`

**Domain model** (`domain/model/MacroScript.kt`):
- `MacroScript(id, name, enabled, trigger: Trigger, actions: List<Action>)`
- `sealed class Trigger`: `OnStartup`, `SimCardDataConnected` (param-less data objects), `BatteryLevel(level: Int, mode: String)` (@SerialName "battery_level"), `TimeSchedule(intervalMinutes: Int)` (@SerialName "time_schedule")
- `Trigger.BatteryLevel` companion: `MODE_BELOW="below"`, `MODE_ABOVE="above"`
- `Action(type: String, params: Map<String,String>)`

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

**Trigger param handlers in ViewModel** (Phase 4): `onBatteryLevelChange(String)`, `onBatteryModeChange(String)`, `onTimeScheduleIntervalChange(String)` — default invalid input to 20/MODE_BELOW/15 respectively. All call `regenerateJson()`.

**Action list UI pattern** (MacroEditor Visual Builder tab):
- Accordion: `remember { mutableStateOf<String?>(null) }` tracks `expandedActionId`. Tapping summary row toggles expand; only one row open at a time.
- Summary row shows type-specific text via `ActionDraft.summaryText()` extension fun (pure, no business logic).
- Drag-to-reorder: `sh.calvin.reorderable:reorderable:2.4.3` (on mavenCentral, already cached). Wrap `LazyColumn` with `rememberReorderableLazyListState` + `ReorderableItem`. `.draggableHandle()` Modifier applied to the Menu icon (not DragHandle — that's in extended icons, not core). Up/down arrow buttons also present as accessible fallback.
- `onMoveAction(fromIndex: Int, toIndex: Int)` on ViewModel — reorders `EditorUiState.actions` list and calls `regenerateJson()`.

**Icons available in `material-icons-core`**: `Add`, `ArrowBack`, `Check`, `Clear`, `Close`, `Delete`, `Edit`, `KeyboardArrowDown`, `KeyboardArrowUp`, `Menu`, `MoreVert`, `Search`, `Settings`. DragHandle is in *extended* icons — use `Icons.Filled.Menu` as drag handle stand-in.

**Strict rule**: No business logic, JSON handling, or parsing in `ui/`. Call repository methods only. All UI code goes in `app/src/main/java/at/resch/routines/ui/`.

Why: Layer separation mandate from CLAUDE.md — System Implementer owns core/, UI Designer owns ui/ only.
How to apply: Any JSON work (serialize, parse, validate) → delegate to repository or domain layer, never do it in ViewModel or Screen.
