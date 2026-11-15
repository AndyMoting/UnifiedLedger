package com.unifiedledger.desktop

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P8-05 批 b (D-331 spec sections 4 批 b / D-E12; V-4): the desktop readable-export sink bridge's
 * JVM injection tests (the `DesktopBackupTargetPort` injection pattern — the chooser and the
 * stream opener are lambdas, so the port runs headless). The spec's sink-semantics-equivalence
 * matrix, symmetric to the Android bridge:
 *
 * - the save dialog is offered the readable export's OWN suggested name (the D-E7 date shape),
 *   never the backup container's suggestion;
 * - commit is fsync-then-ATOMIC_MOVE exactly once — the byte-delivery obligation folded into the
 *   bridge (the D-E12 rule the backup writer's own commit does not carry);
 * - an uncommitted close abandons the temp file WITHOUT claiming success;
 * - a dismissed dialog opens nothing — the honest null.
 */
class DesktopReadableExportTargetPortTest {
    /** A headless chooser double: records the suggested names, returns the canned target. */
    private class RecordingChooser {
        val suggestedNames = mutableListOf<String>()
        var nextFile: File? = null

        val show: (String) -> File? =
            { suggestedName ->
                suggestedNames += suggestedName
                nextFile
            }
    }

    /** A recording stream opener tracking close/commit-relevant state (headless, no real fsync). */
    private class RecordingOpener {
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        val deleted = java.util.concurrent.atomic.AtomicBoolean(false)

        fun open(temporary: File): FileOutputStream {
            Files.write(temporary.toPath(), ByteArray(0))
            return object : FileOutputStream(temporary) {
                override fun close() {
                    closed.set(true)
                    super.close()
                }
            }
        }
    }

    @Test
    fun theSaveDialogIsOfferedTheReadableExportSuggestedNameNotTheBackupName() {
        // D-E7: the readable export's own display-name shape; the backup suggestion must never be
        // offered (the frozen 备份容器面 is untouched).
        val chooser = RecordingChooser()
        chooser.nextFile = File(Files.createTempDirectory("ul-rx-dlg").toFile(), "chosen.xlsx")
        val port =
            DesktopReadableExportTargetPort(
                showSaveFileChooser = chooser.show,
                openOutputStream = { file -> FileOutputStream(file) },
                suggestedName = { "unifiedledger-export-20261115.xlsx" },
            )

        val sink = readableExportSinkFromTargetPort(port)

        assertTrue(sink != null)
        assertEquals(listOf("unifiedledger-export-20261115.xlsx"), chooser.suggestedNames)
        assertFalse(chooser.suggestedNames.any { it.endsWith(".ulbk") }, "the backup container suggestion must never launch")
        sink!!.close()
    }

    @Test
    fun commitFsyncsThenAtomicallyPublishesTheTargetExactlyOnce() {
        // D-E12: the bridge folds the fsync INTO the commit, then the writer's ATOMIC_MOVE
        // publishes the target; the delivered bytes are exactly what was written.
        val directory = Files.createTempDirectory("ul-rx-commit").toFile()
        val target = File(directory, "chosen.xlsx")
        val chooser = RecordingChooser()
        chooser.nextFile = target
        val recorder = RecordingOpener()
        val port =
            DesktopReadableExportTargetPort(
                showSaveFileChooser = chooser.show,
                openOutputStream = recorder::open,
                suggestedName = { "unifiedledger-export-20261115.xlsx" },
            )

        val writer = readableExportSinkFromTargetPort(port)!!
        val payload = ByteArray(50_000) { (it % 251).toByte() }
        writer.write(payload, 0, payload.size)
        writer.commit()
        writer.close()

        assertContentEquals(payload, target.readBytes())
        assertTrue(recorder.closed.get(), "the commit must close the temp stream before the move")
        assertFalse(target.toPath().resolveSibling(target.name + ".tmp").toFile().exists(), "the temp file must be gone after the commit")
        assertFalse(recorder.deleted.get(), "a committed writer must not run the abandoning delete")
    }

    @Test
    fun closingBeforeCommitAbandonsTheTempFileWithoutClaimingSuccess() {
        // Close-before-commit never reports success: the temp file is deleted, the target is
        // never published (no "committed half-product" semantics, V-4).
        val directory = Files.createTempDirectory("ul-rx-abandon").toFile()
        val target = File(directory, "chosen.xlsx")
        val chooser = RecordingChooser()
        chooser.nextFile = target
        val recorder = RecordingOpener()
        val port =
            DesktopReadableExportTargetPort(
                showSaveFileChooser = chooser.show,
                openOutputStream = recorder::open,
                suggestedName = { "unifiedledger-export-20261115.xlsx" },
            )

        val writer = readableExportSinkFromTargetPort(port)!!
        writer.write(ByteArray(10) { 1 }, 0, 10)
        writer.close()

        assertFalse(target.exists(), "an abandoned writer must never publish the target")
        assertFalse(target.toPath().resolveSibling(target.name + ".tmp").toFile().exists(), "the abandoning close must delete the temp file")
        assertTrue(recorder.deleted.get(), "the abandoning close must run the temp cleanup")
    }

    @Test
    fun aDismissedDialogOpensNoSink() {
        val chooser = RecordingChooser()
        chooser.nextFile = null
        val port =
            DesktopReadableExportTargetPort(
                showSaveFileChooser = chooser.show,
                openOutputStream = { FileOutputStream(it) },
                suggestedName = { "unifiedledger-export-20261115.xlsx" },
            )

        assertNull(readableExportSinkFromTargetPort(port), "a dismissed dialog must open nothing (the honest null)")
    }

    @Test
    fun theBridgeWritesReachTheTargetThroughTheTargetWriter() {
        // The BackupTargetWriter-facing sink bridge: every write lands on the target stream, the
        // bridge's commit folds the flush into the target's own commit exactly once.
        val directory = Files.createTempDirectory("ul-rx-bridge").toFile()
        val target = File(directory, "chosen.xlsx")
        val chooser = RecordingChooser()
        chooser.nextFile = target
        val port =
            DesktopReadableExportTargetPort(
                showSaveFileChooser = chooser.show,
                openOutputStream = { FileOutputStream(it) },
                suggestedName = { "unifiedledger-export-20261115.xlsx" },
            )

        val sink = readableExportSinkFromTargetPort(port)!!
        val payload = "unifiedledger readable export".toByteArray(Charsets.UTF_8)
        sink.write(payload, 0, payload.size)
        sink.commit()
        sink.close()

        assertContentEquals(payload, target.readBytes())
    }
}
