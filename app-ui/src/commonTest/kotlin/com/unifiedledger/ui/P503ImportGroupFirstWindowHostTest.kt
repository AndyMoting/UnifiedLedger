package com.unifiedledger.ui

import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportDuplicateCandidateId
import com.unifiedledger.application.ImportFileIntakeOutcome
import com.unifiedledger.application.ImportReviewRowsResult
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B6 first-window batch (D-280, spec `docs/specs/2026-11-04-b6-group-card-paged-enumeration-design.md`
 * §3.3 首窗事件落盘后立即开卡): the pure dispatch seams of ONE 整组确认页 enumeration round —
 * [importGroupEnumerationFirstWindowEvents], [importGroupEnumerationContinuationEvents] and
 * [importGroupEnumerationRoundEndEvents] — plus the reducer sequence they produce.
 *
 * The host that reads the pages is a `@Composable` with no JVM harness (the `importPickDecisionEvent`
 * precedent), so the round's ORDER-SENSITIVE dispatch decisions live in those seams and are asserted
 * here; the host only calls them from its main-dispatcher hops.
 *
 * Coverage map: T1 = [theFirstWindowOpensTheCardAndTheContinuationWindowsAppendInOrder];
 * T2 = [theRoundKeepsTheProgressFlagUntilTheLastPageLands] (the reducer-level Started/Completed pin
 * itself is pre-existing: `P503ImportReviewReducerTest`
 * `enumerationProgressEventsSetAndClearTheInProgressMarkerOnTheOverview`); T3 =
 * [aFreshRoundOpensTheCardOnTheVeryFirstWindow]; T4 =
 * [aFailedPageClosesWhateverTheFirstWindowOpenedAndLeavesNoSmallerGroup]; T5 =
 * [aRoundTheUserClosedDropsTheRemainingWindowsAndNeverReopens].
 */
