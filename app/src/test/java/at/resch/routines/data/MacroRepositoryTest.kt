package at.resch.routines.data

import app.cash.turbine.test
import at.resch.routines.domain.model.Action
import at.resch.routines.domain.model.MacroScript
import at.resch.routines.domain.model.Trigger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [MacroRepository] with a mocked [MacroDao].
 */
class MacroRepositoryTest {

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private val sampleMacro = MacroScript(
        id = "m1",
        name = "Test",
        enabled = true,
        trigger = Trigger.OnStartup,
        actions = listOf(Action("log", mapOf("message" to "hello")))
    )

    private val sampleJson = MacroJson.encodeToString(sampleMacro)

    private val sampleEntity = MacroEntity(macroId = "m1", configuration = sampleJson)

    // -----------------------------------------------------------------------
    // observeAll() — maps entities to MacroScript and drops unparseable rows
    // -----------------------------------------------------------------------

    @Test
    fun `observeAll maps valid entities to MacroScript list`() = runTest {
        val dao = mockk<MacroDao>()
        coEvery { dao.observeAll() } returns flowOf(listOf(sampleEntity))
        val repo = MacroRepository(dao)

        repo.observeAll().test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals(sampleMacro, list.single())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeAll silently drops unparseable rows`() = runTest {
        val brokenEntity = MacroEntity(macroId = "broken", configuration = "not-json{{{")
        val dao = mockk<MacroDao>()
        coEvery { dao.observeAll() } returns flowOf(listOf(brokenEntity, sampleEntity))
        val repo = MacroRepository(dao)

        repo.observeAll().test {
            val list = awaitItem()
            // Broken row is dropped; only the valid one survives.
            assertEquals(1, list.size)
            assertEquals(sampleMacro, list.single())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeAll returns empty list when all entities are unparseable`() = runTest {
        val dao = mockk<MacroDao>()
        coEvery { dao.observeAll() } returns flowOf(
            listOf(
                MacroEntity("a", "{}"),
                MacroEntity("b", "")
            )
        )
        val repo = MacroRepository(dao)

        repo.observeAll().test {
            val list = awaitItem()
            assertEquals(0, list.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeAll returns empty list when DAO emits empty list`() = runTest {
        val dao = mockk<MacroDao>()
        coEvery { dao.observeAll() } returns flowOf(emptyList())
        val repo = MacroRepository(dao)

        repo.observeAll().test {
            assertEquals(0, awaitItem().size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // -----------------------------------------------------------------------
    // save() — serializes via MacroJson
    // -----------------------------------------------------------------------

    @Test
    fun `save serializes macro to JSON and upserts it via DAO`() = runTest {
        val dao = mockk<MacroDao>()
        val slot = slot<MacroEntity>()
        coEvery { dao.upsert(capture(slot)) } returns Unit
        val repo = MacroRepository(dao)

        repo.save(sampleMacro)

        coVerify(exactly = 1) { dao.upsert(any()) }
        assertEquals("m1", slot.captured.macroId)
        // Round-trip: the captured JSON must parse back to the original macro.
        val roundTripped = MacroJson.decodeFromString<MacroScript>(slot.captured.configuration)
        assertEquals(sampleMacro, roundTripped)
    }

    // -----------------------------------------------------------------------
    // saveRaw() — passes JSON through unchanged
    // -----------------------------------------------------------------------

    @Test
    fun `saveRaw passes raw JSON string through to DAO unchanged`() = runTest {
        val dao = mockk<MacroDao>()
        val slot = slot<MacroEntity>()
        coEvery { dao.upsert(capture(slot)) } returns Unit
        val repo = MacroRepository(dao)

        val rawJson = """{"id":"raw","name":"raw","trigger":{"type":"on_startup"},"actions":[]}"""
        repo.saveRaw("raw", rawJson)

        coVerify(exactly = 1) { dao.upsert(any()) }
        assertEquals("raw", slot.captured.macroId)
        assertEquals(rawJson, slot.captured.configuration)
    }

    // -----------------------------------------------------------------------
    // getById() — returns null on bad JSON or missing entity
    // -----------------------------------------------------------------------

    @Test
    fun `getById returns parsed MacroScript for valid entity`() = runTest {
        val dao = mockk<MacroDao>()
        coEvery { dao.getById("m1") } returns sampleEntity
        val repo = MacroRepository(dao)

        val result = repo.getById("m1")
        assertEquals(sampleMacro, result)
    }

    @Test
    fun `getById returns null when DAO returns null (not found)`() = runTest {
        val dao = mockk<MacroDao>()
        coEvery { dao.getById("missing") } returns null
        val repo = MacroRepository(dao)

        assertNull(repo.getById("missing"))
    }

    @Test
    fun `getById returns null when entity has invalid JSON`() = runTest {
        val dao = mockk<MacroDao>()
        coEvery { dao.getById("bad") } returns MacroEntity("bad", "not-json")
        val repo = MacroRepository(dao)

        assertNull(repo.getById("bad"))
    }

    // -----------------------------------------------------------------------
    // parseOrNull()
    // -----------------------------------------------------------------------

    @Test
    fun `parseOrNull returns MacroScript for valid JSON`() {
        val dao = mockk<MacroDao>()
        val repo = MacroRepository(dao)
        assertEquals(sampleMacro, repo.parseOrNull(sampleJson))
    }

    @Test
    fun `parseOrNull returns null for invalid JSON`() {
        val dao = mockk<MacroDao>()
        val repo = MacroRepository(dao)
        assertNull(repo.parseOrNull("garbage"))
    }

    @Test
    fun `parseOrNull returns null for empty string`() {
        val dao = mockk<MacroDao>()
        val repo = MacroRepository(dao)
        assertNull(repo.parseOrNull(""))
    }
}
