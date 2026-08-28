package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class Rg09NegativeDeltaTest {
    @Test
    fun `negative adjustment uses target decrease and positive explanation reversal`() {
        val fixture = loadFixture()
        val cny = CurrencyUnit("CNY", 2)
        val targetAmount = Money.ofMinor(7_000L, cny)
        val targetTime = Instant.parse("2026-01-31T15:59:59Z")
        val savedAt = Instant.parse("2026-02-01T01:00:00Z")
        val confirmedAt = Instant.parse("2026-02-01T02:00:00Z")
        val runtime = Rg09Runtime(fixture.catalog, fixture.openingTransactions)

        val previewSource = assertIs<Rg09Operation.PreviewTargetBalance>(fixture.operations[0].operation)
        val preview =
            previewSource.copy(
                input =
                    previewSource.input.copy(
                        requestId = RequestId("request-negative-preview-rg09"),
                        targetAmount = targetAmount,
                        targetObservedAt = targetTime,
                        savedAt = savedAt,
                        targetObservedAtText = "2026-01-31T23:59:59+08:00",
                        savedAtText = "2026-02-01T09:00:00+08:00",
                    ),
                ids =
                    Rg09PreviewIds(
                        observationId = Rg09ObservationId("observation-negative-rg09"),
                        sourceRecordId = Rg09SourceRecordId("source-negative-rg09"),
                        evidenceId = Rg09EvidenceId("evidence-negative-rg09"),
                        evidenceLinkId = Rg09EvidenceLinkId("evidence-link-negative-rg09"),
                        candidateId = Rg09CandidateId("candidate-negative-rg09"),
                    ),
            )
        assertIs<Rg09ExecutionResult.Accepted>(runtime.commit(preview))
        val candidate = runtime.snapshot().candidates.single { it.id.value == "candidate-negative-rg09" }
        assertEquals(-3_000L, candidate.delta.minorUnits)

        val adjustmentSource = assertIs<Rg09Operation.ConfirmBalanceAdjustment>(fixture.operations[1].operation)
        val adjustment =
            adjustmentSource.copy(
                input =
                    adjustmentSource.input.copy(
                        requestId = RequestId("request-negative-adjustment-rg09"),
                        candidateId = candidate.id,
                        ledgerFingerprint = candidate.ledgerFingerprint,
                        confirmedAt = confirmedAt,
                        confirmedAtText = "2026-02-01T10:00:00+08:00",
                    ),
                ids =
                    Rg09AdjustmentCommitIds(
                        confirmationId = Rg09ConfirmationId("confirmation-negative-rg09"),
                        adjustmentId = Rg09AdjustmentId("adjustment-negative-rg09"),
                        transactionId = TransactionId("transaction-adjustment-negative-rg09"),
                        versionId = TransactionVersionId("version-adjustment-negative-rg09-v1"),
                        postingSetId = PostingSetId("posting-set-adjustment-negative-rg09"),
                        targetPostingId = PostingId("posting-adjustment-negative-asset-rg09"),
                        equityPostingId = PostingId("posting-adjustment-negative-equity-rg09"),
                        historyId = "history-adjustment-negative-open-rg09",
                    ),
            )
        assertIs<Rg09ExecutionResult.Accepted>(runtime.commit(adjustment))
        val adjustmentState = runtime.snapshot().adjustments.single { it.id.value == "adjustment-negative-rg09" }
        assertEquals(-3_000L, adjustmentState.originalDelta.minorUnits)
        assertEquals(-3_000L, adjustmentState.formalTargetAmount(runtime.snapshot()).minorUnits)

        val transferSource = assertIs<Rg09Operation.ConfirmRealTransfer>(fixture.operations[2].operation)
        val transfer =
            transferSource.copy(
                input =
                    transferSource.input.copy(
                        requestId = RequestId("request-negative-transfer-rg09"),
                        amount = Money.ofMinor(2_000L, cny),
                        actualOccurredAt = Instant.parse("2026-01-20T04:00:00Z"),
                        discoveredAt = Instant.parse("2026-02-01T02:30:00Z"),
                        confirmedAt = Instant.parse("2026-02-01T03:00:00Z"),
                        targetAccountDirection = "decrease",
                        actualOccurredAtText = "2026-01-20T12:00:00+08:00",
                        discoveredAtText = "2026-02-01T10:30:00+08:00",
                        confirmedAtText = "2026-02-01T11:00:00+08:00",
                    ),
                ids =
                    Rg09TransferCommitIds(
                        confirmationId = Rg09ConfirmationId("confirmation-negative-transfer-rg09"),
                        sourceRecordId = Rg09SourceRecordId("source-negative-transfer-rg09"),
                        transactionId = TransactionId("transaction-transfer-negative-rg09"),
                        versionId = TransactionVersionId("version-transfer-negative-rg09-v1"),
                        postingSetId = PostingSetId("posting-set-transfer-negative-rg09"),
                        sourcePostingId = PostingId("posting-transfer-negative-asset-rg09"),
                        destinationPostingId = PostingId("posting-transfer-negative-counter-rg09"),
                    ),
            )
        assertIs<Rg09ExecutionResult.Accepted>(runtime.commit(transfer))
        val transferRecord =
            runtime.snapshot().formalTransactions.single {
                it.formalTransaction.transaction.id.value == "transaction-transfer-negative-rg09"
            }
        assertEquals(
            -2_000L,
            transferRecord.formalTransaction
                .currentPostings()
                .single {
                    it.accountId.value == "asset-a"
                }.amount.minorUnits,
        )

        val explanationSource = assertIs<Rg09Operation.ConfirmExplanationAllocation>(fixture.operations[3].operation)
        val explanation =
            explanationSource.copy(
                input =
                    explanationSource.input.copy(
                        requestId = RequestId("request-negative-explanation-rg09"),
                        adjustmentId = Rg09AdjustmentId("adjustment-negative-rg09"),
                        transactionId = TransactionId("transaction-transfer-negative-rg09"),
                        actualOccurredAt = Instant.parse("2026-01-20T04:00:00Z"),
                        realTransactionAmount = Money.ofMinor(2_000L, cny),
                        targetObservedAt = targetTime,
                        explanationAmount = Money.ofMinor(2_000L, cny),
                        confirmedAt = Instant.parse("2026-02-01T04:00:00Z"),
                        actualOccurredAtText = "2026-01-20T12:00:00+08:00",
                        targetObservedAtText = "2026-01-31T23:59:59+08:00",
                        confirmedAtText = "2026-02-01T12:00:00+08:00",
                        discoveredAt = transfer.input.discoveredAt,
                        discoveredAtText = transfer.input.discoveredAtText,
                    ),
                ids =
                    Rg09AllocationCommitIds(
                        confirmationId = Rg09ConfirmationId("confirmation-negative-explanation-rg09"),
                        allocationId = Rg09AllocationId("allocation-negative-rg09"),
                        reversalTransactionId = TransactionId("transaction-reversal-negative-rg09"),
                        reversalVersionId = TransactionVersionId("version-reversal-negative-rg09-v1"),
                        reversalPostingSetId = PostingSetId("posting-set-reversal-negative-rg09"),
                        reversalTargetPostingId = PostingId("posting-reversal-negative-asset-rg09"),
                        reversalEquityPostingId = PostingId("posting-reversal-negative-equity-rg09"),
                        adjustmentAuditLinkId = Rg09AuditLinkId("audit-link-negative-adjustment-rg09"),
                        explanationAuditLinkId = Rg09AuditLinkId("audit-link-negative-explanation-rg09"),
                        reversalAuditLinkId = Rg09AuditLinkId("audit-link-negative-reversal-rg09"),
                        historyId = "history-adjustment-negative-partial-rg09",
                    ),
            )
        assertIs<Rg09ExecutionResult.Accepted>(runtime.commit(explanation))

        val after = runtime.snapshot()
        val finalAdjustment = after.adjustments.single { it.id.value == "adjustment-negative-rg09" }
        assertEquals(2_000L, finalAdjustment.explainedAmount.minorUnits)
        assertEquals(1_000L, finalAdjustment.remainingAmount.minorUnits)
        assertEquals("partially_explained", finalAdjustment.state)
        val reversal =
            after.formalTransactions.single {
                it.formalTransaction.transaction.id.value == "transaction-reversal-negative-rg09"
            }
        assertEquals(
            2_000L,
            reversal.formalTransaction
                .currentPostings()
                .single {
                    it.accountId == AccountId("asset-a")
                }.amount.minorUnits,
        )
        assertTrue(after.candidates.single { it.id.value == "candidate-negative-rg09" }.status == "confirmed")
    }

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
}

private fun Rg09Adjustment.formalTargetAmount(snapshot: Rg09Snapshot): Money =
    snapshot.formalTransactions
        .single {
            it.formalTransaction.transaction.id == transactionId
        }.formalTransaction
        .currentPostings()
        .single {
            it.accountId == targetAccountId
        }.amount
