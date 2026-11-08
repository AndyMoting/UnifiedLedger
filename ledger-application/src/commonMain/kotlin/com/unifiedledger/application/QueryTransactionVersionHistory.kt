package com.unifiedledger.application

import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersion

/**
 * P8-00 b3 (V-6; spec `docs/specs/2026-11-08-p8-00-visual-nav-baseline-design.md` section 2.4):
 * the read-only financial version chain of one transaction, projected as the domain
 * [TransactionVersion] rows (versionNumber/postingSetId/times/note) in ascending version-number
 * order. Display = version number, both persisted times (R-Q06-1) and the version note; the
 * per-version posting diff-set is deliberately out of scope (it needs a per-version posting read
 * and is registered as future work in the batch registration draft).
 *
 * Zero writes: the version chain is an audit presentation, never an edit affordance (the D-223
 * read-only-block precedent).
 */
class QueryTransactionVersionHistory(
    private val readPort: LedgerCurrentStateReadPort,
    private val ledgerId: LedgerId,
) {
    fun query(transactionId: TransactionId): TransactionVersionHistoryResult {
        // The same keyed single-row read the detail surface uses: it applies the
        // "current-version AND not voided" effective predicate, so a transaction outside this
        // ledger's effective set reports NotFound instead of leaking a cross-ledger or voided
        // version chain (the QueryTransactionDetail NotFound precedent).
        val row =
            try {
                readPort.loadLedgerEntryRow(ledgerId, transactionId)
            } catch (failure: Exception) {
                return TransactionVersionHistoryResult.Unavailable
            } ?: return TransactionVersionHistoryResult.NotFound

        val versions =
            try {
                readPort.loadTransactionVersions(ledgerId, transactionId)
            } catch (failure: Exception) {
                return TransactionVersionHistoryResult.Unavailable
            }
        if (versions.isEmpty()) {
            // An existing effective transaction always carries at least its creation version;
            // an empty chain is a persistence inconsistency, never an honest "no history".
            return TransactionVersionHistoryResult.InvalidState
        }
        return TransactionVersionHistoryResult.Success(
            TransactionVersionHistory(
                ledgerId = ledgerId,
                transactionId = row.transactionId,
                currentVersionId = row.currentVersionId,
                versions = versions,
            ),
        )
    }
}

data class TransactionVersionHistory(
    val ledgerId: LedgerId,
    val transactionId: TransactionId,
    val currentVersionId: com.unifiedledger.domain.TransactionVersionId,
    /** The full chain, ascending version-number order as the read port projects it. */
    val versions: List<TransactionVersion>,
)

sealed interface TransactionVersionHistoryResult {
    data class Success(
        val history: TransactionVersionHistory,
    ) : TransactionVersionHistoryResult

    /** The requested transaction is not in this ledger's effective set (the detail read's predicate). */
    data object NotFound : TransactionVersionHistoryResult

    /** Persistence inconsistency: an effective transaction without any version row. */
    data object InvalidState : TransactionVersionHistoryResult

    /** Read-port exception (database unavailable). */
    data object Unavailable : TransactionVersionHistoryResult
}
