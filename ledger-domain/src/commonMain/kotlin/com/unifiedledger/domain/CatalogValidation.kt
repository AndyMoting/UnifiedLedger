package com.unifiedledger.domain

/**
 * P7-01 product catalog load validation (spec section 4.4, N-3).
 *
 * This is deliberately *not* called from [LedgerCatalog.create] and never applied to frozen
 * golden catalog states. It only validates the three catalog-tree shape rules for product
 * catalogs loaded from the `catalog_*` tables:
 *
 *  - a level-1 category (no parent) must not carry a posting account;
 *  - an active leaf must carry a posting account that is a same-ledger hidden account
 *    (`systemRole == null && !ownedByUser && !realAccount`) of the matching kind;
 *  - an inactive/historical leaf may omit its posting account (frozen golden compatibility).
 *
 * Depth and parent-existence checks are intentionally out of scope here (spec section 4.4):
 * the frozen `rg-10` runtime catalog contains a level-2 category whose parent is absent, and
 * management commands enforce those rules with `CategoryLevelNotSupported` /
 * `CategoryParentRequired` instead.
 */
fun validateProductCatalog(catalog: LedgerCatalog): DomainResult<Unit> {
    val accountsById = catalog.accounts.associateBy { it.id }
    for (category in catalog.categories) {
        if (category.parentId == null) {
            if (category.postingAccountId != null) {
                return DomainResult.Failure(CatalogViolation.CategoryPostingAccountInvalid)
            }
            continue
        }
        val postingAccountId = category.postingAccountId
        if (postingAccountId == null) {
            if (category.active) {
                return DomainResult.Failure(CatalogViolation.CategoryPostingAccountInvalid)
            }
            continue
        }
        val account =
            accountsById[postingAccountId]
                ?: return DomainResult.Failure(CatalogViolation.CategoryPostingAccountInvalid)
        val expectedKind =
            when (category.kind) {
                CategoryKind.EXPENSE -> AccountKind.EXPENSE
                CategoryKind.INCOME -> AccountKind.INCOME
            }
        if (
            account.ledgerId != category.ledgerId ||
            account.kind != expectedKind ||
            account.ownedByUser ||
            account.realAccount ||
            account.systemRole != null
        ) {
            return DomainResult.Failure(CatalogViolation.CategoryPostingAccountInvalid)
        }
    }
    return DomainResult.Success(Unit)
}
