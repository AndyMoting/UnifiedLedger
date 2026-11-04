package com.unifiedledger.android

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * DEF-EXPORT-OOM (D-269 item 7): the bounded-read progress hook is INERT unless it is wired.
 *
 * The adapter is constructed with no hook by default ([AndroidLedgerFileSystem]'s null parameter),
 * and the composition root only passes one when [heapCaptureEnabled] agrees. This pins the adapter
 * half of that contract on the JVM: reads still work, chunks stay bounded by the caller's buffer,
 * and a hook — when supplied — fires once per [com.unifiedledger.ui.LedgerReadStream.read] call,
 * exactly the sampling point the device run needs.
 *
 * `android.util.AtomicFile` is an android.jar stub off-device, so only the READ side is exercised
 * here (the [DesktopStyleTestFileSystem] precedent); the write side is unchanged by this batch.
 */
class AndroidLedgerFileSystemHeapCaptureTest {
    private fun tempFile(bytes: ByteArray): File {
        val directory = Files.createTempDirectory("def-export-oom-read-").toFile()
        val file = File(directory, "snapshot.bin")
        file.writeBytes(bytes)
        return file
    }

    @Test
    fun theAdapterWithoutAHookReadsBoundedChunksAndNeverSamples() {
        val payload = ByteArray(4096) { (it % 251).toByte() }
        val file = tempFile(payload)
        val fileSystem = AndroidLedgerFileSystem()

        val collected = readAll(fileSystem, file, chunk = 1024)

        assertContentEquals(payload.toList(), collected, "a hookless adapter must read the same bytes")
    }

    @Test
    fun aSuppliedHookIsInvokedOncePerBoundedReadAndNeverChangesTheBytes() {
        val payload = ByteArray(4096) { (it % 251).toByte() }
        val file = tempFile(payload)
        var hookCalls = 0
        val fileSystem = AndroidLedgerFileSystem(heapCapture = { hookCalls += 1 })

        val collected = readAll(fileSystem, file, chunk = 1024)

        assertContentEquals(payload.toList(), collected, "the hook must not alter the stream")
        // 4 full chunks plus the end-of-stream call: one invocation per read().
        assertEquals(5, hookCalls, "the hook must sample every read, got $hookCalls")
    }

    @Test
    fun resolveHookReturnsNullWithoutAContextSoNoDiagnosticPathIsWired() {
        assertNull(BackupHeapCapture.resolveHook(null), "a null context must yield no hook")
    }

    private fun readAll(
        fileSystem: AndroidLedgerFileSystem,
        file: File,
        chunk: Int,
    ): List<Byte> {
        val collected = mutableListOf<Byte>()
        fileSystem.openRead(file.absolutePath).use { stream ->
            val buffer = ByteArray(chunk)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                collected += buffer.copyOf(read).toList()
            }
        }
        return collected
    }
}
