package com.unifiedledger.application

/**
 * P8-01a i2 reconciliation identity: the ids one reconciliation write needs, minted by the
 * ledger's existing identity source (UUIDv7 in both composition roots).
 *
 * [linkId] and [reconciliationId] are unused by the bind step, which needs only [requestId]; one
 * source is kept for both writes so the graph wires one identity source per surface rather than
 * two that could drift apart.
 */
data class P801ReconciliationIds(
    val requestId: String,
    val linkId: String,
    val reconciliationId: String,
)

fun interface P801ReconciliationIdSource {
    fun next(): P801ReconciliationIds
}
