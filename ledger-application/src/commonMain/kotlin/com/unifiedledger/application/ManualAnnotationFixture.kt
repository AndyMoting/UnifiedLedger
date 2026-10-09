package com.unifiedledger.application

import kotlin.time.Instant

/**
 * P7-08 08.B-1 (D-221; ruling R3-fallout / C4): the frozen module-level fixture `created_at` used
 * ONLY by the golden replay adapters ([GoldenManualExpenseAdapter], [Rg02ManualIncomeAdapter]).
 *
 * Golden replay requires byte-deterministic output; injecting the product wall clock would make the
 * oracle non-deterministic, so the annotation audit time is a fixed fixture constant here. This
 * constant is NOT a product wall-clock and does NOT leak into the product write path (the product
 * path always samples the injected `LedgerClock` once and passes the value down).
 */
internal val GOLDEN_MANUAL_ANNOTATION_CREATED_AT: Instant = Instant.parse("2026-01-01T00:00:00Z")
