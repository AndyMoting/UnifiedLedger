package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.EntryFoundationViolation
import com.unifiedledger.domain.LedgerId
import kotlin.time.Instant

/**
 * P7-02 E-4: one manual pin mark, scoped by ledger, target kind (`account` | `category`) and the
 * stable catalog id. Pins are a pure ordering preference: they never change accounting facts and
 * never re-enable a disabled/inactive object.
 */
sealed interface EntryPinTarget {
    val ledgerId: LedgerId

    data class AccountTarget(
        override val ledgerId: LedgerId,
        val accountId: AccountId,
    ) : EntryPinTarget

    data class CategoryTarget(
        override val ledgerId: LedgerId,
        val categoryId: CategoryId,
    ) : EntryPinTarget
}

/**
 * P7-02 E-4 toggle outcome. Toggling is a set membership flip (pin when unpinned, unpin when
 * pinned); a missing or cross-ledger target is a typed rejection with zero writes (section 5.3,
 * `EntryPinTargetNotFound`).
 */
sealed interface EntryPinResult {
    data class Toggled(
        val target: EntryPinTarget,
        val pinned: Boolean,
    ) : EntryPinResult

    data class Rejected(
        val violation: EntryFoundationViolation,
    ) : EntryPinResult
}

/**
 * P7-02 E-4 (section 4.2 item 7): persistence port for the per-ledger entry pins. Reads return
 * the current pin set; [togglePin] flips one target atomically and stamps the pin instant the
 * caller supplies (the store itself holds no clock).
 */
interface EntryPreferenceStore {
    fun pinnedTargets(ledgerId: LedgerId): Set<EntryPinTarget>

    fun togglePin(
        target: EntryPinTarget,
        pinnedAt: Instant,
    ): EntryPinResult
}

/**
 * P7-02 E-4 (section 4.2 item 7): the single application sort derivation — pinned entries first,
 * remaining entries keep the existing deterministic order (stable partition). Pinning affects
 * ordering only; callers render and select exactly as before.
 */
object EntryPinOrdering {
    fun sortAccounts(
        accounts: List<ManageableAccountView>,
        pinned: Set<EntryPinTarget>,
    ): List<ManageableAccountView> = pinnedFirst(accounts, pinnedAccountIds(pinned)) { it.accountId }

    fun sortCategories(
        categories: List<CategoryTreeView>,
        pinned: Set<EntryPinTarget>,
    ): List<CategoryTreeView> = pinnedFirst(categories, pinnedCategoryIds(pinned)) { it.categoryId }

    fun sortPaymentAccounts(
        accounts: List<PaymentAccountOption>,
        pinned: Set<EntryPinTarget>,
    ): List<PaymentAccountOption> = pinnedFirst(accounts, pinnedAccountIds(pinned)) { it.accountId }

    fun sortExpenseCategories(
        categories: List<ExpenseCategoryOption>,
        pinned: Set<EntryPinTarget>,
    ): List<ExpenseCategoryOption> = pinnedFirst(categories, pinnedCategoryIds(pinned)) { it.categoryId }

    fun sortIncomeCategories(
        categories: List<IncomeCategoryOption>,
        pinned: Set<EntryPinTarget>,
    ): List<IncomeCategoryOption> = pinnedFirst(categories, pinnedCategoryIds(pinned)) { it.categoryId }

    private fun pinnedAccountIds(pinned: Set<EntryPinTarget>): Set<AccountId> = pinned.filterIsInstance<EntryPinTarget.AccountTarget>().mapTo(HashSet()) { it.accountId }

    private fun pinnedCategoryIds(pinned: Set<EntryPinTarget>): Set<CategoryId> = pinned.filterIsInstance<EntryPinTarget.CategoryTarget>().mapTo(HashSet()) { it.categoryId }

    private inline fun <T, K> pinnedFirst(
        items: List<T>,
        pinnedIds: Set<K>,
        id: (T) -> K,
    ): List<T> {
        if (pinnedIds.isEmpty()) return items
        val head = ArrayList<T>(items.size)
        val tail = ArrayList<T>(items.size)
        for (item in items) {
            (if (id(item) in pinnedIds) head else tail).add(item)
        }
        return head + tail
    }
}
