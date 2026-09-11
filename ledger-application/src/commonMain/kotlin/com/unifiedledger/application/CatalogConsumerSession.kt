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
 */
class CatalogConsumerSession(
    private val reader: CatalogAuthorityReader,
    val ledgerId: LedgerId,
    initialAuthority: CatalogAuthority,
    private val readPort: LedgerCurrentStateReadPort,
) {
    var authority: CatalogAuthority = initialAuthority
        private set

    var optionsProvider: ManualExpenseOptionsProvider = QueryAuthoritativeManualExpenseOptions(reader, ledgerId)
        private set

    var queryCurrentState: QueryLedgerCurrentState = QueryLedgerCurrentState(readPort, ledgerId, authority.catalog)
        private set

    var summarizeActivity: SummarizeLedgerActivity = SummarizeLedgerActivity(authority.catalog)
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
        queryCurrentState = QueryLedgerCurrentState(readPort, ledgerId, reloaded.catalog)
        summarizeActivity = SummarizeLedgerActivity(reloaded.catalog)
        return reloaded
    }
}
