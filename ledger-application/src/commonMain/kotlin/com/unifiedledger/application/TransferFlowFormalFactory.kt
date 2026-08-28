package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.OwnAssetPrincipalTransferCommand
import com.unifiedledger.domain.OwnAssetPrincipalTransferIds
import com.unifiedledger.domain.PrincipalTransferField
import com.unifiedledger.domain.PrincipalTransferViolation
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.createOwnAssetPrincipalTransfer
import kotlin.time.Instant

/**
 * Production factory for transfer formalization (P4-04 wallet perspective).
 *
 * Creates an [ImportFormalCommit] from a [ImportCandidateFormalizationInput] by:
 * 1. Validating the direction gate (wallet leg must match direction)
 * 2. Validating both accounts are self-owned real assets of the same ledger
 * 3. Normalizing source scale to target currency precision
 * 4. Calling [createOwnAssetPrincipalTransfer] with the normalized amount
 *
 * The wallet-perspective direction gate is the frozen P4-04 semantic (D-100 section 7):
 * "out" => wallet is the FROM leg; "in" => wallet is the TO leg. This factory is
 * untouched by BP-01's bank-side direction-gate variant ([BankStatementTransferFlowFormalFactory],
 * spec section 3.4); both share the same [createTransferFormalCommit] body with a
 * different observed account.
 *
 * @param catalog the ledger catalog containing accounts and categories
 * @param walletAccountId the wallet account ID (used for direction gate validation)
 */
class TransferFlowFormalFactory(
    val catalog: LedgerCatalog,
    private val walletAccountId: AccountId,
) : ImportCandidateFormalFactory {
    override fun create(
        input: ImportCandidateFormalizationInput,
        ids: ImportCommitIds,
    ): DomainResult<ImportFormalCommit> =
        createTransferFormalCommit(
            input = input,
            ids = ids,
            catalog = catalog,
            observedAccountId = walletAccountId,
        )
}

/**
 * BP-01 bank-side direction-gate variant (spec section 3.4, registered independently
 * of the P4-04 wallet perspective).
 *
 * Identical formal semantics to [TransferFlowFormalFactory]: the observed account is
 * the BANK leg. The bank perspective is the mirror view of the same self-owned
 * wallet<->bank flow: direction "out" (money leaves the bank) => bank is the FROM leg;
 * direction "in" (money enters the bank) => bank is the TO leg. The gate predicate is
 * exactly the P4-04 predicate applied to a different observed account; it never
 * changes the wallet-perspective behavior.
 *
 * @param catalog the ledger catalog containing accounts and categories
 * @param bankAccountId the bank account ID (used for direction gate validation)
 */
class BankStatementTransferFlowFormalFactory(
    val catalog: LedgerCatalog,
    private val bankAccountId: AccountId,
) : ImportCandidateFormalFactory {
    override fun create(
        input: ImportCandidateFormalizationInput,
        ids: ImportCommitIds,
    ): DomainResult<ImportFormalCommit> =
        createTransferFormalCommit(
            input = input,
            ids = ids,
            catalog = catalog,
            observedAccountId = bankAccountId,
        )
}

