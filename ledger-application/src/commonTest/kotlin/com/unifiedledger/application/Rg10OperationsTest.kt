package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.StoredValueLot
import com.unifiedledger.domain.StoredValueLotId
import com.unifiedledger.domain.TransactionKind
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * RG-10 approved runtime behavior (D-083): the 13-action closed registry, deterministic
 * allocation, import pending-confirmation, evidence-bound reconciliation, replace-not-append
 * activation, and fail-closed GAP-05/GAP-06 paths. Driven by the frozen fixture and the
 * deterministic runtime inputs; no expected-output oracle is built here.
 */
class Rg10OperationsTest {
    private val cny = CurrencyUnit("CNY", 2)

    @Test
    fun `frozen main path confirms recharge spend reminder and expiry loss`() {
        val fixture = loadFixture()
        val runtime = Rg10Runtime(fixture.catalog, fixture.openingTransactions)
        fixture.operations.forEach { item ->
            assertEquals("accepted", item.expectedStatus, item.id)
            assertIs<Rg10ExecutionResult.Accepted>(runtime.commit(item.operation), item.id)
        }
        val state = runtime.snapshot()
        assertEquals(
            listOf(
                TransactionKind.OPENING_BALANCE,
                TransactionKind.STORED_VALUE_RECHARGE,
                TransactionKind.STORED_VALUE_SPEND,
                TransactionKind.STORED_VALUE_EXPIRY_LOSS,
            ),
            state.formalTransactions.map { it.formalTransaction.transaction.kind },
        )
        assertEquals(80_000L, state.balances.getValue(AccountId("asset-stored-value-x")).minorUnits)
        assertEquals(400_000L, state.balances.getValue(AccountId("asset-bank-a")).minorUnits)
        assertEquals(-20_000L, state.balances.getValue(AccountId("income-special-bonus-rg10")).minorUnits)
        assertEquals(30_000L, state.balances.getValue(AccountId("expense-consumption-rg10")).minorUnits)
        assertEquals(10_000L, state.balances.getValue(AccountId("expense-expiry-loss-rg10")).minorUnits)
        val lot = state.lots.single()
        assertEquals(80_000L, lot.remainingFaceValue.minorUnits)
        assertEquals("unknown_after_unallocated_consumption", lot.compositionStatus)
        assertEquals(
            listOf("loaded", "spent", "expired"),
            lot.history.map { it.event },
        )
        val report = state.reports.getValue("cumulative")
        assertEquals(20_000L, report.specialNonCashBonusIncomeMinor)
        assertEquals(30_000L, report.ordinaryExpenseMinor)
        assertEquals(10_000L, report.expiryLossMinor)
        assertEquals(30_000L, report.consumptionMinor)
        assertEquals(100_000L, report.cashOutflowMinor)
        assertEquals(-20_000L, report.netWorthChangeMinor)
        assertEquals(3, state.confirmations.size)
        assertEquals(5, state.evidenceLinks.size)
    }

    @Test
    fun `every frozen idempotency retry is no change with the original returned ids`() {
        val fixture = loadFixture()
        val retries = fixture.allOperations.filter { it.retryOf != null }
        assertEquals(10, retries.size)
        val committed = mutableMapOf<String, Pair<Rg10Runtime, Rg10ExecutionResult.Accepted>>()

        fun commitOriginal(item: Rg10FixtureOperation, runtime: Rg10Runtime) {
            val result = assertIs<Rg10ExecutionResult.Accepted>(runtime.commit(item.operation), item.id)
            committed[item.operation.identity.value] = runtime to result
        }

        val chain = Rg10Runtime(fixture.catalog, fixture.openingTransactions)
        fixture.operations.forEach { commitOriginal(it, chain) }
        commitOriginal(
            fixture.allOperations.first { it.operation is Rg10Operation.ReconcileMerchantCredit },
            chain,
        )
        commitOriginal(
            fixture.allOperations.first { it.operation is Rg10Operation.ReconcileBankPayment },
            chain,
        )
        val importRuntime = Rg10Runtime(fixture.catalog, fixture.openingTransactions)
        fixture.operations.take(1).forEach { commitOriginal(it, importRuntime) }
        commitOriginal(
            fixture.allOperations.first { it.operation is Rg10Operation.IngestStoredValueRechargeCandidate },
            importRuntime,
        )
        commitOriginal(
            fixture.allOperations.first { it.operation is Rg10Operation.IngestStoredValueSpendCandidate },
            importRuntime,
        )
        val activationRuntime = Rg10Runtime(fixture.catalog, fixture.openingTransactions)
        commitOriginal(
            fixture.allOperations.first { it.operation is Rg10Operation.ConfirmStoredValueActivationBalance },
            activationRuntime,
        )
        val allocationRuntime = fixture.baselines.getValue("state-rg10-merchant-allocation-baseline")
        commitOriginal(
            fixture.allOperations.first { it.operation is Rg10Operation.ApplyMerchantLotAllocation },
            allocationRuntime,
        )

        retries.forEach { retry ->
            val (runtime, original) = committed.getValue(retry.retryOf!!)
            val result = runtime.commit(retry.operation)
            assertEquals(Rg10ExecutionResult.NoChange(original.returnedIds), result, retry.id)
            assertEquals("no_change", retry.expectedStatus, retry.id)
        }
    }

