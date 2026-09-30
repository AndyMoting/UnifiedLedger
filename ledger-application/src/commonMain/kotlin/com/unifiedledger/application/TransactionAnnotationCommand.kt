package com.unifiedledger.application

import com.unifiedledger.domain.CatalogItem
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.MerchantId
import com.unifiedledger.domain.TagId
import com.unifiedledger.domain.TransactionAnnotation
import com.unifiedledger.domain.TransactionAnnotationViolation
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.validateTransactionAnnotation
import kotlin.time.Instant

/*
 * P7-08 08.A (D-187; spec sections 3.1/3.2/4.3) transaction annotation command contract.
 *
 * An independent command family mirroring the note-update chain's structure (explicit request +
 * canonical snapshot + claim-first + CAS + stale-removes-claim + receipt) without merging into it.
 * Every edit REPLACES the whole tag set and merchant as one new immutable annotation revision; the
 * `expectedAnnotationRevision` CAS uses the sentinel 0 meaning "the transaction currently has no
 * annotation" (no current-pointer row), mirroring the 07.B `expectedRevision == 0` fresh-binding
 * precedent. `expectedCurrentVersionId` additionally pins the financial version the edit saw.
 *
 * Open item 1 (use-case/port signatures and failure-code tokens) and open item 4 (claim-table
 * annotation encoding) are ruled here and registered: the annotation fields are persisted as
 * dedicated request-table columns (with `NULL` meaning "no annotation" for legacy rows), and the
 * replay equivalence is a STRUCTURED COLUMN comparison — never a bare snapshot-string comparison —
 * so an old note/other request row that lacks annotation columns matches the frozen default
 * "no annotation" (spec section 4.3).
 */

data class AnnotationRequestId(
    val value: String,
)

/**
 * Stable annotation failure codes (spec section 8 open item 1). [code] is the frozen literal
 * compared by consumers; enum constant names are only an internal spelling.
 */
enum class AnnotationFailureCode(
    val code: String,
) {
    ANNOTATION_REVISION_CONFLICT("AnnotationRevisionConflict"),
    ANNOTATION_CURRENT_VERSION_CONFLICT("AnnotationCurrentVersionConflict"),
    ANNOTATION_VOIDED_TRANSACTION("AnnotationVoidedTransaction"),
    ANNOTATION_TOO_MANY_TAGS("AnnotationTooManyTags"),
    ANNOTATION_UNKNOWN_TAG("AnnotationUnknownTag"),
    ANNOTATION_TAG_NOT_SELECTABLE("AnnotationTagNotSelectable"),
    ANNOTATION_TAG_CROSS_LEDGER("AnnotationTagCrossLedger"),
    ANNOTATION_UNKNOWN_MERCHANT("AnnotationUnknownMerchant"),
    ANNOTATION_MERCHANT_NOT_SELECTABLE("AnnotationMerchantNotSelectable"),
    ANNOTATION_MERCHANT_CROSS_LEDGER("AnnotationMerchantCrossLedger"),
    REQUEST_IDENTITY_CONFLICT("RequestIdentityConflict"),
    ANNOTATION_CONSTRAINT_VIOLATION("AnnotationConstraintViolation"),
    ;

    companion object {
        fun of(violation: DomainViolation): AnnotationFailureCode =
            when (violation) {
                TransactionAnnotationViolation.TooManyTags -> ANNOTATION_TOO_MANY_TAGS
                TransactionAnnotationViolation.UnknownTag -> ANNOTATION_UNKNOWN_TAG
                TransactionAnnotationViolation.TagNotSelectable -> ANNOTATION_TAG_NOT_SELECTABLE
                TransactionAnnotationViolation.TagCrossLedger -> ANNOTATION_TAG_CROSS_LEDGER
                TransactionAnnotationViolation.UnknownMerchant -> ANNOTATION_UNKNOWN_MERCHANT
                TransactionAnnotationViolation.MerchantNotSelectable -> ANNOTATION_MERCHANT_NOT_SELECTABLE
                TransactionAnnotationViolation.MerchantCrossLedger -> ANNOTATION_MERCHANT_CROSS_LEDGER
                else -> ANNOTATION_CONSTRAINT_VIOLATION
            }
    }
}

data class TransactionAnnotationRequest(
    val ledgerId: LedgerId,
    val requestId: AnnotationRequestId,
    val transactionId: TransactionId,
    val tagIds: List<TagId>,
    val merchantId: MerchantId?,
    val expectedAnnotationRevision: Long,
    val expectedCurrentVersionId: TransactionVersionId,
    val requestSnapshot: String,
    val inputFingerprint: String,
    val createdAt: Instant,
)

/** Persisted request outcome value domain; only successful claims reach a terminal row. */
enum class AnnotationReceiptOutcome {
    ACCEPTED,
    NO_CHANGE,
}

data class TransactionAnnotationReceipt(
    val requestId: AnnotationRequestId,
    val outcome: AnnotationReceiptOutcome,
    val transactionId: TransactionId,
    val newAnnotationRevision: Long,
)

sealed interface TransactionAnnotationResult {
    data class Accepted(
        val receipt: TransactionAnnotationReceipt,
    ) : TransactionAnnotationResult

