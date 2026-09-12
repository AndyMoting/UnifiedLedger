package com.unifiedledger.application

import com.unifiedledger.domain.LedgerId

/**
 * P7-02 S-1: the shared UI consumes application types only, so one entry-type-tagged union
 * carries the per-type submission outcome through the same `SubmissionResult` event. The
 * per-type results keep their own frozen shapes; this union adds no behavior of its own.
 */
sealed interface ManualEntrySubmissionResult {
    data class Expense(
        val result: ManualExpenseSubmissionResult,
    ) : ManualEntrySubmissionResult

    data class Income(
        val result: ManualIncomeSubmissionResult,
    ) : ManualEntrySubmissionResult

    data class Transfer(
        val result: ManualTransferSubmissionResult,
    ) : ManualEntrySubmissionResult

    data class Lend(
        val result: ManualLendSubmissionResult,
    ) : ManualEntrySubmissionResult

    data class Collect(
        val result: ManualCollectSubmissionResult,
    ) : ManualEntrySubmissionResult
}

/**
 * P7-02 S-1: the shared analogue of [ManualEntrySubmissionResult] for the unknown-commit status
 * check, so the existing `CommitStatusResolved` event stays type-tagged for every entry type.
 */
sealed interface ManualEntryCommitResolution {
    data class Expense(
        val resolution: ManualExpenseCommitResolution,
    ) : ManualEntryCommitResolution

    data class Income(
        val resolution: ManualIncomeCommitResolution,
    ) : ManualEntryCommitResolution

    data class Transfer(
        val resolution: ManualTransferCommitResolution,
    ) : ManualEntryCommitResolution

    data class Lend(
        val resolution: ManualLendingCommitResolution,
    ) : ManualEntryCommitResolution

    data class Collect(
        val resolution: ManualLendingCommitResolution,
    ) : ManualEntryCommitResolution
}

/**
 * P7-02 S-1: entry-type-tagged save input. The composition root builds the per-type input it
 * already knows how to build; the shared submission entry point dispatches on this union.
 */
sealed interface ManualEntrySaveInput {
    val ledgerId: LedgerId

    val requestId: RequestId

    val note: String

    data class Expense(
        val input: ManualExpenseSaveInput,
    ) : ManualEntrySaveInput {
        override val ledgerId: LedgerId get() = input.ledgerId

        override val requestId: RequestId get() = input.requestId

        override val note: String get() = input.note
    }

    data class Income(
        val input: ManualIncomeSaveInput,
    ) : ManualEntrySaveInput {
        override val ledgerId: LedgerId get() = input.ledgerId

        override val requestId: RequestId get() = input.requestId

        override val note: String get() = input.note
    }

    data class Transfer(
        val input: ManualTransferSaveInput,
    ) : ManualEntrySaveInput {
        override val ledgerId: LedgerId get() = input.ledgerId

        override val requestId: RequestId get() = input.requestId

        override val note: String get() = input.note
    }

    data class Lend(
        val input: ManualLendSaveInput,
    ) : ManualEntrySaveInput {
        override val ledgerId: LedgerId get() = input.ledgerId

        override val requestId: RequestId get() = input.requestId

        override val note: String get() = input.note
    }

    data class Collect(
        val input: ManualCollectSaveInput,
    ) : ManualEntrySaveInput {
        override val ledgerId: LedgerId get() = input.ledgerId

        override val requestId: RequestId get() = input.requestId

        override val note: String get() = input.note
    }
}

/**
 * P7-02.C per-behavior lending submission, so [ExecuteManualEntrySubmission] can dispatch a LEND
 * or COLLECT save input without eagerly constructing a submission it may not need.
 */
interface ManualLendingSubmission {
    fun submitLend(input: ManualLendSaveInput): ManualLendSubmissionResult

    fun submitCollect(input: ManualCollectSaveInput): ManualCollectSubmissionResult
}

class ExecuteLendingSubmission(
    private val submission: ExecuteManualLendingSubmission,
) : ManualLendingSubmission {
    override fun submitLend(input: ManualLendSaveInput): ManualLendSubmissionResult = submission.saveLend(input)

    override fun submitCollect(input: ManualCollectSaveInput): ManualCollectSubmissionResult = submission.saveCollect(input)
}

/**
 * P7-02 S-1/S-4 shared submission entry point. It enforces the note length limit once (typed
 * rejection with zero formal writes) and then delegates to the per-type submission with the
 * exact same tracker/port instances the composition root injected, so claim-first idempotency
 * and the D-119 exception recovery order are unchanged.
 */
class ExecuteManualEntrySubmission(
    private val expense: ExecuteManualExpenseSubmission,
    private val income: ExecuteManualIncomeSubmission,
    private val transfer: ExecuteManualTransferSubmission? = null,
    private val lending: ManualLendingSubmission? = null,
) {
    fun submit(input: ManualEntrySaveInput): ManualEntrySubmissionResult {
        validateEntryNote(input.note)?.let { violation ->
            return when (input) {
                is ManualEntrySaveInput.Expense ->
                    ManualEntrySubmissionResult.Expense(
                        ManualExpenseSubmissionResult.Application(
                            ManualExpenseSaveResult.Executed(ConfirmedManualExpenseResult.Rejected(violation)),
                        ),
                    )

                is ManualEntrySaveInput.Income ->
                    ManualEntrySubmissionResult.Income(
                        ManualIncomeSubmissionResult.Application(
                            ManualIncomeSaveResult.Executed(ConfirmedManualIncomeResult.Rejected(violation)),
                        ),
                    )

                is ManualEntrySaveInput.Transfer ->
                    ManualEntrySubmissionResult.Transfer(
                        ManualTransferSubmissionResult.Application(
                            ManualTransferSaveResult.Executed(ConfirmedManualTransferResult.Rejected(violation)),
                        ),
                    )

                is ManualEntrySaveInput.Lend ->
                    ManualEntrySubmissionResult.Lend(
                        ManualLendSubmissionResult.Application(
                            ManualLendSaveResult.Executed(ConfirmedManualLendingResult.Rejected(violation)),
                        ),
                    )

                is ManualEntrySaveInput.Collect ->
                    ManualEntrySubmissionResult.Collect(
                        ManualCollectSubmissionResult.Application(
                            ManualCollectSaveResult.Executed(ConfirmedManualLendingResult.Rejected(violation)),
                        ),
                    )
            }
        }
        return when (input) {
            is ManualEntrySaveInput.Expense -> ManualEntrySubmissionResult.Expense(expense.submit(input.input))
            is ManualEntrySaveInput.Income -> ManualEntrySubmissionResult.Income(income.submit(input.input))
            is ManualEntrySaveInput.Transfer -> {
                val submission = transfer ?: return ManualEntrySubmissionResult.Transfer(ManualTransferSubmissionResult.UnknownCommit)
                ManualEntrySubmissionResult.Transfer(submission.submit(input.input))
            }

            is ManualEntrySaveInput.Lend -> {
                val submission = lending ?: return ManualEntrySubmissionResult.Lend(ManualLendSubmissionResult.UnknownCommit)
                ManualEntrySubmissionResult.Lend(submission.submitLend(input.input))
            }

            is ManualEntrySaveInput.Collect -> {
                val submission = lending ?: return ManualEntrySubmissionResult.Collect(ManualCollectSubmissionResult.UnknownCommit)
                ManualEntrySubmissionResult.Collect(submission.submitCollect(input.input))
            }
        }
    }
}
