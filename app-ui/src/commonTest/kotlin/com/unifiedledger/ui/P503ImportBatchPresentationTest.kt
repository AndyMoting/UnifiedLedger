package com.unifiedledger.ui

import com.unifiedledger.application.ConfirmImportCandidate
import com.unifiedledger.application.ImportCandidateCommitPort
import com.unifiedledger.application.ImportCandidateConfirmRequest
import com.unifiedledger.application.ImportCandidateDecisionResult
import com.unifiedledger.application.ImportCandidateFormalFactory
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCommitIds
import com.unifiedledger.application.ImportConfirmDecisionFields
import com.unifiedledger.application.ImportConfirmationId
import com.unifiedledger.application.ImportDuplicateStatus
import com.unifiedledger.application.ImportIdSource
import com.unifiedledger.application.ImportReceipt
import com.unifiedledger.application.ImportRequestId
import com.unifiedledger.application.ImportReviewRow
import com.unifiedledger.application.ImportReviewRowsResult
import com.unifiedledger.application.ImportStatusHistoryId
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.SpineDiagnostics
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P7-04.D pure presentation tests (D-146; spec sections 3.2.3/3.3.2/4.5.3/6.2): the six-variant
 * decision-fields mapping, the dispatch-time admission (派发前按最新行重校验： stale selections
 * typed-skip without a submit), the sequential dispatch loop (逐项原子、可见部分成功、Unknown 暂停
 * 后续派发)， the equivalent replay rebuild (同 requestId + 等价 snapshot) and the result-summary copy
 * (D04: 半提交不误报 — the summary is per-item and never claims a whole-batch 成功/失败). Fake
 * commit-port/execute pattern: no Compose, no IO.
 */
class P503ImportBatchPresentationTest {
    private val ledgerId = LedgerId("ledger-local-test")
    private val cny = CurrencyUnit("CNY", 2)
    private val parseAmount = ParseManualExpenseAmount()
    private val confirmedAt = "2026-09-14T08:00:00Z"

    // ---- fixtures ----

    private fun row(
        candidateId: String,
        candidateKind: String = "ordinary_flow",
        candidateStatus: String = "pending_confirmation",
        duplicateStatus: ImportDuplicateStatus? = null,
        contentHash: String = "sha256:fixed-$candidateId",
        paymentProfileVariant: String? = null,
    ): ImportReviewRow =
        ImportReviewRow(
            candidateId = ImportCandidateId(candidateId),
            candidateKind = candidateKind,
            sourceInputRef = "pick-handle-1",
            amountMinor = 3_580L,
            currencyCode = "CNY",
            currencyPrecision = 2,
            occurredAt = "2026-03-15T02:00:00Z",
            directionToken = "expense",
            statusToken = "trade_success",
            fundingState = com.unifiedledger.application.ImportFundingState.SETTLED,
            completeness = com.unifiedledger.application.ImportCompleteness.VALID_COMPLETE,
            contentHash = contentHash,
            candidateStatus = candidateStatus,
            requiresConfirmation = true,
            confidence = "high",
            duplicateStatus = duplicateStatus,
            paymentProfileVariant = paymentProfileVariant,
            paymentProfileAssetLegKindToken = null,
            paymentProfileCreditLegKindToken = null,
        )

    private val stubCatalog: LedgerCatalog =
        (LedgerCatalog.create(emptyList(), emptyList()) as DomainResult.Success).value

    private fun stubUseCase(): ConfirmImportCandidate =
        ConfirmImportCandidate(
            commitPort =
                object : ImportCandidateCommitPort {
                    override fun commitOnce(
                        identity: com.unifiedledger.application.ImportRequestIdentity,
                        snapshot: com.unifiedledger.application.ImportCandidateDecisionSnapshot,
                        allocateIds: () -> ImportCommitIds,
                        catalog: LedgerCatalog,
                        createFormalTransaction: (com.unifiedledger.application.ImportCandidateFormalizationInput, ImportCommitIds) -> DomainResult<com.unifiedledger.application.ImportFormalCommit>,
                    ): ImportCandidateDecisionResult = error("the stub use case is a token; the loop's execute callback runs")

                    override fun commitRejectOnce(
                        identity: com.unifiedledger.application.ImportRequestIdentity,
                        snapshot: com.unifiedledger.application.ImportCandidateDecisionSnapshot,
                        allocateStatusId: () -> ImportStatusHistoryId,
                    ): ImportCandidateDecisionResult = error("the stub use case is a token; the loop's execute callback runs")
                },
            idSource = ImportIdSource { error("unused") },
            createFormalTransaction = ImportCandidateFormalFactory { _, _ -> error("unused") },
            catalog = stubCatalog,
        )

