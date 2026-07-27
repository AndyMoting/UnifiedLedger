package com.unifiedledger.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.*
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.*
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class Rg05FullStateOracleTest {
    @Test
    fun `v1 independently executes 17 roots before v2 validates 25 operations and 42 states`() {
        val (observed, expected) = observeRg05Oracle()

        assertGoldenV2Oracle(observed, expected, expectedRootCount = 17, expectedOperationCount = 25, expectedStateCount = 42)
    }

    @Test
    fun `swapped operation emission order fails the oracle`() {
        val (observed, expected) = observeRg05Oracle()
        assertGoldenV2Oracle(
            observed,
            expected,
            expectedRootCount = 17,
            expectedOperationCount = 25,
            expectedStateCount = 42,
        )

        val importIndex = observed.indexOfFirst { it.spec.purpose == "rg05_import_lifecycle" }
        val importRoot = observed[importIndex]
        val swappedOperations = importRoot.operations.toMutableList().apply {
            val first = this[0]
            this[0] = this[1]
            this[1] = first
        }
        val swappedEmission = observed.toMutableList().apply {
            this[importIndex] = importRoot.copy(operations = swappedOperations)
        }

        assertFailsWith<AssertionError> {
            assertGoldenV2Oracle(
                swappedEmission,
                expected,
                expectedRootCount = 17,
                expectedOperationCount = 25,
                expectedStateCount = 42,
            )
        }
    }
}

private fun observeRg05Oracle(): Pair<List<GoldenV2ObservedRoot>, JsonObject> {
    val raw = Files.readString(rg05OracleRepositoryFile("golden/rules/rg-05.json"))
    val v1 = Json.parseToJsonElement(raw).jsonObject
    val decoded = assertIs<Rg05RawJsonDecodeResult.Success>(decodeRg05RawJson(raw)).value
    val specs = rg05OracleRootSpecs(v1)
    val observed = specs.map { executeRg05OracleRoot(it, decoded, v1) }
    val expected = Json.parseToJsonElement(
        Files.readString(rg05OracleRepositoryFile("docs/migrations/golden-v2/rg-05-expected.json")),
    ).jsonObject
    return observed to expected
}

private fun rg05OracleRootSpecs(v1: JsonObject): List<GoldenV2RootSpec> {
    fun root(
        purpose: String,
        rootLocator: String,
        rootDiscriminator: String,
        operations: List<GoldenV2OperationSpec>,
    ): GoldenV2RootSpec {
        val rootId = goldenV2RootId(RG05_CASE_ID, rootLocator, rootDiscriminator)
        return GoldenV2RootSpec(
            purpose = purpose,
            rootId = rootId,
            initialStateId = goldenV2MigrationId(
                RG05_CASE_ID,
                rootId,
                "state",
                RG05_OPENING_LOCATOR,
                RG05_OPENING_TRANSACTION_ID,
            ),
            openingVersionId = goldenV2MigrationId(
                RG05_CASE_ID,
                rootId,
                "transaction_version",
                RG05_OPENING_LOCATOR,
                RG05_OPENING_TRANSACTION_ID,
            ),
            openingPostingSetId = goldenV2MigrationId(
                RG05_CASE_ID,
                rootId,
                "posting_set",
                RG05_OPENING_LOCATOR,
                RG05_OPENING_TRANSACTION_ID,
            ),
            operations = operations,
        )
    }

    val manualRequestId = v1.getValue("manual_path").jsonObject
        .getValue("input").jsonObject.rg05OracleString("request_id")
    val importOperations = v1.getValue("import_path").jsonObject
        .getValue("ordered_operations").jsonArray
    val importOperationIds = importOperations.map { it.jsonObject.rg05OracleString("id") }
    val bankSourceId = importOperations[0].jsonObject.getValue("input").jsonObject
        .getValue("bank_fact").jsonObject.rg05OracleString("source_id")
    val confirmRequestId = importOperations[1].jsonObject.getValue("input").jsonObject.rg05OracleString("request_id")
    val receiptEvidenceId = importOperations[2].jsonObject.getValue("input").jsonObject.rg05OracleString("evidence_id")
    val allocationFailures = v1.getValue("allocation_failures").jsonArray

    val specs = mutableListOf<GoldenV2RootSpec>()
    specs += root(
        "rg05_manual_merged_payment",
        "$.manual_path",
        manualRequestId,
        listOf(
            GoldenV2OperationSpec(0, "$.manual_path", manualRequestId),
            GoldenV2OperationSpec(1, "$.idempotency.retried_inputs[*]", manualRequestId),
        ),
    )
    specs += root(
        "rg05_import_lifecycle",
        "$.import_path",
        bankSourceId,
        listOf(
            GoldenV2OperationSpec(2, "$.import_path.ordered_operations[*]", importOperationIds[0]),
            GoldenV2OperationSpec(3, "$.idempotency.retried_inputs[*]", bankSourceId),
            GoldenV2OperationSpec(4, "$.allocation_failures[*]", allocationFailures[0].jsonObject.rg05OracleString("id")),
            GoldenV2OperationSpec(5, "$.allocation_failures[*]", allocationFailures[1].jsonObject.rg05OracleString("id")),
            GoldenV2OperationSpec(6, "$.import_path.ordered_operations[*]", importOperationIds[1]),
            GoldenV2OperationSpec(7, "$.idempotency.retried_inputs[*]", confirmRequestId),
            GoldenV2OperationSpec(8, "$.import_path.ordered_operations[*]", importOperationIds[2]),
            GoldenV2OperationSpec(9, "$.idempotency.retried_inputs[*]", receiptEvidenceId),
        ),
    )
    v1.getValue("invalid_manual_inputs").jsonArray.forEachIndexed { index, element ->
        val id = element.jsonObject.rg05OracleString("id")
        specs += root(
            "rg05_invalid_${id.replace('-', '_')}",
            "$.invalid_manual_inputs[*]",
            id,
            listOf(
                GoldenV2OperationSpec(
                    10 + index,
                    "$.invalid_manual_inputs[*]",
                    id,
                    "$.invalid_manual_inputs[*].expected",
                ),
            ),
        )
    }
    return specs.sortedBy { it.rootId }
}

private fun executeRg05OracleRoot(
    spec: GoldenV2RootSpec,
    decoded: Rg05RawJsonCase,
    v1: JsonObject,
): GoldenV2ObservedRoot = executeGoldenV2Root(
    caseId = RG05_CASE_ID,
    spec = spec,
    ledgerId = decoded.ledgerId,
    v1 = v1,
    requestId = { operation -> rg05OracleRequestId(operation.index, decoded, v1) },
    createRuntime = { database, driver, operationIdsByRequest ->
        val store = SqlDelightRg05Store(
            database,
            driver,
            decoded.catalog,
            object : Rg05IdentitySource {
                override fun manual(requestId: RequestId): Rg05ManualCommitIds =
                    error("RG-05 oracle supplies contract-derived manual identities")
            },
        )
        val executor = ExecuteRg05Operation(store)
        val projector = Rg05StateProjector(database, driver, decoded, v1, spec, operationIdsByRequest)
        GoldenV2RootRuntime(
            projectState = projector::state,
            executeOperation = { operationSpec ->
                val result = executeRg05OracleOperation(operationSpec.index, decoded, v1, executor)
                GoldenV2OperationResult(rg05OracleOutcome(result), rg05OracleReturnedIds(result, decoded))
            },
        )
    },
)

