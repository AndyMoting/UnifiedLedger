package com.unifiedledger.data

import com.unifiedledger.application.Rg12ExecutionResult
import com.unifiedledger.application.Rg12FieldPath
import com.unifiedledger.application.Rg12FixtureCase
import com.unifiedledger.application.Rg12FixtureInputs
import com.unifiedledger.application.Rg12FixtureOperation
import com.unifiedledger.application.Rg12RejectionReason
import com.unifiedledger.application.Rg12ReturnedId
import com.unifiedledger.application.Rg12Runtime
import com.unifiedledger.application.Rg12Snapshot
import com.unifiedledger.application.adaptRg12Fixture
import com.unifiedledger.application.parseRg12FixtureInputs
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.ExplicitOperationConfirmation
import com.unifiedledger.domain.PostingReconciliationStatus
import com.unifiedledger.domain.ReconciliationEffect
import com.unifiedledger.domain.ReconciliationMatchStatus
import com.unifiedledger.domain.ReconciliationSummary
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId
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
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * RG-12 D-085 acceptance oracle (shard 4): the frozen direct-v2 contract
 * `golden/rules/rg-12.json` (12 operations: accepted 1 / no_change 1 / rejected 10; root-correction
 * 2, root-rejections 10) is independently replayed against the pure [Rg12Runtime] (no store) and
 * compared field by field with the frozen expected blocks: outcome (returned IDs / reason /
 * field path), complete canonical state (transactions, versions, posting sets, postings,
 * sources, confirmations, evidence, evidence links, domain entities, audit links, posting
 * reconciliations, balances, reports, derived statuses), entity/value deltas, status changes,
 * rejected/no-change baseline equality and retry equality (root-correction-replay returns the
 * first-time ids of root-correction-correct). Mirrors the Rg08/Rg10/Rg11 oracles; the runtime
 * is driven purely through the typed operations ([Rg12FixtureReplay] derives the plan from the
 * frozen contract and the runtime-input anchors).
 *
 * Registered fixture-to-state projection rules:
 *
 * 1. `catalog`: the domain [com.unifiedledger.domain.Account]/[com.unifiedledger.domain.Category]
 *    carry no display `name`, `reconciliation_eligible` or `hidden` fields; the catalog is
 *    static input that no RG-12 action mutates. The per-state catalog is asserted separately on
 *    the representable fields (id/kind/currency/owned_by_user/real_account for accounts,
 *    id/parent_id/posting_account_id/active for categories) and excluded from the per-state
 *    collection comparison.
 *
 * 2. Times render in the case timezone `Asia/Shanghai` (+08:00) with a fixed-offset formatter
 *    (kotlin.time.Instant renders UTC); every frozen time is at +08:00, so the rendering
 *    recovers the frozen strings byte for byte. The per-version `created_at` and `note` and the
 *    per-version `confirmation_id` are projected from the record-level
 *    `versionCreatedAtTexts` / `versionConfirmationIds` maps (the v2 version is created and
 *    confirmed at the frozen `corrected_at`).
 *
 * 3. `sources`, `evidence`, `evidence_links` and `relations` are immutable seeds of the initial
 *    state; no RG-12 action mutates them and the runtime does not maintain them, so the oracle
 *    projects them from the fixture seeds of the root (candidates and relations are always
 *    empty). `audit_links` are the `posting_replacement` links (from/to `posting` endpoints and
 *    the closed `reconciliation_effect` payload).
 *
 * 4. The `domain_entities` collection order is a validator serialization artifact: the golden
 *    validator compares entities by id (test_rg12_golden_v2.py `by_id`), the runtime projects
 *    consumption records first and then matches (append order), so the state comparison
 *    normalizes `domain_entities` to a set of canonical JSON entries. The `status_history`
 *    array inside a match payload is rendered by the runtime as one JSON text and parsed back;
 *    its order IS contractual (append-only sequences).
 *
 * 5. Balances: the runtime emits catalog order while the frozen initial states also use
 *    catalog order but the frozen result states sort by account id; the validator compares
 *    balances as maps, so the order is not contractual and the state comparison normalizes
 *    balances to a set of entries. The delta `balances` value changes stay compared in the
 *    frozen sorted-by-key order (the Python generator demands that order).
 *
 * 6. `posting_reconciliations` ids follow no uniform derivation rule (see Rg12FixtureReplay);
 *    the fixture case supplies the postingId -> reconciliationId anchors (initial-state seeds +
 *    runtime-input `reconciliation_fact_ids`).
 *
 * 7. The runtime `reconciliationSummary()` map carries no status id; the frozen
 *    `derived_statuses` id follows the deterministic `<rootId>-summary` convention of the
 *    fixture, so the projection derives it from the root id of the state being compared.
 *
 * 8. Entity change id lists and balance value changes are compared in the frozen sorted order
 *    (the Python validator and test_rg12_golden_v2.py both demand sorted id lists and sorted
 *    balance keys); report/derived-status value changes and status changes entry order follows
 *    the Python generator's set iteration and is not contractual, so those three are compared
 *    as sets of canonical JSON entries (mirroring test_rg12_golden_v2.py).
 */
