package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.TransactionKind

/**
 * DEF-B (D-268): one transaction kind's exact count over the effective current-version set. The
 * kind is `tx.kind` (the pre-D-268 summary semantics), not the entry-row canonical kind.
 */
data class ActivityKindCount(
    val kind: TransactionKind,
    val count: Int,
)

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
        // DEF-B (D-268): the totals must cover the whole ledger. A bounded projection is refused
        // loudly instead of under-reporting; the Analysis tab uses [summarizeAggregate].
        check(currentState.transactionTotal == currentState.transactions.size) {
            "summarize() needs the complete current-version set; use summarizeAggregate() for a bounded projection"
        }
        val accountSums =
            currentState.transactions
                .flatMap { row -> row.postings }
                .groupBy { posting -> posting.accountId to posting.amount.currency }
                .map { (key, postings) ->
                    AccountSum(
                        accountId = key.first,
                        currency = key.second,
                        ledgerSignedMinorUnits =
                            postings.fold(0L) { total, posting ->
                                checkedAdd(total, posting.amount.minorUnits)
                                    ?: throw ArithmeticException("account sum overflow for ${key.second.code}")
                            },
                    )
                }
        val kindCounts =
            currentState.transactions
                .groupingBy { it.kind }
                .eachCount()
                .map { (kind, count) -> ActivityKindCount(kind, count) }
        return summarizeAggregate(accountSums, kindCounts, currentState.transactionTotal)
    }

    /**
     * DEF-B (D-268): the Analysis tab's projection over the exact aggregates the current state
     * carries — per-(account, currency) sums and per-kind counts read in SQL — so the tab never
     * reads or materialises the ledger's rows.
     */
    fun summarizeAggregate(currentState: LedgerCurrentState): LedgerActivitySummary =
        summarizeAggregate(
            accountSums =
                currentState.balances.map { balance ->
                    AccountSum(balance.accountId, balance.currency, balance.ledgerSignedMinorUnits)
                },
            kindCounts = currentState.activityKindCounts,
            totalTransactionCount = currentState.transactionTotal,
        )

    private fun summarizeAggregate(
        accountSums: List<AccountSum>,
        kindCounts: List<ActivityKindCount>,
        totalTransactionCount: Int,
    ): LedgerActivitySummary {
        val expenseTotals = mutableMapOf<CurrencyUnit, Long>()
        val incomeTotals = mutableMapOf<CurrencyUnit, Long>()
        for (sum in accountSums) {
            val account =
                accountsById[sum.accountId]
                    ?: throw IllegalStateException(
                        "Posting account ${sum.accountId.value} is not in the catalog",
                    )
            when (account.kind) {
                AccountKind.EXPENSE -> {
                    val total =
                        checkedAdd(expenseTotals[sum.currency] ?: 0L, sum.ledgerSignedMinorUnits)
                            ?: throw ArithmeticException("expense total overflow for ${sum.currency.code}")
                    expenseTotals[sum.currency] = total
                }
                AccountKind.INCOME -> {
                    val income =
                        checkedNegate(sum.ledgerSignedMinorUnits)
                            ?: throw ArithmeticException("income sum negation overflow for ${sum.currency.code}")
                    val total =
                        checkedAdd(incomeTotals[sum.currency] ?: 0L, income)
                            ?: throw ArithmeticException("income total overflow for ${sum.currency.code}")
                    incomeTotals[sum.currency] = total
                }
                AccountKind.ASSET,
                AccountKind.LIABILITY,
                AccountKind.EQUITY,
                -> Unit
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
            totalTransactionCount = totalTransactionCount,
            countByKind = kindCounts.associate { it.kind to it.count },
            totalsByCurrency = totalsByCurrency,
        )
    }

    private data class AccountSum(
        val accountId: AccountId,
        val currency: CurrencyUnit,
        val ledgerSignedMinorUnits: Long,
    )
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