private fun rg05OracleRequestId(index: Int, decoded: Rg05RawJsonCase, v1: JsonObject): String = when (index) {
    0, 1 -> (decoded.manual.requestId as Rg05Field.Value).value
    2, 3 -> (decoded.importOperations[0] as Rg05PreparedOperation.Ingest).snapshot.requestId.value
    4 -> rg05AllocationFailureRequestId(
        (decoded.importOperations[0] as Rg05PreparedOperation.Ingest).snapshot.bankFact.sourceId,
        v1.getValue("allocation_failures").jsonArray[0].jsonObject.rg05OracleString("id"),
    )
    5 -> rg05AllocationFailureRequestId(
        (decoded.importOperations[0] as Rg05PreparedOperation.Ingest).snapshot.bankFact.sourceId,
        v1.getValue("allocation_failures").jsonArray[1].jsonObject.rg05OracleString("id"),
    )
    6, 7 -> (decoded.importOperations[1] as Rg05PreparedOperation.Confirm).snapshot.requestId.value
    8, 9 -> (decoded.importOperations[2] as Rg05PreparedOperation.Receipt).snapshot.requestId.value
    else -> {
        val invalid = v1.getValue("invalid_manual_inputs").jsonArray[index - 10].jsonObject
        rg05InvalidManualRequestId(invalid.rg05OracleString("id"))
    }
}

private fun executeRg05OracleOperation(
    index: Int,
    decoded: Rg05RawJsonCase,
    v1: JsonObject,
    executor: ExecuteRg05Operation,
): Any = when (index) {
    0, 1 -> when (val adapted = adaptRg05Manual(decoded, decoded.manual, assertNotNull(decoded.manualIds))) {
        is Rg05AdaptResult.Success -> executor.execute(adapted.operation)
        is Rg05AdaptResult.Invalid -> adapted
    }
    2, 3 -> executor.execute(decoded.importOperations[0])
    4, 5 -> {
        val confirm = decoded.importOperations[1] as Rg05PreparedOperation.Confirm
        val failure = v1.getValue("allocation_failures").jsonArray[index - 4].jsonObject
        val requestId = rg05AllocationFailureRequestId(
            (decoded.importOperations[0] as Rg05PreparedOperation.Ingest).snapshot.bankFact.sourceId,
            failure.rg05OracleString("id"),
        )
        val secondAmount = if (index == 4) 5_000L else 7_000L
        executor.execute(rg05OracleReallocated(confirm, requestId, 4_000L, secondAmount))
    }
    6, 7 -> executor.execute(decoded.importOperations[1])
    8, 9 -> executor.execute(decoded.importOperations[2])
    else -> {
        val invalidIndex = index - 10
        val entry = v1.getValue("invalid_manual_inputs").jsonArray[invalidIndex].jsonObject
        when (val adapted = adaptRg05Manual(decoded, rg05OracleInvalidManualInput(entry), rg05OracleInvalidIds(invalidIndex))) {
            is Rg05AdaptResult.Success -> executor.execute(adapted.operation)
            is Rg05AdaptResult.Invalid -> adapted
        }
    }
}

private fun rg05OracleOutcome(result: Any): JsonObject = when (result) {
    is Rg05AdaptResult.Invalid -> rg05JsonObjectOf(
        "status" to JsonPrimitive("rejected"),
        "reason_code" to JsonPrimitive(result.reason),
        "field_path" to JsonPrimitive("$.attempted_input.${result.field}"),
    )
    is Rg05ExecutionResult.Rejected -> rg05JsonObjectOf(
        "status" to JsonPrimitive("rejected"),
        "reason_code" to JsonPrimitive(result.error.name.lowercase()),
        "field_path" to JsonPrimitive("$.attempted_input.${result.field}"),
    )
    is Rg05ExecutionResult.NoChange,
    is Rg05ExecutionResult.IngestNoChange,
    is Rg05ExecutionResult.ReceiptNoChange,
    -> rg05JsonObjectOf(
        "status" to JsonPrimitive("no_change"),
        "reason_code" to JsonPrimitive("idempotent_replay"),
    )
    is Rg05ExecutionResult.Accepted,
    is Rg05ExecutionResult.IngestAccepted,
    is Rg05ExecutionResult.ReceiptAccepted,
    -> rg05JsonObjectOf("status" to JsonPrimitive("accepted"))
    Rg05ExecutionResult.RequestIdentityConflict -> error("unexpected RG-05 request identity conflict")
    else -> error("unexpected RG-05 result $result")
}

private fun rg05OracleReturnedIds(result: Any, decoded: Rg05RawJsonCase): JsonArray {
    fun entry(kind: String, id: String) = rg05JsonObjectOf("kind" to JsonPrimitive(kind), "id" to JsonPrimitive(id))
    val entries = when (result) {
        is Rg05ExecutionResult.Accepted -> rg05OracleFormalReturnedIds(
            result.confirmationId,
            result.transactionId,
            result.relationId,
            decoded,
        )
        is Rg05ExecutionResult.NoChange -> rg05OracleFormalReturnedIds(
            result.confirmationId,
            result.transactionId,
            result.relationId,
            decoded,
        )
        is Rg05ExecutionResult.IngestAccepted ->
            result.sourceIds.map { "source" to it } +
                result.evidenceIds.map { "evidence" to it } +
                listOf("candidate" to result.candidateId)
        is Rg05ExecutionResult.IngestNoChange ->
            result.sourceIds.map { "source" to it } +
                result.evidenceIds.map { "evidence" to it } +
                listOf("candidate" to result.candidateId)
        is Rg05ExecutionResult.ReceiptAccepted -> listOf(
            "source" to result.sourceId,
            "evidence" to result.evidenceId,
            "evidence_link" to result.evidenceLinkId,
        )
        is Rg05ExecutionResult.ReceiptNoChange -> listOf(
            "source" to result.sourceId,
            "evidence" to result.evidenceId,
            "evidence_link" to result.evidenceLinkId,
        )
        else -> emptyList()
    }
    return JsonArray(entries.map { (kind, id) -> entry(kind, id) })
}

