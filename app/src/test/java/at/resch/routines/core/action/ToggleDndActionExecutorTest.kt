package at.resch.routines.core.action

import at.resch.routines.domain.model.Action
import at.resch.routines.domain.model.ActionResult
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ToggleDndActionExecutor].
 *
 * [DndController] is fully mocked — no Android notification infrastructure required.
 */
class ToggleDndActionExecutorTest {

    private lateinit var controller: DndController
    private lateinit var executor: ToggleDndActionExecutor

    @Before
    fun setup() {
        controller = mockk()
        executor = ToggleDndActionExecutor(controller)
    }

    // -----------------------------------------------------------------------
    // type discriminator
    // -----------------------------------------------------------------------

    @Test
    fun `type is toggle_dnd`() {
        assert(executor.type == "toggle_dnd")
    }

    // -----------------------------------------------------------------------
    // Success path — enabled param parsed correctly
    // -----------------------------------------------------------------------

    @Test
    fun `enabled true — setEnabled called with true and returns Success`() = runTest {
        every { controller.setEnabled(true) } just runs

        val result = executor.execute(Action(type = "toggle_dnd", params = mapOf("enabled" to "true")))

        assertTrue(result is ActionResult.Success)
        verify(exactly = 1) { controller.setEnabled(true) }
    }

    @Test
    fun `enabled false — setEnabled called with false and returns Success`() = runTest {
        every { controller.setEnabled(false) } just runs

        val result = executor.execute(Action(type = "toggle_dnd", params = mapOf("enabled" to "false")))

        assertTrue(result is ActionResult.Success)
        verify(exactly = 1) { controller.setEnabled(false) }
    }

    // -----------------------------------------------------------------------
    // Default / fallback param handling
    // -----------------------------------------------------------------------

    @Test
    fun `missing enabled param defaults to true`() = runTest {
        every { controller.setEnabled(true) } just runs

        val result = executor.execute(Action(type = "toggle_dnd", params = emptyMap()))

        assertTrue(result is ActionResult.Success)
        verify(exactly = 1) { controller.setEnabled(true) }
    }

    @Test
    fun `unparseable enabled value defaults to true`() = runTest {
        every { controller.setEnabled(true) } just runs

        val result = executor.execute(Action(type = "toggle_dnd", params = mapOf("enabled" to "yes")))

        assertTrue(result is ActionResult.Success)
        verify(exactly = 1) { controller.setEnabled(true) }
    }

    // -----------------------------------------------------------------------
    // Failure path — controller throws → Failure (no re-throw)
    // -----------------------------------------------------------------------

    @Test
    fun `controller throws SecurityException — returns Failure without re-throwing`() = runTest {
        every { controller.setEnabled(any()) } throws SecurityException("Notification Policy Access nicht gewährt")

        val result = executor.execute(Action(type = "toggle_dnd", params = mapOf("enabled" to "true")))

        assertTrue(result is ActionResult.Failure)
    }

    @Test
    fun `controller throws SecurityException — Failure carries the cause`() = runTest {
        val cause = SecurityException("Policy denied")
        every { controller.setEnabled(any()) } throws cause

        val result = executor.execute(Action(type = "toggle_dnd", params = emptyMap()))

        val failure = result as ActionResult.Failure
        assert(failure.cause == cause)
    }

    @Test
    fun `controller throws SecurityException — Failure reason is non-empty`() = runTest {
        every { controller.setEnabled(any()) } throws SecurityException("denied")

        val result = executor.execute(Action(type = "toggle_dnd", params = emptyMap()))

        val failure = result as ActionResult.Failure
        assertTrue(failure.reason.isNotEmpty())
    }
}
