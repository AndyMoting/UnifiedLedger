package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.CATALOG_MANAGED_CURRENCY
import com.unifiedledger.application.CatalogAdmissionReader
import com.unifiedledger.application.CatalogAuthority
import com.unifiedledger.application.CatalogAuthorityReader
import com.unifiedledger.application.CatalogCategoryReferenceProbe
import com.unifiedledger.application.CatalogCommandReceipt
import com.unifiedledger.application.CatalogCommandRequest
import com.unifiedledger.application.CatalogCommandResult
import com.unifiedledger.application.CatalogFailureCode
import com.unifiedledger.application.CatalogManagementCommitPort
import com.unifiedledger.application.CatalogReceiptOutcome
import com.unifiedledger.application.DEFAULT_EXPENSE_GROUP_ID
import com.unifiedledger.application.DEFAULT_EXPENSE_GROUP_NAME
import com.unifiedledger.application.DEFAULT_EXPENSE_LEAF_ID
import com.unifiedledger.application.DEFAULT_EXPENSE_LEAF_NAME
import com.unifiedledger.application.DEFAULT_HIDDEN_EXPENSE_ACCOUNT_ID
import com.unifiedledger.application.DEFAULT_HIDDEN_EXPENSE_ACCOUNT_NAME
import com.unifiedledger.application.DEFAULT_MANAGEABLE_ACCOUNT_ID
import com.unifiedledger.application.DEFAULT_MANAGEABLE_ACCOUNT_NAME
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CatalogOwnerKind
import com.unifiedledger.domain.CatalogWrite
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.validateProductCatalog

/**
 * P7-01 catalog store (spec section 5, D-143).
 *
 * Loads the ledger-scoped product catalog from the `catalog_*` tables, performs the idempotent
 * bootstrap for old/empty ledgers with fail-closed unknown-reference detection, and provides the
 * claim-first atomic management command boundary. Product rows never participate in the frozen
 * `rgXX_` silos or golden replay.
 */