private fun rg05OracleFormalReturnedIds(
    confirmationId: String,
    transactionId: TransactionId,
    relationId: String,
    decoded: Rg05RawJsonCase,
): List<Pair<String, String>> {
    val confirm = decoded.importOperations.filterIsInstance<Rg05PreparedOperation.Confirm>().single()
    val imported = relationId == confirm.relationId
    val consumptionIds = if (imported) confirm.consumptionIds else assertNotNull(decoded.manualIds).consumptionIds
    val allocationIds = if (imported) confirm.allocationIds else assertNotNull(decoded.manualIds).allocationIds
    val candidate = if (imported) listOf("candidate" to confirm.snapshot.candidateId) else emptyList()
    val evidenceLinks = if (imported) {
        listOf("evidence_link" to confirm.bankEvidenceLinkId) +
            confirm.itemEvidenceLinkIds.values.map { "evidence_link" to it }
    } else {
        emptyList()
    }
    return candidate +
        listOf("confirmation" to confirmationId, "transaction" to transactionId.value) +
        consumptionIds.values.map { "domain_entity" to it } +
        allocationIds.values.map { "domain_entity" to it } +
        listOf("relation" to relationId) +
        evidenceLinks
}

private fun rg05OracleInvalidManualInput(entry: JsonObject): Rg05ManualInput {
    val input = entry.getValue("input").jsonObject
    return Rg05ManualInput(
        Rg05Field.Value(rg05InvalidManualRequestId(entry.rg05OracleString("id"))),
        Rg05Field.Value(RG05_PAYMENT_AT),
        Rg05Field.Value(input.rg05OracleString("total_amount")),
        Rg05Field.Value(input.rg05OracleString("currency")),
        Rg05Field.Value(input.rg05OracleString("funding_account_id")),
        input.getValue("items").jsonArray.map { element ->
            val item = element.jsonObject
            Rg05ItemInput(
                Rg05Field.Value(item.rg05OracleString("id")),
                Rg05Field.Value(item.rg05OracleString("amount")),
                Rg05Field.Value(item.rg05OracleString("currency")),
                when (val category = item["category_id"]) {
                    null, JsonNull -> Rg05Field.Null
                    else -> Rg05Field.Value(category.jsonPrimitive.content)
                },
                Rg05Field.Value(item.rg05OracleString("id")),
                Rg05Field.Value(if (item.rg05OracleString("id").endsWith("b")) RG05_ITEM_B_OBSERVED_AT else RG05_ITEM_A_OBSERVED_AT),
            )
        },
        Rg05Field.Value(true),
    )
}

private fun rg05OracleInvalidIds(index: Int): Rg05PreparedIds = Rg05PreparedIds(
    MergedPaymentExpenseIds(
        TransactionId("tx-rg05-oracle-invalid-$index"),
        TransactionVersionId("version-rg05-oracle-invalid-$index"),
        PostingSetId("posting-set-rg05-oracle-invalid-$index"),
        listOf(
            PostingId("posting-expense-a-rg05-oracle-invalid-$index"),
            PostingId("posting-expense-b-rg05-oracle-invalid-$index"),
        ),
        PostingId("posting-asset-rg05-oracle-invalid-$index"),
    ),
    "association-group-rg05-oracle-invalid-$index",
    "confirmation-rg05-oracle-invalid-$index",
    "reconciliation-rg05-oracle-invalid-$index",
    mapOf(
        "item-rg05-invalid-a" to "consumption-a-rg05-oracle-invalid-$index",
        "item-rg05-invalid-b" to "consumption-b-rg05-oracle-invalid-$index",
    ),
    mapOf(
        "item-rg05-invalid-a" to "allocation-a-rg05-oracle-invalid-$index",
        "item-rg05-invalid-b" to "allocation-b-rg05-oracle-invalid-$index",
    ),
)

private fun rg05OracleReallocated(
    confirm: Rg05PreparedOperation.Confirm,
    requestId: String,
    first: Long,
    second: Long,
): Rg05PreparedOperation.Confirm = confirm.copy(
    snapshot = confirm.snapshot.copy(
        requestId = RequestId(requestId),
        allocations = confirm.snapshot.allocations.mapIndexed { index, allocation ->
            allocation.copy(amount = Money.ofMinor(if (index == 0) first else second, allocation.amount.currency))
        },
    ),
)

private data class Rg05ProjectedTransaction(val id: String, val type: String, val currentVersionId: String)
private data class Rg05ProjectedVersion(
    val id: String,
    val transactionId: String,
    val versionNumber: Long,
    val postingSetId: String,
    val occurredAt: String,
    val statisticsAt: String,
    val effectiveAt: String,
    val confirmationId: String?,
)
private data class Rg05ProjectedPosting(
    val id: String,
    val postingSetId: String,
    val accountId: String,
    val amountMinor: Long,
    val currency: String,
    val precision: Long,
    val role: String?,
    val categoryId: String?,
    val reconciliationEligible: Long?,
)
private data class Rg05ProjectedSource(
    val id: String,
    val sourceType: String,
    val evidenceId: String,
    val itemId: String?,
    val evidenceKind: String,
    val observedAt: String,
    val details: String,
    val amountMinor: Long,
    val currency: String,
    val precision: Long,
    val suggestedCategoryId: String?,
    val completeness: String,
)
private data class Rg05ProjectedCandidate(
    val id: String,
    val bankSourceId: String,
    val paymentTotalMinor: Long,
    val currency: String,
    val precision: Long,
    val ruleName: String,
    val ruleVersion: Long,
    val confidence: String,
)
private data class Rg05ProjectedCandidateItem(
    val candidateId: String,
    val index: Long,
    val itemId: String,
    val sourceId: String,
    val evidenceId: String,
    val amountMinor: Long,
    val currency: String,
    val precision: Long,
    val categoryId: String,
)
private data class Rg05ProjectedCandidateStatus(
    val candidateId: String,
    val sequence: Long,
    val id: String,
    val status: String,
)
private data class Rg05ProjectedConfirmation(
    val id: String,
    val requestId: String,
    val candidateId: String?,
    val transactionId: String,
    val kind: String,
)
private data class Rg05ProjectedEvidence(val id: String, val sourceId: String, val type: String, val observedAt: String)
private data class Rg05ProjectedEvidenceLink(
    val id: String,
    val evidenceId: String,
    val targetKind: String,
    val targetId: String,
    val role: String,
)
private data class Rg05ProjectedRelation(
    val id: String,
    val transactionId: String,
    val paymentPostingId: String,
    val displayName: String,
    val paymentTotalMinor: Long,
    val currency: String,
    val precision: Long,
)
private data class Rg05ProjectedRelationItem(
    val relationId: String,
    val index: Long,
    val itemId: String,
    val completeness: String,
)
private data class Rg05ProjectedDomainItem(
    val relationId: String,
    val index: Long,
    val itemId: String,
    val amountMinor: Long,
    val currency: String,
    val precision: Long,
    val categoryId: String,
    val details: String,
    val sourceObservedAt: String,
    val statisticsAt: String,
    val consumptionId: String,
    val allocationId: String,
    val sourceId: String?,
    val evidenceId: String?,
    val expensePostingId: String,
)
private data class Rg05ProjectedReconciliation(val id: String, val postingId: String, val status: String)