class Rg12FullStateOracleTest {
    @Test
    fun `raw operation registry preserves all outcome families`() {
        val oracle = loadOracle()
        assertEquals(12, oracle.operations.size)
        assertEquals(1, oracle.fixture.allOperations.count { it.expectedStatus == "accepted" })
        assertEquals(1, oracle.fixture.allOperations.count { it.expectedStatus == "no_change" })
        assertEquals(10, oracle.fixture.allOperations.count { it.expectedStatus == "rejected" })
        assertEquals(oracle.operations.keys.toList(), oracle.fixture.allOperations.map { it.id })
        assertEquals(setOf("root-partial", "root-correction", "root-rejections"), oracle.fixture.catalogs.keys)
        assertEquals(3, oracle.fixture.baselines.size)
        assertEquals(3, oracle.fixture.initialStateIds.size)
        // The ten rejection ops cover the ten frozen reason codes, one each.
        assertEquals(
            10,
            oracle.fixture.allOperations
                .filter { it.expectedStatus == "rejected" }
                .mapNotNull { it.expectedReason }
                .toSet()
                .size,
        )
        assertEquals(
            "idempotent_replay",
            oracle.fixture.allOperations
                .single { it.id == "root-correction-replay" }
                .expectedReason,
        )
        assertEquals(
            "root-correction-correct",
            oracle.fixture.allOperations
                .single { it.id == "root-correction-replay" }
                .retryOf,
        )
        // The partial root is a baseline-only root of the state graph: no operations.
        assertEquals(emptyList(), oracle.fixture.allOperations.filter { it.rootId == "root-partial" })
    }

    @Test
    fun `correction root operations replay against full canonical states`() {
        val oracle = loadOracle()
        oracle.fixture.allOperations
            .filter { it.rootId == "root-correction" }
            .forEach { assertOperation(oracle, it) }
    }

    @Test
    fun `rejection root operations reject with exact reason field path and zero effect`() {
        val oracle = loadOracle()
        oracle.fixture.allOperations
            .filter { it.rootId == "root-rejections" }
            .forEach { assertOperation(oracle, it) }
    }

    @Test
    fun `idempotent replay returns no change with first-time ids`() {
        val oracle = loadOracle()
        val replay = oracle.fixture.allOperations.single { it.id == "root-correction-replay" }
        assertEquals("root-correction-correct", replay.retryOf)
        assertOperation(oracle, replay)
        // Retry equality: the replayed operation carries the first-time ids of its original.
        val original = oracle.operations.getValue("root-correction-correct")
        val runtime = baselineRuntime(oracle, replay)
        val result = runtime.commit(replay.operation)
        val noChange = assertIs<Rg12ExecutionResult.NoChange>(result, "root-correction-replay")
        assertEquals(expectedReturnedIds(original), noChange.returnedIds, "root-correction-replay: no-change IDs equal first-time IDs")
    }

