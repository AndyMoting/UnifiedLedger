package com.unifiedledger.application

import com.unifiedledger.domain.BudgetId
import com.unifiedledger.domain.BudgetScope
import com.unifiedledger.domain.BudgetViolation
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.LedgerId
import kotlinx.datetime.YearMonth
import kotlin.time.Instant

/*
 * P7-07 budget slice 07.B (configuration model), approved by D-184 item 3 /
 * `docs/specs/2026-09-28-p7-07-budget-design.md` section 3. This file mirrors the P7-01
 * catalog owner's protocol shape (`CatalogManagement.kt`): one request shape carrying a
 * canonical `requestSnapshot` (the sole equivalent-replay basis), a derived
 * `inputFingerprint` that never participates in equivalence, an optimistic
 * `expectedRevision`, and a four-state result family Accepted / NoChange / Rejected /
 * Conflict.
 *
 * The `YearMonth`-bearing types live here, not in `ledger-domain`, because `ledger-domain`
 * deliberately declares no `kotlinx-datetime` dependency (documented by the merged 07.A
 * slice in `BudgetMonth.kt`). The pure `BudgetScope`/`BudgetViolation` value objects stay in
 * `ledger-domain`.
 */

/** The 07.B configuration currency: CNY with precision 2 (spec section 3.1). */
val BUDGET_CONFIGURED_CURRENCY: CurrencyUnit = CurrencyUnit("CNY", 2)

/**
 * Stable budget failure codes (spec sections 3.3/3.4). [code] is the frozen literal compared
 * by consumers; enum constant names are only an internal spelling. Messages are never compared.
 */
enum class BudgetFailureCode(
    val code: String,
) {
    BUDGET_LIMIT_NEGATIVE("BudgetLimitNegative"),
    BUDGET_SCOPE_UNSUPPORTED("BudgetScopeUnsupported"),
    BUDGET_SCOPE_CATEGORY_INVALID("BudgetScopeCategoryInvalid"),
    BUDGET_CURRENCY_UNSUPPORTED("BudgetCurrencyUnsupported"),
    BUDGET_REVISION_CONFLICT("BudgetRevisionConflict"),
    REQUEST_IDENTITY_CONFLICT("RequestIdentityConflict"),
    BUDGET_CONSTRAINT_VIOLATION("BudgetConstraintViolation"),
    ;

    companion object {
        /**
         * Maps a domain [BudgetViolation] to its frozen failure code. Mirrors the
         * `CatalogFailureCode.of` precedent — a domain-violation -> code mapper that the store's
         * abort path calls in production (`SqlDelightCatalogStore.kt:145`,
         * `abortCatalog(CatalogFailureCode.of(result.violation))`) — so any other domain violation
         * falls back to the generic constraint code.
         */
        fun of(violation: DomainViolation): BudgetFailureCode =
            when (violation) {
                BudgetViolation.LimitNegative -> BUDGET_LIMIT_NEGATIVE
                BudgetViolation.ScopeUnsupported -> BUDGET_SCOPE_UNSUPPORTED
                BudgetViolation.ScopeCategoryInvalid -> BUDGET_SCOPE_CATEGORY_INVALID
                BudgetViolation.CurrencyUnsupported -> BUDGET_CURRENCY_UNSUPPORTED
                else -> BUDGET_CONSTRAINT_VIOLATION
            }
    }
}

/**
 * The two 07.B configuration commands (spec sections 3.3/3.4). `SetLimit` covers add and
 * modify (a fresh identity starts at revision 0 and appends its first history row); `Close`
 * stops monitoring by appending a CLOSED history row and never deletes configuration or
 * history. A zero limit is a valid monitored budget, distinct from close/unset.
 */
sealed interface BudgetCommandPayload {
    val commandName: String

    data class SetLimit(
        val limitMinorUnits: Long,
    ) : BudgetCommandPayload {
        override val commandName: String = "SetBudgetLimit"
    }

    data object Close : BudgetCommandPayload {
        override val commandName: String = "CloseBudget"
    }
}

/**
 * The stable identity of a budget configuration target (spec section 3.1):
 * `(ledgerId, month, currency, scope)`. [monthKey] is the canonical `YYYY-MM` text and
 * [scopeKey] the canonical scope token; [scopeCategoryId] is present exactly for a category
 * scope. The commit port derives the stable [BudgetId] for a fresh identity from these.
 */
data class BudgetTarget(
    val ledgerId: LedgerId,
    val monthKey: String,
    val currency: CurrencyUnit,
    val scopeKey: String,
    val scopeCategoryId: CategoryId?,
)

data class BudgetRequestId(
    val value: String,
)

data class BudgetCommandRequest(
    val target: BudgetTarget,
    val requestId: BudgetRequestId,
    val requestSnapshot: String,
    val inputFingerprint: String,
    val expectedRevision: Long,
    val command: BudgetCommandPayload,
    val createdAt: Instant,
)

/** Persisted request outcome value domain; only successful claims reach a terminal row. */
enum class BudgetReceiptOutcome {
    ACCEPTED,
    NO_CHANGE,
}

