package com.unifiedledger.ui

/*
 * P7-06 06.1 (D-176; spec `docs/specs/2026-09-24-p7-06-stable-storage-runtime-owner-design.md`
 * sections 3 and 4.5): the shared, platform-independent stable-storage layout, the startup
 * resolution rules and the non-destructive old-path upgrade sequence.
 *
 * This file is deliberately permission-agnostic and file-API-free: every file operation goes
 * through the injected [LedgerFileSystem] port, so the whole sequence (including its crash
 * ordering) is exercisable in common tests with a fault-injecting fake, while each composition
 * root supplies the platform implementation. The tracked files never contain a machine absolute
 * path: the host directory is resolved at runtime by the platform adapter (spec section 3.1).
 *
 * The literal names frozen here are an implementation-batch decision (the container-format spec
 * section 5.2 delegates the literal generation/journal layout to the implementation batch and
 * freezes only the semantics: private, single-ledger, enumerable, rollbackable).
 */

/** The generations parent directory inside the platform-resolved host directory. */
internal const val LEDGER_GENERATIONS_DIRECTORY = "ledger-generations"

/** The atomic active pointer file (spec section 3.2 (d); container-format spec section 5.3). */
internal const val LEDGER_ACTIVE_POINTER_FILE = "active-generation"

/**
 * The persistent switch journal file. 06.1 never wrote one (the `prepared -> switched ->
 * committed` machine belongs to 06.D); a present journal was a 06.1 startup gate (section 5.1
 * step 2) and always failed closed.
 *
 * P7-06 06.D (D-182; spec `docs/specs/2026-09-26-p7-06-restore-confirm-switch-design.md`
 * section 4.3): the 06.D confirm/switch use case now writes this journal, and the startup gate
 * below is EXTENDED exactly as the 06.1 spec registered as the 06.D obligation: a RECOGNIZABLE
 * journal (a versioned [LedgerSwitchJournal]) is rolled back per the container-format spec
 * section 5.3 frozen ROLLBACK restart rule and startup then proceeds normally; an
 * unrecognizable/unparseable journal keeps the frozen fail-closed [LedgerStorageFailure.JOURNAL_PRESENT]
 * semantics. D-176's fail-closed rules for everything else (POINTER_MISSING, POINTER_INVALID,
 * ACTIVE_GENERATION_*) are unchanged, and the silent-empty-database prohibition is untouched:
 * every journal recovery path re-anchors on the recorded OLD generation pointer.
 */
internal const val LEDGER_SWITCH_JOURNAL_FILE = "switch-journal"

/** The main database file name inside one generation directory. */
internal const val LEDGER_MAIN_FILE_NAME = "ledger.db"

/** The generation directory name prefix; the full name is `gen-<n>`. */
internal const val LEDGER_GENERATION_PREFIX = "gen-"

/**
 * The SQLite sidecar suffixes that must travel with the main file as one consistent set on COPY.
 *
 * Deliberately EXCLUDES the rollback journal `-journal`: it is TRANSIENT (the write-side artifact of
 * an in-progress transaction) and MUST NOT be copied into a new generation — a stale journal beside a
 * copied database is a corruption hazard, not consistency. It must nonetheless be DELETED wherever a
 * main file is deleted, which is what [LEDGER_DELETABLE_SIDECAR_SUFFIXES] exists for.
 */
internal val LEDGER_SIDECAR_SUFFIXES: List<String> = listOf("-wal", "-shm")

/**
 * The DELETION-ONLY superset of [LEDGER_SIDECAR_SUFFIXES] (P7-06 06.D device-gate defect 2, D-183):
 * every sidecar that must never survive its main file, including the transient rollback journal that
 * a read-WRITE framework open can leave behind (the behaviour the repo already documents on device —
 * `AndroidBackupSnapshotVerificationInstrumentedTest`, the 0-byte `<name>-journal` beside a
 * read-write-opened snapshot).
 *
 * WHY A SEPARATE LIST (and not a widened [LEDGER_SIDECAR_SUFFIXES]): the same suffix set governs the
 * legacy-upgrade COPY and the confirm-time staging copy, where `-journal` must NOT travel; only the
 * DELETE paths need the superset. Widening the shared list would copy transient journals into new
 * generations. `-journal` is deliberately NOT a tracked sidecar for sizing either: it is transient
 * and normally zero-length.
 */
internal val LEDGER_DELETABLE_SIDECAR_SUFFIXES: List<String> = listOf("-wal", "-shm", "-journal")

/**
 * P7-06 06.B (D-177; spec `2026-09-24-p7-06-backup-export-design.md` section 6): the private
 * backup staging directory under the platform-resolved host directory. The snapshot file, the
 * private staging container and any plaintext live ONLY here (never in the user target, never in
 * a public location); the literal name is an implementation-batch decision (the container-format
 * spec section 5.2 freezes only the semantics: private, single-ledger, enumerable, rollbackable).
 */
internal const val LEDGER_BACKUP_STAGING_DIRECTORY = "backup-staging"

/** The snapshot file name prefix inside the backup staging directory. */
internal const val LEDGER_BACKUP_SNAPSHOT_PREFIX = "snapshot-"

