package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.ConfirmedManualTransferCommit
import com.unifiedledger.application.ConfirmedManualTransferCommitPort
import com.unifiedledger.application.ConfirmedManualTransferResult
import com.unifiedledger.application.ConfirmedTransferReceipt
import com.unifiedledger.application.ManualTransferRequestIdentity
import com.unifiedledger.application.ManualTransferRequestSnapshot
import com.unifiedledger.application.TagMerchantAuthority
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.MerchantId
import com.unifiedledger.domain.TagId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.encodeAnnotationMerchantId
import com.unifiedledger.domain.encodeAnnotationTagIds
import kotlin.time.Instant

/**
 * P7-02.B manual-transfer claim-first commit boundary, aligned with
 * [SqlDelightConfirmedManualExpenseCommitPort]: one atomic claim/work/receipt transaction,
 * equivalent replay returns the original receipt, a differing snapshot is an identity conflict,
 * and a typed rejection rolls the claim back so the identity stays retryable. P7-08 08.B-1
 * (D-221) additionally writes the initial annotation aggregate in that same transaction.
 */
class SqlDelightConfirmedManualTransferCommitPort private constructor(
    private val database: LedgerDatabase,
) : ConfirmedManualTransferCommitPort {
    constructor(database: LedgerDatabase, driver: SqlDriver) : this(database) {
        configureSqliteConnection(driver)
    }

    private val catalogReader: SqlDelightTagMerchantCatalogStore =
        SqlDelightTagMerchantCatalogStore.forPlatformConfiguredDatabase(database)

    override fun commitOnce(
        identity: ManualTransferRequestIdentity,
        requestSnapshot: ManualTransferRequestSnapshot,
        createdAt: Instant,
        createFormalTransaction: () -> DomainResult<ConfirmedManualTransferCommit>,
    ): ConfirmedManualTransferResult {
        require(identity.ledgerId == requestSnapshot.ledgerId) {
            "Request identity and snapshot must belong to the same ledger"
        }

        return database.transactionWithResult {
            database.ledgerQueries.claimManualTransferRequest(
                ledger_id = identity.ledgerId.value,
                request_id = identity.requestId.value,
                source_account_id = requestSnapshot.sourceAccountId.value,
                destination_account_id = requestSnapshot.destinationAccountId.value,
                destination_credit_minor = requestSnapshot.destinationCredit.minorUnits,
                currency_code = requestSnapshot.destinationCredit.currency.code,
                currency_precision =
                    requestSnapshot.destinationCredit.currency.precision
                        .toLong(),
                fee_minor = requestSnapshot.fee.minorUnits,
                fee_category_id = requestSnapshot.feeCategoryId?.value,
                occurred_at = requestSnapshot.occurredAt.toString(),
                note = requestSnapshot.note,
                confirmation_marker = EXPLICIT_MANUAL_SAVE_MARKER,
            )
            val claimed = database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() == 1L
            if (!claimed) {
                return@transactionWithResult resolveExisting(identity, requestSnapshot)
            }

            when (val creation = createFormalTransaction()) {
                is DomainResult.Failure -> {
                    database.ledgerQueries.deleteManualTransferRequest(identity.ledgerId.value, identity.requestId.value)
                    ConfirmedManualTransferResult.Rejected(creation.violation)
                }

                is DomainResult.Success -> {
                    require(creation.value.transaction.transaction.ledgerId == identity.ledgerId) {
                        "Committed transaction must belong to the request ledger"
                    }
                    persistFormalTransaction(creation.value.transaction)
                    appendAnnotation(identity, requestSnapshot, creation.value, createdAt)
                    database.ledgerQueries.insertConfirmedTransferReceipt(
                        identity.ledgerId.value,
                        identity.requestId.value,
                        creation.value.confirmationId.value,
                        creation.value.transaction.transaction.id.value,
                    )
                    ConfirmedManualTransferResult.Created(
                        ConfirmedTransferReceipt(
                            confirmationId = creation.value.confirmationId,
                            transactionId = creation.value.transaction.transaction.id,
                        ),
                    )
                }
            }
        }
    }

    private fun appendAnnotation(
        identity: ManualTransferRequestIdentity,
        requestSnapshot: ManualTransferRequestSnapshot,
        commit: ConfirmedManualTransferCommit,
        createdAt: Instant,
    ) {
        requireActiveCatalogItems(identity.ledgerId, requestSnapshot.tagIds, requestSnapshot.merchantId)
        database.ledgerQueries.updateManualTransferRequestAnnotation(
            annotation_tag_ids = encodeAnnotationTagIds(requestSnapshot.tagIds),
            annotation_merchant_id = encodeAnnotationMerchantId(requestSnapshot.merchantId),
            ledger_id = identity.ledgerId.value,
            request_id = identity.requestId.value,
        )
        database.requireOneAnnotationRowChanged()
        database.appendManualCreateAnnotation(
            ledgerId = identity.ledgerId.value,
            transactionId = commit.transaction.transaction.id.value,
            requestId = identity.requestId.value,
            currentVersionId = commit.transaction.transaction.currentVersionId.value,
            tagIds = requestSnapshot.tagIds,
            merchantId = requestSnapshot.merchantId,
            createdAt = createdAt,
        )
    }

    private fun requireActiveCatalogItems(
        ledgerId: LedgerId,
        tagIds: Set<TagId>,
        merchantId: MerchantId?,
    ) {
        if (tagIds.isEmpty() && merchantId == null) return
        val authority: TagMerchantAuthority = requireNotNull(catalogReader.load(ledgerId)) {
            "Manual create with an association requires an existing tag/merchant catalog"
        }
        tagIds.forEach { tagId ->
            val tag = authority.tags.firstOrNull { it.id == tagId.value }
            require(tag != null && tag.active && !tag.tombstoned) {
                "Manual create references an unknown, inactive or tombstoned tag"
            }
        }
        if (merchantId != null) {
            val merchant = authority.merchants.firstOrNull { it.id == merchantId.value }
            require(merchant != null && merchant.active && !merchant.tombstoned) {
                "Manual create references an unknown, inactive or tombstoned merchant"
            }
        }
    }

    private fun resolveExisting(
        identity: ManualTransferRequestIdentity,
        snapshot: ManualTransferRequestSnapshot,
    ): ConfirmedManualTransferResult {
        val stored =
            checkNotNull(
                database.ledgerQueries
                    .selectCommittedManualTransferRequest(identity.ledgerId.value, identity.requestId.value) {
                        sourceAccountId,
                        destinationAccountId,
                        destinationCreditMinor,
                        currencyCode,
                        currencyPrecision,
                        feeMinor,
                        feeCategoryId,
                        occurredAt,
                        note,
                        confirmationMarker,
                        annotationTagIds,
                        annotationMerchantId,
                        confirmationId,
                        transactionId,
                        ->
                        StoredTransferCommit(
                            sourceAccountId = sourceAccountId,
                            destinationAccountId = destinationAccountId,
                            destinationCreditMinor = destinationCreditMinor,
                            currencyCode = currencyCode,
                            currencyPrecision = currencyPrecision,
                            feeMinor = feeMinor,
                            feeCategoryId = feeCategoryId,
                            occurredAt = occurredAt,
                            note = note,
                            confirmationMarker = confirmationMarker,
                            annotationTagIds = annotationTagIds,
                            annotationMerchantId = annotationMerchantId,
                            receipt = ConfirmedTransferReceipt(ConfirmationId(confirmationId), TransactionId(transactionId)),
                        )
                    }.executeAsOneOrNull(),
            ) { "Committed request is missing its receipt" }
        return if (stored.matches(snapshot)) {
            ConfirmedManualTransferResult.NoChange(stored.receipt)
        } else {
            ConfirmedManualTransferResult.RequestIdentityConflict(identity)
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
        ): SqlDelightConfirmedManualTransferCommitPort = SqlDelightConfirmedManualTransferCommitPort(database)
    }
}

