package com.unifiedledger.domain

import kotlin.time.Instant

data class StagedPaymentRelationId(val value: String)

data class StagedPaymentLifecycleId(val value: String)

data class InstallmentPaymentId(val value: String)

data class StagedPaymentHistoryId(val value: String)

sealed interface StagedPaymentResult<out T> {
    data class Success<out T>(val value: T) : StagedPaymentResult<T>

    data class Failure(val violation: StagedPaymentViolation) : StagedPaymentResult<Nothing>
}

@ConsistentCopyVisibility
data class StagedPaymentSourceTime private constructor(
    val instant: Instant,
    val text: String,
) {
    companion object {
        fun create(
            instant: Instant,
            text: String,
            expectedOffsetText: String,
        ): StagedPaymentResult<StagedPaymentSourceTime> {
            if (!SOURCE_OFFSET_PATTERN.matches(expectedOffsetText)) {
                return StagedPaymentResult.Failure(StagedPaymentViolation.InvalidSourcePaymentOffset)
            }
            if (!STRICT_RFC3339_PATTERN.matches(text)) {
                return StagedPaymentResult.Failure(StagedPaymentViolation.InvalidSourcePaymentTimestamp)
            }
            val parsed = try {
                Instant.parse(text)
            } catch (_: IllegalArgumentException) {
                return StagedPaymentResult.Failure(StagedPaymentViolation.InvalidSourcePaymentTimestamp)
            }
            if (parsed != instant) {
                return StagedPaymentResult.Failure(StagedPaymentViolation.SourcePaymentTimeTextMismatch)
            }

            val sourceOffsetText = if (text.endsWith("Z")) "Z" else text.takeLast(6)
            if (normalizeZeroOffset(sourceOffsetText) != normalizeZeroOffset(expectedOffsetText)) {
                return StagedPaymentResult.Failure(StagedPaymentViolation.SourcePaymentOffsetMismatch)
            }
            return StagedPaymentResult.Success(StagedPaymentSourceTime(instant, text))
        }
    }
}

enum class StagedPaymentRole {
    DEPOSIT,
    FINAL,
}

enum class StagedPaymentProgress {
    UNPAID,
    PARTIALLY_PAID,
    PAID_IN_FULL,
}

enum class StagedPaymentFulfillment {
    IN_PROGRESS,
    FULFILLED,
}

enum class StagedPaymentReconciliation {
    PENDING,
    PARTIAL,
    COMPLETE,
}

enum class StagedPaymentReconciliationStatus {
    PENDING,
    MATCHED,
    HAS_DIFFERENCE,
}

data class StagedPaymentReconciliationFact(
    val postingId: PostingId,
    val eligible: Boolean,
    val status: StagedPaymentReconciliationStatus,
)

enum class StagedPaymentEvent {
    GROUP_CREATED,
    PAYMENT_CONFIRMED,
    FULFILLMENT_CHANGED,
    COMPLETION_CONFIRMED,
}

sealed interface StagedPaymentMemberRef {
    data class Lifecycle(val id: StagedPaymentLifecycleId) : StagedPaymentMemberRef

    data class Installment(val id: InstallmentPaymentId) : StagedPaymentMemberRef
}

class StagedPaymentRelation private constructor(
    val id: StagedPaymentRelationId,
    val memberRefs: Set<StagedPaymentMemberRef>,
) {
    val type: String = "staged_payment"
    val payload: Map<String, Nothing> = emptyMap()

    internal fun append(paymentId: InstallmentPaymentId): StagedPaymentRelation =
        StagedPaymentRelation(
            id = id,
            memberRefs = memberRefs + StagedPaymentMemberRef.Installment(paymentId),
        )

    companion object {
        internal fun create(
            id: StagedPaymentRelationId,
            lifecycleId: StagedPaymentLifecycleId,
        ): StagedPaymentRelation =
            StagedPaymentRelation(
                id = id,
                memberRefs = setOf(StagedPaymentMemberRef.Lifecycle(lifecycleId)),
            )
    }
}

