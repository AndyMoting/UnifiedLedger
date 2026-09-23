package com.unifiedledger.ui

import com.unifiedledger.application.CatalogSnapshotView
import com.unifiedledger.application.CorrectTransactionVersionResult
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.ExplicitlyConfirmedTransactionCorrection
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.ParseManualExpenseOccurredAt
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.TransactionCorrectionCommitResolution
import com.unifiedledger.application.TransactionCorrectionRequestIdentity
import com.unifiedledger.application.TransactionCorrectionRequestSnapshot
import com.unifiedledger.application.TransactionDetail
import com.unifiedledger.application.TransactionVoidCommitResolution
import com.unifiedledger.application.TransactionVoidRequestIdentity
import com.unifiedledger.application.TransactionVoidRequestSnapshot
import com.unifiedledger.application.VoidTransactionRequest
import com.unifiedledger.application.VoidTransactionResult
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.P705FailureCode
import com.unifiedledger.domain.TransactionVoidFactKind
import com.unifiedledger.domain.VoidReason

// P7-05 slice 1b Piece 4 host wiring support (D-156; spec sections 3.2/3.3/3.5/4.2). These are the
// pure, JVM-assertable decisions the composition-root call sites in P503App.kt delegate to: the
// host-resolved old-value snapshot of a correction target (from the authoritative detail plus the
// current catalog), the request builders of the correction/void/restore commits, and the
// snapshot-aware unknown-commit resolution mapping (spec section 4.2). No Compose, no IO and no
// logging lives here — in particular the void/restore reason and its note are only ever carried,
// never printed (V-21).

/**
 * P7-05.B (spec section 3.2; the Piece 3 residual): the host-resolved old-value snapshot of the
 * correction target, derived from the authoritative detail payload plus the current catalog. The
 * CAS token ([TransactionDetail.currentVersionId]) rides the detail; the amount is the detail's
 * positive leg formatted at ITS OWN currency precision (the transaction's actual currency, not the
 * ledger default — the Piece 3 preview assumption is retired here); the category/funding references
 * are resolved against the current catalog by their posting-account mapping, since the detail legs
 * carry names rather than ids. `null` when the amount leg or its currency cannot be resolved, so the
 * caller offers no edit affordance instead of a dead button.
 */
internal fun transactionEditOriginFromDetail(
    detail: TransactionDetail,
    snapshot: CatalogSnapshotView,
): TransactionEditOrigin? {
    val amountLeg = detail.legs.firstOrNull { it.amount.minorUnits > 0L } ?: return null
    val currency = amountLeg.amount.currency
    val fundingAccountId =
        detail.legs
            .firstOrNull { leg -> snapshot.manageableAccounts.any { it.accountId == leg.accountId && it.kind == AccountKind.ASSET } }
            ?.accountId
    val categoryId =
        detail.legs
            .firstNotNullOfOrNull { leg -> snapshot.categories.firstOrNull { it.postingAccountId == leg.accountId }?.categoryId }
    return TransactionEditOrigin(
        transactionId = detail.transactionId,
        currentVersionId = detail.currentVersionId,
        note = detail.note,
        statisticsAt = detail.statisticsAt,
        amountText = formatMinorUnits(amountLeg.amount.minorUnits, currency.precision),
        categoryId = categoryId,
        fundingAccountId = fundingAccountId,
        currency = currency,
    )
}

/**
 * P7-05.B (spec section 3.2): builds the correction request from the surface's draft and origin.
 * `null` when a frozen field is absent or inadmissible, so the caller dispatches the typed field
 * rejection rather than calling the use case with a degraded request.
 *
 * The amount text mirrors the preview's own reading (a blank field means "unchanged", so the
 * origin's amount stands). The statistics time keeps the origin instant verbatim while the field
 * is untouched — the stored instant may carry sub-second precision the entry parser deliberately
 * does not accept, and re-typing the seeded text must not lose it; only a user-edited text is
 * parsed (with the same lenient parser and clock the entry flow uses).
 */
internal fun transactionCorrectionRequest(
    ledgerId: LedgerId,
    requestId: RequestId,
    origin: TransactionEditOrigin,
    draft: TransactionCorrectionDraft,
    parseAmount: ParseManualExpenseAmount,
    parseOccurredAt: ParseManualExpenseOccurredAt,
    ledgerClock: LedgerClock,
    fallbackCurrency: CurrencyUnit,
): ExplicitlyConfirmedTransactionCorrection? {
    val currency = origin.currency ?: fallbackCurrency
    val amountText = draft.amountText.ifBlank { origin.amountText }
    val minorUnits = (parseAmount.parse(amountText, currency) as? ParseManualExpenseAmount.Result.Valid)?.minorUnits ?: return null
    val statisticsAt =
        if (draft.statisticsAtText == origin.statisticsAt.toString()) {
            origin.statisticsAt
        } else {
            (parseOccurredAt.parse(draft.statisticsAtText, ledgerClock) as? ParseManualExpenseOccurredAt.Result.Valid)?.instant ?: return null
        }
    val categoryId = draft.categoryId ?: return null
    val fundingAccountId = draft.fundingAccountId ?: return null
    return ExplicitlyConfirmedTransactionCorrection(
        ledgerId = ledgerId,
        requestId = requestId,
        transactionId = origin.transactionId,
        expectedCurrentVersionId = origin.currentVersionId,
        // An emptied note is the absent representation, matching the preview's 无 reading.
        note = draft.note.ifEmpty { null },
        statisticsAt = statisticsAt,
        amount = Money.ofMinor(minorUnits, currency),
        categoryId = categoryId,
        fundingAccountId = fundingAccountId,
        confirmation = ExplicitManualSave,
    )
}

