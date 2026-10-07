package com.unifiedledger.ui

/*
 * P7-06 06.D (D-182; spec `docs/specs/2026-09-26-p7-06-restore-confirm-switch-design.md`
 * section 5.3): the POINTER_MISSING explicit recovery — the design-level closure of the D-178
 * residual (a): a process killed between the fresh-install/legacy-upgrade generation creation and
 * the pointer publish left a generation directory with no pointer, which 06.1 fail-closed on
 * forever. The startup behavior is UNCHANGED — `resolveLedgerStorage` still rejects
 * `POINTER_MISSING`/`POINTER_INVALID`/`JOURNAL_PRESENT` exactly as before, nothing here is ever
 * consulted or run automatically, and the D-176 silent-empty-database prohibition is untouched:
 * every branch re-anchors on an existing on-disk artifact (a validated generation directory, or
 * the legacy original for the discard-and-re-upgrade branch).
 *
 * THE TWO USER-CONFIRMED BRANCHES (spec section 5.3; calling [adopt] or
 * [discardUnvalidatableAndReUpgrade] IS the explicit user confirmation, exactly like the confirm
 * flow's discipline — there is no implicit path and no automatic adoption):
 * - a candidate that verifies COMPLETE-AND-CURRENT (`isUsableSqliteMainFile` +
 *   `PRAGMA integrity_check` all ok + authoritative `PRAGMA user_version` EXACTLY equal to the
 *   current supported schema version) is ADOPTED through the frozen `publishActivePointer`
 *   primitive as ONE transaction of verify-then-publish: the candidates are re-verified inside
 *   the adoption, the highest passing generation wins, and there is never a partial adoption
 *   (verify fails -> no publish). The recovery set is pinned to `{current}` — deliberately
 *   DISTINCT from the wiring whitelist `{1, 31, 32, 33}` (spec section 5.4): a complete-but-OLD-version
 *   candidate is the 06.1 upgrade window's raw copy and must reach the ledger only through the
 *   authoritative open path's migration + read-back, never by adoption.
 * - when NO candidate verifies, and the legacy original is still present and usable (guard 1 of
 *   the spec; Android's `databases/ledger.db` — desktop has no legacy location and stays
 *   fail-closed with NO discard branch), the user-confirmed DISCARD-AND-RE-UPGRADE branch deletes
 *   the unvalidatable candidate directories (including sidecars, the P2-3 sidecar discipline) and
 *   returns; the composition root then re-runs the normal startup, whose frozen `UpgradeLegacy`
 *   sequence re-stages from the user's real legacy database. That rebuild is not a fresh install.
 *
 * Threading: every operation is blocking file/SQLite work; the host dispatches it off the UI
 * thread (container-format spec section 4.8). The use case never touches the runtime owner — a
 * pointerless start never reached Ready, so there are no leases to respect — and it performs NO
 * disk mutation until one of the two confirmed actions runs ([probe] is read-only except that it
 * re-runs the shared startup resolution, whose recognizable-journal branch is the sanctioned
 * startup recovery).
 */

/** Why one candidate generation directory is (or is not) adoptable (spec section 5.3). */
enum class PointerRecoveryCandidateVerdict {
    /** Usable main file, `integrity_check` all ok, and the authoritative version IS the current one. */
    Adoptable,

    /**
     * Usable and integral, but at an OLDER schema version: the upgrade window's raw copy — never
     * adopted (the recovery set is `{current}`), routed to the discard-and-re-upgrade branch.
     */
    WrongVersion,

    /** Not a usable SQLite main file, or the integrity/version probe failed: never adopted. */
    Unusable,
}

/** One candidate generation directory and its verification verdict. */
class PointerRecoveryCandidate(
    val generation: Int,
    val verdict: PointerRecoveryCandidateVerdict,
)

/**
 * The read-only inspection behind the recovery face: what the candidate directories are, whether
 * one is adoptable, and whether the discard-and-re-upgrade branch is available at all.
 */
class PointerMissingRecoveryState(
    val candidates: List<PointerRecoveryCandidate>,
    /** The highest adoptable generation, or null when none verifies — no partial adoption exists. */
    val adoptableGeneration: Int?,
    /**
     * Whether the discard-and-re-upgrade branch is available: the legacy original is configured,
     * present and usable. False on a platform without a legacy location (desktop), which keeps
     * the pointerless state fail-closed with no discard branch (spec section 5.3, guard 1).
     */
    val legacyUpgradeAvailable: Boolean,
)

