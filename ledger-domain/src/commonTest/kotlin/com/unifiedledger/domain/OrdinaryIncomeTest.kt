package com.unifiedledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OrdinaryIncomeTest {
    @Test fun `income credits owned asset and debits hidden income account`() {
        val ledger = LedgerId("ledger")
        val currency = CurrencyUnit("CNY", 2)
        val catalog =
            LedgerCatalog
                .create(
                    listOf(
                        Account(AccountId("asset"), ledger, AccountKind.ASSET, currency, true, true),
                        Account(AccountId("income"), ledger, AccountKind.INCOME, currency, false, false),
                    ),
                    listOf(
                        Category(CategoryId("parent"), ledger, null, null, true, CategoryKind.INCOME),
                        Category(CategoryId("child"), ledger, CategoryId("parent"), AccountId("income"), true, CategoryKind.INCOME),
                    ),
                ).let { assertIs<DomainResult.Success<LedgerCatalog>>(it).value }
        val result = createAssetReceivedOrdinaryIncome(catalog, AssetReceivedOrdinaryIncomeCommand(ledger, Money.ofMinor(3000, currency), CategoryId("child"), AccountId("asset"), TransactionTimes.collapsed(kotlin.time.Instant.parse("2026-01-16T01:00:00Z"))), AssetReceivedOrdinaryIncomeIds(TransactionId("tx"), TransactionVersionId("v1"), PostingSetId("set"), PostingId("asset-posting"), PostingId("income-posting")))
        val transaction = assertIs<DomainResult.Success<FormalTransaction>>(result).value
        assertEquals(TransactionKind.INCOME, transaction.transaction.kind)
        assertEquals(
            listOf(3000L, -3000L),
            transaction.postingSets
                .single()
                .postings
                .map { it.amount.minorUnits },
        )
    }
}
