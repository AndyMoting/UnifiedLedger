package com.unifiedledger.data

import com.unifiedledger.application.P408MatchConfidence
import com.unifiedledger.application.P408MatchDisposition
import com.unifiedledger.application.P408Matcher
import com.unifiedledger.application.P408MaterializeResult
import com.unifiedledger.application.P408ProjectionState
import com.unifiedledger.application.P801AccountPostingFactsResult
import com.unifiedledger.application.P801BindEvidenceRequest
import com.unifiedledger.application.P801BindEvidenceResult
import com.unifiedledger.application.P801BindableEvidenceResult
import com.unifiedledger.application.P801CandidateEvidenceResult
import com.unifiedledger.application.P801CandidateProposalFailure
import com.unifiedledger.application.P801CandidateProposalResult
import com.unifiedledger.domain.TransactionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P8-01a i2 candidate proposal evidence (spec sections 2.2/2.4; D-308 items 2/3/5). Every test runs
 * the real read adapter and the real frozen [P408Matcher] over a real in-memory database, so each
 * guard below is load-bearing:
 *
 *  - a unique five-field match is `PROPOSED_MATCH` and carries the matcher's own basis, distance,
 *    window, `EXACT` confidence and reason — nothing is recomputed by the use case;
 *  - two same-shape legs of one account inside the window are `AMBIGUOUS` with both candidates and
 *    both distances, never a silently chosen one;
 *  - a source time whose TEXT shape differs from the leg's (or does not parse at all) is
 *    `UNRESOLVED` with the matcher's own `source_time_unresolved`, and nothing is written;
 *  - the presentation order is parsed instant ascending, unparseable last, `evidence_id` ascending
 *    as the tie-break — raw text order would put the offset-bearing row in the wrong place;
 *  - a fee (EXPENSE) leg and another account's leg never enter the candidate list, and another
 *    account's evidence never enters this account's window;
 *  - entry B matches one evidence against its OWN projected account's legs, and refuses an
 *    unprojected, rejected, unknown or anchor-less case with a typed failure instead of an empty
 *    success.
 *
 * All data synthetic and anonymous, every instant fixed.
 */
class SqlDelightP801ReconciliationCandidatesTest {
    @Test
    fun aUniqueMatchCarriesTheMatchersOwnBasisAndExcludesFeeAndForeignLegs() {
        P801ReconciliationFixture().use { fixture ->
            fixture.seedTransaction(
                "tx-transfer",
                "2026-03-01T02:00:00Z",
                legs = listOf(P801Leg("posting-out", fixture.assetAccountId.value, -5_000L), P801Leg("posting-in", fixture.otherAssetAccountId.value, 5_000L)),
            )
            // Same instant and same amount, but the fee leg is on the hidden EXPENSE account and the
            // other leg on a second real account: neither may appear in this account's proposals.
            fixture.seedTransaction(
                "tx-fee",
                "2026-03-01T02:00:00Z",
                legs = listOf(P801Leg("posting-fee", fixture.expenseAccountId.value, -5_000L), P801Leg("posting-foreign", fixture.otherAssetAccountId.value, 5_000L)),
            )
            val evidence = fixture.seedEvidence("one", amountMinor = 5_000L, direction = "out", occurredAt = "2026-03-01T02:00:00Z")
            assertIs<P408MaterializeResult.Accepted>(fixture.materialize(evidence.evidenceId))

            val proposals = success(fixture.propose.proposeForAccountLeg(fixture.ledgerId, fixture.queueRow(fixture.assetAccountId, "posting-out")))
            assertEquals(listOf("evidence-one"), proposals.map { it.evidence.evidenceId })
            val proposal = proposals.single()
            assertEquals(P408MatchDisposition.PROPOSED_MATCH, proposal.match.disposition)
            assertNull(proposal.match.reason)
            val candidate = proposal.match.candidates.single()
            assertEquals("posting-out", candidate.posting.postingId)
            assertEquals("tx-transfer", candidate.posting.transactionId)
            assertEquals("out", candidate.posting.direction)
            assertEquals(-5_000L, candidate.posting.amountMinor)
            // Basis, distance and window are the matcher's verbatim output (spec section 2.5).
            assertEquals(P408Matcher.MATCH_FIELDS, candidate.basis.fields)
            assertEquals(0L, candidate.basis.naturalDayDistance)
            assertEquals(P408Matcher.DEFAULT_WINDOW_DAYS, candidate.basis.windowDays)
            assertEquals(P408MatchConfidence.EXACT, candidate.confidence)
            // Raw facts stay raw; the projection is the only identity authority (D-111/D-112).
            val row = proposal.evidence
            assertEquals(5_000L, row.rawAmountMinor)
            assertEquals("CNY", row.rawCurrencyCode)
            assertEquals(2, row.rawCurrencyPrecision)
            assertEquals("out", row.rawDirectionToken)
            assertEquals("candidate-one", row.candidateId)
            assertEquals("offset_datetime", row.occurredAt.kind)
            assertEquals(Instant.parse("2026-03-01T02:00:00Z"), row.occurredAt.instant)
            assertEquals("2026-03-01T02:00:00Z", row.occurredAt.rawText)
            assertEquals(P408ProjectionState.READY, row.projection?.state)
            assertEquals(false, row.hasActiveEvidenceLink)

            // The window's leg set excludes the fee leg by the composite mark, and the flag proves it.
            val assetFacts =
                assertIs<P801AccountPostingFactsResult.Success>(
                    fixture.read.loadAccountPostingFacts(fixture.ledgerId, fixture.assetAccountId, WINDOW_FROM, WINDOW_TO),
                ).facts
            assertEquals(listOf("posting-out"), assetFacts.map { it.postingId })
            assertTrue(assetFacts.single().eligibleRealAccount)
            assertTrue(assetFacts.single().current)
            val feeFacts =
                assertIs<P801AccountPostingFactsResult.Success>(
                    fixture.read.loadAccountPostingFacts(fixture.ledgerId, fixture.expenseAccountId, WINDOW_FROM, WINDOW_TO),
                ).facts
            assertEquals(listOf("posting-fee"), feeFacts.map { it.postingId })
            assertEquals(false, feeFacts.single().eligibleRealAccount)
        }
    }

