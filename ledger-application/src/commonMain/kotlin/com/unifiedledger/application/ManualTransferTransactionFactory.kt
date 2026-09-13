package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.AccountTransfer
import com.unifiedledger.domain.AccountTransferViolation
import com.unifiedledger.domain.CatalogAdmissionRejection
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.ManualTransferViolation
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.OwnAssetAccountTransferCommand
import com.unifiedledger.domain.OwnAssetPrincipalTransferCommand
import com.unifiedledger.domain.OwnAssetPrincipalTransferIds
import com.unifiedledger.domain.PrincipalTransferViolation
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.createOwnAssetAccountTransfer
import com.unifiedledger.domain.createOwnAssetPrincipalTransfer

/**
 * P7-02.B T-1..T-4 product transaction factory. It derives the source debit
 * (`destinationCredit + fee`, P1-2), then reuses the domain factories: the fee-bearing three-leg
 * factory when `fee > 0` (note `String = ""`) and the pure-principal two-leg factory when
 * `fee == 0` (note explicitly passed by the product, overriding the import-chain `null` default).
 *
 * The factory assumes the account/category references were already admitted by the V-2 wrapper;
 * it still maps any residual domain rejection onto the stable product token family so the
 * product surface never leaks a raw `AccountTransferViolation`.
 */
class ManualTransferTransactionFactory(
    private val catalog: LedgerCatalog,
) : ConfirmedTransferTransactionFactory {
    override fun create(
        request: ManualTransferRequestSnapshot,
        ids: ConfirmedManualTransferCommitIds,
    ): DomainResult<ConfirmedManualTransferCommit> {
        validateEntryNote(request.note)?.let { return DomainResult.Failure(it) }
        // P702IMPL-02: the frozen `TransferFeeMustNotBeNegative` token is reachable here — a
        // negative fee is a typed zero-write rejection before any derivation or domain call.
        if (request.fee.minorUnits < 0L) return DomainResult.Failure(ManualTransferViolation.TransferFeeMustNotBeNegative)
        val currency = request.destinationCredit.currency
        val sum = request.destinationCredit.minorUnits + request.fee.minorUnits
        if (sum < 0L) return DomainResult.Failure(DomainViolation.ArithmeticOverflow)

        return if (request.fee.minorUnits > 0L) {
            val feeCategoryId = request.feeCategoryId ?: return DomainResult.Failure(ManualTransferViolation.TransferFeeCategoryRequired)
            val sourceDebit = Money.ofMinor(sum, currency)
            when (
                val result =
                    createOwnAssetAccountTransfer(
                        catalog = catalog,
                        command =
                            OwnAssetAccountTransferCommand(
                                ledgerId = request.ledgerId,
                                sourceAccountId = request.sourceAccountId,
                                destinationAccountId = request.destinationAccountId,
                                sourceDebit = sourceDebit,
                                destinationCredit = request.destinationCredit,
                                fee = request.fee,
                                feeCategoryId = feeCategoryId,
                                times = TransactionTimes.collapsed(request.occurredAt),
                                note = request.note,
                            ),
                        ids = ids.transferIds,
                    )
            ) {
                is DomainResult.Success ->
                    DomainResult.Success(
                        ConfirmedManualTransferCommit(
                            confirmationId = ids.confirmationId,
                            transfer = result.value,
                        ),
                    )
                is DomainResult.Failure -> DomainResult.Failure(result.violation.toManualTransferViolation())
            }
        } else {
            // fee == 0: T-4 forbids carrying a fee category; the pure-principal factory is used.
            if (request.feeCategoryId != null) return DomainResult.Failure(ManualTransferViolation.TransferFeeCategoryNotAllowed)
            when (
                val result =
                    createOwnAssetPrincipalTransfer(
                        catalog = catalog,
                        command =
                            OwnAssetPrincipalTransferCommand(
                                ledgerId = request.ledgerId,
                                sourceAccountId = request.sourceAccountId,
                                destinationAccountId = request.destinationAccountId,
                                amount = request.destinationCredit,
                                times = TransactionTimes.collapsed(request.occurredAt),
                                note = request.note,
                            ),
                        ids =
                            OwnAssetPrincipalTransferIds(
                                transactionId = ids.transferIds.transactionId,
                                versionId = ids.transferIds.versionId,
                                postingSetId = ids.transferIds.postingSetId,
                                sourcePostingId = ids.transferIds.sourcePostingId,
                                destinationPostingId = ids.transferIds.destinationPostingId,
                            ),
                    )
            ) {
                is DomainResult.Success ->
                    DomainResult.Success(
                        ConfirmedManualTransferCommit(
                            confirmationId = ids.confirmationId,
                            transfer = result.value.toAccountTransfer(),
                        ),
                    )
                is DomainResult.Failure -> DomainResult.Failure(result.violation.toManualTransferViolation())
            }
        }
    }
}

