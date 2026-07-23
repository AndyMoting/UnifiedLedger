package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.*
import com.unifiedledger.data.db.LedgerDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.Properties
import kotlinx.serialization.json.*
import kotlin.test.*

class Rg04RawJsonEndToEndTest {
    @Test
    fun trackedV1ExecutesEighteenManualOperationsBeforeApprovedProjectionIsRead() {
        val raw = Files.readString(repositoryFile("golden/rules/rg-04.json"))
        val rawRoot = Json.parseToJsonElement(raw).jsonObject
        val case = assertIs<Rg04RawJsonDecodeResult.Success>(decodeRg04RawJson(raw)).value
        assertEquals(18, case.operations.size)
        assertEquals(8, case.deferredOperations.size)
        assertEquals(
            listOf(
                "$.manual_lifecycle.ordered_operations[*]" to "request-rg04-manual-purchase",
                "$.idempotency.retried_inputs[*]" to "request-rg04-manual-purchase",
                "$.manual_lifecycle.ordered_operations[*]" to "request-rg04-repayment",
                "$.idempotency.retried_inputs[*]" to "request-rg04-repayment",
            ),
            case.operations.take(4).map { it.source.locator to it.source.discriminator },
        )
        assertEquals(
            14,
            case.operations.drop(4).count {
                it.source.locator == "$.invalid_manual_inputs[*]" && it.source.rawId == it.source.discriminator
            },
        )
        assertEquals(setOf("ingest_mixed_payment_source", "confirm_mixed_payment_candidate", "merge_mixed_payment_mirror_evidence"), case.deferredOperations.map { it.action }.toSet())
        assertEquals(8, case.deferredOperations.map { it.source.locator to it.source.discriminator }.distinct().size)
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { setProperty("foreign_keys", "true") })
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val executor = ExecuteRg04Operation(SqlDelightRg04Store(database, driver, case.catalog, frozenIds(case)))
            val observed = mutableListOf<Rg04Projection>()
            val approvedRaw: String
            val executionOperations = case.operations.sortedWith(compareBy<Rg04DecodedOperation>(
                { rg04OperationIdentity(it, 0).rootId },
                { rg04OperationIdentity(it, 0).sequence },
            ))
            executionOperations.forEachIndexed { index, decoded ->
                val expected = decoded.expected
                val adapted = adaptRg04Operation(case, decoded)
                val outcome = when (adapted) {
                    is Rg04AdaptResult.Invalid -> Rg04ObservedOutcome("rejected", adapted.reason, "$.attempted_input.${adapted.field}", null)
                    is Rg04AdaptResult.Success -> when (val result = executor.execute(adapted.operation)) {
                        is Rg04ExecutionResult.Accepted -> Rg04ObservedOutcome("accepted", null, null, result)
                        is Rg04ExecutionResult.NoChange -> Rg04ObservedOutcome("no_change", "idempotent_replay", null, result)
                        is Rg04ExecutionResult.Rejected -> Rg04ObservedOutcome("rejected", result.error.name.lowercase(), "$.attempted_input.${result.field}", null)
                        Rg04ExecutionResult.RequestIdentityConflict -> error("fixture conflict")
                    }
                }
                val actual = projectObserved(case, decoded, index, outcome, database, rawRoot)
                observed += actual
                when (expected) {
                    Rg04Expected.Accepted -> assertEquals("accepted", actual.status)
                    Rg04Expected.NoChange -> assertEquals("no_change", actual.status)
                    is Rg04Expected.Rejected -> {
                        assertEquals(expected.reason, actual.reason)
                        assertEquals("$.attempted_input.${expected.field}", actual.fieldPath)
                    }
                }
            }
            assertEquals(2L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(5L, database.ledgerQueries.countPostings().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRg04Relations().executeAsOne())
            assertEquals(3L, database.ledgerQueries.countRg04RelationMembers().executeAsOne())
            assertEquals(2L, database.ledgerQueries.countRg04CompositionComponents().executeAsOne())
            assertEquals(4L, database.ledgerQueries.countRg04PostingReconciliations().executeAsOne())
            assertTrue(database.ledgerQueries.selectRg04PostingReconciliations().executeAsList().all { it.status == "PENDING" })
            assertEquals(listOf(12_000L, -7_000L, -5_000L, -5_000L, 5_000L), database.ledgerQueries.selectPersistedPostings().executeAsList().map { it.amount_minor })

            // The approved v2 file is deliberately loaded only after every v1 operation executed.
            approvedRaw = Files.readString(repositoryFile("docs/migrations/golden-v2/rg-04-expected.json"))
            val approvedProjection = approvedRaw.exactManualProjection(observed.map { it.id }.toSet())
            assertEquals(18, approvedProjection.size)
            assertEquals(approvedProjection, approvedProjection.canonicalOperationOrder())
            assertEquals(approvedProjection, observed)

            val swappedEmission = observed.toMutableList().apply {
                val first = this[0]
                this[0] = this[1]
                this[1] = first
            }
            assertFailsWith<AssertionError> { assertEquals(approvedProjection, swappedEmission) }
        } finally { driver.close() }
    }

    @Test
    fun trackedV1RejectsMissingOrMutatedOperationInventory() {
        val raw = Files.readString(repositoryFile("golden/rules/rg-04.json"))
        val missing = raw.replaceFirst("\"id\": \"zero-total\",", "\"removed_id\": \"zero-total\",")
        assertIs<Rg04RawJsonDecodeResult.Invalid>(decodeRg04RawJson(missing))
        val mutated = raw.replaceFirst("\"id\": \"merge-liability-mirror-evidence\",", "\"id\": \"mutated-mirror-operation\",")
        assertIs<Rg04RawJsonDecodeResult.Invalid>(decodeRg04RawJson(mutated))
    }

    @Test
    fun trackedV1StrictlyOwnsManualLifecycleMetadata() {
        val raw = Files.readString(repositoryFile("golden/rules/rg-04.json"))
        listOf(
            Triple("\"sequence\": 1", "\"sequence\": 99", "$.manual_lifecycle.ordered_operations[0].sequence"),
            Triple("\"sequence\": 2", "\"sequence\": 99", "$.manual_lifecycle.ordered_operations[1].sequence"),
            Triple("\"mode\": \"explicit_manual_save\"", "\"mode\": \"automatic\"", "$.manual_lifecycle.ordered_operations[0].confirmation.mode"),
            Triple("\"candidate\": null", "\"candidate\": {}", "$.manual_lifecycle.ordered_operations[0].candidate"),
        ).forEach { (from, to, path) ->
            val error = assertIs<Rg04RawJsonDecodeResult.Invalid>(decodeRg04RawJson(raw.replaceFirst(from, to))).error
            assertEquals(path, error.fieldPath)
            assertEquals(Rg04RawJsonContractErrorReason.INVALID_VALUE, error.reason)
        }
    }

    @Test
    fun operationSequenceComesFromFrozenOperationSemanticsRatherThanLoopPosition() {
        val raw = Files.readString(repositoryFile("golden/rules/rg-04.json"))
        val case = assertIs<Rg04RawJsonDecodeResult.Success>(decodeRg04RawJson(raw)).value
        val reordered = case.operations.take(4).reversed()

        assertEquals(
            listOf(4, 3, 2, 1),
            reordered.mapIndexed { index, operation -> rg04OperationIdentity(operation, index).sequence },
        )
    }
}

