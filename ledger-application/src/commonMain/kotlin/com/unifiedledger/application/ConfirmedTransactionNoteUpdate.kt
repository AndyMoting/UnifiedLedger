package com.unifiedledger.application

import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionNoteUpdateCommand
import com.unifiedledger.domain.TransactionNoteUpdateIds
import com.unifiedledger.domain.TransactionVersionId

data class ExplicitlyConfirmedTransactionNoteUpdate(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val transactionId: TransactionId,
    val note: String,
    val confirmation: ExplicitManualSave,
)

data class TransactionNoteUpdateRequestIdentity(
    val ledgerId: LedgerId,
    val requestId: RequestId,
)

data class TransactionNoteUpdateRequestSnapshot(
    val ledgerId: LedgerId,
    val transactionId: TransactionId,
    val command: TransactionNoteUpdateCommand,
)

data class ConfirmedTransactionNoteUpdateReceipt(
    val confirmationId: ConfirmationId,
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val expectedCurrentVersionId: TransactionVersionId,
)

data class ConfirmedTransactionNoteUpdateIds(
    val confirmationId: ConfirmationId,
    val replacement: TransactionNoteUpdateIds,
    val expectedCurrentVersionId: TransactionVersionId,
)

fun interface ConfirmedTransactionNoteUpdateIdSource {
    fun next(): ConfirmedTransactionNoteUpdateIds
}

sealed interface ConfirmedTransactionNoteUpdateResult {
    data class Created(
        val receipt: ConfirmedTransactionNoteUpdateReceipt,
    ) : ConfirmedTransactionNoteUpdateResult

    data class NoChange(
        val receipt: ConfirmedTransactionNoteUpdateReceipt,
    ) : ConfirmedTransactionNoteUpdateResult

    data class RequestIdentityConflict(
        val identity: TransactionNoteUpdateRequestIdentity,
    ) : ConfirmedTransactionNoteUpdateResult

    data object StaleCurrentVersion : ConfirmedTransactionNoteUpdateResult

    data class Rejected(
        val violation: DomainViolation,
    ) : ConfirmedTransactionNoteUpdateResult
}

fun interface ConfirmedTransactionNoteUpdateCommitPort {
    fun commitOnce(
        identity: TransactionNoteUpdateRequestIdentity,
        requestSnapshot: TransactionNoteUpdateRequestSnapshot,
        replaceNote: () -> ConfirmedTransactionNoteUpdateResult,
    ): ConfirmedTransactionNoteUpdateResult
}

class ExecuteConfirmedTransactionNoteUpdate(
    private val commitPort: ConfirmedTransactionNoteUpdateCommitPort,
    private val idSource: ConfirmedTransactionNoteUpdateIdSource,
) {
    fun execute(request: ExplicitlyConfirmedTransactionNoteUpdate): ConfirmedTransactionNoteUpdateResult {
        val identity = TransactionNoteUpdateRequestIdentity(request.ledgerId, request.requestId)
        val snapshot =
            TransactionNoteUpdateRequestSnapshot(
                request.ledgerId,
                request.transactionId,
                TransactionNoteUpdateCommand(request.note),
            )
        return commitPort.commitOnce(identity, snapshot) {
            val ids = idSource.next()
            ConfirmedTransactionNoteUpdateResult.Created(
                ConfirmedTransactionNoteUpdateReceipt(
                    ids.confirmationId,
                    request.transactionId,
                    ids.replacement.versionId,
                    ids.expectedCurrentVersionId,
                ),
            )
        }
    }
}

fun projectRg01TransactionNoteUpdateResult(
    result: ConfirmedTransactionNoteUpdateResult,
): Rg01OutcomeProjection =
    when (result) {
        is ConfirmedTransactionNoteUpdateResult.Created ->
            Rg01OutcomeProjection(
                Rg01OutcomeStatus.ACCEPTED,
                returnedIds =
                    setOf(
                        Rg01ReturnedId("transaction", result.receipt.transactionId.value),
                        Rg01ReturnedId("transaction_version", result.receipt.versionId.value),
                    ),
            )
        is ConfirmedTransactionNoteUpdateResult.NoChange ->
            Rg01OutcomeProjection(
                Rg01OutcomeStatus.NO_CHANGE,
                reasonCode = "idempotent_replay",
                returnedIds =
                    setOf(
                        Rg01ReturnedId("transaction", result.receipt.transactionId.value),
                        Rg01ReturnedId("transaction_version", result.receipt.versionId.value),
                    ),
            )
        is ConfirmedTransactionNoteUpdateResult.RequestIdentityConflict ->
            Rg01OutcomeProjection(
                Rg01OutcomeStatus.REJECTED,
                reasonCode = "request_identity_conflict",
                returnedIds = emptySet(),
            )
        ConfirmedTransactionNoteUpdateResult.StaleCurrentVersion ->
            Rg01OutcomeProjection(
                Rg01OutcomeStatus.REJECTED,
                reasonCode = "stale_current_version",
                returnedIds = emptySet(),
            )
        is ConfirmedTransactionNoteUpdateResult.Rejected ->
            Rg01OutcomeProjection(
                Rg01OutcomeStatus.REJECTED,
                reasonCode = "domain_rejected",
                returnedIds = emptySet(),
            )
    }
