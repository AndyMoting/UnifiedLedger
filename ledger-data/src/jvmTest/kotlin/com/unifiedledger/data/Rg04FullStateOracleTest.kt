package com.unifiedledger.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.*
import com.unifiedledger.data.db.LedgerDatabase
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.math.absoluteValue

class Rg04FullStateOracleTest {
    @Test
    fun `v1 independently executes 17 roots before v2 validates all operations states and deltas`() {
        val raw = Files.readString(rg04FullStateRepositoryFile("golden/rules/rg-04.json"))
        val v1 = Json.parseToJsonElement(raw).jsonObject
        val decoded = assertIs<Rg04RawJsonDecodeResult.Success>(decodeRg04RawJson(raw)).value
        val observed = rg04FullStateRootSpecs(v1).map { spec ->
            executeRg04FullStateRoot(spec, decoded, v1)
        }
        val expected = Json.parseToJsonElement(
            Files.readString(rg04FullStateRepositoryFile("golden/rules-v2/rg-04.json")),
        ).jsonObject

        assertRg04OperationContracts(observed, expected, v1, decoded)
        assertGoldenV2Oracle(
            observed,
            expected,
            expectedRootCount = 17,
            expectedOperationCount = 26,
            expectedStateCount = 43,
        )
    }
}

private data class Rg04OperationContract(
    val operationClass: String,
    val actionType: String,
    val input: JsonObject?,
    val attemptedInput: JsonObject?,
)

private fun assertRg04OperationContracts(
    observed: List<GoldenV2ObservedRoot>,
    expected: JsonObject,
    v1: JsonObject,
    decoded: Rg04RawJsonCase,
) {
    val expectedRoots = expected.getValue("roots").jsonArray.associateBy { it.jsonObject.rg04String("purpose") }
    val expectedOperations = expected.getValue("operations").jsonArray.associateBy { it.jsonObject.rg04String("id") }

    observed.forEach { root ->
        val expectedRoot = expectedRoots.getValue(root.spec.purpose).jsonObject
        root.operations.forEachIndexed { position, actual ->
            val spec = root.spec.operations[position]
            val operation = expectedOperations.getValue(actual.operationId)
            val contract = rg04OperationContract(root.spec.purpose, spec, decoded, v1)
            val expectedBaseline = if (position == 0) {
                root.spec.initialStateId
            } else {
                val previous = root.spec.operations[position - 1]
                goldenV2MigrationId(
                    RG04_CASE_ID,
                    root.spec.rootId,
                    "state",
                    previous.stateLocator,
                    previous.discriminator,
                )
            }
            val expectedResult = goldenV2MigrationId(
                RG04_CASE_ID,
                root.spec.rootId,
                "state",
                spec.stateLocator,
                spec.discriminator,
            )

            assertEquals(root.spec.rootId, operation.jsonObject.rg04String("root_id"), "${actual.operationId} root_id")
            assertEquals(position + 1, operation.jsonObject.getValue("sequence").jsonPrimitive.int, "${actual.operationId} sequence")
            assertEquals(contract.operationClass, operation.jsonObject.rg04String("operation_class"), "${actual.operationId} operation_class")
            assertEquals(contract.actionType, operation.jsonObject.rg04String("action_type"), "${actual.operationId} action_type")
            assertEquals(expectedBaseline, operation.jsonObject.rg04String("baseline_state_id"), "${actual.operationId} baseline_state_id")
            assertEquals(expectedResult, operation.jsonObject.rg04String("result_state_id"), "${actual.operationId} result_state_id")
            assertRg04JsonFields(operation.jsonObject["input"], contract.input, "${actual.operationId}.input")
            assertRg04JsonFields(operation.jsonObject["attempted_input"], contract.attemptedInput, "${actual.operationId}.attempted_input")

            assertEquals(expectedRoot.getValue("id").jsonPrimitive.content, operation.jsonObject.rg04String("root_id"), "${actual.operationId} root membership")
        }
    }
}

private fun assertRg04JsonFields(expected: JsonElement?, actual: JsonElement?, path: String) {
    when {
        expected == null || actual == null -> assertEquals(expected, actual, path)
        expected is JsonObject && actual is JsonObject -> {
            assertEquals(expected.keys, actual.keys, "$path fields")
            expected.forEach { (key, value) ->
                assertRg04JsonFields(value, actual[key], "$path.$key")
            }
        }
        expected is JsonArray && actual is JsonArray -> {
            assertEquals(expected.size, actual.size, "$path size")
            expected.zip(actual).forEachIndexed { index, (expectedItem, actualItem) ->
                assertRg04JsonFields(expectedItem, actualItem, "$path[$index]")
            }
        }
        else -> assertEquals(expected, actual, path)
    }
}

private fun rg04OperationContract(
    purpose: String,
    spec: GoldenV2OperationSpec,
    decoded: Rg04RawJsonCase,
    v1: JsonObject,
): Rg04OperationContract {
    if (purpose == "rg04_import_lifecycle" || purpose == "rg04_missing_funding_leg") {
        val importIndex = rg04ImportOperationIndex(
            GoldenV2RootSpec(purpose, "", "", "", "", emptyList()),
            spec.index,
        )
        val operation = decoded.importOperations[importIndex]
        val (actionType, operationClass) = when (operation) {
            is Rg04DecodedImportOperation.Source -> "ingest_mixed_payment_source" to "creation"
            is Rg04DecodedImportOperation.Confirm -> "confirm_mixed_payment_candidate" to "creation"
            is Rg04DecodedImportOperation.Mirror -> "merge_mixed_payment_mirror_evidence" to "reconciliation"
        }
        val rawInput = rg04ImportRawInput(importIndex, v1)
        val normalizedInput = rawInput.filterKeys { it != "kind" }.toMutableMap().apply {
            remove("confirmed")?.let { put("explicit_confirmation", it) }
        }
        if (operation is Rg04DecodedImportOperation.Mirror) {
            val confirmed = v1.getValue("import_lifecycle").jsonObject
                .getValue("ordered_operations").jsonArray[1].jsonObject
            normalizedInput["transaction_id"] =
                JsonPrimitive(
                    v1.getValue("idempotency").jsonObject.getValue("expected").jsonObject
                        .getValue("import_state").jsonObject.getValue("transaction_id").jsonPrimitive.content,
                )
            normalizedInput["candidate_id"] = confirmed.getValue("input").jsonObject.getValue("candidate_id")
            normalizedInput["amount"] = JsonPrimitive(BigDecimal(rawInput.rg04String("amount")).abs().toPlainString())
        }
        return Rg04OperationContract(
            operationClass = operationClass,
            actionType = actionType,
            input = JsonObject(normalizedInput),
            attemptedInput = null,
        )
    }

    val operation = decoded.operations[spec.index]
    return Rg04OperationContract(
        operationClass = operation.operationClass.name.lowercase(),
        actionType = operation.action.name.lowercase(),
        input = rg04NormalizedInput(operation),
        attemptedInput = rg04NormalizedAttemptedInput(operation, v1),
    )
}

private fun rg04ImportRawInput(index: Int, v1: JsonObject): JsonObject {
    val container = when (index) {
        0, 1 -> v1.getValue("import_lifecycle").jsonObject.getValue("ordered_operations").jsonArray[0]
        2, 3 -> v1.getValue("import_lifecycle").jsonObject.getValue("ordered_operations").jsonArray[1]
        4, 5 -> v1.getValue("import_lifecycle").jsonObject.getValue("ordered_operations").jsonArray[2]
        6, 7 -> v1.getValue("missing_funding_leg")
        else -> error("unknown RG-04 import operation index $index")
    }
    return container.jsonObject.getValue("input").jsonObject
}

