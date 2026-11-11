package com.unifiedledger.ui

import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.P408EvidenceProjection
import com.unifiedledger.application.P408EvidenceResponsibility
import com.unifiedledger.application.P408MatchBasis
import com.unifiedledger.application.P408MatchCandidate
import com.unifiedledger.application.P408MatchDisposition
import com.unifiedledger.application.P408MatchResult
import com.unifiedledger.application.P408PostingFacts
import com.unifiedledger.application.P408ProjectionState
import com.unifiedledger.application.P408ReconciliationStatus
import com.unifiedledger.application.P408TemporalEvidence
import com.unifiedledger.application.P801AccountReconciliationCursor
import com.unifiedledger.application.P801AccountReconciliationPageResult
import com.unifiedledger.application.P801AccountReconciliationQueuePage
import com.unifiedledger.application.P801AccountReconciliationQueueRow
import com.unifiedledger.application.P801AccountReconciliationReadFailure
import com.unifiedledger.application.P801AccountReconciliationStatusTotal
import com.unifiedledger.application.P801AccountReconciliationSummaryResult
import com.unifiedledger.application.P801BindEvidenceResult
import com.unifiedledger.application.P801BindableEvidenceResult
import com.unifiedledger.application.P801CandidateProposal
import com.unifiedledger.application.P801CandidateProposalFailure
import com.unifiedledger.application.P801CandidateProposalResult
import com.unifiedledger.application.P801ConfirmLinkRefusal
import com.unifiedledger.application.P801ConfirmLinkResult
import com.unifiedledger.application.P801ReconciledLegAudit
import com.unifiedledger.application.P801ReconciledLegAuditResult
import com.unifiedledger.application.P801ReconciliationCandidateEvidence
import com.unifiedledger.application.RecycleBinResult
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P8-01a i3a (D-308; spec sections 2.2/2.3/2.4/2.5/2.6) state-machine extension tests.
 *
 * Pins: the family is absorbed in every non-design state (table 6.2a — zero ISE anywhere); the
 * queue's two failure flags stay independent and a failure keeps the loaded rows (V-9); the
 * more-page read is single-flight and token-gated; the 已核对 partition is collapsed by default; the
 * queue's scroll slot is carried into the detail's return target and consumed once; every confirm
 * result branch (Linked / NoChange / Rejected / Refused / unknown) and every bind branch
 * (Bound / NoChange / Rejected / Refused / unknown) land on their own notice; and neither write can
 * be triggered without the explicit user step. Every pre-existing unlisted combination stays ISE.
 */
class P503ReconciliationReducerTest {
    private val ledgerId = LedgerId("ledger-reconciliation-test")
    private val cny = CurrencyUnit("CNY", 2)
    private val accountId = AccountId("asset-bank")
    private val otherAccountId = AccountId("asset-other")
    private val occurredAt = Instant.parse("2026-03-15T02:00:00Z")
    private val fixedClock = com.unifiedledger.application.LedgerClock { Instant.parse("2026-09-15T02:00:00Z") }
    private val reducer = P503ReducerImpl(com.unifiedledger.application.ParseManualExpenseAmount(), cny, ledgerClock = fixedClock)

    private val currentState =
        LedgerCurrentState(
            ledgerId = ledgerId,
            transactions = emptyList(),
            balances = emptyList(),
        )

    private fun overview(): P503AppState.OverviewEmpty = P503AppState.OverviewEmpty(state = currentState, selectedTab = P503Tab.ACCOUNTS)

    private fun accountFlow(): P503AppState.AccountFlow =
        P503AppState.AccountFlow(
            overview = overview(),
            accountId = accountId,
            balanceLine = "招商储蓄卡 · 资产 · CNY",
            balanceMinorUnits = -3_580L,
            balanceCurrency = cny,
            loadedRows = emptyList(),
            nextWholeLedgerCursor = null,
        )

    private fun queueRow(
        postingId: String = "posting-1",
        status: P408ReconciliationStatus = P408ReconciliationStatus.PENDING,
        hasActiveEvidenceLink: Boolean = false,
    ): P801AccountReconciliationQueueRow =
        P801AccountReconciliationQueueRow(
            postingId = PostingId(postingId),
            transactionId = TransactionId("tx-$postingId"),
            occurredAt = occurredAt,
            note = "转账",
            accountId = accountId,
            amountMinor = -10_000L,
            currency = cny,
            status = status,
            hasActiveEvidenceLink = hasActiveEvidenceLink,
            transactionKind = TransactionKind.ACCOUNT_TRANSFER,
        )

    private fun total(
        status: P408ReconciliationStatus,
        totalMinorUnits: Long,
        rowCount: Int = 1,
        currency: CurrencyUnit = cny,
    ): P801AccountReconciliationStatusTotal = P801AccountReconciliationStatusTotal(currency, status, totalMinorUnits, rowCount)

    private fun page(
        vararg rows: P801AccountReconciliationQueueRow,
        cursor: P801AccountReconciliationCursor? = null,
    ): P801AccountReconciliationPageResult = P801AccountReconciliationPageResult.Success(P801AccountReconciliationQueuePage(rows.toList(), cursor))

    private fun summary(vararg totals: P801AccountReconciliationStatusTotal): P801AccountReconciliationSummaryResult = P801AccountReconciliationSummaryResult.Success(totals.toList())

    private val failedPage: P801AccountReconciliationPageResult = P801AccountReconciliationPageResult.Failed(P801AccountReconciliationReadFailure.Unavailable)
    private val failedSummary: P801AccountReconciliationSummaryResult = P801AccountReconciliationSummaryResult.Failed(P801AccountReconciliationReadFailure.Unavailable)

    private fun cursor(): P801AccountReconciliationCursor = P801AccountReconciliationCursor("2026-03-15T02:00:00Z", PostingId("posting-1"))

    private fun queue(
        rows: List<P801AccountReconciliationQueueRow> = listOf(queueRow()),
        nextCursor: P801AccountReconciliationCursor? = null,
        totals: List<P801AccountReconciliationStatusTotal> = listOf(total(P408ReconciliationStatus.PENDING, -10_000L)),
        rowsLoadFailed: Boolean = false,
        summaryLoadFailed: Boolean = false,
    ): P503AppState.ReconciliationQueue =
        P503AppState.ReconciliationQueue(
            returnTo = accountFlow(),
            accountId = accountId,
            accountLine = "招商储蓄卡 · 资产 · CNY",
            rows = rows,
            nextCursor = nextCursor,
            totals = totals,
            rowsLoadFailed = rowsLoadFailed,
            summaryLoadFailed = summaryLoadFailed,
        )

