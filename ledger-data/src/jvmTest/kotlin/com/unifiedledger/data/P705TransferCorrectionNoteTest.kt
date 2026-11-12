package com.unifiedledger.data

import com.unifiedledger.application.CorrectTransactionVersionResult
import com.unifiedledger.application.SummarizeLedgerActivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class P705TransferCorrectionNoteTest : P705TransferCorrectionTestSupport() {

    /**
     * T-02 on its own terms (spec section 10): a two-leg transfer (fee == 0) whose note is
     * corrected keeps the posting set, adds no cash flow and carries no fee category. The
     * three-leg note vector above cannot show the last two parts, because a categorised fee leg
     * is expected there.
     */
    @Test
    fun aTwoLegNoteCorrectionKeepsThePostingSetAndAddsNoCashFlow() {
        P705Database.create("p705-t-note-twoleg-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-note2", destinationCreditMinor = 10_000L, feeMinor = 0L)
            val ids = TransferIds("p705-t-note2")
            val requestId = ids.requestId()
            // fee == 0 seeds exactly the two principal legs, so the before/after leg comparison
            // below is over an already two-leg set.
            val beforePostings = harness.currentPostings("tx-transfer-note2")
            assertEquals(
                listOf(P705Fixture.bankA.value to -10_000L, P705Fixture.bankB.value to 10_000L),
                beforePostings,
            )

            val created =
                assertIs<CorrectTransactionVersionResult.Created>(
                    correct(
                        harness,
                        ids,
                        requestId,
                        transactionId = "tx-transfer-note2",
                        note = "renamed two-leg transfer",
                        destinationCreditMinor = 10_000L,
                        feeMinor = 0L,
                        feeCategoryId = null,
                    ),
                )
            assertEquals(2L, harness.versionCount("tx-transfer-note2"))
            // Only the note changed: the unchanged leg multiset reuses the current posting set
            // (CorrectTransactionVersion.kt:165-168), so no posting identity is rebound.
            assertEquals(
                "tx-transfer-note2-posting-set-1",
                harness.ledgerQueryText("SELECT posting_set_id FROM transaction_version WHERE version_id = '${created.receipt.versionId.value}'"),
            )
            assertEquals("renamed two-leg transfer", harness.ledgerQueryText("SELECT note FROM transaction_version WHERE version_id = '${created.receipt.versionId.value}'"))
            assertEquals(beforePostings, harness.currentPostings("tx-transfer-note2"))
            // No leg was added and none was moved.
            assertEquals(2L, harness.ledgerQueryCount("SELECT count(*) FROM posting"))
            // The committed request row must exist AND store no category. Both are needed:
            // `ledgerQueryTextOrNull` answers null for a missing row exactly as it does for a
            // NULL value (P705Harness.kt:235-246), so without the row-count step "the category
            // was not carried" and "the row was never written" would be indistinguishable.
            // The predicate interpolates `requestId.value`, never the `RequestId` itself, which
            // would render as its data-class `toString()` and match no row.
            assertEquals(
                1L,
                harness.ledgerQueryCount("SELECT count(*) FROM transaction_correction_request WHERE request_id = '${requestId.value}'"),
            )
            assertNull(
                harness.ledgerQueryTextOrNull(
                    "SELECT category_id FROM transaction_correction_request WHERE request_id = '${requestId.value}'",
                ),
            )
            // Zero new cash flow: a two-leg transfer posts no EXPENSE/INCOME leg, so the summary
            // derives no currency row at all (SummarizeLedgerActivity.kt:84-95).
            val summary = SummarizeLedgerActivity(catalog).summarize(state(harness))
            assertEquals(1, summary.totalTransactionCount)
            assertTrue(summary.totalsByCurrency.isEmpty())
        }
    }

    /**
     * Transfer spec section 7.4, correction side, restricted to the owners the spec marks as
     * unconditionally zero-write: the lending principal history (DP-4), the import owner (DP-5)
     * and `evidence_projection`. This vector performs a note-only correction, so the posting set
     * is reused and no funding leg changes.
     *
     * `evidence_projection` belongs to the spec's reconciliation-owner row, whose correction-side
     * writes are "only the J3-decided surface", so asserting it unchanged needs its own
     * justification rather than the blanket DP-4/DP-5 one. The port only ever composes D-113
     * invalidation with `resultState = MISSING` - the CHECKED successor re-proof is a later
     * slice - and the projection authority branch is reached only for CHECKED, so a transfer
     * correction writes no projection even when a leg changes. `posting_reconciliation*` and
     * `evidence_link*` stay out because invalidation legitimately touches them on a changed leg,
     * and the DP-10 matrix vectors above pin that.
     *
     * This is NOT the spec's T-11 row either: T-11 is worded for the void/restore path. The
     * transfer void/restore owner leg remains unasserted and stays OPEN.
     */
    @Test
    fun aNoteOnlyTransferCorrectionWritesNoneOfTheLendingImportOrProjectionOwners() {
        P705Database.create("p705-t-owner-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-owner", destinationCreditMinor = 10_000L, feeMinor = 200L)
            seedCatalogRows(harness)
            // Non-empty baselines: an all-zero start would make every comparison red-able only
            // from zero to zero. The import spine here hangs off an unrelated bystander, never
            // off the corrected transfer, so the creation-lineage gate is not what rejects us.
            harness.insertOrdinaryExpense("tx-expense-owner-bystander", amountMinor = 5_000L)
            harness.insertImportCreationConfirmation("tx-expense-owner-bystander")
            // `lending_position` FKs both `counterparty` and `catalog_account`, so the
            // counterparty row must exist too - seeding only the position would leave an
            // FK-invalid row that lands solely because this harness does not enforce FKs.
            harness.database.ledgerQueries.insertCounterparty(
                ledgerId.value,
                "counterparty-owner",
                "counterparty-owner-name",
                P705Fixture.bankA.value,
                1L,
            )
            harness.database.ledgerQueries.insertLendingPosition(
                ledger_id = ledgerId.value,
                counterparty_id = "counterparty-owner",
                receivable_account_id = P705Fixture.bankA.value,
                currency_code = P705Fixture.cny.code,
                currency_precision = P705Fixture.cny.precision.toLong(),
                principal_balance_minor = 7_000L,
            )
            harness.database.ledgerQueries.insertLendingPositionHistory(
                ledger_id = ledgerId.value,
                counterparty_id = "counterparty-owner",
                entry_id = "entry-owner",
                behavior_code = "LEND",
                amount_minor = 7_000L,
                principal_balance_after_minor = 7_000L,
                transaction_id = "tx-expense-owner-bystander",
                occurred_at = P705Fixture.marchStatistics.toString(),
            )
            harness.database.ledgerQueries.insertEvidenceProjection(
                ledger_id = ledgerId.value,
                projection_id = "projection-owner",
                evidence_id = "evidence-p705-expense-owner-bystander",
                source_id = "source-p705-expense-owner-bystander",
                source_hash = "content-hash-p705",
                target_account_id = P705Fixture.bankA.value,
                currency_code = P705Fixture.cny.code,
                currency_precision = P705Fixture.cny.precision.toLong(),
                raw_amount_minor = 5_000L,
                raw_currency_precision = P705Fixture.cny.precision.toLong(),
                normalized_amount_minor = 5_000L,
                direction_token = "out",
                state = "READY",
                rejection_code = null,
                rule_id = "rule-owner",
                rule_version = 1L,
                materialization_request_id = "materialization-owner",
                materialized_at = P705Fixture.marchStatistics.toString(),
            )
            harness.database.ledgerQueries.insertImportReceipt(
                ledgerId.value,
                "request-p705-expense-owner-bystander",
                "accepted",
                "source-p705-expense-owner-bystander",
                "evidence-p705-expense-owner-bystander",
                "candidate-p705-expense-owner-bystander",
                "confirmation-p705-expense-owner-bystander",
                "tx-expense-owner-bystander",
            )
            val ownerTables =
                listOf(
                    "evidence_projection",
                    "lending_position",
                    "lending_position_history",
                    "import_confirmation",
                    "import_receipt",
                    "import_candidate_status_history",
                )
            // The harness does not enforce foreign keys, so an FK-invalid seed would land
            // silently and the zero-write comparison below would be built on a broken baseline.
            // Assert the whole ledger's seed set is FK-valid before comparing.
            assertEquals(
                emptyList<String>(),
                harness.ledgerQueryTexts("SELECT \"table\" FROM pragma_foreign_key_check"),
            )
            val before = ownerTables.associateWith { table -> ownerRowsOf(harness, table) }
            // Non-degeneracy guard: every owner table really does hold rows, so the equality
            // below compares content rather than two empty lists.
            for (table in ownerTables) {
                assertTrue(before.getValue(table).isNotEmpty(), "$table must be non-empty before the correction")
            }

            val ids = TransferIds("p705-t-owner")
            assertIs<CorrectTransactionVersionResult.Created>(
                correct(
                    harness,
                    ids,
                    ids.requestId(),
                    transactionId = "tx-transfer-owner",
                    note = "renamed",
                    destinationCreditMinor = 10_000L,
                    feeMinor = 200L,
                    feeCategoryId = P705Fixture.food,
                ),
            )
            assertEquals(2L, harness.versionCount("tx-transfer-owner"))
            // Row-for-row equality over every column of every row, read as stored text through a
            // multi-column mapper. This detects added, removed and changed rows; it does not
            // distinguish an INTEGER-to-TEXT storage-class change of an unchanged value, which a
            // zero-write question does not need.
            assertEquals(before, ownerTables.associateWith { table -> ownerRowsOf(harness, table) })
        }
    }

    /**
     * Behavior-matrix row C (D-228 J3 rendering (i)/(iv), T-12): a note-only correction is
     * never rejected and never touches reconciliation or evidence rows - neither a PENDING
     * placeholder nor a live active link is a rejection reason when no funding leg changed.
     */
    @Test
    fun noteOnlyCorrectionKeepsSeededReconciliationRowsAndLinksVerbatim() {
        P705Database.create("p705-t-j3-c-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-j3c", destinationCreditMinor = 10_000L, feeMinor = 200L)
            seedCatalogRows(harness)
            seedPendingPlaceholder(harness, "tx-transfer-j3c-posting-0", "reconciliation-j3c-src")
            seedActiveLink(harness, "tx-transfer-j3c", "tx-transfer-j3c-posting-1", "link-j3c-dst", "reconciliation-j3c-dst")
            val ids = TransferIds("p705-t-j3c")

            assertIs<CorrectTransactionVersionResult.Created>(
                correct(
                    harness,
                    ids,
                    ids.requestId(),
                    transactionId = "tx-transfer-j3c",
                    note = "note only",
                    destinationCreditMinor = 10_000L,
                    feeMinor = 200L,
                    feeCategoryId = P705Fixture.food,
                ),
            )
            // The reuse write form kept the posting set, so no funding leg changed: the
            // placeholder row and the active link stay value-identical, and the D-113 request
            // family was never entered (no derived request, receipt or correction snapshot).
            assertEquals(
                listOf("PENDING"),
                harness.ledgerQueryTexts("SELECT status FROM posting_reconciliation_history WHERE reconciliation_id = 'reconciliation-j3c-src' ORDER BY sequence"),
            )
            assertEquals("PENDING", harness.ledgerQueryText("SELECT status FROM posting_reconciliation WHERE reconciliation_id = 'reconciliation-j3c-src'"))
            assertEquals(
                listOf("active"),
                harness.ledgerQueryTexts("SELECT state FROM evidence_link_history WHERE link_id = 'link-j3c-dst' ORDER BY sequence"),
            )
            assertEquals("CHECKED", harness.ledgerQueryText("SELECT status FROM posting_reconciliation WHERE reconciliation_id = 'reconciliation-j3c-dst'"))
            assertEquals(2L, harness.ledgerQueryCount("SELECT count(*) FROM reconciliation_request"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM reconciliation_receipt"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM reconciliation_correction_snapshot"))
        }
    }

    @Test
    fun noteOnlyTransferCorrectionReusesThePostingSet() {
        P705Database.create("p705-t-note-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-note", destinationCreditMinor = 10_000L, feeMinor = 200L)
            val ids = TransferIds("p705-t-note")

            val created =
                assertIs<CorrectTransactionVersionResult.Created>(
                    correct(
                        harness,
                        ids,
                        ids.requestId(),
                        transactionId = "tx-transfer-note",
                        note = "renamed transfer",
                        destinationCreditMinor = 10_000L,
                        feeMinor = 200L,
                        feeCategoryId = P705Fixture.food,
                    ),
                )
            assertEquals(2L, harness.versionCount("tx-transfer-note"))
            assertEquals(created.receipt.versionId.value, harness.currentVersionId("tx-transfer-note"))
            // Only the note changed, so the appended version keeps the current posting set - no
            // posting identity is rebound and no posting row is added. (This seed is the
            // three-leg fee>0 shape; the spec's T-02 row is the two-leg fee==0 case, pinned
            // separately below.)
            assertEquals(
                "tx-transfer-note-posting-set-1",
                harness.ledgerQueryText("SELECT posting_set_id FROM transaction_version WHERE version_id = '${created.receipt.versionId.value}'"),
            )
            assertEquals(
                "renamed transfer",
                harness.ledgerQueryText("SELECT note FROM transaction_version WHERE version_id = '${created.receipt.versionId.value}'"),
            )
            assertEquals(
                listOf(P705Fixture.bankA.value to -10_200L, P705Fixture.bankB.value to 10_000L, P705Fixture.expenseAccount.value to 200L),
                harness.currentPostings("tx-transfer-note"),
            )
            assertEquals(3L, harness.ledgerQueryCount("SELECT count(*) FROM posting"))
        }
    }
}
