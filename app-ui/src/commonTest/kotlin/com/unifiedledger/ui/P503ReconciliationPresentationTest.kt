package com.unifiedledger.ui

import com.unifiedledger.application.ImportReviewRow
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.P408EvidenceProjection
import com.unifiedledger.application.P408EvidenceResponsibility
import com.unifiedledger.application.P408MatchBasis
import com.unifiedledger.application.P408MatchCandidate
import com.unifiedledger.application.P408MatchDisposition
import com.unifiedledger.application.P408MatchResult
import com.unifiedledger.application.P408PostingFacts
import com.unifiedledger.application.P408ProjectionState
import com.unifiedledger.application.P408ReconciliationStatus
import com.unifiedledger.application.P408TemporalEvidence
import com.unifiedledger.application.P801AccountReconciliationCursor
import com.unifiedledger.application.P801AccountReconciliationQueueRow
import com.unifiedledger.application.P801AccountReconciliationStatusTotal
import com.unifiedledger.application.P801BindEvidenceRefusal
import com.unifiedledger.application.P801BindableEvidenceResult
import com.unifiedledger.application.P801CandidateProposal
import com.unifiedledger.application.P801CandidateProposalFailure
import com.unifiedledger.application.P801CandidateProposalResult
import com.unifiedledger.application.P801ConfirmLinkRefusal
import com.unifiedledger.application.P801ReconciledEvidenceSource
import com.unifiedledger.application.P801ReconciledLegAudit
import com.unifiedledger.application.P801ReconciledLegAuditResult
import com.unifiedledger.application.P801ReconciledLegDecision
import com.unifiedledger.application.P801ReconciledLegEvidenceLink
import com.unifiedledger.application.P801ReconciledLegLinkHistoryEntry
import com.unifiedledger.application.P801ReconciledLegReconciliation
import com.unifiedledger.application.P801ReconciledLegStatusEntry
import com.unifiedledger.application.P801ReconciliationCandidateEvidence
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P8-01a i3a reconciliation presentation tests (D-308; spec sections 2.1/2.3/2.4/2.5/2.6/2.7).
 *
 * Pins the frozen copy without a Compose harness (CONTRIBUTING:207): the five-state labels and the
 * `null` eligibility fallback (V-7), the three matcher causes quoted verbatim, every rejection code
 * keeping its raw token, the 已核对 / 未核对 / 条数 summary (and the absence of the rejected
 * 余额−明细合计 phrasing), the two independent failure copies, the bind irreversibility warning, the
 * non-confirmable-leg sentence, and the spec section 2.6 audit lines.
 */
class P503ReconciliationPresentationTest {
    private val cny = CurrencyUnit("CNY", 2)
    private val accountId = AccountId("asset-bank")
    private val occurredAt = Instant.parse("2026-03-15T02:00:00Z")
    private val temporal =
        P408TemporalEvidence(
            rawText = "2026-03-15T02:00:00Z",
            kind = "offset_datetime",
            offsetPresent = true,
            components = com.unifiedledger.application.P408TemporalComponents(2026, 3, 15, 2, 0, 0),
            instant = occurredAt,
        )

    private fun total(
        status: P408ReconciliationStatus,
        totalMinorUnits: Long,
        rowCount: Int = 1,
        currency: CurrencyUnit = cny,
    ): P801AccountReconciliationStatusTotal = P801AccountReconciliationStatusTotal(currency, status, totalMinorUnits, rowCount)

    private fun candidate(
        direction: String = "out",
        eligible: Boolean = true,
        postingId: String = "posting-1",
    ): P408MatchCandidate =
        P408MatchCandidate(
            posting =
                P408PostingFacts(
                    ledgerId = "ledger-1",
                    postingId = postingId,
                    transactionId = "tx-1",
                    transactionLedgerId = "ledger-1",
                    amountMinor = if (direction == "out") -10_000L else 10_000L,
                    currencyCode = "CNY",
                    currencyPrecision = 2,
                    direction = direction,
                    accountId = accountId.value,
                    occurredAt = temporal,
                    eligibleRealAccount = eligible,
                    current = true,
                ),
            basis = P408MatchBasis(setOf("amount", "currency", "direction", "account", "occurred_at_window"), 2L, 2),
        )

    private fun link(
        responsibility: P408EvidenceResponsibility = P408EvidenceResponsibility.REAL_ACCOUNT_POSTING,
        state: String = "active",
    ): P801ReconciledLegEvidenceLink =
        P801ReconciledLegEvidenceLink(
            linkId = "link-1",
            evidenceId = "evidence-1",
            candidateId = "candidate-1",
            requestId = "request-1",
            responsibility = responsibility,
            createdAt = "2026-03-16T00:00:00Z",
            state = state,
            stateSequence = 1L,
            decision =
                P801ReconciledLegDecision(
                    basisVersion = 2,
                    matchBasis = listOf("account", "amount", "currency", "direction", "occurred_at_window"),
                    windowDays = 2,
                    naturalDayDistance = 1,
                    humanDecision = "confirm_match",
                    decidedAt = "2026-03-16T00:00:00Z",
                ),
            source =
                P801ReconciledEvidenceSource(
                    evidenceId = "evidence-1",
                    sourceId = "source-1",
                    importInputRef = "pick-handle-1",
                    recordOrdinal = 2L,
                    recordKind = "transfer_flow_source",
                    occurredAt = "2026-03-15T02:00:00Z",
                ),
            history = listOf(P801ReconciledLegLinkHistoryEntry(1L, "active", "confirmed", "2026-03-16T00:00:00Z")),
        )

