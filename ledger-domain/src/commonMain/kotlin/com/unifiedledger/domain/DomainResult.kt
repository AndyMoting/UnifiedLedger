package com.unifiedledger.domain

sealed interface DomainResult<out T> {
    data class Success<out T>(val value: T) : DomainResult<T>

    data class Failure(val violation: DomainViolation) : DomainResult<Nothing>
}

sealed interface DomainViolation {
    data object UnbalancedPostingSet : DomainViolation

    data object InvalidPostingSet : DomainViolation

    data object ArithmeticOverflow : DomainViolation

    data object InvalidCatalog : DomainViolation

    data object InvalidFormalTransaction : DomainViolation

    data object InvalidOrdinaryExpense : DomainViolation

    data object InvalidOrdinaryIncome : DomainViolation

    data object InvalidBalanceReplay : DomainViolation

    data object InvalidMixedPayment : DomainViolation

    data object InvalidMergedPayment : DomainViolation

    data object InvalidRefundReceipt : DomainViolation
}

sealed interface MixedPaymentViolation : DomainViolation {
    data object AmountMustBePositive : MixedPaymentViolation
    data object FundingLegMustBePositive : MixedPaymentViolation
    data object DuplicateFundingAccount : MixedPaymentViolation
    data object FundingTotalMustEqualExpense : MixedPaymentViolation
    data object UnknownRealAccount : MixedPaymentViolation
    data object RealFinancialAccountRequired : MixedPaymentViolation
    data object OwnedAccountRequired : MixedPaymentViolation
    data object SecondaryCategoryRequired : MixedPaymentViolation
    data object CategoryInactive : MixedPaymentViolation
    data object ExpenseCategoryRequired : MixedPaymentViolation
    data object SingleCurrencyRequired : MixedPaymentViolation
    data object AssetAndCreditLiabilityRequired : MixedPaymentViolation
}

sealed interface MergedPaymentViolation : DomainViolation {
    data object AmountMustBePositive : MergedPaymentViolation
    data object ItemAmountMustBePositive : MergedPaymentViolation
    data object AllocationTotalMustEqualPayment : MergedPaymentViolation
    data object DuplicateItemId : MergedPaymentViolation
    data object UnknownRealAccount : MergedPaymentViolation
    data object RealFinancialAccountRequired : MergedPaymentViolation
    data object AssetAccountRequired : MergedPaymentViolation
    data object OwnedAccountRequired : MergedPaymentViolation
    data object SecondaryCategoryRequired : MergedPaymentViolation
    data object CategoryInactive : MergedPaymentViolation
    data object ExpenseCategoryRequired : MergedPaymentViolation
    data object SingleCurrencyRequired : MergedPaymentViolation
}

sealed interface OrdinaryExpenseViolation : DomainViolation {
    data object AmountMustBePositive : OrdinaryExpenseViolation

    data object SecondaryCategoryRequired : OrdinaryExpenseViolation

    data object CategoryInactive : OrdinaryExpenseViolation
}

sealed interface OrdinaryIncomeViolation : DomainViolation {
    data object AmountMustBePositive : OrdinaryIncomeViolation

    data object SecondaryCategoryRequired : OrdinaryIncomeViolation

    data object CategoryInactive : OrdinaryIncomeViolation

    data object IncomeCategoryRequired : OrdinaryIncomeViolation
}

enum class AccountTransferField {
    SOURCE_ACCOUNT,
    DESTINATION_ACCOUNT,
    SOURCE_DEBIT,
    DESTINATION_CREDIT,
    FEE,
}

sealed interface AccountTransferViolation : DomainViolation {
    data class KnownAccountRequired(val field: AccountTransferField) : AccountTransferViolation

    data object DistinctAccountsRequired : AccountTransferViolation

    data class OwnAccountRequired(val field: AccountTransferField) : AccountTransferViolation

    data class RealFinancialAccountRequired(val field: AccountTransferField) : AccountTransferViolation

    data class AssetAccountRequired(val field: AccountTransferField) : AccountTransferViolation

    data class AmountMustBePositive(val field: AccountTransferField) : AccountTransferViolation

    data object FeeMustNotBeNegative : AccountTransferViolation

    data object AmountsMustBalance : AccountTransferViolation

    data object SameCurrencyRequired : AccountTransferViolation

    data object InvalidFeeCategory : AccountTransferViolation
}

enum class BalanceAdjustmentField {
    TARGET_ACCOUNT,
    ADJUSTMENT_EQUITY_ACCOUNT,
    AMOUNT,
}

sealed interface BalanceAdjustmentViolation : DomainViolation {
    data class KnownAccountRequired(val field: BalanceAdjustmentField) : BalanceAdjustmentViolation

    data class OwnedRealAssetRequired(val field: BalanceAdjustmentField) : BalanceAdjustmentViolation

    data object DedicatedAdjustmentEquityRequired : BalanceAdjustmentViolation

    data object NonZeroAmountRequired : BalanceAdjustmentViolation

    data object SameCurrencyRequired : BalanceAdjustmentViolation

    data object SupportedKindRequired : BalanceAdjustmentViolation
}

enum class PrincipalTransferField {
    SOURCE_ACCOUNT,
    DESTINATION_ACCOUNT,
    AMOUNT,
}

sealed interface PrincipalTransferViolation : DomainViolation {
    data class KnownAccountRequired(val field: PrincipalTransferField) : PrincipalTransferViolation

    data class OwnedRealAssetRequired(val field: PrincipalTransferField) : PrincipalTransferViolation

    data object DistinctAccountsRequired : PrincipalTransferViolation

    data object AmountMustBePositive : PrincipalTransferViolation

    data object SameCurrencyRequired : PrincipalTransferViolation
}
