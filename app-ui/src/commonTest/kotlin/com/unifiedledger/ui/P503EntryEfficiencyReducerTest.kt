package com.unifiedledger.ui

import com.unifiedledger.application.CollectDraft
import com.unifiedledger.application.EntryExpressionCode
import com.unifiedledger.application.EntryExpressionEvaluator
import com.unifiedledger.application.EntryPinTarget
import com.unifiedledger.application.EntryType
import com.unifiedledger.application.ExpenseDraft
import com.unifiedledger.application.IncomeDraft
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.LendDraft
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.ParseManualExpenseOccurredAt
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.TransferDraft
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-02.D entry-efficiency reducer contracts (E-2/E-3/E-4, section 6.1/6.2a/6.2b and B06):
 * the expression preview lives on `Editing` only, `ApplyExpressionResult` rewrites the amount
 * only from a legal (valid) preview, `TogglePin` is an ordering-only overview effect, and
 * "record again" rebuilds a fresh per-type draft from the retained intent revalidated against
 * the current authoritative catalog.
 */
class P503EntryEfficiencyReducerTest {
    private val cny = CurrencyUnit("CNY", 2)
    private val ledgerId = LedgerId("ledger-local-test")
    private val occurredAt = Instant.parse("2026-01-15T00:30:00Z")
    private val nowInstant = Instant.parse("2026-09-12T00:00:00Z")
    private val requestId = RequestId("request-efficiency-1")
    private val emptyState = LedgerCurrentStateForTests.empty(ledgerId)
    private val ledgerClock = LedgerClock { nowInstant }
    private val reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny, ledgerClock, EntryExpressionEvaluator())

    private val accountId = AccountId("asset-a")
    private val otherAccountId = AccountId("asset-missing-from-catalog")
    private val expenseCategoryId = CategoryId("expense-cat")
    private val incomeCategoryId = CategoryId("income-cat")
    private val feeCategoryId = CategoryId("fee-cat")

    /** The host snapshots this from the authoritative options at re-record time. */
    private val revalidation =
        RetainedIntentRevalidation(
            accountIds = setOf(accountId),
            expenseCategoryIds = setOf(expenseCategoryId),
            incomeCategoryIds = setOf(incomeCategoryId),
        )

    private val nonEditingStates =
        listOf<P503AppState>(
            P503AppState.Ready,
            P503AppState.OverviewEmpty(emptyState),
            P503AppState.AwaitingConfirmation(expenseDraft(), requestId),
            P503AppState.Submitting(expenseDraft(), requestId),
            P503AppState.Created,
            P503AppState.NoChange,
            P503AppState.Recovered,
            P503AppState.RequestIdentityConflict(expenseDraft(), requestId, emptyState, P503Tab.HOME),
            P503AppState.DomainRejected(expenseDraft(), requestId, emptyState, P503Tab.HOME),
            P503AppState.InfrastructureFailure(InfrastructureFailureContext.SUBMISSION, expenseDraft(), requestId),
            P503AppState.InfrastructureFailure(InfrastructureFailureContext.READ),
            P503AppState.UnknownCommit(expenseDraft(), requestId),
        )

    private fun expenseDraft() = ExpenseDraft(accountId, expenseCategoryId, "35.80", occurredAt, note = "lunch")

    // ---- E-3 expression preview (section 6.1/6.2a) ----

    @Test
    fun evaluateEntryExpressionWritesTheExactPreviewInEditing() {
        val editing = P503AppState.Editing(ExpenseDraft(accountId, expenseCategoryId, "", null), requestId)
        val evaluated = assertIs<P503AppState.Editing>(reducer.reduce(editing, P503UiEvent.EvaluateEntryExpression("12.5+8")))
        assertEquals(ExpressionPreview.Valid(2050L, "20.50"), evaluated.expressionPreview)

        val parenthesized =
            assertIs<P503AppState.Editing>(reducer.reduce(editing, P503UiEvent.EvaluateEntryExpression("2×(3+4)")))
        assertEquals(ExpressionPreview.Valid(1400L, "14.00"), parenthesized.expressionPreview)

        val exactDivision =
            assertIs<P503AppState.Editing>(reducer.reduce(editing, P503UiEvent.EvaluateEntryExpression("10/4")))
        assertEquals(ExpressionPreview.Valid(250L, "2.50"), exactDivision.expressionPreview)
    }

    @Test
    fun evaluateEntryExpressionWritesTypedRejectionPreviews() {
        val editing = P503AppState.Editing(ExpenseDraft(accountId, expenseCategoryId, "", null), requestId)
        val nonTerminating = assertIs<P503AppState.Editing>(reducer.reduce(editing, P503UiEvent.EvaluateEntryExpression("1/3")))
        assertEquals(ExpressionPreview.Invalid(EntryExpressionCode.NonCurrencyPrecision), nonTerminating.expressionPreview)

        val negative = assertIs<P503AppState.Editing>(reducer.reduce(editing, P503UiEvent.EvaluateEntryExpression("1-2")))
        assertEquals(ExpressionPreview.Invalid(EntryExpressionCode.NegativeResult), negative.expressionPreview)

        val illegal = assertIs<P503AppState.Editing>(reducer.reduce(editing, P503UiEvent.EvaluateEntryExpression("-1+2")))
        assertEquals(ExpressionPreview.Invalid(EntryExpressionCode.Invalid), illegal.expressionPreview)

        val divideByZero = assertIs<P503AppState.Editing>(reducer.reduce(editing, P503UiEvent.EvaluateEntryExpression("5/0")))
        assertEquals(ExpressionPreview.Invalid(EntryExpressionCode.DivideByZero), divideByZero.expressionPreview)

        val overflow = assertIs<P503AppState.Editing>(reducer.reduce(editing, P503UiEvent.EvaluateEntryExpression("10000000000*10000000000")))
        assertEquals(ExpressionPreview.Invalid(EntryExpressionCode.Overflow), overflow.expressionPreview)
    }

    @Test
    fun applyExpressionResultRewritesTheMainAmountOnlyFromAValidPreview() {
        // Each draft type: the applied text lands on the per-type main amount field.
        val editing = P503AppState.Editing(ExpenseDraft(accountId, expenseCategoryId, "1", occurredAt), requestId)
        val evaluated = assertIs<P503AppState.Editing>(reducer.reduce(editing, P503UiEvent.EvaluateEntryExpression("12.5+8")))
        val applied = assertIs<P503AppState.Editing>(reducer.reduce(evaluated, P503UiEvent.ApplyExpressionResult))
        assertEquals("20.50", assertIs<ExpenseDraft>(applied.draft).amountText)
        assertNull(applied.expressionPreview)

        val lendEditing =
            assertIs<P503AppState.Editing>(
                reducer.reduce(
                    P503AppState.Editing(LendDraft(null, "1", accountId, occurredAt), requestId),
                    P503UiEvent.EvaluateEntryExpression("2×(3+4)"),
                ),
            )
        assertEquals("14.00", assertIs<LendDraft>(assertIs<P503AppState.Editing>(reducer.reduce(lendEditing, P503UiEvent.ApplyExpressionResult)).draft).amount)

        val transferEditing =
            assertIs<P503AppState.Editing>(
                reducer.reduce(
                    P503AppState.Editing(TransferDraft(accountId, null, "1", occurredAt = occurredAt), requestId),
                    P503UiEvent.EvaluateEntryExpression("10/4"),
                ),
            )
        assertEquals("2.50", assertIs<TransferDraft>(assertIs<P503AppState.Editing>(reducer.reduce(transferEditing, P503UiEvent.ApplyExpressionResult)).draft).destinationCredit)

        val collectEditing =
            assertIs<P503AppState.Editing>(
                reducer.reduce(
                    P503AppState.Editing(CollectDraft(null, "1", "", "", destinationAccountId = accountId, occurredAt = occurredAt), requestId),
                    P503UiEvent.EvaluateEntryExpression("10/4"),
                ),
            )
        assertEquals("2.50", assertIs<CollectDraft>(assertIs<P503AppState.Editing>(reducer.reduce(collectEditing, P503UiEvent.ApplyExpressionResult)).draft).totalReceived)
    }

    @Test
    fun applyExpressionResultWithoutAValidPreviewLeavesTheStateUntouched() {
        val editing = P503AppState.Editing(expenseDraft(), requestId)
        // No preview at all: absorbed in place.
        assertEquals(editing, reducer.reduce(editing, P503UiEvent.ApplyExpressionResult))
        // An invalid preview is not a legal amount source; it is kept for the explanatory UI.
        val invalid =
            assertIs<P503AppState.Editing>(reducer.reduce(editing, P503UiEvent.EvaluateEntryExpression("1/3")))
        assertEquals(invalid, reducer.reduce(invalid, P503UiEvent.ApplyExpressionResult))
    }

    @Test
    fun expressionEventsAreAbsorbedInEveryNonEditingState() {
        for (state in nonEditingStates) {
            for (event in listOf<P503UiEvent>(P503UiEvent.EvaluateEntryExpression("1+1"), P503UiEvent.ApplyExpressionResult)) {
                assertEquals(state, reducer.reduce(state, event), "absorbed $event in $state")
            }
        }
    }

    // ---- E-4 manual pinning (section 6.1/6.2a) ----

    @Test
    fun togglePinFlipsTheOverviewMembershipOnly() {
        val target = EntryPinTarget.AccountTarget(ledgerId, accountId)
        val categoryTarget = EntryPinTarget.CategoryTarget(ledgerId, expenseCategoryId)
        val overview = P503AppState.OverviewEmpty(emptyState)

        val pinned = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(overview, P503UiEvent.TogglePin(target)))
        assertEquals(setOf<EntryPinTarget>(target), pinned.pinnedTargets)
        val alsoCategory = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(pinned, P503UiEvent.TogglePin(categoryTarget)))
        assertEquals(setOf<EntryPinTarget>(target, categoryTarget), alsoCategory.pinnedTargets)
        val unpinned = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(alsoCategory, P503UiEvent.TogglePin(target)))
        assertEquals(setOf<EntryPinTarget>(categoryTarget), unpinned.pinnedTargets)
    }

    @Test
    fun togglePinIsAbsorbedInEveryNonOverviewState() {
        val target = EntryPinTarget.AccountTarget(ledgerId, accountId)
        // TogglePin's only effect state is OverviewEmpty; everywhere else it is absorbed.
        for (state in nonEditingStates.filterNot { it is P503AppState.OverviewEmpty }) {
            assertEquals(state, reducer.reduce(state, P503UiEvent.TogglePin(target)), "absorbed TogglePin in $state")
        }
    }

    @Test
    fun pinsSurviveRefreshesAndAreSeededFromTheInitialLoad() {
        val pins = setOf<EntryPinTarget>(EntryPinTarget.AccountTarget(ledgerId, accountId))
        // An ordinary overview refresh keeps the current pin set.
        val overview = P503AppState.OverviewEmpty(emptyState, P503Tab.HOME, pinnedTargets = pins)
        val refreshed = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(overview, P503UiEvent.RefreshResult(emptyState)))
        assertEquals(pins, refreshed.pinnedTargets)

        // The determinate-success refresh carries the host mirror into the fresh overview.
        val afterSuccess =
            assertIs<P503AppState.OverviewEmpty>(reducer.reduce(P503AppState.Created, P503UiEvent.RefreshResult(emptyState, pinnedTargets = pins)))
        assertEquals(pins, afterSuccess.pinnedTargets)

        // A READ-failure retry success carries them too.
        val afterReadRetry =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(P503AppState.InfrastructureFailure(InfrastructureFailureContext.READ), P503UiEvent.RefreshResult(emptyState, pinnedTargets = pins)),
            )
        assertEquals(pins, afterReadRetry.pinnedTargets)

        // Startup seeds the persisted pins.
        val afterLoad = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(P503AppState.Ready, P503UiEvent.InitialLoadResult(emptyState, pins)))
        assertEquals(pins, afterLoad.pinnedTargets)
    }

    // ---- E-2 record again (per-type rebuild + catalog revalidation, B06) ----

    @Test
    fun recordAgainBuildsThePerTypeDraftFromTheRetainedIntent() {
        val overviewState = P503AppState.OverviewEmpty(emptyState, P503Tab.ACCOUNTS)

        val expenseEditing =
            assertIs<P503AppState.Editing>(
                reducer.reduce(
                    overviewState.copy(retainedIntent = RetainedEntryIntent(EntryType.EXPENSE, "35.80", accountId, expenseCategoryId, "lunch", occurredAt, P503Tab.ACCOUNTS)),
                    P503UiEvent.SaveAndRecordAgain(revalidation),
                ),
            )
        val expense = assertIs<ExpenseDraft>(expenseEditing.draft)
        assertEquals("", expense.amountText)
        assertEquals("", expense.note)
        assertEquals(nowInstant, expense.occurredAt)
        assertEquals(accountId, expense.paymentAccountId)
        assertEquals(expenseCategoryId, expense.categoryId)
        assertNull(expenseEditing.requestId)
        assertEquals(P503Tab.ACCOUNTS, expenseEditing.originTab)

        val incomeEditing =
            assertIs<P503AppState.Editing>(
                reducer.reduce(
                    overviewState.copy(retainedIntent = RetainedEntryIntent(EntryType.INCOME, "300.00", accountId, incomeCategoryId, "salary", occurredAt, P503Tab.HOME)),
                    P503UiEvent.SaveAndRecordAgain(revalidation),
                ),
            )
        val income = assertIs<IncomeDraft>(incomeEditing.draft)
        assertEquals(accountId, income.receivingAccountId)
        assertEquals(incomeCategoryId, income.categoryId)
        assertEquals("", income.amountText)
        assertEquals("", income.note)
        assertEquals(nowInstant, income.occurredAt)

        val transferEditing =
            assertIs<P503AppState.Editing>(
                reducer.reduce(
                    overviewState.copy(retainedIntent = RetainedEntryIntent(EntryType.TRANSFER, "50.00", accountId, feeCategoryId, "move", occurredAt, P503Tab.HOME)),
                    P503UiEvent.SaveAndRecordAgain(revalidation),
                ),
            )
        val transfer = assertIs<TransferDraft>(transferEditing.draft)
        // The source account belongs to the shared asset-account class; the destination, the fee
        // (reset to 0.00) and the fee category never carry over (E-1/T-4).
        assertEquals(accountId, transfer.sourceAccountId)
        assertNull(transfer.destinationAccountId)
        assertEquals("", transfer.destinationCredit)
        assertEquals("0.00", transfer.fee)
        assertNull(transfer.feeCategoryId)
        assertEquals(nowInstant, transfer.occurredAt)
        assertEquals("", transfer.note)

        val lendEditing =
            assertIs<P503AppState.Editing>(
                reducer.reduce(
                    overviewState.copy(retainedIntent = RetainedEntryIntent(EntryType.LEND, "100.00", accountId, null, "lend", occurredAt, P503Tab.HOME)),
                    P503UiEvent.SaveAndRecordAgain(revalidation),
                ),
            )
        val lend = assertIs<LendDraft>(lendEditing.draft)
        assertEquals(accountId, lend.fundingAccountId)
        assertNull(lend.counterpartyId)
        assertEquals("", lend.amount)
        assertEquals(nowInstant, lend.occurredAt)
        assertEquals("", lend.note)

        val collectEditing =
            assertIs<P503AppState.Editing>(
                reducer.reduce(
                    overviewState.copy(retainedIntent = RetainedEntryIntent(EntryType.COLLECT, "45.00", accountId, incomeCategoryId, "collect", occurredAt, P503Tab.HOME)),
                    P503UiEvent.SaveAndRecordAgain(revalidation),
                ),
            )
        val collect = assertIs<CollectDraft>(collectEditing.draft)
        assertEquals(accountId, collect.destinationAccountId)
        assertEquals(incomeCategoryId, collect.interestCategoryId)
        assertNull(collect.counterpartyId)
        assertEquals("", collect.totalReceived)
        assertEquals("", collect.principal)
        assertEquals("", collect.interest)
        assertEquals(nowInstant, collect.occurredAt)
    }

    @Test
    fun recordAgainDropsObjectsTheCurrentCatalogNoLongerOffers() {
        val overviewState = P503AppState.OverviewEmpty(emptyState)
        val staleAccountIntent =
            RetainedEntryIntent(EntryType.EXPENSE, "35.80", otherAccountId, CategoryId("category-removed"), "lunch", occurredAt, P503Tab.HOME)
        val editing =
            assertIs<P503AppState.Editing>(reducer.reduce(overviewState.copy(retainedIntent = staleAccountIntent), P503UiEvent.SaveAndRecordAgain(revalidation)))
        val draft = assertIs<ExpenseDraft>(editing.draft)
        assertNull(draft.paymentAccountId)
        assertNull(draft.categoryId)
        assertEquals("", draft.amountText)
        assertEquals("", draft.note)
        assertEquals(nowInstant, draft.occurredAt)
    }

    @Test
    fun recordAgainWithoutARevalidationPayloadKeepsTheFrozenBatchABehavior() {
        // Legacy/defensive: no catalog view supplied, the intent fields carry as frozen before D.
        val overviewState = P503AppState.OverviewEmpty(emptyState)
        val intent = RetainedEntryIntent(EntryType.EXPENSE, "35.80", otherAccountId, expenseCategoryId, "lunch", occurredAt, P503Tab.HOME)
        val editing = assertIs<P503AppState.Editing>(reducer.reduce(overviewState.copy(retainedIntent = intent), P503UiEvent.SaveAndRecordAgain()))
        val draft = assertIs<ExpenseDraft>(editing.draft)
        assertEquals(otherAccountId, draft.paymentAccountId)
        assertEquals(expenseCategoryId, draft.categoryId)
    }

    @Test
    fun b06RecordAgainIsAvailableAfterEachDeterminateSuccess() {
        val intent = RetainedEntryIntent(EntryType.EXPENSE, "35.80", accountId, expenseCategoryId, "lunch", occurredAt, P503Tab.HOME)
        for (state in listOf<P503AppState>(P503AppState.Created, P503AppState.NoChange, P503AppState.Recovered)) {
            val overview = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(state, P503UiEvent.RefreshResult(emptyState, intent)))
            assertEquals(intent, overview.retainedIntent)
            val editing = assertIs<P503AppState.Editing>(reducer.reduce(overview, P503UiEvent.SaveAndRecordAgain(revalidation)))
            val draft = assertIs<ExpenseDraft>(editing.draft)
            // Old amount/note/confirmation cleared, occurredAt is the current instant, and a new
            // requestId is allocated by the host on the next Continue (never the old one).
            assertEquals("", draft.amountText)
            assertEquals("", draft.note)
            assertEquals(nowInstant, draft.occurredAt)
            assertNull(editing.requestId)
        }
    }

    @Test
    fun b06RecordAgainTruncatesToWholeSecondsSoContinueIsReachable() {
        // P702SPEC-02: the clock carries a fractional second; the frozen D-138 parser rejects
        // fractional ISO text, so the re-record instant is truncated to whole seconds and the
        // Continue gate stays reachable.
        val fractionalClock = LedgerClock { Instant.parse("2026-09-12T00:00:00.750Z") }
        val fractionalReducer = P503ReducerImpl(ParseManualExpenseAmount(), cny, fractionalClock, EntryExpressionEvaluator())
        val validation = P503DraftValidation(ParseManualExpenseAmount(), ParseManualExpenseOccurredAt(), fractionalClock)
        val intent = RetainedEntryIntent(EntryType.EXPENSE, "35.80", accountId, expenseCategoryId, "lunch", occurredAt, P503Tab.HOME)
        val overview = P503AppState.OverviewEmpty(emptyState, P503Tab.HOME, retainedIntent = intent)
        val editing = assertIs<P503AppState.Editing>(fractionalReducer.reduce(overview, P503UiEvent.SaveAndRecordAgain(revalidation)))
        val draft = assertIs<ExpenseDraft>(editing.draft)
        val truncated = Instant.parse("2026-09-12T00:00:00Z")
        assertEquals(truncated, draft.occurredAt)
        // Gate level: the displayed ISO text re-parses to exactly the draft instant ...
        val displayed = draft.occurredAt?.toString() ?: ""
        assertTrue(validation.occurredAtTextReconciles(displayed, draft.occurredAt))
        // ... and with the amount typed the full Continue gate passes.
        val filled = assertIs<P503AppState.Editing>(fractionalReducer.reduce(editing, P503UiEvent.UpdateAmount("20.50")))
        assertTrue(validation.isValid(filled.draft, cny))
    }

    @Test
    fun saveAndRecordAgainWithoutARetainedIntentIsAbsorbed() {
        val overview = P503AppState.OverviewEmpty(emptyState)
        assertEquals(overview, reducer.reduce(overview, P503UiEvent.SaveAndRecordAgain(revalidation)))
    }
}