/** Maps the reused domain transfer tokens onto the stable product failure family (T-2..T-4). */
private fun DomainViolation.toManualTransferViolation(): DomainViolation =
    when (this) {
        AccountTransferViolation.DistinctAccountsRequired,
        PrincipalTransferViolation.DistinctAccountsRequired,
        -> ManualTransferViolation.TransferSameAccount

        AccountTransferViolation.FeeMustNotBeNegative -> ManualTransferViolation.TransferFeeMustNotBeNegative

        AccountTransferViolation.AmountsMustBalance -> ManualTransferViolation.TransferAmountMismatch

        AccountTransferViolation.InvalidFeeCategory -> ManualTransferViolation.TransferFeeCategoryRequired

        is AccountTransferViolation.AmountMustBePositive,
        PrincipalTransferViolation.AmountMustBePositive,
        -> ManualTransferViolation.TransferAmountMustBePositive

        is AccountTransferViolation.KnownAccountRequired,
        is AccountTransferViolation.OwnAccountRequired,
        is AccountTransferViolation.RealFinancialAccountRequired,
        is AccountTransferViolation.AssetAccountRequired,
        is AccountTransferViolation.SameCurrencyRequired,
        is PrincipalTransferViolation.KnownAccountRequired,
        is PrincipalTransferViolation.OwnedRealAssetRequired,
        is PrincipalTransferViolation.SameCurrencyRequired,
        -> ManualTransferViolation.TransferAccountNotEligible

        else -> this
    }

/** The principal transfer's report effects are a subset; wrap it into the uniform transfer shape. */
private fun com.unifiedledger.domain.OwnAssetPrincipalTransfer.toAccountTransfer(): AccountTransfer =
    AccountTransfer(
        formalTransaction = formalTransaction,
        postings =
            postings.map {
                com.unifiedledger.domain.AccountTransferPosting(
                    posting = it.posting,
                    role =
                        when (it.role) {
                            com.unifiedledger.domain.PrincipalTransferPostingRole.PRINCIPAL_OUT ->
                                com.unifiedledger.domain.TransferPostingRole.PRINCIPAL_OUT
                            com.unifiedledger.domain.PrincipalTransferPostingRole.PRINCIPAL_IN ->
                                com.unifiedledger.domain.TransferPostingRole.PRINCIPAL_IN
                        },
                )
            },
        reportEffects =
            com.unifiedledger.domain.AccountTransferReportEffects(
                consumptionMinor = 0L,
                ordinaryExpenseMinor = 0L,
                cashOutflowMinor = 0L,
                ordinaryIncomeMinor = 0L,
                cashInflowMinor = 0L,
                principalConsumptionMinor = 0L,
                principalExternalCashFlowMinor = 0L,
                internalTransferMinor = reportEffects.internalTransferMinor,
                netWorthChangeMinor = reportEffects.netWorthChangeMinor,
            ),
    )

