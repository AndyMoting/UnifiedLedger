package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.Money
import kotlin.time.Instant

/** Result of an exact minor-unit scale conversion. No rounding or truncation is allowed. */
internal sealed interface ExactAmountNormalization {
    data class Success(
        val amountMinor: Long,
    ) : ExactAmountNormalization

    data object NotRepresentable : ExactAmountNormalization

    data object ArithmeticOverflow : ExactAmountNormalization
}

/**
 * Converts source minor units to a target precision using integer arithmetic only.
 *
 * A source precision can be larger than the domain CurrencyUnit range because it is
 * retained as source evidence. In that case zero remains exactly representable while
 * any non-zero value requiring more than 18 decimal places is rejected.
 */
internal fun normalizeSourceMinorExact(
    amountMinor: Long,
    sourceScale: Int,
    targetPrecision: Int,
): ExactAmountNormalization {
    if (sourceScale < 0 || targetPrecision < 0) return ExactAmountNormalization.NotRepresentable

    if (targetPrecision >= sourceScale) {
        val factor =
            powerOfTenOrNull(targetPrecision - sourceScale)
                ?: return ExactAmountNormalization.ArithmeticOverflow
        return multiplyExactOrNull(amountMinor, factor)?.let(ExactAmountNormalization::Success)
            ?: ExactAmountNormalization.ArithmeticOverflow
    }

    val difference = sourceScale - targetPrecision
    // A non-zero Long cannot contain 10^19 as a factor. Avoid constructing a
    // power for arbitrarily high source precision; zero is exact at every scale.
    if (amountMinor == 0L) return ExactAmountNormalization.Success(0L)
    if (difference > 18) return ExactAmountNormalization.NotRepresentable

    val divisor =
        powerOfTenOrNull(difference)
            ?: return ExactAmountNormalization.ArithmeticOverflow
    if (amountMinor % divisor != 0L) return ExactAmountNormalization.NotRepresentable
    return ExactAmountNormalization.Success(amountMinor / divisor)
}

/** Normalizes one resolved source fact into an explicitly selected account currency. */
internal fun normalizeImportAmountExact(
    resolved: ImportResolvedSourceFacts,
    targetCurrency: CurrencyUnit,
): DomainResult<Money> {
    if (resolved.currencyCode != targetCurrency.code) {
        return DomainResult.Failure(
            DomainViolation.AmountNotRepresentableInCurrency(
                amountMinor = resolved.amountMinor,
                sourceScale = resolved.currencyPrecision,
                targetCurrencyCode = targetCurrency.code,
                targetPrecision = targetCurrency.precision,
            ),
        )
    }
    return when (
        val result =
            normalizeSourceMinorExact(
                amountMinor = resolved.amountMinor,
                sourceScale = resolved.currencyPrecision,
                targetPrecision = targetCurrency.precision,
            )
    ) {
        is ExactAmountNormalization.Success ->
            DomainResult.Success(Money.ofMinor(result.amountMinor, targetCurrency))
        ExactAmountNormalization.NotRepresentable ->
            DomainResult.Failure(
                DomainViolation.AmountNotRepresentableInCurrency(
                    amountMinor = resolved.amountMinor,
                    sourceScale = resolved.currencyPrecision,
                    targetCurrencyCode = targetCurrency.code,
                    targetPrecision = targetCurrency.precision,
                ),
            )
        ExactAmountNormalization.ArithmeticOverflow ->
            DomainResult.Failure(DomainViolation.ArithmeticOverflow)
    }
}

/** Parses source time inside the typed factory contract instead of leaking an exception. */
internal fun parseImportOccurredAt(value: String): DomainResult<Instant> =
    try {
        DomainResult.Success(Instant.parse(value))
    } catch (_: IllegalArgumentException) {
        DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
    }

private fun powerOfTenOrNull(exponent: Int): Long? {
    if (exponent < 0) return null
    var result = 1L
    repeat(exponent) {
        if (result > Long.MAX_VALUE / 10L) return null
        result *= 10L
    }
    return result
}

private fun multiplyExactOrNull(
    left: Long,
    right: Long,
): Long? {
    if (left == 0L || right == 0L) return 0L
    if (right > 0L) {
        if (left > 0L && left > Long.MAX_VALUE / right) return null
        if (left < 0L && left < Long.MIN_VALUE / right) return null
    } else {
        if (left > 0L && left > Long.MIN_VALUE / right) return null
        if (left < 0L && left < Long.MAX_VALUE / right) return null
    }
    return left * right
}

/**
 * Adds the account-catalog half of the binding contract. The commit port invokes this
 * before any formal INSERT, so an arbitrary factory cannot bypass selected-account or
 * category semantics by returning an internally balanced graph.
 */
