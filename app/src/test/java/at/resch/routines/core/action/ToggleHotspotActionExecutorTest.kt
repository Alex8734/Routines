package at.resch.routines.core.action

import at.resch.routines.core.cache.CapabilityCache
import at.resch.routines.core.shell.RootChecker
import at.resch.routines.core.shell.ShellExecutor
import at.resch.routines.core.shell.ShellResult
import at.resch.routines.domain.model.Action
import at.resch.routines.domain.model.ActionResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ToggleHotspotActionExecutor] — param handling, root-guard,
 * and aggregated-failure scenarios.
 *
 * Each root strategy now does THREE phases via the SAME shellExecutor:
 *   1. Idempotency pre-check: reads dumpsys; if already in desired state → Success immediately.
 *   2. Action command: issued only if pre-check didn't short-circuit.
 *   3. Verify (polling): reads dumpsys up to 10× to confirm state reached.
 *
 * Strategy chain (first success wins):
 *   1. `tethering_reflection` (requiresRoot=false) — HotspotReflectionController
 *   2. `service_call_root`    (requiresRoot=true)  — `service call wifi <startCode> i32 0` / stopCode
 *   3. `cmd_softap_root`      (requiresRoot=true)  — `cmd wifi start-softap|stop-softap`
 *
 * Uses stateful device model (var apOn) so pre-check and polling both see realistic state.
 */
class ToggleHotspotActionExecutorTest {

    private lateinit var shellExecutor: ShellExecutor
    private lateinit var rootChecker: RootChecker
    private lateinit var capabilityCache: CapabilityCache
    private lateinit var reflectionController: HotspotReflectionController

    private val codes = SoftApServiceCallCodes(startCode = 42, stopCode = 43)

    // Helper — builds executor with a given serviceCallCodes provider
    private fun makeExecutor(
        serviceCallCodes: () -> SoftApServiceCallCodes? = { codes }
    ): ToggleHotspotActionExecutor =
        ToggleHotspotActionExecutor(
            rootChecker = rootChecker,
            capabilityCache = capabilityCache,
            shellExecutor = shellExecutor,
            reflectionController = reflectionController,
            serviceCallCodes = serviceCallCodes
        )

    // Helper — builds an action with optional params
    private fun action(enabled: String? = null, ssid: String? = null): Action {
        val params = buildMap<String, String> {
            if (enabled != null) put("enabled", enabled)
            if (ssid != null) put("ssid", ssid)
        }
        return Action(type = "toggle_hotspot", params = params)
    }

    @Before
    fun setup() {
        shellExecutor = mockk()
        rootChecker = mockk()
        capabilityCache = mockk(relaxed = true)
        reflectionController = mockk()

        // Default: no cached preference — declared-order fallback
        every { capabilityCache.preferredStrategy(any()) } returns null
    }

    // =========================================================================
    // Helpers for stateful device model
    // =========================================================================

