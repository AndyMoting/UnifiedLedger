package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.AnnotationFailureCode
import com.unifiedledger.application.AnnotationReceiptOutcome
import com.unifiedledger.application.TransactionAnnotationAuthority
import com.unifiedledger.application.TransactionAnnotationCommitPort
import com.unifiedledger.application.TransactionAnnotationReceipt
import com.unifiedledger.application.TransactionAnnotationRequest
import com.unifiedledger.application.TransactionAnnotationResult
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.CatalogItem
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionAnnotation
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId

/**
 * P7-08 08.A (D-187; spec sections 3.1/3.2) transaction annotation store.
 *
 * Claim-first atomic annotation boundary: the `(ledgerId, requestId)` claim, the new immutable
 * annotation revision, its tag association rows, the insert-or-advance of the current pointer and
 * the receipt are written in ONE transaction. An equivalent snapshot replay returns the original
 * receipt with zero writes; a same-id different-snapshot request returns `RequestIdentityConflict`;
 * a stale `expectedAnnotationRevision` (sentinel 0 == no current annotation) returns
 * `AnnotationRevisionConflict`; a stale `expectedCurrentVersionId` returns
 * `AnnotationCurrentVersionConflict`; a voided transaction is `Rejected` by the conservative gate.
 * Every typed failure throws out of the transaction, so the claim is rolled back too and the
 * identity stays retryable.
 *
 * Spec section 8 open item 4 ruling (claim-table annotation encoding): the 08.B extension of the
 * existing `manual_*_request` tables uses encoding (a) — dedicated NULLABLE columns — and its
 * replay matcher is a STRUCTURED per-column comparison whose missing-field default is the frozen
 * "no annotation" (empty tag set / null merchant). This 08.A owner is a brand-new table with no
 * legacy rows, so its canonical `request_snapshot` (a deterministic, sorted, JCS-escaped encoding
 * of exactly transactionId + tag set + merchantId) is provably identical to a structured
 * per-column comparison; the same snapshot excluded the CAS fields so a stale retry of the SAME
 * intent replays the original receipt.
 */
