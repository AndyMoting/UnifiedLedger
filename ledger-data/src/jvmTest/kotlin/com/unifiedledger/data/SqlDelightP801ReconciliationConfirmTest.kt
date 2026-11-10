package com.unifiedledger.data

import com.unifiedledger.application.P408MatchDisposition
import com.unifiedledger.application.P408Matcher
import com.unifiedledger.application.P408MaterializeResult
import com.unifiedledger.application.P408ProjectionState
import com.unifiedledger.application.P408ReconciliationStatus
import com.unifiedledger.application.P801AccountReconciliationStatusTotal
import com.unifiedledger.application.P801AccountReconciliationSummaryResult
import com.unifiedledger.application.P801CandidateEvidenceResult
import com.unifiedledger.application.P801CandidateProposal
import com.unifiedledger.application.P801CandidateProposalResult
import com.unifiedledger.application.P801ConfirmLinkInput
import com.unifiedledger.application.P801ConfirmLinkRefusal
import com.unifiedledger.application.P801ConfirmLinkResult
import com.unifiedledger.application.P801ConfirmRequestIdentity
import com.unifiedledger.domain.AccountId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * P8-01a i2 confirmation evidence (spec sections 2.5/4; D-308 item 3, vectors V-2/V-3/V-4). The
 * confirm runs the real commit port against a real in-memory database over the real matcher output,
 * so each assertion below is load-bearing:
 *
 *  - one out-leg confirm writes an `evidence_link` (v2, five-field basis, distance 0, window 2) and
 *    a `CHECKED` `posting_reconciliation` whose history is `PENDING` then `CHECKED`, while every
 *    transaction/version/posting-set/posting row and the per-month leg totals stay byte-identical;
 *  - the transaction's OTHER leg keeps no reconciliation row (V-3: legs are reconciled independently);
 *  - the same evidence confirmed again is refused by the store with `P408_EVIDENCE_ALREADY_LINKED`
 *    and a second evidence on the same leg with `P408_POSTING_RESPONSIBILITY_ALREADY_LINKED` — both
 *    are the port's own codes, never pre-empted by the use case;
 *  - an in-leg confirm carries `destination_asset_posting`, decided by direction alone;
 *  - an unprojected evidence is refused before any write.
 *
 * All data synthetic and anonymous, every instant fixed.
 */
