package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.goldenV2MigrationId
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.LedgerId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal data class GoldenV2RootSpec(
    val purpose: String,
    val rootId: String,
    val initialStateId: String,
    val openingVersionId: String,
    val openingPostingSetId: String,
    val operations: List<GoldenV2OperationSpec>,
    /** RG-02 variant roots start from an empty ledger (no opening chain). */
    val seedOpening: Boolean = true,
)

internal data class GoldenV2OperationSpec(
    val index: Int,
    val locator: String,
    val discriminator: String,
    val stateLocator: String = locator,
)

internal data class GoldenV2ObservedRoot(
    val spec: GoldenV2RootSpec,
    val initialState: JsonObject,
    val operations: List<GoldenV2ObservedOperation>,
)

internal data class GoldenV2ObservedOperation(
    val operationId: String,
    val before: JsonObject,
    val after: JsonObject,
    val outcome: JsonObject,
    val returnedIds: JsonArray,
)

internal data class GoldenV2OperationResult(
    val outcome: JsonObject,
    val returnedIds: JsonArray,
)

internal class GoldenV2RootRuntime(
    val projectState: (id: String, asOfOperationId: String?) -> JsonObject,
    val executeOperation: (GoldenV2OperationSpec) -> GoldenV2OperationResult,
)

internal fun executeGoldenV2Root(
    caseId: String,
    spec: GoldenV2RootSpec,
    ledgerId: LedgerId,
    v1: JsonObject,
    requestId: (GoldenV2OperationSpec) -> String?,
    createRuntime: (
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
        operationIdsByRequest: Map<String, String>,
    ) -> GoldenV2RootRuntime,
): GoldenV2ObservedRoot {
    val driver =
        JdbcSqliteDriver(
            JdbcSqliteDriver.IN_MEMORY,
            Properties().apply { setProperty("foreign_keys", "true") },
        )
    try {
        LedgerDatabase.Schema.create(driver)
        val database = LedgerDatabase(driver)
        seedGoldenV2Opening(database, ledgerId, spec, v1)
        val operationIdsByRequest = linkedMapOf<String, String>()
        spec.operations.forEach { operationSpec ->
            val id = requestId(operationSpec)
            if (id != null && id !in operationIdsByRequest) {
                operationIdsByRequest[id] =
                    goldenV2MigrationId(
                        caseId,
                        spec.rootId,
                        "operation",
                        operationSpec.locator,
                        operationSpec.discriminator,
                    )
            }
        }
        val runtime = createRuntime(database, driver, operationIdsByRequest)
        val initial = runtime.projectState(spec.initialStateId, null)
        val operations =
            spec.operations.map { operationSpec ->
                val operationId =
                    goldenV2MigrationId(
                        caseId,
                        spec.rootId,
                        "operation",
                        operationSpec.locator,
                        operationSpec.discriminator,
                    )
                val before = runtime.projectState("before-$operationId", null)
                val result = runtime.executeOperation(operationSpec)
                val stateId =
                    goldenV2MigrationId(
                        caseId,
                        spec.rootId,
                        "state",
                        operationSpec.stateLocator,
                        operationSpec.discriminator,
                    )
                val after = runtime.projectState(stateId, operationId)
                GoldenV2ObservedOperation(operationId, before, after, result.outcome, result.returnedIds)
            }
        return GoldenV2ObservedRoot(spec, initial, operations)
    } finally {
        driver.close()
    }
}

