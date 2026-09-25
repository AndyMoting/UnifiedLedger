package com.unifiedledger.ui

import com.unifiedledger.application.CorrectTransactionVersionResult
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.RecycleBinResult
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.VoidTransactionResult
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.P705FailureCode
import com.unifiedledger.domain.VoidReasonCode
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
 * P7-06 06.B (D-177; spec `2026-09-24-p7-06-backup-export-design.md` sections 3/6) state-machine
 * tests for the backup-export surface. Every new event has its designed effect only in its designed
 * state and is absorbed in every other state; no new event throws anywhere; `Exit` stays unlisted
 * (ISE) on the new state (the G-B discipline). The password is an in-memory draft only — these
 * tests assert it is carried in state and cleared on leave, never that it is logged.
 */
class P503BackupExportReducerTest {
    private val ledgerId = LedgerId("ledger-local-test")
    private val cny = CurrencyUnit("CNY", 2)
    private val reducer: P503Reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)
    private val emptyState = LedgerCurrentState(ledgerId, transactions = emptyList(), balances = emptyList())

    private fun overview(): P503AppState.OverviewEmpty = P503AppState.OverviewEmpty(emptyState)

    private fun origin(): TransactionEditOrigin =
        TransactionEditOrigin(
            transactionId = com.unifiedledger.domain.TransactionId("tx-1"),
            currentVersionId = com.unifiedledger.domain.TransactionVersionId("version-1"),
            note = "old note",
            statisticsAt = Instant.parse("2026-09-14T08:00:00Z"),
            amountText = "100.00",
            categoryId = CategoryId("category-food"),
            fundingAccountId = AccountId("asset-payment-local"),
        )

    private fun exportState(
        password: String = "",
        running: Boolean = false,
        outcome: BackupExportResult? = null,
    ): P503AppState.BackupExport = P503AppState.BackupExport(overview(), password, running, outcome)

    // ---- the one designed open transition ----

    @Test
    fun theOverviewEntryOpensTheSurfaceCarryingTheExactOverview() {
        val overview = overview()
        val opened = assertIs<P503AppState.BackupExport>(reducer.reduce(overview, P503UiEvent.OpenBackupExport))
        assertSame(overview, opened.overview)
        assertEquals("", opened.password)
        assertEquals(false, opened.running)
        assertNull(opened.outcome)
    }

    // ---- the surface's own four events ----

    @Test
    fun aPasswordWriteUpdatesOnlyTheInMemoryDraft() {
        val state = exportState()
        val updated = assertIs<P503AppState.BackupExport>(reducer.reduce(state, P503UiEvent.UpdateBackupPassword("correct horse")))
        assertEquals("correct horse", updated.password)
        // The overview is preserved untouched.
        assertSame(state.overview, updated.overview)
    }

    @Test
    fun confirmSetsTheRunningMarkerAndClearsAnyPreviousOutcome() {
        val state = exportState(password = "password123", outcome = BackupExportResult.Failed(BackupExportFailure.SNAPSHOT_FAILED))
        val confirmed = assertIs<P503AppState.BackupExport>(reducer.reduce(state, P503UiEvent.ConfirmBackupExport))
        assertEquals(true, confirmed.running)
        assertNull(confirmed.outcome)
    }

    @Test
    fun aSecondConfirmWhileRunningIsAbsorbed() {
        val running = exportState(password = "password123", running = true)
        assertSame(running, reducer.reduce(running, P503UiEvent.ConfirmBackupExport))
    }

    @Test
    fun aLandedResultClearsTheMarkerAndRecordsTheTypedOutcome() {
        val running = exportState(password = "password123", running = true)
        val landed = assertIs<P503AppState.BackupExport>(reducer.reduce(running, P503UiEvent.BackupExportResultLanded(BackupExportResult.Succeeded(1234))))
        assertEquals(false, landed.running)
        assertEquals(BackupExportResult.Succeeded(1234), landed.outcome)
    }

    @Test
    fun closeLeavesForThePreservedOverview() {
        val state = exportState(password = "password123")
        assertSame(state.overview, reducer.reduce(state, P503UiEvent.CloseBackupExport))
        assertSame(state.overview, reducer.reduce(state, P503UiEvent.Back))
    }

    @Test
    fun aRunningExportAbsorbsCloseAndBack() {
        val running = exportState(password = "password123", running = true)
        assertSame(running, reducer.reduce(running, P503UiEvent.CloseBackupExport))
        assertSame(running, reducer.reduce(running, P503UiEvent.Back))
    }

    // ---- absorption columns ----

    @Test
    fun theSurfaceEventsAreAbsorbedOutsideTheExportState() {
        val export = exportState()
        everyStateExcept(export).forEach { state ->
            assertSame(state, reducer.reduce(state, P503UiEvent.UpdateBackupPassword("x")))
            assertSame(state, reducer.reduce(state, P503UiEvent.ConfirmBackupExport))
            assertSame(state, reducer.reduce(state, P503UiEvent.BackupExportResultLanded(BackupExportResult.Cancelled)))
            assertSame(state, reducer.reduce(state, P503UiEvent.CloseBackupExport))
        }
    }

    @Test
    fun openBackupExportIsAbsorbedOutsideTheOverview() {
        val overview = overview()
        everyStateExcept(overview).forEach { state ->
            assertSame(state, reducer.reduce(state, P503UiEvent.OpenBackupExport))
        }
    }

    @Test
    fun preExistingEventsAreAbsorbedOnTheExportState() {
        val export = exportState()
        val events =
            listOf(
                P503UiEvent.StartNewExpense,
                P503UiEvent.Confirm,
                P503UiEvent.Continue(RequestId("request-2")),
                P503UiEvent.SelectTab(P503Tab.HOME),
                P503UiEvent.RetryRefresh,
                P503UiEvent.RefreshResult(emptyState),
                P503UiEvent.SelectTransaction(com.unifiedledger.domain.TransactionId("tx-1"), com.unifiedledger.application.TransactionDetailResult.NotFound),
                P503UiEvent.RefreshImportReview,
                P503UiEvent.RequestImportBatchConfirm,
                P503UiEvent.ImportGroupEnumerationCompleted,
                P503UiEvent.RetryP705CommitStatusCheck(),
            )
        for (event in events) {
            assertSame(export, reducer.reduce(export, event))
        }
    }

    @Test
    fun theFullP705EventFamilyIsAbsorbedOnTheExportState() {
        // P1-A fix (06.B review): the P7-05 correction/void/recycle-bin family must be absorbed on
        // the BackupExport state. It is reachable: the HOME overview renders 回收站 and 导出备份
        // together, so a recycle-bin read dispatched before the export entry opened lands on the
        // export state. Before the fix these reached `unhandled` -> IllegalStateException. Each
        // case asserts the SAME state instance is returned (absorbed, not mutated).
        val export = exportState()
        val p705Events =
            listOf(
                P503UiEvent.OpenRecycleBin(RecycleBinResult.Success(emptyList())),
                P503UiEvent.RecycleBinResult(RecycleBinResult.Success(emptyList())),
                P503UiEvent.CloseRecycleBin,
                P503UiEvent.OpenTransactionEdit(origin()),
                P503UiEvent.UpdateTransactionCorrectionField(TransactionCorrectionFieldUpdate.Note("x")),
                P503UiEvent.PreviewTransactionEdit,
                P503UiEvent.ConfirmTransactionEdit(RequestId("request-1")),
                P503UiEvent.TransactionEditResult(CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_FIELD_NOT_SUPPORTED)),
                P503UiEvent.OpenVoidConfirm(com.unifiedledger.domain.TransactionId("tx-1")),
                P503UiEvent.UpdateVoidReasonField(VoidReasonFieldUpdate.Code(VoidReasonCode.MIS_ENTERED)),
                P503UiEvent.ConfirmVoid(RequestId("request-2")),
                P503UiEvent.TransactionVoidResult(VoidTransactionResult.Rejected(P705FailureCode.P705_FIELD_NOT_SUPPORTED)),
                P503UiEvent.OpenRestoreConfirm(com.unifiedledger.domain.TransactionId("tx-1")),
                P503UiEvent.UpdateRestoreReasonField(VoidReasonFieldUpdate.Code(VoidReasonCode.MIS_ENTERED)),
                P503UiEvent.ConfirmRestore(RequestId("request-3")),
                P503UiEvent.TransactionRestoreResult(VoidTransactionResult.Rejected(P705FailureCode.P705_FIELD_NOT_SUPPORTED)),
                P503UiEvent.CloseRestoreConfirm,
            )
        for (event in p705Events) {
            assertSame(export, reducer.reduce(export, event), "the P7-05 event $event must be absorbed on BackupExport")
        }
    }

    @Test
    fun exitStaysIllegalStateOnTheExportState() {
        assertFailsWith<IllegalStateException> { reducer.reduce(exportState(), P503UiEvent.Exit) }
    }

    @Test
    fun theStateStringFormNeverContainsANonEmptyPassword() {
        // P1-B fix (06.B review): the generated data-class toString would print the plaintext, and
        // `unhandled` builds its ISE message from "$state"; `Exit` is a deliberate ISE on this
        // state, so the password could reach a platform log. The override must redact it.
        val secret = "s3cr3t-password-value"
        val state = exportState(password = secret)
        assertFalse(state.toString().contains(secret), "the state's toString must not contain the password")
        assertTrue(state.toString().contains("<redacted>"))
        // The exact diagnostic shape the reducer throws must not contain it either.
        val failure =
            assertFailsWith<IllegalStateException> { reducer.reduce(state, P503UiEvent.Exit) }
        assertFalse(failure.message.orEmpty().contains(secret), "the ISE message must not contain the password")
        // An empty password stays explicit (no false "<redacted>" marker).
        assertFalse(exportState(password = "").toString().contains("<redacted>"))
    }

    // ---- helpers ----

    private fun everyStateExcept(vararg excluded: P503AppState): List<P503AppState> = allStates().filterNot { it in excluded }

    private fun allStates(): List<P503AppState> {
        val draft = ManualExpenseDraft(com.unifiedledger.domain.AccountId("asset-payment-local"), null, "35.80", null)
        val requestId = RequestId("request-1")
        val transactionId = com.unifiedledger.domain.TransactionId("tx-1")
        return listOf(
            P503AppState.Ready,
            overview(),
            P503AppState.TransactionDetail(
                overview = overview(),
                originTab = P503Tab.HOME,
                transactionId = transactionId,
                detail = com.unifiedledger.application.TransactionDetailResult.NotFound,
            ),
            // P3-I fix (06.B review): the previously omitted states are enumerated so the
            // "absorbed in every other state" tests actually cover them.
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
            P503AppState.VoidConfirm(overview = overview(), transactionId = transactionId),
            P503AppState.RecycleBin(overview = overview(), rows = RecycleBinResult.Success(emptyList())),
            exportState(),
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
