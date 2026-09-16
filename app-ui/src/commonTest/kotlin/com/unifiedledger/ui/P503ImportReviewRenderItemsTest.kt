package com.unifiedledger.ui

import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportDuplicateCandidateId
import com.unifiedledger.application.ImportDuplicateStatus
import com.unifiedledger.application.ImportFileIntakeOutcome
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportIntakeRecordDisposition
import com.unifiedledger.application.ImportIntakeRecordSummary
import com.unifiedledger.application.ImportPlatformKind
import com.unifiedledger.application.ImportRequestId
import com.unifiedledger.application.ImportReviewRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * FOUND-P704-D01-01 render-model pins (the IMPORT overview LazyColumn fix).
 *
 * The overview previously composed every candidate row eagerly inside a scrolling Column, which
 * fatally OOM'd at the registered intake cap (10,000 candidates) on device. These tests pin the
 * pure flat render model [importReviewRenderItems] that the LazyColumn now consumes: the exact
 * section mapping of the frozen presentation semantics (six-class order, empty-group omission,
 * 待确认 empty states, notice banner placement, session summary with its disclosed 200-record cap,
 * batch result per-item expansion with Unknown check entries), the selection propagation into
 * candidate items, and the stable/unique key contract (a duplicate LazyColumn key crashes at
 * runtime with "Key was already used"). All fixtures are fully synthetic (D06).
 */
class P503ImportReviewRenderItemsTest {
    private fun row(
        candidateId: String,
        candidateStatus: String = "pending_confirmation",
        duplicateStatus: ImportDuplicateStatus? = null,
        candidateKind: String = "ordinary_flow",
        fundingState: ImportFundingState = ImportFundingState.SETTLED,
        completeness: ImportCompleteness = ImportCompleteness.VALID_COMPLETE,
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
            paymentProfileVariant = null,
            paymentProfileAssetLegKindToken = null,
            paymentProfileCreditLegKindToken = null,
        )

    private fun acceptedSession(
        recordCount: Int,
        inputRef: String = "pick-handle-1",
    ): ImportIntakeSessionSummary =
        ImportIntakeSessionSummary(
            displayName = "synthetic-bill.csv",
            inputRef = inputRef,
            outcome =
                ImportIntakePipelineOutcome.Intaken(
                    ImportFileIntakeOutcome.Accepted(
                        records =
                            (0 until recordCount).map {
                                ImportIntakeRecordSummary(
                                    recordOrdinal = it,
                                    disposition = ImportIntakeRecordDisposition.INTAKE_ACCEPTED,
                                )
                            },
                        newCandidateIds = emptyList(),
                    ),
                ),
        )

    private fun batchResultSummary(): ImportBatchResultSummary =
        ImportBatchResultSummary(
            confirmedAt = "2026-09-15T10:00:00Z",
            items =
                listOf(
                    ImportBatchResultItem(
                        ImportBatchItem(ImportCandidateId("candidate-rejected"), ImportRequestId("request-1")),
                        ImportBatchItemOutcome.Rejected("SPINE_DUPLICATE_NOT_PENDING"),
                    ),
                    ImportBatchResultItem(
                        ImportBatchItem(ImportCandidateId("candidate-conflict"), ImportRequestId("request-2")),
                        ImportBatchItemOutcome.CheckConflict("EQUIVALENCE_BROKEN"),
                    ),
                    ImportBatchResultItem(
                        ImportBatchItem(ImportCandidateId("candidate-skipped"), ImportRequestId("request-3")),
                        ImportBatchItemOutcome.Skipped(IMPORT_BATCH_DECISION_INCOMPLETE),
                    ),
                    ImportBatchResultItem(
                        ImportBatchItem(ImportCandidateId("candidate-unknown"), ImportRequestId("request-4")),
                        ImportBatchItemOutcome.Unknown,
                    ),
                ),
        )

    private fun groupDispositionPage(inputRef: String = "pick-handle-1"): ImportDuplicateGroupDispositionPage =
        ImportDuplicateGroupDispositionPage(
            inputRef = inputRef,
            items =
                listOf(
                    ImportDuplicateGroupItemState(
                        item =
                            ImportDuplicateGroupDispositionItem(
                                candidateId = ImportCandidateId("candidate-dup-1"),
                                duplicateCandidateId = ImportDuplicateCandidateId("dup-1"),
                                comparisonSnapshot = "{\"amount_minor\":3580}",
                                expectedComparisonFingerprint = "sha256:fixed-fingerprint",
                            ),
                    ),
                ),
        )

