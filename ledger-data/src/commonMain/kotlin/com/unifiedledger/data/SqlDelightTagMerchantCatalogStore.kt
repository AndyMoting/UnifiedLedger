package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.CatalogItemCommandPayload
import com.unifiedledger.application.CatalogItemReferenceProbe
import com.unifiedledger.application.TagMerchantAuthority
import com.unifiedledger.application.TagMerchantCatalogReader
import com.unifiedledger.application.TagMerchantCommandReceipt
import com.unifiedledger.application.TagMerchantCommandRequest
import com.unifiedledger.application.TagMerchantCommandResult
import com.unifiedledger.application.TagMerchantCommitPort
import com.unifiedledger.application.TagMerchantFailureCode
import com.unifiedledger.application.TagMerchantReceiptOutcome
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.CatalogItem
import com.unifiedledger.domain.CatalogItemKind
import com.unifiedledger.domain.CatalogItemWrite
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerId

/**
 * P7-08 08.A (D-187; spec section 2.2) tag/merchant catalog store.
 *
 * Claim-first atomic catalog boundary mirroring the P7-01 catalog owner / 07.B budget store: the
 * `(ledgerId, requestId)` claim, the item state (with any appended name-history row), the dedicated
 * `catalog_item_version` counter and the receipt are written in ONE transaction. An equivalent
 * snapshot replay returns the original receipt with zero writes; a same-id different-snapshot
 * request returns `RequestIdentityConflict`; a stale `expectedRevision` returns
 * `TagMerchantRevisionConflict` with zero writes.
 *
 * Mint-id-post-claim: the fresh identity's id is minted by the caller-provided [mintId] callback
 * ONLY after a fresh claim and ONLY for a create, exactly like the budget store's `mintBudgetId`
 * argument; an equivalent replay discards the minted id and returns the original receipt.
 * Product rows never participate in the frozen `rgXX_` silos or golden replay.
 */
