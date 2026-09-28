package com.unifiedledger.ui

import com.unifiedledger.application.backup.BACKUP_DISK_HEADROOM_BYTES
import com.unifiedledger.application.backup.BACKUP_STREAM_CHUNK_BYTES
import com.unifiedledger.application.backup.BackupCryptoPrimitives
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/*
 * P7-06 06.D (D-182; spec `docs/specs/2026-09-26-p7-06-restore-confirm-switch-design.md`
 * sections 3, 4 and 5.1): the shared restore CONFIRM & SWITCH use case. It consumes the opaque
 * 06.C preflight token (D-179 section 7.1/7.5) and executes the frozen nine-step confirm
 * sequence: the four D-179 section 7.5 revalidations, the confirm-time peak disk precheck
 * (spec section 3.2), `quiesce` WITHOUT holding a lease, the post-quiesce generation recheck,
 * `closeActiveGraph`, the gen-(current+1) assembly with the delete-then-stage precondition and
 * the journal=prepared write, the atomic pointer publish through the frozen
 * [publishActivePointer] primitive plus journal=switched, the `reopen(GenerationSelection.Explicit)`
 * of the just-published generation with the authoritative read-back (the explicit route, so this
 * flow's own live `switched` journal is NOT consumed by the startup gate — see
 * [openExplicitGeneration]), and the journal removal (committed) with the generation-guarded
 * result delivery (spec section 3.9).
 *
 * CORE INVARIANTS (spec section 3, plan P706-A03/A05):
 * - any revalidation failure is a typed STALE rejection with ZERO switch;
 * - a blocked quiesce/close is a typed POSTPONED rejection with ZERO switch;
 * - every failure BEFORE the pointer publish leaves the pointer bytes untouched;
 * - every failure AFTER the pointer publish rolls back to the old pointer (spec section 3.8)
 *   and a rollback that also fails lands in the fail-closed [BackupRestoreSwitchResult.RecoveryRequired];
 * - every abort AFTER `quiesce` restores the runtime through the frozen restoration program
 *   (spec section 3.10): re-drain by re-entering `quiesce` (it joins the existing drain) and
 *   `reopen(ActivePointer)`, never leaving the owner in `Quiescing` silently — the one honest
 *   exception is a drain that cannot converge within the budget (a stuck lease), reported as
 *   [BackupRestoreRuntimeOutcome.NotRestored];
 * - the disk switch results never carry a path, a password or a `P706_*` container code
 *   (spec section 3.7): they are a separate typed result space;
 * - the use case is SINGLE-FLIGHT: a second concurrent confirm — including one bound to a
 *   different token — is typed-postponed before touching anything, so two flows can never
 *   interleave their staging into the same `gen-(current+1)` directory;
 * - an ESCAPE from the guarded post-quiesce region (caller cancellation, an Error from the
 *   fail-loud ports, or an escaped RuntimeException — F-7 broadened the catch to every Throwable)
 *   rethrows honestly but never wedges the runtime: the applicable best-effort
 *   restoration (spec section 3.10) or rollback (spec section 3.8) runs under `NonCancellable`
 *   first, so the owner always ends Ready, StartupError, or — only for a drain that cannot
 *   converge — Quiescing with the escape reported.
 *
 * The old generation is NEVER deleted by this flow (it is the rollback anchor; spec section 5.2
 * keeps the post-success retention policy OPEN). The journal machine itself (encoding, the
 * startup gate recovery) lives in [LedgerStableStorage.kt]; this file is its only writer.
 *
 * Like the preflight, every file operation goes through the injected [LedgerFileSystem] and the
 * whole flow must be dispatched on a background thread (container-format spec section 4.8);
 * `quiesce` is a suspend call, so [confirm] is one too.
 */

/** Why a confirmation was rejected as stale with zero switch (06.D spec sections 3.1 and 3.4). */
enum class BackupRestoreStaleReason {
    /** A token-bound staging artifact (the plaintext snapshot, or the migrated copy) is missing. */
    StagingArtifactMissing,

    /** A staging artifact exists but could not be read for its digest re-verification. */
    ArtifactUnreadable,

    /** A streaming SHA-256 of a staging artifact does not match the token-bound digest. */
    DigestMismatch,

    /**
     * The token's captured runtime generation is no longer the active one — checked before the
     * quiesce and re-checked after it (spec section 3.4); a cross-session token is always caught
     * here or by [StagingArtifactMissing] (the startup sweep removed its artifacts, spec 5.7).
     */
    GenerationSuperseded,

    /** The token's target ledger identity does not equal the fixed target identity. */
    TargetLedgerMismatch,
}

/** Why the switch was postponed without any write (06.D spec sections 3.3 and 3.5). */
enum class BackupRestorePostponeReason {
    /** `quiesce()` did not drain the in-flight leases within the bounded timeout. */
    QuiesceBlocked,

    /** `closeActiveGraph()` was blocked (transition contention or in-flight leases). */
    CloseBlocked,

    /**
     * A confirm was already in flight on this use-case instance (the single-flight guard, P2-2).
     * The rejected call touched NOTHING — no file, no quiesce, no staging — and may be retried
     * once the running confirm settles. INFO-1: the loser is disturbed nowhere — it acquires no
     * lease, drains nothing, and its `RestoredReady` runtime outcome is trivially true because
     * the runtime owner was never entered; the running confirm's own result carries the real
     * runtime outcome of that flow.
     */
    ConfirmAlreadyRunning,
}

