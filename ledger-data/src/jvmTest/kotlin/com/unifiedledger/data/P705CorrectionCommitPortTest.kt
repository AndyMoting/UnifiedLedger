package com.unifiedledger.data

import com.unifiedledger.application.CorrectTransactionVersionResult
import com.unifiedledger.application.ENTRY_NOTE_MAX_CODE_POINTS
import com.unifiedledger.application.ExecuteCorrectTransactionVersion
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.ExplicitlyConfirmedTransactionCorrection
import com.unifiedledger.application.LedgerCurrentStateResult
import com.unifiedledger.application.QueryLedgerCurrentState
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.SummarizeLedgerActivity
import com.unifiedledger.application.TransactionCorrectionPlan
import com.unifiedledger.application.TransactionCorrectionReceipt
import com.unifiedledger.application.TransactionCorrectionRequestIdentity
import com.unifiedledger.application.TransactionCorrectionRequestSnapshot
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.P705FailureCode
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-05.B correction commit evidence (spec sections 3.2/4.3; acceptance vectors V-04..V-10,
 * V-12, V-13, V-18). Every expectation uses the spec's exact values: the corrected amount is
 * 80.00 where the original was 100.00, `occurred_at`/`effective_at` are copied verbatim, only
 * `statistics_at` follows the request, and the reconciliation/evidence/lending/import owners
 * stay at zero rows.
 */
class P705CorrectionCommitPortTest {
    private val ledgerId = P705Fixture.ledgerId
    private val catalog = P705Fixture.catalog()
    private val firstVersionId = TransactionVersionId("tx-expense-100-version-1")

    private fun correct(
        harness: P705Database,
        ids: P705Ids,
        requestId: RequestId,
        transactionId: String = "tx-expense-100",
        amountMinor: Long = 8_000L,
        statisticsAt: Instant = P705Fixture.marchStatistics,
        note: String? = "corrected",
        expectedVersionId: TransactionVersionId = firstVersionId,
        categoryId: com.unifiedledger.domain.CategoryId = P705Fixture.food,
        fundingAccountId: com.unifiedledger.domain.AccountId = P705Fixture.bankA,
        admissionReader: com.unifiedledger.application.CatalogAdmissionReader = P705Fixture.admissionReader,
    ): CorrectTransactionVersionResult =
        ExecuteCorrectTransactionVersion(harness.correctionPort, ids.correctSource, admissionReader)
            .execute(
                ExplicitlyConfirmedTransactionCorrection(
                    ledgerId = ledgerId,
                    requestId = requestId,
                    transactionId = TransactionId(transactionId),
                    expectedCurrentVersionId = expectedVersionId,
                    note = note,
                    statisticsAt = statisticsAt,
                    amount = Money.ofMinor(amountMinor, P705Fixture.cny),
                    categoryId = categoryId,
                    fundingAccountId = fundingAccountId,
                    confirmation = ExplicitManualSave,
                ),
            )

    private val ownerTables =
        listOf(
            "posting_reconciliation",
            "posting_reconciliation_history",
            "evidence_link",
            "evidence_link_history",
            "evidence_projection",
            "reconciliation_correction_snapshot",
            "lending_position",
            "lending_position_history",
            "import_confirmation",
            "import_receipt",
            "import_candidate_status_history",
        )

    private fun ownerCounts(harness: P705Database): Map<String, Long> = ownerTables.associateWith { table -> harness.ledgerQueryCount("SELECT count(*) FROM $table") }

