package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.data.db.LedgerDatabase
import java.nio.file.Files
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * P8-03 i1 (D-312; spec section 2.1/5 V-11) v36 -> v37 migration evidence for the manual refund
 * receipt owner. The edge is purely additive: one table, one index and one update-immutability
 * trigger, created verbatim in `36.sqm` and in the fresh `Ledger.sq`.
 */
class P803ManualRefundReceiptMigrationV36ToV37Test : MigrationTestSupport() {
    @Test
    fun v11FreshSchemaIsTheThirtySeventhAndCarriesTheNewOwner() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)

            assertEquals(37, LedgerDatabase.Schema.version)
            assertEquals(0L, database.ledgerQueries.countManualRefundReceipts("ledger-a").executeAsOne())
            assertEquals(
                listOf(
                    "ledger_id",
                    "relationship_id",
                    "request_id",
                    "request_fingerprint",
                    "original_transaction_id",
                    "refund_transaction_id",
                    "amount_minor",
                    "currency_code",
                    "currency_precision",
                    "destination_account_id",
                    "category_id",
                    "arrived_at",
                    "confirmed_at",
                    "created_at",
                    "confirmation_marker",
                ),
                tableColumnNames(driver, "manual_refund_receipt"),
            )
            assertEquals(
                1L,
                queryCount(
                    driver,
                    "SELECT count(*) FROM sqlite_master WHERE type='index' AND name='manual_refund_receipt_original_idx'",
                ),
            )
            // `pragma_foreign_key_list` row order is not part of its contract: compare the
            // (from, to) column pairs order-free, keeping each composite pair intact.
            assertEquals(
                listOf(
                    "ledger_id|ledger_id",
                    "ledger_id|ledger_id",
                    "original_transaction_id|transaction_id",
                    "refund_transaction_id|transaction_id",
                ),
                driver
                    .executeQuery(
                        null,
                        "SELECT \"from\" || '|' || \"to\" FROM pragma_foreign_key_list('manual_refund_receipt')",
                        { cursor ->
                            val rows = buildList { while (cursor.next().value) add(requireNotNull(cursor.getString(0))) }
                            app.cash.sqldelight.db.QueryResult.Value(rows)
                        },
                        0,
                    ).value
                    .sorted(),
            )
            assertEquals(true, triggerSql(driver, "manual_refund_receipt_guard_update").contains("cannot update manual refund receipt"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun v11MigrationFromV36IsAdditiveAndPreservesExistingRows() {
        val path = Files.createTempFile("ledger-data-v36-v37-refund-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 36)
                driver.execute(
                    null,
                    "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind, canonical_kind) VALUES ('tx-existing-refund','ledger-a','EXPENSE','REFUND_RECEIPT')",
                    0,
                )
                assertEquals(2L, queryCount(driver, "SELECT count(*) FROM ledger_transaction"))
                assertEquals(2L, queryCount(driver, "SELECT count(*) FROM posting"))
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 36, 37)
                assertEquals(37, LedgerDatabase.Schema.version)
                // Existing data survives value for value and the v37 owner starts empty.
                assertEquals(2L, queryCount(driver, "SELECT count(*) FROM ledger_transaction"))
                assertEquals(2L, queryCount(driver, "SELECT count(*) FROM posting"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM ledger_transaction WHERE transaction_id='tx-existing' AND kind='EXPENSE'"))
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM manual_refund_receipt"))
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM pragma_foreign_key_check"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='manual_refund_receipt'"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE type='index' AND name='manual_refund_receipt_original_idx'"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name='manual_refund_receipt_guard_update'"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun v11FreshEqualsMigratedForTheWholeTerminalSchema() {
        val freshPath = Files.createTempFile("ledger-data-fresh-v37-refund-", ".db")
        val migratedPath = Files.createTempFile("ledger-data-migrated-v37-refund-", ".db")
        try {
            JdbcSqliteDriver("jdbc:sqlite:${freshPath.absolutePathString()}", migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
            }
            DriverManager.getConnection("jdbc:sqlite:${migratedPath.absolutePathString()}").use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver("jdbc:sqlite:${migratedPath.absolutePathString()}", migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 37)
            }
            assertEquals(
                schemaMetadata("jdbc:sqlite:${freshPath.absolutePathString()}"),
                schemaMetadata("jdbc:sqlite:${migratedPath.absolutePathString()}"),
            )
        } finally {
            Files.deleteIfExists(freshPath)
            Files.deleteIfExists(migratedPath)
        }
    }

    @Test
    fun v11ConstraintsAndTheImmutabilityTriggerAreEnforced() {
        val path = Files.createTempFile("ledger-data-v37-refund-constraints-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 37)
                driver.execute(
                    null,
                    "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind, canonical_kind) VALUES ('tx-refund-m','ledger-a','EXPENSE','REFUND_RECEIPT')",
                    0,
                )
                driver.execute(
                    null,
                    "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind, canonical_kind) VALUES ('tx-other-ledger','ledger-b','EXPENSE',NULL)",
                    0,
                )
                val validRow =
                    "INSERT INTO manual_refund_receipt(ledger_id, relationship_id, request_id, request_fingerprint, original_transaction_id, refund_transaction_id, amount_minor, currency_code, currency_precision, destination_account_id, category_id, arrived_at, confirmed_at, created_at, confirmation_marker) " +
                        "VALUES ('ledger-a','relationship-m','request-m','fp-m','tx-existing','tx-refund-m',1000,'CNY',2,'account-bank-a','category-daily','2026-02-02T02:00:00Z','2026-02-02T02:00:05Z','2026-02-02T02:00:05Z','explicit_manual_confirm')"
                driver.execute(null, validRow, 0)
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM manual_refund_receipt"))

                // amount_minor > 0
                assertFailsWith<SQLException> {
                    driver.execute(null, validRow.replace("'request-m'", "'request-zero'").replace(",1000,", ",0,"), 0)
                }
                // the explicit confirmation marker is the only accepted value
                assertFailsWith<SQLException> {
                    driver.execute(
                        null,
                        validRow
                            .replace("'request-m'", "'request-marker'")
                            .replace("'explicit_manual_confirm'", "'explicit_manual_save'"),
                        0,
                    )
                }
                // composite foreign keys keep both transactions in the same ledger
                assertFailsWith<SQLException> {
                    driver.execute(
                        null,
                        validRow
                            .replace("'request-m'", "'request-cross-ledger'")
                            .replace("'tx-refund-m'", "'tx-other-ledger'"),
                        0,
                    )
                }
                // no UPDATE path at all
                assertFailsWith<SQLException> {
                    driver.execute(null, "UPDATE manual_refund_receipt SET amount_minor = 1 WHERE ledger_id = 'ledger-a' AND request_id = 'request-m'", 0)
                }
                assertEquals(1000L, queryCount(driver, "SELECT amount_minor FROM manual_refund_receipt WHERE request_id = 'request-m'"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
