package com.unifiedledger.application

import com.unifiedledger.domain.*
import kotlin.time.Instant

sealed interface Rg05Field<out T> {
    data object Omitted : Rg05Field<Nothing>
    data object Null : Rg05Field<Nothing>
    data class Value<T>(val value: T) : Rg05Field<T>
}

data class Rg05ItemInput(
    val itemId: Rg05Field<String>,
    val amount: Rg05Field<String>,
    val currency: Rg05Field<String>,
    val categoryId: Rg05Field<String>,
    val details: Rg05Field<String>,
    val sourceObservedAt: Rg05Field<String>,
)

data class Rg05ManualInput(
    val requestId: Rg05Field<String>,
    val paymentAt: Rg05Field<String>,
    val totalAmount: Rg05Field<String>,
    val currency: Rg05Field<String>,
    val fundingAccountId: Rg05Field<String>,
    val items: List<Rg05ItemInput>,
    val explicitConfirmation: Rg05Field<Boolean>,
)

data class Rg05ManualSnapshot(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val paymentAt: Instant,
    val paymentAtText: String,
    val total: Money,
    val fundingAccountId: AccountId,
    val items: List<MergedPaymentItem>,
    val confirmed: Boolean,
    val statisticsAt: Instant = paymentAt,
    val statisticsAtText: String = paymentAtText,
)

sealed interface Rg05PreparedOperation {
    data class Manual(
        val snapshot: Rg05ManualSnapshot,
        val formalIds: MergedPaymentExpenseIds,
        val relationId: String,
        val confirmationId: String,
        val reconciliationId: String,
        val consumptionIds: Map<String, String> = emptyMap(),
        val allocationIds: Map<String, String> = emptyMap(),
    ) : Rg05PreparedOperation

    data class Ingest(val snapshot: Rg05IngestSnapshot) : Rg05PreparedOperation

    data class Confirm(
        val snapshot: Rg05ConfirmSnapshot,
        val formalIds: MergedPaymentExpenseIds,
        val relationId: String,
        val confirmationId: String,
        val reconciliationId: String,
        val bankEvidenceLinkId: String,
        val itemEvidenceLinkIds: Map<String, String>,
        val consumptionIds: Map<String, String>,
        val allocationIds: Map<String, String>,
    ) : Rg05PreparedOperation

    data class Receipt(val snapshot: Rg05ReceiptSnapshot) : Rg05PreparedOperation
}

enum class Rg05Action {
    MANUAL_MERGED_PAYMENT,
    INGEST_MERGED_PAYMENT_FACTS,
    CONFIRM_MERGED_PAYMENT_CANDIDATE,
    MERGE_ITEM_RECEIPT_EVIDENCE,
}
enum class Rg05OperationClass { CREATION, REJECTION, RECONCILIATION }
enum class Rg05ExecutionError {
    EXPLICIT_CONFIRMATION_REQUIRED, MUST_BE_POSITIVE, ITEM_AMOUNT_MUST_BE_POSITIVE,
    ALLOCATION_TOTAL_MUST_EQUAL_PAYMENT, DUPLICATE_ITEM_ID, UNKNOWN_REAL_ACCOUNT,
    REAL_FINANCIAL_ACCOUNT_REQUIRED, ASSET_ACCOUNT_REQUIRED, OWNED_ACCOUNT_REQUIRED,
    SECONDARY_CATEGORY_REQUIRED, CATEGORY_INACTIVE, EXPENSE_CATEGORY_REQUIRED,
    SINGLE_CURRENCY_REQUIRED, INVALID_TIMESTAMP,
    CANDIDATE_NOT_FOUND, CANDIDATE_NOT_PENDING, ALLOCATION_INCOMPLETE,
    ALLOCATION_CONFLICT, ALLOCATION_TARGET_NOT_FOUND, ALLOCATION_TARGET_MISMATCH,
    INTERNAL_DOMAIN_VIOLATION,
}

