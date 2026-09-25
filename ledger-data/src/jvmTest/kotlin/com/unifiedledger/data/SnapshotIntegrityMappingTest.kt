package com.unifiedledger.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P2-D fix (06.B review): JVM tests for [snapshotIntegrityOk], the pure `PRAGMA integrity_check`
 * rows -> integrityOk mapping shared by the SqlDelight adapter ([verifySnapshotOn]) and the Android
 * framework adapter (`verifyAndroidSnapshotFile`). Before this extraction each adapter inlined its
 * own cursor fold and nothing pinned the mapping, so a regression that always returned `true` (the
 * Android valid-file test still passes; the corrupt test takes its `failure != null` branch) had no
 * test to go red.
 *
 * Rule (P3-P): OK only when at least one row is present AND every row is exactly `ok`.
 */
class SnapshotIntegrityMappingTest {
    @Test
    fun zeroRowsIsNotOk() {
        // SQLite always returns at least one row for integrity_check; a zero-row read is a broken
        // surface, never a clean database.
        assertFalse(snapshotIntegrityOk(emptyList()))
    }

    @Test
    fun aSingleOkRowIsOk() {
        assertTrue(snapshotIntegrityOk(listOf("ok")))
    }

    @Test
    fun aSingleErrorRowIsNotOk() {
        assertFalse(snapshotIntegrityOk(listOf("*** in database main ***\nPage 3: broken")))
    }

    @Test
    fun aNullRowIsNotOk() {
        // A null cell is not the literal "ok" and must not be treated as clean.
        assertFalse(snapshotIntegrityOk(listOf(null)))
    }

    @Test
    fun multipleErrorRowsAreNotOk() {
        assertFalse(snapshotIntegrityOk(listOf("row 1: error", "row 2: error")))
    }

    @Test
    fun anOkFollowedByAnErrorRowIsNotOk() {
        // AND-all-rows: a later error row must fail the check. This is the shape a last-row-wins
        // fold would misreport (it would return the last row's value).
        assertFalse(snapshotIntegrityOk(listOf("ok", "row 2: error")))
    }

    @Test
    fun anErrorFollowedByOkIsNotOk() {
        assertFalse(snapshotIntegrityOk(listOf("row 1: error", "ok")))
    }

    @Test
    fun caseAndWhitespaceAreSignificant() {
        // The engine emits the exact literal `ok`; anything else (case, padding) is not clean.
        assertFalse(snapshotIntegrityOk(listOf("OK")))
        assertFalse(snapshotIntegrityOk(listOf(" ok ")))
    }
}
