package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.*
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class Rg05RawJsonEndToEndTest {
    @Test
    fun trackedV1ImportLifecycleDecodesAndExecutesWithoutASecondCashFlow() {
        assertEquals(8, importRootObservations().size)
    }

    @Test
    fun manualMergedPaymentDecodesAndExecutes() {
        assertEquals(2, manualRootObservations().size)
    }

    @Test
    fun invalidManualInputsAreRejectedWithoutStateChange() {
        assertEquals(15, invalidManualObservations().size)
    }

    @Test
    fun decodedIdentitiesAreDeterministicAndMatchTheFrozenContract() {
        val first = rg05DecodedCase()
        val second = rg05DecodedCase()
        assertEquals(first.importOperations, second.importOperations)
        assertEquals(first.manualIds, second.manualIds)

        val manualIds = assertNotNull(first.manualIds)
        val ingest = assertIs<Rg05PreparedOperation.Ingest>(first.importOperations[0])
        val confirm = assertIs<Rg05PreparedOperation.Confirm>(first.importOperations[1])

        // Frozen in docs/migrations/golden-v2/rg-05-expected.json.
        assertEquals("94539e72-e936-531a-a9a5-a5f5fb352ed7", manualIds.confirmationId)
        assertEquals("a2dbab61-27d1-5b76-950b-88c8b2f51f7c", manualIds.reconciliationId)
        assertEquals("6efb9958-d8ac-5dc1-a42e-932a94b8c27b", ingest.snapshot.pendingStatusId)
        assertEquals("de5933f3-ab27-567c-802e-4e4e2a636dff", confirm.snapshot.confirmedStatusId)
        assertEquals("f13b31d2-f853-5f03-b6a7-e801ab481d9c", confirm.confirmationId)
        assertEquals("e8eaaca9-8236-54b5-980a-0f060655bb90", confirm.reconciliationId)
    }

    @Test
    fun twentyFiveGoldenOperationsMatchTheExpectedContract() {
        val runtime = invalidManualObservations() + manualRootObservations() + importRootObservations()
        assertEquals(25, runtime.size)

        val expected = expectedContractObservations()
        assertEquals(25, expected.size)
        assertEquals(expected.groupingBy { it }.eachCount(), runtime.groupingBy { it }.eachCount())
    }

    /** Manual root: accepted once, then replayed under the same request id. */
    private fun manualRootObservations(): List<Rg05Observation> {
        val case = rg05DecodedCase()
        val ids = assertNotNull(case.manualIds)
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val executor = rg05Executor(database, driver, case.catalog)
            val adapted = assertIs<Rg05AdaptResult.Success>(adaptRg05Manual(case, case.manual, ids))
            val observed = mutableListOf<Rg05Observation>()

            val accepted = assertIs<Rg05ExecutionResult.Accepted>(database.observing(observed, "manual_merged_payment", case) { executor.execute(adapted.operation) })
            assertEquals(ids.confirmationId, accepted.confirmationId)
            assertEquals(TransactionId("tx-merged-rg05-manual"), accepted.transactionId)
            assertEquals("association-group-rg05-manual", accepted.relationId)
            assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(3L, database.ledgerQueries.countPostings().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRg05MergedPayments().executeAsOne())
            assertEquals(2L, database.ledgerQueries.countRg05Items().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRg05Relations().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRg05PostingReconciliations().executeAsOne())
            assertEquals(listOf("NONE", "NONE"), database.ledgerQueries.selectRg05RelationCompleteness("ledger-a", "association-group-rg05-manual").executeAsList())

            val replayed = assertIs<Rg05ExecutionResult.NoChange>(database.observing(observed, "manual_merged_payment", case) { executor.execute(adapted.operation) })
            assertEquals(accepted.confirmationId, replayed.confirmationId)
            assertEquals(accepted.transactionId, replayed.transactionId)
            assertEquals(accepted.relationId, replayed.relationId)
            assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(3L, database.ledgerQueries.countPostings().executeAsOne())
            assertEquals(2L, database.ledgerQueries.countRg05Items().executeAsOne())
            return observed
        }
    }

    /** Import root: ingest, replay, both allocation failures, confirm, replay, receipt merge, replay. */
    private fun importRootObservations(): List<Rg05Observation> {
        val case = rg05DecodedCase()
        assertEquals(3, case.importOperations.size)
        val ingest = assertIs<Rg05PreparedOperation.Ingest>(case.importOperations[0])
        val confirm = assertIs<Rg05PreparedOperation.Confirm>(case.importOperations[1])
        val receipt = assertIs<Rg05PreparedOperation.Receipt>(case.importOperations[2])

        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val executor = rg05Executor(database, driver, case.catalog)
            val observed = mutableListOf<Rg05Observation>()
            fun run(action: String, operation: Rg05PreparedOperation) =
                database.observing(observed, action, case) { executor.execute(operation) }

            val ingested = assertIs<Rg05ExecutionResult.IngestAccepted>(run("ingest_merged_payment_facts", ingest))
            assertEquals("candidate-rg05-imported", ingested.candidateId)
            assertEquals(0L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countPostings().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countRg05PostingReconciliations().executeAsOne())

            assertIs<Rg05ExecutionResult.IngestNoChange>(run("ingest_merged_payment_facts", ingest))
            assertEquals(1L, database.ledgerQueries.countRg05Candidates().executeAsOne())
            assertEquals(listOf(ingest.snapshot.pendingStatusId), database.ledgerQueries.selectRg05CandidateStatusIds().executeAsList())

            // Allocation totals below and above the 100.00 bank debit are both refused and must
            // leave the candidate pending with no formal effect at all.
            assertEquals(
                Rg05ExecutionResult.Rejected(Rg05ExecutionError.ALLOCATION_INCOMPLETE, "allocation_total"),
                run("confirm_merged_payment_candidate", reallocated(confirm, "request-rg05-allocation-incomplete", 4_000, 5_000, case.currency)),
            )
            assertEquals(
                Rg05ExecutionResult.Rejected(Rg05ExecutionError.ALLOCATION_CONFLICT, "allocation_total"),
                run("confirm_merged_payment_candidate", reallocated(confirm, "request-rg05-allocation-conflict", 4_000, 7_000, case.currency)),
            )
            assertEquals(0L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRg05CandidateStatuses().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRg05OperationRequests().executeAsOne())

            val confirmed = assertIs<Rg05ExecutionResult.Accepted>(run("confirm_merged_payment_candidate", confirm))
            assertEquals(confirm.confirmationId, confirmed.confirmationId)
            assertEquals(TransactionId("tx-merged-rg05-imported"), confirmed.transactionId)
            assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(3L, database.ledgerQueries.countPostings().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRg05PostingReconciliations().executeAsOne())
            assertEquals(
                listOf(ingest.snapshot.pendingStatusId, confirm.snapshot.confirmedStatusId),
                database.ledgerQueries.selectRg05CandidateStatusIds().executeAsList(),
            )
            assertEquals(listOf("COMPLETE", "NONE"), database.ledgerQueries.selectRg05RelationCompleteness("ledger-a", "association-group-rg05-imported").executeAsList())

            assertIs<Rg05ExecutionResult.NoChange>(run("confirm_merged_payment_candidate", confirm))
            assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(3L, database.ledgerQueries.countPostings().executeAsOne())

            // Mirror evidence for item B completes business evidence only: no second cash flow, no
            // new transaction or posting, and no change to financial reconciliation.
            val merged = assertIs<Rg05ExecutionResult.ReceiptAccepted>(run("merge_item_receipt_evidence", receipt))
            assertEquals("match-item-b-rg05", merged.evidenceLinkId)
            assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(3L, database.ledgerQueries.countPostings().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRg05PostingReconciliations().executeAsOne())
            assertEquals(listOf("COMPLETE", "COMPLETE"), database.ledgerQueries.selectRg05RelationCompleteness("ledger-a", "association-group-rg05-imported").executeAsList())

            assertIs<Rg05ExecutionResult.ReceiptNoChange>(run("merge_item_receipt_evidence", receipt))
            assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(3L, database.ledgerQueries.countPostings().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRg05PostingReconciliations().executeAsOne())
            return observed
        }
    }

    /** Every `invalid_manual_inputs` entry is its own rejection root and must leave no trace. */
    private fun invalidManualObservations(): List<Rg05Observation> {
        val case = rg05DecodedCase()
        val entries = rg05FixtureRoot()["invalid_manual_inputs"]!!.jsonArray
        assertEquals(15, entries.size)

        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val executor = rg05Executor(database, driver, case.catalog)
            val observed = mutableListOf<Rg05Observation>()

            entries.forEachIndexed { index, element ->
                val entry = element.jsonObject
                val id = entry.text("id")
                val expected = entry["expected"]!!.jsonObject
                val ids = invalidManualPreparedIds(index)
                when (val adapted = adaptRg05Manual(case, invalidManualInput(entry, index), ids)) {
                    is Rg05AdaptResult.Invalid ->
                        observed += Rg05Observation("rejection", "manual_merged_payment", "rejected", adapted.reason, "$.attempted_input.${adapted.field}", CONTRACT_CATEGORIES.associateWith { emptyList() }, emptyList())
                    is Rg05AdaptResult.Success -> {
                        val result = database.observing(observed, "manual_merged_payment", case) { executor.execute(adapted.operation) }
                        assertIs<Rg05ExecutionResult.Rejected>(result, "case $id must be rejected")
                    }
                }
                val actual = observed.last()
                assertEquals(expected.text("reason"), actual.reasonCode, "reason for case $id")
                assertEquals("$.attempted_input.${expected.text("field")}", actual.fieldPath, "field for case $id")
                assertTrue(actual.added.values.all { it.isEmpty() }, "case $id must not add entities: ${actual.added}")
            }

            assertEquals(0L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countPostings().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countRg05OperationRequests().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countRg05MergedPayments().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countRg05Items().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countRg05Relations().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countRg05PostingReconciliations().executeAsOne())
            return observed
        }
    }

    /** Runs one operation, records the entity delta it caused, and returns the raw result. */
    private fun LedgerDatabase.observing(
        sink: MutableList<Rg05Observation>,
        actionType: String,
        case: Rg05RawJsonCase,
        block: () -> Rg05ExecutionResult,
    ): Rg05ExecutionResult {
        val before = entitySnapshot()
        val result = block()
        val after = entitySnapshot()
        val added = CONTRACT_CATEGORIES.associateWith { category ->
            (after.getValue(category) - before.getValue(category).toSet()).sorted()
        }
        sink += Rg05Observation(
            operationClass = when {
                result is Rg05ExecutionResult.Rejected -> "rejection"
                actionType == "merge_item_receipt_evidence" -> "reconciliation"
                else -> "creation"
            },
            actionType = actionType,
            status = when (result) {
                is Rg05ExecutionResult.Rejected -> "rejected"
                is Rg05ExecutionResult.NoChange, is Rg05ExecutionResult.IngestNoChange, is Rg05ExecutionResult.ReceiptNoChange -> "no_change"
                Rg05ExecutionResult.RequestIdentityConflict -> fail("unexpected request identity conflict for $actionType")
                else -> "accepted"
            },
            reasonCode = when (result) {
                is Rg05ExecutionResult.Rejected -> result.error.name.lowercase()
                is Rg05ExecutionResult.NoChange, is Rg05ExecutionResult.IngestNoChange, is Rg05ExecutionResult.ReceiptNoChange -> "idempotent_replay"
                else -> null
            },
            fieldPath = (result as? Rg05ExecutionResult.Rejected)?.let { "$.attempted_input.${it.field}" },
            added = added,
            returnedIds = returnedIds(result, case),
        )
        return result
    }

    private fun LedgerDatabase.entitySnapshot(): Map<String, List<String>> {
        val versions = ledgerQueries.selectPersistedVersions().executeAsList()
        val items = ledgerQueries.selectRg05DomainEntityIds().executeAsList()
        return mapOf(
            "transactions" to ledgerQueries.selectPersistedTransaction().executeAsList().map { it.transaction_id },
            "transaction_versions" to versions.map { it.version_id },
            "posting_sets" to versions.map { it.posting_set_id }.distinct(),
            "postings" to ledgerQueries.selectPersistedPostings().executeAsList().map { it.posting_id },
            "sources" to ledgerQueries.selectRg05SourceIds().executeAsList(),
            "candidates" to ledgerQueries.selectRg05CandidateIds().executeAsList(),
            "confirmations" to ledgerQueries.selectRg05ConfirmationIds().executeAsList(),
            "evidence" to ledgerQueries.selectRg05EvidenceIds().executeAsList(),
            "evidence_links" to ledgerQueries.selectRg05EvidenceLinkIds().executeAsList(),
            "relations" to ledgerQueries.selectRg05RelationIds().executeAsList(),
            "domain_entities" to items.flatMap { listOf(it.consumption_id, it.allocation_id) },
            "posting_reconciliations" to ledgerQueries.selectRg05PostingReconciliationIds().executeAsList(),
        )
    }

    /**
     * The contract returns the identities the caller can act on next. Formal and relation identities
     * come back on the result; consumption, allocation and evidence-link identities are owned by the
     * prepared operation, so both sources are projected together.
     */
    private fun returnedIds(result: Rg05ExecutionResult, case: Rg05RawJsonCase): List<Pair<String, String>> = when (result) {
        is Rg05ExecutionResult.Accepted -> formalReturnedIds(result.confirmationId, result.transactionId, result.relationId, case)
        is Rg05ExecutionResult.NoChange -> formalReturnedIds(result.confirmationId, result.transactionId, result.relationId, case)
        is Rg05ExecutionResult.IngestAccepted -> result.sourceIds.map { "source" to it } + result.evidenceIds.map { "evidence" to it } + listOf("candidate" to result.candidateId)
        is Rg05ExecutionResult.IngestNoChange -> result.sourceIds.map { "source" to it } + result.evidenceIds.map { "evidence" to it } + listOf("candidate" to result.candidateId)
        is Rg05ExecutionResult.ReceiptAccepted -> listOf("source" to result.sourceId, "evidence" to result.evidenceId, "evidence_link" to result.evidenceLinkId)
        is Rg05ExecutionResult.ReceiptNoChange -> listOf("source" to result.sourceId, "evidence" to result.evidenceId, "evidence_link" to result.evidenceLinkId)
        is Rg05ExecutionResult.Rejected, Rg05ExecutionResult.RequestIdentityConflict -> emptyList()
    }.canonical()

    private fun formalReturnedIds(confirmationId: String, transactionId: TransactionId, relationId: String, case: Rg05RawJsonCase): List<Pair<String, String>> {
        val confirm = case.importOperations.filterIsInstance<Rg05PreparedOperation.Confirm>().single()
        val imported = relationId == confirm.relationId
        val consumptions = if (imported) confirm.consumptionIds else requireNotNull(case.manualIds).consumptionIds
        val allocations = if (imported) confirm.allocationIds else requireNotNull(case.manualIds).allocationIds
        val candidate = if (imported) listOf("candidate" to confirm.snapshot.candidateId) else emptyList()
        val links = if (imported) listOf("evidence_link" to confirm.bankEvidenceLinkId) + confirm.itemEvidenceLinkIds.values.map { "evidence_link" to it } else emptyList()
        return candidate +
            listOf("confirmation" to confirmationId, "transaction" to transactionId.value, "relation" to relationId) +
            consumptions.values.map { "domain_entity" to it } +
            allocations.values.map { "domain_entity" to it } +
            links
    }

    private fun expectedContractObservations(): List<Rg05Observation> {
        val root = Json.parseToJsonElement(Files.readString(rg05RepositoryFile("docs/migrations/golden-v2/rg-05-expected.json"))).jsonObject
        return root["operations"]!!.jsonArray.map { element ->
            val operation = element.jsonObject
            val outcome = operation["outcome"]!!.jsonObject
            val changes = operation["deltas"]!!.jsonObject["entity_changes"]!!.jsonObject
            fun addedIds(category: String) = changes[category]!!.jsonObject["added_ids"]!!.jsonArray.map { it.jsonPrimitive.content }.sorted()
            // Categories the RG-05 runtime never writes must stay empty, so that comparing only the
            // modelled categories cannot hide an unexpected entity.
            UNMODELLED_CATEGORIES.forEach { assertEquals(emptyList(), addedIds(it), "$it must stay empty in RG-05") }
            Rg05Observation(
                operationClass = operation.text("operation_class"),
                actionType = operation.text("action_type"),
                status = outcome.text("status"),
                reasonCode = outcome["reason_code"]?.jsonPrimitive?.content,
                fieldPath = outcome["field_path"]?.jsonPrimitive?.content,
                added = CONTRACT_CATEGORIES.associateWith { addedIds(it) },
                returnedIds = operation["returned_ids"]!!.jsonArray.map {
                    val entry = it.jsonObject
                    entry.text("kind") to entry.text("id")
                }.canonical(),
            )
        }
    }

    private fun invalidManualInput(entry: JsonObject, index: Int): Rg05ManualInput {
        val input = entry["input"]!!.jsonObject
        return Rg05ManualInput(
            Rg05Field.Value("request-rg05-invalid-$index"),
            Rg05Field.Value(INVALID_PAYMENT_AT),
            Rg05Field.Value(input.text("total_amount")),
            Rg05Field.Value(input.text("currency")),
            Rg05Field.Value(input.text("funding_account_id")),
            input["items"]!!.jsonArray.map { element ->
                val item = element.jsonObject
                Rg05ItemInput(
                    Rg05Field.Value(item.text("id")),
                    Rg05Field.Value(item.text("amount")),
                    Rg05Field.Value(item.text("currency")),
                    when (val category = item["category_id"]) {
                        null, JsonNull -> Rg05Field.Null
                        else -> Rg05Field.Value(category.jsonPrimitive.content)
                    },
                    Rg05Field.Value(item.text("id")),
                    Rg05Field.Value(INVALID_OBSERVED_AT),
                )
            },
            Rg05Field.Value(true),
        )
    }

    private fun invalidManualPreparedIds(index: Int) = Rg05PreparedIds(
        MergedPaymentExpenseIds(
            TransactionId("tx-rg05-invalid-$index"),
            TransactionVersionId("version-rg05-invalid-$index"),
            PostingSetId("posting-set-rg05-invalid-$index"),
            listOf(PostingId("posting-expense-a-rg05-invalid-$index"), PostingId("posting-expense-b-rg05-invalid-$index")),
            PostingId("posting-asset-rg05-invalid-$index"),
        ),
        "association-group-rg05-invalid-$index",
        "confirmation-rg05-invalid-$index",
        "reconciliation-rg05-invalid-$index",
    )

    private fun reallocated(confirm: Rg05PreparedOperation.Confirm, requestId: String, first: Long, second: Long, currency: CurrencyUnit) =
        confirm.copy(
            snapshot = confirm.snapshot.copy(
                requestId = RequestId(requestId),
                allocations = confirm.snapshot.allocations.mapIndexed { index, allocation ->
                    allocation.copy(amount = Money.ofMinor(if (index == 0) first else second, currency))
                },
            ),
        )

    private fun rg05Executor(database: LedgerDatabase, driver: JdbcSqliteDriver, catalog: LedgerCatalog) =
        ExecuteRg05Operation(SqlDelightRg05Store(database, driver, catalog, FALLBACK_IDENTITY))

    private fun rg05DecodedCase(): Rg05RawJsonCase =
        assertIs<Rg05RawJsonDecodeResult.Success>(decodeRg05RawJson(Files.readString(rg05RepositoryFile("golden/rules/rg-05.json")))).value

    private fun rg05FixtureRoot(): JsonObject =
        Json.parseToJsonElement(Files.readString(rg05RepositoryFile("golden/rules/rg-05.json"))).jsonObject

    private fun JsonObject.text(name: String) = this[name]!!.jsonPrimitive.content

    private companion object {
        const val INVALID_PAYMENT_AT = "2026-04-10T18:30:00+08:00"
        const val INVALID_OBSERVED_AT = "2026-04-08T10:00:00+08:00"
        val CONTRACT_CATEGORIES = listOf(
            "transactions", "transaction_versions", "posting_sets", "postings", "sources", "candidates",
            "confirmations", "evidence", "evidence_links", "relations", "domain_entities", "posting_reconciliations",
        )
        val UNMODELLED_CATEGORIES = listOf("catalog_accounts", "catalog_categories", "audit_links")
        val FALLBACK_IDENTITY = object : Rg05IdentitySource {
            override fun manual(requestId: RequestId) =
                Rg05ManualCommitIds("confirmation-${requestId.value}", "reconciliation-${requestId.value}")
        }
    }
}

private fun List<Pair<String, String>>.canonical() = sortedWith(compareBy({ it.first }, { it.second }))

private data class Rg05Observation(
    val operationClass: String,
    val actionType: String,
    val status: String,
    val reasonCode: String?,
    val fieldPath: String?,
    val added: Map<String, List<String>>,
    val returnedIds: List<Pair<String, String>>,
)

private fun rg05RepositoryFile(relative: String): Path {
    var path = Path.of(System.getProperty("user.dir"))
    repeat(6) {
        if (Files.isRegularFile(path.resolve("settings.gradle.kts"))) return path.resolve(relative)
        path = path.parent ?: error("repository root not found")
    }
    error("repository root not found")
}
