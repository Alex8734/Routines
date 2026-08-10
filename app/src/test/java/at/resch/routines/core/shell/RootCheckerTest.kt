package at.resch.routines.core.shell

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [RootChecker] (caching + delegation to [RootProbe]).
 */
class RootCheckerTest {

    private lateinit var probe: RootProbe
    private lateinit var checker: RootChecker

    @Before
    fun setup() {
        probe = mockk()
        checker = RootChecker(probe)
    }

    // -------------------------------------------------------------------------
    // Binary absent → short-circuit false, canExecuteSu never called
    // -------------------------------------------------------------------------

    @Test
    fun `no su binary returns false without calling canExecuteSu`() = runTest {
        every { probe.suBinaryExists() } returns false

        val result = checker.isRootAvailable()

        assertFalse(result)
        coVerify(exactly = 0) { probe.canExecuteSu() }
    }

    // -------------------------------------------------------------------------
    // Binary present + su works → true
    // -------------------------------------------------------------------------

    @Test
    fun `su binary exists and canExecuteSu true returns true`() = runTest {
        every { probe.suBinaryExists() } returns true
        coEvery { probe.canExecuteSu() } returns true

        assertTrue(checker.isRootAvailable())
    }

    // -------------------------------------------------------------------------
    // Binary present + su fails → false
    // -------------------------------------------------------------------------

    @Test
    fun `su binary exists but canExecuteSu false returns false`() = runTest {
        every { probe.suBinaryExists() } returns true
        coEvery { probe.canExecuteSu() } returns false

        assertFalse(checker.isRootAvailable())
    }

    // -------------------------------------------------------------------------
    // Caching: probe called exactly once across repeated invocations
    // -------------------------------------------------------------------------

    @Test
    fun `repeated calls use cached result and probe invoked only once`() = runTest {
        every { probe.suBinaryExists() } returns true
        coEvery { probe.canExecuteSu() } returns true

        checker.isRootAvailable()
        checker.isRootAvailable()
        checker.isRootAvailable()

        // suBinaryExists and canExecuteSu should each be called exactly once
        verify(exactly = 1) { probe.suBinaryExists() }
        coVerify(exactly = 1) { probe.canExecuteSu() }
    }

    @Test
    fun `caching also works when result is false`() = runTest {
        every { probe.suBinaryExists() } returns false

        checker.isRootAvailable()
        checker.isRootAvailable()

        verify(exactly = 1) { probe.suBinaryExists() }
    }

    // -------------------------------------------------------------------------
    // invalidate() forces re-probe on next call
    // -------------------------------------------------------------------------

    @Test
    fun `invalidate clears cache and probe re-invoked on next call`() = runTest {
        every { probe.suBinaryExists() } returns true
        coEvery { probe.canExecuteSu() } returns true

        checker.isRootAvailable()  // fills cache
        checker.invalidate()
        checker.isRootAvailable()  // must re-probe

        verify(exactly = 2) { probe.suBinaryExists() }
        coVerify(exactly = 2) { probe.canExecuteSu() }
    }

    @Test
    fun `invalidate allows result to change on re-probe`() = runTest {
        every { probe.suBinaryExists() } returns true
        coEvery { probe.canExecuteSu() } returns false

        assertFalse(checker.isRootAvailable())

        // Now root becomes available after invalidate
        coEvery { probe.canExecuteSu() } returns true
        checker.invalidate()

        assertTrue(checker.isRootAvailable())
    }
}
