package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import kotlin.time.Instant

data class CandidateId(
    val value: String,
)

data class SourceRecordId(
    val value: String,
)

data class EvidenceId(
    val value: String,
)

enum class SourceCompleteness { COMPLETE, MISSING_DESTINATION }

enum class CandidateStatus { PENDING_CONFIRMATION, CONFIRMED }

data class Rg03ManualTransferSnapshot(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val occurredAt: Instant,
    val sourceAccountId: AccountId,
    val destinationAccountId: AccountId,
    val sourceDebit: Money,
    val destinationCredit: Money,
    val fee: Money,
    val feeCategoryId: CategoryId,
    val originalOccurredAtText: String = occurredAt.toString(),
)

data class Rg03SourceSnapshot(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val sourceId: SourceRecordId,
    val evidenceId: EvidenceId,
    val observedAt: Instant,
    val sourceAccountId: AccountId,
    val destinationAccountId: AccountId?,
    val sourceDebit: Money,
    val destinationCredit: Money?,
    val fee: Money?,
    val feeCategoryId: CategoryId,
    val completeness: SourceCompleteness,
    val originalObservedAtText: String = observedAt.toString(),
)

data class Rg03MirrorSnapshot(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val sourceId: SourceRecordId,
    val evidenceId: EvidenceId,
    val observedAt: Instant,
    val accountId: AccountId,
    val credit: Money,
    val originalObservedAtText: String = observedAt.toString(),
)

sealed interface Rg03Command {
    data class ManualTransfer(
        val snapshot: Rg03ManualTransferSnapshot,
        val rawInput: Rg03DecodedInput,
    ) : Rg03Command

    data class ImportSource(
        val snapshot: Rg03SourceSnapshot,
    ) : Rg03Command

    data class ConfirmCandidate(
        val requestId: RequestId,
        val candidateId: CandidateId,
        val confirmed: Boolean,
        val ledgerId: LedgerId,
    ) : Rg03Command

    data class ImportMirror(
        val snapshot: Rg03MirrorSnapshot,
    ) : Rg03Command
}

data class Rg03PersistedTransferCandidate(
    val ledgerId: LedgerId,
    val candidateId: CandidateId,
    val status: CandidateStatus,
    val sourceId: SourceRecordId,
    val evidenceId: EvidenceId,
    val sourceAccountId: AccountId,
    val destinationAccountId: AccountId?,
    val sourceDebit: Money,
    val destinationCredit: Money?,
    val fee: Money?,
    val feeCategoryId: CategoryId,
    val observedAt: Instant,
    val originalObservedAtText: String = observedAt.toString(),
)

fun interface Rg03CandidateRecoveryPort {
    fun load(
        ledgerId: LedgerId,
        candidateId: CandidateId,
    ): Rg03PersistedTransferCandidate?
}

data class Rg03MirrorScope(
    val candidateId: CandidateId,
)

data class Rg03MirrorTarget(
    val candidateId: CandidateId,
    val transactionId: TransactionId,
    val destinationPostingId: PostingId,
    val destinationAccountId: AccountId,
    val destinationCredit: Money,
)

sealed interface Rg03MirrorBindingResult {
    data class Unique(
        val target: Rg03MirrorTarget,
    ) : Rg03MirrorBindingResult

    data object Missing : Rg03MirrorBindingResult

    data object Ambiguous : Rg03MirrorBindingResult
}

fun interface Rg03MirrorBindingPort {
    fun resolve(
        ledgerId: LedgerId,
        scope: Rg03MirrorScope,
    ): Rg03MirrorBindingResult
}

sealed interface Rg03PreparedOperation {
    data class CreateManual(
        val snapshot: Rg03ManualTransferSnapshot,
    ) : Rg03PreparedOperation

    data class StoreSource(
        val snapshot: Rg03SourceSnapshot,
    ) : Rg03PreparedOperation

    data class ConfirmCandidate(
        val requestId: RequestId,
        val candidate: Rg03PersistedTransferCandidate,
    ) : Rg03PreparedOperation

    data class MergeMirror(
        val snapshot: Rg03MirrorSnapshot,
        val target: Rg03MirrorTarget?,
    ) : Rg03PreparedOperation
}

enum class Rg03ExecutionError {
    DOMAIN_VALIDATION_FAILED,
    KNOWN_ACCOUNT_REQUIRED,
    DISTINCT_OWN_REAL_FINANCIAL_ACCOUNTS_REQUIRED,
    OWN_ACCOUNT_REQUIRED,
    REAL_FINANCIAL_ACCOUNT_REQUIRED,
    MUST_BE_POSITIVE,
    AMOUNTS_MUST_BALANCE,
    SAME_CURRENCY_REQUIRED,
    FEE_MUST_NOT_BE_NEGATIVE,
    INVALID_FEE_CATEGORY,
    ASSET_ACCOUNT_REQUIRED,
    EXPLICIT_CONFIRMATION_REQUIRED,
    CANDIDATE_NOT_FOUND,
    CANDIDATE_NOT_PENDING,
    CANDIDATE_INCOMPLETE,
    MIRROR_TARGET_NOT_FOUND,
    AMBIGUOUS_MIRROR_TARGET,
    MIRROR_TARGET_MISMATCH,
}

