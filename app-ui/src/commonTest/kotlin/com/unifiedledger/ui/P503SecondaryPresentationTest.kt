package com.unifiedledger.ui

import com.unifiedledger.application.CreationEntry
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportDuplicateCandidateId
import com.unifiedledger.application.ImportDuplicateStatus
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportReviewRow
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.P408EvidenceResponsibility
import com.unifiedledger.application.P408MatchBasis
import com.unifiedledger.application.P408MatchCandidate
import com.unifiedledger.application.P408MatchDisposition
import com.unifiedledger.application.P408MatchResult
import com.unifiedledger.application.P408PostingFacts
import com.unifiedledger.application.P408ReconciliationStatus
import com.unifiedledger.application.P408TemporalComponents
import com.unifiedledger.application.P408TemporalEvidence
import com.unifiedledger.application.P801AccountReconciliationQueueRow
import com.unifiedledger.application.P801CandidateProposal
import com.unifiedledger.application.P801ReconciledEvidenceSource
import com.unifiedledger.application.P801ReconciledLegAudit
import com.unifiedledger.application.P801ReconciledLegDecision
import com.unifiedledger.application.P801ReconciledLegEvidenceLink
import com.unifiedledger.application.P801ReconciledLegLinkHistoryEntry
import com.unifiedledger.application.P801ReconciledLegReconciliation
import com.unifiedledger.application.P801ReconciledLegStatusEntry
import com.unifiedledger.application.P801ReconciliationCandidateEvidence
import com.unifiedledger.application.P806AccountPostingRow
import com.unifiedledger.application.P806CalendarView
import com.unifiedledger.application.P806CurrencyView
import com.unifiedledger.application.P806PeriodKind
import com.unifiedledger.application.P806ReportView
import com.unifiedledger.application.P806ResolvedPeriod
import com.unifiedledger.application.P806SnapshotFailure
import com.unifiedledger.application.P806TransactionPage
import com.unifiedledger.application.P806Window
import com.unifiedledger.application.PresentationTimeRole
import com.unifiedledger.application.TransactionAnnotationHistoryEntry
import com.unifiedledger.application.TransactionDetail
import com.unifiedledger.application.TransactionDetailLeg
import com.unifiedledger.application.TransactionDetailResult
import com.unifiedledger.application.TransactionReconciliationProjection
import com.unifiedledger.application.TransactionVersionHistoryResult
import com.unifiedledger.application.presentationTimes
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersion
import com.unifiedledger.domain.TransactionVersionId
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * D-369 C: the secondary-surface presentation contracts.
 *
 * Covers the UTC day boundary (both sides), the two profiles' outputs, the collapsed 技术信息 area
 * that keeps every identifier, the explainable failure copy of every typed failure branch, the one
 * difference-preview projection that drives visible text AND speech, and the retained audit facts of
 * the detail/history/annotation surfaces. No Compose runtime is involved.
 */
class P503SecondaryPresentationTest {
    private val cny = CurrencyUnit("CNY", 2)
    private val ledgerId = LedgerId("0190a5c0-0000-7000-8000-000000000001")
    private val accountId = AccountId("asset-bank")

    // ---- UTC day boundary: UTC 16:00-23:59 is already the next Asia/Shanghai day ----

    @Test
    fun reconciliationQueueRowKeepsTheUtcDateOnLegacyAndUsesTheShanghaiDayOnAndroid() {
        // 15:59Z is 23:59 Shanghai on the SAME day: a negative case for the day shift.
        val beforeBoundary = queueRow("2026-11-27T15:59:00Z")
        assertEquals("2026-11-27", reconciliationRowDateText(beforeBoundary, UiPresentationProfile.LEGACY))
        assertEquals("2026-11-27", reconciliationRowDateText(beforeBoundary, UiPresentationProfile.ANDROID))

        // 16:00Z is 00:00 Shanghai the NEXT day: the positive case the spec requires.
        val atBoundary = queueRow("2026-11-27T16:00:00Z")
        assertEquals("2026-11-27", reconciliationRowDateText(atBoundary, UiPresentationProfile.LEGACY))
        assertEquals("2026-11-28", reconciliationRowDateText(atBoundary, UiPresentationProfile.ANDROID))

        val lateEvening = queueRow("2026-11-27T23:59:00Z")
        assertEquals("2026-11-27", reconciliationRowDateText(lateEvening, UiPresentationProfile.LEGACY))
        assertEquals("2026-11-28", reconciliationRowDateText(lateEvening, UiPresentationProfile.ANDROID))
    }

    @Test
    fun reconciliationCandidateUsesTheShanghaiDayAndKeepsTheExactAmount() {
        val candidate = candidate("2026-11-27T16:30:00Z")

        val legacy = reconciliationCandidateText(candidate, UiPresentationProfile.LEGACY)
        val android = reconciliationCandidateText(candidate, UiPresentationProfile.ANDROID)

        assertTrue(legacy.startsWith("2026-11-27 · -100.00 CNY"), message = legacy)
        assertTrue(android.startsWith("2026-11-28 · -100.00 CNY"), message = android)
        assertTrue(android.contains("自然日距离"), android)
    }

    // ---- the two profiles' business language ----

    @Test
    fun kindAmountAndTimeFollowTheSelectedProfile() {
        val kind = TransactionKind.CREDIT_REPAYMENT
        assertEquals("CREDIT_REPAYMENT", cBusinessKindText(kind, UiPresentationProfile.LEGACY))
        assertEquals("信用还款", cBusinessKindText(kind, UiPresentationProfile.ANDROID))

        assertEquals("-12.34", cAmountText(-1_234L, cny, UiPresentationProfile.LEGACY))
        assertEquals("-12.34 CNY", cAmountText(-1_234L, cny, UiPresentationProfile.ANDROID))
        assertEquals("0.00 CNY", cAmountText(0L, cny, UiPresentationProfile.ANDROID))

        val instant = Instant.parse("2026-11-27T17:05:00Z")
        assertEquals("2026-11-27T17:05:00Z", cBusinessTimeText(instant, PresentationTimeRole.OCCURRED, UiPresentationProfile.LEGACY))
        assertEquals("2026-11-28T01:05", cBusinessTimeText(instant, PresentationTimeRole.OCCURRED, UiPresentationProfile.ANDROID))
    }

