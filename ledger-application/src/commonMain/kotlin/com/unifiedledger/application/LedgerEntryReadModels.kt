package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
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
)

/**
 * Reconciliation eligibility per the frozen registered interpretation (spec section 3.2.1):
 * eligible iff an rg03 semantic row marks the posting eligible (a), or — when no rg03 row
 * exists — the posting has an active evidence link or a reconciliation status row (b).
 * An rg03 row with `reconciliation_eligible = 0` (fee leg) is explicitly excluded and
 * takes precedence over (b). Read-only: no reconciliation state is created or changed.
 */
fun isReconciliationEligible(row: TransactionReconciliationLegRow): Boolean =
    when (row.rg03ReconciliationEligible) {
        false -> false
        true -> true
        null -> row.hasActiveEvidenceLink || row.hasReconciliationRow
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
