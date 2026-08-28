package com.unifiedledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class BalanceReplayTest {
    private val fixture = Rg01Fixture()

    @Test
    fun replaysTheOpeningBalanceAndAcceptedExpenseByAccountId() {
        val snapshot =
            success(
                replayBalances(
                    catalog = fixture.catalog,
                    transactions = listOf(fixture.openingBalance(), fixture.acceptedExpense()),
                ),
            )
        val expectedBalances =
            mapOf(
                fixture.paymentAccountId to money(96_420, fixture.cny),
                fixture.expenseAccountId to money(3_580, fixture.cny),
                fixture.equityAccountId to money(-100_000, fixture.cny),
            )

        expectedBalances.forEach { (accountId, balance) ->
            assertEquals(balance, snapshot.balances[accountId], accountId.toString())
        }
    }

    @Test
    fun rejectsDuplicateTransactionIdentityDuringReplay() {
        val transaction = fixture.openingBalance()

        val result =
            replayBalances(
                catalog = fixture.catalog,
                transactions = listOf(transaction, transaction),
            )

        assertEquals(DomainViolation.InvalidBalanceReplay, failure(result))
    }

    @Test
    fun snapshotCopiesCallerBalances() {
        val originalBalance = money(100_000, fixture.cny)
        val source = mutableMapOf(fixture.paymentAccountId to originalBalance)
        val snapshot = BalanceSnapshot(balances = source)

        source[fixture.paymentAccountId] = money(1, fixture.cny)

        assertEquals(originalBalance, snapshot.balances[fixture.paymentAccountId])
    }
}