/** The private staging container file name prefix inside the backup staging directory. */
internal const val LEDGER_BACKUP_CONTAINER_PREFIX = "container-"

/**
 * P7-06 06.C (D-179; spec `2026-09-25-p7-06-restore-preflight-design.md` section 6.1): the restore
 * preflight's private staging prefixes. The spec PROPOSES exactly these three (`restore-container-`,
 * `restore-snapshot-`, `restore-migrated-`) and forbids mixing them with the 06.B export prefixes;
 * because they share the `restore-` stem, one sweep prefix covers all three.
 */
internal const val LEDGER_RESTORE_STAGING_PREFIX = "restore-"

/** The 16-byte SQLite file magic; a main file must start with it to be openable (section 4.5). */
internal val LEDGER_SQLITE_HEADER: ByteArray = "SQLite format 3\u0000".encodeToByteArray()

/**
 * The platform file operations the stable-storage sequence needs. Paths are opaque strings
 * produced by [join]; each platform adapter decides their syntax. Implementations must be
 * fail-loud: an unsupported or failing operation throws rather than silently succeeding, so the
 * sequence's fail-closed steps are observable.
 */
interface LedgerFileSystem {
    /** Joins a child name onto a parent path in the platform's syntax. */
    fun join(
        parent: String,
        child: String,
    ): String

    fun exists(path: String): Boolean

    fun isDirectory(path: String): Boolean

    fun length(path: String): Long

    fun readBytes(path: String): ByteArray

    /**
     * Reads at most [length] bytes from the start of [path]. This exists so the startup
     * SQLite-header guard ([isUsableSqliteMainFile]) inspects only the 16-byte header instead of
     * allocating the whole ledger file (which can be hundreds of megabytes) on the startup path
     * (review Fix 6). Implementations must read no more than [length] bytes.
     */
    fun readPrefix(
        path: String,
        length: Int,
    ): ByteArray

    /**
     * Atomic replace: the bytes become visible at [path] in one platform atomic step
     * (Android `AtomicFile` / desktop temp-file + atomic rename, container-format spec section
     * 5.3). A partially written pointer must never be observable.
     */
    fun writeAtomic(
        path: String,
        bytes: ByteArray,
    )

    fun copy(
        source: String,
        target: String,
    )

    fun delete(path: String)

    fun createDirectories(path: String)

    /** Flushes and fsyncs a file's contents to stable storage. */
    fun fsyncFile(path: String)

    /**
     * Flushes and fsyncs a directory entry so a rename/create inside it is durable. Platform
     * adapters implement the strongest available primitive; where a platform has no directory
     * fsync the adapter may treat it as best effort (documented at the adapter).
     */
    fun fsyncDirectory(path: String)

    /**
     * P7-06 06.B (D-177; spec section 3.2 / container-format spec section 4.8): the available
     * bytes on the volume holding [path], or null when the platform cannot report it. The export
     * disk precheck requires `available >= container_size + plaintext_size + 64 MiB`; this
     * primitive did not exist before 06.B (the 06.B spec section 1.2 records zero hits). A null
     * result is treated as "cannot verify" by the export use case, which proceeds and relies on
     * the write-side failure handling instead of blocking on an unknown value.
     */
    fun usableSpace(path: String): Long?

    /**
     * P7-06 06.B (D-177; spec section 5): opens a bounded, chunked read stream over [path]. The
     * export must never call [readBytes] on the snapshot or the container — both can be ~2 GiB —
     * so the shared writer streams through this port in fixed 64 KiB chunks. Callers must close
     * the returned stream.
     */
    fun openRead(path: String): LedgerReadStream

    /**
     * P7-06 06.B (D-177; spec section 3.5 phase 2): opens a bounded, chunked write stream to
     * [path] with the atomic-rename semantics of [writeAtomic] — the bytes become visible at
     * [path] only after a clean [LedgerWriteStream.close], never a partially written file.
     *
     * This is deliberately NOT [writeAtomic]: that primitive takes the whole content as a
     * `ByteArray` (whole-file in memory), which the ~2 GiB container forbids. Adapters implement
     * the temp-file + platform atomic replace; where the target cannot be atomically replaced the
     * adapter may write in place and rely on the stream's clean close (spec section 3.5).
     */
    fun openWrite(path: String): LedgerWriteStream

    /**
     * P7-06 06.B (D-177; spec section 6): the child entry names directly under [path], used only
     * by the private-staging sweep. Returns an empty list when the directory does not exist or
     * cannot be listed — the sweep is a best-effort cleanup (the spec forbids claiming a secure
     * erase, and a cleanup failure must never fail startup).
     */
    fun listDirectory(path: String): List<String>
}

/** A bounded read stream over a file (P7-06 06.B; spec section 5). */
interface LedgerReadStream : AutoCloseable {
    /**
     * Reads at most `buffer.size` bytes into [buffer], returning the count read or a non-positive
     * value at end of stream (the `InputStream` convention). Never reads the whole file at once.
     */
    fun read(buffer: ByteArray): Int
}

