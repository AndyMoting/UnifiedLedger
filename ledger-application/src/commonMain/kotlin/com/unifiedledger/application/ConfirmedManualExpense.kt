package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseIds
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.TransactionId
import kotlin.time.Instant

data class RequestId(
    val value: String,
)

data class ConfirmationId(
    val value: String,
)

data object ExplicitManualSave

data class ExplicitlyConfirmedManualExpense(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val amount: Money,
    val categoryId: CategoryId,
    val paymentAccountId: AccountId,
    val occurredAt: Instant,
    val note: String,
    val confirmation: ExplicitManualSave,
)

data class ManualExpenseRequestIdentity(
    val ledgerId: LedgerId,
    val requestId: RequestId,
)

data class ManualExpenseRequestSnapshot(
    val ledgerId: LedgerId,
    val amount: Money,
    val categoryId: CategoryId,
    val paymentAccountId: AccountId,
    val occurredAt: Instant,
    val note: String,
)

data class ConfirmedExpenseReceipt(
    val confirmationId: ConfirmationId,
    val transactionId: TransactionId,
)

data class ConfirmedManualExpenseCommitIds(
    val confirmationId: ConfirmationId,
    val expenseIds: AssetPaidOrdinaryExpenseIds,
)

fun interface ConfirmedManualExpenseIdSource {
    fun next(): ConfirmedManualExpenseCommitIds
}

data class ConfirmedManualExpenseCommit(
    val confirmationId: ConfirmationId,
    val transaction: FormalTransaction,
)

fun interface ConfirmedExpenseTransactionFactory {
    fun create(
        request: ManualExpenseRequestSnapshot,
        ids: ConfirmedManualExpenseCommitIds,
    ): DomainResult<ConfirmedManualExpenseCommit>
}

sealed interface ConfirmedManualExpenseResult {
    data class Created(
        val receipt: ConfirmedExpenseReceipt,
    ) : ConfirmedManualExpenseResult

    data class NoChange(
        val receipt: ConfirmedExpenseReceipt,
    ) : ConfirmedManualExpenseResult

    data class RequestIdentityConflict(
        val identity: ManualExpenseRequestIdentity,
    ) : ConfirmedManualExpenseResult

    data class Rejected(
        val violation: DomainViolation,
    ) : ConfirmedManualExpenseResult
}

/**
 * Atomic boundary for committing an explicitly confirmed manual expense.
 *
 * This contract defines required commit behavior only. It does not provide a storage or
 * concurrency implementation.
 */
fun interface ConfirmedManualExpenseCommitPort {
    /**
     * Implementations MUST enforce the following rules:
     *
     * - [identity] and [requestSnapshot] MUST identify the same ledger.
     * - The existing request record MUST be found by [identity] before comparing its snapshot.
     * - An equivalent replay MUST return the original receipt without invoking
     *   [createFormalTransaction] and without overwriting or rewinding the current formal
     *   transaction, including a current version created by note replacement.
     * - The same identity with a different snapshot MUST return a request identity conflict
     *   without invoking [createFormalTransaction] and without writing any state.
     * - For a first request, [createFormalTransaction] MUST be invoked at most once. A
     *   [DomainResult.Failure] MUST return the corresponding typed rejection without writing a
     *   request record, receipt, or transaction, and the identity MUST remain available for a
     *   corrected request.
     * - On [DomainResult.Success], the request record, receipt, and formal transaction MUST be
     *   committed as one indivisible all-or-nothing operation.
     */
    fun commitOnce(
        identity: ManualExpenseRequestIdentity,
        requestSnapshot: ManualExpenseRequestSnapshot,
        createFormalTransaction: () -> DomainResult<ConfirmedManualExpenseCommit>,
    ): ConfirmedManualExpenseResult
}

class ExecuteConfirmedManualExpense(
    private val commitPort: ConfirmedManualExpenseCommitPort,
    private val idSource: ConfirmedManualExpenseIdSource,
    private val createFormalTransaction: ConfirmedExpenseTransactionFactory,
) {
    fun execute(request: ExplicitlyConfirmedManualExpense): ConfirmedManualExpenseResult {
        val identity =
            ManualExpenseRequestIdentity(
                ledgerId = request.ledgerId,
                requestId = request.requestId,
            )
        val snapshot =
            ManualExpenseRequestSnapshot(
                ledgerId = request.ledgerId,
                amount = request.amount,
                categoryId = request.categoryId,
                paymentAccountId = request.paymentAccountId,
                occurredAt = request.occurredAt,
                note = request.note,
            )

        return commitPort.commitOnce(
            identity = identity,
            requestSnapshot = snapshot,
        ) {
            createFormalTransaction.create(
                request = snapshot,
                ids = idSource.next(),
            )
        }
    }
}
