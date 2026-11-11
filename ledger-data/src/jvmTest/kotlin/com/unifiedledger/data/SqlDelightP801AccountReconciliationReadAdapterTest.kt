package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.DEFAULT_HIDDEN_EXPENSE_ACCOUNT_ID
import com.unifiedledger.application.DEFAULT_MANAGEABLE_ACCOUNT_ID
import com.unifiedledger.application.P408EvidenceResponsibility
import com.unifiedledger.application.P408ReconciliationStatus
import com.unifiedledger.application.P801AccountReconciliationCursor
import com.unifiedledger.application.P801AccountReconciliationPageResult
import com.unifiedledger.application.P801AccountReconciliationStatusTotal
import com.unifiedledger.application.P801AccountReconciliationSummaryResult
import com.unifiedledger.application.P801ReconciledLegAuditResult
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P8-01a account reconciliation read adapter evidence (D-308 items 6/8; spec section 2.3). Drives
 * the real SQL over a real in-memory SQLite database, so every guard below is load-bearing: the
 * named fixture that fails when the guard is deleted is listed with it.
 *
 *  - the frozen queue order is a TIME order, not a text order: `occurred_at` is projected inline to
 *    epoch nanoseconds and the same parser is applied to the cursor text (a `+08:00` leg whose text
 *    sorts above an older `Z` leg must follow it, and the second page must still find it);
 *  - the CURRENT version only: a transaction with two versions admits only version 2's posting, and
 *    version 1's posting id never appears (page or summary);
 *  - EFFECTIVE transactions only: a voided transaction's leg appears in neither read;
 *  - the section 2.1 composite mark is column-wise: an ASSET account failing `owned_by_user` alone,
 *    one failing `real_account` alone, and an owned/real EXPENSE account are each excluded on their
 *    own, while the same-shaped owned/real ASSET one appears;
 *  - the posting-id tie-break inside one instant, and a keyset cursor continuing the page exactly
 *    once (no skip, no repeat);
 *  - a leg on another real account of the same ledger never enters the requested account's queue;
 *  - a queue leg without a `posting_reconciliation` row reads `PENDING` (the SQL COALESCE default),
 *    a stored status is read as-is, and only an ACTIVE latest evidence-link history sets
 *    `hasActiveEvidenceLink`;
 *  - the summary folds exact integer minor units per (currency, status) with its row count over the
 *    SAME leg set as the page (it excludes the superseded and the voided legs too);
 *  - an absent group is absent and never a zero row, while a real zero amount is a real group with
 *    one row (the D-089 distinction both ways).
 *
 * The storage text is round-tripped through [Instant.parse] by the adapter, which is also the only
 * user-visible failure for an unparseable `occurred_at`; the SQL's epoch sentinel for such a text is
 * documented in the query, not asserted here (no writer shape in this fixture produces one).
 *
 * All data synthetic and anonymous, with fixed instants.
 */
class SqlDelightP801AccountReconciliationReadAdapterTest {
    private val ledgerId = LedgerId("ledger-p801-read")
    private val assetAccountId = AccountId(DEFAULT_MANAGEABLE_ACCOUNT_ID)
    private val expenseAccountId = AccountId(DEFAULT_HIDDEN_EXPENSE_ACCOUNT_ID)
    private val otherAssetAccountId = AccountId("asset-second")

    @Test
    fun queueOrdersByOccurredAtDescThenPostingIdAscAndContinuesByKeyset() {
        withDatabase { _, driver, adapter ->
            seedTransaction(driver, "tx-old", "2026-03-01T02:00:00Z", legs = listOf(SeededLeg("posting-old", assetAccountId.value, -1_000L)))
            // Same instant, two legs of one transaction: the tie-break is posting_id ASC, never the
            // insertion (posting_index) order.
            seedTransaction(
                driver,
                "tx-tie",
                "2026-03-05T02:00:00Z",
                note = "tie note",
                legs =
                    listOf(
                        SeededLeg("posting-z", assetAccountId.value, -200L),
                        SeededLeg("posting-a", assetAccountId.value, -300L),
                    ),
            )
            seedTransaction(driver, "tx-new", "2026-03-09T02:00:00Z", legs = listOf(SeededLeg("posting-new", assetAccountId.value, -4_000L)))

            val first =
                assertIs<P801AccountReconciliationPageResult.Success>(
                    adapter.loadAccountReconciliationPage(ledgerId, assetAccountId, after = null, pageSize = 2),
                ).page
            assertEquals(listOf("posting-new", "posting-a"), first.rows.map { it.postingId.value })
            assertEquals(
                P801AccountReconciliationCursor(occurredAtStorageValue = "2026-03-05T02:00:00Z", postingId = PostingId("posting-a")),
                first.nextCursor,
            )
            // A row carries the leg's own exact facts, not an aggregate: signed minor units, the
            // currency, the current version's note and the transaction's effective kind.
            val tieLeg = first.rows[1]
            assertEquals(TransactionId("tx-tie"), tieLeg.transactionId)
            assertEquals(Instant.parse("2026-03-05T02:00:00Z"), tieLeg.occurredAt)
            assertEquals("tie note", tieLeg.note)
            assertEquals(assetAccountId, tieLeg.accountId)
            assertEquals(-300L, tieLeg.amountMinor)
            assertEquals(CurrencyUnit("CNY", 2), tieLeg.currency)
            assertEquals(P408ReconciliationStatus.PENDING, tieLeg.status)
            assertEquals(false, tieLeg.hasActiveEvidenceLink)
            assertEquals(TransactionKind.ACCOUNT_TRANSFER, tieLeg.transactionKind)

            val second =
                assertIs<P801AccountReconciliationPageResult.Success>(
                    adapter.loadAccountReconciliationPage(ledgerId, assetAccountId, after = first.nextCursor, pageSize = 2),
                ).page
            assertEquals(listOf("posting-z", "posting-old"), second.rows.map { it.postingId.value })
            assertNull(second.nextCursor)
        }
    }

