package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId
import kotlin.time.Instant

/**
 * P8-03 refund-receipt entry (D-312; spec sections 2.5/2.6/4). The frozen rejection closed set:
 * every refusal of the manual refund submission is one of these eight codes, and none of them
 * carries a free-form message. [REFUND_EXCEEDS_STRUCTURAL_CAP] and [REFUND_EXCEEDS_REMAINING]
 * are distinguished by one server-decidable threshold only (the expense-leg amount), so neither
 * the request nor its fingerprint needs a cap snapshot.
 */
enum class ManualRefundRejectionCode {
    REFUND_CATEGORY_INELIGIBLE,
    REFUND_ACCOUNT_INELIGIBLE,
    ORIGINAL_NOT_REFUNDABLE,
    ORIGINAL_NO_LONGER_REFUNDABLE,
    REFUND_EXCEEDS_STRUCTURAL_CAP,
    REFUND_EXCEEDS_REMAINING,
    REFUND_REQUEST_CONFLICT,
    REFUND_STORE_UNAVAILABLE,
}

/** Fresh stable ids of one committed manual refund relationship and its formal transaction. */
data class ManualRefundReceiptIds(
    val relationshipId: String,
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val destinationPostingId: PostingId,
    val expensePostingId: PostingId,
)

fun interface ManualRefundReceiptIdSource {
    fun next(): ManualRefundReceiptIds
}

/**
 * One explicit manual refund arrival. [arrivedAt] is the user's arrival instant (the refund
 * transaction's occurred/statistics/effective instant); [confirmedAt] is the confirmation audit
 * instant sampled from the injected clock. No category is carried: the server re-resolves the
 * inherited secondary expense category from the original transaction (spec section 2.4).
 */
data class ManualRefundReceiptRequest(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val originalTransactionId: TransactionId,
    val amount: Money,
    val destinationAccountId: AccountId,
    val arrivedAt: Instant,
    val confirmedAt: Instant,
)

/** The authoritative read-back shape of one committed relationship row. */
data class ManualRefundReceiptRecord(
    val relationshipId: String,
    val refundTransactionId: TransactionId,
)

sealed interface ManualRefundReceiptCommitResult {
    data class Created(
        val receipt: ManualRefundReceiptRecord,
    ) : ManualRefundReceiptCommitResult

    data class Replayed(
        val receipt: ManualRefundReceiptRecord,
    ) : ManualRefundReceiptCommitResult

    data class Rejected(
        val code: ManualRefundRejectionCode,
    ) : ManualRefundReceiptCommitResult

    /**
     * The attempt failed and its relationship row does not answer it, so no refund is known to be
     * committed. A row may still exist when the replay read failed too: this is not a proof of
     * rollback.
     */
    data object Unavailable : ManualRefundReceiptCommitResult
}

/**
 * Atomic commit boundary of one explicit manual refund.
 *
 * Implementations MUST, inside one write transaction and in this order:
 * 1. resolve the request id: same id + same fingerprint replays the stored row, same id +
 *    different fingerprint rejects with `REFUND_REQUEST_CONFLICT` and writes nothing;
 * 2. re-check that the original transaction is still effective and still satisfies the
 *    candidate conditions, rejecting `ORIGINAL_NOT_REFUNDABLE` / `ORIGINAL_NO_LONGER_REFUNDABLE`;
 * 3. recompute the structural cap and the remaining capacity from the effective refund rows;
 * 4. build the refund receipt through the frozen domain factory and persist its formal
 *    transaction;
 * 5. insert the relationship row (arrival/confirmation history, fingerprint, marker).
 *
 * [mintIds] is invoked at most once and only for a fresh request, after the replay check, so a
 * replay, a conflict and a losing concurrent writer consume no ids. A storage failure never
 * escapes as an untyped exception: the implementation re-reads the request's relationship row
 * (the write may have committed before the failure surfaced), so an exact fingerprint returns
 * [ManualRefundReceiptCommitResult.Replayed] and anything else returns
 * [ManualRefundReceiptCommitResult.Unavailable].
 */
fun interface ManualRefundReceiptCommitPort {
    fun commitOnce(
        request: ManualRefundReceiptRequest,
        requestFingerprint: String,
        mintIds: () -> ManualRefundReceiptIds,
    ): ManualRefundReceiptCommitResult
}

/** One persisted relationship row, read by the unknown-commit resolution path. */
data class ManualRefundReceiptReadRecord(
    val requestFingerprint: String,
    val receipt: ManualRefundReceiptRecord,
)

interface ManualRefundReceiptReadPort {
    fun findByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualRefundReceiptReadRecord?
}

/**
 * P8-03 unknown-commit resolution (spec section 2.6). Only an exact fingerprint match may
 * recover a success; a mismatch is the stable request conflict; absent and unavailable both
 * remain unknown (absence cannot prove rollback).
 */
sealed interface ManualRefundReceiptCommitResolution {
    data class MatchingReceipt(
        val receipt: ManualRefundReceiptRecord,
    ) : ManualRefundReceiptCommitResolution

    data object SnapshotConflict : ManualRefundReceiptCommitResolution

