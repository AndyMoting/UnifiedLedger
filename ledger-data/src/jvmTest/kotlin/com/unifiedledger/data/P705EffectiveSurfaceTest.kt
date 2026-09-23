package com.unifiedledger.data

import com.unifiedledger.application.CorrectTransactionVersionResult
import com.unifiedledger.application.CreationEntry
import com.unifiedledger.application.ExecuteCorrectTransactionVersion
import com.unifiedledger.application.ExecuteRestoreTransaction
import com.unifiedledger.application.ExecuteVoidTransaction
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.LedgerCurrentStateResult
import com.unifiedledger.application.MonthlyBuckets
import com.unifiedledger.application.QueryLedgerCurrentState
import com.unifiedledger.application.QueryRecycleBin
import com.unifiedledger.application.RecycleBinResult
import com.unifiedledger.application.ResolveTransactionCorrectionCommitStatus
import com.unifiedledger.application.SummarizeLedgerActivity
import com.unifiedledger.application.TransactionCorrectionCommitResolution
import com.unifiedledger.application.TransactionCorrectionPlan
import com.unifiedledger.application.TransactionCorrectionReceipt
import com.unifiedledger.application.TransactionCorrectionRequestIdentity
import com.unifiedledger.application.TransactionCorrectionRequestSnapshot
import com.unifiedledger.application.TransactionVoidRequestSnapshot
import com.unifiedledger.application.VoidTransactionRequest
import com.unifiedledger.application.VoidTransactionResult
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.TransactionVoidFactKind
import com.unifiedledger.domain.VoidReason
import com.unifiedledger.domain.VoidReasonCode
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-05 effective-surface evidence (spec section 3.6; acceptance vectors V-01, V-20 and V-23).
 *
 * The effective predicate must reach every transaction-derived read surface. #1-#3
 * (monthly card/category/trend, flow list, detail) read `ledgerEntryRowsForLedger`; #4-#5
 * (HOME balances, the HOME "current transactions" fallback and the Analysis tab) read
 * `currentVersionRowsForLedger`. Both now join the single `transaction_effective_state` view,
 * and this class asserts the surfaces themselves, not just the view.
 */
class P705EffectiveSurfaceTest {
    private val ledgerId = P705Fixture.ledgerId
    private val catalog = P705Fixture.catalog()
    private val restoredAt = Instant.parse("2026-05-01T01:00:00Z")

    private fun state(harness: P705Database): LedgerCurrentState =
        assertIs<LedgerCurrentStateResult.Success>(
            QueryLedgerCurrentState(harness.readAdapter, ledgerId, catalog).query(),
        ).state

    private fun voidExpense(
        harness: P705Database,
        transactionId: String,
        ids: P705Ids,
    ): VoidTransactionResult =
        ExecuteVoidTransaction(harness.voidPort, ids.voidSource, fixedClock(P705Fixture.voidedAt))
            .execute(
                VoidTransactionRequest(
                    ledgerId = ledgerId,
                    requestId = ids.requestId(),
                    transactionId = TransactionId(transactionId),
                    reason = VoidReason(VoidReasonCode.MIS_ENTERED, "entered the wrong amount"),
                    confirmation = ExplicitManualSave,
                ),
            )

    private fun restoreTransaction(
        harness: P705Database,
        transactionId: String,
        ids: P705Ids,
        at: Instant,
    ): VoidTransactionResult =
        ExecuteRestoreTransaction(harness.voidPort, ids.voidSource, fixedClock(at), P705Fixture.admissionReader)
            .execute(
                VoidTransactionRequest(
                    ledgerId = ledgerId,
                    requestId = ids.requestId(),
                    transactionId = TransactionId(transactionId),
                    reason = VoidReason(VoidReasonCode.VOIDED_IN_ERROR, "voided by mistake"),
                    confirmation = ExplicitManualSave,
                ),
            )