class SqlDelightCatalogStore private constructor(
    private val database: LedgerDatabase,
) : CatalogAuthorityReader,
    CatalogAdmissionReader,
    CatalogManagementCommitPort,
    CatalogCategoryReferenceProbe {
    constructor(database: LedgerDatabase, driver: SqlDriver) : this(database) {
        configureSqliteConnection(driver)
    }

    override fun load(ledgerId: LedgerId): CatalogAuthority? = loadAuthority(ledgerId)

    override fun loadCurrent(ledgerId: LedgerId): LedgerCatalog? = loadAuthority(ledgerId)?.catalog

    override fun hasReferences(
        ledgerId: LedgerId,
        categoryId: CategoryId,
    ): Boolean = categoryHasReferences(ledgerId, categoryId)

    /**
     * Idempotent bootstrap (spec section 5.3). Seeds the default product catalog only when the
     * ledger has no catalog rows. Existing rows are never overwritten and the version is not
     * advanced. If any existing formal/import reference cannot be resolved against the seeded
     * default catalog, the whole insert is rolled back and [CatalogBootstrapResult.UnknownReference]
     * is returned with zero catalog writes.
     */
    fun bootstrap(
        ledgerId: LedgerId,
        seed: CatalogSeed,
    ): CatalogBootstrapResult {
        // Tables (not merely a stray version row) decide whether the ledger is already
        // initialized, so a half-written or racing writer cannot be mistaken for a seeded
        // catalog and converge to an empty one. Existing rows that fail to load fail closed
        // instead of being overwritten.
        if (hasCatalogRows(ledgerId)) {
            return loadAuthority(ledgerId)
                ?.let { CatalogBootstrapResult.AlreadyInitialized(it) }
                ?: CatalogBootstrapResult.UnknownReference
        }

        return try {
            val authority =
                database.transactionWithResult {
                    // Re-check inside the transaction: a concurrent seeder that committed first
                    // makes this call converge to the same authority without inserting again.
                    if (hasCatalogRows(ledgerId)) {
                        return@transactionWithResult loadAuthority(ledgerId) ?: abortBootstrap()
                    }
                    seed.accounts.forEach { account ->
                        insertSeedAccount(ledgerId, account)
                    }
                    seed.categories.forEach { category ->
                        insertSeedCategory(ledgerId, category)
                    }
                    if (unresolvedReferences(ledgerId, seed)) abortBootstrap()
                    database.ledgerQueries.insertCatalogVersion(ledger_id = ledgerId.value, version = 1L)
                    loadAuthority(ledgerId) ?: abortBootstrap()
                }
            CatalogBootstrapResult.Seeded(authority)
        } catch (rolledBack: CatalogBootstrapRollback) {
            CatalogBootstrapResult.UnknownReference
        }
    }

    private fun hasCatalogRows(ledgerId: LedgerId): Boolean {
        val ledger = ledgerId.value
        return database.ledgerQueries.countCatalogAccounts(ledger).executeAsOne() > 0L ||
            database.ledgerQueries.countCatalogCategories(ledger).executeAsOne() > 0L
    }

    override fun commitOnce(
        request: CatalogCommandRequest,
        apply: (CatalogAuthority) -> DomainResult<List<CatalogWrite>>,
    ): CatalogCommandResult {
        val ledger = request.ledgerId.value
        return try {
            database.transactionWithResult {
                database.ledgerQueries.claimCatalogCommandRequest(
                    ledger_id = ledger,
                    request_id = request.requestId.value,
                    command = request.command.commandName,
                    request_snapshot = request.requestSnapshot,
                    input_fingerprint = request.inputFingerprint,
                    outcome = "ACCEPTED",
                    expected_catalog_version = request.expectedCatalogVersion,
                )
                if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
                    return@transactionWithResult resolveExisting(request)
                }

                val authority =
                    loadAuthority(request.ledgerId)
                        ?: abortCatalog(CatalogFailureCode.CATALOG_CONSTRAINT_VIOLATION)
                if (authority.catalogVersion != request.expectedCatalogVersion) {
                    abortCatalog(CatalogFailureCode.CATALOG_VERSION_CONFLICT, conflict = true)
                }

                val writes =
                    when (val result = apply(authority)) {
                        is DomainResult.Failure -> abortCatalog(CatalogFailureCode.of(result.violation))
                        is DomainResult.Success -> result.value
                    }

                writes.forEach { applyWrite(request.ledgerId, it) }
                database.ledgerQueries.advanceCatalogVersion(ledger_id = ledger)
                val newVersion = database.ledgerQueries.selectCatalogVersion(ledger).executeAsOne()

                database.ledgerQueries.updateCatalogCommandRequestOutcome(
                    outcome = "ACCEPTED",
                    result_catalog_version = newVersion,
                    ledger_id = ledger,
                    request_id = request.requestId.value,
                )
                val receipt = receiptFor(request, writes, CatalogReceiptOutcome.ACCEPTED, newVersion)
                persistReceipt(ledger, receipt)
                CatalogCommandResult.Accepted(receipt)
            }
        } catch (rejected: CatalogTypedRollback) {
            rejected.result
        } catch (failure: Exception) {
            // Trigger/unique/FK fallback (spec 6.3): the whole command transaction already rolled
            // back above, so the identity stays retryable and no terminal row was written.
            CatalogCommandResult.Rejected(CatalogFailureCode.CATALOG_CONSTRAINT_VIOLATION)
        }
    }

    private fun resolveExisting(request: CatalogCommandRequest): CatalogCommandResult {
        val ledger = request.ledgerId.value
        val requestId = request.requestId.value
        val stored =
            database.ledgerQueries
                .selectCatalogCommandRequest(ledger, requestId) { _, snapshot, _, _, _, _ ->
                    snapshot
                }.executeAsOneOrNull() ?: abortCatalog(CatalogFailureCode.CATALOG_CONSTRAINT_VIOLATION)
        if (stored != request.requestSnapshot) {
            return CatalogCommandResult.Conflict(CatalogFailureCode.REQUEST_IDENTITY_CONFLICT)
        }
        val receipt =
            database.ledgerQueries
                .selectCatalogCommandReceipt(ledger, requestId) {
                    receiptOutcome,
                    newCatalogVersion,
                    createdManageableAccountId,
                    createdPostingAccountId,
                    createdParentCategoryId,
                    createdChildCategoryId,
                    ->
                    CatalogCommandReceipt(
                        requestId = request.requestId,
                        outcome =
                            if (receiptOutcome == "ACCEPTED") {
                                CatalogReceiptOutcome.ACCEPTED
                            } else {
                                CatalogReceiptOutcome.NO_CHANGE
                            },
                        newCatalogVersion = newCatalogVersion,
                        createdManageableAccountId = createdManageableAccountId?.let(::AccountId),
                        createdPostingAccountId = createdPostingAccountId?.let(::AccountId),
                        createdParentCategoryId = createdParentCategoryId?.let(::CategoryId),
                        createdChildCategoryId = createdChildCategoryId?.let(::CategoryId),
                    )
                }.executeAsOneOrNull() ?: abortCatalog(CatalogFailureCode.CATALOG_CONSTRAINT_VIOLATION)
        return CatalogCommandResult.NoChange(receipt)
    }

    private fun insertSeedAccount(
        ledgerId: LedgerId,
        account: CatalogSeedAccount,
    ) {
        database.ledgerQueries.insertCatalogAccount(
            ledger_id = ledgerId.value,
            account_id = account.accountId.value,
            name = account.name,
            kind = account.kind.name,
            currency_code = account.currency.code,
            currency_precision = account.currency.precision.toLong(),
            owned_by_user = if (account.ownedByUser) 1L else 0L,
            real_account = if (account.realAccount) 1L else 0L,
            system_role = account.systemRole,
            hidden = if (account.hidden) 1L else 0L,
            active = 1L,
        )
        database.ledgerQueries.insertCatalogNameHistory(
            ledger_id = ledgerId.value,
            owner_kind = "account",
            owner_id = account.accountId.value,
            version_number = 1L,
            name = account.name,
            status = "CURRENT",
        )
    }

    private fun insertSeedCategory(
        ledgerId: LedgerId,
        category: CatalogSeedCategory,
    ) {
        database.ledgerQueries.insertCatalogCategory(
            ledger_id = ledgerId.value,
            category_id = category.categoryId.value,
            kind = category.kind.name,
            parent_id = category.parentId?.value,
            posting_account_id = category.postingAccountId?.value,
            active = 1L,
        )
        database.ledgerQueries.insertCatalogNameHistory(
            ledger_id = ledgerId.value,
            owner_kind = "category",
            owner_id = category.categoryId.value,
            version_number = 1L,
            name = category.name,
            status = "CURRENT",
        )
    }

    private fun applyWrite(
        ledgerId: LedgerId,
        write: CatalogWrite,
    ) {
        val ledger = ledgerId.value
        when (write) {
            is CatalogWrite.InsertAccount -> {
                database.ledgerQueries.insertCatalogAccount(
                    ledger_id = ledger,
                    account_id = write.account.id.value,
                    name = write.account.name,
                    kind = write.account.kind.name,
                    currency_code = write.account.currency.code,
                    currency_precision =
                        write.account.currency.precision
                            .toLong(),
                    owned_by_user = if (write.account.ownedByUser) 1L else 0L,
                    real_account = if (write.account.realAccount) 1L else 0L,
                    system_role = write.account.systemRole,
                    hidden = if (!write.account.ownedByUser && !write.account.realAccount) 1L else 0L,
                    active = if (write.account.active) 1L else 0L,
                )
                appendNameVersion(ledger, CatalogOwnerKind.ACCOUNT, write.account.id.value, write.account.name)
            }

            is CatalogWrite.SetAccountActive -> {
                database.ledgerQueries.updateCatalogAccountActive(
                    active = if (write.active) 1L else 0L,
                    ledger_id = ledger,
                    account_id = write.accountId.value,
                )
            }

            is CatalogWrite.SetAccountName -> {
                database.ledgerQueries.updateCatalogAccountName(
                    name = write.name,
                    ledger_id = ledger,
                    account_id = write.accountId.value,
                )
            }

            is CatalogWrite.InsertCategory -> {
                database.ledgerQueries.insertCatalogCategory(
                    ledger_id = ledger,
                    category_id = write.category.id.value,
                    kind = write.category.kind.name,
                    parent_id = write.category.parentId?.value,
                    posting_account_id = write.category.postingAccountId?.value,
                    active = if (write.category.active) 1L else 0L,
                )
                appendNameVersion(ledger, CatalogOwnerKind.CATEGORY, write.category.id.value, write.category.name)
            }

            is CatalogWrite.SetCategoryActive -> {
                database.ledgerQueries.updateCatalogCategoryActive(
                    active = if (write.active) 1L else 0L,
                    ledger_id = ledger,
                    category_id = write.categoryId.value,
                )
            }

            is CatalogWrite.DeleteCategory -> {
                // C-7/C-10: a category delete removes its name history AND its hidden posting
                // account (with that account's name history) in the same transaction. The
                // posting account id is read before the category row is deleted.
                val postingAccountId =
                    database.ledgerQueries
                        .selectCatalogCategory(ledger, write.categoryId.value)
                        .executeAsOneOrNull()
                        ?.posting_account_id
                database.ledgerQueries.deleteCatalogNameHistory(
                    ledger_id = ledger,
                    owner_kind = "category",
                    owner_id = write.categoryId.value,
                )
                database.ledgerQueries.deleteCatalogCategory(
                    ledger_id = ledger,
                    category_id = write.categoryId.value,
                )
                if (postingAccountId != null) {
                    database.ledgerQueries.deleteCatalogNameHistory(
                        ledger_id = ledger,
                        owner_kind = "account",
                        owner_id = postingAccountId,
                    )
                    database.ledgerQueries.deleteCatalogAccount(
                        ledger_id = ledger,
                        account_id = postingAccountId,
                    )
                }
            }

            is CatalogWrite.AppendNameHistory -> {
                val token = ownerKindToken(write.ownerKind)
                database.ledgerQueries.supersedeCatalogCurrentName(
                    ledger_id = ledger,
                    owner_kind = token,
                    owner_id = write.ownerId,
                )
                appendNameVersion(ledger, write.ownerKind, write.ownerId, write.name)
            }
        }
    }

    private fun appendNameVersion(
        ledger: String,
        ownerKind: CatalogOwnerKind,
        ownerId: String,
        name: String,
    ) {
        val token = ownerKindToken(ownerKind)
        val next =
            database.ledgerQueries
                .selectCatalogMaxNameVersion(
                    ledger_id = ledger,
                    owner_kind = token,
                    owner_id = ownerId,
                ) { max -> max ?: 0L }
                .executeAsOne()
        database.ledgerQueries.insertCatalogNameHistory(
            ledger_id = ledger,
            owner_kind = token,
            owner_id = ownerId,
            version_number = next + 1L,
            name = name,
            status = "CURRENT",
        )
    }

    private fun persistReceipt(
        ledger: String,
        receipt: CatalogCommandReceipt,
    ) {
        database.ledgerQueries.insertCatalogCommandReceipt(
            ledger_id = ledger,
            request_id = receipt.requestId.value,
            outcome = if (receipt.outcome == CatalogReceiptOutcome.ACCEPTED) "ACCEPTED" else "NO_CHANGE",
            new_catalog_version = receipt.newCatalogVersion,
            created_manageable_account_id = receipt.createdManageableAccountId?.value,
            created_posting_account_id = receipt.createdPostingAccountId?.value,
            created_parent_category_id = receipt.createdParentCategoryId?.value,
            created_child_category_id = receipt.createdChildCategoryId?.value,
        )
    }

    private fun receiptFor(
        request: CatalogCommandRequest,
        writes: List<CatalogWrite>,
        outcome: CatalogReceiptOutcome,
        newVersion: Long,
    ): CatalogCommandReceipt {
        val insertedAccounts = writes.filterIsInstance<CatalogWrite.InsertAccount>().map { it.account }
        val insertedCategories = writes.filterIsInstance<CatalogWrite.InsertCategory>().map { it.category }
        return CatalogCommandReceipt(
            requestId = request.requestId,
            outcome = outcome,
            newCatalogVersion = newVersion,
            createdManageableAccountId = insertedAccounts.firstOrNull { it.ownedByUser && it.realAccount }?.id,
            createdPostingAccountId = insertedAccounts.firstOrNull { !it.ownedByUser && !it.realAccount }?.id,
            createdParentCategoryId = insertedCategories.firstOrNull { it.parentId == null }?.id,
            createdChildCategoryId = insertedCategories.firstOrNull { it.parentId != null }?.id,
        )
    }

    private fun loadAuthority(ledgerId: LedgerId): CatalogAuthority? {
        val ledger = ledgerId.value
        val version = database.ledgerQueries.selectCatalogVersion(ledger).executeAsOneOrNull() ?: return null
        val currentCategoryNames =
            database.ledgerQueries
                .selectCatalogCurrentNamesByKind(ledger, "category") { ownerId, name -> ownerId to name }
                .executeAsList()
                .toMap()
        val accounts =
            database.ledgerQueries
                .selectCatalogAccounts(ledger) {
                    accountId,
                    name,
                    kind,
                    currencyCode,
                    currencyPrecision,
                    ownedByUser,
                    realAccount,
                    systemRole,
                    _,
                    active,
                    ->
                    Account(
                        id = AccountId(accountId),
                        ledgerId = ledgerId,
                        kind = AccountKind.valueOf(kind),
                        currency = CurrencyUnit(currencyCode, currencyPrecision.toInt()),
                        ownedByUser = ownedByUser == 1L,
                        realAccount = realAccount == 1L,
                        systemRole = systemRole,
                        name = name,
                        active = active == 1L,
                    )
                }.executeAsList()
        val categories =
            database.ledgerQueries
                .selectCatalogCategories(ledger) { categoryId, kind, parentId, postingAccountId, active ->
                    Category(
                        id = CategoryId(categoryId),
                        ledgerId = ledgerId,
                        parentId = parentId?.let(::CategoryId),
                        postingAccountId = postingAccountId?.let(::AccountId),
                        active = active == 1L,
                        kind = CategoryKind.valueOf(kind),
                        name = currentCategoryNames[categoryId] ?: "",
                    )
                }.executeAsList()
        val catalog =
            when (val result = LedgerCatalog.create(accounts = accounts, categories = categories)) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return null
            }
        // Spec section 4.4: the product catalog load path validates the catalog-tree shape and
        // fails closed on a violation, so no admission/command path ever sees an inconsistent
        // product catalog. The frozen golden path stays on the unchanged LedgerCatalog.create.
        when (validateProductCatalog(catalog)) {
            is DomainResult.Success -> Unit
            is DomainResult.Failure -> return null
        }
        return CatalogAuthority(ledgerId = ledgerId, catalog = catalog, catalogVersion = version)
    }

    private fun unresolvedReferences(
        ledgerId: LedgerId,
        seed: CatalogSeed,
    ): Boolean {
        val ledger = ledgerId.value
        val seededAccounts = seed.accounts.map { it.accountId.value }.toSet()
        val seededCategories = seed.categories.map { it.categoryId.value }.toSet()
        // A2 (review): the scan must include the product manual entry points too, so a ledger
        // whose existing manual expense/income references a non-default category fails closed.
        if (referencedAccountIds(ledger).any { it !in seededAccounts }) return true
        return referencedCategoryIds(ledger).any { it !in seededCategories }
    }

    /**
     * A1 (review): the C-7 delete-reference probe must be at least as wide as the bootstrap
     * unknown-reference scan (spec 5.3/6.3), so it shares [referencedCategoryIds] /
     * [referencedAccountIds] instead of a hand-maintained subset. Deleting a category also
     * deletes its hidden posting account (C-10), so a formal/product posting that already
     * references that account blocks the delete as well (fail-closed).
     */
    private fun categoryHasReferences(
        ledgerId: LedgerId,
        categoryId: CategoryId,
    ): Boolean {
        val ledger = ledgerId.value
        if (categoryId.value in referencedCategoryIds(ledger)) return true
        val postingAccountId =
            database.ledgerQueries
                .selectCatalogCategory(ledger, categoryId.value)
                .executeAsOneOrNull()
                ?.posting_account_id
                ?: return false
        return postingAccountId in referencedAccountIds(ledger)
    }

    /** Every category id referenced by a product row, across the same surface bootstrap scans. */
    private fun referencedCategoryIds(ledger: String): Set<String> =
        buildSet {
            addAll(database.ledgerQueries.catalogReferencedManualCategoryIds(ledger, ledger).executeAsList())
            addAll(database.ledgerQueries.catalogReferencedDecisionCategoryIds(ledger).executeAsList())
            addAll(
                database.ledgerQueries
                    .catalogReferencedSemanticCategoryIds(ledger, ledger, ledger, ledger, ledger, ledger, ledger)
                    .executeAsList(),
            )
            addAll(
                database.ledgerQueries
                    .catalogReferencedConfirmationCategoryIds(
                        ledger,
                        ledger,
                        ledger,
                        ledger,
                        ledger,
                        ledger,
                        ledger,
                        ledger,
                        ledger,
                        ledger,
                    ).executeAsList(),
            )
            addAll(
                database.ledgerQueries
                    .catalogReferencedStagingCategoryIds(
                        ledger,
                        ledger,
                        ledger,
                        ledger,
                        ledger,
                        ledger,
                        ledger,
                        ledger,
                        ledger,
                        ledger,
                    ).executeAsList(),
            )
        }

    /** Every account id referenced by a product row (posting, projection or decision). */
    private fun referencedAccountIds(ledger: String): Set<String> =
        buildSet {
            addAll(database.ledgerQueries.catalogReferencedPostingAccounts(ledger).executeAsList())
            addAll(database.ledgerQueries.catalogReferencedProjectionAccounts(ledger).executeAsList())
            addAll(
                database.ledgerQueries
                    .catalogReferencedDecisionAccountIds(ledger, ledger, ledger, ledger, ledger)
                    .executeAsList(),
            )
        }

    companion object {
        /**
         * Android/foreign-key-configured handle variant (mirrors
         * [SqlDelightConfirmedManualExpenseCommitPort.forPlatformConfiguredDatabase]): the
         * platform driver already configured foreign keys and its busy timeout, so this never
         * issues the JDBC-only `PRAGMA busy_timeout` statement through the driver.
         */
        internal fun forPlatformConfiguredDatabase(
            database: LedgerDatabase,
        ): SqlDelightCatalogStore = SqlDelightCatalogStore(database)
    }
}