data class BudgetCommandReceipt(
    val requestId: BudgetRequestId,
    val outcome: BudgetReceiptOutcome,
    val budgetId: BudgetId,
    val newRevision: Long,
)

sealed interface BudgetCommandResult {
    data class Accepted(
        val receipt: BudgetCommandReceipt,
    ) : BudgetCommandResult

    data class NoChange(
        val receipt: BudgetCommandReceipt,
    ) : BudgetCommandResult

    data class Rejected(
        val failureCode: BudgetFailureCode,
    ) : BudgetCommandResult

    data class Conflict(
        val failureCode: BudgetFailureCode,
    ) : BudgetCommandResult
}

/**
 * Current authoritative budget configuration for one identity (spec section 3.4):
 * the stable id, the CAS [revision] and the current [limitMinorUnits] (`null` when closed).
 */
data class BudgetAuthority(
    val budgetId: BudgetId,
    val target: BudgetTarget,
    val revision: Long,
    val limitMinorUnits: Long?,
)

/**
 * Claim-first atomic budget configuration boundary (spec section 3.4), mirroring
 * [CatalogManagementCommitPort]. Implementations MUST:
 *
 * - claim `(ledgerId, requestId)` in the same transaction as work, history and receipt;
 * - return [BudgetCommandResult.NoChange] with the original receipt when an existing claim
 *   has an equivalent `requestSnapshot`, and [BudgetCommandResult.Conflict] with
 *   `RequestIdentityConflict` when it differs (zero write in both cases);
 * - reject `expectedRevision` mismatches with `BudgetRevisionConflict` and zero writes;
 * - append exactly one immutable settings-history row per accepted add/modify/close;
 * - advance the current revision by exactly one and persist the terminal request row plus
 *   immutable receipt in one transaction;
 * - invoke [mintBudgetId] at most once and only for a fresh identity (the minted id is
 *   discarded on an equivalent replay, whose original receipt already carries the stable id).
 */
fun interface BudgetConfigurationCommitPort {
    fun commitOnce(
        request: BudgetCommandRequest,
        mintBudgetId: () -> BudgetId,
    ): BudgetCommandResult
}

/*
 * Spec section 3.5's category-delete reference rule needs no separate budget probe: the
 * catalog owner's `CatalogCategoryReferenceProbe` is widened to include the budget reference
 * surface (`catalogReferencedBudgetCategoryIds`), so the single delete path already refuses a
 * category that any current OR historical budget names.
 */

/**
 * Independent budget request id source (spec section 3.4). Kept separate from the catalog and
 * manual-entry sources so no consumption count is shared.
 */
fun interface BudgetRequestIdSource {
    fun next(): BudgetRequestId
}

class UuidV7BudgetRequestIdSource(
    private val generator: UuidV7Generator,
) : BudgetRequestIdSource {
    override fun next(): BudgetRequestId = BudgetRequestId(generator.next())
}

/** Minted stable id for a fresh budget identity; only consumed when a fresh claim is applied. */
fun interface BudgetIdSource {
    fun next(): BudgetId
}

class UuidV7BudgetIdSource(
    private val generator: UuidV7Generator,
) : BudgetIdSource {
    override fun next(): BudgetId = BudgetId(generator.next())
}

/**
 * Canonical scope token (spec section 3.1). `TOTAL` or `CATEGORY:<categoryId>`. This is the
 * scope identity carried in the UNIQUE key; the nullable `scope_category_id` column alone
 * cannot key a TOTAL scope because SQLite treats NULLs as distinct.
 */
fun budgetScopeKey(scope: BudgetScope): String =
    when (scope) {
        BudgetScope.Total -> "TOTAL"
        is BudgetScope.Category -> "CATEGORY:${scope.categoryId.value}"
    }

/**
 * Canonical month key `YYYY-MM` (spec section 3.1). `kotlinx.datetime.YearMonth.toString()`
 * is the ISO-8601 extended month form (`2026-03`), zero-padded and stable, so it is used
 * directly rather than re-assembling the fields (`monthNumber` is internal to YearMonth).
 */
fun budgetMonthKey(month: YearMonth): String = month.toString()

/**
 * Canonical command-payload copy: the only equivalent-replay basis (spec section 3.4).
 * Field order is fixed and every value is JCS-escaped, so the same logical command always
 * yields the same bytes across retries and platforms. The request id is deliberately absent
 * (it is the claim identity, not the payload), and `expectedRevision` is excluded so a
 * stale-revision retry of the SAME payload still replays the original receipt.
 */
fun canonicalBudgetRequestSnapshot(payload: BudgetCommandPayload): String =
    when (payload) {
        is BudgetCommandPayload.SetLimit ->
            "{\"command\":${jcsString(payload.commandName)},\"limit_minor\":${jcsString(payload.limitMinorUnits.toString())}}"
        BudgetCommandPayload.Close ->
            "{\"command\":${jcsString(payload.commandName)}}"
    }

/** Derived integrity digest; explicitly not part of equivalent-replay identity (spec A4). */
fun budgetInputFingerprint(snapshot: String): String = "sha256:" + Sha256.digestHex(snapshot.encodeToByteArray())