    private fun audit(
        vararg links: P801ReconciledLegEvidenceLink,
        postingId: String = "posting-1",
    ): P801ReconciledLegAudit =
        P801ReconciledLegAudit(
            postingId = PostingId(postingId),
            transactionId = TransactionId("tx-1"),
            reconciliation =
                P801ReconciledLegReconciliation(
                    reconciliationId = "reconciliation-1",
                    status = P408ReconciliationStatus.CHECKED,
                    history = listOf(P801ReconciledLegStatusEntry(1L, P408ReconciliationStatus.PENDING, null, "schema-v23")),
                ),
            evidence = links.toList(),
        )

    private fun queueRow(status: P408ReconciliationStatus = P408ReconciliationStatus.PENDING): P801AccountReconciliationQueueRow =
        P801AccountReconciliationQueueRow(
            postingId = PostingId("posting-1"),
            transactionId = TransactionId("tx-1"),
            occurredAt = occurredAt,
            note = "转账",
            accountId = accountId,
            amountMinor = -10_000L,
            currency = cny,
            status = status,
            hasActiveEvidenceLink = false,
            transactionKind = TransactionKind.ACCOUNT_TRANSFER,
        )

    // ---- five-state labels and the null eligibility copy (V-7) ----

    @Test
    fun theFiveStateLabelsAreTheFrozenOnes() {
        assertEquals("待对账", reconciliationStatusText(P408ReconciliationStatus.PENDING))
        assertEquals("部分匹配", reconciliationStatusText(P408ReconciliationStatus.PARTIAL))
        assertEquals("有差异", reconciliationStatusText(P408ReconciliationStatus.DIFFERENCE))
        assertEquals("待补资料", reconciliationStatusText(P408ReconciliationStatus.MISSING))
        assertEquals("已核对", reconciliationStatusText(P408ReconciliationStatus.CHECKED))
        val labels = P408ReconciliationStatus.entries.map { status -> reconciliationStatusText(status) }
        assertEquals(labels.size, labels.distinct().size)
    }

    @Test
    fun aNullStatusRendersTheNoEligibilityCopyAndNeverAFiveStateLabel() {
        assertEquals("—（无对账资格）", reconciliationStatusText(null))
        assertEquals(RECONCILIATION_NO_ELIGIBILITY_TEXT, reconciliationStatusText(null))
        // V-7: the null copy is NOT any of the five labels — in particular never PENDING (待对账).
        P408ReconciliationStatus.entries.forEach { status ->
            assertTrue(reconciliationStatusText(status) != reconciliationStatusText(null))
        }
        assertTrue(reconciliationStatusText(P408ReconciliationStatus.PENDING) != reconciliationStatusText(null))
        // The candidate-leg eligibility line is the same copy, and an eligible leg shows nothing.
        assertEquals(RECONCILIATION_NO_ELIGIBILITY_TEXT, reconciliationCandidateEligibilityText(false))
        assertNull(reconciliationCandidateEligibilityText(true))
    }

    // ---- disposition + the three causes (spec section 2.4) ----

    @Test
    fun theThreeMatcherCausesAreQuotedVerbatim() {
        listOf("no_unique_funding_candidate", "source_time_unresolved", "funding_facts_unresolved").forEach { cause ->
            assertTrue(reconciliationUnresolvedCauseText(cause).contains(cause))
        }
        assertEquals("成因：no_unique_funding_candidate", reconciliationUnresolvedCauseText("no_unique_funding_candidate"))
        // A missing reason is a typed absence, never an invented cause.
        assertEquals("成因：—", reconciliationUnresolvedCauseText(null))
    }

    @Test
    fun eachDispositionHasItsOwnCopyAndAmbiguousAsksForTheExplicitPick() {
        assertEquals("唯一候选", reconciliationDispositionText(P408MatchDisposition.PROPOSED_MATCH))
        assertTrue(reconciliationDispositionText(P408MatchDisposition.AMBIGUOUS).contains("明确选择"))
        // A reason-less UNRESOLVED is the typed-absence wording, never one of the three causes.
        assertEquals("无法确定对应关系", reconciliationDispositionText(P408MatchDisposition.UNRESOLVED))
    }

    // ---- rejection codes keep their token (spec section 2.5) ----

