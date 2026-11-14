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
import kotlin.test.assertFalse
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
 * candidate items, the group-disposition affordance leading the list body above the session's
 * record lines (D-327), the open disposition card's D-330 render form — BOUNDED to the
 * IMPORT_REVIEW_GROUP_CARD_BOUNDED_ROWS keyset prefix plus the 查看全部 entry row by default,
 * the flat header + every item + footer shape once expanded (Q1 案 C; zero loss when expanded,
 * pinned at the 10,000-item cap and the 99,600 rig shape) — and the
 * stable/unique key contract (a duplicate LazyColumn key crashes at
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
        val inSessionItems = importReviewRenderItems(inSession, ImportPlatformKind.ANDROID)
        val buttonIndex = inSessionItems.indexOfFirst { it is ImportReviewRenderItem.GroupDispositionButton }
        val firstCandidateIndex = inSessionItems.indexOfFirst { it is ImportReviewRenderItem.CandidateItem }
        assertTrue(buttonIndex >= 0)
        // The 20k-row group must not bury its only action below the candidate list.
        assertTrue(firstCandidateIndex > buttonIndex)
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

    // ---- D-322 count 驱动组入口 (spec `docs/specs/2026-11-14-d322-count-driven-group-entry-design.md` D-3/D-4) ----

    /**
     * D-322 D-3 (V-2): a count > 0 renders the button with ZERO loaded rows — the entry's existence
     * is carried by the landing lease's committed-session count, not by how many list pages have
     * been loaded (the maximum-rig failure class: the group entry sat ~50,000 candidates deep in an
     * unloaded page). The button stays BEFORE the candidate rows (here: before nothing loaded).
     */
    @Test
    fun aCountDrivenSessionRendersTheButtonWithZeroLoadedRows() {
        val view =
            ImportReviewView(
                rows = emptyList(),
                lastIntakeSession = acceptedSession(recordCount = 1),
                currentSessionDuplicateGroupCount = 3L,
            )
        val items = importReviewRenderItems(view, ImportPlatformKind.ANDROID)
        val buttonIndex = items.indexOfFirst { it is ImportReviewRenderItem.GroupDispositionButton }
        assertTrue(buttonIndex >= 0, "a count > 0 renders the entry even with zero loaded rows")
        assertTrue(
            items.none { it is ImportReviewRenderItem.CandidateItem },
            "the shape pins the zero-rows-loaded window",
        )
    }

    /**
     * D-322 D-3 (V-2): a real zero (the SQL COUNT(*) true zero of a session without a group) hides
     * the button — even when the loaded rows would have satisfied the legacy enumeration. Zero is
     * the authoritative reading; it never collapses into the null fallback.
     */
    @Test
    fun aTrueZeroCountHidesTheButton() {
        val view =
            ImportReviewView(
                rows = listOf(row("dup-a", duplicateStatus = ImportDuplicateStatus.DEFERRED, sourceInputRef = "pick-handle-1")),
                lastIntakeSession = acceptedSession(recordCount = 1),
                currentSessionDuplicateGroupCount = 0L,
            )
        assertTrue(
            importReviewRenderItems(view, ImportPlatformKind.ANDROID)
                .none { it is ImportReviewRenderItem.GroupDispositionButton },
        )
    }

    /**
     * D-322 D-3 (V-2): a null count is ONLY "no reading" (a failed count read or a pre-D-322
     * construction) — it NEVER reads as zero. The gate falls back to the legacy loaded-rows
     * enumeration, byte-equivalent to the pre-D-322 behavior: an in-session suspected row renders
     * the button, a foreign-session row does not (the existing cases above stay unchanged).
     */
    @Test
    fun aNullCountFallsBackToTheLoadedRowsEnumeration() {
        val inSession =
            ImportReviewView(
                rows = listOf(row("dup-a", duplicateStatus = ImportDuplicateStatus.DEFERRED, sourceInputRef = "pick-handle-1")),
                lastIntakeSession = acceptedSession(recordCount = 1),
            )
        val items = importReviewRenderItems(inSession, ImportPlatformKind.ANDROID)
        val buttonIndex = items.indexOfFirst { it is ImportReviewRenderItem.GroupDispositionButton }
        val firstCandidateIndex = items.indexOfFirst { it is ImportReviewRenderItem.CandidateItem }
        assertTrue(buttonIndex >= 0, "null falls back to the legacy enumeration, which finds the group")
        assertTrue(firstCandidateIndex > buttonIndex, "the button stays before the candidate rows")
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

    /**
     * D-322 D-3 (V-2): the null fallback still respects the no-session guard — without a session
     * handle there is nothing to count and nothing to enumerate, so the button stays hidden.
     */
    @Test
    fun aNullCountWithoutASessionStaysHidden() {
        val view = ImportReviewView(rows = listOf(row("dup-a", duplicateStatus = ImportDuplicateStatus.DEFERRED)))
        assertTrue(
            importReviewRenderItems(view, ImportPlatformKind.ANDROID)
                .none { it is ImportReviewRenderItem.GroupDispositionButton },
        )
    }

    /**
     * D-322 D-4 (V-5): the 空态共渲染意图对 — the NoCandidatesEmptyState describes the LOADED window
     * ("no rows in the loaded window") while the button describes the SESSION ("the session's group
     * exists"); the two intents are independent and co-render. D-327 moved the affordance above
     * the 待确认 shell: the button now precedes the empty state (and every IntakeRecordLine /
     * CandidateItem below it).
     */
    @Test
    fun theEmptyStateAndTheCountDrivenButtonCoRender() {
        val view =
            ImportReviewView(
                rows = emptyList(),
                lastIntakeSession = acceptedSession(recordCount = 1),
                currentSessionDuplicateGroupCount = 3L,
            )
        val items = importReviewRenderItems(view, ImportPlatformKind.ANDROID)
        val emptyIndex = items.indexOfFirst { it is ImportReviewRenderItem.NoCandidatesEmptyState }
        val buttonIndex = items.indexOfFirst { it is ImportReviewRenderItem.GroupDispositionButton }
        assertTrue(emptyIndex >= 0, "the empty state describes the loaded window")
        assertTrue(buttonIndex >= 0, "the button describes the session's group — the intents co-render")
        assertTrue(buttonIndex < emptyIndex, "the button precedes the empty state (D-327 placement)")
        assertTrue(
            items.none { it is ImportReviewRenderItem.CandidateItem },
            "no candidate row is loaded, so the button precedes the (empty) rows",
        )
    }

    /**
     * D-327 regression (the maximum-rig group-disposition fix, D-326 probe run `43578009152`):
     * the affordance must sit BEFORE the IntakeRecordLine block. A maximum 10,000-record intake
     * session renders one record line per record (disclosed at the 200-line cap), which used to
     * bury the button at LazyColumn depth ~10,030 — beyond the rig's 80-step accessibility seek
     * and every real user's reach, while the count gate itself stayed open (`entry=true`) and the
     * button sat outside the composed window. The gate facts stay the D-322 count-driven ones
     * (count > 0 renders the entry with zero loaded rows).
     */
    @Test
    fun theGroupDispositionButtonPrecedesTheIntakeRecordLinesAtTheMaximumSession() {
        val view =
            ImportReviewView(
                rows = emptyList(),
                lastIntakeSession = acceptedSession(recordCount = 10_000),
                currentSessionDuplicateGroupCount = 3L,
            )
        val items = importReviewRenderItems(view, ImportPlatformKind.ANDROID)
        val buttonIndex = items.indexOfFirst { it is ImportReviewRenderItem.GroupDispositionButton }
        val firstRecordLineIndex = items.indexOfFirst { it is ImportReviewRenderItem.IntakeRecordLine }
        assertTrue(buttonIndex >= 0, "count > 0 renders the affordance at the maximum session")
        assertTrue(firstRecordLineIndex >= 0, "the 10,000-record session renders its record lines")
        assertTrue(
            buttonIndex < firstRecordLineIndex,
            "the button precedes the record lines (pre-D-327 it sat ~10,030 items deep)",
        )
        // The whole session block stays intact below the affordance: header, then the summary
        // lines, then the disclosed record lines (10,000 records → 200 rendered + 1 disclosure).
        val headerIndex = items.indexOfFirst { it is ImportReviewRenderItem.IntakeSessionHeader }
        assertTrue(headerIndex > buttonIndex, "the session summary block follows the affordance")
        val recordLines = items.filterIsInstance<ImportReviewRenderItem.IntakeRecordLine>()
        assertEquals(201, recordLines.size)
        assertTrue(
            items.none { it is ImportReviewRenderItem.CandidateItem },
            "the zero-rows window keeps the button before the (unloaded) candidate list too",
        )
    }

    // ---- P7-05 enumeration progress line (in-flight marker of the session-level batch read) ----

    @Test
    fun theEnumerationProgressLineRendersWhileInFlightThroughTheContinuationPages() {
        val rows = listOf(row("dup-a", duplicateStatus = ImportDuplicateStatus.DEFERRED, sourceInputRef = "pick-handle-1"))
        val session = acceptedSession(recordCount = 1)
        // Default (not in flight): the progress line is absent — the pre-P7-05 shape is unchanged.
        assertTrue(
            importReviewRenderItems(ImportReviewView(rows = rows, lastIntakeSession = session), ImportPlatformKind.ANDROID)
                .none { it is ImportReviewRenderItem.GroupEnumerationInProgress },
        )
        // In flight without an open card: the progress line renders right after the affordance.
        val inFlight =
            importReviewRenderItems(
                ImportReviewView(rows = rows, lastIntakeSession = session, groupEnumerationInProgress = true),
                ImportPlatformKind.ANDROID,
            )
        val buttonIndex = inFlight.indexOfFirst { it is ImportReviewRenderItem.GroupDispositionButton }
        val progressIndex = inFlight.indexOfFirst { it is ImportReviewRenderItem.GroupEnumerationInProgress }
        assertTrue(buttonIndex >= 0 && progressIndex == buttonIndex + 1)
        // B6 first-window (D-280 spec §3.3 续页期间): the card is already open on its first
        // window while the remaining pages land, and the flag KEEPS carrying the line through it —
        // the line still sits between the affordance and the card header, never superseded by the
        // card.
        val continuation =
            importReviewRenderItems(
                ImportReviewView(rows = rows, lastIntakeSession = session, groupDisposition = groupDispositionPage(), groupEnumerationInProgress = true),
                ImportPlatformKind.ANDROID,
            )
        val continuationButton = continuation.indexOfFirst { it is ImportReviewRenderItem.GroupDispositionButton }
        val continuationProgress = continuation.indexOfFirst { it is ImportReviewRenderItem.GroupEnumerationInProgress }
        val cardHeader = continuation.indexOfFirst { it is ImportReviewRenderItem.GroupDispositionCardHeader }
        val firstRecordLine = continuation.indexOfFirst { it is ImportReviewRenderItem.IntakeRecordLine }
        val lastRecordLine = continuation.indexOfLast { it is ImportReviewRenderItem.IntakeRecordLine }
        assertEquals(continuationButton + 1, continuationProgress)
        // D-327: the in-flight band (affordance + progress line) sits BEFORE the IntakeRecordLine
        // block; the open card stays in the 待确认 section, so its header now follows the record
        // lines. The line still precedes the card it carries, never superseded by it.
        assertTrue(firstRecordLine >= 0, "the session block renders its record lines")
        assertTrue(continuationProgress < firstRecordLine, "the progress line precedes the record lines (D-327)")
        assertTrue(lastRecordLine < cardHeader, "the card header follows the record lines (D-327)")
    }

    @Test
    fun theGroupDispositionCardRendersBoundedRowsByDefaultAndAllRowsOnceExpanded() {
        // Two review rows of the SAME subject candidate: the flat item key is the duplicate
        // candidate id, never the subject candidate id（同一 candidate 组内可多 review 行）.
        val page =
            ImportDuplicateGroupDispositionPage(
                inputRef = "pick-handle-1",
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
                        ImportDuplicateGroupItemState(
                            item =
                                ImportDuplicateGroupDispositionItem(
                                    candidateId = ImportCandidateId("candidate-dup-1"),
                                    duplicateCandidateId = ImportDuplicateCandidateId("dup-2"),
                                    comparisonSnapshot = "{\"amount_minor\":3581}",
                                    expectedComparisonFingerprint = "sha256:fixed-fingerprint",
                                ),
                        ),
                    ),
            )
        val view = ImportReviewView(rows = listOf(row("dup-a", duplicateStatus = ImportDuplicateStatus.DEFERRED)), groupDisposition = page)
        // D-330 default form (Q2 恒默认有界): the named initial decision — not a bare literal.
        assertFalse(initialGroupCardRowsExpanded(), "the card defaults to the bounded form, never remembered")
        val bounded =
            importReviewRenderItems(
                view,
                ImportPlatformKind.ANDROID,
                groupCardRowsExpanded = initialGroupCardRowsExpanded(),
            )
        // Under the bound, both members still list (2 ≤ the bound): the small-card shape is
        // unchanged except that NO view-all entry renders — the whole group fits under K.
        val boundedHeader = bounded.filterIsInstance<ImportReviewRenderItem.GroupDispositionCardHeader>().single()
        assertEquals("pick-handle-1", boundedHeader.inputRef)
        assertEquals(2, boundedHeader.itemCount)
        val boundedGroupItems = bounded.filterIsInstance<ImportReviewRenderItem.GroupDispositionItem>()
        assertEquals(page.items, boundedGroupItems.map { it.state })
        assertEquals(
            listOf("group-disposition-item:dup-1", "group-disposition-item:dup-2"),
            boundedGroupItems.map { it.stableKey },
        )
        val boundedFooter = bounded.filterIsInstance<ImportReviewRenderItem.GroupDispositionCardFooter>().single()
        assertEquals(2, boundedFooter.itemCount)
        assertTrue(
            bounded.none { it is ImportReviewRenderItem.GroupCardViewAllRows },
            "a group whose whole count does not exceed the bound renders no entry row",
        )
        assertTrue(bounded.none { it is ImportReviewRenderItem.GroupCardCollapseRows }, "the bounded form has no collapse row")
        val boundedHeaderIndex = bounded.indexOfFirst { it is ImportReviewRenderItem.GroupDispositionCardHeader }
        val boundedFirstItemIndex = bounded.indexOfFirst { it is ImportReviewRenderItem.GroupDispositionItem }
        val boundedLastItemIndex = bounded.indexOfLast { it is ImportReviewRenderItem.GroupDispositionItem }
        val boundedFooterIndex = bounded.indexOfLast { it is ImportReviewRenderItem.GroupDispositionCardFooter }
        assertEquals(boundedHeaderIndex + 1, boundedFirstItemIndex)
        assertEquals(boundedHeaderIndex + 2, boundedLastItemIndex)
        assertEquals(boundedHeaderIndex + 3, boundedFooterIndex)
        // Expanded form (the user's one action): every landed row lists, plus the leading
        // collapse row — the pre-D-330 flat shape plus exactly that one row.
        val expanded =
            importReviewRenderItems(
                view,
                ImportPlatformKind.ANDROID,
                groupCardRowsExpanded = true,
            )
        assertTrue(expanded.none { it is ImportReviewRenderItem.GroupCardViewAllRows }, "the expanded form has no entry row")
        val collapseIndex = expanded.indexOfFirst { it is ImportReviewRenderItem.GroupCardCollapseRows }
        assertTrue(collapseIndex >= 0, "the expanded form carries the collapse affordance")
        val expandedHeaderIndex = expanded.indexOfFirst { it is ImportReviewRenderItem.GroupDispositionCardHeader }
        val expandedGroupItems = expanded.filterIsInstance<ImportReviewRenderItem.GroupDispositionItem>()
        assertEquals(page.items, expandedGroupItems.map { it.state })
        val expandedFooterIndex = expanded.indexOfLast { it is ImportReviewRenderItem.GroupDispositionCardFooter }
        assertEquals(expandedHeaderIndex + 1, collapseIndex, "the collapse row leads (reachable above a huge list)")
        // The footer follows the LAST member row directly (header, collapse row, every member
        // row, footer — nothing interleaves).
        assertEquals(expandedFooterIndex - 1, expanded.indexOfLast { it is ImportReviewRenderItem.GroupDispositionItem })
        assertEquals(expandedFooterIndex - expandedHeaderIndex - 2, expandedGroupItems.size)
        // Closed page: none of the card subtypes render at all.
        val closed =
            importReviewRenderItems(ImportReviewView(rows = listOf(row("pending-1"))), ImportPlatformKind.ANDROID)
        assertTrue(closed.none { it is ImportReviewRenderItem.GroupDispositionCardHeader })
        assertTrue(closed.none { it is ImportReviewRenderItem.GroupDispositionItem })
        assertTrue(closed.none { it is ImportReviewRenderItem.GroupDispositionCardFooter })
        assertTrue(closed.none { it is ImportReviewRenderItem.GroupCardViewAllRows })
        assertTrue(closed.none { it is ImportReviewRenderItem.GroupCardCollapseRows })
    }

    /**
     * B6 group-card paging batch (D-280, P704GP-ACC-COUNT-01): while the card shows only the landed
     * keyset prefix, the header and footer still disclose the WHOLE-group total from the count
     * query (page.totalCount), never the landed count the card happens to list.
     */
    @Test
    fun thePagedGroupCardDisclosesTheWholeGroupTotalWhileListingOnlyTheLandedPrefix() {
        val landed =
            ImportDuplicateGroupDispositionPage(
                inputRef = "pick-handle-1",
                items =
                    (1..2).map { ordinal ->
                        ImportDuplicateGroupItemState(
                            item =
                                ImportDuplicateGroupDispositionItem(
                                    candidateId = ImportCandidateId("candidate-dup-1"),
                                    duplicateCandidateId = ImportDuplicateCandidateId("dup-$ordinal"),
                                    comparisonSnapshot = "{\"amount_minor\":3580}",
                                    expectedComparisonFingerprint = "sha256:fixed-fingerprint",
                                ),
                            ordinal = ordinal,
                        )
                    },
                totalCount = 20_001,
            )
        val items =
            importReviewRenderItems(
                ImportReviewView(rows = listOf(row("dup-a", duplicateStatus = ImportDuplicateStatus.DEFERRED)), groupDisposition = landed),
                ImportPlatformKind.ANDROID,
            )
        val header = items.filterIsInstance<ImportReviewRenderItem.GroupDispositionCardHeader>().single()
        assertEquals(20_001, header.itemCount)
        // The rendered header copy says 共 20001 条 even though two items are listed.
        assertTrue(groupDispositionCardHeaderDisclosure(header.itemCount).endsWith("共 20001 条。"))
        // The batch switch's description carries the same whole-group count.
        assertEquals(
            "收起全部比较信息（共 20001 条）",
            groupDispositionComparisonBatchToggleDescription(collapsedAll = false, itemCount = header.itemCount),
        )
        val footer = items.filterIsInstance<ImportReviewRenderItem.GroupDispositionCardFooter>().single()
        assertEquals(20_001, footer.itemCount)
        // The card itself lists only the landed prefix (the page's items, in order), under the
        // D-330 bounded default form.
        val groupItems = items.filterIsInstance<ImportReviewRenderItem.GroupDispositionItem>()
        assertEquals(landed.items, groupItems.map { it.state })
        assertEquals(2, groupItems.size)
        // D-330: the whole group exceeds what the card lists, so the entry row renders with the
        // rows NOT currently listed — the whole-group total minus the listed prefix (the landed
        // 2 rows here, the bound's worth once the prefix fills). The count's 口径 is the same
        // totalCount the header/footer bind to, computed dynamically (never a frozen literal).
        val viewAll = items.filterIsInstance<ImportReviewRenderItem.GroupCardViewAllRows>().single()
        assertEquals(20_001 - 2, viewAll.beyondCount)
        assertEquals(20_001, viewAll.totalCount)
        assertEquals("查看全部 ${20_001 - 2} 条", groupCardViewAllRowsText(viewAll.beyondCount))
        assertEquals("查看全部组成员行：整组共 20001 条，逐条列出", groupCardViewAllRowsDescription(viewAll.totalCount))
        // D-330 V-3: the member rows keep the keyset prefix order — the bound neither truncates
        // the payload nor reorders it (the full list is one 查看全部 action away).
        assertEquals(landed.items.map { it.item.duplicateCandidateId }, groupItems.map { it.state.item.duplicateCandidateId })
    }

    /**
     * B6 group-card paging batch (D-280, P704GP-ACC-KEY-01, render-item half): each card item keeps
     * the whole-group `ordinal` its landing assigned, and its identity (`stableKey`/`contentType`)
     * is derived from the duplicate candidate id alone — never from a position.
     *
     * The assertions target this layer's contract, [ImportReviewRenderItem.GroupDispositionItem],
     * not the composable that consumes it. Each is a regression pin:
     * - `state.ordinal` must survive the flattening. Regression caught: the render model rebuilding
     *   the state from the landed list index (`forEachIndexed { i, s -> ... s.copy(ordinal = i + 1) }`
     *   in `importReviewRenderItems`) — the fixture's ordinals 7 and 3 differ from the indices 0/1.
     * - `stableKey` must be `group-disposition-item:<duplicateCandidateId>` verbatim. Regression
     *   caught: a key built from the ordinal, or from the subject candidate id (both members here
     *   share `candidate-dup-1`, so a subject-id key would collide and crash the LazyColumn).
     * - `contentType` must stay the fixed reuse token. Regression caught: a content type that
     *   varied per item, which would kill the LazyColumn's item reuse.
     * The expected keys are independent literals, never read back off the produced items.
     */
    @Test
    fun theCardItemCarriesItsLandedOrdinalAndAnOrdinalIndependentIdentity() {
        // Deliberately NOT 1..n: an item whose ordinal differs from its index in the landed list.
        // Both members also share one subject candidate, so the id in the key must be the duplicate.
        val page =
            ImportDuplicateGroupDispositionPage(
                inputRef = "pick-handle-1",
                items =
                    listOf(
                        ImportDuplicateGroupItemState(
                            item =
                                ImportDuplicateGroupDispositionItem(
                                    candidateId = ImportCandidateId("candidate-dup-1"),
                                    duplicateCandidateId = ImportDuplicateCandidateId("dup-b"),
                                    comparisonSnapshot = "{\"amount_minor\":3580}",
                                    expectedComparisonFingerprint = "sha256:fixed-fingerprint",
                                ),
                            ordinal = 7,
                        ),
                        ImportDuplicateGroupItemState(
                            item =
                                ImportDuplicateGroupDispositionItem(
                                    candidateId = ImportCandidateId("candidate-dup-1"),
                                    duplicateCandidateId = ImportDuplicateCandidateId("dup-a"),
                                    comparisonSnapshot = "{\"amount_minor\":3580}",
                                    expectedComparisonFingerprint = "sha256:fixed-fingerprint",
                                ),
                            ordinal = 3,
                        ),
                    ),
                totalCount = 20_001,
            )
        val items =
            importReviewRenderItems(
                ImportReviewView(rows = listOf(row("dup-a", duplicateStatus = ImportDuplicateStatus.DEFERRED)), groupDisposition = page),
                ImportPlatformKind.ANDROID,
            )
        val groupItems = items.filterIsInstance<ImportReviewRenderItem.GroupDispositionItem>()
        assertEquals(2, groupItems.size)
        // The landed ordinals reach this layer intact and in landing order.
        assertEquals(listOf(7, 3), groupItems.map { it.state.ordinal })
        // Identity per item, pinned with independent literals.
        assertEquals(
            listOf("group-disposition-item:dup-b", "group-disposition-item:dup-a"),
            groupItems.map { it.stableKey },
        )
        assertEquals(listOf("groupDispositionItem", "groupDispositionItem"), groupItems.map { it.contentType })
        // The literal keys above already forbid both a positional component (which would read
        // `group-disposition-item:7` / `group-disposition-item:3`) and a subject-candidate
        // component (`candidate-dup-1` is shared by both members, so that key would collide).
        // The description formatter renders the ordinal it is handed (J4 copy); this pins the copy
        // contract only — the ordinal it receives is wired by the composable (P503ImportReview.kt
        // `val itemOrdinal = item.state.ordinal`), which no unit test here reaches.
        assertEquals(
            "收起候选 candidate-dup-1 的第 7 条比较信息",
            groupDispositionItemComparisonToggleDescription(
                expanded = true,
                candidateId = ImportCandidateId("candidate-dup-1"),
                itemOrdinal = 7,
            ),
        )
        assertEquals(
            "展开候选 candidate-dup-1 的第 3 条比较信息",
            groupDispositionItemComparisonToggleDescription(
                expanded = false,
                candidateId = ImportCandidateId("candidate-dup-1"),
                itemOrdinal = 3,
            ),
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
                // P7-05 + B6 first-window (D-280 spec §3.3): the in-flight marker is on in this
                // fixture so the progress-line key joins the uniqueness contract WITH the card
                // open — the continuation state, where the line and the growing card render
                // together.
                groupEnumerationInProgress = true,
            )
        val items = importReviewRenderItems(view, ImportPlatformKind.ANDROID)
        val keys = items.map { it.stableKey }
        assertEquals(keys.size, keys.toSet().size, "duplicate stable keys: ${keys.groupingBy { it }.eachCount().filterValues { it > 1 }}")
        // The flattened disposition-card subtypes share the `group-disposition-` key prefix with
        // the affordance button but never collide: button/header/item/footer keys stay pairwise
        // distinct across the whole list (the item key is the duplicate candidate id, never the
        // subject candidate id).
        val groupDispositionKeys = keys.filter { it.startsWith("group-disposition-") }
        assertEquals(1 + 1 + view.groupDisposition!!.items.size + 1, groupDispositionKeys.size)
        assertEquals(groupDispositionKeys.size, groupDispositionKeys.toSet().size)
        assertTrue(items.all { it.contentType.isNotBlank() })
        assertIs<ImportReviewRenderItem.TitleBar>(items.first())
        // P7-05 progress-line key: present with the exact stable key in the in-flight-only
        // projection (no open card), and absent again once the marker clears.
        val inFlightOnly =
            ImportReviewView(
                rows = listOf(row("dup-a", duplicateStatus = ImportDuplicateStatus.DEFERRED)),
                lastIntakeSession = acceptedSession(recordCount = 1),
                groupEnumerationInProgress = true,
            )
        val inFlightItems = importReviewRenderItems(inFlightOnly, ImportPlatformKind.ANDROID)
        val progressKey = inFlightItems.filterIsInstance<ImportReviewRenderItem.GroupEnumerationInProgress>().single().stableKey
        assertEquals("group-enumeration-in-progress", progressKey)
        assertEquals("groupEnumerationProgress", inFlightItems.filterIsInstance<ImportReviewRenderItem.GroupEnumerationInProgress>().single().contentType)
        val inFlightKeys = inFlightItems.map { it.stableKey }
        assertEquals(inFlightKeys.size, inFlightKeys.toSet().size)
        assertTrue(inFlightKeys.contains(progressKey))
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

    /**
     * D-330 cap pin (Q1 案 C): a fully landed 10,000-item disposition page renders the BOUNDED
     * default form — exactly one header + the [IMPORT_REVIEW_GROUP_CARD_BOUNDED_ROWS] keyset
     * prefix + the 查看全部 entry + one footer — while the header/footer counts stay the
     * WHOLE-group total. Zero payload loss: the remainder is reachable through the entry (one
     * action lists every landed row), and the entry's count uses the dynamic whole-group
     * overflow, never a frozen literal.
     */
    @Test
    fun capScaleGroupDispositionRendersTheBoundedPrefixAndTheViewAllEntryByDefault() {
        val page =
            ImportDuplicateGroupDispositionPage(
                inputRef = "pick-handle-1",
                items =
                    (0 until 10_000).map { index ->
                        ImportDuplicateGroupItemState(
                            item =
                                ImportDuplicateGroupDispositionItem(
                                    candidateId = ImportCandidateId("candidate-dup-1"),
                                    duplicateCandidateId = ImportDuplicateCandidateId("dup-" + index.toString().padStart(5, '0')),
                                    comparisonSnapshot = "{\"amount_minor\":3580}",
                                    expectedComparisonFingerprint = "sha256:fixed-fingerprint",
                                ),
                        )
                    },
            )
        val view =
            ImportReviewView(
                rows = (0 until 10_000).map { row("candidate-" + it.toString().padStart(5, '0'), duplicateStatus = ImportDuplicateStatus.DEFERRED) },
                lastIntakeSession = acceptedSession(recordCount = 1),
                groupDisposition = page,
            )
        val items = importReviewRenderItems(view, ImportPlatformKind.ANDROID)
        // Static sections (TitleBar + 2 dividers + format header + pending header + one group
        // header) + the FIVE-entry Android format matrix (P8-04 i2 D-13 added self-entry-xlsx) +
        // the session block (1 header + 2 summary lines + 1 record line) + 1 disposition
        // affordance button (D-327: before the session block) + the 10,000 candidate rows + the
        // card's 1 header + the bounded prefix + the view-all entry + 1 footer.
        val staticSections = 6
        val sessionBlock = 1 + importIntakeSessionLines(view.lastIntakeSession!!).size + 1
        val affordance = 1
        val cardBlock = 1 + IMPORT_REVIEW_GROUP_CARD_BOUNDED_ROWS + 1 + 1
        // No frozen literal here: the bounded card block is defined by the constant, and a K
        // change must not silently invalidate this pin (the mutable-form literal lesson).
        assertEquals(
            staticSections + importFormatEntries(ImportPlatformKind.ANDROID).size + sessionBlock + 10_000 + affordance + cardBlock,
            items.size,
        )
        // D-327 order: the affordance leads the whole list body (before the session summary and
        // its record lines); the card header follows the record lines and the 待确认 shell; the
        // bounded prefix follows in page order; the footer sits before the first candidate row.
        val buttonIndex = items.indexOfFirst { it is ImportReviewRenderItem.GroupDispositionButton }
        val headerIndex = items.indexOfFirst { it is ImportReviewRenderItem.GroupDispositionCardHeader }
        val firstRecordLineIndex = items.indexOfFirst { it is ImportReviewRenderItem.IntakeRecordLine }
        val pendingHeaderIndex = items.indexOfFirst { it is ImportReviewRenderItem.PendingSectionHeader }
        assertTrue(firstRecordLineIndex > buttonIndex, "the affordance precedes the record lines (D-327)")
        assertTrue(headerIndex > pendingHeaderIndex, "the card header follows the 待确认 shell")
        assertEquals(ImportReviewRenderItem.GroupDispositionCardHeader("pick-handle-1", 10_000), items[headerIndex])
        // The prefix: exactly the bound's worth of rows, in the landed keyset order, never
        // reordered — the first, last and count all pin the prefix contract.
        val groupItems = items.filterIsInstance<ImportReviewRenderItem.GroupDispositionItem>()
        assertEquals(IMPORT_REVIEW_GROUP_CARD_BOUNDED_ROWS, groupItems.size)
        assertEquals(page.items.take(IMPORT_REVIEW_GROUP_CARD_BOUNDED_ROWS), groupItems.map { it.state })
        assertEquals(
            (0 until IMPORT_REVIEW_GROUP_CARD_BOUNDED_ROWS).map { "group-disposition-item:dup-" + it.toString().padStart(5, '0') },
            groupItems.map { it.stableKey },
        )
        // The entry row: whole-group overflow, computed dynamically (not a frozen literal).
        val viewAllIndex = items.indexOfFirst { it is ImportReviewRenderItem.GroupCardViewAllRows }
        val viewAll = assertIs<ImportReviewRenderItem.GroupCardViewAllRows>(items[viewAllIndex])
        assertEquals(10_000 - IMPORT_REVIEW_GROUP_CARD_BOUNDED_ROWS, viewAll.beyondCount)
        assertEquals(10_000, viewAll.totalCount)
        assertEquals(headerIndex + 1 + IMPORT_REVIEW_GROUP_CARD_BOUNDED_ROWS, viewAllIndex, "the entry follows the prefix")
        assertEquals(
            "查看全部 ${10_000 - IMPORT_REVIEW_GROUP_CARD_BOUNDED_ROWS} 条",
            groupCardViewAllRowsText(viewAll.beyondCount),
        )
        // The footer keeps the whole-group count.
        val footerIndex = items.indexOfFirst { it is ImportReviewRenderItem.GroupDispositionCardFooter }
        val firstCandidateIndex = items.indexOfFirst { it is ImportReviewRenderItem.CandidateItem }
        assertEquals(headerIndex + cardBlock - 1, footerIndex, "the entry row sits directly before the footer")
        assertTrue(footerIndex >= 0 && footerIndex < firstCandidateIndex)
        assertEquals(10_000, (items[footerIndex] as ImportReviewRenderItem.GroupDispositionCardFooter).itemCount)
        // Every key stays pairwise distinct across the whole bounded-form list.
        val keys = items.map { it.stableKey }
        assertEquals(keys.size, keys.toSet().size)
        // The expanded form of the SAME page lays out every landed row (zero loss) — asserted in
        // the dedicated 99,600-shape test below.
        assertTrue(
            importReviewRenderItems(view, ImportPlatformKind.ANDROID, groupCardRowsExpanded = true).size > items.size,
            "expanding adds the remaining landed rows beyond the bounded default form",
        )
    }

    /**
     * D-330 V-2 pin (the A-VIS re-anchored duty): the 查看全部 action lists EVERY landed member
     * row — on the rig's 99,600-item shape (498 windows × 200), the expanded form is the
     * pre-D-330 flat shape plus the leading collapse row: one header + every item + one footer,
     * zero loss, keyset prefix order untouched (P704GP-ACC-ORDER-01), payload never truncated
     * or silently dropped.
     */
    @Test
    fun expandingTheGroupCardListsEveryLandedRowOnTheRigShape() {
        val rigItemCount = 99_600
        val page =
            ImportDuplicateGroupDispositionPage(
                inputRef = "pick-handle-1",
                items =
                    (0 until rigItemCount).map { index ->
                        ImportDuplicateGroupItemState(
                            item =
                                ImportDuplicateGroupDispositionItem(
                                    candidateId = ImportCandidateId("candidate-dup-1"),
                                    duplicateCandidateId = ImportDuplicateCandidateId("dup-" + index.toString().padStart(6, '0')),
                                    comparisonSnapshot = "{\"amount_minor\":3580}",
                                    expectedComparisonFingerprint = "sha256:fixed-fingerprint",
                                ),
                        )
                    },
                totalCount = rigItemCount,
            )
        val view =
            ImportReviewView(
                rows = listOf(row("dup-a", duplicateStatus = ImportDuplicateStatus.DEFERRED)),
                groupDisposition = page,
            )
        val expanded = importReviewRenderItems(view, ImportPlatformKind.ANDROID, groupCardRowsExpanded = true)
        val headerIndex = expanded.indexOfFirst { it is ImportReviewRenderItem.GroupDispositionCardHeader }
        val collapseIndex = expanded.indexOfFirst { it is ImportReviewRenderItem.GroupCardCollapseRows }
        val footerIndex = expanded.indexOfLast { it is ImportReviewRenderItem.GroupDispositionCardFooter }
        assertEquals(headerIndex + 1, collapseIndex, "the collapse row leads the expanded member rows")
        val groupItems = expanded.filterIsInstance<ImportReviewRenderItem.GroupDispositionItem>()
        assertEquals(rigItemCount, groupItems.size)
        // Keyset prefix order untouched: the first and last landed ordinals survive verbatim.
        assertEquals(page.items.first(), groupItems.first().state)
        assertEquals(page.items.last(), groupItems.last().state)
        assertEquals(
            "group-disposition-item:dup-000000",
            groupItems.first().stableKey,
        )
        assertEquals(
            "group-disposition-item:dup-099599",
            groupItems.last().stableKey,
        )
        assertTrue(expanded.none { it is ImportReviewRenderItem.GroupCardViewAllRows }, "the expanded form has no entry row")
        // The footer follows the last member row directly.
        assertEquals(headerIndex + 1 + 1 + rigItemCount, footerIndex)
        assertEquals(rigItemCount, (expanded[footerIndex] as ImportReviewRenderItem.GroupDispositionCardFooter).itemCount)
        val keys = expanded.map { it.stableKey }
        assertEquals(keys.size, keys.toSet().size)
    }

    /**
     * D-330 reset pin (Q2 恒默认有界不记忆): the production initial decision is the BOUNDED form
     * — every (re)open starts bounded, nothing carries the expanded state across rounds. The
     * composable owns the remember(reset) wiring; this layer pins the named decision the
     * remember sources from (the initialGroupComparisonExpansion precedent).
     */
    @Test
    fun theGroupCardRowsExpansionDefaultsToBoundedOnEveryOpen() {
        assertFalse(initialGroupCardRowsExpanded(), "the bounded form is the only initial state — never remembered")
    }

    @Test
    fun capScaleTenThousandCandidatesKeepEveryRowInFrozenOrder() {
        val capacityRows = (0 until 10_000).map { row("candidate-" + it.toString().padStart(5, '0')) }
        val items =
            importReviewRenderItems(
                ImportReviewView(rows = capacityRows),
                ImportPlatformKind.ANDROID,
            )
        // Static sections (TitleBar + 2 dividers + format header + pending header + one group
        // header) + the FIVE-entry Android format matrix (P8-04 i2 D-13) + all 10,000 candidate
        // rows — zero loss, zero new cap (no silent truncation of the candidate list itself).
        val staticSections = 6
        assertEquals(
            staticSections + importFormatEntries(ImportPlatformKind.ANDROID).size + 10_000,
            items.size,
        )
        assertEquals(10_011, items.size)
        assertIs<ImportReviewRenderItem.TitleBar>(items.first())
        // The fifth matrix entry shifts the group header one slot later (10 -> 11).
        val header = assertIs<ImportReviewRenderItem.GroupHeader>(items[10])
        assertEquals(ImportCandidateClass.PENDING_USER_DECISION, header.classToken)
        assertEquals(10_000, header.rowCount)
        // First and last candidate items are the first and last fixture rows, in order.
        assertEquals("candidate:candidate-00000", items[11].stableKey)
        assertEquals("candidate:candidate-09999", items.last().stableKey)
        val candidates = items.filterIsInstance<ImportReviewRenderItem.CandidateItem>()
        assertEquals(10_000, candidates.size)
        assertEquals(10_000, candidates.map { it.stableKey }.toSet().size)
        assertEquals(capacityRows, candidates.map { it.row })
        assertTrue(items.none { it is ImportReviewRenderItem.NoCandidatesEmptyState })
    }
}
