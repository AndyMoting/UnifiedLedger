package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.AccountTransfer
import com.unifiedledger.domain.AccountTransferIds
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.ManualTransferViolation
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * P7-02.B product transfer transaction factory: derived source debit, the fee-bearing three-leg
 * path, the pure-principal two-leg path (with the product note and report effects) and the
 * stable product failure tokens.
 */
class ManualTransferTransactionFactoryTest {
    private val ledger = LedgerId("ledger-a")
    private val cny = CurrencyUnit("CNY", 2)
    private val occurredAt = Instant.parse("2026-01-15T00:30:00Z")

    private fun catalog(): LedgerCatalog =
        assertIs<DomainResult.Success<LedgerCatalog>>(
            LedgerCatalog.create(
                accounts =
                    listOf(
                        Account(AccountId("asset-a"), ledger, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                        Account(AccountId("asset-b"), ledger, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                        Account(AccountId("asset-x"), ledger, AccountKind.ASSET, cny, ownedByUser = false, realAccount = false),
                        Account(AccountId("expense-posting"), ledger, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                    ),
                categories =
                    listOf(
                        Category(CategoryId("fee-group"), ledger, null, null, active = true, kind = CategoryKind.EXPENSE),
                        Category(CategoryId("fee-leaf"), ledger, CategoryId("fee-group"), AccountId("expense-posting"), active = true, kind = CategoryKind.EXPENSE),
                    ),
            ),
        ).value

    private fun ids() =
        ConfirmedManualTransferCommitIds(
            confirmationId = ConfirmationId("confirmation-1"),
            transferIds =
                AccountTransferIds(
                    transactionId = TransactionId("tx-1"),
                    versionId = TransactionVersionId("v1"),
                    postingSetId = PostingSetId("set-1"),
                    sourcePostingId = PostingId("p-out"),
                    destinationPostingId = PostingId("p-in"),
                    feePostingId = PostingId("p-fee"),
                ),
        )

    private fun snapshot(
        credit: Long = 10_000L,
        fee: Long = 0L,
        feeCategory: CategoryId? = null,
        note: String = "",
        source: AccountId = AccountId("asset-a"),
        destination: AccountId = AccountId("asset-b"),
    ) = ManualTransferRequestSnapshot(
        ledgerId = ledger,
        sourceAccountId = source,
        destinationAccountId = destination,
        destinationCredit = Money.ofMinor(credit, cny),
        fee = Money.ofMinor(fee, cny),
        feeCategoryId = feeCategory,
        occurredAt = occurredAt,
        note = note,
    )

    @Test
    fun `fee bearing transfer derives the source debit and writes all three legs`() {
        val commit =
            assertIs<DomainResult.Success<ConfirmedManualTransferCommit>>(
                ManualTransferTransactionFactory(catalog()).create(snapshot(credit = 10_000L, fee = 200L, feeCategory = CategoryId("fee-leaf"), note = "rent"), ids()),
            ).value
        val transfer: AccountTransfer = commit.transfer
        assertEquals(TransactionKind.ACCOUNT_TRANSFER, transfer.formalTransaction.transaction.kind)
        assertEquals(
            listOf(-10_200L, 10_000L, 200L),
            transfer.formalTransaction.postingSets
                .single()
                .postings
                .map { it.amount.minorUnits },
        )
        assertEquals(
            "rent",
            transfer.formalTransaction.versions
                .single()
                .note,
        )
        // T-3 report effects: fee is the ordinary expense / net-worth change; principal is internal.
        assertEquals(200L, transfer.reportEffects.ordinaryExpenseMinor)
        assertEquals(-200L, transfer.reportEffects.netWorthChangeMinor)
        assertEquals(10_000L, transfer.reportEffects.internalTransferMinor)
        assertEquals(0L, transfer.reportEffects.principalConsumptionMinor)
    }

    @Test
    fun `pure principal transfer uses two legs and carries the product note`() {
        val commit =
            assertIs<DomainResult.Success<ConfirmedManualTransferCommit>>(
                ManualTransferTransactionFactory(catalog()).create(snapshot(credit = 10_000L, fee = 0L, note = "move"), ids()),
            ).value
        val transfer = commit.transfer
        assertEquals(
            listOf(-10_000L, 10_000L),
            transfer.formalTransaction.postingSets
                .single()
                .postings
                .map { it.amount.minorUnits },
        )
        assertEquals(
            "move",
            transfer.formalTransaction.versions
                .single()
                .note,
        )
        assertEquals(0L, transfer.reportEffects.ordinaryExpenseMinor)
        assertEquals(0L, transfer.reportEffects.netWorthChangeMinor)
        assertEquals(10_000L, transfer.reportEffects.internalTransferMinor)
    }

    @Test
    fun `same account is rejected with the product token`() {
        val result =
            ManualTransferTransactionFactory(catalog())
                .create(snapshot(source = AccountId("asset-a"), destination = AccountId("asset-a")), ids())
        assertEquals(ManualTransferViolation.TransferSameAccount, assertIs<DomainResult.Failure>(result).violation)
    }

    @Test
    fun `positive fee without a category is rejected`() {
        val result = ManualTransferTransactionFactory(catalog()).create(snapshot(fee = 200L, feeCategory = null), ids())
        assertEquals(ManualTransferViolation.TransferFeeCategoryRequired, assertIs<DomainResult.Failure>(result).violation)
    }

    @Test
    fun `zero fee carrying a category is rejected`() {
        val result = ManualTransferTransactionFactory(catalog()).create(snapshot(fee = 0L, feeCategory = CategoryId("fee-leaf")), ids())
        assertEquals(ManualTransferViolation.TransferFeeCategoryNotAllowed, assertIs<DomainResult.Failure>(result).violation)
    }

    @Test
    fun `an ineligible endpoint is rejected as not eligible`() {
        val result = ManualTransferTransactionFactory(catalog()).create(snapshot(source = AccountId("asset-x")), ids())
        assertEquals(ManualTransferViolation.TransferAccountNotEligible, assertIs<DomainResult.Failure>(result).violation)
    }

    @Test
    fun `admission validates both endpoints and the fee category`() {
        val catalog = catalog()
        assertNull(validateManualTransferAdmission(catalog, ledger, AccountId("asset-a"), AccountId("asset-b"), Money.ofMinor(0L, cny), null))
        assertEquals(
            com.unifiedledger.domain.CatalogAdmissionRejection.PaymentAccountNotFound,
            validateManualTransferAdmission(catalog, ledger, AccountId("missing"), AccountId("asset-b"), Money.ofMinor(0L, cny), null),
        )
        assertEquals(
            ManualTransferViolation.TransferFeeCategoryRequired,
            validateManualTransferAdmission(catalog, ledger, AccountId("asset-a"), AccountId("asset-b"), Money.ofMinor(200L, cny), null),
        )
        assertEquals(
            ManualTransferViolation.TransferFeeCategoryNotAllowed,
            validateManualTransferAdmission(catalog, ledger, AccountId("asset-a"), AccountId("asset-b"), Money.ofMinor(0L, cny), CategoryId("fee-leaf")),
        )
        assertNull(validateManualTransferAdmission(catalog, ledger, AccountId("asset-a"), AccountId("asset-b"), Money.ofMinor(200L, cny), CategoryId("fee-leaf")))
    }
}
