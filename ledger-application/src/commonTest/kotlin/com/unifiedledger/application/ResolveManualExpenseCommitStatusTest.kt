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

class ResolveManualExpenseCommitStatusTest {
    private val ledgerId = LedgerId("ledger-a")
    private val requestId = RequestId("request-p5-03")
    private val cny = CurrencyUnit("CNY", 2)
    private val occurredAt = Instant.parse("2026-01-15T00:30:00Z")
    private val receipt =
        ConfirmedExpenseReceipt(
            confirmationId = ConfirmationId("confirmation-1"),
            transactionId = TransactionId("tx-1"),
        )
    private val attempted =
        ManualExpenseRequestSnapshot(
            ledgerId = ledgerId,
            amount = Money.ofMinor(3_580L, cny),
            categoryId = CategoryId("expense-category-breakfast"),
            paymentAccountId = AccountId("asset-payment-local"),
            occurredAt = occurredAt,
            note = "",
        )

    @Test
    fun persistedSnapshotEqualToAttemptedReturnsMatchingReceipt() {
        val resolver =
            ResolveManualExpenseCommitStatus(
                ResolutionFixedReadPort(
                    ManualExpenseCommitRecord(
                        ledgerId = ledgerId,
                        requestId = requestId,
                        snapshot = attempted,
                        receipt = receipt,
                        currentVersionId = TransactionVersionId("version-1"),
                    ),
                ),
            )

        val result = resolver.resolve(ledgerId, requestId, attempted)

        val matching = assertIs<ManualExpenseCommitResolution.MatchingReceipt>(result)
        assertEquals(receipt, matching.receipt)
    }

    @Test
    fun persistedSnapshotDifferentOnAnyFieldReturnsSnapshotConflict() {
        val conflictingSnapshots =
            listOf(
                attempted.copy(amount = Money.ofMinor(3_581L, cny)),
                attempted.copy(amount = Money.ofMinor(3_580L, CurrencyUnit("USD", 2))),
                attempted.copy(categoryId = CategoryId("expense-category-other")),
                attempted.copy(paymentAccountId = AccountId("asset-payment-other")),
                attempted.copy(occurredAt = Instant.parse("2026-01-15T00:31:00Z")),
                attempted.copy(note = "changed note"),
            )
        for (conflicting in conflictingSnapshots) {
            val resolver =
                ResolveManualExpenseCommitStatus(
                    ResolutionFixedReadPort(
                        ManualExpenseCommitRecord(
                            ledgerId = ledgerId,
                            requestId = requestId,
                            snapshot = conflicting,
                            receipt = receipt,
                            currentVersionId = TransactionVersionId("version-1"),
                        ),
                    ),
                )

            assertEquals(
                ManualExpenseCommitResolution.SnapshotConflict,
                resolver.resolve(ledgerId, requestId, attempted),
            )
        }
    }

    @Test
    fun noPersistedRequestReturnsAbsent() {
        val resolver = ResolveManualExpenseCommitStatus(ResolutionFixedReadPort(null))

        assertEquals(ManualExpenseCommitResolution.Absent, resolver.resolve(ledgerId, requestId, attempted))
    }

    @Test
    fun readPortExceptionReturnsUnavailable() {
        val resolver = ResolveManualExpenseCommitStatus(ResolutionThrowingReadPort())

        assertEquals(ManualExpenseCommitResolution.Unavailable, resolver.resolve(ledgerId, requestId, attempted))
    }
}

private class ResolutionFixedReadPort(
    private val record: ManualExpenseCommitRecord?,
) : LedgerCurrentStateReadPort {
    override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> = emptyList()

    override fun findManualExpenseByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualExpenseCommitRecord? = record

    override fun findManualExpenseByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedExpenseReceipt,
    ): ManualExpenseCommitRecord? = record
}

private class ResolutionThrowingReadPort : LedgerCurrentStateReadPort {
    override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> = throw IllegalStateException("database unavailable")

    override fun findManualExpenseByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualExpenseCommitRecord? = throw IllegalStateException("database unavailable")

    override fun findManualExpenseByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedExpenseReceipt,
    ): ManualExpenseCommitRecord? = throw IllegalStateException("database unavailable")
}
