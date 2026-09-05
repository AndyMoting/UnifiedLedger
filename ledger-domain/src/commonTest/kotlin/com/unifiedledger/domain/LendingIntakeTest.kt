package com.unifiedledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class LendingIntakeTest {
    private val currency = CurrencyUnit("CNY", 2)
    private val pendingAt = Instant.parse("2026-01-01T00:00:00Z")
    private val confirmedAt = Instant.parse("2026-01-02T00:00:00Z")

    @Test
    fun confirmationRequiresAUniqueNonBlankLaterHistoryEntry() {
        val candidate = assertIs<DomainResult.Success<LendingCandidate>>(createCandidate()).value
        val gates = LendingConfirmationGateField.ALL.toSet()

        assertEquals(
            LendingViolation.HistoryMustBeAppendOnly,
            assertIs<DomainResult.Failure>(confirmLendingCandidate(candidate, gates, "", confirmedAt, 1)).violation,
        )
        assertEquals(
            LendingViolation.HistoryMustBeAppendOnly,
            assertIs<DomainResult.Failure>(confirmLendingCandidate(candidate, gates, "pending-history", confirmedAt, 1)).violation,
        )
        assertEquals(
            LendingViolation.HistoryMustBeAppendOnly,
            assertIs<DomainResult.Failure>(confirmLendingCandidate(candidate, gates, "confirmed-history", pendingAt, 1)).violation,
        )
        assertEquals(
            LendingViolation.HistoryMustBeAppendOnly,
            assertIs<DomainResult.Failure>(confirmLendingCandidate(candidate, gates, "confirmed-history", Instant.parse("2025-12-31T00:00:00Z"), 1)).violation,
        )
        assertEquals(createCandidate(), DomainResult.Success(candidate))
    }

    @Test
    fun confirmationAppendsOneHistoryAndPreservesThePendingCandidate() {
        val candidate = assertIs<DomainResult.Success<LendingCandidate>>(createCandidate()).value
        val confirmed =
            assertIs<DomainResult.Success<LendingCandidate>>(
                confirmLendingCandidate(candidate, LendingConfirmationGateField.ALL.toSet(), "confirmed-history", confirmedAt, 1),
            ).value
        assertEquals(LendingCandidateStatus.CONFIRMED, confirmed.status)
        assertEquals(emptyList(), confirmed.requiresConfirmation)
        assertEquals(
            candidate.statusHistory + LendingCandidateStatusHistoryEntry("confirmed-history", LendingCandidateStatus.CONFIRMED, confirmedAt, 1),
            confirmed.statusHistory,
        )
        assertEquals(createCandidate(), DomainResult.Success(candidate))
    }

    private fun createCandidate(): DomainResult<LendingCandidate> =
        createLendingCandidate(
            id = "candidate-1",
            type = "collection",
            proposedTotalReceivedMinor = 100,
            currency = currency,
            sourceIds = listOf("source-1"),
            ruleVersion = 1,
            confidence = "high",
            statusHistory =
                listOf(
                    LendingCandidateStatusHistoryEntry(
                        id = "pending-history",
                        status = LendingCandidateStatus.PENDING_CONFIRMATION,
                        occurredAt = pendingAt,
                        formalEffectCount = 0,
                    ),
                ),
        )
}
