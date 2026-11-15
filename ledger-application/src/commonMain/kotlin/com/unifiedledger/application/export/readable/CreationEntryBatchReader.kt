package com.unifiedledger.application.export.readable

import com.unifiedledger.application.CreationEntry
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionId

/**
 * P8-05 批 a batched creation-lineage read behind the export's 来源 column (D-331 spec D-E6).
 * One call per export covering EVERY effective transaction of the ledger, so the 50k-row walk
 * never degrades into the per-transaction N+1 (spec section 7 R7). Precedence is the P7-03.A
 * one (`QueryRecycleBin.kt:143-147`): an import-confirmation hit beats a manual receipt hit,
 * everything else is unmarked — the reader returns raw hits, the use case resolves the label.
 */
fun interface CreationEntryBatchReader {
    fun loadCreationEntries(ledgerId: LedgerId): CreationEntryBatch
}

/** The raw batched lineage hit set; empty sets mean "no receipt surface hit" (never guessed). */
data class CreationEntryBatch(
    /** Transactions with an `import_confirmation` `operation_class='creation'` row. */
    val importTransactionIds: Set<TransactionId>,
    /** Transactions with a receipt row in one of the manual four chains. */
    val manualTransactionIds: Set<TransactionId>,
) {
    fun entryFor(transactionId: TransactionId): CreationEntry =
        when {
            transactionId in importTransactionIds -> CreationEntry.IMPORT_CREATED
            transactionId in manualTransactionIds -> CreationEntry.MANUAL_CREATED
            else -> CreationEntry.UNMARKED
        }
}
