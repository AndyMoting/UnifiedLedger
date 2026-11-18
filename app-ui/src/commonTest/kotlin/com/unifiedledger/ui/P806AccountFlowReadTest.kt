package com.unifiedledger.ui

import com.unifiedledger.application.AccountCurrencyBalance
import com.unifiedledger.application.CatalogAuthority
import com.unifiedledger.application.CatalogAuthorityReader
import com.unifiedledger.application.CatalogConsumerSession
import com.unifiedledger.application.ConfirmedExpenseReceipt
import com.unifiedledger.application.ConfirmedIncomeReceipt
import com.unifiedledger.application.ConfirmedTransferReceipt
import com.unifiedledger.application.CurrentVersionRow
import com.unifiedledger.application.LedgerCurrentStateReadPort
import com.unifiedledger.application.ManageableAccountView
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
import com.unifiedledger.domain.AccountKind
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P8-06 batch b (D-343 sections 3.2.2/3.7): the account-flow read glue over the REAL P806
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
        pageSetGeneration: Generation? = null,
        pageSetCatalogVersion: Long? = null,
    ): P806AccountRequestIdentity =
        P806AccountRequestIdentity(
            token = token,
            ledgerId = ledgerId,
            accountId = accountId,
            cursor = cursor,
            pageSetGeneration = pageSetGeneration,
            pageSetCatalogVersion = pageSetCatalogVersion,
        )

    private fun state(
        token: Long = 5L,
        loadedGeneration: Generation? = null,
        loadedCatalogVersion: Long? = null,
    ): P503AppState.AccountFlow {
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
            loadedGeneration = loadedGeneration,
            loadedCatalogVersion = loadedCatalogVersion,
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
    fun aContinuationReadsOnlyUnderThePageSetProvenance() {
        val setup = sessionSetup()
        val cursor = P806AccountCursor(occurredAtEpochNanos = 1_000L, postingId = "posting-1")
        val landed =
            assertIs<P806AccountPageRead.Landed>(
                readP806AccountPage(
                    Seam(generation = 3, facade = setup.facade),
                    identity(token = 6, cursor = cursor, pageSetGeneration = 3, pageSetCatalogVersion = 7L),
                ),
            )
        assertEquals(6L, landed.capture.request.token)
        assertEquals(cursor, landed.capture.request.cursor)
        assertEquals(cursor, setup.port.accountRequests.single().cursor)
    }

    @Test
    fun aContinuationUnderAnAdvancedCatalogNeverCallsThePort() {
        val setup = sessionSetup()
        val cursor = P806AccountCursor(occurredAtEpochNanos = 1_000L, postingId = "posting-1")
        // The page set was read at version 7; the session has already published version 8 by the
        // time the continuation runs under the lease.
        setup.reader.current = authority(8L)
        setup.session.refresh()
        val read =
            readP806AccountPage(
                Seam(generation = 3, facade = setup.facade),
                identity(token = 6, cursor = cursor, pageSetGeneration = 3, pageSetCatalogVersion = 7L),
            )
        val stale = assertIs<P806AccountPageRead.StaleProvenance>(read)
        assertEquals(P806SnapshotFailure.CatalogVersionMismatch, stale.failure)
        // The old cursor was never sent into the new catalog.
        assertTrue(setup.port.accountRequests.isEmpty())
    }

    @Test
    fun aContinuationUnderAnotherGenerationNeverCallsThePort() {
        val setup = sessionSetup()
        val cursor = P806AccountCursor(occurredAtEpochNanos = 1_000L, postingId = "posting-1")
        val read =
            readP806AccountPage(
                Seam(generation = 3, facade = setup.facade),
                identity(token = 6, cursor = cursor, pageSetGeneration = 2, pageSetCatalogVersion = 7L),
            )
        val stale = assertIs<P806AccountPageRead.StaleProvenance>(read)
        assertEquals(P806SnapshotFailure.Unavailable, stale.failure)
        assertTrue(setup.port.accountRequests.isEmpty())
    }

    @Test
    fun aRefusedLeaseAndAnUnwiredGraphStayDistinct() {
        val request = identity(token = 5)
        val refused = readP806AccountPage(Seam(generation = 3, facade = null), request)
        assertIs<P806AccountPageRead.Refused>(refused)
        assertEquals(request, refused.request)
        // A facade without a catalog session wires no P806 account query; the lease WAS acquired,
        // so the acquire generation travels with the unwired result.
        val unwired = readP806AccountPage(Seam(generation = 3, facade = minimalP503LedgerFacade()), request)
        assertIs<P806AccountPageRead.Unwired>(unwired)
        assertEquals(request, unwired.request)
        assertEquals(3, unwired.generation)
    }

    @Test
    fun theHeaderFallsBackToTheDeclaredCurrencyForANeverPostedAccount() {
        val usd = CurrencyUnit("USD", 2)
        val account =
            ManageableAccountView(
                accountId = accountId,
                name = "默认支付账户",
                kind = AccountKind.ASSET,
                currency = cny,
                active = true,
                balanceMinorUnits = null,
            )
        // No balance row: the asset row's own projection is the declared currency + 0 display
        // amount — the account is still reachable, and no balance is computed.
        val neverPosted = assertNotNull(p806AccountFlowHeader(accountId, mapOf(accountId to "工资卡"), listOf(account), emptyList()))
        assertEquals("工资卡 · 资产 · CNY", neverPosted.balanceLine)
        assertEquals(0L, neverPosted.displayMinorUnits)
        assertEquals(cny, neverPosted.currency)
        // A balance row wins, including its currency.
        val balance = AccountCurrencyBalance(accountId = accountId, currency = usd, ledgerSignedMinorUnits = 1_000L, displayMinorUnits = 1_000L)
        val posted = assertNotNull(p806AccountFlowHeader(accountId, emptyMap(), listOf(account), listOf(balance)))
        assertEquals("asset-payment · 资产 · USD", posted.balanceLine)
        assertEquals(1_000L, posted.displayMinorUnits)
        assertEquals(usd, posted.currency)
        // An account outside the manageable projection has no asset row, so no entry to open.
        assertNull(p806AccountFlowHeader(otherAccountId, emptyMap(), listOf(account), emptyList()))
    }

    @Test
    fun theHeaderMatchesTheAssetRowWhenOneAccountHasSeveralBalanceRows() {
        val usd = CurrencyUnit("USD", 2)
        val account =
            ManageableAccountView(
                accountId = accountId,
                name = "默认支付账户",
                kind = AccountKind.ASSET,
                currency = cny,
                active = true,
                balanceMinorUnits = null,
            )
        val balances =
            listOf(
                AccountCurrencyBalance(accountId = accountId, currency = cny, ledgerSignedMinorUnits = 100L, displayMinorUnits = 100L),
                AccountCurrencyBalance(accountId = accountId, currency = usd, ledgerSignedMinorUnits = 500L, displayMinorUnits = 500L),
            )
        // The asset list resolves the LAST balance row per account (associateBy); the flow header
        // must head the account exactly as its asset row does.
        val assetRow = assetAccountGroups(listOf(account), balances.associateBy { balance -> balance.accountId }).assetRows.single()
        val header = assertNotNull(p806AccountFlowHeader(accountId, emptyMap(), listOf(account), balances))
        assertEquals(assetRow.currency, header.currency)
        assertEquals(assetRow.minorUnits, header.displayMinorUnits)
        assertEquals("asset-payment · 资产 · USD", header.balanceLine)
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
    fun theDecisionGatesTheUnwiredGenerationButNotARefusedLease() {
        val firstPage = identity(token = 5)
        // Unwired carries the acquire generation: a graph swap between acquire and landing is a
        // stale generation, never the unwired state.
        val unwired = P806AccountPageRead.Unwired(firstPage, generation = 3)
        assertEquals(P806AccountLandingDecision.ACCEPTED, p806AccountLandingDecision(state(token = 5), unwired, 3, 7L))
        assertEquals(P806AccountLandingDecision.STALE_GENERATION, p806AccountLandingDecision(state(token = 5), unwired, 4, 7L))
        // A refused lease never ran, so it claims no generation and is not gated on one.
        val refused = P806AccountPageRead.Refused(firstPage)
        assertEquals(P806AccountLandingDecision.ACCEPTED, p806AccountLandingDecision(state(token = 5), refused, 4, null))
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