    @Test
    fun twoSameShapeLegsInOneWindowAreAmbiguousWithBothDistances() {
        P801ReconciliationFixture().use { fixture ->
            fixture.seedTransaction("tx-a", "2026-03-01T02:00:00Z", legs = listOf(P801Leg("posting-a", fixture.assetAccountId.value, -5_000L)))
            fixture.seedTransaction("tx-b", "2026-03-01T05:00:00Z", legs = listOf(P801Leg("posting-b", fixture.assetAccountId.value, -5_000L)))
            val evidence = fixture.seedEvidence("one", amountMinor = 5_000L, direction = "out", occurredAt = "2026-03-01T03:00:00Z")
            fixture.materialize(evidence.evidenceId)

            val proposal = success(fixture.propose.proposeForAccountLeg(fixture.ledgerId, fixture.queueRow(fixture.assetAccountId, "posting-a"))).single()
            assertEquals(P408MatchDisposition.AMBIGUOUS, proposal.match.disposition)
            assertEquals("ambiguous_multiple_candidates", proposal.match.reason)
            assertEquals(listOf("posting-a", "posting-b"), proposal.match.candidates.map { it.posting.postingId })
            assertEquals(listOf(0L, 0L), proposal.match.candidates.map { it.basis.naturalDayDistance })
        }
    }

    @Test
    fun aSourceTimeWhoseShapeDiffersOrDoesNotParseIsUnresolvedWithTheMatchersReason() {
        P801ReconciliationFixture().use { fixture ->
            fixture.seedTransaction("tx-transfer", "2026-03-01T02:00:00Z", legs = listOf(P801Leg("posting-out", fixture.assetAccountId.value, -5_000L)))
            // Same instant, different TEXT shape (`+08:00` vs `Z`): never comparable (D-103 shape rule).
            val offset = fixture.seedEvidence("offset", amountMinor = 5_000L, direction = "out", occurredAt = "2026-03-01T10:00:00+08:00")
            val broken = fixture.seedEvidence("broken", amountMinor = 5_000L, direction = "out", occurredAt = "not-a-time")
            fixture.materialize(offset.evidenceId)
            fixture.materialize(broken.evidenceId)

            val proposals = success(fixture.propose.proposeForAccountLeg(fixture.ledgerId, fixture.queueRow(fixture.assetAccountId, "posting-out")))
            assertEquals(listOf("evidence-offset", "evidence-broken"), proposals.map { it.evidence.evidenceId })
            proposals.forEach { proposal ->
                assertEquals(P408MatchDisposition.UNRESOLVED, proposal.match.disposition)
                assertEquals("source_time_unresolved", proposal.match.reason)
                assertTrue(proposal.match.candidates.isEmpty())
            }
            val brokenRow = proposals.single { it.evidence.evidenceId == "evidence-broken" }.evidence
            assertNull(brokenRow.occurredAt.instant)
            assertEquals("local_datetime", brokenRow.occurredAt.kind)
        }
    }

