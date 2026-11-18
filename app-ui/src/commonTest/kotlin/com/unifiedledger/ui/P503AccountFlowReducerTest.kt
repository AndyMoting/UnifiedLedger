package com.unifiedledger.ui

import com.unifiedledger.application.P806AccountCursor
import com.unifiedledger.application.P806AccountPostingRow
import com.unifiedledger.application.P806AccountPostingsPage
import com.unifiedledger.application.P806AccountView
import com.unifiedledger.application.P806SnapshotFailure
import com.unifiedledger.application.P806SnapshotResult
import com.unifiedledger.application.RecycleBinResult
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P8-06 batch b (D-343 sections 1.3/3.2.2/3.7) state-machine tests for the account-flow surface: the open
 * lands in the explicit not-loaded state; the first and continuation reads are single-flight;
 * a landing applies only while its request identity, generation and catalog version still match;
 * a superseded landing never touches a newer request, while a matching request whose generation
 * or catalog advanced ends only its own loading and retries from the first page; failures keep
 * rows/count/cursor. The event family is absorbed in every other state (table 6.2a — zero ISE
 * outside the designed states), and every pre-existing unlisted combination stays ISE (G-B).
 */
class P503AccountFlowReducerTest {
    private val ledgerId = LedgerId("ledger-account-flow-test")
    private val otherLedgerId = LedgerId("ledger-account-flow-other")
    private val cny = CurrencyUnit("CNY", 2)
    private val paymentAccountId = AccountId("asset-payment-local")
    private val otherAccountId = AccountId("asset-other")
    private val occurredAt = Instant.parse("2026-03-15T02:00:00Z")
    private val fixedClock = com.unifiedledger.application.LedgerClock { Instant.parse("2026-09-15T02:00:00Z") }
    private val reducer = P503ReducerImpl(com.unifiedledger.application.ParseManualExpenseAmount(), cny, ledgerClock = fixedClock)

    /** The capture values of the "current" graph the landing hop compares against. */
    private val generation: Generation = 7
    private val otherGeneration: Generation = 8
    private val catalogVersion = 41L
    private val otherCatalogVersion = 42L

    /** The host's monotonic request-token sequence (one per test instance). */
    private var issuedTokens = 0L

    private val oneTransactionState =
        com.unifiedledger.application.LedgerCurrentState(
            ledgerId = ledgerId,
            transactions = emptyList(),
            balances = emptyList(),
        )

    private fun overview(scroll: ListScrollPosition? = null): P503AppState.OverviewEmpty =
        P503AppState.OverviewEmpty(
            state = oneTransactionState,
            selectedTab = P503Tab.ACCOUNTS,
            originListScroll = scroll,
        )

    private fun posting(
        postingId: String = "posting-1",
        transactionId: String = "tx-1",
        amountMinor: Long = -3_580L,
        note: String? = null,
        occurredAt: Instant = this.occurredAt,
        kind: TransactionKind = TransactionKind.EXPENSE,
    ): P806AccountPostingRow =
        P806AccountPostingRow(
            postingId = PostingId(postingId),
            transactionId = TransactionId(transactionId),
            occurredAt = occurredAt,
            note = note,
            kind = kind,
            amount = Money.ofMinor(amountMinor, cny),
        )

    private fun cursor(occurredAtEpochNanos: Long = 1_000L, postingId: String = "posting-1"): P806AccountCursor = P806AccountCursor(occurredAtEpochNanos, postingId)

    private fun view(
        rows: List<P806AccountPostingRow>,
        nextCursor: P806AccountCursor?,
        totalCount: Int,
        ledgerId: LedgerId = this.ledgerId,
        accountId: AccountId = paymentAccountId,
    ): P806AccountView =
        P806AccountView(
            ledgerId = ledgerId,
            accountId = accountId,
            page = P806AccountPostingsPage(rows = rows, nextCursor = nextCursor),
            totalCount = totalCount,
        )

    private fun identity(
        token: Long,
        cursor: P806AccountCursor? = null,
        ledgerId: LedgerId = this.ledgerId,
        accountId: AccountId = paymentAccountId,
    ): P806AccountRequestIdentity =
        P806AccountRequestIdentity(
            token = token,
            ledgerId = ledgerId,
            accountId = accountId,
            cursor = cursor,
        )

    private fun landedPage(
        token: Long,
        rows: List<P806AccountPostingRow>,
        nextCursor: P806AccountCursor? = null,
        totalCount: Int = rows.size,
        requestCursor: P806AccountCursor? = null,
        generation: Generation = this.generation,
        catalogVersion: Long = this.catalogVersion,
    ): P806AccountPageRead.Landed =
        P806AccountPageRead.Landed(
            capture = P806AccountReadCapture(identity(token, requestCursor), generation, catalogVersion),
            result = P806SnapshotResult.Success(view(rows, nextCursor, totalCount)),
        )

