package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import kotlin.time.Instant

data class ManualExpenseSaveInput(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val amount: Money?,
    val categoryId: CategoryId?,
    val paymentAccountId: AccountId?,
    val occurredAt: Instant,
    val note: String,
    val confirmation: ExplicitManualSave,
)

enum class ManualExpenseInputField {
    AMOUNT,
    PAYMENT_ACCOUNT,
    CATEGORY,
}

sealed interface ManualExpenseInputFailure {
    data class Missing(
        val field: ManualExpenseInputField,
    ) : ManualExpenseInputFailure
}

sealed interface ManualExpenseSaveResult {
    data class InvalidInput(
        val failures: Set<ManualExpenseInputFailure>,
    ) : ManualExpenseSaveResult

    data class Executed(
        val result: ConfirmedManualExpenseResult,
    ) : ManualExpenseSaveResult
}

class ExecuteManualExpenseSave(
    private val executeConfirmed: ExecuteConfirmedManualExpense,
) {
    fun execute(input: ManualExpenseSaveInput): ManualExpenseSaveResult {
        val amount = input.amount
        val categoryId = input.categoryId
        val paymentAccountId = input.paymentAccountId
        val failures = mutableSetOf<ManualExpenseInputFailure>()

        if (amount == null) {
            failures += ManualExpenseInputFailure.Missing(ManualExpenseInputField.AMOUNT)
        }
        if (paymentAccountId == null) {
            failures += ManualExpenseInputFailure.Missing(ManualExpenseInputField.PAYMENT_ACCOUNT)
        }
        if (categoryId == null) {
            failures += ManualExpenseInputFailure.Missing(ManualExpenseInputField.CATEGORY)
        }
        if (failures.isNotEmpty()) {
            return ManualExpenseSaveResult.InvalidInput(failures.toSet())
        }

        val confirmed = ExplicitlyConfirmedManualExpense(
            ledgerId = input.ledgerId,
            requestId = input.requestId,
            amount = checkNotNull(amount),
            categoryId = checkNotNull(categoryId),
            paymentAccountId = checkNotNull(paymentAccountId),
            occurredAt = input.occurredAt,
            note = input.note,
            confirmation = input.confirmation,
        )
        return ManualExpenseSaveResult.Executed(executeConfirmed.execute(confirmed))
    }
}
