package com.unifiedledger.application

/**
 * P4-08 normalized evidence projection contract (approved implementation spec,
 * sections 4/V-1 and 5/V-2..V-5; D-112).
 *
 * The projection is the only matching authority for mirror evidence: it is an
 * immutable terminal-state row derived deterministically from untouched source
 * facts plus explicitly confirmed target inputs. Runtime callers may never
 * normalize amounts themselves; they either consume a READY projection or stay
 * unresolved/rejected with zero writes.
 */
enum class P408ProjectionState(val storageValue: String) {
    READY("READY"),
    REJECTED("REJECTED");

    companion object {
        fun fromStorage(value: String): P408ProjectionState =
            values().first { it.storageValue == value }
    }
}

/** Immutable read model of one evidence_projection row. */
data class P408EvidenceProjection(
    val ledgerId: String,
    val projectionId: String,
    val evidenceId: String,
    val sourceId: String,
    val sourceHash: String,
    val targetAccountId: String,
    val currencyCode: String,
    val currencyPrecision: Int,
    val rawAmountMinor: Long,
    val rawCurrencyPrecision: Int,
    val normalizedAmountMinor: Long,
    val directionToken: String,
    val state: P408ProjectionState,
    val rejectionCode: String?,
    val ruleId: String,
    val ruleVersion: Int,
    val materializationRequestId: String,
    val materializedAt: String,
)

/**
 * Explicit materialization input. Target account/currency/precision come from
 * caller-held explicit decision bindings; raw facts are re-read by the store
 * from import_source_record so no second copy of economic truth exists. Times
 * are provenance strings supplied by the caller (never runtime Clock output).
 */
data class P408MaterializationRequest(
    val ledgerId: String,
    val requestId: String,
    val evidenceId: String,
    val targetAccountId: String,
    val targetCurrencyCode: String,
    val targetCurrencyPrecision: Int,
    val materializedAt: String,
)

sealed interface P408MaterializeResult {
    /** First insertion of this exact projection content. */
    data class Accepted(val projection: P408EvidenceProjection) : P408MaterializeResult

    /** Identical replay of an already-materialized row; nothing appended. */
    data class NoChange(val projection: P408EvidenceProjection) : P408MaterializeResult

    /**
     * Typed failure. On the standalone/explicit path a REJECTED terminal row is
     * persisted before returning; on confirm-path hooks the whole surrounding
     * transaction aborts instead, so zero rows remain.
     */
    data class Rejected(val code: String) : P408MaterializeResult
}

interface P408EvidenceProjectionPort {
    fun readProjection(ledgerId: String, evidenceId: String): P408EvidenceProjection?

    fun materialize(request: P408MaterializationRequest): P408MaterializeResult

    companion object {
        /** Frozen runtime rule identity (UQ-5 register; backfill uses its own token). */
        const val RULE_ID: String = "p408_evidence_projection_v1"
        const val RULE_VERSION: Int = 1
    }
}
