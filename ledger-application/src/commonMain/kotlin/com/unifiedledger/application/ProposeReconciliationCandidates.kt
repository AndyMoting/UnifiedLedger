package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionKind
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * P8-01a i2 candidate proposal (spec sections 2.2/2.4; D-308 items 2/3/5).
 *
 * One use case, two entries over the SAME frozen [P408Matcher]: the account entry ([proposeForAccountLeg])
 * starts from a queue leg, the evidence entry ([proposeForEvidence]) starts from one import
 * candidate's evidence. Neither entry re-implements matching, reorders the five-field identity or
 * scores anything: [P408MatchDisposition], the candidate list, each [P408MatchBasis] and the
 * reason string come out of [P408Matcher.match] verbatim, and `confidence` stays the matcher's
 * single `EXACT` value (spec section 2.4 forbids implying a ranking).
 *
 * WHAT THIS USE CASE MAY NOT SEE. The matcher only accepts [P408EvidenceFacts], whose funding
 * identity is the frozen five-field tuple: four of those fields (amount, currency, direction,
 * account) come from a `READY` [P408EvidenceProjection] (D-112), while the fifth
 * (`occurred_at_window`) is decided by the matcher from the raw source text. Evidence without a
 * `READY` projection therefore has no matcher facts: the read face surfaces it together with its
 * projection state so the caller can offer the bind step (spec section 2.4), and this use case
 * omits it from [P801CandidateProposalResult.Success.proposals] rather than fabricating identity
 * facts, a reason or a disposition for it.
 *
 * ZERO WRITES: nothing here creates, changes or re-links an evidence, projection, reconciliation or
 * accounting row.
 */
