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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * DEF-B (D-268): the HOME flow page use case over the read port's default page implementation.
 * The page key is the cursor order, the accumulated pages are the caller's to re-sort into the
 * frozen display order, and every failure is typed (never an empty page).
 */
class QueryLedgerFlowPageTest {
    private val ledgerId = LedgerId("ledger-flow-page-test")
    private val cny = CurrencyUnit("CNY", 2)
    private val expenseId = AccountId("account-expense-flow")
    private val assetId = AccountId("account-asset-flow")

    @Test
    fun firstPageAndCursorsWalkTheWholeLedgerWithoutOverlap() {
        val useCase = QueryLedgerFlowPage(FlowPort(rows = fiveRows()), ledgerId)

        val walked = mutableListOf<String>()
        var cursor: LedgerCurrentStateReadPort.LedgerFlowCursor? = null
        var pages = 0
        do {
            val page = assertIs<LedgerFlowPageResult.Success>(useCase.firstPageOr(cursor, pageSize = 2)).page
            walked += page.rows.map { it.transactionId.value }
            pages += 1
            cursor = page.nextCursor
        } while (cursor != null)

        assertEquals(3, pages)
        assertEquals(listOf("tx-05", "tx-04", "tx-03", "tx-02", "tx-01"), walked)
    }

    private fun QueryLedgerFlowPage.firstPageOr(
        cursor: LedgerCurrentStateReadPort.LedgerFlowCursor?,
        pageSize: Int,
    ): LedgerFlowPageResult = if (cursor == null) firstPage(pageSize) else pageAfter(cursor, pageSize)

    @Test
    fun theTotalIsTheExactCountAndItsFailureIsUnavailable() {
        val ok = QueryLedgerFlowPage(FlowPort(rows = fiveRows()), ledgerId)
        assertEquals(5, assertIs<LedgerFlowTotalResult.Success>(ok.total()).total)

        val failing = QueryLedgerFlowPage(FlowPort(rows = fiveRows(), throwOnCurrentRows = true), ledgerId)
        assertIs<LedgerFlowTotalResult.Unavailable>(failing.total())
    }

    @Test
    fun aReadFailureIsUnavailableNeverAnEmptyPage() {
        val failing = QueryLedgerFlowPage(FlowPort(rows = fiveRows(), throwOnEntryRows = true), ledgerId)
        assertIs<LedgerFlowPageResult.Unavailable>(failing.firstPage())
    }

    @Test
    fun aNonPositivePageSizeIsAProgrammingError() {
        val useCase = QueryLedgerFlowPage(FlowPort(rows = fiveRows()), ledgerId)
        assertFailsWith<IllegalArgumentException> { useCase.firstPage(pageSize = 0) }
    }

    @Test
    fun aPageLargerThanTheLedgerReturnsEverythingWithoutACursor() {
        val page =
            assertIs<LedgerFlowPageResult.Success>(
                QueryLedgerFlowPage(FlowPort(rows = fiveRows()), ledgerId).firstPage(pageSize = 50),
            ).page
        assertEquals(5, page.rows.size)
        assertNull(page.nextCursor)
    }

    private fun fiveRows(): List<LedgerEntryRow> =
        listOf(
            flowRow("tx-01", "2026-03-01T02:00:00Z"),
            flowRow("tx-02", "2026-03-02T02:00:00Z"),
            flowRow("tx-03", "2026-03-03T02:00:00Z"),
            flowRow("tx-04", "2026-03-03T02:00:00Z"),
            flowRow("tx-05", "2026-03-03T02:00:00Z"),
        )

    private fun flowRow(
        transactionId: String,
        statisticsAt: String,
    ): LedgerEntryRow =
        LedgerEntryRow(
            transactionId = TransactionId(transactionId),
            currentVersionId = TransactionVersionId("version-$transactionId"),
            kind = TransactionKind.EXPENSE,
            occurredAt = Instant.parse(statisticsAt),
            statisticsAt = Instant.parse(statisticsAt),
            note = null,
            postings =
                listOf(
                    Posting(PostingId("posting-$transactionId"), expenseId, Money.ofMinor(100L, cny)),
                ),
        )
}

/** Read-port fake: entry rows for the page read, current rows for the count default. */
private class FlowPort(
    private val rows: List<LedgerEntryRow>,
    private val throwOnEntryRows: Boolean = false,
    private val throwOnCurrentRows: Boolean = false,
) : LedgerCurrentStateReadPort {
    override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> =
        if (throwOnCurrentRows) {
            throw IllegalStateException("current rows unavailable")
        } else {
            rows.map { row ->
                CurrentVersionRow(
                    transactionId = row.transactionId,
                    currentVersionId = row.currentVersionId,
                    kind = row.kind,
                    occurredAt = row.occurredAt,
                    postings = row.postings,
                )
            }
        }

    override fun loadLedgerEntryRows(ledgerId: LedgerId): List<LedgerEntryRow> {
        if (throwOnEntryRows) throw IllegalStateException("entry rows unavailable")
        return rows
    }

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
}
