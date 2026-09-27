package com.unifiedledger.android

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unifiedledger.data.AndroidLedgerDatabaseHandle
import com.unifiedledger.data.currentSupportedSchemaVersion
import com.unifiedledger.ui.ImportFilePickResultChannel
import com.unifiedledger.ui.LedgerStorageFailure
import com.unifiedledger.ui.LedgerStorageRejectedException
import com.unifiedledger.ui.PointerMissingRecoveryProbe
import com.unifiedledger.ui.PointerMissingRecoveryUseCase
import com.unifiedledger.ui.PointerRecoveryAdoptionResult
import com.unifiedledger.ui.PointerRecoveryCandidateVerdict
import com.unifiedledger.ui.PointerRecoveryDiscardResult
import com.unifiedledger.ui.ledgerStorageLayout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * P7-06 06.D (D-182; spec sections 5.3, 4.1/4.3 and 7 — the device-instrumented row): on-device
 * verification for the 06.D recovery surfaces, driven through the REAL production startup
 * (`openAndroidStableStorageLedger`), the REAL `AndroidLedgerFileSystem`, the REAL
 * `AndroidRestoreIsolatedDatabasePort` (a real framework SQLite open) and the real
 * `PointerMissingRecoveryUseCase`. The JVM suites pin the shared logic with fakes; what only the
 * device can prove is that these primitives compose over a real Android filesystem and a real
 * SQLite file.
 *
 * Face 1 (spec section 5.3, D-178 residual (a), the "copy completed" sub-case): a generation
 * directory with a valid, current, integral main file and NO pointer. The production startup must
 * still fail closed with `POINTER_MISSING` (the D-176 silent-empty-database prohibition is NOT
 * weakened) and mutate nothing; the user-confirmed ADOPTION must then publish the pointer through
 * the frozen primitive, and the very next production startup must open that generation. With no
 * legacy original on disk the discard-and-re-upgrade branch must be typed-unavailable — guard 1 of
 * section 5.3, the reason desktop has no such branch.
 *
 * Face 2 (spec section 4.1 table rows 4-5, the restart half of the frozen ROLLBACK rule): a
 * `switched` journal with the pointer already naming the new generation. Startup must republish the
 * recorded OLD pointer, remove the journal, discard the staged new-generation directory, and open
 * the old generation — never the half-switched new one.
 *
 * Face 3 (spec section 5.3, the D-178 defect 2 "mid-copy" sub-case and the 06.D-new isolated-port
 * probes): an unverifiable candidate plus a still-present legacy original, where the user-confirmed
 * DISCARD-AND-RE-UPGRADE must delete the candidates and let the normal startup re-stage the user's
 * real legacy database; and the two 06.D-new adapter probes (`integrityCheckOk`, `readOwnerCounts`)
 * over a real framework SQLite file.
 *
 * MUST FIX A (data safety): the test must never touch the production ledger. The production open
 * resolves every path from `context.getDatabasePath(name)` alone, so the target context is wrapped
 * in a [ContextWrapper] whose `getDatabasePath` returns a file under a fresh per-test directory in
 * the target's cache dir; the guard inside [redirectedContext] refuses to run unless the
 * redirection is actually in effect. The recording `openDriver` also replaces the production
 * driver-open, so no production SQLite connection is made; the only real SQLite files opened are the
 * test's own seeded candidates under that temp directory.
 */
@RunWith(AndroidJUnit4::class)
class AndroidRestoreRecoveryInstrumentedTest {
    private val createdDirectories = mutableListOf<File>()

    @After
    fun cleanUp() {
        for (directory in createdDirectories) {
            directory.deleteRecursively()
        }
        createdDirectories.clear()
    }

    // ------------------------------------------------------------------ face 1: POINTER_MISSING (spec section 5.3)