    @Test
    fun amountCorrectionAppendsVersionTwoKeepsHistoryAndLeavesTheOwnersAtZero() {
        P705Database.create("p705-correct-").use { harness ->
            harness.insertOrdinaryExpense("tx-expense-100", amountMinor = 10_000L)
            val ids = P705Ids("p705-correct")
            val ownersBefore = ownerCounts(harness)
            assertEquals(0L, ownersBefore.values.sum())

            val result = correct(harness, ids, ids.requestId())
            val created = assertIs<CorrectTransactionVersionResult.Created>(result)

            // V-06: version_number + 1, a fresh posting set, and the old version untouched.
            assertEquals(2L, harness.versionCount("tx-expense-100"))
            assertEquals(created.receipt.versionId.value, harness.currentVersionId("tx-expense-100"))
            assertEquals(
                1L,
                harness.ledgerQueryCount("SELECT count(*) FROM transaction_version WHERE transaction_id = 'tx-expense-100' AND version_number = 2"),
            )
            assertEquals(
                1L,
                harness.ledgerQueryCount("SELECT count(*) FROM transaction_version WHERE version_id = 'tx-expense-100-version-1' AND version_number = 1 AND statistics_at = '2026-03-05T02:00:00Z'"),
            )
            // V-05: the new version carries 80.00 on both legs; the old posting set keeps 100.00.
            assertEquals(
                listOf(8_000L, -8_000L),
                harness.database.ledgerQueries
                    .currentVersionPostingsForTransaction(ledgerId.value, "tx-expense-100") { _, _, _, amountMinor, _, _ ->
                        amountMinor
                    }.executeAsList(),
            )
            assertEquals(
                listOf(10_000L, -10_000L),
                harness.ledgerQueryLongs(
                    "SELECT amount_minor FROM posting WHERE posting_set_id = 'tx-expense-100-posting-set-1' ORDER BY posting_index",
                ),
            )
            // The three time columns: occurred_at/effective_at verbatim, statistics_at from the request.
            assertEquals(
                "2026-03-05T02:00:00Z|2026-03-05T02:00:00Z|2026-03-05T02:00:00Z",
                harness.ledgerQueryText(
                    "SELECT occurred_at || '|' || effective_at || '|' || statistics_at FROM transaction_version " +
                        "WHERE version_id = '${created.receipt.versionId.value}'",
                ),
            )
            // Only 80 participates in the effective surfaces, and no new economic event appeared.
            assertEquals(
                listOf(8_000L, -8_000L),
                harness.readAdapter
                    .loadLedgerEntryRows(ledgerId)
                    .single()
                    .postings
                    .map { it.amount.minorUnits },
            )
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM ledger_transaction"))
            assertEquals(4L, harness.ledgerQueryCount("SELECT count(*) FROM posting"))
            val state =
                assertIs<LedgerCurrentStateResult.Success>(
                    QueryLedgerCurrentState(harness.readAdapter, ledgerId, catalog).query(),
                ).state
            assertEquals(-8_000L, state.balances.single { it.accountId == P705Fixture.bankA }.ledgerSignedMinorUnits)
            assertEquals(
                listOf(8_000L),
                SummarizeLedgerActivity(catalog).summarize(state).totalsByCurrency.map { it.expenseMinorUnits },
            )
            // V-12: the reconciliation/evidence/lending/import owners keep zero rows.
            assertEquals(ownersBefore, ownerCounts(harness))
        }
    }

    @Test
    fun crossMonthStatisticsCorrectionMovesOnlyTheBucketAndKeepsTheOccurredTime() {
        P705Database.create("p705-correct-month-").use { harness ->
            harness.insertOrdinaryExpense("tx-expense-100", amountMinor = 10_000L)
            val ids = P705Ids("p705-correct-month")
            assertIs<CorrectTransactionVersionResult.Created>(
                correct(harness, ids, ids.requestId(), statisticsAt = P705Fixture.aprilStatistics),
            )
            val row = harness.readAdapter.loadLedgerEntryRows(ledgerId).single()
            assertEquals(P705Fixture.aprilStatistics, row.statisticsAt)
            assertEquals(P705Fixture.marchStatistics, row.occurredAt)
            assertEquals(
                "2026-03-05T02:00:00Z",
                harness.ledgerQueryText("SELECT occurred_at FROM transaction_version WHERE version_id = '${ids.lastVersionId}'"),
            )
            // The month bucket key is the row's statistics_at (MonthlyBuckets is a pure
            // function of this value and is covered by the ledger-application suite), so the
            // cross-month correction lands the row in April only.
            assertEquals(
                listOf(P705Fixture.aprilStatistics),
                harness.readAdapter.loadLedgerEntryRows(ledgerId).map { it.statisticsAt },
            )
            assertEquals(
                "2026-04-05T02:00:00Z",
                harness.ledgerQueryText("SELECT statistics_at FROM transaction_version WHERE version_id = '${ids.lastVersionId}'"),
            )
        }
    }

