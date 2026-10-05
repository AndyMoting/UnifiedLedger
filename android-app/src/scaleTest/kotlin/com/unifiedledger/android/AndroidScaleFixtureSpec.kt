package com.unifiedledger.android

/** Profiles the cloud chain may be driven with (see D-216). */
internal val ANDROID_SCALE_LONG_PROFILES = setOf("maximum", "local-small", "local-medium")

/**
 * D-216: manifest-derived expectations for the long chain, so the same device
 * test drives both the cloud `maximum` fixture and the local `local-small`
 * fixture instead of hard-coding maximum's 61,000/150,000 oracle.
 *
 * Every value is read from the generated manifest (the single source of truth,
 * see `tools/python/android_scale/fixture.py`); the derived properties
 * reproduce maximum's historical literals exactly: rowsPerSession=10000,
 * uniqueRows=1000, initialSessions=5 give initialCandidates=51000,
 * finalCandidates=61000, initialDuplicateRelations=100000,
 * finalDuplicateRelations=150000, newSessionDuplicateRelations=50000,
 * initiallyConfirmedRelations=100, and the duplicate-history laws 100100
 * (after prepare) and 200100 (after the group disposition).
 *
 * Pure logic only (no Android, no JSON) so it is covered by the JVM tests.
 */
internal data class AndroidScaleFixtureSpec(
    val profile: String,
    val seed: Long,
    val initialSessions: Int,
    val rowsPerSession: Int,
    val uniqueRows: Int,
    val mainSessionRows: Int,
    val initialCandidates: Int,
    val finalCandidates: Int,
    val initialDuplicateRelations: Int,
    val finalDuplicateRelations: Int,
    val newSessionDuplicateRelations: Int,
    val initiallyConfirmedRelations: Int,
) {
    /** Sessions held in `state`: the prepare sessions plus the unique-rows session. */
    val stateSessions: Int get() = initialSessions + 1

    /** Shared-value multiplicity: prepare sessions initially, plus the SAF session finally. */
    val finalSessions: Int get() = initialSessions + 1

    /** Distinct amounts: shared values plus the (disjoint) unique rows. */
    val distinctAmounts: Int get() = rowsPerSession + uniqueRows

    /** Endpoint pairs per shared value: C(n, 2). */
    val initialPairCount: Int get() = initialSessions * (initialSessions - 1) / 2
    val finalPairCount: Int get() = finalSessions * (finalSessions - 1) / 2

    /** One creation history row per relation, plus one status_transition per disposition. */
    val duplicateHistoryAfterPrepare: Long get() = initialDuplicateRelations.toLong() + initiallyConfirmedRelations
    val duplicateHistoryAfterGroup: Long
        get() = finalDuplicateRelations.toLong() + initiallyConfirmedRelations + newSessionDuplicateRelations

    val duplicateReviewReceiptAfterPrepare: Long get() = initiallyConfirmedRelations.toLong()
    val duplicateReviewReceiptAfterGroup: Long get() = (initiallyConfirmedRelations + newSessionDuplicateRelations).toLong()

    /** Rows the intake session at `index` carries (prepare sessions, then the unique session). */
    fun sessionRows(index: Int): Int = if (index < initialSessions) rowsPerSession else uniqueRows

    /** SAF-published fixture file of the main (post-prepare) session. */
    val mainSessionFileName: String get() = "session-" + stateSessions.toString().padStart(2, '0') + ".csv"

    /** Amount offset of the unique session: its values start after the shared range. */
    fun sessionAmountOffset(index: Int): Int = if (index < initialSessions) 0 else rowsPerSession

    /** Shared-value multiplicity: prepare sessions before the SAF import, all sessions after. */
    fun multiplicity(final: Boolean): Int = if (final) finalSessions else initialSessions

    fun pairCount(final: Boolean): Int = if (final) finalPairCount else initialPairCount

    fun duplicateRelations(final: Boolean): Int = if (final) finalDuplicateRelations else initialDuplicateRelations

    fun candidates(final: Boolean): Int = if (final) finalCandidates else initialCandidates

    /**
     * Generator-consistency facts (mirrors `_manifest` in fixture.py). A manifest
     * that violates them cannot have come from the generator and is refused
     * before the chain trusts any expected total.
     */
    fun isValid(): Boolean =
        profile in ANDROID_SCALE_LONG_PROFILES &&
            initialSessions > 0 &&
            rowsPerSession > 0 &&
            uniqueRows > 0 &&
            initialCandidates == initialSessions * rowsPerSession + uniqueRows &&
            finalCandidates == initialCandidates + mainSessionRows &&
            finalDuplicateRelations == initialDuplicateRelations + newSessionDuplicateRelations
}