    @Test
    fun presentationOrderIsParsedInstantThenEvidenceIdWithUnparseableLast() {
        P801ReconciliationFixture().use { fixture ->
            fixture.seedTransaction("tx-transfer", "2026-03-01T02:00:00Z", legs = listOf(P801Leg("posting-out", fixture.assetAccountId.value, -5_000L)))
            listOf(
                "c" to "2026-03-01T03:00:00Z",
                "b" to "2026-03-01T01:00:00Z",
                "m" to "2026-03-01T01:00:00Z",
                "z" to "2026-03-01T01:00:00Z",
                "a" to "not-a-time",
            ).forEach { (key, occurredAt) ->
                fixture.materialize(fixture.seedEvidence(key, amountMinor = 5_000L, direction = "out", occurredAt = occurredAt).evidenceId)
            }

            val ids =
                success(fixture.propose.proposeForAccountLeg(fixture.ledgerId, fixture.queueRow(fixture.assetAccountId, "posting-out")))
                    .map { it.evidence.evidenceId }
            // 01:00Z trio by evidence_id, then 03:00Z, then the unparseable one — never text order and
            // never the seed order.
            assertEquals(listOf("evidence-b", "evidence-m", "evidence-z", "evidence-c", "evidence-a"), ids)
        }
    }

    @Test
    fun anotherAccountsEvidenceNeverEntersThisAccountsWindowAndEntryBMatchesItsOwnAccount() {
        P801ReconciliationFixture().use { fixture ->
            fixture.seedTransaction(
                "tx-transfer",
                "2026-03-01T02:00:00Z",
                legs = listOf(P801Leg("posting-out", fixture.assetAccountId.value, -5_000L), P801Leg("posting-in", fixture.otherAssetAccountId.value, 5_000L)),
            )
            val outgoing = fixture.seedEvidence("out", amountMinor = 5_000L, direction = "out", occurredAt = "2026-03-01T02:00:00Z")
            val incoming = fixture.seedEvidence("in", amountMinor = 5_000L, direction = "in", occurredAt = "2026-03-01T02:00:00Z")
            fixture.materialize(outgoing.evidenceId, targetAccountId = fixture.assetAccountId)
            fixture.materialize(incoming.evidenceId, targetAccountId = fixture.otherAssetAccountId)

            val assetRows =
                assertIs<P801CandidateEvidenceResult.Success>(
                    fixture.read.loadCandidateEvidence(fixture.ledgerId, fixture.assetAccountId, WINDOW_FROM, WINDOW_TO),
                ).rows
            assertEquals(listOf("evidence-out"), assetRows.map { it.evidenceId })
            val otherRows =
                assertIs<P801CandidateEvidenceResult.Success>(
                    fixture.read.loadCandidateEvidence(fixture.ledgerId, fixture.otherAssetAccountId, WINDOW_FROM, WINDOW_TO),
                ).rows
            assertEquals(listOf("evidence-in"), otherRows.map { it.evidenceId })

            val fromAccount = success(fixture.propose.proposeForAccountLeg(fixture.ledgerId, fixture.queueRow(fixture.assetAccountId, "posting-out"))).single()
            assertEquals(listOf("posting-out"), fromAccount.match.candidates.map { it.posting.postingId })
            // Entry B: the account is the evidence's own projection target, so the in-leg is proposed.
            val fromEvidence = success(fixture.propose.proposeForEvidence(fixture.ledgerId, incoming.evidenceId)).single()
            assertEquals(P408MatchDisposition.PROPOSED_MATCH, fromEvidence.match.disposition)
            assertEquals(listOf("posting-in"), fromEvidence.match.candidates.map { it.posting.postingId })
            val incomingCandidate = fromEvidence.match.candidates.single()
            assertEquals("in", incomingCandidate.posting.direction)
        }
    }

