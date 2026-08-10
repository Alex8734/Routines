package at.resch.routines.core

import at.resch.routines.core.action.WaitActionExecutor
import at.resch.routines.domain.model.Action
import at.resch.routines.domain.model.ActionResult
import at.resch.routines.domain.model.MacroScript
import at.resch.routines.domain.model.Trigger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration-level unit tests verifying MacroEngine's sequential ordering
 * combined with the real [WaitActionExecutor].
 *
 * Because the engine runs actions via a sequential suspend `map`, a `delay`
 * inside the wait executor blocks subsequent actions in virtual time — tests
 * can assert the timing relationship by capturing `currentTime` before/after.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MacroEngineSequentialOrderTest {

    private fun buildMacro(vararg actions: Action) = MacroScript(
        id = "seq-test",
        name = "Sequential",
        enabled = true,
        trigger = Trigger.OnStartup,
        actions = actions.toList()
    )

    // -----------------------------------------------------------------------
    // Simple recording executor (top-level scope to avoid inner-class capture issues)
    // -----------------------------------------------------------------------

    /**
     * Tracks execution times by writing to a shared mutable list so the outer
     * `runTest` lambda (which owns `currentTime`) can pass time in.
     */
    private fun recordingEngine(
        typeA: String,
        typeB: String,
        timeRecorder: MutableList<Pair<String, Long>>,
        timeSupplier: () -> Long
    ): MacroEngine {
        val execA = object : ActionExecutor {
            override val type = typeA
            override suspend fun execute(action: Action): ActionResult {
                timeRecorder += typeA to timeSupplier()
                return ActionResult.Success(output = typeA)
            }
        }
        val execB = object : ActionExecutor {
            override val type = typeB
            override suspend fun execute(action: Action): ActionResult {
                timeRecorder += typeB to timeSupplier()
                return ActionResult.Success(output = typeB)
            }
        }
        return MacroEngine(listOf(execA, execB, WaitActionExecutor()))
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    fun `results are returned in action list order A wait B`() = runTest {
        val times = mutableListOf<Pair<String, Long>>()
        val engine = recordingEngine("actionA", "actionB", times) { currentTime }
        val macro = buildMacro(
            Action(type = "actionA"),
            Action(type = "wait", params = mapOf("durationMs" to "1000")),
            Action(type = "actionB")
        )

        val results = engine.execute(macro)

        assertEquals(3, results.size)
        assertEquals(ActionResult.Success(output = "actionA"), results[0])
        assertEquals(ActionResult.Success(output = "waited 1000ms"), results[1])
        assertEquals(ActionResult.Success(output = "actionB"), results[2])
    }

    @Test
    fun `both recording executors ran`() = runTest {
        val times = mutableListOf<Pair<String, Long>>()
        val engine = recordingEngine("actionA", "actionB", times) { currentTime }
        val macro = buildMacro(
            Action(type = "actionA"),
            Action(type = "wait", params = mapOf("durationMs" to "1000")),
            Action(type = "actionB")
        )

        engine.execute(macro)

        val ranTypes = times.map { it.first }
        assertTrue("executor A should have run", "actionA" in ranTypes)
        assertTrue("executor B should have run", "actionB" in ranTypes)
    }

    @Test
    fun `B executes exactly 1000ms after A due to wait action`() = runTest {
        val times = mutableListOf<Pair<String, Long>>()
        val engine = recordingEngine("actionA", "actionB", times) { currentTime }
        val macro = buildMacro(
            Action(type = "actionA"),
            Action(type = "wait", params = mapOf("durationMs" to "1000")),
            Action(type = "actionB")
        )

        engine.execute(macro)

        val timeA = times.first { it.first == "actionA" }.second
        val timeB = times.first { it.first == "actionB" }.second
        assertEquals(
            "B should execute exactly 1000ms after A (the wait delay)",
            1000L,
            timeB - timeA
        )
    }

    @Test
    fun `A executes before wait delay and B executes after 500ms`() = runTest {
        val times = mutableListOf<Pair<String, Long>>()
        val engine = recordingEngine("actionA", "actionB", times) { currentTime }
        val macro = buildMacro(
            Action(type = "actionA"),
            Action(type = "wait", params = mapOf("durationMs" to "500")),
            Action(type = "actionB")
        )

        engine.execute(macro)

        val timeA = times.first { it.first == "actionA" }.second
        val timeB = times.first { it.first == "actionB" }.second
        assertTrue("A should run before the 500ms delay", timeA < 500L)
        assertTrue("B should run at or after 500ms", timeB >= 500L)
    }

    @Test
    fun `zero-duration wait still runs all actions in order`() = runTest {
        val times = mutableListOf<Pair<String, Long>>()
        val engine = recordingEngine("actionA", "actionB", times) { currentTime }
        val macro = buildMacro(
            Action(type = "actionA"),
            Action(type = "wait", params = mapOf("durationMs" to "0")),
            Action(type = "actionB")
        )

        val results = engine.execute(macro)

        assertEquals(3, results.size)
        assertTrue(results[0] is ActionResult.Success)
        assertTrue(results[1] is ActionResult.Success)
        assertTrue(results[2] is ActionResult.Success)
        val ranTypes = times.map { it.first }
        assertTrue("actionA" in ranTypes)
        assertTrue("actionB" in ranTypes)
    }

    @Test
    fun `wait Failure does not stop subsequent actions`() = runTest {
        val times = mutableListOf<Pair<String, Long>>()
        val engine = recordingEngine("actionA", "actionB", times) { currentTime }
        val macro = buildMacro(
            Action(type = "actionA"),
            Action(type = "wait", params = emptyMap()), // invalid → Failure
            Action(type = "actionB")
        )

        val results = engine.execute(macro)

        assertEquals(3, results.size)
        assertTrue(results[0] is ActionResult.Success)
        assertTrue(results[1] is ActionResult.Failure)
        assertTrue(results[2] is ActionResult.Success)
        assertTrue(
            "B should still have run after wait Failure",
            times.any { it.first == "actionB" }
        )
    }
}
