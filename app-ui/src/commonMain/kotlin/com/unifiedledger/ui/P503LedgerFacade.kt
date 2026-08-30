package com.unifiedledger.ui

import com.unifiedledger.application.ExecuteManualExpenseSubmission
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.ManualExpenseOptionsProvider
import com.unifiedledger.application.ManualExpenseRequestIdSource
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.QueryLedgerCurrentState
import com.unifiedledger.application.ResolveManualExpenseCommitStatus
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId

/**
 * P5-03 composition-root facade (spec section 4.7). Assembled by each platform composition
 * root and handed to the shared UI. The facade contains application-layer types only; the
 * UI never sees a driver, a database handle or SQL.
 */
class P503LedgerFacade(
    val ledgerId: LedgerId,
    val currency: CurrencyUnit,
    val catalog: LedgerCatalog,
    val parseAmount: ParseManualExpenseAmount,
    val optionsProvider: ManualExpenseOptionsProvider,
    val queryCurrentState: QueryLedgerCurrentState,
    val resolveCommitStatus: ResolveManualExpenseCommitStatus,
    val submitExpense: ExecuteManualExpenseSubmission,
    val requestIdSource: ManualExpenseRequestIdSource,
    val ledgerClock: LedgerClock,
)