    @Test
    fun `posting-facts correction appends version two with full replacement lineage`() {
        val oracle = loadOracle()
        val operation = oracle.fixture.allOperations.single { it.id == "root-correction-correct" }
        val runtime = baselineRuntime(oracle, operation)
        val result = runtime.commit(operation.operation)
        assertIs<Rg12ExecutionResult.Accepted>(result)
        val snapshot = runtime.snapshot()
        val corrected = snapshot.formalTransactions.single { it.formalTransaction.transaction.id.value == "root-correction-transaction" }
        assertEquals("root-correction-transaction-v2", corrected.formalTransaction.transaction.currentVersionId.value)
        val v2 = corrected.formalTransaction.versions.single { it.id.value == "root-correction-transaction-v2" }
        assertEquals(2, v2.versionNumber)
        // The v2 version preserves the economic times and note of v1 and is created at corrected_at.
        assertEquals("2026-04-10T09:30:00+08:00", instantText(v2.times.occurredAt))
        assertEquals("2026-04-10T09:30:00+08:00", instantText(v2.times.statisticsAt))
        assertEquals("2026-04-10T09:30:00+08:00", instantText(v2.times.effectiveAt))
        assertEquals("mixed expense", v2.note)
        assertEquals("2026-04-20T10:00:00+08:00", corrected.versionCreatedAtTexts.getValue(v2.id))
        assertEquals("2026-04-10T09:30:00+08:00", corrected.statisticsAtText)
        assertEquals(
            mapOf(TransactionVersionId("root-correction-transaction-v2") to "root-correction-confirmation"),
            corrected.versionConfirmationIds,
        )
        // The explicit operation confirmation owns the operation id itself and is confirmed at corrected_at.
        val confirmation = snapshot.confirmations.single()
        assertEquals("root-correction-confirmation", confirmation.id)
        assertEquals("root-correction-correct", confirmation.operationId)
        assertEquals("operation", confirmation.subject.kind)
        assertEquals("root-correction-correct", confirmation.subject.id)
        assertEquals("2026-04-20T10:00:00+08:00", instantText(confirmation.createdAt))
        // The three-value replacement chain: not_applicable expense, preserved asset, invalidated liability.
        val effects = snapshot.postingReplacements.associate { it.fromPostingId.value to it.reconciliationEffect }
        assertEquals(ReconciliationEffect.NOT_APPLICABLE, effects["root-correction-expense-v1"])
        assertEquals(ReconciliationEffect.PRESERVED, effects["root-correction-asset-v1"])
        assertEquals(ReconciliationEffect.INVALIDATED, effects["root-correction-liability-v1"])
        // The invalidated predecessor match appends its invalidation entry at corrected_at.
        val liabilityMatch = snapshot.reconciliationMatches.single { it.id == "root-correction-match-liability-v1" }
        assertEquals(
            listOf(ReconciliationMatchStatus.MATCHED, ReconciliationMatchStatus.INVALIDATED),
            liabilityMatch.statusHistory.map { it.status },
        )
        assertEquals("2026-04-20T10:00:00+08:00", instantText(liabilityMatch.statusHistory.last().at))
        // The preserved asset leg inherits a fresh match whose history-1 entry takes the
        // predecessor's last matched time.
        val assetV2Match = snapshot.reconciliationMatches.single { it.id == "root-correction-match-asset-v2" }
        assertEquals("root-correction-asset-v2", assetV2Match.postingId.value)
        assertEquals("2026-04-11T09:00:00+08:00", instantText(assetV2Match.statusHistory.single().at))
        // Fresh facts: preserved -> matched, invalidated -> pending, expense -> none.
        val facts = snapshot.postingReconciliations.associate { it.postingId.value to it.status }
        assertEquals(PostingReconciliationStatus.MATCHED, facts["root-correction-asset-v2"])
        assertEquals(PostingReconciliationStatus.PENDING, facts["root-correction-liability-v2"])
        assertEquals(null, facts["root-correction-expense-v2"])
        // The summary moves matched -> partial.
        assertEquals(ReconciliationSummary.PARTIAL, snapshot.reconciliationSummary.getValue(TransactionId("root-correction-transaction")))
        // Balances follow the replacement postings.
        assertEquals(11_000L, snapshot.balances.getValue(AccountId("root-correction-expense")).minorUnits)
        assertEquals(-7_000L, snapshot.balances.getValue(AccountId("root-correction-asset")).minorUnits)
        assertEquals(-4_000L, snapshot.balances.getValue(AccountId("root-correction-liability")).minorUnits)
        // Reports recompute: cash outflow unchanged at 70.00, consumption 110.00, zero correction day.
        val day = snapshot.reports.getValue("2026-04-10")
        assertEquals(7_000L, day.cashOutflowMinor)
        assertEquals(11_000L, day.consumptionMinor)
        assertEquals(11_000L, day.categoryConsumptionMinor)
        assertEquals(-11_000L, day.netWorthChangeMinor)
        val correctionDay = snapshot.reports.getValue("2026-04-20")
        assertEquals(0L, correctionDay.cashOutflowMinor)
        assertEquals(0L, correctionDay.consumptionMinor)
        assertEquals(0L, correctionDay.categoryConsumptionMinor)
        assertEquals(0L, correctionDay.netWorthChangeMinor)
    }

    @Test
    fun `three roots initial states project onto the frozen baselines`() {
        val oracle = loadOracle()
        oracle.fixture.initialStateIds.forEach { (rootId, stateId) ->
            val runtime = buildStateRuntime(oracle, stateId)
            val mismatches = stateMismatches(oracle, stateId, runtime.snapshot(), "$rootId initial")
            assertEquals(emptyList(), mismatches, "$rootId initial state")
        }
        // The partial root baseline carries the pending liability fact and the partial summary.
        val partial = oracle.fixture.baselines.getValue("root-partial")
        val facts = partial.postingReconciliations.associate { it.postingId.value to it.status }
        assertEquals(PostingReconciliationStatus.MATCHED, facts["root-partial-asset-v1"])
        assertEquals(PostingReconciliationStatus.PENDING, facts["root-partial-liability-v1"])
    }

    @Test
    fun `rejected and replayed operations keep baseline and result byte identical`() {
        val oracle = loadOracle()
        oracle.fixture.allOperations
            .filter { it.expectedStatus in setOf("rejected", "no_change") }
            .forEach { operation ->
                val runtime = baselineRuntime(oracle, operation)
                val before = runtime.snapshot()
                runtime.commit(operation.operation)
                val after = runtime.snapshot()
                assertEquals(
                    projectState(before, oracle.fixture, operation.rootId),
                    projectState(after, oracle.fixture, operation.rootId),
                    "${operation.id}: non-mutating outcome changed state",
                )
                val baselineDoc = oracle.states.getValue(operation.baselineStateId!!)
                val resultDoc = oracle.states.getValue(operation.resultStateId!!)
                assertEquals(payloadOf(baselineDoc), payloadOf(resultDoc), "${operation.id}: frozen baseline/result payloads differ")
            }
    }

    // ------------------------------------------------------------------ per-op

