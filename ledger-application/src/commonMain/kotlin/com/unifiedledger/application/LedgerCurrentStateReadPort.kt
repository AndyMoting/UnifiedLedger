package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlin.time.Instant

/**
 * P5-03 read boundary (D-119 section 4; plan section 3.2.3).
 *
 * The data adapter returns only ledger-scoped, current-version rows and the persisted
 * request/snapshot/receipt relationships with ledger-signed minor units; the application
 * layer owns ownership/kind/currency validation and the normal-balance display sign.
 */
data class CurrentVersionRow(
    val transactionId: TransactionId,
    val currentVersionId: TransactionVersionId,
    val kind: TransactionKind,
    val occurredAt: Instant,
    val postings: List<Posting>,
)

data class ManualExpenseCommitRecord(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val snapshot: ManualExpenseRequestSnapshot,
    val receipt: ConfirmedExpenseReceipt,
    val currentVersionId: TransactionVersionId,
)

/**
 * P7-02.A S-1 income analogue of [ManualExpenseCommitRecord]: the persisted request/snapshot/
 * receipt relationship plus the transaction's current version id.
 */
data class ManualIncomeCommitRecord(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val snapshot: ManualIncomeRequestSnapshot,
    val receipt: ConfirmedIncomeReceipt,
    val currentVersionId: TransactionVersionId,
)

/**
 * P7-05.B/C commit record behind the snapshot-aware unknown-commit resolvers: the persisted
 * request snapshot plus its receipt, keyed by request identity.
 */
data class TransactionCorrectionCommitRecord(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val snapshot: TransactionCorrectionRequestSnapshot,
    val receipt: TransactionCorrectionReceipt,
)

data class TransactionVoidCommitRecord(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val snapshot: TransactionVoidRequestSnapshot,
    val receipt: TransactionVoidReceipt,
)

interface LedgerCurrentStateReadPort {
    fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow>

    fun findManualExpenseByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualExpenseCommitRecord?