/** The outcome of probing the pointerless shape (spec section 5.3: only `POINTER_MISSING` recovers). */
sealed interface PointerMissingRecoveryProbe {
    /** The startup failure is the pointerless-generations shape; the state backs the recovery face. */
    data class Recoverable(
        val state: PointerMissingRecoveryState,
    ) : PointerMissingRecoveryProbe

    /**
     * The startup failure is NOT the pointerless shape (another failure class, or the resolution
     * now plans a normal open — e.g. the startup journal recovery completed): no recovery face.
     */
    data object NotPointerMissing : PointerMissingRecoveryProbe
}

/** The outcome of the user-confirmed ADOPTION (spec section 5.3, first branch). */
sealed interface PointerRecoveryAdoptionResult {
    /** The pointer was published through the frozen primitive; the next start opens [generation]. */
    data class Adopted(
        val generation: Int,
    ) : PointerRecoveryAdoptionResult

    /** Re-verification found NO adoptable candidate: nothing was published (no partial adoption). */
    data object NoAdoptableCandidate : PointerRecoveryAdoptionResult

    /** The pointerless shape is gone (the state moved between the face and the tap): re-probe. */
    data object StateChanged : PointerRecoveryAdoptionResult

    /** The publish failed; the state stays fail-closed with no pointer and the next start retries. */
    data object PublishFailed : PointerRecoveryAdoptionResult
}

/** The outcome of the user-confirmed DISCARD-AND-RE-UPGRADE (spec section 5.3, second branch). */
sealed interface PointerRecoveryDiscardResult {
    /** The candidates were discarded; the caller re-runs the normal startup (`UpgradeLegacy`). */
    data object DiscardedAwaitingUpgrade : PointerRecoveryDiscardResult

    /** The legacy original is absent/unusable: the branch is typed-unavailable (desktop shape). */
    data object LegacyOriginalMissing : PointerRecoveryDiscardResult

    /** An adoptable candidate NOW verifies: adopt instead of discarding — refuse, nothing deleted. */
    data object AdoptableCandidatePresent : PointerRecoveryDiscardResult

    /** The pointerless shape is gone (the state moved between the face and the tap): re-probe. */
    data object StateChanged : PointerRecoveryDiscardResult

    /** A candidate deletion failed; the state stays fail-closed and a retry re-runs the discard. */
    data object DiscardFailed : PointerRecoveryDiscardResult

    /**
     * The generations directory holds non-candidate content: the discard destroys only what it
     * can verify as a candidate, so it refuses up front (zero mutation) and the state stays
     * fail-closed for human attention.
     */
    data object UnexpectedContent : PointerRecoveryDiscardResult
}

/**
 * The shared POINTER_MISSING recovery use case (06.D spec section 5.3). Constructed by the
 * composition root with the SAME collaborators the startup sequence uses; invoked only from the
 * recovery face on the startup-failure surface, always on a background thread.
 *
 * @param fileSystem the platform file operations (the startup sequence's port).
 * @param layout the stable-storage layout over the SAME host directory the startup resolved.
 * @param isolatedDatabase the controlled isolated-database surface (integrity + version probes).
 * @param currentSchemaVersion the schema version this build supports — the recovery set is
 *   `{current}` (spec section 5.3), DISTINCT from the confirm-wiring whitelist `{1, 31, 32, 33}`.
 * @param legacyMainFile the platform's legacy product database path, or null when the platform
 *   has none (desktop): null disables the discard-and-re-upgrade branch entirely (typed).
 */
