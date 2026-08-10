---
name: phase8-simple-seam-tests
description: 28 new tests for ToggleDndActionExecutor, SetVolumeProfileActionExecutor, SetBrightnessActionExecutor — simple seam-based executors (NOT StrategyActionExecutor)
metadata:
  type: project
---

Phase 8 batch: 28 new unit tests across 3 simple seam executors (gate now ~274 total).

**Why:** These 3 executors (DND, Volume, Brightness) each wrap a single-method seam interface and do NOT use the StrategyActionExecutor/root-fallback contract — they follow the simpler ShowNotification-style pattern.

**Files written:**
- `ToggleDndActionExecutorTest.kt` — 8 tests
- `SetVolumeProfileActionExecutorTest.kt` — 9 tests
- `SetBrightnessActionExecutorTest.kt` — 11 tests

**Key patterns confirmed:**
- `mockk()` (non-relaxed) + `every { ... } just runs` for void seam methods.
- `every { ... } throws SecurityException(...)` for failure paths.
- `verify(exactly = 0) { controller.method(any()) }` to assert seam NOT called on early-return Failure paths (invalid params).
- `toBooleanStrictOrNull() ?: true` — "yes"/"1" parse as null, fall through to default true.
- VolumeProfile: present-but-invalid → Failure WITHOUT controller call; absent → default NORMAL.
- SetBrightness: `level` is mandatory; missing or non-Int → Failure WITHOUT controller call; over-range clamped via `coerceIn(0,100)` before `(pct*255)/100`.

**How to apply:** For future simple-seam executors follow this ShowNotification-style template, not StrategyActionExecutor template. See [[phase7-strategy-tests]] for contrast.
