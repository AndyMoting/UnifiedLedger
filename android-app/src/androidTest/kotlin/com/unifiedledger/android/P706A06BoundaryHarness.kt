package com.unifiedledger.android

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.unifiedledger.application.backup.BackupContainerSink
import com.unifiedledger.application.backup.BackupPlaintextReader
import com.unifiedledger.application.backup.BackupPlaintextSource
import com.unifiedledger.application.backup.JvmBackupCryptoPrimitives
import com.unifiedledger.application.backup.writeBackupContainer
import com.unifiedledger.data.createAndroidLedgerDatabase
import com.unifiedledger.data.currentSupportedSchemaVersion
import com.unifiedledger.data.defaultCatalogSeed
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.ui.LedgerFileSystem
import com.unifiedledger.ui.LedgerReadStream
import com.unifiedledger.ui.LedgerStorageLayout
import com.unifiedledger.ui.LedgerWriteStream
import com.unifiedledger.ui.ledgerStorageLayout
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/*
 * P706-A06 (D-246; spec `docs/specs/2026-10-24-p706-a02-a06-acceptance-design.md` sections 3.3-3.5):
 * the shared harness for the two-phase process-kill boundary protocol. The kill leg
 * (`P706A06BoundaryKillInstrumentedTest`, run A) drives a REAL production flow over the
 * `P706A06BoundaryHarness` decorator until the armed boundary's primitive parks the flow thread,
 * writes the marker, and kills the process with `Process.killProcess(Process.myPid())`; the verify
 * leg (`P706A06BoundaryVerifyInstrumentedTest`, run B) reads the marker and drives the REAL
 * production startup to assert the spec section 3.5 face. A single instrumented run cannot kill
 * itself and then keep asserting, so the two legs are separate instrumentation starts over one
 * stable redirected root (spec section 3.3, the two-phase protocol).
 *
 * Same-primitive multi-site discrimination (spec section 3.3, registered as an implementation
 * obligation in section 6 item 5): the parked primitives are app-ui `internal` functions that
 * androidTest cannot intercept by name, so the decorator discriminates by PUBLIC
 * `LedgerFileSystem` calls only - the primitive method, the target path, the content of the bytes
 * being written (the journal stage literal, the pointer generation literal) and the rolling call
 * history since arming. `deleteGenerationDirectory` has seven call sites in this chain -
 * `ConfirmBackupRestore.kt:426` (post-staging-failure sweep), `:439` (journal-write-failure
 * sweep), `:634` (the delete-then-stage precondition, boundary B1), `:743` (escape-repair sweep,
 * boundary B9's park), `:789` (rollback sweep), `BackupRestoreRecovery.kt:234` (boundary B16) and
 * `LedgerStableStorage.kt:817` (boundary B19) - and `publishActivePointer` serves five boundary
 * rows - B5 (`ConfirmBackupRestore.kt:448`), B12 (`LedgerRuntimeOwner.kt:1051`), B14 (`:1032`),
 * B15 (`BackupRestoreRecovery.kt:185`), B17 (`LedgerStableStorage.kt:799`) - plus two
 * non-boundary sites, the escape-repair republish (`ConfirmBackupRestore.kt:741`) and the
 * rollback republish (`:767`). No rule parks on a bare primitive occurrence: every rule keys on
 * history and content so the failure sweeps, the rollback sweep and the two non-boundary
 * republishes can never satisfy a boundary rule (spec section 3.3 forbids parking on a single
 * call site).
 *
 * Platform media honesty (spec section 3.1): every kill lands at a frozen primitive boundary -
 * after the boundary's last platform call returned, or (B11/B14) before the pointer publish call
 * runs - so the protocol does NOT produce a kill inside a primitive's streaming copy or inside an
 * `AtomicFile` write window (`.new` residue shapes) and does not claim to; process death also
 * preserves the page cache, so this is a process-kill protocol, not a power-loss one (the
 * spec registers real power loss as a separate, blocked leg). `fsyncDirectory` is a documented
 * best-effort no-op on Android (`AndroidLedgerFileSystem.kt:93-98`), so no assertion claims a
 * directory fsync effect.
 */

/** Which production flow the armed boundary is parked inside of (spec section 3.1's four groups). */
internal enum class P706A06BoundaryPhase {
    /** The confirm & switch nine-step sequence (`ConfirmBackupRestore.kt`), boundaries B1-B9. */
    CONFIRM_SWITCH,

    /** The composition-root legacy upgrade (`LedgerRuntimeOwner.kt:1039-1059`), B10-B13. */
    LEGACY_UPGRADE,

    /** The composition-root fresh install (`LedgerRuntimeOwner.kt:1025-1038`), B14. */
    FRESH_INSTALL,

    /** The user-confirmed POINTER_MISSING adoption (`BackupRestoreRecovery.kt:174-194`), B15. */
    RECOVERY_ADOPT,

    /** The user-confirmed discard-and-re-upgrade (`BackupRestoreRecovery.kt:196-261`), B16. */
    RECOVERY_DISCARD,

    /** The startup journal-gate recovery (`LedgerStableStorage.kt:780-819`), B17-B19. */
    STARTUP_RECOVERY,
}

/**
 * The nineteen persistent-state boundary rows (spec section 3.1, the code-named enumeration;
 * section 3.4's per-boundary matrix). Each entry documents its matrix row, its product
 * `file:line` anchors and the discrimination its park rule uses. All fixtures fix the disk
 * generations (old = `gen-1`, staged new = `gen-2`), so the rules below name those literals.
 */
