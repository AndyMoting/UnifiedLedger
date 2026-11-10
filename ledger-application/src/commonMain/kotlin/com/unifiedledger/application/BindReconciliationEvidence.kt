package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.LedgerId
import kotlin.time.Instant

/**
 * P8-01a i2 evidence binding (spec section 2.4; D-308 item 10).
 *
 * The one explicit user decision of the slice: "this import row belongs to this account". It is
 * carried out by the existing `P408EvidenceProjectionPort.materialize` — the first production
 * caller of that port — with the target account the user named and the target currency and
 * precision read from that account's catalog row.
 *
 * DERIVED WRITE, ZERO ACCOUNTING EFFECT: only an `evidence_projection` row can appear. No
 * transaction, posting, version, balance or report is created or changed, and no reconciliation
 * status moves. An identical replay is [P408MaterializeResult.NoChange] with no new row.
 *
 * REFUSED BEFORE THE WRITE when the projection port could not decide honestly on its own: an
 * evidence already projected `READY` to a DIFFERENT account (projection content is immutable, so
 * the bind cannot be redirected), an evidence whose source raw facts are unresolved (materialize
 * would otherwise persist a `REJECTED` terminal row for what is really a caller-side gap), and a
 * target account that is absent from the catalog or fails the spec section 2.1 composite mark.
 * Every refusal is typed and writes nothing.
 *
 * WHY THE MARK MATTERS HERE (spec sections 2.1/2.4/5): projection content is immutable and the
 * slice offers no in-app re-bind, so binding to an account that fails the section 2.1 mark (a
 * system/external or non-asset/liability account) would create a
 * `READY` row that can never enter the queue or the candidate set and can never be re-pointed.
 * The §2.1 composite mark (`owned_by_user = 1 AND real_account = 1 AND kind IN
 * ('ASSET','LIABILITY')`; `active` is not part of it) is therefore a precondition, not a filter.
 */
class BindReconciliationEvidence(
    private val readPort: P801AccountReconciliationReadPort,
    private val projections: P408EvidenceProjectionPort,
    private val catalog: CatalogAdmissionReader,
    private val idSource: P801ReconciliationIdSource,
) {
    fun bind(request: P801BindEvidenceRequest): P801BindEvidenceResult {
        val evidence =
            when (val loaded = readPort.loadCandidateEvidenceById(request.ledgerId, request.evidenceId)) {
                is P801CandidateEvidenceResult.Success ->
                    loaded.rows.firstOrNull() ?: return P801BindEvidenceResult.Refused(P801BindEvidenceRefusal.EVIDENCE_NOT_FOUND)
                is P801CandidateEvidenceResult.Failed -> return P801BindEvidenceResult.Refused(P801BindEvidenceRefusal.EVIDENCE_READ_UNAVAILABLE)
            }
        val current = evidence.projection
        if (current != null && current.state == P408ProjectionState.READY && current.targetAccountId != request.targetAccountId.value) {
            return P801BindEvidenceResult.Refused(P801BindEvidenceRefusal.PROJECTION_TARGETS_ANOTHER_ACCOUNT)
        }
        // The projection's own raw-fact gate would persist a REJECTED terminal row for this; the
        // spec reserves REJECTED for a refusal of the account decision, not for a caller that
        // picked an evidence whose economic facts were never imported (D-111).
        if (evidence.rawAmountMinor == null ||
            evidence.rawCurrencyCode == null ||
            evidence.rawCurrencyPrecision == null ||
            evidence.rawDirectionToken == null
        ) {
            return P801BindEvidenceResult.Refused(P801BindEvidenceRefusal.SOURCE_FACTS_UNRESOLVED)
        }
        val account =
            catalog.loadCurrent(request.ledgerId)?.accounts?.firstOrNull { it.id == request.targetAccountId }
                ?: return P801BindEvidenceResult.Refused(P801BindEvidenceRefusal.TARGET_ACCOUNT_NOT_IN_CATALOG)
        if (!isReconcilableTarget(account)) {
            return P801BindEvidenceResult.Refused(P801BindEvidenceRefusal.TARGET_ACCOUNT_NOT_RECONCILABLE)
        }
        return when (
            val materialized =
                projections.materialize(
                    P408MaterializationRequest(
                        ledgerId = request.ledgerId.value,
                        requestId = idSource.next().requestId,
                        evidenceId = request.evidenceId,
                        targetAccountId = account.id.value,
                        targetCurrencyCode = account.currency.code,
                        targetCurrencyPrecision = account.currency.precision,
                        materializedAt = request.materializedAt.toString(),
                    ),
                )
        ) {
            is P408MaterializeResult.Accepted -> P801BindEvidenceResult.Bound(materialized.projection)
            is P408MaterializeResult.NoChange -> P801BindEvidenceResult.NoChange(materialized.projection)
            is P408MaterializeResult.Rejected -> P801BindEvidenceResult.Rejected(materialized.code)
        }
    }

    /** The spec section 2.1 composite mark, shared by the queue and this pre-write gate. */
    private fun isReconcilableTarget(account: Account): Boolean =
        account.ownedByUser &&
            account.realAccount &&
            account.kind in setOf(AccountKind.ASSET, AccountKind.LIABILITY)
}

/** One explicit bind decision: this evidence belongs to this account. */
data class P801BindEvidenceRequest(
    val ledgerId: LedgerId,
    val evidenceId: String,
    val targetAccountId: AccountId,
    /**
     * Provenance time of the projection row, supplied by the caller — the projection port forbids
     * reading a runtime clock for it, and no default is offered so a caller cannot silently
     * timestamp a bind it did not make.
     */
    val materializedAt: Instant,
)

/** The result of one bind attempt. */
sealed interface P801BindEvidenceResult {
    /** First materialization of this exact projection content. */
    data class Bound(
        val projection: P408EvidenceProjection,
    ) : P801BindEvidenceResult

    /** Identical replay; nothing was appended and the stored projection is returned. */
    data class NoChange(
        val projection: P408EvidenceProjection,
    ) : P801BindEvidenceResult

    /** The projection port refused the bind; the code is rendered as-is. */
    data class Rejected(
        val code: String,
    ) : P801BindEvidenceResult

    /** The use case refused before any write. */
    data class Refused(
        val reason: P801BindEvidenceRefusal,
    ) : P801BindEvidenceResult
}

/** Why a bind was refused before reaching the projection port (each writes nothing). */
enum class P801BindEvidenceRefusal {
    /** No such evidence in this ledger. */
    EVIDENCE_NOT_FOUND,

    /** The candidate-evidence read failed; never treated as "no evidence" (D-089). */
    EVIDENCE_READ_UNAVAILABLE,

    /** The evidence is already `READY`-projected to a different account; immutable, so refused. */
    PROJECTION_TARGETS_ANOTHER_ACCOUNT,

    /** The source's raw economic facts are absent, so there is nothing to project (D-111). */
    SOURCE_FACTS_UNRESOLVED,

    /** The catalog does not hold the target account, so its currency and precision are unknown. */
    TARGET_ACCOUNT_NOT_IN_CATALOG,

    /**
     * The target account exists but fails the spec section 2.1 composite mark (system, external or
     * non-asset/liability): a `READY` projection there could never enter the queue or
     * candidates, and the immutable projection could never be re-pointed in-app (spec section 5).
     */
    TARGET_ACCOUNT_NOT_RECONCILABLE,
}
