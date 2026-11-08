package com.unifiedledger.ui

import com.unifiedledger.application.LedgerCurrentStateReadPort
import com.unifiedledger.application.LedgerFlowPageResult
import com.unifiedledger.application.RecycleBinResult
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P8-00 b3 (V-5) state-machine extension tests: the account-flow event family has its designed
 * effect only on OverviewEmpty/AccountFlow and is absorbed in EVERY other state (table 6.2a —
 * zero ISE anywhere); the scroll round-trip proves 「离开时位 → 返回后同位」 at reducer level; the
 * late-write guard drops a superseded/duplicated more-page landing; ListScrollRestored is the
 * one-shot consume. Every pre-existing unlisted combination stays ISE (G-B).
 */
class P503AccountFlowReducerTest {
    private val ledgerId = LedgerId("ledger-account-flow-test")
    private val cny = CurrencyUnit("CNY", 2)
    private val paymentAccountId = AccountId("asset-payment-local")
    private val otherAccountId = AccountId("asset-other")
    private val occurredAt = Instant.parse("2026-03-15T02:00:00Z")
    private val fixedClock = com.unifiedledger.application.LedgerClock { Instant.parse("2026-09-15T02:00:00Z") }
    private val reducer = P503ReducerImpl(com.unifiedledger.application.ParseManualExpenseAmount(), cny, ledgerClock = fixedClock)

    private val oneTransactionState =
        com.unifiedledger.application.LedgerCurrentState(
            ledgerId = ledgerId,
            transactions = emptyList(),
            balances = emptyList(),
        )

    private fun overview(scroll: ListScrollPosition? = null): P503AppState.OverviewEmpty =
        P503AppState.OverviewEmpty(
            state = oneTransactionState,
            selectedTab = P503Tab.ACCOUNTS,
            originListScroll = scroll,
        )

    private fun flowRow(
        transactionId: String,
        vararg postings: Posting,
    ): com.unifiedledger.application.LedgerEntryRow =
        com.unifiedledger.application.LedgerEntryRow(
            transactionId = TransactionId(transactionId),
            currentVersionId = TransactionVersionId("version-$transactionId"),
            kind = TransactionKind.EXPENSE,
            occurredAt = occurredAt,
            statisticsAt = occurredAt,
            note = null,
            postings = postings.toList(),
        )

    private fun page(
        vararg rows: com.unifiedledger.application.LedgerEntryRow,
        cursor: LedgerCurrentStateReadPort.LedgerFlowCursor? = null,
    ): LedgerFlowPageResult = LedgerFlowPageResult.Success(LedgerCurrentStateReadPort.LedgerFlowPage(rows.toList(), cursor))

    private fun paymentLeg(amountMinor: Long = -3_580L): Posting = Posting(PostingId("posting-payment"), paymentAccountId, Money.ofMinor(amountMinor, cny))

    private fun otherLeg(amountMinor: Long = 3_580L): Posting = Posting(PostingId("posting-other"), otherAccountId, Money.ofMinor(amountMinor, cny))

    private fun selectEvent(
        cursor: LedgerCurrentStateReadPort.LedgerFlowCursor? = defaultCursor(),
        firstPage: LedgerFlowPageResult = page(flowRow("tx-1", paymentLeg(), otherLeg()), flowRow("tx-2", otherLeg()), cursor = cursor),
    ): P503UiEvent.SelectAccountFlow =
        P503UiEvent.SelectAccountFlow(
            accountId = paymentAccountId,
            balanceLine = "默认支付账户 · 资产 · CNY",
            balanceMinorUnits = -3_580L,
            balanceCurrency = cny,
            firstPage = firstPage,
            originListScroll = ListScrollPosition(3, 24),
        )

    /** A non-terminal walk cursor (the default first page keeps 加载更多 open). */
    private fun defaultCursor(): LedgerCurrentStateReadPort.LedgerFlowCursor = LedgerCurrentStateReadPort.LedgerFlowCursor(123L, "tx-2")

