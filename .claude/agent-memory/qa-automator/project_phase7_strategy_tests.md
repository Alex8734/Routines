---
name: phase7-strategy-tests
description: StrategyActionExecutor/ToggleHotspot tests — 3-strategy chain, stateful device model, idempotency pre-check; 35 tests GREEN (Phase 7.3)
metadata:
  type: project
---

## Phase 7.3 (2026-06-24) — Stateful device model rewrite

Production logic refined after on-device testing: each root strategy now does THREE phases:
1. Idempotency pre-check (`alreadyInState`): reads `readHotspotState()` once; if already in desired state → immediate `Success("... bereits im Zielzustand")`, action command NEVER issued.
2. Action command (only if pre-check didn't short-circuit).
3. Verify (polling up to 10× with 600ms delay, auto-advanced by `runTest`) via `readHotspotState()`.

### Key semantic change from Phase 7.2
OLD: "exit 0 + parcel-false ⇒ Failure" was the core guard.
NEW: parcel-false is only a **fallback signal** (`commandSucceeded`). Truth source = real verified AP state.
- AP stays in wrong state after command → `Failure("... AP-Zustand=inaktiv")`.
- `verifyOrFallback` branches: `actual==desired→Success(verifiziert)`, `actual!=null&&!=desired→Failure(AP-Zustand=...)`, `actual==null&&parcelTrue→Success(nicht verifizierbar)`, `actual==null&&parcelFalse→Failure(Kommando-Signal negativ, Zustand unbekannt)`.

### Idempotency short-circuit
Pre-check fires before the action command. If already in desired state → Success, command never called.
Test: `coVerify(exactly=0){ shellExecutor.execute(match { it.contains("service call wifi") }, any(), any()) }`.

### `readHotspotState()` command
`STATE_PROBE_COMMAND = "dumpsys connectivity 2>/dev/null | grep -m1 -E \"wlan[0-9]+ - (Tethered|Available)State\""`.
Match with: `match { it.contains("dumpsys connectivity") }`.
Returns: `wlan\d+ - TetheredState` → true; `wlan\d+ - AvailableState` → false; otherwise → null.

### CRITICAL: Stateful device model (replaces brittle `returnsMany`)
Because dumpsys is called multiple times per strategy (pre-check + up to 10 polling calls),
`returnsMany` sequences are too brittle. Use a shared mutable ref:

```kotlin
val apOn = booleanArrayOf(false) // or true

// dumpsys reflects current apOn
coEvery {
    shellExecutor.execute(match { it.contains("dumpsys connectivity") }, ShellExecutor.Mode.ROOT, any())
} answers {
    ShellResult(0, if (apOn[0]) "wlan0 - TetheredState - lastError = 0" else "wlan0 - AvailableState - lastError = 0", "")
}

// action command flips apOn
coEvery {
    shellExecutor.execute(match { it.contains("service call wifi") }, ShellExecutor.Mode.ROOT, any())
} answers {
    val cmd = firstArg<String>()
    apOn[0] = cmd.contains("i32 0") // start has "i32 0", stop does not
    ShellResult(0, "Result: Parcel(00000000 00000001  '.....')", "")
}
```

CRITICAL: all mocks using the same ref must share the SAME `BooleanArray` instance.
Do NOT call `setupStatefulModel()` then separately create a local `apOn` — they are disconnected refs.
Either use `setupDumpsysFromRef(apOn)` + inline service_call mock, or use `setupStatefulModel()` return value.

### "command does nothing" pattern (case 5)
For "command issued but AP never reaches desired → Failure":
- action-command mock returns parcel-false AND does NOT flip apOn.
- dumpsys mock always returns the initial (wrong) state.
- verifyHotspotActive polls 10×, never sees desired → Failure.

### Test files (Phase 7.3)

- `ToggleHotspotActionExecutorTest.kt` — 4 tests: param (enabled true/false/missing) + all-fail aggregated Failure
- `ToggleHotspotStrategiesTest.kt` — 31 tests covering all 12 required cases + additional assertions:
  - Case 1: enable from OFF via service_call_root → Success + cache remember
  - Case 2: idempotency enable when already ON → short-circuit, action cmd coVerify(exactly=0)
  - Case 3: disable via service_call_root, stopCode 43 no i32
  - Case 4: null codes → no "service call wifi" shell call, chain to cmd_softap
  - Case 5: command issued but AP never reaches desired → Failure("AP-Zustand=inaktiv")
  - Case 6a: dumpsys garbage + parcel-true → Success("nicht verifizierbar")
  - Case 6b: dumpsys garbage + parcel-false → Failure (mentions service_call_root)
  - Case 7: reflection success → no rootChecker, no shell; cache remember(reflection)
  - Case 8: root unavailable → only reflection tried; no ROOT shell calls
  - Case 9: cmd_softap enable/ssid-default/ssid-custom/disable + idempotency + cache
  - Case 10: cache hit (service_call/cmd_softap/reflection) → no re-remember
  - Case 11: enabled param true/false/missing reflected in command
  - Case 12: all fail → aggregated Failure listing all 3 strategy IDs
  - Additional: chain order, exact command strings, reflection passes enabled param

Total: **35 tests GREEN**

### Constructor (named args)
```kotlin
ToggleHotspotActionExecutor(
    rootChecker = rootChecker,
    capabilityCache = capabilityCache,   // mockk(relaxed=true)
    shellExecutor = shellExecutor,
    reflectionController = reflectionController,
    serviceCallCodes = { SoftApServiceCallCodes(42, 43) } // or { null }
)
```

### Key pitfalls

1. **`returnsMany` is BROKEN** with the new multi-call design. Use stateful BooleanArray model.
2. **Shared ref**: all mocks touching apOn state must share the SAME `booleanArrayOf(...)` instance.
3. **Pre-check fires first**: if apOn already matches desired, SUCCESS immediately, no action command.
4. **Cache hit does NOT re-call `remember()`** — assert `verify(exactly=0) { remember(...) }`.
5. **Enable = `i32 0`** (null WifiConfig = system SSID), NOT `i32 1`.
6. **Disable = `service call wifi 43`** with NO `i32` at all.
7. **parcel-false is now only fallback signal**, not a standalone Failure guard.

## Why:
On-device testing revealed: `startSoftAp(null)` returns parcel-false if AP already running;
dumping this as Failure was wrong. Also, calling startSoftAp when already active restarts the AP
(causing a transient state that makes polling fail). Idempotency pre-check prevents this.

## How to apply:
Always use the stateful BooleanArray model. For "command does nothing" tests: mock the action
command to NOT flip apOn (and optionally return parcel-false). dumpsys will always return
the initial (wrong) state, so polling exhausts and returns Failure.