    @Test
    fun `reconciliation links only the matching posting role and never changes balances`() {
        val fixture = loadFixture()
        val runtime = Rg10Runtime(fixture.catalog, fixture.openingTransactions)
        fixture.operations.take(2).forEach { item ->
            assertIs<Rg10ExecutionResult.Accepted>(runtime.commit(item.operation), item.id)
        }
        val before = runtime.snapshot()
        val merchant = fixture.allOperations.first {
            it.operation is Rg10Operation.ReconcileMerchantCredit
        }
        val bank = fixture.allOperations.first {
            it.operation is Rg10Operation.ReconcileBankPayment
        }
        assertIs<Rg10ExecutionResult.Accepted>(runtime.commit(merchant.operation), merchant.id)
        assertEquals("matched", runtime.snapshot().reconciliation["posting-stored-recharge-rg10"])
        assertEquals("pending", runtime.snapshot().reconciliation["posting-bank-recharge-rg10"])

        // Merchant evidence can never reconcile the bank posting. The attempt uses the bank
        // source identity on a separate runtime so it cannot poison the bank receipt.
        val wrongRuntime = Rg10Runtime(fixture.catalog, fixture.openingTransactions)
        fixture.operations.take(2).forEach { item ->
            assertIs<Rg10ExecutionResult.Accepted>(wrongRuntime.commit(item.operation), item.id)
        }
        assertIs<Rg10ExecutionResult.Accepted>(wrongRuntime.commit(merchant.operation))
        val wrongTarget = Rg10Operation.ReconcileMerchantCredit(
            ledgerId = fixture.ledgerId,
            input = Rg10ReconcileInput(
                sourceId = Rg10SourceRecordId("source-bank-payment-rg10"),
                evidenceId = Rg10EvidenceId("evidence-bank-payment-rg10"),
                role = "stored_value_asset_posting",
                targetPostingId = com.unifiedledger.domain.PostingId("posting-bank-recharge-rg10"),
                explicitConfirmation = true,
            ),
        )
        val wrongResult = wrongRuntime.commit(wrongTarget)
        assertEquals(
            Rg10RejectionReason.EVIDENCE_ROLE_TARGET_MISMATCH,
            assertIs<Rg10ExecutionResult.Rejected>(wrongResult).reason,
        )
        assertEquals("pending", wrongRuntime.snapshot().reconciliation["posting-bank-recharge-rg10"])

        assertIs<Rg10ExecutionResult.Accepted>(runtime.commit(bank.operation), bank.id)
        val after = runtime.snapshot()
        assertEquals("matched", after.reconciliation["posting-bank-recharge-rg10"])
        assertEquals(before.balances, after.balances)
        assertEquals(before.reports, after.reports)
        assertEquals(before.formalTransactions.size, after.formalTransactions.size)
    }

