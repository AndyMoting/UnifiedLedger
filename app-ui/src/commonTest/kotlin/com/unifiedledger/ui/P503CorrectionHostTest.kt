package com.unifiedledger.ui

import com.unifiedledger.application.CatalogSnapshotView
import com.unifiedledger.application.CategoryTreeView
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.CorrectTransactionVersionResult
import com.unifiedledger.application.CreationEntry
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.ExplicitlyConfirmedTransactionCorrection
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
import com.unifiedledger.application.TransactionCorrectionRequestSnapshot
import com.unifiedledger.application.TransactionDetail
import com.unifiedledger.application.TransactionDetailLeg
import com.unifiedledger.application.TransactionReconciliationProjection
import com.unifiedledger.application.TransactionVoidCommitResolution
import com.unifiedledger.application.TransactionVoidReceipt
import com.unifiedledger.application.TransactionVoidRequestIdentity
import com.unifiedledger.application.VoidTransactionRequest
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
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-05 slice 1b Piece 4 host-wiring vectors (D-156; spec sections 3.2/3.3/3.5/4.2). The host call
 * sites themselves live in the `@Composable` [P503App] and have no JVM harness (the P7-03/P7-04
 * precedent), so the load-bearing decisions are extracted into pure functions here and asserted:
 * the detail-plus-catalog old-value origin resolution (including the transaction's own currency,
 * the Piece 3 residual), the correction/void request builders, the snapshot-aware unknown-commit
 * resolution mapping, and the single success gate of the P7-05 refresh chain. All data is anonymous
 * synthetic; this suite asserts on synthetic note literals in value equality checks only, and it
 * does not pass a note as an assertion message argument. V-21's frozen product constraint is that
 * the reason note must not enter product logs or test failure messages.
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

    // ---- the edit-entrance decision (transfer shape first, never a transfer fallback) ----

    private fun transferSnapshot(): CatalogSnapshotView =
        CatalogSnapshotView(
            catalogVersion = 1L,
            manageableAccounts =
                listOf(
                    ManageableAccountView(AccountId("asset-source"), "工资卡", AccountKind.ASSET, cny, active = true, balanceMinorUnits = null),
                    ManageableAccountView(AccountId("asset-destination"), "储蓄卡", AccountKind.ASSET, cny, active = true, balanceMinorUnits = null),
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
                        postingAccountId = AccountId("expense-fee"),
                    ),
                ),
        )

    private fun transferDetail(kind: TransactionKind = TransactionKind.ACCOUNT_TRANSFER): TransactionDetail =
        TransactionDetail(
            ledgerId = ledgerId,
            transactionId = transactionId,
            currentVersionId = versionId,
            kind = kind,
            occurredAt = statisticsAt,
            statisticsAt = statisticsAt,
            note = null,
            legs =
                listOf(
                    TransactionDetailLeg(PostingId("posting-source"), AccountId("asset-source"), "工资卡", Money.ofMinor(-10_200L, cny), null),
                    TransactionDetailLeg(PostingId("posting-destination"), AccountId("asset-destination"), "储蓄卡", Money.ofMinor(10_000L, cny), null),
                    TransactionDetailLeg(PostingId("posting-fee"), AccountId("expense-fee"), "餐饮", Money.ofMinor(200L, cny), "餐饮"),
                ),
            creationEntry = CreationEntry.MANUAL_CREATED,
            reconciliation = TransactionReconciliationProjection(legs = emptyList(), rollup = null),
        )

    @Test
    fun theEditEntrancePrefersTheTransferShapeAndNeverFallsBackForATransfer() {
        // A transfer-shaped detail opens the transfer surface.
        assertIs<TransactionEditEntrance.Transfer>(transactionEditEntrance(transferDetail(), transferSnapshot()))
        // An ordinary detail keeps the ordinary surface.
        assertIs<TransactionEditEntrance.Ordinary>(transactionEditEntrance(detail(), snapshot()))
        // A transfer whose shape does not resolve (its source leg is no longer a manageable asset)
        // resolves to NOTHING rather than falling back to the ordinary form, which cannot express a
        // destination account or a fee.
        val unresolvable =
            transferDetail().let { target ->
                target.copy(legs = target.legs.map { leg -> if (leg.accountId == AccountId("asset-source")) leg.copy(accountId = AccountId("expense-fee")) else leg })
            }
        assertNull(transactionEditEntrance(unresolvable, transferSnapshot()))
        // The same detail keeps opening the transfer surface only while its shape resolves.
        assertNotNull(transactionEditEntrance(transferDetail(), transferSnapshot()))
    }
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
        val requestSnapshot = request.toRequestSnapshot()
        assertEquals(request.expectedCurrentVersionId, requestSnapshot.expectedCurrentVersionId)
        assertEquals(request.amount, requestSnapshot.amount)
        assertEquals(request.categoryId, requestSnapshot.categoryId)
        assertEquals(request.fundingAccountId, requestSnapshot.fundingAccountId)
        // An ordinary request keeps the ordinary shape of the two transfer columns - the
        // snapshot is compared column for column by the lost-commit resolver, so the shape has to
        // be explicit rather than merely defaulted.
        assertNull(requestSnapshot.destinationAccountId)
        assertEquals(0L, requestSnapshot.feeMinor)
    }

    @Test
    fun theTransferRequestSnapshotCarriesBothTransferColumns() {
        val origin = transferOrigin()
        val request =
            assertNotNull(
                transferCorrectionRequest(
                    ledgerId = ledgerId,
                    requestId = RequestId("request-transfer"),
                    origin = origin,
                    draft = transferCorrectionDraftFromOrigin(origin),
                    statisticsAt = origin.statisticsAt,
                ),
            )
        val requestSnapshot = request.toRequestSnapshot()
        // The two transfer columns round-trip verbatim: dropping either would let a lost transfer
        // commit compare equal to another request and be reported as an already-committed hit.
        assertEquals("asset-destination", requestSnapshot.destinationAccountId)
        assertEquals(200L, requestSnapshot.feeMinor)
        assertEquals(CategoryId("category-food-leaf"), requestSnapshot.categoryId)
        assertEquals(AccountId("asset-source"), requestSnapshot.fundingAccountId)
        // A fee-less transfer stores no category and a zero fee (transfer spec section 8.3).
        val feeLess = origin.copy(feeMinorUnits = 0L, feeCategoryId = null)
        val feeLessRequest =
            assertNotNull(
                transferCorrectionRequest(
                    ledgerId = ledgerId,
                    requestId = RequestId("request-transfer-2"),
                    origin = feeLess,
                    draft = transferCorrectionDraftFromOrigin(feeLess),
                    statisticsAt = feeLess.statisticsAt,
                ),
            )
        assertNull(feeLessRequest.toRequestSnapshot().categoryId)
        assertEquals(0L, feeLessRequest.toRequestSnapshot().feeMinor)
    }

    @Test
    fun theTransferStatisticsInstantKeepsTheStoredInstantUntilTheTextIsEdited() {
        val origin = transferOrigin()
        val seeded = transferCorrectionDraftFromOrigin(origin)
        // Untouched text: the origin instant verbatim, so a stored sub-second precision survives.
        assertEquals(origin.statisticsAt, transferCorrectionStatisticsInstant(origin, seeded, ParseManualExpenseOccurredAt(), clock))
        // A readable edit is parsed by the entry flow's own parser and clock.
        val edited = seeded.withUpdate(TransferCorrectionFieldUpdate.StatisticsAt("2026-04-01 08:30"))
        assertEquals(
            Instant.parse("2026-04-01T00:30:00Z"),
            transferCorrectionStatisticsInstant(origin, edited, ParseManualExpenseOccurredAt(), clock),
        )
        // An unreadable edit is absent, so the caller dispatches the typed field rejection instead
        // of committing a request whose time silently reverted to the stored instant.
        val unreadable = seeded.withUpdate(TransferCorrectionFieldUpdate.StatisticsAt("not-a-time"))
        assertNull(transferCorrectionStatisticsInstant(origin, unreadable, ParseManualExpenseOccurredAt(), clock))
    }

    private fun transferOrigin(): TransferCorrectionOrigin =
        TransferCorrectionOrigin(
            transactionId = transactionId,
            currentVersionId = versionId,
            note = null,
            statisticsAt = statisticsAt,
            currency = cny,
            sourceAccountId = AccountId("asset-source"),
            destinationAccountId = AccountId("asset-destination"),
            destinationCreditMinorUnits = 10_000L,
            feeMinorUnits = 200L,
            feeCategoryId = CategoryId("category-food-leaf"),
        )

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

    // ---- V-19 (D-173): the manual re-check uses the RETAINED snapshot, never the live draft ----

    /** One re-check resolve call, recorded so the crux (which snapshot was compared) is assertable. */
    private class ResolveProbe<T> {
        val attempted = mutableListOf<T>()
        val requestIds = mutableListOf<RequestId>()
    }

    private fun correctionRequest(draftNote: String): ExplicitlyConfirmedTransactionCorrection {
        val origin = assertNotNull(transactionEditOriginFromDetail(detail(), snapshot()))
        return assertNotNull(
            transactionCorrectionRequest(
                ledgerId = ledgerId,
                requestId = RequestId("request-1"),
                origin = origin,
                draft =
                    TransactionCorrectionDraft(
                        note = draftNote,
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
    }

    @Test
    fun theRetainedCorrectionSnapshotIsTheOneComparedAndNeverTheMutatedDraft() {
        // The committed request captured at confirm time.
        val committed = correctionRequest("committed note")
        val retained = committed.toRetainedRequest()
        assertEquals(RequestId("request-1"), retained.requestId)
        assertEquals("committed note", retained.snapshot.note)

        // The draft is then MUTATED (the surfaces keep their fields editable while submitting) and
        // would build a DIFFERENT request; the re-check must ignore it entirely and compare the
        // retained snapshot — otherwise the resolver would answer SnapshotConflict (a false one).
        val mutated = correctionRequest("mutated after confirm")
        assertTrue(committed.toRequestSnapshot() != mutated.toRequestSnapshot())

        val probe = ResolveProbe<TransactionCorrectionRequestSnapshot>()
        val event =
            correctionRecheckEvent(retained) { _, requestId, snapshot ->
                probe.attempted += snapshot
                probe.requestIds += requestId
                // The store compares against the committed snapshot: a hit.
                if (snapshot == committed.toRequestSnapshot()) {
                    TransactionCorrectionCommitResolution.MatchingReceipt(correctionReceipt())
                } else {
                    TransactionCorrectionCommitResolution.SnapshotConflict
                }
            }
        assertEquals(listOf(committed.toRequestSnapshot()), probe.attempted)
        assertEquals(listOf(RequestId("request-1")), probe.requestIds)
        // The hit lands as the EXISTING result event carrying NoChange (a determinate success).
        val landed = assertIs<P503UiEvent.TransactionEditResult>(event)
        assertIs<CorrectTransactionVersionResult.NoChange>(landed.result)
    }

    @Test
    fun theRetainedVoidSnapshotCarriesItsFactKindAndTheRestoreRecheckUsesTheRestoreFamily() {
        val request =
            VoidTransactionRequest(
                ledgerId = ledgerId,
                requestId = RequestId("request-1"),
                transactionId = transactionId,
                reason = VoidReason(VoidReasonCode.MIS_ENTERED),
                confirmation = ExplicitManualSave,
            )
        val voidRetained = assertNotNull(request.toRetainedRequest(TransactionVoidFactKind.VOID))
        assertEquals(TransactionVoidFactKind.VOID, voidRetained.snapshot.factKind)
        val restoreRetained = assertNotNull(request.toRetainedRequest(TransactionVoidFactKind.RESTORE))
        assertEquals(TransactionVoidFactKind.RESTORE, restoreRetained.snapshot.factKind)
        // The landing event family follows the RETAINED factKind, not a caller flag.
        assertIs<P503UiEvent.TransactionVoidResult>(
            voidRestoreRecheckEvent(voidRetained) { _, _, _ -> TransactionVoidCommitResolution.MatchingReceipt(voidReceipt()) },
        )
        assertIs<P503UiEvent.TransactionRestoreResult>(
            voidRestoreRecheckEvent(restoreRetained) { _, _, _ -> TransactionVoidCommitResolution.MatchingReceipt(voidReceipt()) },
        )
    }

    @Test
    fun anAbsentOrUnavailableRecheckProducesTheStillUnknownMarkerEvent() {
        val retained = correctionRequest("committed note").toRetainedRequest()
        val absent =
            assertIs<P503UiEvent.RetryP705CommitStatusCheck>(
                correctionRecheckEvent(retained) { _, _, _ -> TransactionCorrectionCommitResolution.Absent },
            )
        assertEquals(P705CommitCheckOutcome.ABSENT, absent.outcome)
        val unavailable =
            assertIs<P503UiEvent.RetryP705CommitStatusCheck>(
                correctionRecheckEvent(retained) { _, _, _ -> TransactionCorrectionCommitResolution.Unavailable },
            )
        assertEquals(P705CommitCheckOutcome.UNAVAILABLE, unavailable.outcome)
        // A snapshot conflict lands as the existing conflict result (never a fabricated notice).
        val conflict =
            assertIs<P503UiEvent.TransactionEditResult>(
                correctionRecheckEvent(retained) { _, _, _ -> TransactionCorrectionCommitResolution.SnapshotConflict },
            )
        assertIs<CorrectTransactionVersionResult.RequestIdentityConflict>(conflict.result)

        val voidRetained =
            assertNotNull(
                VoidTransactionRequest(ledgerId, RequestId("request-1"), transactionId, VoidReason(VoidReasonCode.OTHER), ExplicitManualSave)
                    .toRetainedRequest(TransactionVoidFactKind.VOID),
            )
        assertEquals(
            P705CommitCheckOutcome.ABSENT,
            assertIs<P503UiEvent.RetryP705CommitStatusCheck>(
                voidRestoreRecheckEvent(voidRetained) { _, _, _ -> TransactionVoidCommitResolution.Absent },
            ).outcome,
        )
    }

    @Test
    fun onlyADeterminateRecheckHitFiresTheRefreshChain() {
        val probe = RefreshProbe()
        val coordinator = coordinatorWithProbe(probe)
        // A hit (NoChange) refreshes exactly like a first-time success.
        refreshAfterP705Recheck(P503UiEvent.TransactionEditResult(CorrectTransactionVersionResult.NoChange(correctionReceipt())), coordinator)
        refreshAfterP705Recheck(P503UiEvent.TransactionVoidResult(VoidTransactionResult.NoChange(voidReceipt())), coordinator)
        refreshAfterP705Recheck(P503UiEvent.TransactionRestoreResult(VoidTransactionResult.NoChange(voidReceipt())), coordinator)
        assertEquals(3, probe.refreshes)
        // The conflict and the still-unknown marker landings refresh nothing.
        refreshAfterP705Recheck(
            P503UiEvent.TransactionEditResult(CorrectTransactionVersionResult.RequestIdentityConflict(TransactionCorrectionRequestIdentity(ledgerId, RequestId("request-1")))),
            coordinator,
        )
        refreshAfterP705Recheck(P503UiEvent.RetryP705CommitStatusCheck(P705CommitCheckOutcome.ABSENT), coordinator)
        assertEquals(3, probe.refreshes)
    }

    // ---- the retained-snapshot lifecycle (the landed-state gate, V-19/D-173) ----

    @Test
    fun theRetainedSnapshotIsKeptExactlyWhileTheSurfaceHoldsALostCommit() {
        val retained = correctionRequest("committed note").toRetainedRequest()
        val overview = overview()
        val origin = assertNotNull(transactionEditOriginFromDetail(detail(), snapshot()))
        // A surface submitting with no result keeps the snapshot (the lost-commit window).
        assertTrue(p705SurfaceHoldsLostCommit(P503AppState.TransactionEdit(overview, origin, submitting = true)))
        assertTrue(p705SurfaceHoldsLostCommit(P503AppState.TransferEdit(overview, transferOrigin(), submitting = true)))
        assertTrue(p705SurfaceHoldsLostCommit(P503AppState.VoidConfirm(overview, transactionId, submitting = true)))
        assertTrue(p705SurfaceHoldsLostCommit(P503AppState.RecycleBin(overview, RecycleBinResult.Success(emptyList()), RestoreConfirm(transactionId, submitting = true))))
        assertSame(retained, retainedP705RequestAfterLanding(retained, P503AppState.TransactionEdit(overview, origin, submitting = true)))
        assertSame(retained, retainedP705RequestAfterLanding(retained, P503AppState.TransferEdit(overview, transferOrigin(), submitting = true)))
        // Leaving, landing a result or any non-P7-05 state releases it.
        assertNull(retainedP705RequestAfterLanding(retained, overview))
        assertNull(retainedP705RequestAfterLanding(retained, P503AppState.TransactionEdit(overview, origin)))
        assertNull(retainedP705RequestAfterLanding(retained, P503AppState.TransferEdit(overview, transferOrigin())))
        assertNull(retainedP705RequestAfterLanding(retained, P503AppState.VoidConfirm(overview, transactionId)))
        assertNull(retainedP705RequestAfterLanding(retained, P503AppState.RecycleBin(overview, RecycleBinResult.Success(emptyList()))))
        // A landing that a surface REFUSES (the reducer absorbs it, e.g. a second bin row tapped
        // while a lost restore is in flight) must NOT release the still-needed snapshot.
        val stillLost = P503AppState.RecycleBin(overview, RecycleBinResult.Success(emptyList()), RestoreConfirm(transactionId, submitting = true))
        assertSame(retained, retainedP705RequestAfterLanding(retained, stillLost))
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