internal enum class P706A06Boundary(
    val phase: P706A06BoundaryPhase,
    /** True = park BEFORE delegating the matched call (the pointer publish never runs). */
    val parkBeforeDelegate: Boolean = false,
) {
    /**
     * B1, the delete-then-stage pre-delete. `ConfirmBackupRestore.kt:633-634` calling
     * `deleteGenerationDirectory` (`LedgerStableStorage.kt:710-736`). Parks mid-deletion, right
     * after the leftover new-generation MAIN file delete returns (the sidecar and directory-entry
     * deletes have not run). Discriminated as the first delete of the new generation's main file
     * with no pointer write yet in history; the six other `deleteGenerationDirectory` sites are
     * excluded by their own history shapes (see the file KDoc).
     */
    B1(P706A06BoundaryPhase.CONFIRM_SWITCH),

    /**
     * B2, the new-generation main-file copy. `ConfirmBackupRestore.kt:636` (`fileSystem.copy`).
     * Parks right after the platform's streamed copy returns, before any sidecar copy or fsync -
     * the "copied but not yet durable" window. The row's restart face (an inert journal-less
     * staged generation plus an intact old generation) is what run B asserts.
     */
    B2(P706A06BoundaryPhase.CONFIRM_SWITCH),

    /**
     * B3, the sidecar copies + per-file fsyncs + generation-directory fsync + local gate.
     * `ConfirmBackupRestore.kt:637-651`. Parks after `fsyncDirectory` of the new generation
     * directory returns - the last write-op of `stageNewGeneration` (the trailing gate is
     * read-only).
     */
    B3(P706A06BoundaryPhase.CONFIRM_SWITCH),

    /**
     * B4, the journal=prepared write. `ConfirmBackupRestore.kt:429-441` calling `writeJournal`
     * (`:688-696`). Parks after the host-directory fsync that follows the `stage=prepared`
     * atomic write. Discriminated by the decoded journal content plus "no pointer write yet".
     */
    B4(P706A06BoundaryPhase.CONFIRM_SWITCH),

    /**
     * B5, the pointer publish. `ConfirmBackupRestore.kt:446-448` calling `publishActivePointer`
     * (`LedgerStableStorage.kt:562-570`). Parks after the full primitive (atomic write + file
     * fsync + host fsync) - the textbook "pointer moved, journal still prepared" rollback window.
     * Discriminated by the decoded pointer bytes naming gen-2.
     */
    B5(P706A06BoundaryPhase.CONFIRM_SWITCH),

    /**
     * B6, the journal=switched write. `ConfirmBackupRestore.kt:449`. Parks after the
     * host-directory fsync following the `stage=switched` atomic write - the switched window run
     * B's startup must roll back.
     */
    B6(P706A06BoundaryPhase.CONFIRM_SWITCH),

    /**
     * B7, the step-8 explicit reopen window - a crash-window row with NO new disk write (spec
     * section 3.1). `ConfirmBackupRestore.kt:471` re-opening through `openExplicitGeneration`
     * (`LedgerStableStorage.kt:388-400`). Parks during the reopen's gate read (`readPrefix` of
     * the new generation's main file, observable because this harness wires the explicit route
     * over the decorator); the disk equals the B6 end state, which is exactly the row's window.
     */
    B7(P706A06BoundaryPhase.CONFIRM_SWITCH),

    /**
     * B8, the durable journal removal = committed. `ConfirmBackupRestore.kt:480-492` calling
     * `removeSwitchJournalDurably` (`:703-706`). Parks after the host-directory fsync that
     * follows the journal delete. The matrix row's two kill points (before/after that fsync)
     * coincide on disk here because `fsyncDirectory` is a best-effort no-op on Android
     * (`AndroidLedgerFileSystem.kt:93-98`) - the park lands after both calls, the strongest
     * durability the platform offers.
     */
    B8(P706A06BoundaryPhase.CONFIRM_SWITCH),

    /**
     * B9, the post-commit escape repair - a flow row (spec section 3.1). The escape is produced
     * deterministically by injecting an `Error` out of the fail-loud port at the prepared
     * journal's `fsyncFile` (the `catch (failure: Error) { throw failure }` at
     * `ConfirmBackupRestore.kt:433-434` rethrows it into the section 3.10 repair at
     * `:493-503`/`:727-751`). Parks mid-repair, right after the repair's
     * `deleteGenerationDirectory` (`:743`) deletes the staged main file. Discriminated by the
     * injected-error marker plus the repair's own republish/journal-removal history - the
     * rollback sweep at `:789` performs the same deletes WITHOUT the injected-error tag and can
     * never satisfy this rule.
     */
    B9(P706A06BoundaryPhase.CONFIRM_SWITCH),

    /**
     * B10, the legacy-upgrade copy + fsync sequence. `LedgerRuntimeOwner.kt:1043` calling
     * `stageLegacyUpgrade` (`LedgerStableStorage.kt:530-556`). Parks after the staged generation
     * directory's `fsyncDirectory` returns - copy complete and flushed, pointer not published.
     */
    B10(P706A06BoundaryPhase.LEGACY_UPGRADE),

    /**
     * B11, the pre-pointer-publish window of the legacy upgrade - a crash-window row with no new
     * disk write. Between `LedgerRuntimeOwner.kt:1043` (stage complete) and `:1051`
     * (`publishActivePointer`). Parks BEFORE delegating the pointer's atomic write, with the
     * staged copy, the usability gate and the real driver open already behind it - the
     * "generation directory but no pointer" shape that startup fails closed on (the D-178
     * bricking window, spec section 3.4 row B11).
     */
    B11(P706A06BoundaryPhase.LEGACY_UPGRADE, parkBeforeDelegate = true),

    /**
     * B12, the upgrade pointer publish. `LedgerRuntimeOwner.kt:1051` calling
     * `publishActivePointer` (`LedgerStableStorage.kt:562-570`) - the same frozen primitive as
     * B5/B14/B15/B17, discriminated here by the legacy-upgrade history (a staged copy of the
     * legacy set is behind it). Parks after the full primitive.
     */
    B12(P706A06BoundaryPhase.LEGACY_UPGRADE),

    /**
     * B13, the legacy set removal. `LedgerRuntimeOwner.kt:1052` calling `removeLegacyFiles`
     * (`LedgerStableStorage.kt:578-591`). Parks right after the legacy MAIN file delete returns,
     * before the deletable-sidecar loop - the half-removed shape.
     */
    B13(P706A06BoundaryPhase.LEGACY_UPGRADE),

    /**
     * B14, the fresh-install generation creation and pointer publish.
     * `LedgerRuntimeOwner.kt:1029-1032` (createDirectories, create-on-open driver open, publish).
     * Parks BEFORE delegating the pointer's atomic write: a real driver-created `gen-1` database
     * exists, no pointer, and no legacy original - so the discard branch is typed-unavailable
     * (`BackupRestoreRecovery.kt:213-218`) and run B must converge through adoption, never a
     * silent rebuild (D-176's prohibition).
     */
    B14(P706A06BoundaryPhase.FRESH_INSTALL, parkBeforeDelegate = true),

    /**
     * B15, the recovery adoption publish. `BackupRestoreRecovery.kt:185` calling
     * `publishActivePointer`. Parks after the full primitive - the "pointer fully published" end
     * of the row's disjunction; the publish is the branch's only mutation, so no intermediate
     * state exists to consume.
     */
    B15(P706A06BoundaryPhase.RECOVERY_ADOPT),

    /**
     * B16, the discard-and-re-upgrade candidate deletion. `BackupRestoreRecovery.kt:234` calling
     * `deleteGenerationDirectory` (strict deletion, `:240`). Parks right after the candidate main
     * file delete returns, before the sidecar and directory-entry deletes - mid-deletion, with
     * the generations directory still present so the shape stays fail-closed.
     */
    B16(P706A06BoundaryPhase.RECOVERY_DISCARD),

    /**
     * B17, the startup recovery's old-pointer republish. `LedgerStableStorage.kt:799` inside
     * `recoverSwitchJournalAtStartup` (`:780-819`), reached through the public
     * `openStableStorageLedger` resolution. Parks after the full primitive; the journal is still
     * on disk, so run B's own startup re-executes the same recovery - the recovery-of-recovery
     * idempotency the matrix row asserts.
     */
    B17(P706A06BoundaryPhase.STARTUP_RECOVERY),

    /**
     * B18, the startup recovery's journal deletion. `LedgerStableStorage.kt:806-816`. Parks
     * after the host-directory fsync following the journal delete. On Android that fsync is a
     * best-effort no-op (`AndroidLedgerFileSystem.kt:93-98`), which the row's restart face does
     * not depend on: what must not resurrect is the deleted journal, and run B asserts it stays
     * consumed.
     */
    B18(P706A06BoundaryPhase.STARTUP_RECOVERY),

    /**
     * B19, the startup recovery's staged-generation deletion. `LedgerStableStorage.kt:817`
     * calling `deleteGenerationDirectory` (`:710-736`). Parks right after the staged main file
     * delete returns - the half-deleted remnant shape.
     */
    B19(P706A06BoundaryPhase.STARTUP_RECOVERY),
}

