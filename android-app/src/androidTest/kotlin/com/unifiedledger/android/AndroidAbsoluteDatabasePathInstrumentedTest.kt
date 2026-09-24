package com.unifiedledger.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unifiedledger.data.createAndroidLedgerDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * P0 hotfix (defect 1): on-device regression guard for the generation driver path. The Android
 * composition root hands [createAndroidLedgerDatabase] the ABSOLUTE generation main file
 * (`databases/ledger-generations/gen-1/ledger.db`). Passing that same file as a RELATIVE name
 * with a path separator makes androidx's FrameworkSQLiteOpenHelper route through
 * `Context.getDatabasePath`, whose non-absolute branch calls `ContextImpl.makeFilename`, which
 * throws `IllegalArgumentException("File " + name + " contains a path separator")`. The absolute
 * branch instead resolves the parent directory itself and works.
 *
 * This test drives the real production factory with an absolute path inside a subdirectory of the
 * app-private `databases/` directory (the exact generation layout shape) and asserts the open
 * succeeds, the file is created at that path, and the handle answers a query. It is the
 * instrumented counterpart of the JVM wiring assertion in `AndroidStableStorageTest`
 * (`theGenerationMainFileHandedToTheDriverIsAbsoluteOnBothGenerationPlans`); run it on the
 * managed emulator as gate evidence.
 */
@RunWith(AndroidJUnit4::class)
class AndroidAbsoluteDatabasePathInstrumentedTest {
    private val createdGenerationDirectories = mutableListOf<File>()

    @After
    fun cleanUpGenerationDirectories() {
        for (directory in createdGenerationDirectories) {
            directory.deleteRecursively()
        }
        createdGenerationDirectories.clear()
    }

    @Test
    fun theAbsoluteGenerationPathInADatabasesSubdirectoryOpensAndAnswersAQuery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databasesDirectory = context.getDatabasePath("unused-placeholder").parentFile
        val generationDirectory = File(File(databasesDirectory, "p0fix-generations"), "gen-1")
        createdGenerationDirectories += File(databasesDirectory, "p0fix-generations")
        generationDirectory.mkdirs()
        val absoluteMainFile = File(generationDirectory, "ledger.db")

        assertTrue(absoluteMainFile.isAbsolute)
        // The absolute branch of Context.getDatabasePath resolves and creates the parent itself;
        // the file must not exist yet so the create-on-open path is exercised (the fresh-install
        // shape App.kt reaches after the sequence creates the generation directory).
        assertTrue(!absoluteMainFile.exists())

        createAndroidLedgerDatabase(context, absoluteMainFile.absolutePath).use { handle ->
            assertNull(
                handle
                    .database
                    .ledgerQueries
                    .selectCommittedRequest(
                        ledger_id = "ledger-p0fix-a",
                        request_id = "request-p0fix-a",
                    ).executeAsOneOrNull(),
            )
        }

        assertTrue("the database must be created at the absolute generation path", absoluteMainFile.exists())
        assertTrue(absoluteMainFile.length() > 0)
        assertEquals(
            absoluteMainFile.absolutePath,
            context.getDatabasePath(absoluteMainFile.absolutePath).absolutePath,
        )
    }
}