    fun findManualExpenseByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedExpenseReceipt,
    ): ManualExpenseCommitRecord?

    /** P7-02.A S-1: income request lookup for the snapshot-aware commit-status resolver. */
    fun findManualIncomeByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualIncomeCommitRecord?

    /** P7-02.A S-1: income receipt lookup for the snapshot-aware commit-status resolver. */
    fun findManualIncomeByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedIncomeReceipt,
    ): ManualIncomeCommitRecord?

    /** P7-02.B: transfer request lookup for the snapshot-aware commit-status resolver. */
    fun findManualTransferByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualTransferCommitRecord?

    /** P7-02.B: transfer receipt lookup for the snapshot-aware commit-status resolver. */
    fun findManualTransferByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedTransferReceipt,
    ): ManualTransferCommitRecord?

    /**
     * P7-02.C: lending request lookup for the snapshot-aware commit-status resolver. The default
     * `null` (no lending rows) keeps pre-P7-02 read-port fakes source-compatible; the real
     * adapter overrides it.
     */
    fun findManualLendingByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualLendingCommitRecord? = null

    /** P7-02.C: lending receipt lookup for the snapshot-aware commit-status resolver. */
    fun findManualLendingByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedLendingReceipt,
    ): ManualLendingCommitRecord? = null

    /**
     * P7-03.A (D-145): ledger-scoped current-version entry rows with the effective kind
     * (`COALESCE(canonical_kind, kind)`), both persisted times and the current note.
     *
     * There is deliberately no neutral default: returning an empty list would make an
     * unimplemented port indistinguishable from an empty ledger and render zeros or empty
     * months (R-Q06-4). The default fails loudly instead, and every consumer already maps a
     * read-port exception to its typed failure family
     * ([MonthlyActivityResult.Unavailable] / [TransactionDetailResult.Unavailable], or the
     * caller's typed surface for [QueryLedgerEntryRows]); the real data adapter overrides it.
     */
    fun loadLedgerEntryRows(ledgerId: LedgerId): List<LedgerEntryRow> =
        throw UnsupportedOperationException(
            "loadLedgerEntryRows is not implemented by this read port; a missing P7-03 ledger-entry " +
                "read must surface as a typed read failure, never as an empty ledger (R-Q06-4)",
        )

    /**
     * DEF-A (D-266): entry rows whose statistics instant lies in `[startEpochNanosInclusive,
     * endEpochNanosExclusive)`. The default keeps the exact pre-D-266 semantics by filtering the
     * unbounded read, so every existing port fake stays source-compatible and equivalent; the
     * SQL adapter overrides it with a range scan so a monthly projection never materialises the
     * whole ledger.
     */
    fun loadLedgerEntryRowsInWindow(
        ledgerId: LedgerId,
        startEpochNanosInclusive: Long,
        endEpochNanosExclusive: Long,
    ): List<LedgerEntryRow> =
        loadLedgerEntryRows(ledgerId).filter { row ->
            val nanos = row.statisticsAt.toEpochMilliseconds() * 1_000_000L
            nanos >= startEpochNanosInclusive && nanos < endEpochNanosExclusive
        }

    /**
     * DEF-A/DEF-C (D-267): the ledger's activity currency axis — every currency carried by an
     * effective posting of this ledger, never restricted to a read window.
     *
     * [MonthlyActivity.currencies] is defined per activity currency OF THE LEDGER (R-Q07-1's
     * explicit zero rows depend on it), so a bounded month window cannot supply it: an empty
     * month, or a month whose rows all sit in another currency, would otherwise collapse to an
     * empty axis and render no zero row at all. The default keeps the exact pre-D-266 semantics
     * by projecting the currencies of the unbounded read, so every existing port fake stays
     * source-compatible and equivalent; the SQL adapter overrides it with one DISTINCT aggregate
     * that returns the axis without materialising any entry row. Both report the axis in
     * `(code, precision)` order.
     *
     * The default is deliberately NOT neutral: it delegates to [loadLedgerEntryRows], whose own
     * default throws when a port never implemented the P7-03 surface (G6), so such a port fails
     * loudly into the caller's Unavailable instead of reporting an empty axis.
     */
    fun loadLedgerActivityCurrencies(ledgerId: LedgerId): List<CurrencyUnit> = MonthlyBuckets.activityCurrenciesOf(loadLedgerEntryRows(ledgerId))

    /**
     * DEF-B (D-268): the flow-list page key — one row per transaction, `(nanos, transaction id)`.
     * This is the CURSOR order, not the display order: it only decides where a page ends.
     */
    data class LedgerFlowCursor(
        val statisticsAtEpochNanos: Long,
        val transactionId: String,
    )

    /** DEF-B (D-268): one flow page plus the cursor of the following page (`null` = last page). */
    data class LedgerFlowPage(
        val rows: List<LedgerEntryRow>,
        val nextCursor: LedgerFlowCursor?,
    )

    /**
     * DEF-B (D-268): one page of the HOME flow list in the page-key order
     * `(statistics_at_epoch_nanos DESC, transaction_id DESC)` — the same shape as the keyset
     * precedent in `tagMerchantFilterRows`. The key is a stable cursor, NOT the display order:
     * the application re-sorts the accumulated pages with [sortLedgerEntryRowsForDisplay], so
     * P703SPEC-03 stays frozen and no schema or index change is needed. The default reproduces the
     * same order and next-cursor rule over the unbounded read at full Instant precision (never the
     * millisecond truncation of [loadLedgerEntryRowsInWindow]); the SQL adapter overrides it with
     * keyset pages so one page never materialises the ledger.
     */
    fun loadLedgerFlowPage(
        ledgerId: LedgerId,
        after: LedgerFlowCursor?,
        pageSize: Int,
    ): LedgerFlowPage {
        require(pageSize > 0) { "page size must be positive" }
        val sorted =
            loadLedgerEntryRows(ledgerId)
                .map { row -> row to row.statisticsAt.toEpochNanos() }
                .sortedWith(
                    compareByDescending<Pair<LedgerEntryRow, Long>> { it.second }
                        .thenByDescending { it.first.transactionId.value },
                )
        val remaining =
            sorted.filter { (row, nanos) ->
                after == null ||
                    nanos < after.statisticsAtEpochNanos ||
                    (nanos == after.statisticsAtEpochNanos && row.transactionId.value < after.transactionId)
            }
        val rows = remaining.take(pageSize)
        val nextCursor =
            if (remaining.size > rows.size) {
                rows.lastOrNull()?.let { (row, nanos) -> LedgerFlowCursor(nanos, row.transactionId.value) }
            } else {
                null
            }
        return LedgerFlowPage(rows.map { it.first }, nextCursor)
    }

    /**
     * DEF-B (D-268): the exact number of effective transactions ("共 N 笔"). Both entry rows and
     * current-version rows are the same effective set, so the default projects the current-version
     * read every port already implements; the SQL adapter answers with one count query.
     */
    fun effectiveTransactionCount(ledgerId: LedgerId): Int = loadCurrentRows(ledgerId).size

    /**
     * DEF-B (D-268): the bounded fallback list for a not-loaded monthly cycle — newest by
     * CREATION order (the UUIDv7 transaction id), never by statistics time. The default sorts the
     * unbounded read by id descending; the SQL adapter binds `ORDER BY tx.transaction_id DESC
     * LIMIT`, so the list stays bounded.
     */
    fun loadRecentCurrentRows(
        ledgerId: LedgerId,
        limit: Int,
    ): List<CurrentVersionRow> {
        require(limit > 0) { "limit must be positive" }
        return loadCurrentRows(ledgerId).sortedByDescending { it.transactionId.value }.take(limit)
    }

    /**
     * DEF-A: the earliest effective statistics instant (the SelectMonth lower bound), or `null`
     * for an empty ledger. The default filters nothing extra; the SQL adapter projects one number.
     */
    fun earliestEffectiveStatisticsAt(ledgerId: LedgerId): Instant? = loadLedgerEntryRows(ledgerId).minOfOrNull { it.statisticsAt }

    /**
     * DEF-A: one transaction's entry row, or `null` when it is absent (or not effective). The
     * default keeps pre-D-266 semantics over the unbounded read; the adapter reads by key.
     */
    fun loadLedgerEntryRow(
        ledgerId: LedgerId,
        transactionId: TransactionId,
    ): LedgerEntryRow? = loadLedgerEntryRows(ledgerId).firstOrNull { it.transactionId == transactionId }

    /**
     * DEF-B (D-268): the exact per-kind transaction counts behind the Analysis tab. The default
     * groups the current-version read (the same effective set); the SQL adapter answers with one
     * GROUP BY so the tab never materialises the ledger.
     */
    fun loadActivityKindCounts(ledgerId: LedgerId): List<ActivityKindCount> =
        loadCurrentRows(ledgerId)
            .groupingBy { it.kind }
            .eachCount()
            .map { (kind, count) -> ActivityKindCount(kind, count) }
            .sortedBy { it.kind.name }

    /**
     * DEF-A: per-account balance sums in exact ledger-signed minor units. The default reproduces
     * the in-memory grouping of [loadCurrentRows]; the SQL adapter aggregates in SQL so the HOME
     * projection does not materialise postings for a read-only sum.
     */
    fun accountBalanceSums(ledgerId: LedgerId): List<AccountBalanceSum> {
        val totals = mutableMapOf<Pair<AccountId, CurrencyUnit>, Long>()
        for (row in loadCurrentRows(ledgerId)) {
            for (posting in row.postings) {
                val key = posting.accountId to posting.amount.currency
                val previous = totals[key] ?: 0L
                totals[key] = previous + posting.amount.minorUnits
            }
        }
        return totals.map { (key, total) ->
            AccountBalanceSum(
                accountId = key.first,
                currency = key.second,
                ledgerSignedMinorUnits = total,
            )
        }
    }

    /** DEF-A: one account/currency balance row as the read port projects it. */
    data class AccountBalanceSum(
        val accountId: AccountId,
        val currency: CurrencyUnit,
        val ledgerSignedMinorUnits: Long,
    )

    /**
     * P7-03.A: reverse creation lineage — the import confirmation that created the
     * transaction (`operation_class = 'creation'`), or `null` (Appendix A).
     *
     * G6: no neutral default, for the same reason as [loadLedgerEntryRows]. Returning `null`
     * from an unimplemented port would claim "this transaction has no import confirmation" and
     * render a definitive 来源未标注 instead of a typed read failure; [QueryTransactionDetail]
     * maps the thrown exception to [TransactionDetailResult.Unavailable]. A read port that
     * genuinely has no such row returns `null` explicitly.
     */
    fun findImportCreationConfirmation(
        ledgerId: LedgerId,
        transactionId: TransactionId,
    ): ImportCreationConfirmationRow? =
        throw UnsupportedOperationException(
            "findImportCreationConfirmation is not implemented by this read port; a missing lineage read " +
                "must surface as a typed read failure, never as an unmarked creation entry (R-Q06-4)",
        )

    /**
     * P7-03.A: reverse creation lineage — the manual four-chain receipt for the
     * transaction, or `null` (Appendix A). G6: no neutral default (see
     * [findImportCreationConfirmation]).
     */
    fun findManualCreationReceipt(
        ledgerId: LedgerId,
        transactionId: TransactionId,
    ): ManualCreationReceiptRow? =
        throw UnsupportedOperationException(
            "findManualCreationReceipt is not implemented by this read port; a missing lineage read " +
                "must surface as a typed read failure, never as an unmarked creation entry (R-Q06-4)",
        )

    /**
     * P7-03.A: read-only reconciliation leg projection for the transaction's current
     * version (R-Q07-4 / spec section 3.2.1). Never writes reconciliation state.
     *
     * G6: no neutral default. An empty list from an unimplemented port would render every leg
     * as 无对账资格 — a definitive-looking verdict about reconciliation state that was never
     * read; [QueryTransactionDetail] maps the thrown exception to
     * [TransactionDetailResult.Unavailable].
     */
    fun loadTransactionReconciliationLegs(
        ledgerId: LedgerId,
        transactionId: TransactionId,
    ): List<TransactionReconciliationLegRow> =
        throw UnsupportedOperationException(
            "loadTransactionReconciliationLegs is not implemented by this read port; a missing reconciliation " +
                "projection must surface as a typed read failure, never as an all-ineligible verdict (R-Q06-4)",
        )

    /**
     * P7-05.C (D-156, DP-1): the voided complement of [loadLedgerEntryRows] — the same table
     * group and row shape plus the void metadata the recycle bin shows, ordered by the frozen
     * `(void time DESC, transaction_id ASC)` total order. The recycle bin must not invent a
     * second source of truth, so this is the same predicate view with the opposite polarity.
     *
     * G6: no neutral default, for the same reason as [loadLedgerEntryRows]. An empty list from
     * an unimplemented port would render an empty recycle bin for a ledger that may well have
     * voided transactions; [QueryRecycleBin] maps the thrown exception to
     * [RecycleBinResult.Unavailable].
     */
    fun loadVoidedTransactionRows(ledgerId: LedgerId): List<VoidedTransactionRow> =
        throw UnsupportedOperationException(
            "loadVoidedTransactionRows is not implemented by this read port; a missing recycle-bin read " +
                "must surface as a typed read failure, never as an empty recycle bin (R-Q06-4)",
        )

    /**
     * P7-05.C (D-156, DP-13): whether the transaction has an effective linked refund. The void
     * precondition and the recycle bin's dependency explanation both read it.
     *
     * G6: no neutral default. `false` from an unimplemented port would claim "no linked refund"
     * and let a void through on an unread fact; the void commit port and [QueryRecycleBin] map
     * the thrown exception to their typed failure surfaces.
     */
    fun hasEffectiveRefundLink(
        ledgerId: LedgerId,
        transactionId: TransactionId,
    ): Boolean =
        throw UnsupportedOperationException(
            "hasEffectiveRefundLink is not implemented by this read port; a missing refund-linkage read " +
                "must surface as a typed read failure, never as 'no linked refund' (R-Q06-4)",
        )

    /**
     * P7-05.B (D-156, spec section 4.2): the persisted correction request/receipt pair behind
     * the snapshot-aware unknown-commit resolver, or `null` when this request identity was
     * never committed. G6: no neutral default — `null` from an unimplemented port would claim
     * "this request was never committed" and let the caller re-commit it.
     */
    fun findTransactionCorrectionByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): TransactionCorrectionCommitRecord? =
        throw UnsupportedOperationException(
            "findTransactionCorrectionByRequest is not implemented by this read port; a missing request " +
                "read must surface as a typed read failure, never as 'never committed' (R-Q06-4)",
        )

    /**
     * P7-05.C (D-156, spec section 4.2): the persisted void/restore request/receipt pair behind
     * the snapshot-aware unknown-commit resolver, or `null` when the identity was never
     * committed. G6: no neutral default (see [findTransactionCorrectionByRequest]).
     */
    fun findTransactionVoidByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): TransactionVoidCommitRecord? =
        throw UnsupportedOperationException(
            "findTransactionVoidByRequest is not implemented by this read port; a missing request " +
                "read must surface as a typed read failure, never as 'never committed' (R-Q06-4)",
        )
}

/** DEF-B (D-268): full-precision epoch nanoseconds — the projection column's unit, never milliseconds. */
private fun Instant.toEpochNanos(): Long = epochSeconds * 1_000_000_000L + nanosecondsOfSecond.toLong()
