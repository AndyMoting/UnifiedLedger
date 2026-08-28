package com.unifiedledger.data

import com.unifiedledger.application.Rg11ExecutionResult
import com.unifiedledger.application.Rg11FieldPath
import com.unifiedledger.application.Rg11FixtureCase
import com.unifiedledger.application.Rg11FixtureInputs
import com.unifiedledger.application.Rg11FixtureOperation
import com.unifiedledger.application.Rg11RejectionReason
import com.unifiedledger.application.Rg11ReturnedId
import com.unifiedledger.application.Rg11Runtime
import com.unifiedledger.application.Rg11Snapshot
import com.unifiedledger.application.adaptRg11Fixture
import com.unifiedledger.application.parseRg11FixtureInputs
import com.unifiedledger.domain.ExplicitOperationConfirmation
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
 * RG-11 D-085 acceptance oracle (shard 4): the frozen direct-v2 contract
 * `golden/rules/rg-11.json` (22 operations: accepted 11 / no_change 1 / rejected 10; root-main 6,
 * root-revision 6, root-z-rejections 10) is independently replayed against the pure
 * [Rg11Runtime] (no store) and compared field by field with the frozen expected blocks:
 * outcome, returned IDs, complete canonical state (transactions, versions, posting sets,
 * postings, confirmations, domain entities, audit links, posting reconciliations, balances,
 * reports, derived statuses), entity/value deltas, status changes, rejected/no-change baseline
 * equality and retry equality (main-replay returns the first-time ids of main-recognize-03).
 * Mirrors the Rg08/Rg10 oracles; the runtime is driven purely through the typed operations.
 *
 * Registered fixture-to-state projection rules:
 *
 * 1. `catalog`: the domain [com.unifiedledger.domain.Account]/[com.unifiedledger.domain.Category]
 *    carry no display `name`, `reconciliation_eligible` or `hidden` fields; the catalog is
 *    static input that no RG-11 action mutates. The per-state catalog is asserted separately on
 *    the representable fields (id/kind/currency/owned_by_user/real_account for accounts,
 *    id/parent_id/posting_account_id/active for categories) and excluded from the per-state
 *    collection comparison.
 *
 * 2. Times render in the case timezone `Asia/Shanghai` (+08:00) with a fixed-offset formatter
 *    (kotlin.time.Instant renders UTC); every frozen time is at +08:00, so the rendering
 *    recovers the frozen strings byte for byte.
 *
 * 3. Balances: the runtime emits the catalog order while the frozen initial states also use
 *    catalog order but the frozen result states sort by account id; the validator compares
 *    balances as maps, so the per-state balances order is not contractual and the state
 *    comparison normalizes balances to a set of entries. The delta `balances` value changes
 *    stay compared in the frozen sorted-by-key order (the Python test demands that order).
 *
 * 4. Reports are single-currency (CNY) in rg-11; the runtime report registry is
 *    currency-agnostic, so the projection pins the frozen case currency CNY.
 *
 * 5. Entity change id lists and balance value changes are compared in the frozen sorted order
 *    (the Python validator and test_rg11_golden_v2.py both demand sorted id lists and sorted
 *    balance keys); report/derived-status value changes and status changes entry order follows
 *    the Python generator's set iteration and is not contractual, so those three are compared
 *    as sets of canonical JSON entries (mirroring test_rg11_golden_v2.py).
 *
 * 6. `posting_reconciliations` ids follow no uniform derivation rule (see Rg11FixtureReplay);
 *    the fixture case supplies the postingId -> reconciliationId anchors.
 */
