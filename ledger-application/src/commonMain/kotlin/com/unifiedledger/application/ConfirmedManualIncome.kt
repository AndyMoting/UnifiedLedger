package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AssetReceivedOrdinaryIncomeIds
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.TransactionId
import kotlin.time.Instant

data class ExplicitlyConfirmedManualIncome(val ledgerId: LedgerId, val requestId: RequestId, val amount: Money, val categoryId: CategoryId, val receivingAccountId: AccountId, val occurredAt: Instant, val note: String, val confirmation: ExplicitManualSave)
data class ManualIncomeRequestIdentity(val ledgerId: LedgerId, val requestId: RequestId)
data class ManualIncomeRequestSnapshot(val ledgerId: LedgerId, val amount: Money, val categoryId: CategoryId, val receivingAccountId: AccountId, val occurredAt: Instant, val note: String)
data class ConfirmedIncomeReceipt(val confirmationId: ConfirmationId, val transactionId: TransactionId)
data class ConfirmedManualIncomeCommitIds(val confirmationId: ConfirmationId, val incomeIds: AssetReceivedOrdinaryIncomeIds)
fun interface ConfirmedManualIncomeIdSource { fun next(): ConfirmedManualIncomeCommitIds }
data class ConfirmedManualIncomeCommit(val confirmationId: ConfirmationId, val transaction: FormalTransaction)
fun interface ConfirmedIncomeTransactionFactory { fun create(request: ManualIncomeRequestSnapshot, ids: ConfirmedManualIncomeCommitIds): DomainResult<ConfirmedManualIncomeCommit> }
sealed interface ConfirmedManualIncomeResult {
    data class Created(val receipt: ConfirmedIncomeReceipt) : ConfirmedManualIncomeResult
    data class NoChange(val receipt: ConfirmedIncomeReceipt) : ConfirmedManualIncomeResult
    data class RequestIdentityConflict(val identity: ManualIncomeRequestIdentity) : ConfirmedManualIncomeResult
    data class Rejected(val violation: DomainViolation) : ConfirmedManualIncomeResult
}
fun interface ConfirmedManualIncomeCommitPort {
    fun commitOnce(identity: ManualIncomeRequestIdentity, requestSnapshot: ManualIncomeRequestSnapshot, createFormalTransaction: () -> DomainResult<ConfirmedManualIncomeCommit>): ConfirmedManualIncomeResult
}
class ExecuteConfirmedManualIncome(private val commitPort: ConfirmedManualIncomeCommitPort, private val idSource: ConfirmedManualIncomeIdSource, private val createFormalTransaction: ConfirmedIncomeTransactionFactory) {
    fun execute(request: ExplicitlyConfirmedManualIncome): ConfirmedManualIncomeResult {
        val identity = ManualIncomeRequestIdentity(request.ledgerId, request.requestId)
        val snapshot = ManualIncomeRequestSnapshot(request.ledgerId, request.amount, request.categoryId, request.receivingAccountId, request.occurredAt, request.note)
        return commitPort.commitOnce(identity, snapshot) { createFormalTransaction.create(snapshot, idSource.next()) }
    }
}

data class ManualIncomeSaveInput(val ledgerId: LedgerId, val requestId: RequestId, val amount: Money?, val categoryId: CategoryId?, val receivingAccountId: AccountId?, val occurredAt: Instant, val note: String, val confirmation: ExplicitManualSave)
enum class ManualIncomeInputField { AMOUNT, RECEIVING_ACCOUNT, CATEGORY }
sealed interface ManualIncomeSaveResult { data class InvalidInput(val fields: Set<ManualIncomeInputField>) : ManualIncomeSaveResult; data class Executed(val result: ConfirmedManualIncomeResult) : ManualIncomeSaveResult }
class ExecuteManualIncomeSave(private val executeConfirmed: ExecuteConfirmedManualIncome) {
    fun execute(input: ManualIncomeSaveInput): ManualIncomeSaveResult {
        val missing = buildSet { if (input.amount == null) add(ManualIncomeInputField.AMOUNT); if (input.categoryId == null) add(ManualIncomeInputField.CATEGORY); if (input.receivingAccountId == null) add(ManualIncomeInputField.RECEIVING_ACCOUNT) }
        if (missing.isNotEmpty()) return ManualIncomeSaveResult.InvalidInput(missing)
        return ManualIncomeSaveResult.Executed(executeConfirmed.execute(ExplicitlyConfirmedManualIncome(input.ledgerId, input.requestId, checkNotNull(input.amount), checkNotNull(input.categoryId), checkNotNull(input.receivingAccountId), input.occurredAt, input.note, input.confirmation)))
    }
}