internal fun assertGoldenV2Oracle(
    observed: List<GoldenV2ObservedRoot>,
    expected: JsonObject,
    expectedRootCount: Int,
    expectedOperationCount: Int,
    expectedStateCount: Int,
) {
    assertEquals(expectedRootCount, observed.size)
    assertEquals(expectedOperationCount, observed.sumOf { it.operations.size })
    assertEquals(expectedStateCount, observed.sumOf { it.operations.size + 1 })

    val expectedRootArray = expected.getValue("roots").jsonArray
    val expectedStateArray = expected.getValue("states").jsonArray
    val expectedOperationArray = expected.getValue("operations").jsonArray
    assertEquals(expectedRootCount, expectedRootArray.size, "expected roots count")
    assertEquals(expectedStateCount, expectedStateArray.size, "expected states count")
    assertEquals(expectedOperationCount, expectedOperationArray.size, "expected operations count")

    val expectedRoots = expectedRootArray.associateBy { it.jsonObject.goldenV2String("purpose") }
    val expectedStates = expectedStateArray.associateBy { it.jsonObject.goldenV2String("id") }
    val expectedOperations = expectedOperationArray.associateBy { it.jsonObject.goldenV2String("id") }
    val expectedRootIds = expectedRootArray.map { it.jsonObject.goldenV2String("id") }
    assertEquals(expectedRootCount, expectedRoots.size, "expected root purposes unique")
    assertEquals(expectedRootCount, expectedRootIds.toSet().size, "expected root IDs unique")
    assertEquals(expectedStateCount, expectedStates.size, "expected state IDs unique")
    assertEquals(expectedOperationCount, expectedOperations.size, "expected operation IDs unique")

    val expectedOperationIds =
        expectedRootArray.flatMap { root ->
            root.jsonObject
                .getValue("operation_ids")
                .jsonArray
                .map { it.jsonPrimitive.content }
        }
    assertEquals(expectedOperationCount, expectedOperationIds.size, "expected operation references count")
    assertEquals(expectedOperationCount, expectedOperationIds.toSet().size, "expected operation references unique")
    assertEquals(expectedOperations.keys, expectedOperationIds.toSet(), "expected operation closure")

    val expectedReferencedStateIds =
        expectedRootArray.flatMap { root ->
            val rootObject = root.jsonObject
            listOf(rootObject.goldenV2String("initial_state_id")) +
                rootObject.getValue("operation_ids").jsonArray.map { operationId ->
                    expectedOperations.getValue(operationId.jsonPrimitive.content).jsonObject.goldenV2String("result_state_id")
                }
        }
    assertEquals(expectedStateCount, expectedReferencedStateIds.size, "expected state references count")
    assertEquals(expectedStateCount, expectedReferencedStateIds.toSet().size, "expected state references unique")
    assertEquals(expectedStates.keys, expectedReferencedStateIds.toSet(), "expected state closure")
    expectedOperations.forEach { (operationId, operation) ->
        val operationObject = operation.jsonObject
        assertTrue(expectedStates.containsKey(operationObject.goldenV2String("baseline_state_id")), "$operationId baseline state reference")
        assertTrue(expectedStates.containsKey(operationObject.goldenV2String("result_state_id")), "$operationId result state reference")
    }

    val observedRootPurposes = observed.map { it.spec.purpose }
    val observedRootIds = observed.map { it.spec.rootId }
    assertEquals(expectedRootCount, observedRootPurposes.toSet().size, "observed root purposes unique")
    assertEquals(expectedRoots.keys, observedRootPurposes.toSet(), "observed root closure")
    assertEquals(expectedRootCount, observedRootIds.toSet().size, "observed root IDs unique")
    assertEquals(expectedRootIds.toSet(), observedRootIds.toSet(), "observed root ID closure")
    val observedOperationIds = observed.flatMap { root -> root.operations.map { it.operationId } }
    assertEquals(expectedOperationCount, observedOperationIds.toSet().size, "observed operation IDs unique")
    assertEquals(expectedOperations.keys, observedOperationIds.toSet(), "observed operation closure")
    val observedStateIds =
        observed.flatMap { root ->
            listOf(root.initialState.goldenV2String("id")) + root.operations.map { it.after.goldenV2String("id") }
        }
    assertEquals(expectedStateCount, observedStateIds.toSet().size, "observed state IDs unique")
    assertEquals(expectedStates.keys, observedStateIds.toSet(), "observed state closure")

    observed.forEach { root ->
        val expectedRoot = expectedRoots.getValue(root.spec.purpose).jsonObject
        assertEquals(root.spec.rootId, expectedRoot.goldenV2String("id"), root.spec.purpose)
        assertEquals(
            goldenV2CanonicalState(expectedStates.getValue(expectedRoot.goldenV2String("initial_state_id")).jsonObject),
            goldenV2CanonicalState(root.initialState),
        )
        val operationIds = expectedRoot.getValue("operation_ids").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(root.operations.size, operationIds.size)
        root.operations.zip(operationIds).forEach { (actual, operationId) ->
            val expectedOperation = expectedOperations.getValue(operationId).jsonObject
            assertEquals(actual.operationId, operationId)
            assertEquals(expectedOperation.getValue("outcome"), actual.outcome, "$operationId outcome")
            assertEquals(
                goldenV2CanonicalReturnedIds(expectedOperation.getValue("returned_ids").jsonArray),
                goldenV2CanonicalReturnedIds(actual.returnedIds),
                "$operationId returned_ids",
            )
            assertEquals(
                goldenV2CanonicalState(expectedStates.getValue(expectedOperation.goldenV2String("result_state_id")).jsonObject),
                goldenV2CanonicalState(actual.after),
                "$operationId state",
            )
            assertEquals(expectedOperation.getValue("deltas"), goldenV2Deltas(actual.before, actual.after), "$operationId deltas")
            assertEquals(
                expectedOperation.getValue("status_changes"),
                goldenV2StatusChanges(actual.before, actual.after),
                "$operationId status_changes",
            )
            if (expectedOperation.getValue("outcome").jsonObject.goldenV2String("status") != "accepted") {
                assertEquals(goldenV2StatePayload(actual.before), goldenV2StatePayload(actual.after), "$operationId residue")
            }
        }
    }
}

