---
name: project-phase4-tests
description: Phase 4 Batch 1 verification gate — 74 new tests, all green, total suite 181
metadata:
  type: project
---

Phase 4 Batch 1 verification gate closed: 6 new test files, 74 new tests. Suite total: 181 tests, 0 failures, 0 errors.

**Why:** Covers new triggers (BatteryTriggerSource, TimeScheduleTriggerSource), new actions (ShowNotificationActionExecutor, HttpRequestActionExecutor), SystemEvent.matches logic, and MacroJsonPretty.

**How to apply:** For future trigger/action tests, use these patterns from this batch.

## Key patterns discovered

### HttpRequestActionExecutor dispatcher
Inject `UnconfinedTestDispatcher()` (NOT `StandardTestDispatcher()`) as `ioDispatcher`.
A fresh `StandardTestDispatcher()` in `@Before` uses a different scheduler than `runTest`, so `withContext` work is never drained. `UnconfinedTestDispatcher` processes work eagerly.

### BatteryTriggerSource idempotency test
Use `MutableStateFlow` (never-completing) for the flow, not `flowOf()`. `flowOf()` completes immediately, making `job?.isActive == false` true when the second `start()` is called — defeating the idempotency guard.

### TimeScheduleTriggerSource cancellation
The production loop is `while(isActive) { wait(interval); emit(TimeTick) }`. After `scope.cancel()` inside a `DelayProvider` lambda, the `emit` that follows in the same iteration still executes (cooperative cancellation, checked at next suspension point). Assert `>= N` not `== N` for tick counts, or cancel+assert on a single iteration.

### Log.w under unit tests
`unitTests.isReturnDefaultValues = true` handles `android.util.Log` calls (returns 0). No Robolectric or Log-stub needed.

## New test files (all in app/src/test/)
- `core/trigger/BatteryTriggerSourceTest.kt` — 8 tests
- `core/trigger/TimeScheduleTriggerSourceTest.kt` — 10 tests
- `core/action/ShowNotificationActionExecutorTest.kt` — 10 tests
- `core/action/HttpRequestActionExecutorTest.kt` — 18 tests
- `core/SystemEventMatchesTest.kt` — 19 tests
- `data/MacroJsonPrettyTest.kt` — 9 tests (includes MacroJson.encodeToString inline call)

## Suite breakdown at gate close
- FireAppIntentActionExecutorTest: 10
- HttpRequestActionExecutorTest: 18 (new)
- ShellScriptActionExecutorTest: 10
- ShowNotificationActionExecutorTest: 10 (new)
- ToggleHotspotActionExecutorTest: 6
- EventBusTest: 7
- MacroEngineTest: 10
- MacroEvaluatorTest: 7
- NetworkTriggerSourceTest: 6
- SystemEventMatchesTest: 19 (new)
- BatteryTriggerSourceTest: 8 (new)
- TriggerRegistryTest: 8
- TimeScheduleTriggerSourceTest: 10 (new)
- RootCheckerTest: 7
- ShellExecutorTest: 8
- MacroJsonPrettyTest: 9 (new)
- MacroRepositoryTest: 12
- MacroScriptSerializationTest: 3
- ExampleUnitTest: 1
- MacroEditorViewModelTest: 12
- **Total: 181**