    @Test
    fun queueOrdersByInstantNotByTextAndTheCursorParsesTheSameWay() {
        withDatabase { _, driver, adapter ->
            // The two persisted shapes disagree with time order: "2026-01-15T00:30:00+08:00" is
            // 16:30Z, EARLIER than "2026-01-14T20:00:00Z", while its TEXT sorts LATER — so TEXT DESC
            // would render the offset leg first and a text-compared cursor would then drop the other.
            seedTransaction(driver, "tx-offset", "2026-01-15T00:30:00+08:00", legs = listOf(SeededLeg("posting-offset", assetAccountId.value, -100L)))
            seedTransaction(driver, "tx-z", "2026-01-14T20:00:00Z", legs = listOf(SeededLeg("posting-z", assetAccountId.value, -200L)))

            val first =
                assertIs<P801AccountReconciliationPageResult.Success>(
                    adapter.loadAccountReconciliationPage(ledgerId, assetAccountId, after = null, pageSize = 1),
                ).page
            assertEquals(listOf("posting-z"), first.rows.map { it.postingId.value })
            assertEquals(Instant.parse("2026-01-14T20:00:00Z"), first.rows.single().occurredAt)
            assertEquals(
                P801AccountReconciliationCursor(occurredAtStorageValue = "2026-01-14T20:00:00Z", postingId = PostingId("posting-z")),
                first.nextCursor,
            )

            val second =
                assertIs<P801AccountReconciliationPageResult.Success>(
                    adapter.loadAccountReconciliationPage(ledgerId, assetAccountId, after = first.nextCursor, pageSize = 1),
                ).page
            assertEquals(listOf("posting-offset"), second.rows.map { it.postingId.value })
            assertEquals(Instant.parse("2026-01-15T00:30:00+08:00"), second.rows.single().occurredAt)
            assertNull(second.nextCursor)
        }
    }

    @Test
    fun onlyTheCurrentVersionsPostingsEnterTheQueueAndTheSummary() {
        withDatabase { _, driver, adapter ->
            seedSupersededTransaction(
                driver,
                transactionId = "tx-replaced",
                firstOccurredAt = "2026-03-01T02:00:00Z",
                firstLegs = listOf(SeededLeg("posting-replaced", assetAccountId.value, -1_000L)),
                secondOccurredAt = "2026-03-02T02:00:00Z",
                secondLegs = listOf(SeededLeg("posting-current", assetAccountId.value, -2_000L)),
            )

            val rows =
                assertIs<P801AccountReconciliationPageResult.Success>(
                    adapter.loadAccountReconciliationPage(ledgerId, assetAccountId, after = null),
                ).page.rows
            assertEquals(listOf("posting-current"), rows.map { it.postingId.value })
            assertEquals(Instant.parse("2026-03-02T02:00:00Z"), rows.single().occurredAt)
            // The summary reads the same leg set: one current leg, not one per version.
            assertEquals(
                listOf(P801AccountReconciliationStatusTotal(CurrencyUnit("CNY", 2), P408ReconciliationStatus.PENDING, -2_000L, 1)),
                assertIs<P801AccountReconciliationSummaryResult.Success>(
                    adapter.loadAccountReconciliationSummary(ledgerId, assetAccountId),
                ).totals,
            )
        }
    }

