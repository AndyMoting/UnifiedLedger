package com.unifiedledger.ui

import com.unifiedledger.application.EntryType
import com.unifiedledger.application.ExpenseDraft
import com.unifiedledger.application.IncomeDraft
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.RequestId
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-09 09.0 inbound-navigation state machine (spec sections 2.2/3/4; D-225 Q19). The pure routing
 * table [decideExternalNavigation] and its reducer application are exercised here without a Compose
 * harness (the `P503HostCoordinatorTest` precedent): every routing-table row is asserted, the
 * single pending slot's retain/replace discipline is pinned, and the "no requestId/snapshot/
 * authorization time is rewritten" guarantee is checked on the live surfaces.
 */
class ExternalNavigationReducerTest {
    private val ledgerId = LedgerId("ledger-local-test")
    private val cny = CurrencyUnit("CNY", 2)
    private val paymentAccountId = AccountId("asset-payment-local")
    private val categoryId = CategoryId("expense-category-breakfast")
    private val occurredAt = Instant.parse("2026-01-15T00:30:00Z")
    private val requestId1 = RequestId("request-uuid-v7-1")
    private val reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)

    private val emptyState = LedgerCurrentState(ledgerId, transactions = emptyList(), balances = emptyList())

    private fun overview() = P503AppState.OverviewEmpty(emptyState)

    private fun expenseDraft() = ExpenseDraft(paymentAccountId, categoryId, "35.80", occurredAt)

    private fun incomeDraft() = IncomeDraft(paymentAccountId, categoryId, "12.00", occurredAt)

    private val newExpense = ExternalNavigationRequest.OpenNewEntry(EntryType.EXPENSE)
    private val shareImport = ExternalNavigationRequest.OpenImport("delivery-1")

    private fun reduce(
        state: P503AppState,
        vararg events: P503UiEvent,
    ): P503AppState = events.fold(state) { current, event -> reducer.reduce(current, event) }

    // ---------------------------------------------------------------- routing table (pure)

    @Test
    fun notReadyDefersUntilReady() {
        val decision = decideExternalNavigation(overview(), newExpense, runtimeReady = false, runtimeQuiesced = false, existingPending = null)
        val deferred = assertIs<ExternalNavigationDecision.DeferredUntilReady>(decision)
        assertEquals(newExpense, deferred.request)
    }

    @Test
    fun anExistingPendingSlotAlwaysWinsOverRouting() {
        val pending = PendingExternalNavigation(shareImport, ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED)
        val decision = decideExternalNavigation(overview(), newExpense, runtimeReady = true, runtimeQuiesced = false, existingPending = pending)
        val retainOrReplace = assertIs<ExternalNavigationDecision.RetainOrReplace>(decision)
        assertEquals(shareImport, retainOrReplace.existing.request)
        assertEquals(newExpense, retainOrReplace.incoming)
    }

    @Test
    fun quiesceHoldsTheRequest() {
        val decision = decideExternalNavigation(overview(), newExpense, runtimeReady = true, runtimeQuiesced = true, existingPending = null)
        val held = assertIs<ExternalNavigationDecision.Held>(decision)
        assertEquals(ExternalNavigationHoldReason.RUNTIME_QUIESCED, held.pending.holdReason)
    }

    @Test
    fun idleRoutesAnImplementedEntryTypeWithoutAFormalWrite() {
        val decision = decideExternalNavigation(overview(), newExpense, runtimeReady = true, runtimeQuiesced = false, existingPending = null)
        assertEquals(newExpense, assertIs<ExternalNavigationDecision.Routed>(decision).request)
    }

    @Test
    fun anUnimplementedEntryTypeIsHeldNotRouted() {
        val lend = ExternalNavigationRequest.OpenNewEntry(EntryType.LEND)
        val decision = decideExternalNavigation(overview(), lend, runtimeReady = true, runtimeQuiesced = false, existingPending = null)
        assertEquals(ExternalNavigationHoldReason.UNROUTABLE_TARGET, assertIs<ExternalNavigationDecision.Held>(decision).pending.holdReason)
    }

    @Test
    fun aShareImportIsAlwaysHeldForTheFormatChoice() {
        val decision = decideExternalNavigation(overview(), shareImport, runtimeReady = true, runtimeQuiesced = false, existingPending = null)
        assertEquals(ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED, assertIs<ExternalNavigationDecision.Held>(decision).pending.holdReason)
    }

    @Test
    fun editingHoldsForTheDraftDecision() {
        val editing = P503AppState.Editing(expenseDraft(), requestId1)
        val decision = decideExternalNavigation(editing, newExpense, runtimeReady = true, runtimeQuiesced = false, existingPending = null)
        assertEquals(ExternalNavigationHoldReason.DRAFT_IN_PROGRESS, assertIs<ExternalNavigationDecision.Held>(decision).pending.holdReason)
    }

    @Test
    fun awaitingConfirmationHoldsForTheConfirmationDecision() {
        val awaiting = P503AppState.AwaitingConfirmation(expenseDraft(), requestId1)
        val decision = decideExternalNavigation(awaiting, newExpense, runtimeReady = true, runtimeQuiesced = false, existingPending = null)
        assertEquals(ExternalNavigationHoldReason.CONFIRMATION_PENDING, assertIs<ExternalNavigationDecision.Held>(decision).pending.holdReason)
    }

    @Test
    fun submittingAndUnknownCommitHoldAsCommitInFlight() {
        val submitting = P503AppState.Submitting(expenseDraft(), requestId1)
        assertEquals(
            ExternalNavigationHoldReason.COMMIT_IN_FLIGHT,
            assertIs<ExternalNavigationDecision.Held>(
                decideExternalNavigation(submitting, newExpense, true, false, null),
            ).pending.holdReason,
        )
        val unknown = P503AppState.UnknownCommit(expenseDraft(), requestId1)
        assertEquals(
            ExternalNavigationHoldReason.COMMIT_IN_FLIGHT,
            assertIs<ExternalNavigationDecision.Held>(
                decideExternalNavigation(unknown, newExpense, true, false, null),
            ).pending.holdReason,
        )
    }

    @Test
    fun importBatchSubmittingHoldsAsBatchInFlight() {
        val batch =
            P503AppState.ImportBatchSubmitting(
                overview = overview(),
                confirmedAt = "2026-01-15T00:30:00Z",
                items = emptyList(),
            )
        assertEquals(
            ExternalNavigationHoldReason.IMPORT_BATCH_IN_FLIGHT,
            assertIs<ExternalNavigationDecision.Held>(
                decideExternalNavigation(batch, newExpense, true, false, null),
            ).pending.holdReason,
        )
    }

    // ---------------------------------------------------------------- reducer application

    @Test
    fun aRoutedNewEntryEntersTheEditorWithZeroFormalWrite() {
        val landed = reduce(overview(), P503UiEvent.ExternalNavigationDecisionLanded(ExternalNavigationDecision.Routed(newExpense)))
        val editing = assertIs<P503AppState.Editing>(landed)
        assertIs<ExpenseDraft>(editing.draft)
        assertNull(editing.requestId)
        // Zero formal effect: the entry is a blank draft, not a committed transaction.
        assertEquals("", editing.draft.amountText)
    }

    @Test
    fun aRoutedIncomeEntryEntersTheIncomeEditor() {
        val landed =
            reduce(
                overview(),
                P503UiEvent.ExternalNavigationDecisionLanded(
                    ExternalNavigationDecision.Routed(ExternalNavigationRequest.OpenNewEntry(EntryType.INCOME)),
                ),
            )
        assertIs<IncomeDraft>(assertIs<P503AppState.Editing>(landed).draft)
    }

    @Test
    fun aHeldDecisionWritesTheVisiblePendingSlot() {
        val held = ExternalNavigationDecision.Held(PendingExternalNavigation(shareImport, ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED))
        val landed = reduce(overview(), P503UiEvent.ExternalNavigationDecisionLanded(held))
        val overview = assertIs<P503AppState.OverviewEmpty>(landed)
        assertEquals(ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED, overview.pendingExternalNavigation?.holdReason)
    }

    @Test
    fun aSecondRequestRetainsTheOriginalAndCarriesTheIncomingOne() {
        val pending = PendingExternalNavigation(shareImport, ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED)
        val existing = overview().copy(pendingExternalNavigation = pending)
        val landed =
            reduce(
                existing,
                P503UiEvent.ExternalNavigationDecisionLanded(ExternalNavigationDecision.RetainOrReplace(pending, newExpense)),
            )
        val slot = assertIs<P503AppState.OverviewEmpty>(landed).pendingExternalNavigation!!
        assertEquals(shareImport, slot.request, "the original request must be retained, never overwritten")
        assertEquals(newExpense, slot.displaced)
        assertTrue(slot.awaitingUserDecision)
    }

    @Test
    fun retainClearsTheDisplacedRequestAndKeepsTheOriginal() {
        val pending =
            PendingExternalNavigation(
                request = shareImport,
                holdReason = ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED,
                awaitingUserDecision = true,
                displaced = newExpense,
            )
        val landed = reduce(overview().copy(pendingExternalNavigation = pending), P503UiEvent.ExternalNavigationRetained)
        val slot = assertIs<P503AppState.OverviewEmpty>(landed).pendingExternalNavigation!!
        assertEquals(shareImport, slot.request)
        assertNull(slot.displaced)
        assertEquals(false, slot.awaitingUserDecision)
    }

    @Test
    fun replaceSwapsInTheIncomingRequestOnlyAfterTheExplicitConfirmation() {
        val pending =
            PendingExternalNavigation(
                request = shareImport,
                holdReason = ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED,
                awaitingUserDecision = true,
                displaced = newExpense,
            )
        val landed = reduce(overview().copy(pendingExternalNavigation = pending), P503UiEvent.ExternalNavigationReplaced)
        val slot = assertIs<P503AppState.OverviewEmpty>(landed).pendingExternalNavigation!!
        assertEquals(newExpense, slot.request)
    }

    @Test
    fun keepCurrentDeclinesTheHeldNavigationWithoutTouchingTheDraft() {
        val editing = P503AppState.Editing(expenseDraft(), requestId1, overview = emptyState).copy(pendingExternalNavigation = PendingExternalNavigation(newExpense, ExternalNavigationHoldReason.DRAFT_IN_PROGRESS))
        val landed = reduce(editing, P503UiEvent.ExternalNavigationKeptCurrent)
        val kept = assertIs<P503AppState.Editing>(landed)
        assertNull(kept.pendingExternalNavigation)
        assertEquals(expenseDraft(), kept.draft, "the original draft must not be overwritten")
        assertEquals(requestId1, kept.requestId)
    }

    @Test
    fun discardAndSwitchDropsTheDraftAndRoutesTheHeldRequest() {
        val editing =
            P503AppState.Editing(
                draft = expenseDraft(),
                requestId = requestId1,
                overview = emptyState,
                originTab = P503Tab.HOME,
            ).copy(pendingExternalNavigation = PendingExternalNavigation(newExpense, ExternalNavigationHoldReason.DRAFT_IN_PROGRESS))
        val landed = reduce(editing, P503UiEvent.ExternalNavigationDiscarded)
        val routed = assertIs<P503AppState.Editing>(landed)
        assertEquals("", routed.draft.amountText, "the held request's fresh draft replaces the discarded one")
        assertNull(routed.requestId)
    }

    @Test
    fun discardAndSwitchOnASubmittingSurfaceKeepsTheRequestHeld() {
        val submitting =
            P503AppState.Submitting(expenseDraft(), requestId1).copy(
                pendingExternalNavigation = PendingExternalNavigation(newExpense, ExternalNavigationHoldReason.COMMIT_IN_FLIGHT),
            )
        val landed = reduce(submitting, P503UiEvent.ExternalNavigationDiscarded)
        val still = assertIs<P503AppState.Submitting>(landed)
        assertEquals(requestId1, still.requestId, "a navigation decision never abandons a pending commit")
        assertEquals(newExpense, still.pendingExternalNavigation?.request)
    }

    // ---------------------------------------------------------------- T4 visible Busy

    @Test
    fun theSecondPickBusyLandsAVisibleStripWithZeroFormalEffect() {
        val landed = reduce(overview(), P503UiEvent.ImportIntakeBusyLanded)
        val overview = assertIs<P503AppState.OverviewEmpty>(landed)
        assertEquals(ExternalNavigationHoldReason.IMPORT_INTAKE_IN_FLIGHT, overview.externalNavigationBusy?.reason)
        // Zero formal effect: no candidate list, no session summary.
        assertNull(overview.importReview)
    }

    @Test
    fun dismissingTheBusyStripClearsIt() {
        val busy = overview().copy(externalNavigationBusy = ExternalNavigationBusy(ExternalNavigationHoldReason.IMPORT_INTAKE_IN_FLIGHT))
        assertNull(assertIs<P503AppState.OverviewEmpty>(reduce(busy, P503UiEvent.DismissExternalNavigationBusy)).externalNavigationBusy)
    }

    @Test
    fun theBusyCopyLeaksNoAmountOrPayload() {
        val copy = externalNavigationBusyText(ExternalNavigationBusy(ExternalNavigationHoldReason.IMPORT_INTAKE_IN_FLIGHT))
        assertTrue(copy.isNotBlank())
        // No digit/currency symbol in the static copy (硬禁明文金额 discipline, spec section 6.5).
        assertTrue(copy.none { it.isDigit() }, "the Busy copy must contain no amount literal: $copy")
    }

    // ---------------------------------------------------------------- absorption discipline

    @Test
    fun theInboundEventFamilyIsAbsorbedInEveryOtherState() {
        // The family never throws in any state (the "new events never ISE" discipline).
        val states =
            listOf(
                P503AppState.Ready,
                P503AppState.Created,
                P503AppState.TransactionDetail(overview(), P503Tab.HOME, TransactionId("tx-1"), com.unifiedledger.application.TransactionDetailResult.Unavailable),
                P503AppState.TagMerchantFilter(overview(), kotlinx.datetime.YearMonth(2026, 1)),
                P503AppState.RestoreSessionTerminal(BackupRestoreRecoveryCause.RollbackReopenFailed),
            )
        for (state in states) {
            assertSame(state, reduce(state, P503UiEvent.ExternalNavigationRequested(newExpense)))
            assertSame(state, reduce(state, P503UiEvent.ExternalNavigationRetained))
            assertSame(state, reduce(state, P503UiEvent.ExternalNavigationReplaced))
            assertSame(state, reduce(state, P503UiEvent.ExternalNavigationKeptCurrent))
            assertSame(state, reduce(state, P503UiEvent.ExternalNavigationDiscarded))
            assertSame(state, reduce(state, P503UiEvent.ImportIntakeBusyLanded))
            assertSame(state, reduce(state, P503UiEvent.DismissExternalNavigationBusy))
        }
    }

    @Test
    fun aDecisionLandingOnANonRoutingSurfaceCannotCreateAHiddenQueue() {
        val detail =
            P503AppState.TransactionDetail(overview(), P503Tab.HOME, TransactionId("tx-1"), com.unifiedledger.application.TransactionDetailResult.Unavailable)
        val landed =
            reduce(
                detail,
                P503UiEvent.ExternalNavigationDecisionLanded(
                    ExternalNavigationDecision.Held(PendingExternalNavigation(shareImport, ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED)),
                ),
            )
        assertSame(detail, landed)
    }

    @Test
    fun aRequestedEventIsStatePreserving() {
        val state = overview()
        assertSame(state, reduce(state, P503UiEvent.ExternalNavigationRequested(newExpense)))
    }

    // ---------------------------------------------------------------- host call-site seams

    @Test
    fun theAlreadyInFlightPickSeamYieldsTheVisibleBusyEventNeverUnit() {
        // T4: the seam that replaced the silent `-> Unit` drop MUST yield the visible Busy event.
        val busyEvent =
            importPickDecisionEvent(
                ImportPickIntakeDecision.AlreadyInFlight(ExternalNavigationBusy(ExternalNavigationHoldReason.IMPORT_INTAKE_IN_FLIGHT)),
            )
        assertEquals(P503UiEvent.ImportIntakeBusyLanded, busyEvent)
        // A started intake and a resolved non-pick produce no event of their own.
        assertNull(importPickDecisionEvent(ImportPickIntakeDecision.StartIntake(PickedImportFile("f.csv", null) { BoundedFileRead.Bytes(ByteArray(0)) })))
        assertNull(importPickDecisionEvent(ImportPickIntakeDecision.NoPipeline))
    }

    @Test
    fun theChannelBuffersOneColdStartRequestAndDrainsItOnSubscribe() {
        val channel = ExternalNavigationChannel()
        // Delivered BEFORE any subscriber (a cold-start share): buffered, not lost.
        channel.deliver(shareImport)
        val received = mutableListOf<ExternalNavigationRequest>()
        channel.subscribe { received += it }
        assertEquals(listOf<ExternalNavigationRequest>(shareImport), received)
        // The buffer drains exactly once.
        channel.subscribe(null as ((ExternalNavigationRequest) -> Unit)?)
        channel.subscribe { received += it }
        assertEquals(1, received.size)
    }

    @Test
    fun theChannelDeliversDirectlyOnceSubscribedAndBuffersDepthOne() {
        val channel = ExternalNavigationChannel()
        val received = mutableListOf<ExternalNavigationRequest>()
        channel.subscribe { received += it }
        channel.deliver(shareImport)
        channel.deliver(newExpense)
        assertEquals(listOf<ExternalNavigationRequest>(shareImport, newExpense), received)
        // Depth one: a second pre-subscription request replaces the first (no unbounded queue).
        channel.subscribe(null as ((ExternalNavigationRequest) -> Unit)?)
        channel.deliver(shareImport)
        channel.deliver(newExpense)
        val drained = mutableListOf<ExternalNavigationRequest>()
        channel.subscribe { drained += it }
        assertEquals(listOf<ExternalNavigationRequest>(newExpense), drained)
    }

    @Test
    fun theInboundRequestSeamRoutesThroughTheFrozenTable() {
        // The call-site seam wires the pure table: an idle overview routes, a share is held.
        val routedEvent = assertIs<P503UiEvent.ExternalNavigationDecisionLanded>(externalNavigationDecisionEvent(overview(), newExpense, runtimeQuiesced = false))
        assertIs<ExternalNavigationDecision.Routed>(routedEvent.decision)

        val heldEvent = assertIs<P503UiEvent.ExternalNavigationDecisionLanded>(externalNavigationDecisionEvent(overview(), shareImport, runtimeQuiesced = false))
        assertEquals(ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED, assertIs<ExternalNavigationDecision.Held>(heldEvent.decision).pending.holdReason)

        // End to end through the reducer: the seam's event lands the visible slot.
        val landed = reduce(overview(), externalNavigationDecisionEvent(overview(), shareImport, runtimeQuiesced = false))
        assertEquals(
            ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED,
            assertIs<P503AppState.OverviewEmpty>(landed).pendingExternalNavigation?.holdReason,
        )
    }

    @Test
    fun routingKeepsTheOverviewMonthCursorAndPinSet() {
        val month = kotlinx.datetime.YearMonth(2026, 3)
        val withCursor = overview().copy(selectedMonth = month)
        val landed = reduce(withCursor, P503UiEvent.ExternalNavigationDecisionLanded(ExternalNavigationDecision.Routed(newExpense)))
        val editing = assertIs<P503AppState.Editing>(landed)
        assertEquals(month, editing.selectedMonth)
        assertEquals(emptyState, editing.overview)
    }

    @Test
    fun openCurrentMonthRoutesToThePreservedOverviewWithTheSlotCleared() {
        val pending = PendingExternalNavigation(ExternalNavigationRequest.OpenCurrentMonth, ExternalNavigationHoldReason.UNROUTABLE_TARGET)
        val landed = reduce(overview().copy(pendingExternalNavigation = pending), P503UiEvent.ExternalNavigationDiscarded)
        val routed = assertIs<P503AppState.OverviewEmpty>(landed)
        assertNull(routed.pendingExternalNavigation)
    }

}