    private val wiredUseCases =
        ImportConfirmUseCaseSet(
            ordinaryFlow = stubUseCase(),
            transferFlow = stubUseCase(),
            creditExpense = stubUseCase(),
            creditRepayment = stubUseCase(),
            mixedPayment = stubUseCase(),
        )

    private fun completeOrdinaryDraft() =
        ImportDecisionDraft(
            categoryId = CategoryId("category-food"),
            fundingAccountId = AccountId("asset-payment"),
        )

    private fun receipt(candidateId: String): ImportReceipt =
        ImportReceipt(
            requestId = ImportRequestId("request-$candidateId"),
            sourceId = null,
            evidenceId = null,
            candidateId = ImportCandidateId(candidateId),
            confirmationId = ImportConfirmationId("confirmation-$candidateId"),
            transactionId = TransactionId("tx-$candidateId"),
        )

    private fun batchItem(
        candidateId: String,
        requestId: String = "request-$candidateId",
    ) = ImportBatchItem(ImportCandidateId(candidateId), ImportRequestId(requestId))

    private fun itemState(
        candidateId: String,
        outcome: ImportBatchItemOutcome? = null,
    ) = ImportBatchSubmittingItem(batchItem(candidateId), outcome)

    // ---- importBatchDecisionFields (六变体映射 + 完整性) ----

    @Test
    fun theSixDecisionFieldVariantsMapFromTheRowKindAndDraft() {
        assertEquals(
            ImportConfirmDecisionFields.OrdinaryFlow(CategoryId("category-food"), AccountId("asset-payment")),
            importBatchDecisionFields(row("c-1"), completeOrdinaryDraft(), parseAmount, cny),
        )
        assertEquals(
            ImportConfirmDecisionFields.TransferFlow(AccountId("asset-from"), AccountId("asset-to")),
            importBatchDecisionFields(
                row("c-2", candidateKind = "transfer_flow"),
                ImportDecisionDraft(fromAccountId = AccountId("asset-from"), toAccountId = AccountId("asset-to")),
                parseAmount,
                cny,
            ),
        )
        assertEquals(
            ImportConfirmDecisionFields.CreditExpenseFlow(CategoryId("category-food"), AccountId("credit-1")),
            importBatchDecisionFields(
                row("c-3", candidateKind = "credit_expense", paymentProfileVariant = "credit_expense_direct"),
                ImportDecisionDraft(categoryId = CategoryId("category-food"), creditLiabilityAccountId = AccountId("credit-1")),
                parseAmount,
                cny,
            ),
        )
        assertEquals(
            ImportConfirmDecisionFields.CreditExpenseRefundFlow(CategoryId("category-food"), AccountId("credit-1"), TransactionId("tx-orig")),
            importBatchDecisionFields(
                row("c-4", candidateKind = "credit_expense", paymentProfileVariant = "credit_expense_refund"),
                ImportDecisionDraft(categoryId = CategoryId("category-food"), creditLiabilityAccountId = AccountId("credit-1"), originalTransactionId = TransactionId("tx-orig")),
                parseAmount,
                cny,
            ),
        )
        assertEquals(
            ImportConfirmDecisionFields.CreditRepaymentFlow(AccountId("asset-payment"), AccountId("credit-1")),
            importBatchDecisionFields(
                row("c-5", candidateKind = "credit_repayment"),
                ImportDecisionDraft(assetAccountId = AccountId("asset-payment"), creditLiabilityAccountId = AccountId("credit-1")),
                parseAmount,
                cny,
            ),
        )
        assertEquals(
            ImportConfirmDecisionFields.MixedPaymentFlow(CategoryId("category-food"), AccountId("asset-payment"), AccountId("credit-1"), 2_000L, 1_580L),
            importBatchDecisionFields(
                row("c-6", candidateKind = "mixed_payment"),
                ImportDecisionDraft(
                    categoryId = CategoryId("category-food"),
                    assetAccountId = AccountId("asset-payment"),
                    creditLiabilityAccountId = AccountId("credit-1"),
                    assetLegAmountText = "20.00",
                    creditLegAmountText = "15.80",
                    confirmedAtText = "2026-09-14T08:00:00Z",
                ),
                parseAmount,
                cny,
            ),
        )
    }