    @Test
    fun aVoidedTransactionsLegEntersNeitherTheQueueNorTheSummary() {
        withDatabase { _, driver, adapter ->
            seedTransaction(driver, "tx-lived", "2026-03-01T02:00:00Z", legs = listOf(SeededLeg("posting-lived", assetAccountId.value, -100L)))
            seedVoidedTransaction(driver, "tx-voided", "2026-03-02T02:00:00Z", legs = listOf(SeededLeg("posting-voided", assetAccountId.value, -7_000L)))

            val rows =
                assertIs<P801AccountReconciliationPageResult.Success>(
                    adapter.loadAccountReconciliationPage(ledgerId, assetAccountId, after = null),
                ).page.rows
            assertEquals(listOf("posting-lived"), rows.map { it.postingId.value })
            assertEquals(
                listOf(P801AccountReconciliationStatusTotal(CurrencyUnit("CNY", 2), P408ReconciliationStatus.PENDING, -100L, 1)),
                assertIs<P801AccountReconciliationSummaryResult.Success>(
                    adapter.loadAccountReconciliationSummary(ledgerId, assetAccountId),
                ).totals,
            )
        }
    }

    @Test
    fun eachCompositeMarkColumnExcludesOnItsOwn() {
        withDatabase { _, driver, adapter ->
            // Same-shape ASSET accounts, each failing exactly one mark column, plus one that passes.
            seedCatalogAccount(driver, "asset-not-owned", kind = "ASSET", ownedByUser = false, realAccount = true)
            seedCatalogAccount(driver, "asset-not-real", kind = "ASSET", ownedByUser = true, realAccount = false)
            seedCatalogAccount(driver, "expense-marked-like", kind = "EXPENSE", ownedByUser = true, realAccount = true)
            seedCatalogAccount(driver, "asset-marked", kind = "ASSET", ownedByUser = true, realAccount = true)
            seedTransaction(driver, "tx-not-owned", "2026-03-01T02:00:00Z", legs = listOf(SeededLeg("posting-not-owned", "asset-not-owned", -1L)))
            seedTransaction(driver, "tx-not-real", "2026-03-02T02:00:00Z", legs = listOf(SeededLeg("posting-not-real", "asset-not-real", -2L)))
            seedTransaction(driver, "tx-wrong-kind", "2026-03-03T02:00:00Z", legs = listOf(SeededLeg("posting-wrong-kind", "expense-marked-like", -3L)))
            seedTransaction(driver, "tx-marked", "2026-03-04T02:00:00Z", legs = listOf(SeededLeg("posting-marked", "asset-marked", -4L)))

            // The passing account proves the three failing ones differ only by their own mark column.
            assertEquals(
                listOf("posting-marked"),
                assertIs<P801AccountReconciliationPageResult.Success>(
                    adapter.loadAccountReconciliationPage(ledgerId, AccountId("asset-marked"), after = null),
                ).page.rows.map { it.postingId.value },
            )
            for (excluded in listOf("asset-not-owned", "asset-not-real", "expense-marked-like")) {
                val page =
                    assertIs<P801AccountReconciliationPageResult.Success>(
                        adapter.loadAccountReconciliationPage(ledgerId, AccountId(excluded), after = null),
                    ).page
                assertTrue(page.rows.isEmpty(), "account $excluded must not be a queue member")
                assertTrue(
                    assertIs<P801AccountReconciliationSummaryResult.Success>(
                        adapter.loadAccountReconciliationSummary(ledgerId, AccountId(excluded)),
                    ).totals.isEmpty(),
                    "account $excluded must not contribute a summary group",
                )
            }
        }
    }

    @Test
    fun onlyTheCompositeMarkedLegsOfTheRequestedAccountEnterTheQueue() {
        withDatabase { _, driver, adapter ->
            seedCatalogAccount(driver, otherAssetAccountId.value, kind = "ASSET", ownedByUser = true, realAccount = true)
            // One transfer: its real asset leg and its fee leg on the hidden expense account.
            seedTransaction(
                driver,
                "tx-transfer",
                "2026-03-05T02:00:00Z",
                legs =
                    listOf(
                        SeededLeg("posting-transfer-out", assetAccountId.value, -6_000L),
                        SeededLeg("posting-transfer-fee", expenseAccountId.value, 100L),
                    ),
            )
            // The destination leg of the same ledger, on another real asset account.
            seedTransaction(driver, "tx-other", "2026-03-06T02:00:00Z", legs = listOf(SeededLeg("posting-other", otherAssetAccountId.value, 5_900L)))

            val assetQueue =
                assertIs<P801AccountReconciliationPageResult.Success>(
                    adapter.loadAccountReconciliationPage(ledgerId, assetAccountId, after = null),
                ).page
            assertEquals(listOf("posting-transfer-out"), assetQueue.rows.map { it.postingId.value })
            // The fee leg never appears: the composite mark excludes EXPENSE (and INCOME) accounts,
            // so the hidden fee leg of the very same transaction stays out of every queue.
            val expenseQueue =
                assertIs<P801AccountReconciliationPageResult.Success>(
                    adapter.loadAccountReconciliationPage(ledgerId, expenseAccountId, after = null),
                ).page
            assertTrue(expenseQueue.rows.isEmpty())
            val otherQueue =
                assertIs<P801AccountReconciliationPageResult.Success>(
                    adapter.loadAccountReconciliationPage(ledgerId, otherAssetAccountId, after = null),
                ).page
            assertEquals(listOf("posting-other"), otherQueue.rows.map { it.postingId.value })
        }
    }

