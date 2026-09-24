package com.unifiedledger.android

import android.util.AtomicFile
import com.unifiedledger.ui.LedgerFileSystem
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * P7-06 06.1 (D-176; spec section 3.2): the Android implementation of the shared
 * [LedgerFileSystem] port over the application-private data directory.
 *
 * - The host directory is resolved at runtime from the platform API (the parent of
 *   `Context.getDatabasePath`, i.e. the app-private `databases/` directory): app-private, stable
 *   across process restarts, no storage permission, and no machine absolute path in tracked code.
 * - The atomic pointer publish uses [AtomicFile] (the container-format spec section 5.3 platform
 *   primitive for Android), so a partially written pointer is never observable.
 * - File content is fsynced through the file descriptor. Android offers no directory fsync
 *   primitive, so [fsyncDirectory] is best effort (documented; the spec section 3.2 durability
 *   requirement is carried by the file fsyncs, which are real here).
 */
internal class AndroidLedgerFileSystem : LedgerFileSystem {
    override fun join(
        parent: String,
        child: String,
    ): String = File(parent, child).path

    override fun exists(path: String): Boolean = File(path).exists()

    override fun isDirectory(path: String): Boolean = File(path).isDirectory

    override fun childNames(directory: String): List<String> = File(directory).list()?.toList() ?: emptyList()

    override fun length(path: String): Long = File(path).length()

    override fun readBytes(path: String): ByteArray = File(path).readBytes()

    override fun writeBytes(
        path: String,
        bytes: ByteArray,
    ) {
        File(path).parentFile?.mkdirs()
        FileOutputStream(path).use { stream ->
            stream.write(bytes)
            stream.flush()
        }
    }

    override fun writeAtomic(
        path: String,
        bytes: ByteArray,
    ) {
        val file = File(path)
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            output.flush()
            atomic.finishWrite(output)
        } catch (failure: Throwable) {
            atomic.failWrite(output)
            throw failure
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
        if (File(path).exists()) {
            File(path).delete()
        }
    }

    override fun createDirectories(path: String) {
        File(path).mkdirs()
    }

    override fun fsyncFile(path: String) {
        FileOutputStream(path, true).use { stream -> stream.fd.sync() }
    }

    override fun fsyncDirectory(path: String) {
        // No directory fsync primitive on Android; see the class note. The file-level fsyncs
        // before and after the pointer publish carry the durability ordering this adapter can
        // guarantee.
    }
}

/**
 * Resolves the Android host directory and the legacy product database path (spec section 3.2):
 * the app-private `databases/` directory is the host, and `databases/ledger.db` is the legacy
 * location that the first generation-aware start must migrate non-destructively.
 */
internal fun androidStableStoragePaths(
    databasePath: File,
): Pair<String, String> {
    val hostDirectory = databasePath.parentFile?.absolutePath ?: databasePath.absolutePath
    return hostDirectory to databasePath.absolutePath
}

/**
 * The AndroidSqliteDriver takes a name relative to the app-private `databases/` directory, so the
 * composition root converts the resolved absolute generation path back into that relative name.
 * The generation directory itself is created by the shared stable-storage sequence before the
 * open, so the driver only ever opens an existing, guarded file (section 4.5).
 */
internal fun androidDatabaseName(
    hostDirectory: String,
    mainFile: String,
): String {
    val prefix = File(hostDirectory).absolutePath + File.separator
    val absoluteMain = File(mainFile).absolutePath
    return if (absoluteMain.startsWith(prefix)) {
        absoluteMain.removePrefix(prefix).replace(File.separatorChar, '/')
    } else {
        absoluteMain
    }
}
