package com.unifiedledger.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ImportCandidateDetailResult
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportDuplicateCandidateId
import com.unifiedledger.application.ImportDuplicateReviewRequest
import com.unifiedledger.application.ImportDuplicateReviewResult
import com.unifiedledger.application.ImportDuplicateReviewsForSessionResult
import com.unifiedledger.application.ImportDuplicateReviewsResult
import com.unifiedledger.application.ImportDuplicateStatus
import com.unifiedledger.application.ImportFileIntakeInput
import com.unifiedledger.application.ImportFileIntakeOutcome
import com.unifiedledger.application.ImportFormatCapabilities
import com.unifiedledger.application.ImportPlatformKind
import com.unifiedledger.application.ImportRequestIdentity
import com.unifiedledger.application.ImportReviewRowsResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * P7-04.C desktop composition-root wiring (D-146; spec sections 4.5/6.1): the facade exposes the
 * three import-review read queries, the core duplicate-review use case, its per-intent id mint and
 * the pick-result channel (DesktopLedgerViewCompositionRootTest precedent); one synthetic intake
 * through the wired import surface feeds the review read projection end-to-end (list row with the
 * session's opaque handle and `pending_confirmation`); unknown candidates fail typed (Absent, never
 * empty-masquerade, G6); the core review use case rejects an unknown duplicate candidate typed
 * with zero writes.
 */