class SqlDelightTagMerchantCatalogStore private constructor(
    private val database: LedgerDatabase,
) : TagMerchantCommitPort,
    TagMerchantCatalogReader,
    CatalogItemReferenceProbe {
    constructor(database: LedgerDatabase, driver: SqlDriver) : this(database) {
        configureSqliteConnection(driver)
    }

    override fun load(ledgerId: LedgerId): TagMerchantAuthority? = loadAuthority(ledgerId)

    override fun hasReferences(
        ledgerId: LedgerId,
        kind: CatalogItemKind,
        id: String,
    ): Boolean {
        val ledger = ledgerId.value
        return when (kind) {
            CatalogItemKind.TAG -> id in database.ledgerQueries.catalogReferencedTagIds(ledger).executeAsList()
            CatalogItemKind.MERCHANT -> id in database.ledgerQueries.catalogReferencedMerchantIds(ledger).executeAsList()
        }
    }

    override fun commitOnce(
        request: TagMerchantCommandRequest,
        mintId: () -> String,
        apply: (TagMerchantAuthority, String) -> DomainResult<List<CatalogItemWrite>>,
    ): TagMerchantCommandResult {
        val ledger = request.ledgerId.value
        return try {
            database.transactionWithResult {
                database.ledgerQueries.claimCatalogItemCommandRequest(
                    ledger_id = ledger,
                    request_id = request.requestId.value,
                    command = request.command.commandName,
                    owner_kind = kindToken(request.command.kind),
                    owner_id = targetIdOrNull(request.command) ?: "",
                    request_snapshot = request.requestSnapshot,
                    input_fingerprint = request.inputFingerprint,
                    outcome = "ACCEPTED",
                    expected_revision = request.expectedRevision,
                )
                if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
                    return@transactionWithResult resolveExisting(request)
                }

                val authority = loadAuthority(request.ledgerId) ?: TagMerchantAuthority(request.ledgerId, emptyList(), emptyList(), 0L)
                val existing = targetItem(authority, request.command)
                val currentRevision = existing?.revision ?: 0L
                if (currentRevision != request.expectedRevision) {
                    abortTagMerchant(TagMerchantFailureCode.TAG_MERCHANT_REVISION_CONFLICT, conflict = true)
                }

                // Mint the stable id only for a fresh create claim.
                val mintedId = if (request.command is CatalogItemCommandPayload.CreateItem) mintId() else ""
                val writes =
                    when (val result = apply(authority, mintedId)) {
                        is DomainResult.Failure -> abortTagMerchant(TagMerchantFailureCode.of(result.violation))
                        is DomainResult.Success -> result.value
                    }

                val newRevision = currentRevision + 1L
                writes.forEach { applyWrite(ledger, it, newRevision) }
                database.ledgerQueries.insertCatalogItemVersion(ledger, 0L)
                database.ledgerQueries.advanceCatalogItemVersion(ledger)

                val itemId = existing?.id ?: mintedId
                database.ledgerQueries.updateCatalogItemCommandRequestOwner(
                    owner_id = itemId,
                    ledger_id = ledger,
                    request_id = request.requestId.value,
                )
                database.ledgerQueries.updateCatalogItemCommandRequestOutcome(
                    outcome = "ACCEPTED",
                    result_revision = newRevision,
                    ledger_id = ledger,
                    request_id = request.requestId.value,
                )
                val receipt =
                    TagMerchantCommandReceipt(
                        requestId = request.requestId,
                        outcome = TagMerchantReceiptOutcome.ACCEPTED,
                        kind = request.command.kind,
                        itemId = itemId,
                        newRevision = newRevision,
                    )
                database.ledgerQueries.insertCatalogItemCommandReceipt(
                    ledger_id = ledger,
                    request_id = receipt.requestId.value,
                    outcome = "ACCEPTED",
                    owner_kind = kindToken(receipt.kind),
                    owner_id = receipt.itemId,
                    new_revision = receipt.newRevision,
                )
                TagMerchantCommandResult.Accepted(receipt)
            }
        } catch (rejected: TagMerchantTypedRollback) {
            rejected.result
        } catch (failure: Exception) {
            // Trigger/unique/FK fallback: the whole command transaction already rolled back, so the
            // identity stays retryable and no terminal row was written.
            TagMerchantCommandResult.Rejected(TagMerchantFailureCode.TAG_MERCHANT_CONSTRAINT_VIOLATION)
        }
    }

    private fun resolveExisting(request: TagMerchantCommandRequest): TagMerchantCommandResult {
        val ledger = request.ledgerId.value
        val requestId = request.requestId.value
        val stored =
            database.ledgerQueries
                .selectCatalogItemCommandRequest(ledger, requestId) { _, snapshot, _, _, _, _, _, _ -> snapshot }
                .executeAsOneOrNull() ?: abortTagMerchant(TagMerchantFailureCode.TAG_MERCHANT_CONSTRAINT_VIOLATION)
        if (stored != request.requestSnapshot) {
            return TagMerchantCommandResult.Conflict(TagMerchantFailureCode.REQUEST_IDENTITY_CONFLICT)
        }
        val receipt =
            database.ledgerQueries
                .selectCatalogItemCommandReceipt(ledger, requestId) { outcome, ownerKind, ownerId, newRevision ->
                    TagMerchantCommandReceipt(
                        requestId = request.requestId,
                        outcome = if (outcome == "ACCEPTED") TagMerchantReceiptOutcome.ACCEPTED else TagMerchantReceiptOutcome.NO_CHANGE,
                        kind = kindFromToken(ownerKind),
                        itemId = ownerId,
                        newRevision = newRevision,
                    )
                }.executeAsOneOrNull() ?: abortTagMerchant(TagMerchantFailureCode.TAG_MERCHANT_CONSTRAINT_VIOLATION)
        return TagMerchantCommandResult.NoChange(receipt)
    }

    private fun applyWrite(
        ledger: String,
        write: CatalogItemWrite,
        newRevision: Long,
    ) {
        when (write) {
            is CatalogItemWrite.Insert -> {
                // The inserted row's revision equals the receipt's newRevision: the item revision
                // counter and the per-ledger catalog_item_version advance together (spec 2.2).
                if (write.kind == CatalogItemKind.TAG) {
                    database.ledgerQueries.insertCatalogTag(ledger, write.id, 1L, 0L, newRevision)
                } else {
                    database.ledgerQueries.insertCatalogMerchant(ledger, write.id, 1L, 0L, newRevision)
                }
                appendNameVersion(ledger, write.kind, write.id, write.name)
            }

            is CatalogItemWrite.SetActiveState -> {
                if (write.kind == CatalogItemKind.TAG) {
                    database.ledgerQueries.setCatalogTagState(write.active.toBit(), 0L, ledger, write.id)
                } else {
                    database.ledgerQueries.setCatalogMerchantState(write.active.toBit(), 0L, ledger, write.id)
                }
            }

            is CatalogItemWrite.Rename -> {
                if (write.kind == CatalogItemKind.TAG) {
                    database.ledgerQueries.renameCatalogTag(ledger, write.id)
                } else {
                    database.ledgerQueries.renameCatalogMerchant(ledger, write.id)
                }
                supersedeAndAppendName(ledger, write.kind, write.id, write.name)
            }

            is CatalogItemWrite.Tombstone -> {
                if (write.kind == CatalogItemKind.TAG) {
                    database.ledgerQueries.setCatalogTagState(0L, 1L, ledger, write.id)
                } else {
                    database.ledgerQueries.setCatalogMerchantState(0L, 1L, ledger, write.id)
                }
            }
        }
    }

    private fun appendNameVersion(
        ledger: String,
        kind: CatalogItemKind,
        ownerId: String,
        name: String,
    ) {
        val token = kindToken(kind)
        val next =
            database.ledgerQueries
                .selectCatalogItemMaxNameVersion(ledger, token, ownerId) { max -> max ?: 0L }
                .executeAsOne()
        database.ledgerQueries.insertCatalogItemNameHistory(ledger, token, ownerId, next + 1L, name, "CURRENT")
    }

    private fun supersedeAndAppendName(
        ledger: String,
        kind: CatalogItemKind,
        ownerId: String,
        name: String,
    ) {
        database.ledgerQueries.supersedeCatalogItemCurrentName(ledger, kindToken(kind), ownerId)
        appendNameVersion(ledger, kind, ownerId, name)
    }

    private fun loadAuthority(ledgerId: LedgerId): TagMerchantAuthority? {
        val ledger = ledgerId.value
        val version = database.ledgerQueries.selectCatalogItemVersion(ledger).executeAsOneOrNull() ?: return null
        val tagNames = currentNames(ledger, "tag")
        val merchantNames = currentNames(ledger, "merchant")
        val tags =
            database.ledgerQueries
                .selectCatalogTags(ledger) { tagId, active, tombstoned, revision ->
                    CatalogItem(CatalogItemKind.TAG, tagId, ledgerId, tagNames[tagId] ?: "", active == 1L, tombstoned == 1L, revision)
                }.executeAsList()
        val merchants =
            database.ledgerQueries
                .selectCatalogMerchants(ledger) { merchantId, active, tombstoned, revision ->
                    CatalogItem(CatalogItemKind.MERCHANT, merchantId, ledgerId, merchantNames[merchantId] ?: "", active == 1L, tombstoned == 1L, revision)
                }.executeAsList()
        return TagMerchantAuthority(ledgerId, tags, merchants, version)
    }

    private fun currentNames(
        ledger: String,
        kindToken: String,
    ): Map<String, String> =
        database.ledgerQueries
            .selectCatalogItemCurrentNamesByKind(ledger, kindToken) { ownerId, name -> ownerId to name }
            .executeAsList()
            .toMap()

    private fun targetItem(
        authority: TagMerchantAuthority,
        payload: CatalogItemCommandPayload,
    ): CatalogItem? {
        val id = targetIdOrNull(payload) ?: return null
        val items = if (payload.kind == CatalogItemKind.TAG) authority.tags else authority.merchants
        return items.firstOrNull { it.id == id }
    }

    companion object {
        /**
         * Android/foreign-key-configured handle variant (mirrors the other product stores): the
         * platform driver already configured foreign keys and its busy timeout.
         */
        internal fun forPlatformConfiguredDatabase(
            database: LedgerDatabase,
        ): SqlDelightTagMerchantCatalogStore = SqlDelightTagMerchantCatalogStore(database)
    }
}

private fun kindToken(kind: CatalogItemKind): String = if (kind == CatalogItemKind.TAG) "tag" else "merchant"

private fun kindFromToken(token: String): CatalogItemKind = if (token == "tag") CatalogItemKind.TAG else CatalogItemKind.MERCHANT

private fun targetIdOrNull(payload: CatalogItemCommandPayload): String? =
    when (payload) {
        is CatalogItemCommandPayload.CreateItem -> null
        is CatalogItemCommandPayload.RenameItem -> payload.id
        is CatalogItemCommandPayload.SetItemActive -> payload.id
        is CatalogItemCommandPayload.DeleteItem -> payload.id
    }

private fun Boolean.toBit(): Long = if (this) 1L else 0L

private class TagMerchantTypedRollback(
    val result: TagMerchantCommandResult,
) : RuntimeException()

private fun abortTagMerchant(
    code: TagMerchantFailureCode,
    conflict: Boolean = false,
): Nothing =
    throw TagMerchantTypedRollback(
        if (conflict) {
            TagMerchantCommandResult.Conflict(code)
        } else {
            TagMerchantCommandResult.Rejected(code)
        },
    )
