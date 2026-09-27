package com.unifiedledger.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * P7-06 06.D device-gate defect 1 (D-183): the isolated-database corruption policy MUST NOT delete.
 *
 * The platform's `DefaultDatabaseErrorHandler.onCorruption` CLOSES and DELETES the database file
 * (AOSP `DefaultDatabaseErrorHandler.java:53-108`), and `SQLiteDatabase` substitutes that default
 * whenever an open passes a null handler (AOSP `SQLiteDatabase.java:493`). Before this batch both
 * isolated opens passed null, so merely PROBING an unverifiable 06.D recovery candidate deleted it
 * on device (logcat: "Corruption reported by sqlite on database", "deleting the database file").
 *
 * The fix is [PreservingIsolatedDatabaseErrorHandler], whose `onCorruption` performs NO file or
 * database operation and throws the typed [LedgerIsolatedDatabaseCorruptionException] instead. Its
 * body makes no framework call, so — unlike the adapter's open, which needs a real `SQLiteDatabase`
 * — the policy itself is host-testable here: this drives it with `null` (the body must not dereference
 * its parameter) and asserts the typed fail-closed throw. A regression that delegated to the
 * platform default, or that read the handle, cannot satisfy both assertions.
 *
 * The device-level end-to-end half (a real corrupt candidate surviving a real probe) is pinned by
 * `AndroidRestoreRecoveryInstrumentedTest.aStructurallyCorruptCandidateIsNeverAdopted`; this host
 * test pins the policy seam itself, which is what a device run cannot localise.
 */
class IsolatedDatabaseCorruptionHandlerTest {
    @Test
    fun onCorruptionThrowsTheTypedFailClosedSignalWithoutTouchingTheHandle() {
        // The nullable parameter is deliberate: the handler must not dereference it. Passing null
        // proves the body performs no handle call (a `.path`/`.isOpen()`/`.close()` read would throw
        // a NullPointerException here instead of the typed signal).
        val thrown =
            assertFailsWith<LedgerIsolatedDatabaseCorruptionException> {
                PreservingIsolatedDatabaseErrorHandler.onCorruption(null)
            }

        assertEquals(
            "isolated database corruption detected; the original file is preserved; fail-closed",
            thrown.message,
        )
    }
}
