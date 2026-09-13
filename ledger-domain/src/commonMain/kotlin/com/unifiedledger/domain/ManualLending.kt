package com.unifiedledger.domain

import kotlin.time.Instant

/**
 * P7-02.C L-2..L-5 product manual-lending contract, an independent product equivalent of the
 * RG-08 replay orchestrator. It never touches [com.unifiedledger.application.Rg08Operations]:
 * no `BANK_DEBIT` source record, no fabricated hash and no `MATCHED` evidence are produced (L-5),
 * so a hand-entered LEND/COLLECT funding leg simply stays unreconciled.
 *
 * The outstanding position is always rebuilt through the frozen [createLendingPosition]
 * (LendingPosition.kt:51-112) as the sole rebuild validator, and the collect composition is
 * validated by the reused [createLendingSettlement] (LendingSettlement.kt:72-196). No new rebuild
 * function is introduced (L-4/P0-1).
 */
sealed interface ManualLendingViolation : DomainViolation {
    data object LendingCounterpartyNotFound : ManualLendingViolation

    data object LendingAccountNotEligible : ManualLendingViolation

    data object LendingPrincipalExceedsBalance : ManualLendingViolation

    data object LendingComponentsMismatch : ManualLendingViolation

    data object LendingFeeMustBeZero : ManualLendingViolation

    data object LendingInterestCategoryRequired : ManualLendingViolation

    data object LendingAmountMustBePositive : ManualLendingViolation

    data object LendingTotalMustBePositive : ManualLendingViolation

    data object LendingBackdatedNotAllowed : ManualLendingViolation

    /** Defensive dead code: the product surface only exposes the two fixed use cases (L-6). */
    data object LendingBehaviorNotSupported : ManualLendingViolation
}

/**
 * L-2/L-3 report effects. Principal is never ordinary income/expense: LEND reports a pure
 * principal external cash flow with `netWorthChange == 0`; COLLECT reports the principal cash
 * inflow separately from the interest ordinary income and `netWorthChange == interest`.
 */
data class ManualLendingReportEffects(
    val cashInflowMinor: Long,
    val cashOutflowMinor: Long,
    val ordinaryIncomeMinor: Long,
    val ordinaryExpenseMinor: Long,
    val consumptionMinor: Long,
    /** Signed principal movement: negative on LEND (money out), positive on COLLECT (money in). */
    val principalCashFlowMinor: Long,
    val internalTransferMinor: Long,
    val netWorthChangeMinor: Long,
    val feeMinor: Long,
)

data class ManualLendCommand(
    val ledgerId: LedgerId,
    val counterpartyId: CounterpartyId,
    val receivableAccountId: AccountId,
    val fundingAccountId: AccountId,
    val amount: Money,
    val times: TransactionTimes,
    val note: String = "",
)

data class ManualLendIds(
    /** Stable per-object history id; the append-only sort key is `(occurredAt, entryId)`. */
    val entryId: String,
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val receivablePostingId: PostingId,
    val fundingPostingId: PostingId,
)

data class ManualLend(
    val formalTransaction: FormalTransaction,
    val position: LendingPosition,
    val reportEffects: ManualLendingReportEffects,
)

data class ManualCollectCommand(
    val ledgerId: LedgerId,
    val counterpartyId: CounterpartyId,
    val receivableAccountId: AccountId,
    val destinationAccountId: AccountId,
    val interestCategoryId: CategoryId,
    val totalReceived: Money,
    val principal: Money,
    val interest: Money,
    val fee: Money,
    val times: TransactionTimes,
    val note: String = "",
)

data class ManualCollectIds(
    val entryId: String,
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val destinationPostingId: PostingId,
    val principalPostingId: PostingId,
    val interestPostingId: PostingId,
)

data class ManualCollect(
    val formalTransaction: FormalTransaction,
    val position: LendingPosition,
    val reportEffects: ManualLendingReportEffects,
)

/**
 * L-2/L-4: appends one LEND event to [position] and builds the two-leg LEND transaction
 * (receivable `+principal`, funding `-principal`). The event time must not be earlier than the
 * object's latest history time ([ManualLendingViolation.LendingBackdatedNotAllowed]).
 */