    private fun landedFailure(
        token: Long,
        failure: P806SnapshotFailure,
        requestCursor: P806AccountCursor? = null,
    ): P806AccountPageRead.Landed =
        P806AccountPageRead.Landed(
            capture = P806AccountReadCapture(identity(token, requestCursor), generation, catalogVersion),
            result = P806SnapshotResult.Failed(failure),
        )

    private fun landing(
        read: P806AccountPageRead,
        currentGeneration: Generation? = generation,
        currentCatalogVersion: Long? = catalogVersion,
    ): P503UiEvent.AccountFlowPageLanded =
        P503UiEvent.AccountFlowPageLanded(
            read = read,
            currentGeneration = currentGeneration,
            currentCatalogVersion = currentCatalogVersion,
        )

    private fun openEvent(): P503UiEvent.SelectAccountFlow =
        P503UiEvent.SelectAccountFlow(
            accountId = paymentAccountId,
            balanceLine = "默认支付账户 · 资产 · CNY",
            balanceMinorUnits = -3_580L,
            balanceCurrency = cny,
            originListScroll = ListScrollPosition(3, 24),
        )

    private fun queueEvent(originScroll: ListScrollPosition? = ListScrollPosition(2, 10)): P503UiEvent.SelectReconciliationQueue =
        P503UiEvent.SelectReconciliationQueue(
            accountId = paymentAccountId,
            accountLine = "默认支付账户 · 资产 · CNY",
            firstPage = com.unifiedledger.application.P801AccountReconciliationPageResult.Success(com.unifiedledger.application.P801AccountReconciliationQueuePage(emptyList(), null)),
            summary = com.unifiedledger.application.P801AccountReconciliationSummaryResult.Success(emptyList()),
            originScroll = originScroll,
        )

    private fun enterAccountFlow(source: P503AppState.OverviewEmpty = overview()): P503AppState.AccountFlow =
        assertIs<P503AppState.AccountFlow>(reducer.reduce(source, openEvent()))

    private fun requestPage(
        state: P503AppState.AccountFlow,
        currentGeneration: Generation? = generation,
        currentCatalogVersion: Long? = catalogVersion,
    ): P503AppState.AccountFlow =
        assertIs<P503AppState.AccountFlow>(
            reducer.reduce(
                state,
                P503UiEvent.RequestAccountFlowMorePage(
                    requestToken = ++issuedTokens,
                    currentGeneration = currentGeneration,
                    currentCatalogVersion = currentCatalogVersion,
                ),
            ),
        )

    private fun land(
        state: P503AppState.AccountFlow,
        event: P503UiEvent.AccountFlowPageLanded,
    ): P503AppState.AccountFlow = assertIs<P503AppState.AccountFlow>(reducer.reduce(state, event))

    /** Open → first request → the first page landed (the ordinary loaded surface). */
    private fun loadedFlow(
        rows: List<P806AccountPostingRow> = listOf(posting()),
        nextCursor: P806AccountCursor? = cursor(),
        totalCount: Int = rows.size,
    ): P503AppState.AccountFlow {
        val requesting = requestPage(enterAccountFlow())
        assertEquals(1L, requesting.pageRequestToken)
        return land(requesting, landing(landedPage(1, rows, nextCursor, totalCount)))
    }

    // ---- open + first background page ----

    @Test
    fun theOpenLandsInTheExplicitNotLoadedStateWithTheSampledScroll() {
        val source = overview()
        val entered = enterAccountFlow(source)
        assertEquals(paymentAccountId, entered.accountId)
        assertEquals(emptyList(), entered.loadedRows)
        assertNull(entered.totalCount)
        assertNull(entered.nextCursor)
        assertTrue(!entered.rowsLoading)
        assertNull(entered.failure)
        assertTrue(!entered.unwired)
        assertEquals(0L, entered.pageRequestToken)
        assertEquals("默认支付账户 · 资产 · CNY", entered.balanceLine)
        assertEquals(-3_580L, entered.balanceMinorUnits)
        // The sampled ACCOUNTS scroll rides the carried overview (the source held none).
        assertEquals(ListScrollPosition(3, 24), entered.overview.originListScroll)
        assertEquals(P503Tab.ACCOUNTS, entered.overview.selectedTab)
        assertEquals(AccountFlowBodyFace.NOT_LOADED, accountFlowBodyFace(entered))
    }

