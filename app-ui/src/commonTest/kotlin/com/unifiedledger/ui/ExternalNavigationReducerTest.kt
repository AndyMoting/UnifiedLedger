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

    // P7-09 09.B (D-226; spec section 2.1 `OpenImportTab`): the fourth neutral request — open the
    // EXISTING IMPORT surface, carrying no file and no deliveryId.
    private val importTab = ExternalNavigationRequest.OpenImportTab

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
            P503AppState
                .Editing(
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
                P503AppState.Ready(),
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
            // P2-2: the Busy events are absorbed (state-preserving) on a NON-slot surface; on a slot
            // surface they write the field, which the dedicated P2-2 tests below assert.
            if (state !is P503AppState.Ready) {
                assertSame(state, reduce(state, P503UiEvent.ImportIntakeBusyLanded))
                assertSame(state, reduce(state, P503UiEvent.DismissExternalNavigationBusy))
            }
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
        val ready = ExternalNavigationRuntimeFacts(ready = true, quiesced = false)
        // The call-site seam wires the pure table: an idle overview routes, a share is held.
        val routedEvent = assertIs<P503UiEvent.ExternalNavigationDecisionLanded>(externalNavigationDecisionEvent(overview(), newExpense, ready))
        assertIs<ExternalNavigationDecision.Routed>(routedEvent.decision)

        val heldEvent = assertIs<P503UiEvent.ExternalNavigationDecisionLanded>(externalNavigationDecisionEvent(overview(), shareImport, ready))
        assertEquals(ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED, assertIs<ExternalNavigationDecision.Held>(heldEvent.decision).pending.holdReason)

        // End to end through the reducer: the seam's event lands the visible slot.
        val landed = reduce(overview(), externalNavigationDecisionEvent(overview(), shareImport, ready))
        assertEquals(
            ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED,
            assertIs<P503AppState.OverviewEmpty>(landed).pendingExternalNavigation?.holdReason,
        )
    }

    // ---------------------------------------------------------------- P2-1 runtime facts seam

    @Test
    fun theRuntimeFactsMappingObservesTheLeaseFaceAndTheSwitchingFlag() {
        // P2-1: the exact mapping the P503App call site uses (ExternalNavigationRuntimeFacts.from):
        // a null active generation means not Ready; a live generation means Ready; the host's own
        // switching flag is the quiesce observation.
        val readyIdle = ExternalNavigationRuntimeFacts.from(activeGeneration = 3, switchingRuntime = false)
        assertEquals(true, readyIdle.ready)
        assertEquals(false, readyIdle.quiesced)
        val notReady = ExternalNavigationRuntimeFacts.from(activeGeneration = null, switchingRuntime = false)
        assertEquals(false, notReady.ready)
        val readySwitching = ExternalNavigationRuntimeFacts.from(activeGeneration = 3, switchingRuntime = true)
        assertEquals(true, readySwitching.ready)
        assertEquals(true, readySwitching.quiesced)
    }

    @Test
    fun theRuntimeFactsSeamDefersWhenTheLeaseFaceHasNoActiveGeneration() {
        // P2-1: the not-Ready row must be production-reachable. The facts are derived from the
        // lease face (a null active generation == not Ready), and the seam then DEFERS — never the
        // former hard-coded `runtimeReady = true` that made this row dead code.
        val notReady = ExternalNavigationRuntimeFacts.from(activeGeneration = null, switchingRuntime = false)
        assertEquals(false, notReady.ready)
        val event = assertIs<P503UiEvent.ExternalNavigationDecisionLanded>(externalNavigationDecisionEvent(overview(), newExpense, notReady))
        assertEquals(newExpense, assertIs<ExternalNavigationDecision.DeferredUntilReady>(event.decision).request)
        // End to end: the deferred request still lands a VISIBLE held slot (never dropped).
        val landed = assertIs<P503AppState.OverviewEmpty>(reduce(overview(), event))
        assertEquals(ExternalNavigationHoldReason.STARTUP_NOT_READY, landed.pendingExternalNavigation?.holdReason)
    }

    @Test
    fun theRuntimeFactsSeamHoldsWhenTheLeaseFaceReportsAQuiesce() {
        // P2-1: the quiesce / 换库 row must be production-reachable via the observed facts.
        val quiesced = ExternalNavigationRuntimeFacts.from(activeGeneration = 7, switchingRuntime = true)
        assertEquals(true, quiesced.ready)
        assertEquals(true, quiesced.quiesced)
        val event = assertIs<P503UiEvent.ExternalNavigationDecisionLanded>(externalNavigationDecisionEvent(overview(), newExpense, quiesced))
        assertEquals(ExternalNavigationHoldReason.RUNTIME_QUIESCED, assertIs<ExternalNavigationDecision.Held>(event.decision).pending.holdReason)
    }

    // ---------------------------------------------------------------- P1-A Ready entry surface

    @Test
    fun aColdStartShareOnTheReadyEntrySurfaceLandsAVisibleSlotNotASilentDrop() {
        // P1-A regression: a cold-start share drains from the channel while the host is still on
        // the Ready ENTRY surface. The routing table must HOLD it into the Ready slot (visible Busy)
        // rather than absorb it into `else ->` (the pre-fix silent drop).
        val event =
            externalNavigationDecisionEvent(
                P503AppState.Ready(),
                shareImport,
                ExternalNavigationRuntimeFacts(ready = true, quiesced = false),
            )
        val held = assertIs<ExternalNavigationDecision.Held>(assertIs<P503UiEvent.ExternalNavigationDecisionLanded>(event).decision)
        assertEquals(ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED, held.pending.holdReason)
        // The slot lands on the Ready surface and is visible.
        val landed = assertIs<P503AppState.Ready>(reduce(P503AppState.Ready(), event))
        assertEquals(ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED, landed.pendingExternalNavigation?.holdReason)
        assertEquals(ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED, landed.pendingExternalNavigationSlot()?.holdReason)
    }

    @Test
    fun aColdStartNewEntryOnReadyIsHeldVisiblyAndCarriedOntoTheOverview() {
        // A cold-start 记支出 on the Ready entry surface: no overview exists yet, so it is held (not
        // routed into a dead editor), and when the initial load lands the slot is CARRIED forward.
        val ready = P503AppState.Ready()
        val event = externalNavigationDecisionEvent(ready, newExpense, ExternalNavigationRuntimeFacts(ready = true, quiesced = false))
        assertEquals(ExternalNavigationHoldReason.STARTUP_NOT_READY, assertIs<ExternalNavigationDecision.Held>(assertIs<P503UiEvent.ExternalNavigationDecisionLanded>(event).decision).pending.holdReason)
        val held = assertIs<P503AppState.Ready>(reduce(ready, event))
        assertEquals(newExpense, held.pendingExternalNavigation?.request)
        // The initial authoritative load advances the surface and CARRIES the held slot (P1-A).
        val overview = assertIs<P503AppState.OverviewEmpty>(reduce(held, P503UiEvent.InitialLoadResult(emptyState)))
        assertEquals(newExpense, overview.pendingExternalNavigation?.request)
    }

    @Test
    fun aDecisionLandingOnReadyWithoutASlotValueStillLandsTheSlot() {
        // The absolute slot write point must cover Ready (P1-A): a Held decision lands even though
        // Ready started with a null slot.
        val landed =
            assertIs<P503AppState.Ready>(
                reduce(
                    P503AppState.Ready(),
                    P503UiEvent.ExternalNavigationDecisionLanded(
                        ExternalNavigationDecision.Held(PendingExternalNavigation(shareImport, ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED)),
                    ),
                ),
            )
        assertEquals(shareImport, landed.pendingExternalNavigation?.request)
    }

    // ---------------------------------------------------------------- P2-2 Busy on every surface

    @Test
    fun theSecondPickBusyIsVisibleOnEverySlotSurfaceNotOnlyTheOverview() {
        // P2-2 regression: the T4 Busy must be visible on Editing / AwaitingConfirmation /
        // Submitting / ImportBatchSubmitting / Ready too — before the fix it was only written (and
        // rendered) on OverviewEmpty, so a second pick there was the silent drop this batch removes.
        val editing = P503AppState.Editing(expenseDraft(), requestId1)
        val awaiting = P503AppState.AwaitingConfirmation(expenseDraft(), requestId1)
        val submitting = P503AppState.Submitting(expenseDraft(), requestId1)
        val batch = P503AppState.ImportBatchSubmitting(overview(), "2026-01-15T00:30:00Z", emptyList())
        val states: List<P503AppState> = listOf(P503AppState.Ready(), overview(), editing, awaiting, submitting, batch)
        for (state in states) {
            val landed = reduce(state, P503UiEvent.ImportIntakeBusyLanded)
            assertEquals(
                ExternalNavigationHoldReason.IMPORT_INTAKE_IN_FLIGHT,
                landed.externalNavigationBusyOrNull()?.reason,
                "the Busy must be visible on ${state::class.simpleName}",
            )
            // Dismiss clears it on the same surface.
            assertNull(reduce(landed, P503UiEvent.DismissExternalNavigationBusy).externalNavigationBusyOrNull())
        }
    }

    @Test
    fun theBusyAccessorIsNullOnANonSlotSurface() {
        val detail =
            P503AppState.TransactionDetail(overview(), P503Tab.HOME, TransactionId("tx-1"), com.unifiedledger.application.TransactionDetailResult.Unavailable)
        assertNull(detail.externalNavigationBusyOrNull())
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

    // ---------------------------------------------------------------- P7-09 09.B OpenImportTab (D-226)

    @Test
    fun anIdleOpenImportTabRoutesToTheExistingImportSurfaceWithZeroFormalWrite() {
        // D-226: 打开既有 IMPORT 面 is the `SelectTab(P503Tab.IMPORT)` navigation semantic with the
        // slot consumed. Zero formal-ledger write: the overview's read state is untouched.
        val decision = decideExternalNavigation(overview(), importTab, runtimeReady = true, runtimeQuiesced = false, existingPending = null)
        assertEquals(importTab, assertIs<ExternalNavigationDecision.Routed>(decision).request)

        val landed = reduce(overview(), P503UiEvent.ExternalNavigationDecisionLanded(ExternalNavigationDecision.Routed(importTab)))
        val routed = assertIs<P503AppState.OverviewEmpty>(landed)
        assertEquals(P503Tab.IMPORT, routed.selectedTab)
        assertNull(routed.pendingExternalNavigation, "the routed request is consumed, not left held")
        // The projection is NOT fabricated by the pure reducer (the host's state effect loads it).
        assertNull(routed.importReview)
    }

    @Test
    fun anOpenImportTabOnTheReadyEntrySurfaceIsHeldNotRoutedAndCarriedOntoTheOverview() {
        // A cold-start 导入 shortcut: no overview exists yet, so it is held with the visible slot
        // (never routed into a dead surface) and CARRIED once the initial load lands.
        val ready = P503AppState.Ready()
        val event = externalNavigationDecisionEvent(ready, importTab, ExternalNavigationRuntimeFacts(ready = true, quiesced = false))
        assertEquals(
            ExternalNavigationHoldReason.STARTUP_NOT_READY,
            assertIs<ExternalNavigationDecision.Held>(assertIs<P503UiEvent.ExternalNavigationDecisionLanded>(event).decision).pending.holdReason,
        )
        val held = assertIs<P503AppState.Ready>(reduce(ready, event))
        assertEquals(importTab, held.pendingExternalNavigation?.request)
        val carried = assertIs<P503AppState.OverviewEmpty>(reduce(held, P503UiEvent.InitialLoadResult(emptyState)))
        assertEquals(importTab, carried.pendingExternalNavigation?.request)
    }

    @Test
    fun anOpenImportTabDoesNotBypassTheConfirmationChain() {
        // The frozen table applies to the fourth request exactly as to the others: an editor with an
        // unsaved draft holds it for the user's decision (不绕过确认/不丢草稿).
        val editing = P503AppState.Editing(expenseDraft(), requestId1)
        assertEquals(
            ExternalNavigationHoldReason.DRAFT_IN_PROGRESS,
            assertIs<ExternalNavigationDecision.Held>(
                decideExternalNavigation(editing, importTab, true, false, null),
            ).pending.holdReason,
        )
        assertEquals(
            ExternalNavigationHoldReason.CONFIRMATION_PENDING,
            assertIs<ExternalNavigationDecision.Held>(
                decideExternalNavigation(P503AppState.AwaitingConfirmation(expenseDraft(), requestId1), importTab, true, false, null),
            ).pending.holdReason,
        )
    }

    // ------------------------------------------------ P7-09 09.B / T11 startup re-entry (D-226; spec section 3 line 150)

    private fun startupHeld(request: ExternalNavigationRequest) = PendingExternalNavigation(request, ExternalNavigationHoldReason.STARTUP_NOT_READY)

    @Test
    fun aCarriedStartupHeldNewEntryReEntersTheGateAndRoutesIntoTheEditor() {
        // T11 leg 1: a cold-start 记支出 carried onto the overview re-runs the SAME gate and ROUTES
        // (it no longer stays on the Busy strip until the user presses 放弃后切换).
        val carried = overview().copy(pendingExternalNavigation = startupHeld(newExpense))
        val decision = startupHeldReRoutingDecision(carried, ExternalNavigationRuntimeFacts(ready = true, quiesced = false), carried.pendingExternalNavigation)
        assertIs<ExternalNavigationDecision.Routed>(decision)
        val landed = reduce(carried, P503UiEvent.ExternalNavigationDecisionLanded(decision!!))
        val editing = assertIs<P503AppState.Editing>(landed)
        assertEquals("", editing.draft.amountText, "a re-route is a navigation, never a formal write")
        assertNull(editing.requestId)
    }

    @Test
    fun aCarriedStartupHeldImportTabReEntersTheGateAndOpensImport() {
        // T11 leg 2: the cold-start 导入 shortcut ends on the SAME surface the hot path reaches.
        val carried = overview().copy(pendingExternalNavigation = startupHeld(importTab))
        val decision = startupHeldReRoutingDecision(carried, ExternalNavigationRuntimeFacts(ready = true, quiesced = false), carried.pendingExternalNavigation)
        assertIs<ExternalNavigationDecision.Routed>(decision)
        val landed = reduce(carried, P503UiEvent.ExternalNavigationDecisionLanded(decision!!))
        assertEquals(P503Tab.IMPORT, assertIs<P503AppState.OverviewEmpty>(landed).selectedTab)
    }

    @Test
    fun aShareAwaitingItsFormatIsNeverReRouted() {
        // T11 leg 3 (negative): the 09.A closed loop must not be disturbed. Its slot reason is
        // IMPORT_FORMAT_REQUIRED, so the guard rejects it before the gate is ever consulted.
        val heldShare = PendingExternalNavigation(shareImport, ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED)
        val carried = overview().copy(pendingExternalNavigation = heldShare)
        assertNull(startupHeldReRoutingDecision(carried, ExternalNavigationRuntimeFacts(ready = true, quiesced = false), carried.pendingExternalNavigation))
    }

    @Test
    fun aSlotAwaitingTheRetainOrReplaceDecisionIsNeverReRoutedAndStillRetains() {
        // T11 leg 4 (the B1′ pin): a slot the user has not yet adjudicated carries
        // awaitingUserDecision/displaced. A re-route must NEVER decide it for them — otherwise the
        // un-adjudicated incoming request would be routed straight into the editor through the
        // OverviewEmpty row and the displaced request would be silently dropped (spec section 2.2).
        val pending =
            PendingExternalNavigation(
                request = newExpense,
                holdReason = ExternalNavigationHoldReason.STARTUP_NOT_READY,
                awaitingUserDecision = true,
                displaced = importTab,
            )
        val carried = overview().copy(pendingExternalNavigation = pending)
        assertNull(
            startupHeldReRoutingDecision(carried, ExternalNavigationRuntimeFacts(ready = true, quiesced = false), carried.pendingExternalNavigation),
            "a slot awaiting the retain/replace decision must not be re-routed",
        )
        // The explicit retain/replace semantics remain reachable and unchanged.
        val retained = assertIs<P503AppState.OverviewEmpty>(reduce(carried, P503UiEvent.ExternalNavigationRetained)).pendingExternalNavigation!!
        assertEquals(newExpense, retained.request)
        assertNull(retained.displaced)
        val replaced = assertIs<P503AppState.OverviewEmpty>(reduce(carried, P503UiEvent.ExternalNavigationReplaced)).pendingExternalNavigation!!
        assertEquals(importTab, replaced.request)
    }

    @Test
    fun aStartupHeldRequestWhileEditingStaysHeldWithTheCorrectedReasonNotRouted() {
        // T11 leg 5: a cold-start request whose runtime was not Ready while the user was Editing must
        // NOT be routed (不丢草稿); the re-entry only CORRECTS the reason to DRAFT_IN_PROGRESS.
        val editing = P503AppState.Editing(expenseDraft(), requestId1).copy(pendingExternalNavigation = startupHeld(newExpense))
        val decision = startupHeldReRoutingDecision(editing, ExternalNavigationRuntimeFacts(ready = true, quiesced = false), editing.pendingExternalNavigation)
        assertEquals(ExternalNavigationHoldReason.DRAFT_IN_PROGRESS, assertIs<ExternalNavigationDecision.Held>(decision).pending.holdReason)
        val landed = assertIs<P503AppState.Editing>(reduce(editing, P503UiEvent.ExternalNavigationDecisionLanded(decision!!)))
        assertEquals(expenseDraft(), landed.draft, "the draft must survive the re-entry")
        assertEquals(requestId1, landed.requestId)
    }

    @Test
    fun aStartupHeldSlotOnAStillNotReadyRuntimeIsNotReRoutedAndDoesNotChurn() {
        // T11 leg 6 (idempotence): while the runtime is still not Ready the gate concludes the SAME
        // STARTUP_NOT_READY hold, so the seam returns null — nothing changes and repeated dispatches
        // cannot churn the same value.
        val carried = overview().copy(pendingExternalNavigation = startupHeld(newExpense))
        assertNull(startupHeldReRoutingDecision(carried, ExternalNavigationRuntimeFacts(ready = false, quiesced = false), carried.pendingExternalNavigation))
        // And nothing to re-route at all when the surface carries no slot.
        assertNull(startupHeldReRoutingDecision(overview(), ExternalNavigationRuntimeFacts(ready = true, quiesced = false), null))
    }

    @Test
    fun aStartupHeldSlotOnAQuiescedRuntimeIsReHeldWithTheRuntimeQuiescedReason() {
        // T11: the quiesce row still applies on re-entry — the slot's reason is corrected (a genuine
        // change, so a decision is returned) and the request stays held, never routed.
        val carried = overview().copy(pendingExternalNavigation = startupHeld(newExpense))
        val decision = startupHeldReRoutingDecision(carried, ExternalNavigationRuntimeFacts(ready = true, quiesced = true), carried.pendingExternalNavigation)
        assertEquals(ExternalNavigationHoldReason.RUNTIME_QUIESCED, assertIs<ExternalNavigationDecision.Held>(decision).pending.holdReason)
    }

    @Test
    fun theReRoutingSeamIsSelfTerminatingAfterOneLanding() {
        // T11 self-termination: once the re-route has landed, the landing itself changes the slot, so
        // the guard is false on the next pass (no loop, no repeated landing).
        val carried = overview().copy(pendingExternalNavigation = startupHeld(newExpense))
        val decision = startupHeldReRoutingDecision(carried, ExternalNavigationRuntimeFacts(ready = true, quiesced = false), carried.pendingExternalNavigation)!!
        val landed = reduce(carried, P503UiEvent.ExternalNavigationDecisionLanded(decision))
        assertNull(startupHeldReRoutingDecision(landed, ExternalNavigationRuntimeFacts(ready = true, quiesced = false), landed.pendingExternalNavigationSlot()))
        // The Editing landing (leg 5) is equally terminal: its reason is no longer STARTUP_NOT_READY.
        val editing = P503AppState.Editing(expenseDraft(), requestId1).copy(pendingExternalNavigation = startupHeld(newExpense))
        val held = reduce(editing, P503UiEvent.ExternalNavigationDecisionLanded(startupHeldReRoutingDecision(editing, ExternalNavigationRuntimeFacts(ready = true, quiesced = false), editing.pendingExternalNavigation)!!))
        assertNull(startupHeldReRoutingDecision(held, ExternalNavigationRuntimeFacts(ready = true, quiesced = false), held.pendingExternalNavigationSlot()))
    }
}
