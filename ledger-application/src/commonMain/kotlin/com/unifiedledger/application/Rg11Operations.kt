package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.ExplicitOperationConfirmation
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PeriodicAllocationAnchor
import com.unifiedledger.domain.PeriodicAllocationCadence
import com.unifiedledger.domain.PeriodicAllocationInstallment
import com.unifiedledger.domain.PeriodicAllocationInstallmentStatus
import com.unifiedledger.domain.PeriodicAllocationRevision
import com.unifiedledger.domain.PeriodicAllocationSchedule
import com.unifiedledger.domain.PeriodicAllocationScheduleStatus
import com.unifiedledger.domain.PeriodicAllocationViolation
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSet
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.Transaction
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersion
import com.unifiedledger.domain.TransactionVersionAppendIds
import com.unifiedledger.domain.TransactionVersionChange
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.appendVersion
import com.unifiedledger.domain.createExplicitOperationConfirmation
import com.unifiedledger.domain.createInitialInstallments
import com.unifiedledger.domain.createPeriodicAllocationRevision
import com.unifiedledger.domain.createPeriodicAllocationSchedule
import com.unifiedledger.domain.createRevisedInstallments
import com.unifiedledger.domain.deriveInstallmentAllocationStatus
import com.unifiedledger.domain.deriveScheduleAllocationStatus
import com.unifiedledger.domain.validateInstallmentRecognition
import kotlin.time.Instant

/**
 * D-085 RG-11 periodic allocation application layer (shard 2 of the RG-11 runtime).
 *
 * Executes the four approved operation classes of the frozen direct-v2 contract
 * `golden/rules/rg-11.json` (contract_version 2.0.0) on top of the shard-1 domain entities
 * ([PeriodicAllocationSchedule], [PeriodicAllocationRevision], [PeriodicAllocationInstallment]),
 * the shared [FormalTransaction.appendVersion] primitive and
 * [createExplicitOperationConfirmation]. It mirrors the Rg08Operations.kt / Rg10Operations.kt
 * pattern: typed operation boundary, deterministic fingerprints, idempotent replay receipts,
 * zero-effect rejections and a full [Rg11Snapshot] of the executable state.
 *
 * The frozen contract counts 22 operations: 8 create / 10 recognize / 3 revise / 1 correct
 * (root-main 6, root-revision 6, root-z-rejections 10). Rejection reason codes and
 * `attempted_input` field paths are recomputed in the exact stable priority of the Python
 * validator (`tools/python/golden_cases/v2.py` `_periodic_allocation_rejection`): every
 * rejected or incomplete path keeps the baseline field by field and has zero formal effect.
 */
data class Rg11OperationIdentity(
    val ledgerId: LedgerId,
    val value: String,
)

/**
 * The four closed action codes of the approved RG-11 registry. Unlike RG-08 there is no
 * dedicated retry action: replays reuse the action of the replayed operation and are detected
 * by identity fingerprint in [Rg11Runtime.commit].
 */
enum class Rg11Action(
    val code: String,
) {
    CREATE_PERIODIC_ALLOCATION("create_periodic_allocation"),
    RECOGNIZE_PERIODIC_ALLOCATION_INSTALLMENT("recognize_periodic_allocation_installment"),
    REVISE_PERIODIC_ALLOCATION("revise_periodic_allocation"),
    CORRECT_TRANSACTION_VERSION("correct_transaction_version"),
}

/**
 * Per-rejection closed predicates driven by the frozen `operation-reject-*` fixtures. The
 * [Rg11InvalidInput] form carries the raw `attempted_input` map (like Rg08InvalidInput); the
 * runtime recomputes the exact frozen reason code and `$.attempted_input.*` field path.
 */
enum class Rg11InvalidPredicate {
    EXACT_DECIMAL_AMOUNT,
    EXACT_DECIMAL_REMAINING_AMOUNT,
    ZERO_OR_NEGATIVE_AMOUNT,
    ZERO_OR_NEGATIVE_REMAINING_AMOUNT,
    UNSUPPORTED_CURRENCY,
    CURRENCY_MISMATCH,
    INVALID_ANCHOR,
    INSTALLMENT_NOT_PENDING,
    EXCEEDS_REMAINING_PREPAID,
    INVALID_REVISION_BOUNDARY,
    INVALID_INSTALLMENT_COUNT,
    INVALID_REMAINING_INSTALLMENT_COUNT,
    INSTALLMENT_AMOUNT_MISMATCH,
    REMAINING_AMOUNT_MISMATCH,
    TRANSACTION_NOT_CORRECTABLE,
}

data class Rg11InvalidInput(
    val requestId: RequestId,
    val action: Rg11Action,
    val predicate: Rg11InvalidPredicate,
    val attemptedInput: Map<String, String?>,
)

/**
 * D-085 RG-11 create input, matching the frozen `main-create` / `revision-create` shape:
 * payment/prepaid accounts, category, exact amount, currency, `start_at`, anchor, cadence,
 * explicit confirmation, actual payment `occurred_at` and `installment_count`.
 */
data class Rg11CreateInput(
    val requestId: RequestId,
    val paymentAccountId: AccountId,
    val prepaidAccountId: AccountId,
    val categoryId: CategoryId,
    val amount: Money,
    val currency: CurrencyUnit,
    val startAt: Instant,
    val startAtText: String = startAt.toString(),
    val anchor: PeriodicAllocationAnchor,
    val cadence: PeriodicAllocationCadence = PeriodicAllocationCadence.MONTHLY,
    val explicitConfirmation: Boolean,
    val occurredAt: Instant,
    val occurredAtText: String = occurredAt.toString(),
    val installmentCount: Int,
)

/**
 * Deterministic ids of a create operation. The fixture adapter generates them from the
 * normalized input (GoldenV2Identity); the runtime only consumes them.
 */
data class Rg11CreateIds(
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val paymentPostingId: PostingId,
    val prepaidPostingId: PostingId,
    val scheduleId: String,
    val revisionId: String,
    val installmentIds: List<String>,
)

/**
 * D-085 RG-11 recognition input: schedule, installment, exact installment amount, currency
 * and explicit confirmation (frozen `main-recognize-*` shape).
 */
data class Rg11RecognizeInput(
    val requestId: RequestId,
    val scheduleId: String,
    val installmentId: String,
    val amount: Money,
    val currency: CurrencyUnit,
    val explicitConfirmation: Boolean,
)

data class Rg11RecognizeIds(
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val expensePostingId: PostingId,
    val prepaidPostingId: PostingId,
    val auditLinkId: String,
)

/**
 * D-085 RG-11 revise input: recognized-through boundary, exact remaining amount, currency,
 * explicit confirmation and remaining installment count (frozen `revision-revise` shape).
 */
data class Rg11ReviseInput(
    val requestId: RequestId,
    val scheduleId: String,
    val recognizedThrough: String,
    val remainingAmount: Money,
    val currency: CurrencyUnit,
    val explicitConfirmation: Boolean,
    val remainingInstallmentCount: Int,
)

data class Rg11ReviseIds(
    val revisionId: String,
    val installmentIds: List<String>,
)

/**
 * D-085 RG-11 `correct_transaction_version` input with the `statistics_time` semantics:
 * target transaction, correction kind, new statistics time and explicit confirmation.
 */
data class Rg11CorrectInput(
    val requestId: RequestId,
    val transactionId: TransactionId,
    val correctionKind: String,
    val statisticsAt: Instant,
    val statisticsAtText: String = statisticsAt.toString(),
    val explicitConfirmation: Boolean,
)

/**
 * Deterministic ids of a correction. `operationId` is the id of the operation itself (the
 * frozen confirmation references `operation_id == operation["id"]`, not the request id);
 * `confirmationCreatedAt` is the confirmation time carried by the id set because the frozen
 * input has no confirmation time field.
 */
data class Rg11CorrectIds(
    val versionId: TransactionVersionId,
    val confirmationId: String,
    val operationId: String,
    val confirmationCreatedAt: Instant,
    val confirmationCreatedAtText: String = confirmationCreatedAt.toString(),
)

/** Generic retry (RG-08 pattern): replays by input anchor id with the replayed action. */
data class Rg11RetryInput(
    val inputId: String,
    val replayedAction: Rg11Action,
)

sealed interface Rg11Operation {
    val ledgerId: LedgerId
    val action: Rg11Action
    val identity: Rg11OperationIdentity

    data class CreatePeriodicAllocation(
        override val ledgerId: LedgerId,
        val input: Rg11CreateInput,
        val ids: Rg11CreateIds,
    ) : Rg11Operation {
        override val action = Rg11Action.CREATE_PERIODIC_ALLOCATION
        override val identity = Rg11OperationIdentity(ledgerId, input.requestId.value)
    }

    data class RecognizePeriodicAllocationInstallment(
        override val ledgerId: LedgerId,
        val input: Rg11RecognizeInput,
        val ids: Rg11RecognizeIds,
    ) : Rg11Operation {
        override val action = Rg11Action.RECOGNIZE_PERIODIC_ALLOCATION_INSTALLMENT
        override val identity = Rg11OperationIdentity(ledgerId, input.requestId.value)
    }

