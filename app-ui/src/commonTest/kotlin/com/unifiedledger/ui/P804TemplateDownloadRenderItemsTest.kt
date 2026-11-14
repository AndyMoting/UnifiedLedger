package com.unifiedledger.ui

import com.unifiedledger.application.ImportPlatformKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P8-04 i1 (D-11): the 「获取导入模板」 render-model pins — the entry row's existence, its
 * position directly after the format matrix (the template is needed BEFORE any picker) and the
 * no-dead-affordance gate (an unwired root renders neither the row nor a dead action; the
 * `onUseForReconciliation` null-wire convention). The full key-uniqueness contract stays pinned
 * by [P503ImportReviewRenderItemsTest].
 */
class P804TemplateDownloadRenderItemsTest {
    @Test
    fun theTemplateEntryRendersWhenWired() {
        val items = importReviewRenderItems(ImportReviewView(), ImportPlatformKind.ANDROID, templateDownloadWired = true)
        val entry = assertIs<ImportReviewRenderItem.TemplateDownloadEntry>(items.first { it is ImportReviewRenderItem.TemplateDownloadEntry })
        assertEquals("template-download-entry", entry.stableKey)
        assertEquals("templateDownloadEntry", entry.contentType)
    }

    @Test
    fun theTemplateEntrySitsDirectlyAfterTheFormatMatrix() {
        val items = importReviewRenderItems(ImportReviewView(), ImportPlatformKind.ANDROID, templateDownloadWired = true)
        val lastFormatIndex = items.indexOfLast { it is ImportReviewRenderItem.FormatEntry }
        val entryIndex = items.indexOfFirst { it is ImportReviewRenderItem.TemplateDownloadEntry }
        assertTrue(lastFormatIndex >= 0, "the format matrix renders before the entry")
        assertEquals(lastFormatIndex + 1, entryIndex, "the entry follows the format matrix directly")
        // The following section header (最近导入, when a session exists; otherwise the PENDING
        // divider) comes after the entry — the row never lands inside another section.
        val pendingDividerIndex = items.indexOfFirst { it is ImportReviewRenderItem.SectionDivider && it.slot == ImportReviewDividerSlot.PENDING }
        assertTrue(entryIndex < pendingDividerIndex)
    }

    @Test
    fun theTemplateEntryStaysAbsentWhenUnwired() {
        // Default (the pre-P8-04 call sites and the render-gate): no row, no dead button.
        val items = importReviewRenderItems(ImportReviewView(), ImportPlatformKind.ANDROID)
        assertTrue(items.none { it is ImportReviewRenderItem.TemplateDownloadEntry })
    }

    @Test
    fun theTemplateEntryCoExistsWithTheProjectionAndItsKeyStaysUnique() {
        val items =
            importReviewRenderItems(
                ImportReviewView(),
                ImportPlatformKind.ANDROID,
                templateDownloadWired = true,
            )
        val keys = items.map { it.stableKey }
        assertEquals(keys.size, keys.toSet().size, "duplicate stable keys: ${keys.groupingBy { it }.eachCount().filterValues { it > 1 }}")
    }
}
