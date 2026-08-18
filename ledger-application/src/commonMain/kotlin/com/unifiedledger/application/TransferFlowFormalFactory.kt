package com.unifiedledger.application

import com.unifiedledger.domain.*
import kotlin.time.Instant

/**
 * Production factory for transfer formalization.
 *
 * Creates an [ImportFormalCommit] from a [ImportCandidateFormalizationInput] by:
 * 1. Validating the direction gate (wallet leg must match direction)
 * 2. Validating both accounts are self-owned real assets of the same ledger
 * 3. Normalizing source scale to target currency precision
 * 4. Calling [createOwnAssetPrincipalTransfer] with the normalized amount
 *
 * @param catalog the ledger catalog containing accounts and categories
 * @param walletAccountId the wallet account ID (used for direction gate validation)
 */
class TransferFlowFormalFactory(
    private val catalog: LedgerCatalog,
    private val walletAccountId: AccountId,
) : ImportCandidateFormalFactory {

    override fun create(
        input: ImportCandidateFormalizationInput,
        ids: ImportCommitIds,
    ): DomainResult<ImportFormalCommit> {
        val decisionFields = input.decisionFields
        if (decisionFields !is ImportConfirmDecisionFields.TransferFlow) {
            // Compatibility note: using InvalidOrdinaryIncome for the direction gate failure
            // per spec §11.7. This is a technical debt item - the next batch that allows
            // extending ledger-domain must migrate to a dedicated violation.
            return DomainResult.Failure(DomainViolation.InvalidOrdinaryIncome)
        }

        // Direction gate: wallet leg must match direction
        // "out" => wallet is the FROM leg; "in" => wallet is the TO leg
        val directionOk = when (input.resolved.directionToken) {
            "out" -> decisionFields.fromAccountId == walletAccountId
            "in" -> decisionFields.toAccountId == walletAccountId
            else -> false
        }
        if (!directionOk) {
            // Compatibility note: using InvalidOrdinaryIncome per spec §11.7
            return DomainResult.Failure(DomainViolation.InvalidOrdinaryIncome)
        }

        // Validate both accounts belong to the same ledger
        val fromAccount = catalog.accounts.firstOrNull { it.id == decisionFields.fromAccountId }
            ?: return DomainResult.Failure(
                PrincipalTransferViolation.KnownAccountRequired(PrincipalTransferField.SOURCE_ACCOUNT),
            )
        val toAccount = catalog.accounts.firstOrNull { it.id == decisionFields.toAccountId }
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
        if (!fromAccount.ownedByUser || !toAccount.ownedByUser ||
            !fromAccount.realAccount || !toAccount.realAccount
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

        // Source currency code must match target currency
        if (input.resolved.currencyCode != fromAccount.currency.code) {
            return DomainResult.Failure(
                DomainViolation.AmountNotRepresentableInCurrency(
                    amountMinor = input.resolved.amountMinor,
                    sourceScale = input.resolved.currencyPrecision,
                    targetCurrencyCode = fromAccount.currency.code,
                    targetPrecision = fromAccount.currency.precision,
                ),
            )
        }

        // Normalize source amount to target precision
        val targetCurrency = fromAccount.currency
        val normalizedAmount = normalizeSourceMinorExact(
            amountMinor = input.resolved.amountMinor,
            sourceScale = input.resolved.currencyPrecision,
            targetPrecision = targetCurrency.precision,
        ) ?: return DomainResult.Failure(
            DomainViolation.AmountNotRepresentableInCurrency(
                amountMinor = input.resolved.amountMinor,
                sourceScale = input.resolved.currencyPrecision,
                targetCurrencyCode = targetCurrency.code,
                targetPrecision = targetCurrency.precision,
            ),
        )

        val normalizedMoney = Money.ofMinor(normalizedAmount, targetCurrency)

        // Create the principal transfer
        val command = OwnAssetPrincipalTransferCommand(
            ledgerId = input.ledgerId,
            sourceAccountId = decisionFields.fromAccountId,
            destinationAccountId = decisionFields.toAccountId,
            amount = normalizedMoney,
            times = TransactionTimes.collapsed(Instant.parse(input.resolved.occurredAt)),
        )
        val transferIds = OwnAssetPrincipalTransferIds(
            transactionId = ids.formalIds.transactionId,
            versionId = ids.formalIds.versionId,
            postingSetId = ids.formalIds.postingSetId,
            sourcePostingId = ids.formalIds.postingIds[0],
            destinationPostingId = ids.formalIds.postingIds[1],
        )

        return when (val result = createOwnAssetPrincipalTransfer(catalog, command, transferIds)) {
            is DomainResult.Success -> {
                val ft = result.value.formalTransaction
                DomainResult.Success(
                    ImportFormalCommit(
                        confirmationId = ids.confirmationId,
                        statusHistoryId = ids.statusHistoryId,
                        transaction = ft,
                    ),
                )
            }
            is DomainResult.Failure -> DomainResult.Failure(result.violation)
        }
    }
}

/**
 * Validates that the formal commit graph created by a factory exactly matches the
 * immutable input and the allocated IDs.
 *
 * @param input the immutable formalization input used for this operation
 * @param allocatedIds the IDs allocated by the commit port for this specific attempt
 * @param created the formal commit returned by the factory
 * @return Success if all bindings are valid, Failure with a generic violation otherwise
 */
fun validateImportFormalBinding(
    input: ImportCandidateFormalizationInput,
    allocatedIds: ImportCommitIds,
    created: ImportFormalCommit,
): DomainResult<Unit> {
    // Validate confirmationId
    if (created.confirmationId != allocatedIds.confirmationId) {
        return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
    }
    // Validate statusHistoryId
    if (created.statusHistoryId != allocatedIds.statusHistoryId) {
        return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
    }

    val tx = created.transaction

    // Validate transaction ID
    if (tx.transaction.id != allocatedIds.formalIds.transactionId) {
        return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
    }
    // Validate ledger
    if (tx.transaction.ledgerId != input.ledgerId) {
        return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
    }

    // For TransferFlow: validate the complete formal graph
    if (input.decisionFields is ImportConfirmDecisionFields.TransferFlow) {
        // Kind must be ACCOUNT_TRANSFER
        if (tx.transaction.kind != TransactionKind.ACCOUNT_TRANSFER) {
            return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
        }

        // Must have exactly one version
        if (tx.versions.size != 1) {
            return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
        }
        val version = tx.versions[0]
        if (version.id != allocatedIds.formalIds.versionId) {
            return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
        }
        if (version.transactionId != tx.transaction.id) {
            return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
        }
        if (version.versionNumber != 1) {
            return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
        }
        if (version.note != null) {
            return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
        }

        // Must have exactly one posting set
        if (tx.postingSets.size != 1) {
            return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
        }
        val postingSet = tx.postingSets[0]
        if (postingSet.id != allocatedIds.formalIds.postingSetId) {
            return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
        }
        if (version.postingSetId != postingSet.id) {
            return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
        }

        // Must have exactly two postings
        if (postingSet.postings.size != 2) {
            return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
        }
        if (allocatedIds.formalIds.postingIds.size != 2) {
            return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
        }

        val fields = input.decisionFields
        for (i in 0..1) {
            val posting = postingSet.postings[i]
            if (posting.id != allocatedIds.formalIds.postingIds[i]) {
                return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
            }
            // Validate posting accounts match decision fields
            when (i) {
                0 -> if (posting.accountId != fields.fromAccountId) return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
                1 -> if (posting.accountId != fields.toAccountId) return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
            }
        }

        // Amount/currency/precision binding (spec 4.2): postings 0/1 must be
        // (fromAccountId, -normalized target minor, target CurrencyUnit) and
        // (toAccountId, +normalized target minor, same currency/precision). Both
        // postings must share one CurrencyUnit whose code equals the resolved source
        // currency code, and the minor units must be exactly the source amount
        // normalized to that shared precision (no rounding, no floating point).
        val sourceAmount = postingSet.postings[0].amount
        val destinationAmount = postingSet.postings[1].amount
        if (sourceAmount.currency != destinationAmount.currency) {
            return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
        }
        val targetCurrency = sourceAmount.currency
        if (targetCurrency.code != input.resolved.currencyCode) {
            return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
        }
        val expectedNormalizedMinor = normalizeSourceMinorExact(
            amountMinor = input.resolved.amountMinor,
            sourceScale = input.resolved.currencyPrecision,
            targetPrecision = targetCurrency.precision,
        ) ?: return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
        if (sourceAmount.minorUnits != -expectedNormalizedMinor) {
            return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
        }
        if (destinationAmount.minorUnits != expectedNormalizedMinor) {
            return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
        }

        // Validate times match source occurredAt
        val sourceOccurredAt = Instant.parse(input.resolved.occurredAt)
        if (version.times.occurredAt != sourceOccurredAt ||
            version.times.statisticsAt != sourceOccurredAt ||
            version.times.effectiveAt != sourceOccurredAt
        ) {
            return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
        }
    }

    return DomainResult.Success(Unit)
}

/**
 * Exactly normalizes a source amount from its source scale to a target precision.
 *
 * Rules:
 * (a) sourceScale < 0 => null (AmountNotRepresentableInCurrency)
 * (b) targetPrecision >= sourceScale => multiply by 10^(diff), checked for overflow
 * (c) targetPrecision < sourceScale => divide by 10^(diff), only if exactly divisible
 *     when diff > 18 and amountMinor != 0, shortcut to null
 * (d) NO rounding, NO Double/Float
 */
internal fun normalizeSourceMinorExact(
    amountMinor: Long,
    sourceScale: Int,
    targetPrecision: Int,
): Long? {
    // (a) negative source scale
    if (sourceScale < 0) return null

    // (b) targetPrecision >= sourceScale: multiply up
    if (targetPrecision >= sourceScale) {
        val factor = checkedPow10(targetPrecision - sourceScale)
        return multiplyExact(amountMinor, factor)
    }

    // (c) targetPrecision < sourceScale: divide down
    val divisor = sourceScale - targetPrecision
    // Optimization: if divisor > 18 and amountMinor != 0, definitely not divisible
    if (divisor > 18 && amountMinor != 0L) return null
    val factor = checkedPow10(divisor)
    if (amountMinor % factor != 0L) return null
    return amountMinor / factor
}

/** Computes 10^exp for exp in 0..18, throwing on overflow. */
private fun checkedPow10(exp: Int): Long {
    require(exp >= 0) { "negative exponent: $exp" }
    if (exp > 18) error("exponent $exp > 18 overflow")
    var result = 1L
    repeat(exp) { result *= 10L }
    return result
}

/** Multiplies two longs, returning null on overflow. */
private fun multiplyExact(a: Long, b: Long): Long? {
    if (a == 0L || b == 0L) return 0L
    val result = a * b
    if (result / a != b) return null
    return result
}