data class StagedPaymentHistoryEntry(
    val id: StagedPaymentHistoryId,
    val sequence: Int,
    val event: StagedPaymentEvent,
    val occurredAt: Instant,
    val totalAmount: Money,
    val paidAmount: Money,
    val dueAmount: Money,
    val paymentId: InstallmentPaymentId?,
    val paymentProgress: StagedPaymentProgress,
    val fulfillmentStatus: StagedPaymentFulfillment,
    val stateTransitionEffectCount: Int = 0,
)

class StagedPaymentLifecycle private constructor(
    val id: StagedPaymentLifecycleId,
    val totalAmount: Money,
    val paidAmount: Money,
    val dueAmount: Money,
    val currency: CurrencyUnit,
    val categoryId: CategoryId,
    val stateHistory: List<StagedPaymentHistoryEntry>,
) {
    val displayName: String = "分阶段付款"
    val systemManaged: Boolean = true
    val genericOrderLifecycle: Boolean = false

    internal fun append(
        historyId: StagedPaymentHistoryId,
        event: StagedPaymentEvent,
        occurredAt: Instant,
        paidAmount: Money,
        dueAmount: Money,
        paymentId: InstallmentPaymentId?,
        paymentProgress: StagedPaymentProgress,
        fulfillmentStatus: StagedPaymentFulfillment,
    ): StagedPaymentLifecycle =
        StagedPaymentLifecycle(
            id = id,
            totalAmount = totalAmount,
            paidAmount = paidAmount,
            dueAmount = dueAmount,
            currency = currency,
            categoryId = categoryId,
            stateHistory = stateHistory + StagedPaymentHistoryEntry(
                id = historyId,
                sequence = stateHistory.size + 1,
                event = event,
                occurredAt = occurredAt,
                totalAmount = totalAmount,
                paidAmount = paidAmount,
                dueAmount = dueAmount,
                paymentId = paymentId,
                paymentProgress = paymentProgress,
                fulfillmentStatus = fulfillmentStatus,
            ),
        )

    companion object {
        internal fun create(
            id: StagedPaymentLifecycleId,
            totalAmount: Money,
            categoryId: CategoryId,
            historyId: StagedPaymentHistoryId,
            createdAt: Instant,
        ): StagedPaymentLifecycle {
            val zero = Money.ofMinor(0L, totalAmount.currency)
            return StagedPaymentLifecycle(
                id = id,
                totalAmount = totalAmount,
                paidAmount = zero,
                dueAmount = totalAmount,
                currency = totalAmount.currency,
                categoryId = categoryId,
                stateHistory = listOf(
                    StagedPaymentHistoryEntry(
                        id = historyId,
                        sequence = 1,
                        event = StagedPaymentEvent.GROUP_CREATED,
                        occurredAt = createdAt,
                        totalAmount = totalAmount,
                        paidAmount = zero,
                        dueAmount = totalAmount,
                        paymentId = null,
                        paymentProgress = StagedPaymentProgress.UNPAID,
                        fulfillmentStatus = StagedPaymentFulfillment.IN_PROGRESS,
                    ),
                ),
            )
        }
    }
}

data class InstallmentPayment(
    val id: InstallmentPaymentId,
    val role: StagedPaymentRole,
    val amount: Money,
    val currency: CurrencyUnit,
    val fundingAccountId: AccountId,
    val transactionId: TransactionId,
    val expensePostingId: PostingId,
    val assetPostingId: PostingId,
    val actualPaymentAt: Instant,
    val statisticsAt: Instant,
    val sourceTime: StagedPaymentSourceTime?,
) {
    val sourcePaymentAt: Instant?
        get() = sourceTime?.instant

    val sourcePaymentAtText: String?
        get() = sourceTime?.text
}

data class CreateStagedPaymentCommand(
    val ledgerId: LedgerId,
    val totalAmount: Money,
    val categoryId: CategoryId,
    val createdAt: Instant,
)