private val GOLDEN_V2_ID_COLLECTIONS =
    setOf(
        "transactions",
        "transaction_versions",
        "posting_sets",
        "postings",
        "sources",
        "candidates",
        "confirmations",
        "evidence",
        "evidence_links",
        "relations",
        "domain_entities",
        "audit_links",
        "posting_reconciliations",
    )

private fun goldenV2CanonicalState(state: JsonObject): JsonObject = goldenV2CanonicalElement(state, emptyList()).jsonObject

private fun goldenV2CanonicalElement(
    element: JsonElement,
    path: List<String>,
): JsonElement =
    when (element) {
        is JsonObject ->
            JsonObject(
                element.entries.associate { (key, value) ->
                    key to goldenV2CanonicalElement(value, path + key)
                },
            )
        is JsonArray -> goldenV2CanonicalArray(element, path)
        else -> element
    }

private fun goldenV2CanonicalArray(
    array: JsonArray,
    path: List<String>,
): JsonArray {
    val items = array.map { goldenV2CanonicalElement(it, path) }
    val key = path.lastOrNull()
    val sorted =
        when {
            path.size == 1 && key != null && key in GOLDEN_V2_ID_COLLECTIONS -> items.sortedWith(compareBy { it.goldenV2Scalar("id") })
            path == listOf("catalog", "accounts") || path == listOf("catalog", "categories") ->
                items.sortedWith(compareBy { it.goldenV2Scalar("id") })
            key == "balances" -> items.sortedWith(compareBy({ it.goldenV2Scalar("account_id") }, { it.goldenV2Scalar("currency") }))
            key == "reports" -> items.sortedWith(compareBy({ it.goldenV2Scalar("period_type") }, { it.goldenV2Scalar("period") }))
            key == "metrics" -> items.sortedWith(compareBy({ it.goldenV2Scalar("metric") }, { it.goldenV2Scalar("currency") }))
            key == "derived_statuses" -> items.sortedWith(compareBy({ it.goldenV2Scalar("target_kind") }, { it.goldenV2Scalar("target_id") }, { it.goldenV2Scalar("status_name") }))
            key == "member_refs" -> items.sortedWith(compareBy({ it.goldenV2Scalar("kind") }, { it.goldenV2Scalar("id") }))
            key == "funding_components" || key == "known_funding_components" ->
                items.sortedWith(
                    compareBy({ it.goldenV2Scalar("posting_id") }, { it.goldenV2Scalar("account_id") }, { it.goldenV2Scalar("funding_amount") }, { it.goldenV2Scalar("currency") }),
                )
            key == "posting_ids" || key == "source_ids" || key == "evidence_refs" || key == "requires_confirmation" ->
                items.sortedBy { it.jsonPrimitive.content }
            else -> items
        }
    return JsonArray(sorted)
}

private fun JsonElement.goldenV2Scalar(key: String): String =
    when (this) {
        is JsonObject -> this[key]?.let { value -> if (value is JsonNull) "" else value.jsonPrimitive.content } ?: ""
        else -> jsonPrimitive.content
    }

private fun goldenV2CanonicalReturnedIds(returnedIds: JsonArray): JsonArray = JsonArray(returnedIds.map { it }.sortedWith(compareBy({ it.goldenV2Scalar("kind") }, { it.goldenV2Scalar("id") })))