private data class StoredTransferCommit(
    val sourceAccountId: String,
    val destinationAccountId: String,
    val destinationCreditMinor: Long,
    val currencyCode: String,
    val currencyPrecision: Long,
    val feeMinor: Long,
    val feeCategoryId: String?,
    val occurredAt: String,
    val note: String,
    val confirmationMarker: String,
    val annotationTagIds: String?,
    val annotationMerchantId: String?,
    val receipt: ConfirmedTransferReceipt,
) {
    fun matches(snapshot: ManualTransferRequestSnapshot): Boolean =
        sourceAccountId == snapshot.sourceAccountId.value &&
            destinationAccountId == snapshot.destinationAccountId.value &&
            destinationCreditMinor == snapshot.destinationCredit.minorUnits &&
            currencyCode == snapshot.destinationCredit.currency.code &&
            currencyPrecision ==
            snapshot.destinationCredit.currency.precision
                .toLong() &&
            feeMinor == snapshot.fee.minorUnits &&
            feeCategoryId == snapshot.feeCategoryId?.value &&
            occurredAt == snapshot.occurredAt.toString() &&
            note == snapshot.note &&
            confirmationMarker == EXPLICIT_MANUAL_SAVE_MARKER &&
            annotationTagIds == encodeAnnotationTagIds(snapshot.tagIds) &&
            annotationMerchantId == encodeAnnotationMerchantId(snapshot.merchantId)
}

private const val EXPLICIT_MANUAL_SAVE_MARKER = "explicit_manual_save"
