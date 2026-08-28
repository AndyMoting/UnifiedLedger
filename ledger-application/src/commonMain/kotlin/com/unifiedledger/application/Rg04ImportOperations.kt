package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.MixedPaymentExpenseIds
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import kotlin.time.Instant

data class Rg04CandidateId(
    val value: String,
)

data class Rg04SourceId(
    val value: String,
)

data class Rg04EvidenceId(
    val value: String,
)

enum class Rg04ImportCompleteness { COMPLETE, MISSING_FUNDING_LEG }

enum class Rg04ImportCandidateStatus { PENDING_CONFIRMATION, CONFIRMED }

data class Rg04ImportFunding(
    val accountId: AccountId?,
    val amount: Money,
    val evidenceAvailable: Boolean,
)

data class Rg04ImportSourceSnapshot(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val sourceId: Rg04SourceId,
    val evidenceId: Rg04EvidenceId,
    val observedAt: Instant,
    val observedAtText: String,
    val total: Money,
    val suggestedCategoryId: CategoryId?,
    val funding: List<Rg04ImportFunding>,
    val completeness: Rg04ImportCompleteness,
    val confidence: String,
    val candidateKind: String,
    val candidateId: Rg04CandidateId,
    val candidateStatusId: String,
)

data class Rg04ImportConfirmationSnapshot(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val candidateId: Rg04CandidateId,
    val categoryId: CategoryId,
    val funding: List<Rg04FundingSnapshot>,
    val confirmed: Boolean,
    val formalIds: MixedPaymentExpenseIds,
    val confirmationId: String,
    val confirmedStatusId: String,
    val relationId: String,
    val relationDisplayName: String,
    val assetEvidenceLinkId: String,
    val assetReconciliationId: String,
    val liabilityReconciliationId: String,
)

data class Rg04ImportMirrorSnapshot(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val sourceId: Rg04SourceId,
    val evidenceId: Rg04EvidenceId,
    val observedAt: Instant,
    val observedAtText: String,
    val accountId: AccountId,
    val amount: Money,
    val evidenceLinkId: String,
)

sealed interface Rg04DecodedImportOperation {
    val expected: Rg04Expected

    data class Source(
        val snapshot: Rg04ImportSourceSnapshot,
        override val expected: Rg04Expected,
    ) : Rg04DecodedImportOperation

    data class Confirm(
        val snapshot: Rg04ImportConfirmationSnapshot,
        override val expected: Rg04Expected,
    ) : Rg04DecodedImportOperation

    data class Mirror(
        val snapshot: Rg04ImportMirrorSnapshot,
        override val expected: Rg04Expected,
    ) : Rg04DecodedImportOperation
}

sealed interface Rg04PreparedImportOperation {
    data class StoreSource(
        val snapshot: Rg04ImportSourceSnapshot,
    ) : Rg04PreparedImportOperation

    data class ConfirmCandidate(
        val snapshot: Rg04ImportConfirmationSnapshot,
    ) : Rg04PreparedImportOperation

    data class MergeMirror(
        val snapshot: Rg04ImportMirrorSnapshot,
    ) : Rg04PreparedImportOperation
}

enum class Rg04ImportReturnedIdKind { SOURCE, EVIDENCE, CANDIDATE, CONFIRMATION, TRANSACTION, EVIDENCE_LINK }

data class Rg04ImportReturnedId(
    val kind: Rg04ImportReturnedIdKind,
    val id: String,
)

enum class Rg04ImportExecutionError {
    EXPLICIT_CONFIRMATION_REQUIRED,
    CANDIDATE_NOT_FOUND,
    CANDIDATE_NOT_PENDING,
    CANDIDATE_INCOMPLETE,
    MIRROR_TARGET_NOT_FOUND,
    AMBIGUOUS_MIRROR_TARGET,
    MIRROR_TARGET_MISMATCH,
    RECONCILIATION_PRECONDITION_FAILED,
    DOMAIN_VALIDATION_FAILED,
}

sealed interface Rg04ImportExecutionResult {
    data class Accepted(
        val returnedIds: List<Rg04ImportReturnedId>,
    ) : Rg04ImportExecutionResult

    data class NoChange(
        val returnedIds: List<Rg04ImportReturnedId>,
    ) : Rg04ImportExecutionResult

    data class Rejected(
        val error: Rg04ImportExecutionError,
        val field: String? = null,
    ) : Rg04ImportExecutionResult

    data object RequestIdentityConflict : Rg04ImportExecutionResult
}

fun interface Rg04ImportCommitPort {
    fun commit(operation: Rg04PreparedImportOperation): Rg04ImportExecutionResult
}

class ExecuteRg04ImportOperation(
    private val port: Rg04ImportCommitPort,
) {
    fun execute(operation: Rg04DecodedImportOperation): Rg04ImportExecutionResult =
        when (operation) {
            is Rg04DecodedImportOperation.Source -> port.commit(Rg04PreparedImportOperation.StoreSource(operation.snapshot))
            is Rg04DecodedImportOperation.Confirm ->
                if (!operation.snapshot.confirmed) {
                    Rg04ImportExecutionResult.Rejected(Rg04ImportExecutionError.EXPLICIT_CONFIRMATION_REQUIRED, "confirmed")
                } else {
                    port.commit(Rg04PreparedImportOperation.ConfirmCandidate(operation.snapshot))
                }
            is Rg04DecodedImportOperation.Mirror -> port.commit(Rg04PreparedImportOperation.MergeMirror(operation.snapshot))
        }
}

data class Rg04ImportTarget(
    val candidateId: Rg04CandidateId,
    val transactionId: TransactionId,
    val postingId: PostingId,
    val accountId: AccountId,
    val amount: Money,
)