class Rg11FullStateOracleTest {
    @Test
    fun `raw operation registry preserves all outcome families`() {
        val oracle = loadOracle()
        assertEquals(22, oracle.operations.size)
        assertEquals(11, oracle.fixture.allOperations.count { it.expectedStatus == "accepted" })
        assertEquals(1, oracle.fixture.allOperations.count { it.expectedStatus == "no_change" })
        assertEquals(10, oracle.fixture.allOperations.count { it.expectedStatus == "rejected" })
        assertEquals(oracle.operations.keys.toList(), oracle.fixture.allOperations.map { it.id })
        assertEquals(3, oracle.fixture.catalogs.size)
        assertEquals(3, oracle.fixture.baselines.size)
        // The ten rejection ops cover nine unique frozen reason codes.
        assertEquals(
            9,
            oracle.fixture.allOperations
                .filter { it.expectedStatus == "rejected" }
                .mapNotNull { it.expectedReason }
                .toSet()
                .size,
        )
        assertEquals(
            "idempotent_replay",
            oracle.fixture.allOperations
                .single { it.id == "main-replay" }
                .expectedReason,
        )
    }

    @Test
    fun `main root operations replay against full canonical states`() {
        val oracle = loadOracle()
        oracle.fixture.allOperations
            .filter { it.rootId == "root-main" }
            .forEach { assertOperation(oracle, it) }
    }

    @Test
    fun `revision root operations replay against full canonical states`() {
        val oracle = loadOracle()
        oracle.fixture.allOperations
            .filter { it.rootId == "root-revision" }
            .forEach { assertOperation(oracle, it) }
    }

    @Test
    fun `rejection root operations reject with exact reason field path and zero effect`() {
        val oracle = loadOracle()
        oracle.fixture.allOperations
            .filter { it.rootId == "root-z-rejections" }
            .forEach { assertOperation(oracle, it) }
    }

    @Test
    fun `idempotent replay returns no change with first-time ids`() {
        val oracle = loadOracle()
        val replay = oracle.fixture.allOperations.single { it.id == "main-replay" }
        assertEquals("main-recognize-03", replay.retryOf)
        assertOperation(oracle, replay)
        // Retry equality: the replayed operation carries the first-time ids of its original.
        val original = oracle.operations.getValue("main-recognize-03")
        val runtime = baselineRuntime(oracle, replay)
        val result = runtime.commit(replay.operation)
        val noChange = assertIs<Rg11ExecutionResult.NoChange>(result, "main-replay")
        assertEquals(expectedReturnedIds(original), noChange.returnedIds, "main-replay: no-change IDs equal first-time IDs")
    }

    @Test
    fun `statistics-time correction appends version two with operation confirmation`() {
        val oracle = loadOracle()
        val operation = oracle.fixture.allOperations.single { it.id == "main-correct" }
        val runtime = baselineRuntime(oracle, operation)
        val result = runtime.commit(operation.operation)
        assertIs<Rg11ExecutionResult.Accepted>(result)
        val snapshot = runtime.snapshot()
        val corrected = snapshot.formalTransactions.single { it.formalTransaction.transaction.id.value == "main-recognition-01" }
        assertEquals("main-recognition-01-v2", corrected.formalTransaction.transaction.currentVersionId.value)
        val v2 = corrected.formalTransaction.versions.single { it.id.value == "main-recognition-01-v2" }
        assertEquals(2, v2.versionNumber)
        assertEquals("2026-02-01T00:00:00+08:00", instantText(v2.times.statisticsAt))
        assertEquals("2026-01-31T00:00:00+08:00", instantText(v2.times.occurredAt))
        assertEquals(
            mapOf(TransactionVersionId("main-recognition-01-v2") to "main-correct-confirmation"),
            corrected.versionConfirmationIds,
        )
        assertEquals("2026-02-01T00:00:00+08:00", corrected.statisticsAtText)
        val confirmation = snapshot.confirmations.single()
        assertEquals("main-correct-confirmation", confirmation.id)
        assertEquals("main-correct", confirmation.operationId)
        assertEquals("operation", confirmation.subject.kind)
        assertEquals("main-correct", confirmation.subject.id)
        // The frozen version v1 keeps its times and the old version stays immutable.
        val v1 = corrected.formalTransaction.versions.single { it.id.value == "main-recognition-01-v1" }
        assertEquals("2026-01-31T00:00:00+08:00", instantText(v1.times.statisticsAt))
        // Balances and reconciliation state are untouched by a statistics-time correction.
        assertEquals(10_000L, snapshot.balances.getValue(com.unifiedledger.domain.AccountId("account-main-expense")).minorUnits)
        assertEquals(0L, snapshot.balances.getValue(com.unifiedledger.domain.AccountId("account-main-prepaid")).minorUnits)
        assertEquals(mapOf("main-opening-cash" to "pending", "main-purchase-cash" to "pending"), snapshot.reconciliation)
    }