class PointerMissingRecoveryUseCase(
    private val fileSystem: LedgerFileSystem,
    private val layout: LedgerStorageLayout,
    private val isolatedDatabase: RestoreIsolatedDatabasePort,
    private val currentSchemaVersion: Long,
    private val legacyMainFile: String?,
) {
    /**
     * The probe behind the recovery face: whether the current failure shape is the pointerless
     * one, and if so what the candidates look like. It performs NO RECOVERY ACTION of its own —
     * it never adopts, never discards, and never touches the legacy original.
     *
     * It is NOT, however, strictly disk-read-only (F-9): it re-runs the shared
     * [resolveLedgerStorage], and that resolution's recognizable-journal branch is the sanctioned
     * 06.D startup recovery (container-format spec section 5.3's ROLLBACK restart half) — it
     * republishes the recorded old pointer and removes the journal. So a probe issued while a
     * recognizable journal is present will complete that startup recovery as a side effect; the
     * probe then reports [PointerMissingRecoveryProbe.NotPointerMissing], because the resolution
     * is no longer the pointerless shape. An unrecognizable journal keeps the frozen fail-closed
     * gate and mutates nothing.
     */
    fun probe(): PointerMissingRecoveryProbe =
        when (currentShape()) {
            null -> PointerMissingRecoveryProbe.NotPointerMissing
            else -> PointerMissingRecoveryProbe.Recoverable(inspect())
        }

    /**
     * The user-confirmed ADOPTION: re-verify (the single verify-then-publish transaction) and, only
     * when a candidate still passes, publish the HIGHEST passing generation through the frozen
     * [publishActivePointer] primitive. The caller re-runs the normal startup afterwards.
     */
    fun adopt(): PointerRecoveryAdoptionResult {
        // The transaction's verify leg: the shape AND the verdicts are re-established here, so a
        // state that moved (or a candidate that changed) can never be adopted from a stale face.
        val adoptable = verifiedAdoptableGenerations() ?: return PointerRecoveryAdoptionResult.StateChanged
        val target = adoptable.maxOrNull() ?: return PointerRecoveryAdoptionResult.NoAdoptableCandidate
        return try {
            publishActivePointer(fileSystem, layout, target)
            PointerRecoveryAdoptionResult.Adopted(target)
        } catch (failure: Error) {
            throw failure
        } catch (failure: Throwable) {
            // The publish is the only mutation and it failed before completing: the state stays
            // fail-closed (no pointer), the next start (or retry) re-runs the recovery.
            PointerRecoveryAdoptionResult.PublishFailed
        }
    }

    /**
     * The user-confirmed DISCARD-AND-RE-UPGRADE: only when NO candidate verifies and the legacy
     * original is still present and usable, delete the candidate directories (including sidecars)
     * AND then the emptied generations directory itself — the next normal startup must resolve
     * `UpgradeLegacy` again, and a generations directory that merely exists (even empty) keeps the
     * pointer-missing fail-closed forever. The listing is guarded first: any child that is not a
     * candidate generation directory refuses the whole discard up front (zero mutation) — the
     * branch never destroys content it cannot verify as its own. The legacy files are never
     * touched by this flow.
     */
    fun discardUnvalidatableAndReUpgrade(): PointerRecoveryDiscardResult {
        val generations = pointerlessCandidateGenerations() ?: return PointerRecoveryDiscardResult.StateChanged
        if (generations.any { verdictOf(it) == PointerRecoveryCandidateVerdict.Adoptable }) {
            // An adoptable candidate appeared: the discard branch is defined ONLY for the
            // no-verification shape; refuse before deleting anything.
            return PointerRecoveryDiscardResult.AdoptableCandidatePresent
        }
        if (!legacyUpgradeAvailable()) {
            // Guard 1 (spec section 5.3): without the user's real legacy original on disk there is
            // nothing to re-upgrade from — the branch does not exist, typed, and nothing is deleted
            // (the D-176 silent-empty prohibition keeps its full strength).
            return PointerRecoveryDiscardResult.LegacyOriginalMissing
        }
        val children =
            try {
                fileSystem.listDirectory(layout.generationsDirectory)
            } catch (failure: Error) {
                throw failure
            } catch (failure: Throwable) {
                return PointerRecoveryDiscardResult.DiscardFailed
            }
        if (children.any { name -> parseGenerationName(name) == null }) {
            // Foreign content: the discard deletes only what it can prove is a candidate, so the
            // whole branch refuses (fail-closed, zero mutation).
            return PointerRecoveryDiscardResult.UnexpectedContent
        }
        for (generation in generations) {
            try {
                deleteGenerationDirectory(fileSystem, layout, generation)
            } catch (failure: Error) {
                throw failure
            } catch (failure: Throwable) {
                // Fail-closed: a partial discard still leaves the pointerless shape; a retry
                // re-runs the discard (the next confirm's delete-then-stage shares the discipline).
                // deleteGenerationDirectory is STRICT: a surviving directory throws (defect 2), so a
                // silent platform no-op can no longer be reported as a successful discard.
                return PointerRecoveryDiscardResult.DiscardFailed
            }
        }
        return try {
            fileSystem.delete(layout.generationsDirectory)
            // The emptied parent must actually be GONE for the re-upgrade to resolve (spec section
            // 5.3; 06.1 rule 2 keeps POINTER_MISSING fail-closed while the directory exists). A
            // platform `delete` that silently no-ops must not be reported as success (defect 2).
            if (fileSystem.exists(layout.generationsDirectory)) {
                throw LedgerGenerationDirectoryDeleteException(LedgerGenerationDirectoryKind.GENERATIONS_DIRECTORY)
            }
            PointerRecoveryDiscardResult.DiscardedAwaitingUpgrade
        } catch (failure: Error) {
            throw failure
        } catch (failure: Throwable) {
            // The candidates are gone but the empty parent still blocks the re-upgrade; a retry
            // re-runs the discard and reaches this single remaining deletion.
            PointerRecoveryDiscardResult.DiscardFailed
        }
    }

    /** The pointerless shape's candidate generations, or null when the shape has moved. */
    private fun pointerlessCandidateGenerations(): List<Int>? {
        val resolution = resolveLedgerStorage(fileSystem, layout, legacyMainFile)
        val failure = (resolution as? LedgerStorageResolution.Rejected)?.failure ?: return null
        return if (failure == LedgerStorageFailure.POINTER_MISSING) listCandidateGenerations() else null
    }

    private fun verifiedAdoptableGenerations(): List<Int>? = pointerlessCandidateGenerations()?.filter { verdictOf(it) == PointerRecoveryCandidateVerdict.Adoptable }

    /** Whether the CURRENT resolution still is the pointerless shape (null when it is not). */
    private fun currentShape(): LedgerStorageFailure? {
        val resolution = resolveLedgerStorage(fileSystem, layout, legacyMainFile)
        val failure = (resolution as? LedgerStorageResolution.Rejected)?.failure ?: return null
        return if (failure == LedgerStorageFailure.POINTER_MISSING) failure else null
    }

    /** The full face state for the pointerless shape (the shape was just confirmed by the caller). */
    private fun inspect(): PointerMissingRecoveryState {
        val candidates =
            listCandidateGenerations().map { generation -> PointerRecoveryCandidate(generation, verdictOf(generation)) }
        return PointerMissingRecoveryState(
            candidates = candidates,
            adoptableGeneration = candidates.filter { it.verdict == PointerRecoveryCandidateVerdict.Adoptable }.maxOfOrNull { it.generation },
            legacyUpgradeAvailable = legacyUpgradeAvailable(),
        )
    }

    /** The candidate generation numbers, ascending, from the generations directory listing. */
    private fun listCandidateGenerations(): List<Int> =
        runCatching { fileSystem.listDirectory(layout.generationsDirectory) }
            .getOrDefault(emptyList())
            .mapNotNull { name -> parseGenerationName(name) }
            .sorted()

    /** The three-step verification of one candidate (spec section 5.3): usable, integral, current. */
    private fun verdictOf(generation: Int): PointerRecoveryCandidateVerdict {
        val mainFile = layout.mainFile(layout.generationDirectory(generation))
        if (!isUsableSqliteMainFile(fileSystem, mainFile)) return PointerRecoveryCandidateVerdict.Unusable
        val integrity =
            try {
                isolatedDatabase.integrityCheckOk(mainFile)
            } catch (failure: Error) {
                throw failure
            } catch (failure: Throwable) {
                // A probe that cannot complete is a candidate that cannot be verified — the
                // fail-closed verdict, never an optimistic adoption.
                false
            }
        if (!integrity) return PointerRecoveryCandidateVerdict.Unusable
        val version =
            try {
                isolatedDatabase.readAuthoritativeUserVersion(mainFile)
            } catch (failure: Error) {
                throw failure
            } catch (failure: Throwable) {
                -1L
            }
        // The recovery set is pinned to {current}: a complete-but-old candidate is the upgrade
        // window's raw copy and reaches the ledger only through the authoritative open path.
        return if (version == currentSchemaVersion) {
            PointerRecoveryCandidateVerdict.Adoptable
        } else {
            PointerRecoveryCandidateVerdict.WrongVersion
        }
    }

    /** Guard 1 of the discard branch: the legacy original is configured, present and usable. */
    private fun legacyUpgradeAvailable(): Boolean = legacyMainFile != null && fileSystem.exists(legacyMainFile) && isUsableSqliteMainFile(fileSystem, legacyMainFile)
}
