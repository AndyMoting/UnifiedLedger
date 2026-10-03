package com.unifiedledger.android

import android.database.sqlite.SQLiteDatabaseLockedException

/**
 * D-209 bounded SQLITE_BUSY retry policy for the scale oracle's read-only
 * observation.
 *
 * Run 37136633556 reached the maximum chain's deepest point (coldstart PASS
 * 26.5s, `saf_import` 342s -- navigation, SAF pick and the 10k import all
 * worked) and then `oracle.assertFixture` threw
 * `SQLiteDatabaseLockedException` (code 5 SQLITE_BUSY) out of
 * [AndroidScaleOracle.read]: the oracle opens a fresh read-only connection and
 * `beginTransactionReadOnly` per observation with no busy retry, while the
 * live app process still held write transactions for its post-import flush,
 * so the default ~2.5s busy timeout expired and the observation threw. The
 * chain design intends concurrent observation of a live app, so retrying a
 * read-only observation is safe by construction: every attempt re-reads the
 * active pointer, re-validates the generation/journal invariants, and re-runs
 * the whole observation inside a fresh read-only transaction, so a retried
 * read satisfies exactly the same checks as a first-attempt one.
 *
 * Everything here is pure logic (bounds, backoff, failure classification) so
 * it can be shared between the device writer and the JVM tests. The sleep and
 * the deadline re-check stay in [AndroidScaleOracle.read]: the stage deadline
 * remains the hard bound because tick runs after every sleep, and non-lock
 * failures are never retried. The `android.database.sqlite` API is
 * Android-only, so the throwable-level classifier is device-CI-owned beyond
 * these pure tests (the JVM cannot even construct the exception against the
 * stub android.jar; the true branch is verified against the exact class
 * literal via [isLockRetryableType]).
 */
internal object AndroidScaleOracleRetry {
    /** Total observation attempts: the initial attempt plus 11 lock retries. */
    const val LOCK_RETRY_MAX_ATTEMPTS = 12

    /**
     * Exponential backoff with a 5s cap for the 1-based retry number being
     * decided: 1000, 2000, 4000, then 5000 for every remaining retry (worst
     * case ~47s of waiting across the 11 retries, independent of deadlines).
     */
    fun lockRetryBackoffMs(attempt: Int): Long = minOf(1000L shl (attempt - 1), 5000L)

    /**
     * True exactly for `SQLiteDatabaseLockedException` (and subclasses).
     * Corruption, pointer/invariant failures, deadline expiry and assertion
     * failures are never retried and must surface immediately.
     */
    fun isLockRetryable(throwable: Throwable?): Boolean = throwable != null && isLockRetryableType(throwable.javaClass)

    /**
     * Type-level counterpart of [isLockRetryable], so the JVM test can pin the
     * exact Android exception class (and its subclass rule) with class
     * literals where instances cannot be constructed.
     */
    fun isLockRetryableType(type: Class<*>): Boolean = SQLiteDatabaseLockedException::class.java.isAssignableFrom(type)

    /**
     * D-211 quiescence gating: poll interval while waiting for the active
     * generation's rollback journal to drain before a lock retry.
     */
    const val JOURNAL_WAIT_POLL_MS = 1000L

    /**
     * D-211 quiescence gating: cap on journal waiting per retry gap (5 minutes),
     * bounded independently of the stage deadline, which stays the hard bound
     * because tick runs after every sleep.
     */
    const val JOURNAL_WAIT_BUDGET_MS = 300_000L

    /**
     * D-211 quiescence gating: consecutive empty/absent journal reads required
     * before a retry attempt may proceed.
     */
    const val JOURNAL_QUIESCE_CONSECUTIVE = 2

    /** D-211 outcome of one journal quiescence evaluation for a retry gap. */
    enum class JournalWait {
        /** Journal empty for long enough: quiescent enough to attempt now. */
        PROCEED,

        /** Journal non-empty or not yet quiesced long enough, and budget remains. */
        WAIT,

        /**
         * Budget exhausted: proceed with the attempt anyway rather than fail —
         * a bounded observer that cannot confirm quiescence still tries, since
         * attempts are what produce the lock error or success.
         */
        GIVE_UP,
    }

    /**
     * D-211 pure journal quiescence decision for one poll: [journalLen] is the
     * rollback journal's length (0 for absent, and any stat error is treated
     * as 0 by the caller), [consecutiveEmpty] counts consecutive empty/absent
     * journal reads including this one, and [waitedMs] is the time already
     * spent journal-waiting in this retry gap. [JournalWait.PROCEED] requires
     * both an empty/absent journal and [JOURNAL_QUIESCE_CONSECUTIVE]
     * consecutive empty reads; [JournalWait.GIVE_UP] fires once the budget is
     * exhausted (waited >= [JOURNAL_WAIT_BUDGET_MS]) so the attempt still runs;
     * otherwise [JournalWait.WAIT]. Pure: no File or clock access.
     */
    fun journalWaitDecision(
        journalLen: Long,
        consecutiveEmpty: Int,
        waitedMs: Long,
    ): JournalWait =
        when {
            journalLen <= 0 && consecutiveEmpty >= JOURNAL_QUIESCE_CONSECUTIVE -> JournalWait.PROCEED
            waitedMs >= JOURNAL_WAIT_BUDGET_MS -> JournalWait.GIVE_UP
            else -> JournalWait.WAIT
        }
}
