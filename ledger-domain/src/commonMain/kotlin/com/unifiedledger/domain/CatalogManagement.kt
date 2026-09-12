package com.unifiedledger.domain

/**
 * P7-01 catalog management pure domain surface (spec sections 4.4/4.6, D-143).
 *
 * Everything here is a pure function over an already constructed [LedgerCatalog]: no IO, no
 * Clock and no random source. Identifier minting, persistence and optimistic concurrency live
 * in the application/data layers. Name normalization and the append-only name-history model
 * mirror the frozen [CategoryRename] version model without reusing the RG-02 silo.
 *
 * A-6: [CATALOG_MAX_NAME_CODE_POINTS] is the normalized display name length limit in Unicode
 * code points.
 */
const val CATALOG_MAX_NAME_CODE_POINTS: Int = 64

enum class CatalogOwnerKind {
    ACCOUNT,
    CATEGORY,
}

/**
 * Typed catalog management violations. Each token maps to exactly one stable failure code in
 * the application layer (spec section 6.3). `LastActiveChildCategory` is the documented domain
 * token for the stable `CategoryHasNoActiveChild` code (spec section 6.3 mapping note).
 */
sealed interface CatalogViolation : DomainViolation {
    data object CatalogNameEmpty : CatalogViolation

    data object CatalogNameTooLong : CatalogViolation

    data object CatalogNameInvalid : CatalogViolation

    data object CatalogNameConflict : CatalogViolation

    data object CatalogObjectNotFound : CatalogViolation

    data object AccountNotManageable : CatalogViolation

    data object AccountKindNotManageable : CatalogViolation

    data object CategoryNotManageable : CatalogViolation

    data object CategoryLevelNotSupported : CatalogViolation

    data object CategoryParentRequired : CatalogViolation

    data object CategoryParentCrossLedger : CatalogViolation

    data object CategoryPostingAccountInvalid : CatalogViolation

    data object LastActiveChildCategory : CatalogViolation

    data object CategoryHasReferences : CatalogViolation

    data object ParentInactive : CatalogViolation
}

/**
 * B4 (review): typed V-2 admission rejection tokens. A catalog-consuming commit that fails
 * revalidation (inactive/unknown/wrong-kind/not-leaf reference) must stay a typed rejection with
 * zero formal writes, but it must also be diagnosable instead of collapsing every shape into one
 * token. These tokens are consumed by the application [CatalogAdmissionViolation] mapping.
 */
sealed interface CatalogAdmissionRejection : DomainViolation {
    data object PaymentAccountNotFound : CatalogAdmissionRejection

    data object PaymentAccountInactive : CatalogAdmissionRejection

    data object PaymentAccountNotManageableFinancial : CatalogAdmissionRejection

    data object PaymentAccountWrongKind : CatalogAdmissionRejection

    data object CategoryNotFound : CatalogAdmissionRejection

    data object CategoryInactive : CatalogAdmissionRejection

    data object CategoryNotLeaf : CatalogAdmissionRejection

    data object CategoryKindMismatch : CatalogAdmissionRejection

    /** No authoritative catalog could be loaded inside the write transaction (fail closed). */
    data object CatalogUnavailable : CatalogAdmissionRejection
}

/**
 * Storage-neutral mutation vocabulary. The application layer derives an ordered list of these
 * from a pure transition; the store applies them in a single claim-first transaction.
 */
sealed interface CatalogWrite {
    data class InsertAccount(
        val account: Account,
    ) : CatalogWrite

    data class SetAccountActive(
        val accountId: AccountId,
        val active: Boolean,
    ) : CatalogWrite

    data class SetAccountName(
        val accountId: AccountId,
        val name: String,
    ) : CatalogWrite

    data class InsertCategory(
        val category: Category,
    ) : CatalogWrite

    data class SetCategoryActive(
        val categoryId: CategoryId,
        val active: Boolean,
    ) : CatalogWrite

    data class DeleteCategory(
        val categoryId: CategoryId,
    ) : CatalogWrite

    data class AppendNameHistory(
        val ownerKind: CatalogOwnerKind,
        val ownerId: String,
        val name: String,
    ) : CatalogWrite
}

