---
name: project-phase3-tests
description: Phase 3 Root/System Actions unit tests — seam patterns, build bug fixed, new test count
metadata:
  type: project
---

Phase 3 unit tests completed. Five test classes written in `app/src/test/`, covering `ShellExecutor`, `RootChecker`, `ShellScriptActionExecutor`, `ToggleHotspotActionExecutor`, and `FireAppIntentActionExecutor`.

**Why:** System Implementer finished Phase 3; QA gate requires green tests before architect approval.

**How to apply:** Phase 4 action executor tests should follow the same mock-seam pattern (mock the seam interface, not the Android class directly).

## Test counts
- Phase 3 new tests: 41 (8 + 7 + 10 + 6 + 10)
- Phase 2 existing: 54
- Grand total: 95 — all green

## Seam pattern for Phase 3
- `ProcessRunner` (interface) is the seam for `ShellExecutor`. Mock `Process` with `mockk(relaxed=true)`, stub `inputStream`/`errorStream` as `ByteArrayInputStream`, `waitFor()`, `waitFor(timeout, unit)`, `exitValue()`, `destroyForcibly()`.
- `RootProbe` (interface) is the seam for `RootChecker`. Cache test: verify probe called exactly once across multiple `isRootAvailable()` calls; `invalidate()` re-probes.
- `ShellExecutor` itself is mocked (not `ProcessRunner`) for action executor tests — that layer only cares about `ShellResult` values.
- `IntentLauncher` (interface) is the seam for `FireAppIntentActionExecutor`. Use `mockk<Intent>(relaxed=true)` as the return value — no real Android runtime needed. `testOptions.unitTests.isReturnDefaultValues = true` handles any un-mocked Intent methods.

## Pre-existing production bug discovered
`material-icons-core` dependency was missing from `build.gradle.kts` and `libs.versions.toml`, breaking compilation of `MacroDashboardScreen` and `MacroEditorScreen` (`Unresolved reference 'icons'`). Fixed by adding the dependency to both files (build infrastructure, not production logic). This was not a Phase 3 regression — the UI screens already used `Icons.Filled.*`.

## MockK challenges
- `android.content.Intent` is final; using `mockk<Intent>(relaxed=true)` works fine under JVM unit tests because it never reaches real Android runtime. No `mockkConstructor` needed since `IntentLauncher` seam returns the mocked intent directly.
- `Process` is mockable without relaxed=true but requires explicit stubs for all three stream/wait methods to avoid NPE in `readStream`.
