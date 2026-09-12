package com.unifiedledger.data

import com.unifiedledger.application.EntryPinResult
import com.unifiedledger.application.EntryPinTarget
import com.unifiedledger.application.EntryPreferenceStore
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.EntryFoundationViolation
import com.unifiedledger.domain.LedgerId
import kotlin.time.Instant

/**
 * P7-02 E-4 (section 4.2 item 7): SqlDelight persistence for the manual account/category entry
 * pins (`entry_pin`, v29). A toggle validates the target against the authoritative product
 * catalog inside one transaction — a missing or cross-ledger target is a typed
 * `EntryPinTargetNotFound` rejection with zero writes — then flips the pin row. Pins are an
 * ordering preference only: no accounting fact and no catalog `active` flag is ever touched.
 */
class SqlDelightEntryPreferenceStore(
    private val database: LedgerDatabase,
) : EntryPreferenceStore {
    override fun pinnedTargets(ledgerId: LedgerId): Set<EntryPinTarget> =
        database.ledgerQueries
            .selectEntryPins(ledgerId.value)
            .executeAsList()
            .mapNotNull { row ->
                when (row.target_kind) {
                    KIND_ACCOUNT -> EntryPinTarget.AccountTarget(ledgerId, AccountId(row.target_id))
                    KIND_CATEGORY -> EntryPinTarget.CategoryTarget(ledgerId, CategoryId(row.target_id))
                    // The table CHECK forbids any other kind; defensive skip keeps a corrupt row
                    // from crashing the read path.
                    else -> null
                }
            }.toSet()

    override fun togglePin(
        target: EntryPinTarget,
        pinnedAt: Instant,
    ): EntryPinResult =
        database.transactionWithResult {
            val targetKind: String
            val targetId: String
            when (target) {
                is EntryPinTarget.AccountTarget -> {
                    targetKind = KIND_ACCOUNT
                    targetId = target.accountId.value
                }
                is EntryPinTarget.CategoryTarget -> {
                    targetKind = KIND_CATEGORY
                    targetId = target.categoryId.value
                }
            }
            // Authoritative existence check in the same transaction: unknown and cross-ledger
            // targets are the same zero-write typed rejection.
            val exists =
                when (target) {
                    is EntryPinTarget.AccountTarget ->
                        database.ledgerQueries
                            .selectCatalogAccount(target.ledgerId.value, targetId)
                            .executeAsOneOrNull() != null
                    is EntryPinTarget.CategoryTarget ->
                        database.ledgerQueries
                            .selectCatalogCategory(target.ledgerId.value, targetId)
                            .executeAsOneOrNull() != null
                }
            if (!exists) {
                return@transactionWithResult EntryPinResult.Rejected(EntryFoundationViolation.EntryPinTargetNotFound)
            }
            val removed = database.ledgerQueries.deleteEntryPin(target.ledgerId.value, targetKind, targetId).value
            if (removed > 0L) {
                EntryPinResult.Toggled(target, pinned = false)
            } else {
                database.ledgerQueries.insertEntryPin(target.ledgerId.value, targetKind, targetId, pinnedAt.toString())
                EntryPinResult.Toggled(target, pinned = true)
            }
        }

    private companion object {
        const val KIND_ACCOUNT: String = "account"

        const val KIND_CATEGORY: String = "category"
    }
}
