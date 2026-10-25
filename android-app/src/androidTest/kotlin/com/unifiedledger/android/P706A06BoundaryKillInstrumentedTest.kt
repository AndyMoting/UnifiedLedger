package com.unifiedledger.android

import android.content.Context
import android.net.Uri
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unifiedledger.application.backup.JvmBackupCryptoPrimitives
import com.unifiedledger.data.AndroidLedgerDatabaseHandle
import com.unifiedledger.data.createAndroidLedgerDatabase
import com.unifiedledger.data.currentSupportedSchemaVersion
import com.unifiedledger.ui.BackupSourcePort
import com.unifiedledger.ui.ConfirmBackupRestoreUseCase
import com.unifiedledger.ui.ImportFilePickResultChannel
import com.unifiedledger.ui.LedgerRuntimeOwner
import com.unifiedledger.ui.LedgerStartupResult
import com.unifiedledger.ui.PointerMissingRecoveryUseCase
import com.unifiedledger.ui.RestorePreflightRequest
import com.unifiedledger.ui.RestorePreflightResult
import com.unifiedledger.ui.RestorePreflightUseCase
import com.unifiedledger.ui.ledgerStorageLayout
import com.unifiedledger.ui.openGenerationForSelection
import com.unifiedledger.ui.openStableStorageLedger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * P706-A06 (D-246; spec `docs/specs/2026-10-24-p706-a02-a06-acceptance-design.md` sections
 * 3.3/3.4): run A of the two-phase process-kill protocol - the KILL leg. Each method drives one
 * REAL production flow over the [P706A06BoundaryHarness] decorator until the armed boundary
 * parks the flow thread, writes the marker (boundary number + fixture fingerprint, spec section
 * 3.3 step 1), and kills the process with `Process.killProcess(Process.myPid())` - the frozen
 * main kill means; `am kill` only covers safe-to-stop background processes and is not reliable
 * for a foreground instrumented process (spec section 3.3's kill-means restriction).
 *
 * SELECTION DISCIPLINE: one boundary per instrumentation start. The process dies inside every
 * method, so a run of this class executes exactly the first selected method's kill; the device
 * gate pairs each `bNN` method with the matching `vNN` verify method of
 * `P706A06BoundaryVerifyInstrumentedTest` in the next instrumentation start over the same stable
 * redirected root (spec section 3.3 step 3 - runs may be grouped, assertions may not be merged;
 * this suite keeps one @Test per boundary row, B1..B19 all covered).
 *
 * What runs for real: the confirm-chain rows (B1-B9) drive the REAL owner (startup through the
 * production `openAndroidStableStorageLedger`), the REAL preflight over a REAL authenticated
 * container (`RestorePreflightUseCase` + `AndroidRestoreIsolatedDatabasePort` +
 * `JvmBackupCryptoPrimitives`, the `AndroidRestoreConfirmSwitchInstrumentedTest` wiring) and the
 * REAL `ConfirmBackupRestoreUseCase.confirm` with the decorator as its file system; its step-8
 * explicit reopen is wired through `openAndroidExplicitGenerationWith` over the decorator so the
 * reopen's gate reads are observable (B7) - production always arrives at the same body through
 * `openAndroidExplicitGeneration`. B10-B14 and B17-B19 drive the public `openStableStorageLedger`
 * (`LedgerRuntimeOwner.kt:1003`) over the decorator, with the REAL platform driver
 * (`createAndroidLedgerDatabase`) as the graph open - the create-on-open of B14 is therefore a
 * REAL on-device database creation. B15/B16 drive the REAL `PointerMissingRecoveryUseCase` over
 * the decorator with the real isolated-database port.
 *
 * The process kill itself is the phenomenon under test: a single instrumented run cannot kill
 * itself and keep asserting, so nothing after `Process.killProcess` runs here by design; the
 * assertions live in the paired verify leg, and this leg's own pass criterion is that the flow
 * parked at the armed boundary before the kill (a boundary that never parks fails loudly here,
 * leaving the redirected root in place as forensics).
 *
 * Data safety: the production ledger is never touched - the stable-root redirection guard
 * ([p706A06RedirectedContext]) and the isolation guard ([assertFileIsolatedFromProduction])
 * follow the `AndroidRestoreRecoveryInstrumentedTest` MUST FIX A pattern.
 */
@RunWith(AndroidJUnit4::class)
class P706A06BoundaryKillInstrumentedTest {
    @Test
    fun b01PreStageDeleteKillsMidDeletion() = killAtBoundary(P706A06Boundary.B1)

    @Test
    fun b02MainFileCopyKillsAfterTheCopy() = killAtBoundary(P706A06Boundary.B2)

    @Test
    fun b03SidecarFsyncSequenceKillsAfterTheGenerationFsync() = killAtBoundary(P706A06Boundary.B3)

    @Test
    fun b04PreparedJournalKillsAfterItsDurableWrite() = killAtBoundary(P706A06Boundary.B4)

    @Test
    fun b05PointerPublishKillsAfterThePublish() = killAtBoundary(P706A06Boundary.B5)

    @Test
    fun b06SwitchedJournalKillsAfterItsDurableWrite() = killAtBoundary(P706A06Boundary.B6)

    @Test
    fun b07StepEightReopenKillsDuringTheReopenGate() = killAtBoundary(P706A06Boundary.B7)

    @Test
    fun b08JournalRemovalKillsAfterTheDurableDelete() = killAtBoundary(P706A06Boundary.B8)

    @Test
    fun b09EscapeRepairKillsMidRepairDeletion() = killAtBoundary(P706A06Boundary.B9)

    @Test
    fun b10LegacyUpgradeKillsAfterTheStageFsync() = killAtBoundary(P706A06Boundary.B10)

    @Test
    fun b11LegacyUpgradeKillsBeforeThePointerPublish() = killAtBoundary(P706A06Boundary.B11)

    @Test
    fun b12UpgradePublishKillsAfterThePublish() = killAtBoundary(P706A06Boundary.B12)

    @Test
    fun b13LegacyRemovalKillsAfterTheMainDelete() = killAtBoundary(P706A06Boundary.B13)

    @Test
    fun b14FreshInstallKillsBeforeThePointerPublish() = killAtBoundary(P706A06Boundary.B14)

    @Test
    fun b15AdoptionKillsAfterThePublish() = killAtBoundary(P706A06Boundary.B15)

    @Test
    fun b16DiscardKillsMidCandidateDeletion() = killAtBoundary(P706A06Boundary.B16)

    @Test
    fun b17StartupRecoveryKillsAfterTheOldPointerRepublish() = killAtBoundary(P706A06Boundary.B17)

    @Test
    fun b18StartupRecoveryKillsAfterTheJournalDelete() = killAtBoundary(P706A06Boundary.B18)

    @Test
    fun b19StartupRecoveryKillsMidStagedDeletion() = killAtBoundary(P706A06Boundary.B19)

    // ------------------------------------------------------------------ the two-phase protocol, step 1

    /**
     * Spec section 3.3 step 1: clean and seed the stable root, arm [boundary], drive the real
     * flow on a worker thread until the decorator parks it, write the marker, then kill the
     * process while the flow is parked (the "boundary N durable, N+1 not started" window).
     */
    private fun killAtBoundary(boundary: P706A06Boundary) {
        val root = p706A06StableRoot()
        root.deleteRecursively()
        assertTrue("the stable root must be recreatable", root.mkdirs())
        val context = p706A06RedirectedContext(root)
        val hostDirectory = androidStableStoragePaths(context.getDatabasePath(P706A06BoundaryFixturePayloads.LEGACY_DATABASE_NAME)).first
        val legacyMainFile = context.getDatabasePath(P706A06BoundaryFixturePayloads.LEGACY_DATABASE_NAME).absolutePath
        val harness = P706A06BoundaryHarness(hostDirectory, legacyMainFile)
        val realFileSystem = AndroidLedgerFileSystem()
        val fixtures = P706A06BoundaryFixtures(context, realFileSystem, ledgerStorageLayout(realFileSystem, hostDirectory))
        var container = ByteArray(0)
        when (boundary.phase) {
            P706A06BoundaryPhase.CONFIRM_SWITCH -> container = fixtures.seedConfirmSwitchFixture()
            P706A06BoundaryPhase.LEGACY_UPGRADE -> fixtures.seedLegacyUpgradeFixture()
            P706A06BoundaryPhase.FRESH_INSTALL -> fixtures.seedFreshInstallFixture()
            P706A06BoundaryPhase.RECOVERY_ADOPT -> fixtures.seedAdoptCandidateFixture()
            P706A06BoundaryPhase.RECOVERY_DISCARD -> fixtures.seedDiscardCandidateFixture()
            P706A06BoundaryPhase.STARTUP_RECOVERY -> fixtures.seedSwitchedJournalFixture()
        }
        assertFileIsolatedFromProduction(root)

        harness.arm(boundary)
        val flowFailure = AtomicReference<Throwable?>(null)
        val flow =
            thread(name = "p706a06-flow-${boundary.name}") {
                try {
                    driveFlow(boundary, context, harness, container)
                } catch (failure: Throwable) {
                    flowFailure.set(failure)
                }
            }
        if (!harness.awaitParked(PARK_TIMEOUT_SECONDS)) {
            flow.join(5_000L)
            fail(
                "boundary ${boundary.name} never parked; decorated call history was:" +
                    harness.historyLines().joinToString(separator = "\n  ", prefix = "\n  ") +
                    "\nflow failure: ${flowFailure.get()}",
            )
        }

        val marker = markerFor(boundary, fixtures)
        P706A06BoundaryMarkerProtocol.write(root, marker)
        File(root, RUN_A_LOG_NAME).writeText(
            listOf(
                "boundary=${boundary.name}",
                "phase=${boundary.phase.name}",
                "convergence=${marker.convergence.name}",
                "parkedOn=" + harness.historyLines().last(),
            ).joinToString(separator = "\n", postfix = "\n"),
        )
        android.util.Log.i(
            P706A06_LOG_TAG,
            "run A marker written for ${boundary.name}; killing the process",
        )
        Process.killProcess(Process.myPid())
    }

    /** Drives the REAL production flow that owns the armed boundary, on the flow thread. */
    private fun driveFlow(
        boundary: P706A06Boundary,
        context: Context,
        harness: P706A06BoundaryHarness,
        container: ByteArray,
    ) {
        when (boundary.phase) {
            P706A06BoundaryPhase.CONFIRM_SWITCH -> driveConfirmSwitchFlow(context, harness, container)
            P706A06BoundaryPhase.LEGACY_UPGRADE,
            P706A06BoundaryPhase.FRESH_INSTALL,
            P706A06BoundaryPhase.STARTUP_RECOVERY,
            -> driveCompositionRootStartup(boundary, context, harness)
            P706A06BoundaryPhase.RECOVERY_ADOPT -> driveRecoveryAdoption(harness)
            P706A06BoundaryPhase.RECOVERY_DISCARD -> driveRecoveryDiscard(harness)
        }
    }

    /**
     * The confirm chain (B1-B9): the real owner (ActivePointer through the production startup,
     * Explicit through the decorator so B7's gate read is observable), the real preflight over a
     * real container, then the REAL confirm - the nine-step sequence whose staging, journal and
     * pointer primitives all run through the armed decorator.
     */
    private fun driveConfirmSwitchFlow(
        context: Context,
        harness: P706A06BoundaryHarness,
        container: ByteArray,
    ) {
        val importPort = silentImportPort()
        val importChannel = ImportFilePickResultChannel()
        val owner =
            LedgerRuntimeOwner<CloseableLedgerGraph>(
                openGeneration = { selection ->
                    openGenerationForSelection(
                        selection = selection,
                        openActivePointer = { openAndroidStableStorageLedger(context, importPort, importChannel) },
                        openExplicit = { generation ->
                            openAndroidExplicitGenerationWith(
                                fileSystem = harness,
                                layout = harness.layout,
                                generation = generation,
                                importFilePickPort = importPort,
                                importPickChannel = importChannel,
                                openDriver = { name -> createAndroidLedgerDatabase(context, name) },
                            )
                        },
                    )
                },
                closeGraph = { graph -> graph.close() },
                facadeOf = { graph -> graph.facade },
            )
        assertEquals(LedgerStartupResult.Started(1), owner.startup())

        val realFileSystem = AndroidLedgerFileSystem()
        val preflight =
            RestorePreflightUseCase(
                owner = owner,
                fileSystem = realFileSystem,
                layout = ledgerStorageLayout(realFileSystem, harness.layout.hostDirectory),
                source = deliveringSourcePort(container),
                isolatedDatabase = AndroidRestoreIsolatedDatabasePort(),
                crypto = JvmBackupCryptoPrimitives(),
                newToken = { P706A06BoundaryFixturePayloads.CONFIRM_TOKEN },
                nowMillis = { 0L },
            )
        val request =
            RestorePreflightRequest(
                password = P706A06BoundaryFixturePayloads.CONFIRM_PASSWORD,
                targetLedgerId = RESTORE_TARGET_LEDGER_ID,
                supportedSourceVersions = RESTORE_SUPPORTED_SOURCE_VERSIONS,
                currentSchemaVersion = currentSupportedSchemaVersion(),
            )
        val preview = preflight.preflight(request)
        assertTrue("the real preflight must produce a preview: $preview", preview is RestorePreflightResult.PreviewReady)
        val token = (preview as RestorePreflightResult.PreviewReady).token

        val confirm =
            ConfirmBackupRestoreUseCase(
                owner = owner,
                fileSystem = harness,
                layout = harness.layout,
                crypto = JvmBackupCryptoPrimitives(),
                targetLedgerId = RESTORE_TARGET_LEDGER_ID,
            )
        runBlocking { confirm.confirm(token) }
    }

    /**
     * The composition-root phases over the PUBLIC `openStableStorageLedger`
     * (`LedgerRuntimeOwner.kt:1003`): the legacy upgrade (B10-B13), the fresh install (B14) and
     * the preset switched-journal startup recovery (B17-B19, parked inside the resolution's
     * journal gate). The graph open is the REAL platform driver over the resolved absolute main
     * file - create-on-open included, which is what makes B14's gen-1 a real on-device database.
     */
    private fun driveCompositionRootStartup(
        boundary: P706A06Boundary,
        context: Context,
        harness: P706A06BoundaryHarness,
    ) {
        val legacyMainFile =
            context.getDatabasePath(P706A06BoundaryFixturePayloads.LEGACY_DATABASE_NAME).absolutePath
        val opened: AndroidLedgerDatabaseHandle =
            openStableStorageLedger(
                fileSystem = harness,
                layout = harness.layout,
                legacyMainFile = legacyMainFile,
                closeGraph = { handle -> handle.close() },
                openGraph = { target -> createAndroidLedgerDatabase(context, target.mainFile) },
            )
        // Unreachable for every armed boundary (the flow parks before the sequence returns); the
        // close keeps the driver from leaking on a mis-armed run.
        opened.close()
        throw AssertionError("the ${boundary.name} flow returned without parking at its boundary")
    }

    /** B15: the REAL user-confirmed adoption over the decorator. */
    private fun driveRecoveryAdoption(harness: P706A06BoundaryHarness) {
        val recovery = pointerMissingRecoveryUseCase(harness)
        recovery.adopt()
        throw AssertionError("the B15 flow returned without parking at its boundary")
    }

    /** B16: the REAL user-confirmed discard-and-re-upgrade over the decorator. */
    private fun driveRecoveryDiscard(harness: P706A06BoundaryHarness) {
        val recovery = pointerMissingRecoveryUseCase(harness)
        recovery.discardUnvalidatableAndReUpgrade()
        throw AssertionError("the B16 flow returned without parking at its boundary")
    }

    private fun pointerMissingRecoveryUseCase(harness: P706A06BoundaryHarness): PointerMissingRecoveryUseCase =
        PointerMissingRecoveryUseCase(
            fileSystem = harness,
            layout = harness.layout,
            isolatedDatabase = AndroidRestoreIsolatedDatabasePort(),
            currentSchemaVersion = currentSupportedSchemaVersion(),
            legacyMainFile = harness.legacyMainFilePath,
        )

    /**
     * The marker inputs per boundary: the convergence run B must observe and the fixture
     * fingerprints captured at park time (the authoritative generation's marker row and SHA-256,
     * plus the legacy original's when the fixture has one).
     */
    private fun markerFor(
        boundary: P706A06Boundary,
        fixtures: P706A06BoundaryFixtures,
    ): P706A06BoundaryMarker {
        val payloads = P706A06BoundaryFixturePayloads
        val legacyFile = File(fixtures.legacyMainFilePath())
        return when (boundary) {
            P706A06Boundary.B1,
            P706A06Boundary.B2,
            P706A06Boundary.B3,
            P706A06Boundary.B4,
            P706A06Boundary.B5,
            P706A06Boundary.B6,
            P706A06Boundary.B7,
            P706A06Boundary.B9,
            -> confirmMarker(boundary, fixtures, payloads.CONFIRM_OLD, P706A06MarkerConvergence.OPENS_OLD_GENERATION)
            P706A06Boundary.B8 -> confirmMarker(boundary, fixtures, payloads.CONFIRM_NEW, P706A06MarkerConvergence.OPENS_NEW_GENERATION)
            P706A06Boundary.B10,
            P706A06Boundary.B11,
            -> legacyMarker(boundary, fixtures, legacyFile, P706A06MarkerConvergence.POINTER_MISSING_THEN_ADOPT, legacyRetained = true)
            P706A06Boundary.B12 -> legacyMarker(boundary, fixtures, legacyFile, P706A06MarkerConvergence.OPENS_OLD_GENERATION, legacyRetained = true)
            P706A06Boundary.B13 -> legacyMarker(boundary, fixtures, legacyFile, P706A06MarkerConvergence.OPENS_OLD_GENERATION, legacyRetained = false)
            P706A06Boundary.B14 -> freshInstallMarker(boundary, fixtures)
            P706A06Boundary.B15 -> adoptionMarker(boundary, fixtures)
            P706A06Boundary.B16 -> discardMarker(boundary, fixtures, legacyFile)
            P706A06Boundary.B17,
            P706A06Boundary.B18,
            P706A06Boundary.B19,
            -> confirmMarker(boundary, fixtures, payloads.RECOVERY_OLD, P706A06MarkerConvergence.OPENS_OLD_GENERATION)
        }
    }

    /** The legacy-upgrade marker (B10-B13): the staged gen-1 is the authority, gen-1's copy. */
    private fun legacyMarker(
        boundary: P706A06Boundary,
        fixtures: P706A06BoundaryFixtures,
        legacyFile: File,
        convergence: P706A06MarkerConvergence,
        legacyRetained: Boolean,
    ): P706A06BoundaryMarker =
        P706A06BoundaryMarker(
            boundary = boundary,
            convergence = convergence,
            authoritativePayload = P706A06BoundaryFixturePayloads.LEGACY_UPGRADE,
            legacyPayload = P706A06BoundaryFixturePayloads.LEGACY_UPGRADE,
            legacySha256 = if (legacyRetained) fixtures.sha256Of(legacyFile) else null,
            authoritativeSha256 = fixtures.sha256Of(generationMain(fixtures, 1)),
        )

    /**
     * The fresh-install marker (B14): the gen-1 database is created by the real driver mid-flow,
     * so it carries the product schema at the current version but no boundary_marker row.
     */
    private fun freshInstallMarker(
        boundary: P706A06Boundary,
        fixtures: P706A06BoundaryFixtures,
    ): P706A06BoundaryMarker =
        P706A06BoundaryMarker(
            boundary = boundary,
            convergence = P706A06MarkerConvergence.POINTER_MISSING_THEN_ADOPT_FRESH,
            authoritativePayload = null,
            legacyPayload = null,
            legacySha256 = null,
            authoritativeSha256 = fixtures.sha256Of(generationMain(fixtures, 1)),
        )

    /** The adoption marker (B15): the adoptable candidate gen-1 is the authority. */
    private fun adoptionMarker(
        boundary: P706A06Boundary,
        fixtures: P706A06BoundaryFixtures,
    ): P706A06BoundaryMarker =
        P706A06BoundaryMarker(
            boundary = boundary,
            convergence = P706A06MarkerConvergence.OPENS_OLD_GENERATION,
            authoritativePayload = P706A06BoundaryFixturePayloads.ADOPT_CANDIDATE,
            legacyPayload = null,
            legacySha256 = null,
            authoritativeSha256 = fixtures.sha256Of(generationMain(fixtures, 1)),
        )

    /** The discard marker (B16): the legacy original is both the authority and the re-source. */
    private fun discardMarker(
        boundary: P706A06Boundary,
        fixtures: P706A06BoundaryFixtures,
        legacyFile: File,
    ): P706A06BoundaryMarker {
        val legacySha256 = fixtures.sha256Of(legacyFile)
        return P706A06BoundaryMarker(
            boundary = boundary,
            convergence = P706A06MarkerConvergence.POINTER_MISSING_THEN_DISCARD_RE_UPGRADE,
            authoritativePayload = P706A06BoundaryFixturePayloads.LEGACY_UPGRADE,
            legacyPayload = P706A06BoundaryFixturePayloads.LEGACY_UPGRADE,
            legacySha256 = legacySha256,
            authoritativeSha256 = legacySha256,
        )
    }

    /** The confirm-chain marker shape: no legacy original exists in that fixture. */
    private fun confirmMarker(
        boundary: P706A06Boundary,
        fixtures: P706A06BoundaryFixtures,
        payload: String,
        convergence: P706A06MarkerConvergence,
    ): P706A06BoundaryMarker =
        P706A06BoundaryMarker(
            boundary = boundary,
            convergence = convergence,
            authoritativePayload = payload,
            legacyPayload = null,
            legacySha256 = null,
            authoritativeSha256 = fixtures.sha256Of(generationMain(fixtures, if (boundary == P706A06Boundary.B8) 2 else 1)),
        )

    /** The authoritative generation main file over the fixture layout. */
    private fun generationMain(
        fixtures: P706A06BoundaryFixtures,
        generation: Int,
    ): File {
        val main = File(fixtures.generationMainFile(generation))
        assertTrue("the authoritative generation main file must exist at park time: $main", main.exists())
        return main
    }

    /** The REAL Android import port with a silent SAF face (the recovery-suite shape). */
    private fun silentImportPort(): AndroidImportFilePickPort<Uri> =
        AndroidImportFilePickPort<Uri>(
            launchOpenDocument = {},
            resolveMetadata = { PickedSafFileMetadata(displayName = "", sizeBytes = null) },
            openInputStream = { null },
            onResult = {},
        )

    /**
     * The REAL [AndroidBackupSourcePort] delivering the container bytes synchronously (the
     * confirm-switch suite's in-memory delivery shape; the SAF provider path is that suite's
     * covered surface, not this one's).
     */
    private fun deliveringSourcePort(container: ByteArray): BackupSourcePort {
        var portRef: AndroidBackupSourcePort<Uri>? = null
        val port =
            AndroidBackupSourcePort<Uri>(
                postToMainThread = { action -> action() },
                launchOpenDocument = { portRef?.onOpenDocumentResult(SOURCE_URI) },
                openInputStream = { ByteArrayInputStream(container) },
                sizeOf = { container.size.toLong() },
            )
        portRef = port
        return port
    }

    private companion object {
        internal const val PARK_TIMEOUT_SECONDS = 180L

        internal const val RUN_A_LOG_NAME = "p706a06-run-a.log"

        internal val SOURCE_URI: Uri = Uri.parse("content://com.unifiedledger.android.p706a06/source")
    }
}