/** How run B must converge after the kill (spec section 3.4's per-row "new device leg" column). */
internal enum class P706A06MarkerConvergence {
    /** The next real startup opens the surviving generation directly through the pointer. */
    OPENS_OLD_GENERATION,

    /** The committed shape: the journal is gone and the pointer names the new generation. */
    OPENS_NEW_GENERATION,

    /** Startup fails closed POINTER_MISSING; the user-confirmed adoption then converges. */
    POINTER_MISSING_THEN_ADOPT,

    /** B14: fail-closed, the discard branch is typed-unavailable, the adoption converges. */
    POINTER_MISSING_THEN_ADOPT_FRESH,

    /** B16: fail-closed, the user-confirmed discard-and-re-upgrade converges from the legacy set. */
    POINTER_MISSING_THEN_DISCARD_RE_UPGRADE,
}

/** The marker record exchanged between run A and run B inside the stable redirected root. */
internal class P706A06BoundaryMarker(
    val boundary: P706A06Boundary,
    val convergence: P706A06MarkerConvergence,
    /** The boundary-marker row payload run B must read back, or null for the fresh-install seed. */
    val authoritativePayload: String?,
    /** The legacy original's payload row, or null when the fixture has no legacy file. */
    val legacyPayload: String?,
    /** The legacy original's SHA-256 at marker time, or null when absent/not applicable. */
    val legacySha256: String?,
    /** The authoritative generation main file's SHA-256 at marker time (identity checkpoint). */
    val authoritativeSha256: String,
)

/**
 * The marker protocol (spec section 3.3): run A writes "target boundary number + fixture
 * fingerprint" to the marker file inside the test-owned redirected root after the flow has
 * parked and BEFORE the kill; run B reads it back strictly. A missing, corrupt or foreign marker
 * must fail the verify leg explicitly - never a silent pass.
 */
internal object P706A06BoundaryMarkerProtocol {
    const val FILE_NAME = "p706a06-boundary-marker"

    private const val HEADER = "p706a06-boundary-marker-v1"

    private const val ABSENT_FIELD = "-"

    /** Writes the marker; called by run A after [P706A06BoundaryHarness.awaitParked] succeeded. */
    fun write(
        root: File,
        marker: P706A06BoundaryMarker,
    ) {
        val lines =
            listOf(
                HEADER,
                "boundary=${marker.boundary.name}",
                "convergence=${marker.convergence.name}",
                "authoritativePayload=${marker.authoritativePayload ?: ABSENT_FIELD}",
                "legacyPayload=${marker.legacyPayload ?: ABSENT_FIELD}",
                "legacySha256=${marker.legacySha256 ?: ABSENT_FIELD}",
                "authoritativeSha256=${marker.authoritativeSha256}",
            )
        File(root, FILE_NAME).writeText(lines.joinToString(separator = "\n", postfix = "\n"))
    }