data class StagedPaymentCreationIds(
    val relationId: StagedPaymentRelationId,
    val lifecycleId: StagedPaymentLifecycleId,
    val historyId: StagedPaymentHistoryId,
)

data class RecordStagedPaymentInstallmentCommand(
    val role: StagedPaymentRole,
    val amount: Money,
    val fundingAccountId: AccountId,
    val actualPaymentAt: Instant,
    val sourceTime: StagedPaymentSourceTime? = null,
)

data class StagedPaymentInstallmentIds(
    val paymentId: InstallmentPaymentId,
    val historyId: StagedPaymentHistoryId,
    val expenseIds: AssetPaidOrdinaryExpenseIds,
)

sealed interface StagedPaymentViolation {
    data object TotalAmountMustBePositive : StagedPaymentViolation
    data object PaymentAmountMustBePositive : StagedPaymentViolation
    data object SecondaryCategoryRequired : StagedPaymentViolation
    data object CategoryInactive : StagedPaymentViolation
    data object ExpenseCategoryRequired : StagedPaymentViolation
    data object SingleCurrencyRequired : StagedPaymentViolation
    data object UnknownRealAccount : StagedPaymentViolation
    data object RealFinancialAccountRequired : StagedPaymentViolation
    data object OwnedAccountRequired : StagedPaymentViolation
    data object AssetAccountRequired : StagedPaymentViolation
    data class DuplicateRole(val role: StagedPaymentRole) : StagedPaymentViolation
    data object DuplicateIdentity : StagedPaymentViolation
    data object DepositRequired : StagedPaymentViolation
    data object DepositMustBeLessThanTotal : StagedPaymentViolation
    data object PaymentExceedsDue : StagedPaymentViolation
    data object FinalMustEqualRemainingDue : StagedPaymentViolation
    data object FinalPaymentMustBeLaterThanDeposit : StagedPaymentViolation
    data object FinalSourcePaymentMustBeLaterThanDeposit : StagedPaymentViolation
    data object InvalidSourcePaymentOffset : StagedPaymentViolation
    data object InvalidSourcePaymentTimestamp : StagedPaymentViolation
    data object SourcePaymentTimeTextMismatch : StagedPaymentViolation
    data object SourcePaymentOffsetMismatch : StagedPaymentViolation
    data object HistoryTimeMustIncrease : StagedPaymentViolation
    data object InvalidFulfillmentTransition : StagedPaymentViolation
    data object FulfillmentAlreadySet : StagedPaymentViolation
    data object CompletionRequiresPaidInFull : StagedPaymentViolation
    data object CompletionAlreadyConfirmed : StagedPaymentViolation
    data object ConflictingReconciliationFacts : StagedPaymentViolation
    data class DependencyViolation(val cause: DomainViolation) : StagedPaymentViolation
}

