package com.unifiedledger.ui

import com.unifiedledger.application.CreationEntry
import com.unifiedledger.application.P408EvidenceResponsibility
import com.unifiedledger.application.P408MatchBasis
import com.unifiedledger.application.P408MatchCandidate
import com.unifiedledger.application.P408PostingFacts
import com.unifiedledger.application.P408ReconciliationStatus
import com.unifiedledger.application.P408TemporalComponents
import com.unifiedledger.application.P408TemporalEvidence
import com.unifiedledger.application.P801AccountReconciliationQueueRow
import com.unifiedledger.application.P801ReconciledEvidenceSource
import com.unifiedledger.application.P801ReconciledLegAudit
import com.unifiedledger.application.P801ReconciledLegDecision
import com.unifiedledger.application.P801ReconciledLegEvidenceLink
import com.unifiedledger.application.P801ReconciledLegLinkHistoryEntry
import com.unifiedledger.application.P801ReconciledLegReconciliation
import com.unifiedledger.application.P801ReconciledLegStatusEntry
import com.unifiedledger.application.P806SnapshotFailure
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

        assertEquals(
            listOf("所见版本 ID" to "version-7", "请求 ID" to "request-9"),
            cTransactionAnnotationTechnicalRows(entry).map { row -> row.label to row.value },
        )
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
    fun targetAccountShowsTheQueueLineThenTheChosenNameThenAnExplicitAbsence() {
        assertEquals("现金账户", reconciliationTargetAccountText("现金账户", "忽略"))
        assertEquals("信用卡", reconciliationTargetAccountText(null, "信用卡"))
        assertEquals("目标账户未加载（账户名不可用）", reconciliationTargetAccountText(null, null))
        assertEquals("目标账户未加载（账户名不可用）", reconciliationTargetAccountText("  ", " "))
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
}
