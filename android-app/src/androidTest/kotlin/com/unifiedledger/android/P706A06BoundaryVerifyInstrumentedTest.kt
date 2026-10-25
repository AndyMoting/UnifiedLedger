package com.unifiedledger.android

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unifiedledger.data.currentSupportedSchemaVersion
import com.unifiedledger.ui.ImportFilePickResultChannel
import com.unifiedledger.ui.LedgerStorageFailure
import com.unifiedledger.ui.LedgerStorageLayout
import com.unifiedledger.ui.LedgerStorageRejectedException
import com.unifiedledger.ui.PointerMissingRecoveryProbe
import com.unifiedledger.ui.PointerMissingRecoveryUseCase
import com.unifiedledger.ui.PointerRecoveryAdoptionResult
import com.unifiedledger.ui.PointerRecoveryCandidateVerdict
import com.unifiedledger.ui.PointerRecoveryDiscardResult
import com.unifiedledger.ui.ledgerStorageLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * P706-A06 (D-246; spec `docs/specs/2026-10-24-p706-a02-a06-acceptance-design.md` sections
 * 3.3-3.5): run B of the two-phase process-kill protocol - the VERIFY leg. Each method pairs
 * with the same-numbered kill method of `P706A06BoundaryKillInstrumentedTest`: it reads the
 * marker the killed process left in the stable redirected root (a missing, corrupt or FOREIGN
 * marker fails this leg explicitly), then drives the REAL production startup and asserts the
 * spec section 3.5 face - a complete old or new generation is usable, no half library, no silent
 * empty database, at most one effective graph - plus the authoritative read-back (the ledger
 * facts, the pointer target, the journal consumption state) in the per-row form of the section
 * 3.4 matrix's "new device leg" column.
 *
 * STARTUP ENTRY CHOICE: the production entry [openAndroidStableStorageLedger] (`App.kt`, the
 * app's own startup) is used rather than the public `openStableStorageLedger`
 * (`LedgerRuntimeOwner.kt:1003`), because it is the exact startup the app runs - the full
 * `resolveLedgerStorage` including the startup switch-journal gate, the staging sweep, the REAL
 * platform driver and the REAL graph build - which is the strongest form of "the next real start
 * converges" this leg can drive; the redirected context keeps it off the production ledger. The
 * kill leg necessarily drives the public `openStableStorageLedger` instead, because only the
 * decorated file system (which the production entry does not accept) can park the flow.
 *
 * Platform media honesty (spec section 3.1): the kills landed at frozen primitive boundaries, so
 * this leg asserts what a process kill on this platform leaves observable - the page cache
 * survives, AtomicFile replacements have either fully happened or not started, and the
 * best-effort no-op `fsyncDirectory` (`AndroidLedgerFileSystem.kt:93-98`) is never claimed as a
 * durability effect. This is the process-kill leg; real power loss is the separate registered
 * BLOCKED leg and is not claimed here.
 *
 * CLEANUP: run B is read-only over the killed process's disk until every assertion of its row
 * has passed; only then does it delete the stable root (a failed row leaves the root in place as
 * forensics, and the paired kill method cleans at its own start anyway).
 */
@RunWith(AndroidJUnit4::class)
class P706A06BoundaryVerifyInstrumentedTest {
    @Test
    fun v01PreStageDeleteMidKillConvergesToTheOldGeneration() = verifyBoundary(P706A06Boundary.B1)

    @Test
    fun v02MainFileCopyMidKillLeavesTheStagedGenerationInert() = verifyBoundary(P706A06Boundary.B2)

    @Test
    fun v03FsyncSequenceMidKillLeavesTheStagedGenerationInert() = verifyBoundary(P706A06Boundary.B3)

    @Test
    fun v04PreparedJournalRollsBackAtStartup() = verifyBoundary(P706A06Boundary.B4)

    @Test
    fun v05PublishedPointerPreparedJournalRollsBackAtStartup() = verifyBoundary(P706A06Boundary.B5)

    @Test
    fun v06SwitchedJournalRollsBackAtStartup() = verifyBoundary(P706A06Boundary.B6)

    @Test
    fun v07ReopenWindowSwitchedJournalRollsBackAtStartup() = verifyBoundary(P706A06Boundary.B7)

    @Test
    fun v08RemovedJournalCommitsTheSwitchOnRestart() = verifyBoundary(P706A06Boundary.B8)

    @Test
    fun v09KilledEscapeRepairConvergesByJournalAndPointerState() = verifyBoundary(P706A06Boundary.B9)

    @Test
    fun v10LegacyUpgradeStageKillConvergesThroughAdoption() = verifyBoundary(P706A06Boundary.B10)

    @Test
    fun v11LegacyUpgradePrePublishWindowKillConvergesThroughAdoption() = verifyBoundary(P706A06Boundary.B11)

    @Test
    fun v12UpgradePublishKilledAfterPublishSelectsTheNewGeneration() = verifyBoundary(P706A06Boundary.B12)

    @Test
    fun v13LegacyRemovalMidKillKeepsTheNewGenerationAuthoritative() = verifyBoundary(P706A06Boundary.B13)

    @Test
    fun v14FreshInstallPrePublishKillStaysFailClosedUntilAdoption() = verifyBoundary(P706A06Boundary.B14)

    @Test
    fun v15AdoptionPublishKilledAfterPublishOpensTheCandidate() = verifyBoundary(P706A06Boundary.B15)

    @Test
    fun v16DiscardMidKillStaysFailClosedUntilReUpgrade() = verifyBoundary(P706A06Boundary.B16)

    @Test
    fun v17RecoveryRepublishKilledMidRecoveryReRunsAtStartup() = verifyBoundary(P706A06Boundary.B17)

    @Test
    fun v18RecoveryJournalDeleteKilledKeepsTheRecoveredState() = verifyBoundary(P706A06Boundary.B18)

    @Test
    fun v19RecoveryDeletionKilledMidDeletionLeavesTheRemnantInert() = verifyBoundary(P706A06Boundary.B19)

    // ------------------------------------------------------------------ the two-phase protocol, step 2

    /** Spec section 3.3 step 2: read the marker, converge, assert this row's face, then clean. */
    private fun verifyBoundary(boundary: P706A06Boundary) {
        val root = p706A06StableRoot()
        val marker = P706A06BoundaryMarkerProtocol.readStrict(root, boundary)
        val context = p706A06RedirectedContext(root)
        val realFileSystem = AndroidLedgerFileSystem()
        val hostDirectory = androidStableStoragePaths(context.getDatabasePath(P706A06BoundaryFixturePayloads.LEGACY_DATABASE_NAME)).first
        val layout = ledgerStorageLayout(realFileSystem, hostDirectory)
        val fixtures = P706A06BoundaryFixtures(context, realFileSystem, layout)
        assertFileIsolatedFromProduction(root)
        when (boundary) {
            P706A06Boundary.B1,
            P706A06Boundary.B2,
            P706A06Boundary.B3,
            P706A06Boundary.B4,
            P706A06Boundary.B5,
            P706A06Boundary.B6,
            P706A06Boundary.B7,
            P706A06Boundary.B8,
            P706A06Boundary.B9,
            -> verifyConfirmRow(boundary, marker, context, fixtures, layout, realFileSystem)
            P706A06Boundary.B10,
            P706A06Boundary.B11,
            -> verifyLegacyPrePublishRow(marker, context, fixtures, layout, realFileSystem)
            P706A06Boundary.B12,
            P706A06Boundary.B13,
            -> verifyLegacyPostPublishRow(boundary, marker, context, fixtures, layout, realFileSystem)
            P706A06Boundary.B14 -> verifyFreshInstallRow(marker, context, fixtures, layout, realFileSystem)
            P706A06Boundary.B15 -> verifyAdoptionRow(marker, context, fixtures, layout, realFileSystem)
            P706A06Boundary.B16 -> verifyDiscardRow(marker, context, fixtures, layout, realFileSystem)
            P706A06Boundary.B17,
            P706A06Boundary.B18,
            P706A06Boundary.B19,
            -> verifyStartupRecoveryRow(boundary, marker, context, fixtures, layout, realFileSystem)
        }
        root.deleteRecursively()
    }

    // ------------------------------------------------------------------ the confirm chain (B1-B9)

    private fun verifyConfirmRow(
        boundary: P706A06Boundary,
        marker: P706A06BoundaryMarker,
        context: Context,
        fixtures: P706A06BoundaryFixtures,
        layout: LedgerStorageLayout,
        realFileSystem: AndroidLedgerFileSystem,
    ) {
        val graph = openProductionStartup(context)
        try {
            // The section 3.5 face, confirm-chain form: whatever the kill left behind, the next
            // real start opens exactly one usable generation through a single-valued pointer,
            // with no journal left unconsumed.
            assertPointerNames(realFileSystem, layout, if (boundary == P706A06Boundary.B8) 2 else 1)
            assertJournalAbsent(realFileSystem, layout)
        } finally {
            graph.close()
        }
        when (boundary) {
            P706A06Boundary.B1 -> {
                // The mid-deletion shape: the leftover main file is gone, its sidecar and
                // directory survive, and none of it was ever selected.
                assertFalse("the half-deleted leftover main file must be gone", realFileSystem.exists(fixtures.generationMainFile(2)))
                assertTrue("the half-deleted leftover directory must survive the kill", realFileSystem.exists(layout.generationDirectory(2)))
                assertTrue(
                    "the leftover deletable sidecar must survive the mid-deletion kill",
                    realFileSystem.exists(layout.sidecarFile(layout.generationDirectory(2), "-wal")),
                )
            }
            P706A06Boundary.B2,
            P706A06Boundary.B3,
            -> {
                // The staged-but-inert shape: a complete journal-less generation exists on disk
                // and the startup never selected it (the opened facts prove the identity).
                assertTrue("the staged journal-less generation must survive inert", realFileSystem.exists(fixtures.generationMainFile(2)))
            }
            P706A06Boundary.B4,
            P706A06Boundary.B5,
            P706A06Boundary.B6,
            P706A06Boundary.B7,
            -> {
                // The frozen ROLLBACK restart rule: the journal was consumed, the staged new
                // generation discarded by the gate, the old generation opened.
                assertFalse("the rolled-back generation must be discarded at startup", realFileSystem.exists(layout.generationDirectory(2)))
            }
            P706A06Boundary.B8 -> {
                // The committed shape: the journal removal survived the kill, the new generation
                // is authoritative and the old generation is retained as the rollback anchor.
                assertTrue("the old generation is the rollback anchor and must be retained", realFileSystem.exists(fixtures.generationMainFile(1)))
            }
            P706A06Boundary.B9 -> {
                // The repair's own disk trail: the old pointer restored, the journal removed and
                // the staged main file deleted before the process died - a shape only the
                // repair's ordering produces.
                assertFalse("the repair must have deleted the staged main file", realFileSystem.exists(fixtures.generationMainFile(2)))
            }
            else -> throw AssertionError("unreachable confirm row: $boundary")
        }
        assertAuthoritativeFacts(fixtures, File(fixtures.generationMainFile(if (boundary == P706A06Boundary.B8) 2 else 1)), marker)
    }

    // ------------------------------------------------------------------ the legacy upgrade (B10-B13)

    /** B10/B11: the "generation directory but no pointer" window - fail closed, then adopt. */
    private fun verifyLegacyPrePublishRow(
        marker: P706A06BoundaryMarker,
        context: Context,
        fixtures: P706A06BoundaryFixtures,
        layout: LedgerStorageLayout,
        realFileSystem: AndroidLedgerFileSystem,
    ) {
        assertStartupFailsClosedPointerMissing(context, fixtures, layout, realFileSystem, expectedGenerations = listOf("gen-1"))
        // Identity checkpoint: nothing has opened the staged generation yet, so its bytes must
        // still be the park-time fingerprint; the legacy original is equally untouched.
        assertShaMatches(fixtures, File(fixtures.generationMainFile(1)), marker.authoritativeSha256, "the staged generation")
        assertShaMatches(fixtures, File(fixtures.legacyMainFilePath()), requireNotNull(marker.legacySha256), "the legacy original")

        // The user-confirmed adoption converges (the row's disjunction picks adoption here: the
        // staged copy is complete-and-current, so it verifies Adoptable; the discard branch is
        // defined only for the no-verification shape).
        val recovery = pointerMissingRecoveryUseCase(fixtures, layout)
        assertEquals(PointerRecoveryAdoptionResult.Adopted(1), recovery.adopt())
        assertPointerNames(realFileSystem, layout, 1)

        // The legacy set survives the adoption untouched (the adopt branch only publishes).
        assertShaMatches(fixtures, File(fixtures.legacyMainFilePath()), requireNotNull(marker.legacySha256), "the legacy original after adoption")

        val graph = openProductionStartup(context)
        try {
            assertJournalAbsent(realFileSystem, layout)
        } finally {
            graph.close()
        }
        assertPointerNames(realFileSystem, layout, 1)
        assertAuthoritativeFacts(fixtures, File(fixtures.generationMainFile(1)), marker)
        assertShaMatches(fixtures, File(fixtures.legacyMainFilePath()), requireNotNull(marker.legacySha256), "the legacy original after the open")
    }

    /** B12/B13: the pointer is already published - the next start selects the new generation. */
    private fun verifyLegacyPostPublishRow(
        boundary: P706A06Boundary,
        marker: P706A06BoundaryMarker,
        context: Context,
        fixtures: P706A06BoundaryFixtures,
        layout: LedgerStorageLayout,
        realFileSystem: AndroidLedgerFileSystem,
    ) {
        assertShaMatches(fixtures, File(fixtures.generationMainFile(1)), marker.authoritativeSha256, "the staged generation")
        if (boundary == P706A06Boundary.B12) {
            assertShaMatches(fixtures, File(fixtures.legacyMainFilePath()), requireNotNull(marker.legacySha256), "the legacy original")
        } else {
            assertFalse("the half-removed legacy main file must stay gone", File(fixtures.legacyMainFilePath()).exists())
        }
        val graph = openProductionStartup(context)
        try {
            assertJournalAbsent(realFileSystem, layout)
        } finally {
            graph.close()
        }
        assertPointerNames(realFileSystem, layout, 1)
        assertAuthoritativeFacts(fixtures, File(fixtures.generationMainFile(1)), marker)
        if (boundary == P706A06Boundary.B12) {
            assertTrue(
                "the legacy original is inert after the upgrade and must still exist",
                File(fixtures.legacyMainFilePath()).exists(),
            )
        } else {
            assertFalse("no second effective graph: the legacy main file must be gone", File(fixtures.legacyMainFilePath()).exists())
        }
    }

    // ------------------------------------------------------------------ fresh install (B14)

    private fun verifyFreshInstallRow(
        marker: P706A06BoundaryMarker,
        context: Context,
        fixtures: P706A06BoundaryFixtures,
        layout: LedgerStorageLayout,
        realFileSystem: AndroidLedgerFileSystem,
    ) {
        assertStartupFailsClosedPointerMissing(context, fixtures, layout, realFileSystem, expectedGenerations = listOf("gen-1"))
        // No legacy original exists, so the discard branch is typed-unavailable (guard 1) - the
        // silent-empty-database prohibition keeps its full strength on a half-built fresh install.
        assertEquals(
            PointerRecoveryDiscardResult.LegacyOriginalMissing,
            pointerMissingRecoveryUseCase(fixtures, layout).discardUnvalidatableAndReUpgrade(),
        )
        assertShaMatches(fixtures, File(fixtures.generationMainFile(1)), marker.authoritativeSha256, "the driver-created generation")
        assertEquals(PointerRecoveryAdoptionResult.Adopted(1), pointerMissingRecoveryUseCase(fixtures, layout).adopt())
        assertPointerNames(realFileSystem, layout, 1)

        val graph = openProductionStartup(context)
        try {
            assertJournalAbsent(realFileSystem, layout)
        } finally {
            graph.close()
        }
        assertPointerNames(realFileSystem, layout, 1)
        assertAuthoritativeFacts(fixtures, File(fixtures.generationMainFile(1)), marker)
        assertEquals(
            "no second generation may appear anywhere in the convergence",
            listOf("gen-1"),
            realFileSystem.listDirectory(layout.generationsDirectory).sorted(),
        )
    }

    // ------------------------------------------------------------------ POINTER_MISSING branches (B15/B16)

    private fun verifyAdoptionRow(
        marker: P706A06BoundaryMarker,
        context: Context,
        fixtures: P706A06BoundaryFixtures,
        layout: LedgerStorageLayout,
        realFileSystem: AndroidLedgerFileSystem,
    ) {
        assertShaMatches(fixtures, File(fixtures.generationMainFile(1)), marker.authoritativeSha256, "the adopted candidate")
        val graph = openProductionStartup(context)
        try {
            assertJournalAbsent(realFileSystem, layout)
        } finally {
            graph.close()
        }
        // The publish is the branch's only mutation: the pointer either stayed absent or was
        // published complete - the AtomicFile replacement admits no partial state to consume.
        assertPointerNames(realFileSystem, layout, 1)
        assertAuthoritativeFacts(fixtures, File(fixtures.generationMainFile(1)), marker)
    }

    private fun verifyDiscardRow(
        marker: P706A06BoundaryMarker,
        context: Context,
        fixtures: P706A06BoundaryFixtures,
        layout: LedgerStorageLayout,
        realFileSystem: AndroidLedgerFileSystem,
    ) {
        assertStartupFailsClosedPointerMissing(context, fixtures, layout, realFileSystem, expectedGenerations = listOf("gen-1"))
        val recovery = pointerMissingRecoveryUseCase(fixtures, layout)
        val probe = recovery.probe()
        assertTrue("the pointerless shape must surface as recoverable: $probe", probe is PointerMissingRecoveryProbe.Recoverable)
        val state = (probe as PointerMissingRecoveryProbe.Recoverable).state
        assertEquals(listOf(1), state.candidates.map { candidate -> candidate.generation })
        assertTrue(
            "a half-deleted candidate must never verify Adoptable: ${state.candidates.single().verdict}",
            state.candidates.single().verdict != PointerRecoveryCandidateVerdict.Adoptable,
        )
        assertNull("no adoptable generation may exist in the discard shape", state.adoptableGeneration)
        assertShaMatches(fixtures, File(fixtures.legacyMainFilePath()), requireNotNull(marker.legacySha256), "the legacy original")

        assertEquals(PointerRecoveryDiscardResult.DiscardedAwaitingUpgrade, recovery.discardUnvalidatableAndReUpgrade())

        // The re-upgrade is the normal startup's frozen non-destructive sequence: the user's real
        // legacy ledger is re-staged into gen-1 and only then removed.
        val graph = openProductionStartup(context)
        try {
            assertJournalAbsent(realFileSystem, layout)
        } finally {
            graph.close()
        }
        assertPointerNames(realFileSystem, layout, 1)
        assertAuthoritativeFacts(fixtures, File(fixtures.generationMainFile(1)), marker)
        assertFalse("the legacy set is removed only after the re-upgrade publishes", File(fixtures.legacyMainFilePath()).exists())
    }

    // ------------------------------------------------------------------ the startup recovery rows (B17-B19)

    private fun verifyStartupRecoveryRow(
        boundary: P706A06Boundary,
        marker: P706A06BoundaryMarker,
        context: Context,
        fixtures: P706A06BoundaryFixtures,
        layout: LedgerStorageLayout,
        realFileSystem: AndroidLedgerFileSystem,
    ) {
        if (boundary != P706A06Boundary.B17) {
            // B18/B19 start from a settled disk (no journal), so the old generation's bytes are
            // still the park-time fingerprint before any read-write open runs.
            assertShaMatches(fixtures, File(fixtures.generationMainFile(1)), marker.authoritativeSha256, "the old generation")
        }
        val graph = openProductionStartup(context)
        try {
            assertJournalAbsent(realFileSystem, layout)
        } finally {
            graph.close()
        }
        assertPointerNames(realFileSystem, layout, 1)
        assertAuthoritativeFacts(fixtures, File(fixtures.generationMainFile(1)), marker)
        when (boundary) {
            P706A06Boundary.B17 ->
                // Recovery of recovery: run A killed inside the journal-gate recovery with the
                // journal still on disk, and THIS startup re-ran the same frozen recovery to
                // completion - idempotent convergence on the old generation.
                assertFalse("the gate must have discarded the staged generation", realFileSystem.exists(layout.generationDirectory(2)))
            P706A06Boundary.B18 ->
                // The recovered state does not resurrect: the journal stays consumed and the
                // retained staged generation stays inert, never selected.
                assertTrue("the retained staged generation must stay inert on disk", realFileSystem.exists(fixtures.generationMainFile(2)))
            P706A06Boundary.B19 ->
                // The half-deleted staged remnant survives as inert content only.
                assertFalse("the half-deleted staged main file must stay gone", realFileSystem.exists(fixtures.generationMainFile(2)))
            else -> throw AssertionError("unreachable recovery row: $boundary")
        }
    }

    // ------------------------------------------------------------------ shared assertion faces

    /** The production startup over the redirected context, with the REAL driver and graph. */
    private fun openProductionStartup(context: Context): CloseableLedgerGraph =
        openAndroidStableStorageLedger(
            context = context,
            importFilePickPort = silentImportPort(),
            importPickChannel = ImportFilePickResultChannel(),
        )

    /**
     * The fail-closed face (spec section 3.5, no-silent-empty-database): the pointerless shape
     * must reject with `POINTER_MISSING`, publish nothing and rebuild nothing - the generation
     * listing must be exactly what the killed process left.
     */
    private fun assertStartupFailsClosedPointerMissing(
        context: Context,
        fixtures: P706A06BoundaryFixtures,
        layout: LedgerStorageLayout,
        realFileSystem: AndroidLedgerFileSystem,
        expectedGenerations: List<String>,
    ) {
        assertFalse("the fixture must still be pointerless", realFileSystem.exists(layout.activePointerFile))
        try {
            openProductionStartup(context)
            fail("the pointerless shape must fail closed, not open a graph")
        } catch (rejected: LedgerStorageRejectedException) {
            assertEquals(LedgerStorageFailure.POINTER_MISSING, rejected.failure)
        }
        assertFalse("the fail-closed startup must not publish a pointer", realFileSystem.exists(layout.activePointerFile))
        assertEquals(
            "the fail-closed startup must neither rebuild nor add generations",
            expectedGenerations,
            realFileSystem.listDirectory(layout.generationsDirectory).sorted(),
        )
        assertFileIsolatedFromProduction(File(fixtures.legacyMainFilePath()))
    }

    /** The pointer is single-valued and names exactly the generation that was opened. */
    private fun assertPointerNames(
        realFileSystem: AndroidLedgerFileSystem,
        layout: LedgerStorageLayout,
        generation: Int,
    ) {
        assertEquals(
            "the active pointer must name the converged generation",
            "gen-$generation",
            realFileSystem.readBytes(layout.activePointerFile).decodeToString(),
        )
    }

    /** The journal consumption state: nothing the restart consumed (or found absent) survives. */
    private fun assertJournalAbsent(
        realFileSystem: AndroidLedgerFileSystem,
        layout: LedgerStorageLayout,
    ) {
        assertFalse("no switch journal may survive the convergence", realFileSystem.exists(layout.switchJournalFile))
    }

    /**
     * The authoritative read-back (spec section 3.5): the converged main file carries the
     * fixture's marker row and the current schema version - a complete old or new generation,
     * never a half library and never a silently rebuilt empty one.
     */
    private fun assertAuthoritativeFacts(
        fixtures: P706A06BoundaryFixtures,
        mainFile: File,
        marker: P706A06BoundaryMarker,
    ) {
        assertTrue("the converged generation main file must exist: ${mainFile.path}", mainFile.exists())
        assertTrue("a converged main file must be non-empty", mainFile.length() > 0L)
        val facts = fixtures.readFacts(mainFile.absolutePath)
        assertEquals(
            "the converged ledger must carry the current schema version (never a rebuilt empty schema)",
            currentSupportedSchemaVersion(),
            facts.userVersion,
        )
        if (marker.authoritativePayload != null) {
            assertEquals(
                "the authoritative read-back must carry the fixture's marker row",
                marker.authoritativePayload,
                facts.payload,
            )
        } else {
            assertNull("the fresh-install seed carries no marker row", facts.payload)
        }
    }

    /** The identity checkpoint: a file nothing has reopened must still be its park-time bytes. */
    private fun assertShaMatches(
        fixtures: P706A06BoundaryFixtures,
        file: File,
        expectedSha256: String,
        what: String,
    ) {
        assertTrue("$what must still exist: ${file.path}", file.exists())
        assertEquals("$what must be byte-identical to the park-time fingerprint", expectedSha256, fixtures.sha256Of(file))
    }

    private fun pointerMissingRecoveryUseCase(
        fixtures: P706A06BoundaryFixtures,
        layout: LedgerStorageLayout,
    ): PointerMissingRecoveryUseCase =
        PointerMissingRecoveryUseCase(
            fileSystem = AndroidLedgerFileSystem(),
            layout = layout,
            isolatedDatabase = AndroidRestoreIsolatedDatabasePort(),
            currentSchemaVersion = currentSupportedSchemaVersion(),
            legacyMainFile = fixtures.legacyMainFilePath(),
        )

    /** The REAL Android import port with a silent SAF face (the recovery-suite shape). */
    private fun silentImportPort(): AndroidImportFilePickPort<Uri> =
        AndroidImportFilePickPort<Uri>(
            launchOpenDocument = {},
            resolveMetadata = { PickedSafFileMetadata(displayName = "", sizeBytes = null) },
            openInputStream = { null },
            onResult = {},
        )
}
