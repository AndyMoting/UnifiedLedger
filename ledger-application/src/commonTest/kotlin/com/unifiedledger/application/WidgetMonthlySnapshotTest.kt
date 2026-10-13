package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-09 09.C `QueryWidgetMonthlySnapshot` evidence (D-225 Q20; spec sections 6.2/6.4). The
 * snapshot is ONE bounded read folded ONCE — never a second money formula, never the whole-ledger
 * path — and every failure is typed: a read failure / missing projection / clock failure is the
 * 失败 state, and a catalog-version mismatch, a catalog/posting inconsistency, a checked overflow
 * or a multi-currency month is the 失效 state. A genuine empty month is a REAL zero (P709-A05:
 * failure must never masquerade as zero — and zero must never masquerade as failure either).
 *
 * The `P709-Axx` tags in comments are intent labels only; no P709 vector is claimed PASS here
 * (device evidence belongs to the acceptance batch, spec section 9).
 */
class WidgetMonthlySnapshotTest {
    private val ledgerId = LedgerId("ledger-widget-snapshot")
    private val cny = CurrencyUnit("CNY", 2)
    private val usd = CurrencyUnit("USD", 2)
    private val assetId = AccountId("account-widget-asset")
    private val incomeAccountId = AccountId("account-widget-income")
    private val expenseAccountId = AccountId("account-widget-expense")
    private val usdExpenseAccountId = AccountId("account-widget-expense-usd")
    private val usdAssetId = AccountId("account-widget-asset-usd")
    private val categoryId = CategoryId("category-widget-expense")
    private val march = YearMonth(2026, 3)

    @Test
    fun theReadIsOneBoundedWindowOfTheShanghaiNaturalMonth() {
        // Spec sections 6.2/6.4: the window is exactly [monthStart(month), monthStart(nextMonth))
        // in the frozen Asia/Shanghai zone, issued ONCE.
        val port = RecordingPort(emptyList())
        useCase(port).read(ledgerId, march, expectedCatalogVersion = 4L, generation = 9)
        assertEquals(1, port.readCount)
        assertEquals(MonthlyBuckets.monthStart(march), port.lastStart)
        assertEquals(MonthlyBuckets.monthStart(YearMonth(2026, 4)), port.lastEnd)
        assertEquals(4L, port.lastExpectedCatalogVersion)
    }

    @Test
    fun monthBoundaryFollowsTheShanghaiMonthNotTheUtcMonth() {
        // 2026-03-31T16:00:00Z is 2026-04-01 00:00 +08:00, so it belongs to APRIL (C01 vector).
        // The read port applies the window, so the row's bucket follows the frozen zone.
        val port = RecordingPort(listOf(expenseRow("tx-boundary", 1_000L, "2026-03-31T16:00:00Z")))
        val marchSnapshot = useCase(port).read(ledgerId, march, 1L, 1)
        val aprilSnapshot = useCase(port).read(ledgerId, YearMonth(2026, 4), 1L, 1)
        // March has no posting in the window (the row bucketed into April): a genuine zero.
        assertEquals(0L, marchAmounts(marchSnapshot).netExpenseMinorUnits)
        assertEquals(1_000L, aprilAmounts(aprilSnapshot).netExpenseMinorUnits)
    }

    @Test
    fun theThreeAmountsReuseTheFrozenMonthlyBucketsSemantics() {
        // P7-03 semantics (spec section 6.2: no second formula): expense 75.00, income 100.00,
        // balance = ordinary income - net expense = 25.00; a refund posting reduces net expense.
        val rows =
            listOf(
                expenseRow("tx-expense", 7_500L, "2026-03-10T02:00:00Z"),
                incomeRow("tx-income", 10_000L, "2026-03-11T02:00:00Z"),
                expenseRow("tx-refund", -2_500L, "2026-03-12T02:00:00Z", kind = TransactionKind.REFUND_RECEIPT),
            )
        val expected =
            MonthlyBuckets.aggregate(rows, ledgerId, catalog(), listOf(march)).getValue(march).currencies.single()
        val amounts = amounts(useCase(RecordingPort(rows)).read(ledgerId, march, 1L, 3))
        assertEquals(expected.currency, amounts.currency)
        assertEquals(expected.ordinaryIncomeMinorUnits, amounts.ordinaryIncomeMinorUnits)
        assertEquals(expected.netExpenseMinorUnits, amounts.netExpenseMinorUnits)
        assertEquals(expected.balanceMinorUnits, amounts.balanceMinorUnits)
        assertEquals(10_000L, amounts.ordinaryIncomeMinorUnits)
        assertEquals(5_000L, amounts.netExpenseMinorUnits)
        assertEquals(5_000L, amounts.balanceMinorUnits)
    }

