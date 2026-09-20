package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.CorrectTransactionVersionCommitPort
import com.unifiedledger.application.CorrectTransactionVersionResult
import com.unifiedledger.application.TransactionCorrectionPlan
import com.unifiedledger.application.TransactionCorrectionReceipt
import com.unifiedledger.application.TransactionCorrectionRequestIdentity
import com.unifiedledger.application.TransactionCorrectionRequestSnapshot
import com.unifiedledger.application.TransactionCorrectionTarget
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.P705FailureCode
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.TransactionVoidFact
import com.unifiedledger.domain.TransactionVoidFactKind
import com.unifiedledger.domain.TransactionVoidState
import com.unifiedledger.domain.isP705CorrectionSupportedKind

/**
 * P7-05.B correction commit boundary (spec sections 3.2/4.3; D-156).
 *
 * Claim-first and request-idempotent: the request row is claimed before anything else, and a
 * lost claim is resolved by comparing the stored snapshot, so replay is decided before any CAS
 * evaluation. The new version is appended by `copyCurrentVersionWithNewPostingSet`, whose
 * join on `ledger_transaction_current_version` makes the CAS part of the statement itself:
 * zero changed rows is `StaleCurrentVersion` with nothing written but the (discarded) claim.
 * `occurred_at` and `effective_at` are copied verbatim; only `statistics_at` follows the
 * request. No reconciliation, evidence, lending or import owner is touched.
 */
