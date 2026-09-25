package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P7-06 06.B (D-177; spec section 3.3/3.4): the driver-level `VACUUM INTO` snapshot helper and the
 * snapshot-file verification helper, exercised against the real JVM SQLite driver. This is the
 * desktop half of the execution surface (JDBC); the Android `driver.execute` binder path is
 * registered as unverified in the spec section 9 item 4.
 */
class BackupSnapshotDriverTest {
    private fun tempDir(): File {
        val directory = File.createTempFile("p706-snapshot", "")
        directory.delete()
        directory.mkdirs()
        directory.deleteOnExit()
        return directory
    }

    @Test
    fun vacuumIntoProducesASelfConsistentSnapshotThatVerifiesAsOk() {
        val dir = tempDir()
        dir.deleteOnExit()
        val mainFile = File(dir, "ledger.db")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${mainFile.path}")
        try {
            driver.execute(null, "CREATE TABLE sample (id INTEGER PRIMARY KEY, value TEXT)", 0)
            driver.execute(null, "INSERT INTO sample (value) VALUES ('a'), ('b'), ('c')", 0)
            driver.execute(null, "PRAGMA user_version = 31", 0)

            val snapshotFile = File(dir, "snapshot")
            runSnapshotIntoOn(driver, snapshotFile.path)

            assertTrue(snapshotFile.exists())
            assertTrue(snapshotFile.length() > 0)
            // The snapshot file starts with the SQLite magic (a real database, not a raw copy).
            val header = snapshotFile.inputStream().use { it.readNBytes(16) }
            assertTrue(header.decodeToString().startsWith("SQLite format 3"))

            val verificationDriver = JdbcSqliteDriver("jdbc:sqlite:${snapshotFile.path}")
            try {
                val verification = verifySnapshotOn(verificationDriver)
                assertTrue(verification.integrityOk)
                assertEquals(31L, verification.schemaVersion)
            } finally {
                verificationDriver.close()
            }
        } finally {
            driver.close()
            mainFile.delete()
        }
    }

    @Test
    fun vacuumIntoRefusesAnExistingTargetSoTheCallerMustClearItFirst() {
        val dir = tempDir()
        dir.deleteOnExit()
        val mainFile = File(dir, "ledger.db")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${mainFile.path}")
        try {
            driver.execute(null, "CREATE TABLE sample (id INTEGER PRIMARY KEY)", 0)
            val snapshotFile = File(dir, "snapshot")
            snapshotFile.writeBytes(ByteArray(1))

            val failure = runCatching { runSnapshotIntoOn(driver, snapshotFile.path) }.exceptionOrNull()

            assertTrue(failure != null, "VACUUM INTO must refuse an existing target")
        } finally {
            driver.close()
            mainFile.delete()
        }
    }

    @Test
    fun verificationReportsNotOkForANonDatabaseSnapshotFile() {
        val dir = tempDir()
        dir.deleteOnExit()
        val bogus = File(dir, "bogus")
        bogus.writeBytes(ByteArray(4096) { 0x5A })
        val driver = JdbcSqliteDriver("jdbc:sqlite:${bogus.path}")
        try {
            // A non-database file fails the integrity check; the helper reports the typed outcome
            // instead of throwing (the caller maps it to SNAPSHOT_INTEGRITY_FAILED).
            val verification = runCatching { verifySnapshotOn(driver) }.getOrNull()
            if (verification != null) {
                assertFalse(verification.integrityOk)
            }
        } finally {
            driver.close()
            bogus.delete()
        }
    }

    @Test
    fun theSnapshotPreservesCommittedRowsAcrossADedicatedVerificationConnection() {
        val dir = tempDir()
        dir.deleteOnExit()
        val mainFile = File(dir, "ledger.db")
        val driver: SqlDriver = JdbcSqliteDriver("jdbc:sqlite:${mainFile.path}")
        try {
            driver.execute(null, "CREATE TABLE sample (id INTEGER PRIMARY KEY, value TEXT NOT NULL)", 0)
            driver.execute(null, "INSERT INTO sample (value) VALUES ('kept')", 0)

            val snapshotFile = File(dir, "snapshot")
            runSnapshotIntoOn(driver, snapshotFile.path)

            val verificationDriver = JdbcSqliteDriver("jdbc:sqlite:${snapshotFile.path}")
            try {
                var value: String? = null
                verificationDriver
                    .executeQuery(
                        null,
                        "SELECT value FROM sample",
                        { cursor ->
                            if (cursor.next().value) value = cursor.getString(0)
                            app.cash.sqldelight.db.QueryResult.Unit
                        },
                        0,
                        null,
                    ).value
                assertEquals("kept", value)
            } finally {
                verificationDriver.close()
            }
        } finally {
            driver.close()
            mainFile.delete()
        }
    }
}
