package com.unifiedledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class MixedPaymentTest {
    private val currency = CurrencyUnit("CNY", 2)
    private val ledgerId = LedgerId("ledger-a")

    @Test
    fun mixedExpenseCreatesTypedBalancedPostingsAndReportEffects() {
        val result = createMixedPaymentExpense(
            catalog(),
            MixedPaymentExpenseCommand(
                ledgerId, Money.ofMinor(12_000, currency), CategoryId("expense-daily"),
                listOf(
                    FundingComponent(AccountId("asset-a"), Money.ofMinor(7_000, currency)),
                    FundingComponent(AccountId("liability-b"), Money.ofMinor(5_000, currency)),
                ),
                TransactionTimes.collapsed(Instant.parse("2026-02-10T04:00:00Z")),
            ),
            MixedPaymentExpenseIds(
                TransactionId("tx"), TransactionVersionId("version"), PostingSetId("set"),
                PostingId("expense"), listOf(PostingId("asset"), PostingId("liability")),
            ),
        )
        val expense = assertIs<DomainResult.Success<MixedPaymentExpense>>(result).value
        assertEquals(TransactionKind.EXPENSE, expense.formalTransaction.transaction.kind)
        assertEquals(listOf(12_000L, -7_000L, -5_000L), expense.postings.map { it.posting.amount.minorUnits })
        assertEquals(
            listOf(
                MixedPaymentPostingRole.EXPENSE,
                MixedPaymentPostingRole.MIXED_EXPENSE_ASSET_FUNDING,
                MixedPaymentPostingRole.MIXED_EXPENSE_CREDIT_FUNDING,
            ),
            expense.postings.map { it.role },
        )
        assertEquals(CategoryId("expense-daily"), expense.postings.first().categoryId)
        assertEquals(MixedPaymentReportEffects(12_000, 12_000, 7_000, 0, -12_000), expense.reportEffects)
        val replayed = assertIs<DomainResult.Success<BalanceSnapshot>>(
            replayBalances(catalog(), listOf(expense.formalTransaction)),
        ).value.balances
        assertEquals(12_000L, replayed.getValue(AccountId("expense-account")).minorUnits)
        assertEquals(-7_000L, replayed.getValue(AccountId("asset-a")).minorUnits)
        assertEquals(-5_000L, replayed.getValue(AccountId("liability-b")).minorUnits)
    }

    @Test
    fun mixedExpenseUsesFrozenValidationPrecedence() {
        val base = MixedPaymentExpenseCommand(
            ledgerId, Money.ofMinor(12_000, currency), CategoryId("expense-daily"),
            listOf(
                FundingComponent(AccountId("asset-a"), Money.ofMinor(7_000, currency)),
                FundingComponent(AccountId("liability-b"), Money.ofMinor(5_000, currency)),
            ),
            TransactionTimes.collapsed(Instant.DISTANT_PAST),
        )
        val ids = MixedPaymentExpenseIds(TransactionId("t"), TransactionVersionId("v"), PostingSetId("s"), PostingId("e"), listOf(PostingId("a"), PostingId("l")))
        fun violation(command: MixedPaymentExpenseCommand) = assertIs<DomainResult.Failure>(createMixedPaymentExpense(catalog(), command, ids)).violation
        assertEquals(
            MixedPaymentViolation.SecondaryCategoryRequired,
            violation(base.copy(categoryId = CategoryId("expense-root"), total = Money.ofMinor(0, currency))),
        )
        assertEquals(MixedPaymentViolation.AmountMustBePositive, violation(base.copy(total = Money.ofMinor(0, currency))))
        assertEquals(MixedPaymentViolation.FundingLegMustBePositive, violation(base.copy(funding = base.funding.mapIndexed { i, it -> if (i == 0) it.copy(amount = Money.ofMinor(0, currency)) else it })))
        assertEquals(
            MixedPaymentViolation.FundingTotalMustEqualExpense,
            violation(base.copy(funding = listOf(base.funding[0], base.funding[1].copy(accountId = AccountId("asset-a"), amount = Money.ofMinor(4_999, currency))))),
        )
        assertEquals(
            MixedPaymentViolation.DuplicateFundingAccount,
            violation(base.copy(funding = listOf(base.funding[0], base.funding[1].copy(accountId = AccountId("asset-a"))))),
        )
    }

    @Test
    fun mixedExpenseRejectsTwoDistinctAssetLegsWithAssetAndCreditLiabilityRequired() {
        val violation = assertIs<DomainResult.Failure>(
            createMixedPaymentExpense(
                catalog(),
                MixedPaymentExpenseCommand(
                    ledgerId, Money.ofMinor(12_000, currency), CategoryId("expense-daily"),
                    listOf(
                        FundingComponent(AccountId("asset-a"), Money.ofMinor(7_000, currency)),
                        FundingComponent(AccountId("asset-b"), Money.ofMinor(5_000, currency)),
                    ),
                    TransactionTimes.collapsed(Instant.DISTANT_PAST),
                ),
                MixedPaymentExpenseIds(TransactionId("t"), TransactionVersionId("v"), PostingSetId("s"), PostingId("e"), listOf(PostingId("a"), PostingId("l"))),
            ),
        ).violation
        assertEquals(MixedPaymentViolation.AssetAndCreditLiabilityRequired, violation)
    }

    @Test
    fun creditPrincipalRepaymentIsNotAnAccountTransferAndHasNoConsumption() {
        val result = createCreditPrincipalRepayment(
            catalog(),
            CreditPrincipalRepaymentCommand(
                ledgerId, AccountId("asset-a"), AccountId("liability-b"), Money.ofMinor(5_000, currency),
                TransactionTimes.collapsed(Instant.parse("2026-03-05T01:00:00Z")),
            ),
            CreditPrincipalRepaymentIds(TransactionId("repay"), TransactionVersionId("repay-v1"), PostingSetId("repay-set"), PostingId("repay-asset"), PostingId("repay-liability")),
        )
        val repayment = assertIs<DomainResult.Success<CreditPrincipalRepayment>>(result).value
        assertEquals(TransactionKind.CREDIT_REPAYMENT, repayment.formalTransaction.transaction.kind)
        assertEquals(listOf(-5_000L, 5_000L), repayment.postings.map { it.posting.amount.minorUnits })
        assertEquals(
            listOf(
                MixedPaymentPostingRole.CREDIT_REPAYMENT_ASSET_OUTFLOW,
                MixedPaymentPostingRole.CREDIT_REPAYMENT_LIABILITY_PRINCIPAL,
            ),
            repayment.postings.map { it.role },
        )
        assertEquals(MixedPaymentReportEffects(0, 0, 5_000, 0, 0), repayment.reportEffects)
    }

    private fun catalog(): LedgerCatalog = assertIs<DomainResult.Success<LedgerCatalog>>(
        LedgerCatalog.create(
            listOf(
                Account(AccountId("asset-a"), ledgerId, AccountKind.ASSET, currency, true, true),
                Account(AccountId("asset-b"), ledgerId, AccountKind.ASSET, currency, true, true),
                Account(AccountId("liability-b"), ledgerId, AccountKind.LIABILITY, currency, true, true),
                Account(AccountId("expense-account"), ledgerId, AccountKind.EXPENSE, currency, false, false),
            ),
            listOf(
                Category(CategoryId("expense-root"), ledgerId, null, null, true),
                Category(CategoryId("expense-daily"), ledgerId, CategoryId("expense-root"), AccountId("expense-account"), true),
            ),
        ),
    ).value
}
