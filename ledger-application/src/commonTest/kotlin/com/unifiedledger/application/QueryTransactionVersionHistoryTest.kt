package com.unifiedledger.application

import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersion
import com.unifiedledger.domain.TransactionVersionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * P8-00 b3 (V-6): the read-only version-chain use case. The chain is the domain rows in the read
 * port's ascending version-number order; a transaction outside the effective set is NotFound (the
 * detail read's predicate — never a leaked cross-ledger or voided chain), a read failure is
 * Unavailable, an empty chain behind an existing effective transaction is InvalidState, and a G6
 * unimplemented port surfaces as Unavailable (never a fabricated empty history).
 */
class QueryTransactionVersionHistoryTest {
    private val ledgerId = LedgerId("ledger-version-history-test")
    private val transactionId = TransactionId("tx-1")

    @Test
    fun theFullChainReturnsInAscendingVersionOrderWithTheCurrentMarker() {
        val versions =
            listOf(
                version("version-1", 1),
                version("version-2", 2),
                version("version-3", 3),
            )
        val result =
            QueryTransactionVersionHistory(
                VersionHistoryPort(entryRow = entryRow(currentVersionId = "version-2"), versions = versions),
                ledgerId,
            ).query(transactionId)
        val history = assertIs<TransactionVersionHistoryResult.Success>(result).history
        assertEquals(ledgerId, history.ledgerId)
        assertEquals(transactionId, history.transactionId)
        assertEquals(TransactionVersionId("version-2"), history.currentVersionId)
        assertEquals(listOf(1, 2, 3), history.versions.map { it.versionNumber })
        // Every row is the domain row verbatim (postingSetId/times/note ride unchanged).
        assertEquals(listOf("posting-set-1", "posting-set-2", "posting-set-3"), history.versions.map { it.postingSetId.value })
        assertEquals(listOf("note-1", null, "note-3"), history.versions.map { it.note })
    }

    @Test
    fun aTransactionOutsideTheEffectiveSetIsNotFoundNeverAnEmptyChain() {
        val result =
            QueryTransactionVersionHistory(
                VersionHistoryPort(entryRow = null, versions = listOf(version("version-1", 1))),
                ledgerId,
            ).query(transactionId)
        assertIs<TransactionVersionHistoryResult.NotFound>(result)
    }

    @Test
    fun aReadFailureIsUnavailableNeverAnEmptyHistory() {
        assertIs<TransactionVersionHistoryResult.Unavailable>(
            QueryTransactionVersionHistory(VersionHistoryPort(entryRow = null, throwOnEntryRow = true), ledgerId).query(transactionId),
        )
        assertIs<TransactionVersionHistoryResult.Unavailable>(
            QueryTransactionVersionHistory(
                VersionHistoryPort(entryRow = entryRow("version-1"), versions = emptyList(), throwOnVersions = true),
                ledgerId,
            ).query(transactionId),
        )
    }

    @Test
    fun anEmptyChainBehindAnEffectiveTransactionIsInvalidStateNeverAnHonestZero() {
        // An effective transaction always carries at least its creation version; an empty chain is
        // a persistence inconsistency and renders the typed failure, never "1 个版本"-style claims.
        val result =
            QueryTransactionVersionHistory(
                VersionHistoryPort(entryRow = entryRow("version-1"), versions = emptyList()),
                ledgerId,
            ).query(transactionId)
        assertIs<TransactionVersionHistoryResult.InvalidState>(result)
    }

    @Test
    fun aG6UnimplementedPortSurfacesAsUnavailableNotAFabricatedCount() {
        // The inherited default throws; the use case maps it to the typed Unavailable, so an
        // unimplemented adapter can never render a definitive version count (R-Q06-4).
        val result =
            QueryTransactionVersionHistory(
                EntryRowsOnlyPort(entryRow = entryRow("version-1")),
                ledgerId,
            ).query(transactionId)
        assertIs<TransactionVersionHistoryResult.Unavailable>(result)
    }

    private fun entryRow(currentVersionId: String): LedgerEntryRow =
        LedgerEntryRow(
            transactionId = transactionId,
            currentVersionId = TransactionVersionId(currentVersionId),
            kind = com.unifiedledger.domain.TransactionKind.EXPENSE,
            occurredAt = Instant.parse("2026-03-01T02:00:00Z"),
            statisticsAt = Instant.parse("2026-03-01T02:00:00Z"),
            note = null,
            postings = emptyList(),
        )

    private fun version(
        versionId: String,
        number: Int,
    ): TransactionVersion =
        TransactionVersion(
            id = TransactionVersionId(versionId),
            transactionId = transactionId,
            versionNumber = number,
            postingSetId = PostingSetId("posting-set-$number"),
            times =
                TransactionTimes(
                    occurredAt = Instant.parse("2026-03-01T02:00:00Z"),
                    statisticsAt = Instant.parse("2026-03-01T02:00:00Z"),
                    effectiveAt = Instant.parse("2026-03-01T02:00:00Z"),
                ),
            note = if (number == 2) null else "note-$number",
        )
}

/** Read-port fake: the effective entry row plus the version chain, with per-read throw switches. */
private class VersionHistoryPort(
    private val entryRow: LedgerEntryRow?,
    private val versions: List<TransactionVersion> = emptyList(),
    private val throwOnEntryRow: Boolean = false,
    private val throwOnVersions: Boolean = false,
) : LedgerCurrentStateReadPort {
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
    ): ManualTransferCommitRecord? = null

    override fun findManualTransferByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedTransferReceipt,
    ): ManualTransferCommitRecord? = null

    override fun loadLedgerEntryRow(
        ledgerId: LedgerId,
        transactionId: TransactionId,
    ): LedgerEntryRow? {
        if (throwOnEntryRow) throw IllegalStateException("database unavailable")
        return entryRow
    }

    override fun loadTransactionVersions(
        ledgerId: LedgerId,
        transactionId: TransactionId,
    ): List<TransactionVersion> {
        if (throwOnVersions) throw IllegalStateException("database unavailable")
        return versions
    }
}

/** G6 base fake: implements only the pre-P8 surface, so the version-chain read exercises the inherited throwing default. */
private class EntryRowsOnlyPort(
    private val entryRow: LedgerEntryRow?,
) : LedgerCurrentStateReadPort {
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
    ): ManualTransferCommitRecord? = null

    override fun findManualTransferByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedTransferReceipt,
    ): ManualTransferCommitRecord? = null

    override fun loadLedgerEntryRow(
        ledgerId: LedgerId,
        transactionId: TransactionId,
    ): LedgerEntryRow? = entryRow
}
