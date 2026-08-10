package at.resch.routines.core.action

import at.resch.routines.core.cache.CapabilityCache
import at.resch.routines.core.shell.RootChecker
import at.resch.routines.domain.model.Action
import at.resch.routines.domain.model.ActionResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [StrategyActionExecutor] — the Compatibility-Contract base class.
 *
 * Production code is injected at compile time. Tests cover:
 *  - Cache hit: preferred strategy tried first, others skipped on success.
 *  - Root skip: requiresRoot strategies are skipped when root unavailable;
 *               subsequent non-root strategies still run.
 *  - Declared order: first success wins and is persisted via CapabilityCache.remember.
 *  - All-fail: aggregated Failure references attempted strategy ids.
 *  - Edge — stale cache id: falls back to declared order when id not in list.
 *  - Edge — strategy.attempt throws: treated as failure, iteration continues.
 *
 * [TestStrategyExecutor] is a minimal test-only subclass of [StrategyActionExecutor].
 */
class StrategyActionExecutorTest {

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private lateinit var rootChecker: RootChecker
    private lateinit var capabilityCache: CapabilityCache

    /** Action used throughout; executor type doesn't matter for base-class logic. */
    private val testAction = Action(type = "test_action", params = emptyMap())

    @Before
    fun setup() {
        rootChecker = mockk()
        capabilityCache = mockk(relaxed = true) // remember() calls are void — relax them
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a mock [Strategy] whose [Strategy.attempt] returns [result].
     * [requiresRoot] defaults to false.
     */
    private fun strategyMock(
        id: String,
        result: ActionResult,
        requiresRoot: Boolean = false
    ): Strategy {
        val s = mockk<Strategy>()
        every { s.id } returns id
        every { s.requiresRoot } returns requiresRoot
        coEvery { s.attempt(any()) } returns result
        return s
    }

    /** Builds a mock [Strategy] whose [Strategy.attempt] throws [exception]. */
    private fun throwingStrategyMock(id: String, exception: Exception): Strategy {
        val s = mockk<Strategy>()
        every { s.id } returns id
        every { s.requiresRoot } returns false
        coEvery { s.attempt(any()) } throws exception
        return s
    }

    /** Constructs the [TestStrategyExecutor] with the given strategies list. */
    private fun executorWith(strategies: List<Strategy>): TestStrategyExecutor =
        TestStrategyExecutor(
            rootChecker = rootChecker,
            capabilityCache = capabilityCache,
            strategyList = strategies
        )

    // =========================================================================
    // 1. Cache hit — preferred strategy tried first, others skipped on success
    // =========================================================================

    @Test
    fun `cache hit tries preferred strategy first and skips others`() = runTest {
        val slow = strategyMock("slow_strategy", ActionResult.Success("slow"))
        val fast = strategyMock("fast_strategy", ActionResult.Success("fast"))

        every { capabilityCache.preferredStrategy("test_action") } returns "fast_strategy"
        // Root not needed for these non-root strategies
        coEvery { rootChecker.isRootAvailable() } returns false

        val executor = executorWith(listOf(slow, fast))
        val result = executor.execute(testAction)

        assertTrue("Expected Success", result is ActionResult.Success)
        // fast_strategy was invoked
        coVerify(exactly = 1) { fast.attempt(testAction) }
        // slow_strategy was never attempted
        coVerify(exactly = 0) { slow.attempt(any()) }
    }

    // =========================================================================
    // 2. Root skip — requiresRoot strategy skipped; non-root fallback runs
    // =========================================================================

    @Test
    fun `root strategy skipped when root unavailable and non-root fallback executes`() = runTest {
        val rootStrategy = strategyMock("root_strat", ActionResult.Success("root"), requiresRoot = true)
        val userStrategy = strategyMock("user_strat", ActionResult.Success("user"), requiresRoot = false)

        every { capabilityCache.preferredStrategy("test_action") } returns null
        coEvery { rootChecker.isRootAvailable() } returns false

        val executor = executorWith(listOf(rootStrategy, userStrategy))
        val result = executor.execute(testAction)

        assertTrue("Expected Success from user fallback", result is ActionResult.Success)
        // Root strategy must NOT have been attempted
        coVerify(exactly = 0) { rootStrategy.attempt(any()) }
        // User strategy must have been attempted
        coVerify(exactly = 1) { userStrategy.attempt(testAction) }
    }

    @Test
    fun `isRootAvailable only consulted when a requiresRoot strategy is reached`() = runTest {
        // Only non-root strategies — root check should never be called
        val s1 = strategyMock("s1", ActionResult.Success("ok"), requiresRoot = false)

        every { capabilityCache.preferredStrategy("test_action") } returns null

        val executor = executorWith(listOf(s1))
        executor.execute(testAction)

        coVerify(exactly = 0) { rootChecker.isRootAvailable() }
    }

    @Test
    fun `root strategy executes when root is available`() = runTest {
        val rootStrategy = strategyMock("root_strat", ActionResult.Success("root"), requiresRoot = true)

        every { capabilityCache.preferredStrategy("test_action") } returns null
        coEvery { rootChecker.isRootAvailable() } returns true

        val executor = executorWith(listOf(rootStrategy))
        val result = executor.execute(testAction)

        assertTrue("Expected Success", result is ActionResult.Success)
        coVerify(exactly = 1) { rootStrategy.attempt(testAction) }
    }

    // =========================================================================
    // 3. Declared order — first success wins; winner is remembered; later skipped
    // =========================================================================

    @Test
    fun `strategies attempted in declared order, first success wins and is remembered`() = runTest {
        val failing = strategyMock("fail_strat", ActionResult.Failure("nope"), requiresRoot = false)
        val winning = strategyMock("win_strat", ActionResult.Success("ok"), requiresRoot = false)
        val never = strategyMock("never_strat", ActionResult.Success("should not run"), requiresRoot = false)

        every { capabilityCache.preferredStrategy("test_action") } returns null
        // No root strategies involved
        coEvery { rootChecker.isRootAvailable() } returns false

        val executor = executorWith(listOf(failing, winning, never))
        val result = executor.execute(testAction)

        assertTrue("Expected Success", result is ActionResult.Success)
        // Order: fail → win
        coVerify(exactly = 1) { failing.attempt(testAction) }
        coVerify(exactly = 1) { winning.attempt(testAction) }
        // Strategy after winner must not be called
        coVerify(exactly = 0) { never.attempt(any()) }
        // Winner must be persisted
        verify(exactly = 1) { capabilityCache.remember("test_action", "win_strat") }
    }

    // =========================================================================
    // 4. All strategies fail — aggregated Failure
    // =========================================================================

    @Test
    fun `all strategies fail returns aggregated Failure listing attempted ids`() = runTest {
        val s1 = strategyMock("alpha", ActionResult.Failure("err_alpha"), requiresRoot = false)
        val s2 = strategyMock("beta", ActionResult.Failure("err_beta"), requiresRoot = false)

        every { capabilityCache.preferredStrategy("test_action") } returns null
        coEvery { rootChecker.isRootAvailable() } returns false

        val executor = executorWith(listOf(s1, s2))
        val result = executor.execute(testAction)

        assertTrue("Expected Failure", result is ActionResult.Failure)
        val reason = (result as ActionResult.Failure).reason
        assertTrue("Failure reason should mention 'alpha'", reason.contains("alpha"))
        assertTrue("Failure reason should mention 'beta'", reason.contains("beta"))
        // remember must never be called when all fail
        verify(exactly = 0) { capabilityCache.remember(any(), any()) }
    }

    @Test
    fun `all strategies root-skipped returns Failure`() = runTest {
        val r1 = strategyMock("root_only_1", ActionResult.Success("unreachable"), requiresRoot = true)
        val r2 = strategyMock("root_only_2", ActionResult.Success("unreachable"), requiresRoot = true)

        every { capabilityCache.preferredStrategy("test_action") } returns null
        coEvery { rootChecker.isRootAvailable() } returns false

        val executor = executorWith(listOf(r1, r2))
        val result = executor.execute(testAction)

        assertTrue("Expected Failure when all root strategies are skipped", result is ActionResult.Failure)
        coVerify(exactly = 0) { r1.attempt(any()) }
        coVerify(exactly = 0) { r2.attempt(any()) }
    }

    // =========================================================================
    // 5. Edge: cached id not in strategy list → falls back to declared order
    // =========================================================================

    @Test
    fun `stale cached id not present in list falls back to declared order`() = runTest {
        val s1 = strategyMock("current_strat", ActionResult.Success("ok"), requiresRoot = false)

        // Cache points to an id that no longer exists in the strategy list
        every { capabilityCache.preferredStrategy("test_action") } returns "removed_strat"
        coEvery { rootChecker.isRootAvailable() } returns false

        val executor = executorWith(listOf(s1))
        val result = executor.execute(testAction)

        assertTrue("Expected Success via fallback order", result is ActionResult.Success)
        coVerify(exactly = 1) { s1.attempt(testAction) }
    }

    // =========================================================================
    // 6. Edge: strategy.attempt throws → treated as failure, iteration continues
    // =========================================================================

    @Test
    fun `strategy that throws is treated as failure and iteration continues`() = runTest {
        val exploding = throwingStrategyMock("exploding_strat", RuntimeException("boom"))
        val safe = strategyMock("safe_strat", ActionResult.Success("recovered"), requiresRoot = false)

        every { capabilityCache.preferredStrategy("test_action") } returns null
        coEvery { rootChecker.isRootAvailable() } returns false

        val executor = executorWith(listOf(exploding, safe))
        val result = executor.execute(testAction)

        assertTrue("Expected Success after recovering from thrown exception", result is ActionResult.Success)
        coVerify(exactly = 1) { safe.attempt(testAction) }
    }

    @Test
    fun `all strategies throw returns Failure`() = runTest {
        val e1 = throwingStrategyMock("throw_1", RuntimeException("error 1"))
        val e2 = throwingStrategyMock("throw_2", IllegalStateException("error 2"))

        every { capabilityCache.preferredStrategy("test_action") } returns null
        coEvery { rootChecker.isRootAvailable() } returns false

        val executor = executorWith(listOf(e1, e2))
        val result = executor.execute(testAction)

        assertTrue("Expected Failure when all strategies throw", result is ActionResult.Failure)
    }
}

// =============================================================================
// Test-only subclass of StrategyActionExecutor
// =============================================================================

/**
 * Minimal concrete subclass used exclusively in tests.
 * Accepts [strategyList] at construction time so each test can configure the
 * strategy chain without subclassing per test. The type is fixed to "test_action"
 * to match the test action instances used throughout this file.
 */
private class TestStrategyExecutor(
    rootChecker: RootChecker,
    capabilityCache: CapabilityCache,
    private val strategyList: List<Strategy>
) : StrategyActionExecutor(rootChecker, capabilityCache) {

    override val type: String = "test_action"

    override fun strategies(action: Action): List<Strategy> = strategyList
}