class SqlDelightTransactionAnnotationStore private constructor(
    private val database: LedgerDatabase,
) : TransactionAnnotationCommitPort {
    constructor(database: LedgerDatabase, driver: SqlDriver) : this(database) {
        configureSqliteConnection(driver)
    }

    private val catalogReader: SqlDelightTagMerchantCatalogStore =
        SqlDelightTagMerchantCatalogStore.forPlatformConfiguredDatabase(database)

    override fun commitOnce(
        request: TransactionAnnotationRequest,
        apply: (TransactionAnnotationAuthority) -> DomainResult<TransactionAnnotation>,
    ): TransactionAnnotationResult {
        val ledger = request.ledgerId.value
        val transactionId = request.transactionId.value
        return try {
            database.transactionWithResult {
                database.ledgerQueries.claimTransactionAnnotationCommandRequest(
                    ledger_id = ledger,
                    request_id = request.requestId.value,
                    transaction_id = transactionId,
                    request_snapshot = request.requestSnapshot,
                    input_fingerprint = request.inputFingerprint,
                    expected_annotation_revision = request.expectedAnnotationRevision,
                    expected_current_version_id = request.expectedCurrentVersionId.value,
                    outcome = "ACCEPTED",
                )
                if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
                    return@transactionWithResult resolveExisting(request)
                }

                val currentVersionId =
                    database.ledgerQueries
                        .selectCurrentVersionIdForTransaction(ledger, transactionId)
                        .executeAsOneOrNull()
                        ?: abortAnnotation(AnnotationFailureCode.ANNOTATION_CONSTRAINT_VIOLATION)
                val effective =
                    database.ledgerQueries
                        .transactionEffectiveState(ledger, transactionId)
                        .executeAsOneOrNull()
                        ?: abortAnnotation(AnnotationFailureCode.ANNOTATION_CONSTRAINT_VIOLATION)
                val pointer =
                    database.ledgerQueries
                        .selectTransactionAnnotationCurrentRevision(ledger, transactionId)
                        .executeAsOneOrNull()

                if (currentVersionId != request.expectedCurrentVersionId.value) {
                    abortAnnotation(AnnotationFailureCode.ANNOTATION_CURRENT_VERSION_CONFLICT, conflict = true)
                }
                val currentRevision = pointer ?: 0L
                if (currentRevision != request.expectedAnnotationRevision) {
                    abortAnnotation(AnnotationFailureCode.ANNOTATION_REVISION_CONFLICT, conflict = true)
                }
                if (effective != 1L) {
                    abortAnnotation(AnnotationFailureCode.ANNOTATION_VOIDED_TRANSACTION)
                }

                val (tags, merchants) = loadCatalog(request.ledgerId)
                val authority =
                    TransactionAnnotationAuthority(
                        ledgerId = request.ledgerId,
                        transactionId = request.transactionId,
                        currentAnnotationRevision = currentRevision,
                        currentVersionId = TransactionVersionId(currentVersionId),
                        effective = true,
                        tags = tags,
                        merchants = merchants,
                    )
                val annotation =
                    when (val result = apply(authority)) {
                        is DomainResult.Failure -> abortAnnotation(AnnotationFailureCode.of(result.violation))
                        is DomainResult.Success -> result.value
                    }

                val newRevision = currentRevision + 1L
                database.ledgerQueries.insertTransactionAnnotationRevision(
                    ledger_id = ledger,
                    transaction_id = transactionId,
                    annotation_revision = newRevision,
                    observed_transaction_version_id = currentVersionId,
                    merchant_id = annotation.merchantId?.value,
                    request_id = request.requestId.value,
                    created_at = request.createdAt.toString(),
                )
                annotation.tagIds.forEach { tagId ->
                    database.ledgerQueries.insertTransactionAnnotationTag(ledger, transactionId, newRevision, tagId.value)
                }
                if (pointer == null) {
                    database.ledgerQueries.insertTransactionAnnotationCurrent(ledger, transactionId, newRevision)
                } else {
                    database.ledgerQueries.advanceTransactionAnnotationCurrent(newRevision, ledger, transactionId, currentRevision)
                }

                database.ledgerQueries.updateTransactionAnnotationCommandRequestOutcome(
                    outcome = "ACCEPTED",
                    result_annotation_revision = newRevision,
                    ledger_id = ledger,
                    request_id = request.requestId.value,
                )
                val receipt =
                    TransactionAnnotationReceipt(
                        requestId = request.requestId,
                        outcome = AnnotationReceiptOutcome.ACCEPTED,
                        transactionId = request.transactionId,
                        newAnnotationRevision = newRevision,
                    )
                database.ledgerQueries.insertTransactionAnnotationCommandReceipt(
                    ledger_id = ledger,
                    request_id = receipt.requestId.value,
                    outcome = "ACCEPTED",
                    transaction_id = receipt.transactionId.value,
                    new_annotation_revision = receipt.newAnnotationRevision,
                )
                TransactionAnnotationResult.Accepted(receipt)
            }
        } catch (rejected: AnnotationTypedRollback) {
            rejected.result
        } catch (failure: Exception) {
            // Trigger/unique/FK fallback: the whole command transaction already rolled back, so the
            // identity stays retryable and no terminal row was written.
            TransactionAnnotationResult.Rejected(AnnotationFailureCode.ANNOTATION_CONSTRAINT_VIOLATION)
        }
    }

    private fun resolveExisting(request: TransactionAnnotationRequest): TransactionAnnotationResult {
        val ledger = request.ledgerId.value
        val requestId = request.requestId.value
        val stored =
            database.ledgerQueries
                .selectTransactionAnnotationCommandRequest(ledger, requestId) { transactionId, snapshot, _, _, _, _, _ ->
                    StoredAnnotationRequest(transactionId, snapshot)
                }.executeAsOneOrNull() ?: abortAnnotation(AnnotationFailureCode.ANNOTATION_CONSTRAINT_VIOLATION)
        if (!stored.matches(request)) {
            return TransactionAnnotationResult.Conflict(AnnotationFailureCode.REQUEST_IDENTITY_CONFLICT)
        }
        val receipt =
            database.ledgerQueries
                .selectTransactionAnnotationCommandReceipt(ledger, requestId) { outcome, transactionId, newRevision ->
                    TransactionAnnotationReceipt(
                        requestId = request.requestId,
                        outcome = if (outcome == "ACCEPTED") AnnotationReceiptOutcome.ACCEPTED else AnnotationReceiptOutcome.NO_CHANGE,
                        transactionId = TransactionId(transactionId),
                        newAnnotationRevision = newRevision,
                    )
                }.executeAsOneOrNull() ?: abortAnnotation(AnnotationFailureCode.ANNOTATION_CONSTRAINT_VIOLATION)
        return TransactionAnnotationResult.NoChange(receipt)
    }

    private fun loadCatalog(ledgerId: LedgerId): Pair<List<CatalogItem>, List<CatalogItem>> {
        val authority = catalogReader.load(ledgerId) ?: return emptyList<CatalogItem>() to emptyList()
        return authority.tags to authority.merchants
    }

    companion object {
        /**
         * Android/foreign-key-configured handle variant (mirrors the other product stores): the
         * platform driver already configured foreign keys and its busy timeout.
         */
        internal fun forPlatformConfiguredDatabase(
            database: LedgerDatabase,
        ): SqlDelightTransactionAnnotationStore = SqlDelightTransactionAnnotationStore(database)
    }
}

/**
 * The persisted annotation-request projection used for the structured replay match. The canonical
 * snapshot deterministically encodes exactly these fields, so an equal snapshot plus equal
 * transaction id is the same equivalence as the per-column comparison the 08.B owner must use.
 */
private data class StoredAnnotationRequest(
    val transactionId: String,
    val requestSnapshot: String,
) {
    fun matches(request: TransactionAnnotationRequest): Boolean =
        transactionId == request.transactionId.value && requestSnapshot == request.requestSnapshot
}

private class AnnotationTypedRollback(
    val result: TransactionAnnotationResult,
) : RuntimeException()

private fun abortAnnotation(
    code: AnnotationFailureCode,
    conflict: Boolean = false,
): Nothing =
    throw AnnotationTypedRollback(
        if (conflict) {
            TransactionAnnotationResult.Conflict(code)
        } else {
            TransactionAnnotationResult.Rejected(code)
        },
    )