class StagedPayment private constructor(
    val ledgerId: LedgerId,
    val relation: StagedPaymentRelation,
    val lifecycle: StagedPaymentLifecycle,
    installments: List<InstallmentPayment>,
    formalTransactions: List<FormalTransaction>,
) {
    val installments: List<InstallmentPayment> = installments.toList()
    val formalTransactions: List<FormalTransaction> = formalTransactions.toList()

    val paymentProgress: StagedPaymentProgress
        get() = progressFor(lifecycle.paidAmount.minorUnits, lifecycle.dueAmount.minorUnits)

    val fulfillmentStatus: StagedPaymentFulfillment
        get() = lifecycle.stateHistory.last().fulfillmentStatus

    fun recordInstallment(
        catalog: LedgerCatalog,
        command: RecordStagedPaymentInstallmentCommand,
        ids: StagedPaymentInstallmentIds,
    ): StagedPaymentResult<StagedPayment> {
        if (command.amount.minorUnits <= 0L) {
            return StagedPaymentResult.Failure(StagedPaymentViolation.PaymentAmountMustBePositive)
        }
        if (installments.any { it.role == command.role }) {
            return StagedPaymentResult.Failure(StagedPaymentViolation.DuplicateRole(command.role))
        }

        val deposit = installments.singleOrNull { it.role == StagedPaymentRole.DEPOSIT }
        if (command.role == StagedPaymentRole.FINAL && deposit == null) {
            return StagedPaymentResult.Failure(StagedPaymentViolation.DepositRequired)
        }
        if (hasDuplicateIdentity(ids)) {
            return StagedPaymentResult.Failure(StagedPaymentViolation.DuplicateIdentity)
        }
        if (command.amount.currency != lifecycle.currency) {
            return StagedPaymentResult.Failure(StagedPaymentViolation.SingleCurrencyRequired)
        }
        when (command.role) {
            StagedPaymentRole.DEPOSIT -> {
                if (command.amount.minorUnits >= lifecycle.totalAmount.minorUnits) {
                    return StagedPaymentResult.Failure(StagedPaymentViolation.DepositMustBeLessThanTotal)
                }
            }

            StagedPaymentRole.FINAL -> {
                if (command.amount.minorUnits > lifecycle.dueAmount.minorUnits) {
                    return StagedPaymentResult.Failure(StagedPaymentViolation.PaymentExceedsDue)
                }
                if (command.amount.minorUnits != lifecycle.dueAmount.minorUnits) {
                    return StagedPaymentResult.Failure(StagedPaymentViolation.FinalMustEqualRemainingDue)
                }
            }
        }

        val fundingAccount = catalog.account(command.fundingAccountId)
            ?: return StagedPaymentResult.Failure(StagedPaymentViolation.UnknownRealAccount)
        if (!fundingAccount.realAccount) {
            return StagedPaymentResult.Failure(StagedPaymentViolation.RealFinancialAccountRequired)
        }
        if (!fundingAccount.ownedByUser || fundingAccount.ledgerId != ledgerId) {
            return StagedPaymentResult.Failure(StagedPaymentViolation.OwnedAccountRequired)
        }
        if (fundingAccount.kind != AccountKind.ASSET) {
            return StagedPaymentResult.Failure(StagedPaymentViolation.AssetAccountRequired)
        }
        if (fundingAccount.currency != lifecycle.currency) {
            return StagedPaymentResult.Failure(StagedPaymentViolation.SingleCurrencyRequired)
        }

        if (command.actualPaymentAt <= lifecycle.stateHistory.last().occurredAt) {
            return StagedPaymentResult.Failure(
                if (command.role == StagedPaymentRole.FINAL) {
                    StagedPaymentViolation.FinalPaymentMustBeLaterThanDeposit
                } else {
                    StagedPaymentViolation.HistoryTimeMustIncrease
                },
            )
        }
        if (command.role == StagedPaymentRole.FINAL && command.actualPaymentAt <= checkNotNull(deposit).actualPaymentAt) {
            return StagedPaymentResult.Failure(StagedPaymentViolation.FinalPaymentMustBeLaterThanDeposit)
        }
        val depositSourceTime = deposit?.sourceTime
        val finalSourceTime = command.sourceTime
        if (
            command.role == StagedPaymentRole.FINAL &&
            depositSourceTime != null &&
            finalSourceTime != null &&
            finalSourceTime.instant <= depositSourceTime.instant
        ) {
            return StagedPaymentResult.Failure(StagedPaymentViolation.FinalSourcePaymentMustBeLaterThanDeposit)
        }

        val paidMinor = checkedAdd(lifecycle.paidAmount.minorUnits, command.amount.minorUnits)
            ?: return StagedPaymentResult.Failure(
                StagedPaymentViolation.DependencyViolation(DomainViolation.ArithmeticOverflow),
            )
        val negativePayment = checkedNegate(command.amount.minorUnits)
            ?: return StagedPaymentResult.Failure(
                StagedPaymentViolation.DependencyViolation(DomainViolation.ArithmeticOverflow),
            )
        val dueMinor = checkedAdd(lifecycle.dueAmount.minorUnits, negativePayment)
            ?: return StagedPaymentResult.Failure(
                StagedPaymentViolation.DependencyViolation(DomainViolation.ArithmeticOverflow),
            )
        if (dueMinor < 0L) {
            return StagedPaymentResult.Failure(StagedPaymentViolation.PaymentExceedsDue)
        }

        val times = TransactionTimes(
            occurredAt = command.actualPaymentAt,
            statisticsAt = command.actualPaymentAt,
            effectiveAt = command.actualPaymentAt,
        )
        val formalTransaction = when (
            val result = createAssetPaidOrdinaryExpense(
                catalog = catalog,
                command = AssetPaidOrdinaryExpenseCommand(
                    ledgerId = ledgerId,
                    amount = command.amount,
                    categoryId = lifecycle.categoryId,
                    paymentAccountId = command.fundingAccountId,
                    times = times,
                ),
                ids = ids.expenseIds,
            )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> {
                return StagedPaymentResult.Failure(
                    StagedPaymentViolation.DependencyViolation(result.violation),
                )
            }
        }

        val paidAmount = Money.ofMinor(paidMinor, lifecycle.currency)
        val dueAmount = Money.ofMinor(dueMinor, lifecycle.currency)
        val installment = InstallmentPayment(
            id = ids.paymentId,
            role = command.role,
            amount = command.amount,
            currency = lifecycle.currency,
            fundingAccountId = command.fundingAccountId,
            transactionId = ids.expenseIds.transactionId,
            expensePostingId = ids.expenseIds.expensePostingId,
            assetPostingId = ids.expenseIds.paymentPostingId,
            actualPaymentAt = command.actualPaymentAt,
            statisticsAt = command.actualPaymentAt,
            sourceTime = command.sourceTime,
        )
        val progress = progressFor(paidMinor, dueMinor)
        val nextLifecycle = lifecycle.append(
            historyId = ids.historyId,
            event = StagedPaymentEvent.PAYMENT_CONFIRMED,
            occurredAt = command.actualPaymentAt,
            paidAmount = paidAmount,
            dueAmount = dueAmount,
            paymentId = ids.paymentId,
            paymentProgress = progress,
            fulfillmentStatus = fulfillmentStatus,
        )
        return StagedPaymentResult.Success(
            StagedPayment(
                ledgerId = ledgerId,
                relation = relation.append(ids.paymentId),
                lifecycle = nextLifecycle,
                installments = installments + installment,
                formalTransactions = formalTransactions + formalTransaction,
            ),
        )
    }

    fun changeFulfillment(
        historyId: StagedPaymentHistoryId,
        fulfillment: StagedPaymentFulfillment,
        occurredAt: Instant,
    ): StagedPaymentResult<StagedPayment> {
        val identityFailure = validateStateHistoryAppend(historyId, occurredAt)
        if (identityFailure != null) return StagedPaymentResult.Failure(identityFailure)
        if (fulfillment != StagedPaymentFulfillment.FULFILLED) {
            return StagedPaymentResult.Failure(StagedPaymentViolation.InvalidFulfillmentTransition)
        }
        if (fulfillment == fulfillmentStatus) {
            return StagedPaymentResult.Failure(StagedPaymentViolation.FulfillmentAlreadySet)
        }
        val nextLifecycle = lifecycle.append(
            historyId = historyId,
            event = StagedPaymentEvent.FULFILLMENT_CHANGED,
            occurredAt = occurredAt,
            paidAmount = lifecycle.paidAmount,
            dueAmount = lifecycle.dueAmount,
            paymentId = null,
            paymentProgress = paymentProgress,
            fulfillmentStatus = fulfillment,
        )
        return StagedPaymentResult.Success(copyWithLifecycle(nextLifecycle))
    }

    fun confirmCompletion(
        historyId: StagedPaymentHistoryId,
        occurredAt: Instant,
    ): StagedPaymentResult<StagedPayment> {
        if (lifecycle.dueAmount.minorUnits != 0L) {
            return StagedPaymentResult.Failure(StagedPaymentViolation.CompletionRequiresPaidInFull)
        }
        if (lifecycle.stateHistory.any { it.event == StagedPaymentEvent.COMPLETION_CONFIRMED }) {
            return StagedPaymentResult.Failure(StagedPaymentViolation.CompletionAlreadyConfirmed)
        }
        val identityFailure = validateStateHistoryAppend(historyId, occurredAt)
        if (identityFailure != null) return StagedPaymentResult.Failure(identityFailure)
        val nextLifecycle = lifecycle.append(
            historyId = historyId,
            event = StagedPaymentEvent.COMPLETION_CONFIRMED,
            occurredAt = occurredAt,
            paidAmount = lifecycle.paidAmount,
            dueAmount = lifecycle.dueAmount,
            paymentId = null,
            paymentProgress = paymentProgress,
            fulfillmentStatus = fulfillmentStatus,
        )
        return StagedPaymentResult.Success(copyWithLifecycle(nextLifecycle))
    }

    fun reconciliation(
        facts: List<StagedPaymentReconciliationFact>,
    ): StagedPaymentResult<StagedPaymentReconciliation> {
        val factsByPosting = mutableMapOf<PostingId, StagedPaymentReconciliationFact>()
        for (fact in facts) {
            val existing = factsByPosting[fact.postingId]
            if (existing != null && existing != fact) {
                return StagedPaymentResult.Failure(StagedPaymentViolation.ConflictingReconciliationFacts)
            }
            factsByPosting[fact.postingId] = fact
        }

        val ownedPaymentAssetPostingIds = installments.mapTo(mutableSetOf()) { it.assetPostingId }
        val matchedInstallmentCount = factsByPosting.values.count {
            it.postingId in ownedPaymentAssetPostingIds &&
                it.eligible &&
                it.status == StagedPaymentReconciliationStatus.MATCHED
        }
        val status = when {
            installments.isEmpty() || matchedInstallmentCount == 0 -> StagedPaymentReconciliation.PENDING
            matchedInstallmentCount < installments.size -> StagedPaymentReconciliation.PARTIAL
            else -> StagedPaymentReconciliation.COMPLETE
        }
        return StagedPaymentResult.Success(status)
    }

    private fun validateStateHistoryAppend(
        historyId: StagedPaymentHistoryId,
        occurredAt: Instant,
    ): StagedPaymentViolation? =
        when {
            lifecycle.stateHistory.any { it.id == historyId } -> StagedPaymentViolation.DuplicateIdentity
            occurredAt <= lifecycle.stateHistory.last().occurredAt -> StagedPaymentViolation.HistoryTimeMustIncrease
            else -> null
        }

    private fun hasDuplicateIdentity(ids: StagedPaymentInstallmentIds): Boolean {
        if (installments.any { it.id == ids.paymentId } || lifecycle.stateHistory.any { it.id == ids.historyId }) {
            return true
        }
        val existingPostingIds = installments.flatMapTo(mutableSetOf()) {
            listOf(it.expensePostingId, it.assetPostingId)
        }
        if (
            ids.expenseIds.expensePostingId == ids.expenseIds.paymentPostingId ||
            ids.expenseIds.expensePostingId in existingPostingIds ||
            ids.expenseIds.paymentPostingId in existingPostingIds
        ) {
            return true
        }
        return installments.any { payment ->
            payment.transactionId == ids.expenseIds.transactionId
        } || formalTransactions.any { formal ->
            formal.versions.any { it.id == ids.expenseIds.versionId } ||
                formal.postingSets.any { it.id == ids.expenseIds.postingSetId }
        }
    }

    private fun copyWithLifecycle(nextLifecycle: StagedPaymentLifecycle): StagedPayment =
        StagedPayment(
            ledgerId = ledgerId,
            relation = relation,
            lifecycle = nextLifecycle,
            installments = installments,
            formalTransactions = formalTransactions,
        )

    companion object {
        internal fun create(
            ledgerId: LedgerId,
            relation: StagedPaymentRelation,
            lifecycle: StagedPaymentLifecycle,
        ): StagedPayment =
            StagedPayment(
                ledgerId = ledgerId,
                relation = relation,
                lifecycle = lifecycle,
                installments = emptyList(),
                formalTransactions = emptyList(),
            )
    }
}