    @Test
    fun pendingIsReadWithoutAReconciliationRowAndOnlyAnActiveLinkCounts() {
        withDatabase { _, driver, adapter ->
            seedTransaction(driver, "tx-plain", "2026-03-01T02:00:00Z", legs = listOf(SeededLeg("posting-plain", assetAccountId.value, -1_000L)))
            seedTransaction(driver, "tx-checked", "2026-03-02T02:00:00Z", legs = listOf(SeededLeg("posting-checked", assetAccountId.value, -2_000L)))
            seedReconciliation(driver, "posting-checked", "CHECKED")
            seedTransaction(driver, "tx-linked", "2026-03-03T02:00:00Z", legs = listOf(SeededLeg("posting-linked", assetAccountId.value, -3_000L)))
            seedEvidenceLink(driver, "tx-linked", "posting-linked", active = true)
            seedTransaction(driver, "tx-invalidated", "2026-03-04T02:00:00Z", legs = listOf(SeededLeg("posting-invalidated", assetAccountId.value, -4_000L)))
            seedEvidenceLink(driver, "tx-invalidated", "posting-invalidated", active = false)

            val rows =
                assertIs<P801AccountReconciliationPageResult.Success>(
                    adapter.loadAccountReconciliationPage(ledgerId, assetAccountId, after = null),
                ).page.rows
            assertEquals(
                listOf("posting-invalidated", "posting-linked", "posting-checked", "posting-plain"),
                rows.map { it.postingId.value },
            )
            // An invalidated latest history is not an active link; an active one is.
            assertEquals(false, rows[0].hasActiveEvidenceLink)
            assertEquals(true, rows[1].hasActiveEvidenceLink)
            // A stored status is read as-is; a leg with no row at all is PENDING (待对账).
            assertEquals(P408ReconciliationStatus.CHECKED, rows[2].status)
            assertEquals(P408ReconciliationStatus.PENDING, rows[1].status)
            assertEquals(P408ReconciliationStatus.PENDING, rows[3].status)
        }
    }

    @Test
    fun theSummarySumsExactMinorUnitsPerCurrencyAndStatus() {
        withDatabase { _, driver, adapter ->
            // Two PENDING CNY legs on one account must fold into one group; the CHECKED CNY leg and
            // the USD leg stay separate groups, so the grouping key really carries both dimensions.
            seedTransaction(driver, "tx-p1", "2026-03-01T02:00:00Z", legs = listOf(SeededLeg("posting-p1", assetAccountId.value, -5_000L)))
            seedTransaction(driver, "tx-p2", "2026-03-02T02:00:00Z", legs = listOf(SeededLeg("posting-p2", assetAccountId.value, -1_000L)))
            seedTransaction(driver, "tx-c1", "2026-03-03T02:00:00Z", legs = listOf(SeededLeg("posting-c1", assetAccountId.value, -3_000L)))
            seedReconciliation(driver, "posting-c1", "CHECKED")
            seedTransaction(
                driver,
                "tx-usd",
                "2026-03-04T02:00:00Z",
                legs = listOf(SeededLeg("posting-usd", assetAccountId.value, 1_234L, currencyCode = "USD")),
            )

            val totals =
                assertIs<P801AccountReconciliationSummaryResult.Success>(
                    adapter.loadAccountReconciliationSummary(ledgerId, assetAccountId),
                ).totals
            assertEquals(
                listOf(
                    P801AccountReconciliationStatusTotal(CurrencyUnit("CNY", 2), P408ReconciliationStatus.CHECKED, -3_000L, 1),
                    P801AccountReconciliationStatusTotal(CurrencyUnit("CNY", 2), P408ReconciliationStatus.PENDING, -6_000L, 2),
                    P801AccountReconciliationStatusTotal(CurrencyUnit("USD", 2), P408ReconciliationStatus.PENDING, 1_234L, 1),
                ),
                totals,
            )
            // The two-leg CNY PENDING group is an exact integer sum of its legs, not a rounded value.
            assertEquals(-6_000L, totals[1].totalMinorUnits)
            assertEquals(2, totals[1].rowCount)
        }
    }

