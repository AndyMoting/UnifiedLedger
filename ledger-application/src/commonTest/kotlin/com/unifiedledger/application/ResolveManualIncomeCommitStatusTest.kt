package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * P7-02.A S-1/§5.2: the income snapshot-aware resolver compares the full snapshot (including
 * note) field by field; only a matching receipt recovers, a differing snapshot conflicts and
 * absent/unavailable stay unknown.
 */
class ResolveManualIncomeCommitStatusTest {
    private val ledgerId = LedgerId("ledger-a")
    private val requestId = RequestId("request-income")
    private val cny = CurrencyUnit("CNY", 2)
    private val occurredAt = Instant.parse("2026-01-15T00:30:00Z")
    private val receipt = ConfirmedIncomeReceipt(ConfirmationId("confirmation-1"), TransactionId("tx-1"))
    private val attempted =
        ManualIncomeRequestSnapshot(
            ledgerId = ledgerId,
            amount = Money.ofMinor(3_000L, cny),
            categoryId = CategoryId("income-leaf"),
            receivingAccountId = AccountId("asset-payment-local"),
            occurredAt = occurredAt,
            note = "salary",
        )

    private fun port(record: ManualIncomeCommitRecord?): LedgerCurrentStateReadPort =
        object : LedgerCurrentStateReadPort {
            override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> = emptyList()

            override fun findManualExpenseByRequest(
                ledgerId: LedgerId,
                requestId: RequestId,
            ): ManualExpenseCommitRecord? = null

            override fun findManualExpenseByReceipt(
                ledgerId: LedgerId,
                receipt: ConfirmedExpenseReceipt,
            ): ManualExpenseCommitRecord? = null

            override fun findManualIncomeByRequest(
                ledgerId: LedgerId,
                requestId: RequestId,
            ): ManualIncomeCommitRecord? = record

            override fun findManualIncomeByReceipt(
                ledgerId: LedgerId,
                receipt: ConfirmedIncomeReceipt,
            ): ManualIncomeCommitRecord? = record
        }

    @Test
    fun matchingSnapshotReturnsMatchingReceipt() {
        val record = ManualIncomeCommitRecord(ledgerId, requestId, attempted, receipt, TransactionVersionId("v1"))
        val resolution = ResolveManualIncomeCommitStatus(port(record)).resolve(ledgerId, requestId, attempted)
        assertEquals(ManualIncomeCommitResolution.MatchingReceipt(receipt), resolution)
    }

    @Test
    fun noteDifferenceIsASnapshotConflict() {
        val record = ManualIncomeCommitRecord(ledgerId, requestId, attempted, receipt, TransactionVersionId("v1"))
        val resolution =
            ResolveManualIncomeCommitStatus(port(record))
                .resolve(ledgerId, requestId, attempted.copy(note = "different"))
        assertEquals(ManualIncomeCommitResolution.SnapshotConflict, resolution)
    }

    @Test
    fun accountDifferenceIsASnapshotConflict() {
        val record = ManualIncomeCommitRecord(ledgerId, requestId, attempted, receipt, TransactionVersionId("v1"))
        val resolution =
            ResolveManualIncomeCommitStatus(port(record))
                .resolve(ledgerId, requestId, attempted.copy(receivingAccountId = AccountId("other")))
        assertEquals(ManualIncomeCommitResolution.SnapshotConflict, resolution)
    }

    @Test
    fun absentRecordStaysAbsentAndExceptionStaysUnavailable() {
        assertEquals(
            ManualIncomeCommitResolution.Absent,
            ResolveManualIncomeCommitStatus(port(null)).resolve(ledgerId, requestId, attempted),
        )
        val throwing =
            object : LedgerCurrentStateReadPort {
                override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> = emptyList()

                override fun findManualExpenseByRequest(
                    ledgerId: LedgerId,
                    requestId: RequestId,
                ): ManualExpenseCommitRecord? = null

                override fun findManualExpenseByReceipt(
                    ledgerId: LedgerId,
                    receipt: ConfirmedExpenseReceipt,
                ): ManualExpenseCommitRecord? = null

                override fun findManualIncomeByRequest(
                    ledgerId: LedgerId,
                    requestId: RequestId,
                ): ManualIncomeCommitRecord? = throw IllegalStateException("db down")

                override fun findManualIncomeByReceipt(
                    ledgerId: LedgerId,
                    receipt: ConfirmedIncomeReceipt,
                ): ManualIncomeCommitRecord? = throw IllegalStateException("db down")
            }
        assertEquals(
            ManualIncomeCommitResolution.Unavailable,
            ResolveManualIncomeCommitStatus(throwing).resolve(ledgerId, requestId, attempted),
        )
    }