    @Test
    fun anEmptyMonthIsAGenuineZeroInTheProductCurrencyNotAFailure() {
        // P709-A05 intent: an empty month is a REAL zero (never a failure), expressed in the single
        // managed product currency.
        val snapshot = useCase(RecordingPort(emptyList())).read(ledgerId, march, expectedCatalogVersion = 2L, generation = 1)
        assertEquals(WidgetMonthlySnapshotStatus.AVAILABLE, snapshot.status)
        val amounts = assertIs<WidgetMonthlyAmounts>(snapshot.amounts)
        assertEquals(CATALOG_MANAGED_CURRENCY, amounts.currency)
        assertEquals(0L, amounts.ordinaryIncomeMinorUnits)
        assertEquals(0L, amounts.netExpenseMinorUnits)
        assertEquals(0L, amounts.balanceMinorUnits)
        assertEquals(2L, snapshot.catalogVersion)
    }

    @Test
    fun theSnapshotCarriesItsReadProvenance() {
        val clock = FixedLedgerClock(Instant.parse("2026-03-20T05:00:00Z"))
        val snapshot = useCase(RecordingPort(emptyList()), clock).read(ledgerId, march, 7L, generation = 12)
        assertEquals(ledgerId, snapshot.ledgerId)
        assertEquals(march, snapshot.month)
        assertEquals(12, snapshot.generation)
        assertEquals(7L, snapshot.catalogVersion)
        assertEquals(Instant.parse("2026-03-20T05:00:00Z"), snapshot.asOf)
    }

    @Test
    fun aMultiCurrencyMonthIsTheTypedInvalidStateAndIsNeverCollapsed() {
        // Batch open item O-CUR (a gap the spec does NOT cover, so it is registered as a NEW open
        // item rather than folded into spec section 8 item 4): the snapshot freezes ONE currency
        // while the projection yields a currency LIST. A month with two activity currencies is
        // 失效 — never a silently picked or summed single-currency number.
        val rows =
            listOf(
                expenseRow("tx-cny", 1_000L, "2026-03-10T02:00:00Z"),
                expenseRow("tx-usd", 2_000L, "2026-03-10T03:00:00Z", currency = usd, accountId = usdExpenseAccountId),
            )
        val snapshot = useCase(RecordingPort(rows)).read(ledgerId, march, 1L, 1)
        assertEquals(WidgetMonthlySnapshotStatus.INVALID, snapshot.status)
        assertNull(snapshot.amounts)
        assertNull(snapshot.currency)
    }

    @Test
    fun aCatalogVersionMismatchFromThePortIsTheTypedInvalidStateNotAZero() {
        val port =
            MonthlyContributionReadPort { _, _, _, _ ->
                MonthlyContributionReadResult.Failed(MonthlyContributionReadFailure.CatalogVersionMismatch)
            }
        val snapshot = QueryWidgetMonthlySnapshot(port, FixedLedgerClock(Instant.parse("2026-03-20T05:00:00Z")))
            .read(ledgerId, march, 1L, 5)
        assertEquals(WidgetMonthlySnapshotStatus.INVALID, snapshot.status)
        assertNull(snapshot.amounts)
        assertEquals(5, snapshot.generation)
    }

    @Test
    fun aMissingProjectionOrReadFailureIsUnavailableAndNeverZeros() {
        for (failure in listOf(MonthlyContributionReadFailure.MissingProjection, MonthlyContributionReadFailure.Unavailable)) {
            val port = MonthlyContributionReadPort { _, _, _, _ -> MonthlyContributionReadResult.Failed(failure) }
            val snapshot = QueryWidgetMonthlySnapshot(port, FixedLedgerClock(Instant.parse("2026-03-20T05:00:00Z")))
                .read(ledgerId, march, 1L, 1)
            assertEquals(WidgetMonthlySnapshotStatus.UNAVAILABLE, snapshot.status)
            assertNull(snapshot.amounts)
            assertNull(snapshot.asOf)
        }
    }

    @Test
    fun aThrowingReadPortIsUnavailableNotAZero() {
        val port = MonthlyContributionReadPort { _, _, _, _ -> throw IllegalStateException("database gone") }
        val snapshot = QueryWidgetMonthlySnapshot(port, FixedLedgerClock(Instant.parse("2026-03-20T05:00:00Z")))
            .read(ledgerId, march, 1L, 1)
        assertEquals(WidgetMonthlySnapshotStatus.UNAVAILABLE, snapshot.status)
        assertNull(snapshot.amounts)
    }

