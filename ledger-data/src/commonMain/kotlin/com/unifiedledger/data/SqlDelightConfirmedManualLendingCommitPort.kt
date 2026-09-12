package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.ConfirmedLendingReceipt
import com.unifiedledger.application.ConfirmedManualLendingCommit
import com.unifiedledger.application.ConfirmedManualLendingCommitPort
import com.unifiedledger.application.ConfirmedManualLendingResult
import com.unifiedledger.application.ManualLendingBehavior
import com.unifiedledger.application.ManualLendingCommitRecord
import com.unifiedledger.application.ManualLendingRequestIdentity
import com.unifiedledger.application.ManualLendingRequestSnapshot
import com.unifiedledger.application.RequestId
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CounterpartyId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId
import kotlin.time.Instant

/**
 * P7-02.C manual-lending claim-first commit boundary, aligned with
 * [SqlDelightConfirmedManualTransferCommitPort]: one atomic claim/work/receipt transaction that
 * also appends the per-object position history, equivalent replay returns the original receipt,
 * a differing snapshot is an identity conflict, and a typed rejection rolls the claim back so the
 * identity stays retryable.
 *
 * The transaction factory rebuilds the outstanding position from the same claim transaction, so a
 * concurrent collect can never pass admission against a stale balance (L-3).
 */
