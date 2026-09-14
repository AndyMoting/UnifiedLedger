package com.unifiedledger.application

import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlin.time.Instant

/**
 * P5-03 read boundary (D-119 section 4; plan section 3.2.3).
 *
 * The data adapter returns only ledger-scoped, current-version rows and the persisted
 * request/snapshot/receipt relationships with ledger-signed minor units; the application
 * layer owns ownership/kind/currency validation and the normal-balance display sign.
 */
data class CurrentVersionRow(
    val transactionId: TransactionId,
    val currentVersionId: TransactionVersionId,
    val kind: TransactionKind,
    val occurredAt: Instant,
    val postings: List<Posting>,
)

data class ManualExpenseCommitRecord(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val snapshot: ManualExpenseRequestSnapshot,
    val receipt: ConfirmedExpenseReceipt,
    val currentVersionId: TransactionVersionId,
)

/**
 * P7-02.A S-1 income analogue of [ManualExpenseCommitRecord]: the persisted request/snapshot/
 * receipt relationship plus the transaction's current version id.
 */
data class ManualIncomeCommitRecord(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val snapshot: ManualIncomeRequestSnapshot,
    val receipt: ConfirmedIncomeReceipt,
    val currentVersionId: TransactionVersionId,
)

interface LedgerCurrentStateReadPort {
    fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow>

    fun findManualExpenseByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualExpenseCommitRecord?

    fun findManualExpenseByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedExpenseReceipt,
    ): ManualExpenseCommitRecord?

    /** P7-02.A S-1: income request lookup for the snapshot-aware commit-status resolver. */
    fun findManualIncomeByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualIncomeCommitRecord?

    /** P7-02.A S-1: income receipt lookup for the snapshot-aware commit-status resolver. */
    fun findManualIncomeByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedIncomeReceipt,
    ): ManualIncomeCommitRecord?

    /** P7-02.B: transfer request lookup for the snapshot-aware commit-status resolver. */
    fun findManualTransferByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualTransferCommitRecord?

    /** P7-02.B: transfer receipt lookup for the snapshot-aware commit-status resolver. */
    fun findManualTransferByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedTransferReceipt,
    ): ManualTransferCommitRecord?

    /**
     * P7-02.C: lending request lookup for the snapshot-aware commit-status resolver. The default
     * `null` (no lending rows) keeps pre-P7-02 read-port fakes source-compatible; the real
     * adapter overrides it.
     */
    fun findManualLendingByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualLendingCommitRecord? = null

    /** P7-02.C: lending receipt lookup for the snapshot-aware commit-status resolver. */
    fun findManualLendingByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedLendingReceipt,
    ): ManualLendingCommitRecord? = null

    /**
     * P7-03.A (D-145): ledger-scoped current-version entry rows with the effective kind
     * (`COALESCE(canonical_kind, kind)`), both persisted times and the current note.
     *
     * There is deliberately no neutral default: returning an empty list would make an
     * unimplemented port indistinguishable from an empty ledger and render zeros or empty
     * months (R-Q06-4). The default fails loudly instead, and every consumer already maps a
     * read-port exception to its typed failure family
     * ([MonthlyActivityResult.Unavailable] / [TransactionDetailResult.Unavailable], or the
     * caller's typed surface for [QueryLedgerEntryRows]); the real data adapter overrides it.
     */
    fun loadLedgerEntryRows(ledgerId: LedgerId): List<LedgerEntryRow> =
        throw UnsupportedOperationException(
            "loadLedgerEntryRows is not implemented by this read port; a missing P7-03 ledger-entry " +
                "read must surface as a typed read failure, never as an empty ledger (R-Q06-4)",
        )

    /**
     * P7-03.A: reverse creation lineage — the import confirmation that created the
     * transaction (`operation_class = 'creation'`), or `null` (Appendix A).
     *
     * G6: no neutral default, for the same reason as [loadLedgerEntryRows]. Returning `null`
     * from an unimplemented port would claim "this transaction has no import confirmation" and
     * render a definitive 来源未标注 instead of a typed read failure; [QueryTransactionDetail]
     * maps the thrown exception to [TransactionDetailResult.Unavailable]. A read port that
     * genuinely has no such row returns `null` explicitly.
     */
    fun findImportCreationConfirmation(
        ledgerId: LedgerId,
        transactionId: TransactionId,
    ): ImportCreationConfirmationRow? =
        throw UnsupportedOperationException(
            "findImportCreationConfirmation is not implemented by this read port; a missing lineage read " +
                "must surface as a typed read failure, never as an unmarked creation entry (R-Q06-4)",
        )

    /**
     * P7-03.A: reverse creation lineage — the manual four-chain receipt for the
     * transaction, or `null` (Appendix A). G6: no neutral default (see
     * [findImportCreationConfirmation]).
     */
    fun findManualCreationReceipt(
        ledgerId: LedgerId,
        transactionId: TransactionId,
    ): ManualCreationReceiptRow? =
        throw UnsupportedOperationException(
            "findManualCreationReceipt is not implemented by this read port; a missing lineage read " +
                "must surface as a typed read failure, never as an unmarked creation entry (R-Q06-4)",
        )

    /**
     * P7-03.A: read-only reconciliation leg projection for the transaction's current
     * version (R-Q07-4 / spec section 3.2.1). Never writes reconciliation state.
     *
     * G6: no neutral default. An empty list from an unimplemented port would render every leg
     * as 无对账资格 — a definitive-looking verdict about reconciliation state that was never
     * read; [QueryTransactionDetail] maps the thrown exception to
     * [TransactionDetailResult.Unavailable].
     */
    fun loadTransactionReconciliationLegs(
        ledgerId: LedgerId,
        transactionId: TransactionId,
    ): List<TransactionReconciliationLegRow> =
        throw UnsupportedOperationException(
            "loadTransactionReconciliationLegs is not implemented by this read port; a missing reconciliation " +
                "projection must surface as a typed read failure, never as an all-ineligible verdict (R-Q06-4)",
        )
}