sealed interface Rg05ExecutionResult {
    data class Accepted(val confirmationId: String, val transactionId: TransactionId, val relationId: String) : Rg05ExecutionResult
    data class NoChange(val confirmationId: String, val transactionId: TransactionId, val relationId: String) : Rg05ExecutionResult
    data class IngestAccepted(val candidateId: String, val sourceIds: List<String>, val evidenceIds: List<String>) : Rg05ExecutionResult
    data class IngestNoChange(val candidateId: String, val sourceIds: List<String>, val evidenceIds: List<String>) : Rg05ExecutionResult
    data class ReceiptAccepted(val sourceId: String, val evidenceId: String, val evidenceLinkId: String) : Rg05ExecutionResult
    data class ReceiptNoChange(val sourceId: String, val evidenceId: String, val evidenceLinkId: String) : Rg05ExecutionResult
    data class Rejected(val error: Rg05ExecutionError, val field: String) : Rg05ExecutionResult
    data object RequestIdentityConflict : Rg05ExecutionResult
}

fun interface Rg05CommitPort { fun commit(operation: Rg05PreparedOperation): Rg05ExecutionResult }

class ExecuteRg05Operation(private val port: Rg05CommitPort) {
    fun execute(operation: Rg05PreparedOperation): Rg05ExecutionResult {
        val confirmed = when (operation) {
            is Rg05PreparedOperation.Manual -> operation.snapshot.confirmed
            is Rg05PreparedOperation.Confirm -> operation.snapshot.confirmed
            is Rg05PreparedOperation.Ingest, is Rg05PreparedOperation.Receipt -> true
        }
        return if (!confirmed) Rg05ExecutionResult.Rejected(Rg05ExecutionError.EXPLICIT_CONFIRMATION_REQUIRED, "explicit_confirmation") else port.commit(operation)
    }
}

enum class Rg05EvidenceKind { BANK_PAYMENT, ITEM_RECEIPT, ITEM_SUMMARY }

data class Rg05BankFact(
    val sourceId: String,
    val evidenceId: String,
    val observedAt: Instant,
    val observedAtText: String,
    val details: String,
    val amount: Money,
)

data class Rg05ItemFact(
    val itemId: String,
    val sourceId: String,
    val evidenceId: String,
    val evidenceKind: Rg05EvidenceKind,
    val observedAt: Instant,
    val observedAtText: String,
    val details: String,
    val amount: Money,
    val suggestedCategoryId: CategoryId,
)

data class Rg05IngestSnapshot(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val bankFact: Rg05BankFact,
    val itemFacts: List<Rg05ItemFact>,
    val candidateId: String,
    val pendingStatusId: String,
)

data class Rg05ConfirmAllocation(
    val itemId: String,
    val categoryId: CategoryId,
    val amount: Money,
)

data class Rg05ConfirmSnapshot(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val candidateId: String,
    val fundingAccountId: AccountId,
    val paymentAt: Instant,
    val paymentAtText: String,
    val statisticsAt: Instant,
    val statisticsAtText: String,
    val allocations: List<Rg05ConfirmAllocation>,
    val confirmed: Boolean,
    val confirmedStatusId: String,
)

data class Rg05ReceiptSnapshot(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val sourceId: String,
    val evidenceId: String,
    val allocationId: String,
    val evidenceLinkId: String,
    val observedAt: Instant,
    val observedAtText: String,
    val details: String,
    val amount: Money,
)

sealed interface Rg05AdaptResult {
    data class Success(val operation: Rg05PreparedOperation) : Rg05AdaptResult
    data class Invalid(val reason: String, val field: String) : Rg05AdaptResult
}

