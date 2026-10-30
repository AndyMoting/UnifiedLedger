package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.LedgerCurrentStateReadPort.LedgerFlowCursor
import com.unifiedledger.application.sortLedgerEntryRowsForDisplay
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * DEF-B (D-268) evidence: the HOME flow page read is a stable keyset walk in
 * `(statistics_at_epoch_nanos DESC, transaction_id DESC)`, the accumulated pages re-sort into the
 * frozen P703SPEC-03 display order (the cursor order is not the display order), the total is
 * exact, the fallback list is bounded by creation order, and a missing numeric projection fails
 * loudly instead of dropping the row.
 */
class DefBFlowPageReadTest {
    private val ledgerId = LedgerId("ledger-def-b-test")
    private val otherLedgerId = LedgerId("ledger-def-b-other")
    private val cny = CurrencyUnit("CNY", 2)
    private val expenseAccount = AccountId("expense-def-b")
    private val assetAccount = AccountId("asset-def-b")

    @Test
    fun pagesWalkTheKeysetOrderWithoutDuplicatesAndTheTotalIsExact() {
        val path = newDatabaseFile("def-b-page-")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${path.absolutePathString()}")
        try {
            LedgerDatabase.Schema.create(driver)
            // Three transactions share one statistics instant, so a page boundary falls INSIDE
            // the tie group; the ids decide the cursor order within it.
            insertTransaction(driver, "tx-01", atLocal("2026-03-01T02:00:00Z"), 1_000L)
            insertTransaction(driver, "tx-02", atLocal("2026-03-02T02:00:00Z"), 2_000L)
            insertTransaction(driver, "tx-03", atLocal("2026-03-03T02:00:00Z"), 3_000L)
            insertTransaction(driver, "tx-04", atLocal("2026-03-03T02:00:00Z"), 4_000L)
            insertTransaction(driver, "tx-05", atLocal("2026-03-03T02:00:00Z"), 5_000L)
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(LedgerDatabase(driver))

            val walked = mutableListOf<String>()
            var cursor: LedgerFlowCursor? = null
            var pages = 0
            do {
                val page = adapter.loadLedgerFlowPage(ledgerId, cursor, pageSize = 2)
                walked += page.rows.map { it.transactionId.value }
                pages += 1
                cursor = page.nextCursor
            } while (cursor != null)

            // Keyset order: statistics DESC, then transaction id DESC; no duplicates, no gaps.
            assertEquals(listOf("tx-05", "tx-04", "tx-03", "tx-02", "tx-01"), walked)
            assertEquals(3, pages)
            assertEquals(5, adapter.effectiveTransactionCount(ledgerId))
        } finally {
            driver.close()
        }
    }