    @Test
    fun theFirstBackgroundPageLandsTheRowsAndTheExactCountOnce() {
        val entered = enterAccountFlow()
        val requesting = requestPage(entered)
        assertTrue(requesting.rowsLoading)
        assertEquals(1L, requesting.pageRequestToken)
        val landed =
            land(
                requesting,
                landing(
                    landedPage(
                        token = 1,
                        rows = listOf(posting(postingId = "posting-1", transactionId = "tx-1"), posting(postingId = "posting-2", transactionId = "tx-1")),
                        nextCursor = cursor(),
                        totalCount = 2,
                    ),
                ),
            )
        // Two postings of the same transaction stay TWO rows (the posting unit, no transaction
        // de-duplication) in the read's own order.
        assertEquals(listOf("posting-1", "posting-2"), landed.loadedRows.map { it.postingId.value })
        assertEquals(2, landed.totalCount)
        assertEquals(cursor(), landed.nextCursor)
        assertTrue(!landed.rowsLoading)
        assertNull(landed.failure)
        // The landed payload records the provenance a continuation must still match.
        assertEquals(generation, landed.loadedGeneration)
        assertEquals(catalogVersion, landed.loadedCatalogVersion)
        assertEquals(AccountFlowBodyFace.LIST, accountFlowBodyFace(landed))
    }

    @Test
    fun anEmptySuccessIsItsOwnFaceAndNeverAFailureOrANotLoadedWindow() {
        val landed = loadedFlow(rows = emptyList(), nextCursor = null, totalCount = 0)
        assertEquals(0, landed.totalCount)
        assertNull(landed.failure)
        assertEquals(AccountFlowBodyFace.EMPTY, accountFlowBodyFace(landed))
        // Nothing more to request on the honest empty end.
        assertSame(
            landed,
            reducer.reduce(
                landed,
                P503UiEvent.RequestAccountFlowMorePage(++issuedTokens, generation, catalogVersion),
            ),
        )
    }

    @Test
    fun aRefusedLeaseAndAnUnwiredGraphAreDistinctTypedStates() {
        val requesting = requestPage(enterAccountFlow())
        // A refused lease carries no generation, so it is not gated on one: the matching active
        // request ends as the typed Unavailable.
        val refused = land(requesting, landing(P806AccountPageRead.Refused(identity(1)), currentGeneration = otherGeneration))
        assertEquals(P806SnapshotFailure.Unavailable, refused.failure)
        assertTrue(!refused.unwired)
        assertEquals(AccountFlowBodyFace.FAILED, accountFlowBodyFace(refused))
        // An acquired-but-unwired graph carries the acquire generation and is gated on it.
        val unwiredRequesting = requestPage(refused)
        val unwired = land(unwiredRequesting, landing(P806AccountPageRead.Unwired(identity(2), generation)))
        assertTrue(unwired.unwired)
        assertNull(unwired.totalCount)
        assertEquals(AccountFlowBodyFace.UNWIRED, accountFlowBodyFace(unwired))
        // A stale acquire generation never turns into the unwired state (it ends this request as
        // the typed graph-swap failure instead).
        val freshRequesting = requestPage(enterAccountFlow())
        val staleUnwired =
            land(
                freshRequesting,
                landing(P806AccountPageRead.Unwired(identity(freshRequesting.pageRequestToken), otherGeneration)),
            )
        assertTrue(!staleUnwired.unwired)
        assertEquals(P806SnapshotFailure.Unavailable, staleUnwired.failure)
    }

    // ---- the request identity gate ----

    @Test
    fun aDuplicateRequestDoesNotAdvanceTheTokenOrStartASecondRead() {
        val requesting = requestPage(enterAccountFlow())
        val duplicate = requestPage(requesting)
        assertEquals(requesting, duplicate)
    }

    @Test
    fun aStaleForeignOrCursorMismatchedLandingChangesNothing() {
        val loaded = loadedFlow()
        // An ACTIVE continuation exists; a completion with an old token, another account, another
        // ledger or a cursor the surface is no longer at is stale — the request keeps its state.
        val active = requestPage(loaded)
        assertSame(active, reducer.reduce(active, landing(landedPage(1, listOf(posting(postingId = "posting-x"))))))
        val foreignAccount =
            P806AccountPageRead.Landed(
                capture = P806AccountReadCapture(identity(active.pageRequestToken, accountId = otherAccountId, cursor = cursor()), generation, catalogVersion),
                result = P806SnapshotResult.Success(view(listOf(posting(postingId = "posting-x")), null, 1, accountId = otherAccountId)),
            )
        assertSame(active, reducer.reduce(active, landing(foreignAccount)))
        val foreignLedger =
            P806AccountPageRead.Landed(
                capture = P806AccountReadCapture(identity(active.pageRequestToken, ledgerId = otherLedgerId, cursor = cursor()), generation, catalogVersion),
                result = P806SnapshotResult.Success(view(listOf(posting(postingId = "posting-x")), null, 1, ledgerId = otherLedgerId)),
            )
        assertSame(active, reducer.reduce(active, landing(foreignLedger)))
        val wrongCursor = landedPage(active.pageRequestToken, listOf(posting(postingId = "posting-x")), requestCursor = cursor(9_999L))
        assertSame(active, reducer.reduce(active, landing(wrongCursor)))
    }

