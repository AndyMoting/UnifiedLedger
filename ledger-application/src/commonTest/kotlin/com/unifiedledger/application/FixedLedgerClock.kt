package com.unifiedledger.application

import kotlin.time.Instant

/**
 * Deterministic [LedgerClock] test fixture (P5-02, IMP-5). Returns the injected [instant] on
 * every read. Test and golden fixtures only; never used in a product composition root.
 */
class FixedLedgerClock(
    private val instant: Instant,
) : LedgerClock {
    override fun now(): Instant = instant
}