    @Test
    fun entryBRefusesUnprojectedRejectedUnknownAndAnchorlessCases() {
        P801ReconciliationFixture().use { fixture ->
            fixture.seedTransaction("tx-transfer", "2026-03-01T02:00:00Z", legs = listOf(P801Leg("posting-out", fixture.assetAccountId.value, -5_000L)))
            val unprojected = fixture.seedEvidence("unprojected", amountMinor = 5_000L, direction = "out", occurredAt = "2026-03-01T02:00:00Z")
            val rejected = fixture.seedEvidence("rejected", amountMinor = 5_000L, direction = "out", occurredAt = "2026-03-01T02:00:00Z")
            val rejectedResult =
                assertIs<P408MaterializeResult.Rejected>(
                    fixture.materialize(rejected.evidenceId, targetAccountId = fixture.usdAccountId, currency = fixture.usd),
                )
            assertEquals("P408_PROJECTION_CURRENCY_MISMATCH", rejectedResult.code)

            assertEquals(
                P801CandidateProposalFailure.EVIDENCE_NOT_PROJECTED,
                failure(fixture.propose.proposeForEvidence(fixture.ledgerId, unprojected.evidenceId)),
            )
            assertEquals(
                P801CandidateProposalFailure.EVIDENCE_PROJECTION_REJECTED,
                failure(fixture.propose.proposeForEvidence(fixture.ledgerId, rejected.evidenceId)),
            )
            assertEquals(
                P801CandidateProposalFailure.EVIDENCE_NOT_FOUND,
                failure(fixture.propose.proposeForEvidence(fixture.ledgerId, "evidence-absent")),
            )
            // A queue row whose occurred_at no longer covers its own leg cannot seed a window.
            val stale = fixture.queueRow(fixture.assetAccountId, "posting-out").copy(occurredAt = Instant.parse("2026-05-01T02:00:00Z"))
            assertEquals(
                P801CandidateProposalFailure.LEG_NOT_IN_QUEUE,
                failure(fixture.propose.proposeForAccountLeg(fixture.ledgerId, stale)),
            )
        }
    }

    @Test
    fun aCandidateBeyondTheOldThreeDayPrefilterKeepsTheAmbiguity() {
        P801ReconciliationFixture().use { fixture ->
            fixture.seedTransaction("tx-anchor", "2026-03-01T02:00:00Z", legs = listOf(P801Leg("posting-out", fixture.assetAccountId.value, -5_000L)))
            // Geometry (all +08:00 local days): the evidence is 2 natural days after the anchor and
            // still inside entry A's ±3-day evidence window; the second same-shape leg is exactly 2
            // natural days after the evidence, i.e. 4.58 days after the anchor. The old ±3-day
            // POSTING prefetch dropped it, so the matcher saw one candidate and answered
            // PROPOSED_MATCH (a confirmable wrong link) instead of AMBIGUOUS.
            fixture.seedTransaction("tx-boundary", "2026-03-05T15:59:00Z", legs = listOf(P801Leg("posting-boundary", fixture.assetAccountId.value, -5_000L)))
            val evidence = fixture.seedEvidence("one", amountMinor = 5_000L, direction = "out", occurredAt = "2026-03-03T15:59:00Z")
            fixture.materialize(evidence.evidenceId)

            val proposal = success(fixture.propose.proposeForAccountLeg(fixture.ledgerId, fixture.queueRow(fixture.assetAccountId, "posting-out"))).single()
            assertEquals(P408MatchDisposition.AMBIGUOUS, proposal.match.disposition)
            assertEquals("ambiguous_multiple_candidates", proposal.match.reason)
            assertEquals(listOf("posting-boundary", "posting-out"), proposal.match.candidates.map { it.posting.postingId })
            assertEquals(listOf(2L, 2L), proposal.match.candidates.map { it.basis.naturalDayDistance })
        }
    }

