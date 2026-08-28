package com.unifiedledger.application

import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.FundingComponent
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.MixedPaymentExpenseCommand
import com.unifiedledger.domain.MixedPaymentExpenseIds
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.createMixedPaymentExpense

/**
 * P4-06 slice 2 (RL-06 mixed payment, D-108 section 4.2): production factory for the
 * mixed-payment confirm path, mirroring [CreditFlowFormalFactory].
 *
 * Dispatches [ImportConfirmDecisionFields.MixedPaymentFlow] to the frozen domain
 * [createMixedPaymentExpense] (exactly two funding legs: asset - and credit liability
 * -; expense +total; TransactionKind.EXPENSE with exactly three postings). The
 * expense arithmetic constraint (legs sum = source total) is owned by the domain.
 * Accounts are always explicit user decisions; no product Clock is read.
 * explicitConfirmedAt travels on the request as everywhere else. A null leg amount is
 * a defensive [DomainResult.Failure] (the store's leg gate intercepts it first).
 */
class MixedPaymentFlowFormalFactory(
    val catalog: LedgerCatalog,
) : ImportCandidateFormalFactory {
    override fun create(
        input: ImportCandidateFormalizationInput,
        ids: ImportCommitIds,
    ): DomainResult<ImportFormalCommit> {
        val fields =
            input.decisionFields as? ImportConfirmDecisionFields.MixedPaymentFlow
                ?: return DomainResult.Failure(com.unifiedledger.domain.DomainViolation.InvalidFormalTransaction)
        if (ids.formalIds.postingIds.size != 3) {
            return DomainResult.Failure(com.unifiedledger.domain.DomainViolation.InvalidFormalTransaction)
        }
        val assetLegMinor =
            fields.assetLegMinor
                ?: return DomainResult.Failure(com.unifiedledger.domain.DomainViolation.InvalidFormalTransaction)
        val creditLegMinor =
            fields.creditLegMinor
                ?: return DomainResult.Failure(com.unifiedledger.domain.DomainViolation.InvalidFormalTransaction)
        val targetCurrency =
            catalog.accounts
                .firstOrNull {
                    it.id == fields.assetAccountId && it.ledgerId == input.ledgerId
                }?.currency
                ?: return DomainResult.Failure(com.unifiedledger.domain.MixedPaymentViolation.UnknownRealAccount)
        if (catalog.accounts.none { it.id == fields.creditLiabilityAccountId && it.ledgerId == input.ledgerId }) {
            return DomainResult.Failure(com.unifiedledger.domain.MixedPaymentViolation.UnknownRealAccount)
        }
        val resolved = input.resolved
        val total =
            when (val normalized = normalizeImportAmountExact(resolved, targetCurrency)) {
                is DomainResult.Success -> normalized.value
                is DomainResult.Failure -> return DomainResult.Failure(normalized.violation)
            }
        val occurredAt =
            when (val parsed = parseImportOccurredAt(resolved.occurredAt)) {
                is DomainResult.Success -> parsed.value
                is DomainResult.Failure -> return DomainResult.Failure(parsed.violation)
            }
        return when (
            val result =
                createMixedPaymentExpense(
                    catalog,
                    MixedPaymentExpenseCommand(
                        ledgerId = input.ledgerId,
                        total = total,
                        categoryId = fields.categoryId,
                        funding =
                            listOf(
                                // D-108 freezes leg fields as target-account minor units; this
                                // batch does not infer or rescale an independent leg precision.
                                FundingComponent(fields.assetAccountId, Money.ofMinor(assetLegMinor, targetCurrency)),
                                FundingComponent(fields.creditLiabilityAccountId, Money.ofMinor(creditLegMinor, targetCurrency)),
                            ),
                        times = TransactionTimes.collapsed(occurredAt),
                    ),
                    MixedPaymentExpenseIds(
                        transactionId = ids.formalIds.transactionId,
                        versionId = ids.formalIds.versionId,
                        postingSetId = ids.formalIds.postingSetId,
                        expensePostingId = ids.formalIds.postingIds[0],
                        fundingPostingIds = listOf(ids.formalIds.postingIds[1], ids.formalIds.postingIds[2]),
                    ),
                )
        ) {
            is DomainResult.Success ->
                checkedImportFormalCommit(
                    input,
                    ids,
                    ImportFormalCommit(ids.confirmationId, ids.statusHistoryId, result.value.formalTransaction),
                    catalog,
                )
            is DomainResult.Failure -> DomainResult.Failure(result.violation)
        }
    }
}
