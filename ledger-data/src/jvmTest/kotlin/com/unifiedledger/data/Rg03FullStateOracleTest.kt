package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.*
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.*
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class Rg03FullStateOracleTest {
    @Test
    fun `v1 independently executes every root before v2 validates all operations states and deltas`() {
        val raw = Files.readString(rg03RepositoryFile("golden/rules/rg-03.json"))
        val v1 = Json.parseToJsonElement(raw).jsonObject
        val decoded = assertIs<Rg03RawJsonDecodeResult.Success>(decodeRg03RawJson(raw)).value
        val context = Rg03AdapterContext(decoded.ledgerId, decoded.currency, decoded.timezone, "+08:00")
        val specs = rg03RootSpecs(v1)

        val observed = specs.map { executeRg03Root(it, decoded, context, v1) }
        assertEquals(13, observed.size)
        assertEquals(20, observed.sumOf { it.operations.size })
        assertEquals(33, observed.sumOf { it.operations.size + 1 })

        val v2 = Json.parseToJsonElement(
            Files.readString(rg03RepositoryFile("docs/migrations/golden-v2/rg-03-expected.json")),
        ).jsonObject
        val expectedRoots = v2.getValue("roots").jsonArray.associateBy { it.jsonObject.string("purpose") }
        val expectedStates = v2.getValue("states").jsonArray.associateBy { it.jsonObject.string("id") }
        val expectedOperations = v2.getValue("operations").jsonArray.associateBy { it.jsonObject.string("id") }

        observed.forEach { root ->
            val expectedRoot = expectedRoots.getValue(root.spec.purpose).jsonObject
            assertEquals(root.spec.rootId, expectedRoot.string("id"), root.spec.purpose)
            assertEquals(expectedStates.getValue(expectedRoot.string("initial_state_id")), root.initialState)
            val operationIds = expectedRoot.getValue("operation_ids").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(root.operations.size, operationIds.size)
            root.operations.zip(operationIds).forEach { (actual, operationId) ->
                val expected = expectedOperations.getValue(operationId).jsonObject
                assertEquals(actual.operationId, operationId)
                assertEquals(expected.getValue("outcome"), actual.outcome, "$operationId outcome")
                assertEquals(expected.getValue("returned_ids"), actual.returnedIds, "$operationId returned_ids")
                assertEquals(expectedStates.getValue(expected.string("result_state_id")), actual.after, "$operationId state")
                assertEquals(expected.getValue("deltas"), rg03Deltas(actual.before, actual.after), "$operationId deltas")
                assertEquals(expected.getValue("status_changes"), rg03StatusChanges(actual.before, actual.after), "$operationId status_changes")
                if (expected.getValue("outcome").jsonObject.string("status") != "accepted") {
                    assertEquals(rg03StatePayload(actual.before), rg03StatePayload(actual.after), "$operationId residue")
                }
            }
        }
    }

    @Test
    fun `opaque persisted ids derive reconciliation from current posting set and confirmation kind`() {
        val transaction = ProjectedTransaction("transaction-opaque", "ACCOUNT_TRANSFER", "version-opaque")
        val summary = projectRg03ReconciliationSummary(
            transaction = transaction,
            versions = listOf(
                ProjectedVersion(
                    "version-opaque", transaction.id, 1, "posting-set-opaque",
                    "2026-01-20T02:00:00Z", "2026-01-20T02:00:00Z", "2026-01-20T02:00:00Z",
                ),
            ),
            postings = listOf(
                ProjectedPosting("posting-alpha", "posting-set-opaque", "asset-a", -6_000, "CNY", 2, "TRANSFER_PRINCIPAL_OUT", 1),
                ProjectedPosting("posting-beta", "posting-set-opaque", "asset-b", 5_900, "CNY", 2, "TRANSFER_PRINCIPAL_IN", 1),
                ProjectedPosting("posting-fee", "posting-set-opaque", "expense-fee", 100, "CNY", 2, "TRANSFER_FEE", 0),
                ProjectedPosting("posting-imported-decoy", "posting-set-other", "asset-c", 1, "CNY", 2, "TRANSFER_PRINCIPAL_IN", 1),
            ),
            confirmations = listOf(
                ProjectedConfirmation("confirmation-opaque", "request-opaque", null, transaction.id, "MANUAL_TRANSFER"),
            ),
            reconciliations = listOf(
                ProjectedReconciliation("reconciliation-alpha", "posting-alpha", "MATCHED"),
                ProjectedReconciliation("reconciliation-beta", "posting-beta", "PENDING"),
                ProjectedReconciliation("reconciliation-fee", "posting-fee", "MATCHED"),
                ProjectedReconciliation("reconciliation-decoy", "posting-imported-decoy", "MATCHED"),
            ),
        )

        assertEquals("partial", summary.value)
        assertEquals("$.manual_create.expected.reconciliation", summary.locator)
    }
}

private data class Rg03RootSpec(
    val purpose: String,
    val rootId: String,
    val initialStateId: String,
    val openingVersionId: String,
    val openingPostingSetId: String,
    val operations: List<Rg03OperationSpec>,
)

private data class Rg03OperationSpec(
    val index: Int,
    val locator: String,
    val discriminator: String,
    val stateLocator: String = locator,
)
private data class Rg03ObservedRoot(
    val spec: Rg03RootSpec,
    val initialState: JsonObject,
    val operations: List<Rg03ObservedOperation>,
)
private data class Rg03ObservedOperation(
    val operationId: String,
    val before: JsonObject,
    val after: JsonObject,
    val outcome: JsonObject,
    val returnedIds: JsonArray,
)

