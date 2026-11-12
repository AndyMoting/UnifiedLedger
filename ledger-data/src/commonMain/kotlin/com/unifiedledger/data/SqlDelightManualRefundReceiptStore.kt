package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.ManualRefundReceiptCommitPort
import com.unifiedledger.application.ManualRefundReceiptCommitResult
import com.unifiedledger.application.ManualRefundReceiptIds
import com.unifiedledger.application.ManualRefundReceiptReadPort
import com.unifiedledger.application.ManualRefundReceiptReadRecord
import com.unifiedledger.application.ManualRefundReceiptRecord
import com.unifiedledger.application.ManualRefundReceiptRequest
import com.unifiedledger.application.ManualRefundRejectionCode
import com.unifiedledger.application.RequestId
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.RefundReceiptCommand
import com.unifiedledger.domain.RefundReceiptIds
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.createRefundReceipt

/**
 * P8-03 (D-312; spec sections 2.5/2.6/4). The product-owned manual refund relationship store:
 * the atomic commit boundary and the unknown-commit read-back.
 *
 * Every refusal and replay is decided INSIDE the one write transaction: the relationship row is
 * the idempotency record (same id + same fingerprint replays it, same id + different fingerprint
 * conflicts), the original is re-checked against the same seven candidate conditions, and the
 * remaining capacity is recomputed from the effective refund rows. A rejection returns before
 * the first write. A thrown storage failure is resolved from that same relationship row: the
 * write may have committed before the failure surfaced, so an exact fingerprint answers
 * [ManualRefundReceiptCommitResult.Replayed] and only the absence of a replay stays
 * [ManualRefundReceiptCommitResult.Unavailable] (spec section 2.6).
 */
