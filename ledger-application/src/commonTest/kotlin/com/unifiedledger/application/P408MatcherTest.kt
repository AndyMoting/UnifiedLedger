package com.unifiedledger.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class P408MatcherTest {
    private val matcher = P408Matcher()

    @Test
    fun uniqueFundingMatchWithinTwoNaturalDaysIsAccepted() {
        val evidence = evidence("e-1", "2026-08-10T23:30:00+08:00")
        val posting = posting("p-1", "2026-08-12T00:10:00+08:00")

        val result = matcher.match(evidence, listOf(posting))

        assertEquals(P408MatchDisposition.PROPOSED_MATCH, result.disposition)
        assertEquals(listOf("p-1"), result.candidates.map { it.posting.postingId })
        assertEquals(2L, result.candidates.single().basis.naturalDayDistance)
        assertEquals(P408MatchConfidence.EXACT, result.candidates.single().confidence)
        assertEquals(
            setOf("amount", "currency", "direction", "account", "occurred_at_window"),
            result.candidates.single().basis.fields,
        )
    }

    @Test
    fun candidateOutsideWindowIsNotMatched() {
        val result = matcher.match(
            evidence("e-2", "2026-08-10T12:00:00+08:00"),
            listOf(posting("p-2", "2026-08-13T12:00:00+08:00")),
        )

        assertEquals(P408MatchDisposition.UNRESOLVED, result.disposition)
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun sameAmountDifferentFundingFactDoesNotMatch() {
        val result = matcher.match(
            evidence("e-3", "2026-08-10T12:00:00+08:00"),
            listOf(posting("p-3", "2026-08-10T12:00:00+08:00", accountId = "other-account")),
        )

        assertEquals(P408MatchDisposition.UNRESOLVED, result.disposition)
    }

    @Test
    fun multipleCandidatesDeferWithoutChoosingOne() {
        val evidence = evidence("e-4", "2026-08-10T12:00:00+08:00")
        val result = matcher.match(
            evidence,
            listOf(
                posting("p-2", "2026-08-11T12:00:00+08:00"),
                posting("p-1", "2026-08-10T12:30:00+08:00"),
            ),
        )

        assertEquals(P408MatchDisposition.AMBIGUOUS, result.disposition)
        assertEquals(listOf("p-1", "p-2"), result.candidates.map { it.posting.postingId })
        assertEquals("ambiguous_multiple_candidates", result.reason)
    }

    @Test
    fun naturalDayUsesConfiguredLocalOffsetAcrossUtcMidnight() {
        val result = matcher.match(
            evidence("e-5", "2026-08-10T23:30:00+08:00"),
            listOf(posting("p-5", "2026-08-11T00:15:00+08:00")),
        )

        assertEquals(P408MatchDisposition.PROPOSED_MATCH, result.disposition)
        assertEquals(1L, result.candidates.single().basis.naturalDayDistance)
    }

    @Test
    fun missingSourceOffsetRemainsUnresolved() {
        val source = P408EvidenceFacts(
            "ledger", "e-6", 1_000, "CNY", 2, "out", "wallet",
            P408TemporalEvidence("2026-08-10 12:00:00", "local_datetime", false, null, null),
        )
        val result = matcher.match(source, listOf(posting("p-6", "2026-08-10T12:00:00+08:00")))

        assertEquals(P408MatchDisposition.UNRESOLVED, result.disposition)
        assertEquals("source_time_unresolved", result.reason)
    }

    @Test
    fun unresolvedCompetingPostingPreventsUniqueProposal() {
        val comparable = posting("p-comparable", "2026-08-10T12:00:00+08:00")
        val unresolved = posting("p-unresolved", "2026-08-10T12:00:00+08:00").copy(
            occurredAt = P408TemporalEvidence(
                "2026-08-10 12:00:00",
                "local_datetime",
                false,
                null,
                null,
            ),
        )

        val result = matcher.match(
            evidence("e-7", "2026-08-10T12:00:00+08:00"),
            listOf(comparable, unresolved),
        )

        assertEquals(P408MatchDisposition.UNRESOLVED, result.disposition)
        assertEquals(listOf("p-comparable"), result.candidates.map { it.posting.postingId })
        assertEquals("source_time_unresolved", result.reason)
    }

    @Test
    fun onlyCurrentEligiblePostingOwnedByLedgerCanMatch() {
        val base = posting("p-valid", "2026-08-10T12:00:00+08:00")
        val result = matcher.match(
            evidence("e-8", "2026-08-10T12:00:00+08:00"),
            listOf(
                base.copy(postingId = "p-other-ledger", ledgerId = "other-ledger"),
                base.copy(postingId = "p-other-transaction-ledger", transactionLedgerId = "other-ledger"),
                base.copy(postingId = "p-ineligible", eligibleRealAccount = false),
                base.copy(postingId = "p-superseded", current = false),
                base,
            ),
        )

        assertEquals(P408MatchDisposition.PROPOSED_MATCH, result.disposition)
        assertEquals(listOf("p-valid"), result.candidates.map { it.posting.postingId })
    }

    @Test
    fun signedPostingAmountMustAgreeWithDirection() {
        val result = matcher.match(
            evidence("e-9", "2026-08-10T12:00:00+08:00"),
            listOf(posting("p-wrong-sign", "2026-08-10T12:00:00+08:00").copy(amountMinor = 1_000)),
        )

        assertEquals(P408MatchDisposition.UNRESOLVED, result.disposition)
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun sameInstantWithDifferentSourceTokenRemainsUnresolved() {
        val result = matcher.match(
            evidence("e-10", "2026-08-10T12:00:00+08:00"),
            listOf(posting("p-token", "2026-08-10T04:00:00Z")),
        )

        assertEquals(P408MatchDisposition.UNRESOLVED, result.disposition)
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun unsupportedTemporalKindFailsClosed() {
        val result = matcher.match(
            evidence("e-11", "2026-08-10T12:00:00+08:00"),
            listOf(
                posting("p-kind", "2026-08-10T12:00:00+08:00").copy(
                    occurredAt = temporal("2026-08-10T12:00:00+08:00").copy(kind = "garbage"),
                ),
            ),
        )

        assertEquals(P408MatchDisposition.UNRESOLVED, result.disposition)
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun componentsMustAgreeWithRawSourceToken() {
        val result = matcher.match(
            evidence("e-12", "2026-08-10T12:00:00+08:00"),
            listOf(
                posting("p-components", "2026-08-10T12:00:00+08:00").copy(
                    occurredAt = temporal("2026-08-10T12:00:00+08:00").copy(
                        components = P408TemporalComponents(2026, 8, 11, 12, 0, 0),
                    ),
                ),
            ),
        )

        assertEquals(P408MatchDisposition.UNRESOLVED, result.disposition)
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun zeroDayWindowRequiresTheSameNaturalDay() {
        val zeroDayMatcher = P408Matcher(windowDays = 0)
        val result = zeroDayMatcher.match(
            evidence("e-13", "2026-08-10T23:30:00+08:00"),
            listOf(posting("p-zero", "2026-08-11T00:15:00+08:00")),
        )

        assertEquals(P408MatchDisposition.UNRESOLVED, result.disposition)
    }

    @Test
    fun incompleteFundingFactsFailClosed() {
        val result = matcher.match(
            evidence("e-14", "2026-08-10T12:00:00+08:00").copy(accountId = "", currencyPrecision = -1),
            listOf(posting("p-incomplete", "2026-08-10T12:00:00+08:00")),
        )

        assertEquals(P408MatchDisposition.UNRESOLVED, result.disposition)
        assertEquals("funding_facts_unresolved", result.reason)
    }

    @Test
    fun negativeSourceAmountFailsClosed() {
        val result = matcher.match(
            evidence("e-16", "2026-08-10T12:00:00+08:00").copy(amountMinor = -1),
            listOf(posting("p-negative", "2026-08-10T12:00:00+08:00")),
        )

        assertEquals(P408MatchDisposition.UNRESOLVED, result.disposition)
        assertEquals("funding_facts_unresolved", result.reason)
    }

    @Test
    fun temporalKindMustAgreeWithOffsetToken() {
        val mislabeled = temporal("2026-08-10T12:00:00+08:00").copy(kind = "local_datetime")
        val result = matcher.match(
            evidence("e-15", "2026-08-10T12:00:00+08:00").copy(occurredAt = mislabeled),
            listOf(posting("p-kind-token", "2026-08-10T12:00:00+08:00").copy(occurredAt = mislabeled)),
        )

        assertEquals(P408MatchDisposition.UNRESOLVED, result.disposition)
        assertTrue(result.candidates.isEmpty())
    }

    private fun evidence(id: String, occurredAt: String): P408EvidenceFacts =
        P408EvidenceFacts("ledger", id, 1_000, "CNY", 2, "out", "wallet", temporal(occurredAt))

    private fun posting(
        id: String,
        occurredAt: String,
        accountId: String = "wallet",
    ): P408PostingFacts = P408PostingFacts(
        ledgerId = "ledger",
        postingId = id,
        transactionId = "tx-$id",
        transactionLedgerId = "ledger",
        amountMinor = -1_000,
        currencyCode = "CNY",
        currencyPrecision = 2,
        direction = "out",
        accountId = accountId,
        occurredAt = temporal(occurredAt),
        eligibleRealAccount = true,
        current = true,
    )

    private fun temporal(text: String): P408TemporalEvidence {
        val instant = Instant.parse(text)
        val date = text.substring(0, 10).split('-').map(String::toInt)
        val time = text.substring(11, 19).split(':').map(String::toInt)
        return P408TemporalEvidence(
            text,
            "offset_datetime",
            true,
            P408TemporalComponents(date[0], date[1], date[2], time[0], time[1], time[2]),
            instant,
        )
    }
}
