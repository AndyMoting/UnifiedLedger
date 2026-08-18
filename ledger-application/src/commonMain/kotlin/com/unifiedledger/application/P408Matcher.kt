package com.unifiedledger.application

import kotlin.time.Instant

/** P4-08 product matcher input. Business-similarity fields are deliberately absent. */
data class P408TemporalComponents(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
)

data class P408TemporalEvidence(
    val rawText: String,
    val kind: String,
    val offsetPresent: Boolean,
    val components: P408TemporalComponents?,
    val instant: Instant?,
)

data class P408EvidenceFacts(
    val ledgerId: String,
    val evidenceId: String,
    val amountMinor: Long,
    val currencyCode: String,
    val currencyPrecision: Int,
    val direction: String,
    val accountId: String,
    val occurredAt: P408TemporalEvidence,
)

/** A real-account posting candidate exposed by the formal-ledger read port. */
data class P408PostingFacts(
    val ledgerId: String,
    val postingId: String,
    val transactionId: String,
    val transactionLedgerId: String,
    /** Signed posting amount: out = negative, in = positive. */
    val amountMinor: Long,
    val currencyCode: String,
    val currencyPrecision: Int,
    val direction: String,
    val accountId: String,
    val occurredAt: P408TemporalEvidence,
    val eligibleRealAccount: Boolean,
    val current: Boolean,
)

enum class P408MatchDisposition { PROPOSED_MATCH, UNRESOLVED, AMBIGUOUS }

enum class P408MatchConfidence { EXACT, NONE }

data class P408MatchBasis(
    val fields: Set<String>,
    val naturalDayDistance: Long,
    val windowDays: Int,
)

data class P408MatchCandidate(
    val posting: P408PostingFacts,
    val basis: P408MatchBasis,
    val confidence: P408MatchConfidence = P408MatchConfidence.EXACT,
)

data class P408MatchResult(
    val evidenceId: String,
    val disposition: P408MatchDisposition,
    val candidates: List<P408MatchCandidate>,
    val reason: String? = null,
)

/**
 * Deterministic proposal implementation of the approved O-1/O-2/O-3 contract.
 *
 * The matcher is intentionally pure. It never creates a transaction, changes a posting,
 * writes a link, or chooses among multiple candidates.
 */
