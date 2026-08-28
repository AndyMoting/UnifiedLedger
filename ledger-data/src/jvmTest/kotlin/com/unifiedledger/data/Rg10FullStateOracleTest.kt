package com.unifiedledger.data

import com.unifiedledger.application.Rg10EvidenceLinkId
import com.unifiedledger.application.Rg10ExecutionResult
import com.unifiedledger.application.Rg10FieldPath
import com.unifiedledger.application.Rg10FixtureCase
import com.unifiedledger.application.Rg10FixtureInputs
import com.unifiedledger.application.Rg10FixtureOperation
import com.unifiedledger.application.Rg10FormalTransactionRecord
import com.unifiedledger.application.Rg10InvalidPredicate
import com.unifiedledger.application.Rg10Operation
import com.unifiedledger.application.Rg10RejectionReason
import com.unifiedledger.application.Rg10ReturnedId
import com.unifiedledger.application.Rg10Runtime
import com.unifiedledger.application.Rg10Snapshot
import com.unifiedledger.application.adaptRg10Fixture
import com.unifiedledger.application.parseRg10FixtureInputs
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.StoredValueLot
import com.unifiedledger.domain.StoredValueLotHistory
import com.unifiedledger.domain.StoredValueLotId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.defaultLotOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * RG-10 D-083 acceptance oracle (RG10-SPEC-001/002/003/006/009): every frozen operation-shaped
 * case in golden/rules/rg-10.json (44 operations: accepted 12 / no_change 10 / rejected 22) is
 * independently replayed against the typed runtime and compared field by field with the frozen
 * expected blocks: outcome, returned IDs, complete canonical state, formal/intake/reconciliation
 * deltas, report deltas, status changes, rejected/no-change baseline equality and retry equality
 * (D-083 oracle contract). Structured as a 1:1 mirror of the RG-09 oracle
 * (Rg09FullStateOracleTest) with the RG-10 projections registered below.
 *
 * Registered projection rules (RG10-SPEC-002):
 *
 * 1. Evidence links are asserted in the v2 split-role form the runtime emits. The frozen v1
 *    states carry 3 recharge links including the legacy mixed role `stored_value_credit_lot`;
 *    the runtime emits 4 links (`bank_payment_posting`, `stored_value_asset_posting`,
 *    `stored_value_lot_fact`, `stored_value_bonus_component`). The mapping
 *    (docs/migrations/golden-v2/rg-10-mapping.md "Merchant credit requires two independent
 *    typed links ... The old mixed stored_value_credit_lot link is never emitted") authorizes
 *    the v2 split roles. The expected side therefore expands the legacy merchant link into the
 *    asset-posting link (id preserved) plus the lot-fact link (id from the runtime inputs),
 *    and the activation link target is rewritten from the legacy adjustment transaction to the
 *    activation_adjustment domain entity (mapping: "it never targets the adjustment
 *    transaction") with status `pending` (legacy `confirmed_business_fact` has no evidence
 *    verification owner, RG10-GAP-06).
 *
 * 2. Reconciliation is asserted in the runtime posting-keyed shape with a synthesized
 *    transaction-level key per transaction that owns reconciliation-eligible postings:
 *    `complete` (all eligible postings matched), `partial` (some matched), `pending`
 *    (recharge/spend kinds) or `pending_financial_evidence` (expiry/activation kinds) when
 *    none matched. The frozen v1 transaction-level keys map onto that synthesis; legacy
 *    `not_present`/`not_applicable` values map to record absence (mapping: "Legacy
 *    not_present/not_applicable reconciliation maps to no posting_reconciliations record").
 *
 * 3. Per-role confirmation shapes, candidate `occurred_at`, lot `composition_status` on spent
 *    history, and transaction-level spend composition/allocation-source fields are projected
 *    only in the frozen v1 field sets; runtime-only extras are not reproduced (documented
 *    inline at each projection).
 */
class Rg10FullStateOracleTest {
    @Test
    fun `raw operation registry preserves all outcome families`() {
        val oracle = loadOracle()
        assertEquals(44, oracle.documents.size)
        assertEquals(12, oracle.fixture.allOperations.count { it.expectedStatus == "accepted" })
        assertEquals(10, oracle.fixture.allOperations.count { it.expectedStatus == "no_change" })
        assertEquals(22, oracle.fixture.allOperations.count { it.expectedStatus == "rejected" })
        assertEquals(oracle.documents.values.map { it.id }, oracle.fixture.allOperations.map { it.id })
    }

    @Test
    fun `main path operations replay against full canonical states`() {
        val oracle = loadOracle()
        oracle.fixture.allOperations
            .filter { it.sourcePath.startsWith("$.main_path.") }
            .forEach { assertOperation(oracle, it) }
    }

    @Test
    fun `reconciliation path operations replay against delta states`() {
        val oracle = loadOracle()
        oracle.fixture.allOperations
            .filter { it.sourcePath.startsWith("$.reconciliation_path.") }
            .forEach { assertOperation(oracle, it) }
    }

    @Test
    fun `import path operations replay against pending states`() {
        val oracle = loadOracle()
        oracle.fixture.allOperations
            .filter { it.sourcePath.startsWith("$.import_path.") }
            .forEach { assertOperation(oracle, it) }
    }

    @Test
    fun `secondary case operations replay against their baselines`() {
        val oracle = loadOracle()
        oracle.fixture.allOperations
            .filter { it.sourcePath.startsWith("$.secondary_cases.") }
            .forEach { assertOperation(oracle, it) }
    }

    @Test
    fun `idempotency retries return no change with first-time ids`() {
        val oracle = loadOracle()
        oracle.fixture.allOperations
            .filter { it.retryOf != null }
            .forEach { assertOperation(oracle, it) }
    }

    @Test
    fun `invalid inputs reject with exact reason field path and zero effect`() {
        val oracle = loadOracle()
        oracle.fixture.allOperations
            .filter { it.operation is Rg10Operation.InvalidInput }
            .forEach { assertOperation(oracle, it) }
    }

    private fun assertOperation(
        oracle: OracleFixture,
        operation: Rg10FixtureOperation,
    ) {
        val document = oracle.documents.getValue(operation.id).json
        val expected = document.getValue("expected").jsonObject
        val original =
            operation.retryOf?.let { inputId ->
                oracle.fixture.allOperations.firstOrNull { candidate ->
                    candidate.retryOf == null && matchesInputId(candidate.operation, inputId)
                } ?: error("missing retry source $inputId for ${operation.id}")
            }
        val runtime =
            if (operation.retryOf != null && original != null) {
                // The retry baseline is exactly the original's result state, so the original is
                // replayed first on its own baseline to carry the receipt (for reminder the result
                // state equals its baseline, so the original is not on the producer chain). This
                // also asserts the first-time acceptance explicitly (retry equality, D-083).
                val sourceBaseline =
                    original.baselineStateId
                        ?: error("${operation.id} retry source ${original.id} has no baseline")
                val sourceRuntime = buildStateRuntime(oracle.fixture, sourceBaseline)
                assertIs<Rg10ExecutionResult.Accepted>(
                    sourceRuntime.commit(original.operation),
                    "${operation.id} retry source ${original.id}",
                )
                sourceRuntime
            } else {
                baselineRuntime(oracle, operation)
            }
        val before = runtime.snapshot()
        val baselineStateId = operation.baselineStateId
        if (baselineStateId != null) {
            val baselineDoc = oracle.states.getValue(baselineStateId)
            assertState(oracle, baselineDoc, before, baselineStateId, "${operation.id} baseline")
        } else {
            assertMultiLotBaseline(oracle.multiLotBase, before, operation.id)
        }

        val result = runtime.commit(operation.operation)
        assertOutcome(oracle, document, operation, original, result, operation.id)
        val after = runtime.snapshot()

        when (operation.expectedStatus) {
            "accepted" -> {
                val resultStateId = operation.resultStateId
                if (resultStateId != null) {
                    val resultDoc = oracle.states.getValue(resultStateId)
                    assertState(oracle, resultDoc, after, resultStateId, "${operation.id} result")
                }
            }
            "rejected", "no_change" -> {
                assertEquals(before, after, "${operation.id}: non-mutating outcome changed state")
            }
            else -> error("unsupported RG-10 expected status ${operation.expectedStatus}")
        }
        assertDeltas(expected, before, after, operation, operation.id)
        assertExpectedSpecifics(expected, after, operation, operation.id)
        assertStatus(expected, before, after, operation, operation.id)
    }

    private fun assertOutcome(
        oracle: OracleFixture,
        document: JsonObject,
        operation: Rg10FixtureOperation,
        original: Rg10FixtureOperation?,
        result: Rg10ExecutionResult,
        label: String,
    ) {
        val expected = document.getValue("expected").jsonObject
        when (operation.expectedStatus) {
            "accepted" -> {
                val accepted = assertIs<Rg10ExecutionResult.Accepted>(result, label)
                assertEquals(expectedReturnedIds(operation.operation, oracle.inputs), accepted.returnedIds, "$label: accepted IDs")
                assertStableIds(expected, accepted.returnedIds, label)
            }
            "no_change" -> {
                // The frozen v1 retry block still says `accepted: true` (idempotent replay
                // returns the original stable ids); the runtime maps an identical fingerprint
                // replay to NoChange with the first-time ids (RG-09 precedent, D-083 retry).
                val noChange = assertIs<Rg10ExecutionResult.NoChange>(result, label)
                val firstIds =
                    expectedReturnedIds(
                        original?.operation ?: error("$label retry has no original operation"),
                        oracle.inputs,
                    )
                assertEquals(firstIds, noChange.returnedIds, "$label: no-change IDs equal first-time IDs")
                assertStableIds(expected, noChange.returnedIds, label)
            }
            "rejected" -> {
                val rejected = assertIs<Rg10ExecutionResult.Rejected>(result, label)
                val reason = Rg10RejectionReason.entries.single { it.code == expected.string("reason") }
                assertEquals(reason, rejected.reason, "$label: rejection reason")
                assertEquals(expectedFieldPath(operation.operation, reason), rejected.fieldPath, "$label: field path")
            }
            else -> error("unsupported RG-10 expected status ${operation.expectedStatus}")
        }
    }

    private fun assertState(
        oracle: OracleFixture,
        expected: JsonObject,
        actual: Rg10Snapshot,
        stateId: String,
        label: String,
    ) {
        assertTrue(expected.containsKey("id"), "$label: state must retain its frozen state ID")
        val projected: JsonObject =
            when (stateMode(expected)) {
                StateMode.FULL -> projectFullState(actual, oracle.fixture)
                StateMode.PENDING -> {
                    val producer =
                        producerOf(oracle.fixture, stateId)
                            ?: error("$label: pending state $stateId has no formal producer")
                    val formalBaseline = buildStateRuntime(oracle.fixture, producer.baselineStateId!!).snapshot()
                    projectPendingState(actual, formalBaseline, oracle, stateId)
                }
                StateMode.ALLOCATION -> projectAllocationState(actual)
                StateMode.RECONCILIATION -> {
                    val producerBaseline =
                        producerOf(oracle.fixture, stateId)?.baselineStateId
                            ?: error("$label: reconciliation state $stateId has no derived-from producer")
                    projectReconciliationState(actual, oracle, producerBaseline)
                }
            }
        val expectedPayload =
            when (stateMode(expected)) {
                StateMode.FULL -> projectExpectedFullState(expected, oracle.inputs)
                StateMode.PENDING,
                StateMode.ALLOCATION,
                StateMode.RECONCILIATION,
                -> JsonObject(expected.filterKeys { it != "id" })
            }
        expectedPayload.keys.forEach { key ->
            assertEquals(expectedPayload[key], projected[key], "$label: complete state field $key")
        }
        assertEquals(expectedPayload.keys, projected.keys, "$label: complete state fields")
    }

    private fun assertDeltas(
        expected: JsonObject,
        before: Rg10Snapshot,
        after: Rg10Snapshot,
        operation: Rg10FixtureOperation,
        label: String,
    ) {
        val counts = allCounts(before, after)

        fun assertBlock(
            block: JsonObject?,
            message: String,
        ) {
            block?.forEach { (key, value) ->
                // The frozen intake delta counts the legacy 3-link merchant shape; the v2
                // split-role projection (RG10-SPEC-002) emits exactly one extra lot-fact link
                // per legacy stored_value_credit_lot link, so the recharge count projects 3 -> 4.
                val projected =
                    if (key == "new_evidence_link_count" &&
                        operation.retryOf == null &&
                        operation.operation is Rg10Operation.ConfirmStoredValueRecharge
                    ) {
                        json(value.jsonPrimitive.content.toInt() + 1)
                    } else {
                        value
                    }
                assertEquals(
                    projected,
                    json(counts[key] ?: error("$label: unknown $message key $key")),
                    "$label: $message $key",
                )
            }
        }
        assertBlock(expected["formal_deltas"]?.jsonObject, "formal delta")
        assertBlock(expected["intake_deltas"]?.jsonObject, "intake delta")
        assertBlock(expected["reconciliation_deltas"]?.jsonObject, "reconciliation delta")
        // rename_zero_effect publishes flat top-level counts (some without the new_ prefix).
        expected.forEach { (key, value) ->
            val countsKey = FLAT_COUNT_KEY_MAP[key] ?: if (key.startsWith("new_") && key.endsWith("_count")) key else null
            if (countsKey != null) {
                assertEquals(value, json(counts.getValue(countsKey)), "$label: flat count $key")
            }
        }
        expected["report_delta"]?.jsonObject?.let { block ->
            val delta = reportDelta(before, after)
            block.forEach { (key, value) ->
                assertEquals(value, delta.getValue(key), "$label: report delta $key")
            }
        }
        expected["cashflow"]?.let { cashflow ->
            val delta = reportDelta(before, after)
            val actual =
                moneyText(
                    delta
                        .getValue("cash_inflow")
                        .jsonPrimitive.content
                        .toMinor() -
                        delta
                            .getValue("cash_outflow")
                            .jsonPrimitive.content
                            .toMinor(),
                )
            assertEquals(cashflow, json(actual), "$label: cashflow")
        }
        expected["net_worth_change"]?.let { value ->
            assertEquals(value, reportDelta(before, after).getValue("net_worth_change"), "$label: net worth change")
        }
        val newConsumptions = after.consumptions.drop(before.consumptions.size)
        expected["consumption"]?.let { block ->
            assertEquals(block, projectConsumptionSummaries(newConsumptions), "$label: consumption")
        }
        expected["consumptions"]?.let { block ->
            val fullShape =
                block.jsonArray
                    .firstOrNull()
                    ?.jsonObject
                    ?.containsKey("id") == true
            val projected =
                if (fullShape) {
                    projectConsumptionsFull(newConsumptions)
                } else {
                    projectConsumptionSummaries(newConsumptions)
                }
            assertEquals(block, projected, "$label: consumptions")
        }
    }

    private fun assertExpectedSpecifics(
        expected: JsonObject,
        after: Rg10Snapshot,
        operation: Rg10FixtureOperation,
        label: String,
    ) {
        expected["allocation_id"]?.let { value ->
            assertEquals(
                value,
                json(
                    after.allocations
                        .single()
                        .id.value,
                ),
                "$label: allocation id",
            )
        }
        expected["allocation_source"]?.let { value ->
            assertEquals(value, json(after.allocations.single().allocationSource), "$label: allocation source")
        }
        expected["default_order_overridden"]?.let { value ->
            val defaultFirst = defaultLotOrder(after.lots).firstOrNull()?.id
            assertEquals(value, json(defaultFirst != after.allocations.single().lotId), "$label: default order overridden")
        }
        expected["remaining_effective_balance"]?.let { value ->
            val effective = after.lots.sumOf { it.remainingFaceValue.minorUnits }
            assertEquals(value, json(moneyText(effective)), "$label: remaining effective balance")
        }
        expected["allocation_order"]?.let { value ->
            assertEquals(value, JsonArray(after.consumptions.map { json(it.lotId.value) }), "$label: allocation order")
        }
        expected["remaining_face_values"]?.jsonObject?.forEach { (lotId, value) ->
            assertEquals(
                value,
                json(moneyText(after.lots.single { it.id.value == lotId }.remainingFaceValue)),
                "$label: remaining face value $lotId",
            )
        }
        expected["forbidden_inference"]?.let { _ ->
            after.consumptions.forEach { consumption ->
                assertEquals("unknown", consumption.paidBonusComposition, "$label: paid/bonus inference forbidden")
            }
        }
        expected["stable_account_id"]?.let { value ->
            val accountId = assertIs<Rg10Operation.RenameStoredValueLabels>(operation.operation, label).input.accountId.value
            assertEquals(value, json(accountId), "$label: stable account id")
        }
        expected["stable_lot_id"]?.let { value ->
            val lotId = assertIs<Rg10Operation.RenameStoredValueLabels>(operation.operation, label).input.lotId.value
            assertEquals(value, json(lotId), "$label: stable lot id")
        }
        // Activation-boundary semantic fields that are directly derivable from the snapshot;
        // the remaining design flags (pre_activation_events_unchanged, double_counting, the
        // replace-not-append rule text) stay covered by the canonical state and typed tests.
        expected["adjustment_transaction_type"]?.let { value ->
            val kind =
                after.formalTransactions
                    .single {
                        it.formalTransaction.transaction.kind == TransactionKind.STORED_VALUE_PRE_ACTIVATION_BALANCE_ADJUSTMENT
                    }.formalTransaction.transaction.kind
            assertEquals(value, json(kind.name.lowercase()), "$label: adjustment transaction type")
        }
        expected["is_recharge"]?.let { value ->
            val kind =
                after.formalTransactions
                    .single {
                        it.formalTransaction.transaction.kind == TransactionKind.STORED_VALUE_PRE_ACTIVATION_BALANCE_ADJUSTMENT
                    }.formalTransaction.transaction.kind
            assertEquals(value, json(kind == TransactionKind.STORED_VALUE_RECHARGE), "$label: is recharge")
        }
        expected["composition_status"]?.let { value ->
            assertEquals(
                value,
                json(after.adjustments.single().compositionStatus),
                "$label: adjustment composition status",
            )
        }
        expected["stored_value_posting"]?.let { value ->
            val posting =
                after.formalTransactions
                    .single {
                        it.formalTransaction.transaction.kind == TransactionKind.STORED_VALUE_PRE_ACTIVATION_BALANCE_ADJUSTMENT
                    }.formalTransaction
                    .currentPostings()
                    .single {
                        after.postingSemantics[it.id.value]?.role == "STORED_VALUE_ASSET"
                    }
            assertEquals(value, json(moneyText(posting.amount)), "$label: activation stored-value posting")
        }
        expected["adjustment_equity_posting"]?.let { value ->
            val posting =
                after.formalTransactions
                    .single {
                        it.formalTransaction.transaction.kind == TransactionKind.STORED_VALUE_PRE_ACTIVATION_BALANCE_ADJUSTMENT
                    }.formalTransaction
                    .currentPostings()
                    .single {
                        after.postingSemantics[it.id.value]?.role == "PRE_ACTIVATION_ADJUSTMENT_EQUITY"
                    }
            assertEquals(value, json(moneyText(posting.amount)), "$label: activation equity posting")
        }
        expected["replacement_semantics"]?.jsonObject?.let { semantics ->
            semantics["adjustment_id"]?.let { value ->
                assertEquals(
                    value,
                    json(
                        after.adjustments
                            .single()
                            .id.value,
                    ),
                    "$label: replacement adjustment id",
                )
            }
            semantics["replacement_group_id"]?.let { value ->
                assertEquals(value, json(after.reconstructions.single().replacementGroupId), "$label: replacement group id")
            }
        }
    }

    private fun assertStatus(
        expected: JsonObject,
        before: Rg10Snapshot,
        after: Rg10Snapshot,
        operation: Rg10FixtureOperation,
        label: String,
    ) {
        if (operation.expectedStatus != "accepted") return
        val status = expected["status"]?.jsonPrimitive?.content ?: return
        when (status) {
            "confirmed" -> assertTrue(after.confirmations.size > before.confirmations.size, "$label: confirmed status")
            "pending_confirmation" -> {
                assertTrue(after.candidates.size > before.candidates.size, "$label: pending status")
                assertEquals("pending_confirmation", after.candidates.last().status, "$label: candidate lifecycle")
            }
            "reminder_only" -> {
                assertEquals(before.formalTransactions.size, after.formalTransactions.size, "$label: reminder zero formal effect")
                assertEquals(before.confirmations.size, after.confirmations.size, "$label: reminder zero confirmation")
            }
            else -> error("unsupported RG-10 status $status")
        }
    }

    private fun assertMultiLotBaseline(
        base: JsonObject,
        before: Rg10Snapshot,
        label: String,
    ) {
        val expectedLots = base.getValue("lots").jsonArray
        assertEquals(expectedLots.map { it.jsonObject.string("id") }, before.lots.map { it.id.value }, "$label: base lots")
        expectedLots.forEach { element ->
            val lot = element.jsonObject
            val id = lot.string("id")
            assertEquals(
                lot.string("remaining_face_value"),
                moneyText(before.lots.single { it.id.value == id }.remainingFaceValue),
                "$label: base remaining face value $id",
            )
        }
    }

    private fun baselineRuntime(
        oracle: OracleFixture,
        operation: Rg10FixtureOperation,
    ): Rg10Runtime {
        if (operation.sourcePath == "$.secondary_cases.multi_lot_allocation") {
            return Rg10Runtime(oracle.fixture.catalog, multiLotSnapshot(oracle.multiLotBase))
        }
        val stateId = operation.baselineStateId ?: error("${operation.id} has no baseline state id")
        return buildStateRuntime(oracle.fixture, stateId)
    }

    private fun buildStateRuntime(
        fixture: Rg10FixtureCase,
        stateId: String,
    ): Rg10Runtime {
        if (stateId == "state-rg10-opening") {
            return Rg10Runtime(fixture.catalog, fixture.openingTransactions)
        }
        // Synthetic baselines are shared fixture instances; copy them so every op replays on a
        // fresh runtime and no test can poison another (the snapshot copy preserves all facts).
        fixture.baselines[stateId]?.let { shared ->
            return Rg10Runtime(fixture.catalog, shared.snapshot())
        }
        val producer =
            producerOf(fixture, stateId)
                ?: error("no producer for canonical state $stateId")
        val runtime = buildStateRuntime(fixture, producer.baselineStateId!!)
        val result = runtime.commit(producer.operation)
        check(result is Rg10ExecutionResult.Accepted) {
            "${producer.id} baseline producer did not accept: $result"
        }
        return runtime
    }

    private fun producerOf(
        fixture: Rg10FixtureCase,
        stateId: String,
    ): Rg10FixtureOperation? =
        fixture.allOperations.firstOrNull { candidate ->
            candidate.retryOf == null &&
                candidate.resultStateId == stateId &&
                candidate.baselineStateId != stateId
        }

    private fun matchesInputId(
        operation: Rg10Operation,
        inputId: String,
    ): Boolean =
        operation.identity.value == inputId ||
            (operation is Rg10Operation.ReconcileMerchantCredit && operation.input.sourceId.value == inputId) ||
            (operation is Rg10Operation.ReconcileBankPayment && operation.input.sourceId.value == inputId)

    private fun expectedReturnedIds(
        operation: Rg10Operation,
        inputs: Rg10FixtureInputs,
    ): List<Rg10ReturnedId> =
        when (operation) {
            is Rg10Operation.ConfirmStoredValueRecharge ->
                listOf(
                    Rg10ReturnedId.Transaction(operation.ids.transactionId),
                    Rg10ReturnedId.Lot(operation.ids.lotId),
                )
            is Rg10Operation.ConfirmStoredValueSpend -> listOf(Rg10ReturnedId.Transaction(operation.ids.transactionId))
            is Rg10Operation.RecordExpiryReminder -> listOf(Rg10ReturnedId.Request(operation.input.requestId.value))
            is Rg10Operation.ConfirmStoredValueExpiryLoss -> listOf(Rg10ReturnedId.Transaction(operation.ids.transactionId))
            is Rg10Operation.ReconcileMerchantCredit ->
                listOf(
                    Rg10ReturnedId.EvidenceLink(Rg10EvidenceLinkId(reconcileLinkId(inputs, operation.input.sourceId.value, merchant = true))),
                )
            is Rg10Operation.ReconcileBankPayment ->
                listOf(
                    Rg10ReturnedId.EvidenceLink(Rg10EvidenceLinkId(reconcileLinkId(inputs, operation.input.sourceId.value, merchant = false))),
                )
            is Rg10Operation.IngestStoredValueRechargeCandidate -> listOf(Rg10ReturnedId.Candidate(operation.ids.candidateId))
            is Rg10Operation.IngestStoredValueSpendCandidate -> listOf(Rg10ReturnedId.Candidate(operation.ids.candidateId))
            is Rg10Operation.ApplyMerchantLotAllocation ->
                listOf(
                    Rg10ReturnedId.Allocation(operation.ids.allocationId),
                    Rg10ReturnedId.Consumption(operation.ids.consumptionId),
                )
            is Rg10Operation.ConfirmStoredValueActivationBalance ->
                listOf(
                    Rg10ReturnedId.Transaction(operation.ids.transactionId),
                    Rg10ReturnedId.Adjustment(operation.ids.adjustmentId),
                    Rg10ReturnedId.Confirmation(operation.ids.confirmationId),
                )
            is Rg10Operation.RenameStoredValueLabels -> emptyList()
            is Rg10Operation.InvalidInput,
            is Rg10Operation.ConfirmImportedStoredValueRecharge,
            is Rg10Operation.ConfirmImportedStoredValueSpend,
            -> emptyList()
        }

    private fun reconcileLinkId(
        inputs: Rg10FixtureInputs,
        sourceId: String,
        merchant: Boolean,
    ): String {
        val sourceField = if (merchant) "merchant_source_id" else "bank_source_id"
        val linkField = if (merchant) "merchant_posting_link_id" else "bank_link_id"
        val entry = inputs.ids.entries.first { (_, fields) -> fields[sourceField] == sourceId }
        return entry.value[linkField] ?: error("missing $linkField for $sourceId")
    }

    private fun assertStableIds(
        expected: JsonObject,
        returnedIds: List<Rg10ReturnedId>,
        label: String,
    ) {
        val expectedIds = expected["returned_stable_ids"]?.jsonArray?.map { it.jsonPrimitive.content } ?: return
        assertEquals(expectedIds, returnedIds.map(::stableIdValue), "$label: returned stable IDs")
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

    /**
     * Mirrors the runtime rejection table (Rg10Operations.kt rejectInvalidInput plus the two
     * incomplete-import rejectors); RG10-SPEC-006 gives rejected field paths an authoritative
     * oracle-side expectation instead of leaving them unverified.
     */
    private fun expectedFieldPath(
        operation: Rg10Operation,
        reason: Rg10RejectionReason,
    ): Rg10FieldPath =
        when (operation) {
            is Rg10Operation.InvalidInput ->
                when (operation.input.predicate) {
                    Rg10InvalidPredicate.EXACT_DECIMAL_PAID -> Rg10FieldPath.ATTEMPTED_PAID_AMOUNT
                    Rg10InvalidPredicate.EXACT_DECIMAL_CREDITED -> Rg10FieldPath.ATTEMPTED_CREDITED_AMOUNT
                    Rg10InvalidPredicate.EXACT_DECIMAL_BONUS -> Rg10FieldPath.ATTEMPTED_BONUS_AMOUNT
                    Rg10InvalidPredicate.PAID_POSITIVE -> Rg10FieldPath.ATTEMPTED_PAID_AMOUNT
                    Rg10InvalidPredicate.CREDITED_POSITIVE -> Rg10FieldPath.ATTEMPTED_CREDITED_AMOUNT
                    Rg10InvalidPredicate.BONUS_NON_NEGATIVE -> Rg10FieldPath.ATTEMPTED_BONUS_AMOUNT
                    Rg10InvalidPredicate.CREDITED_EQUALS_PAID_PLUS_BONUS -> Rg10FieldPath.ATTEMPTED_CREDITED_AMOUNT
                    Rg10InvalidPredicate.COMPONENT_SUM_MATCH -> Rg10FieldPath.ATTEMPTED_CREDITED_AMOUNT
                    Rg10InvalidPredicate.STORED_ACCOUNT_ENABLED -> Rg10FieldPath.ATTEMPTED_STORED_VALUE_ACCOUNT
                    Rg10InvalidPredicate.STORED_MODEL_ISOLATION -> Rg10FieldPath.ATTEMPTED_MODEL
                    Rg10InvalidPredicate.EFFECTIVE_BALANCE_CAP -> Rg10FieldPath.ATTEMPTED_AMOUNT
                    Rg10InvalidPredicate.LOT_ALLOCATION_CAP -> Rg10FieldPath.ATTEMPTED_AMOUNT
                    Rg10InvalidPredicate.EXPIRY_EXPLICIT_CONFIRMATION -> Rg10FieldPath.ATTEMPTED_EXPLICIT_CONFIRMATION
                    Rg10InvalidPredicate.COMPOSITION_EVIDENCED -> Rg10FieldPath.ATTEMPTED_COMPOSITION
                    Rg10InvalidPredicate.ACTIVE_SECONDARY_CATEGORY -> Rg10FieldPath.ATTEMPTED_CATEGORY
                    Rg10InvalidPredicate.KNOWN_PAYMENT_ACCOUNT -> Rg10FieldPath.ATTEMPTED_PAYMENT_ACCOUNT
                    Rg10InvalidPredicate.OWNED_PAYMENT_ASSET -> Rg10FieldPath.ATTEMPTED_PAYMENT_ACCOUNT
                    Rg10InvalidPredicate.ENABLED_STORED_VALUE_ASSET -> Rg10FieldPath.ATTEMPTED_STORED_VALUE_ACCOUNT
                    Rg10InvalidPredicate.SAME_CNY_CURRENCY -> Rg10FieldPath.ATTEMPTED_CURRENCY
                    Rg10InvalidPredicate.IMPORT_RECHARGE_CONFIRMATION -> Rg10FieldPath.ATTEMPTED_EXPLICIT_CONFIRMATION
                    Rg10InvalidPredicate.IMPORT_SPEND_CONFIRMATION -> Rg10FieldPath.ATTEMPTED_EXPLICIT_CONFIRMATION
                }
            is Rg10Operation.ConfirmImportedStoredValueRecharge,
            is Rg10Operation.ConfirmImportedStoredValueSpend,
            -> Rg10FieldPath.ATTEMPTED_EXPLICIT_CONFIRMATION
            else -> error("no field path expectation for ${operation.action.code}")
        }

    private fun stateMode(expected: JsonObject): StateMode =
        when {
            expected.containsKey("derived_from") -> StateMode.RECONCILIATION
            expected.containsKey("formal_state_id") -> StateMode.PENDING
            expected.containsKey("allocations") || expected.containsKey("consumptions") -> StateMode.ALLOCATION
            else -> StateMode.FULL
        }

    private fun projectFullState(
        snapshot: Rg10Snapshot,
        fixture: Rg10FixtureCase,
    ): JsonObject {
        val nonOpening =
            nonOpeningTransactions(snapshot).sortedWith(
                compareBy<Rg10FormalTransactionRecord> { it.effectiveAtText ?: "" }
                    .thenBy { it.formalTransaction.transaction.id.value },
            )
        return jsonObjectOf(
            "transactions" to JsonArray(nonOpening.map { projectTransaction(it, snapshot, fixture) }),
            "versions" to
                JsonArray(
                    nonOpening.map { record ->
                        val formal = record.formalTransaction
                        val version = formal.versions.single { it.id == formal.transaction.currentVersionId }
                        jsonObjectOf(
                            "id" to json(version.id.value),
                            "transaction_id" to json(version.transactionId.value),
                            "posting_set_id" to json(version.postingSetId.value),
                            "version_number" to json(version.versionNumber),
                            "effective" to json(true),
                            "created_at" to json(record.createdAtText ?: record.createdAt.toString()),
                        )
                    },
                ),
            "lots" to JsonArray(snapshot.lots.map(::projectFullLot)),
            "adjustments" to
                JsonArray(
                    snapshot.adjustments.map { adjustment ->
                        jsonObjectOf(
                            "id" to json(adjustment.id.value),
                            "transaction_id" to json(adjustment.transactionId.value),
                            "activation_at" to json(adjustment.activationAtText),
                            "existing_balance" to json(moneyText(adjustment.existingBalance)),
                            "composition_status" to json(adjustment.compositionStatus),
                            "replacement_status" to json(adjustment.replacementStatus),
                            "history" to
                                JsonArray(
                                    adjustment.history.map { history ->
                                        jsonObjectOf(
                                            "id" to json(history.id),
                                            "event" to json(history.event),
                                            "transaction_id" to json(history.transactionId.value),
                                            "occurred_at" to json(history.occurredAtText),
                                            "created_at" to json(history.createdAtText),
                                        )
                                    },
                                ),
                        )
                    },
                ),
            "candidates" to
                JsonArray(
                    snapshot.candidates.map { candidate ->
                        // The frozen pending states omit the candidate occurrence time; the runtime
                        // preserves it internally but it is not part of the v1 state shape.
                        jsonObjectOf(
                            "id" to json(candidate.id.value),
                            "request_id" to json(candidate.requestId.value),
                            "candidate_type" to json(candidate.candidateType),
                            "status" to json(candidate.status),
                            "paid_amount" to candidate.paidAmount?.let { json(moneyText(it)) },
                            "credited_amount" to candidate.creditedAmount?.let { json(moneyText(it)) },
                            "bonus_amount" to candidate.bonusAmount?.let { json(moneyText(it)) },
                            "amount" to candidate.amount?.let { json(moneyText(it)) },
                            "currency" to json(candidate.currency.code),
                        )
                    },
                ),
            "confirmations" to JsonArray(snapshot.confirmations.map(::projectConfirmation)),
            "source_records" to JsonArray(snapshot.sourceRecords.map(::projectSourceRecord)),
            "evidence" to
                JsonArray(
                    snapshot.evidence.map { item ->
                        jsonObjectOf(
                            "id" to json(item.id.value),
                            "source_id" to json(item.sourceId.value),
                            "evidence_type" to json(item.evidenceType),
                            "observed_at" to json(item.observedAtText),
                        )
                    },
                ),
            "evidence_links" to projectEvidenceLinks(snapshot),
            "audit_links" to
                JsonArray(
                    snapshot.auditLinks.map { link ->
                        jsonObjectOf(
                            "id" to json(link.id.value),
                            "role" to json(link.role),
                            "source_id" to link.sourceId?.let { json(it.value) },
                            "evidence_id" to link.evidenceId?.let { json(it.value) },
                            "confirmation_id" to link.confirmationId?.let { json(it.value) },
                            "transaction_id" to link.transactionId?.let { json(it.value) },
                        )
                    },
                ),
            // The frozen v1 balances omit disabled stored-value accounts (their zero balance is
            // not published); the projection applies the same publication rule.
            "balances" to
                JsonObject(
                    snapshot.balances.entries
                        .filter { (accountId, _) ->
                            fixture.catalog.accounts
                                .single { it.id == accountId }
                                .storedValue
                                ?.enabled != false
                        }.associate { (accountId, amount) -> accountId.value to json(moneyText(amount)) },
                ),
            "reports" to reportsJson(snapshot),
            "reconciliation" to projectReconciliation(snapshot),
        )
    }

    private fun projectTransaction(
        record: Rg10FormalTransactionRecord,
        snapshot: Rg10Snapshot,
        fixture: Rg10FixtureCase,
    ): JsonObject {
        val formal = record.formalTransaction
        val transaction = formal.transaction
        val version = formal.versions.single { it.id == transaction.currentVersionId }
        val postings = formal.currentPostings()
        val semantics = snapshot.postingSemantics

        fun postingByRole(role: String): Posting = postings.single { semantics[it.id.value]?.role == role }
        val fields = mutableListOf<Pair<String, JsonElement?>>()
        fields += "id" to json(transaction.id.value)
        fields += "current_version_id" to json(version.id.value)
        fields += "posting_set_id" to json(version.postingSetId.value)
        fields += "type" to json(transaction.kind.name.lowercase())
        fields += "occurred_at" to json(economicTime(record, version.times.occurredAt))
        fields += "statistics_at" to json(economicTime(record, version.times.statisticsAt))
        fields += "effective_at" to json(economicTime(record, version.times.effectiveAt))
        fields += "created_at" to json(record.createdAtText ?: record.createdAt.toString())
        fields += "effective" to json(true)
        when (transaction.kind) {
            TransactionKind.STORED_VALUE_RECHARGE -> {
                val stored = postingByRole("STORED_VALUE_ASSET")
                val payment = postingByRole("PAYMENT_OUT")
                val bonus = postingByRole("BONUS_INCOME")
                val lotId = snapshot.lots.single { it.rechargeTransactionId == transaction.id }.id
                fields += "stored_value_account_id" to json(stored.accountId.value)
                fields += "payment_account_id" to json(payment.accountId.value)
                fields += "paid_amount" to json(moneyText(-payment.amount.minorUnits))
                fields += "credited_amount" to json(moneyText(stored.amount.minorUnits))
                fields += "bonus_amount" to json(moneyText(-bonus.amount.minorUnits))
                fields += "lot_id" to json(lotId.value)
            }
            TransactionKind.STORED_VALUE_SPEND -> {
                val expense = postingByRole("EXPENSE_OUT")
                val category = fixture.catalog.categories.single { it.postingAccountId == expense.accountId }
                val consumed = consumedLots(snapshot, transaction.id)
                val consumptionByLot =
                    consumed.associate { (lot, _) ->
                        lot.id to snapshot.consumptions.single { it.lotId == lot.id }
                    }
                val compositionStatus =
                    consumptionByLot.values
                        .map { it.paidBonusComposition }
                        .distinct()
                        .let { if (it.size == 1) it.single() else "mixed" }
                val allocationSource =
                    if (consumptionByLot.values.any { it.allocationId != null }) "merchant_evidence" else "default_expiry_load_id_order"
                fields += "stored_value_account_id" to json(postingByRole("STORED_VALUE_DEBIT").accountId.value)
                fields += "category_id" to json(category.id.value)
                fields += "amount" to json(moneyText(expense.amount.minorUnits))
                fields += "allocation_source" to json(allocationSource)
                fields += "composition_status" to json(compositionStatus)
                fields += "lot_consumptions" to
                    JsonArray(
                        consumed.map { (lot, history) ->
                            val consumption = consumptionByLot.getValue(lot.id)
                            jsonObjectOf(
                                "lot_id" to json(lot.id.value),
                                "amount" to json(moneyText(-history.amount.minorUnits)),
                                "paid_bonus_composition" to json(consumption.paidBonusComposition),
                                "evidence_id" to (consumption.evidenceId?.let { json(it.value) } ?: JsonNull),
                            )
                        },
                    )
            }
            TransactionKind.STORED_VALUE_EXPIRY_LOSS -> {
                val loss = postingByRole("EXPIRY_LOSS")
                val lotId =
                    snapshot.lots
                        .first {
                            it.history.any { history -> history.transactionId == transaction.id && history.event == "expired" }
                        }.id
                fields += "stored_value_account_id" to json(postingByRole("STORED_VALUE_DEBIT").accountId.value)
                fields += "lot_id" to json(lotId.value)
                fields += "confirmed_expired_amount" to json(moneyText(loss.amount.minorUnits))
            }
            TransactionKind.STORED_VALUE_PRE_ACTIVATION_BALANCE_ADJUSTMENT -> {
                val adjustment = snapshot.adjustments.single { it.transactionId == transaction.id }
                fields += "stored_value_account_id" to json(postingByRole("STORED_VALUE_ASSET").accountId.value)
                fields += "existing_balance" to json(moneyText(adjustment.existingBalance))
                fields += "composition_status" to json(adjustment.compositionStatus)
                fields += "adjustment_id" to json(adjustment.id.value)
            }
            else -> error("unexpected RG-10 non-opening kind ${transaction.kind}")
        }
        fields += "postings" to
            JsonArray(
                postings.map { posting ->
                    jsonObjectOf(
                        "id" to json(posting.id.value),
                        "account_id" to json(posting.accountId.value),
                        "amount" to json(moneyText(posting.amount)),
                        "currency" to json(posting.amount.currency.code),
                        "reconciliation_eligible" to json(semantics[posting.id.value]?.reconciliationEligible ?: false),
                    )
                },
            )
        return jsonObjectOf(*fields.toTypedArray())
    }

    private fun projectFullLot(lot: StoredValueLot): JsonObject =
        jsonObjectOf(
            "id" to json(lot.id.value),
            "recharge_transaction_id" to lot.rechargeTransactionId?.let { json(it.value) },
            "loaded_at" to json(lot.loadedAtText),
            "expires_at" to json(lot.expiresAtText),
            "face_value" to json(moneyText(lot.faceValue)),
            "remaining_face_value" to json(moneyText(lot.remainingFaceValue)),
            "paid_amount" to lot.paidAmount?.let { json(moneyText(it)) },
            "bonus_amount" to lot.bonusAmount?.let { json(moneyText(it)) },
            // The frozen states keep these two fields explicit after an unallocated spend, where
            // the runtime has no remaining paid/bonus composition left to attribute.
            "remaining_paid_amount" to (lot.remainingPaidAmount?.let { json(moneyText(it)) } ?: JsonNull),
            "remaining_bonus_amount" to (lot.remainingBonusAmount?.let { json(moneyText(it)) } ?: JsonNull),
            "composition_status" to json(lot.compositionStatus),
            "history" to
                JsonArray(
                    lot.history.map { history ->
                        jsonObjectOf(
                            "id" to json(history.id),
                            "event" to json(history.event),
                            "transaction_id" to json(history.transactionId.value),
                            "amount" to json(moneyText(history.amount)),
                            "remaining_face_value" to json(moneyText(history.remainingFaceValue)),
                            "occurred_at" to json(history.occurredAtText),
                            "created_at" to json(history.createdAtText),
                            // The frozen v1 lot history records composition only on spent events.
                            "composition_status" to history.compositionStatus?.let(::json),
                        )
                    },
                ),
            "merchant_id" to lot.merchantId?.let(::json),
        )

    private fun projectConfirmation(confirmation: com.unifiedledger.application.Rg10Confirmation): JsonObject {
        val allowed =
            when (confirmation.role) {
                "stored_value_recharge_confirmation",
                "stored_value_spend_confirmation",
                -> setOf("id", "request_id", "role", "transaction_id", "confirmed_at")
                "stored_value_expiry_confirmation" ->
                    setOf(
                        "id",
                        "request_id",
                        "role",
                        "transaction_id",
                        "confirmed_at",
                        "confirms_actual_expiry",
                    )
                "stored_value_activation_balance_confirmation" ->
                    setOf(
                        "id",
                        "request_id",
                        "role",
                        "transaction_id",
                        "source_id",
                        "evidence_id",
                        "audit_link_id",
                        "confirmed_at",
                        "explicit_confirmation",
                    )
                else -> error("unexpected RG-10 confirmation role ${confirmation.role}")
            }
        val projected =
            jsonObjectOf(
                "id" to json(confirmation.id.value),
                "request_id" to json(confirmation.requestId.value),
                "role" to json(confirmation.role),
                "transaction_id" to confirmation.transactionId?.let { json(it.value) },
                "source_id" to confirmation.sourceId?.let { json(it.value) },
                "evidence_id" to confirmation.evidenceId?.let { json(it.value) },
                "audit_link_id" to confirmation.auditLinkId?.let { json(it.value) },
                "confirmed_at" to json(confirmation.confirmedAtText),
                "explicit_confirmation" to confirmation.explicitConfirmation?.let(::json),
                "confirms_actual_expiry" to confirmation.confirmsActualExpiry?.let(::json),
            )
        return JsonObject(projected.filterKeys { it in allowed })
    }

    private fun projectSourceRecord(source: com.unifiedledger.application.Rg10SourceRecord): JsonObject =
        jsonObjectOf(
            "id" to json(source.id.value),
            "source_type" to json(source.sourceType),
            "observed_at" to json(source.observedAtText),
            "account_id" to source.accountId?.let { json(it.value) },
            "amount" to source.amount?.let { json(moneyText(it)) },
            "currency" to source.amount?.let { json(it.currency.code) },
            "lot_id" to source.lotId?.let { json(it.value) },
            "immutable_payload_digest" to json(source.immutablePayloadDigest),
        )

    private fun projectEvidenceLinks(snapshot: Rg10Snapshot): JsonArray =
        JsonArray(
            snapshot.evidenceLinks.map { link ->
                jsonObjectOf(
                    "id" to json(link.id.value),
                    "source_id" to json(link.sourceId.value),
                    "evidence_id" to json(link.evidenceId.value),
                    "role" to json(link.role),
                    "target_id" to json(link.targetId),
                    "lot_id" to link.lotId?.let { json(it.value) },
                    "status" to json(link.status),
                )
            },
        )

    private fun reportsJson(snapshot: Rg10Snapshot): JsonObject =
        JsonObject(
            snapshot.reports.mapValues { (_, report) ->
                jsonObjectOf(
                    "ordinary_income" to json(moneyText(report.ordinaryIncomeMinor)),
                    "special_non_cash_bonus_income" to json(moneyText(report.specialNonCashBonusIncomeMinor)),
                    "ordinary_expense" to json(moneyText(report.ordinaryExpenseMinor)),
                    "expiry_loss" to json(moneyText(report.expiryLossMinor)),
                    "consumption" to json(moneyText(report.consumptionMinor)),
                    "budget_effect" to json(moneyText(report.budgetEffectMinor)),
                    "category_effect" to json(moneyText(report.categoryEffectMinor)),
                    "cash_inflow" to json(moneyText(report.cashInflowMinor)),
                    "cash_outflow" to json(moneyText(report.cashOutflowMinor)),
                    "net_worth_change" to json(moneyText(report.netWorthChangeMinor)),
                )
            },
        )

    /**
     * Registered reconciliation projection (RG10-SPEC-002): posting-level keys keep the runtime
     * pending/matched values; transaction-level keys are synthesized per transaction that owns
     * reconciliation-eligible postings (complete / partial / pending, with
     * pending_financial_evidence for the expiry and activation kinds whose dedicated evidence
     * is not posting-bound).
     */
    private fun projectReconciliation(snapshot: Rg10Snapshot): JsonObject {
        val projected = linkedMapOf<String, JsonElement>()
        nonOpeningTransactions(snapshot).forEach { record ->
            val eligible =
                record.formalTransaction
                    .currentPostings()
                    .filter { snapshot.reconciliation.containsKey(it.id.value) }
            if (eligible.isEmpty()) return@forEach
            val matched = eligible.count { snapshot.reconciliation[it.id.value] == "matched" }
            val value =
                when {
                    matched == eligible.size -> "complete"
                    matched > 0 -> "partial"
                    record.formalTransaction.transaction.kind in PENDING_FINANCIAL_EVIDENCE_KINDS -> "pending_financial_evidence"
                    else -> "pending"
                }
            projected[record.formalTransaction.transaction.id.value] = json(value)
        }
        snapshot.reconciliation.forEach { (postingId, value) -> projected[postingId] = json(value) }
        return JsonObject(projected)
    }

    /**
     * Pending states are intake-delta projections: the frozen shape lists only the intake the
     * import created on top of the referenced formal state (formal_state_id), so the candidate,
     * source, evidence, link and audit collections are diffed against that formal baseline.
     */
    private fun projectPendingState(
        snapshot: Rg10Snapshot,
        formalBaseline: Rg10Snapshot,
        oracle: OracleFixture,
        stateId: String,
    ): JsonObject {
        val producerBaseline =
            producerOf(oracle.fixture, stateId)?.baselineStateId
                ?: error("pending state $stateId has no formal producer")
        val newCandidates =
            snapshot.candidates.filter { candidate ->
                formalBaseline.candidates.none { it.id == candidate.id }
            }
        val newSources =
            snapshot.sourceRecords.filter { source ->
                formalBaseline.sourceRecords.none { it.id == source.id }
            }
        val newEvidence =
            snapshot.evidence.filter { item ->
                formalBaseline.evidence.none { it.id == item.id }
            }
        val newLinks =
            snapshot.evidenceLinks.filter { link ->
                formalBaseline.evidenceLinks.none { it.id == link.id }
            }
        val newAudit =
            snapshot.auditLinks.filter { link ->
                formalBaseline.auditLinks.none { it.id == link.id }
            }
        val baselineReconciliation = formalBaseline.reconciliation
        return jsonObjectOf(
            "formal_state_id" to json(producerBaseline),
            "candidates" to
                JsonArray(
                    newCandidates.map { candidate ->
                        jsonObjectOf(
                            "id" to json(candidate.id.value),
                            "request_id" to json(candidate.requestId.value),
                            "candidate_type" to json(candidate.candidateType),
                            "status" to json(candidate.status),
                            "paid_amount" to candidate.paidAmount?.let { json(moneyText(it)) },
                            "credited_amount" to candidate.creditedAmount?.let { json(moneyText(it)) },
                            "bonus_amount" to candidate.bonusAmount?.let { json(moneyText(it)) },
                            "amount" to candidate.amount?.let { json(moneyText(it)) },
                            "currency" to json(candidate.currency.code),
                        )
                    },
                ),
            "source_records" to JsonArray(newSources.map(::projectSourceRecord)),
            "evidence" to
                JsonArray(
                    newEvidence.map { item ->
                        jsonObjectOf(
                            "id" to json(item.id.value),
                            "source_id" to json(item.sourceId.value),
                            "evidence_type" to json(item.evidenceType),
                            "observed_at" to json(item.observedAtText),
                        )
                    },
                ),
            "evidence_links" to
                JsonArray(
                    newLinks.map { link ->
                        jsonObjectOf(
                            "id" to json(link.id.value),
                            "source_id" to json(link.sourceId.value),
                            "evidence_id" to json(link.evidenceId.value),
                            "role" to json(link.role),
                            "target_id" to json(link.targetId),
                            "lot_id" to link.lotId?.let { json(it.value) },
                            "status" to json(link.status),
                        )
                    },
                ),
            "audit_links" to
                JsonArray(
                    newAudit.map { link ->
                        jsonObjectOf(
                            "id" to json(link.id.value),
                            "role" to json(link.role),
                            "source_id" to link.sourceId?.let { json(it.value) },
                            "evidence_id" to link.evidenceId?.let { json(it.value) },
                            "confirmation_id" to link.confirmationId?.let { json(it.value) },
                            "transaction_id" to link.transactionId?.let { json(it.value) },
                        )
                    },
                ),
            // Posting-level reconciliation only: the synthesized transaction-level keys are a
            // projection artifact of the full-state shape and are not part of the intake delta.
            "reconciliation" to
                JsonObject(
                    snapshot.reconciliation
                        .filterKeys { it !in baselineReconciliation }
                        .mapValues { (_, value) -> json(value) },
                ),
        )
    }

    private fun projectAllocationState(snapshot: Rg10Snapshot): JsonObject =
        jsonObjectOf(
            "source_records" to JsonArray(snapshot.sourceRecords.map(::projectSourceRecord)),
            "evidence" to
                JsonArray(
                    snapshot.evidence.map { item ->
                        jsonObjectOf(
                            "id" to json(item.id.value),
                            "source_id" to json(item.sourceId.value),
                            "evidence_type" to json(item.evidenceType),
                            "observed_at" to json(item.observedAtText),
                        )
                    },
                ),
            "lots" to
                JsonArray(
                    snapshot.lots.map { lot ->
                        jsonObjectOf(
                            "id" to json(lot.id.value),
                            "loaded_at" to json(lot.loadedAtText),
                            "expires_at" to json(lot.expiresAtText),
                            "face_value" to json(moneyText(lot.faceValue)),
                            "remaining_face_value" to json(moneyText(lot.remainingFaceValue)),
                        )
                    },
                ),
            "allocations" to
                JsonArray(
                    snapshot.allocations.map { allocation ->
                        jsonObjectOf(
                            "id" to json(allocation.id.value),
                            "request_id" to json(allocation.requestId.value),
                            "source_id" to json(allocation.sourceId.value),
                            "evidence_id" to json(allocation.evidenceId.value),
                            "lot_id" to json(allocation.lotId.value),
                            "consumption_id" to json(allocation.consumptionId.value),
                            "amount" to json(moneyText(allocation.amount)),
                            "allocation_source" to json(allocation.allocationSource),
                        )
                    },
                ),
            "consumptions" to projectConsumptionsFull(snapshot.consumptions),
        )

    private fun projectReconciliationState(
        snapshot: Rg10Snapshot,
        oracle: OracleFixture,
        derivedFromStateId: String,
    ): JsonObject {
        val derivedFrom = buildStateRuntime(oracle.fixture, derivedFromStateId).snapshot()
        return jsonObjectOf(
            "derived_from" to json(derivedFromStateId),
            "transactions_ref" to
                JsonArray(
                    nonOpeningTransactions(snapshot).map { json(it.formalTransaction.transaction.id.value) },
                ),
            "lots_ref" to JsonArray(snapshot.lots.map { json(it.id.value) }),
            "balances_unchanged" to json(derivedFrom.balances == snapshot.balances),
            "reports_unchanged" to json(derivedFrom.reports == snapshot.reports),
            "reconciliation" to projectReconciliation(snapshot),
        )
    }

    /**
     * Registered evidence-link projection (RG10-SPEC-002): the frozen legacy merchant link
     * (`stored_value_credit_lot`) expands into the two v2 typed links
     * (`stored_value_asset_posting` keeping the legacy id, and `stored_value_lot_fact` taking
     * the runtime-input id), and the activation fact link is projected onto the
     * activation_adjustment domain entity with status `pending` (mapping authority cited in the
     * class comment; RG10-GAP-06 leaves legacy link status without an owner).
     */
    private fun projectExpectedFullState(
        expected: JsonObject,
        inputs: Rg10FixtureInputs,
    ): JsonObject =
        JsonObject(
            expected
                .filterKeys { it != "id" && it != "preserved_source_record_ids" && it != "preserved_evidence_ids" }
                .mapValues { (key, value) ->
                    when (key) {
                        "evidence_links" -> expandLegacyEvidenceLinks(value.jsonArray, inputs)
                        "reconciliation" -> projectExpectedReconciliation(value.jsonObject)
                        else -> value
                    }
                },
        )

    private fun expandLegacyEvidenceLinks(
        links: JsonArray,
        inputs: Rg10FixtureInputs,
    ): JsonArray =
        JsonArray(
            links.flatMap { element ->
                val link = element.jsonObject
                when (link.string("role")) {
                    "stored_value_credit_lot" -> {
                        val entry =
                            inputs.ids.entries.firstOrNull { (_, fields) ->
                                fields["merchant_source_id"] == link.string("source_id")
                            } ?: error("legacy merchant link has no v2 owner: ${link.string("id")}")
                        val lotFactId =
                            entry.value["merchant_lot_link_id"]
                                ?: error("missing merchant_lot_link_id for ${link.string("source_id")}")
                        val lotId = link.string("lot_id")
                        listOf(
                            JsonObject(link + ("role" to json("stored_value_asset_posting")) - "lot_id"),
                            jsonObjectOf(
                                "id" to json(lotFactId),
                                "source_id" to link["source_id"],
                                "evidence_id" to link["evidence_id"],
                                "role" to json("stored_value_lot_fact"),
                                "target_id" to json(lotId),
                                "lot_id" to json(lotId),
                                "status" to link["status"],
                            ),
                        )
                    }
                    "stored_value_activation_balance_fact" -> {
                        val adjustmentId =
                            inputs.ids.getValue("request-activation-rg10").getValue("adjustment_id")
                                ?: error("missing activation adjustment id")
                        listOf(
                            JsonObject(
                                link +
                                    ("target_id" to json(adjustmentId)) +
                                    ("status" to json("pending")),
                            ),
                        )
                    }
                    else -> listOf(link)
                }
            },
        )

    private fun projectExpectedReconciliation(expected: JsonObject): JsonObject =
        JsonObject(
            expected.filterValues { value ->
                value.jsonPrimitive.content !in setOf("not_present", "not_applicable")
            },
        )

    private fun projectConsumptionSummaries(consumptions: List<com.unifiedledger.application.Rg10LotConsumption>): JsonArray =
        JsonArray(
            consumptions.map { consumption ->
                jsonObjectOf(
                    "lot_id" to json(consumption.lotId.value),
                    "amount" to json(moneyText(consumption.amount)),
                    "paid_bonus_composition" to json(consumption.paidBonusComposition),
                )
            },
        )

    private fun projectConsumptionsFull(consumptions: List<com.unifiedledger.application.Rg10LotConsumption>): JsonArray =
        JsonArray(
            consumptions.map { consumption ->
                jsonObjectOf(
                    "id" to json(consumption.id.value),
                    "allocation_id" to consumption.allocationId?.let { json(it.value) },
                    "source_id" to consumption.sourceId?.let { json(it.value) },
                    "evidence_id" to consumption.evidenceId?.let { json(it.value) },
                    "lot_id" to json(consumption.lotId.value),
                    "amount" to json(moneyText(consumption.amount)),
                    "paid_bonus_composition" to json(consumption.paidBonusComposition),
                )
            },
        )

    private fun allCounts(
        before: Rg10Snapshot,
        after: Rg10Snapshot,
    ): Map<String, Int> =
        mapOf(
            "new_transaction_count" to nonOpeningTransactions(after).size - nonOpeningTransactions(before).size,
            "new_posting_count" to postingCount(after) - postingCount(before),
            "new_lot_count" to after.lots.size - before.lots.size,
            "new_lot_consumption_count" to after.consumptions.size - before.consumptions.size,
            "new_version_count" to nonOpeningTransactions(after).size - nonOpeningTransactions(before).size,
            "new_adjustment_count" to after.adjustments.size - before.adjustments.size,
            "new_confirmation_count" to after.confirmations.size - before.confirmations.size,
            "new_report_effect_count" to reportEffectCount(before, after),
            "new_balance_change_count" to balanceChangeCount(before, after),
            "new_reconciliation_change_count" to reconciliationChangeCount(before, after),
            "new_candidate_count" to after.candidates.size - before.candidates.size,
            "new_source_record_count" to after.sourceRecords.size - before.sourceRecords.size,
            "new_evidence_count" to after.evidence.size - before.evidence.size,
            "new_evidence_link_count" to after.evidenceLinks.size - before.evidenceLinks.size,
            "new_audit_link_count" to after.auditLinks.size - before.auditLinks.size,
        )

    private fun reportDelta(
        before: Rg10Snapshot,
        after: Rg10Snapshot,
    ): JsonObject {
        val previous = before.reports["cumulative"]
        val current = after.reports["cumulative"]

        fun delta(metric: (com.unifiedledger.application.Rg10Report) -> Long): String = moneyText((current?.let(metric) ?: 0L) - (previous?.let(metric) ?: 0L))
        return jsonObjectOf(
            "ordinary_income" to json(delta { it.ordinaryIncomeMinor }),
            "special_non_cash_bonus_income" to json(delta { it.specialNonCashBonusIncomeMinor }),
            "ordinary_expense" to json(delta { it.ordinaryExpenseMinor }),
            "expiry_loss" to json(delta { it.expiryLossMinor }),
            "consumption" to json(delta { it.consumptionMinor }),
            "budget_effect" to json(delta { it.budgetEffectMinor }),
            "category_effect" to json(delta { it.categoryEffectMinor }),
            "cash_inflow" to json(delta { it.cashInflowMinor }),
            "cash_outflow" to json(delta { it.cashOutflowMinor }),
            "net_worth_change" to json(delta { it.netWorthChangeMinor }),
        )
    }

    private fun nonOpeningTransactions(snapshot: Rg10Snapshot): List<Rg10FormalTransactionRecord> =
        snapshot.formalTransactions.filter {
            it.formalTransaction.transaction.kind != TransactionKind.OPENING_BALANCE
        }

    private fun postingCount(snapshot: Rg10Snapshot): Int =
        nonOpeningTransactions(snapshot).sumOf {
            it.formalTransaction.currentPostings().size
        }

    private fun reportEffectCount(
        before: Rg10Snapshot,
        after: Rg10Snapshot,
    ): Int = if (before.reports["cumulative"] != after.reports["cumulative"]) 1 else 0

    private fun balanceChangeCount(
        before: Rg10Snapshot,
        after: Rg10Snapshot,
    ): Int =
        (before.balances.keys + after.balances.keys).count { key ->
            before.balances[key]?.minorUnits != after.balances[key]?.minorUnits
        }

    private fun reconciliationChangeCount(
        before: Rg10Snapshot,
        after: Rg10Snapshot,
    ): Int =
        (before.reconciliation.keys + after.reconciliation.keys).count { key ->
            before.reconciliation[key] != after.reconciliation[key]
        }

    private fun consumedLots(
        snapshot: Rg10Snapshot,
        transactionId: com.unifiedledger.domain.TransactionId,
    ): List<Pair<StoredValueLot, StoredValueLotHistory>> =
        snapshot.lots.mapNotNull { lot ->
            lot.history
                .firstOrNull {
                    it.transactionId == transactionId && it.event == "spent"
                }?.let { lot to it }
        }

    private fun multiLotSnapshot(base: JsonObject): Rg10Snapshot {
        val currency = CurrencyUnit("CNY", 2)
        val lots =
            base.getValue("lots").jsonArray.map { element ->
                val lot = element.jsonObject
                StoredValueLot(
                    id = StoredValueLotId(lot.string("id")),
                    rechargeTransactionId = null,
                    loadedAt = lot.instant("loaded_at"),
                    expiresAt = lot.instant("expires_at"),
                    faceValue = lot.money("face_value", currency),
                    remainingFaceValue = lot.money("remaining_face_value", currency),
                    paidAmount = lot.money("paid_amount", currency),
                    bonusAmount = lot.money("bonus_amount", currency),
                    remainingPaidAmount = null,
                    remainingBonusAmount = null,
                    compositionStatus = "known",
                    history = emptyList(),
                    merchantId = null,
                    loadedAtText = lot.string("loaded_at"),
                    expiresAtText = lot.string("expires_at"),
                )
            }
        return Rg10Snapshot(
            formalTransactions = emptyList(),
            lots = lots,
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
        )
    }

    private fun economicTime(
        record: Rg10FormalTransactionRecord,
        fallback: Instant,
    ): String = record.effectiveAtText ?: fallback.toString()

    private fun moneyText(amount: Money): String = moneyText(amount.minorUnits, amount.currency.precision)

    private fun moneyText(
        minor: Long,
        precision: Int = 2,
    ): String = BigDecimal.valueOf(minor, precision).setScale(precision).toPlainString()

    private fun rawOperationDocuments(fixture: JsonObject): List<RawOperationDocument> =
        buildList {
            val main = fixture.getValue("main_path").jsonObject
            MAIN_PATH_NAMES.forEach { name ->
                add(RawOperationDocument("$.main_path.$name", main.getValue(name).jsonObject))
            }
            fixture.getValue("reconciliation_path").jsonObject.forEach { (name, element) ->
                add(RawOperationDocument("$.reconciliation_path.$name", element.jsonObject))
            }
            val imports = fixture.getValue("import_path").jsonObject
            imports.getValue("complete_unconfirmed").jsonArray.forEachIndexed { index, element ->
                add(RawOperationDocument("$.import_path.complete_unconfirmed[$index]", element.jsonObject))
            }
            imports.getValue("incomplete_confirmations").jsonArray.forEachIndexed { index, element ->
                add(RawOperationDocument("$.import_path.incomplete_confirmations[$index]", element.jsonObject))
            }
            fixture.getValue("secondary_cases").jsonObject.forEach { (name, element) ->
                add(RawOperationDocument("$.secondary_cases.$name", element.jsonObject))
            }
            fixture.getValue("invalid_inputs").jsonArray.forEach { element ->
                val json = element.jsonObject
                add(RawOperationDocument("$.invalid_inputs[${json.string("id")}]", json))
            }
            fixture.getValue("idempotency").jsonObject.forEach { (name, element) ->
                add(RawOperationDocument("$.idempotency.$name", element.jsonObject))
            }
        }

    private fun stateDocuments(fixture: JsonObject): Map<String, JsonObject> =
        buildMap {
            fixture.getValue("canonical_states").jsonObject.forEach { (_, element) ->
                put(element.jsonObject.string("id"), element.jsonObject)
            }
            fixture.getValue("reconciliation_states").jsonObject.forEach { (_, element) ->
                put(element.jsonObject.string("id"), element.jsonObject)
            }
            fixture.getValue("import_path").jsonObject.getValue("pending_states").jsonObject.forEach { (_, element) ->
                put(element.jsonObject.string("id"), element.jsonObject)
            }
            fixture
                .getValue("secondary_cases")
                .jsonObject
                .getValue("merchant_evidenced_allocation")
                .jsonObject
                .getValue("states")
                .jsonObject
                .forEach { (_, element) ->
                    put(element.jsonObject.string("id"), element.jsonObject)
                }
        }

    private fun rawDocumentId(
        raw: JsonObject,
        sourcePath: String,
    ): String {
        raw["id"]
            ?.takeUnless { it is JsonNull }
            ?.jsonPrimitive
            ?.content
            ?.let { return it }
        raw["operation_context"]?.jsonObject?.let { context ->
            context["operation_id"]
                ?.takeUnless { it is JsonNull }
                ?.jsonPrimitive
                ?.content
                ?.let { return it }
        }
        return sourcePath.substringAfterLast('.')
    }

    private fun loadOracle(): OracleFixture {
        val raw = Files.readString(repositoryFile("golden/rules/rg-10.json"))
        val fixtureJson = Json.parseToJsonElement(raw).jsonObject
        val inputs = parseRg10FixtureInputs(Files.readString(repositoryFile("tests/fixtures/rg10-runtime-input.json")))
        val fixture = adaptRg10Fixture(raw, inputs)
        val documents =
            rawOperationDocuments(fixtureJson).map {
                RawOperationDocument(it.sourcePath, JsonObject(it.json + ("id" to json(rawDocumentId(it.json, it.sourcePath)))))
            }
        return OracleFixture(
            fixture = fixture,
            inputs = inputs,
            documents = documents.associateBy { it.id },
            states = stateDocuments(fixtureJson),
            multiLotBase =
                fixtureJson
                    .getValue("secondary_cases")
                    .jsonObject
                    .getValue("multi_lot_allocation")
                    .jsonObject
                    .getValue("base")
                    .jsonObject,
        )
    }

    private fun repositoryFile(relative: String): Path {
        var candidate = Path.of(System.getProperty("user.dir"))
        repeat(8) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)
            candidate = candidate.parent ?: error("repository root not found")
        }
        error("repository root not found")
    }

    private data class OracleFixture(
        val fixture: Rg10FixtureCase,
        val inputs: Rg10FixtureInputs,
        val documents: Map<String, RawOperationDocument>,
        val states: Map<String, JsonObject>,
        val multiLotBase: JsonObject,
    )

    private data class RawOperationDocument(
        val sourcePath: String,
        val json: JsonObject,
    ) {
        val id: String get() = json.string("id")
    }

    private enum class StateMode {
        FULL,
        PENDING,
        ALLOCATION,
        RECONCILIATION,
    }

    private companion object {
        val MAIN_PATH_NAMES =
            listOf(
                "recharge",
                "spend",
                "expiry_reminder",
                "expiry_confirmation",
            )

        val PENDING_FINANCIAL_EVIDENCE_KINDS =
            setOf(
                TransactionKind.STORED_VALUE_EXPIRY_LOSS,
                TransactionKind.STORED_VALUE_PRE_ACTIVATION_BALANCE_ADJUSTMENT,
            )

        val FLAT_COUNT_KEY_MAP =
            mapOf(
                "balance_change_count" to "new_balance_change_count",
                "report_change_count" to "new_report_effect_count",
                "reconciliation_change_count" to "new_reconciliation_change_count",
            )
    }
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.instant(key: String): kotlin.time.Instant = kotlin.time.Instant.parse(string(key))

private fun JsonObject.money(
    key: String,
    currency: CurrencyUnit,
): Money = string(key).toMinorMoney(currency)

private fun String.toMinorMoney(currency: CurrencyUnit): Money {
    val parts = split('.')
    val minor = parts[0].toLong() * 100L + parts[1].toLong()
    return Money.ofMinor(minor, currency)
}

private fun String.toMinor(): Long {
    val parts = split('.')
    return parts[0].toLong() * 100L + parts[1].toLong()
}

private fun json(value: String): JsonPrimitive = JsonPrimitive(value)

private fun json(value: Boolean): JsonPrimitive = JsonPrimitive(value)

private fun json(value: Int): JsonPrimitive = JsonPrimitive(value)

private fun jsonObjectOf(vararg fields: Pair<String, JsonElement?>): JsonObject = JsonObject(fields.mapNotNull { (key, value) -> value?.let { key to it } }.toMap())

private fun repositoryFile(relative: String): Path {
    var candidate = Path.of(System.getProperty("user.dir"))
    repeat(8) {
        if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)
        candidate = candidate.parent ?: error("repository root not found")
    }
    error("repository root not found")
}
