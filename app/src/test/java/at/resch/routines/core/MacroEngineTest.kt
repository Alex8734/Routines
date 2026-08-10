package at.resch.routines.core

import at.resch.routines.domain.model.Action
import at.resch.routines.domain.model.ActionResult
import at.resch.routines.domain.model.MacroScript
import at.resch.routines.domain.model.Trigger
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MacroEngine].
 *
 * android.util.Log is stubbed out via testOptions.unitTests.isReturnDefaultValues = true
 * in app/build.gradle.kts — no Robolectric needed.
 */
class MacroEngineTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private val validJson = """
        {
          "id": "m1",
          "name": "Test",
          "enabled": true,
          "trigger": { "type": "on_startup" },
          "actions": [
            { "type": "log", "params": { "message": "hello" } }
          ]
        }
    """.trimIndent()

    private fun buildMacro(vararg actions: Action) = MacroScript(
        id = "m1",
        name = "Test",
        enabled = true,
        trigger = Trigger.OnStartup,
        actions = actions.toList()
    )

    // -----------------------------------------------------------------------
    // parse()
    // -----------------------------------------------------------------------

    @Test
    fun `parse valid JSON returns MacroScript`() {
        val engine = MacroEngine(emptyList())
        val result = engine.parse(validJson)
        assertNotNull(result)
        assertEquals("m1", result!!.id)
        assertEquals(Trigger.OnStartup, result.trigger)
        assertEquals("log", result.actions.single().type)
    }

    @Test
    fun `parse invalid JSON returns null without throwing`() {
        val engine = MacroEngine(emptyList())
        val result = engine.parse("not-json-at-all{{{")
        assertNull(result)
    }

    @Test
    fun `parse empty string returns null without throwing`() {
        val engine = MacroEngine(emptyList())
        val result = engine.parse("")
        assertNull(result)
    }

    @Test
    fun `parse JSON missing required field returns null`() {
        val engine = MacroEngine(emptyList())
        // "id" field is missing
        val result = engine.parse("""{"name":"x","trigger":{"type":"on_startup"},"actions":[]}""")
        assertNull(result)
    }

    // -----------------------------------------------------------------------
    // execute()
    // -----------------------------------------------------------------------

    @Test
    fun `execute runs actions sequentially and returns results in order`() = runTest {
        val exec1 = mockk<ActionExecutor>()
        val exec2 = mockk<ActionExecutor>()
        coEvery { exec1.type } returns "type1"
        coEvery { exec2.type } returns "type2"
        coEvery { exec1.execute(any()) } returns ActionResult.Success("out1")
        coEvery { exec2.execute(any()) } returns ActionResult.Success("out2")

        val engine = MacroEngine(listOf(exec1, exec2))
        val macro = buildMacro(
            Action(type = "type1"),
            Action(type = "type2")
        )

        val results = engine.execute(macro)

        assertEquals(2, results.size)
        assertEquals(ActionResult.Success("out1"), results[0])
        assertEquals(ActionResult.Success("out2"), results[1])

        coVerifyOrder {
            exec1.execute(any())
            exec2.execute(any())
        }
    }

    @Test
    fun `unknown action type produces Failure and subsequent actions still run`() = runTest {
        val knownExec = mockk<ActionExecutor>()
        coEvery { knownExec.type } returns "log"
        coEvery { knownExec.execute(any()) } returns ActionResult.Success("done")

        val engine = MacroEngine(listOf(knownExec))
        val macro = buildMacro(
            Action(type = "unknown_type"),
            Action(type = "log")
        )

        val results = engine.execute(macro)

        assertEquals(2, results.size)
        assertTrue("first result must be Failure", results[0] is ActionResult.Failure)
        assertTrue("second result must be Success", results[1] is ActionResult.Success)
    }

    @Test
    fun `executor that throws produces Failure and engine does not crash`() = runTest {
        val throwingExec = mockk<ActionExecutor>()
        val safeExec = mockk<ActionExecutor>()
        coEvery { throwingExec.type } returns "throw_me"
        coEvery { safeExec.type } returns "safe"
        coEvery { throwingExec.execute(any()) } throws RuntimeException("boom")
        coEvery { safeExec.execute(any()) } returns ActionResult.Success("ok")

        val engine = MacroEngine(listOf(throwingExec, safeExec))
        val macro = buildMacro(
            Action(type = "throw_me"),
            Action(type = "safe")
        )

        val results = engine.execute(macro)

        assertEquals(2, results.size)
        val failure = results[0] as ActionResult.Failure
        assertNotNull(failure.cause)
        assertEquals("boom", failure.cause!!.message)
        assertEquals(ActionResult.Success("ok"), results[1])
    }

    @Test
    fun `execute with no actions returns empty list`() = runTest {
        val engine = MacroEngine(emptyList())
        val macro = buildMacro()
        val results = engine.execute(macro)
        assertTrue(results.isEmpty())
    }

    // -----------------------------------------------------------------------
    // parseAndExecute()
    // -----------------------------------------------------------------------

    @Test
    fun `parseAndExecute with valid JSON executes and returns results`() = runTest {
        val exec = mockk<ActionExecutor>()
        coEvery { exec.type } returns "log"
        coEvery { exec.execute(any()) } returns ActionResult.Success("hi")

        val engine = MacroEngine(listOf(exec))
        val results = engine.parseAndExecute(validJson)

        assertNotNull(results)
        assertEquals(1, results!!.size)
        assertEquals(ActionResult.Success("hi"), results[0])
    }

    @Test
    fun `parseAndExecute with bad JSON returns null`() = runTest {
        val engine = MacroEngine(emptyList())
        val result = engine.parseAndExecute("garbage")
        assertNull(result)
    }
}