/**
 * Why the confirm aborted BEFORE the pointer publish with zero switch (06.D spec sections 3.2
 * and 3.6). The pointer bytes are untouched and the runtime is restored per section 3.10.
 */
enum class BackupRestoreAbortReason {
    /** The confirm-time peak disk precheck found less space than the frozen formula requires. */
    InsufficientDiskSpace,

    /**
     * The platform could not report a usable-space value: fail-closed by the section 3.2 ruling
     * (unlike the 06.C preflight, which fails open on unknown space — the confirm-time peak is
     * the largest and must never end mid-switch).
     */
    UnknownDiskSpace,

    /** The on-disk active pointer is missing or does not name a generation. */
    ActivePointerUnreadable,

    /** Assembling the new generation directory (or its post-staging consistency gate) failed. */
    StagingFailed,

    /** Writing the `prepared` journal failed; the staged directory is discarded. */
    JournalWriteFailed,
}

/**
 * The honest state of the runtime owner after an abort's restoration program (06.D spec
 * section 3.10). A rejection result always carries one of these so the host never has to guess.
 */
enum class BackupRestoreRuntimeOutcome {
    /** The owner is Ready again on the old graph; business admission is restored. */
    RestoredReady,

    /** The restoration reopen failed closed; the owner is in StartupError (D-176, never retried silently). */
    FailClosed,

    /**
     * The runtime could NOT be restored: the re-drain did not converge within the budget (a
     * stuck in-flight lease — the owner stays `Quiescing`) or transition contention persisted
     * through the single retry. Reported honestly, never papered over.
     */
    NotRestored,
}

/** Why a post-publish failure escalated to the fail-closed [BackupRestoreSwitchResult.RecoveryRequired]. */
enum class BackupRestoreRecoveryCause {
    /** Republishing the old pointer failed; the journal (and possibly the new directory) remain. */
    RollbackPublishFailed,

    /** Removing the journal failed; the old pointer is restored, both generation directories remain. */
    RollbackJournalRemoveFailed,

    /** Reopening the old graph failed; the owner is fail-closed (StartupError). */
    RollbackReopenFailed,
}

/**
 * The typed switch-result space of the confirm flow (06.D spec section 3.7). Deliberately
 * separate from the `P706_*` container rejection codes: those belong to the container format;
 * these describe the switch lifecycle (stale / postponed / aborted / rolled back / recovery /
 * committed). No variant carries a file path, a password or a raw throwable.
 */
sealed interface BackupRestoreSwitchResult {
    /**
     * The owner generation the host's landing hop must bind this result to (spec section 3.9): the
     * POST-reopen generation for [Committed]/[RolledBack], and the POST-restoration generation for
     * the abort results (spec section 3.10: the restoration program's `reopen` ADVANCES the process
     * generation, so the pre-confirm capture is stale by construction). Null when the owner has no
     * active generation — the fail-closed [RecoveryRequired], or a restoration that ended in
     * StartupError: the host lands such a result UNGUARDED, because there is no current generation
     * to compare against and discarding it would strand the surface forever (the F-1 landing bug).
     */
    val landingGeneration: Generation?

    /**
     * The switch committed: the pointer names the new generation, the new graph is open and
     * authoritatively read back, and the journal is removed. [runtimeGeneration] is the owner's
     * active generation AFTER the reopen — the host's landing hop must deliver this result only
     * while [LedgerLeaseScope.isCurrentGeneration] still holds for it (spec section 3.9).
     */
    data class Committed(
        val runtimeGeneration: Generation,
    ) : BackupRestoreSwitchResult {
        override val landingGeneration: Generation get() = runtimeGeneration
    }

    /**
     * A stale rejection (spec section 3.1/3.4): zero switch; [runtime] reports the owner state.
     * [runtimeGeneration] is the owner's active generation when the rejection was produced — the
     * POST-restoration generation for a post-quiesce abort (spec section 3.10), the untouched
     * pre-quiesce generation otherwise. Null when the owner has no active generation.
     */
    data class Stale(
        val reason: BackupRestoreStaleReason,
        val runtime: BackupRestoreRuntimeOutcome,
        val runtimeGeneration: Generation? = null,
    ) : BackupRestoreSwitchResult {
        override val landingGeneration: Generation? get() = runtimeGeneration
    }

    /**
     * A postponed rejection (spec section 3.3/3.5): zero switch; the flow may be retried whole.
     * [runtimeGeneration] carries the post-restoration owner generation exactly as [Stale] does.
     */
    data class Postponed(
        val reason: BackupRestorePostponeReason,
        val runtime: BackupRestoreRuntimeOutcome,
        val runtimeGeneration: Generation? = null,
    ) : BackupRestoreSwitchResult {
        override val landingGeneration: Generation? get() = runtimeGeneration
    }

    /**
     * An abort before the pointer publish (spec section 3.2/3.6): zero switch, the half-built
     * new-generation directory is discarded, and [runtime] reports the restored owner state.
     * [runtimeGeneration] carries the post-restoration owner generation exactly as [Stale] does.
     */
    data class AbortedBeforePublish(
        val reason: BackupRestoreAbortReason,
        val runtime: BackupRestoreRuntimeOutcome,
        val runtimeGeneration: Generation? = null,
    ) : BackupRestoreSwitchResult {
        override val landingGeneration: Generation? get() = runtimeGeneration
    }

