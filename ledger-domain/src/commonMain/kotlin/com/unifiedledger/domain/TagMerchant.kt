package com.unifiedledger.domain

/*
 * P7-08 08.A pure domain surface for the tag/merchant catalogs and the transaction annotation
 * aggregate, approved by D-187 /
 * `docs/specs/2026-09-30-p7-08-tag-merchant-design.md` sections 2 and 3. Everything here is a
 * pure function over an already constructed catalog snapshot: no IO, no Clock and no random
 * source. Identifier minting, persistence and optimistic concurrency live in the application/data
 * layers. Name normalization deliberately REUSES the frozen P7-01 [normalizeCatalogName] contract
 * and therefore reports its [CatalogViolation] family unchanged; no second normalization
 * implementation exists (spec section 2.3).
 */

/** Stable tag identity (spec section 2.2). The id is minted by the caller, never derived from a name. */
data class TagId(
    val value: String,
)

/** Stable merchant identity (spec section 2.2). Independent from the tag namespace. */
data class MerchantId(
    val value: String,
)

/**
 * The two independent catalog namespaces (spec section 2.1). The same display name may exist in
 * both namespaces at once; a cross-ledger or cross-namespace reference is a typed failure.
 */
enum class CatalogItemKind {
    TAG,
    MERCHANT,
}

/**
 * Aggregation cardinality (spec section 2.1): one annotation revision holds 0..[MAX_ANNOTATION_TAGS]
 * tags and 0..1 merchant. The tag bound is enforced at the request-validation layer AND by a
 * storage-side guard trigger (spec section 3.2); the merchant bound is the nullable column itself.
 */
const val MAX_ANNOTATION_TAGS: Int = 20

/** The sentinel `expectedAnnotationRevision` meaning "the transaction currently has no annotation". */
const val NO_ANNOTATION_REVISION: Long = 0L

/**
 * One catalog item as loaded for a command (spec section 2.2). [tombstoned] rows carry a
 * [revision] just like live rows so the terminal delete is itself one forward CAS step.
 */
data class CatalogItem(
    val kind: CatalogItemKind,
    val id: String,
    val ledgerId: LedgerId,
    val name: String,
    val active: Boolean,
    val tombstoned: Boolean,
    val revision: Long,
)

/**
 * Typed catalog-item violations. Each token maps to exactly one stable failure code in the
 * application layer. The name tokens are the frozen [CatalogViolation] family reused unchanged.
 */
sealed interface CatalogItemViolation : DomainViolation {
    data object CatalogNameEmpty : CatalogItemViolation

    data object CatalogNameTooLong : CatalogItemViolation

    data object CatalogNameInvalid : CatalogItemViolation

    data object CatalogNameConflict : CatalogItemViolation

    data object CatalogObjectNotFound : CatalogItemViolation

    data object CatalogItemTombstoned : CatalogItemViolation

    data object CatalogItemHasReferences : CatalogItemViolation

    data object CatalogItemKindMismatch : CatalogItemViolation
}

/**
 * Storage-neutral mutation vocabulary. The application layer derives an ordered list of these
 * from a pure transition; the store applies them in a single claim-first transaction.
 */
sealed interface CatalogItemWrite {
    data class Insert(
        val kind: CatalogItemKind,
        val id: String,
        val name: String,
    ) : CatalogItemWrite

    data class SetActiveState(
        val kind: CatalogItemKind,
        val id: String,
        val active: Boolean,
    ) : CatalogItemWrite

    data class Rename(
        val kind: CatalogItemKind,
        val id: String,
        val name: String,
    ) : CatalogItemWrite

    data class Tombstone(
        val kind: CatalogItemKind,
        val id: String,
    ) : CatalogItemWrite
}

/**
 * Creates a tag/merchant (spec section 2.2). The caller mints the stable [id] after claim
 * (mint-id-post-claim). A name already taken in the SAME namespace of the same ledger is a typed
 * conflict; the other namespace is unaffected.
 */
