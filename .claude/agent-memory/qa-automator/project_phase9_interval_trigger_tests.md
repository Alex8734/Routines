---
name: project-phase9-interval-trigger-tests
description: Tests for the "interval" trigger (per-macro periodic ticker for REST polling) — 48 new tests, gate closed at 327 total; virtual-time-vs-DelayProvider-fake decision tree for concurrent tickers
metadata:
  type: project
---

Phase 9 added tests for `Trigger.Interval` / `IntervalTriggerSource` / `SystemEvent.IntervalTick` /
`HttpRequestActionExecutor` headers+timeoutSeconds / `MacroEditorViewModel` interval mutators.
48 new tests across 6 files, gate closed at **327 total unit tests**, all green
(`./gradlew :app:testDebugUnitTest`).

**Why:** interval trigger enables cyclic REST polling macros (Home-Assistant-style config-driven
engine, see [[../system-implementer/project_core_engine.md]]). Contract: the ticker map key and
`SystemEvent.IntervalTick.intervalSeconds` are always the macro's **original** `intervalSeconds`;
only the actual wait duration is clamped to `max(intervalSeconds, MIN_INTERVAL_SECONDS=5) * 1000L`.
A macro with `intervalSeconds=1` matches its own tick but is throttled to a 5s real cadence.

**How to apply / key pitfall discovered:**

`IntervalTriggerSource` runs **one ticker Job per distinct interval value**, reconciled reactively
from a `Flow<Set<Trigger.Interval>>`. This breaks the usual `UnconfinedTestDispatcher` +
fake-`DelayProvider`-that-calls-`scope.cancel()` pattern (used throughout
`TimeScheduleTriggerSourceTest`/`BatteryTriggerSourceTest`, which only ever have ONE ticker) as soon
as **more than one ticker is alive at once** (concurrent intervals, or reconcile add/remove):

- With `UnconfinedTestDispatcher`, `scope.launch { ... }` runs the new coroutine synchronously to
  completion (or first *real* suspension) before returning to the caller.
- A fake `DelayProvider` lambda that never calls the real `delay()` never truly suspends — so the
  first ticker launched loops "forever" (until something cancels its own Job specifically) in one
  synchronous call frame, **before the second ticker ever gets to run**, and before a
  `MutableStateFlow` update made from inside the fake can be observed by the collector (StateFlow
  does not re-invoke a collector reentrantly — the update is only picked up once the collector's
  current invocation returns to the `collect` loop).
- Net effect: tests that try to swap `intervals` or run two intervals concurrently using the
  Unconfined+fake-cancel pattern either silently only exercise one ticker or hang.

**Fix used:** for anything involving >1 concurrent ticker or a changing `intervals` flow, use the
**real default `DelayProvider`** (`kotlinx.coroutines.delay`) together with plain `runTest { }`
(`StandardTestDispatcher`/`TestCoroutineScheduler`) and drive time explicitly with
`advanceTimeBy(...)` + `runCurrent()`, passing `this` (the `runTest` `TestScope`) directly as the
`scope` argument to `source.start(...)`. Real `delay()` genuinely suspends, so the scheduler can
properly interleave multiple ticker coroutines and let the collector react to new `intervals`
values in between ticks. Single-ticker assertions (triggerId, repeated-tick value, runOnStart
ordering, clamping-captures-the-millis-argument, stop() safety, idempotent start) keep using the
classic `UnconfinedTestDispatcher` + `DelayProvider`-fake-that-cancels-the-scope pattern — it's
simpler and matches the neighbor files' style there.

**Cancellation-ordering pitfall (same as [[project_phase6_wait_reorder_tests.md]]/
[[project_phase4_tests.md]]):** in the Unconfined+fake pattern, `scope.cancel()` called from
inside the `wait()` fake does NOT stop the `emit()` call that follows it in the same loop
iteration — cancellation is only checked at the top of the *next* `while(isActive)` iteration. For
`runOnStart=true`, this means the captured event sequence is `[tick, wait, tick]`, not `[tick,
wait]` — got this wrong on the first pass and had to fix the assertion after seeing the actual
failure.

**Kotlin test-name gotcha:** backtick-quoted `@Test fun` names must not contain `:` or `.` — both
are illegal in JVM method names and fail with cryptic "Name contains illegal characters" /
"Unresolved reference" compiler errors that point at the wrong column. Wrote `Trigger.Interval` in
test names as `Trigger Interval` (plain space) instead.

**MockK/interface-default gotcha:** `HttpClient.request(url, method, body, headers =
emptyMap(), timeoutMillis = DEFAULT)` — Kotlin interface default parameter values do **not**
apply to `mockk()`-generated proxies, and the production executor always calls with all 5
positional args. Every `coEvery`/`coVerify { client.request(...) }` matcher in
`HttpRequestActionExecutorTest` needed exactly 5 argument matchers (or it wouldn't match / wouldn't
compile) — bulk-fixed via `replace_all` on the 4 distinct call shapes (plain `any()×3`, url-slot,
method-slot, body-slot) rather than editing one by one.

**Files touched:** new `core/trigger/IntervalTriggerSourceTest.kt` (11 tests); extended
`core/SystemEventMatchesTest.kt` (+10), `core/TriggerRegistryTest.kt` (+3),
`domain/model/MacroScriptSerializationTest.kt` (+3), `core/action/HttpRequestActionExecutorTest.kt`
(+13 headers/timeout tests, plus signature fixes to ~15 existing calls), `ui/viewmodel/MacroEditorViewModelTest.kt` (+7).

No production-code bugs found this phase — `Trigger.Interval`, `IntervalTriggerSource`,
`SystemEvent.IntervalTick`, `HttpRequestActionExecutor` headers/timeout parsing, and the
`MacroEditorViewModel` interval mutators all behaved exactly as documented in their KDoc.
