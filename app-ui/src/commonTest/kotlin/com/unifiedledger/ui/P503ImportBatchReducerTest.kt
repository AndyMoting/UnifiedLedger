package com.unifiedledger.ui

import com.unifiedledger.application.ImportCandidateDetailResult
import com.unifiedledger.application.ImportCandidateDetailRow
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportReceipt
import com.unifiedledger.application.ImportRequestId
import com.unifiedledger.application.ImportReviewRow
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.TransactionDetailResult
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-04.D state-machine extension tests (D-146; spec sections 3.2.3/3.3.2/6.1/6.2, table 6.2a):
 * every batch event has its designed effect only in its designed state and is absorbed in every
 * other state; no new event throws anywhere; every pre-existing ISE path stays locked (G-B,
 * including `Exit` on the new states, spec section 6.3, and system Back on the dispatch state —
 * intercepted by the host guards and never dispatched, so the unlisted combination stays an ISE
 * exactly like the manual `Submitting`). The authorization carries the host-minted snapshot ONCE
 * (Q09.4: the sample and per-item requestIds ride the event; the reducer keeps them verbatim —
 * Resume and the replay check reuse the SAME values); an Unknown item pauses the batch, the
 * check resolves it, and Resume/Abandon are the only exits; Abandon dissolves the snapshot in
 * ONE transition with no hidden intermediate UI state (义务③).
 */
