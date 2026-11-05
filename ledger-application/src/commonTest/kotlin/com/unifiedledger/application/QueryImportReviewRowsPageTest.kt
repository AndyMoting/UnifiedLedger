package com.unifiedledger.application

import com.unifiedledger.domain.LedgerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A-PERF (D-293 step 2): the paged review use case's typed mapping.
 *
 * The paged read exists so the host can render the first page instead of materializing 61k rows.
 * Its failure mapping is the part that must not be silently wrong: an unimplemented or throwing
 * port has to surface as [ImportReviewRowsPageResult.Unavailable], never as an EMPTY page — an
 * empty page means "exhausted" to the host, which would silently truncate the review list in
 * exactly the situation where the read broke.
 */
class QueryImportReviewRowsPageTest {
    private val ledgerId = LedgerId("ledger-paged-review")

    private class FakePort(
        private val page: (String?) -> ImportReviewRowsPage,
    ) : ImportReviewReadPort {
        val cursors = mutableListOf<String?>()

        override fun loadImportReviewPage(
            ledgerId: LedgerId,
            pageSize: Long,
            afterCandidateId: String?,
        ): ImportReviewRowsPage {
            cursors += afterCandidateId
            return page(afterCandidateId)
        }
    }

    @Test
    fun aPageLandsWithItsCursor() {
        val row = row("candidate-1")
        val port = FakePort { ImportReviewRowsPage(listOf(row), "candidate-1") }

        val result = QueryImportReviewRows(port).queryPage(ledgerId, pageSize = 50, afterCandidateId = null)

        val page =
            assertIs<ImportReviewRowsPageResult.Page>(result)
        assertEquals(listOf(row), page.rows)
        assertEquals("candidate-1", page.nextAfterCandidateId)
        assertEquals(listOf<String?>(null), port.cursors, "the host's cursor must reach the port unchanged")
    }

    @Test
    fun anExhaustedPageCarriesNoCursor() {
        val port = FakePort { ImportReviewRowsPage(listOf(row("candidate-9")), null) }

        val page =
            assertIs<ImportReviewRowsPageResult.Page>(
                QueryImportReviewRows(port).queryPage(ledgerId, pageSize = 50, afterCandidateId = "candidate-8"),
            )

        assertNull(page.nextAfterCandidateId, "null cursor is the host's exhaust signal")
        assertEquals(listOf<String?>("candidate-8"), port.cursors)
    }

    @Test
    fun aThrowingPortIsUnavailableRatherThanAnEmptyPage() {
        val port =
            FakePort { throw UnsupportedOperationException("the port cannot page") }

        val result = QueryImportReviewRows(port).queryPage(ledgerId, pageSize = 50, afterCandidateId = null)

        assertIs<ImportReviewRowsPageResult.Unavailable>(result)
    }

    @Test
    fun theUnimplementedDefaultIsUnavailableToo() {
        // The port's own default throws; the use case must map that to Unavailable as well, so a
        // composition that never wired paging cannot clear the list.
        val port =
            object : ImportReviewReadPort {
                override fun loadImportReviewRows(ledgerId: LedgerId) = emptyList<ImportReviewRow>()
            }

        assertIs<ImportReviewRowsPageResult.Unavailable>(
            QueryImportReviewRows(port).queryPage(ledgerId, pageSize = 50, afterCandidateId = null),
        )
    }

    private fun row(candidateId: String): ImportReviewRow =
        ImportReviewRow(
            candidateId = ImportCandidateId(candidateId),
            candidateKind = "ordinary_flow",
            sourceInputRef = "ref-$candidateId",
            amountMinor = 100L,
            currencyCode = "CNY",
            currencyPrecision = 2,
            occurredAt = "2026-11-01T10:00:00+08:00",
            directionToken = "OUTFLOW",
            statusToken = "PENDING",
            fundingState = ImportFundingState.SETTLED,
            completeness = ImportCompleteness.VALID_COMPLETE,
            contentHash = "hash-$candidateId",
            candidateStatus = "pending_confirmation",
            requiresConfirmation = true,
            confidence = "1.0",
            duplicateStatus = ImportDuplicateStatus.DEFERRED,
            paymentProfileVariant = null,
            paymentProfileAssetLegKindToken = null,
            paymentProfileCreditLegKindToken = null,
        )
}
