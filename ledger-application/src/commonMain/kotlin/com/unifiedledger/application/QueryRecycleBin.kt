package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.P705FailureCode
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.TransactionVoidFactKind
import com.unifiedledger.domain.VoidReason
import kotlin.time.Instant

/**
 * P7-05.C recycle-bin read model (spec sections 3.4/4.4; DP-1).
 *
 * One voided-state transaction of the requested ledger plus the metadata the recycle bin must
 * show: the frozen reason, the audit void time and the dependency material (current names and
 * admissibility of the referenced accounts/categories, effective refund linkage, creation
 * entry). The row is the effective [LedgerEntryRow] shape plus void metadata, derived from the
 * same table group and the same effective-predicate view — the recycle bin never invents a
 * second source of truth.
 */
data class VoidedTransactionRow(
    val transactionId: TransactionId,
    val currentVersionId: TransactionVersionId,
    val kind: TransactionKind,
    val occurredAt: Instant,
    val statisticsAt: Instant,
    val note: String?,
    val postings: List<Posting>,
    val voidFactKind: TransactionVoidFactKind,
    val voidReason: VoidReason,
    val voidedAt: Instant,
)

/** One referenced leg as the recycle bin must explain it (current name + admissibility). */
data class RecycleBinDependencyLeg(
    val postingId: PostingId,
    val accountId: AccountId,
    val accountName: String,
    val accountActive: Boolean,
    val categoryName: String?,
    val categoryActive: Boolean?,
)

/**
 * One recycle-bin line. [restoreRejectionCode] is `null` exactly when the restore would be
 * admissible right now; otherwise it carries the frozen rejection the restore would produce
 * (a deactivated/inadmissible reference, or an effective linked refund).
 */
data class RecycleBinRow(
    val voided: VoidedTransactionRow,
    val creationEntry: CreationEntry,
    val dependencies: List<RecycleBinDependencyLeg>,
    val hasEffectiveRefundLink: Boolean,
    val restoreRejectionCode: P705FailureCode?,
) {
    val restoreAdmissible: Boolean get() = restoreRejectionCode == null
}

sealed interface RecycleBinResult {
    data class Success(
        val rows: List<RecycleBinRow>,
    ) : RecycleBinResult

    /** Catalog/posting inconsistency of a voided row (defensive; same rule as the detail read). */
    data object InvalidState : RecycleBinResult

    /** Read-port exception (database unavailable). */
    data object Unavailable : RecycleBinResult
}

/**
 * Read-only recycle-bin projection. The display order is the query's frozen
 * `(void time DESC, transaction_id ASC)` total order; the use case never re-sorts, so the
 * ordering has exactly one definition. Voided history (versions, creation entry, source
 * relations) stays readable: only the effective surfaces drop the transaction.
 *
 * Slice-1a known cost, registered rather than hidden: [toRecycleBinRow] issues its dependency
 * reads (refund linkage, import creation, manual creation) once per row, so a large recycle bin
 * costs O(rows) round-trips. Deliberate for the first slice (rows are few and each read is a
 * primary-key lookup); if the bin ever grows, slice 1b should batch those three probes per
 * ledger into the voided-row query instead of restructuring this projection.
 */
class QueryRecycleBin(
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

    fun query(): RecycleBinResult {
        val rows =
            try {
                readPort.loadVoidedTransactionRows(ledgerId)
            } catch (failure: Exception) {
                return RecycleBinResult.Unavailable
            }
        return try {
            RecycleBinResult.Success(rows.map(::toRecycleBinRow))
        } catch (failure: Exception) {
            RecycleBinResult.Unavailable
        }
    }

    private fun toRecycleBinRow(row: VoidedTransactionRow): RecycleBinRow {
        val dependencies =
            row.postings.map { posting ->
                val account = accountsById[posting.accountId]
                val category = categoryByPostingAccount[posting.accountId]
                RecycleBinDependencyLeg(
                    postingId = posting.id,
                    accountId = posting.accountId,
                    accountName = account?.name ?: posting.accountId.value,
                    accountActive = account?.active ?: false,
                    categoryName = category?.name,
                    categoryActive = category?.active,
                )
            }
        val refundLinked = readPort.hasEffectiveRefundLink(ledgerId, row.transactionId)
        val revalidation =
            revalidateOrdinaryVersionReferences(
                catalog = catalog,
                ledgerId = ledgerId,
                kind = row.kind,
                postings = row.postings,
            )
        val rejection =
            when {
                refundLinked -> P705FailureCode.P705_REFUND_LINKED_VOID_NOT_SUPPORTED
                else -> revalidation
            }
        return RecycleBinRow(
            voided = row,
            creationEntry =
                when {
                    readPort.findImportCreationConfirmation(ledgerId, row.transactionId) != null -> CreationEntry.IMPORT_CREATED
                    readPort.findManualCreationReceipt(ledgerId, row.transactionId) != null -> CreationEntry.MANUAL_CREATED
                    else -> CreationEntry.UNMARKED
                },
            dependencies = dependencies,
            hasEffectiveRefundLink = refundLinked,
            restoreRejectionCode = rejection,
        )
    }
}