/**
 * A bounded write stream to a file with atomic-rename semantics (P7-06 06.B; spec section 3.5
 * phase 2). The file at the target path is replaced atomically on [close]; a failure before
 * [close] leaves the previous content untouched.
 */
interface LedgerWriteStream : AutoCloseable {
    fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    )

    /** Flushes and fsyncs the staged content where the platform supports it. */
    fun flushAndSync()

    /** Atomically publishes the staged content; must be called exactly once. */
    fun commit()

    /** Abandons the staged content without publishing it. Idempotent and safe after [commit]. */
    override fun close()
}

/** The stable-storage layout under one platform-resolved host directory (section 3). */
class LedgerStorageLayout internal constructor(
    private val fileSystem: LedgerFileSystem,
    /** The platform-resolved, cross-process-stable host directory (never a tracked literal). */
    val hostDirectory: String,
) {
    val generationsDirectory: String = fileSystem.join(hostDirectory, LEDGER_GENERATIONS_DIRECTORY)

    val activePointerFile: String = fileSystem.join(hostDirectory, LEDGER_ACTIVE_POINTER_FILE)

    val switchJournalFile: String = fileSystem.join(hostDirectory, LEDGER_SWITCH_JOURNAL_FILE)

    /**
     * P7-06 06.B (D-177; spec section 6): the private backup staging directory. Snapshot,
     * staging container and any plaintext live only here, under the app-private host directory
     * (container-format spec section 4.9).
     */
    val backupStagingDirectory: String = fileSystem.join(hostDirectory, LEDGER_BACKUP_STAGING_DIRECTORY)

    /** The snapshot file for one export [token] (a per-export unique suffix). */
    fun backupSnapshotFile(token: String): String = fileSystem.join(backupStagingDirectory, LEDGER_BACKUP_SNAPSHOT_PREFIX + token)

    /** The private staging container file for one export [token]. */
    fun backupContainerFile(token: String): String = fileSystem.join(backupStagingDirectory, LEDGER_BACKUP_CONTAINER_PREFIX + token)

    /**
     * P7-06 06.C (D-179; spec section 6.1): the restore preflight's private staging container copy
     * (the user-chosen source copied once, so later reads never touch a replaceable external file).
     */
    fun restoreContainerFile(token: String): String = fileSystem.join(backupStagingDirectory, "restore-container-$token")

    /** The restore preflight's decrypted plaintext snapshot (spec section 3.5). */
    fun restoreSnapshotFile(token: String): String = fileSystem.join(backupStagingDirectory, "restore-snapshot-$token")

    /** The restore preflight's isolated migration copy (spec section 3.9/6.1). */
    fun restoreMigratedFile(token: String): String = fileSystem.join(backupStagingDirectory, "restore-migrated-$token")

    fun generationDirectoryName(generation: Int): String = "$LEDGER_GENERATION_PREFIX$generation"

    fun generationDirectory(generation: Int): String = fileSystem.join(generationsDirectory, generationDirectoryName(generation))

    fun mainFile(generationDirectory: String): String = fileSystem.join(generationDirectory, LEDGER_MAIN_FILE_NAME)

    fun sidecarFile(
        generationDirectory: String,
        suffix: String,
    ): String = fileSystem.join(generationDirectory, LEDGER_MAIN_FILE_NAME + suffix)
}

/** Builds the layout for a platform-resolved host directory. */
fun ledgerStorageLayout(
    fileSystem: LedgerFileSystem,
    hostDirectory: String,
): LedgerStorageLayout = LedgerStorageLayout(fileSystem, hostDirectory)

/** The startup plan the resolution rules select (section 3.2 rules 1-3). */
sealed interface LedgerStoragePlan {
    /** An existing, valid active generation selected through the pointer. */
    data class OpenGeneration(
        val generation: Int,
        val generationDirectory: String,
        val mainFile: String,
    ) : LedgerStoragePlan

    /** No generation directory and no legacy database: the only path allowed to create (rule 3). */
    data class FreshInstall(
        val generationDirectory: String,
        val mainFile: String,
    ) : LedgerStoragePlan

    /** A legacy database exists without a generation directory: the non-destructive upgrade (rule 1). */
    data class UpgradeLegacy(
        val legacyMainFile: String,
        val generationDirectory: String,
        val mainFile: String,
    ) : LedgerStoragePlan
}

/** The typed reasons the stable-storage resolution fails closed (section 3.2 rule 4, section 4.5). */
enum class LedgerStorageFailure {
    /** A journal file exists; 06.1 cannot roll back and must refuse (section 5.1 step 2). */
    JOURNAL_PRESENT,

    /** The generations directory exists but the active pointer is absent (rule 4). */
    POINTER_MISSING,

    /** The generations directory exists but the pointer does not name a generation (rule 4). */
    POINTER_INVALID,

    /** The pointer names a generation whose directory or main file is absent (section 4.5). */
    ACTIVE_GENERATION_MISSING,

    /** The active main file exists but is zero-length or lacks a valid SQLite header (section 4.5). */
    ACTIVE_GENERATION_UNUSABLE,
}

