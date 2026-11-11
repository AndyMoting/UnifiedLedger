package com.unifiedledger.data

import com.unifiedledger.application.P408EvidenceProjection
import com.unifiedledger.application.P408EvidenceProjectionPort
import com.unifiedledger.application.P408EvidenceResponsibility
import com.unifiedledger.application.P408PostingFacts
import com.unifiedledger.application.P408ReconciliationStatus
import com.unifiedledger.application.P801AccountPostingFactsResult
import com.unifiedledger.application.P801AccountReconciliationCursor
import com.unifiedledger.application.P801AccountReconciliationPageResult
import com.unifiedledger.application.P801AccountReconciliationQueuePage
import com.unifiedledger.application.P801AccountReconciliationQueueRow
import com.unifiedledger.application.P801AccountReconciliationReadFailure
import com.unifiedledger.application.P801AccountReconciliationReadPort
import com.unifiedledger.application.P801AccountReconciliationStatusTotal
import com.unifiedledger.application.P801AccountReconciliationSummaryResult
import com.unifiedledger.application.P801CandidateEvidenceResult
import com.unifiedledger.application.P801ReconciledEvidenceSource
import com.unifiedledger.application.P801ReconciledLegAudit
import com.unifiedledger.application.P801ReconciledLegAuditResult
import com.unifiedledger.application.P801ReconciledLegDecision
import com.unifiedledger.application.P801ReconciledLegEvidenceLink
import com.unifiedledger.application.P801ReconciledLegLinkHistoryEntry
import com.unifiedledger.application.P801ReconciledLegReconciliation
import com.unifiedledger.application.P801ReconciledLegStatusEntry
import com.unifiedledger.application.P801ReconciliationCandidateEvidence
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import kotlin.time.Instant

/**
 * P8-01a account reconciliation read adapter (D-308 items 6/8; spec section 2.3).
 *
 * Implements [P801AccountReconciliationReadPort] against the single [LedgerDatabase]. Five
 * read-only named queries: the queue page (`p801AccountReconciliationLegPage`), its per-(currency,
 * status) totals (`p801AccountReconciliationStatusTotals`), the two candidate-evidence reads
 * (`p801ReconciliationCandidateEvidence` / `p801ReconciliationCandidateEvidenceById`) and the leg
 * facts (`p801AccountReconciliationPostingFacts`). The page and the totals share one table group,
 * one current-version join, the single effective-predicate definition point
 * (`transaction_effective_state`) and the section 2.1 composite mark over `catalog_account`, so
 * they cannot disagree about which legs exist and no second state rule is introduced. Zero writes:
 * no reconciliation, evidence, posting, balance or report row is created or changed here.
 *
 * FAIL-CLOSED (spec section 2.3, V-9; D-089). A database failure, a persisted value that does not
 * map to its domain type (a kind or status outside its enum, an unparseable `occurred_at`), or an
 * integer minor-unit sum that overflows SQLite's INTEGER `sum()` all surface as
 * [P801AccountReconciliationReadFailure.Unavailable]. None of them degrades to an empty page, an
 * empty totals list or an amount of zero — an absent group is absent, a corrupt row is never
 * silently dropped, and a real zero is only ever the sum of real rows.
 *
 * The keyset order is the frozen `occurred_at DESC, posting_id ASC`, applied to the epoch-nanosecond
 * projection the query computes inline from `occurred_at` (no DDL, no added column). `occurred_at`
 * is TEXT in mixed shapes, so text order is not time order; the query applies the frozen
 * `statistics_at` parser to the row text AND to the cursor text the next page hands back, so a page
 * boundary is an instant boundary and the cursor stays exact. An unparseable text projects to the
 * epoch sentinel, keeping the order total (see the query comment).
 *
 * P8-01a i2 adds the two matcher inputs to the same port rather than a second read surface: the
 * candidate evidence around a leg (`p801ReconciliationCandidateEvidence` /
 * `p801ReconciliationCandidateEvidenceById`) and the leg facts themselves
 * (`p801AccountReconciliationPostingFacts`). Raw import facts stay raw there (D-111); the
 * projection row is read through the existing authority port (D-112) in ONE batch per candidate
 * read, and the leg-facts query only offers `ACCOUNT_TRANSFER` legs because the confirm write path
 * accepts nothing else (spec section 2.1), so neither read normalizes anything on its own.
 *
 * REGISTERED RESIDUAL (D-308 item 9 scope): the queue ignores an rg03
 * `reconciliation_eligible = 0` override, so such a leg would render 待对账 while the detail face
 * derives it ineligible and renders `null`. Unreachable today: the schema ties that value to the
 * fee leg, which posts to an EXPENSE account and so fails the section 2.1 composite mark too.
 * The i2 candidate reads and the posting-facts read ignore rg03 as well — they are scoped by the
 * projection / the account mark and never read the rg03 row; a future eligibility unification
 * (P8-01b) must cover all five reads, not only the queue.
 */