    private val temporal =
        P408TemporalEvidence(
            rawText = "2026-03-15T02:00:00Z",
            kind = "offset_datetime",
            offsetPresent = true,
            components = com.unifiedledger.application.P408TemporalComponents(2026, 3, 15, 2, 0, 0),
            instant = occurredAt,
        )

    private fun postingFacts(
        postingId: String = "posting-1",
        direction: String = "out",
        eligibleRealAccount: Boolean = true,
    ): P408PostingFacts =
        P408PostingFacts(
            ledgerId = ledgerId.value,
            postingId = postingId,
            transactionId = "tx-$postingId",
            transactionLedgerId = ledgerId.value,
            amountMinor = if (direction == "out") -10_000L else 10_000L,
            currencyCode = "CNY",
            currencyPrecision = 2,
            direction = direction,
            accountId = accountId.value,
            occurredAt = temporal,
            eligibleRealAccount = eligibleRealAccount,
            current = true,
        )

    private fun projection(): P408EvidenceProjection =
        P408EvidenceProjection(
            ledgerId = ledgerId.value,
            projectionId = "projection-1",
            evidenceId = "evidence-1",
            sourceId = "source-1",
            sourceHash = "sha256:source-1",
            targetAccountId = accountId.value,
            currencyCode = "CNY",
            currencyPrecision = 2,
            rawAmountMinor = 10_000L,
            rawCurrencyPrecision = 2,
            normalizedAmountMinor = 10_000L,
            directionToken = "out",
            state = P408ProjectionState.READY,
            rejectionCode = null,
            ruleId = "p408_evidence_projection_v1",
            ruleVersion = 1,
            materializationRequestId = "materialize-1",
            materializedAt = "2026-03-16T00:00:00Z",
        )

    private fun evidence(
        evidenceId: String = "evidence-1",
        projection: P408EvidenceProjection? = projection(),
        hasActiveEvidenceLink: Boolean = false,
    ): P801ReconciliationCandidateEvidence =
        P801ReconciliationCandidateEvidence(
            evidenceId = evidenceId,
            sourceId = "source-1",
            candidateId = "candidate-1",
            contentHash = "sha256:source-1",
            rawAmountMinor = 10_000L,
            rawCurrencyCode = "CNY",
            rawCurrencyPrecision = 2,
            rawDirectionToken = "out",
            occurredAt = temporal,
            projection = projection,
            hasActiveEvidenceLink = hasActiveEvidenceLink,
        )

    private fun candidate(postingId: String = "posting-1"): P408MatchCandidate =
        P408MatchCandidate(
            posting = postingFacts(postingId),
            basis = P408MatchBasis(setOf("amount", "currency", "direction", "account", "occurred_at_window"), 1L, 2),
        )

    private fun proposal(
        disposition: P408MatchDisposition = P408MatchDisposition.PROPOSED_MATCH,
        candidates: List<P408MatchCandidate> = listOf(candidate()),
        reason: String? = null,
    ): P801CandidateProposal =
        P801CandidateProposal(
            evidence = evidence(),
            match = P408MatchResult("evidence-1", disposition, candidates, reason),
        )

    private fun proposals(vararg proposal: P801CandidateProposal): P801CandidateProposalResult = P801CandidateProposalResult.Success(proposal.toList())

    private fun audit(activeResponsibility: P408EvidenceResponsibility? = null): P801ReconciledLegAuditResult =
        P801ReconciledLegAuditResult.Success(
            P801ReconciledLegAudit(
                postingId = PostingId("posting-1"),
                transactionId = TransactionId("tx-posting-1"),
                reconciliation =
                    com.unifiedledger.application.P801ReconciledLegReconciliation(
                        reconciliationId = "reconciliation-1",
                        status = P408ReconciliationStatus.PENDING,
                        history = emptyList(),
                    ),
                evidence =
                    if (activeResponsibility == null) {
                        emptyList()
                    } else {
                        listOf(
                            com.unifiedledger.application.P801ReconciledLegEvidenceLink(
                                linkId = "link-1",
                                evidenceId = "evidence-0",
                                candidateId = "candidate-0",
                                requestId = "request-0",
                                responsibility = activeResponsibility,
                                createdAt = "2026-03-16T00:00:00Z",
                                state = "active",
                                stateSequence = 1L,
                                decision = null,
                                source =
                                    com.unifiedledger.application.P801ReconciledEvidenceSource(
                                        evidenceId = "evidence-0",
                                        sourceId = "source-0",
                                        importInputRef = "pick-1",
                                        recordOrdinal = 0L,
                                        recordKind = "transfer_flow_source",
                                        occurredAt = "2026-03-15T02:00:00Z",
                                    ),
                                history = emptyList(),
                            ),
                        )
                    },
            ),
        )

    private fun detail(
        selected: P503AppState.ReconciliationSelection? = null,
        proposals: P801CandidateProposalResult? = proposals(proposal()),
        audit: P801ReconciledLegAuditResult = audit(),
        anchorEvidenceId: String? = null,
        detailAccountId: AccountId? = this.accountId,
        bindTarget: AccountId? = this.accountId,
        bindAccounts: List<P503AppState.ReconciliationAccountChoice> = emptyList(),
        notice: P503AppState.ReconciliationDetailNotice? = null,
        anchorLeg: P801AccountReconciliationQueueRow? = queueRow(),
        bindableEvidence: P801BindableEvidenceResult? = null,
        bindEvidenceId: String? = null,
        anchorEvidence: P801ReconciliationCandidateEvidence? = null,
    ): P503AppState.ReconciliationDetail =
        P503AppState.ReconciliationDetail(
            returnTo = ReconciliationBackTarget.Queue(queue()),
            anchorPostingId = if (anchorEvidenceId == null) PostingId("posting-1") else null,
            anchorEvidenceId = anchorEvidenceId,
            accountId = detailAccountId,
            bindTarget = bindTarget,
            bindAccounts = bindAccounts,
            proposals = proposals,
            audit = audit,
            selectedCandidate = selected,
            notice = notice,
            anchorLeg = anchorLeg,
            bindableEvidence = bindableEvidence,
            bindEvidenceId = bindEvidenceId,
            anchorEvidence = anchorEvidence,
        )