    // ---- empty overview shapes (view == null vs rows empty) ----

    @Test
    fun nullViewRendersShellWithUnloadedEmptyState() {
        val items = importReviewRenderItems(null, ImportPlatformKind.ANDROID)
        assertIs<ImportReviewRenderItem.TitleBar>(items.first())
        // The format capability matrix renders regardless of the projection load (不静默省略).
        assertTrue(items.any { it is ImportReviewRenderItem.FormatSectionHeader })
        assertEquals(
            importFormatEntries(ImportPlatformKind.ANDROID).size,
            items.count { it is ImportReviewRenderItem.FormatEntry },
        )
        // 待确认草稿 header stays, followed by the unloaded empty state (导入清单尚未加载。).
        val pendingHeaderIndex = items.indexOfFirst { it is ImportReviewRenderItem.PendingSectionHeader }
        val emptyIndex = items.indexOfFirst { it is ImportReviewRenderItem.UnloadedEmptyState }
        assertTrue(pendingHeaderIndex >= 0 && emptyIndex == pendingHeaderIndex + 1)
        assertTrue(items.none { it is ImportReviewRenderItem.NoCandidatesEmptyState })
        assertTrue(items.none { it is ImportReviewRenderItem.CandidateItem })
        assertTrue(items.none { it is ImportReviewRenderItem.NoticeBanner })
        assertTrue(items.none { it is ImportReviewRenderItem.IntakeSessionHeader })
    }

    @Test
    fun emptyRowsWithoutNoticeRenderTheNoCandidatesEmptyState() {
        val items = importReviewRenderItems(ImportReviewView(), ImportPlatformKind.ANDROID)
        assertTrue(items.any { it is ImportReviewRenderItem.NoCandidatesEmptyState })
        assertTrue(items.none { it is ImportReviewRenderItem.UnloadedEmptyState })
        assertTrue(items.none { it is ImportReviewRenderItem.GroupHeader })
        assertTrue(items.none { it is ImportReviewRenderItem.CandidateItem })
    }

    // ---- notice banner presence and placement ----

    @Test
    fun anActiveNoticeRendersTheBannerRightAfterTheTitleBar() {
        val view = ImportReviewView(notice = ImportReviewNotice.ReviewReadFailed)
        val items = importReviewRenderItems(view, ImportPlatformKind.ANDROID)
        assertIs<ImportReviewRenderItem.TitleBar>(items[0])
        val banner = assertIs<ImportReviewRenderItem.NoticeBanner>(items[1])
        assertEquals(ImportReviewNotice.ReviewReadFailed, banner.notice)
        // Notice + empty rows: the 暂无导入候选 empty state is suppressed (the banner explains).
        assertTrue(items.none { it is ImportReviewRenderItem.NoCandidatesEmptyState })
        assertTrue(items.none { it is ImportReviewRenderItem.UnloadedEmptyState })
    }

    @Test
    fun noticeAbsentOmitsTheBanner() {
        val items = importReviewRenderItems(ImportReviewView(), ImportPlatformKind.ANDROID)
        assertTrue(items.none { it is ImportReviewRenderItem.NoticeBanner })
    }

    // ---- six-class frozen order, empty-group omission, header counts ----