    /**
     * Reads the marker strictly for the verify method that expects [expected]: a missing file, a
     * wrong header, an unparsable field, an unknown boundary id or a boundary that is not the
     * expected one (a foreign marker from another pair) each throw [AssertionError] with the
     * precise reason.
     */
    fun readStrict(
        root: File,
        expected: P706A06Boundary,
    ): P706A06BoundaryMarker {
        val file = File(root, FILE_NAME)
        assertTrue("the marker file must exist (run the paired kill method first): ${file.path}", file.exists())
        val lines = file.readText().trim().lines()
        assertTrue("the marker must carry its header: ${lines.firstOrNull()}", lines.firstOrNull() == HEADER)
        val fields = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            val index = line.indexOf('=')
            assertTrue("corrupt marker line (expected key=value): $line", index > 0)
            fields[line.substring(0, index)] = line.substring(index + 1)
        }
        val required = setOf("boundary", "convergence", "authoritativePayload", "legacyPayload", "legacySha256", "authoritativeSha256")
        val missing = required - fields.keys
        assertTrue("the marker must carry every required field; missing: $missing", missing.isEmpty())
        val boundary = boundaryOf(fields.getValue("boundary"))
        if (boundary == null) {
            throw AssertionError("unknown boundary id in the marker: ${fields.getValue("boundary")}")
        }
        assertTrue(
            "foreign marker: the file names boundary ${boundary.name} but this verify method expects ${expected.name}",
            boundary == expected,
        )
        val convergence = convergenceOf(fields.getValue("convergence"))
        if (convergence == null) {
            throw AssertionError("unknown convergence in the marker: ${fields.getValue("convergence")}")
        }
        return P706A06BoundaryMarker(
            boundary = boundary,
            convergence = convergence,
            authoritativePayload = fields.getValue("authoritativePayload").takeIf { it != ABSENT_FIELD },
            legacyPayload = fields.getValue("legacyPayload").takeIf { it != ABSENT_FIELD },
            legacySha256 = fields.getValue("legacySha256").takeIf { it != ABSENT_FIELD },
            authoritativeSha256 = fields.getValue("authoritativeSha256"),
        )
    }

    /** The boundary enum value with exactly [name], or null (a strict, loop-based lookup). */
    private fun boundaryOf(name: String): P706A06Boundary? {
        for (candidate in P706A06Boundary.entries) {
            if (candidate.name == name) {
                return candidate
            }
        }
        return null
    }

    /** The convergence enum value with exactly [name], or null (a strict, loop-based lookup). */
    private fun convergenceOf(name: String): P706A06MarkerConvergence? {
        for (candidate in P706A06MarkerConvergence.entries) {
            if (candidate.name == name) {
                return candidate
            }
        }
        return null
    }
}

/** One observed [LedgerFileSystem] call; [tag] carries the decoded journal/pointer content. */
private class BoundaryCall(
    val method: String,
    val target: String,
    val tag: String? = null,
) {
    val isMutating: Boolean
        get() = method == METHOD_WRITE_ATOMIC || method == METHOD_COPY || method == METHOD_DELETE || method == METHOD_CREATE_DIRECTORIES
}

/** The deterministic escape injected for B9 (an `Error`, so the typed catches rethrow it). */
private class P706A06InjectedEscapedError : Error("p706a06 injected escape at the prepared-journal fsync")

/** Raised on the flow thread only if a park outlives the whole protocol (the run already failed). */
private class P706A06BoundaryParkTimeoutError(
    boundary: P706A06Boundary,
) : Error("p706a06 park at ${boundary.name} timed out without the process kill")

private const val METHOD_COPY = "copy"
private const val METHOD_CREATE_DIRECTORIES = "createDirectories"
private const val METHOD_DELETE = "delete"
private const val METHOD_FSYNC_DIRECTORY = "fsyncDirectory"
private const val METHOD_FSYNC_FILE = "fsyncFile"
private const val METHOD_READ_PREFIX = "readPrefix"
private const val METHOD_WRITE_ATOMIC = "writeAtomic"

private const val TAG_JOURNAL_PREPARED = "journal:prepared"
private const val TAG_JOURNAL_SWITCHED = "journal:switched"
private const val TAG_POINTER_GEN_1 = "pointer:gen-1"
private const val TAG_POINTER_GEN_2 = "pointer:gen-2"
private const val TAG_INJECTED_ERROR = "injected:escaped-error"

/** The confirm-chain fixtures fix the old generation at 1 and the staged new generation at 2. */
private const val CONFIRM_OLD_GENERATION = 1

private const val CONFIRM_NEW_GENERATION = 2

/** The legacy-upgrade and adoption fixtures stage/adopt generation 1. */
private const val FIRST_GENERATION = 1

/** The injected error for B9; also used to tag the synthetic history event. */
private const val B9_ESCAPE_BOUNDARY_NAME = "B9"

/**
 * The test-side decorator around the REAL [AndroidLedgerFileSystem]: every call delegates to the
 * platform implementation, is recorded, and when the armed boundary's rule matches, the flow
 * thread parks (a latch the protocol never releases - the process kill is the release). Parking
 * happens either after the matched call returned (the "boundary N durable, N+1 not started"
 * window of spec section 3.3) or, for the two park-before boundaries (B11/B14), before the
 * pointer's atomic write is delegated.
 */