    private fun selectQueue(
        firstPage: P801AccountReconciliationPageResult = page(queueRow(), cursor = cursor()),
        summary: P801AccountReconciliationSummaryResult = summary(total(P408ReconciliationStatus.PENDING, -10_000L)),
        originScroll: ListScrollPosition? = null,
    ): P503UiEvent.SelectReconciliationQueue =
        P503UiEvent.SelectReconciliationQueue(
            accountId = accountId,
            accountLine = "招商储蓄卡 · 资产 · CNY",
            firstPage = firstPage,
            summary = summary,
            originScroll = originScroll,
        )

    private fun enterQueue(): P503AppState.ReconciliationQueue = assertIs<P503AppState.ReconciliationQueue>(reducer.reduce(accountFlow(), selectQueue()))

    private fun enterDetail(): P503AppState.ReconciliationDetail =
        assertIs<P503AppState.ReconciliationDetail>(
            reducer.reduce(
                enterQueue(),
                P503UiEvent.OpenReconciliationLeg(
                    leg = queueRow(),
                    proposals = proposals(proposal()),
                    audit = audit(),
                    queueScroll = ListScrollPosition(0, 42),
                ),
            ),
        )

    // ---- entry A: SelectReconciliationQueue ----

    @Test
    fun selectReconciliationQueueEntersWithBothPayloads() {
        val entered = enterQueue()
        assertEquals(accountId, entered.accountId)
        assertEquals("招商储蓄卡 · 资产 · CNY", entered.accountLine)
        assertEquals(1, entered.rows.size)
        assertEquals(1, entered.totals.size)
        assertTrue(!entered.rowsLoadFailed)
        assertTrue(!entered.summaryLoadFailed)
        assertTrue(!entered.checkedExpanded)
        assertEquals(accountFlow().accountId, (entered.returnTo as P503AppState.AccountFlow).accountId)
    }

    @Test
    fun theTwoFailureFlagsAreIndependentAndNeverEraseTheOtherPayload() {
        // A failed page read keeps the computed totals (V-9: the aggregate is its own read).
        val rowsFailed = assertIs<P503AppState.ReconciliationQueue>(reducer.reduce(accountFlow(), selectQueue(firstPage = failedPage)))
        assertTrue(rowsFailed.rowsLoadFailed)
        assertTrue(!rowsFailed.summaryLoadFailed)
        assertEquals(1, rowsFailed.totals.size)
        assertEquals(emptyList(), rowsFailed.rows)
        assertNull(rowsFailed.nextCursor)
        // A failed summary keeps the loaded rows and its own flag only (never a zero total).
        val summaryFailed = assertIs<P503AppState.ReconciliationQueue>(reducer.reduce(accountFlow(), selectQueue(summary = failedSummary)))
        assertTrue(summaryFailed.summaryLoadFailed)
        assertTrue(!summaryFailed.rowsLoadFailed)
        assertEquals(1, summaryFailed.rows.size)
        assertEquals(emptyList(), summaryFailed.totals)
        // Both failed: still two flags, no row and no total invented.
        val both = assertIs<P503AppState.ReconciliationQueue>(reducer.reduce(accountFlow(), selectQueue(firstPage = failedPage, summary = failedSummary)))
        assertTrue(both.rowsLoadFailed)
        assertTrue(both.summaryLoadFailed)
        assertEquals(emptyList(), both.rows)
        assertEquals(emptyList(), both.totals)
    }

    @Test
    fun selectReconciliationQueueIsAbsorbedInEveryOtherState() {
        everyOtherState(exclude = { state -> state is P503AppState.AccountFlow }) { state ->
            assertSame(state, reducer.reduce(state, selectQueue()))
        }
        // The queue itself cannot re-open the queue (only AccountFlow owns the entry).
        val queueState = enterQueue()
        assertSame(queueState, reducer.reduce(queueState, selectQueue()))
        val detailState = enterDetail()
        assertSame(detailState, reducer.reduce(detailState, selectQueue()))
    }

    // ---- paging: single flight + token gate + failure keeps rows ----

    @Test
    fun requestMorePageAdvancesTheTokenOnceAndIsAbsorbedWhileLoading() {
        val withCursor = assertIs<P503AppState.ReconciliationQueue>(reducer.reduce(accountFlow(), selectQueue(page(queueRow(), cursor = cursor()))))
        val requested = assertIs<P503AppState.ReconciliationQueue>(reducer.reduce(withCursor, P503UiEvent.RequestReconciliationQueueMorePage))
        assertTrue(requested.rowsLoading)
        assertEquals(1L, requested.pageRequestToken)
        // 一次一页: a second request while the read is in flight changes nothing (the token stays).
        val again = assertIs<P503AppState.ReconciliationQueue>(reducer.reduce(requested, P503UiEvent.RequestReconciliationQueueMorePage))
        assertEquals(1L, again.pageRequestToken)
        assertTrue(again.rowsLoading)
    }

    @Test
    fun requestMorePageIsAbsorbedWhenTheKeysetEndedWithoutAFailure() {
        val ended = assertIs<P503AppState.ReconciliationQueue>(reducer.reduce(accountFlow(), selectQueue(page(queueRow()))))
        assertNull(ended.nextCursor)
        val after = assertIs<P503AppState.ReconciliationQueue>(reducer.reduce(ended, P503UiEvent.RequestReconciliationQueueMorePage))
        assertTrue(!after.rowsLoading)
        assertEquals(0L, after.pageRequestToken)
    }

    @Test
    fun requestMorePageRetriesAfterAFailedRead() {
        val failed = assertIs<P503AppState.ReconciliationQueue>(reducer.reduce(accountFlow(), selectQueue(firstPage = failedPage)))
        val retried = assertIs<P503AppState.ReconciliationQueue>(reducer.reduce(failed, P503UiEvent.RequestReconciliationQueueMorePage))
        // A failed read holds no cursor, so the retry must be allowed to re-run the first page (V-9).
        assertTrue(retried.rowsLoading)
        assertEquals(1L, retried.pageRequestToken)
    }

    @Test
    fun morePageLandedAppliesOnlyUnderTheCurrentToken() {
        val withCursor = assertIs<P503AppState.ReconciliationQueue>(reducer.reduce(accountFlow(), selectQueue(page(queueRow(), cursor = cursor()))))
        val requested = assertIs<P503AppState.ReconciliationQueue>(reducer.reduce(withCursor, P503UiEvent.RequestReconciliationQueueMorePage))
        val stale = queueRow(postingId = "posting-stale")
        val superseded =
            assertIs<P503AppState.ReconciliationQueue>(
                reducer.reduce(requested, P503UiEvent.ReconciliationQueuePageLanded(99L, page(stale))),
            )
        assertSame(requested, superseded)
        val landed =
            assertIs<P503AppState.ReconciliationQueue>(
                reducer.reduce(requested, P503UiEvent.ReconciliationQueuePageLanded(1L, page(queueRow(postingId = "posting-2")))),
            )
        assertTrue(!landed.rowsLoading)
        assertEquals(2, landed.rows.size)
        assertNull(landed.nextCursor)
    }