    @Test
    fun businessTimesCarryTheirOwnRoleAndReadOrAuditTimesAreSeparate() {
        // A statistics instant is never rendered through the occurred role: the pair keeps both
        // labels and both wall clocks, and each side comes from its own persisted instant.
        val times =
            presentationTimes(
                Instant.parse("2026-11-27T17:05:00Z"),
                Instant.parse("2026-11-27T17:06:00Z"),
            )
        assertEquals(
            "发生时间 2026-11-28T01:05 · 统计时间 2026-11-28T01:06",
            cPresentationTimesText(times, UiPresentationProfile.ANDROID),
        )
        assertEquals(
            "发生时间 2026-11-27T17:05:00Z · 统计时间 2026-11-27T17:06:00Z",
            cPresentationTimesText(times, UiPresentationProfile.LEGACY),
        )
        // The role-bound single-instant helper agrees with the pair and keeps the role it was given.
        assertEquals(
            cBusinessTimeText(times.statistics, UiPresentationProfile.ANDROID),
            cBusinessTimeText(Instant.parse("2026-11-27T17:06:00Z"), PresentationTimeRole.STATISTICS, UiPresentationProfile.ANDROID),
        )

        // A READ instant and a stored AUDIT instant are their own things: same frozen zone, no
        // occurred/statistics role claimed, and LEGACY echoes the raw value.
        val read = Instant.parse("2026-11-27T17:05:00Z")
        assertEquals("2026-11-27T17:05:00Z", cReadInstantText(read, UiPresentationProfile.LEGACY))
        assertEquals("2026-11-28T01:05", cReadInstantText(read, UiPresentationProfile.ANDROID))
        assertEquals("2026-11-27T17:05:00Z", cStoredAuditText("2026-11-27T17:05:00Z", UiPresentationProfile.LEGACY))
        assertEquals("2026-11-28T01:05", cStoredAuditText("2026-11-27T17:05:00Z", UiPresentationProfile.ANDROID))
        // An unparseable stored audit value is echoed verbatim, never silently dropped.
        assertEquals("not-an-instant", cStoredAuditText("not-an-instant", UiPresentationProfile.ANDROID))
    }

    // ---- 技术信息: collapsed by default, complete when expanded ----

    @Test
    fun technicalInfoIsCollapsedByDefaultAndKeepsEveryValueUntruncatedWhenExpanded() {
        val longId = "0190a5c0-1111-7000-8000-abcdefabcdef"
        val rows =
            listOf(
                TechnicalInfoRow("交易 ID", longId),
                TechnicalInfoRow("请求 ID", "0190a5c0-2222-7000-8000-000000000002"),
            )

        val collapsed = cTechnicalInfoDisclosure(rows, expanded = false)
        assertEquals("技术信息", collapsed.header)
        assertEquals("展开技术信息", collapsed.toggleLabel)
        assertEquals("", collapsed.visibleText())
        assertTrue(collapsed.visibleRows.isEmpty())
        assertFalse(collapsed.spokenText().contains(longId))
        assertTrue(collapsed.spokenText().contains("已收起"))

        val expanded = cTechnicalInfoDisclosure(rows, expanded = true)
        assertEquals("收起技术信息", expanded.toggleLabel)
        assertEquals(rows, expanded.visibleRows)
        // The expanded value is the ORIGINAL string: no truncation, no ellipsis, no hash.
        assertTrue(expanded.visibleText().contains(longId))
        assertTrue(expanded.spokenText().contains(longId))
        assertFalse(expanded.visibleText().contains("…"))
    }

    @Test
    fun detailMovesIdentifiersToTheTechnicalAreaAndKeepsTheShanghaiUserFields() {
        val detail = detail()
        val profile = UiPresentationProfile.ANDROID

        val userLines = cTransactionDetailUserLines(detail, profile)
        assertEquals(
            listOf(
                "发生时间：2026-11-28T01:05",
                "统计时间：2026-11-28T01:05",
                "备注：无",
                "类型：信用还款",
            ),
            userLines,
        )
        assertTrue(userLines.none { line -> line.contains(detail.transactionId.value) })

        val technical = cTransactionDetailTechnicalRows(detail)
        assertEquals(
            listOf(
                "账本 ID" to ledgerId.value,
                "交易 ID" to detail.transactionId.value,
                "当前版本 ID" to detail.currentVersionId.value,
                "分录 ID" to "posting-expense",
            ),
            technical.map { row -> row.label to row.value },
        )
    }

    @Test
    fun annotationHistoryLineMovesTheSeenVersionAndRequestIdIntoTheTechnicalRows() {
        val entry = annotation()
        val legacy = cTransactionAnnotationUserLine(entry, null, UiPresentationProfile.LEGACY)
        assertTrue(legacy.contains("所见版本 version-7"), legacy)
        assertTrue(legacy.contains("请求 request-9"), legacy)
        assertTrue(legacy.contains("（2026-11-27T17:05:00Z）"), legacy)

        val android = cTransactionAnnotationUserLine(entry, null, UiPresentationProfile.ANDROID)
        assertFalse(android.contains("version-7"), android)
        assertFalse(android.contains("request-9"), android)
        assertTrue(android.contains("（2026-11-28T01:05）"), android)
        assertTrue(android.contains("标签：无"), android)
        assertTrue(android.contains("商家：无"), android)

        // D-224: the ORIGINAL stored createdAt text stays complete in 技术信息 even though the
        // visible line shows the friendly Shanghai wall clock. The original value is never removed.
        val technical = cTransactionAnnotationTechnicalRows(entry).map { row -> row.label to row.value }
        assertEquals(
            listOf(
                "注释时间（原值）" to "2026-11-27T17:05:00Z",
                "所见版本 ID" to "version-7",
                "请求 ID" to "request-9",
            ),
            technical,
        )
        val expanded = cTechnicalInfoDisclosure(cTransactionAnnotationTechnicalRows(entry), expanded = true)
        assertTrue(expanded.visibleText().contains("2026-11-27T17:05:00Z"), expanded.visibleText())
    }

    @Test
    fun versionHistoryMovesTheInternalIdsIntoTheTechnicalRowsWithoutDroppingTheAuditFact() {
        val currentVersionId = TransactionVersionId("version-7")
        val version =
            TransactionVersion(
                id = currentVersionId,
                transactionId = TransactionId("tx-1"),
                versionNumber = 7,
                postingSetId = PostingSetId("posting-set-7"),
                times = TransactionTimes(Instant.parse("2026-11-27T17:05:00Z"), Instant.parse("2026-11-27T17:05:00Z"), Instant.parse("2026-11-27T17:05:00Z")),
                note = "午餐",
            )

        val legacy = cTransactionVersionUserLine(version, currentVersionId, UiPresentationProfile.LEGACY)
        assertTrue(legacy.contains("2026-11-27T17:05:00Z"), legacy)
        assertTrue(legacy.contains("（当前版本）"), legacy)

        val android = cTransactionVersionUserLine(version, currentVersionId, UiPresentationProfile.ANDROID)
        assertTrue(android.contains("2026-11-28T01:05"), android)
        assertFalse(android.contains("version-7"), android)
        assertEquals(
            listOf("版本 ID" to "version-7", "分录集 ID" to "posting-set-7"),
            cTransactionVersionTechnicalRows(version).map { row -> row.label to row.value },
        )
    }

