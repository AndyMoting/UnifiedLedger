package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.Rg04CommitPort
import com.unifiedledger.application.Rg04ExecutionError
import com.unifiedledger.application.Rg04ExecutionResult
import com.unifiedledger.application.Rg04ManualSnapshot
import com.unifiedledger.application.Rg04PreparedOperation
import com.unifiedledger.application.Rg04RepaymentSnapshot
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountTransferViolation
import com.unifiedledger.domain.BalanceAdjustmentViolation
import com.unifiedledger.domain.CategoryRenameViolation
import com.unifiedledger.domain.CorrectTransactionVersionViolation
import com.unifiedledger.domain.CreditPrincipalRepaymentCommand
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.ExplicitOperationConfirmationViolation
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.FundingComponent
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.LendingViolation
import com.unifiedledger.domain.MergedPaymentViolation
import com.unifiedledger.domain.MixedPaymentExpenseCommand
import com.unifiedledger.domain.MixedPaymentPosting
import com.unifiedledger.domain.MixedPaymentPostingRole
import com.unifiedledger.domain.MixedPaymentViolation
import com.unifiedledger.domain.OrdinaryExpenseViolation
import com.unifiedledger.domain.OrdinaryIncomeViolation
import com.unifiedledger.domain.PeriodicAllocationViolation
import com.unifiedledger.domain.PostingReconciliationViolation
import com.unifiedledger.domain.PostingReplacementViolation
import com.unifiedledger.domain.PrincipalTransferViolation
import com.unifiedledger.domain.ReconciliationMatchViolation
import com.unifiedledger.domain.StoredValueViolation
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.createCreditPrincipalRepayment
import com.unifiedledger.domain.createMixedPaymentExpense

data class Rg04ManualCommitIds(
    val confirmationId: String,
    val reconciliationIds: List<String>,
)

data class Rg04RepaymentCommitIds(
    val confirmationId: String,
    val reconciliationIds: List<String>,
)

interface Rg04IdentitySource {
    fun manual(requestId: RequestId): Rg04ManualCommitIds

    fun repayment(requestId: RequestId): Rg04RepaymentCommitIds
}

internal enum class Rg04FailurePoint { AFTER_FORMAL, AFTER_RELATION, AFTER_RECONCILIATION, BEFORE_RECEIPT }

internal fun interface Rg04FailureInjector {
    fun failAt(point: Rg04FailurePoint)
}

private val NO_FAILURE = Rg04FailureInjector { }