    @Test
    fun groupsFollowTheFrozenDisplayOrderWithEmptyGroupsOmittedAndCountingHeaders() {
        val rows =
            listOf(
                row("resolved-1", candidateStatus = "confirmed"),
                row("blocked-1", duplicateStatus = ImportDuplicateStatus.CONFIRMED_DUPLICATE),
                row("pending-1"),
                row("pending-2"),
                row("incomplete-1", candidateStatus = "incomplete", completeness = ImportCompleteness.VALID_INCOMPLETE),
            )
        val items = importReviewRenderItems(ImportReviewView(rows = rows), ImportPlatformKind.ANDROID)
        val listItems = items.filter { it is ImportReviewRenderItem.GroupHeader || it is ImportReviewRenderItem.CandidateItem }
        assertEquals(
            listOf(
                ImportReviewRenderItem.GroupHeader(ImportCandidateClass.PENDING_USER_DECISION, 2),
                ImportReviewRenderItem.CandidateItem(rows[2], selected = false),
                ImportReviewRenderItem.CandidateItem(rows[3], selected = false),
                ImportReviewRenderItem.GroupHeader(ImportCandidateClass.DUPLICATE_CONFIRMED_BLOCKED, 1),
                ImportReviewRenderItem.CandidateItem(rows[1], selected = false),
                ImportReviewRenderItem.GroupHeader(ImportCandidateClass.INCOMPLETE_SOURCE_FACTS, 1),
                ImportReviewRenderItem.CandidateItem(rows[4], selected = false),
                ImportReviewRenderItem.GroupHeader(ImportCandidateClass.RESOLVED, 1),
                ImportReviewRenderItem.CandidateItem(rows[0], selected = false),
            ),
            listItems,
        )
        // Empty groups are omitted: no SUSPECTED_DUPLICATE_PENDING_REVIEW / RETAINABLE_SIMILAR header.
        assertTrue(
            items.none {
                it is ImportReviewRenderItem.GroupHeader &&
                    (
                        it.classToken == ImportCandidateClass.SUSPECTED_DUPLICATE_PENDING_REVIEW ||
                            it.classToken == ImportCandidateClass.RETAINABLE_SIMILAR
                    )
            },
        )
    }

    @Test
    fun selectionStatePropagatesIntoCandidateItems() {
        val rows = listOf(row("pending-1"), row("pending-2"))
        val view =
            ImportReviewView(
                rows = rows,
                selectedCandidateIds = setOf(ImportCandidateId("pending-2")),
            )
        val candidates =
            importReviewRenderItems(view, ImportPlatformKind.ANDROID)
                .filterIsInstance<ImportReviewRenderItem.CandidateItem>()
        assertEquals(false, candidates.single { it.row.candidateId.value == "pending-1" }.selected)
        assertEquals(true, candidates.single { it.row.candidateId.value == "pending-2" }.selected)
    }

    // ---- session summary and its disclosed record cap (reuses importIntakeRecordLines) ----

    @Test
    fun theSessionSummaryRendersItsHeaderSessionLinesAndDisclosedRecordLines() {
        val session = acceptedSession(recordCount = 251)
        val items =
            importReviewRenderItems(
                ImportReviewView(lastIntakeSession = session),
                ImportPlatformKind.ANDROID,
            )
        val headerIndex = items.indexOfFirst { it is ImportReviewRenderItem.IntakeSessionHeader }
        assertTrue(headerIndex >= 0)
        // The session copy lines come verbatim from importIntakeSessionLines (既有钉点复用).
        val sessionLines =
            items.filterIsInstance<ImportReviewRenderItem.IntakeSessionLine>()
        assertEquals(importIntakeSessionLines(session), sessionLines.map { it.line })
        assertEquals(sessionLines.indices.toList(), sessionLines.map { it.ordinal })
        // The per-record lines come verbatim from importIntakeRecordLines, displayLimit = 200
        // disclosed: 200 rendered + 1 disclosure line, and the disclosure is the LAST record item.
        val recordLines =
            items.filterIsInstance<ImportReviewRenderItem.IntakeRecordLine>()
        val expectedRecordLines =
            importIntakeRecordLines(
                (session.outcome as ImportIntakePipelineOutcome.Intaken)
                    .let { it.outcome as ImportFileIntakeOutcome.Accepted }
                    .records,
            )
        assertEquals(expectedRecordLines, recordLines.map { it.line })
        assertEquals(201, recordLines.size)
        assertTrue(recordLines.last().line.contains("其余 51 条"))
        // Placement: header, then session lines, then record lines.
        val lastSessionLineIndex = items.indexOfLast { it is ImportReviewRenderItem.IntakeSessionLine }
        val firstRecordLineIndex = items.indexOfFirst { it is ImportReviewRenderItem.IntakeRecordLine }
        assertTrue(headerIndex < items.indexOfFirst { it is ImportReviewRenderItem.IntakeSessionLine })
        assertTrue(lastSessionLineIndex < firstRecordLineIndex)
    }

    // ---- batch result per-item expansion with Unknown check entries ----