/** The resolution outcome. */
sealed interface LedgerStorageResolution {
    data class Planned(
        val plan: LedgerStoragePlan,
    ) : LedgerStorageResolution

    data class Rejected(
        val failure: LedgerStorageFailure,
    ) : LedgerStorageResolution
}

/**
 * Section 4.5 silent-empty-database prohibition, mechanism (a): a target main file is usable only
 * when it exists, is non-empty and starts with the SQLite file magic. A zero-length or
 * structurally invalid file must be REJECTED here — never handed to the create-on-open factory,
 * which would silently build an empty schema (the FOUND-001 class of defect the container-format
 * spec section 5.1 forbids).
 *
 * Public because the composition roots apply the same guard immediately before their own
 * platform driver construction (the desktop JDBC driver and the Android SQLite driver both create
 * on open), so the boundary is enforced at the platform seam as well as in the shared sequence.
 */
fun isUsableSqliteMainFile(
    fileSystem: LedgerFileSystem,
    mainFile: String,
): Boolean {
    if (!fileSystem.exists(mainFile)) return false
    val length = fileSystem.length(mainFile)
    if (length < LEDGER_SQLITE_HEADER.size.toLong()) return false
    // Read ONLY the header prefix, never the whole ledger (review Fix 6): the startup path must
    // not allocate a multi-hundred-megabyte file just to inspect 16 bytes.
    val header = fileSystem.readPrefix(mainFile, LEDGER_SQLITE_HEADER.size)
    if (header.size < LEDGER_SQLITE_HEADER.size) return false
    return LEDGER_SQLITE_HEADER.indices.all { index -> header[index] == LEDGER_SQLITE_HEADER[index] }
}

/**
 * P7-06 06.D (D-182; spec `docs/specs/2026-09-26-p7-06-restore-confirm-switch-design.md` section 3
 * step 8, section 3.8, section 4.3): opens an EXPLICITLY named on-disk generation DIRECTLY — no
 * startup resolution, no pointer lookup and, crucially, NO switch-journal gate.
 *
 * This is what the confirm & switch step-8 reopen needs. The spec's step-8 line states
 * `reopen(ActivePointer)`, but that is unimplementable here as written: step 6 already staged,
 * fsynced and locally gated `gen-(n+1)` and step 7 already published the pointer to it while the
 * `switched` journal is DELIBERATELY still present (it is removed only after the read-back
 * succeeds, section 3.9). Running the normal startup sequence would execute
 * `resolveLedgerStorage`'s journal gate (`recoverSwitchJournalAtStartup`) on this flow's OWN
 * `switched` journal: it republishes the OLD pointer and deletes the NEW generation directory, so
 * the switch would roll itself back while still reporting success. The step-8 reopen therefore
 * selects the published generation explicitly.
 *
 * Only a caller that has JUST staged and published [generation] may use this. Startup
 * ([openStableStorageLedger]), the section 3.10 restoration reopen and the section 3.8 rollback
 * reopen all keep selecting through [GenerationSelection.ActivePointer], so a crashed session's
 * `prepared`/`switched` journal still rolls back at startup exactly as the frozen restart rule
 * (container-format spec section 5.3) requires.
 *
 * The same pre-open consistency gate as every non-fresh startup path applies: a named generation
 * that is missing, empty or header-less fails closed with
 * [LedgerStorageFailure.ACTIVE_GENERATION_UNUSABLE] instead of reaching a create-on-open factory
 * (the silent-empty-database prohibition, D-176).
 */
fun <G> openExplicitGeneration(
    fileSystem: LedgerFileSystem,
    layout: LedgerStorageLayout,
    generation: Int,
    openGraph: (LedgerOpenTarget) -> G,
): G {
    val directory = layout.generationDirectory(generation)
    val mainFile = layout.mainFile(directory)
    if (!isUsableSqliteMainFile(fileSystem, mainFile)) {
        throw LedgerStorageRejectedException(LedgerStorageFailure.ACTIVE_GENERATION_UNUSABLE)
    }
    return openGraph(LedgerOpenTarget(mainFile, allowCreateOnOpen = false))
}

/**
 * Section 5.1 startup order, steps 1-3: resolve the active generation. The journal check runs
 * FIRST (step 2). As of 06.D (D-182; spec section 4.3) the branch is EXTENDED from 06.1's
 * unconditional fail-closed gate: a RECOGNIZABLE journal (a versioned [LedgerSwitchJournal]) is
 * rolled back per the frozen ROLLBACK restart rule and startup then proceeds through the restored
 * old-generation pointer; an unrecognizable/unparseable journal (including a read failure) keeps
 * the frozen [LedgerStorageFailure.JOURNAL_PRESENT] semantics. So a present journal CAN now be
 * parsed and removed — it is no longer only refused.
 *
 * [legacyMainFile] is the platform's legacy product database path (Android `databases/ledger.db`),
 * or null when the platform has no migratable legacy location (desktop, section 3.3).
 */