    @Test
    fun aNonTransferAnchorIsProposedLikeAnyOtherLeg() {
        P801ReconciliationFixture().use { fixture ->
            fixture.seedTransaction("tx-income", "2026-03-01T02:00:00Z", kind = "INCOME", legs = listOf(P801Leg("posting-income", fixture.assetAccountId.value, -5_000L)))
            val evidence = fixture.seedEvidence("one", amountMinor = 5_000L, direction = "out", occurredAt = "2026-03-01T02:00:00Z")
            fixture.materialize(evidence.evidenceId)
            val leg = fixture.queueRow(fixture.assetAccountId, "posting-income")
            assertEquals(TransactionKind.INCOME, leg.transactionKind)
            // P8-01b removed the kind gate: the widened write face accepts any composite-marked
            // real-account leg, so this anchor is proposed instead of being refused before the read.
            val proposal = success(fixture.propose.proposeForAccountLeg(fixture.ledgerId, leg)).single()
            assertEquals(P408MatchDisposition.PROPOSED_MATCH, proposal.match.disposition)
            assertEquals(listOf("posting-income"), proposal.match.candidates.map { it.posting.postingId })
        }
    }

    @Test
    fun aMixedWindowWithATransferAndAnOrdinaryLegIsAmbiguous() {
        P801ReconciliationFixture().use { fixture ->
            fixture.seedTransaction("tx-transfer", "2026-03-01T02:00:00Z", legs = listOf(P801Leg("posting-out", fixture.assetAccountId.value, -5_000L)))
            // Same account, same amount and shape, but an ordinary income: both legs are confirmable
            // after the widening, so the frozen matcher must answer AMBIGUOUS rather than a single
            // PROPOSED_MATCH that would hide one of them.
            fixture.seedTransaction("tx-ordinary", "2026-03-01T02:00:00Z", kind = "INCOME", legs = listOf(P801Leg("posting-ordinary", fixture.assetAccountId.value, -5_000L)))
            val evidence = fixture.seedEvidence("one", amountMinor = 5_000L, direction = "out", occurredAt = "2026-03-01T02:00:00Z")
            fixture.materialize(evidence.evidenceId)

            val proposal = success(fixture.propose.proposeForAccountLeg(fixture.ledgerId, fixture.queueRow(fixture.assetAccountId, "posting-out"))).single()
            assertEquals(P408MatchDisposition.AMBIGUOUS, proposal.match.disposition)
            assertEquals("ambiguous_multiple_candidates", proposal.match.reason)
            assertEquals(listOf("posting-ordinary", "posting-out"), proposal.match.candidates.map { it.posting.postingId })
            val facts =
                assertIs<P801AccountPostingFactsResult.Success>(
                    fixture.read.loadAccountPostingFacts(fixture.ledgerId, fixture.assetAccountId, WINDOW_FROM, WINDOW_TO),
                ).facts
            assertEquals(listOf("posting-ordinary", "posting-out"), facts.map { it.postingId })
        }
    }

    @Test
    fun anOffsetFreeSourceTimeOutsideTheWindowIsKeptWithNoInstant() {
        P801ReconciliationFixture().use { fixture ->
            // SQLite itself parses this offset-free text as UTC and would place it outside the
            // window; the frozen matcher classifies it `local_datetime`, so the read must KEEP it
            // and the matcher must answer `source_time_unresolved` rather than the row vanishing.
            fixture.seedEvidence("local", amountMinor = 5_000L, direction = "out", occurredAt = "2026-05-01T02:00:00")
            val row =
                assertIs<P801CandidateEvidenceResult.Success>(
                    fixture.read.loadCandidateEvidence(fixture.ledgerId, fixture.assetAccountId, WINDOW_FROM, WINDOW_TO),
                ).rows.single()
            assertEquals("local_datetime", row.occurredAt.kind)
            assertNull(row.occurredAt.instant)
            assertEquals("2026-05-01T02:00:00", row.occurredAt.rawText)
        }
    }

