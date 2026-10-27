package com.unifiedledger.data

import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.RequestId
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The snapshot-aware commit resolver of the read adapter
 * (`SqlDelightLedgerCurrentStateReadAdapter.findTransactionCorrectionByRequest`) over the rows
 * the claim-first request/receipt pair stores, on both transfer row shapes. Every value is
 * seeded through the product's own named queries and read back through the adapter, so a column
 * the mapper drops cannot hide behind a copy of the value the test itself holds.
 *
 * Two vectors, one per row shape, and each carries its own ablation:
 * - [feeFreeTransferRequestReadsBackWithItsTransferColumns] is the only vector whose stored
 *   `category_id` is NULL, so restoring a non-null requirement on that column reddens it alone.
 * - [feeBearingTransferRequestReadsBackWithItsTransferColumns] stores a positive fee, so dropping
 *   the `fee_minor` mapping reddens it; the fee-free vector cannot redden that one, because it
 *   compares 0 against 0.
 *
 * Both vectors assert `destination_account_id`, so dropping that mapping reddens both.
 */
class P705TransferCorrectionSnapshotReadTest {
    private val ledgerId = P705Fixture.ledgerId

    @Test
    fun feeFreeTransferRequestReadsBackWithItsTransferColumns() {
        P705Database.create(prefix = "p705-transfer-correction-snapshot-").use { harness ->
            val transactionId = "tx-transfer-correction-no-fee"
            // fee == 0 seeds the two-leg shape: the fee column of this row is zero and its
            // category column is NULL, which is a legal stored state for a transfer row.
            harness.insertManualTransfer(transactionId, destinationCreditMinor = 10_000L, feeMinor = 0L)
            val appendedVersionId = appendSecondVersion(harness, transactionId)
            val requestId = RequestId("request-transfer-correction-no-fee")
            seedCorrectionPair(
                harness = harness,
                transactionId = transactionId,
                requestId = requestId,
                appendedVersionId = appendedVersionId,
                note = "renamed transfer",
                amountMinor = 10_000L,
                categoryId = null,
                destinationAccountId = P705Fixture.bankB,
                feeMinor = 0L,
            )

            val record = assertNotNull(harness.readAdapter.findTransactionCorrectionByRequest(ledgerId, requestId))

            assertEquals(ledgerId, record.ledgerId)
            assertEquals(requestId, record.requestId)
            val snapshot = record.snapshot
            assertEquals(ledgerId, snapshot.ledgerId)
            assertEquals(TransactionId(transactionId), snapshot.transactionId)
            assertEquals(TransactionVersionId("$transactionId-version-1"), snapshot.expectedCurrentVersionId)
            assertEquals("renamed transfer", snapshot.note)
            assertEquals(P705Fixture.marchStatistics, snapshot.statisticsAt)
            assertEquals(Money.ofMinor(10_000L, P705Fixture.cny), snapshot.amount)
            assertNull(snapshot.categoryId)
            assertEquals(P705Fixture.bankA, snapshot.fundingAccountId)
            assertEquals(P705Fixture.bankB.value, snapshot.destinationAccountId)
            // This vector compares 0 against 0, so it cannot redden a dropped fee-column
            // mapping; the fee-bearing vector below carries that mapping.
            assertEquals(0L, snapshot.feeMinor)
            assertEquals(ConfirmationId("${requestId.value}-correction-confirmation"), record.receipt.confirmationId)
            assertEquals(TransactionId(transactionId), record.receipt.transactionId)
            assertEquals(TransactionVersionId(appendedVersionId), record.receipt.versionId)
            assertEquals(TransactionVersionId("$transactionId-version-1"), record.receipt.expectedCurrentVersionId)
        }
    }