internal fun resolveLedgerStorage(
    fileSystem: LedgerFileSystem,
    layout: LedgerStorageLayout,
    legacyMainFile: String?,
): LedgerStorageResolution {
    if (fileSystem.exists(layout.switchJournalFile)) {
        // 06.D journal gate (spec section 4.3): a recognizable journal recovers deterministically
        // (restart half of the frozen ROLLBACK rule, container-format spec section 5.3) and
        // startup then re-resolves through the restored old-generation pointer; anything
        // unrecognizable — including a read failure and any foreign format — keeps the frozen
        // fail-closed JOURNAL_PRESENT semantics of 06.1 (spec section 5.1 step 2).
        val journal =
            try {
                parseLedgerSwitchJournal(fileSystem.readBytes(layout.switchJournalFile))
            } catch (failure: Error) {
                throw failure
            } catch (failure: Throwable) {
                null
            }
        if (journal == null || !recoverSwitchJournalAtStartup(fileSystem, layout, journal)) {
            // D-203 startup trace: resolution completed, fail-closed on the journal gate.
            StartupTrace.emit("storage.resolve end outcome=journalPresent")
            return LedgerStorageResolution.Rejected(LedgerStorageFailure.JOURNAL_PRESENT)
        }
    }

    val generationsDirectoryExists =
        fileSystem.exists(layout.generationsDirectory) && fileSystem.isDirectory(layout.generationsDirectory)
    if (generationsDirectoryExists) {
        // Rule 2/4: a generation directory exists, so the legacy location is never consulted
        // again; the pointer alone decides. Any invalid pointer fails closed and never falls back
        // to a fresh install (the (a)->(d) crash window of rule 1 must not become an empty ledger).
        if (!fileSystem.exists(layout.activePointerFile)) {
            // D-203 startup trace: resolution completed, fail-closed without an active pointer.
            StartupTrace.emit("storage.resolve end outcome=pointerMissing")
            return LedgerStorageResolution.Rejected(LedgerStorageFailure.POINTER_MISSING)
        }
        val generation = parsePointer(fileSystem.readBytes(layout.activePointerFile), layout)
        if (generation == null) {
            // D-203 startup trace: resolution completed, fail-closed on an invalid pointer.
            StartupTrace.emit("storage.resolve end outcome=pointerInvalid")
            return LedgerStorageResolution.Rejected(LedgerStorageFailure.POINTER_INVALID)
        }
        val directory = layout.generationDirectory(generation)
        val mainFile = layout.mainFile(directory)
        if (!fileSystem.exists(directory) || !fileSystem.isDirectory(directory) || !fileSystem.exists(mainFile)) {
            // D-203 startup trace: resolution completed, fail-closed on a missing generation.
            StartupTrace.emit("storage.resolve end outcome=activeGenerationMissing")
            return LedgerStorageResolution.Rejected(LedgerStorageFailure.ACTIVE_GENERATION_MISSING)
        }
        if (!isUsableSqliteMainFile(fileSystem, mainFile)) {
            // D-203 startup trace: resolution completed, fail-closed on an unusable main file.
            StartupTrace.emit("storage.resolve end outcome=activeGenerationUnusable")
            return LedgerStorageResolution.Rejected(LedgerStorageFailure.ACTIVE_GENERATION_UNUSABLE)
        }
        // D-203 startup trace: resolution completed, a generation open is planned.
        StartupTrace.emit("storage.resolve end outcome=plannedGeneration")
        return LedgerStorageResolution.Planned(LedgerStoragePlan.OpenGeneration(generation, directory, mainFile))
    }

    // No generation directory at all. Rule 1: a legacy database must be upgraded, never treated
    // as an empty install. Rule 3: only when neither exists is this a genuine fresh install.
    val freshDirectory = layout.generationDirectory(1)
    if (legacyMainFile != null && fileSystem.exists(legacyMainFile)) {
        // D-203 startup trace: resolution completed, a legacy upgrade is planned.
        StartupTrace.emit("storage.resolve end outcome=plannedUpgradeLegacy")
        return LedgerStorageResolution.Planned(
            LedgerStoragePlan.UpgradeLegacy(legacyMainFile, freshDirectory, layout.mainFile(freshDirectory)),
        )
    }
    // D-203 startup trace: resolution completed, a fresh install is planned.
    StartupTrace.emit("storage.resolve end outcome=plannedFreshInstall")
    return LedgerStorageResolution.Planned(
        LedgerStoragePlan.FreshInstall(freshDirectory, layout.mainFile(freshDirectory)),
    )
}

/**
 * Parses the active pointer content. The pointer holds exactly one generation directory name
 * (`gen-<n>`); anything else is invalid. The generation number is not otherwise trusted — the
 * caller re-derives the directory from it, so a pointer naming a generation outside the
 * enumerable set resolves to a missing directory and fails closed.
 *
 * 06.D visibility note (spec section 1.1 P3-2, the recommended option): relaxed from `private`
 * to `internal` so the confirm/switch use case can read the current disk generation for the
 * `gen-(current + 1)` numbering and the peak-disk precheck, without adding any public API.
 */
internal fun parsePointer(
    bytes: ByteArray,
    layout: LedgerStorageLayout,
): Int? {
    val name = bytes.decodeToString().trim()
    val number = parseGenerationName(name) ?: return null
    if (layout.generationDirectoryName(number) != name) return null
    return number
}