class ProposeReconciliationCandidates(
    private val readPort: P801AccountReconciliationReadPort,
    private val matcher: P408Matcher = P408Matcher(),
) {
    /**
     * Entry A (spec section 2.2, account view): the evidence of [leg]'s account window, each with
     * the matcher's own disposition.
     *
     * The matcher is given the WHOLE window's legs, not only [leg], so an ambiguity with a second
     * same-shape leg of the same account is reported as `AMBIGUOUS` instead of being hidden; a
     * result whose candidate list mentions other legs but never [leg] is dropped, and an
     * `UNRESOLVED` result (no candidate, or an unusable source time) is kept because the spec's
     * three causes must be presented for the leg being reconciled (spec section 2.4 / V-6).
     *
     * WRITE-SIDE ELIGIBILITY (spec sections 2.1/2.4; D-308 item 3): the confirm write path accepts
     * `ACCOUNT_TRANSFER` legs only, so a non-transfer anchor is refused BEFORE either window read
     * with [P801CandidateProposalFailure.LEG_NOT_TRANSFER_CONFIRMABLE] — the state-accurate
     * 「暂不支持在应用内核对」 answer, never [P801CandidateProposalFailure.LEG_NOT_IN_QUEUE]. Such a
     * leg still appears in the i1 queue (its status is presented; only the action is unavailable).
     */
    fun proposeForAccountLeg(
        ledgerId: LedgerId,
        leg: P801AccountReconciliationQueueRow,
    ): P801CandidateProposalResult {
        if (leg.transactionKind != TransactionKind.ACCOUNT_TRANSFER) {
            return P801CandidateProposalResult.Failed(P801CandidateProposalFailure.LEG_NOT_TRANSFER_CONFIRMABLE)
        }
        val evidenceWindow = windowAround(leg.occurredAt)
        val postingWindow = candidateSpanAround(leg.occurredAt)
        val postings =
            when (val loaded = readPort.loadAccountPostingFacts(ledgerId, leg.accountId, postingWindow.from, postingWindow.to)) {
                is P801AccountPostingFactsResult.Success -> loaded.facts
                is P801AccountPostingFactsResult.Failed -> return P801CandidateProposalResult.Failed(P801CandidateProposalFailure.POSTING_FACTS_UNAVAILABLE)
            }
        // A leg outside the i1 queue membership cannot produce candidates (spec section 2.4); the
        // window read is its superset, so an absent anchor is a structural mismatch, not "no match".
        if (postings.none { posting -> posting.postingId == leg.postingId.value }) {
            return P801CandidateProposalResult.Failed(P801CandidateProposalFailure.LEG_NOT_IN_QUEUE)
        }
        val evidence =
            when (val loaded = readPort.loadCandidateEvidence(ledgerId, leg.accountId, evidenceWindow.from, evidenceWindow.to)) {
                is P801CandidateEvidenceResult.Success -> loaded.rows
                is P801CandidateEvidenceResult.Failed -> return P801CandidateProposalResult.Failed(P801CandidateProposalFailure.CANDIDATE_EVIDENCE_UNAVAILABLE)
            }
        return P801CandidateProposalResult.Success(
            evidence
                .mapNotNull { row ->
                    matchableFacts(ledgerId, row)?.let { facts -> P801CandidateProposal(row, matcher.match(facts, postings)) }
                }.filter { proposal ->
                    proposal.match.candidates.isEmpty() ||
                        proposal.match.candidates.any { candidate -> candidate.posting.postingId == leg.postingId.value }
                }.sortedWith(PRESENTATION_ORDER),
        )
    }

    /**
     * Entry A bind step (spec section 2.4; D-308 item 10): the evidence of [leg]'s account window
     * that still needs the user's explicit "this import row belongs to this account" decision — no
     * `READY` projection and no active evidence link — in the frozen presentation order.
     *
     * It reuses the SAME candidate-evidence read as [proposeForAccountLeg] (no second SQL, no
     * account widening) and the same window, so the bind list and the proposal list can never
     * disagree about which evidence belongs to the leg's window. A row already projected to another
     * account is excluded by that read (projection content is immutable, spec section 2.4); a
     * linked-but-unprojected row is excluded here because it may not be bound again. ZERO WRITES:
     * the bind use case is the only writer, and only on the user's explicit action.
     */
    fun bindableEvidenceForAccountLeg(
        ledgerId: LedgerId,
        leg: P801AccountReconciliationQueueRow,
    ): P801BindableEvidenceResult {
        val window = windowAround(leg.occurredAt)
        val evidence =
            when (val loaded = readPort.loadCandidateEvidence(ledgerId, leg.accountId, window.from, window.to)) {
                is P801CandidateEvidenceResult.Success -> loaded.rows
                is P801CandidateEvidenceResult.Failed ->
                    return P801BindableEvidenceResult.Failed(P801CandidateProposalFailure.CANDIDATE_EVIDENCE_UNAVAILABLE)
            }
        return P801BindableEvidenceResult.Success(
            evidence
                .filter { row -> row.projection?.state != P408ProjectionState.READY && !row.hasActiveEvidenceLink }
                .sortedWith(EVIDENCE_PRESENTATION_ORDER),
        )
    }

    /**
     * Entry B (spec section 2.2, candidate view): one evidence against the legs of its own projected
     * account in the window its source time defines. The account is the projection's target — the
     * only account the evidence has been declared to belong to — so an evidence that is not yet
     * projected is refused rather than matched against some guessed account.
     */
    fun proposeForEvidence(
        ledgerId: LedgerId,
        evidenceId: String,
    ): P801CandidateProposalResult {
        val row =
            when (val loaded = readPort.loadCandidateEvidenceById(ledgerId, evidenceId)) {
                is P801CandidateEvidenceResult.Success ->
                    loaded.rows.firstOrNull() ?: return P801CandidateProposalResult.Failed(P801CandidateProposalFailure.EVIDENCE_NOT_FOUND)
                is P801CandidateEvidenceResult.Failed -> return P801CandidateProposalResult.Failed(P801CandidateProposalFailure.CANDIDATE_EVIDENCE_UNAVAILABLE)
            }
        val projection = row.projection ?: return P801CandidateProposalResult.Failed(P801CandidateProposalFailure.EVIDENCE_NOT_PROJECTED)
        if (projection.state != P408ProjectionState.READY) {
            return P801CandidateProposalResult.Failed(P801CandidateProposalFailure.EVIDENCE_PROJECTION_REJECTED)
        }
        val sourceInstant = row.occurredAt.instant ?: return P801CandidateProposalResult.Failed(P801CandidateProposalFailure.SOURCE_TIME_UNRESOLVED)
        val facts = matchableFacts(ledgerId, row) ?: return P801CandidateProposalResult.Failed(P801CandidateProposalFailure.EVIDENCE_FACTS_UNRESOLVED)
        val window = windowAround(sourceInstant)
        val postings =
            when (val loaded = readPort.loadAccountPostingFacts(ledgerId, AccountId(projection.targetAccountId), window.from, window.to)) {
                is P801AccountPostingFactsResult.Success -> loaded.facts
                is P801AccountPostingFactsResult.Failed -> return P801CandidateProposalResult.Failed(P801CandidateProposalFailure.POSTING_FACTS_UNAVAILABLE)
            }
        return P801CandidateProposalResult.Success(listOf(P801CandidateProposal(row, matcher.match(facts, postings))))
    }

    /**
     * The matcher input for one evidence row, or `null` when the frozen contract cannot be
     * satisfied honestly: a non-`READY` projection (identity facts may only come from a `READY`
     * row, D-112) or a source row whose raw facts are absent (D-111 refuses to invent them).
     */
    private fun matchableFacts(
        ledgerId: LedgerId,
        row: P801ReconciliationCandidateEvidence,
    ): P408EvidenceFacts? {
        val projection = row.projection ?: return null
        if (projection.state != P408ProjectionState.READY) return null
        return P408EvidenceFacts(
            ledgerId = ledgerId.value,
            evidenceId = row.evidenceId,
            raw =
                P408RawEvidenceFacts(
                    sourceId = row.sourceId,
                    contentHash = row.contentHash,
                    amountMinor = row.rawAmountMinor ?: return null,
                    currencyCode = row.rawCurrencyCode ?: return null,
                    currencyPrecision = row.rawCurrencyPrecision ?: return null,
                    directionToken = row.rawDirectionToken ?: return null,
                ),
            normalized =
                P408NormalizedProjectionFacts(
                    projectionId = projection.projectionId,
                    targetAccountId = projection.targetAccountId,
                    currencyCode = projection.currencyCode,
                    currencyPrecision = projection.currencyPrecision,
                    normalizedAmountMinor = projection.normalizedAmountMinor,
                    directionToken = projection.directionToken,
                    ruleId = projection.ruleId,
                    ruleVersion = projection.ruleVersion,
                ),
            occurredAt = row.occurredAt,
        )
    }

    /**
     * The EVIDENCE window handed to SQL for an anchor instant: the matcher window widened by one
     * local day on each side, so an evidence whose source time is at most
     * [P408Matcher.DEFAULT_WINDOW_DAYS] natural days from the anchor is never filtered out before
     * the matcher sees it (a `±2` natural-day span is less than `±3` times 24 hours in instants,
     * which is the only slack a prefilter may take). The bound only ever narrows what the matcher
     * then decides; it is never the matching rule itself.
     */
    private fun windowAround(instant: Instant): Window {
        val slack = (P408Matcher.DEFAULT_WINDOW_DAYS + 1).days
        return Window(from = instant - slack, to = instant + slack)
    }

    /**
     * The POSTING read window for an account-entry anchor. Entry A does not anchor on the evidence:
     * the evidence may sit up to [P408Matcher.DEFAULT_WINDOW_DAYS] + 1 days from the leg, and a
     * matcher candidate of that evidence may sit another [P408Matcher.DEFAULT_WINDOW_DAYS] natural
     * days away — a `≤2` natural-day distance spans less than 3 days of instants — so a true
     * candidate can be up to `2 * (DEFAULT_WINDOW_DAYS + 1)` days from the leg. Reading only
     * `±(DEFAULT_WINDOW_DAYS + 1)` there would drop such a posting and turn a genuine `AMBIGUOUS`
     * into a confirmable `PROPOSED_MATCH` (spec section 2.4 / V-6). Entry B keeps [windowAround],
     * because it anchors on the evidence instant itself.
     */
    private fun candidateSpanAround(instant: Instant): Window {
        val slack = (2 * (P408Matcher.DEFAULT_WINDOW_DAYS + 1)).days
        return Window(from = instant - slack, to = instant + slack)
    }

    private data class Window(
        val from: Instant,
        val to: Instant,
    )

    companion object {
        /**
         * The frozen presentation order (spec section 2.4): parsed source instant ascending,
         * rows whose source time does not parse last, and `evidence_id` ascending as the
         * tie-break. Raw text is never the sort key — an offset-bearing text sorts differently
         * from the instant it denotes. ONE comparator backs both the proposal list and the bind
         * list, so the two cannot disagree about order.
         */
        private val EVIDENCE_PRESENTATION_ORDER: Comparator<P801ReconciliationCandidateEvidence> =
            Comparator { left, right ->
                val leftInstant = left.occurredAt.instant
                val rightInstant = right.occurredAt.instant
                when {
                    leftInstant != null && rightInstant != null -> {
                        val byInstant = leftInstant.compareTo(rightInstant)
                        if (byInstant != 0) byInstant else left.evidenceId.compareTo(right.evidenceId)
                    }
                    leftInstant != null -> -1
                    rightInstant != null -> 1
                    else -> left.evidenceId.compareTo(right.evidenceId)
                }
            }

        /** The same frozen order over the proposal wrapper. */
        private val PRESENTATION_ORDER: Comparator<P801CandidateProposal> =
            Comparator { left, right -> EVIDENCE_PRESENTATION_ORDER.compare(left.evidence, right.evidence) }
    }
}

