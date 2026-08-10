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
 * Unit tests for [SetBrightnessActionExecutor].
 *
 * [BrightnessController] is fully mocked — no Android settings infrastructure required.
 * Mapping: (percent * 255) / 100, clamped to 0..100 before mapping.
 */
class SetBrightnessActionExecutorTest {

    private lateinit var controller: BrightnessController
    private lateinit var executor: SetBrightnessActionExecutor

    @Before
    fun setup() {
        controller = mockk()
        executor = SetBrightnessActionExecutor(controller)
    }

    // -----------------------------------------------------------------------
    // type discriminator
    // -----------------------------------------------------------------------

    @Test
    fun `type is set_brightness`() {
        assert(executor.type == "set_brightness")
    }

    // -----------------------------------------------------------------------
    // Success path — level mapped correctly to 0..255 range
    // -----------------------------------------------------------------------

    @Test
    fun `level 100 maps to 255 and returns Success`() = runTest {
        every { controller.setBrightness(255) } just runs

        val result = executor.execute(Action(type = "set_brightness", params = mapOf("level" to "100")))

        assertTrue(result is ActionResult.Success)
        verify(exactly = 1) { controller.setBrightness(255) }
    }

    @Test
    fun `level 0 maps to 0 and returns Success`() = runTest {
        every { controller.setBrightness(0) } just runs

        val result = executor.execute(Action(type = "set_brightness", params = mapOf("level" to "0")))

        assertTrue(result is ActionResult.Success)
        verify(exactly = 1) { controller.setBrightness(0) }
    }

    @Test
    fun `level 50 maps to 127 via integer division`() = runTest {
        // (50 * 255) / 100 = 127 (integer division)
        every { controller.setBrightness(127) } just runs

        val result = executor.execute(Action(type = "set_brightness", params = mapOf("level" to "50")))

        assertTrue(result is ActionResult.Success)
        verify(exactly = 1) { controller.setBrightness(127) }
    }

    // -----------------------------------------------------------------------
    // Clamping — out-of-range value is clamped before mapping
    // -----------------------------------------------------------------------

    @Test
    fun `level 150 is clamped to 100 and maps to 255`() = runTest {
        every { controller.setBrightness(255) } just runs

        val result = executor.execute(Action(type = "set_brightness", params = mapOf("level" to "150")))

        assertTrue(result is ActionResult.Success)
        verify(exactly = 1) { controller.setBrightness(255) }
    }

    @Test
    fun `negative level is clamped to 0 and maps to 0`() = runTest {
        every { controller.setBrightness(0) } just runs

        val result = executor.execute(Action(type = "set_brightness", params = mapOf("level" to "-10")))

        assertTrue(result is ActionResult.Success)
        verify(exactly = 1) { controller.setBrightness(0) }
    }

    // -----------------------------------------------------------------------
    // Failure path — missing/invalid level → Failure WITHOUT controller call
    // -----------------------------------------------------------------------

    @Test
    fun `missing level param — returns Failure without calling setBrightness`() = runTest {
        val result = executor.execute(Action(type = "set_brightness", params = emptyMap()))

        assertTrue(result is ActionResult.Failure)
        verify(exactly = 0) { controller.setBrightness(any()) }
    }

    @Test
    fun `non-Int level abc — returns Failure without calling setBrightness`() = runTest {
        val result = executor.execute(Action(type = "set_brightness", params = mapOf("level" to "abc")))

        assertTrue(result is ActionResult.Failure)
        verify(exactly = 0) { controller.setBrightness(any()) }
    }

    @Test
    fun `missing level — Failure reason is non-empty`() = runTest {
        val result = executor.execute(Action(type = "set_brightness", params = emptyMap()))

        val failure = result as ActionResult.Failure
        assertTrue(failure.reason.isNotEmpty())
    }

    // -----------------------------------------------------------------------
    // Failure path — controller throws → Failure (no re-throw)
    // -----------------------------------------------------------------------

    @Test
    fun `controller throws SecurityException — returns Failure without re-throwing`() = runTest {
        every { controller.setBrightness(any()) } throws SecurityException("WRITE_SETTINGS nicht gewährt")

        val result = executor.execute(Action(type = "set_brightness", params = mapOf("level" to "80")))

        assertTrue(result is ActionResult.Failure)
    }

    @Test
    fun `controller throws SecurityException — Failure carries the cause`() = runTest {
        val cause = SecurityException("WRITE_SETTINGS denied")
        every { controller.setBrightness(any()) } throws cause

        val result = executor.execute(Action(type = "set_brightness", params = mapOf("level" to "50")))

        val failure = result as ActionResult.Failure
        assert(failure.cause == cause)
    }
}