private data class Rg04ReturnedId(val kind: String, val id: String)
private data class Rg04EntityProjection(
    val transactions: List<String> = emptyList(),
    val transactionVersions: List<String> = emptyList(),
    val postingSets: List<String> = emptyList(),
    val postings: List<String> = emptyList(),
    val confirmations: List<String> = emptyList(),
    val relations: List<String> = emptyList(),
    val postingReconciliations: List<String> = emptyList(),
)
private data class Rg04Projection(
    val id: String,
    val rootId: String,
    val sequence: Int,
    val action: String,
    val operationClass: String,
    val input: JsonObject?,
    val attemptedInput: JsonObject?,
    val status: String,
    val reason: String?,
    val fieldPath: String?,
    val returnedIds: List<Rg04ReturnedId>,
    val added: Rg04EntityProjection,
)
private fun List<Rg04Projection>.canonicalOperationOrder(): List<Rg04Projection> =
    sortedWith(compareBy(Rg04Projection::rootId, Rg04Projection::sequence))
private data class Rg04ObservedOutcome(
    val status: String,
    val reason: String?,
    val fieldPath: String?,
    val result: Rg04ExecutionResult?,
)

private fun String.exactManualProjection(operationIds: Set<String>): List<Rg04Projection> {
    val root = kotlinx.serialization.json.Json.parseToJsonElement(this).jsonObject
    return root.getValue("operations").jsonArray
        .map { it.jsonObject }
        .filter { it.getValue("id").jsonPrimitive.content in operationIds }
        .map { operation ->
        val outcome = operation.getValue("outcome").jsonObject
        val changes = operation.getValue("deltas").jsonObject.getValue("entity_changes").jsonObject
        Rg04Projection(
            id = operation.getValue("id").jsonPrimitive.content,
            rootId = operation.getValue("root_id").jsonPrimitive.content,
            sequence = operation.getValue("sequence").jsonPrimitive.content.toInt(),
            action = operation.getValue("action_type").jsonPrimitive.content,
            operationClass = operation.getValue("operation_class").jsonPrimitive.content,
            input = operation["input"]?.takeUnless { it is JsonNull }?.jsonObject,
            attemptedInput = operation["attempted_input"]?.takeUnless { it is JsonNull }?.jsonObject,
            status = outcome.getValue("status").jsonPrimitive.content,
            reason = outcome["reason_code"]?.jsonPrimitive?.content,
            fieldPath = outcome["field_path"]?.jsonPrimitive?.content,
            returnedIds = operation.getValue("returned_ids").jsonArray.map { value ->
                val item = value.jsonObject
                Rg04ReturnedId(item.getValue("kind").jsonPrimitive.content, item.getValue("id").jsonPrimitive.content)
            },
            added = changes.addedProjection(),
        )
    }
}

