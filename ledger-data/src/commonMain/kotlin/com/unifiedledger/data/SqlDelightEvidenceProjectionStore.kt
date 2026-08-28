package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.P408EvidenceProjection
import com.unifiedledger.application.P408EvidenceProjectionPort
import com.unifiedledger.application.P408MaterializationRequest
import com.unifiedledger.application.P408MaterializeResult
import com.unifiedledger.application.P408ProjectionState
import com.unifiedledger.data.db.Evidence_projection
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.data.db.SelectP408EvidenceSourceFacts

/**
 * Sole product writer of `evidence_projection` (approved implementation spec,
 * sections 4/V-1..V-5; D-112). Rows are immutable terminal states; materialization
 * performs exact integer normalization (equal precision / exact zero-pad scale-up /
 * exact divisor scale-down) and refuses remainders, overflow, and currency
 * mismatches with typed codes from the frozen V-4 family. Runtime callers never
 * normalize amounts themselves.
 *
 * Persistence boundary (UQ-2 = V-5-A):
 *  - [materialize] (standalone / explicit target) owns its transaction and
 *    persists the resulting READY or REJECTED terminal row;
 *  - [resolutionFor] + [insertIfAbsent] are the transaction-sharing core used by
 *    confirm-path hooks (spine success path and confirmLink lazy
 *    materialization): callers run inside their own active transaction and any
 *    failure there aborts that whole transaction so ZERO projection rows survive
 *    a failed confirmation.
 *
 * Normalization implemented here mirrors ledger-application
 * `normalizeSourceMinorExact` semantics exactly (application `internal`
 * visibility prevents direct reuse); the TP-02..TP-07 matrix pins equivalence.
 */