    @Test
    fun theCurrentAndEligibleRealAccountFlagsAreEachLoadBearing() {
        P801ReconciliationFixture().use { fixture ->
            // Version 1's posting still exists; version 2 is current. Both are same-shape and
            // same-amount, so without the `current` flag the matcher would call this AMBIGUOUS.
            fixture.seedSupersededTransaction(
                "tx-two-versions",
                firstOccurredAt = "2026-03-01T02:00:00Z",
                firstLegs = listOf(P801Leg("posting-old-version", fixture.assetAccountId.value, -5_000L)),
                secondOccurredAt = "2026-03-01T02:00:00Z",
                secondLegs = listOf(P801Leg("posting-current-version", fixture.assetAccountId.value, -5_000L)),
            )
            // A leg on the hidden EXPENSE account (not part of the §2.1 mark) is still readable, with
            // `eligible_real_account = false`, so the flag itself carries the exclusion.
            fixture.seedTransaction("tx-fee", "2026-03-01T02:00:00Z", legs = listOf(P801Leg("posting-fee", fixture.expenseAccountId.value, -5_000L)))
            val evidence = fixture.seedEvidence("one", amountMinor = 5_000L, direction = "out", occurredAt = "2026-03-01T02:00:00Z")
            fixture.materialize(evidence.evidenceId)

            val assetFacts =
                assertIs<P801AccountPostingFactsResult.Success>(
                    fixture.read.loadAccountPostingFacts(fixture.ledgerId, fixture.assetAccountId, WINDOW_FROM, WINDOW_TO),
                ).facts
            assertEquals(listOf("posting-current-version", "posting-old-version"), assetFacts.map { it.postingId })
            assertEquals(listOf(true, false), assetFacts.map { it.current })
            val proposal = success(fixture.propose.proposeForAccountLeg(fixture.ledgerId, fixture.queueRow(fixture.assetAccountId, "posting-current-version"))).single()
            assertEquals(P408MatchDisposition.PROPOSED_MATCH, proposal.match.disposition)
            assertEquals(listOf("posting-current-version"), proposal.match.candidates.map { it.posting.postingId })

            val feeFacts =
                assertIs<P801AccountPostingFactsResult.Success>(
                    fixture.read.loadAccountPostingFacts(fixture.ledgerId, fixture.expenseAccountId, WINDOW_FROM, WINDOW_TO),
                ).facts
            assertEquals(listOf("posting-fee"), feeFacts.map { it.postingId })
            assertEquals(false, feeFacts.single().eligibleRealAccount)
        }
    }

    @Test
    fun anOffsetShapedUnparsableTextIsItsOwnKindAndEntryBRefusesIt() {
        P801ReconciliationFixture().use { fixture ->
            val broken = fixture.seedEvidence("broken-offset", amountMinor = 5_000L, direction = "out", occurredAt = "2026-13-45T99:00:00Z")
            fixture.materialize(broken.evidenceId)
            val row =
                assertIs<P801CandidateEvidenceResult.Success>(
                    fixture.read.loadCandidateEvidenceById(fixture.ledgerId, broken.evidenceId),
                ).rows.single()
            assertEquals("unparsable_datetime", row.occurredAt.kind)
            assertTrue(row.occurredAt.offsetPresent)
            assertNull(row.occurredAt.instant)
            assertEquals(
                P801CandidateProposalFailure.SOURCE_TIME_UNRESOLVED,
                failure(fixture.propose.proposeForEvidence(fixture.ledgerId, broken.evidenceId)),
            )
        }
    }

    @Test
    fun anActiveEvidenceLinkIsVisibleOnTheStillListedCandidateRow() {
        P801ReconciliationFixture().use { fixture ->
            fixture.seedTransaction("tx-transfer", "2026-03-01T02:00:00Z", legs = listOf(P801Leg("posting-out", fixture.assetAccountId.value, -5_000L)))
            val evidence = fixture.seedEvidence("one", amountMinor = 5_000L, direction = "out", occurredAt = "2026-03-01T02:00:00Z")
            fixture.materialize(evidence.evidenceId)
            fixture.seedActiveLink("active", evidence.evidenceId, "posting-out", "tx-transfer")

            val row =
                assertIs<P801CandidateEvidenceResult.Success>(
                    fixture.read.loadCandidateEvidenceById(fixture.ledgerId, evidence.evidenceId),
                ).rows.single()
            assertEquals(true, row.hasActiveEvidenceLink)
            // Still listed (spec section 2.4: an already-linked evidence stays visible and is
            // presented as no longer confirmable, rather than disappearing).
            assertEquals("evidence-one", row.evidenceId)
        }
    }

