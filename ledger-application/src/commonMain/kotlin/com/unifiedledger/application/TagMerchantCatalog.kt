package com.unifiedledger.application

import com.unifiedledger.domain.CatalogItem
import com.unifiedledger.domain.CatalogItemKind
import com.unifiedledger.domain.CatalogItemViolation
import com.unifiedledger.domain.CatalogItemWrite
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.createCatalogItem
import com.unifiedledger.domain.renameCatalogItem
import com.unifiedledger.domain.setCatalogItemActive
import com.unifiedledger.domain.tombstoneCatalogItem

/*
 * P7-08 08.A (D-187; spec sections 2.2/8 open items 1/3/9) tag/merchant catalog command contract.
 *
 * Mirrors the P7-01 catalog owner's protocol shape: one request shape carrying the claim
 * identity, the canonical `requestSnapshot` (the sole equivalent-replay basis), a derived
 * `inputFingerprint` that never participates in equivalence, the optimistic per-item
 * `expectedRevision` (the BudgetCommandRequest precedent, spec section 2.2) and the command
 * payload; the result is one of four states Accepted / NoChange / Rejected / Conflict.
 *
 * Open item 1 (exact use-case/port signatures and failure-code tokens) is ruled here and
 * registered: the names below are frozen by this implementation batch.
 */

/** Independent tag/merchant request id source (spec section 2.2); never shares a consumption count. */
data class TagMerchantRequestId(
    val value: String,
)

/**
 * Stable tag/merchant failure codes (spec section 8 open item 1). [code] is the frozen literal
 * compared by consumers; enum constant names are only an internal spelling. Messages are never
 * compared. `RequestIdentityConflict` is deliberately the same literal the other owners use.
 */
enum class TagMerchantFailureCode(
    val code: String,
) {
    TAG_MERCHANT_NAME_EMPTY("TagMerchantNameEmpty"),
    TAG_MERCHANT_NAME_TOO_LONG("TagMerchantNameTooLong"),
    TAG_MERCHANT_NAME_INVALID("TagMerchantNameInvalid"),
    TAG_MERCHANT_NAME_CONFLICT("TagMerchantNameConflict"),
    TAG_MERCHANT_NOT_FOUND("TagMerchantNotFound"),
    TAG_MERCHANT_KIND_MISMATCH("TagMerchantKindMismatch"),
    TAG_MERCHANT_TOMBSTONED("TagMerchantTombstoned"),
    TAG_MERCHANT_HAS_REFERENCES("TagMerchantHasReferences"),
    TAG_MERCHANT_REVISION_CONFLICT("TagMerchantRevisionConflict"),
    REQUEST_IDENTITY_CONFLICT("RequestIdentityConflict"),
    TAG_MERCHANT_CONSTRAINT_VIOLATION("TagMerchantConstraintViolation"),
    ;

    companion object {
        /** Maps a domain [DomainViolation] to its frozen failure code; unknown falls back to the generic code. */
        fun of(violation: DomainViolation): TagMerchantFailureCode =
            when (violation) {
                CatalogItemViolation.CatalogNameEmpty -> TAG_MERCHANT_NAME_EMPTY
                CatalogItemViolation.CatalogNameTooLong -> TAG_MERCHANT_NAME_TOO_LONG
                CatalogItemViolation.CatalogNameInvalid -> TAG_MERCHANT_NAME_INVALID
                CatalogItemViolation.CatalogNameConflict -> TAG_MERCHANT_NAME_CONFLICT
                CatalogItemViolation.CatalogObjectNotFound -> TAG_MERCHANT_NOT_FOUND
                CatalogItemViolation.CatalogItemKindMismatch -> TAG_MERCHANT_KIND_MISMATCH
                CatalogItemViolation.CatalogItemTombstoned -> TAG_MERCHANT_TOMBSTONED
                CatalogItemViolation.CatalogItemHasReferences -> TAG_MERCHANT_HAS_REFERENCES
                else -> TAG_MERCHANT_CONSTRAINT_VIOLATION
            }
    }
}

/** The four tag/merchant catalog commands (spec section 2.2). */
sealed interface CatalogItemCommandPayload {
    val commandName: String

    /** The namespace this command targets (independent tag/merchant namespaces). */
    val kind: CatalogItemKind

    data class CreateItem(
        override val kind: CatalogItemKind,
        val name: String,
    ) : CatalogItemCommandPayload {
        override val commandName: String = "CreateCatalogItem"
    }

    data class RenameItem(
        override val kind: CatalogItemKind,
        val id: String,
        val newName: String,
    ) : CatalogItemCommandPayload {
        override val commandName: String = "RenameCatalogItem"
    }