    // ---- error copy: every stable failure type has explainable copy ----

    @Test
    fun everySnapshotFailureHasDistinctExplainableCopyOnEverySurface() {
        val failures =
            listOf(
                P806SnapshotFailure.Unavailable,
                P806SnapshotFailure.CatalogVersionMismatch,
                P806SnapshotFailure.CatalogReference(
                    unresolvedAccount = 1L,
                    crossLedgerAccount = 0L,
                    currencyMismatch = 0L,
                ),
                P806SnapshotFailure.MissingProjection,
                P806SnapshotFailure.OccurredAtUnparseable,
                P806SnapshotFailure.InvalidState,
                P806SnapshotFailure.InvalidWindow,
            )
        val surfaces = CPresentationSurface.entries
        for (surface in surfaces) {
            val texts = failures.map { failure -> p806SnapshotFailureUserText(failure, surface) }
            assertEquals(texts.size, texts.distinct().size, "one copy per failure branch on $surface")
            for (text in texts) {
                assertTrue(text.isNotBlank())
                // Explainable prose, never the raw enum constant or a bare code.
                assertFalse(text.contains("P806SnapshotFailure"), text)
                assertFalse(text.contains("Unavailable"), text)
            }
        }
        // The three surfaces keep their own frozen wording rather than collapsing into one sentence.
        assertEquals(
            "报表读取不可用：请重试。",
            p806SnapshotFailureUserText(P806SnapshotFailure.Unavailable, CPresentationSurface.REPORT),
        )
    }

    @Test
    fun detailAndVersionHistoryFailuresHaveExplainableCopy() {
        assertEquals("交易不存在或不在当前账本。", transactionDetailFailureText(TransactionDetailResult.NotFound))
        assertEquals("交易数据不一致，无法展示详情。", transactionDetailFailureText(TransactionDetailResult.InvalidState))
        assertEquals("无法读取交易详情（本地数据库不可用）。", transactionDetailFailureText(TransactionDetailResult.Unavailable))
        assertEquals(
            "版本历史不可用：交易数据不一致。",
            transactionVersionHistoryFailureText(TransactionVersionHistoryResult.InvalidState),
        )
    }

    // ---- one diff projection drives the visible line and the TalkBack sentence ----

    @Test
    fun oneDiffProjectionDrivesBothTheVisibleLineAndTheSpokenLine() {
        val changed = cDiffLineProjection("备注", "旧备注", "新备注", changed = true)
        assertEquals("备注：旧备注 → 新备注（已修改）", changed.visibleText)
        assertEquals("备注，旧值 旧备注，新值 新备注，已修改", changed.spokenText)

        val unchanged = cDiffLineProjection("统计时间", "2026-11-27T10:00", "2026-11-27T10:00", changed = false)
        assertEquals("统计时间：2026-11-27T10:00 → 2026-11-27T10:00", unchanged.visibleText)
        assertEquals("统计时间，旧值 2026-11-27T10:00，新值 2026-11-27T10:00，未修改", unchanged.spokenText)

        // Both read-aloud helpers now read the same fields, so neither can drift from the visible copy.
        val transferLine =
            TransferEditDiffLine(
                field = TransferEditField.NOTE,
                label = "备注",
                oldText = "旧备注",
                newText = "新备注",
            )
        assertEquals(changed.spokenText, transferEditDiffLineContentDescription(transferLine))
    }

    @Test
    fun restoreTimestampFollowsTheProfile() {
        val epochMillis = Instant.parse("2026-11-27T17:05:00Z").toEpochMilliseconds()
        assertEquals("2026-11-27T17:05:00Z", restoreTimestampText(epochMillis))
        assertEquals("2026-11-28T01:05", restoreTimestampText(epochMillis, UiPresentationProfile.ANDROID))
    }

    // ---- calendar: the one failure mapping keeps every frozen sentence ----

    @Test
    fun calendarFailureCopyIsPinnedThroughTheSharedMapping() {
        val expected =
            mapOf(
                P806SnapshotFailure.Unavailable to "日历读取失败，可点击重试。",
                P806SnapshotFailure.CatalogVersionMismatch to "账目目录已更新，可点击重试。",
                P806SnapshotFailure.CatalogReference(unresolvedAccount = 1L, crossLedgerAccount = 0L, currencyMismatch = 0L) to
                    "目录引用不一致（缺账户 / 跨账本 / 币种不符），日历不可用。",
                P806SnapshotFailure.MissingProjection to "账目缺少统计时间投影，日历不可用。",
                P806SnapshotFailure.OccurredAtUnparseable to "存在无法解析的资金发生时间，日历不可用。",
                P806SnapshotFailure.InvalidState to "日历数据不一致，不可用。",
                P806SnapshotFailure.InvalidWindow to "日历时间范围无效，不可用。",
            )
        for ((failure, copy) in expected) {
            assertEquals(copy, p806CalendarFailureText(failure))
            assertEquals(copy, p806SnapshotFailureUserText(failure, CPresentationSurface.CALENDAR))
            assertFalse(copy.contains("P806SnapshotFailure"), copy)
        }
    }

    // ---- audit read-back: identifiers and audit instants fold, facts stay ----

    @Test
    fun auditHeadlineNamesTheStatusAndMovesThePostingIdToTheTechnicalArea() {
        val audit = audit()
        assertEquals("分录 posting-7 · 已核对", reconciliationAuditHeadlineText(audit))
        val android = reconciliationAuditHeadlineText(audit, UiPresentationProfile.ANDROID)
        assertEquals("对账状态：已核对", android)
        assertFalse(android.contains("posting-7"), android)

        val collapsed = cTechnicalInfoDisclosure(reconciliationAuditTechnicalRows(audit), expanded = false)
        assertFalse(collapsed.spokenText().contains("posting-7"))
        val expanded = cTechnicalInfoDisclosure(reconciliationAuditTechnicalRows(audit), expanded = true)
        assertTrue(expanded.visibleText().contains("posting-7"))
        assertTrue(expanded.visibleText().contains("evidence-9"))
        // The stored audit instants stay readable as their original values.
        assertTrue(expanded.visibleText().contains("2026-11-27T17:05:00Z"))
        assertTrue(expanded.visibleText().contains("2026-11-27T17:06:00Z"))
    }

