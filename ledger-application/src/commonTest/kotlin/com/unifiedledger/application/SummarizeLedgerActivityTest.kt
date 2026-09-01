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
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class SummarizeLedgerActivityTest {
    private val ledgerId = LedgerId("ledger-a")
    private val cny = CurrencyUnit("CNY", 2)
    private val usd = CurrencyUnit("USD", 2)
    private val assetId = AccountId("account-asset")
    private val savingsId = AccountId("account-savings")
    private val expenseId = AccountId("account-expense")
    private val incomeId = AccountId("account-income")
    private val usdAssetId = AccountId("account-usd-asset")
    private val usdExpenseId = AccountId("account-usd-expense")
    private val occurredAt = Instant.parse("2026-01-15T00:30:00Z")
    private val summarize = SummarizeLedgerActivity(catalog())

    @Test
    fun emptyStateSummarizesToZeroCountsAndEmptyTotals() {
        val summary = summarize.summarize(LedgerCurrentState(ledgerId, transactions = emptyList(), balances = emptyList()))

        assertEquals(0, summary.totalTransactionCount)
        assertEquals(emptyMap<TransactionKind, Int>(), summary.countByKind)
        assertEquals(emptyList<CurrencyActivityTotal>(), summary.totalsByCurrency)
    }

    @Test
    fun singleManualExpenseCountsOnceAndTotalsTheCnyExpense() {
        val summary =
            summarize.summarize(
                stateOf(
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
                    ),
                ),
            )

        assertEquals(1, summary.totalTransactionCount)
        assertEquals(1, summary.countByKind[TransactionKind.EXPENSE])
        val cnyTotal = summary.totalsByCurrency.single()
        assertEquals(cny, cnyTotal.currency)
        assertEquals(3_580L, cnyTotal.expenseMinorUnits)
        assertEquals(0L, cnyTotal.incomeMinorUnits)
    }

    @Test
    fun incomeTransactionNegatesIncomeAccountPostingsIntoPositiveIncome() {
        val summary =
            summarize.summarize(
                stateOf(
                    CurrentVersionRow(
                        transactionId = TransactionId("tx-1"),
                        currentVersionId = TransactionVersionId("version-1"),
                        kind = TransactionKind.INCOME,
                        occurredAt = occurredAt,
                        postings =
                            listOf(
                                Posting(PostingId("posting-1"), assetId, Money.ofMinor(5_000L, cny)),
                                Posting(PostingId("posting-2"), incomeId, Money.ofMinor(-5_000L, cny)),
                            ),
                    ),
                ),
            )

        assertEquals(1, summary.totalTransactionCount)
        assertEquals(1, summary.countByKind[TransactionKind.INCOME])
        val cnyTotal = summary.totalsByCurrency.single()
        assertEquals(0L, cnyTotal.expenseMinorUnits)
        assertEquals(5_000L, cnyTotal.incomeMinorUnits)
    }

    @Test
    fun accountTransferCountsOnceWithoutContributingAnyAmount() {
        val summary =
            summarize.summarize(
                stateOf(
                    CurrentVersionRow(
                        transactionId = TransactionId("tx-1"),
                        currentVersionId = TransactionVersionId("version-1"),
                        kind = TransactionKind.ACCOUNT_TRANSFER,
                        occurredAt = occurredAt,
                        postings =
                            listOf(
                                Posting(PostingId("posting-1"), assetId, Money.ofMinor(-1_000L, cny)),
                                Posting(PostingId("posting-2"), savingsId, Money.ofMinor(1_000L, cny)),
                            ),
                    ),
                ),
            )

        assertEquals(1, summary.totalTransactionCount)
        assertEquals(1, summary.countByKind[TransactionKind.ACCOUNT_TRANSFER])
        assertEquals(emptyList<CurrencyActivityTotal>(), summary.totalsByCurrency)
    }

    @Test
    fun multiCurrencyTotalsArePerCurrencyAndSortedByCodeAscending() {
        val summary =
            summarize.summarize(
                stateOf(
                    CurrentVersionRow(
                        transactionId = TransactionId("tx-cny-expense"),
                        currentVersionId = TransactionVersionId("version-1"),
                        kind = TransactionKind.EXPENSE,
                        occurredAt = occurredAt,
                        postings =
                            listOf(
                                Posting(PostingId("posting-1"), assetId, Money.ofMinor(-3_580L, cny)),
                                Posting(PostingId("posting-2"), expenseId, Money.ofMinor(3_580L, cny)),
                            ),
                    ),
                    CurrentVersionRow(
                        transactionId = TransactionId("tx-cny-income"),
                        currentVersionId = TransactionVersionId("version-2"),
                        kind = TransactionKind.INCOME,
                        occurredAt = occurredAt,
                        postings =
                            listOf(
                                Posting(PostingId("posting-3"), assetId, Money.ofMinor(2_000L, cny)),
                                Posting(PostingId("posting-4"), incomeId, Money.ofMinor(-2_000L, cny)),
                            ),
                    ),
                    CurrentVersionRow(
                        transactionId = TransactionId("tx-usd-expense"),
                        currentVersionId = TransactionVersionId("version-3"),
                        kind = TransactionKind.EXPENSE,
                        occurredAt = occurredAt,
                        postings =
                            listOf(
                                Posting(PostingId("posting-5"), usdAssetId, Money.ofMinor(-250L, usd)),
                                Posting(PostingId("posting-6"), usdExpenseId, Money.ofMinor(250L, usd)),
                            ),
                    ),
                ),
            )

        assertEquals(3, summary.totalTransactionCount)
        assertEquals(listOf(cny, usd), summary.totalsByCurrency.map { it.currency })
        assertEquals(3_580L, summary.totalsByCurrency[0].expenseMinorUnits)
        assertEquals(2_000L, summary.totalsByCurrency[0].incomeMinorUnits)
        assertEquals(250L, summary.totalsByCurrency[1].expenseMinorUnits)
        assertEquals(0L, summary.totalsByCurrency[1].incomeMinorUnits)
    }

    @Test
    fun postingAccountOutsideTheCatalogFailsClosed() {
        val unknownId = AccountId("account-unknown")
        val row =
            CurrentVersionRow(
                transactionId = TransactionId("tx-unknown"),
                currentVersionId = TransactionVersionId("version-1"),
                kind = TransactionKind.EXPENSE,
                occurredAt = occurredAt,
                postings = listOf(Posting(PostingId("posting-1"), unknownId, Money.ofMinor(1L, cny))),
            )

        assertFailsWith<IllegalStateException> {
            summarize.summarize(stateOf(row))
        }
    }

    @Test
    fun incomePostingAtLongMinValueFailsClosedOnCheckedNegation() {
        val row =
            CurrentVersionRow(
                transactionId = TransactionId("tx-min"),
                currentVersionId = TransactionVersionId("version-1"),
                kind = TransactionKind.INCOME,
                occurredAt = occurredAt,
                postings = listOf(Posting(PostingId("posting-1"), incomeId, Money.ofMinor(Long.MIN_VALUE, cny))),
            )

        assertFailsWith<ArithmeticException> {
            summarize.summarize(stateOf(row))
        }
    }

    private fun stateOf(
        vararg rows: CurrentVersionRow,
    ): LedgerCurrentState = LedgerCurrentState(ledgerId, transactions = rows.toList(), balances = emptyList())

    private fun catalog(): LedgerCatalog =
        when (
            val result =
                LedgerCatalog.create(
                    accounts =
                        listOf(
                            Account(assetId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                            Account(savingsId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                            Account(expenseId, ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                            Account(incomeId, ledgerId, AccountKind.INCOME, cny, ownedByUser = false, realAccount = false),
                            Account(usdAssetId, ledgerId, AccountKind.ASSET, usd, ownedByUser = true, realAccount = true),
                            Account(usdExpenseId, ledgerId, AccountKind.EXPENSE, usd, ownedByUser = false, realAccount = false),
                        ),
                    categories = emptyList(),
                )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> error("test catalog must be valid")
        }
}