fun createCatalogItem(
    kind: CatalogItemKind,
    catalog: List<CatalogItem>,
    ledgerId: LedgerId,
    id: String,
    name: String,
): DomainResult<List<CatalogItemWrite>> {
    if (id.isBlank()) return DomainResult.Failure(CatalogItemViolation.CatalogObjectNotFound)
    val normalized =
        when (val result = normalizeCatalogName(name)) {
            is DomainResult.Failure -> return result.toItemFailure()
            is DomainResult.Success -> result.value
        }
    if (catalogItemNameTaken(catalog, ledgerId, kind, normalized, excluding = null)) {
        return DomainResult.Failure(CatalogItemViolation.CatalogNameConflict)
    }
    return DomainResult.Success(listOf(CatalogItemWrite.Insert(kind, id, normalized)))
}

/**
 * Renames a catalog item by superseding the current name version and appending a new one
 * (spec section 2.2). The stable id never changes; a tombstoned item cannot be renamed.
 */
fun renameCatalogItem(
    catalog: List<CatalogItem>,
    ledgerId: LedgerId,
    kind: CatalogItemKind,
    id: String,
    newName: String,
): DomainResult<List<CatalogItemWrite>> {
    val item = catalogItem(catalog, ledgerId, kind, id) ?: return failureFor(catalog, ledgerId, kind, id)
    if (item.tombstoned) return DomainResult.Failure(CatalogItemViolation.CatalogItemTombstoned)
    val normalized =
        when (val result = normalizeCatalogName(newName)) {
            is DomainResult.Failure -> return result.toItemFailure()
            is DomainResult.Success -> result.value
        }
    if (catalogItemNameTaken(catalog, ledgerId, kind, normalized, excluding = id)) {
        return DomainResult.Failure(CatalogItemViolation.CatalogNameConflict)
    }
    return DomainResult.Success(listOf(CatalogItemWrite.Rename(kind, id, normalized)))
}

/**
 * Enables or disables an item (spec section 2.2). Disabling (active=false) is a reversible soft
 * disable that keeps every historical reference; a tombstoned item can never be re-enabled.
 */
fun setCatalogItemActive(
    catalog: List<CatalogItem>,
    ledgerId: LedgerId,
    kind: CatalogItemKind,
    id: String,
    active: Boolean,
): DomainResult<List<CatalogItemWrite>> {
    val item = catalogItem(catalog, ledgerId, kind, id) ?: return failureFor(catalog, ledgerId, kind, id)
    if (item.tombstoned) return DomainResult.Failure(CatalogItemViolation.CatalogItemTombstoned)
    return DomainResult.Success(listOf(CatalogItemWrite.SetActiveState(kind, id, active)))
}

/**
 * Terminal tombstone delete (spec section 2.2). Only allowed with NO current or historical
 * annotation reference; the row is kept, marked tombstoned and never selectable again. The
 * stable id is never reused.
 */
fun tombstoneCatalogItem(
    catalog: List<CatalogItem>,
    ledgerId: LedgerId,
    kind: CatalogItemKind,
    id: String,
    hasReferences: () -> Boolean,
): DomainResult<List<CatalogItemWrite>> {
    val item = catalogItem(catalog, ledgerId, kind, id) ?: return failureFor(catalog, ledgerId, kind, id)
    if (item.tombstoned) return DomainResult.Failure(CatalogItemViolation.CatalogItemTombstoned)
    if (hasReferences()) return DomainResult.Failure(CatalogItemViolation.CatalogItemHasReferences)
    return DomainResult.Success(listOf(CatalogItemWrite.Tombstone(kind, id)))
}

private fun catalogItem(
    catalog: List<CatalogItem>,
    ledgerId: LedgerId,
    kind: CatalogItemKind,
    id: String,
): CatalogItem? = catalog.firstOrNull { it.ledgerId == ledgerId && it.kind == kind && it.id == id }

/**
 * A missing id and an id that exists in the OTHER namespace are distinguished: the latter is a
 * typed kind-mismatch so a cross-namespace reference can never silently look "not found".
 */
private fun failureFor(
    catalog: List<CatalogItem>,
    ledgerId: LedgerId,
    kind: CatalogItemKind,
    id: String,
): DomainResult.Failure {
    val existsOtherKind = catalog.any { it.ledgerId == ledgerId && it.kind != kind && it.id == id }
    return DomainResult.Failure(
        if (existsOtherKind) CatalogItemViolation.CatalogItemKindMismatch else CatalogItemViolation.CatalogObjectNotFound,
    )
}

