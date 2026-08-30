package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class QueryLedgerCurrentStateTest {
    private val ledgerId = LedgerId("ledger-a")
    private val cny = CurrencyUnit("CNY", 2)
    private val usd = CurrencyUnit("USD", 2)
    private val occurredAt = Instant.parse("2026-01-15T00:30:00Z")

    @Test
    fun projectionReturnsExactlyTheCurrentRowsFromTheReadPort() {
        val assetId = AccountId("account-asset")
        val expenseId = AccountId("account-expense")
        val row =
            CurrentVersionRow(
                transactionId = TransactionId("tx-1"),
                currentVersionId = TransactionVersionId("version-1"),
                kind = TransactionKind.EXPENSE,
                occurredAt = occurredAt,
                postings =
                    listOf(
                        Posting(PostingId("posting-1"), assetId, Money.ofMinor(-3_580L, cny)),
                        Posting(PostingId("posting-2"), expenseId, Money.ofMinor(3_580L, cny)),
                    ),
            )
        val query = QueryLedgerCurrentState(CurrentStateFixedReadPort(listOf(row)), ledgerId, fiveKindCatalog(assetId, expenseId, liabilityId = null, incomeId = null, equityId = null))

        val result = assertIs<LedgerCurrentStateResult.Success>(query.query())

        assertEquals(listOf(row), result.state.transactions)
        val asset = result.state.balances.single { it.accountId == assetId }
        assertEquals(cny, asset.currency)
        assertEquals(-3_580L, asset.ledgerSignedMinorUnits)
        assertEquals(-3_580L, asset.displayMinorUnits)
        val expense = result.state.balances.single { it.accountId == expenseId }
        assertEquals(3_580L, expense.ledgerSignedMinorUnits)
        assertEquals(3_580L, expense.displayMinorUnits)
    }

    @Test
    fun allFiveAccountKindsDeriveTheNormalBalanceDisplaySign() {
        val assetId = AccountId("account-asset")
        val expenseId = AccountId("account-expense")
        val liabilityId = AccountId("account-liability")
        val incomeId = AccountId("account-income")
        val equityId = AccountId("account-equity")
        val row =
            CurrentVersionRow(
                transactionId = TransactionId("tx-kinds"),
                currentVersionId = TransactionVersionId("version-kinds"),
                kind = TransactionKind.EXPENSE,
                occurredAt = occurredAt,
                postings =
                    listOf(
                        Posting(PostingId("p-asset"), assetId, Money.ofMinor(500L, cny)),
                        Posting(PostingId("p-expense"), expenseId, Money.ofMinor(300L, cny)),
                        Posting(PostingId("p-liability"), liabilityId, Money.ofMinor(-200L, cny)),
                        Posting(PostingId("p-income"), incomeId, Money.ofMinor(-100L, cny)),
                        Posting(PostingId("p-equity"), equityId, Money.ofMinor(-500L, cny)),
                    ),
            )
        val query =
            QueryLedgerCurrentState(
                CurrentStateFixedReadPort(listOf(row)),
                ledgerId,
                fiveKindCatalog(assetId, expenseId, liabilityId, incomeId, equityId),
            )

        val result = assertIs<LedgerCurrentStateResult.Success>(query.query())
        val balances = result.state.balances.associateBy { it.accountId }
        assertEquals(500L, balances.getValue(assetId).displayMinorUnits)
        assertEquals(300L, balances.getValue(expenseId).displayMinorUnits)
        assertEquals(200L, balances.getValue(liabilityId).displayMinorUnits)
        assertEquals(100L, balances.getValue(incomeId).displayMinorUnits)
        assertEquals(500L, balances.getValue(equityId).displayMinorUnits)
    }

    @Test
    fun balancesAreGroupedPerAccountAndCurrencyWithoutCrossCurrencySummation() {
        val cnyAccountId = AccountId("account-cny")
        val usdAccountId = AccountId("account-usd")
        val row =
            CurrentVersionRow(
                transactionId = TransactionId("tx-multi"),
                currentVersionId = TransactionVersionId("version-multi"),
                kind = TransactionKind.EXPENSE,
                occurredAt = occurredAt,
                postings =
                    listOf(
                        Posting(PostingId("p-cny-1"), cnyAccountId, Money.ofMinor(1_000L, cny)),
                        Posting(PostingId("p-cny-2"), cnyAccountId, Money.ofMinor(500L, cny)),
                        Posting(PostingId("p-usd"), usdAccountId, Money.ofMinor(-250L, usd)),
                    ),
            )
        val query =
            QueryLedgerCurrentState(
                CurrentStateFixedReadPort(listOf(row)),
                ledgerId,
                catalogOfAccounts(
                    Account(cnyAccountId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                    Account(usdAccountId, ledgerId, AccountKind.ASSET, usd, ownedByUser = true, realAccount = true),
                ),
            )

        val result = assertIs<LedgerCurrentStateResult.Success>(query.query())
        val balances = result.state.balances
        assertEquals(2, balances.size)
        val cnyBalance = balances.single { it.currency == cny }
        assertEquals(1_500L, cnyBalance.ledgerSignedMinorUnits)
        assertEquals(1_500L, cnyBalance.displayMinorUnits)
        val usdBalance = balances.single { it.currency == usd }
        assertEquals(-250L, usdBalance.ledgerSignedMinorUnits)
        assertEquals(-250L, usdBalance.displayMinorUnits)
    }

    @Test
    fun readPortExceptionSurfacesAsUnavailable() {
        val query = QueryLedgerCurrentState(CurrentStateThrowingReadPort(), ledgerId, fiveKindCatalog())

        assertEquals(LedgerCurrentStateResult.Unavailable, query.query())
    }

    @Test
    fun postingAccountOutsideTheCatalogSurfacesAsInvalidState() {
        val unknownId = AccountId("account-unknown")
        val row =
            CurrentVersionRow(
                transactionId = TransactionId("tx-unknown"),
                currentVersionId = TransactionVersionId("version-unknown"),
                kind = TransactionKind.EXPENSE,
                occurredAt = occurredAt,
                postings = listOf(Posting(PostingId("p-unknown"), unknownId, Money.ofMinor(1L, cny))),
            )
        val query = QueryLedgerCurrentState(CurrentStateFixedReadPort(listOf(row)), ledgerId, fiveKindCatalog())

        assertEquals(LedgerCurrentStateResult.InvalidState, query.query())
    }

    @Test
    fun postingAccountOfAnotherLedgerSurfacesAsInvalidState() {
        val foreignId = AccountId("account-foreign-ledger")
        val row =
            CurrentVersionRow(
                transactionId = TransactionId("tx-foreign"),
                currentVersionId = TransactionVersionId("version-foreign"),
                kind = TransactionKind.EXPENSE,
                occurredAt = occurredAt,
                postings = listOf(Posting(PostingId("p-foreign"), foreignId, Money.ofMinor(1L, cny))),
            )
        val catalog =
            catalogOfAccounts(
                Account(foreignId, LedgerId("ledger-other"), AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
            )
        val query = QueryLedgerCurrentState(CurrentStateFixedReadPort(listOf(row)), ledgerId, catalog)

        assertEquals(LedgerCurrentStateResult.InvalidState, query.query())
    }

    @Test
    fun liabilityDisplaySignNegationOverflowSurfacesAsInvalidState() {
        val liabilityId = AccountId("account-liability")
        val row =
            CurrentVersionRow(
                transactionId = TransactionId("tx-min"),
                currentVersionId = TransactionVersionId("version-min"),
                kind = TransactionKind.EXPENSE,
                occurredAt = occurredAt,
                postings = listOf(Posting(PostingId("p-min"), liabilityId, Money.ofMinor(Long.MIN_VALUE, cny))),
            )
        val query = QueryLedgerCurrentState(CurrentStateFixedReadPort(listOf(row)), ledgerId, fiveKindCatalog())

        // Negating Long.MIN_VALUE would overflow; the checked negation surfaces InvalidState.
        assertEquals(LedgerCurrentStateResult.InvalidState, query.query())
    }

    private fun fiveKindCatalog(
        assetId: AccountId = AccountId("account-asset"),
        expenseId: AccountId = AccountId("account-expense"),
        liabilityId: AccountId? = AccountId("account-liability"),
        incomeId: AccountId? = AccountId("account-income"),
        equityId: AccountId? = AccountId("account-equity"),
    ): LedgerCatalog =
        catalogOfAccounts(
            listOfNotNull(
                Account(assetId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                Account(expenseId, ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                liabilityId?.let { Account(it, ledgerId, AccountKind.LIABILITY, cny, ownedByUser = false, realAccount = true) },
                incomeId?.let { Account(it, ledgerId, AccountKind.INCOME, cny, ownedByUser = false, realAccount = false) },
                equityId?.let { Account(it, ledgerId, AccountKind.EQUITY, cny, ownedByUser = false, realAccount = false) },
            ),
        )

    private fun catalogOfAccounts(
        vararg accounts: Account,
    ): LedgerCatalog = catalogOfAccounts(accounts.toList())

    private fun catalogOfAccounts(accounts: List<Account>): LedgerCatalog =
        when (val result = LedgerCatalog.create(accounts = accounts, categories = emptyList())) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> error("test catalog must be valid")
        }
}

private class CurrentStateFixedReadPort(
    private val rows: List<CurrentVersionRow>,
) : LedgerCurrentStateReadPort {
    override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> = rows

    override fun findManualExpenseByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualExpenseCommitRecord? = null

    override fun findManualExpenseByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedExpenseReceipt,
    ): ManualExpenseCommitRecord? = null
}

private class CurrentStateThrowingReadPort : LedgerCurrentStateReadPort {
    override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> = throw IllegalStateException("database unavailable")

    override fun findManualExpenseByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualExpenseCommitRecord? = throw IllegalStateException("database unavailable")

    override fun findManualExpenseByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedExpenseReceipt,
    ): ManualExpenseCommitRecord? = throw IllegalStateException("database unavailable")
}
