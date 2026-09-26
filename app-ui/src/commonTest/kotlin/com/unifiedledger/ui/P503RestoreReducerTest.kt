package com.unifiedledger.ui

import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.RecycleBinResult
import com.unifiedledger.application.RequestId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * P7-06 06.D (D-182; spec sections 3/5.5/6) state-machine tests for the restore confirm & switch
 * surface and the session-terminal face. Every new event has its designed effect only in its
 * designed state and is absorbed in every other state; no new event throws anywhere; `Exit` stays
 * unlisted (ISE) on the surface (the G-B discipline) while the SESSION-TERMINAL face absorbs
 * everything — it has no continuation to route to. The password is an in-memory draft only.
 */
class P503RestoreReducerTest {
    private val ledgerId = LedgerId("ledger-local-test")
    private val cny = CurrencyUnit("CNY", 2)
    private val reducer: P503Reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)
    private val emptyState = LedgerCurrentState(ledgerId, transactions = emptyList(), balances = emptyList())

    private fun overview(): P503AppState.OverviewEmpty = P503AppState.OverviewEmpty(emptyState)

    private fun summary(): RestorePreflightSummary =
        RestorePreflightSummary(
            containerFormatVersion = 1,
            containerSize = 2048L,
            sourceSchemaVersion = 1L,
            migratedSchemaVersion = 31L,
            sourceLedgerId = "ledger-local-test",
            targetLedgerId = "ledger-local-test",
            authenticatedArtifactSha256Hex = "ab".repeat(32),
            integrityOk = true,
            foreignKeyOk = true,
            domainOk = true,
            ledgerIdentityCount = 1,
            formalTableCount = 8,
            postingImbalanceCount = 0,
            accountsCount = 3,
            categoriesCount = 12,
            transactionsCount = 45,
            preflightEpochMillis = 1_700_000_000_000L,
        )

    private fun token(): RestorePreflightToken = RestorePreflightToken("tok", 1, "ledger-local-test", ByteArray(32), null)

    private fun restoreState(
        password: String = "",
        runningPreflight: Boolean = false,
        preflightOutcome: RestorePreflightResult? = null,
        preview: RestorePreflightSummary? = null,
        boundToken: RestorePreflightToken? = null,
        runningConfirm: Boolean = false,
        confirmOutcome: BackupRestoreSwitchResult? = null,
    ): P503AppState.BackupRestore =
        P503AppState.BackupRestore(
            overview = overview(),
            password = password,
            runningPreflight = runningPreflight,
            preflightOutcome = preflightOutcome,
            preview = preview,
            token = boundToken,
            runningConfirm = runningConfirm,
            confirmOutcome = confirmOutcome,
        )

    // ---- the one designed open transition ----

    @Test
    fun theOverviewEntryOpensTheSurfaceCarryingTheExactOverview() {
        val overview = overview()
        val opened = assertIs<P503AppState.BackupRestore>(reducer.reduce(overview, P503UiEvent.OpenBackupRestore))
        assertSame(overview, opened.overview)
        assertEquals("", opened.password)
        assertNull(opened.preview)
        assertNull(opened.token)
    }

    // ---- the preflight phase ----

    @Test
    fun aPasswordWriteUpdatesOnlyTheInMemoryDraft() {
        val state = restoreState()
        val updated = assertIs<P503AppState.BackupRestore>(reducer.reduce(state, P503UiEvent.UpdateRestorePassword("correct horse")))
        assertEquals("correct horse", updated.password)
        assertSame(state.overview, updated.overview)
    }

    @Test
    fun thePreflightConfirmSetsItsMarkerAndClearsThePreviousBanner() {
        val state = restoreState(preflightOutcome = RestorePreflightResult.Cancelled)
        val confirmed = assertIs<P503AppState.BackupRestore>(reducer.reduce(state, P503UiEvent.ConfirmRestorePreflight))
        assertTrue(confirmed.runningPreflight)
        assertNull(confirmed.preflightOutcome)
    }

    @Test
    fun aSecondPreflightConfirmWhileRunningIsAbsorbed() {
        val running = restoreState(runningPreflight = true)
        assertSame(running, reducer.reduce(running, P503UiEvent.ConfirmRestorePreflight))
    }

    @Test
    fun aLandedPreviewFillsThePreviewAndBindsTheToken() {
        val running = restoreState(runningPreflight = true)
        val ready = RestorePreflightResult.PreviewReady(summary(), token())
        val landed = assertIs<P503AppState.BackupRestore>(reducer.reduce(running, P503UiEvent.RestorePreflightLanded(ready)))
        assertFalse(landed.runningPreflight)
        assertEquals(ready.summary, landed.preview)
        assertSame(ready.token, landed.token)
    }

    @Test
    fun aLandedRejectionKeepsTheSurfaceWithTheTypedBanner() {
        val running = restoreState(runningPreflight = true)
        val landed =
            assertIs<P503AppState.BackupRestore>(
                reducer.reduce(running, P503UiEvent.RestorePreflightLanded(RestorePreflightResult.Rejected(com.unifiedledger.application.backup.BackupPreflightRejection.P706_CONTAINER_AUTHENTICATION_FAILED))),
            )
        assertFalse(landed.runningPreflight)
        assertEquals(com.unifiedledger.application.backup.BackupPreflightRejection.P706_CONTAINER_AUTHENTICATION_FAILED, landed.preflightOutcome?.let { (it as RestorePreflightResult.Rejected).code })
        assertNull(landed.preview)
    }

    // ---- the confirm phase ----

    @Test
    fun theSwitchConfirmSetsItsMarkerAndClearsThePreflightBanner() {
        val state = restoreState(preview = summary(), boundToken = token(), preflightOutcome = RestorePreflightResult.PreviewReady(summary(), token()))
        val confirmed = assertIs<P503AppState.BackupRestore>(reducer.reduce(state, P503UiEvent.ConfirmBackupRestoreSwitch))
        assertTrue(confirmed.runningConfirm)
        assertNull(confirmed.preflightOutcome)
    }

    @Test
    fun aSwitchConfirmWithoutABoundTokenIsAbsorbed() {
        val state = restoreState(preview = null)
        assertSame(state, reducer.reduce(state, P503UiEvent.ConfirmBackupRestoreSwitch))
    }

    @Test
    fun aSecondSwitchConfirmWhileRunningIsAbsorbed() {
        val running = restoreState(preview = summary(), boundToken = token(), runningConfirm = true)
        assertSame(running, reducer.reduce(running, P503UiEvent.ConfirmBackupRestoreSwitch))
    }

    @Test
    fun aCommittedSwitchClosesBackToThePreservedOverview() {
        // The committed closeout (spec section 3.9): the switch is durable; the surface leaves.
        val running = restoreState(preview = summary(), boundToken = token(), runningConfirm = true)
        val result = BackupRestoreSwitchResult.Committed(runtimeGeneration = 2)
        assertEquals(running.overview, reducer.reduce(running, P503UiEvent.RestoreSwitchResultLanded(result)))
    }

    @Test
    fun aRecoveryRequiredSwitchIsSessionTerminal() {
        // The P3-2 composition obligation: a rollback that also failed ends the session.
        val running = restoreState(preview = summary(), boundToken = token(), runningConfirm = true)
        val terminal = assertIs<P503AppState.RestoreSessionTerminal>(reducer.reduce(running, P503UiEvent.RestoreSwitchResultLanded(BackupRestoreSwitchResult.RecoveryRequired(BackupRestoreRecoveryCause.RollbackReopenFailed))))
        assertEquals(BackupRestoreRecoveryCause.RollbackReopenFailed, terminal.cause)
    }

    @Test
    fun aRolledBackSwitchKeepsTheSurfaceWithTheTypedBanner() {
        val running = restoreState(preview = summary(), boundToken = token(), runningConfirm = true)
        val landed =
            assertIs<P503AppState.BackupRestore>(
                reducer.reduce(running, P503UiEvent.RestoreSwitchResultLanded(BackupRestoreSwitchResult.RolledBack(runtimeGeneration = 1))),
            )
        assertFalse(landed.runningConfirm)
        assertEquals(BackupRestoreSwitchResult.RolledBack(runtimeGeneration = 1), landed.confirmOutcome)
    }

    // ---- close/back and the terminal face ----

    @Test
    fun closeLeavesForThePreservedOverviewWhenNothingRuns() {
        val state = restoreState(preview = summary(), boundToken = token())
        assertSame(state.overview, reducer.reduce(state, P503UiEvent.CloseBackupRestore))
        assertSame(state.overview, reducer.reduce(state, P503UiEvent.Back))
    }

    @Test
    fun aRunningOperationAbsorbsCloseAndBack() {
        val preflighting = restoreState(runningPreflight = true)
        assertSame(preflighting, reducer.reduce(preflighting, P503UiEvent.CloseBackupRestore))
        assertSame(preflighting, reducer.reduce(preflighting, P503UiEvent.Back))
        val confirming = restoreState(runningConfirm = true)
        assertSame(confirming, reducer.reduce(confirming, P503UiEvent.CloseBackupRestore))
    }

    @Test
    fun theSessionTerminalAbsorbsEveryEventIncludingItsOwnFamilyAndExit() {
        // The session-terminal face has no continuation: every event (business, restore family and
        // even Exit — the host wires the exit outside the reducer) is absorbed, never an ISE.
        val terminal = P503AppState.RestoreSessionTerminal(BackupRestoreRecoveryCause.RollbackPublishFailed)
        val events =
            listOf(
                P503UiEvent.OpenBackupRestore,
                P503UiEvent.UpdateRestorePassword("x"),
                P503UiEvent.ConfirmRestorePreflight,
                P503UiEvent.RestorePreflightLanded(RestorePreflightResult.Cancelled),
                P503UiEvent.ConfirmBackupRestoreSwitch,
                P503UiEvent.RestoreSwitchResultLanded(BackupRestoreSwitchResult.Committed(runtimeGeneration = 2)),
                P503UiEvent.CloseBackupRestore,
                P503UiEvent.Back,
                P503UiEvent.Exit,
                P503UiEvent.StartNewExpense,
                P503UiEvent.Confirm,
            )
        for (event in events) {
            assertSame(terminal, reducer.reduce(terminal, event), "$event must be absorbed on the session-terminal face")
        }
    }

    // ---- absorption columns ----

    @Test
    fun theSurfaceEventsAreAbsorbedOutsideTheRestoreState() {
        val surface = restoreState()
        everyStateExcept(surface).forEach { state ->
            assertSame(state, reducer.reduce(state, P503UiEvent.UpdateRestorePassword("x")))
            assertSame(state, reducer.reduce(state, P503UiEvent.ConfirmRestorePreflight))
            assertSame(state, reducer.reduce(state, P503UiEvent.RestorePreflightLanded(RestorePreflightResult.Cancelled)))
            assertSame(state, reducer.reduce(state, P503UiEvent.ConfirmBackupRestoreSwitch))
            assertSame(state, reducer.reduce(state, P503UiEvent.RestoreSwitchResultLanded(BackupRestoreSwitchResult.Committed(runtimeGeneration = 2))))
            assertSame(state, reducer.reduce(state, P503UiEvent.CloseBackupRestore))
        }
    }

    @Test
    fun openBackupRestoreIsAbsorbedOutsideTheOverview() {
        val overview = overview()
        everyStateExcept(overview).forEach { state ->
            assertSame(state, reducer.reduce(state, P503UiEvent.OpenBackupRestore))
        }
    }

    @Test
    fun preExistingAndP705EventsAreAbsorbedOnTheRestoreState() {
        val surface = restoreState()
        val events =
            listOf(
                P503UiEvent.StartNewExpense,
                P503UiEvent.Confirm,
                P503UiEvent.Continue(RequestId("request-2")),
                P503UiEvent.SelectTab(P503Tab.HOME),
                P503UiEvent.RetryRefresh,
                P503UiEvent.RefreshResult(emptyState),
                P503UiEvent.OpenRecycleBin(RecycleBinResult.Success(emptyList())),
                P503UiEvent.RecycleBinResult(RecycleBinResult.Success(emptyList())),
                P503UiEvent.CloseRecycleBin,
                P503UiEvent.OpenTransactionEdit(editOrigin()),
                P503UiEvent.OpenBackupExport,
                P503UiEvent.ConfirmBackupExport,
                P503UiEvent.BackupExportResultLanded(BackupExportResult.Cancelled),
                P503UiEvent.CloseBackupExport,
                P503UiEvent.RetryP705CommitStatusCheck(),
            )
        for (event in events) {
            assertSame(surface, reducer.reduce(surface, event), "$event must be absorbed on BackupRestore")
        }
    }

    @Test
    fun exitStaysIllegalStateOnTheRestoreSurface() {
        assertFailsWith<IllegalStateException> { reducer.reduce(restoreState(), P503UiEvent.Exit) }
    }

    @Test
    fun theStateStringFormNeverContainsANonEmptyPassword() {
        val secret = "s3cr3t-password-value"
        val state = restoreState(password = secret)
        assertFalse(state.toString().contains(secret), "the state's toString must not contain the password")
        assertTrue(state.toString().contains("<redacted>"))
        val failure = assertFailsWith<IllegalStateException> { reducer.reduce(state, P503UiEvent.Exit) }
        assertFalse(failure.message.orEmpty().contains(secret), "the ISE message must not contain the password")
    }

    // ---- helpers ----

    private fun editOrigin(): TransactionEditOrigin =
        TransactionEditOrigin(
            transactionId = com.unifiedledger.domain.TransactionId("tx-1"),
            currentVersionId = com.unifiedledger.domain.TransactionVersionId("version-1"),
            note = "old note",
            statisticsAt = kotlin.time.Instant.parse("2026-09-14T08:00:00Z"),
            amountText = "100.00",
            categoryId = com.unifiedledger.domain.CategoryId("category-food"),
            fundingAccountId = com.unifiedledger.domain.AccountId("asset-payment-local"),
        )

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
            P503AppState.ImportCandidateDetail(
                overview = overview(),
                candidateId = com.unifiedledger.application.ImportCandidateId("candidate-1"),
                detail = com.unifiedledger.application.ImportCandidateDetailResult.Absent,
                duplicates = com.unifiedledger.application.ImportDuplicateReviewsResult.NoDuplicates,
                form = ImportDecisionDraft(),
            ),
            P503AppState.ImportBatchConfirm(overview()),
            P503AppState.ImportBatchSubmitting(overview(), "2026-09-14T08:00:00Z", emptyList()),
            P503AppState.TransactionEdit(overview = overview(), origin = editOrigin()),
            P503AppState.VoidConfirm(overview = overview(), transactionId = transactionId),
            P503AppState.RecycleBin(overview = overview(), rows = RecycleBinResult.Success(emptyList())),
            P503AppState.BackupExport(overview()),
            restoreState(),
            P503AppState.RestoreSessionTerminal(BackupRestoreRecoveryCause.RollbackPublishFailed),
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
