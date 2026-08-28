package com.unifiedledger.domain

import kotlin.time.Instant

data class StagedPaymentRelationId(
    val value: String,
)

data class StagedPaymentLifecycleId(
    val value: String,
)

data class InstallmentPaymentId(
    val value: String,
)

data class StagedPaymentHistoryId(
    val value: String,
)

sealed interface StagedPaymentResult<out T> {
    data class Success<out T>(
        val value: T,
    ) : StagedPaymentResult<T>

    data class Failure(
        val violation: StagedPaymentViolation,
    ) : StagedPaymentResult<Nothing>
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
            val parsed =
                try {
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

        internal fun restoreStructural(
            instant: Instant,
            text: String,
        ): StagedPaymentSourceTime? {
            if (!STRICT_RFC3339_PATTERN.matches(text)) return null
            val parsed =
                try {
                    Instant.parse(text)
                } catch (_: IllegalArgumentException) {
                    return null
                }
            return if (parsed == instant) StagedPaymentSourceTime(instant, text) else null
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
    data class Lifecycle(
        val id: StagedPaymentLifecycleId,
    ) : StagedPaymentMemberRef

    data class Installment(
        val id: InstallmentPaymentId,
    ) : StagedPaymentMemberRef
}

class StagedPaymentRelation private constructor(
    val id: StagedPaymentRelationId,
    memberRefs: Set<StagedPaymentMemberRef>,
) {
    private val memberRefSnapshot = memberRefs.toSet()
    val memberRefs: Set<StagedPaymentMemberRef> get() = memberRefSnapshot.toSet()
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

        internal fun rehydrate(snapshot: StagedPaymentRelationSnapshot): StagedPaymentRelation = StagedPaymentRelation(snapshot.id, snapshot.memberRefs.toSet())
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
    stateHistory: List<StagedPaymentHistoryEntry>,
) {
    private val historySnapshot = stateHistory.toList()
    val stateHistory: List<StagedPaymentHistoryEntry> get() = historySnapshot.toMutableList()
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
            stateHistory =
                stateHistory +
                    StagedPaymentHistoryEntry(
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
                stateHistory =
                    listOf(
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

        internal fun rehydrate(snapshot: StagedPaymentLifecycleSnapshot): StagedPaymentLifecycle =
            StagedPaymentLifecycle(
                id = snapshot.id,
                totalAmount = snapshot.totalAmount,
                paidAmount = snapshot.paidAmount,
                dueAmount = snapshot.dueAmount,
                currency = snapshot.currency,
                categoryId = snapshot.categoryId,
                stateHistory = snapshot.stateHistory,
            )
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

class StagedPaymentRelationSnapshot(
    val id: StagedPaymentRelationId,
    memberRefs: Collection<StagedPaymentMemberRef>,
) {
    private val memberRefSnapshot = memberRefs.toList()
    val memberRefs: List<StagedPaymentMemberRef> get() = memberRefSnapshot.toMutableList()

    fun copy(
        id: StagedPaymentRelationId = this.id,
        memberRefs: Collection<StagedPaymentMemberRef> = this.memberRefs,
    ): StagedPaymentRelationSnapshot = StagedPaymentRelationSnapshot(id, memberRefs)

    override fun equals(other: Any?): Boolean = other is StagedPaymentRelationSnapshot && id == other.id && memberRefSnapshot == other.memberRefSnapshot

    override fun hashCode(): Int = 31 * id.hashCode() + memberRefSnapshot.hashCode()
}

class StagedPaymentLifecycleSnapshot(
    val id: StagedPaymentLifecycleId,
    val totalAmount: Money,
    val paidAmount: Money,
    val dueAmount: Money,
    val currency: CurrencyUnit,
    val categoryId: CategoryId,
    stateHistory: Collection<StagedPaymentHistoryEntry>,
) {
    private val historySnapshot = stateHistory.toList()
    val stateHistory: List<StagedPaymentHistoryEntry> get() = historySnapshot.toMutableList()

    fun copy(
        id: StagedPaymentLifecycleId = this.id,
        totalAmount: Money = this.totalAmount,
        paidAmount: Money = this.paidAmount,
        dueAmount: Money = this.dueAmount,
        currency: CurrencyUnit = this.currency,
        categoryId: CategoryId = this.categoryId,
        stateHistory: Collection<StagedPaymentHistoryEntry> = this.stateHistory,
    ): StagedPaymentLifecycleSnapshot =
        StagedPaymentLifecycleSnapshot(
            id,
            totalAmount,
            paidAmount,
            dueAmount,
            currency,
            categoryId,
            stateHistory,
        )

    override fun equals(other: Any?): Boolean =
        other is StagedPaymentLifecycleSnapshot &&
            id == other.id &&
            totalAmount == other.totalAmount &&
            paidAmount == other.paidAmount &&
            dueAmount == other.dueAmount &&
            currency == other.currency &&
            categoryId == other.categoryId &&
            historySnapshot == other.historySnapshot

    override fun hashCode(): Int = arrayOf(id, totalAmount, paidAmount, dueAmount, currency, categoryId, historySnapshot).contentHashCode()
}

data class StagedPaymentInstallmentSnapshot(
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
    val sourcePaymentAt: Instant?,
    val sourcePaymentAtText: String?,
)

data class StagedPaymentTransactionSnapshot(
    val id: TransactionId,
    val ledgerId: LedgerId,
    val kind: TransactionKind,
    val currentVersionId: TransactionVersionId,
)

data class StagedPaymentTransactionVersionSnapshot(
    val id: TransactionVersionId,
    val transactionId: TransactionId,
    val versionNumber: Int,
    val postingSetId: PostingSetId,
    val times: TransactionTimes,
    val note: String? = null,
)

data class StagedPaymentPostingSnapshot(
    val id: PostingId,
    val accountId: AccountId,
    val amount: Money,
)

class StagedPaymentPostingSetSnapshot(
    val id: PostingSetId,
    postings: Collection<StagedPaymentPostingSnapshot>,
) {
    private val postingSnapshot = postings.map { it.copy() }
    val postings: List<StagedPaymentPostingSnapshot> get() = postingSnapshot.map { it.copy() }.toMutableList()

    fun copy(
        id: PostingSetId = this.id,
        postings: Collection<StagedPaymentPostingSnapshot> = this.postings,
    ): StagedPaymentPostingSetSnapshot = StagedPaymentPostingSetSnapshot(id, postings)

    override fun equals(other: Any?): Boolean = other is StagedPaymentPostingSetSnapshot && id == other.id && postingSnapshot == other.postingSnapshot

    override fun hashCode(): Int = 31 * id.hashCode() + postingSnapshot.hashCode()
}

class StagedPaymentFormalTransactionSnapshot(
    val transaction: StagedPaymentTransactionSnapshot,
    versions: Collection<StagedPaymentTransactionVersionSnapshot>,
    postingSets: Collection<StagedPaymentPostingSetSnapshot>,
) {
    private val versionSnapshot = versions.map { it.copy(times = it.times.copy()) }
    private val postingSetSnapshot = postingSets.map { it.copy() }
    val versions: List<StagedPaymentTransactionVersionSnapshot>
        get() = versionSnapshot.map { it.copy(times = it.times.copy()) }.toMutableList()
    val postingSets: List<StagedPaymentPostingSetSnapshot>
        get() = postingSetSnapshot.map { it.copy() }.toMutableList()

    fun copy(
        transaction: StagedPaymentTransactionSnapshot = this.transaction,
        versions: Collection<StagedPaymentTransactionVersionSnapshot> = this.versions,
        postingSets: Collection<StagedPaymentPostingSetSnapshot> = this.postingSets,
    ): StagedPaymentFormalTransactionSnapshot =
        StagedPaymentFormalTransactionSnapshot(
            transaction.copy(),
            versions,
            postingSets,
        )

    override fun equals(other: Any?): Boolean =
        other is StagedPaymentFormalTransactionSnapshot &&
            transaction == other.transaction &&
            versionSnapshot == other.versionSnapshot &&
            postingSetSnapshot == other.postingSetSnapshot

    override fun hashCode(): Int = arrayOf(transaction, versionSnapshot, postingSetSnapshot).contentHashCode()
}

private fun FormalTransaction.toStagedPaymentSnapshot(): StagedPaymentFormalTransactionSnapshot =
    StagedPaymentFormalTransactionSnapshot(
        transaction =
            StagedPaymentTransactionSnapshot(
                transaction.id,
                transaction.ledgerId,
                transaction.kind,
                transaction.currentVersionId,
            ),
        versions =
            versions.map { version ->
                StagedPaymentTransactionVersionSnapshot(
                    version.id,
                    version.transactionId,
                    version.versionNumber,
                    version.postingSetId,
                    version.times.copy(),
                    version.note,
                )
            },
        postingSets =
            postingSets.map { postingSet ->
                StagedPaymentPostingSetSnapshot(
                    postingSet.id,
                    postingSet.postings.map { posting ->
                        StagedPaymentPostingSnapshot(posting.id, posting.accountId, posting.amount)
                    },
                )
            },
    )

private fun StagedPaymentFormalTransactionSnapshot.toFormalTransactionOrNull(): FormalTransaction? {
    val restoredPostingSets = mutableListOf<PostingSet>()
    for (postingSet in postingSets) {
        val restored =
            try {
                PostingSet.create(
                    postingSet.id,
                    postingSet.postings.map { posting ->
                        Posting(posting.id, posting.accountId, posting.amount)
                    },
                )
            } catch (_: IllegalArgumentException) {
                return null
            }
        when (restored) {
            is DomainResult.Success -> restoredPostingSets += restored.value
            is DomainResult.Failure -> return null
        }
    }

    val restored =
        try {
            FormalTransaction.create(
                transaction =
                    Transaction(
                        transaction.id,
                        transaction.ledgerId,
                        transaction.kind,
                        transaction.currentVersionId,
                    ),
                versions =
                    versions.map { version ->
                        TransactionVersion(
                            version.id,
                            version.transactionId,
                            version.versionNumber,
                            version.postingSetId,
                            version.times.copy(),
                            version.note,
                        )
                    },
                postingSets = restoredPostingSets,
            )
        } catch (_: IllegalArgumentException) {
            return null
        }
    return when (restored) {
        is DomainResult.Success -> restored.value
        is DomainResult.Failure -> null
    }
}

private fun copyFormalTransaction(formalTransaction: FormalTransaction): FormalTransaction = checkNotNull(formalTransaction.toStagedPaymentSnapshot().toFormalTransactionOrNull())

class StagedPaymentSnapshot(
    val ledgerId: LedgerId,
    val relation: StagedPaymentRelationSnapshot,
    val lifecycle: StagedPaymentLifecycleSnapshot,
    installments: Collection<StagedPaymentInstallmentSnapshot>,
    formalTransactions: Collection<StagedPaymentFormalTransactionSnapshot>,
) {
    private val installmentSnapshot = installments.toList()
    private val formalTransactionSnapshot = formalTransactions.map { it.copy() }
    val installments: List<StagedPaymentInstallmentSnapshot> get() = installmentSnapshot.toMutableList()
    val formalTransactions: List<StagedPaymentFormalTransactionSnapshot>
        get() = formalTransactionSnapshot.map { it.copy() }.toMutableList()

    fun copy(
        ledgerId: LedgerId = this.ledgerId,
        relation: StagedPaymentRelationSnapshot = this.relation,
        lifecycle: StagedPaymentLifecycleSnapshot = this.lifecycle,
        installments: Collection<StagedPaymentInstallmentSnapshot> = this.installments,
        formalTransactions: Collection<StagedPaymentFormalTransactionSnapshot> = this.formalTransactions,
    ): StagedPaymentSnapshot =
        StagedPaymentSnapshot(
            ledgerId,
            relation,
            lifecycle,
            installments,
            formalTransactions,
        )
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

    data class DuplicateRole(
        val role: StagedPaymentRole,
    ) : StagedPaymentViolation

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

    data class InvalidSnapshot(
        val problem: StagedPaymentSnapshotProblem,
        val index: Int?,
    ) : StagedPaymentViolation

    data class DependencyViolation(
        val cause: DomainViolation,
    ) : StagedPaymentViolation
}

enum class StagedPaymentSnapshotProblem {
    RELATION_MEMBERSHIP,
    LIFECYCLE_ARITHMETIC,
    IDENTITY,
    HISTORY,
    INSTALLMENT,
    SOURCE_TIME,
    FORMAL_TRANSACTION,
    FORMAL_LINKAGE,
}

class StagedPayment private constructor(
    val ledgerId: LedgerId,
    val relation: StagedPaymentRelation,
    val lifecycle: StagedPaymentLifecycle,
    installments: List<InstallmentPayment>,
    formalTransactions: List<FormalTransaction>,
) {
    private val installmentSnapshot = installments.toList()
    private val formalTransactionSnapshot = formalTransactions.map(::copyFormalTransaction)
    val installments: List<InstallmentPayment> get() = installmentSnapshot.toMutableList()
    val formalTransactions: List<FormalTransaction>
        get() = formalTransactionSnapshot.map(::copyFormalTransaction).toMutableList()

    val paymentProgress: StagedPaymentProgress
        get() = progressFor(lifecycle.paidAmount.minorUnits, lifecycle.dueAmount.minorUnits)

    val fulfillmentStatus: StagedPaymentFulfillment
        get() = lifecycle.stateHistory.last().fulfillmentStatus

    fun snapshot(): StagedPaymentSnapshot =
        StagedPaymentSnapshot(
            ledgerId = ledgerId,
            relation = StagedPaymentRelationSnapshot(relation.id, relation.memberRefs),
            lifecycle =
                StagedPaymentLifecycleSnapshot(
                    lifecycle.id,
                    lifecycle.totalAmount,
                    lifecycle.paidAmount,
                    lifecycle.dueAmount,
                    lifecycle.currency,
                    lifecycle.categoryId,
                    lifecycle.stateHistory,
                ),
            installments =
                installments.map { payment ->
                    StagedPaymentInstallmentSnapshot(
                        payment.id,
                        payment.role,
                        payment.amount,
                        payment.currency,
                        payment.fundingAccountId,
                        payment.transactionId,
                        payment.expensePostingId,
                        payment.assetPostingId,
                        payment.actualPaymentAt,
                        payment.statisticsAt,
                        payment.sourcePaymentAt,
                        payment.sourcePaymentAtText,
                    )
                },
            formalTransactions = formalTransactionSnapshot.map(FormalTransaction::toStagedPaymentSnapshot),
        )

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

        val fundingAccount =
            catalog.account(command.fundingAccountId)
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

        val paidMinor =
            checkedAdd(lifecycle.paidAmount.minorUnits, command.amount.minorUnits)
                ?: return StagedPaymentResult.Failure(
                    StagedPaymentViolation.DependencyViolation(DomainViolation.ArithmeticOverflow),
                )
        val negativePayment =
            checkedNegate(command.amount.minorUnits)
                ?: return StagedPaymentResult.Failure(
                    StagedPaymentViolation.DependencyViolation(DomainViolation.ArithmeticOverflow),
                )
        val dueMinor =
            checkedAdd(lifecycle.dueAmount.minorUnits, negativePayment)
                ?: return StagedPaymentResult.Failure(
                    StagedPaymentViolation.DependencyViolation(DomainViolation.ArithmeticOverflow),
                )
        if (dueMinor < 0L) {
            return StagedPaymentResult.Failure(StagedPaymentViolation.PaymentExceedsDue)
        }

        val times =
            TransactionTimes(
                occurredAt = command.actualPaymentAt,
                statisticsAt = command.actualPaymentAt,
                effectiveAt = command.actualPaymentAt,
            )
        val formalTransaction =
            when (
                val result =
                    createAssetPaidOrdinaryExpense(
                        catalog = catalog,
                        command =
                            AssetPaidOrdinaryExpenseCommand(
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
        val installment =
            InstallmentPayment(
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
        val nextLifecycle =
            lifecycle.append(
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
        val nextLifecycle =
            lifecycle.append(
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
        val nextLifecycle =
            lifecycle.append(
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
        val matchedInstallmentCount =
            factsByPosting.values.count {
                it.postingId in ownedPaymentAssetPostingIds &&
                    it.eligible &&
                    it.status == StagedPaymentReconciliationStatus.MATCHED
            }
        val status =
            when {
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
        val existingPostingIds =
            formalTransactions.flatMapTo(mutableSetOf()) { formal ->
                formal.postingSets.flatMap { postingSet -> postingSet.postings.map { it.id } }
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
        } ||
            formalTransactions.any { formal ->
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

        internal fun rehydrate(
            ledgerId: LedgerId,
            relation: StagedPaymentRelation,
            lifecycle: StagedPaymentLifecycle,
            installments: List<InstallmentPayment>,
            formalTransactions: List<FormalTransaction>,
        ): StagedPayment =
            StagedPayment(
                ledgerId,
                relation,
                lifecycle,
                installments,
                formalTransactions,
            )
    }
}

/**
 * Restores a persisted RG-06 aggregate without consulting a current catalog or replaying commands.
 * Validation is deterministic: relation, lifecycle arithmetic, history, installments, identities,
 * source time, formal construction, then current formal linkage. Malformed content is returned as
 * [StagedPaymentViolation.InvalidSnapshot]; dependency factories are never allowed to throw through
 * this boundary.
 */
fun rehydrateStagedPayment(snapshot: StagedPaymentSnapshot): StagedPaymentResult<StagedPayment> {
    fun invalid(
        problem: StagedPaymentSnapshotProblem,
        index: Int? = null,
    ) = StagedPaymentResult.Failure(StagedPaymentViolation.InvalidSnapshot(problem, index))

    val installmentSnapshots = snapshot.installments
    val formalSnapshots = snapshot.formalTransactions
    val relationRows = snapshot.relation.memberRefs
    val relationMembers = mutableSetOf<StagedPaymentMemberRef>()
    relationRows.forEachIndexed { index, member ->
        if (!relationMembers.add(member)) {
            return invalid(StagedPaymentSnapshotProblem.RELATION_MEMBERSHIP, index)
        }
    }
    val expectedMembers =
        buildSet {
            add(StagedPaymentMemberRef.Lifecycle(snapshot.lifecycle.id))
            installmentSnapshots.forEach { add(StagedPaymentMemberRef.Installment(it.id)) }
        }
    if (relationMembers != expectedMembers) {
        return invalid(StagedPaymentSnapshotProblem.RELATION_MEMBERSHIP)
    }

    val lifecycle = snapshot.lifecycle
    if (
        lifecycle.totalAmount.minorUnits <= 0L ||
        lifecycle.paidAmount.minorUnits < 0L ||
        lifecycle.dueAmount.minorUnits < 0L ||
        lifecycle.totalAmount.currency != lifecycle.currency ||
        lifecycle.paidAmount.currency != lifecycle.currency ||
        lifecycle.dueAmount.currency != lifecycle.currency ||
        checkedAdd(lifecycle.paidAmount.minorUnits, lifecycle.dueAmount.minorUnits) != lifecycle.totalAmount.minorUnits
    ) {
        return invalid(StagedPaymentSnapshotProblem.LIFECYCLE_ARITHMETIC)
    }

    val history = lifecycle.stateHistory
    if (history.isEmpty()) return invalid(StagedPaymentSnapshotProblem.HISTORY)
    var expectedPaid = 0L
    var fulfillment = StagedPaymentFulfillment.IN_PROGRESS
    var paymentIndex = 0
    var fulfillmentChanged = false
    var completionConfirmed = false
    history.forEachIndexed { index, entry ->
        if (
            entry.sequence != index + 1 ||
            entry.stateTransitionEffectCount != 0 ||
            (index > 0 && entry.occurredAt <= history[index - 1].occurredAt) ||
            entry.totalAmount != lifecycle.totalAmount ||
            entry.paidAmount.currency != lifecycle.currency ||
            entry.dueAmount.currency != lifecycle.currency ||
            entry.paidAmount.minorUnits < 0L ||
            entry.dueAmount.minorUnits < 0L ||
            checkedAdd(entry.paidAmount.minorUnits, entry.dueAmount.minorUnits) != lifecycle.totalAmount.minorUnits ||
            entry.paymentProgress != progressFor(entry.paidAmount.minorUnits, entry.dueAmount.minorUnits)
        ) {
            return invalid(StagedPaymentSnapshotProblem.HISTORY, index)
        }
        when (entry.event) {
            StagedPaymentEvent.GROUP_CREATED ->
                if (
                    index != 0 ||
                    entry.paymentId != null ||
                    entry.paidAmount.minorUnits != 0L ||
                    entry.dueAmount != lifecycle.totalAmount ||
                    entry.fulfillmentStatus != StagedPaymentFulfillment.IN_PROGRESS
                ) {
                    return invalid(StagedPaymentSnapshotProblem.HISTORY, index)
                }

            StagedPaymentEvent.PAYMENT_CONFIRMED -> {
                val payment =
                    installmentSnapshots.getOrNull(paymentIndex)
                        ?: return invalid(StagedPaymentSnapshotProblem.HISTORY, index)
                val nextPaid =
                    checkedAdd(expectedPaid, payment.amount.minorUnits)
                        ?: return invalid(StagedPaymentSnapshotProblem.HISTORY, index)
                val nextDue =
                    checkedAdd(lifecycle.totalAmount.minorUnits, checkedNegate(nextPaid) ?: return invalid(StagedPaymentSnapshotProblem.HISTORY, index))
                        ?: return invalid(StagedPaymentSnapshotProblem.HISTORY, index)
                if (
                    entry.paymentId != payment.id ||
                    entry.occurredAt != payment.actualPaymentAt ||
                    entry.paidAmount.minorUnits != nextPaid ||
                    entry.dueAmount.minorUnits != nextDue ||
                    entry.fulfillmentStatus != fulfillment
                ) {
                    return invalid(StagedPaymentSnapshotProblem.HISTORY, index)
                }
                expectedPaid = nextPaid
                paymentIndex += 1
            }

            StagedPaymentEvent.FULFILLMENT_CHANGED -> {
                if (
                    entry.paymentId != null ||
                    fulfillmentChanged ||
                    entry.paidAmount.minorUnits != expectedPaid ||
                    entry.dueAmount.minorUnits != lifecycle.totalAmount.minorUnits - expectedPaid ||
                    fulfillment != StagedPaymentFulfillment.IN_PROGRESS ||
                    entry.fulfillmentStatus != StagedPaymentFulfillment.FULFILLED
                ) {
                    return invalid(StagedPaymentSnapshotProblem.HISTORY, index)
                }
                fulfillment = StagedPaymentFulfillment.FULFILLED
                fulfillmentChanged = true
            }

            StagedPaymentEvent.COMPLETION_CONFIRMED -> {
                if (
                    entry.paymentId != null ||
                    completionConfirmed ||
                    entry.paidAmount.minorUnits != expectedPaid ||
                    entry.dueAmount.minorUnits != 0L ||
                    expectedPaid != lifecycle.totalAmount.minorUnits ||
                    entry.fulfillmentStatus != fulfillment
                ) {
                    return invalid(StagedPaymentSnapshotProblem.HISTORY, index)
                }
                completionConfirmed = true
            }
        }
    }
    val latest = history.last()
    if (
        paymentIndex != installmentSnapshots.size ||
        latest.paidAmount != lifecycle.paidAmount ||
        latest.dueAmount != lifecycle.dueAmount ||
        latest.fulfillmentStatus != fulfillment
    ) {
        return invalid(StagedPaymentSnapshotProblem.HISTORY, history.lastIndex)
    }

    val expectedRoles =
        when (installmentSnapshots.size) {
            0 -> emptyList()
            1 -> listOf(StagedPaymentRole.DEPOSIT)
            2 -> listOf(StagedPaymentRole.DEPOSIT, StagedPaymentRole.FINAL)
            else -> return invalid(StagedPaymentSnapshotProblem.INSTALLMENT, 2)
        }
    installmentSnapshots.forEachIndexed { index, payment ->
        if (
            payment.role != expectedRoles[index] ||
            payment.amount.minorUnits <= 0L ||
            payment.amount.currency != lifecycle.currency ||
            payment.currency != lifecycle.currency ||
            payment.statisticsAt != payment.actualPaymentAt
        ) {
            return invalid(StagedPaymentSnapshotProblem.INSTALLMENT, index)
        }
        if (index == 0 && payment.amount.minorUnits >= lifecycle.totalAmount.minorUnits) {
            return invalid(StagedPaymentSnapshotProblem.INSTALLMENT, index)
        }
        if (index == 1) {
            val deposit = installmentSnapshots[0]
            val remaining = checkedAdd(lifecycle.totalAmount.minorUnits, checkedNegate(deposit.amount.minorUnits)!!)
            if (
                remaining != payment.amount.minorUnits || payment.actualPaymentAt <= deposit.actualPaymentAt
            ) {
                return invalid(StagedPaymentSnapshotProblem.INSTALLMENT, index)
            }
        }
    }

    val historyIds = mutableSetOf<StagedPaymentHistoryId>()
    history.forEachIndexed { index, entry ->
        if (!historyIds.add(entry.id)) return invalid(StagedPaymentSnapshotProblem.IDENTITY, index)
    }
    val paymentIds = mutableSetOf<InstallmentPaymentId>()
    val transactionIds = mutableSetOf<TransactionId>()
    val installmentPostingIds = mutableSetOf<PostingId>()
    installmentSnapshots.forEachIndexed { index, payment ->
        if (
            !paymentIds.add(payment.id) ||
            !transactionIds.add(payment.transactionId) ||
            payment.expensePostingId == payment.assetPostingId ||
            !installmentPostingIds.add(payment.expensePostingId) ||
            !installmentPostingIds.add(payment.assetPostingId)
        ) {
            return invalid(StagedPaymentSnapshotProblem.IDENTITY, index)
        }
    }
    val formalTransactionIds = mutableSetOf<TransactionId>()
    val formalVersionIds = mutableSetOf<TransactionVersionId>()
    val formalPostingSetIds = mutableSetOf<PostingSetId>()
    val formalPostingIds = mutableSetOf<PostingId>()
    formalSnapshots.forEachIndexed { index, formal ->
        if (!formalTransactionIds.add(formal.transaction.id)) {
            return invalid(StagedPaymentSnapshotProblem.IDENTITY, index)
        }
        for (version in formal.versions) {
            if (!formalVersionIds.add(version.id)) return invalid(StagedPaymentSnapshotProblem.IDENTITY, index)
        }
        for (postingSet in formal.postingSets) {
            if (!formalPostingSetIds.add(postingSet.id)) return invalid(StagedPaymentSnapshotProblem.IDENTITY, index)
            for (posting in postingSet.postings) {
                if (!formalPostingIds.add(posting.id)) return invalid(StagedPaymentSnapshotProblem.IDENTITY, index)
            }
        }
    }

    installmentSnapshots.forEachIndexed { index, payment ->
        val sourceInstant = payment.sourcePaymentAt
        val sourceText = payment.sourcePaymentAtText
        if ((sourceInstant == null) != (sourceText == null)) {
            return invalid(StagedPaymentSnapshotProblem.SOURCE_TIME, index)
        }
        if (sourceInstant != null && StagedPaymentSourceTime.restoreStructural(sourceInstant, sourceText!!) == null) {
            return invalid(StagedPaymentSnapshotProblem.SOURCE_TIME, index)
        }
        if (
            index == 1 &&
            sourceInstant != null &&
            installmentSnapshots[0].sourcePaymentAt != null &&
            sourceInstant <= installmentSnapshots[0].sourcePaymentAt!!
        ) {
            return invalid(StagedPaymentSnapshotProblem.SOURCE_TIME, index)
        }
    }

    val restoredFormals = mutableListOf<FormalTransaction>()
    formalSnapshots.forEachIndexed { index, formal ->
        val restored =
            formal.toFormalTransactionOrNull()
                ?: return invalid(StagedPaymentSnapshotProblem.FORMAL_TRANSACTION, index)
        restoredFormals += restored
    }
    if (restoredFormals.size != installmentSnapshots.size) {
        return invalid(StagedPaymentSnapshotProblem.FORMAL_LINKAGE)
    }
    installmentSnapshots.forEachIndexed { index, payment ->
        val formal = restoredFormals[index]
        if (formal.transaction.id != payment.transactionId) {
            return invalid(StagedPaymentSnapshotProblem.FORMAL_LINKAGE, index)
        }
        val currentVersion =
            formal.versions.singleOrNull { it.id == formal.transaction.currentVersionId }
                ?: return invalid(StagedPaymentSnapshotProblem.FORMAL_LINKAGE, index)
        val currentSet =
            formal.postingSets.singleOrNull { it.id == currentVersion.postingSetId }
                ?: return invalid(StagedPaymentSnapshotProblem.FORMAL_LINKAGE, index)
        if (
            formal.transaction.ledgerId != snapshot.ledgerId ||
            formal.transaction.kind != TransactionKind.EXPENSE ||
            currentVersion.times.occurredAt != payment.actualPaymentAt ||
            currentVersion.times.statisticsAt != payment.statisticsAt ||
            currentVersion.times.effectiveAt != payment.actualPaymentAt ||
            currentSet.postings.size != 2
        ) {
            return invalid(StagedPaymentSnapshotProblem.FORMAL_LINKAGE, index)
        }
        val postings = currentSet.postings.associateBy { it.id }
        if (postings.size != 2 || postings.keys != setOf(payment.expensePostingId, payment.assetPostingId)) {
            return invalid(StagedPaymentSnapshotProblem.FORMAL_LINKAGE, index)
        }
        val expensePosting = postings.getValue(payment.expensePostingId)
        val assetPosting = postings.getValue(payment.assetPostingId)
        if (
            expensePosting.amount != payment.amount ||
            assetPosting.amount.currency != payment.currency ||
            assetPosting.amount.minorUnits != checkedNegate(payment.amount.minorUnits) ||
            assetPosting.accountId != payment.fundingAccountId ||
            expensePosting.accountId == payment.fundingAccountId
        ) {
            return invalid(StagedPaymentSnapshotProblem.FORMAL_LINKAGE, index)
        }
    }

    val restoredInstallments =
        installmentSnapshots.map { payment ->
            InstallmentPayment(
                payment.id,
                payment.role,
                payment.amount,
                payment.currency,
                payment.fundingAccountId,
                payment.transactionId,
                payment.expensePostingId,
                payment.assetPostingId,
                payment.actualPaymentAt,
                payment.statisticsAt,
                payment.sourcePaymentAt?.let { instant ->
                    StagedPaymentSourceTime.restoreStructural(instant, checkNotNull(payment.sourcePaymentAtText))
                },
            )
        }
    return StagedPaymentResult.Success(
        StagedPayment.rehydrate(
            snapshot.ledgerId,
            StagedPaymentRelation.rehydrate(snapshot.relation),
            StagedPaymentLifecycle.rehydrate(snapshot.lifecycle),
            restoredInstallments,
            restoredFormals,
        ),
    )
}

fun createStagedPayment(
    catalog: LedgerCatalog,
    command: CreateStagedPaymentCommand,
    ids: StagedPaymentCreationIds,
): StagedPaymentResult<StagedPayment> {
    if (command.totalAmount.minorUnits <= 0L) {
        return StagedPaymentResult.Failure(StagedPaymentViolation.TotalAmountMustBePositive)
    }
    val category =
        catalog.category(command.categoryId)
            ?: return StagedPaymentResult.Failure(StagedPaymentViolation.SecondaryCategoryRequired)
    val parentId =
        category.parentId
            ?: return StagedPaymentResult.Failure(StagedPaymentViolation.SecondaryCategoryRequired)
    val parent =
        catalog.category(parentId)
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
    val expenseAccount =
        category.postingAccountId?.let(catalog::account)
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

    val lifecycle =
        StagedPaymentLifecycle.create(
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

private fun progressFor(
    paidMinor: Long,
    dueMinor: Long,
): StagedPaymentProgress =
    when {
        paidMinor == 0L -> StagedPaymentProgress.UNPAID
        dueMinor == 0L -> StagedPaymentProgress.PAID_IN_FULL
        else -> StagedPaymentProgress.PARTIALLY_PAID
    }

private val SOURCE_OFFSET_PATTERN =
    Regex(
        "^(?:Z|\\+(?:[01][0-9]|2[0-3]):[0-5][0-9]|-(?!00:00)(?:[01][0-9]|2[0-3]):[0-5][0-9])$",
    )

private val STRICT_RFC3339_PATTERN =
    Regex(
        "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}" +
            "(?:\\.[0-9]+)?(?:Z|\\+(?:[01][0-9]|2[0-3]):[0-5][0-9]" +
            "|-(?!00:00)(?:[01][0-9]|2[0-3]):[0-5][0-9])$",
    )

private fun normalizeZeroOffset(offsetText: String): String = if (offsetText == "Z") "+00:00" else offsetText
