package com.unifiedledger.application.import.selfentry

import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.SelfEntryMatchBasis
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.LedgerCatalog

/**
 * P8-04 i3a (D-18): the frozen `import_candidate.rule` string marking every self-entry
 * candidate. The rule column carries no value CHECK (Ledger.sq pins `rule_version = 1`
 * only), so the distinct string lands with zero DDL; `rule_version` stays 1.
 */
const val SELF_ENTRY_CANDIDATE_RULE: String = "self_entry_v1"

/**
 * Why one required name has no unique catalog resolution (D-18: an ambiguous name counts
 * as no match; D-024: same-name leaves disambiguate through the full parent path). Typed,
 * never a string, so a resolution outcome can only carry one of these tokens.
 */
enum class SelfEntryMatchUnresolvedReason {
    CATEGORY_NOT_FOUND,
    CATEGORY_AMBIGUOUS,
    ACCOUNT_NOT_FOUND,
    ACCOUNT_AMBIGUOUS,
}

/**
 * P8-04 i3a (D-18): one parsed row's name-match outcome over the batch's catalog snapshot.
 *
 * The outcome is a prefill-draft and display carrier only: matching never decides (D-017 —
 * confirmation still walks the checkbox gate and the decision forms), never persists, and
 * never enters the content fingerprint (the inputs are session data, D-016 keeps them out
 * of the source facts). Per-kind slots never mix: ordinary rows fill the category and
 * funding-account pair (from/to stay null), transfer rows fill the from/to pair (a
 * transfer never consumes a category — its category text surfaces as
 * [SelfEntryMatchOutcome.Matched.unconsumedCategoryText] for the i3b display area).
 */
sealed interface SelfEntryMatchOutcome {
    /**
     * Every consumed lookup resolved to exactly one active catalog entity. A null id means
     * that slot's cell was blank — nothing to match, no failure (the decision form fills
     * it, D-017); it is not an unmatched face. [categoryMatchMethod] is the D-19 display
     * method of the category slot: `"path"` = the full parent-path match disambiguated
     * same-name leaves, `"exact"` = the bare leaf name uniquely hit, null = the slot was
     * blank (nothing consumed). Accounts have no hierarchy and resolve exactly, so they
     * carry no method.
     */
    data class Matched(
        val categoryId: CategoryId?,
        val categoryName: String?,
        val categoryMatchMethod: String?,
        val fundingAccountId: AccountId?,
        val fundingAccountName: String?,
        val fromAccountId: AccountId?,
        val fromAccountName: String?,
        val toAccountId: AccountId?,
        val toAccountName: String?,
        val unconsumedCategoryText: Boolean,
    ) : SelfEntryMatchOutcome

    /**
     * At least one consumed required name had no unique resolution (D-18: an ambiguous
     * name is handled as unmatched). Each reason slot is null when that slot's cell was
     * blank (nothing to match) or matched; a transfer carries at most the two leg reasons.
     */
    data class Unmatched(
        val categoryReason: SelfEntryMatchUnresolvedReason?,
        val fundingAccountReason: SelfEntryMatchUnresolvedReason?,
        val fromAccountReason: SelfEntryMatchUnresolvedReason?,
        val toAccountReason: SelfEntryMatchUnresolvedReason?,
        val unconsumedCategoryText: Boolean,
    ) : SelfEntryMatchOutcome
}

/**
 * P8-04 i3a (D-18): the parse arm's pure name matcher over one immutable [LedgerCatalog]
 * snapshot. No IO, no clock, no decisions — the orchestrator constructs one matcher per
 * intake dispatch and reuses it for every row (the name indexes are assembled once here,
 * never per row).
 *
 * Matching semantics (D-18/D-024/D-032): exact string equality after trimming and folding
 * every internal whitespace run to one space — never fuzzy, substring or case-folded
 * matching (a Chinese name matches character for character). Ambiguity (no unique
 * resolution) is handled as no match. Categories resolve against active categories of the
 * row's own kind: with a non-blank secondary the full path (parent name + leaf name)
 * disambiguates same-name leaves, and a failed parent name falls back to bare leaf-name
 * matching where exactly one active same-kind leaf wins; more than one is ambiguous.
 * Funding accounts resolve against active, user-owned, real ASSET accounts only (the
 * CatalogAdmission optional set); accounts have no hierarchy, so more than one same-name
 * candidate is ambiguous. A blank cell never fails: it simply carries no target.
 */
