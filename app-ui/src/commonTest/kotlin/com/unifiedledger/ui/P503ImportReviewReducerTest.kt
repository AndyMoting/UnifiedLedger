package com.unifiedledger.ui

import com.unifiedledger.application.EntryPinTarget
import com.unifiedledger.application.ImportCandidateDetailResult
import com.unifiedledger.application.ImportCandidateDetailRow
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportDuplicateCandidateId
import com.unifiedledger.application.ImportDuplicateReviewId
import com.unifiedledger.application.ImportDuplicateReviewReceipt
import com.unifiedledger.application.ImportDuplicateReviewResult
import com.unifiedledger.application.ImportDuplicateReviewRow
import com.unifiedledger.application.ImportDuplicateReviewsResult
import com.unifiedledger.application.ImportDuplicateStatus
import com.unifiedledger.application.ImportFileIntakeOutcome
import com.unifiedledger.application.ImportFormatId
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportIntakeRecordDisposition
import com.unifiedledger.application.ImportIntakeRecordSummary
import com.unifiedledger.application.ImportRequestId
import com.unifiedledger.application.ImportReviewRow
import com.unifiedledger.application.ImportReviewRowsResult
import com.unifiedledger.application.ImportStatusHistoryId
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.MonthlyActivityResult
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.TransactionDetailResult
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-04.C state-machine extension tests (D-146; spec sections 6.1/6.2, table 6.2a): every new
 * import event has its designed effect only in OverviewEmpty/ImportCandidateDetail and is
 * absorbed in every other state; no new event throws anywhere; every pre-existing ISE path stays
 * locked (G-B, including `Exit` on the new state, spec section 6.3). The request-intent events
 * (`StartImportFilePick`/`RefreshImportReview`) and the host-channel pick events are
 * reducer-absorbed everywhere by design — their effect is the coordinator-owned host action
 * (pinned in P503ImportReviewHostCoordinatorTest); intake/review result events carry the
 * preserve-previous-payload discipline (读失败不篡改， 失败条 + 保留上一清单, F1) and the detail
 * keeps the in-session decision draft across close/re-enter (表单字段保留, SPEC:283).
 */