    @Test
    fun aPointerlessHostFailsClosedAndTheConfirmedAdoptionOpensTheGeneration() {
        val context = redirectedContext()
        val (fileSystem, layout) = storageOver(context)
        seedCurrentGeneration(fileSystem, layout, generation = 1)

        // Startup 1: the pointerless shape. The production sequence must fail closed with the
        // typed POINTER_MISSING failure and leave the disk exactly as found (no pointer, the
        // candidate directory untouched) — never a fresh install (D-176).
        val rejected = assertRejectedPointerMissing(context)
        assertEquals(LedgerStorageFailure.POINTER_MISSING, rejected.failure)
        assertFalse("the fail-closed startup must not publish a pointer", fileSystem.exists(layout.activePointerFile))
        assertTrue("the fail-closed startup must not touch the candidate", fileSystem.exists(layout.mainFile(layout.generationDirectory(1))))

        // The recovery face: the probe reports the adoptable candidate and the absence of a
        // legacy original (Android always CONFIGURES the legacy path; here no file exists at it,
        // so the discard branch guard 1 is unsatisfied).
        val recovery = recoveryUseCase(fileSystem, layout, legacyMainFile = absentLegacyPath(context))
        val probe = recovery.probe()
        assertTrue("the pointerless shape must surface as recoverable: $probe", probe is PointerMissingRecoveryProbe.Recoverable)
        val state = (probe as PointerMissingRecoveryProbe.Recoverable).state
        assertEquals(1, state.adoptableGeneration)
        assertFalse("with no legacy original on disk the discard branch must be unavailable", state.legacyUpgradeAvailable)
        assertEquals(
            "an ADOPTABLE candidate must never be discarded (the discard branch is defined only for the no-verification shape)",
            PointerRecoveryDiscardResult.AdoptableCandidatePresent,
            recovery.discardUnvalidatableAndReUpgrade(),
        )

        // The user CONFIRMS the adoption: the frozen primitive publishes the pointer.
        val adopted = recovery.adopt()
        assertEquals(PointerRecoveryAdoptionResult.Adopted(1), adopted)
        assertEquals("gen-1", fileSystem.readBytes(layout.activePointerFile).decodeToString())

        // Startup 2: the next production start opens the adopted generation through the pointer,
        // WITHOUT create-on-open (a non-fresh target must never be silently rebuilt).
        val recorded = mutableListOf<String>()
        driveProductionStartupRecordingDriver(context, recorded)
        assertEquals("exactly one open must run after the adoption", 1, recorded.size)
        assertTrue("the adopted driver name must be absolute: ${recorded.single()}", File(recorded.single()).isAbsolute)
        assertTrue("the adopted generation must be opened: ${recorded.single()}", recorded.single().contains("gen-1"))
    }

    @Test
    fun aCandidateAtAnOlderSchemaVersionIsNotAdopted() {
        val context = redirectedContext()
        val (fileSystem, layout) = storageOver(context)
        // A real SQLite file stamped at v1: complete-and-OLD. The recovery set is pinned to
        // {currentSchemaVersion()} (spec section 5.3), so this candidate must NOT be adopted.
        val candidate = seedRealSqliteDatabase(fileSystem, layout, generation = 1, userVersion = 1L)

        val recovery = recoveryUseCase(fileSystem, layout, legacyMainFile = absentLegacyPath(context))
        val probe = recovery.probe()
        assertTrue("the pointerless shape must surface as recoverable: $probe", probe is PointerMissingRecoveryProbe.Recoverable)
        assertEquals(
            "a v1 candidate must not be adoptable",
            null,
            (probe as PointerMissingRecoveryProbe.Recoverable).state.adoptableGeneration,
        )
        assertEquals(
            PointerRecoveryAdoptionResult.NoAdoptableCandidate,
            recovery.adopt(),
        )
        assertFalse("no partial adoption may publish a pointer", fileSystem.exists(layout.activePointerFile))
        assertTrue("the rejected candidate must be left in place", File(candidate).exists())
        assertEquals(
            "with no legacy original the discard-and-re-upgrade branch must be typed-unavailable (guard 1, section 5.3)",
            PointerRecoveryDiscardResult.LegacyOriginalMissing,
            recovery.discardUnvalidatableAndReUpgrade(),
        )
        assertTrue("the typed-unavailable branch must delete nothing", File(candidate).exists())
    }

    // ------------------------------------------------------------------ face 2: the switched-window restart rollback (spec section 4.1)

