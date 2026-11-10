package com.unifiedledger.data

import com.unifiedledger.application.P408ProjectionState
import com.unifiedledger.application.P801BindEvidenceRefusal
import com.unifiedledger.application.P801BindEvidenceRequest
import com.unifiedledger.application.P801BindEvidenceResult
import com.unifiedledger.domain.AccountId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * P8-01a i2 evidence binding (spec sections 2.4; D-308 item 10). The bind runs the real projection
 * port against a real in-memory database, so each assertion below is load-bearing:
 *
 *  - the first bind is `Accepted` and produces exactly one `READY` projection row with the target
 *    account and the account's catalog currency/precision, while every formal accounting row and
 *    both derived aggregates stay byte-identical (the derived write has zero accounting effect);
 *  - an identical replay is `NoChange` and appends nothing;
 *  - a rejection (source currency vs target account currency) persists its `REJECTED` terminal row
 *    and stays rejected on replay — it never becomes a `READY` row;
 *  - a second bind to a DIFFERENT account after a `READY` projection is refused with zero writes,
 *    because projection content is immutable (spec section 2.4);
 *  - absent source facts and a target account the catalog does not hold are refused before the
 *    projection port is ever reached, so no `REJECTED` row is fabricated for a caller-side gap.
 *
 * All data synthetic and anonymous, every instant fixed.
 */
class SqlDelightP801ReconciliationBindingTest {
    @Test
    fun firstBindIsAcceptedAndChangesNoAccountingRow() {
        P801ReconciliationFixture().use { fixture ->
            fixture.seedTransaction(
                "tx-transfer",
                "2026-03-01T02:00:00Z",
                legs = listOf(P801Leg("posting-out", fixture.assetAccountId.value, -5_000L), P801Leg("posting-in", fixture.otherAssetAccountId.value, 5_000L)),
            )
            val evidence = fixture.seedEvidence("one", amountMinor = 5_000L, direction = "out", occurredAt = "2026-03-01T02:00:00Z")
            val formalBefore = fixture.formalCounts()
            val transactionsBefore = fixture.transactionFingerprint()
            val postingsBefore = fixture.postingFingerprint()
            val monthlyBefore = fixture.monthlyTotals()
            assertEquals(0L, fixture.projectionCount(evidence.evidenceId))

            val bound = assertIs<P801BindEvidenceResult.Bound>(fixture.bind.bind(bindRequest(fixture, evidence.evidenceId)))
            assertEquals(P408ProjectionState.READY, bound.projection.state)
            assertNull(bound.projection.rejectionCode)
            assertEquals(fixture.assetAccountId.value, bound.projection.targetAccountId)
            assertEquals("CNY", bound.projection.currencyCode)
            assertEquals(2, bound.projection.currencyPrecision)
            assertEquals(5_000L, bound.projection.rawAmountMinor)
            assertEquals(5_000L, bound.projection.normalizedAmountMinor)
            assertEquals("out", bound.projection.directionToken)
            assertEquals("request-p801-1", bound.projection.materializationRequestId)
            assertEquals(evidence.sourceId, bound.projection.sourceId)

            assertEquals(1L, fixture.projectionCount(evidence.evidenceId))
            assertEquals(formalBefore, fixture.formalCounts())
            assertEquals(transactionsBefore, fixture.transactionFingerprint())
            assertEquals(postingsBefore, fixture.postingFingerprint())
            assertEquals(monthlyBefore, fixture.monthlyTotals())
        }
    }

    @Test
    fun aHiddenExpenseAccountCannotBeBoundAsAProjectionTarget() {
        P801ReconciliationFixture().use { fixture ->
            val evidence = fixture.seedEvidence("one", amountMinor = 5_000L, direction = "out", occurredAt = "2026-03-01T02:00:00Z")

            // The hidden expense account exists in the catalog but fails the §2.1 composite mark
            // (owned_by_user = 0, real_account = 0, kind = EXPENSE). A READY projection there could
            // never enter the queue or candidates and could never be re-pointed in-app, so the bind
            // must refuse BEFORE any write instead of persisting a dead immutable projection.
            val refused =
                assertIs<P801BindEvidenceResult.Refused>(
                    fixture.bind.bind(bindRequest(fixture, evidence.evidenceId, targetAccountId = fixture.expenseAccountId)),
                )
            assertEquals(P801BindEvidenceRefusal.TARGET_ACCOUNT_NOT_RECONCILABLE, refused.reason)
            assertEquals(0L, fixture.projectionCount(evidence.evidenceId))
        }
    }

