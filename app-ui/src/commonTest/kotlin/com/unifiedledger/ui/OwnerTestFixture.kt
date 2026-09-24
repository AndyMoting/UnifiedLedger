package com.unifiedledger.ui

import com.unifiedledger.application.CommitOnceInvocationTracker
import com.unifiedledger.application.ConfirmedExpenseReceipt
import com.unifiedledger.application.ConfirmedExpenseTransactionFactory
import com.unifiedledger.application.ConfirmedIncomeReceipt
import com.unifiedledger.application.ConfirmedManualExpenseCommitPort
import com.unifiedledger.application.ConfirmedManualExpenseIdSource
import com.unifiedledger.application.ConfirmedManualExpenseResult
import com.unifiedledger.application.ConfirmedTransferReceipt
import com.unifiedledger.application.CurrentVersionRow
import com.unifiedledger.application.ExecuteConfirmedManualExpense
import com.unifiedledger.application.ExecuteManualExpenseSave
import com.unifiedledger.application.ExecuteManualExpenseSubmission
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.LedgerCurrentStateReadPort
import com.unifiedledger.application.ManualExpenseCommitRecord
import com.unifiedledger.application.ManualExpenseRequestIdSource
import com.unifiedledger.application.ManualIncomeCommitRecord
import com.unifiedledger.application.ManualTransferCommitRecord
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.QueryLedgerCurrentState
import com.unifiedledger.application.QueryManualExpenseOptions
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.ResolveManualExpenseCommitStatus
import com.unifiedledger.application.SummarizeLedgerActivity
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import kotlin.time.Clock

/**
 * P7-06 06.1 (D-176): a minimal but valid [P503LedgerFacade] for the owner tests. The owner only
 * stores and projects the facade and never calls its methods, so the collaborator stubs below
 * never run (the `AndroidStartupControllerTest` fixture precedent, shared here for common tests).
 */
internal fun minimalP503LedgerFacade(): P503LedgerFacade {
    val ledgerId = LedgerId("ledger-local-test")
    val currency = CurrencyUnit("CNY", 2)
    val catalog =
        (LedgerCatalog.create(accounts = emptyList(), categories = emptyList()) as DomainResult.Success).value
    val readPort =
        object : LedgerCurrentStateReadPort {
            override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> = emptyList()

            override fun findManualExpenseByRequest(
                ledgerId: LedgerId,
                requestId: RequestId,
            ): ManualExpenseCommitRecord? = null

            override fun findManualExpenseByReceipt(
                ledgerId: LedgerId,
                receipt: ConfirmedExpenseReceipt,
            ): ManualExpenseCommitRecord? = null

            override fun findManualIncomeByRequest(
                ledgerId: LedgerId,
                requestId: RequestId,
            ): ManualIncomeCommitRecord? = null

            override fun findManualIncomeByReceipt(
                ledgerId: LedgerId,
                receipt: ConfirmedIncomeReceipt,
            ): ManualIncomeCommitRecord? = null

            override fun findManualTransferByRequest(
                ledgerId: LedgerId,
                requestId: RequestId,
            ): ManualTransferCommitRecord? = null

            override fun findManualTransferByReceipt(
                ledgerId: LedgerId,
                receipt: ConfirmedTransferReceipt,
            ): ManualTransferCommitRecord? = null
        }
    val resolver = ResolveManualExpenseCommitStatus(readPort)
    val commitPort =
        ConfirmedManualExpenseCommitPort { _, _, _ ->
            ConfirmedManualExpenseResult.Rejected(DomainViolation.InvalidOrdinaryExpense)
        }
    val tracker = CommitOnceInvocationTracker(commitPort)
    val idSource = ConfirmedManualExpenseIdSource { error("commit id source must not be used in owner tests") }
    val transactionFactory =
        ConfirmedExpenseTransactionFactory { _, _ ->
            error("transaction factory must not be used in owner tests")
        }
    val executeConfirmed = ExecuteConfirmedManualExpense(tracker, idSource, transactionFactory)
    val executeSave = ExecuteManualExpenseSave(executeConfirmed)
    val submission = ExecuteManualExpenseSubmission(executeSave, tracker, resolver)

    return P503LedgerFacade(
        ledgerId = ledgerId,
        currency = currency,
        catalog = catalog,
        parseAmount = ParseManualExpenseAmount(),
        baseOptionsProvider = QueryManualExpenseOptions(ledgerId, catalog),
        baseQueryCurrentState = QueryLedgerCurrentState(readPort, ledgerId, catalog),
        resolveCommitStatus = resolver,
        submitExpense = submission,
        requestIdSource = ManualExpenseRequestIdSource { RequestId("request-owner-test") },
        ledgerClock = LedgerClock { Clock.System.now() },
        baseSummarizeActivity = SummarizeLedgerActivity(catalog),
    )
}