    data class RevisePeriodicAllocation(
        override val ledgerId: LedgerId,
        val input: Rg11ReviseInput,
        val ids: Rg11ReviseIds,
    ) : Rg11Operation {
        override val action = Rg11Action.REVISE_PERIODIC_ALLOCATION
        override val identity = Rg11OperationIdentity(ledgerId, input.requestId.value)
    }

    data class CorrectTransactionVersion(
        override val ledgerId: LedgerId,
        val input: Rg11CorrectInput,
        val ids: Rg11CorrectIds,
    ) : Rg11Operation {
        override val action = Rg11Action.CORRECT_TRANSACTION_VERSION
        override val identity = Rg11OperationIdentity(ledgerId, input.requestId.value)
    }

    data class RetryIdempotentInput(
        override val ledgerId: LedgerId,
        val input: Rg11RetryInput,
    ) : Rg11Operation {
        override val action = input.replayedAction
        override val identity = Rg11OperationIdentity(ledgerId, input.inputId)
    }

    data class InvalidInput(
        override val ledgerId: LedgerId,
        val input: Rg11InvalidInput,
    ) : Rg11Operation {
        override val action = input.action
        override val identity = Rg11OperationIdentity(ledgerId, input.requestId.value)
    }
}

sealed interface Rg11ReturnedId {
    data class Transaction(
        val id: TransactionId,
    ) : Rg11ReturnedId

    data class Version(
        val id: TransactionVersionId,
    ) : Rg11ReturnedId

    data class DomainEntity(
        val id: String,
    ) : Rg11ReturnedId

    data class Confirmation(
        val id: String,
    ) : Rg11ReturnedId

    data class Request(
        val id: String,
    ) : Rg11ReturnedId
}

/**
 * Closed rejection reasons of the frozen rg-11.json. The first nine entries are the frozen
 * `operation-reject-*` reason codes; the next three (`installment_amount_mismatch`,
 * `remaining_amount_mismatch`, `transaction_not_correctable`) are registered rejection codes
 * of the v2 validator recomputation (`_periodic_allocation_rejection`) that no frozen fixture
 * triggers; the last three are application-level guards.
 */
enum class Rg11RejectionReason(
    val code: String,
) {
    EXACT_DECIMAL_STRING_REQUIRED("exact_decimal_string_required"),
    MUST_BE_POSITIVE("must_be_positive"),
    UNSUPPORTED_CURRENCY("unsupported_currency"),
    CURRENCY_MISMATCH("currency_mismatch"),
    INVALID_ANCHOR("invalid_anchor"),
    INSTALLMENT_NOT_PENDING("installment_not_pending"),
    EXCEEDS_REMAINING_PREPAID("exceeds_remaining_prepaid"),
    INVALID_REVISION_BOUNDARY("invalid_revision_boundary"),
    INVALID_INSTALLMENT_COUNT("invalid_installment_count"),
    INSTALLMENT_AMOUNT_MISMATCH("installment_amount_mismatch"),
    REMAINING_AMOUNT_MISMATCH("remaining_amount_mismatch"),
    TRANSACTION_NOT_CORRECTABLE("transaction_not_correctable"),
    EXPLICIT_CONFIRMATION_REQUIRED("explicit_confirmation_required"),
    INVALID_RG11_INPUT("invalid_rg11_input"),
    DOMAIN_REJECTED("domain_rejected"),
}

/** Frozen `field_path` values; rejection paths always use the `$.attempted_input.*` form. */
enum class Rg11FieldPath(
    val value: String,
) {
    INPUT_CONFIRMATION("$.input.explicit_confirmation"),
    INPUT_PAYMENT_ACCOUNT_ID("$.input.payment_account_id"),
    INPUT_PREPAID_ACCOUNT_ID("$.input.prepaid_account_id"),
    INPUT_CATEGORY_ID("$.input.category_id"),
    INPUT_CORRECTION_KIND("$.input.correction_kind"),
    ATTEMPTED_REQUEST_ID("$.attempted_input.request_id"),
    ATTEMPTED_AMOUNT("$.attempted_input.amount"),
    ATTEMPTED_REMAINING_AMOUNT("$.attempted_input.remaining_amount"),
    ATTEMPTED_CURRENCY("$.attempted_input.currency"),
    ATTEMPTED_ANCHOR("$.attempted_input.anchor"),
    ATTEMPTED_INSTALLMENT_ID("$.attempted_input.installment_id"),
    ATTEMPTED_RECOGNIZED_THROUGH("$.attempted_input.recognized_through"),
    ATTEMPTED_REMAINING_INSTALLMENT_COUNT("$.attempted_input.remaining_installment_count"),
    ATTEMPTED_INSTALLMENT_COUNT("$.attempted_input.installment_count"),
    ATTEMPTED_TRANSACTION_ID("$.attempted_input.transaction_id"),
    ATTEMPTED_SCHEDULE_ID("$.attempted_input.schedule_id"),
    ATTEMPTED_REVISION_ID("$.attempted_input.revision_id"),
}

sealed interface Rg11ExecutionResult {
    class Accepted(
        returnedIds: List<Rg11ReturnedId>,
    ) : Rg11ExecutionResult {
        private val snapshot = returnedIds.toList()
        val returnedIds: List<Rg11ReturnedId> get() = snapshot.toList()

        override fun equals(other: Any?) = other is Accepted && snapshot == other.snapshot

        override fun hashCode(): Int = snapshot.hashCode()

        override fun toString(): String = "Accepted(returnedIds=$snapshot)"
    }

    class NoChange(
        returnedIds: List<Rg11ReturnedId>,
    ) : Rg11ExecutionResult {
        private val snapshot = returnedIds.toList()
        val returnedIds: List<Rg11ReturnedId> get() = snapshot.toList()

        override fun equals(other: Any?) = other is NoChange && snapshot == other.snapshot

        override fun hashCode(): Int = snapshot.hashCode()

        override fun toString(): String = "NoChange(returnedIds=$snapshot)"
    }

    data class Rejected(
        val reason: Rg11RejectionReason,
        val fieldPath: Rg11FieldPath,
    ) : Rg11ExecutionResult

    data object RequestIdentityConflict : Rg11ExecutionResult
}

fun interface Rg11CommitPort {
    fun commit(operation: Rg11Operation): Rg11ExecutionResult
}

/**
 * A formal transaction as appended by the runtime: the formal chain plus the booking time
 * texts used by report periods and the per-version explicit-operation-confirmation ownership
 * of `correct_transaction_version` (the frozen v2 version carries `confirmation_id`).
 */
data class Rg11FormalTransactionRecord(
    val formalTransaction: FormalTransaction,
    val createdAt: Instant,
    val createdAtText: String? = null,
    val statisticsAtText: String? = null,
    val versionConfirmationIds: Map<TransactionVersionId, String> = emptyMap(),
)

/**
 * RG-11 audit link. The frozen `periodic_allocation_recognition` link is
 * `{type, from: {kind: "domain_entity", id: installment}, to: {kind: "transaction", id},
 * payload: {}}`; the payload is always empty. Shard 1 delivered no domain audit-link type,
 * so the application owns the record.
 */
data class Rg11AuditLink(
    val id: String,
    val linkType: String,
    val fromKind: String,
    val fromId: String,
    val toKind: String,
    val toId: String,
)

/**
 * Per-posting projection facts the golden postings need (`role`, `category_id`) plus the
 * reconciliation eligibility flag (the domain [Posting] carries neither; eligibility follows
 * the frozen rule "eligible owned real account posting").
 */
data class Rg11PostingSemantic(
    val role: String?,
    val reconciliationEligible: Boolean,
    val categoryId: String? = null,
)

/** Day-period report values of the frozen rg-11 report registry. */
data class Rg11Report(
    val budgetMinor: Long = 0L,
    val cashOutflowMinor: Long = 0L,
    val categoryEffectMinor: Long = 0L,
    val consumptionMinor: Long = 0L,
    val incomeMinor: Long = 0L,
    val netWorthChangeMinor: Long = 0L,
)

/** A derived status with the deterministic fixture id conventions. */
data class Rg11DerivedStatus(
    val id: String,
    val targetKind: String,
    val targetId: String,
    val statusName: String,
    val value: String,
)

/**
 * Full executable state of the RG-11 runtime. `versions` and `postings` are reachable through
 * [Rg11FormalTransactionRecord.formalTransaction] (the oracle projects them exactly like the
 * RG-08 oracle does); balances, reports, reconciliation and derived statuses are recomputed
 * from the immutable facts, never stored.
 */
data class Rg11Snapshot(
    val formalTransactions: List<Rg11FormalTransactionRecord>,
    val schedules: List<PeriodicAllocationSchedule>,
    val revisions: List<PeriodicAllocationRevision>,
    val installments: List<PeriodicAllocationInstallment>,
    val confirmations: List<ExplicitOperationConfirmation>,
    val auditLinks: List<Rg11AuditLink>,
    val postingSemantics: Map<String, Rg11PostingSemantic>,
    val balances: Map<AccountId, Money>,
    val reports: Map<String, Rg11Report>,
    val reconciliation: Map<String, String>,
    val derivedStatuses: List<Rg11DerivedStatus>,
)

/**
 * Deterministic runtime for the approved RG-11 action registry (D-085). Business transitions
 * stay independent from a database driver; persistence integration preserves this typed
 * operation boundary. Rejected/incomplete/no-change paths have zero formal effect and keep
 * the baseline state field by field. Recognition state is derived exclusively from the
 * immutable `periodic_allocation_recognition` audit links; the domain payloads never carry
 * confirmation state.
 */
