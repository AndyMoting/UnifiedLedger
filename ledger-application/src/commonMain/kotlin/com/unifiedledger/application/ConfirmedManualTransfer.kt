package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountTransfer
import com.unifiedledger.domain.AccountTransferIds
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.TransactionId
import kotlin.time.Instant

/**
 * P7-02.B T-1..T-5 manual transfer product chain, mirroring the manual-expense/income pattern:
 * a canonical snapshot as the only equivalent-replay basis, a claim-first commit port, a
 * four-state result, a save-input gate and a snapshot-aware resolver.
 *
 * The transfer source debit is derived as `destinationCredit + fee` (T-4/P1-2); the product
 * request never accepts an independent source amount, so `TransferAmountMismatch` is a
 * defensive dead code in the product UI.
 */
data class ManualTransferRequestIdentity(
    val ledgerId: LedgerId,
    val requestId: RequestId,
)

data class ManualTransferRequestSnapshot(
    val ledgerId: LedgerId,
    val sourceAccountId: AccountId,
    val destinationAccountId: AccountId,
    val destinationCredit: Money,
    val fee: Money,
    val feeCategoryId: CategoryId?,
    val occurredAt: Instant,
    val note: String,
)

data class ConfirmedTransferReceipt(
    val confirmationId: ConfirmationId,
    val transactionId: TransactionId,
)

data class ConfirmedManualTransferCommitIds(
    val confirmationId: ConfirmationId,
    val transferIds: AccountTransferIds,
)

fun interface ConfirmedManualTransferIdSource {
    fun next(): ConfirmedManualTransferCommitIds
}

data class ConfirmedManualTransferCommit(
    val confirmationId: ConfirmationId,
    val transfer: AccountTransfer,
) {
    val transaction: FormalTransaction get() = transfer.formalTransaction
}

/**
 * Product factory that turns one confirmed snapshot into a transfer commit. Implementations
 * build the domain command (`sourceDebit = destinationCredit + fee`, note) and delegate to the
 * reused domain factory. A residual domain rejection is mapped onto the stable product token.
 */
fun interface ConfirmedTransferTransactionFactory {
    fun create(
        request: ManualTransferRequestSnapshot,
        ids: ConfirmedManualTransferCommitIds,
    ): DomainResult<ConfirmedManualTransferCommit>
}

sealed interface ConfirmedManualTransferResult {
    data class Created(
        val receipt: ConfirmedTransferReceipt,
    ) : ConfirmedManualTransferResult

    data class NoChange(
        val receipt: ConfirmedTransferReceipt,
    ) : ConfirmedManualTransferResult

    data class RequestIdentityConflict(
        val identity: ManualTransferRequestIdentity,
    ) : ConfirmedManualTransferResult

    data class Rejected(
        val violation: DomainViolation,
    ) : ConfirmedManualTransferResult
}

/**
 * Claim-first atomic boundary for one confirmed manual transfer. The same all-or-nothing and
 * equivalent-replay rules as [ConfirmedManualExpenseCommitPort] apply: an equivalent replay
 * returns the original receipt with zero writes; the same identity with a different snapshot is
 * a request identity conflict with zero writes; a first-request domain failure rolls the claim
 * back so the identity stays retryable.
 */
fun interface ConfirmedManualTransferCommitPort {
    fun commitOnce(
        identity: ManualTransferRequestIdentity,
        requestSnapshot: ManualTransferRequestSnapshot,
        createFormalTransaction: () -> DomainResult<ConfirmedManualTransferCommit>,
    ): ConfirmedManualTransferResult
}

class ExecuteConfirmedManualTransfer(
    private val commitPort: ConfirmedManualTransferCommitPort,
    private val idSource: ConfirmedManualTransferIdSource,
    private val createFormalTransaction: ConfirmedTransferTransactionFactory,
) {
    fun execute(request: ExplicitlyConfirmedManualTransfer): ConfirmedManualTransferResult {
        val identity = ManualTransferRequestIdentity(request.ledgerId, request.requestId)
        val snapshot =
            ManualTransferRequestSnapshot(
                ledgerId = request.ledgerId,
                sourceAccountId = request.sourceAccountId,
                destinationAccountId = request.destinationAccountId,
                destinationCredit = request.destinationCredit,
                fee = request.fee,
                feeCategoryId = request.feeCategoryId,
                occurredAt = request.occurredAt,
                note = request.note,
            )
        return commitPort.commitOnce(identity, snapshot) {
            createFormalTransaction.create(snapshot, idSource.next())
        }
    }
}

data class ExplicitlyConfirmedManualTransfer(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val sourceAccountId: AccountId,
    val destinationAccountId: AccountId,
    val destinationCredit: Money,
    val fee: Money,
    val feeCategoryId: CategoryId?,
    val occurredAt: Instant,
    val note: String,
    val confirmation: ExplicitManualSave,
)

enum class ManualTransferInputField {
    SOURCE_ACCOUNT,
    DESTINATION_ACCOUNT,
    DESTINATION_CREDIT,
    FEE,
    FEE_CATEGORY,
}

sealed interface ManualTransferSaveResult {
    data class InvalidInput(
        val fields: Set<ManualTransferInputField>,
    ) : ManualTransferSaveResult

    data class Executed(
        val result: ConfirmedManualTransferResult,
    ) : ManualTransferSaveResult
}

data class ManualTransferSaveInput(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val sourceAccountId: AccountId?,
    val destinationAccountId: AccountId?,
    val destinationCredit: Money?,
    /** T-1: optional; `null` means an explicit `0.00` in the destination-credit currency. */
    val fee: Money?,
    val feeCategoryId: CategoryId?,
    val occurredAt: Instant,
    val note: String,
    val confirmation: ExplicitManualSave,
)

class ExecuteManualTransferSave(
    private val executeConfirmed: ExecuteConfirmedManualTransfer,
) {
    fun execute(input: ManualTransferSaveInput): ManualTransferSaveResult {
        val source = input.sourceAccountId
        val destination = input.destinationAccountId
        val destinationCredit = input.destinationCredit
        val failures = mutableSetOf<ManualTransferInputField>()
        if (source == null) failures += ManualTransferInputField.SOURCE_ACCOUNT
        if (destination == null) failures += ManualTransferInputField.DESTINATION_ACCOUNT
        if (destinationCredit == null) failures += ManualTransferInputField.DESTINATION_CREDIT
        if (failures.isNotEmpty()) return ManualTransferSaveResult.InvalidInput(failures.toSet())

        val fee = input.fee ?: Money.ofMinor(0L, checkNotNull(destinationCredit).currency)
        // T-4: a positive fee requires a fee category; a zero fee must not carry one.
        if (fee.minorUnits > 0L && input.feeCategoryId == null) {
            return ManualTransferSaveResult.InvalidInput(setOf(ManualTransferInputField.FEE_CATEGORY))
        }

        return ManualTransferSaveResult.Executed(
            executeConfirmed.execute(
                ExplicitlyConfirmedManualTransfer(
                    ledgerId = input.ledgerId,
                    requestId = input.requestId,
                    sourceAccountId = checkNotNull(source),
                    destinationAccountId = checkNotNull(destination),
                    destinationCredit = checkNotNull(destinationCredit),
                    fee = fee,
                    feeCategoryId = input.feeCategoryId,
                    occurredAt = input.occurredAt,
                    note = input.note,
                    confirmation = input.confirmation,
                ),
            ),
        )
    }
}