    @Test
    fun auditDecisionLinesKeepTheVisibleBasisAndRenderTheDecisionTimeInShanghai() {
        val link = audit().evidence.single()

        val legacy = reconciliationAuditDecisionLines(link)
        assertTrue(legacy.any { line -> line.contains("外部证据：evidence-9") }, legacy.toString())
        assertTrue(legacy.any { line -> line.contains("2026-11-27T17:05:00Z") }, legacy.toString())

        val android = reconciliationAuditDecisionLines(link, UiPresentationProfile.ANDROID)
        assertFalse(android.any { line -> line.contains("evidence-9") }, android.toString())
        assertTrue(android.any { line -> line.contains("证据职责：real_account_posting") }, android.toString())
        assertTrue(android.any { line -> line.startsWith("匹配依据：amount · currency") }, android.toString())
        assertTrue(android.any { line -> line.contains("自然日距离 1 天") }, android.toString())
        assertTrue(android.any { line -> line.contains("人工决定：confirm_match · 2026-11-28T01:05") }, android.toString())
    }

    @Test
    fun auditHistoryTimesUseShanghaiWhileStatesAndSequencesStay() {
        val link = audit().evidence.single()
        val entry = link.history.single()
        assertEquals("#1 active confirmed 2026-11-27T17:06:00Z", reconciliationAuditLinkHistoryText(entry))
        assertEquals(
            "#1 active confirmed 2026-11-28T01:06",
            reconciliationAuditLinkHistoryText(entry, UiPresentationProfile.ANDROID),
        )

        val status = audit().reconciliation!!.history.single()
        assertEquals("#1 已核对 2026-11-27T17:07:00Z", reconciliationAuditStatusHistoryText(status))
        assertEquals(
            "#1 已核对 2026-11-28T01:07",
            reconciliationAuditStatusHistoryText(status, UiPresentationProfile.ANDROID),
        )
    }

    @Test
    fun theEvidenceSourceLineIsNeverRewrittenByTheDisplayRule() {
        val link = audit().evidence.single()
        // Raw source facts: import handle, record ordinal, record kind and the persisted source time
        // text stay verbatim (no Shanghai conversion, no re-serialisation).
        assertEquals(
            "证据来源：导入 import-handle · 第 3 条（wechat_row）· 来源时间 2026-11-27T20:00:00+08:00",
            reconciliationAuditSourceText(link),
        )
    }

    @Test
    fun confirmEvidenceIdFoldsWhileTheVisibleDecisionFieldsStay() {
        assertEquals(listOf(TechnicalInfoRow("证据 ID", "evidence-9")), reconciliationConfirmTechnicalRows("evidence-9"))
    }

    @Test
    fun confirmShowsTheEvidenceFactsAndFoldsOnlyTheId() {
        val proposal = proposal()
        val candidate = candidate("2026-11-27T16:30:00Z")

        // LEGACY keeps the frozen identifier line verbatim.
        assertEquals("外部证据：evidence-9", reconciliationConfirmEvidenceLine(proposal, UiPresentationProfile.LEGACY))

        // ANDROID shows the persisted facts (source time text + raw amount) and never the opaque id.
        val androidLines = reconciliationConfirmLines(proposal, candidate, UiPresentationProfile.ANDROID)
        assertEquals(5, androidLines.size)
        assertEquals("外部证据：来源时间 2026-11-27T20:00:00+08:00 · -100.00 CNY", androidLines[0])
        assertTrue(androidLines[1].startsWith("证据职责：real_account_posting"), androidLines[1])
        assertTrue(androidLines[2].startsWith("匹配依据：amount · currency · direction · account · occurred_at_window"), androidLines[2])
        assertTrue(androidLines[3].contains("自然日距离 2 天"), androidLines[3])
        assertTrue(androidLines[4].startsWith("人工决定：confirm_match"), androidLines[4])
        assertTrue(androidLines.none { line -> line.contains("evidence-9") }, androidLines.toString())

        // The id stays complete in 技术信息: hidden while collapsed, readable when expanded.
        val rows = reconciliationConfirmTechnicalRows(proposal.evidence.evidenceId)
        val collapsed = cTechnicalInfoDisclosure(rows, expanded = false)
        assertFalse(collapsed.spokenText().contains("evidence-9"), collapsed.spokenText())
        val expanded = cTechnicalInfoDisclosure(rows, expanded = true)
        assertTrue(expanded.visibleText().contains("evidence-9"), expanded.visibleText())
    }

    @Test
    fun targetAccountDispatchesByProfileAndKeepsTheRawIdInTheTechnicalArea() {
        // LEGACY is the frozen expression verbatim: queue line, else raw account id, else the dash.
        assertEquals("现金账户", reconciliationTargetAccountText("现金账户", "忽略", "account-9"))
        assertEquals("account-9", reconciliationTargetAccountText(null, "信用卡", "account-9"))
        assertEquals("—", reconciliationTargetAccountText(null, null, null))

        // ANDROID prefers a readable name and states the absence explicitly instead of a raw id.
        assertEquals("现金账户", reconciliationTargetAccountText("现金账户", "忽略", "account-9", UiPresentationProfile.ANDROID))
        assertEquals("信用卡", reconciliationTargetAccountText(null, "信用卡", "account-9", UiPresentationProfile.ANDROID))
        assertEquals(
            "目标账户未加载（账户名不可用）",
            reconciliationTargetAccountText(null, null, "account-9", UiPresentationProfile.ANDROID),
        )
        assertEquals(
            "目标账户未加载（账户名不可用）",
            reconciliationTargetAccountText("  ", " ", "account-9", UiPresentationProfile.ANDROID),
        )

        // The id itself is never deleted on ANDROID: it is a technical row, present whether or not
        // the visible line could name the account.
        assertEquals(listOf(TechnicalInfoRow("目标账户 ID", "account-9")), reconciliationTargetAccountTechnicalRows("account-9"))
        assertTrue(reconciliationTargetAccountTechnicalRows(null).isEmpty())
    }