    data object Absent : ManualRefundReceiptCommitResolution

    data object Unavailable : ManualRefundReceiptCommitResolution
}

class ResolveManualRefundReceiptCommitStatus(
    private val readPort: ManualRefundReceiptReadPort,
) {
    fun resolve(
        ledgerId: LedgerId,
        requestId: RequestId,
        attemptedFingerprint: String,
    ): ManualRefundReceiptCommitResolution {
        val record =
            try {
                readPort.findByRequest(ledgerId, requestId)
            } catch (failure: Exception) {
                return ManualRefundReceiptCommitResolution.Unavailable
            }
        return when {
            record == null -> ManualRefundReceiptCommitResolution.Absent
            record.requestFingerprint == attemptedFingerprint ->
                ManualRefundReceiptCommitResolution.MatchingReceipt(record.receipt)
            else -> ManualRefundReceiptCommitResolution.SnapshotConflict
        }
    }
}

/**
 * The frozen per-type submission union of the manual refund entry. It mirrors the shape of the
 * other manual entry result families; the shared `ManualEntrySubmissionResult` union gains its
 * branch in the entry-UI batch, when the four compile-forced `app-ui` sites are updated in the
 * same change.
 */
sealed interface ManualRefundReceiptSubmissionResult {
    data class Created(
        val receipt: ManualRefundReceiptRecord,
    ) : ManualRefundReceiptSubmissionResult

    data class Replayed(
        val receipt: ManualRefundReceiptRecord,
    ) : ManualRefundReceiptSubmissionResult

    data class Recovered(
        val receipt: ManualRefundReceiptRecord,
    ) : ManualRefundReceiptSubmissionResult

    data class Rejected(
        val code: ManualRefundRejectionCode,
    ) : ManualRefundReceiptSubmissionResult

    /**
     * The commit may have happened but the authoritative read-back failed (or found nothing):
     * the write is never retried and the caller must reuse the same request id on a user retry.
     */
    data object UnknownCommit : ManualRefundReceiptSubmissionResult
}

/**
 * P8-03 submit use case (spec sections 2.6/2.7). The fingerprint is computed here, once, so the
 * store and the resolution path compare the same bytes. A store failure before any commit is the
 * typed `REFUND_STORE_UNAVAILABLE`; an exception thrown after the commit boundary is resolved by
 * the authoritative read-back, and only a matching receipt may recover a success.
 */
class SubmitManualRefundReceipt(
    private val commitPort: ManualRefundReceiptCommitPort,
    private val idSource: ManualRefundReceiptIdSource,
    private val resolver: ResolveManualRefundReceiptCommitStatus,
) {
    fun submit(request: ManualRefundReceiptRequest): ManualRefundReceiptSubmissionResult {
        val fingerprint = manualRefundRequestFingerprint(request)
        return try {
            when (val outcome = commitPort.commitOnce(request, fingerprint) { idSource.next() }) {
                is ManualRefundReceiptCommitResult.Created ->
                    ManualRefundReceiptSubmissionResult.Created(outcome.receipt)
                is ManualRefundReceiptCommitResult.Replayed ->
                    ManualRefundReceiptSubmissionResult.Replayed(outcome.receipt)
                is ManualRefundReceiptCommitResult.Rejected ->
                    ManualRefundReceiptSubmissionResult.Rejected(outcome.code)
                ManualRefundReceiptCommitResult.Unavailable ->
                    ManualRefundReceiptSubmissionResult.Rejected(ManualRefundRejectionCode.REFUND_STORE_UNAVAILABLE)
            }
        } catch (failure: Exception) {
            when (
                val resolution =
                    resolver.resolve(request.ledgerId, request.requestId, fingerprint)
            ) {
                is ManualRefundReceiptCommitResolution.MatchingReceipt ->
                    ManualRefundReceiptSubmissionResult.Recovered(resolution.receipt)
                ManualRefundReceiptCommitResolution.SnapshotConflict ->
                    ManualRefundReceiptSubmissionResult.Rejected(ManualRefundRejectionCode.REFUND_REQUEST_CONFLICT)
                ManualRefundReceiptCommitResolution.Absent,
                ManualRefundReceiptCommitResolution.Unavailable,
                -> ManualRefundReceiptSubmissionResult.UnknownCommit
            }
        }
    }
}

/**
 * Spec section 2.6's frozen fingerprint input: original transaction | amount | currency |
 * destination account | arrival instant. Ledger and request id are the row's identity, not the
 * payload, so a same-id request with a different payload always changes the digest.
 */
fun manualRefundRequestFingerprint(request: ManualRefundReceiptRequest): String {
    val material =
        buildString {
            append("p8-03-manual-refund-v1")
            append("|original=").append(request.originalTransactionId.value)
            append("|amount_minor=").append(request.amount.minorUnits)
            append("|currency_code=").append(request.amount.currency.code)
            append("|currency_precision=").append(request.amount.currency.precision)
            append("|destination=").append(request.destinationAccountId.value)
            append("|arrived_at=").append(request.arrivedAt.toString())
        }
    return "sha256:" + Sha256.digestHex(material.encodeToByteArray())
}
