package com.unifiedledger.application

import com.unifiedledger.domain.CatalogItemKind
import com.unifiedledger.domain.CatalogItemViolation
import com.unifiedledger.domain.MerchantId
import com.unifiedledger.domain.TagId
import com.unifiedledger.domain.TransactionAnnotationViolation
import com.unifiedledger.domain.TransactionId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * P7-08 08.A application-level evidence (D-187; spec sections 2.2/3.1, open item 1).
 *
 * Canonical snapshot canonicalization and fingerprint separation, the frozen failure-code
 * literals, and the tag-set ordering rule (deduplicate + sort by stable id, never by name).
 */
class TagMerchantAnnotationUseCasesTest {
    private val transactionId = TransactionId("tx-1")

    @Test
    fun canonicalCatalogSnapshotsAreStableAndCommandNamed() {
        val payloads =
            listOf(
                CatalogItemCommandPayload.CreateItem(CatalogItemKind.TAG, "咖啡"),
                CatalogItemCommandPayload.RenameItem(CatalogItemKind.TAG, "tag-1", "茶"),
                CatalogItemCommandPayload.SetItemActive(CatalogItemKind.MERCHANT, "merchant-1", false),
                CatalogItemCommandPayload.DeleteItem(CatalogItemKind.MERCHANT, "merchant-1"),
            )
        val snapshots = payloads.map(::canonicalTagMerchantRequestSnapshot)
        assertEquals(payloads.size, snapshots.toSet().size)
        payloads.forEachIndexed { index, payload ->
            assertEquals(true, snapshots[index].contains(payload.commandName))
            assertEquals(snapshots[index], canonicalTagMerchantRequestSnapshot(payload))
        }
    }

    @Test
    fun annotationSnapshotSortsTagsByStableIdAndExcludesTheCasFields() {
        val ordered = canonicalTransactionAnnotationSnapshot(transactionId, listOf(TagId("b"), TagId("a")), MerchantId("m"))
        // The tag set is deduplicated and sorted by stable id regardless of request order.
        assertEquals(canonicalTransactionAnnotationSnapshot(transactionId, listOf(TagId("a"), TagId("b"), TagId("a")), MerchantId("m")), ordered)
        assertEquals("{\"merchant_id\":\"m\",\"tag_ids\":[\"a\",\"b\"],\"transaction_id\":\"tx-1\"}", ordered)
        // A null merchant is distinct from a named one.
        assertEquals(
            "{\"merchant_id\":null,\"tag_ids\":[\"a\"],\"transaction_id\":\"tx-1\"}",
            canonicalTransactionAnnotationSnapshot(transactionId, listOf(TagId("a")), null),
        )
        // The snapshot carries no CAS fields, so a stale-revision retry of the SAME intent replays.
        assertEquals(annotationInputFingerprint(ordered), annotationInputFingerprint(canonicalTransactionAnnotationSnapshot(transactionId, listOf(TagId("a"), TagId("b")), MerchantId("m"))))
    }

    @Test
    fun frozenFailureCodeLiteralsMatchTheRegisteredNames() {
        assertEquals("TagMerchantNameEmpty", TagMerchantFailureCode.of(CatalogItemViolation.CatalogNameEmpty).code)
        assertEquals("TagMerchantNameTooLong", TagMerchantFailureCode.of(CatalogItemViolation.CatalogNameTooLong).code)
        assertEquals("TagMerchantNameInvalid", TagMerchantFailureCode.of(CatalogItemViolation.CatalogNameInvalid).code)
        assertEquals("TagMerchantNameConflict", TagMerchantFailureCode.of(CatalogItemViolation.CatalogNameConflict).code)
        assertEquals("TagMerchantNotFound", TagMerchantFailureCode.of(CatalogItemViolation.CatalogObjectNotFound).code)
        assertEquals("TagMerchantKindMismatch", TagMerchantFailureCode.of(CatalogItemViolation.CatalogItemKindMismatch).code)
        assertEquals("TagMerchantTombstoned", TagMerchantFailureCode.of(CatalogItemViolation.CatalogItemTombstoned).code)
        assertEquals("TagMerchantHasReferences", TagMerchantFailureCode.of(CatalogItemViolation.CatalogItemHasReferences).code)
        assertEquals("TagMerchantRevisionConflict", TagMerchantFailureCode.TAG_MERCHANT_REVISION_CONFLICT.code)
        assertEquals("RequestIdentityConflict", TagMerchantFailureCode.REQUEST_IDENTITY_CONFLICT.code)
        assertEquals("TagMerchantConstraintViolation", TagMerchantFailureCode.TAG_MERCHANT_CONSTRAINT_VIOLATION.code)

        assertEquals("AnnotationRevisionConflict", AnnotationFailureCode.ANNOTATION_REVISION_CONFLICT.code)
        assertEquals("AnnotationCurrentVersionConflict", AnnotationFailureCode.ANNOTATION_CURRENT_VERSION_CONFLICT.code)
        assertEquals("AnnotationVoidedTransaction", AnnotationFailureCode.ANNOTATION_VOIDED_TRANSACTION.code)
        assertEquals("AnnotationTooManyTags", AnnotationFailureCode.of(TransactionAnnotationViolation.TooManyTags).code)
        assertEquals("AnnotationUnknownTag", AnnotationFailureCode.of(TransactionAnnotationViolation.UnknownTag).code)
        assertEquals("AnnotationTagNotSelectable", AnnotationFailureCode.of(TransactionAnnotationViolation.TagNotSelectable).code)
        assertEquals("AnnotationTagCrossLedger", AnnotationFailureCode.of(TransactionAnnotationViolation.TagCrossLedger).code)
        assertEquals("AnnotationUnknownMerchant", AnnotationFailureCode.of(TransactionAnnotationViolation.UnknownMerchant).code)
        assertEquals("AnnotationMerchantNotSelectable", AnnotationFailureCode.of(TransactionAnnotationViolation.MerchantNotSelectable).code)
        assertEquals("AnnotationMerchantCrossLedger", AnnotationFailureCode.of(TransactionAnnotationViolation.MerchantCrossLedger).code)
        assertEquals("RequestIdentityConflict", AnnotationFailureCode.REQUEST_IDENTITY_CONFLICT.code)
        assertEquals("AnnotationConstraintViolation", AnnotationFailureCode.ANNOTATION_CONSTRAINT_VIOLATION.code)
    }
}