    @Test
    fun `imported candidates stay pending with zero formal effect and confirmations are rejected`() {
        val fixture = loadFixture()
        val rechargeRuntime = Rg10Runtime(fixture.catalog, fixture.openingTransactions)
        val ingestRecharge = fixture.allOperations.first {
            it.operation is Rg10Operation.IngestStoredValueRechargeCandidate
        }
        assertIs<Rg10ExecutionResult.Accepted>(rechargeRuntime.commit(ingestRecharge.operation))
        val pending = rechargeRuntime.snapshot()
        assertEquals("pending_confirmation", pending.candidates.single().status)
        assertEquals(1, pending.formalTransactions.size)
        assertEquals(0, pending.lots.size)
        assertIs<Rg10ExecutionResult.NoChange>(rechargeRuntime.commit(ingestRecharge.operation))

        // The incomplete confirmation is an alternative opening-baseline branch with the same
        // request identity, so it runs on a fresh runtime and stays atomically rejected.
        val incompleteRuntime = Rg10Runtime(fixture.catalog, fixture.openingTransactions)
        val incompleteRecharge = fixture.allOperations.first {
            it.operation is Rg10Operation.ConfirmImportedStoredValueRecharge
        }
        val incompleteResult = incompleteRuntime.commit(incompleteRecharge.operation)
        assertEquals(
            Rg10RejectionReason.BANK_PAYMENT_MODEL_AND_ALL_RECHARGE_FACTS_REQUIRED,
            assertIs<Rg10ExecutionResult.Rejected>(incompleteResult).reason,
        )
        assertEquals(1, incompleteRuntime.snapshot().formalTransactions.size)
        assertEquals(0, incompleteRuntime.snapshot().candidates.size)
        assertEquals(pending, rechargeRuntime.snapshot())

        val spendRuntime = Rg10Runtime(fixture.catalog, fixture.openingTransactions)
        fixture.operations.take(1).forEach { item ->
            assertIs<Rg10ExecutionResult.Accepted>(spendRuntime.commit(item.operation), item.id)
        }
        val ingestSpend = fixture.allOperations.first {
            it.operation is Rg10Operation.IngestStoredValueSpendCandidate
        }
        assertIs<Rg10ExecutionResult.Accepted>(spendRuntime.commit(ingestSpend.operation))
        assertEquals("pending_confirmation", spendRuntime.snapshot().candidates.single().status)

        val incompleteSpendRuntime = Rg10Runtime(fixture.catalog, fixture.openingTransactions)
        fixture.operations.take(1).forEach { item ->
            assertIs<Rg10ExecutionResult.Accepted>(incompleteSpendRuntime.commit(item.operation), item.id)
        }
        val incompleteSpend = fixture.allOperations.first {
            it.operation is Rg10Operation.ConfirmImportedStoredValueSpend
        }
        val incompleteSpendResult = incompleteSpendRuntime.commit(incompleteSpend.operation)
        assertEquals(
            Rg10RejectionReason.SPEND_CATEGORY_AND_BEHAVIOR_CONFIRMATION_REQUIRED,
            assertIs<Rg10ExecutionResult.Rejected>(incompleteSpendResult).reason,
        )
        assertEquals(2, incompleteSpendRuntime.snapshot().formalTransactions.size)
        assertEquals(0, incompleteSpendRuntime.snapshot().candidates.size)
    }

    @Test
    fun `activation boundary is not recharge and registers replace-not-append reconstruction`() {
        val fixture = loadFixture()
        val runtime = Rg10Runtime(fixture.catalog, fixture.openingTransactions)
        val activation = fixture.allOperations.first {
            it.operation is Rg10Operation.ConfirmStoredValueActivationBalance
        }
        assertIs<Rg10ExecutionResult.Accepted>(runtime.commit(activation.operation), activation.id)
        val state = runtime.snapshot()
        val formal = state.formalTransactions.first {
            it.formalTransaction.transaction.kind == TransactionKind.STORED_VALUE_PRE_ACTIVATION_BALANCE_ADJUSTMENT
        }.formalTransaction
        assertEquals(60_000L, state.balances.getValue(AccountId("asset-stored-value-x")).minorUnits)
        assertEquals(-60_000L, state.balances.getValue(AccountId("equity-stored-value-adjustment-rg10")).minorUnits)
        assertEquals(0, state.lots.size)
        val adjustment = state.adjustments.single()
        assertEquals("adjustment-pre-activation-rg10", adjustment.id.value)
        assertEquals("unknown", adjustment.compositionStatus)
        assertEquals("active_until_replaced", adjustment.replacementStatus)
        val reconstruction = state.reconstructions.single()
        assertEquals("replacement-group-pre-activation-rg10", reconstruction.replacementGroupId)
        assertEquals(com.unifiedledger.domain.StoredValueActiveMode.ADJUSTMENT, reconstruction.activeMode)
        assertTrue(reconstruction.reconstructedTransactionIds.isEmpty())
        assertEquals("created", reconstruction.history.single().event)
        assertEquals(1, state.auditLinks.size)
        assertEquals(1, state.confirmations.size)
        assertIs<Rg10ExecutionResult.NoChange>(runtime.commit(activation.operation))
    }

