package com.unifiedledger.ui

import com.unifiedledger.domain.LedgerId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Ledger-identity short-patch copy tests (D-146 entry 12, DECISIONS.md:2805): the catalog
 * management screen must present the active ledger identity read-only (same 账本： copy family
 * as the home tab, P503OverviewScreen) and an honest disabled 切换账本 trigger — never a fake
 * picker, never a dead button without explanation (same honesty class as the import format
 * matrix's 待设备运行验证，暂不可用 line, R-Q08-3). The full multi-ledger selector (picker
 * port / use case / lastActiveLedgerId persistence) stays a P7-05+ future batch; this patch
 * only makes the current ledger identifiable on this screen.
 */
class P503CatalogManagementPresentationTest {
    // ---- ledger identity line (read-only current-ledger disclosure) ----

    @Test
    fun ledgerIdentityLineFollowsTheHomeTabCopyFamily() {
        assertEquals("账本：ledger-local-test", catalogLedgerIdentityText(LedgerId("ledger-local-test")))
    }

    @Test
    fun ledgerIdentityLineReflectsAnyLedgerIdValueVerbatim() {
        assertEquals("账本：second-ledger", catalogLedgerIdentityText(LedgerId("second-ledger")))
    }

    // ---- honest disabled switch trigger (no ledger-list data source this batch) ----

    @Test
    fun switchLedgerButtonIsDisabledWithHonestInlineExplanation() {
        assertEquals(false, CATALOG_SWITCH_LEDGER_ENABLED)
        assertEquals("切换账本", CATALOG_SWITCH_LEDGER_BUTTON_TEXT)
        assertEquals("（多账本未启用）", CATALOG_SWITCH_LEDGER_HONEST_SUFFIX_TEXT)
    }

    // ---- accessibility labels (C04 style: every new element carries one) ----

    @Test
    fun ledgerIdentityAccessibilityLabelAnnouncesTheCurrentLedger() {
        assertEquals("当前账本：ledger-local-test", catalogLedgerIdentityContentDescription(LedgerId("ledger-local-test")))
    }

    @Test
    fun switchTriggerAccessibilityLabelStatesItIsNotEnabled() {
        assertEquals("切换账本（未启用）", CATALOG_SWITCH_LEDGER_CONTENT_DESCRIPTION)
    }
}
