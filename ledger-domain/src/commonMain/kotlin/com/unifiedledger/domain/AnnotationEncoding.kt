package com.unifiedledger.domain

/*
 * P7-08 08.B-1 (D-221; spec section 4.3, ruling R-5) canonical manual-request annotation tagging.
 *
 * The write side (the claim-table UPDATE), the read-back side (the commit lookup SELECT
 * projections) and the attempted-snapshot side (the resolver's expected snapshot) MUST share this
 * ONE encoding function, otherwise an empty-association create would compare unequal and produce a
 * false `SnapshotConflict`.
 *
 * Encoding (frozen): after dedup and stable-id sort, an empty tag set serializes to NULL; a
 * non-empty set is the stable ids joined by [ANNOTATION_TAG_SEPARATOR] in order. A null merchant is
 * no merchant. Legacy (pre-v35) rows carry NULL in both columns and decode to the empty set / null
 * merchant, i.e. "no annotation", which is exactly the frozen default the structured replay matcher
 * compares against.
 */

/** The stable, ordered join between tag ids in the canonical claim-table encoding. */
const val ANNOTATION_TAG_SEPARATOR: String = "\u001F"

/** Encodes a tag-id set into its canonical NULL-or-joined storage form (ruling R-5). */
fun encodeAnnotationTagIds(tagIds: Collection<TagId>): String? {
    val distinctSorted = tagIds.map { it.value }.distinct().sorted()
    return if (distinctSorted.isEmpty()) null else distinctSorted.joinToString(ANNOTATION_TAG_SEPARATOR)
}

/** Encodes an optional merchant id into its canonical NULL-or-id storage form. */
fun encodeAnnotationMerchantId(merchantId: MerchantId?): String? = merchantId?.value

/** Decodes the canonical tag-id encoding back into the sorted, deduplicated stable-id list. */
fun decodeAnnotationTagIds(encoded: String?): List<TagId> =
    if (encoded.isNullOrEmpty()) {
        emptyList()
    } else {
        encoded.split(ANNOTATION_TAG_SEPARATOR).filter { it.isNotEmpty() }.distinct().sorted().map(::TagId)
    }

/** Decodes the canonical merchant encoding back into an optional stable id. */
fun decodeAnnotationMerchantId(encoded: String?): MerchantId? = encoded?.let(::MerchantId)
