package com.unifiedledger.data

import com.unifiedledger.application.P408MatchDisposition
import com.unifiedledger.application.P408ReconciliationStatus
import com.unifiedledger.application.P801AccountReconciliationPageResult
import com.unifiedledger.application.P801AccountReconciliationStatusTotal
import com.unifiedledger.application.P801AccountReconciliationSummaryResult
import com.unifiedledger.application.P801CandidateProposal
import com.unifiedledger.application.P801CandidateProposalResult
import com.unifiedledger.application.P801ConfirmLinkInput
import com.unifiedledger.application.P801ConfirmLinkResult
import com.unifiedledger.application.isReconciliationEligible
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.TransactionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P8-01b widened reconciliation write face (spec sections 2.1-2.8; acceptance vectors V-12..V-19).
 * Every case runs the real commit port, the real read adapter and the real matcher against a real
 * in-memory database, so each assertion is about SQL and writes rather than about a stub.
 *
 *  - V-12 a NON-transfer asset leg is proposed and linked, and the accounting rows are unchanged;
 *  - V-13 a leg whose `posting_reconciliation` row already exists links under the ROW's identity;
 *  - V-14 a leg whose transaction is voided after the candidate was read is refused, zero writes;
 *  - V-16 queue, totals and detail agree on composite-marked legs;
 *  - V-17 the report read face still describes transfer legs only (registered oracle-only);
 *  - V-18 the rg03 false override hides a leg from the queue and the totals, the write face still links it;
 *  - V-19 the frozen correction predicate keeps both its kind filter and its missing effective gate.
 *
 * All data synthetic and anonymous; every instant fixed.
 */
class P801WidenedReconciliationTest {
    @Test
    fun v12ANonTransferAssetLegIsProposedAndLinked() {
        P801ReconciliationFixture().use { fixture ->
            fixture.seedTransaction("tx-expense", AT, kind = "EXPENSE", legs = listOf(P801Leg("posting-expense", fixture.assetAccountId.value, -5_000L)))
            val evidence = fixture.seedEvidence("expense", amountMinor = 5_000L, direction = "out", occurredAt = AT)
            fixture.materialize(evidence.evidenceId)
            val accountingBefore = fixture.accountingCounts()
            val postingsBefore = fixture.postingFingerprint()
            val transactionsBefore = fixture.transactionFingerprint()
            val monthlyBefore = fixture.monthlyTotals()

            val proposal = proposalFor(fixture, fixture.assetAccountId, "posting-expense", evidence.evidenceId)
            val linked = assertIs<P801ConfirmLinkResult.Linked>(fixture.confirm.confirm(input(fixture, proposal)))
            assertEquals(2L, linked.receipt.historySequence)

            assertEquals("CHECKED", fixture.reconciliationStatusOf("posting-expense"))
            assertEquals(
                "real_account_posting",
                fixture.scalarText("SELECT responsibility FROM evidence_link WHERE ledger_id = '$LEDGER'"),
            )
            // The widened face writes reconciliation state only: no accounting row may move.
            assertEquals(accountingBefore, fixture.accountingCounts())
            assertEquals(postingsBefore, fixture.postingFingerprint())
            assertEquals(transactionsBefore, fixture.transactionFingerprint())
            assertEquals(monthlyBefore, fixture.monthlyTotals())
        }
    }

    @Test
    fun v13AnExistingReconciliationRowKeepsItsOwnIdentity() {
        P801ReconciliationFixture().use { fixture ->
            fixture.seedTransaction("tx-transfer", AT, legs = listOf(P801Leg("posting-out", fixture.assetAccountId.value, -5_000L)))
            fixture.seedPendingReconciliation("posting-out", "reconciliation-v23-seed")
            val evidence = fixture.seedEvidence("out", amountMinor = 5_000L, direction = "out", occurredAt = AT)
            fixture.materialize(evidence.evidenceId)

            val proposal = proposalFor(fixture, fixture.assetAccountId, "posting-out", evidence.evidenceId)
            val linked = assertIs<P801ConfirmLinkResult.Linked>(fixture.confirm.confirm(input(fixture, proposal)))
            // The use case minted a fresh identity; the existing row's identity is authoritative.
            assertEquals("reconciliation-v23-seed", linked.receipt.reconciliationId)
            assertEquals(2L, linked.receipt.historySequence)
            assertEquals(1L, fixture.scalarLong("SELECT count(*) FROM posting_reconciliation WHERE ledger_id = '$LEDGER'"))
            assertEquals(
                "PENDING,CHECKED",
                fixture.scalarText(
                    "SELECT group_concat(status, ',') FROM (SELECT status FROM posting_reconciliation_history " +
                        "WHERE ledger_id = '$LEDGER' ORDER BY sequence)",
                ),
            )
        }
    }