    private fun enterAccountFlow(
        source: P503AppState.OverviewEmpty = overview(),
        event: P503UiEvent.SelectAccountFlow = selectEvent(),
    ): P503AppState.AccountFlow = assertIs<P503AppState.AccountFlow>(reducer.reduce(source, event))

    // ---- SelectAccountFlow (effect on OverviewEmpty, absorbed everywhere else) ----

    @Test
    fun selectAccountFlowEntersWithTheFilteredFirstPageAndTheSampledScroll() {
        val entered = enterAccountFlow()
        assertEquals(paymentAccountId, entered.accountId)
        // 诚实过滤: only rows whose postings include the account (tx-2 is the other account's
        // alone and must not render).
        assertEquals(listOf("tx-1"), entered.loadedRows.map { it.transactionId.value })
        assertEquals(1, entered.loadedCount)
        assertEquals("默认支付账户 · 资产 · CNY", entered.balanceLine)
        assertEquals(-3_580L, entered.balanceMinorUnits)
        assertTrue(entered.hasMoreWholeLedgerPages)
        // The sampled ACCOUNTS scroll rides the carried overview (the source held none).
        assertEquals(ListScrollPosition(3, 24), entered.overview.originListScroll)
        assertEquals(P503Tab.ACCOUNTS, entered.overview.selectedTab)
    }

    @Test
    fun selectAccountFlowIsAbsorbedInEveryOtherState() {
        everyNonOverviewState { state ->
            assertSame(state, reducer.reduce(state, selectEvent()))
        }
        // The guard absorbs a re-entry dispatch on the flow surface itself too (only
        // OverviewEmpty opens it).
        val entered = enterAccountFlow()
        assertSame(entered, reducer.reduce(entered, selectEvent()))
    }

    @Test
    fun anUnavailableFirstPageLandsAsTheFailureMarkerNeverAsAnEmptyAccount() {
        // F-Q1 fix round: the typed Unavailable FIRST page rides the state as rowsLoadFailed —
        // the surface renders the Unavailable face (with its retry), never 「该账户暂无流水」.
        val entered =
            assertIs<P503AppState.AccountFlow>(
                reducer.reduce(overview(), selectEvent(firstPage = LedgerFlowPageResult.Unavailable)),
            )
        assertEquals(emptyList(), entered.loadedRows)
        assertEquals(0, entered.loadedCount)
        assertTrue(entered.rowsLoadFailed)
        assertTrue(!entered.hasMoreWholeLedgerPages)
        assertTrue(!entered.rowsLoading)
    }

    // ---- CloseAccountFlow/Back (effect on AccountFlow, absorbed everywhere else) ----

