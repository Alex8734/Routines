---
name: project-phase5-editor-tests
description: Phase 5 MacroEditorViewModel verification gate — 12 tests, all green, total suite 107
metadata:
  type: project
---

Phase 5 verification gate closed: `MacroEditorViewModelTest` (12 tests) passes in full. Suite total: 107 tests, 0 failures, 0 errors.

**Why:** The draft test file written before this session was already correct. No test changes were needed. The production code and test stubs matched exactly.

**How to apply:** For future ViewModel tests, reference this file's patterns:
- `Dispatchers.setMain(StandardTestDispatcher())` in `@Before`, reset in `@After`
- `repository.parseOrNull` stubbed with real `MacroJson.decodeFromString` via `every { } answers { runCatching { }.getOrNull() }`
- Async init (load by ID) requires `advanceUntilIdle()` before assertions
- Synchronous init (new macro, `null` id) requires NO `advanceUntilIdle()` — `regenerateJson()` is called directly
- `runTest(dispatcher)` (not bare `runTest`) so the test dispatcher drives coroutines

Test file: `app/src/test/java/at/resch/routines/ui/viewmodel/MacroEditorViewModelTest.kt`

Suite breakdown at gate close:
- FireAppIntentActionExecutorTest: 10
- ShellScriptActionExecutorTest: 10
- ToggleHotspotActionExecutorTest: 6
- EventBusTest: 7
- MacroEngineTest: 10
- MacroEvaluatorTest: 7
- NetworkTriggerSourceTest: 6
- RootCheckerTest: 7
- ShellExecutorTest: 8
- TriggerRegistryTest: 8
- MacroRepositoryTest: 12
- MacroScriptSerializationTest: 3
- ExampleUnitTest: 1
- MacroEditorViewModelTest: 12
- **Total: 107**