class P503ImportBatchReducerTest {
    private val ledgerId = LedgerId("ledger-local-test")
    private val cny = CurrencyUnit("CNY", 2)
    private val reducer: P503Reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)
    private val emptyState = LedgerCurrentState(ledgerId, transactions = emptyList(), balances = emptyList())
    private val occurredAt = Instant.parse("2026-03-15T02:00:00Z")
    private val confirmedAt = "2026-09-14T08:00:00Z"

    // ---- fixtures ----

    private val pendingRow = importRow("candidate-pending")
    private val retainableRow =
        importRow(
            "candidate-retainable",
            candidateStatus = "pending_confirmation",
            duplicateStatus = com.unifiedledger.application.ImportDuplicateStatus.CONFIRMED_DISTINCT,
        )
    private val incompleteRow =
        importRow(
            "candidate-incomplete",
            candidateStatus = "incomplete",
            candidateKind = "transfer_flow_missing_leg",
            fundingState = ImportFundingState.UNRESOLVED,
            completeness = ImportCompleteness.VALID_INCOMPLETE,
        )

    private fun importRow(
        candidateId: String,
        candidateStatus: String = "pending_confirmation",
        candidateKind: String = "ordinary_flow",
        duplicateStatus: com.unifiedledger.application.ImportDuplicateStatus? = null,
        fundingState: ImportFundingState = ImportFundingState.SETTLED,
        completeness: ImportCompleteness = ImportCompleteness.VALID_COMPLETE,
    ): ImportReviewRow =
        ImportReviewRow(
            candidateId = ImportCandidateId(candidateId),
            candidateKind = candidateKind,
            sourceInputRef = "pick-handle-1",
            amountMinor = 3_580L,
            currencyCode = "CNY",
            currencyPrecision = 2,
            occurredAt = "2026-03-15T02:00:00Z",
            directionToken = "expense",
            statusToken = "trade_success",
            fundingState = fundingState,
            completeness = completeness,
            contentHash = "sha256:fixed-$candidateId",
            candidateStatus = candidateStatus,
            requiresConfirmation = true,
            confidence = "high",
            duplicateStatus = duplicateStatus,
            paymentProfileVariant = null,
            paymentProfileAssetLegKindToken = null,
            paymentProfileCreditLegKindToken = null,
        )

    private fun overview(
        rows: List<ImportReviewRow> = listOf(pendingRow, retainableRow, incompleteRow),
        selection: Set<ImportCandidateId> = emptySet(),
        drafts: Map<ImportCandidateId, ImportDecisionDraft> = emptyMap(),
    ): P503AppState.OverviewEmpty =
        P503AppState.OverviewEmpty(
            state = emptyState,
            selectedTab = P503Tab.IMPORT,
            importReview =
                ImportReviewView(
                    rows = rows,
                    selectedCandidateIds = selection,
                    decisionDrafts = drafts,
                ),
        )

    private fun batchItem(
        candidateId: String,
        requestId: String,
    ): ImportBatchItem = ImportBatchItem(ImportCandidateId(candidateId), ImportRequestId(requestId))

    private fun confirmedReceipt(candidateId: String): ImportReceipt =
        ImportReceipt(
            requestId = ImportRequestId("request-$candidateId"),
            sourceId = null,
            evidenceId = null,
            candidateId = ImportCandidateId(candidateId),
            confirmationId = null,
            transactionId = TransactionId("tx-$candidateId"),
        )

    private fun submitting(
        overviewState: P503AppState.OverviewEmpty =
            overview(
                selection = setOf(ImportCandidateId("candidate-pending"), ImportCandidateId("candidate-retainable")),
            ),
        items: List<ImportBatchSubmittingItem> =
            listOf(
                ImportBatchSubmittingItem(batchItem("candidate-pending", "request-batch-1")),
                ImportBatchSubmittingItem(batchItem("candidate-retainable", "request-batch-2")),
            ),
        confirmedAtValue: String = confirmedAt,
        paused: Boolean = false,
    ): P503AppState.ImportBatchSubmitting =
        P503AppState.ImportBatchSubmitting(
            overview = overviewState,
            confirmedAt = confirmedAtValue,
            items = items,
            dispatchPaused = paused,
        )

    // ---- RequestImportBatchConfirm / CancelImportBatchConfirm (table 6.2a rows) ----

    @Test
    fun requestImportBatchConfirmOpensTheConfirmPageOnlyForANonEmptySelection() {
        val source = overview(selection = setOf(ImportCandidateId("candidate-pending")))
        val opened = assertIs<P503AppState.ImportBatchConfirm>(reducer.reduce(source, P503UiEvent.RequestImportBatchConfirm))
        assertSame(source, opened.overview)

        // 空集 absorbed； a missing projection absorbs too.
        val emptySelection = overview()
        assertSame(emptySelection, reducer.reduce(emptySelection, P503UiEvent.RequestImportBatchConfirm))
        val bare = P503AppState.OverviewEmpty(emptyState)
        assertSame(bare, reducer.reduce(bare, P503UiEvent.RequestImportBatchConfirm))
    }

    @Test
    fun requestImportBatchConfirmFromTheDetailCarriesTheDraftIntoThePage() {
        val draft = ImportDecisionDraft(categoryId = com.unifiedledger.domain.CategoryId("category-food"))
        val detail =
            P503AppState.ImportCandidateDetail(
                overview = overview(selection = setOf(ImportCandidateId("candidate-pending"))),
                candidateId = ImportCandidateId("candidate-pending"),
                detail = ImportCandidateDetailResult.Found(ImportCandidateDetailRow(pendingRow, 1L)),
                duplicates = com.unifiedledger.application.ImportDuplicateReviewsResult.NoDuplicates,
                form = draft,
            )
        val opened = assertIs<P503AppState.ImportBatchConfirm>(reducer.reduce(detail, P503UiEvent.RequestImportBatchConfirm))
        // 携详情决策： the detail's draft is written back into the carried overview (SPEC:283).
        assertEquals(
            draft,
            opened.overview.importReview
                ?.decisionDrafts
                ?.get(ImportCandidateId("candidate-pending")),
        )
    }

    @Test
    fun cancelAndBackReturnTheConfirmPageToThePreservedOverview() {
        val source = overview(selection = setOf(ImportCandidateId("candidate-pending")))
        val confirm = P503AppState.ImportBatchConfirm(source)
        assertSame(source, assertIs<P503AppState.OverviewEmpty>(reducer.reduce(confirm, P503UiEvent.CancelImportBatchConfirm)))
        assertSame(source, assertIs<P503AppState.OverviewEmpty>(reducer.reduce(confirm, P503UiEvent.Back)))
    }

    // ---- AuthorizeImportBatch (授权快照： 单次取样 + 逐项 requestId) ----

    @Test
    fun authorizeBuildsTheSnapshotInRowsOrderWithTheSingleSampleAndPerItemRequestIds() {
        val source =
            overview(
                selection = setOf(ImportCandidateId("candidate-retainable"), ImportCandidateId("candidate-pending"), ImportCandidateId("candidate-selected-unknown")),
            )
        val requestIds =
            mapOf(
                ImportCandidateId("candidate-pending") to ImportRequestId("request-batch-1"),
                ImportCandidateId("candidate-retainable") to ImportRequestId("request-batch-2"),
                ImportCandidateId("candidate-selected-unknown") to ImportRequestId("request-batch-3"),
            )
        val dispatching =
            assertIs<P503AppState.ImportBatchSubmitting>(
                reducer.reduce(P503AppState.ImportBatchConfirm(source), P503UiEvent.AuthorizeImportBatch(confirmedAt, requestIds)),
            )
        assertEquals(confirmedAt, dispatching.confirmedAt)
        assertEquals(false, dispatching.dispatchPaused)
        // Rows order first (pending before retainable), then the selected-but-absent id by value.
        assertEquals(
            listOf("candidate-pending", "candidate-retainable", "candidate-selected-unknown"),
            dispatching.items.map { it.item.candidateId.value },
        )
        assertEquals(ImportRequestId("request-batch-1"), dispatching.items[0].item.requestId)
        assertEquals(ImportRequestId("request-batch-3"), dispatching.items[2].item.requestId)
        assertTrue(dispatching.items.all { it.outcome == null })
        assertSame(source.state, dispatching.overview.state)
    }

    @Test
    fun authorizeWithoutARequestIdForEverySelectedItemAbsorbsDefensively() {
        val source = overview(selection = setOf(ImportCandidateId("candidate-pending")))
        val confirm = P503AppState.ImportBatchConfirm(source)
        val partial = P503UiEvent.AuthorizeImportBatch(confirmedAt, emptyMap())
        assertSame(confirm, reducer.reduce(confirm, partial))
    }

    // ---- ImportItemResult (逐项结果； Unknown 暂停； 全部项终态 → OverviewEmpty(IMPORT)) ----

    @Test
    fun anUnknownOutcomePausesTheBatchAndKeepsTheCheckEntry() {
        val state = submitting()
        val updated =
            assertIs<P503AppState.ImportBatchSubmitting>(
                reducer.reduce(state, P503UiEvent.ImportItemResult(batchItem("candidate-pending", "request-batch-1"), ImportBatchItemOutcome.Unknown)),
            )
        assertTrue(updated.dispatchPaused)
        assertIs<ImportBatchItemOutcome.Unknown>(updated.items[0].outcome)
        // The later item keeps awaiting dispatch (后续派发暂停).
        assertNull(updated.items[1].outcome)
    }

    @Test
    fun aTerminalOutcomeOnTheLastItemLeavesToTheOverviewWithTheRetainedSummary() {
        val state = submitting()
        val first =
            assertIs<P503AppState.ImportBatchSubmitting>(
                reducer.reduce(state, P503UiEvent.ImportItemResult(batchItem("candidate-pending", "request-batch-1"), ImportBatchItemOutcome.Confirmed(confirmedReceipt("candidate-pending")))),
            )
        val second = reducer.reduce(first, P503UiEvent.ImportItemResult(batchItem("candidate-retainable", "request-batch-2"), ImportBatchItemOutcome.Rejected("SPINE_DUPLICATE_NOT_CONFIRMABLE")))
        val left = assertIs<P503AppState.OverviewEmpty>(second)
        assertEquals(P503Tab.IMPORT, left.selectedTab)
        assertSame(state.overview.state, left.state)
        val summary = left.importReview?.batchResult
        assertEquals(confirmedAt, summary?.confirmedAt)
        assertEquals(2, summary?.items?.size)
        assertIs<ImportBatchItemOutcome.Confirmed>(summary?.items?.get(0)?.outcome)
        val rejected = assertIs<ImportBatchItemOutcome.Rejected>(summary?.items?.get(1)?.outcome)
        assertEquals("SPINE_DUPLICATE_NOT_CONFIRMABLE", rejected.code)
        // 保留结果摘要 replaces nothing else: rows/selection/drafts are the authorize-time ones.
        assertSame(state.overview.importReview?.rows, left.importReview?.rows)
    }

    @Test
    fun aResolvedItemOutcomeIsNeverOverwritten() {
        val state = submitting()
        val first =
            assertIs<P503AppState.ImportBatchSubmitting>(
                reducer.reduce(state, P503UiEvent.ImportItemResult(batchItem("candidate-pending", "request-batch-1"), ImportBatchItemOutcome.Confirmed(confirmedReceipt("candidate-pending")))),
            )
        // A spurious late duplicate result for the resolved item cannot relabel it (已成功不
        // 重复标注).
        assertSame(first, reducer.reduce(first, P503UiEvent.ImportItemResult(batchItem("candidate-pending", "request-batch-1"), ImportBatchItemOutcome.Rejected("SPINE_CANDIDATE_NOT_PENDING"))))
        // An unknown item id absorbs too.
        assertSame(first, reducer.reduce(first, P503UiEvent.ImportItemResult(batchItem("candidate-unknown", "request-x"), ImportBatchItemOutcome.Unknown)))
    }

    // ---- Q09.4: 授权单次取样复用 (Resume 后不重取样) ----

    @Test
    fun theSingleClockSampleAndRequestIdsSurviveResumeUnchanged() {
        val state =
            submitting(
                items =
                    listOf(
                        ImportBatchSubmittingItem(batchItem("candidate-pending", "request-batch-1"), ImportBatchItemOutcome.Unknown),
                        ImportBatchSubmittingItem(batchItem("candidate-retainable", "request-batch-2")),
                    ),
                paused = true,
            )
        val resumed = assertIs<P503AppState.ImportBatchSubmitting>(reducer.reduce(state, P503UiEvent.ResumeImportBatchDispatch))
        // 复用同次 LedgerClock 取样与既有 requestId — nothing is re-sampled or re-minted.
        assertEquals(confirmedAt, resumed.confirmedAt)
        assertEquals(ImportRequestId("request-batch-1"), resumed.items[0].item.requestId)
        assertEquals(ImportRequestId("request-batch-2"), resumed.items[1].item.requestId)
        assertEquals(false, resumed.dispatchPaused)
    }

    // ---- ImportUnknownItemCheckResult (核对结果更新该项； 仅全部项终态后可离开) ----

    @Test
    fun aConfirmedCheckResolvesTheUnknownItemAndLeavesWhenEverythingIsTerminal() {
        val state =
            submitting(
                items =
                    listOf(
                        ImportBatchSubmittingItem(batchItem("candidate-pending", "request-batch-1"), ImportBatchItemOutcome.Unknown),
                        ImportBatchSubmittingItem(batchItem("candidate-retainable", "request-batch-2"), ImportBatchItemOutcome.Confirmed(confirmedReceipt("candidate-retainable"))),
                    ),
                paused = true,
            )
        val left =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(state, P503UiEvent.ImportUnknownItemCheckResult(batchItem("candidate-pending", "request-batch-1"), ImportUnknownCheckOutcome.Confirmed(confirmedReceipt("candidate-pending")))),
            )
        val summary = left.importReview?.batchResult
        assertIs<ImportBatchItemOutcome.Confirmed>(summary?.items?.get(0)?.outcome)
        assertIs<ImportBatchItemOutcome.Confirmed>(summary?.items?.get(1)?.outcome)
    }

    @Test
    fun aConflictCheckTypesTheItemAndAStillUnknownCheckKeepsTheEntry() {
        val state =
            submitting(
                items =
                    listOf(
                        ImportBatchSubmittingItem(batchItem("candidate-pending", "request-batch-1"), ImportBatchItemOutcome.Unknown),
                        ImportBatchSubmittingItem(batchItem("candidate-retainable", "request-batch-2")),
                    ),
                paused = true,
            )
        val conflicted =
            assertIs<P503AppState.ImportBatchSubmitting>(
                reducer.reduce(state, P503UiEvent.ImportUnknownItemCheckResult(batchItem("candidate-pending", "request-batch-1"), ImportUnknownCheckOutcome.Conflict("SPINE_REQUEST_IDENTITY_CONFLICT"))),
            )
        val conflict = assertIs<ImportBatchItemOutcome.CheckConflict>(conflicted.items[0].outcome)
        assertEquals("SPINE_REQUEST_IDENTITY_CONFLICT", conflict.code)
        // Still not all terminal (the retainable item awaits): the state stays open.
        val stillUnknown =
            assertIs<P503AppState.ImportBatchSubmitting>(
                reducer.reduce(conflicted, P503UiEvent.ImportUnknownItemCheckResult(batchItem("candidate-pending", "request-batch-1"), ImportUnknownCheckOutcome.StillUnknown)),
            )
        assertIs<ImportBatchItemOutcome.CheckConflict>(stillUnknown.items[0].outcome)
        // A StillUnknown check on the Unknown item keeps the entry (仍未知).
        val pausedUnknown =
            submitting(
                items = listOf(ImportBatchSubmittingItem(batchItem("candidate-pending", "request-batch-1"), ImportBatchItemOutcome.Unknown)),
                paused = true,
            )
        val kept =
            assertIs<P503AppState.ImportBatchSubmitting>(
                reducer.reduce(pausedUnknown, P503UiEvent.ImportUnknownItemCheckResult(batchItem("candidate-pending", "request-batch-1"), ImportUnknownCheckOutcome.StillUnknown)),
            )
        assertIs<ImportBatchItemOutcome.Unknown>(kept.items[0].outcome)
        assertTrue(kept.dispatchPaused)
    }

    // ---- ResumeImportBatchDispatch / AbandonImportBatch (继续/放弃出口) ----

    @Test
    fun resumeWithUndispatchedItemsContinuesAndResumeWithNothingLeftLeavesCarryingTheUnknowns() {
        val state =
            submitting(
                items =
                    listOf(
                        ImportBatchSubmittingItem(batchItem("candidate-pending", "request-batch-1"), ImportBatchItemOutcome.Unknown),
                        ImportBatchSubmittingItem(batchItem("candidate-retainable", "request-batch-2"), ImportBatchItemOutcome.Unknown),
                    ),
                paused = true,
            )
        // Undispatched items remain? Neither item is undispatched (both Unknown): the batch is
        // spent, so Resume leaves and the summary carries the Unknown items with their check
        // entries (table 6.2a: 核对入口在 IMPORT 结果摘要内).
        val left = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(state, P503UiEvent.ResumeImportBatchDispatch))
        val summary = left.importReview?.batchResult
        assertEquals(2, summary?.items?.size)
        assertIs<ImportBatchItemOutcome.Unknown>(summary?.items?.get(0)?.outcome)
        assertIs<ImportBatchItemOutcome.Unknown>(summary?.items?.get(1)?.outcome)
        // The undispatched case continues instead (no leave).
        val withUndispatched =
            submitting(
                items =
                    listOf(
                        ImportBatchSubmittingItem(batchItem("candidate-pending", "request-batch-1"), ImportBatchItemOutcome.Unknown),
                        ImportBatchSubmittingItem(batchItem("candidate-retainable", "request-batch-2")),
                    ),
                paused = true,
            )
        val resumed = assertIs<P503AppState.ImportBatchSubmitting>(reducer.reduce(withUndispatched, P503UiEvent.ResumeImportBatchDispatch))
        assertEquals(false, resumed.dispatchPaused)
    }

    @Test
    fun abandonDissolvesTheSnapshotInOneTransitionKeepingOnlyCompletedResults() {
        val state =
            submitting(
                items =
                    listOf(
                        ImportBatchSubmittingItem(batchItem("candidate-pending", "request-batch-1"), ImportBatchItemOutcome.Confirmed(confirmedReceipt("candidate-pending"))),
                        ImportBatchSubmittingItem(batchItem("candidate-retainable", "request-batch-2"), ImportBatchItemOutcome.Unknown),
                        ImportBatchSubmittingItem(batchItem("candidate-incomplete", "request-batch-3")),
                    ),
                paused = true,
            )
        // 义务③ (PRODUCT_REQUIREMENTS 措辞): 放弃后已完成各项的结果保留显示， 未提交各项留在
        // 待确认清单 — ONE transition straight to the overview; no hidden intermediate UI state.
        val left = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(state, P503UiEvent.AbandonImportBatch))
        assertEquals(P503Tab.IMPORT, left.selectedTab)
        val summary = left.importReview?.batchResult
        assertEquals(confirmedAt, summary?.confirmedAt)
        // The completed items (含未知项的核对入口) stay; the never-dispatched item is NOT in the
        // summary — it is an ordinary pending list row again.
        assertEquals(setOf("candidate-pending", "candidate-retainable"), summary?.items?.map { it.item.candidateId.value }?.toSet())
        assertIs<ImportBatchItemOutcome.Confirmed>(summary?.items?.get(0)?.outcome)
        assertIs<ImportBatchItemOutcome.Unknown>(summary?.items?.get(1)?.outcome)
        // The rows/selection are the authorize-time overview: the pending candidates are the
        // ordinary 待确认清单项 again (其持久状态保持 pending_confirmation).
        assertEquals(state.overview.importReview?.rows, left.importReview?.rows)
        assertEquals(state.overview.importReview?.selectedCandidateIds, left.importReview?.selectedCandidateIds)
    }

    // ---- ImportUnknownItemCheckResult on the overview (核对入口在 IMPORT 结果摘要内) ----

    @Test
    fun theOverviewSummaryItemTakesTheCheckVerdictInPlace() {
        val summary =
            ImportBatchResultSummary(
                confirmedAt = confirmedAt,
                items =
                    listOf(
                        ImportBatchResultItem(batchItem("candidate-pending", "request-batch-1"), ImportBatchItemOutcome.Unknown),
                        ImportBatchResultItem(batchItem("candidate-retainable", "request-batch-2"), ImportBatchItemOutcome.Rejected("SPINE_DUPLICATE_NOT_CONFIRMABLE")),
                    ),
            )
        val state = overview().let { P503AppState.OverviewEmpty(it.state, it.selectedTab, importReview = it.importReview?.copy(batchResult = summary)) }
        val confirmed =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(state, P503UiEvent.ImportUnknownItemCheckResult(batchItem("candidate-pending", "request-batch-1"), ImportUnknownCheckOutcome.Confirmed(confirmedReceipt("candidate-pending")))),
            )
        assertIs<ImportBatchItemOutcome.Confirmed>(
            confirmed.importReview
                ?.batchResult
                ?.items
                ?.get(0)
                ?.outcome,
        )
        // The other summary item is untouched.
        assertIs<ImportBatchItemOutcome.Rejected>(
            confirmed.importReview
                ?.batchResult
                ?.items
                ?.get(1)
                ?.outcome,
        )
        // A StillUnknown verdict keeps the entry.
        val kept =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(confirmed, P503UiEvent.ImportUnknownItemCheckResult(batchItem("candidate-pending", "request-batch-1"), ImportUnknownCheckOutcome.StillUnknown)),
            )
        // The now-Confirmed item no longer matches an Unknown check target: nothing changes.
        assertSame(confirmed.importReview?.batchResult, kept.importReview?.batchResult)
    }

    // ---- absorbed columns (table 6.2a: every batch event absorbed outside its effect states) ----

    @Test
    fun batchEventsAreAbsorbedOutsideTheirDesignedStates() {
        val confirm = P503AppState.ImportBatchConfirm(overview())
        val dispatching = submitting()
        val detailState = allStates().first { it is P503AppState.ImportCandidateDetail }
        // RequestImportBatchConfirm also acts on the detail (携详情决策进入确认页)， so the
        // detail joins the excluded set for that one event.
        everyStateExcept(confirm, dispatching, detailState).forEach { state ->
            assertSame(state, reducer.reduce(state, P503UiEvent.RequestImportBatchConfirm))
        }
        val states = everyStateExcept(confirm, dispatching)
        states.forEach { state ->
            assertSame(state, reducer.reduce(state, P503UiEvent.CancelImportBatchConfirm))
            assertSame(state, reducer.reduce(state, P503UiEvent.AuthorizeImportBatch(confirmedAt, emptyMap())))
            assertSame(state, reducer.reduce(state, P503UiEvent.ImportItemResult(batchItem("candidate-pending", "request-batch-1"), ImportBatchItemOutcome.Confirmed(confirmedReceipt("candidate-pending")))))
            assertSame(state, reducer.reduce(state, P503UiEvent.ResumeImportBatchDispatch))
            assertSame(state, reducer.reduce(state, P503UiEvent.AbandonImportBatch))
            assertSame(state, reducer.reduce(state, P503UiEvent.ImportUnknownItemCheck(ImportCandidateId("candidate-pending"))))
            assertSame(state, reducer.reduce(state, P503UiEvent.ImportUnknownItemCheckResult(batchItem("candidate-pending", "request-batch-1"), ImportUnknownCheckOutcome.Confirmed(confirmedReceipt("candidate-pending")))))
        }
    }

    @Test
    fun preExistingEventsAreAbsorbedOnBothNewStates() {
        val events =
            listOf(
                P503UiEvent.StartNewExpense,
                P503UiEvent.Confirm,
                P503UiEvent.Cancel,
                P503UiEvent.Continue(com.unifiedledger.application.RequestId("request-2")),
                P503UiEvent.SelectTab(P503Tab.HOME),
                P503UiEvent.RetryRefresh,
                P503UiEvent.RefreshResult(emptyState),
                P503UiEvent.SelectTransaction(TransactionId("tx-1"), TransactionDetailResult.NotFound),
                P503UiEvent.SelectMonth(kotlinx.datetime.YearMonth(2026, 3)),
                P503UiEvent.RefreshImportReview,
                P503UiEvent.ImportReviewResult(com.unifiedledger.application.ImportReviewRowsResult.Unavailable),
                P503UiEvent.CloseImportCandidateDetail,
                P503UiEvent.SubmitImportDuplicateReview(ImportDuplicateReviewUiDecision.CONFIRMED_DUPLICATE, "user-reviewed"),
            )
        for (state in listOf<P503AppState>(P503AppState.ImportBatchConfirm(overview()), submitting())) {
            for (event in events) {
                assertSame(state, reducer.reduce(state, event))
            }
        }
    }

    // ---- P704D-SPEC-01: the residual stopped sub-state keeps reachable exits ----

    @Test
    fun aResidualUnknownSubStateLeavesOnResumeAndAbandon() {
        // A resumed run finished cleanly but an earlier Unknown remains (its check came back
        // 仍未知)： paused=false, nothing undispatched, one Unknown — the reducer cannot
        // auto-leave an Unknown item, so Resume/Abandon are the exits (the screen renders them
        // through importBatchExitAvailable; system back stays intercepted — without these
        // affordances a persistently failing check would deadlock the page until restart).
        val state =
            submitting(
                items =
                    listOf(
                        ImportBatchSubmittingItem(batchItem("candidate-pending", "request-batch-1"), ImportBatchItemOutcome.Unknown),
                        ImportBatchSubmittingItem(batchItem("candidate-retainable", "request-batch-2"), ImportBatchItemOutcome.Confirmed(confirmedReceipt("candidate-retainable"))),
                    ),
                paused = false,
            )
        assertTrue(importBatchExitAvailable(state))
        val left = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(state, P503UiEvent.ResumeImportBatchDispatch))
        val summary = left.importReview?.batchResult
        assertIs<ImportBatchItemOutcome.Unknown>(summary?.items?.get(0)?.outcome)
        assertIs<ImportBatchItemOutcome.Confirmed>(summary?.items?.get(1)?.outcome)
        val abandoned = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(state, P503UiEvent.AbandonImportBatch))
        assertEquals(
            2,
            abandoned.importReview
                ?.batchResult
                ?.items
                ?.size,
        )
    }

    // ---- P704D-QUAL-01/SPEC-02: run-level typed skips leave no dead end ----

    @Test
    fun runLevelTypedSkipsLandPerItemAndLeaveTheBatchState() {
        // A failed pre-phase (guarded in the host) becomes one typed skip per still-undispatched
        // item; once every item carries a terminal outcome the reducer auto-leaves to the
        // overview with the typed summary — the candidates stay pending, nothing is stranded.
        val state = submitting()
        val first =
            reducer.reduce(
                state,
                P503UiEvent.ImportItemResult(
                    batchItem("candidate-pending", "request-batch-1"),
                    ImportBatchItemOutcome.Skipped(IMPORT_BATCH_DISPATCH_UNAVAILABLE),
                ),
            )
        assertIs<P503AppState.ImportBatchSubmitting>(first)
        val second =
            reducer.reduce(
                first,
                P503UiEvent.ImportItemResult(
                    batchItem("candidate-retainable", "request-batch-2"),
                    ImportBatchItemOutcome.Skipped(IMPORT_BATCH_DISPATCH_UNAVAILABLE),
                ),
            )
        val left = assertIs<P503AppState.OverviewEmpty>(second)
        val summary = left.importReview?.batchResult
        assertEquals(2, summary?.items?.size)
        summary?.items?.forEach { entry ->
            val skipped = assertIs<ImportBatchItemOutcome.Skipped>(entry.outcome)
            assertEquals(IMPORT_BATCH_DISPATCH_UNAVAILABLE, skipped.code)
        }
    }

    // ---- P704D-SPEC-03: the detail's empty-selection gate aligns with the overview column ----

    @Test
    fun requestImportBatchConfirmOnTheDetailAbsorbsAnEmptySelection() {
        val detail =
            P503AppState.ImportCandidateDetail(
                overview = overview(),
                candidateId = ImportCandidateId("candidate-pending"),
                detail = ImportCandidateDetailResult.Found(ImportCandidateDetailRow(pendingRow, 1L)),
                duplicates = com.unifiedledger.application.ImportDuplicateReviewsResult.NoDuplicates,
                form = ImportDecisionDraft(categoryId = com.unifiedledger.domain.CategoryId("category-food")),
            )
        // 空集 absorbed — the page is never opened without a checked item.
        assertSame(detail, reducer.reduce(detail, P503UiEvent.RequestImportBatchConfirm))
    }

    // ---- locked ISE behavior (G-B: unlisted combinations stay programming errors) ----

    @Test
    fun exitStaysIllegalStateOnBothNewBatchStates() {
        assertFailsWith<IllegalStateException> { reducer.reduce(P503AppState.ImportBatchConfirm(overview()), P503UiEvent.Exit) }
        assertFailsWith<IllegalStateException> { reducer.reduce(submitting(), P503UiEvent.Exit) }
    }

    @Test
    fun backOnTheDispatchStateStaysTheSubmittingStyleIllegalState() {
        // 沿既有 Submitting 语义： system back is intercepted and swallowed by the host guards
        // (isBackEnabled = true, isBackDispatchSafe = false), so `Back` never reaches the
        // reducer here — the unlisted combination stays an ISE, exactly like `Submitting`.
        assertFailsWith<IllegalStateException> { reducer.reduce(submitting(), P503UiEvent.Back) }
    }

    @Test
    fun theExistingOverviewBackFloorStaysTheUnhandledIllegalState() {
        // No ISE reversal anywhere: Back on the IMPORT overview root stays unlisted.
        assertFailsWith<IllegalStateException> { reducer.reduce(overview(), P503UiEvent.Back) }
        assertFailsWith<IllegalStateException> { reducer.reduce(overview(), P503UiEvent.RetryRefresh) }
    }

    // ---- helpers ----

    private fun everyStateExcept(vararg excluded: P503AppState): List<P503AppState> = allStates().filterNot { it in excluded }

    private fun allStates(): List<P503AppState> {
        val draft = ManualExpenseDraft(com.unifiedledger.domain.AccountId("asset-payment-local"), null, "35.80", occurredAt)
        val requestId = com.unifiedledger.application.RequestId("request-1")
        return listOf(
            P503AppState.Ready,
            overview(),
            P503AppState.TransactionDetail(overview(), P503Tab.HOME, TransactionId("tx-1"), TransactionDetailResult.NotFound),
            P503AppState.ImportCandidateDetail(
                overview = overview(),
                candidateId = ImportCandidateId("candidate-pending"),
                detail = ImportCandidateDetailResult.Found(ImportCandidateDetailRow(pendingRow, 1L)),
                duplicates = com.unifiedledger.application.ImportDuplicateReviewsResult.NoDuplicates,
                form = ImportDecisionDraft(),
            ),
            P503AppState.ImportBatchConfirm(overview()),
            submitting(),
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
    }
}
