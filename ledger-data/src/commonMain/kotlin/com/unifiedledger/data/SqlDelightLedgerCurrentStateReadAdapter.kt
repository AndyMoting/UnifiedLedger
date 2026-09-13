package com.unifiedledger.data

import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.ConfirmedExpenseReceipt
import com.unifiedledger.application.ConfirmedIncomeReceipt
import com.unifiedledger.application.ConfirmedLendingReceipt
import com.unifiedledger.application.ConfirmedTransferReceipt
import com.unifiedledger.application.CurrentVersionRow
import com.unifiedledger.application.ImportCreationConfirmationRow
import com.unifiedledger.application.LedgerCurrentStateReadPort
import com.unifiedledger.application.LedgerEntryRow
import com.unifiedledger.application.ManualCreationChain
import com.unifiedledger.application.ManualCreationReceiptRow
import com.unifiedledger.application.ManualExpenseCommitRecord
import com.unifiedledger.application.ManualExpenseRequestSnapshot
import com.unifiedledger.application.ManualIncomeCommitRecord
import com.unifiedledger.application.ManualIncomeRequestSnapshot
import com.unifiedledger.application.ManualLendingCommitRecord
import com.unifiedledger.application.ManualTransferCommitRecord
import com.unifiedledger.application.ManualTransferRequestSnapshot
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.TransactionReconciliationLegRow
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlin.time.Instant

/**
 * P5-03 read adapter (P5-03 implementation spec section 5).
 *
 * Implements [LedgerCurrentStateReadPort] against the single [LedgerDatabase]. Every query
 * is ledger-filtered and current-version-only; rows carry ledger-signed minor units with no
 * display-sign flip. Exceptions propagate to the use-case boundary, which maps them to
 * `Unavailable`; a database failure is never mapped to a domain `Rejected`.
 */