/**
 * A-6 name normalization: trim, collapse runs of the ASCII space U+0020 and the ideographic
 * space U+3000 to a single ASCII space, reject empty/blank, any Unicode control character and
 * normalized names longer than [CATALOG_MAX_NAME_CODE_POINTS] code points.
 *
 * The control-character ruling is deliberately strict: only U+0020 and U+3000 are treated as
 * collapsible whitespace, while every code point in U+0000-U+001F (tab U+0009, newline U+000A,
 * carriage return U+000D included) and U+007F-U+009F is rejected as
 * [CatalogViolation.CatalogNameInvalid] and never folded.
 */
fun normalizeCatalogName(raw: String): DomainResult<String> {
    if (raw.any { isControlCodePoint(it) }) {
        return DomainResult.Failure(CatalogViolation.CatalogNameInvalid)
    }
    val collapsed = collapseWhitespace(raw)
    if (collapsed.isEmpty()) {
        return DomainResult.Failure(CatalogViolation.CatalogNameEmpty)
    }
    if (codePointCount(collapsed) > CATALOG_MAX_NAME_CODE_POINTS) {
        return DomainResult.Failure(CatalogViolation.CatalogNameTooLong)
    }
    return DomainResult.Success(collapsed)
}

/**
 * A-2 manageable account predicate. Deliberately distinct from the payment-option predicate in
 * the manual expense options provider (spec A11); both are kept side by side on purpose.
 */
fun isManageableAccount(
    account: Account,
    ledgerId: LedgerId,
): Boolean =
    account.ledgerId == ledgerId &&
        account.realAccount &&
        account.ownedByUser &&
        account.systemRole == null &&
        (account.kind == AccountKind.ASSET || account.kind == AccountKind.LIABILITY)

fun createManagedAccount(
    catalog: LedgerCatalog,
    ledgerId: LedgerId,
    accountId: AccountId,
    name: String,
    kind: AccountKind,
    currency: CurrencyUnit,
): DomainResult<List<CatalogWrite>> {
    if (kind != AccountKind.ASSET && kind != AccountKind.LIABILITY) {
        return DomainResult.Failure(CatalogViolation.AccountKindNotManageable)
    }
    val normalized =
        when (val result = normalizeCatalogName(name)) {
            is DomainResult.Failure -> return result
            is DomainResult.Success -> result.value
        }
    if (accountNameTaken(catalog, ledgerId, normalized, excluding = null)) {
        return DomainResult.Failure(CatalogViolation.CatalogNameConflict)
    }
    return DomainResult.Success(
        listOf(
            CatalogWrite.InsertAccount(
                Account(
                    id = accountId,
                    ledgerId = ledgerId,
                    kind = kind,
                    currency = currency,
                    ownedByUser = true,
                    realAccount = true,
                    systemRole = null,
                    name = normalized,
                    active = true,
                ),
            ),
        ),
    )
}

fun renameManagedAccount(
    catalog: LedgerCatalog,
    ledgerId: LedgerId,
    accountId: AccountId,
    newName: String,
): DomainResult<List<CatalogWrite>> {
    val account = catalogManageableAccount(catalog, ledgerId, accountId) ?: return accountFailure(catalog, ledgerId, accountId)
    val normalized =
        when (val result = normalizeCatalogName(newName)) {
            is DomainResult.Failure -> return result
            is DomainResult.Success -> result.value
        }
    if (accountNameTaken(catalog, ledgerId, normalized, excluding = account.id.value)) {
        return DomainResult.Failure(CatalogViolation.CatalogNameConflict)
    }
    return DomainResult.Success(
        listOf(
            CatalogWrite.SetAccountName(account.id, normalized),
            CatalogWrite.AppendNameHistory(CatalogOwnerKind.ACCOUNT, account.id.value, normalized),
        ),
    )
}

fun setManagedAccountActive(
    catalog: LedgerCatalog,
    ledgerId: LedgerId,
    accountId: AccountId,
    active: Boolean,
): DomainResult<List<CatalogWrite>> {
    val account = catalogManageableAccount(catalog, ledgerId, accountId) ?: return accountFailure(catalog, ledgerId, accountId)
    return DomainResult.Success(listOf(CatalogWrite.SetAccountActive(account.id, active)))
}

