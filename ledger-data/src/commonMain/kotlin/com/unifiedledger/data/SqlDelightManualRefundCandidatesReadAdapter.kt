package com.unifiedledger.data

import com.unifiedledger.application.ManualRefundCandidate
import com.unifiedledger.application.ManualRefundCandidatesReadPort
import com.unifiedledger.application.ManualRefundCandidatesResult
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.TransactionId
import kotlin.time.Instant

/**
 * P8-03 i2a bounded candidate read adapter (spec sections 2.3/2.4/5).
 *
 * Implements [ManualRefundCandidatesReadPort]. One page read runs in ONE
 * `transactionWithResult(noEnclosing = true)`, so the page and its lookahead come from one snapshot,
 * and it reads `pageSize + 1` rows: `hasMore` is `rows.size > pageSize` and only the first `pageSize`
 * rows become the page, so "more pages" costs no second statement and the page never exceeds the
 * caller's bound (V-15).
 *
 * [ManualRefundCandidatesResult.Unavailable] is the fail-closed answer to every failure of the read,
 * an unparseable `occurred_at` included: a failed read is never returned as `Loaded(emptyList())`
 * (V-13). Only a genuinely empty page is `Loaded(emptyList(), hasMore = false)`.
 *
 * Read-only: no row is written and no balance, version or reconciliation state is changed.
 */
class SqlDelightManualRefundCandidatesReadAdapter(
    private val database: LedgerDatabase,
) : ManualRefundCandidatesReadPort {
    override fun readPage(
        ledgerId: LedgerId,
        pageSize: Int,
        pageOffset: Int,
    ): ManualRefundCandidatesResult {
        // Programming errors, not user input: the entry surface never passes these values.
        require(pageSize > 0) { "manual refund candidate page size must be positive" }
        require(pageOffset >= 0) { "manual refund candidate page offset must not be negative" }
        return try {
            database.transactionWithResult(noEnclosing = true) {
                val rows =
                    database.ledgerQueries
                        .selectManualRefundExpenseCandidates(
                            ledger_id = ledgerId.value,
                            page_size = pageSize.toLong() + 1L,
                            page_offset = pageOffset.toLong(),
                        ) {
                            transactionId,
                            occurredAt,
                            categoryId,
                            structuralCapMinor,
                            currencyCode,
                            currencyPrecision,
                            remainingMinor,
                            categoryName,
                            ->
                            ManualRefundCandidate(
                                transactionId = TransactionId(transactionId),
                                occurredAt = Instant.parse(occurredAt),
                                categoryId = CategoryId(categoryId),
                                categoryName = categoryName,
                                structuralCap = Money.ofMinor(structuralCapMinor, CurrencyUnit(currencyCode, currencyPrecision.toInt())),
                                remaining = Money.ofMinor(remainingMinor, CurrencyUnit(currencyCode, currencyPrecision.toInt())),
                            )
                        }.executeAsList()
                ManualRefundCandidatesResult.Loaded(
                    candidates = rows.take(pageSize),
                    hasMore = rows.size > pageSize,
                )
            }
        } catch (failure: Exception) {
            ManualRefundCandidatesResult.Unavailable
        }
    }

    companion object {
        internal fun forPlatformConfiguredDatabase(
            database: LedgerDatabase,
        ): SqlDelightManualRefundCandidatesReadAdapter = SqlDelightManualRefundCandidatesReadAdapter(database)
    }
}
