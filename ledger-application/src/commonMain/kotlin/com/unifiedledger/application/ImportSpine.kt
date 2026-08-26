package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId

/**
 * P4-02 Shared Import Spine: frozen application contract (spec section 8).
 *
 * All names and signatures below are the frozen round-A contract. ledger-domain is
 * unchanged; ledger-data implements the commit ports.
 */

/** Frozen no_change reason token (spec section 3). */
const val SPINE_NO_CHANGE_REASON_CODE = "equivalent_replay"

data class ImportRequestId(val value: String)
data class ImportSourceId(val value: String)
data class ImportEvidenceId(val value: String)
data class ImportCandidateId(val value: String)
data class ImportConfirmationId(val value: String)
data class ImportStatusHistoryId(val value: String)
data class ImportDuplicateCandidateId(val value: String)
data class ImportDuplicateReviewId(val value: String)

data class ImportRawIdentity(val ledgerId: LedgerId, val inputRef: String, val recordOrdinal: Int)
data class ImportRequestIdentity(val ledgerId: LedgerId, val requestId: ImportRequestId)

enum class ImportRecordKind(val storageValue: String, val contractVersion: Int) {
    ORDINARY_FLOW_SOURCE("ordinary_flow_source", 1),
    TRANSFER_FLOW_SOURCE("transfer_flow_source", 2),
    TRANSFER_FLOW_SOURCE_MISSING_LEG("transfer_flow_source_missing_leg", 2),
    CREDIT_EXPENSE_SOURCE("credit_expense_source", 3),
    CREDIT_REPAYMENT_SOURCE("credit_repayment_source", 3),
    MIXED_PAYMENT_SOURCE("mixed_payment_source", 3),
}

/**
 * P4-06 slice 1 (D-107 section 3.2): candidate-layer semantic marker carried by every
 * v3 intake row. The refund variant of a credit expense is not a fourth record kind:
 * it is marked here and formalized under the D-078 refund contract semantics.
 */
enum class ImportPaymentVariant(val storageValue: String) {
    CREDIT_EXPENSE_DIRECT("credit_expense_direct"),
    CREDIT_EXPENSE_REFUND("credit_expense_refund"),
    CREDIT_REPAYMENT("credit_repayment"),
    MIXED_PAYMENT("mixed_payment"),
}

/**
 * Payment-leg provenance for v3 rows (D-107 section 2.1 privacy boundary): only the
 * normalized whitelist leg-kind tokens ever leave the parser; masked tails, accounts
 * and raw bracket annotations never persist. [assetLegKindToken] is nullable for a
 * repayment row (advisory input; column 7 may be empty) and [creditLegKindToken] is
 * always null for a repayment row.
 */
data class ImportPaymentProfile(
    val variant: ImportPaymentVariant,
    val assetLegKindToken: String?,
    val creditLegKindToken: String?,
)

/** Frozen variant-shape rules (D-107 section 3.2); violations are SPINE_INTAKE_INVALID. */
fun importPaymentProfileShapeValid(profile: ImportPaymentProfile?): Boolean {
    if (profile == null) return false
    return when (profile.variant) {
        ImportPaymentVariant.CREDIT_EXPENSE_DIRECT,
        ImportPaymentVariant.CREDIT_EXPENSE_REFUND,
        -> profile.creditLegKindToken != null && profile.assetLegKindToken == null
        ImportPaymentVariant.CREDIT_REPAYMENT -> profile.creditLegKindToken == null
        ImportPaymentVariant.MIXED_PAYMENT ->
            profile.creditLegKindToken != null && profile.assetLegKindToken != null
    }
}

enum class ImportCompleteness { VALID_COMPLETE, VALID_INCOMPLETE }

enum class ImportFundingState { SETTLED, NO_FUNDS, UNRESOLVED }
enum class ImportDuplicateCandidateKind { EXACT_BUSINESS_TUPLE, CLOSED_OR_FAILED_NO_FUNDS }
enum class ImportDuplicateStatus { DEFERRED, CONFIRMED_DUPLICATE, CONFIRMED_DISTINCT, DISMISSED_LOOKALIKE, REJECTED }

