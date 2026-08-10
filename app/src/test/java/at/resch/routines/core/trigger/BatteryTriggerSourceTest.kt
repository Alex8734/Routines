package at.resch.routines.core.trigger

import at.resch.routines.core.SystemEvent
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BatteryTriggerSource].
 *
 * [BatteryLevelProvider] is mocked so no Android broadcast infrastructure is needed.
 * Filtering of the invalid level -1 is verified against the production code's
 * actual guard: `if (percent in 0..100)`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BatteryTriggerSourceTest {

    private fun fakeProvider(flow: kotlinx.coroutines.flow.Flow<Int>): BatteryLevelProvider {
        val provider = mockk<BatteryLevelProvider>()
        every { provider.batteryLevelPercent } returns flow
        return provider
    }

    // -----------------------------------------------------------------------
    // triggerId
    // -----------------------------------------------------------------------

    @Test
    fun `triggerId is battery_level`() {
        val source = BatteryTriggerSource(fakeProvider(flowOf()))
        assertEquals(BatteryTriggerSource.TRIGGER_ID, source.triggerId)
        assertEquals("battery_level", source.triggerId)
    }

    // -----------------------------------------------------------------------
    // Valid levels emitted as BatteryChanged
    // -----------------------------------------------------------------------

    @Test
    fun `start emits BatteryChanged for each valid level`() = runTest(UnconfinedTestDispatcher()) {
        val provider = fakeProvider(flowOf(50, 20, 100))
        val source = BatteryTriggerSource(provider)

        val received = mutableListOf<SystemEvent>()
        val scope = TestScope(UnconfinedTestDispatcher())
        source.start(scope) { received += it }
        scope.advanceUntilIdle()

        assertEquals(3, received.size)
        assertEquals(SystemEvent.BatteryChanged(50), received[0])
        assertEquals(SystemEvent.BatteryChanged(20), received[1])
        assertEquals(SystemEvent.BatteryChanged(100), received[2])
    }

    @Test
    fun `start emits BatteryChanged with correct level value`() = runTest(UnconfinedTestDispatcher()) {
        val stateFlow = MutableStateFlow(80)
        val provider = fakeProvider(stateFlow)
        val source = BatteryTriggerSource(provider)

        val received = mutableListOf<SystemEvent>()
        val scope = TestScope(UnconfinedTestDispatcher())
        source.start(scope) { received += it }
        scope.advanceUntilIdle()

        // initial value 80 arrives
        assertTrue(received.any { it == SystemEvent.BatteryChanged(80) })
    }

    @Test
    fun `start emits BatteryChanged for boundary levels 0 and 100`() = runTest(UnconfinedTestDispatcher()) {
        val provider = fakeProvider(flowOf(0, 100))
        val source = BatteryTriggerSource(provider)

        val received = mutableListOf<SystemEvent>()
        val scope = TestScope(UnconfinedTestDispatcher())
        source.start(scope) { received += it }
        scope.advanceUntilIdle()

        assertEquals(SystemEvent.BatteryChanged(0), received[0])
        assertEquals(SystemEvent.BatteryChanged(100), received[1])
    }

    // -----------------------------------------------------------------------
    // Invalid level (-1) is filtered
    // -----------------------------------------------------------------------

    @Test
    fun `invalid level -1 is filtered and does not emit BatteryChanged`() = runTest(UnconfinedTestDispatcher()) {
        // Production code: if (percent in 0..100) emit(...)
        // -1 is not in 0..100, so it must be dropped.
        val provider = fakeProvider(flowOf(-1))
        val source = BatteryTriggerSource(provider)

        val received = mutableListOf<SystemEvent>()
        val scope = TestScope(UnconfinedTestDispatcher())
        source.start(scope) { received += it }
        scope.advanceUntilIdle()

        assertTrue("Expected no events for level -1, got: $received", received.isEmpty())
    }

    @Test
    fun `mix of valid and invalid levels — only valid ones emitted`() = runTest(UnconfinedTestDispatcher()) {
        // -1 and 101 are both out of 0..100
        val provider = fakeProvider(flowOf(-1, 42, 101, 55))
        val source = BatteryTriggerSource(provider)

        val received = mutableListOf<SystemEvent>()
        val scope = TestScope(UnconfinedTestDispatcher())
        source.start(scope) { received += it }
        scope.advanceUntilIdle()

        assertEquals(2, received.size)
        assertEquals(SystemEvent.BatteryChanged(42), received[0])
        assertEquals(SystemEvent.BatteryChanged(55), received[1])
    }

    // -----------------------------------------------------------------------
    // stop() cancels collection
    // -----------------------------------------------------------------------

    @Test
    fun `stop cancels collection so further emissions are not received`() = runTest(UnconfinedTestDispatcher()) {
        val stateFlow = MutableStateFlow(50)
        val provider = fakeProvider(stateFlow)
        val source = BatteryTriggerSource(provider)

        val received = mutableListOf<SystemEvent>()
        val scope = TestScope(UnconfinedTestDispatcher())
        source.start(scope) { received += it }
        scope.advanceUntilIdle() // drains initial 50

        source.stop()

        stateFlow.value = 30
        scope.advanceUntilIdle()

        // Only the initial 50 should have arrived; 30 must not.
        assertTrue(received.none { it == SystemEvent.BatteryChanged(30) })
    }

    // -----------------------------------------------------------------------
    // Idempotent start
    // -----------------------------------------------------------------------

    @Test
    fun `calling start twice does not create a second collection job`() = runTest(UnconfinedTestDispatcher()) {
        // Production code guard: if (job?.isActive == true) return
        // Use MutableStateFlow so the first job stays active (open subscription) when
        // the second start() is called — flowOf() would complete immediately.
        val stateFlow = MutableStateFlow(60)
        val provider = fakeProvider(stateFlow)
        val source = BatteryTriggerSource(provider)

        val received = mutableListOf<SystemEvent>()
        val scope = TestScope(UnconfinedTestDispatcher())

        source.start(scope) { received += it }
        // The first job is still active (StateFlow never completes), so the second
        // start() must be a no-op per the idempotency guard.
        source.start(scope) { received += it }
        scope.advanceUntilIdle()

        // If idempotency works, 60 appears exactly once (from one collector, not two).
        assertEquals(1, received.size)
    }
}
