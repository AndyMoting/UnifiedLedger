package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.appendCategoryChild
import com.unifiedledger.domain.createCategoryGroup
import com.unifiedledger.domain.createManagedAccount
import com.unifiedledger.domain.deleteManagedCategory
import com.unifiedledger.domain.enableCategoryGroup
import com.unifiedledger.domain.renameManagedAccount
import com.unifiedledger.domain.renameManagedCategory
import com.unifiedledger.domain.setCategoryActive
import com.unifiedledger.domain.setManagedAccountActive

/** A-1/A-3: manageable financial accounts are always CNY with precision 2 in this batch. */
val CATALOG_MANAGED_CURRENCY: CurrencyUnit = CurrencyUnit("CNY", 2)

/** A-7 default catalog stable ids, kept identical to both composition roots' former synthetic catalog. */
const val DEFAULT_MANAGEABLE_ACCOUNT_ID: String = "asset-payment-local"
const val DEFAULT_HIDDEN_EXPENSE_ACCOUNT_ID: String = "expense-account-local"
const val DEFAULT_EXPENSE_GROUP_ID: String = "expense-category-food"
const val DEFAULT_EXPENSE_LEAF_ID: String = "expense-category-breakfast"

/** A-7 default display names: anonymous synthetic literals frozen by the implementation review. */
const val DEFAULT_MANAGEABLE_ACCOUNT_NAME: String = "默认支付账户"
const val DEFAULT_HIDDEN_EXPENSE_ACCOUNT_NAME: String = "隐藏费用过账账户"
const val DEFAULT_EXPENSE_GROUP_NAME: String = "餐饮"
const val DEFAULT_EXPENSE_LEAF_NAME: String = "早餐"

/**
 * P7-01 catalog management use cases (spec section 6.1). Each command mints an independent
 * request id, derives the canonical request snapshot and its non-identity fingerprint, and
 * delegates the atomic claim/work/receipt boundary to the injected [CatalogManagementCommitPort].
 */
class ExecuteCatalogCommand(
    private val commitPort: CatalogManagementCommitPort,
    private val requestIdSource: CatalogManagementRequestIdSource,
    private val entityIdSource: CatalogEntityIdSource,
    private val categoryReferenceProbe: CatalogCategoryReferenceProbe,
    private val managedCurrency: CurrencyUnit = CATALOG_MANAGED_CURRENCY,
) {
    fun createAccount(
        ledgerId: LedgerId,
        name: String,
        kind: AccountKind,
        expectedCatalogVersion: Long,
    ): CatalogCommandResult {
        val payload = CatalogCommandPayload.CreateAccount(name, kind)
        return execute(ledgerId, expectedCatalogVersion, payload) { authority ->
            val ids = entityIdSource.next()
            createManagedAccount(
                catalog = authority.catalog,
                ledgerId = ledgerId,
                accountId = ids.manageableAccountId,
                name = name,
                kind = kind,
                currency = managedCurrency,
            )
        }
    }

    fun renameAccount(
        ledgerId: LedgerId,
        accountId: AccountId,
        newName: String,
        expectedCatalogVersion: Long,
    ): CatalogCommandResult {
        val payload = CatalogCommandPayload.RenameAccount(accountId, newName)
        return execute(ledgerId, expectedCatalogVersion, payload) { authority ->
            renameManagedAccount(authority.catalog, ledgerId, accountId, newName)
        }
    }

    fun setAccountActive(
        ledgerId: LedgerId,
        accountId: AccountId,
        active: Boolean,
        expectedCatalogVersion: Long,
    ): CatalogCommandResult {
        val payload = CatalogCommandPayload.SetAccountActive(accountId, active)
        return execute(ledgerId, expectedCatalogVersion, payload) { authority ->
            setManagedAccountActive(authority.catalog, ledgerId, accountId, active)
        }
    }

    fun createCategoryGroup(
        ledgerId: LedgerId,
        kind: CategoryKind,
        groupName: String,
        firstChildName: String,
        expectedCatalogVersion: Long,
    ): CatalogCommandResult {
        val payload = CatalogCommandPayload.CreateCategoryGroup(kind, groupName, firstChildName)
        return execute(ledgerId, expectedCatalogVersion, payload) { authority ->
            val ids = entityIdSource.next()
            createCategoryGroup(
                catalog = authority.catalog,
                ledgerId = ledgerId,
                kind = kind,
                groupId = ids.parentCategoryId,
                groupName = groupName,
                childId = ids.childCategoryId,
                childName = firstChildName,
                postingAccountId = ids.postingAccountId,
                currency = managedCurrency,
            )
        }
    }

    fun appendCategoryChild(
        ledgerId: LedgerId,
        parentId: CategoryId,
        name: String,
        expectedCatalogVersion: Long,
    ): CatalogCommandResult {
        val payload = CatalogCommandPayload.AppendCategoryChild(parentId, name)
        return execute(ledgerId, expectedCatalogVersion, payload) { authority ->
            val ids = entityIdSource.next()
            appendCategoryChild(
                catalog = authority.catalog,
                ledgerId = ledgerId,
                parentId = parentId,
                childId = ids.childCategoryId,
                name = name,
                postingAccountId = ids.postingAccountId,
                currency = managedCurrency,
            )
        }
    }

    fun renameCategory(
        ledgerId: LedgerId,
        categoryId: CategoryId,
        newName: String,
        expectedCatalogVersion: Long,
    ): CatalogCommandResult {
        val payload = CatalogCommandPayload.RenameCategory(categoryId, newName)
        return execute(ledgerId, expectedCatalogVersion, payload) { authority ->
            renameManagedCategory(authority.catalog, ledgerId, categoryId, newName)
        }
    }

    fun setCategoryActive(
        ledgerId: LedgerId,
        categoryId: CategoryId,
        active: Boolean,
        expectedCatalogVersion: Long,
    ): CatalogCommandResult {
        val payload = CatalogCommandPayload.SetCategoryActive(categoryId, active)
        return execute(ledgerId, expectedCatalogVersion, payload) { authority ->
            setCategoryActive(authority.catalog, ledgerId, categoryId, active)
        }
    }

    fun deleteCategory(
        ledgerId: LedgerId,
        categoryId: CategoryId,
        expectedCatalogVersion: Long,
    ): CatalogCommandResult {
        val payload = CatalogCommandPayload.DeleteCategory(categoryId)
        return execute(ledgerId, expectedCatalogVersion, payload) { authority ->
            deleteManagedCategory(
                catalog = authority.catalog,
                ledgerId = ledgerId,
                categoryId = categoryId,
                hasReferences = { id -> categoryReferenceProbe.hasReferences(ledgerId, id) },
            )
        }
    }

    fun enableCategoryGroup(
        ledgerId: LedgerId,
        parentId: CategoryId,
        expectedCatalogVersion: Long,
    ): CatalogCommandResult {
        val payload = CatalogCommandPayload.EnableCategoryGroup(parentId)
        return execute(ledgerId, expectedCatalogVersion, payload) { authority ->
            enableCategoryGroup(authority.catalog, ledgerId, parentId)
        }
    }

    private fun execute(
        ledgerId: LedgerId,
        expectedCatalogVersion: Long,
        payload: CatalogCommandPayload,
        apply: (CatalogAuthority) -> DomainResult<List<com.unifiedledger.domain.CatalogWrite>>,
    ): CatalogCommandResult {
        val snapshot = canonicalRequestSnapshot(payload)
        val request =
            CatalogCommandRequest(
                ledgerId = ledgerId,
                requestId = requestIdSource.next(),
                requestSnapshot = snapshot,
                inputFingerprint = catalogInputFingerprint(snapshot),
                expectedCatalogVersion = expectedCatalogVersion,
                command = payload,
            )
        return commitPort.commitOnce(request, apply)
    }
}

