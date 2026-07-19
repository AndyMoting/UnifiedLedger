package com.unifiedledger.data

import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.ConfirmedExpenseReceipt
import com.unifiedledger.application.ConfirmedManualExpenseCommit
import com.unifiedledger.application.ConfirmedManualExpenseCommitPort
import com.unifiedledger.application.ConfirmedManualExpenseResult
import com.unifiedledger.application.ManualExpenseRequestIdentity
import com.unifiedledger.application.ManualExpenseRequestSnapshot
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.TransactionId
import app.cash.sqldelight.db.SqlDriver

class SqlDelightConfirmedManualExpenseCommitPort private constructor(
    private val database: LedgerDatabase,
) : ConfirmedManualExpenseCommitPort {
    constructor(database: LedgerDatabase, driver: SqlDriver) : this(database) {
        configureSqliteConnection(driver)
    }

    override fun commitOnce(
        identity: ManualExpenseRequestIdentity,
        requestSnapshot: ManualExpenseRequestSnapshot,
        createFormalTransaction: () -> DomainResult<ConfirmedManualExpenseCommit>,
    ): ConfirmedManualExpenseResult {
        require(identity.ledgerId == requestSnapshot.ledgerId) {
            "Request identity and snapshot must belong to the same ledger"
        }

        return database.transactionWithResult {
            database.ledgerQueries.claimManualExpenseRequest(
                ledger_id = identity.ledgerId.value,
                request_id = identity.requestId.value,
                amount_minor = requestSnapshot.amount.minorUnits,
                currency_code = requestSnapshot.amount.currency.code,
                currency_precision = requestSnapshot.amount.currency.precision.toLong(),
                category_id = requestSnapshot.categoryId.value,
                payment_account_id = requestSnapshot.paymentAccountId.value,
                occurred_at = requestSnapshot.occurredAt.toString(),
                note = requestSnapshot.note,
                confirmation_marker = EXPLICIT_MANUAL_SAVE_MARKER,
            )
            val claimed = database.ledgerQueries.lastStatementChangedRowCount()
                .executeAsOne() == 1L
            if (!claimed) {
                return@transactionWithResult resolveExisting(identity, requestSnapshot)
            }

            when (val creation = createFormalTransaction()) {
                is DomainResult.Failure -> {
                    database.ledgerQueries.deleteManualExpenseRequest(
                        ledger_id = identity.ledgerId.value,
                        request_id = identity.requestId.value,
                    )
                    ConfirmedManualExpenseResult.Rejected(creation.violation)
                }

                is DomainResult.Success -> {
                    require(
                        creation.value.transaction.transaction.ledgerId == identity.ledgerId,
                    ) { "Committed transaction must belong to the request ledger" }
                    persistFormalTransaction(creation.value.transaction)
                    persistReceipt(identity, creation.value)
                    ConfirmedManualExpenseResult.Created(
                        ConfirmedExpenseReceipt(
                            confirmationId = creation.value.confirmationId,
                            transactionId = creation.value.transaction.transaction.id,
                        ),
                    )
                }
            }
        }
    }

    private fun resolveExisting(
        identity: ManualExpenseRequestIdentity,
        requestSnapshot: ManualExpenseRequestSnapshot,
    ): ConfirmedManualExpenseResult {
        val existing = checkNotNull(
            database.ledgerQueries.selectCommittedRequest(
                ledger_id = identity.ledgerId.value,
                request_id = identity.requestId.value,
            ) { amountMinor, currencyCode, currencyPrecision, categoryId, paymentAccountId,
                    occurredAt, note, confirmationMarker, confirmationId, transactionId ->
                StoredCommit(
                    amountMinor = amountMinor,
                    currencyCode = currencyCode,
                    currencyPrecision = currencyPrecision,
                    categoryId = categoryId,
                    paymentAccountId = paymentAccountId,
                    occurredAt = occurredAt,
                    note = note,
                    confirmationMarker = confirmationMarker,
                    receipt = ConfirmedExpenseReceipt(
                        confirmationId = ConfirmationId(confirmationId),
                        transactionId = TransactionId(transactionId),
                    ),
                )
            }.executeAsOneOrNull(),
        ) { "Committed request is missing its receipt" }
        return if (existing.matches(requestSnapshot)) {
            ConfirmedManualExpenseResult.NoChange(existing.receipt)
        } else {
            ConfirmedManualExpenseResult.RequestIdentityConflict(identity)
        }
    }

    private fun persistFormalTransaction(formalTransaction: FormalTransaction) {
        formalTransaction.postingSets.forEach { postingSet ->
            database.ledgerQueries.insertPostingSet(
                posting_set_id = postingSet.id.value,
                ledger_id = formalTransaction.transaction.ledgerId.value,
            )
        }

        val transaction = formalTransaction.transaction
        database.ledgerQueries.insertTransaction(
            transaction_id = transaction.id.value,
            ledger_id = transaction.ledgerId.value,
            kind = transaction.kind.name,
        )

        formalTransaction.versions.forEach { version ->
            database.ledgerQueries.insertTransactionVersion(
                version_id = version.id.value,
                transaction_id = version.transactionId.value,
                ledger_id = transaction.ledgerId.value,
                version_number = version.versionNumber.toLong(),
                posting_set_id = version.postingSetId.value,
                occurred_at = version.times.occurredAt.toString(),
                statistics_at = version.times.statisticsAt.toString(),
                effective_at = version.times.effectiveAt.toString(),
                note = version.note,
            )
        }

        database.ledgerQueries.insertTransactionCurrentVersion(
            transaction_id = transaction.id.value,
            ledger_id = transaction.ledgerId.value,
            current_version_id = transaction.currentVersionId.value,
        )

        formalTransaction.postingSets.forEach { postingSet ->
            postingSet.postings.forEachIndexed { index, posting ->
                database.ledgerQueries.insertPosting(
                    posting_id = posting.id.value,
                    posting_set_id = postingSet.id.value,
                    ledger_id = transaction.ledgerId.value,
                    posting_index = index.toLong(),
                    account_id = posting.accountId.value,
                    amount_minor = posting.amount.minorUnits,
                    currency_code = posting.amount.currency.code,
                    currency_precision = posting.amount.currency.precision.toLong(),
                )
            }
        }
    }

    private fun persistReceipt(
        identity: ManualExpenseRequestIdentity,
        commit: ConfirmedManualExpenseCommit,
    ) {
        database.ledgerQueries.insertConfirmedExpenseReceipt(
            ledger_id = identity.ledgerId.value,
            request_id = identity.requestId.value,
            confirmation_id = commit.confirmationId.value,
            transaction_id = commit.transaction.transaction.id.value,
        )
    }

    companion object {
        internal fun forPlatformConfiguredDatabase(
            database: LedgerDatabase,
        ): SqlDelightConfirmedManualExpenseCommitPort =
            SqlDelightConfirmedManualExpenseCommitPort(database)
    }
}

