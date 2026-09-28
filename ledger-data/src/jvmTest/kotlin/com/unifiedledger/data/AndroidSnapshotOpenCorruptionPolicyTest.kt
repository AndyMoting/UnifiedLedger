package com.unifiedledger.data

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P7-06 06.D device-gate finding DG-1 (D-183): the 06.B snapshot verification open must pass the
 * SAME non-deleting corruption handler as the 06.D isolated opens.
 *
 * [verifyAndroidSnapshotFile] used the 3-argument
 * `SQLiteDatabase.openDatabase(snapshotPath, null, SQLiteDatabase.OPEN_READONLY)` overload. That
 * overload forwards a `null` error handler, and the `SQLiteDatabase` constructor substitutes
 * `new DefaultDatabaseErrorHandler()` (AOSP `SQLiteDatabase.java:493`), whose `onCorruption` CLOSES
 * then DELETES the database file (AOSP `DefaultDatabaseErrorHandler.java:53-108`). So merely
 * verifying a CORRUPT export snapshot destroyed it. A read-only open does not help: the platform
 * invokes the default handler on the corruption path regardless of flags. The fix passes the shared
 * [PreservingIsolatedDatabaseErrorHandler].
 *
 * WHY A SOURCE-LEVEL GUARD AND NOT BEHAVIOUR: the open takes a real framework `SQLiteDatabase`, and
 * `androidHostTest` runs without Robolectric, so no host test can execute it. The handler's OWN
 * policy is behaviour-pinned on the host by [IsolatedDatabaseCorruptionHandlerTest] (it throws the
 * typed signal and touches no handle) and on device by
 * `AndroidBackupSnapshotVerificationInstrumentedTest.aCorruptSnapshotIsNeverDeletedByVerification`
 * (a corrupt fixture survives byte-identically). This guard pins the remaining seam those two cannot:
 * that the snapshot open is WIRED to the shared handler. Reverting the open to the 3-argument
 * null-handler overload turns this test RED without a device.
 */
class AndroidSnapshotOpenCorruptionPolicyTest {
    @Test
    fun theSnapshotVerificationOpenPassesTheSharedNonDeletingHandler() {
        val source = repositoryFile("ledger-data/src/androidMain/kotlin/com/unifiedledger/data/AndroidLedgerDatabaseHandle.kt").let(Files::readString)

        val openArguments = openDatabaseArgumentsIn(source, "verifyAndroidSnapshotFile")
        assertTrue(
            openArguments.contains("PreservingIsolatedDatabaseErrorHandler"),
            "verifyAndroidSnapshotFile must pass PreservingIsolatedDatabaseErrorHandler (DG-1, D-183): $openArguments",
        )
        assertTrue(
            openArguments.contains("SQLiteDatabase.OPEN_READONLY"),
            "the snapshot open must stay READ-ONLY (the deletion guard is the handler, not the flags)",
        )
        assertFalse(
            isThreeArgumentNullHandlerOpen(openArguments),
            "the 3-argument null-handler overload (the destructive shape) must not be used by the snapshot open: $openArguments",
        )
    }

    @Test
    fun theHandlerIsReusedFromItsDeclaringFileAndNotDuplicated() {
        // The fix must REUSE the one handler object the 06.D batch introduced (a second object would
        // be a drift surface: a later edit could weaken one and leave the other deleting).
        val snapshotFile = repositoryFile("ledger-data/src/androidMain/kotlin/com/unifiedledger/data/AndroidLedgerDatabaseHandle.kt").let(Files::readString)
        assertFalse(
            snapshotFile.contains("object PreservingIsolatedDatabaseErrorHandler"),
            "the snapshot file must reference the shared handler, not declare a second one",
        )
        val driverFile = repositoryFile("ledger-data/src/androidMain/kotlin/com/unifiedledger/data/AndroidFrameworkSqlDriver.kt").let(Files::readString)
        assertTrue(
            driverFile.contains("internal object PreservingIsolatedDatabaseErrorHandler : DatabaseErrorHandler"),
            "the shared handler must stay declared in AndroidFrameworkSqlDriver.kt",
        )
    }

    /**
     * The argument list of the `SQLiteDatabase.openDatabase(...)` call inside [functionName]'s body,
     * from the opening parenthesis to its matching close. Scoped to the function so it can never be
     * satisfied by a handler argument somewhere else in the file.
     */
    private fun openDatabaseArgumentsIn(
        source: String,
        functionName: String,
    ): String {
        val functionStart = source.indexOf("fun $functionName(")
        check(functionStart >= 0) { "function $functionName not found" }
        val openCallStart = source.indexOf("SQLiteDatabase.openDatabase(", functionStart)
        check(openCallStart >= 0) { "no SQLiteDatabase.openDatabase call inside $functionName" }
        val argumentsStart = openCallStart + "SQLiteDatabase.openDatabase(".length
        val argumentsEnd = source.indexOf(')', argumentsStart)
        check(argumentsEnd > argumentsStart) { "the open call's argument list is unbalanced" }
        return source.substring(argumentsStart, argumentsEnd)
    }

    /** The destructive 3-argument shape: a null handler argument with no explicit handler after it. */
    private fun isThreeArgumentNullHandlerOpen(arguments: String): Boolean {
        val normalizedArguments = arguments.split(',').map { it.trim() }
        return normalizedArguments.size == 3 && normalizedArguments[1] == "null"
    }

    /** Resolves a path relative to the repository root by walking up to the settings file. */
    private fun repositoryFile(relative: String): Path {
        var candidate = Path.of(System.getProperty("user.dir"))
        repeat(8) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)
            candidate = candidate.parent ?: error("repository root not found")
        }
        error("repository root not found")
    }
}