class P503ImportReviewReducerTest {
    private val ledgerId = LedgerId("ledger-local-test")
    private val cny = CurrencyUnit("CNY", 2)
    private val reducer: P503Reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)
    private val emptyState = LedgerCurrentState(ledgerId, transactions = emptyList(), balances = emptyList())
    private val occurredAt = Instant.parse("2026-03-15T02:00:00Z")

    // ---- fixtures ----

    private val pendingRow = importRow("candidate-pending", candidateStatus = "pending_confirmation")
    private val suspectedRow =
        importRow("candidate-suspected", candidateStatus = "pending_confirmation", duplicateStatus = ImportDuplicateStatus.DEFERRED)
    private val retainableRow =
        importRow("candidate-retainable", candidateStatus = "pending_confirmation", duplicateStatus = ImportDuplicateStatus.CONFIRMED_DISTINCT)
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
        candidateStatus: String,
        candidateKind: String = "ordinary_flow",
        duplicateStatus: ImportDuplicateStatus? = null,
        fundingState: ImportFundingState = ImportFundingState.SETTLED,
        completeness: ImportCompleteness = ImportCompleteness.VALID_COMPLETE,
        sourceInputRef: String = "pick-handle-1",
    ): ImportReviewRow =
        ImportReviewRow(
            candidateId = ImportCandidateId(candidateId),
            candidateKind = candidateKind,
            sourceInputRef = sourceInputRef,
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
        rows: List<ImportReviewRow> = listOf(pendingRow, suspectedRow, retainableRow, incompleteRow),
        selection: Set<ImportCandidateId> = emptySet(),
        drafts: Map<ImportCandidateId, ImportDecisionDraft> = emptyMap(),
        session: ImportIntakeSessionSummary? = null,
        notice: ImportReviewNotice? = null,
    ): P503AppState.OverviewEmpty =
        P503AppState.OverviewEmpty(
            state = emptyState,
            selectedTab = P503Tab.IMPORT,
            importReview =
                ImportReviewView(
                    rows = rows,
                    selectedCandidateIds = selection,
                    decisionDrafts = drafts,
                    lastIntakeSession = session,
                    notice = notice,
                ),
        )

    private fun detailResult(row: ImportReviewRow): ImportCandidateDetailResult = ImportCandidateDetailResult.Found(ImportCandidateDetailRow(row = row, statusHistoryCount = 1L))

    private fun deferredReview(duplicateId: String): ImportDuplicateReviewRow =
        ImportDuplicateReviewRow(
            duplicateCandidateId = ImportDuplicateCandidateId(duplicateId),
            kind = "EXACT_BUSINESS_TUPLE",
            comparisonFingerprint = "sha256:fixed-fingerprint-$duplicateId",
            comparisonSnapshot = "{\"amount_minor\":3580}",
            latestStatus = ImportDuplicateStatus.DEFERRED,
            reviewDecision = null,
            reviewReasonToken = null,
            reviewedAt = null,
            possibleExistingSource = null,
        )

    private fun detail(
        candidateId: String = "candidate-suspected",
        row: ImportReviewRow = suspectedRow,
        overviewState: P503AppState.OverviewEmpty = overview(),
        duplicates: com.unifiedledger.application.ImportDuplicateReviewsResult =
            ImportDuplicateReviewsResult.Reviews(listOf(deferredReview("duplicate-1"))),
        form: ImportDecisionDraft = ImportDecisionDraft(),
        reviewPending: Boolean = false,
    ): P503AppState.ImportCandidateDetail =
        P503AppState.ImportCandidateDetail(
            overview = overviewState,
            candidateId = ImportCandidateId(candidateId),
            detail = detailResult(row),
            duplicates = duplicates,
            form = form,
            reviewPending = reviewPending,
        )

    private fun acceptedReview(): ImportDuplicateReviewResult =
        ImportDuplicateReviewResult.Accepted(
            ImportDuplicateReviewReceipt(
                requestId = ImportRequestId("request-review-1"),
                candidateId = ImportDuplicateCandidateId("duplicate-1"),
                reviewId = ImportDuplicateReviewId("review-1"),
                historyId = ImportStatusHistoryId("history-1"),
                outcome = ImportDuplicateStatus.CONFIRMED_DUPLICATE,
            ),
        )

    private fun acceptedSession(inputRef: String = "pick-handle-1"): ImportIntakeSessionSummary =
        ImportIntakeSessionSummary(
            displayName = "synthetic-bill.csv",
            inputRef = inputRef,
            outcome =
                ImportIntakePipelineOutcome.Intaken(
                    ImportFileIntakeOutcome.Accepted(
                        records = listOf(ImportIntakeRecordSummary(0, ImportIntakeRecordDisposition.INTAKE_ACCEPTED)),
                        newCandidateIds = listOf(ImportCandidateId("candidate-pending")),
                    ),
                ),
        )

    /** Every state of the machine (both new import states included), for the absorbed columns. */
    private fun allStates(): List<P503AppState> {
        val draft = ManualExpenseDraft(AccountId("asset-payment-local"), null, "35.80", occurredAt)
        val requestId = RequestId("request-1")
        return listOf(
            P503AppState.Ready,
            overview(),
            P503AppState.TransactionDetail(overview(), P503Tab.HOME, TransactionId("tx-1"), TransactionDetailResult.NotFound),
            detail(),
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

    private fun everyStateExcept(vararg excluded: P503AppState): List<P503AppState> = allStates().filterNot { it in excluded }

    // ---- SelectImportCandidate / Close / Back (table 6.2a rows 6/5 + SPEC:281/283) ----

    @Test
    fun selectImportCandidateEntersTheDetailWithThePreservedOverviewAndARestoredDraft() {
        val restored =
            ImportDecisionDraft(categoryId = CategoryId("category-food"), fundingAccountId = AccountId("asset-1"))
        val source = overview(drafts = mapOf(ImportCandidateId("candidate-suspected") to restored))
        val entered =
            assertIs<P503AppState.ImportCandidateDetail>(
                reducer.reduce(source, P503UiEvent.SelectImportCandidate(ImportCandidateId("candidate-suspected"), detailResult(suspectedRow), ImportDuplicateReviewsResult.Reviews(listOf(deferredReview("duplicate-1"))))),
            )
        assertSame(source, entered.overview)
        assertEquals(ImportCandidateId("candidate-suspected"), entered.candidateId)
        // SPEC:283 表单字段保留: the in-session draft is restored on re-entry.
        assertEquals(restored, entered.form)

        val fresh = reducer.reduce(overview(), P503UiEvent.SelectImportCandidate(ImportCandidateId("candidate-pending"), detailResult(pendingRow), ImportDuplicateReviewsResult.NoDuplicates))
        assertEquals(ImportDecisionDraft(), assertIs<P503AppState.ImportCandidateDetail>(fresh).form)
    }

    @Test
    fun closeImportCandidateDetailReturnsToTheOverviewWritingTheDraftBack() {
        val source = overview()
        val entered = assertIs<P503AppState.ImportCandidateDetail>(reducer.reduce(source, P503UiEvent.SelectImportCandidate(ImportCandidateId("candidate-suspected"), detailResult(suspectedRow), ImportDuplicateReviewsResult.Reviews(listOf(deferredReview("duplicate-1"))))))
        val edited =
            assertIs<P503AppState.ImportCandidateDetail>(
                reducer.reduce(entered, P503UiEvent.UpdateImportDecisionField(ImportDecisionFieldUpdate.Category(CategoryId("category-food")))),
            )
        val closed = reducer.reduce(edited, P503UiEvent.CloseImportCandidateDetail)
        val returned = assertIs<P503AppState.OverviewEmpty>(closed)
        assertEquals(P503Tab.IMPORT, returned.selectedTab)
        // The selection set and rows are preserved; the edited draft is written back (SPEC:283).
        assertSame(source.state, returned.state)
        val stored = returned.importReview!!.decisionDrafts[ImportCandidateId("candidate-suspected")]
        assertEquals(CategoryId("category-food"), stored?.categoryId)
        // Re-entering the same candidate keeps the draft within the session.
        val reentered = assertIs<P503AppState.ImportCandidateDetail>(reducer.reduce(returned, P503UiEvent.SelectImportCandidate(ImportCandidateId("candidate-suspected"), detailResult(suspectedRow), ImportDuplicateReviewsResult.Reviews(listOf(deferredReview("duplicate-1"))))))
        assertEquals(stored, reentered.form)
    }

    @Test
    fun backOnTheDetailClosesWithTheSameSemantics() {
        val source = overview(selection = setOf(ImportCandidateId("candidate-retainable")))
        val entered = assertIs<P503AppState.ImportCandidateDetail>(reducer.reduce(source, P503UiEvent.SelectImportCandidate(ImportCandidateId("candidate-suspected"), detailResult(suspectedRow), ImportDuplicateReviewsResult.Reviews(listOf(deferredReview("duplicate-1"))))))
        val closed = reducer.reduce(entered, P503UiEvent.Back)
        // SPEC:281: 返回 OverviewEmpty(IMPORT) 保留清单/勾选集.
        val returned = assertIs<P503AppState.OverviewEmpty>(closed)
        assertEquals(P503Tab.IMPORT, returned.selectedTab)
        assertEquals(setOf(ImportCandidateId("candidate-retainable")), returned.importReview?.selectedCandidateIds)
    }

    @Test
    fun updateImportDecisionFieldIsAPureFormWrite() {
        val state = detail(candidateId = "candidate-pending", row = pendingRow, duplicates = ImportDuplicateReviewsResult.NoDuplicates)
        val updated =
            assertIs<P503AppState.ImportCandidateDetail>(
                reducer.reduce(state, P503UiEvent.UpdateImportDecisionField(ImportDecisionFieldUpdate.FundingAccount(AccountId("asset-1")))),
            )
        assertEquals(AccountId("asset-1"), updated.form.fundingAccountId)
    }

    // ---- ToggleImportCandidateSelection (先审后勾门, R-Q10-1/R-Q10-3) ----

    @Test
    fun selectionTogglesOnlySelectableClassesOnTheOverview() {
        val base = overview()
        val selected =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(base, P503UiEvent.ToggleImportCandidateSelection(ImportCandidateId("candidate-pending"))),
            )
        assertEquals(setOf(ImportCandidateId("candidate-pending")), selected.importReview?.selectedCandidateIds)
        val unselected = reducer.reduce(selected, P503UiEvent.ToggleImportCandidateSelection(ImportCandidateId("candidate-pending")))
        assertEquals(emptySet(), assertIs<P503AppState.OverviewEmpty>(unselected).importReview?.selectedCandidateIds)

        // 先审后勾: the suspected-duplicate and incomplete rows absorb the toggle.
        assertSame(base, reducer.reduce(base, P503UiEvent.ToggleImportCandidateSelection(ImportCandidateId("candidate-suspected"))))
        assertSame(base, reducer.reduce(base, P503UiEvent.ToggleImportCandidateSelection(ImportCandidateId("candidate-incomplete"))))
        // An unknown candidate id and a missing projection absorb it too.
        assertSame(base, reducer.reduce(base, P503UiEvent.ToggleImportCandidateSelection(ImportCandidateId("candidate-unknown"))))
        val bareOverview = P503AppState.OverviewEmpty(emptyState)
        assertSame(bareOverview, reducer.reduce(bareOverview, P503UiEvent.ToggleImportCandidateSelection(ImportCandidateId("candidate-pending"))))
    }

    @Test
    fun selectionTogglesOnTheDetailThroughTheCarriedOverview() {
        val state = detail(candidateId = "candidate-pending", row = pendingRow, overviewState = overview(), duplicates = ImportDuplicateReviewsResult.NoDuplicates)
        val updated = reducer.reduce(state, P503UiEvent.ToggleImportCandidateSelection(ImportCandidateId("candidate-pending")))
        val detailUpdated = assertIs<P503AppState.ImportCandidateDetail>(updated)
        assertEquals(setOf(ImportCandidateId("candidate-pending")), detailUpdated.overview.importReview?.selectedCandidateIds)
        // The gate holds inside the detail: a suspected row on the carried overview absorbs it.
        val suspectedDetail = detail(candidateId = "candidate-suspected", row = suspectedRow)
        assertSame(suspectedDetail, reducer.reduce(suspectedDetail, P503UiEvent.ToggleImportCandidateSelection(ImportCandidateId("candidate-suspected"))))
    }

    // ---- SubmitImportDuplicateReview / ImportDuplicateReviewResult (期间禁重复提交) ----

    @Test
    fun submitImportDuplicateReviewMarksTheDetailPendingAndAbsorbsDuplicates() {
        val state = detail()
        val submitted =
            assertIs<P503AppState.ImportCandidateDetail>(
                reducer.reduce(state, P503UiEvent.SubmitImportDuplicateReview(ImportDuplicateReviewUiDecision.CONFIRMED_DUPLICATE, "user-reviewed")),
            )
        assertTrue(submitted.reviewPending)
        // 期间禁重复提交: a duplicate submit while in flight is absorbed.
        assertSame(submitted, reducer.reduce(submitted, P503UiEvent.SubmitImportDuplicateReview(ImportDuplicateReviewUiDecision.CONFIRMED_DISTINCT, "user-reviewed")))
    }

    @Test
    fun submitImportDuplicateReviewWithoutAReviewableTargetIsAbsorbed() {
        val noTarget = detail(candidateId = "candidate-pending", row = pendingRow, duplicates = ImportDuplicateReviewsResult.NoDuplicates)
        assertSame(noTarget, reducer.reduce(noTarget, P503UiEvent.SubmitImportDuplicateReview(ImportDuplicateReviewUiDecision.CONFIRMED_DUPLICATE, "user-reviewed")))
    }

    @Test
    fun importDuplicateReviewResultOnTheDetailClearsPendingAndRefreshesTheDuplicateState() {
        val state = detail(reviewPending = true)
        val refreshedRows = listOf(retainableRow)
        val freshDuplicates = ImportDuplicateReviewsResult.Reviews(emptyList())
        val freshDetail = detailResult(suspectedRow)
        val updated =
            assertIs<P503AppState.ImportCandidateDetail>(
                reducer.reduce(
                    state,
                    P503UiEvent.ImportDuplicateReviewResult(acceptedReview(), ImportDuplicateReviewRefresh(ImportReviewRowsResult.Rows(refreshedRows), freshDetail, freshDuplicates)),
                ),
            )
        assertFalse(updated.reviewPending)
        assertSame(freshDuplicates, updated.duplicates)
        assertSame(freshDetail, updated.detail)
        assertNull(updated.notice)
        assertEquals(refreshedRows, updated.overview.importReview?.rows)
    }

    @Test
    fun importDuplicateReviewRejectionIsPresentedTypedOnTheDetail() {
        val state = detail(reviewPending = true)
        val rejection = ImportDuplicateReviewResult.Rejected(importReviewDiagnostic("SPINE_DUPLICATE_NOT_PENDING"))
        val updated =
            assertIs<P503AppState.ImportCandidateDetail>(
                reducer.reduce(state, P503UiEvent.ImportDuplicateReviewResult(rejection, ImportDuplicateReviewRefresh(ImportReviewRowsResult.Unavailable))),
            )
        assertFalse(updated.reviewPending)
        val notice = assertIs<ImportReviewNotice.ReviewRejected>(updated.notice)
        assertEquals("SPINE_DUPLICATE_NOT_PENDING", notice.code)
    }

    @Test
    fun importDuplicateReviewResultOnTheOverviewRefreshesTheListAfterTheDetailClosed() {
        val state = overview()
        val refreshedRows = listOf(retainableRow)
        val updated =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(state, P503UiEvent.ImportDuplicateReviewResult(acceptedReview(), ImportDuplicateReviewRefresh(ImportReviewRowsResult.Rows(refreshedRows)))),
            )
        assertEquals(refreshedRows, updated.importReview?.rows)
        // A typed rejection surfaces the banner instead (the fresh equal read replaces the rows,
        // a rejection writes nothing so the two lists are identical in practice).
        val rejected =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(state, P503UiEvent.ImportDuplicateReviewResult(ImportDuplicateReviewResult.Rejected(importReviewDiagnostic("SPINE_DUPLICATE_STALE_FINGERPRINT")), ImportDuplicateReviewRefresh(ImportReviewRowsResult.Rows(refreshedRows)))),
            )
        assertIs<ImportReviewNotice.ReviewRejected>(rejected.importReview?.notice)
    }

    // ---- ImportFileIntakeResult / ImportReviewResult (F1: 失败条 + 保留上一清单) ----

    @Test
    fun intakeSuccessReplacesTheRowsAndTheSessionAndClosesAStaleGroupPage() {
        val stalePage = ImportDuplicateGroupDispositionPage("pick-handle-1", listOf(ImportDuplicateGroupItemState(ImportDuplicateGroupDispositionItem(ImportCandidateId("candidate-suspected"), ImportDuplicateCandidateId("duplicate-1"), "snapshot", "sha256:fixed-fingerprint-duplicate-1"))))
        val source = overview(session = acceptedSession("pick-handle-old")).let { P503AppState.OverviewEmpty(it.state, it.selectedTab, importReview = it.importReview?.copy(groupDisposition = stalePage)) }
        val freshRows = listOf(pendingRow, retainableRow)
        val updated =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(source, P503UiEvent.ImportFileIntakeResult(acceptedSession("pick-handle-1"), ImportReviewRowsResult.Rows(freshRows))),
            )
        val view = updated.importReview
        assertEquals("pick-handle-1", view?.lastIntakeSession?.inputRef)
        assertEquals(freshRows, view?.rows)
        assertNull(view?.notice)
        assertNull(view?.groupDisposition)
    }

    @Test
    fun typedIntakeFailureKeepsThePreviousListAndSurfacesTheBanner() {
        val stalePage =
            ImportDuplicateGroupDispositionPage(
                "pick-handle-old",
                listOf(
                    ImportDuplicateGroupItemState(
                        ImportDuplicateGroupDispositionItem(
                            ImportCandidateId("candidate-suspected"),
                            ImportDuplicateCandidateId("duplicate-1"),
                            "snapshot",
                            "sha256:fixed-fingerprint-duplicate-1",
                        ),
                    ),
                ),
            )
        val source =
            overview().let {
                P503AppState.OverviewEmpty(it.state, it.selectedTab, importReview = it.importReview?.copy(groupDisposition = stalePage))
            }
        val failedSession =
            ImportIntakeSessionSummary(
                displayName = "ccb-bill.xls",
                inputRef = "pick-handle-2",
                outcome =
                    ImportIntakePipelineOutcome.ReadExceedsLimit(IMPORT_FILE_PICK_MAX_READ_BYTES + 1),
            )
        val updated =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(source, P503UiEvent.ImportFileIntakeResult(failedSession, ImportReviewRowsResult.Unavailable)),
            )
        val view = updated.importReview
        // 失败条 + 保留上一清单: the session is recorded, the previous rows stay, the banner shows.
        assertSame(source.importReview?.rows, view?.rows)
        assertEquals("pick-handle-2", view?.lastIntakeSession?.inputRef)
        assertIs<ImportReviewNotice.IntakeFailed>(view?.notice)
        // P704C-SPEC-08: ANY intake result landing closes the open group page — the old handle's
        // page must not coexist with the new session summary even on a failed pipeline.
        assertNull(view?.groupDisposition)
    }

    @Test
    fun intakeSuccessWithAFailedListReReadKeepsTheRowsWithTheReadBanner() {
        val source = overview()
        val updated =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(source, P503UiEvent.ImportFileIntakeResult(acceptedSession(), ImportReviewRowsResult.Unavailable)),
            )
        val view = updated.importReview
        assertSame(source.importReview?.rows, view?.rows)
        assertEquals(ImportReviewNotice.ReviewReadFailed, view?.notice)
    }

    @Test
    fun reviewListSuccessReplacesTheRowsAndClearsTheBanner() {
        val source = overview(notice = ImportReviewNotice.ReviewReadFailed)
        val fresh = listOf(retainableRow)
        val updated = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(source, P503UiEvent.ImportReviewResult(ImportReviewRowsResult.Rows(fresh))))
        assertEquals(fresh, updated.importReview?.rows)
        assertNull(updated.importReview?.notice)
    }

    @Test
    fun reviewListFailureKeepsThePreviousSuccessfulList() {
        val source = overview()
        val failed = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(source, P503UiEvent.ImportReviewResult(ImportReviewRowsResult.Unavailable)))
        assertSame(source.importReview?.rows, failed.importReview?.rows)
        assertEquals(ImportReviewNotice.ReviewReadFailed, failed.importReview?.notice)
        // A first-ever failure still materializes the projection so the banner has a place to live.
        val firstEver = reducer.reduce(P503AppState.OverviewEmpty(emptyState), P503UiEvent.ImportReviewResult(ImportReviewRowsResult.Unavailable))
        val view = assertIs<P503AppState.OverviewEmpty>(firstEver).importReview
        assertEquals(ImportReviewNotice.ReviewReadFailed, view?.notice)
        assertEquals(emptyList(), view?.rows)
    }

    /** P704C-SPEC-06: a non-empty selection survives a successful review-list refresh. */
    @Test
    fun selectionSurvivesAReviewListSuccessRefresh() {
        val selected = setOf(ImportCandidateId("candidate-pending"), ImportCandidateId("candidate-retainable"))
        val source = overview(selection = selected)
        val refreshed =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(
                    source,
                    P503UiEvent.ImportReviewResult(ImportReviewRowsResult.Rows(listOf(pendingRow, suspectedRow, retainableRow))),
                ),
            )
        assertEquals(selected, refreshed.importReview?.selectedCandidateIds)
        assertEquals(listOf(pendingRow, suspectedRow, retainableRow), refreshed.importReview?.rows)
    }

    // ---- request-intent events: the reducer state is unchanged (host-action effects) ----

    @Test
    fun requestIntentAndChannelEventsLeaveTheReducerStateUnchangedEverywhere() {
        val events =
            listOf<P503UiEvent>(
                P503UiEvent.StartImportFilePick(ImportFormatId("wechat-xlsx")),
                P503UiEvent.RefreshImportReview,
                P503UiEvent.ImportFilePicked(PickedImportFile("synthetic-bill.csv", null) { BoundedFileRead.Bytes(ByteArray(0)) }),
                P503UiEvent.ImportFilePickCancelled,
                P503UiEvent.ImportFilePickFailed(ImportFilePickFailure.PICKER_LAUNCH_FAILED),
            )
        allStates().forEach { state ->
            events.forEach { event -> assertSame(state, reducer.reduce(state, event)) }
        }
    }

    // ---- group disposition lifecycle (P704SPEC-12 registered mechanism) ----

    @Test
    fun groupDispositionLifecycleOpensMarksAndCloses() {
        val source = overview(session = acceptedSession())
        val item = ImportDuplicateGroupDispositionItem(ImportCandidateId("candidate-suspected"), ImportDuplicateCandidateId("duplicate-1"), "{\"amount_minor\":3580}", "sha256:fixed-fingerprint-duplicate-1")
        val opened =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(source, P503UiEvent.StartImportDuplicateGroupDisposition("pick-handle-1", listOf(item))),
            )
        val page = opened.importReview?.groupDisposition
        assertEquals("pick-handle-1", page?.inputRef)
        assertNull(page?.items?.single()?.outcome)

        val outcome = ImportDuplicateGroupItemOutcome(ImportDuplicateCandidateId("duplicate-1"), ImportDuplicateGroupItemResult.Reviewed(ImportDuplicateStatus.CONFIRMED_DUPLICATE))
        val marked =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(opened, P503UiEvent.ImportDuplicateGroupDispositionResult(listOf(outcome), ImportReviewRowsResult.Rows(listOf(retainableRow)))),
            )
        // 可见部分成功: the item carries its verdict; the rows refresh.
        val markedPage = marked.importReview?.groupDisposition
        val markedOutcome = assertIs<ImportDuplicateGroupItemResult.Reviewed>(markedPage?.items?.single()?.outcome)
        assertEquals(ImportDuplicateStatus.CONFIRMED_DUPLICATE, markedOutcome.outcome)
        assertEquals(listOf(retainableRow), marked.importReview?.rows)

        val closed = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(marked, P503UiEvent.CloseImportDuplicateGroupDisposition))
        assertNull(closed.importReview?.groupDisposition)
    }

    @Test
    fun groupDispositionEventsWithoutAProjectionOrPageAreAbsorbed() {
        val bare = P503AppState.OverviewEmpty(emptyState)
        assertSame(bare, reducer.reduce(bare, P503UiEvent.StartImportDuplicateGroupDisposition("pick-handle-1", emptyList())))
        assertSame(bare, reducer.reduce(bare, P503UiEvent.CloseImportDuplicateGroupDisposition))
        val withoutPage = overview(session = acceptedSession())
        assertSame(withoutPage, reducer.reduce(withoutPage, P503UiEvent.ImportDuplicateGroupDispositionResult(emptyList(), ImportReviewRowsResult.Unavailable)))
    }

    /**
     * P704C-QUAL-01/SPEC-03 re-run idempotency: a Reviewed item verdict is never overwritten —
     * the host loop never re-submits a Reviewed item, and even a spurious late Rejected outcome
     * for it (exactly what the core produces for an already-disposed item) cannot relabel it
     * 失败； a still-pending or previously-Rejected item takes the fresh verdict.
     */
    @Test
    fun aGroupReRunResultNeverOverwritesAReviewedItemVerdict() {
        val source = overview(session = acceptedSession())
        val itemOne =
            ImportDuplicateGroupDispositionItem(
                ImportCandidateId("candidate-suspected"),
                ImportDuplicateCandidateId("duplicate-1"),
                "{\"amount_minor\":3580}",
                "sha256:fixed-fingerprint-duplicate-1",
            )
        val itemTwo = itemOne.copy(duplicateCandidateId = ImportDuplicateCandidateId("duplicate-2"), expectedComparisonFingerprint = "sha256:fixed-fingerprint-duplicate-2")
        val opened =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(source, P503UiEvent.StartImportDuplicateGroupDisposition("pick-handle-1", listOf(itemOne, itemTwo))),
            )
        // First run: duplicate-1 reviewed, duplicate-2 typed-rejected.
        val first =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(
                    opened,
                    P503UiEvent.ImportDuplicateGroupDispositionResult(
                        listOf(
                            ImportDuplicateGroupItemOutcome(ImportDuplicateCandidateId("duplicate-1"), ImportDuplicateGroupItemResult.Reviewed(ImportDuplicateStatus.CONFIRMED_DUPLICATE)),
                            ImportDuplicateGroupItemOutcome(ImportDuplicateCandidateId("duplicate-2"), ImportDuplicateGroupItemResult.Rejected("SPINE_DUPLICATE_NOT_PENDING")),
                        ),
                        ImportReviewRowsResult.Rows(listOf(retainableRow)),
                    ),
                ),
            )
        val firstPage = first.importReview?.groupDisposition
        val firstItems = firstPage?.items.orEmpty()
        assertIs<ImportDuplicateGroupItemResult.Reviewed>(firstItems[0].outcome)
        assertIs<ImportDuplicateGroupItemResult.Rejected>(firstItems[1].outcome)

        // Re-run: only duplicate-2 is re-attempted; the spurious late rejection for the already
        // reviewed duplicate-1 must not overwrite its true verdict.
        val second =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(
                    first,
                    P503UiEvent.ImportDuplicateGroupDispositionResult(
                        listOf(
                            ImportDuplicateGroupItemOutcome(ImportDuplicateCandidateId("duplicate-1"), ImportDuplicateGroupItemResult.Rejected("SPINE_DUPLICATE_NOT_PENDING")),
                            ImportDuplicateGroupItemOutcome(ImportDuplicateCandidateId("duplicate-2"), ImportDuplicateGroupItemResult.Reviewed(ImportDuplicateStatus.CONFIRMED_DUPLICATE)),
                        ),
                        ImportReviewRowsResult.Rows(listOf(retainableRow)),
                    ),
                ),
            )
        val secondPage = second.importReview?.groupDisposition
        val secondItems = secondPage?.items.orEmpty()
        val kept = assertIs<ImportDuplicateGroupItemResult.Reviewed>(secondItems[0].outcome)
        assertEquals(ImportDuplicateStatus.CONFIRMED_DUPLICATE, kept.outcome)
        val retried = assertIs<ImportDuplicateGroupItemResult.Reviewed>(secondItems[1].outcome)
        assertEquals(ImportDuplicateStatus.CONFIRMED_DUPLICATE, retried.outcome)
    }

    // ---- absorbed columns (table 6.2a: every new event absorbed outside its effect states) ----

    @Test
    fun intakeAndListResultEventsAreAbsorbedOutsideTheOverview() {
        val states = everyStateExcept(overview())
        states.forEach { state ->
            assertSame(state, reducer.reduce(state, P503UiEvent.ImportFileIntakeResult(acceptedSession(), ImportReviewRowsResult.Rows(emptyList()))))
            assertSame(state, reducer.reduce(state, P503UiEvent.ImportReviewResult(ImportReviewRowsResult.Rows(emptyList()))))
        }
    }

    @Test
    fun selectImportCandidateIsAbsorbedOutsideTheOverview() {
        val states = everyStateExcept(overview())
        states.forEach { state ->
            assertSame(state, reducer.reduce(state, P503UiEvent.SelectImportCandidate(ImportCandidateId("candidate-pending"), detailResult(pendingRow), ImportDuplicateReviewsResult.NoDuplicates)))
        }
    }

    @Test
    fun closeDetailAndFormEventsAreAbsorbedOutsideTheDetail() {
        val detailState = detail()
        val states = everyStateExcept(detailState)
        states.forEach { state ->
            assertSame(state, reducer.reduce(state, P503UiEvent.CloseImportCandidateDetail))
            assertSame(state, reducer.reduce(state, P503UiEvent.UpdateImportDecisionField(ImportDecisionFieldUpdate.Category(CategoryId("category-food")))))
            assertSame(state, reducer.reduce(state, P503UiEvent.SubmitImportDuplicateReview(ImportDuplicateReviewUiDecision.CONFIRMED_DUPLICATE, "user-reviewed")))
        }
    }

    @Test
    fun selectionTogglesOfNonSelectableCandidatesAreAbsorbedEverywhere() {
        // 先审后勾门: the suspected row's id absorbs in every state (the gate has no live targets).
        val states = allStates().filterNot { it is P503AppState.OverviewEmpty && it.importReview != null }
        states.forEach { state ->
            assertSame(state, reducer.reduce(state, P503UiEvent.ToggleImportCandidateSelection(ImportCandidateId("candidate-suspected"))))
        }
    }

    @Test
    fun reviewResultEventsAreAbsorbedOutsideOverviewAndDetail() {
        val states = everyStateExcept(overview(), detail())
        states.forEach { state ->
            assertSame(state, reducer.reduce(state, P503UiEvent.ImportDuplicateReviewResult(acceptedReview(), ImportDuplicateReviewRefresh(ImportReviewRowsResult.Unavailable))))
        }
    }

    @Test
    fun groupDispositionEventsAreAbsorbedOutsideTheOverview() {
        val item = ImportDuplicateGroupDispositionItem(ImportCandidateId("candidate-suspected"), ImportDuplicateCandidateId("duplicate-1"), "snapshot", "sha256:fixed-fingerprint-duplicate-1")
        val states = everyStateExcept(overview())
        states.forEach { state ->
            assertSame(state, reducer.reduce(state, P503UiEvent.StartImportDuplicateGroupDisposition("pick-handle-1", listOf(item))))
            assertSame(state, reducer.reduce(state, P503UiEvent.ImportDuplicateGroupDispositionResult(emptyList(), ImportReviewRowsResult.Unavailable)))
            assertSame(state, reducer.reduce(state, P503UiEvent.CloseImportDuplicateGroupDisposition))
        }
    }

    // ---- pre-existing events on the new state: absorbed except Exit (§6.2/6.3) ----

    @Test
    fun preExistingEventsAreAbsorbedOnTheImportDetail() {
        val state = detail()
        val events =
            listOf(
                P503UiEvent.StartNewExpense,
                P503UiEvent.Confirm,
                P503UiEvent.Cancel,
                P503UiEvent.Continue(RequestId("request-2")),
                P503UiEvent.SelectTab(P503Tab.HOME),
                P503UiEvent.RetryRefresh,
                P503UiEvent.RetrySubmission,
                P503UiEvent.RefreshResult(emptyState),
                P503UiEvent.RefreshFailed,
                P503UiEvent.SelectTransaction(TransactionId("tx-1"), TransactionDetailResult.NotFound),
                P503UiEvent.SelectMonth(kotlinx.datetime.YearMonth(2026, 3)),
                P503UiEvent.MonthlyActivityResult(MonthlyActivityResult.Unavailable, emptyList()),
                P503UiEvent.TogglePin(EntryPinTarget.AccountTarget(ledgerId, AccountId("asset-payment-local"))),
            )
        for (event in events) {
            assertSame(state, reducer.reduce(state, event))
        }
    }

    // ---- locked ISE behavior (G-B: unlisted combinations stay programming errors) ----

    @Test
    fun exitStaysIllegalStateOnTheNewDetailState() {
        // Spec section 6.3 (P7-02 §6.2b): `Exit` is not absorbed by the new state either.
        assertFailsWith<IllegalStateException> { reducer.reduce(detail(), P503UiEvent.Exit) }
    }

    @Test
    fun backOnTheImportTabStaysTheExistingUnhandledIllegalState() {
        // The overview root stays the back floor: Back leaves only the ACCOUNTS tab for HOME;
        // the IMPORT tab joins HOME/ANALYSIS in the unhandled branch (no ISE reversal anywhere).
        assertFailsWith<IllegalStateException> { reducer.reduce(overview(), P503UiEvent.Back) }
        // The pre-existing locked path is untouched.
        assertFailsWith<IllegalStateException> { reducer.reduce(overview(), P503UiEvent.RetryRefresh) }
    }
}

private fun importReviewDiagnostic(code: String): com.unifiedledger.application.ImportDiagnostic =
    com.unifiedledger.application.ImportDiagnosticRecord(
        code = code,
        severity = "conflict",
        scope = "request",
        location = com.unifiedledger.application.ImportDiagnosticLocation(null, null, ImportRequestId("request-review-1"), null),
    )
