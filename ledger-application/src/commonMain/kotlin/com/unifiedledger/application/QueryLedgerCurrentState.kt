package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Posting

/**
 * P5-03 current-state projection (D-119 section 4; plan section 3.2.3-4).
 *
 * Only current-version transactions are projected. Every account of every posting is
 * validated against the injected catalog (same ledger, same currency as the posting) and
 * balances are grouped per account and currency without any cross-currency summation.
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

        val postings = rows.flatMap { it.postings }
        for (posting in postings) {
            if (!isCatalogConsistent(posting)) {
                return LedgerCurrentStateResult.InvalidState
            }
        }

        val balances = buildBalances(postings) ?: return LedgerCurrentStateResult.InvalidState
        return LedgerCurrentStateResult.Success(
            LedgerCurrentState(
                ledgerId = ledgerId,
                transactions = rows,
                balances = balances,
            ),
        )
    }

    private fun isCatalogConsistent(posting: Posting): Boolean {
        val account = accountsById[posting.accountId] ?: return false
        if (account.ledgerId != ledgerId) return false
        if (account.currency != posting.amount.currency) return false
        return true
    }

    private fun buildBalances(postings: List<Posting>): List<AccountCurrencyBalance>? {
        val totals = mutableMapOf<AccountCurrencyKey, Long>()
        for (posting in postings) {
            val key = AccountCurrencyKey(posting.accountId, posting.amount.currency)
            val previous = totals[key] ?: 0L
            val next = checkedAdd(previous, posting.amount.minorUnits) ?: return null
            totals[key] = next
        }
        return totals
            .map { (key, ledgerSigned) ->
                val account = checkNotNull(accountsById[key.accountId])
                val displayMinorUnits = displayMinorUnits(account.kind, ledgerSigned) ?: return null
                AccountCurrencyBalance(
                    accountId = key.accountId,
                    currency = key.currency,
                    ledgerSignedMinorUnits = ledgerSigned,
                    displayMinorUnits = displayMinorUnits,
                )
            }.sortedWith(compareBy({ it.accountId.value }, { it.currency.code }, { it.currency.precision }))
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

    private data class AccountCurrencyKey(
        val accountId: AccountId,
        val currency: CurrencyUnit,
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
