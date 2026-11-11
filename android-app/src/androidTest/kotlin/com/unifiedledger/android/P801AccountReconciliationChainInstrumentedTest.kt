package com.unifiedledger.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unifiedledger.application.DEFAULT_MANAGEABLE_ACCOUNT_ID
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.P408EvidenceResponsibility
import com.unifiedledger.application.P408MatchDisposition
import com.unifiedledger.application.P408Matcher
import com.unifiedledger.application.P408ProjectionState
import com.unifiedledger.application.P408ReconciliationStatus
import com.unifiedledger.application.P801AccountReconciliationPageResult
import com.unifiedledger.application.P801AccountReconciliationQueuePage
import com.unifiedledger.application.P801AccountReconciliationQueueRow
import com.unifiedledger.application.P801AccountReconciliationSummaryResult
import com.unifiedledger.application.P801BindEvidenceResult
import com.unifiedledger.application.P801CandidateProposalResult
import com.unifiedledger.application.P801ConfirmLinkResult
import com.unifiedledger.application.P801ReconciledLegAuditResult
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.ui.ListScrollPosition
import com.unifiedledger.ui.P503AppState
import com.unifiedledger.ui.P503ReducerImpl
import com.unifiedledger.ui.P503UiEvent
import com.unifiedledger.ui.ReconciliationBackTarget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Instant

/**
 * P8-01a V-10 device proof (spec sections 4 V-10 and 6): the whole reconciliation chain
 * 账户 → 队列 → 绑定 → 候选 → 确认 → 审计 runs on a real Android device through the PRODUCTION
 * composition root (`openAndroidStableStorageLedger` + the real Android SQLite driver), with the
 * reducer's scroll-slot round trip (返回保留滚动位) as the fourth leg.
 *
 * The seeding lives in `P801ChainFixture.kt` (this file holds exactly the four device cases the
 * inventory enumerates). No Compose UI test harness is involved: the scroll slots are plain
 * reducer state, and `app-ui` is an `implementation` dependency of this module.
 *
 * EVERY CASE IS INDEPENDENT: each one opens its own redirected ledger root (see the fixture), so
 * the four can run in any order and leave nothing behind.
 */
@RunWith(AndroidJUnit4::class)
class P801AccountReconciliationChainInstrumentedTest {
    private var fixture: P801ChainFixture? = null

    @After
    fun closeTheFixture() {
        fixture?.close()
        fixture = null
    }

    /**
     * V-1 on device: the account queue enumerates exactly the transfer's destination leg — still
     * `PENDING` because no reconciliation row exists — and the summary totals that same leg.
     */
    @Test
    fun theQueueAndSummaryShowTheTransfersPendingLeg() {
        val chain = openChain("queue")
        val accountId = chain.createSecondAccount()
        chain.createConfirmedTransfer(accountId)

        val rows = chain.queueRows(accountId)
        assertEquals("the queue must list exactly the transfer's destination leg", 1, rows.size)
        val leg = rows.single()
        assertEquals(P801ChainFacts.TRANSFER_MINOR, leg.amountMinor)
        assertEquals(accountId, leg.accountId)
        assertEquals(P801ChainFacts.currency, leg.currency)
        assertEquals(P801ChainFacts.NOTE, leg.note)
        assertEquals(TransactionKind.ACCOUNT_TRANSFER, leg.transactionKind)
        assertEquals(P408ReconciliationStatus.PENDING, leg.status)
        assertFalse("a leg with no link must report no active evidence link", leg.hasActiveEvidenceLink)

        val totals = chain.summaryTotals(accountId)
        assertEquals("the summary must carry exactly one (currency, status) group", 1, totals.size)
        val total = totals.single()
        assertEquals(P801ChainFacts.currency, total.currency)
        assertEquals(P408ReconciliationStatus.PENDING, total.status)
        assertEquals(P801ChainFacts.TRANSFER_MINOR, total.totalMinorUnits)
        assertEquals(1, total.rowCount)
    }

