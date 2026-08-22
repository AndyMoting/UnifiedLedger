package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.ImportCandidateCommitPort
import com.unifiedledger.application.ImportDuplicateReviewCommitPort
import com.unifiedledger.application.ImportDuplicateReviewRequest
import com.unifiedledger.application.ImportDuplicateReviewResult
import com.unifiedledger.application.ImportDuplicateReviewReceipt
import com.unifiedledger.application.ImportDuplicateStatus
import com.unifiedledger.application.ImportCandidateDecision
import com.unifiedledger.application.ImportCandidateDecisionResult
import com.unifiedledger.application.ImportCandidateDecisionSnapshot
import com.unifiedledger.application.ImportCandidateFormalizationInput
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportCommitIds
import com.unifiedledger.application.ImportConfirmDecisionFields
import com.unifiedledger.application.ImportConfirmationId
import com.unifiedledger.application.ImportContentFingerprint
import com.unifiedledger.application.ImportEvidenceId
import com.unifiedledger.application.ImportFormalCommit
import com.unifiedledger.application.ImportFormalIds
import com.unifiedledger.application.ImportIntakeCommitPort
import com.unifiedledger.application.ImportIntakeIds
import com.unifiedledger.application.ImportIntakeResult
import com.unifiedledger.application.ImportIntakeSnapshot
import com.unifiedledger.application.ImportReceipt
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportRequestId
import com.unifiedledger.application.ImportRequestIdentity
import com.unifiedledger.application.ImportResolvedSourceFacts
import com.unifiedledger.application.ImportReturnedId
import com.unifiedledger.application.ImportReturnedIdKind
import com.unifiedledger.application.ImportSourceId
import com.unifiedledger.application.ImportStatusHistoryId
import com.unifiedledger.application.SPINE_NO_CHANGE_REASON_CODE
import com.unifiedledger.application.SpineDiagnostics
import com.unifiedledger.application.validateImportFormalBinding
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.TransactionId

internal enum class ImportSpineFailurePoint { INTAKE_AFTER_CANDIDATE, CONFIRM_AFTER_FORMAL, REVIEW_AFTER_SNAPSHOT }
internal fun interface ImportSpineFailureInjector { fun failAt(point: ImportSpineFailurePoint) }
private val NO_IMPORT_SPINE_FAILURE = ImportSpineFailureInjector { }

/**
 * P4-02 shared import spine persistence (spec section 8).
 *
 * - commitIntake: claim-first; same-request replay resolves against the request-bound
 *   source row (hash + facts double equivalence); raw identity idempotency rolls the
 *   whole transaction back (claim included) and writes nothing; non-equivalent same
 *   identity is a hard SPINE_IDENTITY_COLLISION rejection with zero writes.
 * - commitOnce / commitRejectOnce: claim-first; same-request replay resolves against
 *   the persisted decision snapshot (expected content hash compared first, then the
 *   frozen field list); rejections and losing paths roll back the claim and leave zero
 *   residue; only the winning first request invokes the application callback and the
 *   ID sources.
 */
