package com.unifiedledger.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P8-00 b2 fix round (2026-11-08 user ruling; review F-Q1): the export carrier face's restore
 * entry. The drawer's 备份与恢复 row opens this export face as the restore CARRIER; the entry
 * gate below decides its two honest shapes — clickable 恢复备份 when the composition root wired
 * the restore use cases, and the disabled 恢复备份（暂未开放） affordance when it did not (the
 * shell batch's disabled-row precedent, never a dead button, never a silently absent leg) — and
 * a running export disables it too (提交中不重入, the surface's own submitting discipline).
 *
 * The carrier ROUTE itself (the event's effect on the export state) is pinned by
 * [P503RestoreReducerTest.theDrawerCarrierRouteOpensTheSurfaceOffTheExportFaceCarryingItsOverview];
 * this file pins the presentation layer of the affordance that fires it, so the wired/unwired
 * shapes and the running guard are assertable without a Compose harness (the
 * [P503BackupExportPresentationTest] precedent).
 */
class P503BackupExportRestoreEntryTest {
    // ---- the two shapes ----

    @Test
    fun theRestoreEntryIsEnabledExactlyWhenRestoreIsWiredAndNoExportRuns() {
        // Wired: the clickable 恢复备份 shape — the affordance that fires OpenBackupRestore.
        assertTrue(backupExportRestoreEntryEnabled(restoreWired = true, running = false))
        // Unwired: the disabled 恢复备份（暂未开放） shape — honest, not absent, not dead-clickable.
        assertFalse(backupExportRestoreEntryEnabled(restoreWired = false, running = false))
    }

    @Test
    fun aRunningExportDisablesTheRestoreEntry() {
        // 提交中不重入: the surface's own submitting discipline covers the restore leg too.
        assertTrue(backupExportRestoreEntryEnabled(restoreWired = true, running = false))
        assertFalse(backupExportRestoreEntryEnabled(restoreWired = true, running = true))
        assertFalse(backupExportRestoreEntryEnabled(restoreWired = false, running = true))
    }

    // ---- the affordance copy ----

    @Test
    fun theAffordanceCopyKeepsTheTwoHonestShapes() {
        // The wired label names the restore leg plainly; the unwired label carries the honest
        // 暂未开放 suffix (the drawer's disabled-row precedent wording).
        assertEquals("恢复备份", backupExportRestoreEntryLabel(restoreWired = true))
        assertEquals("恢复备份（暂未开放）", backupExportRestoreEntryLabel(restoreWired = false))
    }
}