class SelfEntryNameMatcher(
    catalog: LedgerCatalog,
) {
    private val fundingAccountsByName: Map<String, List<Account>> =
        catalog.accounts
            .filter { it.kind == AccountKind.ASSET && it.active && it.ownedByUser && it.realAccount }
            .groupBy { it.name.foldedNameKey() }

    private val categoriesById: Map<CategoryId, Category> = catalog.categories.associateBy { it.id }

    private val activeCategoriesByKind: Map<CategoryKind, List<Category>> =
        catalog.categories.filter { it.active }.groupBy { it.kind }

    /**
     * Resolves one row's name inputs. [directionToken] picks the ordinary row's category
     * kind ("in" -> INCOME, anything else -> EXPENSE, the parser's own derivation); the
     * transfer kinds ignore it.
     */
    fun match(
        inputs: SelfEntryMatchInputs,
        recordKind: ImportRecordKind,
        directionToken: String,
    ): SelfEntryMatchOutcome =
        when (recordKind) {
            ImportRecordKind.ORDINARY_FLOW_SOURCE -> {
                val kind =
                    if (directionToken == DIRECTION_IN) {
                        CategoryKind.INCOME
                    } else {
                        CategoryKind.EXPENSE
                    }
                foldOrdinary(resolveCategory(inputs.categoryPrimary, inputs.categorySecondary, kind), resolveAccount(inputs.accountPrimary))
            }
            ImportRecordKind.TRANSFER_FLOW_SOURCE, ImportRecordKind.TRANSFER_FLOW_SOURCE_MISSING_LEG -> {
                val from = resolveAccount(inputs.accountPrimary)
                val to =
                    if (inputs.accountSecondary.isBlank()) {
                        AccountResolution(null, null, null)
                    } else {
                        resolveAccount(inputs.accountSecondary)
                    }
                foldTransfer(
                    from,
                    to,
                    unconsumedCategoryText = inputs.categoryPrimary.isNotBlank() || inputs.categorySecondary.isNotBlank(),
                )
            }
            // v3 kinds never ride the self-entry arm (D-3): the parser cannot produce them.
            else -> throw IllegalArgumentException("self-entry matching got unexpected kind ${recordKind.name}")
        }

    private fun foldOrdinary(
        category: CategoryResolution,
        funding: AccountResolution,
    ): SelfEntryMatchOutcome =
        if (category.reason == null && funding.reason == null) {
            SelfEntryMatchOutcome.Matched(
                categoryId = category.id,
                categoryName = category.name,
                categoryMatchMethod = category.matchMethod,
                fundingAccountId = funding.id,
                fundingAccountName = funding.name,
                fromAccountId = null,
                fromAccountName = null,
                toAccountId = null,
                toAccountName = null,
                unconsumedCategoryText = false,
            )
        } else {
            SelfEntryMatchOutcome.Unmatched(
                categoryReason = category.reason,
                fundingAccountReason = funding.reason,
                fromAccountReason = null,
                toAccountReason = null,
                unconsumedCategoryText = false,
            )
        }

    private fun foldTransfer(
        from: AccountResolution,
        to: AccountResolution,
        unconsumedCategoryText: Boolean,
    ): SelfEntryMatchOutcome =
        if (from.reason == null && to.reason == null) {
            SelfEntryMatchOutcome.Matched(
                categoryId = null,
                categoryName = null,
                categoryMatchMethod = null,
                fundingAccountId = null,
                fundingAccountName = null,
                fromAccountId = from.id,
                fromAccountName = from.name,
                toAccountId = to.id,
                toAccountName = to.name,
                unconsumedCategoryText = unconsumedCategoryText,
            )
        } else {
            SelfEntryMatchOutcome.Unmatched(
                categoryReason = null,
                fundingAccountReason = null,
                fromAccountReason = from.reason,
                toAccountReason = to.reason,
                unconsumedCategoryText = unconsumedCategoryText,
            )
        }

    private fun resolveCategory(
        primaryText: String,
        secondaryText: String,
        kind: CategoryKind,
    ): CategoryResolution {
        if (primaryText.isBlank() && secondaryText.isBlank()) {
            return CategoryResolution(null, null, null, null)
        }
        val leaves = activeCategoriesByKind[kind].orEmpty()
        val target = (if (secondaryText.isBlank()) primaryText else secondaryText).foldedNameKey()
        if (secondaryText.isNotBlank()) {
            // D-024: the full path (parent name + leaf name) disambiguates same-name leaves.
            val parent = primaryText.foldedNameKey()
            val byPath =
                leaves.filter { leaf ->
                    leaf.name.foldedNameKey() == target && parentNameOf(leaf)?.foldedNameKey() == parent
                }
            if (byPath.size == 1) {
                val leaf = byPath.single()
                return CategoryResolution(leaf.id, leaf.name, null, MATCH_METHOD_PATH)
            }
            if (byPath.size > 1) {
                return CategoryResolution(null, null, SelfEntryMatchUnresolvedReason.CATEGORY_AMBIGUOUS, null)
            }
            // D-024: a failed parent name falls back to bare leaf-name matching below.
        }
        val byLeaf = leaves.filter { it.name.foldedNameKey() == target }
        val single = byLeaf.singleOrNull()
        return if (single != null) {
            CategoryResolution(single.id, single.name, null, MATCH_METHOD_EXACT)
        } else {
            CategoryResolution(
                null,
                null,
                if (byLeaf.isEmpty()) SelfEntryMatchUnresolvedReason.CATEGORY_NOT_FOUND else SelfEntryMatchUnresolvedReason.CATEGORY_AMBIGUOUS,
                null,
            )
        }
    }

    // The parent resolves over the full category list: the leaf's own active flag gates the
    // match, the parent only carries the disambiguating path name.
    private fun parentNameOf(category: Category): String? = category.parentId?.let { categoriesById[it]?.name }

    private fun resolveAccount(nameText: String): AccountResolution {
        if (nameText.isBlank()) {
            return AccountResolution(null, null, null)
        }
        val candidates = fundingAccountsByName[nameText.foldedNameKey()].orEmpty()
        val single = candidates.singleOrNull()
        return if (single != null) {
            AccountResolution(single.id, single.name, null)
        } else {
            AccountResolution(
                null,
                null,
                if (candidates.isEmpty()) SelfEntryMatchUnresolvedReason.ACCOUNT_NOT_FOUND else SelfEntryMatchUnresolvedReason.ACCOUNT_AMBIGUOUS,
            )
        }
    }

    private data class CategoryResolution(
        val id: CategoryId?,
        val name: String?,
        val reason: SelfEntryMatchUnresolvedReason?,
        /** P8-04 i3b (D-19): `"path"`/`"exact"` when resolved; null when blank or unresolved. */
        val matchMethod: String?,
    )

    private data class AccountResolution(
        val id: AccountId?,
        val name: String?,
        val reason: SelfEntryMatchUnresolvedReason?,
    )

    private companion object {
        const val DIRECTION_IN: String = "in"

        /** P8-04 i3b (D-19) match-method tokens: the full-path disambiguation and the bare-leaf hit. */
        const val MATCH_METHOD_PATH: String = "path"
        const val MATCH_METHOD_EXACT: String = "exact"

        /** The exact-match folding: every internal whitespace run (space/tab/…) to one space. */
        val WHITESPACE_RUN = Regex("\\s+")

        fun String.foldedNameKey(): String {
            val trimmed = trim()
            return if (trimmed.any { it.isWhitespace() }) WHITESPACE_RUN.replace(trimmed, " ") else trimmed
        }
    }
}