    @Test
    fun aCompletionAfterTheRequestSettledChangesNothing() {
        // A duplicate completion, and any completion that arrives after a read-only return, finds
        // no ACTIVE request: it never writes rows/count/cursor.
        val settled = loadedFlow()
        assertSame(settled, reducer.reduce(settled, landing(landedPage(1, listOf(posting(postingId = "posting-again")), nextCursor = null, totalCount = 9))))
    }

    @Test
    fun aSuccessPayloadWhoseLedgerOrAccountDiffersFailsTheActiveRequestOnly() {
        val loaded = loadedFlow()
        val requesting = requestPage(loaded)
        val mismatchedPayload =
            P806AccountPageRead.Landed(
                capture = P806AccountReadCapture(identity(requesting.pageRequestToken, cursor()), generation, catalogVersion),
                result = P806SnapshotResult.Success(view(listOf(posting(postingId = "posting-x")), null, 9, accountId = otherAccountId)),
            )
        // The identity matches the active request, but the payload names another account: the
        // payload is dropped and only this request ends, as the typed InvalidState — the loaded
        // page set and its cursor stay.
        val rejected = land(requesting, landing(mismatchedPayload))
        assertEquals(loaded.loadedRows, rejected.loadedRows)
        assertEquals(loaded.totalCount, rejected.totalCount)
        assertEquals(cursor(), rejected.nextCursor)
        assertTrue(!rejected.rowsLoading)
        assertEquals(P806SnapshotFailure.InvalidState, rejected.failure)
    }

    @Test
    fun aNewerRequestKeepsItsLoadingWhenAnOldLandingArrives() {
        val loaded = loadedFlow()
        val newer = requestPage(loaded)
        assertEquals(2L, newer.pageRequestToken)
        // The old page (token 1) lands late: nothing is written and the newer request stays in
        // flight — only the matching request's marker is cleared on landing.
        assertSame(newer, reducer.reduce(newer, landing(landedPage(1, listOf(posting(postingId = "posting-late"))))))
    }

    @Test
    fun aGenerationChangeEndsOnlyTheMatchingRequestAndResetsTheCursor() {
        val loaded = loadedFlow()
        val requesting = requestPage(loaded)
        val replaced = land(requesting, landing(landedPage(2, listOf(posting(postingId = "posting-old")), requestCursor = cursor()), currentGeneration = otherGeneration))
        // The old graph's rows/count are NOT written; the surface keeps the previous success,
        // ends this request's loading and asks for an explainable retry from the first page.
        assertEquals(loaded.loadedRows, replaced.loadedRows)
        assertEquals(loaded.totalCount, replaced.totalCount)
        assertTrue(!replaced.rowsLoading)
        assertEquals(P806SnapshotFailure.Unavailable, replaced.failure)
        assertNull(replaced.nextCursor)
        // The retry therefore requests the first page (cursor-less identity).
        val retry = requestPage(replaced)
        assertEquals(3L, retry.pageRequestToken)
        assertEquals(AccountFlowBodyFace.LIST, accountFlowBodyFace(retry))
        assertTrue(retry.rowsLoading)
    }

    @Test
    fun aCatalogAdvanceRejectsTheOldCursorAndTheFirstPageRetryReplacesThePayload() {
        val first = loadedFlow(rows = listOf(posting(postingId = "posting-1")), nextCursor = cursor(), totalCount = 5)
        val requesting = requestPage(first)
        val advanced = land(requesting, landing(landedPage(2, listOf(posting(postingId = "posting-old")), requestCursor = cursor()), currentCatalogVersion = otherCatalogVersion))
        assertEquals(P806SnapshotFailure.CatalogVersionMismatch, advanced.failure)
        assertNull(advanced.nextCursor)
        assertEquals(first.loadedRows, advanced.loadedRows)
        assertEquals(5, advanced.totalCount)
        // The retry reads the FIRST page; its success replaces rows/count instead of appending to
        // the stale page set.
        val retry = requestPage(advanced)
        assertEquals(3L, retry.pageRequestToken)
        val replaced =
            land(
                retry,
                landing(landedPage(3, listOf(posting(postingId = "posting-a"), posting(postingId = "posting-b")), nextCursor = null, totalCount = 6)),
            )
        assertEquals(listOf("posting-a", "posting-b"), replaced.loadedRows.map { it.postingId.value })
        assertEquals(6, replaced.totalCount)
        assertNull(replaced.nextCursor)
        assertNull(replaced.failure)
    }