// P4-07 funding facts are explicit at every construction site (D-105 section 5): the
// caller owns them; there is no silent default. The legacy-settled token is the frozen
// relay value for sources without an approved funding-state provider contract.
const val IMPORT_FUNDING_RULE_LEGACY_SETTLED = "legacy-settled-v1"

data class ImportSourceFacts(
    val amountMinor: Long,
    val currencyCode: String,
    val currencyPrecision: Int,
    val occurredAt: String,
    val directionToken: String,
    val statusToken: String?,
    val fundingState: ImportFundingState,
    val fundingRuleId: String,
    val fundingRuleVersion: Int,
)

data class ImportIntakeRequest(
    val identity: ImportRequestIdentity,
    val inputRef: String,
    val recordOrdinal: Int,
    val recordKind: ImportRecordKind,
    val facts: ImportSourceFacts,
    val completeness: ImportCompleteness,
    val candidateGeneratedAt: String,
    /** Non-null iff recordKind is a v3 kind (spine validation gate, D-107 section 3.2). */
    val paymentProfile: ImportPaymentProfile? = null,
)

data class ImportIntakeSnapshot(
    val identity: ImportRequestIdentity,
    val inputRef: String,
    val recordOrdinal: Int,
    val recordKind: ImportRecordKind,
    val facts: ImportSourceFacts,
    val completeness: ImportCompleteness,
    val contentHash: String,
    val candidateGeneratedAt: String,
    val paymentProfile: ImportPaymentProfile? = null,
)

data class ImportDuplicateComparisonSnapshot(
    val subjectSourceId: ImportSourceId,
    val possibleExistingSourceId: ImportSourceId?,
    val recordKind: ImportRecordKind,
    val contractVersion: Int,
    val amountMinor: Long,
    val currencyCode: String,
    val currencyPrecision: Int,
    val occurredAt: String,
    val directionToken: String,
    val statusToken: String?,
)

data class ImportDuplicateReviewRequest(
    val identity: ImportRequestIdentity,
    val candidateId: ImportDuplicateCandidateId,
    val expectedComparisonFingerprint: String,
    val decision: ImportDuplicateStatus,
    val reasonToken: String,
    val reviewedAt: String,
    val reviewerReference: String,
    val generatedAt: String,
    val reviewId: ImportDuplicateReviewId,
    val historyId: ImportStatusHistoryId,
)

data class ImportDuplicateReviewReceipt(
    val requestId: ImportRequestId,
    val candidateId: ImportDuplicateCandidateId,
    val reviewId: ImportDuplicateReviewId,
    val historyId: ImportStatusHistoryId,
    val outcome: ImportDuplicateStatus,
)

sealed interface ImportDuplicateReviewResult {
    data class Accepted(val receipt: ImportDuplicateReviewReceipt) : ImportDuplicateReviewResult
    data class NoChange(val receipt: ImportDuplicateReviewReceipt, val reasonCode: String) : ImportDuplicateReviewResult
    data class Rejected(val diagnostic: ImportDiagnostic) : ImportDuplicateReviewResult
}

enum class ImportCandidateDecision { CONFIRM, REJECT }

sealed interface ImportConfirmDecisionFields {
    data class OrdinaryFlow(
        val categoryId: CategoryId,
        val fundingAccountId: AccountId,
    ) : ImportConfirmDecisionFields

    data class TransferFlow(
        val fromAccountId: AccountId,
        val toAccountId: AccountId,
    ) : ImportConfirmDecisionFields

    /** P4-06 slice 1 (D-107 section 3.3): credit expense direct variant. */
    data class CreditExpenseFlow(
        val categoryId: CategoryId,
        val creditLiabilityAccountId: AccountId,
    ) : ImportConfirmDecisionFields

    /** P4-06 slice 1: credit expense refund variant (links the original transaction). */
    data class CreditExpenseRefundFlow(
        val categoryId: CategoryId,
        val creditLiabilityAccountId: AccountId,
        val originalTransactionId: TransactionId,
    ) : ImportConfirmDecisionFields