class SqlDelightLedgerCurrentStateReadAdapter(
    private val database: LedgerDatabase,
) : LedgerCurrentStateReadPort {
    override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> {
        val rows = database.ledgerQueries.currentVersionRowsForLedger(ledgerId.value).executeAsList()
        return rows
            .groupBy { it.transaction_id }
            .map { (_, groupedRows) ->
                val first = groupedRows.first()
                CurrentVersionRow(
                    transactionId = TransactionId(first.transaction_id),
                    currentVersionId = TransactionVersionId(first.current_version_id),
                    kind = TransactionKind.valueOf(first.kind),
                    occurredAt = Instant.parse(first.occurred_at),
                    postings =
                        groupedRows
                            .sortedBy { it.posting_index }
                            .map { row ->
                                Posting(
                                    id = PostingId(row.posting_id),
                                    accountId = AccountId(row.account_id),
                                    amount =
                                        Money.ofMinor(
                                            row.amount_minor,
                                            CurrencyUnit(row.currency_code, row.currency_precision.toInt()),
                                        ),
                                )
                            },
                )
            }
    }

    override fun findManualExpenseByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualExpenseCommitRecord? {
        val row =
            database.ledgerQueries
                .manualExpenseCommitByRequest(ledgerId.value, requestId.value)
                .executeAsOneOrNull()
                ?: return null
        return row.toRecord(ledgerId)
    }

    override fun findManualExpenseByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedExpenseReceipt,
    ): ManualExpenseCommitRecord? {
        val row =
            database.ledgerQueries
                .manualExpenseCommitByReceipt(
                    ledger_id = ledgerId.value,
                    confirmation_id = receipt.confirmationId.value,
                    transaction_id = receipt.transactionId.value,
                ).executeAsOneOrNull()
                ?: return null
        return row.toRecord(ledgerId)
    }

    override fun findManualIncomeByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualIncomeCommitRecord? {
        val row =
            database.ledgerQueries
                .manualIncomeCommitByRequest(ledgerId.value, requestId.value)
                .executeAsOneOrNull()
                ?: return null
        return row.toRecord(ledgerId)
    }

    override fun findManualIncomeByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedIncomeReceipt,
    ): ManualIncomeCommitRecord? {
        val row =
            database.ledgerQueries
                .manualIncomeCommitByReceipt(
                    ledger_id = ledgerId.value,
                    confirmation_id = receipt.confirmationId.value,
                    transaction_id = receipt.transactionId.value,
                ).executeAsOneOrNull()
                ?: return null
        return row.toRecord(ledgerId)
    }

    override fun findManualTransferByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualTransferCommitRecord? {
        val row =
            database.ledgerQueries
                .manualTransferCommitByRequest(ledgerId.value, requestId.value)
                .executeAsOneOrNull()
                ?: return null
        return row.toRecord(ledgerId)
    }

    override fun findManualTransferByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedTransferReceipt,
    ): ManualTransferCommitRecord? {
        val row =
            database.ledgerQueries
                .manualTransferCommitByReceipt(
                    ledger_id = ledgerId.value,
                    confirmation_id = receipt.confirmationId.value,
                    transaction_id = receipt.transactionId.value,
                ).executeAsOneOrNull()
                ?: return null
        return row.toRecord(ledgerId)
    }

    override fun findManualLendingByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualLendingCommitRecord? {
        val row =
            database.ledgerQueries
                .manualLendingCommitByRequest(ledgerId.value, requestId.value)
                .executeAsOneOrNull()
                ?: return null
        return row.toRecord(ledgerId)
    }

    override fun findManualLendingByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedLendingReceipt,
    ): ManualLendingCommitRecord? {
        val row =
            database.ledgerQueries
                .manualLendingCommitByReceipt(
                    ledger_id = ledgerId.value,
                    confirmation_id = receipt.confirmationId.value,
                    transaction_id = receipt.transactionId.value,
                ).executeAsOneOrNull()
                ?: return null
        return row.toRecord(ledgerId)
    }

    /**
     * P7-03.A (D-145): ledger-view entry rows (spec sections 4.1/5, Appendix A). Same
     * ledger-scoped current-version-only join shape as [loadCurrentRows], with the
     * effective kind via the query's `COALESCE(canonical_kind, kind)`, both persisted
     * times and the current version note. Read-only; exceptions propagate to the use-case
     * boundary which maps them to `Unavailable`.
     */
    override fun loadLedgerEntryRows(ledgerId: LedgerId): List<LedgerEntryRow> {
        val rows = database.ledgerQueries.ledgerEntryRowsForLedger(ledgerId.value).executeAsList()
        return rows
            .groupBy { it.transaction_id }
            .map { (_, groupedRows) ->
                val first = groupedRows.first()
                LedgerEntryRow(
                    transactionId = TransactionId(first.transaction_id),
                    currentVersionId = TransactionVersionId(first.current_version_id),
                    kind = TransactionKind.valueOf(first.kind),
                    occurredAt = Instant.parse(first.occurred_at),
                    statisticsAt = Instant.parse(first.statistics_at),
                    note = first.note,
                    postings =
                        groupedRows
                            .sortedBy { it.posting_index }
                            .map { row ->
                                Posting(
                                    id = PostingId(row.posting_id),
                                    accountId = AccountId(row.account_id),
                                    amount =
                                        Money.ofMinor(
                                            row.amount_minor,
                                            CurrencyUnit(row.currency_code, row.currency_precision.toInt()),
                                        ),
                                )
                            },
                )
            }
    }

    /** P7-03.A: reverse creation lineage into `import_confirmation` (`operation_class='creation'`). */
    override fun findImportCreationConfirmation(
        ledgerId: LedgerId,
        transactionId: TransactionId,
    ): ImportCreationConfirmationRow? =
        database.ledgerQueries
            .importCreationConfirmationByTransaction(ledgerId.value, transactionId.value)
            .executeAsOneOrNull()
            ?.let { row ->
                ImportCreationConfirmationRow(
                    confirmationId = row.confirmation_id,
                    requestId = row.request_id,
                    candidateId = row.candidate_id,
                    transactionId = TransactionId(row.transaction_id),
                    operationClass = row.operation_class,
                    confirmedAt = row.confirmed_at,
                )
            }

    /** P7-03.A: reverse creation lineage into the manual four-chain receipt tables. */
    override fun findManualCreationReceipt(
        ledgerId: LedgerId,
        transactionId: TransactionId,
    ): ManualCreationReceiptRow? =
        database.ledgerQueries
            .manualCreationReceiptByTransaction(
                ledger_id = ledgerId.value,
                transaction_id = transactionId.value,
            ).executeAsOneOrNull()
            ?.let { row ->
                ManualCreationReceiptRow(
                    chain =
                        when (row.chain) {
                            "expense" -> ManualCreationChain.EXPENSE
                            "income" -> ManualCreationChain.INCOME
                            "transfer" -> ManualCreationChain.TRANSFER
                            "lending" -> ManualCreationChain.LENDING
                            else -> return null
                        },
                    confirmationId = row.confirmation_id,
                )
            }

    /**
     * P7-03.A: read-only reconciliation leg projection for one transaction (R-Q07-4,
     * spec section 3.2.1). Rows follow the `selectP408ReconciliationReport` line semantics
     * narrowed to the transaction; a posting with several active evidence links yields
     * several rows, which are folded into one projection row per posting. Existing rows
     * are only read — no reconciliation, evidence, or rg03 row is ever written here.
     */
    override fun loadTransactionReconciliationLegs(
        ledgerId: LedgerId,
        transactionId: TransactionId,
    ): List<TransactionReconciliationLegRow> {
        val rows = database.ledgerQueries.transactionReconciliationLegs(ledgerId.value, transactionId.value).executeAsList()
        return rows
            .groupBy { it.posting_id }
            .map { (_, groupedRows) ->
                val first = groupedRows.first()
                TransactionReconciliationLegRow(
                    postingId = PostingId(first.posting_id),
                    postingIndex = first.posting_index.toInt(),
                    accountId = AccountId(first.account_id),
                    amountMinor = first.amount_minor,
                    currency = CurrencyUnit(first.currency_code, first.currency_precision.toInt()),
                    statusStorageValue = first.status,
                    hasReconciliationRow = first.reconciliation_id != null,
                    hasActiveEvidenceLink = groupedRows.any { it.active_link_id != null },
                    rg03ReconciliationEligible = first.reconciliation_eligible?.let { it != 0L },
                )
            }.sortedBy { it.postingIndex }
    }
}

