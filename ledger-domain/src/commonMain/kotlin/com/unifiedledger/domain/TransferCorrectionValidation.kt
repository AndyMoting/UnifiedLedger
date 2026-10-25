package com.unifiedledger.domain

import kotlin.time.Instant

/**
 * P7-05 transfer-slice correction pure validation (spec sections 3/8.1; D-228 J1). One transfer
 * correction request carries the complete target state of the new version - the two endpoint
 * accounts, the destination credit, the fee and the optional fee category - and this planner
 * derives the replacement posting set against the *current* authoritative catalog.
 *
 * Admission reuse (hard constraint of the spec section 8.1): the planner delegates every
 * admission decision to the creation factories themselves - [createOwnAssetAccountTransfer] for
 * the fee-bearing three-leg target and [createOwnAssetPrincipalTransfer] for the fee-less
 * two-leg target, the exact dispatch `ManualTransferTransactionFactory` uses - so a correction
 * admits exactly what a creation would have admitted at the same catalog state and no second
 * copy of the endpoint or fee-category admission rules exists here. Only the factories' typed
 * posting lists are consumed; their transaction/version outputs are discarded placeholders
 * ([TransferCorrectionCommand.statisticsAt] feeds only the discarded version), because the
 * product correction port appends the version through its own CAS-guarded SQL copy.
 *
 * Leg-count changes (spec section 3 write form) never touch `PostingFactsCorrection`'s
 * equal-count replacement branches: the two-leg/three-leg shape is decided by the *target*
 * state's fee, so a `fee > 0 -> fee == 0` correction drops the FEE leg and a `fee == 0 ->
 * fee > 0` correction adds one carrying the fee category, per-currency balanced by
 * construction (the factories build balanced sets).
 *
 * Affected funding legs (spec sections 3/8.1): the frozen [affectedFundingLegs] derivation
 * stays the single definition - for a transfer the real-account legs are the two principal
 * legs; the FEE posting-account leg is not a real account and never participates. This wiring
 * batch does not compose that derivation into any invalidation.
 *
 * Slice registration (this wiring batch): transfer restore revalidation stays on the
 * `OrdinaryVersionRevalidation` EXPENSE/INCOME hardcode (slice 3 owns the transfer-aware
 * replacement), and the DP-10 placeholder narrowing plus the D-113 combination are slice 3
 * and stay untouched. This batch wires and tests neither.
 *
 * The rejection vocabulary is the frozen [P705FailureCode] family (spec section 9); no new
 * token is introduced.
 */
data class TransferCorrectionCommand(
    val ledgerId: LedgerId,
    val sourceAccountId: AccountId,
    val destinationAccountId: AccountId,
    val destinationCredit: Money,
    val fee: Money,
    /** `null` exactly when the fee is zero; a positive fee requires a fee category (T-4). */
    val feeCategoryId: CategoryId?,
    /** Placeholder for the reused factories' discarded version output; never persisted. */
    val statisticsAt: Instant,
)

/**
 * Fresh posting ids of the replacement legs. [feePostingId] is nullable because the fee-less
 * two-leg form allocates no fee leg; a positive fee without a fee-leg id is a fail-closed
 * wiring-invariant rejection, not a user error.
 */
data class TransferCorrectionPostingIds(
    val sourcePostingId: PostingId,
    val destinationPostingId: PostingId,
    val feePostingId: PostingId?,
)

sealed interface TransferCorrectionPlan {
    /** The validated replacement posting set, in the creation factories' frozen leg order. */
    data class Postings(
        val postings: List<Posting>,
    ) : TransferCorrectionPlan

    data class Rejected(
        val code: P705FailureCode,
    ) : TransferCorrectionPlan
}

/**
 * Derives and validates the replacement posting set of one transfer correction against the
 * current authoritative catalog. The returned list is exactly the posting list the new version
 * binds: `PRINCIPAL_OUT`, `PRINCIPAL_IN` and - only when the target fee is positive - a `FEE`
 * leg on the fee category's posting account carrying the fee category.
 */