    /** P4-06 slice 1: credit repayment (asset leg pays the liability principal). */
    data class CreditRepaymentFlow(
        val assetAccountId: AccountId,
        val creditLiabilityAccountId: AccountId,
    ) : ImportConfirmDecisionFields

    /**
     * P4-06 slice 2 (D-108 section 4.1): mixed payment (asset leg + credit liability
     * leg). A null leg amount means incomplete decision data: the candidate stays
     * pending and the confirmation gate rejects with zero writes until completed.
     */
    data class MixedPaymentFlow(
        val categoryId: CategoryId,
        val assetAccountId: AccountId,
        val creditLiabilityAccountId: AccountId,
        val assetLegMinor: Long?,
        val creditLegMinor: Long?,
    ) : ImportConfirmDecisionFields
}

data class ImportCandidateDecisionSnapshot(
    val candidateId: ImportCandidateId,
    val decision: ImportCandidateDecision,
    val expectedContentHash: String,
    val explicitConfirmedAt: String?,
    val confirmDecisionFields: ImportConfirmDecisionFields?,
)

data class ImportCandidateConfirmRequest(
    val identity: ImportRequestIdentity,
    val candidateId: ImportCandidateId,
    val expectedContentHash: String,
    val explicitConfirmedAt: String?,
    val decisionFields: ImportConfirmDecisionFields,
)

data class ImportCandidateRejectRequest(
    val identity: ImportRequestIdentity,
    val candidateId: ImportCandidateId,
    val expectedContentHash: String,
)

data class ImportReceipt(
    val requestId: ImportRequestId,
    val sourceId: ImportSourceId?,
    val evidenceId: ImportEvidenceId?,
    val candidateId: ImportCandidateId,
    val confirmationId: ImportConfirmationId?,
    val transactionId: TransactionId?,
)

data class ImportDiagnosticLocation(
    val inputRef: String?,
    val recordOrdinal: Int?,
    val requestId: ImportRequestId?,
    val candidateId: ImportCandidateId?,
)

sealed interface ImportDiagnostic {
    val code: String
    val severity: String
    val scope: String
    val location: ImportDiagnosticLocation
}

data class ImportDiagnosticRecord(
    override val code: String,
    override val severity: String,
    override val scope: String,
    override val location: ImportDiagnosticLocation,
) : ImportDiagnostic

/**
 * The spec section 3 registry. Each code populates exactly its registered safe-location
 * fields; message text is unstable and never compared.
 */
object SpineDiagnostics {
    fun identityCollision(inputRef: String, recordOrdinal: Int): ImportDiagnostic =
        ImportDiagnosticRecord(
            "SPINE_IDENTITY_COLLISION", "fatal", "record",
            ImportDiagnosticLocation(inputRef, recordOrdinal, null, null),
        )

    fun requestIdentityConflict(requestId: ImportRequestId): ImportDiagnostic =
        ImportDiagnosticRecord(
            "SPINE_REQUEST_IDENTITY_CONFLICT", "conflict", "request",
            ImportDiagnosticLocation(null, null, requestId, null),
        )

    fun candidateNotPending(candidateId: ImportCandidateId): ImportDiagnostic =
        ImportDiagnosticRecord(
            "SPINE_CANDIDATE_NOT_PENDING", "invalid", "candidate",
            ImportDiagnosticLocation(null, null, null, candidateId),
        )

    fun candidateNotFound(candidateId: ImportCandidateId): ImportDiagnostic =
        ImportDiagnosticRecord(
            "SPINE_CANDIDATE_NOT_FOUND", "invalid", "candidate",
            ImportDiagnosticLocation(null, null, null, candidateId),
        )

    fun candidateIncomplete(candidateId: ImportCandidateId): ImportDiagnostic =
        ImportDiagnosticRecord(
            "SPINE_CANDIDATE_INCOMPLETE", "invalid", "candidate",
            ImportDiagnosticLocation(null, null, null, candidateId),
        )

    fun staleFingerprint(candidateId: ImportCandidateId): ImportDiagnostic =
        ImportDiagnosticRecord(
            "SPINE_STALE_FINGERPRINT", "stale", "candidate",
            ImportDiagnosticLocation(null, null, null, candidateId),
        )

