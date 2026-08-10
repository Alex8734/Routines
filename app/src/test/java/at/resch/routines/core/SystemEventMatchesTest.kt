package at.resch.routines.core

import at.resch.routines.domain.model.Trigger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SystemEvent.matches] — the threshold/mode logic living on the event
 * itself (Open/Closed: evaluator stays generic).
 *
 * Coverage:
 * - [SystemEvent.BatteryChanged] vs [Trigger.BatteryLevel] — below/above/boundary/unknown mode
 * - [SystemEvent.BatteryChanged] vs wrong trigger type → false
 * - [SystemEvent.TimeTick] matches [Trigger.TimeSchedule] but not other triggers
 */
class SystemEventMatchesTest {

    // -----------------------------------------------------------------------
    // BatteryChanged — mode "below" (STRICT: boundary == does NOT match)
    // -----------------------------------------------------------------------

    @Test
    fun `BatteryChanged below trigger — level strictly less than threshold matches`() {
        val event = SystemEvent.BatteryChanged(level = 19)
        val trigger = Trigger.BatteryLevel(level = 20, mode = Trigger.BatteryLevel.MODE_BELOW)
        assertTrue(event.matches(trigger))
    }

    @Test
    fun `BatteryChanged below trigger — level equal to threshold does NOT match`() {
        val event = SystemEvent.BatteryChanged(level = 20)
        val trigger = Trigger.BatteryLevel(level = 20, mode = Trigger.BatteryLevel.MODE_BELOW)
        // Strict: level < trigger.level, so == must return false
        assertFalse(event.matches(trigger))
    }

    @Test
    fun `BatteryChanged below trigger — level above threshold does NOT match`() {
        val event = SystemEvent.BatteryChanged(level = 21)
        val trigger = Trigger.BatteryLevel(level = 20, mode = Trigger.BatteryLevel.MODE_BELOW)
        assertFalse(event.matches(trigger))
    }

    // -----------------------------------------------------------------------
    // BatteryChanged — mode "above" (STRICT: boundary == does NOT match)
    // -----------------------------------------------------------------------

    @Test
    fun `BatteryChanged above trigger — level strictly greater than threshold matches`() {
        val event = SystemEvent.BatteryChanged(level = 81)
        val trigger = Trigger.BatteryLevel(level = 80, mode = Trigger.BatteryLevel.MODE_ABOVE)
        assertTrue(event.matches(trigger))
    }

    @Test
    fun `BatteryChanged above trigger — level equal to threshold does NOT match`() {
        val event = SystemEvent.BatteryChanged(level = 80)
        val trigger = Trigger.BatteryLevel(level = 80, mode = Trigger.BatteryLevel.MODE_ABOVE)
        // Strict: level > trigger.level, so == must return false
        assertFalse(event.matches(trigger))
    }

    @Test
    fun `BatteryChanged above trigger — level below threshold does NOT match`() {
        val event = SystemEvent.BatteryChanged(level = 79)
        val trigger = Trigger.BatteryLevel(level = 80, mode = Trigger.BatteryLevel.MODE_ABOVE)
        assertFalse(event.matches(trigger))
    }

    // -----------------------------------------------------------------------
    // BatteryChanged — unknown mode → false
    // -----------------------------------------------------------------------

    @Test
    fun `BatteryChanged unknown mode returns false`() {
        val event = SystemEvent.BatteryChanged(level = 50)
        val trigger = Trigger.BatteryLevel(level = 50, mode = "equal")
        assertFalse(event.matches(trigger))
    }

    @Test
    fun `BatteryChanged empty string mode returns false`() {
        val event = SystemEvent.BatteryChanged(level = 30)
        val trigger = Trigger.BatteryLevel(level = 10, mode = "")
        assertFalse(event.matches(trigger))
    }

    // -----------------------------------------------------------------------
    // BatteryChanged — wrong trigger type → false
    // -----------------------------------------------------------------------

    @Test
    fun `BatteryChanged does not match OnStartup trigger`() {
        val event = SystemEvent.BatteryChanged(level = 50)
        assertFalse(event.matches(Trigger.OnStartup))
    }

    @Test
    fun `BatteryChanged does not match SimCardDataConnected trigger`() {
        val event = SystemEvent.BatteryChanged(level = 50)
        assertFalse(event.matches(Trigger.SimCardDataConnected))
    }

    @Test
    fun `BatteryChanged does not match TimeSchedule trigger`() {
        val event = SystemEvent.BatteryChanged(level = 50)
        assertFalse(event.matches(Trigger.TimeSchedule(intervalMinutes = 15)))
    }

