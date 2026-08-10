---
name: project-phase2-tests
description: Phase 2 Core-Engine unit tests delivered by QA Automator — what was written, patterns used, and discovered seams
metadata:
  type: project
---

Phase 2 unit tests completed. Six test classes written in `app/src/test/`, covering `MacroEngine`, `MacroEvaluator`, `EventBus`, `TriggerRegistry`, `MacroRepository`, and `NetworkTriggerSource`.

**Why:** System Implementer finished Phase 2 Core-Engine; QA gate requires green tests before architect approval.

**How to apply:** When adding Phase 3/4 features, extend the matching test class rather than creating separate files unless a new class is introduced.

## Key decisions

- `android.util.Log` is mocked via `testOptions { unitTests.isReturnDefaultValues = true }` in `app/build.gradle.kts` — lighter than Robolectric, keeps tests fast JVM-only.
- `NetworkStatusTracker` (Android class) is MockK-mocked at the `isCellularConnected: Flow<Boolean>` boundary — `NetworkTriggerSource` takes the tracker as a constructor arg, making this clean.
- `TriggerRegistry.start()` uses `UnconfinedTestDispatcher` in tests to make `distinctUntilChanged().collect {}` process synchronously.
- `MacroEvaluator.onEvent()` is tested directly (the production `start()` just wraps it in a `collect`); no need to drive the EventBus in evaluator tests.

## Discovered seams / production observations
- `TriggerRegistry` hardcodes `BootReceiver.TRIGGER_ID` and `NetworkStatusTracker.TRIGGER_ID` in `triggerIdOf()`. If a new Trigger type is added to the sealed class without updating `triggerIdOf()`, it becomes a compile error (exhaustive `when`) — good.
- `MacroEngine.parse()` catches `SerializationException` and `IllegalArgumentException` but NOT `Exception`. Any other parse-path exception would propagate. This is intentional per the code comments but worth noting for Phase 3 executors.
- `LogActionExecutor` calls `android.util.Log` directly; covered by `isReturnDefaultValues`.
