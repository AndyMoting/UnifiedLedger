package com.unifiedledger.ui

/**
 * D-215 bounded retry policy for the initial current-state read's transient lease refusal.
 *
 * Run 37209978362 (device timeline): `optimize.thread end ok=true` @958617 →
 * `startupController.state state=Ready` @958618 → `read.catalogSnapshot begin` @958659 →
 * `read.currentState begin` @958663 → `read.currentState end kind=failed cause=leaseNotReady`
 * @958667 (4ms) → `read.catalogSnapshot end kind=completed` @958714. The app fires two startup
 * reads concurrently and the runtime exposes a SINGLE global, non-blocking lease whose
 * `mutex.tryLock()` failure returns `RuntimeNotReady` without waiting (P7-06 spec 4.2). The
 * catalog read already tolerates that refusal (it keeps its previous/loading value), while the
 * current-state read treated it as fatal, producing the fail-closed error screen with no retry.
 * `state=Ready` had already been emitted and the sibling `catalogSnapshot` read succeeded under
 * the same facade in 55ms, so the only remaining `LeaseOutcome.NotReady` source is the tryLock
 * race. The initial read performs no writes (a plain `queryCurrentState.query()` plus a pin
 * preference read) and the sibling read released the lease within tens of milliseconds, so
 * re-attempting the SAME read-only lease acquisition is safe by construction: no ledger state is
 * created or replaced, and exhaustion preserves the D-214 fail-closed behavior unchanged.
 *
 * Decision shape (pure, JVM-testable): [shouldRetryCause] takes the D-214 discriminated cause
 * token and is true EXACTLY for the transient [LEASE_NOT_READY_CAUSE]; [shouldRetry] adds the
 * bounded attempt count. Real failures — `readThrew`, any `readResult` variant, and a stale
 * generation — arrive as [LeaseOutcome.Completed] and never reach this classifier.
 */
internal object InitialReadLeaseRetry {
    /**
     * Total acquisition attempts for the initial read: the initial attempt plus three retries.
     * The sibling startup read completes in tens of milliseconds, so the bound stays small.
     */
    const val MAX_ATTEMPTS = 4

    /**
     * Delay before each re-attempt; worst case adds
     * `(MAX_ATTEMPTS - 1) * RETRY_DELAY_MS` = 300ms to the initial load.
     */
    const val RETRY_DELAY_MS = 100L

    /**
     * The transient D-214 cause token for a bare lease refusal — the only retryable token. It is
     * the sole cause produced when the runtime is Ready but the single non-blocking lease is
     * momentarily held by the concurrent startup read.
     */
    const val LEASE_NOT_READY_CAUSE = "leaseNotReady"

    /**
     * True exactly for the transient [LEASE_NOT_READY_CAUSE] token; false for the D-214
     * `readThrew errorType=...` and `readResult variant=...` causes and for anything else, so a
     * real read failure is never retried.
     */
    fun shouldRetryCause(cause: String): Boolean = cause == LEASE_NOT_READY_CAUSE

    /**
     * Pure bounded decision: retry only while [shouldRetryCause] holds and fewer than
     * [MAX_ATTEMPTS] acquisition attempts have been made. [attemptsMade] counts attempts already
     * performed, so `attemptsMade == MAX_ATTEMPTS` means the bound is exhausted.
     */
    fun shouldRetry(attemptsMade: Int, cause: String): Boolean =
        attemptsMade < MAX_ATTEMPTS && shouldRetryCause(cause)
}
