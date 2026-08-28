package com.unifiedledger.application

import com.unifiedledger.domain.AssetPaidOrdinaryExpenseCommand
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseIds
import com.unifiedledger.domain.AssetReceivedOrdinaryIncomeCommand
import com.unifiedledger.domain.AssetReceivedOrdinaryIncomeIds
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.createAssetPaidOrdinaryExpense
import com.unifiedledger.domain.createAssetReceivedOrdinaryIncome

/**
 * Shared application-layer ordinary-flow assembly helper.
 *
 * Phase 5 has not started, so this class is deliberately limited to the import
 * confirmation factory contract. It does not create an app composition root.
 */
class OrdinaryFlowFormalFactory(
    val catalog: LedgerCatalog,
) : ImportCandidateFormalFactory {
    override fun create(
        input: ImportCandidateFormalizationInput,
        ids: ImportCommitIds,
    ): DomainResult<ImportFormalCommit> {
        val fields =
            input.decisionFields as? ImportConfirmDecisionFields.OrdinaryFlow
                ?: return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
        if (ids.formalIds.postingIds.size != 2) {
            return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
        }
        val targetCurrency =
            catalog.accounts
                .firstOrNull {
                    it.id == fields.fundingAccountId && it.ledgerId == input.ledgerId
                }?.currency
                ?: return DomainResult.Failure(
                    if (input.resolved.directionToken == "in") {
                        DomainViolation.InvalidOrdinaryIncome
                    } else {
                        DomainViolation.InvalidOrdinaryExpense
                    },
                )
        val money =
            when (val normalized = normalizeImportAmountExact(input.resolved, targetCurrency)) {
                is DomainResult.Success -> normalized.value
                is DomainResult.Failure -> return DomainResult.Failure(normalized.violation)
            }
        val occurredAt =
            when (val parsed = parseImportOccurredAt(input.resolved.occurredAt)) {
                is DomainResult.Success -> parsed.value
                is DomainResult.Failure -> return DomainResult.Failure(parsed.violation)
            }
        val times = TransactionTimes.collapsed(occurredAt)
        return when (input.resolved.directionToken) {
            "out" ->
                when (
                    val result =
                        createAssetPaidOrdinaryExpense(
                            catalog,
                            AssetPaidOrdinaryExpenseCommand(
                                ledgerId = input.ledgerId,
                                amount = money,
                                categoryId = fields.categoryId,
                                paymentAccountId = fields.fundingAccountId,
                                times = times,
                            ),
                            AssetPaidOrdinaryExpenseIds(
                                transactionId = ids.formalIds.transactionId,
                                versionId = ids.formalIds.versionId,
                                postingSetId = ids.formalIds.postingSetId,
                                expensePostingId = ids.formalIds.postingIds[0],
                                paymentPostingId = ids.formalIds.postingIds[1],
                            ),
                        )
                ) {
                    is DomainResult.Success ->
                        checkedImportFormalCommit(
                            input,
                            ids,
                            ImportFormalCommit(ids.confirmationId, ids.statusHistoryId, result.value),
                            catalog,
                        )
                    is DomainResult.Failure -> DomainResult.Failure(result.violation)
                }

            "in" ->
                when (
                    val result =
                        createAssetReceivedOrdinaryIncome(
                            catalog,
                            AssetReceivedOrdinaryIncomeCommand(
                                ledgerId = input.ledgerId,
                                amount = money,
                                categoryId = fields.categoryId,
                                receivingAccountId = fields.fundingAccountId,
                                times = times,
                            ),
                            AssetReceivedOrdinaryIncomeIds(
                                transactionId = ids.formalIds.transactionId,
                                versionId = ids.formalIds.versionId,
                                postingSetId = ids.formalIds.postingSetId,
                                receivingPostingId = ids.formalIds.postingIds[0],
                                incomePostingId = ids.formalIds.postingIds[1],
                            ),
                        )
                ) {
                    is DomainResult.Success ->
                        checkedImportFormalCommit(
                            input,
                            ids,
                            ImportFormalCommit(ids.confirmationId, ids.statusHistoryId, result.value),
                            catalog,
                        )
                    is DomainResult.Failure -> DomainResult.Failure(result.violation)
                }

            else -> DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
        }
    }
}
