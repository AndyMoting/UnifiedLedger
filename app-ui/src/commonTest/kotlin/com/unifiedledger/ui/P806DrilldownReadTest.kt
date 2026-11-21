package com.unifiedledger.ui

import com.unifiedledger.application.CatalogAuthority
import com.unifiedledger.application.CatalogAuthorityReader
import com.unifiedledger.application.CatalogConsumerSession
import com.unifiedledger.application.ConfirmedExpenseReceipt
import com.unifiedledger.application.ConfirmedIncomeReceipt
import com.unifiedledger.application.ConfirmedTransferReceipt
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.LedgerCurrentStateReadPort
import com.unifiedledger.application.LedgerEntryRow
import com.unifiedledger.application.ManualExpenseCommitRecord
import com.unifiedledger.application.ManualIncomeCommitRecord
import com.unifiedledger.application.ManualTransferCommitRecord
import com.unifiedledger.application.P806DrilldownRequest
import com.unifiedledger.application.P806DrilldownSnapshot
import com.unifiedledger.application.P806SnapshotFailure
import com.unifiedledger.application.P806SnapshotResult
import com.unifiedledger.application.P806TransactionCursor
import com.unifiedledger.application.P806TransactionPage
import com.unifiedledger.application.P806Window
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.RequestId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P8-06 batch 3 (D-362 1/2; spec sections 3.7/3.9/3.10.4): the report drilldown's read glue over the
 * REAL P806 drilldown query and the REAL facade/session wiring.
 *
 * The fake read port records the request the real query issued, and the tests supply the lease
 * generation — so the assertions prove the production glue captures the request identity, the
 * acquire-time generation, the query instance's construction catalog version and the as-of instant,
 * and that the nine-value landing decision compares those captures against the CURRENT values. A
 * continuation's page-set provenance gate is checked under the same read, so an old cursor is never
 * sent into another catalog/generation world. No Compose runtime, no timing.
 */
