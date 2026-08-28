package com.unifiedledger.domain

import kotlin.test.assertIs
import kotlin.time.Instant

internal inline fun <reified T> success(result: DomainResult<T>): T = assertIs<DomainResult.Success<T>>(result).value

internal fun failure(result: DomainResult<*>): DomainViolation = assertIs<DomainResult.Failure>(result).violation

internal fun money(
    minorUnits: Long,
    currency: CurrencyUnit,
): Money = Money.ofMinor(minorUnits, currency)

internal class Rg01Fixture {
    val ledgerId = LedgerId("ledger-a")
    val cny = CurrencyUnit("CNY", 2)
    val paymentAccountId = AccountId("asset-bank-a")
    val expenseAccountId = AccountId("expense-account-breakfast")
    val equityAccountId = AccountId("equity-opening-a")
    private val parentCategoryId = CategoryId("expense-category-food")
    val categoryId = CategoryId("expense-category-breakfast")
    val amount = money(3_580, cny)
    val times = TransactionTimes.collapsed(Instant.parse("2026-01-15T00:30:00Z"))

    val accounts =
        listOf(
            Account(
                id = paymentAccountId,
                ledgerId = ledgerId,
                kind = AccountKind.ASSET,
                currency = cny,
                ownedByUser = true,
                realAccount = true,
            ),
            Account(
                id = expenseAccountId,
                ledgerId = ledgerId,
                kind = AccountKind.EXPENSE,
                currency = cny,
                ownedByUser = false,
                realAccount = false,
            ),
            Account(
                id = equityAccountId,
                ledgerId = ledgerId,
                kind = AccountKind.EQUITY,
                currency = cny,
                ownedByUser = false,
                realAccount = false,
            ),
        )
    val categories =
        listOf(
            Category(
                id = parentCategoryId,
                ledgerId = ledgerId,
                parentId = null,
                postingAccountId = null,
                active = true,
            ),
            Category(
                id = categoryId,
                ledgerId = ledgerId,
                parentId = parentCategoryId,
                postingAccountId = expenseAccountId,
                active = true,
            ),
        )
    val catalog =
        success(
            LedgerCatalog.create(
                accounts = accounts,
                categories = categories,
            ),
        )

    val command =
        AssetPaidOrdinaryExpenseCommand(
            ledgerId = ledgerId,
            amount = amount,
            categoryId = categoryId,
            paymentAccountId = paymentAccountId,
            times = times,
        )

    val expenseIds =
        AssetPaidOrdinaryExpenseIds(
            transactionId = TransactionId("tx-expense-rg01"),
            versionId = TransactionVersionId("version-expense-rg01-v1"),
            postingSetId = PostingSetId("posting-set-expense-rg01"),
            expensePostingId = PostingId("posting-expense-rg01"),
            paymentPostingId = PostingId("posting-bank-rg01"),
        )

    fun acceptedExpense(): FormalTransaction = success(createAssetPaidOrdinaryExpense(catalog, command, expenseIds))

    fun openingBalance(): FormalTransaction {
        val transactionId = TransactionId("tx-opening-a")
        val versionId = TransactionVersionId("version-opening-a-v1")
        val postingSetId = PostingSetId("posting-set-opening-a")
        val openingTimes =
            TransactionTimes.collapsed(
                Instant.parse("2026-01-01T00:00:00+08:00"),
            )
        val postingSet =
            success(
                PostingSet.create(
                    id = postingSetId,
                    postings =
                        listOf(
                            Posting(
                                PostingId("posting-opening-bank-a"),
                                paymentAccountId,
                                money(100_000, cny),
                            ),
                            Posting(
                                PostingId("posting-opening-equity-a"),
                                equityAccountId,
                                money(-100_000, cny),
                            ),
                        ),
                ),
            )
        val transaction =
            Transaction(
                id = transactionId,
                ledgerId = ledgerId,
                kind = TransactionKind.OPENING_BALANCE,
                currentVersionId = versionId,
            )
        val version =
            TransactionVersion(
                id = versionId,
                transactionId = transactionId,
                versionNumber = 1,
                postingSetId = postingSetId,
                times = openingTimes,
            )
        return success(
            FormalTransaction.create(
                transaction = transaction,
                versions = listOf(version),
                postingSets = listOf(postingSet),
            ),
        )
    }
}
