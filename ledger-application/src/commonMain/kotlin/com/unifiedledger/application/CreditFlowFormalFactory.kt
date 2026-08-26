package com.unifiedledger.application

import com.unifiedledger.domain.CreditExpenseCommand
import com.unifiedledger.domain.CreditExpenseIds
import com.unifiedledger.domain.CreditPrincipalRepaymentCommand
import com.unifiedledger.domain.CreditPrincipalRepaymentIds
import com.unifiedledger.domain.CreditRefundOriginalExpense
import com.unifiedledger.domain.CreditRefundReceiptCommand
import com.unifiedledger.domain.CreditRefundReceiptIds
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.MixedPaymentViolation
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.createCreditExpense
import com.unifiedledger.domain.createCreditPrincipalRepayment
import com.unifiedledger.domain.createCreditRefundReceipt

/**
 * P4-06 slice 1 (RL-05 credit, D-107 section 3.4): production factory for the three
 * credit lifecycles, mirroring [TransferFlowFormalFactory].
 *
 * Dispatches on the decision-fields type:
 * - [ImportConfirmDecisionFields.CreditExpenseFlow] -> [createCreditExpense]
 *   (single credit-leg degeneration of the D-072 mixed entry; expense +, liability -;
 *   report effects cash-outflow 0, consumption and ordinary expense = total,
 *   net-worth change = -total).
 * - [ImportConfirmDecisionFields.CreditRepaymentFlow] -> the frozen
 *   [createCreditPrincipalRepayment] (independent CREDIT_REPAYMENT transaction; asset -,
 *   liability +; report effect cash = principal; never ACCOUNT_TRANSFER, D-072).
 * - [ImportConfirmDecisionFields.CreditExpenseRefundFlow] -> [createCreditRefundReceipt]
 *   (D-078 refund receipt with a LIABILITY destination; expense -, liability +; the
 *   original expense transaction is resolved through [originalExpenseProvider], which
 *   the caller wires to persistence; the domain performs the two original-transaction
 *   validations).
 *
 * Accounts are always explicit user decisions (D-107 section 3.3): nothing here guesses
 * or auto-creates a credit-liability account. No product Clock is read;
 * explicitConfirmedAt travels on the request as everywhere else.
 */
class CreditFlowFormalFactory(
    val catalog: LedgerCatalog,
    private val originalExpenseProvider: (TransactionId) -> CreditRefundOriginalExpense?,
) : ImportCandidateFormalFactory {

    override fun create(
        input: ImportCandidateFormalizationInput,
        ids: ImportCommitIds,
    ): DomainResult<ImportFormalCommit> {
        val resolved = input.resolved
        val targetAccountId = when (val fields = input.decisionFields) {
            is ImportConfirmDecisionFields.CreditExpenseFlow -> fields.creditLiabilityAccountId
            is ImportConfirmDecisionFields.CreditExpenseRefundFlow -> fields.creditLiabilityAccountId
            is ImportConfirmDecisionFields.CreditRepaymentFlow -> fields.creditLiabilityAccountId
            else -> return DomainResult.Failure(com.unifiedledger.domain.DomainViolation.InvalidFormalTransaction)
        }
        if (ids.formalIds.postingIds.size != 2) {
            return DomainResult.Failure(com.unifiedledger.domain.DomainViolation.InvalidFormalTransaction)
        }
        val targetCurrency = targetAccountId.let { accountId ->
            catalog.accounts.firstOrNull { it.id == accountId && it.ledgerId == input.ledgerId }?.currency
        } ?: return DomainResult.Failure(MixedPaymentViolation.UnknownRealAccount)
        val money = when (val normalized = normalizeImportAmountExact(resolved, targetCurrency)) {
            is DomainResult.Success -> normalized.value
            is DomainResult.Failure -> return DomainResult.Failure(normalized.violation)
        }
        val occurredAt = when (val parsed = parseImportOccurredAt(resolved.occurredAt)) {
            is DomainResult.Success -> parsed.value
            is DomainResult.Failure -> return DomainResult.Failure(parsed.violation)
        }
        val times = TransactionTimes.collapsed(occurredAt)
        return when (val fields = input.decisionFields) {
            is ImportConfirmDecisionFields.CreditExpenseFlow -> when (
                val result = createCreditExpense(
                    catalog,
                    CreditExpenseCommand(
                        ledgerId = input.ledgerId,
                        total = money,
                        categoryId = fields.categoryId,
                        creditLiabilityAccountId = fields.creditLiabilityAccountId,
                        times = times,
                    ),
                    CreditExpenseIds(
                        transactionId = ids.formalIds.transactionId,
                        versionId = ids.formalIds.versionId,
                        postingSetId = ids.formalIds.postingSetId,
                        expensePostingId = ids.formalIds.postingIds[0],
                        liabilityPostingId = ids.formalIds.postingIds[1],
                    ),
                )
            ) {
                is DomainResult.Success -> checkedImportFormalCommit(
                    input, ids, ImportFormalCommit(ids.confirmationId, ids.statusHistoryId, result.value.formalTransaction), catalog,
                )
                is DomainResult.Failure -> DomainResult.Failure(result.violation)
            }

            is ImportConfirmDecisionFields.CreditRepaymentFlow -> when (
                val result = createCreditPrincipalRepayment(
                    catalog,
                    CreditPrincipalRepaymentCommand(
                        ledgerId = input.ledgerId,
                        assetAccountId = fields.assetAccountId,
                        liabilityAccountId = fields.creditLiabilityAccountId,
                        principal = money,
                        times = times,
                    ),
                    CreditPrincipalRepaymentIds(
                        transactionId = ids.formalIds.transactionId,
                        versionId = ids.formalIds.versionId,
                        postingSetId = ids.formalIds.postingSetId,
                        assetPostingId = ids.formalIds.postingIds[0],
                        liabilityPostingId = ids.formalIds.postingIds[1],
                    ),
                )
            ) {
                is DomainResult.Success -> checkedImportFormalCommit(
                    input, ids, ImportFormalCommit(ids.confirmationId, ids.statusHistoryId, result.value.formalTransaction), catalog,
                )
                is DomainResult.Failure -> DomainResult.Failure(result.violation)
            }

            is ImportConfirmDecisionFields.CreditExpenseRefundFlow -> {
                val original = originalExpenseProvider(fields.originalTransactionId)
                    ?: return DomainResult.Failure(com.unifiedledger.domain.DomainViolation.InvalidRefundReceipt)
                when (
                    val result = createCreditRefundReceipt(
                        catalog,
                        CreditRefundReceiptCommand(
                            ledgerId = input.ledgerId,
                            originalTransactionId = fields.originalTransactionId,
                            originalExpense = original,
                            amount = money,
                            categoryId = fields.categoryId,
                            creditLiabilityAccountId = fields.creditLiabilityAccountId,
                            times = times,
                        ),
                        CreditRefundReceiptIds(
                            transactionId = ids.formalIds.transactionId,
                            versionId = ids.formalIds.versionId,
                            postingSetId = ids.formalIds.postingSetId,
                            creditLiabilityPostingId = ids.formalIds.postingIds[0],
                            expensePostingId = ids.formalIds.postingIds[1],
                        ),
                    )
                ) {
                    is DomainResult.Success -> checkedImportFormalCommit(
                        input, ids, ImportFormalCommit(ids.confirmationId, ids.statusHistoryId, result.value.formalTransaction), catalog,
                    )
                    is DomainResult.Failure -> DomainResult.Failure(result.violation)
                }
            }

            else -> DomainResult.Failure(com.unifiedledger.domain.DomainViolation.InvalidFormalTransaction)
        }
    }
}