    @Test
    fun aSwitchedJournalWithTheNewPointerRollsBackToTheOldGenerationAtStartup() {
        val context = redirectedContext()
        val (fileSystem, layout) = storageOver(context)
        val oldMain = seedCurrentGeneration(fileSystem, layout, generation = 1)
        // The staged new generation and the ALREADY-MOVED pointer: the crash window between the
        // pointer publish and the journal removal (spec section 4.1, rows 4-5).
        val newMain = seedCurrentGeneration(fileSystem, layout, generation = 2)
        val newSidecar = layout.sidecarFile(layout.generationDirectory(2), "-wal")
        fileSystem.writeAtomic(newSidecar, ByteArray(8) { 9 })
        fileSystem.writeAtomic(layout.activePointerFile, "gen-2".encodeToByteArray())
        // The frozen journal encoding (LedgerStableStorage.kt `ledgerSwitchJournalBytes`); written
        // literally because the encoder is app-ui-internal and this suite must observe the on-disk
        // contract, not the helper.
        fileSystem.writeAtomic(
            layout.switchJournalFile,
            "unified-ledger switch journal v1\nstage=switched\nold=gen-1\nnew=gen-2".encodeToByteArray(),
        )

        val recorded = mutableListOf<String>()
        driveProductionStartupRecordingDriver(context, recorded)

        // The frozen ROLLBACK rule, restart half: the OLD pointer is republished, the journal is
        // removed, the staged new generation (including its sidecar) is discarded, and the old
        // generation opens.
        assertEquals("gen-1", fileSystem.readBytes(layout.activePointerFile).decodeToString())
        assertFalse("the journal must be consumed", fileSystem.exists(layout.switchJournalFile))
        assertFalse("the staged new generation must be discarded", fileSystem.exists(newMain))
        assertFalse("the staged sidecar must be discarded with it", fileSystem.exists(newSidecar))
        assertTrue("the old generation is the rollback anchor and must survive", File(oldMain).exists())
        assertEquals(1, recorded.size)
        assertTrue("the rolled-back start must open the OLD generation: ${recorded.single()}", recorded.single().contains("gen-1"))
        assertFalse("the half-switched generation must never be opened: ${recorded.single()}", recorded.single().contains("gen-2"))
    }

    @Test
    fun aMidCopyBrickedCandidateIsDiscardedAndTheLegacyOriginalIsReStaged() {
        val context = redirectedContext()
        val (fileSystem, layout) = storageOver(context)
        // The mid-copy bricking shape (D-178 defect 2): the generation directory holds an
        // incomplete/unverifiable copy of the user's OLD database, and the legacy original is
        // still on disk (removeLegacyFiles only runs after a successful pointer publish, so a
        // mid-copy kill necessarily leaves it).
        val candidate = seedRealSqliteDatabase(fileSystem, layout, generation = 1, userVersion = 1L)
        val legacyPath = context.getDatabasePath(LEGACY_DATABASE_NAME).absolutePath
        File(legacyPath).parentFile!!.mkdirs()
        val legacyData = "legacy-marker-row-0123456789"
        SQLiteDatabase.openOrCreateDatabase(File(legacyPath), null).use { database ->
            database.execSQL("CREATE TABLE legacy_marker (payload TEXT)")
            database.execSQL("INSERT INTO legacy_marker VALUES ('$legacyData')")
            database.version = currentSupportedSchemaVersion().toInt()
        }
        assertIsolatedFromProduction(File(legacyPath))

        // The user CONFIRMS the discard-and-re-upgrade: the unverifiable candidate is deleted
        // (candidate directories AND the emptied generations directory), the legacy original is
        // never touched.
        val recovery = recoveryUseCase(fileSystem, layout, legacyMainFile = legacyPath)
        val probe = recovery.probe()
        assertTrue("the bricked shape must surface as recoverable: $probe", probe is PointerMissingRecoveryProbe.Recoverable)
        assertTrue("the legacy original makes the discard branch available", (probe as PointerMissingRecoveryProbe.Recoverable).state.legacyUpgradeAvailable)
        assertEquals(PointerRecoveryDiscardResult.DiscardedAwaitingUpgrade, recovery.discardUnvalidatableAndReUpgrade())
        assertFalse("the bricked candidate must be discarded", fileSystem.exists(candidate))
        assertFalse("the emptied generations directory must be gone so the re-upgrade can resolve", fileSystem.exists(layout.generationsDirectory))
        assertTrue("the legacy original must never be touched by the discard", File(legacyPath).exists())
        assertEquals(legacyData, readLegacyMarker(legacyPath))
        // Captured BEFORE the re-upgrade: the frozen UpgradeLegacy sequence removes the legacy set
        // only AFTER it publishes the pointer (rule 1 step (e)), so the bytes must be read now.
        val legacyBytesBeforeUpgrade = File(legacyPath).readBytes()

        // The caller re-runs the normal startup: the frozen UpgradeLegacy sequence re-stages the
        // user's REAL legacy database into gen-1 — the old data returns to a generation directory,
        // not a fresh install.
        val recorded = mutableListOf<String>()
        driveProductionStartupRecordingDriver(context, recorded)
        val reStagedMain = layout.mainFile(layout.generationDirectory(1))
        assertTrue("the re-upgrade must re-stage gen-1", File(reStagedMain).exists())
        assertArrayEquals(
            "the re-staged generation must be a byte copy of the user's legacy original",
            legacyBytesBeforeUpgrade,
            File(reStagedMain).readBytes(),
        )
        // The re-staged data carries the user's actual row, not an empty schema.
        assertEquals(legacyData, readLegacyMarker(reStagedMain))
        assertEquals(1, recorded.size)
        assertTrue("the re-upgrade must open the re-staged generation: ${recorded.single()}", recorded.single().contains("gen-1"))
        // The recording openDriver throws INSIDE the frozen UpgradeLegacy sequence, i.e. before its
        // step (d) pointer publish and step (e) legacy removal, so the legacy set must still be
        // intact — the non-destructive ordering the sequence guarantees.
        assertTrue("the legacy set is removed only after a successful publish (rule 1 step (e))", File(legacyPath).exists())
    }

