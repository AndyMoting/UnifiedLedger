package com.unifiedledger.ui

import com.unifiedledger.application.CreationEntry
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.P806AccountCursor
import com.unifiedledger.application.P806AccountPostingRow
import com.unifiedledger.application.P806SnapshotFailure
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
 * account-flow surface's face/footer/count decisions are honest (P8-06 batch b: the explicit
 * six faces with 「N 条账户变动」 and the occurred-at Shanghai local 「资金发生日期」 row axis; a
 * loading or failed request keeps the previous payload labelled as old context, never a new
 * zero; a typed failure renders its own copy and retry). Pure functions — no Compose runtime.
 */
class P8B3SecondaryNavPresentationTest {
    private val cny = CurrencyUnit("CNY", 2)
    private val assetId = AccountId("account-asset")
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

    // ---- 件 3: the account-flow presentation decisions (P8-06 batch b) ----

    private fun flowRow(
        occurredAt: Instant = Instant.parse("2026-11-05T18:30:00Z"),
        note: String? = "工作午餐",
        amountMinor: Long = -3_200L,
    ): P806AccountPostingRow =
        P806AccountPostingRow(
            postingId = PostingId("posting-a"),
            transactionId = TransactionId("tx-1"),
            occurredAt = occurredAt,
            note = note,
            kind = TransactionKind.EXPENSE,
            amount = Money.ofMinor(amountMinor, cny),
        )

    private fun surface(
        rows: List<P806AccountPostingRow> = emptyList(),
        totalCount: Int? = null,
        nextCursor: P806AccountCursor? = null,
        rowsLoading: Boolean = false,
        failure: P806SnapshotFailure? = null,
        unwired: Boolean = false,
    ): P503AppState.AccountFlow =
        P503AppState.AccountFlow(
            overview = P503AppState.OverviewEmpty(LedgerCurrentState(LedgerId("ledger-p8b3"), transactions = emptyList(), balances = emptyList())),
            accountId = assetId,
            balanceLine = "默认支付账户 · 资产 · CNY",
            balanceMinorUnits = 0L,
            balanceCurrency = cny,
            loadedRows = rows,
            totalCount = totalCount,
            nextCursor = nextCursor,
            rowsLoading = rowsLoading,
            failure = failure,
            unwired = unwired,
        )

    @Test
    fun theRowHeadlineCarriesTheFundsDateInShanghaiLocalTime() {
        // 2026-11-05T18:30:00Z is 2026-11-06 02:30 in Asia/Shanghai: the row must show the local
        // funding day, not the UTC day — the same axis the P806 account page is sorted by.
        assertEquals("资金发生日期 2026-11-06 · 工作午餐", accountFlowRowHeadlineText(flowRow()))
        assertEquals("资金发生日期 2026-11-06 · EXPENSE", accountFlowRowHeadlineText(flowRow(note = null)))
    }

    @Test
    fun theRowAccessibilityLabelUsesTheSameFundsDateAxisAndTheExactLegAmount() {
        val label = accountFlowRowContentDescription(flowRow(), "默认支付账户")
        assertTrue(label.startsWith("EXPENSE，资金发生日期 2026-11-06，备注 工作午餐；"))
        assertTrue(label.contains("默认支付账户 -32.00 CNY"))
        // The bare account id never reaches TalkBack when the name is known.
        assertFalse(label.contains("asset"))
    }

    @Test
    fun theRowAmountKeepsTheExactSignedPostingAmountAtTheLongMinEnd() {
        // CNY precision 2: |Long.MIN_VALUE| is one minor unit past Long.MAX_VALUE, which the shared
        // formatMinorUnits substitutes and would show as ...58.07. The row must carry the exact
        // posting amount ...58.08 in the visible line's text and in its contentDescription (V-8:
        // the shown amount equals the account posting amount).
        val extreme = flowRow(amountMinor = Long.MIN_VALUE)
        assertEquals("默认支付账户 -92233720368547758.08 CNY", accountFlowRowAmountText(extreme, "默认支付账户"))
        assertEquals(
            "EXPENSE，资金发生日期 2026-11-06，备注 工作午餐；默认支付账户 -92233720368547758.08 CNY",
            accountFlowRowContentDescription(extreme, "默认支付账户"),
        )
        // Ordinary output is the control: every other value is unchanged.
        assertEquals("默认支付账户 -32.00 CNY", accountFlowRowAmountText(flowRow(), "默认支付账户"))
    }

    @Test
    fun theCountUsesTheAccountMovementWordingAndOnlyASuccessRendersIt() {
        assertEquals("3 条账户变动", accountFlowCountText(3))
        // The count is the snapshot's exact posting count — the rows on screen may be fewer only
        // while a later page is loading, and a never-loaded surface has no count at all.
        val loaded = surface(rows = listOf(flowRow()), totalCount = 3, nextCursor = P806AccountCursor(1_000L, "posting-a"))
        assertEquals(3, loaded.totalCount)
        assertNull(surface().totalCount)
    }