    @Test
    fun anAbsentGroupIsEmptyWhileARealZeroAmountIsARealGroup() {
        withDatabase { _, driver, adapter ->
            // The account holds only a non-real (fee) leg, so both reads answer with nothing at all.
            seedTransaction(driver, "tx-fee", "2026-03-01T02:00:00Z", legs = listOf(SeededLeg("posting-fee", expenseAccountId.value, 100L)))
            val feePage =
                assertIs<P801AccountReconciliationPageResult.Success>(
                    adapter.loadAccountReconciliationPage(ledgerId, expenseAccountId, after = null),
                ).page
            assertTrue(feePage.rows.isEmpty())
            assertNull(feePage.nextCursor)
            val emptyTotals =
                assertIs<P801AccountReconciliationSummaryResult.Success>(
                    adapter.loadAccountReconciliationSummary(ledgerId, expenseAccountId),
                ).totals
            // No synthesized (CNY, PENDING, 0, 0) row: an absent group is absent (D-089).
            assertTrue(emptyTotals.isEmpty())

            // A real zero-amount leg is the OTHER kind of answer: a real group with one real row,
            // which is exactly what makes "no data" and "a zero sum" distinguishable.
            seedTransaction(driver, "tx-zero", "2026-03-02T02:00:00Z", legs = listOf(SeededLeg("posting-zero", assetAccountId.value, 0L)))
            assertEquals(
                listOf(P801AccountReconciliationStatusTotal(CurrencyUnit("CNY", 2), P408ReconciliationStatus.PENDING, 0L, 1)),
                assertIs<P801AccountReconciliationSummaryResult.Success>(
                    adapter.loadAccountReconciliationSummary(ledgerId, assetAccountId),
                ).totals,
            )
        }
    }

    /**
     * P8-01a i3a audit read-back (spec section 2.6; D-308 item 7). Three load-bearing distinctions:
     * an absent leg is [P801ReconciledLegAuditResult.NotFound] (never an empty audit), a leg with
     * nothing reconciled is a SUCCESSFUL audit with no link and no reconciliation fact (a typed
     * absence, never a zero-filled basis), and a linked leg answers with the evidence source labels,
     * the responsibility, the link's own latest state and its history — while a link whose request
     * carries no snapshot row keeps `decision == null` instead of inventing a basis.
     */
    @Test
    fun theAuditReadBackAnswersAbsenceAndTheLinkedLegsEvidenceSource() {
        withDatabase { _, driver, adapter ->
            seedTransaction(driver, "tx-audit-plain", "2026-03-01T02:00:00Z", legs = listOf(SeededLeg("posting-audit-plain", assetAccountId.value, -1_000L)))
            seedTransaction(driver, "tx-audit-linked", "2026-03-02T02:00:00Z", legs = listOf(SeededLeg("posting-audit-linked", assetAccountId.value, -2_000L)))
            seedEvidenceLink(driver, "tx-audit-linked", "posting-audit-linked", active = true)

            // A posting id that names no leg on this ledger: an explicit absent verdict.
            assertEquals(
                P801ReconciledLegAuditResult.NotFound,
                adapter.loadReconciledLegAudit(ledgerId, PostingId("posting-does-not-exist")),
            )

            // A real leg with nothing reconciled: success, no link, no reconciliation fact.
            val plain = assertIs<P801ReconciledLegAuditResult.Success>(adapter.loadReconciledLegAudit(ledgerId, PostingId("posting-audit-plain"))).audit
            assertEquals(PostingId("posting-audit-plain"), plain.postingId)
            assertEquals(TransactionId("tx-audit-plain"), plain.transactionId)
            assertNull(plain.reconciliation)
            assertTrue(plain.evidence.isEmpty())

            // The linked leg: one link with its source row, responsibility, latest state and history.
            val linked = assertIs<P801ReconciledLegAuditResult.Success>(adapter.loadReconciledLegAudit(ledgerId, PostingId("posting-audit-linked"))).audit
            val link = linked.evidence.single()
            assertEquals("link-tx-audit-linked-posting-audit-linked", link.linkId)
            assertEquals("evidence-tx-audit-linked-posting-audit-linked", link.evidenceId)
            assertEquals(P408EvidenceResponsibility.REAL_ACCOUNT_POSTING, link.responsibility)
            assertEquals("active", link.state)
            assertEquals(1L, link.stateSequence)
            assertEquals("ref-tx-audit-linked-posting-audit-linked", link.source.importInputRef)
            assertEquals(0L, link.source.recordOrdinal)
            assertEquals("ordinary_flow_source", link.source.recordKind)
            assertEquals(listOf("1", "active", "confirmed"), link.history.single().let { entry -> listOf(entry.sequence.toString(), entry.state, entry.reason) })
            // The fixture's link has no request-snapshot row, so the basis stays a typed absence.
            assertNull(link.decision)
            // The leg itself still carries no reconciliation fact (the link alone does not write one).
            assertNull(linked.reconciliation)
        }
    }

