package com.unifiedledger.ui

import com.unifiedledger.application.CreationEntry
import com.unifiedledger.application.P408MatchBasis
import com.unifiedledger.application.P408MatchCandidate
import com.unifiedledger.application.P408PostingFacts
import com.unifiedledger.application.P408ReconciliationStatus
import com.unifiedledger.application.P408TemporalComponents
import com.unifiedledger.application.P408TemporalEvidence
import com.unifiedledger.application.P801AccountReconciliationQueueRow
import com.unifiedledger.application.P806SnapshotFailure
import com.unifiedledger.application.TransactionAnnotationHistoryEntry
import com.unifiedledger.application.TransactionDetail
import com.unifiedledger.application.TransactionDetailLeg
import com.unifiedledger.application.TransactionDetailResult
import com.unifiedledger.application.TransactionReconciliationProjection
import com.unifiedledger.application.TransactionVersionHistoryResult
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
        assertEquals("2026-11-27T17:05:00Z", cBusinessTimeText(instant, UiPresentationProfile.LEGACY))
        assertEquals("2026-11-28T01:05", cBusinessTimeText(instant, UiPresentationProfile.ANDROID))
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