private class Rg05StateProjector(
    private val database: LedgerDatabase,
    private val driver: JdbcSqliteDriver,
    private val decoded: Rg05RawJsonCase,
    private val v1: JsonObject,
    private val spec: GoldenV2RootSpec,
    private val operationIdsByRequest: Map<String, String>,
) {
    fun state(id: String, asOfOperationId: String?): JsonObject {
        val transactions = transactions()
        val versions = versions()
        val postingSets = postingSets()
        val postings = postings()
        val sources = sources()
        val candidates = candidates()
        val candidateItems = candidateItems()
        val candidateStatuses = candidateStatuses()
        val confirmations = confirmations()
        val evidence = evidence()
        val evidenceLinks = evidenceLinks()
        val relations = relations()
        val relationItems = relationItems()
        val domainItems = domainItems()
        val reconciliations = reconciliations()

        return rg05JsonObjectOf(
            "id" to JsonPrimitive(id),
            "root_id" to JsonPrimitive(spec.rootId),
            "as_of_operation_id" to (asOfOperationId?.let(::JsonPrimitive) ?: JsonNull),
            "catalog" to projectCatalog(),
            "transactions" to JsonArray(transactions.map { transaction ->
                rg05JsonObjectOf(
                    "id" to JsonPrimitive(transaction.id),
                    "type" to JsonPrimitive(transaction.type.lowercase()),
                    "current_version_id" to JsonPrimitive(transaction.currentVersionId),
                )
            }),
            "transaction_versions" to JsonArray(versions.map { version ->
                rg05JsonObjectOf(
                    "id" to JsonPrimitive(version.id),
                    "transaction_id" to JsonPrimitive(version.transactionId),
                    "version_number" to JsonPrimitive(version.versionNumber),
                    "posting_set_id" to JsonPrimitive(version.postingSetId),
                    "occurred_at" to JsonPrimitive(version.occurredAt),
                    "statistics_at" to JsonPrimitive(version.statisticsAt),
                    "effective_at" to JsonPrimitive(version.effectiveAt),
                    "confirmation_id" to version.confirmationId?.let(::JsonPrimitive),
                )
            }),
            "posting_sets" to JsonArray(postingSets.map { (setId, postingIds) ->
                rg05JsonObjectOf(
                    "id" to JsonPrimitive(setId),
                    "posting_ids" to JsonArray(postingIds.map(::JsonPrimitive)),
                )
            }),
            "postings" to JsonArray(postings.map { posting ->
                rg05JsonObjectOf(
                    "id" to JsonPrimitive(posting.id),
                    "posting_set_id" to JsonPrimitive(posting.postingSetId),
                    "account_id" to JsonPrimitive(posting.accountId),
                    "category_id" to posting.categoryId?.let(::JsonPrimitive),
                    "amount" to JsonPrimitive(rg05OracleAmount(posting.amountMinor, posting.precision)),
                    "currency" to JsonPrimitive(posting.currency),
                    "role" to posting.role?.let(::JsonPrimitive),
                    "reconciliation_eligible" to JsonPrimitive(posting.reconciliationEligible == 1L),
                )
            }),
            "sources" to JsonArray(sources.map(::projectSource)),
            "candidates" to JsonArray(candidates.map { candidate ->
                projectCandidate(candidate, candidateItems, candidateStatuses, confirmations)
            }),
            "confirmations" to JsonArray(confirmations.map(::projectConfirmation)),
            "evidence" to JsonArray(evidence.map { item ->
                rg05JsonObjectOf(
                    "id" to JsonPrimitive(item.id),
                    "type" to JsonPrimitive(item.type.lowercase()),
                    "source_ids" to JsonArray(listOf(JsonPrimitive(item.sourceId))),
                    "payload" to rg05JsonObjectOf("observed_at" to JsonPrimitive(item.observedAt)),
                )
            }),
            "evidence_links" to JsonArray(evidenceLinks.map { link ->
                rg05JsonObjectOf(
                    "id" to JsonPrimitive(link.id),
                    "evidence_id" to JsonPrimitive(link.evidenceId),
                    "target_kind" to JsonPrimitive(if (link.targetKind == "POSTING") "posting" else "domain_entity"),
                    "target_id" to JsonPrimitive(link.targetId),
                    "role" to JsonPrimitive(link.role.lowercase()),
                )
            }),
            "relations" to JsonArray(relations.map { relation -> projectRelation(relation, domainItems) }),
            "domain_entities" to projectDomainEntities(domainItems),
            "audit_links" to JsonArray(emptyList()),
            "posting_reconciliations" to JsonArray(reconciliations.map { reconciliation ->
                rg05JsonObjectOf(
                    "id" to JsonPrimitive(reconciliation.id),
                    "posting_id" to JsonPrimitive(reconciliation.postingId),
                    "status" to JsonPrimitive(reconciliation.status.lowercase()),
                )
            }),
            "balances" to projectBalances(postings),
            "reports" to projectReports(transactions, versions, postings),
            "derived_statuses" to projectDerivedStatuses(
                transactions,
                versions,
                postings,
                candidateStatuses,
                confirmations,
                relations,
                relationItems,
                reconciliations,
            ),
        )
    }

    private fun projectCatalog(): JsonObject {
        val rawCatalog = v1.getValue("catalog").jsonObject
        val accountsById = decoded.catalog.accounts.associateBy { it.id.value }
        val categoriesById = decoded.catalog.categories.associateBy { it.id.value }
        return rg05JsonObjectOf(
            "accounts" to JsonArray(rawCatalog.getValue("accounts").jsonArray.map { element ->
                val raw = element.jsonObject
                val account = accountsById.getValue(raw.rg05OracleString("id"))
                rg05JsonObjectOf(
                    "id" to JsonPrimitive(account.id.value),
                    "name" to JsonPrimitive(raw.rg05OracleString("name")),
                    "kind" to JsonPrimitive(account.kind.name.lowercase()),
                    "currency" to JsonPrimitive(account.currency.code),
                    "owned_by_user" to JsonPrimitive(account.ownedByUser),
                    "real_account" to JsonPrimitive(account.realAccount),
                    "reconciliation_eligible" to JsonPrimitive(account.ownedByUser && account.realAccount),
                )
            }),
            "categories" to JsonArray(rawCatalog.getValue("categories").jsonArray.map { element ->
                val raw = element.jsonObject
                val category = categoriesById.getValue(raw.rg05OracleString("id"))
                rg05JsonObjectOf(
                    "id" to JsonPrimitive(category.id.value),
                    "name" to JsonPrimitive(raw.rg05OracleString("name")),
                    "parent_id" to (category.parentId?.value?.let(::JsonPrimitive) ?: JsonNull),
                    "posting_account_id" to (category.postingAccountId?.value?.let(::JsonPrimitive) ?: JsonNull),
                    "active" to JsonPrimitive(category.active),
                )
            }),
        )
    }

    private fun projectSource(source: Rg05ProjectedSource): JsonObject = rg05JsonObjectOf(
        "id" to JsonPrimitive(source.id),
        "type" to JsonPrimitive(
            if (source.sourceType == "BANK_FACT") "merged_payment_bank_fact" else "merged_payment_item_fact",
        ),
        "payload" to rg05JsonObjectOf(
            "item_id" to source.itemId?.let(::JsonPrimitive),
            "evidence_id" to JsonPrimitive(source.evidenceId),
            "evidence_kind" to if (source.sourceType == "ITEM_FACT") JsonPrimitive(source.evidenceKind.lowercase()) else null,
            "observed_at" to JsonPrimitive(source.observedAt),
            "details" to JsonPrimitive(source.details),
            "amount" to JsonPrimitive(rg05OracleAmount(source.amountMinor, source.precision)),
            "currency" to JsonPrimitive(source.currency),
            "suggested_category_id" to source.suggestedCategoryId?.let(::JsonPrimitive),
            "completeness" to JsonPrimitive(source.completeness.lowercase()),
        ),
    )

    private fun projectCandidate(
        candidate: Rg05ProjectedCandidate,
        items: List<Rg05ProjectedCandidateItem>,
        statuses: List<Rg05ProjectedCandidateStatus>,
        confirmations: List<Rg05ProjectedConfirmation>,
    ): JsonObject {
        val candidateItems = items.filter { it.candidateId == candidate.id }.sortedBy { it.index }
        val sourceIds = listOf(candidate.bankSourceId) + candidateItems.map { it.sourceId }
        val bankEvidenceId = sources().single { it.id == candidate.bankSourceId }.evidenceId
        return rg05JsonObjectOf(
            "id" to JsonPrimitive(candidate.id),
            "type" to JsonPrimitive("merged_payment"),
            "source_ids" to JsonArray(sourceIds.map(::JsonPrimitive)),
            "confidence" to JsonPrimitive(candidate.confidence),
            "payload" to rg05JsonObjectOf(
                "payment_total" to JsonPrimitive(rg05OracleAmount(candidate.paymentTotalMinor, candidate.precision)),
                "currency" to JsonPrimitive(candidate.currency),
                "bank_source_id" to JsonPrimitive(candidate.bankSourceId),
                "item_source_ids" to JsonArray(candidateItems.map { JsonPrimitive(it.sourceId) }),
                "item_proposals" to JsonArray(candidateItems.map { item ->
                    rg05JsonObjectOf(
                        "item_id" to JsonPrimitive(item.itemId),
                        "amount" to JsonPrimitive(rg05OracleAmount(item.amountMinor, item.precision)),
                        "currency" to JsonPrimitive(item.currency),
                        "suggested_category_id" to JsonPrimitive(item.categoryId),
                        "source_id" to JsonPrimitive(item.sourceId),
                        "evidence_id" to JsonPrimitive(item.evidenceId),
                    )
                }),
                "evidence_refs" to JsonArray(
                    (listOf(bankEvidenceId) + candidateItems.map { it.evidenceId }).map(::JsonPrimitive),
                ),
                "provenance" to rg05JsonObjectOf(
                    "rule" to JsonPrimitive(candidate.ruleName),
                    "rule_version" to JsonPrimitive(candidate.ruleVersion),
                ),
                "requires_confirmation" to JsonArray(
                    listOf(
                        "funding_account_id",
                        "secondary_categories",
                        "allocation_closure",
                        "formal_transaction_creation",
                    ).map(::JsonPrimitive),
                ),
                "transaction_id" to confirmations.singleOrNull { it.candidateId == candidate.id }
                    ?.transactionId?.let(::JsonPrimitive),
            ),
            "status_history" to JsonArray(
                statuses.filter { it.candidateId == candidate.id }.sortedBy { it.sequence }.map { status ->
                    rg05JsonObjectOf(
                        "id" to JsonPrimitive(status.id),
                        "sequence" to JsonPrimitive(status.sequence),
                        "status" to JsonPrimitive(status.status.lowercase()),
                    )
                },
            ),
        )
    }

    private fun projectConfirmation(confirmation: Rg05ProjectedConfirmation): JsonObject {
        val operationId = operationIdsByRequest.getValue(confirmation.requestId)
        val (type, subjectKind, subjectId) = when {
            confirmation.kind == "EXPLICIT_MANUAL_SAVE" && confirmation.candidateId == null ->
                Triple("explicit_manual_save", "operation", operationId)
            confirmation.kind == "CANDIDATE_CONFIRMATION" && confirmation.candidateId != null ->
                Triple("candidate_confirmation", "candidate", confirmation.candidateId)
            else -> error("Unsupported RG-05 confirmation shape")
        }
        return rg05JsonObjectOf(
            "id" to JsonPrimitive(confirmation.id),
            "type" to JsonPrimitive(type),
            "operation_id" to JsonPrimitive(operationId),
            "subject" to rg05JsonObjectOf(
                "kind" to JsonPrimitive(subjectKind),
                "id" to JsonPrimitive(subjectId),
            ),
            "payload" to JsonObject(emptyMap()),
        )
    }

    private fun projectRelation(
        relation: Rg05ProjectedRelation,
        items: List<Rg05ProjectedDomainItem>,
    ): JsonObject {
        val relationItems = items.filter { it.relationId == relation.id }.sortedBy { it.index }
        return rg05JsonObjectOf(
            "id" to JsonPrimitive(relation.id),
            "type" to JsonPrimitive("merged_payment"),
            "member_refs" to JsonArray(
                listOf(
                    rg05JsonObjectOf("kind" to JsonPrimitive("transaction"), "id" to JsonPrimitive(relation.transactionId)),
                    rg05JsonObjectOf("kind" to JsonPrimitive("posting"), "id" to JsonPrimitive(relation.paymentPostingId)),
                ) + relationItems.map { item ->
                    rg05JsonObjectOf("kind" to JsonPrimitive("domain_entity"), "id" to JsonPrimitive(item.allocationId))
                },
            ),
            "payload" to rg05JsonObjectOf(
                "system_managed" to JsonPrimitive(true),
                "display_name" to JsonPrimitive(relation.displayName),
                "generic_order_lifecycle" to JsonPrimitive(false),
                "payment_total" to JsonPrimitive(rg05OracleAmount(relation.paymentTotalMinor, relation.precision)),
                "currency" to JsonPrimitive(relation.currency),
            ),
        )
    }

    private fun projectDomainEntities(items: List<Rg05ProjectedDomainItem>): JsonArray {
        val ordered = items.sortedWith(compareBy({ it.relationId }, { it.index }))
        val consumptions = ordered.map { item ->
            rg05JsonObjectOf(
                "id" to JsonPrimitive(item.consumptionId),
                "type" to JsonPrimitive("consumption_record"),
                "payload" to rg05JsonObjectOf(
                    "expense_posting_id" to JsonPrimitive(item.expensePostingId),
                    "category_id" to JsonPrimitive(item.categoryId),
                    "amount" to JsonPrimitive(rg05OracleAmount(item.amountMinor, item.precision)),
                    "currency" to JsonPrimitive(item.currency),
                    "statistics_at" to JsonPrimitive(item.statisticsAt),
                    "details" to JsonPrimitive(item.details),
                    "source_observed_at" to JsonPrimitive(rg05OracleCaseTime(item.sourceObservedAt)),
                    "source_item_id" to item.sourceId?.let { JsonPrimitive(item.itemId) },
                    "source_id" to item.sourceId?.let(::JsonPrimitive),
                    "evidence_id" to item.evidenceId?.let(::JsonPrimitive),
                ),
            )
        }
        val allocations = ordered.map { item ->
            rg05JsonObjectOf(
                "id" to JsonPrimitive(item.allocationId),
                "type" to JsonPrimitive("item_allocation"),
                "payload" to rg05JsonObjectOf(
                    "consumption_record_id" to JsonPrimitive(item.consumptionId),
                    "expense_posting_id" to JsonPrimitive(item.expensePostingId),
                    "category_id" to JsonPrimitive(item.categoryId),
                    "amount" to JsonPrimitive(rg05OracleAmount(item.amountMinor, item.precision)),
                    "currency" to JsonPrimitive(item.currency),
                    "source_item_id" to item.sourceId?.let { JsonPrimitive(item.itemId) },
                    "source_id" to item.sourceId?.let(::JsonPrimitive),
                    "evidence_id" to item.evidenceId?.let(::JsonPrimitive),
                ),
            )
        }
        return JsonArray(consumptions + allocations)
    }

    private fun projectBalances(postings: List<Rg05ProjectedPosting>): JsonArray = JsonArray(
        decoded.catalog.accounts.map { account ->
            val amount = postings.filter { it.accountId == account.id.value }.sumOf { it.amountMinor }
            rg05JsonObjectOf(
                "account_id" to JsonPrimitive(account.id.value),
                "currency" to JsonPrimitive(account.currency.code),
                "amount" to JsonPrimitive(rg05OracleAmount(amount, account.currency.precision.toLong())),
            )
        },
    )

    private fun projectReports(
        transactions: List<Rg05ProjectedTransaction>,
        versions: List<Rg05ProjectedVersion>,
        postings: List<Rg05ProjectedPosting>,
    ): JsonArray {
        val periods = when (spec.purpose) {
            "rg05_manual_merged_payment" -> {
                val day = v1.getValue("manual_path").jsonObject.getValue("input").jsonObject
                    .rg05OracleString("payment_at").substring(0, 10)
                listOf("day" to day, "month" to day.substring(0, 7), "cumulative" to "lifecycle")
            }
            "rg05_import_lifecycle" -> {
                val confirm = v1.getValue("import_path").jsonObject.getValue("ordered_operations").jsonArray[1]
                    .jsonObject.getValue("input").jsonObject
                val day = confirm.rg05OracleString("common_statistics_at").substring(0, 10)
                listOf("day" to day, "month" to day.substring(0, 7), "cumulative" to "lifecycle")
            }
            else -> listOf("cumulative" to "lifecycle")
        }
        return JsonArray(periods.map { (periodType, period) ->
            val matchingVersions = versions.filter { version ->
                transactions.any { it.type == "EXPENSE" && it.currentVersionId == version.id } &&
                    when (periodType) {
                        "day" -> version.statisticsAt.startsWith(period)
                        "month" -> version.statisticsAt.startsWith(period)
                        else -> true
                    }
            }
            val selectedPostingSets = matchingVersions.map { it.postingSetId }.toSet()
            val selected = postings.filter { it.postingSetId in selectedPostingSets }
            val expense = selected.filter { it.role == "expense" }.sumOf { it.amountMinor }
            val cashOutflow = -selected.filter { it.role == "payment_asset" && it.amountMinor < 0 }.sumOf { it.amountMinor }
            val metrics = listOf(
                "balance_adjustment_net_worth_change" to 0L,
                "budget" to null,
                "cash_inflow" to 0L,
                "cash_outflow" to cashOutflow,
                "consumption" to expense,
                "income" to 0L,
                "internal_transfer_amount" to 0L,
                "net_worth_change" to -expense,
                "ordinary_expense" to expense,
                "ordinary_income" to 0L,
            )
            rg05JsonObjectOf(
                "period_type" to JsonPrimitive(periodType),
                "period" to JsonPrimitive(period),
                "metrics" to JsonArray(metrics.map { (metric, amount) ->
                    if (amount == null) {
                        rg05JsonObjectOf(
                            "metric" to JsonPrimitive(metric),
                            "applicability" to JsonPrimitive("not_applicable"),
                        )
                    } else {
                        rg05JsonObjectOf(
                            "metric" to JsonPrimitive(metric),
                            "applicability" to JsonPrimitive("applicable"),
                            "currency" to JsonPrimitive(decoded.currency.code),
                            "amount" to JsonPrimitive(rg05OracleAmount(amount, decoded.currency.precision.toLong())),
                        )
                    }
                }),
            )
        })
    }

    private fun projectDerivedStatuses(
        transactions: List<Rg05ProjectedTransaction>,
        versions: List<Rg05ProjectedVersion>,
        postings: List<Rg05ProjectedPosting>,
        candidateStatuses: List<Rg05ProjectedCandidateStatus>,
        confirmations: List<Rg05ProjectedConfirmation>,
        relations: List<Rg05ProjectedRelation>,
        relationItems: List<Rg05ProjectedRelationItem>,
        reconciliations: List<Rg05ProjectedReconciliation>,
    ): JsonArray {
        val values = mutableListOf<JsonObject>()
        candidateStatuses.groupBy { it.candidateId }.forEach { (candidateId, history) ->
            val latest = history.maxBy { it.sequence }
            values += rg05JsonObjectOf(
                "id" to JsonPrimitive(
                    goldenV2MigrationId(
                        RG05_CASE_ID,
                        spec.rootId,
                        "derived_status",
                        "$.import_path.ordered_operations[*].expected.candidate.status",
                        candidateId,
                    ),
                ),
                "target_kind" to JsonPrimitive("candidate"),
                "target_id" to JsonPrimitive(candidateId),
                "status_name" to JsonPrimitive("confirmation_status"),
                "value" to JsonPrimitive(latest.status.lowercase()),
            )
        }
        transactions.filter { it.type == "EXPENSE" }.forEach { transaction ->
            val currentVersion = versions.single { it.id == transaction.currentVersionId }
            val eligible = postings.filter {
                it.postingSetId == currentVersion.postingSetId && it.reconciliationEligible == 1L
            }.map { it.id }.toSet()
            val relevant = reconciliations.filter { it.postingId in eligible }
            val value = when {
                relevant.size == eligible.size && relevant.all { it.status == "MATCHED" } -> "matched"
                relevant.any { it.status == "MATCHED" } -> "partial"
                else -> "pending"
            }
            val confirmation = confirmations.single { it.transactionId == transaction.id }
            val locator = if (confirmation.candidateId == null) {
                "$.manual_path.expected.reconciliation"
            } else {
                "$.import_path.ordered_operations[*].expected.reconciliation"
            }
            values += rg05JsonObjectOf(
                "id" to JsonPrimitive(
                    goldenV2MigrationId(RG05_CASE_ID, spec.rootId, "derived_status", locator, transaction.id),
                ),
                "target_kind" to JsonPrimitive("transaction"),
                "target_id" to JsonPrimitive(transaction.id),
                "status_name" to JsonPrimitive("reconciliation_summary"),
                "value" to JsonPrimitive(value),
            )
        }
        relations.forEach { relation ->
            val completeness = relationItems.filter { it.relationId == relation.id }.map { it.completeness }
            val value = when {
                completeness.all { it == "COMPLETE" } -> "complete"
                completeness.all { it == "NONE" } -> "none"
                else -> "partial"
            }
            val manual = confirmations.single { it.transactionId == relation.transactionId }.candidateId == null
            val locator = if (manual) {
                "$.manual_path.expected.item_evidence_completeness"
            } else {
                "$.import_path.ordered_operations[*].expected.item_evidence_completeness"
            }
            values += rg05JsonObjectOf(
                "id" to JsonPrimitive(
                    goldenV2MigrationId(RG05_CASE_ID, spec.rootId, "derived_status", locator, relation.id),
                ),
                "target_kind" to JsonPrimitive("relation"),
                "target_id" to JsonPrimitive(relation.id),
                "status_name" to JsonPrimitive("item_evidence_completeness"),
                "value" to JsonPrimitive(value),
            )
        }
        return JsonArray(values)
    }

    private fun transactions(): List<Rg05ProjectedTransaction> = driver.rg05OracleRows(
        """
        SELECT tx.transaction_id, tx.kind, current.current_version_id
        FROM ledger_transaction AS tx
        JOIN ledger_transaction_current_version AS current
          ON current.ledger_id = tx.ledger_id AND current.transaction_id = tx.transaction_id
        WHERE tx.ledger_id = '$RG05_LEDGER_ID'
        ORDER BY tx.rowid
        """.trimIndent(),
    ) { cursor ->
        Rg05ProjectedTransaction(cursor.string(0), cursor.string(1), cursor.string(2))
    }

    private fun versions(): List<Rg05ProjectedVersion> = driver.rg05OracleRows(
        """
        SELECT version.version_id, version.transaction_id, version.version_number, version.posting_set_id,
               version.occurred_at, version.statistics_at, version.effective_at, confirmation.confirmation_id
        FROM transaction_version AS version
        LEFT JOIN rg05_confirmation AS confirmation
          ON confirmation.ledger_id = version.ledger_id AND confirmation.transaction_id = version.transaction_id
        WHERE version.ledger_id = '$RG05_LEDGER_ID'
        ORDER BY version.rowid
        """.trimIndent(),
    ) { cursor ->
        Rg05ProjectedVersion(
            cursor.string(0),
            cursor.string(1),
            cursor.long(2),
            cursor.string(3),
            cursor.string(4),
            cursor.string(5),
            cursor.string(6),
            cursor.getString(7),
        )
    }

    private fun postingSets(): List<Pair<String, List<String>>> {
        val rows = driver.rg05OracleRows(
            """
            SELECT posting_set.posting_set_id, posting.posting_id
            FROM posting_set
            JOIN posting ON posting.ledger_id = posting_set.ledger_id
              AND posting.posting_set_id = posting_set.posting_set_id
            WHERE posting_set.ledger_id = '$RG05_LEDGER_ID'
            ORDER BY posting_set.rowid, posting.posting_index
            """.trimIndent(),
        ) { cursor -> cursor.string(0) to cursor.string(1) }
        return rows.groupBy({ it.first }, { it.second }).map { it.key to it.value }
    }

    private fun postings(): List<Rg05ProjectedPosting> = driver.rg05OracleRows(
        """
        SELECT posting.posting_id, posting.posting_set_id, posting.account_id, posting.amount_minor,
               posting.currency_code, posting.currency_precision, semantic.role, semantic.category_id,
               semantic.reconciliation_eligible
        FROM posting
        JOIN posting_set ON posting_set.ledger_id = posting.ledger_id
          AND posting_set.posting_set_id = posting.posting_set_id
        LEFT JOIN rg05_posting_semantic AS semantic
          ON semantic.ledger_id = posting.ledger_id AND semantic.posting_id = posting.posting_id
        WHERE posting.ledger_id = '$RG05_LEDGER_ID'
        ORDER BY posting_set.rowid, posting.posting_index
        """.trimIndent(),
    ) { cursor ->
        Rg05ProjectedPosting(
            cursor.string(0),
            cursor.string(1),
            cursor.string(2),
            cursor.long(3),
            cursor.string(4),
            cursor.long(5),
            cursor.getString(6),
            cursor.getString(7),
            cursor.getLong(8),
        )
    }

    private fun sources(): List<Rg05ProjectedSource> = driver.rg05OracleRows(
        """
        SELECT source_id, source_type, evidence_id, item_id, evidence_kind, observed_at, details,
               amount_minor, currency_code, currency_precision, suggested_category_id, completeness
        FROM rg05_source_record
        WHERE ledger_id = '$RG05_LEDGER_ID'
        ORDER BY rowid
        """.trimIndent(),
    ) { cursor ->
        Rg05ProjectedSource(
            cursor.string(0),
            cursor.string(1),
            cursor.string(2),
            cursor.getString(3),
            cursor.string(4),
            cursor.string(5),
            cursor.string(6),
            cursor.long(7),
            cursor.string(8),
            cursor.long(9),
            cursor.getString(10),
            cursor.string(11),
        )
    }

    private fun candidates(): List<Rg05ProjectedCandidate> = driver.rg05OracleRows(
        """
        SELECT candidate_id, bank_source_id, payment_total_minor, currency_code, currency_precision,
               rule_name, rule_version, confidence
        FROM rg05_candidate
        WHERE ledger_id = '$RG05_LEDGER_ID'
        ORDER BY rowid
        """.trimIndent(),
    ) { cursor ->
        Rg05ProjectedCandidate(
            cursor.string(0),
            cursor.string(1),
            cursor.long(2),
            cursor.string(3),
            cursor.long(4),
            cursor.string(5),
            cursor.long(6),
            cursor.string(7),
        )
    }

    private fun candidateItems(): List<Rg05ProjectedCandidateItem> = driver.rg05OracleRows(
        """
        SELECT candidate_id, item_index, item_id, source_id, evidence_id, amount_minor,
               currency_code, currency_precision, suggested_category_id
        FROM rg05_candidate_item
        WHERE ledger_id = '$RG05_LEDGER_ID'
        ORDER BY candidate_id, item_index
        """.trimIndent(),
    ) { cursor ->
        Rg05ProjectedCandidateItem(
            cursor.string(0),
            cursor.long(1),
            cursor.string(2),
            cursor.string(3),
            cursor.string(4),
            cursor.long(5),
            cursor.string(6),
            cursor.long(7),
            cursor.string(8),
        )
    }

    private fun candidateStatuses(): List<Rg05ProjectedCandidateStatus> = driver.rg05OracleRows(
        """
        SELECT candidate_id, status_sequence, status_id, status
        FROM rg05_candidate_status_history
        WHERE ledger_id = '$RG05_LEDGER_ID'
        ORDER BY candidate_id, status_sequence
        """.trimIndent(),
    ) { cursor ->
        Rg05ProjectedCandidateStatus(cursor.string(0), cursor.long(1), cursor.string(2), cursor.string(3))
    }

    private fun confirmations(): List<Rg05ProjectedConfirmation> = driver.rg05OracleRows(
        """
        SELECT confirmation_id, request_id, candidate_id, transaction_id, confirmation_kind
        FROM rg05_confirmation
        WHERE ledger_id = '$RG05_LEDGER_ID'
        ORDER BY rowid
        """.trimIndent(),
    ) { cursor ->
        Rg05ProjectedConfirmation(
            cursor.string(0),
            cursor.string(1),
            cursor.getString(2),
            cursor.string(3),
            cursor.string(4),
        )
    }

    private fun evidence(): List<Rg05ProjectedEvidence> = driver.rg05OracleRows(
        """
        SELECT evidence_id, source_id, evidence_type, observed_at
        FROM rg05_evidence
        WHERE ledger_id = '$RG05_LEDGER_ID'
        ORDER BY rowid
        """.trimIndent(),
    ) { cursor -> Rg05ProjectedEvidence(cursor.string(0), cursor.string(1), cursor.string(2), cursor.string(3)) }

    private fun evidenceLinks(): List<Rg05ProjectedEvidenceLink> = driver.rg05OracleRows(
        """
        SELECT link_id, evidence_id, target_kind, target_id, role
        FROM rg05_evidence_link
        WHERE ledger_id = '$RG05_LEDGER_ID'
        ORDER BY rowid
        """.trimIndent(),
    ) { cursor ->
        Rg05ProjectedEvidenceLink(
            cursor.string(0),
            cursor.string(1),
            cursor.string(2),
            cursor.string(3),
            cursor.string(4),
        )
    }

    private fun relations(): List<Rg05ProjectedRelation> = driver.rg05OracleRows(
        """
        SELECT relation_id, transaction_id, payment_posting_id, display_name, payment_total_minor,
               currency_code, currency_precision
        FROM rg05_relation
        WHERE ledger_id = '$RG05_LEDGER_ID'
        ORDER BY rowid
        """.trimIndent(),
    ) { cursor ->
        Rg05ProjectedRelation(
            cursor.string(0),
            cursor.string(1),
            cursor.string(2),
            cursor.string(3),
            cursor.long(4),
            cursor.string(5),
            cursor.long(6),
        )
    }

    private fun relationItems(): List<Rg05ProjectedRelationItem> = driver.rg05OracleRows(
        """
        SELECT relation_id, item_index, item_id, business_evidence_completeness
        FROM rg05_relation_item
        WHERE ledger_id = '$RG05_LEDGER_ID'
        ORDER BY relation_id, item_index
        """.trimIndent(),
    ) { cursor ->
        Rg05ProjectedRelationItem(cursor.string(0), cursor.long(1), cursor.string(2), cursor.string(3))
    }

    private fun domainItems(): List<Rg05ProjectedDomainItem> = driver.rg05OracleRows(
        """
        SELECT payment.relation_id, item.item_index, item.item_id, item.amount_minor, item.currency_code,
               item.currency_precision, item.category_id, item.details, item.source_observed_at,
               payment.statistics_at, item.consumption_id, item.allocation_id, item.source_id,
               item.evidence_id, item.expense_posting_id
        FROM rg05_item_snapshot AS item
        JOIN rg05_merged_payment_snapshot AS payment
          ON payment.ledger_id = item.ledger_id AND payment.request_id = item.request_id
        WHERE item.ledger_id = '$RG05_LEDGER_ID'
        ORDER BY payment.rowid, item.item_index
        """.trimIndent(),
    ) { cursor ->
        Rg05ProjectedDomainItem(
            cursor.string(0),
            cursor.long(1),
            cursor.string(2),
            cursor.long(3),
            cursor.string(4),
            cursor.long(5),
            cursor.string(6),
            cursor.string(7),
            cursor.string(8),
            cursor.string(9),
            cursor.string(10),
            cursor.string(11),
            cursor.getString(12),
            cursor.getString(13),
            cursor.string(14),
        )
    }

    private fun reconciliations(): List<Rg05ProjectedReconciliation> = driver.rg05OracleRows(
        """
        SELECT reconciliation_id, posting_id, status
        FROM rg05_posting_reconciliation
        WHERE ledger_id = '$RG05_LEDGER_ID'
        ORDER BY rowid
        """.trimIndent(),
    ) { cursor -> Rg05ProjectedReconciliation(cursor.string(0), cursor.string(1), cursor.string(2)) }
}

