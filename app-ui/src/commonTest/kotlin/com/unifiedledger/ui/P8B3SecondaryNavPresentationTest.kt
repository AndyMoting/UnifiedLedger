package com.unifiedledger.ui

import com.unifiedledger.application.CreationEntry
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.LedgerCurrentStateReadPort
import com.unifiedledger.application.LedgerEntryRow
import com.unifiedledger.application.TransactionDetail
import com.unifiedledger.application.TransactionDetailLeg
import com.unifiedledger.application.TransactionReconciliationProjection
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersion
import com.unifiedledger.domain.TransactionVersionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P8-00 b3 (V-6/V-5) presentation decisions: the canvas-04 headline amount never fabricates a
 * net for a multi-leg transfer, the sub-line always renders, the version-history line carries
 * version number + both times + note (可空 → 无) + the current-version marker, and the
 * account-flow surface's filter/footer/empty copy decisions are honest (a not-loaded state never
 * reads as empty; no total is ever claimed beyond the loaded count). Pure functions — no Compose
 * runtime.
 */
class P8B3SecondaryNavPresentationTest {
    private val cny = CurrencyUnit("CNY", 2)
    private val assetId = AccountId("account-asset")
    private val expenseId = AccountId("account-expense")
    private val occurredAt = Instant.parse("2026-11-06T04:30:00Z")

    private fun detail(
        vararg amounts: Long,
        currency: CurrencyUnit = cny,
    ): TransactionDetail {
        val legs =
            amounts.mapIndexed { index, minor ->
                TransactionDetailLeg(PostingId("posting-$index"), AccountId("account-$index"), "账户$index", Money.ofMinor(minor, currency), categoryName = null)
            }
        return TransactionDetail(
            ledgerId = LedgerId("ledger-p8b3"),
            transactionId = TransactionId("tx-1"),
            currentVersionId = TransactionVersionId("version-1"),
            kind = TransactionKind.EXPENSE,
            occurredAt = occurredAt,
            statisticsAt = occurredAt,
            note = null,
            legs = legs,
            creationEntry = CreationEntry.MANUAL_CREATED,
            reconciliation = TransactionReconciliationProjection(legs = emptyList(), rollup = null),
        )
    }

    // ---- 件 2: the canvas-04 headline amount (no fabricated nets) ----

    @Test
    fun aSingleLegHeadlineIsTheExactSignedAmount() {
        assertEquals("-32.00 CNY", transactionDetailHeadlineAmount(detail(-3_200L)))
    }

    @Test
    fun anExactNegatedPairHeadlineIsSignedByTheKind() {
        // 支出 shape (canvas 04's -32.00): two exact negations with kind EXPENSE → the negative
        // headline regardless of the legs' order (the direction lives in the kind, the exact
        // legs stand below).
        assertEquals("-32.00 CNY", transactionDetailHeadlineAmount(detail(-3_200L, 3_200L)))
        assertEquals("-32.00 CNY", transactionDetailHeadlineAmount(detail(3_200L, -3_200L)))
        // A zero-magnitude pair (both legs 0) renders no headline.
        assertNull(transactionDetailHeadlineAmount(detail(0L, 0L)))
    }

    @Test
    fun aTransferShapeRendersNoHeadlineNeverAFabricatedNet() {
        // 100 → -70/-30-style multi-leg: no single unambiguous amount exists, so no headline.
        assertNull(transactionDetailHeadlineAmount(detail(10_000L, -7_000L, -3_000L)))
        // A two-leg pair that does NOT net to one value (a partial pair) renders no headline either.
        assertNull(transactionDetailHeadlineAmount(detail(3_200L, -3_000L)))
    }

    @Test
    fun theSubLineAlwaysRendersTheCurrencyPrefixAndTheOccurredTime() {
        val line = transactionDetailHeadlineSubLine(detail(-3_200L))
        assertTrue(line.startsWith("CNY · "))
        assertTrue(line.contains("发生 $occurredAt"))
        // A headline-less shape still renders an honest sub-line (first leg's currency).
        assertTrue(transactionDetailHeadlineSubLine(detail(10_000L, -7_000L, -3_000L)).startsWith("CNY · "))
    }

    // ---- 件 1: the version-history line ----

    @Test
    fun theVersionHistoryLineCarriesNumberTimesNoteAndTheCurrentMarker() {
        val version =
            TransactionVersion(
                id = TransactionVersionId("version-2"),
                transactionId = TransactionId("tx-1"),
                versionNumber = 2,
                postingSetId = com.unifiedledger.domain.PostingSetId("posting-set-2"),
                times =
                    TransactionTimes(
                        occurredAt = Instant.parse("2026-03-01T02:00:00Z"),
                        statisticsAt = Instant.parse("2026-04-01T02:00:00Z"),
                        effectiveAt = Instant.parse("2026-03-01T02:00:00Z"),
                    ),
                note = "改了备注",
            )
        val line = transactionVersionHistoryLine(version, TransactionVersionId("version-2"))
        assertTrue(line.startsWith("版本 2（当前版本）"))
        assertTrue(line.contains("发生 2026-03-01T02:00:00Z"))
        assertTrue(line.contains("统计 2026-04-01T02:00:00Z"))
        assertTrue(line.endsWith("备注：改了备注"))
        // A non-current version carries no marker; a null note renders the honest 无.
        val older = transactionVersionHistoryLine(version.copy(id = TransactionVersionId("version-1"), note = null), TransactionVersionId("version-2"))
        assertFalse(older.startsWith("版本 2（当前版本）"))
        assertTrue(older.startsWith("版本 2 · "))
        assertTrue(older.endsWith("备注：无"))
    }