internal class P706A06BoundaryHarness(
    hostDirectory: String,
    legacyMainFile: String,
) : LedgerFileSystem {
    private val real = AndroidLedgerFileSystem()

    /** The platform legacy path this harness was built over (the B13 rule matches on it). */
    val legacyMainFilePath: String = legacyMainFile

    /** The layout over this decorator; every path the rules match on flows through it. */
    val layout: LedgerStorageLayout = ledgerStorageLayout(this, hostDirectory)

    private val lock = Any()
    private val history = mutableListOf<BoundaryCall>()
    private var armedBoundary: P706A06Boundary? = null
    private var escapeInjected = false
    private val parkedSignal = CountDownLatch(1)
    private val parkRelease = CountDownLatch(1)

    /** Arms exactly one boundary; call before launching the driven flow thread. */
    fun arm(boundary: P706A06Boundary) {
        synchronized(lock) { armedBoundary = boundary }
    }

    /** Blocks the driving (main) thread until the flow thread has parked at the armed boundary. */
    fun awaitParked(timeoutSeconds: Long): Boolean = parkedSignal.await(timeoutSeconds, TimeUnit.SECONDS)

    /** A compact forensic trace of every decorated call (for failure messages only). */
    fun historyLines(): List<String> =
        synchronized(lock) {
            history.map { call -> "${call.method} ${call.target}${call.tag?.let { " [$it]" } ?: ""}" }
        }

    private fun parkAt(
        boundary: P706A06Boundary,
        call: BoundaryCall,
    ) {
        android.util.Log.i(P706A06_LOG_TAG, "parked at ${boundary.name} on ${call.method} ${call.target}")
        parkedSignal.countDown()
        if (!parkRelease.await(PARK_RELEASE_WAIT_SECONDS, TimeUnit.SECONDS)) {
            throw P706A06BoundaryParkTimeoutError(boundary)
        }
    }

    /**
     * Records [call] and decides the before-delegate actions: the B9 one-shot escape injection,
     * or a park-before match (B11/B14) which must NOT delegate the write it parked on. Returns
     * true when the caller must return without delegating.
     */
    private fun interceptBeforeDelegate(call: BoundaryCall): Boolean {
        var parkBoundary: P706A06Boundary? = null
        synchronized(lock) {
            history += call
            val armed = armedBoundary
            if (
                armed?.name == B9_ESCAPE_BOUNDARY_NAME &&
                !escapeInjected &&
                call.method == METHOD_FSYNC_FILE &&
                call.target == layout.switchJournalFile &&
                lastTagToLocked(layout.switchJournalFile) == TAG_JOURNAL_PREPARED
            ) {
                history += BoundaryCall(METHOD_FSYNC_FILE, call.target, tag = TAG_INJECTED_ERROR)
                escapeInjected = true
                throw P706A06InjectedEscapedError()
            }
            if (armed != null && armed.parkBeforeDelegate && ruleMatchesLocked(armed, call)) {
                parkBoundary = armed
            }
        }
        val boundary = parkBoundary ?: return false
        parkAt(boundary, call)
        return true
    }

    /** Records the after-delegate park decision for the park-after boundaries. */
    private fun interceptAfterDelegate(call: BoundaryCall) {
        val armed = synchronized(lock) { armedBoundary }
        if (armed == null || armed.parkBeforeDelegate) {
            return
        }
        val match = synchronized(lock) { ruleMatchesLocked(armed, call) }
        if (match) {
            parkAt(armed, call)
        }
    }

    /**
     * The B1-B19 discrimination rules (the registry the enum KDocs describe). Every rule keys on
     * the call plus the rolling history since arming, never on a bare primitive occurrence.
     */
    private fun ruleMatchesLocked(
        boundary: P706A06Boundary,
        call: BoundaryCall,
    ): Boolean {
        val hostDirectory = layout.hostDirectory
        val pointerFile = layout.activePointerFile
        val journalFile = layout.switchJournalFile
        val newMainFile = mainFileOfLocked(CONFIRM_NEW_GENERATION)
        val hasPointerWrite = history.any { it.method == METHOD_WRITE_ATOMIC && it.target == pointerFile }
        val lastMutating = history.lastOrNull { it.isMutating }
        return when (boundary) {
            P706A06Boundary.B1 ->
                call.method == METHOD_DELETE &&
                    call.target == newMainFile &&
                    !hasPointerWrite
            P706A06Boundary.B2 ->
                call.method == METHOD_COPY &&
                    call.target == newMainFile
            P706A06Boundary.B3 ->
                call.method == METHOD_FSYNC_DIRECTORY &&
                    call.target == layout.generationDirectory(CONFIRM_NEW_GENERATION)
            P706A06Boundary.B4 ->
                call.method == METHOD_FSYNC_DIRECTORY &&
                    call.target == hostDirectory &&
                    lastTagToLocked(journalFile) == TAG_JOURNAL_PREPARED &&
                    !hasPointerWrite
            P706A06Boundary.B5 ->
                call.method == METHOD_FSYNC_DIRECTORY &&
                    call.target == hostDirectory &&
                    lastTagToLocked(pointerFile) == TAG_POINTER_GEN_2
            P706A06Boundary.B6 ->
                call.method == METHOD_FSYNC_DIRECTORY &&
                    call.target == hostDirectory &&
                    lastTagToLocked(journalFile) == TAG_JOURNAL_SWITCHED
            P706A06Boundary.B7 ->
                call.method == METHOD_READ_PREFIX &&
                    call.target == newMainFile &&
                    lastTagToLocked(journalFile) == TAG_JOURNAL_SWITCHED
            P706A06Boundary.B8 ->
                call.method == METHOD_FSYNC_DIRECTORY &&
                    call.target == hostDirectory &&
                    lastMutating?.method == METHOD_DELETE &&
                    lastMutating.target == journalFile
            P706A06Boundary.B9 ->
                call.method == METHOD_DELETE &&
                    call.target == newMainFile &&
                    history.any { it.tag == TAG_INJECTED_ERROR } &&
                    history.any { it.method == METHOD_WRITE_ATOMIC && it.tag == TAG_POINTER_GEN_1 } &&
                    history.any { it.method == METHOD_DELETE && it.target == journalFile }
            P706A06Boundary.B10 ->
                call.method == METHOD_FSYNC_DIRECTORY &&
                    call.target == layout.generationDirectory(FIRST_GENERATION)
            P706A06Boundary.B11 ->
                call.method == METHOD_WRITE_ATOMIC &&
                    call.target == pointerFile &&
                    history.any { it.method == METHOD_COPY && it.target == mainFileOfLocked(FIRST_GENERATION) }
            P706A06Boundary.B12 ->
                call.method == METHOD_FSYNC_DIRECTORY &&
                    call.target == hostDirectory &&
                    lastTagToLocked(pointerFile) == TAG_POINTER_GEN_1 &&
                    history.any { it.method == METHOD_COPY && it.target == mainFileOfLocked(FIRST_GENERATION) }
            P706A06Boundary.B13 ->
                call.method == METHOD_DELETE &&
                    call.target == legacyMainFilePath
            P706A06Boundary.B14 ->
                call.method == METHOD_WRITE_ATOMIC &&
                    call.target == pointerFile &&
                    history.none { it.method == METHOD_COPY } &&
                    history.none { it.method == METHOD_WRITE_ATOMIC && it.target == journalFile }
            P706A06Boundary.B15 ->
                call.method == METHOD_FSYNC_DIRECTORY &&
                    call.target == hostDirectory &&
                    lastTagToLocked(pointerFile) == TAG_POINTER_GEN_1 &&
                    history.none { it.method == METHOD_COPY }
            P706A06Boundary.B16 ->
                call.method == METHOD_DELETE &&
                    call.target == mainFileOfLocked(FIRST_GENERATION)
            P706A06Boundary.B17 ->
                call.method == METHOD_FSYNC_DIRECTORY &&
                    call.target == hostDirectory &&
                    lastTagToLocked(pointerFile) == TAG_POINTER_GEN_1 &&
                    history.any { it.method == "readBytes" && it.target == journalFile }
            P706A06Boundary.B18 ->
                call.method == METHOD_FSYNC_DIRECTORY &&
                    call.target == hostDirectory &&
                    lastMutating?.method == METHOD_DELETE &&
                    lastMutating.target == journalFile
            P706A06Boundary.B19 ->
                call.method == METHOD_DELETE &&
                    call.target == newMainFile
        }
    }

    /** The tag of the most recent atomic write to [path] (the decoded journal/pointer content). */
    private fun lastTagToLocked(path: String): String? {
        val lastWrite = history.lastOrNull { it.method == METHOD_WRITE_ATOMIC && it.target == path }
        return lastWrite?.tag
    }

    private fun mainFileOfLocked(generation: Int): String = layout.mainFile(layout.generationDirectory(generation))

    private fun contentTagFor(
        path: String,
        bytes: ByteArray,
    ): String? {
        if (path == layout.switchJournalFile) {
            val text = bytes.decodeToString()
            return when {
                text.contains("stage=prepared") -> TAG_JOURNAL_PREPARED
                text.contains("stage=switched") -> TAG_JOURNAL_SWITCHED
                else -> "journal:other"
            }
        }
        if (path == layout.activePointerFile) {
            return "pointer:" + bytes.decodeToString().trim()
        }
        return null
    }

    // ------------------------------------------------------------------ the LedgerFileSystem surface

    override fun join(
        parent: String,
        child: String,
    ): String = real.join(parent, child)

    override fun exists(path: String): Boolean {
        synchronized(lock) { history += BoundaryCall("exists", path) }
        return real.exists(path)
    }

    override fun isDirectory(path: String): Boolean {
        synchronized(lock) { history += BoundaryCall("isDirectory", path) }
        return real.isDirectory(path)
    }

    override fun length(path: String): Long {
        synchronized(lock) { history += BoundaryCall("length", path) }
        return real.length(path)
    }

    override fun readBytes(path: String): ByteArray {
        synchronized(lock) { history += BoundaryCall("readBytes", path) }
        return real.readBytes(path)
    }

    override fun readPrefix(
        path: String,
        length: Int,
    ): ByteArray {
        val call = BoundaryCall(METHOD_READ_PREFIX, path)
        if (interceptBeforeDelegate(call)) {
            throw IllegalStateException("a read-only park-before must not exist")
        }
        val result = real.readPrefix(path, length)
        interceptAfterDelegate(call)
        return result
    }

    override fun writeAtomic(
        path: String,
        bytes: ByteArray,
    ) {
        val call = BoundaryCall(METHOD_WRITE_ATOMIC, path, tag = contentTagFor(path, bytes))
        if (interceptBeforeDelegate(call)) {
            return
        }
        real.writeAtomic(path, bytes)
        interceptAfterDelegate(call)
    }

    override fun copy(
        source: String,
        target: String,
    ) {
        val call = BoundaryCall(METHOD_COPY, target)
        if (interceptBeforeDelegate(call)) {
            throw IllegalStateException("a copy must never be parked before delegating")
        }
        real.copy(source, target)
        interceptAfterDelegate(call)
    }

    override fun delete(path: String) {
        val call = BoundaryCall(METHOD_DELETE, path)
        if (interceptBeforeDelegate(call)) {
            throw IllegalStateException("a delete must never be parked before delegating")
        }
        real.delete(path)
        interceptAfterDelegate(call)
    }

    override fun createDirectories(path: String) {
        val call = BoundaryCall(METHOD_CREATE_DIRECTORIES, path)
        if (interceptBeforeDelegate(call)) {
            throw IllegalStateException("a createDirectories must never be parked before delegating")
        }
        real.createDirectories(path)
        interceptAfterDelegate(call)
    }

    override fun fsyncFile(path: String) {
        val call = BoundaryCall(METHOD_FSYNC_FILE, path)
        if (interceptBeforeDelegate(call)) {
            throw IllegalStateException("a fsyncFile must never be parked before delegating")
        }
        real.fsyncFile(path)
        interceptAfterDelegate(call)
    }

    override fun fsyncDirectory(path: String) {
        val call = BoundaryCall(METHOD_FSYNC_DIRECTORY, path)
        if (interceptBeforeDelegate(call)) {
            throw IllegalStateException("a fsyncDirectory must never be parked before delegating")
        }
        real.fsyncDirectory(path)
        interceptAfterDelegate(call)
    }

    override fun usableSpace(path: String): Long? {
        synchronized(lock) { history += BoundaryCall("usableSpace", path) }
        return real.usableSpace(path)
    }

    override fun openRead(path: String): LedgerReadStream {
        synchronized(lock) { history += BoundaryCall("openRead", path) }
        return real.openRead(path)
    }

    override fun openWrite(path: String): LedgerWriteStream {
        synchronized(lock) { history += BoundaryCall("openWrite", path) }
        return real.openWrite(path)
    }

    override fun listDirectory(path: String): List<String> {
        synchronized(lock) { history += BoundaryCall("listDirectory", path) }
        return real.listDirectory(path)
    }

    private companion object {
        /** The parked flow thread waits for the process kill; the bound only outlives failed runs. */
        const val PARK_RELEASE_WAIT_SECONDS = 3_600L
    }
}

