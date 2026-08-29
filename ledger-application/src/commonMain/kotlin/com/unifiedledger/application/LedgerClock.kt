package com.unifiedledger.application

import kotlin.time.Instant

/**
 * Runtime clock port owned by the application layer (P5-02, IMP-4).
 *
 * The port supplies processing, creation, confirmation, and audit-event time only. Source
 * occurrence, payment, posting, accrual, and observation times are immutable source facts and
 * MUST never be rewritten or backfilled from this clock.
 */
fun interface LedgerClock {
    fun now(): Instant
}
