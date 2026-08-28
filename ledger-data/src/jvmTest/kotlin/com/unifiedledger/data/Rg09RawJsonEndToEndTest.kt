package com.unifiedledger.data

import com.unifiedledger.application.RequestId
import com.unifiedledger.application.Rg09ExecutionResult
import com.unifiedledger.application.Rg09FixtureCase
import com.unifiedledger.application.Rg09FormalTransactionRecord
import com.unifiedledger.application.Rg09Operation
import com.unifiedledger.application.Rg09RejectionReason
import com.unifiedledger.application.Rg09Runtime
import com.unifiedledger.application.adaptRg09Fixture
import com.unifiedledger.application.parseRg09FixtureInputs
import com.unifiedledger.application.replayRg09Fixture
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.OwnAssetPrincipalTransferCommand
import com.unifiedledger.domain.OwnAssetPrincipalTransferIds
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.createOwnAssetPrincipalTransfer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class Rg09RawJsonEndToEndTest {
    @Test
    fun `frozen RG-09 main path replays through typed runtime`() {
        val fixture = loadFixture()
        val replay =
            replayRg09Fixture(
                Files.readString(repositoryFile("golden/rules/rg-09.json")),
                loadRuntimeInputs(),
            )
        assertEquals(50, replay.operations.size)
        assertEquals(14, replay.accepted)
        assertEquals(15, replay.noChange)
        assertEquals(21, replay.rejected)

        val runtime = Rg09Runtime(fixture.catalog, fixture.openingTransactions)
        val results =
            fixture.operations.map { operation ->
                val result = runtime.commit(operation.operation)
                assertIs<Rg09ExecutionResult.Accepted>(result, "${operation.id}: $result")
                result
            }
        assertEquals(6, results.size)

        val finalState = runtime.snapshot()
        assertEquals(
            "sha256:rg09-transfer",
            finalState.sourceRecords
                .first { it.id.value == "source-real-transfer-confirmation-rg09" }
                .immutablePayloadDigest,
        )
        assertEquals(
            "sha256:rg09-transfer-remaining",
            finalState.sourceRecords
                .first { it.id.value == "source-real-transfer-confirmation-rg09-remaining" }
                .immutablePayloadDigest,
        )
        assertEquals(
            listOf("history-adjustment-open-rg09", "history-adjustment-partial-rg09", "history-adjustment-full-rg09"),
            finalState.adjustments
                .single()
                .history
                .map { it.id },
        )

        val preview = finalState
        assertEquals(6, preview.formalTransactions.size)
        assertEquals(
            3,
            preview.formalTransactions.count {
                it.formalTransaction.transaction.kind == TransactionKind.BALANCE_ADJUSTMENT ||
                    it.formalTransaction.transaction.kind == TransactionKind.BALANCE_ADJUSTMENT_REVERSAL
            },
            preview.formalTransactions.map { it.formalTransaction.transaction.kind }.toString(),
        )
    }

    @Test
    fun `main path preserves adjustment report isolation and derived lifecycle`() {
        val fixture = loadFixture()
        val runtime = Rg09Runtime(fixture.catalog, fixture.openingTransactions)
        fixture.operations.take(4).forEach { operation ->
            val result = runtime.commit(operation.operation)
            assertIs<Rg09ExecutionResult.Accepted>(result, "${operation.id}: $result")
        }

        val state = runtime.snapshot()
        assertEquals(13_000L, state.balances.getValue(AccountId("asset-a")).minorUnits)
        assertEquals(3_000L, state.balances.getValue(AccountId("asset-b")).minorUnits)
        assertEquals(-1_000L, state.balances.getValue(AccountId("equity-balance-adjustments")).minorUnits)
        assertEquals("partially_explained", state.adjustments.single().state)
        assertEquals(
            1_000L,
            state.adjustments
                .single()
                .remainingAmount.minorUnits,
        )
        assertEquals(4, state.formalTransactions.size)
        assertEquals(
            listOf(TransactionKind.OPENING_BALANCE, TransactionKind.BALANCE_ADJUSTMENT, TransactionKind.ACCOUNT_TRANSFER, TransactionKind.BALANCE_ADJUSTMENT_REVERSAL),
            state.formalTransactions.map { it.formalTransaction.transaction.kind },
        )
        val report = state.reports.getValue("cumulative")
        assertEquals(0L, report.ordinaryIncomeMinor)
        assertEquals(0L, report.ordinaryExpenseMinor)
        assertEquals(0L, report.cashInflowMinor)
        assertEquals(0L, report.cashOutflowMinor)
        assertEquals(2_000L, report.internalTransferMinor)
        assertEquals(1_000L, report.balanceAdjustmentNetWorthChangeMinor)
        assertEquals("balanced_with_unexplained_adjustment", state.reconciliation.getValue("observation-target-rg09"))
    }

    @Test
    fun `accepted operation replay is no change and changed input is conflict`() {
        val fixture = loadFixture()
        val runtime = Rg09Runtime(fixture.catalog, fixture.openingTransactions)
        val operation = fixture.operations.first().operation
        assertIs<Rg09ExecutionResult.Accepted>(runtime.commit(operation))
        assertIs<Rg09ExecutionResult.NoChange>(runtime.commit(operation))
        val conflict =
            when (operation) {
                is Rg09Operation.PreviewTargetBalance ->
                    operation.copy(
                        input = operation.input.copy(targetAmount = Money.ofMinor(13_100L, CurrencyUnit("CNY", 2))),
                    )
                else -> error("RG-09 fixture first operation must be preview")
            }
        assertIs<Rg09ExecutionResult.RequestIdentityConflict>(runtime.commit(conflict))
        assertEquals(1, runtime.snapshot().observations.size)
        assertEquals(1, runtime.snapshot().candidates.size)
    }

    @Test
    fun `preview rejection does not persist intake entities`() {
        val fixture = loadFixture()
        val runtime = Rg09Runtime(fixture.catalog, fixture.openingTransactions)
        val preview = assertIs<Rg09Operation.PreviewTargetBalance>(fixture.operations.first().operation)
        val before = runtime.snapshot()

        val result = runtime.commit(preview.copy(ids = preview.ids.copy(candidateId = null)))

        assertIs<Rg09ExecutionResult.Rejected>(result)
        assertEquals(result, runtime.commit(preview.copy(ids = preview.ids.copy(candidateId = null))))
        assertEquals(before, runtime.snapshot())
        assertEquals(0, runtime.snapshot().observations.size)
        assertEquals(0, runtime.snapshot().sourceRecords.size)
        assertEquals(0, runtime.snapshot().evidence.size)
        assertEquals(0, runtime.snapshot().evidenceLinks.size)
    }

    @Test
    fun `preview intake ID collision rejects before append`() {
        val fixture = loadFixture()
        val runtime = Rg09Runtime(fixture.catalog, fixture.openingTransactions)
        val preview = assertIs<Rg09Operation.PreviewTargetBalance>(fixture.operations.first().operation)
        assertIs<Rg09ExecutionResult.Accepted>(runtime.commit(preview))
        val before = runtime.snapshot()

        val zeroDeltaCollision =
            preview.copy(
                input =
                    preview.input.copy(
                        requestId = RequestId("request-rg09-zero-delta-collision"),
                        targetAmount = Money.ofMinor(10_000L, CurrencyUnit("CNY", 2)),
                    ),
                ids = preview.ids.copy(candidateId = null),
            )

        val result = runtime.commit(zeroDeltaCollision)

        assertEquals(Rg09RejectionReason.DOMAIN_REJECTED, assertIs<Rg09ExecutionResult.Rejected>(result).reason)
        assertEquals(before, runtime.snapshot())
    }

    @Test
    fun `idempotency includes non-primary input fields`() {
        val fixture = loadFixture()
        val runtime = Rg09Runtime(fixture.catalog, fixture.openingTransactions)
        val preview = assertIs<Rg09Operation.PreviewTargetBalance>(fixture.operations.first().operation)
        assertIs<Rg09ExecutionResult.Accepted>(runtime.commit(preview))

        val changed =
            preview.copy(
                input = preview.input.copy(savedAt = Instant.parse("2026-02-02T09:00:00+08:00")),
            )

        assertEquals(Rg09ExecutionResult.RequestIdentityConflict, runtime.commit(changed))
    }

    @Test
    fun `explanation requires an account transfer with matching effective time`() {
        val fixture = loadFixture()
        val runtime = Rg09Runtime(fixture.catalog, fixture.openingTransactions)
        fixture.operations.take(3).forEach { operation ->
            assertIs<Rg09ExecutionResult.Accepted>(runtime.commit(operation.operation))
        }
        val explanation = assertIs<Rg09Operation.ConfirmExplanationAllocation>(fixture.operations[3].operation)
        val opening = fixture.openingTransactions.single().formalTransaction
        val openingTime =
            opening.versions
                .single()
                .times.effectiveAt

        val wrongKind =
            explanation.copy(
                input =
                    explanation.input.copy(
                        transactionId = opening.transaction.id,
                        actualOccurredAt = openingTime,
                        realTransactionAmount = Money.ofMinor(10_000L, CurrencyUnit("CNY", 2)),
                    ),
            )
        val wrongKindResult = runtime.commit(wrongKind)
        assertEquals(Rg09RejectionReason.REAL_TRANSFER_REQUIRED, assertIs<Rg09ExecutionResult.Rejected>(wrongKindResult).reason)
        assertEquals(0, runtime.snapshot().allocations.size)

        val wrongTime =
            explanation.copy(
                input =
                    explanation.input.copy(
                        requestId = RequestId("request-rg09-wrong-time"),
                        actualOccurredAt = Instant.parse("2026-01-19T12:00:00+08:00"),
                    ),
            )
        val wrongTimeResult = runtime.commit(wrongTime)
        assertEquals(Rg09RejectionReason.REAL_TRANSACTION_TIME_MISMATCH, assertIs<Rg09ExecutionResult.Rejected>(wrongTimeResult).reason)
        assertEquals(0, runtime.snapshot().allocations.size)

        val overRealTransaction =
            explanation.copy(
                input =
                    explanation.input.copy(
                        requestId = RequestId("request-rg09-over-real"),
                        explanationAmount = Money.ofMinor(2_100L, CurrencyUnit("CNY", 2)),
                    ),
            )
        val overRealTransactionResult = runtime.commit(overRealTransaction)
        assertEquals(
            Rg09RejectionReason.EXPLANATION_EXCEEDS_REAL_TRANSACTION,
            assertIs<Rg09ExecutionResult.Rejected>(overRealTransactionResult).reason,
        )
        assertEquals(0, runtime.snapshot().allocations.size)
    }

    @Test
    fun `explanation rejects an account transfer without its explicit confirmation`() {
        val fixture = loadFixture()
        val runtime = Rg09Runtime(fixture.catalog, fixture.openingTransactions)
        fixture.operations.take(2).forEach { operation ->
            assertIs<Rg09ExecutionResult.Accepted>(runtime.commit(operation.operation))
        }
        val external =
            when (
                val result =
                    createOwnAssetPrincipalTransfer(
                        fixture.catalog,
                        OwnAssetPrincipalTransferCommand(
                            ledgerId = fixture.ledgerId,
                            sourceAccountId = AccountId("asset-b"),
                            destinationAccountId = AccountId("asset-a"),
                            amount = Money.ofMinor(1_000L, CurrencyUnit("CNY", 2)),
                            times = TransactionTimes.collapsed(Instant.parse("2026-01-21T04:00:00Z")),
                        ),
                        OwnAssetPrincipalTransferIds(
                            TransactionId("tx-rg09-unconfirmed"),
                            com.unifiedledger.domain.TransactionVersionId("version-rg09-unconfirmed"),
                            com.unifiedledger.domain.PostingSetId("posting-set-rg09-unconfirmed"),
                            com.unifiedledger.domain.PostingId("posting-out-rg09-unconfirmed"),
                            com.unifiedledger.domain.PostingId("posting-in-rg09-unconfirmed"),
                        ),
                    )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> error("test transfer must be valid")
            }
        runtime.appendExternalTransaction(
            Rg09FormalTransactionRecord(
                formalTransaction = external.formalTransaction,
                createdAt = Instant.parse("2026-02-10T17:00:00Z"),
            ),
        )

        val explanation = assertIs<Rg09Operation.ConfirmExplanationAllocation>(fixture.operations[3].operation)
        val result =
            runtime.commit(
                explanation.copy(
                    input =
                        explanation.input.copy(
                            requestId = RequestId("request-rg09-unconfirmed-transfer"),
                            transactionId = TransactionId("tx-rg09-unconfirmed"),
                            actualOccurredAt = Instant.parse("2026-01-21T04:00:00Z"),
                            realTransactionAmount = Money.ofMinor(1_000L, CurrencyUnit("CNY", 2)),
                            explanationAmount = Money.ofMinor(1_000L, CurrencyUnit("CNY", 2)),
                        ),
                ),
            )

        assertEquals(
            Rg09RejectionReason.REAL_TRANSACTION_NOT_CONFIRMED,
            assertIs<Rg09ExecutionResult.Rejected>(result).reason,
        )
        assertEquals(0, runtime.snapshot().allocations.size)
    }

    @Test
    fun `explanation cannot reuse the same real transfer for a second allocation`() {
        val fixture = loadFixture()
        val runtime = Rg09Runtime(fixture.catalog, fixture.openingTransactions)
        fixture.operations.take(4).forEach { operation ->
            assertIs<Rg09ExecutionResult.Accepted>(runtime.commit(operation.operation))
        }
        val explanation = assertIs<Rg09Operation.ConfirmExplanationAllocation>(fixture.operations[3].operation)
        val repeated =
            explanation.copy(
                input =
                    explanation.input.copy(
                        requestId = RequestId("request-rg09-repeat-allocation"),
                        explanationAmount = Money.ofMinor(1_000L, CurrencyUnit("CNY", 2)),
                    ),
                ids =
                    explanation.ids.copy(
                        confirmationId = com.unifiedledger.application.Rg09ConfirmationId("confirmation-rg09-repeat"),
                        allocationId = com.unifiedledger.application.Rg09AllocationId("allocation-rg09-repeat"),
                        reversalTransactionId = TransactionId("transaction-rg09-repeat-reversal"),
                        reversalVersionId = com.unifiedledger.domain.TransactionVersionId("version-rg09-repeat-reversal"),
                        reversalPostingSetId = com.unifiedledger.domain.PostingSetId("posting-set-rg09-repeat-reversal"),
                        reversalTargetPostingId = com.unifiedledger.domain.PostingId("posting-rg09-repeat-target"),
                        reversalEquityPostingId = com.unifiedledger.domain.PostingId("posting-rg09-repeat-equity"),
                        adjustmentAuditLinkId = com.unifiedledger.application.Rg09AuditLinkId("audit-rg09-repeat-adjustment"),
                        explanationAuditLinkId = com.unifiedledger.application.Rg09AuditLinkId("audit-rg09-repeat-explanation"),
                        reversalAuditLinkId = com.unifiedledger.application.Rg09AuditLinkId("audit-rg09-repeat-reversal"),
                        historyId = "history-adjustment-repeat-rg09",
                    ),
            )

        val result = runtime.commit(repeated)

        assertEquals(
            Rg09RejectionReason.EXPLANATION_EXCEEDS_REAL_TRANSACTION,
            assertIs<Rg09ExecutionResult.Rejected>(result).reason,
        )
        assertEquals(1, runtime.snapshot().allocations.size)
        assertEquals(4, runtime.snapshot().formalTransactions.size)
    }

    @Test
    fun `formal transaction overflow is rejected before append`() {
        val fixture = loadFixture()
        val runtime = Rg09Runtime(fixture.catalog, fixture.openingTransactions)
        fixture.operations.take(2).forEach { operation ->
            assertIs<Rg09ExecutionResult.Accepted>(runtime.commit(operation.operation))
        }
        val transfer = assertIs<Rg09Operation.ConfirmRealTransfer>(fixture.operations[2].operation)
        assertIs<Rg09ExecutionResult.Accepted>(runtime.commit(transfer))
        assertEquals(3, runtime.snapshot().formalTransactions.size)

        val duplicate =
            transfer.copy(
                input =
                    transfer.input.copy(
                        requestId = RequestId("request-rg09-duplicate-formal-id"),
                        amount = Money.ofMinor(1_000L, CurrencyUnit("CNY", 2)),
                    ),
                ids =
                    transfer.ids.copy(
                        confirmationId = com.unifiedledger.application.Rg09ConfirmationId("confirmation-rg09-formal-id-duplicate"),
                        sourceRecordId = com.unifiedledger.application.Rg09SourceRecordId("source-rg09-formal-id-duplicate"),
                    ),
            )
        val beforeDuplicate = runtime.snapshot()
        assertEquals(
            Rg09RejectionReason.DOMAIN_REJECTED,
            assertIs<Rg09ExecutionResult.Rejected>(runtime.commit(duplicate)).reason,
        )
        assertEquals(beforeDuplicate, runtime.snapshot())

        val largeTransfer =
            transfer.copy(
                input =
                    transfer.input.copy(
                        requestId = RequestId("request-rg09-overflow-transfer"),
                        amount = Money.ofMinor(Long.MAX_VALUE, CurrencyUnit("CNY", 2)),
                    ),
                ids =
                    transfer.ids.copy(
                        confirmationId = com.unifiedledger.application.Rg09ConfirmationId("confirmation-rg09-overflow"),
                        sourceRecordId = com.unifiedledger.application.Rg09SourceRecordId("source-rg09-overflow"),
                        transactionId = TransactionId("tx-rg09-overflow"),
                        versionId = com.unifiedledger.domain.TransactionVersionId("version-rg09-overflow"),
                        postingSetId = com.unifiedledger.domain.PostingSetId("posting-set-rg09-overflow"),
                        sourcePostingId = com.unifiedledger.domain.PostingId("posting-out-rg09-overflow"),
                        destinationPostingId = com.unifiedledger.domain.PostingId("posting-in-rg09-overflow"),
                    ),
            )

        val beforeOverflow = runtime.snapshot()
        val result = runtime.commit(largeTransfer)

        assertEquals(Rg09RejectionReason.DOMAIN_REJECTED, assertIs<Rg09ExecutionResult.Rejected>(result).reason)
        assertEquals(beforeOverflow, runtime.snapshot())
    }

    private fun loadFixture(): Rg09FixtureCase =
        adaptRg09Fixture(
            Files.readString(repositoryFile("golden/rules/rg-09.json")),
            loadRuntimeInputs(),
        )

    private fun loadRuntimeInputs() = parseRg09FixtureInputs(Files.readString(repositoryFile("tests/fixtures/rg09-runtime-input.json")))

    private fun repositoryFile(relative: String): Path {
        var candidate = Path.of(System.getProperty("user.dir"))
        repeat(8) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)
            candidate = candidate.parent ?: error("repository root not found")
        }
        error("repository root not found")
    }
}