    @Test
    fun incompleteDecisionDataMapsToNullNeverFabricated() {
        // A missing required field.
        assertNull(importBatchDecisionFields(row("c-1"), ImportDecisionDraft(), parseAmount, cny))
        // Mixed: a blank leg is incomplete decision data (两腿金额可空： 留空 = 未补全).
        assertNull(
            importBatchDecisionFields(
                row("c-6", candidateKind = "mixed_payment"),
                ImportDecisionDraft(
                    categoryId = CategoryId("category-food"),
                    assetAccountId = AccountId("asset-payment"),
                    creditLiabilityAccountId = AccountId("credit-1"),
                    assetLegAmountText = "",
                    creditLegAmountText = "15.80",
                    confirmedAtText = "2026-09-14T08:00:00Z",
                ),
                parseAmount,
                cny,
            ),
        )
        // Mixed E13: a blank confirmation-time marker is incomplete (提交时取授权取样值， C 批表单注).
        assertNull(
            importBatchDecisionFields(
                row("c-6", candidateKind = "mixed_payment"),
                ImportDecisionDraft(
                    categoryId = CategoryId("category-food"),
                    assetAccountId = AccountId("asset-payment"),
                    creditLiabilityAccountId = AccountId("credit-1"),
                    assetLegAmountText = "20.00",
                    creditLegAmountText = "15.80",
                    confirmedAtText = "",
                ),
                parseAmount,
                cny,
            ),
        )
        // An unparsable leg text is incomplete.
        assertNull(
            importBatchDecisionFields(
                row("c-6", candidateKind = "mixed_payment"),
                ImportDecisionDraft(
                    categoryId = CategoryId("category-food"),
                    assetAccountId = AccountId("asset-payment"),
                    creditLiabilityAccountId = AccountId("credit-1"),
                    assetLegAmountText = "abc",
                    creditLegAmountText = "15.80",
                    confirmedAtText = "2026-09-14T08:00:00Z",
                ),
                parseAmount,
                cny,
            ),
        )
        // A kind without a decision form.
        assertNull(importBatchDecisionFields(row("c-7", candidateKind = "transfer_flow_missing_leg"), completeOrdinaryDraft(), parseAmount, cny))
    }

    // ---- admitImportBatchItem (派发前按最新行重校验) ----

    @Test
    fun anAdmissibleItemBuildsTheConfirmRequestFromTheLatestRowAndTheSingleSample() {
        val admission =
            admitImportBatchItem(
                item = batchItem("c-1", "request-batch-1"),
                rows = ImportReviewRowsResult.Rows(listOf(row("c-1"))),
                drafts = mapOf(ImportCandidateId("c-1") to completeOrdinaryDraft()),
                confirmedAt = confirmedAt,
                useCases = wiredUseCases,
                ledgerId = ledgerId,
                parseAmount = parseAmount,
                defaultCurrency = cny,
            )
        val submit = assertIs<ImportBatchItemAdmission.Submit>(admission)
        assertEquals(ledgerId, submit.request.identity.ledgerId)
        assertEquals(ImportRequestId("request-batch-1"), submit.request.identity.requestId)
        assertEquals("sha256:fixed-c-1", submit.request.expectedContentHash)
        // Q09.4: the authorization clock sample is the explicitConfirmedAt of EVERY kind.
        assertEquals(confirmedAt, submit.request.explicitConfirmedAt)
        assertEquals(wiredUseCases.ordinaryFlow, submit.useCase)
    }