private fun seedGoldenV2Opening(
    database: LedgerDatabase,
    ledgerId: LedgerId,
    spec: GoldenV2RootSpec,
    v1: JsonObject,
) {
    if (!spec.seedOpening) return
    val opening =
        v1
            .getValue("opening")
            .jsonObject
            .getValue("transactions")
            .jsonArray
            .single()
            .jsonObject
    database.ledgerQueries.insertPostingSet(spec.openingPostingSetId, ledgerId.value)
    database.ledgerQueries.insertTransaction(opening.goldenV2String("id"), ledgerId.value, "OPENING_BALANCE")
    database.ledgerQueries.insertTransactionVersion(
        spec.openingVersionId,
        opening.goldenV2String("id"),
        ledgerId.value,
        1,
        spec.openingPostingSetId,
        opening.goldenV2String("occurred_at"),
        opening.goldenV2String("occurred_at"),
        opening.goldenV2String("occurred_at"),
        null,
    )
    database.ledgerQueries.insertTransactionCurrentVersion(
        opening.goldenV2String("id"),
        ledgerId.value,
        spec.openingVersionId,
    )
    opening.getValue("postings").jsonArray.forEachIndexed { index, element ->
        val posting = element.jsonObject
        database.ledgerQueries.insertPosting(
            posting.goldenV2String("id"),
            spec.openingPostingSetId,
            ledgerId.value,
            index.toLong(),
            posting.goldenV2String("account_id"),
            BigDecimal(posting.goldenV2String("amount")).movePointRight(2).longValueExact(),
            posting.goldenV2String("currency"),
            2,
        )
    }
}