private fun JsonObject.addedProjection() = Rg04EntityProjection(
    transactions = added("transactions"),
    transactionVersions = added("transaction_versions"),
    postingSets = added("posting_sets"),
    postings = added("postings"),
    confirmations = added("confirmations"),
    relations = added("relations"),
    postingReconciliations = added("posting_reconciliations"),
)
private fun JsonObject.added(name: String): List<String> =
    getValue(name).jsonObject.getValue("added_ids").jsonArray.map { it.jsonPrimitive.content }

private data class Rg04OperationIdentity(val rootId: String, val operationId: String, val sequence: Int)
private fun rg04OperationIdentity(decoded: Rg04DecodedOperation, @Suppress("UNUSED_PARAMETER") index: Int): Rg04OperationIdentity {
    val isInvalid = decoded.source.locator == "$.invalid_manual_inputs[*]"
    val rootLocator = if (isInvalid) "$.invalid_manual_inputs[*]" else "$.manual_lifecycle"
    val rootDiscriminator = if (isInvalid) decoded.source.discriminator else "request-rg04-manual-purchase"
    val rootId = rg04RootId(rootLocator, rootDiscriminator)
    val operationId = rg04MigrationId(rootId, "operation", decoded.source.locator, decoded.source.discriminator)
    val sequence = when {
        isInvalid -> 1
        decoded is Rg04DecodedOperation.Manual && decoded.expected is Rg04Expected.Accepted -> 1
        decoded is Rg04DecodedOperation.Manual && decoded.expected is Rg04Expected.NoChange -> 2
        decoded is Rg04DecodedOperation.Repayment && decoded.expected is Rg04Expected.Accepted -> 3
        decoded is Rg04DecodedOperation.Repayment && decoded.expected is Rg04Expected.NoChange -> 4
        else -> error("unsupported RG-04 manual operation sequence")
    }
    return Rg04OperationIdentity(rootId, operationId, sequence)
}

