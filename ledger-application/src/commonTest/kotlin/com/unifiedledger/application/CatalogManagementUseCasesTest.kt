package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CatalogAdmissionRejection
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * P7-01 application command-level evidence (spec sections 6.1/6.3): request snapshot
 * canonicalization and fingerprint separation, the nine commands' payload mapping, the
 * failure-code mapping, read projections, and V-2 admission revalidation.
 */
class CatalogManagementUseCasesTest {
    private val ledgerId = LedgerId("ledger-a")
    private val cny = CurrencyUnit("CNY", 2)

    @Test
    fun canonicalSnapshotIsStableAndIdentityRelevant() {
        val first = canonicalRequestSnapshot(CatalogCommandPayload.CreateAccount("现金", AccountKind.ASSET))
        val second = canonicalRequestSnapshot(CatalogCommandPayload.CreateAccount("现金", AccountKind.ASSET))
        val different = canonicalRequestSnapshot(CatalogCommandPayload.CreateAccount("现金", AccountKind.LIABILITY))
        assertEquals(first, second)
        assertEquals(false, first == different)
        assertEquals(catalogInputFingerprint(first), catalogInputFingerprint(second))
        assertEquals(false, catalogInputFingerprint(first) == catalogInputFingerprint(different))
    }

    @Test
    fun everyCommandProducesItsOwnStableSnapshotWithCommandName() {
        val payloads =
            listOf(
                CatalogCommandPayload.CreateAccount("n", AccountKind.ASSET),
                CatalogCommandPayload.RenameAccount(AccountId("a"), "n"),
                CatalogCommandPayload.SetAccountActive(AccountId("a"), true),
                CatalogCommandPayload.CreateCategoryGroup(CategoryKind.EXPENSE, "g", "c"),
                CatalogCommandPayload.AppendCategoryChild(CategoryId("p"), "c"),
                CatalogCommandPayload.RenameCategory(CategoryId("c"), "n"),
                CatalogCommandPayload.SetCategoryActive(CategoryId("c"), false),
                CatalogCommandPayload.DeleteCategory(CategoryId("c")),
                CatalogCommandPayload.EnableCategoryGroup(CategoryId("p")),
            )
        val snapshots = payloads.map { canonicalRequestSnapshot(it) }
        assertEquals(payloads.size, snapshots.toSet().size)
        payloads.forEachIndexed { index, payload ->
            assertEquals(true, snapshots[index].contains(payload.commandName))
        }
    }

    @Test
    fun failureCodeMappingCoversEverySpecCode() {
        assertEquals(CatalogFailureCode.CATALOG_NAME_EMPTY, CatalogFailureCode.of(com.unifiedledger.domain.CatalogViolation.CatalogNameEmpty))
        assertEquals(CatalogFailureCode.CATALOG_NAME_TOO_LONG, CatalogFailureCode.of(com.unifiedledger.domain.CatalogViolation.CatalogNameTooLong))
        assertEquals(CatalogFailureCode.CATALOG_NAME_INVALID, CatalogFailureCode.of(com.unifiedledger.domain.CatalogViolation.CatalogNameInvalid))
        assertEquals(CatalogFailureCode.CATALOG_NAME_CONFLICT, CatalogFailureCode.of(com.unifiedledger.domain.CatalogViolation.CatalogNameConflict))
        assertEquals(CatalogFailureCode.CATALOG_OBJECT_NOT_FOUND, CatalogFailureCode.of(com.unifiedledger.domain.CatalogViolation.CatalogObjectNotFound))
        assertEquals(CatalogFailureCode.ACCOUNT_NOT_MANAGEABLE, CatalogFailureCode.of(com.unifiedledger.domain.CatalogViolation.AccountNotManageable))
        assertEquals(CatalogFailureCode.ACCOUNT_KIND_NOT_MANAGEABLE, CatalogFailureCode.of(com.unifiedledger.domain.CatalogViolation.AccountKindNotManageable))
        assertEquals(CatalogFailureCode.CATEGORY_NOT_MANAGEABLE, CatalogFailureCode.of(com.unifiedledger.domain.CatalogViolation.CategoryNotManageable))
        assertEquals(CatalogFailureCode.CATEGORY_LEVEL_NOT_SUPPORTED, CatalogFailureCode.of(com.unifiedledger.domain.CatalogViolation.CategoryLevelNotSupported))
        assertEquals(CatalogFailureCode.CATEGORY_PARENT_REQUIRED, CatalogFailureCode.of(com.unifiedledger.domain.CatalogViolation.CategoryParentRequired))
        assertEquals(CatalogFailureCode.CATEGORY_PARENT_CROSS_LEDGER, CatalogFailureCode.of(com.unifiedledger.domain.CatalogViolation.CategoryParentCrossLedger))
        assertEquals(CatalogFailureCode.CATEGORY_POSTING_ACCOUNT_INVALID, CatalogFailureCode.of(com.unifiedledger.domain.CatalogViolation.CategoryPostingAccountInvalid))
        assertEquals(CatalogFailureCode.CATEGORY_HAS_NO_ACTIVE_CHILD, CatalogFailureCode.of(com.unifiedledger.domain.CatalogViolation.LastActiveChildCategory))
        assertEquals(CatalogFailureCode.CATEGORY_HAS_REFERENCES, CatalogFailureCode.of(com.unifiedledger.domain.CatalogViolation.CategoryHasReferences))
        assertEquals(CatalogFailureCode.PARENT_INACTIVE, CatalogFailureCode.of(com.unifiedledger.domain.CatalogViolation.ParentInactive))
    }