private fun <T> JdbcSqliteDriver.rg05OracleRows(sql: String, mapper: (SqlCursor) -> T): List<T> =
    executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            val rows = mutableListOf<T>()
            while (cursor.next().value) rows += mapper(cursor)
            QueryResult.Value(rows)
        },
        parameters = 0,
    ).value

private fun SqlCursor.string(index: Int): String = requireNotNull(getString(index))
private fun SqlCursor.long(index: Int): Long = requireNotNull(getLong(index))

private fun rg05OracleAmount(minor: Long, precision: Long): String =
    BigDecimal.valueOf(minor, precision.toInt()).setScale(precision.toInt()).toPlainString()

private fun rg05OracleCaseTime(value: String): String =
    OffsetDateTime.parse(value)
        .withOffsetSameInstant(ZoneOffset.ofHours(8))
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))

private fun rg05JsonObjectOf(vararg fields: Pair<String, JsonElement?>): JsonObject =
    JsonObject(fields.mapNotNull { (key, value) -> value?.let { key to it } }.toMap())

private fun JsonObject.rg05OracleString(key: String): String = getValue(key).jsonPrimitive.content

private fun rg05OracleRepositoryFile(relative: String): Path {
    var candidate = Path.of(System.getProperty("user.dir"))
    repeat(6) {
        if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)
        candidate = candidate.parent ?: error("repository root not found")
    }
    error("repository root not found")
}

private const val RG05_CASE_ID = "RG-05"
private const val RG05_LEDGER_ID = "ledger-a"
private const val RG05_OPENING_LOCATOR = "$.opening.transactions[*]"
private const val RG05_OPENING_TRANSACTION_ID = "tx-opening-rg05"
private const val RG05_PAYMENT_AT = "2026-04-10T18:30:00+08:00"
private const val RG05_ITEM_A_OBSERVED_AT = "2026-04-08T10:00:00+08:00"
private const val RG05_ITEM_B_OBSERVED_AT = "2026-04-09T15:00:00+08:00"