private fun projectObserved(
    case: Rg04RawJsonCase,
    decoded: Rg04DecodedOperation,
    index: Int,
    outcome: Rg04ObservedOutcome,
    database: LedgerDatabase,
    rawRoot: JsonObject,
): Rg04Projection {
    val identity = rg04OperationIdentity(decoded, index)
    val accepted = outcome.result is Rg04ExecutionResult.Accepted
    val returned = when (val result = outcome.result) {
        is Rg04ExecutionResult.Accepted -> listOf(Rg04ReturnedId("confirmation", result.confirmationId), Rg04ReturnedId("transaction", result.transactionId.value))
        is Rg04ExecutionResult.NoChange -> listOf(Rg04ReturnedId("confirmation", result.confirmationId), Rg04ReturnedId("transaction", result.transactionId.value))
        else -> emptyList()
    }
    val formal = when {
        !accepted -> Rg04EntityProjection()
        decoded is Rg04DecodedOperation.Manual -> actualEntities(
            database,
            case.manualIds.transactionId.value,
            case.manualIds.versionId.value,
            case.manualIds.postingSetId.value,
            listOf(case.manualIds.expensePostingId.value) + case.manualIds.fundingPostingIds.map { it.value },
            returned.first { it.kind == "confirmation" }.id,
            listOf(case.relationId),
        )
        else -> actualEntities(
            database,
            case.repaymentIds.transactionId.value,
            case.repaymentIds.versionId.value,
            case.repaymentIds.postingSetId.value,
            listOf(case.repaymentIds.assetPostingId.value, case.repaymentIds.liabilityPostingId.value),
            returned.first { it.kind == "confirmation" }.id,
            emptyList(),
        )
    }
    return Rg04Projection(
        identity.operationId,
        identity.rootId,
        identity.sequence,
        decoded.action.name.lowercase(),
        decoded.operationClass.name.lowercase(),
        normalizedInput(decoded),
        normalizedAttemptedInput(decoded, rawRoot),
        outcome.status,
        outcome.reason,
        outcome.fieldPath,
        returned,
        formal,
    )
}

private fun normalizedInput(decoded: Rg04DecodedOperation): JsonObject? = when (decoded) {
    is Rg04DecodedOperation.Manual -> if (decoded.operationClass == Rg04OperationClass.REJECTION) null else buildJsonObject {
        val input = decoded.input
        val settlement = requireNotNull(input.settlement)
        put("request_id", input.requestId.requiredText())
        put("occurred_at", input.occurredAt.requiredText())
        put("total_amount", input.totalAmount.requiredText())
        put("currency", input.currency.requiredText())
        put("category_id", input.categoryId.requiredText())
        put("settlement_explanation", buildJsonObject {
            put("original_amount", settlement.originalAmount)
            put("discount_amount", settlement.discountAmount)
            put("settled_amount", settlement.settledAmount)
        })
        put("asset_account_id", input.funding[0].accountId.requiredText())
        put("liability_account_id", input.funding[1].accountId.requiredText())
        put("asset_funding_amount", input.funding[0].amount.requiredText())
        put("liability_funding_amount", input.funding[1].amount.requiredText())
        put("explicit_confirmation", (input.explicitConfirmation as Rg04Field.Value).value)
    }
    is Rg04DecodedOperation.Repayment -> buildJsonObject {
        val input = decoded.input
        put("request_id", input.requestId.requiredText())
        put("occurred_at", input.occurredAt.requiredText())
        put("asset_account_id", input.assetAccountId.requiredText())
        put("liability_account_id", input.liabilityAccountId.requiredText())
        put("principal_amount", input.principalAmount.requiredText())
        put("currency", input.currency.requiredText())
        put("explicit_confirmation", (input.explicitConfirmation as Rg04Field.Value).value)
    }
}

private fun normalizedAttemptedInput(decoded: Rg04DecodedOperation, rawRoot: JsonObject): JsonObject? {
    if (decoded !is Rg04DecodedOperation.Manual || decoded.operationClass != Rg04OperationClass.REJECTION) return null
    val source = rawRoot.getValue("invalid_manual_inputs").jsonArray
        .map { it.jsonObject }
        .single { it.getValue("id").jsonPrimitive.content == decoded.source.rawId }
        .getValue("input").jsonObject
    return JsonObject(linkedMapOf<String, JsonElement>(
        "request_id" to JsonPrimitive(decoded.input.requestId.requiredText()),
    ).apply { putAll(source) })
}

