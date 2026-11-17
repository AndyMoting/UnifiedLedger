package com.unifiedledger.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.data.db.LedgerDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * D-348: `import_evidence` must carry the (ledger_id, source_id) covering index that the
 * production review read probes once per output row.
 *
 * WHY THIS ASSERTS THE SCHEMA AND NOT A DURATION: the removed defect is a full scan of
 * `import_evidence` behind each of the two per-output-row probes (the `min(evidence_id)`
 * projection scalar and the active-link EXISTS), which made `importReviewRowsForLedgerPage` and
 * its four sibling reads cost O(candidates x evidence rows); the measured symptom was 85.6 ms per
 * row on a 51k-candidate rig reading. A wall-clock bound cannot police that on a CI host, so the
 * index itself is the contract and its shape is what these legs assert. The migrated leg carries
 * the weight: the fresh DDL alone would leave every installed database without the index.
 *
 * The v37 fixture is the current schema minus this batch's single object — dropping the index and
 * stamping `user_version = 37` reproduces the migration's starting point exactly, the
 * reverse-downgrade shape docs/CONTRIBUTING.md's schema checklist asks for.
 */
class ImportEvidenceProbeIndexTest {
    @Test
    fun freshSchemaIndexesTheEvidenceSourceProbe() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            assertSourceProbeIndex(driver)
        } finally {
            driver.close()
        }
    }

    @Test
    fun theVersionThirtySevenToCurrentEdgeCreatesTheSameIndex() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            driver.execute(null, "DROP INDEX import_evidence_by_source", 0)
            driver.execute(null, "PRAGMA user_version = 37", 0)
            assertEquals(
                emptyList<String>(),
                sourceProbeIndexes(driver),
                "the v37 fixture must carry no index the probe can use",
            )
            LedgerDatabase.Schema.migrate(driver, 37, LedgerDatabase.Schema.version)
            assertSourceProbeIndex(driver)
        } finally {
            driver.close()
        }
    }

    /** The named index, and its first two key columns in probe order. */
    private fun assertSourceProbeIndex(driver: JdbcSqliteDriver) {
        assertEquals(listOf("import_evidence_by_source"), sourceProbeIndexes(driver))
        assertEquals(listOf("ledger_id", "source_id"), indexColumns(driver, "import_evidence_by_source"))
    }

    /**
     * Every index on `import_evidence` whose first two key columns are (ledger_id, source_id).
     * `PRAGMA index_list` also reports SQLite's own auto-indexes, so the filter is on column shape
     * rather than on the index name.
     */
    private fun sourceProbeIndexes(driver: JdbcSqliteDriver): List<String> =
        queryStrings(driver, "PRAGMA index_list('import_evidence')", 1)
            .filter { name -> indexColumns(driver, name).take(2) == listOf("ledger_id", "source_id") }
            .sorted()

    private fun indexColumns(
        driver: JdbcSqliteDriver,
        index: String,
    ): List<String> = queryStrings(driver, "PRAGMA index_info('$index')", 2)

    private fun queryStrings(
        driver: JdbcSqliteDriver,
        sql: String,
        column: Int,
    ): List<String> =
        driver.executeQuery(
            null,
            sql,
            mapper = { cursor ->
                val values = mutableListOf<String>()
                while (cursor.next().value) values += (cursor.getString(column) ?: "")
                QueryResult.Value(values.toList())
            },
            parameters = 0,
        ).value
}
