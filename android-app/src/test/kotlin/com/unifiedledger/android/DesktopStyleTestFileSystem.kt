package com.unifiedledger.android

import com.unifiedledger.ui.LedgerFileSystem
import com.unifiedledger.ui.LedgerReadStream
import com.unifiedledger.ui.LedgerWriteStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * P7-06 06.1 (D-176): a JVM filesystem [LedgerFileSystem] for the Android unit tests. The
 * production adapter uses `android.util.AtomicFile`, which is an android.jar stub without
 * Robolectric, so the tests exercise the shared sequence through this real-filesystem
 * implementation instead (the sequence itself is platform-independent).
 */
internal class DesktopStyleTestFileSystem : LedgerFileSystem {
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
        FileOutputStream(temporary).use { it.write(bytes) }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IllegalStateException("atomic rename failed for $path")
        }
    }

    override fun copy(
        source: String,
        target: String,
    ) {
        File(target).parentFile?.mkdirs()
        FileInputStream(source).use { input -> FileOutputStream(target).use { input.copyTo(it) } }
    }

    override fun delete(path: String) {
        File(path).delete()
    }

    override fun createDirectories(path: String) {
        File(path).mkdirs()
    }

    override fun fsyncFile(path: String) {
        RandomAccessFile(path, "r").use { it.fd.sync() }
    }

    override fun fsyncDirectory(path: String) {
        // Best effort on the JVM; see the production adapters' notes.
    }

    // P7-06 06.B (D-177): the test doubles for the three 06.B port additions.

    override fun usableSpace(path: String): Long? = File(path).let { nearestExisting(it).usableSpace }

    override fun openRead(path: String): LedgerReadStream = JvmTestReadStream(FileInputStream(path))

    override fun openWrite(path: String): LedgerWriteStream = JvmTestWriteStream(File(path))

    override fun listDirectory(path: String): List<String> =
        File(path).list()?.toList() ?: emptyList()

    private fun nearestExisting(file: File): File {
        var candidate = file
        while (!candidate.exists() && candidate.parentFile != null) {
            candidate = candidate.parentFile
        }
        return candidate
    }
}

private class JvmTestReadStream(
    private val stream: FileInputStream,
) : LedgerReadStream {
    override fun read(buffer: ByteArray): Int = stream.read(buffer)

    override fun close() {
        stream.close()
    }
}

private class JvmTestWriteStream(
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
    }

    override fun commit() {
        stream.flush()
        stream.close()
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IllegalStateException("atomic rename failed for $target")
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
