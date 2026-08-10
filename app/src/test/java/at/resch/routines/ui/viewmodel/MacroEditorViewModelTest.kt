package at.resch.routines.ui.viewmodel

import at.resch.routines.data.MacroJson
import at.resch.routines.data.MacroRepository
import at.resch.routines.domain.model.Action
import at.resch.routines.domain.model.MacroScript
import at.resch.routines.domain.model.Trigger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit-Tests für [MacroEditorViewModel] (Phase 5 Verification Gate).
 *
 * Deckt die bidirektionale Form↔JSON-Synchronisation, die JSON-Validierung
 * (delegiert an [MacroRepository.parseOrNull]) und die beiden Speicherpfade ab.
 * `viewModelScope` läuft auf dem Main-Dispatcher → wird per [Dispatchers.setMain]
 * auf einen [StandardTestDispatcher] umgebogen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MacroEditorViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: MacroRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mockk(relaxed = true)
        // parseOrNull realistisch verdrahten: echtes MacroJson-Verhalten.
        every { repository.parseOrNull(any()) } answers {
            runCatching { MacroJson.decodeFromString<MacroScript>(firstArg<String>()) }.getOrNull()
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newVm(macroId: String? = null) = MacroEditorViewModel(repository, macroId)

    private fun macro(
        id: String = "m1",
        name: String = "Loaded",
        trigger: Trigger = Trigger.OnStartup,
        actions: List<Action> = emptyList()
    ) = MacroScript(id = id, name = name, enabled = true, trigger = trigger, actions = actions)

    @Test
    fun `new macro init - not loading, valid non-empty json, no error`() = runTest(dispatcher) {
        val vm = newVm(macroId = null)
        val state = vm.uiState.value

        assertFalse(state.isLoading)
        assertNull(state.jsonError)
        assertTrue("rawJson should be non-empty", state.rawJson.isNotBlank())
        // rawJson muss wieder parsbar sein
        assertNotNull(MacroJson.decodeFromString<MacroScript>(state.rawJson))
    }

    @Test
    fun `load existing macro - state reflects loaded values`() = runTest(dispatcher) {
        coEvery { repository.getById("x") } returns
            macro(id = "x", name = "Geladen", trigger = Trigger.SimCardDataConnected,
                actions = listOf(Action("log", mapOf("message" to "hi"))))

        val vm = newVm(macroId = "x")
        advanceUntilIdle() // init-Lade-Coroutine ausführen

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals("Geladen", state.name)
        assertEquals(Trigger.SimCardDataConnected, state.trigger)
        assertEquals(1, state.actions.size)
        assertEquals("log", state.actions.first().type)
    }

    @Test
    fun `load missing id - stops loading with empty form`() = runTest(dispatcher) {
        coEvery { repository.getById("missing") } returns null

        val vm = newVm(macroId = "missing")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals("", state.name)
    }

    @Test
    fun `onNameChange syncs into rawJson`() = runTest(dispatcher) {
        val vm = newVm()
        vm.onNameChange("MeinMakro")

        val state = vm.uiState.value
        assertEquals("MeinMakro", state.name)
        assertTrue(state.rawJson.contains("MeinMakro"))
        assertNull(state.jsonError)
    }

    @Test
    fun `onRawJsonChange with valid json updates form and clears error`() = runTest(dispatcher) {
        val vm = newVm()
        val json = MacroJson.encodeToString(
            macro(id = "z", name = "AusJson", trigger = Trigger.SimCardDataConnected)
        )

        vm.onRawJsonChange(json)

        val state = vm.uiState.value
        assertNull(state.jsonError)
        assertEquals("AusJson", state.name)
        assertEquals(Trigger.SimCardDataConnected, state.trigger)
    }

    @Test
    fun `onRawJsonChange with invalid json sets error and leaves form untouched`() = runTest(dispatcher) {
        val vm = newVm()
        vm.onNameChange("Original")
        val before = vm.uiState.value.name

        vm.onRawJsonChange("{kaputt}")

        val state = vm.uiState.value
        assertNotNull(state.jsonError)
        assertEquals(before, state.name) // Formular unverändert
        assertEquals("{kaputt}", state.rawJson)
    }

    @Test
    fun `saveFromForm persists via repository save and sets isSaved`() = runTest(dispatcher) {
        val vm = newVm()
        vm.onNameChange("Speicher")

        vm.saveFromForm()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.save(any()) }
        assertTrue(vm.uiState.value.isSaved)
    }

    @Test
    fun `saveFromJson with valid json persists raw via saveRaw`() = runTest(dispatcher) {
        val vm = newVm()
        val json = MacroJson.encodeToString(macro(id = "raw1", name = "RawSave"))
        vm.onRawJsonChange(json)

        vm.saveFromJson()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.saveRaw("raw1", json) }
    }

    @Test
    fun `saveFromJson blocked when jsonError set`() = runTest(dispatcher) {
        val vm = newVm()
        vm.onRawJsonChange("{kaputt}")

        vm.saveFromJson()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.saveRaw(any(), any()) }
    }

    @Test
    fun `onSaveHandled resets isSaved`() = runTest(dispatcher) {
        val vm = newVm()
        vm.saveFromForm()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isSaved)

        vm.onSaveHandled()
        assertFalse(vm.uiState.value.isSaved)
    }

    @Test
    fun `onActionTypeChange clears that action's params`() = runTest(dispatcher) {
        val vm = newVm()
        vm.onAddAction()
        val actionId = vm.uiState.value.actions.single().id
        vm.onActionParamChange(actionId, "message", "hallo")
        assertEquals("hallo", vm.uiState.value.actions.single().params["message"])

        vm.onActionTypeChange(actionId, "execute_shell_script")

        val draft = vm.uiState.value.actions.single()
        assertEquals("execute_shell_script", draft.type)
        assertTrue(draft.params.isEmpty())
    }

    @Test
    fun `onActionParamChange with blank value removes key`() = runTest(dispatcher) {
        val vm = newVm()
        vm.onAddAction()
        val actionId = vm.uiState.value.actions.single().id
        vm.onActionParamChange(actionId, "message", "x")
        assertTrue(vm.uiState.value.actions.single().params.containsKey("message"))

        vm.onActionParamChange(actionId, "message", "   ")

        assertFalse(vm.uiState.value.actions.single().params.containsKey("message"))
    }
}
