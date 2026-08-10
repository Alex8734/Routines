---
name: project-phase6-wait-reorder-tests
description: Phase 6 tests — WaitActionExecutor, MacroEngine sequential ordering, MacroEditorViewModel reorder+wait round-trip; gate closed at 216 total
metadata:
  type: project
---

35 new tests added across 3 new files, raising the suite from 181 to 216 tests (0 failures).

**Why:** verification gate for `wait` action, `onMoveAction`, and wait action round-trip through the editor ViewModel.

**How to apply:** follow established patterns from [[project_phase4_tests]] and [[project_phase5_editor_tests]] for future test batches.

## New test files

- `app/src/test/java/at/resch/routines/core/action/WaitActionExecutorTest.kt` — 14 tests
- `app/src/test/java/at/resch/routines/core/MacroEngineSequentialOrderTest.kt` — 6 tests
- `app/src/test/java/at/resch/routines/ui/viewmodel/MacroEditorReorderAndWaitTest.kt` — 15 tests

## Key patterns / pitfalls discovered

### `currentTime` inside inner classes
`currentTime` is a property on `TestScope`, not importable directly inside inner or anonymous
class methods. Solution: pass `timeSupplier: () -> Long` (a lambda that captures `currentTime`
from the outer `runTest` lambda) into the anonymous executor at construction time.

### ViewModel constructor parameter name
The constructor param is `initialMacroId`, not `macroId`. Always check the actual signature
before using named arguments.

### `runTest` virtual time and `delay`
`WaitActionExecutor` uses plain `kotlinx.coroutines.delay` (no injected dispatcher), so it
correctly honors virtual time in `runTest` — no `TestCoroutineDispatcher` injection needed.
`currentTime` advances by exactly the requested `durationMs` (or `MAX_DURATION_MS` if clamped).

### `MacroEditorViewModel` — parseOrNull stub
Stub must delegate to `MacroJson.decodeFromString<MacroScript>()` wrapped in `runCatching`
(see existing `MacroEditorViewModelTest.setUp()`). The ViewModel calls `parseOrNull` from
`onRawJsonChange` to validate/sync JSON→form.
