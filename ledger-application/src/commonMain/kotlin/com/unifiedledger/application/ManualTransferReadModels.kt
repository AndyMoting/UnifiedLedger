package com.unifiedledger.application

import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.ManualTransferViolation
import com.unifiedledger.domain.TransactionVersionId

/**
 * P7-02.B stable transfer failure codes (spec section 5.3). `code` is the frozen literal;
 * messages are never compared.
 */
enum class ManualTransferFailureCode(
    val code: String,
) {
    TRANSFER_SAME_ACCOUNT("TransferSameAccount"),
    TRANSFER_ACCOUNT_NOT_ELIGIBLE("TransferAccountNotEligible"),
    TRANSFER_AMOUNT_MUST_BE_POSITIVE("TransferAmountMustBePositive"),
    TRANSFER_FEE_MUST_NOT_BE_NEGATIVE("TransferFeeMustNotBeNegative"),
    TRANSFER_AMOUNT_MISMATCH("TransferAmountMismatch"),
    TRANSFER_FEE_CATEGORY_REQUIRED("TransferFeeCategoryRequired"),
    TRANSFER_FEE_CATEGORY_NOT_ALLOWED("TransferFeeCategoryNotAllowed"),
    ;

    companion object {
        fun of(violation: DomainViolation): ManualTransferFailureCode? =
            when (violation) {
                ManualTransferViolation.TransferSameAccount -> TRANSFER_SAME_ACCOUNT
                ManualTransferViolation.TransferAccountNotEligible -> TRANSFER_ACCOUNT_NOT_ELIGIBLE
                ManualTransferViolation.TransferAmountMustBePositive -> TRANSFER_AMOUNT_MUST_BE_POSITIVE
                ManualTransferViolation.TransferFeeMustNotBeNegative -> TRANSFER_FEE_MUST_NOT_BE_NEGATIVE
                ManualTransferViolation.TransferAmountMismatch -> TRANSFER_AMOUNT_MISMATCH
                ManualTransferViolation.TransferFeeCategoryRequired -> TRANSFER_FEE_CATEGORY_REQUIRED
                ManualTransferViolation.TransferFeeCategoryNotAllowed -> TRANSFER_FEE_CATEGORY_NOT_ALLOWED
                else -> null
            }
    }
}

/**
 * P7-02.B read model for one persisted manual transfer, mirroring [ManualExpenseCommitRecord].
 */
data class ManualTransferCommitRecord(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val snapshot: ManualTransferRequestSnapshot,
    val receipt: ConfirmedTransferReceipt,
    val currentVersionId: TransactionVersionId,
)

/**
 * P7-02.B snapshot-aware unknown-commit resolution. Compares the persisted snapshot field by
 * field (ledger, both accounts, destination credit, fee, fee category presence, occurredAt and
 * note). Only [ManualTransferCommitResolution.MatchingReceipt] recovers success.
 */
sealed interface ManualTransferCommitResolution {
    data class MatchingReceipt(
        val receipt: ConfirmedTransferReceipt,
    ) : ManualTransferCommitResolution

    data object SnapshotConflict : ManualTransferCommitResolution

    data object Absent : ManualTransferCommitResolution

    data object Unavailable : ManualTransferCommitResolution
}

class ResolveManualTransferCommitStatus(
    private val readPort: LedgerCurrentStateReadPort,
) {
    fun resolve(
        ledgerId: LedgerId,
        requestId: RequestId,
        attempted: ManualTransferRequestSnapshot,
    ): ManualTransferCommitResolution {
        val record =
            try {
                readPort.findManualTransferByRequest(ledgerId, requestId)
            } catch (failure: Exception) {
                return ManualTransferCommitResolution.Unavailable
            }
        return when {
            record == null -> ManualTransferCommitResolution.Absent
            record.snapshot == attempted -> ManualTransferCommitResolution.MatchingReceipt(record.receipt)
            else -> ManualTransferCommitResolution.SnapshotConflict
        }
    }
}

/** P7-02.B independent transfer request id source (one UUIDv7 per new intent). */
fun interface ManualTransferRequestIdSource {
    fun next(): RequestId
}

class UuidV7ManualTransferRequestIdSource(
    private val generator: UuidV7Generator,
) : ManualTransferRequestIdSource {
    override fun next(): RequestId = RequestId(generator.next())
}

/**
 * P7-02.B income-style option projection for transfers: the source and destination drawers are
 * both the same owned real ASSET account set already used by the expense chain, so the existing
 * [PaymentAccountOption] shape is reused; the fee categories are the same active leaf EXPENSE
 * options as the expense chain.
 */
data class ManualTransferOptions(
    val ownedAssetAccounts: List<PaymentAccountOption>,
    val feeCategories: List<ExpenseCategoryOption>,
)

fun interface ManualTransferOptionsProvider {
    fun queryOptions(): ManualTransferOptions
}

class QueryManualTransferOptions(
    private val ledgerId: LedgerId,
    private val catalog: LedgerCatalog,
) : ManualTransferOptionsProvider {
    override fun queryOptions(): ManualTransferOptions {
        val expenseOptions = QueryManualExpenseOptions(ledgerId, catalog).queryOptions()
        return ManualTransferOptions(
            ownedAssetAccounts = expenseOptions.paymentAccounts,
            feeCategories = expenseOptions.expenseCategories,
        )
    }
}

class QueryAuthoritativeManualTransferOptions(
    private val reader: CatalogAuthorityReader,
    private val ledgerId: LedgerId,
) : ManualTransferOptionsProvider {
    override fun queryOptions(): ManualTransferOptions {
        val catalog = reader.load(ledgerId)?.catalog ?: return ManualTransferOptions(emptyList(), emptyList())
        return QueryManualTransferOptions(ledgerId, catalog).queryOptions()
    }
}
