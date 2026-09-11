package com.unifiedledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * P7-01 product catalog load-validation evidence (spec section 4.4 / 8 load-validation gate).
 * Positive product path, negative shape violations, and the deliberate scope assertions that
 * keep frozen golden catalog states (inactive leaf without a posting account, dangling parent)
 * loadable through the unchanged [LedgerCatalog.create].
 */
class CatalogValidationTest {
    private val ledgerId = LedgerId("ledger-a")
    private val cny = CurrencyUnit("CNY", 2)

    private val payment = account(AccountId("asset-payment"), AccountKind.ASSET, owned = true, real = true)
    private val expenseHidden = account(AccountId("expense-hidden"), AccountKind.EXPENSE, owned = false, real = false)
    private val incomeHidden = account(AccountId("income-hidden"), AccountKind.INCOME, owned = false, real = false)

    @Test
    fun productCatalogWithGroupAndActiveExpenseAndIncomeLeavesIsValid() {
        val catalog =
            catalog(
                listOf(payment, expenseHidden, incomeHidden),
                listOf(
                    category("expense-group", parent = null, posting = null, active = true, kind = CategoryKind.EXPENSE),
                    category("expense-leaf", parent = "expense-group", posting = "expense-hidden", active = true, kind = CategoryKind.EXPENSE),
                    category("income-group", parent = null, posting = null, active = true, kind = CategoryKind.INCOME),
                    category("income-leaf", parent = "income-group", posting = "income-hidden", active = true, kind = CategoryKind.INCOME),
                ),
            )

        assertIs<DomainResult.Success<Unit>>(validateProductCatalog(catalog))
    }

    @Test
    fun activeLeafWithoutPostingAccountIsRejected() {
        val catalog =
            catalog(
                listOf(payment, expenseHidden),
                listOf(
                    category("expense-group", parent = null, posting = null, active = true, kind = CategoryKind.EXPENSE),
                    category("expense-leaf", parent = "expense-group", posting = null, active = true, kind = CategoryKind.EXPENSE),
                ),
            )

        assertEquals(CatalogViolation.CategoryPostingAccountInvalid, failure(validateProductCatalog(catalog)))
    }

    @Test
    fun activeLeafWithWrongKindPostingAccountIsRejected() {
        val catalog =
            catalog(
                listOf(payment, incomeHidden),
                listOf(
                    category("expense-group", parent = null, posting = null, active = true, kind = CategoryKind.EXPENSE),
                    category("expense-leaf", parent = "expense-group", posting = "income-hidden", active = true, kind = CategoryKind.EXPENSE),
                ),
            )

        assertEquals(CatalogViolation.CategoryPostingAccountInvalid, failure(validateProductCatalog(catalog)))
    }

    @Test
    fun activeLeafWithUserOwnedPostingAccountIsRejected() {
        val ownedExpense = account(AccountId("expense-owned"), AccountKind.EXPENSE, owned = true, real = true)
        val catalog =
            catalog(
                listOf(payment, ownedExpense),
                listOf(
                    category("expense-group", parent = null, posting = null, active = true, kind = CategoryKind.EXPENSE),
                    category("expense-leaf", parent = "expense-group", posting = "expense-owned", active = true, kind = CategoryKind.EXPENSE),
                ),
            )

        assertEquals(CatalogViolation.CategoryPostingAccountInvalid, failure(validateProductCatalog(catalog)))
    }

    @Test
    fun activeLeafWithCrossLedgerPostingAccountIsRejected() {
        val otherLedgerHidden = expenseHidden.copy(ledgerId = LedgerId("ledger-b"))
        val catalog =
            catalog(
                listOf(payment, otherLedgerHidden),
                listOf(
                    category("expense-group", parent = null, posting = null, active = true, kind = CategoryKind.EXPENSE),
                    category("expense-leaf", parent = "expense-group", posting = "expense-hidden", active = true, kind = CategoryKind.EXPENSE),
                ),
            )

        assertEquals(CatalogViolation.CategoryPostingAccountInvalid, failure(validateProductCatalog(catalog)))
    }

    @Test
    fun levelOneCategoryCarryingPostingAccountIsRejected() {
        val catalog =
            catalog(
                listOf(payment, expenseHidden),
                listOf(
                    category("expense-group", parent = null, posting = "expense-hidden", active = true, kind = CategoryKind.EXPENSE),
                ),
            )

        assertEquals(CatalogViolation.CategoryPostingAccountInvalid, failure(validateProductCatalog(catalog)))
    }

    @Test
    fun inactiveLeafWithoutPostingAccountIsAcceptedLikeFrozenGolden() {
        // golden/rules/rg-01.json:49 (expense-category-inactive) and rg-02.json:25
        // (income-category-inactive) are inactive leaves with posting_account_id == null.
        val catalog =
            catalog(
                listOf(payment, expenseHidden),
                listOf(
                    category("expense-group", parent = null, posting = null, active = true, kind = CategoryKind.EXPENSE),
                    category("expense-leaf", parent = "expense-group", posting = "expense-hidden", active = true, kind = CategoryKind.EXPENSE),
                    category("expense-inactive", parent = "expense-group", posting = null, active = false, kind = CategoryKind.EXPENSE),
                ),
            )

        assertIs<DomainResult.Success<Unit>>(validateProductCatalog(catalog))
    }

    @Test
    fun danglingParentAndOversizedDepthAreNotRejectedByLoadValidation() {
        // golden/rules/rg-10.json:120 (expense-category-meal-rg10): level 2, active, parent
        // absent from the catalog. Load validation must not reject it (spec section 4.4 scope).
        val catalog =
            catalog(
                listOf(payment, expenseHidden),
                listOf(
                    category("expense-meal-rg10", parent = "category-meal-parent-rg10", posting = "expense-hidden", active = true, kind = CategoryKind.EXPENSE),
                    category("expense-level-three", parent = "expense-meal-rg10", posting = "expense-hidden", active = true, kind = CategoryKind.EXPENSE),
                ),
            )

        assertIs<DomainResult.Success<Unit>>(validateProductCatalog(catalog))
    }

    @Test
    fun ledgerCatalogCreateStillOnlyRejectsDuplicateIds() {
        // spec 4.3/4.6: create is unchanged; the strict invariants live in validateProductCatalog
        // only. A shape-invalid catalog must still construct through create.
        val shapeInvalid =
            catalog(
                listOf(payment),
                listOf(
                    category("expense-leaf", parent = "missing-parent", posting = null, active = true, kind = CategoryKind.EXPENSE),
                ),
            )

        assertIs<LedgerCatalog>(shapeInvalid)
        assertEquals(
            CatalogViolation.CategoryPostingAccountInvalid,
            failure(validateProductCatalog(shapeInvalid)),
        )
    }

    private fun account(
        id: AccountId,
        kind: AccountKind,
        owned: Boolean,
        real: Boolean,
    ): Account =
        Account(
            id = id,
            ledgerId = ledgerId,
            kind = kind,
            currency = cny,
            ownedByUser = owned,
            realAccount = real,
            name = id.value,
        )

    private fun category(
        id: String,
        parent: String?,
        posting: String?,
        active: Boolean,
        kind: CategoryKind,
    ): Category =
        Category(
            id = CategoryId(id),
            ledgerId = ledgerId,
            parentId = parent?.let(::CategoryId),
            postingAccountId = posting?.let(::AccountId),
            active = active,
            kind = kind,
            name = id,
        )

    private fun catalog(
        accounts: List<Account>,
        categories: List<Category>,
    ): LedgerCatalog = success(LedgerCatalog.create(accounts, categories))
}
