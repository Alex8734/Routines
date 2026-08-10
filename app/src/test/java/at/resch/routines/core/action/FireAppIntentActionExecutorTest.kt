package at.resch.routines.core.action

import android.content.Intent
import at.resch.routines.domain.model.Action
import at.resch.routines.domain.model.ActionResult
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [FireAppIntentActionExecutor].
 *
 * [IntentLauncher] is the test seam — no real Context or PackageManager needed.
 * android.content.Intent is mocked with MockK (relaxed=true) since it is a final
 * Android class and testOptions.unitTests.isReturnDefaultValues = true provides
 * safe default stubs for any un-mocked Android methods.
 */
class FireAppIntentActionExecutorTest {

    private lateinit var launcher: IntentLauncher
    private lateinit var executor: FireAppIntentActionExecutor

    // A relaxed mock Intent so we can pass it around without any real Android runtime.
    private val fakeIntent = mockk<Intent>(relaxed = true)

    @Before
    fun setup() {
        launcher = mockk()
        executor = FireAppIntentActionExecutor(launcher)
    }

    // Helper to build the standard action
    private fun action(vararg params: Pair<String, String>) =
        Action(type = "fire_app_intent", params = mapOf(*params))

    // -------------------------------------------------------------------------
    // Missing package param → Failure, no launcher calls
    // -------------------------------------------------------------------------

    @Test
    fun `missing package returns Failure without calling launcher`() = runTest {
        val result = executor.execute(action())

        assertTrue(result is ActionResult.Failure)
        verify(exactly = 0) { launcher.launchIntentFor(any()) }
        verify(exactly = 0) { launcher.canResolve(any()) }
        verify(exactly = 0) { launcher.startActivity(any()) }
    }

    @Test
    fun `blank package returns Failure`() = runTest {
        val result = executor.execute(action("package" to "   "))

        assertTrue(result is ActionResult.Failure)
    }

    // -------------------------------------------------------------------------
    // Simple app launch (only package) → Success
    // -------------------------------------------------------------------------

    @Test
    fun `only package provided uses launchIntentFor and succeeds`() = runTest {
        every { launcher.launchIntentFor("com.example.app") } returns fakeIntent
        justRun { launcher.startActivity(fakeIntent) }

        val result = executor.execute(action("package" to "com.example.app"))

        assertTrue(result is ActionResult.Success)
        verify(exactly = 1) { launcher.startActivity(fakeIntent) }
        // explicit intent path should NOT be taken
        verify(exactly = 0) { launcher.canResolve(any()) }
    }

    // -------------------------------------------------------------------------
    // launchIntentFor returns null → not installed → Failure
    // -------------------------------------------------------------------------

    @Test
    fun `launchIntentFor returns null means not installed returns Failure`() = runTest {
        every { launcher.launchIntentFor(any()) } returns null

        val result = executor.execute(action("package" to "com.not.installed"))

        assertTrue(result is ActionResult.Failure)
        val reason = (result as ActionResult.Failure).reason
        assertTrue(reason.contains("com.not.installed"))
        verify(exactly = 0) { launcher.startActivity(any()) }
    }

    // -------------------------------------------------------------------------
    // Explicit intent (action + data + className) + canResolve true → started
    // -------------------------------------------------------------------------

    @Test
    fun `explicit intent with action and canResolve true starts activity`() = runTest {
        every { launcher.canResolve(any()) } returns true
        justRun { launcher.startActivity(any()) }

        val result = executor.execute(
            action(
                "package" to "com.example.app",
                "action" to "android.intent.action.VIEW"
            )
        )

        assertTrue(result is ActionResult.Success)
        verify(exactly = 1) { launcher.startActivity(any()) }
        // launchIntentFor must NOT be called when explicit params present
        verify(exactly = 0) { launcher.launchIntentFor(any()) }
    }

    @Test
    fun `explicit intent with className and canResolve true succeeds`() = runTest {
        every { launcher.canResolve(any()) } returns true
        justRun { launcher.startActivity(any()) }

        val result = executor.execute(
            action(
                "package" to "com.example.app",
                "className" to "com.example.app.MainActivity"
            )
        )

        assertTrue(result is ActionResult.Success)
    }

    @Test
    fun `explicit intent with data and canResolve true succeeds`() = runTest {
        every { launcher.canResolve(any()) } returns true
        justRun { launcher.startActivity(any()) }

        val result = executor.execute(
            action(
                "package" to "com.example.app",
                "data" to "content://media/external/images"
            )
        )

        assertTrue(result is ActionResult.Success)
    }

    // -------------------------------------------------------------------------
    // Explicit intent + canResolve false → Failure, startActivity never called
    // -------------------------------------------------------------------------

    @Test
    fun `explicit intent with canResolve false returns Failure`() = runTest {
        every { launcher.canResolve(any()) } returns false

        val result = executor.execute(
            action(
                "package" to "com.example.app",
                "action" to "android.intent.action.SEND"
            )
        )

        assertTrue(result is ActionResult.Failure)
        verify(exactly = 0) { launcher.startActivity(any()) }
    }

    // -------------------------------------------------------------------------
    // startActivity throws → Failure carrying cause, no crash
    // -------------------------------------------------------------------------

    @Test
    fun `startActivity throws returns Failure with cause and does not crash`() = runTest {
        every { launcher.launchIntentFor("com.example.app") } returns fakeIntent
        val exception = SecurityException("Not allowed")
        every { launcher.startActivity(any()) } throws exception

        val result = executor.execute(action("package" to "com.example.app"))

        assertTrue(result is ActionResult.Failure)
        val failure = result as ActionResult.Failure
        assertNotNull(failure.cause)
        assertTrue(failure.cause is SecurityException)
    }

    @Test
    fun `startActivity with explicit intent throws returns Failure with cause`() = runTest {
        every { launcher.canResolve(any()) } returns true
        val exception = RuntimeException("Intent resolution error")
        every { launcher.startActivity(any()) } throws exception

        val result = executor.execute(
            action(
                "package" to "com.example.app",
                "action" to "android.intent.action.VIEW"
            )
        )

        assertTrue(result is ActionResult.Failure)
        assertNotNull((result as ActionResult.Failure).cause)
    }
}
