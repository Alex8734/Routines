---
name: verify-build
description: Always verify the build compiles and existing tests still pass before reporting work complete
metadata:
  type: feedback
---

Before reporting Phase work as done, run `./gradlew :app:compileDebugKotlin` and `:app:testDebugUnitTest` (and `:app:assembleDebug` if feasible).

**Why:** The task spec explicitly requires "Verify before reporting" and "do not break the existing passing unit test". The build uses experimental AGP 9.2.1 / Kotlin 2.2.10 with `android.disallowKotlinSourceSets=false`.

**How to apply:** After any code change in core/ or data/, compile and run the existing unit test suite. Report the exact gradle command and its result in the summary.
