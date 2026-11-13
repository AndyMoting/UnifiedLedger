package com.unifiedledger.ui

import com.unifiedledger.application.CountImportDuplicateCandidatesForSession
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportDuplicateCandidateId
import com.unifiedledger.application.ImportDuplicateReviewRow
import com.unifiedledger.application.ImportDuplicateReviewsForSessionMemberPage
import com.unifiedledger.application.ImportDuplicateReviewsForSessionMemberPageResult
import com.unifiedledger.application.ImportDuplicateReviewsForSessionRow
import com.unifiedledger.application.ImportDuplicateReviewsResult
import com.unifiedledger.application.ImportDuplicateStatus
import com.unifiedledger.application.ImportFileIntakeOutcome
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportReviewReadPort
import com.unifiedledger.application.ImportReviewRow
import com.unifiedledger.application.ImportReviewRowsPage
import com.unifiedledger.application.ImportReviewRowsPageResult
import com.unifiedledger.application.ImportReviewRowsResult
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.QueryImportDuplicateReviewsForSessionMemberPage
import com.unifiedledger.application.QueryImportReviewRows
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * P7-04 B1/B4 windowed read (design `docs/specs/2026-11-13-p7-04-b1-b4-windowed-read-design.md`
 * D-5/D-6, acceptance V-7): the 整组确认页's member set comes from the session's MEMBER pages, not
 * from the review list state — plus the two-path selection that keeps every unwired composition on
 * the pre-D-5 round.
 *
 * The host that runs the loop is a `@Composable` with no JVM harness, so the load-bearing pieces are
 * pure and asserted here: [enumerateImportDuplicateGroupMemberWindow] (one page of members plus its
 * review page → that window's items and its resume cursor), [importGroupEnumerationMemberSource]
 * (which path the round takes), and the real use cases over a fake [ImportReviewReadPort] — the
 * reads are the production ones, not a re-implementation. [MemberPageReadPort] records WHICH read
 * the round used and with which cursor, so "members came from the member pages" is evidence rather
 * than a claim.
 *
 * Coverage map: T1 = [theGroupMembersComeFromTheMemberPagesAndNotTheListState]; T2 =
 * [aMemberWithoutReviewRowsIsConsumedWithoutAnItem]; T3 =
 * [aShortMemberPageWithACursorKeepsTheRoundGoing]; T4 = [aNullMemberCursorEndsTheRound]; T5 =
 * [anUnavailablePageVoidsTheRound]; T6 = [theMemberPagePathIsSelectedOnlyWhenAllThreeReadsAreWired];
 * T7 = [theLegacyRoundStillReadsOnlyTheListStateMembers]; T8 =
 * [aPageHoldingTheWholeGroupLandsInBoundedWindowsWithContinuousOrdinals]; T9 =
 * [anExactMultipleOfTheWindowCapProducesNoEmptyWindow]; T10 = [anEmptyPageProducesNoWindow]; T11 =
 * [aPageShorterThanTheWindowCapLandsAsOneWindow].
 */
class P503ImportGroupMemberWindowHostTest {
    private val ledgerId = LedgerId("ledger-local-test")
    private val session = "pick-handle-1"
    private val reducer: P503Reducer = P503ReducerImpl(ParseManualExpenseAmount(), CurrencyUnit("CNY", 2))
    private val emptyState = LedgerCurrentState(ledgerId, transactions = emptyList(), balances = emptyList())

    // ---- fixtures: one group-member review row, one duplicate-review row, the two page shapes ----

    /**
     * One candidate of the pick session: the group card's member with the default DEFERRED
     * duplicate status, or a plain candidate (`null`) that the pure function filters out.
     */
    private fun memberRow(
        candidateId: String,
        duplicateStatus: ImportDuplicateStatus? = ImportDuplicateStatus.DEFERRED,
    ): ImportReviewRow =
        ImportReviewRow(
            candidateId = ImportCandidateId(candidateId),
            candidateKind = "ordinary_flow",
            sourceInputRef = session,
            amountMinor = 3_580L,
            currencyCode = "CNY",
            currencyPrecision = 2,
            occurredAt = "2026-03-15T02:00:00Z",
            directionToken = "expense",
            statusToken = "trade_success",
            fundingState = ImportFundingState.SETTLED,
            completeness = ImportCompleteness.VALID_COMPLETE,
            contentHash = "sha256:fixed-content-hash",
            candidateStatus = "pending_confirmation",
            requiresConfirmation = true,
            confidence = "high",
            duplicateStatus = duplicateStatus,
            paymentProfileVariant = null,
            paymentProfileAssetLegKindToken = null,
            paymentProfileCreditLegKindToken = null,
        )

    /** One member-major duplicate-review row: the subject is the member, the id is the duplicate. */
    private fun reviewRow(
        member: String,
        duplicateId: String,
    ): ImportDuplicateReviewsForSessionRow =
        ImportDuplicateReviewsForSessionRow(
            subjectCandidateId = ImportCandidateId(member),
            review =
                ImportDuplicateReviewRow(
                    duplicateCandidateId = ImportDuplicateCandidateId(duplicateId),
                    kind = "EXACT_BUSINESS_TUPLE",
                    comparisonFingerprint = "sha256:fixed-fingerprint-$duplicateId",
                    comparisonSnapshot = "{\"amount_minor\":3580}",
                    latestStatus = ImportDuplicateStatus.DEFERRED,
                    reviewDecision = null,
                    reviewReasonToken = null,
                    reviewedAt = null,
                    possibleExistingSource = null,
                ),
        )

    private fun reviews(vararg entries: Pair<String, String>): List<ImportDuplicateReviewsForSessionRow> = entries.map { (member, duplicateId) -> reviewRow(member, duplicateId) }

    /** One member's whole duplicate run, `count` rows — the 20k fixture's shape at test scale. */
    private fun memberReviewRun(
        member: String,
        count: Int,
    ): List<ImportDuplicateReviewsForSessionRow> = (1..count).map { index -> reviewRow(member, "duplicate-$index") }

    /** `count` group items with unique duplicate ids, as the pure enumeration would produce them. */
    private fun dispositionItems(count: Int): List<ImportDuplicateGroupDispositionItem> =
        (1..count).map { index ->
            ImportDuplicateGroupDispositionItem(
                candidateId = ImportCandidateId("member-1"),
                duplicateCandidateId = ImportDuplicateCandidateId("duplicate-$index"),
                comparisonSnapshot = "{\"amount_minor\":3580}",
                expectedComparisonFingerprint = "sha256:fixed-fingerprint-$index",
            )
        }

    private fun sessionPage(
        rows: List<ImportReviewRow>,
        nextAfterCandidateId: String?,
    ): ImportReviewRowsPage = ImportReviewRowsPage(rows, nextAfterCandidateId)

    private fun memberReviewPage(
        rows: List<ImportDuplicateReviewsForSessionRow>,
        nextAfterCandidateId: String?,
    ): ImportDuplicateReviewsForSessionMemberPage =
        ImportDuplicateReviewsForSessionMemberPage(
            rows = rows,
            nextAfterCandidateId = nextAfterCandidateId?.let(::ImportCandidateId),
        )

    // ---- the round's two dispatches, driven through the REAL use cases over a fake port ----

    /**
     * The host's member-page path, minus the dispatcher hops: the real paged reads over the fake
     * port, the per-page assembly, then exactly the host's two loop rules — advance on
     * [ImportDuplicateGroupMemberWindow.nextAfterCandidateId], `firstOrdinal += items.size`.
     */
    private fun memberWindows(
        source: ImportGroupEnumerationMemberSource.MemberPages,
        sessionInputRef: String = session,
    ): List<LandedWindow> {
        val windows = mutableListOf<LandedWindow>()
        var afterCursor: String? = null
        var firstOrdinal = 1
        while (true) {
            val memberPage =
                source.memberPageQuery.querySessionPage(ledgerId, sessionInputRef, MEMBER_PAGE_SIZE, afterCursor)
            val reviewPage =
                source.memberReviewPageQuery.query(ledgerId, sessionInputRef, MEMBER_PAGE_SIZE, afterCursor)
            val window = enumerateImportDuplicateGroupMemberWindow(sessionInputRef, memberPage, reviewPage)
            val enumeration = window.enumeration
            if (enumeration is ImportDuplicateGroupEnumeration.Ready) {
                // The host's landing rule: one page's items land as bounded item windows, and an
                // empty page lands nothing at all.
                val itemWindows = importDuplicateGroupItemWindows(enumeration.items, WINDOW_ITEMS)
                itemWindows.forEach { itemWindow ->
                    windows += LandedWindow(itemWindow, firstOrdinal)
                    firstOrdinal += itemWindow.size
                }
            }
            afterCursor = window.nextAfterCandidateId ?: break
        }
        return windows
    }

    private fun memberSource(port: ImportReviewReadPort): ImportGroupEnumerationMemberSource.MemberPages =
        assertIs<ImportGroupEnumerationMemberSource.MemberPages>(
            importGroupEnumerationMemberSource(
                memberPageQuery = QueryImportReviewRows(port),
                memberReviewPageQuery = QueryImportDuplicateReviewsForSessionMemberPage(port),
                countQuery = CountImportDuplicateCandidatesForSession(port),
            ),
        )

    /** The overview a round starts from: the import surface with the pick session handle. */
    private fun overviewWithPickSession(): P503AppState.OverviewEmpty =
        P503AppState.OverviewEmpty(
            state = emptyState,
            importSurface = true,
            importReview =
                ImportReviewView(
                    lastIntakeSession =
                        ImportIntakeSessionSummary(
                            displayName = "synthetic-bill.csv",
                            inputRef = session,
                            outcome =
                                ImportIntakePipelineOutcome.Intaken(
                                    ImportFileIntakeOutcome.Accepted(records = emptyList(), newCandidateIds = emptyList()),
                                ),
                        ),
                ),
        )

    /** The host's landing dispatch: the first window opens the card, the rest append in order. */
    private fun landedState(
        windows: List<LandedWindow>,
        totalCount: Int,
    ): P503AppState {
        val events = mutableListOf<P503UiEvent>()
        windows.forEachIndexed { index, window ->
            if (index == 0) {
                events +=
                    importGroupEnumerationFirstWindowEvents(
                        sessionInputRef = session,
                        items = window.items,
                        totalCount = totalCount,
                        cardWasOpenAtRoundStart = false,
                        cardStillOpen = false,
                    )
            } else {
                events +=
                    importGroupEnumerationContinuationEvents(
                        sessionInputRef = session,
                        items = window.items,
                        firstOrdinal = window.firstOrdinal,
                        cardStillOpen = true,
                    )
            }
        }
        return events.fold(overviewWithPickSession(), reducer::reduce)
    }

    /** T1 (D-5's core claim, V-7): the members come from the member PAGES, not the list state. */
    @Test
    fun theGroupMembersComeFromTheMemberPagesAndNotTheListState() {
        val port =
            MemberPageReadPort(
                sessionPages =
                    mapOf(
                        null to
                            sessionPage(
                                listOf(memberRow("member-1"), memberRow("member-2"), memberRow("member-3")),
                                "member-3",
                            ),
                        "member-3" to
                            sessionPage(
                                listOf(memberRow("member-4"), memberRow("member-5"), memberRow("member-6")),
                                null,
                            ),
                    ),
                memberReviewPages =
                    mapOf(
                        null to
                            memberReviewPage(
                                reviews(
                                    "member-1" to "duplicate-1",
                                    "member-2" to "duplicate-2",
                                    "member-3" to "duplicate-3",
                                ),
                                "member-3",
                            ),
                        "member-3" to
                            memberReviewPage(
                                reviews(
                                    "member-4" to "duplicate-4",
                                    "member-5" to "duplicate-5",
                                    "member-6" to "duplicate-6",
                                ),
                                null,
                            ),
                    ),
                // The 20k device shape the design's §9 records: the list state never held the group.
                listStateRows = emptyList(),
                groupCount = 6,
            )
        val source = memberSource(port)

        // The 共 N 条 header keeps binding to the count read, never to the landed windows.
        assertEquals(6L, source.countQuery.query(ledgerId, session))

        val windows = memberWindows(source)

        // Two member pages, driven by the page cursor — and the list state never read at all.
        assertEquals(listOf(null, "member-3"), port.sessionCursors)
        assertEquals(listOf(null, "member-3"), port.memberReviewCursors)
        assertEquals(0, port.listStateReads)
        // D-6: both reads are asked for the group page size, independently of the list's 50.
        assertEquals(listOf(200L, 200L, 200L, 200L), port.requestedPageSizes)
        assertEquals(listOf(3, 3), windows.map { it.items.size })

        // Landed through S1's own seams: the ordinals are the production sequence 1..6.
        val events = mutableListOf<P503UiEvent>()
        windows.forEachIndexed { index, window ->
            if (index == 0) {
                events +=
                    importGroupEnumerationFirstWindowEvents(
                        sessionInputRef = session,
                        items = window.items,
                        totalCount = 6,
                        cardWasOpenAtRoundStart = false,
                        cardStillOpen = false,
                    )
            } else {
                events +=
                    importGroupEnumerationContinuationEvents(
                        sessionInputRef = session,
                        items = window.items,
                        firstOrdinal = window.firstOrdinal,
                        cardStillOpen = true,
                    )
            }
        }
        val page = assertIs<P503AppState.OverviewEmpty>(events.fold(overviewWithPickSession(), reducer::reduce)).importReview?.groupDisposition
        assertEquals(
            listOf("duplicate-1", "duplicate-2", "duplicate-3", "duplicate-4", "duplicate-5", "duplicate-6"),
            page?.items?.map { it.item.duplicateCandidateId.value },
        )
        assertEquals(listOf(1, 2, 3, 4, 5, 6), page?.items?.map { it.ordinal })
        assertEquals(6, page?.totalCount)
    }

    /**
     * T2 (V-7 ②): a member whose run carries no review row consumes its turn and contributes no
     * item — and the walk keeps going past it to the NEXT member's item.
     */
    @Test
    fun aMemberWithoutReviewRowsIsConsumedWithoutAnItem() {
        val port =
            MemberPageReadPort(
                sessionPages =
                    mapOf(
                        null to
                            sessionPage(
                                listOf(memberRow("member-1"), memberRow("member-2"), memberRow("member-3")),
                                null,
                            ),
                    ),
                // member-2 has no duplicate-review row at all.
                memberReviewPages = mapOf(null to memberReviewPage(reviews("member-1" to "duplicate-1", "member-3" to "duplicate-3"), null)),
                listStateRows = emptyList(),
                groupCount = 2,
            )

        val windows = memberWindows(memberSource(port))

        assertEquals(1, windows.size)
        assertEquals(listOf("duplicate-1", "duplicate-3"), windows.single().items.map { it.duplicateCandidateId.value })
    }

    /**
     * T3 (V-7 ③): a page holding FEWER members than the page size is not the end. The cursor is the
     * only exhaustion signal — a `rows.size < pageSize` check would have stopped after page one and
     * landed a smaller group.
     */
    @Test
    fun aShortMemberPageWithACursorKeepsTheRoundGoing() {
        val port =
            MemberPageReadPort(
                sessionPages =
                    mapOf(
                        // Three rows, well short of the 200-member page — cursor non-null.
                        null to
                            sessionPage(
                                listOf(memberRow("member-1"), memberRow("member-2"), memberRow("member-3")),
                                "member-3",
                            ),
                        "member-3" to sessionPage(listOf(memberRow("member-4"), memberRow("member-5")), null),
                    ),
                memberReviewPages =
                    mapOf(
                        null to memberReviewPage(reviews("member-1" to "duplicate-1", "member-2" to "duplicate-2"), "member-3"),
                        "member-3" to memberReviewPage(reviews("member-4" to "duplicate-4"), null),
                    ),
                listStateRows = emptyList(),
                groupCount = 3,
            )

        val windows = memberWindows(memberSource(port))

        assertEquals(listOf(null, "member-3"), port.sessionCursors)
        assertEquals(listOf(2, 1), windows.map { it.items.size })
        // The continuation window resumes at its whole-group ordinal, not at a page-local one.
        assertEquals(listOf(1, 3), windows.map { it.firstOrdinal })
    }

    /** T4 (V-7 ④): a null member cursor ends the round — no third page is ever asked for. */
    @Test
    fun aNullMemberCursorEndsTheRound() {
        val port =
            MemberPageReadPort(
                sessionPages =
                    mapOf(
                        null to sessionPage(listOf(memberRow("member-1"), memberRow("member-2")), "member-2"),
                        "member-2" to sessionPage(listOf(memberRow("member-3")), null),
                    ),
                memberReviewPages =
                    mapOf(
                        null to memberReviewPage(reviews("member-1" to "duplicate-1"), "member-2"),
                        "member-2" to memberReviewPage(reviews("member-3" to "duplicate-3"), null),
                    ),
                listStateRows = emptyList(),
                groupCount = 2,
            )

        val windows = memberWindows(memberSource(port))

        // An ignored null cursor would ask the fake for one more page; the fake would then fail the
        // read and this list would be longer (and the window count would not be 2).
        assertEquals(listOf(null, "member-2"), port.sessionCursors)
        assertEquals(listOf(null, "member-2"), port.memberReviewCursors)
        assertEquals(2, windows.size)
    }

    /**
     * T5 (V-7 ⑤, D-3): either page failing typed voids the whole round — the window reports
     * [ImportDuplicateGroupEnumeration.ReadFailed] and S1's round-end seam is the close + banner, so
     * an already-opened first window is never left on screen as a smaller group.
     */
    @Test
    fun anUnavailablePageVoidsTheRound() {
        val memberPage = ImportReviewRowsPageResult.Page(listOf(memberRow("member-1")), null)
        val reviewPage =
            ImportDuplicateReviewsForSessionMemberPageResult.Page(
                memberReviewPage(reviews("member-1" to "duplicate-1"), null),
            )

        assertEquals(
            ImportDuplicateGroupEnumeration.ReadFailed,
            enumerateImportDuplicateGroupMemberWindow(session, ImportReviewRowsPageResult.Unavailable, reviewPage).enumeration,
        )
        assertEquals(
            ImportDuplicateGroupEnumeration.ReadFailed,
            enumerateImportDuplicateGroupMemberWindow(session, memberPage, ImportDuplicateReviewsForSessionMemberPageResult.Unavailable).enumeration,
        )
        assertEquals(
            listOf(
                P503UiEvent.CloseImportDuplicateGroupDisposition,
                P503UiEvent.ImportReviewResult(ImportReviewRowsResult.Unavailable),
            ),
            importGroupEnumerationRoundEndEvents(failed = true, anyPageLanded = true),
        )
    }

    /**
     * T6 (V-7's path half): the member-page path is all-or-nothing. Only a fully wired
     * facade takes it; any missing read — the desktop root of this batch leaves the
     * member-review read unwired — keeps the pre-D-5 round, so its 整组确认页 cannot regress
     * into a typed-failure banner.
     */
    @Test
    fun theMemberPagePathIsSelectedOnlyWhenAllThreeReadsAreWired() {
        val port = MemberPageReadPort(emptyMap(), emptyMap(), emptyList(), groupCount = 0L)
        val memberPageQuery = QueryImportReviewRows(port)
        val memberReviewPageQuery = QueryImportDuplicateReviewsForSessionMemberPage(port)
        val countQuery = CountImportDuplicateCandidatesForSession(port)

        val wired =
            assertIs<ImportGroupEnumerationMemberSource.MemberPages>(
                importGroupEnumerationMemberSource(memberPageQuery, memberReviewPageQuery, countQuery),
            )
        assertSame(memberPageQuery, wired.memberPageQuery)
        assertSame(memberReviewPageQuery, wired.memberReviewPageQuery)
        assertSame(countQuery, wired.countQuery)

        assertEquals(
            ImportGroupEnumerationMemberSource.LegacyListState,
            importGroupEnumerationMemberSource(null, memberReviewPageQuery, countQuery),
        )
        assertEquals(
            ImportGroupEnumerationMemberSource.LegacyListState,
            importGroupEnumerationMemberSource(memberPageQuery, null, countQuery),
        )
        assertEquals(
            ImportGroupEnumerationMemberSource.LegacyListState,
            importGroupEnumerationMemberSource(memberPageQuery, memberReviewPageQuery, null),
        )
        assertEquals(
            ImportGroupEnumerationMemberSource.LegacyListState,
            importGroupEnumerationMemberSource(null, null, null),
        )
    }

    /**
     * T7 (V-7's behaviour-equivalence half): the unwired path is the pre-D-5 round, unchanged
     * — its member source is the loaded review list page, so a member the member pages would have
     * found is NOT enumerated there. That is the old behaviour this batch deliberately preserves;
     * the same port then finds all six members through the member pages (T1).
     */
    @Test
    fun theLegacyRoundStillReadsOnlyTheListStateMembers() {
        val port =
            MemberPageReadPort(
                // Never asked on the legacy path; the fake would throw if it were.
                sessionPages = emptyMap(),
                memberReviewPages = emptyMap(),
                // The pre-D-5 member source: whatever the review list state had loaded.
                listStateRows = listOf(memberRow("member-1"), memberRow("member-2")),
                groupCount = 6,
            )
        assertEquals(
            ImportGroupEnumerationMemberSource.LegacyListState,
            importGroupEnumerationMemberSource(
                memberPageQuery = QueryImportReviewRows(port),
                memberReviewPageQuery = null,
                countQuery = CountImportDuplicateCandidatesForSession(port),
            ),
        )

        // The pre-D-5 assembly, unchanged in shape: members = the list state, reviews per member.
        val rows = assertIs<ImportReviewRowsResult.Rows>(QueryImportReviewRows(port).query(ledgerId)).rows
        val enumeration =
            enumerateImportDuplicateGroupItems(session, rows) { candidateId ->
                ImportDuplicateReviewsResult.Reviews(
                    reviews("member-1" to "duplicate-1", "member-2" to "duplicate-2")
                        .filter { it.subjectCandidateId == candidateId }
                        .map { it.review },
                )
            }
        val ready = assertIs<ImportDuplicateGroupEnumeration.Ready>(enumeration)
        assertEquals(listOf("duplicate-1", "duplicate-2"), ready.items.map { it.duplicateCandidateId.value })
        // Six members exist; the legacy round can only see the two the list state holds.
        assertEquals(6L, port.groupCount)
        assertTrue(port.sessionCursors.isEmpty())
        assertTrue(port.memberReviewCursors.isEmpty())
    }

    /**
     * T8 (the window cap, from the 20k device read): ONE member page can hold the whole group — the
     * 20k fixture is a single member with 20 000 duplicate rows — so the page's items land as
     * bounded windows, and the ordinals stay one continuous 1..n sequence across them.
     */
    @Test
    fun aPageHoldingTheWholeGroupLandsInBoundedWindowsWithContinuousOrdinals() {
        val port =
            MemberPageReadPort(
                sessionPages = mapOf(null to sessionPage(listOf(memberRow("member-1")), null)),
                memberReviewPages =
                    mapOf(null to memberReviewPage(memberReviewRun("member-1", WHOLE_GROUP_ITEMS), null)),
                listStateRows = emptyList(),
                groupCount = WHOLE_GROUP_ITEMS.toLong(),
            )

        val windows = memberWindows(memberSource(port))

        // One member page, 20 000 items, 100 bounded windows of 200.
        assertEquals(WHOLE_GROUP_WINDOWS, windows.size)
        assertTrue(windows.all { it.items.size == WINDOW_ITEMS })
        assertEquals(1, windows.first().firstOrdinal)
        assertEquals(WHOLE_GROUP_ITEMS - WINDOW_ITEMS + 1, windows.last().firstOrdinal)

        // Landed through S1's seams: 20 000 items, ordinals 1..20000 with no gap and no overlap.
        val page =
            assertIs<P503AppState.OverviewEmpty>(landedState(windows, WHOLE_GROUP_ITEMS))
                .importReview
                ?.groupDisposition
        val landedItems = page?.items.orEmpty()
        assertEquals(WHOLE_GROUP_ITEMS, landedItems.size)
        assertEquals((1..WHOLE_GROUP_ITEMS).toList(), landedItems.map { it.ordinal })
        assertEquals("duplicate-1", landedItems.first().item.duplicateCandidateId.value)
        assertEquals("duplicate-20000", landedItems.last().item.duplicateCandidateId.value)
    }

    /** T9: an exact multiple of the cap yields exactly n windows — never a trailing empty one. */
    @Test
    fun anExactMultipleOfTheWindowCapProducesNoEmptyWindow() {
        assertEquals(2, importDuplicateGroupItemWindows(dispositionItems(400), WINDOW_ITEMS).size)
        assertEquals(
            listOf(WINDOW_ITEMS, WINDOW_ITEMS),
            importDuplicateGroupItemWindows(dispositionItems(400), WINDOW_ITEMS).map { it.size },
        )
        assertEquals(1, importDuplicateGroupItemWindows(dispositionItems(WINDOW_ITEMS), WINDOW_ITEMS).size)
        assertEquals(3, importDuplicateGroupItemWindows(dispositionItems(600), WINDOW_ITEMS).size)
        assertTrue(importDuplicateGroupItemWindows(dispositionItems(600), WINDOW_ITEMS).none { it.isEmpty() })
    }

    /** T10: an empty page yields NO window — the host lands nothing, never an empty landing. */
    @Test
    fun anEmptyPageProducesNoWindow() {
        assertTrue(importDuplicateGroupItemWindows(emptyList(), WINDOW_ITEMS).isEmpty())

        // End to end on the member path: a page whose candidates are not group members has no
        // items, so no window reaches the dispatcher.
        val port =
            MemberPageReadPort(
                sessionPages = mapOf(null to sessionPage(listOf(memberRow("plain-1", duplicateStatus = null)), null)),
                memberReviewPages = mapOf(null to memberReviewPage(emptyList(), null)),
                listStateRows = emptyList(),
                groupCount = 0,
            )
        assertTrue(memberWindows(memberSource(port)).isEmpty())
        // With nothing landed, S1's round end is the typed failure end (close + banner) rather than
        // a 0-row card: `anyPageLanded` stays false.
        assertEquals(
            listOf(
                P503UiEvent.CloseImportDuplicateGroupDisposition,
                P503UiEvent.ImportReviewResult(ImportReviewRowsResult.Unavailable),
            ),
            importGroupEnumerationRoundEndEvents(failed = false, anyPageLanded = false),
        )
    }

    /** T11: a page shorter than the cap lands as exactly one window. */
    @Test
    fun aPageShorterThanTheWindowCapLandsAsOneWindow() {
        val windows = importDuplicateGroupItemWindows(dispositionItems(WINDOW_ITEMS - 1), WINDOW_ITEMS)
        assertEquals(1, windows.size)
        assertEquals(WINDOW_ITEMS - 1, windows.single().size)
        assertEquals(1, importDuplicateGroupItemWindows(dispositionItems(1), WINDOW_ITEMS).single().size)
    }

    /** One landed window: the items the host would dispatch and their whole-group ordinal. */
    private class LandedWindow(
        val items: List<ImportDuplicateGroupDispositionItem>,
        val firstOrdinal: Int,
    )
}

/**
 * The round's read port, recording WHICH read the round used and with which cursor — the difference
 * between "the members came from the member pages" and a claim. Page maps are keyed by the cursor
 * the round asks for; an unknown cursor means the round asked for a page it should not have, so the
 * read throws and the real use case surfaces its typed Unavailable.
 */
private class MemberPageReadPort(
    private val sessionPages: Map<String?, ImportReviewRowsPage>,
    private val memberReviewPages: Map<String?, ImportDuplicateReviewsForSessionMemberPage>,
    private val listStateRows: List<ImportReviewRow>,
    val groupCount: Long,
) : ImportReviewReadPort {
    /** The requested page sizes, in call order: the member-page round's one number (D-6). */
    val requestedPageSizes = mutableListOf<Long>()
    val sessionCursors = mutableListOf<String?>()
    val memberReviewCursors = mutableListOf<String?>()
    var listStateReads = 0
        private set

    override fun loadImportReviewRows(ledgerId: LedgerId): List<ImportReviewRow> {
        listStateReads += 1
        return listStateRows
    }

    override fun loadImportReviewRowsForSessionPage(
        ledgerId: LedgerId,
        sessionInputRef: String,
        pageSize: Long,
        afterCandidateId: String?,
    ): ImportReviewRowsPage {
        requestedPageSizes += pageSize
        sessionCursors += afterCandidateId
        return sessionPages[afterCandidateId] ?: error("no session candidate page for cursor $afterCandidateId")
    }

    override fun loadImportDuplicateReviewsForSessionMemberPage(
        ledgerId: LedgerId,
        sessionInputRef: String,
        pageSize: Long,
        afterCandidateId: String?,
    ): ImportDuplicateReviewsForSessionMemberPage {
        requestedPageSizes += pageSize
        memberReviewCursors += afterCandidateId
        return memberReviewPages[afterCandidateId] ?: error("no member review page for cursor $afterCandidateId")
    }

    override fun countImportDuplicateCandidatesForSession(
        ledgerId: LedgerId,
        sessionInputRef: String,
    ): Long = groupCount
}

/**
 * The host constants' values (`P503App.kt`), mirrored here: the round reads 200-member pages, lands
 * at most 200 items per window, and the 20k device fixture is ONE member with 20 000 duplicate rows.
 * A resizing of either bound must not silently change what these tests pin.
 */
private const val MEMBER_PAGE_SIZE = 200L
private const val WINDOW_ITEMS = 200
private const val WHOLE_GROUP_ITEMS = 20_000
private const val WHOLE_GROUP_WINDOWS = 100