    @Test
    fun auditTechnicalRowsGroupEachLinkSoMultiLinkAuditsStayAttributable() {
        val single = audit()
        val secondLink =
            single.evidence.single().copy(
                linkId = "link-2",
                evidenceId = "evidence-b2",
                state = "active",
                history =
                    listOf(
                        P801ReconciledLegLinkHistoryEntry(
                            sequence = 2L,
                            state = "active",
                            reason = "confirmed",
                            occurredAt = "2026-11-27T18:00:00Z",
                        ),
                    ),
            )
        val twoLinks = single.copy(evidence = listOf(single.evidence.single(), secondLink))

        val rows = reconciliationAuditTechnicalRows(twoLinks)
        assertEquals("链接 1（active）", rows[2].label)
        assertEquals("证据 ID", rows[3].label)
        assertEquals("evidence-9", rows[3].value)
        val secondHeader = rows.indexOfFirst { row -> row.label == "链接 2（active）" }
        assertTrue(secondHeader > rows.indexOfFirst { row -> row.value == "evidence-9" })
        assertEquals("证据 ID", rows[secondHeader + 1].label)
        assertEquals("evidence-b2", rows[secondHeader + 1].value)

        val expanded = cTechnicalInfoDisclosure(rows, expanded = true)
        val text = expanded.visibleText()
        // Each link's values sit inside its own group: header, then that link's evidence id.
        assertTrue(text.indexOf("链接 1（active）") < text.indexOf("evidence-9"))
        assertTrue(text.indexOf("evidence-9") < text.indexOf("链接 2（active）"))
        assertTrue(text.indexOf("链接 2（active）") < text.indexOf("evidence-b2"))
        // A header row renders as the bare group label, not as an empty `label：` value.
        assertTrue(text.contains("链接 1（active）\n"), text)
        assertFalse(text.contains("链接 1（active）："), text)
    }

    // ---- consumer branches of the secondary surfaces ----

    @Test
    fun accountFlowRowBranchesByProfileWithTheShanghaiDayAndTheExactLeg() {
        val row = flowRow()
        assertEquals("资金发生日期 2026-11-28 · ACCOUNT_TRANSFER", accountFlowRowHeadlineText(row, UiPresentationProfile.LEGACY))
        assertEquals("资金发生日期 2026-11-28 · 转账", accountFlowRowHeadlineText(row, UiPresentationProfile.ANDROID))
        assertEquals(
            "ACCOUNT_TRANSFER，资金发生日期 2026-11-28；现金账户 -12.34 CNY",
            accountFlowRowContentDescription(row, "现金账户", UiPresentationProfile.LEGACY),
        )
        assertEquals(
            "转账，资金发生日期 2026-11-28；现金账户 -12.34 CNY",
            accountFlowRowContentDescription(row, "现金账户", UiPresentationProfile.ANDROID),
        )
    }

    @Test
    fun calendarOldContextRendersTheReadInstantPerProfile() {
        val state = calendarState()
        val legacy = p806CalendarOldContextText(state, UiPresentationProfile.LEGACY) ?: error("a retained payload must be labelled")
        assertTrue(legacy.contains("2026年12月"), legacy)
        assertTrue(legacy.contains("读取时点 2026-11-27T17:05:00Z"), legacy)

        val android = p806CalendarOldContextText(state, UiPresentationProfile.ANDROID) ?: error("a retained payload must be labelled")
        assertTrue(android.contains("2026年12月"), android)
        assertTrue(android.contains("读取时点 2026-11-28T01:05"), android)
        assertFalse(android.contains("17:05:00Z"), android)
    }

    @Test
    fun reportLastSuccessReadInstantIsPerProfile() {
        val state = reportState()
        assertEquals(
            "最近成功（期间 月：2026-11-01 至 2026-11-30；币种轴 CNY；读取时点 2026-11-27T17:05:00Z）",
            p806ReportLastSuccessContextText(state, UiPresentationProfile.LEGACY),
        )
        assertEquals(
            "最近成功（期间 月：2026-11-01 至 2026-11-30；币种轴 CNY；读取时点 2026-11-28T01:05）",
            p806ReportLastSuccessContextText(state, UiPresentationProfile.ANDROID),
        )
    }

    @Test
    fun restorePreviewKeepsAllSevenFieldsAndTheShanghaiReadInstant() {
        val android = restorePreviewLines(restoreSummary(), UiPresentationProfile.ANDROID)
        assertEquals(7, android.size)
        assertEquals("容器格式：版本 1（2048 字节）", android[0])
        assertEquals("账本结构版本：1 → 31", android[1])
        assertEquals("账本身份：ledger-a（目标：ledger-local-test）", android[2])
        assertEquals("工件摘要：${"ab".repeat(32)}", android[3])
        assertEquals("校验：完整性 通过、外键 通过、领域 通过", android[4])
        assertEquals("将替换为：3 个账户、12 个分类、45 笔交易", android[5])
        assertEquals("预检时间：2023-11-15T06:13", android[6])

        val legacy = restorePreviewLines(restoreSummary(), UiPresentationProfile.LEGACY)
        assertEquals("预检时间：2023-11-14T22:13:20Z", legacy[6])
    }

    @Test
    fun reconciliationQueueRowBranchesByProfile() {
        val row = queueRow("2026-11-27T17:05:00Z")
        assertEquals(
            "2026-11-27 · ACCOUNT_TRANSFER · -100.00 CNY · 待对账",
            reconciliationQueueRowText(row, UiPresentationProfile.LEGACY),
        )
        assertEquals(
            "2026-11-28 · 转账 · -100.00 CNY · 待对账",
            reconciliationQueueRowText(row, UiPresentationProfile.ANDROID),
        )
    }

    // ---- fixtures ---------------------------------------------------------------------

    private fun queueRow(occurredAtText: String): P801AccountReconciliationQueueRow =
        P801AccountReconciliationQueueRow(
            postingId = PostingId("posting-1"),
            transactionId = TransactionId("tx-1"),
            occurredAt = Instant.parse(occurredAtText),
            note = null,
            accountId = accountId,
            amountMinor = -10_000L,
            currency = cny,
            status = P408ReconciliationStatus.PENDING,
            hasActiveEvidenceLink = false,
            transactionKind = TransactionKind.ACCOUNT_TRANSFER,
        )

    private fun candidate(occurredAtText: String): P408MatchCandidate {
        val instant = Instant.parse(occurredAtText)
        return P408MatchCandidate(
            posting =
                P408PostingFacts(
                    ledgerId = ledgerId.value,
                    postingId = "posting-1",
                    transactionId = "tx-1",
                    transactionLedgerId = ledgerId.value,
                    amountMinor = -10_000L,
                    currencyCode = "CNY",
                    currencyPrecision = 2,
                    direction = "out",
                    accountId = accountId.value,
                    occurredAt =
                        P408TemporalEvidence(
                            rawText = occurredAtText,
                            kind = "offset_datetime",
                            offsetPresent = true,
                            components = P408TemporalComponents(2026, 11, 27, 16, 30, 0),
                            instant = instant,
                        ),
                    eligibleRealAccount = true,
                    current = true,
                ),
            basis = P408MatchBasis(setOf("amount", "currency", "direction", "account", "occurred_at_window"), 2L, 2),
        )
    }

