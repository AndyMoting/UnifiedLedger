package com.unifiedledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class OrdinaryExpenseValidationTest {
    private val fixture = Rg01Fixture()

    @Test
    fun rejectsZeroAmountWithTypedViolation() {
        val result =
            createAssetPaidOrdinaryExpense(
                catalog = fixture.catalog,
                command =
                    fixture.command.copy(
                        amount = Money.ofMinor(0L, fixture.cny),
                    ),
                ids = fixture.expenseIds,
            )

        assertEquals(
            OrdinaryExpenseViolation.AmountMustBePositive,
            failure(result),
        )
    }

    @Test
    fun rejectsNegativeOneMinorUnitWithTypedViolation() {
        val result =
            createAssetPaidOrdinaryExpense(
                catalog = fixture.catalog,
                command =
                    fixture.command.copy(
                        amount = Money.ofMinor(-1L, fixture.cny),
                    ),
                ids = fixture.expenseIds,
            )

        assertEquals(
            OrdinaryExpenseViolation.AmountMustBePositive,
            failure(result),
        )
    }

    @Test
    fun rejectsPrimaryCategoryWithTypedViolation() {
        val primaryCategoryId = fixture.categories.single { it.parentId == null }.id
        val result =
            createAssetPaidOrdinaryExpense(
                catalog = fixture.catalog,
                command = fixture.command.copy(categoryId = primaryCategoryId),
                ids = fixture.expenseIds,
            )

        assertEquals(
            OrdinaryExpenseViolation.SecondaryCategoryRequired,
            failure(result),
        )
    }

    @Test
    fun classifiesInactiveSecondaryBeforeItsMissingPostingAccount() {
        val primaryCategoryId = fixture.categories.single { it.parentId == null }.id
        val inactiveCategoryId = CategoryId("expense-category-inactive")
        val catalog =
            success(
                LedgerCatalog.create(
                    accounts = fixture.accounts,
                    categories =
                        fixture.categories +
                            Category(
                                id = inactiveCategoryId,
                                ledgerId = fixture.ledgerId,
                                parentId = primaryCategoryId,
                                postingAccountId = null,
                                active = false,
                            ),
                ),
            )
        val result =
            createAssetPaidOrdinaryExpense(
                catalog = catalog,
                command = fixture.command.copy(categoryId = inactiveCategoryId),
                ids = fixture.expenseIds,
            )

        assertEquals(
            OrdinaryExpenseViolation.CategoryInactive,
            failure(result),
        )
    }

    @Test
    fun doesNotClassifyAnInactiveCategoryWithMissingParentAsCategoryInactive() {
        val invalidCategoryId = CategoryId("expense-category-invalid-parent")
        val catalog =
            success(
                LedgerCatalog.create(
                    accounts = fixture.accounts,
                    categories =
                        fixture.categories +
                            Category(
                                id = invalidCategoryId,
                                ledgerId = fixture.ledgerId,
                                parentId = CategoryId("expense-category-missing-parent"),
                                postingAccountId = null,
                                active = false,
                            ),
                ),
            )
        val result =
            createAssetPaidOrdinaryExpense(
                catalog = catalog,
                command = fixture.command.copy(categoryId = invalidCategoryId),
                ids = fixture.expenseIds,
            )
        val violation = failure(result)

        assertNotEquals(OrdinaryExpenseViolation.CategoryInactive, violation)
    }

    @Test
    fun rejectsIncomeCategoryEvenWhenItPointsAtAnExpenseAccount() {
        val parent = CategoryId("income-parent")
        val child = CategoryId("income-child")
        val catalog =
            success(
                LedgerCatalog.create(
                    accounts = fixture.accounts,
                    categories =
                        fixture.categories +
                            listOf(
                                Category(parent, fixture.ledgerId, null, null, true, CategoryKind.INCOME),
                                Category(child, fixture.ledgerId, parent, fixture.command.categoryId.let { fixture.categories.single { category -> category.id == it }.postingAccountId }, true, CategoryKind.INCOME),
                            ),
                ),
            )

        assertEquals(
            DomainViolation.InvalidOrdinaryExpense,
            failure(createAssetPaidOrdinaryExpense(catalog, fixture.command.copy(categoryId = child), fixture.expenseIds)),
        )
    }
}