/** The shared logcat tag for both legs of the protocol. */
internal const val P706A06_LOG_TAG = "P706A06Boundary"

/** The fixture payload rows (anonymous synthetic strings; no personal data). */
internal object P706A06BoundaryFixturePayloads {
    const val CONFIRM_OLD = "p706a06-confirm-old-generation"

    const val CONFIRM_NEW = "p706a06-confirm-new-generation"

    const val LEGACY_UPGRADE = "p706a06-legacy-upgrade-original"

    const val ADOPT_CANDIDATE = "p706a06-adopt-candidate"

    const val RECOVERY_OLD = "p706a06-recovery-old-generation"

    const val RECOVERY_NEW = "p706a06-recovery-new-generation"

    /** The stable redirected root directory name inside the target's cache dir. */
    const val STABLE_ROOT_DIRECTORY_NAME = "p706a06-boundary-root"

    const val LEGACY_DATABASE_NAME = "ledger.db"

    /** The deterministic preflight token, so the staging file names are known to the suite. */
    const val CONFIRM_TOKEN = "p706a06-boundary-token"

    /** The container password the fixture's container writer and the real preflight share. */
    const val CONFIRM_PASSWORD = "p706a06-boundary-confirm-password"
}

/** The authoritative read-back of one SQLite main file: its version and its marker row, if any. */
internal class P706A06BoundaryFacts(
    val userVersion: Long,
    val payload: String?,
)