    @Test
    fun `merchant evidence overrides the default lot order with zero guessing`() {
        val fixture = loadFixture()
        val runtime = fixture.baselines.getValue("state-rg10-merchant-allocation-baseline")
        val allocation = fixture.allOperations.first {
            it.operation is Rg10Operation.ApplyMerchantLotAllocation
        }
        assertIs<Rg10ExecutionResult.Accepted>(runtime.commit(allocation.operation), allocation.id)
        val state = runtime.snapshot()
        val consumption = state.consumptions.single()
        assertEquals("lot-rg10-loaded-first", consumption.lotId.value)
        assertEquals(10_000L, consumption.amount.minorUnits)
        assertEquals("unknown", consumption.paidBonusComposition)
        assertEquals("allocation-merchant-rg10", consumption.allocationId?.value)
        val allocationRow = state.allocations.single()
        assertEquals("merchant_evidence", allocationRow.allocationSource)
        assertEquals("source-merchant-allocation-rg10", allocationRow.sourceId.value)
        assertEquals(0L, state.lots.single { it.id.value == "lot-rg10-loaded-first" }.remainingFaceValue.minorUnits)
        assertEquals(
            50_000L,
            state.lots.single { it.id.value == "lot-rg10-expiring-first" }.remainingFaceValue.minorUnits,
        )
        assertIs<Rg10ExecutionResult.NoChange>(runtime.commit(allocation.operation))

        // Guessed paid-first composition is fail-closed even with merchant evidence present.
        val changed = (allocation.operation as Rg10Operation.ApplyMerchantLotAllocation).copy(
            input = allocation.operation.input.copy(
                allocations = listOf(
                    Rg10LotAllocationInput(
                        StoredValueLotId("lot-rg10-loaded-first"),
                        Money.ofMinor(5_000L, cny),
                    ),
                ),
            ),
        )
        // The same identity with a changed fingerprint is an idempotency conflict, never an effect.
        assertEquals(Rg10ExecutionResult.RequestIdentityConflict, runtime.commit(changed))
    }

    @Test
    fun `multi-lot merchant allocation is rejected fail-closed with zero lot effect`() {
        val fixture = loadFixture()
        val runtime = fixture.baselines.getValue("state-rg10-merchant-allocation-baseline")
        val allocation = fixture.allOperations.first {
            it.operation is Rg10Operation.ApplyMerchantLotAllocation
        }.operation as Rg10Operation.ApplyMerchantLotAllocation
        val before = runtime.snapshot()
        // Two lots with matching totals make a valid plan of size two, which the single
        // consumption id commit shape cannot represent; it must reject before any lot
        // remaining is changed (GAP-05 fail-closed).
        val multiLot = allocation.copy(
            input = allocation.input.copy(
                requestId = RequestId("request-multi-lot-allocation-rg10"),
                amount = Money.ofMinor(20_000L, cny),
                allocations = listOf(
                    Rg10LotAllocationInput(StoredValueLotId("lot-rg10-expiring-first"), Money.ofMinor(10_000L, cny)),
                    Rg10LotAllocationInput(StoredValueLotId("lot-rg10-loaded-first"), Money.ofMinor(10_000L, cny)),
                ),
            ),
            ids = Rg10AllocationCommitIds(
                allocationId = Rg10AllocationId("allocation-multi-lot-rg10"),
                consumptionId = Rg10ConsumptionId("consumption-multi-lot-rg10"),
            ),
        )
        val result = runtime.commit(multiLot)
        assertEquals(
            Rg10RejectionReason.DOMAIN_REJECTED,
            assertIs<Rg10ExecutionResult.Rejected>(result).reason,
        )
        assertEquals(before, runtime.snapshot())
        // The single-lot fixture allocation still commits afterwards on the untouched baseline.
        assertIs<Rg10ExecutionResult.Accepted>(runtime.commit(allocation))
    }