class SqlDelightRg04Store private constructor(
    private val database: LedgerDatabase,
    private val catalog: LedgerCatalog,
    private val identity: Rg04IdentitySource,
    private val failure: Rg04FailureInjector,
) : Rg04CommitPort {
    constructor(database: LedgerDatabase, driver: SqlDriver, catalog: LedgerCatalog, identity: Rg04IdentitySource) : this(database, catalog, identity, NO_FAILURE) {

        configureSqliteConnection(driver)
    }

    internal constructor(database: LedgerDatabase, driver: SqlDriver, catalog: LedgerCatalog, identity: Rg04IdentitySource, failure: Rg04FailureInjector) : this(database, catalog, identity, failure) {

        configureSqliteConnection(driver)
    }

    override fun commit(operation: Rg04PreparedOperation): Rg04ExecutionResult {
        val confirmed =

            when (operation) {
                is Rg04PreparedOperation.Manual -> operation.snapshot.confirmed

                is Rg04PreparedOperation.Repayment -> operation.snapshot.confirmed
            }

        if (!confirmed) {
            return Rg04ExecutionResult.Rejected(
                Rg04ExecutionError.EXPLICIT_CONFIRMATION_REQUIRED,
                "explicit_confirmation",
            )
        }

        return when (operation) {
            is Rg04PreparedOperation.Manual -> manual(operation)

            is Rg04PreparedOperation.Repayment -> repayment(operation)
        }
    }

    private fun manual(operation: Rg04PreparedOperation.Manual): Rg04ExecutionResult {
        val snapshot = operation.snapshot

        if (database.ledgerQueries.selectRg04Action(snapshot.ledgerId.value, snapshot.requestId.value).executeAsOneOrNull() != null) {
            return resolveManual(snapshot)
        }

        val ids = identity.manual(snapshot.requestId)

        require(ids.reconciliationIds.size == 2) { "mixed expense requires exactly two reconciliation identities" }

        return database.transactionWithResult {
            database.ledgerQueries.claimRg04Request(snapshot.ledgerId.value, snapshot.requestId.value, "MANUAL_MIXED_EXPENSE")

            if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) return@transactionWithResult resolveManual(snapshot)

            val formalIds = operation.formalIds

            val created = createMixedPaymentExpense(catalog, MixedPaymentExpenseCommand(snapshot.ledgerId, snapshot.total, snapshot.categoryId, snapshot.funding.map { FundingComponent(it.accountId, it.amount) }, TransactionTimes.collapsed(snapshot.occurredAt)), formalIds)

            val aggregate =

                when (created) {
                    is DomainResult.Success -> created.value

                    is DomainResult.Failure -> {
                        database.ledgerQueries.deleteRg04Request(snapshot.ledgerId.value, snapshot.requestId.value)

                        return@transactionWithResult created.violation.rejected()
                    }
                }

            if (snapshot.settlement.original.minorUnits - snapshot.settlement.discount.minorUnits != snapshot.settlement.settled.minorUnits || snapshot.settlement.settled != snapshot.total) {
                database.ledgerQueries.deleteRg04Request(snapshot.ledgerId.value, snapshot.requestId.value)

                return@transactionWithResult Rg04ExecutionResult.Rejected(Rg04ExecutionError.FUNDING_TOTAL_MUST_EQUAL_EXPENSE, "settlement_explanation")
            }

            database.ledgerQueries.insertRg04MixedSnapshot(snapshot.ledgerId.value, snapshot.requestId.value, snapshot.occurredAtText, snapshot.total.minorUnits, snapshot.currency.code, snapshot.currency.precision.toLong(), snapshot.categoryId.value, snapshot.settlement.original.minorUnits, snapshot.settlement.discount.minorUnits, snapshot.settlement.settled.minorUnits, "explicit_manual_save")

            snapshot.funding.forEachIndexed { i, item ->

                database.ledgerQueries.insertRg04MixedSnapshotComponent(
                    snapshot.ledgerId.value,
                    snapshot.requestId.value,
                    i.toLong(),
                    item.accountId.value,
                    item.amount.minorUnits,
                    item.amount.currency.code,
                    item.amount.currency.precision
                        .toLong(),
                )
            }

            persistFormal(aggregate.formalTransaction, snapshot.occurredAtText)

            persistSemantics(snapshot.ledgerId, aggregate.postings)

            failure.failAt(Rg04FailurePoint.AFTER_FORMAL)

            val relationId = operation.relationId

            database.ledgerQueries.insertFormalRelation(snapshot.ledgerId.value, relationId)

            database.ledgerQueries.insertFormalRelationMember(snapshot.ledgerId.value, relationId, 0, "TRANSACTION", formalIds.transactionId.value, null)

            aggregate.postings.filter { it.role != MixedPaymentPostingRole.EXPENSE }.forEachIndexed { i, typed -> database.ledgerQueries.insertFormalRelationMember(snapshot.ledgerId.value, relationId, (i + 1).toLong(), "POSTING", null, typed.posting.id.value) }

            database.ledgerQueries.insertRg04MixedComposition(
                snapshot.ledgerId.value,
                relationId,
                formalIds.transactionId.value,
                operation.relationDisplayName,
                snapshot.total.minorUnits,
                snapshot.total.currency.code,
                snapshot.total.currency.precision
                    .toLong(),
            )

            aggregate.postings.filter { it.role != MixedPaymentPostingRole.EXPENSE }.forEachIndexed { i, typed ->

                val positive = snapshot.funding.first { it.accountId == typed.posting.accountId }.amount

                database.ledgerQueries.insertRg04MixedCompositionComponent(snapshot.ledgerId.value, relationId, i.toLong(), typed.posting.id.value, typed.posting.accountId.value, positive.minorUnits, positive.currency.code, positive.currency.precision.toLong())
            }

            database.ledgerQueries.insertRg04Settlement(
                snapshot.ledgerId.value,
                formalIds.transactionId.value,
                snapshot.settlement.original.minorUnits,
                snapshot.settlement.discount.minorUnits,
                snapshot.settlement.settled.minorUnits,
                snapshot.total.currency.code,
                snapshot.total.currency.precision
                    .toLong(),
            )

            failure.failAt(Rg04FailurePoint.AFTER_RELATION)

            formalIds.fundingPostingIds.zip(ids.reconciliationIds).forEachIndexed { index, (posting, rec) ->

                database.ledgerQueries.insertRg04PostingReconciliation(snapshot.ledgerId.value, rec, posting.value)

                database.ledgerQueries.insertRg04InitialReconciliationIdentity(snapshot.ledgerId.value, snapshot.requestId.value, index.toLong(), rec, posting.value)
            }

            failure.failAt(Rg04FailurePoint.AFTER_RECONCILIATION)

            database.ledgerQueries.insertRg04Confirmation(snapshot.ledgerId.value, ids.confirmationId, snapshot.requestId.value, formalIds.transactionId.value)

            failure.failAt(Rg04FailurePoint.BEFORE_RECEIPT)

            database.ledgerQueries.insertRg04Receipt(snapshot.ledgerId.value, snapshot.requestId.value, ids.confirmationId, formalIds.transactionId.value)

            Rg04ExecutionResult.Accepted(ids.confirmationId, formalIds.transactionId)
        }
    }

    private fun repayment(operation: Rg04PreparedOperation.Repayment): Rg04ExecutionResult {
        val snapshot = operation.snapshot

        if (database.ledgerQueries.selectRg04Action(snapshot.ledgerId.value, snapshot.requestId.value).executeAsOneOrNull() != null) {
            return resolveRepayment(snapshot)
        }

        val ids = identity.repayment(snapshot.requestId)

        require(ids.reconciliationIds.size == 2) { "credit repayment requires exactly two reconciliation identities" }

        return database.transactionWithResult {
            database.ledgerQueries.claimRg04Request(snapshot.ledgerId.value, snapshot.requestId.value, "CREDIT_PRINCIPAL_REPAYMENT")

            if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) return@transactionWithResult resolveRepayment(snapshot)

            val formalIds = operation.formalIds

            val created = createCreditPrincipalRepayment(catalog, CreditPrincipalRepaymentCommand(snapshot.ledgerId, snapshot.assetAccountId, snapshot.liabilityAccountId, snapshot.principal, TransactionTimes.collapsed(snapshot.occurredAt)), formalIds)

            val aggregate =

                when (created) {
                    is DomainResult.Success -> created.value

                    is DomainResult.Failure -> {
                        database.ledgerQueries.deleteRg04Request(snapshot.ledgerId.value, snapshot.requestId.value)

                        return@transactionWithResult created.violation.rejected()
                    }
                }

            database.ledgerQueries.insertRg04RepaymentSnapshot(snapshot.ledgerId.value, snapshot.requestId.value, snapshot.occurredAtText, snapshot.assetAccountId.value, snapshot.liabilityAccountId.value, snapshot.principal.minorUnits, snapshot.currency.code, snapshot.currency.precision.toLong(), "explicit_manual_save")

            persistFormal(aggregate.formalTransaction, snapshot.occurredAtText)

            persistSemantics(snapshot.ledgerId, aggregate.postings)

            failure.failAt(Rg04FailurePoint.AFTER_FORMAL)

            listOf(formalIds.assetPostingId, formalIds.liabilityPostingId).zip(ids.reconciliationIds).forEachIndexed { index, (posting, rec) ->

                database.ledgerQueries.insertRg04PostingReconciliation(snapshot.ledgerId.value, rec, posting.value)

                database.ledgerQueries.insertRg04InitialReconciliationIdentity(snapshot.ledgerId.value, snapshot.requestId.value, index.toLong(), rec, posting.value)
            }

            failure.failAt(Rg04FailurePoint.AFTER_RECONCILIATION)

            database.ledgerQueries.insertRg04Confirmation(snapshot.ledgerId.value, ids.confirmationId, snapshot.requestId.value, formalIds.transactionId.value)

            failure.failAt(Rg04FailurePoint.BEFORE_RECEIPT)

            database.ledgerQueries.insertRg04Receipt(snapshot.ledgerId.value, snapshot.requestId.value, ids.confirmationId, formalIds.transactionId.value)

            Rg04ExecutionResult.Accepted(ids.confirmationId, formalIds.transactionId)
        }
    }

    private fun resolveManual(snapshot: Rg04ManualSnapshot): Rg04ExecutionResult {
        if (database.ledgerQueries.selectRg04Action(snapshot.ledgerId.value, snapshot.requestId.value).executeAsOneOrNull() != "MANUAL_MIXED_EXPENSE") return Rg04ExecutionResult.RequestIdentityConflict

        val stored = database.ledgerQueries.selectRg04ManualCommit(snapshot.ledgerId.value, snapshot.requestId.value).executeAsOneOrNull() ?: return Rg04ExecutionResult.RequestIdentityConflict

        val components = database.ledgerQueries.selectRg04ManualSnapshotComponents(snapshot.ledgerId.value, snapshot.requestId.value).executeAsList()

        val matches =

            stored.confirmation_marker == "explicit_manual_save" &&

                snapshot.confirmed &&

                stored.occurred_at == snapshot.occurredAtText &&

                stored.total_minor == snapshot.total.minorUnits &&

                stored.currency_code == snapshot.currency.code &&

                stored.currency_precision == snapshot.currency.precision.toLong() &&

                stored.category_id == snapshot.categoryId.value &&

                stored.original_minor == snapshot.settlement.original.minorUnits &&

                stored.discount_minor == snapshot.settlement.discount.minorUnits &&

                stored.settled_minor == snapshot.settlement.settled.minorUnits &&

                components.size == snapshot.funding.size &&

                components.zip(snapshot.funding).all { (a, b) ->

                    a.account_id == b.accountId.value &&

                        a.amount_minor == b.amount.minorUnits &&

                        a.currency_code == b.amount.currency.code &&

                        a.currency_precision ==

                        b.amount.currency.precision
                            .toLong()
                }

        return if (matches) Rg04ExecutionResult.NoChange(stored.confirmation_id, TransactionId(stored.transaction_id)) else Rg04ExecutionResult.RequestIdentityConflict
    }

    private fun resolveRepayment(snapshot: Rg04RepaymentSnapshot): Rg04ExecutionResult {
        if (database.ledgerQueries.selectRg04Action(snapshot.ledgerId.value, snapshot.requestId.value).executeAsOneOrNull() != "CREDIT_PRINCIPAL_REPAYMENT") return Rg04ExecutionResult.RequestIdentityConflict

        val stored = database.ledgerQueries.selectRg04RepaymentCommit(snapshot.ledgerId.value, snapshot.requestId.value).executeAsOneOrNull() ?: return Rg04ExecutionResult.RequestIdentityConflict

        val matches = stored.confirmation_marker == "explicit_manual_save" && snapshot.confirmed && stored.occurred_at == snapshot.occurredAtText && stored.asset_account_id == snapshot.assetAccountId.value && stored.liability_account_id == snapshot.liabilityAccountId.value && stored.principal_minor == snapshot.principal.minorUnits && stored.currency_code == snapshot.currency.code && stored.currency_precision == snapshot.currency.precision.toLong()

        return if (matches) Rg04ExecutionResult.NoChange(stored.confirmation_id, TransactionId(stored.transaction_id)) else Rg04ExecutionResult.RequestIdentityConflict
    }

    private fun persistFormal(
        value: FormalTransaction,
        time: String,
    ) {
        value.postingSets.forEach { database.ledgerQueries.insertPostingSet(it.id.value, value.transaction.ledgerId.value) }

        database.ledgerQueries.insertTransaction(value.transaction.id.value, value.transaction.ledgerId.value, value.transaction.kind.name)

        value.versions.forEach { database.ledgerQueries.insertTransactionVersion(it.id.value, it.transactionId.value, value.transaction.ledgerId.value, it.versionNumber.toLong(), it.postingSetId.value, time, time, time, it.note) }

        database.ledgerQueries.insertTransactionCurrentVersion(value.transaction.id.value, value.transaction.ledgerId.value, value.transaction.currentVersionId.value)

        value.postingSets.forEach { set ->

            set.postings.forEachIndexed { i, p ->

                database.ledgerQueries.insertPosting(
                    p.id.value,
                    set.id.value,
                    value.transaction.ledgerId.value,
                    i.toLong(),
                    p.accountId.value,
                    p.amount.minorUnits,
                    p.amount.currency.code,
                    p.amount.currency.precision
                        .toLong(),
                )
            }
        }
    }

    private fun persistSemantics(
        ledger: LedgerId,
        postings: List<MixedPaymentPosting>,
    ) = postings.forEach {
        database.ledgerQueries.insertRg04PostingSemantic(
            ledger.value,
            it.posting.id.value,
            it.role.name.lowercase(),
            it.categoryId?.value,
            if (it.role == MixedPaymentPostingRole.EXPENSE) 0 else 1,
        )
    }
}