fun validateImportFormalBindingAgainstCatalog(
    input: ImportCandidateFormalizationInput,
    allocatedIds: ImportCommitIds,
    created: ImportFormalCommit,
    catalog: LedgerCatalog,
): DomainResult<Unit> {
    when (val generic = validateImportFormalBinding(input, allocatedIds, created)) {
        is DomainResult.Failure -> return generic
        is DomainResult.Success -> Unit
    }
    val fields = input.decisionFields
    val targetCurrencies =
        when (fields) {
            is ImportConfirmDecisionFields.OrdinaryFlow -> listOf(fields.fundingAccountId)
            is ImportConfirmDecisionFields.TransferFlow -> listOf(fields.fromAccountId, fields.toAccountId)
            is ImportConfirmDecisionFields.CreditExpenseFlow -> listOf(fields.creditLiabilityAccountId)
            is ImportConfirmDecisionFields.CreditExpenseRefundFlow -> listOf(fields.creditLiabilityAccountId)
            is ImportConfirmDecisionFields.CreditRepaymentFlow -> listOf(fields.creditLiabilityAccountId, fields.assetAccountId)
            is ImportConfirmDecisionFields.MixedPaymentFlow -> listOf(fields.assetAccountId, fields.creditLiabilityAccountId)
        }.map { accountId ->
            catalog.accounts.firstOrNull { it.id == accountId && it.ledgerId == input.ledgerId }?.currency
                ?: return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
        }
    if (targetCurrencies.distinct().size != 1) return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
    val expectedCurrency = targetCurrencies.distinct().single()
    val postings = created.transaction.currentPostings()
    if (postings.any { posting ->
            val account =
                catalog.accounts.firstOrNull {
                    it.id == posting.accountId && it.ledgerId == input.ledgerId
                }
            account == null || account.currency != posting.amount.currency || account.currency != expectedCurrency
        }
    ) {
        return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
    }

    fun categoryPostingAccount(
        categoryId: com.unifiedledger.domain.CategoryId,
        kind: CategoryKind,
    ): AccountId? {
        val category =
            catalog.categories.firstOrNull { it.id == categoryId && it.ledgerId == input.ledgerId }
                ?: return null
        val parent =
            category.parentId?.let { parentId ->
                catalog.categories.firstOrNull { it.id == parentId && it.ledgerId == input.ledgerId }
            } ?: return null
        val postingAccountId = category.postingAccountId ?: return null
        val postingAccount =
            catalog.accounts.firstOrNull {
                it.id == postingAccountId && it.ledgerId == input.ledgerId
            } ?: return null
        if (!category.active ||
            category.kind != kind ||
            parent.parentId != null ||
            parent.kind != kind ||
            postingAccount.kind !=
            when (kind) {
                CategoryKind.EXPENSE -> AccountKind.EXPENSE
                CategoryKind.INCOME -> AccountKind.INCOME
            } ||
            postingAccount.realAccount
        ) {
            return null
        }
        return postingAccountId
    }

    fun matchesCategory(
        index: Int,
        categoryId: com.unifiedledger.domain.CategoryId,
        kind: CategoryKind,
    ): Boolean {
        val postingAccountId = categoryPostingAccount(categoryId, kind) ?: return false
        return postings.getOrNull(index)?.accountId == postingAccountId
    }

    fun selectedAccountMatches(
        accountId: AccountId,
        kind: AccountKind,
        ownedByUser: Boolean = true,
        realAccount: Boolean = true,
    ): Boolean {
        val account =
            catalog.accounts.firstOrNull {
                it.id == accountId && it.ledgerId == input.ledgerId
            } ?: return false
        return account.kind == kind &&
            (!ownedByUser || account.ownedByUser) &&
            (!realAccount || account.realAccount)
    }

    val categoryBindingValid =
        when (val fields = input.decisionFields) {
            is ImportConfirmDecisionFields.OrdinaryFlow ->
                when (input.resolved.directionToken) {
                    "out" ->
                        selectedAccountMatches(fields.fundingAccountId, AccountKind.ASSET) &&
                            matchesCategory(0, fields.categoryId, CategoryKind.EXPENSE)
                    "in" ->
                        selectedAccountMatches(fields.fundingAccountId, AccountKind.ASSET) &&
                            matchesCategory(1, fields.categoryId, CategoryKind.INCOME)
                    else -> false
                }
            is ImportConfirmDecisionFields.CreditExpenseFlow ->
                selectedAccountMatches(fields.creditLiabilityAccountId, AccountKind.LIABILITY) &&
                    matchesCategory(0, fields.categoryId, CategoryKind.EXPENSE)
            is ImportConfirmDecisionFields.CreditExpenseRefundFlow ->
                selectedAccountMatches(fields.creditLiabilityAccountId, AccountKind.LIABILITY) &&
                    matchesCategory(1, fields.categoryId, CategoryKind.EXPENSE)
            is ImportConfirmDecisionFields.CreditRepaymentFlow ->
                selectedAccountMatches(fields.assetAccountId, AccountKind.ASSET) &&
                    selectedAccountMatches(fields.creditLiabilityAccountId, AccountKind.LIABILITY)
            is ImportConfirmDecisionFields.MixedPaymentFlow ->
                selectedAccountMatches(fields.assetAccountId, AccountKind.ASSET) &&
                    selectedAccountMatches(fields.creditLiabilityAccountId, AccountKind.LIABILITY) &&
                    matchesCategory(0, fields.categoryId, CategoryKind.EXPENSE)
            is ImportConfirmDecisionFields.TransferFlow ->
                selectedAccountMatches(fields.fromAccountId, AccountKind.ASSET) &&
                    selectedAccountMatches(fields.toAccountId, AccountKind.ASSET)
        }
    if (!categoryBindingValid) return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
    return DomainResult.Success(Unit)
}

internal fun checkedImportFormalCommit(
    input: ImportCandidateFormalizationInput,
    allocatedIds: ImportCommitIds,
    created: ImportFormalCommit,
    catalog: LedgerCatalog,
): DomainResult<ImportFormalCommit> =
    when (
        val validation = validateImportFormalBindingAgainstCatalog(input, allocatedIds, created, catalog)
    ) {
        is DomainResult.Success -> DomainResult.Success(created)
        is DomainResult.Failure -> DomainResult.Failure(validation.violation)
    }