    @Test
    fun `reconciliation rejects a mismatched claimed evidence role with zero effect`() {
        val fixture = loadFixture()
        val runtime = Rg10Runtime(fixture.catalog, fixture.openingTransactions)
        fixture.operations.take(2).forEach { item ->
            assertIs<Rg10ExecutionResult.Accepted>(runtime.commit(item.operation), item.id)
        }
        val before = runtime.snapshot()
        // A bank claim on the merchant op is rejected before any source/evidence lookup;
        // the fresh source identity keeps the receipt separate from the real merchant op.
        val wrongRole = Rg10Operation.ReconcileMerchantCredit(
            ledgerId = fixture.ledgerId,
            input = Rg10ReconcileInput(
                sourceId = Rg10SourceRecordId("source-role-mismatch-rg10"),
                evidenceId = Rg10EvidenceId("evidence-merchant-credit-rg10"),
                role = "bank_payment_posting",
                targetPostingId = com.unifiedledger.domain.PostingId("posting-stored-recharge-rg10"),
                explicitConfirmation = true,
            ),
        )
        val result = runtime.commit(wrongRole)
        assertEquals(
            Rg10RejectionReason.EVIDENCE_ROLE_TARGET_MISMATCH,
            assertIs<Rg10ExecutionResult.Rejected>(result).reason,
        )
        assertEquals(before, runtime.snapshot())
        // The correctly claimed merchant role still reconciles the owning posting.
        val merchant = fixture.allOperations.first {
            it.operation is Rg10Operation.ReconcileMerchantCredit
        }
        assertIs<Rg10ExecutionResult.Accepted>(runtime.commit(merchant.operation), merchant.id)
        assertEquals("matched", runtime.snapshot().reconciliation["posting-stored-recharge-rg10"])
    }

    @Test
    fun `spend with mismatched consumption commit ids rejects with zero formal effect`() {
        val fixture = loadFixture()
        val runtime = Rg10Runtime(fixture.catalog, fixture.openingTransactions)
        fixture.operations.take(2).forEach { item ->
            assertIs<Rg10ExecutionResult.Accepted>(runtime.commit(item.operation), item.id)
        }
        val before = runtime.snapshot()
        val spend = fixture.operations[1].operation as Rg10Operation.ConfirmStoredValueSpend
        val mismatched = spend.copy(
            input = spend.input.copy(requestId = RequestId("request-spend-mismatch-rg10")),
            ids = spend.ids.copy(
                transactionId = com.unifiedledger.domain.TransactionId("transaction-spend-mismatch-rg10"),
                versionId = com.unifiedledger.domain.TransactionVersionId("version-spend-mismatch-rg10-v1"),
                postingSetId = com.unifiedledger.domain.PostingSetId("posting-set-spend-mismatch-rg10"),
                expensePostingId = com.unifiedledger.domain.PostingId("posting-expense-mismatch-rg10"),
                storedValuePostingId = com.unifiedledger.domain.PostingId("posting-stored-mismatch-rg10"),
                confirmationId = Rg10ConfirmationId("confirmation-spend-mismatch-rg10"),
                consumptions = emptyList(),
                lotHistoryIds = emptyList(),
            ),
        )
        val result = runtime.commit(mismatched)
        assertEquals(
            Rg10RejectionReason.DOMAIN_REJECTED,
            assertIs<Rg10ExecutionResult.Rejected>(result).reason,
        )
        assertEquals(before, runtime.snapshot())
    }

