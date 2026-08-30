package com.unifiedledger.data

import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.ConfirmedExpenseReceipt
import com.unifiedledger.application.CurrentVersionRow
import com.unifiedledger.application.LedgerCurrentStateReadPort
import com.unifiedledger.application.ManualExpenseCommitRecord
import com.unifiedledger.application.ManualExpenseRequestSnapshot
import com.unifiedledger.application.RequestId
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