    /**
     * V-11 → V-2 on device: binding the import evidence produces the `READY` projection the frozen
     * matcher consumes (a derived write: the queue status and every formal row stay put), the
     * account-entry proposal is one `PROPOSED_MATCH` on that very leg, and confirming it links the
     * evidence so the leg reads `CHECKED`.
     */
    @Test
    fun bindingTurnsTheEvidenceReadyAndConfirmingLinksTheProposedMatch() {
        val chain = openChain("bind")
        val accountId = chain.createSecondAccount()
        chain.createConfirmedTransfer(accountId)
        val evidenceId = chain.createImportEvidence()
        val sourceAccountId = AccountId(DEFAULT_MANAGEABLE_ACCOUNT_ID)

        val leg = chain.queueRows(accountId).single()
        assertEquals(P408ReconciliationStatus.PENDING, leg.status)
        val countsBeforeBind = chain.formalCounts()
        val balancesBeforeBind = chain.balanceFingerprint()
        val postingsBeforeBind = chain.postingFingerprint(accountId, sourceAccountId)

        val bound = chain.bind(evidenceId, accountId)
        check(bound is P801BindEvidenceResult.Bound) { "the bind must be accepted, got $bound" }
        assertEquals("the bind must create the READY projection", P408ProjectionState.READY, bound.projection.state)
        assertEquals(accountId.value, bound.projection.targetAccountId)
        assertEquals(P801ChainFacts.TRANSFER_MINOR, bound.projection.normalizedAmountMinor)
        assertEquals(P801ChainFacts.DIRECTION_IN, bound.projection.directionToken)

        // A bind is a derived write: no reconciliation status and no formal row may move.
        assertEquals(P408ReconciliationStatus.PENDING, chain.queueRows(accountId).single().status)
        assertEquals(countsBeforeBind, chain.formalCounts())
        assertEquals(balancesBeforeBind, chain.balanceFingerprint())
        assertEquals(postingsBeforeBind, chain.postingFingerprint(accountId, sourceAccountId))

        val proposals = chain.propose(leg)
        check(proposals is P801CandidateProposalResult.Success) { "the proposal read must answer, got $proposals" }
        assertEquals("the window holds exactly one evidence row", 1, proposals.proposals.size)
        val proposal = proposals.proposals.single()
        assertEquals(evidenceId, proposal.evidence.evidenceId)
        assertEquals(P408MatchDisposition.PROPOSED_MATCH, proposal.match.disposition)
        val candidate = proposal.match.candidates.single()
        assertEquals(leg.postingId, candidate.posting.postingId)
        assertEquals(leg.transactionId, candidate.posting.transactionId)
        assertEquals(P408Matcher.MATCH_FIELDS, candidate.basis.fields)
        assertEquals(P408Matcher.DEFAULT_WINDOW_DAYS, candidate.basis.windowDays)
        assertEquals(0L, candidate.basis.naturalDayDistance)

        val confirmed = chain.confirm(proposal.evidence, candidate)
        check(confirmed is P801ConfirmLinkResult.Linked) { "the confirm must be accepted, got $confirmed" }
        val reconciled = chain.queueRows(accountId).single()
        assertEquals(P408ReconciliationStatus.CHECKED, reconciled.status)
        assertTrue("the reconciled leg must report its active evidence link", reconciled.hasActiveEvidenceLink)
    }

