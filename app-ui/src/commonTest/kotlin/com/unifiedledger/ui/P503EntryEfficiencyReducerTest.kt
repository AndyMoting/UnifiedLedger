package com.unifiedledger.ui

import com.unifiedledger.application.CatalogSnapshotView
import com.unifiedledger.application.CollectDraft
import com.unifiedledger.application.EntryExpressionCode
import com.unifiedledger.application.EntryExpressionEvaluator
import com.unifiedledger.application.EntryPinTarget
import com.unifiedledger.application.EntryType
import com.unifiedledger.application.ExpenseDraft
import com.unifiedledger.application.IncomeDraft
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.LendDraft
import com.unifiedledger.application.ManualExpenseCommitResolution
import com.unifiedledger.application.ManualExpenseSaveResult
import com.unifiedledger.application.ManualExpenseSubmissionResult
import com.unifiedledger.application.MonthlyActivity
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.ParseManualExpenseOccurredAt
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.TransferDraft
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant
import com.unifiedledger.application.MonthlyActivityResult as MonthlyActivityQueryResult

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

    // ---- E-4 manual pinning (section 6.1/6.2a; A-02 FIX-PIN-1..4) ----

    @Test
    fun togglePinSetsTheAuthoritativeMembershipInsteadOfFlipping() {
        val target = EntryPinTarget.AccountTarget(ledgerId, accountId)
        val categoryTarget = EntryPinTarget.CategoryTarget(ledgerId, expenseCategoryId)
        val overview = P503AppState.OverviewEmpty(emptyState)

        val pinned = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(overview, P503UiEvent.TogglePin(target, pinned = true)))
        assertEquals(setOf<EntryPinTarget>(target), pinned.pinnedTargets)
        val alsoCategory = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(pinned, P503UiEvent.TogglePin(categoryTarget, pinned = true)))
        assertEquals(setOf<EntryPinTarget>(target, categoryTarget), alsoCategory.pinnedTargets)
        val unpinned = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(alsoCategory, P503UiEvent.TogglePin(target, pinned = false)))
        assertEquals(setOf<EntryPinTarget>(categoryTarget), unpinned.pinnedTargets)

        // A02PIN-002: setting the desired value is idempotent in both directions. A render copy
        // that had diverged (the target absent while the store still holds the pin) must not
        // invert the persisted state, and re-setting an already-present target changes nothing.
        val unsetWhenAbsent = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(overview, P503UiEvent.TogglePin(target, pinned = false)))
        assertTrue(unsetWhenAbsent.pinnedTargets.isEmpty())
        val stillPinned = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(pinned, P503UiEvent.TogglePin(target, pinned = true)))
        assertEquals(setOf<EntryPinTarget>(target), stillPinned.pinnedTargets)
    }

    @Test
    fun togglePinInstallsTheHostReDerivedSnapshotWithoutTouchingTheNoticeOrDialog() {
        val target = EntryPinTarget.AccountTarget(ledgerId, accountId)
        val current = CatalogSnapshotView(6, emptyList(), emptyList())
        val reDerived = CatalogSnapshotView(7, emptyList(), emptyList())
        val notice = CatalogNotice("已保存", error = false)
        val dialog = CatalogDialog.RenameAccount(accountId, "旧名")
        val overview =
            P503AppState.OverviewEmpty(
                emptyState,
                catalogSnapshot = current,
                catalogDialog = dialog,
                catalogNotice = notice,
            )

        val toggled =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(overview, P503UiEvent.TogglePin(target, pinned = true, catalogSnapshot = reDerived)),
            )
        assertEquals(reDerived, toggled.catalogSnapshot)
        // A02PIN-001: the re-derivation must not clear the management banner or the open form.
        assertEquals(notice, toggled.catalogNotice)
        assertEquals(dialog, toggled.catalogDialog)

        // A payload-less toggle keeps the current projection (defensive call sites).
        val kept = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(overview, P503UiEvent.TogglePin(target, pinned = true)))
        assertEquals(current, kept.catalogSnapshot)
    }

    @Test
    fun togglePinIsAbsorbedInEveryNonOverviewState() {
        val target = EntryPinTarget.AccountTarget(ledgerId, accountId)
        // TogglePin's only effect state is OverviewEmpty; everywhere else it is absorbed.
        for (state in nonEditingStates.filterNot { it is P503AppState.OverviewEmpty }) {
            assertEquals(state, reducer.reduce(state, P503UiEvent.TogglePin(target, pinned = true)), "absorbed TogglePin in $state")
        }
    }

    @Test
    fun selectTabKeepsThePinSet() {
        val pins = setOf<EntryPinTarget>(EntryPinTarget.AccountTarget(ledgerId, accountId))
        val overview = P503AppState.OverviewEmpty(emptyState, P503Tab.HOME, pinnedTargets = pins)
        val accounts = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(overview, P503UiEvent.SelectTab(P503Tab.ACCOUNTS)))
        assertEquals(pins, accounts.pinnedTargets)
    }

    @Test
    fun backFromEveryEditorFlowBranchKeepsThePinSet() {
        val pins = setOf<EntryPinTarget>(EntryPinTarget.AccountTarget(ledgerId, accountId))
        val draft = expenseDraft()
        val branches =
            listOf<P503AppState>(
                P503AppState.Editing(draft, requestId, emptyState, P503Tab.ACCOUNTS, pinnedTargets = pins),
                P503AppState.AwaitingConfirmation(draft, requestId, emptyState, P503Tab.ACCOUNTS, pinnedTargets = pins),
                P503AppState.RequestIdentityConflict(draft, requestId, emptyState, P503Tab.ACCOUNTS, pinnedTargets = pins),
                P503AppState.DomainRejected(draft, requestId, emptyState, P503Tab.ACCOUNTS, pinnedTargets = pins),
                P503AppState.InfrastructureFailure(InfrastructureFailureContext.SUBMISSION, draft, requestId, emptyState, P503Tab.ACCOUNTS, pinnedTargets = pins),
            )
        for (branch in branches) {
            val closed = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(branch, P503UiEvent.Back))
            assertEquals(pins, closed.pinnedTargets, "Back dropped the pin set of $branch")
            assertEquals(emptyState, closed.state)
            assertEquals(P503Tab.ACCOUNTS, closed.selectedTab)
        }
    }

    @Test
    fun pinsSurviveTheEditorFlowBackToTheManagementList() {
        // A02PIN-002 device vector: 置顶 → 关闭录入页 → the management list keeps its pin marks.
        val pins = setOf<EntryPinTarget>(EntryPinTarget.CategoryTarget(ledgerId, expenseCategoryId))
        val overview = P503AppState.OverviewEmpty(emptyState, P503Tab.ACCOUNTS, pinnedTargets = pins)

        val editing = assertIs<P503AppState.Editing>(reducer.reduce(overview, P503UiEvent.StartNewExpense))
        assertEquals(pins, editing.pinnedTargets)
        assertEquals(pins, assertIs<P503AppState.OverviewEmpty>(reducer.reduce(editing, P503UiEvent.Back)).pinnedTargets)

        val filled =
            reduceAll(
                editing,
                P503UiEvent.UpdatePaymentAccount(accountId),
                P503UiEvent.UpdateCategory(expenseCategoryId),
                P503UiEvent.UpdateAmount("35.80"),
                P503UiEvent.UpdateOccurredAt(occurredAt),
            )
        val awaiting = assertIs<P503AppState.AwaitingConfirmation>(reducer.reduce(filled, P503UiEvent.Continue(requestId)))
        assertEquals(pins, awaiting.pinnedTargets)
        assertEquals(pins, assertIs<P503AppState.OverviewEmpty>(reducer.reduce(awaiting, P503UiEvent.Back)).pinnedTargets)

        val cancelled = assertIs<P503AppState.Editing>(reducer.reduce(awaiting, P503UiEvent.Cancel))
        assertEquals(pins, cancelled.pinnedTargets)
        assertEquals(pins, assertIs<P503AppState.OverviewEmpty>(reducer.reduce(cancelled, P503UiEvent.Back)).pinnedTargets)
    }

    @Test
    fun pinsSurviveEverySubmissionFailureBranchAndItsBack() {
        val pins = setOf<EntryPinTarget>(EntryPinTarget.AccountTarget(ledgerId, accountId))
        val submitting = P503AppState.Submitting(expenseDraft(), requestId, emptyState, P503Tab.ACCOUNTS, pinnedTargets = pins)

        val failed =
            assertIs<P503AppState.InfrastructureFailure>(
                reducer.reduce(submitting, P503UiEvent.SubmissionResult(ManualExpenseSubmissionResult.InfrastructureFailure)),
            )
        assertEquals(pins, failed.pinnedTargets)
        assertEquals(pins, assertIs<P503AppState.OverviewEmpty>(reducer.reduce(failed, P503UiEvent.Back)).pinnedTargets)
        assertEquals(pins, assertIs<P503AppState.Submitting>(reducer.reduce(failed, P503UiEvent.RetrySubmission)).pinnedTargets)
        assertEquals(pins, assertIs<P503AppState.Editing>(reducer.reduce(failed, P503UiEvent.Cancel)).pinnedTargets)

        val unknown =
            assertIs<P503AppState.UnknownCommit>(
                reducer.reduce(submitting, P503UiEvent.SubmissionResult(ManualExpenseSubmissionResult.UnknownCommit)),
            )
        assertEquals(pins, unknown.pinnedTargets)
        val conflicted =
            assertIs<P503AppState.RequestIdentityConflict>(
                reducer.reduce(unknown, P503UiEvent.CommitStatusResolved(ManualExpenseCommitResolution.SnapshotConflict)),
            )
        assertEquals(pins, conflicted.pinnedTargets)
        assertEquals(pins, assertIs<P503AppState.OverviewEmpty>(reducer.reduce(conflicted, P503UiEvent.Back)).pinnedTargets)
    }

    @Test
    fun fieldEditsAndAbandonOnTheFailureScreensKeepThePinSet() {
        val pins = setOf<EntryPinTarget>(EntryPinTarget.AccountTarget(ledgerId, accountId))
        val conflict = P503AppState.RequestIdentityConflict(expenseDraft(), requestId, emptyState, P503Tab.ACCOUNTS, pinnedTargets = pins)
        val edited = assertIs<P503AppState.Editing>(reducer.reduce(conflict, P503UiEvent.UpdateAmount("12.00")))
        assertEquals(pins, edited.pinnedTargets)
        assertEquals(pins, assertIs<P503AppState.OverviewEmpty>(reducer.reduce(edited, P503UiEvent.Back)).pinnedTargets)
        assertEquals(pins, assertIs<P503AppState.Editing>(reducer.reduce(conflict, P503UiEvent.AbandonConflict)).pinnedTargets)

        val rejected = P503AppState.DomainRejected(expenseDraft(), requestId, emptyState, P503Tab.ACCOUNTS, pinnedTargets = pins)
        val rejectedEdit = assertIs<P503AppState.Editing>(reducer.reduce(rejected, P503UiEvent.UpdateNote("retry")))
        assertEquals(pins, rejectedEdit.pinnedTargets)
        assertEquals(pins, assertIs<P503AppState.OverviewEmpty>(reducer.reduce(rejectedEdit, P503UiEvent.Back)).pinnedTargets)
    }

    @Test
    fun pinsSurviveRefreshesAndAreSeededFromTheInitialLoad() {
        val pins = setOf<EntryPinTarget>(EntryPinTarget.AccountTarget(ledgerId, accountId))
        val otherPins = setOf<EntryPinTarget>(EntryPinTarget.CategoryTarget(ledgerId, expenseCategoryId))
        // An ordinary overview refresh without a payload keeps the current pin set.
        val overview = P503AppState.OverviewEmpty(emptyState, P503Tab.HOME, pinnedTargets = pins)
        val refreshed = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(overview, P503UiEvent.RefreshResult(emptyState)))
        assertEquals(pins, refreshed.pinnedTargets)

        // A-02 FIX-PIN-4: an ordinary refresh carrying the host mirror adopts it, so a diverged
        // render copy self-heals instead of keeping its stale set.
        val healed = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(overview, P503UiEvent.RefreshResult(emptyState, pinnedTargets = otherPins)))
        assertEquals(otherPins, healed.pinnedTargets)

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

        // A payload-less retry falls back to the retained monthly overview's pin set.
        val retained = P503AppState.InfrastructureFailure(InfrastructureFailureContext.READ, monthlyOverview = P503AppState.OverviewEmpty(emptyState, P503Tab.HOME, pinnedTargets = pins))
        val afterRetryWithoutPayload = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(retained, P503UiEvent.RefreshResult(emptyState)))
        assertEquals(pins, afterRetryWithoutPayload.pinnedTargets)

        // Startup seeds the persisted pins.
        val afterLoad = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(P503AppState.Ready, P503UiEvent.InitialLoadResult(emptyState, pins)))
        assertEquals(pins, afterLoad.pinnedTargets)
    }

    // ---- A-02 FIX-MONTH-2 monthly snapshot carry (D-152; spec 2.2) ----

    private val march = YearMonth(2026, 3)
    private val selectableDomain = listOf(YearMonth(2026, 2), YearMonth(2026, 3))

    /** Minimal unified payload whose identity is asserted through the carry/restore vectors. */
    private fun monthlyActivity(month: YearMonth) =
        MonthlyActivity(
            ledgerId = ledgerId,
            month = month,
            currencies = emptyList(),
            expenseCategories = emptyList(),
            incomeCategories = emptyList(),
        )

    @Test
    fun startNewExpenseCarriesTheMonthlySnapshotAndBackRestoresIt() {
        val payload = monthlyActivity(march)
        val overview =
            P503AppState.OverviewEmpty(
                emptyState,
                P503Tab.ACCOUNTS,
                selectedMonth = march,
                selectableMonths = selectableDomain,
                monthlyActivity = payload,
            )

        val editing = assertIs<P503AppState.Editing>(reducer.reduce(overview, P503UiEvent.StartNewExpense))
        assertEquals(march, editing.selectedMonth)
        assertEquals(selectableDomain, editing.selectableMonths)
        assertSame(payload, editing.monthlyActivity)

        // A02MONTH-001 device vector: 打开并关闭录入页 → the month card payload and the month
        // cursor are back on the overview without a re-request (monthlyReloadRequired stays off).
        val closed = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(editing, P503UiEvent.Back))
        assertEquals(march, closed.selectedMonth)
        assertEquals(selectableDomain, closed.selectableMonths)
        assertSame(payload, closed.monthlyActivity)
        assertFalse(closed.monthlyReloadRequired)
    }

    @Test
    fun recordAgainCarriesTheMonthlySnapshotToo() {
        val payload = monthlyActivity(march)
        val overview =
            P503AppState.OverviewEmpty(
                emptyState,
                P503Tab.HOME,
                selectedMonth = march,
                selectableMonths = selectableDomain,
                monthlyActivity = payload,
                retainedIntent = RetainedEntryIntent(EntryType.EXPENSE, "35.80", accountId, expenseCategoryId, "lunch", occurredAt, P503Tab.HOME),
            )
        val editing = assertIs<P503AppState.Editing>(reducer.reduce(overview, P503UiEvent.SaveAndRecordAgain(revalidation)))
        assertEquals(march, editing.selectedMonth)
        assertEquals(selectableDomain, editing.selectableMonths)
        assertSame(payload, editing.monthlyActivity)
    }

    @Test
    fun monthlySnapshotSurvivesEveryEditorFlowBranchAndItsBack() {
        val payload = monthlyActivity(march)
        val draft = expenseDraft()
        // Submitting has no Back; every other flow branch closes to the originating overview.
        val branches =
            listOf<P503AppState>(
                P503AppState.Editing(draft, requestId, emptyState, P503Tab.ACCOUNTS, selectedMonth = march, selectableMonths = selectableDomain, monthlyActivity = payload),
                P503AppState.AwaitingConfirmation(draft, requestId, emptyState, P503Tab.ACCOUNTS, selectedMonth = march, selectableMonths = selectableDomain, monthlyActivity = payload),
                P503AppState.RequestIdentityConflict(draft, requestId, emptyState, P503Tab.ACCOUNTS, selectedMonth = march, selectableMonths = selectableDomain, monthlyActivity = payload),
                P503AppState.DomainRejected(draft, requestId, emptyState, P503Tab.ACCOUNTS, selectedMonth = march, selectableMonths = selectableDomain, monthlyActivity = payload),
                P503AppState.InfrastructureFailure(
                    InfrastructureFailureContext.SUBMISSION,
                    draft,
                    requestId,
                    emptyState,
                    P503Tab.ACCOUNTS,
                    selectedMonth = march,
                    selectableMonths = selectableDomain,
                    monthlyActivity = payload,
                ),
            )
        for (branch in branches) {
            val closed = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(branch, P503UiEvent.Back))
            assertEquals(march, closed.selectedMonth, "Back dropped the month cursor of $branch")
            assertEquals(selectableDomain, closed.selectableMonths, "Back dropped the SelectMonth domain of $branch")
            assertSame(payload, closed.monthlyActivity, "Back dropped the monthly payload of $branch")
            assertFalse(closed.monthlyReloadRequired, "Back left a reload flag on $branch")
        }
    }

    @Test
    fun monthlySnapshotSurvivesTheFullSubmissionFlowAndUnknownCommitResolutions() {
        val payload = monthlyActivity(march)
        val overview =
            P503AppState.OverviewEmpty(
                emptyState,
                P503Tab.HOME,
                selectedMonth = march,
                selectableMonths = selectableDomain,
                monthlyActivity = payload,
            )

        val editing = assertIs<P503AppState.Editing>(reducer.reduce(overview, P503UiEvent.StartNewExpense))
        // Fill the draft so the Continue gate passes (the empty StartNewExpense draft does not).
        val filled =
            reduceAll(
                editing,
                P503UiEvent.UpdatePaymentAccount(accountId),
                P503UiEvent.UpdateCategory(expenseCategoryId),
                P503UiEvent.UpdateAmount("35.80"),
                P503UiEvent.UpdateOccurredAt(occurredAt),
            )
        val awaiting = assertIs<P503AppState.AwaitingConfirmation>(reducer.reduce(filled, P503UiEvent.Continue(requestId)))
        assertEquals(march, awaiting.selectedMonth)
        val submitting = assertIs<P503AppState.Submitting>(reducer.reduce(awaiting, P503UiEvent.Confirm))
        assertEquals(march, submitting.selectedMonth)

        // The infrastructure-failure branch: Cancel returns to Editing, Back restores the payload.
        val failed =
            assertIs<P503AppState.InfrastructureFailure>(
                reducer.reduce(submitting, P503UiEvent.SubmissionResult(ManualExpenseSubmissionResult.InfrastructureFailure)),
            )
        assertEquals(march, failed.selectedMonth)
        assertEquals(payload, assertIs<P503AppState.Editing>(reducer.reduce(failed, P503UiEvent.Cancel)).monthlyActivity)
        assertSame(payload, assertIs<P503AppState.OverviewEmpty>(reducer.reduce(failed, P503UiEvent.Back)).monthlyActivity)

        // The unknown-commit branch: the conflict resolution and a later abandon keep the snapshot.
        val unknown =
            assertIs<P503AppState.UnknownCommit>(
                reducer.reduce(submitting, P503UiEvent.SubmissionResult(ManualExpenseSubmissionResult.UnknownCommit)),
            )
        assertEquals(march, unknown.selectedMonth)
        val conflicted =
            assertIs<P503AppState.RequestIdentityConflict>(
                reducer.reduce(unknown, P503UiEvent.CommitStatusResolved(ManualExpenseCommitResolution.SnapshotConflict)),
            )
        assertEquals(march, conflicted.selectedMonth)
        assertEquals(selectableDomain, assertIs<P503AppState.Editing>(reducer.reduce(conflicted, P503UiEvent.AbandonConflict)).selectableMonths)

        // The invalid-input return to Editing carries the snapshot as well.
        val invalid =
            assertIs<P503AppState.Editing>(
                reducer.reduce(submitting, P503UiEvent.SubmissionResult(ManualExpenseSubmissionResult.Application(ManualExpenseSaveResult.InvalidInput(emptySet())))),
            )
        assertSame(payload, invalid.monthlyActivity)
    }

    @Test
    fun monthlySnapshotWithoutPayloadRestoresEmptyWithTheCursorIntact() {
        // The pre-editor overview had no payload (a failed monthly read with the reload affordance
        // up): Back restores the cursor/domain it had, no payload, and the explicit reload flag is
        // NOT set — the AWAITING surface matches the pre-fix behavior with no regression.
        val overview =
            P503AppState.OverviewEmpty(
                emptyState,
                P503Tab.HOME,
                selectedMonth = march,
                selectableMonths = selectableDomain,
                monthlyReloadRequired = true,
            )
        val editing = assertIs<P503AppState.Editing>(reducer.reduce(overview, P503UiEvent.StartNewExpense))
        assertNull(editing.monthlyActivity)
        val closed = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(editing, P503UiEvent.Back))
        assertEquals(march, closed.selectedMonth)
        assertEquals(selectableDomain, closed.selectableMonths)
        assertNull(closed.monthlyActivity)
        assertFalse(closed.monthlyReloadRequired)
    }

    @Test
    fun monthlyActivityResultStaysAbsorbedInsideTheEditorFlow() {
        // Spec 6.2 absorption table unchanged (D-152): an editor-state MonthlyActivityResult is
        // still absorbed in place — it never updates the carried snapshot.
        val editing = P503AppState.Editing(expenseDraft(), requestId, emptyState, P503Tab.HOME)
        assertSame(editing, reducer.reduce(editing, P503UiEvent.MonthlyActivityResult(MonthlyActivityQueryResult.Unavailable, emptyList())))
    }

    private fun reduceAll(
        from: P503AppState,
        vararg events: P503UiEvent,
    ): P503AppState = events.fold(from) { state, event -> reducer.reduce(state, event) }

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