    @Test
    fun feeBearingTransferRequestReadsBackWithItsTransferColumns() {
        P705Database.create(prefix = "p705-transfer-correction-snapshot-").use { harness ->
            val transactionId = "tx-transfer-correction-fee"
            // fee > 0 seeds the three-leg shape, and the request row of that shape carries the
            // fee category plus a non-zero fee.
            harness.insertManualTransfer(transactionId, destinationCreditMinor = 8_000L, feeMinor = 200L)
            val appendedVersionId = appendSecondVersion(harness, transactionId)
            val requestId = RequestId("request-transfer-correction-fee")
            seedCorrectionPair(
                harness = harness,
                transactionId = transactionId,
                requestId = requestId,
                appendedVersionId = appendedVersionId,
                note = "fee-bearing transfer",
                amountMinor = 8_000L,
                categoryId = P705Fixture.food,
                destinationAccountId = P705Fixture.bankB,
                feeMinor = 200L,
            )

            val record = assertNotNull(harness.readAdapter.findTransactionCorrectionByRequest(ledgerId, requestId))

            assertEquals(ledgerId, record.ledgerId)
            assertEquals(requestId, record.requestId)
            val snapshot = record.snapshot
            assertEquals(ledgerId, snapshot.ledgerId)
            assertEquals(TransactionId(transactionId), snapshot.transactionId)
            assertEquals(TransactionVersionId("$transactionId-version-1"), snapshot.expectedCurrentVersionId)
            assertEquals("fee-bearing transfer", snapshot.note)
            assertEquals(P705Fixture.marchStatistics, snapshot.statisticsAt)
            assertEquals(Money.ofMinor(8_000L, P705Fixture.cny), snapshot.amount)
            assertEquals(P705Fixture.food, snapshot.categoryId)
            assertEquals(P705Fixture.bankA, snapshot.fundingAccountId)
            assertEquals(P705Fixture.bankB.value, snapshot.destinationAccountId)
            assertEquals(200L, snapshot.feeMinor)
            assertEquals(ConfirmationId("${requestId.value}-correction-confirmation"), record.receipt.confirmationId)
            assertEquals(TransactionId(transactionId), record.receipt.transactionId)
            assertEquals(TransactionVersionId(appendedVersionId), record.receipt.versionId)
            assertEquals(TransactionVersionId("$transactionId-version-1"), record.receipt.expectedCurrentVersionId)
        }
    }

    /**
     * A second version of the seeded chain, so the receipt's `version_id` is a real
     * `transaction_version` row - the receipt foreign-keys
     * `(transaction_id, version_id, ledger_id)` - and differs from the request's
     * `expected_current_version_id`. Without that difference, a projection that swapped the two
     * columns would read the same value twice and pass.
     */
    private fun appendSecondVersion(
        harness: P705Database,
        transactionId: String,
    ): String {
        val versionId = "$transactionId-version-2"
        harness.database.ledgerQueries.insertTransactionVersion(
            versionId,
            transactionId,
            ledgerId.value,
            2L,
            "$transactionId-posting-set-1",
            P705Fixture.marchStatistics.toString(),
            P705Fixture.marchStatistics.toString(),
            P705Fixture.marchStatistics.toString(),
            "renamed transfer",
        )
        return versionId
    }

    /**
     * The claim-first request/receipt pair of one already-seeded transfer, written through the
     * same two named queries the commit port writes. The claim is `ON CONFLICT DO NOTHING` and
     * the bare receipt insert is the duplicate defence, exactly as on the product path.
     *
     * `category_id` and `destination_account_id` are passed as given, so a vector can store the
     * NULL category of a fee-free transfer as well as the fee category of a fee-bearing one.
     * Every interpolated identity uses `.value`: a data class instance would render as its
     * `toString()` and match nothing.
     */
    private fun seedCorrectionPair(
        harness: P705Database,
        transactionId: String,
        requestId: RequestId,
        appendedVersionId: String,
        note: String,
        amountMinor: Long,
        categoryId: CategoryId?,
        destinationAccountId: AccountId,
        feeMinor: Long,
    ) {
        harness.database.ledgerQueries.claimTransactionCorrectionRequest(
            ledger_id = ledgerId.value,
            request_id = requestId.value,
            transaction_id = transactionId,
            expected_current_version_id = "$transactionId-version-1",
            note = note,
            statistics_at = P705Fixture.marchStatistics.toString(),
            amount_minor = amountMinor,
            currency_code = P705Fixture.cny.code,
            currency_precision = P705Fixture.cny.precision.toLong(),
            category_id = categoryId?.value,
            funding_account_id = P705Fixture.bankA.value,
            confirmation_marker = "explicit_manual_save",
            destination_account_id = destinationAccountId.value,
            fee_minor = feeMinor,
        )
        harness.database.ledgerQueries.insertTransactionCorrectionReceipt(
            ledger_id = ledgerId.value,
            request_id = requestId.value,
            confirmation_id = "${requestId.value}-correction-confirmation",
            transaction_id = transactionId,
            version_id = appendedVersionId,
            expected_current_version_id = "$transactionId-version-1",
        )
    }
}
