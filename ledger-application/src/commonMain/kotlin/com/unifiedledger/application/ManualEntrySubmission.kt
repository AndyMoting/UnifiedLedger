package com.unifiedledger.application

import com.unifiedledger.domain.LedgerId

/**
 * P7-02.A S-1: the shared UI consumes application types only, so one entry-type-tagged union
 * carries either the expense or the income submission outcome through the same
 * `SubmissionResult` event. The per-type results keep their own frozen shapes; this union adds
 * no behavior of its own.
 */
sealed interface ManualEntrySubmissionResult {
    data class Expense(
        val result: ManualExpenseSubmissionResult,
    ) : ManualEntrySubmissionResult

    data class Income(
        val result: ManualIncomeSubmissionResult,
    ) : ManualEntrySubmissionResult
}

/**
 * P7-02.A S-1: the shared analogue of [ManualEntrySubmissionResult] for the unknown-commit
 * status check, so the existing `CommitStatusResolved` event stays type-tagged for both entry
 * types.
 */
sealed interface ManualEntryCommitResolution {
    data class Expense(
        val resolution: ManualExpenseCommitResolution,
    ) : ManualEntryCommitResolution

    data class Income(
        val resolution: ManualIncomeCommitResolution,
    ) : ManualEntryCommitResolution
}

/**
 * P7-02.A S-1: entry-type-tagged save input. The composition root builds the per-type input it
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
}

/**
 * P7-02.A S-1/S-4 shared submission entry point. It enforces the note length limit once (typed
 * rejection with zero formal writes) and then delegates to the per-type submission with the
 * exact same tracker/port instances the composition root injected, so claim-first idempotency
 * and the D-119 exception recovery order are unchanged.
 */
class ExecuteManualEntrySubmission(
    private val expense: ExecuteManualExpenseSubmission,
    private val income: ExecuteManualIncomeSubmission,
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
            }
        }
        return when (input) {
            is ManualEntrySaveInput.Expense -> ManualEntrySubmissionResult.Expense(expense.submit(input.input))
            is ManualEntrySaveInput.Income -> ManualEntrySubmissionResult.Income(income.submit(input.input))
        }
    }
}
