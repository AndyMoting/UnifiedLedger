package com.unifiedledger.data

import com.unifiedledger.application.CreationEntryBatch
import com.unifiedledger.application.CreationEntryBatchReader
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionId

/**
 * P8-05 批 a (D-331 spec D-E6): the SQL backing of [CreationEntryBatchReader] over the additive
 * named query `readableExportCreationEntries` (`Ledger.sq`, the UNION ALL of
 * `import_confirmation` (`operation_class='creation'`) and the four manual receipt chains — the
 * `manualCreationReceiptByTransaction` shape lifted to one ledger-wide read). SELECT-only; the
 * query adds no DDL and no writer. `entry_source` literals map to the two hit sets; any future
 * literal fails loudly instead of silently dropping a lineage hit.
 */
class SqlDelightCreationEntryBatchReader(
    private val database: LedgerDatabase,
) : CreationEntryBatchReader {
    override fun loadCreationEntries(ledgerId: LedgerId): CreationEntryBatch {
        val rows = database.ledgerQueries.readableExportCreationEntries(ledgerId.value).executeAsList()
        val import = mutableSetOf<TransactionId>()
        val manual = mutableSetOf<TransactionId>()
        for (row in rows) {
            val transactionId = TransactionId(row.transaction_id)
            when (row.entry_source) {
                SOURCE_IMPORT -> import.add(transactionId)
                SOURCE_EXPENSE, SOURCE_INCOME, SOURCE_TRANSFER, SOURCE_LENDING -> manual.add(transactionId)
                else -> throw IllegalStateException(
                    "readableExportCreationEntries returned an unknown entry_source: ${row.entry_source}",
                )
            }
        }
        return CreationEntryBatch(importTransactionIds = import, manualTransactionIds = manual)
    }
}

private const val SOURCE_IMPORT = "import"
private const val SOURCE_EXPENSE = "expense"
private const val SOURCE_INCOME = "income"
private const val SOURCE_TRANSFER = "transfer"
private const val SOURCE_LENDING = "lending"
