package com.unifiedledger.application

import com.unifiedledger.domain.AssetReceivedOrdinaryIncomeIds
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId

/**
 * P7-02.A S-1 product [ConfirmedManualIncomeIdSource] implementation backed by RFC 9562
 * UUIDv7, mirroring [UuidV7ConfirmedManualExpenseIdSource].
 *
 * Every [next] call mints exactly six UUIDv7 identifiers: the [ConfirmedManualIncomeCommitIds]
 * confirmation id plus all five [AssetReceivedOrdinaryIncomeIds] fields (transaction, version,
 * posting set, receiving posting and income posting).
 *
 * Lazy materialization is unchanged: [next] is invoked only inside the atomic first-request
 * callback of the persistence `commitOnce` port, so exact replays, identity conflicts and
 * losing concurrent writers consume no ids.
 */
class UuidV7ConfirmedManualIncomeIdSource(
    private val generator: UuidV7Generator,
) : ConfirmedManualIncomeIdSource {
    override fun next(): ConfirmedManualIncomeCommitIds =
        ConfirmedManualIncomeCommitIds(
            confirmationId = ConfirmationId(generator.next()),
            incomeIds =
                AssetReceivedOrdinaryIncomeIds(
                    transactionId = TransactionId(generator.next()),
                    versionId = TransactionVersionId(generator.next()),
                    postingSetId = PostingSetId(generator.next()),
                    receivingPostingId = PostingId(generator.next()),
                    incomePostingId = PostingId(generator.next()),
                ),
        )
}