/**
 * The four fixture families of spec section 3.3's boundary-cases paragraph (plus the
 * fresh-install shape for B14), seeded with REAL product ledgers through the product factory
 * (`createAndroidLedgerDatabase` + the product catalog seed, the
 * `AndroidRestoreConfirmSwitchInstrumentedTest` shape) and one synthetic `boundary_marker` row
 * per fixture so run B's authoritative read-back compares facts, not bytes of opened databases.
 */
internal class P706A06BoundaryFixtures(
    private val context: Context,
    private val fileSystem: LedgerFileSystem,
    private val layout: LedgerStorageLayout,
) {
    /**
     * The confirm-switch fixture: an active `gen-1` product ledger (payload CONFIRM_OLD) behind a
     * `gen-1` pointer, a LEFTOVER staged `gen-2` (main file plus a `-wal` sidecar) standing in
     * for a previous aborted confirm - the on-disk precondition without which B1's
     * delete-then-stage pre-delete would delete nothing - and a second real product ledger
     * (payload CONFIRM_NEW) wrapped in a real authenticated container, returned for the real
     * preflight.
     */
    fun seedConfirmSwitchFixture(): ByteArray {
        fileSystem.createDirectories(layout.generationDirectory(CONFIRM_OLD_GENERATION))
        seedProductLedger(layout.mainFile(layout.generationDirectory(CONFIRM_OLD_GENERATION)), P706A06BoundaryFixturePayloads.CONFIRM_OLD)
        fileSystem.writeAtomic(layout.activePointerFile, "gen-1".encodeToByteArray())
        fileSystem.createDirectories(layout.generationDirectory(CONFIRM_NEW_GENERATION))
        fileSystem.writeAtomic(
            layout.mainFile(layout.generationDirectory(CONFIRM_NEW_GENERATION)),
            "p706a06-leftover-staged-bytes-0123456789".toByteArray(),
        )
        fileSystem.writeAtomic(
            layout.sidecarFile(layout.generationDirectory(CONFIRM_NEW_GENERATION), "-wal"),
            ByteArray(8),
        )
        val payloadFile = File(layout.hostDirectory, "p706a06-confirm-payload.db")
        seedProductLedger(payloadFile.absolutePath, P706A06BoundaryFixturePayloads.CONFIRM_NEW)
        return containerAround(payloadFile)
    }

    /**
     * The legacy-upgrade fixture: the user's real legacy product ledger at the platform legacy
     * path and NO generation directory, so the startup resolves `UpgradeLegacy` (B10-B13).
     */
    fun seedLegacyUpgradeFixture() {
        seedProductLedger(legacyMainFilePath(), P706A06BoundaryFixturePayloads.LEGACY_UPGRADE)
    }

    /**
     * The fresh-install fixture: an empty host - no generations directory, no pointer, no legacy
     * file - so the startup resolves `FreshInstall` and the driver creates `gen-1` itself (B14).
     */
    fun seedFreshInstallFixture() = Unit

    /**
     * The POINTER_MISSING adoption fixture: a complete-and-current `gen-1` product ledger with NO
     * pointer and no legacy original (B15), the shape the user-confirmed adoption publishes.
     */
    fun seedAdoptCandidateFixture() {
        fileSystem.createDirectories(layout.generationDirectory(FIRST_GENERATION))
        seedProductLedger(layout.mainFile(layout.generationDirectory(FIRST_GENERATION)), P706A06BoundaryFixturePayloads.ADOPT_CANDIDATE)
    }

    /**
     * The POINTER_MISSING discard fixture: an UNVERIFIABLE candidate `gen-1` (a valid SQLite
     * header followed by garbage, the `AndroidRestoreRecoveryInstrumentedTest` corrupt-candidate
     * shape) with NO pointer, plus the still-present legacy original the discard branch needs
     * (B16).
     */
    fun seedDiscardCandidateFixture() {
        fileSystem.createDirectories(layout.generationDirectory(FIRST_GENERATION))
        val corruptBytes = "SQLite format 3\u0000".encodeToByteArray() + ByteArray(64) { 0x7F }
        fileSystem.writeAtomic(layout.mainFile(layout.generationDirectory(FIRST_GENERATION)), corruptBytes)
        seedProductLedger(legacyMainFilePath(), P706A06BoundaryFixturePayloads.LEGACY_UPGRADE)
    }

    /**
     * The preset switched-journal fixture (the `AndroidRestoreRecoveryInstrumentedTest` face-2
     * shape): a real old `gen-1` and staged new `gen-2`, the pointer ALREADY naming `gen-2`, and
     * the frozen switched journal literal - the restart-rollback input B17-B19 park inside of.
     */
    fun seedSwitchedJournalFixture() {
        fileSystem.createDirectories(layout.generationDirectory(CONFIRM_OLD_GENERATION))
        seedProductLedger(layout.mainFile(layout.generationDirectory(CONFIRM_OLD_GENERATION)), P706A06BoundaryFixturePayloads.RECOVERY_OLD)
        fileSystem.createDirectories(layout.generationDirectory(CONFIRM_NEW_GENERATION))
        seedProductLedger(layout.mainFile(layout.generationDirectory(CONFIRM_NEW_GENERATION)), P706A06BoundaryFixturePayloads.RECOVERY_NEW)
        fileSystem.writeAtomic(layout.activePointerFile, "gen-2".encodeToByteArray())
        fileSystem.writeAtomic(
            layout.switchJournalFile,
            "unified-ledger switch journal v1\nstage=switched\nold=gen-1\nnew=gen-2".encodeToByteArray(),
        )
    }

    /** The platform legacy path over the redirected host (the file may or may not exist). */
    fun legacyMainFilePath(): String = context.getDatabasePath(P706A06BoundaryFixturePayloads.LEGACY_DATABASE_NAME).absolutePath

    /** The main file path of one generation directory over the fixture layout. */
    fun generationMainFile(generation: Int): String = layout.mainFile(layout.generationDirectory(generation))

    /**
     * Creates a REAL product ledger at [mainFilePath] through the product factory with the
     * product catalog seed, then adds one synthetic `boundary_marker` row through a framework
     * open; any transient rollback journal the framework open leaves is deleted so the fixture
     * carries exactly its main file.
     */
    fun seedProductLedger(
        mainFilePath: String,
        payload: String,
    ) {
        File(mainFilePath).parentFile?.mkdirs()
        createAndroidLedgerDatabase(context, mainFilePath).use { handle ->
            handle.catalogStore.bootstrap(LedgerId(RESTORE_TARGET_LEDGER_ID), defaultCatalogSeed())
        }
        SQLiteDatabase.openDatabase(mainFilePath, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.execSQL("CREATE TABLE boundary_marker (payload TEXT)")
            database.execSQL("INSERT INTO boundary_marker VALUES ('$payload')")
        }
        File("$mainFilePath-journal").delete()
        assertFileIsolatedFromProduction(File(mainFilePath))
    }

    /** Reads the version and marker row of [mainFilePath] through a read-only framework open. */
    fun readFacts(mainFilePath: String): P706A06BoundaryFacts =
        SQLiteDatabase.openDatabase(mainFilePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            val version =
                database.rawQuery("PRAGMA user_version", null).use { cursor ->
                    check(cursor.moveToFirst()) { "PRAGMA user_version must answer" }
                    cursor.getLong(0)
                }
            val payload =
                try {
                    database.rawQuery("SELECT payload FROM boundary_marker", null).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                } catch (failure: Throwable) {
                    null
                }
            P706A06BoundaryFacts(version, payload)
        }

    /** The SHA-256 hex of a file's bytes (fixture fingerprints only; small fixture files). */
    fun sha256Of(file: File): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    /** One real authenticated container around the payload file (the product writer + JCE). */
    private fun containerAround(payloadFile: File): ByteArray {
        val out = ByteArrayOutputStream()
        writeBackupContainer(
            plaintext = filePlaintextSource(payloadFile),
            sink = BackupContainerSink { bytes, offset, length -> out.write(bytes, offset, length) },
            password = P706A06BoundaryFixturePayloads.CONFIRM_PASSWORD,
            schemaVersion = currentSupportedSchemaVersion(),
            plaintextLength = payloadFile.length(),
            crypto = JvmBackupCryptoPrimitives(),
        )
        return out.toByteArray()
    }

    /** A re-openable file-backed plaintext source (the container writer reads it twice). */
    private fun filePlaintextSource(file: File): BackupPlaintextSource =
        BackupPlaintextSource {
            val stream = FileInputStream(file)
            object : BackupPlaintextReader {
                override fun read(buffer: ByteArray): Int = stream.read(buffer)

                override fun close() {
                    stream.close()
                }
            }
        }
}