private fun rg04NormalizedInput(operation: Rg04DecodedOperation): JsonObject? = when (operation) {
    is Rg04DecodedOperation.Manual -> if (operation.operationClass == Rg04OperationClass.REJECTION) {
        null
    } else {
        val input = operation.input
        val settlement = requireNotNull(input.settlement)
        rg04JsonObjectOf(
            "request_id" to JsonPrimitive((input.requestId as Rg04Field.Value).value),
            "occurred_at" to JsonPrimitive((input.occurredAt as Rg04Field.Value).value),
            "total_amount" to JsonPrimitive((input.totalAmount as Rg04Field.Value).value),
            "currency" to JsonPrimitive((input.currency as Rg04Field.Value).value),
            "category_id" to JsonPrimitive((input.categoryId as Rg04Field.Value).value),
            "settlement_explanation" to rg04JsonObjectOf(
                "original_amount" to JsonPrimitive(settlement.originalAmount),
                "discount_amount" to JsonPrimitive(settlement.discountAmount),
                "settled_amount" to JsonPrimitive(settlement.settledAmount),
            ),
            "asset_account_id" to JsonPrimitive((input.funding[0].accountId as Rg04Field.Value).value),
            "liability_account_id" to JsonPrimitive((input.funding[1].accountId as Rg04Field.Value).value),
            "asset_funding_amount" to JsonPrimitive((input.funding[0].amount as Rg04Field.Value).value),
            "liability_funding_amount" to JsonPrimitive((input.funding[1].amount as Rg04Field.Value).value),
            "explicit_confirmation" to JsonPrimitive((input.explicitConfirmation as Rg04Field.Value).value),
        )
    }
    is Rg04DecodedOperation.Repayment -> {
        val input = operation.input
        rg04JsonObjectOf(
            "request_id" to JsonPrimitive((input.requestId as Rg04Field.Value).value),
            "occurred_at" to JsonPrimitive((input.occurredAt as Rg04Field.Value).value),
            "asset_account_id" to JsonPrimitive((input.assetAccountId as Rg04Field.Value).value),
            "liability_account_id" to JsonPrimitive((input.liabilityAccountId as Rg04Field.Value).value),
            "principal_amount" to JsonPrimitive((input.principalAmount as Rg04Field.Value).value),
            "currency" to JsonPrimitive((input.currency as Rg04Field.Value).value),
            "explicit_confirmation" to JsonPrimitive((input.explicitConfirmation as Rg04Field.Value).value),
        )
    }
}

private fun rg04NormalizedAttemptedInput(operation: Rg04DecodedOperation, v1: JsonObject): JsonObject? {
    if (operation !is Rg04DecodedOperation.Manual || operation.operationClass != Rg04OperationClass.REJECTION) return null
    val input = v1.getValue("invalid_manual_inputs").jsonArray
        .map { it.jsonObject }
        .single { it.getValue("id").jsonPrimitive.content == operation.source.rawId }
        .getValue("input").jsonObject
    return JsonObject(linkedMapOf<String, JsonElement>(
        "request_id" to JsonPrimitive((operation.input.requestId as Rg04Field.Value).value),
    ).apply { putAll(input) })
}

private fun rg04FullStateRootSpecs(v1: JsonObject): List<GoldenV2RootSpec> {
    fun root(
        purpose: String,
        locator: String,
        discriminator: String,
        periods: List<Pair<String, String>>,
        operations: List<GoldenV2OperationSpec>,
    ): GoldenV2RootSpec {
        val rootId = goldenV2RootId(RG04_CASE_ID, locator, discriminator)
        val openingId = v1.getValue("opening").jsonObject
            .getValue("transactions").jsonArray.single().jsonObject
            .getValue("id").jsonPrimitive.content
        return GoldenV2RootSpec(
            purpose = purpose,
            rootId = rootId,
            initialStateId = goldenV2MigrationId(
                RG04_CASE_ID,
                rootId,
                "state",
                RG04_OPENING_LOCATOR,
                openingId,
            ),
            openingVersionId = goldenV2MigrationId(
                RG04_CASE_ID,
                rootId,
                "transaction_version",
                RG04_OPENING_LOCATOR,
                openingId,
            ),
            openingPostingSetId = goldenV2MigrationId(
                RG04_CASE_ID,
                rootId,
                "posting_set",
                RG04_OPENING_LOCATOR,
                openingId,
            ),
            operations = operations,
        ).also { require(periods.isNotEmpty()) }
    }

    fun operation(index: Int, locator: String, discriminator: String) =
        GoldenV2OperationSpec(index, locator, discriminator)

    val specs = mutableListOf<GoldenV2RootSpec>()
    val manualOperations = v1.getValue("manual_lifecycle").jsonObject
        .getValue("ordered_operations").jsonArray
    val manualPurchaseRequest = manualOperations[0].jsonObject.getValue("input").jsonObject
        .getValue("request_id").jsonPrimitive.content
    val repaymentRequest = manualOperations[1].jsonObject.getValue("input").jsonObject
        .getValue("request_id").jsonPrimitive.content
    specs += root(
        "rg04_manual_lifecycle",
        "$.manual_lifecycle",
        manualPurchaseRequest,
        listOf(
            "day" to "2026-02-10",
            "month" to "2026-02",
            "day" to "2026-03-05",
            "month" to "2026-03",
            "cumulative" to "lifecycle",
        ),
        listOf(
            operation(0, "$.manual_lifecycle.ordered_operations[*]", manualPurchaseRequest),
            operation(1, "$.idempotency.retried_inputs[*]", manualPurchaseRequest),
            operation(2, "$.manual_lifecycle.ordered_operations[*]", repaymentRequest),
            operation(3, "$.idempotency.retried_inputs[*]", repaymentRequest),
        ),
    )

    val importOperations = v1.getValue("import_lifecycle").jsonObject
        .getValue("ordered_operations").jsonArray
    val importSourceRecordId = importOperations[0].jsonObject.getValue("input").jsonObject
        .getValue("source_record").jsonObject.getValue("id").jsonPrimitive.content
    val confirmRequestId = importOperations[1].jsonObject.getValue("input").jsonObject
        .getValue("request_id").jsonPrimitive.content
    val mirrorEvidenceId = importOperations[2].jsonObject.getValue("input").jsonObject
        .getValue("evidence_id").jsonPrimitive.content
    specs += root(
        "rg04_import_lifecycle",
        "$.import_lifecycle",
        importSourceRecordId,
        listOf("day" to "2026-02-11", "month" to "2026-02", "cumulative" to "lifecycle"),
        listOf(
            operation(0, "$.import_lifecycle.ordered_operations[*]", importOperations[0].jsonObject.getValue("id").jsonPrimitive.content),
            operation(1, "$.idempotency.retried_inputs[*]", importSourceRecordId),
            operation(2, "$.import_lifecycle.ordered_operations[*]", importOperations[1].jsonObject.getValue("id").jsonPrimitive.content),
            operation(3, "$.idempotency.retried_inputs[*]", confirmRequestId),
            operation(4, "$.import_lifecycle.ordered_operations[*]", importOperations[2].jsonObject.getValue("id").jsonPrimitive.content),
            operation(5, "$.idempotency.retried_inputs[*]", mirrorEvidenceId),
        ),
    )

    val missing = v1.getValue("missing_funding_leg").jsonObject
    val missingSourceId = missing.getValue("input").jsonObject
        .getValue("source_record").jsonObject.getValue("id").jsonPrimitive.content
    specs += root(
        "rg04_missing_funding_leg",
        "$.missing_funding_leg",
        missingSourceId,
        listOf("cumulative" to "lifecycle"),
        listOf(
            operation(0, "$.missing_funding_leg", missingSourceId),
            operation(1, "$.missing_funding_leg.retry", missingSourceId),
        ),
    )

    v1.getValue("invalid_manual_inputs").jsonArray.forEachIndexed { index, item ->
        val invalidId = item.jsonObject.getValue("id").jsonPrimitive.content
        specs += root(
            "rg04_invalid_${invalidId.replace('-', '_')}",
            "$.invalid_manual_inputs[*]",
            invalidId,
            listOf("cumulative" to "lifecycle"),
            listOf(operation(4 + index, "$.invalid_manual_inputs[*]", invalidId)),
        )
    }
    return specs.sortedBy { it.rootId }
}