class P503ImportGroupFirstWindowHostTest {
    private val ledgerId = LedgerId("ledger-local-test")
    private val cny = CurrencyUnit("CNY", 2)
    private val reducer: P503Reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)
    private val emptyState = LedgerCurrentState(ledgerId, transactions = emptyList(), balances = emptyList())

    private fun groupItem(duplicateId: String): ImportDuplicateGroupDispositionItem =
        ImportDuplicateGroupDispositionItem(
            candidateId = ImportCandidateId("candidate-suspected"),
            duplicateCandidateId = ImportDuplicateCandidateId(duplicateId),
            comparisonSnapshot = "{\"amount_minor\":3580}",
            expectedComparisonFingerprint = "sha256:fixed-fingerprint-$duplicateId",
        )

    /** The overview the host starts a round from: the import surface with the pick session handle. */
    private fun overviewWithPickSession(): P503AppState.OverviewEmpty =
        P503AppState.OverviewEmpty(
            state = emptyState,
            importSurface = true,
            importReview =
                ImportReviewView(
                    // The round reads only the session handle off the summary; the outcome lists are
                    // a state fixture (an empty pick is a legal empty Accepted).
                    lastIntakeSession =
                        ImportIntakeSessionSummary(
                            displayName = "synthetic-bill.csv",
                            inputRef = "pick-handle-1",
                            outcome =
                                ImportIntakePipelineOutcome.Intaken(
                                    ImportFileIntakeOutcome.Accepted(records = emptyList(), newCandidateIds = emptyList()),
                                ),
                        ),
                ),
        )

    private fun reduceAll(
        events: List<P503UiEvent>,
        start: P503AppState = overviewWithPickSession(),
    ): P503AppState = events.fold(start) { state, event -> reducer.reduce(state, event) }

    /**
     * T3 (R2-12/R2-13, spec §3.3 首窗事件落盘后立即开卡): the FIRST window's hop is what opens the
     * card — with THAT window's items and the count query's whole-group total, never with the last
     * page and never after the round ends. A card that was open at round start and is still open is
     * replaced by the round's own first window (the re-run shape); a round with nothing to show opens
     * none at all.
     */
    @Test
    fun aFreshRoundOpensTheCardOnTheVeryFirstWindow() {
        val events =
            importGroupEnumerationFirstWindowEvents(
                sessionInputRef = "pick-handle-1",
                items = listOf(groupItem("duplicate-1"), groupItem("duplicate-2")),
                totalCount = 20_001,
                cardWasOpenAtRoundStart = false,
                cardStillOpen = false,
            )
        val open = assertIs<P503UiEvent.StartImportDuplicateGroupDisposition>(events.single())
        assertEquals("pick-handle-1", open.inputRef)
        assertEquals(listOf("duplicate-1", "duplicate-2"), open.items.map { it.duplicateCandidateId.value })
        // The header binds to the count query's whole-group total, not to the landed window.
        assertEquals(20_001, open.totalCount)

        // The re-run shape: the card was open at round start and is still open — the round opens its
        // OWN first window over it (there is no "already open, so skip" branch), and the replacement
        // keeps the count query's whole-group total rather than the window's item count.
        val stillOpenRound = importGroupEnumerationFirstWindowEvents("pick-handle-1", listOf(groupItem("duplicate-1")), 3, true, true)
        val replacement = assertIs<P503UiEvent.StartImportDuplicateGroupDisposition>(stillOpenRound.single())
        assertEquals(3, replacement.totalCount)
        assertEquals(listOf("duplicate-1"), replacement.items.map { it.duplicateCandidateId.value })

        // The whole-group-empty shape opens no card: the affordance's non-empty group is the only
        // producer of a round, so this is the defensive no-op the unpaged host had.
        assertTrue(
            importGroupEnumerationFirstWindowEvents(
                sessionInputRef = "pick-handle-1",
                items = emptyList(),
                totalCount = 0,
                cardWasOpenAtRoundStart = false,
                cardStillOpen = false,
            ).isEmpty(),
        )
    }

    /**
     * T1 (spec §3.3 + review R2-5): the round's dispatch order is Start FIRST, then the continuation
     * landings — the reverse would let the Start replacement wipe the drained pages. The event lists
     * below are the seams' real output for a 5-item group in 2+2+1 windows, reduced through the real
     * reducer; the ordinals prove the landings append at their whole-group positions rather than at a
     * page-local index.
     */
    @Test
    fun theFirstWindowOpensTheCardAndTheContinuationWindowsAppendInOrder() {
        val opening =
            importGroupEnumerationFirstWindowEvents(
                sessionInputRef = "pick-handle-1",
                items = listOf(groupItem("duplicate-1"), groupItem("duplicate-2")),
                totalCount = 5,
                cardWasOpenAtRoundStart = false,
                cardStillOpen = false,
            )
        val secondWindow =
            importGroupEnumerationContinuationEvents(
                sessionInputRef = "pick-handle-1",
                items = listOf(groupItem("duplicate-3"), groupItem("duplicate-4")),
                firstOrdinal = 3,
                cardStillOpen = true,
            )
        val thirdWindow =
            importGroupEnumerationContinuationEvents(
                sessionInputRef = "pick-handle-1",
                items = listOf(groupItem("duplicate-5")),
                firstOrdinal = 5,
                cardStillOpen = true,
            )
        val end = importGroupEnumerationRoundEndEvents(failed = false, anyPageLanded = true)
        val round = mutableListOf<P503UiEvent>(P503UiEvent.ImportGroupEnumerationStarted)
        round.addAll(opening)
        round.addAll(secondWindow)
        round.addAll(thirdWindow)
        round += P503UiEvent.ImportGroupEnumerationCompleted
        round.addAll(end)

        val page = assertIs<P503AppState.OverviewEmpty>(reduceAll(round)).importReview?.groupDisposition
        assertEquals(
            listOf("duplicate-1", "duplicate-2", "duplicate-3", "duplicate-4", "duplicate-5"),
            page?.items?.map { it.item.duplicateCandidateId.value },
        )
        assertEquals(listOf(1, 2, 3, 4, 5), page?.items?.map { it.ordinal })
        // The whole-group total survives every append.
        assertEquals(5, page?.totalCount)

        // Order sensitivity, asserted rather than described: the SAME two continuation windows
        // reduced BEFORE the Start are absorbed (there is no card to append to) and the Start
        // replacement then wipes whatever they had produced — page 2..3's items are gone. This is the
        // failure a "drain the landings first" host would ship.
        val reversed =
            reduceAll(
                listOf(
                    P503UiEvent.ImportGroupEnumerationPageLanded(
                        "pick-handle-1",
                        listOf(groupItem("duplicate-3"), groupItem("duplicate-4")),
                        firstOrdinal = 3,
                    ),
                    P503UiEvent.StartImportDuplicateGroupDisposition(
                        "pick-handle-1",
                        listOf(groupItem("duplicate-1"), groupItem("duplicate-2")),
                        totalCount = 5,
                    ),
                ),
            )
        val reversedPage = assertIs<P503AppState.OverviewEmpty>(reversed).importReview?.groupDisposition
        assertEquals(listOf("duplicate-1", "duplicate-2"), reversedPage?.items?.map { it.item.duplicateCandidateId.value })
    }

    /**
     * T2 (spec §3.3, the timing change the spec pins): the first window does NOT flip
     * `groupEnumerationInProgress` — the 正在整理重复组…… line is carried by the flag through the
     * continuation pages — and only the round's last-page
     * [P503UiEvent.ImportGroupEnumerationCompleted] clears it, leaving the whole landed group on
     * screen.
     */
    @Test
    fun theRoundKeepsTheProgressFlagUntilTheLastPageLands() {
        val started =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(overviewWithPickSession(), P503UiEvent.ImportGroupEnumerationStarted),
            )
        assertEquals(true, started.importReview?.groupEnumerationInProgress)
        val withFirstWindow =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(
                    started,
                    P503UiEvent.StartImportDuplicateGroupDisposition("pick-handle-1", listOf(groupItem("duplicate-1")), totalCount = 4),
                ),
            )
        // The card is open AND the flag is still on: the continuation state the spec's progress line
        // lives in.
        assertEquals("pick-handle-1", withFirstWindow.importReview?.groupDisposition?.inputRef)
        assertEquals(true, withFirstWindow.importReview?.groupEnumerationInProgress)
        val stillLanding =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(
                    withFirstWindow,
                    P503UiEvent.ImportGroupEnumerationPageLanded("pick-handle-1", listOf(groupItem("duplicate-2")), firstOrdinal = 2),
                ),
            )
        assertEquals(true, stillLanding.importReview?.groupEnumerationInProgress)
        val completed =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(stillLanding, P503UiEvent.ImportGroupEnumerationCompleted),
            )
        assertEquals(false, completed.importReview?.groupEnumerationInProgress)
        assertEquals(
            listOf(1, 2),
            completed.importReview
                ?.groupDisposition
                ?.items
                ?.map { it.ordinal },
        )
    }

    /**
     * T4 (R2-1 / spec §2.3): any typed page failure voids the WHOLE round — the round's end is the
     * close plus the typed list-failure banner, never a "smaller group" built from the landed prefix,
     * and reduced over a card the first window already opened it leaves zero rows behind (the banner
     * replaces the card).
     */
    @Test
    fun aFailedPageClosesWhateverTheFirstWindowOpenedAndLeavesNoSmallerGroup() {
        val failed = importGroupEnumerationRoundEndEvents(failed = true, anyPageLanded = true)
        assertEquals(2, failed.size)
        assertEquals(P503UiEvent.CloseImportDuplicateGroupDisposition, failed.first())
        assertEquals(ImportReviewRowsResult.Unavailable, assertIs<P503UiEvent.ImportReviewResult>(failed[1]).result)
        assertTrue(
            failed.none {
                it is P503UiEvent.StartImportDuplicateGroupDisposition || it is P503UiEvent.ImportGroupEnumerationPageLanded
            },
        )

        // Reduced over an OPEN card (the first window landed, then a later page failed): the card is
        // gone — the already-landed prefix is never left on screen as a smaller group.
        val opened =
            reduceAll(
                listOf(
                    P503UiEvent.StartImportDuplicateGroupDisposition(
                        "pick-handle-1",
                        listOf(groupItem("duplicate-1"), groupItem("duplicate-2")),
                        totalCount = 5,
                    ),
                ),
            )
        val openedPage = assertIs<P503AppState.OverviewEmpty>(opened).importReview?.groupDisposition
        assertEquals(2, openedPage?.items?.size)
        val afterFailure = assertIs<P503AppState.OverviewEmpty>(reduceAll(failed, opened))
        assertNull(afterFailure.importReview?.groupDisposition)
        assertEquals(ImportReviewNotice.ReviewReadFailed, afterFailure.importReview?.notice)

        // A completed round dispatches nothing of its own (the landings published every item); a
        // round that landed NO window is the same typed-failure end as a failed page.
        assertTrue(importGroupEnumerationRoundEndEvents(failed = false, anyPageLanded = true).isEmpty())
        assertEquals(failed, importGroupEnumerationRoundEndEvents(failed = false, anyPageLanded = false))
    }

    /**
     * T5 (R2-13 / spec §3.5): the user's 返回 closes the card mid-round — the round must not reopen it
     * and every later window is dropped. Both moments read the SAME hop-time `cardStillOpen()` (no new
     * state bit): the opening drops (no Start), the continuation drops (no landing), and the round's
     * own end stays the successful no-op. The close itself does not clear the progress marker — only
     * the round's Completed does (spec §3.3) — so the line keeps reporting a round that is still
     * finishing while the dropped windows leave the closed card closed.
     */
    @Test
    fun aRoundTheUserClosedDropsTheRemainingWindowsAndNeverReopens() {
        assertTrue(
            importGroupEnumerationFirstWindowEvents(
                sessionInputRef = "pick-handle-1",
                items = listOf(groupItem("duplicate-1")),
                totalCount = 3,
                cardWasOpenAtRoundStart = true,
                cardStillOpen = false,
            ).isEmpty(),
        )
        assertTrue(
            importGroupEnumerationContinuationEvents(
                sessionInputRef = "pick-handle-1",
                items = listOf(groupItem("duplicate-2")),
                firstOrdinal = 2,
                cardStillOpen = false,
            ).isEmpty(),
        )
        // The card is open (the first window landed, the flag still on) and the user closed it.
        val opened =
            reduceAll(
                listOf(
                    P503UiEvent.ImportGroupEnumerationStarted,
                    P503UiEvent.StartImportDuplicateGroupDisposition("pick-handle-1", listOf(groupItem("duplicate-1")), totalCount = 3),
                ),
            )
        val closed = reduceAll(listOf(P503UiEvent.CloseImportDuplicateGroupDisposition), opened)
        assertNull(assertIs<P503AppState.OverviewEmpty>(closed).importReview?.groupDisposition)
        // Every window the round still had queued dispatches NOTHING, so the closed card cannot be
        // reopened by a late landing.
        val lateOpening =
            importGroupEnumerationFirstWindowEvents(
                sessionInputRef = "pick-handle-1",
                items = listOf(groupItem("duplicate-1")),
                totalCount = 3,
                cardWasOpenAtRoundStart = true,
                cardStillOpen = false,
            )
        val lateContinuation =
            importGroupEnumerationContinuationEvents(
                sessionInputRef = "pick-handle-1",
                items = listOf(groupItem("duplicate-2")),
                firstOrdinal = 2,
                cardStillOpen = false,
            )
        val lateEnd = importGroupEnumerationRoundEndEvents(failed = false, anyPageLanded = true)
        val dropped = mutableListOf<P503UiEvent>()
        dropped.addAll(lateOpening)
        dropped.addAll(lateContinuation)
        dropped.addAll(lateEnd)
        assertTrue(dropped.isEmpty())
        val afterLateWindows = assertIs<P503AppState.OverviewEmpty>(reduceAll(dropped, closed))
        assertNull(afterLateWindows.importReview?.groupDisposition)
        assertEquals(true, afterLateWindows.importReview?.groupEnumerationInProgress)
    }
}
