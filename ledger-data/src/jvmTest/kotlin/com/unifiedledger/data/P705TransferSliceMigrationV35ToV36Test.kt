package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.data.db.LedgerDatabase
import java.nio.file.Files
import java.util.Properties
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * P7-05 transfer slice (D-228, D-255) v35 -> v36 correction-family rebuild columnInfo evidence.
 *
 * The edge `35.sqm` rebuilds the correction-request family: `transaction_correction_request` is
 * re-created with `category_id` relaxed to nullable plus the two transfer tail columns
 * (`destination_account_id` and `fee_minor INTEGER NOT NULL DEFAULT 0`), while
 * `transaction_correction_receipt` is re-created with its unchanged shape so the parent swap
 * keeps the ON DELETE CASCADE foreign key intact.
 *
 * Contract notes (D-219; CONTRIBUTING "Schema 迁移批次清单" item 3): a table rebuild cannot be
 * judged by raw `sqlite_master` text, so the fresh-vs-migrated contract is `PRAGMA table_info`
 * compared per column. Each column renders as name|type|notnull|default|pk in declaration order;
 * the default slot extends the frozen name|type|notnull|pk baseline because this edge carries the
 * family's only declared column default (fee_minor DEFAULT 0). The `p705_rebuild_guard` table is
 * transient inside the migration and exists in neither terminal state, so it is out of scope.
 *
 * Populated-row value preservation and whole-migration rollback for this edge are owned by
 * `LedgerDatabaseMigrationTest.populatedV35ToV36CorrectionRebuildPreservesCommittedRowsValueForValue`
 * and `LedgerDatabaseMigrationTest.lateV35ToV36FailureRollsBackCorrectionRebuildAndKeepsLegacyRows`;
 * this class does not repeat that evidence.
 */
class P705TransferSliceMigrationV35ToV36Test {
    /** The two tables `35.sqm` rebuilds; the migration's transient guard table is out of scope. */
    private val rebuiltTables =
        listOf(
            "transaction_correction_request",
            "transaction_correction_receipt",
        )

    @Test
    fun versionThirtySixIsCurrent() {
        assertEquals(36, LedgerDatabase.Schema.version)
    }

    @Test
    fun freshSchemaEqualsTheV35ToV36MigrationByTheColumnInfoContract() {
        val freshPath = Files.createTempFile("p705-v36-fresh-", ".db")
        val migratedPath = Files.createTempFile("p705-v35-v36-migrated-", ".db")
        val freshUrl = "jdbc:sqlite:${freshPath.absolutePathString()}"
        val migratedUrl = "jdbc:sqlite:${migratedPath.absolutePathString()}"
        try {
            JdbcSqliteDriver(freshUrl, migrationProperties()).use { driver -> LedgerDatabase.Schema.create(driver) }
            stageV35(migratedUrl)
            // The staged world is genuinely v35 for this family — category_id NOT NULL and no
            // transfer tail columns — so the migration below is load-bearing, not a no-op.
            JdbcSqliteDriver(migratedUrl, migrationProperties()).use { driver ->
                val staged = columnInfo(driver, "transaction_correction_request")
                assertEquals(true, staged.any { it == "category_id|TEXT|1|null|0" })
                assertEquals(
                    0,
                    staged.count { it.startsWith("destination_account_id|") || it.startsWith("fee_minor|") },
                )
            }
            JdbcSqliteDriver(migratedUrl, migrationProperties()).use { driver ->
                LedgerDatabase(driver).transaction { LedgerDatabase.Schema.migrate(driver, 35, 36) }
            }
            JdbcSqliteDriver(freshUrl, migrationProperties()).use { fresh ->
                JdbcSqliteDriver(migratedUrl, migrationProperties()).use { migrated ->
                    rebuiltTables.forEach { table ->
                        assertEquals(
                            columnInfo(fresh, table),
                            columnInfo(migrated, table),
                            "$table fresh-vs-migrated columnInfo drift",
                        )
                    }
                }
            }
        } finally {
            Files.deleteIfExists(freshPath)
            Files.deleteIfExists(migratedPath)
        }
    }