    @Test
    fun morePageFailureKeepsTheLoadedRowsAndOnlySetsTheRowsFlag() {
        val withCursor = assertIs<P503AppState.ReconciliationQueue>(reducer.reduce(accountFlow(), selectQueue(page(queueRow(), cursor = cursor()))))
        val requested = assertIs<P503AppState.ReconciliationQueue>(reducer.reduce(withCursor, P503UiEvent.RequestReconciliationQueueMorePage))
        val failed =
            assertIs<P503AppState.ReconciliationQueue>(
                reducer.reduce(requested, P503UiEvent.ReconciliationQueuePageLanded(1L, failedPage)),
            )
        assertEquals(1, failed.rows.size)
        assertTrue(failed.rowsLoadFailed)
        assertTrue(!failed.summaryLoadFailed)
        assertEquals(1, failed.totals.size)
        assertTrue(!failed.rowsLoading)
    }

    // ---- partition, scroll slot, back targets ----

    @Test
    fun theCheckedPartitionIsCollapsedByDefaultAndToggles() {
        val mixed =
            assertIs<P503AppState.ReconciliationQueue>(
                reducer.reduce(
                    accountFlow(),
                    selectQueue(page(queueRow(), queueRow(postingId = "posting-2", status = P408ReconciliationStatus.CHECKED))),
                ),
            )
        assertTrue(!mixed.checkedExpanded)
        assertEquals(1, mixed.uncheckedRows.size)
        assertEquals(1, mixed.checkedRows.size)
        val expanded = assertIs<P503AppState.ReconciliationQueue>(reducer.reduce(mixed, P503UiEvent.ToggleReconciliationCheckedPartition))
        assertTrue(expanded.checkedExpanded)
        val collapsed = assertIs<P503AppState.ReconciliationQueue>(reducer.reduce(expanded, P503UiEvent.ToggleReconciliationCheckedPartition))
        assertTrue(!collapsed.checkedExpanded)
    }

    @Test
    fun closeAndBackReturnToTheCarriedSurfaces() {
        val entered = enterQueue()
        assertEquals(P503AppState.AccountFlow::class, (reducer.reduce(entered, P503UiEvent.Back))::class)
        val detail = enterDetail()
        assertEquals(P503AppState.ReconciliationQueue::class, (reducer.reduce(detail, P503UiEvent.Back))::class)
    }

    @Test
    fun openingALegCarriesTheSampledQueueScrollIntoTheReturnTarget() {
        val queueState = enterQueue()
        val detail =
            assertIs<P503AppState.ReconciliationDetail>(
                reducer.reduce(
                    queueState,
                    P503UiEvent.OpenReconciliationLeg(queueRow(), proposals(proposal()), audit(), queueScroll = ListScrollPosition(0, 42)),
                ),
            )
        assertEquals(ListScrollPosition(0, 42), (detail.returnTo as ReconciliationBackTarget.Queue).state.listScroll)
        assertEquals(PostingId("posting-1"), detail.anchorPostingId)
        assertEquals(accountId, detail.bindTarget)
        assertNull(detail.selectedCandidate)
        // The restore slot is consumed exactly once, on the queue that carries it.
        val restored =
            assertIs<P503AppState.ReconciliationQueue>(
                reducer.reduce((detail.returnTo as ReconciliationBackTarget.Queue).state, P503UiEvent.ListScrollRestored),
            )
        assertNull(restored.listScroll)
        assertSame(restored, reducer.reduce(restored, P503UiEvent.ListScrollRestored))
    }

    @Test
    fun openingALegClearsAnInFlightPageReadSoTheQueueCannotStickLoading() {
        val withCursor = assertIs<P503AppState.ReconciliationQueue>(reducer.reduce(accountFlow(), selectQueue(page(queueRow(), cursor = cursor()))))
        val loading = assertIs<P503AppState.ReconciliationQueue>(reducer.reduce(withCursor, P503UiEvent.RequestReconciliationQueueMorePage))
        assertTrue(loading.rowsLoading)
        val detail =
            assertIs<P503AppState.ReconciliationDetail>(
                reducer.reduce(loading, P503UiEvent.OpenReconciliationLeg(queueRow(), proposals(proposal()), audit())),
            )
        // F12c: the detail absorbs the landing, so the carried queue must not keep the marker (or
        // the footer would say 「正在加载更多…」 forever and absorb every later load-more).
        assertTrue(!(detail.returnTo as ReconciliationBackTarget.Queue).state.rowsLoading)
        val back = assertIs<P503AppState.ReconciliationQueue>(reducer.reduce(detail, P503UiEvent.Back))
        assertTrue(!back.rowsLoading)
    }

    @Test
    fun backFromTheQueueLandsOnTheAccountFlowWithTheSampledPositionConsumedOnce() {
        // F6 / V-10: 账户流水 → 队列 → 返回 restores the flow's scroll position.
        val sampled = ListScrollPosition(0, 240)
        val queueState = assertIs<P503AppState.ReconciliationQueue>(reducer.reduce(accountFlow(), selectQueue(originScroll = sampled)))
        assertEquals(sampled, queueState.returnTo.listScroll)
        val back = assertIs<P503AppState.AccountFlow>(reducer.reduce(queueState, P503UiEvent.Back))
        assertEquals(sampled, back.listScroll)
        val consumed = assertIs<P503AppState.AccountFlow>(reducer.reduce(back, P503UiEvent.ListScrollRestored))
        assertNull(consumed.listScroll)
        assertSame(consumed, reducer.reduce(consumed, P503UiEvent.ListScrollRestored))
    }

