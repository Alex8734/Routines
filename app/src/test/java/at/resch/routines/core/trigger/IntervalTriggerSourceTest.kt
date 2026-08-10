package at.resch.routines.core.trigger

import at.resch.routines.core.SystemEvent
import at.resch.routines.domain.model.Trigger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [IntervalTriggerSource].
 *
 * Two testing strategies are used, matching the concern at hand:
 *
 * 1. **Single-ticker ordering/value assertions** (matching the style of
 *    [TimeScheduleTriggerSourceTest] / [BatteryTriggerSourceTest]): a fake
 *    [DelayProvider] paired with [UnconfinedTestDispatcher] and a separate
 *    [TestScope] that is cancelled from inside the fake once enough calls have
 *    been observed. Only safe when exactly one ticker job is involved — the
 *    fake `wait()` never truly suspends, so with more than one concurrent
 *    ticker the first one launched would run to completion synchronously
 *    (Unconfined) before the second ever gets a chance to run.
 * 2. **Concurrency / reconcile assertions** (multiple tickers alive at once,
 *    or the needed-interval-set changing over time): the *real* default
 *    [DelayProvider] (`kotlinx.coroutines.delay`) combined with `runTest`'s
 *    virtual-time scheduler (`advanceTimeBy` / `runCurrent`). Real `delay()`
 *    genuinely suspends, so the scheduler can properly interleave multiple
 *    ticker coroutines and let the collector job react to new [intervals]
 *    values in between ticks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IntervalTriggerSourceTest {

    // -----------------------------------------------------------------------
    // triggerId
    // -----------------------------------------------------------------------

    @Test
    fun `triggerId is interval`() {
        val source = IntervalTriggerSource(intervals = flowOf(emptySet()))
        assertEquals(IntervalTriggerSource.TRIGGER_ID, source.triggerId)
        assertEquals("interval", source.triggerId)
    }

    // -----------------------------------------------------------------------
    // Single interval — repeated IntervalTick(n) with the correct value
    // -----------------------------------------------------------------------

    @Test
    fun `single interval in flow emits repeated IntervalTick events with the correct value`() =
        runTest(UnconfinedTestDispatcher()) {
            val received = mutableListOf<SystemEvent>()
            val scope = TestScope(UnconfinedTestDispatcher())

            var callCount = 0
            val delayProvider = DelayProvider { _ ->
                callCount++
                if (callCount >= 3) scope.cancel()
            }

            val source = IntervalTriggerSource(
                intervals = flowOf(setOf(Trigger.Interval(intervalSeconds = 45))),
                delayProvider = delayProvider
            )

            source.start(scope) { received += it }
            scope.advanceUntilIdle()

            assertTrue("Expected at least 2 IntervalTick events, got: $received", received.size >= 2)
            received.forEach { assertEquals(SystemEvent.IntervalTick(45), it) }
        }

    // -----------------------------------------------------------------------
    // runOnStart ordering
    // -----------------------------------------------------------------------

    @Test
    fun `runOnStart true emits an immediate tick before the first wait`() =
        runTest(UnconfinedTestDispatcher()) {
            val sequence = mutableListOf<String>()
            val scope = TestScope(UnconfinedTestDispatcher())
            val delayProvider = DelayProvider { _ ->
                sequence += "wait"
                scope.cancel()
            }

            val source = IntervalTriggerSource(
                intervals = flowOf(setOf(Trigger.Interval(intervalSeconds = 30, runOnStart = true))),
                delayProvider = delayProvider
            )

            source.start(scope) { event ->
                sequence += "tick:${(event as SystemEvent.IntervalTick).intervalSeconds}"
            }
            scope.advanceUntilIdle()

            // Per the documented cancellation semantics (see TimeScheduleTriggerSourceTest):
            // cancellation is cooperative and only checked at the top of the next loop
            // iteration, so the emit() that follows the cancelling wait() still runs.
            // What matters here is that the very FIRST event is the immediate runOnStart
            // tick, occurring strictly before the first "wait".
            assertEquals(listOf("tick:30", "wait", "tick:30"), sequence)
            assertEquals("tick:30", sequence.first())
        }

    @Test
    fun `runOnStart false emits the first tick only after waiting`() =
        runTest(UnconfinedTestDispatcher()) {
            val sequence = mutableListOf<String>()
            val scope = TestScope(UnconfinedTestDispatcher())
            val delayProvider = DelayProvider { _ ->
                sequence += "wait"
                scope.cancel()
            }

            val source = IntervalTriggerSource(
                intervals = flowOf(setOf(Trigger.Interval(intervalSeconds = 30, runOnStart = false))),
                delayProvider = delayProvider
            )

            source.start(scope) { event ->
                sequence += "tick:${(event as SystemEvent.IntervalTick).intervalSeconds}"
            }
            scope.advanceUntilIdle()

            assertEquals(listOf("wait", "tick:30"), sequence)
        }

    // -----------------------------------------------------------------------
    // Two concurrent intervals — each ticker carries its own value
    // -----------------------------------------------------------------------

    @Test
    fun `two different intervals run concurrently and each emits its own value`() = runTest {
        val received = mutableListOf<SystemEvent>()
        val source = IntervalTriggerSource(
            intervals = flowOf(
                setOf(
                    Trigger.Interval(intervalSeconds = 10),
                    Trigger.Interval(intervalSeconds = 20)
                )
            )
        )

        source.start(this) { received += it }
        // 21s of virtual time: the 10s ticker should have fired twice (10s, 20s),
        // the 20s ticker once (20s).
        advanceTimeBy(21_000)
        runCurrent()
        source.stop()

        val ticks10 = received.count { it == SystemEvent.IntervalTick(10) }
        val ticks20 = received.count { it == SystemEvent.IntervalTick(20) }
        assertTrue("Expected >= 2 ticks for interval 10, got $ticks10 ($received)", ticks10 >= 2)
        assertTrue("Expected >= 1 tick for interval 20, got $ticks20 ($received)", ticks20 >= 1)
        assertTrue(
            "Only IntervalTick(10)/IntervalTick(20) expected, got: $received",
            received.all { it == SystemEvent.IntervalTick(10) || it == SystemEvent.IntervalTick(20) }
        )
    }

    // -----------------------------------------------------------------------
    // Clamping — wait is clamped, the emitted/keyed value is not
    // -----------------------------------------------------------------------

    @Test
    fun `intervalSeconds below the minimum is clamped to 5000ms for the wait but the event carries the original value`() =
        runTest(UnconfinedTestDispatcher()) {
            val received = mutableListOf<SystemEvent>()
            val scope = TestScope(UnconfinedTestDispatcher())
            val capturedMillis = mutableListOf<Long>()
            val delayProvider = DelayProvider { millis ->
                capturedMillis += millis
                scope.cancel()
            }

            val source = IntervalTriggerSource(
                intervals = flowOf(setOf(Trigger.Interval(intervalSeconds = 1))),
                delayProvider = delayProvider
            )

            source.start(scope) { received += it }
            scope.advanceUntilIdle()

            assertEquals(listOf(5_000L), capturedMillis)
            assertEquals(listOf(SystemEvent.IntervalTick(1)), received)
        }

    // -----------------------------------------------------------------------
    // Reconcile — a value dropping out of the flow stops its ticker;
    // a new value starts one
    // -----------------------------------------------------------------------

    @Test
    fun `reconcile stops the ticker for a removed interval and starts one for a newly added interval`() =
        runTest {
            val received = mutableListOf<SystemEvent>()
            val configsFlow = MutableStateFlow(setOf(Trigger.Interval(intervalSeconds = 10)))
            val source = IntervalTriggerSource(intervals = configsFlow)

            source.start(this) { received += it }

            advanceTimeBy(10_000)
            runCurrent()
            assertTrue(
                "Expected a tick for interval 10 before the swap",
                received.any { it == SystemEvent.IntervalTick(10) }
            )

            // Swap: 10s interval falls out, 30s interval becomes needed.
            configsFlow.value = setOf(Trigger.Interval(intervalSeconds = 30))
            received.clear()

            // Advance well past where interval-10 would have ticked again (20s) and past
            // where the newly added interval-30 becomes due (30s after the swap).
            advanceTimeBy(31_000)
            runCurrent()
            source.stop()

            assertTrue(
                "interval-10 ticker must have been stopped, no further ticks expected: $received",
                received.none { it == SystemEvent.IntervalTick(10) }
            )
            assertTrue(
                "Expected a tick for the newly added interval 30: $received",
                received.any { it == SystemEvent.IntervalTick(30) }
            )
        }

    // -----------------------------------------------------------------------
    // stop() ends all tickers
    // -----------------------------------------------------------------------

    @Test
    fun `stop cancels the collector and all running tickers so no further emissions occur`() = runTest {
        val received = mutableListOf<SystemEvent>()
        val configsFlow = MutableStateFlow(
            setOf(Trigger.Interval(intervalSeconds = 10), Trigger.Interval(intervalSeconds = 20))
        )
        val source = IntervalTriggerSource(intervals = configsFlow)

        source.start(this) { received += it }
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue("Sanity check: expected at least one tick before stop()", received.isNotEmpty())

        source.stop()
        received.clear()

        advanceTimeBy(60_000)
        runCurrent()

        assertTrue("No further emissions expected after stop(), got: $received", received.isEmpty())
    }

    @Test
    fun `stop with no active job does not throw`() {
        val source = IntervalTriggerSource(intervals = flowOf(emptySet()))
        source.stop() // must not throw when collectorJob == null
    }

    @Test
    fun `stop is safe to call twice`() {
        val source = IntervalTriggerSource(intervals = flowOf(emptySet()))
        source.stop()
        source.stop() // second stop with everything already null/empty must not throw
    }

    // -----------------------------------------------------------------------
    // Idempotent start
    // -----------------------------------------------------------------------

    @Test
    fun `calling start twice is idempotent — no duplicate ticker for the same interval`() = runTest {
        // MutableStateFlow never completes, so the first collectorJob stays active when
        // the second start() call happens (mirrors BatteryTriggerSourceTest's pattern).
        val configsFlow = MutableStateFlow(setOf(Trigger.Interval(intervalSeconds = 10)))
        val received = mutableListOf<SystemEvent>()
        val source = IntervalTriggerSource(intervals = configsFlow)

        source.start(this) { received += it }
        source.start(this) { received += it } // must be a no-op — collectorJob still active

        advanceTimeBy(10_000)
        runCurrent()
        source.stop()

        // If idempotency held, exactly one tick fires from a single ticker (not two).
        assertEquals(1, received.count { it == SystemEvent.IntervalTick(10) })
    }
}