class SqlDelightP801AccountReconciliationReadAdapter(
    private val database: LedgerDatabase,
    private val projections: P408EvidenceProjectionPort,
) : P801AccountReconciliationReadPort {
    override fun loadAccountReconciliationPage(
        ledgerId: LedgerId,
        accountId: AccountId,
        after: P801AccountReconciliationCursor?,
        pageSize: Int,
    ): P801AccountReconciliationPageResult {
        require(pageSize > 0) { "page size must be positive" }
        return try {
            // One extra row tells the caller whether another page exists without a second query.
            val rawRows =
                database.ledgerQueries
                    .p801AccountReconciliationLegPage(
                        ledger_id = ledgerId.value,
                        account_id = accountId.value,
                        cursor_occurred_at = after?.occurredAtStorageValue,
                        cursor_posting_id = after?.postingId?.value ?: "",
                        page_size = pageSize.toLong() + 1L,
                    ).executeAsList()
            val pageRows = rawRows.take(pageSize)
            val nextCursor =
                if (rawRows.size > pageSize) {
                    pageRows.lastOrNull()?.let { row ->
                        P801AccountReconciliationCursor(
                            occurredAtStorageValue = row.occurred_at,
                            postingId = PostingId(row.posting_id),
                        )
                    }
                } else {
                    null
                }
            P801AccountReconciliationPageResult.Success(
                P801AccountReconciliationQueuePage(
                    rows =
                        pageRows.map { row ->
                            P801AccountReconciliationQueueRow(
                                postingId = PostingId(row.posting_id),
                                transactionId = TransactionId(row.transaction_id),
                                occurredAt = Instant.parse(row.occurred_at),
                                note = row.note,
                                accountId = AccountId(row.account_id),
                                amountMinor = row.amount_minor,
                                currency = CurrencyUnit(row.currency_code, row.currency_precision.toInt()),
                                status = P408ReconciliationStatus.fromStorage(row.status),
                                hasActiveEvidenceLink = row.active_link_count > 0L,
                                transactionKind = TransactionKind.valueOf(row.kind),
                            )
                        },
                    nextCursor = nextCursor,
                ),
            )
        } catch (failure: Exception) {
            failedPage()
        }
    }

    override fun loadAccountReconciliationSummary(
        ledgerId: LedgerId,
        accountId: AccountId,
    ): P801AccountReconciliationSummaryResult =
        try {
            val totals =
                database.ledgerQueries
                    .p801AccountReconciliationStatusTotals(
                        ledger_id = ledgerId.value,
                        account_id = accountId.value,
                    ).executeAsList()
                    .map { row ->
                        P801AccountReconciliationStatusTotal(
                            currency = CurrencyUnit(row.currency_code, row.currency_precision.toInt()),
                            status = P408ReconciliationStatus.fromStorage(row.status),
                            // sum() is typed nullable because an empty input has no value; a GROUP BY
                            // row always has at least one leg, so a null here is a read integrity
                            // failure, never a zero (D-089).
                            totalMinorUnits = checkNotNull(row.total_minor) { "reconciliation status total without a sum" },
                            rowCount = row.row_count.toInt(),
                        )
                    }
            P801AccountReconciliationSummaryResult.Success(totals)
        } catch (failure: Exception) {
            failedSummary()
        }

    private fun failedPage(): P801AccountReconciliationPageResult = P801AccountReconciliationPageResult.Failed(P801AccountReconciliationReadFailure.Unavailable)

    private fun failedSummary(): P801AccountReconciliationSummaryResult = P801AccountReconciliationSummaryResult.Failed(P801AccountReconciliationReadFailure.Unavailable)

    override fun loadCandidateEvidence(
        ledgerId: LedgerId,
        accountId: AccountId,
        occurredFrom: Instant,
        occurredTo: Instant,
    ): P801CandidateEvidenceResult =
        try {
            val rows =
                database.ledgerQueries
                    .p801ReconciliationCandidateEvidence(
                        ledger_id = ledgerId.value,
                        account_id = accountId.value,
                        window_from_epoch_seconds = occurredFrom.epochSeconds,
                        window_to_epoch_seconds = occurredTo.epochSeconds,
                    ).executeAsList()
            // ONE authority round-trip for the whole batch, never one per evidence row.
            val currentProjections = projections.readProjections(ledgerId.value, rows.map { row -> row.evidence_id })
            P801CandidateEvidenceResult.Success(
                rows.map { row ->
                    candidateEvidence(
                        evidenceId = row.evidence_id,
                        sourceId = row.source_id,
                        candidateId = row.candidate_id,
                        contentHash = row.content_hash,
                        rawAmountMinor = row.amount_minor,
                        rawCurrencyCode = row.currency_code,
                        rawCurrencyPrecision = row.currency_precision?.toInt(),
                        rawDirectionToken = row.direction_token,
                        occurredAt = row.occurred_at,
                        activeLinkCount = row.active_link_count,
                        projection = currentProjections[row.evidence_id],
                    )
                },
            )
        } catch (failure: Exception) {
            P801CandidateEvidenceResult.Failed(P801AccountReconciliationReadFailure.Unavailable)
        }

    override fun loadCandidateEvidenceById(
        ledgerId: LedgerId,
        evidenceId: String,
    ): P801CandidateEvidenceResult =
        try {
            val rows =
                database.ledgerQueries
                    .p801ReconciliationCandidateEvidenceById(ledger_id = ledgerId.value, evidence_id = evidenceId)
                    .executeAsList()
            val currentProjections = projections.readProjections(ledgerId.value, rows.map { row -> row.evidence_id })
            P801CandidateEvidenceResult.Success(
                rows.map { row ->
                    candidateEvidence(
                        evidenceId = row.evidence_id,
                        sourceId = row.source_id,
                        candidateId = row.candidate_id,
                        contentHash = row.content_hash,
                        rawAmountMinor = row.amount_minor,
                        rawCurrencyCode = row.currency_code,
                        rawCurrencyPrecision = row.currency_precision?.toInt(),
                        rawDirectionToken = row.direction_token,
                        occurredAt = row.occurred_at,
                        activeLinkCount = row.active_link_count,
                        projection = currentProjections[row.evidence_id],
                    )
                },
            )
        } catch (failure: Exception) {
            P801CandidateEvidenceResult.Failed(P801AccountReconciliationReadFailure.Unavailable)
        }

    override fun loadAccountPostingFacts(
        ledgerId: LedgerId,
        accountId: AccountId,
        occurredFrom: Instant,
        occurredTo: Instant,
    ): P801AccountPostingFactsResult =
        try {
            P801AccountPostingFactsResult.Success(
                database.ledgerQueries
                    .p801AccountReconciliationPostingFacts(
                        ledger_id = ledgerId.value,
                        account_id = accountId.value,
                        window_from_epoch_seconds = occurredFrom.epochSeconds,
                        window_to_epoch_seconds = occurredTo.epochSeconds,
                    ).executeAsList()
                    .map { row ->
                        P408PostingFacts(
                            ledgerId = row.ledger_id,
                            postingId = row.posting_id,
                            transactionId = row.transaction_id,
                            transactionLedgerId = row.transaction_ledger_id,
                            amountMinor = row.amount_minor,
                            currencyCode = row.currency_code,
                            currencyPrecision = row.currency_precision.toInt(),
                            // The posting table stores only the sign, so the direction token is
                            // derived from it. A zero leg has no direction at all and gets an empty
                            // token: the matcher's own valid-facts filter drops it, instead of this
                            // adapter inventing an "in" or an "out".
                            direction =
                                when {
                                    row.amount_minor < 0L -> "out"
                                    row.amount_minor > 0L -> "in"
                                    else -> ""
                                },
                            accountId = row.account_id,
                            occurredAt = P408Computation.temporalEvidence(row.occurred_at),
                            eligibleRealAccount = row.eligible_real_account > 0L,
                            current = row.is_current > 0L,
                        )
                    },
            )
        } catch (failure: Exception) {
            P801AccountPostingFactsResult.Failed(P801AccountReconciliationReadFailure.Unavailable)
        }

    /**
     * P8-01a i3a audit read-back (spec section 2.6; D-308 item 7). Three read-only named queries:
     * the leg's links with their frozen decision basis and source labels
     * (`p801ReconciledLegEvidenceAudit`), each link's own state history
     * (`p801ReconciledLegLinkHistory`) and the leg's reconciliation status history
     * (`p801ReconciledLegReconciliationHistory`).
     *
     * NOT-FOUND vs FAILED: the leg's existence is decided by whether `posting_id` names a leg on
     * this ledger at all — read through the same queue-membership-independent posting scope the
     * other reads use. A leg that exists but carries neither a link nor a reconciliation row is a
     * SUCCESSFUL audit with an empty link list and a null reconciliation: that is the spec's typed
     * absence (an unchecked leg), not a missing leg and never a zero-filled one (D-089). A leg whose
     * reconciliation row exists but whose history is empty still answers that row with an empty
     * history (the LEFT JOIN), so the audit never reports "no reconciliation record" for a row it
     * could actually read.
     */
    override fun loadReconciledLegAudit(
        ledgerId: LedgerId,
        postingId: PostingId,
    ): P801ReconciledLegAuditResult =
        try {
            val leg =
                database.ledgerQueries
                    .p801ReconciledLegIdentity(ledger_id = ledgerId.value, posting_id = postingId.value)
                    .executeAsOneOrNull()
            if (leg == null) {
                P801ReconciledLegAuditResult.NotFound
            } else {
                val linkRows =
                    database.ledgerQueries
                        .p801ReconciledLegEvidenceAudit(ledger_id = ledgerId.value, posting_id = postingId.value)
                        .executeAsList()
                val linkHistoryRows =
                    database.ledgerQueries
                        .p801ReconciledLegLinkHistory(ledger_id = ledgerId.value, posting_id = postingId.value)
                        .executeAsList()
                val historyByLink = linkHistoryRows.groupBy { row -> row.link_id }
                val reconciliationRows =
                    database.ledgerQueries
                        .p801ReconciledLegReconciliationHistory(ledger_id = ledgerId.value, posting_id = postingId.value)
                        .executeAsList()
                P801ReconciledLegAuditResult.Success(
                    P801ReconciledLegAudit(
                        postingId = postingId,
                        transactionId = TransactionId(leg.transaction_id),
                        reconciliation =
                            reconciliationRows.firstOrNull()?.let { first ->
                                P801ReconciledLegReconciliation(
                                    reconciliationId = first.reconciliation_id,
                                    status = P408ReconciliationStatus.fromStorage(first.current_status),
                                    // The history join is a LEFT JOIN, so a reconciliation row with no
                                    // history still answers (with an EMPTY history list); a NULL
                                    // sequence is that absent side, never a fabricated entry.
                                    history =
                                        reconciliationRows.mapNotNull { row ->
                                            row.sequence?.let { sequence ->
                                                P801ReconciledLegStatusEntry(
                                                    sequence = sequence,
                                                    status = P408ReconciliationStatus.fromStorage(checkNotNull(row.status)),
                                                    evidenceLinkId = row.evidence_link_id,
                                                    occurredAt = checkNotNull(row.occurred_at),
                                                )
                                            }
                                        },
                                )
                            },
                        evidence =
                            linkRows
                                .map { row ->
                                    P801ReconciledLegEvidenceLink(
                                        linkId = row.link_id,
                                        evidenceId = row.evidence_id,
                                        candidateId = row.candidate_id,
                                        requestId = row.request_id,
                                        responsibility = P408EvidenceResponsibility.fromStorage(row.responsibility),
                                        createdAt = row.created_at,
                                        state = row.link_state,
                                        stateSequence = row.link_state_sequence,
                                        // A snapshot-less link (a hand-written or pre-snapshot row) keeps
                                        // a null basis: no window, distance or decision is invented.
                                        decision =
                                            if (row.match_basis == null) {
                                                null
                                            } else {
                                                P801ReconciledLegDecision(
                                                    basisVersion = checkNotNull(row.basis_version).toInt(),
                                                    matchBasis = row.match_basis.split(","),
                                                    windowDays = checkNotNull(row.window_days).toInt(),
                                                    naturalDayDistance = checkNotNull(row.natural_day_distance).toInt(),
                                                    humanDecision = checkNotNull(row.human_decision),
                                                    decidedAt = checkNotNull(row.confirmed_at),
                                                )
                                            },
                                        source =
                                            P801ReconciledEvidenceSource(
                                                evidenceId = row.evidence_id,
                                                sourceId = row.source_id,
                                                importInputRef = row.input_ref,
                                                recordOrdinal = row.record_ordinal,
                                                recordKind = row.record_kind,
                                                occurredAt = row.source_occurred_at,
                                            ),
                                        history =
                                            historyByLink[row.link_id].orEmpty().map { entry ->
                                                P801ReconciledLegLinkHistoryEntry(
                                                    sequence = entry.sequence,
                                                    state = entry.state,
                                                    reason = entry.reason,
                                                    occurredAt = entry.occurred_at,
                                                )
                                            },
                                    )
                                }.sortedBy { link -> link.linkId },
                    ),
                )
            }
        } catch (failure: Exception) {
            P801ReconciledLegAuditResult.Failed(P801AccountReconciliationReadFailure.Unavailable)
        }

    /**
     * One candidate-evidence row. The projection is read through the existing authority reader
     * ([projections]) rather than joined into the evidence query, so this adapter cannot grow a
     * second copy of the CURRENT-row predicate or of the projection mapping. The caller reads the
     * whole batch in one [P408EvidenceProjectionPort.readProjections] call, so a proposal read is
     * one authority round-trip, not one per row; the number of ROWS is whatever the evidence query
     * returned and is not window-bounded, because unparseable/offset-free evidence rows are
     * deliberately kept by that query (they surface as `source_time_unresolved`).
     */
    private fun candidateEvidence(
        evidenceId: String,
        sourceId: String,
        candidateId: String?,
        contentHash: String,
        rawAmountMinor: Long?,
        rawCurrencyCode: String?,
        rawCurrencyPrecision: Int?,
        rawDirectionToken: String?,
        occurredAt: String?,
        activeLinkCount: Long,
        projection: P408EvidenceProjection?,
    ): P801ReconciliationCandidateEvidence =
        P801ReconciliationCandidateEvidence(
            evidenceId = evidenceId,
            sourceId = sourceId,
            candidateId = candidateId,
            contentHash = contentHash,
            rawAmountMinor = rawAmountMinor,
            rawCurrencyCode = rawCurrencyCode,
            rawCurrencyPrecision = rawCurrencyPrecision,
            rawDirectionToken = rawDirectionToken,
            occurredAt = P408Computation.temporalEvidence(occurredAt),
            projection = projection,
            hasActiveEvidenceLink = activeLinkCount > 0L,
        )
}