private fun executeRg04FullStateRoot(
    spec: GoldenV2RootSpec,
    decoded: Rg04RawJsonCase,
    v1: JsonObject,
): GoldenV2ObservedRoot = executeGoldenV2Root(
    caseId = RG04_CASE_ID,
    spec = spec,
    ledgerId = decoded.ledgerId,
    v1 = v1,
    requestId = { operation ->
        if (spec.purpose == "rg04_import_lifecycle" || spec.purpose == "rg04_missing_funding_leg") {
            rg04ImportRequestId(decoded.importOperations[rg04ImportOperationIndex(spec, operation.index)])
        } else {
            rg04OperationRequestId(decoded.operations[operation.index])
        }
    },
    createRuntime = { database, driver, operationIdsByRequest ->
        val projector = Rg04FullStateProjector(database, driver, decoded, v1, spec, operationIdsByRequest)
        val importRoot = spec.purpose == "rg04_import_lifecycle" || spec.purpose == "rg04_missing_funding_leg"
        if (importRoot) {
            val executor = ExecuteRg04ImportOperation(
                SqlDelightRg04ImportStore(database, driver, decoded.catalog),
            )
            GoldenV2RootRuntime(
                projectState = projector::state,
                executeOperation = { operation ->
                    val result = executor.execute(decoded.importOperations[rg04ImportOperationIndex(spec, operation.index)])
                    GoldenV2OperationResult(rg04FullStateOutcome(result), rg04FullStateReturnedIds(result))
                },
            )
        } else {
            val executor = ExecuteRg04Operation(
                SqlDelightRg04Store(database, driver, decoded.catalog, rg04FullStateIdentity(spec, decoded)),
            )
            GoldenV2RootRuntime(
                projectState = projector::state,
                executeOperation = { operation ->
                    val decodedOperation = decoded.operations[operation.index]
                    val result = when (val adapted = adaptRg04Operation(decoded, decodedOperation)) {
                        is Rg04AdaptResult.Invalid -> adapted
                        is Rg04AdaptResult.Success -> executor.execute(adapted.operation)
                    }
                    GoldenV2OperationResult(rg04FullStateOutcome(result), rg04FullStateReturnedIds(result))
                },
            )
        }
    },
)

private fun rg04ImportOperationIndex(spec: GoldenV2RootSpec, index: Int): Int =
    index + if (spec.purpose == "rg04_missing_funding_leg") 6 else 0

private fun rg04OperationRequestId(operation: Rg04DecodedOperation): String = when (operation) {
    is Rg04DecodedOperation.Manual -> (operation.input.requestId as Rg04Field.Value).value
    is Rg04DecodedOperation.Repayment -> (operation.input.requestId as Rg04Field.Value).value
}

private fun rg04ImportRequestId(operation: Rg04DecodedImportOperation): String = when (operation) {
    is Rg04DecodedImportOperation.Source -> operation.snapshot.requestId.value
    is Rg04DecodedImportOperation.Confirm -> operation.snapshot.requestId.value
    is Rg04DecodedImportOperation.Mirror -> operation.snapshot.requestId.value
}

private fun rg04FullStateIdentity(spec: GoldenV2RootSpec, decoded: Rg04RawJsonCase): Rg04IdentitySource =
    object : Rg04IdentitySource {
        private val locator = "$.manual_lifecycle.ordered_operations[*]"

        override fun manual(requestId: RequestId): Rg04ManualCommitIds = Rg04ManualCommitIds(
            goldenV2MigrationId(RG04_CASE_ID, spec.rootId, "confirmation", "$locator.confirmation", requestId.value),
            decoded.manualIds.fundingPostingIds.map { posting ->
                goldenV2MigrationId(
                    RG04_CASE_ID,
                    spec.rootId,
                    "posting_reconciliation",
                    "$locator.expected.reconciliation",
                    posting.value,
                )
            },
        )

        override fun repayment(requestId: RequestId): Rg04RepaymentCommitIds = Rg04RepaymentCommitIds(
            goldenV2MigrationId(RG04_CASE_ID, spec.rootId, "confirmation", "$locator.confirmation", requestId.value),
            listOf(decoded.repaymentIds.assetPostingId, decoded.repaymentIds.liabilityPostingId).map { posting ->
                goldenV2MigrationId(
                    RG04_CASE_ID,
                    spec.rootId,
                    "posting_reconciliation",
                    "$locator.expected.reconciliation",
                    posting.value,
                )
            },
        )
    }

private fun rg04FullStateOutcome(result: Any): JsonObject = when (result) {
    is Rg04AdaptResult.Invalid -> rg04JsonObjectOf(
        "status" to JsonPrimitive("rejected"),
        "reason_code" to JsonPrimitive(result.reason),
        "field_path" to JsonPrimitive("$.attempted_input.${result.field}"),
    )
    is Rg04ExecutionResult.Accepted,
    is Rg04ImportExecutionResult.Accepted,
    -> rg04JsonObjectOf("status" to JsonPrimitive("accepted"))
    is Rg04ExecutionResult.NoChange,
    is Rg04ImportExecutionResult.NoChange,
    -> rg04JsonObjectOf(
        "status" to JsonPrimitive("no_change"),
        "reason_code" to JsonPrimitive("idempotent_replay"),
    )
    is Rg04ExecutionResult.Rejected -> rg04JsonObjectOf(
        "status" to JsonPrimitive("rejected"),
        "reason_code" to JsonPrimitive(result.error.name.lowercase()),
        "field_path" to JsonPrimitive("$.attempted_input.${result.field}"),
    )
    is Rg04ImportExecutionResult.Rejected -> rg04JsonObjectOf(
        "status" to JsonPrimitive("rejected"),
        "reason_code" to JsonPrimitive(result.error.name.lowercase()),
        "field_path" to result.field?.let { JsonPrimitive("$.attempted_input.$it") },
    )
    Rg04ExecutionResult.RequestIdentityConflict,
    Rg04ImportExecutionResult.RequestIdentityConflict,
    -> error("unexpected RG-04 request identity conflict")
    else -> error("unexpected RG-04 result $result")
}

private fun rg04FullStateReturnedIds(result: Any): JsonArray {
    val ids = when (result) {
        is Rg04ExecutionResult.Accepted -> listOf(
            "confirmation" to result.confirmationId,
            "transaction" to result.transactionId.value,
        )
        is Rg04ExecutionResult.NoChange -> listOf(
            "confirmation" to result.confirmationId,
            "transaction" to result.transactionId.value,
        )
        is Rg04ImportExecutionResult.Accepted -> result.returnedIds.map { it.kind.name.lowercase() to it.id }
        is Rg04ImportExecutionResult.NoChange -> result.returnedIds.map { it.kind.name.lowercase() to it.id }
        else -> emptyList()
    }
    return JsonArray(ids.map { (kind, id) -> rg04JsonObjectOf("kind" to JsonPrimitive(kind), "id" to JsonPrimitive(id)) })
}

