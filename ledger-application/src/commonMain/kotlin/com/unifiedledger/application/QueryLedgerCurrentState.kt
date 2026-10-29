package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId

/**
 * P5-03 current-state projection (D-119 section 4; plan section 3.2.3-4).
 *
 * Only current-version transactions are projected. Every account of every posting is
 * validated against the injected catalog (same ledger, same currency as the posting);
 * balances arrive as one exact per-account/currency SUM from the read port (D-266) and are
 * never summed across currencies.
 * [AccountCurrencyBalance.ledgerSignedMinorUnits] is passed through unchanged from the data
 * layer; [AccountCurrencyBalance.displayMinorUnits] is derived from the account's
 * normal-balance direction per `docs/ACCOUNTING_RULES.md`: ASSET/EXPENSE display the ledger
 * sign, LIABILITY/INCOME/EQUITY display the negated ledger sign. Database failures surface
 * as [LedgerCurrentStateResult.Unavailable], never as a domain rejection.
 */
data class AccountCurrencyBalance(
    val accountId: AccountId,
    val currency: CurrencyUnit,
    val ledgerSignedMinorUnits: Long,
    val displayMinorUnits: Long,
)

data class LedgerCurrentState(
    val ledgerId: LedgerId,
    val transactions: List<CurrentVersionRow>,
    val balances: List<AccountCurrencyBalance>,
    /**
     * B1 (spec 7.3/D-027): current authoritative display names for the accounts referenced by
     * this state, so the overview renders names instead of bare ids. An empty value (a catalog
     * row without a name) falls back to the stable id in the UI. Derived from the same injected
     * catalog as balances, so reloading the catalog after a rename yields the new names while
     * ids and amounts are unchanged.
     */
    val accountNames: Map<AccountId, String> = emptyMap(),
)

sealed interface LedgerCurrentStateResult {
    data class Success(
        val state: LedgerCurrentState,
    ) : LedgerCurrentStateResult

    /** Catalog/posting consistency validation failed (defensive; unreachable with the fixed fixture). */
    data object InvalidState : LedgerCurrentStateResult

    /** Read-port exception (database unavailable). */
    data object Unavailable : LedgerCurrentStateResult
}

class QueryLedgerCurrentState(
    private val readPort: LedgerCurrentStateReadPort,
    private val ledgerId: LedgerId,
    private val catalog: LedgerCatalog,
) {
    private val accountsById: Map<AccountId, Account> = catalog.accounts.associateBy { it.id }

    fun query(): LedgerCurrentStateResult {
        val rows =
            try {
                readPort.loadCurrentRows(ledgerId)
            } catch (failure: Exception) {
                return LedgerCurrentStateResult.Unavailable
            }

        // DEF-A (D-266): balances are a SQL aggregate, not an in-memory sum over every posting.
        val sums =
            try {
                readPort.accountBalanceSums(ledgerId)
            } catch (failure: Exception) {
                return LedgerCurrentStateResult.Unavailable
            }

        val balances = buildBalances(sums) ?: return LedgerCurrentStateResult.InvalidState
        return LedgerCurrentStateResult.Success(
            LedgerCurrentState(
                ledgerId = ledgerId,
                transactions = rows,
                balances = balances,
                accountNames =
                    buildMap {
                        accountsById.values
                            .filter { it.name.isNotEmpty() }
                            .forEach { put(it.id, it.name) }
                    },
            ),
        )
    }

    /**
     * DEF-A (D-266): validates one aggregate row against the injected catalog, then projects the
     * display sign. The aggregate itself is an exact integer SUM in SQL; an unknown account, a
     * foreign-ledger account or a currency mismatch still fails the whole projection (R-Q06-4).
     */
    private fun buildBalances(
        sums: List<LedgerCurrentStateReadPort.AccountBalanceSum>,
    ): List<AccountCurrencyBalance>? {
        val balances = ArrayList<AccountCurrencyBalance>(sums.size)
        for (sum in sums) {
            val account = accountsById[sum.accountId] ?: return null
            if (account.ledgerId != ledgerId) return null
            if (account.currency != sum.currency) return null
            val displayMinorUnits = displayMinorUnits(account.kind, sum.ledgerSignedMinorUnits) ?: return null
            balances.add(
                AccountCurrencyBalance(
                    accountId = sum.accountId,
                    currency = sum.currency,
                    ledgerSignedMinorUnits = sum.ledgerSignedMinorUnits,
                    displayMinorUnits = displayMinorUnits,
                ),
            )
        }
        return balances.sortedWith(compareBy({ it.accountId.value }, { it.currency.code }, { it.currency.precision }))
    }

    private fun displayMinorUnits(
        kind: AccountKind,
        ledgerSignedMinorUnits: Long,
    ): Long? =
        when (kind) {
            AccountKind.ASSET,
            AccountKind.EXPENSE,
            -> ledgerSignedMinorUnits
            AccountKind.LIABILITY,
            AccountKind.INCOME,
            AccountKind.EQUITY,
            -> checkedNegate(ledgerSignedMinorUnits)
        }
}

private fun checkedNegate(value: Long): Long? = if (value == Long.MIN_VALUE) null else -value