    fun referenceIntegrityViolation(candidateId: ImportCandidateId): ImportDiagnostic =
        ImportDiagnosticRecord(
            "SPINE_REFERENCE_INTEGRITY_VIOLATION", "invalid", "candidate",
            ImportDiagnosticLocation(null, null, null, candidateId),
        )

    fun intakeInvalid(inputRef: String, recordOrdinal: Int): ImportDiagnostic =
        ImportDiagnosticRecord(
            "SPINE_INTAKE_INVALID", "invalid", "record",
            ImportDiagnosticLocation(inputRef, recordOrdinal, null, null),
        )

    fun domainValidationFailed(candidateId: ImportCandidateId): ImportDiagnostic =
        ImportDiagnosticRecord(
            "SPINE_DOMAIN_VALIDATION_FAILED", "invalid", "candidate",
            ImportDiagnosticLocation(null, null, null, candidateId),
        )

    fun transferNotConfirmable(candidateId: ImportCandidateId): ImportDiagnostic =
        ImportDiagnosticRecord(
            "SPINE_TRANSFER_NOT_CONFIRMABLE", "invalid", "candidate",
            ImportDiagnosticLocation(null, null, null, candidateId),
        )

    fun decisionKindMismatch(candidateId: ImportCandidateId): ImportDiagnostic =
        ImportDiagnosticRecord(
            "SPINE_DECISION_KIND_MISMATCH", "invalid", "candidate",
            ImportDiagnosticLocation(null, null, null, candidateId),
        )

    fun duplicateNotConfirmable(candidateId: ImportCandidateId): ImportDiagnostic =
        ImportDiagnosticRecord(
            "SPINE_DUPLICATE_NOT_CONFIRMABLE", "invalid", "candidate",
            ImportDiagnosticLocation(null, null, null, candidateId),
        )
}

enum class ImportReturnedIdKind { SOURCE, EVIDENCE, CANDIDATE, CONFIRMATION, TRANSACTION }

data class ImportReturnedId(val kind: ImportReturnedIdKind, val id: String)

sealed interface ImportIntakeResult {
    data class Accepted(val receipt: ImportReceipt, val returnedIds: List<ImportReturnedId>) : ImportIntakeResult

    /**
     * Same-request equivalent replay (O-02) returns the original receipt; the raw
     * identity idempotent path (O-03) writes nothing and has no receipt object
     * (receipt = null).
     */
    data class NoChange(
        val returnedIds: List<ImportReturnedId>,
        val receipt: ImportReceipt?,
        val reasonCode: String,
    ) : ImportIntakeResult

    data class Rejected(val diagnostic: ImportDiagnostic) : ImportIntakeResult
}

sealed interface ImportCandidateDecisionResult {
    data class Accepted(val receipt: ImportReceipt, val returnedIds: List<ImportReturnedId>) :
        ImportCandidateDecisionResult

    /** Equivalent replay returns the original receipt (D-098:1516); reasonCode is frozen. */
    data class NoChange(val receipt: ImportReceipt, val reasonCode: String) : ImportCandidateDecisionResult

    data class Rejected(val diagnostic: ImportDiagnostic) : ImportCandidateDecisionResult
}

data class ImportIntakeIds(
    val sourceId: ImportSourceId,
    val evidenceId: ImportEvidenceId,
    val candidateId: ImportCandidateId,
    val statusHistoryId: ImportStatusHistoryId,
    val duplicateIds: List<ImportDuplicateIntakeIds> = emptyList(),
)

data class ImportDuplicateIntakeIds(
    val candidateId: ImportDuplicateCandidateId,
    val statusHistoryId: ImportStatusHistoryId,
)

fun interface ImportIntakeIdSource {
    fun next(): ImportIntakeIds
}

data class ImportFormalIds(
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val postingIds: List<PostingId>,
)

data class ImportCommitIds(
    val confirmationId: ImportConfirmationId,
    val statusHistoryId: ImportStatusHistoryId,
    val formalIds: ImportFormalIds,
)

/** Invoked only inside the winning first-request callback (ConfirmedManualExpense.kt:133-141). */
fun interface ImportIdSource {
    fun next(): ImportCommitIds
}