private fun com.unifiedledger.data.db.ManualExpenseCommitByRequest.toRecord(ledgerId: LedgerId): ManualExpenseCommitRecord =
    ManualExpenseCommitRecord(
        ledgerId = ledgerId,
        requestId = RequestId(request_id),
        snapshot =
            ManualExpenseRequestSnapshot(
                ledgerId = ledgerId,
                amount = Money.ofMinor(amount_minor, CurrencyUnit(currency_code, currency_precision.toInt())),
                categoryId = CategoryId(category_id),
                paymentAccountId = AccountId(payment_account_id),
                occurredAt = Instant.parse(occurred_at),
                note = note,
            ),
        receipt =
            ConfirmedExpenseReceipt(
                confirmationId = ConfirmationId(confirmation_id),
                transactionId = TransactionId(transaction_id),
            ),
        currentVersionId = TransactionVersionId(current_version_id),
    )

private fun com.unifiedledger.data.db.ManualExpenseCommitByReceipt.toRecord(ledgerId: LedgerId): ManualExpenseCommitRecord =
    ManualExpenseCommitRecord(
        ledgerId = ledgerId,
        requestId = RequestId(request_id),
        snapshot =
            ManualExpenseRequestSnapshot(
                ledgerId = ledgerId,
                amount = Money.ofMinor(amount_minor, CurrencyUnit(currency_code, currency_precision.toInt())),
                categoryId = CategoryId(category_id),
                paymentAccountId = AccountId(payment_account_id),
                occurredAt = Instant.parse(occurred_at),
                note = note,
            ),
        receipt =
            ConfirmedExpenseReceipt(
                confirmationId = ConfirmationId(confirmation_id),
                transactionId = TransactionId(transaction_id),
            ),
        currentVersionId = TransactionVersionId(current_version_id),
    )

