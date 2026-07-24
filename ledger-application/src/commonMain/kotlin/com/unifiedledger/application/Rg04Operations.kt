package com.unifiedledger.application

import com.unifiedledger.domain.*
import kotlin.time.Instant

sealed interface Rg04Field<out T> {
    data object Omitted : Rg04Field<Nothing>
    data object Null : Rg04Field<Nothing>
    data class Value<T>(val value: T) : Rg04Field<T>
}

data class Rg04FundingInput(val accountId: Rg04Field<String>, val amount: Rg04Field<String>, val currency: Rg04Field<String>)
data class Rg04SettlementInput(val originalAmount: String, val discountAmount: String, val settledAmount: String)
data class Rg04ManualInput(
    val requestId: Rg04Field<String>, val occurredAt: Rg04Field<String>, val totalAmount: Rg04Field<String>,
    val currency: Rg04Field<String>, val categoryId: Rg04Field<String>, val funding: List<Rg04FundingInput>,
    val settlement: Rg04SettlementInput? = null, val explicitConfirmation: Rg04Field<Boolean> = Rg04Field.Omitted,
)
data class Rg04RepaymentInput(
    val requestId: Rg04Field<String>, val occurredAt: Rg04Field<String>, val assetAccountId: Rg04Field<String>,
    val liabilityAccountId: Rg04Field<String>, val principalAmount: Rg04Field<String>, val currency: Rg04Field<String>,
    val explicitConfirmation: Rg04Field<Boolean>,
)

enum class Rg04Action { MANUAL_MIXED_EXPENSE, CREDIT_PRINCIPAL_REPAYMENT }
enum class Rg04OperationClass { CREATION, REJECTION, RECONCILIATION }
data class Rg04OperationSource(val locator: String, val discriminator: String, val rawId: String)
sealed interface Rg04Expected {
    data object Accepted : Rg04Expected
    data object NoChange : Rg04Expected
    data class Rejected(val reason: String, val field: String) : Rg04Expected
}
sealed interface Rg04DecodedOperation {
    val action: Rg04Action
    val operationClass: Rg04OperationClass
    val source: Rg04OperationSource
    val expected: Rg04Expected
    data class Manual(
        val input: Rg04ManualInput,
        override val expected: Rg04Expected,
        override val source: Rg04OperationSource,
        override val operationClass: Rg04OperationClass,
    ) : Rg04DecodedOperation { override val action = Rg04Action.MANUAL_MIXED_EXPENSE }
    data class Repayment(
        val input: Rg04RepaymentInput,
        override val expected: Rg04Expected,
        override val source: Rg04OperationSource,
    ) : Rg04DecodedOperation {
        override val action = Rg04Action.CREDIT_PRINCIPAL_REPAYMENT
        override val operationClass = Rg04OperationClass.CREATION
    }
}

data class Rg04DeferredOperation(
    val action: String,
    val operationClass: Rg04OperationClass,
    val expected: Rg04Expected,
    val source: Rg04OperationSource,
)
data class Rg04RawJsonCase(
    val ledgerId: LedgerId, val currency: CurrencyUnit, val timezone: String, val catalog: LedgerCatalog,
    val operations: List<Rg04DecodedOperation>, val deferredOperations: List<Rg04DeferredOperation>,
    val manualIds: MixedPaymentExpenseIds, val repaymentIds: CreditPrincipalRepaymentIds,
    val relationId: String, val relationDisplayName: String,
    val importOperations: List<Rg04DecodedImportOperation> = emptyList(),
)

