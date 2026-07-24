package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.*
import com.unifiedledger.data.db.LedgerDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Rg04RuntimeIntegrationTest {
    @Test
    fun executesAllTwentySixStrictV1OperationsBeforeReadingOracle() {
        val raw = Files.readString(repositoryFile("golden/rules/rg-04.json"))
        val case = assertIs<Rg04RawJsonDecodeResult.Success>(decodeRg04RawJson(raw)).value
        assertEquals(18, case.operations.size)
        assertEquals(8, case.importOperations.size)

        val manualStatuses = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val executor = ExecuteRg04Operation(
                SqlDelightRg04Store(database, driver, case.catalog, manualIdentity(case)),
            )
            case.operations.map { decoded ->
                when (val adapted = adaptRg04Operation(case, decoded)) {
                    is Rg04AdaptResult.Invalid -> "rejected"
                    is Rg04AdaptResult.Success -> status(executor.execute(adapted.operation))
                }
            }.also {
                assertEquals(2L, database.ledgerQueries.countTransactions().executeAsOne())
                assertEquals(5L, database.ledgerQueries.countPostings().executeAsOne())
            }
        }

        val importResults = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val executor = ExecuteRg04ImportOperation(SqlDelightRg04ImportStore(database, driver, case.catalog))
            case.importOperations.map(executor::execute).also {
                assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
                assertEquals(3L, database.ledgerQueries.countPostings().executeAsOne())
                assertEquals(3L, database.ledgerQueries.countRg04ImportSources().executeAsOne())
                assertEquals(3L, database.ledgerQueries.countRg04ImportEvidence().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countRg04ImportCandidates().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countRg04ImportMatches().executeAsOne())
            }
        }

        val actualStatuses = manualStatuses + importResults.map(::status)
        assertEquals(26, actualStatuses.size)
        assertEquals(26, case.operations.count() + case.importOperations.count())
        assertEquals(6, actualStatuses.count { it == "accepted" })
        assertEquals(6, actualStatuses.count { it == "no_change" })
        assertEquals(14, actualStatuses.count { it == "rejected" })

        val importRootId = deterministicRoot("$.import_lifecycle", "source-record-rg04-complete")
        val confirmationId = deterministicId(
            importRootId,
            "confirmation",
            "$.import_lifecycle.ordered_operations[*].expected.candidate_status",
            "request-rg04-confirm-candidate",
        )
        val acceptedConfirmation = assertIs<Rg04ImportExecutionResult.Accepted>(importResults[2])
        assertEquals(
            confirmationId,
            acceptedConfirmation.returnedIds.single { it.kind == Rg04ImportReturnedIdKind.CONFIRMATION }.id,
        )

        val oracle = Json.parseToJsonElement(
            Files.readString(repositoryFile("docs/migrations/golden-v2/rg-04-expected.json")),
        ).jsonObject
        val oracleOperations = oracle.getValue("operations").jsonArray
        assertEquals(26, oracleOperations.size)
        val oracleStatuses = oracleOperations.map { it.jsonObject.getValue("outcome").jsonObject.getValue("status").jsonPrimitive.content }
        assertEquals(actualStatuses.groupingBy { it }.eachCount(), oracleStatuses.groupingBy { it }.eachCount())

        listOf(
            "source-record-rg04-complete",
            "evidence-rg04-asset-debit",
            "candidate-purchase-rg04",
            confirmationId,
            "tx-purchase-rg04-imported",
            "source-record-rg04-liability-mirror",
            "evidence-rg04-liability-mirror",
            "match-rg04-liability-mirror",
            "source-record-rg04-missing-leg",
            "evidence-rg04-known-asset-debit",
            "candidate-purchase-rg04-missing-leg",
        ).forEach { id ->
            assertTrue(
                oracleOperations.any { operation ->
                    operation.jsonObject.getValue("returned_ids").jsonArray.any { returned ->
                        returned.jsonObject.getValue("id").jsonPrimitive.content == id
                    }
                },
                "oracle must expose post-execution returned id $id",
            )
        }
    }
}

private fun status(result: Rg04ExecutionResult): String = when (result) {
    is Rg04ExecutionResult.Accepted -> "accepted"
    is Rg04ExecutionResult.NoChange -> "no_change"
    is Rg04ExecutionResult.Rejected -> "rejected"
    Rg04ExecutionResult.RequestIdentityConflict -> "conflict"
}

private fun status(result: Rg04ImportExecutionResult): String = when (result) {
    is Rg04ImportExecutionResult.Accepted -> "accepted"
    is Rg04ImportExecutionResult.NoChange -> "no_change"
    is Rg04ImportExecutionResult.Rejected -> "rejected"
    Rg04ImportExecutionResult.RequestIdentityConflict -> "conflict"
}

private fun manualIdentity(case: Rg04RawJsonCase): Rg04IdentitySource {
    val rootId = deterministicRoot("$.manual_lifecycle", "request-rg04-manual-purchase")
    val locator = "$.manual_lifecycle.ordered_operations[*]"
    return object : Rg04IdentitySource {
        override fun manual(requestId: RequestId) = Rg04ManualCommitIds(
            deterministicId(rootId, "confirmation", "$locator.confirmation", requestId.value),
            case.manualIds.fundingPostingIds.map { posting ->
                deterministicId(rootId, "posting_reconciliation", "$locator.expected.reconciliation", posting.value)
            },
        )

        override fun repayment(requestId: RequestId) = Rg04RepaymentCommitIds(
            deterministicId(rootId, "confirmation", "$locator.confirmation", requestId.value),
            listOf(case.repaymentIds.assetPostingId, case.repaymentIds.liabilityPostingId).map { posting ->
                deterministicId(rootId, "posting_reconciliation", "$locator.expected.reconciliation", posting.value)
            },
        )
    }
}

private fun deterministicRoot(locator: String, occurrence: String): String =
    deterministicUuid("RG-04\n@root\nroot\n$locator\noccurrence=$occurrence")

private fun deterministicId(rootId: String, kind: String, locator: String, occurrence: String): String =
    deterministicUuid("RG-04\n$rootId\n$kind\n$locator\noccurrence=$occurrence")

private fun deterministicUuid(name: String): String {
    val namespace = UUID.fromString("cfad3f84-edb1-5838-ae53-aae49684cf1a")
    val bytes = java.security.MessageDigest.getInstance("SHA-1").digest(namespace.toRfcBytes() + name.encodeToByteArray())
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val buffer = ByteBuffer.wrap(bytes.copyOf(16))
    return UUID(buffer.long, buffer.long).toString()
}

private fun UUID.toRfcBytes(): ByteArray = ByteArray(16).also { bytes ->
    val most = mostSignificantBits
    val least = leastSignificantBits
    for (i in 0 until 8) bytes[i] = (most ushr (56 - i * 8)).toByte()
    for (i in 0 until 8) bytes[8 + i] = (least ushr (56 - i * 8)).toByte()
}

private fun repositoryFile(relative: String): Path {
    var current = Path.of("").toAbsolutePath()
    while (true) {
        val candidate = current.resolve(relative)
        if (Files.exists(candidate)) return candidate
        current = current.parent ?: error("repository root not found")
    }
}
