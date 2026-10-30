package com.unifiedledger.application

import com.unifiedledger.domain.LedgerId

/** DEF-B (D-268): the fixed HOME flow page size (spec §4.1, ruling R-268-5). */
const val HOME_FLOW_PAGE_SIZE: Int = 200

/** DEF-B (D-268): the bounded fallback list size for a not-loaded monthly cycle (spec §4.2). */
const val HOME_FALLBACK_ROW_LIMIT: Int = 200

sealed interface LedgerFlowPageResult {
    data class Success(
        val page: LedgerCurrentStateReadPort.LedgerFlowPage,
    ) : LedgerFlowPageResult

    /** Read failure (R-Q06-4): never an empty page and never a silently shortened one. */
    data object Unavailable : LedgerFlowPageResult
}

sealed interface LedgerFlowTotalResult {
    data class Success(
        val total: Int,
    ) : LedgerFlowTotalResult

    data object Unavailable : LedgerFlowTotalResult
}

/**
 * DEF-B (D-268, spec §4.1/§4.2): the HOME flow list's page source.
 *
 * One page per request in the frozen PAGE-KEY order `(statistics_at_epoch_nanos DESC,
 * transaction_id DESC)`; the caller re-sorts the accumulated pages with
 * [sortLedgerEntryRowsForDisplay] so P703SPEC-03's display order stays frozen (ruling R-268-1).
 * A read failure is [LedgerFlowPageResult.Unavailable] — never an empty page, and never a page
 * that silently skipped rows whose statistics projection is missing (the adapter's fail-loud
 * probe, spec §4.1).
 */
class QueryLedgerFlowPage(
    private val readPort: LedgerCurrentStateReadPort,
    private val ledgerId: LedgerId,
) {
    fun firstPage(pageSize: Int = HOME_FLOW_PAGE_SIZE): LedgerFlowPageResult = page(null, pageSize)

    fun pageAfter(
        cursor: LedgerCurrentStateReadPort.LedgerFlowCursor,
        pageSize: Int = HOME_FLOW_PAGE_SIZE,
    ): LedgerFlowPageResult = page(cursor, pageSize)

    /** The exact total ("共 N 笔"), read independently of the loaded pages. */
    fun total(): LedgerFlowTotalResult =
        try {
            LedgerFlowTotalResult.Success(readPort.effectiveTransactionCount(ledgerId))
        } catch (failure: Exception) {
            LedgerFlowTotalResult.Unavailable
        }

    private fun page(
        cursor: LedgerCurrentStateReadPort.LedgerFlowCursor?,
        pageSize: Int,
    ): LedgerFlowPageResult {
        require(pageSize > 0) { "page size must be positive" }
        return try {
            LedgerFlowPageResult.Success(readPort.loadLedgerFlowPage(ledgerId, cursor, pageSize))
        } catch (failure: Exception) {
            LedgerFlowPageResult.Unavailable
        }
    }
}
