package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.ConfirmedIncomeReceipt
import com.unifiedledger.application.ConfirmedManualIncomeCommit
import com.unifiedledger.application.ConfirmedManualIncomeCommitPort
import com.unifiedledger.application.ConfirmedManualIncomeResult
import com.unifiedledger.application.ManualIncomeRequestIdentity
import com.unifiedledger.application.ManualIncomeRequestSnapshot
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.TransactionId

/**
 * P7-02.A S-1: income claim-first commit boundary, aligned with
 * [SqlDelightConfirmedManualExpenseCommitPort] (same atomic claim/work/receipt discipline,
 * same SQL semantics; this batch only adds the companion entry point, the marker constant and
 * the documentation).
 */
class SqlDelightConfirmedManualIncomeCommitPort private constructor(
    private val database: LedgerDatabase,
) : ConfirmedManualIncomeCommitPort {
    constructor(database: LedgerDatabase, driver: SqlDriver) : this(database) {
        configureSqliteConnection(driver)
    }

    override fun commitOnce(
        identity: ManualIncomeRequestIdentity,
        requestSnapshot: ManualIncomeRequestSnapshot,
        createFormalTransaction: () -> DomainResult<ConfirmedManualIncomeCommit>,
    ): ConfirmedManualIncomeResult {
        require(identity.ledgerId == requestSnapshot.ledgerId) {
            "Request identity and snapshot must belong to the same ledger"
        }

        return database.transactionWithResult {
            database.ledgerQueries.claimManualIncomeRequest(
                ledger_id = identity.ledgerId.value,
                request_id = identity.requestId.value,
                amount_minor = requestSnapshot.amount.minorUnits,
                currency_code = requestSnapshot.amount.currency.code,
                currency_precision =
                    requestSnapshot.amount.currency.precision
                        .toLong(),
                category_id = requestSnapshot.categoryId.value,
                receiving_account_id = requestSnapshot.receivingAccountId.value,
                occurred_at = requestSnapshot.occurredAt.toString(),
                note = requestSnapshot.note,
                confirmation_marker = EXPLICIT_MANUAL_SAVE_MARKER,
            )

            if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
                return@transactionWithResult resolveExisting(identity, requestSnapshot)
            }

            when (val created = createFormalTransaction()) {
                is DomainResult.Failure -> {
                    database.ledgerQueries.deleteManualIncomeRequest(identity.ledgerId.value, identity.requestId.value)
                    ConfirmedManualIncomeResult.Rejected(created.violation)
                }

                is DomainResult.Success -> {
                    require(created.value.transaction.transaction.ledgerId == identity.ledgerId) {
                        "Committed transaction must belong to the request ledger"
                    }
                    persistFormalTransaction(created.value.transaction)
                    database.ledgerQueries.insertConfirmedIncomeReceipt(identity.ledgerId.value, identity.requestId.value, created.value.confirmationId.value, created.value.transaction.transaction.id.value)
                    ConfirmedManualIncomeResult.Created(ConfirmedIncomeReceipt(created.value.confirmationId, created.value.transaction.transaction.id))
                }
            }
        }
    }

    private fun resolveExisting(
        identity: ManualIncomeRequestIdentity,
        snapshot: ManualIncomeRequestSnapshot,
    ): ConfirmedManualIncomeResult {
        val stored = checkNotNull(database.ledgerQueries.selectCommittedManualIncomeRequest(identity.ledgerId.value, identity.requestId.value) { amount, code, precision, category, account, occurred, note, marker, confirmation, transaction -> StoredIncomeCommit(amount, code, precision, category, account, occurred, note, marker, ConfirmedIncomeReceipt(ConfirmationId(confirmation), TransactionId(transaction))) }.executeAsOneOrNull())

        return if (stored.matches(snapshot)) ConfirmedManualIncomeResult.NoChange(stored.receipt) else ConfirmedManualIncomeResult.RequestIdentityConflict(identity)
    }

    private fun persistFormalTransaction(value: FormalTransaction) {
        value.postingSets.forEach { database.ledgerQueries.insertPostingSet(it.id.value, value.transaction.ledgerId.value) }

        val transaction = value.transaction

        database.ledgerQueries.insertTransaction(transaction.id.value, transaction.ledgerId.value, transaction.kind.name)

        value.versions.forEach { database.ledgerQueries.insertTransactionVersion(it.id.value, it.transactionId.value, transaction.ledgerId.value, it.versionNumber.toLong(), it.postingSetId.value, it.times.occurredAt.toString(), it.times.statisticsAt.toString(), it.times.effectiveAt.toString(), it.note) }

        database.ledgerQueries.insertTransactionCurrentVersion(transaction.id.value, transaction.ledgerId.value, transaction.currentVersionId.value)

        value.postingSets.forEach { set ->
            set.postings.forEachIndexed { index, posting ->
                database.ledgerQueries.insertPosting(
                    posting.id.value,
                    set.id.value,
                    transaction.ledgerId.value,
                    index.toLong(),
                    posting.accountId.value,
                    posting.amount.minorUnits,
                    posting.amount.currency.code,
                    posting.amount.currency.precision
                        .toLong(),
                )
            }
        }
    }

    companion object {
        internal fun forPlatformConfiguredDatabase(
            database: LedgerDatabase,
        ): SqlDelightConfirmedManualIncomeCommitPort = SqlDelightConfirmedManualIncomeCommitPort(database)
    }
}

private const val EXPLICIT_MANUAL_SAVE_MARKER = "explicit_manual_save"

private data class StoredIncomeCommit(
    val amount: Long,
    val code: String,
    val precision: Long,
    val category: String,
    val account: String,
    val occurred: String,
    val note: String,
    val marker: String,
    val receipt: ConfirmedIncomeReceipt,
) {
    fun matches(value: ManualIncomeRequestSnapshot) =
        amount == value.amount.minorUnits &&
            code == value.amount.currency.code &&
            precision ==
            value.amount.currency.precision
                .toLong() &&
            category == value.categoryId.value &&
            account == value.receivingAccountId.value &&
            occurred == value.occurredAt.toString() &&
            note == value.note &&
            marker == EXPLICIT_MANUAL_SAVE_MARKER
}
