package com.unifiedledger.application

import com.unifiedledger.domain.LedgerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * P7-05 enumeration performance batch: the session-level duplicate-review use case
 * ([QueryImportDuplicateReviewsForSession]) over [ImportReviewReadPort], the batch counterpart
 * of [QueryImportDuplicateReviews]. Pins the frozen result family's four semantics: Reviews
 * (the whole pick session's rows, each carrying its subject candidate), Absent (the session
 * has no candidate row), NoDuplicates (a present session with no duplicate candidate), and
 * Unavailable (a read failure never degrades to an empty verdict, F1/G6). All fixtures are
 * fully synthetic (D06).
 */
class ImportDuplicateReviewsForSessionJvmTest {
    private val ledgerId = LedgerId("ledger-p705")

    private fun sessionRow(
        subjectCandidateId: String,
        duplicateCandidateId: String,
    ): ImportDuplicateReviewsForSessionRow =
        ImportDuplicateReviewsForSessionRow(
            subjectCandidateId = ImportCandidateId(subjectCandidateId),
            review =
                ImportDuplicateReviewRow(
                    duplicateCandidateId = ImportDuplicateCandidateId(duplicateCandidateId),
                    kind = "EXACT_BUSINESS_TUPLE",
                    comparisonFingerprint = "sha256:fixed-fingerprint",
                    comparisonSnapshot = "{\"amount_minor\":3580}",
                    latestStatus = ImportDuplicateStatus.DEFERRED,
                    reviewDecision = null,
                    reviewReasonToken = null,
                    reviewedAt = null,
                    possibleExistingSource = null,
                ),
        )

    @Test
    fun sessionReviewsCarryEveryRowOfTheSessionWithItsSubjectCandidate() {
        val reviews =
            listOf(
                sessionRow(subjectCandidateId = "candidate-a", duplicateCandidateId = "dup-a-1"),
                sessionRow(subjectCandidateId = "candidate-a", duplicateCandidateId = "dup-a-2"),
                sessionRow(subjectCandidateId = "candidate-b", duplicateCandidateId = "dup-b-1"),
            )
        val readPort =
            object : ImportReviewReadPort {
                override fun loadImportDuplicateReviewsForSession(
                    ledgerId: LedgerId,
                    sessionInputRef: String,
                ): List<ImportDuplicateReviewsForSessionRow>? {
                    assertEquals(this@ImportDuplicateReviewsForSessionJvmTest.ledgerId, ledgerId)
                    assertEquals("pick-handle-1", sessionInputRef)
                    return reviews
                }
            }
        val result = QueryImportDuplicateReviewsForSession(readPort).query(ledgerId, "pick-handle-1")
        val ready = assertIs<ImportDuplicateReviewsForSessionResult.Reviews>(result)
        assertEquals(reviews, ready.reviews)
        assertEquals(
            listOf("candidate-a", "candidate-a", "candidate-b"),
            ready.reviews.map { it.subjectCandidateId.value },
        )
    }

    @Test
    fun anAbsentSessionIsTheExplicitAbsentVerdict() {
        val readPort =
            object : ImportReviewReadPort {
                override fun loadImportDuplicateReviewsForSession(
                    ledgerId: LedgerId,
                    sessionInputRef: String,
                ): List<ImportDuplicateReviewsForSessionRow>? = null
            }
        assertEquals(
            ImportDuplicateReviewsForSessionResult.Absent,
            QueryImportDuplicateReviewsForSession(readPort).query(ledgerId, "pick-handle-missing"),
        )
    }

    @Test
    fun aPresentSessionWithoutDuplicatesIsTheExplicitNoDuplicatesVerdict() {
        val readPort =
            object : ImportReviewReadPort {
                override fun loadImportDuplicateReviewsForSession(
                    ledgerId: LedgerId,
                    sessionInputRef: String,
                ): List<ImportDuplicateReviewsForSessionRow>? = emptyList()
            }
        assertEquals(
            ImportDuplicateReviewsForSessionResult.NoDuplicates,
            QueryImportDuplicateReviewsForSession(readPort).query(ledgerId, "pick-handle-1"),
        )
    }

    @Test
    fun aFailingSessionReadSurfacesTypedUnavailableNeverAnEmptyVerdict() {
        val readPort =
            object : ImportReviewReadPort {
                override fun loadImportDuplicateReviewsForSession(
                    ledgerId: LedgerId,
                    sessionInputRef: String,
                ): List<ImportDuplicateReviewsForSessionRow>? = error("synthetic session read failure")
            }
        assertEquals(
            ImportDuplicateReviewsForSessionResult.Unavailable,
            QueryImportDuplicateReviewsForSession(readPort).query(ledgerId, "pick-handle-1"),
        )
    }

    @Test
    fun theFailLoudDefaultPortNeverPresentsAnEmptyVerdict() {
        // G6: the unimplemented port default must surface as a typed read failure, never as a
        // definitive empty/absent verdict.
        assertEquals(
            ImportDuplicateReviewsForSessionResult.Unavailable,
            QueryImportDuplicateReviewsForSession(object : ImportReviewReadPort {}).query(ledgerId, "pick-handle-1"),
        )
    }
}
