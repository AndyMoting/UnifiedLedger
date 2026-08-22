package com.unifiedledger.application

import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.FundingComponent
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.MixedPaymentExpenseCommand
import com.unifiedledger.domain.MixedPaymentExpenseIds
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.createMixedPaymentExpense
import kotlin.time.Instant

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
    private val catalog: LedgerCatalog,
) : ImportCandidateFormalFactory {

    override fun create(
        input: ImportCandidateFormalizationInput,
        ids: ImportCommitIds,
    ): DomainResult<ImportFormalCommit> {
        val fields = input.decisionFields as? ImportConfirmDecisionFields.MixedPaymentFlow
            ?: return DomainResult.Failure(com.unifiedledger.domain.DomainViolation.InvalidFormalTransaction)
        val assetLegMinor = fields.assetLegMinor
            ?: return DomainResult.Failure(com.unifiedledger.domain.DomainViolation.InvalidFormalTransaction)
        val creditLegMinor = fields.creditLegMinor
            ?: return DomainResult.Failure(com.unifiedledger.domain.DomainViolation.InvalidFormalTransaction)
        val resolved = input.resolved
        val currency = CurrencyUnit(resolved.currencyCode, resolved.currencyPrecision)
        return when (
            val result = createMixedPaymentExpense(
                catalog,
                MixedPaymentExpenseCommand(
                    ledgerId = input.ledgerId,
                    total = Money.ofMinor(resolved.amountMinor, currency),
                    categoryId = fields.categoryId,
                    funding = listOf(
                        FundingComponent(fields.assetAccountId, Money.ofMinor(assetLegMinor, currency)),
                        FundingComponent(fields.creditLiabilityAccountId, Money.ofMinor(creditLegMinor, currency)),
                    ),
                    times = TransactionTimes.collapsed(Instant.parse(resolved.occurredAt)),
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
            is DomainResult.Success -> DomainResult.Success(
                ImportFormalCommit(ids.confirmationId, ids.statusHistoryId, result.value.formalTransaction),
            )
            is DomainResult.Failure -> DomainResult.Failure(result.violation)
        }
    }
}
