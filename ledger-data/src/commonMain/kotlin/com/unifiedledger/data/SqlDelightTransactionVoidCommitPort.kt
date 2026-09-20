package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.TransactionVoidCommitPort
import com.unifiedledger.application.TransactionVoidPlan
import com.unifiedledger.application.TransactionVoidReceipt
import com.unifiedledger.application.TransactionVoidRequestIdentity
import com.unifiedledger.application.TransactionVoidRequestSnapshot
import com.unifiedledger.application.TransactionVoidTarget
import com.unifiedledger.application.VoidTransactionResult
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.P705FailureCode
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.TransactionVoidFact
import com.unifiedledger.domain.TransactionVoidFactKind
import com.unifiedledger.domain.TransactionVoidState
import com.unifiedledger.domain.isP705CorrectionSupportedKind
import com.unifiedledger.domain.voidAppendRejection

/**
 * P7-05.C void/restore commit boundary (spec sections 3.3/4.3; D-156).
 *
 * Claim-first and request-idempotent over the merged void/restore family: one request table
 * carries `fact_kind`, and a lost claim is resolved by comparing the stored snapshot before any
 * other evaluation. The append writes exactly one immutable fact plus its receipt in the same
 * transaction as the claim; every failure path discards the claim, so the identity stays
 * retryable and no orphan fact or receipt can exist. No reconciliation, evidence, lending or
 * import owner is written.
 */
class SqlDelightTransactionVoidCommitPort private constructor(
    private val database: LedgerDatabase,
) : TransactionVoidCommitPort {
    constructor(database: LedgerDatabase, driver: SqlDriver) : this(database) {
        configureSqliteConnection(driver)
    }

    override fun commitOnce(
        identity: TransactionVoidRequestIdentity,
        requestSnapshot: TransactionVoidRequestSnapshot,
        plan: (TransactionVoidTarget) -> TransactionVoidPlan,
    ): VoidTransactionResult {
        require(identity.ledgerId == requestSnapshot.ledgerId) {
            "Request identity and snapshot must belong to the same ledger"
        }
        return try {
            database.transactionWithResult {
                database.ledgerQueries.claimTransactionVoidRequest(
                    ledger_id = identity.ledgerId.value,
                    request_id = identity.requestId.value,
                    transaction_id = requestSnapshot.transactionId.value,
                    fact_kind = requestSnapshot.factKind.storageValue,
                    reason_code = requestSnapshot.reason.code.storageValue,
                    reason_note = requestSnapshot.reason.note,
                    confirmation_marker = EXPLICIT_MANUAL_SAVE_MARKER,
                )
                if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
                    return@transactionWithResult resolveExisting(identity, requestSnapshot)
                }

                val target =
                    database.ledgerQueries
                        .transactionCorrectionTarget(identity.ledgerId.value, requestSnapshot.transactionId.value)
                        .executeAsOneOrNull()
                if (target == null) {
                    return@transactionWithResult reject(identity, P705FailureCode.P705_TRANSACTION_NOT_FOUND)
                }
                val kind = TransactionKind.valueOf(target.kind)
                if (!isP705CorrectionSupportedKind(kind)) {
                    return@transactionWithResult reject(identity, P705FailureCode.P705_KIND_NOT_SUPPORTED)
                }
                if (importCreationConfirmationCount(identity.ledgerId.value, requestSnapshot.transactionId.value) > 0L) {
                    return@transactionWithResult reject(identity, P705FailureCode.P705_CREATION_LINEAGE_NOT_SUPPORTED)
                }
                val state = voidState(identity.ledgerId.value, requestSnapshot.transactionId.value)
                val appendRejection = voidAppendRejection(state, requestSnapshot.factKind)
                if (appendRejection != null) {
                    return@transactionWithResult reject(identity, appendRejection)
                }
                if (
                    requestSnapshot.factKind == TransactionVoidFactKind.VOID &&
                    hasEffectiveRefundLink(identity.ledgerId.value, requestSnapshot.transactionId.value)
                ) {
                    return@transactionWithResult reject(
                        identity,
                        P705FailureCode.P705_REFUND_LINKED_VOID_NOT_SUPPORTED,
                    )
                }

                val planned =
                    plan(
                        TransactionVoidTarget(
                            transactionId = requestSnapshot.transactionId,
                            kind = kind,
                            currentVersionId = TransactionVersionId(target.current_version_id),
                            postings = currentVersionPostings(identity.ledgerId.value, requestSnapshot.transactionId.value),
                            voidState = state,
                        ),
                    )
                when (planned) {
                    is TransactionVoidPlan.Rejected -> reject(identity, planned.code)
                    is TransactionVoidPlan.Commit -> {
                        database.ledgerQueries.insertTransactionVoidFact(
                            ledger_id = identity.ledgerId.value,
                            transaction_id = requestSnapshot.transactionId.value,
                            sequence = (state.depth + 1).toLong(),
                            fact_id = planned.receipt.factId,
                            fact_kind = planned.receipt.factKind.storageValue,
                            reason_code = requestSnapshot.reason.code.storageValue,
                            reason_note = requestSnapshot.reason.note,
                            request_id = identity.requestId.value,
                            confirmation_id = planned.receipt.confirmationId.value,
                            created_at = planned.createdAt.toString(),
                        )
                        database.ledgerQueries.insertTransactionVoidReceipt(
                            ledger_id = identity.ledgerId.value,
                            request_id = identity.requestId.value,
                            confirmation_id = planned.receipt.confirmationId.value,
                            transaction_id = planned.receipt.transactionId.value,
                            fact_id = planned.receipt.factId,
                            fact_kind = planned.receipt.factKind.storageValue,
                        )
                        VoidTransactionResult.Created(planned.receipt)
                    }
                }
            }
        } catch (failure: Exception) {
            if (isSqliteConstraintFailure(failure) || isVoidGuardFailure(failure)) {
                // The whole transaction already rolled back, so the identity stays retryable and
                // no fact/receipt pair survived a guard or uniqueness failure.
                VoidTransactionResult.Rejected(P705FailureCode.P705_CONSTRAINT_VIOLATION)
            } else {
                throw failure
            }
        }
    }

    private fun importCreationConfirmationCount(
        ledgerId: String,
        transactionId: String,
    ): Long =
        database.ledgerQueries
            .importCreationConfirmationCountForTransaction(ledgerId, transactionId)
            .executeAsOne()

    /**
     * DP-13's effective linked-refund probe of the product path: the import credit flow's
     * decision snapshot (`original_transaction_id`) joined to the confirmation that created the
     * refund transaction, filtered by the single effective-predicate view. The rgXX_ refund
     * silo has no product writer and is not read (see the query comment in `Ledger.sq`).
     */
    private fun hasEffectiveRefundLink(
        ledgerId: String,
        transactionId: String,
    ): Boolean =
        database.ledgerQueries
            .productLinkedRefundCountForTransaction(ledgerId, transactionId)
            .executeAsOne() > 0L

    private fun voidState(
        ledgerId: String,
        transactionId: String,
    ): TransactionVoidState =
        TransactionVoidState.of(
            database.ledgerQueries
                .transactionVoidFactsForTransaction(ledgerId, transactionId) { sequence, factKind, _, _, _, _, _ ->
                    TransactionVoidFact(
                        sequence = sequence.toInt(),
                        factKind =
                            TransactionVoidFactKind.fromStorage(factKind)
                                ?: TransactionVoidFactKind.VOID,
                    )
                }.executeAsList(),
        )

    private fun currentVersionPostings(
        ledgerId: String,
        transactionId: String,
    ): List<Posting> =
        database.ledgerQueries
            .currentVersionPostingsForTransaction(ledgerId, transactionId) {
                postingId,
                _,
                accountId,
                amountMinor,
                currencyCode,
                currencyPrecision,
                ->
                Posting(
                    id = PostingId(postingId),
                    accountId = AccountId(accountId),
                    amount =
                        Money.ofMinor(
                            amountMinor,
                            CurrencyUnit(currencyCode, currencyPrecision.toInt()),
                        ),
                )
            }.executeAsList()

    private fun reject(
        identity: TransactionVoidRequestIdentity,
        code: P705FailureCode,
    ): VoidTransactionResult {
        database.ledgerQueries.deleteTransactionVoidRequest(identity.ledgerId.value, identity.requestId.value)
        return VoidTransactionResult.Rejected(code)
    }

    private fun resolveExisting(
        identity: TransactionVoidRequestIdentity,
        snapshot: TransactionVoidRequestSnapshot,
    ): VoidTransactionResult {
        val existing =
            checkNotNull(
                database.ledgerQueries
                    .selectCommittedTransactionVoidRequest(identity.ledgerId.value, identity.requestId.value) {
                        transactionId,
                        factKind,
                        reasonCode,
                        reasonNote,
                        confirmationMarker,
                        confirmationId,
                        factId,
                        ->
                        StoredVoidFact(
                            transactionId = transactionId,
                            factKind = factKind,
                            reasonCode = reasonCode,
                            reasonNote = reasonNote,
                            confirmationMarker = confirmationMarker,
                            confirmationId = confirmationId,
                            factId = factId,
                        )
                    }.executeAsOneOrNull(),
            ) { "Committed void/restore is missing its receipt" }
        return if (existing.matches(snapshot)) {
            VoidTransactionResult.NoChange(
                TransactionVoidReceipt(
                    confirmationId = ConfirmationId(existing.confirmationId),
                    transactionId = TransactionId(existing.transactionId),
                    factId = existing.factId,
                    factKind =
                        TransactionVoidFactKind.fromStorage(existing.factKind)
                            ?: TransactionVoidFactKind.VOID,
                ),
            )
        } else {
            VoidTransactionResult.RequestIdentityConflict(identity)
        }
    }
}

