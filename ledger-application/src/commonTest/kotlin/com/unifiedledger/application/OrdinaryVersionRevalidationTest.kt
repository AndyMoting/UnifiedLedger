package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.P705FailureCode
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Unit evidence of the P7-05 restore revalidation (spec sections 3.3/8.2; D-156 DP-9; the
 * transfer-aware branch is D-228 slice 3): the `ACCOUNT_TRANSFER` branch admits what the
 * creation factories would admit at the same catalog state (two-leg without a category,
 * three-leg with an active leaf expense category, rename-but-active endpoints), rejects
 * deactivated/inadmissible references with the frozen catalog code while keeping the
 * transaction voided, and fail-closes on a stored shape no factory could have produced.
 *
 * The `EXPENSE`/`INCOME` vectors pin the first slice's judgments verbatim against the
 * pre-replacement behavior (base 919146e51 of `OrdinaryVersionRevalidation.kt`): same checks,
 * same order, same codes, so the kind dispatch is a pure dispatch. Values are synthetic.
 */
class OrdinaryVersionRevalidationTest {
    private val ledgerId = LedgerId("ledger-revalidation")
    private val otherLedgerId = LedgerId("ledger-revalidation-other")
    private val cny = CurrencyUnit("CNY", 2)

    private val bankA = AccountId("asset-bank-a")
    private val bankB = AccountId("asset-bank-b")
    private val otherLedgerBank = AccountId("asset-other-ledger")
    private val liabilityAccount = AccountId("asset-liability")
    private val expensePosting = AccountId("expense-fee-posting")
    private val incomePosting = AccountId("income-salary-posting")
    private val feeParent = CategoryId("category-fee-parent")
    private val feeLeaf = CategoryId("category-fee-leaf")
    private val salaryParent = CategoryId("category-salary-parent")
    private val salaryLeaf = CategoryId("category-salary-leaf")

    private fun catalog(
        accounts: List<Account> = defaultAccounts(),
        categories: List<Category> = defaultCategories(),
    ): LedgerCatalog =
        assertIs<DomainResult.Success<LedgerCatalog>>(
            LedgerCatalog.create(accounts = accounts, categories = categories),
        ).value

