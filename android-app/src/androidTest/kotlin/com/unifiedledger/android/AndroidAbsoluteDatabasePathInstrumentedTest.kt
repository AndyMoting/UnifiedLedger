package com.unifiedledger.android

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unifiedledger.data.AndroidLedgerDatabaseHandle
import com.unifiedledger.ui.ImportFilePickResultChannel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * P0 hotfix (defect 1): on-device regression guard for the generation driver path. This drives the
 * REAL production function [openAndroidStableStorageLedger] with a recording `openDriver`, and
 * asserts that the name the production sequence actually hands to the driver is ABSOLUTE for both
 * the fresh-install and the legacy-upgrade plans.
 *
 * MUST FIX A (data safety): the test must never touch the production ledger. The production
 * function resolves every path from `context.getDatabasePath(name)` alone (read:
 * `openAndroidStableStorageLedgerLocked` derives the host directory, legacy path, generations
 * directory and pointer from that one call), so this test wraps the target context in a
 * [ContextWrapper] whose `getDatabasePath` returns a file under a JUnit-temporary directory. All
 * reads and writes therefore land in the temp directory, and the real `databases/ledger.db` and
 * `databases/ledger-generations` are never opened, copied, written or deleted. The recording
 * `openDriver` also replaces the default driver-open, so no production SQLite connection is made.
 * [assertRedirectedAwayFromProduction] additionally refuses to run if the redirection is not in
 * effect, so a future regression cannot silently point the test at real data.
 *
 * Why this is the guard and not merely a positive control: the pre-fix code converted the absolute
 * generation main file into the RELATIVE name `ledger-generations/gen-1/ledger.db` before calling
 * the driver, and that relative name is rejected by the framework — androidx
 * FrameworkSQLiteOpenHelper passes the raw name to `Context.getDatabasePath`, whose
 * non-separator-prefixed branch calls `ContextImpl.makeFilename`, which throws
 * `IllegalArgumentException("File " + name + " contains a path separator")`. Recording the name at
 * the production seam makes that regression go red here.
 */
@RunWith(AndroidJUnit4::class)
class AndroidAbsoluteDatabasePathInstrumentedTest {
    private val createdDirectories = mutableListOf<File>()

    @After
    fun cleanUp() {
        // Only the test's own temp directories are removed.
        for (directory in createdDirectories) {
            directory.deleteRecursively()
        }
        createdDirectories.clear()
    }

    @Test
    fun theFreshInstallPlanHandsAnAbsoluteNameToTheDriver() {
        val context = redirectedContext()
        val generationsDirectory = File(hostDirectory(context), "ledger-generations")
        generationsDirectory.deleteRecursively()

        val recorded = mutableListOf<String>()
        openRecordingProductionOpen(context, recorded)

        assertEquals(1, recorded.size)
        assertTrue("the fresh-install driver name must be absolute: ${recorded.single()}", File(recorded.single()).isAbsolute)
        assertTrue(recorded.single().endsWith("ledger.db"))
    }

