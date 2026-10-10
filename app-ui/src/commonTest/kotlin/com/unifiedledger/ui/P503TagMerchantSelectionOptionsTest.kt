package com.unifiedledger.ui

import com.unifiedledger.application.TagMerchantAuthority
import com.unifiedledger.application.TagMerchantCatalogReader
import com.unifiedledger.domain.CatalogItem
import com.unifiedledger.domain.CatalogItemKind
import com.unifiedledger.domain.LedgerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P7-08 08.B-2 (R-222-1; spec sections 2.2/4.1) facade selection-option projection contracts: the
 * raw catalog authority carries EVERY row (including disabled and tombstoned ones, because it is
 * the full audit surface), so the selector must never present them. The filter lives on the facade
 * — the single place the UI reads the catalog through — and this file pins it, plus the honest
 * "unwired => null" behavior that keeps a legacy composition from rendering a dead empty picker.
 *
 * No P708 vector is marked PASS here; this is the batch's evidence for the main agent's acceptance.
 */
class P503TagMerchantSelectionOptionsTest {
    private val ledgerId = LedgerId("ledger-selection-test")

    private fun item(
        kind: CatalogItemKind,
        id: String,
        name: String,
        active: Boolean = true,
        tombstoned: Boolean = false,
    ) = CatalogItem(
        kind = kind,
        id = id,
        ledgerId = ledgerId,
        name = name,
        active = active,
        tombstoned = tombstoned,
        revision = 1L,
    )

    @Test
    fun p708b2SelectionOptionsExcludeDisabledAndTombstonedItems() {
        val reader =
            TagMerchantCatalogReader { _ ->
                TagMerchantAuthority(
                    ledgerId = ledgerId,
                    tags =
                        listOf(
                            item(CatalogItemKind.TAG, "tag-active", "active"),
                            item(CatalogItemKind.TAG, "tag-disabled", "disabled", active = false),
                            item(CatalogItemKind.TAG, "tag-tombstone", "tombstone", tombstoned = true),
                        ),
                    merchants =
                        listOf(
                            item(CatalogItemKind.MERCHANT, "merchant-active", "shop"),
                            item(CatalogItemKind.MERCHANT, "merchant-disabled", "closed", active = false),
                        ),
                    catalogItemVersion = 1L,
                )
            }
        val options = minimalP503LedgerFacade(tagMerchantCatalogReader = { reader }).tagMerchantSelectionOptions()
        assertEquals(listOf("tag-active"), options?.tags?.map { it.id })
        assertEquals(listOf("merchant-active"), options?.merchants?.map { it.id })
    }

    @Test
    fun p708b2SelectionOptionsAreNullWhenTheReadSourceIsUnwired() {
        // A legacy composition renders no selector rather than an empty picker that would read as an
        // authoritative empty catalog (the honest-null discipline the catalog snapshot established).
        assertNull(minimalP503LedgerFacade().tagMerchantSelectionOptions())
    }

    @Test
    fun p708b2SelectionOptionsAreEmptyWhenTheLedgerHasNoCatalogAuthorityYet() {
        val reader = TagMerchantCatalogReader { _ -> null }
        val options = minimalP503LedgerFacade(tagMerchantCatalogReader = { reader }).tagMerchantSelectionOptions()
        assertTrue(options != null)
        assertTrue(options.tags.isEmpty() && options.merchants.isEmpty())
    }
}
