package com.unifiedledger.application

import com.unifiedledger.domain.LedgerId

/**
 * Fail-closed signal raised by a composition root when the authoritative catalog cannot be
 * bootstrapped or loaded (an unresolved existing reference, or an unloadable catalog). Hosts
 * map it to their existing startup-error surface (Retry/Exit) and never fall back to a static
 * catalog.
 */
class CatalogBootstrapFailedException(
    ledgerId: LedgerId,
    cause: Throwable? = null,
) : RuntimeException(
        "authoritative catalog bootstrap failed closed for ledger ${ledgerId.value}",
        cause,
    )

/**
 * Host-layer authoritative catalog session (P7-01.C spec section 6.2, D-143).
 *
 * Options, writes, reads and summaries must all come from one authoritative catalog version.
 * This session holds the freshly loaded [CatalogAuthority] and the read models derived from it,
 * and exposes [refresh] as the no-restart entry a host (P7-01.D UI) calls after a successful
 * management command: refresh reloads the current authority from the [CatalogAuthorityReader]
 * and rebuilds [optionsProvider] / [queryCurrentState] / [summarizeActivity] against the new
 * catalog, so the very same version serves every consumer again.
 *
 * The write path is not held here: it re-reads the authoritative catalog inside its own write
 * transaction through [CatalogAdmissionReader] (V-2) and therefore never relies on a preview of
 * this session.
 *
 * P7-03.C/D (D-145) adds the ledger-view read models on the same discipline: [queryLedgerEntryRows]
 * (flow-list rows in the frozen display order), [queryTransactionDetail] (read-only detail) and
 * [queryMonthlyActivity] (unified monthly projection, built only when the host injects the
 * reporting [clock] — R-Q06-2 本月 resolution). All three are rebuilt from the reloaded catalog
 * by [refresh], so renames/deactivations show current names without a restart.
 */
class CatalogConsumerSession(
    private val reader: CatalogAuthorityReader,
    val ledgerId: LedgerId,
    initialAuthority: CatalogAuthority,
    private val readPort: LedgerCurrentStateReadPort,
    // P7-02.C: the counterparty directory is a different persistence surface from the catalog, so
    // the lending options provider receives it explicitly (null keeps pre-C constructions valid).
    private val counterpartyReader: CounterpartyDirectoryReader? = null,
    // P7-03.C/D: the reporting clock for the unified monthly projection (R-Q06-2); null keeps
    // pre-P7-03 constructions valid (the monthly surface stays absent).
    private val clock: LedgerClock? = null,
) {
    var authority: CatalogAuthority = initialAuthority
        private set

    var optionsProvider: ManualExpenseOptionsProvider = QueryAuthoritativeManualExpenseOptions(reader, ledgerId)
        private set

    /** P7-02.A S-1: the authoritative income option projection on the same catalog version. */
    var incomeOptionsProvider: ManualIncomeOptionsProvider = QueryAuthoritativeManualIncomeOptions(reader, ledgerId)
        private set

    /** P7-02.B: the authoritative transfer option projection on the same catalog version. */
    var transferOptionsProvider: ManualTransferOptionsProvider = QueryAuthoritativeManualTransferOptions(reader, ledgerId)
        private set

    /** P7-02.C: the authoritative lending option projection on the same catalog version. */
    var lendingOptionsProvider: ManualLendingOptionsProvider = QueryAuthoritativeManualLendingOptions(reader, ledgerId, counterpartyReader)
        private set

    var queryCurrentState: QueryLedgerCurrentState = QueryLedgerCurrentState(readPort, ledgerId, authority.catalog)
        private set

    var summarizeActivity: SummarizeLedgerActivity = SummarizeLedgerActivity(authority.catalog)
        private set

    /** P7-03.C: display-ordered ledger entry rows for the HOME flow list (spec section 4.2.5). */
    var queryLedgerEntryRows: QueryLedgerEntryRows = QueryLedgerEntryRows(readPort, ledgerId)
        private set

    /** P7-03.C/D: the read-only transaction detail projection on the same catalog version. */
    var queryTransactionDetail: QueryTransactionDetail = QueryTransactionDetail(readPort, ledgerId, authority.catalog)
        private set

    /** P7-03.B/C: the unified monthly projection; `null` without an injected reporting clock. */
    var queryMonthlyActivity: QueryMonthlyActivity? = clock?.let { QueryMonthlyActivity(readPort, ledgerId, authority.catalog, it) }
        private set

    /**
     * Reloads the authoritative catalog from persistence and rebuilds every read model so they
     * share the reloaded version. Returns the reloaded authority (non-null after bootstrap; a
     * null reload is a programming error and throws instead of exposing a stale or empty view).
     */
    fun refresh(): CatalogAuthority {
        val reloaded =
            checkNotNull(reader.load(ledgerId)) {
                "authoritative catalog for ${ledgerId.value} must load after bootstrap"
            }
        authority = reloaded
        optionsProvider = QueryAuthoritativeManualExpenseOptions(reader, ledgerId)
        incomeOptionsProvider = QueryAuthoritativeManualIncomeOptions(reader, ledgerId)
        transferOptionsProvider = QueryAuthoritativeManualTransferOptions(reader, ledgerId)
        lendingOptionsProvider = QueryAuthoritativeManualLendingOptions(reader, ledgerId, counterpartyReader)
        queryCurrentState = QueryLedgerCurrentState(readPort, ledgerId, reloaded.catalog)
        summarizeActivity = SummarizeLedgerActivity(reloaded.catalog)
        queryTransactionDetail = QueryTransactionDetail(readPort, ledgerId, reloaded.catalog)
        queryMonthlyActivity = clock?.let { QueryMonthlyActivity(readPort, ledgerId, reloaded.catalog, it) }
        return reloaded
    }
}
