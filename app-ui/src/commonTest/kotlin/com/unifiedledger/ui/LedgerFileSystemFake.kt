package com.unifiedledger.ui

/**
 * P7-06 06.1 (D-176): an in-memory, fault-injecting [LedgerFileSystem] for the stable-storage
 * tests. It records the exact operation sequence so the frozen upgrade ordering (copy -> fsync
 * new set -> open/read-back -> publish pointer -> remove legacy) can be asserted, and it can be
 * made to throw at any single operation so every step's fail-closed behaviour is exercised.
 *
 * Paths are POSIX-style (`/` joined); the fake never touches the real filesystem.
 */
internal class LedgerFileSystemFake : LedgerFileSystem {
    private val files = linkedMapOf<String, ByteArray>()
    private val directories = linkedSetOf<String>()

    /** The ordered operation log, e.g. `copy:/legacy.db->/gens/gen-1/ledger.db`. */
    val operations = mutableListOf<String>()

    /** When set, the matching operation label throws [InjectedFileSystemFailure]. */
    var failOn: String? = null

    /** The prefix lengths requested through [readPrefix], in order (review Fix 6 evidence). */
    val readPrefixLengths = mutableListOf<Int>()

    /** Operations applied so far (excluding the failing one). */
    fun operationsSnapshot(): List<String> = operations.toList()

    fun putFile(
        path: String,
        bytes: ByteArray,
    ) {
        files[path] = bytes
        parentOf(path)?.let { directories += it }
    }

    fun putFile(
        path: String,
        text: String,
    ) = putFile(path, text.encodeToByteArray())

    fun putDirectory(path: String) {
        directories += path
    }

    fun fileBytes(path: String): ByteArray? = files[path]

    fun hasFile(path: String): Boolean = files.containsKey(path)

    fun hasDirectory(path: String): Boolean = directories.contains(path)

    private fun parentOf(path: String): String? {
        val index = path.lastIndexOf('/')
        return if (index <= 0) null else path.substring(0, index)
    }

    private fun record(label: String) {
        if (failOn == label) throw InjectedFileSystemFailure(label)
        operations += label
    }

    override fun join(
        parent: String,
        child: String,
    ): String = if (parent.endsWith("/")) "$parent$child" else "$parent/$child"

    override fun exists(path: String): Boolean = files.containsKey(path) || directories.contains(path)

    override fun isDirectory(path: String): Boolean = directories.contains(path)

    override fun length(path: String): Long = files[path]?.size?.toLong() ?: 0L

    override fun readBytes(path: String): ByteArray = files[path]?.copyOf() ?: throw InjectedFileSystemFailure("readBytes:$path")

    override fun readPrefix(
        path: String,
        length: Int,
    ): ByteArray {
        record("readPrefix:$path")
        // Record the requested length so a test can prove only the header prefix was read.
        readPrefixLengths += length
        val bytes = files[path] ?: throw InjectedFileSystemFailure("readPrefix:$path")
        return bytes.copyOf(minOf(length, bytes.size))
    }

    override fun writeAtomic(
        path: String,
        bytes: ByteArray,
    ) {
        record("writeAtomic:$path")
        files[path] = bytes.copyOf()
        parentOf(path)?.let { directories += it }
    }

    override fun copy(
        source: String,
        target: String,
    ) {
        record("copy:$source->$target")
        files[target] = readBytes(source)
        parentOf(target)?.let { directories += it }
    }

    override fun delete(path: String) {
        record("delete:$path")
        files.remove(path)
        directories.remove(path)
    }

    override fun createDirectories(path: String) {
        record("createDirectories:$path")
        directories += path
        parentOf(path)?.let { directories += it }
    }

    override fun fsyncFile(path: String) {
        record("fsyncFile:$path")
    }

    override fun fsyncDirectory(path: String) {
        record("fsyncDirectory:$path")
    }
}

/** A deterministic, single-operation failure injection. */
internal class InjectedFileSystemFailure(
    val operation: String,
) : RuntimeException("injected file-system failure at $operation")

/** A minimal valid SQLite main-file payload (magic header plus one page of filler). */
internal fun sqliteLikeBytes(filler: Byte = 0): ByteArray = LEDGER_SQLITE_HEADER + ByteArray(4096) { filler }