internal fun createTransferFormalCommit(
    input: ImportCandidateFormalizationInput,
    ids: ImportCommitIds,
    catalog: LedgerCatalog,
    observedAccountId: AccountId,
): DomainResult<ImportFormalCommit> {
    val decisionFields = input.decisionFields
    if (decisionFields !is ImportConfirmDecisionFields.TransferFlow) {
        // Compatibility note: using InvalidOrdinaryIncome for the direction gate failure
        // per spec §11.7. This is a technical debt item - the next batch that allows
        // extending ledger-domain must migrate to a dedicated violation.
        return DomainResult.Failure(DomainViolation.InvalidOrdinaryIncome)
    }

    // Direction gate: the observed leg must match direction.
    // "out" => observed account is the FROM leg; "in" => observed account is the TO leg.
    val directionOk =
        when (input.resolved.directionToken) {
            "out" -> decisionFields.fromAccountId == observedAccountId
            "in" -> decisionFields.toAccountId == observedAccountId
            else -> false
        }
    if (!directionOk) {
        // Compatibility note: using InvalidOrdinaryIncome per spec §11.7
        return DomainResult.Failure(DomainViolation.InvalidOrdinaryIncome)
    }
    if (ids.formalIds.postingIds.size != 2) {
        return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
    }

    // Validate both accounts belong to the same ledger
    val fromAccount =
        catalog.accounts.firstOrNull { it.id == decisionFields.fromAccountId }
            ?: return DomainResult.Failure(
                PrincipalTransferViolation.KnownAccountRequired(PrincipalTransferField.SOURCE_ACCOUNT),
            )
    val toAccount =
        catalog.accounts.firstOrNull { it.id == decisionFields.toAccountId }
            ?: return DomainResult.Failure(
                PrincipalTransferViolation.KnownAccountRequired(PrincipalTransferField.DESTINATION_ACCOUNT),
            )

    if (fromAccount.ledgerId != input.ledgerId || toAccount.ledgerId != input.ledgerId) {
        return DomainResult.Failure(DomainViolation.InvalidCatalog)
    }

    // Both must be self-owned real asset accounts
    if (fromAccount.kind != AccountKind.ASSET || toAccount.kind != AccountKind.ASSET) {
        return DomainResult.Failure(
            PrincipalTransferViolation.OwnedRealAssetRequired(PrincipalTransferField.SOURCE_ACCOUNT),
        )
    }
    if (!fromAccount.ownedByUser ||
        !toAccount.ownedByUser ||
        !fromAccount.realAccount ||
        !toAccount.realAccount
    ) {
        return DomainResult.Failure(
            PrincipalTransferViolation.OwnedRealAssetRequired(PrincipalTransferField.SOURCE_ACCOUNT),
        )
    }

    // Distinct accounts
    if (decisionFields.fromAccountId == decisionFields.toAccountId) {
        return DomainResult.Failure(PrincipalTransferViolation.DistinctAccountsRequired)
    }

    // Same currency
    if (fromAccount.currency != toAccount.currency) {
        return DomainResult.Failure(PrincipalTransferViolation.SameCurrencyRequired)
    }

    // Normalize source amount to the explicit account currency. This is the only
    // point at which source precision may be changed; the source facts remain raw.
    val targetCurrency = fromAccount.currency
    val normalizedMoney =
        when (val result = normalizeImportAmountExact(input.resolved, targetCurrency)) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return DomainResult.Failure(result.violation)
        }

    // Create the principal transfer
    val occurredAt =
        when (val parsed = parseImportOccurredAt(input.resolved.occurredAt)) {
            is DomainResult.Success -> parsed.value
            is DomainResult.Failure -> return DomainResult.Failure(parsed.violation)
        }
    val command =
        OwnAssetPrincipalTransferCommand(
            ledgerId = input.ledgerId,
            sourceAccountId = decisionFields.fromAccountId,
            destinationAccountId = decisionFields.toAccountId,
            amount = normalizedMoney,
            times = TransactionTimes.collapsed(occurredAt),
        )
    val transferIds =
        OwnAssetPrincipalTransferIds(
            transactionId = ids.formalIds.transactionId,
            versionId = ids.formalIds.versionId,
            postingSetId = ids.formalIds.postingSetId,
            sourcePostingId = ids.formalIds.postingIds[0],
            destinationPostingId = ids.formalIds.postingIds[1],
        )

    return when (val result = createOwnAssetPrincipalTransfer(catalog, command, transferIds)) {
        is DomainResult.Success -> {
            val ft = result.value.formalTransaction
            checkedImportFormalCommit(
                input,
                ids,
                ImportFormalCommit(
                    confirmationId = ids.confirmationId,
                    statusHistoryId = ids.statusHistoryId,
                    transaction = ft,
                ),
                catalog,
            )
        }
        is DomainResult.Failure -> DomainResult.Failure(result.violation)
    }
}

/**
 * Fail-closed binding check for every import confirmation flow. The domain factory is
 * allowed to construct a richer graph, but the graph persisted by the spine must remain
 * tied to the request's immutable source facts, decision fields, and allocated IDs.
 */