private fun rg03RootSpecs(v1: JsonObject): List<Rg03RootSpec> {
    fun root(
        purpose: String,
        rootLocator: String,
        rootDiscriminator: String,
        initialLocator: String,
        initialDiscriminator: String,
        operations: List<Rg03OperationSpec>,
    ): Rg03RootSpec {
        val rootId = rg03RootId(rootLocator, rootDiscriminator)
        return Rg03RootSpec(
            purpose,
            rootId,
            rg03MigrationId(rootId, "state", initialLocator, initialDiscriminator),
            rg03MigrationId(rootId, "transaction_version", "$.opening.transactions[*]", "tx-opening-rg03"),
            rg03MigrationId(rootId, "posting_set", "$.opening.transactions[*]", "tx-opening-rg03"),
            operations,
        )
    }

    val specs = mutableListOf<Rg03RootSpec>()
    specs += root(
        "rg03_manual_account_transfer", "$.manual_create", "request-rg03-manual-create",
        "$.opening.transactions[*]", "tx-opening-rg03",
        listOf(
            Rg03OperationSpec(0, "$.manual_create.request", "request-rg03-manual-create"),
            Rg03OperationSpec(5, "$.idempotency.repeated_manual_request_id", "request-rg03-manual-create"),
        ),
    )
    specs += root(
        "rg03_import_lifecycle", "$.import_lifecycle", "import-complete-source",
        "$.opening", "import-complete-source",
        listOf(
            Rg03OperationSpec(1, "$.import_lifecycle.ordered_operations[*]", "import-complete-source"),
            Rg03OperationSpec(6, "$.idempotency.repeated_source_request_id", "request-rg03-import-source"),
            Rg03OperationSpec(2, "$.import_lifecycle.ordered_operations[*]", "confirm-import-candidate"),
            Rg03OperationSpec(7, "$.idempotency.repeated_confirmation_request_id", "request-rg03-confirm-candidate"),
            Rg03OperationSpec(3, "$.import_lifecycle.ordered_operations[*]", "merge-mirror-evidence"),
            Rg03OperationSpec(8, "$.idempotency.repeated_mirror_request_id", "request-rg03-import-mirror"),
        ),
    )
    specs += root(
        "rg03_incomplete_source", "$.unknown_one_sided_debit", "request-rg03-unknown-debit",
        "$.opening", "request-rg03-unknown-debit",
        listOf(
            Rg03OperationSpec(4, "$.unknown_one_sided_debit.input", "request-rg03-unknown-debit"),
            Rg03OperationSpec(9, "$.unknown_one_sided_debit.retry.repeated_request_id", "request-rg03-unknown-debit"),
        ),
    )
    v1.getValue("invalid_manual_inputs").jsonArray.forEachIndexed { index, item ->
        val id = item.jsonObject.string("id")
        specs += root(
            "rg03_invalid_${id.replace('-', '_')}", "$.invalid_manual_inputs[*]", id,
            "$.opening", id,
            listOf(Rg03OperationSpec(10 + index, "$.invalid_manual_inputs[*]", id, "$.invalid_manual_inputs[*].expected")),
        )
    }
    return specs.sortedBy { it.rootId }
}

private fun executeRg03Root(
    spec: Rg03RootSpec,
    decoded: Rg03RawJsonCase,
    context: Rg03AdapterContext,
    v1: JsonObject,
): Rg03ObservedRoot {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { setProperty("foreign_keys", "true") })
    try {
        LedgerDatabase.Schema.create(driver)
        val database = LedgerDatabase(driver)
        seedRg03Opening(database, decoded.ledgerId, spec, v1)
        val store = SqlDelightRg03TransferStore(database, driver, decoded.catalog, rg03IdentitySource(spec))
        val executor = ExecuteRg03Operation(store, store, store)
        val operationIdsByRequest = linkedMapOf<String, String>()
        spec.operations.forEach { operationSpec ->
            val requestId = (decoded.operations[operationSpec.index].input.requestId as? Rg03JsonField.Value)?.value
            if (requestId != null && requestId !in operationIdsByRequest) {
                operationIdsByRequest[requestId] = rg03MigrationId(
                    spec.rootId, "operation", operationSpec.locator, operationSpec.discriminator,
                )
            }
        }
        val projector = Rg03StateProjector(database, decoded, v1, spec, operationIdsByRequest)
        val initial = projector.state(spec.initialStateId, null)
        val operations = spec.operations.map { operationSpec ->
            val operationId = rg03MigrationId(spec.rootId, "operation", operationSpec.locator, operationSpec.discriminator)
            val before = projector.state("before-$operationId", null)
            val result = executeRg03(decoded.operations[operationSpec.index], context, executor)
            val stateId = rg03MigrationId(spec.rootId, "state", operationSpec.stateLocator, operationSpec.discriminator)
            val after = projector.state(stateId, operationId)
            Rg03ObservedOperation(operationId, before, after, rg03Outcome(result), rg03ReturnedIds(result))
        }
        return Rg03ObservedRoot(spec, initial, operations)
    } finally {
        driver.close()
    }
}

private fun seedRg03Opening(database: LedgerDatabase, ledgerId: LedgerId, spec: Rg03RootSpec, v1: JsonObject) {
    val opening = v1.getValue("opening").jsonObject.getValue("transactions").jsonArray.single().jsonObject
    database.ledgerQueries.insertPostingSet(spec.openingPostingSetId, ledgerId.value)
    database.ledgerQueries.insertTransaction(opening.string("id"), ledgerId.value, "OPENING_BALANCE")
    database.ledgerQueries.insertTransactionVersion(
        spec.openingVersionId, opening.string("id"), ledgerId.value, 1,
        spec.openingPostingSetId, opening.string("occurred_at"), opening.string("occurred_at"),
        opening.string("occurred_at"), null,
    )
    database.ledgerQueries.insertTransactionCurrentVersion(opening.string("id"), ledgerId.value, spec.openingVersionId)
    opening.getValue("postings").jsonArray.forEachIndexed { index, element ->
        val posting = element.jsonObject
        database.ledgerQueries.insertPosting(
            posting.string("id"), spec.openingPostingSetId, ledgerId.value, index.toLong(),
            posting.string("account_id"), rg03Minor(posting.string("amount")), posting.string("currency"), 2,
        )
    }
}