class DesktopImportReviewCompositionRootTest {
    private fun withGraph(block: (graph: DesktopLedgerGraph) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            block(buildLedgerGraph(driver, createSchema = true))
        } finally {
            driver.close()
        }
    }

    @Test
    fun facadeExposesTheImportReviewSurface() {
        withGraph { graph ->
            assertTrue(graph.facade.queryImportReviewRows != null)
            assertTrue(graph.facade.queryImportCandidateDetail != null)
            assertTrue(graph.facade.queryImportDuplicateReviews != null)
            // P7-05: the session-level batch read behind the 整组确认页 enumeration is wired too.
            assertTrue(graph.facade.queryImportDuplicateReviewsForSession != null)
            assertTrue(graph.facade.importDuplicateReview != null)
            assertTrue(graph.facade.importPickResultChannel != null)
            assertTrue(graph.facade.importFilePickPort != null)
            assertTrue(graph.facade.importFileIntake != null)
            assertEquals(ImportPlatformKind.DESKTOP, graph.facade.importPlatformKind)
            // The pick-session factory mints one fresh opaque handle per call (R-Q09-1).
            val first = graph.facade.importIntakeSessionFactory()
            val second = graph.facade.importIntakeSessionFactory()
            assertTrue(first != null && second != null)
            assertNotEquals(first!!.inputRef, second!!.inputRef)
        }
    }

    @Test
    fun reviewIdMintProducesAFreshTriplePerIntent() {
        withGraph { graph ->
            val first = graph.facade.importDuplicateReviewIds()!!
            val second = graph.facade.importDuplicateReviewIds()!!
            val all = listOf(first.requestId.value, first.reviewId.value, first.historyId.value, second.requestId.value, second.reviewId.value, second.historyId.value)
            assertEquals(6, all.toSet().size, "every minted id must be fresh")
        }
    }

    /**
     * End-to-end wiring vector: a synthetic CMB bill (the frozen BP-01 header/data shape, all
     * values anonymous) goes through the wired intake surface and the review list read surfaces
     * the resulting candidate with the pick session's opaque handle.
     */
    @Test
    fun intakeThroughTheWiredGraphFeedsTheReviewReadSurface() {
        withGraph { graph ->
            val session = graph.facade.importIntakeSessionFactory()!!
            val intake =
                graph.facade.importFileIntake!!.intake(
                    ImportFileIntakeInput(
                        format = ImportFormatCapabilities.CMB_CSV.identifier,
                        platform = ImportPlatformKind.DESKTOP,
                        session = session,
                        bytes = syntheticCmbBill(),
                    ),
                )
            val accepted = assertIs<ImportFileIntakeOutcome.Accepted>(intake)
            assertEquals(1, accepted.newCandidateIds.size)

            val rows = assertIs<ImportReviewRowsResult.Rows>(graph.facade.queryImportReviewRows!!.query(graph.ledgerId))
            val row = rows.rows.single()
            assertEquals("pending_confirmation", row.candidateStatus)
            assertEquals("ordinary_flow", row.candidateKind)
            assertEquals(session.inputRef, row.sourceInputRef)
            assertEquals(3_580L, row.amountMinor)

            // The same candidate's detail read carries the row + history count.
            val detail =
                assertIs<ImportCandidateDetailResult.Found>(graph.facade.queryImportCandidateDetail!!.query(graph.ledgerId, row.candidateId))
            assertEquals(1L, detail.detail.statusHistoryCount)
            assertEquals(row.candidateId, detail.detail.row.candidateId)
            // A settled ordinary row has no duplicate candidate yet.
            assertEquals(
                ImportDuplicateReviewsResult.NoDuplicates,
                graph.facade.queryImportDuplicateReviews!!.query(graph.ledgerId, row.candidateId),
            )
        }
    }

    @Test
    fun unknownCandidatesFailTypedNeverAsEmptyVerdicts() {
        withGraph { graph ->
            val unknown = ImportCandidateId("candidate-does-not-exist")
            assertEquals(
                ImportCandidateDetailResult.Absent,
                graph.facade.queryImportCandidateDetail!!.query(graph.ledgerId, unknown),
            )
            assertEquals(
                ImportDuplicateReviewsResult.Absent,
                graph.facade.queryImportDuplicateReviews!!.query(graph.ledgerId, unknown),
            )
        }
    }

    /**
     * P7-05: the session-level batch read through the wired graph. A synthetic intake's session
     * whose candidates have no duplicate reads NoDuplicates (the present-session verdict); a
     * handle no candidate carries reads Absent (the absent-session verdict, G6).
     */
    @Test
    fun sessionBatchReadResolvesThePresentAndAbsentSessionVerdictsTyped() {
        withGraph { graph ->
            val session = graph.facade.importIntakeSessionFactory()!!
            val intake =
                graph.facade.importFileIntake!!.intake(
                    ImportFileIntakeInput(
                        format = ImportFormatCapabilities.CMB_CSV.identifier,
                        platform = ImportPlatformKind.DESKTOP,
                        session = session,
                        bytes = syntheticCmbBill(),
                    ),
                )
            assertIs<ImportFileIntakeOutcome.Accepted>(intake)
            assertEquals(
                ImportDuplicateReviewsForSessionResult.NoDuplicates,
                graph.facade.queryImportDuplicateReviewsForSession!!.query(graph.ledgerId, session.inputRef),
            )
            assertEquals(
                ImportDuplicateReviewsForSessionResult.Absent,
                graph.facade.queryImportDuplicateReviewsForSession!!.query(graph.ledgerId, "pick-handle-no-such-session"),
            )
        }
    }

    @Test
    fun duplicateReviewUseCaseRejectsAnUnknownCandidateTyped() {
        withGraph { graph ->
            val ids = graph.facade.importDuplicateReviewIds()!!
            val request =
                ImportDuplicateReviewRequest(
                    identity = ImportRequestIdentity(graph.ledgerId, ids.requestId),
                    candidateId = ImportDuplicateCandidateId("duplicate-does-not-exist"),
                    expectedComparisonFingerprint = "sha256:synthetic-expected",
                    decision = ImportDuplicateStatus.CONFIRMED_DUPLICATE,
                    reasonToken = "user-reviewed",
                    reviewedAt = "2026-09-14T08:00:00Z",
                    reviewerReference = "desktop-composition-root-test",
                    generatedAt = "2026-09-14T08:00:00Z",
                    reviewId = ids.reviewId,
                    historyId = ids.historyId,
                )
            val rejected = assertIs<ImportDuplicateReviewResult.Rejected>(graph.facade.importDuplicateReview!!.execute(request))
            assertEquals("SPINE_CANDIDATE_NOT_FOUND", rejected.diagnostic.code)
        }
    }

    // ------------------------------------------------------------------ synthetic CMB bill fixture

    /** The frozen BP-01 container: 6 quoted comment rows, an empty row, the byte-exact header. */
    private fun syntheticCmbBill(): ByteArray {
        val comment = (0..5).map { "\"SYN-CMB-META-$it\"" }
        val header = "\"交易日期\",\"交易时间\",\"收入\",\"支出\",\"余额\",\"交易类型\",\"交易备注\""
        // The frozen date/time shape is YYYYMMDD + HH:MM:SS (BASIC_ISO_DATE), +08:00 offset.
        val dataRow =
            listOf("\t20260315", "\t10:11:12", "", "35.80", "1000.00", "网联协议支付", "\tSYN-CMB-VEC-REMARK")
                .joinToString(",") { "\"$it\"" }
        val lines = comment + listOf("\"\"") + listOf(header) + listOf(dataRow) + listOf("\"\"")
        return ("\uFEFF" + lines.joinToString("\r\n") + "\r\n").toByteArray(Charsets.UTF_8)
    }
}
