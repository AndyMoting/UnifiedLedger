package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AssetReceivedOrdinaryIncomeIds
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.MerchantId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.TagId
import com.unifiedledger.domain.TransactionId
import kotlin.time.Instant

data class ExplicitlyConfirmedManualIncome(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val amount: Money,
    val categoryId: CategoryId,
    val receivingAccountId: AccountId,
    val occurredAt: Instant,
    val note: String,
    val tagIds: Set<TagId>,
    val merchantId: MerchantId?,
    /** P7-08 08.B-1 (D-221; ruling R3): write-path-only annotation audit time (no default). */
    val createdAt: Instant,
    val confirmation: ExplicitManualSave,
)

data class ManualIncomeRequestIdentity(
    val ledgerId: LedgerId,
    val requestId: RequestId,
)

data class ManualIncomeRequestSnapshot(
    val ledgerId: LedgerId,
    val amount: Money,
    val categoryId: CategoryId,
    val receivingAccountId: AccountId,
    val occurredAt: Instant,
    val note: String,
    val tagIds: Set<TagId> = emptySet(),
    val merchantId: MerchantId? = null,
)

data class ConfirmedIncomeReceipt(
    val confirmationId: ConfirmationId,
    val transactionId: TransactionId,
)

data class ConfirmedManualIncomeCommitIds(
    val confirmationId: ConfirmationId,
    val incomeIds: AssetReceivedOrdinaryIncomeIds,
)

fun interface ConfirmedManualIncomeIdSource {
    fun next(): ConfirmedManualIncomeCommitIds
}

data class ConfirmedManualIncomeCommit(
    val confirmationId: ConfirmationId,
    val transaction: FormalTransaction,
)

fun interface ConfirmedIncomeTransactionFactory {
    fun create(
        request: ManualIncomeRequestSnapshot,
        ids: ConfirmedManualIncomeCommitIds,
    ): DomainResult<ConfirmedManualIncomeCommit>
}

sealed interface ConfirmedManualIncomeResult {
    data class Created(
        val receipt: ConfirmedIncomeReceipt,
    ) : ConfirmedManualIncomeResult

    data class NoChange(
        val receipt: ConfirmedIncomeReceipt,
    ) : ConfirmedManualIncomeResult

    data class RequestIdentityConflict(
        val identity: ManualIncomeRequestIdentity,
    ) : ConfirmedManualIncomeResult

    data class Rejected(
        val violation: DomainViolation,
    ) : ConfirmedManualIncomeResult
}

fun interface ConfirmedManualIncomeCommitPort {
    fun commitOnce(
        identity: ManualIncomeRequestIdentity,
        requestSnapshot: ManualIncomeRequestSnapshot,
        createdAt: Instant,
        createFormalTransaction: () -> DomainResult<ConfirmedManualIncomeCommit>,
    ): ConfirmedManualIncomeResult
}

class ExecuteConfirmedManualIncome(
    private val commitPort: ConfirmedManualIncomeCommitPort,
    private val idSource: ConfirmedManualIncomeIdSource,
    private val createFormalTransaction: ConfirmedIncomeTransactionFactory,
) {
    fun execute(request: ExplicitlyConfirmedManualIncome): ConfirmedManualIncomeResult {
        val identity = ManualIncomeRequestIdentity(request.ledgerId, request.requestId)
        val snapshot =
            ManualIncomeRequestSnapshot(
                ledgerId = request.ledgerId,
                amount = request.amount,
                categoryId = request.categoryId,
                receivingAccountId = request.receivingAccountId,
                occurredAt = request.occurredAt,
                note = request.note,
                tagIds = request.tagIds,
                merchantId = request.merchantId,
            )
        return commitPort.commitOnce(identity, snapshot, request.createdAt) { createFormalTransaction.create(snapshot, idSource.next()) }
    }
}

data class ManualIncomeSaveInput(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val amount: Money?,
    val categoryId: CategoryId?,
    val receivingAccountId: AccountId?,
    val occurredAt: Instant,
    val note: String,
    /** P7-08 08.B-1 (D-221; ruling R3): sampled once from `LedgerClock` by the confirming action. */
    val createdAt: Instant,
    val tagIds: Set<TagId> = emptySet(),
    val merchantId: MerchantId? = null,
    val confirmation: ExplicitManualSave,
)

enum class ManualIncomeInputField { AMOUNT, RECEIVING_ACCOUNT, CATEGORY }

sealed interface ManualIncomeSaveResult {
    data class InvalidInput(
        val fields: Set<ManualIncomeInputField>,
    ) : ManualIncomeSaveResult

    data class Executed(
        val result: ConfirmedManualIncomeResult,
    ) : ManualIncomeSaveResult
}

class ExecuteManualIncomeSave(
    private val executeConfirmed: ExecuteConfirmedManualIncome,
) {
    fun execute(input: ManualIncomeSaveInput): ManualIncomeSaveResult {
        val missing =
            buildSet {
                if (input.amount == null) add(ManualIncomeInputField.AMOUNT)
                if (input.categoryId == null) add(ManualIncomeInputField.CATEGORY)
                if (input.receivingAccountId == null) add(ManualIncomeInputField.RECEIVING_ACCOUNT)
            }
        if (missing.isNotEmpty()) return ManualIncomeSaveResult.InvalidInput(missing)
        return ManualIncomeSaveResult.Executed(
            executeConfirmed.execute(
                ExplicitlyConfirmedManualIncome(
                    ledgerId = input.ledgerId,
                    requestId = input.requestId,
                    amount = checkNotNull(input.amount),
                    categoryId = checkNotNull(input.categoryId),
                    receivingAccountId = checkNotNull(input.receivingAccountId),
                    occurredAt = input.occurredAt,
                    note = input.note,
                    tagIds = input.tagIds,
                    merchantId = input.merchantId,
                    createdAt = input.createdAt,
                    confirmation = input.confirmation,
                ),
            ),
        )
    }
}