/** One evidence row with the frozen matcher's own result for it. */
data class P801CandidateProposal(
    val evidence: P801ReconciliationCandidateEvidence,
    val match: P408MatchResult,
)

/** The result of one proposal read; nothing here is written. */
sealed interface P801CandidateProposalResult {
    data class Success(
        val proposals: List<P801CandidateProposal>,
    ) : P801CandidateProposalResult

    data class Failed(
        val reason: P801CandidateProposalFailure,
    ) : P801CandidateProposalResult
}

/** Why a proposal read produced no dispositions at all (each is a typed, renderable cause). */
enum class P801CandidateProposalFailure {
    /** The candidate-evidence read failed; never reported as "no evidence" (D-089). */
    CANDIDATE_EVIDENCE_UNAVAILABLE,

    /** The posting-facts read failed; never reported as "no leg" (D-089). */
    POSTING_FACTS_UNAVAILABLE,

    /** The anchor posting is not a current effective leg of that account. */
    LEG_NOT_IN_QUEUE,

    /**
     * The anchor leg's transaction is not an `ACCOUNT_TRANSFER`, so the confirm write path cannot
     * accept it (spec section 2.1 / D-308 item 3): 暂不支持在应用内核对. Refused before any read.
     */
    LEG_NOT_TRANSFER_CONFIRMABLE,

    /** Entry B: no such evidence in this ledger. */
    EVIDENCE_NOT_FOUND,

    /** Entry B: the evidence has no current projection; it must be bound first. */
    EVIDENCE_NOT_PROJECTED,

    /** Entry B: the current projection is `REJECTED`; its code is on the read row. */
    EVIDENCE_PROJECTION_REJECTED,

    /** Entry B: a `READY` projection whose source raw facts are absent (D-111). */
    EVIDENCE_FACTS_UNRESOLVED,

    /** Entry B: the source time does not parse, so the evidence defines no window. */
    SOURCE_TIME_UNRESOLVED,
}

/**
 * The entry A bind list (spec section 2.4): an empty [Success] row list is a genuine "no evidence
 * is waiting to be bound for this account/window" (rendered with its own copy, never as "no import
 * rows in this window"), and a [Failed] read is never presented as that empty answer (D-089).
 */
sealed interface P801BindableEvidenceResult {
    data class Success(
        val rows: List<P801ReconciliationCandidateEvidence>,
    ) : P801BindableEvidenceResult

    data class Failed(
        val reason: P801CandidateProposalFailure,
    ) : P801BindableEvidenceResult
}
