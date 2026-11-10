package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import kotlin.time.Instant

/*
 * P8-01a account-scoped reconciliation read face (D-308 items 6/8; spec section 2.3).
 *
 * This port is the READ side only: it creates no reconciliation row, writes no evidence link and
 * changes no balance or report. It answers the two questions the account queue needs — one keyset
 * page of the account's reconcilable legs, and the per-(currency, status) totals over exactly the
 * same legs.
 *
 * WHAT MAKES A LEG A QUEUE MEMBER. Exactly the spec section 2.1 composite mark
 * (`owned_by_user = 1 AND real_account = 1 AND kind IN ('ASSET','LIABILITY')`) on the leg's own
 * account, over the current version of an effective transaction. The mark is evaluated by the
 * data query against `catalog_account`, so page and totals share one membership definition; this
 * port never re-derives it from a partially loaded row set.
 *
 * NO NEUTRAL DEFAULT (R-Q06-4 / D-089). Every answer is a typed result: an absent payload is
 * either an empty [P801AccountReconciliationQueuePage] / an empty totals list (a genuine "no such
 * leg") or a [P801AccountReconciliationReadFailure] — never a zero amount, a zero count or an
 * empty result standing in for a read that failed. The page size is frozen by D-308 item 8.
 */

/** D-308 item 8: the frozen queue page size (100 legs per page). */
const val P801_ACCOUNT_RECONCILIATION_PAGE_SIZE: Int = 100

/**
 * One keyset position of the account reconciliation queue: the frozen page key
 * `(occurred_at DESC, posting_id ASC)` of D-308 item 8.
 *
 * [occurredAtStorageValue] is the PERSISTED `occurred_at` text of the last row of the previous
 * page (the same `*StorageValue` shape [TransactionReconciliationLegRow.statusStorageValue] uses),
 * never a re-serialised `Instant`: `occurred_at` is a TEXT column and the keyset predicate
 * compares that column, so passing back a re-serialised instant could skip or repeat a row at a
 * page boundary. The value is opaque to the application layer — take it from the page and hand it
 * back unchanged.
 */
data class P801AccountReconciliationCursor(
    val occurredAtStorageValue: String,
    val postingId: PostingId,
)

/**
 * One reconcilable leg of the account reconciliation queue (spec section 2.3). The amount is the
 * leg's exact ledger-signed minor units with its own [currency]; [status] is the stored
 * reconciliation status, where a leg with no `posting_reconciliation` row reads
 * [P408ReconciliationStatus.PENDING]; [transactionKind] is the transaction's effective kind
 * (`COALESCE(canonical_kind, kind)`), so the queue can label a leg by date, note or kind.
 */
data class P801AccountReconciliationQueueRow(
    val postingId: PostingId,
    val transactionId: TransactionId,
    val occurredAt: Instant,
    val note: String?,
    val accountId: AccountId,
    val amountMinor: Long,
    val currency: CurrencyUnit,
    val status: P408ReconciliationStatus,
    val hasActiveEvidenceLink: Boolean,
    val transactionKind: TransactionKind,
)

/**
 * One page of the account reconciliation queue. An empty [rows] list is a genuine "this account
 * has no such leg in this page position", never a failure; [nextCursor] is `null` exactly when
 * the page is the last one.
 */
data class P801AccountReconciliationQueuePage(
    val rows: List<P801AccountReconciliationQueueRow>,
    val nextCursor: P801AccountReconciliationCursor?,
)

/**
 * One `(currency, status)` group of the account reconciliation summary (spec section 2.3): the
 * exact integer-minor-unit sum and the leg count of that group.
 *
 * Only groups the ledger actually has are returned — an absent group stays absent instead of
 * being emitted as a zero row (D-089). A real `0` [totalMinorUnits] is therefore the sum of real
 * rows, which [rowCount] proves.
 */
data class P801AccountReconciliationStatusTotal(
    val currency: CurrencyUnit,
    val status: P408ReconciliationStatus,
    val totalMinorUnits: Long,
    val rowCount: Int,
) {
    init {
        require(rowCount >= 1) { "a reconciliation status total must cover at least one leg, never a fabricated zero row" }
    }
}

/** The typed failure family of the account reconciliation read face (spec section 2.3). */
sealed interface P801AccountReconciliationReadFailure {
    /**
     * The read failed, or a persisted value could not be read as its domain type, or the integer
     * minor-unit sum overflowed (SQLite refuses an overflowing INTEGER `sum()` instead of
     * returning a float). Every one of them is a typed failure: the caller keeps the rows it
     * already has and shows the failure rather than a zero (spec section 2.3, V-9).
     */
    data object Unavailable : P801AccountReconciliationReadFailure
}

/** The result of one queue page read. */
sealed interface P801AccountReconciliationPageResult {
    data class Success(
        val page: P801AccountReconciliationQueuePage,
    ) : P801AccountReconciliationPageResult

    data class Failed(
        val failure: P801AccountReconciliationReadFailure,
    ) : P801AccountReconciliationPageResult
}

/** The result of one summary read. */
sealed interface P801AccountReconciliationSummaryResult {
    data class Success(
        val totals: List<P801AccountReconciliationStatusTotal>,
    ) : P801AccountReconciliationSummaryResult

    data class Failed(
        val failure: P801AccountReconciliationReadFailure,
    ) : P801AccountReconciliationSummaryResult
}

/**
 * P8-01a account reconciliation read port (D-308 terms 6/8; spec section 2.3).
 *
 * Implementations MUST:
 *
 * - restrict every read to the ledger AND the requested account, to the CURRENT version of each
 *   EFFECTIVE transaction through the single effective-predicate definition point
 *   (`transaction_effective_state`), and to accounts satisfying the section 2.1 composite mark;
 * - page by the frozen key `(occurred_at DESC, posting_id ASC)` with the caller's
 *   [P801_ACCOUNT_RECONCILIATION_PAGE_SIZE] default, so concatenating the pages reproduces the
 *   whole queue exactly once;
 * - keep every amount in exact integer minor units — no floating point, no `total()`, and no
 *   sum folded by the caller from a differently-filtered read;
 * - answer with a typed failure instead of a zero, an empty page or an empty totals list when the
 *   read fails (R-Q06-4). There is deliberately no neutral default on this interface: a port that
 *   cannot answer must fail loudly rather than look like a reconciled-empty account.
 */
interface P801AccountReconciliationReadPort {
    /**
     * One keyset page of [accountId]'s reconcilable legs, ordered `occurred_at DESC,
     * posting_id ASC`. [after] is the previous page's cursor, or `null` for the first page.
     */
    fun loadAccountReconciliationPage(
        ledgerId: LedgerId,
        accountId: AccountId,
        after: P801AccountReconciliationCursor?,
        pageSize: Int = P801_ACCOUNT_RECONCILIATION_PAGE_SIZE,
    ): P801AccountReconciliationPageResult

    /**
     * The exact per-(currency, status) totals over the SAME legs the page enumerates — the whole
     * account, never a window (D-308 item 8 registers the range selector as a later slice).
     */
    fun loadAccountReconciliationSummary(
        ledgerId: LedgerId,
        accountId: AccountId,
    ): P801AccountReconciliationSummaryResult
}
