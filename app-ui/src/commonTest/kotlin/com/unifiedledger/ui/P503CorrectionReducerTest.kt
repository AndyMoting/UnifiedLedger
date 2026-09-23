package com.unifiedledger.ui

import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.CorrectTransactionVersionResult
import com.unifiedledger.application.ImportCandidateDetailResult
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportDuplicateReviewsResult
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.RecycleBinResult
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.TransactionCorrectionReceipt
import com.unifiedledger.application.TransactionCorrectionRequestIdentity
import com.unifiedledger.application.TransactionDetailResult
import com.unifiedledger.application.TransactionVoidReceipt
import com.unifiedledger.application.TransactionVoidRequestIdentity
import com.unifiedledger.application.VoidTransactionResult
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.P705FailureCode
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.TransactionVoidFactKind
import com.unifiedledger.domain.VoidReasonCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-05 state-machine extension tests (D-156; spec sections 3.2-3.5/4.4): every new event has its
 * designed effect only in its designed state and is absorbed in every other state; no new event
 * throws anywhere; every pre-existing ISE path stays locked (G-B, including `Exit` on the new
 * states, spec section 6.3). The correction form is an independent surface (spec section 4.4) —
 * it never reuses the P7-02 entry-field retention discipline.
 *
 * Reducer-level spec vectors: V-04 (cancel/reject zero effect), V-09 (stale CAS is its own
 * surface, never folded into `Rejected`), V-11 (restore rejection keeps the transaction voided in
 * the bin), V-19 (a lost commit keeps the per-operation marker — no auto-retry, no requestId swap,
 * no leave). The stale/restore-admissibility *decisions* themselves live in the application use
 * cases and are asserted there; this suite pins the state/event mapping only.
 */