    /**
     * Sets up a stateful device model where apOn starts at [initialState].
     * dumpsys reflects the current apOn; service_call flips apOn (start/stop).
     * Returns a ref so tests can mutate apOn for "command does nothing" cases.
     */
    private fun setupStatefulServiceCallModel(initialState: Boolean): BooleanRef {
        val ref = BooleanRef(initialState)
        coEvery {
            shellExecutor.execute(
                match { it.contains("dumpsys connectivity") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } answers {
            ShellResult(
                0,
                if (ref.value) "wlan0 - TetheredState - lastError = 0"
                else "wlan0 - AvailableState - lastError = 0",
                ""
            )
        }
        coEvery {
            shellExecutor.execute(
                match { it.contains("service call wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } answers {
            val cmd = firstArg<String>()
            // start command has "i32 0", stop has just stopCode with no i32
            ref.value = cmd.contains("i32 0")
            ShellResult(0, "Result: Parcel(00000000 00000001  '.....')", "")
        }
        return ref
    }

    /** Mutable boolean reference for stateful device model. */
    class BooleanRef(var value: Boolean)

    // =========================================================================
    // Param handling: enabled true / false / missing
    // =========================================================================

    @Test
    fun `enabled true produces service call with startCode`() = runTest {
        val commands = mutableListOf<String>()
        coEvery { reflectionController.setHotspotEnabled(any()) } returns
            ActionResult.Failure("reflection noop")
        coEvery { rootChecker.isRootAvailable() } returns true

        // Device starts OFF; service_call sets it ON
        setupStatefulServiceCallModel(initialState = false)
        // Override to also capture commands
        coEvery {
            shellExecutor.execute(
                match { it.contains("service call wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } answers {
            val cmd = firstArg<String>()
            commands.add(cmd)
            ShellResult(0, "Result: Parcel(00000000 00000001  '.....')", "")
        }
        // After command, device is ON
        coEvery {
            shellExecutor.execute(
                match { it.contains("dumpsys connectivity") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } returnsMany listOf(
            // pre-check: OFF (not yet in desired state)
            ShellResult(0, "wlan0 - AvailableState - lastError = 0", ""),
            // verify poll 1: ON (desired reached)
            ShellResult(0, "wlan0 - TetheredState - lastError = 0", "")
        )

        makeExecutor().execute(action("true"))

        assertTrue(
            "Enable command must use startCode 42 with i32 0",
            commands.any { it.contains("42") && it.contains("i32 0") }
        )
    }

    @Test
    fun `enabled false produces service call with stopCode`() = runTest {
        val commands = mutableListOf<String>()
        coEvery { reflectionController.setHotspotEnabled(any()) } returns
            ActionResult.Failure("reflection noop")
        coEvery { rootChecker.isRootAvailable() } returns true

        coEvery {
            shellExecutor.execute(
                match { it.contains("service call wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } answers {
            val cmd = firstArg<String>()
            commands.add(cmd)
            ShellResult(0, "Result: Parcel(00000000 00000001  '.....')", "")
        }
        // Device starts ON; desired=disable
        coEvery {
            shellExecutor.execute(
                match { it.contains("dumpsys connectivity") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } returnsMany listOf(
            // pre-check: ON (not yet in desired=off state)
            ShellResult(0, "wlan0 - TetheredState - lastError = 0", ""),
            // verify poll 1: OFF (desired reached)
            ShellResult(0, "wlan0 - AvailableState - lastError = 0", "")
        )

        makeExecutor().execute(action("false"))

        assertTrue(
            "Disable command must use stopCode 43",
            commands.any { it.contains("43") }
        )
    }

    @Test
    fun `missing enabled param defaults to enable`() = runTest {
        val commands = mutableListOf<String>()
        coEvery { reflectionController.setHotspotEnabled(any()) } returns
            ActionResult.Failure("reflection noop")
        coEvery { rootChecker.isRootAvailable() } returns true

        coEvery {
            shellExecutor.execute(
                match { it.contains("service call wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } answers {
            val cmd = firstArg<String>()
            commands.add(cmd)
            ShellResult(0, "Result: Parcel(00000000 00000001  '.....')", "")
        }
        coEvery {
            shellExecutor.execute(
                match { it.contains("dumpsys connectivity") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } returnsMany listOf(
            // pre-check: OFF (not yet in desired=on state)
            ShellResult(0, "wlan0 - AvailableState - lastError = 0", ""),
            // verify poll 1: ON (desired reached)
            ShellResult(0, "wlan0 - TetheredState - lastError = 0", "")
        )

        makeExecutor().execute(action(enabled = null))

        assertTrue(
            "Default must use startCode 42 (enable) with i32 0",
            commands.any { it.contains("42") && it.contains("i32 0") }
        )
    }

    // =========================================================================
    // All strategies fail → aggregated Failure
    // =========================================================================

    @Test
    fun `all strategies fail returns aggregated Failure`() = runTest {
        coEvery { reflectionController.setHotspotEnabled(any()) } returns
            ActionResult.Failure("reflection failed")
        coEvery { rootChecker.isRootAvailable() } returns true

        // service_call: pre-check says OFF (not already in desired), command runs but
        // does NOT flip apOn, parcel-false returned → verify polls see OFF ≠ desired ON
        // → Failure("...AP-Zustand=inaktiv")
        coEvery {
            shellExecutor.execute(
                match { it.contains("service call wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } returns ShellResult(0, "Result: Parcel(00000000 00000000  '.....')", "") // parcel-false
        // cmd_softap: pre-check says OFF → action runs → non-zero exit → Failure
        coEvery {
            shellExecutor.execute(
                match { it.contains("cmd wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } returns ShellResult(1, "", "permission denied")
        // dumpsys always returns Available (AP never comes up) — for pre-checks and polls
        coEvery {
            shellExecutor.execute(
                match { it.contains("dumpsys connectivity") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } returns ShellResult(0, "wlan0 - AvailableState - lastError = 0", "")

        val result = makeExecutor().execute(action("true"))

        assertTrue("Expected Failure", result is ActionResult.Failure)
        val reason = (result as ActionResult.Failure).reason
        assertTrue("Should mention tethering_reflection", reason.contains("tethering_reflection"))
        assertTrue("Should mention service_call_root", reason.contains("service_call_root"))
        assertTrue("Should mention cmd_softap_root", reason.contains("cmd_softap_root"))
    }
}