private fun ownerKindToken(ownerKind: CatalogOwnerKind): String = if (ownerKind == CatalogOwnerKind.ACCOUNT) "account" else "category"

/** Bootstrap outcome (spec section 5.3). */
sealed interface CatalogBootstrapResult {
    data class Seeded(
        val authority: CatalogAuthority,
    ) : CatalogBootstrapResult

    data class AlreadyInitialized(
        val authority: CatalogAuthority,
    ) : CatalogBootstrapResult

    /** Existing references cannot be resolved against the seeded default catalog: fail closed. */
    data object UnknownReference : CatalogBootstrapResult
}

/** Deterministic default product catalog seed (A-7, spec Appendix A note). */
data class CatalogSeed(
    val accounts: List<CatalogSeedAccount>,
    val categories: List<CatalogSeedCategory>,
)

data class CatalogSeedAccount(
    val accountId: AccountId,
    val name: String,
    val kind: AccountKind,
    val currency: CurrencyUnit,
    val ownedByUser: Boolean,
    val realAccount: Boolean,
    val systemRole: String? = null,
    val hidden: Boolean,
)

data class CatalogSeedCategory(
    val categoryId: CategoryId,
    val name: String,
    val kind: CategoryKind,
    val parentId: CategoryId?,
    val postingAccountId: AccountId?,
)

