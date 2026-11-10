package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlin.time.Instant

/**
 * P7-03.A ledger-view entry row (D-145; spec section 4.1.1). One current-version
 * transaction of the requested ledger. `kind` carries the effective business kind
 * `COALESCE(canonical_kind, kind)` so LEND/COLLECT/REFUND_RECEIPT and the other newer
 * kinds are no longer misread as the legacy-compatible `EXPENSE` column value. The row
 * carries both persisted times (R-Q06-1) and the current version's note.
 *
 * P7-05 (D-156) re-freeze: this row set is "current-version AND not voided", and so is the
 * [CurrentVersionRow] set behind the HOME balances and the Analysis tab. The 22 D-145 test
 * anchors stay value-identical on a fact-free ledger because the effective predicate is a
 * no-op there; a voided transaction is reachable only through [VoidedTransactionRow].
 */
data class LedgerEntryRow(
    val transactionId: TransactionId,
    val currentVersionId: TransactionVersionId,
    val kind: TransactionKind,
    val occurredAt: Instant,
    val statisticsAt: Instant,
    val note: String?,
    val postings: List<Posting>,
)

/** Reverse creation lineage hit in `import_confirmation` (Appendix A; only ids, no source names — R-9). */
data class ImportCreationConfirmationRow(
    val confirmationId: String,
    val requestId: String,
    val candidateId: String,
    val transactionId: TransactionId,
    val operationClass: String,
    val confirmedAt: String?,
)

/** The manual receipt table a creation receipt was found in (transaction_id UNIQUE each). */
enum class ManualCreationChain {
    EXPENSE,
    INCOME,
    TRANSFER,
    LENDING,
}

/** Reverse creation lineage hit in one of the manual four-chain receipt tables (Appendix A). */
data class ManualCreationReceiptRow(
    val chain: ManualCreationChain,
    val confirmationId: String,
) {
    init {
        require(confirmationId.isNotBlank()) { "manual creation receipt confirmation id must not be blank" }
    }
}

/**
 * Creation-entry display enumeration (spec section 4.2.2). Resolution precedence:
 * import confirmation hit, else manual receipt hit, else unmarked — never guessed
 * from evidence links (P703SPEC-11 mirror vector) or other clues (R-9).
 */
enum class CreationEntry(
    val label: String,
) {
    IMPORT_CREATED("导入创建"),
    MANUAL_CREATED("手工创建"),
    UNMARKED("来源未标注"),
}

/**
 * P7-03.A read-only reconciliation leg projection row for one posting of the current
 * version (Appendix A `transactionReconciliationLegs`). Purely a projection of existing
 * rows: `hasReconciliationRow` reflects an actual `posting_reconciliation` row,
 * `hasActiveEvidenceLink` an active latest-history evidence link, and
 * `rg03ReconciliationEligible` reads the existing `rg03_transfer_posting_semantic`
 * column as-is (`null` = no rg03 row).
 *
 * P8-01a (spec section 2.1; D-308 item 9): the three source columns of the composite mark ride
 * the same query as a LEFT join on `catalog_account`, so a posting whose account has no catalog
 * row keeps every pre-P8-01a eligibility outcome (all three are `null`) and
 * [hasReconciliationCompositeMark] is false — the derivation is additive-only.
 */
data class TransactionReconciliationLegRow(
    val postingId: PostingId,
    val postingIndex: Int,
    val accountId: AccountId,
    val amountMinor: Long,
    val currency: CurrencyUnit,
    val statusStorageValue: String,
    val hasReconciliationRow: Boolean,
    val hasActiveEvidenceLink: Boolean,
    val rg03ReconciliationEligible: Boolean?,
    /** `catalog_account.owned_by_user`; `null` when the account has no catalog row. */
    val accountOwnedByUser: Boolean?,
    /** `catalog_account.real_account`; `null` when the account has no catalog row. */
    val accountRealAccount: Boolean?,
    /** `catalog_account.kind`; `null` when the account has no catalog row. */
    val accountKind: AccountKind?,
)

/**
 * P8-01a spec section 2.1 composite mark (D-308 item 9): a leg's account must be
 * `owned_by_user = 1 AND real_account = 1 AND kind IN ('ASSET','LIABILITY')`. `active`
 * deliberately does not participate (deactivating an account does not rewrite its history,
 * `ACCOUNTING_RULES.md:259`), system/external accounts are excluded by `owned_by_user`, and the
 * hidden posting accounts fall outside the two real kinds because they follow their leaf expense
 * or income category (`ACCOUNTING_RULES.md:261`). The RG-07 golden fixture's stored-value
 * exclusion is a scenario rule and is NOT adopted here: stored-value asset legs must stay
 * reconcilable (`ACCOUNTING_RULES.md:188`). A missing catalog row marks nothing.
 */
fun hasReconciliationCompositeMark(row: TransactionReconciliationLegRow): Boolean =
    row.accountOwnedByUser == true &&
        row.accountRealAccount == true &&
        (row.accountKind == AccountKind.ASSET || row.accountKind == AccountKind.LIABILITY)

/**
 * Reconciliation eligibility per the frozen registered interpretation (spec section 3.2.1 as
 * revised by the P8-01a spec section 2.1 and D-308 item 9). An rg03 semantic row always wins: it
 * marks the leg eligible, or explicitly excludes it (the fee leg), and it takes precedence over
 * every later clause. With no rg03 row the leg is eligible when it satisfies the section 2.1
 * composite mark ([hasReconciliationCompositeMark]), has an active evidence link, or has a
 * reconciliation status row — the two pre-P8-01a clauses kept verbatim.
 *
 * ADDITIVE-ONLY: the composite mark is an added `OR` term and never a replacement, so no leg
 * can become ineligible because of it. Read-only: no reconciliation state is created or changed.
 */
fun isReconciliationEligible(row: TransactionReconciliationLegRow): Boolean =
    when (row.rg03ReconciliationEligible) {
        false -> false
        true -> true
        null -> hasReconciliationCompositeMark(row) || row.hasActiveEvidenceLink || row.hasReconciliationRow
    }

/**
 * Transaction-level rollup over eligible legs only (R-Q07-4): any MISSING wins, then any
 * DIFFERENCE, then any PARTIAL, then all CHECKED, otherwise PENDING. `null` means no
 * eligible leg exists (「无对账资格」) and no rollup may be displayed.
 */
fun rollupReconciliationStatus(eligibleLegStatuses: List<P408ReconciliationStatus>): P408ReconciliationStatus? {
    if (eligibleLegStatuses.isEmpty()) return null
    if (eligibleLegStatuses.any { it == P408ReconciliationStatus.MISSING }) return P408ReconciliationStatus.MISSING
    if (eligibleLegStatuses.any { it == P408ReconciliationStatus.DIFFERENCE }) return P408ReconciliationStatus.DIFFERENCE
    if (eligibleLegStatuses.any { it == P408ReconciliationStatus.PARTIAL }) return P408ReconciliationStatus.PARTIAL
    if (eligibleLegStatuses.all { it == P408ReconciliationStatus.CHECKED }) return P408ReconciliationStatus.CHECKED
    return P408ReconciliationStatus.PENDING
}
