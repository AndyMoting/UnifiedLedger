package com.unifiedledger.domain

/**
 * P7-02.C L-1 minimal stable counterparty directory (product side, never the RG-08 silo).
 *
 * A counterparty owns a stable [CounterpartyId], one append-only name history and one dedicated
 * same-ledger receivable account. Renaming never changes the receivable account, the position
 * balance or any history row (it only supersedes the current name version and appends a new one),
 * so the directory contract is "stable id, append-only history, rename does not move money"
 * (`ACCOUNTING_RULES.md:74`).
 */
data class CounterpartyId(
    val value: String,
)

/**
 * One append-only name version. Exactly one version is [current] for a live counterparty; the
 * displayed name is always the current version (`ACCOUNTING_RULES.md:74`).
 */
data class CounterpartyNameVersion(
    val versionNumber: Int,
    val name: String,
    val current: Boolean,
)

data class Counterparty(
    val id: CounterpartyId,
    val ledgerId: LedgerId,
    val name: String,
    val receivableAccountId: AccountId,
    val active: Boolean,
    val nameHistory: List<CounterpartyNameVersion>,
)

/**
 * P7-02.C counterparty-directory tokens. Name normalization reuses the frozen P7-01
 * [normalizeCatalogName] contract (fold only U+0020/U+3000, reject every other control code point,
 * enforce the code-point limit) and therefore reports its [CatalogViolation] family unchanged.
 */
sealed interface CounterpartyViolation : DomainViolation {
    data object CounterpartyNotFound : CounterpartyViolation

    data object CounterpartyInactive : CounterpartyViolation
}

/**
 * L-1: creates a counterparty with version 1 of its name history marked current. The receivable
 * account is minted by the caller (see [counterpartyReceivableAccount]) and never derived here.
 */
fun createCounterparty(
    id: CounterpartyId,
    ledgerId: LedgerId,
    name: String,
    receivableAccountId: AccountId,
): DomainResult<Counterparty> {
    if (id.value.isBlank()) return DomainResult.Failure(DomainViolation.InvalidCatalog)
    val normalized =
        when (val result = normalizeCatalogName(name)) {
            is DomainResult.Failure -> return result
            is DomainResult.Success -> result.value
        }
    return DomainResult.Success(
        Counterparty(
            id = id,
            ledgerId = ledgerId,
            name = normalized,
            receivableAccountId = receivableAccountId,
            active = true,
            nameHistory = listOf(CounterpartyNameVersion(versionNumber = 1, name = normalized, current = true)),
        ),
    )
}

/**
 * L-1: renames a counterparty by superseding the current name version and appending a new current
 * one. The receivable account, active flag and principal position are untouched. Renaming to the
 * already-current normalized name is a no-op success and appends no version.
 */
fun renameCounterparty(
    counterparty: Counterparty,
    newName: String,
): DomainResult<Counterparty> {
    val normalized =
        when (val result = normalizeCatalogName(newName)) {
            is DomainResult.Failure -> return result
            is DomainResult.Success -> result.value
        }
    if (normalized == counterparty.name) return DomainResult.Success(counterparty)
    val nextVersion = (counterparty.nameHistory.maxOfOrNull { it.versionNumber } ?: 0) + 1
    val history =
        counterparty.nameHistory.map { it.copy(current = false) } +
            CounterpartyNameVersion(versionNumber = nextVersion, name = normalized, current = true)
    return DomainResult.Success(
        counterparty.copy(
            name = normalized,
            nameHistory = history,
        ),
    )
}

/** L-1: activates/deactivates a counterparty without touching its name history or position. */
fun setCounterpartyActive(
    counterparty: Counterparty,
    active: Boolean,
): DomainResult<Counterparty> = DomainResult.Success(counterparty.copy(active = active))

/**
 * L-1 receivable account shape. It is deliberately the opposite of the RG-08 replay predicate:
 * the product receivable account is `ASSET`, `owned_by_user=0`, `real_account=0`, `hidden=1`,
 * `system_role=NULL` (`!!ownedByUser && !realAccount` is exactly how the catalog store derives the
 * persisted `hidden` flag, so it never needs a separate field), while `Rg08Operations` requires a
 * receivable account with `ownedByUser && realAccount` (`Rg08Operations.kt:837-843`). The two
 * predicates describe different persistence surfaces (product `counterparty`/`lending_position`
 * vs the `rgXX_` silo) and are never wired together (L-1, L-5); no single account satisfies both.
 *
 * D-143 disclosure: because this account fails the A-2 manageable predicate
 * (`ownedByUser && realAccount && kind==ASSET`, `CatalogManagement.kt:152-160`) it never appears in
 * the ordinary account-management surface.
 */
fun counterpartyReceivableAccount(
    id: AccountId,
    ledgerId: LedgerId,
    currency: CurrencyUnit,
    name: String,
): Account =
    Account(
        id = id,
        ledgerId = ledgerId,
        kind = AccountKind.ASSET,
        currency = currency,
        ownedByUser = false,
        realAccount = false,
        systemRole = null,
        name = name,
        active = true,
    )