    private fun detail(): TransactionDetail {
        val leg =
            TransactionDetailLeg(
                postingId = PostingId("posting-expense"),
                accountId = AccountId("account-expense"),
                accountName = "信用还款账户",
                amount = Money.ofMinor(-10_000L, cny),
                categoryName = null,
            )
        return TransactionDetail(
            ledgerId = ledgerId,
            transactionId = TransactionId("tx-detail-1"),
            currentVersionId = TransactionVersionId("version-detail-3"),
            kind = TransactionKind.CREDIT_REPAYMENT,
            occurredAt = Instant.parse("2026-11-27T17:05:00Z"),
            statisticsAt = Instant.parse("2026-11-27T17:05:00Z"),
            note = null,
            legs = listOf(leg),
            creationEntry = CreationEntry.UNMARKED,
            reconciliation = TransactionReconciliationProjection(legs = emptyList(), rollup = null),
        )
    }

    private fun audit(): P801ReconciledLegAudit {
        val link =
            P801ReconciledLegEvidenceLink(
                linkId = "link-1",
                evidenceId = "evidence-9",
                candidateId = "candidate-1",
                requestId = "request-1",
                responsibility = P408EvidenceResponsibility.REAL_ACCOUNT_POSTING,
                createdAt = "2026-11-27T17:05:00Z",
                state = "active",
                stateSequence = 1L,
                decision =
                    P801ReconciledLegDecision(
                        basisVersion = 2,
                        matchBasis = listOf("amount", "currency"),
                        windowDays = 3,
                        naturalDayDistance = 1,
                        humanDecision = "confirm_match",
                        decidedAt = "2026-11-27T17:05:00Z",
                    ),
                source =
                    P801ReconciledEvidenceSource(
                        evidenceId = "evidence-9",
                        sourceId = "source-1",
                        importInputRef = "import-handle",
                        recordOrdinal = 3L,
                        recordKind = "wechat_row",
                        occurredAt = "2026-11-27T20:00:00+08:00",
                    ),
                history =
                    listOf(
                        P801ReconciledLegLinkHistoryEntry(
                            sequence = 1L,
                            state = "active",
                            reason = "confirmed",
                            occurredAt = "2026-11-27T17:06:00Z",
                        ),
                    ),
            )
        return P801ReconciledLegAudit(
            postingId = PostingId("posting-7"),
            transactionId = TransactionId("tx-audit-7"),
            reconciliation =
                P801ReconciledLegReconciliation(
                    reconciliationId = "reconciliation-7",
                    status = P408ReconciliationStatus.CHECKED,
                    history =
                        listOf(
                            P801ReconciledLegStatusEntry(
                                sequence = 1L,
                                status = P408ReconciliationStatus.CHECKED,
                                evidenceLinkId = "link-1",
                                occurredAt = "2026-11-27T17:07:00Z",
                            ),
                        ),
                ),
            evidence = listOf(link),
        )
    }

    private fun annotation(): TransactionAnnotationHistoryEntry =
        TransactionAnnotationHistoryEntry(
            annotationRevision = 4,
            observedTransactionVersionId = TransactionVersionId("version-7"),
            tagIds = emptyList(),
            merchantId = null,
            requestId = "request-9",
            createdAt = "2026-11-27T17:05:00Z",
        )

    private fun flowRow(): P806AccountPostingRow =
        P806AccountPostingRow(
            postingId = PostingId("posting-flow-1"),
            transactionId = TransactionId("tx-flow-1"),
            occurredAt = Instant.parse("2026-11-27T17:05:00Z"),
            note = null,
            kind = TransactionKind.ACCOUNT_TRANSFER,
            amount = Money.ofMinor(-1_234L, cny),
        )

    private fun calendarState(): P503AppState.Calendar =
        P503AppState.Calendar(
            overview = P503AppState.OverviewEmpty(LedgerCurrentState(ledgerId, emptyList(), emptyList())),
            month = YearMonth(2026, Month.NOVEMBER),
            loaded = P806CalendarView(ledgerId, YearMonth(2026, Month.DECEMBER), listOf(cny), emptyList()),
            failure = P806SnapshotFailure.Unavailable,
            landedAsOf = Instant.parse("2026-11-27T17:05:00Z"),
        )

    private fun reportState(): P806ReportRegionState =
        P806ReportRegionState(
            landed = reportView(),
            landedKind = P806PeriodKind.MONTH,
            landedAsOf = Instant.parse("2026-11-27T17:05:00Z"),
        )

    private fun reportView(): P806ReportView =
        P806ReportView(
            ledgerId = ledgerId,
            period = P806ResolvedPeriod(P806Window(1L, 2L), LocalDate(2026, 11, 1), LocalDate(2026, 11, 30), 30),
            currencies = listOf(P806CurrencyView(cny, 0L, 0L, 0L, 0L, 0L, null, 0)),
            expenseCategories = emptyList(),
            incomeCategories = emptyList(),
            uncategorizedExpenseTotals = emptyList(),
            uncategorizedIncomeTotals = emptyList(),
            transactionCount = 0,
            futureTransactionCount = 0,
            detailPage = P806TransactionPage(emptyList(), null),
            detailCount = 0,
        )

    private fun restoreSummary(): RestorePreflightSummary =
        RestorePreflightSummary(
            containerFormatVersion = 1,
            containerSize = 2048L,
            sourceSchemaVersion = 1L,
            migratedSchemaVersion = 31L,
            sourceLedgerId = "ledger-a",
            targetLedgerId = "ledger-local-test",
            authenticatedArtifactSha256Hex = "ab".repeat(32),
            integrityOk = true,
            foreignKeyOk = true,
            domainOk = true,
            ledgerIdentityCount = 1,
            formalTableCount = 8,
            postingImbalanceCount = 0,
            accountsCount = 3L,
            categoriesCount = 12L,
            transactionsCount = 45L,
            preflightEpochMillis = 1_700_000_000_000L,
        )

    private fun proposal(): P801CandidateProposal {
        val evidence =
            P801ReconciliationCandidateEvidence(
                evidenceId = "evidence-9",
                sourceId = "source-1",
                candidateId = "candidate-1",
                contentHash = "hash",
                rawAmountMinor = -10_000L,
                rawCurrencyCode = "CNY",
                rawCurrencyPrecision = 2,
                rawDirectionToken = "out",
                occurredAt =
                    P408TemporalEvidence(
                        rawText = "2026-11-27T20:00:00+08:00",
                        kind = "offset_datetime",
                        offsetPresent = true,
                        components = P408TemporalComponents(2026, 11, 27, 20, 0, 0),
                        instant = Instant.parse("2026-11-27T12:00:00Z"),
                    ),
                projection = null,
                hasActiveEvidenceLink = false,
            )
        return P801CandidateProposal(
            evidence = evidence,
            match =
                P408MatchResult(
                    evidenceId = evidence.evidenceId,
                    disposition = P408MatchDisposition.PROPOSED_MATCH,
                    candidates = listOf(candidate("2026-11-27T16:30:00Z")),
                ),
        )
    }

