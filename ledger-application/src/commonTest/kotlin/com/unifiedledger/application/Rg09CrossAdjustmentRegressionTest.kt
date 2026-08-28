package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSet
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.Transaction
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersion
import com.unifiedledger.domain.TransactionVersionId
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * Dedicated RG-09 regression coverage for the two quality-reviewer-driven reconciliation
 * semantics (IQR-006, IQR-007):
 *
 *  - IQR-006: each observation's requiredPostingIds is limited to THIS adjustment's own
 *    allocations; postings allocated to (and evidenced for) another adjustment must never
 *    satisfy this adjustment's fully_reconciled predicate.
 *  - IQR-007: hasUnallocatedRealTransfer is limited to THIS adjustment's target account /
 *    currency / direction / effective-at range and only unallocated ACCOUNT_TRANSFER records;
 *    an unallocated transfer outside that scope must not change this adjustment's
 *    reconciliation state.
 *
 * All expectations below mirror the frozen golden semantics in golden/rules/rg-09.json
 * (canonical_states / main_path / evidence_path) and the state_derivation_cases table.
 * Every test runs against ONE Rg09Runtime instance (one shared ledger) with operations
 * applied sequentially, so any cross-observation state leak would surface as a failure.
 */
class Rg09CrossAdjustmentRegressionTest {
    @Test
    fun `frozen golden main path and evidence path transitions hold on one runtime`() {
        val fixture = loadFixture()
        val runtime = Rg09Runtime(fixture.catalog, fixture.openingTransactions)
        val main = fixture.operations
        assertEquals(
            listOf(
                "preview-rg09",
                "confirm-adjustment-rg09",
                "transfer-confirmation-rg09",
                "explanation-confirmation-rg09",
                "second-transfer-confirmation-rg09",
                "second-explanation-confirmation-rg09",
            ),
            main.map { it.id },
        )
        val evidenceOps = fixture.allOperations.filter { it.sourcePath.startsWith("$.evidence_path.") }
        assertEquals(
            listOf(
                "link-first_transfer_asset_a-rg09",
                "link-first_transfer_asset_b-rg09",
                "link-second_transfer_asset_a-rg09",
                "link-second_transfer_asset_b-rg09",
            ),
            evidenceOps.map { it.id },
        )

        // preview -> difference_pending_confirmation (canonical state state-rg09-previewed)
        assertIs<Rg09ExecutionResult.Accepted>(runtime.commit(main[0].operation))
        var snapshot = runtime.snapshot()
        assertEquals("difference_pending_confirmation", observationState(snapshot))
        assertEquals("30.00", snapshot.reconciliation.getValue("remaining_adjustment"))
        assertEquals(
            "pending_confirmation",
            snapshot.candidates.single { it.id.value == "candidate-adjustment-rg09" }.status,
        )

        // confirmation -> balanced_with_unexplained_adjustment (state-rg09-adjustment-confirmed)
        assertIs<Rg09ExecutionResult.Accepted>(runtime.commit(main[1].operation))
        snapshot = runtime.snapshot()
        assertEquals("balanced_with_unexplained_adjustment", observationState(snapshot))
        assertEquals("30.00", snapshot.reconciliation.getValue("remaining_adjustment"))
        assertEquals(
            "confirmed",
            snapshot.candidates.single { it.id.value == "candidate-adjustment-rg09" }.status,
        )
        assertAdjustment(snapshot, "adjustment-rg09", "open", 0L, 3_000L)

        // transfer_confirmation -> difference_pending_explanation_confirmation: the confirmed
        // real transfer is eligible for posting reconciliation immediately, before allocation.
        assertIs<Rg09ExecutionResult.Accepted>(runtime.commit(main[2].operation))
        snapshot = runtime.snapshot()
        assertEquals("difference_pending_explanation_confirmation", observationState(snapshot))
        assertEquals("30.00", snapshot.reconciliation.getValue("remaining_adjustment"))
        assertEquals("pending_evidence", snapshot.reconciliation.getValue("posting-transfer-a-rg09"))
        assertEquals("pending_evidence", snapshot.reconciliation.getValue("posting-transfer-b-rg09"))

        // explanation_confirmation -> balanced_with_unexplained_adjustment with the first
        // transfer's postings pending evidence (state-rg09-partially-explained)
        assertIs<Rg09ExecutionResult.Accepted>(runtime.commit(main[3].operation))
        snapshot = runtime.snapshot()
        assertEquals("balanced_with_unexplained_adjustment", observationState(snapshot))
        assertEquals("10.00", snapshot.reconciliation.getValue("remaining_adjustment"))
        assertEquals("pending_evidence", snapshot.reconciliation.getValue("posting-transfer-a-rg09"))
        assertEquals("pending_evidence", snapshot.reconciliation.getValue("posting-transfer-b-rg09"))
        assertAdjustment(snapshot, "adjustment-rg09", "partially_explained", 2_000L, 1_000L)

        // second_transfer_confirmation -> difference_pending_explanation_confirmation
        // (state-rg09-second-transfer-confirmed): the second real transfer is unallocated and
        // inside the adjustment's scope, so hasUnallocatedRealTransfer becomes true.
        assertIs<Rg09ExecutionResult.Accepted>(runtime.commit(main[4].operation))
        snapshot = runtime.snapshot()
        assertEquals(
            "difference_pending_explanation_confirmation",
            observationState(snapshot),
        )
        assertEquals("10.00", snapshot.reconciliation.getValue("remaining_adjustment"))
        assertEquals("pending_evidence", snapshot.reconciliation.getValue("posting-transfer-a-rg09-remaining"))
        assertEquals("pending_evidence", snapshot.reconciliation.getValue("posting-transfer-b-rg09-remaining"))

        // second_explanation_confirmation -> evidence_incomplete (state-rg09-fully-explained-unreconciled):
        // adjustment is fully explained but its own required postings are not yet evidenced.
        assertIs<Rg09ExecutionResult.Accepted>(runtime.commit(main[5].operation))
        snapshot = runtime.snapshot()
        assertEquals("evidence_incomplete", observationState(snapshot))
        assertEquals("0.00", snapshot.reconciliation.getValue("remaining_adjustment"))
        assertAdjustment(snapshot, "adjustment-rg09", "fully_explained", 3_000L, 0L)

        // Evidence path: every matched posting keeps the state at evidence_incomplete until
        // the last required posting is matched (state-rg09-fully-explained-evidence-1..3),
        // then fully_reconciled (state-rg09-fully-explained).
        assertEquals("evidence_incomplete", observationState(snapshot))
        assertIs<Rg09ExecutionResult.Accepted>(runtime.commit(evidenceOps[0].operation))
        snapshot = runtime.snapshot()
        assertEquals("evidence_incomplete", observationState(snapshot))
        assertEquals("matched", snapshot.reconciliation.getValue("posting-transfer-a-rg09"))
        assertEquals("pending_evidence", snapshot.reconciliation.getValue("posting-transfer-b-rg09"))

        assertIs<Rg09ExecutionResult.Accepted>(runtime.commit(evidenceOps[1].operation))
        snapshot = runtime.snapshot()
        assertEquals("evidence_incomplete", observationState(snapshot))
        assertEquals("matched", snapshot.reconciliation.getValue("posting-transfer-b-rg09"))

        assertIs<Rg09ExecutionResult.Accepted>(runtime.commit(evidenceOps[2].operation))
        snapshot = runtime.snapshot()
        assertEquals("evidence_incomplete", observationState(snapshot))
        assertEquals("matched", snapshot.reconciliation.getValue("posting-transfer-a-rg09-remaining"))
        assertEquals("pending_evidence", snapshot.reconciliation.getValue("posting-transfer-b-rg09-remaining"))

        assertIs<Rg09ExecutionResult.Accepted>(runtime.commit(evidenceOps[3].operation))
        snapshot = runtime.snapshot()
        assertEquals("fully_reconciled", observationState(snapshot))
        assertEquals("matched", snapshot.reconciliation.getValue("posting-transfer-b-rg09-remaining"))
    }