    @Test
    fun theBatchResultExpandsPerItemLinesAndOneCheckEntryPerUnknownItem() {
        val summary = batchResultSummary()
        val items =
            importReviewRenderItems(
                ImportReviewView(batchResult = summary),
                ImportPlatformKind.ANDROID,
            )
        val headerIndex = items.indexOfFirst { it is ImportReviewRenderItem.BatchResultHeader }
        assertTrue(headerIndex >= 0)
        // Per-item expansion: the summary copy lines verbatim from importBatchResultLines.
        val lines = items.filterIsInstance<ImportReviewRenderItem.BatchResultLine>()
        assertEquals(importBatchResultLines(summary), lines.map { it.line })
        assertEquals(5, lines.size)
        // Exactly one check entry per Unknown item, carrying its candidate id (table 6.2a 核对入口).
        val unknownEntries = items.filterIsInstance<ImportReviewRenderItem.UnknownCheckItem>()
        assertEquals(listOf(ImportCandidateId("candidate-unknown")), unknownEntries.map { it.candidateId })
        // Placement: header, then the copy lines, then the Unknown check entries.
        val lastLineIndex = items.indexOfLast { it is ImportReviewRenderItem.BatchResultLine }
        val firstUnknownIndex = items.indexOfFirst { it is ImportReviewRenderItem.UnknownCheckItem }
        assertTrue(headerIndex < items.indexOfFirst { it is ImportReviewRenderItem.BatchResultLine })
        assertTrue(lastLineIndex < firstUnknownIndex)
        // 批量结果不设独立顶层态: absent summary means no batch-result items at all.
        val without = importReviewRenderItems(ImportReviewView(), ImportPlatformKind.ANDROID)
        assertTrue(without.none { it is ImportReviewRenderItem.BatchResultHeader })
        assertTrue(without.none { it is ImportReviewRenderItem.BatchResultLine })
        assertTrue(without.none { it is ImportReviewRenderItem.UnknownCheckItem })
    }

    // ---- batch duplicate disposition affordances (P704SPEC-12) ----

    @Test
    fun theGroupDispositionButtonRequiresACurrentSessionSuspectedDuplicateGroup() {
        val inSession =
            ImportReviewView(
                rows = listOf(row("dup-a", duplicateStatus = ImportDuplicateStatus.DEFERRED, sourceInputRef = "pick-handle-1")),
                lastIntakeSession = acceptedSession(recordCount = 1),
            )
        assertTrue(
            importReviewRenderItems(inSession, ImportPlatformKind.ANDROID)
                .any { it is ImportReviewRenderItem.GroupDispositionButton },
        )
        val otherSession =
            ImportReviewView(
                rows = listOf(row("dup-b", duplicateStatus = ImportDuplicateStatus.DEFERRED, sourceInputRef = "pick-handle-2")),
                lastIntakeSession = acceptedSession(recordCount = 1),
            )
        assertTrue(
            importReviewRenderItems(otherSession, ImportPlatformKind.ANDROID)
                .none { it is ImportReviewRenderItem.GroupDispositionButton },
        )
    }

    @Test
    fun theGroupDispositionCardRendersOnlyWhileThePageIsOpen() {
        val page = groupDispositionPage()
        val withPage =
            importReviewRenderItems(
                ImportReviewView(rows = listOf(row("dup-a", duplicateStatus = ImportDuplicateStatus.DEFERRED)), groupDisposition = page),
                ImportPlatformKind.ANDROID,
            )
        val card = withPage.filterIsInstance<ImportReviewRenderItem.GroupDispositionCard>().single()
        assertEquals(page, card.page)
        assertTrue(
            importReviewRenderItems(ImportReviewView(rows = listOf(row("pending-1"))), ImportPlatformKind.ANDROID)
                .none { it is ImportReviewRenderItem.GroupDispositionCard },
        )
    }

    // ---- batch confirmation entry gate (勾选集非空才可达) ----

    @Test
    fun theBatchConfirmEntryRequiresANonEmptySelection() {
        assertTrue(
            importReviewRenderItems(ImportReviewView(rows = listOf(row("pending-1"))), ImportPlatformKind.ANDROID)
                .none { it is ImportReviewRenderItem.BatchConfirmButton },
        )
        val selected =
            importReviewRenderItems(
                ImportReviewView(
                    rows = listOf(row("pending-1"), row("pending-2")),
                    selectedCandidateIds = setOf(ImportCandidateId("pending-1"), ImportCandidateId("pending-2")),
                ),
                ImportPlatformKind.ANDROID,
            )
        val button = selected.filterIsInstance<ImportReviewRenderItem.BatchConfirmButton>().single()
        assertEquals(2, button.selectionCount)
    }