    /**
     * P8-01a i3a F3 (spec sections 2.6/3): the audit read's POSITIVE path. The previous absence
     * fixture left `p801ReconciledLegReconciliationHistory` unexecuted, so the basis columns, the
     * sorted-and-comma-joined `match_basis` (split by the adapter), the window/distance integers, the
     * human decision with its timestamp and the status history with sequence 1/2 were all unpinned.
     */
    @Test
    fun theAuditReadBackAnswersTheFrozenDecisionBasisAndTheStatusHistory() {
        withDatabase { _, driver, adapter ->
            seedTransaction(driver, "tx-audit-decision", "2026-03-02T02:00:00Z", legs = listOf(SeededLeg("posting-audit-decision", assetAccountId.value, -2_000L)))
            seedEvidenceLink(driver, "tx-audit-decision", "posting-audit-decision", active = true)
            seedDecisionSnapshot(driver, "tx-audit-decision", "posting-audit-decision")
            seedCheckedReconciliation(driver, "tx-audit-decision", "posting-audit-decision")

            val audit =
                assertIs<P801ReconciledLegAuditResult.Success>(
                    adapter.loadReconciledLegAudit(ledgerId, PostingId("posting-audit-decision")),
                ).audit
            val link = audit.evidence.single()
            val decision = assertNotNull(link.decision)
            // The store writes the set sorted and comma-joined; the adapter splits it back.
            assertEquals(2, decision.basisVersion)
            assertEquals(listOf("account", "amount", "currency", "direction", "occurred_at_window"), decision.matchBasis)
            assertEquals(2, decision.windowDays)
            assertEquals(1, decision.naturalDayDistance)
            assertEquals("confirm_match", decision.humanDecision)
            assertEquals("2026-03-02T03:00:00Z", decision.decidedAt)
            val reconciliation = assertNotNull(audit.reconciliation)
            assertEquals("reconciliation-posting-audit-decision", reconciliation.reconciliationId)
            assertEquals(P408ReconciliationStatus.CHECKED, reconciliation.status)
            assertEquals(listOf(1L, 2L), reconciliation.history.map { entry -> entry.sequence })
            assertEquals(
                listOf(P408ReconciliationStatus.PENDING, P408ReconciliationStatus.CHECKED),
                reconciliation.history.map { entry -> entry.status },
            )
            assertEquals(listOf(null, link.linkId), reconciliation.history.map { entry -> entry.evidenceLinkId })
        }
    }

    /**
     * P8-01a i3a F5 (spec section 2.6): a `posting_reconciliation` row whose history is empty must
     * still answer its CURRENT status with an empty history (the LEFT JOIN) — with an inner join the
     * leg would read as "no reconciliation row at all", which the copy would then repeat as a lie.
     */
    @Test
    fun theAuditAnswersAReconciliationRowWhoseHistoryIsEmpty() {
        withDatabase { _, driver, adapter ->
            seedTransaction(driver, "tx-audit-empty-history", "2026-03-03T02:00:00Z", legs = listOf(SeededLeg("posting-audit-empty-history", assetAccountId.value, -1_500L)))
            seedReconciliation(driver, "posting-audit-empty-history", "CHECKED")

            val audit =
                assertIs<P801ReconciledLegAuditResult.Success>(
                    adapter.loadReconciledLegAudit(ledgerId, PostingId("posting-audit-empty-history")),
                ).audit
            val reconciliation = assertNotNull(audit.reconciliation)
            assertEquals(P408ReconciliationStatus.CHECKED, reconciliation.status)
            assertTrue(reconciliation.history.isEmpty())
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun withDatabase(block: (LedgerDatabase, JdbcSqliteDriver, SqlDelightP801AccountReconciliationReadAdapter) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, migrationProperties())
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            SqlDelightCatalogStore(database, driver).bootstrap(ledgerId, defaultCatalogSeed())
            block(database, driver, SqlDelightP801AccountReconciliationReadAdapter(database, SqlDelightEvidenceProjectionStore(database, driver)))
        } finally {
            driver.close()
        }
    }

    /**
     * One synthetic current-version transaction with its own posting set and the given legs. The
     * numeric statistics projection is left NULL on purpose: the queue and the summary are defined
     * on `occurred_at` and must never depend on it.
     */
    private fun seedTransaction(
        driver: JdbcSqliteDriver,
        transactionId: String,
        occurredAt: String,
        note: String? = null,
        kind: String = "ACCOUNT_TRANSFER",
        legs: List<SeededLeg>,
    ) {
        seedTransactionRow(driver, transactionId, kind)
        seedVersion(driver, transactionId, versionNumber = 1, occurredAt = occurredAt, note = note, legs = legs)
        pointCurrentVersion(driver, transactionId, versionNumber = 1)
    }

    /**
     * One transaction with two versions where `current_version` names version 2: version 1's posting
     * set still carries its own postings, so only the current-version join can keep them out.
     */
    private fun seedSupersededTransaction(
        driver: JdbcSqliteDriver,
        transactionId: String,
        firstOccurredAt: String,
        firstLegs: List<SeededLeg>,
        secondOccurredAt: String,
        secondLegs: List<SeededLeg>,
    ) {
        seedTransactionRow(driver, transactionId, kind = "ACCOUNT_TRANSFER")
        seedVersion(driver, transactionId, versionNumber = 1, occurredAt = firstOccurredAt, note = null, legs = firstLegs)
        seedVersion(driver, transactionId, versionNumber = 2, occurredAt = secondOccurredAt, note = null, legs = secondLegs)
        pointCurrentVersion(driver, transactionId, versionNumber = 2)
    }