/** Status-row ID for the winning reject path. */
fun interface ImportStatusIdSource {
    fun next(): ImportStatusHistoryId
}

/**
 * Source facts resolved by the store from the persisted source row and passed to the
 * confirm callback. Callers must not fabricate economic facts. Confirm only resolves
 * pending (valid_complete) sources, so statusToken is non-null in practice; the
 * nullability only mirrors the import_source_record column shape.
 */
data class ImportResolvedSourceFacts(
    val amountMinor: Long,
    val currencyCode: String,
    val currencyPrecision: Int,
    val occurredAt: String,
    val directionToken: String,
    val statusToken: String?,
)

data class ImportFormalCommit(
    val confirmationId: ImportConfirmationId,
    val statusHistoryId: ImportStatusHistoryId,
    val transaction: FormalTransaction,
)

data class ImportCandidateFormalizationInput(
    val ledgerId: LedgerId,
    val resolved: ImportResolvedSourceFacts,
    val decisionFields: ImportConfirmDecisionFields,
)

fun interface ImportCandidateFormalFactory {
    fun create(input: ImportCandidateFormalizationInput, ids: ImportCommitIds): DomainResult<ImportFormalCommit>
}

fun interface ImportIntakeCommitPort {
    fun commitIntake(
        identity: ImportRequestIdentity,
        snapshot: ImportIntakeSnapshot,
        allocateIds: () -> ImportIntakeIds,
    ): ImportIntakeResult
}

// Plain interface: the frozen round-A contract declares two methods, so this is not a
// SAM fun interface. Implementations are ledger-data ports.
interface ImportCandidateCommitPort {
    /**
     * confirmCandidate(identity, snapshot) confirmation port with commitOnce semantics
     * (ConfirmedManualExpense.kt:89-112). Implementations MUST: keep identity and
     * snapshot on the same ledger; invoke the callback at most once and only on the
     * winning first request; leave zero residue (including the request claim) on any
     * typed rejection and keep the identity available for a corrected request; commit
     * success all-or-nothing; return the original receipt on equivalent replay without
     * invoking the callback or overwriting the formal transaction.
     */
    fun commitOnce(
        identity: ImportRequestIdentity,
        snapshot: ImportCandidateDecisionSnapshot,
        allocateIds: () -> ImportCommitIds,
        catalog: LedgerCatalog,
        createFormalTransaction: (input: ImportCandidateFormalizationInput, ids: ImportCommitIds) -> DomainResult<ImportFormalCommit>,
    ): ImportCandidateDecisionResult

    fun commitRejectOnce(
        identity: ImportRequestIdentity,
        snapshot: ImportCandidateDecisionSnapshot,
        allocateStatusId: () -> ImportStatusHistoryId,
    ): ImportCandidateDecisionResult
}

/** P4-07 owns duplicate review request identity independently from import_request. */
fun interface ImportDuplicateReviewCommitPort {
    fun commitReviewOnce(request: ImportDuplicateReviewRequest): ImportDuplicateReviewResult
}

class ReviewImportDuplicateCandidate(
    private val commitPort: ImportDuplicateReviewCommitPort,
) {
    fun execute(request: ImportDuplicateReviewRequest): ImportDuplicateReviewResult =
        commitPort.commitReviewOnce(request)
}