class P503CorrectionReducerTest {
    private val ledgerId = LedgerId("ledger-local-test")
    private val cny = CurrencyUnit("CNY", 2)
    private val reducer: P503Reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)
    private val emptyState = LedgerCurrentState(ledgerId, transactions = emptyList(), balances = emptyList())
    private val statisticsAt = Instant.parse("2026-03-15T02:00:00Z")
    private val transactionId = TransactionId("tx-1")
    private val versionId = TransactionVersionId("version-1")

    // ---- fixtures ----

    private fun overview(): P503AppState.OverviewEmpty = P503AppState.OverviewEmpty(emptyState)

    private fun origin(): TransactionEditOrigin =
        TransactionEditOrigin(
            transactionId = transactionId,
            currentVersionId = versionId,
            note = "old note",
            statisticsAt = statisticsAt,
            amountText = "100.00",
            categoryId = CategoryId("category-food"),
            fundingAccountId = AccountId("asset-payment-local"),
        )

    private fun detail(): P503AppState.TransactionDetail =
        P503AppState.TransactionDetail(
            overview = overview(),
            originTab = P503Tab.HOME,
            transactionId = transactionId,
            detail = TransactionDetailResult.NotFound,
        )

    private fun editing(
        draft: TransactionCorrectionDraft = transactionCorrectionDraftFromOrigin(origin()),
        preview: TransactionEditPreview? = null,
        requestId: RequestId? = null,
        submitting: Boolean = false,
        notice: P705Notice? = null,
        checkOutcome: P705CommitCheckOutcome = P705CommitCheckOutcome.NONE,
    ): P503AppState.TransactionEdit =
        P503AppState.TransactionEdit(
            overview = overview(),
            origin = origin(),
            draft = draft,
            preview = preview,
            requestId = requestId,
            submitting = submitting,
            notice = notice,
            checkOutcome = checkOutcome,
        )

    private fun voidConfirm(
        reason: VoidReasonDraft = VoidReasonDraft(),
        requestId: RequestId? = null,
        submitting: Boolean = false,
        notice: P705Notice? = null,
        checkOutcome: P705CommitCheckOutcome = P705CommitCheckOutcome.NONE,
    ): P503AppState.VoidConfirm =
        P503AppState.VoidConfirm(
            overview = overview(),
            transactionId = transactionId,
            reason = reason,
            requestId = requestId,
            submitting = submitting,
            notice = notice,
            checkOutcome = checkOutcome,
        )

    private fun recycleBin(
        rows: RecycleBinResult = RecycleBinResult.Success(emptyList()),
        restore: RestoreConfirm? = null,
    ): P503AppState.RecycleBin = P503AppState.RecycleBin(overview = overview(), rows = rows, restore = restore)

    private fun correctionReceipt(): TransactionCorrectionReceipt =
        TransactionCorrectionReceipt(
            confirmationId = ConfirmationId("confirmation-1"),
            transactionId = transactionId,
            versionId = TransactionVersionId("version-2"),
            expectedCurrentVersionId = versionId,
        )

    private fun voidReceipt(): TransactionVoidReceipt =
        TransactionVoidReceipt(
            confirmationId = ConfirmationId("confirmation-1"),
            transactionId = transactionId,
            factId = "fact-1",
            factKind = TransactionVoidFactKind.VOID,
        )

    // ---- open events (DP-12: 仅详情入口) ----

    @Test
    fun openTransactionEditEntersOnlyFromTheDetailAndPreservesTheOverview() {
        val source = detail()
        val opened = assertIs<P503AppState.TransactionEdit>(reducer.reduce(source, P503UiEvent.OpenTransactionEdit(origin())))
        assertSame(source.overview, opened.overview)
        assertEquals(versionId, opened.origin.currentVersionId)
        assertNull(opened.requestId)
        assertEquals(false, opened.submitting)
        // The overview does not afford the correction surface: absorbed there.
        val overviewState = overview()
        assertSame(overviewState, reducer.reduce(overviewState, P503UiEvent.OpenTransactionEdit(origin())))
    }

    @Test
    fun openVoidConfirmEntersOnlyFromTheDetailWithNoCasTarget() {
        val source = detail()
        val opened = assertIs<P503AppState.VoidConfirm>(reducer.reduce(source, P503UiEvent.OpenVoidConfirm(transactionId)))
        assertSame(source.overview, opened.overview)
        assertEquals(transactionId, opened.transactionId)
        // The merged void/restore family carries no version CAS (the fact sequence guards it,
        // DP-8): the page holds only its target id and its pure reason form.
        assertEquals(VoidReasonDraft(), opened.reason)
        assertNull(opened.requestId)
        assertEquals(false, opened.submitting)
        val overviewState = overview()
        assertSame(overviewState, reducer.reduce(overviewState, P503UiEvent.OpenVoidConfirm(transactionId)))
    }

    @Test
    fun openRecycleBinEntersFromTheEffectiveSurfacesAndCarriesTheProjection() {
        val rows = RecycleBinResult.Success(emptyList())
        val overviewState = overview()
        val fromOverview = assertIs<P503AppState.RecycleBin>(reducer.reduce(overviewState, P503UiEvent.OpenRecycleBin(rows)))
        assertSame(overviewState, fromOverview.overview)
        assertSame(rows, fromOverview.rows)
        assertNull(fromOverview.restore)
        val source = detail()
        val fromDetail = assertIs<P503AppState.RecycleBin>(reducer.reduce(source, P503UiEvent.OpenRecycleBin(rows)))
        assertSame(source.overview, fromDetail.overview)
    }

    // ---- correction form / preview / confirm (spec section 4.4) ----

    @Test
    fun theCorrectionFormIsAnIndependentDraftAndThePreviewIsAPureRead() {
        val state = editing()
        val updated =
            assertIs<P503AppState.TransactionEdit>(
                reducer.reduce(state, P503UiEvent.UpdateTransactionCorrectionField(TransactionCorrectionFieldUpdate.Amount("80.00"))),
            )
        assertEquals("80.00", updated.draft.amountText)
        // The preview is display-only and never a commit permission (spec section 3.2).
        val previewed = assertIs<P503AppState.TransactionEdit>(reducer.reduce(updated, P503UiEvent.PreviewTransactionEdit))
        val fields = checkNotNull(previewed.preview).fields
        assertEquals(TransactionEditField.entries.toList(), fields.map { it.field })
        val amount = fields.first { it.field == TransactionEditField.AMOUNT }
        assertEquals("100.00", amount.oldText)
        assertEquals("80.00", amount.newText)
        assertTrue(amount.changed)
        val note = fields.first { it.field == TransactionEditField.NOTE }
        assertEquals("old note", note.oldText)
        assertEquals(false, note.changed)
        // A preview is a pure read: no requestId is minted and the surface is not submitting.
        assertNull(previewed.requestId)
        assertEquals(false, previewed.submitting)
    }

    @Test
    fun confirmTransactionEditSetsTheMarkerAndAbsorbsAReEntry() {
        val state = editing()
        val confirming = assertIs<P503AppState.TransactionEdit>(reducer.reduce(state, P503UiEvent.ConfirmTransactionEdit(RequestId("request-1"))))
        assertEquals(RequestId("request-1"), confirming.requestId)
        assertTrue(confirming.submitting)
        // 提交中不重入: a second confirm while the first is in flight changes nothing.
        assertSame(confirming, reducer.reduce(confirming, P503UiEvent.ConfirmTransactionEdit(RequestId("request-2"))))
        // 提交中不得离开: the surface cannot leave while the commit is in flight (the host guards
        // intercept the back; the reducer absorbs both exits).
        assertSame(confirming, reducer.reduce(confirming, P503UiEvent.Back))
        assertSame(confirming, reducer.reduce(confirming, P503UiEvent.Cancel))
    }

    // ---- V-04 / V-09: zero effect on cancel and reject; stale is its own surface ----

    @Test
    fun cancelAndBackReturnTheCorrectionSurfaceToThePreservedOverviewWithZeroEffect() {
        val state = editing(preview = TransactionEditPreview(emptyList()))
        assertSame(state.overview, reducer.reduce(state, P503UiEvent.Cancel))
        assertSame(state.overview, reducer.reduce(state, P503UiEvent.Back))
    }

    @Test
    fun aDeterminateCorrectionSuccessLeavesToThePreservedOverview() {
        val state = editing(requestId = RequestId("request-1"), submitting = true)
        assertSame(state.overview, reducer.reduce(state, P503UiEvent.TransactionEditResult(CorrectTransactionVersionResult.Created(correctionReceipt()))))
        assertSame(state.overview, reducer.reduce(state, P503UiEvent.TransactionEditResult(CorrectTransactionVersionResult.NoChange(correctionReceipt()))))
    }

    @Test
    fun aRejectedCorrectionSurfacesItsCodeAndKeepsTheSurface() {
        val state = editing(requestId = RequestId("request-1"), submitting = true)
        val rejected =
            assertIs<P503AppState.TransactionEdit>(
                reducer.reduce(state, P503UiEvent.TransactionEditResult(CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_KIND_NOT_SUPPORTED))),
            )
        assertEquals(P705Notice.Rejected(P705FailureCode.P705_KIND_NOT_SUPPORTED), rejected.notice)
        assertEquals(false, rejected.submitting)
        assertSame(state.overview, rejected.overview)
    }

    @Test
    fun staleCurrentVersionIsItsOwnNoticeAndIsNeverFoldedIntoRejected() {
        val state = editing(requestId = RequestId("request-1"), submitting = true)
        val stale =
            assertIs<P503AppState.TransactionEdit>(
                reducer.reduce(state, P503UiEvent.TransactionEditResult(CorrectTransactionVersionResult.StaleCurrentVersion)),
            )
        assertEquals(P705Notice.StaleCurrentVersion, stale.notice)
        // The frozen result surface keeps stale distinct from a rejection (spec section 3.2).
        assertTrue(stale.notice !is P705Notice.Rejected)
        assertEquals(false, stale.submitting)
        assertSame(state.overview, stale.overview)
    }

    @Test
    fun aCorrectionIdentityConflictSurfacesItsOwnNotice() {
        val state = editing(requestId = RequestId("request-1"), submitting = true)
        val conflict =
            assertIs<P503AppState.TransactionEdit>(
                reducer.reduce(
                    state,
                    P503UiEvent.TransactionEditResult(
                        CorrectTransactionVersionResult.RequestIdentityConflict(TransactionCorrectionRequestIdentity(ledgerId, RequestId("request-1"))),
                    ),
                ),
            )
        assertEquals(P705Notice.RequestIdentityConflict(RequestId("request-1")), conflict.notice)
        assertEquals(false, conflict.submitting)
    }

    // ---- V-19 (D-173): the manual re-check of a lost commit ----

    @Test
    fun aRecheckRequestIsStatePreservingOnEverySurface() {
        // The re-check REQUEST carries no outcome: it leaves the surface instance untouched (不切态,
        // the P7-02 RetryCommitStatusCheck precedent), so the host's per-instance guard is not
        // disturbed and no new requestId can be minted by the reducer.
        val edit = editing(requestId = RequestId("request-1"), submitting = true)
        assertSame(edit, reducer.reduce(edit, P503UiEvent.RetryP705CommitStatusCheck()))
        val void = voidConfirm(requestId = RequestId("request-1"), submitting = true)
        assertSame(void, reducer.reduce(void, P503UiEvent.RetryP705CommitStatusCheck()))
        val restore = recycleBin(restore = RestoreConfirm(transactionId = transactionId, requestId = RequestId("request-1"), submitting = true))
        assertSame(restore, reducer.reduce(restore, P503UiEvent.RetryP705CommitStatusCheck()))
        // A surface with no lost commit in flight absorbs the landing too (defensive).
        val idle = editing(requestId = RequestId("request-1"))
        assertSame(idle, reducer.reduce(idle, P503UiEvent.RetryP705CommitStatusCheck(P705CommitCheckOutcome.ABSENT)))
    }

    @Test
    fun anAbsentOrUnavailableRecheckKeepsTheSurfaceSubmittingAndRecheckable() {
        for (outcome in listOf(P705CommitCheckOutcome.ABSENT, P705CommitCheckOutcome.UNAVAILABLE)) {
            val edit = editing(requestId = RequestId("request-1"), submitting = true)
            val checked = assertIs<P503AppState.TransactionEdit>(reducer.reduce(edit, P503UiEvent.RetryP705CommitStatusCheck(outcome)))
            assertEquals(outcome, checked.checkOutcome)
            // The marker stands: still unknown, still no automatic retry and no requestId swap.
            assertTrue(checked.submitting)
            assertEquals(RequestId("request-1"), checked.requestId)
            assertSame(checked, reducer.reduce(checked, P503UiEvent.Back))
            // The surface remains re-checkable: a later hit still leaves it (the marker never blocks).
            assertSame(checked.overview, reducer.reduce(checked, P503UiEvent.TransactionEditResult(CorrectTransactionVersionResult.NoChange(correctionReceipt()))))

            val void = voidConfirm(requestId = RequestId("request-1"), submitting = true)
            assertEquals(outcome, assertIs<P503AppState.VoidConfirm>(reducer.reduce(void, P503UiEvent.RetryP705CommitStatusCheck(outcome))).checkOutcome)

            val restore = recycleBin(restore = RestoreConfirm(transactionId = transactionId, requestId = RequestId("request-1"), submitting = true))
            val checkedRestore = assertIs<P503AppState.RecycleBin>(reducer.reduce(restore, P503UiEvent.RetryP705CommitStatusCheck(outcome)))
            assertEquals(outcome, checkedRestore.restore?.checkOutcome)
            assertTrue(checkNotNull(checkedRestore.restore).submitting)
        }
    }

    @Test
    fun aRecheckHitLandsAsADeterminateSuccessAndAConflictAsTheExistingNotice() {
        // A re-check hit returns the original receipt as NoChange (the host's resolver mapping),
        // which the reducer treats exactly like Created: leave to the preserved overview.
        val edit = editing(requestId = RequestId("request-1"), submitting = true, checkOutcome = P705CommitCheckOutcome.ABSENT)
        assertSame(edit.overview, reducer.reduce(edit, P503UiEvent.TransactionEditResult(CorrectTransactionVersionResult.NoChange(correctionReceipt()))))
        // A snapshot conflict keeps the existing notice mapping and clears the marker.
        val conflict =
            assertIs<P503AppState.TransactionEdit>(
                reducer.reduce(
                    edit,
                    P503UiEvent.TransactionEditResult(
                        CorrectTransactionVersionResult.RequestIdentityConflict(TransactionCorrectionRequestIdentity(ledgerId, RequestId("request-1"))),
                    ),
                ),
            )
        assertEquals(P705Notice.RequestIdentityConflict(RequestId("request-1")), conflict.notice)
        assertEquals(false, conflict.submitting)
        // The void/restore family follows the same mapping.
        val restore =
            recycleBin(
                restore = RestoreConfirm(transactionId = transactionId, requestId = RequestId("request-1"), submitting = true, checkOutcome = P705CommitCheckOutcome.UNAVAILABLE),
            )
        assertSame(restore.overview, reducer.reduce(restore, P503UiEvent.TransactionRestoreResult(VoidTransactionResult.NoChange(voidReceipt()))))
        val void = voidConfirm(requestId = RequestId("request-1"), submitting = true, checkOutcome = P705CommitCheckOutcome.ABSENT)
        assertSame(void.overview, reducer.reduce(void, P503UiEvent.TransactionVoidResult(VoidTransactionResult.NoChange(voidReceipt()))))
    }

    @Test
    fun aLostCorrectionCommitKeepsTheMarkerWithNoAutoRetryAndNoRequestIdSwap() {
        val confirming = assertIs<P503AppState.TransactionEdit>(reducer.reduce(editing(), P503UiEvent.ConfirmTransactionEdit(RequestId("request-1"))))
        // The result never arrived (the host owns the snapshot-aware resolve): the marker stands,
        // the requestId is unchanged and no automatic leave/refresh path opens.
        assertTrue(confirming.submitting)
        assertEquals(RequestId("request-1"), confirming.requestId)
        assertSame(confirming, reducer.reduce(confirming, P503UiEvent.RefreshResult(emptyState)))
        assertSame(confirming, reducer.reduce(confirming, P503UiEvent.RetryRefresh))
        assertSame(confirming, reducer.reduce(confirming, P503UiEvent.Back))
    }

    // ---- void reason / confirm / result ----

    @Test
    fun theVoidReasonFormIsPureAndConfirmSetsTheMarker() {
        val state = voidConfirm()
        val coded =
            assertIs<P503AppState.VoidConfirm>(
                reducer.reduce(state, P503UiEvent.UpdateVoidReasonField(VoidReasonFieldUpdate.Code(VoidReasonCode.MIS_ENTERED))),
            )
        assertEquals(VoidReasonCode.MIS_ENTERED, coded.reason.code)
        val noted =
            assertIs<P503AppState.VoidConfirm>(
                reducer.reduce(coded, P503UiEvent.UpdateVoidReasonField(VoidReasonFieldUpdate.Note("typed by hand"))),
            )
        assertEquals("typed by hand", noted.reason.note)
        val confirming = assertIs<P503AppState.VoidConfirm>(reducer.reduce(noted, P503UiEvent.ConfirmVoid(RequestId("request-1"))))
        assertTrue(confirming.submitting)
        assertSame(confirming, reducer.reduce(confirming, P503UiEvent.ConfirmVoid(RequestId("request-2"))))
    }

    @Test
    fun aDeterminateVoidSuccessLeavesToThePreservedOverviewAndRejectionsSurface() {
        val state = voidConfirm(requestId = RequestId("request-1"), submitting = true)
        assertSame(state.overview, reducer.reduce(state, P503UiEvent.TransactionVoidResult(VoidTransactionResult.Created(voidReceipt()))))
        assertSame(state.overview, reducer.reduce(state, P503UiEvent.TransactionVoidResult(VoidTransactionResult.NoChange(voidReceipt()))))
        val rejected =
            assertIs<P503AppState.VoidConfirm>(
                reducer.reduce(state, P503UiEvent.TransactionVoidResult(VoidTransactionResult.Rejected(P705FailureCode.P705_REFUND_LINKED_VOID_NOT_SUPPORTED))),
            )
        assertEquals(P705Notice.Rejected(P705FailureCode.P705_REFUND_LINKED_VOID_NOT_SUPPORTED), rejected.notice)
        assertEquals(false, rejected.submitting)
        val conflict =
            assertIs<P503AppState.VoidConfirm>(
                reducer.reduce(
                    state,
                    P503UiEvent.TransactionVoidResult(
                        VoidTransactionResult.RequestIdentityConflict(TransactionVoidRequestIdentity(ledgerId, RequestId("request-1"))),
                    ),
                ),
            )
        assertEquals(P705Notice.RequestIdentityConflict(RequestId("request-1")), conflict.notice)
        // Cancel/Back return to the preserved overview (zero writes) on a non-submitting page.
        val idle = voidConfirm(requestId = RequestId("request-1"))
        assertSame(idle.overview, reducer.reduce(idle, P503UiEvent.Cancel))
        assertSame(idle.overview, reducer.reduce(idle, P503UiEvent.Back))
    }

    // ---- recycle bin / nested restore ----

    @Test
    fun theBinListRefreshesInPlaceAndClosesToThePreservedOverview() {
        val state = recycleBin()
        val refreshed = assertIs<P503AppState.RecycleBin>(reducer.reduce(state, P503UiEvent.RecycleBinResult(RecycleBinResult.Success(emptyList()))))
        assertSame(state.overview, refreshed.overview)
        assertNull(refreshed.restore)
        assertSame(state.overview, reducer.reduce(state, P503UiEvent.CloseRecycleBin))
        assertSame(state.overview, reducer.reduce(state, P503UiEvent.Back))
    }

    @Test
    fun oneBinRowOpensTheNestedRestoreConfirmAndCloseKeepsTheList() {
        val state = recycleBin()
        val restoring = assertIs<P503AppState.RecycleBin>(reducer.reduce(state, P503UiEvent.OpenRestoreConfirm(transactionId)))
        assertEquals(transactionId, restoring.restore?.transactionId)
        assertSame(state.rows, restoring.rows)
        // A second open while one is shown stays on the open sub-state.
        assertSame(restoring, reducer.reduce(restoring, P503UiEvent.OpenRestoreConfirm(TransactionId("tx-2"))))
        val closed = assertIs<P503AppState.RecycleBin>(reducer.reduce(restoring, P503UiEvent.CloseRestoreConfirm))
        assertNull(closed.restore)
        assertSame(state.rows, closed.rows)
        // Back closes the nested sub-state first (the catalog-dialog Back precedent) and only
        // leaves the bin when no sub-state is open.
        val backFromNested = assertIs<P503AppState.RecycleBin>(reducer.reduce(restoring, P503UiEvent.Back))
        assertNull(backFromNested.restore)
        assertSame(restoring.overview, reducer.reduce(closed, P503UiEvent.Back))
    }

    @Test
    fun theRestoreReasonFormIsPureAndConfirmSetsTheMarker() {
        val restoring = recycleBin(restore = RestoreConfirm(transactionId = transactionId))
        val coded =
            assertIs<P503AppState.RecycleBin>(
                reducer.reduce(restoring, P503UiEvent.UpdateRestoreReasonField(VoidReasonFieldUpdate.Code(VoidReasonCode.MIS_ENTERED))),
            )
        assertEquals(VoidReasonCode.MIS_ENTERED, coded.restore?.reason?.code)
        val confirming =
            assertIs<P503AppState.RecycleBin>(
                reducer.reduce(coded, P503UiEvent.ConfirmRestore(RequestId("request-1"))),
            )
        assertTrue(checkNotNull(confirming.restore).submitting)
        assertEquals(RequestId("request-1"), checkNotNull(confirming.restore).requestId)
        // 提交中不重入: a duplicate confirm is absorbed.
        assertSame(confirming, reducer.reduce(confirming, P503UiEvent.ConfirmRestore(RequestId("request-2"))))
    }

    @Test
    fun aDeterminateRestoreSuccessLeavesToThePreservedOverview() {
        val restoring = recycleBin(restore = RestoreConfirm(transactionId = transactionId, requestId = RequestId("request-1"), submitting = true))
        assertSame(restoring.overview, reducer.reduce(restoring, P503UiEvent.TransactionRestoreResult(VoidTransactionResult.Created(voidReceipt()))))
        assertSame(restoring.overview, reducer.reduce(restoring, P503UiEvent.TransactionRestoreResult(VoidTransactionResult.NoChange(voidReceipt()))))
    }

    // ---- V-11: a rejected restore keeps the transaction voided in the bin ----

    @Test
    fun aRejectedRestoreSurfacesItsCodeAndKeepsTheBinOpen() {
        val restoring = recycleBin(restore = RestoreConfirm(transactionId = transactionId, requestId = RequestId("request-1"), submitting = true))
        val rejected =
            assertIs<P503AppState.RecycleBin>(
                reducer.reduce(
                    restoring,
                    P503UiEvent.TransactionRestoreResult(VoidTransactionResult.Rejected(P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE)),
                ),
            )
        assertEquals(P705Notice.Rejected(P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE), rejected.restore?.notice)
        assertEquals(false, rejected.restore?.submitting)
        // The bin stays open and the rows are unchanged: the transaction stays voided.
        assertSame(restoring.rows, rejected.rows)
        assertSame(restoring.overview, rejected.overview)
    }

    // ---- F1: a restore commit in flight never leaves the bin (提交中不得离开) ----

    @Test
    fun aSubmittingRestoreAbsorbsEveryLeavePathAndAnIdleOneBehavesAsBefore() {
        val submitting = recycleBin(restore = RestoreConfirm(transactionId = transactionId, requestId = RequestId("request-1"), submitting = true))
        // Every leave path absorbs while the restore commit is in flight: the bin close, the
        // nested close and the Back that would close the sub-state or leave the bin.
        assertSame(submitting, reducer.reduce(submitting, P503UiEvent.CloseRecycleBin))
        assertSame(submitting, reducer.reduce(submitting, P503UiEvent.CloseRestoreConfirm))
        assertSame(submitting, reducer.reduce(submitting, P503UiEvent.Back))
        // With the sub-state not submitting the three paths keep their frozen behavior: the bin
        // close leaves for the preserved overview, Back closes the nested sub-state first (keeping
        // the bin and its rows), the nested close drops the sub-state, and a bare bin Back leaves
        // for the preserved overview.
        val idle = recycleBin(restore = RestoreConfirm(transactionId = transactionId, requestId = RequestId("request-1")))
        assertSame(idle.overview, reducer.reduce(idle, P503UiEvent.CloseRecycleBin))
        val backFromNested = assertIs<P503AppState.RecycleBin>(reducer.reduce(idle, P503UiEvent.Back))
        assertNull(backFromNested.restore)
        assertSame(idle.rows, backFromNested.rows)
        val closed = assertIs<P503AppState.RecycleBin>(reducer.reduce(idle, P503UiEvent.CloseRestoreConfirm))
        assertNull(closed.restore)
        assertSame(idle.rows, closed.rows)
        val bare = recycleBin()
        assertSame(bare.overview, reducer.reduce(bare, P503UiEvent.Back))
    }

    // ---- absorbed columns (table 6.2a: every new event absorbed outside its designed states) ----

    @Test
    fun newEventsAreAbsorbedOutsideTheirDesignedStates() {
        val edit = editing()
        val void = voidConfirm()
        // The exclusions must match the exact instances `allStates()` builds (structural equality).
        val bin = recycleBin()
        // OpenTransactionEdit/OpenVoidConfirm act on the detail; OpenRecycleBin acts on the
        // overview and the detail. Those three sources are excluded per event.
        val detailState = detail()
        val overviewState = overview()

        everyStateExcept(detailState).forEach { state ->
            assertSame(state, reducer.reduce(state, P503UiEvent.OpenTransactionEdit(origin())))
            assertSame(state, reducer.reduce(state, P503UiEvent.OpenVoidConfirm(transactionId)))
        }
        everyStateExcept(overviewState, detailState, bin).forEach { state ->
            assertSame(state, reducer.reduce(state, P503UiEvent.OpenRecycleBin(RecycleBinResult.Success(emptyList()))))
        }

        everyStateExcept(edit).forEach { state ->
            assertSame(state, reducer.reduce(state, P503UiEvent.UpdateTransactionCorrectionField(TransactionCorrectionFieldUpdate.Note("x"))))
            assertSame(state, reducer.reduce(state, P503UiEvent.PreviewTransactionEdit))
            assertSame(state, reducer.reduce(state, P503UiEvent.ConfirmTransactionEdit(RequestId("request-1"))))
            assertSame(state, reducer.reduce(state, P503UiEvent.TransactionEditResult(CorrectTransactionVersionResult.StaleCurrentVersion)))
        }
        everyStateExcept(void).forEach { state ->
            assertSame(state, reducer.reduce(state, P503UiEvent.UpdateVoidReasonField(VoidReasonFieldUpdate.Note("x"))))
            assertSame(state, reducer.reduce(state, P503UiEvent.ConfirmVoid(RequestId("request-1"))))
            assertSame(state, reducer.reduce(state, P503UiEvent.TransactionVoidResult(VoidTransactionResult.Rejected(P705FailureCode.P705_TRANSACTION_VOIDED))))
        }
        everyStateExcept(bin).forEach { state ->
            assertSame(state, reducer.reduce(state, P503UiEvent.RecycleBinResult(RecycleBinResult.Success(emptyList()))))
            assertSame(state, reducer.reduce(state, P503UiEvent.CloseRecycleBin))
            assertSame(state, reducer.reduce(state, P503UiEvent.OpenRestoreConfirm(transactionId)))
            assertSame(state, reducer.reduce(state, P503UiEvent.UpdateRestoreReasonField(VoidReasonFieldUpdate.Note("x"))))
            assertSame(state, reducer.reduce(state, P503UiEvent.ConfirmRestore(RequestId("request-1"))))
            assertSame(state, reducer.reduce(state, P503UiEvent.TransactionRestoreResult(VoidTransactionResult.Rejected(P705FailureCode.P705_TRANSACTION_NOT_VOIDED))))
            assertSame(state, reducer.reduce(state, P503UiEvent.CloseRestoreConfirm))
        }
        // V-19 (D-173): the re-check intent is state-preserving in EVERY state (its designed effect
        // is the host's read-only resolve, not a state transition); the absent/unavailable landing is
        // absorbed in every state whose surface holds no lost commit (all of these fixtures).
        allStates().forEach { state ->
            assertSame(state, reducer.reduce(state, P503UiEvent.RetryP705CommitStatusCheck()))
            assertSame(state, reducer.reduce(state, P503UiEvent.RetryP705CommitStatusCheck(P705CommitCheckOutcome.ABSENT)))
            assertSame(state, reducer.reduce(state, P503UiEvent.RetryP705CommitStatusCheck(P705CommitCheckOutcome.UNAVAILABLE)))
        }
    }

    @Test
    fun preExistingEventsAreAbsorbedOnAllThreeNewStates() {
        val events =
            listOf(
                P503UiEvent.StartNewExpense,
                P503UiEvent.Confirm,
                P503UiEvent.Continue(RequestId("request-2")),
                P503UiEvent.SelectTab(P503Tab.HOME),
                P503UiEvent.RetryRefresh,
                P503UiEvent.RefreshResult(emptyState),
                P503UiEvent.SelectTransaction(transactionId, TransactionDetailResult.NotFound),
                P503UiEvent.SelectMonth(kotlinx.datetime.YearMonth(2026, 3)),
                P503UiEvent.RefreshImportReview,
                P503UiEvent.RequestImportBatchConfirm,
                P503UiEvent.ImportGroupEnumerationCompleted,
                P503UiEvent.SubmitImportDuplicateReview(ImportDuplicateReviewUiDecision.CONFIRMED_DUPLICATE, "user-reviewed"),
            )
        for (state in listOf<P503AppState>(editing(), voidConfirm(), recycleBin())) {
            for (event in events) {
                assertSame(state, reducer.reduce(state, event))
            }
        }
    }

    // ---- locked ISE behavior (G-B: unlisted combinations stay programming errors) ----

    @Test
    fun exitStaysIllegalStateOnAllThreeNewStates() {
        assertFailsWith<IllegalStateException> { reducer.reduce(editing(), P503UiEvent.Exit) }
        assertFailsWith<IllegalStateException> { reducer.reduce(voidConfirm(), P503UiEvent.Exit) }
        assertFailsWith<IllegalStateException> { reducer.reduce(recycleBin(), P503UiEvent.Exit) }
    }

    @Test
    fun preExistingUnlistedCombinationsStayIllegalState() {
        // No ISE reversal anywhere: the overview's Back floor and a monthly retry on the
        // correction surface stay unlisted.
        assertFailsWith<IllegalStateException> { reducer.reduce(overview(), P503UiEvent.Back) }
        assertFailsWith<IllegalStateException> { reducer.reduce(overview(), P503UiEvent.RetryRefresh) }
    }

    // ---- helpers ----

    private fun everyStateExcept(vararg excluded: P503AppState): List<P503AppState> = allStates().filterNot { it in excluded }

    private fun allStates(): List<P503AppState> {
        val draft = ManualExpenseDraft(AccountId("asset-payment-local"), null, "35.80", statisticsAt)
        val requestId = RequestId("request-1")
        return listOf(
            P503AppState.Ready,
            overview(),
            detail(),
            P503AppState.ImportCandidateDetail(
                overview = overview(),
                candidateId = ImportCandidateId("candidate-1"),
                detail = ImportCandidateDetailResult.Absent,
                duplicates = ImportDuplicateReviewsResult.NoDuplicates,
                form = ImportDecisionDraft(),
            ),
            P503AppState.ImportBatchConfirm(overview()),
            P503AppState.ImportBatchSubmitting(overview(), "2026-09-14T08:00:00Z", emptyList()),
            editing(),
            voidConfirm(),
            recycleBin(),
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
