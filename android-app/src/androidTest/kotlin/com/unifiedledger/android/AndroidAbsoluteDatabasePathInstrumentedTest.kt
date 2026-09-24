package com.unifiedledger.android

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unifiedledger.data.AndroidLedgerDatabaseHandle
import com.unifiedledger.ui.ImportFilePickResultChannel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * P0 hotfix (defect 1): on-device regression guard for the generation driver path. This drives the
 * REAL production function [openAndroidStableStorageLedger] with a recording `openDriver`, and
 * asserts that the name the production sequence actually hands to the driver is ABSOLUTE for both
 * the fresh-install and the legacy-upgrade plans.
 *
 * Why this is the guard and not merely a positive control: the pre-fix code converted the absolute
 * generation main file into the RELATIVE name `ledger-generations/gen-1/ledger.db` before calling
 * the driver, and that relative name is rejected by the framework — androidx
 * FrameworkSQLiteOpenHelper passes the raw name to `Context.getDatabasePath`, whose
 * non-separator-prefixed branch calls `ContextImpl.makeFilename`, which throws
 * `IllegalArgumentException("File " + name + " contains a path separator")`. Recording the name at
 * the production seam makes that regression go red here: reintroducing a relative-name conversion
 * at the `openDriver(androidGenerationDriverName(target.mainFile))` call site makes the recorded
 * name non-absolute and fails the `File(name).isAbsolute` assertions below. (Calling
 * `createAndroidLedgerDatabase` directly with an absolute path, as a bare framework test would,
 * does NOT guard the production computation — the pre-fix code also accepted an absolute path
 * handed to it directly.)
 *
 * The injected `openDriver` records the name and then throws a sentinel, so the test never builds
 * the full business graph; the sequence's own behaviour on that throw is irrelevant here.
 */
@RunWith(AndroidJUnit4::class)
class AndroidAbsoluteDatabasePathInstrumentedTest {
    private val createdDirectories = mutableListOf<File>()

    @After
    fun cleanUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Test-injected files only; deleteDatabase also removes the -wal/-shm companions.
        context.deleteDatabase(LEGACY_DATABASE_NAME)
        for (directory in createdDirectories) {
            directory.deleteRecursively()
        }
        createdDirectories.clear()
    }

    @Test
    fun theFreshInstallPlanHandsAnAbsoluteNameToTheDriver() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // A genuine fresh install: no generations directory and no legacy database.
        context.deleteDatabase(LEGACY_DATABASE_NAME)
        val generationsDirectory = File(databaseDirectory(context), "ledger-generations")
        generationsDirectory.deleteRecursively()
        createdDirectories += generationsDirectory

        val recorded = mutableListOf<String>()
        openRecordingProductionOpen(context, recorded)

        assertEquals(1, recorded.size)
        assertTrue("the fresh-install driver name must be absolute: ${recorded.single()}", File(recorded.single()).isAbsolute)
        assertTrue(recorded.single().endsWith("ledger.db"))
    }

    @Test
    fun theLegacyUpgradePlanHandsAnAbsoluteNameToTheDriver() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val generationsDirectory = File(databaseDirectory(context), "ledger-generations")
        generationsDirectory.deleteRecursively()
        createdDirectories += generationsDirectory
        // A legacy database at databases/ledger.db selects the non-destructive upgrade plan.
        val legacy = context.getDatabasePath(LEGACY_DATABASE_NAME)
        legacy.parentFile?.mkdirs()
        legacy.writeBytes(SQLITE_HEADER + ByteArray(1024))

        val recorded = mutableListOf<String>()
        openRecordingProductionOpen(context, recorded)

        assertEquals(1, recorded.size)
        assertTrue("the legacy-upgrade driver name must be absolute: ${recorded.single()}", File(recorded.single()).isAbsolute)
        assertTrue(recorded.single().contains("ledger-generations"))
        assertTrue(recorded.single().endsWith("ledger.db"))
    }

    /**
     * Drives the real [openAndroidStableStorageLedger] with a recording `openDriver`. The sentinel
     * throw stops the sequence before it builds the graph; only the recorded name is asserted.
     */
    private fun openRecordingProductionOpen(
        context: android.content.Context,
        recorded: MutableList<String>,
    ) {
        val port =
            AndroidImportFilePickPort<Uri>(
                launchOpenDocument = {},
                resolveMetadata = { PickedSafFileMetadata(displayName = "", sizeBytes = null) },
                openInputStream = { null },
                onResult = {},
            )
        val openDriver: (String) -> AndroidLedgerDatabaseHandle = { name ->
            recorded += name
            throw RecordingOpenSentinel()
        }
        try {
            openAndroidStableStorageLedger(
                context = context,
                importFilePickPort = port,
                importPickChannel = ImportFilePickResultChannel(),
                openDriver = openDriver,
            )
        } catch (expected: RecordingOpenSentinel) {
            // Expected: the recording openDriver throws after recording the name.
        }
    }

    private fun databaseDirectory(context: android.content.Context): File = context.getDatabasePath("unused-placeholder").parentFile

    private companion object {
        const val LEGACY_DATABASE_NAME = "ledger.db"
        val SQLITE_HEADER: ByteArray = "SQLite format 3\u0000".encodeToByteArray()
    }
}

/** The sentinel the recording `openDriver` throws after recording the driver name. */
private class RecordingOpenSentinel : RuntimeException("recording openDriver sentinel")
