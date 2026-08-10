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
    fun `interval macro with http_request action round-trips`() {
        val original = MacroScript(
            id = "macro_interval_001",
            name = "REST-Polling",
            trigger = Trigger.Interval(intervalSeconds = 60, runOnStart = true),
            actions = listOf(
                Action(
                    type = "http_request",
                    params = mapOf("url" to "https://api.example.com/poll", "method" to "GET")
                )
            )
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<MacroScript>(encoded)

        assertEquals(original, decoded)
        assertTrue(
            "trigger must serialize with type discriminator 'interval', was: $encoded",
            encoded.contains("\"type\":\"interval\"")
        )
        val decodedTrigger = decoded.trigger as Trigger.Interval
        assertEquals(60, decodedTrigger.intervalSeconds)
        assertTrue(decodedTrigger.runOnStart)
    }

    @Test
    fun `interval trigger JSON without runOnStart defaults to false`() {
        val raw = """
            {
              "id": "macro_interval_002",
              "name": "Ohne runOnStart",
              "trigger": { "type": "interval", "intervalSeconds": 60 },
              "actions": []
            }
        """.trimIndent()

        val decoded = json.decodeFromString<MacroScript>(raw)

        val trigger = decoded.trigger as Trigger.Interval
        assertEquals(60, trigger.intervalSeconds)
        assertEquals(false, trigger.runOnStart)
    }

    @Test
    fun `decodes a realistic REST polling macro JSON with headers and body`() {
        val raw = """
            {
              "id": "macro_rest_poll",
              "name": "Poll Webhook",
              "enabled": true,
              "trigger": { "type": "interval", "intervalSeconds": 120, "runOnStart": true },
              "actions": [
                {
                  "type": "http_request",
                  "params": {
                    "url": "https://api.example.com/status",
                    "method": "POST",
                    "headers": "Content-Type: application/json\nAuthorization: Bearer abc123",
                    "body": "{\"ping\":true}",
                    "timeoutSeconds": "10"
                  }
                }
              ]
            }
        """.trimIndent()

        val decoded = json.decodeFromString<MacroScript>(raw)

        assertEquals("macro_rest_poll", decoded.id)
        assertEquals(Trigger.Interval(intervalSeconds = 120, runOnStart = true), decoded.trigger)
        val action = decoded.actions.single()
        assertEquals("http_request", action.type)
        assertEquals("https://api.example.com/status", action.params["url"])
        assertEquals("POST", action.params["method"])
        assertEquals(
            "Content-Type: application/json\nAuthorization: Bearer abc123",
            action.params["headers"]
        )
        assertEquals("{\"ping\":true}", action.params["body"])
        assertEquals("10", action.params["timeoutSeconds"])
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
