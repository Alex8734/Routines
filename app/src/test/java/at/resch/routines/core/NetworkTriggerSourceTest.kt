package at.resch.routines.core

import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [NetworkTriggerSource].
 *
 * [NetworkStatusTracker] is mocked so no Android context is required.
 * The tracker's [NetworkStatusTracker.isCellularConnected] property is backed by a
 * [MutableStateFlow]/[flowOf] under test control.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkTriggerSourceTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun fakeTracker(flow: kotlinx.coroutines.flow.Flow<Boolean>): NetworkStatusTracker {
        val tracker = mockk<NetworkStatusTracker>()
        every { tracker.isCellularConnected } returns flow
        return tracker
    }

    // -----------------------------------------------------------------------
    // Emits CellularDataConnected only on transition to true
    // -----------------------------------------------------------------------

    @Test
    fun `start emits CellularDataConnected when isCellularConnected becomes true`() =
        runTest(UnconfinedTestDispatcher()) {
            val stateFlow = MutableStateFlow(false)
            val tracker = fakeTracker(stateFlow)
            val source = NetworkTriggerSource(tracker)

            val received = mutableListOf<SystemEvent>()
            val scope = TestScope(UnconfinedTestDispatcher())
            source.start(scope) { received += it }

            stateFlow.value = true
            scope.advanceUntilIdle()

            assertEquals(1, received.size)
            assertEquals(SystemEvent.CellularDataConnected, received.single())
        }

    @Test
    fun `start does not emit when isCellularConnected stays false`() =
        runTest(UnconfinedTestDispatcher()) {
            val stateFlow = MutableStateFlow(false)
            val tracker = fakeTracker(stateFlow)
            val source = NetworkTriggerSource(tracker)

            val received = mutableListOf<SystemEvent>()
            val scope = TestScope(UnconfinedTestDispatcher())
            source.start(scope) { received += it }

            // Emit another false — should not trigger.
            stateFlow.value = false
            scope.advanceUntilIdle()

            assertEquals(0, received.size)
        }

    @Test
    fun `start does not emit when isCellularConnected transitions from true to false`() =
        runTest(UnconfinedTestDispatcher()) {
            // Start with true, then flip to false.
            val stateFlow = MutableStateFlow(true)
            val tracker = fakeTracker(stateFlow)
            val source = NetworkTriggerSource(tracker)

            val received = mutableListOf<SystemEvent>()
            val scope = TestScope(UnconfinedTestDispatcher())
            // Consume the initial true via start — that itself would emit once.
            source.start(scope) { received += it }
            scope.advanceUntilIdle()

            val countAfterStart = received.size // may be 1 due to initial true

            stateFlow.value = false
            scope.advanceUntilIdle()

            // No additional events after dropping to false.
            assertEquals(countAfterStart, received.size)
        }

    @Test
    fun `emits CellularDataConnected for each true transition in sequence`() =
        runTest(UnconfinedTestDispatcher()) {
            val stateFlow = MutableStateFlow(false)
            val tracker = fakeTracker(stateFlow)
            val source = NetworkTriggerSource(tracker)

            val received = mutableListOf<SystemEvent>()
            val scope = TestScope(UnconfinedTestDispatcher())
            source.start(scope) { received += it }

            stateFlow.value = true   // transition: false→true  → emit
            scope.advanceUntilIdle()
            stateFlow.value = false  // transition: true→false  → no emit
            scope.advanceUntilIdle()
            stateFlow.value = true   // transition: false→true  → emit
            scope.advanceUntilIdle()

            assertEquals(2, received.size)
            received.forEach { assertEquals(SystemEvent.CellularDataConnected, it) }
        }

    // -----------------------------------------------------------------------
    // stop() cancels collection
    // -----------------------------------------------------------------------

    @Test
    fun `stop cancels collection so no further events are emitted`() =
        runTest(UnconfinedTestDispatcher()) {
            val stateFlow = MutableStateFlow(false)
            val tracker = fakeTracker(stateFlow)
            val source = NetworkTriggerSource(tracker)

            val received = mutableListOf<SystemEvent>()
            val scope = TestScope(UnconfinedTestDispatcher())
            source.start(scope) { received += it }

            // Emit one event, then stop.
            stateFlow.value = true
            scope.advanceUntilIdle()
            source.stop()

            val countAfterStop = received.size

            // Further state changes after stop must not arrive.
            stateFlow.value = false
            stateFlow.value = true
            scope.advanceUntilIdle()

            assertEquals(countAfterStop, received.size)
        }

    // -----------------------------------------------------------------------
    // triggerId
    // -----------------------------------------------------------------------

    @Test
    fun `triggerId matches NetworkStatusTracker TRIGGER_ID`() {
        val tracker = fakeTracker(flowOf())
        val source = NetworkTriggerSource(tracker)
        assertEquals(NetworkStatusTracker.TRIGGER_ID, source.triggerId)
    }
}