/**
 * Canonical command-payload copy: the only equivalent-replay basis. Field order is fixed and
 * every value is JCS-escaped, so the same logical payload always yields the same bytes across
 * retries and platforms.
 */
fun canonicalRequestSnapshot(payload: CatalogCommandPayload): String =
    when (payload) {
        is CatalogCommandPayload.CreateAccount -> "{\"command\":${jcsString(payload.commandName)},\"kind\":${jcsString(payload.kind.name)},\"name\":${jcsString(payload.name)}}"
        is CatalogCommandPayload.RenameAccount -> "{\"account_id\":${jcsString(payload.accountId.value)},\"command\":${jcsString(payload.commandName)},\"new_name\":${jcsString(payload.newName)}}"
        is CatalogCommandPayload.SetAccountActive -> "{\"account_id\":${jcsString(payload.accountId.value)},\"active\":${jcsString(payload.active.toString())},\"command\":${jcsString(payload.commandName)}}"
        is CatalogCommandPayload.CreateCategoryGroup -> "{\"command\":${jcsString(payload.commandName)},\"first_child_name\":${jcsString(payload.firstChildName)},\"group_name\":${jcsString(payload.groupName)},\"kind\":${jcsString(payload.kind.name)}}"
        is CatalogCommandPayload.AppendCategoryChild -> "{\"command\":${jcsString(payload.commandName)},\"name\":${jcsString(payload.name)},\"parent_id\":${jcsString(payload.parentId.value)}}"
        is CatalogCommandPayload.RenameCategory -> "{\"category_id\":${jcsString(payload.categoryId.value)},\"command\":${jcsString(payload.commandName)},\"new_name\":${jcsString(payload.newName)}}"
        is CatalogCommandPayload.SetCategoryActive -> "{\"active\":${jcsString(payload.active.toString())},\"category_id\":${jcsString(payload.categoryId.value)},\"command\":${jcsString(payload.commandName)}}"
        is CatalogCommandPayload.DeleteCategory -> "{\"category_id\":${jcsString(payload.categoryId.value)},\"command\":${jcsString(payload.commandName)}}"
        is CatalogCommandPayload.EnableCategoryGroup -> "{\"command\":${jcsString(payload.commandName)},\"parent_id\":${jcsString(payload.parentId.value)}}"
    }

/** Derived integrity digest; explicitly not part of equivalent-replay identity (spec A4). */
fun catalogInputFingerprint(snapshot: String): String = "sha256:" + Sha256.digestHex(snapshot.encodeToByteArray())