class SqlDelightManualRefundReceiptStore private constructor(
    private val database: LedgerDatabase,
) : ManualRefundReceiptCommitPort,
    ManualRefundReceiptReadPort {
    constructor(database: LedgerDatabase, driver: SqlDriver) : this(database) {
        configureSqliteConnection(driver)
    }

    private val catalogStore: SqlDelightCatalogStore =
        SqlDelightCatalogStore.forPlatformConfiguredDatabase(database)

    override fun commitOnce(
        request: ManualRefundReceiptRequest,
        requestFingerprint: String,
        mintIds: () -> ManualRefundReceiptIds,
    ): ManualRefundReceiptCommitResult =
        try {
            database.transactionWithResult { commit(request, requestFingerprint, mintIds) }
        } catch (failure: Exception) {
            // The failure may have surfaced after the commit landed, so the row decides: a
            // matching fingerprint is the replay, anything else remains the typed unavailable.
            resolvePossiblyCommitted(request, requestFingerprint)
        }

    override fun findByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualRefundReceiptReadRecord? =
        database.ledgerQueries
            .selectManualRefundReceiptByRequest(ledgerId.value, requestId.value) {
                relationshipId,
                fingerprint,
                refundTransactionId,
                ->
                ManualRefundReceiptReadRecord(
                    requestFingerprint = fingerprint,
                    receipt =
                        ManualRefundReceiptRecord(
                            relationshipId = relationshipId,
                            refundTransactionId = TransactionId(refundTransactionId),
                        ),
                )
            }.executeAsOneOrNull()

    private fun resolvePossiblyCommitted(
        request: ManualRefundReceiptRequest,
        requestFingerprint: String,
    ): ManualRefundReceiptCommitResult =
        try {
            val record = findByRequest(request.ledgerId, request.requestId)
            if (record != null && record.requestFingerprint == requestFingerprint) {
                ManualRefundReceiptCommitResult.Replayed(record.receipt)
            } else {
                ManualRefundReceiptCommitResult.Unavailable
            }
        } catch (failure: Exception) {
            ManualRefundReceiptCommitResult.Unavailable
        }

    private fun commit(
        request: ManualRefundReceiptRequest,
        requestFingerprint: String,
        mintIds: () -> ManualRefundReceiptIds,
    ): ManualRefundReceiptCommitResult {
        val ledger = request.ledgerId.value
        val existing =
            database.ledgerQueries
                .selectManualRefundReceiptByRequest(ledger, request.requestId.value) {
                    relationshipId,
                    storedFingerprint,
                    refundTransactionId,
                    ->
                    Triple(relationshipId, storedFingerprint, refundTransactionId)
                }.executeAsOneOrNull()
        if (existing != null) {
            val receipt = ManualRefundReceiptRecord(existing.first, TransactionId(existing.third))
            return if (existing.second == requestFingerprint) {
                ManualRefundReceiptCommitResult.Replayed(receipt)
            } else {
                ManualRefundReceiptCommitResult.Rejected(ManualRefundRejectionCode.REFUND_REQUEST_CONFLICT)
            }
        }

        // A non-positive amount is the same user-correctable amount rejection as the structural
        // cap: the frozen closed set has no separate code and no vector exercises it.
        if (request.amount.minorUnits <= 0L) {
            return reject(ManualRefundRejectionCode.REFUND_EXCEEDS_STRUCTURAL_CAP)
        }
        val effective =
            database.ledgerQueries
                .transactionEffectiveState(ledger, request.originalTransactionId.value)
                .executeAsOneOrNull()
                ?: return reject(ManualRefundRejectionCode.ORIGINAL_NOT_REFUNDABLE)
        if (effective == 0L) {
            // It was a candidate when the user picked it and has been voided since (spec 2.6).
            return reject(ManualRefundRejectionCode.ORIGINAL_NO_LONGER_REFUNDABLE)
        }
        val original =
            database.ledgerQueries
                .selectManualRefundableOriginal(ledger, request.originalTransactionId.value) {
                    categoryId,
                    structuralCapMinor,
                    currencyCode,
                    currencyPrecision,
                    ->
                    OriginalExpense(
                        categoryId = categoryId,
                        structuralCapMinor = structuralCapMinor,
                        currencyCode = currencyCode,
                        currencyPrecision = currencyPrecision,
                    )
                }.executeAsOneOrNull()
                ?: return reject(ManualRefundRejectionCode.ORIGINAL_NOT_REFUNDABLE)
        val currency = request.amount.currency
        if (
            currency.code != original.currencyCode ||
            currency.precision.toLong() != original.currencyPrecision
        ) {
            return reject(ManualRefundRejectionCode.ORIGINAL_NOT_REFUNDABLE)
        }
        val catalog =
            catalogStore.loadCurrent(request.ledgerId)
                ?: return reject(ManualRefundRejectionCode.REFUND_STORE_UNAVAILABLE)
        val category =
            catalog.categories.firstOrNull { it.id.value == original.categoryId }
                ?: return reject(ManualRefundRejectionCode.REFUND_CATEGORY_INELIGIBLE)
        if (
            !category.active ||
            category.kind != CategoryKind.EXPENSE ||
            category.parentId == null ||
            category.postingAccountId == null ||
            category.ledgerId != request.ledgerId
        ) {
            return reject(ManualRefundRejectionCode.REFUND_CATEGORY_INELIGIBLE)
        }
        val destination =
            catalog.accounts.firstOrNull { it.id == request.destinationAccountId }
                ?: return reject(ManualRefundRejectionCode.REFUND_ACCOUNT_INELIGIBLE)
        if (
            destination.ledgerId != request.ledgerId ||
            destination.kind != AccountKind.ASSET ||
            !destination.ownedByUser ||
            !destination.realAccount ||
            !destination.active ||
            destination.currency != request.amount.currency
        ) {
            return reject(ManualRefundRejectionCode.REFUND_ACCOUNT_INELIGIBLE)
        }
        if (request.amount.minorUnits > original.structuralCapMinor) {
            return reject(ManualRefundRejectionCode.REFUND_EXCEEDS_STRUCTURAL_CAP)
        }
        val refunded =
            database.ledgerQueries
                .selectManualRefundRefundedTotal(
                    ledger,
                    request.originalTransactionId.value,
                    request.amount.currency.code,
                ).executeAsOne()
        if (request.amount.minorUnits > original.structuralCapMinor - refunded) {
            return reject(ManualRefundRejectionCode.REFUND_EXCEEDS_REMAINING)
        }

        val ids = mintIds()
        val created =
            createRefundReceipt(
                catalog,
                RefundReceiptCommand(
                    ledgerId = request.ledgerId,
                    originalTransactionId = request.originalTransactionId,
                    amount = request.amount,
                    categoryId = category.id,
                    destinationAccountId = request.destinationAccountId,
                    times = TransactionTimes.collapsed(request.arrivedAt),
                ),
                RefundReceiptIds(
                    transactionId = ids.transactionId,
                    versionId = ids.versionId,
                    postingSetId = ids.postingSetId,
                    destinationPostingId = ids.destinationPostingId,
                    expensePostingId = ids.expensePostingId,
                ),
            )
        when (created) {
            is DomainResult.Failure ->
                return reject(
                    when (created.violation) {
                        DomainViolation.InvalidRefundReceipt -> ManualRefundRejectionCode.REFUND_CATEGORY_INELIGIBLE
                        DomainViolation.ArithmeticOverflow -> ManualRefundRejectionCode.REFUND_EXCEEDS_STRUCTURAL_CAP
                        else -> ManualRefundRejectionCode.REFUND_STORE_UNAVAILABLE
                    },
                )

            is DomainResult.Success -> persistFormalTransaction(created.value.formalTransaction)
        }
        // V-16: the refund's destination asset leg starts its own pending reconciliation, so it
        // can be linked to evidence independently; the original payment leg's reconciliation
        // state is never read or written here.
        database.ledgerQueries.insertP408PostingReconciliation(
            ledger_id = ledger,
            reconciliation_id = MANUAL_REFUND_RECONCILIATION_PREFIX + ids.destinationPostingId.value,
            posting_id = ids.destinationPostingId.value,
        )
        database.ledgerQueries.insertManualRefundReceipt(
            ledger_id = ledger,
            relationship_id = ids.relationshipId,
            request_id = request.requestId.value,
            request_fingerprint = requestFingerprint,
            original_transaction_id = request.originalTransactionId.value,
            refund_transaction_id = ids.transactionId.value,
            amount_minor = request.amount.minorUnits,
            currency_code = currency.code,
            currency_precision = currency.precision.toLong(),
            destination_account_id = request.destinationAccountId.value,
            category_id = category.id.value,
            arrived_at = request.arrivedAt.toString(),
            confirmed_at = request.confirmedAt.toString(),
            created_at = request.confirmedAt.toString(),
            confirmation_marker = EXPLICIT_MANUAL_CONFIRM_MARKER,
        )
        return ManualRefundReceiptCommitResult.Created(
            ManualRefundReceiptRecord(
                relationshipId = ids.relationshipId,
                refundTransactionId = ids.transactionId,
            ),
        )
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
                val postingCurrency = posting.amount.currency
                database.ledgerQueries.insertPosting(
                    posting_id = posting.id.value,
                    posting_set_id = postingSet.id.value,
                    ledger_id = transaction.ledgerId.value,
                    posting_index = index.toLong(),
                    account_id = posting.accountId.value,
                    amount_minor = posting.amount.minorUnits,
                    currency_code = postingCurrency.code,
                    currency_precision = postingCurrency.precision.toLong(),
                )
            }
        }
    }

    private fun reject(code: ManualRefundRejectionCode): ManualRefundReceiptCommitResult = ManualRefundReceiptCommitResult.Rejected(code)

    companion object {
        /**
         * The composition-root entry point for a platform-configured [LedgerDatabase]: the database
         * already carries the platform's connection configuration (the Android handle owns the
         * driver), so this deliberately skips [configureSqliteConnection]. Public because both
         * composition roots (D-312; spec section 4) construct the store through it.
         */
        fun forPlatformConfiguredDatabase(
            database: LedgerDatabase,
        ): SqlDelightManualRefundReceiptStore = SqlDelightManualRefundReceiptStore(database)
    }
}

private data class OriginalExpense(
    val categoryId: String,
    val structuralCapMinor: Long,
    val currencyCode: String,
    val currencyPrecision: Long,
)

private const val EXPLICIT_MANUAL_CONFIRM_MARKER = "explicit_manual_confirm"
private const val MANUAL_REFUND_RECONCILIATION_PREFIX = "manual-refund-reconciliation-"
