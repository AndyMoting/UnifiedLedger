package com.unifiedledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * P7-01 pure catalog lifecycle evidence (spec sections 4.6 / 6.1 / 6.3). Every transition is a
 * pure function over a [LedgerCatalog] returning [DomainResult]; no IO, Clock or random source
 * is involved and stable IDs never change.
 */
class CatalogLifecycleTest {
    private val ledgerId = LedgerId("ledger-a")
    private val cny = CurrencyUnit("CNY", 2)
    private val payment = AccountId("asset-payment")
    private val savings = AccountId("asset-savings")
    private val expenseHidden = AccountId("expense-hidden")
    private val group = CategoryId("expense-group")
    private val leaf = CategoryId("expense-leaf")
    private val leaf2 = CategoryId("expense-leaf-2")

    @Test
    fun normalizationTrimsAndCollapsesAsciiAndFullWidthSpacesOnly() {
        assertEquals(
            DomainResult.Success("食品 饮料"),
            normalizeCatalogName("  食品\u3000\u3000饮料  "),
        )
        assertEquals(DomainResult.Success("a b"), normalizeCatalogName(" \u3000a\u3000 b "))
    }

    @Test
    fun normalizationRejectsEveryUnicodeControlCharacterIncludingTabNewlineReturn() {
        // G6 ruling: only U+0020 and U+3000 may be collapsed; every other code point in
        // U+0000-U+001F (tab/newline/return included) and U+007F-U+009F is CatalogNameInvalid.
        val controls =
            listOf(
                "a\tb",
                "a\nb",
                "a\rb",
                "bad\u0000name",
                "a\u000bb",
                "a\u001fb",
                "a\u007fb",
                "a\u0085b",
                "a\u009fb",
            )
        controls.forEach { raw ->
            assertEquals(CatalogViolation.CatalogNameInvalid, failure(normalizeCatalogName(raw)), raw)
        }
    }

    @Test
    fun normalizationRejectsEmptyAndOverlongNames() {
        assertEquals(CatalogViolation.CatalogNameEmpty, failure(normalizeCatalogName("   \u3000 ")))
        assertEquals(
            CatalogViolation.CatalogNameTooLong,
            failure(normalizeCatalogName("x".repeat(CATALOG_MAX_NAME_CODE_POINTS + 1))),
        )
        assertIs<DomainResult.Success<String>>(normalizeCatalogName("x".repeat(CATALOG_MAX_NAME_CODE_POINTS)))
    }

    @Test
    fun createManagedAccountFixesKindCurrencyOwnershipAndZeroBalance() {
        val writes =
            success(
                createManagedAccount(
                    catalog = baseCatalog(),
                    ledgerId = ledgerId,
                    accountId = AccountId("asset-new"),
                    name = " 新账户 ",
                    kind = AccountKind.LIABILITY,
                    currency = cny,
                ),
            )
        val inserted = assertIs<CatalogWrite.InsertAccount>(writes.single()).account
        assertEquals(AccountId("asset-new"), inserted.id)
        assertEquals(AccountKind.LIABILITY, inserted.kind)
        assertEquals(cny, inserted.currency)
        assertEquals(true, inserted.ownedByUser)
        assertEquals(true, inserted.realAccount)
        assertEquals(null, inserted.systemRole)
        assertEquals(true, inserted.active)
        assertEquals("新账户", inserted.name)
    }

    @Test
    fun createManagedAccountRejectsNonFinancialKindAndDuplicateName() {
        assertEquals(
            CatalogViolation.AccountKindNotManageable,
            failure(
                createManagedAccount(baseCatalog(), ledgerId, AccountId("x"), "x", AccountKind.EXPENSE, cny),
            ),
        )
        assertEquals(
            CatalogViolation.CatalogNameConflict,
            failure(
                createManagedAccount(baseCatalog(), ledgerId, AccountId("asset-new"), "支付账户", AccountKind.ASSET, cny),
            ),
        )
    }

    @Test
    fun renameManagedAccountKeepsStableIdAndAppendsNameHistory() {
        val writes = success(renameManagedAccount(baseCatalog(), ledgerId, payment, " 新支付 "))
        assertEquals(CatalogWrite.SetAccountName(payment, "新支付"), writes[0])
        assertEquals(CatalogWrite.AppendNameHistory(CatalogOwnerKind.ACCOUNT, payment.value, "新支付"), writes[1])
    }

    @Test
    fun renameManagedAccountRejectsHiddenSystemAndUnknownAccounts() {
        assertEquals(
            CatalogViolation.AccountNotManageable,
            failure(renameManagedAccount(baseCatalog(), ledgerId, expenseHidden, "x")),
        )
        assertEquals(
            CatalogViolation.CatalogObjectNotFound,
            failure(renameManagedAccount(baseCatalog(), ledgerId, AccountId("missing"), "x")),
        )
    }

