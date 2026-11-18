package com.unifiedledger.ui

import com.unifiedledger.application.CatalogAuthority
import com.unifiedledger.application.CatalogAuthorityReader
import com.unifiedledger.application.CatalogConsumerSession
import com.unifiedledger.application.ConfirmedExpenseReceipt
import com.unifiedledger.application.ConfirmedIncomeReceipt
import com.unifiedledger.application.ConfirmedTransferReceipt
import com.unifiedledger.application.CurrentVersionRow
import com.unifiedledger.application.LedgerCurrentStateReadPort
import com.unifiedledger.application.ManualExpenseCommitRecord
import com.unifiedledger.application.ManualIncomeCommitRecord
import com.unifiedledger.application.ManualTransferCommitRecord
import com.unifiedledger.application.P806AccountCursor
import com.unifiedledger.application.P806AccountPostingRow
import com.unifiedledger.application.P806AccountPostingsPage
import com.unifiedledger.application.P806AccountRequest
import com.unifiedledger.application.P806AccountSnapshot
import com.unifiedledger.application.P806AccountView
import com.unifiedledger.application.P806SnapshotFailure
import com.unifiedledger.application.P806SnapshotResult
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.RequestId
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P8-06 batch b (taskbook items 2/3; matrix A/B): the account-flow read glue over the REAL P806
 * account query and the REAL facade/session wiring. The fake read port records what the query
 * asked for, and the fake lease seam supplies the generation — so the assertions prove the
 * production glue captures the request identity, the acquire-time generation and the query
 * instance's construction catalog version, and that the production landing decision compares those
 * captures against the CURRENT values (a graph swap and a catalog advance each reject the old
 * read; a newer request keeps its own state). No Compose runtime, no timing.
 */
