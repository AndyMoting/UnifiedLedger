package com.unifiedledger.ui

import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.domain.LedgerId
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P7-07 07.D budget refresh-linkage evidence (D-184; spec section 5): the budget month
 * re-request mirrors the frozen monthly trigger set (initial overview load, effective-month
 * change, unconditional re-request) WITHOUT touching the monthly semantics, and the budget
 * arm rides the SAME post-landing chain as the monthly arm — armed by the P7-05 effective-
 * surface change and the import batch confirmation ONLY once the budget surface has been
 * loaded, consumed exactly once on a successful landing, dropped on a failed one, and fired
 * immediately after a committed budget configuration.
 */
class P503BudgetHostCoordinatorTest {
    private val ledgerId = LedgerId("ledger-budget-coordinator")
    private val march = YearMonth(2026, 3)

    private fun overview(month: YearMonth? = null): P503AppState.OverviewEmpty =
        P503AppState.OverviewEmpty(
            LedgerCurrentState(ledgerId, transactions = emptyList(), balances = emptyList()),
            selectedMonth = month,
        )

    private fun newCoordinator(): Pair<P503HostCoordinator, MutableList<String>> {
        val fired = mutableListOf<String>()
        val coordinator =
            P503HostCoordinator(
                onRefresh = {},
                onSubmit = { _, _ -> },
                onCheck = { _, _ -> },
                onMonthlyRequest = { fired += "monthly" },
                onBudgetMonthRequest = { fired += "budget" },
            )
        return coordinator to fired
    }

    @Test
    fun decideBudgetMonthFiresOnTheFirstOverviewAndOnEffectiveMonthChange() {
        val (coordinator, fired) = newCoordinator()
        assertTrue(coordinator.decideBudgetMonth(overview()))
        assertEquals(listOf("budget"), fired)
        // The same effective month does not re-fire (the (a)/(d) guard mirrors decideMonthly).
        assertFalse(coordinator.decideBudgetMonth(overview()))
        assertEquals(listOf("budget"), fired)
        assertTrue(coordinator.decideBudgetMonth(overview(march)))
        assertEquals(listOf("budget", "budget"), fired)
        assertFalse(coordinator.decideBudgetMonth(overview(march)))
        assertEquals(listOf("budget", "budget"), fired)
    }

    @Test
    fun decideBudgetMonthIgnoresEveryNonOverviewState() {
        val (coordinator, fired) = newCoordinator()
        val config =
            P503AppState.BudgetConfig(
                overview = overview(),
                scope = com.unifiedledger.domain.BudgetScope.Total,
                month = march,
                revision = 1L,
                closed = false,
                limitMinorUnits = null,
            )
        assertFalse(coordinator.decideBudgetMonth(P503AppState.Ready))
        assertFalse(coordinator.decideBudgetMonth(config))
        assertTrue(fired.isEmpty())
    }

    @Test
    fun requestBudgetMonthNowIsUnconditionalAndStampsTheEffectiveMonth() {
        val (coordinator, fired) = newCoordinator()
        assertTrue(coordinator.requestBudgetMonthNow(overview(march)))
        assertTrue(coordinator.requestBudgetMonthNow(overview(march)))
        assertEquals(listOf("budget", "budget"), fired)
        // After the stamp, the (a)/(d) guard stays quiet for the same month.
        assertFalse(coordinator.decideBudgetMonth(overview(march)))
    }

    @Test
    fun theP705ArmRidesTheSharedChainOnlyAfterTheBudgetSurfaceLoaded() {
        val (coordinator, fired) = newCoordinator()
        // Before the budget surface was ever requested, the arm records nothing for the budget.
        coordinator.onP705EffectiveSurfaceChanged()
        coordinator.consumeBudgetMonthReRequestAfterRefresh(overview(march))
        assertTrue(fired.isEmpty())
        // Once loaded, the same effective-surface change arms exactly one post-landing request.
        coordinator.decideBudgetMonth(overview(march))
        coordinator.onP705EffectiveSurfaceChanged()
        assertEquals(listOf("budget"), fired)
        coordinator.consumeBudgetMonthReRequestAfterRefresh(overview(march))
        assertEquals(listOf("budget", "budget"), fired)
        // The flag was consumed: a later landing without a new arm requests nothing.
        coordinator.consumeBudgetMonthReRequestAfterRefresh(overview(march))
        assertEquals(listOf("budget", "budget"), fired)
    }

    @Test
    fun theImportBatchArmRidesTheSameChain() {
        val (coordinator, fired) = newCoordinator()
        coordinator.decideBudgetMonth(overview(march))
        coordinator.onImportBatchConfirmed()
        assertEquals(listOf("budget"), fired)
        coordinator.consumeBudgetMonthReRequestAfterRefresh(overview(march))
        assertEquals(listOf("budget", "budget"), fired)
    }

    @Test
    fun aFailedRefreshLandingDropsTheArmedBudgetRequest() {
        val (coordinator, fired) = newCoordinator()
        coordinator.decideBudgetMonth(overview(march))
        coordinator.onP705EffectiveSurfaceChanged()
        coordinator.dropBudgetMonthReRequestAfterFailedRefresh()
        coordinator.consumeBudgetMonthReRequestAfterRefresh(overview(march))
        assertEquals(listOf("budget"), fired)
    }

    @Test
    fun aCommittedBudgetConfigurationRequestsTheBudgetMonthImmediately() {
        val (coordinator, fired) = newCoordinator()
        coordinator.decideBudgetMonth(overview(march))
        // The config commit changed no transaction, so no authoritative refresh rides it: the
        // budget month re-request fires directly from the landing state (the config surface,
        // whose month is stamped, not from an OverviewEmpty-only path).
        val config =
            P503AppState.BudgetConfig(
                overview = overview(march),
                scope = com.unifiedledger.domain.BudgetScope.Total,
                month = march,
                revision = 2L,
                closed = false,
                limitMinorUnits = 100L,
            )
        coordinator.onBudgetConfigCommitted(config)
        assertEquals(listOf("budget", "budget"), fired)
        // The stamp sees the config surface's month: the following overview guard stays quiet.
        assertFalse(coordinator.decideBudgetMonth(overview(march)))
    }

    @Test
    fun theMonthlyTriggerSemanticsAreUntouchedByTheBudgetChain() {
        val (coordinator, fired) = newCoordinator()
        // The monthly (a)/(d) trigger fires exactly as before the budget chain existed.
        assertTrue(coordinator.decideMonthly(overview()))
        assertFalse(coordinator.decideMonthly(overview()))
        assertTrue(coordinator.decideMonthly(overview(march)))
        assertEquals(listOf("monthly", "monthly"), fired)
        // The P7-05 arm still arms the MONTHLY re-request exactly once (zero monthly change).
        coordinator.onP705EffectiveSurfaceChanged()
        coordinator.consumeMonthlyReRequestAfterRefresh(overview(march))
        assertEquals(listOf("monthly", "monthly", "monthly"), fired)
    }
}