    /**
     * A post-publish failure was rolled back (spec section 3.8): the old pointer is restored
     * byte-identical, the journal is removed, the staged new-generation directory (including its
     * sidecars) is deleted, and the old graph is reopened. The data is intact and the whole
     * confirm flow may be retried. [runtimeGeneration] is the reopened old graph's generation,
     * for the same landing guard as [Committed].
     */
    data class RolledBack(
        val runtimeGeneration: Generation,
    ) : BackupRestoreSwitchResult {
        override val landingGeneration: Generation get() = runtimeGeneration
    }

    /**
     * The rollback itself failed: the fail-closed recovery state (spec section 3.8). The old and
     * new generations are preserved as found, no loop re-initializes anything (the D-176
     * silent-empty-database prohibition), and the persistent shape is resolved by the startup
     * journal recovery on the next start (spec section 4.3) or the explicit recovery surfaces.
     * The owner is Closed or StartupError, so there is no active generation to guard against; the
     * host lands this session-terminal result unguarded.
     */
    data class RecoveryRequired(
        val cause: BackupRestoreRecoveryCause,
    ) : BackupRestoreSwitchResult {
        override val landingGeneration: Generation? get() = null
    }
}

/** The number of bounded re-drain attempts the restoration program makes (spec section 8 item 3). */
private const val RESTORATION_RE_DRAIN_ATTEMPTS = 2

/** The default bounded pause between the two restoration reopen attempts under contention. */
private const val RESTORATION_REOPEN_RETRY_PAUSE_MILLIS = 100L

/**
 * The shared confirm & switch use case (06.D spec section 5.1, ruling A). Construct once per
 * composition root; invoke [confirm] on a background thread. Calling [confirm] IS the explicit
 * user confirmation — there is no implicit path — and the use case never holds an operation
 * lease (the quiesce caller contract, [LedgerRuntimeOwner.quiesce]).
 *
 * @param owner the runtime owner; consumed strictly through the frozen D-176 primitives
 *   (`quiesce`, `closeActiveGraph`, `reopen`) plus the lock-free snapshots.
 * @param fileSystem the platform file operations; every disk access goes through it.
 * @param layout the stable-storage layout (staging files, generation directories, pointer, journal).
 * @param crypto the crypto primitives; ONLY the streaming SHA-256 re-verification primitive is used.
 * @param targetLedgerId the fixed target ledger identity the token must bind (container-format
 *   spec section 5.4); the same value the composition root supplies to the preflight request.
 * @param reDrainAttempts how many bounded `quiesce` attempts the restoration program makes
 *   before honestly reporting [BackupRestoreRuntimeOutcome.NotRestored] (spec section 8 item 3
 *   leaves the budget to the implementation batch).
 * @param reopenRetryPauseMillis the bounded pause between the two restoration reopen attempts
 *   under transition contention (spec section 3.10's retry-once discipline). Injectable for the
 *   same reason as the owner's `quiesceTimeoutMillis`: a test needs deterministic margins.
 */