    /**
     * P8-01a i3a F1 (spec section 2.4; V-10 账户 → 队列 → 绑定): the leg-anchored bind list is the
     * ONE read the entry A bind step offers. It must be exactly the unprojected, unlinked evidence of
     * the leg's account/window — a READY row is a candidate already, a linked-but-unprojected row may
     * not be bound again, and another account's projection is unreachable for this leg. Binding the
     * one offered row shrinks the list to empty AND makes it a candidate (the F1d chain).
     */
    @Test
    fun theLegBindListHoldsOnlyUnprojectedUnlinkedEvidenceAndShrinksAfterBinding() {
        P801ReconciliationFixture().use { fixture ->
            fixture.seedTransaction("tx-transfer", "2026-03-01T02:00:00Z", legs = listOf(P801Leg("posting-out", fixture.assetAccountId.value, -5_000L)))
            val unprojected = fixture.seedEvidence("unprojected", amountMinor = 5_000L, direction = "out", occurredAt = "2026-03-01T02:00:00Z")
            val projected = fixture.seedEvidence("projected", amountMinor = 5_000L, direction = "out", occurredAt = "2026-03-01T02:00:00Z")
            fixture.materialize(projected.evidenceId)
            // Unprojected BUT already linked: must never be offered for binding.
            val linked = fixture.seedEvidence("linked", amountMinor = 5_000L, direction = "out", occurredAt = "2026-03-01T02:00:00Z")
            fixture.seedActiveLink("linked", linked.evidenceId, "posting-out", "tx-transfer")
            // Projected to ANOTHER account: the read excludes it before the filter can see it.
            val other = fixture.seedEvidence("other", amountMinor = 5_000L, direction = "out", occurredAt = "2026-03-01T02:00:00Z")
            fixture.materialize(other.evidenceId, targetAccountId = fixture.otherAssetAccountId)

            val leg = fixture.queueRow(fixture.assetAccountId, "posting-out")
            assertEquals(
                listOf("evidence-unprojected"),
                assertIs<P801BindableEvidenceResult.Success>(fixture.propose.bindableEvidenceForAccountLeg(fixture.ledgerId, leg))
                    .rows
                    .map { row -> row.evidenceId },
            )

            // The real bind write (the one derived write of the slice) …
            assertIs<P801BindEvidenceResult.Bound>(
                fixture.bind.bind(
                    P801BindEvidenceRequest(
                        ledgerId = fixture.ledgerId,
                        evidenceId = unprojected.evidenceId,
                        targetAccountId = fixture.assetAccountId,
                        materializedAt = P801_FIXED_NOW,
                    ),
                ),
            )
            // … removes it from the bind list (it now has a READY projection) …
            assertEquals(
                emptyList(),
                assertIs<P801BindableEvidenceResult.Success>(fixture.propose.bindableEvidenceForAccountLeg(fixture.ledgerId, leg))
                    .rows
                    .map { row -> row.evidenceId },
            )
            // … and makes it a candidate of the SAME leg (spec section 2.4 / F1d).
            val proposal =
                success(fixture.propose.proposeForAccountLeg(fixture.ledgerId, leg))
                    .single { it.evidence.evidenceId == "evidence-unprojected" }
            assertEquals(P408MatchDisposition.PROPOSED_MATCH, proposal.match.disposition)
            assertEquals(listOf("posting-out"), proposal.match.candidates.map { candidate -> candidate.posting.postingId })
        }
    }

    private fun success(result: P801CandidateProposalResult) = assertIs<P801CandidateProposalResult.Success>(result).proposals

    private fun failure(result: P801CandidateProposalResult) = assertIs<P801CandidateProposalResult.Failed>(result).reason

    private companion object {
        /** The widened matcher window the reads are called with directly (spec section 2.4). */
        val WINDOW_FROM: Instant = Instant.parse("2026-02-25T00:00:00Z")
        val WINDOW_TO: Instant = Instant.parse("2026-03-05T00:00:00Z")
    }
}
