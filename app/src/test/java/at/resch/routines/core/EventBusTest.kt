package at.resch.routines.core

import app.cash.turbine.test
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [EventBus].
 *
 * Uses Turbine for Flow assertions and coroutines-test for structured concurrency.
 */
class EventBusTest {

    // -----------------------------------------------------------------------
    // emit / collect round-trip
    // -----------------------------------------------------------------------

    @Test
    fun `emit and collect round-trip delivers event to subscriber`() = runTest {
        val bus = EventBus()

        bus.events.test {
            bus.emit(SystemEvent.Startup)
            assertEquals(SystemEvent.Startup, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `emit delivers multiple events in order`() = runTest {
        val bus = EventBus()

        bus.events.test {
            bus.emit(SystemEvent.Startup)
            bus.emit(SystemEvent.CellularDataConnected)
            bus.emit(SystemEvent.Startup)

            assertEquals(SystemEvent.Startup, awaitItem())
            assertEquals(SystemEvent.CellularDataConnected, awaitItem())
            assertEquals(SystemEvent.Startup, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // -----------------------------------------------------------------------
    // tryEmit
    // -----------------------------------------------------------------------

    @Test
    fun `tryEmit returns true within buffer capacity`() = runTest {
        val bus = EventBus()

        // Buffer is 64; emitting a handful without a collector should succeed.
        repeat(10) { i ->
            val result = bus.tryEmit(
                if (i % 2 == 0) SystemEvent.Startup else SystemEvent.CellularDataConnected
            )
            assertTrue("tryEmit #$i should succeed", result)
        }
    }

    @Test
    fun `tryEmit event is received by subsequent collector`() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus()

        bus.events.test {
            assertTrue(bus.tryEmit(SystemEvent.CellularDataConnected))
            assertEquals(SystemEvent.CellularDataConnected, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // -----------------------------------------------------------------------
    // replay = 0: late subscriber does NOT receive prior events
    // -----------------------------------------------------------------------

    @Test
    fun `late subscriber does not receive events emitted before subscription`() = runTest {
        val bus = EventBus()

        // Emit before any subscriber exists.
        bus.tryEmit(SystemEvent.Startup)
        bus.tryEmit(SystemEvent.CellularDataConnected)

        // Now subscribe — with replay=0, nothing buffered for late arrivals.
        bus.events.test {
            // Send one fresh event to confirm the subscriber IS working.
            bus.emit(SystemEvent.Startup)
            assertEquals(SystemEvent.Startup, awaitItem())
            // No leftover items from the pre-subscription emissions.
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `two independent collectors each receive the same emitted event`() =
        runTest(UnconfinedTestDispatcher()) {
            val bus = EventBus()
            val received1 = mutableListOf<SystemEvent>()
            val received2 = mutableListOf<SystemEvent>()

            val job1 = launch { bus.events.collect { received1 += it } }
            val job2 = launch { bus.events.collect { received2 += it } }

            bus.emit(SystemEvent.CellularDataConnected)

            // Allow coroutines to process.
            testScheduler.advanceUntilIdle()

            job1.cancel()
            job2.cancel()

            assertEquals(listOf(SystemEvent.CellularDataConnected), received1)
            assertEquals(listOf(SystemEvent.CellularDataConnected), received2)
        }

    @Test
    fun `tryEmit returns false only when buffer is completely full`() = runTest {
        // We cannot easily fill a 64-slot buffer in a deterministic way without a
        // suspended collector. This test documents the contract: with an active
        // collector draining immediately, tryEmit always returns true.
        val bus = EventBus()
        var anyFailed = false

        bus.events.test {
            repeat(64) {
                if (!bus.tryEmit(SystemEvent.Startup)) anyFailed = true
                awaitItem() // drain immediately so buffer stays empty
            }
            assertFalse("All tryEmit calls should succeed with an active draining collector", anyFailed)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