/** Strictly parses one `gen-<n>` name with `n >= 1`; anything else is null. */
internal fun parseGenerationName(name: String): Int? {
    if (!name.startsWith(LEDGER_GENERATION_PREFIX)) return null
    val number = name.removePrefix(LEDGER_GENERATION_PREFIX).toIntOrNull() ?: return null
    if (number < 1) return null
    return number
}

/** The pointer bytes for one generation. */
internal fun ledgerPointerBytes(generation: Int): ByteArray = "$LEDGER_GENERATION_PREFIX$generation".encodeToByteArray()

/**
 * Section 3.2 rule 1 steps (a)-(b): stage the legacy database into the new generation directory
 * as one consistent set (main file plus whichever `-wal`/`-shm` sidecars exist), then flush and
 * fsync the staged files and the generation directory entry BEFORE the pointer is published.
 *
 * The legacy files stay untouched throughout: this function copies, never moves. A throw from
 * any operation propagates so the caller fails closed with the legacy set still intact.
 */
internal fun stageLegacyUpgrade(
    fileSystem: LedgerFileSystem,
    legacyMainFile: String,
    targetDirectory: String,
    targetMainFile: String,
) {
    fileSystem.createDirectories(targetDirectory)
    fileSystem.copy(legacyMainFile, targetMainFile)
    for (suffix in LEDGER_SIDECAR_SUFFIXES) {
        val legacySidecar = legacyMainFile + suffix
        if (fileSystem.exists(legacySidecar)) {
            fileSystem.copy(legacySidecar, fileSystem.join(targetDirectory, LEDGER_MAIN_FILE_NAME + suffix))
        }
    }
    // (b) Persist the new set before the pointer can reference it: fsync each staged file, then
    // the generation directory entry. Deferring these to after the pointer publish would leave a
    // power-loss window where the pointer is durable but the new generation is only partially on
    // disk (spec section 3.2, NEW-1).
    fileSystem.fsyncFile(targetMainFile)
    for (suffix in LEDGER_SIDECAR_SUFFIXES) {
        val stagedSidecar = fileSystem.join(targetDirectory, LEDGER_MAIN_FILE_NAME + suffix)
        if (fileSystem.exists(stagedSidecar)) {
            fileSystem.fsyncFile(stagedSidecar)
        }
    }
    fileSystem.fsyncDirectory(targetDirectory)
}

/**
 * Section 3.2 rule 1 step (d) / container-format spec section 5.3: publish the atomic active
 * pointer and fsync the directory holding it, so the pointer replacement itself is durable.
 */
internal fun publishActivePointer(
    fileSystem: LedgerFileSystem,
    layout: LedgerStorageLayout,
    generation: Int,
) {
    fileSystem.writeAtomic(layout.activePointerFile, ledgerPointerBytes(generation))
    fileSystem.fsyncFile(layout.activePointerFile)
    fileSystem.fsyncDirectory(layout.hostDirectory)
}

/**
 * Section 3.2 rule 1 step (e): only after the pointer is durable may the legacy set be removed.
 * Sidecars are removed with the main file so no stale `-wal` can be picked up by a later open of
 * the legacy name. The deletion set is the superset [LEDGER_DELETABLE_SIDECAR_SUFFIXES]: a transient
 * `-journal` left by a read-write open must not survive its main file either (06.D defect 2, D-183).
 */
internal fun removeLegacyFiles(
    fileSystem: LedgerFileSystem,
    legacyMainFile: String,
) {
    if (fileSystem.exists(legacyMainFile)) {
        fileSystem.delete(legacyMainFile)
    }
    for (suffix in LEDGER_DELETABLE_SIDECAR_SUFFIXES) {
        val legacySidecar = legacyMainFile + suffix
        if (fileSystem.exists(legacySidecar)) {
            fileSystem.delete(legacySidecar)
        }
    }
}

/**
 * P7-06 06.B/06.C (D-177/D-179; spec sections 3.7/6 and 6.5): the private-staging sweep run at
 * startup. Every export/preflight cleans its own artifacts in a `finally`, but a process killed
 * mid-operation leaves them behind; the spec designates "next start" as the cleanup fallback. This
 * deletes only files carrying the frozen staging prefixes, so it can never touch a generation, the
 * pointer, a journal or any other ledger state.
 *
 * 06.C (D-179 spec section 6.5) extends the prefix set with [LEDGER_RESTORE_STAGING_PREFIX] so a
 * killed preflight's plaintext snapshot is also cleaned. That extension is safe ONLY together with
 * the preflight's whole-duration operation lease (spec section 7.3): `reopen` returns
 * `QuiesceBlocked` while a preflight holds a lease, so this destructive sweep cannot run mid-preflight
 * and cannot delete the live preflight's own authenticated artifacts (a dangling token).
 *
 * Best effort by contract: a listing/deletion failure is swallowed (the spec forbids claiming a
 * secure erase, and cleanup must never turn a good startup into a failure).
 */
