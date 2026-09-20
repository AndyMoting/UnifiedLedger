package com.unifiedledger.application

import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.P705FailureCode
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.TransactionVoidFactKind
import com.unifiedledger.domain.TransactionVoidState
import com.unifiedledger.domain.VoidReason
import com.unifiedledger.domain.voidReasonRejection
import kotlin.time.Instant

/**
 * P7-05.C logical void and restore (spec section 3.3; D-156).
 *
 * A void appends one immutable fact that removes the transaction from every effective derived
 * surface; a restore appends a second fact that makes it effective again. Nothing is deleted,
 * rewritten or compensated, and no reconciliation/evidence/lending/import owner is written.
 * The first slice allows at most one void plus one restore per transaction (DP-8).
 */
data class TransactionVoidRequestIdentity(
    val ledgerId: LedgerId,
    val requestId: RequestId,
)

/** The frozen void/restore snapshot column set; `confirmation_marker` is the constant. */
data class TransactionVoidRequestSnapshot(
    val ledgerId: LedgerId,
    val transactionId: TransactionId,
    val factKind: TransactionVoidFactKind,
    val reason: VoidReason,
)

data class TransactionVoidReceipt(
    val confirmationId: ConfirmationId,
    val transactionId: TransactionId,
    val factId: String,
    val factKind: TransactionVoidFactKind,
)

/** Fresh ids of one void/restore fact. */
data class TransactionVoidFactIds(
    val confirmationId: ConfirmationId,
    val factId: String,
)

fun interface TransactionVoidFactIdSource {
    fun next(): TransactionVoidFactIds
}

/**
 * The persisted facts of the void/restore target, read inside the write transaction: the
 * transaction kind, its current version postings (restore revalidation material) and the
 * void/restore fact sequence.
 */
data class TransactionVoidTarget(
    val transactionId: TransactionId,
    val kind: TransactionKind,
    val currentVersionId: TransactionVersionId,
    val postings: List<Posting>,
    val voidState: TransactionVoidState,
)

/**
 * The plan callback's outcome. [Commit] carries the audit timestamp read from the clock inside
 * the write transaction (a generated column, excluded from replay comparison); [Rejected]
 * carries the frozen failure code and writes nothing.
 */
sealed interface TransactionVoidPlan {
    data class Commit(
        val receipt: TransactionVoidReceipt,
        val createdAt: Instant,
    ) : TransactionVoidPlan

    data class Rejected(
        val code: P705FailureCode,
    ) : TransactionVoidPlan
}

/** Shared result family of the merged void/restore family (spec section 4.3). */
sealed interface VoidTransactionResult {
    data class Created(
        val receipt: TransactionVoidReceipt,
    ) : VoidTransactionResult

    data class NoChange(
        val receipt: TransactionVoidReceipt,
    ) : VoidTransactionResult

    data class RequestIdentityConflict(
        val identity: TransactionVoidRequestIdentity,
    ) : VoidTransactionResult

    data class Rejected(
        val code: P705FailureCode,
    ) : VoidTransactionResult
}

/**
 * Claim-first commit boundary of the merged void/restore family. As in the correction port, a
 * lost claim is resolved by comparing the stored snapshot before any other evaluation, so a
 * replay can never be misreported as a fresh rejection.
 */
fun interface TransactionVoidCommitPort {
    fun commitOnce(
        identity: TransactionVoidRequestIdentity,
        requestSnapshot: TransactionVoidRequestSnapshot,
        plan: (TransactionVoidTarget) -> TransactionVoidPlan,
    ): VoidTransactionResult
}

/** One void or restore request; `reason` is nullable so a missing reason is representable. */
data class VoidTransactionRequest(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val transactionId: TransactionId,
    val reason: VoidReason?,
    val confirmation: ExplicitManualSave,
)

/**
 * The void use case. The reason is mandatory (DP-11) and its absence or over-long note is a
 * typed rejection with zero writes; the fact timestamp is the audit `created_at` read from the
 * injected [clock] inside the write transaction.
 */
class ExecuteVoidTransaction(
    private val commitPort: TransactionVoidCommitPort,
    private val idSource: TransactionVoidFactIdSource,
    private val clock: LedgerClock,
) {
    fun execute(request: VoidTransactionRequest): VoidTransactionResult {
        val rejection = voidReasonRejection(request.reason)
        if (rejection != null) return VoidTransactionResult.Rejected(rejection)
        val reason = checkNotNull(request.reason)
        val identity = TransactionVoidRequestIdentity(request.ledgerId, request.requestId)
        val snapshot =
            TransactionVoidRequestSnapshot(
                ledgerId = request.ledgerId,
                transactionId = request.transactionId,
                factKind = TransactionVoidFactKind.VOID,
                reason = reason,
            )
        return commitPort.commitOnce(identity, snapshot) { target ->
            val ids = idSource.next()
            TransactionVoidPlan.Commit(
                receipt =
                    TransactionVoidReceipt(
                        confirmationId = ids.confirmationId,
                        transactionId = target.transactionId,
                        factId = ids.factId,
                        factKind = TransactionVoidFactKind.VOID,
                    ),
                createdAt = clock.now(),
            )
        }
    }
}

/**
 * The restore use case. The restore revalidates the current version's references against the
 * current authoritative catalog (DP-9: a deactivated/inadmissible reference is a typed
 * rejection and the transaction stays voided; a renamed but active reference stays
 * admissible). The reason is mandatory for a restore as well (DP-11).
 */
class ExecuteRestoreTransaction(
    private val commitPort: TransactionVoidCommitPort,
    private val idSource: TransactionVoidFactIdSource,
    private val clock: LedgerClock,
    private val admissionReader: CatalogAdmissionReader,
) {
    fun execute(request: VoidTransactionRequest): VoidTransactionResult {
        val rejection = voidReasonRejection(request.reason)
        if (rejection != null) return VoidTransactionResult.Rejected(rejection)
        val reason = checkNotNull(request.reason)
        val identity = TransactionVoidRequestIdentity(request.ledgerId, request.requestId)
        val snapshot =
            TransactionVoidRequestSnapshot(
                ledgerId = request.ledgerId,
                transactionId = request.transactionId,
                factKind = TransactionVoidFactKind.RESTORE,
                reason = reason,
            )
        return commitPort.commitOnce(identity, snapshot) { target ->
            val ids = idSource.next()
            val catalog =
                admissionReader.loadCurrent(request.ledgerId)
                    ?: return@commitOnce TransactionVoidPlan.Rejected(
                        P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE,
                    )
            val revalidation =
                revalidateOrdinaryVersionReferences(
                    catalog = catalog,
                    ledgerId = request.ledgerId,
                    kind = target.kind,
                    postings = target.postings,
                )
            if (revalidation != null) return@commitOnce TransactionVoidPlan.Rejected(revalidation)
            TransactionVoidPlan.Commit(
                receipt =
                    TransactionVoidReceipt(
                        confirmationId = ids.confirmationId,
                        transactionId = target.transactionId,
                        factId = ids.factId,
                        factKind = TransactionVoidFactKind.RESTORE,
                    ),
                createdAt = clock.now(),
            )
        }
    }
}