    @Test
    fun voidRemovesTheEffectFromEveryEffectiveSurfaceAndTheRecycleBinKeepsIt() {
        P705Database.create("p705-surface-").use { harness ->
            harness.insertOrdinaryExpense("tx-expense-100", amountMinor = 10_000L)
            harness.insertOrdinaryIncome("tx-income-500", amountMinor = 50_000L)
            harness.insertManualExpenseCreationReceipt("tx-expense-100")

            // Before: both transactions are effective on every surface.
            assertEquals(
                setOf("tx-expense-100", "tx-income-500"),
                harness.readAdapter
                    .loadLedgerEntryRows(ledgerId)
                    .map { it.transactionId.value }
                    .toSet(),
            )
            assertEquals(
                setOf("tx-expense-100", "tx-income-500"),
                harness.readAdapter
                    .loadCurrentRows(ledgerId)
                    .map { it.transactionId.value }
                    .toSet(),
            )
            assertEquals(40_000L, state(harness).balances.single { it.accountId == P705Fixture.bankA }.ledgerSignedMinorUnits)
            // #1's input is exactly this row set; the month bucket key is the row's
            // statistics_at (MonthlyBuckets is covered by the ledger-application suite).
            assertEquals(
                listOf(P705Fixture.marchStatistics, P705Fixture.marchStatistics),
                harness.readAdapter.loadLedgerEntryRows(ledgerId).map { it.statisticsAt },
            )

            val ids = P705Ids("p705-surface")
            assertIs<VoidTransactionResult.Created>(voidExpense(harness, "tx-expense-100", ids))

            // #1-#3: monthly/flow/detail row set drops the voided transaction.
            assertEquals(
                listOf("tx-income-500"),
                harness.readAdapter.loadLedgerEntryRows(ledgerId).map { it.transactionId.value },
            )
            // #4: HOME current rows drop it, so the account balance loses exactly its effect.
            assertEquals(
                listOf("tx-income-500"),
                harness.readAdapter.loadCurrentRows(ledgerId).map { it.transactionId.value },
            )
            val after = state(harness)
            assertEquals(50_000L, after.balances.single { it.accountId == P705Fixture.bankA }.ledgerSignedMinorUnits)
            assertEquals(50_000L, after.balances.single { it.accountId == P705Fixture.bankA }.displayMinorUnits)
            // The voided expense leg no longer contributes any balance row at all.
            assertTrue(after.balances.none { it.accountId == P705Fixture.expenseAccount })
            // #5: the Analysis tab input (counts by kind and the expense/income totals) drops it.
            val summary = SummarizeLedgerActivity(catalog).summarize(after)
            assertEquals(1, summary.totalTransactionCount)
            assertEquals(mapOf(TransactionKind.INCOME to 1), summary.countByKind)
            assertEquals(listOf(50_000L), summary.totalsByCurrency.map { it.incomeMinorUnits })
            assertEquals(listOf(0L), summary.totalsByCurrency.map { it.expenseMinorUnits })
            // #1 again, with the frozen DP-3 month口径: the monthly/category/trend input no
            // longer carries the voided row, so it cannot be counted or bucketed any more.
            assertEquals(
                listOf("tx-income-500"),
                harness.readAdapter.loadLedgerEntryRows(ledgerId).map { it.transactionId.value },
            )

            // The transaction is still reachable through the recycle-bin read path.
            val recycleBin =
                assertIs<RecycleBinResult.Success>(QueryRecycleBin(harness.readAdapter, ledgerId, catalog).query())
            val row = recycleBin.rows.single()
            assertEquals(TransactionId("tx-expense-100"), row.voided.transactionId)
            assertEquals(TransactionVoidFactKind.VOID, row.voided.voidFactKind)
            assertEquals(VoidReason(VoidReasonCode.MIS_ENTERED, "entered the wrong amount"), row.voided.voidReason)
            assertEquals(P705Fixture.voidedAt, row.voided.voidedAt)
            assertEquals(CreationEntry.MANUAL_CREATED, row.creationEntry)
            assertTrue(row.restoreAdmissible)
        }
    }