    /**
     * V-2 / V-10 audit leg on device: after the confirm, the audit read-back reports the frozen
     * decision basis (v2, the five fields, the natural-day distance, the human decision) and the
     * evidence's own source row — and the whole chain moved no transaction, posting, balance or
     * report count (the monthly report is a projection of the identical posting set).
     */
    @Test
    fun theAuditReadBackReportsTheFrozenBasisAndMovesNoAccounting() {
        val chain = openChain("audit")
        val accountId = chain.createSecondAccount()
        chain.createConfirmedTransfer(accountId)
        val evidenceId = chain.createImportEvidence()
        val sourceAccountId = AccountId(DEFAULT_MANAGEABLE_ACCOUNT_ID)

        val countsBeforeChain = chain.formalCounts()
        val balancesBeforeChain = chain.balanceFingerprint()
        val postingsBeforeChain = chain.postingFingerprint(accountId, sourceAccountId)

        val leg = chain.queueRows(accountId).single()
        val bound = chain.bind(evidenceId, accountId)
        check(bound is P801BindEvidenceResult.Bound) { "the bind must be accepted, got $bound" }
        val proposals = chain.propose(leg)
        check(proposals is P801CandidateProposalResult.Success) { "the proposal read must answer, got $proposals" }
        val proposal = proposals.proposals.single()
        val confirmed = chain.confirm(proposal.evidence, proposal.match.candidates.single())
        check(confirmed is P801ConfirmLinkResult.Linked) { "the confirm must be accepted, got $confirmed" }

        val audit = chain.audit(leg.postingId)
        check(audit is P801ReconciledLegAuditResult.Success) { "the audit read must answer, got $audit" }
        val readBack = audit.audit
        assertEquals(leg.postingId, readBack.postingId)
        assertEquals(leg.transactionId, readBack.transactionId)

        val reconciliation = checkNotNull(readBack.reconciliation) { "the confirmed leg must carry a reconciliation row" }
        assertEquals(P408ReconciliationStatus.CHECKED, reconciliation.status)
        assertEquals("one leg holds exactly one evidence link", 1, readBack.evidence.size)
        val link = readBack.evidence.single()
        assertEquals(evidenceId, link.evidenceId)
        assertEquals(P408EvidenceResponsibility.DESTINATION_ASSET_POSTING, link.responsibility)
        assertEquals("active", link.state)

        val decision = checkNotNull(link.decision) { "a confirmed link must carry its request snapshot" }
        assertEquals("every new write is basis version 2", 2, decision.basisVersion)
        assertEquals(
            "the five-field funding identity, in the persisted (sorted) order",
            listOf("account", "amount", "currency", "direction", "occurred_at_window"),
            decision.matchBasis,
        )
        assertEquals(P408Matcher.DEFAULT_WINDOW_DAYS, decision.windowDays)
        assertEquals("the seeded evidence shares the posting's natural day", 0, decision.naturalDayDistance)
        assertEquals("confirm_match", decision.humanDecision)

        val source = link.source
        assertEquals(evidenceId, source.evidenceId)
        assertEquals(P801ChainFacts.INPUT_REF, source.importInputRef)
        assertEquals(0L, source.recordOrdinal)
        assertEquals(ImportRecordKind.ORDINARY_FLOW_SOURCE.storageValue, source.recordKind)
        assertEquals(P801ChainFacts.OCCURRED_AT_TEXT, source.occurredAt)

        // Reconciliation never changes accounting: identical counts, balances and posting rows.
        assertEquals(countsBeforeChain, chain.formalCounts())
        assertEquals(balancesBeforeChain, chain.balanceFingerprint())
        assertEquals(postingsBeforeChain, chain.postingFingerprint(accountId, sourceAccountId))
    }