    /**
     * V-07 through the real monthly projection (D-158 section 4): the row-set-layer assertion
     * above does not exercise `QueryMonthlyActivity`. Re-asserted here on the unified monthly use
     * case: after a cross-month `statistics_at` correction the effect is counted in April only,
     * March is zero, and `occurredAt` (the source-document time) is unchanged.
     */
    @Test
    fun crossMonthCorrectionMovesOnlyTheStatisticsMonthThroughTheMonthlyProjection() {
        P705Database.create("p705-correct-month-projection-").use { harness ->
            harness.insertOrdinaryExpense("tx-expense-100", amountMinor = 10_000L)
            val ids = P705Ids("p705-correct-month-projection")
            val clock = fixedClock(Instant.parse("2026-04-20T00:00:00Z"))
            val query = com.unifiedledger.application.QueryMonthlyActivity(harness.readAdapter, ledgerId, catalog, clock)
            val march = kotlinx.datetime.YearMonth(2026, 3)
            val april = kotlinx.datetime.YearMonth(2026, 4)

            fun activity(month: kotlinx.datetime.YearMonth) = assertIs<com.unifiedledger.application.MonthlyActivityResult.Success>(query.query(month)).activity

            // Before the correction the expense sits in March.
            assertEquals(1, activity(march).currencies.single().transactionCount)
            assertEquals(0, activity(april).currencies.single().transactionCount)

            assertIs<CorrectTransactionVersionResult.Created>(
                correct(harness, ids, ids.requestId(), amountMinor = 10_000L, statisticsAt = P705Fixture.aprilStatistics),
            )

            // After the cross-month correction the effect is counted in April only; March is zero.
            assertEquals(0, activity(march).currencies.single().transactionCount)
            assertEquals(1, activity(april).currencies.single().transactionCount)
            assertEquals(10_000L, activity(april).currencies.single().netExpenseMinorUnits)
            // The source-document time is unchanged: only statistics_at follows the request.
            val row = harness.readAdapter.loadLedgerEntryRows(ledgerId).single()
            assertEquals(P705Fixture.marchStatistics, row.occurredAt)
            assertEquals(P705Fixture.aprilStatistics, row.statisticsAt)
        }
    }

    @Test
    fun aNoteOnlyCorrectionReusesTheCurrentPostingSetAndKeepsPostingIdentity() {
        P705Database.create("p705-correct-note-").use { harness ->
            harness.insertOrdinaryExpense("tx-expense-100", amountMinor = 10_000L)
            val ids = P705Ids("p705-correct-note")
            val created =
                assertIs<CorrectTransactionVersionResult.Created>(
                    correct(harness, ids, ids.requestId(), amountMinor = 10_000L, note = "renamed"),
                )
            // Spec section 3.2's frozen write form: an unchanged (accountId, amount, currency)
            // leg set keeps the current posting set, so posting identity is not rebound and the
            // basis of "unchanged funding legs keep their reconciliation rows and evidence
            // links" holds. Version 2 binds the same set and the same posting rows.
            assertEquals(
                "tx-expense-100-posting-set-1",
                harness.ledgerQueryText("SELECT posting_set_id FROM transaction_version WHERE version_id = '${created.receipt.versionId.value}'"),
            )
            assertEquals(
                listOf("tx-expense-100-posting-0", "tx-expense-100-posting-1"),
                harness.ledgerQueryTexts("SELECT posting_id FROM posting ORDER BY posting_index"),
            )
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM posting_set"))
            assertEquals(2L, harness.versionCount("tx-expense-100"))
            assertEquals(
                "renamed",
                harness.ledgerQueryText("SELECT note FROM transaction_version WHERE version_id = '${created.receipt.versionId.value}'"),
            )
            // A real leg change still allocates a fresh posting set with fresh posting ids.
            assertIs<CorrectTransactionVersionResult.Created>(
                correct(
                    harness,
                    ids,
                    ids.requestId(),
                    amountMinor = 8_000L,
                    note = "renamed",
                    expectedVersionId = TransactionVersionId(created.receipt.versionId.value),
                ),
            )
            val secondVersionId = harness.currentVersionId("tx-expense-100")
            assertEquals(2L, harness.ledgerQueryCount("SELECT count(*) FROM posting_set"))
            assertEquals(4L, harness.ledgerQueryCount("SELECT count(*) FROM posting"))
            assertTrue(
                harness.ledgerQueryText("SELECT posting_set_id FROM transaction_version WHERE version_id = '$secondVersionId'") !=
                    "tx-expense-100-posting-set-1",
            )
            // The reused set is untouched by either correction.
            assertEquals(
                listOf(10_000L, -10_000L),
                harness.ledgerQueryLongs(
                    "SELECT amount_minor FROM posting WHERE posting_set_id = 'tx-expense-100-posting-set-1' ORDER BY posting_index",
                ),
            )
        }
    }

