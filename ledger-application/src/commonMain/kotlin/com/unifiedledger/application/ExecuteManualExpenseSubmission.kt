package com.unifiedledger.application

import com.unifiedledger.domain.DomainResult

/**
 * P5-03 submission orchestration and unknown-commit classification (D-119 section 3.5;
 * plan section 3.2.6).
 *
 * The exception recovery order is frozen:
 *
 * 1. Every [submit] starts by resetting the [CommitOnceInvocationTracker] so its handoff
 *    marker reflects only the current submission (a previous successful submission's marker
 *    must not classify a current pre-handoff failure as unknown).
 * 2. Once the commit handoff has happened, any later exception enters the unknown-commit
 *    resolution path.
 * 3. The resolver queries the authoritative state with ledgerId, requestId and the current
 *    attempted snapshot.
 * 4. MatchingReceipt recovers success; SnapshotConflict maps to a stable request identity
 *    conflict and never recovers success.
 * 5. Absent and Unavailable remain unknown: absence cannot prove rollback, so auto retry,
 *    optimistic refresh and requestId replacement are forbidden.
 *
 * Pre-handoff failures are only [ManualExpenseSubmissionResult.InfrastructureFailure] when
 * zero `commitOnce` calls are provable within the current submission.
 */
sealed interface ManualExpenseSubmissionResult {
    data class Application(
        val result: ManualExpenseSaveResult,
    ) : ManualExpenseSubmissionResult

    data class Recovered(
        val receipt: ConfirmedExpenseReceipt,
    ) : ManualExpenseSubmissionResult

    data object InfrastructureFailure : ManualExpenseSubmissionResult

    data object UnknownCommit : ManualExpenseSubmissionResult
}

/**
 * Composition-root constructed as `CommitOnceInvocationTracker(realPort)`; the same instance
 * is injected into both [ExecuteConfirmedManualExpense] and [ExecuteManualExpenseSubmission].
 */
class CommitOnceInvocationTracker(
    private val delegate: ConfirmedManualExpenseCommitPort,
) : ConfirmedManualExpenseCommitPort {
    var commitOnceInvoked: Boolean = false
        private set

    /** Called at every submit start so the marker only reflects the current submission. */
    fun reset() {
        commitOnceInvoked = false
    }

    override fun commitOnce(
        identity: ManualExpenseRequestIdentity,
        requestSnapshot: ManualExpenseRequestSnapshot,
        createFormalTransaction: () -> DomainResult<ConfirmedManualExpenseCommit>,
    ): ConfirmedManualExpenseResult {
        commitOnceInvoked = true
        return delegate.commitOnce(identity, requestSnapshot, createFormalTransaction)
    }
}

class ExecuteManualExpenseSubmission(
    private val executeSave: ExecuteManualExpenseSave,
    private val tracker: CommitOnceInvocationTracker,
    private val resolver: ResolveManualExpenseCommitStatus,
) {
    fun submit(input: ManualExpenseSaveInput): ManualExpenseSubmissionResult {
        tracker.reset()
        return try {
            ManualExpenseSubmissionResult.Application(executeSave.execute(input))
        } catch (failure: Exception) {
            if (!tracker.commitOnceInvoked) {
                ManualExpenseSubmissionResult.InfrastructureFailure
            } else {
                resolveAfterHandoff(input)
            }
        }
    }

    private fun resolveAfterHandoff(input: ManualExpenseSaveInput): ManualExpenseSubmissionResult {
        val attempted = attemptedSnapshot(input) ?: return ManualExpenseSubmissionResult.UnknownCommit
        return when (val resolution = resolver.resolve(input.ledgerId, input.requestId, attempted)) {
            is ManualExpenseCommitResolution.MatchingReceipt ->
                ManualExpenseSubmissionResult.Recovered(resolution.receipt)

            ManualExpenseCommitResolution.SnapshotConflict ->
                ManualExpenseSubmissionResult.Application(
                    ManualExpenseSaveResult.Executed(
                        ConfirmedManualExpenseResult.RequestIdentityConflict(
                            ManualExpenseRequestIdentity(input.ledgerId, input.requestId),
                        ),
                    ),
                )

            ManualExpenseCommitResolution.Absent,
            ManualExpenseCommitResolution.Unavailable,
            -> ManualExpenseSubmissionResult.UnknownCommit
        }
    }

    private fun attemptedSnapshot(input: ManualExpenseSaveInput): ManualExpenseRequestSnapshot? {
        val amount = input.amount ?: return null
        val categoryId = input.categoryId ?: return null
        val paymentAccountId = input.paymentAccountId ?: return null
        return ManualExpenseRequestSnapshot(
            ledgerId = input.ledgerId,
            amount = amount,
            categoryId = categoryId,
            paymentAccountId = paymentAccountId,
            occurredAt = input.occurredAt,
            note = input.note,
        )
    }
}
