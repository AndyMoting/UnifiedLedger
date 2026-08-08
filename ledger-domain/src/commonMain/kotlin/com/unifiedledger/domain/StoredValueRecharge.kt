package com.unifiedledger.domain

data class StoredValueRechargeCommand(
    val ledgerId: LedgerId,
    val storedValueAccountId: AccountId,
    val paymentAccountId: AccountId,
    val paidAmount: Money,
    val creditedAmount: Money,
    val bonusAmount: Money,
    val times: TransactionTimes,
)

data class StoredValueRechargeIds(
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val storedValuePostingId: PostingId,
    val paymentPostingId: PostingId,
    val bonusIncomePostingId: PostingId,
)

enum class StoredValueRechargePostingRole {
    STORED_VALUE_CREDIT,
    PAYMENT_OUT,
    BONUS_INCOME,
}

data class StoredValueRechargePosting(
    val posting: Posting,
    val role: StoredValueRechargePostingRole,
)

data class StoredValueRechargeReportEffects(
    val cashOutflowMinor: Long,
    val specialNonCashBonusIncomeMinor: Long,
    val netWorthChangeMinor: Long,
)

data class StoredValueRecharge(
    val formalTransaction: FormalTransaction,
    val postings: List<StoredValueRechargePosting>,
    val reportEffects: StoredValueRechargeReportEffects,
)

/**
 * D-064/D-083 stored-value recharge factory. The stored-value asset is credited with the
 * full merchant face value (paid + bonus); the payment bank asset leaves by the paid amount;
 * the paid/bonus difference enters the dedicated special non-cash bonus rights income account.
 */
fun createStoredValueRecharge(
    catalog: LedgerCatalog,
    command: StoredValueRechargeCommand,
    ids: StoredValueRechargeIds,
): DomainResult<StoredValueRecharge> {
    val stored = catalog.account(command.storedValueAccountId)
        ?: return DomainResult.Failure(
            StoredValueViolation.KnownAccountRequired(StoredValueField.STORED_VALUE_ACCOUNT),
        )
    val payment = catalog.account(command.paymentAccountId)
        ?: return DomainResult.Failure(
            StoredValueViolation.KnownAccountRequired(StoredValueField.PAYMENT_ACCOUNT),
        )
    if (stored.ledgerId != command.ledgerId || payment.ledgerId != command.ledgerId) {
        return DomainResult.Failure(DomainViolation.InvalidCatalog)
    }
    val storedConfig = stored.storedValue
    if (
        stored.kind != AccountKind.ASSET ||
        !stored.ownedByUser ||
        !stored.realAccount ||
        storedConfig == null ||
        !storedConfig.enabled ||
        !storedConfig.merchantRestricted
    ) {
        return DomainResult.Failure(
            StoredValueViolation.EnabledRestrictedStoredValueAssetRequired(StoredValueField.STORED_VALUE_ACCOUNT),
        )
    }
    if (payment.kind != AccountKind.ASSET || !payment.ownedByUser || !payment.realAccount) {
        return DomainResult.Failure(
            StoredValueViolation.OwnedPaymentAssetRequired(StoredValueField.PAYMENT_ACCOUNT),
        )
    }
    if (command.paidAmount.minorUnits <= 0L) {
        return DomainResult.Failure(StoredValueViolation.PaidAmountMustBePositive)
    }
    if (command.creditedAmount.minorUnits <= 0L) {
        return DomainResult.Failure(StoredValueViolation.CreditedAmountMustBePositive)
    }
    if (command.bonusAmount.minorUnits < 0L) {
        return DomainResult.Failure(StoredValueViolation.BonusAmountMustBeZeroOrPositive)
    }
    val composed = checkedAdd(command.paidAmount.minorUnits, command.bonusAmount.minorUnits)
        ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
    if (composed != command.creditedAmount.minorUnits) {
        return DomainResult.Failure(StoredValueViolation.CreditedMustEqualPaidPlusBonus)
    }
    if (
        stored.currency != command.paidAmount.currency ||
        payment.currency != command.paidAmount.currency ||
        command.paidAmount.currency != command.creditedAmount.currency ||
        command.creditedAmount.currency != command.bonusAmount.currency
    ) {
        return DomainResult.Failure(StoredValueViolation.SameCurrencyRequired)
    }
    val bonusIncome = catalog.accounts.firstOrNull { it.systemRole == STORED_VALUE_BONUS_RIGHT_INCOME_ROLE }
        ?: return DomainResult.Failure(StoredValueViolation.BonusIncomeAccountRequired)
    if (
        bonusIncome.kind != AccountKind.INCOME ||
        bonusIncome.realAccount ||
        bonusIncome.ownedByUser ||
        bonusIncome.currency != command.paidAmount.currency
    ) {
        return DomainResult.Failure(StoredValueViolation.BonusIncomeAccountRequired)
    }
    val paymentAmount = checkedNegate(command.paidAmount.minorUnits)
        ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
    val bonusAmount = checkedNegate(command.bonusAmount.minorUnits)
        ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
    val typedPostings = listOf(
        StoredValueRechargePosting(
            Posting(ids.storedValuePostingId, stored.id, command.creditedAmount),
            StoredValueRechargePostingRole.STORED_VALUE_CREDIT,
        ),
        StoredValueRechargePosting(
            Posting(ids.paymentPostingId, payment.id, Money.ofMinor(paymentAmount, command.paidAmount.currency)),
            StoredValueRechargePostingRole.PAYMENT_OUT,
        ),
        StoredValueRechargePosting(
            Posting(ids.bonusIncomePostingId, bonusIncome.id, Money.ofMinor(bonusAmount, command.bonusAmount.currency)),
            StoredValueRechargePostingRole.BONUS_INCOME,
        ),
    )
    val postingSet = when (
        val created = PostingSet.create(ids.postingSetId, typedPostings.map(StoredValueRechargePosting::posting))
    ) {
        is DomainResult.Success -> created.value
        is DomainResult.Failure -> return created
    }
    val transaction = Transaction(
        id = ids.transactionId,
        ledgerId = command.ledgerId,
        kind = TransactionKind.STORED_VALUE_RECHARGE,
        currentVersionId = ids.versionId,
    )
    val version = TransactionVersion(
        id = ids.versionId,
        transactionId = ids.transactionId,
        versionNumber = 1,
        postingSetId = ids.postingSetId,
        times = command.times,
    )
    val formal = when (
        val created = FormalTransaction.create(transaction, listOf(version), listOf(postingSet))
    ) {
        is DomainResult.Success -> created.value
        is DomainResult.Failure -> return created
    }
    return DomainResult.Success(
        StoredValueRecharge(
            formalTransaction = formal,
            postings = typedPostings,
            reportEffects = StoredValueRechargeReportEffects(
                cashOutflowMinor = command.paidAmount.minorUnits,
                specialNonCashBonusIncomeMinor = command.bonusAmount.minorUnits,
                netWorthChangeMinor = command.bonusAmount.minorUnits,
            ),
        ),
    )
}