    @Test
    fun staleOrUnconfirmableSelectionsAreTypedSkippedWithoutASubmit() {
        val rows =
            ImportReviewRowsResult.Rows(
                listOf(
                    row("c-1"),
                    row("c-suspected", duplicateStatus = ImportDuplicateStatus.DEFERRED),
                    row("c-resolved", candidateStatus = "confirmed"),
                    row("c-incomplete", candidateKind = "transfer_flow_missing_leg", candidateStatus = "incomplete"),
                ),
            )
        val drafts = mapOf(ImportCandidateId("c-1") to completeOrdinaryDraft())

        // A selected id the latest rows no longer carry (刷新后勾选集可能含已不在 rows 的 id).
        val missing = admitImportBatchItem(batchItem("c-gone"), rows, drafts, confirmedAt, wiredUseCases, ledgerId, parseAmount, cny)
        assertEquals(ImportBatchItemAdmission.Skip(IMPORT_BATCH_ITEM_NOT_CONFIRMABLE), missing)

        // 疑似重复未审 / 已确认 / 来源事实不完整 — the section 3.3.1 gate holds at dispatch time.
        assertEquals(
            ImportBatchItemAdmission.Skip(IMPORT_BATCH_ITEM_NOT_CONFIRMABLE),
            admitImportBatchItem(batchItem("c-suspected"), rows, drafts, confirmedAt, wiredUseCases, ledgerId, parseAmount, cny),
        )
        assertEquals(
            ImportBatchItemAdmission.Skip(IMPORT_BATCH_ITEM_NOT_CONFIRMABLE),
            admitImportBatchItem(batchItem("c-resolved"), rows, drafts, confirmedAt, wiredUseCases, ledgerId, parseAmount, cny),
        )
        assertEquals(
            ImportBatchItemAdmission.Skip(IMPORT_BATCH_ITEM_NOT_CONFIRMABLE),
            admitImportBatchItem(batchItem("c-incomplete"), rows, drafts, confirmedAt, wiredUseCases, ledgerId, parseAmount, cny),
        )

        // An unreadable list (rows re-read failed) typed-skips every item instead of guessing.
        assertEquals(
            ImportBatchItemAdmission.Skip(IMPORT_BATCH_REVALIDATION_UNAVAILABLE),
            admitImportBatchItem(batchItem("c-1"), ImportReviewRowsResult.Unavailable, drafts, confirmedAt, wiredUseCases, ledgerId, parseAmount, cny),
        )

        // An unwired confirm surface typed-skips (IMPORT_* namespace, never a SPINE_ code).
        assertEquals(
            ImportBatchItemAdmission.Skip(IMPORT_BATCH_CONFIRM_UNWIRED),
            admitImportBatchItem(batchItem("c-1"), rows, drafts, confirmedAt, null, ledgerId, parseAmount, cny),
        )

        // Incomplete decision data typed-skips the item (不提交).
        assertEquals(
            ImportBatchItemAdmission.Skip(IMPORT_BATCH_DECISION_INCOMPLETE),
            admitImportBatchItem(batchItem("c-1"), rows, emptyMap(), confirmedAt, wiredUseCases, ledgerId, parseAmount, cny),
        )
    }

    // ---- the sequential dispatch loop (逐项原子、可见部分成功、Unknown 暂停) ----