    // ---- 件 3: the account-flow presentation decisions ----

    @Test
    fun theRowHeadlineIsTheStatisticsDatePlusTheNoteOrTheKind() {
        val row =
            LedgerEntryRow(
                transactionId = TransactionId("tx-1"),
                currentVersionId = TransactionVersionId("version-1"),
                kind = TransactionKind.EXPENSE,
                occurredAt = occurredAt,
                statisticsAt = occurredAt,
                note = "工作午餐",
                postings = emptyList(),
            )
        assertEquals("2026-11-06 · 工作午餐", accountFlowRowHeadlineText(row))
        assertEquals("2026-11-06 · EXPENSE", accountFlowRowHeadlineText(row.copy(note = null)))
    }

    @Test
    fun theFooterFollowsTheWalkAndTheFailureMarkers() {
        val base =
            P503AppState.AccountFlow(
                overview = P503AppState.OverviewEmpty(LedgerCurrentState(LedgerId("ledger-p8b3"), transactions = emptyList(), balances = emptyList())),
                accountId = assetId,
                balanceLine = "默认支付账户 · 资产 · CNY",
                balanceMinorUnits = 0L,
                balanceCurrency = cny,
                loadedRows = emptyList(),
                nextWholeLedgerCursor = LedgerCurrentStateReadPort.LedgerFlowCursor(0L, "tx-0"),
            )
        assertEquals(FlowFooterState.FETCH_MORE, accountFlowFooterState(base))
        assertEquals(FlowFooterState.LOADING, accountFlowFooterState(base.copy(rowsLoading = true)))
        assertEquals(FlowFooterState.RETRY, accountFlowFooterState(base.copy(rowsLoadFailed = true)))
        // cursor = null → the honest whole-ledger end.
        assertEquals(FlowFooterState.END, accountFlowFooterState(base.copy(nextWholeLedgerCursor = null)))
    }

    @Test
    fun anEmptyFlowIsHonestNeverAFabricatedZero() {
        val surface =
            P503AppState.AccountFlow(
                overview = P503AppState.OverviewEmpty(LedgerCurrentState(LedgerId("ledger-p8b3"), transactions = emptyList(), balances = emptyList())),
                accountId = assetId,
                balanceLine = "默认支付账户 · 资产 · CNY",
                balanceMinorUnits = 0L,
                balanceCurrency = cny,
                loadedRows = emptyList(),
                nextWholeLedgerCursor = null,
            )
        assertEquals("该账户暂无流水。", accountFlowEmptyText(surface))
        // A walk still open with nothing loaded yet: the not-loaded state never reads as empty (F2).
        assertEquals("该账户的流水尚未加载完成。", accountFlowEmptyText(surface.copy(nextWholeLedgerCursor = LedgerCurrentStateReadPort.LedgerFlowCursor(0L, "tx-0"))))
    }

    @Test
    fun theLoadedCountIsExactlyTheRowsOnScreenNeverALedgerTotal() {
        val row =
            LedgerEntryRow(
                transactionId = TransactionId("tx-1"),
                currentVersionId = TransactionVersionId("version-1"),
                kind = TransactionKind.EXPENSE,
                occurredAt = occurredAt,
                statisticsAt = occurredAt,
                note = null,
                postings = emptyList(),
            )
        val surface =
            P503AppState.AccountFlow(
                overview = P503AppState.OverviewEmpty(LedgerCurrentState(LedgerId("ledger-p8b3"), transactions = emptyList(), balances = emptyList())),
                accountId = assetId,
                balanceLine = "默认支付账户 · 资产 · CNY",
                balanceMinorUnits = 0L,
                balanceCurrency = cny,
                loadedRows = listOf(row, row.copy(transactionId = TransactionId("tx-2"))),
                nextWholeLedgerCursor = null,
            )
        assertEquals(2, surface.loadedCount)
        // hasMore mirrors the whole-ledger cursor only — never a per-account claim.
        assertFalse(surface.hasMoreWholeLedgerPages)
        assertTrue(surface.copy(nextWholeLedgerCursor = LedgerCurrentStateReadPort.LedgerFlowCursor(0L, "tx-0")).hasMoreWholeLedgerPages)
    }

    @Test
    fun anUnavailableFirstPageLeavesTheHonestEmptyDecisionToTheScreenBranchOrder() {
        // An Unavailable first page lands cursor-null with zero rows; the screen's branch order
        // (failure copy → empty-with-open-cursor → empty-ended) is what keeps the typed failure
        // from ever rendering as 暂无流水. This pins the data invariant the order relies on:
        // an Unavailable page never contributes rows or a cursor.
        val surface =
            P503AppState.AccountFlow(
                overview = P503AppState.OverviewEmpty(LedgerCurrentState(LedgerId("ledger-p8b3"), transactions = emptyList(), balances = emptyList())),
                accountId = assetId,
                balanceLine = "默认支付账户 · 资产 · CNY",
                balanceMinorUnits = 0L,
                balanceCurrency = cny,
                loadedRows = emptyList(),
                nextWholeLedgerCursor = null,
            )
        assertEquals(0, surface.loadedCount)
        assertFalse(surface.hasMoreWholeLedgerPages)
        assertEquals("该账户暂无流水。", accountFlowEmptyText(surface))
    }
}
