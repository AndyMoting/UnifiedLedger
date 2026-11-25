package com.unifiedledger.android

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unifiedledger.application.DEFAULT_HIDDEN_EXPENSE_ACCOUNT_ID
import com.unifiedledger.application.DEFAULT_MANAGEABLE_ACCOUNT_ID
import com.unifiedledger.application.P408ReconciliationStatus
import com.unifiedledger.application.P801AccountReconciliationPageResult
import com.unifiedledger.application.P801AccountReconciliationSummaryResult
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.PostingId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P8-07 Q2 flow 2, the stage-acceptance design's second cross-slice flow (design section 2, row 2;
 * the criteria are P8-01a spec sections 2.4–2.7 and section 4, vectors V-8 / V-10 / V-11):
 * IMPORT EVIDENCE → RECONCILIATION → AUDIT RE-READ, on the dev device, over the shared anonymous
 * starting seed.
 *
 * WHAT THIS CASE IS. One device run of the whole flow, from the shared seed's independent initial
 * state (two confirmed transactions / four postings / one pending import candidate, the device
 * equivalent built by [P807Q2Flow1Seed]), through the real UI. Both entry points of the spec's
 * section 2.2 are walked inside this ONE case: entry A (account flow → 账户对账 queue) for the
 * queue and the audit, entry B (the import candidate row → 用于核对既有分录) for the bind, the
 * candidate and the confirm. The formal ledger is read before and after every write leg through
 * the app's own runtime accessor, and the reconciliation read face is read through the same
 * runtime's leased port, so the UI copy and the authoritative payload are compared rather than
 * trusted.
 *
 * THE LEGS AND WHAT EACH ONE PROVES
 * 1. Initial state: the seed's own read and the flow's first authoritative read agree — 2
 *    transactions / 4 postings / 1 pending candidate / the exact four account balances; the
 *    evidence carries NO projection and NO active link; the import review row is NOT used for
 *    reconciliation; the account queue holds exactly the one pending destination leg.
 * 2. Entry A: the accounts region's account row opens the account flow, its 账户对账 action opens
 *    the queue; the unchecked partition, the whole-account totals and the row's exact text render.
 * 3. Entry B: leaving the queue the original way, the import review list offers 用于核对既有分录 on
 *    the seeded candidate; the evidence-focused surface states 绑定到账户 and the irreversible
 *    warning BEFORE the action, keeps 绑定 unavailable until an account is chosen, then binds and
 *    answers 已绑定，该导入行现在可用于核对。 The bound evidence reads back as a READY projection on
 *    the chosen account with the formal ledger unchanged (the bind is a derived write).
 * 4. Candidates: 候选证据, the frozen disposition 唯一候选, and the one candidate's exact line with
 *    the matcher's natural-day distance.
 * 5. Four elements + confirm: 外部证据 / 证据职责 / 匹配依据 / 人工决定 render for the selected
 *    candidate, with the `in` leg's unique duty token (`destination_asset_posting`) and the frozen
 *    five-field basis; 确认核对 answers the entry-B success notice.
 * 6. Queue flip: entry A re-read shows 未核对 0 条, the collapsed checked partition, the flipped
 *    totals, and the row's own 已核对 label once expanded.
 * 7. Audit read-back: 审计回看 and 状态历史 render the evidence source, the duty, the five-field
 *    basis, the natural-day distance, the human decision with its time and the status history; each
 *    of those lines EQUALS the line derived from the port's own audit payload (the verbatim
 *    "UI == authoritative read" comparison, not a second copy of the rule).
 * 8. Re-entry (Q4: page-level, no force-stop): leaving the audit surface and entering it again
 *    still locates the same evidence, and the leg still carries exactly one ACTIVE link.
 * 9. Review surface + refusal (V-8): the candidate row now carries 已用于核对既有分录 and the entry-B
 *    affordance is gone; the transfer decision is completed from the candidate detail, the candidate
 *    is selected and authorized, and the persisted per-item line refuses it with
 *    IMPORT_EVIDENCE_ALREADY_RECONCILED and 已入账 0 项，拒绝 1 项.
 * 10. Zero accounting effect: the formal ledger read back after every leg equals the first read
 *    item by item — transactions, postings, balances, VERSION ids and candidate rows — the evidence
 *    keeps exactly one active link, and the candidate's status row is unchanged.
 *
 * NOT COVERED, DELIBERATELY (design gate Q5; each has its own service-level device evidence in
 * `P801AccountReconciliationChainInstrumentedTest.kt:260-282`, the #430 / 2fb90f16 chain): the
 * `import_candidate_status_history` row count (the read face exposes only a count, not the rows,
 * `ImportReviewReadPort.kt:73-76`); the V-11 same-parameter replay's `NoChange` result (this flow
 * binds once, so no second identical bind is reachable from the UI); and the spec section 2.5
 * "unknown result" branch (no way to interrupt the in-flight call from a UI-only driver). V-10's
 * "返回保留滚动位" is likewise unobservable here: the queue holds exactly ONE leg, so there is no
 * scrollable position to preserve — the mechanism's read-face evidence is
 * `P801AccountReconciliationChainInstrumentedTest.theScrollSlotsRoundTripThroughTheReducer`
 * (`:342`).
 *
 * WHAT IS NOT CLAIMED: no duration bound, no network, no force-stop/restart, no stage acceptance
 * (the design's section 6 keeps acceptance to its own decision), and no product code change — this
 * case drives the copied product through its own UI; it never writes the ledger itself.
 */
@RunWith(AndroidJUnit4::class)
class P807Q2Flow2ReconciliationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext

    @Test
    fun theImportedEvidenceBindsToTheExistingPostingAndSurvivesAuditReentry() {
        val log: (String) -> Unit = { line -> Log.i(TAG, line) }
        val facts = P807Q2Flow2Facts
        val destinationPostingId = PostingId(facts.TRANSFER_DESTINATION_POSTING_ID)

        // ---------------------------------------------------------------- L1: the seeded initial state
        val seeded = P807Q2Flow1Seed.prepare(targetContext, log)
        val reconciliation = P807Q2Flow2Reconciliation(targetContext, log)
        val ui = P807Q2Flow2Ui(P807Q2Flow1Ui(instrumentation, log), log)
        ui.launch()
        assertTrue(
            "${facts.PREFIX} the launched home must render the live overview's search capsule (the seeded ledger opened)",
            ui.awaitHome(),
        )
        val initialRead = reconciliation.snapshot()
        assertEquals("${facts.PREFIX} the launched read must equal the seed's own read of the shared state", seeded.initial, initialRead.formal)
        assertEquals("${facts.PREFIX} the shared seed must start with 2 transactions", 2, initialRead.formal.transactionTotal)
        assertEquals("${facts.PREFIX} the shared seed must start with 4 postings", 4, initialRead.formal.legs.size)
        assertEquals("${facts.PREFIX} the shared seed must start with one pending import candidate", 1, initialRead.formal.pendingCandidateCount())
        assertEquals(
            "${facts.PREFIX} the shared seed's balances must be the exact seeded amounts",
            p807Q2Flow1Balances(
                DEFAULT_MANAGEABLE_ACCOUNT_ID to -P807Q2Flow1Facts.TRANSFER_MINOR,
                seeded.reconcileAccountId.value to P807Q2Flow1Facts.TRANSFER_MINOR,
                seeded.refundAccountId.value to -P807Q2Flow1Facts.REFUNDABLE_EXPENSE_MINOR,
                DEFAULT_HIDDEN_EXPENSE_ACCOUNT_ID to P807Q2Flow1Facts.REFUNDABLE_EXPENSE_MINOR,
            ),
            initialRead.formal.balances,
        )
        val evidenceBefore =
            checkNotNull(reconciliation.evidence(facts.SEEDED_EVIDENCE_ID)) {
                "${facts.PREFIX} the seed's evidence must be readable by identity"
            }
        assertNull(
            "${facts.PREFIX} the seeded evidence must carry NO projection before the bind (spec section 2.4)",
            evidenceBefore.projection,
        )
        assertFalse(
            "${facts.PREFIX} the seeded evidence must NOT read as a READY projection before the bind",
            p807Q2Flow2ProjectionReady(evidenceBefore),
        )
        assertFalse("${facts.PREFIX} the seeded evidence must have no active link before the flow", evidenceBefore.hasActiveEvidenceLink)
        val auditBefore = reconciliation.audit(destinationPostingId)
        assertEquals("${facts.PREFIX} the destination leg must carry no evidence link before the flow", 0, auditBefore.evidence.size)
        assertNull("${facts.PREFIX} the destination leg must carry no posting_reconciliation row before the confirm", auditBefore.reconciliation)
        val reviewBefore = reconciliation.reviewRow(facts.SEEDED_CANDIDATE_ID)
        assertEquals("${facts.PREFIX} the seeded candidate must be pending_confirmation", "pending_confirmation", reviewBefore.candidateStatus)
        assertFalse("${facts.PREFIX} the seeded candidate must NOT be used for reconciliation before the flow", reviewBefore.usedForReconciliation)
        assertEquals("${facts.PREFIX} the review row must carry the seeded evidence id", facts.SEEDED_EVIDENCE_ID, reviewBefore.evidenceId)
        val rowsBefore = firstQueueRows(reconciliation, seeded.reconcileAccountId)
        assertEquals("${facts.PREFIX} the account queue must hold exactly the one pending destination leg", 1, rowsBefore.size)
        assertEquals("${facts.PREFIX} the queued leg must be the transfer's destination posting", destinationPostingId, rowsBefore.single().postingId)
        assertEquals("${facts.PREFIX} the queued leg must carry the transfer's exact minor units", P807Q2Flow1Facts.TRANSFER_MINOR, rowsBefore.single().amountMinor)
        assertEquals("${facts.PREFIX} the queued leg must read as PENDING", P408ReconciliationStatus.PENDING, rowsBefore.single().status)
        assertEquals(
            "${facts.PREFIX} the port's own row face must be the exact queue copy",
            facts.QUEUE_ROW_PENDING,
            p807Q2Flow2QueueRowText(rowsBefore.single()),
        )
        assertEquals(
            "${facts.PREFIX} the totals must be the single PENDING group of the exact amount",
            listOf("CNY|PENDING|1234|1"),
            p807Q2Flow2TotalLines(summaryTotals(reconciliation, seeded.reconcileAccountId)),
        )
        log("${facts.PREFIX} initial ${initialRead.logLine()}")

        // ---------------------------------------------------------------- L2: entry A, the queue
        ui.openAccountsTab()
        ui.openAccountFlow(P807Q2Flow1Facts.RECONCILE_ACCOUNT_NAME)
        ui.openQueue(facts.UNCHECKED_ONE)
        ui.seek(facts.SUMMARY_BEFORE)
        assertTrue(
            "${facts.PREFIX} the queue must render the whole-account totals [${facts.SUMMARY_BEFORE}]",
            ui.hasText(facts.SUMMARY_BEFORE),
        )
        ui.seek(facts.QUEUE_ROW_PENDING)
        assertTrue(
            "${facts.PREFIX} the queue row must render its date, note, exact amount and state [${facts.QUEUE_ROW_PENDING}]",
            ui.hasText(facts.QUEUE_ROW_PENDING),
        )

        // ---------------------------------------------------------------- L3: entry B, the bind
        ui.backTo(facts.ACCOUNT_FLOW_TITLE)
        ui.backTo(facts.ACCOUNTS_GROUP_PREFIX)
        ui.openImportReview()
        ui.openEvidenceEntry()
        assertTrue(
            "${facts.PREFIX} the evidence-focused surface must be the entry-B surface",
            ui.hasText(facts.EVIDENCE_ENTRY_TITLE),
        )
        assertTrue(
            "${facts.PREFIX} the bind step must print its irreversible warning BEFORE the action",
            ui.bindWarningVisible(),
        )
        assertTrue(
            "${facts.PREFIX} the account pick must enable 绑定 and the bind must be accepted",
            ui.bindToAccount(P807Q2Flow1Facts.RECONCILE_ACCOUNT_NAME),
        )
        val evidenceAfterBind =
            checkNotNull(reconciliation.evidence(facts.SEEDED_EVIDENCE_ID)) {
                "${facts.PREFIX} the bound evidence must still be readable"
            }
        assertTrue(
            "${facts.PREFIX} the bind must leave a READY projection (spec section 2.4)",
            p807Q2Flow2ProjectionReady(evidenceAfterBind),
        )
        assertEquals(
            "${facts.PREFIX} the projection's target must be the account the user picked",
            seeded.reconcileAccountId.value,
            evidenceAfterBind.projection?.targetAccountId,
        )
        assertEquals(
            "${facts.PREFIX} the bind is a DERIVED write: the formal ledger must not move",
            initialRead.formal,
            reconciliation.snapshot().formal,
        )

        // ---------------------------------------------------------------- L4: the candidate block
        ui.seek(facts.CANDIDATES_HEADER)
        assertTrue("${facts.PREFIX} the surface must render the candidate section", ui.hasText(facts.CANDIDATES_HEADER))
        ui.seek(facts.PROPOSED_MATCH)
        assertTrue("${facts.PREFIX} the frozen matcher must propose ONE candidate", ui.hasText(facts.PROPOSED_MATCH))
        ui.seek(facts.CANDIDATE_LINE)
        assertTrue(
            "${facts.PREFIX} the candidate line must carry the leg's date, exact amount and natural-day distance",
            ui.hasText(facts.CANDIDATE_LINE),
        )

        // ---------------------------------------------------------------- L5: four elements + confirm
        assertTrue("${facts.PREFIX} the candidate selection must open the four-element block", ui.selectCandidate())
        sequenceOf(
            facts.AUDIT_EXTERNAL_EVIDENCE_LINE,
            facts.FOUR_ELEMENT_RESPONSIBILITY_LINE,
            facts.MATCH_BASIS_LINE,
            facts.NATURAL_DAY_DISTANCE,
            facts.FOUR_ELEMENT_HUMAN_DECISION_LINE,
        ).forEach { line ->
            ui.seek(line)
            assertTrue("${facts.PREFIX} the four-element block must show the exact line [$line]", ui.hasText(line))
        }
        assertTrue("${facts.PREFIX} 确认核对 must be accepted and answer its success notice", ui.confirm())

        // ---------------------------------------------------------------- L6: the queue flips
        ui.backTo(facts.REVIEW_PENDING_HEADER)
        ui.goHome()
        ui.openAccountsTab()
        ui.openAccountFlow(P807Q2Flow1Facts.RECONCILE_ACCOUNT_NAME)
        ui.openQueue(facts.UNCHECKED_ZERO)
        ui.seek(facts.CHECKED_ONE_COLLAPSED)
        assertTrue(
            "${facts.PREFIX} the checked partition must be collapsed with one leg [${facts.CHECKED_ONE_COLLAPSED}]",
            ui.hasText(facts.CHECKED_ONE_COLLAPSED),
        )
        ui.seek(facts.SUMMARY_AFTER)
        assertTrue(
            "${facts.PREFIX} the totals must flip to the checked group [${facts.SUMMARY_AFTER}]",
            ui.hasText(facts.SUMMARY_AFTER),
        )
        assertTrue("${facts.PREFIX} expanding the checked partition must render the leg's checked row", ui.expandCheckedPartition())
        val rowsAfter = firstQueueRows(reconciliation, seeded.reconcileAccountId)
        assertEquals("${facts.PREFIX} the queue must still hold exactly one leg", 1, rowsAfter.size)
        assertEquals("${facts.PREFIX} the leg must read as CHECKED after the confirm", P408ReconciliationStatus.CHECKED, rowsAfter.single().status)
        assertEquals(
            "${facts.PREFIX} the port's flipped row face must be the exact queue copy",
            facts.QUEUE_ROW_CHECKED,
            p807Q2Flow2QueueRowText(rowsAfter.single()),
        )
        assertEquals(
            "${facts.PREFIX} the totals must flip to the exact checked amount",
            listOf("CNY|CHECKED|1234|1"),
            p807Q2Flow2TotalLines(summaryTotals(reconciliation, seeded.reconcileAccountId)),
        )

        // ---------------------------------------------------------------- L7: the audit read-back
        assertTrue("${facts.PREFIX} the queue row must open the leg's audit surface", ui.openLegAudit())
        val audit = reconciliation.audit(destinationPostingId)
        assertEquals("${facts.PREFIX} the leg must carry exactly one evidence link", 1, audit.evidence.size)
        val link = audit.evidence.single()
        assertEquals("${facts.PREFIX} the link must be ACTIVE", "active", link.state)
        assertEquals("${facts.PREFIX} the link must name the seeded evidence", facts.SEEDED_EVIDENCE_ID, link.evidenceId)
        val decision =
            checkNotNull(link.decision) {
                "${facts.PREFIX} the confirmed link must carry its reconciliation_request_snapshot basis"
            }
        assertEquals("${facts.PREFIX} the frozen basis version must be 2 (spec section 2.5)", 2, decision.basisVersion)
        assertEquals("${facts.PREFIX} the frozen window must be 2 natural days (spec section 2.5)", 2, decision.windowDays)
        assertEquals("${facts.PREFIX} the matcher's natural-day distance must be 0", 0, decision.naturalDayDistance)
        assertEquals("${facts.PREFIX} the persisted human decision must be confirm_match", facts.HUMAN_DECISION_CONFIRM_MATCH, decision.humanDecision)
        val reconciliationRow =
            checkNotNull(audit.reconciliation) {
                "${facts.PREFIX} the confirmed leg must carry its posting_reconciliation row"
            }
        assertEquals("${facts.PREFIX} the leg's reconciliation status must be CHECKED", P408ReconciliationStatus.CHECKED, reconciliationRow.status)
        val checkedHistory =
            checkNotNull(reconciliationRow.history.lastOrNull { entry -> entry.status == P408ReconciliationStatus.CHECKED }) {
                "${facts.PREFIX} the status history must record the CHECKED state"
            }
        val portSourceLine =
            facts.auditSourceLine(
                link.source.importInputRef,
                link.source.recordOrdinal,
                link.source.recordKind,
                link.source.occurredAt,
            )
        val portBasisLine = facts.auditBasisLine(decision.matchBasis)
        val portResponsibilityLine = facts.auditResponsibilityLine(link.responsibility)
        val portHumanDecisionLine = facts.auditHumanDecisionLine(decision.humanDecision, decision.decidedAt)
        val portHistoryLine = p807Q2Flow2StatusHistoryLine(checkedHistory.sequence, checkedHistory.status, checkedHistory.occurredAt)
        assertEquals(
            "${facts.PREFIX} the port's source line must be the seeded source the flow expects",
            facts.AUDIT_SOURCE_LINE,
            portSourceLine,
        )
        assertEquals(
            "${facts.PREFIX} the port's basis line must be the frozen five-field basis",
            facts.MATCH_BASIS_LINE,
            portBasisLine,
        )
        assertEquals(
            "${facts.PREFIX} the port's duty line must be the in leg's unique duty",
            facts.FOUR_ELEMENT_RESPONSIBILITY_LINE,
            portResponsibilityLine,
        )
        sequenceOf(
            facts.AUDIT_HEADLINE,
            facts.AUDIT_SOURCE_LINE,
            portResponsibilityLine,
            portBasisLine,
            facts.NATURAL_DAY_DISTANCE,
            portHumanDecisionLine,
            facts.HISTORY_HEADER,
            portHistoryLine,
        ).forEach { line ->
            ui.seek(line)
            assertTrue("${facts.PREFIX} the audit surface must show the port's own line verbatim [$line]", ui.hasText(line))
        }
        log(
            "${facts.PREFIX} audit link=${link.linkId} evidence=${link.evidenceId} basis=[${decision.matchBasis.joinToString(";")}] " +
                "distance=${decision.naturalDayDistance} decision=${decision.humanDecision} at=${decision.decidedAt}",
        )

        // ---------------------------------------------------------------- L8: audit re-entry (page-level, Q4)
        ui.backTo(facts.CHECKED_ONE_PREFIX)
        assertTrue("${facts.PREFIX} re-entering the audit surface must open it again", ui.openLegAudit())
        val auditAgain = reconciliation.audit(destinationPostingId)
        assertEquals(
            "${facts.PREFIX} the re-entered audit must locate the same evidence",
            link.evidenceId,
            auditAgain.evidence.singleOrNull()?.evidenceId,
        )
        assertEquals("${facts.PREFIX} the leg must carry exactly one ACTIVE link", 1, reconciliation.activeLinkCount(destinationPostingId))
        ui.seek(facts.AUDIT_SOURCE_LINE)
        assertTrue(
            "${facts.PREFIX} the re-entered audit must still show the original evidence source",
            ui.hasText(facts.AUDIT_SOURCE_LINE),
        )

        // ---------------------------------------------------------------- L9: the review marker + the refusal
        ui.backTo(facts.CHECKED_ONE_PREFIX)
        ui.backTo(facts.ACCOUNT_FLOW_TITLE)
        ui.backTo(facts.ACCOUNTS_GROUP_PREFIX)
        ui.openImportReview()
        ui.seek(facts.USED_FOR_RECONCILIATION)
        assertTrue(
            "${facts.PREFIX} the review row must now be marked 已用于核对既有分录 (spec section 2.7)",
            ui.hasText(facts.USED_FOR_RECONCILIATION),
        )
        assertNull(
            "${facts.PREFIX} the entry-B affordance must be gone once the evidence is linked",
            ui.awaitNoText(facts.EVIDENCE_ENTRY_TITLE),
        )
        ui.openCandidateDetail()
        assertTrue(
            "${facts.PREFIX} the seeded transfer candidate's decision must be completable from the candidate detail",
            ui.completeTransferDecision(facts.DECISION_FROM_ACCOUNT_NAME, P807Q2Flow1Facts.RECONCILE_ACCOUNT_NAME),
        )
        assertTrue("${facts.PREFIX} the candidate's own checkbox must establish the batch selection", ui.selectForBatch())
        assertTrue("${facts.PREFIX} the authorization page must accept the single-item confirmation", ui.authorizeBatch())
        assertTrue("${facts.PREFIX} the authorization page must return to the import review list", ui.returnToReviewList())
        assertTrue(
            "${facts.PREFIX} the retained batch summary must render after the refusal",
            awaitVisibleOrSeek(ui, facts.BATCH_RESULT_PREFIX),
        )
        assertTrue(
            "${facts.PREFIX} the per-item line must refuse the candidate with the double-booking code",
            awaitVisibleOrSeek(ui, facts.BATCH_REJECTED_LINE),
        )
        ui.seek(facts.BATCH_REJECTED_LINE)
        assertTrue(
            "${facts.PREFIX} the per-item line must be the exact refusal copy [${facts.BATCH_REJECTED_LINE}]",
            ui.hasText(facts.BATCH_REJECTED_LINE),
        )
        assertTrue(
            "${facts.PREFIX} the summary must count zero confirmed and one rejected",
            awaitVisibleOrSeek(ui, facts.BATCH_ZERO_CONFIRMED_ONE_REJECTED),
        )

        // ---------------------------------------------------------------- L10: zero accounting effect
        val finalRead = reconciliation.snapshot()
        assertEquals("${facts.PREFIX} the transactions must be item-for-item unchanged", initialRead.formal.transactions, finalRead.formal.transactions)
        assertEquals("${facts.PREFIX} the postings must be item-for-item unchanged", initialRead.formal.legs, finalRead.formal.legs)
        assertEquals("${facts.PREFIX} the balances must be item-for-item unchanged", initialRead.formal.balances, finalRead.formal.balances)
        assertEquals("${facts.PREFIX} the VERSION ids must be item-for-item unchanged", initialRead.versions, finalRead.versions)
        assertEquals(
            "${facts.PREFIX} the candidate status rows must be unchanged (the refusal writes no candidate state)",
            initialRead.formal.candidates,
            finalRead.formal.candidates,
        )
        assertEquals("${facts.PREFIX} the whole formal read must equal the first read", initialRead, finalRead)
        assertEquals("${facts.PREFIX} the evidence must keep exactly one ACTIVE link", 1, reconciliation.activeLinkCount(destinationPostingId))
        val evidenceFinal =
            checkNotNull(reconciliation.evidence(facts.SEEDED_EVIDENCE_ID)) {
                "${facts.PREFIX} the evidence must still be readable after the refusal"
            }
        assertTrue("${facts.PREFIX} the evidence's projection must still be READY", p807Q2Flow2ProjectionReady(evidenceFinal))
        assertEquals(
            "${facts.PREFIX} the candidate must stay pending_confirmation after the refusal",
            "pending_confirmation",
            reconciliation.reviewRow(facts.SEEDED_CANDIDATE_ID).candidateStatus,
        )
        log(
            "${facts.PREFIX} PASS evidence=${facts.SEEDED_EVIDENCE_ID} posting=${destinationPostingId.value} " +
                "activeLinks=${reconciliation.activeLinkCount(destinationPostingId)} candidates=${finalRead.formal.candidates.size} " +
                "transactions=${finalRead.formal.transactionTotal} postings=${finalRead.formal.legs.size}",
        )
    }

    // ------------------------------------------------------------------ read helpers

    /** The first queue page's rows, or a loud failure when the read cannot answer (never an empty claim). */
    private fun firstQueueRows(
        reconciliation: P807Q2Flow2Reconciliation,
        accountId: AccountId,
    ) = when (val result = reconciliation.queuePage(accountId)) {
        is P801AccountReconciliationPageResult.Success -> result.page.rows
        is P801AccountReconciliationPageResult.Failed -> error("the queue page read failed: ${result.failure}")
    }

    /** The whole-account totals, or a loud failure (a failed aggregate is never read as a zero). */
    private fun summaryTotals(
        reconciliation: P807Q2Flow2Reconciliation,
        accountId: AccountId,
    ) = when (val result = reconciliation.summary(accountId)) {
        is P801AccountReconciliationSummaryResult.Success -> result.totals
        is P801AccountReconciliationSummaryResult.Failed -> error("the queue summary read failed: ${result.failure}")
    }

    /**
     * Waits (bounded) until one visible text CONTAINS [text], paying one forward seek per poll
     * while the target sits below the fold. The batch summary is a single line whose tail carries
     * the counts, so an exact-text assertion is deliberately not used there.
     */
    private fun awaitVisibleOrSeek(
        ui: P807Q2Flow2Ui,
        text: String,
        timeoutMillis: Long = TEXT_CONTAINING_TIMEOUT_MILLIS,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ui.visibleTexts().any { line -> line.contains(text) }) return true
            runCatching { ui.seek(text) }
            SystemClock.sleep(UI_POLL_MILLIS)
        }
        ui.dump("awaitVisibleOrSeek")
        return false
    }

    private companion object {
        const val TAG = "UnifiedLedger"

        /** The batch result lands after the per-item dispatch; the wait is bounded, never open. */
        const val TEXT_CONTAINING_TIMEOUT_MILLIS = 60_000L

        /** The UI poll interval (the frozen flow-1 `POLL_MILLIS` value). */
        const val UI_POLL_MILLIS = 500L
    }
}
