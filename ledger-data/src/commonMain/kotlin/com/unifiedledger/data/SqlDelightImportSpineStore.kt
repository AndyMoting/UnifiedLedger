package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.ImportCandidateCommitPort
import com.unifiedledger.application.ImportCandidateDecision
import com.unifiedledger.application.ImportCandidateDecisionResult
import com.unifiedledger.application.ImportCandidateDecisionSnapshot
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportConfirmationId
import com.unifiedledger.application.ImportContentFingerprint
import com.unifiedledger.application.ImportEvidenceId
import com.unifiedledger.application.ImportFormalCommit
import com.unifiedledger.application.ImportIntakeCommitPort
import com.unifiedledger.application.ImportIntakeIds
import com.unifiedledger.application.ImportIntakeResult
import com.unifiedledger.application.ImportIntakeSnapshot
import com.unifiedledger.application.ImportReceipt
import com.unifiedledger.application.ImportRequestId
import com.unifiedledger.application.ImportRequestIdentity
import com.unifiedledger.application.ImportResolvedSourceFacts
import com.unifiedledger.application.ImportReturnedId
import com.unifiedledger.application.ImportReturnedIdKind
import com.unifiedledger.application.ImportSourceId
import com.unifiedledger.application.ImportStatusHistoryId
import com.unifiedledger.application.SPINE_NO_CHANGE_REASON_CODE
import com.unifiedledger.application.SpineDiagnostics
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.TransactionId