    @Test
    fun aTokenNotNewerThanTheSurfaceNeverRestartsARead() {
        val loaded = loadedFlow()
        // The host's monotonic token must be newer than the stored one: a stale host call is
        // absorbed whole (no loading marker, no token move).
        assertSame(loaded, reducer.reduce(loaded, P503UiEvent.RequestAccountFlowMorePage(1L, generation, catalogVersion)))
        val newer = requestPage(loaded)
        assertEquals(2L, newer.pageRequestToken)
        assertTrue(newer.rowsLoading)
    }

    @Test
    fun aLandingFromThePreviousOpenNeverLandsInAReopenedSurface() {
        // Open → first page (host token 1) → close → same account reopened: the reopened surface
        // starts at token 0, so the previous open's in-flight landing is stale.
        val previous = loadedFlow()
        val closed = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(previous, P503UiEvent.CloseAccountFlow))
        val reopened = assertIs<P503AppState.AccountFlow>(reducer.reduce(closed, openEvent()))
        assertEquals(0L, reopened.pageRequestToken)
        assertSame(
            reopened,
            reducer.reduce(reopened, landing(landedPage(1, listOf(posting(postingId = "posting-late")), nextCursor = null, totalCount = 1))),
        )
        // Even after the reopened surface issues its own request (the host sequence keeps
        // increasing), the previous open's token-1 read stays stale and the new request keeps its
        // loading marker.
        val requesting = requestPage(reopened)
        assertEquals(2L, requesting.pageRequestToken)
        assertSame(
            requesting,
            reducer.reduce(requesting, landing(landedPage(1, listOf(posting(postingId = "posting-late")), nextCursor = null, totalCount = 1))),
        )
        assertTrue(requesting.rowsLoading)
    }

    @Test
    fun aContinuationIsNeverIssuedUnderAnAdvancedCatalogOrGeneration() {
        val loaded = loadedFlow(rows = listOf(posting(postingId = "posting-1")), nextCursor = cursor(), totalCount = 5)
        // The catalog moved BEFORE the read was issued: the reducer drops the cursor, so the host
        // requests the first page and never sends the old cursor into the new catalog.
        val catalogAdvanced = requestPage(loaded, currentCatalogVersion = otherCatalogVersion)
        assertNull(catalogAdvanced.nextCursor)
        assertEquals(P806SnapshotFailure.CatalogVersionMismatch, catalogAdvanced.failure)
        assertTrue(catalogAdvanced.rowsLoading)
        // The first-page retry replaces rows/count instead of splicing two page sets.
        val replaced =
            land(
                catalogAdvanced,
                landing(landedPage(catalogAdvanced.pageRequestToken, listOf(posting(postingId = "posting-a")), nextCursor = null, totalCount = 6)),
            )
        assertEquals(listOf("posting-a"), replaced.loadedRows.map { it.postingId.value })
        assertEquals(6, replaced.totalCount)
        assertNull(replaced.failure)
        // The replacement page set records its own provenance (the landing fixture's version).
        assertEquals(catalogVersion, replaced.loadedCatalogVersion)
        // A generation change before the read gets the same reset, with the Unavailable reason.
        val generationAdvanced = requestPage(loaded, currentGeneration = otherGeneration)
        assertNull(generationAdvanced.nextCursor)
        assertEquals(P806SnapshotFailure.Unavailable, generationAdvanced.failure)
    }

    @Test
    fun aContinuationWhosePageSetProvenanceAdvancedIsReplacedWhole() {
        // The in-lease check can still find a provenance mismatch (a catalog advance between the
        // pre-check and the acquire): the read reports StaleProvenance without sending the old
        // cursor; the surface keeps the old rows/count and retries from the first page.
        val loaded = loadedFlow(rows = listOf(posting(postingId = "posting-1")), nextCursor = cursor(), totalCount = 5)
        val requesting = requestPage(loaded)
        val stale =
            P806AccountPageRead.StaleProvenance(
                request = identity(requesting.pageRequestToken, cursor()),
                failure = P806SnapshotFailure.CatalogVersionMismatch,
            )
        val reset = land(requesting, landing(stale, currentCatalogVersion = otherCatalogVersion))
        assertEquals(loaded.loadedRows, reset.loadedRows)
        assertEquals(5, reset.totalCount)
        assertEquals(P806SnapshotFailure.CatalogVersionMismatch, reset.failure)
        assertNull(reset.nextCursor)
        assertTrue(!reset.rowsLoading)
    }

    // ---- failures, retries and continuation ----

    @Test
    fun aFirstPageFailureRetriesTheFirstPageAndAContinuationFailureKeepsTheCursor() {
        val entering = requestPage(enterAccountFlow())
        val firstFailed = land(entering, landing(landedFailure(1, P806SnapshotFailure.Unavailable)))
        assertEquals(P806SnapshotFailure.Unavailable, firstFailed.failure)
        assertNull(firstFailed.totalCount)
        assertNull(firstFailed.nextCursor)
        assertEquals(AccountFlowBodyFace.FAILED, accountFlowBodyFace(firstFailed))
        val firstRetry = requestPage(firstFailed)
        assertEquals(2L, firstRetry.pageRequestToken)
        val firstLanded = land(firstRetry, landing(landedPage(2, listOf(posting(postingId = "posting-1")), nextCursor = cursor(), totalCount = 3)))
        assertEquals(1, firstLanded.loadedRows.size)
        assertEquals(3, firstLanded.totalCount)
        // A continuation failure keeps rows, count and the page cursor; the retry re-requests the
        // SAME cursor (never the first page).
        val more = requestPage(firstLanded)
        assertEquals(cursor(), more.nextCursor)
        val continuationFailed = land(more, landing(landedFailure(3, P806SnapshotFailure.Unavailable, requestCursor = cursor())))
        assertEquals(firstLanded.loadedRows, continuationFailed.loadedRows)
        assertEquals(3, continuationFailed.totalCount)
        assertEquals(cursor(), continuationFailed.nextCursor)
        assertEquals(P806SnapshotFailure.Unavailable, continuationFailed.failure)
        val retry = requestPage(continuationFailed)
        assertEquals(4L, retry.pageRequestToken)
        assertEquals(cursor(), retry.nextCursor)
        val appended =
            land(
                retry,
                landing(landedPage(4, listOf(posting(postingId = "posting-2")), nextCursor = null, totalCount = 3, requestCursor = cursor())),
            )
        assertEquals(listOf("posting-1", "posting-2"), appended.loadedRows.map { it.postingId.value })
        assertEquals(3, appended.totalCount)
        assertNull(appended.nextCursor)
        assertEquals(AccountFlowBodyFace.LIST, accountFlowBodyFace(appended))
    }

    @Test
    fun anEndedWalkWithoutFailureRequestsNothing() {
        val ended = loadedFlow(rows = listOf(posting()), nextCursor = null, totalCount = 1)
        assertSame(ended, reducer.reduce(ended, P503UiEvent.RequestAccountFlowMorePage(++issuedTokens, generation, catalogVersion)))
    }

    // ---- exits and scroll restoration (unchanged mechanisms) ----

    @Test
    fun closeAccountFlowReturnsToTheExactOverviewWithTheSampledScrollPosition() {
        val source = overview()
        val entered = enterAccountFlow(source)
        val closed = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(entered, P503UiEvent.CloseAccountFlow))
        assertEquals(ListScrollPosition(3, 24), closed.originListScroll)
        assertEquals(P503Tab.ACCOUNTS, closed.selectedTab)
        assertSame(source.state, closed.state)
    }

    @Test
    fun backOnTheAccountFlowClosesWithTheSameSemantics() {
        val source = overview()
        val entered = enterAccountFlow(source)
        assertEquals(ListScrollPosition(3, 24), entered.overview.originListScroll)
        val closed = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(entered, P503UiEvent.Back))
        assertSame(entered.overview, closed)
        assertEquals(P503Tab.ACCOUNTS, closed.selectedTab)
    }

    @Test
    fun leavingForTheReconciliationQueueKeepsThePayloadAndClearsTheInFlightMarker() {
        // A page read is in flight when the queue opens: the carried flow keeps the landed page and
        // count, drops the loading marker (no permanently-loading footer on return) and issues no
        // read; the request token stays the host's own sequence value.
        val requesting = requestPage(enterAccountFlow())
        val queue = assertIs<P503AppState.ReconciliationQueue>(reducer.reduce(requesting, queueEvent()))
        assertEquals(ListScrollPosition(2, 10), queue.returnTo.listScroll)
        assertTrue(!queue.returnTo.rowsLoading)
        assertEquals(1L, queue.returnTo.pageRequestToken)
        // The landing that was in flight while the queue opened is absorbed there — it can never
        // write the queue's state.
        assertSame(
            queue,
            reducer.reduce(queue, landing(landedPage(1, listOf(posting(postingId = "posting-late"))))),
        )
        val returned = assertIs<P503AppState.AccountFlow>(reducer.reduce(queue, P503UiEvent.Back))
        assertSame(queue.returnTo, returned)
        assertTrue(!returned.rowsLoading)
        // The read that was in flight when the queue opened completes AFTER the return: with no
        // active request on the returned surface it must not write (and the return issues no read).
        assertSame(
            returned,
            reducer.reduce(returned, landing(landedPage(1, listOf(posting(postingId = "posting-late"))))),
        )
    }

    @Test
    fun aFirstReadThatNeverLandedCanBeRequestedAgainByHandAfterAReadOnlyDetour() {
        // Open → first page requested (token 1) → the user enters the reconciliation queue before
        // it lands → back. The surface has no success and no failure: it is the explicit
        // NOT_LOADED state and offers the manual 加载账户流水 action.
        val requesting = requestPage(enterAccountFlow())
        assertEquals(AccountFlowBodyFace.LOADING, accountFlowBodyFace(requesting))
        val queue = assertIs<P503AppState.ReconciliationQueue>(reducer.reduce(requesting, queueEvent()))
        val returned = assertIs<P503AppState.AccountFlow>(reducer.reduce(queue, P503UiEvent.Back))
        assertNull(returned.totalCount)
        assertNull(returned.failure)
        assertTrue(!returned.rowsLoading)
        assertEquals(AccountFlowBodyFace.NOT_LOADED, accountFlowBodyFace(returned))
        assertEquals(ACCOUNT_FLOW_LOAD_ACTION_TEXT, accountFlowLoadActionLabel(AccountFlowBodyFace.NOT_LOADED))
        // The late first landing (the old token) is discarded after the return.
        assertSame(
            returned,
            reducer.reduce(
                returned,
                landing(landedPage(1, listOf(posting(postingId = "posting-late")), nextCursor = null, totalCount = 1)),
            ),
        )
        // The manual action runs the SAME request handler: a NEW token, and its success lands.
        val manual = requestPage(returned)
        assertEquals(2L, manual.pageRequestToken)
        assertTrue(manual.rowsLoading)
        val landed =
            land(
                manual,
                landing(landedPage(2, listOf(posting(postingId = "posting-first")), nextCursor = null, totalCount = 1)),
            )
        assertEquals(listOf("posting-first"), landed.loadedRows.map { it.postingId.value })
        assertEquals(1, landed.totalCount)
        assertEquals(AccountFlowBodyFace.LIST, accountFlowBodyFace(landed))
    }

    @Test
    fun listScrollRestoredClearsThePositionExactlyOnce() {
        val loaded = loadedFlow()
        val withScroll = loaded.copy(listScroll = ListScrollPosition(1, 2))
        val consumed = assertIs<P503AppState.AccountFlow>(reducer.reduce(withScroll, P503UiEvent.ListScrollRestored))
        assertNull(consumed.listScroll)
        assertSame(consumed, reducer.reduce(consumed, P503UiEvent.ListScrollRestored))
    }

    @Test
    fun theOverviewScrollPositionSurvivesADetailDetour() {
        val detailResult = com.unifiedledger.application.TransactionDetailResult.NotFound
        val source = overview()
        val entered =
            assertIs<P503AppState.TransactionDetail>(
                reducer.reduce(
                    source,
                    P503UiEvent.SelectTransaction(TransactionId("tx-1"), detailResult, homeFlowScroll = ListScrollPosition(9, 3)),
                ),
            )
        assertEquals(ListScrollPosition(9, 3), entered.overview.originListScroll)
        val closed = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(entered, P503UiEvent.CloseTransactionDetail))
        assertEquals(ListScrollPosition(9, 3), closed.originListScroll)
    }

    // ---- absorption + locked ISE behavior ----

    @Test
    fun theAccountFlowFamilyIsAbsorbedInEveryOtherState() {
        everyNonOverviewState { state ->
            assertSame(state, reducer.reduce(state, openEvent()))
            assertSame(state, reducer.reduce(state, P503UiEvent.CloseAccountFlow))
            assertSame(state, reducer.reduce(state, P503UiEvent.RequestAccountFlowMorePage(++issuedTokens, generation, catalogVersion)))
            assertSame(state, reducer.reduce(state, landing(landedPage(1, listOf(posting())))))
        }
        // The guard absorbs re-entry dispatches on the flow surface itself too.
        val entered = enterAccountFlow()
        assertSame(entered, reducer.reduce(entered, openEvent()))
    }

    @Test
    fun preExistingUnlistedCombinationsOnAccountFlowRemainIllegalState() {
        val entered = enterAccountFlow()
        assertFailsWith<IllegalStateException> { reducer.reduce(entered, P503UiEvent.RetryRefresh) }
        assertFailsWith<IllegalStateException> { reducer.reduce(entered, P503UiEvent.StartNewExpense) }
        assertFailsWith<IllegalStateException> { reducer.reduce(entered, P503UiEvent.SelectTab(P503Tab.HOME)) }
        assertFailsWith<IllegalStateException> { reducer.reduce(entered, P503UiEvent.SelectMonth(kotlinx.datetime.YearMonth(2026, 3))) }
    }

    // ---- helpers ----

    /**
     * Runs the block against every state other than OverviewEmpty/AccountFlow — the full
     * `allStates()` enumeration (the F-Q4 fix round; an omitted state's absorption is asserted by
     * nobody).
     */
    private fun everyNonOverviewState(block: (P503AppState) -> Unit) {
        val draft = com.unifiedledger.application.ExpenseDraft(paymentAccountId, null, "35.80", occurredAt)
        val requestId = com.unifiedledger.application.RequestId("request-1")
        val transactionId = TransactionId("tx-1")
        val states =
            listOf<P503AppState>(
                P503AppState.Ready(),
                P503AppState.TransactionDetail(
                    overview = overview(),
                    originTab = P503Tab.HOME,
                    transactionId = transactionId,
                    detail = com.unifiedledger.application.TransactionDetailResult.NotFound,
                ),
                P503AppState.TransactionDetail(
                    overview = overview(),
                    originTab = P503Tab.HOME,
                    transactionId = transactionId,
                    detail = com.unifiedledger.application.TransactionDetailResult.NotFound,
                    annotationHistory = emptyList(),
                    versionHistory = com.unifiedledger.application.TransactionVersionHistoryResult.Unavailable,
                ),
                P503AppState.ImportCandidateDetail(
                    overview = overview(),
                    candidateId = com.unifiedledger.application.ImportCandidateId("candidate-1"),
                    detail = com.unifiedledger.application.ImportCandidateDetailResult.Absent,
                    duplicates = com.unifiedledger.application.ImportDuplicateReviewsResult.NoDuplicates,
                    form = ImportDecisionDraft(),
                ),
                P503AppState.ImportBatchConfirm(overview()),
                P503AppState.ImportBatchSubmitting(overview(), "2026-09-14T08:00:00Z", emptyList()),
                P503AppState.TransactionEdit(overview = overview(), origin = origin()),
                P503AppState.TransferEdit(overview = overview(), origin = transferOrigin()),
                P503AppState.VoidConfirm(overview = overview(), transactionId = transactionId),
                P503AppState.RecycleBin(
                    overview = overview(),
                    rows = RecycleBinResult.Success(emptyList()),
                ),
                P503AppState.TransactionAnnotationEdit(
                    overview = overview(),
                    intent =
                        TransactionAnnotationEditIntent(
                            transactionId = transactionId,
                            expectedAnnotationRevision = 0L,
                            expectedCurrentVersionId = TransactionVersionId("version-1"),
                        ),
                ),
                P503AppState.BackupExport(overview = overview()),
                P503AppState.BackupRestore(overview = overview()),
                P503AppState.RestoreSessionTerminal(BackupRestoreRecoveryCause.RollbackReopenFailed),
                P503AppState.BudgetConfig(
                    overview = overview(),
                    scope = com.unifiedledger.domain.BudgetScope.Total,
                    month = kotlinx.datetime.YearMonth(2026, 3),
                    revision = 0L,
                    closed = false,
                    limitMinorUnits = null,
                ),
                P503AppState.Search(overview()),
                P503AppState.Editing(draft, requestId),
                P503AppState.AwaitingConfirmation(draft, requestId),
                P503AppState.Submitting(draft, requestId),
                P503AppState.Created,
                P503AppState.NoChange,
                P503AppState.Recovered,
                P503AppState.RequestIdentityConflict(draft, requestId),
                P503AppState.DomainRejected(draft, requestId),
                P503AppState.InfrastructureFailure(InfrastructureFailureContext.SUBMISSION, draft, requestId),
                P503AppState.InfrastructureFailure(InfrastructureFailureContext.READ),
                P503AppState.UnknownCommit(draft, requestId),
            )
        states.forEach(block)
    }

    private fun origin(): TransactionEditOrigin =
        TransactionEditOrigin(
            transactionId = TransactionId("tx-1"),
            currentVersionId = TransactionVersionId("version-1"),
            note = "old note",
            statisticsAt = occurredAt,
            amountText = "100.00",
            categoryId = com.unifiedledger.domain.CategoryId("category-food"),
            fundingAccountId = paymentAccountId,
        )

    private fun transferOrigin(): TransferCorrectionOrigin =
        TransferCorrectionOrigin(
            transactionId = TransactionId("tx-1"),
            currentVersionId = TransactionVersionId("version-1"),
            note = null,
            statisticsAt = occurredAt,
            currency = cny,
            sourceAccountId = paymentAccountId,
            destinationAccountId = AccountId("asset-bank"),
            destinationCreditMinorUnits = 10_000L,
            feeMinorUnits = 0L,
            feeCategoryId = null,
        )
}
