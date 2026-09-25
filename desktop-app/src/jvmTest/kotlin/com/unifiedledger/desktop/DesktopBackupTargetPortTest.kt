package com.unifiedledger.desktop

import com.unifiedledger.ui.BACKUP_TARGET_SUGGESTED_NAME
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P7-06 06.B (D-177; spec section 3.5 phase 2): the desktop save-target port and the bounded
 * streaming write it exposes. The dialog is an injected lambda, so the port runs headless; the
 * real Swing chooser is product wiring and is never instantiated here (CI has no display).
 */
class DesktopBackupTargetPortTest {
    @Test
    fun aDismissedDialogYieldsNoTarget() {
        val port = DesktopBackupTargetPort(showSaveFileChooser = { null })

        assertNull(port.openTarget())
    }

    @Test
    fun theSuggestedNameIsPassedToTheDialog() {
        var seen: String? = null
        val port =
            DesktopBackupTargetPort(
                showSaveFileChooser = {
                    seen = it
                    null
                },
            )

        port.openTarget()

        assertEquals(BACKUP_TARGET_SUGGESTED_NAME, seen)
    }

    @Test
    fun aCommittedTargetIsPublishedAtomicallyWithTheExactBytes() {
        val directory = freshDirectory("p706-desktop-target")
        directory.deleteOnExit()
        val target = File(directory, "backup.ulbk")
        val port = DesktopBackupTargetPort(showSaveFileChooser = { target })

        val writer = port.openTarget()!!
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        writer.write(payload, 0, payload.size)
        writer.flushAndSync()
        writer.commit()
        writer.close()

        assertTrue(target.exists())
        assertContentEquals(payload, target.readBytes())
        // The sibling temp file is gone after the atomic move.
        assertFalse(File(directory, "backup.ulbk.tmp").exists())
    }

    @Test
    fun anUncommittedTargetIsAbandonedWithoutPublishing() {
        val directory = freshDirectory("p706-desktop-target-abandon")
        directory.deleteOnExit()
        val target = File(directory, "backup.ulbk")
        val port = DesktopBackupTargetPort(showSaveFileChooser = { target })

        val writer = port.openTarget()!!
        writer.write(ByteArray(10), 0, 10)
        writer.close()

        assertFalse(target.exists(), "an abandoned target must not be published")
        assertFalse(File(directory, "backup.ulbk.tmp").exists())
    }

    @Test
    fun theFileSystemStreamsChunkedReadsAndWritesAndReportsUsableSpace() {
        val directory = freshDirectory("p706-desktop-fs")
        directory.deleteOnExit()
        val fileSystem = DesktopLedgerFileSystem()
        val source = File(directory, "source.bin")
        val payload = ByteArray(150_000) { (it % 200).toByte() }
        source.writeBytes(payload)

        // Bounded chunked read.
        val read = fileSystem.openRead(source.path)
        val collected = ArrayList<Byte>()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read.read(buffer)
            if (count <= 0) break
            for (index in 0 until count) collected += buffer[index]
        }
        read.close()
        assertContentEquals(payload, collected.toByteArray())

        // Chunked atomic write.
        val target = File(directory, "target.bin")
        val write = fileSystem.openWrite(target.path)
        write.write(payload, 0, payload.size)
        write.flushAndSync()
        write.commit()
        write.close()
        assertContentEquals(payload, target.readBytes())

        // Available space is reported for an existing directory.
        val usable = fileSystem.usableSpace(directory.path)
        assertTrue(usable != null && usable > 0)
    }

    /** A fresh empty temp directory (created by deleting the temp file the factory made). */
    private fun freshDirectory(prefix: String): File {
        val directory = File.createTempFile(prefix, "")
        directory.delete()
        directory.mkdirs()
        directory.deleteOnExit()
        return directory
    }
}
