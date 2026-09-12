package com.unifiedledger.ui

import com.unifiedledger.application.CatalogConsumerSession
import com.unifiedledger.application.CatalogSnapshotView
import com.unifiedledger.application.ExecuteCatalogCommand
import com.unifiedledger.application.ExecuteManualExpenseSubmission
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.ManualExpenseOptionsProvider
import com.unifiedledger.application.ManualExpenseRequestIdSource
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.ParseManualExpenseOccurredAt
import com.unifiedledger.application.QueryLedgerCurrentState
import com.unifiedledger.application.ResolveManualExpenseCommitStatus
import com.unifiedledger.application.SummarizeLedgerActivity
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId

/**
 * P5-03 composition-root facade (spec section 4.7). Assembled by each platform composition
 * root and handed to the shared UI. The facade contains application-layer types only; the
 * UI never sees a driver, a database handle or SQL.
 *
 * P7-01.D (D-143) adds the catalog-management surface: [catalogSnapshot] is the current
 * authoritative projection (names replacing bare ids, including `catalogVersion`),
 * [executeCatalogCommand] is the nine-command entry point (it mints each command's request id
 * internally, so equivalent replay stays deterministic), and [refreshCatalog] reloads the
 * shared catalog session so options/reads/summaries continue from the same authoritative
 * version without a restart.
 *
 * When a [catalogSession] is injected (spec 6.2), [optionsProvider], [queryCurrentState] and
 * [summarizeActivity] are read through it, so `refreshCatalog()` reaches every consumer of the
 * authoritative catalog and not just the management projection. The `base*` parameters remain
 * as the construction-time fallback for pre-P7-01 composition sites (startup tests) that have
 * no session; product roots always inject the session.
 */
class P503LedgerFacade(
    val ledgerId: LedgerId,
    val currency: CurrencyUnit,
    val catalog: LedgerCatalog,
    val parseAmount: ParseManualExpenseAmount,
    // D-138: lenient occurred-at parser, injected alongside parseAmount (spec 3). The
    // default exists only so the Android startup test keeps constructing the facade
    // unchanged; product roots always inject the real instance.
    val parseOccurredAt: ParseManualExpenseOccurredAt = ParseManualExpenseOccurredAt(),
    baseOptionsProvider: ManualExpenseOptionsProvider,
    baseQueryCurrentState: QueryLedgerCurrentState,
    val resolveCommitStatus: ResolveManualExpenseCommitStatus,
    val submitExpense: ExecuteManualExpenseSubmission,
    val requestIdSource: ManualExpenseRequestIdSource,
    val ledgerClock: LedgerClock,
    baseSummarizeActivity: SummarizeLedgerActivity,
    // P7-01.D catalog management surface; null/empty defaults keep legacy constructions valid.
    val catalogSnapshot: () -> CatalogSnapshotView? = { null },
    val executeCatalogCommand: ExecuteCatalogCommand? = null,
    val refreshCatalog: () -> Unit = {},
    catalogSession: CatalogConsumerSession? = null,
) {
    private val session = catalogSession
    private val fallbackOptionsProvider = baseOptionsProvider
    private val fallbackQueryCurrentState = baseQueryCurrentState
    private val fallbackSummarizeActivity = baseSummarizeActivity

    /** Current authoritative options provider; follows [refreshCatalog] when a session is injected. */
    val optionsProvider: ManualExpenseOptionsProvider
        get() = session?.optionsProvider ?: fallbackOptionsProvider

    /** Current authoritative read model; follows [refreshCatalog] when a session is injected. */
    val queryCurrentState: QueryLedgerCurrentState
        get() = session?.queryCurrentState ?: fallbackQueryCurrentState

    /** Current authoritative summary model; follows [refreshCatalog] when a session is injected. */
    val summarizeActivity: SummarizeLedgerActivity
        get() = session?.summarizeActivity ?: fallbackSummarizeActivity
}
