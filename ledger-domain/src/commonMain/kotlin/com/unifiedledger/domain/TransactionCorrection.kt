package com.unifiedledger.domain

/**
 * P7-05 product correction pure validation (spec section 3.2). One correction request
 * carries the complete target state of the new version: the note, the statistics time and
 * the ordinary five-field amount/category/funding-account triple. The functions here derive
 * the replacement posting set and the affected funding legs from the *current* authoritative
 * catalog without touching any history.
 *
 * The reusable factories ([createAssetPaidOrdinaryExpense], [createAssetReceivedOrdinaryIncome])
 * stay authoritative for the posting shape; this file reuses their admission rules verbatim so
 * a correction admits exactly what a creation would have admitted at the same catalog state.
 *
 * The rejection vocabulary is the frozen [P705FailureCode] family rather than a new
 * [DomainViolation] subtype: the product correction surface reports stable codes, and keeping
 * the new vocabulary out of the shared sealed violation hierarchy leaves every existing
 * exhaustive `when` over [DomainViolation] (including the `rgXX_` replay stores) untouched.
 */
sealed interface OrdinaryCorrectionPlan {
    /** The validated replacement posting set, in the frozen leg order of the creation factories. */
    data class Postings(
        val postings: List<Posting>,
    ) : OrdinaryCorrectionPlan

    data class Rejected(
        val code: P705FailureCode,
    ) : OrdinaryCorrectionPlan
}

/** The five frozen correction fields, of which note/statistics time are version fields. */
data class OrdinaryCorrectionCommand(
    val ledgerId: LedgerId,
    val kind: TransactionKind,
    val amount: Money,
    val categoryId: CategoryId,
    val fundingAccountId: AccountId,
)

/** Fresh posting ids for the replacement posting set (category leg first, funding leg second). */
data class OrdinaryCorrectionPostingIds(
    val categoryPostingId: PostingId,
    val fundingPostingId: PostingId,
)

/**
 * The P7-05 correction support matrix: the first-slice `EXPENSE`/`INCOME` rows (spec section
 * 3.1) plus the transfer slice's `ACCOUNT_TRANSFER` row (transfer spec section 2.1; D-228 J1).
 * This is the single shared definition - both commit ports and the restore revalidation call
 * it, and no port writes its own copy - so opening the transfer row here also opens the
 * transfer void (the void plan carries no kind-specific validation of its own).
 *
 * Restore stays ordinary in the transfer wiring batch (intermediate state, registered): a
 * transfer restore still runs the `OrdinaryVersionRevalidation` EXPENSE/INCOME hardcode, so it
 * fails closed on the category lookup rather than restoring; the transfer-aware replacement,
 * the DP-10 placeholder narrowing and the D-113 combination are slice 3 and untouched here.
 */
fun isP705CorrectionSupportedKind(kind: TransactionKind): Boolean =
    kind == TransactionKind.EXPENSE ||
        kind == TransactionKind.INCOME ||
        kind == TransactionKind.ACCOUNT_TRANSFER

/**
 * Derives and validates the replacement posting set of one correction against the current
 * authoritative catalog. The returned list is exactly the posting list the new version binds,
 * in the same leg order the creation factories use (category leg first for an expense, funding
 * leg first for an income), and it is per-currency balanced by construction.
 */
