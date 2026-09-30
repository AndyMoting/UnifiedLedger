package com.unifiedledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P7-08 08.A pure domain evidence (D-187; spec sections 2.1-2.3).
 *
 * Covers independent tag/merchant namespaces, name normalization reuse (empty/control/too-long),
 * cross-namespace kind mismatch, tombstone unreachability, reference-gated tombstone, the
 * reversible active toggle, and the annotation cardinality/selectability rules.
 */
class TagMerchantCatalogTest {
    private val ledgerId = LedgerId("ledger-a")

    private fun tag(
        id: String,
        name: String,
        active: Boolean = true,
        tombstoned: Boolean = false,
        revision: Long = 0L,
    ) = CatalogItem(CatalogItemKind.TAG, id, ledgerId, name, active, tombstoned, revision)

    private fun merchant(
        id: String,
        name: String,
        active: Boolean = true,
        tombstoned: Boolean = false,
    ) = CatalogItem(CatalogItemKind.MERCHANT, id, ledgerId, name, active, tombstoned, 0L)

    @Test
    fun createNormalizesTheNameAndInsertsTheMintedId() {
        val writes = success(createCatalogItem(CatalogItemKind.TAG, emptyList(), ledgerId, "tag-1", "  \u3000咖啡  Tea "))
        assertEquals(listOf(CatalogItemWrite.Insert(CatalogItemKind.TAG, "tag-1", "咖啡 Tea")), writes)
    }

    @Test
    fun emptyControlAndTooLongNamesAreTypedRejections() {
        assertIs<CatalogItemViolation.CatalogNameEmpty>(failure(createCatalogItem(CatalogItemKind.TAG, emptyList(), ledgerId, "tag-1", "\u3000 ")))
        assertIs<CatalogItemViolation.CatalogNameInvalid>(failure(createCatalogItem(CatalogItemKind.TAG, emptyList(), ledgerId, "tag-1", "a\u0009b")))
        assertIs<CatalogItemViolation.CatalogNameTooLong>(failure(createCatalogItem(CatalogItemKind.TAG, emptyList(), ledgerId, "tag-1", "x".repeat(65))))
    }

    @Test
    fun nameUniquenessIsPerNamespaceSoTheSameNameMayExistAsBothKinds() {
        val tags = listOf(tag("tag-1", "Coffee"))
        // The same name is free in the merchant namespace.
        assertIs<DomainResult.Success<*>>(
            createCatalogItem(CatalogItemKind.MERCHANT, tags, ledgerId, "merchant-1", "Coffee"),
        )
        // But a second tag with the same normalized name conflicts.
        assertIs<CatalogItemViolation.CatalogNameConflict>(
            failure(createCatalogItem(CatalogItemKind.TAG, tags, ledgerId, "tag-2", "Coffee")),
        )
    }

    @Test
    fun anIdThatExistsInTheOtherNamespaceIsAKindMismatchNotNotFound() {
        val tags = listOf(tag("shared-id", "Coffee"))
        val failure = failure(renameCatalogItem(tags, ledgerId, CatalogItemKind.MERCHANT, "shared-id", "New"))
        assertIs<CatalogItemViolation.CatalogItemKindMismatch>(failure)
        assertIs<CatalogItemViolation.CatalogObjectNotFound>(failure(renameCatalogItem(tags, ledgerId, CatalogItemKind.TAG, "absent", "New")))
    }

    @Test
    fun renameAppendsNameAndTombstonedItemsCannotBeRenamedOrReactivated() {
        val items = listOf(tag("tag-1", "Old"))
        assertEquals(listOf(CatalogItemWrite.Rename(CatalogItemKind.TAG, "tag-1", "New")), success(renameCatalogItem(items, ledgerId, CatalogItemKind.TAG, "tag-1", "New")))
        val dead = listOf(tag("tag-1", "Old", tombstoned = true))
        assertIs<CatalogItemViolation.CatalogItemTombstoned>(failure(renameCatalogItem(dead, ledgerId, CatalogItemKind.TAG, "tag-1", "New")))
        assertIs<CatalogItemViolation.CatalogItemTombstoned>(failure(setCatalogItemActive(dead, ledgerId, CatalogItemKind.TAG, "tag-1", true)))
        assertIs<CatalogItemViolation.CatalogItemTombstoned>(failure(tombstoneCatalogItem(dead, ledgerId, CatalogItemKind.TAG, "tag-1") { false }))
    }

