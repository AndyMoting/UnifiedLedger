package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.CandidateId
import com.unifiedledger.application.CandidateStatus
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.ExecuteRg03Operation
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.Rg03ActionType
import com.unifiedledger.application.Rg03AdaptResult
import com.unifiedledger.application.Rg03AdapterContext
import com.unifiedledger.application.Rg03ContractErrorReason
import com.unifiedledger.application.Rg03DecodedOperation
import com.unifiedledger.application.Rg03ExecutionResult
import com.unifiedledger.application.Rg03ExpectedOutcome
import com.unifiedledger.application.Rg03JsonField
import com.unifiedledger.application.Rg03RawJsonCase
import com.unifiedledger.application.Rg03RawJsonDecodeResult
import com.unifiedledger.application.adaptRg03Operation
import com.unifiedledger.application.decodeRg03RawJson
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountTransferIds
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class Rg03RawJsonEndToEndTest {
    @Test
    fun `tracked v1 executes manual and imported transfer lifecycle through SQLDelight`() {
        val raw = Files.readString(rg03RepositoryFile("golden/rules/rg-03.json"))

        val decoded = assertIs<Rg03RawJsonDecodeResult.Success>(decodeRg03RawJson(raw)).value

        val context =

            Rg03AdapterContext(
                ledgerId = decoded.ledgerId,
                currency = decoded.currency,
                caseTimeZone = decoded.timezone,
                validNumericOffset = "+08:00",
            )

        val driver =

            JdbcSqliteDriver(
                JdbcSqliteDriver.IN_MEMORY,
                Properties().apply {
                    setProperty("foreign_keys", "true")
                },
            )

        try {
            LedgerDatabase.Schema.create(driver)

            val database = LedgerDatabase(driver)

            val store = SqlDelightRg03TransferStore(database, driver, decoded.catalog, rg03FrozenIdentitySource())

            val executor = ExecuteRg03Operation(store, store, store)

            assertIs<Rg03ExecutionResult.Accepted>(executor.execute(decoded.operations[0], context))

            assertIs<Rg03ExecutionResult.NoChange>(executor.execute(decoded.operations[5], context))

            assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())

            assertEquals(3L, database.ledgerQueries.countPostings().executeAsOne())

            assertEquals(
                listOf(-6_000L, 5_900L, 100L),
                database.ledgerQueries
                    .selectPersistedPostings()
                    .executeAsList()
                    .map { it.amount_minor },
            )

            assertEquals(
                listOf("PENDING", "PENDING"),
                database.ledgerQueries
                    .selectRg03PostingReconciliations()
                    .executeAsList()
                    .map { it.status },
            )

            assertIs<Rg03ExecutionResult.Accepted>(executor.execute(decoded.operations[1], context))

            assertIs<Rg03ExecutionResult.NoChange>(executor.execute(decoded.operations[6], context))

            assertEquals(1L, database.ledgerQueries.countRg03Candidates().executeAsOne())

            assertEquals(1L, database.ledgerQueries.countRg03CandidateStatuses().executeAsOne())

            assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())

            assertIs<Rg03ExecutionResult.Accepted>(executor.execute(decoded.operations[2], context))

            assertIs<Rg03ExecutionResult.NoChange>(executor.execute(decoded.operations[7], context))

            assertEquals(
                CandidateStatus.CONFIRMED,
                store.load(decoded.ledgerId, CandidateId("candidate-transfer-rg03"))?.status,
            )

            assertEquals(2L, database.ledgerQueries.countTransactions().executeAsOne())

            assertEquals(6L, database.ledgerQueries.countPostings().executeAsOne())

            assertEquals(1L, database.ledgerQueries.countRg03EvidenceLinks().executeAsOne())

            val partial =

                database.ledgerQueries
                    .selectRg03PostingReconciliations()
                    .executeAsList()
                    .associate { it.posting_id to it.status }

            assertEquals("MATCHED", partial.getValue("posting-source-rg03-imported"))

            assertEquals("PENDING", partial.getValue("posting-destination-rg03-imported"))

            assertIs<Rg03ExecutionResult.Accepted>(executor.execute(decoded.operations[3], context))

            assertIs<Rg03ExecutionResult.NoChange>(executor.execute(decoded.operations[8], context))

            assertEquals(2L, database.ledgerQueries.countRg03SourceRecords().executeAsOne())

            assertEquals(2L, database.ledgerQueries.countRg03Evidence().executeAsOne())

            assertEquals(2L, database.ledgerQueries.countRg03EvidenceLinks().executeAsOne())

            val matched =

                database.ledgerQueries
                    .selectRg03PostingReconciliations()
                    .executeAsList()
                    .associate { it.posting_id to it.status }

            assertEquals("MATCHED", matched.getValue("posting-source-rg03-imported"))

            assertEquals("MATCHED", matched.getValue("posting-destination-rg03-imported"))

            assertEquals(2L, database.ledgerQueries.countTransactions().executeAsOne())

            assertEquals(6L, database.ledgerQueries.countPostings().executeAsOne())

            assertIs<Rg03ExecutionResult.Accepted>(executor.execute(decoded.operations[4], context))

            assertIs<Rg03ExecutionResult.NoChange>(executor.execute(decoded.operations[9], context))

            val incomplete =

                checkNotNull(
                    store.load(decoded.ledgerId, CandidateId("candidate-transfer-rg03-unknown-debit")),
                )

            assertEquals(CandidateStatus.PENDING_CONFIRMATION, incomplete.status)

            assertNull(incomplete.destinationAccountId)

            assertNull(incomplete.destinationCredit)

            assertNull(incomplete.fee)

            assertEquals(3L, database.ledgerQueries.countRg03SourceRecords().executeAsOne())

            assertEquals(1L, database.ledgerQueries.countRg03CompleteSourceDetails().executeAsOne())

            assertEquals(2L, database.ledgerQueries.countRg03Candidates().executeAsOne())

            assertEquals(3L, database.ledgerQueries.countRg03CandidateStatuses().executeAsOne())

            assertEquals(3L, database.ledgerQueries.countRg03Evidence().executeAsOne())

            assertEquals(2L, database.ledgerQueries.countTransactions().executeAsOne())

            assertEquals(6L, database.ledgerQueries.countPostings().executeAsOne())

            decoded.operations.drop(10).forEach { operation ->

                val before = database.rg03Counts()

                executor.assertRejected(operation, context)

                assertEquals(before, database.rg03Counts())
            }

            assertEquals(20, decoded.operations.size)

            assertEquals(false, decoded.supportsCombinationTransfer)

            val approvedV2 =

                Files.readString(
                    rg03RepositoryFile("docs/migrations/golden-v2/rg-03-expected.json"),
                )

            assertEquals(decoded.oracleSignatures(), approvedV2.oracleSignatures())
        } finally {
            driver.close()
        }
    }
}