    @Test
    fun theLoopIsSequentialWithVisiblePartialSuccessAndNeverSubmitsSkippedItems() {
        val rows =
            ImportReviewRowsResult.Rows(
                listOf(
                    row("c-ok"),
                    row("c-rejected"),
                ),
            )
        val drafts =
            mapOf(
                ImportCandidateId("c-ok") to completeOrdinaryDraft(),
                ImportCandidateId("c-rejected") to completeOrdinaryDraft(),
                ImportCandidateId("c-stale") to completeOrdinaryDraft(),
            )
        val submitted = mutableListOf<ImportCandidateConfirmRequest>()
        val loop =
            ImportBatchDispatchLoop(
                items = listOf(batchItem("c-ok"), batchItem("c-rejected"), batchItem("c-stale")),
                rows = rows,
                drafts = drafts,
                confirmedAt = confirmedAt,
                useCases = wiredUseCases,
                ledgerId = ledgerId,
                parseAmount = parseAmount,
                defaultCurrency = cny,
            )
        val results = mutableListOf<ImportBatchDispatchResult>()
        val run =
            loop.run(
                dispatch = { results += it },
                execute = { _, request ->
                    submitted += request
                    if (request.candidateId.value == "c-rejected") {
                        ImportCandidateDecisionResult.Rejected(SpineDiagnostics.candidateNotPending(request.candidateId))
                    } else {
                        ImportCandidateDecisionResult.Accepted(receipt(request.candidateId.value), emptyList())
                    }
                },
            )
        assertEquals(ImportBatchDispatchRun.Completed, run)
        // 顺序循环 + 可见部分成功： the stale item is typed-skipped and NEVER submitted; the
        // rejection never stops the later items (they were all evaluated in order).
        assertEquals(listOf("c-ok", "c-rejected"), submitted.map { it.candidateId.value })
        assertEquals(listOf("c-ok", "c-rejected", "c-stale"), results.map { it.item.candidateId.value })
        assertIs<ImportBatchItemOutcome.Confirmed>(results[0].outcome)
        val rejected = assertIs<ImportBatchItemOutcome.Rejected>(results[1].outcome)
        assertEquals("SPINE_CANDIDATE_NOT_PENDING", rejected.code)
        val skipped = assertIs<ImportBatchItemOutcome.Skipped>(results[2].outcome)
        assertEquals(IMPORT_BATCH_ITEM_NOT_CONFIRMABLE, skipped.code)
    }

    @Test
    fun anUnknownOutcomePausesTheLoopAndTheRemainingItemsAreNotDispatched() {
        val rows = ImportReviewRowsResult.Rows(listOf(row("c-1"), row("c-2"), row("c-3")))
        val drafts =
            mapOf(
                ImportCandidateId("c-1") to completeOrdinaryDraft(),
                ImportCandidateId("c-2") to completeOrdinaryDraft(),
                ImportCandidateId("c-3") to completeOrdinaryDraft(),
            )
        var executions = 0
        val loop =
            ImportBatchDispatchLoop(
                items = listOf(batchItem("c-1"), batchItem("c-2"), batchItem("c-3")),
                rows = rows,
                drafts = drafts,
                confirmedAt = confirmedAt,
                useCases = wiredUseCases,
                ledgerId = ledgerId,
                parseAmount = parseAmount,
                defaultCurrency = cny,
            )
        val results = mutableListOf<ImportBatchDispatchResult>()
        val run =
            loop.run(
                dispatch = { results += it },
                execute = { _, _ ->
                    executions += 1
                    if (executions == 2) throw IllegalStateException("synthetic infra failure after handover")
                    ImportCandidateDecisionResult.Accepted(receipt("executed-$executions"), emptyList())
                },
            )
        val paused = assertIs<ImportBatchDispatchRun.PausedAtUnknown>(run)
        assertEquals(ImportCandidateId("c-2"), paused.unknownItem.candidateId)
        // The first item confirmed; the second handed over unknown; the third was never
        // attempted (暂停后续派发 — no result, its snapshot outcome stays null).
        assertEquals(2, executions)
        assertEquals(2, results.size)
        assertIs<ImportBatchItemOutcome.Confirmed>(results[0].outcome)
        assertIs<ImportBatchItemOutcome.Unknown>(results[1].outcome)
    }

    // ---- the equivalent replay check (Q10.2: 同 requestId + 等价 snapshot) ----

