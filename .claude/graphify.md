# graphify - Android Automation App Knowledge Base

## Core Architecture (Clean Architecture)

```
ui/        (Presentation)  → Compose Screens, ViewModels (MVVM), reiner JSON-Editor
    ↓
domain/    (Domain)        → Modelle (MacroScript/Trigger/Action), Use Cases, Verträge
    ↓
core/      (Core Engine)   → Foreground Service, SharedFlow-EventBus, Evaluator,
                             Trigger-Quellen, Action-Executoren, Root/Shell
    ↓
data/      (Data)          → Room DB (schlank: macro_id + configuration JSON), Repository
```

## Data Philosophy

Ein Makro ist ein roher JSON-String. Die Room-DB (`MacroEntity`) hält **genau zwei
Spalten**: `macro_id` (PK) und `configuration` (roher JSON). Keine Relationen,
keine redundanten Spalten — `name`/`enabled`/`trigger`/`actions` leben im JSON und
werden erst im Domain-Layer (`MacroScript`) geparst.

## JSON Macro Schema (aktuell, strukturiert)

```json
{
  "id": "macro_001",
  "name": "Start-Routine",
  "enabled": true,
  "trigger": { "type": "on_startup" },
  "actions": [
    { "type": "execute_shell_script", "params": { "command": "svc wifi enable" } },
    { "type": "log", "params": { "message": "fertig" } }
  ]
}
```

- **Trigger** ist polymorph (kotlinx sealed class). JSON-Diskriminator = `"type"`.
  Aktuelle Typen: `on_startup`, `sim_card_data_connected`.
- **Action** ist nicht-polymorph: `type` (String-ID) + `params` (String-Map).
- ⚠️ Historie: Ein früheres Schema nutzte `"trigger": "BOOT"` (String) und
  `"command"` direkt auf der Action. Das ist **überholt** — nicht mehr verwenden.

## Key Components

### domain/model/MacroScript.kt
- `MacroScript`, sealed `Trigger` (OnStartup, SimCardDataConnected), `Action`, `ActionResult`
- Tests: `src/test/.../domain/model/MacroScriptSerializationTest.kt`


### core/action/StrategyActionExecutor.kt (Basis für ALLE Actions)
- Abstrakte Basis: Fallback-Kette + Capability-Cache + Root-Detection.
- Jeder konkrete Executor überschreibt nur strategies(action): List<Strategy>.
- Garantiert ROM-/versions-Robustheit projektweit (Compatibility Contract).
- CapabilityCache: core/cache/CapabilityCache.kt (SharedPreferences).
- Tests: Basis-Verhalten in StrategyActionExecutorTest, pro Action nur die
  strategies()-Liste.

### core/BootReceiver.kt
- Trigger `on_startup` nach Boot. Manifest: `RECEIVE_BOOT_COMPLETED`.
- Phase 2: emittiert an EventBus, startet Foreground Service.

### core/NetworkStatusTracker.kt
- Trigger `sim_card_data_connected` via `ConnectivityManager` callbackFlow
  (`TRANSPORT_CELLULAR`). Manifest: `ACCESS_NETWORK_STATE`.


### data/ (Room)
- `MacroEntity` (macro_id + configuration), `MacroDao`, `AppDatabase`.

### ui/ (Phase 5, geplant)
- `MacroDashboardScreen` + ViewModel, `MacroEditorScreen` (Visual Builder + Expert JSON).

## Testing Strategy
- Unit (`src/test/`): JUnit4, MockK, Turbine, kotlinx-coroutines-test.
- UI/Integration (`src/androidTest/`): Compose UI Test, MockK-Android, Turbine.

## Delegation Pattern
1. Architect definiert das Was (Feature Spec) + konsultiert diesen Graph.
2. System Implementer baut `core/` (+ Domain-Verträge).
3. UI Designer baut `ui/` (Presentation).
4. QA Automator schreibt alle Tests (Verification Gate).
5. Zurück zum Architect zur Freigabe.
