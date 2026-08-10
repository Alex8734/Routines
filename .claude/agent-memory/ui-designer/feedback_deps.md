---
name: feedback-deps
description: Lifecycle dependency setup for Compose UI — which artifacts are needed and why
metadata:
  type: feedback
---

Three lifecycle artifacts are required for full Compose ViewModel + Flow support:

1. `lifecycle-runtime-ktx` — base, already present
2. `lifecycle-viewmodel-compose` — provides `viewModel()` Compose factory function
3. `lifecycle-runtime-compose` — provides `collectAsStateWithLifecycle()` extension

All use `version.ref = "lifecycleRuntimeKtx"`. Version was bumped from `2.6.1` to `2.9.1` to align with Compose BOM 2026.02.01.

**Why:** `collectAsStateWithLifecycle` is in `lifecycle-runtime-compose`, not in the BOM-managed compose artifacts. Without it, Flow collection is not lifecycle-aware (memory leak risk).

**How to apply:** Always add all three when building a new screen that uses ViewModels and Flow. Check that `lifecycleRuntimeKtx` version is recent enough to match the Compose BOM in use.
