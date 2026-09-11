package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.CatalogCategoryReferenceProbe
import com.unifiedledger.application.CatalogCommandResult
import com.unifiedledger.application.CatalogEntityIdSource
import com.unifiedledger.application.CatalogFailureCode
import com.unifiedledger.application.CatalogManagementRequestIdSource
import com.unifiedledger.application.CatalogRequestId
import com.unifiedledger.application.ExecuteCatalogCommand
import com.unifiedledger.application.QueryManageableAccounts
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.LedgerId
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * P7-01 catalog store evidence (spec sections 5.3/6.1/6.3): idempotent bootstrap with
 * fail-closed unknown references, claim/replay/identity-conflict command semantics, version
 * plus one, and the manageable projection.
 */
class SqlDelightCatalogStoreTest {
    private val ledgerId = LedgerId("ledger-a")

    @Test
    fun bootstrapSeedsDefaultCatalogOnceAndIsIdempotent() {
        withStore { store, driver ->
            val first = assertIs<CatalogBootstrapResult.Seeded>(store.bootstrap(ledgerId, defaultCatalogSeed()))
            assertEquals(1L, first.authority.catalogVersion)
            assertEquals(
                listOf("asset-payment-local", "expense-account-local"),
                first.authority.catalog.accounts
                    .map { it.id.value },
            )

            // Second call must not overwrite, append or bump the version.
            val second = assertIs<CatalogBootstrapResult.AlreadyInitialized>(store.bootstrap(ledgerId, defaultCatalogSeed()))
            assertEquals(1L, second.authority.catalogVersion)
            assertEquals(2L, queryLong(driver, "SELECT count(*) FROM catalog_account WHERE ledger_id = 'ledger-a'"))
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM catalog_version WHERE ledger_id = 'ledger-a'"))
        }
    }

    @Test
    fun bootstrapFailsClosedWithZeroWritesWhenAnExistingPostingReferenceIsUnknown() {
        withStore { store, driver ->
            insertPosting(driver, accountId = "account-not-seeded")

            val result = store.bootstrap(ledgerId, defaultCatalogSeed())
            assertEquals(CatalogBootstrapResult.UnknownReference, result)
            assertEquals(0L, queryLong(driver, "SELECT count(*) FROM catalog_account WHERE ledger_id = 'ledger-a'"))
            assertEquals(0L, queryLong(driver, "SELECT count(*) FROM catalog_category WHERE ledger_id = 'ledger-a'"))
            assertEquals(0L, queryLong(driver, "SELECT count(*) FROM catalog_name_history WHERE ledger_id = 'ledger-a'"))
            assertEquals(0L, queryLong(driver, "SELECT count(*) FROM catalog_version WHERE ledger_id = 'ledger-a'"))
        }
    }

    @Test
    fun bootstrapFailsClosedWhenAnExistingPostingCategoryReferenceIsUnknown() {
        withStore { store, driver ->
            insertPosting(driver, accountId = "asset-payment-local")
            driver.execute(
                null,
                "INSERT INTO rg03_transfer_posting_semantic VALUES ('ledger-a','p-x','TRANSFER_FEE','category-unknown-rg03',0)",
                0,
            )

            assertEquals(CatalogBootstrapResult.UnknownReference, store.bootstrap(ledgerId, defaultCatalogSeed()))
            assertEquals(0L, queryLong(driver, "SELECT count(*) FROM catalog_account WHERE ledger_id = 'ledger-a'"))
            assertEquals(0L, queryLong(driver, "SELECT count(*) FROM catalog_category WHERE ledger_id = 'ledger-a'"))
            assertEquals(0L, queryLong(driver, "SELECT count(*) FROM catalog_name_history WHERE ledger_id = 'ledger-a'"))
        }
    }