/** P7-05.B (spec section 4.2): the request's frozen snapshot column set, for the resolver comparison. */
internal fun ExplicitlyConfirmedTransactionCorrection.toRequestSnapshot(): TransactionCorrectionRequestSnapshot =
    TransactionCorrectionRequestSnapshot(
        ledgerId = ledgerId,
        transactionId = transactionId,
        expectedCurrentVersionId = expectedCurrentVersionId,
        note = note,
        statisticsAt = statisticsAt,
        amount = amount,
        categoryId = categoryId,
        fundingAccountId = fundingAccountId,
    )

/**
 * P7-05.C (DP-11): the domain reason of a typed reason draft. `null` when no code was chosen, so
 * the use case's mandatory-reason rejection carries the typed code with zero writes. The note is
 * kept verbatim (including whitespace-only) so the domain's blank rule still applies; an empty
 * draft note is the absent representation.
 */
internal fun voidReasonFromDraft(draft: VoidReasonDraft): VoidReason? = draft.code?.let { VoidReason(it, draft.note.ifEmpty { null }) }

/** P7-05.C (spec section 4.2): the void/restore request's frozen snapshot, `null` when no reason was chosen. */
internal fun VoidTransactionRequest.toRequestSnapshot(factKind: TransactionVoidFactKind): TransactionVoidRequestSnapshot? =
    reason?.let {
        TransactionVoidRequestSnapshot(
            ledgerId = ledgerId,
            transactionId = transactionId,
            factKind = factKind,
            reason = it,
        )
    }

/**
 * P7-05.B (spec section 4.2; the P7-02 S-3 discipline): the snapshot-aware verdict of a lost
 * correction commit. A hit is the original receipt (`NoChange`, zero new entities); a mismatched
 * snapshot is a stable identity conflict; an absent row or an unreadable read stays unknown —
 * `null`, so the caller keeps the submitting marker with no automatic retry and no request-id swap
 * (a lost commit is not a notice).
 */
internal fun correctResultFromResolution(
    identity: TransactionCorrectionRequestIdentity,
    resolution: TransactionCorrectionCommitResolution,
): CorrectTransactionVersionResult? =
    when (resolution) {
        is TransactionCorrectionCommitResolution.MatchingReceipt -> CorrectTransactionVersionResult.NoChange(resolution.receipt)
        TransactionCorrectionCommitResolution.SnapshotConflict -> CorrectTransactionVersionResult.RequestIdentityConflict(identity)
        TransactionCorrectionCommitResolution.Absent,
        TransactionCorrectionCommitResolution.Unavailable,
        -> null
    }

/** The void/restore analogue of [correctResultFromResolution] over the merged family. */
internal fun voidResultFromResolution(
    identity: TransactionVoidRequestIdentity,
    resolution: TransactionVoidCommitResolution,
): VoidTransactionResult? =
    when (resolution) {
        is TransactionVoidCommitResolution.MatchingReceipt -> VoidTransactionResult.NoChange(resolution.receipt)
        TransactionVoidCommitResolution.SnapshotConflict -> VoidTransactionResult.RequestIdentityConflict(identity)
        TransactionVoidCommitResolution.Absent,
        TransactionVoidCommitResolution.Unavailable,
        -> null
    }

/** P7-05.B (spec section 3.5): the typed field rejection of an inadmissible correction draft. */
internal val CORRECTION_FIELD_REJECTION: CorrectTransactionVersionResult =
    CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_FIELD_NOT_SUPPORTED)

// P7-05 slice 1b Piece 6 (V-19; D-173): the manual re-check of a lost commit. The crux is the
// RETAINED snapshot: the three surfaces do not freeze their draft while submitting, so the snapshot
// is captured at confirm time and re-resolved verbatim here — never re-derived from the live draft
// (which could differ and cause a FALSE SnapshotConflict). These are the pure, JVM-assertable
// decisions the composition-root call sites in P503App.kt delegate to; the resolver IO is injected.

/** The retained request of one explicit correction confirm (the snapshot captured at confirm time). */
internal fun ExplicitlyConfirmedTransactionCorrection.toRetainedRequest(): RetainedP705Request.Correction = RetainedP705Request.Correction(requestId = requestId, snapshot = toRequestSnapshot())