private fun DomainViolation.rejected(): Rg04ExecutionResult.Rejected =

    when (this) {
        MixedPaymentViolation.AmountMustBePositive -> Rg04ExecutionResult.Rejected(Rg04ExecutionError.MUST_BE_POSITIVE, "total_amount")

        MixedPaymentViolation.FundingLegMustBePositive -> Rg04ExecutionResult.Rejected(Rg04ExecutionError.FUNDING_LEG_MUST_BE_POSITIVE, "funding_components")

        MixedPaymentViolation.DuplicateFundingAccount -> Rg04ExecutionResult.Rejected(Rg04ExecutionError.DUPLICATE_FUNDING_ACCOUNT, "funding_components")

        MixedPaymentViolation.FundingTotalMustEqualExpense -> Rg04ExecutionResult.Rejected(Rg04ExecutionError.FUNDING_TOTAL_MUST_EQUAL_EXPENSE, "funding_components")

        MixedPaymentViolation.UnknownRealAccount -> Rg04ExecutionResult.Rejected(Rg04ExecutionError.UNKNOWN_REAL_ACCOUNT, "funding_components")

        MixedPaymentViolation.RealFinancialAccountRequired -> Rg04ExecutionResult.Rejected(Rg04ExecutionError.REAL_FINANCIAL_ACCOUNT_REQUIRED, "funding_components")

        MixedPaymentViolation.OwnedAccountRequired -> Rg04ExecutionResult.Rejected(Rg04ExecutionError.OWNED_ACCOUNT_REQUIRED, "funding_components")

        MixedPaymentViolation.SecondaryCategoryRequired -> Rg04ExecutionResult.Rejected(Rg04ExecutionError.SECONDARY_CATEGORY_REQUIRED, "category_id")

        MixedPaymentViolation.CategoryInactive -> Rg04ExecutionResult.Rejected(Rg04ExecutionError.CATEGORY_INACTIVE, "category_id")

        MixedPaymentViolation.ExpenseCategoryRequired -> Rg04ExecutionResult.Rejected(Rg04ExecutionError.EXPENSE_CATEGORY_REQUIRED, "category_id")

        MixedPaymentViolation.SingleCurrencyRequired -> Rg04ExecutionResult.Rejected(Rg04ExecutionError.SINGLE_CURRENCY_REQUIRED, "funding_components")

        MixedPaymentViolation.AssetAndCreditLiabilityRequired -> Rg04ExecutionResult.Rejected(Rg04ExecutionError.ASSET_AND_CREDIT_LIABILITY_REQUIRED, "funding_components")

        DomainViolation.ArithmeticOverflow,

        is DomainViolation.AmountNotRepresentableInCurrency,

        DomainViolation.InvalidPostingSet,

        DomainViolation.UnbalancedPostingSet,

        DomainViolation.InvalidFormalTransaction,

        DomainViolation.InvalidMixedPayment,

        -> Rg04ExecutionResult.Rejected(Rg04ExecutionError.INTERNAL_DOMAIN_VIOLATION, "operation")

        DomainViolation.InvalidCatalog,

        DomainViolation.InvalidOrdinaryExpense,

        DomainViolation.InvalidOrdinaryIncome,

        DomainViolation.InvalidBalanceReplay,

        DomainViolation.InvalidMergedPayment,

        DomainViolation.InvalidRefundReceipt,

        is OrdinaryExpenseViolation,

        is OrdinaryIncomeViolation,

        is AccountTransferViolation,

        is MergedPaymentViolation,

        is BalanceAdjustmentViolation,

        is PrincipalTransferViolation,

        is StoredValueViolation,

        is LendingViolation,

        is PeriodicAllocationViolation,

        is ExplicitOperationConfirmationViolation,

        is CorrectTransactionVersionViolation,

        is ReconciliationMatchViolation,

        is PostingReplacementViolation,

        is PostingReconciliationViolation,

        is CategoryRenameViolation,

        -> Rg04ExecutionResult.Rejected(Rg04ExecutionError.INTERNAL_DOMAIN_VIOLATION, "operation")
    }
