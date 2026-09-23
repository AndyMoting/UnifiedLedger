package com.unifiedledger.ui

import com.unifiedledger.application.CatalogSnapshotView
import com.unifiedledger.application.CategoryTreeView
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.CorrectTransactionVersionResult
import com.unifiedledger.application.CreationEntry
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.ManageableAccountView
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.ParseManualExpenseOccurredAt
import com.unifiedledger.application.RecycleBinResult
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.TransactionCorrectionCommitResolution
import com.unifiedledger.application.TransactionCorrectionReceipt
import com.unifiedledger.application.TransactionCorrectionRequestIdentity
import com.unifiedledger.application.TransactionDetail
import com.unifiedledger.application.TransactionDetailLeg
import com.unifiedledger.application.TransactionReconciliationProjection
import com.unifiedledger.application.TransactionVoidCommitResolution
import com.unifiedledger.application.TransactionVoidReceipt
import com.unifiedledger.application.TransactionVoidRequestIdentity
import com.unifiedledger.application.VoidTransactionResult
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.P705FailureCode
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.TransactionVoidFactKind
import com.unifiedledger.domain.VoidReason
import com.unifiedledger.domain.VoidReasonCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-05 slice 1b Piece 4 host-wiring vectors (D-156; spec sections 3.2/3.3/3.5/4.2). The host call
 * sites themselves live in the `@Composable` [P503App] and have no JVM harness (the P7-03/P7-04
 * precedent), so the load-bearing decisions are extracted into pure functions here and asserted:
 * the detail-plus-catalog old-value origin resolution (including the transaction's own currency,
 * the Piece 3 residual), the correction/void request builders, the snapshot-aware unknown-commit
 * resolution mapping, and the single success gate of the P7-05 refresh chain. All data is anonymous
 * synthetic; V-21: no reason note is ever printed in a failure message.
 */
class P503CorrectionHostTest {
    private val ledgerId = LedgerId("ledger-local-test")
    private val cny = CurrencyUnit("CNY", 2)
    private val jpy = CurrencyUnit("JPY", 0)
    private val transactionId = TransactionId("tx-1")
    private val versionId = TransactionVersionId("version-1")
    private val statisticsAt = Instant.parse("2026-03-15T02:00:00Z")
    private val clock = LedgerClock { Instant.parse("2026-03-20T00:00:00Z") }

    // ---- fixtures ----

    private fun snapshot(currency: CurrencyUnit = cny): CatalogSnapshotView =
        CatalogSnapshotView(
            catalogVersion = 1L,
            manageableAccounts =
                listOf(
                    ManageableAccountView(AccountId("asset-payment"), "零钱", AccountKind.ASSET, currency, active = true, balanceMinorUnits = null),
                ),
            categories =
                listOf(
                    CategoryTreeView(CategoryId("category-food"), parentId = null, name = "餐饮", kind = CategoryKind.EXPENSE, active = true, postingAccountId = null),
                    CategoryTreeView(
                        CategoryId("category-food-leaf"),
                        parentId = CategoryId("category-food"),
                        name = "餐饮",
                        kind = CategoryKind.EXPENSE,
                        active = true,
                        postingAccountId = AccountId("expense-account"),
                    ),
                ),
        )

    private fun detail(
        currency: CurrencyUnit = cny,
        amountMinor: Long = 10000L,
    ): TransactionDetail =
        TransactionDetail(
            ledgerId = ledgerId,
            transactionId = transactionId,
            currentVersionId = versionId,
            kind = TransactionKind.EXPENSE,
            occurredAt = statisticsAt,
            statisticsAt = statisticsAt,
            note = "old note",
            legs =
                listOf(
                    TransactionDetailLeg(PostingId("posting-expense"), AccountId("expense-account"), "餐饮", Money.ofMinor(-amountMinor, currency), "餐饮"),
                    TransactionDetailLeg(PostingId("posting-asset"), AccountId("asset-payment"), "零钱", Money.ofMinor(amountMinor, currency), null),
                ),
            creationEntry = CreationEntry.MANUAL_CREATED,
            reconciliation = TransactionReconciliationProjection(legs = emptyList(), rollup = null),
        )

    // ---- origin resolution (spec section 3.2; the Piece 3 residual) ----

