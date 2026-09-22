package com.unifiedledger.ui

import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportDuplicateStatus
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportPlatformKind
import com.unifiedledger.application.ImportReviewRow
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D-169 attribution, side B (test-only): the eager render-model build
 * (`importReviewRenderItems`) on the registered 61k-candidate library shape. D-169 section 5
 * registered an operability defect (~11 s tap-to-visible on a 61k-candidate device library, above
 * the OOM fix's frozen <=3 s threshold) and located the cost in the UI layer by bounded read-only
 * code inspection, but did NOT attribute the 11 s between (A) the paged DB read
 * (`SqlDelightImportReviewReadAdapter.loadImportReviewRows`, measured in the ledger-data companion
 * `ImportReview61kReadAttributionTest`) and (B) this render-model build. This test measures (B)
 * alone.
 *
 * INPUT SHAPE: `ImportReviewView.rows` is a synthetic approximation of the list (A) returns —
 * 61,000 `ImportReviewRow`, 10,000 with a null duplicate status and 51,000 folded to DEFERRED (the
 * D-166 profile), all pending_confirmation / SETTLED / VALID_COMPLETE ordinary_flow rows. This is
 * the D-166 synthetic profile only: the registered artifact's folded mix differs (10,000 null /
 * 50,600 DEFERRED / 400 CONFIRMED_DUPLICATE), so the artifact's real list carries three
 * classification group headers rather than the two below, and the item-count/group-header comment
 * describes this synthetic profile alone. The rows are constructed directly here (app-ui cannot
 * depend on ledger-data), matching the read-model shape the adapter folds; building that 61k list
 * is done BEFORE timing so it is not counted in (B). The measured call is the app's own
 * composition-thread projection, with no `remember` (the call site is `P503ImportReview.kt`), so
 * this is exactly the work the first IMPORT entry pays.
 *
 * TIMING API: `kotlin.system.measureNanoTime` (common stdlib; its JVM implementation delegates to
 * `System.nanoTime`). `java.lang.System` is not visible to a common source set and this change must
 * stay test-only, so the common nanoTime primitive is used instead of calling `System.nanoTime`
 * directly. Wall times are recorded only, never asserted (a time assertion would flake); the printed
 * `D169ATTR ` lines are the evidence.
 *
 * ATTRIBUTION OUTCOME (recorded, not proven): on this host side A (paged read + fold) is ~2.0-2.3
 * s/round and side B (this render-model build) is ~10-50 ms/round — together only ~2.4 s of the
 * registered ~11 s. The residual ~8.6 s is therefore NOT explained by these host-side components
 * and is most plausibly device-side UI composition/recomposition, which these tests do not
 * measure. This is an attribution of the measured host-side work, not a proven device root cause.
 */
class P503ImportReviewRenderModelAttributionTest {
    @Test
    fun renderModelBuildTimeForThe61kCandidateLibrary() {
        val rows = synthetic61kRows()
        assertEquals(EXPECTED_CANDIDATES, rows.size)
        assertEquals(EXPECTED_ZERO_DUPLICATE_CANDIDATES, rows.count { it.duplicateStatus == null })
        assertEquals(EXPECTED_DUPLICATE_CANDIDATES, rows.count { it.duplicateStatus == ImportDuplicateStatus.DEFERRED })
        val view = ImportReviewView(rows = rows)
        // Warmup: discarded, so the measured rounds do not pay first-call class loading / JIT warmup.
        importReviewRenderItems(view, ImportPlatformKind.ANDROID)
        val times = mutableListOf<Long>()
        var itemCount = 0
        repeat(ROUNDS) {
            var count = 0
            val nanos =
                measureNanoTime {
                    count = importReviewRenderItems(view, ImportPlatformKind.ANDROID).size
                }
            itemCount = count
            times += nanos
        }
        // The render model is not truncated: every candidate becomes one CandidateItem, plus the
        // static sections, the four-entry Android format matrix and the single group header.
        assertEquals(EXPECTED_ITEM_COUNT, itemCount)
        println("D169ATTR: side=B component=render-model-build fixture=61k-synthetic-rows")
        println("D169ATTR: side=B candidates=${rows.size} items=$itemCount")
        times.forEachIndexed { index, nanos -> println("D169ATTR: side=B round=${index + 1} render-ms=${format(nanos)}") }
        println("D169ATTR: side=B render-min-ms=${format(times.min())} render-max-ms=${format(times.max())}")
        assertTrue(itemCount > rows.size, "the render model must carry every candidate plus its sections")
    }

    /**
     * The D-166 synthetic profile: 61,000 pending ordinary_flow rows, the first 10,000 without a
     * duplicate relation and the remaining 51,000 folded to DEFERRED. These form TWO classification
     * groups — 10,000 in 待确认——缺用户决策 (no duplicate relation) and 51,000 in 疑似重复——待审核
     * (DEFERRED) — so `importCandidateClassGroups` still runs its six full-list filters and
     * `classifyImportCandidate` over all 61,000 rows, matching the app's work.
     * The registered artifact's folded mix differs (400 CONFIRMED_DUPLICATE), so its real list
     * carries a third classification group header; this synthetic profile keeps the two described
     * in the companion comment below.
     */
    private fun synthetic61kRows(): List<ImportReviewRow> =
        (0 until EXPECTED_CANDIDATES).map { index ->
            val duplicateStatus =
                if (index < EXPECTED_ZERO_DUPLICATE_CANDIDATES) null else ImportDuplicateStatus.DEFERRED
            ImportReviewRow(
                candidateId = ImportCandidateId("candidate-" + index.toString().padStart(6, '0')),
                candidateKind = "ordinary_flow",
                sourceInputRef = "pick-handle-1",
                amountMinor = 3_580L,
                currencyCode = "CNY",
                currencyPrecision = 2,
                occurredAt = "2026-03-15T02:00:00Z",
                directionToken = "expense",
                statusToken = "trade_success",
                fundingState = ImportFundingState.SETTLED,
                completeness = ImportCompleteness.VALID_COMPLETE,
                contentHash = "sha256:fixed-content-hash",
                candidateStatus = "pending_confirmation",
                requiresConfirmation = true,
                confidence = "1.00",
                duplicateStatus = duplicateStatus,
                paymentProfileVariant = null,
                paymentProfileAssetLegKindToken = null,
                paymentProfileCreditLegKindToken = null,
            )
        }

    private fun format(nanos: Long): String {
        val whole = nanos / 1_000_000
        val fraction = (nanos % 1_000_000) / 1_000
        return "$whole.${fraction.toString().padStart(3, '0')}"
    }

    private companion object {
        const val ROUNDS = 3
        const val EXPECTED_CANDIDATES = 61_000
        const val EXPECTED_ZERO_DUPLICATE_CANDIDATES = 10_000
        const val EXPECTED_DUPLICATE_CANDIDATES = 51_000

        /**
         * TitleBar + 2 dividers + format header + pending header + TWO group headers (the 10,000
         * no-duplicate rows fall in 待确认——缺用户决策 and the 51,000 DEFERRED rows in
         * 疑似重复——待审核) = 7 static, + the 4-entry Android format matrix, + 61,000
         * CandidateItems = 61,011. No disposition affordance renders (no session handle is set).
         * This count holds for the D-166 synthetic profile only: the registered artifact carries a
         * third group header (400 CONFIRMED_DUPLICATE), which this test does not construct.
         */
        const val EXPECTED_ITEM_COUNT = 7 + 4 + EXPECTED_CANDIDATES
    }
}
