package at.resch.routines.core.action

import at.resch.routines.core.shell.ShellExecutor
import at.resch.routines.core.shell.ShellResult
import at.resch.routines.domain.model.Action
import at.resch.routines.domain.model.ActionResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ShellScriptActionExecutor].
 * ShellExecutor is fully mocked — no real process execution.
 */
class ShellScriptActionExecutorTest {

    private lateinit var shellExecutor: ShellExecutor
    private lateinit var executor: ShellScriptActionExecutor

    @Before
    fun setup() {
        shellExecutor = mockk()
        executor = ShellScriptActionExecutor(shellExecutor)
    }

    // -------------------------------------------------------------------------
    // Missing / blank command → Failure (no executor call)
    // -------------------------------------------------------------------------

    @Test
    fun `missing command param returns Failure`() = runTest {
        val action = Action(type = "execute_shell_script", params = emptyMap())

        val result = executor.execute(action)

        assertTrue(result is ActionResult.Failure)
        coVerify(exactly = 0) { shellExecutor.execute(any(), any(), any()) }
    }

    @Test
    fun `blank command param returns Failure`() = runTest {
        val action = Action(type = "execute_shell_script", params = mapOf("command" to "   "))

        val result = executor.execute(action)

        assertTrue(result is ActionResult.Failure)
    }

    // -------------------------------------------------------------------------
    // Exit 0 → Success carrying stdout
    // -------------------------------------------------------------------------

    @Test
    fun `successful command returns Success with stdout`() = runTest {
        coEvery { shellExecutor.execute(any(), any(), any()) } returns
            ShellResult(exitCode = 0, stdout = "hello", stderr = "")

        val action = Action(type = "execute_shell_script", params = mapOf("command" to "echo hello"))
        val result = executor.execute(action)

        assertTrue(result is ActionResult.Success)
        assertEquals("hello", (result as ActionResult.Success).output)
    }

    // -------------------------------------------------------------------------
    // runAsRoot="true" → ROOT mode forwarded
    // -------------------------------------------------------------------------

    @Test
    fun `runAsRoot true passes ROOT mode to ShellExecutor`() = runTest {
        val modeSlot = slot<ShellExecutor.Mode>()
        coEvery { shellExecutor.execute(any(), capture(modeSlot), any()) } returns
            ShellResult(0, "ok", "")

        val action = Action(
            type = "execute_shell_script",
            params = mapOf("command" to "id", "runAsRoot" to "true")
        )
        executor.execute(action)

        assertEquals(ShellExecutor.Mode.ROOT, modeSlot.captured)
    }

    @Test
    fun `runAsRoot false passes USER mode to ShellExecutor`() = runTest {
        val modeSlot = slot<ShellExecutor.Mode>()
        coEvery { shellExecutor.execute(any(), capture(modeSlot), any()) } returns
            ShellResult(0, "ok", "")

        val action = Action(
            type = "execute_shell_script",
            params = mapOf("command" to "echo", "runAsRoot" to "false")
        )
        executor.execute(action)

        assertEquals(ShellExecutor.Mode.USER, modeSlot.captured)
    }

    @Test
    fun `missing runAsRoot defaults to USER mode`() = runTest {
        val modeSlot = slot<ShellExecutor.Mode>()
        coEvery { shellExecutor.execute(any(), capture(modeSlot), any()) } returns
            ShellResult(0, "ok", "")

        val action = Action(
            type = "execute_shell_script",
            params = mapOf("command" to "echo")
        )
        executor.execute(action)

        assertEquals(ShellExecutor.Mode.USER, modeSlot.captured)
    }

    // -------------------------------------------------------------------------
    // Non-zero exit → Failure
    // -------------------------------------------------------------------------

    @Test
    fun `non-zero exit returns Failure with stderr in reason`() = runTest {
        coEvery { shellExecutor.execute(any(), any(), any()) } returns
            ShellResult(exitCode = 127, stdout = "", stderr = "command not found")

        val action = Action(
            type = "execute_shell_script",
            params = mapOf("command" to "nonexistent")
        )
        val result = executor.execute(action)

        assertTrue(result is ActionResult.Failure)
        val failure = result as ActionResult.Failure
        assertTrue(failure.reason.contains("127"))
    }

    @Test
    fun `TIMEOUT_EXIT_CODE returns Failure`() = runTest {
        coEvery { shellExecutor.execute(any(), any(), any()) } returns
            ShellResult(ShellResult.TIMEOUT_EXIT_CODE, "", "Timeout nach 500ms")

        val action = Action(
            type = "execute_shell_script",
            params = mapOf("command" to "slow")
        )
        val result = executor.execute(action)

        assertTrue(result is ActionResult.Failure)
    }

    // -------------------------------------------------------------------------
    // timeoutMs parsed and forwarded
    // -------------------------------------------------------------------------

    @Test
    fun `timeoutMs param parsed and forwarded to ShellExecutor`() = runTest {
        val timeoutSlot = slot<Long?>()
        coEvery { shellExecutor.execute(any(), any(), captureNullable(timeoutSlot)) } returns
            ShellResult(0, "ok", "")

        val action = Action(
            type = "execute_shell_script",
            params = mapOf("command" to "cmd", "timeoutMs" to "3000")
        )
        executor.execute(action)

        assertEquals(3000L, timeoutSlot.captured)
    }

    @Test
    fun `invalid timeoutMs param forwarded as null`() = runTest {
        val timeoutSlot = slot<Long?>()
        coEvery { shellExecutor.execute(any(), any(), captureNullable(timeoutSlot)) } returns
            ShellResult(0, "ok", "")

        val action = Action(
            type = "execute_shell_script",
            params = mapOf("command" to "cmd", "timeoutMs" to "notanumber")
        )
        executor.execute(action)

        assertEquals(null, timeoutSlot.captured)
    }
}