private fun com.unifiedledger.data.db.ManualIncomeCommitByRequest.toRecord(ledgerId: LedgerId): ManualIncomeCommitRecord =
    ManualIncomeCommitRecord(
        ledgerId = ledgerId,
        requestId = RequestId(request_id),
        snapshot =
            ManualIncomeRequestSnapshot(
                ledgerId = ledgerId,
                amount = Money.ofMinor(amount_minor, CurrencyUnit(currency_code, currency_precision.toInt())),
                categoryId = CategoryId(category_id),
                receivingAccountId = AccountId(receiving_account_id),
                occurredAt = Instant.parse(occurred_at),
                note = note,
            ),
        receipt =
            ConfirmedIncomeReceipt(
                confirmationId = ConfirmationId(confirmation_id),
                transactionId = TransactionId(transaction_id),
            ),
        currentVersionId = TransactionVersionId(current_version_id),
    )

private fun com.unifiedledger.data.db.ManualIncomeCommitByReceipt.toRecord(ledgerId: LedgerId): ManualIncomeCommitRecord =
    ManualIncomeCommitRecord(
        ledgerId = ledgerId,
        requestId = RequestId(request_id),
        snapshot =
            ManualIncomeRequestSnapshot(
                ledgerId = ledgerId,
                amount = Money.ofMinor(amount_minor, CurrencyUnit(currency_code, currency_precision.toInt())),
                categoryId = CategoryId(category_id),
                receivingAccountId = AccountId(receiving_account_id),
                occurredAt = Instant.parse(occurred_at),
                note = note,
            ),
        receipt =
            ConfirmedIncomeReceipt(
                confirmationId = ConfirmationId(confirmation_id),
                transactionId = TransactionId(transaction_id),
            ),
        currentVersionId = TransactionVersionId(current_version_id),
    )

private fun com.unifiedledger.data.db.ManualTransferCommitByRequest.toRecord(ledgerId: LedgerId): ManualTransferCommitRecord =
    ManualTransferCommitRecord(
        ledgerId = ledgerId,
        requestId = RequestId(request_id),
        snapshot =
            ManualTransferRequestSnapshot(
                ledgerId = ledgerId,
                sourceAccountId = AccountId(source_account_id),
                destinationAccountId = AccountId(destination_account_id),
                destinationCredit = Money.ofMinor(destination_credit_minor, CurrencyUnit(currency_code, currency_precision.toInt())),
                fee = Money.ofMinor(fee_minor, CurrencyUnit(currency_code, currency_precision.toInt())),
                feeCategoryId = fee_category_id?.let(::CategoryId),
                occurredAt = Instant.parse(occurred_at),
                note = note,
            ),
        receipt = ConfirmedTransferReceipt(ConfirmationId(confirmation_id), TransactionId(transaction_id)),
        currentVersionId = TransactionVersionId(current_version_id),
    )

private fun com.unifiedledger.data.db.ManualTransferCommitByReceipt.toRecord(ledgerId: LedgerId): ManualTransferCommitRecord =
    ManualTransferCommitRecord(
        ledgerId = ledgerId,
        requestId = RequestId(request_id),
        snapshot =
            ManualTransferRequestSnapshot(
                ledgerId = ledgerId,
                sourceAccountId = AccountId(source_account_id),
                destinationAccountId = AccountId(destination_account_id),
                destinationCredit = Money.ofMinor(destination_credit_minor, CurrencyUnit(currency_code, currency_precision.toInt())),
                fee = Money.ofMinor(fee_minor, CurrencyUnit(currency_code, currency_precision.toInt())),
                feeCategoryId = fee_category_id?.let(::CategoryId),
                occurredAt = Instant.parse(occurred_at),
                note = note,
            ),
        receipt = ConfirmedTransferReceipt(ConfirmationId(confirmation_id), TransactionId(transaction_id)),
        currentVersionId = TransactionVersionId(current_version_id),
    )