    @Test
    fun theFailureCopyIsTypedPerReasonAndNeverAZero() {
        val unavailable = accountFlowFailureText(P806SnapshotFailure.Unavailable)
        val catalogAdvanced = accountFlowFailureText(P806SnapshotFailure.CatalogVersionMismatch)
        assertTrue(unavailable.contains("重试"))
        assertTrue(catalogAdvanced.contains("从第一页重试"))
        assertEquals(
            "账户目录引用不一致（缺账户 / 跨账本 / 币种不符），不可用。",
            accountFlowFailureText(P806SnapshotFailure.CatalogReference(unresolvedAccount = 1L, crossLedgerAccount = 0L, currencyMismatch = 0L)),
        )
        assertEquals(
            "存在无法解析的资金发生时间，账户流水不可用。",
            accountFlowFailureText(P806SnapshotFailure.OccurredAtUnparseable),
        )
        assertEquals(
            4,
            setOf(
                unavailable,
                catalogAdvanced,
                accountFlowFailureText(P806SnapshotFailure.InvalidState),
                accountFlowFailureText(P806SnapshotFailure.MissingProjection),
            ).size,
        )
    }

    @Test
    fun theBodyFacesAreExplicitAndAFailureNeverReadsAsAnEmptyAccount() {
        assertEquals(AccountFlowBodyFace.NOT_LOADED, accountFlowBodyFace(surface()))
        assertEquals(AccountFlowBodyFace.LOADING, accountFlowBodyFace(surface(rowsLoading = true)))
        assertEquals(AccountFlowBodyFace.FAILED, accountFlowBodyFace(surface(failure = P806SnapshotFailure.Unavailable)))
        assertEquals(AccountFlowBodyFace.EMPTY, accountFlowBodyFace(surface(totalCount = 0)))
        assertEquals(AccountFlowBodyFace.LIST, accountFlowBodyFace(surface(rows = listOf(flowRow()), totalCount = 1)))
        assertEquals(AccountFlowBodyFace.UNWIRED, accountFlowBodyFace(surface(unwired = true)))
        // A loading continuation with a previous success stays the LIST face (rows + count kept),
        // never the empty copy and never a fabricated zero.
        val loadingMore = surface(rows = listOf(flowRow()), totalCount = 2, nextCursor = P806AccountCursor(1_000L, "posting-a"), rowsLoading = true)
        assertEquals(AccountFlowBodyFace.LIST, accountFlowBodyFace(loadingMore))
        assertEquals("以下为上次成功读取的内容，可能已过期。", accountFlowOldContextText(loadingMore))
        assertNull(accountFlowOldContextText(surface(rows = listOf(flowRow()), totalCount = 2)))
        // The empty copy belongs to the empty SUCCESS only.
        assertEquals("该账户暂无流水。", ACCOUNT_FLOW_EMPTY_TEXT)
        assertEquals("尚未加载账户流水。", ACCOUNT_FLOW_NOT_LOADED_TEXT)
        assertEquals("账户流水不可用（本版本未接线）。", ACCOUNT_FLOW_UNWIRED_TEXT)
    }

    @Test
    fun theNotLoadedFaceOffersAnExplicitLoadAction() {
        // A surface whose first read never landed (no success, no failure) is NOT_LOADED and owns a
        // real, explicitly labelled action — the screen renders it with the existing request handler,
        // so nothing is re-read automatically.
        assertEquals(AccountFlowBodyFace.NOT_LOADED, accountFlowBodyFace(surface()))
        assertEquals("加载账户流水", accountFlowLoadActionLabel(AccountFlowBodyFace.NOT_LOADED))
        assertEquals(ACCOUNT_FLOW_LOAD_ACTION_TEXT, accountFlowLoadActionLabel(AccountFlowBodyFace.NOT_LOADED))
        // Every other face leaves the action to its own affordance (the footer's paging/retry) or
        // offers nothing here; the empty success does not grow a load button.
        assertNull(accountFlowLoadActionLabel(AccountFlowBodyFace.LOADING))
        assertNull(accountFlowLoadActionLabel(AccountFlowBodyFace.FAILED))
        assertNull(accountFlowLoadActionLabel(AccountFlowBodyFace.EMPTY))
        assertNull(accountFlowLoadActionLabel(AccountFlowBodyFace.LIST))
        assertNull(accountFlowLoadActionLabel(AccountFlowBodyFace.UNWIRED))
    }

    @Test
    fun theFooterFollowsTheP806CursorAndTheFailureMarker() {
        val base = surface(totalCount = 1, nextCursor = P806AccountCursor(1_000L, "posting-a"))
        assertEquals(FlowFooterState.FETCH_MORE, accountFlowFooterState(base))
        assertEquals(FlowFooterState.LOADING, accountFlowFooterState(base.copy(rowsLoading = true)))
        assertEquals(FlowFooterState.RETRY, accountFlowFooterState(base.copy(failure = P806SnapshotFailure.Unavailable)))
        // The P806 cursor exhausted: the honest end.
        assertEquals(FlowFooterState.END, accountFlowFooterState(base.copy(nextCursor = null)))
    }
}