data class Rg04FundingSnapshot(val accountId: AccountId, val amount: Money)
data class Rg04SettlementSnapshot(val original: Money, val discount: Money, val settled: Money)
data class Rg04ManualSnapshot(
    val ledgerId: LedgerId, val requestId: RequestId, val occurredAt: Instant, val occurredAtText: String,
    val total: Money, val categoryId: CategoryId, val funding: List<Rg04FundingSnapshot>,
    val settlement: Rg04SettlementSnapshot, val confirmed: Boolean,
    val currency: CurrencyUnit = total.currency,
)
data class Rg04RepaymentSnapshot(
    val ledgerId: LedgerId, val requestId: RequestId, val occurredAt: Instant, val occurredAtText: String,
    val assetAccountId: AccountId, val liabilityAccountId: AccountId, val principal: Money, val confirmed: Boolean,
    val currency: CurrencyUnit = principal.currency,
)
sealed interface Rg04PreparedOperation {
    data class Manual(
        val snapshot: Rg04ManualSnapshot,
        val formalIds: MixedPaymentExpenseIds,
        val relationId: String,
        val relationDisplayName: String,
    ) : Rg04PreparedOperation
    data class Repayment(
        val snapshot: Rg04RepaymentSnapshot,
        val formalIds: CreditPrincipalRepaymentIds,
    ) : Rg04PreparedOperation
}
enum class Rg04ExecutionError {
    EXPLICIT_CONFIRMATION_REQUIRED, MUST_BE_POSITIVE, FUNDING_LEG_MUST_BE_POSITIVE,
    DUPLICATE_FUNDING_ACCOUNT, FUNDING_TOTAL_MUST_EQUAL_EXPENSE, UNKNOWN_REAL_ACCOUNT,
    REAL_FINANCIAL_ACCOUNT_REQUIRED, OWNED_ACCOUNT_REQUIRED, SECONDARY_CATEGORY_REQUIRED,
    CATEGORY_INACTIVE, EXPENSE_CATEGORY_REQUIRED, SINGLE_CURRENCY_REQUIRED,
    ASSET_AND_CREDIT_LIABILITY_REQUIRED,
}
sealed interface Rg04ExecutionResult {
    data class Accepted(val confirmationId: String, val transactionId: TransactionId) : Rg04ExecutionResult
    data class NoChange(val confirmationId: String, val transactionId: TransactionId) : Rg04ExecutionResult
    data class Rejected(val error: Rg04ExecutionError, val field: String) : Rg04ExecutionResult
    data object RequestIdentityConflict : Rg04ExecutionResult
}
fun interface Rg04CommitPort { fun commit(operation: Rg04PreparedOperation): Rg04ExecutionResult }
class ExecuteRg04Operation(private val port: Rg04CommitPort) {
    fun execute(operation: Rg04PreparedOperation): Rg04ExecutionResult {
        val confirmed = when (operation) { is Rg04PreparedOperation.Manual -> operation.snapshot.confirmed; is Rg04PreparedOperation.Repayment -> operation.snapshot.confirmed }
        return if (!confirmed) Rg04ExecutionResult.Rejected(Rg04ExecutionError.EXPLICIT_CONFIRMATION_REQUIRED, "explicit_confirmation") else port.commit(operation)
    }
}

sealed interface Rg04AdaptResult { data class Success(val operation: Rg04PreparedOperation) : Rg04AdaptResult; data class Invalid(val reason: String, val field: String) : Rg04AdaptResult }

fun adaptRg04Operation(case: Rg04RawJsonCase, operation: Rg04DecodedOperation): Rg04AdaptResult = when (operation) {
    is Rg04DecodedOperation.Manual -> when (val adapted = adaptManual(case, operation.input)) {
        is Rg04AdaptResult.Invalid -> adapted
        is Rg04AdaptResult.Success -> {
            val snapshot = (adapted.operation as Rg04PreparedOperation.Manual).snapshot
            Rg04AdaptResult.Success(Rg04PreparedOperation.Manual(snapshot, case.manualIds, case.relationId, case.relationDisplayName))
        }
    }
    is Rg04DecodedOperation.Repayment -> when (val adapted = adaptRepayment(case, operation.input)) {
        is Rg04AdaptResult.Invalid -> adapted
        is Rg04AdaptResult.Success -> {
            val snapshot = (adapted.operation as Rg04PreparedOperation.Repayment).snapshot
            Rg04AdaptResult.Success(Rg04PreparedOperation.Repayment(snapshot, case.repaymentIds))
        }
    }
}

