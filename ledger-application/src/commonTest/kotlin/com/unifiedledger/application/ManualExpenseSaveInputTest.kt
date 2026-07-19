package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.OrdinaryExpenseViolation
import com.unifiedledger.domain.TransactionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ManualExpenseSaveInputTest {
    private val fixture = ManualExpenseSaveFixture()

    @Test
    fun missingAmountReturnsOnlyAmountFailureWithoutCallingDownstream() {
        val harness = fixture.harness()

        val result = harness.execute(fixture.input().copy(amount = null))

        assertEquals(
            ManualExpenseSaveResult.InvalidInput(
                setOf(
                    ManualExpenseInputFailure.Missing(ManualExpenseInputField.AMOUNT),
                ),
            ),
            result,
        )
        harness.assertNoDownstreamCalls()
    }

    @Test
    fun missingPaymentAccountReturnsOnlyPaymentFailureWithoutCallingDownstream() {
        val harness = fixture.harness()

        val result = harness.execute(fixture.input().copy(paymentAccountId = null))

        assertEquals(
            ManualExpenseSaveResult.InvalidInput(
                setOf(
                    ManualExpenseInputFailure.Missing(ManualExpenseInputField.PAYMENT_ACCOUNT),
                ),
            ),
            result,
        )
        harness.assertNoDownstreamCalls()
    }

    @Test
    fun missingCategoryReturnsOnlyCategoryFailureWithoutCallingDownstream() {
        val harness = fixture.harness()

        val result = harness.execute(fixture.input().copy(categoryId = null))

        assertEquals(
            ManualExpenseSaveResult.InvalidInput(
                setOf(
                    ManualExpenseInputFailure.Missing(ManualExpenseInputField.CATEGORY),
                ),
            ),
            result,
        )
        harness.assertNoDownstreamCalls()
    }

    @Test
    fun multipleMissingFieldsReturnTheCompleteUnorderedFailureSetWithoutCallingDownstream() {
        val harness = fixture.harness()

        val result = harness.execute(
            fixture.input().copy(
                amount = null,
                categoryId = null,
            ),
        )

        assertEquals(
            ManualExpenseSaveResult.InvalidInput(
                setOf(
                    ManualExpenseInputFailure.Missing(ManualExpenseInputField.AMOUNT),
                    ManualExpenseInputFailure.Missing(ManualExpenseInputField.CATEGORY),
                ),
            ),
            result,
        )
        harness.assertNoDownstreamCalls()
    }

    @Test
    fun completeInputDelegatesOnceWithTheExactIdentityAndSnapshot() {
        val downstreamResult = ConfirmedManualExpenseResult.NoChange(
            ConfirmedExpenseReceipt(
                confirmationId = ConfirmationId("confirmation-rg01-existing"),
                transactionId = TransactionId("tx-expense-rg01-existing"),
            ),
        )
        val harness = fixture.harness(downstreamResult)
        val input = fixture.input()
        val confirmation: ExplicitManualSave = input.confirmation

        val result = harness.execute(input)

        assertEquals(ExplicitManualSave, confirmation)
        assertEquals(ManualExpenseSaveResult.Executed(downstreamResult), result)
        assertEquals(1, harness.commitPort.invocationCount)
        assertEquals(
            ManualExpenseRequestIdentity(
                ledgerId = input.ledgerId,
                requestId = input.requestId,
            ),
            harness.commitPort.capturedIdentity,
        )
        assertEquals(
            ManualExpenseRequestSnapshot(
                ledgerId = input.ledgerId,
                amount = fixture.amount,
                categoryId = fixture.categoryId,
                paymentAccountId = fixture.paymentAccountId,
                occurredAt = input.occurredAt,
                note = input.note,
            ),
            harness.commitPort.capturedSnapshot,
        )
        assertEquals(0, harness.idSource.invocationCount)
        assertEquals(0, harness.transactionFactory.invocationCount)
    }

    @Test
    fun zeroAmountIsPresentAndDelegatesTheTypedDomainRejection() {
        val zero = Money.ofMinor(0L, fixture.cny)
        val downstreamResult = ConfirmedManualExpenseResult.Rejected(
            OrdinaryExpenseViolation.AmountMustBePositive,
        )
        val harness = fixture.harness(downstreamResult)

        val result = harness.execute(fixture.input().copy(amount = zero))

        assertEquals(ManualExpenseSaveResult.Executed(downstreamResult), result)
        assertEquals(1, harness.commitPort.invocationCount)
        assertEquals(zero, harness.commitPort.capturedSnapshot?.amount)
        assertEquals(0, harness.idSource.invocationCount)
        assertEquals(0, harness.transactionFactory.invocationCount)
    }
}

