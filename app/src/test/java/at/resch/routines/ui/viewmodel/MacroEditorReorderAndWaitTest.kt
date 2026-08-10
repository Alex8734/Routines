package at.resch.routines.ui.viewmodel

import at.resch.routines.data.MacroJson
import at.resch.routines.data.MacroRepository
import at.resch.routines.domain.model.Action
import at.resch.routines.domain.model.MacroScript
import at.resch.routines.domain.model.Trigger
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [MacroEditorViewModel.onMoveAction] (action reordering) and
 * the `wait` action type round-trip in the visual editor.
 *
 * Follows the same Main-dispatcher pattern as [MacroEditorViewModelTest]:
 * `viewModelScope` runs on the Main dispatcher, which is replaced with a
 * [StandardTestDispatcher] so the ViewModel can be constructed and mutated
 * synchronously under `runTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MacroEditorReorderAndWaitTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: MacroRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mockk(relaxed = true)
        // Wire parseOrNull to the real MacroJson behaviour, mirroring existing test pattern.
        every { repository.parseOrNull(any()) } answers {
            runCatching { MacroJson.decodeFromString<MacroScript>(firstArg<String>()) }.getOrNull()
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newVm() = MacroEditorViewModel(repository, initialMacroId = null)

    /** Adds [count] default actions to the ViewModel and returns the list of their IDs. */
    private fun addActions(vm: MacroEditorViewModel, count: Int): List<String> {
        repeat(count) { vm.onAddAction() }
        return vm.uiState.value.actions.map { it.id }
    }

    /** Decodes rawJson back into MacroScript so we can inspect the actions array. */
    private fun decodeJson(rawJson: String): MacroScript =
        MacroJson.decodeFromString(rawJson)

    // -----------------------------------------------------------------------
    // onMoveAction — happy path
    // -----------------------------------------------------------------------

    @Test
    fun `onMoveAction(0,2) over 3 actions reorders uiState actions`() = runTest(dispatcher) {
        val vm = newVm()
        addActions(vm, 3)

        // Give each action a distinct type so we can track positions after reorder.
        val ids = vm.uiState.value.actions.map { it.id }
        vm.onActionTypeChange(ids[0], "typeA")
        vm.onActionTypeChange(ids[1], "typeB")
        vm.onActionTypeChange(ids[2], "typeC")

        // Move first action to index 2 → expected order: typeB, typeC, typeA
        vm.onMoveAction(0, 2)

        val actions = vm.uiState.value.actions
        assertEquals(3, actions.size)
        assertEquals("typeB", actions[0].type)
        assertEquals("typeC", actions[1].type)
        assertEquals("typeA", actions[2].type)
    }

    @Test
    fun `onMoveAction(0,2) updates rawJson to reflect new action order`() = runTest(dispatcher) {
        val vm = newVm()
        addActions(vm, 3)

        val ids = vm.uiState.value.actions.map { it.id }
        vm.onActionTypeChange(ids[0], "typeA")
        vm.onActionTypeChange(ids[1], "typeB")
        vm.onActionTypeChange(ids[2], "typeC")

        vm.onMoveAction(0, 2)

        val decoded = decodeJson(vm.uiState.value.rawJson)
        assertEquals(3, decoded.actions.size)
        assertEquals("typeB", decoded.actions[0].type)
        assertEquals("typeC", decoded.actions[1].type)
        assertEquals("typeA", decoded.actions[2].type)
    }

    @Test
    fun `onMoveAction(2,0) moves last action to first position`() = runTest(dispatcher) {
        val vm = newVm()
        addActions(vm, 3)

        val ids = vm.uiState.value.actions.map { it.id }
        vm.onActionTypeChange(ids[0], "typeA")
        vm.onActionTypeChange(ids[1], "typeB")
        vm.onActionTypeChange(ids[2], "typeC")

        vm.onMoveAction(2, 0)

        val actions = vm.uiState.value.actions
        assertEquals("typeC", actions[0].type)
        assertEquals("typeA", actions[1].type)
        assertEquals("typeB", actions[2].type)
    }

    // -----------------------------------------------------------------------
    // onMoveAction — edge cases / no-op
    // -----------------------------------------------------------------------

    @Test
    fun `onMoveAction(1,1) is no-op for same index`() = runTest(dispatcher) {
        val vm = newVm()
        addActions(vm, 3)

        val ids = vm.uiState.value.actions.map { it.id }
        vm.onActionTypeChange(ids[0], "typeA")
        vm.onActionTypeChange(ids[1], "typeB")
        vm.onActionTypeChange(ids[2], "typeC")

        vm.onMoveAction(1, 1)

        val actions = vm.uiState.value.actions
        assertEquals("typeA", actions[0].type)
        assertEquals("typeB", actions[1].type)
        assertEquals("typeC", actions[2].type)
    }

    @Test
    fun `onMoveAction(-1,0) does not crash and leaves state unchanged`() = runTest(dispatcher) {
        val vm = newVm()
        addActions(vm, 3)
        val idsBefore = vm.uiState.value.actions.map { it.id }

        vm.onMoveAction(-1, 0) // out-of-range from — must be a no-op

        val idsAfter = vm.uiState.value.actions.map { it.id }
        assertEquals(idsBefore, idsAfter)
    }

    @Test
    fun `onMoveAction(0,99) does not crash and leaves state unchanged`() = runTest(dispatcher) {
        val vm = newVm()
        addActions(vm, 3)
        val idsBefore = vm.uiState.value.actions.map { it.id }

        vm.onMoveAction(0, 99) // out-of-range to — must be a no-op

        val idsAfter = vm.uiState.value.actions.map { it.id }
        assertEquals(idsBefore, idsAfter)
    }

    @Test
    fun `onMoveAction on empty list does not crash`() = runTest(dispatcher) {
        val vm = newVm()
        // No actions added — state.actions is empty
        vm.onMoveAction(0, 1)
        assertTrue(vm.uiState.value.actions.isEmpty())
    }

    @Test
    fun `onMoveAction on single-action list with from==to is no-op`() = runTest(dispatcher) {
        val vm = newVm()
        addActions(vm, 1)
        val idBefore = vm.uiState.value.actions.single().id

        vm.onMoveAction(0, 0)

        assertEquals(idBefore, vm.uiState.value.actions.single().id)
    }

    // -----------------------------------------------------------------------
    // wait action round-trip
    // -----------------------------------------------------------------------

    @Test
    fun `onActionTypeChange to wait sets type to wait and clears params`() = runTest(dispatcher) {
        val vm = newVm()
        vm.onAddAction()
        val actionId = vm.uiState.value.actions.single().id

        // Set a param first so we can confirm it gets cleared
        vm.onActionParamChange(actionId, "message", "hello")
        assertEquals("hello", vm.uiState.value.actions.single().params["message"])

        vm.onActionTypeChange(actionId, "wait")

        val draft = vm.uiState.value.actions.single()
        assertEquals("wait", draft.type)
        assertTrue("params should be empty after type change", draft.params.isEmpty())
    }

    @Test
    fun `onActionParamChange durationMs 3000 is reflected in uiState`() = runTest(dispatcher) {
        val vm = newVm()
        vm.onAddAction()
        val actionId = vm.uiState.value.actions.single().id
        vm.onActionTypeChange(actionId, "wait")

        vm.onActionParamChange(actionId, "durationMs", "3000")

        val draft = vm.uiState.value.actions.single()
        assertEquals("3000", draft.params["durationMs"])
    }

    @Test
    fun `onActionParamChange durationMs 3000 appears in rawJson`() = runTest(dispatcher) {
        val vm = newVm()
        vm.onAddAction()
        val actionId = vm.uiState.value.actions.single().id
        vm.onActionTypeChange(actionId, "wait")
        vm.onActionParamChange(actionId, "durationMs", "3000")

        val rawJson = vm.uiState.value.rawJson
        assertTrue("rawJson should contain 'durationMs'", rawJson.contains("durationMs"))
        assertTrue("rawJson should contain '3000'", rawJson.contains("3000"))
    }

    @Test
    fun `rawJson wait action round-trips through MacroJson decode correctly`() = runTest(dispatcher) {
        val vm = newVm()
        vm.onAddAction()
        val actionId = vm.uiState.value.actions.single().id
        vm.onActionTypeChange(actionId, "wait")
        vm.onActionParamChange(actionId, "durationMs", "3000")

        val decoded = decodeJson(vm.uiState.value.rawJson)
        assertEquals(1, decoded.actions.size)
        val action = decoded.actions.single()
        assertEquals("wait", action.type)
        assertEquals("3000", action.params["durationMs"])
    }

    @Test
    fun `blank durationMs removes the key from params`() = runTest(dispatcher) {
        val vm = newVm()
        vm.onAddAction()
        val actionId = vm.uiState.value.actions.single().id
        vm.onActionTypeChange(actionId, "wait")
        vm.onActionParamChange(actionId, "durationMs", "3000")
        assertTrue(vm.uiState.value.actions.single().params.containsKey("durationMs"))

        vm.onActionParamChange(actionId, "durationMs", "   ")

        assertFalse(
            "durationMs key should be removed when blank",
            vm.uiState.value.actions.single().params.containsKey("durationMs")
        )
    }

    @Test
    fun `blank durationMs also removed from rawJson`() = runTest(dispatcher) {
        val vm = newVm()
        vm.onAddAction()
        val actionId = vm.uiState.value.actions.single().id
        vm.onActionTypeChange(actionId, "wait")
        vm.onActionParamChange(actionId, "durationMs", "3000")
        vm.onActionParamChange(actionId, "durationMs", "")

        val decoded = decodeJson(vm.uiState.value.rawJson)
        assertFalse(
            "durationMs should be absent from rawJson after blank",
            decoded.actions.single().params.containsKey("durationMs")
        )
    }

    @Test
    fun `wait action with durationMs survives reorder and rawJson stays consistent`() = runTest(dispatcher) {
        val vm = newVm()
        addActions(vm, 2) // add two actions
        val ids = vm.uiState.value.actions.map { it.id }

        // First action: log; second action: wait with durationMs
        vm.onActionTypeChange(ids[0], "log")
        vm.onActionParamChange(ids[0], "message", "hello")
        vm.onActionTypeChange(ids[1], "wait")
        vm.onActionParamChange(ids[1], "durationMs", "2000")

        // Move wait to first position
        vm.onMoveAction(1, 0)

        val actions = vm.uiState.value.actions
        assertEquals("wait", actions[0].type)
        assertEquals("2000", actions[0].params["durationMs"])
        assertEquals("log", actions[1].type)

        // Verify rawJson is consistent
        val decoded = decodeJson(vm.uiState.value.rawJson)
        assertEquals("wait", decoded.actions[0].type)
        assertEquals("2000", decoded.actions[0].params["durationMs"])
        assertEquals("log", decoded.actions[1].type)
    }
}