private const val EXPLICIT_MANUAL_SAVE_MARKER = "explicit_manual_save"

private data class StoredVoidFact(
    val transactionId: String,
    val factKind: String,
    val reasonCode: String,
    val reasonNote: String?,
    val confirmationMarker: String,
    val confirmationId: String,
    val factId: String,
) {
    fun matches(snapshot: TransactionVoidRequestSnapshot): Boolean =
        transactionId == snapshot.transactionId.value &&
            factKind == snapshot.factKind.storageValue &&
            reasonCode == snapshot.reason.code.storageValue &&
            reasonNote == snapshot.reason.note &&
            confirmationMarker == EXPLICIT_MANUAL_SAVE_MARKER
}

/**
 * The frozen `transaction_void_fact` guard messages (sequence/alternation/append-only).
 *
 * Deliberately brittle and acknowledged as such: the JDBC SQLite driver surfaces a trigger
 * `RAISE(ABORT, ...)` as a plain message with no typed code, so classification has to match the
 * frozen message text. The alternative — treating every SQLite failure as a guard failure —
 * would silently swallow unrelated errors, which is worse. If the driver ever exposes a code,
 * this is the place to switch.
 */
private fun isVoidGuardFailure(failure: Throwable): Boolean {
    var current: Throwable? = failure
    while (current != null) {
        val message = current.message ?: ""
        if (message.contains("void fact") || message.contains("void cycle")) return true
        current = current.cause
    }
    return false
}
