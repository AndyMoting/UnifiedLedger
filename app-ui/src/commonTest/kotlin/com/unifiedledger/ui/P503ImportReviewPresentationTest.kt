package com.unifiedledger.ui

import com.unifiedledger.application.ExpenseCategoryOption
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportDuplicateCandidateId
import com.unifiedledger.application.ImportDuplicateReviewRow
import com.unifiedledger.application.ImportDuplicateReviewsResult
import com.unifiedledger.application.ImportDuplicateStatus
import com.unifiedledger.application.ImportFileIntakeOutcome
import com.unifiedledger.application.ImportFormatAvailability
import com.unifiedledger.application.ImportFormatCapabilities
import com.unifiedledger.application.ImportFormatId
import com.unifiedledger.application.ImportFormatUnavailableReason
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportIntakeBatchFailure
import com.unifiedledger.application.ImportIntakeRecordDisposition
import com.unifiedledger.application.ImportIntakeRecordSummary
import com.unifiedledger.application.ImportPlatformKind
import com.unifiedledger.application.ImportReviewRow
import com.unifiedledger.application.IncomeCategoryOption
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.TransactionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P7-04.C pure presentation tests (D-146; spec sections 3.3.1/3.1.1/4.5.3/4.6, P704SPEC-12).
 *
 * Pins the six-class classification matrix with the incomplete-first RED LINE (a NO_FUNDS
 * candidate's folded DEFERRED duplicate status must never read as 疑似重复待审核), the D03 batch
 * group boundary (同次选择句柄 + EXACT_BUSINESS_TUPLE + DEFERRED 入组；异次选择/异 kind/非 DEFERRED
 * 出组), the honest format matrix (every format appears with its declared availability; all
 * four are available on both platforms since the A-04.2 flip), the session summary/failure
 * copy, and the decision form face + validation (mixed 确认时间必填， E13).
 */
class P503ImportReviewPresentationTest {
    private val cny = CurrencyUnit("CNY", 2)

    private fun row(
        candidateStatus: String,
        duplicateStatus: ImportDuplicateStatus? = null,
        candidateKind: String = "ordinary_flow",
        fundingState: ImportFundingState = ImportFundingState.SETTLED,
        completeness: ImportCompleteness = ImportCompleteness.VALID_COMPLETE,
        paymentProfileVariant: String? = null,
        candidateId: String = "candidate-1",
        sourceInputRef: String = "pick-handle-1",
    ): ImportReviewRow =
        ImportReviewRow(
            candidateId = ImportCandidateId(candidateId),
            candidateKind = candidateKind,
            sourceInputRef = sourceInputRef,
            amountMinor = 3_580L,
            currencyCode = "CNY",
            currencyPrecision = 2,
            occurredAt = "2026-03-15T02:00:00Z",
            directionToken = "expense",
            statusToken = "trade_success",
            fundingState = fundingState,
            completeness = completeness,
            contentHash = "sha256:fixed-content-hash",
            candidateStatus = candidateStatus,
            requiresConfirmation = true,
            confidence = "high",
            duplicateStatus = duplicateStatus,
            paymentProfileVariant = paymentProfileVariant,
            paymentProfileAssetLegKindToken = null,
            paymentProfileCreditLegKindToken = null,
        )

    private fun reviewRow(
        duplicateId: String,
        kind: String = "EXACT_BUSINESS_TUPLE",
        latestStatus: ImportDuplicateStatus = ImportDuplicateStatus.DEFERRED,
    ): ImportDuplicateReviewRow =
        ImportDuplicateReviewRow(
            duplicateCandidateId = ImportDuplicateCandidateId(duplicateId),
            kind = kind,
            comparisonFingerprint = "sha256:fixed-fingerprint",
            comparisonSnapshot = "{\"amount_minor\":3580,\"occurred_at\":\"2026-03-15T02:00:00Z\"}",
            latestStatus = latestStatus,
            reviewDecision = null,
            reviewReasonToken = null,
            reviewedAt = null,
            possibleExistingSource = null,
        )

    // ---- classification matrix: six classes, one vector each (spec section 3.3.1) ----

    @Test
    fun pendingCandidateWithoutUnreviewedDuplicatesIsTheUserDecisionClass() {
        assertEquals(ImportCandidateClass.PENDING_USER_DECISION, classifyImportCandidate(row("pending_confirmation", duplicateStatus = null)))
    }

    @Test
    fun pendingCandidateWithADeferredDuplicateIsSuspectedDuplicatePendingReview() {
        assertEquals(
            ImportCandidateClass.SUSPECTED_DUPLICATE_PENDING_REVIEW,
            classifyImportCandidate(row("pending_confirmation", duplicateStatus = ImportDuplicateStatus.DEFERRED)),
        )
    }

    @Test
    fun pendingCandidateWithAConfirmedDuplicateIsBlocked() {
        assertEquals(
            ImportCandidateClass.DUPLICATE_CONFIRMED_BLOCKED,
            classifyImportCandidate(row("pending_confirmation", duplicateStatus = ImportDuplicateStatus.CONFIRMED_DUPLICATE)),
        )
    }

    @Test
    fun pendingCandidateWithARetainedSimilarVerdictIsRetainable() {
        assertEquals(
            ImportCandidateClass.RETAINABLE_SIMILAR,
            classifyImportCandidate(row("pending_confirmation", duplicateStatus = ImportDuplicateStatus.CONFIRMED_DISTINCT)),
        )
        assertEquals(
            ImportCandidateClass.RETAINABLE_SIMILAR,
            classifyImportCandidate(row("pending_confirmation", duplicateStatus = ImportDuplicateStatus.DISMISSED_LOOKALIKE)),
        )
    }

    @Test
    fun incompleteCandidateIsTheIncompleteSourceFactsClassRegardlessOfItsFoldedDuplicateStatus() {
        assertEquals(ImportCandidateClass.INCOMPLETE_SOURCE_FACTS, classifyImportCandidate(row("incomplete")))
    }

    @Test
    fun confirmedAndRejectedCandidatesAreTheResolvedHistoryClass() {
        assertEquals(ImportCandidateClass.RESOLVED, classifyImportCandidate(row("confirmed")))
        assertEquals(ImportCandidateClass.RESOLVED, classifyImportCandidate(row("rejected")))
    }

    /**
     * RED LINE（评审裁决）: a NO_FUNDS candidate carries a folded DEFERRED duplicate status (from
     * its CLOSED_OR_FAILED_NO_FUNDS group) — the incomplete-first rule must classify it 来源事实
     * 不完整, never 疑似重复待审核.
     */
    @Test
    fun noFundsCandidateWithAFoldedDeferredDuplicateIsIncompleteNotSuspectedDuplicate() {
        val noFundsCandidate =
            row(
                candidateStatus = "incomplete",
                duplicateStatus = ImportDuplicateStatus.DEFERRED,
                fundingState = ImportFundingState.NO_FUNDS,
                completeness = ImportCompleteness.VALID_INCOMPLETE,
            )
        val classified = classifyImportCandidate(noFundsCandidate)
        assertEquals(ImportCandidateClass.INCOMPLETE_SOURCE_FACTS, classified)
        assertFalse(classified.selectable)
        // R-Q10-3: the explanation names the missing source fact (NO_FUNDS), never a generic error.
        assertTrue(importIncompleteSourceFactsExplanation(noFundsCandidate).contains("NO_FUNDS"))
    }

    @Test
    fun selectableGateAllowsOnlyTheTwoUserDecidableClasses() {
        assertTrue(ImportCandidateClass.PENDING_USER_DECISION.selectable)
        assertTrue(ImportCandidateClass.RETAINABLE_SIMILAR.selectable)
        // 先审后勾 (R-Q10-1) + core block (D-104/D-105) + incomplete (R-Q10-3) + history.
        assertFalse(ImportCandidateClass.SUSPECTED_DUPLICATE_PENDING_REVIEW.selectable)
        assertFalse(ImportCandidateClass.DUPLICATE_CONFIRMED_BLOCKED.selectable)
        assertFalse(ImportCandidateClass.INCOMPLETE_SOURCE_FACTS.selectable)
        assertFalse(ImportCandidateClass.RESOLVED.selectable)
    }

    @Test
    fun aFoldedCoreRejectedVerdictStaysTheUserDecisionClassAndNeverBlocks() {
        // REJECTED 保留 core 值域、UI 不可达 (P704SPEC-09); nothing unreviewed remains and the
        // core blocks confirmation on CONFIRMED_DUPLICATE only.
        assertEquals(
            ImportCandidateClass.PENDING_USER_DECISION,
            classifyImportCandidate(row("pending_confirmation", duplicateStatus = ImportDuplicateStatus.REJECTED)),
        )
    }

    @Test
    fun unknownCandidateStatusTokenFailsLoud() {
        assertFailsWith<IllegalStateException> { classifyImportCandidate(row("unknown_status")) }
    }

    @Test
    fun listGroupsFollowTheFrozenDisplayOrderByClass() {
        val rows =
            listOf(
                row("confirmed", candidateId = "a"),
                row("pending_confirmation", duplicateStatus = ImportDuplicateStatus.DEFERRED, candidateId = "b"),
                row("pending_confirmation", candidateId = "c"),
                row("incomplete", candidateId = "d"),
            )
        val groups = importCandidateClassGroups(rows)
        assertEquals(
            listOf(
                ImportCandidateClass.PENDING_USER_DECISION,
                ImportCandidateClass.SUSPECTED_DUPLICATE_PENDING_REVIEW,
                ImportCandidateClass.INCOMPLETE_SOURCE_FACTS,
                ImportCandidateClass.RESOLVED,
            ),
            groups.map { it.classToken },
        )
        assertEquals(listOf("c"), groups[0].rows.map { it.candidateId.value })
        assertEquals(listOf("b"), groups[1].rows.map { it.candidateId.value })
    }

    @Test
    fun incompleteExplanationsNameTheMissingSourceFacts() {
        assertTrue(
            importIncompleteSourceFactsExplanation(
                row("incomplete", fundingState = ImportFundingState.NO_FUNDS, completeness = ImportCompleteness.VALID_INCOMPLETE),
            ).contains("已关闭或失败"),
        )
        assertTrue(
            importIncompleteSourceFactsExplanation(row("incomplete", candidateKind = "transfer_flow_missing_leg")).contains("缺少对方腿"),
        )
        assertTrue(
            importIncompleteSourceFactsExplanation(row("incomplete", fundingState = ImportFundingState.UNRESOLVED)).contains("状态未解"),
        )
        assertTrue(importIncompleteSourceFactsExplanation(row("incomplete")).contains("来源事实不完整"))
    }

    // ---- D03 group boundary (P704SPEC-12; spec section 3.3.1) ----

    @Test
    fun sameSessionHandleExactBusinessTupleDeferredJoinsTheGroup() {
        assertTrue(
            isImportDuplicateBatchGroupMember(
                sessionInputRef = "pick-handle-1",
                subjectInputRef = "pick-handle-1",
                reviewRow = reviewRow("dup-1"),
            ),
        )
    }

    @Test
    fun differentPickSessionHandleIsOutOfTheGroup() {
        assertFalse(
            isImportDuplicateBatchGroupMember(
                sessionInputRef = "pick-handle-1",
                subjectInputRef = "pick-handle-2",
                reviewRow = reviewRow("dup-1"),
            ),
        )
    }

    @Test
    fun closedOrFailedNoFundsKindIsOutOfTheGroup() {
        assertFalse(
            isImportDuplicateBatchGroupMember(
                sessionInputRef = "pick-handle-1",
                subjectInputRef = "pick-handle-1",
                reviewRow = reviewRow("dup-1", kind = "CLOSED_OR_FAILED_NO_FUNDS"),
            ),
        )
    }

    @Test
    fun nonDeferredLatestStatusIsOutOfTheGroup() {
        ImportDuplicateStatus.entries
            .filterNot { it == ImportDuplicateStatus.DEFERRED }
            .forEach { status ->
                assertFalse(
                    isImportDuplicateBatchGroupMember(
                        sessionInputRef = "pick-handle-1",
                        subjectInputRef = "pick-handle-1",
                        reviewRow = reviewRow("dup-1", latestStatus = status),
                    ),
                )
            }
    }

    @Test
    fun rowLevelGroupCoversOnlyTheCurrentSessionSuspectedDuplicates() {
        val rows =
            listOf(
                row("pending_confirmation", duplicateStatus = ImportDuplicateStatus.DEFERRED, candidateId = "in-session", sourceInputRef = "pick-handle-1"),
                row("pending_confirmation", duplicateStatus = ImportDuplicateStatus.DEFERRED, candidateId = "other-session", sourceInputRef = "pick-handle-2"),
                row("pending_confirmation", candidateId = "no-duplicate", sourceInputRef = "pick-handle-1"),
                row("incomplete", duplicateStatus = ImportDuplicateStatus.DEFERRED, candidateId = "incomplete-nofunds", sourceInputRef = "pick-handle-1", fundingState = ImportFundingState.NO_FUNDS, completeness = ImportCompleteness.VALID_INCOMPLETE),
            )
        val group = importDuplicateGroupRows(rows, "pick-handle-1")
        assertEquals(listOf("in-session"), group.map { it.candidateId.value })
    }

    @Test
    fun detailReviewTargetIsTheFirstExactBusinessTupleDeferredRow() {
        val reviews =
            ImportDuplicateReviewsResult.Reviews(
                listOf(
                    reviewRow("dup-reviewed", latestStatus = ImportDuplicateStatus.CONFIRMED_DISTINCT),
                    reviewRow("dup-pending"),
                    reviewRow("dup-nofunds", kind = "CLOSED_OR_FAILED_NO_FUNDS"),
                    reviewRow("dup-pending-2"),
                ),
            )
        assertEquals("dup-pending", importDuplicateReviewTarget(reviews)?.duplicateCandidateId?.value)
        assertNull(importDuplicateReviewTarget(ImportDuplicateReviewsResult.NoDuplicates))
        assertNull(importDuplicateReviewTarget(ImportDuplicateReviewsResult.Reviews(emptyList())))
    }

    // ---- group enumeration (P704C-SPEC-05: pure, typed-abort, never a silent partial group) ----

    @Test
    fun groupEnumerationCollectsGroupMembersInRowOrder() {
        val rows =
            listOf(
                row("pending_confirmation", candidateId = "candidate-a", duplicateStatus = ImportDuplicateStatus.DEFERRED, sourceInputRef = "pick-handle-1"),
                row("pending_confirmation", candidateId = "candidate-b", duplicateStatus = ImportDuplicateStatus.DEFERRED, sourceInputRef = "pick-handle-1"),
                row("pending_confirmation", candidateId = "candidate-other-session", duplicateStatus = ImportDuplicateStatus.DEFERRED, sourceInputRef = "pick-handle-2"),
            )
        val byId =
            mapOf(
                "candidate-a" to ImportDuplicateReviewsResult.Reviews(listOf(reviewRow("dup-a-1"), reviewRow("dup-a-2", kind = "CLOSED_OR_FAILED_NO_FUNDS"))),
                "candidate-b" to ImportDuplicateReviewsResult.Reviews(listOf(reviewRow("dup-b-1", latestStatus = ImportDuplicateStatus.CONFIRMED_DISTINCT), reviewRow("dup-b-2"))),
            )
        val enumeration =
            enumerateImportDuplicateGroupItems("pick-handle-1", rows) { candidateId ->
                byId[candidateId.value] ?: ImportDuplicateReviewsResult.Absent
            }
        val ready = assertIs<ImportDuplicateGroupEnumeration.Ready>(enumeration)
        // Only the P704SPEC-12 boundary members: the non-DEFERRED and non-EXACT rows are filtered
        // out per candidate, and the other session's candidate never loads.
        assertEquals(listOf("dup-a-1", "dup-b-2"), ready.items.map { it.duplicateCandidateId.value })
        assertEquals("candidate-a", ready.items[0].candidateId.value)
        assertEquals("sha256:fixed-fingerprint", ready.items[0].expectedComparisonFingerprint)
    }

    @Test
    fun groupEnumerationAbortsTypedOnAnyUnavailableRead() {
        val rows =
            listOf(
                row("pending_confirmation", candidateId = "candidate-a", duplicateStatus = ImportDuplicateStatus.DEFERRED, sourceInputRef = "pick-handle-1"),
                row("pending_confirmation", candidateId = "candidate-b", duplicateStatus = ImportDuplicateStatus.DEFERRED, sourceInputRef = "pick-handle-1"),
            )
        val byId =
            mapOf(
                "candidate-a" to ImportDuplicateReviewsResult.Reviews(listOf(reviewRow("dup-a-1"))),
                "candidate-b" to ImportDuplicateReviewsResult.Unavailable,
            )
        val enumeration =
            enumerateImportDuplicateGroupItems("pick-handle-1", rows) { candidateId ->
                byId[candidateId.value] ?: ImportDuplicateReviewsResult.Absent
            }
        // The FIRST typed read failure aborts wholesale even though candidate-a already yielded a
        // member — never a silently partial group (the host surfaces the typed banner instead).
        assertIs<ImportDuplicateGroupEnumeration.ReadFailed>(enumeration)
    }

    @Test
    fun groupEnumerationIgnoresNoDuplicatesAndAbsentWithoutFailing() {
        val rows = listOf(row("pending_confirmation", candidateId = "candidate-a", duplicateStatus = ImportDuplicateStatus.DEFERRED, sourceInputRef = "pick-handle-1"))
        val enumeration =
            enumerateImportDuplicateGroupItems("pick-handle-1", rows) { candidateId ->
                when (candidateId.value) {
                    "candidate-a" -> ImportDuplicateReviewsResult.NoDuplicates
                    else -> ImportDuplicateReviewsResult.Absent
                }
            }
        val ready = assertIs<ImportDuplicateGroupEnumeration.Ready>(enumeration)
        assertEquals(emptyList(), ready.items)
    }

    // ---- Option A flattened disposition-card copy (header/footer/item presentation functions) ----

    @Test
    fun theGroupDispositionCardHeaderAndFooterExposeInputRefAndItemCount() {
        // The header copy pins the open card's title + the per-item manual-review disclosure with
        // the exact item count; the footer copy pins the confirm/close affordance row.
        assertEquals(
            "整组标记为重复（逐条核对）",
            groupDispositionCardHeaderTitle(),
        )
        assertEquals(
            "将逐条提交人工审核判定为重复，每条独立生效；某一条失败不影响其余各条。共 7 条。",
            groupDispositionCardHeaderDisclosure(itemCount = 7),
        )
        assertEquals("本次会话：pick-handle-1", groupDispositionCardHeaderSessionText(inputRef = "pick-handle-1"))
        assertEquals("确认整组标记", groupDispositionCardFooterConfirmText())
        assertEquals("关闭", groupDispositionCardFooterCloseText())
    }

    @Test
    fun theGroupDispositionItemCarriesStateSnapshotAndOutcome() {
        val pending =
            ImportDuplicateGroupItemState(
                item =
                    ImportDuplicateGroupDispositionItem(
                        candidateId = ImportCandidateId("candidate-dup-1"),
                        duplicateCandidateId = ImportDuplicateCandidateId("dup-1"),
                        comparisonSnapshot = "{\"amount_minor\":3580}",
                        expectedComparisonFingerprint = "sha256:fixed-fingerprint",
                    ),
            )
        // 待处置 / 已标记 / 失败: the per-item copy mirrors the pre-lazy card's outcome lines.
        assertEquals("候选 candidate-dup-1", groupDispositionItemCandidateText(pending))
        assertEquals("比较信息：{\"amount_minor\":3580}", groupDispositionItemComparisonText(pending))
        assertEquals("待处置", groupDispositionItemOutcomeText(pending))
        val reviewed = pending.copy(outcome = ImportDuplicateGroupItemResult.Reviewed(ImportDuplicateStatus.CONFIRMED_DUPLICATE))
        assertEquals("已标记：CONFIRMED_DUPLICATE", groupDispositionItemOutcomeText(reviewed))
        val rejected = pending.copy(outcome = ImportDuplicateGroupItemResult.Rejected("SPINE_DUPLICATE_NOT_PENDING"))
        assertEquals("失败（SPINE_DUPLICATE_NOT_PENDING）", groupDispositionItemOutcomeText(rejected))
    }

    // ---- possibly-existing source facts copy (P704C-SPEC-04; spec section 4.5.4) ----

    @Test
    fun possibleExistingSourceFactsRenderExactly() {
        val text =
            importPossibleExistingSourceText(
                com.unifiedledger.application.ImportDuplicatePossibleExistingSourceFacts(
                    sourceId = com.unifiedledger.application.ImportSourceId("source-existing-1"),
                    amountMinor = 3_580L,
                    currencyCode = "CNY",
                    currencyPrecision = 2,
                    occurredAt = "2026-03-15T02:00:00+08:00",
                    directionToken = "out",
                    statusToken = "trade_success",
                ),
            )
        assertTrue(text.contains("35.80 CNY"))
        assertTrue(text.contains("2026-03-15T02:00:00+08:00"))
        assertTrue(text.contains("方向 out"))
        assertTrue(text.contains("状态 trade_success"))
        // D-105: a CLOSED_OR_FAILED_NO_FUNDS row has no directed target — the explicit copy.
        assertTrue(importPossibleExistingSourceText(null).contains("无指向目标"))
    }

    // ---- format capability matrix (R-Q08-3; A-04.2 flip: all four formats available on Android) ----

    @Test
    fun androidShowsAllFourEntriesAllAvailable() {
        val entries = importFormatEntries(ImportPlatformKind.ANDROID)
        assertEquals(ImportFormatCapabilities.ALL.map { it.identifier }, entries.map { it.descriptor.identifier })
        // A-04.1's instrumented verification flipped the CCB XLS Android matrix unit to
        // AVAILABLE (A-04.2); every matrix format is now offered as available on Android.
        assertTrue(entries.all { it.availability == ImportFormatAvailability.AVAILABLE })
        assertEquals(4, entries.count { it.availability == ImportFormatAvailability.AVAILABLE })
    }

    @Test
    fun desktopOffersAllFourFormatsAsAvailable() {
        val entries = importFormatEntries(ImportPlatformKind.DESKTOP)
        assertTrue(entries.all { it.availability == ImportFormatAvailability.AVAILABLE })
        assertEquals(4, entries.size)
    }

    // ---- session summary / typed failure copy (spec sections 6.1/4.6) ----

    private fun record(
        ordinal: Int,
        disposition: ImportIntakeRecordDisposition,
    ): ImportIntakeRecordSummary = ImportIntakeRecordSummary(recordOrdinal = ordinal, disposition = disposition)

    @Test
    fun acceptedSessionSummaryCountsEveryDisposition() {
        val records =
            listOf(
                record(0, ImportIntakeRecordDisposition.INTAKE_ACCEPTED),
                record(1, ImportIntakeRecordDisposition.INTAKE_NO_CHANGE),
                record(2, ImportIntakeRecordDisposition.PARSER_REJECTED),
                record(3, ImportIntakeRecordDisposition.INTAKE_REJECTED),
            )
        val session =
            ImportIntakeSessionSummary(
                displayName = "synthetic-bill.csv",
                inputRef = "pick-handle-1",
                outcome =
                    ImportIntakePipelineOutcome.Intaken(
                        ImportFileIntakeOutcome.Accepted(
                            records = records,
                            newCandidateIds = listOf(ImportCandidateId("candidate-1")),
                        ),
                    ),
            )
        val lines = importIntakeSessionLines(session)
        assertEquals("最近导入：synthetic-bill.csv", lines[0])
        assertTrue(lines[1].contains("新增 1"))
        assertTrue(lines[1].contains("等价重放 1"))
        assertTrue(lines[1].contains("解析拒绝 1"))
        assertTrue(lines[1].contains("接治拒绝 1"))
        // 逐项结果: every record line carries its ordinal and verdict.
        val recordLines = importIntakeRecordLines(records)
        assertEquals(4, recordLines.size)
        assertTrue(recordLines[0].contains("第 0 条：已接治"))
        assertTrue(recordLines[2].contains("单行无法解析"))
    }

    @Test
    fun recordLineRenderingDisclosesItsCap() {
        val records = (0..250).map { record(it, ImportIntakeRecordDisposition.INTAKE_NO_CHANGE) }
        val lines = importIntakeRecordLines(records, displayLimit = 200)
        assertEquals(200, lines.size - 1)
        assertTrue(lines.last().contains("其余 51 条"))
    }

    @Test
    fun typedBatchFailuresCarryTheirFrozenCopy() {
        val pendingVerification =
            importIntakeFailureText(
                ImportIntakeBatchFailure.FormatUnavailable(
                    ImportFormatId("ccb-xls"),
                    "建设银行账单（XLS）",
                    ImportFormatUnavailableReason.PENDING_DEVICE_VERIFICATION,
                ),
            )
        assertTrue(pendingVerification.contains("待运行验证"))
        val charset =
            importIntakeFailureText(
                ImportIntakeBatchFailure.FormatUnavailable(
                    ImportFormatId("alipay-csv"),
                    "支付宝账单（CSV）",
                    ImportFormatUnavailableReason.CHARSET_UNSUPPORTED,
                ),
            )
        assertTrue(charset.contains("GB18030"))
        val overLimit = importIntakeFailureText(ImportIntakeBatchFailure.BatchExceedsLimit(actualAcceptedRecords = 10_001))
        assertTrue(overLimit.contains("10,000"))
        assertTrue(overLimit.contains("10001"))
        assertTrue(overLimit.contains("未写入任何记录"))
        val parserRejected =
            importIntakeFailureText(
                ImportIntakeBatchFailure.ParserRejected(
                    com.unifiedledger.application.ImportBatchParserDiagnostic(
                        code = "WECHAT_UNSUPPORTED_INPUT",
                        severity = "fatal",
                        scope = "container",
                        inputRef = "pick-handle-1",
                        recordOrdinal = null,
                        fieldRole = null,
                    ),
                ),
            )
        assertTrue(parserRejected.contains("WECHAT_UNSUPPORTED_INPUT"))
    }

    @Test
    fun l0FailuresAndNoticesHaveDistinctTypedCopy() {
        val exceeds = importIntakeSessionLines(ImportIntakeSessionSummary("big.bin", "pick-handle-1", ImportIntakePipelineOutcome.ReadExceedsLimit(17_000_000)))
        assertTrue(exceeds.any { it.contains("16 MiB") })
        assertTrue(exceeds.any { it.contains("17000000") })
        val readFailed = importIntakeSessionLines(ImportIntakeSessionSummary("gone.bin", "pick-handle-1", ImportIntakePipelineOutcome.ReadFailed(ImportPickReadFailure.STREAM_OPEN_FAILED)))
        assertTrue(readFailed.any { it.contains("读取失败") })
        assertTrue(importReviewNoticeText(ImportReviewNotice.ReviewReadFailed).contains("保留上一次成功加载的清单"))
        assertTrue(importReviewNoticeText(ImportReviewNotice.ReviewRejected("SPINE_DUPLICATE_NOT_PENDING")).contains("SPINE_DUPLICATE_NOT_PENDING"))
        // P704D-SPEC-02: the UI-owned infrastructure-failure banner carries the code and zero
        // writes, and never claims 审核未通过 (the core never returned a verdict).
        val submitFailed = importReviewNoticeText(ImportReviewNotice.ReviewSubmitFailed(IMPORT_REVIEW_SUBMIT_UNAVAILABLE))
        assertTrue(submitFailed.contains(IMPORT_REVIEW_SUBMIT_UNAVAILABLE))
        assertTrue(submitFailed.contains("未写入任何记录"))
        assertFalse(submitFailed.contains("审核未通过"))
    }

    // ---- D06 sanitization: failure/diagnostic copy never echoes the pick's sensitive values ----

    /**
     * D06 (plan section 6.3; spec sections 6.4/7 D06): the sanitized failure and diagnostic
     * lines never leak the original file name, a URI, raw row content, personal identifiers or
     * underlying exception text. The display name's ONLY sanctioned surface is the in-session
     * summary line（文件显示名仅出现于当次会话摘要；the D06 acceptance row exempts 会话显示）， so the
     * absence assertions cover every line after that one plus the notice banner and the
     * per-record lines — never the 最近导入 session line itself.
     */
    @Test
    fun sanitizedFailureAndDiagnosticCopyNeverLeaksThePickSensitiveValues() {
        val sensitiveName = "张三"
        val sensitiveUri = "file:///vault/synthetic-user/个人账单-张三-2026-08.csv"
        val sensitiveFileName = "个人账单-$sensitiveName-2026-08.csv"
        val rawRow = "2026-09-01 08:30:00,张三,消费,6222020200112233445,12.34"
        val exceptionText = "FileNotFoundException: $sensitiveUri (系统找不到指定的路径)"
        val tokens = listOf(sensitiveName, sensitiveUri, sensitiveFileName, rawRow, exceptionText, "6222020200112233445")

        fun assertSanitized(lines: List<String>) = tokens.forEach { token -> assertTrue(lines.none { it.contains(token) }) }

        // (1) Every pipeline outcome renders the display name on the session line only; every
        // failure/diagnostic line after it stays token-free.
        val outcomes =
            listOf(
                ImportIntakePipelineOutcome.ReadFailed(ImportPickReadFailure.STREAM_OPEN_FAILED),
                ImportIntakePipelineOutcome.ReadFailed(ImportPickReadFailure.STREAM_READ_FAILED),
                ImportIntakePipelineOutcome.ReadExceedsLimit(actualBytes = 17_000_000),
                ImportIntakePipelineOutcome.Intaken(
                    ImportFileIntakeOutcome.Rejected(
                        ImportIntakeBatchFailure.ParserRejected(
                            com.unifiedledger.application.ImportBatchParserDiagnostic(
                                code = "SPINE_CMB_UNKNOWN_TOKEN",
                                severity = "fatal",
                                scope = "record",
                                inputRef = "pick-handle-1",
                                recordOrdinal = 3,
                                fieldRole = "tx_type",
                            ),
                        ),
                    ),
                ),
                ImportIntakePipelineOutcome.Intaken(
                    ImportFileIntakeOutcome.Accepted(
                        records =
                            listOf(
                                ImportIntakeRecordSummary(
                                    recordOrdinal = 0,
                                    disposition = ImportIntakeRecordDisposition.PARSER_REJECTED,
                                    diagnosticCode = "SPINE_CMB_UNKNOWN_TOKEN",
                                ),
                                ImportIntakeRecordSummary(
                                    recordOrdinal = 1,
                                    disposition = ImportIntakeRecordDisposition.INTAKE_ACCEPTED,
                                ),
                            ),
                        newCandidateIds = listOf(ImportCandidateId("candidate-1")),
                    ),
                ),
            )
        outcomes.forEach { outcome ->
            val lines = importIntakeSessionLines(ImportIntakeSessionSummary(sensitiveFileName, "pick-handle-1", outcome))
            // The spec-sanctioned session display line: the ONLY surface allowed to show the name.
            assertEquals("最近导入：$sensitiveFileName", lines.first())
            assertSanitized(lines.drop(1))
        }

        // (2) The intake-failure notice banner (a failure surface, not the session summary)
        // never echoes the display name or any injected token.
        assertSanitized(
            listOf(
                importReviewNoticeText(ImportReviewNotice.IntakeFailed(ImportIntakePipelineOutcome.ReadFailed(ImportPickReadFailure.STREAM_OPEN_FAILED))),
                importReviewNoticeText(
                    ImportReviewNotice.IntakeFailed(
                        ImportIntakePipelineOutcome.Intaken(
                            ImportFileIntakeOutcome.Rejected(
                                ImportIntakeBatchFailure.BatchExceedsLimit(actualAcceptedRecords = 10_001),
                            ),
                        ),
                    ),
                ),
                importPickReadFailureText(ImportPickReadFailure.STREAM_READ_FAILED),
            ),
        )

        // (3) Per-record lines carry the ordinal and the diagnostic code token only.
        val records =
            listOf(
                ImportIntakeRecordSummary(
                    recordOrdinal = 0,
                    disposition = ImportIntakeRecordDisposition.PARSER_REJECTED,
                    diagnosticCode = "SPINE_CMB_UNKNOWN_TOKEN",
                ),
                ImportIntakeRecordSummary(
                    recordOrdinal = 1,
                    disposition = ImportIntakeRecordDisposition.INTAKE_REJECTED,
                    diagnosticCode = "SPINE_DUPLICATE_NOT_CONFIRMABLE",
                ),
            )
        assertSanitized(importIntakeRecordLines(records))
    }

    // ---- decision form face (spec section 4.5.3; ImportConfirmDecisionFields mapping) ----

    @Test
    fun formFacesMapTheSixDecisionFieldVariants() {
        val ordinary = importDecisionFormFace(row("pending_confirmation", candidateKind = "ordinary_flow"))
        assertTrue(ordinary!!.requiresCategory && ordinary.requiresFundingAccount)
        assertFalse(ordinary.requiresFromAccount || ordinary.requiresToAccount || ordinary.requiresCreditAccount || ordinary.requiresConfirmedAt)

        val transfer = importDecisionFormFace(row("pending_confirmation", candidateKind = "transfer_flow"))
        assertTrue(transfer!!.requiresFromAccount && transfer.requiresToAccount)
        assertFalse(transfer.requiresCategory || transfer.requiresCreditAccount)

        val creditDirect = importDecisionFormFace(row("pending_confirmation", candidateKind = "credit_expense", paymentProfileVariant = "credit_expense_direct"))
        assertTrue(creditDirect!!.requiresCategory && creditDirect.requiresCreditAccount)
        assertFalse(creditDirect.requiresOriginalTransaction)

        val creditRefund = importDecisionFormFace(row("pending_confirmation", candidateKind = "credit_expense", paymentProfileVariant = "credit_expense_refund"))
        assertTrue(creditRefund!!.requiresCategory && creditRefund.requiresCreditAccount && creditRefund.requiresOriginalTransaction)

        val repayment = importDecisionFormFace(row("pending_confirmation", candidateKind = "credit_repayment"))
        assertTrue(repayment!!.requiresAssetAccount && repayment.requiresCreditAccount)
        assertFalse(repayment.requiresCategory)

        val mixed = importDecisionFormFace(row("pending_confirmation", candidateKind = "mixed_payment", paymentProfileVariant = "mixed_payment"))
        assertTrue(mixed!!.requiresCategory && mixed.requiresAssetAccount && mixed.requiresCreditAccount)
        assertTrue(mixed.legAmountsEditable && mixed.requiresConfirmedAt)

        assertNull(importDecisionFormFace(row("incomplete", candidateKind = "transfer_flow_missing_leg")))
    }

    // ---- FIX-INCOME-FACE-1 (D-154): the decision face's direction-matched category source ----

    private val incomeCategoryOptions =
        listOf(
            IncomeCategoryOption(
                categoryId = CategoryId("category-income-salary"),
                parentCategoryId = CategoryId("category-income"),
                label = "Salary",
                postingAccountId = AccountId("account-receiving"),
            ),
        )
    private val expenseCategoryOptions =
        listOf(
            ExpenseCategoryOption(
                categoryId = CategoryId("category-expense-food"),
                parentCategoryId = CategoryId("category-expense"),
                label = "Food",
                postingAccountId = AccountId("account-expense"),
            ),
        )

    @Test
    fun inDirectionDecisionFaceRendersIncomeCategoryOptionsAndNotExpenseOptions() {
        val options = importDecisionCategoryOptions("in", incomeCategoryOptions, expenseCategoryOptions)
        assertEquals(listOf("category-income-salary"), options.map { it.categoryId.value })
        assertEquals(listOf("Salary"), options.map { it.label })
        // The expense source must never leak into an in-direction face: the pre-fix surface made
        // every in-direction candidate unconfirmable (IncomeCategoryRequired at the spine).
        assertFalse(options.any { it.categoryId == expenseCategoryOptions[0].categoryId })
    }

    @Test
    fun outDirectionDecisionFaceRendersExpenseCategoryOptions() {
        val options = importDecisionCategoryOptions("out", incomeCategoryOptions, expenseCategoryOptions)
        assertEquals(listOf("category-expense-food"), options.map { it.categoryId.value })
        assertFalse(options.any { it.categoryId == incomeCategoryOptions[0].categoryId })
    }

    @Test
    fun nullDirectionTokenKeepsTheExpenseCategorySource() {
        // directionToken is nullable on the read row; a missing token must never flip the face
        // to the income source (the pre-fix behavior for an undirected row).
        val options = importDecisionCategoryOptions(null, incomeCategoryOptions, expenseCategoryOptions)
        assertEquals(listOf("category-expense-food"), options.map { it.categoryId.value })
    }

    // ---- decision form validation (P503DraftValidation pattern; E13 mixed gate) ----

    private val validation = P503ImportDecisionValidation(ParseManualExpenseAmount())
    private val mixedFace = importDecisionFormFace(row("pending_confirmation", candidateKind = "mixed_payment", paymentProfileVariant = "mixed_payment"))!!
    private val ordinaryFace = importDecisionFormFace(row("pending_confirmation", candidateKind = "ordinary_flow"))!!

    @Test
    fun mixedConfirmationTimeIsRequiredAndBlankLegAmountsAreValidNulls() {
        val blankLegs =
            ImportDecisionDraft(
                categoryId = CategoryId("category-food"),
                assetAccountId = AccountId("asset-1"),
                creditLiabilityAccountId = AccountId("credit-1"),
            )
        assertFalse(validation.isSubmittable(blankLegs, mixedFace, cny))
        assertTrue(validation.errors(blankLegs, mixedFace, cny).missingConfirmedAt)

        val withTime = blankLegs.copy(confirmedAtText = "2026-09-14T08:00:00Z")
        assertTrue(validation.isSubmittable(withTime, mixedFace, cny))
    }

    @Test
    fun mixedLegAmountTextsParseOrReportAFormatError() {
        val draft =
            ImportDecisionDraft(
                categoryId = CategoryId("category-food"),
                assetAccountId = AccountId("asset-1"),
                creditLiabilityAccountId = AccountId("credit-1"),
                assetLegAmountText = "12.34",
                creditLegAmountText = "1/3",
                confirmedAtText = "2026-09-14T08:00:00Z",
            )
        val errors = validation.errors(draft, mixedFace, cny)
        assertFalse(errors.missingConfirmedAt)
        assertNull(errors.assetLegAmountError)
        assertIs<com.unifiedledger.application.ManualExpenseAmountFormatError>(errors.creditLegAmountError)
        assertFalse(validation.isSubmittable(draft, mixedFace, cny))
    }

    @Test
    fun ordinaryFormRequiresCategoryAndFundingAccount() {
        val empty = ImportDecisionDraft()
        val errors = validation.errors(empty, ordinaryFace, cny)
        assertTrue(errors.missingCategory && errors.missingFundingAccount)
        assertFalse(validation.isSubmittable(empty, ordinaryFace, cny))

        val complete =
            ImportDecisionDraft(
                categoryId = CategoryId("category-food"),
                fundingAccountId = AccountId("asset-1"),
            )
        assertTrue(validation.isSubmittable(complete, ordinaryFace, cny))
    }

    @Test
    fun originalTransactionFieldIsRequiredForTheRefundVariantOnly() {
        val refundFace = importDecisionFormFace(row("pending_confirmation", candidateKind = "credit_expense", paymentProfileVariant = "credit_expense_refund"))!!
        val draft =
            ImportDecisionDraft(
                categoryId = CategoryId("category-food"),
                creditLiabilityAccountId = AccountId("credit-1"),
            )
        assertTrue(validation.errors(draft, refundFace, cny).missingOriginalTransaction)
        assertFalse(validation.errors(draft.copy(originalTransactionId = TransactionId("tx-1")), refundFace, cny).hasErrors)
    }
}
