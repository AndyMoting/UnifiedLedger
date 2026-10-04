package com.unifiedledger.ui

/**
 * D-215 bounded retry policy for the initial current-state read's lease refusal.
 *
 * Observed facts (run 37209978362, device timeline): `optimize.thread end ok=true` @958617 →
 * `startupController.state state=Ready` @958618 → `read.catalogSnapshot begin` @958659 →
 * `read.currentState begin` @958663 → `read.currentState end kind=failed cause=leaseNotReady`
 * @958667 (4ms after begin) → `read.catalogSnapshot end kind=completed` @958714 (55ms after its
 * begin). The app fires two startup reads concurrently and the runtime exposes a single global,
 * non-blocking lease (`mutex.tryLock()` failure returns `RuntimeNotReady` without waiting, P7-06
 * spec 4.2). The catalog path already tolerates that refusal (it keeps its previous/loading
 * value), while the current-state read treated it as fatal (fail-closed error screen, no retry).
 *
 * The cause is NOT proven, and this batch does not claim it. [LeaseOutcome.NotReady] has three
 * distinct sub-sources: (a) the facade is not wired (`facade == null`); (b) the runtime is not
 * `Ready` or has no `activeGeneration`; (c) the non-blocking `mutex.tryLock()` was momentarily
 * busy. D-214's interpretation rule states all three, and reviewer counter-evidence refutes the
 * "the sibling 55ms catalog query holds the lease" explanation: `acquireLease()` releases the
 * mutex in its `finally` BEFORE `leased` runs the block, so a sustained query never holds that
 * mutex and the tryLock collision window is microseconds. Fix A (this batch) therefore emits a
 * `read.leaseNotReady reason=<facadeNull|stateNotReady|generationAbsent|mutexBusy>` trace line at
 * each origin to attribute the sub-source empirically; the retry does not depend on the answer.
 *
 * Retry scope (honest): because only `LeaseOutcome.NotReady` is observable at the loop's decision
 * point — not its sub-source — the retry covers ALL THREE sub-sources. That is deliberate: at the
 * initial-startup moment all three are plausibly transient (facade wiring, a Ready transition, and
 * the tryLock race all resolve within the composition's startup window), the added window is
 * bounded (at most `(MAX_ATTEMPTS - 1) * RETRY_DELAY_MS` = 300ms), and a genuine lifecycle failure
 * is still surfaced unchanged after exhaustion. The read is read-only (a plain
 * `queryCurrentState.query()` plus a pin preference read), so re-attempting the SAME lease
 * acquisition creates or replaces no ledger state. A real failure — `readThrew`, any `readResult`
 * variant, or a stale generation — arrives as [LeaseOutcome.Completed] and is never retried.
 */
internal object InitialReadLeaseRetry {
    /**
     * Total acquisition attempts for the initial read: the initial attempt plus three retries.
     * The added window is tens of milliseconds in the common case and at most 300ms.
     */
    const val MAX_ATTEMPTS = 4

    /**
     * Delay before each re-attempt; worst case adds
     * `(MAX_ATTEMPTS - 1) * RETRY_DELAY_MS` = 300ms to the initial load.
     */
    const val RETRY_DELAY_MS = 100L

    /**
     * The D-214 cause token for a bare lease refusal. Single source of truth: the production
     * failure trace interpolates THIS constant (never a duplicated literal), and
     * [shouldRetryCause] compares against it.
     */
    const val LEASE_NOT_READY_CAUSE = "leaseNotReady"

    /**
     * True exactly for the transient [LEASE_NOT_READY_CAUSE] token; false for the D-214
     * `readThrew errorType=...` and `readResult variant=...` causes and for anything else, so a
     * real read failure is never retried by this classifier.
     */
    fun shouldRetryCause(cause: String): Boolean = cause == LEASE_NOT_READY_CAUSE

    /**
     * Pure attempt bound: true while fewer than [MAX_ATTEMPTS] attempts have been made.
     * [attemptsMade] counts attempts already performed, so `attemptsMade == MAX_ATTEMPTS` means
     * the bound is exhausted.
     */
    fun attemptsRemaining(attemptsMade: Int): Boolean = attemptsMade < MAX_ATTEMPTS

    /**
     * Pure bounded decision: retry while the bound remains and the cause is retryable. The
     * production loop passes [LEASE_NOT_READY_CAUSE] (the only cause it can observe) and also
     * requires `outcome is LeaseOutcome.NotReady`, so the loop terminates at [MAX_ATTEMPTS].
     */
    fun shouldRetry(
        attemptsMade: Int,
        cause: String,
    ): Boolean = attemptsRemaining(attemptsMade) && shouldRetryCause(cause)
}