class ConfirmBackupRestoreUseCase(
    private val owner: LedgerRuntimeOwner<*>,
    private val fileSystem: LedgerFileSystem,
    private val layout: LedgerStorageLayout,
    private val crypto: BackupCryptoPrimitives,
    private val targetLedgerId: String,
    private val reDrainAttempts: Int = RESTORATION_RE_DRAIN_ATTEMPTS,
    private val reopenRetryPauseMillis: Long = RESTORATION_REOPEN_RETRY_PAUSE_MILLIS,
) {
    /**
     * The single-flight guard (P2-2): at most one confirm runs per use-case instance. Neither
     * `quiesce` nor `closeActiveGraph` can serialize a second confirm (the second's quiesce
     * joins the drain, its generation recheck passes, and its close succeeds idempotently on an
     * already-closed owner), so without this gate two concurrent confirms — possibly bound to
     * different tokens — would interleave their delete-then-stage work into the SAME
     * `gen-(current+1)` directory. The composition root constructs one instance, so the mutex
     * scopes the guard to exactly the product surface.
     */
    private val singleFlight = Mutex()

    /**
     * Runs the frozen nine-step confirm sequence (06.D spec section 3) for an authenticated
     * preflight token. Never writes the current library before the pointer publish, never
     * deletes the old generation, and never leaves the owner stuck in `Quiescing` silently.
     */
    suspend fun confirm(token: RestorePreflightToken): BackupRestoreSwitchResult {
        // Single-flight before ANY effect: the loser is typed-postponed with zero side effects.
        if (!singleFlight.tryLock()) {
            return BackupRestoreSwitchResult.Postponed(
                BackupRestorePostponeReason.ConfirmAlreadyRunning,
                BackupRestoreRuntimeOutcome.RestoredReady,
            )
        }
        try {
            return confirmGuarded(token)
        } finally {
            singleFlight.unlock()
        }
    }

    private suspend fun confirmGuarded(token: RestorePreflightToken): BackupRestoreSwitchResult {
        val snapshotFile = layout.restoreSnapshotFile(token.handle)
        val migratedFile = layout.restoreMigratedFile(token.handle)
        // Spec section 3.1 item 1: a null migrated digest means the preflight validated the
        // snapshot itself (no migration), so only the snapshot is bound.
        val hasMigration = token.migratedArtifactSha256 != null

        // ---- Step 1: the four D-179 section 7.5 hard revalidations (no lease, no quiesce:
        // the owner is untouched, so the runtime outcome is trivially RestoredReady and the
        // landing generation is the owner's CURRENT generation — unchanged by this step).
        if (!fileSystem.exists(snapshotFile) || (hasMigration && !fileSystem.exists(migratedFile))) {
            return BackupRestoreSwitchResult.Stale(BackupRestoreStaleReason.StagingArtifactMissing, BackupRestoreRuntimeOutcome.RestoredReady, owner.activeGeneration)
        }
        val snapshotDigest = digestOfFile(snapshotFile)
        if (snapshotDigest == null) {
            return BackupRestoreSwitchResult.Stale(BackupRestoreStaleReason.ArtifactUnreadable, BackupRestoreRuntimeOutcome.RestoredReady, owner.activeGeneration)
        }
        if (!snapshotDigest.contentEquals(token.authenticatedArtifactSha256)) {
            return BackupRestoreSwitchResult.Stale(BackupRestoreStaleReason.DigestMismatch, BackupRestoreRuntimeOutcome.RestoredReady, owner.activeGeneration)
        }
        if (hasMigration) {
            val migratedDigest = digestOfFile(migratedFile)
            if (migratedDigest == null) {
                return BackupRestoreSwitchResult.Stale(BackupRestoreStaleReason.ArtifactUnreadable, BackupRestoreRuntimeOutcome.RestoredReady, owner.activeGeneration)
            }
            if (!migratedDigest.contentEquals(token.migratedArtifactSha256)) {
                return BackupRestoreSwitchResult.Stale(BackupRestoreStaleReason.DigestMismatch, BackupRestoreRuntimeOutcome.RestoredReady, owner.activeGeneration)
            }
        }
        if (!isTokenGenerationCurrent(token)) {
            return BackupRestoreSwitchResult.Stale(BackupRestoreStaleReason.GenerationSuperseded, BackupRestoreRuntimeOutcome.RestoredReady, owner.activeGeneration)
        }
        if (token.targetLedgerId != targetLedgerId) {
            return BackupRestoreSwitchResult.Stale(BackupRestoreStaleReason.TargetLedgerMismatch, BackupRestoreRuntimeOutcome.RestoredReady, owner.activeGeneration)
        }

        // ---- Step 2: the confirm-time peak disk precheck (spec section 3.2). The staging
        // container copy is already gone, so the coexisting set is snapshot + migrated copy +
        // new-generation lower bound + retained old generation + the frozen 64 MiB headroom.
        val diskGeneration =
            readPointerGeneration()
                ?: return BackupRestoreSwitchResult.AbortedBeforePublish(
                    BackupRestoreAbortReason.ActivePointerUnreadable,
                    BackupRestoreRuntimeOutcome.RestoredReady,
                    owner.activeGeneration,
                )
        val precheck = confirmTimeDiskPrecheck(diskGeneration, snapshotFile, migratedFile, hasMigration)
        if (precheck != null) {
            return BackupRestoreSwitchResult.AbortedBeforePublish(precheck, BackupRestoreRuntimeOutcome.RestoredReady, owner.activeGeneration)
        }

        // From the quiesce entry on, an ESCAPE — the caller's Job being cancelled while this
        // coroutine is suspended (the initial quiesce wait can be the full bounded timeout), or
        // an Error propagating out of the fail-loud ports — must rethrow honestly, but must
        // NEVER wedge the runtime: the owner would otherwise stay Quiescing (cancellation) or
        // Closed with a possibly-mutated pointer (Error), and every business call would fail
        // for the rest of the process. The repair runs under NonCancellable so it completes
        // even while the caller is cancelling, then the original exception propagates.
        // INFO-C: a Job that was ALREADY cancelled before entry still performs exactly ONE
        // bounded repair cycle here (re-drain attempts + reopen, each with its own budget) —
        // that is the deliberate cost of never wedging the runtime, not a leak: the cycle
        // cannot loop, and after it the escape rethrows.
        var repairDiskGeneration: Int? = null
        try {
            // ---- Step 3: quiesce WITHOUT holding a lease (the caller contract). Either exit
            // leaves the owner in Quiescing, so a blocked outcome goes through the restoration
            // program.
            when (owner.quiesce()) {
                QuiesceResult.Quiesced -> Unit
                is QuiesceResult.QuiesceBlocked -> {
                    return postponedResult(BackupRestorePostponeReason.QuiesceBlocked)
                }
            }

            // ---- Step 4: post-quiesce generation recheck, then the authoritative
            // disk-generation read. The recheck runs FIRST so a concurrent transition that
            // starts between the two is observed at the close below instead of masking itself
            // as a stale token.
            if (!isTokenGenerationCurrent(token)) {
                return staleResult(BackupRestoreStaleReason.GenerationSuperseded)
            }
            val currentDiskGeneration =
                readPointerGeneration()
                    ?: return abortedResult(BackupRestoreAbortReason.ActivePointerUnreadable)
            repairDiskGeneration = currentDiskGeneration

            // ---- Step 5: close the active graph. A block is a typed postpone plus restoration;
            // the pointer is untouched and (after the restoration reopen) the old graph is back.
            when (owner.closeActiveGraph()) {
                CloseResult.Closed -> Unit
                is CloseResult.QuiesceBlocked ->
                    return postponedResult(BackupRestorePostponeReason.CloseBlocked)
                CloseResult.TransitionInProgress ->
                    return postponedResult(BackupRestorePostponeReason.CloseBlocked)
            }

            val newGeneration = currentDiskGeneration + 1

            // ---- Step 6: assemble gen-(current+1) from the migrated copy (or the snapshot),
            // with the delete-then-stage precondition, the stageLegacyUpgrade fsync order and
            // the local consistency gate; then journal=prepared. Any failure discards the
            // half-built directory and restores the runtime; the pointer is never touched.
            val stagingAbort =
                stageNewGeneration(
                    sourceFile = if (hasMigration) migratedFile else snapshotFile,
                    newGeneration = newGeneration,
                    requiredWithoutNewGen = precheckRequirementWithoutNewGen(currentDiskGeneration, snapshotFile, migratedFile, hasMigration),
                )
            if (stagingAbort != null) {
                runCatching { deleteGenerationDirectory(fileSystem, layout, newGeneration) }
                return abortedResult(stagingAbort)
            }
            val preparedWritten =
                try {
                    writeJournal(LedgerSwitchJournalStage.Prepared, currentDiskGeneration, newGeneration)
                    true
                } catch (failure: Error) {
                    throw failure
                } catch (failure: Throwable) {
                    false
                }
            if (!preparedWritten) {
                runCatching { deleteGenerationDirectory(fileSystem, layout, newGeneration) }
                return abortedResult(BackupRestoreAbortReason.JournalWriteFailed)
            }

            // ---- Step 7: publish the atomic pointer (the frozen primitive) then
            // journal=switched. From here on the flow is inside the switched window: every
            // failure rolls back.
            val switched =
                try {
                    publishActivePointer(fileSystem, layout, newGeneration)
                    writeJournal(LedgerSwitchJournalStage.Switched, currentDiskGeneration, newGeneration)
                    true
                } catch (failure: Error) {
                    throw failure
                } catch (failure: Throwable) {
                    false
                }
            if (!switched) {
                return rollBack(currentDiskGeneration, newGeneration)
            }

            // ---- Step 8: reopen the generation this flow JUST staged and published, by its
            // explicit name — NOT through the on-disk pointer. The pointer route would run the
            // startup sequence's switch-journal gate, and this flow's OWN journal is still on
            // disk here (it is removed only after the read-back succeeds, step 9). The gate would
            // consume that journal, republish the OLD pointer and delete the NEW generation
            // directory, rolling the switch back while still reporting success. The explicit
            // selection opens the named generation directly (no pointer resolution, no journal
            // gate) and the open closure still performs the authoritative read-back; the
            // generation is safe to open directly because step 6 already staged, fsynced and
            // locally gated it and step 7 published the pointer to it. Anything but a clean
            // reopen rolls back.
            val reopened = reopenOldGraphWithRetry(GenerationSelection.Explicit(newGeneration))
            if (reopened !is ReopenResult.Reopened) {
                return rollBack(currentDiskGeneration, newGeneration)
            }

            // ---- Step 9: remove the journal (committed) and deliver the result bound to the
            // post-reopen generation for the landing guard. A journal-removal failure rolls
            // back too: per the frozen restart rule a surviving journal means the switch did
            // not commit.
            val journalRemoved =
                try {
                    removeSwitchJournalDurably()
                    true
                } catch (failure: Error) {
                    throw failure
                } catch (failure: Throwable) {
                    false
                }
            if (!journalRemoved) {
                return rollBack(currentDiskGeneration, newGeneration)
            }
            return BackupRestoreSwitchResult.Committed(runtimeGeneration = reopened.generation)
        } catch (failure: Throwable) {
            // P2-1/F-7: ANY escape from the guarded region is repaired, then rethrown fail-loud.
            // The fail-loud [LedgerFileSystem] contract permits an escaped RuntimeException (it is
            // documented to throw rather than silently succeed), and the owner must never be left
            // wedged in Quiescing or Closed with a possibly-mutated pointer. The repair runs under
            // NonCancellable so it completes even when the escape is the caller's cancellation;
            // the original throwable — CancellationException, Error or RuntimeException — then
            // propagates unchanged. (Most Throwables are converted to typed results inside the
            // guarded region; this is the last-resort net for one that is not.)
            withContext(NonCancellable) { repairRuntimeAfterEscape(repairDiskGeneration, failure) }
            throw failure
        }
    }

    /** Spec section 3.1 item 3: whether the token's captured generation is still the active one. */
    private fun isTokenGenerationCurrent(token: RestorePreflightToken): Boolean = !shouldDiscardLandingResult(token.generation, owner.activeGeneration)

    /**
     * The confirm-time peak disk precheck (spec section 3.2). Returns null when enough space is
     * verified, otherwise the typed abort reason. The frozen formula is
     * `available >= snapshot_size + migrated_copy_size + new_generation_db_size +
     * retained_old_generation_db_size + 64 MiB`. The retained old generation is measured from the
     * CURRENT pointer (main file plus whichever sidecars exist); the new generation uses the
     * migrated-copy length (or, without a migration, the snapshot length) as its lower bound —
     * the migration rewrites pages, so the sizes are not guaranteed equal. The actual sizes are
     * re-checked after staging (spec section 3.2's post-staging recheck, which routes to the
     * step-6 failure path).
     *
     * "Cannot verify" is ONE outcome regardless of how it arises (P3-1): a null usable-space
     * value AND a throwing readable both map to [BackupRestoreAbortReason.UnknownDiskSpace] —
     * the section 3.2 fail-closed ruling covers the unmeasurable case, not only the absent one.
     */
    private fun confirmTimeDiskPrecheck(
        diskGeneration: Int,
        snapshotFile: String,
        migratedFile: String,
        hasMigration: Boolean,
    ): BackupRestoreAbortReason? =
        try {
            val available = fileSystem.usableSpace(layout.hostDirectory)
            val base = precheckRequirementWithoutNewGen(diskGeneration, snapshotFile, migratedFile, hasMigration)
            val required = saturatedAdd(base, newGenerationLowerBound(snapshotFile, migratedFile, hasMigration))
            when {
                available == null -> BackupRestoreAbortReason.UnknownDiskSpace
                // A saturated requirement (an unmeasurable retained generation) must stay
                // saturated through the comparison, so the precheck fails closed, not by overflow.
                required == Long.MAX_VALUE && available < Long.MAX_VALUE -> BackupRestoreAbortReason.InsufficientDiskSpace
                available < required -> BackupRestoreAbortReason.InsufficientDiskSpace
                else -> null
            }
        } catch (failure: Error) {
            throw failure
        } catch (failure: Throwable) {
            BackupRestoreAbortReason.UnknownDiskSpace
        }

    /** Addition that saturates at [Long.MAX_VALUE] instead of overflowing (the disk formula). */
    private fun saturatedAdd(
        left: Long,
        right: Long,
    ): Long = if (left >= Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    /**
     * The frozen confirm-time requirement MINUS the new-generation term (which is only known as
     * a lower bound before staging and as actual sizes after it):
     * snapshot + migrated copy + retained old generation (main + existing sidecars) + 64 MiB.
     */
    private fun precheckRequirementWithoutNewGen(
        diskGeneration: Int,
        snapshotFile: String,
        migratedFile: String,
        hasMigration: Boolean,
    ): Long =
        try {
            requirementWithoutNewGen(diskGeneration, snapshotFile, migratedFile, hasMigration)
        } catch (failure: Error) {
            throw failure
        } catch (failure: Throwable) {
            // Unmeasurable retained generation: the precheck cannot confirm the requirement.
            Long.MAX_VALUE
        }

    /**
     * The new_generation_db_size lower bound used by the confirm-time precheck (spec section 3.2):
     * the migrated copy's length, or — without a migration — the snapshot's length, because that
     * is the file step 6 copies into the new generation. The actual size is re-checked after
     * staging.
     */
    private fun newGenerationLowerBound(
        snapshotFile: String,
        migratedFile: String,
        hasMigration: Boolean,
    ): Long = if (hasMigration) fileSystem.length(migratedFile) else fileSystem.length(snapshotFile)

    private fun requirementWithoutNewGen(
        diskGeneration: Int,
        snapshotFile: String,
        migratedFile: String,
        hasMigration: Boolean,
    ): Long {
        val snapshotSize = fileSystem.length(snapshotFile)
        val migratedSize = if (hasMigration) fileSystem.length(migratedFile) else 0L
        val oldGenerationSize = generationSizeOnDisk(diskGeneration)
        return snapshotSize + migratedSize + oldGenerationSize + BACKUP_DISK_HEADROOM_BYTES
    }

    /** The actual on-disk size of one generation: the main file plus whichever sidecars exist. */
    private fun generationSizeOnDisk(generation: Int): Long {
        val directory = layout.generationDirectory(generation)
        var total = fileSystem.length(layout.mainFile(directory))
        for (suffix in LEDGER_SIDECAR_SUFFIXES) {
            val sidecar = layout.sidecarFile(directory, suffix)
            if (fileSystem.exists(sidecar)) {
                total += fileSystem.length(sidecar)
            }
        }
        return total
    }

    /**
     * Step 6 (spec section 3.6): assemble the new generation directory from [sourceFile] and
     * write nothing else. Mirrors [stageLegacyUpgrade]: copy the main file, copy whichever
     * sidecars exist, fsync each staged file, then fsync the generation directory — all BEFORE
     * the pointer publish. The delete-then-stage precondition deletes any leftover directory
     * from a previous abort/rollback first (never copying into a non-empty target, so no stale
     * `-wal` can be picked up); a deletion failure aborts. After the fsyncs the local
     * consistency gate ([isUsableSqliteMainFile]) and the actual-size disk recheck run; both
     * route to the step-6 failure path.
     *
     * Returns null on success, otherwise the typed abort reason; the caller discards the
     * half-built directory and restores the runtime.
     */
    private fun stageNewGeneration(
        sourceFile: String,
        newGeneration: Int,
        requiredWithoutNewGen: Long,
    ): BackupRestoreAbortReason? =
        try {
            val directory = layout.generationDirectory(newGeneration)
            val mainFile = layout.mainFile(directory)
            // The delete-then-stage precondition: the target must not exist when staging starts.
            deleteGenerationDirectory(fileSystem, layout, newGeneration)
            fileSystem.createDirectories(directory)
            fileSystem.copy(sourceFile, mainFile)
            for (suffix in LEDGER_SIDECAR_SUFFIXES) {
                val sourceSidecar = sourceFile + suffix
                if (fileSystem.exists(sourceSidecar)) {
                    fileSystem.copy(sourceSidecar, layout.sidecarFile(directory, suffix))
                }
            }
            fileSystem.fsyncFile(mainFile)
            for (suffix in LEDGER_SIDECAR_SUFFIXES) {
                val stagedSidecar = layout.sidecarFile(directory, suffix)
                if (fileSystem.exists(stagedSidecar)) {
                    fileSystem.fsyncFile(stagedSidecar)
                }
            }
            fileSystem.fsyncDirectory(directory)
            val gateFailure = !isUsableSqliteMainFile(fileSystem, mainFile)
            // The post-staging actual-size recheck (spec section 3.2): the new generation now
            // has real sizes, and an unknown value here fails closed like the precheck.
            val available =
                if (gateFailure) {
                    null
                } else {
                    try {
                        fileSystem.usableSpace(layout.hostDirectory)
                    } catch (failure: Error) {
                        throw failure
                    } catch (failure: Throwable) {
                        null
                    }
                }
            when {
                gateFailure -> BackupRestoreAbortReason.StagingFailed
                available == null -> BackupRestoreAbortReason.UnknownDiskSpace
                // A saturated requirement (an unmeasurable retained generation) must stay
                // saturated through the comparison, so the recheck fails closed, not by overflow.
                available < Long.MAX_VALUE && requiredWithoutNewGen >= Long.MAX_VALUE -> BackupRestoreAbortReason.InsufficientDiskSpace
                available < requiredWithoutNewGen + generationSizeOnDisk(newGeneration) ->
                    BackupRestoreAbortReason.InsufficientDiskSpace
                else -> null
            }
        } catch (failure: Error) {
            throw failure
        } catch (failure: Throwable) {
            BackupRestoreAbortReason.StagingFailed
        }

    /**
     * Writes the journal atomically and makes it durable (file fsync + host-directory fsync)
     * BEFORE the pointer can reference the state it describes — the same durability discipline
     * as [publishActivePointer]. The `prepared` record must be durable before the switch and the
     * `switched` record after it, so a crash in either window is recoverable deterministically.
     */
    private fun writeJournal(
        stage: LedgerSwitchJournalStage,
        oldGeneration: Int,
        newGeneration: Int,
    ) {
        fileSystem.writeAtomic(layout.switchJournalFile, ledgerSwitchJournalBytes(LedgerSwitchJournal(stage, oldGeneration, newGeneration)))
        fileSystem.fsyncFile(layout.switchJournalFile)
        fileSystem.fsyncDirectory(layout.hostDirectory)
    }

    /**
     * Removes the journal durably (P3-5): the deletion itself is fsynced like every journal
     * write is, so a committed switch cannot be resurrected as a rollback by a crash that the
     * host directory has not yet forgotten.
     */
    private fun removeSwitchJournalDurably() {
        fileSystem.delete(layout.switchJournalFile)
        fileSystem.fsyncDirectory(layout.hostDirectory)
    }

    /**
     * The best-effort ESCAPE repair (P2-1): an escape from the guarded region — the caller's
     * cancellation, or an Error from a fail-loud port — must rethrow honestly, but the runtime
     * must never be silently wedged in `Quiescing` or `Closed`. Runs under `NonCancellable`.
     *
     * When the journal survives the escape, the switched window may have been entered, so the
     * frozen restart ROLLBACK rule (spec section 4.2, the same actions the startup gate runs)
     * executes first: republish the old pointer, remove the journal, discard the staged new
     * generation — each step best effort, never masking the original escape. Afterwards the
     * section 3.10 restoration program (re-drain + `reopen(ActivePointer)`) returns the owner
     * to Ready on the old graph in every case. With no journal (the escape landed before the
     * pointer publish), the pointer was never touched, so only the restoration program runs —
     * a half-built staged directory stays inert and is deleted by the next confirm's
     * delete-then-stage or the retention policy.
     *
     * P3-A (spec-closure review): the trailing restoration program runs inside a guard that
     * attaches a repair-leg failure to [escape] via `addSuppressed` — the repair must never mask
     * the original escape it is repairing.
     */
    private suspend fun repairRuntimeAfterEscape(
        diskGeneration: Int?,
        escape: Throwable,
    ) {
        if (diskGeneration != null) {
            val journal =
                runCatching {
                    if (fileSystem.exists(layout.switchJournalFile)) {
                        parseLedgerSwitchJournal(fileSystem.readBytes(layout.switchJournalFile))
                    } else {
                        null
                    }
                }.getOrNull()
            if (journal != null) {
                runCatching { publishActivePointer(fileSystem, layout, journal.oldGeneration) }
                runCatching { removeSwitchJournalDurably() }
                runCatching { deleteGenerationDirectory(fileSystem, layout, journal.newGeneration) }
            }
        }
        try {
            restoreRuntimeWithGeneration()
        } catch (repairFailure: Throwable) {
            escape.addSuppressed(repairFailure)
        }
    }

    /**
     * The in-process rollback (spec section 3.8), the first executor of the frozen ROLLBACK
     * rule: republish the old pointer through the frozen primitive, remove the journal, delete
     * the staged new generation directory including its sidecars (best effort — the deletion is
     * cleanup, and a failure is left to the retention policy), and reopen the old graph. The
     * first two steps are NOT best effort: failing them leaves the fail-closed
     * [BackupRestoreSwitchResult.RecoveryRequired] with the disk exactly as found.
     */
    private suspend fun rollBack(
        oldGeneration: Int,
        newGeneration: Int,
    ): BackupRestoreSwitchResult {
        val republished =
            try {
                publishActivePointer(fileSystem, layout, oldGeneration)
                true
            } catch (failure: Error) {
                throw failure
            } catch (failure: Throwable) {
                false
            }
        if (!republished) {
            return BackupRestoreSwitchResult.RecoveryRequired(BackupRestoreRecoveryCause.RollbackPublishFailed)
        }
        val journalRemoved =
            try {
                removeSwitchJournalDurably()
                true
            } catch (failure: Error) {
                throw failure
            } catch (failure: Throwable) {
                false
            }
        if (!journalRemoved) {
            return BackupRestoreSwitchResult.RecoveryRequired(BackupRestoreRecoveryCause.RollbackJournalRemoveFailed)
        }
        runCatching { deleteGenerationDirectory(fileSystem, layout, newGeneration) }
        val reopened = reopenOldGraphWithRetry()
        return when (reopened) {
            is ReopenResult.Reopened -> BackupRestoreSwitchResult.RolledBack(runtimeGeneration = reopened.generation)
            else -> BackupRestoreSwitchResult.RecoveryRequired(BackupRestoreRecoveryCause.RollbackReopenFailed)
        }
    }

    /**
     * The typed abort results below all carry the POST-restoration owner generation (spec section
     * 3.10): the restoration `reopen` advances it, so the host must guard the result against the
     * value the owner holds when the rejection is produced, never the pre-confirm capture.
     */
    private suspend fun staleResult(reason: BackupRestoreStaleReason): BackupRestoreSwitchResult.Stale {
        val restoration = restoreRuntimeWithGeneration()
        return BackupRestoreSwitchResult.Stale(reason, restoration.outcome, restoration.generation)
    }

    private suspend fun postponedResult(reason: BackupRestorePostponeReason): BackupRestoreSwitchResult.Postponed {
        val restoration = restoreRuntimeWithGeneration()
        return BackupRestoreSwitchResult.Postponed(reason, restoration.outcome, restoration.generation)
    }

    private suspend fun abortedResult(reason: BackupRestoreAbortReason): BackupRestoreSwitchResult.AbortedBeforePublish {
        val restoration = restoreRuntimeWithGeneration()
        return BackupRestoreSwitchResult.AbortedBeforePublish(reason, restoration.outcome, restoration.generation)
    }

    /** The restoration program's typed outcome plus the owner generation it left behind. */
    private class RuntimeRestoration(
        val outcome: BackupRestoreRuntimeOutcome,
        val generation: Generation?,
    )

    /**
     * The restoration program of spec section 3.10, run by EVERY abort after the quiesce exit
     * and before the pointer publish. Step 1 re-enters `quiesce`: in the Quiescing state it
     * joins the existing drain and waits, so the in-flight leases can only decrease; a drain
     * that still cannot converge after the bounded attempts is reported honestly as
     * [BackupRestoreRuntimeOutcome.NotRestored] (the stuck-lease exception — the owner then
     * stays Quiescing, which only the result reports). Step 2 reopens the old graph through the
     * on-disk pointer (the pointer was never published on these paths), which returns the owner
     * to Ready; a reopen that fails closed is the StartupError exit. A concurrent transition
     * contention is retried once, then reported honestly.
     *
     * The owner's generation is captured AFTER the program runs: the restoration `reopen`
     * ADVANCES the process generation ([LedgerRuntimeOwner.reopen]), so binding an abort result
     * to the pre-confirm capture would make the host's landing guard discard it and wedge the
     * surface (the F-1 landing bug). The captured value is null exactly when the owner has no
     * active generation (a fail-closed restoration), which the host lands unguarded.
     */
    private suspend fun restoreRuntimeWithGeneration(): RuntimeRestoration {
        repeat(reDrainAttempts) {
            if (owner.quiesce() is QuiesceResult.Quiesced) {
                val outcome =
                    when (reopenOldGraphWithRetry()) {
                        is ReopenResult.Reopened -> BackupRestoreRuntimeOutcome.RestoredReady
                        is ReopenResult.Failed -> BackupRestoreRuntimeOutcome.FailClosed
                        else -> BackupRestoreRuntimeOutcome.NotRestored
                    }
                return RuntimeRestoration(outcome, owner.activeGeneration)
            }
        }
        return RuntimeRestoration(BackupRestoreRuntimeOutcome.NotRestored, owner.activeGeneration)
    }

    /**
     * `reopen(selection)` with the spec section 3.10 contention discipline: one retry after a
     * bounded pause when the owner mutex was held by another transition; a hard reopen failure
     * ([ReopenResult.Failed]) is returned immediately and never retried silently.
     *
     * The [selection] is parameterized because the two reopen sites target DIFFERENT generations
     * through different routes (spec sections 3.8/3.9/3.10): step 8 passes
     * [GenerationSelection.Explicit] with the generation it just published, while the rollback and
     * the restoration program pass [GenerationSelection.ActivePointer] so the pointer (and, on
     * the restoration path, its journal gate) decides. Defaulting to the pointer keeps every
     * existing call site's semantics explicit.
     */
    private suspend fun reopenOldGraphWithRetry(
        selection: GenerationSelection = GenerationSelection.ActivePointer,
    ): ReopenResult {
        val first = owner.reopen(selection)
        if (first is ReopenResult.Reopened || first is ReopenResult.Failed) {
            return first
        }
        delay(reopenRetryPauseMillis)
        return owner.reopen(selection)
    }

    /**
     * The current disk generation, parsed strictly through [parsePointer]. The confirm flow
     * never trusts anything but a well-formed pointer to an existing generation.
     */
    private fun readPointerGeneration(): Int? =
        if (!fileSystem.exists(layout.activePointerFile)) {
            null
        } else {
            try {
                parsePointer(fileSystem.readBytes(layout.activePointerFile), layout)
            } catch (failure: Error) {
                throw failure
            } catch (failure: Throwable) {
                null
            }
        }

    /** The streaming SHA-256 of one staging artifact (the D-179 section 5.4 primitive shape). */
    private fun digestOfFile(path: String): ByteArray? =
        try {
            val digest = crypto.sha256Digest()
            val buffer = ByteArray(BACKUP_STREAM_CHUNK_BYTES)
            fileSystem.openRead(path).use { stream ->
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest()
        } catch (failure: Error) {
            throw failure
        } catch (failure: Throwable) {
            null
        }
}
