---
name: project-core-engine
description: Phase 2 Core-Engine design decisions for Routines — EventBus, SystemEvent placement, registry, DI seams
metadata:
  type: project
---

Phase 2 Core-Engine implemented in `app/src/main/java/at/resch/routines/core/` + `data/`.

**Why:** App is a Tasker-equivalent, JSON/config-driven (Home Assistant style). Room stores only raw JSON (macro_id + configuration); engine parses to MacroScript.

**How to apply:** Reuse these established decisions when extending in Phase 3/4 rather than re-deriving.

Key decisions:
- **SystemEvent lives in `core/`** (not domain) — it is a runtime-only construct, never persisted/edited. Its persisted counterpart is `Trigger` in domain. Event→Trigger matching lives on the event via `SystemEvent.matches(trigger)` (Open/Closed: new event type = new class, no Evaluator edit).
- **EventBus**: `MutableSharedFlow(replay=0, extraBufferCapacity=64)`. replay=0 because events are transient triggers; a late collector must NOT re-fire old events. Startup event is emitted by the service AFTER the evaluator is collecting (BootReceiver → service start → onStartCommand emits Startup), so no replay needed.
- **OnStartup is deliberately NOT a TriggerSource** — it is a one-shot event emitted directly to the bus by BootReceiver/Service. TriggerRegistry only manages continuously-observed sources (NetworkTriggerSource etc.).
- **EngineContainer** (object) is the manual DI composition root holding the single process-wide EventBus. No Hilt in project. All building blocks are constructor-injected for testability.
- **Action dispatch** via registry Map<type, ActionExecutor> in MacroEngine. Default `LogActionExecutor` (type="log") makes engine runnable end-to-end. Per-action error isolation: unknown type or thrown exception → ActionResult.Failure, loop continues.
- **Central Json**: `MacroJson` in data/ — `Json { ignoreUnknownKeys = true }`, default "type" discriminator (Trigger schema relies on it). Reused by engine + repository, no ad-hoc Json instances.
- **Foreground service**: type `specialUse` (generic automation engine fits no predefined FGS type). Permissions: FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS.

Seams left for Phase 3/4: implement `ActionExecutor` per new action (shell/root/hotspot/intent), register in `EngineContainer.defaultExecutors()`; implement `TriggerSource` per new trigger, add to `EngineContainer.defaultTriggerSources()`; extend `TriggerRegistry.triggerIdOf` when a new Trigger subtype is added.
