package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CatalogViolation
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId

/**
 * P7-01 catalog management application contract (spec sections 6.1/6.3, D-143).
 *
 * A single [CatalogCommandRequest] shape carries the claim identity, the canonical
 * `requestSnapshot` (the only equivalent-replay basis), a derived `inputFingerprint` that
 * never participates in equivalence, the optimistic `expectedCatalogVersion` and the command
 * payload. The result is one of four states: Accepted / NoChange / Rejected / Conflict.
 */
data class CatalogRequestId(
    val value: String,
)

/**
 * Stable catalog failure codes (spec section 6.3). [code] is the frozen literal compared by
 * consumers; enum constant names are only an internal spelling. Messages are never compared.
 */
enum class CatalogFailureCode(
    val code: String,
) {
    CATALOG_NAME_EMPTY("CatalogNameEmpty"),
    CATALOG_NAME_TOO_LONG("CatalogNameTooLong"),
    CATALOG_NAME_INVALID("CatalogNameInvalid"),
    CATALOG_NAME_CONFLICT("CatalogNameConflict"),
    CATALOG_VERSION_CONFLICT("CatalogVersionConflict"),
    REQUEST_IDENTITY_CONFLICT("RequestIdentityConflict"),
    CATALOG_OBJECT_NOT_FOUND("CatalogObjectNotFound"),
    ACCOUNT_NOT_MANAGEABLE("AccountNotManageable"),
    ACCOUNT_KIND_NOT_MANAGEABLE("AccountKindNotManageable"),
    CATEGORY_NOT_MANAGEABLE("CategoryNotManageable"),
    CATEGORY_LEVEL_NOT_SUPPORTED("CategoryLevelNotSupported"),
    CATEGORY_PARENT_CROSS_LEDGER("CategoryParentCrossLedger"),
    CATEGORY_PARENT_REQUIRED("CategoryParentRequired"),
    CATEGORY_POSTING_ACCOUNT_INVALID("CategoryPostingAccountInvalid"),
    CATEGORY_HAS_NO_ACTIVE_CHILD("CategoryHasNoActiveChild"),
    CATEGORY_HAS_REFERENCES("CategoryHasReferences"),
    PARENT_INACTIVE("ParentInactive"),
    CATALOG_BOOTSTRAP_UNKNOWN_REFERENCE("CatalogBootstrapUnknownReference"),
    CATALOG_CONSTRAINT_VIOLATION("CatalogConstraintViolation"),
    ;

    companion object {
        fun of(violation: DomainViolation): CatalogFailureCode =
            when (violation) {
                CatalogViolation.CatalogNameEmpty -> CATALOG_NAME_EMPTY
                CatalogViolation.CatalogNameTooLong -> CATALOG_NAME_TOO_LONG
                CatalogViolation.CatalogNameInvalid -> CATALOG_NAME_INVALID
                CatalogViolation.CatalogNameConflict -> CATALOG_NAME_CONFLICT
                CatalogViolation.CatalogObjectNotFound -> CATALOG_OBJECT_NOT_FOUND
                CatalogViolation.AccountNotManageable -> ACCOUNT_NOT_MANAGEABLE
                CatalogViolation.AccountKindNotManageable -> ACCOUNT_KIND_NOT_MANAGEABLE
                CatalogViolation.CategoryNotManageable -> CATEGORY_NOT_MANAGEABLE
                CatalogViolation.CategoryLevelNotSupported -> CATEGORY_LEVEL_NOT_SUPPORTED
                CatalogViolation.CategoryParentRequired -> CATEGORY_PARENT_REQUIRED
                CatalogViolation.CategoryParentCrossLedger -> CATEGORY_PARENT_CROSS_LEDGER
                CatalogViolation.CategoryPostingAccountInvalid -> CATEGORY_POSTING_ACCOUNT_INVALID
                CatalogViolation.LastActiveChildCategory -> CATEGORY_HAS_NO_ACTIVE_CHILD
                CatalogViolation.CategoryHasReferences -> CATEGORY_HAS_REFERENCES
                CatalogViolation.ParentInactive -> PARENT_INACTIVE
                else -> CATALOG_CONSTRAINT_VIOLATION
            }
    }
}

/** The nine manageable catalog commands (spec section 6.1). */
sealed interface CatalogCommandPayload {
    val commandName: String

    data class CreateAccount(
        val name: String,
        val kind: AccountKind,
    ) : CatalogCommandPayload {
        override val commandName: String = "CreateAccount"
    }

    data class RenameAccount(
        val accountId: AccountId,
        val newName: String,
    ) : CatalogCommandPayload {
        override val commandName: String = "RenameAccount"
    }

    data class SetAccountActive(
        val accountId: AccountId,
        val active: Boolean,
    ) : CatalogCommandPayload {
        override val commandName: String = "SetAccountActive"
    }

    data class CreateCategoryGroup(
        val kind: CategoryKind,
        val groupName: String,
        val firstChildName: String,
    ) : CatalogCommandPayload {
        override val commandName: String = "CreateCategoryGroup"
    }