    @Test
    fun stableFailureCodeLiteralsMatchTheFrozenSpecNames() {
        // Spec section 6.3 fixes these exact literals; consumers compare [code], not the enum name.
        assertEquals("CatalogNameEmpty", CatalogFailureCode.CATALOG_NAME_EMPTY.code)
        assertEquals("CatalogNameTooLong", CatalogFailureCode.CATALOG_NAME_TOO_LONG.code)
        assertEquals("CatalogNameInvalid", CatalogFailureCode.CATALOG_NAME_INVALID.code)
        assertEquals("CatalogNameConflict", CatalogFailureCode.CATALOG_NAME_CONFLICT.code)
        assertEquals("CatalogVersionConflict", CatalogFailureCode.CATALOG_VERSION_CONFLICT.code)
        assertEquals("RequestIdentityConflict", CatalogFailureCode.REQUEST_IDENTITY_CONFLICT.code)
        assertEquals("CatalogObjectNotFound", CatalogFailureCode.CATALOG_OBJECT_NOT_FOUND.code)
        assertEquals("AccountNotManageable", CatalogFailureCode.ACCOUNT_NOT_MANAGEABLE.code)
        assertEquals("AccountKindNotManageable", CatalogFailureCode.ACCOUNT_KIND_NOT_MANAGEABLE.code)
        assertEquals("CategoryNotManageable", CatalogFailureCode.CATEGORY_NOT_MANAGEABLE.code)
        assertEquals("CategoryLevelNotSupported", CatalogFailureCode.CATEGORY_LEVEL_NOT_SUPPORTED.code)
        assertEquals("CategoryParentCrossLedger", CatalogFailureCode.CATEGORY_PARENT_CROSS_LEDGER.code)
        assertEquals("CategoryParentRequired", CatalogFailureCode.CATEGORY_PARENT_REQUIRED.code)
        assertEquals("CategoryPostingAccountInvalid", CatalogFailureCode.CATEGORY_POSTING_ACCOUNT_INVALID.code)
        assertEquals("CategoryHasNoActiveChild", CatalogFailureCode.CATEGORY_HAS_NO_ACTIVE_CHILD.code)
        assertEquals("CategoryHasReferences", CatalogFailureCode.CATEGORY_HAS_REFERENCES.code)
        assertEquals("ParentInactive", CatalogFailureCode.PARENT_INACTIVE.code)
        assertEquals("CatalogBootstrapUnknownReference", CatalogFailureCode.CATALOG_BOOTSTRAP_UNKNOWN_REFERENCE.code)
        assertEquals("CatalogConstraintViolation", CatalogFailureCode.CATALOG_CONSTRAINT_VIOLATION.code)
    }

    @Test
    fun manageableProjectionExcludesHiddenAndSystemAccountsAndUsesNames() {
        val authority =
            CatalogAuthority(
                ledgerId = ledgerId,
                catalog = catalog(),
                catalogVersion = 3L,
            )
        val accounts = projectManageableAccounts(authority) { id -> if (id == AccountId("asset-payment")) 12_345L else null }
        assertEquals(listOf("asset-payment"), accounts.map { it.accountId.value })
        assertEquals("支付账户", accounts.single().name)
        assertEquals(AccountKind.ASSET, accounts.single().kind)
        assertEquals(12_345L, accounts.single().balanceMinorUnits)
    }

