package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.isManageableAccount

/**
 * P7-01 catalog read models (spec section 6.2). Projections are pure functions over the current
 * [CatalogAuthority] so options, writes, reads and summaries share one authoritative version.
 * Hidden/system accounts never enter the manageable projection (A-2).
 */
fun interface CatalogAuthorityReader {
    fun load(ledgerId: LedgerId): CatalogAuthority?
}

data class ManageableAccountView(
    val accountId: AccountId,
    val name: String,
    val kind: AccountKind,
    val currency: CurrencyUnit,
    val active: Boolean,
    val balanceMinorUnits: Long?,
)

data class CategoryTreeView(
    val categoryId: CategoryId,
    val parentId: CategoryId?,
    val name: String,
    val kind: CategoryKind,
    val active: Boolean,
    val postingAccountId: AccountId?,
)

data class CatalogSnapshotView(
    val catalogVersion: Long,
    val manageableAccounts: List<ManageableAccountView>,
    val categories: List<CategoryTreeView>,
)

/** A-2 manageable accounts, display names replacing bare ids. */
fun projectManageableAccounts(
    authority: CatalogAuthority,
    balanceMinorUnits: (AccountId) -> Long? = { null },
): List<ManageableAccountView> {
    val ledgerId = authority.ledgerId
    return authority.catalog.accounts
        .filter { isManageableAccount(it, ledgerId) }
        .map { it.toManageableView(balanceMinorUnits(it.id)) }
        .sortedWith(compareBy({ it.name }, { it.accountId.value }))
}

/** Full catalog snapshot: manageable accounts plus the complete category tree. */
fun projectCatalogSnapshot(
    authority: CatalogAuthority,
    balanceMinorUnits: (AccountId) -> Long? = { null },
): CatalogSnapshotView =
    CatalogSnapshotView(
        catalogVersion = authority.catalogVersion,
        manageableAccounts = projectManageableAccounts(authority, balanceMinorUnits),
        categories =
            authority.catalog.categories
                .map { it.toTreeView() }
                .sortedWith(compareBy({ it.parentId?.value ?: "" }, { it.name })),
    )

class QueryCatalogSnapshot(
    private val reader: CatalogAuthorityReader,
) {
    fun query(
        ledgerId: LedgerId,
        balanceMinorUnits: (AccountId) -> Long? = { null },
    ): CatalogSnapshotView? = reader.load(ledgerId)?.let { projectCatalogSnapshot(it, balanceMinorUnits) }
}

class QueryManageableAccounts(
    private val reader: CatalogAuthorityReader,
) {
    fun query(
        ledgerId: LedgerId,
        balanceMinorUnits: (AccountId) -> Long? = { null },
    ): List<ManageableAccountView>? = reader.load(ledgerId)?.let { projectManageableAccounts(it, balanceMinorUnits) }
}

/**
 * Store-backed manual-expense options provider (spec section 6.2): every call reloads the
 * current authoritative catalog, so options, writes, reads and summaries share the same
 * catalog version and a successful management command is visible without a restart.
 */
class QueryAuthoritativeManualExpenseOptions(
    private val reader: CatalogAuthorityReader,
    private val ledgerId: LedgerId,
) : ManualExpenseOptionsProvider {
    override fun queryOptions(): ManualExpenseOptions {
        val catalog = reader.load(ledgerId)?.catalog ?: return ManualExpenseOptions(emptyList(), emptyList())
        return QueryManualExpenseOptions(ledgerId, catalog).queryOptions()
    }
}

/**
 * Store-backed manual-income options provider (P7-02.A S-1): every call reloads the current
 * authoritative catalog, so income options, writes, reads and summaries share the same catalog
 * version and a successful management command is visible without a restart.
 */
class QueryAuthoritativeManualIncomeOptions(
    private val reader: CatalogAuthorityReader,
    private val ledgerId: LedgerId,
) : ManualIncomeOptionsProvider {
    override fun queryOptions(): ManualIncomeOptions {
        val catalog = reader.load(ledgerId)?.catalog ?: return ManualIncomeOptions(emptyList(), emptyList())
        return QueryManualIncomeOptions(ledgerId, catalog).queryOptions()
    }
}

private fun Account.toManageableView(balanceMinorUnits: Long?): ManageableAccountView =
    ManageableAccountView(
        accountId = id,
        name = name,
        kind = kind,
        currency = currency,
        active = active,
        balanceMinorUnits = balanceMinorUnits,
    )

private fun Category.toTreeView(): CategoryTreeView =
    CategoryTreeView(
        categoryId = id,
        parentId = parentId,
        name = name,
        kind = kind,
        active = active,
        postingAccountId = postingAccountId,
    )