class SqlDelightP801ReconciliationConfirmTest {
    @Test
    fun outLegConfirmWritesTheLinkAndCheckedReconciliationWithoutTouchingAccounting() {
        P801ReconciliationFixture().use { fixture ->
            seedTransfer(fixture)
            val evidence = fixture.seedEvidence("out", amountMinor = 5_000L, direction = "out", occurredAt = TRANSFER_AT)
            fixture.materialize(evidence.evidenceId)
            val proposal = proposalFor(fixture, fixture.assetAccountId, "posting-out", evidence.evidenceId)
            val accountingBefore = fixture.accountingCounts()
            val transactionsBefore = fixture.transactionFingerprint()
            val postingsBefore = fixture.postingFingerprint()
            val monthlyBefore = fixture.monthlyTotals()
            assertEquals(
                listOf(P801AccountReconciliationStatusTotal(fixture.cny, P408ReconciliationStatus.PENDING, -5_000L, 1)),
                summaryTotals(fixture),
            )

            val linked = assertIs<P801ConfirmLinkResult.Linked>(fixture.confirm.confirm(input(fixture, proposal)))
            assertNotNull(linked.receipt.linkId)
            assertNotNull(linked.receipt.reconciliationId)
            assertEquals(2L, linked.receipt.historySequence)
            assertEquals("ACCEPTED", linked.receipt.outcome)

            assertEquals(1L, fixture.scalarLong("SELECT count(*) FROM evidence_link WHERE ledger_id = '$LEDGER' AND evidence_id = 'evidence-out'"))
            assertEquals("real_account_posting", fixture.scalarText("SELECT responsibility FROM evidence_link WHERE ledger_id = '$LEDGER' AND evidence_id = 'evidence-out'"))
            assertEquals(2L, fixture.scalarLong("SELECT basis_version FROM evidence_link WHERE ledger_id = '$LEDGER' AND evidence_id = 'evidence-out'"))
            // The port stores the five-field basis as a sorted token set (spec section 2.5).
            assertEquals(
                "account,amount,currency,direction,occurred_at_window",
                fixture.scalarText("SELECT match_basis FROM evidence_link WHERE ledger_id = '$LEDGER' AND evidence_id = 'evidence-out'"),
            )
            assertEquals("posting-out", fixture.scalarText("SELECT posting_id FROM evidence_link WHERE ledger_id = '$LEDGER' AND evidence_id = 'evidence-out'"))
            assertEquals("evidence-out", fixture.scalarText("SELECT evidence_id FROM evidence_link WHERE ledger_id = '$LEDGER'"))
            assertEquals("candidate-out", fixture.scalarText("SELECT candidate_id FROM evidence_link WHERE ledger_id = '$LEDGER'"))
            assertEquals(0L, fixture.scalarLong("SELECT natural_day_distance FROM reconciliation_request_snapshot WHERE ledger_id = '$LEDGER'"))
            assertEquals(2L, fixture.scalarLong("SELECT window_days FROM reconciliation_request_snapshot WHERE ledger_id = '$LEDGER'"))
            assertEquals(5_000L, fixture.scalarLong("SELECT amount_minor FROM reconciliation_request_snapshot WHERE ledger_id = '$LEDGER'"))
            assertEquals(TRANSFER_AT, fixture.scalarText("SELECT source_occurred_at FROM reconciliation_request_snapshot WHERE ledger_id = '$LEDGER'"))
            assertEquals("confirm_match", fixture.scalarText("SELECT human_decision FROM reconciliation_request_snapshot WHERE ledger_id = '$LEDGER'"))

            assertEquals("CHECKED", fixture.scalarText("SELECT status FROM posting_reconciliation WHERE ledger_id = '$LEDGER' AND posting_id = 'posting-out'"))
            assertEquals(2L, fixture.scalarLong("SELECT latest_sequence FROM posting_reconciliation WHERE ledger_id = '$LEDGER' AND posting_id = 'posting-out'"))
            assertEquals(
                "PENDING,CHECKED",
                fixture.scalarText(
                    "SELECT group_concat(status, ',') FROM (SELECT status FROM posting_reconciliation_history " +
                        "WHERE ledger_id = '$LEDGER' ORDER BY sequence)",
                ),
            )
            // V-3: the other leg of the same transaction is still unreconciled.
            assertEquals(0L, fixture.scalarLong("SELECT count(*) FROM posting_reconciliation WHERE ledger_id = '$LEDGER' AND posting_id = 'posting-in'"))
            // The i1 summary rollup follows the write: the account's only leg moves PENDING -> CHECKED.
            assertEquals(
                listOf(P801AccountReconciliationStatusTotal(fixture.cny, P408ReconciliationStatus.CHECKED, -5_000L, 1)),
                summaryTotals(fixture),
            )

            assertEquals(accountingBefore, fixture.accountingCounts())
            assertEquals(transactionsBefore, fixture.transactionFingerprint())
            assertEquals(postingsBefore, fixture.postingFingerprint())
            assertEquals(monthlyBefore, fixture.monthlyTotals())
        }
    }

