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
 * The persistent switch journal file. 06.1 never writes one (the `prepared -> switched ->
 * committed` machine belongs to 06.D); a present journal is a 06.1 startup gate (section 5.1
 * step 2) and always fails closed.
 */
internal const val LEDGER_SWITCH_JOURNAL_FILE = "switch-journal"

/** The main database file name inside one generation directory. */
internal const val LEDGER_MAIN_FILE_NAME = "ledger.db"

/** The generation directory name prefix; the full name is `gen-<n>`. */
internal const val LEDGER_GENERATION_PREFIX = "gen-"

/** The SQLite sidecar suffixes that must travel with the main file as one consistent set. */
internal val LEDGER_SIDECAR_SUFFIXES: List<String> = listOf("-wal", "-shm")

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
 * Section 5.1 startup order, steps 1-3: resolve the active generation. The journal check runs
 * FIRST (step 2) and is a hard fail-closed gate in 06.1 — the full `prepared/switched/committed`
 * machine belongs to 06.D, so a present journal can only be refused, never parsed or removed.
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
        return LedgerStorageResolution.Rejected(LedgerStorageFailure.JOURNAL_PRESENT)
    }

    val generationsDirectoryExists =
        fileSystem.exists(layout.generationsDirectory) && fileSystem.isDirectory(layout.generationsDirectory)
    if (generationsDirectoryExists) {
        // Rule 2/4: a generation directory exists, so the legacy location is never consulted
        // again; the pointer alone decides. Any invalid pointer fails closed and never falls back
        // to a fresh install (the (a)->(d) crash window of rule 1 must not become an empty ledger).
        if (!fileSystem.exists(layout.activePointerFile)) {
            return LedgerStorageResolution.Rejected(LedgerStorageFailure.POINTER_MISSING)
        }
        val generation = parsePointer(fileSystem.readBytes(layout.activePointerFile), layout)
        if (generation == null) {
            return LedgerStorageResolution.Rejected(LedgerStorageFailure.POINTER_INVALID)
        }
        val directory = layout.generationDirectory(generation)
        val mainFile = layout.mainFile(directory)
        if (!fileSystem.exists(directory) || !fileSystem.isDirectory(directory) || !fileSystem.exists(mainFile)) {
            return LedgerStorageResolution.Rejected(LedgerStorageFailure.ACTIVE_GENERATION_MISSING)
        }
        if (!isUsableSqliteMainFile(fileSystem, mainFile)) {
            return LedgerStorageResolution.Rejected(LedgerStorageFailure.ACTIVE_GENERATION_UNUSABLE)
        }
        return LedgerStorageResolution.Planned(LedgerStoragePlan.OpenGeneration(generation, directory, mainFile))
    }

    // No generation directory at all. Rule 1: a legacy database must be upgraded, never treated
    // as an empty install. Rule 3: only when neither exists is this a genuine fresh install.
    val freshDirectory = layout.generationDirectory(1)
    if (legacyMainFile != null && fileSystem.exists(legacyMainFile)) {
        return LedgerStorageResolution.Planned(
            LedgerStoragePlan.UpgradeLegacy(legacyMainFile, freshDirectory, layout.mainFile(freshDirectory)),
        )
    }
    return LedgerStorageResolution.Planned(
        LedgerStoragePlan.FreshInstall(freshDirectory, layout.mainFile(freshDirectory)),
    )
}

/**
 * Parses the active pointer content. The pointer holds exactly one generation directory name
 * (`gen-<n>`); anything else is invalid. The generation number is not otherwise trusted — the
 * caller re-derives the directory from it, so a pointer naming a generation outside the
 * enumerable set resolves to a missing directory and fails closed.
 */
private fun parsePointer(
    bytes: ByteArray,
    layout: LedgerStorageLayout,
): Int? {
    val name = bytes.decodeToString().trim()
    if (!name.startsWith(LEDGER_GENERATION_PREFIX)) return null
    val number = name.removePrefix(LEDGER_GENERATION_PREFIX).toIntOrNull() ?: return null
    if (number < 1) return null
    if (layout.generationDirectoryName(number) != name) return null
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
 * the legacy name.
 */
internal fun removeLegacyFiles(
    fileSystem: LedgerFileSystem,
    legacyMainFile: String,
) {
    if (fileSystem.exists(legacyMainFile)) {
        fileSystem.delete(legacyMainFile)
    }
    for (suffix in LEDGER_SIDECAR_SUFFIXES) {
        val legacySidecar = legacyMainFile + suffix
        if (fileSystem.exists(legacySidecar)) {
            fileSystem.delete(legacySidecar)
        }
    }
}
