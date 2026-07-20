package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.ConfirmedExpenseTransactionFactory
import com.unifiedledger.application.ConfirmedManualExpenseCommit
import com.unifiedledger.application.ConfirmedManualExpenseCommitPort
import com.unifiedledger.application.ConfirmedManualExpenseCommitIds
import com.unifiedledger.application.ConfirmedManualExpenseIdSource
import com.unifiedledger.application.ExecuteConfirmedManualExpense
import com.unifiedledger.application.ExecuteManualExpenseSave
import com.unifiedledger.application.ExecuteConfirmedTransactionNoteUpdate
import com.unifiedledger.application.ExplicitlyConfirmedTransactionNoteUpdate
import com.unifiedledger.application.ConfirmedTransactionNoteUpdateIds
import com.unifiedledger.application.ConfirmedTransactionNoteUpdateIdSource
import com.unifiedledger.application.ConfirmedTransactionNoteUpdateCommitPort
import com.unifiedledger.application.TransactionNoteUpdateRequestSnapshot
import com.unifiedledger.application.TransactionNoteUpdateRequestIdentity
import com.unifiedledger.application.ConfirmedTransactionNoteUpdateResult
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.projectRg01TransactionNoteUpdateResult
import com.unifiedledger.application.Rg01AttemptedExpenseResult
import com.unifiedledger.application.Rg01DecodedManualExpenseInput
import com.unifiedledger.application.Rg01JsonField
import com.unifiedledger.application.Rg01ManualExpenseParseResult
import com.unifiedledger.application.Rg01OutcomeProjection
import com.unifiedledger.application.Rg01OutcomeStatus
import com.unifiedledger.application.Rg01ProjectionResult
import com.unifiedledger.application.Rg01RawJsonDecodeResult
import com.unifiedledger.application.Rg01RawJsonCase
import com.unifiedledger.application.Rg01ReturnedId
import com.unifiedledger.application.decodeRg01RawJson
import com.unifiedledger.application.evaluateRg01AttemptedManualExpense
import com.unifiedledger.application.parseRg01ManualExpenseInput
import com.unifiedledger.application.projectRg01ManualExpenseResult
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseCommand
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseIds
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.createAssetPaidOrdinaryExpense
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Rg01RawJsonEndToEndTest {
    @Test
    fun trackedRawFixtureExecutesThroughApplicationAndSqlDelightAgainstApprovedOutcomes() {
        val source = Files.readString(repoFile("golden/rules/rg-01.json"))
        val decoded = assertIs<Rg01RawJsonDecodeResult.Success>(decodeRg01RawJson(source)).value
        val v1DrivenOutcomes = mutableListOf<Pair<String, Rg01OutcomeProjection>>()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, sqliteProperties())
        LedgerDatabase.Schema.create(driver)
        driver.use {
            val database = LedgerDatabase(driver)
            val harness = ExecutionHarness(decoded, database, driver)

            assertEquals("7f6f2a9b-bb2c-5a11-8424-3211c027638b", rg01ConfirmationId("$.create.request", "request-rg01-create"))
            assertEquals("91540692-0a89-5644-985b-bf2ba4a3f98a", rg01ConfirmationId("$.distinct_reentry.request", "request-rg01-distinct-create"))

            val created = harness.routeStrict(decoded.create.input)
            v1DrivenOutcomes += decoded.create.input.requestId.required() to created
            assertEquals(decoded.create.expected.transactionId, created.returnedIds.single { it.kind == "transaction" }.value)
            assertEquals(E2eStorageCounts(1, 1, 1, 1, 1, 2), database.counts())
            assertEquals(
                listOf("ledger-a", "request-rg01-create", "3580", "CNY", "2", "expense-category-breakfast", "asset-bank-a", "2026-01-15T00:30:00Z", ""),
                database.persistedRequestValues(),
            )
            assertEquals(
                setOf(
                    listOf("posting-expense-rg01", "expense-account-breakfast", "3580", "CNY", "2"),
                    listOf("posting-bank-rg01", "asset-bank-a", "-3580", "CNY", "2"),
                ),
                database.persistedPostingValues(),
            )

            val countsBeforeNote = database.counts()
            val postingsBeforeNote = database.persistedPostingValues()
            val balancesBeforeNote = database.persistedBalances()
            val originalVersion = database.persistedVersionValues().single()
            val noteUpdate = harness.routeNoteUpdate(decoded.noteUpdate)
            v1DrivenOutcomes += decoded.noteUpdate.input.requestId to noteUpdate
            assertEquals(decoded.noteUpdate.expected.versionId, noteUpdate.returnedIds.single { it.kind == "transaction_version" }.value)
            assertEquals(countsBeforeNote.copy(versions = 2), database.counts())
            assertEquals("version-expense-rg01-v2", database.ledgerQueries.selectCurrentVersionId().executeAsOne())
            assertEquals("早餐", database.ledgerQueries.selectCurrentNote { note -> checkNotNull(note) }.executeAsOne())
            assertEquals(postingsBeforeNote, database.persistedPostingValues())
            assertEquals(balancesBeforeNote, database.persistedBalances())
            assertEquals(mapOf("expense-account-breakfast" to 3580L, "asset-bank-a" to -3580L), balancesBeforeNote)
            val replacementVersion = database.persistedVersionValues().single { it[0] == "version-expense-rg01-v2" }
            assertEquals(originalVersion.subList(3, 7), replacementVersion.subList(3, 7))

            val noteReplay = harness.routeNoteUpdate(decoded.noteUpdate)
            assertEquals(Rg01OutcomeStatus.NO_CHANGE, noteReplay.status)
            assertEquals(noteUpdate.returnedIds, noteReplay.returnedIds)
            assertEquals(1, database.ledgerQueries.countTransactionNoteUpdateRequests().executeAsOne())
            assertEquals(1, database.ledgerQueries.countTransactionNoteUpdateReceipts().executeAsOne())

            val conflict = harness.routeNoteUpdate(
                decoded.noteUpdate.copy(input = decoded.noteUpdate.input.copy(note = "changed")),
            )
            assertEquals(Rg01OutcomeStatus.REJECTED, conflict.status)
            assertEquals("request_identity_conflict", conflict.reasonCode)
            assertEquals(countsBeforeNote.copy(versions = 2), database.counts())

            val stale = harness.routeNoteUpdate(
                decoded.noteUpdate.copy(
                    input = decoded.noteUpdate.input.copy(requestId = "request-rg01-note-stale"),
                    expected = decoded.noteUpdate.expected.copy(versionId = "version-expense-rg01-v3"),
                ),
            )
            assertEquals(Rg01OutcomeStatus.REJECTED, stale.status)
            assertEquals("stale_current_version", stale.reasonCode)
            assertEquals(1, database.ledgerQueries.countTransactionNoteUpdateRequests().executeAsOne())
            assertEquals(1, database.ledgerQueries.countTransactionNoteUpdateReceipts().executeAsOne())
            assertEquals(countsBeforeNote.copy(versions = 2), database.counts())

            val replay = harness.routeStrict(decoded.retry.input)
            v1DrivenOutcomes += decoded.retry.input.requestId.required() to replay
            assertEquals(created.returnedIds, replay.returnedIds)
            assertEquals(E2eStorageCounts(1, 1, 1, 2, 1, 2), database.counts())

            val distinct = harness.routeStrict(decoded.distinct.input)
            v1DrivenOutcomes += decoded.distinct.input.requestId.required() to distinct
            assertEquals(decoded.distinct.expected.transactionId, distinct.returnedIds.single { it.kind == "transaction" }.value)
            assertEquals(E2eStorageCounts(2, 2, 2, 3, 2, 4), database.counts())

            val strictApplicationCallsBeforeInvalid = harness.strictApplicationCalls
            val commitCallsBeforeInvalid = harness.commitCalls
            decoded.invalidInputs.forEach { invalid ->
                val sourceId = checkNotNull(invalid.source.sourceId)
                val requestId = rg01InvalidRequestId(sourceId)
                assertEquals(EXPECTED_INVALID_REQUEST_IDS.getValue(sourceId), requestId)
                val projection = harness.routeSparse(invalid.input, requestId)
                v1DrivenOutcomes += requestId to projection
                assertEquals(invalid.expected.fieldPath, projection.fieldPath)
                assertEquals(invalid.expected.reasonCode, projection.reasonCode)
                assertTrue(projection.returnedIds.isEmpty())
                assertEquals(E2eStorageCounts(2, 2, 2, 3, 2, 4), database.counts())
            }
            assertEquals(7, decoded.invalidInputs.size)
            assertEquals(strictApplicationCallsBeforeInvalid, harness.strictApplicationCalls)
            assertEquals(commitCallsBeforeInvalid, harness.commitCalls)

            val approved = ApprovedOutcomes.decode(Files.readString(repoFile("docs/migrations/golden-v2/rg-01-expected.json")))
            v1DrivenOutcomes.forEach { (requestId, projection) ->
                assertProjection(approved.byRequest(requestId, projection.status), projection)
            }
        }
    }
}