private data class Rg04ProjectedTransaction(val id: String, val type: String, val currentVersionId: String)
private data class Rg04ProjectedVersion(
    val id: String,
    val transactionId: String,
    val versionNumber: Long,
    val postingSetId: String,
    val occurredAt: String,
    val statisticsAt: String,
    val effectiveAt: String,
    val confirmationId: String?,
)
private data class Rg04ProjectedPosting(
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
private data class Rg04ProjectedFunding(
    val accountId: String?,
    val amountMinor: Long,
    val currency: String,
    val precision: Long,
    val evidenceAvailable: Long,
)
private data class Rg04ProjectedSourceSnapshot(
    val requestId: String,
    val sourceId: String,
    val observedAt: String,
    val totalMinor: Long,
    val currency: String,
    val precision: Long,
    val suggestedCategoryId: String?,
    val completeness: String,
    val confidence: String,
    val candidateKind: String,
    val candidateId: String,
    val candidateStatusId: String,
    val funding: List<Rg04ProjectedFunding>,
)
private data class Rg04ProjectedMirror(
    val requestId: String,
    val accountId: String,
    val amountMinor: Long,
    val currency: String,
    val precision: Long,
)
private data class Rg04ProjectedSource(
    val id: String,
    val ownerRequestId: String,
    val evidenceId: String,
    val observedAt: String,
    val sourceKind: String,
)
private data class Rg04ProjectedCandidate(
    val id: String,
    val sourceId: String,
    val kind: String,
    val confidence: String,
    val rule: String,
    val ruleVersion: Long,
    val transactionId: String?,
)
private data class Rg04ProjectedCandidateStatus(
    val candidateId: String,
    val sequence: Long,
    val id: String,
    val status: String,
)
private data class Rg04ProjectedConfirmation(
    val id: String,
    val requestId: String,
    val candidateId: String?,
    val transactionId: String,
    val kind: String,
)
private data class Rg04ProjectedEvidence(
    val id: String,
    val sourceId: String,
    val role: String,
    val observedAt: String,
)
private data class Rg04ProjectedEvidenceLink(
    val id: String,
    val evidenceId: String,
    val postingId: String,
)
private data class Rg04ProjectedRelation(
    val id: String,
    val type: String,
)
private data class Rg04ProjectedRelationMember(
    val relationId: String,
    val kind: String,
    val transactionId: String?,
    val postingId: String?,
)
private data class Rg04ProjectedComposition(
    val relationId: String,
    val transactionId: String,
    val displayName: String,
    val totalMinor: Long,
    val currency: String,
    val precision: Long,
)
private data class Rg04ProjectedCompositionComponent(
    val relationId: String,
    val postingId: String,
    val accountId: String,
    val amountMinor: Long,
    val currency: String,
    val precision: Long,
)
private data class Rg04ProjectedReconciliation(val id: String, val postingId: String, val status: String)

private class Rg04FullStateProjector(
    private val database: LedgerDatabase,
    private val driver: JdbcSqliteDriver,
    private val decoded: Rg04RawJsonCase,
    private val v1: JsonObject,
    private val spec: GoldenV2RootSpec,
    private val operationIdsByRequest: Map<String, String>,
) {
    fun state(id: String, asOfOperationId: String?): JsonObject {
        val confirmations = confirmations()
        val transactions = transactions()
        val versions = versions().map { version ->
            version.copy(confirmationId = confirmations.singleOrNull { it.transactionId == version.transactionId }?.id)
        }
        val postingSets = postingSets()
        val postings = postings()
        val sources = sources()
        val sourceSnapshots = sourceSnapshots()
        val mirrors = mirrors()
        val candidates = candidates()
        val candidateStatuses = candidateStatuses()
        val evidence = evidence()
        val evidenceLinks = evidenceLinks()
        val relations = relations()
        val relationMembers = relationMembers()
        val compositions = compositions()
        val compositionComponents = compositionComponents()
        val reconciliations = reconciliations()

        return rg04JsonObjectOf(
            "id" to JsonPrimitive(id),
            "root_id" to JsonPrimitive(spec.rootId),
            "as_of_operation_id" to (asOfOperationId?.let(::JsonPrimitive) ?: JsonNull),
            "catalog" to projectCatalog(),
            "transactions" to JsonArray(transactions.map { transaction ->
                rg04JsonObjectOf(
                    "id" to JsonPrimitive(transaction.id),
                    "type" to JsonPrimitive(transaction.type.lowercase()),
                    "current_version_id" to JsonPrimitive(transaction.currentVersionId),
                )
            }),
            "transaction_versions" to JsonArray(versions.map { version ->
                rg04JsonObjectOf(
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
                rg04JsonObjectOf(
                    "id" to JsonPrimitive(setId),
                    "posting_ids" to JsonArray(postingIds.map(::JsonPrimitive)),
                )
            }),
            "postings" to JsonArray(postings.map { posting ->
                rg04JsonObjectOf(
                    "id" to JsonPrimitive(posting.id),
                    "posting_set_id" to JsonPrimitive(posting.postingSetId),
                    "account_id" to JsonPrimitive(posting.accountId),
                    "category_id" to posting.categoryId?.let(::JsonPrimitive),
                    "amount" to JsonPrimitive(rg04Amount(posting.amountMinor, posting.precision)),
                    "currency" to JsonPrimitive(posting.currency),
                    "role" to posting.role?.lowercase()?.let(::JsonPrimitive),
                    "reconciliation_eligible" to JsonPrimitive(posting.reconciliationEligible == 1L),
                )
            }),
            "sources" to JsonArray(sources.map { projectSource(it, sourceSnapshots, mirrors) }),
            "candidates" to JsonArray(candidates.map { projectCandidate(it, sourceSnapshots, candidateStatuses) }),
            "confirmations" to JsonArray(confirmations.map(::projectConfirmation)),
            "evidence" to JsonArray(evidence.map { item ->
                rg04JsonObjectOf(
                    "id" to JsonPrimitive(item.id),
                    "type" to JsonPrimitive(rg04EvidenceType(item.role)),
                    "source_ids" to JsonArray(listOf(JsonPrimitive(item.sourceId))),
                    "payload" to rg04JsonObjectOf("observed_at" to JsonPrimitive(item.observedAt)),
                )
            }),
            "evidence_links" to JsonArray(evidenceLinks.map { link ->
                rg04JsonObjectOf(
                    "id" to JsonPrimitive(link.id),
                    "evidence_id" to JsonPrimitive(link.evidenceId),
                    "target_kind" to JsonPrimitive("posting"),
                    "target_id" to JsonPrimitive(link.postingId),
                    "role" to JsonPrimitive("real_account_posting"),
                )
            }),
            "relations" to JsonArray(relations.map { relation ->
                projectRelation(relation, relationMembers, compositions, compositionComponents)
            }),
            "domain_entities" to JsonArray(emptyList()),
            "audit_links" to JsonArray(emptyList()),
            "posting_reconciliations" to JsonArray(reconciliations.map { reconciliation ->
                rg04JsonObjectOf(
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
                candidates,
                candidateStatuses,
                confirmations,
                reconciliations,
            ),
        )
    }

    private fun projectCatalog(): JsonObject {
        val rawCatalog = v1.getValue("catalog").jsonObject
        return rg04JsonObjectOf(
            "accounts" to JsonArray(rawCatalog.getValue("accounts").jsonArray.sortedBy { it.jsonObject.rg04String("id") }.map { element ->
                val account = element.jsonObject
                val kind = account.rg04String("kind")
                rg04JsonObjectOf(
                    "id" to JsonPrimitive(account.rg04String("id")),
                    "name" to JsonPrimitive(account.rg04String("name")),
                    "kind" to JsonPrimitive(kind),
                    "currency" to JsonPrimitive(decoded.currency.code),
                    "owned_by_user" to account.getValue("owned_by_user"),
                    "real_account" to account.getValue("real_account"),
                    "reconciliation_eligible" to JsonPrimitive(
                        account.getValue("owned_by_user").jsonPrimitive.boolean &&
                            account.getValue("real_account").jsonPrimitive.boolean &&
                            kind in setOf("asset", "liability"),
                    ),
                )
            }),
            "categories" to JsonArray(rawCatalog.getValue("categories").jsonArray.sortedBy { it.jsonObject.rg04String("id") }.map { element ->
                val category = element.jsonObject
                rg04JsonObjectOf(
                    "id" to JsonPrimitive(category.rg04String("id")),
                    "name" to JsonPrimitive(category.rg04String("name")),
                    "parent_id" to (category["parent_id"] ?: JsonNull),
                    "posting_account_id" to (category["posting_account_id"] ?: JsonNull),
                    "active" to category.getValue("active"),
                )
            }),
        )
    }

    private fun projectSource(
        source: Rg04ProjectedSource,
        snapshots: Map<String, Rg04ProjectedSourceSnapshot>,
        mirrors: Map<String, Rg04ProjectedMirror>,
    ): JsonObject {
        val snapshot = snapshots[source.id]
        val mirror = mirrors[source.ownerRequestId]
        val payload = when {
            mirror != null -> rg04JsonObjectOf(
                "evidence_id" to JsonPrimitive(source.evidenceId),
                "observed_at" to JsonPrimitive(source.observedAt),
                "account_id" to JsonPrimitive(mirror.accountId),
                "amount" to JsonPrimitive(rg04Amount(mirror.amountMinor.absoluteValue, mirror.precision)),
                "currency" to JsonPrimitive(mirror.currency),
            )
            snapshot?.completeness == "COMPLETE" -> rg04JsonObjectOf(
                "evidence_id" to JsonPrimitive(source.evidenceId),
                "observed_at" to JsonPrimitive(source.observedAt),
                "total_amount" to JsonPrimitive(rg04Amount(snapshot.totalMinor, snapshot.precision)),
                "currency" to JsonPrimitive(snapshot.currency),
                "suggested_category_id" to snapshot.suggestedCategoryId?.let(::JsonPrimitive),
                "funding_components" to JsonArray(snapshot.funding.map(::projectFunding)),
                "completeness" to JsonPrimitive("complete"),
            )
            snapshot != null -> {
                val known = snapshot.funding.single { it.accountId != null }
                rg04JsonObjectOf(
                    "evidence_id" to JsonPrimitive(source.evidenceId),
                    "observed_at" to JsonPrimitive(source.observedAt),
                    "total_amount" to JsonPrimitive(rg04Amount(snapshot.totalMinor, snapshot.precision)),
                    "known_asset_funding_amount" to JsonPrimitive(rg04Amount(known.amountMinor, known.precision)),
                    "currency" to JsonPrimitive(snapshot.currency),
                    "completeness" to JsonPrimitive("missing_funding_leg"),
                )
            }
            else -> error("RG-04 source has no snapshot: ${source.id}")
        }
        return rg04JsonObjectOf(
            "id" to JsonPrimitive(source.id),
            "type" to JsonPrimitive("mixed_payment"),
            "payload" to payload,
        )
    }

    private fun projectFunding(item: Rg04ProjectedFunding): JsonObject = rg04JsonObjectOf(
        "account_id" to item.accountId?.let(::JsonPrimitive),
        "funding_amount" to JsonPrimitive(rg04Amount(item.amountMinor, item.precision)),
        "currency" to JsonPrimitive(item.currency),
        "evidence_available" to JsonPrimitive(item.evidenceAvailable == 1L),
    )

    private fun projectCandidate(
        candidate: Rg04ProjectedCandidate,
        snapshots: Map<String, Rg04ProjectedSourceSnapshot>,
        statuses: List<Rg04ProjectedCandidateStatus>,
    ): JsonObject {
        val snapshot = snapshots.getValue(candidate.sourceId)
        val payload = if (snapshot.completeness == "COMPLETE") {
            rg04JsonObjectOf(
                "total_amount" to JsonPrimitive(rg04Amount(snapshot.totalMinor, snapshot.precision)),
                "currency" to JsonPrimitive(snapshot.currency),
                "suggested_category_id" to snapshot.suggestedCategoryId?.let(::JsonPrimitive),
                "known_funding_components" to JsonArray(snapshot.funding.map(::projectFunding)),
                "provenance" to rg04JsonObjectOf(
                    "rule" to JsonPrimitive(
                        if (snapshot.completeness == "COMPLETE") {
                            "complete_mixed_payment_source"
                        } else {
                            "incomplete_mixed_payment_source"
                        },
                    ),
                    "rule_version" to JsonPrimitive(candidate.ruleVersion),
                ),
                "evidence_refs" to JsonArray(listOf(JsonPrimitive(snapshot.sourceId.let { sourceEvidenceId(it) }))),
                "requires_confirmation" to JsonArray(
                    listOf("category_id", "funding_components", "formal_transaction_creation").map(::JsonPrimitive),
                ),
                "transaction_id" to candidate.transactionId?.let(::JsonPrimitive),
            )
        } else {
            val known = snapshot.funding.single { it.accountId != null }
            val missing = snapshot.funding.single { it.accountId == null }
            rg04JsonObjectOf(
                "total_amount" to JsonPrimitive(rg04Amount(snapshot.totalMinor, snapshot.precision)),
                "currency" to JsonPrimitive(snapshot.currency),
                "known_funding_amount" to JsonPrimitive(rg04Amount(known.amountMinor, known.precision)),
                "missing_funding_amount" to JsonPrimitive(rg04Amount(missing.amountMinor, missing.precision)),
                "provenance" to rg04JsonObjectOf(
                    "rule" to JsonPrimitive("incomplete_mixed_payment_source"),
                    "rule_version" to JsonPrimitive(candidate.ruleVersion),
                ),
                "evidence_refs" to JsonArray(listOf(JsonPrimitive(sourceEvidenceId(snapshot.sourceId)))),
                "requires_confirmation" to JsonArray(
                    listOf("funding_account_id", "missing_funding_amount", "category_id", "formal_transaction_creation").map(::JsonPrimitive),
                ),
            )
        }
        val history = statuses.filter { it.candidateId == candidate.id }
        return rg04JsonObjectOf(
            "id" to JsonPrimitive(candidate.id),
            "type" to JsonPrimitive("mixed_payment"),
            "source_ids" to JsonArray(listOf(JsonPrimitive(candidate.sourceId))),
            "confidence" to JsonPrimitive(candidate.confidence),
            "payload" to payload,
            "status_history" to JsonArray(history.map { status ->
                rg04JsonObjectOf(
                    "id" to JsonPrimitive(status.id),
                    "sequence" to JsonPrimitive(status.sequence),
                    "status" to JsonPrimitive(status.status.lowercase()),
                )
            }),
        )
    }

    private fun sourceEvidenceId(sourceId: String): String = sources()
        .single { it.id == sourceId }
        .evidenceId

    private fun projectConfirmation(confirmation: Rg04ProjectedConfirmation): JsonObject {
        val operationId = operationIdsByRequest.getValue(confirmation.requestId)
        return rg04JsonObjectOf(
            "id" to JsonPrimitive(confirmation.id),
            "type" to JsonPrimitive(if (confirmation.candidateId == null) "explicit_manual_save" else "candidate_confirmation"),
            "operation_id" to JsonPrimitive(operationId),
            "subject" to if (confirmation.candidateId == null) {
                rg04JsonObjectOf("kind" to JsonPrimitive("operation"), "id" to JsonPrimitive(operationId))
            } else {
                rg04JsonObjectOf("kind" to JsonPrimitive("candidate"), "id" to JsonPrimitive(confirmation.candidateId))
            },
            "payload" to JsonObject(emptyMap()),
        )
    }

    private fun projectRelation(
        relation: Rg04ProjectedRelation,
        members: List<Rg04ProjectedRelationMember>,
        compositions: List<Rg04ProjectedComposition>,
        components: List<Rg04ProjectedCompositionComponent>,
    ): JsonObject {
        val composition = compositions.single { it.relationId == relation.id }
        val relationComponents = components.filter { it.relationId == relation.id }
        return rg04JsonObjectOf(
            "id" to JsonPrimitive(relation.id),
            "type" to JsonPrimitive(relation.type.lowercase()),
            "member_refs" to JsonArray(
                members.filter { it.relationId == relation.id }
                    .sortedWith(compareBy({ it.kind }, { it.transactionId ?: it.postingId!! }))
                    .map { member ->
                    rg04JsonObjectOf(
                        "kind" to JsonPrimitive(member.kind.lowercase()),
                        "id" to JsonPrimitive(member.transactionId ?: member.postingId!!),
                    )
                },
            ),
            "payload" to rg04JsonObjectOf(
                "system_managed" to JsonPrimitive(true),
                "display_name" to JsonPrimitive(composition.displayName),
                "generic_order_lifecycle" to JsonPrimitive(false),
                "payment_composition_total" to JsonPrimitive(rg04Amount(composition.totalMinor, composition.precision)),
                "funding_components" to JsonArray(relationComponents.sortedBy { it.postingId }.map { component ->
                    rg04JsonObjectOf(
                        "account_id" to JsonPrimitive(component.accountId),
                        "funding_amount" to JsonPrimitive(rg04Amount(component.amountMinor, component.precision)),
                        "currency" to JsonPrimitive(component.currency),
                        "posting_id" to JsonPrimitive(component.postingId),
                    )
                }),
            ),
        )
    }

    private fun projectBalances(postings: List<Rg04ProjectedPosting>): JsonArray = JsonArray(
        v1.getValue("catalog").jsonObject.getValue("accounts").jsonArray.sortedBy { it.jsonObject.rg04String("id") }.map { element ->
            val account = element.jsonObject
            val accountId = account.rg04String("id")
            rg04JsonObjectOf(
                "account_id" to JsonPrimitive(accountId),
                "currency" to JsonPrimitive(decoded.currency.code),
                "amount" to JsonPrimitive(
                    rg04Amount(postings.filter { it.accountId == accountId }.sumOf { it.amountMinor }, decoded.currency.precision.toLong()),
                ),
            )
        },
    )

    private fun projectReports(
        transactions: List<Rg04ProjectedTransaction>,
        versions: List<Rg04ProjectedVersion>,
        postings: List<Rg04ProjectedPosting>,
    ): JsonArray {
        val periods = when (spec.purpose) {
            "rg04_manual_lifecycle" -> listOf(
                "day" to "2026-02-10",
                "month" to "2026-02",
                "day" to "2026-03-05",
                "month" to "2026-03",
                "cumulative" to "lifecycle",
            )
            "rg04_import_lifecycle" -> listOf(
                "day" to "2026-02-11",
                "month" to "2026-02",
                "cumulative" to "lifecycle",
            )
            else -> listOf("cumulative" to "lifecycle")
        }
        return JsonArray(periods.sortedWith(compareBy({ it.first }, { it.second })).map { (periodType, period) ->
            val values = RG04_REPORT_METRICS.associateWith { 0L }.toMutableMap()
            transactions.forEach { transaction ->
                if (transaction.type == "OPENING_BALANCE") return@forEach
                val version = versions.single { it.id == transaction.currentVersionId }
                if (!rg04InPeriod(version.statisticsAt, periodType, period)) return@forEach
                val currentPostings = postings.filter { it.postingSetId == version.postingSetId }
                when (transaction.type) {
                    "EXPENSE" -> {
                        val expense = currentPostings.filter { it.role == "expense" }.sumOf { it.amountMinor }
                        val asset = currentPostings.single { it.role == "mixed_expense_asset_funding" }.amountMinor
                        values["consumption"] = values.getValue("consumption") + expense
                        values["ordinary_expense"] = values.getValue("ordinary_expense") + expense
                        values["cash_outflow"] = values.getValue("cash_outflow") - asset
                        values["net_worth_change"] = values.getValue("net_worth_change") - expense
                    }
                    "CREDIT_REPAYMENT" -> {
                        val asset = currentPostings.single { it.role == "credit_repayment_asset_outflow" }.amountMinor
                        values["cash_outflow"] = values.getValue("cash_outflow") - asset
                    }
                }
            }
            rg04Report(periodType, period, values)
        })
    }

    private fun projectDerivedStatuses(
        transactions: List<Rg04ProjectedTransaction>,
        versions: List<Rg04ProjectedVersion>,
        postings: List<Rg04ProjectedPosting>,
        candidates: List<Rg04ProjectedCandidate>,
        candidateStatuses: List<Rg04ProjectedCandidateStatus>,
        confirmations: List<Rg04ProjectedConfirmation>,
        reconciliations: List<Rg04ProjectedReconciliation>,
    ): JsonArray {
        val values = mutableListOf<JsonObject>()
        candidates.forEach { candidate ->
            val latest = candidateStatuses.filter { it.candidateId == candidate.id }.maxByOrNull { it.sequence }
                ?: return@forEach
            val locator = if (spec.purpose == "rg04_missing_funding_leg") {
                "$.missing_funding_leg.expected.candidate.status"
            } else {
                "$.import_lifecycle.ordered_operations[*].expected.candidate.status"
            }
            values += rg04JsonObjectOf(
                "id" to JsonPrimitive(goldenV2MigrationId(RG04_CASE_ID, spec.rootId, "derived_status", locator, candidate.id)),
                "target_kind" to JsonPrimitive("candidate"),
                "target_id" to JsonPrimitive(candidate.id),
                "status_name" to JsonPrimitive("confirmation_status"),
                "value" to JsonPrimitive(latest.status.lowercase()),
            )
        }
        transactions.filter { it.type != "OPENING_BALANCE" }.forEach { transaction ->
            val version = versions.single { it.id == transaction.currentVersionId }
            val eligibleIds = postings.filter {
                it.postingSetId == version.postingSetId && it.reconciliationEligible == 1L
            }.map { it.id }.toSet()
            val relevant = reconciliations.filter { it.postingId in eligibleIds }
            val value = when {
                relevant.size == eligibleIds.size && relevant.all { it.status == "MATCHED" } -> "matched"
                relevant.any { it.status == "MATCHED" } -> "partial"
                else -> "pending"
            }
            val confirmation = confirmations.single { it.transactionId == transaction.id }
            val locator = if (confirmation.candidateId == null) {
                "$.manual_lifecycle.ordered_operations[*].expected.reconciliation"
            } else {
                "$.import_lifecycle.ordered_operations[*].expected.reconciliation"
            }
            values += rg04JsonObjectOf(
                "id" to JsonPrimitive(goldenV2MigrationId(RG04_CASE_ID, spec.rootId, "derived_status", locator, transaction.id)),
                "target_kind" to JsonPrimitive("transaction"),
                "target_id" to JsonPrimitive(transaction.id),
                "status_name" to JsonPrimitive("reconciliation_summary"),
                "value" to JsonPrimitive(value),
            )
        }
        return JsonArray(values.sortedWith(compareBy({ it.rg04String("target_kind") }, { it.rg04String("target_id") }, { it.rg04String("status_name") })))
    }

    private fun transactions(): List<Rg04ProjectedTransaction> = driver.rg04OracleRows(
        """
        SELECT transaction_row.transaction_id, transaction_row.kind, current_version.current_version_id
        FROM ledger_transaction AS transaction_row
        JOIN ledger_transaction_current_version AS current_version
          ON current_version.ledger_id = transaction_row.ledger_id
         AND current_version.transaction_id = transaction_row.transaction_id
        WHERE transaction_row.ledger_id = '$RG04_LEDGER_ID'
        ORDER BY transaction_row.transaction_id
        """.trimIndent(),
    ) { cursor -> Rg04ProjectedTransaction(cursor.string(0), cursor.string(1), cursor.string(2)) }

    private fun versions(): List<Rg04ProjectedVersion> = driver.rg04OracleRows(
        """
        SELECT version.version_id, version.transaction_id, version.version_number,
               version.posting_set_id, version.occurred_at, version.statistics_at,
               version.effective_at
        FROM transaction_version AS version
        WHERE version.ledger_id = '$RG04_LEDGER_ID'
        ORDER BY version.version_id
        """.trimIndent(),
    ) { cursor ->
        Rg04ProjectedVersion(
            cursor.string(0), cursor.string(1), cursor.long(2), cursor.string(3),
            cursor.string(4), cursor.string(5), cursor.string(6), null,
        )
    }

    private fun postingSets(): List<Pair<String, List<String>>> {
        val rows = driver.rg04OracleRows(
            """
            SELECT posting_set.posting_set_id, posting.posting_id
            FROM posting_set
            JOIN posting ON posting.ledger_id = posting_set.ledger_id
              AND posting.posting_set_id = posting_set.posting_set_id
            WHERE posting_set.ledger_id = '$RG04_LEDGER_ID'
            ORDER BY posting_set.posting_set_id, posting.posting_id
            """.trimIndent(),
        ) { cursor -> cursor.string(0) to cursor.string(1) }
        return rows.groupBy({ it.first }, { it.second }).map { it.key to it.value }
    }

    private fun postings(): List<Rg04ProjectedPosting> = driver.rg04OracleRows(
        """
        SELECT posting.posting_id, posting.posting_set_id, posting.account_id,
               posting.amount_minor, posting.currency_code, posting.currency_precision,
               semantic.role, semantic.category_id, semantic.reconciliation_eligible
        FROM posting
        LEFT JOIN rg04_posting_semantic AS semantic
          ON semantic.ledger_id = posting.ledger_id AND semantic.posting_id = posting.posting_id
        WHERE posting.ledger_id = '$RG04_LEDGER_ID'
        ORDER BY posting.posting_id
        """.trimIndent(),
    ) { cursor ->
        Rg04ProjectedPosting(
            cursor.string(0), cursor.string(1), cursor.string(2), cursor.long(3), cursor.string(4),
            cursor.long(5), cursor.getString(6), cursor.getString(7), cursor.getLong(8),
        )
    }

    private fun sources(): List<Rg04ProjectedSource> = driver.rg04OracleRows(
        """
        SELECT source_id, owner_request_id, evidence_id, observed_at, source_kind
        FROM rg04_import_source
        WHERE ledger_id = '$RG04_LEDGER_ID'
        ORDER BY source_id
        """.trimIndent(),
    ) { cursor ->
        Rg04ProjectedSource(cursor.string(0), cursor.string(1), cursor.string(2), cursor.string(3), cursor.string(4))
    }

    private fun sourceSnapshots(): Map<String, Rg04ProjectedSourceSnapshot> {
        val snapshots = driver.rg04OracleRows(
            """
            SELECT request_id, source_id, observed_at, total_minor, currency_code, currency_precision,
                   suggested_category_id, completeness, confidence, candidate_kind, candidate_id,
                   candidate_status_id
            FROM rg04_import_source_snapshot
            WHERE ledger_id = '$RG04_LEDGER_ID'
            ORDER BY source_id
            """.trimIndent(),
        ) { cursor ->
            Rg04ProjectedSourceSnapshot(
                cursor.string(0), cursor.string(1), cursor.string(2), cursor.long(3), cursor.string(4),
                cursor.long(5), cursor.getString(6), cursor.string(7), cursor.string(8), cursor.string(9),
                cursor.string(10), cursor.string(11), emptyList(),
            )
        }
        val components = driver.rg04OracleRows(
            """
            SELECT request_id, account_id, amount_minor, currency_code,
                   currency_precision, evidence_available
            FROM rg04_import_source_component_snapshot
            WHERE ledger_id = '$RG04_LEDGER_ID'
            ORDER BY request_id, account_id, amount_minor, currency_code, evidence_available
            """.trimIndent(),
        ) { cursor ->
            cursor.string(0) to Rg04ProjectedFunding(
                cursor.getString(1), cursor.long(2), cursor.string(3), cursor.long(4), cursor.long(5),
            )
        }.groupBy({ it.first }, { it.second }).mapValues { (_, values) ->
            values.sortedWith(
                compareBy(
                    { it.accountId ?: "" },
                    { it.amountMinor },
                    { it.currency },
                    { it.precision },
                    { it.evidenceAvailable },
                ),
            )
        }
        return snapshots.associate { snapshot ->
            snapshot.sourceId to snapshot.copy(funding = components[snapshot.requestId].orEmpty())
        }
    }

    private fun mirrors(): Map<String, Rg04ProjectedMirror> = driver.rg04OracleRows(
        """
        SELECT request_id, account_id, amount_minor, currency_code, currency_precision
        FROM rg04_import_mirror_snapshot
        WHERE ledger_id = '$RG04_LEDGER_ID'
        ORDER BY request_id
        """.trimIndent(),
    ) { cursor ->
        cursor.string(0) to Rg04ProjectedMirror(cursor.string(0), cursor.string(1), cursor.long(2), cursor.string(3), cursor.long(4))
    }.toMap()

    private fun candidates(): List<Rg04ProjectedCandidate> = driver.rg04OracleRows(
        """
        SELECT candidate.candidate_id, candidate.source_id, candidate.candidate_kind,
               candidate.confidence, candidate.provenance_rule, candidate.provenance_rule_version,
               confirmation.transaction_id
        FROM rg04_import_candidate AS candidate
        LEFT JOIN rg04_import_confirmation AS confirmation
          ON confirmation.ledger_id = candidate.ledger_id
         AND confirmation.candidate_id = candidate.candidate_id
        WHERE candidate.ledger_id = '$RG04_LEDGER_ID'
        ORDER BY candidate.candidate_id
        """.trimIndent(),
    ) { cursor ->
        Rg04ProjectedCandidate(
            cursor.string(0), cursor.string(1), cursor.string(2), cursor.string(3), cursor.string(4),
            cursor.long(5), cursor.getString(6),
        )
    }

    private fun candidateStatuses(): List<Rg04ProjectedCandidateStatus> = driver.rg04OracleRows(
        """
        SELECT candidate_id, status_sequence, status_id, status
        FROM rg04_import_candidate_status_fact
        WHERE ledger_id = '$RG04_LEDGER_ID'
        ORDER BY candidate_id, status_sequence, status_id
        """.trimIndent(),
    ) { cursor -> Rg04ProjectedCandidateStatus(cursor.string(0), cursor.long(1), cursor.string(2), cursor.string(3)) }

    private fun confirmations(): List<Rg04ProjectedConfirmation> {
        val importRoot = spec.purpose == "rg04_import_lifecycle"
        return if (importRoot) {
            driver.rg04OracleRows(
                """
                SELECT confirmation_id, request_id, candidate_id, transaction_id
                FROM rg04_import_confirmation
                WHERE ledger_id = '$RG04_LEDGER_ID'
                ORDER BY confirmation_id
                """.trimIndent(),
            ) { cursor ->
                Rg04ProjectedConfirmation(cursor.string(0), cursor.string(1), cursor.string(2), cursor.string(3), "CANDIDATE_CONFIRMATION")
            }
        } else {
            driver.rg04OracleRows(
                """
                SELECT confirmation_id, request_id, transaction_id
                FROM rg04_confirmation
                WHERE ledger_id = '$RG04_LEDGER_ID'
                ORDER BY confirmation_id
                """.trimIndent(),
            ) { cursor ->
                Rg04ProjectedConfirmation(cursor.string(0), cursor.string(1), null, cursor.string(2), "EXPLICIT_MANUAL_SAVE")
            }
        }
    }

    private fun evidence(): List<Rg04ProjectedEvidence> = driver.rg04OracleRows(
        """
        SELECT evidence_id, source_id, evidence_role, observed_at
        FROM rg04_import_evidence
        WHERE ledger_id = '$RG04_LEDGER_ID'
        ORDER BY evidence_id
        """.trimIndent(),
    ) { cursor -> Rg04ProjectedEvidence(cursor.string(0), cursor.string(1), cursor.string(2), cursor.string(3)) }

    private fun evidenceLinks(): List<Rg04ProjectedEvidenceLink> = driver.rg04OracleRows(
        """
        SELECT match_id, evidence_id, posting_id
        FROM rg04_import_evidence_match
        WHERE ledger_id = '$RG04_LEDGER_ID'
        ORDER BY match_id
        """.trimIndent(),
    ) { cursor -> Rg04ProjectedEvidenceLink(cursor.string(0), cursor.string(1), cursor.string(2)) }

    private fun relations(): List<Rg04ProjectedRelation> = driver.rg04OracleRows(
        """
        SELECT relation_id, relation_type
        FROM formal_relation
        WHERE ledger_id = '$RG04_LEDGER_ID'
        ORDER BY relation_id
        """.trimIndent(),
    ) { cursor -> Rg04ProjectedRelation(cursor.string(0), cursor.string(1)) }

    private fun relationMembers(): List<Rg04ProjectedRelationMember> = driver.rg04OracleRows(
        """
        SELECT relation_id, member_kind, transaction_id, posting_id
        FROM formal_relation_member
        WHERE ledger_id = '$RG04_LEDGER_ID'
        ORDER BY relation_id, member_kind, COALESCE(transaction_id, posting_id)
        """.trimIndent(),
    ) { cursor ->
        Rg04ProjectedRelationMember(cursor.string(0), cursor.string(1), cursor.getString(2), cursor.getString(3))
    }

    private fun compositions(): List<Rg04ProjectedComposition> = driver.rg04OracleRows(
        """
        SELECT relation_id, transaction_id, display_name, total_minor, currency_code, currency_precision
        FROM rg04_mixed_composition
        WHERE ledger_id = '$RG04_LEDGER_ID'
        ORDER BY relation_id
        """.trimIndent(),
    ) { cursor ->
        Rg04ProjectedComposition(cursor.string(0), cursor.string(1), cursor.string(2), cursor.long(3), cursor.string(4), cursor.long(5))
    }

    private fun compositionComponents(): List<Rg04ProjectedCompositionComponent> = driver.rg04OracleRows(
        """
        SELECT relation_id, posting_id, account_id, amount_minor, currency_code, currency_precision
        FROM rg04_mixed_composition_component
        WHERE ledger_id = '$RG04_LEDGER_ID'
        ORDER BY relation_id, posting_id
        """.trimIndent(),
    ) { cursor ->
        Rg04ProjectedCompositionComponent(cursor.string(0), cursor.string(1), cursor.string(2), cursor.long(3), cursor.string(4), cursor.long(5))
    }

    private fun reconciliations(): List<Rg04ProjectedReconciliation> = driver.rg04OracleRows(
        """
        SELECT reconciliation_id, posting_id, status
        FROM rg04_posting_reconciliation
        WHERE ledger_id = '$RG04_LEDGER_ID'
        ORDER BY reconciliation_id
        """.trimIndent(),
    ) { cursor -> Rg04ProjectedReconciliation(cursor.string(0), cursor.string(1), cursor.string(2)) }
}

private fun rg04Report(periodType: String, period: String, values: Map<String, Long>): JsonObject =
    rg04JsonObjectOf(
        "period_type" to JsonPrimitive(periodType),
        "period" to JsonPrimitive(period),
        "metrics" to JsonArray(RG04_REPORT_METRICS.map { metric ->
            if (metric == "budget") {
                rg04JsonObjectOf(
                    "metric" to JsonPrimitive(metric),
                    "applicability" to JsonPrimitive("not_applicable"),
                )
            } else {
                rg04JsonObjectOf(
                    "metric" to JsonPrimitive(metric),
                    "applicability" to JsonPrimitive("applicable"),
                    "currency" to JsonPrimitive(RG04_CURRENCY),
                    "amount" to JsonPrimitive(rg04Amount(values.getValue(metric), RG04_PRECISION)),
                )
            }
        }),
    )

private fun rg04InPeriod(value: String, periodType: String, period: String): Boolean = when (periodType) {
    "day" -> value.startsWith(period)
    "month" -> value.startsWith(period)
    else -> true
}

private fun rg04EvidenceType(role: String): String = when (role) {
    "ASSET_FUNDING_DEBIT" -> "asset_funding_debit"
    "KNOWN_ASSET_FUNDING_DEBIT" -> "asset_funding_debit"
    "LIABILITY_MIRROR" -> "credit_liability_mirror"
    else -> error("unknown RG-04 evidence role $role")
}

private fun <T> JdbcSqliteDriver.rg04OracleRows(sql: String, mapper: (SqlCursor) -> T): List<T> =
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

private fun rg04Amount(minor: Long, precision: Long): String =
    BigDecimal.valueOf(minor, precision.toInt()).setScale(precision.toInt()).toPlainString()

private fun rg04JsonObjectOf(vararg fields: Pair<String, JsonElement?>): JsonObject =
    JsonObject(fields.mapNotNull { (key, value) -> value?.let { key to it } }.toMap())

private fun JsonObject.rg04String(key: String): String = getValue(key).jsonPrimitive.content

private fun rg04FullStateRepositoryFile(relative: String): Path {
    var candidate = Path.of(System.getProperty("user.dir"))
    repeat(6) {
        if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)
        candidate = candidate.parent ?: error("repository root not found")
    }
    error("repository root not found")
}

private const val RG04_CASE_ID = "RG-04"
private const val RG04_OPENING_LOCATOR = "$.opening.transactions[*]"
private const val RG04_CURRENCY = "CNY"
private const val RG04_PRECISION = 2L
private const val RG04_LEDGER_ID = "ledger-a"
private val RG04_REPORT_METRICS = listOf(
    "balance_adjustment_net_worth_change",
    "budget",
    "cash_inflow",
    "cash_outflow",
    "consumption",
    "income",
    "internal_transfer_amount",
    "net_worth_change",
    "ordinary_expense",
    "ordinary_income",
)
