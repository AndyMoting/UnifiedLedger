package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.*
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.TransactionId

class SqlDelightConfirmedManualIncomeCommitPort private constructor(private val database: LedgerDatabase) : ConfirmedManualIncomeCommitPort {
    constructor(database: LedgerDatabase, driver: SqlDriver) : this(database) { configureSqliteConnection(driver) }
    override fun commitOnce(identity: ManualIncomeRequestIdentity, requestSnapshot: ManualIncomeRequestSnapshot, createFormalTransaction: () -> DomainResult<ConfirmedManualIncomeCommit>): ConfirmedManualIncomeResult {
        require(identity.ledgerId == requestSnapshot.ledgerId)
        return database.transactionWithResult {
            database.ledgerQueries.claimManualIncomeRequest(identity.ledgerId.value, identity.requestId.value, requestSnapshot.amount.minorUnits, requestSnapshot.amount.currency.code, requestSnapshot.amount.currency.precision.toLong(), requestSnapshot.categoryId.value, requestSnapshot.receivingAccountId.value, requestSnapshot.occurredAt.toString(), requestSnapshot.note, "explicit_manual_save")
            if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) return@transactionWithResult resolveExisting(identity, requestSnapshot)
            when (val created = createFormalTransaction()) {
                is DomainResult.Failure -> { database.ledgerQueries.deleteManualIncomeRequest(identity.ledgerId.value, identity.requestId.value); ConfirmedManualIncomeResult.Rejected(created.violation) }
                is DomainResult.Success -> {
                    require(created.value.transaction.transaction.ledgerId == identity.ledgerId)
                    persistFormalTransaction(created.value.transaction)
                    database.ledgerQueries.insertConfirmedIncomeReceipt(identity.ledgerId.value, identity.requestId.value, created.value.confirmationId.value, created.value.transaction.transaction.id.value)
                    ConfirmedManualIncomeResult.Created(ConfirmedIncomeReceipt(created.value.confirmationId, created.value.transaction.transaction.id))
                }
            }
        }
    }
    private fun resolveExisting(identity: ManualIncomeRequestIdentity, snapshot: ManualIncomeRequestSnapshot): ConfirmedManualIncomeResult {
        val stored = checkNotNull(database.ledgerQueries.selectCommittedManualIncomeRequest(identity.ledgerId.value, identity.requestId.value) { amount, code, precision, category, account, occurred, note, marker, confirmation, transaction -> StoredIncomeCommit(amount, code, precision, category, account, occurred, note, marker, ConfirmedIncomeReceipt(ConfirmationId(confirmation), TransactionId(transaction))) }.executeAsOneOrNull())
        return if (stored.matches(snapshot)) ConfirmedManualIncomeResult.NoChange(stored.receipt) else ConfirmedManualIncomeResult.RequestIdentityConflict(identity)
    }
    private fun persistFormalTransaction(value: FormalTransaction) {
        value.postingSets.forEach { database.ledgerQueries.insertPostingSet(it.id.value, value.transaction.ledgerId.value) }
        val transaction = value.transaction
        database.ledgerQueries.insertTransaction(transaction.id.value, transaction.ledgerId.value, transaction.kind.name)
        value.versions.forEach { database.ledgerQueries.insertTransactionVersion(it.id.value, it.transactionId.value, transaction.ledgerId.value, it.versionNumber.toLong(), it.postingSetId.value, it.times.occurredAt.toString(), it.times.statisticsAt.toString(), it.times.effectiveAt.toString(), it.note) }
        database.ledgerQueries.insertTransactionCurrentVersion(transaction.id.value, transaction.ledgerId.value, transaction.currentVersionId.value)
        value.postingSets.forEach { set -> set.postings.forEachIndexed { index, posting -> database.ledgerQueries.insertPosting(posting.id.value, set.id.value, transaction.ledgerId.value, index.toLong(), posting.accountId.value, posting.amount.minorUnits, posting.amount.currency.code, posting.amount.currency.precision.toLong()) } }
    }
}
private data class StoredIncomeCommit(val amount: Long, val code: String, val precision: Long, val category: String, val account: String, val occurred: String, val note: String, val marker: String, val receipt: ConfirmedIncomeReceipt) {
    fun matches(value: ManualIncomeRequestSnapshot) = amount == value.amount.minorUnits && code == value.amount.currency.code && precision == value.amount.currency.precision.toLong() && category == value.categoryId.value && account == value.receivingAccountId.value && occurred == value.occurredAt.toString() && note == value.note && marker == "explicit_manual_save"
}