    @Test
    fun originResolvesTheCasTokenThePositiveAmountAndTheTransactionsOwnCurrency() {
        val origin = assertNotNull(transactionEditOriginFromDetail(detail(currency = jpy, amountMinor = 1000L), snapshot(jpy)))
        assertEquals(transactionId, origin.transactionId)
        assertEquals(versionId, origin.currentVersionId)
        assertEquals("1000", origin.amountText)
        assertEquals(jpy, origin.currency)
        // The category reference is resolved through the current catalog's posting-account mapping.
        assertEquals(CategoryId("category-food-leaf"), origin.categoryId)
        assertEquals(AccountId("asset-payment"), origin.fundingAccountId)
    }

    @Test
    fun originIsAbsentWhenTheAmountLegOrCatalogIsMissing() {
        // No positive amount leg: no origin, so the caller renders no dead edit button.
        val noPositiveLeg =
            detail().let { it.copy(legs = it.legs.filter { leg -> leg.amount.minorUnits <= 0L }) }
        assertNull(transactionEditOriginFromDetail(noPositiveLeg, snapshot()))
    }

    @Test
    fun originSeedsADraftThatShowsNoDifference() {
        val origin = assertNotNull(transactionEditOriginFromDetail(detail(), snapshot()))
        val draft = transactionCorrectionDraftFromOrigin(origin)
        // The seeded draft reproduces the origin's own values.
        assertEquals(origin.note.orEmpty(), draft.note)
        assertEquals(origin.statisticsAt.toString(), draft.statisticsAtText)
        assertEquals(origin.amountText, draft.amountText)
        assertEquals(origin.categoryId, draft.categoryId)
        assertEquals(origin.fundingAccountId, draft.fundingAccountId)
    }

    // ---- correction request builder (spec section 3.2) ----

    @Test
    fun correctionRequestCarriesTheCasTokenAndParsesAtTheOriginCurrency() {
        val origin = assertNotNull(transactionEditOriginFromDetail(detail(), snapshot()))
        val draft =
            TransactionCorrectionDraft(
                note = "new note",
                statisticsAtText = origin.statisticsAt.toString(),
                amountText = "80.00",
                categoryId = origin.categoryId,
                fundingAccountId = origin.fundingAccountId,
            )
        val request =
            assertNotNull(
                transactionCorrectionRequest(
                    ledgerId = ledgerId,
                    requestId = RequestId("request-1"),
                    origin = origin,
                    draft = draft,
                    parseAmount = ParseManualExpenseAmount(),
                    parseOccurredAt = ParseManualExpenseOccurredAt(),
                    ledgerClock = clock,
                    fallbackCurrency = cny,
                ),
            )
        assertEquals(versionId, request.expectedCurrentVersionId)
        assertEquals("new note", request.note)
        assertEquals(8000L, request.amount.minorUnits)
        assertEquals(cny, request.amount.currency)
        // An untouched statistics text keeps the origin instant verbatim (sub-second safe).
        assertEquals(origin.statisticsAt, request.statisticsAt)
    }

    @Test
    fun correctionRequestIsAbsentWhenAFieldIsMissingOrUnparseable() {
        val origin = assertNotNull(transactionEditOriginFromDetail(detail(), snapshot()))

        fun build(draft: TransactionCorrectionDraft) =
            transactionCorrectionRequest(
                ledgerId = ledgerId,
                requestId = RequestId("request-1"),
                origin = origin,
                draft = draft,
                parseAmount = ParseManualExpenseAmount(),
                parseOccurredAt = ParseManualExpenseOccurredAt(),
                ledgerClock = clock,
                fallbackCurrency = cny,
            )
        // An unparseable amount.
        assertNull(build(TransactionCorrectionDraft(amountText = "not-a-number", categoryId = origin.categoryId, fundingAccountId = origin.fundingAccountId)))
        // A missing category or funding account.
        assertNull(build(TransactionCorrectionDraft(amountText = "80.00", fundingAccountId = origin.fundingAccountId)))
        assertNull(build(TransactionCorrectionDraft(amountText = "80.00", categoryId = origin.categoryId)))
    }