/**
 * The stable redirected root both legs resolve under: one deterministic directory inside the
 * target's cache dir, NOT a per-run fresh directory - the two instrumentation starts of one
 * boundary pair must see the same disk (spec section 3.3's two-phase protocol).
 */
internal fun p706A06StableRoot(): File {
    val target = InstrumentationRegistry.getInstrumentation().targetContext
    return File(target.cacheDir, P706A06BoundaryFixturePayloads.STABLE_ROOT_DIRECTORY_NAME)
}

/**
 * The redirected context (the `AndroidRestoreConfirmSwitchInstrumentedTest` MUST FIX A shape):
 * RELATIVE database names resolve under the stable root, ABSOLUTE names pass through unchanged
 * (the framework's own absolute branch, used by the generation driver opens), and the guard
 * asserts the redirection is actually in effect so the suite can never touch the production
 * ledger.
 */
internal fun p706A06RedirectedContext(root: File): Context {
    val target = InstrumentationRegistry.getInstrumentation().targetContext
    val wrapper =
        object : ContextWrapper(target) {
            override fun getDatabasePath(name: String): File = if (File(name).isAbsolute) File(name) else File(root, name)
        }
    val effective = wrapper.getDatabasePath(P706A06BoundaryFixturePayloads.LEGACY_DATABASE_NAME).absolutePath
    val production = target.getDatabasePath(P706A06BoundaryFixturePayloads.LEGACY_DATABASE_NAME).absolutePath
    assertNotEquals("the suite must not resolve to the production database path", production, effective)
    assertTrue("the effective database path must be inside the stable root: $effective", effective.startsWith(root.absolutePath))
    return wrapper
}

/**
 * The hard isolation guard (the driver-suite pattern): every file this suite creates or opens
 * must live under the app-private cache dir and must NOT resolve into the production
 * `databases/` tree.
 */
internal fun assertFileIsolatedFromProduction(file: File) {
    val target = InstrumentationRegistry.getInstrumentation().targetContext
    val productionDatabases = target.getDatabasePath(P706A06BoundaryFixturePayloads.LEGACY_DATABASE_NAME).parentFile?.absolutePath
    assertTrue(
        "the suite file must be under the cache dir: ${file.absolutePath}",
        file.absolutePath.startsWith(target.cacheDir.absolutePath),
    )
    assertTrue(
        "the suite file must not resolve into the production databases directory: ${file.absolutePath}",
        productionDatabases == null || !file.absolutePath.startsWith(productionDatabases),
    )
}
