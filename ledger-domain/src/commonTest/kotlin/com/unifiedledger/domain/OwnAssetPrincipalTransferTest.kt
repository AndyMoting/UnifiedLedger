package com.unifiedledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class OwnAssetPrincipalTransferTest {
    private val ledgerId = LedgerId("ledger-rg09")
    private val cny = CurrencyUnit("CNY", 2)
    private val catalog =
        success(
            LedgerCatalog.create(
                accounts =
                    listOf(
                        Account(AccountId("asset-a"), ledgerId, AccountKind.ASSET, cny, true, true),
                        Account(AccountId("asset-b"), ledgerId, AccountKind.ASSET, cny, true, true),
                    ),
                categories = emptyList(),
            ),
        )

    @Test
    fun `principal transfer creates only two balanced internal postings`() {
        val transfer =
            assertIs<DomainResult.Success<OwnAssetPrincipalTransfer>>(
                createOwnAssetPrincipalTransfer(
                    catalog,
                    OwnAssetPrincipalTransferCommand(
                        ledgerId = ledgerId,
                        sourceAccountId = AccountId("asset-b"),
                        destinationAccountId = AccountId("asset-a"),
                        amount = Money.ofMinor(2_000L, cny),
                        times = TransactionTimes.collapsed(Instant.parse("2026-01-20T04:00:00Z")),
                    ),
                    OwnAssetPrincipalTransferIds(
                        TransactionId("tx-transfer-rg09"),
                        TransactionVersionId("version-transfer-rg09-v1"),
                        PostingSetId("posting-set-transfer-rg09"),
                        PostingId("posting-transfer-b-rg09"),
                        PostingId("posting-transfer-a-rg09"),
                    ),
                ),
            ).value

        assertEquals(TransactionKind.ACCOUNT_TRANSFER, transfer.formalTransaction.transaction.kind)
        assertEquals(listOf(-2_000L, 2_000L), transfer.postings.map { it.posting.amount.minorUnits })
        assertEquals(2_000L, transfer.reportEffects.internalTransferMinor)
        assertEquals(0L, transfer.reportEffects.netWorthChangeMinor)
    }

    @Test
    fun `principal transfer rejects non-positive amount and same account`() {
        val base =
            OwnAssetPrincipalTransferCommand(
                ledgerId,
                AccountId("asset-b"),
                AccountId("asset-a"),
                Money.ofMinor(2_000L, cny),
                TransactionTimes.collapsed(Instant.parse("2026-01-20T04:00:00Z")),
            )
        val ids =
            OwnAssetPrincipalTransferIds(
                TransactionId("tx-transfer"),
                TransactionVersionId("version-transfer"),
                PostingSetId("posting-set-transfer"),
                PostingId("posting-out"),
                PostingId("posting-in"),
            )
        assertEquals(
            PrincipalTransferViolation.AmountMustBePositive,
            failure(createOwnAssetPrincipalTransfer(catalog, base.copy(amount = Money.ofMinor(0L, cny)), ids)),
        )
        assertEquals(
            PrincipalTransferViolation.DistinctAccountsRequired,
            failure(createOwnAssetPrincipalTransfer(catalog, base.copy(destinationAccountId = AccountId("asset-b")), ids)),
        )
    }
}