private val GOLDEN_V2_ENTITY_COLLECTIONS =
    linkedMapOf(
        "catalog_accounts" to listOf("catalog", "accounts"),
        "catalog_categories" to listOf("catalog", "categories"),
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

internal fun goldenV2Deltas(
    before: JsonObject,
    after: JsonObject,
): JsonObject =
    goldenV2JsonObjectOf(
        "entity_changes" to
            JsonObject(
                GOLDEN_V2_ENTITY_COLLECTIONS.mapValues { (_, path) ->
                    val beforeItems = before.goldenV2ArrayAt(path).associateBy { it.jsonObject.goldenV2String("id") }
                    val afterItems = after.goldenV2ArrayAt(path).associateBy { it.jsonObject.goldenV2String("id") }
                    val beforeIds = beforeItems.keys
                    val afterIds = afterItems.keys
                    goldenV2JsonObjectOf(
                        "added_ids" to JsonArray((afterIds - beforeIds).sorted().map(::JsonPrimitive)),
                        "changed_ids" to
                            JsonArray(
                                (beforeIds intersect afterIds)
                                    .filter { beforeItems[it] != afterItems[it] }
                                    .sorted()
                                    .map(::JsonPrimitive),
                            ),
                        "removed_ids" to JsonArray((beforeIds - afterIds).sorted().map(::JsonPrimitive)),
                    )
                },
            ),
        "value_changes" to
            goldenV2JsonObjectOf(
                "balances" to goldenV2BalanceChanges(before, after),
                "reports" to goldenV2ReportChanges(before, after),
                "derived_statuses" to goldenV2DerivedChanges(before, after),
            ),
    )

private fun goldenV2BalanceChanges(
    before: JsonObject,
    after: JsonObject,
): JsonArray {
    fun values(state: JsonObject) =
        state.getValue("balances").jsonArray.associateBy {
            val item = it.jsonObject
            item.goldenV2String("account_id") to item.goldenV2String("currency")
        }
    val old = values(before)
    val new = values(after)
    return JsonArray(
        (old.keys + new.keys).distinct().sortedWith(compareBy({ it.first }, { it.second })).mapNotNull { key ->
            val oldAmount = old[key]?.jsonObject?.goldenV2String("amount")
            val newAmount = new[key]?.jsonObject?.goldenV2String("amount")
            if (oldAmount == newAmount) {
                null
            } else {
                goldenV2JsonObjectOf(
                    "key" to
                        goldenV2JsonObjectOf(
                            "account_id" to JsonPrimitive(key.first),
                            "currency" to JsonPrimitive(key.second),
                        ),
                    "before" to (oldAmount?.let(::JsonPrimitive) ?: JsonNull),
                    "after" to (newAmount?.let(::JsonPrimitive) ?: JsonNull),
                )
            }
        },
    )
}

private data class GoldenV2ReportKey(
    val type: String,
    val period: String,
    val metric: String,
    val currency: String?,
)

private fun goldenV2ReportChanges(
    before: JsonObject,
    after: JsonObject,
): JsonArray {
    fun values(state: JsonObject): Map<GoldenV2ReportKey, JsonObject> =
        buildMap {
            state.getValue("reports").jsonArray.forEach { reportElement ->
                val report = reportElement.jsonObject
                report.getValue("metrics").jsonArray.forEach { metricElement ->
                    val metric = metricElement.jsonObject
                    put(
                        GoldenV2ReportKey(
                            report.goldenV2String("period_type"),
                            report.goldenV2String("period"),
                            metric.goldenV2String("metric"),
                            metric["currency"]?.jsonPrimitive?.content,
                        ),
                        JsonObject(metric.filterKeys { it != "metric" }),
                    )
                }
            }
        }
    val old = values(before)
    val new = values(after)
    val comparator = compareBy<GoldenV2ReportKey>({ it.type }, { it.period }, { it.metric }, { it.currency })
    return JsonArray(
        (old.keys + new.keys).distinct().sortedWith(comparator).mapNotNull { key ->
            if (old[key] == new[key]) {
                null
            } else {
                goldenV2JsonObjectOf(
                    "key" to
                        goldenV2JsonObjectOf(
                            "period_type" to JsonPrimitive(key.type),
                            "period" to JsonPrimitive(key.period),
                            "metric" to JsonPrimitive(key.metric),
                            "currency" to key.currency?.let(::JsonPrimitive),
                        ),
                    "before" to (old[key] ?: JsonNull),
                    "after" to (new[key] ?: JsonNull),
                )
            }
        },
    )
}

private data class GoldenV2DerivedKey(
    val kind: String,
    val targetId: String,
    val statusName: String,
)

private data class GoldenV2DerivedChange(
    val key: GoldenV2DerivedKey,
    val before: String?,
    val after: String?,
)

private fun goldenV2DerivedChanges(
    before: JsonObject,
    after: JsonObject,
): JsonArray =
    JsonArray(
        goldenV2DerivedValueChanges(before, after).map { (key, old, new) ->
            goldenV2JsonObjectOf(
                "key" to
                    goldenV2JsonObjectOf(
                        "kind" to JsonPrimitive(key.kind),
                        "target_id" to JsonPrimitive(key.targetId),
                        "status_name" to JsonPrimitive(key.statusName),
                    ),
                "before" to (old?.let(::JsonPrimitive) ?: JsonNull),
                "after" to (new?.let(::JsonPrimitive) ?: JsonNull),
            )
        },
    )

internal fun goldenV2StatusChanges(
    before: JsonObject,
    after: JsonObject,
): JsonArray =
    JsonArray(
        goldenV2DerivedValueChanges(before, after).map { (key, old, new) ->
            goldenV2JsonObjectOf(
                "target_kind" to JsonPrimitive(key.kind),
                "target_id" to JsonPrimitive(key.targetId),
                "status_name" to JsonPrimitive(key.statusName),
                "before" to (old?.let(::JsonPrimitive) ?: JsonNull),
                "after" to (new?.let(::JsonPrimitive) ?: JsonNull),
            )
        },
    )

private fun goldenV2DerivedValueChanges(
    before: JsonObject,
    after: JsonObject,
): List<GoldenV2DerivedChange> {
    fun values(state: JsonObject) =
        state.getValue("derived_statuses").jsonArray.associate { element ->
            val item = element.jsonObject
            GoldenV2DerivedKey(
                item.goldenV2String("target_kind"),
                item.goldenV2String("target_id"),
                item.goldenV2String("status_name"),
            ) to item.goldenV2String("value")
        }
    val old = values(before)
    val new = values(after)
    val comparator = compareBy<GoldenV2DerivedKey>({ it.kind }, { it.targetId }, { it.statusName })
    return (old.keys + new.keys).distinct().sortedWith(comparator).mapNotNull { key ->
        if (old[key] == new[key]) null else GoldenV2DerivedChange(key, old[key], new[key])
    }
}

private fun JsonObject.goldenV2ArrayAt(path: List<String>): JsonArray {
    var current: JsonElement = this
    path.forEach { current = current.jsonObject.getValue(it) }
    return current.jsonArray
}

internal fun goldenV2StatePayload(state: JsonObject): JsonObject = JsonObject(goldenV2CanonicalState(state).filterKeys { it !in setOf("id", "as_of_operation_id") })

private fun goldenV2JsonObjectOf(vararg fields: Pair<String, JsonElement?>): JsonObject = JsonObject(fields.mapNotNull { (key, value) -> value?.let { key to it } }.toMap())

private fun JsonObject.goldenV2String(key: String): String = getValue(key).jsonPrimitive.content
