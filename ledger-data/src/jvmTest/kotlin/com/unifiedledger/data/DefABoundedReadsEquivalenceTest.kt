package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * DEF-A (D-266) evidence: the bounded read port projections are exactly the unbounded ones
 * restricted to their window, key or aggregate. Each SQL override is compared with the
 * semantics of the unbounded read it replaces, on one anonymous temp-file database, so a
 * join, an effective-state predicate or a rounding change cannot hide behind the device run.
 */
class DefABoundedReadsEquivalenceTest {
    private val ledgerId = LedgerId("ledger-def-a-test")
    private val otherLedgerId = LedgerId("ledger-def-a-other")
    private val cny = CurrencyUnit("CNY", 2)
    private val assetAccount = AccountId("asset-def-a")
    private val expenseAccount = AccountId("expense-def-a")

    @Test
    fun windowReadIsTheUnboundedReadRestrictedToTheWindow() {
        val path = newDatabaseFile("def-a-window-")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${path.absolutePathString()}")
        try {
            LedgerDatabase.Schema.create(driver)
            // March in the frozen zone is [2026-02-28T16:00Z, 2026-03-31T16:00Z).
            insertTransaction(driver, "tx-jan", atLocal("2026-01-15T02:00:00Z"), 1_000L)
            insertTransaction(driver, "tx-mar-a", atLocal("2026-02-28T16:00:00Z"), 2_000L)
            insertTransaction(driver, "tx-mar-b", atLocal("2026-03-31T15:59:59Z"), 3_000L)
            insertTransaction(driver, "tx-apr", atLocal("2026-03-31T16:00:00Z"), 4_000L)
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(LedgerDatabase(driver))

            val all = adapter.loadLedgerEntryRows(ledgerId)
            assertEquals(listOf("tx-apr", "tx-jan", "tx-mar-a", "tx-mar-b"), all.map { it.transactionId.value }.sorted())

            // March in the frozen reporting zone (Asia/Shanghai): [2026-03-01T00:00+08, 2026-04-01T00:00+08).
            val marchStart = Instant.parse("2026-02-28T16:00:00Z")
            val aprilStart = Instant.parse("2026-03-31T16:00:00Z")
            val windowed =
                adapter.loadLedgerEntryRowsInWindow(
                    ledgerId,
                    marchStart.toEpochMilliseconds() * 1_000_000L,
                    aprilStart.toEpochMilliseconds() * 1_000_000L,
                )
            val expected =
                all.filter {
                    val nanos = it.statisticsAt.toEpochMilliseconds() * 1_000_000L
                    nanos >= marchStart.toEpochMilliseconds() * 1_000_000L &&
                        nanos < aprilStart.toEpochMilliseconds() * 1_000_000L
                }
            assertEquals(expected.map { it.transactionId.value }.sorted(), windowed.map { it.transactionId.value }.sorted())
            assertEquals(listOf("tx-mar-a", "tx-mar-b"), windowed.map { it.transactionId.value }.sorted())

            // The keyed read returns exactly the unbounded row and nothing for an absent id.
            val keyed = adapter.loadLedgerEntryRow(ledgerId, TransactionId("tx-mar-b"))
            assertEquals(all.first { it.transactionId.value == "tx-mar-b" }, keyed)
            assertNull(adapter.loadLedgerEntryRow(ledgerId, TransactionId("tx-absent")))
            assertNull(adapter.loadLedgerEntryRow(otherLedgerId, TransactionId("tx-mar-b")))

            // The lower bound is the earliest effective statistics instant.
            assertEquals(Instant.parse("2026-01-15T02:00:00Z"), adapter.earliestEffectiveStatisticsAt(ledgerId))
            assertNull(adapter.earliestEffectiveStatisticsAt(otherLedgerId))
        } finally {
            driver.close()
        }
    }

    @Test
    fun balanceSumsEqualTheInMemoryGroupingOfTheCurrentRows() {
        val path = newDatabaseFile("def-a-balances-")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${path.absolutePathString()}")
        try {
            LedgerDatabase.Schema.create(driver)
            insertTransaction(driver, "tx-1", atLocal("2026-05-02T01:00:00Z"), 1_234L)
            insertTransaction(driver, "tx-2", atLocal("2026-05-03T01:00:00Z"), 7_766L)
            insertTransaction(driver, "tx-3", atLocal("2026-05-04T01:00:00Z"), 5L)
            val database = LedgerDatabase(driver)
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)

            val rows = adapter.loadCurrentRows(ledgerId)
            val inMemory =
                rows.flatMap { it.postings }
                    .groupBy { it.accountId to it.amount.currency }
                    .mapValues { (_, postings) -> postings.sumOf { it.amount.minorUnits } }
            val sums =
                adapter.accountBalanceSums(ledgerId)
                    .associate { (it.accountId to it.currency) to it.ledgerSignedMinorUnits }
            assertEquals(inMemory, sums)
            assertEquals(1_234L + 7_766L + 5L, sums[assetAccount to cny])
            assertEquals(-(1_234L + 7_766L + 5L), sums[expenseAccount to cny])
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
    ) {
        val stamp = statisticsAt.toString()
        val nanos = statisticsAt.toEpochMilliseconds() * 1_000_000L
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

    private fun exec(driver: SqlDriver, sql: String) {
        driver.execute(null, sql, 0)
    }

    private fun newDatabaseFile(prefix: String): Path {
        val path = Files.createTempFile(prefix, ".db")
        Files.deleteIfExists(path)
        return path
    }
}