    /**
     * V-10's return leg on device: the two sampled scroll positions land in their own restore slots
     * (`AccountFlow.listScroll` for the queue's origin, `ReconciliationQueue.listScroll` for the
     * leg's queue position), each is consumed exactly once by `ListScrollRestored`, and a further
     * dispatch is a no-op — the reducer is plain Kotlin, so this needs no Compose harness.
     */
    @Test
    fun theScrollSlotsRoundTripThroughTheReducer() {
        val reducer = P503ReducerImpl(ParseManualExpenseAmount(), P801ChainFacts.currency)
        val accountFlow =
            P503AppState.AccountFlow(
                overview =
                    P503AppState.OverviewEmpty(
                        state =
                            LedgerCurrentState(
                                ledgerId = P801ChainFacts.ledgerId,
                                transactions = emptyList(),
                                balances = emptyList(),
                            ),
                    ),
                accountId = AccountId(DEFAULT_MANAGEABLE_ACCOUNT_ID),
                balanceLine = "",
                balanceMinorUnits = 0L,
                balanceCurrency = P801ChainFacts.currency,
                loadedRows = emptyList(),
                nextWholeLedgerCursor = null,
            )

        val originScroll = ListScrollPosition(3, 24)
        val queue =
            reducer.reduce(
                accountFlow,
                P503UiEvent.SelectReconciliationQueue(
                    accountId = accountFlow.accountId,
                    accountLine = "",
                    firstPage =
                        P801AccountReconciliationPageResult.Success(
                            P801AccountReconciliationQueuePage(rows = emptyList(), nextCursor = null),
                        ),
                    summary = P801AccountReconciliationSummaryResult.Success(emptyList()),
                    originScroll = originScroll,
                ),
            )
        check(queue is P503AppState.ReconciliationQueue) { "the queue entry must land in the queue state" }
        assertEquals("the sampled account-flow position rides the flow the queue returns to", originScroll, queue.returnTo.listScroll)

        val queueScroll = ListScrollPosition(0, 42)
        val detail =
            reducer.reduce(
                queue,
                P503UiEvent.OpenReconciliationLeg(
                    leg = queueLeg(),
                    proposals = P801CandidateProposalResult.Success(emptyList()),
                    audit = P801ReconciledLegAuditResult.NotFound,
                    queueScroll = queueScroll,
                ),
            )
        check(detail is P503AppState.ReconciliationDetail) { "the leg open must land in the detail state" }
        val returnedQueue = (detail.returnTo as ReconciliationBackTarget.Queue).state
        assertEquals("the sampled queue position rides the queue the detail returns to", queueScroll, returnedQueue.listScroll)

        val clearedFlow = reducer.reduce(queue.returnTo, P503UiEvent.ListScrollRestored)
        check(clearedFlow is P503AppState.AccountFlow) { "the flow's consume must stay on the flow" }
        assertNull("the flow slot is consumed exactly once", clearedFlow.listScroll)
        val clearedQueue = reducer.reduce(returnedQueue, P503UiEvent.ListScrollRestored)
        check(clearedQueue is P503AppState.ReconciliationQueue) { "the queue's consume must stay on the queue" }
        assertNull("the queue slot is consumed exactly once", clearedQueue.listScroll)
        assertSame("a further consume is a no-op", clearedQueue, reducer.reduce(clearedQueue, P503UiEvent.ListScrollRestored))
    }

    /** One synthetic queue leg for the reducer case; the reducer never reads the database. */
    private fun queueLeg(): P801AccountReconciliationQueueRow =
        P801AccountReconciliationQueueRow(
            postingId = PostingId("p801-v10-scroll-leg"),
            transactionId = TransactionId("p801-v10-scroll-transaction"),
            occurredAt = Instant.parse(P801ChainFacts.OCCURRED_AT_TEXT),
            note = P801ChainFacts.NOTE,
            accountId = AccountId(DEFAULT_MANAGEABLE_ACCOUNT_ID),
            amountMinor = P801ChainFacts.TRANSFER_MINOR,
            currency = P801ChainFacts.currency,
            status = P408ReconciliationStatus.PENDING,
            hasActiveEvidenceLink = false,
            transactionKind = TransactionKind.ACCOUNT_TRANSFER,
        )

    /** One freshly opened production graph over this case's own redirected ledger root. */
    private fun openChain(name: String): P801ChainFixture =
        P801ChainFixture(name).also { opened ->
            fixture = opened
            opened.open()
        }
}