/**
 * P7-02.B V-2 transfer admission: both ends must be owned real ASSET accounts in the same
 * ledger and currency; `fee > 0` additionally requires an active leaf EXPENSE fee category whose
 * posting account is a same-ledger hidden EXPENSE account. `fee == 0` must not carry a category.
 */
fun validateManualTransferAdmission(
    catalog: LedgerCatalog,
    ledgerId: LedgerId,
    sourceAccountId: AccountId,
    destinationAccountId: AccountId,
    fee: Money,
    feeCategoryId: CategoryId?,
): DomainViolation? {
    validateTransferEndpoint(catalog, ledgerId, sourceAccountId)?.let { return it }
    validateTransferEndpoint(catalog, ledgerId, destinationAccountId)?.let { return it }
    if (fee.minorUnits > 0L) {
        val feeCategoryId = feeCategoryId ?: return ManualTransferViolation.TransferFeeCategoryRequired
        val category =
            catalog.categories.firstOrNull { it.id == feeCategoryId }
                ?: return CatalogAdmissionRejection.CategoryNotFound
        if (category.ledgerId != ledgerId) return CatalogAdmissionRejection.CategoryNotFound
        if (category.kind != CategoryKind.EXPENSE) return CatalogAdmissionRejection.CategoryKindMismatch
        if (category.parentId == null) return CatalogAdmissionRejection.CategoryNotLeaf
        if (!category.active) return CatalogAdmissionRejection.CategoryInactive
        val postingAccount =
            catalog.accounts.firstOrNull { it.id == category.postingAccountId }
                ?: return CatalogAdmissionRejection.CategoryNotFound
        if (postingAccount.ledgerId != ledgerId || postingAccount.kind != AccountKind.EXPENSE) {
            return CatalogAdmissionRejection.CategoryKindMismatch
        }
    } else {
        if (feeCategoryId != null) return ManualTransferViolation.TransferFeeCategoryNotAllowed
    }
    return null
}

private fun validateTransferEndpoint(
    catalog: LedgerCatalog,
    ledgerId: LedgerId,
    accountId: AccountId,
): DomainViolation? {
    val account =
        catalog.accounts.firstOrNull { it.id == accountId }
            ?: return CatalogAdmissionRejection.PaymentAccountNotFound
    if (account.ledgerId != ledgerId) return CatalogAdmissionRejection.PaymentAccountNotFound
    if (account.kind != AccountKind.ASSET) return CatalogAdmissionRejection.PaymentAccountWrongKind
    if (!account.ownedByUser || !account.realAccount) {
        return CatalogAdmissionRejection.PaymentAccountNotManageableFinancial
    }
    if (!account.active) return CatalogAdmissionRejection.PaymentAccountInactive
    return null
}

/**
 * V-2 wrapper around the transfer factory: it re-reads the authoritative catalog immediately
 * before delegating so a stale option snapshot cannot admit an inactive/mismatched endpoint.
 */
class CatalogAdmissionTransferTransactionFactory(
    private val admissionReader: CatalogAdmissionReader,
    private val delegate: ConfirmedTransferTransactionFactory,
) : ConfirmedTransferTransactionFactory {
    override fun create(
        request: ManualTransferRequestSnapshot,
        ids: ConfirmedManualTransferCommitIds,
    ): DomainResult<ConfirmedManualTransferCommit> {
        val catalog =
            admissionReader.loadCurrent(request.ledgerId)
                ?: return DomainResult.Failure(CatalogAdmissionRejection.CatalogUnavailable)
        val violation =
            validateManualTransferAdmission(
                catalog = catalog,
                ledgerId = request.ledgerId,
                sourceAccountId = request.sourceAccountId,
                destinationAccountId = request.destinationAccountId,
                fee = request.fee,
                feeCategoryId = request.feeCategoryId,
            )
        if (violation != null) return DomainResult.Failure(violation)
        return delegate.create(request, ids)
    }
}
