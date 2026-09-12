package com.unifiedledger.application

import com.unifiedledger.domain.AccountTransferIds
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId

/**
 * P7-02.B product [ConfirmedManualTransferIdSource] backed by RFC 9562 UUIDv7, mirroring
 * [UuidV7ConfirmedManualExpenseIdSource]. Each call mints six ids: the confirmation id plus the
 * five [AccountTransferIds] fields (transaction, version, posting set, source/destination/fee
 * postings share `postingSetId`; the pure-principal path reuses the first two posting ids).
 */
class UuidV7ConfirmedManualTransferIdSource(
    private val generator: UuidV7Generator,
) : ConfirmedManualTransferIdSource {
    override fun next(): ConfirmedManualTransferCommitIds =
        ConfirmedManualTransferCommitIds(
            confirmationId = ConfirmationId(generator.next()),
            transferIds =
                AccountTransferIds(
                    transactionId = TransactionId(generator.next()),
                    versionId = TransactionVersionId(generator.next()),
                    postingSetId = PostingSetId(generator.next()),
                    sourcePostingId = PostingId(generator.next()),
                    destinationPostingId = PostingId(generator.next()),
                    feePostingId = PostingId(generator.next()),
                ),
        )
}