fun adaptRg05Manual(case: Rg05RawJsonCase, input: Rg05ManualInput, ids: Rg05PreparedIds): Rg05AdaptResult {
    fun text(field: Rg05Field<String>, name: String): String? = (field as? Rg05Field.Value)?.value ?: return null
    val request = text(input.requestId, "request_id") ?: return Rg05AdaptResult.Invalid("required", "request_id")
    val paymentText = text(input.paymentAt, "payment_at") ?: return Rg05AdaptResult.Invalid("required", "payment_at")
    val totalText = text(input.totalAmount, "total_amount") ?: return Rg05AdaptResult.Invalid("required", "total_amount")
    val currencyCode = text(input.currency, "currency") ?: return Rg05AdaptResult.Invalid("required", "currency")
    val funding = text(input.fundingAccountId, "funding_account_id") ?: return Rg05AdaptResult.Invalid("required", "funding_account_id")
    val currency = CurrencyUnit(currencyCode, case.currency.precision)
    val total = exactMoney(totalText, currency) ?: return Rg05AdaptResult.Invalid("exact_decimal_string_required", "total_amount")
    val payment = try { Instant.parse(paymentText) } catch (_: IllegalArgumentException) { return Rg05AdaptResult.Invalid("invalid_timestamp", "payment_at") }
    val items = input.items.mapIndexed { index, item ->
        val itemId = text(item.itemId, "items[$index].item_id") ?: return Rg05AdaptResult.Invalid("required", "items[$index].item_id")
        val amountText = text(item.amount, "items[$index].amount") ?: return Rg05AdaptResult.Invalid("required", "items[$index].amount")
        val itemCurrency = text(item.currency, "items[$index].currency") ?: return Rg05AdaptResult.Invalid("required", "items[$index].currency")
        val category = (item.categoryId as? Rg05Field.Value)?.value ?: return Rg05AdaptResult.Invalid("secondary_category_required", "items")
        val details = text(item.details, "items[$index].details") ?: return Rg05AdaptResult.Invalid("required", "items[$index].details")
        val observedText = text(item.sourceObservedAt, "items[$index].source_observed_at") ?: return Rg05AdaptResult.Invalid("required", "items[$index].source_observed_at")
        val amount = exactMoney(amountText, CurrencyUnit(itemCurrency, case.currency.precision)) ?: return Rg05AdaptResult.Invalid("exact_decimal_string_required", "items[$index].amount")
        val observed = try { Instant.parse(observedText) } catch (_: IllegalArgumentException) { return Rg05AdaptResult.Invalid("invalid_timestamp", "items[$index].source_observed_at") }
        MergedPaymentItem(itemId, amount, CategoryId(category), details, observed)
    }
    return Rg05AdaptResult.Success(Rg05PreparedOperation.Manual(Rg05ManualSnapshot(case.ledgerId, RequestId(request), payment, paymentText, total, AccountId(funding), items, (input.explicitConfirmation as? Rg05Field.Value)?.value == true), ids.formalIds, ids.relationId, ids.confirmationId, ids.reconciliationId, ids.consumptionIds, ids.allocationIds))
}

data class Rg05PreparedIds(
    val formalIds: MergedPaymentExpenseIds,
    val relationId: String,
    val confirmationId: String,
    val reconciliationId: String,
    val consumptionIds: Map<String, String> = emptyMap(),
    val allocationIds: Map<String, String> = emptyMap(),
)
data class Rg05RawJsonCase(
    val ledgerId: LedgerId,
    val currency: CurrencyUnit,
    val timezone: String,
    val catalog: LedgerCatalog,
    val manual: Rg05ManualInput,
    val importOperations: List<Rg05PreparedOperation> = emptyList(),
    /** Fixture-owned formal IDs plus derived operational IDs; null for hand-built cases. */
    val manualIds: Rg05PreparedIds? = null,
)

internal fun exactMoney(text: String, currency: CurrencyUnit): Money? {
    val match = Regex("^-?(0|[1-9][0-9]*)\\.([0-9]{${currency.precision}})$").matchEntire(text) ?: return null
    val minor = (if (text.startsWith('-')) "-${match.groupValues[1]}${match.groupValues[2]}" else "${match.groupValues[1]}${match.groupValues[2]}").toLongOrNull() ?: return null
    return Money.ofMinor(minor, currency)
}