class Rg11Runtime(
    private val catalog: LedgerCatalog,
    openingTransactions: List<Rg11FormalTransactionRecord>,
    schedules: List<PeriodicAllocationSchedule> = emptyList(),
    revisions: List<PeriodicAllocationRevision> = emptyList(),
    installments: List<PeriodicAllocationInstallment> = emptyList(),
    confirmations: List<ExplicitOperationConfirmation> = emptyList(),
    auditLinks: List<Rg11AuditLink> = emptyList(),
    postingSemantics: Map<String, Rg11PostingSemantic> = emptyMap(),
    private val knownCurrencies: Set<CurrencyUnit> = DEFAULT_KNOWN_CURRENCIES,
    private val utcOffsetSeconds: Int = DEFAULT_UTC_OFFSET_SECONDS,
) : Rg11CommitPort {
    constructor(catalog: LedgerCatalog, snapshot: Rg11Snapshot) : this(
        catalog,
        snapshot.formalTransactions,
        snapshot.schedules,
        snapshot.revisions,
        snapshot.installments,
        snapshot.confirmations,
        snapshot.auditLinks,
        snapshot.postingSemantics,
    )

    private val formalTransactions = openingTransactions.toMutableList()
    private val schedules = schedules.toMutableList()
    private val revisions = revisions.toMutableList()
    private val installments = installments.toMutableList()
    private val confirmations = confirmations.toMutableList()
    private val auditLinks = auditLinks.toMutableList()
    private val postingSemantics = postingSemantics.toMutableMap()
    private val receipts = mutableMapOf<Rg11OperationIdentity, Receipt>()

    private data class Receipt(
        val fingerprint: String,
        val result: Rg11ExecutionResult,
    )

    override fun commit(operation: Rg11Operation): Rg11ExecutionResult {
        if (operation is Rg11Operation.RetryIdempotentInput) {
            return replayRetry(operation)
        }
        val fingerprint = canonicalInput(operation)
        receipts[operation.identity]?.let { receipt ->
            return if (receipt.fingerprint == fingerprint) {
                when (val result = receipt.result) {
                    is Rg11ExecutionResult.Accepted -> Rg11ExecutionResult.NoChange(result.returnedIds)
                    else -> result
                }
            } else {
                Rg11ExecutionResult.RequestIdentityConflict
            }
        }
        val result =
            when (operation) {
                is Rg11Operation.CreatePeriodicAllocation -> createPeriodicAllocation(operation)
                is Rg11Operation.RecognizePeriodicAllocationInstallment -> recognizePeriodicAllocationInstallment(operation)
                is Rg11Operation.RevisePeriodicAllocation -> revisePeriodicAllocation(operation)
                is Rg11Operation.CorrectTransactionVersion -> correctTransactionVersion(operation)
                is Rg11Operation.RetryIdempotentInput -> replayRetry(operation)
                is Rg11Operation.InvalidInput -> rejectInvalidInput(operation)
            }
        if (result is Rg11ExecutionResult.Accepted || result is Rg11ExecutionResult.Rejected) {
            receipts[operation.identity] = Receipt(fingerprint, result)
        }
        return result
    }

    fun snapshot(): Rg11Snapshot =
        Rg11Snapshot(
            formalTransactions = formalTransactions.toList(),
            schedules = schedules.toList(),
            revisions = revisions.map { it.copy(installmentIds = it.installmentIds.toList()) },
            installments = installments.toList(),
            confirmations = confirmations.toList(),
            auditLinks = auditLinks.toList(),
            postingSemantics = postingSemantics.toMap(),
            balances = replayBalances(),
            reports = reports(),
            reconciliation = reconciliation(),
            derivedStatuses = derivedStatuses(),
        )

    fun operationFingerprint(operation: Rg11Operation): String = canonicalInput(operation)

    // ------------------------------------------------------------------ create

    private fun createPeriodicAllocation(operation: Rg11Operation.CreatePeriodicAllocation): Rg11ExecutionResult {
        val input = operation.input
        val ids = operation.ids
        if (!input.explicitConfirmation) {
            return rejected(Rg11RejectionReason.EXPLICIT_CONFIRMATION_REQUIRED, Rg11FieldPath.INPUT_CONFIRMATION)
        }
        // Frozen rejection priority of `_periodic_allocation_rejection` (create branch):
        // amount > 0, currency known, anchor shape, count, account currency, start_at anchor.
        if (input.amount.minorUnits <= 0L) {
            return rejected(Rg11RejectionReason.MUST_BE_POSITIVE, Rg11FieldPath.ATTEMPTED_AMOUNT)
        }
        if (input.currency !in knownCurrencies) {
            return rejected(Rg11RejectionReason.UNSUPPORTED_CURRENCY, Rg11FieldPath.ATTEMPTED_CURRENCY)
        }
        val anchor =
            when (val candidate = input.anchor) {
                PeriodicAllocationAnchor.MonthEnd -> candidate
                is PeriodicAllocationAnchor.DayOfMonth -> {
                    if (candidate.day < 1 || candidate.day > 28) {
                        return rejected(Rg11RejectionReason.INVALID_ANCHOR, Rg11FieldPath.ATTEMPTED_ANCHOR)
                    }
                    candidate
                }
            }
        if (input.installmentCount < 1) {
            return rejected(Rg11RejectionReason.INVALID_INSTALLMENT_COUNT, Rg11FieldPath.ATTEMPTED_INSTALLMENT_COUNT)
        }
        if (ids.installmentIds.size != input.installmentCount || ids.installmentIds.toSet().size != ids.installmentIds.size) {
            return rejected(Rg11RejectionReason.INVALID_RG11_INPUT, Rg11FieldPath.ATTEMPTED_REQUEST_ID)
        }
        val payment =
            catalogAccount(input.paymentAccountId)
                ?: return rejected(Rg11RejectionReason.CURRENCY_MISMATCH, Rg11FieldPath.ATTEMPTED_CURRENCY)
        val prepaid =
            catalogAccount(input.prepaidAccountId)
                ?: return rejected(Rg11RejectionReason.CURRENCY_MISMATCH, Rg11FieldPath.ATTEMPTED_CURRENCY)
        if (payment.currency != input.currency || prepaid.currency != input.currency) {
            return rejected(Rg11RejectionReason.CURRENCY_MISMATCH, Rg11FieldPath.ATTEMPTED_CURRENCY)
        }
        // Payload ownership guards (validator `_validate_periodic_allocations`): the payment
        // account is an owned real asset, the prepaid account an owned non-real asset and the
        // category an active second-level category posting to a non-owned non-real expense
        // account of the same currency.
        if (payment.kind != AccountKind.ASSET || !payment.ownedByUser || !payment.realAccount) {
            return rejected(Rg11RejectionReason.INVALID_RG11_INPUT, Rg11FieldPath.INPUT_PAYMENT_ACCOUNT_ID)
        }
        if (prepaid.kind != AccountKind.ASSET || !prepaid.ownedByUser || prepaid.realAccount) {
            return rejected(Rg11RejectionReason.INVALID_RG11_INPUT, Rg11FieldPath.INPUT_PREPAID_ACCOUNT_ID)
        }
        val category = catalog.categories.firstOrNull { it.id == input.categoryId }
        if (category == null || !category.active || category.parentId == null) {
            return rejected(Rg11RejectionReason.INVALID_RG11_INPUT, Rg11FieldPath.INPUT_CATEGORY_ID)
        }
        val categoryAccount = category.postingAccountId?.let(::catalogAccount)
        if (
            categoryAccount == null ||
            categoryAccount.kind != AccountKind.EXPENSE ||
            categoryAccount.ownedByUser ||
            categoryAccount.realAccount ||
            categoryAccount.currency != input.currency
        ) {
            return rejected(Rg11RejectionReason.INVALID_RG11_INPUT, Rg11FieldPath.INPUT_CATEGORY_ID)
        }
        val schedule =
            when (
                val result =
                    createPeriodicAllocationSchedule(
                        id = ids.scheduleId,
                        paymentTransactionId = ids.transactionId,
                        prepaidAccountId = input.prepaidAccountId,
                        categoryId = input.categoryId,
                        totalAmountMinor = input.amount.minorUnits,
                        currency = input.currency,
                        startAt = input.startAt,
                        anchor = anchor,
                        cadence = input.cadence,
                    )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return domainRejected(result.violation)
            }
        val revision =
            when (
                val result =
                    createPeriodicAllocationRevision(
                        id = ids.revisionId,
                        schedule = schedule,
                        previousRevision = null,
                        recognizedThrough = null,
                        remainingAmountMinor = input.amount.minorUnits,
                        currency = input.currency,
                        installmentIds = ids.installmentIds,
                        recognizedInstallmentIds = emptySet(),
                        recognizedAmountMinor = 0L,
                    )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return domainRejected(result.violation)
            }
        // `start_at` must itself hit the anchor (frozen step after the account checks); the
        // domain rejects a miss with `invalid_anchor`.
        val newInstallments =
            when (
                val result =
                    createInitialInstallments(
                        schedule = schedule,
                        revisionId = ids.revisionId,
                        installmentIds = ids.installmentIds,
                        utcOffsetSeconds = utcOffsetSeconds,
                    )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return domainRejected(result.violation)
            }
        val formal =
            when (
                val result = buildPurchaseTransaction(operation.ledgerId, input, ids)
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return domainRejected(DomainResultFailureViolation)
            }
        val record =
            Rg11FormalTransactionRecord(
                formal,
                createdAt = input.occurredAt,
                createdAtText = input.occurredAtText,
                statisticsAtText = input.occurredAtText,
            )
        if (
            !canAppendFormalTransaction(record) ||
            schedules.any { it.id == ids.scheduleId } ||
            revisions.any { it.id == ids.revisionId } ||
            installments.any { it.id in ids.installmentIds }
        ) {
            return rejected(Rg11RejectionReason.DOMAIN_REJECTED, Rg11FieldPath.ATTEMPTED_REQUEST_ID)
        }
        formalTransactions += record
        schedules += schedule
        revisions += revision
        installments += newInstallments
        postingSemantics[ids.paymentPostingId.value] = Rg11PostingSemantic("payment_asset", reconciliationEligible = true)
        postingSemantics[ids.prepaidPostingId.value] = Rg11PostingSemantic("prepaid_asset", reconciliationEligible = false)
        return accepted(
            listOf(
                Rg11ReturnedId.Transaction(ids.transactionId),
                Rg11ReturnedId.DomainEntity(ids.scheduleId),
            ),
        )
    }

    // ------------------------------------------------------------------ recognize

    private fun recognizePeriodicAllocationInstallment(
        operation: Rg11Operation.RecognizePeriodicAllocationInstallment,
    ): Rg11ExecutionResult {
        val input = operation.input
        val ids = operation.ids
        if (!input.explicitConfirmation) {
            return rejected(Rg11RejectionReason.EXPLICIT_CONFIRMATION_REQUIRED, Rg11FieldPath.INPUT_CONFIRMATION)
        }
        val schedule = schedules.firstOrNull { it.id == input.scheduleId }
        val installment = installments.firstOrNull { it.id == input.installmentId }
        if (schedule == null || installment == null || installment.scheduleId != schedule.id) {
            return rejected(Rg11RejectionReason.INSTALLMENT_NOT_PENDING, Rg11FieldPath.ATTEMPTED_INSTALLMENT_ID)
        }
        val latestRevision =
            latestRevisionOf(schedule.id)
                ?: return rejected(Rg11RejectionReason.INVALID_RG11_INPUT, Rg11FieldPath.ATTEMPTED_REQUEST_ID)
        val recognizedIds = recognizedInstallmentIds()
        if (installment.id in recognizedIds || installment.revisionId != latestRevision.id) {
            return rejected(Rg11RejectionReason.INSTALLMENT_NOT_PENDING, Rg11FieldPath.ATTEMPTED_INSTALLMENT_ID)
        }
        // Frozen rejection priority of the recognize branch: exceeds the prepaid balance
        // first, then currency, then exact installment amount.
        val prepaidBalance = replayBalances()[schedule.prepaidAccountId]?.minorUnits ?: 0L
        if (input.amount.minorUnits > prepaidBalance) {
            return rejected(Rg11RejectionReason.EXCEEDS_REMAINING_PREPAID, Rg11FieldPath.ATTEMPTED_AMOUNT)
        }
        if (input.currency != schedule.currency) {
            return rejected(Rg11RejectionReason.CURRENCY_MISMATCH, Rg11FieldPath.ATTEMPTED_CURRENCY)
        }
        if (input.amount.minorUnits != installment.amountMinor) {
            return rejected(Rg11RejectionReason.INSTALLMENT_AMOUNT_MISMATCH, Rg11FieldPath.ATTEMPTED_AMOUNT)
        }
        when (
            val result =
                validateInstallmentRecognition(
                    schedule = schedule,
                    latestRevision = latestRevision,
                    installments = installments,
                    recognizedInstallmentIds = recognizedIds,
                    requestedInstallmentId = input.installmentId,
                    requestedAmountMinor = input.amount.minorUnits,
                    requestedCurrency = input.currency,
                )
        ) {
            is DomainResult.Success -> Unit
            is DomainResult.Failure -> return domainRejected(result.violation)
        }
        val category =
            catalog.categories.firstOrNull { it.id == schedule.categoryId }
                ?: return rejected(Rg11RejectionReason.INVALID_RG11_INPUT, Rg11FieldPath.ATTEMPTED_REQUEST_ID)
        val expenseAccountId =
            category.postingAccountId
                ?: return rejected(Rg11RejectionReason.INVALID_RG11_INPUT, Rg11FieldPath.ATTEMPTED_REQUEST_ID)
        val formal =
            when (
                val result = buildRecognitionTransaction(operation.ledgerId, schedule, installment, expenseAccountId, ids)
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return domainRejected(DomainResultFailureViolation)
            }
        val record =
            Rg11FormalTransactionRecord(
                formal,
                createdAt = installment.scheduledAt,
                // The frozen recognition report periods are case-timezone (+08:00) local dates;
                // `kotlin.time.Instant.toString()` renders UTC, so the report text is rendered at
                // the runtime's fixed offset like every frozen `statistics_at` text.
                statisticsAtText = localDateTimeText(installment.scheduledAt, utcOffsetSeconds),
            )
        val auditLink =
            Rg11AuditLink(
                id = ids.auditLinkId,
                linkType = PERIODIC_ALLOCATION_RECOGNITION_LINK_TYPE,
                fromKind = "domain_entity",
                fromId = installment.id,
                toKind = "transaction",
                toId = ids.transactionId.value,
            )
        if (
            !canAppendFormalTransaction(record) ||
            auditLinks.any { it.id == ids.auditLinkId } ||
            auditLinks.any { it.fromId == installment.id }
        ) {
            return rejected(Rg11RejectionReason.DOMAIN_REJECTED, Rg11FieldPath.ATTEMPTED_REQUEST_ID)
        }
        formalTransactions += record
        auditLinks += auditLink
        postingSemantics[ids.expensePostingId.value] =
            Rg11PostingSemantic("expense", reconciliationEligible = false, categoryId = schedule.categoryId.value)
        postingSemantics[ids.prepaidPostingId.value] = Rg11PostingSemantic("prepaid_asset", reconciliationEligible = false)
        return accepted(listOf(Rg11ReturnedId.Transaction(ids.transactionId)))
    }

    // ------------------------------------------------------------------ revise

    private fun revisePeriodicAllocation(operation: Rg11Operation.RevisePeriodicAllocation): Rg11ExecutionResult {
        val input = operation.input
        val ids = operation.ids
        if (!input.explicitConfirmation) {
            return rejected(Rg11RejectionReason.EXPLICIT_CONFIRMATION_REQUIRED, Rg11FieldPath.INPUT_CONFIRMATION)
        }
        // Frozen rejection priority of the revise branch: remaining amount parseable/positive,
        // currency known, schedule and boundary, count, currency, remaining match.
        if (input.remainingAmount.minorUnits <= 0L) {
            return rejected(Rg11RejectionReason.MUST_BE_POSITIVE, Rg11FieldPath.ATTEMPTED_REMAINING_AMOUNT)
        }
        if (input.currency !in knownCurrencies) {
            return rejected(Rg11RejectionReason.UNSUPPORTED_CURRENCY, Rg11FieldPath.ATTEMPTED_CURRENCY)
        }
        val schedule = schedules.firstOrNull { it.id == input.scheduleId }
        val scheduleRevisions = revisions.filter { it.scheduleId == input.scheduleId }.sortedBy { it.revisionNumber }
        val latest = scheduleRevisions.lastOrNull()
        if (schedule == null || latest == null) {
            return rejected(Rg11RejectionReason.INVALID_REVISION_BOUNDARY, Rg11FieldPath.ATTEMPTED_RECOGNIZED_THROUGH)
        }
        val recognizedIds = recognizedInstallmentIds()
        val flags = latest.installmentIds.map { it in recognizedIds }
        if (
            !flags.any() ||
            flags.withIndex().any { (index, value) -> value && index > 0 && !flags[index - 1] }
        ) {
            return rejected(Rg11RejectionReason.INVALID_REVISION_BOUNDARY, Rg11FieldPath.ATTEMPTED_RECOGNIZED_THROUGH)
        }
        val boundary = latest.installmentIds[flags.indexOfLast { it }]
        if (input.recognizedThrough != boundary) {
            return rejected(Rg11RejectionReason.INVALID_REVISION_BOUNDARY, Rg11FieldPath.ATTEMPTED_RECOGNIZED_THROUGH)
        }
        if (input.remainingInstallmentCount < 1) {
            return rejected(Rg11RejectionReason.INVALID_INSTALLMENT_COUNT, Rg11FieldPath.ATTEMPTED_REMAINING_INSTALLMENT_COUNT)
        }
        if (ids.installmentIds.size != input.remainingInstallmentCount || ids.installmentIds.toSet().size != ids.installmentIds.size) {
            return rejected(Rg11RejectionReason.INVALID_RG11_INPUT, Rg11FieldPath.ATTEMPTED_REQUEST_ID)
        }
        if (input.currency != schedule.currency) {
            return rejected(Rg11RejectionReason.CURRENCY_MISMATCH, Rg11FieldPath.ATTEMPTED_CURRENCY)
        }
        val recognizedAmount = recognizedAmountMinor(schedule.id)
        val expectedRemaining =
            checkedSubtract(schedule.totalAmountMinor, recognizedAmount)
                ?: return rejected(Rg11RejectionReason.DOMAIN_REJECTED, Rg11FieldPath.ATTEMPTED_REMAINING_AMOUNT)
        if (input.remainingAmount.minorUnits != expectedRemaining) {
            return rejected(Rg11RejectionReason.REMAINING_AMOUNT_MISMATCH, Rg11FieldPath.ATTEMPTED_REMAINING_AMOUNT)
        }
        val revision =
            when (
                val result =
                    createPeriodicAllocationRevision(
                        id = ids.revisionId,
                        schedule = schedule,
                        previousRevision = latest,
                        recognizedThrough = input.recognizedThrough,
                        remainingAmountMinor = input.remainingAmount.minorUnits,
                        currency = input.currency,
                        installmentIds = ids.installmentIds,
                        recognizedInstallmentIds = recognizedIds,
                        recognizedAmountMinor = recognizedAmount,
                    )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return domainRejected(result.violation)
            }
        val builtInstallments =
            when (
                val result =
                    createRevisedInstallments(
                        schedule = schedule,
                        previousRevision = latest,
                        allInstallments = installments,
                        recognizedThrough = input.recognizedThrough,
                        remainingAmountMinor = input.remainingAmount.minorUnits,
                        installmentIds = ids.installmentIds,
                        newRevisionId = ids.revisionId,
                        utcOffsetSeconds = utcOffsetSeconds,
                    )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return domainRejected(result.violation)
            }
        // The frozen contract numbers installment sequences continuously across revisions in
        // revision order (revision 2 continues 4, 5, 6; validator `_validate_periodic_allocations`
        // "unique and consecutive in revision order"). The shard-1 domain helper restarts
        // sequences at 1 per revision, so the runtime offsets them by the schedule's current
        // maximum sequence.
        val sequenceOffset = installments.filter { it.scheduleId == schedule.id }.maxOfOrNull { it.sequence } ?: 0
        val newInstallments = builtInstallments.map { it.copy(sequence = it.sequence + sequenceOffset) }
        if (
            revisions.any { it.id == ids.revisionId } ||
            installments.any { existing -> ids.installmentIds.any { it == existing.id } }
        ) {
            return rejected(Rg11RejectionReason.DOMAIN_REJECTED, Rg11FieldPath.ATTEMPTED_REQUEST_ID)
        }
        revisions += revision
        installments += newInstallments
        return accepted(listOf(Rg11ReturnedId.DomainEntity(ids.revisionId)))
    }

    // ------------------------------------------------------------------ correct

    private fun correctTransactionVersion(operation: Rg11Operation.CorrectTransactionVersion): Rg11ExecutionResult {
        val input = operation.input
        val ids = operation.ids
        if (!input.explicitConfirmation) {
            return rejected(Rg11RejectionReason.EXPLICIT_CONFIRMATION_REQUIRED, Rg11FieldPath.INPUT_CONFIRMATION)
        }
        val index = formalTransactions.indexOfFirst { it.formalTransaction.transaction.id == input.transactionId }
        if (index < 0) {
            return rejected(Rg11RejectionReason.TRANSACTION_NOT_CORRECTABLE, Rg11FieldPath.ATTEMPTED_TRANSACTION_ID)
        }
        val record = formalTransactions[index]
        if (record.formalTransaction.transaction.kind != TransactionKind.PREPAID_RECOGNITION) {
            return rejected(Rg11RejectionReason.TRANSACTION_NOT_CORRECTABLE, Rg11FieldPath.ATTEMPTED_TRANSACTION_ID)
        }
        // RG-12 extends this dispatch with the `posting_facts` kind; only `statistics_time`
        // is in scope for RG-11 (D-085).
        if (input.correctionKind != CORRECTION_KIND_STATISTICS_TIME) {
            return rejected(Rg11RejectionReason.INVALID_RG11_INPUT, Rg11FieldPath.INPUT_CORRECTION_KIND)
        }
        val appended =
            when (
                val result =
                    record.formalTransaction.appendVersion(
                        change = TransactionVersionChange.StatisticsAt(input.statisticsAt),
                        ids = TransactionVersionAppendIds(versionId = ids.versionId),
                        newPostingSetId = null,
                    )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return domainRejected(result.violation, Rg11FieldPath.ATTEMPTED_TRANSACTION_ID)
            }
        val confirmation =
            when (
                val result =
                    createExplicitOperationConfirmation(
                        id = ids.confirmationId,
                        operationId = ids.operationId,
                        createdAt = ids.confirmationCreatedAt,
                    )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return domainRejected(result.violation, Rg11FieldPath.ATTEMPTED_TRANSACTION_ID)
            }
        if (appendedIdCollision(appended, record.formalTransaction) || confirmations.any { it.id == ids.confirmationId }) {
            return rejected(Rg11RejectionReason.DOMAIN_REJECTED, Rg11FieldPath.ATTEMPTED_REQUEST_ID)
        }
        formalTransactions[index] =
            record.copy(
                formalTransaction = appended,
                statisticsAtText = input.statisticsAtText,
                versionConfirmationIds = record.versionConfirmationIds + (ids.versionId to ids.confirmationId),
            )
        confirmations += confirmation
        return accepted(listOf(Rg11ReturnedId.Version(ids.versionId)))
    }

    // ------------------------------------------------------------------ retry / invalid

    private fun replayRetry(operation: Rg11Operation.RetryIdempotentInput): Rg11ExecutionResult {
        val receipt =
            receipts[operation.identity]
                ?: return Rg11ExecutionResult.RequestIdentityConflict
        return when (val result = receipt.result) {
            is Rg11ExecutionResult.Accepted -> Rg11ExecutionResult.NoChange(result.returnedIds)
            else -> result
        }
    }

    private fun rejectInvalidInput(operation: Rg11Operation.InvalidInput): Rg11ExecutionResult {
        val (reason, fieldPath) =
            when (operation.input.predicate) {
                Rg11InvalidPredicate.EXACT_DECIMAL_AMOUNT ->
                    Rg11RejectionReason.EXACT_DECIMAL_STRING_REQUIRED to Rg11FieldPath.ATTEMPTED_AMOUNT
                Rg11InvalidPredicate.EXACT_DECIMAL_REMAINING_AMOUNT ->
                    Rg11RejectionReason.EXACT_DECIMAL_STRING_REQUIRED to Rg11FieldPath.ATTEMPTED_REMAINING_AMOUNT
                Rg11InvalidPredicate.ZERO_OR_NEGATIVE_AMOUNT ->
                    Rg11RejectionReason.MUST_BE_POSITIVE to Rg11FieldPath.ATTEMPTED_AMOUNT
                Rg11InvalidPredicate.ZERO_OR_NEGATIVE_REMAINING_AMOUNT ->
                    Rg11RejectionReason.MUST_BE_POSITIVE to Rg11FieldPath.ATTEMPTED_REMAINING_AMOUNT
                Rg11InvalidPredicate.UNSUPPORTED_CURRENCY ->
                    Rg11RejectionReason.UNSUPPORTED_CURRENCY to Rg11FieldPath.ATTEMPTED_CURRENCY
                Rg11InvalidPredicate.CURRENCY_MISMATCH ->
                    Rg11RejectionReason.CURRENCY_MISMATCH to Rg11FieldPath.ATTEMPTED_CURRENCY
                Rg11InvalidPredicate.INVALID_ANCHOR ->
                    Rg11RejectionReason.INVALID_ANCHOR to Rg11FieldPath.ATTEMPTED_ANCHOR
                Rg11InvalidPredicate.INSTALLMENT_NOT_PENDING ->
                    Rg11RejectionReason.INSTALLMENT_NOT_PENDING to Rg11FieldPath.ATTEMPTED_INSTALLMENT_ID
                Rg11InvalidPredicate.EXCEEDS_REMAINING_PREPAID ->
                    Rg11RejectionReason.EXCEEDS_REMAINING_PREPAID to Rg11FieldPath.ATTEMPTED_AMOUNT
                Rg11InvalidPredicate.INVALID_REVISION_BOUNDARY ->
                    Rg11RejectionReason.INVALID_REVISION_BOUNDARY to Rg11FieldPath.ATTEMPTED_RECOGNIZED_THROUGH
                Rg11InvalidPredicate.INVALID_INSTALLMENT_COUNT ->
                    Rg11RejectionReason.INVALID_INSTALLMENT_COUNT to Rg11FieldPath.ATTEMPTED_INSTALLMENT_COUNT
                Rg11InvalidPredicate.INVALID_REMAINING_INSTALLMENT_COUNT ->
                    Rg11RejectionReason.INVALID_INSTALLMENT_COUNT to Rg11FieldPath.ATTEMPTED_REMAINING_INSTALLMENT_COUNT
                Rg11InvalidPredicate.INSTALLMENT_AMOUNT_MISMATCH ->
                    Rg11RejectionReason.INSTALLMENT_AMOUNT_MISMATCH to Rg11FieldPath.ATTEMPTED_AMOUNT
                Rg11InvalidPredicate.REMAINING_AMOUNT_MISMATCH ->
                    Rg11RejectionReason.REMAINING_AMOUNT_MISMATCH to Rg11FieldPath.ATTEMPTED_REMAINING_AMOUNT
                Rg11InvalidPredicate.TRANSACTION_NOT_CORRECTABLE ->
                    Rg11RejectionReason.TRANSACTION_NOT_CORRECTABLE to Rg11FieldPath.ATTEMPTED_TRANSACTION_ID
            }
        return rejected(reason, fieldPath)
    }

    // ------------------------------------------------------------------ formal builders

    private fun buildPurchaseTransaction(
        ledgerId: LedgerId,
        input: Rg11CreateInput,
        ids: Rg11CreateIds,
    ): DomainResult<FormalTransaction> {
        val postingSet =
            when (
                val result =
                    PostingSet.create(
                        ids.postingSetId,
                        listOf(
                            Posting(
                                ids.paymentPostingId,
                                input.paymentAccountId,
                                Money.ofMinor(
                                    checkedNegate(input.amount.minorUnits)
                                        ?: return DomainResult.Failure(DomainResultFailureViolation),
                                    input.currency,
                                ),
                            ),
                            Posting(ids.prepaidPostingId, input.prepaidAccountId, Money.ofMinor(input.amount.minorUnits, input.currency)),
                        ),
                    )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return DomainResult.Failure(DomainResultFailureViolation)
            }
        return FormalTransaction.create(
            Transaction(ids.transactionId, ledgerId, TransactionKind.PREPAID_PURCHASE, ids.versionId),
            versions =
                listOf(
                    TransactionVersion(
                        ids.versionId,
                        ids.transactionId,
                        versionNumber = 1,
                        postingSetId = ids.postingSetId,
                        times = TransactionTimes(input.occurredAt, input.occurredAt, input.occurredAt),
                    ),
                ),
            postingSets = listOf(postingSet),
        )
    }

    private fun buildRecognitionTransaction(
        ledgerId: LedgerId,
        schedule: PeriodicAllocationSchedule,
        installment: PeriodicAllocationInstallment,
        expenseAccountId: AccountId,
        ids: Rg11RecognizeIds,
    ): DomainResult<FormalTransaction> {
        val postingSet =
            when (
                val result =
                    PostingSet.create(
                        ids.postingSetId,
                        listOf(
                            Posting(ids.expensePostingId, expenseAccountId, Money.ofMinor(installment.amountMinor, schedule.currency)),
                            Posting(
                                ids.prepaidPostingId,
                                schedule.prepaidAccountId,
                                Money.ofMinor(
                                    checkedNegate(installment.amountMinor)
                                        ?: return DomainResult.Failure(DomainResultFailureViolation),
                                    schedule.currency,
                                ),
                            ),
                        ),
                    )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return DomainResult.Failure(DomainResultFailureViolation)
            }
        return FormalTransaction.create(
            Transaction(ids.transactionId, ledgerId, TransactionKind.PREPAID_RECOGNITION, ids.versionId),
            versions =
                listOf(
                    TransactionVersion(
                        ids.versionId,
                        ids.transactionId,
                        versionNumber = 1,
                        postingSetId = ids.postingSetId,
                        // The frozen recognition version collapses all three times on the
                        // scheduled installment date (validator requires occurred_at and
                        // effective_at to equal scheduled_at; statistics_at is the correction
                        // target of `correct_transaction_version`).
                        times = TransactionTimes(installment.scheduledAt, installment.scheduledAt, installment.scheduledAt),
                    ),
                ),
            postingSets = listOf(postingSet),
        )
    }

    // ------------------------------------------------------------------ derived state

    private fun derivedStatuses(): List<Rg11DerivedStatus> =
        buildList {
            val recognized = recognizedInstallmentIds()
            schedules.forEach { schedule ->
                val latest = latestRevisionOf(schedule.id)
                installments.filter { it.scheduleId == schedule.id }.forEach { installment ->
                    val value =
                        if (latest == null) {
                            if (installment.id in recognized) "recognized" else "superseded"
                        } else {
                            when (deriveInstallmentAllocationStatus(installment, latest, recognized)) {
                                PeriodicAllocationInstallmentStatus.RECOGNIZED -> "recognized"
                                PeriodicAllocationInstallmentStatus.PENDING -> "pending"
                                PeriodicAllocationInstallmentStatus.SUPERSEDED -> "superseded"
                            }
                        }
                    add(
                        Rg11DerivedStatus(
                            id = "status-" + installment.id,
                            targetKind = "domain_entity",
                            targetId = installment.id,
                            statusName = "allocation_status",
                            value = value,
                        ),
                    )
                }
                val scheduleValue =
                    if (latest == null) {
                        "active"
                    } else {
                        when (
                            deriveScheduleAllocationStatus(
                                currentInstallments = installments.filter { it.revisionId == latest.id },
                                recognizedInstallmentIds = recognized,
                            )
                        ) {
                            PeriodicAllocationScheduleStatus.RECOGNIZED -> "recognized"
                            PeriodicAllocationScheduleStatus.ACTIVE -> "active"
                        }
                    }
                add(
                    Rg11DerivedStatus(
                        id = "status-" + schedule.id,
                        targetKind = "domain_entity",
                        targetId = schedule.id,
                        statusName = "allocation_status",
                        value = scheduleValue,
                    ),
                )
            }
            formalTransactions.forEach { record ->
                val eligible =
                    record.formalTransaction
                        .currentPostings()
                        .filter { postingSemantics[it.id.value]?.reconciliationEligible == true }
                if (eligible.isNotEmpty()) {
                    val statuses = eligible.map { reconciliation()[it.id.value] ?: "pending" }
                    val summary =
                        when {
                            statuses.any { it == "has_difference" } -> "has_difference"
                            statuses.all { it == "matched" } -> "matched"
                            statuses.all { it == "pending" } -> "pending"
                            else -> "partial"
                        }
                    add(
                        Rg11DerivedStatus(
                            id = "status-reconciliation-" + record.formalTransaction.transaction.id.value,
                            targetKind = "transaction",
                            targetId = record.formalTransaction.transaction.id.value,
                            statusName = "reconciliation_summary",
                            value = summary,
                        ),
                    )
                }
            }
        }

    private fun reports(): Map<String, Rg11Report> {
        val periods = linkedMapOf<String, Rg11Report>()
        formalTransactions.forEach { record ->
            val report =
                when (record.formalTransaction.transaction.kind) {
                    TransactionKind.PREPAID_PURCHASE -> {
                        val payment =
                            record.formalTransaction
                                .currentPostings()
                                .first { postingSemantics[it.id.value]?.role == "payment_asset" }
                        Rg11Report(
                            cashOutflowMinor =
                                checkedNegate(payment.amount.minorUnits)
                                    ?: return@forEach,
                        )
                    }
                    TransactionKind.PREPAID_RECOGNITION -> {
                        val expense =
                            record.formalTransaction
                                .currentPostings()
                                .first { postingSemantics[it.id.value]?.role == "expense" }
                        Rg11Report(
                            budgetMinor = expense.amount.minorUnits,
                            categoryEffectMinor = expense.amount.minorUnits,
                            consumptionMinor = expense.amount.minorUnits,
                            netWorthChangeMinor =
                                checkedNegate(expense.amount.minorUnits)
                                    ?: return@forEach,
                        )
                    }
                    else -> Rg11Report()
                }
            val period = statisticsAtText(record).substring(0, 10)
            val current = periods[period] ?: Rg11Report()
            periods[period] = mergeReports(current, report)
        }
        val cumulative = periods.values.fold(Rg11Report()) { acc, report -> mergeReports(acc, report) }
        return buildMap {
            periods.toSortedMap().forEach { (period, report) -> put(period, report) }
            put("cumulative", cumulative)
        }
    }

    private fun mergeReports(
        left: Rg11Report,
        right: Rg11Report,
    ): Rg11Report =
        Rg11Report(
            budgetMinor = checkedAdd(left.budgetMinor, right.budgetMinor)!!,
            cashOutflowMinor = checkedAdd(left.cashOutflowMinor, right.cashOutflowMinor)!!,
            categoryEffectMinor = checkedAdd(left.categoryEffectMinor, right.categoryEffectMinor)!!,
            consumptionMinor = checkedAdd(left.consumptionMinor, right.consumptionMinor)!!,
            incomeMinor = checkedAdd(left.incomeMinor, right.incomeMinor)!!,
            netWorthChangeMinor = checkedAdd(left.netWorthChangeMinor, right.netWorthChangeMinor)!!,
        )

    private fun reconciliation(): Map<String, String> =
        buildMap {
            formalTransactions.forEach { record ->
                record.formalTransaction.currentPostings().forEach { posting ->
                    if (postingSemantics[posting.id.value]?.reconciliationEligible == true) {
                        // RG-11 has no matching action: every eligible posting stays pending.
                        put(posting.id.value, "pending")
                    }
                }
            }
        }

    // ------------------------------------------------------------------ helpers

    private fun latestRevisionOf(scheduleId: String): PeriodicAllocationRevision? = revisions.filter { it.scheduleId == scheduleId }.maxByOrNull { it.revisionNumber }

    private fun recognizedInstallmentIds(): Set<String> =
        auditLinks
            .filter { it.linkType == PERIODIC_ALLOCATION_RECOGNITION_LINK_TYPE }
            .mapTo(mutableSetOf()) { it.fromId }

    private fun recognizedAmountMinor(scheduleId: String): Long {
        var sum = 0L
        auditLinks
            .filter { it.linkType == PERIODIC_ALLOCATION_RECOGNITION_LINK_TYPE }
            .forEach { link ->
                installments
                    .firstOrNull { it.id == link.fromId && it.scheduleId == scheduleId }
                    ?.let { installment ->
                        sum = checkedAdd(sum, installment.amountMinor)
                            ?: error("RG-11 recognized amount overflow")
                    }
            }
        return sum
    }

    private fun statisticsAtText(record: Rg11FormalTransactionRecord): String =
        record.statisticsAtText
            ?: record.formalTransaction.versions
                .first { it.id == record.formalTransaction.transaction.currentVersionId }
                .times.statisticsAt
                .toString()

    private fun catalogAccount(id: AccountId): Account? = catalog.accounts.firstOrNull { it.id == id }

    private fun canAppendFormalTransaction(record: Rg11FormalTransactionRecord): Boolean {
        if (!catalogCompatible(record.formalTransaction) || formalIdCollision(record.formalTransaction)) {
            return false
        }
        val currentBalances = replayBalances()
        record.formalTransaction
            .currentPostings()
            .groupBy { it.accountId }
            .forEach { (accountId, postings) ->
                var total = currentBalances[accountId]?.minorUnits ?: return false
                postings.forEach { posting ->
                    total = checkedAdd(total, posting.amount.minorUnits) ?: return false
                }
            }
        return true
    }

    private fun catalogCompatible(formal: FormalTransaction): Boolean =
        formal.transaction.ledgerId == catalog.accounts.firstOrNull()?.ledgerId &&
            formal.currentPostings().all { posting ->
                val account = catalogAccount(posting.accountId)
                account != null &&
                    account.ledgerId == formal.transaction.ledgerId &&
                    account.currency == posting.amount.currency
            }

    private fun formalIdCollision(formal: FormalTransaction): Boolean {
        val transactionIds = formalTransactions.mapTo(mutableSetOf()) { it.formalTransaction.transaction.id }
        val versionIds =
            formalTransactions.flatMapTo(mutableSetOf()) { record ->
                record.formalTransaction.versions.map { it.id }
            }
        val postingSetIds =
            formalTransactions.flatMapTo(mutableSetOf()) { record ->
                record.formalTransaction.postingSets.map { it.id }
            }
        val postingIds =
            formalTransactions.flatMapTo(mutableSetOf()) { record ->
                record.formalTransaction.postingSets.flatMap { postingSet -> postingSet.postings.map { it.id } }
            }
        return formal.transaction.id in transactionIds ||
            formal.versions.any { it.id in versionIds } ||
            formal.postingSets.any { it.id in postingSetIds } ||
            formal.currentPostings().any { it.id in postingIds }
    }

    /**
     * Narrow collision check for `correct_transaction_version` only. [appended] is the
     * corrected record with a new version appended; its transaction id, previous versions,
     * posting sets and postings are the corrected record's own legal references and must not
     * be judged as collisions (the shared [formalIdCollision] would always reject them). Only
     * ids the append newly introduces are checked against the whole collection, so the frozen
     * `main-correct` (version v2 append + confirmation) can be accepted.
     */
    private fun appendedIdCollision(
        appended: FormalTransaction,
        baseline: FormalTransaction,
    ): Boolean {
        val baselineVersionIds = baseline.versions.mapTo(mutableSetOf()) { it.id }
        val baselinePostingSetIds = baseline.postingSets.mapTo(mutableSetOf()) { it.id }
        val baselinePostingIds =
            baseline.postingSets.flatMapTo(mutableSetOf()) { postingSet ->
                postingSet.postings.map { it.id }
            }
        val newTransactionIds =
            if (appended.transaction.id == baseline.transaction.id) {
                emptySet()
            } else {
                setOf(appended.transaction.id)
            }
        val newVersionIds =
            appended.versions
                .mapTo(mutableSetOf()) { it.id }
                .apply { removeAll(baselineVersionIds) }
        val newPostingSetIds =
            appended.postingSets
                .mapTo(mutableSetOf()) { it.id }
                .apply { removeAll(baselinePostingSetIds) }
        val newPostingIds =
            appended.postingSets
                .flatMapTo(mutableSetOf()) { postingSet ->
                    postingSet.postings.map { it.id }
                }.apply { removeAll(baselinePostingIds) }
        val existingTransactionIds = formalTransactions.mapTo(mutableSetOf()) { it.formalTransaction.transaction.id }
        val existingVersionIds =
            formalTransactions.flatMapTo(mutableSetOf()) { record ->
                record.formalTransaction.versions.map { it.id }
            }
        val existingPostingSetIds =
            formalTransactions.flatMapTo(mutableSetOf()) { record ->
                record.formalTransaction.postingSets.map { it.id }
            }
        val existingPostingIds =
            formalTransactions.flatMapTo(mutableSetOf()) { record ->
                record.formalTransaction.postingSets.flatMap { postingSet -> postingSet.postings.map { it.id } }
            }
        return newTransactionIds.any { it in existingTransactionIds } ||
            newVersionIds.any { it in existingVersionIds } ||
            newPostingSetIds.any { it in existingPostingSetIds } ||
            newPostingIds.any { it in existingPostingIds }
    }

    private fun replayBalances(): Map<AccountId, Money> =
        buildMap {
            catalog.accounts.forEach { account ->
                var total = 0L
                formalTransactions
                    .filter { it.formalTransaction.transaction.ledgerId == account.ledgerId }
                    .forEach { record ->
                        record.formalTransaction
                            .currentPostings()
                            .filter { it.accountId == account.id }
                            .forEach { posting ->
                                check(posting.amount.currency == account.currency) { "RG-11 posting currency mismatch" }
                                total = checkedAdd(total, posting.amount.minorUnits) ?: error("RG-11 balance overflow")
                            }
                    }
                put(account.id, Money.ofMinor(total, account.currency))
            }
        }

    private fun domainRejected(
        violation: DomainViolation,
        fallbackFieldPath: Rg11FieldPath = Rg11FieldPath.ATTEMPTED_REQUEST_ID,
    ): Rg11ExecutionResult =
        when (violation) {
            is PeriodicAllocationViolation.ExactDecimalStringRequired ->
                rejected(Rg11RejectionReason.EXACT_DECIMAL_STRING_REQUIRED, Rg11FieldPath.ATTEMPTED_AMOUNT)
            is PeriodicAllocationViolation.MustBePositive ->
                rejected(Rg11RejectionReason.MUST_BE_POSITIVE, Rg11FieldPath.ATTEMPTED_AMOUNT)
            is PeriodicAllocationViolation.UnsupportedCurrency ->
                rejected(Rg11RejectionReason.UNSUPPORTED_CURRENCY, Rg11FieldPath.ATTEMPTED_CURRENCY)
            is PeriodicAllocationViolation.CurrencyMismatch ->
                rejected(Rg11RejectionReason.CURRENCY_MISMATCH, Rg11FieldPath.ATTEMPTED_CURRENCY)
            is PeriodicAllocationViolation.InvalidAnchor ->
                rejected(Rg11RejectionReason.INVALID_ANCHOR, Rg11FieldPath.ATTEMPTED_ANCHOR)
            is PeriodicAllocationViolation.InstallmentNotPending ->
                rejected(Rg11RejectionReason.INSTALLMENT_NOT_PENDING, Rg11FieldPath.ATTEMPTED_INSTALLMENT_ID)
            is PeriodicAllocationViolation.ExceedsRemainingPrepaid ->
                rejected(Rg11RejectionReason.EXCEEDS_REMAINING_PREPAID, Rg11FieldPath.ATTEMPTED_AMOUNT)
            is PeriodicAllocationViolation.InvalidRevisionBoundary ->
                rejected(Rg11RejectionReason.INVALID_REVISION_BOUNDARY, Rg11FieldPath.ATTEMPTED_RECOGNIZED_THROUGH)
            is PeriodicAllocationViolation.InvalidInstallmentCount ->
                rejected(
                    Rg11RejectionReason.INVALID_INSTALLMENT_COUNT,
                    if (violation.field.jsonName == "installment_count") {
                        Rg11FieldPath.ATTEMPTED_INSTALLMENT_COUNT
                    } else {
                        Rg11FieldPath.ATTEMPTED_REMAINING_INSTALLMENT_COUNT
                    },
                )
            is PeriodicAllocationViolation.RemainingAmountMismatch ->
                rejected(Rg11RejectionReason.REMAINING_AMOUNT_MISMATCH, Rg11FieldPath.ATTEMPTED_REMAINING_AMOUNT)
            is PeriodicAllocationViolation.AmountMustMatchInstallment ->
                rejected(Rg11RejectionReason.INSTALLMENT_AMOUNT_MISMATCH, Rg11FieldPath.ATTEMPTED_AMOUNT)
            is PeriodicAllocationViolation.IdentityRequired,
            is PeriodicAllocationViolation.UnknownSchedule,
            is PeriodicAllocationViolation.UnknownInstallment,
            is PeriodicAllocationViolation.RevisionMustBeAppendOnly,
            ->
                rejected(Rg11RejectionReason.INVALID_RG11_INPUT, fallbackFieldPath)
            else -> rejected(Rg11RejectionReason.DOMAIN_REJECTED, fallbackFieldPath)
        }

    // ------------------------------------------------------------------ fingerprints

    private fun canonicalInput(operation: Rg11Operation): String =
        when (operation) {
            is Rg11Operation.CreatePeriodicAllocation ->
                canonicalFields(
                    operation.ledgerId.value,
                    operation.action.code,
                    operation.input.requestId.value,
                    operation.input.paymentAccountId.value,
                    operation.input.prepaidAccountId.value,
                    operation.input.categoryId.value,
                    canonicalMoney(operation.input.amount),
                    canonicalCurrency(operation.input.currency),
                    operation.input.startAt.toString(),
                    operation.input.startAtText,
                    canonicalAnchor(operation.input.anchor),
                    operation.input.cadence.name,
                    operation.input.explicitConfirmation.toString(),
                    operation.input.occurredAt.toString(),
                    operation.input.occurredAtText,
                    operation.input.installmentCount.toString(),
                    canonicalCreateIds(operation.ids),
                )
            is Rg11Operation.RecognizePeriodicAllocationInstallment ->
                canonicalFields(
                    operation.ledgerId.value,
                    operation.action.code,
                    operation.input.requestId.value,
                    operation.input.scheduleId,
                    operation.input.installmentId,
                    canonicalMoney(operation.input.amount),
                    canonicalCurrency(operation.input.currency),
                    operation.input.explicitConfirmation.toString(),
                    canonicalRecognizeIds(operation.ids),
                )
            is Rg11Operation.RevisePeriodicAllocation ->
                canonicalFields(
                    operation.ledgerId.value,
                    operation.action.code,
                    operation.input.requestId.value,
                    operation.input.scheduleId,
                    operation.input.recognizedThrough,
                    canonicalMoney(operation.input.remainingAmount),
                    canonicalCurrency(operation.input.currency),
                    operation.input.explicitConfirmation.toString(),
                    operation.input.remainingInstallmentCount.toString(),
                    canonicalReviseIds(operation.ids),
                )
            is Rg11Operation.CorrectTransactionVersion ->
                canonicalFields(
                    operation.ledgerId.value,
                    operation.action.code,
                    operation.input.requestId.value,
                    operation.input.transactionId.value,
                    operation.input.correctionKind,
                    operation.input.statisticsAt.toString(),
                    operation.input.statisticsAtText,
                    operation.input.explicitConfirmation.toString(),
                    canonicalCorrectIds(operation.ids),
                )
            is Rg11Operation.RetryIdempotentInput ->
                canonicalFields(
                    operation.ledgerId.value,
                    operation.action.code,
                    operation.input.inputId,
                )
            is Rg11Operation.InvalidInput ->
                canonicalFields(
                    operation.ledgerId.value,
                    operation.action.code,
                    operation.input.requestId.value,
                    operation.input.predicate.name,
                    operation.input.attemptedInput.entries.sortedBy { it.key }.joinToString("|") { (key, value) ->
                        "$key=${value ?: "<null>"}"
                    },
                )
        }

    private fun canonicalCreateIds(ids: Rg11CreateIds): String =
        canonicalFields(
            ids.transactionId.value,
            ids.versionId.value,
            ids.postingSetId.value,
            ids.paymentPostingId.value,
            ids.prepaidPostingId.value,
            ids.scheduleId,
            ids.revisionId,
            ids.installmentIds.joinToString("|"),
        )

    private fun canonicalRecognizeIds(ids: Rg11RecognizeIds): String =
        canonicalFields(
            ids.transactionId.value,
            ids.versionId.value,
            ids.postingSetId.value,
            ids.expensePostingId.value,
            ids.prepaidPostingId.value,
            ids.auditLinkId,
        )

    private fun canonicalReviseIds(ids: Rg11ReviseIds): String =
        canonicalFields(
            ids.revisionId,
            ids.installmentIds.joinToString("|"),
        )

    private fun canonicalCorrectIds(ids: Rg11CorrectIds): String =
        canonicalFields(
            ids.versionId.value,
            ids.confirmationId,
            ids.operationId,
            ids.confirmationCreatedAt.toString(),
            ids.confirmationCreatedAtText,
        )

    private fun canonicalAnchor(anchor: PeriodicAllocationAnchor): String =
        when (anchor) {
            PeriodicAllocationAnchor.MonthEnd -> "month_end"
            is PeriodicAllocationAnchor.DayOfMonth -> "day_of_month:${anchor.day}"
        }

    private fun canonicalMoney(money: Money): String = "${money.minorUnits}:${canonicalCurrency(money.currency)}"

    private fun canonicalCurrency(currency: CurrencyUnit): String = "${currency.code}:${currency.precision}"

    private fun canonicalFields(vararg values: String?): String =
        buildString {
            values.forEach { value ->
                if (value == null) {
                    append("N;")
                } else {
                    append("V")
                        .append(value.length)
                        .append(':')
                        .append(value)
                        .append(';')
                }
            }
        }

    private fun accepted(ids: List<Rg11ReturnedId>) = Rg11ExecutionResult.Accepted(ids)

    private fun rejected(
        reason: Rg11RejectionReason,
        fieldPath: Rg11FieldPath,
    ) = Rg11ExecutionResult.Rejected(reason, fieldPath)

    private companion object {
        const val PERIODIC_ALLOCATION_RECOGNITION_LINK_TYPE = "periodic_allocation_recognition"
        const val CORRECTION_KIND_STATISTICS_TIME = "statistics_time"

        /** Frozen case currencies of rg-11.json (CNY is the supported product currency). */
        val DEFAULT_KNOWN_CURRENCIES =
            setOf(
                CurrencyUnit(code = "CNY", precision = 2),
                CurrencyUnit(code = "USD", precision = 2),
            )

        /** Case timezone `Asia/Shanghai` of the frozen contract. */
        const val DEFAULT_UTC_OFFSET_SECONDS = 28_800
    }
}