    @Test
    fun `revision supersedes pending installments with global sequence continuation`() {
        val oracle = loadOracle()
        val operation = oracle.fixture.allOperations.single { it.id == "revision-revise" }
        val runtime = baselineRuntime(oracle, operation)
        assertIs<Rg11ExecutionResult.Accepted>(runtime.commit(operation.operation))
        val snapshot = runtime.snapshot()
        val revised = snapshot.installments.filter { it.revisionId == "revision-revision-02" }
        assertEquals(listOf("revision-installment-04", "revision-installment-05", "revision-installment-06"), revised.map { it.id })
        // Sequences continue globally across revisions (4, 5, 6) in revision order.
        assertEquals(listOf(4, 5, 6), revised.map { it.sequence })
        assertEquals(listOf("2026-02-15T00:00:00+08:00", "2026-03-15T00:00:00+08:00", "2026-04-15T00:00:00+08:00"), revised.map { instantText(it.scheduledAt) })
        val statuses =
            snapshot.derivedStatuses
                .filter { it.targetKind == "domain_entity" }
                .associate { it.targetId to it.value }
        assertEquals("recognized", statuses["revision-installment-01"])
        assertEquals("superseded", statuses["revision-installment-02"])
        assertEquals("superseded", statuses["revision-installment-03"])
        assertEquals("pending", statuses["revision-installment-04"])
        assertEquals("pending", statuses["revision-installment-05"])
        assertEquals("pending", statuses["revision-installment-06"])
        assertEquals("active", statuses["schedule-revision"])
        val revision2 = snapshot.revisions.single { it.id == "revision-revision-02" }
        assertEquals(2, revision2.revisionNumber)
        assertEquals("revision-installment-01", revision2.recognizedThrough)
        assertEquals(6_667L, revision2.remainingAmountMinor)
        // No formal effect: revise only appends domain entities.
        assertEquals(3, snapshot.formalTransactions.size)
        assertEquals(1, snapshot.auditLinks.size)
        assertEquals(0, snapshot.confirmations.size)
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
                    projectState(before, oracle.fixture),
                    projectState(after, oracle.fixture),
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
        operation: Rg11FixtureOperation,
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
                    projectState(before, oracle.fixture),
                    projectState(after, oracle.fixture),
                    "${operation.id}: non-mutating outcome changed state",
                )
            }
            else -> error("unsupported RG-11 expected status ${operation.expectedStatus}")
        }
        mismatches += deltaMismatches(oracle, document, before, after, operation.id)
        mismatches += statusChangeMismatches(oracle, document, before, after, operation.id)
        assertEquals(emptyList(), mismatches, "${operation.id}: complete state, delta and status fields")
    }

    private fun assertOutcome(
        oracle: OracleFixture,
        document: JsonObject,
        operation: Rg11FixtureOperation,
        result: Rg11ExecutionResult,
        label: String,
    ) {
        val expected = document.getValue("outcome").jsonObject
        when (operation.expectedStatus) {
            "accepted" -> {
                val accepted = assertIs<Rg11ExecutionResult.Accepted>(result, label)
                assertEquals(expectedReturnedIds(document), accepted.returnedIds, "$label: accepted IDs")
            }
            "no_change" -> {
                assertEquals("idempotent_replay", expected.string("reason_code"), "$label: frozen no-change reason")
                val noChange = assertIs<Rg11ExecutionResult.NoChange>(result, label)
                // The replayed operation returns the first-time ids (frozen `main-replay`
                // returned_ids equal `main-recognize-03` returned_ids).
                assertEquals(expectedReturnedIds(document), noChange.returnedIds, "$label: no-change IDs equal first-time IDs")
            }
            "rejected" -> {
                val rejected = assertIs<Rg11ExecutionResult.Rejected>(result, label)
                val reason = Rg11RejectionReason.entries.single { it.code == expected.string("reason_code") }
                assertEquals(reason, rejected.reason, "$label: rejection reason")
                val fieldPath = Rg11FieldPath.entries.single { it.value == expected.string("field_path") }
                assertEquals(fieldPath, rejected.fieldPath, "$label: field path")
            }
            else -> error("unsupported RG-11 expected status ${operation.expectedStatus}")
        }
    }

    private fun stateMismatches(
        oracle: OracleFixture,
        stateId: String,
        snapshot: Rg11Snapshot,
        label: String,
    ): List<String> {
        val expected = oracle.states.getValue(stateId)
        val projected = projectState(snapshot, oracle.fixture)
        val expectedPayload = expected.filterKeys { it !in STATE_META_KEYS && it != "catalog" }
        val mismatches =
            expectedPayload.mapNotNull { (key, value) ->
                val actual = projected[key]
                val equal =
                    if (key == "balances") {
                        // The frozen states publish balances in catalog order in the initial states and
                        // sorted by account id in the result states; the validator compares balances as
                        // maps, so the order is not contractual. The projection keeps the runtime
                        // catalog order and the comparison normalizes to a set of entries.
                        value.jsonArray.map(::canonical).toSet() == actual?.jsonArray?.map(::canonical)?.toSet()
                    } else {
                        actual == value
                    }
                if (equal) null else "$label: field $key expected=${describe(value)} actual=${describe(actual)}"
            }
        val extraKeys = projected.keys - expectedPayload.keys
        return mismatches + extraKeys.map { "$label: unexpected field $it" } + assertCatalogMismatches(oracle, stateId, label)
    }

    private fun describe(element: JsonElement?): String =
        when (element) {
            null -> "<missing>"
            else -> {
                if (element is JsonArray && element.isNotEmpty() && element.first() is JsonObject && element.first().jsonObject.containsKey("period_type")) {
                    "periods=" +
                        element.jsonArray.joinToString(",") { report ->
                            val item = report.jsonObject
                            item.string("period_type") + "/" + item.string("period")
                        }
                } else {
                    element.toString().take(400)
                }
            }
        }

    private fun deltaMismatches(
        oracle: OracleFixture,
        document: JsonObject,
        before: Rg11Snapshot,
        after: Rg11Snapshot,
        label: String,
    ): List<String> {
        val expected = document.getValue("deltas").jsonObject
        val beforeState = projectState(before, oracle.fixture)
        val afterState = projectState(after, oracle.fixture)

        // Entity changes: frozen id lists are sorted (validator + Python test demand sorted
        // added/changed/removed lists). Catalog collections are static in RG-11 (no action
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
            // test_rg11_golden_v2.py).
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
        before: Rg11Snapshot,
        after: Rg11Snapshot,
        label: String,
    ): List<String> {
        val expected = document["status_changes"]?.jsonArray ?: return emptyList()
        val beforeState = projectState(before, oracle.fixture)
        val afterState = projectState(after, oracle.fixture)
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
        operation: Rg11FixtureOperation,
    ): Rg11Runtime {
        val stateId = operation.baselineStateId ?: error("${operation.id} has no baseline state id")
        return buildStateRuntime(oracle, stateId)
    }

    private fun buildStateRuntime(
        oracle: OracleFixture,
        stateId: String,
    ): Rg11Runtime {
        val rootId = oracle.states.getValue(stateId).string("root_id")
        if (stateId == oracle.fixture.initialStateIds[rootId]) {
            return Rg11Runtime(
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
            result is Rg11ExecutionResult.Accepted ||
                (producer.expectedStatus == "rejected" && result is Rg11ExecutionResult.Rejected),
        ) {
            "${producer.id} baseline producer did not produce its expected outcome: $result"
        }
        return runtime
    }

    private fun producerOf(
        fixture: Rg11FixtureCase,
        stateId: String,
    ): Rg11FixtureOperation? =
        fixture.allOperations.firstOrNull { candidate ->
            candidate.retryOf == null &&
                candidate.resultStateId == stateId &&
                candidate.baselineStateId != stateId
        }

    private fun expectedReturnedIds(document: JsonObject): List<Rg11ReturnedId> =
        document["returned_ids"]?.jsonArray?.map { element ->
            val item = element.jsonObject
            when (item.string("kind")) {
                "transaction" -> Rg11ReturnedId.Transaction(TransactionId(item.string("id")))
                "transaction_version" -> Rg11ReturnedId.Version(TransactionVersionId(item.string("id")))
                "domain_entity" -> Rg11ReturnedId.DomainEntity(item.string("id"))
                "confirmation" -> Rg11ReturnedId.Confirmation(item.string("id"))
                "request" -> Rg11ReturnedId.Request(item.string("id"))
                else -> error("unsupported RG-11 returned id kind ${item.string("kind")}")
            }
        } ?: emptyList()

    // ------------------------------------------------------------------ state projection

    private fun projectState(
        snapshot: Rg11Snapshot,
        fixture: Rg11FixtureCase,
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
                                    "category_id" to semantics?.categoryId?.let(::json),
                                )
                            }
                        }
                    },
                ),
            "sources" to JsonArray(emptyList()),
            "candidates" to JsonArray(emptyList()),
            "confirmations" to JsonArray(snapshot.confirmations.map(::projectConfirmation)),
            "evidence" to JsonArray(emptyList()),
            "evidence_links" to JsonArray(emptyList()),
            "relations" to JsonArray(emptyList()),
            "domain_entities" to projectDomainEntities(snapshot),
            "audit_links" to
                JsonArray(
                    snapshot.auditLinks.map { link ->
                        jsonObjectOf(
                            "id" to json(link.id),
                            "type" to json(link.linkType),
                            "from" to jsonObjectOf("kind" to json(link.fromKind), "id" to json(link.fromId)),
                            "to" to jsonObjectOf("kind" to json(link.toKind), "id" to json(link.toId)),
                            "payload" to JsonObject(emptyMap()),
                        )
                    },
                ),
            "posting_reconciliations" to projectReconciliations(snapshot, fixture),
            "balances" to projectBalances(snapshot),
            "reports" to projectReports(snapshot),
            "derived_statuses" to
                JsonArray(
                    snapshot.derivedStatuses.map { status ->
                        jsonObjectOf(
                            "id" to json(status.id),
                            "target_kind" to json(status.targetKind),
                            "target_id" to json(status.targetId),
                            "status_name" to json(status.statusName),
                            "value" to json(status.value),
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
            "payload" to JsonObject(confirmation.payload.mapValues { (_, value) -> json(value) }),
        )

    private fun projectDomainEntities(snapshot: Rg11Snapshot): JsonArray {
        val installments =
            snapshot.installments.map { installment ->
                jsonObjectOf(
                    "id" to json(installment.id),
                    "type" to json("periodic_allocation_installment"),
                    "payload" to
                        jsonObjectOf(
                            "schedule_id" to json(installment.scheduleId),
                            "revision_id" to json(installment.revisionId),
                            "sequence" to json(installment.sequence),
                            "scheduled_at" to json(instantText(installment.scheduledAt)),
                            "amount" to json(moneyText(installment.amountMinor, installment.currency.precision)),
                            "currency" to json(installment.currency.code),
                        ),
                )
            }
        val revisions =
            snapshot.revisions.map { revision ->
                jsonObjectOf(
                    "id" to json(revision.id),
                    "type" to json("periodic_allocation_revision"),
                    "payload" to
                        jsonObjectOf(
                            "schedule_id" to json(revision.scheduleId),
                            "revision_number" to json(revision.revisionNumber),
                            // The frozen contract writes the null boundary explicitly.
                            "recognized_through" to (revision.recognizedThrough?.let(::json) ?: JsonNull),
                            "remaining_amount" to json(moneyText(revision.remainingAmountMinor, revision.currency.precision)),
                            "currency" to json(revision.currency.code),
                            "installment_ids" to JsonArray(revision.installmentIds.map(::json)),
                        ),
                )
            }
        val schedules =
            snapshot.schedules.map { schedule ->
                jsonObjectOf(
                    "id" to json(schedule.id),
                    "type" to json("periodic_allocation_schedule"),
                    "payload" to
                        jsonObjectOf(
                            "payment_transaction_id" to json(schedule.paymentTransactionId.value),
                            "prepaid_account_id" to json(schedule.prepaidAccountId.value),
                            "category_id" to json(schedule.categoryId.value),
                            "total_amount" to json(moneyText(schedule.totalAmountMinor, schedule.currency.precision)),
                            "currency" to json(schedule.currency.code),
                            "cadence" to json("monthly"),
                            "start_at" to json(instantText(schedule.startAt)),
                            "anchor" to anchorJson(schedule.anchor),
                        ),
                )
            }
        return JsonArray(installments + revisions + schedules)
    }

    private fun anchorJson(anchor: com.unifiedledger.domain.PeriodicAllocationAnchor): JsonObject =
        when (anchor) {
            com.unifiedledger.domain.PeriodicAllocationAnchor.MonthEnd -> jsonObjectOf("type" to json("month_end"))
            is com.unifiedledger.domain.PeriodicAllocationAnchor.DayOfMonth ->
                jsonObjectOf(
                    "type" to json("day_of_month"),
                    "day" to json(anchor.day),
                )
        }

    private fun projectReconciliations(
        snapshot: Rg11Snapshot,
        fixture: Rg11FixtureCase,
    ): JsonArray =
        JsonArray(
            snapshot.reconciliation.map { (postingId, status) ->
                jsonObjectOf(
                    "id" to json(fixture.reconciliationIds[postingId] ?: error("no reconciliation id anchor for $postingId")),
                    "posting_id" to json(postingId),
                    "status" to json(status),
                )
            },
        )

    private fun projectBalances(snapshot: Rg11Snapshot): JsonArray =
        JsonArray(
            snapshot.balances.entries.sortedBy { it.key.value }.map { (accountId, amount) ->
                jsonObjectOf(
                    "account_id" to json(accountId.value),
                    "currency" to json(amount.currency.code),
                    "amount" to json(moneyText(amount)),
                )
            },
        )

    private fun projectReports(snapshot: Rg11Snapshot): JsonArray =
        JsonArray(
            snapshot.reports.entries.map { (period, report) ->
                val (periodType, periodKey) = if (period == "cumulative") "cumulative" to "all" else "day" to period
                jsonObjectOf(
                    "period_type" to json(periodType),
                    "period" to json(periodKey),
                    "metrics" to
                        JsonArray(
                            listOf(
                                metricJson("budget", report.budgetMinor),
                                metricJson("cash_outflow", report.cashOutflowMinor),
                                metricJson("category_effect", report.categoryEffectMinor),
                                metricJson("consumption", report.consumptionMinor),
                                metricJson("income", report.incomeMinor),
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
        val raw = Files.readString(repositoryFile("golden/rules/rg-11.json"))
        val contract = Json.parseToJsonElement(raw).jsonObject
        val inputs = parseRg11FixtureInputs(Files.readString(repositoryFile("tests/fixtures/rg11-runtime-input.json")))
        val fixture = adaptRg11Fixture(raw, inputs)
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

    private fun repositoryFile(relative: String): Path {
        var candidate = Path.of(System.getProperty("user.dir"))
        repeat(8) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)
            candidate = candidate.parent ?: error("repository root not found")
        }
        error("repository root not found")
    }

    private data class OracleFixture(
        val fixture: Rg11FixtureCase,
        val inputs: Rg11FixtureInputs,
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