    @Test
    fun everyRejectionKeepsItsRawCodeToken() {
        val codes =
            listOf(
                "P408_EVIDENCE_ALREADY_LINKED",
                "P408_POSTING_RESPONSIBILITY_ALREADY_LINKED",
                "P408_POSTING_NOT_ELIGIBLE",
                "P408_POSTING_FACT_MISMATCH",
                "P408_POSTING_TIME_UNRESOLVED",
                "P408_POSTING_TIME_WINDOW_MISMATCH",
                "P408_EVIDENCE_NOT_FOUND",
                "P408_RESPONSIBILITY_POSTING_MISMATCH",
                "P408_REQUEST_IDENTITY_CONFLICT",
                "P408_REQUEST_BASIS_VERSION_RETIRED",
                "P408_WINDOW_DAYS_NOT_APPROVED",
                "P408_TRANSACTION_ID_MISMATCH",
                "P408_RECONCILIATION_ID_MISMATCH",
                "P408_RECONCILIATION_MISSING",
                "P408_SOURCE_FACT_UNRESOLVED",
                "P408_PROJECTION_ABSENT",
                "P408_PROJECTION_NOT_READY",
                "P408_PROJECTION_STATE_CONFLICT",
                "P408_PROJECTION_SOURCE_DRIFT",
                "P408_PROJECTION_CURRENCY_MISMATCH",
                "P408_PROJECTION_AMOUNT_NOT_REPRESENTABLE",
                "P408_PROJECTION_ARITHMETIC_OVERFLOW",
                "P408_RECONCILIATION_CONSTRAINT_VIOLATION",
                "P408_RECEIPT_NOT_FOUND",
                "P408_REQUEST_NOT_FOUND",
            )
        codes.forEach { code ->
            val text = reconciliationFailureText(code)
            assertTrue(text.contains(code), "the raw token must survive: $code")
            assertTrue(text.length > code.length, "a readable label must accompany the token: $code")
            assertTrue(text != reconciliationFailureText("UNKNOWN_CODE_$code"))
        }
        // An unknown code still keeps its token (never a silent success).
        assertTrue(reconciliationFailureText("SOMETHING_NEW").contains("SOMETHING_NEW"))
        assertEquals("该分录的此职责已核对", reconciliationFailureLabel("P408_POSTING_RESPONSIBILITY_ALREADY_LINKED"))
        assertEquals(RECONCILIATION_NOT_CONFIRMABLE_TEXT, reconciliationFailureLabel("P408_POSTING_NOT_ELIGIBLE"))
    }

    @Test
    fun thePreWriteRefusalsAndProposalFailuresHaveTypedCopy() {
        P801ConfirmLinkRefusal.entries.forEach { reason ->
            val text = reconciliationRefusalText(reason)
            assertTrue(text.isNotBlank(), "every confirm refusal needs copy: $reason")
        }
        P801BindEvidenceRefusal.entries.forEach { reason ->
            assertTrue(reconciliationBindRefusalText(reason).isNotBlank(), "every bind refusal needs copy: $reason")
        }
        P801CandidateProposalFailure.entries.forEach { failure ->
            assertTrue(reconciliationProposalFailureText(failure).isNotBlank(), "every proposal failure needs copy: $failure")
        }
        assertEquals(RECONCILIATION_NOT_CONFIRMABLE_TEXT, reconciliationProposalFailureText(P801CandidateProposalFailure.LEG_NOT_TRANSFER_CONFIRMABLE))
        // Distinct refusals must not collapse into one string.
        val distinct = P801ConfirmLinkRefusal.entries.map { reason -> reconciliationRefusalText(reason) }.distinct()
        assertEquals(P801ConfirmLinkRefusal.entries.size, distinct.size)
    }

    // ---- summary (spec section 2.3) ----

    @Test
    fun theSummaryUsesOnlyTheSpecTermsAndExactMinorUnits() {
        val totals =
            listOf(
                total(P408ReconciliationStatus.CHECKED, 12_345L),
                total(P408ReconciliationStatus.PENDING, 500L, rowCount = 2),
            )
        val line = reconciliationSummaryLine(cny, totals)
        assertEquals("CNY 已核对合计 123.45 · 未核对合计 5.00 · 条数 3", line)
        assertTrue(line.contains("已核对"))
        assertTrue(line.contains("未核对"))
        assertTrue(line.contains("条数"))
        // D-308 item 6 / D-3 copy: 已对账 is never used, and no balance-derived phrase exists.
        assertFalse(line.contains("已对账"))
        assertFalse(line.contains("余额"))
        assertFalse(line.contains("差额"))
    }

