package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.goldenV2MigrationId
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.LedgerId
import java.math.BigDecimal
import java.util.Properties
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals

internal data class GoldenV2RootSpec(
    val purpose: String,
    val rootId: String,
    val initialStateId: String,
    val openingVersionId: String,
    val openingPostingSetId: String,
    val operations: List<GoldenV2OperationSpec>,
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
    val driver = JdbcSqliteDriver(
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
                operationIdsByRequest[id] = goldenV2MigrationId(
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
        val operations = spec.operations.map { operationSpec ->
            val operationId = goldenV2MigrationId(
                caseId,
                spec.rootId,
                "operation",
                operationSpec.locator,
                operationSpec.discriminator,
            )
            val before = runtime.projectState("before-$operationId", null)
            val result = runtime.executeOperation(operationSpec)
            val stateId = goldenV2MigrationId(
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

    val expectedRoots = expected.getValue("roots").jsonArray.associateBy { it.jsonObject.goldenV2String("purpose") }
    val expectedStates = expected.getValue("states").jsonArray.associateBy { it.jsonObject.goldenV2String("id") }
    val expectedOperations = expected.getValue("operations").jsonArray.associateBy { it.jsonObject.goldenV2String("id") }

    observed.forEach { root ->
        val expectedRoot = expectedRoots.getValue(root.spec.purpose).jsonObject
        assertEquals(root.spec.rootId, expectedRoot.goldenV2String("id"), root.spec.purpose)
        assertEquals(expectedStates.getValue(expectedRoot.goldenV2String("initial_state_id")), root.initialState)
        val operationIds = expectedRoot.getValue("operation_ids").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(root.operations.size, operationIds.size)
        root.operations.zip(operationIds).forEach { (actual, operationId) ->
            val expectedOperation = expectedOperations.getValue(operationId).jsonObject
            assertEquals(actual.operationId, operationId)
            assertEquals(expectedOperation.getValue("outcome"), actual.outcome, "$operationId outcome")
            assertEquals(expectedOperation.getValue("returned_ids"), actual.returnedIds, "$operationId returned_ids")
            assertEquals(
                expectedStates.getValue(expectedOperation.goldenV2String("result_state_id")),
                actual.after,
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

private fun seedGoldenV2Opening(
    database: LedgerDatabase,
    ledgerId: LedgerId,
    spec: GoldenV2RootSpec,
    v1: JsonObject,
) {
    val opening = v1.getValue("opening").jsonObject.getValue("transactions").jsonArray.single().jsonObject
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

private val GOLDEN_V2_ENTITY_COLLECTIONS = linkedMapOf(
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

private fun goldenV2Deltas(before: JsonObject, after: JsonObject): JsonObject = goldenV2JsonObjectOf(
    "entity_changes" to JsonObject(GOLDEN_V2_ENTITY_COLLECTIONS.mapValues { (_, path) ->
        val beforeItems = before.goldenV2ArrayAt(path).associateBy { it.jsonObject.goldenV2String("id") }
        val afterItems = after.goldenV2ArrayAt(path).associateBy { it.jsonObject.goldenV2String("id") }
        val beforeIds = beforeItems.keys
        val afterIds = afterItems.keys
        goldenV2JsonObjectOf(
            "added_ids" to JsonArray((afterIds - beforeIds).sorted().map(::JsonPrimitive)),
            "changed_ids" to JsonArray(
                (beforeIds intersect afterIds)
                    .filter { beforeItems[it] != afterItems[it] }
                    .sorted()
                    .map(::JsonPrimitive),
            ),
            "removed_ids" to JsonArray((beforeIds - afterIds).sorted().map(::JsonPrimitive)),
        )
    }),
    "value_changes" to goldenV2JsonObjectOf(
        "balances" to goldenV2BalanceChanges(before, after),
        "reports" to goldenV2ReportChanges(before, after),
        "derived_statuses" to goldenV2DerivedChanges(before, after),
    ),
)

private fun goldenV2BalanceChanges(before: JsonObject, after: JsonObject): JsonArray {
    fun values(state: JsonObject) = state.getValue("balances").jsonArray.associateBy {
        val item = it.jsonObject
        item.goldenV2String("account_id") to item.goldenV2String("currency")
    }
    val old = values(before)
    val new = values(after)
    return JsonArray((old.keys + new.keys).distinct().sortedWith(compareBy({ it.first }, { it.second })).mapNotNull { key ->
        val oldAmount = old[key]?.jsonObject?.goldenV2String("amount")
        val newAmount = new[key]?.jsonObject?.goldenV2String("amount")
        if (oldAmount == newAmount) {
            null
        } else {
            goldenV2JsonObjectOf(
                "key" to goldenV2JsonObjectOf(
                    "account_id" to JsonPrimitive(key.first),
                    "currency" to JsonPrimitive(key.second),
                ),
                "before" to (oldAmount?.let(::JsonPrimitive) ?: JsonNull),
                "after" to (newAmount?.let(::JsonPrimitive) ?: JsonNull),
            )
        }
    })
}

private data class GoldenV2ReportKey(
    val type: String,
    val period: String,
    val metric: String,
    val currency: String,
)

private fun goldenV2ReportChanges(before: JsonObject, after: JsonObject): JsonArray {
    fun values(state: JsonObject): Map<GoldenV2ReportKey, JsonObject> = buildMap {
        state.getValue("reports").jsonArray.forEach { reportElement ->
            val report = reportElement.jsonObject
            report.getValue("metrics").jsonArray.forEach { metricElement ->
                val metric = metricElement.jsonObject
                put(
                    GoldenV2ReportKey(
                        report.goldenV2String("period_type"),
                        report.goldenV2String("period"),
                        metric.goldenV2String("metric"),
                        metric.goldenV2String("currency"),
                    ),
                    JsonObject(metric.filterKeys { it != "metric" }),
                )
            }
        }
    }
    val old = values(before)
    val new = values(after)
    val comparator = compareBy<GoldenV2ReportKey>({ it.type }, { it.period }, { it.metric }, { it.currency })
    return JsonArray((old.keys + new.keys).distinct().sortedWith(comparator).mapNotNull { key ->
        if (old[key] == new[key]) {
            null
        } else {
            goldenV2JsonObjectOf(
                "key" to goldenV2JsonObjectOf(
                    "period_type" to JsonPrimitive(key.type),
                    "period" to JsonPrimitive(key.period),
                    "metric" to JsonPrimitive(key.metric),
                    "currency" to JsonPrimitive(key.currency),
                ),
                "before" to (old[key] ?: JsonNull),
                "after" to (new[key] ?: JsonNull),
            )
        }
    })
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

private fun goldenV2DerivedChanges(before: JsonObject, after: JsonObject): JsonArray = JsonArray(
    goldenV2DerivedValueChanges(before, after).map { (key, old, new) ->
        goldenV2JsonObjectOf(
            "key" to goldenV2JsonObjectOf(
                "kind" to JsonPrimitive(key.kind),
                "target_id" to JsonPrimitive(key.targetId),
                "status_name" to JsonPrimitive(key.statusName),
            ),
            "before" to (old?.let(::JsonPrimitive) ?: JsonNull),
            "after" to (new?.let(::JsonPrimitive) ?: JsonNull),
        )
    },
)

private fun goldenV2StatusChanges(before: JsonObject, after: JsonObject): JsonArray = JsonArray(
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

private fun goldenV2DerivedValueChanges(before: JsonObject, after: JsonObject): List<GoldenV2DerivedChange> {
    fun values(state: JsonObject) = state.getValue("derived_statuses").jsonArray.associate { element ->
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

private fun goldenV2StatePayload(state: JsonObject): JsonObject =
    JsonObject(state.filterKeys { it !in setOf("id", "as_of_operation_id") })

private fun goldenV2JsonObjectOf(vararg fields: Pair<String, JsonElement?>): JsonObject =
    JsonObject(fields.mapNotNull { (key, value) -> value?.let { key to it } }.toMap())

private fun JsonObject.goldenV2String(key: String): String = getValue(key).jsonPrimitive.content
