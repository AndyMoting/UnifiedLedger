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
 * candidate items, the open disposition card's flat expansion as one header + one item per group
 * member + one footer (Option A windowing; zero loss at the 10,000-item group cap), and the
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

    // ---- P7-05 enumeration progress line (in-flight marker of the session-level batch read) ----

    @Test
    fun theEnumerationProgressLineRendersOnlyWhileInFlightAndNoCardIsOpen() {
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
        // An open card supersedes the progress line (the enumeration already landed).
        assertTrue(
            importReviewRenderItems(
                ImportReviewView(rows = rows, lastIntakeSession = session, groupDisposition = groupDispositionPage(), groupEnumerationInProgress = true),
                ImportPlatformKind.ANDROID,
            ).none { it is ImportReviewRenderItem.GroupEnumerationInProgress },
        )
    }

    @Test
    fun theGroupDispositionCardExpandsAsHeaderItemsAndFooterOnlyWhileThePageIsOpen() {
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
        val withPage =
            importReviewRenderItems(
                ImportReviewView(rows = listOf(row("dup-a", duplicateStatus = ImportDuplicateStatus.DEFERRED)), groupDisposition = page),
                ImportPlatformKind.ANDROID,
            )
        // The open card expands flat: one header, then one item per group member in row order,
        // then one footer — three subtypes, zero nesting (the flat window the LazyColumn consumes).
        val header = withPage.filterIsInstance<ImportReviewRenderItem.GroupDispositionCardHeader>().single()
        assertEquals("pick-handle-1", header.inputRef)
        assertEquals(2, header.itemCount)
        val groupItems = withPage.filterIsInstance<ImportReviewRenderItem.GroupDispositionItem>()
        assertEquals(page.items, groupItems.map { it.state })
        assertEquals(
            listOf("group-disposition-item:dup-1", "group-disposition-item:dup-2"),
            groupItems.map { it.stableKey },
        )
        val footer = withPage.filterIsInstance<ImportReviewRenderItem.GroupDispositionCardFooter>().single()
        assertEquals(2, footer.itemCount)
        // Order: header first, then the two items, then the footer — consecutive, no interleaving.
        val headerIndex = withPage.indexOfFirst { it is ImportReviewRenderItem.GroupDispositionCardHeader }
        val firstItemIndex = withPage.indexOfFirst { it is ImportReviewRenderItem.GroupDispositionItem }
        val lastItemIndex = withPage.indexOfLast { it is ImportReviewRenderItem.GroupDispositionItem }
        val footerIndex = withPage.indexOfLast { it is ImportReviewRenderItem.GroupDispositionCardFooter }
        assertEquals(headerIndex + 1, firstItemIndex)
        assertEquals(headerIndex + 2, lastItemIndex)
        assertEquals(headerIndex + 3, footerIndex)
        // Closed page: none of the three subtypes render at all.
        val closed =
            importReviewRenderItems(ImportReviewView(rows = listOf(row("pending-1"))), ImportPlatformKind.ANDROID)
        assertTrue(closed.none { it is ImportReviewRenderItem.GroupDispositionCardHeader })
        assertTrue(closed.none { it is ImportReviewRenderItem.GroupDispositionItem })
        assertTrue(closed.none { it is ImportReviewRenderItem.GroupDispositionCardFooter })
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
        // The card itself lists only the landed prefix (the page's items, in order).
        val groupItems = items.filterIsInstance<ImportReviewRenderItem.GroupDispositionItem>()
        assertEquals(landed.items, groupItems.map { it.state })
        assertEquals(2, groupItems.size)
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
                // P7-05: the in-flight marker is on in this fixture so the progress-line key
                // joins the uniqueness contract (a card is open, so the line itself is
                // suppressed — the key is exercised below in the in-flight-only projection).
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
     * Option A cap pin: a fully open 10,000-item disposition page expands flat as exactly one
     * header + 10,000 items + one footer — no eager per-item composition inside a single card
     * item, zero loss, zero new cap (the whole list reaches the LazyColumn window).
     */
    @Test
    fun capScaleGroupDispositionTenThousandItemsWindowedAsFlatItems() {
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
        // header) + the 4-entry Android format matrix + the session block (1 header + 2 summary
        // lines + 1 record line) + the 10,000 candidate rows + 1 disposition affordance button +
        // the card's 1 header + 10,000 flat items + 1 footer = 20,017 items — zero loss, zero new
        // cap (C5: nothing is truncated).
        val staticSections = 6
        val sessionBlock = 1 + importIntakeSessionLines(view.lastIntakeSession!!).size + 1
        val affordance = 1
        assertEquals(
            staticSections + importFormatEntries(ImportPlatformKind.ANDROID).size + sessionBlock + 10_000 + affordance + 1 + 10_000 + 1,
            items.size,
        )
        assertEquals(20_017, items.size)
        // Order: the card header sits right after the affordance button; the 10,000 flat items
        // follow in page order; the footer is immediately before the first candidate row (the
        // affordance/card deliberately precede the 20k candidate list).
        val buttonIndex = items.indexOfFirst { it is ImportReviewRenderItem.GroupDispositionButton }
        val headerIndex = items.indexOfFirst { it is ImportReviewRenderItem.GroupDispositionCardHeader }
        assertEquals(buttonIndex + 1, headerIndex)
        assertEquals(ImportReviewRenderItem.GroupDispositionCardHeader("pick-handle-1", 10_000), items[headerIndex])
        val groupItems = items.filterIsInstance<ImportReviewRenderItem.GroupDispositionItem>()
        assertEquals(10_000, groupItems.size)
        assertEquals(page.items, groupItems.map { it.state })
        assertEquals(
            (0 until 10_000).map { "group-disposition-item:dup-" + it.toString().padStart(5, '0') },
            groupItems.map { it.stableKey },
        )
        val footerIndex = items.indexOfFirst { it is ImportReviewRenderItem.GroupDispositionCardFooter }
        val firstCandidateIndex = items.indexOfFirst { it is ImportReviewRenderItem.CandidateItem }
        // The card footer precedes the candidate list; the class GroupHeader sits between them.
        assertTrue(footerIndex >= 0 && footerIndex < firstCandidateIndex)
        assertEquals(10_000, (items[footerIndex] as ImportReviewRenderItem.GroupDispositionCardFooter).itemCount)
        // Every key stays pairwise distinct across the whole 20,017-item list.
        val keys = items.map { it.stableKey }
        assertEquals(keys.size, keys.toSet().size)
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
