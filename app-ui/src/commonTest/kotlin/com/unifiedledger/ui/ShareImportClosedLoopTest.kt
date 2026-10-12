package com.unifiedledger.ui

import com.unifiedledger.application.ImportFileIntakeOutcome
import com.unifiedledger.application.ImportIntakeRecordSummary
import com.unifiedledger.application.ImportIntakeRecordDisposition
import com.unifiedledger.application.ImportIntakeSessionIdentity
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
 * format-from-the-matrix → take the retained source → the EXISTING intake pipeline, and the typed
 * re-share case on a missing source or a read failure — with ZERO formal effect and the pending slot
 * always cleared (never a silent drop).
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
    fun aReadFailureOrAnOverLimitSourceOnTheSharePathRequiresReShareButAnIntakeDoesNot() {
        // The four frozen read-failure vectors: grant expiry/revocation before the read, a mid-read
        // failure, an over-16 MiB source (the missing-source vector is the null take above).
        assertTrue(shareImportReShareRequired(ImportIntakePipelineOutcome.ReadFailed(ImportPickReadFailure.STREAM_OPEN_FAILED)))
        assertTrue(shareImportReShareRequired(ImportIntakePipelineOutcome.ReadFailed(ImportPickReadFailure.STREAM_READ_FAILED)))
        assertTrue(shareImportReShareRequired(ImportIntakePipelineOutcome.ReadExceedsLimit(actualBytes = 17_000_000L)))
        // A completed intake (accepted / no-change / typed format rejection) is NOT a re-share case.
        assertFalse(
            shareImportReShareRequired(
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
        assertFalse(shareImportReShareRequired(ImportIntakePipelineOutcome.Intaken(ImportFileIntakeOutcome.NoChangeAll(emptyList()))))
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
        val android = importFormatEntries(ImportPlatformKind.ANDROID)
        assertEquals(4, android.size)
        assertEquals(listOf("wechat-xlsx", "alipay-csv", "cmb-csv", "ccb-xls"), android.map { it.descriptor.identifier.value })
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
    }
}