    /** The same transaction, then the void fact the schema requires to make it non-effective. */
    private fun seedVoidedTransaction(
        driver: JdbcSqliteDriver,
        transactionId: String,
        occurredAt: String,
        legs: List<SeededLeg>,
    ) {
        seedTransaction(driver, transactionId, occurredAt, legs = legs)
        driver.execute(
            null,
            "INSERT INTO transaction_void_request(ledger_id, request_id, transaction_id, fact_kind, reason_code, reason_note, confirmation_marker) " +
                "VALUES ('${ledgerId.value}','void-request-$transactionId','$transactionId','void','mis_entered',NULL,'explicit_manual_save')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO transaction_void_fact(ledger_id, transaction_id, sequence, fact_id, fact_kind, reason_code, reason_note, request_id, confirmation_id, created_at) " +
                "VALUES ('${ledgerId.value}','$transactionId',1,'void-fact-$transactionId','void','mis_entered',NULL," +
                "'void-request-$transactionId','void-confirmation-$transactionId','$occurredAt')",
            0,
        )
    }

    private fun seedTransactionRow(
        driver: JdbcSqliteDriver,
        transactionId: String,
        kind: String,
    ) {
        driver.execute(
            null,
            "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('$transactionId','${ledgerId.value}','$kind')",
            0,
        )
    }