    // -----------------------------------------------------------------------
    // TimeTick — matches TimeSchedule, not other triggers
    // -----------------------------------------------------------------------

    @Test
    fun `TimeTick matches TimeSchedule trigger`() {
        assertTrue(SystemEvent.TimeTick.matches(Trigger.TimeSchedule(intervalMinutes = 15)))
    }

    @Test
    fun `TimeTick matches TimeSchedule regardless of intervalMinutes value`() {
        assertTrue(SystemEvent.TimeTick.matches(Trigger.TimeSchedule(intervalMinutes = 1)))
        assertTrue(SystemEvent.TimeTick.matches(Trigger.TimeSchedule(intervalMinutes = 60)))
    }

    @Test
    fun `TimeTick does not match OnStartup trigger`() {
        assertFalse(SystemEvent.TimeTick.matches(Trigger.OnStartup))
    }

    @Test
    fun `TimeTick does not match SimCardDataConnected trigger`() {
        assertFalse(SystemEvent.TimeTick.matches(Trigger.SimCardDataConnected))
    }

    @Test
    fun `TimeTick does not match BatteryLevel trigger`() {
        assertFalse(SystemEvent.TimeTick.matches(Trigger.BatteryLevel(level = 20)))
    }

    // -----------------------------------------------------------------------
    // IntervalTick — matches Trigger Interval with the exact same intervalSeconds
    // -----------------------------------------------------------------------

    @Test
    fun `IntervalTick matches Trigger Interval with the same intervalSeconds`() {
        val event = SystemEvent.IntervalTick(intervalSeconds = 60)
        val trigger = Trigger.Interval(intervalSeconds = 60)
        assertTrue(event.matches(trigger))
    }

    @Test
    fun `IntervalTick matches Trigger Interval with the same intervalSeconds regardless of runOnStart`() {
        val event = SystemEvent.IntervalTick(intervalSeconds = 60)
        val trigger = Trigger.Interval(intervalSeconds = 60, runOnStart = true)
        assertTrue(event.matches(trigger))
    }

    @Test
    fun `IntervalTick does not match Trigger Interval with a different intervalSeconds`() {
        val event = SystemEvent.IntervalTick(intervalSeconds = 60)
        val trigger = Trigger.Interval(intervalSeconds = 30)
        assertFalse(event.matches(trigger))
    }

    @Test
    fun `IntervalTick does not match TimeSchedule trigger`() {
        val event = SystemEvent.IntervalTick(intervalSeconds = 60)
        assertFalse(event.matches(Trigger.TimeSchedule(intervalMinutes = 1)))
    }

    @Test
    fun `IntervalTick does not match OnStartup trigger`() {
        assertFalse(SystemEvent.IntervalTick(intervalSeconds = 60).matches(Trigger.OnStartup))
    }

    @Test
    fun `IntervalTick does not match BatteryLevel trigger`() {
        assertFalse(SystemEvent.IntervalTick(intervalSeconds = 60).matches(Trigger.BatteryLevel(level = 20)))
    }

    @Test
    fun `TimeTick does not match Trigger Interval`() {
        assertFalse(SystemEvent.TimeTick.matches(Trigger.Interval(intervalSeconds = 60)))
    }

    @Test
    fun `TimeSchedule tick source event does not accidentally match an Interval trigger via TimeTick`() {
        // Defensive cross-check: TimeTick must never match Trigger Interval, even when
        // the values would coincidentally look related.
        assertFalse(SystemEvent.TimeTick.matches(Trigger.Interval(intervalSeconds = 15)))
    }

    @Test
    fun `Startup does not match Trigger Interval`() {
        assertFalse(SystemEvent.Startup.matches(Trigger.Interval(intervalSeconds = 60)))
    }

    @Test
    fun `BatteryChanged does not match Trigger Interval`() {
        assertFalse(SystemEvent.BatteryChanged(level = 50).matches(Trigger.Interval(intervalSeconds = 60)))
    }

    // -----------------------------------------------------------------------
    // Startup event — sanity check
    // -----------------------------------------------------------------------

    @Test
    fun `Startup matches OnStartup trigger`() {
        assertTrue(SystemEvent.Startup.matches(Trigger.OnStartup))
    }

    @Test
    fun `Startup does not match TimeSchedule trigger`() {
        assertFalse(SystemEvent.Startup.matches(Trigger.TimeSchedule(intervalMinutes = 30)))
    }

    @Test
    fun `Startup does not match BatteryLevel trigger`() {
        assertFalse(SystemEvent.Startup.matches(Trigger.BatteryLevel(level = 50)))
    }
}
