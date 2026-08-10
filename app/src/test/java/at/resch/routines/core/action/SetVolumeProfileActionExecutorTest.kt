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
 * Unit tests for [SetVolumeProfileActionExecutor].
 *
 * [VolumeController] is fully mocked — no Android audio infrastructure required.
 */
class SetVolumeProfileActionExecutorTest {

    private lateinit var controller: VolumeController
    private lateinit var executor: SetVolumeProfileActionExecutor

    @Before
    fun setup() {
        controller = mockk()
        executor = SetVolumeProfileActionExecutor(controller)
    }

    // -----------------------------------------------------------------------
    // type discriminator
    // -----------------------------------------------------------------------

    @Test
    fun `type is set_volume_profile`() {
        assert(executor.type == "set_volume_profile")
    }

    // -----------------------------------------------------------------------
    // Success path — profile param parsed case-insensitively
    // -----------------------------------------------------------------------

    @Test
    fun `profile vibrate lowercase — setProfile called with VIBRATE and returns Success`() = runTest {
        every { controller.setProfile(VolumeProfile.VIBRATE) } just runs

        val result = executor.execute(Action(type = "set_volume_profile", params = mapOf("profile" to "vibrate")))

        assertTrue(result is ActionResult.Success)
        verify(exactly = 1) { controller.setProfile(VolumeProfile.VIBRATE) }
    }

    @Test
    fun `profile SILENT uppercase — setProfile called with SILENT (case-insensitive)`() = runTest {
        every { controller.setProfile(VolumeProfile.SILENT) } just runs

        val result = executor.execute(Action(type = "set_volume_profile", params = mapOf("profile" to "SILENT")))

        assertTrue(result is ActionResult.Success)
        verify(exactly = 1) { controller.setProfile(VolumeProfile.SILENT) }
    }

    @Test
    fun `profile normal — setProfile called with NORMAL`() = runTest {
        every { controller.setProfile(VolumeProfile.NORMAL) } just runs

        val result = executor.execute(Action(type = "set_volume_profile", params = mapOf("profile" to "normal")))

        assertTrue(result is ActionResult.Success)
        verify(exactly = 1) { controller.setProfile(VolumeProfile.NORMAL) }
    }

    // -----------------------------------------------------------------------
    // Default param — missing profile defaults to NORMAL
    // -----------------------------------------------------------------------

    @Test
    fun `missing profile param defaults to NORMAL`() = runTest {
        every { controller.setProfile(VolumeProfile.NORMAL) } just runs

        val result = executor.execute(Action(type = "set_volume_profile", params = emptyMap()))

        assertTrue(result is ActionResult.Success)
        verify(exactly = 1) { controller.setProfile(VolumeProfile.NORMAL) }
    }

    // -----------------------------------------------------------------------
    // Failure path — invalid profile value → Failure WITHOUT controller call
    // -----------------------------------------------------------------------

    @Test
    fun `invalid profile loud — returns Failure without calling setProfile`() = runTest {
        val result = executor.execute(Action(type = "set_volume_profile", params = mapOf("profile" to "loud")))

        assertTrue(result is ActionResult.Failure)
        verify(exactly = 0) { controller.setProfile(any()) }
    }

    @Test
    fun `invalid profile — Failure reason is non-empty`() = runTest {
        val result = executor.execute(Action(type = "set_volume_profile", params = mapOf("profile" to "loud")))

        val failure = result as ActionResult.Failure
        assertTrue(failure.reason.isNotEmpty())
    }

    // -----------------------------------------------------------------------
    // Failure path — controller throws → Failure (no re-throw)
    // -----------------------------------------------------------------------

    @Test
    fun `controller throws SecurityException — returns Failure without re-throwing`() = runTest {
        every { controller.setProfile(any()) } throws SecurityException("DND policy access denied")

        val result = executor.execute(Action(type = "set_volume_profile", params = mapOf("profile" to "silent")))

        assertTrue(result is ActionResult.Failure)
    }

    @Test
    fun `controller throws SecurityException — Failure carries the cause`() = runTest {
        val cause = SecurityException("Policy denied")
        every { controller.setProfile(any()) } throws cause

        val result = executor.execute(Action(type = "set_volume_profile", params = mapOf("profile" to "vibrate")))

        val failure = result as ActionResult.Failure
        assert(failure.cause == cause)
    }
}