fun createCategoryGroup(
    catalog: LedgerCatalog,
    ledgerId: LedgerId,
    kind: CategoryKind,
    groupId: CategoryId,
    groupName: String,
    childId: CategoryId,
    childName: String,
    postingAccountId: AccountId,
    currency: CurrencyUnit,
): DomainResult<List<CatalogWrite>> {
    val normalizedGroup =
        when (val result = normalizeCatalogName(groupName)) {
            is DomainResult.Failure -> return result
            is DomainResult.Success -> result.value
        }
    val normalizedChild =
        when (val result = normalizeCatalogName(childName)) {
            is DomainResult.Failure -> return result
            is DomainResult.Success -> result.value
        }
    if (categoryNameTaken(catalog, ledgerId, kind, parentId = null, name = normalizedGroup, excluding = null)) {
        return DomainResult.Failure(CatalogViolation.CatalogNameConflict)
    }
    if (categoryNameTaken(catalog, ledgerId, kind, parentId = groupId.value, name = normalizedChild, excluding = null)) {
        return DomainResult.Failure(CatalogViolation.CatalogNameConflict)
    }
    return DomainResult.Success(
        listOf(
            CatalogWrite.InsertAccount(hiddenPostingAccount(postingAccountId, ledgerId, kind, currency, normalizedChild)),
            CatalogWrite.InsertCategory(Category(id = groupId, ledgerId = ledgerId, parentId = null, postingAccountId = null, active = true, kind = kind, name = normalizedGroup)),
            CatalogWrite.InsertCategory(Category(id = childId, ledgerId = ledgerId, parentId = groupId, postingAccountId = postingAccountId, active = true, kind = kind, name = normalizedChild)),
        ),
    )
}

fun appendCategoryChild(
    catalog: LedgerCatalog,
    ledgerId: LedgerId,
    parentId: CategoryId,
    childId: CategoryId,
    name: String,
    postingAccountId: AccountId,
    currency: CurrencyUnit,
): DomainResult<List<CatalogWrite>> {
    val parent = catalog.category(parentId)
    if (parent == null || parent.ledgerId != ledgerId) {
        // The requested parent id does not identify a category of this ledger (unknown or
        // owned by another ledger). Never guess a parent across ledgers (spec 6.3).
        return DomainResult.Failure(CatalogViolation.CategoryParentCrossLedger)
    }
    if (parent.parentId != null) {
        return DomainResult.Failure(CatalogViolation.CategoryLevelNotSupported)
    }
    if (parent.postingAccountId != null) {
        // D-066: a category that carries a posting account but no parent identity cannot be
        // confirmed as a level-1 group, so its level is "to be confirmed" rather than guessed.
        //
        // B2 reachability: this guard is kept for the domain contract, but the product path
        // cannot construct the shape. `catalog_category` CHECK forbids a level-1 row with a
        // non-null posting_account_id, and `validateProductCatalog` (load path) reasserts that
        // before any command sees the catalog. So `CategoryParentRequired` is load/domain-only,
        // not reachable from a product command (spec 6.3 mapping narrowed accordingly).
        return DomainResult.Failure(CatalogViolation.CategoryParentRequired)
    }
    val normalized =
        when (val result = normalizeCatalogName(name)) {
            is DomainResult.Failure -> return result
            is DomainResult.Success -> result.value
        }
    if (categoryNameTaken(catalog, ledgerId, parent.kind, parentId = parent.id.value, name = normalized, excluding = null)) {
        return DomainResult.Failure(CatalogViolation.CatalogNameConflict)
    }
    return DomainResult.Success(
        listOf(
            CatalogWrite.InsertAccount(hiddenPostingAccount(postingAccountId, ledgerId, parent.kind, currency, normalized)),
            CatalogWrite.InsertCategory(Category(id = childId, ledgerId = ledgerId, parentId = parent.id, postingAccountId = postingAccountId, active = true, kind = parent.kind, name = normalized)),
        ),
    )
}

