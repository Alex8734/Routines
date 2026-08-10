package at.resch.routines.core.action

import at.resch.routines.domain.model.Action
import at.resch.routines.domain.model.ActionResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [WaitActionExecutor].
 *
 * `delay` in the executor honors the virtual-time scheduler supplied by `runTest`,
 * so tests run without real wall-clock waiting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WaitActionExecutorTest {

    private val executor = WaitActionExecutor()

    // -----------------------------------------------------------------------
    // type
    // -----------------------------------------------------------------------

    @Test
    fun `type is wait`() {
        assertEquals("wait", executor.type)
    }

    // -----------------------------------------------------------------------
    // Success cases
    // -----------------------------------------------------------------------

    @Test
    fun `valid durationMs 5000 returns Success with correct output`() = runTest {
        val action = Action(type = "wait", params = mapOf("durationMs" to "5000"))
        val result = executor.execute(action)
        assertEquals(ActionResult.Success(output = "waited 5000ms"), result)
    }

    @Test
    fun `valid durationMs 5000 advances virtual time by 5000ms`() = runTest {
        val action = Action(type = "wait", params = mapOf("durationMs" to "5000"))
        val timeBefore = currentTime
        executor.execute(action)
        val elapsed = currentTime - timeBefore
        assertEquals(5000L, elapsed)
    }

    @Test
    fun `durationMs 0 returns Success with waited 0ms output`() = runTest {
        val action = Action(type = "wait", params = mapOf("durationMs" to "0"))
        val result = executor.execute(action)
        assertEquals(ActionResult.Success(output = "waited 0ms"), result)
    }

    @Test
    fun `durationMs 0 does not advance virtual time`() = runTest {
        val action = Action(type = "wait", params = mapOf("durationMs" to "0"))
        val timeBefore = currentTime
        executor.execute(action)
        assertEquals(0L, currentTime - timeBefore)
    }

    @Test
    fun `durationMs above MAX clamps to 3600000ms`() = runTest {
        val action = Action(type = "wait", params = mapOf("durationMs" to "99999999"))
        val result = executor.execute(action)
        assertEquals(ActionResult.Success(output = "waited 3600000ms"), result)
    }

    @Test
    fun `durationMs at exact MAX 3600000 is not clamped`() = runTest {
        val action = Action(type = "wait", params = mapOf("durationMs" to "3600000"))
        val result = executor.execute(action)
        assertEquals(ActionResult.Success(output = "waited 3600000ms"), result)
    }

    // -----------------------------------------------------------------------
    // Failure cases
    // -----------------------------------------------------------------------

    @Test
    fun `missing durationMs param returns Failure`() = runTest {
        val action = Action(type = "wait", params = emptyMap())
        val result = executor.execute(action)
        assertTrue("expected Failure", result is ActionResult.Failure)
    }

    @Test
    fun `non-numeric durationMs returns Failure`() = runTest {
        val action = Action(type = "wait", params = mapOf("durationMs" to "abc"))
        val result = executor.execute(action)
        assertTrue("expected Failure", result is ActionResult.Failure)
    }

    @Test
    fun `negative durationMs returns Failure`() = runTest {
        val action = Action(type = "wait", params = mapOf("durationMs" to "-1"))
        val result = executor.execute(action)
        assertTrue("expected Failure", result is ActionResult.Failure)
    }

    @Test
    fun `float string durationMs returns Failure`() = runTest {
        val action = Action(type = "wait", params = mapOf("durationMs" to "1.5"))
        val result = executor.execute(action)
        assertTrue("expected Failure", result is ActionResult.Failure)
    }

    @Test
    fun `empty string durationMs returns Failure`() = runTest {
        val action = Action(type = "wait", params = mapOf("durationMs" to ""))
        val result = executor.execute(action)
        assertTrue("expected Failure", result is ActionResult.Failure)
    }

    @Test
    fun `Failure for missing param does not throw`() = runTest {
        val action = Action(type = "wait", params = emptyMap())
        // Must complete without exception
        executor.execute(action)
    }

    @Test
    fun `Failure reason mentions durationMs`() = runTest {
        val action = Action(type = "wait", params = mapOf("durationMs" to "bad"))
        val result = executor.execute(action) as ActionResult.Failure
        assertTrue(
            "failure reason should mention 'durationMs'",
            result.reason.contains("durationMs")
        )
    }
}