private data class Rg03ObservedRejection(
    val field: String,
    val reason: String,
)

private fun ExecuteRg03Operation.assertRejected(
    operation: Rg03DecodedOperation,
    context: Rg03AdapterContext,
) {
    val expected = assertIs<Rg03ExpectedOutcome.Rejected>(operation.expected)

    val observed =

        when (val adapted = adaptRg03Operation(context, operation)) {
            is Rg03AdaptResult.Invalid ->

                Rg03ObservedRejection(
                    adapted.error.fieldPath.substringAfterLast('.'),
                    when (adapted.error.reason) {
                        Rg03ContractErrorReason.MISSING_REQUIRED_FIELD,

                        Rg03ContractErrorReason.NULL_NOT_ALLOWED,

                        -> "required"

                        else ->

                            adapted.error.reason.name
                                .lowercase()
                    },
                )

            is Rg03AdaptResult.Success -> {
                val rejected = assertIs<Rg03ExecutionResult.Rejected>(execute(adapted.command))

                Rg03ObservedRejection(checkNotNull(rejected.field), rejected.error.name.lowercase())
            }
        }

    assertEquals(Rg03ObservedRejection(expected.field, expected.reason), observed)
}

private data class Rg03DatabaseCounts(
    val requests: Long,
    val receipts: Long,
    val transactions: Long,
    val versions: Long,
    val postingSets: Long,
    val postings: Long,
    val sources: Long,
    val completeSources: Long,
    val candidates: Long,
    val candidateStatuses: Long,
    val confirmations: Long,
    val evidence: Long,
    val evidenceLinks: Long,
    val reconciliations: Long,
)

private fun LedgerDatabase.rg03Counts() =

    Rg03DatabaseCounts(
        ledgerQueries.countRg03OperationRequests().executeAsOne(),
        ledgerQueries.countRg03OperationReceipts().executeAsOne(),
        ledgerQueries.countTransactions().executeAsOne(),
        ledgerQueries.countVersions().executeAsOne(),
        ledgerQueries.countPostingSets().executeAsOne(),
        ledgerQueries.countPostings().executeAsOne(),
        ledgerQueries.countRg03SourceRecords().executeAsOne(),
        ledgerQueries.countRg03CompleteSourceDetails().executeAsOne(),
        ledgerQueries.countRg03Candidates().executeAsOne(),
        ledgerQueries.countRg03CandidateStatuses().executeAsOne(),
        ledgerQueries.countRg03Confirmations().executeAsOne(),
        ledgerQueries.countRg03Evidence().executeAsOne(),
        ledgerQueries.countRg03EvidenceLinks().executeAsOne(),
        ledgerQueries.countRg03PostingReconciliations().executeAsOne(),
    )

private data class Rg03OracleSignature(
    val action: String,
    val status: String,
    val reason: String?,
    val field: String?,
) : Comparable<Rg03OracleSignature> {
    override fun compareTo(other: Rg03OracleSignature): Int = toString().compareTo(other.toString())
}

