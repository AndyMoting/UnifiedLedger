package com.unifiedledger.application

import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.P705FailureCode
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.isP705CorrectionSupportedKind

/**
 * P7-05 restore revalidation against the current authoritative catalog (spec section 3.3,
 * DP-9 as clarified by D-156).
 *
 * A restore never blindly re-applies a stale state and never silently substitutes a
 * reference: it re-reads the catalog and either admits the transaction's current version
 * references as they are, or rejects with a typed code while the transaction stays voided.
 * Only an inadmissible reference (missing, cross-ledger, inactive, wrong kind/leaf, or no
 * longer an owned real asset) is a rejection reason — a rename changes display names only and
 * therefore stays admissible (`ACCOUNTING_RULES.md`: stable ids do not change on rename).
 *
 * Returns `null` when the references are admissible; otherwise the frozen rejection code.
 */
fun revalidateOrdinaryVersionReferences(
    catalog: LedgerCatalog,
    ledgerId: LedgerId,
    kind: TransactionKind,
    postings: List<Posting>,
): P705FailureCode? {
    if (!isP705CorrectionSupportedKind(kind)) return P705FailureCode.P705_KIND_NOT_SUPPORTED
    for (posting in postings) {
        val account =
            catalog.accounts.firstOrNull { it.id == posting.accountId }
                ?: return P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE
        if (account.ledgerId != ledgerId) return P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE
        if (!account.active) return P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE
        if (account.currency != posting.amount.currency) return P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE
    }

    val postingAccountIds = postings.map { it.accountId }.toSet()
    val category =
        catalog.categories.firstOrNull { it.ledgerId == ledgerId && it.postingAccountId in postingAccountIds }
            ?: return P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE
    val expectedCategoryKind =
        if (kind == TransactionKind.EXPENSE) CategoryKind.EXPENSE else CategoryKind.INCOME
    if (category.kind != expectedCategoryKind) return P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE
    if (category.parentId == null) return P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE
    if (!category.active) return P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE

    val categoryAccountId = category.postingAccountId
    for (posting in postings) {
        if (posting.accountId == categoryAccountId) continue
        val account = checkNotNull(catalog.accounts.firstOrNull { it.id == posting.accountId })
        val admissibleRealLeg =
            account.kind == AccountKind.ASSET && account.ownedByUser && account.realAccount
        if (!admissibleRealLeg) return P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE
    }
    return null
}