private fun executeRg03(
    operation: Rg03DecodedOperation,
    context: Rg03AdapterContext,
    executor: ExecuteRg03Operation,
): Any = when (val adapted = adaptRg03Operation(context, operation)) {
    is Rg03AdaptResult.Success -> executor.execute(adapted.command)
    is Rg03AdaptResult.Invalid -> adapted
}

private fun rg03Outcome(result: Any): JsonObject = when (result) {
    is Rg03ExecutionResult.Accepted -> jsonObjectOf("status" to JsonPrimitive("accepted"))
    is Rg03ExecutionResult.NoChange -> jsonObjectOf(
        "status" to JsonPrimitive("no_change"),
        "reason_code" to JsonPrimitive("idempotent_replay"),
    )
    is Rg03AdaptResult.Invalid -> jsonObjectOf(
        "status" to JsonPrimitive("rejected"),
        "reason_code" to JsonPrimitive(
            when (result.error.reason) {
                Rg03ContractErrorReason.MISSING_REQUIRED_FIELD, Rg03ContractErrorReason.NULL_NOT_ALLOWED -> "required"
                else -> result.error.reason.name.lowercase()
            },
        ),
        "field_path" to JsonPrimitive("$.attempted_input.${result.error.fieldPath.substringAfterLast('.')}")
    )
    is Rg03ExecutionResult.Rejected -> jsonObjectOf(
        "status" to JsonPrimitive("rejected"),
        "reason_code" to JsonPrimitive(result.error.name.lowercase()),
        "field_path" to JsonPrimitive("$.attempted_input.${requireNotNull(result.field)}"),
    )
    Rg03ExecutionResult.RequestIdentityConflict -> error("unexpected request identity conflict")
    else -> error("unexpected result $result")
}

private fun rg03ReturnedIds(result: Any): JsonArray {
    val ids = when (result) {
        is Rg03ExecutionResult.Accepted -> result.returnedIds
        is Rg03ExecutionResult.NoChange -> result.returnedIds
        else -> emptyList()
    }
    return JsonArray(ids.map { jsonObjectOf("kind" to JsonPrimitive(it.kind.name.lowercase()), "id" to JsonPrimitive(it.id)) })
}

private fun rg03IdentitySource(spec: Rg03RootSpec): Rg03IdentitySource = object : Rg03IdentitySource {
    override fun source(requestId: RequestId): Rg03SourceCommitIds = when (requestId.value) {
        "request-rg03-import-source" -> Rg03SourceCommitIds(
            CandidateId("candidate-transfer-rg03"),
            rg03MigrationId(spec.rootId, "candidate_status", "$.import_lifecycle.ordered_operations[*].expected.candidate.status", "import-complete-source"),
        )
        "request-rg03-unknown-debit" -> Rg03SourceCommitIds(
            CandidateId("candidate-transfer-rg03-unknown-debit"),
            rg03MigrationId(spec.rootId, "candidate_status", "$.unknown_one_sided_debit.expected.candidate.status", "candidate-transfer-rg03-unknown-debit"),
        )
        else -> error("No source identities for ${requestId.value}")
    }

    override fun transfer(requestId: RequestId): Rg03TransferCommitIds = when (requestId.value) {
        "request-rg03-manual-create" -> rg03TransferIds(
            "manual",
            rg03MigrationId(spec.rootId, "confirmation", "$.manual_create.confirmation", requestId.value),
            rg03MigrationId(spec.rootId, "posting_reconciliation", "$.manual_create.expected.reconciliation", "posting-source-rg03-manual"),
            rg03MigrationId(spec.rootId, "posting_reconciliation", "$.manual_create.expected.reconciliation", "posting-destination-rg03-manual"),
        )
        "request-rg03-confirm-candidate" -> rg03TransferIds(
            "imported",
            rg03MigrationId(spec.rootId, "confirmation", "$.import_lifecycle.ordered_operations[*].expected.transaction.provenance.confirmation_ref", requestId.value),
            rg03MigrationId(spec.rootId, "posting_reconciliation", "$.import_lifecycle.ordered_operations[*].expected.reconciliation.posting-source-rg03-imported", "posting-source-rg03-imported"),
            rg03MigrationId(spec.rootId, "posting_reconciliation", "$.import_lifecycle.ordered_operations[*].expected.reconciliation.posting-destination-rg03-imported", "posting-destination-rg03-imported"),
            rg03MigrationId(spec.rootId, "candidate_status", "$.import_lifecycle.ordered_operations[*].expected.candidate_status", "confirm-import-candidate"),
            "match-rg03-debit",
        )
        else -> rg03TransferIds(
            "rejected-${requestId.value}",
            rg03MigrationId(spec.rootId, "confirmation", "$.invalid_manual_inputs[*]", requestId.value),
            rg03MigrationId(spec.rootId, "posting_reconciliation", "$.invalid_manual_inputs[*]", "source-${requestId.value}"),
            rg03MigrationId(spec.rootId, "posting_reconciliation", "$.invalid_manual_inputs[*]", "destination-${requestId.value}"),
        )
    }

    override fun mirror(requestId: RequestId): Rg03MirrorCommitIds =
        if (requestId.value == "request-rg03-import-mirror") Rg03MirrorCommitIds("match-rg03-credit-mirror")
        else error("No mirror identities for ${requestId.value}")
}