    // ------------------------------------------------------------------ face 3: the 06.D-new isolated-port probes on a real framework DB

    @Test
    fun theOwnerCountsAndIntegrityProbesRunOnARealFrameworkDatabase() {
        val context = redirectedContext()
        val (fileSystem, layout) = storageOver(context)
        val directory = layout.generationDirectory(1)
        fileSystem.createDirectories(directory)
        val mainFile = layout.mainFile(directory)
        SQLiteDatabase.openOrCreateDatabase(File(mainFile), null).use { database ->
            // Minimal count-bearing shapes for the three owner surfaces `readOwnerCounts` selects
            // from. The port issues plain `SELECT count(*)`, so the fixture needs only tables of
            // those names, not the full schema; the real adapter and the real SQLite engine are
            // what this test exercises.
            database.execSQL("CREATE TABLE catalog_account (account_id TEXT)")
            database.execSQL("CREATE TABLE catalog_category (category_id TEXT)")
            database.execSQL("CREATE TABLE ledger_transaction (transaction_id TEXT)")
            database.execSQL("INSERT INTO catalog_account VALUES ('a-1'), ('a-2'), ('a-3')")
            database.execSQL("INSERT INTO catalog_category VALUES ('c-1'), ('c-2')")
            database.execSQL("INSERT INTO ledger_transaction VALUES ('t-1'), ('t-2'), ('t-3'), ('t-4'), ('t-5')")
            database.version = currentSupportedSchemaVersion().toInt()
        }
        assertIsolatedFromProduction(File(mainFile))
        val port = AndroidRestoreIsolatedDatabasePort()

        // 06.D spec section 5.5 field 6: the user-checkable owner counts, read end to end through
        // the real framework adapter.
        val counts = port.readOwnerCounts(mainFile)
        assertEquals(3L, counts.accountsCount)
        assertEquals(2L, counts.categoriesCount)
        assertEquals(5L, counts.transactionsCount)
        // 06.D spec section 5.3: the recovery candidate gate — integrity_check ALONE, which is
        // exactly what the adoption verdict runs.
        assertTrue("a healthy framework database must pass the recovery integrity gate", port.integrityCheckOk(mainFile))
    }

