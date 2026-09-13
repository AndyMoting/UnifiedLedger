package com.unifiedledger.ui

import com.unifiedledger.application.ConfirmedManualTransferResult
import com.unifiedledger.application.ConfirmedTransferReceipt
import com.unifiedledger.application.EntryType
import com.unifiedledger.application.ExpenseDraft
import com.unifiedledger.application.ManualEntryCommitResolution
import com.unifiedledger.application.ManualEntrySubmissionResult
import com.unifiedledger.application.ManualTransferCommitResolution
import com.unifiedledger.application.ManualTransferSaveResult
import com.unifiedledger.application.ManualTransferSubmissionResult
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.TransferDraft
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * P7-02.B transfer draft UI: type retention across the E-1 matrix, transfer field events, the
 * transfer submission/commit-status mapping and transfer draft validation.
 */
class P503TransferReducerTest {
    private val cny = CurrencyUnit("CNY", 2)
    private val occurredAt = Instant.parse("2026-01-15T00:30:00Z")
    private val requestId = RequestId("request-transfer-1")
    private val emptyState = com.unifiedledger.application.LedgerCurrentState(LedgerId("ledger-local-test"), emptyList(), emptyList())
    private val reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)
    private val validation = P503DraftValidation(ParseManualExpenseAmount())

    private fun transferDraft() =
        TransferDraft(
            sourceAccountId = AccountId("asset-a"),
            destinationAccountId = AccountId("asset-b"),
            destinationCredit = "100.00",
            fee = "0.00",
            feeCategoryId = null,
            occurredAt = occurredAt,
            note = "move",
        )

    private fun expenseDraft() = ExpenseDraft(AccountId("asset-a"), CategoryId("expense-leaf"), "100.00", occurredAt, "move")

    @Test
    fun switchingBetweenExpenseAndTransferRetainsAmountTimeNoteAndTheAssetAccountClass() {
        val editing = P503AppState.Editing(expenseDraft(), requestId)
        val toTransfer = assertIs<P503AppState.Editing>(reducer.reduce(editing, P503UiEvent.SelectEntryType(EntryType.TRANSFER)))
        val transfer = assertIs<TransferDraft>(toTransfer.draft)
        assertEquals("100.00", transfer.destinationCredit)
        assertEquals(occurredAt, transfer.occurredAt)
        assertEquals("move", transfer.note)
        // E-1: the expense payment account is the transfer source (asset-account class retained).
        assertEquals(AccountId("asset-a"), transfer.sourceAccountId)
        // Destination is type-specific and cleared; the category is never retained.
        assertNull(transfer.destinationAccountId)
        assertNull(transfer.feeCategoryId)

        val back = assertIs<P503AppState.Editing>(reducer.reduce(toTransfer, P503UiEvent.SelectEntryType(EntryType.EXPENSE)))
        val expense = assertIs<ExpenseDraft>(back.draft)
        assertEquals("100.00", expense.amountText)
        assertEquals(AccountId("asset-a"), expense.paymentAccountId)
        assertNull(expense.categoryId)
    }

    @Test
    fun transferFieldEventsWriteTheTransferDraft() {
        val editing = P503AppState.Editing(transferDraft(), requestId)
        val sourceUpdated = assertIs<P503AppState.Editing>(reducer.reduce(editing, P503UiEvent.UpdateTransferSourceAccount(AccountId("asset-c"))))
        assertEquals(AccountId("asset-c"), assertIs<TransferDraft>(sourceUpdated.draft).sourceAccountId)
        val destUpdated = assertIs<P503AppState.Editing>(reducer.reduce(editing, P503UiEvent.UpdateTransferDestinationAccount(AccountId("asset-d"))))
        assertEquals(AccountId("asset-d"), assertIs<TransferDraft>(destUpdated.draft).destinationAccountId)
        val creditUpdated = assertIs<P503AppState.Editing>(reducer.reduce(editing, P503UiEvent.UpdateTransferDestinationCredit("250.00")))
        assertEquals("250.00", assertIs<TransferDraft>(creditUpdated.draft).destinationCredit)
        val feeUpdated = assertIs<P503AppState.Editing>(reducer.reduce(editing, P503UiEvent.UpdateTransferFee("2.00")))
        assertEquals("2.00", assertIs<TransferDraft>(feeUpdated.draft).fee)
        val feeCatUpdated = assertIs<P503AppState.Editing>(reducer.reduce(editing, P503UiEvent.UpdateTransferFeeCategory(CategoryId("fee-leaf"))))
        assertEquals(CategoryId("fee-leaf"), assertIs<TransferDraft>(feeCatUpdated.draft).feeCategoryId)
    }

    @Test
    fun transferSubmissionCreatedMapsToCreatedAndRejectedMapsToRejected() {
        val created =
            reducer.reduce(
                P503AppState.Submitting(transferDraft(), requestId),
                P503UiEvent.SubmissionResult(
                    ManualEntrySubmissionResult.Transfer(
                        ManualTransferSubmissionResult.Application(
                            ManualTransferSaveResult.Executed(ConfirmedManualTransferResult.Created(ConfirmedTransferReceipt(com.unifiedledger.application.ConfirmationId("c"), TransactionId("t")))),
                        ),
                    ),
                ),
            )
        assertEquals(P503AppState.Created, created)

        val rejected =
            reducer.reduce(
                P503AppState.Submitting(transferDraft(), requestId),
                P503UiEvent.SubmissionResult(
                    ManualEntrySubmissionResult.Transfer(
                        ManualTransferSubmissionResult.Application(
                            ManualTransferSaveResult.Executed(ConfirmedManualTransferResult.Rejected(com.unifiedledger.domain.ManualTransferViolation.TransferSameAccount)),
                        ),
                    ),
                ),
            )
        assertIs<P503AppState.DomainRejected>(rejected)
    }

    @Test
    fun transferResolutionDrivesRecoveryAndAbsence() {
        val unknown = P503AppState.UnknownCommit(transferDraft(), requestId, emptyState, P503Tab.HOME)
        val recovered =
            reducer.reduce(
                unknown,
                P503UiEvent.CommitStatusResolved(
                    ManualEntryCommitResolution.Transfer(
                        ManualTransferCommitResolution.MatchingReceipt(ConfirmedTransferReceipt(com.unifiedledger.application.ConfirmationId("c"), TransactionId("t"))),
                    ),
                ),
            )
        assertEquals(P503AppState.Recovered, recovered)
        val absent =
            reducer.reduce(unknown, P503UiEvent.CommitStatusResolved(ManualEntryCommitResolution.Transfer(ManualTransferCommitResolution.Absent)))
        assertEquals(UnknownCommitCheckOutcome.ABSENT, assertIs<P503AppState.UnknownCommit>(absent).lastCheckOutcome)
    }

    @Test
    fun transferValidationRequiresBothAccountsAndAFeeCategoryOnlyWhenFeeIsPositive() {
        // A complete zero-fee transfer is valid.
        assertEquals(true, validation.isValid(transferDraft(), cny))
        // Missing destination is invalid.
        assertEquals(false, validation.isValid(transferDraft().copy(destinationAccountId = null), cny))
        // A positive fee requires a fee category; a zero fee must not need one.
        val positiveFeeNoCategory = transferDraft().copy(fee = "2.00")
        assertEquals(false, validation.isValid(positiveFeeNoCategory, cny))
        assertEquals(true, validation.isValid(positiveFeeNoCategory.copy(feeCategoryId = CategoryId("fee-leaf")), cny))
    }

    @Test
    fun feesAndCreditRejectInvalidFormat() {
        val badFee = transferDraft().copy(fee = "1.2.3")
        assertEquals(false, validation.isValid(badFee, cny))
        val badCredit = transferDraft().copy(destinationCredit = "abc")
        assertEquals(false, validation.isValid(badCredit, cny))
    }
}
