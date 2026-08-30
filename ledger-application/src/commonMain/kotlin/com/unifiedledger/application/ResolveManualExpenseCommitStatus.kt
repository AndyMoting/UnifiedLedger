package com.unifiedledger.application

import com.unifiedledger.domain.LedgerId

/**
 * P5-03 snapshot-aware unknown-commit resolution (D-119 sections 3.5/4; plan section 3.2.5).
 *
 * The resolution is fixed to four states and compares the persisted snapshot field by field
 * (ledger, amount minor units and currency code+precision, category, payment account,
 * occurredAt and the empty note). Only [ManualExpenseCommitResolution.MatchingReceipt] may
 * produce a recovered success; a snapshot conflict maps to a stable request identity
 * conflict; absent and unavailable both remain unknown. Database exceptions surface as
 * [ManualExpenseCommitResolution.Unavailable], never as a domain rejection.
 */
sealed interface ManualExpenseCommitResolution {
    data class MatchingReceipt(
        val receipt: ConfirmedExpenseReceipt,
    ) : ManualExpenseCommitResolution

    data object SnapshotConflict : ManualExpenseCommitResolution

    data object Absent : ManualExpenseCommitResolution

    data object Unavailable : ManualExpenseCommitResolution
}

class ResolveManualExpenseCommitStatus(
    private val readPort: LedgerCurrentStateReadPort,
) {
    fun resolve(
        ledgerId: LedgerId,
        requestId: RequestId,
        attempted: ManualExpenseRequestSnapshot,
    ): ManualExpenseCommitResolution {
        val record =
            try {
                readPort.findManualExpenseByRequest(ledgerId, requestId)
            } catch (failure: Exception) {
                return ManualExpenseCommitResolution.Unavailable
            }
        return when {
            record == null -> ManualExpenseCommitResolution.Absent
            record.snapshot == attempted ->
                ManualExpenseCommitResolution.MatchingReceipt(record.receipt)
            else -> ManualExpenseCommitResolution.SnapshotConflict
        }
    }
}
