package com.unifiedledger.ui

import com.unifiedledger.application.EntryType
import com.unifiedledger.application.ExpenseDraft
import com.unifiedledger.application.IncomeDraft
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.TransferDraft
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.MerchantId
import com.unifiedledger.domain.TagId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-08 08.B-2 (D-221 residual R-221-1; spec sections 4.1/4.2/4.4) reducer contracts:
 *
 * - the optional tag/merchant association is written on the LIVE draft and is kind-independent
 *   (all five kinds carry it);
 * - record-again CLEARS the association (spec section 4.2 frozen) — the retained intent carries
 *   none, so the rebuilt draft starts with no association (P708-A07's "再记一笔不带旧关联");
 * - switching the entry type RETAINS the association (spec section 4.2 frozen);
 * - the annotation-edit surface replaces the whole set, marks 提交中, and surfaces its outcome.
 *
 * These pin the state machine; the store/command semantics are pinned in `ledger-data`. No P708
 * vector is marked PASS by this file — it delivers the batch's evidence for the main agent's
 * acceptance.
 */
class P503AnnotationReducerTest {
    private val ledgerId = LedgerId("ledger-annotation-test")
    private val occurredAt = Instant.parse("2026-01-15T00:30:00Z")
    private val nowInstant = Instant.parse("2026-09-12T00:00:00Z")
    private val requestId = com.unifiedledger.application.RequestId("request-annotation-1")
    private val accountId = AccountId("account-1")
    private val expenseCategoryId = CategoryId("expense-cat")
    private val tagA = TagId("tag-a")
    private val tagB = TagId("tag-b")
    private val merchant = MerchantId("merchant-1")
    private val currentState = LedgerCurrentStateForTests.empty(ledgerId)
    private val overviewState = P503AppState.OverviewEmpty(state = currentState)
    private val ledgerClock = LedgerClock { nowInstant }
    private val reducer: P503Reducer = P503ReducerImpl(com.unifiedledger.application.ParseManualExpenseAmount(), com.unifiedledger.domain.CurrencyUnit("CNY", 2), ledgerClock, com.unifiedledger.application.EntryExpressionEvaluator())

    private fun editing(draft: com.unifiedledger.application.TypedEntryDraft) = P503AppState.Editing(draft = draft, requestId = requestId, overview = currentState, originTab = P503Tab.HOME)

    // ---- tag/merchant writes on the live draft (spec section 4.1) ----

    @Test
    fun p708b2UpdateTagSelectionWritesTheWholeSetOnTheDraft() {
        val state = editing(ExpenseDraft(accountId, expenseCategoryId, "35.80", occurredAt))
        val updated = assertIs<P503AppState.Editing>(reducer.reduce(state, P503UiEvent.UpdateTagSelection(setOf(tagA, tagB))))
        assertEquals(setOf(tagA, tagB), updated.draft.tagIds)
    }

    @Test
    fun p708b2UpdateMerchantWritesTheDraftAndNullClearsIt() {
        val state = editing(ExpenseDraft(accountId, expenseCategoryId, "35.80", occurredAt))
        val set = assertIs<P503AppState.Editing>(reducer.reduce(state, P503UiEvent.UpdateMerchant(merchant)))
        assertEquals(merchant, set.draft.merchantId)
        val cleared = assertIs<P503AppState.Editing>(reducer.reduce(set, P503UiEvent.UpdateMerchant(null)))
        assertEquals(null, cleared.draft.merchantId)
    }

    @Test
    fun p708b2AssociationIsKindIndependentAcrossTheFiveDrafts() {
        // The association lives on the transaction root, so every kind carries the same writes
        // (spec section 4.1; the plan forbids proving the five-kind matrix with one expense).
        val drafts =
            listOf(
                ExpenseDraft(accountId, expenseCategoryId, "1.00", occurredAt).withTagIds(setOf(tagA)).withMerchantId(merchant),
                IncomeDraft(accountId, expenseCategoryId, "1.00", occurredAt).withTagIds(setOf(tagA)).withMerchantId(merchant),
                TransferDraft(accountId, null, "1.00", occurredAt = occurredAt).withTagIds(setOf(tagA)).withMerchantId(merchant),
                com.unifiedledger.application
                    .LendDraft(null, "1.00", accountId, occurredAt)
                    .withTagIds(setOf(tagA))
                    .withMerchantId(merchant),
                com.unifiedledger.application
                    .CollectDraft(null, "1.00", "1.00", "0.00", "0.00", expenseCategoryId, accountId, occurredAt)
                    .withTagIds(setOf(tagA))
                    .withMerchantId(merchant),
            )
        drafts.forEach { draft ->
            assertEquals(setOf(tagA), draft.tagIds, "kind ${draft.entryType} must carry the tag set")
            assertEquals(merchant, draft.merchantId, "kind ${draft.entryType} must carry the merchant")
        }
    }

    @Test
    fun p708b2EntryEditorAssociationWritesReachEveryKindThroughTheReducer() {
        // P1 regression guard (review round): the entry editor's optional association must be
        // SETTABLE from the editing surface for all five kinds — the write reaches the draft, not
        // just the annotation screen. Before the fix no production code emitted these events.
        val drafts =
            listOf<com.unifiedledger.application.TypedEntryDraft>(
                ExpenseDraft(accountId, expenseCategoryId, "1.00", occurredAt),
                IncomeDraft(accountId, expenseCategoryId, "1.00", occurredAt),
                TransferDraft(accountId, null, "1.00", occurredAt = occurredAt),
                com.unifiedledger.application.LendDraft(null, "1.00", accountId, occurredAt),
                com.unifiedledger.application
                    .CollectDraft(null, "1.00", "1.00", "0.00", "0.00", expenseCategoryId, accountId, occurredAt),
            )
        drafts.forEach { draft ->
            val state = editing(draft)
            val tagged = assertIs<P503AppState.Editing>(reducer.reduce(state, P503UiEvent.UpdateTagSelection(setOf(tagA, tagB))))
            assertEquals(setOf(tagA, tagB), tagged.draft.tagIds, "kind ${draft.entryType} must accept the tag write")
            val merched = assertIs<P503AppState.Editing>(reducer.reduce(tagged, P503UiEvent.UpdateMerchant(merchant)))
            assertEquals(merchant, merched.draft.merchantId, "kind ${draft.entryType} must accept the merchant write")
        }
    }

    // ---- record-again clears (spec section 4.2 frozen; P708-A07) ----

    @Test
    fun p708b2RecordAgainRebuildsWithNoAssociation() {
        // The retained intent carries no tag/merchant at all, so any rebuilt draft starts with an
        // empty association regardless of what the previous draft held.
        val intent =
            RetainedEntryIntent(
                type = EntryType.EXPENSE,
                amountText = "35.80",
                paymentAccountId = accountId,
                categoryId = expenseCategoryId,
                note = "lunch",
                occurredAt = occurredAt,
                originTab = P503Tab.HOME,
            )
        val overview = P503AppState.OverviewEmpty(state = currentState, retainedIntent = intent)
        val next = assertIs<P503AppState.Editing>(reducer.reduce(overview, P503UiEvent.SaveAndRecordAgain()))
        assertTrue(next.draft.tagIds.isEmpty(), "record-again must clear tags")
        assertEquals(null, next.draft.merchantId, "record-again must clear the merchant")
    }

    // ---- switch-type retains (spec section 4.2 frozen) ----

    @Test
    fun p708b2SwitchTypeRetainsTheAssociation() {
        val state = editing(ExpenseDraft(accountId, expenseCategoryId, "35.80", occurredAt).withTagIds(setOf(tagA)).withMerchantId(merchant))
        val switched = assertIs<P503AppState.Editing>(reducer.reduce(state, P503UiEvent.SelectEntryType(EntryType.INCOME)))
        assertEquals(setOf(tagA), switched.draft.tagIds, "switch-type must retain tags")
        assertEquals(merchant, switched.draft.merchantId, "switch-type must retain the merchant")
    }

    // ---- annotation-edit surface (spec section 4.4) ----

    private fun annotationState() =
        P503AppState.TransactionAnnotationEdit(
            overview = overviewState,
            intent =
                TransactionAnnotationEditIntent(
                    transactionId = TransactionId("txn-1"),
                    expectedAnnotationRevision = 0L,
                    expectedCurrentVersionId = TransactionVersionId("version-1"),
                    tagIds = setOf(tagA),
                    merchantId = merchant,
                ),
        )

    @Test
    fun p708b2AnnotationSelectionWritesThePendingIntentNotTheLedger() {
        val state = annotationState()
        val tagged = assertIs<P503AppState.TransactionAnnotationEdit>(reducer.reduce(state, P503UiEvent.UpdateAnnotationTagSelection(setOf(tagA, tagB))))
        assertEquals(setOf(tagA, tagB), tagged.intent.tagIds)
        val cleared = assertIs<P503AppState.TransactionAnnotationEdit>(reducer.reduce(tagged, P503UiEvent.UpdateAnnotationMerchant(null)))
        assertEquals(null, cleared.intent.merchantId)
    }

    @Test
    fun p708b2AnnotationConfirmMarksSubmittingAndDoesNotReenter() {
        val state = annotationState()
        val submitting = assertIs<P503AppState.TransactionAnnotationEdit>(reducer.reduce(state, P503UiEvent.ConfirmTransactionAnnotationEdit))
        assertTrue(submitting.submitting)
        // 提交中不重入: a second confirm while in flight changes nothing.
        val again = assertIs<P503AppState.TransactionAnnotationEdit>(reducer.reduce(submitting, P503UiEvent.ConfirmTransactionAnnotationEdit))
        assertTrue(again.submitting)
    }

    @Test
    fun p708b2AnnotationOutcomeIsSurfacedForEveryState() {
        val submitting = annotationState().copy(submitting = true)
        val outcomes =
            listOf(
                TransactionAnnotationEditOutcome.Accepted(2L),
                TransactionAnnotationEditOutcome.NoChange(1L),
                TransactionAnnotationEditOutcome.Rejected("AnnotationTagNotSelectable"),
                TransactionAnnotationEditOutcome.Conflict("AnnotationRevisionConflict"),
            )
        outcomes.forEach { outcome ->
            val landed = assertIs<P503AppState.TransactionAnnotationEdit>(reducer.reduce(submitting, P503UiEvent.TransactionAnnotationEditResult(outcome)))
            assertEquals(outcome, landed.outcome, "the outcome must be surfaced verbatim, never swallowed")
            assertTrue(!landed.submitting, "a landed result clears the submitting marker")
        }
    }

    @Test
    fun p708b2AnnotationEditRejectsExitsWhileSubmitting() {
        // 提交中不得离开: Back/Cancel are absorbed while the commit is in flight; a settled surface
        // leaves to its preserved overview.
        val submitting = annotationState().copy(submitting = true)
        assertEquals(submitting, reducer.reduce(submitting, P503UiEvent.Back))
        val settled = annotationState()
        assertEquals(overviewState, reducer.reduce(settled, P503UiEvent.Back))
    }

    @Test
    fun p708b2AnnotationEditEventsAreAbsorbedOutsideTheirSurface() {
        // The new family must never reach `unhandled` in any other state (the P7-04/P7-05
        // "new events never ISE" discipline). `reduce` throws on `unhandled`, so simply not
        // throwing is the assertion.
        val events =
            listOf(
                P503UiEvent.OpenTransactionAnnotationEdit(
                    TransactionAnnotationEditIntent(TransactionId("txn-1"), 0L, TransactionVersionId("version-1")),
                ),
                P503UiEvent.UpdateAnnotationTagSelection(setOf(tagA)),
                P503UiEvent.UpdateAnnotationMerchant(merchant),
                P503UiEvent.ConfirmTransactionAnnotationEdit,
                P503UiEvent.TransactionAnnotationEditResult(TransactionAnnotationEditOutcome.Accepted(1L)),
            )
        val states =
            listOf<P503AppState>(
                P503AppState.Ready,
                overviewState,
                P503AppState.RequestIdentityConflict(expenseDraft(), requestId, currentState, P503Tab.HOME),
                P503AppState.DomainRejected(expenseDraft(), requestId, currentState, P503Tab.HOME),
            )
        events.forEach { event ->
            states.forEach { state -> reducer.reduce(state, event) }
        }
    }

    @Test
    fun p708b2DraftAssociationWritesAreAbsorbedInTheConflictAndRejectedStates() {
        // Regression guard for the review-round ISE hole: UpdateTagSelection/UpdateMerchant are
        // draft writes the conflict/rejected surfaces must absorb (mirror UpdateNote), never let
        // fall through to `unhandled`.
        val drafts =
            listOf<P503AppState>(
                P503AppState.RequestIdentityConflict(expenseDraft(), requestId, currentState, P503Tab.HOME),
                P503AppState.DomainRejected(expenseDraft(), requestId, currentState, P503Tab.HOME),
            )
        drafts.forEach { state ->
            val tagged = reducer.reduce(state, P503UiEvent.UpdateTagSelection(setOf(tagA)))
            assertEquals(setOf(tagA), assertIs<P503AppState.Editing>(tagged).draft.tagIds)
            val merched = reducer.reduce(state, P503UiEvent.UpdateMerchant(merchant))
            assertEquals(merchant, assertIs<P503AppState.Editing>(merched).draft.merchantId)
        }
    }

    private fun expenseDraft() = ExpenseDraft(accountId, expenseCategoryId, "35.80", occurredAt)

    @Test
    fun p708b2EntryDraftEventsAreAbsorbedInEveryRemainingSurface() {
        // Verifier-round closure: the batch claims its two new draft events "never ISE in any
        // state". Every state that is not an editor surface must absorb them. These are the
        // remaining reachable surfaces after the editor/overview branches.
        val states =
            listOf<P503AppState>(
                P503AppState.TransactionDetail(
                    overview = overviewState,
                    originTab = P503Tab.HOME,
                    transactionId = TransactionId("txn-1"),
                    detail = com.unifiedledger.application.TransactionDetailResult.NotFound,
                ),
                P503AppState.BudgetConfig(
                    overview = overviewState,
                    scope = com.unifiedledger.domain.BudgetScope.Total,
                    month = kotlinx.datetime.YearMonth(2026, 1),
                    revision = 0L,
                    closed = false,
                    limitMinorUnits = null,
                ),
            )
        val events = listOf<P503UiEvent>(P503UiEvent.UpdateTagSelection(setOf(tagA)), P503UiEvent.UpdateMerchant(merchant))
        states.forEach { state ->
            events.forEach { event -> assertEquals(state, reducer.reduce(state, event), "$event must be absorbed in ${state::class.simpleName}") }
        }
    }
}