    private fun assertOperation(
        oracle: OracleFixture,
        operation: Rg12FixtureOperation,
    ) {
        val document = oracle.operations.getValue(operation.id)
        val runtime = baselineRuntime(oracle, operation)
        val before = runtime.snapshot()
        val baselineStateId = operation.baselineStateId ?: error("${operation.id} has no baseline state id")
        val mismatches = mutableListOf<String>()
        mismatches += stateMismatches(oracle, baselineStateId, before, "${operation.id} baseline")

        val result = runtime.commit(operation.operation)
        assertOutcome(oracle, document, operation, result, operation.id)
        val after = runtime.snapshot()

        when (operation.expectedStatus) {
            "accepted" -> {
                val resultStateId = operation.resultStateId ?: error("${operation.id} has no result state id")
                mismatches += stateMismatches(oracle, resultStateId, after, "${operation.id} result")
            }
            "rejected", "no_change" -> {
                assertEquals(
                    projectState(before, oracle.fixture, operation.rootId),
                    projectState(after, oracle.fixture, operation.rootId),
                    "${operation.id}: non-mutating outcome changed state",
                )
            }
            else -> error("unsupported RG-12 expected status ${operation.expectedStatus}")
        }
        mismatches += deltaMismatches(oracle, document, before, after, operation.id)
        mismatches += statusChangeMismatches(oracle, document, before, after, operation.id)
        assertEquals(emptyList(), mismatches, "${operation.id}: complete state, delta and status fields")
    }

    private fun assertOutcome(
        oracle: OracleFixture,
        document: JsonObject,
        operation: Rg12FixtureOperation,
        result: Rg12ExecutionResult,
        label: String,
    ) {
        val expected = document.getValue("outcome").jsonObject
        when (operation.expectedStatus) {
            "accepted" -> {
                val accepted = assertIs<Rg12ExecutionResult.Accepted>(result, label)
                assertEquals(expectedReturnedIds(document), accepted.returnedIds, "$label: accepted IDs")
            }
            "no_change" -> {
                assertEquals("idempotent_replay", expected.string("reason_code"), "$label: frozen no-change reason")
                val noChange = assertIs<Rg12ExecutionResult.NoChange>(result, label)
                // The replayed operation returns the first-time ids (frozen
                // `root-correction-replay` returned_ids equal `root-correction-correct`).
                assertEquals(expectedReturnedIds(document), noChange.returnedIds, "$label: no-change IDs equal first-time IDs")
            }
            "rejected" -> {
                val rejected = assertIs<Rg12ExecutionResult.Rejected>(result, label)
                val reason = Rg12RejectionReason.entries.single { it.code == expected.string("reason_code") }
                assertEquals(reason, rejected.reason, "$label: rejection reason")
                val fieldPath = expected.string("field_path")
                assertEquals(Rg12FieldPath(fieldPath), rejected.fieldPath, "$label: field path")
            }
            else -> error("unsupported RG-12 expected status ${operation.expectedStatus}")
        }
    }

    private fun stateMismatches(
        oracle: OracleFixture,
        stateId: String,
        snapshot: Rg12Snapshot,
        label: String,
    ): List<String> {
        val expected = oracle.states.getValue(stateId)
        val rootId = expected.string("root_id")
        val projected = projectState(snapshot, oracle.fixture, rootId)
        val expectedPayload = expected.filterKeys { it !in STATE_META_KEYS && it != "catalog" }
        val mismatches =
            expectedPayload.mapNotNull { (key, value) ->
                val actual = projected[key]
                val equal =
                    when (key) {
                        "domain_entities", "balances" ->
                            // Order-insensitive collections: the validator compares by id / as maps.
                            value.jsonArray.map(::canonical).toSet() == actual?.jsonArray?.map(::canonical)?.toSet()
                        else -> actual == value
                    }
                if (equal) null else "$label: field $key expected=${describe(value)} actual=${describe(actual)}"
            }
        val extraKeys = projected.keys - expectedPayload.keys
        return mismatches + extraKeys.map { "$label: unexpected field $it" } + assertCatalogMismatches(oracle, stateId, label)
    }

    private fun describe(element: JsonElement?): String =
        when (element) {
            null -> "<missing>"
            else -> element.toString().take(400)
        }

    private fun deltaMismatches(
        oracle: OracleFixture,
        document: JsonObject,
        before: Rg12Snapshot,
        after: Rg12Snapshot,
        label: String,
    ): List<String> {
        val expected = document.getValue("deltas").jsonObject
        val beforeState = projectState(before, oracle.fixture, document.string("root_id"))
        val afterState = projectState(after, oracle.fixture, document.string("root_id"))

        // Entity changes: frozen id lists are sorted (validator + Python test demand sorted
        // added/changed/removed lists). Catalog collections are static in RG-12 (no action
        // mutates them), so their changes are always empty.
        val computedEntityChanges =
            JsonObject(
                mapOf(
                    "catalog_accounts" to emptyEntityChange(),
                    "catalog_categories" to emptyEntityChange(),
                ) +
                    ENTITY_COLLECTIONS.associate { (name, path) ->
                        name to entityChangeJson(beforeState, afterState, path)
                    },
            )
        val expectedValues = expected.getValue("value_changes").jsonObject
        return buildList {
            if (expected.getValue("entity_changes").jsonObject != computedEntityChanges) {
                add("$label: entity changes differ")
            }
            if (expectedValues.getValue("balances").jsonArray != balanceChanges(beforeState, afterState)) {
                add("$label: balance changes differ")
            }
            // Value change entry order of reports and derived statuses is a Python set-iteration
            // artifact, so both are compared as sets of canonical JSON entries (mirrors
            // test_rg12_golden_v2.py).
            if (expectedValues
                    .getValue("reports")
                    .jsonArray
                    .map(::canonical)
                    .toSet() != reportChanges(beforeState, afterState).map(::canonical).toSet()
            ) {
                add("$label: report changes differ")
            }
            if (expectedValues
                    .getValue("derived_statuses")
                    .jsonArray
                    .map(::canonical)
                    .toSet() != statusValueChanges(beforeState, afterState).map(::canonical).toSet()
            ) {
                add("$label: derived status changes differ")
            }
        }
    }