    @Test
    fun anOffsetBearingSourceTextIsPersistedVerbatimAndTheMatcherSuppliesBasisFields() {
        P801ReconciliationFixture().use { fixture ->
            // Both sides carry the SAME offset-bearing shape, so the frozen shape rule still matches;
            // re-serialising either side to an Instant would produce a different text ("...Z") and a
            // false identity conflict. The persisted raw text must survive into the snapshot.
            fixture.seedTransaction(
                "tx-transfer",
                TRANSFER_AT_OFFSET,
                legs = listOf(P801Leg("posting-out", fixture.assetAccountId.value, -5_000L), P801Leg("posting-in", fixture.otherAssetAccountId.value, 5_000L)),
            )
            val evidence = fixture.seedEvidence("out", amountMinor = 5_000L, direction = "out", occurredAt = TRANSFER_AT_OFFSET)
            fixture.materialize(evidence.evidenceId)
            val proposal = proposalFor(fixture, fixture.assetAccountId, "posting-out", evidence.evidenceId)

            assertIs<P801ConfirmLinkResult.Linked>(fixture.confirm.confirm(input(fixture, proposal)))
            assertEquals(TRANSFER_AT_OFFSET, fixture.scalarText("SELECT occurred_at FROM transaction_version WHERE ledger_id = '$LEDGER'"))
            assertEquals(TRANSFER_AT_OFFSET, fixture.scalarText("SELECT source_occurred_at FROM reconciliation_request_snapshot WHERE ledger_id = '$LEDGER'"))
            // Distance and window are the matcher's own basis (0 natural days, window 2), not a value
            // recomputed from the re-serialised instant.
            assertEquals(0L, fixture.scalarLong("SELECT natural_day_distance FROM reconciliation_request_snapshot WHERE ledger_id = '$LEDGER'"))
            assertEquals(P408Matcher.DEFAULT_WINDOW_DAYS.toLong(), fixture.scalarLong("SELECT window_days FROM reconciliation_request_snapshot WHERE ledger_id = '$LEDGER'"))
            assertEquals(1L, fixture.scalarLong("SELECT count(*) FROM evidence_link WHERE ledger_id = '$LEDGER' AND evidence_id = 'evidence-out'"))
        }
    }

    @Test
    fun anIssuedIdentityReplaysAsNoChangeWithoutWritingASecondLink() {
        P801ReconciliationFixture().use { fixture ->
            seedTransfer(fixture)
            val evidence = fixture.seedEvidence("out", amountMinor = 5_000L, direction = "out", occurredAt = TRANSFER_AT)
            fixture.materialize(evidence.evidenceId)
            val proposal = proposalFor(fixture, fixture.assetAccountId, "posting-out", evidence.evidenceId)
            val identity =
                P801ConfirmRequestIdentity(
                    requestId = "request-p801-retry",
                    linkId = "link-p801-retry",
                    reconciliationId = "reconciliation-p801-retry",
                    confirmedAt = Instant.parse("2026-03-05T00:00:00Z"),
                )

            val first = assertIs<P801ConfirmLinkResult.Linked>(fixture.confirm.confirm(input(fixture, proposal, identity)))
            val linksAfterFirst = fixture.scalarLong("SELECT count(*) FROM evidence_link WHERE ledger_id = '$LEDGER'")
            val historyAfterFirst = fixture.scalarLong("SELECT count(*) FROM posting_reconciliation_history WHERE ledger_id = '$LEDGER'")
            assertEquals(1L, linksAfterFirst)
            assertEquals(1L, fixture.scalarLong("SELECT count(*) FROM posting_reconciliation WHERE ledger_id = '$LEDGER'"))

            // Same requestId AND the same fingerprint (confirmedAt included) -> the store's replay
            // branch answers NoChange and appends nothing.
            val replay = assertIs<P801ConfirmLinkResult.NoChange>(fixture.confirm.confirm(input(fixture, proposal, identity)))
            assertEquals(first.receipt, replay.receipt)
            assertEquals(linksAfterFirst, fixture.scalarLong("SELECT count(*) FROM evidence_link WHERE ledger_id = '$LEDGER'"))
            assertEquals(historyAfterFirst, fixture.scalarLong("SELECT count(*) FROM posting_reconciliation_history WHERE ledger_id = '$LEDGER'"))
            assertEquals(1L, fixture.scalarLong("SELECT count(*) FROM posting_reconciliation WHERE ledger_id = '$LEDGER'"))
        }
    }