    @Test
    fun theReplayRebuildsTheIdenticalRequestAndMapsTheVerdicts() {
        val rows = ImportReviewRowsResult.Rows(listOf(row("c-1", candidateStatus = "confirmed")))
        val drafts = mapOf(ImportCandidateId("c-1") to completeOrdinaryDraft())
        val context =
            importUnknownCheckContext(
                batchItem("c-1", "request-batch-1"),
                rows,
                drafts,
                confirmedAt,
                wiredUseCases,
                ledgerId,
                parseAmount,
                cny,
            )
        val rebuilt = assertIs<ImportBatchCheckContext>(context)
        assertEquals(ImportRequestId("request-batch-1"), rebuilt.request.identity.requestId)
        assertEquals("sha256:fixed-c-1", rebuilt.request.expectedContentHash)
        assertEquals(confirmedAt, rebuilt.request.explicitConfirmedAt)
        assertEquals(wiredUseCases.ordinaryFlow, rebuilt.useCase)
        // The selectability gate is deliberately absent: a landed Unknown item reads
        // `confirmed` and still rebuilds (resolveConfirm returns the original receipt).

        // 原 receipt 判成功 (NoChange replay); a typed rejection 判冲突; a thrown replay 仍未知.
        val original = receipt("c-1")
        assertEquals(
            ImportUnknownCheckOutcome.Confirmed(original),
            runImportUnknownItemCheck(rebuilt) { _, _ -> ImportCandidateDecisionResult.NoChange(original, "SPINE_EQUIVALENT") },
        )
        assertEquals(
            ImportUnknownCheckOutcome.Confirmed(original),
            runImportUnknownItemCheck(rebuilt) { _, _ -> ImportCandidateDecisionResult.Accepted(original, emptyList()) },
        )
        assertEquals(
            ImportUnknownCheckOutcome.Conflict("SPINE_REQUEST_IDENTITY_CONFLICT"),
            runImportUnknownItemCheck(rebuilt) { _, _ ->
                ImportCandidateDecisionResult.Rejected(SpineDiagnostics.requestIdentityConflict(ImportRequestId("request-batch-1")))
            },
        )
        assertIs<ImportUnknownCheckOutcome.StillUnknown>(runImportUnknownItemCheck(rebuilt) { _, _ -> throw IllegalStateException("still failing") })
        // An unrebuildable context (unreadable rows) stays Unknown (仍未知， the entry stays).
        assertIs<ImportUnknownCheckOutcome.StillUnknown>(
            runImportUnknownItemCheck(
                importUnknownCheckContext(batchItem("c-gone"), ImportReviewRowsResult.Unavailable, drafts, confirmedAt, wiredUseCases, ledgerId, parseAmount, cny),
            ) { _, _ -> error("never executed") },
        )
    }

    @Test
    fun theCheckTargetResolvesFromTheSubmittingSnapshotAndTheOverviewSummary() {
        val submitting =
            P503AppState.ImportBatchSubmitting(
                overview = P503AppState.OverviewEmpty(com.unifiedledger.application.LedgerCurrentState(ledgerId, emptyList(), emptyList())),
                confirmedAt = confirmedAt,
                items = listOf(itemState("c-1", ImportBatchItemOutcome.Unknown), itemState("c-2")),
            )
        val (snapshotItem, sample) = resolveImportUnknownCheckTarget(submitting, ImportCandidateId("c-1"))!!
        assertEquals(ImportRequestId("request-c-1"), snapshotItem.requestId)
        assertEquals(confirmedAt, sample)
        assertNull(resolveImportUnknownCheckTarget(submitting, ImportCandidateId("c-2")))

        val summary =
            ImportBatchResultSummary(
                confirmedAt = confirmedAt,
                items = listOf(ImportBatchResultItem(batchItem("c-1"), ImportBatchItemOutcome.Unknown)),
            )
        val overview =
            P503AppState.OverviewEmpty(
                com.unifiedledger.application.LedgerCurrentState(ledgerId, emptyList(), emptyList()),
                P503Tab.IMPORT,
                importReview = ImportReviewView(batchResult = summary),
            )
        val (summaryItem, summarySample) = resolveImportUnknownCheckTarget(overview, ImportCandidateId("c-1"))!!
        assertEquals(ImportRequestId("request-c-1"), summaryItem.requestId)
        assertEquals(confirmedAt, summarySample)
        assertNull(resolveImportUnknownCheckTarget(overview, ImportCandidateId("c-none")))
    }

    // ---- the confirm-page enumeration ordering ----

