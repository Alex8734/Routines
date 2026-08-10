# Android Automation App - Project Rules

## Multi-Agent Architecture Roles
When processing complex tasks, orchestrate the work into these 4 logical sub-agents:
1. **Lead Architect**: Manages overall structure and orchestrates local `graphify` context.
2. **System Implementer**: Specialized in Android Background Services, `BroadcastReceiver`, and Kotlin Coroutine Flows. Writes code ONLY in `core/`.
3. **UI Designer**: Specialized in Jetpack Compose and MVVM ViewModels. Writes code ONLY in `ui/`.
4. **QA Engineer (Test Automator)**: Specialized in MockK, Turbine, and Compose UI Testing. Writes code ONLY in `src/test/` and `src/androidTest/`.

## Development Guidelines
- Always use Kotlin and Jetpack Compose.
- Maintain strict layer separation (Presentation, Domain, Data).
- Before implementing a feature, the Lead must consult the `graphify` knowledge graph.
- Every feature implemented by System or UI MUST be verified by the QA Engineer with corresponding tests before completion.

## Scripting & Data Architecture
- The core engine must be configuration-driven (similar to Home Assistant).
- Every Macro is defined by a central JSON-based script format.
- The UI is just a visual editor that reads and writes this JSON string.
- The Room DB only stores the raw JSON configuration for each macro.