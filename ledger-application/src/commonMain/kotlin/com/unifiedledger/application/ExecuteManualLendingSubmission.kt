package com.unifiedledger.application

import com.unifiedledger.domain.DomainResult

/**
 * P7-02.C submission orchestration and unknown-commit classification, mirroring
 * [ExecuteManualTransferSubmission]: pre-handoff exceptions are an infrastructure failure,
 * post-handoff exceptions resolve against the lending read model, and absence/unavailability stay
 * unknown (no auto retry, no requestId replacement).
 */
sealed interface ManualLendSubmissionResult {
    data class Application(
        val result: ManualLendSaveResult,
    ) : ManualLendSubmissionResult

    data class Recovered(
        val receipt: ConfirmedLendingReceipt,
    ) : ManualLendSubmissionResult

    data object InfrastructureFailure : ManualLendSubmissionResult

    data object UnknownCommit : ManualLendSubmissionResult
}

sealed interface ManualCollectSubmissionResult {
    data class Application(
        val result: ManualCollectSaveResult,
    ) : ManualCollectSubmissionResult

    data class Recovered(
        val receipt: ConfirmedLendingReceipt,
    ) : ManualCollectSubmissionResult

    data object InfrastructureFailure : ManualCollectSubmissionResult

    data object UnknownCommit : ManualCollectSubmissionResult
}

class CommitOnceInvocationTrackerLending(
    private val delegate: ConfirmedManualLendingCommitPort,
) : ConfirmedManualLendingCommitPort {
    var commitOnceInvoked: Boolean = false
        private set

    fun reset() {
        commitOnceInvoked = false
    }

    override fun commitOnce(
        identity: ManualLendingRequestIdentity,
        requestSnapshot: ManualLendingRequestSnapshot,
        createFormalTransaction: () -> DomainResult<ConfirmedManualLendingCommit>,
    ): ConfirmedManualLendingResult {
        commitOnceInvoked = true
        return delegate.commitOnce(identity, requestSnapshot, createFormalTransaction)
    }
}

private fun ManualLendSaveInput.attemptedSnapshot(): ManualLendingRequestSnapshot? {
    val counterpartyId = counterpartyId ?: return null
    val fundingAccountId = fundingAccountId ?: return null
    val amount = amount ?: return null
    return ManualLendingRequestSnapshot(
        ledgerId = ledgerId,
        behavior = ManualLendingBehavior.LEND,
        counterpartyId = counterpartyId,
        principalAccountId = fundingAccountId,
        amount = amount,
        interest =
            com.unifiedledger.domain.Money
                .ofMinor(0L, amount.currency),
        fee =
            com.unifiedledger.domain.Money
                .ofMinor(0L, amount.currency),
        totalReceived = null,
        interestCategoryId = null,
        occurredAt = occurredAt,
        note = note,
    )
}

private fun ManualCollectSaveInput.attemptedSnapshot(): ManualLendingRequestSnapshot? {
    val counterpartyId = counterpartyId ?: return null
    val destinationAccountId = destinationAccountId ?: return null
    val totalReceived = totalReceived ?: return null
    val principal = principal ?: return null
    val interest = interest ?: return null
    val interestCategoryId = interestCategoryId ?: return null
    return ManualLendingRequestSnapshot(
        ledgerId = ledgerId,
        behavior = ManualLendingBehavior.COLLECT,
        counterpartyId = counterpartyId,
        principalAccountId = destinationAccountId,
        amount = principal,
        interest = interest,
        fee =
            com.unifiedledger.domain.Money
                .ofMinor(0L, totalReceived.currency),
        totalReceived = totalReceived,
        interestCategoryId = interestCategoryId,
        occurredAt = occurredAt,
        note = note,
    )
}

class ExecuteManualLendingSubmission(
    private val executeSave: ExecuteManualLendingSave,
    private val tracker: CommitOnceInvocationTrackerLending,
    private val resolver: ResolveManualLendingCommitStatus,
) {
    fun saveLend(input: ManualLendSaveInput): ManualLendSubmissionResult = submitLend(input)

    fun saveCollect(input: ManualCollectSaveInput): ManualCollectSubmissionResult = submitCollect(input)

    private fun submitLend(input: ManualLendSaveInput): ManualLendSubmissionResult {
        tracker.reset()
        return try {
            ManualLendSubmissionResult.Application(executeSave.saveLend(input))
        } catch (failure: Exception) {
            if (!tracker.commitOnceInvoked) {
                ManualLendSubmissionResult.InfrastructureFailure
            } else {
                resolveAfterHandoff(input)
            }
        }
    }

    private fun submitCollect(input: ManualCollectSaveInput): ManualCollectSubmissionResult {
        tracker.reset()
        return try {
            ManualCollectSubmissionResult.Application(executeSave.saveCollect(input))
        } catch (failure: Exception) {
            if (!tracker.commitOnceInvoked) {
                ManualCollectSubmissionResult.InfrastructureFailure
            } else {
                resolveCollectAfterHandoff(input)
            }
        }
    }

    private fun resolveAfterHandoff(input: ManualLendSaveInput): ManualLendSubmissionResult {
        val attempted = input.attemptedSnapshot() ?: return ManualLendSubmissionResult.UnknownCommit
        return when (val resolution = resolver.resolve(input.ledgerId, input.requestId, attempted)) {
            is ManualLendingCommitResolution.MatchingReceipt ->
                ManualLendSubmissionResult.Recovered(resolution.receipt)

            ManualLendingCommitResolution.SnapshotConflict ->
                ManualLendSubmissionResult.Application(
                    ManualLendSaveResult.Executed(
                        ConfirmedManualLendingResult.RequestIdentityConflict(
                            ManualLendingRequestIdentity(input.ledgerId, input.requestId),
                        ),
                    ),
                )

            ManualLendingCommitResolution.Absent,
            ManualLendingCommitResolution.Unavailable,
            -> ManualLendSubmissionResult.UnknownCommit
        }
    }

    private fun resolveCollectAfterHandoff(input: ManualCollectSaveInput): ManualCollectSubmissionResult {
        val attempted = input.attemptedSnapshot() ?: return ManualCollectSubmissionResult.UnknownCommit
        return when (val resolution = resolver.resolve(input.ledgerId, input.requestId, attempted)) {
            is ManualLendingCommitResolution.MatchingReceipt ->
                ManualCollectSubmissionResult.Recovered(resolution.receipt)

            ManualLendingCommitResolution.SnapshotConflict ->
                ManualCollectSubmissionResult.Application(
                    ManualCollectSaveResult.Executed(
                        ConfirmedManualLendingResult.RequestIdentityConflict(
                            ManualLendingRequestIdentity(input.ledgerId, input.requestId),
                        ),
                    ),
                )

            ManualLendingCommitResolution.Absent,
            ManualLendingCommitResolution.Unavailable,
            -> ManualCollectSubmissionResult.UnknownCommit
        }
    }
}