    @Test
    fun aRejectedProjectionIsRefusedAsRejectedAndNeverAsUnprojected() {
        P801ReconciliationFixture().use { fixture ->
            seedTransfer(fixture)
            val twin = fixture.seedEvidence("twin", amountMinor = 5_000L, direction = "out", occurredAt = TRANSFER_AT)
            fixture.materialize(twin.evidenceId)
            val twinProposal = proposalFor(fixture, fixture.assetAccountId, "posting-out", twin.evidenceId)
            val rejected = fixture.seedEvidence("rejected", amountMinor = 5_000L, direction = "out", occurredAt = TRANSFER_AT)
            // Source is CNY, target is a USD account: the projection port persists a REJECTED row.
            assertIs<P408MaterializeResult.Rejected>(
                fixture.materialize(rejected.evidenceId, targetAccountId = fixture.usdAccountId, currency = fixture.usd),
            )
            val rejectedRow =
                assertIs<P801CandidateEvidenceResult.Success>(
                    fixture.read.loadCandidateEvidenceById(fixture.ledgerId, rejected.evidenceId),
                ).rows.single()
            assertEquals(P408ProjectionState.REJECTED, assertNotNull(rejectedRow.projection).state)
            assertEquals("P408_PROJECTION_CURRENCY_MISMATCH", rejectedRow.projection?.rejectionCode)

            val refused =
                assertIs<P801ConfirmLinkResult.Refused>(
                    fixture.confirm.confirm(
                        P801ConfirmLinkInput(
                            ledgerId = fixture.ledgerId,
                            evidence = rejectedRow,
                            candidate = twinProposal.match.candidates.single(),
                        ),
                    ),
                )
            // A rejected bind is its own state: the code lives on the projection row, and calling it
            // EVIDENCE_NOT_PROJECTED would misdescribe an evidence that WAS projected (and refused).
            assertEquals(P801ConfirmLinkRefusal.EVIDENCE_PROJECTION_REJECTED, refused.reason)
            assertEquals(0L, fixture.scalarLong("SELECT count(*) FROM evidence_link WHERE ledger_id = '$LEDGER'"))
            assertEquals(0L, fixture.scalarLong("SELECT count(*) FROM posting_reconciliation WHERE ledger_id = '$LEDGER'"))
        }
    }

    @Test
    fun confirmingTheSameEvidenceAgainIsRejectedAsAlreadyLinked() {
        P801ReconciliationFixture().use { fixture ->
            seedTransfer(fixture)
            val evidence = fixture.seedEvidence("out", amountMinor = 5_000L, direction = "out", occurredAt = TRANSFER_AT)
            fixture.materialize(evidence.evidenceId)
            val proposal = proposalFor(fixture, fixture.assetAccountId, "posting-out", evidence.evidenceId)
            assertIs<P801ConfirmLinkResult.Linked>(fixture.confirm.confirm(input(fixture, proposal)))

            // The id source mints a fresh requestId, so this is a real second request, not a replay.
            val rejected = assertIs<P801ConfirmLinkResult.Rejected>(fixture.confirm.confirm(input(fixture, proposal)))
            assertEquals("P408_EVIDENCE_ALREADY_LINKED", rejected.code)
            assertEquals(1L, fixture.scalarLong("SELECT count(*) FROM evidence_link WHERE ledger_id = '$LEDGER'"))
        }
    }

    @Test
    fun anotherEvidenceForTheSameLegAndDutyIsRejectedAsResponsibilityAlreadyLinked() {
        P801ReconciliationFixture().use { fixture ->
            seedTransfer(fixture)
            val first = fixture.seedEvidence("first", amountMinor = 5_000L, direction = "out", occurredAt = TRANSFER_AT)
            val second = fixture.seedEvidence("second", amountMinor = 5_000L, direction = "out", occurredAt = TRANSFER_AT)
            fixture.materialize(first.evidenceId)
            fixture.materialize(second.evidenceId)
            assertIs<P801ConfirmLinkResult.Linked>(fixture.confirm.confirm(input(fixture, proposalFor(fixture, fixture.assetAccountId, "posting-out", first.evidenceId))))

            val rejected =
                assertIs<P801ConfirmLinkResult.Rejected>(
                    fixture.confirm.confirm(input(fixture, proposalFor(fixture, fixture.assetAccountId, "posting-out", second.evidenceId))),
                )
            assertEquals("P408_POSTING_RESPONSIBILITY_ALREADY_LINKED", rejected.code)
            assertEquals(1L, fixture.scalarLong("SELECT count(*) FROM evidence_link WHERE ledger_id = '$LEDGER'"))
            assertEquals("evidence-first", fixture.scalarText("SELECT evidence_id FROM evidence_link WHERE ledger_id = '$LEDGER'"))
        }
    }