fun renameManagedCategory(
    catalog: LedgerCatalog,
    ledgerId: LedgerId,
    categoryId: CategoryId,
    newName: String,
): DomainResult<List<CatalogWrite>> {
    val category = catalogManageableCategory(catalog, ledgerId, categoryId) ?: return categoryFailure(catalog, ledgerId, categoryId)
    val normalized =
        when (val result = normalizeCatalogName(newName)) {
            is DomainResult.Failure -> return result
            is DomainResult.Success -> result.value
        }
    if (categoryNameTaken(catalog, ledgerId, category.kind, category.parentId?.value, normalized, excluding = category.id.value)) {
        return DomainResult.Failure(CatalogViolation.CatalogNameConflict)
    }
    return DomainResult.Success(
        listOf(
            CatalogWrite.AppendNameHistory(CatalogOwnerKind.CATEGORY, category.id.value, normalized),
        ),
    )
}

fun setCategoryActive(
    catalog: LedgerCatalog,
    ledgerId: LedgerId,
    categoryId: CategoryId,
    active: Boolean,
): DomainResult<List<CatalogWrite>> {
    val category = catalogManageableCategory(catalog, ledgerId, categoryId) ?: return categoryFailure(catalog, ledgerId, categoryId)
    return if (category.parentId == null) {
        groupActiveWrites(catalog, category, active)
    } else {
        leafActiveWrites(catalog, category, active)
    }
}

fun enableCategoryGroup(
    catalog: LedgerCatalog,
    ledgerId: LedgerId,
    parentId: CategoryId,
): DomainResult<List<CatalogWrite>> {
    val group = catalogManageableCategory(catalog, ledgerId, parentId) ?: return categoryFailure(catalog, ledgerId, parentId)
    if (group.parentId != null) {
        return DomainResult.Failure(CatalogViolation.CategoryLevelNotSupported)
    }
    val writes = mutableListOf<CatalogWrite>(CatalogWrite.SetCategoryActive(group.id, true))
    catalog.categories.filter { it.parentId == group.id }.forEach { writes.add(CatalogWrite.SetCategoryActive(it.id, true)) }
    return DomainResult.Success(writes)
}

fun deleteManagedCategory(
    catalog: LedgerCatalog,
    ledgerId: LedgerId,
    categoryId: CategoryId,
    hasReferences: (CategoryId) -> Boolean,
): DomainResult<List<CatalogWrite>> {
    val category = catalogManageableCategory(catalog, ledgerId, categoryId) ?: return categoryFailure(catalog, ledgerId, categoryId)
    val children = if (category.parentId == null) catalog.categories.filter { it.parentId == category.id } else emptyList()
    if (hasReferences(category.id)) {
        return DomainResult.Failure(CatalogViolation.CategoryHasReferences)
    }
    if (category.parentId == null && children.any { hasReferences(it.id) }) {
        return DomainResult.Failure(CatalogViolation.CategoryHasReferences)
    }
    val writes = mutableListOf<CatalogWrite>()
    children.forEach { writes.add(CatalogWrite.DeleteCategory(it.id)) }
    writes.add(CatalogWrite.DeleteCategory(category.id))
    return DomainResult.Success(writes)
}

private fun groupActiveWrites(
    catalog: LedgerCatalog,
    group: Category,
    active: Boolean,
): DomainResult<List<CatalogWrite>> {
    val writes = mutableListOf<CatalogWrite>(CatalogWrite.SetCategoryActive(group.id, active))
    if (!active) {
        catalog.categories.filter { it.parentId == group.id }.forEach { writes.add(CatalogWrite.SetCategoryActive(it.id, false)) }
    }
    return DomainResult.Success(writes)
}

private fun leafActiveWrites(
    catalog: LedgerCatalog,
    leaf: Category,
    active: Boolean,
): DomainResult<List<CatalogWrite>> {
    val parentId = checkNotNull(leaf.parentId)
    if (active) {
        val parent = catalog.category(parentId)
        if (parent == null || !parent.active) {
            return DomainResult.Failure(CatalogViolation.ParentInactive)
        }
    } else {
        val siblingActive = catalog.categories.any { it.parentId == parentId && it.id != leaf.id && it.active }
        if (!siblingActive) {
            return DomainResult.Failure(CatalogViolation.LastActiveChildCategory)
        }
    }
    return DomainResult.Success(listOf(CatalogWrite.SetCategoryActive(leaf.id, active)))
}