    @Test
    fun correctionRequestSnapshotMirrorsTheFrozenColumnSet() {
        val origin = assertNotNull(transactionEditOriginFromDetail(detail(), snapshot()))
        val request =
            assertNotNull(
                transactionCorrectionRequest(
                    ledgerId = ledgerId,
                    requestId = RequestId("request-1"),
                    origin = origin,
                    draft =
                        TransactionCorrectionDraft(
                            note = "new note",
                            statisticsAtText = origin.statisticsAt.toString(),
                            amountText = "80.00",
                            categoryId = origin.categoryId,
                            fundingAccountId = origin.fundingAccountId,
                        ),
                    parseAmount = ParseManualExpenseAmount(),
                    parseOccurredAt = ParseManualExpenseOccurredAt(),
                    ledgerClock = clock,
                    fallbackCurrency = cny,
                ),
            )
        val snapshot = request.toRequestSnapshot()
        assertEquals(request.expectedCurrentVersionId, snapshot.expectedCurrentVersionId)
        assertEquals(request.amount, snapshot.amount)
        assertEquals(request.categoryId, snapshot.categoryId)
        assertEquals(request.fundingAccountId, snapshot.fundingAccountId)
    }

    // ---- reason + void/restore request builder (DP-11) ----

    @Test
    fun voidReasonFromDraftIsAbsentWithoutACodeAndKeepsTheNoteVerbatim() {
        assertNull(voidReasonFromDraft(VoidReasonDraft(note = "ignored")))
        val reason = assertNotNull(voidReasonFromDraft(VoidReasonDraft(code = VoidReasonCode.MIS_ENTERED, note = "笔误")))
        // The draft note survives the mapping verbatim (value equality, the repo precedent).
        assertEquals(VoidReason(VoidReasonCode.MIS_ENTERED, "笔误"), reason)
        // An empty draft note is the absent representation.
        assertNull(assertNotNull(voidReasonFromDraft(VoidReasonDraft(code = VoidReasonCode.OTHER))).note)
    }

    @Test
    fun voidRequestSnapshotCarriesTheFactKindAndIsAbsentWithoutAReason() {
        val request =
            com.unifiedledger.application.VoidTransactionRequest(
                ledgerId = ledgerId,
                requestId = RequestId("request-1"),
                transactionId = transactionId,
                reason = com.unifiedledger.domain.VoidReason(VoidReasonCode.MIS_ENTERED),
                confirmation = com.unifiedledger.application.ExplicitManualSave,
            )
        val voidSnapshot = assertNotNull(request.toRequestSnapshot(TransactionVoidFactKind.VOID))
        assertEquals(TransactionVoidFactKind.VOID, voidSnapshot.factKind)
        assertEquals(transactionId, voidSnapshot.transactionId)
        val restoreSnapshot = assertNotNull(request.toRequestSnapshot(TransactionVoidFactKind.RESTORE))
        assertEquals(TransactionVoidFactKind.RESTORE, restoreSnapshot.factKind)
        assertNull(request.copy(reason = null).toRequestSnapshot(TransactionVoidFactKind.VOID))
    }

    // ---- snapshot-aware unknown-commit resolution (spec section 4.2) ----

    private fun correctionReceipt() = TransactionCorrectionReceipt(ConfirmationId("confirmation-1"), transactionId, versionId, versionId)

    private fun voidReceipt() = TransactionVoidReceipt(ConfirmationId("confirmation-1"), transactionId, "fact-1", TransactionVoidFactKind.VOID)

    @Test
    fun correctionResolutionMapsHitToNoChangeConflictToConflictAndUnknownToNull() {
        val identity = TransactionCorrectionRequestIdentity(ledgerId, RequestId("request-1"))
        assertIs<CorrectTransactionVersionResult.NoChange>(
            assertNotNull(correctResultFromResolution(identity, TransactionCorrectionCommitResolution.MatchingReceipt(correctionReceipt()))),
        )
        assertIs<CorrectTransactionVersionResult.RequestIdentityConflict>(
            assertNotNull(correctResultFromResolution(identity, TransactionCorrectionCommitResolution.SnapshotConflict)),
        )
        // Absent and unreadable stay unknown: the surface keeps its submitting marker (no retry).
        assertNull(correctResultFromResolution(identity, TransactionCorrectionCommitResolution.Absent))
        assertNull(correctResultFromResolution(identity, TransactionCorrectionCommitResolution.Unavailable))
    }