    @Test
    fun incomeSubmissionAfterHandoffWithoutPersistedRecordStaysUnknownCommit() {
        val tracker = CommitOnceInvocationTrackerIncome { _, _, _ -> error("handoff failure before persistence") }
        val resolver = ResolveManualIncomeCommitStatus(port(null))
        val submission =
            ExecuteManualIncomeSubmission(
                executeSave = ExecuteManualIncomeSave(ExecuteConfirmedManualIncome(tracker, ConfirmedManualIncomeIdSource { error("no ids") }, ConfirmedIncomeTransactionFactory { _, _ -> error("no factory") })),
                tracker = tracker,
                resolver = resolver,
            )
        val result =
            submission.submit(
                ManualIncomeSaveInput(
                    ledgerId = ledgerId,
                    requestId = requestId,
                    amount = Money.ofMinor(3_000L, cny),
                    categoryId = CategoryId("income-leaf"),
                    receivingAccountId = AccountId("asset-payment-local"),
                    occurredAt = occurredAt,
                    note = "salary",
                    confirmation = ExplicitManualSave,
                ),
            )
        assertEquals(ManualIncomeSubmissionResult.UnknownCommit, result)
    }

    @Test
    fun incomeSubmissionRecoversAfterHandoffWhenReceiptMatches() {
        val receipt = ConfirmedIncomeReceipt(ConfirmationId("confirmation-x"), TransactionId("tx-x"))
        val record = ManualIncomeCommitRecord(ledgerId, requestId, attempted, receipt, TransactionVersionId("v1"))
        var commits = 0
        val tracker =
            CommitOnceInvocationTrackerIncome(
                ConfirmedManualIncomeCommitPort { _, _, _ ->
                    commits += 1
                    throw IllegalStateException("handoff failure")
                },
            )
        val submission =
            ExecuteManualIncomeSubmission(
                executeSave = ExecuteManualIncomeSave(ExecuteConfirmedManualIncome(tracker, ConfirmedManualIncomeIdSource { error("no ids") }, ConfirmedIncomeTransactionFactory { _, _ -> error("no factory") })),
                tracker = tracker,
                resolver = ResolveManualIncomeCommitStatus(port(record)),
            )
        val result =
            submission.submit(
                ManualIncomeSaveInput(
                    ledgerId = ledgerId,
                    requestId = requestId,
                    amount = Money.ofMinor(3_000L, cny),
                    categoryId = CategoryId("income-leaf"),
                    receivingAccountId = AccountId("asset-payment-local"),
                    occurredAt = occurredAt,
                    note = "salary",
                    confirmation = ExplicitManualSave,
                ),
            )
        assertEquals(1, commits)
        assertEquals(ManualIncomeSubmissionResult.Recovered(receipt), result)
    }

    @Test
    fun failingNoteValidationRejectsBeforeAnyDelegation() {
        var expenseCalls = 0
        val expense =
            ExecuteManualExpenseSubmission(
                executeSave =
                    ExecuteManualExpenseSave(
                        ExecuteConfirmedManualExpense(
                            CommitOnceInvocationTracker { _, _, _ ->
                                expenseCalls++
                                error("must not commit")
                            },
                            ConfirmedManualExpenseIdSource { error("no ids") },
                            ConfirmedExpenseTransactionFactory { _, _ -> error("no factory") },
                        ),
                    ),
                tracker =
                    CommitOnceInvocationTracker { _, _, _ ->
                        expenseCalls++
                        error("must not commit")
                    },
                resolver = ResolveManualExpenseCommitStatus(port(null)),
            )
        val incomeTracker = CommitOnceInvocationTrackerIncome { _, _, _ -> error("must not commit") }
        val entry =
            ExecuteManualEntrySubmission(
                expense = expense,
                income =
                    ExecuteManualIncomeSubmission(
                        executeSave = ExecuteManualIncomeSave(ExecuteConfirmedManualIncome(incomeTracker, ConfirmedManualIncomeIdSource { error("no ids") }, ConfirmedIncomeTransactionFactory { _, _ -> error("no factory") })),
                        tracker = incomeTracker,
                        resolver = ResolveManualIncomeCommitStatus(port(null)),
                    ),
            )
        val tooLong = "a".repeat(ENTRY_NOTE_MAX_CODE_POINTS + 1)
        val result =
            entry.submit(
                ManualEntrySaveInput.Income(
                    ManualIncomeSaveInput(
                        ledgerId = ledgerId,
                        requestId = requestId,
                        amount = Money.ofMinor(3_000L, cny),
                        categoryId = CategoryId("income-leaf"),
                        receivingAccountId = AccountId("asset-payment-local"),
                        occurredAt = occurredAt,
                        note = tooLong,
                        confirmation = ExplicitManualSave,
                    ),
                ),
            )
        val application = assertIs<ManualEntrySubmissionResult.Income>(result)
        val executed = assertIs<ManualIncomeSaveResult.Executed>(assertIs<ManualIncomeSubmissionResult.Application>(application.result).result)
        val rejected = assertIs<ConfirmedManualIncomeResult.Rejected>(executed.result)
        assertEquals(com.unifiedledger.domain.EntryFoundationViolation.NoteTooLong, rejected.violation)
        assertEquals(0, expenseCalls)
    }
}