class P806AccountFlowReadTest {
    private val ledgerId = LedgerId("ledger-local-test")
    private val accountId = AccountId("asset-payment")
    private val otherAccountId = AccountId("asset-other")
    private val cny = CurrencyUnit("CNY", 2)
    private val occurredAt = Instant.parse("2026-03-15T02:00:00Z")
    private val catalog: LedgerCatalog = (LedgerCatalog.create(accounts = emptyList(), categories = emptyList()) as DomainResult.Success).value
    private val reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)

    private fun authority(version: Long): CatalogAuthority = CatalogAuthority(ledgerId, catalog, version)

    private class MutableAuthorityReader(
        var current: CatalogAuthority,
    ) : CatalogAuthorityReader {
        override fun load(ledgerId: LedgerId): CatalogAuthority? = current
    }

    private fun posting(): P806AccountPostingRow =
        P806AccountPostingRow(
            postingId = PostingId("posting-1"),
            transactionId = TransactionId("tx-1"),
            occurredAt = occurredAt,
            note = "工作午餐",
            kind = TransactionKind.EXPENSE,
            amount = Money.ofMinor(-3_580L, cny),
        )

    private fun accountSnapshot(cursor: P806AccountCursor? = null): P806AccountSnapshot =
        P806AccountSnapshot(
            catalogVersion = 7L,
            page = P806AccountPostingsPage(rows = listOf(posting()), nextCursor = cursor),
            totalCount = 1,
        )

    /** Records the P806 account requests the real query made. */
    private class RecordingReadPort(
        var nextSnapshot: P806AccountSnapshot,
    ) : LedgerCurrentStateReadPort {
        val accountRequests = mutableListOf<P806AccountRequest>()

        override fun readAccountPostingsSnapshot(request: P806AccountRequest): P806SnapshotResult<P806AccountSnapshot> {
            accountRequests += request
            return P806SnapshotResult.Success(nextSnapshot)
        }

        override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> = emptyList()

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

    /** The lease seam stand-in: the fake supplies the generation the owner would capture. */
    private class Seam(
        var generation: Generation,
        var facade: P503LedgerFacade?,
    ) : P806AccountLeaseSeam {
        override fun <T> leasedAccountRead(block: (P503LedgerFacade, Generation) -> T): LeaseOutcome<T> {
            val current = facade ?: return LeaseOutcome.NotReady
            return LeaseOutcome.Completed(generation, block(current, generation))
        }
    }

    private class SessionSetup(
        val reader: MutableAuthorityReader,
        val session: CatalogConsumerSession,
        val port: RecordingReadPort,
        val facade: P503LedgerFacade,
    )

    private fun sessionSetup(
        authorityVersion: Long = 7L,
        snapshot: P806AccountSnapshot = accountSnapshot(),
    ): SessionSetup {
        val reader = MutableAuthorityReader(authority(authorityVersion))
        val port = RecordingReadPort(snapshot)
        val session = CatalogConsumerSession(reader, ledgerId, reader.current, port)
        return SessionSetup(reader, session, port, minimalP503LedgerFacade(catalogSession = session))
    }

    private fun identity(
        token: Long,
        cursor: P806AccountCursor? = null,
    ): P806AccountRequestIdentity =
        P806AccountRequestIdentity(
            token = token,
            ledgerId = ledgerId,
            accountId = accountId,
            cursor = cursor,
        )

    private fun state(token: Long = 5L): P503AppState.AccountFlow {
        val overview =
            P503AppState.OverviewEmpty(
                state =
                    com.unifiedledger.application.LedgerCurrentState(
                        ledgerId = ledgerId,
                        transactions = emptyList(),
                        balances = emptyList(),
                    ),
                selectedTab = P503Tab.ACCOUNTS,
            )
        return P503AppState.AccountFlow(
            overview = overview,
            accountId = accountId,
            balanceLine = "默认支付账户 · 资产 · CNY",
            balanceMinorUnits = -3_580L,
            balanceCurrency = cny,
            rowsLoading = true,
            pageRequestToken = token,
        )
    }

    @Test
    fun theHostSequenceNeverRepeatsAToken() {
        // The sequence lives with the composition and outlives the surface state, so a close +
        // reopen cannot reuse a token a previous open's read still carries.
        val sequence = P806AccountRequestSequence()
        assertEquals(1L, sequence.next())
        assertEquals(2L, sequence.next())
        assertEquals(3L, sequence.next())
    }

    @Test
    fun theReadCapturesTheRequestTheLeaseGenerationAndTheQueryCatalogVersion() {
        val setup = sessionSetup()
        val read = readP806AccountPage(Seam(generation = 3, facade = setup.facade), identity(token = 5))
        val landed = assertIs<P806AccountPageRead.Landed>(read)
        assertEquals(identity(token = 5), landed.capture.request)
        assertEquals(3, landed.capture.generation)
        // The version comes from the query INSTANCE the session built, not from a current read.
        assertEquals(7L, landed.capture.catalogVersion)
        assertIs<P806SnapshotResult.Success<P806AccountView>>(landed.result)
        // One snapshot read for the page + count, asking for exactly this ledger/account/cursor
        // under the query's construction version.
        assertEquals(1, setup.port.accountRequests.size)
        val asked = setup.port.accountRequests.single()
        assertEquals(ledgerId, asked.ledgerId)
        assertEquals(accountId, asked.accountId)
        assertNull(asked.cursor)
        assertEquals(7L, asked.expectedCatalogVersion)
    }

    @Test
    fun aContinuationReadPassesTheCursorThrough() {
        val setup = sessionSetup()
        val cursor = P806AccountCursor(occurredAtEpochNanos = 1_000L, postingId = "posting-1")
        val landed = assertIs<P806AccountPageRead.Landed>(readP806AccountPage(Seam(generation = 3, facade = setup.facade), identity(token = 6, cursor = cursor)))
        assertEquals(6L, landed.capture.request.token)
        assertEquals(cursor, landed.capture.request.cursor)
        assertEquals(cursor, setup.port.accountRequests.single().cursor)
    }

    @Test
    fun aRefusedLeaseAndAnUnwiredGraphStayDistinct() {
        val request = identity(token = 5)
        val refused = readP806AccountPage(Seam(generation = 3, facade = null), request)
        assertIs<P806AccountPageRead.Refused>(refused)
        assertEquals(request, refused.request)
        // A facade without a catalog session wires no P806 account query.
        val unwired = readP806AccountPage(Seam(generation = 3, facade = minimalP503LedgerFacade()), request)
        assertIs<P806AccountPageRead.Unwired>(unwired)
        assertEquals(request, unwired.request)
    }

    @Test
    fun theLandingDecisionAcceptsTheMatchingCaptureAndRejectsACatalogAdvance() {
        val setup = sessionSetup()
        val read = assertIs<P806AccountPageRead.Landed>(readP806AccountPage(Seam(generation = 3, facade = setup.facade), identity(token = 5)))
        val flow = state(token = 5)
        assertEquals(P806AccountLandingDecision.ACCEPTED, p806AccountLandingDecision(flow, read, 3, 7L))
        // The catalog advances while the read is in flight: the session publishes a NEW query,
        // the old capture keeps its own construction-time version and the decision compares
        // against the CURRENT version (never re-stamping the capture from it).
        setup.reader.current = authority(8L)
        setup.session.refresh()
        assertEquals(7L, read.capture.catalogVersion)
        assertEquals(8L, setup.facade.currentCatalogVersion)
        assertEquals(
            P806AccountLandingDecision.CATALOG_ADVANCED,
            p806AccountLandingDecision(flow, read, 3, setup.facade.currentCatalogVersion),
        )
        // An absent current version is a fail-closed mismatch, never a pass.
        assertEquals(P806AccountLandingDecision.CATALOG_ADVANCED, p806AccountLandingDecision(flow, read, 3, null))
    }

    @Test
    fun theLandingDecisionRejectsAGraphSwapANewerRequestAndAnotherAccount() {
        val setup = sessionSetup()
        val read = assertIs<P806AccountPageRead.Landed>(readP806AccountPage(Seam(generation = 3, facade = setup.facade), identity(token = 5)))
        val flow = state(token = 5)
        // The owner reopened the ledger: the capture's generation is no longer active.
        assertEquals(P806AccountLandingDecision.STALE_GENERATION, p806AccountLandingDecision(flow, read, 4, 7L))
        assertEquals(P806AccountLandingDecision.STALE_GENERATION, p806AccountLandingDecision(flow, read, null, 7L))
        // A newer request (token 6) owns the surface: the old read changes nothing.
        assertEquals(P806AccountLandingDecision.STALE_REQUEST, p806AccountLandingDecision(state(token = 6), read, 3, 7L))
        // A surface on another account never accepts this read.
        assertEquals(P806AccountLandingDecision.STALE_REQUEST, p806AccountLandingDecision(flow.copy(accountId = otherAccountId), read, 3, 7L))
    }

    @Test
    fun theReducerAcceptsTheRealCaptureAndADeadGraphEndsOnlyThatRequest() {
        val setup = sessionSetup()
        val read = assertIs<P806AccountPageRead.Landed>(readP806AccountPage(Seam(generation = 3, facade = setup.facade), identity(token = 5)))
        val landed =
            assertIs<P503AppState.AccountFlow>(
                reducer.reduce(
                    state(token = 5),
                    P503UiEvent.AccountFlowPageLanded(read = read, currentGeneration = 3, currentCatalogVersion = 7L),
                ),
            )
        assertEquals(1, landed.loadedRows.size)
        assertEquals(1, landed.totalCount)
        assertTrue(!landed.rowsLoading)
        // A controlled re-entry to a NEW generation: the same real read is rejected, and the
        // matching request's loading ends with the retry reset to the first page.
        val reentered =
            assertIs<P503AppState.AccountFlow>(
                reducer.reduce(
                    state(token = 5),
                    P503UiEvent.AccountFlowPageLanded(read = read, currentGeneration = 4, currentCatalogVersion = 7L),
                ),
            )
        assertEquals(0, reentered.loadedRows.size)
        assertTrue(!reentered.rowsLoading)
        assertNull(reentered.nextCursor)
        assertEquals(P806SnapshotFailure.Unavailable, reentered.failure)
    }
}