class P806DrilldownReadTest {
    private val ledgerId = LedgerId("ledger-drilldown-read")
    private val otherLedgerId = LedgerId("ledger-drilldown-other")
    private val categoryId = CategoryId("category-food")
    private val cny = CurrencyUnit("CNY", 2)
    private val readInstant = Instant.parse("2026-11-21T02:00:00Z")
    private val window = P806Window(startEpochNanos = 1_000L, endEpochNanos = 2_000L)
    private val catalog: LedgerCatalog = (LedgerCatalog.create(accounts = emptyList(), categories = emptyList()) as DomainResult.Success).value
    private val reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)

    private val periodTarget = ReportDrilldownTarget.Period(window)
    private val categoryTarget = ReportDrilldownTarget.Category(window = window, categoryId = categoryId, categoryName = "餐饮")

    private fun authority(version: Long): CatalogAuthority = CatalogAuthority(ledgerId, catalog, version)

    private class MutableAuthorityReader(
        var current: CatalogAuthority,
    ) : CatalogAuthorityReader {
        override fun load(ledgerId: LedgerId): CatalogAuthority? = current
    }

    private fun snapshot(
        window: P806Window = P806Window(startEpochNanos = 1_000L, endEpochNanos = 2_000L),
    ): P806DrilldownSnapshot =
        P806DrilldownSnapshot(
            catalogVersion = 7L,
            window = window,
            page = P806TransactionPage(rows = emptyList(), nextCursor = null),
            totalCount = 0,
        )

    /** Records the P806 drilldown requests the real query made. */
    private class RecordingReadPort(
        var nextSnapshot: P806DrilldownSnapshot,
    ) : LedgerCurrentStateReadPort {
        val drilldownRequests = mutableListOf<P806DrilldownRequest>()

        override fun readDrilldownSnapshot(request: P806DrilldownRequest): P806SnapshotResult<P806DrilldownSnapshot> {
            drilldownRequests += request
            return P806SnapshotResult.Success(nextSnapshot)
        }

        override fun loadCurrentRows(ledgerId: LedgerId): List<com.unifiedledger.application.CurrentVersionRow> = emptyList()

        override fun findManualExpenseByRequest(
            ledgerId: LedgerId,
            requestId: RequestId,
        ): ManualExpenseCommitRecord? = null

        override fun findManualExpenseByReceipt(
            ledgerId: LedgerId,
            receipt: ConfirmedExpenseReceipt,
        ): ManualExpenseCommitRecord? = null

        override fun findManualIncomeByRequest(
            ledgerId: LedgerId,
            requestId: RequestId,
        ): ManualIncomeCommitRecord? = null

        override fun findManualIncomeByReceipt(
            ledgerId: LedgerId,
            receipt: ConfirmedIncomeReceipt,
        ): ManualIncomeCommitRecord? = null

        override fun findManualTransferByRequest(
            ledgerId: LedgerId,
            requestId: RequestId,
        ): ManualTransferCommitRecord? = null

        override fun findManualTransferByReceipt(
            ledgerId: LedgerId,
            receipt: ConfirmedTransferReceipt,
        ): ManualTransferCommitRecord? = null
    }

    private class SessionSetup(
        val reader: MutableAuthorityReader,
        val session: CatalogConsumerSession,
        val port: RecordingReadPort,
        val facade: P503LedgerFacade,
    )

    private fun sessionSetup(authorityVersion: Long = 7L): SessionSetup {
        val reader = MutableAuthorityReader(authority(authorityVersion))
        val port = RecordingReadPort(snapshot())
        val session = CatalogConsumerSession(reader, ledgerId, reader.current, port)
        return SessionSetup(reader, session, port, minimalP503LedgerFacade(catalogSession = session))
    }

    private fun identity(
        token: Long,
        target: ReportDrilldownTarget = periodTarget,
        cursor: P806TransactionCursor? = null,
        pageSetGeneration: Generation? = null,
        pageSetCatalogVersion: Long? = null,
        ledger: LedgerId = ledgerId,
    ): P806DrilldownRequestIdentity =
        P806DrilldownRequestIdentity(
            token = token,
            ledgerId = ledger,
            target = target,
            cursor = cursor,
            pageSetGeneration = pageSetGeneration,
            pageSetCatalogVersion = pageSetCatalogVersion,
        )

    private fun overview(ledger: LedgerId = ledgerId): P503AppState.OverviewEmpty =
        P503AppState.OverviewEmpty(
            state = LedgerCurrentState(ledgerId = ledger, transactions = emptyList(), balances = emptyList()),
            selectedTab = P503Tab.ANALYSIS,
        )

    private fun state(
        token: Long = 5L,
        target: ReportDrilldownTarget = periodTarget,
        nextCursor: P806TransactionCursor? = null,
        ledger: LedgerId = ledgerId,
    ): P503AppState.ReportDrilldown =
        P503AppState.ReportDrilldown(
            overview = overview(ledger),
            target = target,
            nextCursor = nextCursor,
            rowsLoading = true,
            pageRequestToken = token,
        )

    private fun capture(
        generation: Generation = 3,
        catalogVersion: Long = 7L,
        asOf: Instant? = readInstant,
    ): P806DrilldownReadCapture = P806DrilldownReadCapture(generation = generation, catalogVersion = catalogVersion, asOf = asOf)

    private fun row(id: String): LedgerEntryRow =
        LedgerEntryRow(
            transactionId = TransactionId(id),
            currentVersionId = TransactionVersionId("version-$id"),
            kind = TransactionKind.EXPENSE,
            occurredAt = readInstant,
            statisticsAt = readInstant,
            note = null,
            postings = emptyList(),
        )

    private fun landedRead(
        token: Long = 5L,
        target: ReportDrilldownTarget = periodTarget,
        cursor: P806TransactionCursor? = null,
        generation: Generation = 3,
        catalogVersion: Long = 7L,
        asOf: Instant? = readInstant,
        viewLedger: LedgerId = ledgerId,
        viewWindow: P806Window = window,
        viewTotalCount: Int = 0,
    ): P806DrilldownPageRead.Landed {
        val request = identity(token = token, target = target, cursor = cursor)
        return P806DrilldownPageRead.Landed(
            request = request,
            capture = capture(generation = generation, catalogVersion = catalogVersion, asOf = asOf),
            result =
                P806SnapshotResult.Success(
                    com.unifiedledger.application.P806DrilldownView(
                        ledgerId = viewLedger,
                        window = viewWindow,
                        page = P806TransactionPage(rows = emptyList(), nextCursor = null),
                        totalCount = viewTotalCount,
                    ),
                ),
        )
    }

    // ---------------------------------------------------------------- the read capture

    @Test
    fun theHostSequenceNeverRepeatsAToken() {
        val sequence = P806DrilldownRequestSequence()
        assertEquals(1L, sequence.next())
        assertEquals(2L, sequence.next())
        assertEquals(3L, sequence.next())
    }

    @Test
    fun theReadCapturesTheRequestTheLeaseGenerationTheQueryCatalogVersionAndTheAsOfInstant() {
        val setup = sessionSetup()
        val read = readP806DrilldownPage(setup.facade, generation = 3, request = identity(token = 5))
        val landed = assertIs<P806DrilldownPageRead.Landed>(read)
        assertEquals(identity(token = 5), landed.request)
        assertEquals(3, landed.capture.generation)
        // The version comes from the query INSTANCE the session built, not from a current read.
        assertEquals(7L, landed.capture.catalogVersion)
        assertNotNull(landed.capture.asOf)
        assertIs<P806SnapshotResult.Success<com.unifiedledger.application.P806DrilldownView>>(landed.result)
        // One page read asking for exactly this ledger/window under the query's construction version.
        assertEquals(1, setup.port.drilldownRequests.size)
        val asked = setup.port.drilldownRequests.single()
        assertEquals(ledgerId, asked.ledgerId)
        assertEquals(window, asked.window)
        assertNull(asked.categoryId)
        assertNull(asked.cursor)
        assertEquals(7L, asked.expectedCatalogVersion)
        assertEquals(com.unifiedledger.application.P806_DETAIL_PAGE_SIZE, asked.pageSize)
    }

    @Test
    fun aCategoryTargetAddsItsCategoryPredicateToTheSameRead() {
        val setup = sessionSetup()
        val read = readP806DrilldownPage(setup.facade, generation = 3, request = identity(token = 5, target = categoryTarget))
        assertIs<P806DrilldownPageRead.Landed>(read)
        val asked = setup.port.drilldownRequests.single()
        assertEquals(categoryId, asked.categoryId)
        assertEquals(window, asked.window)
    }

    @Test
    fun anUnwiredGraphIsTheExplicitUnwiredRead() {
        val read = readP806DrilldownPage(minimalP503LedgerFacade(), generation = 3, request = identity(token = 5))
        assertIs<P806DrilldownPageRead.Unwired>(read)
    }

    // ---------------------------------------------------------------- the page-set provenance gate

    @Test
    fun aContinuationReadsOnlyUnderThePageSetProvenance() {
        val setup = sessionSetup()
        val cursor = P806TransactionCursor(statisticsAtEpochNanos = 1_000L, transactionId = "tx-2")
        val landed =
            assertIs<P806DrilldownPageRead.Landed>(
                readP806DrilldownPage(
                    setup.facade,
                    generation = 3,
                    request = identity(token = 6, cursor = cursor, pageSetGeneration = 3, pageSetCatalogVersion = 7L),
                ),
            )
        assertEquals(cursor, landed.request.cursor)
        assertEquals(
            cursor,
            setup.port.drilldownRequests
                .single()
                .cursor,
        )
    }

    @Test
    fun aContinuationUnderAnAdvancedCatalogNeverCallsThePort() {
        val setup = sessionSetup()
        val cursor = P806TransactionCursor(statisticsAtEpochNanos = 1_000L, transactionId = "tx-2")
        // The page set was read at version 7; the session has published version 8 before the
        // continuation runs under the lease.
        setup.reader.current = authority(8L)
        setup.session.refresh()
        val read =
            readP806DrilldownPage(
                setup.facade,
                generation = 3,
                request = identity(token = 6, cursor = cursor, pageSetGeneration = 3, pageSetCatalogVersion = 7L),
            )
        assertIs<P806DrilldownPageRead.StaleProvenance>(read)
        assertTrue(setup.port.drilldownRequests.isEmpty())
    }

    @Test
    fun aContinuationUnderAnotherGenerationNeverCallsThePort() {
        val setup = sessionSetup()
        val cursor = P806TransactionCursor(statisticsAtEpochNanos = 1_000L, transactionId = "tx-2")
        val read =
            readP806DrilldownPage(
                setup.facade,
                generation = 4,
                request = identity(token = 6, cursor = cursor, pageSetGeneration = 3, pageSetCatalogVersion = 7L),
            )
        assertIs<P806DrilldownPageRead.StaleProvenance>(read)
        assertTrue(setup.port.drilldownRequests.isEmpty())
    }

    // ---------------------------------------------------------------- the nine-value landing decision

    @Test
    fun theDecisionAcceptsTheMatchingCaptureAndRejectsTheStaleIdentityAxes() {
        val read = landedRead(token = 5)
        assertEquals(P806DrilldownLandingDecision.ACCEPTED, p806DrilldownLandingDecision(state(token = 5), read, 3, 7L))
        // A superseded token, a different target, a cursor no longer the continuation point and
        // another ledger each change nothing.
        assertEquals(P806DrilldownLandingDecision.STALE_TOKEN, p806DrilldownLandingDecision(state(token = 6), read, 3, 7L))
        assertEquals(
            P806DrilldownLandingDecision.STALE_TARGET,
            p806DrilldownLandingDecision(state(token = 5, target = categoryTarget), read, 3, 7L),
        )
        assertEquals(
            P806DrilldownLandingDecision.STALE_CURSOR,
            p806DrilldownLandingDecision(
                state(token = 5, nextCursor = P806TransactionCursor(statisticsAtEpochNanos = 9L, transactionId = "tx-9")),
                read,
                3,
                7L,
            ),
        )
        assertEquals(
            P806DrilldownLandingDecision.STALE_LEDGER,
            p806DrilldownLandingDecision(state(token = 5, ledger = otherLedgerId), read, 3, 7L),
        )
    }

    @Test
    fun theDecisionSeparatesAGenerationAdvanceACatalogAdvanceAndARejectedPayload() {
        val read = landedRead(token = 5)
        assertEquals(P806DrilldownLandingDecision.STALE_GENERATION, p806DrilldownLandingDecision(state(token = 5), read, 4, 7L))
        assertEquals(P806DrilldownLandingDecision.STALE_GENERATION, p806DrilldownLandingDecision(state(token = 5), read, null, 7L))
        assertEquals(P806DrilldownLandingDecision.CATALOG_ADVANCED, p806DrilldownLandingDecision(state(token = 5), read, 3, 8L))
        assertEquals(P806DrilldownLandingDecision.CATALOG_ADVANCED, p806DrilldownLandingDecision(state(token = 5), read, 3, null))
        // A success payload naming another ledger or another window is dropped whole.
        assertEquals(
            P806DrilldownLandingDecision.PAYLOAD_REJECTED,
            p806DrilldownLandingDecision(state(token = 5), landedRead(token = 5, viewLedger = otherLedgerId), 3, 7L),
        )
        assertEquals(
            P806DrilldownLandingDecision.PAYLOAD_REJECTED,
            p806DrilldownLandingDecision(
                state(token = 5),
                landedRead(token = 5, viewWindow = P806Window(startEpochNanos = 5_000L, endEpochNanos = 6_000L)),
                3,
                7L,
            ),
        )
        // A typed failed read is a landed conclusion of the matching request, not a payload reject.
        val failed =
            P806DrilldownPageRead.Landed(
                request = identity(token = 5),
                capture = capture(),
                result = P806SnapshotResult.Failed(P806SnapshotFailure.Unavailable),
            )
        assertEquals(P806DrilldownLandingDecision.ACCEPTED, p806DrilldownLandingDecision(state(token = 5), failed, 3, 7L))
    }

    @Test
    fun aSuccessClaimingZeroTotalWhileCarryingRowsIsRejectedAsAPayloadMismatch() {
        // The frozen §2.5 invariant: the reducer must never produce `totalCount == 0` with
        // non-empty rows. A snapshot that contradicts itself is not splittable — the whole payload
        // is dropped as PAYLOAD_REJECTED, and the reducer lands the typed failure face instead.
        val malformed =
            P806DrilldownPageRead.Landed(
                request = identity(token = 5),
                capture = capture(),
                result =
                    P806SnapshotResult.Success(
                        com.unifiedledger.application.P806DrilldownView(
                            ledgerId = ledgerId,
                            window = window,
                            page = P806TransactionPage(rows = listOf(row("tx-1")), nextCursor = null),
                            totalCount = 0,
                        ),
                    ),
            )
        assertEquals(
            P806DrilldownLandingDecision.PAYLOAD_REJECTED,
            p806DrilldownLandingDecision(state(token = 5), malformed, 3, 7L),
        )
        // The same zero count with an EMPTY page is the honest empty success.
        val emptySuccess =
            P806DrilldownPageRead.Landed(
                request = identity(token = 5),
                capture = capture(),
                result =
                    P806SnapshotResult.Success(
                        com.unifiedledger.application.P806DrilldownView(
                            ledgerId = ledgerId,
                            window = window,
                            page = P806TransactionPage(rows = emptyList(), nextCursor = null),
                            totalCount = 0,
                        ),
                    ),
            )
        assertEquals(P806DrilldownLandingDecision.ACCEPTED, p806DrilldownLandingDecision(state(token = 5), emptySuccess, 3, 7L))
    }

    @Test
    fun aStaleProvenanceReadIsItsOwnVerdictAndRefusedOrUnwiredPassTheGate() {
        assertEquals(
            P806DrilldownLandingDecision.STALE_PROVENANCE,
            p806DrilldownLandingDecision(state(token = 5), P806DrilldownPageRead.StaleProvenance(identity(token = 5)), 3, 7L),
        )
        // Refused/Unwired never ran a read, so the gate is what decides them: a matching request is
        // ACCEPTED (the reducer lands the face), a superseded one is STALE_TOKEN.
        assertEquals(
            P806DrilldownLandingDecision.ACCEPTED,
            p806DrilldownLandingDecision(state(token = 5), P806DrilldownPageRead.Refused(identity(token = 5)), 4, null),
        )
        assertEquals(
            P806DrilldownLandingDecision.ACCEPTED,
            p806DrilldownLandingDecision(state(token = 5), P806DrilldownPageRead.Unwired(identity(token = 5)), 3, 7L),
        )
        assertEquals(
            P806DrilldownLandingDecision.STALE_TOKEN,
            p806DrilldownLandingDecision(state(token = 6), P806DrilldownPageRead.Refused(identity(token = 5)), 3, 7L),
        )
        assertEquals(
            P806DrilldownLandingDecision.STALE_TARGET,
            p806DrilldownLandingDecision(
                state(token = 5, target = categoryTarget),
                P806DrilldownPageRead.Unwired(identity(token = 5)),
                3,
                7L,
            ),
        )
        assertEquals(
            P806DrilldownLandingDecision.STALE_TOKEN,
            p806DrilldownLandingDecision(state(token = 6), P806DrilldownPageRead.StaleProvenance(identity(token = 5)), 3, 7L),
        )
    }

    @Test
    fun theReducerAcceptsTheRealCaptureAndADeadGraphEndsOnlyThatRequest() {
        val setup = sessionSetup()
        val read = assertIs<P806DrilldownPageRead.Landed>(readP806DrilldownPage(setup.facade, generation = 3, request = identity(token = 5)))
        val landed =
            assertIs<P503AppState.ReportDrilldown>(
                reducer.reduce(
                    state(token = 5),
                    P503UiEvent.ReportDrilldownPageLanded(read = read, currentGeneration = 3, currentCatalogVersion = 7L),
                ),
            )
        assertEquals(0, landed.loadedRows.size)
        assertEquals(0, landed.totalCount)
        assertTrue(!landed.rowsLoading)
        // The read instant is captured from the composition's clock; its absence is the only
        // permitted null (a clock-less graph still reads, the header then carries no as-of).
        assertNotNull(landed.landedAsOf)
        // A controlled re-entry to a NEW generation: the same real read is rejected, and the
        // matching request's loading ends with the retry reset to the first page.
        val reentered =
            assertIs<P503AppState.ReportDrilldown>(
                reducer.reduce(
                    state(token = 5),
                    P503UiEvent.ReportDrilldownPageLanded(read = read, currentGeneration = 4, currentCatalogVersion = 7L),
                ),
            )
        assertTrue(!reentered.rowsLoading)
        assertNull(reentered.nextCursor)
        assertEquals(P806SnapshotFailure.Unavailable, reentered.failure)
    }
}