    // ---- stable/unique key contract (a duplicate key crashes LazyColumn at runtime) ----

    @Test
    fun everyItemKeyIsUniqueAndEveryContentTypeNonBlankAcrossAFullFixture() {
        val view =
            ImportReviewView(
                rows =
                    listOf(
                        row("pending-1"),
                        row("pending-2"),
                        row("dup-a", duplicateStatus = ImportDuplicateStatus.DEFERRED),
                        row("blocked-1", duplicateStatus = ImportDuplicateStatus.CONFIRMED_DUPLICATE),
                        row("incomplete-1", candidateStatus = "incomplete", completeness = ImportCompleteness.VALID_INCOMPLETE),
                        row("resolved-1", candidateStatus = "confirmed"),
                    ),
                selectedCandidateIds = setOf(ImportCandidateId("pending-1")),
                lastIntakeSession = acceptedSession(recordCount = 3),
                notice = ImportReviewNotice.ReviewReadFailed,
                groupDisposition = groupDispositionPage(),
                batchResult = batchResultSummary(),
            )
        val items = importReviewRenderItems(view, ImportPlatformKind.ANDROID)
        val keys = items.map { it.stableKey }
        assertEquals(keys.size, keys.toSet().size, "duplicate stable keys: ${keys.groupingBy { it }.eachCount().filterValues { it > 1 }}")
        assertTrue(items.all { it.contentType.isNotBlank() })
        assertIs<ImportReviewRenderItem.TitleBar>(items.first())
    }

    @Test
    fun theRenderModelIsDeterministicForTheSameInput() {
        val view =
            ImportReviewView(
                rows = listOf(row("pending-1"), row("dup-a", duplicateStatus = ImportDuplicateStatus.DEFERRED)),
                selectedCandidateIds = setOf(ImportCandidateId("pending-1")),
                lastIntakeSession = acceptedSession(recordCount = 2),
                batchResult = batchResultSummary(),
            )
        val first = importReviewRenderItems(view, ImportPlatformKind.ANDROID)
        val second = importReviewRenderItems(view, ImportPlatformKind.ANDROID)
        assertEquals(first, second)
        assertEquals(first.map { it.stableKey }, second.map { it.stableKey })
    }

    // ---- cap-scale shape regression (FOUND-P704-D01-01: 10,000 candidates, zero loss) ----

    @Test
    fun capScaleTenThousandCandidatesKeepEveryRowInFrozenOrder() {
        val capacityRows = (0 until 10_000).map { row("candidate-" + it.toString().padStart(5, '0')) }
        val items =
            importReviewRenderItems(
                ImportReviewView(rows = capacityRows),
                ImportPlatformKind.ANDROID,
            )
        // Static sections (TitleBar + 2 dividers + format header + pending header + one group
        // header) + the 4-entry Android format matrix + all 10,000 candidate rows — zero loss,
        // zero new cap (no silent truncation of the candidate list itself).
        val staticSections = 6
        assertEquals(
            staticSections + importFormatEntries(ImportPlatformKind.ANDROID).size + 10_000,
            items.size,
        )
        assertEquals(10_010, items.size)
        assertIs<ImportReviewRenderItem.TitleBar>(items.first())
        val header = assertIs<ImportReviewRenderItem.GroupHeader>(items[9])
        assertEquals(ImportCandidateClass.PENDING_USER_DECISION, header.classToken)
        assertEquals(10_000, header.rowCount)
        // First and last candidate items are the first and last fixture rows, in order.
        assertEquals("candidate:candidate-00000", items[10].stableKey)
        assertEquals("candidate:candidate-09999", items.last().stableKey)
        val candidates = items.filterIsInstance<ImportReviewRenderItem.CandidateItem>()
        assertEquals(10_000, candidates.size)
        assertEquals(10_000, candidates.map { it.stableKey }.toSet().size)
        assertEquals(capacityRows, candidates.map { it.row })
        assertTrue(items.none { it is ImportReviewRenderItem.NoCandidatesEmptyState })
    }
}