    @Test
    fun `default allocation proves expiry then load then stable id ordering`() {
        val fixture = loadFixture()
        val base = fixture.baseLots(
            "lot-rg10-expiring-first",
            "lot-rg10-loaded-first",
            "lot-rg10-stable-first",
            "lot-rg10-stable-second",
        )
        val runtime = Rg10Runtime(
            fixture.catalog,
            Rg10Snapshot(
                formalTransactions = emptyList(),
                lots = base,
                consumptions = emptyList(),
                allocations = emptyList(),
                adjustments = emptyList(),
                reconstructions = emptyList(),
                candidates = emptyList(),
                confirmations = emptyList(),
                sourceRecords = emptyList(),
                evidence = emptyList(),
                evidenceLinks = emptyList(),
                auditLinks = emptyList(),
                postingSemantics = emptyMap(),
                balances = emptyMap(),
                reports = emptyMap(),
                reconciliation = emptyMap(),
            ),
        )
        val spend = Rg10Operation.ConfirmStoredValueSpend(
            ledgerId = fixture.ledgerId,
            input = Rg10ConfirmSpendInput(
                requestId = RequestId("request-multi-lot-rg10"),
                model = "stored_value_asset",
                behavior = "stored_value_spend",
                storedValueAccountId = AccountId("asset-stored-value-x"),
                categoryId = CategoryId("expense-category-meal-rg10"),
                amount = Money.ofMinor(80_000L, cny),
                currency = cny,
                occurredAt = Instant.parse("2026-01-20T12:00:00+08:00"),
                occurredAtText = "2026-01-20T12:00:00+08:00",
                createdAt = Instant.parse("2026-01-20T12:03:00+08:00"),
                createdAtText = "2026-01-20T12:03:00+08:00",
                explicitConfirmation = true,
                confirmsModel = true,
                confirmsBehavior = true,
                confirmsStoredValueAccount = true,
                confirmsAmount = true,
                confirmsActualTime = true,
                confirmsCategory = true,
                merchantAllocationProvided = false,
                confirmsLotAllocation = true,
            ),
            ids = Rg10SpendCommitIds(
                transactionId = com.unifiedledger.domain.TransactionId("transaction-multi-lot-rg10"),
                versionId = com.unifiedledger.domain.TransactionVersionId("version-multi-lot-rg10-v1"),
                postingSetId = com.unifiedledger.domain.PostingSetId("posting-set-multi-lot-rg10"),
                expensePostingId = com.unifiedledger.domain.PostingId("posting-expense-multi-lot-rg10"),
                storedValuePostingId = com.unifiedledger.domain.PostingId("posting-stored-multi-lot-rg10"),
                confirmationId = Rg10ConfirmationId("confirmation-multi-lot-rg10"),
                consumptions = listOf(
                    Rg10ConsumptionId("consumption-multi-lot-1-rg10"),
                    Rg10ConsumptionId("consumption-multi-lot-2-rg10"),
                    Rg10ConsumptionId("consumption-multi-lot-3-rg10"),
                    Rg10ConsumptionId("consumption-multi-lot-4-rg10"),
                ),
                lotHistoryIds = listOf(
                    "lot-history-multi-lot-1-rg10",
                    "lot-history-multi-lot-2-rg10",
                    "lot-history-multi-lot-3-rg10",
                    "lot-history-multi-lot-4-rg10",
                ),
            ),
        )
        assertIs<Rg10ExecutionResult.Accepted>(runtime.commit(spend))
        val state = runtime.snapshot()
        assertEquals(
            listOf(
                "lot-rg10-expiring-first",
                "lot-rg10-loaded-first",
                "lot-rg10-stable-first",
                "lot-rg10-stable-second",
            ),
            state.consumptions.map { it.lotId.value },
        )
        state.lots.forEach { lot ->
            assertEquals(0L, lot.remainingFaceValue.minorUnits, lot.id.value)
        }
        assertEquals("unknown", state.consumptions.single { it.lotId.value == "lot-rg10-expiring-first" }.paidBonusComposition)
    }

    @Test
    fun `rename keeps stable ids with zero formal intake and reconciliation effect`() {
        val fixture = loadFixture()
        val runtime = Rg10Runtime(fixture.catalog, fixture.openingTransactions)
        fixture.operations.forEach { item ->
            assertIs<Rg10ExecutionResult.Accepted>(runtime.commit(item.operation), item.id)
        }
        val before = runtime.snapshot()
        val rename = fixture.allOperations.first {
            it.operation is Rg10Operation.RenameStoredValueLabels
        }
        assertIs<Rg10ExecutionResult.Accepted>(runtime.commit(rename.operation), rename.id)
        assertEquals(before, runtime.snapshot())
    }

    @Test
    fun `every frozen invalid input rejects with its exact reason and zero effect`() {
        val fixture = loadFixture()
        val runtime = Rg10Runtime(fixture.catalog, fixture.openingTransactions)
        val invalid = fixture.allOperations.filter { it.operation is Rg10Operation.InvalidInput }
        assertEquals(20, invalid.size)
        invalid.forEach { item ->
            val before = runtime.snapshot()
            val result = runtime.commit(item.operation)
            val rejected = assertIs<Rg10ExecutionResult.Rejected>(result, item.id)
            assertEquals(item.expectedReason, rejected.reason.code, item.id)
            assertEquals(before, runtime.snapshot(), "${item.id} must not change state")
        }
    }

