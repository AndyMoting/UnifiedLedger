package com.unifiedledger.application

import com.unifiedledger.domain.DomainResult

/**
 * P7-02.A S-1 income submission orchestration and unknown-commit classification, mirroring
 * [ExecuteManualExpenseSubmission] and the frozen D-119 exception recovery order:
 *
 * 1. every [submit] resets the income [CommitOnceInvocationTracker] so a previous submission's
 *    handoff marker cannot classify a current pre-handoff failure as unknown;
 * 2. once the commit handoff happened, any later exception enters the unknown-commit resolution
 *    path;
 * 3. the income resolver compares the full snapshot (including note);
 * 4. MatchingReceipt recovers success; SnapshotConflict maps to a stable request identity
 *    conflict and never recovers success;
 * 5. Absent and Unavailable remain unknown: no auto retry, no optimistic refresh, no requestId
 *    replacement.
 */
sealed interface ManualIncomeSubmissionResult {
    data class Application(
        val result: ManualIncomeSaveResult,
    ) : ManualIncomeSubmissionResult

    data class Recovered(
        val receipt: ConfirmedIncomeReceipt,
    ) : ManualIncomeSubmissionResult

    data object InfrastructureFailure : ManualIncomeSubmissionResult

    data object UnknownCommit : ManualIncomeSubmissionResult
}

/**
 * Income analogue of [CommitOnceInvocationTracker]: composition roots wrap the real income
 * commit port and share the same instance with [ExecuteConfirmedManualIncome] and
 * [ExecuteManualIncomeSubmission].
 */
class CommitOnceInvocationTrackerIncome(
    private val delegate: ConfirmedManualIncomeCommitPort,
) : ConfirmedManualIncomeCommitPort {
    var commitOnceInvoked: Boolean = false
        private set

    fun reset() {
        commitOnceInvoked = false
    }

    override fun commitOnce(
        identity: ManualIncomeRequestIdentity,
        requestSnapshot: ManualIncomeRequestSnapshot,
        createFormalTransaction: () -> DomainResult<ConfirmedManualIncomeCommit>,
    ): ConfirmedManualIncomeResult {
        commitOnceInvoked = true
        return delegate.commitOnce(identity, requestSnapshot, createFormalTransaction)
    }
}

class ExecuteManualIncomeSubmission(
    private val executeSave: ExecuteManualIncomeSave,
    private val tracker: CommitOnceInvocationTrackerIncome,
    private val resolver: ResolveManualIncomeCommitStatus,
) {
    fun submit(input: ManualIncomeSaveInput): ManualIncomeSubmissionResult {
        tracker.reset()
        return try {
            ManualIncomeSubmissionResult.Application(executeSave.execute(input))
        } catch (failure: Exception) {
            if (!tracker.commitOnceInvoked) {
                ManualIncomeSubmissionResult.InfrastructureFailure
            } else {
                resolveAfterHandoff(input)
            }
        }
    }

    private fun resolveAfterHandoff(input: ManualIncomeSaveInput): ManualIncomeSubmissionResult {
        val attempted = attemptedSnapshot(input) ?: return ManualIncomeSubmissionResult.UnknownCommit
        return when (val resolution = resolver.resolve(input.ledgerId, input.requestId, attempted)) {
            is ManualIncomeCommitResolution.MatchingReceipt ->
                ManualIncomeSubmissionResult.Recovered(resolution.receipt)

            ManualIncomeCommitResolution.SnapshotConflict ->
                ManualIncomeSubmissionResult.Application(
                    ManualIncomeSaveResult.Executed(
                        ConfirmedManualIncomeResult.RequestIdentityConflict(
                            ManualIncomeRequestIdentity(input.ledgerId, input.requestId),
                        ),
                    ),
                )

            ManualIncomeCommitResolution.Absent,
            ManualIncomeCommitResolution.Unavailable,
            -> ManualIncomeSubmissionResult.UnknownCommit
        }
    }

    private fun attemptedSnapshot(input: ManualIncomeSaveInput): ManualIncomeRequestSnapshot? {
        val amount = input.amount ?: return null
        val categoryId = input.categoryId ?: return null
        val receivingAccountId = input.receivingAccountId ?: return null
        return ManualIncomeRequestSnapshot(
            ledgerId = input.ledgerId,
            amount = amount,
            categoryId = categoryId,
            receivingAccountId = receivingAccountId,
            occurredAt = input.occurredAt,
            note = input.note,
        )
    }
}
