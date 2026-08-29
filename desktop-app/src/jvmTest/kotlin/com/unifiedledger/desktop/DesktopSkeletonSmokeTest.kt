package com.unifiedledger.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ConfirmedManualExpenseResult
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.ExplicitlyConfirmedManualExpense
import com.unifiedledger.application.RequestId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.Money
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

private val UUID_V7 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

class DesktopSkeletonSmokeTest {
    @Test
    fun emptyBootstrapLedgerAcceptsOneManualExpenseAndReplaysWithoutChange() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val graph = buildLedgerGraph(driver, createSchema = true)
        val queries = graph.database.ledgerQueries

        // IMP-12: the empty-bootstrap local test ledger is open with zero persisted state.
        assertEquals(0L, queries.countTransactions().executeAsOne())
        assertEquals(0L, queries.countVersions().executeAsOne())
        assertEquals(0L, queries.countPostingSets().executeAsOne())
        assertEquals(0L, queries.countPostings().executeAsOne())
        assertEquals(0L, queries.countRequests().executeAsOne())
        assertEquals(0L, queries.countReceipts().executeAsOne())

        val request =
            ExplicitlyConfirmedManualExpense(
                ledgerId = graph.ledgerId,
                requestId = RequestId("request-local-test-1"),
                amount = Money.ofMinor(3_580, CurrencyUnit("CNY", 2)),
                categoryId = graph.categoryId,
                paymentAccountId = graph.paymentAccountId,
                occurredAt = Instant.parse("2026-01-15T00:30:00Z"),
                note = "",
                confirmation = ExplicitManualSave,
            )

        val created = assertIs<ConfirmedManualExpenseResult.Created>(graph.useCase.execute(request))
        assertTrue(UUID_V7.matches(created.receipt.confirmationId.value))
        assertTrue(UUID_V7.matches(created.receipt.transactionId.value))

        // Exact counts: one transaction/version/posting set, one request, one receipt, two postings.
        assertEquals(1L, queries.countTransactions().executeAsOne())
        assertEquals(1L, queries.countVersions().executeAsOne())
        assertEquals(1L, queries.countPostingSets().executeAsOne())
        assertEquals(2L, queries.countPostings().executeAsOne())
        assertEquals(1L, queries.countRequests().executeAsOne())
        assertEquals(1L, queries.countReceipts().executeAsOne())

        // Per-currency balance: each currency_code group's amount.minorUnits sums to zero.
        val postings = queries.selectPersistedPostings().executeAsList()
        assertEquals(2, postings.size)
        val minorUnitsByCurrency =
            postings
                .groupBy { it.currency_code }
                .mapValues { (_, rows) -> rows.sumOf { it.amount_minor } }
        assertEquals(mapOf("CNY" to 0L), minorUnitsByCurrency)

        // Exact replay returns NoChange with the unchanged receipt and zero repeated writes.
        val replayed = assertIs<ConfirmedManualExpenseResult.NoChange>(graph.useCase.execute(request))
        assertEquals(created.receipt, replayed.receipt)
        assertEquals(1L, queries.countTransactions().executeAsOne())
        assertEquals(1L, queries.countVersions().executeAsOne())
        assertEquals(1L, queries.countPostingSets().executeAsOne())
        assertEquals(2L, queries.countPostings().executeAsOne())
        assertEquals(1L, queries.countRequests().executeAsOne())
        assertEquals(1L, queries.countReceipts().executeAsOne())

        // IMP-6: the composition-root LedgerClock returns a near-current instant (loose tolerance).
        val now = graph.ledgerClock.now()
        val reference = Clock.System.now()
        assertTrue(abs(now.toEpochMilliseconds() - reference.toEpochMilliseconds()) <= 60_000L)
    }
}