fun createStagedPayment(
    catalog: LedgerCatalog,
    command: CreateStagedPaymentCommand,
    ids: StagedPaymentCreationIds,
): StagedPaymentResult<StagedPayment> {
    if (command.totalAmount.minorUnits <= 0L) {
        return StagedPaymentResult.Failure(StagedPaymentViolation.TotalAmountMustBePositive)
    }
    val category = catalog.category(command.categoryId)
        ?: return StagedPaymentResult.Failure(StagedPaymentViolation.SecondaryCategoryRequired)
    val parentId = category.parentId
        ?: return StagedPaymentResult.Failure(StagedPaymentViolation.SecondaryCategoryRequired)
    val parent = catalog.category(parentId)
        ?: return StagedPaymentResult.Failure(StagedPaymentViolation.SecondaryCategoryRequired)
    if (
        category.ledgerId != command.ledgerId ||
        parent.ledgerId != command.ledgerId ||
        parent.parentId != null
    ) {
        return StagedPaymentResult.Failure(StagedPaymentViolation.SecondaryCategoryRequired)
    }
    if (!category.active) {
        return StagedPaymentResult.Failure(StagedPaymentViolation.CategoryInactive)
    }
    if (category.kind != CategoryKind.EXPENSE || parent.kind != CategoryKind.EXPENSE) {
        return StagedPaymentResult.Failure(StagedPaymentViolation.ExpenseCategoryRequired)
    }
    val expenseAccount = category.postingAccountId?.let(catalog::account)
        ?: return StagedPaymentResult.Failure(StagedPaymentViolation.ExpenseCategoryRequired)
    if (
        expenseAccount.ledgerId != command.ledgerId ||
        expenseAccount.kind != AccountKind.EXPENSE ||
        expenseAccount.realAccount
    ) {
        return StagedPaymentResult.Failure(StagedPaymentViolation.ExpenseCategoryRequired)
    }
    if (expenseAccount.currency != command.totalAmount.currency) {
        return StagedPaymentResult.Failure(StagedPaymentViolation.SingleCurrencyRequired)
    }

    val lifecycle = StagedPaymentLifecycle.create(
        id = ids.lifecycleId,
        totalAmount = command.totalAmount,
        categoryId = command.categoryId,
        historyId = ids.historyId,
        createdAt = command.createdAt,
    )
    return StagedPaymentResult.Success(
        StagedPayment.create(
            ledgerId = command.ledgerId,
            relation = StagedPaymentRelation.create(ids.relationId, ids.lifecycleId),
            lifecycle = lifecycle,
        ),
    )
}

private fun progressFor(paidMinor: Long, dueMinor: Long): StagedPaymentProgress =
    when {
        paidMinor == 0L -> StagedPaymentProgress.UNPAID
        dueMinor == 0L -> StagedPaymentProgress.PAID_IN_FULL
        else -> StagedPaymentProgress.PARTIALLY_PAID
    }

private val SOURCE_OFFSET_PATTERN = Regex(
    "^(?:Z|\\+(?:[01][0-9]|2[0-3]):[0-5][0-9]|-(?!00:00)(?:[01][0-9]|2[0-3]):[0-5][0-9])$",
)

private val STRICT_RFC3339_PATTERN = Regex(
    "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}" +
        "(?:\\.[0-9]+)?(?:Z|\\+(?:[01][0-9]|2[0-3]):[0-5][0-9]" +
        "|-(?!00:00)(?:[01][0-9]|2[0-3]):[0-5][0-9])$",
)

private fun normalizeZeroOffset(offsetText: String): String =
    if (offsetText == "Z") "+00:00" else offsetText