    @Test
    fun `postings allocated to adjustment A do not satisfy adjustment B required postings`() {
        val scenario = RegressionScenario()
        val a = scenario.account("asset-a", 130_00L)

        // Observation A: preview then confirm adjustment A (+30.00 on asset-a).
        scenario.assertObservationState(a, "difference_pending_confirmation")
        scenario.confirmAdjustment(a)
        scenario.assertObservationState(a, "balanced_with_unexplained_adjustment")

        // Observation B: preview and confirm adjustment B (+80.00 on asset-b) on the SAME
        // runtime. B's preview must not disturb A's reconciliation state.
        val b = scenario.account("asset-b", 130_00L)
        scenario.assertObservationState(b, "difference_pending_confirmation")
        scenario.assertObservationState(a, "balanced_with_unexplained_adjustment")
        scenario.confirmAdjustment(b)
        scenario.assertObservationState(b, "balanced_with_unexplained_adjustment")
        scenario.assertObservationState(a, "balanced_with_unexplained_adjustment")

        // Real transfer T_A (20.00 into asset-a): A immediately awaits explicit allocation;
        // B is unaffected because its target posting direction is wrong.
        val transferA = scenario.transfer("reg-transfer-a", AccountId("asset-a"), AccountId("asset-b"), 2_000L)
        scenario.assertObservationState(a, "difference_pending_explanation_confirmation")
        scenario.assertObservationState(b, "balanced_with_unexplained_adjustment")

        // Allocate T_A to adjustment A only; T_A's postings become pending_evidence.
        scenario.allocate(a, transferA.transactionId, 2_000L)
        scenario.assertAdjustment(a, "partially_explained", 2_000L, 1_000L)
        scenario.assertObservationState(a, "balanced_with_unexplained_adjustment")
        scenario.assertObservationState(b, "balanced_with_unexplained_adjustment")
        assertEquals("pending_evidence", scenario.state("posting-reg-transfer-a-source"))
        assertEquals("pending_evidence", scenario.state("posting-reg-transfer-a-destination"))

        // Evidence for T_A's postings matches only A's required postings.
        scenario.linkEvidence(transferA, AccountId("asset-b"), "posting-reg-transfer-a-source", -2_000L, "decrease")
        scenario.linkEvidence(transferA, AccountId("asset-a"), "posting-reg-transfer-a-destination", 2_000L, "increase")
        scenario.assertObservationState(a, "balanced_with_unexplained_adjustment")
        scenario.assertObservationState(b, "balanced_with_unexplained_adjustment")

        // Second real transfer T_A2 (10.00 into asset-a): unallocated and inside A's scope,
        // so A flips to difference_pending_explanation_confirmation; B is unaffected.
        val transferA2 = scenario.transfer("reg-transfer-a2", AccountId("asset-a"), AccountId("asset-b"), 1_000L)
        scenario.assertObservationState(a, "difference_pending_explanation_confirmation")
        scenario.assertObservationState(b, "balanced_with_unexplained_adjustment")

        // Allocate T_A2 to A: A is fully explained (remaining 0) but not yet evidenced.
        scenario.allocate(a, transferA2.transactionId, 1_000L)
        scenario.assertAdjustment(a, "fully_explained", 3_000L, 0L)
        scenario.assertObservationState(a, "evidence_incomplete")
        scenario.assertObservationState(b, "balanced_with_unexplained_adjustment")

        // Evidence for T_A2's postings: A becomes fully_reconciled. B must stay balanced:
        // B has no allocations, so A's matched postings must not leak into B's required set.
        scenario.linkEvidence(transferA2, AccountId("asset-b"), "posting-reg-transfer-a2-source", -1_000L, "decrease")
        scenario.linkEvidence(transferA2, AccountId("asset-a"), "posting-reg-transfer-a2-destination", 1_000L, "increase")
        scenario.assertObservationState(a, "fully_reconciled")
        scenario.assertObservationState(b, "balanced_with_unexplained_adjustment")

        // Real transfer T_B (80.00 into asset-b) is outside A's account scope: A stays
        // fully_reconciled even though T_B is unallocated.
        val transferB = scenario.transfer("reg-transfer-b", AccountId("asset-b"), AccountId("asset-a"), 8_000L)
        scenario.assertObservationState(a, "fully_reconciled")
        scenario.assertObservationState(b, "difference_pending_explanation_confirmation")

        // Allocate T_B to B: B is fully explained with its own required postings pending.
        // A's already-matched postings must not satisfy B (IQR-006), and B's pending postings
        // must not pull A back from fully_reconciled.
        scenario.allocate(b, transferB.transactionId, 8_000L)
        scenario.assertAdjustment(b, "fully_explained", 8_000L, 0L)
        scenario.assertObservationState(b, "evidence_incomplete")
        scenario.assertObservationState(a, "fully_reconciled")
        assertEquals("matched", scenario.state("posting-reg-transfer-a-source"))
        assertEquals("matched", scenario.state("posting-reg-transfer-a2-destination"))
        assertEquals("pending_evidence", scenario.state("posting-reg-transfer-b-source"))
        assertEquals("pending_evidence", scenario.state("posting-reg-transfer-b-destination"))

        // Evidencing B's own postings finally reconciles B; A remains fully_reconciled.
        scenario.linkEvidence(transferB, AccountId("asset-a"), "posting-reg-transfer-b-source", -8_000L, "decrease")
        scenario.linkEvidence(transferB, AccountId("asset-b"), "posting-reg-transfer-b-destination", 8_000L, "increase")
        scenario.assertObservationState(b, "fully_reconciled")
        scenario.assertObservationState(a, "fully_reconciled")
    }