    @Test
    fun `forbidden side effects are structurally impossible`() {
        val fixture = loadFixture()
        val runtime = Rg10Runtime(fixture.catalog, fixture.openingTransactions)
        fixture.operations.forEach { item ->
            assertIs<Rg10ExecutionResult.Accepted>(runtime.commit(item.operation), item.id)
        }
        val recharge = fixture.operations.first().operation as Rg10Operation.ConfirmStoredValueRecharge
        val spend = fixture.operations[1].operation as Rg10Operation.ConfirmStoredValueSpend

        // No hidden clearing leg: recharge has exactly three postings, spend exactly two.
        val rechargeTx = runtime.snapshot().formalTransactions
            .first { it.formalTransaction.transaction.kind == TransactionKind.STORED_VALUE_RECHARGE }
        assertEquals(3, rechargeTx.formalTransaction.currentPostings().size)
        val spendTx = runtime.snapshot().formalTransactions
            .first { it.formalTransaction.transaction.kind == TransactionKind.STORED_VALUE_SPEND }
        assertEquals(2, spendTx.formalTransaction.currentPostings().size)

        // Duplicate effect on retry is NoChange, never a second transaction.
        val beforeRetry = runtime.snapshot()
        assertIs<Rg10ExecutionResult.NoChange>(runtime.commit(spend))
        assertEquals(beforeRetry.formalTransactions.size, runtime.snapshot().formalTransactions.size)

        // Spend over the effective balance is rejected atomically.
        val overBalance = spend.copy(
            input = spend.input.copy(
                requestId = RequestId("request-rg10-over-balance"),
                amount = Money.ofMinor(90_001L, cny),
            ),
            ids = spend.ids.copy(
                transactionId = com.unifiedledger.domain.TransactionId("transaction-rg10-over-balance"),
                versionId = com.unifiedledger.domain.TransactionVersionId("version-rg10-over-balance-v1"),
                postingSetId = com.unifiedledger.domain.PostingSetId("posting-set-rg10-over-balance"),
                expensePostingId = com.unifiedledger.domain.PostingId("posting-expense-over-balance-rg10"),
                storedValuePostingId = com.unifiedledger.domain.PostingId("posting-stored-over-balance-rg10"),
                confirmationId = Rg10ConfirmationId("confirmation-over-balance-rg10"),
                consumptions = listOf(Rg10ConsumptionId("consumption-over-balance-rg10")),
                lotHistoryIds = listOf("lot-history-over-balance-rg10"),
            ),
        )
        val overResult = runtime.commit(overBalance)
        assertEquals(
            Rg10RejectionReason.INSUFFICIENT_EFFECTIVE_STORED_BALANCE,
            assertIs<Rg10ExecutionResult.Rejected>(overResult).reason,
        )

        // Unconfirmed expiry never creates a loss transaction.
        val reminderOnly = runtime.snapshot()
        val unconfirmed = fixture.allOperations.first {
            it.operation is Rg10Operation.InvalidInput &&
                it.id == "unconfirmed-expiry"
        }
        assertIs<Rg10ExecutionResult.Rejected>(runtime.commit(unconfirmed.operation))
        assertEquals(1, reminderOnly.formalTransactions.count {
            it.formalTransaction.transaction.kind == TransactionKind.STORED_VALUE_EXPIRY_LOSS
        })
        assertEquals(reminderOnly, runtime.snapshot())

        // Recharge itself never consumes stored value.
        val rechargeRetry = runtime.commit(recharge)
        assertIs<Rg10ExecutionResult.NoChange>(rechargeRetry)
        assertEquals(1, runtime.snapshot().consumptions.size)
    }

