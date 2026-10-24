package com.unifiedledger.data

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P706-A02 (D-246; spec `docs/specs/2026-10-24-p706-a02-a06-acceptance-design.md` sections 2.2 and
 * 2.5): the desktop JDBC half of the WAL acceptance vector — a database opened in WAL journal mode,
 * a concurrent single-writer thread committing paired rows while the main connection runs
 * `VACUUM INTO` ([runSnapshotIntoOn]), and the snapshot then verified on a dedicated second
 * connection ([verifySnapshotOn]).
 *
 * This is the evidence the single-connection scenario in `BackupSnapshotDriverTest` cannot provide
 * (D-239: A02 lacked any test opening the database in WAL mode or writing concurrently during
 * export). The frozen concurrency-overlap guard (spec section 2.2 step 6) is asserted verbatim:
 * the writer commits at least one transaction before `VACUUM INTO` starts (version v_mid) and at
 * least one more after the snapshot verification connection has opened (final version v_end), and
 * the snapshot's version marker must satisfy `snapshot marker >= v_mid` and `v_end > snapshot
 * marker` — writes from inside the window are contained in the snapshot, and writes committed
 * after the window are provably excluded from it.
 *
 * The WAL premise is established by this test's own connections only; no product open path sets or
 * depends on WAL. The switch is asserted by reading the pragma result back, because SQLite
 * silently returns the original journal mode when the switch cannot be applied.
 */
class P706WalConcurrentSnapshotTest {
    /**
     * Shared latches and flags between the test thread and the writer thread. The writer keeps its
     * own driver: the two threads never share a connection, so no driver-level synchronization is
     * needed.
     */
    private class WriterState {
        val cancel = AtomicBoolean(false)

        /** Set by the test once the snapshot verification connection is open (the frozen guard's reference point). */
        val requirePostVerificationCommit = AtomicBoolean(false)
        val lastCommittedVersion = AtomicLong(0L)
        val failure = AtomicReference<Throwable?>(null)
        val firstCommit = CountDownLatch(1)
        val postVerificationCommit = CountDownLatch(1)
    }

    private fun tempDir(): File {
        val directory = File.createTempFile("p706-a02-wal", "")
        directory.delete()
        directory.mkdirs()
        directory.deleteOnExit()
        return directory
    }

    /**
     * Runs a PRAGMA that returns its result as a row (the journal-mode switch reports the mode it
     * actually applied) and reads the single returned value back.
     */
    private fun readJournalMode(
        driver: SqlDriver,
        pragma: String,
    ): String? {
        var mode: String? = null
        driver
            .executeQuery(
                null,
                pragma,
                { cursor ->
                    if (cursor.next().value) mode = cursor.getString(0)
                    QueryResult.Unit
                },
                0,
                null,
            ).value
        return mode
    }

    /** Reads a single-row single-value integer query, asserting the row count is exactly one. */
    private fun readSingleLong(
        driver: SqlDriver,
        sql: String,
        label: String,
    ): Long {
        var value = 0L
        var rows = 0
        driver
            .executeQuery(
                null,
                sql,
                { cursor ->
                    while (cursor.next().value) {
                        rows += 1
                        if (rows == 1) value = cursor.getLong(0) ?: 0L
                    }
                    QueryResult.Unit
                },
                0,
                null,
            ).value
        assertEquals(1, rows, "$label must return exactly one row")
        return value
    }

    /**
     * The bounded writer: each iteration is one real transaction (the driver's own transaction
     * wrapper, which turns the JDBC connection's auto-commit off — raw `BEGIN`/`COMMIT` statements
     * would be auto-committed away) that replaces the previous pair with a new pair (two rows,
     * members 0 and 1, one shared version stamp) and advances the single-row version marker, so a
     * consistent snapshot must show exactly one complete pair whose stamp equals the marker
     * (orphan or mixed-version shapes become decidable). The loop stops on the cancel flag or the
     * explicit iteration cap, whichever comes first. A failing transaction is rolled back by the
     * wrapper before the failure propagates.
     */
    private fun runWriter(
        driver: SqlDriver,
        state: WriterState,
    ) {
        val transacter = object : TransacterImpl(driver) {}
        var version = 0L
        try {
            while (!state.cancel.get() && version < MAX_WRITER_TRANSACTIONS) {
                version += 1
                val stamp = version
                transacter.transaction(noEnclosing = false) {
                    driver.execute(null, "DELETE FROM pair", 0)
                    driver.execute(
                        null,
                        "INSERT INTO pair (pair_member, version_stamp) VALUES (0, $stamp), (1, $stamp)",
                        0,
                    )
                    driver.execute(null, "UPDATE version_marker SET marker_value = $stamp", 0)
                }
                state.lastCommittedVersion.set(version)
                if (version == 1L) state.firstCommit.countDown()
                if (state.requirePostVerificationCommit.get()) {
                    state.postVerificationCommit.countDown()
                }
            }
        } catch (failure: Throwable) {
            state.failure.compareAndSet(null, failure)
            // Unblock the test thread whichever wait it is in; it observes the failure itself.
            state.firstCommit.countDown()
            state.postVerificationCommit.countDown()
        }
    }

    @Test
    fun snapshotUnderConcurrentWalWritesCapturesASingleSelfConsistentPointInTime() {
        val dir = tempDir()
        dir.deleteOnExit()
        val mainFile = File(dir, "ledger.db")
        val snapshotFile = File(dir, "snapshot")
        val state = WriterState()
        var writer: Thread? = null
        val mainDriver = JdbcSqliteDriver("jdbc:sqlite:${mainFile.path}")
        try {
            // Step 1 (spec section 2.2): establish the WAL premise and read the applied mode back.
            // A failed switch silently returns the old mode, so without this read-back the WAL
            // premise could not be claimed.
            val appliedMode = readJournalMode(mainDriver, "PRAGMA journal_mode = WAL")
            assertEquals("wal", appliedMode, "the journal mode switch must report wal, got $appliedMode")

            mainDriver.execute(
                null,
                "CREATE TABLE pair (id INTEGER PRIMARY KEY, pair_member INTEGER NOT NULL, version_stamp INTEGER NOT NULL)",
                0,
            )
            mainDriver.execute(null, "CREATE TABLE version_marker (marker_value INTEGER NOT NULL)", 0)
            mainDriver.execute(null, "INSERT INTO version_marker (marker_value) VALUES (0)", 0)

            // Step 2 (spec section 2.2): the writer connection on the same file. WAL is persistent
            // on the database file, so the second connection must follow it.
            val writerDriver = JdbcSqliteDriver("jdbc:sqlite:${mainFile.path}")
            try {
                val writerMode = readJournalMode(writerDriver, "PRAGMA journal_mode")
                assertEquals(
                    "wal",
                    writerMode,
                    "the second connection must follow the file's persistent WAL mode, got $writerMode",
                )
                val startedWriter = Thread { runWriter(writerDriver, state) }
                writer = startedWriter
                startedWriter.start()

                // Frozen overlap guard, first half: at least one committed transaction before
                // VACUUM INTO starts (spec section 2.2 step 6).
                assertTrue(
                    state.firstCommit.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "the writer never committed its first transaction",
                )
                val earlyFailure = state.failure.get()
                assertTrue(earlyFailure == null, "the writer failed before the snapshot window: $earlyFailure")
                val versionBeforeVacuum =
                    readSingleLong(
                        mainDriver,
                        "SELECT marker_value FROM version_marker",
                        "the pre-snapshot version marker",
                    )

                // Step 3 (spec section 2.2): the subject action, taken while the writer runs.
                runSnapshotIntoOn(mainDriver, snapshotFile.path)
                assertTrue(
                    snapshotFile.exists() && snapshotFile.length() > 0,
                    "VACUUM INTO must produce a non-empty snapshot file",
                )

                // Step 4 (spec section 2.2): dedicated verification connection on the snapshot file.
                val verificationDriver = JdbcSqliteDriver("jdbc:sqlite:${snapshotFile.path}")
                try {
                    val verification = verifySnapshotOn(verificationDriver)
                    assertTrue(verification.integrityOk, "the snapshot must pass the integrity check")
                    val snapshotMarker =
                        readSingleLong(
                            verificationDriver,
                            "SELECT marker_value FROM version_marker",
                            "the snapshot version marker",
                        )

                    // Frozen overlap guard, second half: with the verification connection now open,
                    // require one more committed transaction and wait until it lands.
                    state.requirePostVerificationCommit.set(true)
                    assertTrue(
                        state.postVerificationCommit.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                        "the writer never committed after the verification connection opened",
                    )

                    // Self-consistency of the captured point in time (A02-a3): single marker
                    // value, an even pair count, a complete pair, no orphan row.
                    val pairRowCount =
                        readSingleLong(
                            verificationDriver,
                            "SELECT COUNT(*) FROM pair",
                            "the snapshot pair row count",
                        )
                    assertEquals(0L, pairRowCount % 2L, "the snapshot pair row count must be even, got $pairRowCount")
                    assertEquals(2L, pairRowCount, "the snapshot must hold exactly one complete pair, got $pairRowCount rows")
                    val firstMembers =
                        readSingleLong(
                            verificationDriver,
                            "SELECT COUNT(*) FROM pair WHERE pair_member = 0",
                            "the snapshot first-member count",
                        )
                    val secondMembers =
                        readSingleLong(
                            verificationDriver,
                            "SELECT COUNT(*) FROM pair WHERE pair_member = 1",
                            "the snapshot second-member count",
                        )
                    assertEquals(1L, firstMembers, "the snapshot pair must contain exactly one first member")
                    assertEquals(1L, secondMembers, "the snapshot pair must contain exactly one second member")

                    // No mixed versions (A02-a4): every row carries the snapshot marker's version.
                    val mixedVersionRows =
                        readSingleLong(
                            verificationDriver,
                            "SELECT COUNT(*) FROM pair WHERE version_stamp <> $snapshotMarker",
                            "the mixed-version row count",
                        )
                    assertEquals(0L, mixedVersionRows, "the snapshot must not mix versions, got $mixedVersionRows mismatched rows")

                    // Bounded shutdown: the writer stops on the cancel flag and is joined.
                    state.cancel.set(true)
                    startedWriter.join(TimeUnit.SECONDS.toMillis(AWAIT_TIMEOUT_SECONDS))
                    assertFalse(startedWriter.isAlive, "the writer thread must terminate before the test ends")
                    val lateFailure = state.failure.get()
                    assertTrue(lateFailure == null, "the writer failed during the run: $lateFailure")

                    // Frozen overlap inequalities (spec section 2.2 step 6), asserted on the
                    // measured values only.
                    assertTrue(
                        snapshotMarker >= versionBeforeVacuum,
                        "the snapshot must contain writes committed before the vacuum (marker=$snapshotMarker, v_mid=$versionBeforeVacuum)",
                    )
                    val finalVersion = state.lastCommittedVersion.get()
                    assertTrue(
                        finalVersion > snapshotMarker,
                        "the writer must commit after the verification connection opened and stay excluded " +
                            "from the snapshot (v_end=$finalVersion, marker=$snapshotMarker)",
                    )

                    // Restore-equivalence minimal exercise (A02-a5): a third, brand-new connection
                    // fully opens the snapshot file and reads it to the end.
                    val replayDriver = JdbcSqliteDriver("jdbc:sqlite:${snapshotFile.path}")
                    try {
                        val replayMarker =
                            readSingleLong(
                                replayDriver,
                                "SELECT marker_value FROM version_marker",
                                "the replayed version marker",
                            )
                        assertEquals(snapshotMarker, replayMarker, "the replayed marker must match the snapshot marker")
                        var replayRows = 0L
                        replayDriver
                            .executeQuery(
                                null,
                                "SELECT id FROM pair ORDER BY id",
                                { cursor ->
                                    while (cursor.next().value) {
                                        replayRows += 1
                                    }
                                    QueryResult.Unit
                                },
                                0,
                                null,
                            ).value
                        assertEquals(pairRowCount, replayRows, "the replayed row count must match the snapshot")
                    } finally {
                        replayDriver.close()
                    }
                } finally {
                    verificationDriver.close()
                }
            } finally {
                // The writer may still be running if an assertion above threw: stop it first, then
                // join so its driver is never closed underneath it.
                state.cancel.set(true)
                writer?.join(TimeUnit.SECONDS.toMillis(AWAIT_TIMEOUT_SECONDS))
                writerDriver.close()
            }
        } finally {
            mainDriver.close()
            mainFile.delete()
            File(dir, "ledger.db-wal").delete()
            File(dir, "ledger.db-shm").delete()
        }
    }

    private companion object {
        const val AWAIT_TIMEOUT_SECONDS = 30L
        const val MAX_WRITER_TRANSACTIONS = 100_000L
    }
}