    @Test
    fun identicalReplayIsNoChangeAndAppendsNoRow() {
        P801ReconciliationFixture().use { fixture ->
            val evidence = fixture.seedEvidence("one", amountMinor = 5_000L, direction = "out", occurredAt = "2026-03-01T02:00:00Z")
            val first = assertIs<P801BindEvidenceResult.Bound>(fixture.bind.bind(bindRequest(fixture, evidence.evidenceId)))

            val replay = assertIs<P801BindEvidenceResult.NoChange>(fixture.bind.bind(bindRequest(fixture, evidence.evidenceId)))
            assertEquals(first.projection.projectionId, replay.projection.projectionId)
            assertEquals(1L, fixture.projectionCount(evidence.evidenceId))
        }
    }

    @Test
    fun aRejectedSourceStaysRejectedAndNeverBecomesReady() {
        P801ReconciliationFixture().use { fixture ->
            val evidence = fixture.seedEvidence("one", amountMinor = 5_000L, direction = "out", occurredAt = "2026-03-01T02:00:00Z")

            // The import row is CNY and the target account is USD: the projection port refuses it and
            // persists the REJECTED terminal row with its own code.
            val rejected =
                assertIs<P801BindEvidenceResult.Rejected>(
                    fixture.bind.bind(bindRequest(fixture, evidence.evidenceId, targetAccountId = fixture.usdAccountId)),
                )
            assertEquals("P408_PROJECTION_CURRENCY_MISMATCH", rejected.code)
            assertEquals(1L, fixture.projectionCount(evidence.evidenceId))
            val stored = checkNotNull(fixture.projections.readProjection(fixture.ledgerId.value, evidence.evidenceId))
            assertEquals(P408ProjectionState.REJECTED, stored.state)
            assertEquals("P408_PROJECTION_CURRENCY_MISMATCH", stored.rejectionCode)

            val replay = assertIs<P801BindEvidenceResult.NoChange>(fixture.bind.bind(bindRequest(fixture, evidence.evidenceId, targetAccountId = fixture.usdAccountId)))
            assertEquals(P408ProjectionState.REJECTED, replay.projection.state)
            assertEquals(1L, fixture.projectionCount(evidence.evidenceId))
        }
    }

    @Test
    fun aSecondBindToAnotherAccountAfterReadyIsRefused() {
        P801ReconciliationFixture().use { fixture ->
            val evidence = fixture.seedEvidence("one", amountMinor = 5_000L, direction = "out", occurredAt = "2026-03-01T02:00:00Z")
            assertIs<P801BindEvidenceResult.Bound>(fixture.bind.bind(bindRequest(fixture, evidence.evidenceId)))

            val refused =
                assertIs<P801BindEvidenceResult.Refused>(
                    fixture.bind.bind(bindRequest(fixture, evidence.evidenceId, targetAccountId = fixture.otherAssetAccountId)),
                )
            assertEquals(P801BindEvidenceRefusal.PROJECTION_TARGETS_ANOTHER_ACCOUNT, refused.reason)
            assertEquals(1L, fixture.projectionCount(evidence.evidenceId))
            assertEquals(
                fixture.assetAccountId.value,
                checkNotNull(fixture.projections.readProjection(fixture.ledgerId.value, evidence.evidenceId)).targetAccountId,
            )
        }
    }

    @Test
    fun unresolvedSourceFactsAndUnknownTargetsAreRefusedWithZeroWrites() {
        P801ReconciliationFixture().use { fixture ->
            val incomplete = fixture.seedIncompleteEvidence("incomplete")
            assertEquals(
                P801BindEvidenceRefusal.SOURCE_FACTS_UNRESOLVED,
                assertIs<P801BindEvidenceResult.Refused>(fixture.bind.bind(bindRequest(fixture, incomplete.evidenceId))).reason,
            )
            assertEquals(0L, fixture.projectionCount(incomplete.evidenceId))

            val complete = fixture.seedEvidence("one", amountMinor = 5_000L, direction = "out", occurredAt = "2026-03-01T02:00:00Z")
            assertEquals(
                P801BindEvidenceRefusal.TARGET_ACCOUNT_NOT_IN_CATALOG,
                assertIs<P801BindEvidenceResult.Refused>(
                    fixture.bind.bind(bindRequest(fixture, complete.evidenceId, targetAccountId = AccountId("account-absent"))),
                ).reason,
            )
            assertEquals(0L, fixture.projectionCount(complete.evidenceId))

            assertEquals(
                P801BindEvidenceRefusal.EVIDENCE_NOT_FOUND,
                assertIs<P801BindEvidenceResult.Refused>(fixture.bind.bind(bindRequest(fixture, "evidence-absent"))).reason,
            )
        }
    }

    private fun bindRequest(
        fixture: P801ReconciliationFixture,
        evidenceId: String,
        targetAccountId: AccountId = fixture.assetAccountId,
    ) = P801BindEvidenceRequest(
        ledgerId = fixture.ledgerId,
        evidenceId = evidenceId,
        targetAccountId = targetAccountId,
        materializedAt = Instant.parse("2026-03-04T00:00:00Z"),
    )
}
