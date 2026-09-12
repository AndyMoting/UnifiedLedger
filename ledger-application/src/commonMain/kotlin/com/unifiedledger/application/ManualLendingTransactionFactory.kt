package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CatalogAdmissionRejection
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.LendingViolation
import com.unifiedledger.domain.ManualCollectCommand
import com.unifiedledger.domain.ManualCollectIds
import com.unifiedledger.domain.ManualLendCommand
import com.unifiedledger.domain.ManualLendIds
import com.unifiedledger.domain.ManualLendingViolation
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.createManualCollect
import com.unifiedledger.domain.createManualLend

/**
 * P7-02.C product transaction factory. It re-reads the authoritative catalog, the authoritative
 * counterparty directory and the outstanding position immediately before delegating (V-2 / L-3),
 * then reuses the product domain constructors
 * [com.unifiedledger.domain.createManualLend]/[com.unifiedledger.domain.createManualCollect].
 *
 * Admission tokens follow spec section 4.2.8: account references report the shared
 * [CatalogAdmissionRejection] `PaymentAccount*`/`Category*` family, while a missing/cross-ledger
 * or `active=false` counterparty reports `LendingCounterpartyNotFound` (L-1/L-3). Any residual
 * domain rejection is mapped onto the stable product token family.
 */
class ManualLendingTransactionFactory(
    private val admissionReader: CatalogAdmissionReader,
    private val counterpartyReader: CounterpartyDirectoryReader,
    private val positionReader: LendingPositionReader,
) : ConfirmedLendingTransactionFactory {
    override fun create(
        request: ManualLendingRequestSnapshot,
        ids: ConfirmedManualLendingCommitIds,
    ): DomainResult<ConfirmedManualLendingCommit> {
        validateEntryNote(request.note)?.let { return DomainResult.Failure(it) }
        val catalog =
            admissionReader.loadCurrent(request.ledgerId)
                ?: return DomainResult.Failure(CatalogAdmissionRejection.CatalogUnavailable)
        validateLendingAssetAccount(catalog, request.ledgerId, request.principalAccountId)?.let {
            return DomainResult.Failure(it)
        }
        if (request.behavior == ManualLendingBehavior.COLLECT) {
            validateLendingInterestCategory(catalog, request.ledgerId, request.interestCategoryId)?.let {
                return DomainResult.Failure(it)
            }
        }
        val counterparty =
            counterpartyReader.find(request.ledgerId, request.counterpartyId)
                ?: return DomainResult.Failure(ManualLendingViolation.LendingCounterpartyNotFound)
        if (!counterparty.active) {
            return DomainResult.Failure(ManualLendingViolation.LendingCounterpartyNotFound)
        }
        val receivableAccountId = counterparty.receivableAccountId
        val receivable =
            catalog.accounts.firstOrNull { it.id == receivableAccountId }
                ?: return DomainResult.Failure(ManualLendingViolation.LendingCounterpartyNotFound)
        if (receivable.ledgerId != request.ledgerId || receivable.kind != AccountKind.ASSET) {
            return DomainResult.Failure(ManualLendingViolation.LendingCounterpartyNotFound)
        }
        val position =
            positionReader.load(
                request.ledgerId,
                request.counterpartyId,
                receivableAccountId,
                request.amount.currency,
            )
        return when (request.behavior) {
            ManualLendingBehavior.LEND -> createLend(request, ids, catalog, position, receivableAccountId)
            ManualLendingBehavior.COLLECT -> createCollect(request, ids, catalog, position, receivableAccountId)
        }
    }

    private fun createLend(
        request: ManualLendingRequestSnapshot,
        ids: ConfirmedManualLendingCommitIds,
        catalog: LedgerCatalog,
        position: com.unifiedledger.domain.LendingPosition,
        receivableAccountId: AccountId,
    ): DomainResult<ConfirmedManualLendingCommit> =
        when (
            val result =
                createManualLend(
                    catalog = catalog,
                    position = position,
                    command =
                        ManualLendCommand(
                            ledgerId = request.ledgerId,
                            counterpartyId = request.counterpartyId,
                            receivableAccountId = receivableAccountId,
                            fundingAccountId = request.principalAccountId,
                            amount = request.amount,
                            times = TransactionTimes.collapsed(request.occurredAt),
                            note = request.note,
                        ),
                    ids =
                        ManualLendIds(
                            entryId = ids.entryId,
                            transactionId = ids.lendingIds.transactionId,
                            versionId = ids.lendingIds.versionId,
                            postingSetId = ids.lendingIds.postingSetId,
                            receivablePostingId = ids.lendingIds.counterpartyPostingId,
                            fundingPostingId = ids.lendingIds.primaryAccountPostingId,
                        ),
                )
        ) {
            is DomainResult.Success ->
                DomainResult.Success(
                    ConfirmedManualLendingCommit(
                        confirmationId = ids.confirmationId,
                        transaction = result.value.formalTransaction,
                        position = result.value.position,
                    ),
                )

            is DomainResult.Failure -> DomainResult.Failure(result.violation.toManualLendingViolation())
        }

    private fun createCollect(
        request: ManualLendingRequestSnapshot,
        ids: ConfirmedManualLendingCommitIds,
        catalog: LedgerCatalog,
        position: com.unifiedledger.domain.LendingPosition,
        receivableAccountId: AccountId,
    ): DomainResult<ConfirmedManualLendingCommit> {
        val totalReceived =
            request.totalReceived
                ?: return DomainResult.Failure(ManualLendingViolation.LendingComponentsMismatch)
        val interestCategoryId =
            request.interestCategoryId
                ?: return DomainResult.Failure(ManualLendingViolation.LendingInterestCategoryRequired)
        return when (
            val result =
                createManualCollect(
                    catalog = catalog,
                    position = position,
                    command =
                        ManualCollectCommand(
                            ledgerId = request.ledgerId,
                            counterpartyId = request.counterpartyId,
                            receivableAccountId = receivableAccountId,
                            destinationAccountId = request.principalAccountId,
                            interestCategoryId = interestCategoryId,
                            totalReceived = totalReceived,
                            principal = request.amount,
                            interest = request.interest,
                            fee = request.fee,
                            times = TransactionTimes.collapsed(request.occurredAt),
                            note = request.note,
                        ),
                    ids =
                        ManualCollectIds(
                            entryId = ids.entryId,
                            transactionId = ids.lendingIds.transactionId,
                            versionId = ids.lendingIds.versionId,
                            postingSetId = ids.lendingIds.postingSetId,
                            destinationPostingId = ids.lendingIds.primaryAccountPostingId,
                            principalPostingId = ids.lendingIds.counterpartyPostingId,
                            interestPostingId = ids.lendingIds.interestPostingId,
                        ),
                )
        ) {
            is DomainResult.Success ->
                DomainResult.Success(
                    ConfirmedManualLendingCommit(
                        confirmationId = ids.confirmationId,
                        transaction = result.value.formalTransaction,
                        position = result.value.position,
                    ),
                )

            is DomainResult.Failure -> DomainResult.Failure(result.violation.toManualLendingViolation())
        }
    }
}