private fun adaptManual(case: Rg04RawJsonCase, input: Rg04ManualInput): Rg04AdaptResult {
    fun text(field: Rg04Field<String>): String? = (field as? Rg04Field.Value)?.value
    val request = text(input.requestId) ?: return invalid("required", "request_id")
    val occurred = text(input.occurredAt) ?: return invalid("required", "occurred_at")
    val totalText = text(input.totalAmount) ?: return invalid("required", "total_amount")
    val currencyCode = text(input.currency) ?: return invalid("required", "currency")
    val currency = CurrencyUnit(currencyCode, case.currency.precision)
    val total = money(totalText, currency) ?: return invalid("exact_decimal_string_required", "total_amount")
    val category = when (input.categoryId) { is Rg04Field.Value -> input.categoryId.value; else -> return invalid("secondary_category_required", "category_id") }
    val funding = input.funding.mapIndexed { index, item ->
        val account = text(item.accountId) ?: return invalid("required", "funding_components[$index].account_id")
        val currency = text(item.currency) ?: return invalid("required", "funding_components[$index].currency")
        val amountText = text(item.amount) ?: return invalid("required", "funding_components[$index].funding_amount")
        val amount = money(amountText, CurrencyUnit(currency, case.currency.precision))
            ?: return invalid("exact_decimal_string_required", "funding_components")
        Rg04FundingSnapshot(AccountId(account), amount)
    }
    val settlement = input.settlement ?: return invalid("required", "settlement_explanation")
    val original = money(settlement.originalAmount, currency) ?: return invalid("exact_decimal_string_required", "settlement_explanation")
    val discount = money(settlement.discountAmount, currency) ?: return invalid("exact_decimal_string_required", "settlement_explanation")
    val settled = money(settlement.settledAmount, currency) ?: return invalid("exact_decimal_string_required", "settlement_explanation")
    val occurredAt = timestamp(occurred) ?: return invalid("invalid_timestamp", "occurred_at")
    return Rg04AdaptResult.Success(Rg04PreparedOperation.Manual(Rg04ManualSnapshot(case.ledgerId, RequestId(request), occurredAt, occurred, total, CategoryId(category), funding, Rg04SettlementSnapshot(original, discount, settled), (input.explicitConfirmation as? Rg04Field.Value)?.value == true, currency), case.manualIds, case.relationId, case.relationDisplayName))
}

private fun adaptRepayment(case: Rg04RawJsonCase, input: Rg04RepaymentInput): Rg04AdaptResult {
    fun text(field: Rg04Field<String>): String? = (field as? Rg04Field.Value)?.value
    val request = text(input.requestId) ?: return invalid("required", "request_id")
    val occurred = text(input.occurredAt) ?: return invalid("required", "occurred_at")
    val asset = text(input.assetAccountId) ?: return invalid("required", "asset_account_id")
    val liability = text(input.liabilityAccountId) ?: return invalid("required", "liability_account_id")
    val principalText = text(input.principalAmount) ?: return invalid("required", "principal_amount")
    val currencyCode = text(input.currency) ?: return invalid("required", "currency")
    val currency = CurrencyUnit(currencyCode, case.currency.precision)
    val principal = money(principalText, currency) ?: return invalid("exact_decimal_string_required", "principal_amount")
    val occurredAt = timestamp(occurred) ?: return invalid("invalid_timestamp", "occurred_at")
    return Rg04AdaptResult.Success(Rg04PreparedOperation.Repayment(Rg04RepaymentSnapshot(case.ledgerId, RequestId(request), occurredAt, occurred, AccountId(asset), AccountId(liability), principal, (input.explicitConfirmation as? Rg04Field.Value)?.value == true, currency), case.repaymentIds))
}

private fun invalid(reason: String, field: String): Rg04AdaptResult.Invalid = Rg04AdaptResult.Invalid(reason, field)
private fun timestamp(text: String): Instant? = try {
    Instant.parse(text)
} catch (_: IllegalArgumentException) {
    null
}
private fun money(text: String, currency: CurrencyUnit): Money? {
    val pattern = Regex("^-?(0|[1-9][0-9]*)\\.([0-9]{${currency.precision}})$")
    val match = pattern.matchEntire(text) ?: return null
    val negative = text.startsWith('-'); val whole = match.groupValues[1]; val fraction = match.groupValues[2]
    return (if (negative) "-$whole$fraction" else "$whole$fraction").toLongOrNull()?.let { Money.ofMinor(it, currency) }
}
