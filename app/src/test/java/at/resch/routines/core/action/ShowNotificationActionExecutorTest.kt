package at.resch.routines.core.action

import at.resch.routines.domain.model.Action
import at.resch.routines.domain.model.ActionResult
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ShowNotificationActionExecutor].
 *
 * [NotificationPoster] is fully mocked — no Android notification infrastructure required.
 */
class ShowNotificationActionExecutorTest {

    private lateinit var poster: NotificationPoster
    private lateinit var executor: ShowNotificationActionExecutor

    @Before
    fun setup() {
        poster = mockk()
        executor = ShowNotificationActionExecutor(poster)
    }

    // -----------------------------------------------------------------------
    // type discriminator
    // -----------------------------------------------------------------------

    @Test
    fun `type is show_notification`() {
        assertEquals("show_notification", executor.type)
    }

    // -----------------------------------------------------------------------
    // Success path — post called with extracted params
    // -----------------------------------------------------------------------

    @Test
    fun `success — post called with title and message from params`() = runTest {
        val titleSlot = slot<String>()
        val messageSlot = slot<String>()
        every { poster.post(capture(titleSlot), capture(messageSlot)) } just runs

        val action = Action(
            type = "show_notification",
            params = mapOf("title" to "Hello", "message" to "World")
        )

        val result = executor.execute(action)

        assertTrue(result is ActionResult.Success)
        assertEquals("Hello", titleSlot.captured)
        assertEquals("World", messageSlot.captured)
        verify(exactly = 1) { poster.post("Hello", "World") }
    }

    @Test
    fun `success message contains posted title`() = runTest {
        every { poster.post(any(), any()) } just runs

        val action = Action(
            type = "show_notification",
            params = mapOf("title" to "MyTitle", "message" to "body")
        )

        val result = executor.execute(action)

        assertTrue(result is ActionResult.Success)
        val output = (result as ActionResult.Success).output.orEmpty()
        assertTrue("Success output should mention title", output.contains("MyTitle"))
    }

    // -----------------------------------------------------------------------
    // Default params — missing title/message default to empty string
    // -----------------------------------------------------------------------

    @Test
    fun `missing title param defaults to empty string`() = runTest {
        val titleSlot = slot<String>()
        every { poster.post(capture(titleSlot), any()) } just runs

        val action = Action(
            type = "show_notification",
            params = mapOf("message" to "some message")
        )

        executor.execute(action)

        assertEquals("", titleSlot.captured)
    }

    @Test
    fun `missing message param defaults to empty string`() = runTest {
        val messageSlot = slot<String>()
        every { poster.post(any(), capture(messageSlot)) } just runs

        val action = Action(
            type = "show_notification",
            params = mapOf("title" to "Hi")
        )

        executor.execute(action)

        assertEquals("", messageSlot.captured)
    }

    @Test
    fun `both title and message missing — post called with empty strings`() = runTest {
        val titleSlot = slot<String>()
        val messageSlot = slot<String>()
        every { poster.post(capture(titleSlot), capture(messageSlot)) } just runs

        val action = Action(type = "show_notification", params = emptyMap())

        val result = executor.execute(action)

        assertTrue(result is ActionResult.Success)
        assertEquals("", titleSlot.captured)
        assertEquals("", messageSlot.captured)
    }

    // -----------------------------------------------------------------------
    // Failure path — post throws → Failure (no crash)
    // -----------------------------------------------------------------------

    @Test
    fun `post throws SecurityException — returns Failure without re-throwing`() = runTest {
        every { poster.post(any(), any()) } throws SecurityException("POST_NOTIFICATIONS denied")

        val action = Action(
            type = "show_notification",
            params = mapOf("title" to "T", "message" to "M")
        )

        val result = executor.execute(action)

        assertTrue(result is ActionResult.Failure)
    }

    @Test
    fun `post throws SecurityException — Failure carries the cause`() = runTest {
        val cause = SecurityException("Permission denied")
        every { poster.post(any(), any()) } throws cause

        val action = Action(type = "show_notification", params = emptyMap())

        val result = executor.execute(action)

        val failure = result as ActionResult.Failure
        assertEquals(cause, failure.cause)
    }

    @Test
    fun `post throws RuntimeException — returns Failure`() = runTest {
        every { poster.post(any(), any()) } throws RuntimeException("Unexpected error")

        val action = Action(type = "show_notification", params = emptyMap())

        val result = executor.execute(action)

        assertTrue(result is ActionResult.Failure)
    }

    @Test
    fun `post throws — Failure reason is non-empty`() = runTest {
        every { poster.post(any(), any()) } throws SecurityException("denied")

        val result = executor.execute(Action(type = "show_notification", params = emptyMap()))

        val failure = result as ActionResult.Failure
        assertTrue(failure.reason.isNotEmpty())
    }
}