    @Test
    fun v14ALegVoidedAfterTheCandidateWasReadIsRefusedWithZeroWrites() {
        P801ReconciliationFixture().use { fixture ->
            fixture.seedTransaction("tx-transfer", AT, legs = listOf(P801Leg("posting-out", fixture.assetAccountId.value, -5_000L)))
            val evidence = fixture.seedEvidence("out", amountMinor = 5_000L, direction = "out", occurredAt = AT)
            fixture.materialize(evidence.evidenceId)
            // TOCTOU: the candidate is read while the transaction is still effective.
            val proposal = proposalFor(fixture, fixture.assetAccountId, "posting-out", evidence.evidenceId)
            fixture.voidTransaction("tx-transfer")

            val rejected = assertIs<P801ConfirmLinkResult.Rejected>(fixture.confirm.confirm(input(fixture, proposal)))
            assertEquals("P408_POSTING_NOT_ELIGIBLE", rejected.code)
            assertEquals(0L, fixture.scalarLong("SELECT count(*) FROM evidence_link WHERE ledger_id = '$LEDGER'"))
            assertEquals(0L, fixture.scalarLong("SELECT count(*) FROM posting_reconciliation WHERE ledger_id = '$LEDGER'"))
            assertNull(fixture.reconciliationStatusOf("posting-out"))
        }
    }

    @Test
    fun v16TheQueueTheTotalsAndTheDetailAgreeOnCompositeMarkedLegs() {
        P801ReconciliationFixture().use { fixture ->
            fixture.seedTransaction("tx-transfer", AT, legs = listOf(P801Leg("posting-out", fixture.assetAccountId.value, -5_000L)))
            fixture.seedTransaction("tx-expense", AT, kind = "EXPENSE", legs = listOf(P801Leg("posting-expense", fixture.assetAccountId.value, -3_000L)))
            fixture.seedPendingReconciliation("posting-expense", "reconciliation-expense", status = "CHECKED")

            val rows = fixture.queueRows(fixture.assetAccountId)
            assertEquals(listOf("posting-expense", "posting-out"), rows.map { it.postingId.value }.sorted())
            rows.forEach { row ->
                // The queue's status is exactly the persisted row, or PENDING when the leg has none.
                assertEquals(fixture.reconciliationStatusOf(row.postingId.value) ?: "PENDING", row.status.storageValue)
            }

            val expectedTotals =
                rows.groupBy { it.status }
                    .map { (status, group) -> P801AccountReconciliationStatusTotal(fixture.cny, status, group.sumOf { it.amountMinor }, group.size) }
                    .sortedBy { total -> total.status.name }
            assertEquals(expectedTotals, summaryTotals(fixture).sortedBy { total -> total.status.name })

            // The detail face derives the same status for the same leg through the same row.
            val detailLegs = fixture.detailRead.loadTransactionReconciliationLegs(fixture.ledgerId, TransactionId("tx-expense"))
            val detailLeg = detailLegs.single { row -> row.postingId.value == "posting-expense" }
            assertTrue(isReconciliationEligible(detailLeg))
            assertEquals(P408ReconciliationStatus.CHECKED.storageValue, detailLeg.statusStorageValue)
        }
    }

    @Test
    fun v17TheReportStillDescribesTransferLegsOnly() {
        P801ReconciliationFixture().use { fixture ->
            fixture.seedTransaction("tx-transfer", AT, legs = listOf(P801Leg("posting-out", fixture.assetAccountId.value, -5_000L)))
            fixture.seedTransaction("tx-expense", AT, kind = "EXPENSE", legs = listOf(P801Leg("posting-expense", fixture.assetAccountId.value, -3_000L)))

            val report = fixture.reconciliation.readReconciliationReport(LEDGER)
            // Registered oracle-only (spec section 2.8): the report read face is deliberately NOT
            // aligned with the queue, so the widened write face must not widen it either.
            assertEquals(listOf("posting-out"), report.map { row -> row.postingId })
            assertEquals(P408ReconciliationStatus.PENDING, report.single().status)
            assertEquals(emptyList(), report.single().activeLinkIds)
        }
    }

