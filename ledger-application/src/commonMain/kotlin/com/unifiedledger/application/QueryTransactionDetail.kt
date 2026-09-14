package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlin.time.Instant

/**
 * P7-03.A read-only transaction detail projection (spec section 4.2.2). Carries the
 * effective kind, both times separately (R-Q06-1), the current version note, amount legs
 * with account and category current names (R-Q07-2; a real-account leg without a
 * `catalog_category.posting_account_id` mapping is an absent category 「无分类」， never a
 * failure — P703SPEC-09), the creation-entry lineage (import confirmation, else manual
 * receipt, else unmarked — never guessed; R-9 keeps source names out) and the read-only
 * multi-leg reconciliation projection with the frozen rollup priority chain (R-Q07-4,
 * section 3.2.1). Zero writes: reconciliation state is never created or changed.
 */
class QueryTransactionDetail(
    private val readPort: LedgerCurrentStateReadPort,
    private val ledgerId: LedgerId,
    private val catalog: LedgerCatalog,
) {
    private val accountsById = catalog.accounts.associateBy { it.id }
    private val categoryByPostingAccount =
        catalog.categories
            .filter { it.postingAccountId != null }
            .sortedBy { it.id.value }
            .associateBy { requireNotNull(it.postingAccountId) }

    fun query(transactionId: TransactionId): TransactionDetailResult {
        val rows =
            try {
                readPort.loadLedgerEntryRows(ledgerId)
            } catch (failure: Exception) {
                return TransactionDetailResult.Unavailable
            }
        val row = rows.firstOrNull { it.transactionId == transactionId } ?: return TransactionDetailResult.NotFound

        for (posting in row.postings) {
            val account = accountsById[posting.accountId] ?: return TransactionDetailResult.InvalidState
            if (account.ledgerId != ledgerId) return TransactionDetailResult.InvalidState
            if (account.currency != posting.amount.currency) return TransactionDetailResult.InvalidState
        }

        val creationEntry =
            try {
                resolveCreationEntry(transactionId)
            } catch (failure: Exception) {
                return TransactionDetailResult.Unavailable
            }
        val reconciliationLegRows =
            try {
                readPort.loadTransactionReconciliationLegs(ledgerId, transactionId).associateBy { it.postingId }
            } catch (failure: Exception) {
                return TransactionDetailResult.Unavailable
            }

        val legs =
            row.postings.map { posting ->
                val account = checkNotNull(accountsById[posting.accountId])
                TransactionDetailLeg(
                    postingId = posting.id,
                    accountId = posting.accountId,
                    accountName = account.name,
                    amount = posting.amount,
                    // Absent category presentation (P703SPEC-09): null, never a failure.
                    categoryName = categoryByPostingAccount[posting.accountId]?.name,
                )
            }
        val reconciliation =
            try {
                buildReconciliationProjection(legs, reconciliationLegRows)
            } catch (failure: Exception) {
                return TransactionDetailResult.InvalidState
            }

        return TransactionDetailResult.Success(
            TransactionDetail(
                ledgerId = ledgerId,
                transactionId = row.transactionId,
                currentVersionId = row.currentVersionId,
                kind = row.kind,
                occurredAt = row.occurredAt,
                statisticsAt = row.statisticsAt,
                note = row.note,
                legs = legs,
                creationEntry = creationEntry,
                reconciliation = reconciliation,
            ),
        )
    }

    /**
     * Frozen precedence (section 4.2.2): import confirmation hit → 导入创建， else manual
     * receipt hit → 手工创建， else 来源未标注. Evidence links are deliberately never consulted
     * here: mirror evidence appended to an existing transaction cannot prove an import
     * creation (P703SPEC-11 mirror vector).
     */
    private fun resolveCreationEntry(transactionId: TransactionId): CreationEntry =
        when {
            readPort.findImportCreationConfirmation(ledgerId, transactionId) != null -> CreationEntry.IMPORT_CREATED
            readPort.findManualCreationReceipt(ledgerId, transactionId) != null -> CreationEntry.MANUAL_CREATED
            else -> CreationEntry.UNMARKED
        }

    private fun buildReconciliationProjection(
        legs: List<TransactionDetailLeg>,
        legRowsByPostingId: Map<PostingId, TransactionReconciliationLegRow>,
    ): TransactionReconciliationProjection {
        val projectionLegs =
            legs.map { leg ->
                val legRow = legRowsByPostingId[leg.postingId]
                val eligible = legRow != null && isReconciliationEligible(legRow)
                TransactionReconciliationLeg(
                    leg = leg,
                    eligible = eligible,
                    // An ineligible leg never participates and displays 无对账资格 (null status).
                    status = if (eligible) P408ReconciliationStatus.fromStorage(checkNotNull(legRow).statusStorageValue) else null,
                )
            }
        val rollup =
            rollupReconciliationStatus(
                projectionLegs.mapNotNull { leg -> leg.status?.takeIf { leg.eligible } },
            )
        return TransactionReconciliationProjection(legs = projectionLegs, rollup = rollup)
    }
}

/** One amount leg of the detail payload: account name + exact amount + currency (R-Q07-2 names). */
data class TransactionDetailLeg(
    val postingId: PostingId,
    val accountId: AccountId,
    val accountName: String,
    val amount: Money,
    /** Current category name or `null` when the leg has no category mapping (无分类， P703SPEC-09). */
    val categoryName: String?,
)

data class TransactionReconciliationLeg(
    val leg: TransactionDetailLeg,
    val eligible: Boolean,
    /** Display status of an eligible leg; `null` for an ineligible leg (「—（无对账资格）」）. */
    val status: P408ReconciliationStatus?,
)

data class TransactionReconciliationProjection(
    val legs: List<TransactionReconciliationLeg>,
    /** Rollup over eligible legs only; `null` = 无对账资格 (R-Q07-4). */
    val rollup: P408ReconciliationStatus?,
)

data class TransactionDetail(
    val ledgerId: LedgerId,
    val transactionId: TransactionId,
    val currentVersionId: TransactionVersionId,
    val kind: TransactionKind,
    val occurredAt: Instant,
    val statisticsAt: Instant,
    val note: String?,
    val legs: List<TransactionDetailLeg>,
    val creationEntry: CreationEntry,
    val reconciliation: TransactionReconciliationProjection,
)

sealed interface TransactionDetailResult {
    data class Success(
        val detail: TransactionDetail,
    ) : TransactionDetailResult

    /** The requested transaction is not in this ledger's current-version set (detail-only). */
    data object NotFound : TransactionDetailResult

    /** Catalog/posting inconsistency (section 4.3). */
    data object InvalidState : TransactionDetailResult

    /** Read-port exception (database unavailable). */
    data object Unavailable : TransactionDetailResult
}