class SqlDelightEvidenceProjectionStore private constructor(
    private val database: LedgerDatabase,
) : P408EvidenceProjectionPort {
    internal constructor(database: LedgerDatabase, driver: SqlDriver) : this(database) {
        configureSqliteConnection(driver)
    }

    override fun readProjection(
        ledgerId: String,
        evidenceId: String,
    ): P408EvidenceProjection? =
        database.ledgerQueries
            .selectP408EvidenceProjection(ledgerId, evidenceId)
            .executeAsOneOrNull()
            ?.toModel()

    /** Standalone/explicit-target entry point; owns its transaction. */
    override fun materialize(request: P408MaterializationRequest): P408MaterializeResult =
        try {
            database.transactionWithResult {
                when (val resolution = resolutionFor(request)) {
                    is Resolution.Existing ->
                        if (resolution.matchesDesired) {
                            P408MaterializeResult.NoChange(resolution.row.toModel())
                        } else {
                            throw ProjectionTypedRollback(CODE_STATE_CONFLICT)
                        }
                    is Resolution.Fresh ->
                        when {
                            resolution.desired.state == P408ProjectionState.READY -> {
                                insert(resolution.desired.model)
                                P408MaterializeResult.Accepted(resolution.desired.model)
                            }
                            // A readable source echo is required before any
                            // REJECTED terminal row may be persisted.
                            resolution.desired.model.sourceId
                                .isNotEmpty() -> {
                                insert(resolution.desired.model)
                                P408MaterializeResult.Rejected(
                                    resolution.desired.model.rejectionCode
                                        ?: CODE_AMOUNT_NOT_REPRESENTABLE,
                                )
                            }
                            else ->
                                P408MaterializeResult.Rejected(
                                    resolution.desired.model.rejectionCode ?: CODE_SOURCE_DRIFT,
                                )
                        }
                }
            }
        } catch (failure: Throwable) {
            if (failure is ProjectionTypedRollback) {
                P408MaterializeResult.Rejected(failure.code)
            } else {
                throw failure
            }
        }

    /**
     * Compute-or-fetch semantics without writing: [Resolution.Fresh] carries the
     * deterministic desired row (READY or REJECTED with its V-4 rejection code);
     * [Resolution.Existing] carries the stored row plus a full-content equality
     * verdict against the freshly computed desired row.
     */
    internal fun resolutionFor(request: P408MaterializationRequest): Resolution {
        val source =
            database.ledgerQueries
                .selectP408EvidenceSourceFacts(request.ledgerId, request.evidenceId)
                .executeAsOneOrNull()
                ?: return missingSource()
        val rawAmount = source.amount_minor ?: return missingSource()
        val rawPrecision = source.currency_precision?.toInt() ?: return missingSource()
        val directionToken = source.direction_token ?: return missingSource()

        val desired = desiredRow(request, source, rawAmount, rawPrecision, directionToken)
        val existing =
            database.ledgerQueries
                .selectP408EvidenceProjection(request.ledgerId, request.evidenceId)
                .executeAsOneOrNull()
                ?: return Resolution.Fresh(desired)
        val model = existing.toModel()
        return Resolution.Existing(
            row = existing,
            matchesDesired = economicEquals(model, desired.model),
            desired = desired,
        )
    }

    /**
     * QUAL-001 (dual-review verdict): an already-materialized READY row is
     * accepted whenever its financial content and identity fields match the
     * freshly computed desired row; materializationRequestId/materializedAt are
     * audit provenance and never participate in equality (a spine-path
     * materialization and a later confirmLink lazy verification carry different
     * provenance by design). Everything else — target/currency/precision,
     * normalized amount, raw echoes, direction, state/rejection, projection and
     * rule identity — is equality-bearing.
     */
    private fun economicEquals(
        left: P408EvidenceProjection,
        right: P408EvidenceProjection,
    ): Boolean =
        left.projectionId == right.projectionId &&
            left.evidenceId == right.evidenceId &&
            left.sourceId == right.sourceId &&
            // sourceHash deliberately excluded: it is liveness/raw-echo audit owned by
            // the P408_PROJECTION_SOURCE_DRIFT gate check, not an economic identity.
            left.targetAccountId == right.targetAccountId &&
            left.currencyCode == right.currencyCode &&
            left.currencyPrecision == right.currencyPrecision &&
            left.rawAmountMinor == right.rawAmountMinor &&
            left.rawCurrencyPrecision == right.rawCurrencyPrecision &&
            left.normalizedAmountMinor == right.normalizedAmountMinor &&
            left.directionToken == right.directionToken &&
            left.state == right.state &&
            left.rejectionCode == right.rejectionCode &&
            left.ruleId == right.ruleId &&
            left.ruleVersion == right.ruleVersion

    private fun missingSource(): Resolution = Resolution.Fresh(rejectedWithoutSource(CODE_PROJECTION_ABSENT))

    /**
     * Correction-authority content matching: [economicEquals] minus projectionId.
     * After a re-expression the CURRENT row carries a generation-suffixed
     * projection id while the desired row keeps the base id, so projection id
     * must not participate when the gate asks "does the current authority already
     * express the desired target/facts".
     */
    private fun contentEquals(
        left: P408EvidenceProjection,
        right: P408EvidenceProjection,
    ): Boolean =
        left.evidenceId == right.evidenceId &&
            left.sourceId == right.sourceId &&
            left.targetAccountId == right.targetAccountId &&
            left.currencyCode == right.currencyCode &&
            left.currencyPrecision == right.currencyPrecision &&
            left.rawAmountMinor == right.rawAmountMinor &&
            left.rawCurrencyPrecision == right.rawCurrencyPrecision &&
            left.normalizedAmountMinor == right.normalizedAmountMinor &&
            left.directionToken == right.directionToken &&
            left.state == right.state &&
            left.rejectionCode == right.rejectionCode &&
            left.ruleId == right.ruleId &&
            left.ruleVersion == right.ruleVersion

    /**
     * Insert-only write for a fully resolved desired row. The partial unique
     * index over the current authorities (superseded_by_projection_id IS NULL,
     * D-113) makes concurrent double-materialization impossible without
     * conflicting, and the update/delete guards forbid any mutation afterwards.
     */
    internal fun insertIfAbsent(desired: Resolution.Fresh) {
        insert(desired.desired.model)
    }

    private fun insert(model: P408EvidenceProjection) {
        database.ledgerQueries.insertEvidenceProjection(
            ledger_id = model.ledgerId,
            projection_id = model.projectionId,
            evidence_id = model.evidenceId,
            source_id = model.sourceId,
            source_hash = model.sourceHash,
            target_account_id = model.targetAccountId,
            currency_code = model.currencyCode,
            currency_precision = model.currencyPrecision.toLong(),
            raw_amount_minor = model.rawAmountMinor,
            raw_currency_precision = model.rawCurrencyPrecision.toLong(),
            normalized_amount_minor = model.normalizedAmountMinor,
            direction_token = model.directionToken,
            state = model.state.storageValue,
            rejection_code = model.rejectionCode,
            rule_id = model.ruleId,
            rule_version = model.ruleVersion.toLong(),
            materialization_request_id = model.materializationRequestId,
            materialized_at = model.materializedAt,
        )
    }

    private fun desiredRow(
        request: P408MaterializationRequest,
        source: SelectP408EvidenceSourceFacts,
        rawAmount: Long,
        rawPrecision: Int,
        directionToken: String,
    ): Desired {
        if (source.currency_code != request.targetCurrencyCode) {
            return rejected(request, source, rawAmount, rawPrecision, directionToken, CODE_CURRENCY_MISMATCH)
        }
        val normalized =
            when (val outcome = normalizeExact(rawAmount, rawPrecision, request.targetCurrencyPrecision)) {
                is Normalized.Value -> outcome.value
                is Normalized.Refusal -> return rejected(request, source, rawAmount, rawPrecision, directionToken, outcome.code)
            }
        return Desired(
            P408EvidenceProjection(
                ledgerId = request.ledgerId,
                projectionId = projectionIdFor(request.evidenceId),
                evidenceId = request.evidenceId,
                sourceId = source.source_id,
                sourceHash = source.content_hash,
                targetAccountId = request.targetAccountId,
                currencyCode = request.targetCurrencyCode,
                currencyPrecision = request.targetCurrencyPrecision,
                rawAmountMinor = rawAmount,
                rawCurrencyPrecision = rawPrecision,
                normalizedAmountMinor = normalized,
                directionToken = directionToken,
                state = P408ProjectionState.READY,
                rejectionCode = null,
                ruleId = P408EvidenceProjectionPort.RULE_ID,
                ruleVersion = P408EvidenceProjectionPort.RULE_VERSION,
                materializationRequestId = request.requestId,
                materializedAt = request.materializedAt,
            ),
            P408ProjectionState.READY,
        )
    }

    private fun rejected(
        request: P408MaterializationRequest,
        source: SelectP408EvidenceSourceFacts?,
        rawAmount: Long,
        rawPrecision: Int,
        directionToken: String,
        code: String,
    ): Desired =
        Desired(
            P408EvidenceProjection(
                ledgerId = request.ledgerId,
                projectionId = projectionIdFor(request.evidenceId),
                evidenceId = request.evidenceId,
                sourceId = source?.source_id ?: "",
                sourceHash = source?.content_hash ?: "",
                targetAccountId = request.targetAccountId,
                currencyCode = request.targetCurrencyCode,
                currencyPrecision = request.targetCurrencyPrecision,
                rawAmountMinor = rawAmount,
                rawCurrencyPrecision = rawPrecision,
                normalizedAmountMinor = 0L,
                directionToken = directionToken,
                state = P408ProjectionState.REJECTED,
                rejectionCode = code,
                ruleId = P408EvidenceProjectionPort.RULE_ID,
                ruleVersion = P408EvidenceProjectionPort.RULE_VERSION,
                materializationRequestId = request.requestId,
                materializedAt = request.materializedAt,
            ),
            P408ProjectionState.REJECTED,
        )

    /** Terminal REJECTED seed used when the source echo itself cannot be read. */
    private fun rejectedWithoutSource(code: String): Desired = rejected(emptyRequest(), null, 0L, 0, "out", code)

    private fun emptyRequest(): P408MaterializationRequest =
        P408MaterializationRequest(
            ledgerId = "",
            requestId = "",
            evidenceId = "",
            targetAccountId = "",
            targetCurrencyCode = "",
            targetCurrencyPrecision = 0,
            materializedAt = "",
        )

    private fun Evidence_projection.toModel(): P408EvidenceProjection =
        P408EvidenceProjection(
            ledgerId = ledger_id,
            projectionId = projection_id,
            evidenceId = evidence_id,
            sourceId = source_id,
            sourceHash = source_hash,
            targetAccountId = target_account_id,
            currencyCode = currency_code,
            currencyPrecision = currency_precision.toInt(),
            rawAmountMinor = raw_amount_minor,
            rawCurrencyPrecision = raw_currency_precision.toInt(),
            normalizedAmountMinor = normalized_amount_minor,
            directionToken = direction_token,
            state = P408ProjectionState.fromStorage(state),
            rejectionCode = rejection_code,
            ruleId = rule_id,
            ruleVersion = rule_version.toInt(),
            materializationRequestId = materialization_request_id,
            materializedAt = materialized_at,
        )

    /** Deterministic projection identity derived from the unique evidence key. */
    companion object {
        /** Same-module factory for sibling stores sharing one open database. */
        internal fun createShared(database: LedgerDatabase): SqlDelightEvidenceProjectionStore = SqlDelightEvidenceProjectionStore(database)

        const val CODE_CURRENCY_MISMATCH = "P408_PROJECTION_CURRENCY_MISMATCH"
        const val CODE_AMOUNT_NOT_REPRESENTABLE = "P408_PROJECTION_AMOUNT_NOT_REPRESENTABLE"
        const val CODE_ARITHMETIC_OVERFLOW = "P408_PROJECTION_ARITHMETIC_OVERFLOW"
        const val CODE_STATE_CONFLICT = "P408_PROJECTION_STATE_CONFLICT"
        const val CODE_SOURCE_DRIFT = "P408_PROJECTION_SOURCE_DRIFT"

        // SPEC-001 closure: P408_PROJECTION_TARGET_ACCOUNT_MISSING is eliminated by
        // construction — P408MaterializationRequest.targetAccountId is a required
        // non-blank field (application data-class require), so an explicit target
        // binding always rides on every materialization. The code is recorded in the
        // V-2/V-4 engineering-adjudication ownership (D-112 implementation
        // registration); no trigger path exists and none is registered.
        const val CODE_PROJECTION_ABSENT = "P408_PROJECTION_ABSENT"
        const val CODE_PROJECTION_NOT_READY = "P408_PROJECTION_NOT_READY"

        fun projectionIdFor(evidenceId: String): String = projectionIdFor(evidenceId, 1)

        /**
         * Deterministic generation-aware projection identity: the first row keeps
         * the v26-era id shape and every controlled re-expression (D-113) appends
         * an increasing suffix, so successor rows never collide.
         */
        fun projectionIdFor(
            evidenceId: String,
            generation: Int,
        ): String = if (generation <= 1) "proj-$evidenceId" else "proj-$evidenceId-$generation"
    }

    /**
     * Confirm-path gate helper (TP-13/V-6-A): resolve-or-lazily-materialize the
     * authority inside the CALLER's active transaction. Never persists a row on
     * any non-READY outcome (E: zero writes beyond claims/links/reconciliation —
     * and none of those happen either, because the caller aborts on NotReady).
     */
    fun ensureReadyWithinTransaction(request: P408MaterializationRequest): EnsureReadyResult =
        when (val resolution = resolutionFor(request)) {
            is Resolution.Existing -> {
                val model = resolution.row.toModel()
                if (model.state == P408ProjectionState.READY && resolution.matchesDesired) {
                    EnsureReadyResult.Ready(model)
                } else if (model.state == P408ProjectionState.REJECTED) {
                    EnsureReadyResult.NotReady(CODE_PROJECTION_NOT_READY)
                } else {
                    EnsureReadyResult.NotReady(CODE_STATE_CONFLICT)
                }
            }
            is Resolution.Fresh ->
                when {
                    resolution.desired.state == P408ProjectionState.READY -> {
                        insertIfAbsent(resolution)
                        EnsureReadyResult.Ready(resolution.desired.model)
                    }
                    else ->
                        EnsureReadyResult.NotReady(
                            resolution.desired.model.rejectionCode ?: CODE_SOURCE_DRIFT,
                        )
                }
        }

    /**
     * D-113 correction-path authority (spec section 8, V-E-A): ensure the
     * CURRENT projection for the evidence matches the desired re-expression,
     * running inside the CALLER's active transaction.
     *
     * - current row content equals the desired content -> zero writes;
     * - desired content is not READY -> NotReady with the frozen V-4 code;
     * - current row is READY-but-different or REJECTED, and the desired row is
     *   READY -> controlled supersede: freeze the current row through the
     *   one-shot superseded_by_projection_id transition, then insert the
     *   successor projection row INSIDE THIS SAME TRANSACTION. The supersede
     *   MUST precede the insert so the partial unique index never observes two
     *   current rows for one evidence (concurrent losers abort via the trigger
     *   or the partial unique index instead of creating two authorities).
     */
    fun ensureCurrentForCorrection(request: P408MaterializationRequest): EnsureReadyResult =
        when (val resolution = resolutionFor(request)) {
            is Resolution.Fresh ->
                when {
                    resolution.desired.state == P408ProjectionState.READY -> {
                        insertIfAbsent(resolution)
                        EnsureReadyResult.Ready(resolution.desired.model)
                    }
                    else ->
                        EnsureReadyResult.NotReady(
                            resolution.desired.model.rejectionCode ?: CODE_SOURCE_DRIFT,
                        )
                }
            is Resolution.Existing -> {
                val model = resolution.row.toModel()
                when {
                    model.state == P408ProjectionState.READY && contentEquals(model, resolution.desired.model) ->
                        EnsureReadyResult.Ready(model)
                    resolution.desired.state != P408ProjectionState.READY ->
                        EnsureReadyResult.NotReady(
                            resolution.desired.model.rejectionCode ?: CODE_SOURCE_DRIFT,
                        )
                    else -> {
                        // Re-expression: supersede the current row, then insert the
                        // successor row. The generation index keeps projection ids
                        // deterministic per serialized correction.
                        val generation =
                            database.ledgerQueries
                                .selectP408EvidenceProjectionCountForEvidence(model.ledgerId, model.evidenceId)
                                .executeAsOne() + 1L
                        val replacement =
                            resolution.desired.model.copy(
                                projectionId = projectionIdFor(model.evidenceId, generation.toInt()),
                            )
                        // Store-level supersede integrity (QUAL-006): the successor
                        // keeps the same evidence and is a fresh row by construction;
                        // DB backstops are the partial unique index and the projection
                        // PK (no trigger-level DDL beyond the approved surface).
                        check(replacement.evidenceId == model.evidenceId) { "correction projection successor must keep the same evidence" }
                        check(replacement.projectionId != model.projectionId) { "correction projection successor must be a fresh row" }
                        database.ledgerQueries.supersedeP408EvidenceProjection(
                            superseded_by_projection_id = replacement.projectionId,
                            ledger_id = model.ledgerId,
                            projection_id = model.projectionId,
                        )
                        insert(replacement)
                        EnsureReadyResult.Ready(replacement)
                    }
                }
            }
        }

    sealed interface EnsureReadyResult {
        data class Ready(
            val projection: P408EvidenceProjection,
        ) : EnsureReadyResult

        data class NotReady(
            val code: String,
        ) : EnsureReadyResult
    }

    internal data class Desired(
        val model: P408EvidenceProjection,
        val state: P408ProjectionState,
    )

    internal sealed interface Resolution {
        /** No row existed yet; the deterministic desired row is ready to insert. */
        data class Fresh(
            val desired: Desired,
        ) : Resolution

        /** Row already present; verdict compares stored content against fresh computation. */
        data class Existing(
            val row: Evidence_projection,
            val matchesDesired: Boolean,
            val desired: Desired,
        ) : Resolution
    }

    private class ProjectionTypedRollback(
        val code: String,
    ) : RuntimeException()
}

