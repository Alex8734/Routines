package at.resch.routines.core

import at.resch.routines.data.MacroRepository
import at.resch.routines.domain.model.Action
import at.resch.routines.domain.model.ActionResult
import at.resch.routines.domain.model.MacroScript
import at.resch.routines.domain.model.Trigger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Unit tests for [MacroEvaluator.onEvent].
 *
 * Strategy: inject a mock [MacroRepository] whose observeAll() returns a controlled
 * list, and a mock [MacroEngine] to verify dispatch. Tests call [MacroEvaluator.onEvent]
 * directly (the separately-testable entry point), so no real EventBus collection is needed.
 */
class MacroEvaluatorTest {

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private val startupMacro = MacroScript(
        id = "startup1",
        name = "Boot macro",
        enabled = true,
        trigger = Trigger.OnStartup,
        actions = listOf(Action("log"))
    )

    private val cellMacro = MacroScript(
        id = "cell1",
        name = "Cell macro",
        enabled = true,
        trigger = Trigger.SimCardDataConnected,
        actions = listOf(Action("log"))
    )

    private val disabledStartupMacro = MacroScript(
        id = "disabled1",
        name = "Disabled boot macro",
        enabled = false,
        trigger = Trigger.OnStartup,
        actions = listOf(Action("log"))
    )

    private fun buildEvaluator(macros: List<MacroScript>): Pair<MacroEvaluator, MacroEngine> {
        val repo = mockk<MacroRepository>()
        val engine = mockk<MacroEngine>()
        coEvery { repo.observeAll() } returns flowOf(macros)
        coEvery { engine.execute(any()) } returns listOf(ActionResult.Success())
        val evaluator = MacroEvaluator(EventBus(), repo, engine)
        return evaluator to engine
    }

    // -----------------------------------------------------------------------
    // Startup event
    // -----------------------------------------------------------------------

    @Test
    fun `Startup event triggers only OnStartup macros`() = runTest {
        val (evaluator, engine) = buildEvaluator(listOf(startupMacro, cellMacro))

        evaluator.onEvent(SystemEvent.Startup)

        coVerify(exactly = 1) { engine.execute(startupMacro) }
        coVerify(exactly = 0) { engine.execute(cellMacro) }
    }

    @Test
    fun `CellularDataConnected event triggers only SimCardDataConnected macros`() = runTest {
        val (evaluator, engine) = buildEvaluator(listOf(startupMacro, cellMacro))

        evaluator.onEvent(SystemEvent.CellularDataConnected)

        coVerify(exactly = 0) { engine.execute(startupMacro) }
        coVerify(exactly = 1) { engine.execute(cellMacro) }
    }

    // -----------------------------------------------------------------------
    // Disabled macros
    // -----------------------------------------------------------------------

    @Test
    fun `disabled macros are skipped even if trigger matches`() = runTest {
        val (evaluator, engine) = buildEvaluator(listOf(disabledStartupMacro))

        evaluator.onEvent(SystemEvent.Startup)

        coVerify(exactly = 0) { engine.execute(any()) }
    }

    @Test
    fun `disabled macro does not run but enabled macro with same trigger does`() = runTest {
        val (evaluator, engine) = buildEvaluator(listOf(disabledStartupMacro, startupMacro))

        evaluator.onEvent(SystemEvent.Startup)

        coVerify(exactly = 1) { engine.execute(startupMacro) }
        coVerify(exactly = 0) { engine.execute(disabledStartupMacro) }
    }

    // -----------------------------------------------------------------------
    // No-match event
    // -----------------------------------------------------------------------

    @Test
    fun `event with no matching macros dispatches nothing to engine`() = runTest {
        val (evaluator, engine) = buildEvaluator(listOf(startupMacro))

        evaluator.onEvent(SystemEvent.CellularDataConnected)

        coVerify(exactly = 0) { engine.execute(any()) }
    }

    @Test
    fun `empty macro list dispatches nothing`() = runTest {
        val (evaluator, engine) = buildEvaluator(emptyList())

        evaluator.onEvent(SystemEvent.Startup)

        coVerify(exactly = 0) { engine.execute(any()) }
    }

    // -----------------------------------------------------------------------
    // Multiple matching macros
    // -----------------------------------------------------------------------

    @Test
    fun `all enabled matching macros are executed when multiple match`() = runTest {
        val startup2 = startupMacro.copy(id = "startup2", name = "Boot macro 2")
        val (evaluator, engine) = buildEvaluator(listOf(startupMacro, startup2, cellMacro))

        evaluator.onEvent(SystemEvent.Startup)

        coVerify(exactly = 1) { engine.execute(startupMacro) }
        coVerify(exactly = 1) { engine.execute(startup2) }
        coVerify(exactly = 0) { engine.execute(cellMacro) }
    }
}