    @Test
    fun theConfirmPageEnumeratesRowsOrderFirstThenTheSelectedButAbsentIds() {
        val view =
            ImportReviewView(
                rows = listOf(row("c-1"), row("c-2"), row("c-3")),
                selectedCandidateIds = setOf(ImportCandidateId("c-3"), ImportCandidateId("c-gone-b"), ImportCandidateId("c-1"), ImportCandidateId("c-gone-a")),
            )
        assertEquals(
            listOf("c-1", "c-3", "c-gone-a", "c-gone-b"),
            importBatchConfirmItems(view).map { it.candidateId.value },
        )
        assertEquals(
            listOf(
                ImportCandidateId("c-1"),
                ImportCandidateId("c-3"),
                ImportCandidateId("c-gone-a"),
                ImportCandidateId("c-gone-b"),
            ),
            importBatchSnapshotCandidateIds(view),
        )
        // The absent entries carry no row; the empty selection yields an empty page.
        assertNull(importBatchConfirmItems(view)[2].row)
        assertTrue(importBatchConfirmItems(ImportReviewView()).isEmpty())
        assertTrue(importBatchConfirmItems(null).isEmpty())
    }

    // ---- P704D-SPEC-01: the exit-affordance matrix of the dispatch state ----

    private fun submittingState(
        items: List<ImportBatchSubmittingItem>,
        paused: Boolean = false,
    ): P503AppState.ImportBatchSubmitting =
        P503AppState.ImportBatchSubmitting(
            overview = P503AppState.OverviewEmpty(com.unifiedledger.application.LedgerCurrentState(ledgerId, emptyList(), emptyList()), P503Tab.IMPORT),
            confirmedAt = confirmedAt,
            items = items,
            dispatchPaused = paused,
        )

    @Test
    fun theExitAffordancesFollowTheStoppedSubStates() {
        // An active run (undispatched items, not paused) renders no exits (沿既有 Submitting 语义).
        val active = submittingState(listOf(itemState("c-1"), itemState("c-2")))
        assertFalse(importBatchExitAvailable(active))
        // Paused (an Unknown landed): the exits render and Resume continues the remaining items.
        val paused = submittingState(listOf(itemState("c-1", ImportBatchItemOutcome.Unknown), itemState("c-2")), paused = true)
        assertTrue(importBatchExitAvailable(paused))
        assertEquals("继续派发剩余各项", importBatchResumeActionText(paused))
        assertTrue(importBatchExitBannerText(paused).contains("已暂停后续各项"))
        // P704D-SPEC-01: the residual stopped sub-state — the resumed run finished cleanly, no
        // item is undispatched, but an Unknown remains (its check came back 仍未知). The exits
        // MUST render here too, or a persistently failing check would strand the page (system
        // back is intercepted); Resume leaves carrying the Unknown into the retained summary.
        val residual =
            submittingState(
                listOf(
                    itemState("c-1", ImportBatchItemOutcome.Unknown),
                    itemState("c-2", ImportBatchItemOutcome.Skipped(IMPORT_BATCH_DECISION_INCOMPLETE)),
                ),
            )
        assertTrue(importBatchExitAvailable(residual))
        assertEquals("结束本次批量并查看结果", importBatchResumeActionText(residual))
        assertTrue(importBatchExitBannerText(residual).contains("结果未知"))
        // A fully terminal state never persists (the reducer auto-leaves on the last terminal
        // outcome): no exits.
        val terminal = submittingState(listOf(itemState("c-1", ImportBatchItemOutcome.Confirmed(receipt("c-1")))))
        assertFalse(importBatchExitAvailable(terminal))
    }

    // ---- P704D-QUAL-01/SPEC-02: the guarded pre-phase of one dispatch run ----