    private fun statusChangeMismatches(
        oracle: OracleFixture,
        document: JsonObject,
        before: Rg12Snapshot,
        after: Rg12Snapshot,
        label: String,
    ): List<String> {
        val expected = document["status_changes"]?.jsonArray ?: return emptyList()
        val beforeState = projectState(before, oracle.fixture, document.string("root_id"))
        val afterState = projectState(after, oracle.fixture, document.string("root_id"))
        val beforeMap = statusMap(beforeState)
        val afterMap = statusMap(afterState)
        val computed =
            JsonArray(
                (beforeMap.keys + afterMap.keys).mapNotNull { key ->
                    val old = beforeMap[key]
                    val new = afterMap[key]
                    if (old == new) {
                        null
                    } else {
                        jsonObjectOf(
                            "target_kind" to json(key.first),
                            "target_id" to json(key.second),
                            "status_name" to json(key.third),
                            // The frozen contract writes absent sides as explicit null.
                            "before" to (old ?: JsonNull),
                            "after" to (new ?: JsonNull),
                        )
                    }
                },
            )
        return if (expected.map(::canonical).toSet() == computed.map(::canonical).toSet()) {
            emptyList()
        } else {
            listOf("$label: status changes differ")
        }
    }

    // ------------------------------------------------------------------ runtime construction

    private fun baselineRuntime(
        oracle: OracleFixture,
        operation: Rg12FixtureOperation,
    ): Rg12Runtime {
        val stateId = operation.baselineStateId ?: error("${operation.id} has no baseline state id")
        return buildStateRuntime(oracle, stateId)
    }

    private fun buildStateRuntime(
        oracle: OracleFixture,
        stateId: String,
    ): Rg12Runtime {
        val rootId = oracle.states.getValue(stateId).string("root_id")
        if (stateId == oracle.fixture.initialStateIds[rootId]) {
            return Rg12Runtime(
                oracle.fixture.catalogs.getValue(rootId),
                oracle.fixture.baselines.getValue(rootId),
            )
        }
        val producer =
            producerOf(oracle.fixture, stateId)
                ?: error("no producer for canonical state $stateId")
        val runtime = buildStateRuntime(oracle, producer.baselineStateId!!)
        val result = runtime.commit(producer.operation)
        check(
            result is Rg12ExecutionResult.Accepted ||
                (producer.expectedStatus == "rejected" && result is Rg12ExecutionResult.Rejected),
        ) {
            "${producer.id} baseline producer did not produce its expected outcome: $result"
        }
        return runtime
    }

    private fun producerOf(
        fixture: Rg12FixtureCase,
        stateId: String,
    ): Rg12FixtureOperation? =
        fixture.allOperations.firstOrNull { candidate ->
            candidate.retryOf == null &&
                candidate.resultStateId == stateId &&
                candidate.baselineStateId != stateId
        }

    private fun expectedReturnedIds(document: JsonObject): List<Rg12ReturnedId> =
        document["returned_ids"]?.jsonArray?.map { element ->
            val item = element.jsonObject
            when (item.string("kind")) {
                "transaction_version" -> Rg12ReturnedId.Version(TransactionVersionId(item.string("id")))
                else -> error("unsupported RG-12 returned id kind ${item.string("kind")}")
            }
        } ?: emptyList()

    // ------------------------------------------------------------------ state projection