fun createManualLend(
    catalog: LedgerCatalog,
    position: LendingPosition,
    command: ManualLendCommand,
    ids: ManualLendIds,
): DomainResult<ManualLend> {
    if (position.counterpartyId != command.counterpartyId.value) {
        return DomainResult.Failure(ManualLendingViolation.LendingCounterpartyNotFound)
    }
    if (command.amount.minorUnits <= 0L) {
        return DomainResult.Failure(ManualLendingViolation.LendingAmountMustBePositive)
    }
    if (command.amount.currency != position.currency) {
        return DomainResult.Failure(ManualLendingViolation.LendingAccountNotEligible)
    }
    val funding =
        catalog.account(command.fundingAccountId)
            ?: return DomainResult.Failure(ManualLendingViolation.LendingAccountNotEligible)
    if (funding.ledgerId != command.ledgerId ||
        funding.kind != AccountKind.ASSET ||
        !funding.ownedByUser ||
        !funding.realAccount ||
        !funding.active ||
        funding.currency != command.amount.currency
    ) {
        return DomainResult.Failure(ManualLendingViolation.LendingAccountNotEligible)
    }
    val receivable =
        catalog.account(command.receivableAccountId)
            ?: return DomainResult.Failure(ManualLendingViolation.LendingCounterpartyNotFound)
    if (receivable.ledgerId != command.ledgerId ||
        receivable.kind != AccountKind.ASSET ||
        receivable.ownedByUser ||
        receivable.realAccount ||
        receivable.currency != command.amount.currency
    ) {
        return DomainResult.Failure(ManualLendingViolation.LendingCounterpartyNotFound)
    }
    latestOccurredAt(position)?.let { latest ->
        if (command.times.occurredAt < latest) {
            return DomainResult.Failure(ManualLendingViolation.LendingBackdatedNotAllowed)
        }
    }
    val newBalance =
        checkedAdd(position.principalBalanceMinor, command.amount.minorUnits)
            ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
    val entry =
        LendingPositionHistoryEntry(
            id = ids.entryId,
            behaviorCode = LendingBehaviorCode.LEND,
            amountMinor = command.amount.minorUnits,
            principalBalanceAfterMinor = newBalance,
            transactionId = ids.transactionId,
            occurredAt = command.times.occurredAt,
        )
    val rebuilt =
        when (
            val result =
                createLendingPosition(
                    id = position.id,
                    counterpartyId = position.counterpartyId,
                    receivableAccountId = command.receivableAccountId,
                    currency = position.currency,
                    principalBalanceMinor = newBalance,
                    history = position.history + entry,
                )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return DomainResult.Failure(result.violation)
        }
    val negative =
        checkedNegate(command.amount.minorUnits)
            ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
    val postingSet =
        when (
            val result =
                PostingSet.create(
                    ids.postingSetId,
                    listOf(
                        Posting(ids.receivablePostingId, command.receivableAccountId, command.amount),
                        Posting(
                            ids.fundingPostingId,
                            command.fundingAccountId,
                            Money.ofMinor(negative, command.amount.currency),
                        ),
                    ),
                )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return result
        }
    val transaction =
        Transaction(
            id = ids.transactionId,
            ledgerId = command.ledgerId,
            kind = TransactionKind.LEND,
            currentVersionId = ids.versionId,
        )
    val version =
        TransactionVersion(
            id = ids.versionId,
            transactionId = ids.transactionId,
            versionNumber = 1,
            postingSetId = ids.postingSetId,
            times = command.times,
            note = command.note,
        )
    val formal =
        when (val created = FormalTransaction.create(transaction, listOf(version), listOf(postingSet))) {
            is DomainResult.Success -> created.value
            is DomainResult.Failure -> return created
        }
    return DomainResult.Success(
        ManualLend(
            formalTransaction = formal,
            position = rebuilt,
            reportEffects =
                ManualLendingReportEffects(
                    cashInflowMinor = 0L,
                    cashOutflowMinor = 0L,
                    ordinaryIncomeMinor = 0L,
                    ordinaryExpenseMinor = 0L,
                    consumptionMinor = 0L,
                    principalCashFlowMinor = -command.amount.minorUnits,
                    internalTransferMinor = 0L,
                    netWorthChangeMinor = 0L,
                    feeMinor = 0L,
                ),
        ),
    )
}

