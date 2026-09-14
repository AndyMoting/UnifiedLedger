package com.unifiedledger.application

import com.unifiedledger.domain.LedgerId

/**
 * P7-03.C flow-list read (spec sections 4.1/4.2.5): the ledger-scoped current-version entry
 * rows in the frozen display order. The data query stays unsorted (zero DDL discipline, no
 * ORDER BY dependency); the ordering is applied here by the pure [sortLedgerEntryRowsForDisplay]
 * function. Read-port failures propagate to the caller, which maps them to typed failures
 * (R-Q06-4) — never to an empty list.
 */
class QueryLedgerEntryRows(
    private val readPort: LedgerCurrentStateReadPort,
    private val ledgerId: LedgerId,
) {
    fun query(): List<LedgerEntryRow> = sortLedgerEntryRowsForDisplay(readPort.loadLedgerEntryRows(ledgerId))
}

/**
 * P703SPEC-03 frozen flow-list ordering: `statistics_at` DESC (the R-Q06-2 bucket key), ties
 * broken by `occurred_at` DESC, then by `transaction_id` ASC (UUIDv7 gives a deterministic
 * total order). `sortedWith` is stable and the sort is row-level only, so the postings inside
 * a row keep their stored `posting_index` order (spec 4.2.5).
 */
fun sortLedgerEntryRowsForDisplay(rows: List<LedgerEntryRow>): List<LedgerEntryRow> =
    rows.sortedWith(
        compareByDescending<LedgerEntryRow> { it.statisticsAt }
            .thenByDescending { it.occurredAt }
            .thenBy { it.transactionId.value },
    )