    private fun projectState(
        snapshot: Rg12Snapshot,
        fixture: Rg12FixtureCase,
        rootId: String,
    ): JsonObject =
        jsonObjectOf(
            "transactions" to
                JsonArray(
                    snapshot.formalTransactions.map { record ->
                        jsonObjectOf(
                            "id" to json(record.formalTransaction.transaction.id.value),
                            "type" to
                                json(
                                    record.formalTransaction.transaction.kind.name
                                        .lowercase(),
                                ),
                            "current_version_id" to json(record.formalTransaction.transaction.currentVersionId.value),
                        )
                    },
                ),
            "transaction_versions" to
                JsonArray(
                    snapshot.formalTransactions.flatMap { record ->
                        record.formalTransaction.versions.map { version ->
                            jsonObjectOf(
                                "id" to json(version.id.value),
                                "transaction_id" to json(version.transactionId.value),
                                "version_number" to json(version.versionNumber),
                                "posting_set_id" to json(version.postingSetId.value),
                                "occurred_at" to json(instantText(version.times.occurredAt)),
                                "statistics_at" to json(instantText(version.times.statisticsAt)),
                                "effective_at" to json(instantText(version.times.effectiveAt)),
                                "created_at" to json(record.versionCreatedAtTexts[version.id] ?: error("no created_at text for ${version.id.value}")),
                                "note" to version.note?.let(::json),
                                "confirmation_id" to record.versionConfirmationIds[version.id]?.let(::json),
                            )
                        }
                    },
                ),
            "posting_sets" to
                JsonArray(
                    snapshot.formalTransactions.flatMap { record ->
                        record.formalTransaction.postingSets.map { set ->
                            jsonObjectOf(
                                "id" to json(set.id.value),
                                "posting_ids" to JsonArray(set.postings.map { json(it.id.value) }),
                            )
                        }
                    },
                ),
            "postings" to
                JsonArray(
                    snapshot.formalTransactions.flatMap { record ->
                        record.formalTransaction.postingSets.flatMap { set ->
                            set.postings.map { posting ->
                                val semantics = snapshot.postingSemantics[posting.id.value]
                                jsonObjectOf(
                                    "id" to json(posting.id.value),
                                    "posting_set_id" to json(set.id.value),
                                    "account_id" to json(posting.accountId.value),
                                    "amount" to json(moneyText(posting.amount)),
                                    "currency" to json(posting.amount.currency.code),
                                    "reconciliation_eligible" to json(semantics?.reconciliationEligible ?: false),
                                    "role" to semantics?.role?.let(::json),
                                    "category_id" to semantics?.categoryId?.value?.let(::json),
                                )
                            }
                        }
                    },
                ),
            "sources" to fixture.staticSeeds.getValue(rootId).sources,
            "candidates" to JsonArray(emptyList()),
            "confirmations" to JsonArray(snapshot.confirmations.map(::projectConfirmation)),
            "evidence" to fixture.staticSeeds.getValue(rootId).evidence,
            "evidence_links" to fixture.staticSeeds.getValue(rootId).evidenceLinks,
            "relations" to JsonArray(emptyList()),
            "domain_entities" to projectDomainEntities(snapshot),
            "audit_links" to
                JsonArray(
                    snapshot.postingReplacements.map { link ->
                        jsonObjectOf(
                            "id" to json(link.id),
                            "type" to json("posting_replacement"),
                            "from" to jsonObjectOf("kind" to json("posting"), "id" to json(link.fromPostingId.value)),
                            "to" to jsonObjectOf("kind" to json("posting"), "id" to json(link.toPostingId.value)),
                            "payload" to jsonObjectOf("reconciliation_effect" to json(link.reconciliationEffect.jsonName)),
                        )
                    },
                ),
            "posting_reconciliations" to projectReconciliations(snapshot, fixture),
            "balances" to projectBalances(snapshot),
            "reports" to projectReports(snapshot),
            "derived_statuses" to
                JsonArray(
                    snapshot.reconciliationSummary.entries.map { (transactionId, summary) ->
                        jsonObjectOf(
                            // The frozen `<rootId>-summary` convention of the fixture (derived status id).
                            "id" to json("$rootId-summary"),
                            "target_kind" to json("transaction"),
                            "target_id" to json(transactionId.value),
                            "status_name" to json("reconciliation_summary"),
                            "value" to json(summary.jsonName),
                        )
                    },
                ),
        )

    private fun projectConfirmation(confirmation: ExplicitOperationConfirmation): JsonObject =
        jsonObjectOf(
            "id" to json(confirmation.id),
            "type" to json("explicit_operation_confirmation"),
            "operation_id" to json(confirmation.operationId),
            "subject" to jsonObjectOf("kind" to json(confirmation.subject.kind), "id" to json(confirmation.subject.id)),
            "confirmed_at" to json(instantText(confirmation.createdAt)),
            "payload" to JsonObject(confirmation.payload.mapValues { (_, value) -> json(value) }),
        )

    private fun projectDomainEntities(snapshot: Rg12Snapshot): JsonArray =
        JsonArray(
            snapshot.domainEntities.map { entity ->
                jsonObjectOf(
                    "id" to json(entity.id),
                    "type" to json(entity.type),
                    "payload" to
                        JsonObject(
                            entity.payload
                                .map { (key, value) ->
                                    key to
                                        if (key == "status_history") {
                                            // The runtime renders the append-only status_history as one JSON
                                            // text; parse it back for the canonical payload comparison.
                                            Json.parseToJsonElement(value)
                                        } else {
                                            json(value)
                                        }
                                }.toMap(),
                        ),
                )
            },
        )

    private fun projectReconciliations(
        snapshot: Rg12Snapshot,
        fixture: Rg12FixtureCase,
    ): JsonArray =
        JsonArray(
            snapshot.postingReconciliations.map { fact ->
                jsonObjectOf(
                    "id" to json(fixture.reconciliationIds[fact.postingId.value] ?: error("no reconciliation id anchor for ${fact.postingId.value}")),
                    "posting_id" to json(fact.postingId.value),
                    "status" to json(fact.status.jsonName),
                )
            },
        )

    private fun projectBalances(snapshot: Rg12Snapshot): JsonArray =
        JsonArray(
            snapshot.balances.entries.sortedBy { it.key.value }.map { (accountId, amount) ->
                jsonObjectOf(
                    "account_id" to json(accountId.value),
                    "currency" to json(amount.currency.code),
                    "amount" to json(moneyText(amount)),
                )
            },
        )