private fun Rg03RawJsonCase.oracleSignatures(): List<Rg03OracleSignature> =

    operations
        .map { operation ->

            val action =

                when (operation.actionType) {
                    Rg03ActionType.MANUAL_ACCOUNT_TRANSFER -> "manual_account_transfer"

                    Rg03ActionType.IMPORT_SOURCE_RECORD ->

                        if (

                            operation.input.completeness == Rg03JsonField.Value("missing_destination")

                        ) {
                            "import_incomplete_source"
                        } else {
                            "import_source_record"
                        }

                    Rg03ActionType.EXPLICIT_CANDIDATE_CONFIRMATION -> "confirm_account_transfer_candidate"

                    Rg03ActionType.IMPORT_MIRROR_RECORD -> "import_mirror_record"
                }

            when (val expected = operation.expected) {
                Rg03ExpectedOutcome.Accepted -> Rg03OracleSignature(action, "accepted", null, null)

                Rg03ExpectedOutcome.NoChange -> Rg03OracleSignature(action, "no_change", "idempotent_replay", null)

                is Rg03ExpectedOutcome.Rejected -> Rg03OracleSignature(action, "rejected", expected.reason, expected.field)
            }
        }.sorted()

private fun String.oracleSignatures(): List<Rg03OracleSignature> =

    Json
        .parseToJsonElement(this)
        .jsonObject
        .getValue("operations")
        .jsonArray
        .map { element ->

            val operation = element.jsonObject

            val outcome = operation.getValue("outcome").jsonObject

            Rg03OracleSignature(
                operation.getValue("action_type").jsonPrimitive.content,
                outcome.getValue("status").jsonPrimitive.content,
                outcome["reason_code"]?.jsonPrimitive?.content,
                outcome["field_path"]?.jsonPrimitive?.content?.substringAfterLast('.'),
            )
        }.sorted()

private fun ExecuteRg03Operation.execute(
    operation: Rg03DecodedOperation,
    context: Rg03AdapterContext,
): Rg03ExecutionResult {
    val command = assertIs<Rg03AdaptResult.Success>(adaptRg03Operation(context, operation)).command

    return execute(command)
}

private fun rg03RepositoryFile(relative: String): Path {
    var candidate = Path.of(System.getProperty("user.dir"))

    repeat(6) {
        if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)

        candidate = candidate.parent ?: error("repository root not found")
    }

    error("repository root not found")
}

private fun rg03FrozenIdentitySource(): Rg03IdentitySource =

    object : Rg03IdentitySource {
        override fun source(requestId: RequestId): Rg03SourceCommitIds =

            when (requestId.value) {
                "request-rg03-import-source" ->

                    Rg03SourceCommitIds(
                        CandidateId("candidate-transfer-rg03"),
                        "status-rg03-import-pending",
                    )

                "request-rg03-unknown-debit" ->

                    Rg03SourceCommitIds(
                        CandidateId("candidate-transfer-rg03-unknown-debit"),
                        "status-rg03-incomplete-pending",
                    )

                else -> error("No frozen source identity for ${requestId.value}")
            }

        override fun transfer(requestId: RequestId): Rg03TransferCommitIds =

            when (requestId.value) {
                "request-rg03-manual-create" -> frozenTransferIds("manual", "3e0b504e-bf9f-5dea-806c-0a9fbaf6aaff")

                "request-rg03-confirm-candidate" ->

                    frozenTransferIds(
                        "imported",
                        "ca460521-ed74-566d-bc26-83e46a775927",
                        "status-rg03-import-confirmed",
                    )

                else -> frozenTransferIds("rejected-${requestId.value}", "confirmation-rejected-${requestId.value}")
            }

        override fun mirror(requestId: RequestId): Rg03MirrorCommitIds =

            when (requestId.value) {
                "request-rg03-import-mirror" -> Rg03MirrorCommitIds("match-rg03-credit-mirror")

                else -> error("No frozen mirror identity for ${requestId.value}")
            }
    }

private fun frozenTransferIds(
    suffix: String,
    confirmationId: String,
    candidateStatusId: String? = null,
) = Rg03TransferCommitIds(
    ConfirmationId(confirmationId),
    com.unifiedledger.domain.AccountTransferIds(
        com.unifiedledger.domain.TransactionId("tx-transfer-rg03-$suffix"),
        com.unifiedledger.domain.TransactionVersionId("version-transfer-rg03-$suffix-v1"),
        com.unifiedledger.domain.PostingSetId("posting-set-transfer-rg03-$suffix"),
        com.unifiedledger.domain.PostingId("posting-source-rg03-$suffix"),
        com.unifiedledger.domain.PostingId("posting-destination-rg03-$suffix"),
        com.unifiedledger.domain.PostingId("posting-fee-rg03-$suffix"),
    ),
    "reconciliation-source-rg03-$suffix",
    "reconciliation-destination-rg03-$suffix",
    candidateStatusId,
    if (candidateStatusId == null) null else "match-rg03-debit",
)
