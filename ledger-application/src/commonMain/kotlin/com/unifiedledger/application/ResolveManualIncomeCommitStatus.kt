package com.unifiedledger.application

import com.unifiedledger.domain.LedgerId

/**
 * P7-02.A S-1 income analogue of [ResolveManualExpenseCommitStatus].
 *
 * The resolution is fixed to four states and compares the persisted snapshot field by field
 * (ledger, amount minor units and currency code+precision, category, receiving account,
 * occurredAt and the note). Only [ManualIncomeCommitResolution.MatchingReceipt] may produce a
 * recovered success; a snapshot conflict maps to a stable request identity conflict; absent and
 * unavailable both remain unknown. Database exceptions surface as
 * [ManualIncomeCommitResolution.Unavailable], never as a domain rejection.
 */
sealed interface ManualIncomeCommitResolution {
    data class MatchingReceipt(
        val receipt: ConfirmedIncomeReceipt,
    ) : ManualIncomeCommitResolution

    data object SnapshotConflict : ManualIncomeCommitResolution

    data object Absent : ManualIncomeCommitResolution

    data object Unavailable : ManualIncomeCommitResolution
}

class ResolveManualIncomeCommitStatus(
    private val readPort: LedgerCurrentStateReadPort,
) {
    fun resolve(
        ledgerId: LedgerId,
        requestId: RequestId,
        attempted: ManualIncomeRequestSnapshot,
    ): ManualIncomeCommitResolution {
        val record =
            try {
                readPort.findManualIncomeByRequest(ledgerId, requestId)
            } catch (failure: Exception) {
                return ManualIncomeCommitResolution.Unavailable
            }
        return when {
            record == null -> ManualIncomeCommitResolution.Absent
            record.snapshot == attempted ->
                ManualIncomeCommitResolution.MatchingReceipt(record.receipt)
            else -> ManualIncomeCommitResolution.SnapshotConflict
        }
    }
}
