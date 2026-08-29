package com.unifiedledger.application

import com.unifiedledger.domain.AssetPaidOrdinaryExpenseIds
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId

/**
 * Product [ConfirmedManualExpenseIdSource] implementation backed by RFC 9562 UUIDv7
 * (P5-02, IMP-9).
 *
 * Every [next] call mints exactly six UUIDv7 identifiers: the [ConfirmedManualExpenseCommitIds]
 * confirmation id plus all five [AssetPaidOrdinaryExpenseIds] fields (transaction, version,
 * posting set, expense posting, and payment posting).
 *
 * Lazy materialization is unchanged: [next] is invoked only inside the atomic first-request
 * callback of the persistence `commitOnce` port, so exact replays, identity conflicts, and
 * losing concurrent writers consume no ids.
 */
class UuidV7ConfirmedManualExpenseIdSource(
    private val generator: UuidV7Generator,
) : ConfirmedManualExpenseIdSource {
    override fun next(): ConfirmedManualExpenseCommitIds =
        ConfirmedManualExpenseCommitIds(
            confirmationId = ConfirmationId(generator.next()),
            expenseIds =
                AssetPaidOrdinaryExpenseIds(
                    transactionId = TransactionId(generator.next()),
                    versionId = TransactionVersionId(generator.next()),
                    postingSetId = PostingSetId(generator.next()),
                    expensePostingId = PostingId(generator.next()),
                    paymentPostingId = PostingId(generator.next()),
                ),
        )
}