private fun rg03TransferIds(
    suffix: String,
    confirmationId: String,
    sourceReconciliationId: String,
    destinationReconciliationId: String,
    candidateStatusId: String? = null,
    evidenceLinkId: String? = null,
) = Rg03TransferCommitIds(
    ConfirmationId(confirmationId),
    AccountTransferIds(
        TransactionId("tx-transfer-rg03-$suffix"),
        TransactionVersionId("version-transfer-rg03-$suffix-v1"),
        PostingSetId("posting-set-transfer-rg03-$suffix"),
        PostingId("posting-source-rg03-$suffix"),
        PostingId("posting-destination-rg03-$suffix"),
        PostingId("posting-fee-rg03-$suffix"),
    ),
    sourceReconciliationId,
    destinationReconciliationId,
    candidateStatusId,
    evidenceLinkId,
)

private fun rg03RootId(locator: String, discriminator: String): String =
    goldenV2RootId("RG-03", locator, discriminator)

private fun rg03MigrationId(rootId: String, kind: String, locator: String, discriminator: String): String =
    goldenV2MigrationId("RG-03", rootId, kind, locator, discriminator)

private fun rg03Minor(amount: String): Long = BigDecimal(amount).movePointRight(2).longValueExact()

private data class ProjectedTransaction(val id: String, val kind: String, val currentVersionId: String)
private data class ProjectedVersion(
    val id: String, val transactionId: String, val number: Long, val postingSetId: String,
    val occurredAt: String, val statisticsAt: String, val effectiveAt: String,
)
private data class ProjectedPosting(
    val id: String, val postingSetId: String, val accountId: String, val minor: Long,
    val currency: String, val precision: Long, val role: String?, val eligible: Long?,
)
private data class ProjectedSource(
    val id: String, val evidenceId: String, val kind: String, val observedAt: String,
    val observedAccountId: String, val observedMinor: Long, val currency: String, val precision: Long,
    val destinationAccountId: String?, val destinationMinor: Long?, val feeMinor: Long?,
)
private data class ProjectedCandidate(
    val id: String, val sourceId: String, val confidence: String, val rule: String, val ruleVersion: Long,
    val transactionId: String?,
)
private data class ProjectedStatus(val candidateId: String, val sequence: Long, val id: String, val status: String)
private data class ProjectedConfirmation(
    val id: String, val requestId: String, val candidateId: String?, val transactionId: String, val kind: String,
)
private data class ProjectedEvidence(val id: String, val sourceId: String, val observedAt: String)
private data class ProjectedEvidenceLink(
    val id: String, val evidenceId: String, val postingId: String, val targetKind: String, val role: String,
)
private data class ProjectedReconciliation(val id: String, val postingId: String, val status: String)
private data class ProjectedReconciliationSummary(val value: String, val locator: String)