    @Test
    fun overLongNoteIsAFieldRejectionAtTheExistingEntryBound() {
        P705Database.create("p705-note-bound-").use { harness ->
            harness.insertOrdinaryExpense("tx-expense-100", amountMinor = 10_000L)
            val ids = P705Ids("p705-note-bound")
            // The frozen bound itself is admissible (spec section 3.2 "备注长度沿既有上限").
            val atBound = "x".repeat(ENTRY_NOTE_MAX_CODE_POINTS)
            assertIs<CorrectTransactionVersionResult.Created>(
                correct(harness, ids, ids.requestId(), amountMinor = 10_000L, note = atBound),
            )
            // One code point over is an inadmissible field value with zero writes; the claim is
            // discarded, so the identity is not left behind either.
            assertEquals(
                CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_FIELD_NOT_SUPPORTED),
                correct(
                    harness,
                    ids,
                    ids.requestId(),
                    amountMinor = 10_000L,
                    note = atBound + "x",
                    expectedVersionId = TransactionVersionId(harness.currentVersionId("tx-expense-100")),
                ),
            )
            assertEquals(2L, harness.versionCount("tx-expense-100"))
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_correction_request"))
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_correction_receipt"))
        }
    }

    /**
     * The plan callback is a public port, so the write boundary must enforce the ledger's
     * posting-set invariant itself instead of trusting the caller: an unbalanced plan is a
     * typed rejection with zero writes (no version, no posting set, no leftover claim).
     */
    @Test
    fun theWriteBoundaryRejectsAPlanWhosePostingsDoNotFormAPostingSet() {
        P705Database.create("p705-posting-boundary-").use { harness ->
            harness.insertOrdinaryExpense("tx-expense-100", amountMinor = 10_000L)
            val ids = P705Ids("p705-posting-boundary")
            val correctionIds = ids.correctIds()
            val requestId = ids.requestId()
            val snapshot =
                TransactionCorrectionRequestSnapshot(
                    ledgerId = ledgerId,
                    transactionId = TransactionId("tx-expense-100"),
                    expectedCurrentVersionId = TransactionVersionId("tx-expense-100-version-1"),
                    note = "unbalanced",
                    statisticsAt = P705Fixture.marchStatistics,
                    amount = Money.ofMinor(8_000L, P705Fixture.cny),
                    categoryId = P705Fixture.food,
                    fundingAccountId = P705Fixture.bankA,
                )
            val result =
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
                                Posting(correctionIds.fundingPostingId, P705Fixture.bankA, Money.ofMinor(-7_000L, P705Fixture.cny)),
                            ),
                        reuseCurrentPostingSet = false,
                    )
                }
            assertEquals(
                CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_CONSTRAINT_VIOLATION),
                result,
            )
            assertEquals(1L, harness.versionCount("tx-expense-100"))
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM posting_set"))
            assertEquals(2L, harness.ledgerQueryCount("SELECT count(*) FROM posting"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_correction_request"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_correction_receipt"))
        }
    }

    @Test
    fun staleExpectedVersionIsItsOwnVariantWithZeroWrites() {
        P705Database.create("p705-stale-").use { harness ->
            harness.insertOrdinaryExpense("tx-expense-100", amountMinor = 10_000L)
            val ids = P705Ids("p705-stale")
            val first = correct(harness, ids, ids.requestId())
            assertIs<CorrectTransactionVersionResult.Created>(first)

            // A new request id still carrying the superseded CAS token must not auto-advance.
            val stale =
                correct(
                    harness,
                    ids,
                    ids.requestId(),
                    amountMinor = 6_000L,
                    expectedVersionId = firstVersionId,
                )
            assertEquals(CorrectTransactionVersionResult.StaleCurrentVersion, stale)
            assertEquals(2L, harness.versionCount("tx-expense-100"))
            // The stale claim was discarded, so only the successful correction left a request row.
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_correction_request"))
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_correction_receipt"))
            // Re-reading the CAS token and retrying succeeds (no silent reuse of the latest version).
            val retried =
                correct(
                    harness,
                    ids,
                    ids.requestId(),
                    amountMinor = 6_000L,
                    expectedVersionId = TransactionVersionId(harness.currentVersionId("tx-expense-100")),
                )
            assertIs<CorrectTransactionVersionResult.Created>(retried)
            assertEquals(3L, harness.versionCount("tx-expense-100"))
        }
    }

    @Test
    fun equivalentReplayReturnsTheOriginalReceiptAndChangedReplayIsAConflict() {
        P705Database.create("p705-replay-").use { harness ->
            harness.insertOrdinaryExpense("tx-expense-100", amountMinor = 10_000L)
            val ids = P705Ids("p705-replay")
            val requestId = ids.requestId()
            val created = assertIs<CorrectTransactionVersionResult.Created>(correct(harness, ids, requestId))

            // Same request id, same snapshot: the original receipt, zero new entities.
            val replay = correct(harness, ids, requestId)
            assertEquals(CorrectTransactionVersionResult.NoChange(created.receipt), replay)
            assertEquals(2L, harness.versionCount("tx-expense-100"))
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_correction_receipt"))

            // Same request id, changed snapshot: a conflict with zero writes.
            val conflict = correct(harness, ids, requestId, amountMinor = 7_000L)
            assertEquals(
                CorrectTransactionVersionResult.RequestIdentityConflict(
                    com.unifiedledger.application.TransactionCorrectionRequestIdentity(ledgerId, requestId),
                ),
                conflict,
            )
            assertEquals(2L, harness.versionCount("tx-expense-100"))

            // Replay after success carrying the newly read CAS token is an identity conflict,
            // never a stale: replay resolution precedes CAS evaluation.
            val newTokenReplay =
                correct(
                    harness,
                    ids,
                    requestId,
                    expectedVersionId = TransactionVersionId(harness.currentVersionId("tx-expense-100")),
                )
            assertIs<CorrectTransactionVersionResult.RequestIdentityConflict>(newTokenReplay)
            assertEquals(2L, harness.versionCount("tx-expense-100"))
        }
    }

    @Test
    fun rejectedCorrectionWritesNothing() {
        P705Database.create("p705-reject-").use { harness ->
            harness.insertOrdinaryExpense("tx-expense-100", amountMinor = 10_000L)
            val ids = P705Ids("p705-reject")
            val requestId = ids.requestId()

            // An inactive category is a typed rejection with zero writes (V-04/V-11 analogue).
            val inactiveCatalog =
                com.unifiedledger.domain.LedgerCatalog
                    .create(
                        accounts = catalog.accounts,
                        categories =
                            catalog.categories.map {
                                if (it.id == P705Fixture.food) it.copy(active = false) else it
                            },
                    ).let { assertIs<com.unifiedledger.domain.DomainResult.Success<com.unifiedledger.domain.LedgerCatalog>>(it).value }
            val rejected =
                correct(
                    harness,
                    ids,
                    requestId,
                    admissionReader = com.unifiedledger.application.CatalogAdmissionReader { inactiveCatalog },
                )
            assertEquals(
                CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE),
                rejected,
            )
            assertEquals(1L, harness.versionCount("tx-expense-100"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_correction_request"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_correction_receipt"))
            assertEquals(2L, harness.ledgerQueryCount("SELECT count(*) FROM posting"))

            // An unavailable catalog fails closed instead of admitting the stale snapshot.
            val unavailable =
                correct(
                    harness,
                    ids,
                    requestId,
                    admissionReader = com.unifiedledger.application.CatalogAdmissionReader { null },
                )
            assertEquals(
                CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE),
                unavailable,
            )
            assertEquals(1L, harness.versionCount("tx-expense-100"))

            // A non-positive amount is a rejected field with zero writes.
            val invalidAmount = correct(harness, ids, requestId, amountMinor = 0L)
            assertEquals(
                CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_FIELD_NOT_SUPPORTED),
                invalidAmount,
            )
            assertEquals(1L, harness.versionCount("tx-expense-100"))
        }
    }

    @Test
    fun unsupportedKindsLineagesAndVoidedTargetsAreTypedRejections() {
        P705Database.create("p705-unsupported-").use { harness ->
            // ACCOUNT_TRANSFER: a supported-by-nobody kind in this slice.
            harness.insertTransactionOfKind("tx-transfer", com.unifiedledger.domain.TransactionKind.ACCOUNT_TRANSFER)
            // The remaining later-slice matrix rows (D-158 section 4): REFUND_RECEIPT (DP-13),
            // CREDIT_REPAYMENT (product-reachable import lineage, DP-5) and a canonical-only kind
            // (LEND, persisted as kind=EXPENSE + canonical_kind) must all be typed rejections.
            harness.insertTransactionOfKind("tx-refund", com.unifiedledger.domain.TransactionKind.REFUND_RECEIPT)
            harness.insertTransactionOfKind("tx-credit", com.unifiedledger.domain.TransactionKind.CREDIT_REPAYMENT)
            harness.insertTransactionOfKind("tx-lend", com.unifiedledger.domain.TransactionKind.LEND)
            // An import-created EXPENSE: the lineage is a later slice (DP-5).
            harness.insertOrdinaryExpense("tx-imported", amountMinor = 10_000L)
            harness.insertImportCreationConfirmation("tx-imported")
            harness.insertOrdinaryExpense("tx-voided", amountMinor = 10_000L)
            harness.insertOrdinaryExpense("tx-expense-100", amountMinor = 10_000L)

            val ids = P705Ids("p705-unsupported")
            assertEquals(
                CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_KIND_NOT_SUPPORTED),
                correct(harness, ids, ids.requestId(), transactionId = "tx-transfer"),
            )
            assertEquals(
                CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_KIND_NOT_SUPPORTED),
                correct(harness, ids, ids.requestId(), transactionId = "tx-refund"),
            )
            assertEquals(
                CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_KIND_NOT_SUPPORTED),
                correct(harness, ids, ids.requestId(), transactionId = "tx-credit"),
            )
            assertEquals(
                CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_KIND_NOT_SUPPORTED),
                correct(harness, ids, ids.requestId(), transactionId = "tx-lend"),
            )
            assertEquals(
                CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_CREATION_LINEAGE_NOT_SUPPORTED),
                correct(harness, ids, ids.requestId(), transactionId = "tx-imported"),
            )
            assertEquals(
                CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_TRANSACTION_NOT_FOUND),
                correct(harness, ids, ids.requestId(), transactionId = "tx-missing"),
            )
            // A CAS token that is not the current version — including one that never existed
            // — is the stale variant, not a not-found: the transaction is in this ledger.
            assertEquals(
                CorrectTransactionVersionResult.StaleCurrentVersion,
                correct(
                    harness,
                    ids,
                    ids.requestId(),
                    transactionId = "tx-expense-100",
                    expectedVersionId = TransactionVersionId("version-of-another-ledger"),
                ),
            )
            assertEquals(1L, harness.versionCount("tx-expense-100"))
            // Void the target and prove a voided transaction cannot be corrected (DP-8).
            assertIs<com.unifiedledger.application.VoidTransactionResult.Created>(
                com.unifiedledger.application
                    .ExecuteVoidTransaction(
                        harness.voidPort,
                        ids.voidSource,
                        fixedClock(P705Fixture.voidedAt),
                    ).execute(
                        com.unifiedledger.application.VoidTransactionRequest(
                            ledgerId = ledgerId,
                            requestId = ids.requestId(),
                            transactionId = TransactionId("tx-voided"),
                            reason = com.unifiedledger.domain.VoidReason(com.unifiedledger.domain.VoidReasonCode.MIS_ENTERED),
                            confirmation = ExplicitManualSave,
                        ),
                    ),
            )
            assertEquals(
                CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_TRANSACTION_VOIDED),
                correct(harness, ids, ids.requestId(), transactionId = "tx-voided"),
            )
            assertEquals(1L, harness.versionCount("tx-voided"))
            assertEquals(1L, harness.versionCount("tx-expense-100"))
        }
    }

    @Test
    fun concurrentCorrectionsOfTheSameVersionHaveExactlyOneWinner() {
        P705Database.create("p705-concurrent-").use { harness ->
            harness.insertOrdinaryExpense("tx-expense-100", amountMinor = 10_000L)
            val first = P705Ids("p705-concurrent-a")
            val second = P705Ids("p705-concurrent-b")
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val requestIds = listOf(first.requestId(), second.requestId())
                val idSources = listOf(first.correctSource, second.correctSource)
                val futures =
                    listOf(0, 1).map { index ->
                        executor.submit<CorrectTransactionVersionResult> {
                            ready.countDown()
                            check(start.await(5, TimeUnit.SECONDS))
                            P705Database.open(harness.filePath).use { connection ->
                                ExecuteCorrectTransactionVersion(
                                    connection.correctionPort,
                                    idSources[index],
                                    P705Fixture.admissionReader,
                                ).execute(
                                    ExplicitlyConfirmedTransactionCorrection(
                                        ledgerId = ledgerId,
                                        requestId = requestIds[index],
                                        transactionId = TransactionId("tx-expense-100"),
                                        expectedCurrentVersionId = firstVersionId,
                                        note = "concurrent $index",
                                        statisticsAt = P705Fixture.marchStatistics,
                                        amount = Money.ofMinor(if (index == 0) 8_000L else 9_000L, P705Fixture.cny),
                                        categoryId = P705Fixture.food,
                                        fundingAccountId = P705Fixture.bankA,
                                        confirmation = ExplicitManualSave,
                                    ),
                                )
                            }
                        }
                    }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                start.countDown()
                val results = futures.map { it.get(20, TimeUnit.SECONDS) }
                assertEquals(1, results.count { it is CorrectTransactionVersionResult.Created })
                assertEquals(1, results.count { it == CorrectTransactionVersionResult.StaleCurrentVersion })
                assertEquals(2L, harness.versionCount("tx-expense-100"))
                assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_correction_receipt"))
                assertEquals(4L, harness.ledgerQueryCount("SELECT count(*) FROM posting"))
                // The loser left no request claim behind, so its identity stays retryable.
                assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_correction_request"))
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun correctionOnALedgerWithoutAnyReconciliationRowsCannotReachTheMatchedLegCode() {
        // V-08: a transfer-free fixture has zero reconciliation and evidence rows before and
        // after the correction, so the defensive P705_MATCHED_FUNDING_LEG_CHANGED path is dead.
        P705Database.create("p705-v08-").use { harness ->
            harness.insertOrdinaryExpense("tx-expense-100", amountMinor = 10_000L)
            val ids = P705Ids("p705-v08")
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM posting_reconciliation"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM evidence_link"))
            assertIs<CorrectTransactionVersionResult.Created>(correct(harness, ids, ids.requestId()))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM posting_reconciliation"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM evidence_link"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM evidence_projection"))
        }
    }
}