fun planOrdinaryCorrectionPostings(
    catalog: LedgerCatalog,
    command: OrdinaryCorrectionCommand,
    ids: OrdinaryCorrectionPostingIds,
): OrdinaryCorrectionPlan {
    // The ordinary planner plans only the ordinary pair. The shared matrix gate above admits
    // the transfer slice's ACCOUNT_TRANSFER row too, whose planner is
    // planTransferCorrectionPostings (TransferCorrectionValidation.kt); reaching this one with
    // a transfer kind must stay a typed rejection, never a silently mis-planned INCOME shape.
    if (command.kind != TransactionKind.EXPENSE && command.kind != TransactionKind.INCOME) {
        return OrdinaryCorrectionPlan.Rejected(P705FailureCode.P705_KIND_NOT_SUPPORTED)
    }
    if (command.amount.minorUnits <= 0L) {
        // Batch-ruled reading (pending the spec revision): the frozen failure-code table has no
        // amount-specific code, and the request type structurally cannot carry `occurredAt` or a
        // kind change, so an inadmissible amount value is reported through the field code rather
        // than a new token.
        return OrdinaryCorrectionPlan.Rejected(P705FailureCode.P705_FIELD_NOT_SUPPORTED)
    }

    val category =
        catalog.category(command.categoryId)
            ?: return OrdinaryCorrectionPlan.Rejected(P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE)
    val expectedCategoryKind =
        if (command.kind == TransactionKind.EXPENSE) CategoryKind.EXPENSE else CategoryKind.INCOME
    if (category.ledgerId != command.ledgerId || category.kind != expectedCategoryKind) {
        return OrdinaryCorrectionPlan.Rejected(P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE)
    }
    val parentCategoryId =
        category.parentId
            ?: return OrdinaryCorrectionPlan.Rejected(P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE)
    val parentCategory =
        catalog.category(parentCategoryId)
            ?: return OrdinaryCorrectionPlan.Rejected(P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE)
    if (
        parentCategory.ledgerId != command.ledgerId ||
        parentCategory.parentId != null ||
        parentCategory.kind != expectedCategoryKind
    ) {
        return OrdinaryCorrectionPlan.Rejected(P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE)
    }
    if (!category.active) {
        return OrdinaryCorrectionPlan.Rejected(P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE)
    }

    val categoryAccount =
        category.postingAccountId?.let(catalog::account)
            ?: return OrdinaryCorrectionPlan.Rejected(P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE)
    val fundingAccount =
        catalog.account(command.fundingAccountId)
            ?: return OrdinaryCorrectionPlan.Rejected(P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE)

    val categoryAccountKind =
        if (command.kind == TransactionKind.EXPENSE) AccountKind.EXPENSE else AccountKind.INCOME
    val validCategoryAccount =
        categoryAccount.ledgerId == command.ledgerId &&
            categoryAccount.kind == categoryAccountKind &&
            categoryAccount.currency == command.amount.currency &&
            !categoryAccount.realAccount
    if (!validCategoryAccount) {
        return OrdinaryCorrectionPlan.Rejected(P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE)
    }
    val validFundingAccount =
        fundingAccount.ledgerId == command.ledgerId &&
            fundingAccount.kind == AccountKind.ASSET &&
            fundingAccount.currency == command.amount.currency &&
            fundingAccount.ownedByUser &&
            fundingAccount.realAccount
    if (!validFundingAccount) {
        return OrdinaryCorrectionPlan.Rejected(P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE)
    }

    val negated =
        checkedNegate(command.amount.minorUnits)
            // Same batch-ruled field code as the non-positive case above (an amount that cannot
            // be represented exactly is an inadmissible value, not a new rejection token).
            ?: return OrdinaryCorrectionPlan.Rejected(P705FailureCode.P705_FIELD_NOT_SUPPORTED)
    val negative = Money.ofMinor(negated, command.amount.currency)
    val postings =
        if (command.kind == TransactionKind.EXPENSE) {
            listOf(
                Posting(id = ids.categoryPostingId, accountId = categoryAccount.id, amount = command.amount),
                Posting(id = ids.fundingPostingId, accountId = fundingAccount.id, amount = negative),
            )
        } else {
            listOf(
                Posting(id = ids.fundingPostingId, accountId = fundingAccount.id, amount = command.amount),
                Posting(id = ids.categoryPostingId, accountId = categoryAccount.id, amount = negative),
            )
        }
    return OrdinaryCorrectionPlan.Postings(postings)
}

/**
 * The affected funding legs of a correction (spec section 3.2, DP-10 narrowing): a leg of the
 * new version whose account is a real account (`catalog_account.real_account = 1`) and whose
 * `(accountId, amount, currency)` tuple does not exist in the old version. Unchanged funding
 * legs are returned in neither list; non-funding legs (expense/income posting accounts) never
 * participate.
 *
 * First-slice transactions have no reconciliation or evidence rows at all, so this derivation
 * has no invalidation target and the D-113 correction port is deliberately not composed; the
 * function exists as the frozen definition of the affected-leg set and is covered by tests.
 */
fun affectedFundingLegs(
    oldPostings: List<Posting>,
    newPostings: List<Posting>,
    realAccountIds: Set<AccountId>,
): List<Posting> {
    val oldFundingLegs = oldPostings.filter { it.accountId in realAccountIds }
    val remaining = oldFundingLegs.toMutableList()
    val affected = mutableListOf<Posting>()
    for (posting in newPostings) {
        if (posting.accountId !in realAccountIds) continue
        val unchanged =
            remaining.indexOfFirst {
                it.accountId == posting.accountId &&
                    it.amount.minorUnits == posting.amount.minorUnits &&
                    it.amount.currency == posting.amount.currency
            }
        if (unchanged >= 0) {
            remaining.removeAt(unchanged)
        } else {
            affected += posting
        }
    }
    return affected
}