    // ---- D-369 C: the import review face's business labels, time and technical area ----

    @Test
    fun importCandidateMetaKeepsTheDesktopLineVerbatim() {
        val row = importRow(duplicateStatus = ImportDuplicateStatus.DEFERRED)

        assertEquals(
            "类型 ordinary_flow；发生 2026-11-27T17:05:00Z；方向 out；状态 settled；重复 DEFERRED",
            importCandidateMetaText(row, UiPresentationProfile.LEGACY),
        )
        // The default argument is LEGACY, so every existing desktop call site keeps its exact output.
        assertEquals(importCandidateMetaText(row, UiPresentationProfile.LEGACY), importCandidateMetaText(row))
    }

    @Test
    fun importCandidateMetaAndroidUsesBusinessLabelsAndTheShanghaiWallClock() {
        val row = importRow(duplicateStatus = ImportDuplicateStatus.DEFERRED)

        // The stored 17:05Z instant is 01:05 the NEXT Shanghai day: the label carries the local
        // wall clock, never the bare ISO instant.
        assertEquals(
            "类型 普通收支；发生 2026-11-28T01:05；方向 支出；状态 交易成功；重复 疑似重复——待审核",
            importCandidateMetaText(row, UiPresentationProfile.ANDROID),
        )
    }

    @Test
    fun importCandidateMetaEchoesNullFieldsUnknownTokensAndAnUnparseableTime() {
        val unknown = importRow(occurredAt = "未提供时间", statusToken = "trade_success")
        assertEquals(
            "类型 普通收支；发生 未提供时间；方向 支出；状态 trade_success；重复 无",
            importCandidateMetaText(unknown, UiPresentationProfile.ANDROID),
        )
        val missingTime = importRow(occurredAt = null)
        assertEquals(
            "类型 普通收支；方向 支出；状态 交易成功；重复 无",
            importCandidateMetaText(missingTime, UiPresentationProfile.ANDROID),
        )
    }

    @Test
    fun importDirectionLabelsFollowTheCandidateKind() {
        assertEquals("转入", importDirectionLabel("transfer_flow", "in"))
        assertEquals("转出", importDirectionLabel("transfer_flow_missing_leg", "out"))
        assertEquals("收入", importDirectionLabel("ordinary_flow", "in"))
        assertEquals("支出", importDirectionLabel("ordinary_flow", "out"))
        // An unknown persisted token is echoed, never guessed into a direction.
        assertEquals("不计收支", importDirectionLabel("ordinary_flow", "不计收支"))
    }

    @Test
    fun importKindLabelsCoverEveryPersistedTokenAndTheCreditRefundVariant() {
        assertEquals("普通收支", importCandidateKindLabel(importRow(candidateKind = "ordinary_flow")))
        assertEquals("转账", importCandidateKindLabel(importRow(candidateKind = "transfer_flow")))
        assertEquals("转账（缺少对方腿）", importCandidateKindLabel(importRow(candidateKind = "transfer_flow_missing_leg")))
        assertEquals("信用消费", importCandidateKindLabel(importRow(candidateKind = "credit_expense")))
        assertEquals(
            "信用退款",
            importCandidateKindLabel(importRow(candidateKind = "credit_expense", paymentProfileVariant = "credit_expense_refund")),
        )
        assertEquals("信用还款", importCandidateKindLabel(importRow(candidateKind = "credit_repayment")))
        assertEquals("混合支付", importCandidateKindLabel(importRow(candidateKind = "mixed_payment")))
        assertEquals("未知来源", importCandidateKindLabel(importRow(candidateKind = "未知来源")))
    }

    @Test
    fun importDuplicateLabelsCoverEveryFoldedDisposition() {
        assertEquals("疑似重复——待审核", importDuplicateStatusLabel(ImportDuplicateStatus.DEFERRED))
        assertEquals("重复已确认——阻断", importDuplicateStatusLabel(ImportDuplicateStatus.CONFIRMED_DUPLICATE))
        assertEquals("已确认不重复", importDuplicateStatusLabel(ImportDuplicateStatus.CONFIRMED_DISTINCT))
        assertEquals("已忽略相似", importDuplicateStatusLabel(ImportDuplicateStatus.DISMISSED_LOOKALIKE))
        assertEquals("已拒绝", importDuplicateStatusLabel(ImportDuplicateStatus.REJECTED))
    }

    @Test
    fun groupItemKeepsTheDesktopIdentifierLineAndNamesTheCandidateByOrdinalOnAndroid() {
        val item = groupItem()

        assertEquals("候选 candidate-import-1", groupDispositionItemCandidateText(item, UiPresentationProfile.LEGACY))
        // The default argument is LEGACY, so the desktop card keeps its exact subject line.
        assertEquals("候选 candidate-import-1", groupDispositionItemCandidateText(item))
        assertEquals("候选第 3 项", groupDispositionItemCandidateText(item, UiPresentationProfile.ANDROID))
        // A not-yet-assigned ordinal degrades to the bare noun, never a fabricated position.
        assertEquals("候选", groupDispositionItemCandidateText(groupItem(ordinal = 0), UiPresentationProfile.ANDROID))
    }

    @Test
    fun groupItemTechnicalAreaKeepsBothIdentifiersComplete() {
        assertEquals(
            listOf(TechnicalInfoRow("候选 ID", "candidate-import-1"), TechnicalInfoRow("重复候选 ID", "duplicate-1")),
            groupDispositionItemTechnicalRows(groupItem()),
        )

        val expanded = cTechnicalInfoDisclosure(groupDispositionItemTechnicalRows(groupItem()), expanded = true)
        assertTrue(expanded.visibleText().contains("候选 ID：candidate-import-1"), expanded.visibleText())
        assertTrue(expanded.spokenText().contains("重复候选 ID duplicate-1"), expanded.spokenText())
    }

