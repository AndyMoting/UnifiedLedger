package com.unifiedledger.application

import com.unifiedledger.domain.LedgerId

/**
 * P7-05.B/C snapshot-aware unknown-commit resolution (spec section 4.2; D-156).
 *
 * After a commit whose result was lost, the caller re-reads the persisted request by its
 * identity and compares the complete snapshot column set: a hit returns the original receipt,
 * a mismatch is a stable request identity conflict, and an absent row stays unknown — the
 * caller never auto-retries and never swaps the request id (the P7-02 S-3 discipline). A
 * database failure surfaces as [Unavailable], never as a domain rejection.
 */
sealed interface TransactionCorrectionCommitResolution {
    data class MatchingReceipt(
        val receipt: TransactionCorrectionReceipt,
    ) : TransactionCorrectionCommitResolution

    data object SnapshotConflict : TransactionCorrectionCommitResolution

    data object Absent : TransactionCorrectionCommitResolution

    data object Unavailable : TransactionCorrectionCommitResolution
}

sealed interface TransactionVoidCommitResolution {
    data class MatchingReceipt(
        val receipt: TransactionVoidReceipt,
    ) : TransactionVoidCommitResolution

    data object SnapshotConflict : TransactionVoidCommitResolution

    data object Absent : TransactionVoidCommitResolution

    data object Unavailable : TransactionVoidCommitResolution
}

class ResolveTransactionCorrectionCommitStatus(
    private val readPort: LedgerCurrentStateReadPort,
) {
    fun resolve(
        ledgerId: LedgerId,
        requestId: RequestId,
        attempted: TransactionCorrectionRequestSnapshot,
    ): TransactionCorrectionCommitResolution {
        val record =
            try {
                readPort.findTransactionCorrectionByRequest(ledgerId, requestId)
            } catch (failure: Exception) {
                return TransactionCorrectionCommitResolution.Unavailable
            }
        return when {
            record == null -> TransactionCorrectionCommitResolution.Absent
            record.snapshot == attempted ->
                TransactionCorrectionCommitResolution.MatchingReceipt(record.receipt)
            else -> TransactionCorrectionCommitResolution.SnapshotConflict
        }
    }
}

class ResolveTransactionVoidCommitStatus(
    private val readPort: LedgerCurrentStateReadPort,
) {
    fun resolve(
        ledgerId: LedgerId,
        requestId: RequestId,
        attempted: TransactionVoidRequestSnapshot,
    ): TransactionVoidCommitResolution {
        val record =
            try {
                readPort.findTransactionVoidByRequest(ledgerId, requestId)
            } catch (failure: Exception) {
                return TransactionVoidCommitResolution.Unavailable
            }
        return when {
            record == null -> TransactionVoidCommitResolution.Absent
            record.snapshot == attempted ->
                TransactionVoidCommitResolution.MatchingReceipt(record.receipt)
            else -> TransactionVoidCommitResolution.SnapshotConflict
        }
    }
}