private class ExecutionHarness(
    private val decoded: Rg01RawJsonCase,
    database: LedgerDatabase,
    driver: JdbcSqliteDriver,
) {
    private val context = decoded.context
    private var activeRequestId = ""
    var strictApplicationCalls = 0
        private set
    var commitCalls = 0
        private set
    private val transactionIds = mapOf(
        decoded.create.input.requestId.required() to checkNotNull(decoded.create.expected.transactionId),
        decoded.distinct.input.requestId.required() to checkNotNull(decoded.distinct.expected.transactionId),
    )
    private val commitPort = ConfirmedManualExpenseCommitPort { identity, snapshot, callback ->
        commitCalls += 1
        SqlDelightConfirmedManualExpenseCommitPort(database, driver).commitOnce(identity, snapshot, callback)
    }
    private val noteCommitPort = ConfirmedTransactionNoteUpdateCommitPort { identity, snapshot, callback ->
        SqlDelightConfirmedTransactionNoteUpdateCommitPort(database, driver).commitOnce(identity, snapshot, callback)
    }
    private val execute = ExecuteManualExpenseSave(
        ExecuteConfirmedManualExpense(
            commitPort,
            ConfirmedManualExpenseIdSource {
                val transactionId = checkNotNull(transactionIds[activeRequestId])
                val locator = if (transactionId.endsWith("-distinct")) "$.distinct_reentry.request" else "$.create.request"
                val confirmationId = rg01ConfirmationId(locator, activeRequestId)
                val suffix = if (transactionId.endsWith("-distinct")) "rg01-distinct" else "rg01"
                ConfirmedManualExpenseCommitIds(
                    ConfirmationId(confirmationId),
                    AssetPaidOrdinaryExpenseIds(
                        TransactionId(transactionId), TransactionVersionId("version-expense-$suffix-v1"),
                        PostingSetId("posting-set-expense-$suffix"), PostingId("posting-expense-$suffix"), PostingId("posting-bank-$suffix"),
                    ),
                )
            },
            ConfirmedExpenseTransactionFactory { request, ids ->
                when (val created = createAssetPaidOrdinaryExpense(
                    context.catalog!!,
                    AssetPaidOrdinaryExpenseCommand(request.ledgerId, request.amount, request.categoryId, request.paymentAccountId, TransactionTimes.collapsed(request.occurredAt)),
                    ids.expenseIds,
                )) {
                    is DomainResult.Failure -> created
                    is DomainResult.Success -> DomainResult.Success(ConfirmedManualExpenseCommit(ids.confirmationId, created.value))
                }
            },
        ),
    )

    fun routeStrict(input: Rg01DecodedManualExpenseInput): Rg01OutcomeProjection {
        strictApplicationCalls += 1
        activeRequestId = input.requestId.required()
        val parsed = assertIs<Rg01ManualExpenseParseResult.Success>(parseRg01ManualExpenseInput(context, input)).value
        return assertIs<Rg01ProjectionResult.Mapped>(projectRg01ManualExpenseResult(execute.execute(parsed.saveInput))).projection
    }

    fun routeSparse(input: Rg01DecodedManualExpenseInput, requestId: String): Rg01OutcomeProjection =
        assertIs<Rg01AttemptedExpenseResult.Mapped>(
            evaluateRg01AttemptedManualExpense(
                context,
                input.copy(requestId = Rg01JsonField.Value(requestId)),
            ),
        ).projection

    fun routeNoteUpdate(operation: com.unifiedledger.application.Rg01DecodedNoteUpdateOperation): Rg01OutcomeProjection {
        val execute = ExecuteConfirmedTransactionNoteUpdate(
            noteCommitPort,
            ConfirmedTransactionNoteUpdateIdSource {
                ConfirmedTransactionNoteUpdateIds(
                    ConfirmationId(rg01ConfirmationId(operation.source.locator, operation.input.requestId)),
                    com.unifiedledger.domain.TransactionNoteUpdateIds(TransactionVersionId(checkNotNull(operation.expected.versionId))),
                    TransactionVersionId(checkNotNull(decoded.create.expected.versionId)),
                )
            },
        )
        return projectRg01TransactionNoteUpdateResult(
            execute.execute(
                ExplicitlyConfirmedTransactionNoteUpdate(
                    context.ledgerId, RequestId(operation.input.requestId), TransactionId(operation.input.transactionId),
                    operation.input.note, ExplicitManualSave,
                ),
            ),
        )
    }
}