private val DomainResultFailureViolation = com.unifiedledger.domain.DomainViolation.InvalidFormalTransaction

private fun checkedAdd(
    left: Long,
    right: Long,
): Long? {
    if (right > 0L && left > Long.MAX_VALUE - right) return null
    if (right < 0L && left < Long.MIN_VALUE - right) return null
    return left + right
}

private fun checkedSubtract(
    left: Long,
    right: Long,
): Long? = checkedAdd(left, checkedNegate(right) ?: return null)

private fun checkedNegate(value: Long): Long? = if (value == Long.MIN_VALUE) null else -value

private const val SECONDS_PER_DAY = 86_400L

/**
 * Renders [instant] in the fixed local calendar of [utcOffsetSeconds] as the frozen
 * `YYYY-MM-DDTHH:MM:SS+HH:MM` text (case timezone `Asia/Shanghai` = `+08:00`), byte-identical
 * to the `statistics_at` / `occurred_at` / `scheduled_at` texts of the frozen rg-11.json.
 * `kotlin.time.Instant.toString()` renders UTC, which would shift report day periods by one
 * day for evening instants of the case timezone.
 */
private fun localDateTimeText(
    instant: Instant,
    utcOffsetSeconds: Int,
): String {
    val localSeconds = instant.epochSeconds + utcOffsetSeconds
    val days = localSeconds.floorDiv(SECONDS_PER_DAY)
    val secondsOfDay = localSeconds - days * SECONDS_PER_DAY
    val (year, month, day) = civilFromDays(days)
    val hour = secondsOfDay / 3_600L
    val minute = (secondsOfDay % 3_600L) / 60L
    val second = secondsOfDay % 60L
    val offsetHours = utcOffsetSeconds / 3_600
    val offsetMinutes = kotlin.math.abs(utcOffsetSeconds % 3_600) / 60
    val sign = if (utcOffsetSeconds < 0) "-" else "+"
    return buildString {
        append(padded(year.toLong(), 4)).append('-')
        append(padded(month.toLong(), 2)).append('-')
        append(padded(day.toLong(), 2)).append('T')
        append(padded(hour, 2)).append(':')
        append(padded(minute, 2)).append(':')
        append(padded(second, 2)).append(sign)
        append(padded(kotlin.math.abs(offsetHours).toLong(), 2)).append(':')
        append(padded(offsetMinutes.toLong(), 2))
    }
}

private fun padded(
    value: Long,
    width: Int,
): String = value.toString().padStart(width, '0')

/** Fixed-offset civil-from-days (Howard Hinnant's public-domain algorithm), same arithmetic as
 * the domain's `PeriodicAllocation.kt` local calendar so both layers agree on case dates. */
private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
    val z = days + 719_468L
    val era = (if (z >= 0) z else z - 146_096L) / 146_097L
    val dayOfEra = z - era * 146_097L
    val yearOfEra = (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
    val year = yearOfEra + era * 400L
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val monthPrime = (5L * dayOfYear + 2L) / 153L
    val day = (dayOfYear - (153L * monthPrime + 2L) / 5L + 1L).toInt()
    val month = (if (monthPrime < 10L) monthPrime + 3L else monthPrime - 9L).toInt()
    val outYear = (if (month <= 2) year + 1L else year).toInt()
    return Triple(outYear, month, day)
}