private class Rg03StateProjector(
    private val database: LedgerDatabase,
    private val decoded: Rg03RawJsonCase,
    private val v1: JsonObject,
    private val spec: Rg03RootSpec,
    private val operationIdsByRequest: Map<String, String>,
) {
    fun state(id: String, asOfOperationId: String?): JsonObject {
        val q = database.ledgerQueries
        val ledger = decoded.ledgerId.value
        val transactions = q.selectRg03AllTransactions(ledger) { transactionId, kind, currentVersionId ->
            ProjectedTransaction(transactionId, kind, currentVersionId)
        }.executeAsList()
        val versions = q.selectRg03AllVersions(ledger) { versionId, transactionId, versionNumber, postingSetId, occurredAt, statisticsAt, effectiveAt ->
            ProjectedVersion(versionId, transactionId, versionNumber, postingSetId, occurredAt, statisticsAt, effectiveAt)
        }.executeAsList()
        val postingSets = q.selectRg03AllPostingSets(ledger) { postingSetId, postingId -> postingSetId to postingId }.executeAsList()
        val postings = q.selectRg03AllPostings(ledger) { postingId, postingSetId, accountId, amountMinor, currencyCode, currencyPrecision, role, _, reconciliationEligible ->
            ProjectedPosting(postingId, postingSetId, accountId, amountMinor, currencyCode, currencyPrecision, role, reconciliationEligible)
        }.executeAsList()
        val sources = q.selectRg03AllSources(ledger) { sourceId, evidenceId, kind, observedAt, observedAccountId, observedMinor, currency, precision, destinationId, destinationMinor, feeMinor, _ ->
            ProjectedSource(sourceId, evidenceId, kind, observedAt, observedAccountId, observedMinor, currency, precision, destinationId, destinationMinor, feeMinor)
        }.executeAsList()
        val candidates = q.selectRg03AllCandidates(ledger) { candidateId, sourceId, _, _, confidence, rule, ruleVersion, transactionId ->
            ProjectedCandidate(candidateId, sourceId, confidence, rule, ruleVersion, transactionId)
        }.executeAsList()
        val statuses = q.selectRg03AllCandidateStatuses(ledger) { candidateId, sequence, statusId, status ->
            ProjectedStatus(candidateId, sequence, statusId, status)
        }.executeAsList()
        val confirmations = q.selectRg03AllConfirmations(ledger) { confirmationId, requestId, candidateId, transactionId, kind ->
            ProjectedConfirmation(confirmationId, requestId, candidateId, transactionId, kind)
        }.executeAsList()
        val evidence = q.selectRg03AllEvidence(ledger) { evidenceId, sourceId, observedAt ->
            ProjectedEvidence(evidenceId, sourceId, observedAt)
        }.executeAsList()
        val evidenceLinks = q.selectRg03AllEvidenceLinks(ledger) { linkId, evidenceId, postingId, targetKind, targetRole ->
            ProjectedEvidenceLink(linkId, evidenceId, postingId, targetKind, targetRole)
        }.executeAsList()
        val reconciliations = q.selectRg03AllReconciliations(ledger) { reconciliationId, postingId, status ->
            ProjectedReconciliation(reconciliationId, postingId, status)
        }.executeAsList()

        val catalog = projectCatalog()
        val confirmationByTransaction = confirmations.associateBy { it.transactionId }
        val balances = projectBalances(postings)
        val reports = projectReports(transactions, versions, postings)
        val derived = projectDerivedStatuses(transactions, versions, postings, statuses, confirmations, reconciliations)
        return jsonObjectOf(
            "id" to JsonPrimitive(id),
            "root_id" to JsonPrimitive(spec.rootId),
            "as_of_operation_id" to (asOfOperationId?.let(::JsonPrimitive) ?: JsonNull),
            "catalog" to catalog,
            "transactions" to JsonArray(transactions.map { transaction ->
                jsonObjectOf(
                    "id" to JsonPrimitive(transaction.id),
                    "type" to JsonPrimitive(transaction.kind.lowercase()),
                    "current_version_id" to JsonPrimitive(transaction.currentVersionId),
                )
            }),
            "transaction_versions" to JsonArray(versions.map { version ->
                jsonObjectOf(
                    "id" to JsonPrimitive(version.id),
                    "transaction_id" to JsonPrimitive(version.transactionId),
                    "version_number" to JsonPrimitive(version.number),
                    "posting_set_id" to JsonPrimitive(version.postingSetId),
                    "occurred_at" to JsonPrimitive(version.occurredAt),
                    "statistics_at" to JsonPrimitive(version.statisticsAt),
                    "effective_at" to JsonPrimitive(version.effectiveAt),
                    "confirmation_id" to confirmationByTransaction[version.transactionId]?.id?.let(::JsonPrimitive),
                )
            }),
            "posting_sets" to JsonArray(postingSets.groupBy({ it.first }, { it.second }).map { (setId, ids) ->
                jsonObjectOf("id" to JsonPrimitive(setId), "posting_ids" to JsonArray(ids.map(::JsonPrimitive)))
            }),
            "postings" to JsonArray(postings.map { posting ->
                jsonObjectOf(
                    "id" to JsonPrimitive(posting.id),
                    "posting_set_id" to JsonPrimitive(posting.postingSetId),
                    "account_id" to JsonPrimitive(posting.accountId),
                    "amount" to JsonPrimitive(rg03Amount(posting.minor, posting.precision)),
                    "currency" to JsonPrimitive(posting.currency),
                    "role" to posting.role?.lowercase()?.let(::JsonPrimitive),
                    "reconciliation_eligible" to JsonPrimitive(posting.eligible == 1L),
                )
            }),
            "sources" to JsonArray(sources.map(::projectSource)),
            "candidates" to JsonArray(candidates.map { projectCandidate(it, sources, statuses) }),
            "confirmations" to JsonArray(confirmations.map(::projectConfirmation)),
            "evidence" to JsonArray(evidence.map { item ->
                jsonObjectOf(
                    "id" to JsonPrimitive(item.id), "type" to JsonPrimitive("transfer_record"),
                    "source_ids" to JsonArray(listOf(JsonPrimitive(item.sourceId))),
                    "payload" to jsonObjectOf("observed_at" to JsonPrimitive(item.observedAt)),
                )
            }),
            "evidence_links" to JsonArray(evidenceLinks.map { link ->
                jsonObjectOf(
                    "id" to JsonPrimitive(link.id), "evidence_id" to JsonPrimitive(link.evidenceId),
                    "target_kind" to JsonPrimitive(link.targetKind.lowercase()),
                    "target_id" to JsonPrimitive(link.postingId), "role" to JsonPrimitive(link.role.lowercase()),
                )
            }),
            "relations" to JsonArray(emptyList()),
            "domain_entities" to JsonArray(emptyList()),
            "audit_links" to JsonArray(emptyList()),
            "posting_reconciliations" to JsonArray(reconciliations.map { item ->
                jsonObjectOf(
                    "id" to JsonPrimitive(item.id), "posting_id" to JsonPrimitive(item.postingId),
                    "status" to JsonPrimitive(item.status.lowercase()),
                )
            }),
            "balances" to balances,
            "reports" to reports,
            "derived_statuses" to derived,
        )
    }

    private fun projectCatalog(): JsonObject {
        val rawCatalog = v1.getValue("catalog").jsonObject
        val accountNames = rawCatalog.getValue("accounts").jsonArray.associate { it.jsonObject.string("id") to it.jsonObject.string("name") }
        val categoryNames = rawCatalog.getValue("categories").jsonArray.associate { it.jsonObject.string("id") to it.jsonObject.string("name") }
        return jsonObjectOf(
            "accounts" to JsonArray(decoded.catalog.accounts.sortedBy { it.id.value }.map { account ->
                jsonObjectOf(
                    "id" to JsonPrimitive(account.id.value), "name" to JsonPrimitive(accountNames.getValue(account.id.value)),
                    "kind" to JsonPrimitive(account.kind.name.lowercase()), "currency" to JsonPrimitive(account.currency.code),
                    "owned_by_user" to JsonPrimitive(account.ownedByUser), "real_account" to JsonPrimitive(account.realAccount),
                    "reconciliation_eligible" to JsonPrimitive(account.ownedByUser && account.realAccount),
                )
            }),
            "categories" to JsonArray(decoded.catalog.categories.sortedBy { it.id.value }.map { category ->
                jsonObjectOf(
                    "id" to JsonPrimitive(category.id.value), "name" to JsonPrimitive(categoryNames.getValue(category.id.value)),
                    "parent_id" to (category.parentId?.value?.let(::JsonPrimitive) ?: JsonNull),
                    "posting_account_id" to (category.postingAccountId?.value?.let(::JsonPrimitive) ?: JsonNull),
                    "active" to JsonPrimitive(category.active),
                )
            }),
        )
    }

    private fun projectSource(source: ProjectedSource): JsonObject {
        val payload = when (source.kind) {
            "ACCOUNT_CREDIT_OBSERVATION" -> jsonObjectOf(
                "account_id" to JsonPrimitive(source.observedAccountId),
                "credit_amount" to JsonPrimitive(rg03Amount(source.observedMinor, source.precision)),
                "currency" to JsonPrimitive(source.currency), "observed_at" to JsonPrimitive(source.observedAt),
                "evidence_id" to JsonPrimitive(source.evidenceId),
            )
            "COMPLETE_TRANSFER_SOURCE" -> jsonObjectOf(
                "source_account_id" to JsonPrimitive(source.observedAccountId),
                "destination_account_id" to JsonPrimitive(requireNotNull(source.destinationAccountId)),
                "source_debit_amount" to JsonPrimitive(rg03Amount(source.observedMinor, source.precision)),
                "destination_credit_amount" to JsonPrimitive(rg03Amount(requireNotNull(source.destinationMinor), source.precision)),
                "fee_amount" to JsonPrimitive(rg03Amount(requireNotNull(source.feeMinor), source.precision)),
                "currency" to JsonPrimitive(source.currency), "completeness" to JsonPrimitive("complete"),
                "observed_at" to JsonPrimitive(source.observedAt), "evidence_id" to JsonPrimitive(source.evidenceId),
            )
            else -> jsonObjectOf(
                "source_account_id" to JsonPrimitive(source.observedAccountId),
                "debit_amount" to JsonPrimitive(rg03Amount(source.observedMinor, source.precision)),
                "currency" to JsonPrimitive(source.currency), "completeness" to JsonPrimitive("missing_destination"),
                "observed_at" to JsonPrimitive(source.observedAt), "evidence_id" to JsonPrimitive(source.evidenceId),
            )
        }
        return jsonObjectOf(
            "id" to JsonPrimitive(source.id),
            "type" to JsonPrimitive(if (source.kind == "ACCOUNT_CREDIT_OBSERVATION") "account_credit_observation" else "account_transfer"),
            "payload" to payload,
        )
    }

    private fun projectCandidate(
        candidate: ProjectedCandidate,
        sources: List<ProjectedSource>,
        statuses: List<ProjectedStatus>,
    ): JsonObject {
        val source = sources.single { it.id == candidate.sourceId }
        val complete = source.kind == "COMPLETE_TRANSFER_SOURCE"
        val payload = jsonObjectOf(
            "source_account_id" to JsonPrimitive(source.observedAccountId),
            (if (complete) "destination_account_id" else "unused") to source.destinationAccountId?.let(::JsonPrimitive),
            (if (complete) "source_debit_amount" else "debit_amount") to JsonPrimitive(rg03Amount(source.observedMinor, source.precision)),
            "destination_credit_amount" to source.destinationMinor?.let { JsonPrimitive(rg03Amount(it, source.precision)) },
            "fee_amount" to source.feeMinor?.let { JsonPrimitive(rg03Amount(it, source.precision)) },
            "currency" to JsonPrimitive(source.currency),
            "evidence_refs" to JsonArray(listOf(JsonPrimitive(source.evidenceId))),
            "provenance" to jsonObjectOf(
                "rule" to JsonPrimitive(candidate.rule), "rule_version" to JsonPrimitive(candidate.ruleVersion),
            ),
            "requires_confirmation" to JsonArray(
                (if (complete) listOf("formal_transaction_creation") else listOf("destination_account_id", "formal_transaction_creation"))
                    .map(::JsonPrimitive),
            ),
            "transaction_id" to candidate.transactionId?.let(::JsonPrimitive),
        )
        return jsonObjectOf(
            "id" to JsonPrimitive(candidate.id), "type" to JsonPrimitive("account_transfer"),
            "source_ids" to JsonArray(listOf(JsonPrimitive(source.id))),
            "confidence" to JsonPrimitive(candidate.confidence), "payload" to payload,
            "status_history" to JsonArray(statuses.filter { it.candidateId == candidate.id }.map { status ->
                jsonObjectOf(
                    "id" to JsonPrimitive(status.id), "sequence" to JsonPrimitive(status.sequence),
                    "status" to JsonPrimitive(status.status.lowercase()),
                )
            }),
        )
    }

    private fun projectConfirmation(confirmation: ProjectedConfirmation): JsonObject {
        val operationId = operationIdsByRequest.getValue(confirmation.requestId)
        return jsonObjectOf(
            "id" to JsonPrimitive(confirmation.id),
            "type" to JsonPrimitive(if (confirmation.candidateId == null) "explicit_manual_save" else "candidate_confirmation"),
            "operation_id" to JsonPrimitive(operationId),
            "subject" to if (confirmation.candidateId == null) {
                jsonObjectOf("kind" to JsonPrimitive("operation"), "id" to JsonPrimitive(operationId))
            } else {
                jsonObjectOf("kind" to JsonPrimitive("candidate"), "id" to JsonPrimitive(confirmation.candidateId))
            },
            "payload" to JsonObject(emptyMap()),
        )
    }

    private fun projectBalances(postings: List<ProjectedPosting>): JsonArray = JsonArray(
        decoded.catalog.accounts.sortedBy { it.id.value }.map { account ->
            val amount = postings.filter { it.accountId == account.id.value }.sumOf { it.minor }
            jsonObjectOf(
                "account_id" to JsonPrimitive(account.id.value), "currency" to JsonPrimitive(account.currency.code),
                "amount" to JsonPrimitive(rg03Amount(amount, account.currency.precision.toLong())),
            )
        },
    )

    private fun projectReports(
        transactions: List<ProjectedTransaction>,
        versions: List<ProjectedVersion>,
        postings: List<ProjectedPosting>,
    ): JsonArray {
        val formalDates = transactions.filter { it.kind == "ACCOUNT_TRANSFER" }.map { transaction ->
            versions.single { it.id == transaction.currentVersionId }.occurredAt.substring(0, 10)
        }
        val days = (listOf("2026-01-20") + formalDates).distinct().sorted()
        val periods = days.map { "day" to it } + listOf("month" to "2026-01")
        return JsonArray(periods.map { (type, period) ->
            val matchingVersions = versions.filter { version ->
                transactions.any { it.kind == "ACCOUNT_TRANSFER" && it.currentVersionId == version.id } &&
                    if (type == "day") version.occurredAt.startsWith(period) else version.occurredAt.startsWith(period)
            }
            val setIds = matchingVersions.map { it.postingSetId }.toSet()
            val selected = postings.filter { it.postingSetId in setIds }
            val fee = selected.filter { it.role == "TRANSFER_FEE" }.sumOf { it.minor }
            val principal = selected.filter { it.role == "TRANSFER_PRINCIPAL_IN" }.sumOf { it.minor }
            val values = mapOf(
                "balance_adjustment_net_worth_change" to 0L, "budget" to 0L, "cash_inflow" to 0L,
                "cash_outflow" to fee, "consumption" to fee, "internal_transfer_amount" to principal,
                "net_worth_change" to -fee, "ordinary_expense" to fee, "ordinary_income" to 0L,
            )
            jsonObjectOf(
                "period_type" to JsonPrimitive(type), "period" to JsonPrimitive(period),
                "metrics" to JsonArray(values.toSortedMap().map { (metric, amount) ->
                    jsonObjectOf(
                        "metric" to JsonPrimitive(metric), "applicability" to JsonPrimitive("applicable"),
                        "currency" to JsonPrimitive("CNY"), "amount" to JsonPrimitive(rg03Amount(amount, 2)),
                    )
                }),
            )
        })
    }

    private fun projectDerivedStatuses(
        transactions: List<ProjectedTransaction>,
        versions: List<ProjectedVersion>,
        postings: List<ProjectedPosting>,
        statuses: List<ProjectedStatus>,
        confirmations: List<ProjectedConfirmation>,
        reconciliations: List<ProjectedReconciliation>,
    ): JsonArray {
        val values = mutableListOf<JsonObject>()
        statuses.groupBy { it.candidateId }.forEach { (candidateId, history) ->
            val latest = history.maxBy { it.sequence }
            values += jsonObjectOf(
                "id" to JsonPrimitive(latest.id), "target_kind" to JsonPrimitive("candidate"),
                "target_id" to JsonPrimitive(candidateId), "status_name" to JsonPrimitive("confirmation_status"),
                "value" to JsonPrimitive(latest.status.lowercase()),
            )
        }
        transactions.filter { it.kind == "ACCOUNT_TRANSFER" }.forEach { transaction ->
            val summary = projectRg03ReconciliationSummary(
                transaction, versions, postings, confirmations, reconciliations,
            )
            values += jsonObjectOf(
                "id" to JsonPrimitive(rg03MigrationId(spec.rootId, "derived_status", summary.locator, transaction.id)),
                "target_kind" to JsonPrimitive("transaction"), "target_id" to JsonPrimitive(transaction.id),
                "status_name" to JsonPrimitive("reconciliation_summary"), "value" to JsonPrimitive(summary.value),
            )
        }
        return JsonArray(values.sortedBy { it.string("id") })
    }
}