    @Test
    fun aStructurallyCorruptCandidateIsNeverAdopted() {
        val context = redirectedContext()
        val (fileSystem, layout) = storageOver(context)
        val directory = layout.generationDirectory(1)
        fileSystem.createDirectories(directory)
        val mainFile = layout.mainFile(directory)
        // A valid SQLite magic prefix followed by garbage: `isUsableSqliteMainFile` passes (it
        // reads only the 16-byte header) but the candidate cannot verify as complete-and-current,
        // so it must never be adopted.
        fileSystem.writeAtomic(mainFile, "SQLite format 3\u0000".encodeToByteArray() + ByteArray(64) { 0x7F })
        assertIsolatedFromProduction(File(mainFile))

        val recovery = recoveryUseCase(fileSystem, layout, legacyMainFile = absentLegacyPath(context))
        val probe = recovery.probe()
        val state = (probe as PointerMissingRecoveryProbe.Recoverable).state
        // The corrupt candidate is still ENUMERATED (the directory name is a candidate name), but
        // its verdict must not be Adoptable. The exact non-adoptable verdict is deliberately not
        // pinned: whether the platform SQLite rejects the image (Unusable) or accepts a
        // header-only image whose user_version is 0 (WrongVersion) is an engine detail this suite
        // does not fake — the load-bearing claim is "never adopted".
        assertEquals("the corrupt candidate must still be enumerated", listOf(1), state.candidates.map { it.generation })
        assertFalse(
            "a corrupt candidate must never verify Adoptable: ${state.candidates.single().verdict}",
            state.candidates.single().verdict == PointerRecoveryCandidateVerdict.Adoptable,
        )
        assertEquals("a corrupt candidate must not be adoptable", null, state.adoptableGeneration)
        assertEquals(PointerRecoveryAdoptionResult.NoAdoptableCandidate, recovery.adopt())
        assertFalse("no partial adoption may publish a pointer", fileSystem.exists(layout.activePointerFile))
        assertTrue("the non-adoptable candidate must be left in place", File(mainFile).exists())
        assertEquals(
            "with no legacy original the discard branch stays typed-unavailable",
            PointerRecoveryDiscardResult.LegacyOriginalMissing,
            recovery.discardUnvalidatableAndReUpgrade(),
        )
    }

    // ------------------------------------------------------------------ helpers

    /** The production file-system adapter and layout over the redirected host directory. */
    private fun storageOver(context: Context): Pair<AndroidLedgerFileSystem, com.unifiedledger.ui.LedgerStorageLayout> {
        val databasePath = context.getDatabasePath(LEGACY_DATABASE_NAME)
        val (hostDirectory, _) = androidStableStoragePaths(databasePath)
        val fileSystem = AndroidLedgerFileSystem()
        return fileSystem to ledgerStorageLayout(fileSystem, hostDirectory)
    }

    /**
     * Seeds a generation directory whose main file is a REAL framework SQLite database stamped at
     * [userVersion] (the current schema for the adoption cases), and returns its absolute path.
     */
    private fun seedCurrentGeneration(
        fileSystem: AndroidLedgerFileSystem,
        layout: com.unifiedledger.ui.LedgerStorageLayout,
        generation: Int,
    ): String = seedRealSqliteDatabase(fileSystem, layout, generation, currentSupportedSchemaVersion())

    private fun seedRealSqliteDatabase(
        fileSystem: AndroidLedgerFileSystem,
        layout: com.unifiedledger.ui.LedgerStorageLayout,
        generation: Int,
        userVersion: Long,
    ): String {
        val directory = layout.generationDirectory(generation)
        fileSystem.createDirectories(directory)
        val mainFile = layout.mainFile(directory)
        SQLiteDatabase.openOrCreateDatabase(File(mainFile), null).use { database ->
            database.version = userVersion.toInt()
        }
        assertIsolatedFromProduction(File(mainFile))
        return mainFile
    }

    private fun recoveryUseCase(
        fileSystem: AndroidLedgerFileSystem,
        layout: com.unifiedledger.ui.LedgerStorageLayout,
        legacyMainFile: String?,
    ): PointerMissingRecoveryUseCase =
        PointerMissingRecoveryUseCase(
            fileSystem = fileSystem,
            layout = layout,
            // The REAL Android isolated-database port: the candidate verdicts come from a real
            // framework SQLite open, integrity_check and user_version read.
            isolatedDatabase = AndroidRestoreIsolatedDatabasePort(),
            currentSchemaVersion = currentSupportedSchemaVersion(),
            legacyMainFile = legacyMainFile,
        )