class SqlDelightTransactionCorrectionCommitPort private constructor(
    private val database: LedgerDatabase,
) : CorrectTransactionVersionCommitPort {
    constructor(database: LedgerDatabase, driver: SqlDriver) : this(database) {
        configureSqliteConnection(driver)
    }

    override fun commitOnce(
        identity: TransactionCorrectionRequestIdentity,
        requestSnapshot: TransactionCorrectionRequestSnapshot,
        plan: (TransactionCorrectionTarget) -> TransactionCorrectionPlan,
    ): CorrectTransactionVersionResult {
        require(identity.ledgerId == requestSnapshot.ledgerId) {
            "Request identity and snapshot must belong to the same ledger"
        }
        return try {
            database.transactionWithResult {
                database.ledgerQueries.claimTransactionCorrectionRequest(
                    ledger_id = identity.ledgerId.value,
                    request_id = identity.requestId.value,
                    transaction_id = requestSnapshot.transactionId.value,
                    expected_current_version_id = requestSnapshot.expectedCurrentVersionId.value,
                    note = requestSnapshot.note,
                    statistics_at = requestSnapshot.statisticsAt.toString(),
                    amount_minor = requestSnapshot.amount.minorUnits,
                    currency_code = requestSnapshot.amount.currency.code,
                    currency_precision =
                        requestSnapshot.amount.currency.precision
                            .toLong(),
                    category_id = requestSnapshot.categoryId.value,
                    funding_account_id = requestSnapshot.fundingAccountId.value,
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
                if (importCreationConfirmationCount(requestSnapshot) > 0L) {
                    return@transactionWithResult reject(identity, P705FailureCode.P705_CREATION_LINEAGE_NOT_SUPPORTED)
                }
                if (!voidState(requestSnapshot).isEffective) {
                    return@transactionWithResult reject(identity, P705FailureCode.P705_TRANSACTION_VOIDED)
                }
                // DP-10 (narrowed) conservative placeholder, batch-ruled pending the spec revision: the
                // first slice is not authorized to invalidate a matched funding leg, so any
                // current version that already carries a reconciliation row is refused rather
                // than silently replaced. Manual EXPENSE/INCOME never carry such a row (V-08/V-12
                // assert the zero-exposure), which is why this path is unreachable today. It is
                // deliberately broader than the frozen "affected funding leg" derivation: the
                // transfer slice that wires DP-10 must narrow it to legs that actually changed,
                // or a note-only correction would be rejected for no reason.
                if (matchedReconciliationCount(requestSnapshot) > 0L) {
                    return@transactionWithResult reject(identity, P705FailureCode.P705_MATCHED_FUNDING_LEG_CHANGED)
                }

                val planned =
                    plan(
                        TransactionCorrectionTarget(
                            transactionId = requestSnapshot.transactionId,
                            kind = kind,
                            currentVersionId = TransactionVersionId(target.current_version_id),
                        ),
                    )
                when (planned) {
                    is TransactionCorrectionPlan.Rejected -> reject(identity, planned.code)
                    is TransactionCorrectionPlan.Commit -> {
                        // CAS: the copy binds the expected current version in its join, so a
                        // concurrent winner makes it change zero rows. Nothing but the claim has
                        // been written at this point, so the stale path is a zero-write path.
                        database.ledgerQueries.copyCurrentVersionWithNewPostingSet(
                            version_id = planned.receipt.versionId.value,
                            new_posting_set_id = planned.postingSetId.value,
                            statistics_at = requestSnapshot.statisticsAt.toString(),
                            note = requestSnapshot.note,
                            transaction_id = requestSnapshot.transactionId.value,
                            ledger_id = identity.ledgerId.value,
                            expected_current_version_id = requestSnapshot.expectedCurrentVersionId.value,
                        )
                        if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
                            database.ledgerQueries.deleteTransactionCorrectionRequest(
                                identity.ledgerId.value,
                                identity.requestId.value,
                            )
                            return@transactionWithResult CorrectTransactionVersionResult.StaleCurrentVersion
                        }
                        database.ledgerQueries.insertPostingSet(
                            posting_set_id = planned.postingSetId.value,
                            ledger_id = identity.ledgerId.value,
                        )
                        planned.postings.forEachIndexed { index, posting ->
                            database.ledgerQueries.insertPosting(
                                posting_id = posting.id.value,
                                posting_set_id = planned.postingSetId.value,
                                ledger_id = identity.ledgerId.value,
                                posting_index = index.toLong(),
                                account_id = posting.accountId.value,
                                amount_minor = posting.amount.minorUnits,
                                currency_code = posting.amount.currency.code,
                                currency_precision =
                                    posting.amount.currency.precision
                                        .toLong(),
                            )
                        }
                        database.ledgerQueries.compareAndSetCurrentVersion(
                            planned.receipt.versionId.value,
                            requestSnapshot.transactionId.value,
                            identity.ledgerId.value,
                            requestSnapshot.expectedCurrentVersionId.value,
                        )
                        check(database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() == 1L) {
                            "Correction CAS must advance exactly one current-version row"
                        }
                        database.ledgerQueries.insertTransactionCorrectionReceipt(
                            ledger_id = identity.ledgerId.value,
                            request_id = identity.requestId.value,
                            confirmation_id = planned.receipt.confirmationId.value,
                            transaction_id = planned.receipt.transactionId.value,
                            version_id = planned.receipt.versionId.value,
                            expected_current_version_id = planned.receipt.expectedCurrentVersionId.value,
                        )
                        CorrectTransactionVersionResult.Created(planned.receipt)
                    }
                }
            }
        } catch (failure: Exception) {
            if (isSqliteConstraintFailure(failure)) {
                // The whole transaction already rolled back, so the identity stays retryable and
                // no partial version/posting/receipt row survived.
                CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_CONSTRAINT_VIOLATION)
            } else {
                throw failure
            }
        }
    }

    private fun importCreationConfirmationCount(requestSnapshot: TransactionCorrectionRequestSnapshot): Long =
        database.ledgerQueries
            .importCreationConfirmationCountForTransaction(
                requestSnapshot.ledgerId.value,
                requestSnapshot.transactionId.value,
            ).executeAsOne()

    private fun matchedReconciliationCount(requestSnapshot: TransactionCorrectionRequestSnapshot): Long =
        database.ledgerQueries
            .matchedCurrentVersionReconciliationCount(
                requestSnapshot.ledgerId.value,
                requestSnapshot.transactionId.value,
            ).executeAsOne()

    private fun voidState(requestSnapshot: TransactionCorrectionRequestSnapshot): TransactionVoidState =
        TransactionVoidState.of(
            database.ledgerQueries
                .transactionVoidFactsForTransaction(
                    requestSnapshot.ledgerId.value,
                    requestSnapshot.transactionId.value,
                ) { sequence, factKind, _, _, _, _, _ ->
                    TransactionVoidFact(
                        sequence = sequence.toInt(),
                        factKind =
                            TransactionVoidFactKind.fromStorage(factKind)
                                ?: TransactionVoidFactKind.VOID,
                    )
                }.executeAsList(),
        )

    private fun reject(
        identity: TransactionCorrectionRequestIdentity,
        code: P705FailureCode,
    ): CorrectTransactionVersionResult {
        database.ledgerQueries.deleteTransactionCorrectionRequest(identity.ledgerId.value, identity.requestId.value)
        return CorrectTransactionVersionResult.Rejected(code)
    }

    private fun resolveExisting(
        identity: TransactionCorrectionRequestIdentity,
        snapshot: TransactionCorrectionRequestSnapshot,
    ): CorrectTransactionVersionResult {
        val existing =
            checkNotNull(
                database.ledgerQueries
                    .selectCommittedTransactionCorrectionRequest(identity.ledgerId.value, identity.requestId.value) {
                        transactionId,
                        expectedCurrentVersionId,
                        note,
                        statisticsAt,
                        amountMinor,
                        currencyCode,
                        currencyPrecision,
                        categoryId,
                        fundingAccountId,
                        confirmationMarker,
                        confirmationId,
                        versionId,
                        ->
                        StoredCorrection(
                            transactionId = transactionId,
                            expectedCurrentVersionId = expectedCurrentVersionId,
                            note = note,
                            statisticsAt = statisticsAt,
                            amountMinor = amountMinor,
                            currencyCode = currencyCode,
                            currencyPrecision = currencyPrecision,
                            categoryId = categoryId,
                            fundingAccountId = fundingAccountId,
                            confirmationMarker = confirmationMarker,
                            confirmationId = confirmationId,
                            versionId = versionId,
                        )
                    }.executeAsOneOrNull(),
            ) { "Committed correction is missing its receipt" }
        return if (existing.matches(snapshot)) {
            CorrectTransactionVersionResult.NoChange(
                TransactionCorrectionReceipt(
                    confirmationId = ConfirmationId(existing.confirmationId),
                    transactionId = TransactionId(existing.transactionId),
                    versionId = TransactionVersionId(existing.versionId),
                    expectedCurrentVersionId = TransactionVersionId(existing.expectedCurrentVersionId),
                ),
            )
        } else {
            CorrectTransactionVersionResult.RequestIdentityConflict(identity)
        }
    }
}

private const val EXPLICIT_MANUAL_SAVE_MARKER = "explicit_manual_save"

private data class StoredCorrection(
    val transactionId: String,
    val expectedCurrentVersionId: String,
    val note: String?,
    val statisticsAt: String,
    val amountMinor: Long,
    val currencyCode: String,
    val currencyPrecision: Long,
    val categoryId: String,
    val fundingAccountId: String,
    val confirmationMarker: String,
    val confirmationId: String,
    val versionId: String,
) {
    fun matches(snapshot: TransactionCorrectionRequestSnapshot): Boolean =
        transactionId == snapshot.transactionId.value &&
            expectedCurrentVersionId == snapshot.expectedCurrentVersionId.value &&
            note == snapshot.note &&
            statisticsAt == snapshot.statisticsAt.toString() &&
            amountMinor == snapshot.amount.minorUnits &&
            currencyCode == snapshot.amount.currency.code &&
            currencyPrecision ==
            snapshot.amount.currency.precision
                .toLong() &&
            categoryId == snapshot.categoryId.value &&
            fundingAccountId == snapshot.fundingAccountId.value &&
            confirmationMarker == EXPLICIT_MANUAL_SAVE_MARKER
}
