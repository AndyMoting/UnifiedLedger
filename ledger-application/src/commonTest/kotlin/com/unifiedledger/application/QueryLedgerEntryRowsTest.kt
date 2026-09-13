package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * P7-03.C display-ordering contract (spec section 4.2.5, P703SPEC-03): the home/monthly flow
 * list orders current-version entries by `statistics_at` DESC, ties broken by `occurred_at`
 * DESC, then by `transaction_id` ASC (UUIDv7 gives a deterministic total order). Postings keep
 * their stored order inside a row; the sort is row-level only.
 */
class QueryLedgerEntryRowsTest {
    private val ledgerId = LedgerId("ledger-flow-test")
    private val cny = CurrencyUnit("CNY", 2)
    private val assetId = AccountId("account-asset")

    @Test
    fun flowOrderingFollowsTheFrozenStatisticsTimeContract() {
        val rows =
            listOf(
                entryRow("tx-older-stat", statisticsAt = "2026-03-01T00:00:00Z"),
                entryRow("tx-newest-stat", statisticsAt = "2026-03-05T00:00:00Z"),
                entryRow("tx-middle-stat", statisticsAt = "2026-03-03T00:00:00Z"),
            )
        val sorted = sortLedgerEntryRowsForDisplay(rows)
        assertEquals(
            listOf("tx-newest-stat", "tx-middle-stat", "tx-older-stat"),
            sorted.map { it.transactionId.value },
        )
    }

    @Test
    fun statisticsTimeTiesBreakByOccurredTimeDescending() {
        val rows =
            listOf(
                entryRow("tx-early-occurred", statisticsAt = "2026-03-03T00:00:00Z", occurredAt = "2026-03-01T00:00:00Z"),
                entryRow("tx-late-occurred", statisticsAt = "2026-03-03T00:00:00Z", occurredAt = "2026-03-02T00:00:00Z"),
            )
        val sorted = sortLedgerEntryRowsForDisplay(rows)
        assertEquals(listOf("tx-late-occurred", "tx-early-occurred"), sorted.map { it.transactionId.value })
    }

    @Test
    fun fullTiesBreakByTransactionIdAscending() {
        val sameTimes = "2026-03-03T08:00:00Z"
        val rows =
            listOf(
                entryRow("tx-z", statisticsAt = sameTimes),
                entryRow("tx-a", statisticsAt = sameTimes),
                entryRow("tx-m", statisticsAt = sameTimes),
            )
        val sorted = sortLedgerEntryRowsForDisplay(rows)
        assertEquals(listOf("tx-a", "tx-m", "tx-z"), sorted.map { it.transactionId.value })
    }

    @Test
    fun postingsKeepTheirStoredOrderInsideARow() {
        val row =
            entryRow(
                "tx-multi-leg",
                statisticsAt = "2026-03-03T00:00:00Z",
                postings =
                    listOf(
                        Posting(PostingId("posting-2"), assetId, Money.ofMinor(-100L, cny)),
                        Posting(PostingId("posting-1"), assetId, Money.ofMinor(100L, cny)),
                    ),
            )
        val sorted = sortLedgerEntryRowsForDisplay(listOf(row, entryRow("tx-0", statisticsAt = "2026-03-01T00:00:00Z")))
        assertEquals(listOf("tx-multi-leg", "tx-0"), sorted.map { it.transactionId.value })
        assertEquals(listOf("posting-2", "posting-1"), sorted.first().postings.map { it.id.value })
    }

    @Test
    fun queryLoadsAndSortsThroughTheReadPortLedgerScoped() {
        val rows =
            listOf(
                entryRow("tx-b", statisticsAt = "2026-03-01T00:00:00Z"),
                entryRow("tx-a", statisticsAt = "2026-03-02T00:00:00Z"),
            )
        val query = QueryLedgerEntryRows(FlowEntryRowsPort(rows, ledgerId), ledgerId)
        assertEquals(listOf("tx-a", "tx-b"), query.query().map { it.transactionId.value })
        assertEquals(emptyList(), QueryLedgerEntryRows(FlowEntryRowsPort(emptyList(), ledgerId), ledgerId).query())
    }

    private fun entryRow(
        transactionId: String,
        statisticsAt: String,
        occurredAt: String = statisticsAt,
        postings: List<Posting> = listOf(Posting(PostingId("posting-$transactionId"), assetId, Money.ofMinor(-100L, cny))),
    ): LedgerEntryRow =
        LedgerEntryRow(
            transactionId = TransactionId(transactionId),
            currentVersionId = TransactionVersionId("version-$transactionId"),
            kind = TransactionKind.EXPENSE,
            occurredAt = Instant.parse(occurredAt),
            statisticsAt = Instant.parse(statisticsAt),
            note = null,
            postings = postings,
        )
}

/** Read-port fake that only serves the P7-03 entry-row surface for one ledger. */
private class FlowEntryRowsPort(
    private val rows: List<LedgerEntryRow>,
    private val expectedLedgerId: LedgerId,
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

    override fun loadLedgerEntryRows(ledgerId: LedgerId): List<LedgerEntryRow> {
        assertEquals(expectedLedgerId, ledgerId)
        return rows
    }
}