    @Test
    fun aClockFailureAfterASuccessfulReadIsUnavailableNotADatedSnapshot() {
        // Spec section 6.2: `asOf` is part of the frozen snapshot. A clock that cannot report a time
        // leaves the provenance unverifiable, so the read is 失败 — never a silently plausible "now".
        val clock = LedgerClock { throw IllegalStateException("no clock") }
        val snapshot = QueryWidgetMonthlySnapshot(RecordingPort(emptyList()), clock).read(ledgerId, march, 1L, 1)
        assertEquals(WidgetMonthlySnapshotStatus.UNAVAILABLE, snapshot.status)
        assertNull(snapshot.asOf)
    }

    @Test
    fun aCheckedOverflowIsInvalidStateNotAWraparound() {
        val rows =
            listOf(
                expenseRow("tx-max", Long.MAX_VALUE, "2026-03-10T02:00:00Z"),
                expenseRow("tx-more", 5L, "2026-03-11T02:00:00Z"),
            )
        val snapshot = useCase(RecordingPort(rows)).read(ledgerId, march, 1L, 1)
        assertEquals(WidgetMonthlySnapshotStatus.INVALID, snapshot.status)
    }

    @Test
    fun aPostingAccountAbsentFromTheReadCatalogIsInvalidStateNotAFold() {
        val rows = listOf(expenseRow("tx-ghost", 1_000L, "2026-03-10T02:00:00Z", accountId = AccountId("account-ghost")))
        val snapshot = useCase(RecordingPort(rows)).read(ledgerId, march, 1L, 1)
        assertEquals(WidgetMonthlySnapshotStatus.INVALID, snapshot.status)
    }

    @Test
    fun aPostingWhoseAccountCurrencyDisagreesIsInvalidState() {
        // The P7-03 read-consistency predicate: the account in the read catalog must carry the
        // posting's own currency; otherwise the snapshot is not one coherent generation.
        val rows = listOf(expenseRow("tx-mismatch", 1_000L, "2026-03-10T02:00:00Z", currency = usd))
        val snapshot = QueryWidgetMonthlySnapshot(RecordingPort(rows), FixedLedgerClock(Instant.parse("2026-03-20T05:00:00Z")))
            .read(ledgerId, march, 1L, 1)
        assertEquals(WidgetMonthlySnapshotStatus.INVALID, snapshot.status)
    }

    @Test
    fun aNonAvailableSnapshotCannotCarryAnAmount() {
        // The structural half of "failure never masquerades as a zero": a non-AVAILABLE snapshot has
        // no amount, no currency, no asOf and no catalog version at all.
        val snapshot = WidgetMonthlySnapshot.invalid(ledgerId, march, 4)
        assertEquals(WidgetMonthlySnapshotStatus.INVALID, snapshot.status)
        assertNull(snapshot.amounts)
        assertNull(snapshot.currency)
        assertNull(snapshot.asOf)
        assertNull(snapshot.catalogVersion)
        val unavailable = WidgetMonthlySnapshot.unavailable(ledgerId, march, 4)
        assertNull(unavailable.amounts)
        assertNull(unavailable.asOf)
    }