class P408Matcher(
    private val windowDays: Int = DEFAULT_WINDOW_DAYS,
    private val localOffsetSeconds: Long = DEFAULT_LOCAL_OFFSET_SECONDS,
) {
    init {
        require(windowDays >= 0) { "windowDays must be non-negative" }
    }

    fun match(
        evidence: P408EvidenceFacts,
        postings: List<P408PostingFacts>,
    ): P408MatchResult {
        require(evidence.evidenceId.isNotBlank()) { "evidenceId must not be blank" }
        require(evidence.ledgerId.isNotBlank()) { "ledgerId must not be blank" }
        if (!validFundingFacts(evidence)) {
            return P408MatchResult(evidence.evidenceId, P408MatchDisposition.UNRESOLVED, emptyList(), "funding_facts_unresolved")
        }
        val fundingCandidates = postings
            .asSequence()
            .filter { posting -> posting.ledgerId == evidence.ledgerId }
            .filter { posting -> posting.transactionLedgerId == evidence.ledgerId }
            .filter { posting -> posting.postingId.isNotBlank() && posting.transactionId.isNotBlank() }
            .filter { posting -> validFundingFacts(posting) }
            .filter { posting -> posting.eligibleRealAccount && posting.current }
            .filter { posting -> sameFundingFacts(evidence, posting) }
            .toList()
        val hasUnresolvedTime = fundingCandidates.any { posting ->
            !temporalComparable(evidence.occurredAt, posting.occurredAt)
        }
        val candidates = fundingCandidates
            .asSequence()
            .mapNotNull { posting ->
                val evidenceTime = evidence.occurredAt
                val postingTime = posting.occurredAt
                if (!temporalComparable(evidenceTime, postingTime)) return@mapNotNull null
                val distance = naturalDayDistance(
                    checkNotNull(evidenceTime.instant),
                    checkNotNull(postingTime.instant),
                )
                if (distance > windowDays) return@mapNotNull null
                P408MatchCandidate(
                    posting = posting,
                    basis = P408MatchBasis(
                        fields = MATCH_FIELDS,
                        naturalDayDistance = distance,
                        windowDays = windowDays,
                    ),
                )
            }
            .sortedBy { it.posting.postingId }
            .toList()

        val disposition = when {
            candidates.size > 1 -> P408MatchDisposition.AMBIGUOUS
            hasUnresolvedTime -> P408MatchDisposition.UNRESOLVED
            candidates.size == 1 -> P408MatchDisposition.PROPOSED_MATCH
            else -> P408MatchDisposition.UNRESOLVED
        }
        val reason = when {
            hasUnresolvedTime -> "source_time_unresolved"
            candidates.isNotEmpty() -> null
            else -> "no_unique_funding_candidate"
        }
        return P408MatchResult(evidence.evidenceId, disposition, candidates, reason)
    }

    private fun sameFundingFacts(
        evidence: P408EvidenceFacts,
        posting: P408PostingFacts,
    ): Boolean = signedAmount(evidence.amountMinor, evidence.direction) == posting.amountMinor &&
        evidence.currencyCode == posting.currencyCode &&
        evidence.currencyPrecision == posting.currencyPrecision &&
        evidence.direction == posting.direction &&
        evidence.accountId == posting.accountId &&
        evidence.direction in DIRECTIONS

    private fun validFundingFacts(evidence: P408EvidenceFacts): Boolean =
        evidence.accountId.isNotBlank() && evidence.currencyCode.isNotBlank() &&
            evidence.currencyPrecision >= 0 && evidence.direction in DIRECTIONS

    private fun validFundingFacts(posting: P408PostingFacts): Boolean =
        posting.accountId.isNotBlank() && posting.currencyCode.isNotBlank() &&
            posting.currencyPrecision >= 0 && posting.direction in DIRECTIONS

    private fun signedAmount(amountMinor: Long, direction: String): Long? {
        if (amountMinor == Long.MIN_VALUE) return null
        val absolute = kotlin.math.abs(amountMinor)
        return when (direction) {
            "out" -> -absolute
            "in" -> absolute
            else -> null
        }
    }

    private fun temporalComparable(
        evidence: P408TemporalEvidence,
        posting: P408TemporalEvidence,
    ): Boolean = evidence.kind in TEMPORAL_KINDS &&
        posting.kind == evidence.kind &&
        temporalKindMatchesToken(evidence) &&
        temporalKindMatchesToken(posting) &&
        temporalShape(evidence.rawText) == temporalShape(posting.rawText) &&
        evidence.components != null &&
        posting.components != null &&
        componentsMatchRawText(evidence) &&
        componentsMatchRawText(posting) &&
        evidence.offsetPresent && posting.offsetPresent &&
        evidence.instant != null && posting.instant != null &&
        parsedInstantMatches(evidence) && parsedInstantMatches(posting)

    private fun temporalKindMatchesToken(temporal: P408TemporalEvidence): Boolean {
        val hasExplicitOffset = temporal.rawText.endsWith('Z') ||
            (temporal.rawText.length >= 6 &&
                temporal.rawText[temporal.rawText.length - 6] in setOf('+', '-') &&
                temporal.rawText[temporal.rawText.length - 3] == ':')
        return when (temporal.kind) {
            "offset_datetime" -> temporal.offsetPresent && hasExplicitOffset
            "local_datetime" -> !temporal.offsetPresent && !hasExplicitOffset
            else -> false
        }
    }

    private fun temporalShape(rawText: String): String = buildString(rawText.length) {
        rawText.forEach { character ->
            append(if (character in '0'..'9') '#' else character)
        }
    }

    private fun componentsMatchRawText(temporal: P408TemporalEvidence): Boolean {
        val components = temporal.components ?: return false
        val raw = temporal.rawText
        if (raw.length < 19 || raw[4] != '-' || raw[7] != '-' || raw[10] != 'T' ||
            raw[13] != ':' || raw[16] != ':'
        ) return false
        return component(raw, 0, 4) == components.year &&
            component(raw, 5, 7) == components.month &&
            component(raw, 8, 10) == components.day &&
            component(raw, 11, 13) == components.hour &&
            component(raw, 14, 16) == components.minute &&
            component(raw, 17, 19) == components.second
    }

    private fun component(raw: String, start: Int, end: Int): Int? {
        val value = raw.substring(start, end)
        return if (value.all { it in '0'..'9' }) value.toInt() else null
    }

    private fun parsedInstantMatches(temporal: P408TemporalEvidence): Boolean =
        runCatching { Instant.parse(temporal.rawText) == temporal.instant }.getOrDefault(false)

    private fun naturalDayDistance(left: Instant, right: Instant): Long {
        val leftDay = floorDivEpochSeconds(left.epochSeconds + localOffsetSeconds)
        val rightDay = floorDivEpochSeconds(right.epochSeconds + localOffsetSeconds)
        return kotlin.math.abs(leftDay - rightDay)
    }

    private fun floorDivEpochSeconds(value: Long): Long {
        val quotient = value / SECONDS_PER_DAY
        return if (value % SECONDS_PER_DAY < 0) quotient - 1 else quotient
    }

    companion object {
        const val DEFAULT_WINDOW_DAYS: Int = 2
        const val DEFAULT_LOCAL_OFFSET_SECONDS: Long = 8 * 60 * 60

        val MATCH_FIELDS: Set<String> = setOf(
            "amount",
            "currency",
            "direction",
            "account",
            "occurred_at_window",
        )

        private val DIRECTIONS = setOf("in", "out")
        private val TEMPORAL_KINDS = setOf("offset_datetime", "local_datetime")

        private const val SECONDS_PER_DAY: Long = 24 * 60 * 60
    }
}