    @Test
    fun snapshotProjectionCarriesVersionAndCategoryTree() {
        val snapshot = projectCatalogSnapshot(CatalogAuthority(ledgerId, catalog(), 7L))
        assertEquals(7L, snapshot.catalogVersion)
        assertEquals(1, snapshot.manageableAccounts.size)
        assertEquals(2, snapshot.categories.size)
        assertEquals("餐饮", snapshot.categories.single { it.categoryId == CategoryId("expense-group") }.name)
    }

    @Test
    fun authoritativeOptionsProviderReflectsTheLatestCatalogVersionWithoutRestart() {
        var current = CatalogAuthority(ledgerId, catalog(), 1L)
        val provider = QueryAuthoritativeManualExpenseOptions(CatalogAuthorityReader { current }, ledgerId)
        assertEquals(listOf("支付账户"), provider.queryOptions().paymentAccounts.map { it.label })

        val renamed =
            catalog().let { base ->
                val accounts = base.accounts.map { if (it.id == AccountId("asset-payment")) it.copy(name = "现金") else it }
                when (val result = LedgerCatalog.create(accounts, base.categories)) {
                    is DomainResult.Success -> result.value
                    is DomainResult.Failure -> error("rebuilt catalog must be valid")
                }
            }
        current = CatalogAuthority(ledgerId, renamed, 2L)
        assertEquals(listOf("现金"), provider.queryOptions().paymentAccounts.map { it.label })
    }

    @Test
    fun commandMintsIndependentRequestIdAndMapsTypedFailure() {
        val requests = mutableListOf<CatalogCommandRequest>()
        val port =
            CatalogManagementCommitPort { request, apply ->
                requests.add(request)
                when (val result = apply(CatalogAuthority(ledgerId, catalog(), 1L))) {
                    is DomainResult.Failure -> CatalogCommandResult.Rejected(CatalogFailureCode.of(result.violation))
                    is DomainResult.Success ->
                        CatalogCommandResult.Accepted(
                            CatalogCommandReceipt(
                                requestId = request.requestId,
                                outcome = CatalogReceiptOutcome.ACCEPTED,
                                newCatalogVersion = request.expectedCatalogVersion + 1,
                            ),
                        )
                }
            }
        val ids =
            CatalogEntityIdSource {
                CatalogEntityIds(
                    manageableAccountId = AccountId("asset-new"),
                    postingAccountId = AccountId("expense-hidden-new"),
                    parentCategoryId = CategoryId("group-new"),
                    childCategoryId = CategoryId("child-new"),
                )
            }
        var counter = 0
        val command =
            ExecuteCatalogCommand(
                commitPort = port,
                requestIdSource = CatalogManagementRequestIdSource { CatalogRequestId("request-${counter++}") },
                entityIdSource = ids,
                categoryReferenceProbe = CatalogCategoryReferenceProbe { _, _ -> false },
            )
        val accepted = assertIs<CatalogCommandResult.Accepted>(command.createAccount(ledgerId, "现金账户", AccountKind.ASSET, 1L))
        assertEquals("request-0", requests.single().requestId.value)
        assertEquals(2L, accepted.receipt.newCatalogVersion)
        val rejected = assertIs<CatalogCommandResult.Rejected>(command.renameAccount(ledgerId, AccountId("expense-hidden"), "x", 1L))
        assertEquals(CatalogFailureCode.ACCOUNT_NOT_MANAGEABLE, rejected.failureCode)
    }

    @Test
    fun admissionRevalidationRejectsInactiveOrUnknownReferences() {
        val base = catalog()
        assertEquals(null, validateManualExpenseAdmission(base, ledgerId, AccountId("asset-payment"), CategoryId("expense-leaf")))

        val inactiveAccount = base.withAccountActive("asset-payment", false)
        assertEquals(
            CatalogAdmissionViolation.PaymentAccountInactive,
            validateManualExpenseAdmission(inactiveAccount, ledgerId, AccountId("asset-payment"), CategoryId("expense-leaf")),
        )

        val inactiveCategory = base.withCategoryActive("expense-leaf", false)
        assertEquals(
            CatalogAdmissionViolation.CategoryInactive,
            validateManualExpenseAdmission(inactiveCategory, ledgerId, AccountId("asset-payment"), CategoryId("expense-leaf")),
        )

        assertEquals(
            CatalogAdmissionViolation.CategoryNotFound,
            validateManualExpenseAdmission(base, ledgerId, AccountId("asset-payment"), CategoryId("missing")),
        )
        assertEquals(
            CatalogAdmissionViolation.CategoryNotLeaf,
            validateManualExpenseAdmission(base, ledgerId, AccountId("asset-payment"), CategoryId("expense-group")),
        )
        assertEquals(
            CatalogAdmissionViolation.PaymentAccountNotFound,
            validateManualExpenseAdmission(base, ledgerId, AccountId("missing"), CategoryId("expense-leaf")),
        )
    }