private fun Rg04Field<String>.requiredText(): String = (this as Rg04Field.Value).value

private fun actualEntities(
    database: LedgerDatabase,
    transactionId: String,
    versionId: String,
    postingSetId: String,
    postingIds: List<String>,
    confirmationId: String,
    relationIds: List<String>,
): Rg04EntityProjection {
    assertTrue(database.ledgerQueries.selectPersistedTransaction().executeAsList().any { it.transaction_id == transactionId })
    assertTrue(database.ledgerQueries.selectPersistedVersions().executeAsList().any { it.version_id == versionId && it.posting_set_id == postingSetId })
    val persistedPostingIds = database.ledgerQueries.selectPersistedPostings().executeAsList().map { it.posting_id }.toSet()
    assertTrue(postingIds.all { it in persistedPostingIds })
    assertTrue(database.ledgerQueries.selectRg04Confirmations().executeAsList().any { it == confirmationId })
    val persistedRelations = database.ledgerQueries.selectRg04Relations().executeAsList().toSet()
    assertTrue(relationIds.all { it in persistedRelations })
    val reconciliationIds = database.ledgerQueries.selectRg04PostingReconciliations().executeAsList()
        .filter { it.posting_id in postingIds }
        .map { it.reconciliation_id }
        .sorted()
    return Rg04EntityProjection(
        listOf(transactionId),
        listOf(versionId),
        listOf(postingSetId),
        postingIds.sorted(),
        listOf(confirmationId),
        relationIds,
        reconciliationIds,
    )
}

private fun frozenIds(case: Rg04RawJsonCase) = object : Rg04IdentitySource {
    private val rootId = rg04RootId("$.manual_lifecycle", "request-rg04-manual-purchase")
    private val locator = "$.manual_lifecycle.ordered_operations[*]"
    override fun manual(requestId: RequestId) = Rg04ManualCommitIds(
        rg04MigrationId(rootId, "confirmation", "$locator.confirmation", requestId.value),
        case.manualIds.fundingPostingIds.map { rg04MigrationId(rootId, "posting_reconciliation", "$locator.expected.reconciliation", it.value) },
    )
    override fun repayment(requestId: RequestId) = Rg04RepaymentCommitIds(
        rg04MigrationId(rootId, "confirmation", "$locator.confirmation", requestId.value),
        listOf(case.repaymentIds.assetPostingId, case.repaymentIds.liabilityPostingId).map { rg04MigrationId(rootId, "posting_reconciliation", "$locator.expected.reconciliation", it.value) },
    )
}
private val RG04_V2_NAMESPACE: UUID = UUID.fromString("cfad3f84-edb1-5838-ae53-aae49684cf1a")
private fun rg04RootId(locator: String, discriminator: String): String =
    rg04Uuid5("RG-04\n@root\nroot\n$locator\noccurrence=$discriminator")
private fun rg04MigrationId(rootId: String, kind: String, locator: String, discriminator: String): String =
    rg04Uuid5("RG-04\n$rootId\n$kind\n$locator\noccurrence=$discriminator")
private fun rg04Uuid5(name: String): String {
    val namespace = ByteBuffer.allocate(16).putLong(RG04_V2_NAMESPACE.mostSignificantBits).putLong(RG04_V2_NAMESPACE.leastSignificantBits).array()
    val bytes = MessageDigest.getInstance("SHA-1").digest(namespace + name.toByteArray(Charsets.UTF_8)).copyOf(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val buffer = ByteBuffer.wrap(bytes)
    return UUID(buffer.long, buffer.long).toString()
}
private fun repositoryFile(relative: String): Path { var p = Path.of(System.getProperty("user.dir")); repeat(6) { if (Files.isRegularFile(p.resolve("settings.gradle.kts"))) return p.resolve(relative); p = p.parent ?: error("root") }; error("root") }
