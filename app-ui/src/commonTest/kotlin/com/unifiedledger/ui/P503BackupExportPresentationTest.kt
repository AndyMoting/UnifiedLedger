package com.unifiedledger.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P7-06 06.B (D-177; spec `2026-09-24-p7-06-backup-export-design.md` sections 3/4.10) pure
 * presentation-decision tests: the overview entry gate, the password confirm gate (the frozen
 * 8-code-point rule reused from the use case) and the typed outcome banner copy. The secret
 * discipline is asserted structurally: no produced text contains the password.
 */
class P503BackupExportPresentationTest {
    // ---- entry gate ----

    @Test
    fun theEntryIsVisibleOnlyWhenTheExportUseCaseIsWired() {
        assertTrue(backupExportEntryVisible(exportWired = true))
        assertFalse(backupExportEntryVisible(exportWired = false))
    }

    // ---- password confirm gate ----

    @Test
    fun confirmIsDisabledForAnEmptyOrShortPassword() {
        assertFalse(backupExportConfirmEnabled("", running = false))
        assertFalse(backupExportConfirmEnabled("1234567", running = false))
    }

    @Test
    fun confirmIsEnabledAtExactlyEightCodePoints() {
        assertTrue(backupExportConfirmEnabled("12345678", running = false))
    }

    @Test
    fun aSurrogatePairEmojiCountsAsOneCodePoint() {
        // 7 ASCII + one surrogate-pair emoji = 8 code points.
        assertTrue(backupExportConfirmEnabled("1234567\uD83D\uDD10", running = false))
        // 6 ASCII + one emoji = 7 code points: still too short.
        assertFalse(backupExportConfirmEnabled("123456\uD83D\uDD10", running = false))
    }

    @Test
    fun confirmIsDisabledWhileRunning() {
        assertFalse(backupExportConfirmEnabled("12345678", running = true))
    }

    // ---- outcome copy ----

    @Test
    fun noOutcomeRendersNothing() {
        assertNull(backupExportOutcomeText(null))
    }

    @Test
    fun successNamesTheContainerSize() {
        val text = backupExportOutcomeText(BackupExportResult.Succeeded(2048))
        assertNotNull(text)
        assertTrue(text.contains("2048"), text)
    }

    @Test
    fun cancelledIsDistinctFromFailure() {
        val text = backupExportOutcomeText(BackupExportResult.Cancelled)
        assertNotNull(text)
        assertTrue(text.contains("取消"), text)
    }

    @Test
    fun everyFailureReasonHasItsOwnNonEmptyLine() {
        val texts = BackupExportFailure.entries.map { backupExportFailureText(it) }
        assertTrue(texts.all { it.isNotBlank() })
        // The reasons are distinct copies (no generic "failed" collapse).
        assertEquals(BackupExportFailure.entries.size, texts.toSet().size)
    }

    @Test
    fun errorOutcomesAreFlagged() {
        assertTrue(backupExportOutcomeIsError(BackupExportResult.Failed(BackupExportFailure.SNAPSHOT_FAILED)))
        assertFalse(backupExportOutcomeIsError(BackupExportResult.Succeeded(1)))
        assertFalse(backupExportOutcomeIsError(BackupExportResult.Cancelled))
        assertFalse(backupExportOutcomeIsError(null))
    }

    @Test
    fun noOutcomeTextEverContainsThePassword() {
        // The outcome copy is a pure function of the outcome alone; the password is not an input,
        // so it cannot leak through it. This pins the structural property (the presentation file
        // never receives the password).
        val password = "s3cr3t-password"
        val texts =
            buildList {
                add(backupExportOutcomeText(BackupExportResult.Succeeded(10)))
                add(backupExportOutcomeText(BackupExportResult.Cancelled))
                BackupExportFailure.entries.forEach { add(backupExportOutcomeText(BackupExportResult.Failed(it))) }
            }
        texts.forEach { text -> assertFalse(text!!.contains(password), text) }
        assertFalse(BACKUP_EXPORT_PASSWORD_WARNING.contains(password))
        assertFalse(BACKUP_EXPORT_PASSWORD_LABEL.contains(password))
    }
}