    @Test
    fun theScrollSlotIsAbsorbedOnEveryOtherSurface() {
        // OverviewEmpty still consumes its own slot through the same event (unchanged behaviour).
        val withScroll = overview().copy(originListScroll = ListScrollPosition(3, 24))
        val cleared = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(withScroll, P503UiEvent.ListScrollRestored))
        assertNull(cleared.originListScroll)
        everyOtherState(
            exclude = { state ->
                state is P503AppState.OverviewEmpty || state is P503AppState.AccountFlow || state is P503AppState.ReconciliationQueue
            },
        ) { state -> assertSame(state, reducer.reduce(state, P503UiEvent.ListScrollRestored)) }
    }

    // ---- entry B ----

    @Test
    fun openEvidenceEntersTheDetailWithoutAnImpliedAccount() {
        val unprojected = evidence(projection = null)
        val entered =
            assertIs<P503AppState.ReconciliationDetail>(
                reducer.reduce(
                    overview(),
                    P503UiEvent.OpenReconciliationEvidence(
                        evidenceId = "evidence-1",
                        proposals = P801CandidateProposalResult.Failed(P801CandidateProposalFailure.EVIDENCE_NOT_PROJECTED),
                        bindAccounts = listOf(P503AppState.ReconciliationAccountChoice(accountId, "招商储蓄卡")),
                        evidence = unprojected,
                    ),
                ),
            )
        assertEquals("evidence-1", entered.anchorEvidenceId)
        assertNull(entered.anchorPostingId)
        assertNull(entered.accountId)
        assertNull(entered.bindTarget)
        // F1/F11: the unprojected evidence row itself makes the bind step reachable.
        assertEquals(unprojected, entered.anchorEvidence)
        assertTrue(reconciliationBindStepVisible(entered))
        assertEquals(1, entered.bindAccounts.size)
        assertEquals(P503AppState.OverviewEmpty::class, (reducer.reduce(entered, P503UiEvent.Back))::class)
    }

    @Test
    fun openEvidenceIsAbsorbedInEveryOtherState() {
        val event =
            P503UiEvent.OpenReconciliationEvidence(
                evidenceId = "evidence-1",
                proposals = P801CandidateProposalResult.Failed(P801CandidateProposalFailure.EVIDENCE_NOT_PROJECTED),
                bindAccounts = emptyList(),
            )
        everyOtherState(
            exclude = { state -> state is P503AppState.OverviewEmpty },
        ) { state -> assertSame(state, reducer.reduce(state, event)) }
        val detailState = enterDetail()
        assertSame(detailState, reducer.reduce(detailState, event))
    }

    // ---- bind ----

    @Test
    fun bindRequiresAnEvidenceAnchorAndADeclaredTarget() {
        val legAnchored = enterDetail()
        val absorbed = assertIs<P503AppState.ReconciliationDetail>(reducer.reduce(legAnchored, P503UiEvent.RequestBindReconciliationEvidence))
        assertTrue(!absorbed.binding)
        // No declared target (entry B before the pick): also absorbed.
        val evidenceAnchored =
            assertIs<P503AppState.ReconciliationDetail>(
                reducer.reduce(
                    overview(),
                    P503UiEvent.OpenReconciliationEvidence("evidence-1", P801CandidateProposalResult.Failed(P801CandidateProposalFailure.EVIDENCE_NOT_PROJECTED), emptyList()),
                ),
            )
        val noTarget = assertIs<P503AppState.ReconciliationDetail>(reducer.reduce(evidenceAnchored, P503UiEvent.RequestBindReconciliationEvidence))
        assertTrue(!noTarget.binding)
        // With a pick the request marks the in-flight state, and a duplicate request is absorbed.
        val picked = assertIs<P503AppState.ReconciliationDetail>(reducer.reduce(evidenceAnchored, P503UiEvent.SelectReconciliationBindTarget(accountId)))
        val binding = assertIs<P503AppState.ReconciliationDetail>(reducer.reduce(picked, P503UiEvent.RequestBindReconciliationEvidence))
        assertTrue(binding.binding)
        val again = assertIs<P503AppState.ReconciliationDetail>(reducer.reduce(binding, P503UiEvent.RequestBindReconciliationEvidence))
        assertTrue(again.binding)
    }

    @Test
    fun eachBindBranchLandsOnItsOwnNotice() {
        val evidenceAnchored =
            assertIs<P503AppState.ReconciliationDetail>(
                reducer.reduce(
                    overview(),
                    P503UiEvent.OpenReconciliationEvidence("evidence-1", P801CandidateProposalResult.Failed(P801CandidateProposalFailure.EVIDENCE_NOT_PROJECTED), listOf(P503AppState.ReconciliationAccountChoice(otherAccountId, "其他账户"))),
                ),
            )
        val picked = assertIs<P503AppState.ReconciliationDetail>(reducer.reduce(evidenceAnchored, P503UiEvent.SelectReconciliationBindTarget(accountId)))
        val binding = assertIs<P503AppState.ReconciliationDetail>(reducer.reduce(picked, P503UiEvent.RequestBindReconciliationEvidence))

        val bound =
            assertIs<P503AppState.ReconciliationDetail>(
                reducer.reduce(binding, P503UiEvent.ReconciliationBindLanded(P801BindEvidenceResult.Bound(projection()), proposals(proposal()))),
            )
        assertIs<P503AppState.ReconciliationDetailNotice.Bound>(bound.notice)
        assertTrue(!bound.binding)
        assertEquals(accountId, bound.bindTarget)
        assertEquals(accountId, bound.accountId)
        assertTrue(!reconciliationBindStepVisible(bound))
        assertTrue(bound.proposals is P801CandidateProposalResult.Success)

        val noChange =
            assertIs<P503AppState.ReconciliationDetail>(
                reducer.reduce(binding, P503UiEvent.ReconciliationBindLanded(P801BindEvidenceResult.NoChange(projection()))),
            )
        assertSame(P503AppState.ReconciliationDetailNotice.BindNoChange, noChange.notice)

        val rejected =
            assertIs<P503AppState.ReconciliationDetail>(
                reducer.reduce(binding, P503UiEvent.ReconciliationBindLanded(P801BindEvidenceResult.Rejected("P408_PROJECTION_STATE_CONFLICT"))),
            )
        assertEquals(
            P503AppState.ReconciliationDetailNotice.BindRejected("P408_PROJECTION_STATE_CONFLICT"),
            rejected.notice,
        )

        val refused =
            assertIs<P503AppState.ReconciliationDetail>(
                reducer.reduce(binding, P503UiEvent.ReconciliationBindLanded(P801BindEvidenceResult.Refused(com.unifiedledger.application.P801BindEvidenceRefusal.TARGET_ACCOUNT_NOT_RECONCILABLE))),
            )
        assertEquals(
            P503AppState.ReconciliationDetailNotice.BindRefused(com.unifiedledger.application.P801BindEvidenceRefusal.TARGET_ACCOUNT_NOT_RECONCILABLE),
            refused.notice,
        )

        val unknown = assertIs<P503AppState.ReconciliationDetail>(reducer.reduce(binding, P503UiEvent.ReconciliationBindLanded(null)))
        assertSame(P503AppState.ReconciliationDetailNotice.BindUnknownResult, unknown.notice)
        assertTrue(!unknown.binding)
        // Each of them kept the surface it started on: no branch left the detail (entry B's return
        // target is the overview, entry A's is the queue).
        listOf(bound, noChange, rejected, refused, unknown).forEach { state ->
            assertTrue(state.returnTo is ReconciliationBackTarget.Overview)
        }
    }

    @Test
    fun aBindLandingWithoutAnInFlightRequestIsAbsorbed() {
        val idle = enterDetail()
        assertSame(idle, reducer.reduce(idle, P503UiEvent.ReconciliationBindLanded(P801BindEvidenceResult.Bound(projection()))))
    }

    // ---- F1: the leg-anchored bind step ----

    @Test
    fun theLegDetailCarriesItsOwnBindableEvidenceAndTheTargetStaysTheLegsAccount() {
        val leg = queueRow()
        val bindable = P801BindableEvidenceResult.Success(listOf(evidence(evidenceId = "evidence-new", projection = null)))
        val detail =
            assertIs<P503AppState.ReconciliationDetail>(
                reducer.reduce(enterQueue(), P503UiEvent.OpenReconciliationLeg(leg, proposals(proposal()), audit(), bindableEvidence = bindable)),
            )
        assertEquals(bindable, detail.bindableEvidence)
        assertEquals(accountId, detail.bindTarget)
        assertEquals(leg.postingId, detail.anchorPostingId)
        assertNull(detail.bindEvidenceId)
        assertTrue(reconciliationBindStepVisible(detail))
        // No silent materialization: nothing is binding until the user picks a row and asks.
        assertFalse(reconciliationBindEnabled(detail))
        val picked = assertIs<P503AppState.ReconciliationDetail>(reducer.reduce(detail, P503UiEvent.SelectReconciliationBindEvidence("evidence-new")))
        assertEquals("evidence-new", picked.bindEvidenceId)
        assertTrue(reconciliationBindEnabled(picked))
        // A stale pick (a row that is not offered) is absorbed.
        assertSame(picked, reducer.reduce(picked, P503UiEvent.SelectReconciliationBindEvidence("evidence-absent")))
        // The bind request marks the single flight; the target is never re-chosen.
        val binding = assertIs<P503AppState.ReconciliationDetail>(reducer.reduce(picked, P503UiEvent.RequestBindReconciliationEvidence))
        assertTrue(binding.binding)
        assertEquals(accountId, binding.bindTarget)
    }

    @Test
    fun aBoundEvidenceLeavesTheBindListAndTheLandingCarriesTheFreshProposals() {
        val bindable = P801BindableEvidenceResult.Success(listOf(evidence(evidenceId = "evidence-1"), evidence(evidenceId = "evidence-2")))
        val detail =
            assertIs<P503AppState.ReconciliationDetail>(
                reducer.reduce(enterQueue(), P503UiEvent.OpenReconciliationLeg(queueRow(), proposals(proposal()), audit(), bindableEvidence = bindable)),
            )
        val picked = assertIs<P503AppState.ReconciliationDetail>(reducer.reduce(detail, P503UiEvent.SelectReconciliationBindEvidence("evidence-1")))
        val binding = assertIs<P503AppState.ReconciliationDetail>(reducer.reduce(picked, P503UiEvent.RequestBindReconciliationEvidence))
        val freshProposals = proposals(proposal())
        val bound =
            assertIs<P503AppState.ReconciliationDetail>(
                reducer.reduce(
                    binding,
                    P503UiEvent.ReconciliationBindLanded(P801BindEvidenceResult.Bound(projection()), proposals = freshProposals),
                ),
            )
        // F1d: binding removes the row from the bind list (it now has a READY projection) and the
        // fresh matcher read makes it a candidate.
        assertEquals(listOf("evidence-2"), (bound.bindableEvidence as P801BindableEvidenceResult.Success).rows.map { row -> row.evidenceId })
        assertSame(freshProposals, bound.proposals)
        assertNull(bound.bindEvidenceId)
        assertSame(P503AppState.ReconciliationDetailNotice.Bound, bound.notice)
    }

    @Test
    fun aNotReadyRuntimeLandsItsOwnTypedNoticeAndClaimsNothing() {
        // F7: the owner's readiness gate refused BEFORE the call, so the surface says "nothing was
        // written" instead of the unknown-result sentence.
        val selected = assertIs<P503AppState.ReconciliationDetail>(reducer.reduce(enterDetail(), P503UiEvent.SelectReconciliationCandidate(0, 0)))
        val confirming = assertIs<P503AppState.ReconciliationDetail>(reducer.reduce(selected, P503UiEvent.RequestConfirmReconciliationLink))
        val notReady = assertIs<P503AppState.ReconciliationDetail>(reducer.reduce(confirming, P503UiEvent.ReconciliationConfirmLanded(result = null, notReady = true)))
        assertSame(P503AppState.ReconciliationDetailNotice.RuntimeNotReady, notReady.notice)
        assertTrue(!notReady.confirming)
        assertTrue(reconciliationNoticeText(P503AppState.ReconciliationDetailNotice.RuntimeNotReady).contains("未写入"))

        val binding = assertIs<P503AppState.ReconciliationDetail>(reducer.reduce(detail(bindEvidenceId = "evidence-1"), P503UiEvent.RequestBindReconciliationEvidence))
        assertTrue(binding.binding)
        val bindNotReady = assertIs<P503AppState.ReconciliationDetail>(reducer.reduce(binding, P503UiEvent.ReconciliationBindLanded(result = null, notReady = true)))
        assertSame(P503AppState.ReconciliationDetailNotice.RuntimeNotReady, bindNotReady.notice)
        assertTrue(!bindNotReady.binding)
    }

    // ---- selection + confirm ----

    @Test
    fun selectionAcceptsOnlyAddressableCandidates() {
        val idle = enterDetail()
        val picked = assertIs<P503AppState.ReconciliationDetail>(reducer.reduce(idle, P503UiEvent.SelectReconciliationCandidate(0, 0)))
        assertEquals(P503AppState.ReconciliationSelection(0, 0), picked.selectedCandidate)
        // Out-of-bounds indices are absorbed, never stored as a dangling selection.
        assertSame(picked, reducer.reduce(picked, P503UiEvent.SelectReconciliationCandidate(3, 0)))
        assertSame(picked, reducer.reduce(picked, P503UiEvent.SelectReconciliationCandidate(0, 5)))
    }

    @Test
    fun confirmRequiresAnExplicitSelection() {
        val idle = enterDetail()
        val absorbed = assertIs<P503AppState.ReconciliationDetail>(reducer.reduce(idle, P503UiEvent.RequestConfirmReconciliationLink))
        assertTrue(!absorbed.confirming)
    }

    @Test
    fun linkedRefreshesTheAuditAndTheQueueAndClearsTheSelection() {
        val selected = assertIs<P503AppState.ReconciliationDetail>(reducer.reduce(enterDetail(), P503UiEvent.SelectReconciliationCandidate(0, 0)))
        val confirming = assertIs<P503AppState.ReconciliationDetail>(reducer.reduce(selected, P503UiEvent.RequestConfirmReconciliationLink))
        assertTrue(confirming.confirming)
        val freshAudit = audit()
        val linked =
            assertIs<P503AppState.ReconciliationDetail>(
                reducer.reduce(
                    confirming,
                    P503UiEvent.ReconciliationConfirmLanded(
                        result = P801ConfirmLinkResult.Linked(receipt("ACCEPTED")),
                        audit = freshAudit,
                        queueRefresh =
                            P503AppState.ReconciliationQueueRefresh(
                                page = page(queueRow(status = P408ReconciliationStatus.CHECKED)),
                                summary = summary(total(P408ReconciliationStatus.CHECKED, -10_000L)),
                            ),
                    ),
                ),
            )
        assertEquals(P503AppState.ReconciliationDetailNotice.Linked(queueRefreshed = true), linked.notice)
        assertTrue(!linked.confirming)
        assertNull(linked.selectedCandidate)
        assertSame(freshAudit, linked.audit)
        val refreshed = (linked.returnTo as ReconciliationBackTarget.Queue).state
        assertEquals(P408ReconciliationStatus.CHECKED, refreshed.rows.single().status)
        assertEquals(1, refreshed.checkedRows.size)
        assertEquals(P408ReconciliationStatus.CHECKED, refreshed.totals.single().status)
    }

    @Test
    fun noChangeIsSuccessWithoutASecondWrite() {
        val selected = assertIs<P503AppState.ReconciliationDetail>(reducer.reduce(enterDetail(), P503UiEvent.SelectReconciliationCandidate(0, 0)))
        val confirming = assertIs<P503AppState.ReconciliationDetail>(reducer.reduce(selected, P503UiEvent.RequestConfirmReconciliationLink))
        val noChange =
            assertIs<P503AppState.ReconciliationDetail>(
                reducer.reduce(confirming, P503UiEvent.ReconciliationConfirmLanded(P801ConfirmLinkResult.NoChange(receipt("NO_CHANGE")))),
            )
        assertSame(P503AppState.ReconciliationDetailNotice.NoChange, noChange.notice)
        assertTrue(!noChange.confirming)
        assertNull(noChange.selectedCandidate)
        // No refresh payload: the audit and the queue stay exactly as they were (nothing is claimed).
        assertSame(confirming.audit, noChange.audit)
        assertSame(confirming.returnTo, noChange.returnTo)
    }

    @Test
    fun rejectedAndRefusedKeepTheirTypedPayloadsAndWriteNothing() {
        val selected = assertIs<P503AppState.ReconciliationDetail>(reducer.reduce(enterDetail(), P503UiEvent.SelectReconciliationCandidate(0, 0)))
        val confirming = assertIs<P503AppState.ReconciliationDetail>(reducer.reduce(selected, P503UiEvent.RequestConfirmReconciliationLink))
        val rejected =
            assertIs<P503AppState.ReconciliationDetail>(
                reducer.reduce(confirming, P503UiEvent.ReconciliationConfirmLanded(P801ConfirmLinkResult.Rejected("P408_EVIDENCE_ALREADY_LINKED"))),
            )
        assertEquals(P503AppState.ReconciliationDetailNotice.Rejected("P408_EVIDENCE_ALREADY_LINKED"), rejected.notice)
        assertTrue(!rejected.confirming)
        // The selection survives a rejection (the user can pick another candidate).
        assertEquals(P503AppState.ReconciliationSelection(0, 0), rejected.selectedCandidate)
        val refused =
            assertIs<P503AppState.ReconciliationDetail>(
                reducer.reduce(confirming, P503UiEvent.ReconciliationConfirmLanded(P801ConfirmLinkResult.Refused(P801ConfirmLinkRefusal.EVIDENCE_NOT_PROJECTED))),
            )
        assertEquals(P503AppState.ReconciliationDetailNotice.Refused(P801ConfirmLinkRefusal.EVIDENCE_NOT_PROJECTED), refused.notice)
    }

    @Test
    fun anUnknownConfirmOutcomeClaimsNothingAboutTheWrite() {
        val selected = assertIs<P503AppState.ReconciliationDetail>(reducer.reduce(enterDetail(), P503UiEvent.SelectReconciliationCandidate(0, 0)))
        val confirming = assertIs<P503AppState.ReconciliationDetail>(reducer.reduce(selected, P503UiEvent.RequestConfirmReconciliationLink))
        val unknown = assertIs<P503AppState.ReconciliationDetail>(reducer.reduce(confirming, P503UiEvent.ReconciliationConfirmLanded(null)))
        assertSame(P503AppState.ReconciliationDetailNotice.UnknownResult, unknown.notice)
        assertTrue(!unknown.confirming)
        // Nothing is claimed: the audit, the queue and the selection stay untouched (D2: no
        // result-unknown row is persisted; re-entry re-reads authoritatively).
        assertSame(confirming.audit, unknown.audit)
        assertSame(confirming.returnTo, unknown.returnTo)
        assertEquals(P503AppState.ReconciliationSelection(0, 0), unknown.selectedCandidate)
        assertEquals("结果未知，重新进入核对", reconciliationNoticeText(checkNotNull(unknown.notice)))
    }

    @Test
    fun aConfirmLandingWithoutAnInFlightRequestIsAbsorbed() {
        val idle = enterDetail()
        assertSame(idle, reducer.reduce(idle, P503UiEvent.ReconciliationConfirmLanded(null)))
    }

    // ---- absorb discipline ----

    @Test
    fun everyQueueEventIsAbsorbedOutsideTheQueue() {
        val events =
            listOf(
                P503UiEvent.RequestReconciliationQueueMorePage,
                P503UiEvent.ReconciliationQueuePageLanded(1L, failedPage),
                P503UiEvent.ToggleReconciliationCheckedPartition,
                P503UiEvent.OpenReconciliationLeg(queueRow(), proposals(proposal()), audit()),
            )
        everyOtherState(exclude = { state -> state is P503AppState.ReconciliationQueue }) { state ->
            events.forEach { event -> assertSame(state, reducer.reduce(state, event)) }
        }
    }

    @Test
    fun everyDetailEventIsAbsorbedOutsideTheDetail() {
        val events =
            listOf(
                P503UiEvent.SelectReconciliationBindTarget(accountId),
                P503UiEvent.SelectReconciliationBindEvidence("evidence-1"),
                P503UiEvent.SelectReconciliationCandidate(0, 0),
                P503UiEvent.RequestBindReconciliationEvidence,
                P503UiEvent.ReconciliationBindLanded(null),
                P503UiEvent.RequestConfirmReconciliationLink,
                P503UiEvent.ReconciliationConfirmLanded(null),
            )
        everyOtherState(exclude = { state -> state is P503AppState.ReconciliationDetail }) { state ->
            events.forEach { event -> assertSame(state, reducer.reduce(state, event)) }
        }
    }

    @Test
    fun preExistingUnlistedCombinationsOnTheNewStatesRemainIllegalState() {
        val queueState = enterQueue()
        val detail = enterDetail()
        listOf(queueState, detail).forEach { state ->
            assertFailsWith<IllegalStateException> { reducer.reduce(state, P503UiEvent.RetryRefresh) }
            assertFailsWith<IllegalStateException> { reducer.reduce(state, P503UiEvent.StartNewExpense) }
            assertFailsWith<IllegalStateException> { reducer.reduce(state, P503UiEvent.SelectTab(P503Tab.HOME)) }
        }
    }

    // ---- helpers ----

    private fun receipt(outcome: String): com.unifiedledger.application.P408ReconciliationReceipt =
        com.unifiedledger.application.P408ReconciliationReceipt(
            requestId = "request-1",
            outcome = outcome,
            linkId = "link-1",
            reconciliationId = "reconciliation-1",
            historySequence = 1L,
        )

    private fun everyOtherState(
        exclude: (P503AppState) -> Boolean = { false },
        block: (P503AppState) -> Unit,
    ) {
        val draft = com.unifiedledger.application.ExpenseDraft(accountId, null, "35.80", occurredAt)
        val requestId = com.unifiedledger.application.RequestId("request-1")
        val transactionId = TransactionId("tx-1")
        val states =
            listOf<P503AppState>(
                P503AppState.Ready(),
                accountFlow(),
                P503AppState.TransactionDetail(
                    overview = overview(),
                    originTab = P503Tab.HOME,
                    transactionId = transactionId,
                    detail = com.unifiedledger.application.TransactionDetailResult.NotFound,
                ),
                P503AppState.ImportCandidateDetail(
                    overview = overview(),
                    candidateId = com.unifiedledger.application.ImportCandidateId("candidate-1"),
                    detail = com.unifiedledger.application.ImportCandidateDetailResult.Absent,
                    duplicates = com.unifiedledger.application.ImportDuplicateReviewsResult.NoDuplicates,
                    form = ImportDecisionDraft(),
                ),
                P503AppState.ImportBatchConfirm(overview()),
                P503AppState.ImportBatchSubmitting(overview(), "2026-09-14T08:00:00Z", emptyList()),
                P503AppState.TransactionEdit(overview = overview(), origin = origin()),
                P503AppState.TransferEdit(overview = overview(), origin = transferOrigin()),
                P503AppState.VoidConfirm(overview = overview(), transactionId = transactionId),
                P503AppState.RecycleBin(overview = overview(), rows = RecycleBinResult.Success(emptyList())),
                P503AppState.TransactionAnnotationEdit(
                    overview = overview(),
                    intent =
                        TransactionAnnotationEditIntent(
                            transactionId = transactionId,
                            expectedAnnotationRevision = 0L,
                            expectedCurrentVersionId = TransactionVersionId("version-1"),
                        ),
                ),
                P503AppState.BackupExport(overview = overview()),
                P503AppState.BackupRestore(overview = overview()),
                P503AppState.RestoreSessionTerminal(BackupRestoreRecoveryCause.RollbackReopenFailed),
                P503AppState.BudgetConfig(
                    overview = overview(),
                    scope = com.unifiedledger.domain.BudgetScope.Total,
                    month = kotlinx.datetime.YearMonth(2026, 3),
                    revision = 0L,
                    closed = false,
                    limitMinorUnits = null,
                ),
                P503AppState.TagMerchantFilter(overview(), kotlinx.datetime.YearMonth(2026, 1)),
                P503AppState.Editing(draft, requestId),
                P503AppState.AwaitingConfirmation(draft, requestId),
                P503AppState.Submitting(draft, requestId),
                P503AppState.Created,
                P503AppState.NoChange,
                P503AppState.Recovered,
                P503AppState.RequestIdentityConflict(draft, requestId),
                P503AppState.DomainRejected(draft, requestId),
                P503AppState.InfrastructureFailure(InfrastructureFailureContext.SUBMISSION, draft, requestId),
                P503AppState.InfrastructureFailure(InfrastructureFailureContext.READ),
                P503AppState.UnknownCommit(draft, requestId),
                queue(),
                detail(),
            )
        states.filterNot(exclude).forEach(block)
    }

    private fun origin(): TransactionEditOrigin =
        TransactionEditOrigin(
            transactionId = TransactionId("tx-1"),
            currentVersionId = TransactionVersionId("version-1"),
            note = "old note",
            statisticsAt = occurredAt,
            amountText = "100.00",
            categoryId = com.unifiedledger.domain.CategoryId("category-food"),
            fundingAccountId = accountId,
        )

    private fun transferOrigin(): TransferCorrectionOrigin =
        TransferCorrectionOrigin(
            transactionId = TransactionId("tx-1"),
            currentVersionId = TransactionVersionId("version-1"),
            note = null,
            statisticsAt = occurredAt,
            currency = cny,
            sourceAccountId = accountId,
            destinationAccountId = otherAccountId,
            destinationCreditMinorUnits = 10_000L,
            feeMinorUnits = 0L,
            feeCategoryId = null,
        )
}
