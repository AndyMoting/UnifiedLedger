package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.TransactionKind

/**
 * One currency's activity totals (D-122). Amounts are ledger-exact minor units under the
 * normal-balance direction of the contributing accounts: expenses keep the ledger sign of
 * EXPENSE-account postings, income keeps the negated sign of INCOME-account postings.
 */
data class CurrencyActivityTotal(
    val currency: CurrencyUnit,
    val expenseMinorUnits: Long,
    val incomeMinorUnits: Long,
)

/**
 * P5-04.1 activity summary over the authoritative current state (D-122). Pure projection:
 * every current-version transaction counts once per transaction kind, and posting amounts
 * are accumulated per currency by the catalog account kind. A negative total is a valid
 * display value (for example from refund events) and must not be clamped by the UI.
 */
data class LedgerActivitySummary(
    val totalTransactionCount: Int,
    val countByKind: Map<TransactionKind, Int>,
    val totalsByCurrency: List<CurrencyActivityTotal>,
)

/**
 * Pure derivation behind the analysis tab (D-122). Only current-version transactions are
 * summarized. EXPENSE-account postings contribute the ledger-signed amount to that
 * currency's expense total, INCOME-account postings contribute the negated amount to the
 * income total (same normal-balance rule as `QueryLedgerCurrentState`), and every other
 * account kind contributes no amount. Accumulation and negation are checked and fail
 * closed with [ArithmeticException]; a posting account missing from the injected catalog
 * is a programming error and fails with [IllegalStateException] instead of a silent
 * fallback.
 */
class SummarizeLedgerActivity(
    catalog: LedgerCatalog,
) {
    private val accountsById: Map<AccountId, Account> = catalog.accounts.associateBy { it.id }

    fun summarize(currentState: LedgerCurrentState): LedgerActivitySummary {
        val countsByKind = mutableMapOf<TransactionKind, Int>()
        val expenseTotals = mutableMapOf<CurrencyUnit, Long>()
        val incomeTotals = mutableMapOf<CurrencyUnit, Long>()
        for (row in currentState.transactions) {
            countsByKind[row.kind] = (countsByKind[row.kind] ?: 0) + 1
            for (posting in row.postings) {
                val account =
                    accountsById[posting.accountId]
                        ?: throw IllegalStateException(
                            "Posting account ${posting.accountId.value} is not in the catalog",
                        )
                val currency = posting.amount.currency
                when (account.kind) {
                    AccountKind.EXPENSE -> {
                        val total =
                            checkedAdd(expenseTotals[currency] ?: 0L, posting.amount.minorUnits)
                                ?: throw ArithmeticException("expense total overflow for ${currency.code}")
                        expenseTotals[currency] = total
                    }
                    AccountKind.INCOME -> {
                        val income =
                            checkedNegate(posting.amount.minorUnits)
                                ?: throw ArithmeticException("income posting negation overflow for ${currency.code}")
                        val total =
                            checkedAdd(incomeTotals[currency] ?: 0L, income)
                                ?: throw ArithmeticException("income total overflow for ${currency.code}")
                        incomeTotals[currency] = total
                    }
                    AccountKind.ASSET,
                    AccountKind.LIABILITY,
                    AccountKind.EQUITY,
                    -> Unit
                }
            }
        }
        val totalsByCurrency =
            (expenseTotals.keys + incomeTotals.keys)
                .distinct()
                .sortedWith(compareBy({ it.code }, { it.precision }))
                .map { currency ->
                    CurrencyActivityTotal(
                        currency = currency,
                        expenseMinorUnits = expenseTotals[currency] ?: 0L,
                        incomeMinorUnits = incomeTotals[currency] ?: 0L,
                    )
                }
        return LedgerActivitySummary(
            totalTransactionCount = currentState.transactions.size,
            countByKind = countsByKind,
            totalsByCurrency = totalsByCurrency,
        )
    }
}

private fun checkedAdd(
    left: Long,
    right: Long,
): Long? {
    if (right > 0 && left > Long.MAX_VALUE - right) return null
    if (right < 0 && left < Long.MIN_VALUE - right) return null
    return left + right
}

private fun checkedNegate(value: Long): Long? = if (value == Long.MIN_VALUE) null else -value
