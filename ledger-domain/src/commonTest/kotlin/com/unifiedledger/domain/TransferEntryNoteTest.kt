package com.unifiedledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * P7-02.B T-4/G-A/R-1: the fee-bearing transfer command writes its note verbatim (default `""`),
 * while the pure-principal transfer command keeps `note: String? = null` and writes `null` when
 * not provided, so the import spine's `version.note != null` binding stays satisfied.
 */
class TransferEntryNoteTest {
    private val ledger = LedgerId("ledger")
    private val cny = CurrencyUnit("CNY", 2)
    private val occurredAt = Instant.parse("2026-01-15T00:30:00Z")

    private fun catalog(): LedgerCatalog =
        assertIs<DomainResult.Success<LedgerCatalog>>(
            LedgerCatalog.create(
                listOf(
                    Account(AccountId("asset-a"), ledger, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                    Account(AccountId("asset-b"), ledger, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                    Account(AccountId("expense"), ledger, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                ),
                listOf(
                    Category(CategoryId("fee-group"), ledger, null, null, true, CategoryKind.EXPENSE),
                    Category(CategoryId("fee-leaf"), ledger, CategoryId("fee-group"), AccountId("expense"), true, CategoryKind.EXPENSE),
                ),
            ),
        ).value

    @Test
    fun `fee bearing transfer note reaches the version and defaults empty`() {
        val withNote =
            createOwnAssetAccountTransfer(
                catalog = catalog(),
                command =
                    OwnAssetAccountTransferCommand(
                        ledgerId = ledger,
                        sourceAccountId = AccountId("asset-a"),
                        destinationAccountId = AccountId("asset-b"),
                        sourceDebit = Money.ofMinor(10_200L, cny),
                        destinationCredit = Money.ofMinor(10_000L, cny),
                        fee = Money.ofMinor(200L, cny),
                        feeCategoryId = CategoryId("fee-leaf"),
                        times = TransactionTimes.collapsed(occurredAt),
                        note = "rent",
                    ),
                ids = AccountTransferIds(TransactionId("t"), TransactionVersionId("v"), PostingSetId("s"), PostingId("p1"), PostingId("p2"), PostingId("p3")),
            )
        assertEquals(
            "rent",
            assertIs<DomainResult.Success<AccountTransfer>>(withNote)
                .value.formalTransaction.versions
                .single()
                .note,
        )

        val defaultNote =
            createOwnAssetAccountTransfer(
                catalog = catalog(),
                command =
                    OwnAssetAccountTransferCommand(
                        ledgerId = ledger,
                        sourceAccountId = AccountId("asset-a"),
                        destinationAccountId = AccountId("asset-b"),
                        sourceDebit = Money.ofMinor(10_200L, cny),
                        destinationCredit = Money.ofMinor(10_000L, cny),
                        fee = Money.ofMinor(200L, cny),
                        feeCategoryId = CategoryId("fee-leaf"),
                        times = TransactionTimes.collapsed(occurredAt),
                    ),
                ids = AccountTransferIds(TransactionId("t"), TransactionVersionId("v"), PostingSetId("s"), PostingId("p1"), PostingId("p2"), PostingId("p3")),
            )
        assertEquals(
            "",
            assertIs<DomainResult.Success<AccountTransfer>>(defaultNote)
                .value.formalTransaction.versions
                .single()
                .note,
        )
    }

    @Test
    fun `pure principal transfer note stays nullable and defaults null for the import chain`() {
        val defaultNote =
            createOwnAssetPrincipalTransfer(
                catalog = catalog(),
                command =
                    OwnAssetPrincipalTransferCommand(
                        ledgerId = ledger,
                        sourceAccountId = AccountId("asset-a"),
                        destinationAccountId = AccountId("asset-b"),
                        amount = Money.ofMinor(10_000L, cny),
                        times = TransactionTimes.collapsed(occurredAt),
                    ),
                ids = OwnAssetPrincipalTransferIds(TransactionId("t"), TransactionVersionId("v"), PostingSetId("s"), PostingId("p1"), PostingId("p2")),
            )
        assertNull(
            assertIs<DomainResult.Success<OwnAssetPrincipalTransfer>>(defaultNote)
                .value.formalTransaction.versions
                .single()
                .note,
        )

        val explicit =
            createOwnAssetPrincipalTransfer(
                catalog = catalog(),
                command =
                    OwnAssetPrincipalTransferCommand(
                        ledgerId = ledger,
                        sourceAccountId = AccountId("asset-a"),
                        destinationAccountId = AccountId("asset-b"),
                        amount = Money.ofMinor(10_000L, cny),
                        times = TransactionTimes.collapsed(occurredAt),
                        note = "move",
                    ),
                ids = OwnAssetPrincipalTransferIds(TransactionId("t"), TransactionVersionId("v"), PostingSetId("s"), PostingId("p1"), PostingId("p2")),
            )
        assertEquals(
            "move",
            assertIs<DomainResult.Success<OwnAssetPrincipalTransfer>>(explicit)
                .value.formalTransaction.versions
                .single()
                .note,
        )
    }
}
