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
 * P7-02.B transfer save-input gate and snapshot-aware resolver: a zero fee defaults to 0.00, a
 * positive fee requires a category, the resolver compares the full snapshot and absent/unavailable
 * stay unknown.
 */
class ManualTransferChainTest {
    private val ledgerId = LedgerId("ledger-a")
    private val requestId = RequestId("request-transfer")
    private val cny = CurrencyUnit("CNY", 2)
    private val occurredAt = Instant.parse("2026-01-15T00:30:00Z")
    private val receipt = ConfirmedTransferReceipt(ConfirmationId("confirmation-1"), TransactionId("tx-1"))
    private val attempted =
        ManualTransferRequestSnapshot(
            ledgerId = ledgerId,
            sourceAccountId = AccountId("asset-a"),
            destinationAccountId = AccountId("asset-b"),
            destinationCredit = Money.ofMinor(10_000L, cny),
            fee = Money.ofMinor(200L, cny),
            feeCategoryId = CategoryId("fee-leaf"),
            occurredAt = occurredAt,
            note = "rent",
        )

    private fun port(record: ManualTransferCommitRecord?): LedgerCurrentStateReadPort =
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
            ): ManualIncomeCommitRecord? = null

            override fun findManualIncomeByReceipt(
                ledgerId: LedgerId,
                receipt: ConfirmedIncomeReceipt,
            ): ManualIncomeCommitRecord? = null

            override fun findManualTransferByRequest(
                ledgerId: LedgerId,
                requestId: RequestId,
            ): ManualTransferCommitRecord? = record

            override fun findManualTransferByReceipt(
                ledgerId: LedgerId,
                receipt: ConfirmedTransferReceipt,
            ): ManualTransferCommitRecord? = record
        }

    @Test
    fun resolverMatchesConflictsAndStaysAbsent() {
        val record = ManualTransferCommitRecord(ledgerId, requestId, attempted, receipt, TransactionVersionId("v1"))
        val resolver = ResolveManualTransferCommitStatus(port(record))
        assertEquals(ManualTransferCommitResolution.MatchingReceipt(receipt), resolver.resolve(ledgerId, requestId, attempted))
        assertEquals(ManualTransferCommitResolution.SnapshotConflict, resolver.resolve(ledgerId, requestId, attempted.copy(fee = Money.ofMinor(0L, cny))))
        assertEquals(ManualTransferCommitResolution.SnapshotConflict, resolver.resolve(ledgerId, requestId, attempted.copy(note = "x")))
        assertEquals(ManualTransferCommitResolution.Absent, ResolveManualTransferCommitStatus(port(null)).resolve(ledgerId, requestId, attempted))
    }

    @Test
    fun zeroFeeDefaultsToZeroAndDoesNotRequireACategory() {
        var received: ExplicitlyConfirmedManualTransfer? = null
        val execute =
            ExecuteManualTransferSave(
                ExecuteConfirmedManualTransfer(
                    commitPort = { _, snapshot, _ ->
                        received = ExplicitlyConfirmedManualTransfer(ledgerId, requestId, snapshot.sourceAccountId, snapshot.destinationAccountId, snapshot.destinationCredit, snapshot.fee, snapshot.feeCategoryId, snapshot.occurredAt, snapshot.note, ExplicitManualSave)
                        ConfirmedManualTransferResult.Created(receipt)
                    },
                    idSource = { error("no ids") },
                    createFormalTransaction = { _, _ -> error("no factory") },
                ),
            )
        val result =
            execute.execute(
                ManualTransferSaveInput(
                    ledgerId = ledgerId,
                    requestId = requestId,
                    sourceAccountId = AccountId("asset-a"),
                    destinationAccountId = AccountId("asset-b"),
                    destinationCredit = Money.ofMinor(10_000L, cny),
                    fee = null,
                    feeCategoryId = null,
                    occurredAt = occurredAt,
                    note = "",
                    confirmation = ExplicitManualSave,
                ),
            )
        assertIs<ManualTransferSaveResult.Executed>(result)
        assertEquals(0L, received?.fee?.minorUnits)
    }

    @Test
    fun positiveFeeRequiresACategory() {
        var calls = 0
        val execute =
            ExecuteManualTransferSave(
                ExecuteConfirmedManualTransfer(
                    commitPort = { _, _, _ ->
                        calls++
                        ConfirmedManualTransferResult.Created(receipt)
                    },
                    idSource = { error("no ids") },
                    createFormalTransaction = { _, _ -> error("no factory") },
                ),
            )
        val result =
            execute.execute(
                ManualTransferSaveInput(
                    ledgerId = ledgerId,
                    requestId = requestId,
                    sourceAccountId = AccountId("asset-a"),
                    destinationAccountId = AccountId("asset-b"),
                    destinationCredit = Money.ofMinor(10_000L, cny),
                    fee = Money.ofMinor(200L, cny),
                    feeCategoryId = null,
                    occurredAt = occurredAt,
                    note = "",
                    confirmation = ExplicitManualSave,
                ),
            )
        assertEquals(ManualTransferSaveResult.InvalidInput(setOf(ManualTransferInputField.FEE_CATEGORY)), result)
        assertEquals(0, calls)
    }

    @Test
    fun missingCoreFieldsAreReportedTogether() {
        val execute =
            ExecuteManualTransferSave(
                ExecuteConfirmedManualTransfer(
                    commitPort = { _, _, _ -> error("no commit") },
                    idSource = { error("no ids") },
                    createFormalTransaction = { _, _ -> error("no factory") },
                ),
            )
        val result =
            execute.execute(
                ManualTransferSaveInput(ledgerId, requestId, null, null, null, null, null, occurredAt, "", ExplicitManualSave),
            )
        val invalid = assertIs<ManualTransferSaveResult.InvalidInput>(result)
        assertEquals(
            setOf(ManualTransferInputField.SOURCE_ACCOUNT, ManualTransferInputField.DESTINATION_ACCOUNT, ManualTransferInputField.DESTINATION_CREDIT),
            invalid.fields,
        )
    }

    @Test
    fun postHandoffFailureWithoutPersistedRecordStaysUnknownCommit() {
        val tracker = CommitOnceInvocationTrackerTransfer { _, _, _ -> error("handoff before persistence") }
        val submission =
            ExecuteManualTransferSubmission(
                ExecuteManualTransferSave(ExecuteConfirmedManualTransfer(tracker, { error("no ids") }, { _, _ -> error("no factory") })),
                tracker,
                ResolveManualTransferCommitStatus(port(null)),
            )
        val input =
            ManualTransferSaveInput(ledgerId, requestId, AccountId("asset-a"), AccountId("asset-b"), Money.ofMinor(1_000L, cny), null, null, occurredAt, "", ExplicitManualSave)
        assertEquals(ManualTransferSubmissionResult.UnknownCommit, submission.submit(input))
    }
}