    @Test
    fun setAccountActiveTogglesOnlyTheManagedAccount() {
        assertEquals(
            listOf(CatalogWrite.SetAccountActive(payment, false)),
            success(setManagedAccountActive(baseCatalog(), ledgerId, payment, false)),
        )
    }

    @Test
    fun createCategoryGroupAtomicallyCreatesHiddenPostingAccountGroupAndFirstChild() {
        val writes =
            success(
                createCategoryGroup(
                    catalog = baseCatalog(),
                    ledgerId = ledgerId,
                    kind = CategoryKind.EXPENSE,
                    groupId = CategoryId("expense-food"),
                    groupName = "新增支出组",
                    childId = CategoryId("expense-breakfast"),
                    childName = "新增早餐",
                    postingAccountId = AccountId("expense-account-food"),
                    currency = cny,
                ),
            )
        val posting = assertIs<CatalogWrite.InsertAccount>(writes[0]).account
        assertEquals(AccountKind.EXPENSE, posting.kind)
        assertEquals(false, posting.ownedByUser)
        assertEquals(false, posting.realAccount)
        val parent = assertIs<CatalogWrite.InsertCategory>(writes[1]).category
        assertEquals(null, parent.parentId)
        assertEquals(null, parent.postingAccountId)
        val child = assertIs<CatalogWrite.InsertCategory>(writes[2]).category
        assertEquals(CategoryId("expense-food"), child.parentId)
        assertEquals(AccountId("expense-account-food"), child.postingAccountId)
    }

    @Test
    fun appendCategoryChildRequiresAGroupParentAndUniqueSiblingName() {
        assertEquals(
            CatalogViolation.CategoryLevelNotSupported,
            failure(
                appendCategoryChild(baseCatalog(), ledgerId, leaf, CategoryId("x"), "x", AccountId("h"), cny),
            ),
        )
        assertEquals(
            CatalogViolation.CatalogNameConflict,
            failure(
                appendCategoryChild(baseCatalog(), ledgerId, group, CategoryId("x"), "午餐", AccountId("h"), cny),
            ),
        )
        val writes =
            success(
                appendCategoryChild(baseCatalog(), ledgerId, group, CategoryId("expense-dinner"), "晚餐", AccountId("expense-hidden-dinner"), cny),
            )
        assertEquals(2, writes.size)
    }

    @Test
    fun appendCategoryChildRejectsUnknownAndCrossLedgerParentsAsParentCrossLedger() {
        assertEquals(
            CatalogViolation.CategoryParentCrossLedger,
            failure(
                appendCategoryChild(baseCatalog(), ledgerId, CategoryId("missing-parent"), CategoryId("x"), "x", AccountId("h"), cny),
            ),
        )
        // A parent owned by another ledger is not a parent of the request ledger.
        val crossLedger =
            catalog(
                accounts = baseCatalog().accounts,
                categories =
                    baseCatalog().categories +
                        Category(
                            CategoryId("foreign-group"),
                            LedgerId("ledger-b"),
                            parentId = null,
                            postingAccountId = null,
                            active = true,
                            name = "其他账本组",
                        ),
            )
        assertEquals(
            CatalogViolation.CategoryParentCrossLedger,
            failure(
                appendCategoryChild(crossLedger, ledgerId, CategoryId("foreign-group"), CategoryId("x"), "x", AccountId("h"), cny),
            ),
        )
    }

    @Test
    fun appendCategoryChildRequiresParentIdentityWhenTheParentIsNotAConfirmedGroup() {
        // D-066: a category that carries a posting account but no parent identity (a dangling
        // level-2 shape) cannot be confirmed as a level-1 group, so the command must not guess
        // its level; it returns CategoryParentRequired instead.
        val danglingParent =
            catalog(
                accounts = baseCatalog().accounts,
                categories =
                    baseCatalog().categories +
                        Category(
                            CategoryId("dangling-leaf"),
                            ledgerId,
                            parentId = null,
                            postingAccountId = expenseHidden,
                            active = true,
                            name = "悬空叶子",
                        ),
            )
        assertEquals(
            CatalogViolation.CategoryParentRequired,
            failure(
                appendCategoryChild(danglingParent, ledgerId, CategoryId("dangling-leaf"), CategoryId("x"), "x", AccountId("h"), cny),
            ),
        )
    }

    @Test
    fun renameCategoryEnforcesSiblingUniquenessAndStableId() {
        assertEquals(
            CatalogViolation.CatalogNameConflict,
            failure(renameManagedCategory(baseCatalog(), ledgerId, leaf, "午餐")),
        )
        val writes = success(renameManagedCategory(baseCatalog(), ledgerId, leaf, " 早 餐 "))
        assertEquals(
            listOf(CatalogWrite.AppendNameHistory(CatalogOwnerKind.CATEGORY, leaf.value, "早 餐")),
            writes,
        )
    }

