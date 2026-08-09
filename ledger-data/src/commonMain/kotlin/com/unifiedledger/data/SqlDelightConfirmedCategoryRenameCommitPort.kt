package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.CategoryRenameIdentity
import com.unifiedledger.application.ConfirmedCategoryRenameCommitPort
import com.unifiedledger.application.ConfirmedCategoryRenameResult
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryNameVersion
import com.unifiedledger.domain.CategoryNameVersionStatus
import com.unifiedledger.domain.CategoryRenameChange
import com.unifiedledger.domain.CategoryRenameViolation
import com.unifiedledger.domain.DomainResult

/**
 * D-087 RG-02 `category_rename` minimal closed loop (persistence).
 *
 * The append-only `rg02_category_name_history` table is the single source of
 * truth for category display names: version 1 records are seeded from the
 * frozen v1 catalog at root start, and each accepted rename supersedes the
 * current record and appends the next version in the same transaction.
 */
class SqlDelightConfirmedCategoryRenameCommitPort private constructor(
    private val database: LedgerDatabase,
) : ConfirmedCategoryRenameCommitPort {
    constructor(database: LedgerDatabase, driver: SqlDriver) : this(database) {
        configureSqliteConnection(driver)
    }

    override fun commitOnce(
        identity: CategoryRenameIdentity,
        newName: String,
        applyRename: (CategoryNameVersion?) -> DomainResult<CategoryRenameChange>,
    ): ConfirmedCategoryRenameResult = database.transactionWithResult {
        val current = database.ledgerQueries.selectRg02CurrentCategoryNameVersion(
            ledger_id = identity.ledgerId.value,
            category_id = identity.categoryId.value,
        ) { categoryId, versionNumber, name, status ->
            CategoryNameVersion(
                CategoryId(categoryId),
                versionNumber,
                name,
                if (status == "CURRENT") CategoryNameVersionStatus.CURRENT else CategoryNameVersionStatus.SUPERSEDED,
            )
        }.executeAsOneOrNull()
        when (val change = applyRename(current)) {
            is DomainResult.Failure ->
                ConfirmedCategoryRenameResult.Rejected(change.violation as CategoryRenameViolation)

            is DomainResult.Success -> {
                val accepted = change.value
                val superseded = checkNotNull(accepted.superseded) { "rename must supersede a current version" }
                database.ledgerQueries.updateRg02CategoryNameStatus(
                    status = "SUPERSEDED",
                    ledger_id = identity.ledgerId.value,
                    category_id = superseded.categoryId.value,
                    version_number = superseded.version,
                )
                check(database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() == 1L)
                database.ledgerQueries.insertRg02CategoryNameHistory(
                    ledger_id = identity.ledgerId.value,
                    category_id = accepted.current.categoryId.value,
                    version_number = accepted.current.version,
                    name = accepted.current.name,
                    status = "CURRENT",
                )
                ConfirmedCategoryRenameResult.Accepted(accepted)
            }
        }
    }
}