    @Test
    fun sameVersionReopenIsANoOp() {
        val path = Files.createTempFile("p705-v36-reopen-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                driver.execute(null, "PRAGMA user_version = 36", 0)
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                val before = rebuiltTables.associateWith { columnInfo(driver, it) }
                LedgerDatabase.Schema.migrate(driver, 36, 36)
                assertEquals(36L, queryLong(driver, "PRAGMA user_version"))
                rebuiltTables.forEach { table ->
                    assertEquals(before.getValue(table), columnInfo(driver, table), table)
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    /*
     * The v35 fixture is a create-then-downgrade world: the receipt table keeps its created
     * shape (v35 and v36 are identical for it), so only the request parent is rebuilt in the
     * legacy definition before the world is stamped as v35. Only this family's own migration
     * edge runs on such a downgraded world, never a full chain.
     */

    /** `Schema.create` at the current version with `transaction_correction_request` in its v35 shape, stamped as v35. */
    private fun stageV35(url: String) {
        JdbcSqliteDriver(url, migrationProperties()).use { driver ->
            LedgerDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = Off", 0)
            driver.execute(null, "DROP TABLE transaction_correction_request", 0)
            driver.execute(null, v35CorrectionRequestDdl, 0)
            driver.execute(null, "PRAGMA foreign_keys = On", 0)
            driver.execute(null, "PRAGMA user_version = 35", 0)
        }
    }

    /** The pre-slice request definition: `category_id TEXT NOT NULL`, no `destination_account_id`, no `fee_minor`. */
    private val v35CorrectionRequestDdl =
        """
        CREATE TABLE transaction_correction_request (
          ledger_id TEXT NOT NULL,
          request_id TEXT NOT NULL,
          transaction_id TEXT NOT NULL,
          expected_current_version_id TEXT NOT NULL,
          note TEXT,
          statistics_at TEXT NOT NULL,
          amount_minor INTEGER NOT NULL,
          currency_code TEXT NOT NULL,
          currency_precision INTEGER NOT NULL CHECK (currency_precision >= 0),
          category_id TEXT NOT NULL,
          funding_account_id TEXT NOT NULL,
          confirmation_marker TEXT NOT NULL CHECK (confirmation_marker = 'explicit_manual_save'),
          PRIMARY KEY (ledger_id, request_id),
          FOREIGN KEY (transaction_id, ledger_id)
            REFERENCES ledger_transaction(transaction_id, ledger_id) DEFERRABLE INITIALLY DEFERRED
        );
        """.trimIndent()

    private fun migrationProperties(): Properties = Properties().apply { setProperty("foreign_keys", "true") }

    private fun queryLong(
        driver: JdbcSqliteDriver,
        sql: String,
    ): Long =
        driver
            .executeQuery(
                null,
                sql,
                { cursor ->
                    check(cursor.next().value)
                    app.cash.sqldelight.db.QueryResult
                        .Value(requireNotNull(cursor.getLong(0)))
                },
                0,
            ).value

    /** `PRAGMA table_info` rendered as name|type|notnull|default|pk rows in declaration order. */
    private fun columnInfo(
        driver: JdbcSqliteDriver,
        table: String,
    ): List<String> =
        driver
            .executeQuery(
                null,
                "PRAGMA table_info($table)",
                { cursor ->
                    val rows =
                        buildList {
                            while (cursor.next().value) {
                                add("${cursor.getString(1)}|${cursor.getString(2)}|${cursor.getLong(3)}|${cursor.getString(4)}|${cursor.getLong(5)}")
                            }
                        }
                    app.cash.sqldelight.db.QueryResult
                        .Value(rows)
                },
                0,
            ).value
}
