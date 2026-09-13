package com.unifiedledger.application

import com.unifiedledger.domain.DomainResult

/**
 * P7-02.B submission orchestration and unknown-commit classification, mirroring
 * [ExecuteManualExpenseSubmission]: pre-handoff exceptions are an infrastructure failure,
 * post-handoff exceptions resolve against the transfer read model, and absence/unavailability
 * stay unknown (no auto retry, no requestId replacement).
 */
sealed interface ManualTransferSubmissionResult {
    data class Application(
        val result: ManualTransferSaveResult,
    ) : ManualTransferSubmissionResult

    data class Recovered(
        val receipt: ConfirmedTransferReceipt,
    ) : ManualTransferSubmissionResult

    data object InfrastructureFailure : ManualTransferSubmissionResult

    data object UnknownCommit : ManualTransferSubmissionResult
}

class CommitOnceInvocationTrackerTransfer(
    private val delegate: ConfirmedManualTransferCommitPort,
) : ConfirmedManualTransferCommitPort {
    var commitOnceInvoked: Boolean = false
        private set

    fun reset() {
        commitOnceInvoked = false
    }

    override fun commitOnce(
        identity: ManualTransferRequestIdentity,
        requestSnapshot: ManualTransferRequestSnapshot,
        createFormalTransaction: () -> DomainResult<ConfirmedManualTransferCommit>,
    ): ConfirmedManualTransferResult {
        commitOnceInvoked = true
        return delegate.commitOnce(identity, requestSnapshot, createFormalTransaction)
    }
}

class ExecuteManualTransferSubmission(
    private val executeSave: ExecuteManualTransferSave,
    private val tracker: CommitOnceInvocationTrackerTransfer,
    private val resolver: ResolveManualTransferCommitStatus,
) {
    fun submit(input: ManualTransferSaveInput): ManualTransferSubmissionResult {
        tracker.reset()
        return try {
            ManualTransferSubmissionResult.Application(executeSave.execute(input))
        } catch (failure: Exception) {
            if (!tracker.commitOnceInvoked) {
                ManualTransferSubmissionResult.InfrastructureFailure
            } else {
                resolveAfterHandoff(input)
            }
        }
    }

    private fun resolveAfterHandoff(input: ManualTransferSaveInput): ManualTransferSubmissionResult {
        val attempted = attemptedSnapshot(input) ?: return ManualTransferSubmissionResult.UnknownCommit
        return when (val resolution = resolver.resolve(input.ledgerId, input.requestId, attempted)) {
            is ManualTransferCommitResolution.MatchingReceipt ->
                ManualTransferSubmissionResult.Recovered(resolution.receipt)

            ManualTransferCommitResolution.SnapshotConflict ->
                ManualTransferSubmissionResult.Application(
                    ManualTransferSaveResult.Executed(
                        ConfirmedManualTransferResult.RequestIdentityConflict(
                            ManualTransferRequestIdentity(input.ledgerId, input.requestId),
                        ),
                    ),
                )

            ManualTransferCommitResolution.Absent,
            ManualTransferCommitResolution.Unavailable,
            -> ManualTransferSubmissionResult.UnknownCommit
        }
    }

    private fun attemptedSnapshot(input: ManualTransferSaveInput): ManualTransferRequestSnapshot? {
        val source = input.sourceAccountId ?: return null
        val destination = input.destinationAccountId ?: return null
        val destinationCredit = input.destinationCredit ?: return null
        val fee =
            input.fee ?: com.unifiedledger.domain.Money
                .ofMinor(0L, destinationCredit.currency)
        return ManualTransferRequestSnapshot(
            ledgerId = input.ledgerId,
            sourceAccountId = source,
            destinationAccountId = destination,
            destinationCredit = destinationCredit,
            fee = fee,
            feeCategoryId = input.feeCategoryId,
            occurredAt = input.occurredAt,
            note = input.note,
        )
    }
}