private const val EXPLICIT_MANUAL_SAVE_MARKER = "explicit_manual_save"
private const val SQLITE_BUSY_TIMEOUT_MILLISECONDS = 5_000

private fun configureSqliteConnection(driver: SqlDriver) {
    driver.execute(null, "PRAGMA foreign_keys = ON", 0)
    driver.execute(
        null,
        "PRAGMA busy_timeout = $SQLITE_BUSY_TIMEOUT_MILLISECONDS",
        0,
    )
}

private data class StoredCommit(
    val amountMinor: Long,
    val currencyCode: String,
    val currencyPrecision: Long,
    val categoryId: String,
    val paymentAccountId: String,
    val occurredAt: String,
    val note: String,
    val confirmationMarker: String,
    val receipt: ConfirmedExpenseReceipt,
) {
    fun matches(snapshot: ManualExpenseRequestSnapshot): Boolean =
        amountMinor == snapshot.amount.minorUnits &&
            currencyCode == snapshot.amount.currency.code &&
            currencyPrecision == snapshot.amount.currency.precision.toLong() &&
            categoryId == snapshot.categoryId.value &&
            paymentAccountId == snapshot.paymentAccountId.value &&
            occurredAt == snapshot.occurredAt.toString() &&
            note == snapshot.note &&
            confirmationMarker == EXPLICIT_MANUAL_SAVE_MARKER
}
