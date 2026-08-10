package at.resch.routines.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smoke-Test (PHASE 1): JSON Round-Trip MacroScript <-> String.
 * Sichert insbesondere ab, dass der polymorphe [Trigger] über den
 * `"type"`-Diskriminator korrekt serialisiert/deserialisiert (keine
 * Diskriminator-Kollision mit einer `type`-Property).
 */
class MacroScriptSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `on_startup macro round-trips`() {
        val original = MacroScript(
            id = "macro_001",
            name = "Start-Routine",
            enabled = true,
            trigger = Trigger.OnStartup,
            actions = listOf(
                Action(type = "log", params = mapOf("message" to "Gerät gestartet"))
            )
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<MacroScript>(encoded)

        assertEquals(original, decoded)
        // Diskriminator muss exakt "type" sein (Schema-Vertrag).
        assertTrue(
            "trigger must serialize with type discriminator, was: $encoded",
            encoded.contains("\"type\":\"on_startup\"")
        )
    }

    @Test
    fun `sim_card_data_connected macro round-trips`() {
        val original = MacroScript(
            id = "macro_002",
            name = "Mobile-Daten-Routine",
            trigger = Trigger.SimCardDataConnected,
            actions = emptyList()
        )

        val decoded = json.decodeFromString<MacroScript>(json.encodeToString(original))

        assertEquals(original, decoded)
        assertEquals(true, decoded.enabled) // Default greift
    }

    @Test
    fun `decodes a hand-written JSON string`() {
        val raw = """
            {
              "id": "macro_003",
              "name": "Hand-getippt",
              "trigger": { "type": "on_startup" },
              "actions": [
                { "type": "execute_shell_script", "params": { "command": "svc wifi enable" } }
              ]
            }
        """.trimIndent()

        val decoded = json.decodeFromString<MacroScript>(raw)

        assertEquals("macro_003", decoded.id)
        assertEquals(Trigger.OnStartup, decoded.trigger)
        assertEquals("execute_shell_script", decoded.actions.single().type)
        assertEquals("svc wifi enable", decoded.actions.single().params["command"])
    }
}
