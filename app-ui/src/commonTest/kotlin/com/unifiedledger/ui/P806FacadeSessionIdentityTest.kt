package com.unifiedledger.ui

import com.unifiedledger.application.CatalogAuthority
import com.unifiedledger.application.CatalogAuthorityReader
import com.unifiedledger.application.CatalogConsumerSession
import com.unifiedledger.application.ConfirmedExpenseReceipt
import com.unifiedledger.application.ConfirmedIncomeReceipt
import com.unifiedledger.application.ConfirmedTransferReceipt
import com.unifiedledger.application.CurrentVersionRow
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.LedgerCurrentStateReadPort
import com.unifiedledger.application.ManualExpenseCommitRecord
import com.unifiedledger.application.ManualIncomeCommitRecord
import com.unifiedledger.application.ManualTransferCommitRecord
import com.unifiedledger.application.RequestId
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Instant

/**
 * P8-06 foundation (D-343 3.5.4, Q2 ruling): the facade exposes the injected session's four P806
 * queries by reference plus a comparison-only catalog version. With no session the whole surface is
 * Unwired (null, no fallback read); without a reporting clock only the relative-period surfaces
 * are absent. A session refresh publishes new instances while previously obtained references keep
 * their own construction-time version.
 */
class P806FacadeSessionIdentityTest {
    private val ledgerId = LedgerId("ledger-local-test")
    private val clock = LedgerClock { Instant.parse("2026-11-17T00:00:00Z") }
    private val catalog: LedgerCatalog =
        (LedgerCatalog.create(accounts = emptyList(), categories = emptyList()) as DomainResult.Success).value

    private fun authority(version: Long): CatalogAuthority = CatalogAuthority(ledgerId, catalog, version)

    @Test
    fun theFacadeGettersAreTheSessionQueriesAndFollowRefresh() {
        val reader = MutableAuthorityReader(authority(7L))
        val session = CatalogConsumerSession(reader, ledgerId, reader.current, EmptyReadPort(), clock = clock)
        val facade = minimalP503LedgerFacade(catalogSession = session)

        assertSame(session.p806ReportQuery, facade.p806ReportQuery)
        assertSame(session.p806CalendarQuery, facade.p806CalendarQuery)
        assertSame(session.p806DrilldownQuery, facade.p806DrilldownQuery)
        assertSame(session.p806AccountPostingsQuery, facade.p806AccountPostingsQuery)
        assertEquals(7L, facade.currentCatalogVersion)

        val oldReport = facade.p806ReportQuery
        val oldCalendar = facade.p806CalendarQuery
        val oldDrilldown = facade.p806DrilldownQuery
        val oldAccount = facade.p806AccountPostingsQuery
        reader.current = authority(8L)
        session.refresh()

        assertSame(session.p806ReportQuery, facade.p806ReportQuery)
        assertSame(session.p806CalendarQuery, facade.p806CalendarQuery)
        assertSame(session.p806DrilldownQuery, facade.p806DrilldownQuery)
        assertSame(session.p806AccountPostingsQuery, facade.p806AccountPostingsQuery)
        assertEquals(8L, facade.currentCatalogVersion)
        assertNotSame(oldReport, facade.p806ReportQuery)
        assertNotSame(oldCalendar, facade.p806CalendarQuery)
        assertNotSame(oldDrilldown, facade.p806DrilldownQuery)
        assertNotSame(oldAccount, facade.p806AccountPostingsQuery)
        assertEquals(7L, oldReport?.catalogVersion)
        assertEquals(7L, oldCalendar?.catalogVersion)
        assertEquals(7L, oldDrilldown?.catalogVersion)
        assertEquals(7L, oldAccount?.catalogVersion)
    }

    @Test
    fun aFacadeWithoutASessionExposesNoP806Surface() {
        val facade = minimalP503LedgerFacade()

        assertNull(facade.p806ReportQuery)
        assertNull(facade.p806CalendarQuery)
        assertNull(facade.p806DrilldownQuery)
        assertNull(facade.p806AccountPostingsQuery)
        assertNull(facade.currentCatalogVersion)
    }

    @Test
    fun aSessionWithoutAClockStillServesDrilldownAndAccountButNotReportOrCalendar() {
        val reader = MutableAuthorityReader(authority(7L))
        val session = CatalogConsumerSession(reader, ledgerId, reader.current, EmptyReadPort())
        val facade = minimalP503LedgerFacade(catalogSession = session)

        assertNull(facade.p806ReportQuery)
        assertNull(facade.p806CalendarQuery)
        assertSame(session.p806DrilldownQuery, facade.p806DrilldownQuery)
        assertSame(session.p806AccountPostingsQuery, facade.p806AccountPostingsQuery)
        assertEquals(7L, facade.currentCatalogVersion)
    }

    // ---------------------------------------------------------------- fixtures

    private class MutableAuthorityReader(
        var current: CatalogAuthority,
    ) : CatalogAuthorityReader {
        override fun load(ledgerId: LedgerId): CatalogAuthority? = current
    }

    /** The seven existing abstract members; no typed P806 read is reached by these assertions. */
    private class EmptyReadPort : LedgerCurrentStateReadPort {
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
}