    @Test
    fun admissionReaderNullCatalogFailsClosedInTheFactory() {
        var delegated = false
        val factory =
            CatalogAdmissionExpenseTransactionFactory(
                admissionReader = CatalogAdmissionReader { null },
                delegate = { _, _ ->
                    delegated = true
                    error("must not be reached")
                },
            )
        val result =
            factory.create(
                ManualExpenseRequestSnapshot(
                    ledgerId = ledgerId,
                    amount = Money.ofMinor(100L, cny),
                    categoryId = CategoryId("expense-leaf"),
                    paymentAccountId = AccountId("asset-payment"),
                    occurredAt = kotlin.time.Instant.parse("2026-01-01T00:00:00Z"),
                    note = "",
                ),
                com.unifiedledger.application.ConfirmedManualExpenseCommitIds(
                    confirmationId = ConfirmationId("confirmation-1"),
                    expenseIds =
                        com.unifiedledger.domain.AssetPaidOrdinaryExpenseIds(
                            transactionId = com.unifiedledger.domain.TransactionId("tx-1"),
                            versionId = com.unifiedledger.domain.TransactionVersionId("version-1"),
                            postingSetId = com.unifiedledger.domain.PostingSetId("set-1"),
                            expensePostingId = com.unifiedledger.domain.PostingId("posting-1"),
                            paymentPostingId = com.unifiedledger.domain.PostingId("posting-2"),
                        ),
                ),
            )
        assertIs<DomainResult.Failure>(result)
        assertEquals(false, delegated)
    }

    @Test
    fun admissionFactoryMapsEachRevalidationShapeToADistinguishableTypedRejection() {
        val delegate =
            ConfirmedExpenseTransactionFactory { _, _ ->
                error("the revalidating factory must not delegate a rejected reference")
            }

        fun factoryFor(catalog: LedgerCatalog?) = CatalogAdmissionExpenseTransactionFactory(admissionReader = CatalogAdmissionReader { catalog }, delegate = delegate)

        fun createWith(
            catalog: LedgerCatalog?,
            account: String,
            category: String,
        ): DomainResult<ConfirmedManualExpenseCommit> = factoryFor(catalog).create(admissionSnapshot(account, category), admissionCommitIds())

        val base = catalog()
        assertEquals(
            CatalogAdmissionRejection.PaymentAccountInactive,
            admissionFailure(createWith(base.withAccountActive("asset-payment", false), "asset-payment", "expense-leaf")),
        )
        // The hidden EXPENSE posting account is not an ASSET payment account.
        assertEquals(
            CatalogAdmissionRejection.PaymentAccountWrongKind,
            admissionFailure(createWith(base, "expense-hidden", "expense-leaf")),
        )
        assertEquals(
            CatalogAdmissionRejection.PaymentAccountNotFound,
            admissionFailure(createWith(base, "missing", "expense-leaf")),
        )
        assertEquals(
            CatalogAdmissionRejection.CategoryNotFound,
            admissionFailure(createWith(base, "asset-payment", "missing")),
        )
        assertEquals(
            CatalogAdmissionRejection.CategoryNotLeaf,
            admissionFailure(createWith(base, "asset-payment", "expense-group")),
        )
        assertEquals(
            CatalogAdmissionRejection.CategoryInactive,
            admissionFailure(createWith(base.withCategoryActive("expense-leaf", false), "asset-payment", "expense-leaf")),
        )
        // An ASSET account that is neither owned by the user nor a real account is not a valid
        // payment account (manageability shape, not kind/inactive).
        val notManageable =
            catalogOf(
                accounts =
                    listOf(
                        Account(AccountId("asset-payment"), ledgerId, AccountKind.ASSET, cny, true, true, name = "支付账户"),
                        Account(AccountId("expense-hidden"), ledgerId, AccountKind.EXPENSE, cny, false, false, name = "餐饮过账"),
                        Account(AccountId("asset-system"), ledgerId, AccountKind.ASSET, cny, false, false, name = "系统资产"),
                    ),
                categories = null,
            )
        assertEquals(
            CatalogAdmissionRejection.PaymentAccountNotManageableFinancial,
            admissionFailure(createWith(notManageable, "asset-system", "expense-leaf")),
        )
        // A kind-mismatched category (INCOME leaf) is its own token.
        val incomeLeaf =
            catalogOf(
                accounts = null,
                categories =
                    listOf(
                        Category(CategoryId("income-leaf"), ledgerId, CategoryId("expense-group"), AccountId("expense-hidden"), true, CategoryKind.INCOME, "副业"),
                    ),
            )
        assertEquals(
            CatalogAdmissionRejection.CategoryKindMismatch,
            admissionFailure(createWith(incomeLeaf, "asset-payment", "income-leaf")),
        )
        // An unloadable catalog fails closed with its own token rather than a member violation.
        assertEquals(
            CatalogAdmissionRejection.CatalogUnavailable,
            admissionFailure(createWith(null, "asset-payment", "expense-leaf")),
        )
    }