private fun catalogManageableAccount(
    catalog: LedgerCatalog,
    ledgerId: LedgerId,
    accountId: AccountId,
): Account? {
    val account = catalog.account(accountId) ?: return null
    return if (isManageableAccount(account, ledgerId)) account else null
}

private fun accountFailure(
    catalog: LedgerCatalog,
    ledgerId: LedgerId,
    accountId: AccountId,
): DomainResult.Failure {
    val exists = catalog.accounts.any { it.id == accountId }
    return DomainResult.Failure(
        if (exists) CatalogViolation.AccountNotManageable else CatalogViolation.CatalogObjectNotFound,
    )
}

private fun categoryInLedger(
    catalog: LedgerCatalog,
    ledgerId: LedgerId,
    categoryId: CategoryId,
): Category? {
    val category = catalog.category(categoryId) ?: return null
    return if (category.ledgerId == ledgerId) category else null
}

private fun catalogManageableCategory(
    catalog: LedgerCatalog,
    ledgerId: LedgerId,
    categoryId: CategoryId,
): Category? = categoryInLedger(catalog, ledgerId, categoryId)

private fun categoryFailure(
    catalog: LedgerCatalog,
    ledgerId: LedgerId,
    categoryId: CategoryId,
): DomainResult.Failure {
    val exists = catalog.categories.any { it.id == categoryId }
    // B2 reachability: `CategoryNotManageable` is the domain answer for an existing category
    // that belongs to another ledger. The product load path scopes `selectCatalogCategories`
    // to one ledger and constructs every row with that ledger id, so a loaded product catalog
    // can never contain a foreign-ledger category; a missing category therefore always reports
    // `CatalogObjectNotFound`. The branch is retained for the domain contract; the spec 6.3
    // command mapping is narrowed accordingly.
    return DomainResult.Failure(
        if (exists) CatalogViolation.CategoryNotManageable else CatalogViolation.CatalogObjectNotFound,
    )
}

private fun accountNameTaken(
    catalog: LedgerCatalog,
    ledgerId: LedgerId,
    name: String,
    excluding: String?,
): Boolean =
    catalog.accounts.any {
        it.ledgerId == ledgerId && it.id.value != excluding && it.name.isNotEmpty() && it.name == name
    }

private fun categoryNameTaken(
    catalog: LedgerCatalog,
    ledgerId: LedgerId,
    kind: CategoryKind,
    parentId: String?,
    name: String,
    excluding: String?,
): Boolean =
    catalog.categories.any {
        it.ledgerId == ledgerId &&
            it.kind == kind &&
            it.parentId?.value == parentId &&
            it.id.value != excluding &&
            it.name.isNotEmpty() &&
            it.name == name
    }

private fun hiddenPostingAccount(
    id: AccountId,
    ledgerId: LedgerId,
    categoryKind: CategoryKind,
    currency: CurrencyUnit,
    name: String,
): Account =
    Account(
        id = id,
        ledgerId = ledgerId,
        kind = if (categoryKind == CategoryKind.EXPENSE) AccountKind.EXPENSE else AccountKind.INCOME,
        currency = currency,
        ownedByUser = false,
        realAccount = false,
        systemRole = null,
        name = name,
        active = true,
    )

private fun collapseWhitespace(raw: String): String {
    val builder = StringBuilder(raw.length)
    var pendingSpace = false
    for (character in raw) {
        if (character == ' ' || character == '\u3000') {
            if (builder.isNotEmpty()) pendingSpace = true
            continue
        }
        if (pendingSpace) {
            builder.append(' ')
            pendingSpace = false
        }
        builder.append(character)
    }
    return builder.toString()
}

private fun isControlCodePoint(character: Char): Boolean {
    val code = character.code
    return code < 0x20 || (code in 0x7F..0x9F)
}

private fun codePointCount(value: String): Int {
    var count = 0
    var index = 0
    while (index < value.length) {
        val character = value[index]
        index += if (character.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate()) 2 else 1
        count += 1
    }
    return count
}