    /**
     * Drives the REAL production startup with a recording `openDriver` (the absolute-path suite's
     * pattern) and returns nothing; the sentinel stops the sequence after the driver name is
     * recorded, so no graph is built and no production connection is made.
     */
    private fun driveProductionStartupRecordingDriver(
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
            throw RestoreRecoveryRecordingOpenSentinel()
        }
        try {
            openAndroidStableStorageLedger(
                context = context,
                importFilePickPort = port,
                importPickChannel = ImportFilePickResultChannel(),
                openDriver = openDriver,
            )
        } catch (expected: RestoreRecoveryRecordingOpenSentinel) {
            // Expected: the recording openDriver throws after recording the name.
        }
    }

    /** Runs the production startup and returns the typed fail-closed failure it must throw. */
    private fun assertRejectedPointerMissing(context: Context): LedgerStorageRejectedException {
        val port =
            AndroidImportFilePickPort<Uri>(
                launchOpenDocument = {},
                resolveMetadata = { PickedSafFileMetadata(displayName = "", sizeBytes = null) },
                openInputStream = { null },
                onResult = {},
            )
        return try {
            openAndroidStableStorageLedger(
                context = context,
                importFilePickPort = port,
                importPickChannel = ImportFilePickResultChannel(),
                openDriver = { throw AssertionError("the pointerless shape must be rejected before any driver open") },
            )
            throw AssertionError("the pointerless shape must fail closed, not open a graph")
        } catch (rejected: LedgerStorageRejectedException) {
            rejected
        }
    }

    /**
     * The Android legacy database path the production root would configure, when NO legacy file
     * exists at it (the post-migration shape): the discard branch's guard 1 is unsatisfied by the
     * absent file, not by a null configuration.
     */
    private fun absentLegacyPath(context: Context): String = context.getDatabasePath(LEGACY_DATABASE_NAME).absolutePath

    /** Reads the marker row back out of a real framework database, proving the data survived. */
    private fun readLegacyMarker(path: String): String =
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("SELECT payload FROM legacy_marker", null).use { cursor ->
                check(cursor.moveToFirst()) { "the legacy marker row must survive" }
                cursor.getString(0)
            }
        }

    /**
     * Builds a [ContextWrapper] whose `getDatabasePath` resolves under a fresh per-test temp
     * directory, and asserts the redirection is actually in effect (fails loudly otherwise, so the
     * test can never run against the production ledger).
     */
    private fun redirectedContext(): Context {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(target.cacheDir, "p706d-recovery-" + System.nanoTime()).apply { mkdirs() }
        createdDirectories += root
        val wrapper =
            object : ContextWrapper(target) {
                override fun getDatabasePath(name: String): File = File(root, name)
            }
        val effective = wrapper.getDatabasePath(LEGACY_DATABASE_NAME).absolutePath
        val production = target.getDatabasePath(LEGACY_DATABASE_NAME).absolutePath
        assertNotEquals("the test must not resolve to the production database path", production, effective)
        assertTrue("the effective database path must be inside the test temp directory: $effective", effective.startsWith(root.absolutePath))
        return wrapper
    }

    /**
     * Hard guard (the 06.C driver suite's pattern): a test-seeded file must live under the
     * app-private cache dir and must NOT resolve into the production `databases/` tree.
     */
    private fun assertIsolatedFromProduction(file: File) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val productionDatabases = target.getDatabasePath(LEGACY_DATABASE_NAME).parentFile?.absolutePath
        assertTrue(
            "the test database must be under the cache dir: ${file.absolutePath}",
            file.absolutePath.startsWith(target.cacheDir.absolutePath),
        )
        assertTrue(
            "the test database must not resolve into the production databases directory: ${file.absolutePath}",
            productionDatabases == null || !file.absolutePath.startsWith(productionDatabases),
        )
    }

    private companion object {
        const val LEGACY_DATABASE_NAME = "ledger.db"
    }
}

/** The sentinel the recording `openDriver` throws after recording the driver name. */
private class RestoreRecoveryRecordingOpenSentinel : RuntimeException("recording openDriver sentinel")