class ExecuteImportIntake(
    private val commitPort: ImportIntakeCommitPort,
    private val idSource: ImportIntakeIdSource,
    private val fingerprint: ImportContentFingerprint,
) {
    fun execute(request: ImportIntakeRequest): ImportIntakeResult {
        val violation = validate(request)
        if (violation != null) return ImportIntakeResult.Rejected(violation)

        // The canonical digest of the inbound facts is materialized exactly once at the
        // intake boundary: the injected fingerprint canonicalizes here (and fails closed
        // on ill-formed tokens), while the commit port persists the same canonical value
        // derived from the snapshot facts.
        val contentHash = fingerprint.digest(request.recordKind, request.facts, request.paymentProfile)

        val identity = request.identity
        val snapshot = ImportIntakeSnapshot(
            identity = request.identity,
            inputRef = request.inputRef,
            recordOrdinal = request.recordOrdinal,
            recordKind = request.recordKind,
            facts = request.facts,
            completeness = request.completeness,
            contentHash = contentHash,
            candidateGeneratedAt = request.candidateGeneratedAt,
            paymentProfile = request.paymentProfile,
        )
        return commitPort.commitIntake(identity, snapshot) { idSource.next() }
    }

    private fun validate(request: ImportIntakeRequest): ImportDiagnostic? {
        if (request.inputRef.isEmpty() ||
            request.inputRef.length > 256 ||
            request.inputRef.any { it.code < 0x20 || it.code == 0x7f }
        ) {
            return SpineDiagnostics.intakeInvalid(request.inputRef, request.recordOrdinal)
        }
        if (request.recordOrdinal < 0) {
            return SpineDiagnostics.intakeInvalid(request.inputRef, request.recordOrdinal)
        }
        if (request.facts.currencyPrecision < 0) {
            return SpineDiagnostics.intakeInvalid(request.inputRef, request.recordOrdinal)
        }
        if (request.facts.currencyCode.isEmpty() ||
            request.facts.occurredAt.isEmpty() ||
            request.facts.directionToken.isEmpty()
        ) {
            return SpineDiagnostics.intakeInvalid(request.inputRef, request.recordOrdinal)
        }
        if (request.facts.statusToken != null && request.facts.statusToken.isEmpty()) {
            return SpineDiagnostics.intakeInvalid(request.inputRef, request.recordOrdinal)
        }
        if (request.completeness == ImportCompleteness.VALID_COMPLETE && request.facts.statusToken == null) {
            return SpineDiagnostics.intakeInvalid(request.inputRef, request.recordOrdinal)
        }
        // P4-06 slice 1 payment-profile gate (D-107 section 3.2), symmetric to the
        // five-fact checks above: a v3 kind must carry a non-null shape-valid profile;
        // a v1/v2 kind must carry none.
        val v3 = request.recordKind.contractVersion == 3
        if (v3 != (request.paymentProfile != null)) {
            return SpineDiagnostics.intakeInvalid(request.inputRef, request.recordOrdinal)
        }
        if (request.paymentProfile != null && !importPaymentProfileShapeValid(request.paymentProfile)) {
            return SpineDiagnostics.intakeInvalid(request.inputRef, request.recordOrdinal)
        }
        // VALID_INCOMPLETE records carry at least one present fact by the frozen type
        // shape (amount/currency/occurred_at/direction are non-null).
        return null
    }
}

class ConfirmImportCandidate(
    private val commitPort: ImportCandidateCommitPort,
    private val idSource: ImportIdSource,
    private val createFormalTransaction: ImportCandidateFormalFactory,
    private val catalog: LedgerCatalog,
) {
    fun execute(request: ImportCandidateConfirmRequest): ImportCandidateDecisionResult {
        val snapshot = ImportCandidateDecisionSnapshot(
            candidateId = request.candidateId,
            decision = ImportCandidateDecision.CONFIRM,
            expectedContentHash = request.expectedContentHash,
            explicitConfirmedAt = request.explicitConfirmedAt,
            confirmDecisionFields = request.decisionFields,
        )
        return commitPort.commitOnce(
            request.identity, snapshot,
            allocateIds = { idSource.next() },
            catalog = catalog,
        ) { input, ids ->
            createFormalTransaction.create(input, ids)
        }
    }
}

class RejectImportCandidate(
    private val commitPort: ImportCandidateCommitPort,
    private val statusIdSource: ImportStatusIdSource,
) {
    fun execute(request: ImportCandidateRejectRequest): ImportCandidateDecisionResult {
        val snapshot = ImportCandidateDecisionSnapshot(
            candidateId = request.candidateId,
            decision = ImportCandidateDecision.REJECT,
            expectedContentHash = request.expectedContentHash,
            explicitConfirmedAt = null,
            confirmDecisionFields = null,
        )
        return commitPort.commitRejectOnce(request.identity, snapshot) { statusIdSource.next() }
    }
}