    private fun projectReports(snapshot: Rg12Snapshot): JsonArray =
        JsonArray(
            snapshot.reports.entries.map { (period, report) ->
                jsonObjectOf(
                    "period_type" to json("day"),
                    "period" to json(period),
                    "metrics" to
                        JsonArray(
                            listOf(
                                metricJson("cash_outflow", report.cashOutflowMinor),
                                metricJson("consumption", report.consumptionMinor),
                                metricJson("category_consumption", report.categoryConsumptionMinor),
                                metricJson("net_worth_change", report.netWorthChangeMinor),
                            ),
                        ),
                )
            },
        )

    private fun metricJson(
        name: String,
        minor: Long,
    ): JsonObject =
        jsonObjectOf(
            "metric" to json(name),
            "applicability" to json("applicable"),
            "currency" to json("CNY"),
            "amount" to json(moneyText(minor)),
        )

    private fun assertCatalogMismatches(
        oracle: OracleFixture,
        stateId: String,
        label: String,
    ): List<String> =
        buildList {
            val state = oracle.states.getValue(stateId)
            val rootId = state.string("root_id")
            val catalog = oracle.fixture.catalogs.getValue(rootId)
            val frozen = state.getValue("catalog").jsonObject
            val frozenAccounts = frozen.getValue("accounts").jsonArray.map { it.jsonObject }
            if (frozenAccounts.map { it.string("id") } != catalog.accounts.map { it.id.value }) {
                add("$label: catalog account ids differ")
            }
            frozenAccounts.forEach { account ->
                val runtime = catalog.accounts.single { it.id.value == account.string("id") }
                if (account.string("kind") != runtime.kind.name.lowercase()) add("$label: account kind ${account.string("id")}")
                if (account.string("currency") != runtime.currency.code) add("$label: account currency ${account.string("id")}")
                if (account.boolean("owned_by_user") != runtime.ownedByUser) add("$label: account ownership ${account.string("id")}")
                if (account.boolean("real_account") != runtime.realAccount) add("$label: account real flag ${account.string("id")}")
            }
            val frozenCategories = frozen.getValue("categories").jsonArray.map { it.jsonObject }
            if (frozenCategories.map { it.string("id") } != catalog.categories.map { it.id.value }) {
                add("$label: catalog category ids differ")
            }
            frozenCategories.forEach { category ->
                val runtime = catalog.categories.single { it.id.value == category.string("id") }
                if (category.optionalString("parent_id") != runtime.parentId?.value) add("$label: category parent ${category.string("id")}")
                if (category.optionalString("posting_account_id") != runtime.postingAccountId?.value) add("$label: category posting account ${category.string("id")}")
                if (category.boolean("active") != runtime.active) add("$label: category active ${category.string("id")}")
            }
        }

    // ------------------------------------------------------------------ delta computation

    private fun emptyEntityChange(): JsonObject =
        jsonObjectOf(
            "added_ids" to JsonArray(emptyList()),
            "changed_ids" to JsonArray(emptyList()),
            "removed_ids" to JsonArray(emptyList()),
        )

    private fun entityChangeJson(
        before: JsonObject,
        after: JsonObject,
        path: List<String>,
    ): JsonObject {
        val beforeById = byId(before.nested(path))
        val afterById = byId(after.nested(path))
        return jsonObjectOf(
            "added_ids" to JsonArray((afterById.keys - beforeById.keys).sorted().map(::json)),
            "changed_ids" to
                JsonArray(
                    (beforeById.keys intersect afterById.keys)
                        .filter { beforeById[it] != afterById[it] }
                        .sorted()
                        .map(::json),
                ),
            "removed_ids" to JsonArray((beforeById.keys - afterById.keys).sorted().map(::json)),
        )
    }

    private fun balanceChanges(
        before: JsonObject,
        after: JsonObject,
    ): JsonArray {
        val beforeMap = balanceMap(before)
        val afterMap = balanceMap(after)
        return JsonArray(
            (beforeMap.keys + afterMap.keys)
                .sortedWith(compareBy({ it.first }, { it.second }))
                .mapNotNull { key ->
                    val old = beforeMap[key]
                    val new = afterMap[key]
                    if (old == new) {
                        null
                    } else {
                        jsonObjectOf(
                            "key" to
                                jsonObjectOf(
                                    "account_id" to json(key.first),
                                    "currency" to json(key.second),
                                ),
                            "before" to old,
                            "after" to new,
                        )
                    }
                },
        )
    }

    private fun reportChanges(
        before: JsonObject,
        after: JsonObject,
    ): JsonArray {
        val beforeMap = reportMap(before)
        val afterMap = reportMap(after)
        return JsonArray(
            (beforeMap.keys + afterMap.keys).mapNotNull { key ->
                val old = beforeMap[key]
                val new = afterMap[key]
                if (old == new) {
                    null
                } else {
                    jsonObjectOf(
                        "key" to
                            jsonObjectOf(
                                "period_type" to json(key.periodType),
                                "period" to json(key.period),
                                "metric" to json(key.metric),
                                "currency" to key.currency?.let(::json),
                            ),
                        // The frozen contract writes absent sides as explicit null.
                        "before" to (old ?: JsonNull),
                        "after" to (new ?: JsonNull),
                    )
                }
            },
        )
    }