    @Test
    fun `unallocated real transfer outside adjustment scope does not set hasUnallocatedRealTransfer`() {
        val scenario = RegressionScenario()
        val a = scenario.account("asset-a", 130_00L)
        scenario.assertObservationState(a, "difference_pending_confirmation")
        scenario.confirmAdjustment(a)
        scenario.assertObservationState(a, "balanced_with_unexplained_adjustment")
        val b = scenario.account("asset-b", 130_00L)
        scenario.assertObservationState(b, "difference_pending_confirmation")
        scenario.confirmAdjustment(b)
        scenario.assertObservationState(b, "balanced_with_unexplained_adjustment")

        // Allocate 20.00 of T_A to A: A is partially explained (remaining 10.00), so any
        // in-scope unallocated real transfer would flip A to
        // difference_pending_explanation_confirmation.
        val transferA = scenario.transfer("reg-scope-a", AccountId("asset-a"), AccountId("asset-b"), 2_000L)
        scenario.allocate(a, transferA.transactionId, 2_000L)
        scenario.assertAdjustment(a, "partially_explained", 2_000L, 1_000L)
        scenario.assertObservationState(a, "balanced_with_unexplained_adjustment")

        // Unallocated transfer into asset-b is outside A's target-account scope (and its
        // asset-a posting has the wrong direction), while B immediately awaits allocation.
        scenario.transfer("reg-scope-b", AccountId("asset-b"), AccountId("asset-a"), 1_000L)
        scenario.assertObservationState(a, "balanced_with_unexplained_adjustment")
        scenario.assertObservationState(b, "difference_pending_explanation_confirmation")

        // External ACCOUNT_TRANSFER into asset-a that is effective AFTER A's target observed
        // time: inside A's account/currency/direction but outside its effective-at range.
        scenario.appendExternalAccountTransfer(
            suffix = "reg-scope-after",
            sourceAccountId = AccountId("asset-b"),
            destinationAccountId = AccountId("asset-a"),
            amountMinor = 1_000L,
            effectiveAt = Instant.parse("2026-02-15T04:00:00Z"),
        )
        scenario.assertObservationState(a, "balanced_with_unexplained_adjustment")

        // External ACCOUNT_TRANSFER on asset-a with the wrong direction (decrease) before the
        // target time: outside A's direction scope even though the account matches.
        scenario.appendExternalAccountTransfer(
            suffix = "reg-scope-direction",
            sourceAccountId = AccountId("asset-a"),
            destinationAccountId = AccountId("asset-b"),
            amountMinor = 1_000L,
            effectiveAt = Instant.parse("2026-01-15T04:00:00Z"),
        )
        scenario.assertObservationState(a, "balanced_with_unexplained_adjustment")

        // Positive control: an unallocated real transfer inside A's account/currency/direction
        // and effective-at scope flips A to difference_pending_explanation_confirmation.
        scenario.transfer("reg-scope-in", AccountId("asset-a"), AccountId("asset-b"), 1_000L)
        scenario.assertObservationState(a, "difference_pending_explanation_confirmation")
        scenario.assertObservationState(b, "difference_pending_explanation_confirmation")
        scenario.assertAdjustment(a, "partially_explained", 2_000L, 1_000L)
        scenario.assertAdjustment(b, "open", 0L, 8_000L)
    }

