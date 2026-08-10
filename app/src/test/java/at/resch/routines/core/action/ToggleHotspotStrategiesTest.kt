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
 * Strategy-level tests for [ToggleHotspotActionExecutor].
 *
 * Each root strategy executes THREE phases:
 *   1. Idempotency pre-check via readHotspotState() (dumpsys); if already in desired → immediate Success,
 *      and the action command is NEVER issued.
 *   2. Action command (service call / cmd wifi), only when pre-check didn't short-circuit.
 *   3. Verify (up to 10× polling via dumpsys) until desired state observed.
 *
 * Strategy chain (first success wins):
 *   1. `tethering_reflection` (requiresRoot=false) — HotspotReflectionController
 *   2. `service_call_root`    (requiresRoot=true)  — `service call wifi <startCode> i32 0` /
 *                                                     `service call wifi <stopCode>`
 *   3. `cmd_softap_root`      (requiresRoot=true)  — `cmd wifi start-softap <ssid> open` /
 *                                                     `cmd wifi stop-softap`
 *
 * Uses STATEFUL device model (var apOn) to make pre-check, polling, and idempotency
 * all behave naturally without brittle returnsMany sequences.
 */
class ToggleHotspotStrategiesTest {

    private lateinit var shellExecutor: ShellExecutor
    private lateinit var capabilityCache: CapabilityCache
    private lateinit var rootChecker: RootChecker
    private lateinit var reflectionController: HotspotReflectionController

    private val codes = SoftApServiceCallCodes(startCode = 42, stopCode = 43)

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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

    private fun action(enabled: String? = null, ssid: String? = null): Action {
        val params = buildMap<String, String> {
            if (enabled != null) put("enabled", enabled)
            if (ssid != null) put("ssid", ssid)
        }
        return Action(type = "toggle_hotspot", params = params)
    }

    private fun reflectionSucceeds() {
        coEvery { reflectionController.setHotspotEnabled(any()) } returns
            ActionResult.Success("Hotspot enable (tethering_reflection)")
    }

    private fun reflectionFails() {
        coEvery { reflectionController.setHotspotEnabled(any()) } returns
            ActionResult.Failure("reflection failed")
    }

    /**
     * Sets up a stateful device model with the given initial AP state.
     * - dumpsys probe reflects the current [apOn] value.
     * - service_call action command: "i32 0" in cmd → apOn=true; otherwise apOn=false.
     * - Returns the mutable ref so callers can customise per-test.
     */
    private fun setupStatefulModel(initialApOn: Boolean): BooleanArray {
        val apOn = booleanArrayOf(initialApOn)
        setupDumpsysFromRef(apOn)
        setupServiceCallFlipsRef(apOn)
        return apOn
    }