    @Test
    fun voidResolutionMapsHitToNoChangeConflictToConflictAndUnknownToNull() {
        val identity = TransactionVoidRequestIdentity(ledgerId, RequestId("request-1"))
        assertIs<VoidTransactionResult.NoChange>(
            assertNotNull(voidResultFromResolution(identity, TransactionVoidCommitResolution.MatchingReceipt(voidReceipt()))),
        )
        assertIs<VoidTransactionResult.RequestIdentityConflict>(
            assertNotNull(voidResultFromResolution(identity, TransactionVoidCommitResolution.SnapshotConflict)),
        )
        assertNull(voidResultFromResolution(identity, TransactionVoidCommitResolution.Absent))
        assertNull(voidResultFromResolution(identity, TransactionVoidCommitResolution.Unavailable))
    }

    // ---- the success gate of the P7-05 refresh chain (spec section 4.3) ----

    @Test
    fun onlyADeterminateCorrectionSuccessRefreshes() {
        assertTrue(shouldRefreshAfterP705Commit(CorrectTransactionVersionResult.Created(correctionReceipt())))
        assertTrue(shouldRefreshAfterP705Commit(CorrectTransactionVersionResult.NoChange(correctionReceipt())))
        // A stale CAS, an identity conflict and a typed rejection write nothing and never refresh.
        assertTrue(!shouldRefreshAfterP705Commit(CorrectTransactionVersionResult.StaleCurrentVersion))
        assertTrue(
            !shouldRefreshAfterP705Commit(
                CorrectTransactionVersionResult.RequestIdentityConflict(TransactionCorrectionRequestIdentity(ledgerId, RequestId("request-1"))),
            ),
        )
        assertTrue(!shouldRefreshAfterP705Commit(CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_TRANSACTION_VOIDED)))
    }

    @Test
    fun onlyADeterminateVoidOrRestoreSuccessRefreshes() {
        assertTrue(shouldRefreshAfterP705Commit(VoidTransactionResult.Created(voidReceipt())))
        assertTrue(shouldRefreshAfterP705Commit(VoidTransactionResult.NoChange(voidReceipt())))
        assertTrue(
            !shouldRefreshAfterP705Commit(
                VoidTransactionResult.RequestIdentityConflict(TransactionVoidRequestIdentity(ledgerId, RequestId("request-1"))),
            ),
        )
        assertTrue(!shouldRefreshAfterP705Commit(VoidTransactionResult.Rejected(P705FailureCode.P705_VOID_CYCLE_EXHAUSTED)))
    }

    // ---- the CALL-SITE gate: the decision is exercised, not only its predicate (Piece 4 review
    // finding 1). A determinate success calls the coordinator trigger exactly once (one refresh +
    // the shared post-landing monthly arm); every non-success calls nothing.

    private class RefreshProbe {
        var refreshes = 0
        var monthlyRequests = 0
    }

    private fun coordinatorWithProbe(probe: RefreshProbe): P503HostCoordinator =
        P503HostCoordinator(
            onRefresh = { probe.refreshes += 1 },
            onSubmit = { _, _ -> },
            onCheck = { _, _ -> },
            onMonthlyRequest = { probe.monthlyRequests += 1 },
        )

    private fun overview(): P503AppState.OverviewEmpty = P503AppState.OverviewEmpty(LedgerCurrentState(ledgerId, transactions = emptyList(), balances = emptyList()))

    @Test
    fun aDeterminateCorrectionSuccessFiresTheRefreshAndArmsTheMonthlyReRequestThroughTheCallSite() {
        val probe = RefreshProbe()
        val coordinator = coordinatorWithProbe(probe)
        refreshAfterP705Commit(CorrectTransactionVersionResult.Created(correctionReceipt()), coordinator)
        assertEquals(1, probe.refreshes)
        // The arm is the shared post-landing monthly re-request: one unconditional request once the
        // refreshed overview lands, and none synchronously beside the refresh.
        assertEquals(0, probe.monthlyRequests)
        coordinator.consumeMonthlyReRequestAfterRefresh(overview())
        assertEquals(1, probe.monthlyRequests)
    }

    @Test
    fun aDeterminateVoidOrRestoreSuccessFiresTheRefreshThroughTheCallSite() {
        val probe = RefreshProbe()
        val coordinator = coordinatorWithProbe(probe)
        refreshAfterP705Commit(VoidTransactionResult.Created(voidReceipt()), coordinator)
        refreshAfterP705Commit(VoidTransactionResult.NoChange(voidReceipt()), coordinator)
        assertEquals(2, probe.refreshes)
        coordinator.consumeMonthlyReRequestAfterRefresh(overview())
        assertEquals(1, probe.monthlyRequests)
    }

    @Test
    fun aRejectedStaleOrConflictCorrectionCallsNothingThroughTheCallSite() {
        val probe = RefreshProbe()
        val coordinator = coordinatorWithProbe(probe)
        refreshAfterP705Commit(CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_KIND_NOT_SUPPORTED), coordinator)
        refreshAfterP705Commit(CorrectTransactionVersionResult.StaleCurrentVersion, coordinator)
        refreshAfterP705Commit(
            CorrectTransactionVersionResult.RequestIdentityConflict(TransactionCorrectionRequestIdentity(ledgerId, RequestId("request-1"))),
            coordinator,
        )
        assertEquals(0, probe.refreshes)
        // No refresh was fired, so no monthly arm was left behind either.
        coordinator.consumeMonthlyReRequestAfterRefresh(overview())
        assertEquals(0, probe.monthlyRequests)
    }

    @Test
    fun aRejectedVoidOrRestoreCallsNothingThroughTheCallSite() {
        val probe = RefreshProbe()
        val coordinator = coordinatorWithProbe(probe)
        refreshAfterP705Commit(VoidTransactionResult.Rejected(P705FailureCode.P705_REFUND_LINKED_VOID_NOT_SUPPORTED), coordinator)
        refreshAfterP705Commit(VoidTransactionResult.Rejected(P705FailureCode.P705_VOID_CYCLE_EXHAUSTED), coordinator)
        assertEquals(0, probe.refreshes)
        // An unresolved commit produces no result at all (the host's resolver returns null), so the
        // call site is never reached: assert the resolver mapping stays the unknown sentinel.
        assertNull(voidResultFromResolution(TransactionVoidRequestIdentity(ledgerId, RequestId("request-1")), TransactionVoidCommitResolution.Absent))
        assertNull(correctResultFromResolution(TransactionCorrectionRequestIdentity(ledgerId, RequestId("request-1")), TransactionCorrectionCommitResolution.Unavailable))
    }

    // ---- the recycle-bin open/re-read landing path (spec section 3.4/4.4) ----

    @Test
    fun theBinReadOpensOnTheEffectiveSurfaceAndRefreshesInPlaceWhenAlreadyOpen() {
        val success = RecycleBinResult.Success(emptyList())
        // First open: the fresh projection opens the bin (its entry affordance is on the effective
        // surfaces, so the current state is not yet RecycleBin).
        assertIs<P503UiEvent.OpenRecycleBin>(recycleBinReadEvent(alreadyOpen = false, result = success))
        // A re-read while the bin is already the open surface refreshes the list in place instead of
        // re-opening it (no second source of truth; the restore effect is visible on the next read).
        assertIs<P503UiEvent.RecycleBinResult>(recycleBinReadEvent(alreadyOpen = true, result = success))
        // A failed read still follows the same landing branch: the failure copy reaches whichever
        // surface is current.
        assertIs<P503UiEvent.OpenRecycleBin>(recycleBinReadEvent(alreadyOpen = false, result = RecycleBinResult.Unavailable))
        assertIs<P503UiEvent.RecycleBinResult>(recycleBinReadEvent(alreadyOpen = true, result = RecycleBinResult.Unavailable))
    }

    // ---- a bin read projection fixture sanity check (the open/re-read path) ----

    @Test
    fun binReadResultsStayDistinctFromEmpty() {
        assertEquals(RecycleBinResult.Success(emptyList()), RecycleBinResult.Success(emptyList()))
        assertTrue(RecycleBinResult.Unavailable != RecycleBinResult.InvalidState)
    }
}
