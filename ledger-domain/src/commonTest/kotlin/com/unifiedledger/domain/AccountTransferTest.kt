package com.unifiedledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class AccountTransferTest {
    private val ledger = LedgerId("ledger-a")
    private val currency = CurrencyUnit("CNY", 2)
    private val catalog = transferCatalog()

    @Test
    fun `own asset transfer creates balanced principal and fee postings with report semantics`() {
        val result = assertIs<DomainResult.Success<AccountTransfer>>(
            createOwnAssetAccountTransfer(catalog, command(), ids()),
        ).value

        assertEquals(TransactionKind.ACCOUNT_TRANSFER, result.formalTransaction.transaction.kind)
        assertEquals(
            listOf(
                Triple("asset-bank-a", -6_000L, TransferPostingRole.PRINCIPAL_OUT),
                Triple("asset-wallet-b", 5_900L, TransferPostingRole.PRINCIPAL_IN),
                Triple("expense-account-transfer-fee", 100L, TransferPostingRole.FEE),
            ),
            result.postings.map {
                Triple(it.posting.accountId.value, it.posting.amount.minorUnits, it.role)
            },
        )
        assertEquals(
            AccountTransferReportEffects(
                consumptionMinor = 100L,
                ordinaryExpenseMinor = 100L,
                cashOutflowMinor = 100L,
                ordinaryIncomeMinor = 0L,
                cashInflowMinor = 0L,
                principalConsumptionMinor = 0L,
                principalExternalCashFlowMinor = 0L,
                internalTransferMinor = 5_900L,
                netWorthChangeMinor = -100L,
            ),
            result.reportEffects,
        )
    }

    @Test
    fun `validation precedence is stable and rejects outside asset to asset scope`() {
        val cases = listOf(
            command(sourceAccountId = AccountId("unknown")) to AccountTransferViolation.KnownAccountRequired(AccountTransferField.SOURCE_ACCOUNT),
            command(destinationAccountId = AccountId("unknown")) to AccountTransferViolation.KnownAccountRequired(AccountTransferField.DESTINATION_ACCOUNT),
            command(destinationAccountId = AccountId("asset-bank-a")) to AccountTransferViolation.DistinctAccountsRequired,
            command(destinationAccountId = AccountId("asset-external-x")) to AccountTransferViolation.OwnAccountRequired(AccountTransferField.DESTINATION_ACCOUNT),
            command(sourceAccountId = AccountId("expense-account-transfer-fee")) to AccountTransferViolation.RealFinancialAccountRequired(AccountTransferField.SOURCE_ACCOUNT),
            command(sourceAccountId = AccountId("liability-credit-c")) to AccountTransferViolation.AssetAccountRequired(AccountTransferField.SOURCE_ACCOUNT),
            command(destinationCredit = Money.ofMinor(0L, currency)) to AccountTransferViolation.AmountMustBePositive(AccountTransferField.DESTINATION_CREDIT),
            command(destinationCredit = Money.ofMinor(-1L, currency)) to AccountTransferViolation.AmountMustBePositive(AccountTransferField.DESTINATION_CREDIT),
            command(fee = Money.ofMinor(-1L, currency)) to AccountTransferViolation.FeeMustNotBeNegative,
            command(fee = Money.ofMinor(99L, currency)) to AccountTransferViolation.AmountsMustBalance,
            command(destinationCredit = Money.ofMinor(5_900L, CurrencyUnit("USD", 2))) to AccountTransferViolation.SameCurrencyRequired,
        )

        cases.forEach { (candidate, expected) ->
            assertEquals(
                expected,
                assertIs<DomainResult.Failure>(
                    createOwnAssetAccountTransfer(catalog, candidate, ids()),
                ).violation,
            )
        }
    }

    private fun command(
        sourceAccountId: AccountId = AccountId("asset-bank-a"),
        destinationAccountId: AccountId = AccountId("asset-wallet-b"),
        sourceDebit: Money = Money.ofMinor(6_000L, currency),
        destinationCredit: Money = Money.ofMinor(5_900L, currency),
        fee: Money = Money.ofMinor(100L, currency),
    ) = OwnAssetAccountTransferCommand(
        ledgerId = ledger,
        sourceAccountId = sourceAccountId,
        destinationAccountId = destinationAccountId,
        sourceDebit = sourceDebit,
        destinationCredit = destinationCredit,
        fee = fee,
        feeCategoryId = CategoryId("expense-category-transfer-fee"),
        times = TransactionTimes.collapsed(Instant.parse("2026-01-20T02:00:00Z")),
    )

    private fun ids() = AccountTransferIds(
        transactionId = TransactionId("tx-transfer"),
        versionId = TransactionVersionId("version-transfer-v1"),
        postingSetId = PostingSetId("posting-set-transfer"),
        sourcePostingId = PostingId("posting-source"),
        destinationPostingId = PostingId("posting-destination"),
        feePostingId = PostingId("posting-fee"),
    )

    private fun transferCatalog(): LedgerCatalog = assertIs<DomainResult.Success<LedgerCatalog>>(
        LedgerCatalog.create(
            accounts = listOf(
                Account(AccountId("asset-bank-a"), ledger, AccountKind.ASSET, currency, ownedByUser = true, realAccount = true),
                Account(AccountId("asset-wallet-b"), ledger, AccountKind.ASSET, currency, ownedByUser = true, realAccount = true),
                Account(AccountId("asset-external-x"), ledger, AccountKind.ASSET, currency, ownedByUser = false, realAccount = true),
                Account(AccountId("liability-credit-c"), ledger, AccountKind.LIABILITY, currency, ownedByUser = true, realAccount = true),
                Account(AccountId("expense-account-transfer-fee"), ledger, AccountKind.EXPENSE, currency, ownedByUser = false, realAccount = false),
            ),
            categories = listOf(
                Category(CategoryId("expense-category-financial"), ledger, null, null, active = true),
                Category(CategoryId("expense-category-transfer-fee"), ledger, CategoryId("expense-category-financial"), AccountId("expense-account-transfer-fee"), active = true),
            ),
        ),
    ).value
}