class SqlDelightConfirmedManualLendingCommitPort private constructor(
    private val database: LedgerDatabase,
) : ConfirmedManualLendingCommitPort {
    constructor(database: LedgerDatabase, driver: SqlDriver) : this(database) {
        configureSqliteConnection(driver)
    }

    override fun commitOnce(
        identity: ManualLendingRequestIdentity,
        requestSnapshot: ManualLendingRequestSnapshot,
        createFormalTransaction: () -> DomainResult<ConfirmedManualLendingCommit>,
    ): ConfirmedManualLendingResult {
        require(identity.ledgerId == requestSnapshot.ledgerId) {
            "Request identity and snapshot must belong to the same ledger"
        }

        return database.transactionWithResult {
            val totalReceived = requestSnapshot.totalReceived
            database.ledgerQueries.claimManualLendingRequest(
                ledger_id = identity.ledgerId.value,
                request_id = identity.requestId.value,
                behavior_code = requestSnapshot.behavior.code,
                counterparty_id = requestSnapshot.counterpartyId.value,
                principal_account_id = requestSnapshot.principalAccountId.value,
                amount_minor = requestSnapshot.amount.minorUnits,
                interest_minor = requestSnapshot.interest.minorUnits,
                fee_minor = requestSnapshot.fee.minorUnits,
                total_received_minor = totalReceived?.minorUnits,
                interest_category_id = requestSnapshot.interestCategoryId?.value,
                currency_code = requestSnapshot.amount.currency.code,
                currency_precision =
                    requestSnapshot.amount.currency.precision
                        .toLong(),
                occurred_at = requestSnapshot.occurredAt.toString(),
                note = requestSnapshot.note,
                confirmation_marker = LENDING_EXPLICIT_MANUAL_SAVE_MARKER,
            )
            val claimed = database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() == 1L
            if (!claimed) {
                return@transactionWithResult resolveExisting(identity, requestSnapshot)
            }

            when (val creation = createFormalTransaction()) {
                is DomainResult.Failure -> {
                    database.ledgerQueries.deleteManualLendingRequest(identity.ledgerId.value, identity.requestId.value)
                    ConfirmedManualLendingResult.Rejected(creation.violation)
                }

                is DomainResult.Success -> {
                    val commit = creation.value
                    require(commit.transaction.transaction.ledgerId == identity.ledgerId) {
                        "Committed transaction must belong to the request ledger"
                    }
                    persistFormalTransaction(commit.transaction)
                    persistPosition(identity.ledgerId.value, requestSnapshot, commit)
                    database.ledgerQueries.insertConfirmedLendingReceipt(
                        identity.ledgerId.value,
                        identity.requestId.value,
                        commit.confirmationId.value,
                        commit.transaction.transaction.id.value,
                    )
                    ConfirmedManualLendingResult.Created(
                        ConfirmedLendingReceipt(
                            confirmationId = commit.confirmationId,
                            transactionId = commit.transaction.transaction.id,
                        ),
                    )
                }
            }
        }
    }

    private fun persistPosition(
        ledger: String,
        request: ManualLendingRequestSnapshot,
        commit: ConfirmedManualLendingCommit,
    ) {
        database.ledgerQueries.insertLendingPosition(
            ledger_id = ledger,
            counterparty_id = request.counterpartyId.value,
            receivable_account_id = commit.position.receivableAccountId.value,
            currency_code = request.amount.currency.code,
            currency_precision =
                request.amount.currency.precision
                    .toLong(),
            principal_balance_minor = commit.position.principalBalanceMinor,
        )
        database.ledgerQueries.updateLendingPositionBalance(
            principal_balance_minor = commit.position.principalBalanceMinor,
            ledger_id = ledger,
            counterparty_id = request.counterpartyId.value,
        )
        val newest = checkNotNull(commit.position.history.lastOrNull()) { "A committed lending event must append one history row" }
        database.ledgerQueries.insertLendingPositionHistory(
            ledger_id = ledger,
            counterparty_id = request.counterpartyId.value,
            entry_id = newest.id,
            behavior_code = newest.behaviorCode.name,
            // The column stores the non-negative magnitude; the behavior code carries the sign.
            amount_minor = kotlin.math.abs(newest.amountMinor),
            principal_balance_after_minor = newest.principalBalanceAfterMinor,
            transaction_id = newest.transactionId.value,
            occurred_at = newest.occurredAt.toString(),
        )
    }

    private fun resolveExisting(
        identity: ManualLendingRequestIdentity,
        snapshot: ManualLendingRequestSnapshot,
    ): ConfirmedManualLendingResult {
        val stored =
            checkNotNull(
                database.ledgerQueries
                    .selectCommittedManualLendingRequest(identity.ledgerId.value, identity.requestId.value) {
                        behaviorCode,
                        counterpartyId,
                        principalAccountId,
                        amountMinor,
                        interestMinor,
                        feeMinor,
                        totalReceivedMinor,
                        interestCategoryId,
                        currencyCode,
                        currencyPrecision,
                        occurredAt,
                        note,
                        confirmationMarker,
                        confirmationId,
                        transactionId,
                        ->
                        StoredLendingCommit(
                            behavior = ManualLendingBehavior.fromCode(behaviorCode),
                            counterpartyId = counterpartyId,
                            principalAccountId = principalAccountId,
                            amountMinor = amountMinor,
                            interestMinor = interestMinor,
                            feeMinor = feeMinor,
                            totalReceivedMinor = totalReceivedMinor,
                            interestCategoryId = interestCategoryId,
                            currencyCode = currencyCode,
                            currencyPrecision = currencyPrecision,
                            occurredAt = occurredAt,
                            note = note,
                            confirmationMarker = confirmationMarker,
                            receipt = ConfirmedLendingReceipt(ConfirmationId(confirmationId), TransactionId(transactionId)),
                        )
                    }.executeAsOneOrNull(),
            ) { "Committed request is missing its receipt" }
        return if (stored.matches(snapshot)) {
            ConfirmedManualLendingResult.NoChange(stored.receipt)
        } else {
            ConfirmedManualLendingResult.RequestIdentityConflict(identity)
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
        database.ledgerQueries.insertTransaction(transaction.id.value, transaction.ledgerId.value, transaction.kind.name)

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
                    currency_precision =
                        posting.amount.currency.precision
                            .toLong(),
                )
            }
        }
    }

    companion object {
        internal fun forPlatformConfiguredDatabase(
            database: LedgerDatabase,
        ): SqlDelightConfirmedManualLendingCommitPort = SqlDelightConfirmedManualLendingCommitPort(database)
    }
}