    @Test
    fun theAccumulatedPagesSortIntoTheFrozenDisplayOrder() {
        val path = newDatabaseFile("def-b-order-")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${path.absolutePathString()}")
        try {
            LedgerDatabase.Schema.create(driver)
            insertTransaction(driver, "tx-01", atLocal("2026-03-01T02:00:00Z"), 1_000L)
            insertTransaction(driver, "tx-03", atLocal("2026-03-03T02:00:00Z"), 3_000L)
            insertTransaction(driver, "tx-04", atLocal("2026-03-03T02:00:00Z"), 4_000L)
            insertTransaction(driver, "tx-05", atLocal("2026-03-03T02:00:00Z"), 5_000L)
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(LedgerDatabase(driver))

            val accumulated = mutableListOf<com.unifiedledger.application.LedgerEntryRow>()
            var cursor: LedgerFlowCursor? = null
            do {
                val page = adapter.loadLedgerFlowPage(ledgerId, cursor, pageSize = 2)
                accumulated += page.rows
                cursor = page.nextCursor
            } while (cursor != null)

            // P703SPEC-03 ties on statistics then occurred DESC, then id ASC — the tie group's
            // id order is the REVERSE of the cursor order, which is why the pages re-sort.
            assertEquals(
                listOf("tx-01", "tx-03", "tx-04", "tx-05"),
                sortLedgerEntryRowsForDisplay(accumulated).map { it.transactionId.value },
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun theFallbackListIsBoundedByCreationOrder() {
        val path = newDatabaseFile("def-b-recent-")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${path.absolutePathString()}")
        try {
            LedgerDatabase.Schema.create(driver)
            insertTransaction(driver, "tx-01", atLocal("2026-03-01T02:00:00Z"), 1_000L)
            insertTransaction(driver, "tx-02", atLocal("2026-03-02T02:00:00Z"), 2_000L)
            insertTransaction(driver, "tx-03", atLocal("2026-03-03T02:00:00Z"), 3_000L)
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(LedgerDatabase(driver))

            // Newest by CREATION order (UUIDv7 id), never by statistics time.
            assertEquals(
                listOf("tx-03", "tx-02"),
                adapter.loadRecentCurrentRows(ledgerId, limit = 2).map { it.transactionId.value },
            )
            assertEquals(3, adapter.effectiveTransactionCount(ledgerId))
        } finally {
            driver.close()
        }
    }

    @Test
    fun aMissingStatisticsProjectionFailsLoudly() {
        val path = newDatabaseFile("def-b-missing-")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${path.absolutePathString()}")
        try {
            LedgerDatabase.Schema.create(driver)
            insertTransaction(driver, "tx-ok", atLocal("2026-03-01T02:00:00Z"), 1_000L)
            insertTransaction(driver, "tx-null", atLocal("2026-03-02T02:00:00Z"), 2_000L, withProjection = false)
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(LedgerDatabase(driver))

            // The NULL row can never satisfy the page predicate, so the read must fail instead of
            // silently returning a shorter page.
            assertFailsWith<IllegalStateException> {
                adapter.loadLedgerFlowPage(ledgerId, null, pageSize = 10)
            }
        } finally {
            driver.close()
        }
    }

    @Test
    fun anEmptyLedgerReturnsAnEmptyPageAndNoCursor() {
        val path = newDatabaseFile("def-b-empty-")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${path.absolutePathString()}")
        try {
            LedgerDatabase.Schema.create(driver)
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(LedgerDatabase(driver))
            val page = adapter.loadLedgerFlowPage(ledgerId, null, pageSize = 10)
            assertEquals(emptyList(), page.rows)
            assertNull(page.nextCursor)
            assertEquals(0, adapter.effectiveTransactionCount(ledgerId))
            assertEquals(emptyList(), adapter.loadRecentCurrentRows(otherLedgerId, limit = 5))
        } finally {
            driver.close()
        }
    }

    private fun atLocal(instant: String): Instant = Instant.parse(instant)

    /** Two-posting expense chain: +amount on the expense account, -amount on the asset account. */
    private fun insertTransaction(
        driver: SqlDriver,
        transactionId: String,
        statisticsAt: Instant,
        amountMinor: Long,
        withProjection: Boolean = true,
    ) {
        val stamp = statisticsAt.toString()
        val nanos = if (withProjection) "${statisticsAt.toEpochMilliseconds() * 1_000_000L}" else "NULL"
        exec(driver, "INSERT INTO ledger_transaction (transaction_id, ledger_id, kind, canonical_kind) VALUES ('$transactionId', '${ledgerId.value}', 'EXPENSE', NULL)")
        exec(driver, "INSERT INTO posting_set (posting_set_id, ledger_id) VALUES ('ps-$transactionId', '${ledgerId.value}')")
        exec(
            driver,
            "INSERT INTO transaction_version (version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note, statistics_at_epoch_nanos) " +
                "VALUES ('v-$transactionId', '$transactionId', '${ledgerId.value}', 1, 'ps-$transactionId', '$stamp', '$stamp', '$stamp', '', $nanos)",
        )
        exec(
            driver,
            "INSERT INTO posting (posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, currency_code, currency_precision) " +
                "VALUES ('p0-$transactionId', 'ps-$transactionId', '${ledgerId.value}', 0, '${expenseAccount.value}', $amountMinor, 'CNY', 2)",
        )
        exec(
            driver,
            "INSERT INTO posting (posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, currency_code, currency_precision) " +
                "VALUES ('p1-$transactionId', 'ps-$transactionId', '${ledgerId.value}', 1, '${assetAccount.value}', ${-amountMinor}, 'CNY', 2)",
        )
        exec(
            driver,
            "INSERT INTO ledger_transaction_current_version (transaction_id, ledger_id, current_version_id) VALUES ('$transactionId', '${ledgerId.value}', 'v-$transactionId')",
        )
    }

    private fun exec(
        driver: SqlDriver,
        sql: String,
    ) {
        driver.execute(null, sql, 0)
    }

    private fun newDatabaseFile(prefix: String): Path {
        val path = Files.createTempFile(prefix, ".db")
        Files.deleteIfExists(path)
        return path
    }
}