    private fun admissionSnapshot(
        account: String,
        category: String,
    ): ManualExpenseRequestSnapshot =
        ManualExpenseRequestSnapshot(
            ledgerId = ledgerId,
            amount = Money.ofMinor(100L, cny),
            categoryId = CategoryId(category),
            paymentAccountId = AccountId(account),
            occurredAt = kotlin.time.Instant.parse("2026-01-01T00:00:00Z"),
            note = "",
        )

    private fun admissionCommitIds(): ConfirmedManualExpenseCommitIds =
        ConfirmedManualExpenseCommitIds(
            confirmationId = ConfirmationId("confirmation-admission"),
            expenseIds =
                com.unifiedledger.domain.AssetPaidOrdinaryExpenseIds(
                    transactionId = com.unifiedledger.domain.TransactionId("tx-admission"),
                    versionId = com.unifiedledger.domain.TransactionVersionId("version-admission"),
                    postingSetId = com.unifiedledger.domain.PostingSetId("set-admission"),
                    expensePostingId = com.unifiedledger.domain.PostingId("posting-admission-expense"),
                    paymentPostingId = com.unifiedledger.domain.PostingId("posting-admission-payment"),
                ),
        )

    private fun admissionFailure(result: DomainResult<ConfirmedManualExpenseCommit>): com.unifiedledger.domain.DomainViolation = (assertIs<DomainResult.Failure>(result)).violation

    private fun catalogOf(
        accounts: List<Account>?,
        categories: List<Category>?,
    ): LedgerCatalog {
        val base = catalog()
        return when (
            val result =
                LedgerCatalog.create(
                    accounts = accounts ?: base.accounts,
                    categories = categories ?: base.categories,
                )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> error("test catalog must be valid")
        }
    }

    private fun catalog(): LedgerCatalog =
        when (
            val result =
                LedgerCatalog.create(
                    accounts =
                        listOf(
                            Account(AccountId("asset-payment"), ledgerId, AccountKind.ASSET, cny, true, true, name = "支付账户"),
                            Account(AccountId("expense-hidden"), ledgerId, AccountKind.EXPENSE, cny, false, false, name = "餐饮过账"),
                        ),
                    categories =
                        listOf(
                            Category(CategoryId("expense-group"), ledgerId, null, null, true, name = "餐饮"),
                            Category(CategoryId("expense-leaf"), ledgerId, CategoryId("expense-group"), AccountId("expense-hidden"), true, name = "早餐"),
                        ),
                )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> error("test catalog must be valid")
        }

    private fun LedgerCatalog.withAccountActive(
        id: String,
        active: Boolean,
    ): LedgerCatalog = rebuild(accounts = accounts.map { if (it.id.value == id) it.copy(active = active) else it }, categories = categories)

    private fun LedgerCatalog.withCategoryActive(
        id: String,
        active: Boolean,
    ): LedgerCatalog = rebuild(accounts = accounts, categories = categories.map { if (it.id.value == id) it.copy(active = active) else it })

    private fun LedgerCatalog.rebuild(
        accounts: List<Account>,
        categories: List<Category>,
    ): LedgerCatalog =
        when (val result = LedgerCatalog.create(accounts, categories)) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> error("rebuilt catalog must be valid")
        }
}