enum class ReturnedIdKind {
    TRANSACTION,
    CONFIRMATION,
    SOURCE,
    EVIDENCE,
    CANDIDATE,
    EVIDENCE_LINK,
}

data class ReturnedId(
    val kind: ReturnedIdKind,
    val id: String,
)

sealed interface Rg03ExecutionResult {
    data class Accepted(
        val returnedIds: List<ReturnedId>,
    ) : Rg03ExecutionResult

    data class NoChange(
        val returnedIds: List<ReturnedId>,
    ) : Rg03ExecutionResult

    data class Rejected(
        val error: Rg03ExecutionError,
        val field: String? = null,
    ) : Rg03ExecutionResult

    data object RequestIdentityConflict : Rg03ExecutionResult
}

fun interface Rg03PreparedOperationCommitPort {
    fun commit(operation: Rg03PreparedOperation): Rg03ExecutionResult
}

class ExecuteRg03Operation(
    private val candidateRecovery: Rg03CandidateRecoveryPort,
    private val mirrorBinding: Rg03MirrorBindingPort,
    private val commitPort: Rg03PreparedOperationCommitPort,
) {
    private var currentLifecycleScope: Rg03LifecycleScope? = null

    fun execute(command: Rg03Command): Rg03ExecutionResult =
        when (command) {
            is Rg03Command.ManualTransfer -> startManual(command)
            is Rg03Command.ImportSource -> startSource(command)
            is Rg03Command.ConfirmCandidate -> confirm(command)
            is Rg03Command.ImportMirror -> mergeMirror(command.snapshot)
        }

    private fun startManual(command: Rg03Command.ManualTransfer): Rg03ExecutionResult {
        currentLifecycleScope = null
        return commitPort.commit(Rg03PreparedOperation.CreateManual(command.snapshot))
    }

    private fun startSource(command: Rg03Command.ImportSource): Rg03ExecutionResult {
        currentLifecycleScope = null
        val result = commitPort.commit(Rg03PreparedOperation.StoreSource(command.snapshot))
        currentLifecycleScope =
            result.singleReturnedCandidateId()?.let {
                Rg03LifecycleScope(command.snapshot.ledgerId, it)
            }
        return result
    }

    private fun confirm(command: Rg03Command.ConfirmCandidate): Rg03ExecutionResult {
        currentLifecycleScope = null
        if (!command.confirmed) {
            return Rg03ExecutionResult.Rejected(Rg03ExecutionError.EXPLICIT_CONFIRMATION_REQUIRED)
        }
        val candidate =
            candidateRecovery.load(command.ledgerId, command.candidateId)
                ?: return Rg03ExecutionResult.Rejected(Rg03ExecutionError.CANDIDATE_NOT_FOUND)
        if (candidate.ledgerId != command.ledgerId || candidate.candidateId != command.candidateId) {
            return Rg03ExecutionResult.Rejected(Rg03ExecutionError.CANDIDATE_NOT_FOUND)
        }
        if (candidate.destinationAccountId == null || candidate.destinationCredit == null || candidate.fee == null) {
            return Rg03ExecutionResult.Rejected(Rg03ExecutionError.CANDIDATE_INCOMPLETE)
        }
        val result = commitPort.commit(Rg03PreparedOperation.ConfirmCandidate(command.requestId, candidate))
        if (result is Rg03ExecutionResult.Accepted || result is Rg03ExecutionResult.NoChange) {
            currentLifecycleScope = Rg03LifecycleScope(command.ledgerId, command.candidateId)
        }
        return result
    }

    private fun mergeMirror(snapshot: Rg03MirrorSnapshot): Rg03ExecutionResult {
        val scope = currentLifecycleScope?.takeIf { it.ledgerId == snapshot.ledgerId }
        val target =
            when (
                val binding =
                    scope?.let {
                        mirrorBinding.resolve(it.ledgerId, Rg03MirrorScope(it.candidateId))
                    }
            ) {
                is Rg03MirrorBindingResult.Unique -> binding.target
                null,
                Rg03MirrorBindingResult.Missing,
                Rg03MirrorBindingResult.Ambiguous,
                -> null
            }
        if (
            target != null &&
            (target.destinationAccountId != snapshot.accountId || target.destinationCredit != snapshot.credit)
        ) {
            return Rg03ExecutionResult.Rejected(Rg03ExecutionError.MIRROR_TARGET_MISMATCH)
        }
        return commitPort.commit(Rg03PreparedOperation.MergeMirror(snapshot, target))
    }
}

private data class Rg03LifecycleScope(
    val ledgerId: LedgerId,
    val candidateId: CandidateId,
)

private fun Rg03ExecutionResult.singleReturnedCandidateId(): CandidateId? {
    val returnedIds =
        when (this) {
            is Rg03ExecutionResult.Accepted -> returnedIds
            is Rg03ExecutionResult.NoChange -> returnedIds
            is Rg03ExecutionResult.Rejected,
            Rg03ExecutionResult.RequestIdentityConflict,
            -> return null
        }
    return returnedIds
        .filter { it.kind == ReturnedIdKind.CANDIDATE }
        .singleOrNull()
        ?.id
        ?.let(::CandidateId)
}
