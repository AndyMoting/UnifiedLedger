package com.unifiedledger.desktop

import com.unifiedledger.ui.LedgerFileSystem
import com.unifiedledger.ui.LedgerReadStream
import com.unifiedledger.ui.LedgerWriteStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * P7-06 06.1 (D-176; spec section 3.3): the desktop implementation of the shared
 * [LedgerFileSystem] port. The atomic pointer publish is a temp-file write plus an atomic rename
 * (container-format spec section 5.3's desktop primitive); file content is fsynced through the
 * descriptor and the containing directory is fsynced where the platform supports it.
 */
internal class DesktopLedgerFileSystem : LedgerFileSystem {
    override fun join(
        parent: String,
        child: String,
    ): String = File(parent, child).path

    override fun exists(path: String): Boolean = File(path).exists()

    override fun isDirectory(path: String): Boolean = File(path).isDirectory

    override fun length(path: String): Long = File(path).length()

    override fun readBytes(path: String): ByteArray = File(path).readBytes()

    override fun readPrefix(
        path: String,
        length: Int,
    ): ByteArray {
        require(length >= 0) { "prefix length must not be negative" }
        RandomAccessFile(path, "r").use { file ->
            val buffer = ByteArray(length)
            val read = file.read(buffer, 0, length)
            return if (read < 0) ByteArray(0) else buffer.copyOf(read)
        }
    }

    override fun writeAtomic(
        path: String,
        bytes: ByteArray,
    ) {
        val target = File(path)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(temporary).use { stream ->
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (unsupported: AtomicMoveNotSupportedException) {
            // The platform filesystem cannot move atomically; the frozen rule is to fail closed
            // rather than silently fall back to a non-atomic replace of the active pointer.
            temporary.delete()
            throw unsupported
        }
    }

    override fun copy(
        source: String,
        target: String,
    ) {
        File(target).parentFile?.mkdirs()
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
    }

    override fun delete(path: String) {
        File(path).delete()
    }

    override fun createDirectories(path: String) {
        File(path).mkdirs()
    }

    override fun fsyncFile(path: String) {
        RandomAccessFile(path, "r").use { file -> file.fd.sync() }
    }

    override fun fsyncDirectory(path: String) {
        // Directory fsync is not portably available on Windows; best effort elsewhere.
        runCatching {
            val channel = FileChannelHolder.open(path)
            channel?.use { it.force(true) }
        }
    }

    /**
     * P7-06 06.B (D-177; spec section 3.2): the available-space primitive did not exist before
     * 06.B. `FileStore.getUsableSpace` reports the volume holding [path]'s nearest existing
     * ancestor; a resolution failure returns null so the export use case proceeds and relies on
     * the write-side failure handling rather than blocking on an unknown value.
     */
    override fun usableSpace(path: String): Long? =
        runCatching {
            val target = nearestExisting(path)
            Files.getFileStore(target.toPath()).usableSpace
        }.getOrNull()

    override fun openRead(path: String): LedgerReadStream = DesktopReadStream(FileInputStream(path))

    override fun openWrite(path: String): LedgerWriteStream = DesktopWriteStream(File(path))

    override fun listDirectory(path: String): List<String> = File(path).list()?.toList() ?: emptyList()

    private fun nearestExisting(path: String): File {
        var candidate = File(path)
        while (!candidate.exists() && candidate.parentFile != null) {
            candidate = candidate.parentFile
        }
        return candidate
    }
}

/** A bounded chunked read stream over a desktop file (P7-06 06.B). */
private class DesktopReadStream(
    private val stream: FileInputStream,
) : LedgerReadStream {
    override fun read(buffer: ByteArray): Int = stream.read(buffer)

    override fun close() {
        stream.close()
    }
}

/**
 * The desktop bounded write stream (P7-06 06.B; spec section 3.5 phase 2): content is staged in a
 * sibling temp file, fsynced, then atomically moved onto the target (`ATOMIC_MOVE`). If the
 * platform cannot move atomically the stream throws rather than silently publishing a partial
 * file, matching the frozen [DesktopLedgerFileSystem.writeAtomic] rule. A failed [commit] or a
 * [close] before commit removes the staged temp file.
 */
private class DesktopWriteStream(
    private val target: File,
) : LedgerWriteStream {
    private val temporary = File(target.parentFile, target.name + ".tmp")
    private val stream = FileOutputStream(temporary).also { target.parentFile?.mkdirs() }
    private var committed = false

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        stream.write(bytes, offset, length)
    }

    override fun flushAndSync() {
        stream.flush()
        stream.fd.sync()
    }

    override fun commit() {
        stream.flush()
        stream.fd.sync()
        stream.close()
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (unsupported: AtomicMoveNotSupportedException) {
            temporary.delete()
            throw unsupported
        }
        committed = true
    }

    override fun close() {
        if (!committed) {
            runCatching { stream.close() }
            temporary.delete()
        }
    }
}

/** Opens a directory read channel for the fsync attempt, or null when unsupported. */
private object FileChannelHolder {
    fun open(path: String): java.nio.channels.FileChannel? =
        runCatching {
            java.nio.channels.FileChannel
                .open(Path.of(path))
        }.getOrNull()
}

/**
 * Resolves the desktop product host directory at runtime (spec section 3.3): the per-OS user data
 * root plus a fixed product subdirectory. Resolution depends only on runtime environment/system
 * properties — never a tracked literal absolute path — and throws (fail-closed) when no usable
 * location can be determined, so the product never silently falls back to a temp directory.
 */
internal fun resolveDesktopHostDirectory(
    environment: (String) -> String? = System::getenv,
    osName: String = System.getProperty("os.name").orEmpty(),
    userHome: String = System.getProperty("user.home").orEmpty(),
): String {
    val root =
        when {
            osName.startsWith("Windows", ignoreCase = true) -> environment("LOCALAPPDATA")
            osName.startsWith("Mac", ignoreCase = true) || osName.startsWith("Darwin", ignoreCase = true) ->
                userHome.takeIf { it.isNotBlank() }?.let { File(it, "Library/Application Support").path }
            else -> environment("XDG_DATA_HOME") ?: userHome.takeIf { it.isNotBlank() }?.let { File(it, ".local/share").path }
        }
    if (root.isNullOrBlank()) {
        throw IllegalStateException("unable to resolve a per-user data directory for the ledger host")
    }
    return File(root, DESKTOP_PRODUCT_DIRECTORY).path
}

/** The fixed product subdirectory under the per-OS user data root. */
private const val DESKTOP_PRODUCT_DIRECTORY = "UnifiedLedger"