    @Test
    fun inLegConfirmCarriesTheDestinationAssetPostingResponsibility() {
        P801ReconciliationFixture().use { fixture ->
            seedTransfer(fixture)
            val evidence = fixture.seedEvidence("in", amountMinor = 5_000L, direction = "in", occurredAt = TRANSFER_AT)
            fixture.materialize(evidence.evidenceId, targetAccountId = fixture.otherAssetAccountId)

            val proposal = proposalFor(fixture, fixture.otherAssetAccountId, "posting-in", evidence.evidenceId)
            val inLeg = proposal.match.candidates.single()
            assertEquals("in", inLeg.posting.direction)
            assertIs<P801ConfirmLinkResult.Linked>(fixture.confirm.confirm(input(fixture, proposal)))
            assertEquals(
                "destination_asset_posting",
                fixture.scalarText("SELECT responsibility FROM evidence_link WHERE ledger_id = '$LEDGER' AND evidence_id = 'evidence-in'"),
            )
            assertEquals("CHECKED", fixture.scalarText("SELECT status FROM posting_reconciliation WHERE ledger_id = '$LEDGER' AND posting_id = 'posting-in'"))
        }
    }

    @Test
    fun anUnprojectedEvidenceIsRefusedBeforeAnyWrite() {
        P801ReconciliationFixture().use { fixture ->
            seedTransfer(fixture)
            val twin = fixture.seedEvidence("twin", amountMinor = 5_000L, direction = "out", occurredAt = TRANSFER_AT)
            fixture.materialize(twin.evidenceId)
            val unprojected = fixture.seedEvidence("unprojected", amountMinor = 5_000L, direction = "out", occurredAt = TRANSFER_AT)
            // The candidate comes from the projected twin; the row handed to the confirm is the
            // unprojected evidence, which has no READY authority and therefore no identity facts.
            val twinProposal = proposalFor(fixture, fixture.assetAccountId, "posting-out", twin.evidenceId)
            val unprojectedRow =
                assertIs<P801CandidateEvidenceResult.Success>(
                    fixture.read.loadCandidateEvidenceById(fixture.ledgerId, unprojected.evidenceId),
                ).rows.single()
            assertNull(unprojectedRow.projection)

            val refused =
                assertIs<P801ConfirmLinkResult.Refused>(
                    fixture.confirm.confirm(
                        P801ConfirmLinkInput(
                            ledgerId = fixture.ledgerId,
                            evidence = unprojectedRow,
                            candidate = twinProposal.match.candidates.single(),
                        ),
                    ),
                )
            assertEquals(P801ConfirmLinkRefusal.EVIDENCE_NOT_PROJECTED, refused.reason)
            assertEquals(0L, fixture.scalarLong("SELECT count(*) FROM evidence_link WHERE ledger_id = '$LEDGER'"))
            assertEquals(0L, fixture.scalarLong("SELECT count(*) FROM posting_reconciliation WHERE ledger_id = '$LEDGER'"))
        }
    }

    private fun seedTransfer(fixture: P801ReconciliationFixture) {
        fixture.seedTransaction(
            "tx-transfer",
            TRANSFER_AT,
            legs = listOf(P801Leg("posting-out", fixture.assetAccountId.value, -5_000L), P801Leg("posting-in", fixture.otherAssetAccountId.value, 5_000L)),
        )
    }

    /** The one `PROPOSED_MATCH` proposal of [evidenceId] for [postingId] (the matcher's own output). */
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
        identity: P801ConfirmRequestIdentity? = null,
    ) = P801ConfirmLinkInput(
        ledgerId = fixture.ledgerId,
        evidence = proposal.evidence,
        candidate = proposal.match.candidates.single(),
        identity = identity,
    )

    private fun summaryTotals(fixture: P801ReconciliationFixture): List<P801AccountReconciliationStatusTotal> =
        assertIs<P801AccountReconciliationSummaryResult.Success>(
            fixture.read.loadAccountReconciliationSummary(fixture.ledgerId, fixture.assetAccountId),
        ).totals

    private companion object {
        const val LEDGER = P801_FIXTURE_LEDGER
        const val TRANSFER_AT = "2026-03-01T02:00:00Z"

        /** Same instant as [TRANSFER_AT], but in the shape a real source writer persists. */
        const val TRANSFER_AT_OFFSET = "2026-03-01T10:00:00+08:00"
    }
}