private class CatalogTypedRollback(
    val result: CatalogCommandResult,
) : RuntimeException()

private fun abortCatalog(
    code: CatalogFailureCode,
    conflict: Boolean = false,
): Nothing =
    throw CatalogTypedRollback(
        if (conflict) {
            CatalogCommandResult.Conflict(code)
        } else {
            CatalogCommandResult.Rejected(code)
        },
    )

/** A-7 deterministic default catalog seed for an empty/new ledger (ledger-agnostic rows). */
fun defaultCatalogSeed(): CatalogSeed =
    CatalogSeed(
        accounts =
            listOf(
                CatalogSeedAccount(
                    accountId = AccountId(DEFAULT_MANAGEABLE_ACCOUNT_ID),
                    name = DEFAULT_MANAGEABLE_ACCOUNT_NAME,
                    kind = AccountKind.ASSET,
                    currency = CATALOG_MANAGED_CURRENCY,
                    ownedByUser = true,
                    realAccount = true,
                    hidden = false,
                ),
                CatalogSeedAccount(
                    accountId = AccountId(DEFAULT_HIDDEN_EXPENSE_ACCOUNT_ID),
                    name = DEFAULT_HIDDEN_EXPENSE_ACCOUNT_NAME,
                    kind = AccountKind.EXPENSE,
                    currency = CATALOG_MANAGED_CURRENCY,
                    ownedByUser = false,
                    realAccount = false,
                    hidden = true,
                ),
            ),
        categories =
            listOf(
                CatalogSeedCategory(
                    categoryId = CategoryId(DEFAULT_EXPENSE_GROUP_ID),
                    name = DEFAULT_EXPENSE_GROUP_NAME,
                    kind = CategoryKind.EXPENSE,
                    parentId = null,
                    postingAccountId = null,
                ),
                CatalogSeedCategory(
                    categoryId = CategoryId(DEFAULT_EXPENSE_LEAF_ID),
                    name = DEFAULT_EXPENSE_LEAF_NAME,
                    kind = CategoryKind.EXPENSE,
                    parentId = CategoryId(DEFAULT_EXPENSE_GROUP_ID),
                    postingAccountId = AccountId(DEFAULT_HIDDEN_EXPENSE_ACCOUNT_ID),
                ),
            ),
    )

private class CatalogBootstrapRollback : RuntimeException()

private fun abortBootstrap(): Nothing = throw CatalogBootstrapRollback()
