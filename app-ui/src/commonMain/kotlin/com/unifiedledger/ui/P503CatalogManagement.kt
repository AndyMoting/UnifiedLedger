package com.unifiedledger.ui

import com.unifiedledger.application.CatalogCommandResult
import com.unifiedledger.application.CatalogFailureCode
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind

/**
 * P7-01.D shared catalog-management form model (spec section 7.1-7.2). The open dialog is part
 * of the reducer state so every transition is a pure function that can be unit-tested without
 * Compose. Rename dialogs carry the current display name captured by the host at open time, so
 * the reducer never has to look inside the authoritative snapshot.
 */
sealed interface CatalogDialog {
    data object None : CatalogDialog

    data class CreateAccount(
        val nameText: String = "",
        val kind: AccountKind = AccountKind.ASSET,
    ) : CatalogDialog

    data class RenameAccount(
        val accountId: AccountId,
        val nameText: String,
    ) : CatalogDialog

    /** C-2: creating a level-1 group always requires at least one level-2 child name. */
    data class CreateCategoryGroup(
        val kind: CategoryKind,
        val groupNameText: String = "",
        val firstChildNameText: String = "",
    ) : CatalogDialog

    data class AppendCategoryChild(
        val parentId: CategoryId,
        val nameText: String = "",
    ) : CatalogDialog

    data class RenameCategory(
        val categoryId: CategoryId,
        val nameText: String,
    ) : CatalogDialog

    /** C-7: deletion is only offered for reference-free categories, behind an explicit confirm. */
    data class ConfirmCategoryDelete(
        val categoryId: CategoryId,
    ) : CatalogDialog
}

/**
 * One management outcome banner. [error] distinguishes a typed rejection/conflict from a
 * successful save; [message] is the readable Chinese text the UI renders.
 */
data class CatalogNotice(
    val message: String,
    val error: Boolean,
)

/**
 * Stable failure-code to readable Chinese text (spec sections 6.3/7.4). The codes are the frozen
 * comparison surface; only this display text may change. `CatalogVersionConflict` maps to the
 * frozen "目录已变化，请刷新" wording and never triggers an automatic retry.
 */
fun catalogFailureMessage(code: CatalogFailureCode): String =
    when (code) {
        CatalogFailureCode.CATALOG_NAME_EMPTY -> "名称不能为空"
        CatalogFailureCode.CATALOG_NAME_TOO_LONG -> "名称过长（最多 64 个字符）"
        CatalogFailureCode.CATALOG_NAME_INVALID -> "名称包含不可用字符"
        CatalogFailureCode.CATALOG_NAME_CONFLICT -> "名称已存在，请换一个"
        CatalogFailureCode.CATALOG_VERSION_CONFLICT -> "目录已变化，请刷新"
        CatalogFailureCode.REQUEST_IDENTITY_CONFLICT -> "请求编号与内容不一致，请刷新后重试"
        CatalogFailureCode.CATALOG_OBJECT_NOT_FOUND -> "目标不存在，请刷新"
        CatalogFailureCode.ACCOUNT_NOT_MANAGEABLE -> "该账户不可管理"
        CatalogFailureCode.ACCOUNT_KIND_NOT_MANAGEABLE -> "仅支持资产或负债账户"
        CatalogFailureCode.CATEGORY_NOT_MANAGEABLE -> "该分类不可管理"
        CatalogFailureCode.CATEGORY_LEVEL_NOT_SUPPORTED -> "分类最多两级"
        CatalogFailureCode.CATEGORY_PARENT_CROSS_LEDGER -> "父级分类不属于当前账本"
        CatalogFailureCode.CATEGORY_PARENT_REQUIRED -> "缺少父级分类"
        CatalogFailureCode.CATEGORY_POSTING_ACCOUNT_INVALID -> "分类对应的过账账户无效"
        CatalogFailureCode.CATEGORY_HAS_NO_ACTIVE_CHILD -> "需保留至少一个可用子分类"
        CatalogFailureCode.CATEGORY_HAS_REFERENCES -> "已有引用，不能删除，请改用停用"
        CatalogFailureCode.PARENT_INACTIVE -> "请先启用所属一级分类"
        CatalogFailureCode.CATALOG_BOOTSTRAP_UNKNOWN_REFERENCE -> "目录初始化失败，请重启"
        CatalogFailureCode.CATALOG_CONSTRAINT_VIOLATION -> "操作未生效，请刷新后重试"
    }

/** Maps one command result to its banner: Accepted/NoChange succeed, Rejected/Conflict fail. */
fun catalogNoticeFor(result: CatalogCommandResult): CatalogNotice =
    when (result) {
        is CatalogCommandResult.Accepted, is CatalogCommandResult.NoChange -> CatalogNotice("已保存", error = false)
        is CatalogCommandResult.Rejected -> CatalogNotice(catalogFailureMessage(result.failureCode), error = true)
        is CatalogCommandResult.Conflict -> CatalogNotice(catalogFailureMessage(result.failureCode), error = true)
    }

/** Text-field update applied only to dialogs that own a single editable name field. */
internal fun CatalogDialog.withPrimaryText(text: String): CatalogDialog =
    when (this) {
        is CatalogDialog.CreateAccount -> copy(nameText = text)
        is CatalogDialog.RenameAccount -> copy(nameText = text)
        is CatalogDialog.CreateCategoryGroup -> copy(groupNameText = text)
        is CatalogDialog.AppendCategoryChild -> copy(nameText = text)
        is CatalogDialog.RenameCategory -> copy(nameText = text)
        CatalogDialog.None, is CatalogDialog.ConfirmCategoryDelete -> this
    }

/** Second text field update, meaningful only for the C-2 level-1 group form. */
internal fun CatalogDialog.withSecondaryText(text: String): CatalogDialog =
    when (this) {
        is CatalogDialog.CreateCategoryGroup -> copy(firstChildNameText = text)
        else -> this
    }

/** Kind selection update, meaningful for the create-account form. */
internal fun CatalogDialog.withAccountKind(kind: AccountKind): CatalogDialog =
    when (this) {
        is CatalogDialog.CreateAccount -> copy(kind = kind)
        else -> this
    }