    @Test
    fun `gap-05 numeric level is never inferred and gap-06 legacy link status is not mapped`() {
        val fixture = loadFixture()
        // Category without any explicit parent identity: the numeric level path stays
        // fail-closed and no parent is guessed, so a formal spend cannot resolve it.
        val levelOnlyCatalog = when (
            val created = LedgerCatalog.create(
                fixture.catalog.accounts,
                listOf(
                    Category(
                        id = CategoryId("expense-category-meal-rg10"),
                        ledgerId = fixture.ledgerId,
                        parentId = null,
                        postingAccountId = AccountId("expense-consumption-rg10"),
                        active = true,
                        kind = CategoryKind.EXPENSE,
                    ),
                ),
            )
        ) {
            is DomainResult.Success -> created.value
            is DomainResult.Failure -> error("invalid level-only catalog")
        }
        val levelOnlyRuntime = Rg10Runtime(levelOnlyCatalog, fixture.openingTransactions)
        assertIs<Rg10ExecutionResult.Accepted>(levelOnlyRuntime.commit(fixture.operations[0].operation))
        val spend = fixture.operations[1].operation as Rg10Operation.ConfirmStoredValueSpend
        val gap05 = levelOnlyRuntime.commit(
            spend.copy(
                input = spend.input.copy(requestId = RequestId("request-rg10-gap05")),
                ids = spend.ids.copy(
                    transactionId = com.unifiedledger.domain.TransactionId("transaction-gap05-rg10"),
                    versionId = com.unifiedledger.domain.TransactionVersionId("version-gap05-rg10-v1"),
                    postingSetId = com.unifiedledger.domain.PostingSetId("posting-set-gap05-rg10"),
                    expensePostingId = com.unifiedledger.domain.PostingId("posting-expense-gap05-rg10"),
                    storedValuePostingId = com.unifiedledger.domain.PostingId("posting-stored-gap05-rg10"),
                    confirmationId = Rg10ConfirmationId("confirmation-gap05-rg10"),
                    consumptions = listOf(Rg10ConsumptionId("consumption-gap05-rg10")),
                    lotHistoryIds = listOf("lot-history-gap05-rg10"),
                ),
            ),
        )
        assertEquals(
            Rg10RejectionReason.ACTIVE_SECONDARY_CATEGORY_REQUIRED,
            assertIs<Rg10ExecutionResult.Rejected>(gap05).reason,
        )

        // GAP-06: the legacy mixed role is never emitted; evidence links carry only the v2
        // split roles, and legacy not_present reconciliation maps to record absence.
        val legacyRuntime = Rg10Runtime(fixture.catalog, fixture.openingTransactions)
        fixture.operations.forEach { item ->
            assertIs<Rg10ExecutionResult.Accepted>(legacyRuntime.commit(item.operation), item.id)
        }
        val emittedRoles = legacyRuntime.snapshot().evidenceLinks.map { it.role }.toSet()
        assertEquals(
            setOf(
                "bank_payment_posting",
                "stored_value_asset_posting",
                "stored_value_lot_fact",
                "stored_value_bonus_component",
                "stored_value_expiry_confirmation",
            ),
            emittedRoles,
        )
        assertTrue("stored_value_credit_lot" !in emittedRoles)
        assertEquals(
            setOf("pending"),
            legacyRuntime.snapshot().reconciliation.values.toSet(),
        )
    }

    private fun loadFixture(): Rg10FixtureCase = adaptRg10Fixture(
        Files.readString(repositoryFile("golden/rules/rg-10.json")),
        parseRg10FixtureInputs(Files.readString(repositoryFile("tests/fixtures/rg10-runtime-input.json"))),
    )

    private fun repositoryFile(relative: String): java.nio.file.Path {
        var candidate = java.nio.file.Path.of(System.getProperty("user.dir"))
        repeat(8) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)
            candidate = candidate.parent ?: error("repository root not found")
        }
        error("repository root not found")
    }
}

private fun Rg10FixtureCase.baseLots(vararg expectedIds: String): List<StoredValueLot> {
    val raw = Json.parseToJsonElement(
        Files.readString(repositoryFile("golden/rules/rg-10.json")),
    ).jsonObject.getValue("secondary_cases").jsonObject.getValue("multi_lot_allocation").jsonObject
        .getValue("base").jsonObject.getValue("lots").jsonArray
    val currency = CurrencyUnit("CNY", 2)
    val lots = raw.map { element ->
        val lot = element.jsonObject
        StoredValueLot(
            id = StoredValueLotId(lot.stringValue("id")),
            rechargeTransactionId = null,
            loadedAt = Instant.parse(lot.stringValue("loaded_at")),
            expiresAt = Instant.parse(lot.stringValue("expires_at")),
            faceValue = Money.ofMinor(lot.stringValue("face_value").toMinor(), currency),
            remainingFaceValue = Money.ofMinor(lot.stringValue("remaining_face_value").toMinor(), currency),
            paidAmount = Money.ofMinor(lot.stringValue("paid_amount").toMinor(), currency),
            bonusAmount = Money.ofMinor(lot.stringValue("bonus_amount").toMinor(), currency),
            remainingPaidAmount = null,
            remainingBonusAmount = null,
            compositionStatus = "known",
            history = emptyList(),
            merchantId = null,
            loadedAtText = lot.stringValue("loaded_at"),
            expiresAtText = lot.stringValue("expires_at"),
        )
    }
    assertEquals(expectedIds.toList(), lots.map { it.id.value })
    return lots
}

private fun kotlinx.serialization.json.JsonObject.stringValue(key: String): String =
    getValue(key).jsonPrimitive.content

private fun String.toMinor(): Long {
    val parts = split('.')
    return parts[0].toLong() * 100L + parts[1].toLong()
}

private fun repositoryFile(relative: String): java.nio.file.Path {
    var candidate = java.nio.file.Path.of(System.getProperty("user.dir"))
    repeat(8) {
        if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)
        candidate = candidate.parent ?: error("repository root not found")
    }
    error("repository root not found")
}