    @Test
    fun closeAccountFlowReturnsToTheExactOverviewWithTheSampledScrollPosition() {
        // 返回保滚动位: the entry sampled a position (the capture rides the open event), so the
        // close returns an overview that EQUALS the pre-entry one plus the position (copy, not
        // the same instance — the instance moved once, synchronously, at entry).
        val source = overview()
        val entered = enterAccountFlow(source)
        val closed = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(entered, P503UiEvent.CloseAccountFlow))
        assertEquals(ListScrollPosition(3, 24), closed.originListScroll)
        assertEquals(P503Tab.ACCOUNTS, closed.selectedTab)
        assertSame(source.state, closed.state)
        assertEquals(source.selectedTab, closed.selectedTab)
    }

    @Test
    fun backOnTheAccountFlowClosesWithTheSameSemantics() {
        // Entry with a FRESH sample (no prior capture): the carried overview is the entry copy
        // (the instance moved once, synchronously, when the position was stored); the close
        // returns it unchanged with the position intact (value identity — the position, tab and
        // payload are the pre-close ones).
        val source = overview()
        val entered = enterAccountFlow(source)
        assertEquals(ListScrollPosition(3, 24), entered.overview.originListScroll)
        val closed = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(entered, P503UiEvent.Back))
        assertSame(entered.overview, closed)
        assertEquals(P503Tab.ACCOUNTS, closed.selectedTab)
    }

    @Test
    fun closeAccountFlowIsAbsorbedInEveryOtherState() {
        everyNonOverviewState { state ->
            assertSame(state, reducer.reduce(state, P503UiEvent.CloseAccountFlow))
        }
        // The overview absorbs it too (there is no flow surface to close).
        val source = overview()
        assertSame(source, reducer.reduce(source, P503UiEvent.CloseAccountFlow))
    }

    // ---- RequestAccountFlowMorePage / AccountFlowMorePageLanded (the single-flight + late-write guard) ----

    @Test
    fun requestMorePageAdvancesTheTokenAndSetsTheMarkerOnce() {
        val entered = enterAccountFlow()
        val requesting = assertIs<P503AppState.AccountFlow>(reducer.reduce(entered, P503UiEvent.RequestAccountFlowMorePage))
        assertTrue(requesting.rowsLoading)
        assertEquals(1, requesting.pageRequestToken)
        // 一次一页: a duplicate request is absorbed and does not advance the token again.
        val duplicate = assertIs<P503AppState.AccountFlow>(reducer.reduce(requesting, P503UiEvent.RequestAccountFlowMorePage))
        assertEquals(requesting, duplicate)
    }

    @Test
    fun requestMorePageIsAbsorbedWithoutAnOpenCursor() {
        // cursor = null, nothing failed → the whole-ledger walk ended; the request is absorbed
        // with zero change.
        val ended = enterAccountFlow(event = selectEvent(cursor = null))
        assertSame(ended, reducer.reduce(ended, P503UiEvent.RequestAccountFlowMorePage))
        // A failed FIRST read also holds no cursor, but its request is NOT absorbed — the retry
        // re-runs the first page (F-Q2 fix round): the token advances and the in-flight marker
        // sets (the failure marker is the landing's to clear or re-set).
        val failed = enterAccountFlow(event = selectEvent(firstPage = LedgerFlowPageResult.Unavailable))
        val retrying = assertIs<P503AppState.AccountFlow>(reducer.reduce(failed, P503UiEvent.RequestAccountFlowMorePage))
        assertTrue(retrying.rowsLoading)
        assertTrue(retrying.rowsLoadFailed)
        assertEquals(1, retrying.pageRequestToken)
    }

    @Test
    fun aLandingWithTheCurrentTokenAppendsOnlyTheAccountRows() {
        val entered = enterAccountFlow()
        val requesting = assertIs<P503AppState.AccountFlow>(reducer.reduce(entered, P503UiEvent.RequestAccountFlowMorePage))
        val landed =
            assertIs<P503AppState.AccountFlow>(
                reducer.reduce(
                    requesting,
                    P503UiEvent.AccountFlowMorePageLanded(
                        requestToken = 1,
                        result = page(flowRow("tx-3", paymentLeg()), flowRow("tx-4", otherLeg())),
                    ),
                ),
            )
        assertEquals(listOf("tx-1", "tx-3"), landed.loadedRows.map { it.transactionId.value })
        assertEquals(2, landed.loadedCount)
        assertTrue(!landed.rowsLoading)
        assertTrue(!landed.rowsLoadFailed)
        // The walked page carried no cursor: the honest whole-ledger end.
        assertTrue(!landed.hasMoreWholeLedgerPages)
    }

    @Test
    fun aSupersededOrDuplicatedLandingIsDroppedWhole() {
        val entered = enterAccountFlow()
        val requesting = assertIs<P503AppState.AccountFlow>(reducer.reduce(entered, P503UiEvent.RequestAccountFlowMorePage))
        // An old-token landing (the read was superseded by a newer request, or duplicated from an
        // older dispatch) can never append — the token gate drops it whole and keeps the marker.
        val stale =
            assertIs<P503AppState.AccountFlow>(
                reducer.reduce(
                    requesting,
                    P503UiEvent.AccountFlowMorePageLanded(requestToken = 0, result = page(flowRow("tx-9", paymentLeg()))),
                ),
            )
        assertEquals(requesting.loadedRows, stale.loadedRows)
        assertTrue(stale.rowsLoading)
        // The current-token landing applies.
        val landed =
            assertIs<P503AppState.AccountFlow>(
                reducer.reduce(
                    stale,
                    P503UiEvent.AccountFlowMorePageLanded(requestToken = 1, result = page(flowRow("tx-5", paymentLeg()))),
                ),
            )
        assertEquals(listOf("tx-1", "tx-5"), landed.loadedRows.map { it.transactionId.value })
    }

    @Test
    fun aLandingWithoutAnInFlightMarkerIsDropped() {
        val entered = enterAccountFlow()
        assertSame(entered, reducer.reduce(entered, P503UiEvent.AccountFlowMorePageLanded(requestToken = 0, result = page(flowRow("tx-9", paymentLeg())))))
    }

    @Test
    fun aFailedMorePageKeepsTheLoadedRowsAndSurfacesTheRetry() {
        val entered = enterAccountFlow()
        val requesting = assertIs<P503AppState.AccountFlow>(reducer.reduce(entered, P503UiEvent.RequestAccountFlowMorePage))
        val failed =
            assertIs<P503AppState.AccountFlow>(
                reducer.reduce(requesting, P503UiEvent.AccountFlowMorePageLanded(requestToken = 1, result = LedgerFlowPageResult.Unavailable)),
            )
        // Fail-closed: the loaded rows stay visible beside the retry; the cursor is kept.
        assertEquals(entered.loadedRows, failed.loadedRows)
        assertTrue(failed.rowsLoadFailed)
        assertTrue(!failed.rowsLoading)
        assertTrue(failed.hasMoreWholeLedgerPages)
    }

    @Test
    fun aFailedFirstPageRetryLandingReplacesTheFailureMarker() {
        // F-Q2 fix round end-to-end at reducer level: the first-read failure's retry advances the
        // token; a SUCCESS landing applies the (now present) first page, clears the failure
        // marker and opens the walk; a second Unavailable landing re-sets the marker instead of
        // clearing it into an empty ended claim.
        val failed = enterAccountFlow(event = selectEvent(firstPage = LedgerFlowPageResult.Unavailable))
        val retrying = assertIs<P503AppState.AccountFlow>(reducer.reduce(failed, P503UiEvent.RequestAccountFlowMorePage))
        assertTrue(retrying.rowsLoading)
        assertTrue(retrying.rowsLoadFailed)
        val landed =
            assertIs<P503AppState.AccountFlow>(
                reducer.reduce(
                    retrying,
                    P503UiEvent.AccountFlowMorePageLanded(requestToken = 1, result = page(flowRow("tx-1", paymentLeg()), cursor = defaultCursor())),
                ),
            )
        assertEquals(listOf("tx-1"), landed.loadedRows.map { it.transactionId.value })
        assertTrue(!landed.rowsLoadFailed)
        assertTrue(!landed.rowsLoading)
        assertTrue(landed.hasMoreWholeLedgerPages)
        // A retry that fails again re-sets the marker (never a silent success shape): a fresh
        // request advances the token to 2, then the Unavailable landing applies against it.
        val requestingAgain = assertIs<P503AppState.AccountFlow>(reducer.reduce(landed, P503UiEvent.RequestAccountFlowMorePage))
        val failedAgain =
            assertIs<P503AppState.AccountFlow>(
                reducer.reduce(
                    requestingAgain,
                    P503UiEvent.AccountFlowMorePageLanded(requestToken = 2, result = LedgerFlowPageResult.Unavailable),
                ),
            )
        assertTrue(failedAgain.rowsLoadFailed)
        assertEquals(listOf("tx-1"), failedAgain.loadedRows.map { it.transactionId.value })
    }

    @Test
    fun requestAndLandedAreAbsorbedInEveryOtherState() {
        everyNonOverviewState { state ->
            assertSame(state, reducer.reduce(state, P503UiEvent.RequestAccountFlowMorePage))
            assertSame(state, reducer.reduce(state, P503UiEvent.AccountFlowMorePageLanded(requestToken = 1, result = page(flowRow("tx-9", paymentLeg())))))
        }
    }

    // ---- ListScrollRestored (the one-shot consume) ----

    @Test
    fun listScrollRestoredClearsTheOverviewPositionExactlyOnce() {
        val consumed = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(overview(scroll = ListScrollPosition(4, 12)), P503UiEvent.ListScrollRestored))
        assertNull(consumed.originListScroll)
        // The consume is idempotent once nothing is held (same instance, no churn).
        assertSame(consumed, reducer.reduce(consumed, P503UiEvent.ListScrollRestored))
    }

    @Test
    fun listScrollRestoredIsAbsorbedInEveryOtherState() {
        everyNonOverviewState { state ->
            assertSame(state, reducer.reduce(state, P503UiEvent.ListScrollRestored))
        }
    }

    // ---- the scroll round-trip across a detail detour (the mechanism's end-to-end reducer proof) ----

    @Test
    fun theScrollPositionSurvivesADetailDetourAndIsConsumedOnce() {
        // Leave with a position → return via CloseTransactionDetail with the same position →
        // consume clears it exactly once. The same trip works through the account-flow surface
        // (covered by closeAccountFlowReturnsToTheExactOverviewWithTheSampledScrollPosition).
        val detailResult = com.unifiedledger.application.TransactionDetailResult.NotFound
        val source = overview()
        val entered =
            assertIs<P503AppState.TransactionDetail>(
                reducer.reduce(
                    source,
                    P503UiEvent.SelectTransaction(TransactionId("tx-1"), detailResult, homeFlowScroll = ListScrollPosition(9, 3)),
                ),
            )
        assertEquals(ListScrollPosition(9, 3), entered.overview.originListScroll)
        val closed = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(entered, P503UiEvent.CloseTransactionDetail))
        assertEquals(ListScrollPosition(9, 3), closed.originListScroll)
        assertSame(source.state, closed.state)
        // A budget landing behind the surface must not lose the position (the carried overview
        // is updated in place).
        val refreshed =
            assertIs<P503AppState.TransactionDetail>(
                reducer.reduce(
                    entered,
                    P503UiEvent.BudgetMonthLanded(com.unifiedledger.application.BudgetMonthViewResult.Unavailable),
                ),
            )
        assertEquals(ListScrollPosition(9, 3), refreshed.overview.originListScroll)
    }

    // ---- locked ISE behavior (G-B: pre-existing unlisted combinations on the new state stay programming errors) ----

    @Test
    fun preExistingUnlistedCombinationsOnAccountFlowRemainIllegalState() {
        val entered = enterAccountFlow()
        assertFailsWith<IllegalStateException> { reducer.reduce(entered, P503UiEvent.RetryRefresh) }
        assertFailsWith<IllegalStateException> { reducer.reduce(entered, P503UiEvent.StartNewExpense) }
        assertFailsWith<IllegalStateException> { reducer.reduce(entered, P503UiEvent.SelectTab(P503Tab.HOME)) }
        assertFailsWith<IllegalStateException> { reducer.reduce(entered, P503UiEvent.SelectMonth(kotlinx.datetime.YearMonth(2026, 3))) }
    }

    // ---- helpers ----

    /**
     * Runs the block against every state other than OverviewEmpty/AccountFlow — the FULL
     * `allStates()` enumeration (the F-Q4 fix round; the BackupExport P3-I precedent: an omitted
     * state's absorption is asserted by nobody). The flow surfaces themselves are covered by the
     * per-test re-entry assertions above (the guard absorbs a re-entry dispatch there too).
     */
    private fun everyNonOverviewState(block: (P503AppState) -> Unit) {
        val draft = com.unifiedledger.application.ExpenseDraft(paymentAccountId, null, "35.80", occurredAt)
        val requestId = com.unifiedledger.application.RequestId("request-1")
        val transactionId = TransactionId("tx-1")
        val states =
            listOf<P503AppState>(
                // P503AppState subtypes, the allStates() shape (F-Q4 fix round: the helper
                // enumerates every non-family subtype — the state family's 27 subtypes minus its
                // own OverviewEmpty/AccountFlow = 25 subtypes; TransactionDetail and
                // InfrastructureFailure appear in two field-shape variants each, 27 entries).
                P503AppState.Ready(),
                P503AppState.TransactionDetail(
                    overview = overview(),
                    originTab = P503Tab.HOME,
                    transactionId = transactionId,
                    detail = com.unifiedledger.application.TransactionDetailResult.NotFound,
                ),
                P503AppState.TransactionDetail(
                    overview = overview(),
                    originTab = P503Tab.HOME,
                    transactionId = transactionId,
                    detail = com.unifiedledger.application.TransactionDetailResult.NotFound,
                    annotationHistory = emptyList(),
                    versionHistory = com.unifiedledger.application.TransactionVersionHistoryResult.Unavailable,
                ),
                P503AppState.ImportCandidateDetail(
                    overview = overview(),
                    candidateId = com.unifiedledger.application.ImportCandidateId("candidate-1"),
                    detail = com.unifiedledger.application.ImportCandidateDetailResult.Absent,
                    duplicates = com.unifiedledger.application.ImportDuplicateReviewsResult.NoDuplicates,
                    form = ImportDecisionDraft(),
                ),
                P503AppState.ImportBatchConfirm(overview()),
                P503AppState.ImportBatchSubmitting(overview(), "2026-09-14T08:00:00Z", emptyList()),
                P503AppState.TransactionEdit(overview = overview(), origin = origin()),
                P503AppState.TransferEdit(overview = overview(), origin = transferOrigin()),
                P503AppState.VoidConfirm(overview = overview(), transactionId = transactionId),
                P503AppState.RecycleBin(
                    overview = overview(),
                    rows = RecycleBinResult.Success(emptyList()),
                ),
                P503AppState.TransactionAnnotationEdit(
                    overview = overview(),
                    intent =
                        TransactionAnnotationEditIntent(
                            transactionId = transactionId,
                            expectedAnnotationRevision = 0L,
                            expectedCurrentVersionId = TransactionVersionId("version-1"),
                        ),
                ),
                P503AppState.BackupExport(overview = overview()),
                P503AppState.BackupRestore(overview = overview()),
                P503AppState.RestoreSessionTerminal(BackupRestoreRecoveryCause.RollbackReopenFailed),
                P503AppState.BudgetConfig(
                    overview = overview(),
                    scope = com.unifiedledger.domain.BudgetScope.Total,
                    month = kotlinx.datetime.YearMonth(2026, 3),
                    revision = 0L,
                    closed = false,
                    limitMinorUnits = null,
                ),
                P503AppState.TagMerchantFilter(overview(), kotlinx.datetime.YearMonth(2026, 1)),
                P503AppState.Editing(draft, requestId),
                P503AppState.AwaitingConfirmation(draft, requestId),
                P503AppState.Submitting(draft, requestId),
                P503AppState.Created,
                P503AppState.NoChange,
                P503AppState.Recovered,
                P503AppState.RequestIdentityConflict(draft, requestId),
                P503AppState.DomainRejected(draft, requestId),
                P503AppState.InfrastructureFailure(InfrastructureFailureContext.SUBMISSION, draft, requestId),
                P503AppState.InfrastructureFailure(InfrastructureFailureContext.READ),
                P503AppState.UnknownCommit(draft, requestId),
            )
        states.forEach(block)
    }

    // The TransactionEdit-origin fixture (the BackupExport test's shape, inlined).
    private fun origin(): TransactionEditOrigin =
        TransactionEditOrigin(
            transactionId = TransactionId("tx-1"),
            currentVersionId = TransactionVersionId("version-1"),
            note = "old note",
            statisticsAt = occurredAt,
            amountText = "100.00",
            categoryId = com.unifiedledger.domain.CategoryId("category-food"),
            fundingAccountId = paymentAccountId,
        )

    private fun transferOrigin(): TransferCorrectionOrigin =
        TransferCorrectionOrigin(
            transactionId = TransactionId("tx-1"),
            currentVersionId = TransactionVersionId("version-1"),
            note = null,
            statisticsAt = occurredAt,
            currency = cny,
            sourceAccountId = paymentAccountId,
            destinationAccountId = AccountId("asset-bank"),
            destinationCreditMinorUnits = 10_000L,
            feeMinorUnits = 0L,
            feeCategoryId = null,
        )
}
