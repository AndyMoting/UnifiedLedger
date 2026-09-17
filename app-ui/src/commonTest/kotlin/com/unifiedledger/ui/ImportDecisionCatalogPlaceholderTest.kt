package com.unifiedledger.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A-PERF (P7-04 read-governance batch, spec section 2.3, rework path 2a): the detail screen's
 * catalog-loading window presentation decision (S2-2) — a loading cached catalog snapshot yields
 * the explicit 载入中 placeholder copy, NEVER an empty option set presented as the authoritative
 * catalog; a loaded catalog yields null so the real decision form section renders its options.
 */
class ImportDecisionCatalogPlaceholderTest {
    @Test
    fun theLoadingWindowYieldsTheExplicitPlaceholderCopy() {
        assertEquals("账户与分类目录载入中，稍候即可补齐决策。", importDecisionCatalogPlaceholder(catalogLoading = true))
    }

    @Test
    fun aLoadedCatalogYieldsNullSoTheRealFormRenders() {
        assertNull(importDecisionCatalogPlaceholder(catalogLoading = false))
    }
}