/**
 * The retained request of one explicit void/restore confirm. `null` when the reason is absent (the
 * confirm is then rejected before any commit, so there is nothing to re-check).
 */
internal fun VoidTransactionRequest.toRetainedRequest(factKind: TransactionVoidFactKind): RetainedP705Request.VoidOrRestore? = toRequestSnapshot(factKind)?.let { RetainedP705Request.VoidOrRestore(requestId = requestId, snapshot = it) }

/**
 * P7-05.B (V-19; D-173): the landing event of one manual correction re-check, resolved against the
 * RETAINED snapshot. A matching receipt is the original success ([P503UiEvent.TransactionEditResult]
 * carrying `NoChange`, which the reducer treats as a determinate success exactly like `Created`); a
 * snapshot conflict is the existing identity-conflict result; the resolver's `Absent`/`Unavailable`
 * produce no result, so the surface is told the still-unknown marker via the state-preserving
 * [P503UiEvent.RetryP705CommitStatusCheck] carrying that outcome. Never mints a new requestId.
 */
internal fun correctionRecheckEvent(
    retained: RetainedP705Request.Correction,
    resolve: (LedgerId, RequestId, TransactionCorrectionRequestSnapshot) -> TransactionCorrectionCommitResolution,
): P503UiEvent {
    val identity = TransactionCorrectionRequestIdentity(retained.ledgerId, retained.requestId)
    val resolution = resolve(retained.ledgerId, retained.requestId, retained.snapshot)
    return when (resolution) {
        is TransactionCorrectionCommitResolution.MatchingReceipt ->
            P503UiEvent.TransactionEditResult(CorrectTransactionVersionResult.NoChange(resolution.receipt))
        TransactionCorrectionCommitResolution.SnapshotConflict ->
            P503UiEvent.TransactionEditResult(CorrectTransactionVersionResult.RequestIdentityConflict(identity))
        TransactionCorrectionCommitResolution.Absent ->
            P503UiEvent.RetryP705CommitStatusCheck(P705CommitCheckOutcome.ABSENT)
        TransactionCorrectionCommitResolution.Unavailable ->
            P503UiEvent.RetryP705CommitStatusCheck(P705CommitCheckOutcome.UNAVAILABLE)
    }
}

/**
 * The void/restore analogue of [correctionRecheckEvent]. The landing event family (void vs restore)
 * is chosen from the RETAINED snapshot's own `factKind`, not from the live state — the retained
 * snapshot is the committed one, so the re-check always answers for the surface that committed it.
 */
internal fun voidRestoreRecheckEvent(
    retained: RetainedP705Request.VoidOrRestore,
    resolve: (LedgerId, RequestId, TransactionVoidRequestSnapshot) -> TransactionVoidCommitResolution,
): P503UiEvent {
    val identity = TransactionVoidRequestIdentity(retained.ledgerId, retained.requestId)
    val resolution = resolve(retained.ledgerId, retained.requestId, retained.snapshot)
    val restore = retained.snapshot.factKind == TransactionVoidFactKind.RESTORE
    return when (resolution) {
        is TransactionVoidCommitResolution.MatchingReceipt -> {
            val result = VoidTransactionResult.NoChange(resolution.receipt)
            if (restore) P503UiEvent.TransactionRestoreResult(result) else P503UiEvent.TransactionVoidResult(result)
        }
        TransactionVoidCommitResolution.SnapshotConflict -> {
            val result = VoidTransactionResult.RequestIdentityConflict(identity)
            if (restore) P503UiEvent.TransactionRestoreResult(result) else P503UiEvent.TransactionVoidResult(result)
        }
        TransactionVoidCommitResolution.Absent ->
            P503UiEvent.RetryP705CommitStatusCheck(P705CommitCheckOutcome.ABSENT)
        TransactionVoidCommitResolution.Unavailable ->
            P503UiEvent.RetryP705CommitStatusCheck(P705CommitCheckOutcome.UNAVAILABLE)
    }
}

/**
 * P7-05 (V-19; D-173): the JVM-assertable seam of the host's re-check landing hop. A determinate
 * hit lands as one of the existing result events carrying `NoChange`, so it fires the same
 * authoritative refresh + post-landing monthly re-request as a first-time success (the effect is
 * already in place, so the surfaces must update); an identity conflict and the still-unknown
 * marker landings refresh nothing.
 */
internal fun refreshAfterP705Recheck(
    event: P503UiEvent,
    coordinator: P503HostCoordinator,
) {
    when (event) {
        is P503UiEvent.TransactionEditResult -> refreshAfterP705Commit(event.result, coordinator)
        is P503UiEvent.TransactionVoidResult -> refreshAfterP705Commit(event.result, coordinator)
        is P503UiEvent.TransactionRestoreResult -> refreshAfterP705Commit(event.result, coordinator)
        else -> Unit
    }
}