    data class SetItemActive(
        override val kind: CatalogItemKind,
        val id: String,
        val active: Boolean,
    ) : CatalogItemCommandPayload {
        override val commandName: String = "SetCatalogItemActive"
    }

    data class DeleteItem(
        override val kind: CatalogItemKind,
        val id: String,
    ) : CatalogItemCommandPayload {
        override val commandName: String = "DeleteCatalogItem"
    }
}

data class TagMerchantCommandRequest(
    val ledgerId: LedgerId,
    val requestId: TagMerchantRequestId,
    val requestSnapshot: String,
    val inputFingerprint: String,
    val expectedRevision: Long,
    val command: CatalogItemCommandPayload,
)

/** Persisted request outcome value domain; only successful claims reach a terminal row. */
enum class TagMerchantReceiptOutcome {
    ACCEPTED,
    NO_CHANGE,
}

data class TagMerchantCommandReceipt(
    val requestId: TagMerchantRequestId,
    val outcome: TagMerchantReceiptOutcome,
    val kind: CatalogItemKind,
    val itemId: String,
    val newRevision: Long,
)

sealed interface TagMerchantCommandResult {
    data class Accepted(
        val receipt: TagMerchantCommandReceipt,
    ) : TagMerchantCommandResult

    data class NoChange(
        val receipt: TagMerchantCommandReceipt,
    ) : TagMerchantCommandResult

    data class Rejected(
        val failureCode: TagMerchantFailureCode,
    ) : TagMerchantCommandResult

    data class Conflict(
        val failureCode: TagMerchantFailureCode,
    ) : TagMerchantCommandResult
}

/**
 * Current authoritative tag/merchant catalog of one ledger. [catalogItemVersion] is the dedicated
 * ledger-level counter (spec section 8 open item 9, ruled in this batch): the future filter read
 * compares it inside one read transaction instead of sharing `CatalogAuthority.catalogVersion`.
 */
data class TagMerchantAuthority(
    val ledgerId: LedgerId,
    val tags: List<CatalogItem>,
    val merchants: List<CatalogItem>,
    val catalogItemVersion: Long,
)

/**
 * Claim-first atomic catalog-item command boundary. Implementations MUST:
 *
 * - claim `(ledgerId, requestId)` in the same transaction as work, name history and receipt;
 * - return [TagMerchantCommandResult.NoChange] with the original receipt on an equivalent
 *   `requestSnapshot` replay and [TagMerchantCommandResult.Conflict] with `RequestIdentityConflict`
 *   when it differs (zero write in both cases);
 * - reject an `expectedRevision` mismatch with `TagMerchantRevisionConflict` and zero writes;
 * - invoke [apply] at most once and only for a fresh claim, passing the freshly minted id (empty
 *   for a non-create command);
 * - roll back the claim on every typed rejection so the identity stays retryable;
 * - on success write the item state and any name-history row, advance the catalog-item version by
 *   one and persist the terminal request row plus immutable receipt in one transaction.
 */
fun interface TagMerchantCommitPort {
    fun commitOnce(
        request: TagMerchantCommandRequest,
        mintId: () -> String,
        apply: (TagMerchantAuthority, String) -> DomainResult<List<CatalogItemWrite>>,
    ): TagMerchantCommandResult
}

/** Reference probe for the terminal tombstone delete (spec section 2.2). */
fun interface CatalogItemReferenceProbe {
    fun hasReferences(
        ledgerId: LedgerId,
        kind: CatalogItemKind,
        id: String,
    ): Boolean
}

/** Read port for the current authoritative tag/merchant catalog (spec section 2.2). */
fun interface TagMerchantCatalogReader {
    fun load(ledgerId: LedgerId): TagMerchantAuthority?
}

/**
 * Independent tag/merchant request id source (spec section 2.2). Kept separate from the catalog,
 * budget and manual-entry sources so no consumption count is shared.
 */
fun interface TagMerchantRequestIdSource {
    fun next(): TagMerchantRequestId
}

class UuidV7TagMerchantRequestIdSource(
    private val generator: UuidV7Generator,
) : TagMerchantRequestIdSource {
    override fun next(): TagMerchantRequestId = TagMerchantRequestId(generator.next())
}

/** Minted stable id for one fresh catalog item; only consumed when a fresh claim is applied. */
fun interface CatalogItemIdSource {
    fun next(): String
}

class UuidV7CatalogItemIdSource(
    private val generator: UuidV7Generator,
) : CatalogItemIdSource {
    override fun next(): String = generator.next()
}

/**
 * P7-08 08.A catalog-item use case (spec section 2.2). Each command mints an independent request
 * id, derives the canonical request snapshot and its non-identity fingerprint, and delegates the
 * atomic claim/work/receipt boundary to the injected [TagMerchantCommitPort].
 */