private data class ApprovedOutcome(
    val requestId: String,
    val status: Rg01OutcomeStatus,
    val reasonCode: String?,
    val fieldPath: String?,
    val returnedIds: Set<Rg01ReturnedId>,
)

private class ApprovedOutcomes private constructor(private val values: List<ApprovedOutcome>) {
    fun byRequest(requestId: String, status: Rg01OutcomeStatus): ApprovedOutcome =
        values.single { it.requestId == requestId && it.status == status }

    companion object {
        fun decode(raw: String): ApprovedOutcomes {
            val root = Json.parseToJsonElement(raw).jsonObject
            require(root.getValue("case").jsonObject.getValue("approval_status").jsonPrimitive.content == "approved")
            val values = root.getValue("operations").jsonArray.mapNotNull { element ->
                val operation = element.jsonObject
                if (operation.getValue("action_type").jsonPrimitive.content !in setOf("manual_expense", "transaction_note_update")) return@mapNotNull null
                val input = operation["input"]?.jsonObject
                val attempted = operation["attempted_input"]?.jsonObject
                val outcome = operation.getValue("outcome").jsonObject
                ApprovedOutcome(
                    requestId = (input ?: attempted)!!.getValue("request_id").jsonPrimitive.content,
                    status = when (outcome.getValue("status").jsonPrimitive.content) {
                        "accepted" -> Rg01OutcomeStatus.ACCEPTED
                        "no_change" -> Rg01OutcomeStatus.NO_CHANGE
                        "rejected" -> Rg01OutcomeStatus.REJECTED
                        else -> error("unsupported approved outcome")
                    },
                    reasonCode = outcome["reason_code"]?.jsonPrimitive?.contentOrNull,
                    fieldPath = outcome["field_path"]?.jsonPrimitive?.contentOrNull,
                    returnedIds = operation.getValue("returned_ids").jsonArray.map { returned ->
                        val value = returned.jsonObject
                        Rg01ReturnedId(value.getValue("kind").jsonPrimitive.content, value.getValue("id").jsonPrimitive.content)
                    }.toSet(),
                )
            }
            return ApprovedOutcomes(values)
        }
    }
}