internal fun sweepBackupStaging(
    fileSystem: LedgerFileSystem,
    layout: LedgerStorageLayout,
) {
    runCatching {
        for (name in fileSystem.listDirectory(layout.backupStagingDirectory)) {
            if (name.startsWith(LEDGER_BACKUP_SNAPSHOT_PREFIX) ||
                name.startsWith(LEDGER_BACKUP_CONTAINER_PREFIX) ||
                name.startsWith(LEDGER_RESTORE_STAGING_PREFIX)
            ) {
                runCatching { fileSystem.delete(fileSystem.join(layout.backupStagingDirectory, name)) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// P7-06 06.D (D-182; spec `docs/specs/2026-09-26-p7-06-restore-confirm-switch-design.md`
// section 4): the persistent switch journal — the in-process instantiation of the frozen
// `prepared -> switched -> committed` machine of the container-format spec section 5.3.
//
// The journal content is deliberately minimal and versioned (the spec section 4.2 leaves the
// encoding to the implementation batch): a fixed version header line, the stage, and the OLD and
// NEW disk generation numbers. Recording BOTH generations is what makes the restart rollback
// deterministic: the OLD generation is the rollback anchor to republish, the NEW one is the
// staged directory to discard. Anything unparseable keeps the fail-closed JOURNAL_PRESENT gate.
// ---------------------------------------------------------------------------

/** The persisted stages of the switch journal (container-format spec section 5.3). */
internal enum class LedgerSwitchJournalStage {
    /** The new generation is fully staged and fsynced; the pointer has NOT been switched. */
    Prepared,

    /** The pointer has been switched to the new generation; the new graph is not confirmed open. */
    Switched,
}

/** One persisted switch journal record (06.D spec section 4.2: stage + old/new disk generation). */
internal class LedgerSwitchJournal(
    val stage: LedgerSwitchJournalStage,
    /** The disk generation the pointer named before the switch — the rollback anchor. */
    val oldGeneration: Int,
    /** The disk generation the switch staged and (for [LedgerSwitchJournalStage.Switched]) published. */
    val newGeneration: Int,
)

/** The fixed, versioned first line of the journal; any other header is unrecognizable. */
private const val LEDGER_SWITCH_JOURNAL_HEADER = "unified-ledger switch journal v1"

/** The journal bytes for one record. Written only through the atomic [LedgerFileSystem.writeAtomic]. */
internal fun ledgerSwitchJournalBytes(journal: LedgerSwitchJournal): ByteArray =
    listOf(
        LEDGER_SWITCH_JOURNAL_HEADER,
        "stage=" +
            when (journal.stage) {
                LedgerSwitchJournalStage.Prepared -> "prepared"
                LedgerSwitchJournalStage.Switched -> "switched"
            },
        "old=$LEDGER_GENERATION_PREFIX${journal.oldGeneration}",
        "new=$LEDGER_GENERATION_PREFIX${journal.newGeneration}",
    ).joinToString("\n").encodeToByteArray()

/**
 * Strictly parses journal bytes; null for ANY foreign or damaged content, which keeps the
 * frozen fail-closed JOURNAL_PRESENT gate (06.D spec section 4.2). The new generation must be
 * exactly the old one + 1 (the only shape the confirm flow ever writes).
 */
internal fun parseLedgerSwitchJournal(bytes: ByteArray): LedgerSwitchJournal? {
    val lines = bytes.decodeToString().lines().map { it.trim() }
    if (lines.size != 4 || lines[0] != LEDGER_SWITCH_JOURNAL_HEADER) return null
    val stage =
        when (lines[1]) {
            "stage=prepared" -> LedgerSwitchJournalStage.Prepared
            "stage=switched" -> LedgerSwitchJournalStage.Switched
            else -> return null
        }
    val oldGeneration = parseGenerationName(lines[2].removePrefix("old=")) ?: return null
    val newGeneration = parseGenerationName(lines[3].removePrefix("new=")) ?: return null
    if (newGeneration != oldGeneration + 1) return null
    return LedgerSwitchJournal(stage, oldGeneration, newGeneration)
}

/**
 * Deletes one generation directory as one consistent set: the main file, every deletable sidecar
 * ([LEDGER_DELETABLE_SIDECAR_SUFFIXES] — `-wal`/`-shm` plus the transient `-journal`), then the
 * directory entry — the same sidecar discipline as [removeLegacyFiles] (a stale `-wal` must never
 * survive its main file).
 *
 * STRICT (06.D device-gate defect 2, D-183): a deletion failure throws, and — the part that was
 * missing — the function VERIFIES the directory is actually gone before returning. The previous
 * version deleted only `-wal`/`-shm`; a `-journal` left by a read-write framework open kept the
 * directory non-empty, so the platform adapters' `delete` (`File.delete()` ignoring its boolean)
 * silently no-opped and the generation directory SURVIVED while this function reported success. That
 * made `BackupRestoreRecovery.discardUnvalidatableAndReUpgrade` return `DiscardedAwaitingUpgrade`
 * while the generations directory still existed, which per spec section 5.3 / the 06.1 rule keeps
 * `POINTER_MISSING` fail-closed FOREVER — the discard-and-re-upgrade branch was useless on Android.
 *
 * Callers that only need a best-effort cleanup wrap this in `runCatching` (rollback, journal
 * recovery); callers that depend on the deletion (the discard branch, the confirm-time
 * delete-then-stage) observe the throw and route to their typed failure.
 */
internal fun deleteGenerationDirectory(
    fileSystem: LedgerFileSystem,
    layout: LedgerStorageLayout,
    generation: Int,
) {
    val directory = layout.generationDirectory(generation)
    if (!fileSystem.exists(directory)) return
    val mainFile = layout.mainFile(directory)
    if (fileSystem.exists(mainFile)) {
        fileSystem.delete(mainFile)
    }
    for (suffix in LEDGER_DELETABLE_SIDECAR_SUFFIXES) {
        val sidecar = layout.sidecarFile(directory, suffix)
        if (fileSystem.exists(sidecar)) {
            fileSystem.delete(sidecar)
        }
    }
    if (fileSystem.exists(directory)) {
        fileSystem.delete(directory)
    }
    // The KDoc's "strict" claim is only true if the directory is actually gone: a platform `delete`
    // that silently no-ops (a surviving sidecar, a permission failure) must fail loud here, so the
    // discard branch can never report a success it did not achieve (defect 2).
    if (fileSystem.exists(directory)) {
        throw LedgerGenerationDirectoryDeleteException(LedgerGenerationDirectoryKind.GENERATION_DIRECTORY)
    }
}

/**
 * Which directory a [LedgerGenerationDirectoryDeleteException] found still present. A typed code,
 * not a path: this is a commonMain exception, and the platform host paths it would otherwise embed
 * are machine-specific (the same reason [LedgerStorageRejectedException] carries an enum rather than
 * a location). The two codes are the two deletion sites this strictness covers.
 */
enum class LedgerGenerationDirectoryKind {
    /** One `gen-<n>` generation directory (the [deleteGenerationDirectory] contract). */
    GENERATION_DIRECTORY,

    /** The `generations/` parent directory (the discard branch's final emptied-parent check). */
    GENERATIONS_DIRECTORY,
}

/**
 * The typed failure of a structured generation-directory deletion (06.D device-gate defect 2,
 * D-183): the directory survived the deletion attempt, so a caller that depends on its absence (the
 * discard-and-re-upgrade branch, the confirm-time delete-then-stage precondition) must fail closed
 * instead of reporting success. [target] names WHICH directory survived as a typed code; the message
 * deliberately carries no runtime path (DG-3, D-183).
 */
class LedgerGenerationDirectoryDeleteException(
    val target: LedgerGenerationDirectoryKind,
) : RuntimeException("generation directory survived deletion: $target")

/**
 * 06.D spec section 4.2/4.3: the restart half of the frozen ROLLBACK rule (container-format spec
 * section 5.3). Both in-process rollback and restart recovery are executors of the SAME rule, in
 * the same order: republish the OLD pointer through the frozen [publishActivePointer] primitive
 * (unconditionally for `switched`; for `prepared` only when the pointer already moved — the crash
 * window between the pointer publish and the `switched` journal write), remove the journal, then
 * discard the staged NEW generation directory including its sidecars.
 *
 * The old generation is never touched (it is the rollback anchor), so the silent-empty-database
 * prohibition (D-176) is untouched: every recovery path re-anchors on an existing old generation.
 * A republish or journal-removal failure returns false so startup keeps failing closed with the
 * journal still in place — the next start retries deterministically from the same journal. The
 * new-generation deletion is best effort by contract (spec section 4.2): a deletion failure never
 * blocks the pointer/journal recovery and is left to the retention policy.
 *
 * Returns true when the recovery completed and startup may re-resolve through the pointer.
 */
private fun recoverSwitchJournalAtStartup(
    fileSystem: LedgerFileSystem,
    layout: LedgerStorageLayout,
    journal: LedgerSwitchJournal,
): Boolean {
    val pointerGeneration =
        if (fileSystem.exists(layout.activePointerFile)) {
            try {
                parsePointer(fileSystem.readBytes(layout.activePointerFile), layout)
            } catch (failure: Error) {
                throw failure
            } catch (failure: Throwable) {
                null
            }
        } else {
            null
        }
    if (journal.stage == LedgerSwitchJournalStage.Switched || pointerGeneration != journal.oldGeneration) {
        try {
            publishActivePointer(fileSystem, layout, journal.oldGeneration)
        } catch (failure: Error) {
            throw failure
        } catch (failure: Throwable) {
            return false
        }
    }
    try {
        fileSystem.delete(layout.switchJournalFile)
        // The deletion is fsynced like every journal write is (the confirm-side durability
        // discipline): a recovery that finished must not be resurrected by a crash the host
        // directory has not yet forgotten.
        fileSystem.fsyncDirectory(layout.hostDirectory)
    } catch (failure: Error) {
        throw failure
    } catch (failure: Throwable) {
        return false
    }
    runCatching { deleteGenerationDirectory(fileSystem, layout, journal.newGeneration) }
    return true
}