class SqlDelightImportSpineStore private constructor(
    private val database: LedgerDatabase,
    private val failure: ImportSpineFailureInjector,
    private val fingerprint: ImportContentFingerprint,
) : ImportIntakeCommitPort, ImportCandidateCommitPort, ImportDuplicateReviewCommitPort {
    constructor(database: LedgerDatabase, driver: SqlDriver) :
        this(database, NO_IMPORT_SPINE_FAILURE, ImportContentFingerprint()) {
        configureSqliteConnection(driver)
    }

    internal constructor(
        database: LedgerDatabase,
        driver: SqlDriver,
        failure: ImportSpineFailureInjector,
    ) : this(database, failure, ImportContentFingerprint()) {
        configureSqliteConnection(driver)
    }

    override fun commitIntake(
        identity: ImportRequestIdentity,
        snapshot: ImportIntakeSnapshot,
        allocateIds: () -> ImportIntakeIds,
    ): ImportIntakeResult {
        require(identity.ledgerId == snapshot.identity.ledgerId) {
            "Request identity and snapshot must belong to the same ledger"
        }
        // Computed exactly once at intake from the inbound facts (spec section 6).
        val digest = fingerprint.digest(snapshot.recordKind, snapshot.facts, snapshot.paymentProfile)
        return try {
            rollbackTypedRejection { database.transactionWithResult {
                database.ledgerQueries.claimImportRequest(identity.ledgerId.value, identity.requestId.value, "intake")
                if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
                    return@transactionWithResult resolveIntake(identity, snapshot, digest)
                }
                val existing = database.ledgerQueries.selectImportSourceByIdentity(
                    snapshot.identity.ledgerId.value,
                    snapshot.inputRef,
                    snapshot.recordOrdinal.toLong(),
                ).executeAsOneOrNull()
                if (existing != null) {
                    if (intakeEquivalent(existing.toStoredFacts(), snapshot, digest)) {
                        // Zero writes: the whole transaction (claim included) rolls back.
                        abortImportSpine(intakeIdentityNoChange(snapshot, existing.source_id))
                    }
                    abortImportSpine(
                        ImportIntakeResult.Rejected(
                            SpineDiagnostics.identityCollision(
                                snapshot.inputRef,
                                snapshot.recordOrdinal,
                            ),
                        ),
                    )
                }
                val ids = allocateIds()
                database.ledgerQueries.insertImportSourceRecord(
                    ledger_id = snapshot.identity.ledgerId.value,
                    source_id = ids.sourceId.value,
                    owner_request_id = identity.requestId.value,
                    input_ref = snapshot.inputRef,
                    record_ordinal = snapshot.recordOrdinal.toLong(),
                    record_kind = snapshot.recordKind.storageValue,
                    content_hash = digest,
                    contract_version = snapshot.recordKind.contractVersion.toLong(),
                    completeness = snapshot.completeness.name.lowercase(),
                    amount_minor = snapshot.facts.amountMinor,
                    currency_code = snapshot.facts.currencyCode,
                    currency_precision = snapshot.facts.currencyPrecision.toLong(),
                    occurred_at = snapshot.facts.occurredAt,
                    direction_token = snapshot.facts.directionToken,
                    status_token = snapshot.facts.statusToken,
                    funding_state = snapshot.facts.fundingState.name,
                    funding_rule_id = snapshot.facts.fundingRuleId,
                    funding_rule_version = snapshot.facts.fundingRuleVersion.toLong(),
                    candidate_generated_at = snapshot.candidateGeneratedAt,
                )
                database.ledgerQueries.insertImportEvidence(
                    ledger_id = snapshot.identity.ledgerId.value,
                    evidence_id = ids.evidenceId.value,
                    source_id = ids.sourceId.value,
                    evidence_kind = "source_observation",
                    observed_at = snapshot.facts.occurredAt,
                )
                database.ledgerQueries.insertImportCandidate(
                    ledger_id = snapshot.identity.ledgerId.value,
                    candidate_id = ids.candidateId.value,
                    source_id = ids.sourceId.value,
                    candidate_kind = when (snapshot.recordKind) {
                        ImportRecordKind.ORDINARY_FLOW_SOURCE -> "ordinary_flow"
                        ImportRecordKind.TRANSFER_FLOW_SOURCE -> "transfer_flow"
                        ImportRecordKind.TRANSFER_FLOW_SOURCE_MISSING_LEG -> "transfer_flow_missing_leg"
                        ImportRecordKind.CREDIT_EXPENSE_SOURCE -> "credit_expense"
                        ImportRecordKind.CREDIT_REPAYMENT_SOURCE -> "credit_repayment"
                        ImportRecordKind.MIXED_PAYMENT_SOURCE -> "mixed_payment"
                    },
                    confidence = confidenceFor(snapshot.completeness),
                    rule = snapshot.recordKind.storageValue,
                    rule_version = 1L,
                )
                // P4-06 slice 1 (D-107 section 5): a v3 row writes its payment profile in
                // the same transaction as source/evidence/candidate ("exactly one profile
                // row per v3 candidate" is owned by this write path + the oracle).
                val paymentProfile = snapshot.paymentProfile
                if (paymentProfile != null) {
                    database.ledgerQueries.insertImportCandidatePaymentProfile(
                        ledger_id = snapshot.identity.ledgerId.value,
                        candidate_id = ids.candidateId.value,
                        variant = paymentProfile.variant.storageValue,
                        asset_leg_kind_token = paymentProfile.assetLegKindToken,
                        credit_leg_kind_token = paymentProfile.creditLegKindToken,
                    )
                }
                database.ledgerQueries.insertImportCandidateRequirement(
                    ledger_id = snapshot.identity.ledgerId.value,
                    candidate_id = ids.candidateId.value,
                    requirement_index = 0L,
                    requirement = "formal_transaction_creation",
                )
                if (snapshot.completeness == ImportCompleteness.VALID_COMPLETE && snapshot.facts.fundingState == com.unifiedledger.application.ImportFundingState.SETTLED) {
                    val existing = database.ledgerQueries.selectDuplicateMatches(
                        snapshot.identity.ledgerId.value, ids.sourceId.value, snapshot.recordKind.storageValue,
                        snapshot.recordKind.contractVersion.toLong(), snapshot.facts.amountMinor, snapshot.facts.currencyCode,
                        snapshot.facts.currencyPrecision.toLong(), snapshot.facts.occurredAt, snapshot.facts.directionToken,
                        snapshot.facts.statusToken, if (snapshot.facts.statusToken == null) 1L else 0L,
                    ).executeAsList()
                    if (ids.duplicateIds.size != existing.size) {
                        abortImportSpine(ImportIntakeResult.Rejected(SpineDiagnostics.referenceIntegrityViolation(ids.candidateId)))
                    }
                    existing.zip(ids.duplicateIds).forEach { (existingSourceId, duplicateIds) ->
                        val projection = com.unifiedledger.application.ImportDuplicateComparisonSnapshot(ids.sourceId, ImportSourceId(existingSourceId), snapshot.recordKind, snapshot.recordKind.contractVersion, snapshot.facts.amountMinor, snapshot.facts.currencyCode, snapshot.facts.currencyPrecision, snapshot.facts.occurredAt, snapshot.facts.directionToken, snapshot.facts.statusToken)
                        val tupleSnapshot = com.unifiedledger.application.ImportDuplicateComparisonFingerprint().canonicalJson(projection)
                        val comparison = "{\"possible_existing_source_id\":\"$existingSourceId\",\"subject_source_id\":\"${ids.sourceId.value}\",\"tuple\":$tupleSnapshot}"
                        val fingerprint = com.unifiedledger.application.ImportDuplicateComparisonFingerprint().digest(projection)
                        database.ledgerQueries.insertDuplicateCandidate(snapshot.identity.ledgerId.value, duplicateIds.candidateId.value, ids.sourceId.value, existingSourceId, "EXACT_BUSINESS_TUPLE", fingerprint, comparison, "source_declared + mechanical_decode + p407_exact_business_tuple_v1", snapshot.candidateGeneratedAt, identity.requestId.value)
                        database.ledgerQueries.insertDuplicateStatus(snapshot.identity.ledgerId.value, duplicateIds.candidateId.value, duplicateIds.statusHistoryId.value, identity.requestId.value)
                    }
                } else if (snapshot.facts.fundingState == com.unifiedledger.application.ImportFundingState.NO_FUNDS) {
                    if (ids.duplicateIds.size != 1) abortImportSpine(ImportIntakeResult.Rejected(SpineDiagnostics.referenceIntegrityViolation(ids.candidateId)))
                    val duplicateIds = ids.duplicateIds.single()
                    // Same privacy-safe frozen projection as exact-tuple candidates, with a
                    // NULL possible-existing target (D-105 sections 3/4); the fingerprint
                    // stays the deterministic no-funds token keyed by the subject source.
                    val noFundsProjection = com.unifiedledger.application.ImportDuplicateComparisonSnapshot(ids.sourceId, null, snapshot.recordKind, snapshot.recordKind.contractVersion, snapshot.facts.amountMinor, snapshot.facts.currencyCode, snapshot.facts.currencyPrecision, snapshot.facts.occurredAt, snapshot.facts.directionToken, snapshot.facts.statusToken)
                    val noFundsTuple = com.unifiedledger.application.ImportDuplicateComparisonFingerprint().canonicalJson(noFundsProjection)
                    val noFundsSnapshot = "{\"possible_existing_source_id\":null,\"subject_source_id\":\"${ids.sourceId.value}\",\"tuple\":$noFundsTuple}"
                    database.ledgerQueries.insertDuplicateCandidate(snapshot.identity.ledgerId.value, duplicateIds.candidateId.value, ids.sourceId.value, null, "CLOSED_OR_FAILED_NO_FUNDS", "sha256:no-funds-${ids.sourceId.value}", noFundsSnapshot, "source_declared + mechanical_decode", snapshot.candidateGeneratedAt, identity.requestId.value)
                    database.ledgerQueries.insertDuplicateStatus(snapshot.identity.ledgerId.value, duplicateIds.candidateId.value, duplicateIds.statusHistoryId.value, identity.requestId.value)
                } else if (ids.duplicateIds.isNotEmpty()) {
                    abortImportSpine(ImportIntakeResult.Rejected(SpineDiagnostics.referenceIntegrityViolation(ids.candidateId)))
                }
                val status = if (snapshot.completeness == ImportCompleteness.VALID_COMPLETE &&
                    snapshot.facts.fundingState == com.unifiedledger.application.ImportFundingState.SETTLED
                ) {
                    "pending_confirmation"
                } else {
                    "incomplete"
                }
                database.ledgerQueries.insertImportStatusHistory(
                    ledger_id = snapshot.identity.ledgerId.value,
                    candidate_id = ids.candidateId.value,
                    sequence = 1L,
                    status_id = ids.statusHistoryId.value,
                    status = status,
                    request_id = identity.requestId.value,
                    operation_class = "creation",
                )
                failure.failAt(ImportSpineFailurePoint.INTAKE_AFTER_CANDIDATE)
                database.ledgerQueries.insertImportReceipt(
                    ledger_id = snapshot.identity.ledgerId.value,
                    request_id = identity.requestId.value,
                    outcome = "accepted",
                    source_id = ids.sourceId.value,
                    evidence_id = ids.evidenceId.value,
                    candidate_id = ids.candidateId.value,
                    confirmation_id = null,
                    transaction_id = null,
                )
                ImportIntakeResult.Accepted(
                    receipt = ImportReceipt(
                        requestId = identity.requestId,
                        sourceId = ids.sourceId,
                        evidenceId = ids.evidenceId,
                        candidateId = ids.candidateId,
                        confirmationId = null,
                        transactionId = null,
                    ),
                    returnedIds = listOf(
                        ImportReturnedId(ImportReturnedIdKind.SOURCE, ids.sourceId.value),
                        ImportReturnedId(ImportReturnedIdKind.EVIDENCE, ids.evidenceId.value),
                        ImportReturnedId(ImportReturnedIdKind.CANDIDATE, ids.candidateId.value),
                    ),
                )
            } }
        } catch (unexpected: Throwable) {
            // Defensive UNIQUE(ledger_id, input_ref, record_ordinal) race handling: the
            // transaction already rolled back, so any pre-existing source row belongs to
            // a committed winner; re-read and re-judge (spec section 8 intake order).
            val existing = database.ledgerQueries.selectImportSourceByIdentity(
                snapshot.identity.ledgerId.value,
                snapshot.inputRef,
                snapshot.recordOrdinal.toLong(),
            ).executeAsOneOrNull() ?: throw unexpected
            if (intakeEquivalent(existing.toStoredFacts(), snapshot, digest)) {
                intakeIdentityNoChange(snapshot, existing.source_id)
            } else {
                ImportIntakeResult.Rejected(
                    SpineDiagnostics.identityCollision(snapshot.inputRef, snapshot.recordOrdinal),
                )
            }
        }
    }

    override fun commitReviewOnce(request: ImportDuplicateReviewRequest): ImportDuplicateReviewResult {
        require(request.identity.ledgerId.value.isNotEmpty())
        val fingerprint = com.unifiedledger.application.ImportDuplicateReviewFingerprint().digest(request)
        return rollbackTypedRejection { database.transactionWithResult {
            database.ledgerQueries.claimDuplicateReviewRequest(request.identity.ledgerId.value, request.identity.requestId.value, fingerprint)
            if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
                val prior = database.ledgerQueries.selectDuplicateReviewRequest(request.identity.ledgerId.value, request.identity.requestId.value).executeAsOneOrNull()
                if (prior?.input_fingerprint != fingerprint) return@transactionWithResult ImportDuplicateReviewResult.Rejected(SpineDiagnostics.requestIdentityConflict(request.identity.requestId))
                val receipt = database.ledgerQueries.selectDuplicateReviewReceipt(request.identity.ledgerId.value, request.identity.requestId.value).executeAsOneOrNull()
                    ?: return@transactionWithResult ImportDuplicateReviewResult.Rejected(SpineDiagnostics.requestIdentityConflict(request.identity.requestId))
                return@transactionWithResult ImportDuplicateReviewResult.NoChange(ImportDuplicateReviewReceipt(ImportRequestId(receipt.request_id), com.unifiedledger.application.ImportDuplicateCandidateId(receipt.candidate_id), com.unifiedledger.application.ImportDuplicateReviewId(receipt.review_id), ImportStatusHistoryId(receipt.history_id), ImportDuplicateStatus.valueOf(receipt.outcome)), SPINE_NO_CHANGE_REASON_CODE)
            }
            val candidate = database.ledgerQueries.selectDuplicateCandidate(request.identity.ledgerId.value, request.candidateId.value).executeAsOneOrNull()
                ?: abortImportSpine(ImportDuplicateReviewResult.Rejected(SpineDiagnostics.candidateNotFound(ImportCandidateId(request.candidateId.value))))
            if (candidate.comparison_fingerprint != request.expectedComparisonFingerprint) abortImportSpine(ImportDuplicateReviewResult.Rejected(SpineDiagnostics.staleFingerprint(ImportCandidateId(request.candidateId.value))))
            // D-105 section 3: CONFIRMED_DUPLICATE requires the rule constraint the
            // candidate carries (a directed possible-existing target). A NULL-target
            // CLOSED_OR_FAILED_NO_FUNDS candidate has none, so that decision is a typed
            // rejection with zero writes (the claim rolls back and stays retryable).
            if (candidate.kind == "CLOSED_OR_FAILED_NO_FUNDS" && request.decision == ImportDuplicateStatus.CONFIRMED_DUPLICATE) {
                abortImportSpine(ImportDuplicateReviewResult.Rejected(SpineDiagnostics.decisionKindMismatch(ImportCandidateId(request.candidateId.value))))
            }
            val current = database.ledgerQueries.selectDuplicateCurrentStatus(request.identity.ledgerId.value, request.candidateId.value).executeAsOneOrNull()
            if (current != "DEFERRED" || request.decision == ImportDuplicateStatus.DEFERRED) abortImportSpine(ImportDuplicateReviewResult.Rejected(SpineDiagnostics.candidateNotPending(ImportCandidateId(request.candidateId.value))))
            database.ledgerQueries.insertDuplicateReviewSnapshot(request.identity.ledgerId.value, request.identity.requestId.value, request.candidateId.value, request.expectedComparisonFingerprint, request.decision.name, request.reasonToken, request.reviewedAt, request.reviewerReference, request.generatedAt, request.reviewId.value)
            // Failure-injection seam for the review path (same pattern as intake): any
            // throw here rolls back the claim, snapshot, history and receipt together.
            failure.failAt(ImportSpineFailurePoint.REVIEW_AFTER_SNAPSHOT)
            val seq = database.ledgerQueries.selectDuplicateLatestSequence(request.identity.ledgerId.value, request.candidateId.value).executeAsOne() + 1
            database.ledgerQueries.updateDuplicateReviewRequest(request.identity.ledgerId.value, request.identity.requestId.value)
            database.ledgerQueries.insertDuplicateReviewStatus(request.identity.ledgerId.value, request.candidateId.value, seq, request.historyId.value, request.decision.name, request.identity.requestId.value)
            database.ledgerQueries.insertDuplicateReviewReceipt(request.identity.ledgerId.value, request.identity.requestId.value, request.candidateId.value, request.reviewId.value, request.historyId.value, request.decision.name)
            ImportDuplicateReviewResult.Accepted(ImportDuplicateReviewReceipt(request.identity.requestId, request.candidateId, request.reviewId, request.historyId, request.decision))
        } }
    }

    override fun commitOnce(
        identity: ImportRequestIdentity,
        snapshot: ImportCandidateDecisionSnapshot,
        allocateIds: () -> ImportCommitIds,
        createFormalTransaction: (input: ImportCandidateFormalizationInput, ids: ImportCommitIds) -> DomainResult<ImportFormalCommit>,
    ): ImportCandidateDecisionResult {
        return rollbackTypedRejection { database.transactionWithResult {
            database.ledgerQueries.claimImportRequest(identity.ledgerId.value, identity.requestId.value, "confirm_candidate")
            if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
                return@transactionWithResult resolveConfirm(identity, snapshot)
            }
            if (snapshot.decision != ImportCandidateDecision.CONFIRM || snapshot.confirmDecisionFields == null) {
                abortImportSpine(
                    ImportCandidateDecisionResult.Rejected(
                        SpineDiagnostics.referenceIntegrityViolation(snapshot.candidateId),
                    ),
                )
            }
            val state = database.ledgerQueries.selectImportCandidateCurrentStatus(
                identity.ledgerId.value,
                snapshot.candidateId.value,
            ).executeAsOneOrNull()
                ?: abortImportSpine(
                    ImportCandidateDecisionResult.Rejected(
                        SpineDiagnostics.candidateNotFound(snapshot.candidateId),
                    ),
                )
            when (state.status) {
                "incomplete" -> abortImportSpine(
                    ImportCandidateDecisionResult.Rejected(
                        SpineDiagnostics.candidateIncomplete(snapshot.candidateId),
                    ),
                )
                "pending_confirmation" -> Unit
                else -> abortImportSpine(
                    ImportCandidateDecisionResult.Rejected(
                        SpineDiagnostics.candidateNotPending(snapshot.candidateId),
                    ),
                )
            }
            // Kind gate: check candidate_kind against decision fields
            val candidateKind = state.candidate_kind
            val decisionFields = snapshot.confirmDecisionFields!!
            // P4-06 slice 1 (D-107 section 3.3): the credit kinds gate on candidate kind
            // + profile variant; mixed_payment is structurally impossible in slice 1
            // (parser never produces it) and always mismatches defensively.
            if (candidateKind == "mixed_payment") {
                abortImportSpine(
                    ImportCandidateDecisionResult.Rejected(
                        SpineDiagnostics.decisionKindMismatch(snapshot.candidateId),
                    ),
                )
            }
            if (candidateKind == "credit_expense") {
                val profile = database.ledgerQueries.selectImportCandidatePaymentProfile(
                    identity.ledgerId.value,
                    snapshot.candidateId.value,
                ).executeAsOneOrNull()
                val fieldsMatch = when (profile?.variant) {
                    "credit_expense_direct" -> decisionFields is ImportConfirmDecisionFields.CreditExpenseFlow
                    "credit_expense_refund" -> decisionFields is ImportConfirmDecisionFields.CreditExpenseRefundFlow
                    else -> false
                }
                if (!fieldsMatch) {
                    abortImportSpine(
                        ImportCandidateDecisionResult.Rejected(
                            SpineDiagnostics.decisionKindMismatch(snapshot.candidateId),
                        ),
                    )
                }
            }
            when {
                candidateKind == "transfer_flow_missing_leg" -> abortImportSpine(
                    ImportCandidateDecisionResult.Rejected(
                        SpineDiagnostics.transferNotConfirmable(snapshot.candidateId),
                    ),
                )
                candidateKind == "ordinary_flow" && decisionFields !is ImportConfirmDecisionFields.OrdinaryFlow -> abortImportSpine(
                    ImportCandidateDecisionResult.Rejected(
                        SpineDiagnostics.decisionKindMismatch(snapshot.candidateId),
                    ),
                )
                candidateKind == "transfer_flow" && decisionFields !is ImportConfirmDecisionFields.TransferFlow -> abortImportSpine(
                    ImportCandidateDecisionResult.Rejected(
                        SpineDiagnostics.decisionKindMismatch(snapshot.candidateId),
                    ),
                )
                candidateKind == "credit_repayment" && decisionFields !is ImportConfirmDecisionFields.CreditRepaymentFlow -> abortImportSpine(
                    ImportCandidateDecisionResult.Rejected(
                        SpineDiagnostics.decisionKindMismatch(snapshot.candidateId),
                    ),
                )
            }
            val source = database.ledgerQueries.selectImportSourceForCandidate(
                identity.ledgerId.value,
                snapshot.candidateId.value,
            ).executeAsOneOrNull()
                ?: abortImportSpine(
                    ImportCandidateDecisionResult.Rejected(
                        SpineDiagnostics.referenceIntegrityViolation(snapshot.candidateId),
                    ),
                )
            if (snapshot.expectedContentHash != source.content_hash) {
                abortImportSpine(
                    ImportCandidateDecisionResult.Rejected(
                        SpineDiagnostics.staleFingerprint(snapshot.candidateId),
                    ),
                )
            }
            // P4-07 is a narrow formalization gate: only the subject source's latest
            // CONFIRMED_DUPLICATE review blocks confirmation. No formal IDs or factory
            // callback may be consumed on this path.
            if (database.ledgerQueries.hasConfirmedDuplicateForSource(
                    identity.ledgerId.value, source.source_id,
                ).executeAsOne()) {
                abortImportSpine(
                    ImportCandidateDecisionResult.Rejected(
                        SpineDiagnostics.duplicateNotConfirmable(snapshot.candidateId),
                    ),
                )
            }
            database.ledgerQueries.selectImportEvidenceForSource(
                identity.ledgerId.value,
                source.source_id,
            ).executeAsOneOrNull()
                ?: abortImportSpine(
                    ImportCandidateDecisionResult.Rejected(
                        SpineDiagnostics.referenceIntegrityViolation(snapshot.candidateId),
                    ),
                )
            val resolved = ImportResolvedSourceFacts(
                amountMinor = source.amount_minor!!,
                currencyCode = source.currency_code!!,
                currencyPrecision = source.currency_precision!!.toInt(),
                occurredAt = source.occurred_at!!,
                directionToken = source.direction_token!!,
                statusToken = source.status_token,
            )
            val input = ImportCandidateFormalizationInput(
                ledgerId = identity.ledgerId,
                resolved = resolved,
                decisionFields = decisionFields,
            )
            val allocatedIds = allocateIds()
            // Shape gate (spec 4.2 / T-54): allocated posting IDs must be exactly two;
            // otherwise reject before the factory runs at all (factory zero-call).
            if (allocatedIds.formalIds.postingIds.size != 2) {
                abortImportSpine(
                    ImportCandidateDecisionResult.Rejected(
                        SpineDiagnostics.referenceIntegrityViolation(snapshot.candidateId),
                    ),
                )
            }
            val created = when (val result = createFormalTransaction(input, allocatedIds)) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> abortImportSpine(
                    ImportCandidateDecisionResult.Rejected(
                        SpineDiagnostics.domainValidationFailed(snapshot.candidateId),
                    ),
                )
            }
            when (val validation = validateImportFormalBinding(input, allocatedIds, created)) {
                is DomainResult.Success -> Unit
                // Binding failure reuses SPINE_REFERENCE_INTEGRITY_VIOLATION (spec 4.2):
                // the returned graph is not bound to the same immutable input/allocated IDs.
                is DomainResult.Failure -> abortImportSpine(
                    ImportCandidateDecisionResult.Rejected(
                        SpineDiagnostics.referenceIntegrityViolation(snapshot.candidateId),
                    ),
                )
            }
            persistFormal(created.transaction)
            failure.failAt(ImportSpineFailurePoint.CONFIRM_AFTER_FORMAL)
            val categoryId: String? = when (decisionFields) {
                is ImportConfirmDecisionFields.OrdinaryFlow -> decisionFields.categoryId.value
                is ImportConfirmDecisionFields.CreditExpenseFlow -> decisionFields.categoryId.value
                is ImportConfirmDecisionFields.CreditExpenseRefundFlow -> decisionFields.categoryId.value
                else -> null
            }
            val fundingAccountId: String? = (decisionFields as? ImportConfirmDecisionFields.OrdinaryFlow)?.fundingAccountId?.value
            val fromAccountId: String? = (decisionFields as? ImportConfirmDecisionFields.TransferFlow)?.fromAccountId?.value
            val toAccountId: String? = (decisionFields as? ImportConfirmDecisionFields.TransferFlow)?.toAccountId?.value
            // P4-06 slice 1 (D-107 section 3.3): the three credit decision shapes.
            // The credit-liability account is shared by all three; the refund variant
            // additionally snapshots the original transaction id (FK-enforced).
            val creditLiabilityAccountId: String? = when (decisionFields) {
                is ImportConfirmDecisionFields.CreditExpenseFlow -> decisionFields.creditLiabilityAccountId.value
                is ImportConfirmDecisionFields.CreditExpenseRefundFlow -> decisionFields.creditLiabilityAccountId.value
                is ImportConfirmDecisionFields.CreditRepaymentFlow -> decisionFields.creditLiabilityAccountId.value
                else -> null
            }
            val creditAssetAccountId: String? =
                (decisionFields as? ImportConfirmDecisionFields.CreditRepaymentFlow)?.assetAccountId?.value
            val originalTransactionId: String? =
                (decisionFields as? ImportConfirmDecisionFields.CreditExpenseRefundFlow)?.originalTransactionId?.value
            database.ledgerQueries.insertImportDecisionSnapshot(
                ledger_id = identity.ledgerId.value,
                request_id = identity.requestId.value,
                decision = "confirm",
                candidate_id = snapshot.candidateId.value,
                expected_content_hash = snapshot.expectedContentHash,
                category_id = categoryId,
                funding_account_id = fundingAccountId,
                from_account_id = fromAccountId,
                to_account_id = toAccountId,
                credit_liability_account_id = creditLiabilityAccountId,
                asset_account_id = creditAssetAccountId,
                original_transaction_id = originalTransactionId,
                asset_leg_minor = null,
                credit_leg_minor = null,
                explicit_confirmed_at = snapshot.explicitConfirmedAt,
            )
            val sequence = database.ledgerQueries.selectImportCandidateLatestSequence(
                identity.ledgerId.value,
                snapshot.candidateId.value,
            ).executeAsOne() + 1L
            database.ledgerQueries.insertImportStatusHistory(
                ledger_id = identity.ledgerId.value,
                candidate_id = snapshot.candidateId.value,
                sequence = sequence,
                status_id = created.statusHistoryId.value,
                status = "confirmed",
                request_id = identity.requestId.value,
                operation_class = "creation",
            )
            database.ledgerQueries.insertImportConfirmation(
                ledger_id = identity.ledgerId.value,
                confirmation_id = created.confirmationId.value,
                request_id = identity.requestId.value,
                candidate_id = snapshot.candidateId.value,
                status_id = created.statusHistoryId.value,
                transaction_id = created.transaction.transaction.id.value,
                operation_class = "creation",
                confirmed_at = snapshot.explicitConfirmedAt,
            )
            database.ledgerQueries.insertImportReceipt(
                ledger_id = identity.ledgerId.value,
                request_id = identity.requestId.value,
                outcome = "accepted",
                source_id = null,
                evidence_id = null,
                candidate_id = snapshot.candidateId.value,
                confirmation_id = created.confirmationId.value,
                transaction_id = created.transaction.transaction.id.value,
            )
            ImportCandidateDecisionResult.Accepted(
                receipt = ImportReceipt(
                    requestId = identity.requestId,
                    sourceId = null,
                    evidenceId = null,
                    candidateId = snapshot.candidateId,
                    confirmationId = created.confirmationId,
                    transactionId = created.transaction.transaction.id,
                ),
                returnedIds = listOf(
                    ImportReturnedId(ImportReturnedIdKind.CONFIRMATION, created.confirmationId.value),
                    ImportReturnedId(ImportReturnedIdKind.TRANSACTION, created.transaction.transaction.id.value),
                ),
            )
        } }
    }

    override fun commitRejectOnce(
        identity: ImportRequestIdentity,
        snapshot: ImportCandidateDecisionSnapshot,
        allocateStatusId: () -> ImportStatusHistoryId,
    ): ImportCandidateDecisionResult {
        return rollbackTypedRejection { database.transactionWithResult {
            database.ledgerQueries.claimImportRequest(identity.ledgerId.value, identity.requestId.value, "reject_candidate")
            if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
                return@transactionWithResult resolveReject(identity, snapshot)
            }
            if (snapshot.decision != ImportCandidateDecision.REJECT || snapshot.confirmDecisionFields != null) {
                abortImportSpine(
                    ImportCandidateDecisionResult.Rejected(
                        SpineDiagnostics.referenceIntegrityViolation(snapshot.candidateId),
                    ),
                )
            }
            val state = database.ledgerQueries.selectImportCandidateCurrentStatus(
                identity.ledgerId.value,
                snapshot.candidateId.value,
            ).executeAsOneOrNull()
                ?: abortImportSpine(
                    ImportCandidateDecisionResult.Rejected(
                        SpineDiagnostics.candidateNotFound(snapshot.candidateId),
                    ),
                )
            if (state.status != "pending_confirmation") {
                abortImportSpine(
                    ImportCandidateDecisionResult.Rejected(
                        SpineDiagnostics.candidateNotPending(snapshot.candidateId),
                    ),
                )
            }
            val source = database.ledgerQueries.selectImportSourceForCandidate(
                identity.ledgerId.value,
                snapshot.candidateId.value,
            ).executeAsOneOrNull()
                ?: abortImportSpine(
                    ImportCandidateDecisionResult.Rejected(
                        SpineDiagnostics.referenceIntegrityViolation(snapshot.candidateId),
                    ),
                )
            if (snapshot.expectedContentHash != source.content_hash) {
                abortImportSpine(
                    ImportCandidateDecisionResult.Rejected(
                        SpineDiagnostics.staleFingerprint(snapshot.candidateId),
                    ),
                )
            }
            database.ledgerQueries.selectImportEvidenceForSource(
                identity.ledgerId.value,
                source.source_id,
            ).executeAsOneOrNull()
                ?: abortImportSpine(
                    ImportCandidateDecisionResult.Rejected(
                        SpineDiagnostics.referenceIntegrityViolation(snapshot.candidateId),
                    ),
                )
            val statusId = allocateStatusId()
            database.ledgerQueries.insertImportDecisionSnapshot(
                ledger_id = identity.ledgerId.value,
                request_id = identity.requestId.value,
                decision = "reject",
                candidate_id = snapshot.candidateId.value,
                expected_content_hash = snapshot.expectedContentHash,
                category_id = null,
                funding_account_id = null,
                from_account_id = null,
                to_account_id = null,
                credit_liability_account_id = null,
                asset_account_id = null,
                original_transaction_id = null,
                asset_leg_minor = null,
                credit_leg_minor = null,
                explicit_confirmed_at = null,
            )
            val sequence = database.ledgerQueries.selectImportCandidateLatestSequence(
                identity.ledgerId.value,
                snapshot.candidateId.value,
            ).executeAsOne() + 1L
            database.ledgerQueries.insertImportStatusHistory(
                ledger_id = identity.ledgerId.value,
                candidate_id = snapshot.candidateId.value,
                sequence = sequence,
                status_id = statusId.value,
                status = "rejected",
                request_id = identity.requestId.value,
                operation_class = "status_transition",
            )
            database.ledgerQueries.insertImportReceipt(
                ledger_id = identity.ledgerId.value,
                request_id = identity.requestId.value,
                outcome = "accepted",
                source_id = null,
                evidence_id = null,
                candidate_id = snapshot.candidateId.value,
                confirmation_id = null,
                transaction_id = null,
            )
            ImportCandidateDecisionResult.Accepted(
                receipt = ImportReceipt(
                    requestId = identity.requestId,
                    sourceId = null,
                    evidenceId = null,
                    candidateId = snapshot.candidateId,
                    confirmationId = null,
                    transactionId = null,
                ),
                returnedIds = listOf(
                    ImportReturnedId(ImportReturnedIdKind.CANDIDATE, snapshot.candidateId.value),
                ),
            )
        } }
    }

    private fun resolveIntake(
        identity: ImportRequestIdentity,
        snapshot: ImportIntakeSnapshot,
        digest: String,
    ): ImportIntakeResult {
        val stored = database.ledgerQueries.selectImportSourceByOwnerRequest(
            identity.ledgerId.value,
            identity.requestId.value,
        ).executeAsOneOrNull()
            ?: return ImportIntakeResult.Rejected(SpineDiagnostics.requestIdentityConflict(identity.requestId))
        if (!intakeEquivalent(stored.toStoredFacts(), snapshot, digest)) {
            return ImportIntakeResult.Rejected(SpineDiagnostics.requestIdentityConflict(identity.requestId))
        }
        val receipt = readReceipt(identity.ledgerId.value, identity.requestId.value)
            ?: return ImportIntakeResult.Rejected(SpineDiagnostics.requestIdentityConflict(identity.requestId))
        return ImportIntakeResult.NoChange(
            returnedIds = intakeReturnedIds(identity.ledgerId.value, stored.source_id),
            receipt = receipt,
            reasonCode = SPINE_NO_CHANGE_REASON_CODE,
        )
    }

    private fun resolveConfirm(
        identity: ImportRequestIdentity,
        snapshot: ImportCandidateDecisionSnapshot,
    ): ImportCandidateDecisionResult {
        val stored = database.ledgerQueries.selectImportDecisionSnapshotByRequest(
            identity.ledgerId.value,
            identity.requestId.value,
        ).executeAsOneOrNull()
            ?: return ImportCandidateDecisionResult.Rejected(SpineDiagnostics.requestIdentityConflict(identity.requestId))
        val source = database.ledgerQueries.selectImportSourceForCandidate(
            identity.ledgerId.value,
            stored.candidate_id,
        ).executeAsOneOrNull()
            ?: return ImportCandidateDecisionResult.Rejected(SpineDiagnostics.requestIdentityConflict(identity.requestId))
        // Hash check first, then the frozen field list (spec section 8).
        if (snapshot.expectedContentHash != source.content_hash) {
            return ImportCandidateDecisionResult.Rejected(SpineDiagnostics.staleFingerprint(snapshot.candidateId))
        }
        val equivalent = stored.decision == "confirm" &&
            stored.candidate_id == snapshot.candidateId.value &&
            stored.category_id == categoryDecisionValue(snapshot.confirmDecisionFields) &&
            stored.funding_account_id == (snapshot.confirmDecisionFields as? ImportConfirmDecisionFields.OrdinaryFlow)?.fundingAccountId?.value &&
            stored.from_account_id == (snapshot.confirmDecisionFields as? ImportConfirmDecisionFields.TransferFlow)?.fromAccountId?.value &&
            stored.to_account_id == (snapshot.confirmDecisionFields as? ImportConfirmDecisionFields.TransferFlow)?.toAccountId?.value &&
            stored.credit_liability_account_id == creditLiabilityDecisionValue(snapshot.confirmDecisionFields) &&
            stored.asset_account_id == (snapshot.confirmDecisionFields as? ImportConfirmDecisionFields.CreditRepaymentFlow)?.assetAccountId?.value &&
            stored.original_transaction_id == (snapshot.confirmDecisionFields as? ImportConfirmDecisionFields.CreditExpenseRefundFlow)?.originalTransactionId?.value &&
            stored.asset_leg_minor == null &&
            stored.credit_leg_minor == null &&
            stored.explicit_confirmed_at == snapshot.explicitConfirmedAt
        if (!equivalent) {
            return ImportCandidateDecisionResult.Rejected(SpineDiagnostics.requestIdentityConflict(identity.requestId))
        }
        val receipt = readReceipt(identity.ledgerId.value, identity.requestId.value)
            ?: return ImportCandidateDecisionResult.Rejected(SpineDiagnostics.requestIdentityConflict(identity.requestId))
        return ImportCandidateDecisionResult.NoChange(receipt, SPINE_NO_CHANGE_REASON_CODE)
    }

    private fun resolveReject(
        identity: ImportRequestIdentity,
        snapshot: ImportCandidateDecisionSnapshot,
    ): ImportCandidateDecisionResult {
        val stored = database.ledgerQueries.selectImportDecisionSnapshotByRequest(
            identity.ledgerId.value,
            identity.requestId.value,
        ).executeAsOneOrNull()
            ?: return ImportCandidateDecisionResult.Rejected(SpineDiagnostics.requestIdentityConflict(identity.requestId))
        val source = database.ledgerQueries.selectImportSourceForCandidate(
            identity.ledgerId.value,
            stored.candidate_id,
        ).executeAsOneOrNull()
            ?: return ImportCandidateDecisionResult.Rejected(SpineDiagnostics.requestIdentityConflict(identity.requestId))
        if (snapshot.expectedContentHash != source.content_hash) {
            return ImportCandidateDecisionResult.Rejected(SpineDiagnostics.staleFingerprint(snapshot.candidateId))
        }
        val equivalent = stored.decision == "reject" &&
            stored.candidate_id == snapshot.candidateId.value &&
            stored.category_id == null &&
            stored.funding_account_id == null &&
            stored.from_account_id == null &&
            stored.to_account_id == null &&
            stored.credit_liability_account_id == null &&
            stored.asset_account_id == null &&
            stored.original_transaction_id == null &&
            stored.asset_leg_minor == null &&
            stored.credit_leg_minor == null &&
            stored.explicit_confirmed_at == null
        if (!equivalent) {
            return ImportCandidateDecisionResult.Rejected(SpineDiagnostics.requestIdentityConflict(identity.requestId))
        }
        val receipt = readReceipt(identity.ledgerId.value, identity.requestId.value)
            ?: return ImportCandidateDecisionResult.Rejected(SpineDiagnostics.requestIdentityConflict(identity.requestId))
        return ImportCandidateDecisionResult.NoChange(receipt, SPINE_NO_CHANGE_REASON_CODE)
    }

    private fun readReceipt(ledgerId: String, requestId: String): ImportReceipt? =
        database.ledgerQueries.selectImportReceiptByRequest(ledgerId, requestId).executeAsOneOrNull()?.let { row ->
            ImportReceipt(
                requestId = ImportRequestId(row.request_id),
                sourceId = row.source_id?.let(::ImportSourceId),
                evidenceId = row.evidence_id?.let(::ImportEvidenceId),
                candidateId = ImportCandidateId(row.candidate_id),
                confirmationId = row.confirmation_id?.let(::ImportConfirmationId),
                transactionId = row.transaction_id?.let(::TransactionId),
            )
        }

    private fun intakeReturnedIds(ledgerId: String, sourceId: String): List<ImportReturnedId> {
        val evidence = database.ledgerQueries.selectImportEvidenceForSource(ledgerId, sourceId).executeAsOne()
        val candidate = database.ledgerQueries.selectImportCandidateBySource(ledgerId, sourceId).executeAsOne()
        return listOf(
            ImportReturnedId(ImportReturnedIdKind.SOURCE, sourceId),
            ImportReturnedId(ImportReturnedIdKind.EVIDENCE, evidence),
            ImportReturnedId(ImportReturnedIdKind.CANDIDATE, candidate),
        )
    }

    private fun intakeIdentityNoChange(
        snapshot: ImportIntakeSnapshot,
        sourceId: String,
    ): ImportIntakeResult = ImportIntakeResult.NoChange(
        returnedIds = intakeReturnedIds(snapshot.identity.ledgerId.value, sourceId),
        receipt = null,
        reasonCode = SPINE_NO_CHANGE_REASON_CODE,
    )

    private data class StoredSourceFacts(
        val sourceId: String,
        val recordKind: String,
        val contentHash: String,
        val completeness: String,
        val amountMinor: Long?,
        val currencyCode: String?,
        val currencyPrecision: Long?,
        val occurredAt: String?,
        val directionToken: String?,
        val statusToken: String?,
        val fundingState: String,
        val fundingRuleId: String,
        val fundingRuleVersion: Long,
        val candidateGeneratedAt: String,
        // P4-06 slice 1: profile columns (LEFT JOIN; all null for v1/v2 rows).
        val profileVariant: String?,
        val profileAssetLegKindToken: String?,
        val profileCreditLegKindToken: String?,
    )

    private fun com.unifiedledger.data.db.SelectImportSourceByIdentity.toStoredFacts(): StoredSourceFacts =
        StoredSourceFacts(
            sourceId = source_id,
            recordKind = record_kind,
            contentHash = content_hash,
            completeness = completeness,
            amountMinor = amount_minor,
            currencyCode = currency_code,
            currencyPrecision = currency_precision,
            occurredAt = occurred_at,
            directionToken = direction_token,
            statusToken = status_token,
            fundingState = funding_state,
            fundingRuleId = funding_rule_id,
            fundingRuleVersion = funding_rule_version,
            candidateGeneratedAt = candidate_generated_at,
            profileVariant = variant,
            profileAssetLegKindToken = asset_leg_kind_token,
            profileCreditLegKindToken = credit_leg_kind_token,
        )

    private fun com.unifiedledger.data.db.SelectImportSourceByOwnerRequest.toStoredFacts(): StoredSourceFacts =
        StoredSourceFacts(
            sourceId = source_id,
            recordKind = record_kind,
            contentHash = content_hash,
            completeness = completeness,
            amountMinor = amount_minor,
            currencyCode = currency_code,
            currencyPrecision = currency_precision,
            occurredAt = occurred_at,
            directionToken = direction_token,
            statusToken = status_token,
            fundingState = funding_state,
            fundingRuleId = funding_rule_id,
            fundingRuleVersion = funding_rule_version,
            candidateGeneratedAt = candidate_generated_at,
            profileVariant = variant,
            profileAssetLegKindToken = asset_leg_kind_token,
            profileCreditLegKindToken = credit_leg_kind_token,
        )

    // Source-fact equivalence for replay (D-105 section 5 + D-107 section 5):
    // candidate_generated_at is an audit fact, not a source fact, so it never
    // participates; funding facts are source facts and do. The P4-06 payment profile's
    // three fields join the frozen comparison list: a replay with a changed profile is
    // a hard identity collision. A replay carrying a different audit timestamp stays a
    // zero-write NoChange; a changed funding fact is a hard identity collision.
    private fun intakeEquivalent(
        stored: StoredSourceFacts,
        snapshot: ImportIntakeSnapshot,
        digest: String,
    ): Boolean = stored.contentHash == digest &&
        stored.recordKind == snapshot.recordKind.storageValue &&
        stored.completeness == snapshot.completeness.name.lowercase() &&
        stored.amountMinor == snapshot.facts.amountMinor &&
        stored.currencyCode == snapshot.facts.currencyCode &&
        stored.currencyPrecision == snapshot.facts.currencyPrecision.toLong() &&
        stored.occurredAt == snapshot.facts.occurredAt &&
        stored.directionToken == snapshot.facts.directionToken &&
        stored.statusToken == snapshot.facts.statusToken &&
        stored.fundingState == snapshot.facts.fundingState.name &&
        stored.fundingRuleId == snapshot.facts.fundingRuleId &&
        stored.fundingRuleVersion == snapshot.facts.fundingRuleVersion.toLong() &&
        stored.profileVariant == snapshot.paymentProfile?.variant?.storageValue &&
        stored.profileAssetLegKindToken == snapshot.paymentProfile?.assetLegKindToken &&
        stored.profileCreditLegKindToken == snapshot.paymentProfile?.creditLegKindToken

    private fun creditLiabilityDecisionValue(fields: ImportConfirmDecisionFields?): String? = when (fields) {
        is ImportConfirmDecisionFields.CreditExpenseFlow -> fields.creditLiabilityAccountId.value
        is ImportConfirmDecisionFields.CreditExpenseRefundFlow -> fields.creditLiabilityAccountId.value
        is ImportConfirmDecisionFields.CreditRepaymentFlow -> fields.creditLiabilityAccountId.value
        else -> null
    }

    private fun categoryDecisionValue(fields: ImportConfirmDecisionFields?): String? = when (fields) {
        is ImportConfirmDecisionFields.OrdinaryFlow -> fields.categoryId.value
        is ImportConfirmDecisionFields.CreditExpenseFlow -> fields.categoryId.value
        is ImportConfirmDecisionFields.CreditExpenseRefundFlow -> fields.categoryId.value
        else -> null
    }

    private fun confidenceFor(completeness: ImportCompleteness): String = when (completeness) {
        ImportCompleteness.VALID_COMPLETE -> "1.00"
        ImportCompleteness.VALID_INCOMPLETE -> "0.50"
    }

    private fun persistFormal(value: FormalTransaction) {
        value.postingSets.forEach { database.ledgerQueries.insertPostingSet(it.id.value, value.transaction.ledgerId.value) }
        database.ledgerQueries.insertTransaction(value.transaction.id.value, value.transaction.ledgerId.value, value.transaction.kind.name)
        value.versions.forEach {
            database.ledgerQueries.insertTransactionVersion(
                it.id.value, it.transactionId.value, value.transaction.ledgerId.value,
                it.versionNumber.toLong(), it.postingSetId.value,
                it.times.occurredAt.toString(), it.times.statisticsAt.toString(), it.times.effectiveAt.toString(), it.note,
            )
        }
        database.ledgerQueries.insertTransactionCurrentVersion(value.transaction.id.value, value.transaction.ledgerId.value, value.transaction.currentVersionId.value)
        value.postingSets.forEach { set ->
            set.postings.forEachIndexed { index, posting ->
                database.ledgerQueries.insertPosting(
                    posting.id.value, set.id.value, value.transaction.ledgerId.value, index.toLong(),
                    posting.accountId.value, posting.amount.minorUnits, posting.amount.currency.code, posting.amount.currency.precision.toLong(),
                )
            }
        }
    }
}

private class ImportSpineTypedRollback(val result: Any) : RuntimeException()

private fun abortImportSpine(result: Any): Nothing = throw ImportSpineTypedRollback(result)

private inline fun <T> rollbackTypedRejection(block: () -> T): T = try {
    block()
} catch (failure: ImportSpineTypedRollback) {
    @Suppress("UNCHECKED_CAST")
    failure.result as T
}