private val RG01_MIGRATION_NAMESPACE: UUID = UUID.fromString("cfad3f84-edb1-5838-ae53-aae49684cf1a")
private val EXPECTED_INVALID_REQUEST_IDS = mapOf(
    "missing-amount" to "27c403a9-cf8b-5f0a-9bee-ab62ac2bccab",
    "missing-payment-account" to "85fad5da-31b0-5dbf-b408-008451dfaa99",
    "missing-secondary-category" to "4b02fef7-4dfc-54bb-bd16-f4279333dafa",
    "zero-amount" to "abb2750a-c8f1-5bbb-862e-3735a99fa23e",
    "negative-amount" to "e41f87e8-d46a-5667-bd97-a09ff3799400",
    "primary-category" to "6ecbbf3a-ec7f-5916-8716-47a8b8c6e8a5",
    "inactive-secondary-category" to "2e224810-3357-594e-8040-de138a63790a",
)
private val RG01_MAIN_ROOT_ID: UUID = uuidV5(
    RG01_MIGRATION_NAMESPACE,
    "RG-01\n@root\nroot\n$.case.id\noccurrence=RG-01",
)
private fun rg01ConfirmationId(locator: String, requestId: String): String = uuidV5(
    RG01_MIGRATION_NAMESPACE,
    "RG-01\n$RG01_MAIN_ROOT_ID\nconfirmation\n$locator\noccurrence=$requestId",
).toString()
private fun rg01InvalidRequestId(sourceId: String): String {
    val rootId = uuidV5(
        RG01_MIGRATION_NAMESPACE,
        "RG-01\n@root\nroot\n$.invalid_inputs[*]\noccurrence=$sourceId",
    )
    return uuidV5(
        RG01_MIGRATION_NAMESPACE,
        "RG-01\n$rootId\nrequest\n$.invalid_inputs[*].id\noccurrence=$sourceId",
    ).toString()
}
private fun uuidV5(namespace: UUID, name: String): UUID {
    val namespaceBytes = ByteBuffer.allocate(16)
        .putLong(namespace.mostSignificantBits)
        .putLong(namespace.leastSignificantBits)
        .array()
    val digest = MessageDigest.getInstance("SHA-1")
    digest.update(namespaceBytes)
    val bytes = digest.digest(name.toByteArray(Charsets.UTF_8)).copyOf(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val buffer = ByteBuffer.wrap(bytes)
    return UUID(buffer.long, buffer.long)
}
private fun <T> Rg01JsonField<T>.required(): T = assertIs<Rg01JsonField.Value<T>>(this).value
private fun assertProjection(expected: ApprovedOutcome, actual: Rg01OutcomeProjection) {
    assertEquals(expected.status, actual.status)
    assertEquals(expected.reasonCode, actual.reasonCode)
    assertEquals(expected.fieldPath, actual.fieldPath)
    assertEquals(expected.returnedIds, actual.returnedIds)
}

private data class E2eStorageCounts(val requests: Long, val receipts: Long, val transactions: Long, val versions: Long, val postingSets: Long, val postings: Long)
private fun LedgerDatabase.counts() = E2eStorageCounts(
    ledgerQueries.countRequests().executeAsOne(), ledgerQueries.countReceipts().executeAsOne(),
    ledgerQueries.countTransactions().executeAsOne(), ledgerQueries.countVersions().executeAsOne(),
    ledgerQueries.countPostingSets().executeAsOne(), ledgerQueries.countPostings().executeAsOne(),
)
private fun LedgerDatabase.persistedRequestValues(): List<String> = ledgerQueries.selectPersistedRequest {
        ledgerId, requestId, amountMinor, currencyCode, currencyPrecision, categoryId,
        paymentAccountId, occurredAt, note, _, _, _ ->
    listOf(ledgerId, requestId, amountMinor.toString(), currencyCode, currencyPrecision.toString(), categoryId, paymentAccountId, occurredAt, note)
}.executeAsOne()
private fun LedgerDatabase.persistedPostingValues(): Set<List<String>> = ledgerQueries.selectPersistedPostings {
        postingId, _, accountId, amountMinor, currencyCode, currencyPrecision ->
    listOf(postingId, accountId, amountMinor.toString(), currencyCode, currencyPrecision.toString())
}.executeAsList().toSet()
private fun LedgerDatabase.persistedVersionValues(): List<List<String>> = ledgerQueries.selectPersistedVersions {
        versionId, transactionId, versionNumber, postingSetId, occurredAt, statisticsAt, effectiveAt, note ->
    listOf(versionId, transactionId, versionNumber.toString(), postingSetId, occurredAt, statisticsAt, effectiveAt, note.orEmpty())
}.executeAsList()
private fun LedgerDatabase.persistedBalances(): Map<String, Long> = ledgerQueries.selectPersistedPostings {
        _, _, accountId, amountMinor, _, _ -> accountId to amountMinor
}.executeAsList().groupingBy { it.first }.fold(0L) { sum, (_, amount) -> sum + amount }
private fun repoFile(relative: String): Path {
    var candidate = Path.of(System.getProperty("user.dir"))
    repeat(6) {
        if (Files.isRegularFile(candidate.resolve("settings.gradle.kts")) && Files.isDirectory(candidate.resolve("golden"))) {
            return candidate.resolve(relative).also { require(Files.isRegularFile(it)) }
        }
        candidate = candidate.parent ?: error("repository root not found")
    }
    error("repository root not found")
}
private fun sqliteProperties() = Properties().apply { setProperty("foreign_keys", "true"); setProperty("busy_timeout", "5000") }