fun planTransferCorrectionPostings(
    catalog: LedgerCatalog,
    command: TransferCorrectionCommand,
    ids: TransferCorrectionPostingIds,
): TransferCorrectionPlan {
    if (command.fee.minorUnits < 0L) {
        // The creation factory's typed rule: a negative fee is an inadmissible field value.
        return TransferCorrectionPlan.Rejected(P705FailureCode.P705_FIELD_NOT_SUPPORTED)
    }
    if (command.fee.minorUnits == 0L && command.feeCategoryId != null) {
        // T-4 (the creation factory's zero-fee rule): a fee-less target must not carry a category.
        return TransferCorrectionPlan.Rejected(P705FailureCode.P705_FIELD_NOT_SUPPORTED)
    }
    if (command.fee.minorUnits > 0L && command.feeCategoryId == null) {
        return TransferCorrectionPlan.Rejected(P705FailureCode.P705_FIELD_NOT_SUPPORTED)
    }
    if (command.fee.currency != command.destinationCredit.currency) {
        // The stored shape carries one currency triple; a fee in another currency is an
        // inconsistent target state, not a catalog reference question.
        return TransferCorrectionPlan.Rejected(P705FailureCode.P705_FIELD_NOT_SUPPORTED)
    }
    if (command.fee.minorUnits > 0L && ids.feePostingId == null) {
        // Fail-closed wiring invariant: the fee leg needs a fresh id, and an id source that
        // predates the transfer slice mints none. Typed rejection, never a two-legged mis-shape.
        return TransferCorrectionPlan.Rejected(P705FailureCode.P705_CONSTRAINT_VIOLATION)
    }
    // DP-T3 (D-228 J1): the source debit is derived as `destinationCredit + fee` with the
    // shared checked arithmetic, never accepted independently; an unrepresentable sum is an
    // inadmissible field value.
    val sourceDebitMinor =
        checkedAdd(command.destinationCredit.minorUnits, command.fee.minorUnits)
            ?: return TransferCorrectionPlan.Rejected(P705FailureCode.P705_FIELD_NOT_SUPPORTED)
    val sourceDebit = Money.ofMinor(sourceDebitMinor, command.destinationCredit.currency)

    return if (command.fee.minorUnits > 0L) {
        when (
            val created =
                createOwnAssetAccountTransfer(
                    catalog = catalog,
                    command =
                        OwnAssetAccountTransferCommand(
                            ledgerId = command.ledgerId,
                            sourceAccountId = command.sourceAccountId,
                            destinationAccountId = command.destinationAccountId,
                            sourceDebit = sourceDebit,
                            destinationCredit = command.destinationCredit,
                            fee = command.fee,
                            feeCategoryId = checkNotNull(command.feeCategoryId),
                            times = TransactionTimes.collapsed(command.statisticsAt),
                        ),
                    ids =
                        AccountTransferIds(
                            transactionId = DISCARDED_FACTORY_ID,
                            versionId = TransactionVersionId(DISCARDED_FACTORY_ID.value),
                            postingSetId = PostingSetId(DISCARDED_FACTORY_ID.value),
                            sourcePostingId = ids.sourcePostingId,
                            destinationPostingId = ids.destinationPostingId,
                            feePostingId = checkNotNull(ids.feePostingId),
                        ),
                )
        ) {
            is DomainResult.Success ->
                TransferCorrectionPlan.Postings(created.value.postings.map(AccountTransferPosting::posting))
            is DomainResult.Failure ->
                TransferCorrectionPlan.Rejected(transferCorrectionRejection(created.violation))
        }
    } else {
        when (
            val created =
                createOwnAssetPrincipalTransfer(
                    catalog = catalog,
                    command =
                        OwnAssetPrincipalTransferCommand(
                            ledgerId = command.ledgerId,
                            sourceAccountId = command.sourceAccountId,
                            destinationAccountId = command.destinationAccountId,
                            amount = command.destinationCredit,
                            times = TransactionTimes.collapsed(command.statisticsAt),
                        ),
                    ids =
                        OwnAssetPrincipalTransferIds(
                            transactionId = DISCARDED_FACTORY_ID,
                            versionId = TransactionVersionId(DISCARDED_FACTORY_ID.value),
                            postingSetId = PostingSetId(DISCARDED_FACTORY_ID.value),
                            sourcePostingId = ids.sourcePostingId,
                            destinationPostingId = ids.destinationPostingId,
                        ),
                )
        ) {
            is DomainResult.Success ->
                TransferCorrectionPlan.Postings(created.value.postings.map(PrincipalTransferPosting::posting))
            is DomainResult.Failure ->
                TransferCorrectionPlan.Rejected(transferCorrectionRejection(created.violation))
        }
    }
}

/** The discarded factory-output identity; never persisted, never compared. */
private val DISCARDED_FACTORY_ID = TransactionId("transfer-correction-factory-placeholder")

/**
 * Maps a reused factory violation onto the frozen P7-05 family (spec section 9): endpoint and
 * fee-category admission failures are catalog-reference rejections (T-04/T-05), the amount,
 * fee and balance violations are field rejections (T-01), and anything else is the constraint
 * backstop. No new token.
 */
private fun transferCorrectionRejection(violation: DomainViolation): P705FailureCode =
    when (violation) {
        is AccountTransferViolation.KnownAccountRequired,
        AccountTransferViolation.DistinctAccountsRequired,
        is AccountTransferViolation.OwnAccountRequired,
        is AccountTransferViolation.RealFinancialAccountRequired,
        is AccountTransferViolation.AssetAccountRequired,
        AccountTransferViolation.SameCurrencyRequired,
        AccountTransferViolation.InvalidFeeCategory,
        is PrincipalTransferViolation.KnownAccountRequired,
        PrincipalTransferViolation.DistinctAccountsRequired,
        is PrincipalTransferViolation.OwnedRealAssetRequired,
        PrincipalTransferViolation.SameCurrencyRequired,
        DomainViolation.InvalidCatalog,
        -> P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE

        is AccountTransferViolation.AmountMustBePositive,
        PrincipalTransferViolation.AmountMustBePositive,
        AccountTransferViolation.FeeMustNotBeNegative,
        AccountTransferViolation.AmountsMustBalance,
        DomainViolation.ArithmeticOverflow,
        -> P705FailureCode.P705_FIELD_NOT_SUPPORTED

        else -> P705FailureCode.P705_CONSTRAINT_VIOLATION
    }