/** Read port for the current configuration of one identity (spec section 3.4). */
fun interface BudgetAuthorityReader {
    fun load(target: BudgetTarget): BudgetAuthority?
}

/** One immutable settings-history entry (spec section 3.4). */
data class BudgetSettingsVersion(
    val revisionNumber: Long,
    val closed: Boolean,
    val limitMinorUnits: Long?,
    val requestId: BudgetRequestId,
    val createdAt: String,
)

/**
 * 07.B save/close use case (spec sections 3.3/3.4). Validates the payload as a typed
 * rejection before any claim, derives the canonical snapshot/fingerprint, and delegates the
 * atomic claim/history/receipt boundary to the injected [BudgetConfigurationCommitPort].
 * A category scope must be an existing `CategoryKind.EXPENSE` category and a negative limit
 * is refused; neither check writes anything. Spec section 3.5 additionally refuses a NEW
 * binding (`expectedRevision == 0`) to a deactivated expense category while leaving an
 * already-bound inactive category modifiable/closable (see [target]).
 */
class SaveBudgetConfiguration(
    private val commitPort: BudgetConfigurationCommitPort,
    private val requestIdSource: BudgetRequestIdSource,
    private val budgetIdSource: BudgetIdSource,
    private val catalogReader: CatalogAuthorityReader,
    private val clock: LedgerClock,
    private val configuredCurrency: CurrencyUnit = BUDGET_CONFIGURED_CURRENCY,
) {
    /**
     * Add or modify the limit of `(ledgerId, month, currency, scope)`. `expectedRevision` is
     * the CAS pointer: `0` for a fresh identity, the current revision otherwise.
     */
    fun setLimit(
        ledgerId: LedgerId,
        month: YearMonth,
        scope: BudgetScope,
        limitMinorUnits: Long,
        expectedRevision: Long,
    ): BudgetCommandResult {
        if (limitMinorUnits < 0L) {
            return BudgetCommandResult.Rejected(BudgetFailureCode.BUDGET_LIMIT_NEGATIVE)
        }
        val budgetTarget =
            target(ledgerId, month, scope, expectedRevision)
                ?: return BudgetCommandResult.Rejected(BudgetFailureCode.BUDGET_SCOPE_CATEGORY_INVALID)
        val payload = BudgetCommandPayload.SetLimit(limitMinorUnits)
        return execute(budgetTarget, expectedRevision, payload)
    }

    /** Stop monitoring by appending a CLOSED history row; configuration and history are kept. */
    fun close(
        ledgerId: LedgerId,
        month: YearMonth,
        scope: BudgetScope,
        expectedRevision: Long,
    ): BudgetCommandResult {
        val budgetTarget =
            target(ledgerId, month, scope, expectedRevision)
                ?: return BudgetCommandResult.Rejected(BudgetFailureCode.BUDGET_SCOPE_CATEGORY_INVALID)
        return execute(budgetTarget, expectedRevision, BudgetCommandPayload.Close)
    }

    /**
     * Spec section 3.1: a category scope must name a stable `CategoryKind.EXPENSE` category
     * of the ledger catalog. Spec section 3.5 adds the activation nuance: a NEW binding — a
     * fresh identity with `expectedRevision == 0` — must NOT name a deactivated expense
     * category, while an EXISTING budget (`expectedRevision > 0`) whose category was later
     * deactivated stays modifiable and closable ("停用分类保留既有预算及统计"; adjusting an
     * existing limit is explicitly NOT re-enabling the category). A missing, non-EXPENSE or
     * newly-bound inactive category is a typed rejection that writes nothing.
     */
    private fun target(
        ledgerId: LedgerId,
        month: YearMonth,
        scope: BudgetScope,
        expectedRevision: Long,
    ): BudgetTarget? {
        val scopeKey = budgetScopeKey(scope)
        if (scope !is BudgetScope.Category) {
            return BudgetTarget(ledgerId, budgetMonthKey(month), configuredCurrency, scopeKey, null)
        }
        val catalog = catalogReader.load(ledgerId)?.catalog ?: return null
        val category =
            catalog.categories.firstOrNull { it.id == scope.categoryId }
                ?: return null
        if (category.kind != CategoryKind.EXPENSE) return null
        if (expectedRevision == 0L && !category.active) return null
        return BudgetTarget(ledgerId, budgetMonthKey(month), configuredCurrency, scopeKey, scope.categoryId)
    }

    private fun execute(
        target: BudgetTarget,
        expectedRevision: Long,
        payload: BudgetCommandPayload,
    ): BudgetCommandResult {
        val snapshot = canonicalBudgetRequestSnapshot(payload)
        val request =
            BudgetCommandRequest(
                target = target,
                requestId = requestIdSource.next(),
                requestSnapshot = snapshot,
                inputFingerprint = budgetInputFingerprint(snapshot),
                expectedRevision = expectedRevision,
                command = payload,
                createdAt = clock.now(),
            )
        return commitPort.commitOnce(request) { budgetIdSource.next() }
    }
}
