package com.unifiedledger.ui

import com.unifiedledger.application.ImportFileIntakeOutcome
import com.unifiedledger.application.ImportFormatAvailability
import com.unifiedledger.application.ImportIntakeRecordDisposition
import com.unifiedledger.application.ImportIntakeRecordSummary
import com.unifiedledger.application.ImportPlatformKind
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * P7-09 09.A share-import CLOSED LOOP (spec sections 2.2/2.3/4; D-225; the batch that closes the
 * "held share has no consumer" gap). These are the pure reducer/host seams of the loop:
 * format-from-the-matrix → take the retained source → the EXISTING intake pipeline, the typed
 * re-share case on a missing or unreadable source, and the too-large prompt on a readable-but-
 * oversize source — with ZERO formal effect and the pending slot always cleared (never a silent
 * drop).
 */
class ShareImportClosedLoopTest {
    private val ledgerId = LedgerId("ledger-local-test")
    private val cny = CurrencyUnit("CNY", 2)
    private val reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)

    private val emptyState = LedgerCurrentState(ledgerId, transactions = emptyList(), balances = emptyList())

    private fun overview() = P503AppState.OverviewEmpty(emptyState)

    private val shareImport = ExternalNavigationRequest.OpenImport("delivery-1")

    private fun heldShare() =
        overview().copy(
            pendingExternalNavigation = PendingExternalNavigation(shareImport, ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED),
        )

    private fun file(name: String = "shared-statement.csv") = PickedImportFile(name, null) { BoundedFileRead.Bytes(ByteArray(0)) }

    private fun reduce(
        state: P503AppState,
        vararg events: P503UiEvent,
    ): P503AppState = events.fold(state) { current, event -> reducer.reduce(current, event) }

    // ---------------------------------------------------------------- held share awaits a format

    @Test
    fun onlyAHeldShareAwaitingItsFormatIsAClosedLoopTarget() {
        assertTrue(heldShare().pendingExternalNavigation.awaitsShareImportFormat())
        // A non-share held request is untouched by the closed loop.
        assertFalse(
            overview()
                .copy(pendingExternalNavigation = PendingExternalNavigation(ExternalNavigationRequest.OpenCurrentMonth, ExternalNavigationHoldReason.UNROUTABLE_TARGET))
                .pendingExternalNavigation
                .awaitsShareImportFormat(),
        )
        // A share held for a DIFFERENT reason (e.g. quiesce) is not yet at its format step.
        assertFalse(
            overview()
                .copy(pendingExternalNavigation = PendingExternalNavigation(shareImport, ExternalNavigationHoldReason.RUNTIME_QUIESCED))
                .pendingExternalNavigation
                .awaitsShareImportFormat(),
        )
        assertFalse((null as PendingExternalNavigation?).awaitsShareImportFormat())
    }

    // ---------------------------------------------------------------- (1) format selection → intake

    @Test
    fun aTakenSourceAdvancesToTheIntakePathAndTheConsumedEventClearsTheSlot() {
        // The pure seam: a non-null take is the StartIntake decision and maps to the consumed event.
        val decision = shareImportTakeDecision(file())
        val startIntake = assertIs<ShareImportTakeDecision.StartIntake>(decision)
        assertEquals("shared-statement.csv", startIntake.file.displayName)
        assertEquals(P503UiEvent.ShareImportConsumed, shareImportTakeEvent(decision))

        // Applying the consumed event on a held share clears the pending slot (the share is consumed).
        val consumed = assertIs<P503AppState.OverviewEmpty>(reduce(heldShare(), P503UiEvent.ShareImportConsumed))
        assertNull(consumed.pendingExternalNavigation)
        // Zero formal effect: no candidate list or session summary was fabricated by the clear.
        assertNull(consumed.importReview)
    }

    @Test
    fun aConsumedEventLeavesANonShareSlotUntouched() {
        // A consumed event must never clear an unrelated held request (no cross-request clearing).
        val other =
            overview().copy(
                pendingExternalNavigation = PendingExternalNavigation(ExternalNavigationRequest.OpenCurrentMonth, ExternalNavigationHoldReason.UNROUTABLE_TARGET),
            )
        assertSame(other, reduce(other, P503UiEvent.ShareImportConsumed))
    }

    // ---------------------------------------------------------------- (2) missing source → re-share

    @Test
    fun aMissingSourceYieldsTheTypedReShareWithZeroFormalEffectAndTheSlotCleared() {
        // A null take is the typed "source unavailable" case (grant expired / process death).
        val decision = shareImportTakeDecision(null)
        assertEquals(ShareImportTakeDecision.ReShareRequired, decision)
        assertEquals(P503UiEvent.ShareImportReShareLanded, shareImportTakeEvent(decision))

        val landed = assertIs<P503AppState.OverviewEmpty>(reduce(heldShare(), P503UiEvent.ShareImportReShareLanded))
        // The slot is cleared (the request is abandoned) and a visible re-share prompt stands.
        assertNull(landed.pendingExternalNavigation)
        assertEquals(ExternalNavigationHoldReason.IMPORT_SOURCE_UNAVAILABLE, landed.externalNavigationBusy?.reason)
        // Zero formal effect: nothing was imported and no candidate list materialized.
        assertNull(landed.importReview)
        // The prompt is dismissible (保留当前), never a silent drop and never a stuck modal.
        assertNull(reduce(landed, P503UiEvent.DismissExternalNavigationBusy).externalNavigationBusyOrNull())
    }

    @Test
    fun testingTheReSharePromptCopyLeaksNoAmountOrPayload() {
        val copy = externalNavigationBusyText(ExternalNavigationBusy(ExternalNavigationHoldReason.IMPORT_SOURCE_UNAVAILABLE))
        assertTrue(copy.isNotBlank())
        assertTrue(copy.none { it.isDigit() }, "the re-share prompt must contain no amount literal: $copy")
        assertTrue(!copy.contains("delivery-1"), "the re-share prompt must not echo a deliveryId: $copy")
    }

    // ---------------------------------------------------------------- read-failure re-share vectors

    @Test
    fun aReadFailureOnTheSharePathStillLandsTheGenericReShareAndAnIntakeLandsNothing() {
        // The UNREADABLE-source vectors: grant expiry/revocation before the read and a mid-read
        // failure (the missing-source vector is the null take above; an over-16 MiB source has its
        // OWN prompt below). These keep the original IMPORT_SOURCE_UNAVAILABLE path.
        assertEquals(P503UiEvent.ShareImportReShareLanded, shareImportReadOutcomeEvent(ImportIntakePipelineOutcome.ReadFailed(ImportPickReadFailure.STREAM_OPEN_FAILED)))
        assertEquals(P503UiEvent.ShareImportReShareLanded, shareImportReadOutcomeEvent(ImportIntakePipelineOutcome.ReadFailed(ImportPickReadFailure.STREAM_READ_FAILED)))
        // A completed intake (accepted / no-change / typed format rejection) is NOT a source failure.
        assertNull(
            shareImportReadOutcomeEvent(
                ImportIntakePipelineOutcome.Intaken(
                    ImportFileIntakeOutcome.Accepted(
                        records =
                            listOf(
                                ImportIntakeRecordSummary(
                                    recordOrdinal = 0,
                                    disposition = ImportIntakeRecordDisposition.INTAKE_ACCEPTED,
                                ),
                            ),
                        newCandidateIds = emptyList(),
                    ),
                ),
            ),
        )
        assertNull(shareImportReadOutcomeEvent(ImportIntakePipelineOutcome.Intaken(ImportFileIntakeOutcome.NoChangeAll(emptyList()))))
    }

    @Test
    fun anOverLimitSourceLandsItsOwnTooLargePromptInsteadOfTheGenericReShare() {
        // P709-A02 error mapping: the source WAS readable and the bounded read refused it for
        // exceeding the frozen 16 MiB bound, so the generic re-share copy ("授权已过期…请重新分享")
        // states a cause that is false and an action that could never succeed.
        assertEquals(
            P503UiEvent.ShareImportTooLargeLanded,
            shareImportReadOutcomeEvent(ImportIntakePipelineOutcome.ReadExceedsLimit(actualBytes = 17_000_000L)),
        )

        // Reducer: the awaiting-format slot is cleared, the too-large reason lands, zero formal effect.
        val landed = assertIs<P503AppState.OverviewEmpty>(reduce(heldShare(), P503UiEvent.ShareImportTooLargeLanded))
        assertNull(landed.pendingExternalNavigation)
        assertEquals(ExternalNavigationHoldReason.IMPORT_SOURCE_TOO_LARGE, landed.externalNavigationBusy?.reason)
        assertNull(landed.importReview)
        // The strip is dismissible (保留当前), never a stuck modal.
        assertNull(reduce(landed, P503UiEvent.DismissExternalNavigationBusy).externalNavigationBusyOrNull())

        // The copy states the frozen bound plus the zero formal effect, and misstates nothing.
        val copy = externalNavigationBusyText(landed.externalNavigationBusy!!)
        assertEquals("分享文件超过 16 MiB 上限，未导入任何内容。", copy)
        assertFalse(copy.contains("重新分享"), "a readable-but-oversize source must not ask for a re-share: $copy")
        assertFalse(copy.contains("授权"), "the cause is the size bound, not an expired grant: $copy")
        assertFalse(copy.contains("delivery-1"), "the strip must not echo a deliveryId: $copy")

        // The retained source leaves the slot, so the host releases it exactly like a re-share...
        val held = PendingExternalNavigation(shareImport, ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED)
        assertEquals(setOf("delivery-1"), releasedShareDeliveryIds(held, landed.pendingExternalNavigationSlot()))
        // ...and the event drops the host fallback too (the B2 no-silent-discard seam).
        assertTrue(clearsExternalNavigation(P503UiEvent.ShareImportTooLargeLanded))
    }

    // ---------------------------------------------------------------- the source port contract

    @Test
    fun theSourcePortDefaultReleaseIsANoOpAndTakeIsNullable() {
        // The JVM-assertable port contract: take may return null and the default release no-ops
        // (so desktop/legacy constructions and tests keep compiling without a registry).
        val port =
            object : ShareImportSourcePort {
                override fun take(deliveryId: String): PickedImportFile? = null
            }
        assertNull(port.take("delivery-1"))
        port.release("delivery-1")
    }

    @Test
    fun theMatrixFormatsTheSharePromptOffersAreTheExistingCapabilityMatrix() {
        // Invariant 1: the format choices are the EXISTING capability matrix (no new list).
        // P8-04 i2 (D-13): the matrix itself gained the fifth entry with Android-pending
        // availability — the share prompt mirrors the matrix (R-Q08-3 honest availability),
        // so the offered count follows the matrix, not a hand-frozen four.
        val android = importFormatEntries(ImportPlatformKind.ANDROID)
        assertEquals(5, android.size)
        assertEquals(
            listOf("wechat-xlsx", "alipay-csv", "cmb-csv", "ccb-xls", "self-entry-xlsx"),
            android.map { it.descriptor.identifier.value },
        )
        // The fifth entry is offered with its honest pending availability, not silently hidden.
        assertEquals(
            ImportFormatAvailability.PENDING_DEVICE_VERIFICATION,
            android.last().availability,
        )
    }

    // ---------------------------------------------------------------- absorption discipline

    @Test
    fun theClosedLoopEventsAreAbsorbedOnNonSlotSurfaces() {
        val detail =
            P503AppState.TransactionDetail(
                overview(),
                P503Tab.HOME,
                com.unifiedledger.domain.TransactionId("tx-1"),
                com.unifiedledger.application.TransactionDetailResult.Unavailable,
            )
        assertSame(detail, reduce(detail, P503UiEvent.ShareImportConsumed))
        assertSame(detail, reduce(detail, P503UiEvent.ShareImportReShareLanded))
        assertSame(detail, reduce(detail, P503UiEvent.ShareImportTooLargeLanded))
    }

    // ---------------------------------------------------------------- fix round B1: release on abandon

    @Test
    fun abandoningTheHeldShareViaKeepCurrentConsumeOrReShareReleasesTheRetainedDelivery() {
        // B1: leaving the awaiting-format share by 保留当前, a consumed format selection or the typed
        // re-share must RELEASE the retained source (the slot transition is the host's release key).
        val held = PendingExternalNavigation(shareImport, ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED)

        // 保留当前 clears the slot (OverviewEmpty carries it) — the id is released.
        val keptCurrent = assertIs<P503AppState.OverviewEmpty>(reduce(heldShare(), P503UiEvent.ExternalNavigationKeptCurrent))
        assertNull(keptCurrent.pendingExternalNavigation)
        assertEquals(setOf("delivery-1"), releasedShareDeliveryIds(held, keptCurrent.pendingExternalNavigationSlot()))

        // A CONSUMED format selection (the pipeline owns the result) also releases.
        val consumed = reduce(heldShare(), P503UiEvent.ShareImportConsumed)
        assertEquals(setOf("delivery-1"), releasedShareDeliveryIds(held, consumed.pendingExternalNavigationSlot()))
        // The typed re-share also releases (nothing is retained after a failed read).
        assertEquals(setOf("delivery-1"), releasedShareDeliveryIds(held, reduce(heldShare(), P503UiEvent.ShareImportReShareLanded).pendingExternalNavigationSlot()))
    }

    @Test
    fun discardAndSwitchRoutsTheHeldShareRatherThanAbandoningIt() {
        // B1 boundary: 放弃后切换 on an IDLE overview ROUTES the held request (spec section 3 Editing
        // row). For a share that means it is RE-HELD awaiting its format — the grant is still needed,
        // so the retained source must NOT be released by this transition.
        val held = PendingExternalNavigation(shareImport, ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED)
        val discarded = assertIs<P503AppState.OverviewEmpty>(reduce(heldShare(), P503UiEvent.ExternalNavigationDiscarded))
        assertEquals("delivery-1", (discarded.pendingExternalNavigation?.request as ExternalNavigationRequest.OpenImport).deliveryId)
        assertTrue(releasedShareDeliveryIds(held, discarded.pendingExternalNavigationSlot()).isEmpty())
    }

    @Test
    fun aShareStillAwaitingItsFormatIsNotReleased() {
        // B1 negative: while the SAME awaiting-format share is still held, nothing is released (an
        // unrelated event must not drop the retained grant the format choice still needs).
        val held = PendingExternalNavigation(shareImport, ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED)
        assertTrue(releasedShareDeliveryIds(held, held).isEmpty())
        // A different still-awaiting share (an explicit replace) releases the ORIGINAL id.
        val replaced = PendingExternalNavigation(ExternalNavigationRequest.OpenImport("delivery-2"), ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED)
        assertEquals(setOf("delivery-1"), releasedShareDeliveryIds(held, replaced))
        // Nothing to release when the previous slot held no share at all.
        assertTrue(releasedShareDeliveryIds(null, held).isEmpty())
        assertTrue(releasedShareDeliveryIds(PendingExternalNavigation(ExternalNavigationRequest.OpenCurrentMonth, ExternalNavigationHoldReason.UNROUTABLE_TARGET), null).isEmpty())
    }

    // ---------------------------------------------------------------- fix round NF1: displaced share released

    @Test
    fun retainedOriginalReleasesTheDisplacedShareAndReplaceReleasesTheOriginal() {
        // NF1: a SECOND share `d2` arrives while `d1` is held awaiting its format. The reducer lands
        // RetainOrReplace (`{request=d1, awaitingUserDecision, displaced=d2}`) and the Activity has
        // ALREADY retained d2. Both sides hold both ids, so the slot's held-share set is {d1, d2}.
        val held = PendingExternalNavigation(shareImport, ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED)
        val d2 = ExternalNavigationRequest.OpenImport("delivery-2")
        val retainedOrReplace =
            reduce(
                heldShare(),
                P503UiEvent.ExternalNavigationDecisionLanded(ExternalNavigationDecision.RetainOrReplace(held, d2)),
            )
        val displaceSlot = assertIs<P503AppState.OverviewEmpty>(retainedOrReplace).pendingExternalNavigation!!
        assertEquals(setOf("delivery-1", "delivery-2"), displaceSlot.heldShareDeliveryIds())

        // 保留原请求 clears `displaced` → d2 leaves the slot and MUST be released (the former
        // request-id-only comparison returned null here and leaked d2's grant).
        val retained = assertIs<P503AppState.OverviewEmpty>(reduce(retainedOrReplace, P503UiEvent.ExternalNavigationRetained)).pendingExternalNavigation!!
        assertEquals("delivery-1", (retained.request as ExternalNavigationRequest.OpenImport).deliveryId)
        assertNull(retained.displaced)
        assertEquals(setOf("delivery-2"), releasedShareDeliveryIds(displaceSlot, retained))

        // The REPLACE path swaps `request` to the incoming d2 → d1 leaves and MUST be released.
        val replaced = assertIs<P503AppState.OverviewEmpty>(reduce(retainedOrReplace, P503UiEvent.ExternalNavigationReplaced)).pendingExternalNavigation!!
        assertEquals("delivery-2", (replaced.request as ExternalNavigationRequest.OpenImport).deliveryId)
        assertEquals(setOf("delivery-1"), releasedShareDeliveryIds(displaceSlot, replaced))
    }

    @Test
    fun anOnCurrentMonthDisplacedRequestContributesNoHeldShareId() {
        // NF1 boundary: only OpenImport ids are ever retained; a non-share displaced request (or a
        // non-share request) contributes nothing, so the difference cannot release an unrelated id.
        val held = PendingExternalNavigation(shareImport, ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED)
        val withNonShareDisplaced =
            PendingExternalNavigation(
                request = shareImport,
                holdReason = ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED,
                awaitingUserDecision = true,
                displaced = ExternalNavigationRequest.OpenCurrentMonth,
            )
        assertEquals(setOf("delivery-1"), withNonShareDisplaced.heldShareDeliveryIds())
        // Retaining drops the non-share displaced request; no share id leaves, so nothing is released.
        val retained = retainPendingExternalNavigation(withNonShareDisplaced)
        assertTrue(releasedShareDeliveryIds(withNonShareDisplaced, retained).isEmpty())
        // A share with no displaced request holds exactly its own id (no regression).
        assertEquals(setOf("delivery-1"), held.heldShareDeliveryIds())
    }

    // ---------------------------------------------------------------- fix round B2: never a silent discard

    @Test
    fun aRequestOnANonSlotSurfaceIsNotSilentlyDiscarded() {
        // B2: the reducer cannot carry the slot on TransactionDetail (it returns the state
        // unchanged), so the HOST fallback must surface the request instead of letting it vanish.
        val detail =
            P503AppState.TransactionDetail(
                overview(),
                P503Tab.HOME,
                com.unifiedledger.domain.TransactionId("tx-1"),
                com.unifiedledger.application.TransactionDetailResult.Unavailable,
            )
        assertEquals(false, detail.carriesExternalNavigationSlot())
        val decision = ExternalNavigationDecision.Held(PendingExternalNavigation(shareImport, ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED))
        // The reducer absorbs it (unchanged, no slot)...
        assertSame(detail, reduce(detail, P503UiEvent.ExternalNavigationDecisionLanded(decision)))
        // ...but the host fallback HOLDS it, so the request stays visible and is never discarded.
        val fallback = hostFallbackHeldNavigation(detail, decision)
        assertEquals("delivery-1", (fallback?.request as ExternalNavigationRequest.OpenImport).deliveryId)
        assertTrue(fallback!!.awaitsShareImportFormat())
    }

    @Test
    fun aQuiescedRequestOnANonSlotSurfaceIsHeldNotDiscarded() {
        // B2: a share arriving during a restore-confirm quiesce (BackupRestore has no slot) is
        // translated to a VISIBLE held outcome, never a discard.
        val quiesced = P503AppState.BackupRestore(overview(), runningConfirm = true)
        assertEquals(false, quiesced.carriesExternalNavigationSlot())
        val decision = ExternalNavigationDecision.Held(PendingExternalNavigation(shareImport, ExternalNavigationHoldReason.RUNTIME_QUIESCED))
        val fallback = hostFallbackHeldNavigation(quiesced, decision)
        assertEquals(ExternalNavigationHoldReason.RUNTIME_QUIESCED, fallback?.holdReason)
    }

    @Test
    fun noHostFallbackIsNeededOnASlotSurfaceOrForARoutedRequest() {
        // B2: when the landed surface carries the slot (the reducer owns it) the host holds nothing.
        val heldDecision = ExternalNavigationDecision.Held(PendingExternalNavigation(shareImport, ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED))
        assertNull(hostFallbackHeldNavigation(heldShare(), heldDecision))
        // A routed request on a slot surface is the reducer's Idle navigation, not a hold.
        assertNull(hostFallbackHeldNavigation(overview(), ExternalNavigationDecision.Routed(ExternalNavigationRequest.OpenNewEntry(com.unifiedledger.application.EntryType.EXPENSE))))
        // A Deferred request on a non-slot surface is held as STARTUP_NOT_READY (visible).
        val detail =
            P503AppState.TransactionDetail(
                overview(),
                P503Tab.HOME,
                com.unifiedledger.domain.TransactionId("tx-1"),
                com.unifiedledger.application.TransactionDetailResult.Unavailable,
            )
        assertEquals(
            ExternalNavigationHoldReason.STARTUP_NOT_READY,
            hostFallbackHeldNavigation(detail, ExternalNavigationDecision.DeferredUntilReady(shareImport))?.holdReason,
        )
    }

    // ---------------------------------------------------------------- fix round B5: admission before consume

    @Test
    fun theRelayAdmissionRefusesWhenTheIntakeSingleFlightIsBusy() {
        // B5: the single-flight admission predicate refuses a relay while an intake is in flight, so
        // the retained source is never consumed on a refused attempt (the source/slot stay retryable).
        assertTrue(shareImportRelayAllowed(intakeBusy = false))
        assertFalse(shareImportRelayAllowed(intakeBusy = true))
    }
}
