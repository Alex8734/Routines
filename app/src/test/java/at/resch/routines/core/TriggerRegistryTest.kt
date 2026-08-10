package at.resch.routines.core

import at.resch.routines.data.MacroRepository
import at.resch.routines.domain.model.Action
import at.resch.routines.domain.model.MacroScript
import at.resch.routines.domain.model.Trigger
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Unit tests for [TriggerRegistry].
 *
 * The registry observes [MacroRepository.observeAll] and starts/stops [TriggerSource]s
 * based on which triggerIds active macros need. Tests use flowOf() to deliver a single
 * snapshot and UnconfinedTestDispatcher so collect() processes synchronously.
 */
class TriggerRegistryTest {

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private val cellMacro = MacroScript(
        id = "c1",
        name = "Cell macro",
        enabled = true,
        trigger = Trigger.SimCardDataConnected,
        actions = listOf(Action("log"))
    )

    private val startupMacro = MacroScript(
        id = "s1",
        name = "Startup macro",
        enabled = true,
        trigger = Trigger.OnStartup,
        actions = listOf(Action("log"))
    )

    private val disabledCellMacro = cellMacro.copy(id = "c2", enabled = false)

    /**
     * Builds a fake [TriggerSource] that records start/stop calls.
     */
    private fun fakeSource(id: String): TriggerSource = mockk(relaxed = true) {
        every { triggerId } returns id
    }

    private fun buildRegistry(
        macros: List<MacroScript>,
        vararg sources: TriggerSource
    ): Triple<TriggerRegistry, MacroRepository, TestScope> {
        val repo = mockk<MacroRepository>()
        every { repo.observeAll() } returns flowOf(macros)
        val scope = TestScope(UnconfinedTestDispatcher())
        val registry = TriggerRegistry(EventBus(), repo, sources.toList())
        return Triple(registry, repo, scope)
    }

    // -----------------------------------------------------------------------
    // Source started when an active macro needs its triggerId
    // -----------------------------------------------------------------------

    @Test
    fun `source is started when active macro requires its triggerId`() = runTest(UnconfinedTestDispatcher()) {
        val cellSource = fakeSource(NetworkStatusTracker.TRIGGER_ID)
        val (registry, _, scope) = buildRegistry(listOf(cellMacro), cellSource)

        registry.start(scope)

        verify(exactly = 1) { cellSource.start(any(), any()) }
    }

    @Test
    fun `source is not started when no active macro requires it`() = runTest(UnconfinedTestDispatcher()) {
        val cellSource = fakeSource(NetworkStatusTracker.TRIGGER_ID)
        val (registry, _, scope) = buildRegistry(emptyList(), cellSource)

        registry.start(scope)

        verify(exactly = 0) { cellSource.start(any(), any()) }
    }

    // -----------------------------------------------------------------------
    // Stopped when no longer needed
    // -----------------------------------------------------------------------

    @Test
    fun `source is stopped when it becomes no longer needed across emissions`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = mockk<MacroRepository>()
            // First emission: cell macro present; second: no macros.
            every { repo.observeAll() } returns kotlinx.coroutines.flow.flow {
                emit(listOf(cellMacro))
                emit(emptyList())
            }
            val cellSource = fakeSource(NetworkStatusTracker.TRIGGER_ID)
            val scope = TestScope(UnconfinedTestDispatcher())
            val registry = TriggerRegistry(EventBus(), repo, listOf(cellSource))

            registry.start(scope)

            verify(exactly = 1) { cellSource.start(any(), any()) }
            verify(exactly = 1) { cellSource.stop() }
        }

    // -----------------------------------------------------------------------
    // Redundant restarts avoided
    // -----------------------------------------------------------------------

    @Test
    fun `source is not restarted if the needed triggerIds set did not change`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = mockk<MacroRepository>()
            // Two identical emissions (same set of triggerIds) — distinctUntilChanged
            // should prevent a second start.
            every { repo.observeAll() } returns kotlinx.coroutines.flow.flow {
                emit(listOf(cellMacro))
                emit(listOf(cellMacro.copy(id = "c_copy"))) // same trigger, different id
            }
            val cellSource = fakeSource(NetworkStatusTracker.TRIGGER_ID)
            val scope = TestScope(UnconfinedTestDispatcher())
            val registry = TriggerRegistry(EventBus(), repo, listOf(cellSource))

            registry.start(scope)

            // The second emission has the same Set<String> of triggerIds, so
            // distinctUntilChanged drops it — start called only once.
            verify(exactly = 1) { cellSource.start(any(), any()) }
        }

    // -----------------------------------------------------------------------
    // stopAll
    // -----------------------------------------------------------------------

    @Test
    fun `stopAll stops all running sources`() = runTest(UnconfinedTestDispatcher()) {
        val cellSource = fakeSource(NetworkStatusTracker.TRIGGER_ID)
        val (registry, _, scope) = buildRegistry(listOf(cellMacro), cellSource)

        registry.start(scope)
        registry.stopAll()

        verify(exactly = 1) { cellSource.stop() }
    }

    @Test
    fun `stopAll does nothing when no sources are running`() = runTest(UnconfinedTestDispatcher()) {
        val cellSource = fakeSource(NetworkStatusTracker.TRIGGER_ID)
        val (registry, _, _) = buildRegistry(emptyList(), cellSource)

        registry.stopAll() // no start() called

        verify(exactly = 0) { cellSource.stop() }
    }

    // -----------------------------------------------------------------------
    // OnStartup never spins up a source
    // -----------------------------------------------------------------------

    @Test
    fun `OnStartup trigger does not cause any TriggerSource to start`() =
        runTest(UnconfinedTestDispatcher()) {
            // The registry maps OnStartup to BootReceiver.TRIGGER_ID ("on_startup"),
            // but there is no TriggerSource registered for that id — by design.
            val cellSource = fakeSource(NetworkStatusTracker.TRIGGER_ID)
            val (registry, _, scope) = buildRegistry(listOf(startupMacro), cellSource)

            registry.start(scope)

            // The cell source must not be started just because an OnStartup macro exists.
            verify(exactly = 0) { cellSource.start(any(), any()) }
        }

    // -----------------------------------------------------------------------
    // Disabled macros do not spin up sources
    // -----------------------------------------------------------------------

    @Test
    fun `disabled macro does not cause its source to start`() = runTest(UnconfinedTestDispatcher()) {
        val cellSource = fakeSource(NetworkStatusTracker.TRIGGER_ID)
        val (registry, _, scope) = buildRegistry(listOf(disabledCellMacro), cellSource)

        registry.start(scope)

        verify(exactly = 0) { cellSource.start(any(), any()) }
    }
}