/**
 * P8-04 i3a (D-18/D-19) projection: one row's outcome plus its verbatim cell texts become
 * the summary basis the review surface renders. Source texts stay verbatim (the user's own
 * cell content); targets carry the catalog display names; unresolved reasons surface as
 * the stable enum token names. The basis is session display data only — never persisted,
 * never part of the content fingerprint.
 */
fun SelfEntryMatchOutcome.toMatchBasis(inputs: SelfEntryMatchInputs): SelfEntryMatchBasis {
    val categorySourceText =
        if (inputs.categorySecondary.isBlank()) inputs.categoryPrimary else "${inputs.categoryPrimary}/${inputs.categorySecondary}"
    return when (this) {
        is SelfEntryMatchOutcome.Matched ->
            SelfEntryMatchBasis(
                categorySourceText = categorySourceText,
                categoryTargetName = categoryName,
                categoryUnmatchedReason = null,
                fundingAccountSourceText = inputs.accountPrimary,
                fundingAccountTargetName = fundingAccountName,
                fundingAccountUnmatchedReason = null,
                fromAccountSourceText = inputs.accountPrimary,
                fromAccountTargetName = fromAccountName,
                fromAccountUnmatchedReason = null,
                toAccountSourceText = inputs.accountSecondary,
                toAccountTargetName = toAccountName,
                toAccountUnmatchedReason = null,
                unconsumedCategoryText = unconsumedCategoryText,
                categoryMatchMethod = categoryMatchMethod,
            )
        is SelfEntryMatchOutcome.Unmatched ->
            SelfEntryMatchBasis(
                categorySourceText = categorySourceText,
                categoryTargetName = null,
                categoryUnmatchedReason = categoryReason?.name,
                fundingAccountSourceText = inputs.accountPrimary,
                fundingAccountTargetName = null,
                fundingAccountUnmatchedReason = fundingAccountReason?.name,
                fromAccountSourceText = inputs.accountPrimary,
                fromAccountTargetName = null,
                fromAccountUnmatchedReason = fromAccountReason?.name,
                toAccountSourceText = inputs.accountSecondary,
                toAccountTargetName = null,
                toAccountUnmatchedReason = toAccountReason?.name,
                unconsumedCategoryText = unconsumedCategoryText,
            )
    }
}