    @Test
    fun restorePutsTheTransactionBackOnEveryEffectiveSurfaceExactlyOnce() {
        P705Database.create("p705-surface-restore-").use { harness ->
            harness.insertOrdinaryExpense("tx-expense-100", amountMinor = 10_000L)
            harness.insertOrdinaryIncome("tx-income-500", amountMinor = 50_000L)
            val ids = P705Ids("p705-surface-restore")
            // V-20: capture the full effective state (HOME balances, the "current transactions" row
            // set and the Analysis counts/amounts) before the void, so the restore can be compared
            // value-identically rather than only piecewise.
            val beforeRows = harness.readAdapter.loadCurrentRows(ledgerId)
            val beforeBalances = state(harness).balances
            val beforeSummary = SummarizeLedgerActivity(catalog).summarize(state(harness))

            assertIs<VoidTransactionResult.Created>(voidExpense(harness, "tx-expense-100", ids))
            assertEquals(1, harness.readAdapter.loadLedgerEntryRows(ledgerId).size)
            assertEquals(1, harness.readAdapter.loadCurrentRows(ledgerId).size)

            assertIs<VoidTransactionResult.Created>(
                restoreTransaction(harness, "tx-expense-100", ids, restoredAt),
            )

            // Exactly one row comes back, with the pre-void values, on every surface.
            val rows = harness.readAdapter.loadLedgerEntryRows(ledgerId)
            assertEquals(2, rows.size)
            val expenseRow = rows.single { it.transactionId.value == "tx-expense-100" }
            assertEquals(listOf(10_000L, -10_000L), expenseRow.postings.map { it.amount.minorUnits })
            assertEquals(2, harness.readAdapter.loadCurrentRows(ledgerId).size)
            // V-20 value identity: the restored surfaces equal the captured pre-void surfaces exactly.
            assertEquals(beforeRows, harness.readAdapter.loadCurrentRows(ledgerId))
            assertEquals(beforeBalances, state(harness).balances)
            assertEquals(beforeSummary, SummarizeLedgerActivity(catalog).summarize(state(harness)))
            assertEquals(
                P705Fixture.marchStatistics,
                harness.readAdapter
                    .loadLedgerEntryRows(ledgerId)
                    .single { it.transactionId.value == "tx-expense-100" }
                    .statisticsAt,
            )
            // The recycle bin is empty again: the latest fact is a restore.
            assertEquals(
                0,
                assertIs<RecycleBinResult.Success>(QueryRecycleBin(harness.readAdapter, ledgerId, catalog).query()).rows.size,
            )
            // No history was deleted: the void fact and its restore are both retained.
            assertEquals(2L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_void_fact"))
            assertEquals(
                1L,
                harness.ledgerQueryCount("SELECT count(*) FROM transaction_void_fact WHERE transaction_id = 'tx-expense-100' AND fact_kind = 'restore'"),
            )
        }
    }

    @Test
    fun sqlEffectivePredicateEqualsTheDomainVoidStateForEveryFactSet() {
        P705Database.create("p705-predicate-").use { harness ->
            harness.insertOrdinaryExpense("tx-no-fact", amountMinor = 1_000L)
            harness.insertOrdinaryExpense("tx-voided", amountMinor = 2_000L)
            harness.insertOrdinaryIncome("tx-voided-restored", amountMinor = 3_000L)

            val ids = P705Ids("p705-predicate")
            assertIs<VoidTransactionResult.Created>(voidExpense(harness, "tx-voided", ids))
            assertIs<VoidTransactionResult.Created>(voidExpense(harness, "tx-voided-restored", ids))
            assertIs<VoidTransactionResult.Created>(
                restoreTransaction(harness, "tx-voided-restored", ids, restoredAt),
            )

            // SQL representation: the named query over the single predicate view.
            val sqlEffective =
                harness.database.ledgerQueries
                    .effectiveTransactionIdsForLedger(ledgerId.value)
                    .executeAsList()
                    .toSet()
            // Domain representation: TransactionVoidState.isEffective over the stored facts, for
            // every transaction of the ledger — including the fact-free one, whose domain state
            // is the empty fact sequence and must still read as effective.
            val transactionIds = harness.transactionIds()
            assertEquals(listOf("tx-no-fact", "tx-voided", "tx-voided-restored"), transactionIds)
            transactionIds.forEach { transactionId ->
                assertEquals(
                    harness.voidStateOf(transactionId).isEffective,
                    transactionId in sqlEffective,
                    "SQL and domain effective predicates disagree for $transactionId",
                )
            }
            assertEquals(setOf("tx-no-fact", "tx-voided-restored"), sqlEffective)
            // The same predicate drives the effective row sets, so the equivalence is not vacuous.
            assertEquals(
                sqlEffective,
                harness.readAdapter
                    .loadLedgerEntryRows(ledgerId)
                    .map { it.transactionId.value }
                    .toSet(),
            )
        }
    }

    /**
     * V-01's monthly half (DP-3), asserted on the real bucketing code rather than on the row
     * set alone: the voided row leaves its own statistics month's `transactionCount` and is not
     * re-bucketed into another month. kotlinx-datetime is a test-only dependency of this module
     * (the application layer owns the frozen `Asia/Shanghai` bucketing).
     */
    @Test
    fun voidedRowLeavesTheMonthlyTransactionCountAndItsStatisticsBucket() {
        P705Database.create("p705-monthly-").use { harness ->
            harness.insertOrdinaryExpense("tx-expense-100", amountMinor = 10_000L)
            harness.insertOrdinaryIncome("tx-income-500", amountMinor = 50_000L)
            val ids = P705Ids("p705-monthly")
            val march = MonthlyBuckets.bucketKey(P705Fixture.marchStatistics)
            val april = MonthlyBuckets.bucketKey(P705Fixture.aprilStatistics)
            assertEquals(YearMonth(2026, 3), march)
            assertEquals(YearMonth(2026, 4), april)

            fun monthlyCount(month: YearMonth): Int =
                MonthlyBuckets
                    .aggregate(
                        harness.readAdapter.loadLedgerEntryRows(ledgerId),
                        ledgerId,
                        catalog,
                        listOf(month),
                    ).getValue(month)
                    .currencies
                    .single()
                    .transactionCount

            assertEquals(2, monthlyCount(march))
            assertIs<VoidTransactionResult.Created>(voidExpense(harness, "tx-expense-100", ids))
            // DP-3: the voided transaction no longer counts in the month its statistics_at
            // belongs to, and the void day does not move it into any other month.
            assertEquals(1, monthlyCount(march))
            assertEquals(0, monthlyCount(april))
        }
    }

    /**
     * V-16 automatic half (D-158 section 4): a ledger that was corrected, voided and restored
     * reopens with the same authoritative values on every surface. The write happens on one
     * connection, which is then truly CLOSED (the file is preserved, the harness's test-only
     * `closePreservingFile`) and proven unable to serve the probe read after the close, so the
     * read-back below cannot be reading through the original connection: it runs on a fresh
     * `P705Database.open` of the same path (mirroring `DesktopCurrentSchemaReopenTest`). The
     * effective row sets, balances, monthly counts, recycle bin and version/postings history must
     * read back value-identically.
     */
    @Test
    fun correctedVoidedAndRestoredLedgerReopensWithIdenticalAuthoritativeValues() {
        P705Database.create("p705-reopen-").use { writer ->
            val ids = P705Ids("p705-reopen")
            writer.insertOrdinaryExpense("tx-corrected", amountMinor = 10_000L)
            writer.insertOrdinaryIncome("tx-restored", amountMinor = 50_000L)
            writer.insertOrdinaryExpense("tx-voided", amountMinor = 3_000L)
            val correctedReceipt =
                assertIs<CorrectTransactionVersionResult.Created>(
                    ExecuteCorrectTransactionVersion(writer.correctionPort, ids.correctSource, P705Fixture.admissionReader)
                        .execute(
                            com.unifiedledger.application.ExplicitlyConfirmedTransactionCorrection(
                                ledgerId = ledgerId,
                                requestId = ids.requestId(),
                                transactionId = TransactionId("tx-corrected"),
                                expectedCurrentVersionId = TransactionVersionId("tx-corrected-version-1"),
                                note = "corrected",
                                statisticsAt = P705Fixture.aprilStatistics,
                                amount = Money.ofMinor(8_000L, P705Fixture.cny),
                                categoryId = P705Fixture.food,
                                fundingAccountId = P705Fixture.bankA,
                                confirmation = ExplicitManualSave,
                            ),
                        ),
                ).receipt
            assertIs<VoidTransactionResult.Created>(voidExpense(writer, "tx-voided", ids))
            assertIs<VoidTransactionResult.Created>(voidExpense(writer, "tx-restored", ids))
            assertIs<VoidTransactionResult.Created>(restoreTransaction(writer, "tx-restored", ids, restoredAt))

            // The writer connection is truly closed here; only the file survives.
            writer.closePreservingFile()
            // The close must be load-bearing: a closed writer can no longer serve reads, so the
            // reopen below cannot be reading through the original connection.
            val closedRead = runCatching { writer.readAdapter.loadLedgerEntryRows(ledgerId) }
            assertTrue(closedRead.isFailure, "the closed writer connection must not serve reads")
            // A fresh open of the same file-backed database is the reopen under test.
            P705Database.open(writer.filePath).use { reopened ->
                // Effective entry rows: the corrected transaction (new amount) and the restored one.
                val rows = reopened.readAdapter.loadLedgerEntryRows(ledgerId)
                assertEquals(
                    listOf("tx-corrected", "tx-restored"),
                    rows.map { it.transactionId.value }.sorted(),
                )
                val correctedRow = rows.single { it.transactionId.value == "tx-corrected" }
                assertEquals(correctedReceipt.versionId, correctedRow.currentVersionId)
                assertEquals(listOf(8_000L, -8_000L), correctedRow.postings.map { it.amount.minorUnits })
                assertEquals(P705Fixture.aprilStatistics, correctedRow.statisticsAt)
                assertEquals("corrected", correctedRow.note)
                // The voided transaction stays off the effective surfaces and in the recycle bin.
                assertEquals(
                    listOf("tx-voided"),
                    reopened.readAdapter.loadVoidedTransactionRows(ledgerId).map { it.transactionId.value },
                )
                // Version history is intact: the corrected transaction kept both versions.
                assertEquals(2L, reopened.versionCount("tx-corrected"))
                assertEquals(
                    1L,
                    reopened.ledgerQueryCount(
                        "SELECT count(*) FROM transaction_version WHERE transaction_id = 'tx-corrected' AND version_number = 1 AND posting_set_id = 'tx-corrected-posting-set-1'",
                    ),
                )
                // HOME balances and the monthly count read the same reopened values.
                val state =
                    assertIs<LedgerCurrentStateResult.Success>(
                        QueryLedgerCurrentState(reopened.readAdapter, ledgerId, catalog).query(),
                    ).state
                // +50,000 restored income − 8,000 corrected expense (voided 3,000 contributes nothing).
                assertEquals(42_000L, state.balances.single { it.accountId == P705Fixture.bankA }.ledgerSignedMinorUnits)
                val april = MonthlyBuckets.bucketKey(P705Fixture.aprilStatistics)
                assertEquals(
                    1,
                    MonthlyBuckets
                        .aggregate(reopened.readAdapter.loadLedgerEntryRows(ledgerId), ledgerId, catalog, listOf(april))
                        .getValue(april)
                        .currencies
                        .single()
                        .transactionCount,
                )
                // The recycle bin metadata is value-identical after reopen.
                val bin = assertIs<RecycleBinResult.Success>(QueryRecycleBin(reopened.readAdapter, ledgerId, catalog).query())
                assertEquals(listOf("tx-voided"), bin.rows.map { it.voided.transactionId.value })
                // V-21: assert the reason identity without printing the note literal in a message.
                val voided = bin.rows.single().voided
                assertEquals(VoidReasonCode.MIS_ENTERED, voided.voidReason.code)
                assertTrue(!voided.voidReason.note.isNullOrEmpty(), "the reopen must keep the reason note")
                assertEquals(TransactionVoidFactKind.VOID, voided.voidFactKind)
            }
        }
    }

    @Test
    fun unknownCommitResolutionHitsTheOriginalCorrectionReceiptAndNeverReCommits() {
        P705Database.create("p705-resolve-").use { harness ->
            harness.insertOrdinaryExpense("tx-expense-100", amountMinor = 10_000L)
            val ids = P705Ids("p705-resolve")
            val correctionIds = ids.correctIds()
            val requestId = ids.requestId()
            val snapshot =
                TransactionCorrectionRequestSnapshot(
                    ledgerId = ledgerId,
                    transactionId = TransactionId("tx-expense-100"),
                    expectedCurrentVersionId = TransactionVersionId("tx-expense-100-version-1"),
                    note = "corrected",
                    statisticsAt = P705Fixture.marchStatistics,
                    amount = Money.ofMinor(8_000L, P705Fixture.cny),
                    categoryId = P705Fixture.food,
                    fundingAccountId = P705Fixture.bankA,
                )
            val committed =
                harness.correctionPort.commitOnce(
                    TransactionCorrectionRequestIdentity(ledgerId, requestId),
                    snapshot,
                ) {
                    TransactionCorrectionPlan.Commit(
                        receipt =
                            TransactionCorrectionReceipt(
                                confirmationId = correctionIds.confirmationId,
                                transactionId = TransactionId("tx-expense-100"),
                                versionId = correctionIds.versionId,
                                expectedCurrentVersionId = snapshot.expectedCurrentVersionId,
                            ),
                        postingSetId = correctionIds.postingSetId,
                        postings =
                            listOf(
                                Posting(correctionIds.categoryPostingId, P705Fixture.expenseAccount, Money.ofMinor(8_000L, P705Fixture.cny)),
                                Posting(correctionIds.fundingPostingId, P705Fixture.bankA, Money.ofMinor(-8_000L, P705Fixture.cny)),
                            ),
                        reuseCurrentPostingSet = false,
                    )
                }
            val created = assertIs<CorrectTransactionVersionResult.Created>(committed)

            val resolver = ResolveTransactionCorrectionCommitStatus(harness.readAdapter)
            assertEquals(
                created.receipt,
                assertIs<TransactionCorrectionCommitResolution.MatchingReceipt>(
                    resolver.resolve(ledgerId, requestId, snapshot),
                ).receipt,
            )
            // A changed snapshot under the same identity is a conflict, never a silent re-commit.
            assertEquals(
                TransactionCorrectionCommitResolution.SnapshotConflict,
                resolver.resolve(
                    ledgerId,
                    requestId,
                    snapshot.copy(amount = Money.ofMinor(9_000L, P705Fixture.cny)),
                ),
            )
            // An identity that was never committed stays unknown (Absent), so the caller may
            // retry the same request id but must not silently reuse another one.
            assertEquals(
                TransactionCorrectionCommitResolution.Absent,
                resolver.resolve(ledgerId, ids.requestId(), snapshot),
            )
            assertEquals(2L, harness.versionCount("tx-expense-100"))
        }
    }

    /** The void/restore snapshot comparison must round-trip the frozen reason vocabulary. */
    @Test
    fun voidUnknownCommitResolutionRoundTripsTheReasonVocabulary() {
        P705Database.create("p705-resolve-void-").use { harness ->
            harness.insertOrdinaryExpense("tx-expense-100", amountMinor = 10_000L)
            val ids = P705Ids("p705-resolve-void")
            val requestId = ids.requestId()
            val snapshot =
                TransactionVoidRequestSnapshot(
                    ledgerId = ledgerId,
                    transactionId = TransactionId("tx-expense-100"),
                    factKind = TransactionVoidFactKind.VOID,
                    reason = VoidReason(VoidReasonCode.NO_LONGER_APPLICABLE, null),
                )
            val created =
                assertIs<VoidTransactionResult.Created>(
                    harness.voidPort.commitOnce(
                        com.unifiedledger.application.TransactionVoidRequestIdentity(ledgerId, requestId),
                        snapshot,
                    ) {
                        val factIds = ids.voidIds()
                        com.unifiedledger.application.TransactionVoidPlan.Commit(
                            receipt =
                                com.unifiedledger.application.TransactionVoidReceipt(
                                    confirmationId = factIds.confirmationId,
                                    transactionId = TransactionId("tx-expense-100"),
                                    factId = factIds.factId,
                                    factKind = TransactionVoidFactKind.VOID,
                                ),
                            createdAt = P705Fixture.voidedAt,
                        )
                    },
                )
            val resolved =
                com.unifiedledger.application
                    .ResolveTransactionVoidCommitStatus(harness.readAdapter)
                    .resolve(ledgerId, requestId, snapshot)
            assertEquals(
                created.receipt,
                assertIs<com.unifiedledger.application.TransactionVoidCommitResolution.MatchingReceipt>(resolved).receipt,
            )
            assertEquals(
                com.unifiedledger.application.TransactionVoidCommitResolution.SnapshotConflict,
                com.unifiedledger.application
                    .ResolveTransactionVoidCommitStatus(harness.readAdapter)
                    .resolve(
                        ledgerId,
                        requestId,
                        snapshot.copy(reason = VoidReason(VoidReasonCode.OTHER, "different")),
                    ),
            )
        }
    }

    /** V-19 for the restore half of the merged family: a lost restore receipt stays resolvable. */
    @Test
    fun restoreUnknownCommitResolutionReturnsTheCommittedReceipt() {
        P705Database.create("p705-resolve-restore-").use { harness ->
            harness.insertOrdinaryExpense("tx-expense-100", amountMinor = 10_000L)
            val ids = P705Ids("p705-resolve-restore")
            assertIs<VoidTransactionResult.Created>(voidExpense(harness, "tx-expense-100", ids))
            val requestId = ids.requestId()
            val snapshot =
                TransactionVoidRequestSnapshot(
                    ledgerId = ledgerId,
                    transactionId = TransactionId("tx-expense-100"),
                    factKind = TransactionVoidFactKind.RESTORE,
                    reason = VoidReason(VoidReasonCode.VOIDED_IN_ERROR, "voided by mistake"),
                )
            val created =
                assertIs<VoidTransactionResult.Created>(
                    harness.voidPort.commitOnce(
                        com.unifiedledger.application.TransactionVoidRequestIdentity(ledgerId, requestId),
                        snapshot,
                    ) {
                        val factIds = ids.voidIds()
                        com.unifiedledger.application.TransactionVoidPlan.Commit(
                            receipt =
                                com.unifiedledger.application.TransactionVoidReceipt(
                                    confirmationId = factIds.confirmationId,
                                    transactionId = TransactionId("tx-expense-100"),
                                    factId = factIds.factId,
                                    factKind = TransactionVoidFactKind.RESTORE,
                                ),
                            createdAt = restoredAt,
                        )
                    },
                )
            val resolved =
                com.unifiedledger.application
                    .ResolveTransactionVoidCommitStatus(harness.readAdapter)
                    .resolve(ledgerId, requestId, snapshot)
            assertEquals(
                created.receipt,
                assertIs<com.unifiedledger.application.TransactionVoidCommitResolution.MatchingReceipt>(resolved).receipt,
            )
            // An identity that was never committed stays unknown, so the caller may retry it.
            assertEquals(
                com.unifiedledger.application.TransactionVoidCommitResolution.Absent,
                com.unifiedledger.application
                    .ResolveTransactionVoidCommitStatus(harness.readAdapter)
                    .resolve(ledgerId, ids.requestId(), snapshot),
            )
            assertEquals(2L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_void_fact"))
        }
    }
}