fun validateImportFormalBinding(
    input: ImportCandidateFormalizationInput,
    allocatedIds: ImportCommitIds,
    created: ImportFormalCommit,
): DomainResult<Unit> {
    fun invalid(): DomainResult<Unit> = DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
    val tx = created.transaction
    val ids = allocatedIds.formalIds
    val expectedCount = if (input.decisionFields is ImportConfirmDecisionFields.MixedPaymentFlow) 3 else 2
    if (created.confirmationId != allocatedIds.confirmationId ||
        created.statusHistoryId != allocatedIds.statusHistoryId ||
        ids.postingIds.size != expectedCount ||
        tx.transaction.id != ids.transactionId ||
        tx.transaction.ledgerId != input.ledgerId ||
        tx.transaction.currentVersionId != ids.versionId ||
        tx.versions.size != 1 ||
        tx.postingSets.size != 1
    ) {
        return invalid()
    }

    val version = tx.versions.single()
    val postingSet = tx.postingSets.single()
    if (version.id != ids.versionId ||
        version.transactionId != tx.transaction.id ||
        version.versionNumber != 1 ||
        version.postingSetId != ids.postingSetId ||
        postingSet.id != ids.postingSetId ||
        postingSet.postings.size != expectedCount
    ) {
        return invalid()
    }
    val sourceInstant = runCatching { Instant.parse(input.resolved.occurredAt) }.getOrNull() ?: return invalid()
    if (version.times != TransactionTimes.collapsed(sourceInstant)) return invalid()
    if (postingSet.postings.indices.any { postingSet.postings[it].id != ids.postingIds[it] }) return invalid()

    val postings = postingSet.postings
    val currencies = postings.map { it.amount.currency }.distinct()
    if (currencies.size != 1) return invalid()
    val currency = currencies.single()
    if (currency.code != input.resolved.currencyCode) return invalid()
    val normalized =
        when (
            val result =
                normalizeSourceMinorExact(
                    input.resolved.amountMinor,
                    input.resolved.currencyPrecision,
                    currency.precision,
                )
        ) {
            is ExactAmountNormalization.Success -> result.amountMinor
            ExactAmountNormalization.NotRepresentable,
            ExactAmountNormalization.ArithmeticOverflow,
            -> return invalid()
        }
    if (normalized <= 0L) return invalid()

    fun matches(
        index: Int,
        accountId: AccountId,
        amount: Long,
    ): Boolean {
        val posting = postings[index]
        return posting.accountId == accountId && posting.amount.currency == currency && posting.amount.minorUnits == amount
    }

    when (val fields = input.decisionFields) {
        is ImportConfirmDecisionFields.OrdinaryFlow ->
            when (input.resolved.directionToken) {
                "out" ->
                    if (tx.transaction.kind != TransactionKind.EXPENSE ||
                        version.note != "" ||
                        !matches(1, fields.fundingAccountId, -normalized) ||
                        postings[0].accountId == fields.fundingAccountId ||
                        postings[0].amount.minorUnits != normalized
                    ) {
                        return invalid()
                    }
                "in" ->
                    if (tx.transaction.kind != TransactionKind.INCOME ||
                        version.note != "" ||
                        !matches(0, fields.fundingAccountId, normalized) ||
                        postings[1].accountId == fields.fundingAccountId ||
                        postings[1].amount.minorUnits != -normalized
                    ) {
                        return invalid()
                    }
                else -> return invalid()
            }
        is ImportConfirmDecisionFields.TransferFlow ->
            if (
                tx.transaction.kind != TransactionKind.ACCOUNT_TRANSFER ||
                version.note != null ||
                fields.fromAccountId == fields.toAccountId ||
                !matches(0, fields.fromAccountId, -normalized) ||
                !matches(1, fields.toAccountId, normalized)
            ) {
                return invalid()
            }
        is ImportConfirmDecisionFields.CreditExpenseFlow ->
            if (
                tx.transaction.kind != TransactionKind.EXPENSE ||
                version.note != "" ||
                !matches(1, fields.creditLiabilityAccountId, -normalized) ||
                postings[0].accountId == fields.creditLiabilityAccountId ||
                postings[0].amount.minorUnits != normalized
            ) {
                return invalid()
            }
        is ImportConfirmDecisionFields.CreditRepaymentFlow ->
            if (
                tx.transaction.kind != TransactionKind.CREDIT_REPAYMENT ||
                version.note != "" ||
                fields.assetAccountId == fields.creditLiabilityAccountId ||
                !matches(0, fields.assetAccountId, -normalized) ||
                !matches(1, fields.creditLiabilityAccountId, normalized)
            ) {
                return invalid()
            }
        is ImportConfirmDecisionFields.CreditExpenseRefundFlow ->
            if (
                tx.transaction.kind != TransactionKind.REFUND_RECEIPT ||
                version.note != null ||
                !matches(0, fields.creditLiabilityAccountId, normalized) ||
                postings[1].accountId == fields.creditLiabilityAccountId ||
                postings[1].amount.minorUnits != -normalized
            ) {
                return invalid()
            }
        is ImportConfirmDecisionFields.MixedPaymentFlow -> {
            val assetLeg = fields.assetLegMinor ?: return invalid()
            val creditLeg = fields.creditLegMinor ?: return invalid()
            if (tx.transaction.kind != TransactionKind.EXPENSE ||
                version.note != "" ||
                assetLeg <= 0L ||
                creditLeg <= 0L ||
                fields.assetAccountId == fields.creditLiabilityAccountId ||
                postings[0].accountId == fields.assetAccountId ||
                postings[0].accountId == fields.creditLiabilityAccountId ||
                postings[0].amount.minorUnits != normalized ||
                !matches(1, fields.assetAccountId, -assetLeg) ||
                !matches(2, fields.creditLiabilityAccountId, -creditLeg) ||
                assetLeg > Long.MAX_VALUE - creditLeg ||
                assetLeg + creditLeg != normalized
            ) {
                return invalid()
            }
        }
    }
    return DomainResult.Success(Unit)
}