    @Test
    fun theSnapshotRejectsAnAvailableStateWithoutAmounts() {
        // The require() guard itself: mislabelling a state as AVAILABLE without its provenance must
        // fail loudly instead of rendering a fabricated zero.
        val failure =
            runCatching {
                WidgetMonthlySnapshot(ledgerId, march, WidgetMonthlySnapshotStatus.AVAILABLE, generation = 1)
            }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException, "AVAILABLE without amounts must be rejected")
    }

    // ------------------------------------------------------------------ helpers

    private fun useCase(
        port: MonthlyContributionReadPort,
        clock: LedgerClock = FixedLedgerClock(Instant.parse("2026-03-20T05:00:00Z")),
    ): QueryWidgetMonthlySnapshot = QueryWidgetMonthlySnapshot(port, clock)

    private fun amounts(snapshot: WidgetMonthlySnapshot): WidgetMonthlyAmounts = assertIs<WidgetMonthlyAmounts>(snapshot.amounts)

    private fun marchAmounts(snapshot: WidgetMonthlySnapshot): WidgetMonthlyAmounts = amounts(snapshot)

    private fun aprilAmounts(snapshot: WidgetMonthlySnapshot): WidgetMonthlyAmounts = amounts(snapshot)

    private fun expenseRow(
        transactionId: String,
        amountMinor: Long,
        statisticsAt: String,
        kind: TransactionKind = TransactionKind.EXPENSE,
        currency: CurrencyUnit = cny,
        accountId: AccountId = expenseAccountId,
    ): LedgerEntryRow = row(transactionId, accountId, amountMinor, currency, statisticsAt, kind)

    private fun incomeRow(
        transactionId: String,
        amountMinor: Long,
        statisticsAt: String,
    ): LedgerEntryRow {
        // The ledger's raw convention: the INCOME-account posting is NEGATIVE (the asset leg is
        // positive), and [MonthlyBuckets] negates it into positive ordinary income — the same
        // convention as the frozen P7-03 vectors.
        val instant = Instant.parse(statisticsAt)
        val postings =
            listOf(
                Posting(PostingId("posting-$transactionId-received"), assetId, Money.ofMinor(amountMinor, cny)),
                Posting(PostingId("posting-$transactionId-income"), incomeAccountId, Money.ofMinor(-amountMinor, cny)),
            )
        return LedgerEntryRow(
            transactionId = TransactionId(transactionId),
            currentVersionId = TransactionVersionId("version-$transactionId"),
            kind = TransactionKind.INCOME,
            occurredAt = instant,
            statisticsAt = instant,
            note = null,
            postings = postings,
        )
    }

    private fun row(
        transactionId: String,
        accountId: AccountId,
        amountMinor: Long,
        currency: CurrencyUnit,
        statisticsAt: String,
        kind: TransactionKind,
    ): LedgerEntryRow {
        val instant = Instant.parse(statisticsAt)
        // The asset leg must be a REAL account of the SAME currency, otherwise the read-consistency
        // predicate (not the branch under test) would produce the INVALID state and mask it.
        val assetLegId = if (currency == usd) usdAssetId else assetId
        val postings =
            listOf(
                Posting(PostingId("posting-$transactionId-subject"), accountId, Money.ofMinor(amountMinor, currency)),
                Posting(
                    PostingId("posting-$transactionId-asset"),
                    assetLegId,
                    Money.ofMinor(-amountMinor, currency),
                ),
            )
        return LedgerEntryRow(
            transactionId = TransactionId(transactionId),
            currentVersionId = TransactionVersionId("version-$transactionId"),
            kind = kind,
            occurredAt = instant,
            statisticsAt = instant,
            note = null,
            postings = postings,
        )
    }

    private fun catalog(): LedgerCatalog =
        when (
            val result =
                LedgerCatalog.create(
                    accounts =
                        listOf(
                            Account(assetId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                            Account(usdAssetId, ledgerId, AccountKind.ASSET, usd, ownedByUser = true, realAccount = true),
                            Account(incomeAccountId, ledgerId, AccountKind.INCOME, cny, ownedByUser = false, realAccount = false),
                            Account(expenseAccountId, ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                            Account(usdExpenseAccountId, ledgerId, AccountKind.EXPENSE, usd, ownedByUser = false, realAccount = false),
                        ),
                    categories =
                        listOf(
                            Category(categoryId, ledgerId, parentId = null, postingAccountId = expenseAccountId, active = true),
                        ),
                )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> error("test catalog must be valid")
        }

    /**
     * Bounded-read fake that returns the given rows and records its invocation and window. The
     * window is NOT re-filtered here: the real adapter applies the `[start, end)` predicate, so the
     * tests that need a boundary decision hand the port the rows the product adapter would return.
     */
    private inner class RecordingPort(
        private val rowsIn: List<LedgerEntryRow>,
    ) : MonthlyContributionReadPort {
        var readCount = 0
        var lastStart: Instant? = null
        var lastEnd: Instant? = null
        var lastExpectedCatalogVersion: Long? = null

        override fun readContributions(
            ledgerId: LedgerId,
            startInclusive: Instant,
            endExclusive: Instant,
            expectedCatalogVersion: Long,
        ): MonthlyContributionReadResult {
            readCount += 1
            lastStart = startInclusive
            lastEnd = endExclusive
            lastExpectedCatalogVersion = expectedCatalogVersion
            val rows = rowsIn.filter { it.statisticsAt >= startInclusive && it.statisticsAt < endExclusive }
            return MonthlyContributionReadResult.Success(catalogVersion = expectedCatalogVersion, catalog = catalog(), rows = rows)
        }
    }
}