    @Test
    fun theSummaryKeepsAbsentGroupsAbsentAndOrdersByTheReadOrder() {
        // Only a PENDING group exists: the checked side is a real 0.00 (V-9 allows it), but no
        // currency line is invented beyond the groups the read returned.
        val onlyPending = listOf(total(P408ReconciliationStatus.PENDING, 500L))
        assertEquals("CNY 已核对合计 0.00 · 未核对合计 5.00 · 条数 1", reconciliationSummaryLine(cny, onlyPending))
        // A second currency appears only because the read returned it, in the read's own order.
        val usd = CurrencyUnit("USD", 2)
        val lines = reconciliationSummaryLines(listOf(total(P408ReconciliationStatus.CHECKED, 100L), total(P408ReconciliationStatus.PENDING, 200L, currency = usd)))
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("CNY "))
        assertTrue(lines[1].startsWith("USD "))
        // No group at all = no line (an empty summary never fabricates a zero row).
        assertEquals(emptyList(), reconciliationSummaryLines(emptyList()))
    }

    @Test
    fun theTwoReadFailuresHaveDistinctCopies() {
        val rowsFailure = reconciliationQueueFailureText()
        val summaryFailure = reconciliationSummaryFailureText()
        assertTrue(rowsFailure != summaryFailure)
        assertTrue(summaryFailure.contains("保留"))
        assertTrue(!rowsFailure.contains("0.00") && !summaryFailure.contains("0.00"))
        assertTrue(reconciliationQueueEmptyText().isNotBlank())
        assertTrue(reconciliationQueueEmptyText() != rowsFailure)
    }

    // ---- queue rows + partitions ----

    @Test
    fun theQueueRowCarriesTheExactSignedAmountAndTheFrozenLabel() {
        val text = reconciliationQueueRowText(queueRow())
        assertTrue(text.contains("-100.00 CNY"))
        assertTrue(text.contains("2026-03-15"))
        assertTrue(text.contains("转账"))
        assertTrue(text.contains("待对账"))
        val checked = reconciliationQueueRowText(queueRow(P408ReconciliationStatus.CHECKED))
        assertTrue(checked.contains("已核对"))
        // F12f: the click label is an ACTION, never a screen name and never the visible text.
        assertEquals("打开该分录的核对详情", RECONCILIATION_OPEN_LEG_LABEL)
        assertFalse(RECONCILIATION_OPEN_LEG_LABEL == RECONCILIATION_TITLE)
        assertFalse(text.contains(RECONCILIATION_OPEN_LEG_LABEL))
    }

    @Test
    fun theCheckedPartitionUsesTheSpecWords() {
        assertTrue(reconciliationUncheckedPartitionText(3).startsWith("未核对"))
        assertTrue(reconciliationCheckedPartitionText(2, expanded = false).contains("已核对"))
        assertTrue(reconciliationCheckedPartitionText(2, expanded = false).contains("展开"))
        assertTrue(reconciliationCheckedPartitionText(2, expanded = true).contains("收起"))
        assertFalse(reconciliationCheckedPartitionText(2, expanded = true).contains("已对账"))
    }

    // ---- confirm gating (spec sections 2.4/2.5) ----

    @Test
    fun confirmabilityFollowsTheLegsDirectionAndTheExistingActiveLink() {
        val anchor = PostingId("posting-1")
        val outLeg = candidate(direction = "out")
        val inLeg = candidate(direction = "in")
        assertTrue(reconciliationCandidateConfirmable(outLeg, audit(), anchor))
        assertTrue(reconciliationCandidateConfirmable(inLeg, audit(), anchor))
        // An active REAL_ACCOUNT_POSTING link blocks the out leg (the same-responsibility rule) and
        // leaves the in leg confirmable (each leg is reconciled independently).
        val withRealAccountLink = audit(link(P408EvidenceResponsibility.REAL_ACCOUNT_POSTING))
        assertFalse(reconciliationCandidateConfirmable(outLeg, withRealAccountLink, anchor))
        assertTrue(reconciliationCandidateConfirmable(inLeg, withRealAccountLink, anchor))
        assertNull(reconciliationCandidateBlockReason(inLeg, withRealAccountLink, anchor))
        assertTrue(reconciliationCandidateBlockReason(outLeg, withRealAccountLink, anchor)!!.isNotBlank())
        // A no-direction leg has no duty to decide: never confirmable, and its block reason is its
        // OWN sentence (F9) — not the same-responsibility one.
        val noDirection = candidate(direction = "")
        assertFalse(reconciliationCandidateConfirmable(noDirection, audit(), anchor))
        assertTrue(reconciliationCandidateBlockReason(noDirection, audit(), anchor)!!.contains("方向"))
        // An INVALIDATED link does not block anything.
        assertTrue(reconciliationCandidateConfirmable(outLeg, audit(link(state = "invalidated")), anchor))
        // F11: a candidate on ANOTHER posting is judged neutrally — the anchor leg's audit says
        // nothing about a sibling leg's link state.
        val sibling = candidate(postingId = "posting-sibling")
        assertTrue(reconciliationCandidateConfirmable(sibling, withRealAccountLink, anchor))
        assertNull(reconciliationCandidateBlockReason(sibling, withRealAccountLink, anchor))
        // R1: an audit that belongs to a DIFFERENT posting (the sibling leg was just confirmed and
        // its audit came back) must not be applied to the anchor leg either.
        val siblingAudit = audit(link(P408EvidenceResponsibility.REAL_ACCOUNT_POSTING), postingId = "posting-sibling")
        assertTrue(reconciliationCandidateConfirmable(outLeg, siblingAudit, anchor))
        assertNull(reconciliationCandidateBlockReason(outLeg, siblingAudit, anchor))
    }

    // ---- audit read-back (spec section 2.6) ----

    @Test
    fun theAuditLinesCarryTheSourceResponsibilityBasisDistanceAndDecisionTime() {
        val evidenceLink = link()
        val source = reconciliationAuditSourceText(evidenceLink)
        assertTrue(source.contains("pick-handle-1"))
        assertTrue(source.contains("第 2 条"))
        assertTrue(source.contains("transfer_flow_source"))
        assertEquals("#1 待对账 schema-v23", reconciliationAuditStatusHistoryText(audit().reconciliation!!.history.single()))
        val lines = reconciliationAuditDecisionLines(evidenceLink)
        assertEquals(6, lines.size)
        assertTrue(lines.any { line -> line.startsWith(RECONCILIATION_ELEMENT_EXTERNAL_EVIDENCE) })
        assertTrue(lines.any { line -> line.startsWith(RECONCILIATION_ELEMENT_RESPONSIBILITY) && line.contains("real_account_posting") })
        assertTrue(lines.any { line -> line.startsWith(RECONCILIATION_ELEMENT_MATCH_BASIS) })
        // F12d: the persisted basis version and window are rendered, not kept as unread fields.
        assertTrue(lines.any { line -> line.contains("v2") && line.contains("窗口 2 天") })
        assertTrue(lines.any { line -> line.contains("自然日距离 1 天") })
        assertTrue(lines.any { line -> line.startsWith(RECONCILIATION_ELEMENT_HUMAN_DECISION) && line.contains("confirm_match") })
        assertTrue(lines.any { line -> line.contains("2026-03-16T00:00:00Z") })
        assertEquals("#1 待对账 schema-v23", reconciliationAuditStatusHistoryText(audit().reconciliation!!.history.single()))
        assertEquals("#1 active confirmed 2026-03-16T00:00:00Z", reconciliationAuditLinkHistoryText(evidenceLink.history.single()))
        assertTrue(reconciliationAuditHeadlineText(audit(evidenceLink)).contains("已核对"))
        assertTrue(reconciliationAuditNoLinkText().isNotBlank())
        assertTrue(reconciliationAuditFailureText().isNotBlank())
        assertTrue(reconciliationAuditNotFoundText().isNotBlank())
    }

    @Test
    fun aSnapshotLessLinkRendersTheTypedAbsenceInsteadOfAZeroBasis() {
        val bare = link().copy(decision = null)
        val lines = reconciliationAuditDecisionLines(bare)
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("—（无请求快照）"))
        assertFalse(lines.single().contains("0"))
    }

    // ---- match basis / no ranking ----

    @Test
    fun theBasisKeepsTheFrozenFiveTokensInCanonicalOrder() {
        val text = reconciliationMatchBasisText(listOf("occurred_at_window", "account", "direction", "currency", "amount"))
        assertEquals("amount · currency · direction · account · occurred_at_window", text)
        // A stored token outside the frozen five still renders (never silently dropped).
        assertTrue(reconciliationMatchBasisText(listOf("amount", "legacy_token")).contains("legacy_token"))
    }

    @Test
    fun noRenderedStringOffersARankingOrScore() {
        val auditLine = audit(link())
        val rendered =
            buildList {
                add(RECONCILIATION_TITLE)
                add(RECONCILIATION_EVIDENCE_ENTRY_TITLE)
                add(RECONCILIATION_BIND_IRREVERSIBLE_WARNING)
                add(RECONCILIATION_NOT_CONFIRMABLE_TEXT)
                add(RECONCILIATION_NO_ELIGIBILITY_TEXT)
                add(RECONCILIATION_UNKNOWN_RESULT_TEXT)
                add(IMPORT_USED_FOR_RECONCILIATION_TEXT)
                add(reconciliationQueueRowText(queueRow()))
                add(reconciliationSummaryLine(cny, listOf(total(P408ReconciliationStatus.CHECKED, 1L))))
                add(reconciliationNoBindableEvidenceText())
                add(reconciliationBindableEvidenceFailureText())
                add(reconciliationQueueEmptyText())
                add(reconciliationQueueFailureText())
                add(reconciliationSummaryFailureText())
                add(reconciliationCandidateText(candidate()))
                add(reconciliationAuditSourceText(link()))
                add(reconciliationAuditHeadlineText(auditLine))
                add(reconciliationAuditEmptyHistoryText())
                addAll(reconciliationAuditDecisionLines(link()))
                P408MatchDisposition.entries.forEach { disposition -> add(reconciliationDispositionText(disposition)) }
                add(reconciliationNaturalDayDistanceText(4L))
                add(reconciliationNoticeText(P503AppState.ReconciliationDetailNotice.Linked(queueRefreshed = true)))
                add(reconciliationNoticeText(P503AppState.ReconciliationDetailNotice.Linked(queueRefreshed = false)))
                add(reconciliationNoticeText(P503AppState.ReconciliationDetailNotice.NoChange))
                add(reconciliationNoticeText(P503AppState.ReconciliationDetailNotice.UnknownResult))
                add(reconciliationNoticeText(P503AppState.ReconciliationDetailNotice.RuntimeNotReady))
                add(reconciliationNoticeText(P503AppState.ReconciliationDetailNotice.Bound))
                add(reconciliationNoticeText(P503AppState.ReconciliationDetailNotice.BindNoChange))
                add(reconciliationBindableEvidenceText(evidenceRow()))
            }
        val forbidden = listOf("余额", "差额", "明细合计", "置信", "评分", "排名", "score", "rank", "confidence", "已对账")
        rendered.forEach { text ->
            forbidden.forEach { word ->
                assertFalse(text.contains(word), "forbidden phrasing '$word' in: $text")
            }
        }
    }

    @Test
    fun theFrozenCopyConstantsAreTheSpecSentences() {
        assertEquals("账户对账", RECONCILIATION_TITLE)
        assertEquals("用于核对既有分录", RECONCILIATION_EVIDENCE_ENTRY_TITLE)
        assertEquals("绑定后不能在应用内改绑", RECONCILIATION_BIND_IRREVERSIBLE_WARNING)
        assertEquals("暂不支持在应用内核对", RECONCILIATION_NOT_CONFIRMABLE_TEXT)
        assertEquals("—（无对账资格）", RECONCILIATION_NO_ELIGIBILITY_TEXT)
        assertEquals("结果未知，重新进入核对", RECONCILIATION_UNKNOWN_RESULT_TEXT)
        assertEquals("已用于核对既有分录", IMPORT_USED_FOR_RECONCILIATION_TEXT)
        assertEquals("confirm_match", RECONCILIATION_HUMAN_DECISION_CONFIRM_MATCH)
        assertEquals("外部证据", RECONCILIATION_ELEMENT_EXTERNAL_EVIDENCE)
        assertEquals("证据职责", RECONCILIATION_ELEMENT_RESPONSIBILITY)
        assertEquals("匹配依据", RECONCILIATION_ELEMENT_MATCH_BASIS)
        assertEquals("人工决定", RECONCILIATION_ELEMENT_HUMAN_DECISION)
        assertEquals("审计回看", RECONCILIATION_AUDIT_HEADER)
    }

    @Test
    fun theImportRowDerivedMarkerIsNotAnInventedStatus() {
        // The marker is plain copy over the application-layer derived flag (spec section 2.7); the
        // row itself carries no new status token.
        val row =
            ImportReviewRow(
                candidateId = com.unifiedledger.application.ImportCandidateId("candidate-1"),
                candidateKind = "transfer_flow",
                sourceInputRef = "pick-handle-1",
                amountMinor = 10_000L,
                currencyCode = "CNY",
                currencyPrecision = 2,
                occurredAt = "2026-03-15T02:00:00Z",
                directionToken = "out",
                statusToken = "trade_success",
                fundingState = com.unifiedledger.application.ImportFundingState.SETTLED,
                completeness = com.unifiedledger.application.ImportCompleteness.VALID_COMPLETE,
                contentHash = "sha256:fixed",
                candidateStatus = "pending_confirmation",
                requiresConfirmation = true,
                confidence = "high",
                duplicateStatus = null,
                paymentProfileVariant = null,
                paymentProfileAssetLegKindToken = null,
                paymentProfileCreditLegKindToken = null,
                evidenceId = "evidence-1",
                usedForReconciliation = true,
            )
        assertTrue(row.usedForReconciliation)
        assertEquals("evidence-1", row.evidenceId)
        // The default (a fixture that predates i3a) is the unlinked shape, never a linked claim.
        assertFalse(row.copy(usedForReconciliation = false).usedForReconciliation)
    }

    // ---- F2: the projection rejection code reaches the screen ----

    @Test
    fun aRejectedProjectionRendersItsStoredCode() {
        val rejected = projectionRow(state = P408ProjectionState.REJECTED, rejectionCode = "P408_PROJECTION_CURRENCY_MISMATCH")
        val code = "P408_PROJECTION_CURRENCY_MISMATCH"
        val proposalText = reconciliationProposalFailureText(P801CandidateProposalFailure.EVIDENCE_PROJECTION_REJECTED, rejected)
        assertTrue(proposalText.contains(code), "the raw token must survive: $proposalText")
        assertTrue(proposalText.contains(reconciliationFailureLabel(code)))
        // The generic sentence remains the typed absence of a projection row, never the REJECTED copy.
        assertEquals(
            "该导入行的绑定被拒绝。",
            reconciliationProposalFailureText(P801CandidateProposalFailure.EVIDENCE_PROJECTION_REJECTED),
        )
        val refusalText = reconciliationRefusalText(P801ConfirmLinkRefusal.EVIDENCE_PROJECTION_REJECTED, code)
        assertTrue(refusalText.contains(code))
        assertTrue(refusalText.contains(reconciliationFailureLabel(code)))
        // The bind list row carries it too, and so does the landed notice path.
        assertTrue(reconciliationBindableEvidenceText(evidenceRow(projection = rejected)).contains(code))
        assertTrue(
            reconciliationNoticeText(
                P503AppState.ReconciliationDetailNotice.Refused(P801ConfirmLinkRefusal.EVIDENCE_PROJECTION_REJECTED, code),
            ).contains(code),
        )
    }

    // ---- F5: unreconciled is not ineligible ----

    @Test
    fun theAuditDoesNotCallAMerelyUnreconciledLegIneligible() {
        val withoutRow = audit().copy(reconciliation = null)
        val headline = reconciliationAuditHeadlineText(withoutRow)
        assertTrue(headline.contains(RECONCILIATION_NO_RECONCILIATION_TEXT))
        assertFalse(headline.contains(RECONCILIATION_NO_ELIGIBILITY_TEXT))
        // The two strings are DISTINCT: the ineligibility copy stays the eligibility face's own.
        assertTrue(RECONCILIATION_NO_RECONCILIATION_TEXT != RECONCILIATION_NO_ELIGIBILITY_TEXT)
        assertEquals(RECONCILIATION_NO_ELIGIBILITY_TEXT, reconciliationStatusText(null))
        // A reconciliation row with an empty history reports its status, never "no record".
        val emptyHistory =
            audit().copy(
                reconciliation = P801ReconciledLegReconciliation("reconciliation-1", P408ReconciliationStatus.CHECKED, emptyList()),
            )
        assertTrue(reconciliationAuditHeadlineText(emptyHistory).contains("已核对"))
        assertFalse(reconciliationAuditHeadlineText(emptyHistory).contains(RECONCILIATION_NO_RECONCILIATION_TEXT))
        assertEquals(RECONCILIATION_NO_STATUS_HISTORY_TEXT, reconciliationAuditEmptyHistoryText())
    }

    // ---- F8: the three pure predicates ----

    @Test
    fun theQueueFaceDistinguishesReadFailureEmptyAndList() {
        assertEquals(ReconciliationQueueFace.UNAVAILABLE, reconciliationQueueFace(queueState().copy(rowsLoadFailed = true)))
        assertEquals(ReconciliationQueueFace.EMPTY, reconciliationQueueFace(queueState()))
        assertEquals(ReconciliationQueueFace.LIST, reconciliationQueueFace(queueState().copy(rows = listOf(queueRow()))))
        // An open keyset with zero rows is a LIST (never an empty claim).
        assertEquals(
            ReconciliationQueueFace.LIST,
            reconciliationQueueFace(queueState().copy(nextCursor = P801AccountReconciliationCursor("2026-03-15T02:00:00Z", PostingId("posting-1")))),
        )
        // A failed read with loaded rows keeps the list (V-9: rows stay beside the retry).
        assertEquals(
            ReconciliationQueueFace.LIST,
            reconciliationQueueFace(queueState().copy(rows = listOf(queueRow()), rowsLoadFailed = true)),
        )
    }

    @Test
    fun confirmAndBindGatingPinTheInFlightAndConflictRules() {
        val selected = P503AppState.ReconciliationSelection(0, 0)
        assertTrue(reconciliationConfirmEnabled(detailState(selected = selected)))
        assertFalse(reconciliationConfirmEnabled(detailState(selected = null)))
        assertFalse(reconciliationConfirmEnabled(detailState(selected = selected, binding = true)))
        assertFalse(reconciliationConfirmEnabled(detailState(selected = selected, confirming = true)))
        // The anchor leg already carries an active link of that responsibility.
        val blocked =
            detailState(
                selected = selected,
                auditResult = P801ReconciledLegAuditResult.Success(audit(link(P408EvidenceResponsibility.REAL_ACCOUNT_POSTING))),
            )
        assertFalse(reconciliationConfirmEnabled(blocked))
        // Bind: a target AND a chosen evidence AND no write in flight.
        assertTrue(reconciliationBindEnabled(detailState(bindEvidenceId = "evidence-1")))
        assertTrue(reconciliationBindEnabled(entryABindableDetail(bindEvidenceId = "evidence-1")))
        assertFalse(reconciliationBindEnabled(detailState(bindEvidenceId = "evidence-1", bindTarget = null)))
        assertFalse(reconciliationBindEnabled(detailState(bindEvidenceId = null)))
        assertFalse(reconciliationBindEnabled(detailState(bindEvidenceId = "evidence-1", binding = true)))
        assertFalse(reconciliationBindEnabled(detailState(bindEvidenceId = "evidence-1", confirming = true)))
    }

    // ---- F9: each UNRESOLVED cause and the direction-undecidable case ----

    @Test
    fun eachUnresolvedCauseHasItsOwnWording() {
        val causes = listOf("no_unique_funding_candidate", "source_time_unresolved", "funding_facts_unresolved")
        val texts = causes.map { cause -> reconciliationDispositionText(P408MatchDisposition.UNRESOLVED, cause) }
        assertEquals(texts.size, texts.distinct().size, "each matcher cause needs its own wording: $texts")
        causes.forEach { cause -> assertTrue(reconciliationUnresolvedCauseText(cause).contains(cause)) }
        assertTrue(reconciliationDispositionText(P408MatchDisposition.UNRESOLVED, null) !in texts)
        assertEquals("唯一候选", reconciliationDispositionText(P408MatchDisposition.PROPOSED_MATCH))
        assertTrue(reconciliationDispositionText(P408MatchDisposition.AMBIGUOUS).contains("明确选择"))
    }

    // ---- F10: the success copy follows the return target ----

    @Test
    fun theSuccessNoticeFollowsTheReturnTarget() {
        val queued = reconciliationNoticeText(P503AppState.ReconciliationDetailNotice.Linked(queueRefreshed = true))
        val auditOnly = reconciliationNoticeText(P503AppState.ReconciliationDetailNotice.Linked(queueRefreshed = false))
        assertTrue(queued.contains("队列"))
        assertTrue(auditOnly.contains("审计"))
        assertFalse(auditOnly.contains("队列"))
        assertTrue(queued != auditOnly)
    }

    // ---- F1/F11: the bind step follows the evidence's projection state ----

    @Test
    fun theBindStepFollowsTheEvidenceProjectionStateNotTheProposalOutcome() {
        // Entry B, unprojected evidence: the bind step is offered.
        assertTrue(
            reconciliationBindStepVisible(
                detailState(anchorPostingId = null, anchorEvidenceId = "evidence-1", anchorEvidence = evidenceRow()),
            ),
        )
        // Entry B, READY projection: no bind step.
        assertFalse(
            reconciliationBindStepVisible(
                detailState(anchorPostingId = null, anchorEvidenceId = "evidence-1", anchorEvidence = evidenceRow(projection = projectionRow())),
            ),
        )
        // Entry B, the evidence read failed (no row): F11 — a failed read must not offer binding.
        assertFalse(reconciliationBindStepVisible(detailState(anchorPostingId = null, anchorEvidenceId = "evidence-1", anchorEvidence = null)))
        // Entry B, projected but the POSTING-FACTS read failed: still no bind step (F11).
        assertFalse(
            reconciliationBindStepVisible(
                detailState(
                    anchorPostingId = null,
                    anchorEvidenceId = "evidence-1",
                    anchorEvidence = evidenceRow(projection = projectionRow()),
                    proposals = P801CandidateProposalResult.Failed(P801CandidateProposalFailure.POSTING_FACTS_UNAVAILABLE),
                ),
            ),
        )
        // Entry A (a leg anchor): the bind step is reachable even with an empty bind list.
        assertTrue(
            reconciliationBindStepVisible(
                entryABindableDetail(bindable = P801BindableEvidenceResult.Success(emptyList())),
            ),
        )
    }

    // ---- helpers ----

    private fun projectionRow(
        state: P408ProjectionState = P408ProjectionState.READY,
        rejectionCode: String? = null,
    ): P408EvidenceProjection =
        P408EvidenceProjection(
            ledgerId = "ledger-1",
            projectionId = "projection-1",
            evidenceId = "evidence-1",
            sourceId = "source-1",
            sourceHash = "sha256:source-1",
            targetAccountId = accountId.value,
            currencyCode = "CNY",
            currencyPrecision = 2,
            rawAmountMinor = 10_000L,
            rawCurrencyPrecision = 2,
            normalizedAmountMinor = 10_000L,
            directionToken = "out",
            state = state,
            rejectionCode = rejectionCode,
            ruleId = "p408_evidence_projection_v1",
            ruleVersion = 1,
            materializationRequestId = "materialize-1",
            materializedAt = "2026-03-16T00:00:00Z",
        )

    private fun evidenceRow(
        projection: P408EvidenceProjection? = null,
        evidenceId: String = "evidence-1",
    ): P801ReconciliationCandidateEvidence =
        P801ReconciliationCandidateEvidence(
            evidenceId = evidenceId,
            sourceId = "source-1",
            candidateId = "candidate-1",
            contentHash = "sha256:source-1",
            rawAmountMinor = 10_000L,
            rawCurrencyCode = "CNY",
            rawCurrencyPrecision = 2,
            rawDirectionToken = "out",
            occurredAt = temporal,
            projection = projection,
            hasActiveEvidenceLink = false,
        )

    private fun proposal(
        candidates: List<P408MatchCandidate> = listOf(candidate()),
        disposition: P408MatchDisposition = P408MatchDisposition.PROPOSED_MATCH,
    ): P801CandidateProposal = P801CandidateProposal(evidenceRow(), P408MatchResult("evidence-1", disposition, candidates, null))

    private fun overview(): P503AppState.OverviewEmpty =
        P503AppState.OverviewEmpty(
            state = LedgerCurrentState(ledgerId = LedgerId("ledger-1"), transactions = emptyList(), balances = emptyList()),
            selectedTab = P503Tab.ACCOUNTS,
        )

    private fun queueState(): P503AppState.ReconciliationQueue =
        P503AppState.ReconciliationQueue(
            returnTo =
                P503AppState.AccountFlow(
                    overview = overview(),
                    accountId = accountId,
                    balanceLine = "招商储蓄卡 · 资产 · CNY",
                    balanceMinorUnits = 0L,
                    balanceCurrency = cny,
                    loadedRows = emptyList(),
                    nextWholeLedgerCursor = null,
                ),
            accountId = accountId,
            accountLine = "招商储蓄卡 · 资产 · CNY",
            rows = emptyList(),
            nextCursor = null,
            totals = emptyList(),
        )

    private fun detailState(
        proposals: P801CandidateProposalResult? = P801CandidateProposalResult.Success(listOf(proposal())),
        selected: P503AppState.ReconciliationSelection? = null,
        auditResult: P801ReconciledLegAuditResult = P801ReconciledLegAuditResult.Success(audit()),
        anchorPostingId: PostingId? = PostingId("posting-1"),
        anchorEvidenceId: String? = null,
        accountId: AccountId? = this.accountId,
        bindTarget: AccountId? = this.accountId,
        bindEvidenceId: String? = null,
        bindableEvidence: P801BindableEvidenceResult? = null,
        anchorEvidence: P801ReconciliationCandidateEvidence? = null,
        binding: Boolean = false,
        confirming: Boolean = false,
        notice: P503AppState.ReconciliationDetailNotice? = null,
    ): P503AppState.ReconciliationDetail =
        P503AppState.ReconciliationDetail(
            returnTo = ReconciliationBackTarget.Queue(queueState()),
            anchorPostingId = anchorPostingId,
            anchorEvidenceId = anchorEvidenceId,
            accountId = accountId,
            bindTarget = bindTarget,
            bindAccounts = emptyList(),
            proposals = proposals,
            audit = auditResult,
            selectedCandidate = selected,
            binding = binding,
            confirming = confirming,
            notice = notice,
            bindableEvidence = bindableEvidence,
            bindEvidenceId = bindEvidenceId,
            anchorEvidence = anchorEvidence,
        )

    private fun entryABindableDetail(
        bindEvidenceId: String? = null,
        bindable: P801BindableEvidenceResult = P801BindableEvidenceResult.Success(listOf(evidenceRow())),
    ): P503AppState.ReconciliationDetail =
        detailState(
            bindableEvidence = bindable,
            bindEvidenceId = bindEvidenceId,
            bindTarget = accountId,
        )
}