    @Test
    fun bootstrapRetryAndConcurrentOpenConvergeToTheSameCatalogWithoutDuplicateRows() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, migrationProperties())
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val firstStore = SqlDelightCatalogStore(database, driver)
            val secondStore = SqlDelightCatalogStore(database, driver)

            val first = assertIs<CatalogBootstrapResult.Seeded>(firstStore.bootstrap(ledgerId, defaultCatalogSeed()))
            // A second composition root opening the same file converges on the same authority.
            val second = assertIs<CatalogBootstrapResult.AlreadyInitialized>(secondStore.bootstrap(ledgerId, defaultCatalogSeed()))
            assertEquals(first.authority.catalogVersion, second.authority.catalogVersion)
            assertEquals(
                first.authority.catalog.accounts
                    .map { it.id.value },
                second.authority.catalog.accounts
                    .map { it.id.value },
            )
            assertEquals(
                first.authority.catalog.categories
                    .map { it.id.value },
                second.authority.catalog.categories
                    .map { it.id.value },
            )
            assertEquals(1L, second.authority.catalogVersion)

            // Exactly one seed group: no duplicated accounts, categories or name history.
            assertEquals(2L, queryLong(driver, "SELECT count(*) FROM catalog_account WHERE ledger_id = 'ledger-a'"))
            assertEquals(2L, queryLong(driver, "SELECT count(*) FROM catalog_category WHERE ledger_id = 'ledger-a'"))
            assertEquals(4L, queryLong(driver, "SELECT count(*) FROM catalog_name_history WHERE ledger_id = 'ledger-a'"))
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM catalog_version WHERE ledger_id = 'ledger-a'"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun bootstrapSeedsWhenOnlyAStrayVersionRowExistsWithoutCatalogRows() {
        withStore { store, driver ->
            // A racing writer that only got as far as the version row must not be mistaken for
            // an initialized ledger; bootstrap seeds and converges to one complete group.
            driver.execute(null, "INSERT INTO catalog_version(ledger_id, version) VALUES ('ledger-a', 1)", 0)

            val seeded = assertIs<CatalogBootstrapResult.Seeded>(store.bootstrap(ledgerId, defaultCatalogSeed()))
            assertEquals(1L, seeded.authority.catalogVersion)
            assertEquals(2L, queryLong(driver, "SELECT count(*) FROM catalog_account WHERE ledger_id = 'ledger-a'"))
            assertEquals(2L, queryLong(driver, "SELECT count(*) FROM catalog_category WHERE ledger_id = 'ledger-a'"))
            assertEquals(4L, queryLong(driver, "SELECT count(*) FROM catalog_name_history WHERE ledger_id = 'ledger-a'"))
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM catalog_version WHERE ledger_id = 'ledger-a'"))
        }
    }

    @Test
    fun createAccountCommandAdvancesVersionAndProjectionUsesNames() {
        withStore { store, _ ->
            assertIs<CatalogBootstrapResult.Seeded>(store.bootstrap(ledgerId, defaultCatalogSeed()))
            val command = commandExecutor(store, singleRequestId("request-create"), fixedIds())
            val result =
                assertIs<CatalogCommandResult.Accepted>(
                    command.createAccount(ledgerId, "现金账户", AccountKind.ASSET, expectedCatalogVersion = 1L),
                )
            assertEquals(2L, result.receipt.newCatalogVersion)
            val accountId = requireNotNull(result.receipt.createdManageableAccountId)

            val accounts = requireNotNull(QueryManageableAccounts(store).query(ledgerId))
            assertEquals(
                setOf("asset-payment-local", accountId.value),
                accounts.map { it.accountId.value }.toSet(),
            )
            assertEquals("现金账户", accounts.single { it.accountId == accountId }.name)
        }
    }

    @Test
    fun equivalentReplayReturnsTheOriginalReceiptWithZeroNewWrites() {
        withStore { store, driver ->
            assertIs<CatalogBootstrapResult.Seeded>(store.bootstrap(ledgerId, defaultCatalogSeed()))
            val command = commandExecutor(store, singleRequestId("request-replay"), fixedIds())
            val first =
                assertIs<CatalogCommandResult.Accepted>(
                    command.createAccount(ledgerId, "现金账户", AccountKind.ASSET, expectedCatalogVersion = 1L),
                )
            val replay =
                assertIs<CatalogCommandResult.NoChange>(
                    command.createAccount(ledgerId, "现金账户", AccountKind.ASSET, expectedCatalogVersion = 1L),
                )
            assertEquals(first.receipt, replay.receipt)
            assertEquals(3L, queryLong(driver, "SELECT count(*) FROM catalog_account WHERE ledger_id = 'ledger-a'"))
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM catalog_command_receipt WHERE ledger_id = 'ledger-a'"))
        }
    }

    @Test
    fun sameRequestIdWithADifferentSnapshotIsAnIdentityConflictWithZeroWrites() {
        withStore { store, driver ->
            assertIs<CatalogBootstrapResult.Seeded>(store.bootstrap(ledgerId, defaultCatalogSeed()))
            val command = commandExecutor(store, singleRequestId("request-conflict"), fixedIds())
            assertIs<CatalogCommandResult.Accepted>(
                command.createAccount(ledgerId, "现金账户", AccountKind.ASSET, expectedCatalogVersion = 1L),
            )
            val conflict =
                assertIs<CatalogCommandResult.Conflict>(
                    command.createAccount(ledgerId, "另一个账户", AccountKind.ASSET, expectedCatalogVersion = 2L),
                )
            assertEquals(CatalogFailureCode.REQUEST_IDENTITY_CONFLICT, conflict.failureCode)
            assertEquals(3L, queryLong(driver, "SELECT count(*) FROM catalog_account WHERE ledger_id = 'ledger-a'"))
        }
    }

    @Test
    fun staleExpectedVersionIsAConflictWithZeroWrites() {
        withStore { store, driver ->
            assertIs<CatalogBootstrapResult.Seeded>(store.bootstrap(ledgerId, defaultCatalogSeed()))
            val command = commandExecutor(store, singleRequestId("request-stale"), fixedIds())
            val conflict =
                assertIs<CatalogCommandResult.Conflict>(
                    command.createAccount(ledgerId, "现金账户", AccountKind.ASSET, expectedCatalogVersion = 99L),
                )
            assertEquals(CatalogFailureCode.CATALOG_VERSION_CONFLICT, conflict.failureCode)
            assertEquals(2L, queryLong(driver, "SELECT count(*) FROM catalog_account WHERE ledger_id = 'ledger-a'"))
            assertEquals(0L, queryLong(driver, "SELECT count(*) FROM catalog_command_request WHERE ledger_id = 'ledger-a'"))
        }
    }

    @Test
    fun typedRejectionRollsBackTheClaimSoTheIdentityIsRetryable() {
        withStore { store, driver ->
            assertIs<CatalogBootstrapResult.Seeded>(store.bootstrap(ledgerId, defaultCatalogSeed()))
            val command = commandExecutor(store, singleRequestId("request-retry"), fixedIds())
            val rejected =
                assertIs<CatalogCommandResult.Rejected>(
                    command.createAccount(ledgerId, "   ", AccountKind.ASSET, expectedCatalogVersion = 1L),
                )
            assertEquals(CatalogFailureCode.CATALOG_NAME_EMPTY, rejected.failureCode)
            assertEquals(0L, queryLong(driver, "SELECT count(*) FROM catalog_command_request WHERE ledger_id = 'ledger-a'"))
            // Same identity with a corrected payload now succeeds.
            assertIs<CatalogCommandResult.Accepted>(
                command.createAccount(ledgerId, "修正名称", AccountKind.ASSET, expectedCatalogVersion = 1L),
            )
        }
    }

    @Test
    fun createCategoryGroupWritesHiddenPostingAccountAndBothLevels() {
        withStore { store, _ ->
            assertIs<CatalogBootstrapResult.Seeded>(store.bootstrap(ledgerId, defaultCatalogSeed()))
            val command = commandExecutor(store, singleRequestId("request-group"), fixedIds())
            val result =
                assertIs<CatalogCommandResult.Accepted>(
                    command.createCategoryGroup(ledgerId, CategoryKind.EXPENSE, "交通", "地铁", expectedCatalogVersion = 1L),
                )
            assertEquals("expense-account-1", result.receipt.createdPostingAccountId?.value)
            assertEquals("expense-category-1", result.receipt.createdParentCategoryId?.value)
            assertEquals("expense-category-2", result.receipt.createdChildCategoryId?.value)

            val authority = requireNotNull(store.load(ledgerId))
            val posting = authority.catalog.accounts.single { it.id.value == "expense-account-1" }
            assertEquals(AccountKind.EXPENSE, posting.kind)
            assertEquals(false, posting.ownedByUser)
            assertEquals(false, posting.realAccount)
            val parent = authority.catalog.categories.single { it.id.value == "expense-category-1" }
            assertNull(parent.parentId)
            assertNull(parent.postingAccountId)
            val child = authority.catalog.categories.single { it.id.value == "expense-category-2" }
            assertEquals(CategoryId("expense-category-1"), child.parentId)
            assertEquals(AccountId("expense-account-1"), child.postingAccountId)
        }
    }

    @Test
    fun renameCategoryAppendsNameHistoryAndUpdatesCurrentName() {
        withStore { store, driver ->
            assertIs<CatalogBootstrapResult.Seeded>(store.bootstrap(ledgerId, defaultCatalogSeed()))
            val command = commandExecutor(store, singleRequestId("request-rename"), fixedIds())
            assertIs<CatalogCommandResult.Accepted>(
                command.renameCategory(ledgerId, CategoryId("expense-category-breakfast"), "早餐店", expectedCatalogVersion = 1L),
            )
            assertEquals(
                2L,
                queryLong(
                    driver,
                    "SELECT count(*) FROM catalog_name_history WHERE owner_kind = 'category' AND owner_id = 'expense-category-breakfast'",
                ),
            )
            assertEquals(
                "早餐店",
                queryString(
                    driver,
                    "SELECT name FROM catalog_name_history WHERE owner_kind = 'category' AND owner_id = 'expense-category-breakfast' AND status = 'CURRENT'",
                ),
            )
            assertEquals(
                "早餐店",
                requireNotNull(store.load(ledgerId))
                    .catalog.categories
                    .single { it.id == CategoryId("expense-category-breakfast") }
                    .name,
            )
        }
    }

    @Test
    fun deletingACategoryWithReferencesIsRejectedAndLeavesItInPlace() {
        withStore { store, driver ->
            assertIs<CatalogBootstrapResult.Seeded>(store.bootstrap(ledgerId, defaultCatalogSeed()))
            driver.execute(
                null,
                "INSERT INTO manual_expense_request VALUES ('ledger-a','request-ref',100,'CNY',2,'expense-category-breakfast','asset-payment-local','2026-01-01T00:00:00+08:00','','explicit_manual_save')",
                0,
            )
            val command = commandExecutor(store, singleRequestId("request-delete"), fixedIds())
            val rejected =
                assertIs<CatalogCommandResult.Rejected>(
                    command.deleteCategory(ledgerId, CategoryId("expense-category-breakfast"), expectedCatalogVersion = 1L),
                )
            assertEquals(CatalogFailureCode.CATEGORY_HAS_REFERENCES, rejected.failureCode)
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM catalog_category WHERE category_id = 'expense-category-breakfast'"))
        }
    }

    @Test
    fun deletingAnUnreferencedLeafRemovesItsHiddenPostingAccountCategoryAndNameHistory() {
        withStore { store, driver ->
            assertIs<CatalogBootstrapResult.Seeded>(store.bootstrap(ledgerId, defaultCatalogSeed()))
            val command = commandExecutor(store, singleRequestId("request-delete-leaf"), fixedIds())
            val accepted =
                assertIs<CatalogCommandResult.Accepted>(
                    command.deleteCategory(ledgerId, CategoryId("expense-category-breakfast"), expectedCatalogVersion = 1L),
                )
            assertEquals(2L, accepted.receipt.newCatalogVersion)
            // The leaf's hidden posting account (expense-account-local) is gone with the leaf.
            assertEquals(0L, queryLong(driver, "SELECT count(*) FROM catalog_category WHERE category_id = 'expense-category-breakfast'"))
            assertEquals(0L, queryLong(driver, "SELECT count(*) FROM catalog_account WHERE account_id = 'expense-account-local'"))
            assertEquals(
                0L,
                queryLong(
                    driver,
                    "SELECT count(*) FROM catalog_name_history WHERE (owner_kind = 'category' AND owner_id = 'expense-category-breakfast') OR (owner_kind = 'account' AND owner_id = 'expense-account-local')",
                ),
            )
            // The group and the seeded manageable account stay.
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM catalog_category WHERE category_id = 'expense-category-food'"))
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM catalog_account WHERE account_id = 'asset-payment-local'"))
            val authority = requireNotNull(store.load(ledgerId))
            assertEquals(listOf("asset-payment-local"), authority.catalog.accounts.map { it.id.value })
            assertEquals(listOf("expense-category-food"), authority.catalog.categories.map { it.id.value })
        }
    }

    @Test
    fun deletingAGroupCascadesChildHiddenAccountsWithTheGroup() {
        withStore { store, driver ->
            assertIs<CatalogBootstrapResult.Seeded>(store.bootstrap(ledgerId, defaultCatalogSeed()))
            val command = commandExecutor(store, singleRequestId("request-delete-group"), fixedIds())
            assertIs<CatalogCommandResult.Accepted>(
                command.deleteCategory(ledgerId, CategoryId("expense-category-food"), expectedCatalogVersion = 1L),
            )
            assertEquals(0L, queryLong(driver, "SELECT count(*) FROM catalog_category"))
            assertEquals(0L, queryLong(driver, "SELECT count(*) FROM catalog_account WHERE account_id = 'expense-account-local'"))
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM catalog_account WHERE account_id = 'asset-payment-local'"))
        }
    }

    @Test
    fun deactivatingTheOnlyActiveLeafIsRejectedByTheDomainRule() {
        withStore { store, _ ->
            assertIs<CatalogBootstrapResult.Seeded>(store.bootstrap(ledgerId, defaultCatalogSeed()))
            val command = commandExecutor(store, singleRequestId("request-deactivate"), fixedIds())
            val rejected =
                assertIs<CatalogCommandResult.Rejected>(
                    command.setCategoryActive(ledgerId, CategoryId("expense-category-breakfast"), false, expectedCatalogVersion = 1L),
                )
            assertEquals(CatalogFailureCode.CATEGORY_HAS_NO_ACTIVE_CHILD, rejected.failureCode)
        }
    }

    @Test
    fun receiptGuardRejectsRewritingAnAcceptedReceipt() {
        withStore { store, driver ->
            assertIs<CatalogBootstrapResult.Seeded>(store.bootstrap(ledgerId, defaultCatalogSeed()))
            val command = commandExecutor(store, singleRequestId("request-immutable"), fixedIds())
            assertIs<CatalogCommandResult.Accepted>(
                command.createAccount(ledgerId, "现金账户", AccountKind.ASSET, expectedCatalogVersion = 1L),
            )
            var threw = false
            try {
                driver.execute(
                    null,
                    "UPDATE catalog_command_receipt SET new_catalog_version = 99 WHERE request_id = 'request-immutable'",
                    0,
                )
            } catch (expected: java.sql.SQLException) {
                threw = true
            }
            assertEquals(true, threw)
        }
    }

    @Test
    fun submitRevalidationRejectsADraftWhoseCategoryWasDeactivatedWithZeroFormalWrites() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, migrationProperties())
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightCatalogStore(database, driver)
            assertIs<CatalogBootstrapResult.Seeded>(store.bootstrap(ledgerId, defaultCatalogSeed()))

            val catalog = requireNotNull(store.load(ledgerId)).catalog
            val delegate =
                com.unifiedledger.application.ConfirmedExpenseTransactionFactory { request, ids ->
                    when (
                        val created =
                            com.unifiedledger.domain.createAssetPaidOrdinaryExpense(
                                catalog = catalog,
                                command =
                                    com.unifiedledger.domain.AssetPaidOrdinaryExpenseCommand(
                                        ledgerId = request.ledgerId,
                                        amount = request.amount,
                                        categoryId = request.categoryId,
                                        paymentAccountId = request.paymentAccountId,
                                        times =
                                            com.unifiedledger.domain.TransactionTimes
                                                .collapsed(request.occurredAt),
                                    ),
                                ids = ids.expenseIds,
                            )
                    ) {
                        is com.unifiedledger.domain.DomainResult.Success ->
                            com.unifiedledger.domain.DomainResult.Success(
                                com.unifiedledger.application.ConfirmedManualExpenseCommit(
                                    confirmationId = ids.confirmationId,
                                    transaction = created.value,
                                ),
                            )
                        is com.unifiedledger.domain.DomainResult.Failure -> created
                    }
                }
            val factory =
                com.unifiedledger.application.CatalogAdmissionExpenseTransactionFactory(
                    admissionReader = store,
                    delegate = delegate,
                )
            val useCase =
                com.unifiedledger.application.ExecuteConfirmedManualExpense(
                    commitPort = SqlDelightConfirmedManualExpenseCommitPort(database, driver),
                    idSource =
                        com.unifiedledger.application.ConfirmedManualExpenseIdSource {
                            com.unifiedledger.application.ConfirmedManualExpenseCommitIds(
                                confirmationId = com.unifiedledger.application.ConfirmationId("confirmation-v2"),
                                expenseIds =
                                    com.unifiedledger.domain.AssetPaidOrdinaryExpenseIds(
                                        transactionId = com.unifiedledger.domain.TransactionId("tx-v2"),
                                        versionId = com.unifiedledger.domain.TransactionVersionId("version-v2"),
                                        postingSetId = com.unifiedledger.domain.PostingSetId("set-v2"),
                                        expensePostingId = com.unifiedledger.domain.PostingId("posting-v2-expense"),
                                        paymentPostingId = com.unifiedledger.domain.PostingId("posting-v2-bank"),
                                    ),
                            )
                        },
                    createFormalTransaction = factory,
                )

            // The draft was prepared against an active category; deactivate it behind the draft.
            driver.execute(
                null,
                "UPDATE catalog_category SET active = 0 WHERE category_id = 'expense-category-breakfast'",
                0,
            )

            val result =
                useCase.execute(
                    com.unifiedledger.application.ExplicitlyConfirmedManualExpense(
                        ledgerId = ledgerId,
                        requestId = com.unifiedledger.application.RequestId("request-v2-reject"),
                        amount =
                            com.unifiedledger.domain.Money
                                .ofMinor(100L, com.unifiedledger.domain.CurrencyUnit("CNY", 2)),
                        categoryId = CategoryId("expense-category-breakfast"),
                        paymentAccountId = AccountId("asset-payment-local"),
                        occurredAt = kotlin.time.Instant.parse("2026-01-15T00:30:00Z"),
                        note = "",
                        confirmation = com.unifiedledger.application.ExplicitManualSave,
                    ),
                )
            assertIs<com.unifiedledger.application.ConfirmedManualExpenseResult.Rejected>(result)
            assertEquals(0L, queryLong(driver, "SELECT count(*) FROM ledger_transaction"))
            assertEquals(0L, queryLong(driver, "SELECT count(*) FROM posting"))
            assertEquals(0L, queryLong(driver, "SELECT count(*) FROM manual_expense_request"))
        } finally {
            driver.close()
        }
    }

    private fun commandExecutor(
        store: SqlDelightCatalogStore,
        requestIdSource: CatalogManagementRequestIdSource,
        ids: CatalogEntityIdSource,
        references: CatalogCategoryReferenceProbe = store,
    ): ExecuteCatalogCommand =
        ExecuteCatalogCommand(
            commitPort = store,
            requestIdSource = requestIdSource,
            entityIdSource = ids,
            categoryReferenceProbe = references,
        )

    private fun singleRequestId(value: String): CatalogManagementRequestIdSource = CatalogManagementRequestIdSource { CatalogRequestId(value) }

    private fun fixedIds(): CatalogEntityIdSource =
        CatalogEntityIdSource {
            com.unifiedledger.application.CatalogEntityIds(
                manageableAccountId = AccountId("asset-managed-1"),
                postingAccountId = AccountId("expense-account-1"),
                parentCategoryId = CategoryId("expense-category-1"),
                childCategoryId = CategoryId("expense-category-2"),
            )
        }

    private fun withStore(block: (SqlDelightCatalogStore, JdbcSqliteDriver) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, migrationProperties())
        try {
            LedgerDatabase.Schema.create(driver)
            block(SqlDelightCatalogStore(LedgerDatabase(driver), driver), driver)
        } finally {
            driver.close()
        }
    }

    /** Seeds one postable ledger transaction whose posting references [accountId]. */
    private fun insertPosting(
        driver: JdbcSqliteDriver,
        accountId: String,
    ) {
        driver.execute(
            null,
            "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind, canonical_kind) VALUES ('tx-x','ledger-a','EXPENSE',NULL)",
            0,
        )
        driver.execute(null, "INSERT INTO posting_set VALUES ('set-x','ledger-a')", 0)
        driver.execute(
            null,
            "INSERT INTO transaction_version(version_id,transaction_id,ledger_id,version_number,posting_set_id,occurred_at,statistics_at,effective_at,note) VALUES ('v-x','tx-x','ledger-a',1,'set-x','2026-01-01T00:00:00+08:00','2026-01-01T00:00:00+08:00','2026-01-01T00:00:00+08:00',NULL)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO posting VALUES ('p-x','set-x','ledger-a',0,'$accountId',-100,'CNY',2)",
            0,
        )
    }

    private fun migrationProperties(): Properties =
        Properties().apply {
            setProperty("foreign_keys", "true")
        }
}

private fun queryLong(
    driver: JdbcSqliteDriver,
    sql: String,
): Long =
    driver
        .executeQuery(
            null,
            sql,
            { cursor ->
                check(cursor.next().value)
                app.cash.sqldelight.db.QueryResult
                    .Value(requireNotNull(cursor.getLong(0)))
            },
            0,
        ).value

private fun queryString(
    driver: JdbcSqliteDriver,
    sql: String,
): String =
    driver
        .executeQuery(
            null,
            sql,
            { cursor ->
                check(cursor.next().value)
                app.cash.sqldelight.db.QueryResult
                    .Value(requireNotNull(cursor.getString(0)))
            },
            0,
        ).value