    @Test
    fun activeToggleIsReversibleButTombstoneIsReferenceGated() {
        val items = listOf(tag("tag-1", "Coffee"))
        assertEquals(listOf(CatalogItemWrite.SetActiveState(CatalogItemKind.TAG, "tag-1", false)), success(setCatalogItemActive(items, ledgerId, CatalogItemKind.TAG, "tag-1", false)))
        assertIs<CatalogItemViolation.CatalogItemHasReferences>(failure(tombstoneCatalogItem(items, ledgerId, CatalogItemKind.TAG, "tag-1") { true }))
        assertEquals(listOf(CatalogItemWrite.Tombstone(CatalogItemKind.TAG, "tag-1")), success(tombstoneCatalogItem(items, ledgerId, CatalogItemKind.TAG, "tag-1") { false }))
    }

    @Test
    fun validateDeduplicatesAndSortsTagsByStableIdAndAllowsAtMostTwenty() {
        val tags = (1..25).map { tag("tag-%02d".format(it), "n$it") }
        val twenty = (1..20).map { TagId("tag-%02d".format(it)) }
        val valid = success(validateTransactionAnnotation(ledgerId, twenty.shuffled(), MerchantId("merchant-1"), tags, listOf(merchant("merchant-1", "Shop"))))
        assertEquals((1..20).map { "tag-%02d".format(it) }, valid.tagIds.map { it.value })
        assertEquals(MerchantId("merchant-1"), valid.merchantId)
        assertIs<TransactionAnnotationViolation.TooManyTags>(
            failure(validateTransactionAnnotation(ledgerId, (1..21).map { TagId("tag-%02d".format(it)) }, null, tags, emptyList())),
        )
    }

    @Test
    fun validateRejectsUnknownInactiveTombstonedAndCrossLedgerReferences() {
        val tags = listOf(tag("tag-1", "A"), tag("tag-2", "B", active = false), tag("tag-3", "C", tombstoned = true))
        assertIs<TransactionAnnotationViolation.UnknownTag>(failure(validateTransactionAnnotation(ledgerId, listOf(TagId("nope")), null, tags, emptyList())))
        assertIs<TransactionAnnotationViolation.TagNotSelectable>(failure(validateTransactionAnnotation(ledgerId, listOf(TagId("tag-2")), null, tags, emptyList())))
        assertIs<TransactionAnnotationViolation.TagNotSelectable>(failure(validateTransactionAnnotation(ledgerId, listOf(TagId("tag-3")), null, tags, emptyList())))
        assertIs<TransactionAnnotationViolation.UnknownMerchant>(failure(validateTransactionAnnotation(ledgerId, emptyList(), MerchantId("nope"), tags, emptyList())))
        assertIs<TransactionAnnotationViolation.MerchantNotSelectable>(
            failure(validateTransactionAnnotation(ledgerId, emptyList(), MerchantId("merchant-2"), tags, listOf(merchant("merchant-2", "Shop", active = false)))),
        )
        val otherLedger = listOf(CatalogItem(CatalogItemKind.TAG, "tag-9", LedgerId("ledger-b"), "X", true, false, 0L))
        assertIs<TransactionAnnotationViolation.TagCrossLedger>(failure(validateTransactionAnnotation(ledgerId, listOf(TagId("tag-9")), null, otherLedger, emptyList())))
    }

    @Test
    fun validateAcceptsAnEmptyAnnotationAsAnExplicitClear() {
        val cleared = success(validateTransactionAnnotation(ledgerId, emptyList(), null, emptyList(), emptyList()))
        assertTrue(cleared.tagIds.isEmpty())
        assertEquals(null, cleared.merchantId)
    }
}
