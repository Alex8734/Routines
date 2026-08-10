package at.resch.routines.data

import at.resch.routines.domain.model.Action
import at.resch.routines.domain.model.MacroScript
import at.resch.routines.domain.model.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MacroJsonPretty].
 *
 * Verifies that:
 * 1. Output contains newlines and 2-space indentation (pretty-print behaviour).
 * 2. Output is valid JSON that round-trips through the compact [MacroJson].
 * 3. Both serializers produce semantically identical [MacroScript] objects.
 */
class MacroJsonPrettyTest {

    private val sampleMacro = MacroScript(
        id = "pretty_001",
        name = "Pretty-Print-Test",
        enabled = true,
        trigger = Trigger.BatteryLevel(level = 20, mode = Trigger.BatteryLevel.MODE_BELOW),
        actions = listOf(
            Action(type = "show_notification", params = mapOf("title" to "Low Battery", "message" to "Charge now"))
        )
    )

    // -----------------------------------------------------------------------
    // Pretty-print format checks
    // -----------------------------------------------------------------------

    @Test
    fun `MacroJsonPretty output contains newlines`() {
        val json = MacroJsonPretty.encodeToString(sampleMacro)
        assertTrue("Expected newlines in pretty output, got: $json", json.contains("\n"))
    }

    @Test
    fun `MacroJsonPretty output contains two-space indentation`() {
        val json = MacroJsonPretty.encodeToString(sampleMacro)
        assertTrue("Expected 2-space indent in pretty output, got: $json", json.contains("  "))
    }

    @Test
    fun `MacroJsonPretty output is multi-line`() {
        val json = MacroJsonPretty.encodeToString(sampleMacro)
        val lineCount = json.lines().size
        assertTrue("Expected multiple lines, got $lineCount line(s)", lineCount > 1)
    }

    // -----------------------------------------------------------------------
    // Round-trip: pretty → compact decoder → same MacroScript
    // -----------------------------------------------------------------------

    @Test
    fun `pretty output round-trips via compact MacroJson decoder`() {
        val prettyJson = MacroJsonPretty.encodeToString(sampleMacro)
        val decoded = MacroJson.decodeFromString<MacroScript>(prettyJson)
        assertEquals(sampleMacro, decoded)
    }

    @Test
    fun `pretty output is valid JSON parseable by MacroJson`() {
        val prettyJson = MacroJsonPretty.encodeToString(sampleMacro)
        // If this throws, the test fails — proving the output is invalid JSON.
        val decoded = MacroJson.decodeFromString<MacroScript>(prettyJson)
        assertEquals("pretty_001", decoded.id)
        assertEquals("Pretty-Print-Test", decoded.name)
    }

    @Test
    fun `MacroJsonPretty round-trips MacroScript with TimeSchedule trigger`() {
        val macro = MacroScript(
            id = "sched_001",
            name = "Schedule Macro",
            trigger = Trigger.TimeSchedule(intervalMinutes = 30),
            actions = emptyList()
        )

        val prettyJson = MacroJsonPretty.encodeToString(macro)
        val decoded = MacroJson.decodeFromString<MacroScript>(prettyJson)

        assertEquals(macro, decoded)
        assertTrue(decoded.trigger is Trigger.TimeSchedule)
        assertEquals(30, (decoded.trigger as Trigger.TimeSchedule).intervalMinutes)
    }

    @Test
    fun `MacroJsonPretty and MacroJson produce semantically identical MacroScript`() {
        val prettyJson = MacroJsonPretty.encodeToString(sampleMacro)
        val compactJson = MacroJson.encodeToString(sampleMacro)

        val fromPretty = MacroJson.decodeFromString<MacroScript>(prettyJson)
        val fromCompact = MacroJson.decodeFromString<MacroScript>(compactJson)

        assertEquals(fromCompact, fromPretty)
    }

    // -----------------------------------------------------------------------
    // Type discriminator preserved in pretty output
    // -----------------------------------------------------------------------

    @Test
    fun `pretty output preserves type discriminator for trigger`() {
        val prettyJson = MacroJsonPretty.encodeToString(sampleMacro)
        assertTrue(
            "Expected type discriminator 'battery_level' in pretty output",
            prettyJson.contains("battery_level")
        )
    }

    @Test
    fun `pretty output preserves on_startup type discriminator`() {
        val macro = sampleMacro.copy(trigger = Trigger.OnStartup)
        val prettyJson = MacroJsonPretty.encodeToString(macro)
        assertTrue(prettyJson.contains("on_startup"))
    }
}