private fun projectRg03ReconciliationSummary(
    transaction: ProjectedTransaction,
    versions: List<ProjectedVersion>,
    postings: List<ProjectedPosting>,
    confirmations: List<ProjectedConfirmation>,
    reconciliations: List<ProjectedReconciliation>,
): ProjectedReconciliationSummary {
    val currentVersion = versions.single {
        it.id == transaction.currentVersionId && it.transactionId == transaction.id
    }
    val eligiblePostingIds = postings.filter {
        it.postingSetId == currentVersion.postingSetId && it.eligible == 1L
    }.map { it.id }.toSet()
    check(eligiblePostingIds.isNotEmpty()) { "Account transfer has no reconciliation-eligible postings" }
    val relevant = reconciliations.filter { it.postingId in eligiblePostingIds }
    val value = when {
        relevant.size == eligiblePostingIds.size && relevant.all { it.status == "MATCHED" } -> "matched"
        relevant.any { it.status == "MATCHED" } -> "partial"
        else -> "pending"
    }
    val confirmation = confirmations.single { it.transactionId == transaction.id }
    val locator = when {
        confirmation.kind == "MANUAL_TRANSFER" && confirmation.candidateId == null ->
            "$.manual_create.expected.reconciliation"
        confirmation.kind == "CANDIDATE_CONFIRMATION" && confirmation.candidateId != null ->
            "$.import_lifecycle.ordered_operations[*].expected.reconciliation.transaction"
        else -> error("Unsupported account transfer confirmation shape")
    }
    return ProjectedReconciliationSummary(value, locator)
}