    /** Registers a dumpsys mock that reads from [ref] on each call. */
    private fun setupDumpsysFromRef(ref: BooleanArray) {
        coEvery {
            shellExecutor.execute(
                match { it.contains("dumpsys connectivity") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } answers {
            ShellResult(
                0,
                if (ref[0]) "wlan0 - TetheredState - lastError = 0"
                else "wlan0 - AvailableState - lastError = 0",
                ""
            )
        }
    }

    /** Registers a service_call mock that flips [ref] based on the start/stop command. */
    private fun setupServiceCallFlipsRef(ref: BooleanArray) {
        coEvery {
            shellExecutor.execute(
                match { it.contains("service call wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } answers {
            val cmd = firstArg<String>()
            ref[0] = cmd.contains("i32 0") // start has "i32 0", stop does not
            ShellResult(0, "Result: Parcel(00000000 00000001  '.....')", "")
        }
    }

    @Before
    fun setup() {
        shellExecutor = mockk()
        capabilityCache = mockk(relaxed = true)
        rootChecker = mockk()
        reflectionController = mockk()

        // Default: no cached preference — normal declared-order traversal
        every { capabilityCache.preferredStrategy(any()) } returns null
    }

    // =========================================================================
    // 1. enable from off → service_call_root success (Case 1)
    // =========================================================================

    @Test
    fun `enable from off via service_call_root succeeds and caches strategy`() = runTest {
        val capturedCmds = mutableListOf<String>()
        reflectionFails()
        coEvery { rootChecker.isRootAvailable() } returns true

        // Stateful model: starts OFF
        val apOn = booleanArrayOf(false)
        setupDumpsysFromRef(apOn)

        // service_call action: flip apOn + capture command
        coEvery {
            shellExecutor.execute(
                match { it.contains("service call wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } answers {
            val cmd = firstArg<String>()
            capturedCmds.add(cmd)
            apOn[0] = cmd.contains("i32 0")
            ShellResult(0, "Result: Parcel(00000000 00000001  '.....')", "")
        }

        val result = makeExecutor(serviceCallCodes = { SoftApServiceCallCodes(42, 43) })
            .execute(action("true"))

        assertTrue("Expected Success", result is ActionResult.Success)
        assertTrue(
            "Enable command must be 'service call wifi 42 i32 0'",
            capturedCmds.any { it == "service call wifi 42 i32 0" }
        )
        verify(exactly = 1) { capabilityCache.remember("toggle_hotspot", "service_call_root") }
    }

    // =========================================================================
    // 2. Idempotency: enable when already ON (Case 2)
    // =========================================================================

    @Test
    fun `idempotency enable when already on short-circuits and action command never called`() = runTest {
        reflectionFails()
        coEvery { rootChecker.isRootAvailable() } returns true

        // Device starts ON — pre-check sees desired=enable already met → immediate Success
        coEvery {
            shellExecutor.execute(
                match { it.contains("dumpsys connectivity") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } returns ShellResult(0, "wlan0 - TetheredState - lastError = 0", "")

        val result = makeExecutor().execute(action("true"))

        assertTrue("Expected Success via idempotency", result is ActionResult.Success)
        val msg = (result as ActionResult.Success).output ?: ""
        assertTrue(
            "Success message should mention 'bereits im Zielzustand'",
            msg.contains("bereits im Zielzustand")
        )
        // Action command must NEVER have been issued
        coVerify(exactly = 0) {
            shellExecutor.execute(
                match { it.contains("service call wifi") },
                any(),
                any()
            )
        }
    }

    // =========================================================================
    // 3. disable: service_call_root with stopCode (Case 3)
    // =========================================================================

    @Test
    fun `disable via service_call_root uses stopCode without i32 and succeeds`() = runTest {
        val capturedCmds = mutableListOf<String>()
        reflectionFails()
        coEvery { rootChecker.isRootAvailable() } returns true

        // Stateful model: starts ON, disable flips apOn→false
        val apOn = booleanArrayOf(true)
        setupDumpsysFromRef(apOn)

        coEvery {
            shellExecutor.execute(
                match { it.contains("service call wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } answers {
            val cmd = firstArg<String>()
            capturedCmds.add(cmd)
            apOn[0] = false // stop command disables AP
            ShellResult(0, "Result: Parcel(00000000 00000001  '.....')", "")
        }

        val result = makeExecutor().execute(action("false"))

        assertTrue("Expected Success for disable", result is ActionResult.Success)
        val disableCmd = capturedCmds.find { it.contains("service call wifi 43") }
        assertTrue("Stop command must be 'service call wifi 43'", disableCmd != null)
        assertTrue(
            "Stop command must NOT contain i32",
            !disableCmd!!.contains("i32")
        )
    }

    // =========================================================================
    // 4. service_call codes null → strategy fails cleanly, chain continues (Case 4)
    // =========================================================================

    @Test
    fun `service_call null codes fails without shell call and chain falls to cmd_softap`() = runTest {
        reflectionFails()
        coEvery { rootChecker.isRootAvailable() } returns true

        // cmd_softap succeeds; stateful model starts OFF, cmd_softap sets it ON
        val apOn = booleanArrayOf(false)
        setupDumpsysFromRef(apOn)
        coEvery {
            shellExecutor.execute(
                match { it.contains("cmd wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } answers {
            apOn[0] = true
            ShellResult(0, "ok", "")
        }

        val result = makeExecutor(serviceCallCodes = { null }).execute(action("true"))

        assertTrue("Expected Success from cmd_softap fallback", result is ActionResult.Success)
        // service call command must NEVER have been issued
        coVerify(exactly = 0) {
            shellExecutor.execute(
                match { it.contains("service call wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        }
    }

    // =========================================================================
    // 5. Command issued but state never reaches desired → Failure (Case 5)
    //    Replaces the old "exit 0 + parcel-false ⇒ Failure" guard.
    // =========================================================================

    @Test
    fun `service_call issued but AP state never reaches desired is Failure and chain continues`() = runTest {
        reflectionFails()
        coEvery { rootChecker.isRootAvailable() } returns true

        // Device stays OFF throughout — command returns parcel-false, AP never flips
        coEvery {
            shellExecutor.execute(
                match { it.contains("dumpsys connectivity") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } returns ShellResult(0, "wlan0 - AvailableState - lastError = 0", "") // always OFF
        coEvery {
            shellExecutor.execute(
                match { it.contains("service call wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } returns ShellResult(0, "Result: Parcel(00000000 00000000  '.....')", "") // parcel-false, no flip
        // cmd_softap also fails so overall result is Failure
        coEvery {
            shellExecutor.execute(
                match { it.contains("cmd wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } returns ShellResult(1, "", "not found")

        val result = makeExecutor().execute(action("true"))

        assertTrue(
            "Failure expected when AP state never reaches desired",
            result is ActionResult.Failure
        )
        val reason = (result as ActionResult.Failure).reason
        assertTrue(
            "Failure reason must mention service_call_root",
            reason.contains("service_call_root")
        )
    }

    // =========================================================================
    // 6a. verify unknown → fallback to parcel-true → Success (Case 6)
    // =========================================================================

    @Test
    fun `verify unknown dumpsys unparseable with parcel-true returns Success unverifiable`() = runTest {
        reflectionFails()
        coEvery { rootChecker.isRootAvailable() } returns true
        // All dumpsys calls return garbage — state unknown
        coEvery {
            shellExecutor.execute(
                match { it.contains("dumpsys connectivity") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } returns ShellResult(0, "nothing useful here", "")
        // Command returns parcel-true (commandSucceeded=true)
        coEvery {
            shellExecutor.execute(
                match { it.contains("service call wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } returns ShellResult(0, "Result: Parcel(00000000 00000001  '.....')", "")

        val result = makeExecutor().execute(action("true"))

        // verify unknown + parcel-true → Success ("Zustand nicht verifizierbar")
        assertTrue(
            "verify unknown + parcel-true must fall back to Success",
            result is ActionResult.Success
        )
        val msg = (result as ActionResult.Success).output ?: ""
        assertTrue(
            "Message must indicate unverifiable state",
            msg.contains("nicht verifizierbar")
        )
    }

    // =========================================================================
    // 6b. verify unknown → fallback to parcel-false → Failure (Case 6)
    // =========================================================================

    @Test
    fun `verify unknown dumpsys with parcel-false returns Failure unknown state`() = runTest {
        reflectionFails()
        coEvery { rootChecker.isRootAvailable() } returns true
        // All dumpsys calls return garbage — state unknown
        coEvery {
            shellExecutor.execute(
                match { it.contains("dumpsys connectivity") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } returns ShellResult(0, "nothing useful here", "")
        // Command returns parcel-false (commandSucceeded=false)
        coEvery {
            shellExecutor.execute(
                match { it.contains("service call wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } returns ShellResult(0, "Result: Parcel(00000000 00000000  '.....')", "")
        // cmd_softap also fails (unknown state → fallback to commandSucceeded=true always,
        // but cmd_softap uses exit code; make it fail)
        coEvery {
            shellExecutor.execute(
                match { it.contains("cmd wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } returns ShellResult(1, "", "not found")

        val result = makeExecutor().execute(action("true"))

        assertTrue(
            "verify unknown + parcel-false must produce Failure",
            result is ActionResult.Failure
        )
        val reason = (result as ActionResult.Failure).reason
        assertTrue(
            "Failure must mention Kommando-Signal negativ or service_call_root",
            reason.contains("service_call_root")
        )
    }

    // =========================================================================
    // 7. Reflection success first — no rootChecker, no shell (Case 7)
    // =========================================================================

    @Test
    fun `reflection success returns Success without consulting rootChecker or shell`() = runTest {
        reflectionSucceeds()

        val result = makeExecutor().execute(action())

        assertTrue("Expected Success from reflection", result is ActionResult.Success)
        coVerify(exactly = 0) { rootChecker.isRootAvailable() }
        coVerify(exactly = 0) { shellExecutor.execute(any(), any(), any()) }
    }

    @Test
    fun `reflection success stores tethering_reflection in cache`() = runTest {
        reflectionSucceeds()

        makeExecutor().execute(action("true"))

        verify(exactly = 1) { capabilityCache.remember("toggle_hotspot", "tethering_reflection") }
    }

    @Test
    fun `reflection passes enabled=true to controller`() = runTest {
        coEvery { reflectionController.setHotspotEnabled(true) } returns
            ActionResult.Success("ok")

        makeExecutor().execute(action("true"))

        coVerify(exactly = 1) { reflectionController.setHotspotEnabled(true) }
    }

    @Test
    fun `reflection passes enabled=false to controller`() = runTest {
        coEvery { reflectionController.setHotspotEnabled(false) } returns
            ActionResult.Success("ok")

        makeExecutor().execute(action("false"))

        coVerify(exactly = 1) { reflectionController.setHotspotEnabled(false) }
    }

    // =========================================================================
    // 8. Root unavailable: all root strategies skipped (Case 8)
    // =========================================================================

    @Test
    fun `root unavailable reflection fails returns Failure no shell calls`() = runTest {
        coEvery { rootChecker.isRootAvailable() } returns false
        reflectionFails()

        val result = makeExecutor().execute(action("true"))

        assertTrue("Expected Failure when reflection fails and root unavailable", result is ActionResult.Failure)
        coVerify(exactly = 0) { shellExecutor.execute(any(), ShellExecutor.Mode.ROOT, any()) }
    }

    @Test
    fun `root unavailable reflection success returns Success without shell calls`() = runTest {
        reflectionSucceeds()

        val result = makeExecutor().execute(action())

        assertTrue("Expected Success", result is ActionResult.Success)
        coVerify(exactly = 0) { shellExecutor.execute(any(), any(), any()) }
    }

    // =========================================================================
    // 9. cmd_softap_root (Case 9)
    // =========================================================================

    @Test
    fun `cmd_softap enable uses start-softap with default ssid Routines`() = runTest {
        val capturedCmds = mutableListOf<String>()
        reflectionFails()
        coEvery { rootChecker.isRootAvailable() } returns true

        // service_call fails cleanly: null codes → no shell call, chain goes to cmd_softap
        // Stateful model starts OFF; cmd_softap sets it ON
        val apOn = booleanArrayOf(false)
        setupDumpsysFromRef(apOn)
        coEvery {
            shellExecutor.execute(
                match { it.contains("cmd wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } answers {
            val cmd = firstArg<String>()
            capturedCmds.add(cmd)
            apOn[0] = true
            ShellResult(0, "ok", "")
        }

        val result = makeExecutor(serviceCallCodes = { null }).execute(action("true"))

        assertTrue("Expected Success from cmd_softap_root", result is ActionResult.Success)
        assertTrue(
            "cmd_softap enable must use start-softap with default ssid 'Routines'",
            capturedCmds.any { it.contains("start-softap") && it.contains("Routines") }
        )
    }

    @Test
    fun `cmd_softap enable uses provided ssid param`() = runTest {
        val capturedCmds = mutableListOf<String>()
        reflectionFails()
        coEvery { rootChecker.isRootAvailable() } returns true

        val apOn = booleanArrayOf(false)
        setupDumpsysFromRef(apOn)
        coEvery {
            shellExecutor.execute(
                match { it.contains("cmd wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } answers {
            val cmd = firstArg<String>()
            capturedCmds.add(cmd)
            apOn[0] = true
            ShellResult(0, "ok", "")
        }

        makeExecutor(serviceCallCodes = { null }).execute(action("true", ssid = "MyNet"))

        assertTrue(
            "Custom ssid must appear in start-softap command",
            capturedCmds.any { it.contains("start-softap") && it.contains("MyNet") }
        )
    }

    @Test
    fun `cmd_softap disable uses stop-softap`() = runTest {
        val capturedCmds = mutableListOf<String>()
        reflectionFails()
        coEvery { rootChecker.isRootAvailable() } returns true

        // Stateful model starts ON; cmd_softap disables it
        val apOn = booleanArrayOf(true)
        setupDumpsysFromRef(apOn)
        coEvery {
            shellExecutor.execute(
                match { it.contains("cmd wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } answers {
            val cmd = firstArg<String>()
            capturedCmds.add(cmd)
            apOn[0] = false
            ShellResult(0, "ok", "")
        }

        val result = makeExecutor(serviceCallCodes = { null }).execute(action("false"))

        assertTrue("Expected Success for cmd_softap disable", result is ActionResult.Success)
        assertTrue(
            "cmd_softap disable must use stop-softap",
            capturedCmds.any { it.contains("stop-softap") }
        )
    }

    @Test
    fun `cmd_softap success stores cmd_softap_root in cache`() = runTest {
        reflectionFails()
        coEvery { rootChecker.isRootAvailable() } returns true

        val apOn = booleanArrayOf(false)
        setupDumpsysFromRef(apOn)
        coEvery {
            shellExecutor.execute(
                match { it.contains("cmd wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } answers {
            apOn[0] = true
            ShellResult(0, "ok", "")
        }

        makeExecutor(serviceCallCodes = { null }).execute(action("true"))

        verify(exactly = 1) { capabilityCache.remember("toggle_hotspot", "cmd_softap_root") }
    }

    @Test
    fun `cmd_softap idempotency disable when already off short-circuits`() = runTest {
        reflectionFails()
        coEvery { rootChecker.isRootAvailable() } returns true

        // Device starts OFF, desired=disable → pre-check sees already in desired state
        coEvery {
            shellExecutor.execute(
                match { it.contains("dumpsys connectivity") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } returns ShellResult(0, "wlan0 - AvailableState - lastError = 0", "")

        val result = makeExecutor(serviceCallCodes = { null }).execute(action("false"))

        assertTrue("Expected Success via idempotency", result is ActionResult.Success)
        val msg = (result as ActionResult.Success).output ?: ""
        assertTrue(
            "Success message should mention 'bereits im Zielzustand'",
            msg.contains("bereits im Zielzustand")
        )
        coVerify(exactly = 0) {
            shellExecutor.execute(
                match { it.contains("cmd wifi") },
                any(),
                any()
            )
        }
    }

    // =========================================================================
    // 10. Cache hit (Case 10)
    // =========================================================================

    @Test
    fun `cache hit on service_call_root skips reflection and does not re-remember`() = runTest {
        every { capabilityCache.preferredStrategy("toggle_hotspot") } returns "service_call_root"
        coEvery { rootChecker.isRootAvailable() } returns true

        // Single shared ref so dumpsys mock and service_call mock both see the same state
        val apOn = booleanArrayOf(false)
        setupDumpsysFromRef(apOn)
        coEvery {
            shellExecutor.execute(
                match { it.contains("service call wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } answers {
            val cmd = firstArg<String>()
            apOn[0] = cmd.contains("i32 0")
            ShellResult(0, "Result: Parcel(00000000 00000001  '.....')", "")
        }

        val result = makeExecutor().execute(action("true"))

        assertTrue("Expected Success", result is ActionResult.Success)
        // Cache hit: reflection must not have been called
        coVerify(exactly = 0) { reflectionController.setHotspotEnabled(any()) }
        // Cache hit: remember() must NOT be called again (strategy already cached)
        verify(exactly = 0) { capabilityCache.remember(any(), any()) }
    }

    @Test
    fun `cache hit on cmd_softap_root skips reflection and service_call`() = runTest {
        every { capabilityCache.preferredStrategy("toggle_hotspot") } returns "cmd_softap_root"
        coEvery { rootChecker.isRootAvailable() } returns true

        // Stateful model: starts OFF
        val apOn = booleanArrayOf(false)
        setupDumpsysFromRef(apOn)
        coEvery {
            shellExecutor.execute(
                match { it.contains("cmd wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } answers {
            apOn[0] = true
            ShellResult(0, "ok", "")
        }

        val result = makeExecutor().execute(action("true"))

        assertTrue("Expected Success", result is ActionResult.Success)
        coVerify(exactly = 0) { reflectionController.setHotspotEnabled(any()) }
        coVerify(exactly = 0) {
            shellExecutor.execute(
                match { it.contains("service call wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        }
        verify(exactly = 0) { capabilityCache.remember(any(), any()) }
    }

    @Test
    fun `cache hit on tethering_reflection succeeds without root check or shell`() = runTest {
        every { capabilityCache.preferredStrategy("toggle_hotspot") } returns "tethering_reflection"
        reflectionSucceeds()

        val result = makeExecutor().execute(action())

        assertTrue("Expected Success", result is ActionResult.Success)
        coVerify(exactly = 0) { rootChecker.isRootAvailable() }
        coVerify(exactly = 0) { shellExecutor.execute(any(), any(), any()) }
        verify(exactly = 0) { capabilityCache.remember(any(), any()) }
    }

    // =========================================================================
    // 11. enabled param true / false / missing (Case 11)
    // =========================================================================

    @Test
    fun `enabled true routes to start command`() = runTest {
        val capturedCmds = mutableListOf<String>()
        reflectionFails()
        coEvery { rootChecker.isRootAvailable() } returns true

        val apOn = booleanArrayOf(false)
        setupDumpsysFromRef(apOn)
        coEvery {
            shellExecutor.execute(
                match { it.contains("service call wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } answers {
            val cmd = firstArg<String>()
            capturedCmds.add(cmd)
            apOn[0] = cmd.contains("i32 0")
            ShellResult(0, "Result: Parcel(00000000 00000001  '.....')", "")
        }

        makeExecutor().execute(action("true"))

        assertTrue(
            "enabled=true must produce start command (service call wifi 42 i32 0)",
            capturedCmds.any { it.contains("i32 0") }
        )
    }

    @Test
    fun `enabled false routes to stop command`() = runTest {
        val capturedCmds = mutableListOf<String>()
        reflectionFails()
        coEvery { rootChecker.isRootAvailable() } returns true

        // Device ON so pre-check doesn't short-circuit disable
        val apOn = booleanArrayOf(true)
        setupDumpsysFromRef(apOn)
        coEvery {
            shellExecutor.execute(
                match { it.contains("service call wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } answers {
            val cmd = firstArg<String>()
            capturedCmds.add(cmd)
            apOn[0] = false
            ShellResult(0, "Result: Parcel(00000000 00000001  '.....')", "")
        }

        makeExecutor().execute(action("false"))

        assertTrue(
            "enabled=false must produce stop command (no i32 0)",
            capturedCmds.any { !it.contains("i32") }
        )
    }

    @Test
    fun `missing enabled param defaults to enable routes to start command`() = runTest {
        val capturedCmds = mutableListOf<String>()
        reflectionFails()
        coEvery { rootChecker.isRootAvailable() } returns true

        val apOn = booleanArrayOf(false)
        setupDumpsysFromRef(apOn)
        coEvery {
            shellExecutor.execute(
                match { it.contains("service call wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } answers {
            val cmd = firstArg<String>()
            capturedCmds.add(cmd)
            apOn[0] = cmd.contains("i32 0")
            ShellResult(0, "Result: Parcel(00000000 00000001  '.....')", "")
        }

        makeExecutor().execute(action(enabled = null))

        assertTrue(
            "Missing enabled must default to enable (i32 0 in command)",
            capturedCmds.any { it.contains("i32 0") }
        )
    }

    // =========================================================================
    // 12. All strategies fail → aggregated Failure (Case 12)
    // =========================================================================

    @Test
    fun `all strategies fail returns aggregated Failure listing all ids`() = runTest {
        reflectionFails()
        coEvery { rootChecker.isRootAvailable() } returns true

        // Device stays OFF throughout; service_call returns parcel-false (no flip)
        // cmd_softap exits non-zero
        coEvery {
            shellExecutor.execute(
                match { it.contains("dumpsys connectivity") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } returns ShellResult(0, "wlan0 - AvailableState - lastError = 0", "")
        coEvery {
            shellExecutor.execute(
                match { it.contains("service call wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } returns ShellResult(0, "Result: Parcel(00000000 00000000  '.....')", "") // parcel-false, AP stays OFF
        coEvery {
            shellExecutor.execute(
                match { it.contains("cmd wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } returns ShellResult(1, "", "not found")

        val result = makeExecutor().execute(action("true"))

        assertTrue("Expected Failure", result is ActionResult.Failure)
        val reason = (result as ActionResult.Failure).reason
        assertTrue("Should mention tethering_reflection", reason.contains("tethering_reflection"))
        assertTrue("Should mention service_call_root", reason.contains("service_call_root"))
        assertTrue("Should mention cmd_softap_root", reason.contains("cmd_softap_root"))
    }

    // =========================================================================
    // Additional: chain order assertion
    // =========================================================================

    @Test
    fun `strategies attempted in declared order reflection then service_call then cmd_softap`() = runTest {
        val commands = mutableListOf<String>()
        reflectionFails()
        coEvery { rootChecker.isRootAvailable() } returns true

        // All shells fail → full chain traversed
        coEvery {
            shellExecutor.execute(
                match { it.contains("dumpsys connectivity") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } returns ShellResult(0, "wlan0 - AvailableState - lastError = 0", "")
        coEvery {
            shellExecutor.execute(
                match { it.contains("service call wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } answers {
            commands.add(firstArg())
            ShellResult(0, "Result: Parcel(00000000 00000000  '.....')", "") // parcel-false, AP stays OFF
        }
        coEvery {
            shellExecutor.execute(
                match { it.contains("cmd wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } answers {
            commands.add(firstArg())
            ShellResult(1, "", "not found")
        }

        makeExecutor().execute(action("true"))

        val serviceCallIdx = commands.indexOfFirst { it.contains("service call wifi") }
        val softapIdx = commands.indexOfFirst { it.contains("cmd wifi") }

        assertTrue("service_call_root must be attempted", serviceCallIdx >= 0)
        assertTrue("cmd_softap_root must be attempted", softapIdx >= 0)
        assertTrue("service_call before cmd_softap", serviceCallIdx < softapIdx)
        assertTrue(
            "svc wifi tether must NOT appear (removed strategy)",
            commands.none { it.contains("svc wifi tether") }
        )
    }

    // =========================================================================
    // Additional: service_call enable exact command verification
    // =========================================================================

    @Test
    fun `service_call enable command is exactly service call wifi 42 i32 0`() = runTest {
        val capturedCmds = mutableListOf<String>()
        reflectionFails()
        coEvery { rootChecker.isRootAvailable() } returns true

        val apOn = booleanArrayOf(false)
        setupDumpsysFromRef(apOn)
        coEvery {
            shellExecutor.execute(
                match { it.contains("service call wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } answers {
            val cmd = firstArg<String>()
            capturedCmds.add(cmd)
            apOn[0] = true
            ShellResult(0, "Result: Parcel(00000000 00000001  '.....')", "")
        }

        val result = makeExecutor().execute(action("true"))

        assertTrue("Expected Success", result is ActionResult.Success)
        assertTrue(
            "Enable command must be exactly 'service call wifi 42 i32 0'",
            capturedCmds.any { it == "service call wifi 42 i32 0" }
        )
    }

    @Test
    fun `service_call disable command is exactly service call wifi 43`() = runTest {
        val capturedCmds = mutableListOf<String>()
        reflectionFails()
        coEvery { rootChecker.isRootAvailable() } returns true

        val apOn = booleanArrayOf(true)
        setupDumpsysFromRef(apOn)
        coEvery {
            shellExecutor.execute(
                match { it.contains("service call wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } answers {
            val cmd = firstArg<String>()
            capturedCmds.add(cmd)
            apOn[0] = false
            ShellResult(0, "Result: Parcel(00000000 00000001  '.....')", "")
        }

        val result = makeExecutor().execute(action("false"))

        assertTrue("Expected Success for disable", result is ActionResult.Success)
        assertTrue(
            "Disable command must be exactly 'service call wifi 43'",
            capturedCmds.any { it == "service call wifi 43" }
        )
    }

    // =========================================================================
    // Additional: reflection is attempted even when root is unavailable
    // =========================================================================

    @Test
    fun `reflection is attempted even when root is unavailable`() = runTest {
        coEvery { rootChecker.isRootAvailable() } returns false
        reflectionSucceeds()

        val result = makeExecutor().execute(action())

        assertTrue("tethering_reflection must run regardless of root", result is ActionResult.Success)
        coVerify(exactly = 1) { reflectionController.setHotspotEnabled(any()) }
    }

    // =========================================================================
    // Additional: verify tethered/available semantics
    // =========================================================================

    @Test
    fun `verify tethered when desired enable returns Success`() = runTest {
        reflectionFails()
        coEvery { rootChecker.isRootAvailable() } returns true

        val apOn = booleanArrayOf(false)
        setupDumpsysFromRef(apOn)
        coEvery {
            shellExecutor.execute(
                match { it.contains("service call wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } answers {
            apOn[0] = true // flip to desired state
            ShellResult(0, "Result: Parcel(00000000 00000001  '.....')", "")
        }

        val result = makeExecutor().execute(action("true"))

        assertTrue("verify=Tethered + desired enable => Success", result is ActionResult.Success)
    }

    @Test
    fun `verify available when desired enable is Failure and chain continues`() = runTest {
        reflectionFails()
        coEvery { rootChecker.isRootAvailable() } returns true

        // AP stays OFF — service_call runs but doesn't flip it
        coEvery {
            shellExecutor.execute(
                match { it.contains("dumpsys connectivity") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } returns ShellResult(0, "wlan0 - AvailableState - lastError = 0", "")
        coEvery {
            shellExecutor.execute(
                match { it.contains("service call wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } returns ShellResult(0, "Result: Parcel(00000000 00000001  '.....')", "") // parcel-true but AP stays off
        // cmd_softap also fails → overall Failure
        coEvery {
            shellExecutor.execute(
                match { it.contains("cmd wifi") },
                ShellExecutor.Mode.ROOT,
                any()
            )
        } returns ShellResult(1, "", "not found")

        val result = makeExecutor().execute(action("true"))

        assertTrue(
            "verify=Available + desired enable must be Failure",
            result is ActionResult.Failure
        )
        val reason = (result as ActionResult.Failure).reason
        assertTrue(
            "Failure reason must mention service_call_root",
            reason.contains("service_call_root")
        )
    }
}