class ExecuteTagMerchantCommand(
    private val commitPort: TagMerchantCommitPort,
    private val requestIdSource: TagMerchantRequestIdSource,
    private val idSource: CatalogItemIdSource,
    private val referenceProbe: CatalogItemReferenceProbe,
) {
    fun createItem(
        ledgerId: LedgerId,
        kind: CatalogItemKind,
        name: String,
        expectedRevision: Long,
    ): TagMerchantCommandResult =
        execute(ledgerId, expectedRevision, CatalogItemCommandPayload.CreateItem(kind, name)) { authority, mintedId ->
            createCatalogItem(kind, items(authority, kind), ledgerId, mintedId, name)
        }

    fun renameItem(
        ledgerId: LedgerId,
        kind: CatalogItemKind,
        id: String,
        newName: String,
        expectedRevision: Long,
    ): TagMerchantCommandResult =
        execute(ledgerId, expectedRevision, CatalogItemCommandPayload.RenameItem(kind, id, newName)) { authority, _ ->
            renameCatalogItem(items(authority, kind), ledgerId, kind, id, newName)
        }

    fun setItemActive(
        ledgerId: LedgerId,
        kind: CatalogItemKind,
        id: String,
        active: Boolean,
        expectedRevision: Long,
    ): TagMerchantCommandResult =
        execute(ledgerId, expectedRevision, CatalogItemCommandPayload.SetItemActive(kind, id, active)) { authority, _ ->
            setCatalogItemActive(items(authority, kind), ledgerId, kind, id, active)
        }

    fun deleteItem(
        ledgerId: LedgerId,
        kind: CatalogItemKind,
        id: String,
        expectedRevision: Long,
    ): TagMerchantCommandResult =
        execute(ledgerId, expectedRevision, CatalogItemCommandPayload.DeleteItem(kind, id)) { authority, _ ->
            tombstoneCatalogItem(items(authority, kind), ledgerId, kind, id) {
                referenceProbe.hasReferences(ledgerId, kind, id)
            }
        }

    private fun execute(
        ledgerId: LedgerId,
        expectedRevision: Long,
        payload: CatalogItemCommandPayload,
        apply: (TagMerchantAuthority, String) -> DomainResult<List<CatalogItemWrite>>,
    ): TagMerchantCommandResult {
        val snapshot = canonicalTagMerchantRequestSnapshot(payload)
        val request =
            TagMerchantCommandRequest(
                ledgerId = ledgerId,
                requestId = requestIdSource.next(),
                requestSnapshot = snapshot,
                inputFingerprint = tagMerchantInputFingerprint(snapshot),
                expectedRevision = expectedRevision,
                command = payload,
            )
        return commitPort.commitOnce(request, { idSource.next() }, apply)
    }

    private fun items(
        authority: TagMerchantAuthority,
        kind: CatalogItemKind,
    ): List<CatalogItem> = if (kind == CatalogItemKind.TAG) authority.tags else authority.merchants
}

/**
 * Canonical command-payload copy: the only equivalent-replay basis (spec section 2.2). Field order
 * is fixed and every value is JCS-escaped. The request id is absent (it is the claim identity) and
 * `expectedRevision` is excluded so a stale-revision retry of the SAME payload replays the original
 * receipt.
 */
fun canonicalTagMerchantRequestSnapshot(payload: CatalogItemCommandPayload): String =
    when (payload) {
        is CatalogItemCommandPayload.CreateItem ->
            "{\"command\":${jcsString(payload.commandName)},\"kind\":${jcsString(payload.kind.name)},\"name\":${jcsString(payload.name)}}"
        is CatalogItemCommandPayload.RenameItem ->
            "{\"command\":${jcsString(payload.commandName)},\"id\":${jcsString(payload.id)},\"kind\":${jcsString(payload.kind.name)},\"new_name\":${jcsString(payload.newName)}}"
        is CatalogItemCommandPayload.SetItemActive ->
            "{\"active\":${jcsString(payload.active.toString())},\"command\":${jcsString(payload.commandName)},\"id\":${jcsString(payload.id)},\"kind\":${jcsString(payload.kind.name)}}"
        is CatalogItemCommandPayload.DeleteItem ->
            "{\"command\":${jcsString(payload.commandName)},\"id\":${jcsString(payload.id)},\"kind\":${jcsString(payload.kind.name)}}"
    }

/** Derived integrity digest; explicitly not part of equivalent-replay identity. */
fun tagMerchantInputFingerprint(snapshot: String): String = "sha256:" + Sha256.digestHex(snapshot.encodeToByteArray())