private fun rg03Amount(minor: Long, precision: Long): String =
    BigDecimal.valueOf(minor, precision.toInt()).setScale(precision.toInt()).toPlainString()

private fun jsonObjectOf(vararg fields: Pair<String, JsonElement?>): JsonObject =
    JsonObject(fields.mapNotNull { (key, value) -> value?.let { key to it } }.toMap())

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

private val RG03_ENTITY_COLLECTIONS = linkedMapOf(
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

private fun rg03Deltas(before: JsonObject, after: JsonObject): JsonObject = jsonObjectOf(
    "entity_changes" to JsonObject(RG03_ENTITY_COLLECTIONS.mapValues { (_, path) ->
        val beforeItems = before.arrayAt(path).associateBy { it.jsonObject.string("id") }
        val afterItems = after.arrayAt(path).associateBy { it.jsonObject.string("id") }
        val beforeIds = beforeItems.keys
        val afterIds = afterItems.keys
        jsonObjectOf(
            "added_ids" to JsonArray((afterIds - beforeIds).sorted().map(::JsonPrimitive)),
            "changed_ids" to JsonArray((beforeIds intersect afterIds).filter { beforeItems[it] != afterItems[it] }.sorted().map(::JsonPrimitive)),
            "removed_ids" to JsonArray((beforeIds - afterIds).sorted().map(::JsonPrimitive)),
        )
    }),
    "value_changes" to jsonObjectOf(
        "balances" to rg03BalanceChanges(before, after),
        "reports" to rg03ReportChanges(before, after),
        "derived_statuses" to rg03DerivedChanges(before, after),
    ),
)

private fun rg03BalanceChanges(before: JsonObject, after: JsonObject): JsonArray {
    fun values(state: JsonObject) = state.getValue("balances").jsonArray.associateBy {
        val item = it.jsonObject
        item.string("account_id") to item.string("currency")
    }
    val old = values(before)
    val new = values(after)
    return JsonArray((old.keys + new.keys).distinct().sortedWith(compareBy({ it.first }, { it.second })).mapNotNull { key ->
        val oldAmount = old[key]?.jsonObject?.string("amount")
        val newAmount = new[key]?.jsonObject?.string("amount")
        if (oldAmount == newAmount) null else jsonObjectOf(
            "key" to jsonObjectOf("account_id" to JsonPrimitive(key.first), "currency" to JsonPrimitive(key.second)),
            "before" to (oldAmount?.let(::JsonPrimitive) ?: JsonNull),
            "after" to (newAmount?.let(::JsonPrimitive) ?: JsonNull),
        )
    })
}

private data class Rg03ReportKey(val type: String, val period: String, val metric: String, val currency: String)

private fun rg03ReportChanges(before: JsonObject, after: JsonObject): JsonArray {
    fun values(state: JsonObject): Map<Rg03ReportKey, JsonObject> = buildMap {
        state.getValue("reports").jsonArray.forEach { reportElement ->
            val report = reportElement.jsonObject
            report.getValue("metrics").jsonArray.forEach { metricElement ->
                val metric = metricElement.jsonObject
                put(
                    Rg03ReportKey(report.string("period_type"), report.string("period"), metric.string("metric"), metric.string("currency")),
                    JsonObject(metric.filterKeys { it != "metric" }),
                )
            }
        }
    }
    val old = values(before)
    val new = values(after)
    val comparator = compareBy<Rg03ReportKey>({ it.type }, { it.period }, { it.metric }, { it.currency })
    return JsonArray((old.keys + new.keys).distinct().sortedWith(comparator).mapNotNull { key ->
        if (old[key] == new[key]) null else jsonObjectOf(
            "key" to jsonObjectOf(
                "period_type" to JsonPrimitive(key.type), "period" to JsonPrimitive(key.period),
                "metric" to JsonPrimitive(key.metric), "currency" to JsonPrimitive(key.currency),
            ),
            "before" to (old[key] ?: JsonNull), "after" to (new[key] ?: JsonNull),
        )
    })
}

private data class Rg03DerivedKey(val kind: String, val targetId: String, val statusName: String)

private fun rg03DerivedChanges(before: JsonObject, after: JsonObject): JsonArray {
    val changes = rg03DerivedValueChanges(before, after)
    return JsonArray(changes.map { (key, old, new) ->
        jsonObjectOf(
            "key" to jsonObjectOf(
                "kind" to JsonPrimitive(key.kind), "target_id" to JsonPrimitive(key.targetId),
                "status_name" to JsonPrimitive(key.statusName),
            ),
            "before" to (old?.let(::JsonPrimitive) ?: JsonNull),
            "after" to (new?.let(::JsonPrimitive) ?: JsonNull),
        )
    })
}

private fun rg03StatusChanges(before: JsonObject, after: JsonObject): JsonArray = JsonArray(
    rg03DerivedValueChanges(before, after).map { (key, old, new) ->
        jsonObjectOf(
            "target_kind" to JsonPrimitive(key.kind), "target_id" to JsonPrimitive(key.targetId),
            "status_name" to JsonPrimitive(key.statusName),
            "before" to (old?.let(::JsonPrimitive) ?: JsonNull),
            "after" to (new?.let(::JsonPrimitive) ?: JsonNull),
        )
    },
)

private data class Rg03DerivedChange(val key: Rg03DerivedKey, val before: String?, val after: String?)

private fun rg03DerivedValueChanges(before: JsonObject, after: JsonObject): List<Rg03DerivedChange> {
    fun values(state: JsonObject) = state.getValue("derived_statuses").jsonArray.associate { element ->
        val item = element.jsonObject
        Rg03DerivedKey(item.string("target_kind"), item.string("target_id"), item.string("status_name")) to item.string("value")
    }
    val old = values(before)
    val new = values(after)
    val comparator = compareBy<Rg03DerivedKey>({ it.kind }, { it.targetId }, { it.statusName })
    return (old.keys + new.keys).distinct().sortedWith(comparator).mapNotNull { key ->
        if (old[key] == new[key]) null else Rg03DerivedChange(key, old[key], new[key])
    }
}

private fun JsonObject.arrayAt(path: List<String>): JsonArray {
    var current: JsonElement = this
    path.forEach { current = current.jsonObject.getValue(it) }
    return current.jsonArray
}

private fun rg03StatePayload(state: JsonObject): JsonObject =
    JsonObject(state.filterKeys { it !in setOf("id", "as_of_operation_id") })

private fun rg03RepositoryFile(relative: String): Path {
    var candidate = Path.of(System.getProperty("user.dir"))
    repeat(6) {
        if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)
        candidate = candidate.parent ?: error("repository root not found")
    }
    error("repository root not found")
}
