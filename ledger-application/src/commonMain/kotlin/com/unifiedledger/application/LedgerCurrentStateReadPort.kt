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
}