    private fun statusValueChanges(
        before: JsonObject,
        after: JsonObject,
    ): JsonArray {
        val beforeMap = statusMap(before)
        val afterMap = statusMap(after)
        return JsonArray(
            (beforeMap.keys + afterMap.keys).mapNotNull { key ->
                val old = beforeMap[key]
                val new = afterMap[key]
                if (old == new) {
                    null
                } else {
                    jsonObjectOf(
                        "key" to
                            jsonObjectOf(
                                "kind" to json(key.first),
                                "target_id" to json(key.second),
                                "status_name" to json(key.third),
                            ),
                        // The frozen contract writes absent sides as explicit null.
                        "before" to (old ?: JsonNull),
                        "after" to (new ?: JsonNull),
                    )
                }
            },
        )
    }

    private fun balanceMap(state: JsonObject): Map<Pair<String, String>, JsonElement> =
        state.getValue("balances").jsonArray.associate { balance ->
            val item = balance.jsonObject
            (item.string("account_id") to item.string("currency")) to item.getValue("amount")
        }

    private data class ReportKey(
        val periodType: String,
        val period: String,
        val metric: String,
        val currency: String?,
    )

    private fun reportMap(state: JsonObject): Map<ReportKey, JsonElement> =
        state
            .getValue("reports")
            .jsonArray
            .flatMap { report ->
                val item = report.jsonObject
                val periodType = item.string("period_type")
                val period = item.string("period")
                item.getValue("metrics").jsonArray.map { metric ->
                    val metricObject = metric.jsonObject
                    ReportKey(
                        periodType,
                        period,
                        metricObject.string("metric"),
                        metricObject.optionalString("currency"),
                    ) to JsonObject(metricObject.filterKeys { it != "metric" })
                }
            }.toMap()

    private fun statusMap(state: JsonObject): Map<Triple<String, String, String>, JsonElement> =
        state.getValue("derived_statuses").jsonArray.associate { status ->
            val item = status.jsonObject
            Triple(item.string("target_kind"), item.string("target_id"), item.string("status_name")) to item.getValue("value")
        }

    // ------------------------------------------------------------------ helpers

    private fun JsonObject.nested(path: List<String>): JsonElement {
        var current: JsonElement = this
        path.forEach { key -> current = current.jsonObject.getValue(key) }
        return current
    }

    private fun byId(collection: JsonElement): Map<String, JsonElement> = collection.jsonArray.associate { element -> element.jsonObject.string("id") to element }

    private fun payloadOf(state: JsonObject): JsonObject = JsonObject(state.filterKeys { it !in STATE_META_KEYS })

    private fun canonical(element: JsonElement): String = element.toString()

    private fun loadOracle(): OracleFixture {
        val raw = Files.readString(repositoryFile("golden/rules/rg-12.json"))
        val contract = Json.parseToJsonElement(raw).jsonObject
        val inputs = parseRg12FixtureInputs(Files.readString(repositoryFile("tests/fixtures/rg12-runtime-input.json")))
        val fixture = adaptRg12Fixture(raw, inputs)
        val states =
            contract
                .getValue("states")
                .jsonArray
                .map { it.jsonObject }
                .associateBy { it.string("id") }
        val operations =
            contract
                .getValue("operations")
                .jsonArray
                .map { it.jsonObject }
                .associateBy { it.string("id") }
        return OracleFixture(fixture, inputs, operations, states)
    }

    private data class OracleFixture(
        val fixture: Rg12FixtureCase,
        val inputs: Rg12FixtureInputs,
        val operations: Map<String, JsonObject>,
        val states: Map<String, JsonObject>,
    )

    private companion object {
        val STATE_META_KEYS = setOf("id", "root_id", "as_of_operation_id")

        /** Frozen delta entity collections in the declared order (catalog handled as static). */
        val ENTITY_COLLECTIONS: List<Pair<String, List<String>>> =
            listOf(
                "transactions" to listOf("transactions"),
                "transaction_versions" to listOf("transaction_versions"),
                "posting_sets" to listOf("posting_sets"),
                "postings" to listOf("postings"),
                "sources" to listOf("sources"),
                "candidates" to listOf("candidates"),
                "confirmations" to listOf("confirmations"),
                "evidence" to listOf("evidence"),
                "evidence_links" to listOf("evidence_links"),
                "relations" to listOf("relations"),
                "domain_entities" to listOf("domain_entities"),
                "audit_links" to listOf("audit_links"),
                "posting_reconciliations" to listOf("posting_reconciliations"),
            )
    }
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.optionalString(key: String): String? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

private fun JsonObject.boolean(key: String): Boolean = getValue(key).jsonPrimitive.content.toBooleanStrict()

private val SHANGHAI_INSTANT_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

private fun instantText(instant: Instant): String =
    SHANGHAI_INSTANT_FORMAT.format(
        OffsetDateTime.ofInstant(java.time.Instant.parse(instant.toString()), ZoneOffset.ofHours(8)),
    )

private fun moneyText(amount: com.unifiedledger.domain.Money): String = moneyText(amount.minorUnits, amount.currency.precision)

private fun moneyText(
    minor: Long,
    precision: Int = 2,
): String = BigDecimal.valueOf(minor, precision).setScale(precision).toPlainString()

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
