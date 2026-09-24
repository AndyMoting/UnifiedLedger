package com.unifiedledger.android

import com.unifiedledger.ui.LedgerFileSystem
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
}
