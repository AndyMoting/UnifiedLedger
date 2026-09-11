package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId

/**
 * V-2 (spec sections 3.3/6.2): a commit that consumes the catalog must revalidate its account
 * and category references against the *current* authoritative catalog inside the write
 * transaction. A preview or option snapshot never constitutes commit permission.
 */
sealed interface CatalogAdmissionViolation {
    data object PaymentAccountNotFound : CatalogAdmissionViolation

    data object PaymentAccountInactive : CatalogAdmissionViolation

    data object PaymentAccountNotManageableFinancial : CatalogAdmissionViolation

    data object PaymentAccountWrongKind : CatalogAdmissionViolation

    data object CategoryNotFound : CatalogAdmissionViolation

    data object CategoryInactive : CatalogAdmissionViolation

    data object CategoryNotLeaf : CatalogAdmissionViolation

    data object CategoryKindMismatch : CatalogAdmissionViolation
}

/**
 * Fresh read of the authoritative catalog, used by a write transaction to revalidate its
 * references. The reader must observe the same catalog version it will commit against.
 */
fun interface CatalogAdmissionReader {
    fun loadCurrent(ledgerId: LedgerId): LedgerCatalog?
}

/**
 * Revalidates a manual-expense style commit: the payment account must exist, be an active
 * same-ledger owned real ASSET account; the category must exist, be an active same-ledger leaf
 * EXPENSE category whose posting account is a same-ledger hidden EXPENSE account.
 */
fun validateManualExpenseAdmission(
    catalog: LedgerCatalog,
    ledgerId: LedgerId,
    paymentAccountId: AccountId,
    categoryId: CategoryId,
): CatalogAdmissionViolation? {
    val account =
        catalog.accounts.firstOrNull { it.id == paymentAccountId }
            ?: return CatalogAdmissionViolation.PaymentAccountNotFound
    if (account.ledgerId != ledgerId) return CatalogAdmissionViolation.PaymentAccountNotFound
    if (account.kind != AccountKind.ASSET) return CatalogAdmissionViolation.PaymentAccountWrongKind
    if (!account.ownedByUser || !account.realAccount) {
        return CatalogAdmissionViolation.PaymentAccountNotManageableFinancial
    }
    if (!account.active) return CatalogAdmissionViolation.PaymentAccountInactive

    val category =
        catalog.categories.firstOrNull { it.id == categoryId }
            ?: return CatalogAdmissionViolation.CategoryNotFound
    if (category.ledgerId != ledgerId) return CatalogAdmissionViolation.CategoryNotFound
    if (category.kind != CategoryKind.EXPENSE) return CatalogAdmissionViolation.CategoryKindMismatch
    if (category.parentId == null) return CatalogAdmissionViolation.CategoryNotLeaf
    if (!category.active) return CatalogAdmissionViolation.CategoryInactive
    val postingAccount =
        catalog.accounts.firstOrNull { it.id == category.postingAccountId }
            ?: return CatalogAdmissionViolation.CategoryNotFound
    if (postingAccount.ledgerId != ledgerId || postingAccount.kind != AccountKind.EXPENSE) {
        return CatalogAdmissionViolation.CategoryKindMismatch
    }
    return null
}

/**
 * V-2 wrapper around a raw formal-transaction factory. It re-reads the authoritative catalog
 * through [admissionReader] immediately before delegating, so a stale option snapshot cannot
 * admit an inactive/deleted/mismatched reference. A typed [CatalogAdmissionViolation] is mapped
 * to the domain `CategoryInactive` violation so the existing commit port rejects the whole
 * request with zero formal writes.
 */
class CatalogAdmissionExpenseTransactionFactory(
    private val admissionReader: CatalogAdmissionReader,
    private val delegate: ConfirmedExpenseTransactionFactory,
) : ConfirmedExpenseTransactionFactory {
    override fun create(
        request: ManualExpenseRequestSnapshot,
        ids: ConfirmedManualExpenseCommitIds,
    ): com.unifiedledger.domain.DomainResult<ConfirmedManualExpenseCommit> {
        val catalog =
            admissionReader.loadCurrent(request.ledgerId)
                ?: return com.unifiedledger.domain.DomainResult.Failure(
                    com.unifiedledger.domain.OrdinaryExpenseViolation.CategoryInactive,
                )
        val violation =
            validateManualExpenseAdmission(
                catalog = catalog,
                ledgerId = request.ledgerId,
                paymentAccountId = request.paymentAccountId,
                categoryId = request.categoryId,
            )
        if (violation != null) {
            return com.unifiedledger.domain.DomainResult.Failure(
                com.unifiedledger.domain.OrdinaryExpenseViolation.CategoryInactive,
            )
        }
        return delegate.create(request, ids)
    }
}

/** Small helpers kept public for the composition roots that load the current catalog. */
fun LedgerCatalog.findAccount(id: AccountId): Account? = accounts.firstOrNull { it.id == id }