    @Test
    fun v18TheRg03FalseOverrideHidesTheLegFromTheQueueAndTheTotals() {
        P801ReconciliationFixture().use { fixture ->
            fixture.seedTransaction("tx-transfer", AT, legs = listOf(P801Leg("posting-hidden", fixture.assetAccountId.value, -5_000L)))
            fixture.seedTransaction("tx-expense", AT, kind = "EXPENSE", legs = listOf(P801Leg("posting-visible", fixture.assetAccountId.value, -3_000L)))
            fixture.seedRg03FalseOverride("posting-hidden")

            // The override is applied inside the page CTE, before the keyset predicate and the LIMIT, so
            // the hidden leg neither occupies a page slot nor shortens the cursor's total order.
            val page =
                assertIs<P801AccountReconciliationPageResult.Success>(
                    fixture.read.loadAccountReconciliationPage(fixture.ledgerId, fixture.assetAccountId, after = null, pageSize = 1),
                ).page
            assertEquals(listOf("posting-visible"), page.rows.map { row -> row.postingId.value })
            assertNull(page.nextCursor)
            assertEquals(
                listOf(P801AccountReconciliationStatusTotal(fixture.cny, P408ReconciliationStatus.PENDING, -3_000L, 1)),
                summaryTotals(fixture),
            )

            // Registered asymmetry (spec section 2.7): the write face does not consume the override, so a
            // leg the queue and the totals hide can still be confirmed by identity.
            val evidence = fixture.seedEvidence("hidden", amountMinor = 5_000L, direction = "out", occurredAt = AT)
            fixture.materialize(evidence.evidenceId)
            val proposal =
                assertIs<P801CandidateProposalResult.Success>(
                    fixture.propose.proposeForEvidence(fixture.ledgerId, evidence.evidenceId),
                ).proposals.single()
            assertEquals(P408MatchDisposition.PROPOSED_MATCH, proposal.match.disposition)
            assertEquals("posting-hidden", proposal.match.candidates.single().posting.postingId)
            assertIs<P801ConfirmLinkResult.Linked>(fixture.confirm.confirm(input(fixture, proposal)))
            assertEquals("CHECKED", fixture.reconciliationStatusOf("posting-hidden"))
        }
    }

    @Test
    fun v19TheFrozenCorrectionPredicateKeepsItsKindFilterAndItsMissingEffectiveGate() {
        P801ReconciliationFixture().use { fixture ->
            fixture.seedTransaction("tx-transfer", AT, legs = listOf(P801Leg("posting-out", fixture.assetAccountId.value, -5_000L)))
            fixture.seedTransaction("tx-expense", AT, kind = "EXPENSE", legs = listOf(P801Leg("posting-expense", fixture.assetAccountId.value, -3_000L)))

            // The correction face keeps the OLD query verbatim (spec section 2.6), so it still refuses a
            // non-transfer leg — the widening did not reach it...
            assertNull(fixture.database.ledgerQueries.selectP408PostingIntegrity(LEDGER, "posting-expense").executeAsOneOrNull())
            fixture.voidTransaction("tx-transfer")
            // ...and it still resolves a voided transfer leg, exactly as before: the effective gate was
            // added to the reconciliation face only, never to the correction face.
            assertNotNull(fixture.database.ledgerQueries.selectP408PostingIntegrity(LEDGER, "posting-out").executeAsOneOrNull())
            assertNull(fixture.database.ledgerQueries.selectP801ReconciliationWriteIntegrity(LEDGER, "posting-out").executeAsOneOrNull())
        }
    }

    private fun proposalFor(
        fixture: P801ReconciliationFixture,
        accountId: AccountId,
        postingId: String,
        evidenceId: String,
    ): P801CandidateProposal {
        val proposals =
            assertIs<P801CandidateProposalResult.Success>(
                fixture.propose.proposeForAccountLeg(fixture.ledgerId, fixture.queueRow(accountId, postingId)),
            ).proposals
        val proposal = proposals.first { it.evidence.evidenceId == evidenceId }
        assertEquals(P408MatchDisposition.PROPOSED_MATCH, proposal.match.disposition)
        assertNull(proposal.match.reason)
        return proposal
    }

    private fun input(
        fixture: P801ReconciliationFixture,
        proposal: P801CandidateProposal,
    ) = P801ConfirmLinkInput(
        ledgerId = fixture.ledgerId,
        evidence = proposal.evidence,
        candidate = proposal.match.candidates.single(),
    )

    private fun summaryTotals(fixture: P801ReconciliationFixture): List<P801AccountReconciliationStatusTotal> =
        assertIs<P801AccountReconciliationSummaryResult.Success>(
            fixture.read.loadAccountReconciliationSummary(fixture.ledgerId, fixture.assetAccountId),
        ).totals

    private companion object {
        const val LEDGER = P801_FIXTURE_LEDGER
        const val AT = "2026-03-01T02:00:00Z"
    }
}
