package com.unifiedledger.application

import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryNameVersion
import com.unifiedledger.domain.CategoryRenameChange
import com.unifiedledger.domain.CategoryRenameViolation
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.renameCategoryName

/**
 * D-087 RG-02 `category_rename` minimal closed loop (application layer).
 *
 * The rename carries no request id in the frozen v1 contract, so this use
 * case is deliberately single-shot: the commit port applies the append-only
 * name history transition exactly once per invocation and returns no returned
 * ids. Existing slice behavior (Rg02CategoryRenameProjection.Unsupported) is
 * intentionally left untouched for the pre-D-087 tests.
 */
data class ExplicitlyConfirmedCategoryRename(
    val ledgerId: LedgerId,
    val categoryId: CategoryId,
    val newName: String,
    val confirmation: ExplicitManualSave,
)

data class CategoryRenameIdentity(
    val ledgerId: LedgerId,
    val categoryId: CategoryId,
)

sealed interface ConfirmedCategoryRenameResult {
    data class Accepted(val change: CategoryRenameChange) : ConfirmedCategoryRenameResult

    data class Rejected(val violation: CategoryRenameViolation) : ConfirmedCategoryRenameResult
}

fun interface ConfirmedCategoryRenameCommitPort {
    /**
     * Atomically transitions the category name history:
     * - the current (highest) name version is superseded,
     * - a new version with [newName] is appended as current.
     * [applyRename] decides acceptance against the current version record;
     * a [DomainResult.Failure] must leave no residue.
     */
    fun commitOnce(
        identity: CategoryRenameIdentity,
        newName: String,
        applyRename: (CategoryNameVersion?) -> DomainResult<CategoryRenameChange>,
    ): ConfirmedCategoryRenameResult
}

class ExecuteConfirmedCategoryRename(
    private val commitPort: ConfirmedCategoryRenameCommitPort,
    private val catalog: LedgerCatalog,
) {
    fun execute(request: ExplicitlyConfirmedCategoryRename): ConfirmedCategoryRenameResult {
        val identity = CategoryRenameIdentity(request.ledgerId, request.categoryId)
        return commitPort.commitOnce(identity, request.newName) { current ->
            renameCategoryName(catalog, request.ledgerId, request.categoryId, request.newName, current)
        }
    }
}