    private fun seedVersion(
        driver: JdbcSqliteDriver,
        transactionId: String,
        versionNumber: Int,
        occurredAt: String,
        note: String?,
        legs: List<SeededLeg>,
    ) {
        val versionId = "$transactionId-version-$versionNumber"
        val postingSetId = "$versionId-posting-set"
        driver.execute(null, "INSERT INTO posting_set(posting_set_id, ledger_id) VALUES ('$postingSetId','${ledgerId.value}')", 0)
        driver.execute(
            null,
            "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note) " +
                "VALUES ('$versionId','$transactionId','${ledgerId.value}',$versionNumber,'$postingSetId','$occurredAt','$occurredAt','$occurredAt',${note?.let { "'${it.replace("'", "''")}'" } ?: "NULL"})",
            0,
        )
        legs.forEachIndexed { index, leg ->
            driver.execute(
                null,
                "INSERT INTO posting(posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, currency_code, currency_precision) " +
                    "VALUES ('${leg.postingId}','$postingSetId','${ledgerId.value}',$index,'${leg.accountId}',${leg.amountMinor},'${leg.currencyCode}',2)",
                0,
            )
        }
    }

    private fun pointCurrentVersion(
        driver: JdbcSqliteDriver,
        transactionId: String,
        versionNumber: Int,
    ) {
        driver.execute(
            null,
            "INSERT INTO ledger_transaction_current_version(transaction_id, ledger_id, current_version_id) " +
                "VALUES ('$transactionId','${ledgerId.value}','$transactionId-version-$versionNumber')",
            0,
        )
    }

    /** An extra `catalog_account` row (the seed provides only the default asset and expense accounts). */
    private fun seedCatalogAccount(
        driver: JdbcSqliteDriver,
        accountId: String,
        kind: String,
        ownedByUser: Boolean,
        realAccount: Boolean,
    ) {
        driver.execute(
            null,
            "INSERT INTO catalog_account(ledger_id, account_id, name, kind, currency_code, currency_precision, owned_by_user, real_account, system_role, hidden, active) " +
                "VALUES ('${ledgerId.value}','$accountId','$accountId','$kind','CNY',2,${if (ownedByUser) 1 else 0},${if (realAccount) 1 else 0},NULL,0,1)",
            0,
        )
    }

    private fun seedReconciliation(
        driver: JdbcSqliteDriver,
        postingId: String,
        status: String,
    ) {
        driver.execute(
            null,
            "INSERT INTO posting_reconciliation(ledger_id, reconciliation_id, posting_id, status, latest_sequence) " +
                "VALUES ('${ledgerId.value}','reconciliation-$postingId','$postingId','$status',1)",
            0,
        )
    }

    /**
     * One evidence link to a posting plus its history. The schema's sequence/transition guards
     * require sequence 1 to be `active`, so an invalidated link carries the legal two-row history
     * (1 active -> 2 invalidated) and its LATEST row is what the queue must read.
     */
    private fun seedEvidenceLink(
        driver: JdbcSqliteDriver,
        transactionId: String,
        postingId: String,
        active: Boolean,
    ) {
        val suffix = "$transactionId-$postingId"
        driver.execute(
            null,
            "INSERT INTO import_request(ledger_id, request_id, operation) VALUES ('${ledgerId.value}','import-request-$suffix','intake')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness) " +
                "VALUES ('${ledgerId.value}','source-$suffix','import-request-$suffix','ref-$suffix',0,'ordinary_flow_source','hash-$suffix',1,'valid_incomplete')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO import_evidence(ledger_id, evidence_id, source_id, evidence_kind, observed_at) " +
                "VALUES ('${ledgerId.value}','evidence-$suffix','source-$suffix','source_observation','2026-03-01T02:00:00Z')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO reconciliation_request(ledger_id, request_id, operation, input_fingerprint, outcome) " +
                "VALUES ('${ledgerId.value}','reconciliation-request-$suffix','confirm_link','fingerprint-$suffix','ACCEPTED')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO evidence_link(ledger_id, link_id, evidence_id, posting_id, transaction_id, responsibility, basis_version, match_basis, candidate_id, request_id, created_at) " +
                "VALUES ('${ledgerId.value}','link-$suffix','evidence-$suffix','$postingId','$transactionId','real_account_posting',2," +
                "'amount,currency,direction,account,occurred_at_window','candidate-$suffix','reconciliation-request-$suffix','2026-03-02T02:00:00Z')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO evidence_link_history(ledger_id, link_id, sequence, state, reason, request_id, occurred_at) " +
                "VALUES ('${ledgerId.value}','link-$suffix',1,'active','confirmed','reconciliation-request-$suffix','2026-03-02T02:00:00Z')",
            0,
        )
        if (!active) {
            driver.execute(
                null,
                "INSERT INTO evidence_link_history(ledger_id, link_id, sequence, state, reason, request_id, occurred_at) " +
                    "VALUES ('${ledgerId.value}','link-$suffix',2,'invalidated','corrected','reconciliation-request-$suffix','2026-03-03T02:00:00Z')",
                0,
            )
        }
    }

    private fun migrationProperties(): Properties =
        Properties().apply {
            setProperty("foreign_keys", "true")
        }

    /**
     * The frozen confirm basis the real store writes for one linked leg (spec section 2.5):
     * `match_basis` is the set sorted at write time and comma-joined, `basis_version` 2, the
     * matcher's window and natural-day distance, and the one admitted human-decision token.
     */
    private fun seedDecisionSnapshot(
        driver: JdbcSqliteDriver,
        transactionId: String,
        postingId: String,
    ) {
        val suffix = "$transactionId-$postingId"
        driver.execute(
            null,
            "INSERT INTO reconciliation_request_snapshot(ledger_id, request_id, evidence_id, candidate_id, posting_id, transaction_id, " +
                "amount_minor, currency_code, currency_precision, direction, account_id, responsibility, basis_version, match_basis, " +
                "window_days, natural_day_distance, source_occurred_at, confirmed_at, human_decision, " +
                "projection_id, projection_rule_id, projection_rule_version, normalized_amount_minor, raw_amount_minor, raw_currency_precision) " +
                "VALUES ('${ledgerId.value}','reconciliation-request-$suffix','evidence-$suffix','candidate-$suffix','$postingId','$transactionId'," +
                "2000,'CNY',2,'out','${assetAccountId.value}','real_account_posting',2," +
                "'account,amount,currency,direction,occurred_at_window',2,1,'2026-03-02T02:00:00Z','2026-03-02T03:00:00Z','confirm_match'," +
                "NULL,NULL,NULL,NULL,2000,2)",
            0,
        )
    }

    /**
     * A CHECKED reconciliation with the legal two-row history (`PENDING` sequence 1 without a link,
     * `CHECKED` sequence 2 through [seedEvidenceLink]'s link). The row is inserted already naming
     * sequence 2 (the update guard only allows latest_sequence + 1 with a matching history row).
     */
    private fun seedCheckedReconciliation(
        driver: JdbcSqliteDriver,
        transactionId: String,
        postingId: String,
    ) {
        val suffix = "$transactionId-$postingId"
        driver.execute(
            null,
            "INSERT INTO posting_reconciliation(ledger_id, reconciliation_id, posting_id, status, latest_sequence) " +
                "VALUES ('${ledgerId.value}','reconciliation-$postingId','$postingId','CHECKED',2)",
            0,
        )
        listOf(
            Triple(1, "PENDING", "NULL"),
            Triple(2, "CHECKED", "'link-$suffix'"),
        ).forEach { (sequence, status, linkId) ->
            driver.execute(
                null,
                "INSERT INTO posting_reconciliation_history(ledger_id, reconciliation_id, sequence, status, evidence_link_id, request_id, occurred_at) " +
                    "VALUES ('${ledgerId.value}','reconciliation-$postingId',$sequence,'$status',$linkId," +
                    "'reconciliation-request-$suffix','2026-03-02T0${sequence + 1}:00:00Z')",
                0,
            )
        }
    }
}

/** One synthetic posting leg of a seeded transaction: its id, account, signed minor units and currency. */
private data class SeededLeg(
    val postingId: String,
    val accountId: String,
    val amountMinor: Long,
    val currencyCode: String = "CNY",
)