    @Test
    fun comparisonToggleDescriptionKeepsTheDesktopIdSentenceAndUsesTheOrdinalOnAndroid() {
        val candidateId = ImportCandidateId("candidate-import-1")

        assertEquals("展开候选 candidate-import-1 的第 2 条比较信息", groupDispositionItemComparisonToggleDescription(expanded = false, candidateId = candidateId, itemOrdinal = 2))
        assertEquals("收起候选 candidate-import-1 的第 1 条比较信息", groupDispositionItemComparisonToggleDescription(expanded = true, candidateId = candidateId, itemOrdinal = 1))

        val android = groupDispositionItemComparisonToggleDescription(expanded = false, candidateId = candidateId, itemOrdinal = 2, groupOrdinal = 3, profile = UiPresentationProfile.ANDROID)
        assertEquals("展开候选第 3 项的第 2 条比较信息", android)
        assertFalse(android.contains(candidateId.value), android)
    }

    @Test
    fun groupItemOutcomeKeepsTheDesktopTokensAndUsesBusinessLabelsOnAndroid() {
        val pending = groupItem()
        assertEquals("待处置", groupDispositionItemOutcomeText(pending, UiPresentationProfile.LEGACY))
        assertEquals("待处置", groupDispositionItemOutcomeText(pending, UiPresentationProfile.ANDROID))

        val reviewed = groupItem(outcome = ImportDuplicateGroupItemResult.Reviewed(ImportDuplicateStatus.CONFIRMED_DUPLICATE))
        assertEquals("已标记：CONFIRMED_DUPLICATE", groupDispositionItemOutcomeText(reviewed, UiPresentationProfile.LEGACY))
        assertEquals("已标记：重复已确认——阻断", groupDispositionItemOutcomeText(reviewed, UiPresentationProfile.ANDROID))
        // The default argument is LEGACY, so the desktop card keeps its exact outcome line.
        assertEquals(groupDispositionItemOutcomeText(reviewed, UiPresentationProfile.LEGACY), groupDispositionItemOutcomeText(reviewed))

        val deferred = groupItem(outcome = ImportDuplicateGroupItemResult.Reviewed(ImportDuplicateStatus.DEFERRED))
        assertEquals("已标记：疑似重复——待审核", groupDispositionItemOutcomeText(deferred, UiPresentationProfile.ANDROID))

        val rejected = groupItem(outcome = ImportDuplicateGroupItemResult.Rejected("SPINE_CANDIDATE_INCOMPLETE"))
        assertEquals("失败（SPINE_CANDIDATE_INCOMPLETE）", groupDispositionItemOutcomeText(rejected, UiPresentationProfile.LEGACY))
        assertEquals("失败：重复审核未通过，未写入任何记录。", groupDispositionItemOutcomeText(rejected, UiPresentationProfile.ANDROID))
        assertFalse(
            groupDispositionItemOutcomeText(rejected, UiPresentationProfile.ANDROID).contains("SPINE_CANDIDATE_INCOMPLETE"),
            "the ANDROID outcome line must not print the diagnostic code",
        )
    }

    @Test
    fun groupItemOutcomeTechnicalRowsKeepTheRawRecordedOutcome() {
        assertTrue(groupDispositionItemOutcomeTechnicalRows(groupItem()).isEmpty())

        val reviewed = groupItem(outcome = ImportDuplicateGroupItemResult.Reviewed(ImportDuplicateStatus.CONFIRMED_DUPLICATE))
        assertEquals(
            listOf(TechnicalInfoRow("处置结果（原值）", "CONFIRMED_DUPLICATE")),
            groupDispositionItemOutcomeTechnicalRows(reviewed),
        )

        val rejected = groupItem(outcome = ImportDuplicateGroupItemResult.Rejected("IMPORT_REVIEW_IDS_UNAVAILABLE"))
        assertEquals(
            listOf(TechnicalInfoRow("诊断码", "IMPORT_REVIEW_IDS_UNAVAILABLE")),
            groupDispositionItemOutcomeTechnicalRows(rejected),
        )
        val expanded = cTechnicalInfoDisclosure(groupDispositionItemOutcomeTechnicalRows(rejected), expanded = true)
        assertTrue(expanded.visibleText().contains("诊断码：IMPORT_REVIEW_IDS_UNAVAILABLE"), expanded.visibleText())
    }

    @Test
    fun groupItemTechnicalAreaCombinesIdentifiersWithTheRecordedOutcome() {
        val rejected = groupItem(outcome = ImportDuplicateGroupItemResult.Rejected("IMPORT_REVIEW_IDS_UNAVAILABLE"))
        val rows = groupDispositionItemTechnicalRows(rejected) + groupDispositionItemOutcomeTechnicalRows(rejected)

        val expanded = cTechnicalInfoDisclosure(rows, expanded = true)
        assertTrue(expanded.visibleText().contains("候选 ID：candidate-import-1"), expanded.visibleText())
        assertTrue(expanded.visibleText().contains("重复候选 ID：duplicate-1"), expanded.visibleText())
        assertTrue(expanded.visibleText().contains("诊断码：IMPORT_REVIEW_IDS_UNAVAILABLE"), expanded.visibleText())
    }

    private fun importRow(
        candidateKind: String = "ordinary_flow",
        occurredAt: String? = "2026-11-27T17:05:00Z",
        directionToken: String? = "out",
        statusToken: String? = "settled",
        duplicateStatus: ImportDuplicateStatus? = null,
        paymentProfileVariant: String? = null,
    ): ImportReviewRow =
        ImportReviewRow(
            candidateId = ImportCandidateId("candidate-import-1"),
            candidateKind = candidateKind,
            sourceInputRef = "pick-handle-1",
            amountMinor = 3_580L,
            currencyCode = "CNY",
            currencyPrecision = 2,
            occurredAt = occurredAt,
            directionToken = directionToken,
            statusToken = statusToken,
            fundingState = ImportFundingState.SETTLED,
            completeness = ImportCompleteness.VALID_COMPLETE,
            contentHash = "sha256:fixed-content-hash",
            candidateStatus = "pending_confirmation",
            requiresConfirmation = true,
            confidence = "high",
            duplicateStatus = duplicateStatus,
            paymentProfileVariant = paymentProfileVariant,
            paymentProfileAssetLegKindToken = null,
            paymentProfileCreditLegKindToken = null,
        )

    private fun groupItem(
        ordinal: Int = 3,
        outcome: ImportDuplicateGroupItemResult? = null,
    ): ImportDuplicateGroupItemState =
        ImportDuplicateGroupItemState(
            item =
                ImportDuplicateGroupDispositionItem(
                    candidateId = ImportCandidateId("candidate-import-1"),
                    duplicateCandidateId = ImportDuplicateCandidateId("duplicate-1"),
                    comparisonSnapshot = "{\"amount_minor\":3580}",
                    expectedComparisonFingerprint = "sha256:fixed-fingerprint",
                ),
            outcome = outcome,
            ordinal = ordinal,
        )
}