/**
 * L-2/L-3/L-4: appends one COLLECT event to [position] and builds the three-leg COLLECT
 * transaction (destination `+totalReceived`, receivable `-principal`, interest income
 * `-interest`). Fee is fixed to `0.00` and produces no posting. The reused
 * [createLendingSettlement] remains the authoritative composition/interest-category validator.
 */
fun createManualCollect(
    catalog: LedgerCatalog,
    position: LendingPosition,
    command: ManualCollectCommand,
    ids: ManualCollectIds,
): DomainResult<ManualCollect> {
    if (position.counterpartyId != command.counterpartyId.value) {
        return DomainResult.Failure(ManualLendingViolation.LendingCounterpartyNotFound)
    }
    if (command.totalReceived.currency != position.currency ||
        command.principal.currency != position.currency ||
        command.interest.currency != position.currency ||
        command.fee.currency != position.currency
    ) {
        return DomainResult.Failure(ManualLendingViolation.LendingAccountNotEligible)
    }
    if (command.fee.minorUnits != 0L) {
        return DomainResult.Failure(ManualLendingViolation.LendingFeeMustBeZero)
    }
    if (command.totalReceived.minorUnits <= 0L) {
        return DomainResult.Failure(ManualLendingViolation.LendingTotalMustBePositive)
    }
    if (command.principal.minorUnits < 0L || command.interest.minorUnits < 0L) {
        return DomainResult.Failure(ManualLendingViolation.LendingComponentsMismatch)
    }
    val composed =
        checkedAdd(command.principal.minorUnits, command.interest.minorUnits)
            ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
    if (composed != command.totalReceived.minorUnits) {
        return DomainResult.Failure(ManualLendingViolation.LendingComponentsMismatch)
    }
    if (command.principal.minorUnits > position.principalBalanceMinor) {
        return DomainResult.Failure(ManualLendingViolation.LendingPrincipalExceedsBalance)
    }
    val destionation =
        catalog.account(command.destinationAccountId)
            ?: return DomainResult.Failure(ManualLendingViolation.LendingAccountNotEligible)
    if (destionation.ledgerId != command.ledgerId ||
        destionation.kind != AccountKind.ASSET ||
        !destionation.ownedByUser ||
        !destionation.realAccount ||
        !destionation.active ||
        destionation.currency != command.totalReceived.currency
    ) {
        return DomainResult.Failure(ManualLendingViolation.LendingAccountNotEligible)
    }
    val receivable =
        catalog.account(command.receivableAccountId)
            ?: return DomainResult.Failure(ManualLendingViolation.LendingCounterpartyNotFound)
    if (receivable.ledgerId != command.ledgerId ||
        receivable.kind != AccountKind.ASSET ||
        receivable.ownedByUser ||
        receivable.realAccount ||
        receivable.currency != command.totalReceived.currency
    ) {
        return DomainResult.Failure(ManualLendingViolation.LendingCounterpartyNotFound)
    }
    val interestCategory =
        catalog.category(command.interestCategoryId)
            ?: return DomainResult.Failure(ManualLendingViolation.LendingInterestCategoryRequired)
    val interestAccount =
        interestCategory.postingAccountId?.let { catalog.account(it) }
            ?: return DomainResult.Failure(ManualLendingViolation.LendingInterestCategoryRequired)
    if (interestCategory.ledgerId != command.ledgerId ||
        interestCategory.kind != CategoryKind.INCOME ||
        !interestCategory.active ||
        interestCategory.parentId == null ||
        interestAccount.kind != AccountKind.INCOME ||
        interestAccount.currency != command.totalReceived.currency
    ) {
        return DomainResult.Failure(ManualLendingViolation.LendingInterestCategoryRequired)
    }
    latestOccurredAt(position)?.let { latest ->
        if (command.times.occurredAt < latest) {
            return DomainResult.Failure(ManualLendingViolation.LendingBackdatedNotAllowed)
        }
    }
    // Reuse the frozen settlement validator as the authoritative composition/interest guard.
    val settlement =
        when (
            val result =
                createLendingSettlement(
                    id = "settlement-${ids.transactionId.value}",
                    catalog = catalog,
                    position = position,
                    transactionId = ids.transactionId,
                    destinationAccountId = command.destinationAccountId,
                    interestCategoryId = command.interestCategoryId,
                    totalReceivedMinor = command.totalReceived.minorUnits,
                    currency = command.totalReceived.currency,
                    actualReceiptAt = command.times.occurredAt,
                    confirmedAt = command.times.occurredAt,
                    components =
                        listOf(
                            LendingSettlementComponent(
                                id = "principal-${ids.transactionId.value}",
                                kind = LendingComponentKind.PRINCIPAL,
                                amountMinor = command.principal.minorUnits,
                                postingId = ids.principalPostingId,
                            ),
                            LendingSettlementComponent(
                                id = "interest-${ids.transactionId.value}",
                                kind = LendingComponentKind.INTEREST,
                                amountMinor = command.interest.minorUnits,
                                postingId = ids.interestPostingId,
                            ),
                            LendingSettlementComponent(
                                id = "fee-${ids.transactionId.value}",
                                kind = LendingComponentKind.FEE,
                                amountMinor = 0L,
                                postingId = null,
                            ),
                        ),
                    history =
                        listOf(
                            LendingSettlementHistoryEntry(
                                id = "settlement-history-${ids.transactionId.value}",
                                status = LendingSettlementStatus.CONFIRMED,
                                occurredAt = command.times.occurredAt,
                                transactionId = ids.transactionId,
                                formalEffectCount = 1,
                            ),
                        ),
                )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return DomainResult.Failure(result.violation)
        }
    check(settlement.counterpartyId == position.counterpartyId)
    val newBalance =
        checkedAdd(position.principalBalanceMinor, -command.principal.minorUnits)
            ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
    if (newBalance < 0L) {
        return DomainResult.Failure(ManualLendingViolation.LendingPrincipalExceedsBalance)
    }
    val entry =
        LendingPositionHistoryEntry(
            id = ids.entryId,
            behaviorCode = LendingBehaviorCode.COLLECT,
            amountMinor = -command.principal.minorUnits,
            principalBalanceAfterMinor = newBalance,
            transactionId = ids.transactionId,
            occurredAt = command.times.occurredAt,
        )
    val rebuilt =
        when (
            val result =
                createLendingPosition(
                    id = position.id,
                    counterpartyId = position.counterpartyId,
                    receivableAccountId = command.receivableAccountId,
                    currency = position.currency,
                    principalBalanceMinor = newBalance,
                    history = position.history + entry,
                )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return DomainResult.Failure(result.violation)
        }
    val negativePrincipal =
        checkedNegate(command.principal.minorUnits)
            ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
    val negativeInterest =
        checkedNegate(command.interest.minorUnits)
            ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
    val postingSet =
        when (
            val result =
                PostingSet.create(
                    ids.postingSetId,
                    listOf(
                        Posting(ids.destinationPostingId, command.destinationAccountId, command.totalReceived),
                        Posting(
                            ids.principalPostingId,
                            command.receivableAccountId,
                            Money.ofMinor(negativePrincipal, command.totalReceived.currency),
                        ),
                        Posting(
                            ids.interestPostingId,
                            interestAccount.id,
                            Money.ofMinor(negativeInterest, command.totalReceived.currency),
                        ),
                    ),
                )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return result
        }
    val transaction =
        Transaction(
            id = ids.transactionId,
            ledgerId = command.ledgerId,
            kind = TransactionKind.COLLECT,
            currentVersionId = ids.versionId,
        )
    val version =
        TransactionVersion(
            id = ids.versionId,
            transactionId = ids.transactionId,
            versionNumber = 1,
            postingSetId = ids.postingSetId,
            times = command.times,
            note = command.note,
        )
    val formal =
        when (val created = FormalTransaction.create(transaction, listOf(version), listOf(postingSet))) {
            is DomainResult.Success -> created.value
            is DomainResult.Failure -> return created
        }
    return DomainResult.Success(
        ManualCollect(
            formalTransaction = formal,
            position = rebuilt,
            reportEffects =
                ManualLendingReportEffects(
                    cashInflowMinor = command.totalReceived.minorUnits,
                    cashOutflowMinor = 0L,
                    ordinaryIncomeMinor = command.interest.minorUnits,
                    ordinaryExpenseMinor = 0L,
                    consumptionMinor = 0L,
                    principalCashFlowMinor = command.principal.minorUnits,
                    internalTransferMinor = 0L,
                    netWorthChangeMinor = command.interest.minorUnits,
                    feeMinor = 0L,
                ),
        ),
    )
}

private fun latestOccurredAt(position: LendingPosition): Instant? = position.history.maxOfOrNull { it.occurredAt }
