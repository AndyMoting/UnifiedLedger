package com.unifiedledger.data

import com.unifiedledger.application.Rg10ExecutionResult
import com.unifiedledger.application.Rg10FixtureCase
import com.unifiedledger.application.Rg10Operation
import com.unifiedledger.application.Rg10RejectionReason
import com.unifiedledger.application.Rg10ReturnedId
import com.unifiedledger.application.Rg10Runtime
import com.unifiedledger.application.adaptRg10Fixture
import com.unifiedledger.application.parseRg10FixtureInputs
import com.unifiedledger.application.replayRg10Fixture
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.TransactionKind
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class Rg10RawJsonEndToEndTest {
    @Test
    fun `frozen RG-10 operation registry replays through the typed runtime`() {
        val fixture = loadFixture()
        val replay =
            replayRg10Fixture(
                Files.readString(repositoryFile("golden/rules/rg-10.json")),
                loadRuntimeInputs(),
            )
        assertEquals(44, replay.operations.size)
        assertEquals(12, replay.accepted)
        assertEquals(10, replay.noChange)
        assertEquals(22, replay.rejected)
    }

    @Test
    fun `frozen RG-10 main path confirms recharge spend reminder and expiry`() {
        val fixture = loadFixture()
        val runtime = Rg10Runtime(fixture.catalog, fixture.openingTransactions)
        val results =
            fixture.operations.map { operation ->
                val result = runtime.commit(operation.operation)
                assertIs<Rg10ExecutionResult.Accepted>(result, "${operation.id}: $result")
                result
            }
        assertEquals(4, results.size)

        val finalState = runtime.snapshot()
        assertEquals(
            listOf(
                TransactionKind.OPENING_BALANCE,
                TransactionKind.STORED_VALUE_RECHARGE,
                TransactionKind.STORED_VALUE_SPEND,
                TransactionKind.STORED_VALUE_EXPIRY_LOSS,
            ),
            finalState.formalTransactions.map { it.formalTransaction.transaction.kind },
        )
        assertEquals(80_000L, finalState.balances.getValue(AccountId("asset-stored-value-x")).minorUnits)
        assertEquals(10_000L, finalState.balances.getValue(AccountId("expense-expiry-loss-rg10")).minorUnits)
        assertEquals(
            listOf("loaded", "spent", "expired"),
            finalState.lots
                .single()
                .history
                .map { it.event },
        )
        assertEquals(3, finalState.confirmations.size)
        assertEquals(5, finalState.evidenceLinks.size)
        assertEquals(3, finalState.sourceRecords.size)
    }

    @Test
    fun `accepted operation replay is no change and changed input is conflict`() {
        val fixture = loadFixture()
        val runtime = Rg10Runtime(fixture.catalog, fixture.openingTransactions)
        val operation = fixture.operations.first().operation
        assertIs<Rg10ExecutionResult.Accepted>(runtime.commit(operation))
        assertIs<Rg10ExecutionResult.NoChange>(runtime.commit(operation))
        val conflict =
            (operation as Rg10Operation.ConfirmStoredValueRecharge).copy(
                input =
                    operation.input.copy(
                        paidAmount =
                            com.unifiedledger.domain.Money
                                .ofMinor(900_00L, com.unifiedledger.domain.CurrencyUnit("CNY", 2)),
                    ),
            )
        assertIs<Rg10ExecutionResult.RequestIdentityConflict>(runtime.commit(conflict))
        assertEquals(1, runtime.snapshot().lots.size)
        assertEquals(2, runtime.snapshot().sourceRecords.size)
    }

    @Test
    fun `every frozen idempotency retry returns the original stable ids`() {
        val fixture = loadFixture()
        val raw =
            kotlinx.serialization.json.Json
                .parseToJsonElement(
                    Files.readString(repositoryFile("golden/rules/rg-10.json")),
                ).jsonObject
                .getValue("idempotency")
                .jsonObject
        val committed = mutableMapOf<String, Pair<Rg10Runtime, Rg10ExecutionResult.Accepted>>()

        fun commitOriginal(
            item: com.unifiedledger.application.Rg10FixtureOperation,
            runtime: Rg10Runtime,
        ) {
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

        fixture.allOperations.filter { it.retryOf != null }.forEach { retry ->
            val expectedIds =
                raw
                    .getValue(retry.id)
                    .jsonObject
                    .getValue("expected")
                    .jsonObject
                    .getValue("returned_stable_ids")
                    .jsonArray
                    .map { it.jsonPrimitive.content }
            val (runtime, original) = committed.getValue(retry.retryOf!!)
            val result = runtime.commit(retry.operation)
            val noChange = assertIs<Rg10ExecutionResult.NoChange>(result, retry.id)
            assertEquals(expectedIds, noChange.returnedIds.map(::stableIdValue), retry.id)
            assertEquals(original.returnedIds, noChange.returnedIds, retry.id)
        }
    }

    @Test
    fun `reconciliation path matches only the owning posting and persists the transition`() {
        val fixture = loadFixture()
        val runtime = Rg10Runtime(fixture.catalog, fixture.openingTransactions)
        fixture.operations.forEach { item ->
            assertIs<Rg10ExecutionResult.Accepted>(runtime.commit(item.operation), item.id)
        }
        val merchant =
            fixture.allOperations.first {
                it.operation is Rg10Operation.ReconcileMerchantCredit
            }
        val bank =
            fixture.allOperations.first {
                it.operation is Rg10Operation.ReconcileBankPayment
            }
        assertIs<Rg10ExecutionResult.Accepted>(runtime.commit(merchant.operation))
        val merchantState = runtime.snapshot()
        assertEquals("matched", merchantState.reconciliation["posting-stored-recharge-rg10"])
        assertEquals("pending", merchantState.reconciliation["posting-bank-recharge-rg10"])
        assertIs<Rg10ExecutionResult.NoChange>(runtime.commit(merchant.operation))
        assertIs<Rg10ExecutionResult.Accepted>(runtime.commit(bank.operation))
        assertEquals("matched", runtime.snapshot().reconciliation["posting-bank-recharge-rg10"])
        assertIs<Rg10ExecutionResult.NoChange>(runtime.commit(bank.operation))
    }

    @Test
    fun `all frozen invalid inputs reject with their exact reason and preserve baseline`() {
        val fixture = loadFixture()
        val runtime = Rg10Runtime(fixture.catalog, fixture.openingTransactions)
        fixture.allOperations.filter { it.operation is Rg10Operation.InvalidInput }.forEach { item ->
            val before = runtime.snapshot()
            val result = runtime.commit(item.operation)
            val rejected = assertIs<Rg10ExecutionResult.Rejected>(result, item.id)
            assertEquals(item.expectedReason, rejected.reason.code, item.id)
            assertEquals(before, runtime.snapshot(), "${item.id} changed the baseline")
        }
    }

    @Test
    fun `incomplete imported recharge confirmation rejects on its independent opening baseline`() {
        val fixture = loadFixture()
        // v1 fixture semantics (golden/rules/rg-10.json import_path.incomplete_confirmations[0]):
        // the incomplete confirmation is an independent operation with
        // pre_operation_baseline_id=state-rg10-opening, expected accepted=false,
        // reason=bank_payment_model_and_all_recharge_facts_required and state_unchanged=true.
        // It must not be serialized after the same-request ingest on one runtime, where the
        // shared request identity would surface as a retry fingerprint conflict instead.
        val runtime = Rg10Runtime(fixture.catalog, fixture.openingTransactions)
        val incomplete =
            fixture.allOperations.first {
                it.operation is Rg10Operation.ConfirmImportedStoredValueRecharge
            }
        val result = runtime.commit(incomplete.operation)
        assertEquals(
            Rg10RejectionReason.BANK_PAYMENT_MODEL_AND_ALL_RECHARGE_FACTS_REQUIRED,
            assertIs<Rg10ExecutionResult.Rejected>(result).reason,
        )
        val state = runtime.snapshot()
        assertEquals(0, state.candidates.size)
        assertEquals(0, state.sourceRecords.size)
        assertEquals(1, state.formalTransactions.size)
    }

    @Test
    fun `imports never auto-confirm even with complete facts`() {
        val fixture = loadFixture()
        val runtime = Rg10Runtime(fixture.catalog, fixture.openingTransactions)
        val ingestSpend =
            fixture.allOperations.first {
                it.operation is Rg10Operation.IngestStoredValueSpendCandidate
            }
        fixture.operations.take(1).forEach { item ->
            assertIs<Rg10ExecutionResult.Accepted>(runtime.commit(item.operation), item.id)
        }
        assertIs<Rg10ExecutionResult.Accepted>(runtime.commit(ingestSpend.operation))
        val state = runtime.snapshot()
        assertEquals("pending_confirmation", state.candidates.single().status)
        assertEquals(0, state.consumptions.size)
        assertEquals(1, state.lots.size)
        // v1 fixture semantics (golden/rules/rg-10.json import_path.complete_unconfirmed[1]):
        // the ingest is intake-only, formal_deltas are all zero and pending_states.spend
        // points at state-rg10-recharge-confirmed, so the lot is never consumed.
        assertEquals(
            120_000L,
            state.lots
                .single()
                .remainingFaceValue.minorUnits,
        )
        assertEquals(2, state.formalTransactions.size)
    }

    @Test
    fun `merchant allocation runs on its synthetic baseline and retries are stable`() {
        val fixture = loadFixture()
        val runtime = fixture.baselines.getValue("state-rg10-merchant-allocation-baseline")
        val allocation =
            fixture.allOperations.first {
                it.operation is Rg10Operation.ApplyMerchantLotAllocation
            }
        assertIs<Rg10ExecutionResult.Accepted>(runtime.commit(allocation.operation), allocation.id)
        val state = runtime.snapshot()
        assertEquals(1, state.allocations.size)
        assertEquals(1, state.consumptions.size)
        assertEquals(
            listOf("allocation-merchant-rg10", "consumption-merchant-rg10"),
            state.allocations.single().let { listOf(it.id.value, it.consumptionId.value) },
        )
        assertEquals(
            0L,
            state.lots
                .single { it.id.value == "lot-rg10-loaded-first" }
                .remainingFaceValue.minorUnits,
        )
        assertIs<Rg10ExecutionResult.NoChange>(runtime.commit(allocation.operation))
    }

    private fun stableIdValue(id: Rg10ReturnedId): String =
        when (id) {
            is Rg10ReturnedId.Transaction -> id.id.value
            is Rg10ReturnedId.Version -> id.id.value
            is Rg10ReturnedId.Lot -> id.id.value
            is Rg10ReturnedId.Confirmation -> id.id.value
            is Rg10ReturnedId.Candidate -> id.id.value
            is Rg10ReturnedId.EvidenceLink -> id.id.value
            is Rg10ReturnedId.Allocation -> id.id.value
            is Rg10ReturnedId.Consumption -> id.id.value
            is Rg10ReturnedId.Adjustment -> id.id.value
            is Rg10ReturnedId.Request -> id.id
        }

    private fun loadFixture(): Rg10FixtureCase =
        adaptRg10Fixture(
            Files.readString(repositoryFile("golden/rules/rg-10.json")),
            loadRuntimeInputs(),
        )

    private fun loadRuntimeInputs() = parseRg10FixtureInputs(Files.readString(repositoryFile("tests/fixtures/rg10-runtime-input.json")))

    private fun repositoryFile(relative: String): Path {
        var candidate = Path.of(System.getProperty("user.dir"))
        repeat(8) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)
            candidate = candidate.parent ?: error("repository root not found")
        }
        error("repository root not found")
    }
}