    data class AppendCategoryChild(
        val parentId: CategoryId,
        val name: String,
    ) : CatalogCommandPayload {
        override val commandName: String = "AppendCategoryChild"
    }

    data class RenameCategory(
        val categoryId: CategoryId,
        val newName: String,
    ) : CatalogCommandPayload {
        override val commandName: String = "RenameCategory"
    }

    data class SetCategoryActive(
        val categoryId: CategoryId,
        val active: Boolean,
    ) : CatalogCommandPayload {
        override val commandName: String = "SetCategoryActive"
    }

    data class DeleteCategory(
        val categoryId: CategoryId,
    ) : CatalogCommandPayload {
        override val commandName: String = "DeleteCategory"
    }

    data class EnableCategoryGroup(
        val parentId: CategoryId,
    ) : CatalogCommandPayload {
        override val commandName: String = "EnableCategoryGroup"
    }
}

data class CatalogCommandRequest(
    val ledgerId: LedgerId,
    val requestId: CatalogRequestId,
    val requestSnapshot: String,
    val inputFingerprint: String,
    val expectedCatalogVersion: Long,
    val command: CatalogCommandPayload,
)

/** Persisted request outcome value domain; only successful claims reach a terminal row. */
enum class CatalogReceiptOutcome {
    ACCEPTED,
    NO_CHANGE,
}

data class CatalogCommandReceipt(
    val requestId: CatalogRequestId,
    val outcome: CatalogReceiptOutcome,
    val newCatalogVersion: Long,
    val createdManageableAccountId: AccountId? = null,
    val createdPostingAccountId: AccountId? = null,
    val createdParentCategoryId: CategoryId? = null,
    val createdChildCategoryId: CategoryId? = null,
)

sealed interface CatalogCommandResult {
    data class Accepted(
        val receipt: CatalogCommandReceipt,
    ) : CatalogCommandResult

    data class NoChange(
        val receipt: CatalogCommandReceipt,
    ) : CatalogCommandResult

    data class Rejected(
        val failureCode: CatalogFailureCode,
    ) : CatalogCommandResult

    data class Conflict(
        val failureCode: CatalogFailureCode,
    ) : CatalogCommandResult
}

/** Current authoritative catalog loaded from persistence, with its ledger and version. */
data class CatalogAuthority(
    val ledgerId: LedgerId,
    val catalog: LedgerCatalog,
    val catalogVersion: Long,
)

/**
 * Claim-first atomic catalog command boundary. Implementations MUST:
 *
 * - claim `(ledgerId, requestId)` in the same transaction as work and receipt;
 * - return [CatalogCommandResult.NoChange] with the original receipt when an existing claim
 *   has an equivalent `requestSnapshot`, and [CatalogCommandResult.Conflict] with
 *   `RequestIdentityConflict` when it differs (zero write in both cases);
 * - reject `expectedCatalogVersion` mismatches with `CatalogVersionConflict` and zero writes;
 * - invoke [apply] at most once and only for a fresh claim;
 * - roll back the claim on every typed rejection so the identity stays retryable;
 * - on success write entities (including name history), advance the catalog version by one and
 *   persist the terminal request row plus immutable receipt in one transaction.
 */
fun interface CatalogManagementCommitPort {
    fun commitOnce(
        request: CatalogCommandRequest,
        apply: (CatalogAuthority) -> DomainResult<List<com.unifiedledger.domain.CatalogWrite>>,
    ): CatalogCommandResult
}

/** Per-command reason provider for `DeleteCategory` reference checks (spec C-7). */
fun interface CatalogCategoryReferenceProbe {
    fun hasReferences(
        ledgerId: LedgerId,
        categoryId: CategoryId,
    ): Boolean
}

/**
 * Independent catalog management request id source (spec section 6.1). Kept separate from the
 * manual-expense request source and the commit id sources so no consumption count is shared.
 */
fun interface CatalogManagementRequestIdSource {
    fun next(): CatalogRequestId
}

class UuidV7CatalogManagementRequestIdSource(
    private val generator: UuidV7Generator,
) : CatalogManagementRequestIdSource {
    override fun next(): CatalogRequestId = CatalogRequestId(generator.next())
}

/** Created stable ids for one catalog command, minted only when a fresh claim is applied. */
data class CatalogEntityIds(
    val manageableAccountId: AccountId,
    val postingAccountId: AccountId,
    val parentCategoryId: CategoryId,
    val childCategoryId: CategoryId,
)

fun interface CatalogEntityIdSource {
    fun next(): CatalogEntityIds
}

class UuidV7CatalogEntityIdSource(
    private val generator: UuidV7Generator,
) : CatalogEntityIdSource {
    override fun next(): CatalogEntityIds =
        CatalogEntityIds(
            manageableAccountId = AccountId(generator.next()),
            postingAccountId = AccountId(generator.next()),
            parentCategoryId = CategoryId(generator.next()),
            childCategoryId = CategoryId(generator.next()),
        )
}
