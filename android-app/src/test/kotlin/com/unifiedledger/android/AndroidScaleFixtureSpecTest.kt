package com.unifiedledger.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D-216: the manifest-derived spec must reproduce the maximum chain's exact
 * historical oracle literals (no loosening) and also cover `local-small`.
 */
class AndroidScaleFixtureSpecTest {
    private val maximum =
        AndroidScaleFixtureSpec(
            profile = "maximum",
            seed = 197198L,
            initialSessions = 5,
            rowsPerSession = 10000,
            uniqueRows = 1000,
            mainSessionRows = 10000,
            initialCandidates = 51000,
            finalCandidates = 61000,
            initialDuplicateRelations = 100000,
            finalDuplicateRelations = 150000,
            newSessionDuplicateRelations = 50000,
            initiallyConfirmedRelations = 100,
        )

    private val localSmall =
        AndroidScaleFixtureSpec(
            profile = "local-small",
            seed = 197198L,
            initialSessions = 5,
            rowsPerSession = 20,
            uniqueRows = 5,
            mainSessionRows = 20,
            initialCandidates = 105,
            finalCandidates = 125,
            initialDuplicateRelations = 200,
            finalDuplicateRelations = 300,
            newSessionDuplicateRelations = 100,
            initiallyConfirmedRelations = 4,
        )

    @Test
    fun maximumReproducesEveryHistoricalLiteralExactly() {
        // The assertions the chain used to hard-code, now derived from the spec.
        assertEquals(6, maximum.stateSessions)
        assertEquals(10000, maximum.sessionRows(0))
        assertEquals(10000, maximum.sessionRows(4))
        assertEquals(1000, maximum.sessionRows(5))
        assertEquals(0, maximum.sessionAmountOffset(0))
        assertEquals(10000, maximum.sessionAmountOffset(5))
        assertEquals(10000, maximum.mainSessionRows)
        assertEquals(51000, maximum.candidates(final = false))
        assertEquals(61000, maximum.candidates(final = true))
        assertEquals(100000, maximum.duplicateRelations(final = false))
        assertEquals(150000, maximum.duplicateRelations(final = true))
        assertEquals(50000, maximum.newSessionDuplicateRelations)
        assertEquals(100, maximum.initiallyConfirmedRelations)
        assertEquals(5, maximum.multiplicity(final = false))
        assertEquals(6, maximum.multiplicity(final = true))
        assertEquals(11000, maximum.distinctAmounts)
        assertEquals(10, maximum.pairCount(final = false))
        assertEquals(15, maximum.pairCount(final = true))
        // import_duplicate_status_history laws: one creation row per relation
        // plus one status_transition per disposition.
        assertEquals(100100L, maximum.duplicateHistoryAfterPrepare)
        assertEquals(200100L, maximum.duplicateHistoryAfterGroup)
        assertEquals(100L, maximum.duplicateReviewReceiptAfterPrepare)
        assertEquals(50100L, maximum.duplicateReviewReceiptAfterGroup)
        assertEquals("session-06.csv", maximum.mainSessionFileName)
        assertTrue(maximum.isValid())
    }

    @Test
    fun localSmallCoversEveryStageWithNonTrivialInput() {
        assertEquals(105, localSmall.candidates(final = false))
        assertEquals(125, localSmall.candidates(final = true))
        assertEquals(200, localSmall.duplicateRelations(final = false))
        assertEquals(300, localSmall.duplicateRelations(final = true))
        // group disposition (new-session relations) and batch confirmation (1)
        assertTrue(localSmall.newSessionDuplicateRelations > 0)
        assertTrue(localSmall.initiallyConfirmedRelations > 0)
        assertEquals(204L, localSmall.duplicateHistoryAfterPrepare)
        assertEquals(404L, localSmall.duplicateHistoryAfterGroup)
        assertEquals(104L, localSmall.duplicateReviewReceiptAfterGroup)
        assertEquals(25, localSmall.distinctAmounts)
        assertEquals("session-06.csv", localSmall.mainSessionFileName)
        assertTrue(localSmall.isValid())
    }

    @Test
    fun generatorConsistencyRejectsARelabelledOrTamperedManifest() {
        assertFalse(maximum.copy(profile = "parser-small").isValid())
        assertFalse(maximum.copy(finalCandidates = 61001).isValid())
        assertFalse(maximum.copy(initialCandidates = 51001).isValid())
        assertFalse(maximum.copy(finalDuplicateRelations = 150001).isValid())
        assertFalse(maximum.copy(initialSessions = 0).isValid())
        assertFalse(maximum.copy(rowsPerSession = 0).isValid())
        assertFalse(maximum.copy(uniqueRows = 0).isValid())
    }
}