    private fun assertAdjustment(
        snapshot: Rg09Snapshot,
        adjustmentId: String,
        state: String,
        explainedMinor: Long,
        remainingMinor: Long,
    ) {
        val adjustment = snapshot.adjustments.single { it.id.value == adjustmentId }
        assertEquals(state, adjustment.state)
        assertEquals(explainedMinor, adjustment.explainedAmount.minorUnits)
        assertEquals(remainingMinor, adjustment.remainingAmount.minorUnits)
    }

    /**
     * The runtime keys each observation's derived status by its typed observation ID
     * (observation-*); the frozen golden publishes the same status under the source-facing
     * target-observation key. This projects the single-observation snapshot onto the golden
     * convention (mirrors the full-state oracle projection).
     */
    private fun observationState(snapshot: Rg09Snapshot): String =
        snapshot.reconciliation.entries
            .firstOrNull { it.key.startsWith("observation-") }
            ?.value
            ?: error("RG-09 observation state missing")

    private fun loadFixture(): Rg09FixtureCase =
        adaptRg09Fixture(
            Files.readString(repositoryFile("golden/rules/rg-09.json")),
            parseRg09FixtureInputs(Files.readString(repositoryFile("tests/fixtures/rg09-runtime-input.json"))),
        )

    private fun repositoryFile(relative: String): java.nio.file.Path {
        var candidate =
            java.nio.file.Path
                .of(System.getProperty("user.dir"))
        repeat(8) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)
            candidate = candidate.parent ?: error("repository root not found")
        }
        error("repository root not found")
    }

    /**
     * One shared runtime plus deterministic per-observation operation builders. All IDs are
     * scoped by the supplied suffix so a single runtime instance can hold multiple
     * observations, adjustments, transfers and allocations without idempotency collisions.
     */
    private inner class RegressionScenario {
        val fixture = loadFixture()
        val runtime = Rg09Runtime(fixture.catalog, fixture.openingTransactions)
        private val cny = CurrencyUnit("CNY", 2)
        private val targetTime = Instant.parse("2026-01-31T15:59:59Z")
        private val targetTimeText = "2026-01-31T23:59:59+08:00"

        fun account(
            accountId: String,
            targetMinor: Long,
        ): ObservedAccount {
            val suffix = "reg-$accountId"
            val candidateId = Rg09CandidateId("candidate-$suffix")
            val observationId = "observation-$suffix"
            val fingerprint = Rg09LedgerFingerprint.digest(runtime.snapshot().formalTransactions, targetTime)
            val savedAt = Instant.parse("2026-02-01T09:00:00+08:00")
            val operation =
                Rg09Operation.PreviewTargetBalance(
                    ledgerId = fixture.ledgerId,
                    input =
                        Rg09PreviewTargetBalanceInput(
                            requestId = RequestId("request-preview-$suffix"),
                            accountId = AccountId(accountId),
                            targetAmount = Money.ofMinor(targetMinor.toLong(), cny),
                            targetObservedAt = targetTime,
                            savedAt = savedAt,
                            currency = cny,
                            explicitConfirmation = false,
                            immutablePayloadDigest = "sha256:rg09-regression-$suffix",
                            ledgerFingerprint = fingerprint,
                            targetObservedAtText = targetTimeText,
                            savedAtText = "2026-02-01T09:00:00+08:00",
                        ),
                    ids =
                        Rg09PreviewIds(
                            observationId = Rg09ObservationId(observationId),
                            sourceRecordId = Rg09SourceRecordId("source-$suffix"),
                            evidenceId = Rg09EvidenceId("evidence-$suffix"),
                            evidenceLinkId = Rg09EvidenceLinkId("evidence-link-$suffix"),
                            candidateId = candidateId,
                        ),
                )
            assertIs<Rg09ExecutionResult.Accepted>(runtime.commit(operation))
            return ObservedAccount(AccountId(accountId), candidateId, observationId, suffix)
        }

        fun confirmAdjustment(account: ObservedAccount) {
            val candidate = runtime.snapshot().candidates.single { it.id == account.candidateId }
            val suffix = account.suffix
            val operation =
                Rg09Operation.ConfirmBalanceAdjustment(
                    ledgerId = fixture.ledgerId,
                    input =
                        Rg09ConfirmBalanceAdjustmentInput(
                            requestId = RequestId("request-confirm-$suffix"),
                            candidateId = candidate.id,
                            ledgerFingerprint = candidate.ledgerFingerprint,
                            explicitConfirmation = true,
                            confirmedAt = Instant.parse("2026-02-01T02:00:00+08:00"),
                            confirmedAtText = "2026-02-01T02:00:00+08:00",
                        ),
                    ids =
                        Rg09AdjustmentCommitIds(
                            confirmationId = Rg09ConfirmationId("confirmation-adjustment-$suffix"),
                            adjustmentId = Rg09AdjustmentId("adjustment-$suffix"),
                            transactionId = TransactionId("transaction-adjustment-$suffix"),
                            versionId = TransactionVersionId("version-adjustment-$suffix-v1"),
                            postingSetId = PostingSetId("posting-set-adjustment-$suffix"),
                            targetPostingId = PostingId("posting-adjustment-$suffix-target"),
                            equityPostingId = PostingId("posting-adjustment-$suffix-equity"),
                            historyId = "history-adjustment-$suffix-open",
                        ),
                )
            assertIs<Rg09ExecutionResult.Accepted>(runtime.commit(operation))
        }

        fun transfer(
            suffix: String,
            targetAccountId: AccountId,
            counterAccountId: AccountId,
            amountMinor: Long,
        ): ConfirmedTransfer {
            val operation =
                Rg09Operation.ConfirmRealTransfer(
                    ledgerId = fixture.ledgerId,
                    input =
                        Rg09ConfirmRealTransferInput(
                            requestId = RequestId("request-transfer-$suffix"),
                            targetAccountId = targetAccountId,
                            counterAccountId = counterAccountId,
                            amount = Money.ofMinor(amountMinor, cny),
                            actualOccurredAt = Instant.parse("2026-01-20T04:00:00Z"),
                            discoveredAt = Instant.parse("2026-02-10T09:30:00+08:00"),
                            confirmedAt = Instant.parse("2026-02-01T03:00:00+08:00"),
                            immutablePayloadDigest = "sha256:rg09-regression-transfer-$suffix",
                            explicitConfirmation = true,
                            confirmsTargetAccount = true,
                            confirmsCounterAccount = true,
                            confirmsActualOccurredAt = true,
                            confirmsCurrency = true,
                            confirmsAmount = true,
                            confirmsExplanationAllocation = false,
                            targetAccountDirection = "increase",
                            actualOccurredAtText = "2026-01-20T12:00:00+08:00",
                            discoveredAtText = "2026-02-10T09:30:00+08:00",
                            confirmedAtText = "2026-02-01T03:00:00+08:00",
                        ),
                    ids =
                        Rg09TransferCommitIds(
                            confirmationId = Rg09ConfirmationId("confirmation-transfer-$suffix"),
                            sourceRecordId = Rg09SourceRecordId("source-transfer-$suffix"),
                            transactionId = TransactionId("transaction-$suffix"),
                            versionId = TransactionVersionId("version-$suffix-v1"),
                            postingSetId = PostingSetId("posting-set-$suffix"),
                            sourcePostingId = PostingId("posting-$suffix-source"),
                            destinationPostingId = PostingId("posting-$suffix-destination"),
                        ),
                )
            assertIs<Rg09ExecutionResult.Accepted>(runtime.commit(operation))
            return ConfirmedTransfer(
                suffix = suffix,
                transactionId = operation.ids.transactionId,
                targetAccountId = targetAccountId,
                counterAccountId = counterAccountId,
                amountMinor = amountMinor,
            )
        }

        fun allocate(
            account: ObservedAccount,
            realTransactionId: TransactionId,
            amountMinor: Long,
        ) {
            val suffix = "${account.suffix}-alloc-$amountMinor"
            val operation =
                Rg09Operation.ConfirmExplanationAllocation(
                    ledgerId = fixture.ledgerId,
                    input =
                        Rg09ConfirmExplanationAllocationInput(
                            requestId = RequestId("request-allocation-$suffix"),
                            adjustmentId = Rg09AdjustmentId("adjustment-${account.suffix}"),
                            transactionId = realTransactionId,
                            targetAccountId = account.accountId,
                            actualOccurredAt = Instant.parse("2026-01-20T04:00:00Z"),
                            realTransactionAmount = Money.ofMinor(amountMinor, cny),
                            targetObservedAt = targetTime,
                            explanationAmount = Money.ofMinor(amountMinor, cny),
                            confirmedAt = Instant.parse("2026-02-01T04:00:00+08:00"),
                            explicitConfirmation = true,
                            confirmsTargetAccount = true,
                            confirmsActualOccurredAt = true,
                            confirmsRealTransactionAmount = true,
                            confirmsCurrency = true,
                            confirmsTargetObservedAt = true,
                            confirmsAllocationDirection = true,
                            confirmsExplanationAmount = true,
                            actualOccurredAtText = "2026-01-20T12:00:00+08:00",
                            targetObservedAtText = targetTimeText,
                            confirmedAtText = "2026-02-01T04:00:00+08:00",
                            discoveredAt = Instant.parse("2026-02-10T09:30:00+08:00"),
                            discoveredAtText = "2026-02-10T09:30:00+08:00",
                        ),
                    ids =
                        Rg09AllocationCommitIds(
                            confirmationId = Rg09ConfirmationId("confirmation-allocation-$suffix"),
                            allocationId = Rg09AllocationId("allocation-$suffix"),
                            reversalTransactionId = TransactionId("transaction-reversal-$suffix"),
                            reversalVersionId = TransactionVersionId("version-reversal-$suffix-v1"),
                            reversalPostingSetId = PostingSetId("posting-set-reversal-$suffix"),
                            reversalTargetPostingId = PostingId("posting-reversal-$suffix-target"),
                            reversalEquityPostingId = PostingId("posting-reversal-$suffix-equity"),
                            adjustmentAuditLinkId = Rg09AuditLinkId("audit-link-$suffix-adjustment"),
                            explanationAuditLinkId = Rg09AuditLinkId("audit-link-$suffix-explanation"),
                            reversalAuditLinkId = Rg09AuditLinkId("audit-link-$suffix-reversal"),
                            historyId = "history-$suffix-allocation",
                        ),
                )
            assertIs<Rg09ExecutionResult.Accepted>(runtime.commit(operation))
        }

        fun linkEvidence(
            transfer: ConfirmedTransfer,
            postingAccountId: AccountId,
            postingId: String,
            amountMinor: Long,
            postingSide: String,
        ) {
            val bookingAt = Instant.parse("2026-01-20T04:00:00Z")
            val suffix = "${transfer.suffix}-$postingId"
            val operation =
                Rg09Operation.LinkRealPostingEvidence(
                    ledgerId = fixture.ledgerId,
                    input =
                        Rg09LinkRealPostingEvidenceInput(
                            requestId = RequestId("request-link-$suffix"),
                            sourceId = Rg09SourceRecordId("source-link-$suffix"),
                            evidenceId = Rg09EvidenceId("evidence-link-$suffix"),
                            targetPostingId = PostingId(postingId),
                            accountId = postingAccountId,
                            amount = Money.ofMinor(amountMinor, cny),
                            postingSide = postingSide,
                            observedAt = Instant.parse("2026-02-11T09:00:00+08:00"),
                            bookingAt = bookingAt,
                            immutablePayloadDigest = "sha256:rg09-regression-evidence-$suffix",
                            explicitConfirmation = true,
                            observedAtText = "2026-02-11T09:00:00+08:00",
                            bookingAtText = "2026-01-20T12:00:00+08:00",
                        ),
                    ids =
                        Rg09PostingEvidenceIds(
                            evidenceLinkId = Rg09EvidenceLinkId("evidence-link-record-$suffix"),
                        ),
                )
            assertIs<Rg09ExecutionResult.Accepted>(runtime.commit(operation))
        }

        fun appendExternalAccountTransfer(
            suffix: String,
            sourceAccountId: AccountId,
            destinationAccountId: AccountId,
            amountMinor: Long,
            effectiveAt: Instant,
        ) {
            val postings =
                listOf(
                    Posting(
                        id = PostingId("posting-$suffix-source"),
                        accountId = sourceAccountId,
                        amount = Money.ofMinor(-amountMinor, cny),
                    ),
                    Posting(
                        id = PostingId("posting-$suffix-destination"),
                        accountId = destinationAccountId,
                        amount = Money.ofMinor(amountMinor, cny),
                    ),
                )
            val postingSetId = PostingSetId("posting-set-$suffix")
            val postingSet = assertIs<DomainResult.Success<PostingSet>>(PostingSet.create(postingSetId, postings)).value
            val versionId = TransactionVersionId("version-$suffix-v1")
            val formal =
                assertIs<DomainResult.Success<FormalTransaction>>(
                    FormalTransaction.create(
                        Transaction(
                            id = TransactionId("transaction-$suffix"),
                            ledgerId = fixture.ledgerId,
                            kind = TransactionKind.ACCOUNT_TRANSFER,
                            currentVersionId = versionId,
                        ),
                        listOf(
                            TransactionVersion(
                                id = versionId,
                                transactionId = TransactionId("transaction-$suffix"),
                                versionNumber = 1,
                                postingSetId = postingSetId,
                                times = TransactionTimes.collapsed(effectiveAt),
                            ),
                        ),
                        listOf(postingSet),
                    ),
                ).value
            runtime.appendExternalTransaction(
                Rg09FormalTransactionRecord(
                    formal,
                    createdAt = effectiveAt,
                    createdAtText = effectiveAt.toString(),
                    effectiveAtText = effectiveAt.toString(),
                ),
            )
        }

        fun state(key: String): String = runtime.snapshot().reconciliation.getValue(key)

        fun assertObservationState(
            account: ObservedAccount,
            expected: String,
        ) {
            assertEquals(expected, state(account.observationId))
        }

        fun assertAdjustment(
            account: ObservedAccount,
            state: String,
            explainedMinor: Long,
            remainingMinor: Long,
        ) {
            val adjustment = runtime.snapshot().adjustments.single { it.id.value == "adjustment-${account.suffix}" }
            assertEquals(state, adjustment.state)
            assertEquals(explainedMinor, adjustment.explainedAmount.minorUnits)
            assertEquals(remainingMinor, adjustment.remainingAmount.minorUnits)
        }
    }
}

private data class ObservedAccount(
    val accountId: AccountId,
    val candidateId: Rg09CandidateId,
    val observationId: String,
    val suffix: String,
)

private data class ConfirmedTransfer(
    val suffix: String,
    val transactionId: TransactionId,
    val targetAccountId: AccountId,
    val counterAccountId: AccountId,
    val amountMinor: Long,
)