private class ManualExpenseSaveFixture {
    val ledgerId = LedgerId("ledger-a")
    val cny = CurrencyUnit("CNY", 2)
    val amount = Money.ofMinor(3_580L, cny)
    val categoryId = CategoryId("expense-category-breakfast")
    val paymentAccountId = AccountId("asset-bank-a")

    fun input() = ManualExpenseSaveInput(
        ledgerId = ledgerId,
        requestId = RequestId("request-rg01-save"),
        amount = amount,
        categoryId = categoryId,
        paymentAccountId = paymentAccountId,
        occurredAt = Instant.parse("2026-01-15T00:30:00Z"),
        note = "",
        confirmation = ExplicitManualSave,
    )

    fun harness(
        downstreamResult: ConfirmedManualExpenseResult = ConfirmedManualExpenseResult.NoChange(
            ConfirmedExpenseReceipt(
                confirmationId = ConfirmationId("confirmation-unused"),
                transactionId = TransactionId("tx-unused"),
            ),
        ),
    ): ManualExpenseSaveHarness = ManualExpenseSaveHarness(downstreamResult)
}

private class ManualExpenseSaveHarness(
    downstreamResult: ConfirmedManualExpenseResult,
) {
    val commitPort = RecordingConfirmedManualExpenseCommitPort(downstreamResult)
    val idSource = FailOnCallConfirmedManualExpenseIdSource()
    val transactionFactory = FailOnCallConfirmedExpenseTransactionFactory()
    private val useCase = ExecuteManualExpenseSave(
        executeConfirmed = ExecuteConfirmedManualExpense(
            commitPort = commitPort,
            idSource = idSource,
            createFormalTransaction = transactionFactory,
        ),
    )

    fun execute(input: ManualExpenseSaveInput): ManualExpenseSaveResult =
        useCase.execute(input)

    fun assertNoDownstreamCalls() {
        assertEquals(0, commitPort.invocationCount)
        assertEquals(0, idSource.invocationCount)
        assertEquals(0, transactionFactory.invocationCount)
    }
}

private class RecordingConfirmedManualExpenseCommitPort(
    private val result: ConfirmedManualExpenseResult,
) : ConfirmedManualExpenseCommitPort {
    var invocationCount = 0
        private set
    var capturedIdentity: ManualExpenseRequestIdentity? = null
        private set
    var capturedSnapshot: ManualExpenseRequestSnapshot? = null
        private set

    override fun commitOnce(
        identity: ManualExpenseRequestIdentity,
        requestSnapshot: ManualExpenseRequestSnapshot,
        createFormalTransaction: () -> DomainResult<ConfirmedManualExpenseCommit>,
    ): ConfirmedManualExpenseResult {
        invocationCount += 1
        capturedIdentity = identity
        capturedSnapshot = requestSnapshot
        return result
    }
}

private class FailOnCallConfirmedManualExpenseIdSource : ConfirmedManualExpenseIdSource {
    var invocationCount = 0
        private set

    override fun next(): ConfirmedManualExpenseCommitIds {
        invocationCount += 1
        error("The recording commit port must not request IDs")
    }
}

private class FailOnCallConfirmedExpenseTransactionFactory : ConfirmedExpenseTransactionFactory {
    var invocationCount = 0
        private set

    override fun create(
        request: ManualExpenseRequestSnapshot,
        ids: ConfirmedManualExpenseCommitIds,
    ): DomainResult<ConfirmedManualExpenseCommit> {
        invocationCount += 1
        error("The recording commit port must not create a transaction")
    }
}