    @Test
    fun deactivatingTheLastActiveLeafIsRejected() {
        assertEquals(
            CatalogViolation.LastActiveChildCategory,
            failure(setCategoryActive(leafOnlyCatalog(), ledgerId, leaf, false)),
        )
    }

    @Test
    fun deactivatingALeafWithASiblingStaysAllowed() {
        val writes = success(setCategoryActive(baseCatalog(), ledgerId, leaf, false))
        assertEquals(listOf(CatalogWrite.SetCategoryActive(leaf, false)), writes)
    }

    @Test
    fun activatingALeafRequiresAnActiveParent() {
        val inactiveParent = baseCatalog().copyCategories(groupActive = false)
        assertEquals(
            CatalogViolation.ParentInactive,
            failure(setCategoryActive(inactiveParent, ledgerId, leaf, true)),
        )
    }

    @Test
    fun deactivatingAGroupCascadesToEveryChild() {
        val writes = success(setCategoryActive(baseCatalog(), ledgerId, group, false))
        assertEquals(
            listOf(
                CatalogWrite.SetCategoryActive(group, false),
                CatalogWrite.SetCategoryActive(leaf, false),
                CatalogWrite.SetCategoryActive(leaf2, false),
            ),
            writes,
        )
    }

    @Test
    fun enableCategoryGroupActivatesGroupAndChildren() {
        val writes = success(enableCategoryGroup(baseCatalog(), ledgerId, group))
        assertEquals(
            listOf(
                CatalogWrite.SetCategoryActive(group, true),
                CatalogWrite.SetCategoryActive(leaf, true),
                CatalogWrite.SetCategoryActive(leaf2, true),
            ),
            writes,
        )
    }

    @Test
    fun deleteCategoryIsRejectedWhileReferencesExist() {
        assertEquals(
            CatalogViolation.CategoryHasReferences,
            failure(deleteManagedCategory(baseCatalog(), ledgerId, leaf) { true }),
        )
    }

    @Test
    fun deleteCategorySucceedsWhenNoReferencesExist() {
        val writes = success(deleteManagedCategory(baseCatalog(), ledgerId, leaf) { false })
        assertEquals(listOf(CatalogWrite.DeleteCategory(leaf)), writes)
    }

    @Test
    fun deleteGroupDeletesEveryChildThenTheGroup() {
        val writes = success(deleteManagedCategory(baseCatalog(), ledgerId, group) { false })
        assertEquals(
            listOf(
                CatalogWrite.DeleteCategory(leaf),
                CatalogWrite.DeleteCategory(leaf2),
                CatalogWrite.DeleteCategory(group),
            ),
            writes,
        )
    }

    @Test
    fun deleteGroupIsRejectedWhenAReferenceExistsOnAnyChild() {
        assertEquals(
            CatalogViolation.CategoryHasReferences,
            failure(deleteManagedCategory(baseCatalog(), ledgerId, group) { it == leaf2 }),
        )
    }

    private fun baseCatalog(): LedgerCatalog =
        catalog(
            accounts =
                listOf(
                    managed(payment, "支付账户"),
                    managed(savings, "储蓄"),
                    hidden(expenseHidden, AccountKind.EXPENSE, "餐饮过账"),
                ),
            categories =
                listOf(
                    Category(group, ledgerId, parentId = null, postingAccountId = null, active = true, name = "餐饮"),
                    Category(leaf, ledgerId, parentId = group, postingAccountId = expenseHidden, active = true, name = "早餐"),
                    Category(leaf2, ledgerId, parentId = group, postingAccountId = expenseHidden, active = true, name = "午餐"),
                ),
        )

    private fun leafOnlyCatalog(): LedgerCatalog =
        catalog(
            accounts = listOf(managed(payment, "支付账户"), hidden(expenseHidden, AccountKind.EXPENSE, "餐饮过账")),
            categories =
                listOf(
                    Category(group, ledgerId, parentId = null, postingAccountId = null, active = true, name = "餐饮"),
                    Category(leaf, ledgerId, parentId = group, postingAccountId = expenseHidden, active = true, name = "早餐"),
                ),
        )

    private fun LedgerCatalog.copyCategories(groupActive: Boolean): LedgerCatalog =
        catalog(
            accounts = accounts,
            categories = categories.map { if (it.id == group) it.copy(active = groupActive) else it },
        )

    private fun managed(
        id: AccountId,
        name: String,
    ): Account = Account(id, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true, name = name)

    private fun hidden(
        id: AccountId,
        kind: AccountKind,
        name: String,
    ): Account = Account(id, ledgerId, kind, cny, ownedByUser = false, realAccount = false, name = name)

    private fun catalog(
        accounts: List<Account>,
        categories: List<Category>,
    ): LedgerCatalog = success(LedgerCatalog.create(accounts, categories))
}