private data class StoredLendingCommit(
    val behavior: ManualLendingBehavior?,
    val counterpartyId: String,
    val principalAccountId: String,
    val amountMinor: Long,
    val interestMinor: Long,
    val feeMinor: Long,
    val totalReceivedMinor: Long?,
    val interestCategoryId: String?,
    val currencyCode: String,
    val currencyPrecision: Long,
    val occurredAt: String,
    val note: String,
    val confirmationMarker: String,
    val receipt: ConfirmedLendingReceipt,
) {
    fun matches(snapshot: ManualLendingRequestSnapshot): Boolean =
        behavior == snapshot.behavior &&
            counterpartyId == snapshot.counterpartyId.value &&
            principalAccountId == snapshot.principalAccountId.value &&
            amountMinor == snapshot.amount.minorUnits &&
            interestMinor == snapshot.interest.minorUnits &&
            feeMinor == snapshot.fee.minorUnits &&
            totalReceivedMinor == snapshot.totalReceived?.minorUnits &&
            interestCategoryId == snapshot.interestCategoryId?.value &&
            currencyCode == snapshot.amount.currency.code &&
            currencyPrecision ==
            snapshot.amount.currency.precision
                .toLong() &&
            occurredAt == snapshot.occurredAt.toString() &&
            note == snapshot.note &&
            confirmationMarker == LENDING_EXPLICIT_MANUAL_SAVE_MARKER
}

private const val LENDING_EXPLICIT_MANUAL_SAVE_MARKER = "explicit_manual_save"

internal fun com.unifiedledger.data.db.ManualLendingCommitByRequest.toRecord(ledgerId: LedgerId): ManualLendingCommitRecord =
    ManualLendingCommitRecord(
        ledgerId = ledgerId,
        requestId = RequestId(request_id),
        snapshot =
            ManualLendingRequestSnapshot(
                ledgerId = ledgerId,
                behavior = checkNotNull(ManualLendingBehavior.fromCode(behavior_code)),
                counterpartyId = CounterpartyId(counterparty_id),
                principalAccountId = AccountId(principal_account_id),
                amount = Money.ofMinor(amount_minor, CurrencyUnit(currency_code, currency_precision.toInt())),
                interest = Money.ofMinor(interest_minor, CurrencyUnit(currency_code, currency_precision.toInt())),
                fee = Money.ofMinor(fee_minor, CurrencyUnit(currency_code, currency_precision.toInt())),
                totalReceived = total_received_minor?.let { Money.ofMinor(it, CurrencyUnit(currency_code, currency_precision.toInt())) },
                interestCategoryId = interest_category_id?.let(::CategoryId),
                occurredAt = Instant.parse(occurred_at),
                note = note,
            ),
        receipt = ConfirmedLendingReceipt(ConfirmationId(confirmation_id), TransactionId(transaction_id)),
        currentVersionId = TransactionVersionId(current_version_id),
    )

internal fun com.unifiedledger.data.db.ManualLendingCommitByReceipt.toRecord(ledgerId: LedgerId): ManualLendingCommitRecord =
    ManualLendingCommitRecord(
        ledgerId = ledgerId,
        requestId = RequestId(request_id),
        snapshot =
            ManualLendingRequestSnapshot(
                ledgerId = ledgerId,
                behavior = checkNotNull(ManualLendingBehavior.fromCode(behavior_code)),
                counterpartyId = CounterpartyId(counterparty_id),
                principalAccountId = AccountId(principal_account_id),
                amount = Money.ofMinor(amount_minor, CurrencyUnit(currency_code, currency_precision.toInt())),
                interest = Money.ofMinor(interest_minor, CurrencyUnit(currency_code, currency_precision.toInt())),
                fee = Money.ofMinor(fee_minor, CurrencyUnit(currency_code, currency_precision.toInt())),
                totalReceived = total_received_minor?.let { Money.ofMinor(it, CurrencyUnit(currency_code, currency_precision.toInt())) },
                interestCategoryId = interest_category_id?.let(::CategoryId),
                occurredAt = Instant.parse(occurred_at),
                note = note,
            ),
        receipt = ConfirmedLendingReceipt(ConfirmationId(confirmation_id), TransactionId(transaction_id)),
        currentVersionId = TransactionVersionId(current_version_id),
    )