    @Test
    fun aThrowingPrePhaseLoaderYieldsTypedFailuresAndPerItemSkips() {
        val rows = ImportReviewRowsResult.Rows(emptyList())
        // Clean loaders pass both phases through unchanged.
        val ready = assertIs<ImportBatchDispatchPrePhase.Ready>(importBatchDispatchPrePhase({ rows }, { wiredUseCases }))
        assertEquals(rows, ready.rows)
        assertEquals(wiredUseCases, ready.useCases)
        // A throwing list read maps to the existing revalidation code.
        assertEquals(
            ImportBatchDispatchPrePhase.Failed(IMPORT_BATCH_REVALIDATION_UNAVAILABLE),
            importBatchDispatchPrePhase({ throw IllegalStateException("rows read failed") }, { wiredUseCases }),
        )
        // A throwing use-case factory maps to the run-level code.
        assertEquals(
            ImportBatchDispatchPrePhase.Failed(IMPORT_BATCH_DISPATCH_UNAVAILABLE),
            importBatchDispatchPrePhase({ rows }, { throw IllegalStateException("factory failed") }),
        )
        // The typed results cover every still-undispatched item exactly once.
        val items = listOf(batchItem("c-1"), batchItem("c-2"))
        val results = importBatchRunLevelFailureResults(items, IMPORT_BATCH_DISPATCH_UNAVAILABLE)
        assertEquals(items, results.map { it.item })
        results.forEach { result ->
            val skipped = assertIs<ImportBatchItemOutcome.Skipped>(result.outcome)
            assertEquals(IMPORT_BATCH_DISPATCH_UNAVAILABLE, skipped.code)
        }
    }

    // ---- P704D-SPEC-03 (Q09.4): the confirm page's confirmation-time note ----

    @Test
    fun theConfirmTimeNoteStatesTheSingleSampleSemantics() {
        // The recorded confirmation time is the ONE authorization-action clock sample (reused
        // by every item through explicitConfirmedAt); the mixed form's confirmation-time text is
        // only the E13 completeness marker, the recorded value is always the authorization sample.
        assertTrue(IMPORT_BATCH_CONFIRM_TIME_NOTE.contains("授权动作"))
        assertTrue(IMPORT_BATCH_CONFIRM_TIME_NOTE.contains("取一次"))
        assertTrue(IMPORT_BATCH_CONFIRM_TIME_NOTE.contains("仅用于补全决策校验"))
    }

    // ---- the result-summary copy (D04: 半提交不误报) ----

    @Test
    fun theResultSummaryPresentsPerItemOutcomesAndNeverClaimsAWholeBatchVerdict() {
        val summary =
            ImportBatchResultSummary(
                confirmedAt = confirmedAt,
                items =
                    listOf(
                        ImportBatchResultItem(batchItem("c-ok"), ImportBatchItemOutcome.Confirmed(receipt("c-ok"))),
                        ImportBatchResultItem(batchItem("c-rej"), ImportBatchItemOutcome.Rejected("SPINE_DUPLICATE_NOT_CONFIRMABLE")),
                        ImportBatchResultItem(batchItem("c-conflict"), ImportBatchItemOutcome.CheckConflict("SPINE_REQUEST_IDENTITY_CONFLICT")),
                        ImportBatchResultItem(batchItem("c-skip"), ImportBatchItemOutcome.Skipped(IMPORT_BATCH_DECISION_INCOMPLETE)),
                        ImportBatchResultItem(batchItem("c-unknown"), ImportBatchItemOutcome.Unknown),
                    ),
            )
        val lines = importBatchResultLines(summary)
        // The counts line discloses every bucket; per-item lines present the typed results.
        assertTrue(lines.any { it.contains("已入账 1 项") && it.contains("拒绝 1 项") && it.contains("核对冲突 1 项") && it.contains("跳过 1 项") && it.contains("未知 1 项") })
        assertTrue(lines.any { it.contains("c-ok") && it.contains("已入账") })
        assertTrue(lines.any { it.contains("c-rej") && it.contains("SPINE_DUPLICATE_NOT_CONFIRMABLE") })
        assertTrue(lines.any { it.contains("c-unknown") && it.contains("可核对") })
        // 半提交不误报: a half-completed batch is NEVER presented as 整批成功 or 整批失败.
        assertFalse(lines.any { it.contains("整批成功") || it.contains("全部成功") || it.contains("整批失败") || it.contains("全部失败") })
        // An abandoned batch with nothing submitted says so honestly.
        assertEquals(listOf("最近批量结果：本次批量没有已提交的项。"), importBatchResultLines(ImportBatchResultSummary(confirmedAt, emptyList())))
    }
}
