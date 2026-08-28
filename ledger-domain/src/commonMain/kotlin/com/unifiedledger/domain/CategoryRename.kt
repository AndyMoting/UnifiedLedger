package com.unifiedledger.domain

/**
 * D-087 RG-02 `category_rename` minimal closed loop.
 *
 * Category display names are not carried by the domain [Category] model; they
 * live in the frozen v1 catalog (version 1) and in the append-only name
 * history owned by the rename commit port. This function defines the accepted
 * append semantics only: an accepted rename supersedes the current name
 * version and appends the next version with the requested name. It does not
 * invent lifecycle rules (for example renames of categories without a seeded
 * current version) that the frozen fixture does not exercise.
 */
enum class CategoryNameVersionStatus {
    CURRENT,
    SUPERSEDED,
}

data class CategoryNameVersion(
    val categoryId: CategoryId,
    val version: Long,
    val name: String,
    val status: CategoryNameVersionStatus,
)

data class CategoryRenameChange(
    val superseded: CategoryNameVersion?,
    val current: CategoryNameVersion,
)

sealed interface CategoryRenameViolation : DomainViolation {
    data object CategoryNotFound : CategoryRenameViolation

    data object EmptyName : CategoryRenameViolation

    data object CurrentNameVersionMissing : CategoryRenameViolation
}

fun renameCategoryName(
    catalog: LedgerCatalog,
    ledgerId: LedgerId,
    categoryId: CategoryId,
    newName: String,
    current: CategoryNameVersion?,
): DomainResult<CategoryRenameChange> {
    val category = catalog.category(categoryId)
    if (category == null || category.ledgerId != ledgerId) {
        return DomainResult.Failure(CategoryRenameViolation.CategoryNotFound)
    }
    if (newName.isBlank()) {
        return DomainResult.Failure(CategoryRenameViolation.EmptyName)
    }
    val previous =
        current ?: return DomainResult.Failure(
            CategoryRenameViolation.CurrentNameVersionMissing,
        )
    if (previous.categoryId != categoryId || previous.status != CategoryNameVersionStatus.CURRENT) {
        return DomainResult.Failure(CategoryRenameViolation.CurrentNameVersionMissing)
    }
    return DomainResult.Success(
        CategoryRenameChange(
            superseded = previous.copy(status = CategoryNameVersionStatus.SUPERSEDED),
            current =
                CategoryNameVersion(
                    categoryId = categoryId,
                    version = previous.version + 1,
                    name = newName,
                    status = CategoryNameVersionStatus.CURRENT,
                ),
        ),
    )
}