private fun catalogItemNameTaken(
    catalog: List<CatalogItem>,
    ledgerId: LedgerId,
    kind: CatalogItemKind,
    name: String,
    excluding: String?,
): Boolean =
    catalog.any {
        it.ledgerId == ledgerId && it.kind == kind && it.id != excluding && it.name == name
    }

/**
 * Maps the frozen P7-01 name violations onto this family so the application layer can expose one
 * stable code set without ever duplicating the normalization logic.
 */
private fun DomainResult.Failure.toItemFailure(): DomainResult.Failure =
    DomainResult.Failure(
        when (violation) {
            CatalogViolation.CatalogNameEmpty -> CatalogItemViolation.CatalogNameEmpty
            CatalogViolation.CatalogNameTooLong -> CatalogItemViolation.CatalogNameTooLong
            CatalogViolation.CatalogNameInvalid -> CatalogItemViolation.CatalogNameInvalid
            else -> CatalogItemViolation.CatalogNameInvalid
        },
    )

/**
 * Anonymous transaction annotation aggregate (spec section 3.1): the tag set and optional merchant
 * of one annotation revision. [tagIds] is deduplicated and ordered by stable id (spec section 2.3).
 */
data class TransactionAnnotation(
    val tagIds: List<TagId>,
    val merchantId: MerchantId?,
)

/**
 * Pure annotation violations (spec sections 3.1/3.2). The tag-count bound and the merchant
 * cardinality are validated here before any claim; the storage-side guard trigger and the
 * nullable column are the defense in depth.
 */
sealed interface TransactionAnnotationViolation : DomainViolation {
    data object TooManyTags : TransactionAnnotationViolation

    data object UnknownTag : TransactionAnnotationViolation

    data object TagNotSelectable : TransactionAnnotationViolation

    data object TagCrossLedger : TransactionAnnotationViolation

    data object UnknownMerchant : TransactionAnnotationViolation

    data object MerchantNotSelectable : TransactionAnnotationViolation

    data object MerchantCrossLedger : TransactionAnnotationViolation
}

/**
 * Validates a requested annotation against the loaded catalogs (spec sections 2.1/3.2). Returns the
 * canonical aggregate (deduplicated, stable-id-ordered) on success. A reference to an unknown,
 * tombstoned or inactive item, or an item of another ledger, is a typed failure and writes nothing.
 */
fun validateTransactionAnnotation(
    ledgerId: LedgerId,
    tagIds: List<TagId>,
    merchantId: MerchantId?,
    tags: List<CatalogItem>,
    merchants: List<CatalogItem>,
): DomainResult<TransactionAnnotation> {
    val distinctTagIds = tagIds.map { it.value }.distinct().sorted()
    if (distinctTagIds.size > MAX_ANNOTATION_TAGS) {
        return DomainResult.Failure(TransactionAnnotationViolation.TooManyTags)
    }
    distinctTagIds.forEach { tagId ->
        val tag = tags.firstOrNull { it.id == tagId }
        when {
            tag == null -> return DomainResult.Failure(TransactionAnnotationViolation.UnknownTag)
            tag.ledgerId != ledgerId -> return DomainResult.Failure(TransactionAnnotationViolation.TagCrossLedger)
            tag.tombstoned || !tag.active -> return DomainResult.Failure(TransactionAnnotationViolation.TagNotSelectable)
        }
    }
    if (merchantId != null) {
        val merchant = merchants.firstOrNull { it.id == merchantId.value }
        when {
            merchant == null -> return DomainResult.Failure(TransactionAnnotationViolation.UnknownMerchant)
            merchant.ledgerId != ledgerId -> return DomainResult.Failure(TransactionAnnotationViolation.MerchantCrossLedger)
            merchant.tombstoned || !merchant.active -> return DomainResult.Failure(TransactionAnnotationViolation.MerchantNotSelectable)
        }
    }
    return DomainResult.Success(
        TransactionAnnotation(
            tagIds = distinctTagIds.map(::TagId),
            merchantId = merchantId,
        ),
    )
}