    @Test
    fun theLegacyUpgradePlanHandsAnAbsoluteNameToTheDriver() {
        val context = redirectedContext()
        // A legacy database in the REDIRECTED host directory selects the upgrade plan.
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
     * MUST FIX B (rotate-during-open): two concurrent calls to the REAL production
     * [openAndroidStableStorageLedger] must be serialized by the process-wide open lock, so only
     * one open of the same generation can run at a time. The redirected host is PRE-SEEDED with a
     * valid generation (a `ledger-generations/gen-1/ledger.db` carrying a real SQLite header, plus
     * an `active-generation` pointer naming `gen-1`), so BOTH opens resolve the `OpenGeneration`
     * plan and BOTH reach the injected `openDriver`. The first blocks inside `openDriver` (the
     * stand-in for the multi-second open) while the second is launched; WITHOUT the lock the second
     * enters concurrently and `maxObservedConcurrency` reaches 2, so this test goes red if the lock
     * is removed.
     *
     * Why the pre-seed is required for non-vacuity: on the FreshInstall/UpgradeLegacy paths the
     * sequence creates the generations directory before `openDriver`, so a second concurrent open
     * would resolve a pointerless generations dir and fail closed with `POINTER_MISSING` before it
     * ever reached `openDriver` — the lock's absence would then be invisible. Seeding a valid
     * active generation removes that shortcut.
     *
     * Device-only evidence: the JVM suite cannot construct an Android Context, but
     * AndroidStableStorageOpenLockTest pins the lock mechanism itself.
     */
    @Test
    fun concurrentProductionOpensAreSerializedByTheProcessWideLock() {
        val context = redirectedContext()
        seedValidActiveGeneration(context)
        val insideOpen = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val active = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)
        val completed = CountDownLatch(2)

        fun openOnce() {
            try {
                openAndroidStableStorageLedger(
                    context = context,
                    importFilePickPort =
                        AndroidImportFilePickPort<Uri>(
                            launchOpenDocument = {},
                            resolveMetadata = { PickedSafFileMetadata(displayName = "", sizeBytes = null) },
                            openInputStream = { null },
                            onResult = {},
                        ),
                    importPickChannel = ImportFilePickResultChannel(),
                    openDriver = {
                        val now = active.incrementAndGet()
                        maxObservedConcurrency.updateAndGet { current -> maxOf(current, now) }
                        insideOpen.countDown()
                        releaseOpen.await(30, TimeUnit.SECONDS)
                        active.decrementAndGet()
                        throw RecordingOpenSentinel()
                    },
                )
            } catch (expected: RecordingOpenSentinel) {
                // Expected sentinel.
            } finally {
                completed.countDown()
            }
        }

        val first = Thread(::openOnce, "p0fix-open-1").apply { isDaemon = true }
        val second = Thread(::openOnce, "p0fix-open-2").apply { isDaemon = true }
        first.start()
        assertTrue("the first open did not enter the driver", insideOpen.await(30, TimeUnit.SECONDS))
        second.start()
        // Bounded settle: give the second open time to enter if the lock were absent.
        Thread.sleep(500)
        assertEquals("the production open must serialize concurrent opens", 1, maxObservedConcurrency.get())

        releaseOpen.countDown()
        assertTrue("both opens did not complete", completed.await(30, TimeUnit.SECONDS))
        assertEquals("concurrency must never exceed one", 1, maxObservedConcurrency.get())
    }

    /**
     * Pre-seeds the redirected host so [openAndroidStableStorageLedger] resolves the
     * `OpenGeneration` plan (generations dir + pointer + usable gen-1 main file), making both
     * concurrent opens reach the driver.
     */
    private fun seedValidActiveGeneration(context: Context) {
        val host = hostDirectory(context)
        val generationDirectory = File(File(host, "ledger-generations"), "gen-1")
        generationDirectory.mkdirs()
        File(generationDirectory, "ledger.db").writeBytes(SQLITE_HEADER + ByteArray(1024))
        File(host, "active-generation").writeText("gen-1")
    }

    /**
     * Drives the real [openAndroidStableStorageLedger] with a recording `openDriver`. The sentinel
     * throw stops the sequence before it builds the graph; only the recorded name is asserted.
     */
    private fun openRecordingProductionOpen(
        context: Context,
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

    /**
     * Builds a [ContextWrapper] whose `getDatabasePath` resolves under a fresh test-scoped temp
     * directory, and asserts the redirection is actually in effect (fails loudly otherwise, so the
     * test can never run against the production ledger).
     */
    private fun redirectedContext(): Context {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(target.cacheDir, "p0fix-db-" + System.nanoTime()).apply { mkdirs() }
        createdDirectories += root
        val wrapper =
            object : ContextWrapper(target) {
                override fun getDatabasePath(name: String): File = File(root, name)
            }
        assertRedirectedAwayFromProduction(target, wrapper, root)
        return wrapper
    }

    /**
     * MUST FIX A hard guard: the effective database path must be under the test temp directory and
     * must NOT be the real production path. If the redirection is broken this fails loudly BEFORE
     * any production file can be touched.
     */
    private fun assertRedirectedAwayFromProduction(
        target: Context,
        wrapper: Context,
        root: File,
    ) {
        val effective = wrapper.getDatabasePath(LEGACY_DATABASE_NAME).absolutePath
        val production = target.getDatabasePath(LEGACY_DATABASE_NAME).absolutePath
        assertNotEquals("the test must not resolve to the production database path", production, effective)
        assertTrue("the effective database path must be inside the test temp directory: $effective", File(effective).absolutePath.startsWith(root.absolutePath))
        assertFalse(
            "the test must never resolve into the production databases directory",
            File(effective).parentFile?.absolutePath == File(production).parentFile?.absolutePath,
        )
    }

    private fun hostDirectory(context: Context): File = context.getDatabasePath(LEGACY_DATABASE_NAME).parentFile!!

    private companion object {
        const val LEGACY_DATABASE_NAME = "ledger.db"
        val SQLITE_HEADER: ByteArray = "SQLite format 3\u0000".encodeToByteArray()
    }
}

/** The sentinel the recording `openDriver` throws after recording the driver name. */
private class RecordingOpenSentinel : RuntimeException("recording openDriver sentinel")