    private fun defaultAccounts(): List<Account> =
        listOf(
            Account(bankA, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
            Account(bankB, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
            Account(otherLedgerBank, otherLedgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
            Account(liabilityAccount, ledgerId, AccountKind.LIABILITY, cny, ownedByUser = true, realAccount = true),
            Account(expensePosting, ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
            Account(incomePosting, ledgerId, AccountKind.INCOME, cny, ownedByUser = false, realAccount = false),
        )

    private fun defaultCategories(): List<Category> =
        listOf(
            Category(feeParent, ledgerId, null, null, active = true),
            Category(feeLeaf, ledgerId, feeParent, expensePosting, active = true),
            Category(salaryParent, ledgerId, null, null, active = true, kind = CategoryKind.INCOME),
            Category(salaryLeaf, ledgerId, salaryParent, incomePosting, active = true, kind = CategoryKind.INCOME),
        )

    private fun posting(
        id: String,
        accountId: AccountId,
        minorUnits: Long,
    ): Posting = Posting(PostingId(id), accountId, Money.ofMinor(minorUnits, cny))

    /** The stored three-leg shape a `fee > 0` transfer's version carries (frozen leg order). */
    private fun threeLegTransferPostings(
        source: AccountId = bankA,
        destination: AccountId = bankB,
        creditMinor: Long = 10_000L,
        feeMinor: Long = 200L,
        feeAccount: AccountId = expensePosting,
    ): List<Posting> =
        listOf(
            posting("p-out", source, -(creditMinor + feeMinor)),
            posting("p-in", destination, creditMinor),
            posting("p-fee", feeAccount, feeMinor),
        )

    /** The stored two-leg shape a `fee == 0` transfer's version carries. */
    private fun twoLegTransferPostings(
        source: AccountId = bankA,
        destination: AccountId = bankB,
        creditMinor: Long = 10_000L,
    ): List<Posting> =
        listOf(
            posting("p-out", source, -creditMinor),
            posting("p-in", destination, creditMinor),
        )

    private fun revalidate(
        kind: TransactionKind,
        postings: List<Posting>,
        catalog: LedgerCatalog = catalog(),
    ): P705FailureCode? =
        revalidateOrdinaryVersionReferences(
            catalog = catalog,
            ledgerId = ledgerId,
            kind = kind,
            postings = postings,
        )

    // ----------------------------------------------------------- transfer branch

    @Test
    fun twoLegTransferWithoutACategoryIsAdmissible() {
        assertEquals(null, revalidate(TransactionKind.ACCOUNT_TRANSFER, twoLegTransferPostings()))
    }

    @Test
    fun threeLegTransferWithAnActiveLeafFeeCategoryIsAdmissible() {
        assertEquals(null, revalidate(TransactionKind.ACCOUNT_TRANSFER, threeLegTransferPostings()))
    }

    @Test
    fun renamedButActiveEndpointsAndFeeCategoryStayAdmissible() {
        val renamed =
            catalog(
                accounts =
                    defaultAccounts().map {
                        when (it.id) {
                            bankA -> it.copy(name = "银行A（新名）")
                            else -> it
                        }
                    },
                categories =
                    defaultCategories().map {
                        when (it.id) {
                            feeLeaf -> it.copy(name = "手续费-新名")
                            else -> it
                        }
                    },
            )
        assertEquals(null, revalidate(TransactionKind.ACCOUNT_TRANSFER, threeLegTransferPostings(), renamed))
    }

    @Test
    fun aDeactivatedEndpointIsACatalogRejection() {
        val deactivated =
            catalog(accounts = defaultAccounts().map { if (it.id == bankB) it.copy(active = false) else it })
        assertEquals(
            P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE,
            revalidate(TransactionKind.ACCOUNT_TRANSFER, threeLegTransferPostings(), deactivated),
        )
    }

    @Test
    fun aDeactivatedFeeCategoryIsACatalogRejection() {
        val deactivated =
            catalog(categories = defaultCategories().map { if (it.id == feeLeaf) it.copy(active = false) else it })
        assertEquals(
            P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE,
            revalidate(TransactionKind.ACCOUNT_TRANSFER, threeLegTransferPostings(), deactivated),
        )
    }

    @Test
    fun aCrossLedgerEndpointIsACatalogRejection() {
        assertEquals(
            P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE,
            revalidate(TransactionKind.ACCOUNT_TRANSFER, threeLegTransferPostings(source = otherLedgerBank)),
        )
    }

    @Test
    fun aNonAssetRealEndpointIsACatalogRejection() {
        assertEquals(
            P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE,
            revalidate(TransactionKind.ACCOUNT_TRANSFER, threeLegTransferPostings(source = liabilityAccount)),
        )
    }

    @Test
    fun aFeeLegWithoutAResolvableCategoryIsACatalogRejection() {
        // The fee posting account exists, but no category maps to it anymore: the reference the
        // stored version relies on is gone from the catalog.
        val withoutFeeCategory = catalog(categories = defaultCategories().filter { it.id != feeLeaf })
        assertEquals(
            P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE,
            revalidate(TransactionKind.ACCOUNT_TRANSFER, threeLegTransferPostings(), withoutFeeCategory),
        )
    }

    @Test
    fun aStoredShapeNoFactoryCouldProduceIsTheConstraintBackstop() {
        // Three real-account legs: no fee leg to identify, not a factory shape.
        assertEquals(
            P705FailureCode.P705_CONSTRAINT_VIOLATION,
            revalidate(
                TransactionKind.ACCOUNT_TRANSFER,
                listOf(
                    posting("p-out", bankA, -10_200L),
                    posting("p-in", bankB, 10_000L),
                    posting("p-extra", liabilityAccount, 200L),
                ),
            ),
        )
        // Two principals with the same sign: no negative source leg to map.
        assertEquals(
            P705FailureCode.P705_CONSTRAINT_VIOLATION,
            revalidate(
                TransactionKind.ACCOUNT_TRANSFER,
                listOf(posting("p-out", bankA, 10_000L), posting("p-in", bankB, 10_000L)),
            ),
        )
    }

    @Test
    fun anUnbalancedStoredTransferShapeIsAFieldRejection() {
        // |PRINCIPAL_OUT| != destinationCredit + fee: the reverse-derived command fails the
        // factories' balance rule, pinning that the command mapping really feeds the planner.
        assertEquals(
            P705FailureCode.P705_FIELD_NOT_SUPPORTED,
            revalidate(
                TransactionKind.ACCOUNT_TRANSFER,
                listOf(
                    posting("p-out", bankA, -10_000L),
                    posting("p-in", bankB, 10_000L),
                    posting("p-fee", expensePosting, 200L),
                ),
            ),
        )
    }

    // ---------------------------------------------------------- ordinary branch

    @Test
    fun ordinaryExpenseAndIncomeRowsStayAdmissible() {
        assertEquals(
            null,
            revalidate(
                TransactionKind.EXPENSE,
                listOf(posting("p-cat", expensePosting, 10_000L), posting("p-fund", bankA, -10_000L)),
            ),
        )
        assertEquals(
            null,
            revalidate(
                TransactionKind.INCOME,
                listOf(posting("p-fund", bankA, 10_000L), posting("p-cat", incomePosting, -10_000L)),
            ),
        )
    }

    @Test
    fun ordinaryRowsKeepTheFirstSliceRejectionCodes() {
        val catalogCode = P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE
        // A posting account missing from the catalog.
        assertEquals(
            catalogCode,
            revalidate(
                TransactionKind.EXPENSE,
                listOf(posting("p-cat", expensePosting, 10_000L), posting("p-fund", AccountId("asset-ghost"), -10_000L)),
            ),
        )
        // A deactivated funding account.
        val deactivatedFund =
            catalog(accounts = defaultAccounts().map { if (it.id == bankA) it.copy(active = false) else it })
        assertEquals(
            catalogCode,
            revalidate(
                TransactionKind.EXPENSE,
                listOf(posting("p-cat", expensePosting, 10_000L), posting("p-fund", bankA, -10_000L)),
                deactivatedFund,
            ),
        )
        // No posting account carries a category of this ledger (the shape that used to reject
        // transfers before the transfer-aware branch existed).
        assertEquals(
            catalogCode,
            revalidate(
                TransactionKind.EXPENSE,
                listOf(posting("p-out", bankA, -10_000L), posting("p-in", bankB, 10_000L)),
            ),
        )
        // A category of the wrong kind for the transaction.
        assertEquals(
            catalogCode,
            revalidate(
                TransactionKind.EXPENSE,
                listOf(posting("p-cat", incomePosting, 10_000L), posting("p-fund", bankA, -10_000L)),
            ),
        )
        // A parent-less (non-leaf) category.
        val leafless =
            catalog(
                categories =
                    defaultCategories().map { if (it.id == feeLeaf) it.copy(parentId = null, postingAccountId = expensePosting) else it },
            )
        assertEquals(
            catalogCode,
            revalidate(
                TransactionKind.EXPENSE,
                listOf(posting("p-cat", expensePosting, 10_000L), posting("p-fund", bankA, -10_000L)),
                leafless,
            ),
        )
        // A deactivated category.
        val deactivatedCategory =
            catalog(categories = defaultCategories().map { if (it.id == feeLeaf) it.copy(active = false) else it })
        assertEquals(
            catalogCode,
            revalidate(
                TransactionKind.EXPENSE,
                listOf(posting("p-cat", expensePosting, 10_000L), posting("p-fund", bankA, -10_000L)),
                deactivatedCategory,
            ),
        )
        // A funding leg that is not an owned real asset.
        assertEquals(
            catalogCode,
            revalidate(
                TransactionKind.EXPENSE,
                listOf(posting("p-cat", expensePosting, 10_000L), posting("p-fund", liabilityAccount, -10_000L)),
            ),
        )
    }

    @Test
    fun unsupportedKindsKeepTheKindGateRejection() {
        assertEquals(
            P705FailureCode.P705_KIND_NOT_SUPPORTED,
            revalidate(TransactionKind.REFUND_RECEIPT, twoLegTransferPostings()),
        )
    }
}
