package com.unifiedledger.application

import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import kotlin.time.Instant

/*
 * P7-07 07.D-1 shared bounded contribution read (D-184 item 3 residual; spec section 5.1).
 *
 * Spec section 5.1 freezes a SHARED bounded read port: input `ledgerId` plus a month range
 * `[start, end)`, returning the range's minimal per-transaction posting contributions in ONE
 * read snapshot, reusing the exact frozen classifier ([BudgetOrdinaryNetExpense] /
 * [MonthlyBuckets.aggregate]) so a budget, a tag or a merchant filter observes the same
 * ordinary semantics. It must NOT call the full-ledger `QueryMonthlyActivity` /
 * `loadLedgerEntryRows` (spec section 1.1's whole-ledger read has no month predicate).
 *
 * WHAT IT RETURNS AND WHY. The port returns the window's current-version effective rows as
 * [LedgerEntryRow] — exactly the input [BudgetOrdinaryNetExpense] consumes — rather than a
 * pre-folded per-scope number. One aggregate pass over these rows yields the TOTAL observation
 * AND every parent/child category observation (spec section 5.1: "一次查询/折叠同时得到总额与
 * 所有一级/二级观察值"), so a use case with N configured scopes still reads the ledger once
 * and folds once. Returning pre-folded per-scope totals would force either N reads or an
 * unbounded scope list into the port, and would move the frozen classification out of its
 * single definition point.
 *
 * The snapshot also carries the [LedgerCatalog] the read ran against, so the classifier folds
 * these postings with EXACTLY the catalog generation they were read with; the caller never
 * re-supplies a possibly-newer catalog.
 *
 * CONSISTENT CATALOG/TRANSACTION VERSION (spec section 5.1 / open item 9). The port takes the
 * caller's `expectedCatalogVersion` — the generation of the [CatalogAuthority] the session
 * classified against — and the data adapter reads the catalog version at the START and END of
 * ONE read transaction: if the two differ (a catalog write committed mid-read) or either
 * differs from `expectedCatalogVersion`, the read fails with
 * [MonthlyContributionReadFailure.CatalogVersionMismatch]. A catalog write between the
 * session's catalog load and this read is therefore detected, never silently zeroed. Reading
 * the version and the postings inside one SQLite read transaction makes the window a single
 * consistent snapshot.
 *
 * FAIL-CLOSED (spec section 5.1, mirroring [MonthlyActivityResult]): a database failure, a
 * missing time projection (spec section 6.4 item 5) or a catalog/tx generation mismatch is a
 * typed failure, never a zero execution amount. The use case maps every failure to its typed
 * result and never renders zero.
 */

/** The typed failure family of the bounded contribution read (spec section 5.1). */
sealed interface MonthlyContributionReadFailure {
    /**
     * A version row in scope has no numeric time projection. The window cannot be computed
     * without falling back to raw-text comparison, which the spec forbids (section 6.4 item 5);
     * the read refuses rather than silently omitting or zeroing the row.
     */
    data object MissingProjection : MonthlyContributionReadFailure

    /**
     * The catalog generation inside the read snapshot differs from the caller's
     * `expectedCatalogVersion` (or changed mid-read), so the caller's catalog and these
     * postings are not from one generation. Reported as a typed failure, never a silent zero
     * (spec section 5.1).
     */
    data object CatalogVersionMismatch : MonthlyContributionReadFailure

    /** A database/read failure, or a window bound outside the projection's representable range. */
    data object Unavailable : MonthlyContributionReadFailure
}

/** The result of one bounded contribution read. */
sealed interface MonthlyContributionReadResult {
    /**
     * The window's current-version effective rows, ordered by `(statisticsAt, transactionId,
     * postingIndex)`, plus the catalog generation they were read with. An empty [rows] list is
     * a genuine empty window, not a failure.
     */
    data class Success(
        val catalogVersion: Long,
        val catalog: LedgerCatalog,
        val rows: List<LedgerEntryRow>,
    ) : MonthlyContributionReadResult

    data class Failed(
        val failure: MonthlyContributionReadFailure,
    ) : MonthlyContributionReadResult
}

/**
 * P7-07 07.D-1 shared bounded contribution read (spec section 5.1).
 *
 * Implementations MUST:
 *
 * - return exactly the CURRENT version of each EFFECTIVE transaction whose statistics instant
 *   lies in `[startInclusive, endExclusive)`, reusing the single effective-predicate definition
 *   point (`transaction_effective_state`) and the numeric projection's range index — never a
 *   second state rule and never a raw-text comparison (spec sections 5.1/6.4 item 6);
 * - complete the catalog-version check and the posting read in ONE snapshot, so a mismatch
 *   between the caller's catalog generation and the read generation is a typed failure;
 * - fail with [MonthlyContributionReadFailure.MissingProjection] rather than omitting a row
 *   whose projection is missing;
 * - never return an empty list to stand in for a read failure, and never fold or dedupe
 *   amounts in SQL (spec section 5.1: no floating-point SUM, no `SUM(DISTINCT amount)`).
 */
fun interface MonthlyContributionReadPort {
    /**
     * The current-version effective rows whose statistics instant is in
     * `[startInclusive, endExclusive)`, with the catalog-generation consistency check.
     * [expectedCatalogVersion] is the caller's [CatalogAuthority.catalogVersion].
     */
    fun readContributions(
        ledgerId: LedgerId,
        startInclusive: Instant,
        endExclusive: Instant,
        expectedCatalogVersion: Long,
    ): MonthlyContributionReadResult
}