/** Shared account admission for lending: owned real ASSET, same ledger, active. */
private fun validateLendingAssetAccount(
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

/** COLLECT interest-category admission: active leaf INCOME with a same-ledger INCOME posting. */
private fun validateLendingInterestCategory(
    catalog: LedgerCatalog,
    ledgerId: LedgerId,
    categoryId: CategoryId?,
): DomainViolation? {
    val categoryId = categoryId ?: return CatalogAdmissionRejection.CategoryNotFound
    val category =
        catalog.categories.firstOrNull { it.id == categoryId }
            ?: return CatalogAdmissionRejection.CategoryNotFound
    if (category.ledgerId != ledgerId) return CatalogAdmissionRejection.CategoryNotFound
    if (category.kind != CategoryKind.INCOME) return CatalogAdmissionRejection.CategoryKindMismatch
    if (category.parentId == null) return CatalogAdmissionRejection.CategoryNotLeaf
    if (!category.active) return CatalogAdmissionRejection.CategoryInactive
    val postingAccount =
        catalog.accounts.firstOrNull { it.id == category.postingAccountId }
            ?: return CatalogAdmissionRejection.CategoryNotFound
    if (postingAccount.ledgerId != ledgerId || postingAccount.kind != AccountKind.INCOME) {
        return CatalogAdmissionRejection.CategoryKindMismatch
    }
    return null
}

/** Maps the reused lending/position domain tokens onto the stable product failure family. */
fun DomainViolation.toManualLendingViolation(): DomainViolation =
    when (this) {
        LendingViolation.PrincipalExceedsOutstandingPosition ->
            ManualLendingViolation.LendingPrincipalExceedsBalance

        LendingViolation.ComponentMustBeNonnegative,
        LendingViolation.ComponentsMustEqualTotal,
        LendingViolation.InvalidComponentSet,
        -> ManualLendingViolation.LendingComponentsMismatch

        LendingViolation.FeeMustBeZeroInRg08V1,
        LendingViolation.NonzeroFeeAccountingOutOfScope,
        -> ManualLendingViolation.LendingFeeMustBeZero

        LendingViolation.ActiveExactInterestCategoryRequired ->
            ManualLendingViolation.LendingInterestCategoryRequired

        LendingViolation.TotalMustBePositive -> ManualLendingViolation.LendingTotalMustBePositive

        LendingViolation.UnknownAccount,
        LendingViolation.OwnedAccountRequired,
        LendingViolation.FinancialAssetAccountRequired,
        LendingViolation.SameCurrencyRequired,
        -> ManualLendingViolation.LendingAccountNotEligible

        LendingViolation.InvalidLendingBehavior ->
            ManualLendingViolation.LendingBehaviorNotSupported

        else -> this
    }