private sealed interface Normalized {
    data class Value(
        val value: Long,
    ) : Normalized

    data class Refusal(
        val code: String,
    ) : Normalized
}

/**
 * Exact integer rescaling mirroring application-level `normalizeSourceMinorExact`
 * (D-111): power-of-ten multiplication with overflow detection, exact divisor
 * downscale, remainder/overshoot refusal, and zero staying exactly zero at any
 * scale. Never binary floating point.
 */
private fun normalizeExact(
    amountMinor: Long,
    sourceScale: Int,
    targetPrecision: Int,
): Normalized {
    if (sourceScale < 0 || targetPrecision < 0) {
        return Normalized.Refusal(SqlDelightEvidenceProjectionStore.CODE_AMOUNT_NOT_REPRESENTABLE)
    }
    if (targetPrecision >= sourceScale) {
        var factor = 1L
        repeat(targetPrecision - sourceScale) {
            if (factor > Long.MAX_VALUE / 10) {
                return Normalized.Refusal(SqlDelightEvidenceProjectionStore.CODE_ARITHMETIC_OVERFLOW)
            }
            factor *= 10
        }
        if (amountMinor != 0L &&
            factor > 1 &&
            (amountMinor > Long.MAX_VALUE / factor || amountMinor < Long.MIN_VALUE / factor)
        ) {
            return Normalized.Refusal(SqlDelightEvidenceProjectionStore.CODE_ARITHMETIC_OVERFLOW)
        }
        return Normalized.Value(amountMinor * factor)
    }
    val difference = sourceScale - targetPrecision
    if (amountMinor == 0L) return Normalized.Value(0L)
    if (difference > 18) {
        return Normalized.Refusal(SqlDelightEvidenceProjectionStore.CODE_AMOUNT_NOT_REPRESENTABLE)
    }
    var divisor = 1L
    repeat(difference) { divisor *= 10 }
    if (amountMinor % divisor != 0L) {
        return Normalized.Refusal(SqlDelightEvidenceProjectionStore.CODE_AMOUNT_NOT_REPRESENTABLE)
    }
    return Normalized.Value(amountMinor / divisor)
}
