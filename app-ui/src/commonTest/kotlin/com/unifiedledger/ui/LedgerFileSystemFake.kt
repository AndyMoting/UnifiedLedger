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

    /** Overrides for [length], so the plaintext gates can be exercised without huge payloads. */
    val lengthOverrides = mutableMapOf<String, Long>()

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

    override fun length(path: String): Long = lengthOverrides[path] ?: files[path]?.size?.toLong() ?: 0L

    override fun readBytes(path: String): ByteArray {
        readBytesCallCount += 1
        return files[path]?.copyOf() ?: throw InjectedFileSystemFailure("readBytes:$path")
    }

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

    // ------------------------------------------------------------ P7-06 06.B additions

    /**
     * P7-06 06.B: the reported available space, or null when unset (the export then skips the
     * disk precheck). Tests set it to a small value to exercise the insufficient-space rejection.
     */
    var usableSpaceBytes: Long? = null

    /** The largest buffer size passed to [openRead]; proves the export never reads whole files. */
    var maxReadBufferSize: Int = 0

    /** The number of [openRead] calls; the two-pass writer must open the snapshot twice. */
    var openReadCount: Int = 0

    /** Whether [readBytes] (the whole-file primitive) was ever called; the export must never. */
    var readBytesCallCount: Int = 0

    /** The bytes delivered to the user target through [openWrite]/commit, in order. */
    val deliveredTargets = mutableMapOf<String, ByteArray>()

    /** When true, [openWrite] throws — the container-write failure injection. */
    var failOnOpenWrite = false

    override fun usableSpace(path: String): Long? = usableSpaceBytes

    override fun openRead(path: String): LedgerReadStream {
        record("openRead:$path")
        openReadCount += 1
        val bytes = files[path] ?: throw InjectedFileSystemFailure("openRead:$path")
        var position = 0
        return object : LedgerReadStream {
            override fun read(buffer: ByteArray): Int {
                maxReadBufferSize = maxOf(maxReadBufferSize, buffer.size)
                if (position >= bytes.size) return -1
                val count = minOf(buffer.size, bytes.size - position)
                bytes.copyInto(buffer, destinationOffset = 0, startIndex = position, endIndex = position + count)
                position += count
                return count
            }

            override fun close() {
                record("closeRead:$path")
            }
        }
    }

    override fun openWrite(path: String): LedgerWriteStream {
        record("openWrite:$path")
        if (failOnOpenWrite) throw InjectedFileSystemFailure("openWrite:$path")
        val staged = ArrayList<Byte>()
        var committed = false
        return object : LedgerWriteStream {
            override fun write(
                bytes: ByteArray,
                offset: Int,
                length: Int,
            ) {
                for (index in offset until offset + length) staged += bytes[index]
            }

            override fun flushAndSync() {
                record("flushAndSync:$path")
            }

            override fun commit() {
                record("commit:$path")
                files[path] = staged.toByteArray()
                parentOf(path)?.let { directories += it }
                committed = true
            }

            override fun close() {
                if (!committed) {
                    // Abandon the staged content; nothing becomes visible at the target.
                    files.remove(path)
                }
            }
        }
    }

    override fun listDirectory(path: String): List<String> {
        record("listDirectory:$path")
        val prefix = if (path.endsWith("/")) path else "$path/"
        return (files.keys + directories)
            .filter { it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
            .map { it.removePrefix(prefix) }
            .sorted()
    }

    /** Records a delivered user-target write (the platform target port's commit). */
    fun recordDeliveredTarget(
        path: String,
        bytes: ByteArray,
    ) {
        deliveredTargets[path] = bytes
    }
}

/** A deterministic, single-operation failure injection. */
internal class InjectedFileSystemFailure(
    val operation: String,
) : RuntimeException("injected file-system failure at $operation")

/** A minimal valid SQLite main-file payload (magic header plus one page of filler). */
internal fun sqliteLikeBytes(filler: Byte = 0): ByteArray = LEDGER_SQLITE_HEADER + ByteArray(4096) { filler }

/**
 * P7-06 06.B (D-177): a no-op [com.unifiedledger.application.backup.BackupCryptoPrimitives] for the
 * owner/lease tests, which only need a constructed [BackupExportUseCase] and never run its crypto.
 * Every method returns deterministic zero bytes; nothing here is a real cipher.
 */
internal fun noopBackupCrypto(): com.unifiedledger.application.backup.BackupCryptoPrimitives =
    object : com.unifiedledger.application.backup.BackupCryptoPrimitives {
        override fun randomBytes(count: Int): ByteArray = ByteArray(count)

        override fun deriveKey(
            password: CharArray,
            salt: ByteArray,
            iterations: Int,
            keyLengthBits: Int,
        ): ByteArray = ByteArray(keyLengthBits / 8)

        override fun sha256Digest(): com.unifiedledger.application.backup.BackupSha256Digest =
            object : com.unifiedledger.application.backup.BackupSha256Digest {
                override fun update(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) {}

                override fun digest(): ByteArray = ByteArray(32)
            }

        override fun gcmEncryptor(
            key: ByteArray,
            iv: ByteArray,
            aad: ByteArray,
        ): com.unifiedledger.application.backup.BackupGcmEncryptor =
            object : com.unifiedledger.application.backup.BackupGcmEncryptor {
                override fun update(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ): ByteArray = ByteArray(0)

                override fun doFinal(): ByteArray = ByteArray(16)
            }

        override fun gcmDecryptor(
            key: ByteArray,
            iv: ByteArray,
            aad: ByteArray,
        ): com.unifiedledger.application.backup.BackupGcmDecryptor =
            object : com.unifiedledger.application.backup.BackupGcmDecryptor {
                override fun update(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ): ByteArray = ByteArray(0)

                override fun doFinal(): ByteArray = ByteArray(0)
            }
    }
