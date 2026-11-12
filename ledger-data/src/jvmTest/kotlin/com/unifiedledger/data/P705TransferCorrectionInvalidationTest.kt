package com.unifiedledger.data

import com.unifiedledger.application.CorrectTransactionVersionResult
import com.unifiedledger.application.ExecuteCorrectTransactionVersion
import com.unifiedledger.application.ExecuteVoidTransaction
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.ExplicitlyConfirmedTransactionCorrection
import com.unifiedledger.application.SummarizeLedgerActivity
import com.unifiedledger.application.VoidTransactionRequest
import com.unifiedledger.application.VoidTransactionResult
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.P705FailureCode
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.VoidReason
import com.unifiedledger.domain.VoidReasonCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

internal class P705TransferCorrectionInvalidationTest : P705TransferCorrectionTestSupport() {
    /**
     * Atomicity of the composition (D-228 J3, same-transaction requirement): a correction whose
     * CAS loses must not leave its invalidation behind. The first, note-only correction
     * advances the version without touching legs; the second, leg-changing correction carries
     * the superseded CAS token, runs its invalidation, then rolls the whole transaction back -
     * the link stays active and no derived D-113 request survives.
     */
    @Test
    fun aStaleCorrectionRollsBackItsInvalidationWrites() {
        P705Database.create("p705-t-j3-stale-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-j3st", destinationCreditMinor = 10_000L, feeMinor = 200L)
            seedCatalogRows(harness)
            seedActiveLink(harness, "tx-transfer-j3st", "tx-transfer-j3st-posting-0", "link-j3st-src", "reconciliation-j3st-src")
            val ids = TransferIds("p705-t-j3-stale")

            // Version 2 via a note-only correction (reuse form: no invalidation, same postings).
            assertIs<CorrectTransactionVersionResult.Created>(
                correct(
                    harness,
                    ids,
                    ids.requestId(),
                    transactionId = "tx-transfer-j3st",
                    note = "first",
                    destinationCreditMinor = 10_000L,
                    feeMinor = 200L,
                    feeCategoryId = P705Fixture.food,
                ),
            )
            // The leg-changing retry carries the superseded version-1 CAS token.
            assertEquals(
                CorrectTransactionVersionResult.StaleCurrentVersion,
                correct(
                    harness,
                    ids,
                    ids.requestId(),
                    transactionId = "tx-transfer-j3st",
                    expectedVersionId = TransactionVersionId("tx-transfer-j3st-version-1"),
                    destinationCreditMinor = 8_000L,
                    feeMinor = 200L,
                    feeCategoryId = P705Fixture.food,
                ),
            )
            // No partial state: the match is still active, the reconciliation row still CHECKED,
            // the derived D-113 request rolled back with the transaction (only the seed request
            // survives), and the claim left no row.
            assertEquals(
                listOf("active"),
                harness.ledgerQueryTexts("SELECT state FROM evidence_link_history WHERE link_id = 'link-j3st-src' ORDER BY sequence"),
            )
            assertEquals("CHECKED", harness.ledgerQueryText("SELECT status FROM posting_reconciliation WHERE posting_id = 'tx-transfer-j3st-posting-0'"))
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM reconciliation_request"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM reconciliation_receipt"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM reconciliation_correction_snapshot"))
            assertEquals(2L, harness.versionCount("tx-transfer-j3st"))
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_correction_request"))
        }
    }

    /**
     * All-or-nothing of the composition (review finding F-1): when a later leg's invalidation
     * fails after an earlier leg's already succeeded, the rejection must roll the whole
     * transaction back - the earlier leg's match stays active and no derived D-113 row survives.
     * The deterministic mid-loop failure: both principal legs are affected (endpoint swap), and
     * the SECOND derived request id is pre-seeded as a PENDING reconciliation_request with a
     * foreign fingerprint, so the D-113 store resolves that claim as a snapshot mismatch and
     * rejects only after the first invalidation has been applied.
     */
    @Test
    fun aMidInvalidationFailureRollsBackEveryInvalidationWrite() {
        P705Database.create("p705-t-j3-atomic-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-j3at", destinationCreditMinor = 10_000L, feeMinor = 200L)
            seedCatalogRows(harness)
            seedActiveLink(harness, "tx-transfer-j3at", "tx-transfer-j3at-posting-0", "link-j3at-src", "reconciliation-j3at-src")
            seedActiveLink(harness, "tx-transfer-j3at", "tx-transfer-j3at-posting-1", "link-j3at-dst", "reconciliation-j3at-dst")
            val ids = TransferIds("p705-t-j3-atomic")
            val requestId = ids.requestId()
            harness.database.ledgerQueries.claimP408ReconciliationRequest(
                ledgerId.value,
                "${requestId.value}:invalidate:link-j3at-dst",
                "invalidate_link",
                "deliberately-foreign-fingerprint",
            )

            // The endpoint swap changes both principal tuples, so both links enter the loop in
            // posting_index order: the source leg's invalidation succeeds first, then the
            // destination leg's store call resolves the blocked claim and rejects.
            assertEquals(
                CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_CONSTRAINT_VIOLATION),
                correct(
                    harness,
                    ids,
                    requestId,
                    transactionId = "tx-transfer-j3at",
                    destinationCreditMinor = 10_000L,
                    feeMinor = 200L,
                    feeCategoryId = P705Fixture.food,
                    sourceAccountId = P705Fixture.bankB,
                    destinationAccountId = P705Fixture.bankA,
                ),
            )
            // All-or-nothing: the first link's invalidation rolled back with the transaction.
            assertEquals(1L, harness.versionCount("tx-transfer-j3at"))
            assertEquals(
                listOf("active"),
                harness.ledgerQueryTexts("SELECT state FROM evidence_link_history WHERE link_id = 'link-j3at-src' ORDER BY sequence"),
            )
            assertEquals(
                listOf("active"),
                harness.ledgerQueryTexts("SELECT state FROM evidence_link_history WHERE link_id = 'link-j3at-dst' ORDER BY sequence"),
            )
            assertEquals("CHECKED", harness.ledgerQueryText("SELECT status FROM posting_reconciliation WHERE posting_id = 'tx-transfer-j3at-posting-0'"))
            assertEquals("CHECKED", harness.ledgerQueryText("SELECT status FROM posting_reconciliation WHERE posting_id = 'tx-transfer-j3at-posting-1'"))
            // The derived request for the first leg rolled back too: only the two seed requests
            // and the pre-seeded blocker remain, and neither a receipt nor a snapshot exists.
            assertEquals(3L, harness.ledgerQueryCount("SELECT count(*) FROM reconciliation_request"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM reconciliation_receipt"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM reconciliation_correction_snapshot"))
            // The rolled-back correction leaves no claim row behind: the identity is retryable.
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_correction_request"))
        }
    }

    /**
     * Behavior-matrix row A (D-228 J3 rendering (i)/(iii)): a changed funding leg that carries
     * an active evidence link has that link invalidated through the reused D-113 store inside
     * the correction transaction - the correction itself succeeds. The invalidation is
     * invalidation-only (`posting_replaced`, `MISSING`, no successor), and unchanged legs - the
     * destination principal leg here, plus the non-real FEE leg, which never participates -
     * keep their rows byte for byte. The active link is a fixture the product path cannot
     * produce yet (rendering (iii): no live trigger point, coverage by explicit construction).
     */
    @Test
    fun changedLegWithAnActiveMatchIsInvalidatedAndTheCorrectionSucceeds() {
        P705Database.create("p705-t-j3-a-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-j3a", destinationCreditMinor = 10_000L, feeMinor = 200L)
            seedCatalogRows(harness)
            seedActiveLink(harness, "tx-transfer-j3a", "tx-transfer-j3a-posting-0", "link-j3a-src", "reconciliation-j3a-src")
            seedActiveLink(harness, "tx-transfer-j3a", "tx-transfer-j3a-posting-1", "link-j3a-dst", "reconciliation-j3a-dst")
            seedPendingPlaceholder(harness, "tx-transfer-j3a-posting-2", "reconciliation-j3a-fee")
            val ids = TransferIds("p705-t-j3a")
            val requestId = ids.requestId()

            // Only the fee moves (200 -> 300): the source principal leg's tuple changes (its
            // derived debit moves) while the destination principal leg and - being non-real -
            // the FEE leg's tuple stay out of the affected set.
            assertIs<CorrectTransactionVersionResult.Created>(
                correct(
                    harness,
                    ids,
                    requestId,
                    transactionId = "tx-transfer-j3a",
                    destinationCreditMinor = 10_000L,
                    feeMinor = 300L,
                    feeCategoryId = P705Fixture.food,
                ),
            )
            assertEquals(2L, harness.versionCount("tx-transfer-j3a"))
            // The source leg's match was invalidated: history moves to invalidated/posting_replaced
            // and the posting's reconciliation advances to MISSING with a link-less history row.
            assertEquals(
                listOf("active", "invalidated"),
                harness.ledgerQueryTexts("SELECT state FROM evidence_link_history WHERE link_id = 'link-j3a-src' ORDER BY sequence"),
            )
            assertEquals(
                listOf("confirmed", "posting_replaced"),
                harness.ledgerQueryTexts("SELECT reason FROM evidence_link_history WHERE link_id = 'link-j3a-src' ORDER BY sequence"),
            )
            assertEquals("MISSING", harness.ledgerQueryText("SELECT status FROM posting_reconciliation WHERE posting_id = 'tx-transfer-j3a-posting-0'"))
            assertEquals(
                listOf("PENDING", "CHECKED", "MISSING"),
                harness.ledgerQueryTexts("SELECT status FROM posting_reconciliation_history WHERE reconciliation_id = 'reconciliation-j3a-src' ORDER BY sequence"),
            )
            assertEquals(
                listOf(-1L, 1L, -1L),
                harness.ledgerQueryLongs(
                    "SELECT CASE WHEN evidence_link_id IS NULL THEN -1 ELSE 1 END FROM posting_reconciliation_history WHERE reconciliation_id = 'reconciliation-j3a-src' ORDER BY sequence",
                ),
            )
            // The derived D-113 request committed as one more reconciliation request/receipt pair.
            assertEquals(
                "ACCEPTED",
                harness.ledgerQueryText("SELECT outcome FROM reconciliation_request WHERE request_id = '${requestId.value}:invalidate:link-j3a-src'"),
            )
            assertEquals(
                1L,
                harness.ledgerQueryCount("SELECT count(*) FROM reconciliation_receipt WHERE request_id = '${requestId.value}:invalidate:link-j3a-src'"),
            )
            assertEquals(
                "posting_replaced",
                harness.ledgerQueryText("SELECT reason FROM reconciliation_correction_snapshot WHERE previous_link_id = 'link-j3a-src'"),
            )
            // Unchanged legs: the destination's active link and CHECKED row, and the FEE leg's
            // placeholder, are all value-identical; no second invalidation request exists.
            assertEquals(
                listOf("active"),
                harness.ledgerQueryTexts("SELECT state FROM evidence_link_history WHERE link_id = 'link-j3a-dst' ORDER BY sequence"),
            )
            assertEquals("CHECKED", harness.ledgerQueryText("SELECT status FROM posting_reconciliation WHERE posting_id = 'tx-transfer-j3a-posting-1'"))
            assertEquals("PENDING", harness.ledgerQueryText("SELECT status FROM posting_reconciliation WHERE posting_id = 'tx-transfer-j3a-posting-2'"))
            assertEquals(
                4L,
                harness.ledgerQueryCount("SELECT count(*) FROM reconciliation_request"),
            )
        }
    }

    /**
     * Behavior-matrix row B (D-228 J3 rendering (ii), T-12's negative pin): a genuinely changed
     * funding leg that carries only a PENDING placeholder (evidence_link_id NULL) has no
     * invalidation object - the correction succeeds, is never rejected, never reports
     * P408_INVALIDATE_LINK_NOT_ACTIVE, and the placeholder row survives verbatim.
     */
    @Test
    fun changedLegWithOnlyAPendingPlaceholderIsNeverRejectedAndKeepsTheRow() {
        P705Database.create("p705-t-j3-b-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-j3b", destinationCreditMinor = 10_000L, feeMinor = 200L)
            seedCatalogRows(harness)
            seedPendingPlaceholder(harness, "tx-transfer-j3b-posting-0", "reconciliation-j3b-src")
            val ids = TransferIds("p705-t-j3b")

            val created =
                assertIs<CorrectTransactionVersionResult.Created>(
                    correct(harness, ids, ids.requestId(), transactionId = "tx-transfer-j3b", destinationCreditMinor = 8_000L, feeMinor = 200L, feeCategoryId = P705Fixture.food),
                )
            // The correction succeeded and rebound the posting set...
            assertEquals(2L, harness.versionCount("tx-transfer-j3b"))
            assertEquals(created.receipt.versionId.value, harness.currentVersionId("tx-transfer-j3b"))
            // ...and the placeholder on the replaced old posting is untouched, with no
            // invalidation side effects anywhere: no linked history row, no derived D-113
            // request beyond the seed, no receipt, no correction snapshot, no evidence link.
            assertEquals(
                listOf("PENDING"),
                harness.ledgerQueryTexts("SELECT status FROM posting_reconciliation_history WHERE reconciliation_id = 'reconciliation-j3b-src' ORDER BY sequence"),
            )
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM posting_reconciliation_history WHERE evidence_link_id IS NOT NULL"))
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM reconciliation_request"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM reconciliation_receipt"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM reconciliation_correction_snapshot"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM evidence_link"))
        }
    }

    /**
     * The composition's fail-closed gate: a port built by the platform factory carries no
     * D-113 store (its driverless construction is impossible), so a correction that would need
     * an invalidation is the constraint backstop - never a silent pass-through that leaves the
     * stale match alive, and never a partial write.
     */
    @Test
    fun aPlatformConfiguredPortWithoutTheInvalidationStoreFailsClosedOnAnActiveMatch() {
        P705Database.create("p705-t-j3-platform-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-j3p", destinationCreditMinor = 10_000L, feeMinor = 200L)
            seedCatalogRows(harness)
            seedActiveLink(harness, "tx-transfer-j3p", "tx-transfer-j3p-posting-0", "link-j3p-src", "reconciliation-j3p-src")
            val ids = TransferIds("p705-t-j3-platform")
            val platformPort = SqlDelightTransactionCorrectionCommitPort.forPlatformConfiguredDatabase(harness.database)

            assertEquals(
                CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_CONSTRAINT_VIOLATION),
                ExecuteCorrectTransactionVersion(platformPort, ids.correctSource, P705Fixture.admissionReader)
                    .execute(
                        ExplicitlyConfirmedTransactionCorrection(
                            ledgerId = ledgerId,
                            requestId = ids.requestId(),
                            transactionId = TransactionId("tx-transfer-j3p"),
                            expectedCurrentVersionId = TransactionVersionId("tx-transfer-j3p-version-1"),
                            note = "platform",
                            statisticsAt = P705Fixture.marchStatistics,
                            amount = Money.ofMinor(8_000L, P705Fixture.cny),
                            categoryId = P705Fixture.food,
                            fundingAccountId = P705Fixture.bankA,
                            destinationAccountId = P705Fixture.bankB,
                            feeMinor = 200L,
                            confirmation = ExplicitManualSave,
                        ),
                    ),
            )
            // Zero-write: the version was not appended, the match is still active and the claim
            // left no request row behind.
            assertEquals(1L, harness.versionCount("tx-transfer-j3p"))
            assertEquals(listOf("active"), harness.ledgerQueryTexts("SELECT state FROM evidence_link_history WHERE link_id = 'link-j3p-src' ORDER BY sequence"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_correction_request"))
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM reconciliation_request"))
        }
    }

    /**
     * T-14 (transfer spec section 10 `:282`; D-228 J2): an import-created two-leg
     * `ACCOUNT_TRANSFER` keeps its creation lineage OPEN, so a row-shape-valid note-only
     * correction (destination present, zero fee, no fee category, current CAS token) is rejected
     * by the lineage gate with zero writes. Without that gate the claim would succeed, the
     * row-shape gate would pass and the CAS copy would append version 2, a correction request and
     * a receipt; the baseline liveness below proves the lineage really is what rejects it.
     */
    @Test
    fun anImportCreatedTransferCorrectionIsALineageRejectionWithZeroWrites() {
        P705Database.create("p705-t-import-lineage-").use { harness ->
            harness.insertImportCreatedTransfer("tx-import-transfer")
            val ids = TransferIds("p705-t-import-lineage")
            val versionCountBefore = harness.versionCount("tx-import-transfer")
            val currentVersionIdBefore = harness.currentVersionId("tx-import-transfer")
            val requestCountBefore = harness.ledgerQueryCount("SELECT count(*) FROM transaction_correction_request")
            val receiptCountBefore = harness.ledgerQueryCount("SELECT count(*) FROM transaction_correction_receipt")
            val postingCountBefore = harness.ledgerQueryCount("SELECT count(*) FROM posting")
            // Non-degenerate baseline: a version-1 two-leg transfer whose creation lineage exists.
            assertEquals(1L, versionCountBefore)
            assertEquals(2L, postingCountBefore)
            assertEquals(
                1L,
                harness.ledgerQueryCount(
                    "SELECT count(*) FROM import_confirmation " +
                        "WHERE transaction_id = 'tx-import-transfer' AND operation_class = 'creation'",
                ),
            )

            assertEquals(
                CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_CREATION_LINEAGE_NOT_SUPPORTED),
                correct(
                    harness,
                    ids,
                    ids.requestId(),
                    transactionId = "tx-import-transfer",
                    destinationCreditMinor = 10_000L,
                    feeMinor = 0L,
                    feeCategoryId = null,
                ),
            )

            assertEquals(versionCountBefore, harness.versionCount("tx-import-transfer"))
            assertEquals(currentVersionIdBefore, harness.currentVersionId("tx-import-transfer"))
            assertEquals(0L, requestCountBefore)
            assertEquals(requestCountBefore, harness.ledgerQueryCount("SELECT count(*) FROM transaction_correction_request"))
            assertEquals(0L, receiptCountBefore)
            assertEquals(receiptCountBefore, harness.ledgerQueryCount("SELECT count(*) FROM transaction_correction_receipt"))
            assertEquals(postingCountBefore, harness.ledgerQueryCount("SELECT count(*) FROM posting"))
        }
    }

    @Test
    fun voidedTransferLeavesTheEffectiveSurfaceAndCannotBeCorrected() {
        P705Database.create("p705-t-voided-").use { harness ->
            // A second, unrelated ordinary expense keeps the ledger's activity currency and the
            // monthly projection non-degenerate, so the post-void zero assertions below cannot
            // pass merely because the whole read side collapsed to an empty ledger.
            harness.insertOrdinaryExpense("tx-expense-bystander", amountMinor = 5_000L)
            insertManualTransfer(harness, "tx-transfer-voided", destinationCreditMinor = 10_000L, feeMinor = 200L)

            // Baseline BEFORE the void (T-06 non-empty positive control): each surface the vector
            // later asserts must be observably non-degenerate with the transfer present.
            assertEquals(2, harness.readAdapter.loadLedgerEntryRows(ledgerId).size)
            val beforeBalances = state(harness).balances.associate { it.accountId.value to it.ledgerSignedMinorUnits }
            // The bystander expense also debits bankA (5_000) and credits the expense posting
            // account (5_000), so each baseline carries both transactions' effects.
            assertEquals(-15_200L, beforeBalances.getValue(P705Fixture.bankA.value))
            assertEquals(10_000L, beforeBalances.getValue(P705Fixture.bankB.value))
            assertEquals(5_200L, beforeBalances.getValue(P705Fixture.expenseAccount.value))
            val beforeSummary = SummarizeLedgerActivity(catalog).summarize(state(harness))
            assertEquals(2, beforeSummary.totalTransactionCount)
            assertEquals(1, beforeSummary.countByKind.getValue(TransactionKind.ACCOUNT_TRANSFER))
            val marchStart = requireNotNull(StatisticsAtProjection.project(Instant.parse("2026-03-01T00:00:00Z")))
            val aprilStart = requireNotNull(StatisticsAtProjection.project(Instant.parse("2026-04-01T00:00:00Z")))
            val projection = SqlDelightStatisticsAtProjectionReadPort(harness.database)
            assertEquals(
                listOf("tx-expense-bystander-version-1", "tx-transfer-voided-version-1").sorted(),
                projection
                    .projectedVersionsInWindow(ledgerId, marchStart, aprilStart)
                    .map { it.versionId.value }
                    .sorted(),
            )
            assertEquals(2, marchActivity(harness).currencies.single().transactionCount)

            val voidIds = P705Ids("p705-t-voided-v")
            assertIs<VoidTransactionResult.Created>(
                ExecuteVoidTransaction(harness.voidPort, voidIds.voidSource, fixedClock(P705Fixture.voidedAt))
                    .execute(
                        VoidTransactionRequest(
                            ledgerId = ledgerId,
                            requestId = voidIds.requestId(),
                            transactionId = TransactionId("tx-transfer-voided"),
                            reason = VoidReason(VoidReasonCode.MIS_ENTERED, "wrong account"),
                            confirmation = ExplicitManualSave,
                        ),
                    ),
            )
            // The transfer leaves the effective entry surface transaction-granularly (J1
            // candidate A): the version rows stay while every surface asserted below drops the
            // whole chain, and the bystander expense keeps the ledger itself non-degenerate.
            // Faces N2-N5 (the monthly-contribution, widget, and tag/merchant adapters) are NOT
            // asserted here; this vector covers #1-#5, N1, N10 and the monthly use case.
            assertEquals(1L, harness.versionCount("tx-transfer-voided"))
            // Faces #1-#3: the input row set.
            assertEquals(listOf("tx-expense-bystander"), harness.readAdapter.loadLedgerEntryRows(ledgerId).map { it.transactionId.value })
            // Face #4: HOME balances - both principal legs leave and the fee leg leaves ordinary
            // expense, leaving only the bystander's effects (bankA -5_000, expense 5_000).
            val afterBalances = state(harness).balances.associate { it.accountId.value to it.ledgerSignedMinorUnits }
            assertEquals(-5_000L, afterBalances.getValue(P705Fixture.bankA.value))
            assertNull(afterBalances[P705Fixture.bankB.value])
            assertEquals(5_000L, afterBalances.getValue(P705Fixture.expenseAccount.value))
            // Face #5: the activity summary drops the transfer and its kind count.
            val afterSummary = SummarizeLedgerActivity(catalog).summarize(state(harness))
            assertEquals(1, afterSummary.totalTransactionCount)
            assertEquals(0, afterSummary.countByKind[TransactionKind.ACCOUNT_TRANSFER] ?: 0)
            // Face N1: the statistics projection window drops the voided version. Sorted on both
            // sides so the comparison never depends on the query's row order.
            assertEquals(
                listOf("tx-expense-bystander-version-1"),
                projection
                    .projectedVersionsInWindow(ledgerId, marchStart, aprilStart)
                    .map { it.versionId.value }
                    .sorted(),
            )
            // The monthly surface also drops the transfer (count and kind count) while keeping the
            // bystander's currency row alive.
            val afterActivity = marchActivity(harness)
            val monthCurrency = afterActivity.currencies.single()
            assertEquals(1, monthCurrency.transactionCount)
            assertEquals(0, monthCurrency.countByKind[TransactionKind.ACCOUNT_TRANSFER] ?: 0)
            // Face N10: the DP-13 refund probe is structurally insensitive to voiding a transfer
            // (spec section 5.2, DP-T5): its effective predicate joins on the refund
            // transaction's confirmation, while the filter is the voided original. The assertion
            // records the frozen no-op invariant and is deliberately NOT counted as T-06
            // red-able coverage; the liveness control below proves the probe is wired.
            assertEquals(false, harness.readAdapter.hasEffectiveRefundLink(ledgerId, TransactionId("tx-transfer-voided")))
            // Liveness positive control: the same probe DOES answer true for a real
            // product-linked refund, so a broken join would red here.
            harness.insertOrdinaryExpense("tx-expense-refunded", amountMinor = 1_000L)
            harness.insertOrdinaryExpense("tx-refund-linked", amountMinor = 400L)
            harness.insertProductLinkedRefund(
                originalTransactionId = "tx-expense-refunded",
                refundTransactionId = "tx-refund-linked",
            )
            assertEquals(true, harness.readAdapter.hasEffectiveRefundLink(ledgerId, TransactionId("tx-expense-refunded")))
            // A correction of the voided transfer is a typed zero-write rejection.
            val ids = TransferIds("p705-t-voided")
            assertEquals(
                CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_TRANSACTION_VOIDED),
                correct(harness, ids, ids.requestId(), transactionId = "tx-transfer-voided", destinationCreditMinor = 8_000L, feeMinor = 200L, feeCategoryId = P705Fixture.food),
            )
            assertEquals(1L, harness.versionCount("tx-transfer-voided"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_correction_request"))
        }
    }

    @Test
    fun replayConflictsAndStaleCasBehaveLikeTheOrdinaryFamily() {
        P705Database.create("p705-t-replay-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-replay", destinationCreditMinor = 10_000L, feeMinor = 200L)
            val ids = TransferIds("p705-t-replay")
            val requestId = ids.requestId()

            val created =
                assertIs<CorrectTransactionVersionResult.Created>(
                    correct(harness, ids, requestId, transactionId = "tx-transfer-replay", destinationCreditMinor = 8_000L, feeMinor = 200L, feeCategoryId = P705Fixture.food),
                )
            // T-13: the same request id with the same snapshot replays onto the original
            // receipt, zero new entities. The replay passes the superseded CAS token
            // `tx-transfer-replay-version-1` explicitly, the same value the helper would default
            // to; the successful correction above already advanced the current version past it.
            // A CAS-first implementation would therefore answer `StaleCurrentVersion`; the
            // observed `NoChange(created.receipt)` proves the replay judgment runs before any CAS
            // evaluation (CorrectTransactionVersion.kt:205-211).
            assertEquals(
                CorrectTransactionVersionResult.NoChange(created.receipt),
                correct(
                    harness,
                    ids,
                    requestId,
                    transactionId = "tx-transfer-replay",
                    expectedVersionId = TransactionVersionId("tx-transfer-replay-version-1"),
                    destinationCreditMinor = 8_000L,
                    feeMinor = 200L,
                    feeCategoryId = P705Fixture.food,
                ),
            )
            assertEquals(2L, harness.versionCount("tx-transfer-replay"))
            // The superseded token the replay carried really was superseded: the current-version
            // pointer has advanced to the receipt's version, so the assertion above is not
            // passing merely because the token was still current.
            assertEquals(created.receipt.versionId.value, harness.currentVersionId("tx-transfer-replay"))
            // The same request id with a different snapshot is an identity conflict, zero writes.
            assertIs<CorrectTransactionVersionResult.RequestIdentityConflict>(
                correct(harness, ids, requestId, transactionId = "tx-transfer-replay", note = "different", destinationCreditMinor = 8_000L, feeMinor = 200L, feeCategoryId = P705Fixture.food),
            )
            assertEquals(2L, harness.versionCount("tx-transfer-replay"))
            // A fresh request id carrying the superseded CAS token is stale, zero writes.
            assertEquals(
                CorrectTransactionVersionResult.StaleCurrentVersion,
                correct(
                    harness,
                    ids,
                    ids.requestId(),
                    transactionId = "tx-transfer-replay",
                    expectedVersionId = TransactionVersionId("tx-transfer-replay-version-1"),
                    destinationCreditMinor = 6_000L,
                    feeMinor = 200L,
                    feeCategoryId = P705Fixture.food,
                ),
            )
            assertEquals(2L, harness.versionCount("tx-transfer-replay"))
            // Every rejected attempt above left the request journal at exactly one row: no
            // rejection wrote a request.
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_correction_request"))
            assertEquals(
                listOf(P705Fixture.bankA.value to -8_200L, P705Fixture.bankB.value to 8_000L, P705Fixture.expenseAccount.value to 200L),
                harness.currentPostings("tx-transfer-replay"),
            )
        }
    }
}