internal enum class ImportSpineFailurePoint { INTAKE_AFTER_CANDIDATE, CONFIRM_AFTER_FORMAL }
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
) : ImportIntakeCommitPort, ImportCandidateCommitPort {
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
        require(identity.ledgerId == snapshot.ledgerId) {
            "Request identity and snapshot must belong to the same ledger"
        }
        // Computed exactly once at intake from the inbound facts (spec section 6).
        val digest = fingerprint.digest(snapshot.facts)
        return try {
            rollbackTypedRejection { database.transactionWithResult {
                database.ledgerQueries.claimImportRequest(identity.ledgerId.value, identity.requestId.value, "intake")
                if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
                    return@transactionWithResult resolveIntake(identity, snapshot, digest)
                }
                val existing = database.ledgerQueries.selectImportSourceByIdentity(
                    snapshot.ledgerId.value,
                    snapshot.identity.inputRef,
                    snapshot.identity.recordOrdinal.toLong(),
                ).executeAsOneOrNull()
                if (existing != null) {
                    if (intakeEquivalent(existing.toStoredFacts(), snapshot, digest)) {
                        // Zero writes: the whole transaction (claim included) rolls back.
                        abortImportSpine(intakeIdentityNoChange(snapshot, existing.source_id))
                    }
                    abortImportSpine(
                        ImportIntakeResult.Rejected(
                            SpineDiagnostics.identityCollision(
                                snapshot.identity.inputRef,
                                snapshot.identity.recordOrdinal,
                            ),
                        ),
                    )
                }
                val ids = allocateIds()
                database.ledgerQueries.insertImportSourceRecord(
                    ledger_id = snapshot.ledgerId.value,
                    source_id = ids.sourceId.value,
                    owner_request_id = identity.requestId.value,
                    input_ref = snapshot.identity.inputRef,
                    record_ordinal = snapshot.identity.recordOrdinal.toLong(),
                    record_kind = ImportContentFingerprint.RECORD_KIND,
                    content_hash = digest,
                    contract_version = 1L,
                    completeness = snapshot.completeness.name.lowercase(),
                    amount_minor = snapshot.facts.amountMinor,
                    currency_code = snapshot.facts.currencyCode,
                    currency_precision = snapshot.facts.currencyPrecision.toLong(),
                    occurred_at = snapshot.facts.occurredAt,
                    direction_token = snapshot.facts.directionToken,
                    status_token = snapshot.facts.statusToken,
                )
                database.ledgerQueries.insertImportEvidence(
                    ledger_id = snapshot.ledgerId.value,
                    evidence_id = ids.evidenceId.value,
                    source_id = ids.sourceId.value,
                    evidence_kind = "source_observation",
                    observed_at = snapshot.facts.occurredAt,
                )
                database.ledgerQueries.insertImportCandidate(
                    ledger_id = snapshot.ledgerId.value,
                    candidate_id = ids.candidateId.value,
                    source_id = ids.sourceId.value,
                    candidate_kind = "ordinary_flow",
                    confidence = confidenceFor(snapshot.completeness),
                    rule = "ordinary_flow_source",
                    rule_version = 1L,
                )
                database.ledgerQueries.insertImportCandidateRequirement(
                    ledger_id = snapshot.ledgerId.value,
                    candidate_id = ids.candidateId.value,
                    requirement_index = 0L,
                    requirement = "formal_transaction_creation",
                )
                val status = if (snapshot.completeness == ImportCompleteness.VALID_COMPLETE) {
                    "pending_confirmation"
                } else {
                    "incomplete"
                }
                database.ledgerQueries.insertImportStatusHistory(
                    ledger_id = snapshot.ledgerId.value,
                    candidate_id = ids.candidateId.value,
                    sequence = 1L,
                    status_id = ids.statusHistoryId.value,
                    status = status,
                    request_id = identity.requestId.value,
                    operation_class = "creation",
                )
                failure.failAt(ImportSpineFailurePoint.INTAKE_AFTER_CANDIDATE)
                database.ledgerQueries.insertImportReceipt(
                    ledger_id = snapshot.ledgerId.value,
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
                snapshot.ledgerId.value,
                snapshot.identity.inputRef,
                snapshot.identity.recordOrdinal.toLong(),
            ).executeAsOneOrNull() ?: throw unexpected
            if (intakeEquivalent(existing.toStoredFacts(), snapshot, digest)) {
                intakeIdentityNoChange(snapshot, existing.source_id)
            } else {
                ImportIntakeResult.Rejected(
                    SpineDiagnostics.identityCollision(snapshot.identity.inputRef, snapshot.identity.recordOrdinal),
                )
            }
        }
    }

    override fun commitOnce(
        identity: ImportRequestIdentity,
        snapshot: ImportCandidateDecisionSnapshot,
        createFormalTransaction: (resolved: ImportResolvedSourceFacts) -> DomainResult<ImportFormalCommit>,
    ): ImportCandidateDecisionResult {
        require(identity.ledgerId == snapshot.ledgerId) {
            "Request identity and snapshot must belong to the same ledger"
        }
        return rollbackTypedRejection { database.transactionWithResult {
            database.ledgerQueries.claimImportRequest(identity.ledgerId.value, identity.requestId.value, "confirm_candidate")
            if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
                return@transactionWithResult resolveConfirm(identity, snapshot)
            }
            if (snapshot.decision != ImportCandidateDecision.CONFIRM || snapshot.confirmFields == null) {
                abortImportSpine(
                    ImportCandidateDecisionResult.Rejected(
                        SpineDiagnostics.referenceIntegrityViolation(snapshot.candidateId),
                    ),
                )
            }
            val state = database.ledgerQueries.selectImportCandidateCurrentStatus(
                snapshot.ledgerId.value,
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
            val source = database.ledgerQueries.selectImportSourceForCandidate(
                snapshot.ledgerId.value,
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
                snapshot.ledgerId.value,
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
            val created = when (val result = createFormalTransaction(resolved)) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> abortImportSpine(
                    ImportCandidateDecisionResult.Rejected(
                        SpineDiagnostics.domainValidationFailed(snapshot.candidateId),
                    ),
                )
            }
            persistFormal(created.transaction)
            failure.failAt(ImportSpineFailurePoint.CONFIRM_AFTER_FORMAL)
            database.ledgerQueries.insertImportDecisionSnapshot(
                ledger_id = snapshot.ledgerId.value,
                request_id = identity.requestId.value,
                decision = "confirm",
                candidate_id = snapshot.candidateId.value,
                expected_content_hash = snapshot.expectedContentHash,
                category_id = snapshot.confirmFields?.categoryId?.value,
                funding_account_id = snapshot.confirmFields?.fundingAccountId?.value,
                explicit_confirmed_at = snapshot.explicitConfirmedAt,
            )
            val sequence = database.ledgerQueries.selectImportCandidateLatestSequence(
                snapshot.ledgerId.value,
                snapshot.candidateId.value,
            ).executeAsOne() + 1L
            database.ledgerQueries.insertImportStatusHistory(
                ledger_id = snapshot.ledgerId.value,
                candidate_id = snapshot.candidateId.value,
                sequence = sequence,
                status_id = created.statusHistoryId.value,
                status = "confirmed",
                request_id = identity.requestId.value,
                operation_class = "creation",
            )
            database.ledgerQueries.insertImportConfirmation(
                ledger_id = snapshot.ledgerId.value,
                confirmation_id = created.confirmationId.value,
                request_id = identity.requestId.value,
                candidate_id = snapshot.candidateId.value,
                status_id = created.statusHistoryId.value,
                transaction_id = created.transaction.transaction.id.value,
                operation_class = "creation",
                confirmed_at = snapshot.explicitConfirmedAt,
            )
            database.ledgerQueries.insertImportReceipt(
                ledger_id = snapshot.ledgerId.value,
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
        require(identity.ledgerId == snapshot.ledgerId) {
            "Request identity and snapshot must belong to the same ledger"
        }
        return rollbackTypedRejection { database.transactionWithResult {
            database.ledgerQueries.claimImportRequest(identity.ledgerId.value, identity.requestId.value, "reject_candidate")
            if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
                return@transactionWithResult resolveReject(identity, snapshot)
            }
            if (snapshot.decision != ImportCandidateDecision.REJECT || snapshot.confirmFields != null) {
                abortImportSpine(
                    ImportCandidateDecisionResult.Rejected(
                        SpineDiagnostics.referenceIntegrityViolation(snapshot.candidateId),
                    ),
                )
            }
            val state = database.ledgerQueries.selectImportCandidateCurrentStatus(
                snapshot.ledgerId.value,
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
                snapshot.ledgerId.value,
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
                snapshot.ledgerId.value,
                source.source_id,
            ).executeAsOneOrNull()
                ?: abortImportSpine(
                    ImportCandidateDecisionResult.Rejected(
                        SpineDiagnostics.referenceIntegrityViolation(snapshot.candidateId),
                    ),
                )
            val statusId = allocateStatusId()
            database.ledgerQueries.insertImportDecisionSnapshot(
                ledger_id = snapshot.ledgerId.value,
                request_id = identity.requestId.value,
                decision = "reject",
                candidate_id = snapshot.candidateId.value,
                expected_content_hash = snapshot.expectedContentHash,
                category_id = null,
                funding_account_id = null,
                explicit_confirmed_at = null,
            )
            val sequence = database.ledgerQueries.selectImportCandidateLatestSequence(
                snapshot.ledgerId.value,
                snapshot.candidateId.value,
            ).executeAsOne() + 1L
            database.ledgerQueries.insertImportStatusHistory(
                ledger_id = snapshot.ledgerId.value,
                candidate_id = snapshot.candidateId.value,
                sequence = sequence,
                status_id = statusId.value,
                status = "rejected",
                request_id = identity.requestId.value,
                operation_class = "status_transition",
            )
            database.ledgerQueries.insertImportReceipt(
                ledger_id = snapshot.ledgerId.value,
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
            stored.category_id == snapshot.confirmFields?.categoryId?.value &&
            stored.funding_account_id == snapshot.confirmFields?.fundingAccountId?.value &&
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
        returnedIds = intakeReturnedIds(snapshot.ledgerId.value, sourceId),
        receipt = null,
        reasonCode = SPINE_NO_CHANGE_REASON_CODE,
    )

    private data class StoredSourceFacts(
        val sourceId: String,
        val contentHash: String,
        val completeness: String,
        val amountMinor: Long?,
        val currencyCode: String?,
        val currencyPrecision: Long?,
        val occurredAt: String?,
        val directionToken: String?,
        val statusToken: String?,
    )

    private fun com.unifiedledger.data.db.SelectImportSourceByIdentity.toStoredFacts(): StoredSourceFacts =
        StoredSourceFacts(
            sourceId = source_id,
            contentHash = content_hash,
            completeness = completeness,
            amountMinor = amount_minor,
            currencyCode = currency_code,
            currencyPrecision = currency_precision,
            occurredAt = occurred_at,
            directionToken = direction_token,
            statusToken = status_token,
        )

    private fun com.unifiedledger.data.db.SelectImportSourceByOwnerRequest.toStoredFacts(): StoredSourceFacts =
        StoredSourceFacts(
            sourceId = source_id,
            contentHash = content_hash,
            completeness = completeness,
            amountMinor = amount_minor,
            currencyCode = currency_code,
            currencyPrecision = currency_precision,
            occurredAt = occurred_at,
            directionToken = direction_token,
            statusToken = status_token,
        )

    private fun intakeEquivalent(
        stored: StoredSourceFacts,
        snapshot: ImportIntakeSnapshot,
        digest: String,
    ): Boolean = stored.contentHash == digest &&
        stored.completeness == snapshot.completeness.name.lowercase() &&
        stored.amountMinor == snapshot.facts.amountMinor &&
        stored.currencyCode == snapshot.facts.currencyCode &&
        stored.currencyPrecision == snapshot.facts.currencyPrecision.toLong() &&
        stored.occurredAt == snapshot.facts.occurredAt &&
        stored.directionToken == snapshot.facts.directionToken &&
        stored.statusToken == snapshot.facts.statusToken

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
