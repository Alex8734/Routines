package at.resch.routines.core.shell

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Unit tests for [ShellExecutor].
 *
 * Process is mocked via MockK. android.util.Log returns defaults via
 * testOptions.unitTests.isReturnDefaultValues = true.
 */
class ShellExecutorTest {

    private val testDispatcher = StandardTestDispatcher()

    // Helper: build a mocked Process that immediately "finishes" with the given values.
    private fun fakeProcess(
        exitCode: Int,
        stdout: String = "",
        stderr: String = "",
        waitForResult: Boolean = true  // used for timeout variant
    ): Process {
        val process = mockk<Process>(relaxed = true)
        every { process.inputStream } returns ByteArrayInputStream(stdout.toByteArray())
        every { process.errorStream } returns ByteArrayInputStream(stderr.toByteArray())
        every { process.waitFor() } returns exitCode
        every { process.waitFor(any(), any()) } returns waitForResult
        every { process.exitValue() } returns exitCode
        every { process.destroyForcibly() } returns process
        return process
    }

    // -------------------------------------------------------------------------
    // argv construction
    // -------------------------------------------------------------------------

    @Test
    fun `USER mode builds sh -c argv`() = runTest(testDispatcher) {
        val runner = mockk<ProcessRunner>()
        val process = fakeProcess(0, "out")
        val capturedArgs = mutableListOf<List<String>>()
        every { runner.start(capture(capturedArgs)) } returns process

        val executor = ShellExecutor(runner, testDispatcher)
        executor.execute("echo hi", ShellExecutor.Mode.USER)

        assertEquals(listOf("sh", "-c", "echo hi"), capturedArgs.first())
    }

    @Test
    fun `ROOT mode builds su -c argv`() = runTest(testDispatcher) {
        val runner = mockk<ProcessRunner>()
        val process = fakeProcess(0, "root")
        val capturedArgs = mutableListOf<List<String>>()
        every { runner.start(capture(capturedArgs)) } returns process

        val executor = ShellExecutor(runner, testDispatcher)
        executor.execute("id", ShellExecutor.Mode.ROOT)

        assertEquals(listOf("su", "-c", "id"), capturedArgs.first())
    }

    // -------------------------------------------------------------------------
    // Exit code + stream capture
    // -------------------------------------------------------------------------

    @Test
    fun `exit 0 returns isSuccess true with trimmed stdout`() = runTest(testDispatcher) {
        val runner = mockk<ProcessRunner>()
        every { runner.start(any()) } returns fakeProcess(0, "  hello world  ")

        val result = ShellExecutor(runner, testDispatcher).execute("cmd")

        assertTrue(result.isSuccess)
        assertEquals("hello world", result.stdout)
    }

    @Test
    fun `non-zero exit returns isSuccess false and captures stderr`() = runTest(testDispatcher) {
        val runner = mockk<ProcessRunner>()
        every { runner.start(any()) } returns fakeProcess(1, stdout = "", stderr = "permission denied")

        val result = ShellExecutor(runner, testDispatcher).execute("cmd")

        assertFalse(result.isSuccess)
        assertEquals(1, result.exitCode)
        assertEquals("permission denied", result.stderr)
    }

    // -------------------------------------------------------------------------
    // ProcessRunner.start throws → no exception, TIMEOUT_EXIT_CODE
    // -------------------------------------------------------------------------

    @Test
    fun `processRunner start throws returns TIMEOUT_EXIT_CODE without throwing`() = runTest(testDispatcher) {
        val runner = mockk<ProcessRunner>()
        every { runner.start(any()) } throws java.io.IOException("su not found")

        val result = ShellExecutor(runner, testDispatcher).execute("id", ShellExecutor.Mode.ROOT)

        assertEquals(ShellResult.TIMEOUT_EXIT_CODE, result.exitCode)
        assertTrue(result.stderr.isNotBlank())
    }

    // -------------------------------------------------------------------------
    // Timeout path
    // -------------------------------------------------------------------------

    @Test
    fun `timeout calls destroyForcibly and returns TIMEOUT_EXIT_CODE`() = runTest(testDispatcher) {
        val runner = mockk<ProcessRunner>()
        // waitFor(timeout, unit) returns false → process did not finish in time
        val process = fakeProcess(0, waitForResult = false)
        every { runner.start(any()) } returns process

        val result = ShellExecutor(runner, testDispatcher).execute("slow", timeoutMs = 100L)

        assertEquals(ShellResult.TIMEOUT_EXIT_CODE, result.exitCode)
        verify(exactly = 1) { process.destroyForcibly() }
    }

    @Test
    fun `null timeout does not call timed waitFor`() = runTest(testDispatcher) {
        val runner = mockk<ProcessRunner>()
        val process = fakeProcess(0, "ok")
        every { runner.start(any()) } returns process

        ShellExecutor(runner, testDispatcher).execute("cmd", timeoutMs = null)

        // The timed overload must NOT be called
        verify(exactly = 0) { process.waitFor(any(), any()) }
        verify(exactly = 1) { process.waitFor() }
    }

    @Test
    fun `zero or negative timeoutMs is treated as no timeout`() = runTest(testDispatcher) {
        val runner = mockk<ProcessRunner>()
        val process = fakeProcess(0, "ok")
        every { runner.start(any()) } returns process

        ShellExecutor(runner, testDispatcher).execute("cmd", timeoutMs = 0L)

        verify(exactly = 0) { process.waitFor(any(), any()) }
    }
}