    data class NoChange(
        val receipt: TransactionAnnotationReceipt,
    ) : TransactionAnnotationResult

    data class Rejected(
        val failureCode: AnnotationFailureCode,
    ) : TransactionAnnotationResult

    data class Conflict(
        val failureCode: AnnotationFailureCode,
    ) : TransactionAnnotationResult
}

/**
 * Current annotation authority for one transaction, loaded inside the commit transaction.
 * [currentAnnotationRevision] is 0 (the sentinel) exactly when no current-pointer row exists.
 */
data class TransactionAnnotationAuthority(
    val ledgerId: LedgerId,
    val transactionId: TransactionId,
    val currentAnnotationRevision: Long,
    val currentVersionId: TransactionVersionId,
    val effective: Boolean,
    val tags: List<CatalogItem>,
    val merchants: List<CatalogItem>,
)

/**
 * Claim-first atomic annotation boundary. Implementations MUST:
 *
 * - claim `(ledgerId, requestId)` in the same transaction as the revision/association/pointer/receipt;
 * - resolve an existing claim by a STRUCTURED COLUMN match, returning [TransactionAnnotationResult.NoChange]
 *   with the original receipt on equivalence and `RequestIdentityConflict` otherwise (zero write);
 * - reject an `expectedAnnotationRevision` mismatch (sentinel 0 == "no current annotation") with
 *   `AnnotationRevisionConflict` and zero writes; reject an `expectedCurrentVersionId` mismatch with
 *   `AnnotationCurrentVersionConflict` and zero writes;
 * - append exactly one immutable revision + association set and (insert-or-advance) the current
 *   pointer in the same transaction as the terminal request row and immutable receipt.
 */
fun interface TransactionAnnotationCommitPort {
    fun commitOnce(
        request: TransactionAnnotationRequest,
        apply: (TransactionAnnotationAuthority) -> DomainResult<TransactionAnnotation>,
    ): TransactionAnnotationResult
}

/** Independent annotation request id source (spec section 3.1); never shares a consumption count. */
fun interface AnnotationRequestIdSource {
    fun next(): AnnotationRequestId
}

class UuidV7AnnotationRequestIdSource(
    private val generator: UuidV7Generator,
) : AnnotationRequestIdSource {
    override fun next(): AnnotationRequestId = AnnotationRequestId(generator.next())
}

/**
 * 08.A annotation update use case (spec sections 3.1/4.4): replace the whole tag set and merchant of
 * a not-yet-voided transaction as one new revision. Validates the catalog references through the
 * injected commit port's authority; the CAS and the conservative "current not voided" gate are the
 * store's responsibility (data layer).
 */
class UpdateTransactionAnnotation(
    private val commitPort: TransactionAnnotationCommitPort,
    private val requestIdSource: AnnotationRequestIdSource,
    private val clock: LedgerClock,
) {
    fun update(
        ledgerId: LedgerId,
        transactionId: TransactionId,
        tagIds: List<TagId>,
        merchantId: MerchantId?,
        expectedAnnotationRevision: Long,
        expectedCurrentVersionId: TransactionVersionId,
    ): TransactionAnnotationResult {
        val snapshot = canonicalTransactionAnnotationSnapshot(transactionId, tagIds, merchantId)
        val request =
            TransactionAnnotationRequest(
                ledgerId = ledgerId,
                requestId = requestIdSource.next(),
                transactionId = transactionId,
                tagIds = tagIds,
                merchantId = merchantId,
                expectedAnnotationRevision = expectedAnnotationRevision,
                expectedCurrentVersionId = expectedCurrentVersionId,
                requestSnapshot = snapshot,
                inputFingerprint = annotationInputFingerprint(snapshot),
                createdAt = clock.now(),
            )
        return commitPort.commitOnce(request) { authority ->
            validateTransactionAnnotation(
                ledgerId = ledgerId,
                tagIds = tagIds,
                merchantId = merchantId,
                tags = authority.tags,
                merchants = authority.merchants,
            )
        }
    }
}

/**
 * Canonical annotation request copy: the sole equivalent-replay basis. Field order is fixed and
 * every value is JCS-escaped. The tag set is deduplicated and sorted by stable id (spec section 2.3,
 * never by display name); `expectedAnnotationRevision`/`expectedCurrentVersionId` are excluded so a
 * stale-CAS retry of the SAME intent still replays the original receipt.
 */
fun canonicalTransactionAnnotationSnapshot(
    transactionId: TransactionId,
    tagIds: List<TagId>,
    merchantId: MerchantId?,
): String {
    val orderedTags = tagIds.map { it.value }.distinct().sorted()
    val tagsJson = orderedTags.joinToString(prefix = "[", postfix = "]", separator = ",") { jcsString(it) }
    val merchantJson = if (merchantId == null) "null" else jcsString(merchantId.value)
    return "{\"merchant_id\":$merchantJson,\"tag_ids\":$tagsJson,\"transaction_id\":${jcsString(transactionId.value)}}"
}

/** Derived integrity digest; explicitly not part of equivalent-replay identity. */
fun annotationInputFingerprint(snapshot: String): String = "sha256:" + Sha256.digestHex(snapshot.encodeToByteArray())
