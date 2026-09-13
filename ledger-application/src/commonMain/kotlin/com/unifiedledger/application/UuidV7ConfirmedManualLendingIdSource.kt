package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CounterpartyId
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId

/**
 * P7-02.C product [ConfirmedManualLendingIdSource] backed by RFC 9562 UUIDv7, mirroring
 * [UuidV7ConfirmedManualTransferIdSource]. Each call mints the confirmation id, the stable
 * per-object history entry id, and the transaction/version/posting-set plus three posting ids.
 */
class UuidV7ConfirmedManualLendingIdSource(
    private val generator: UuidV7Generator,
) : ConfirmedManualLendingIdSource {
    override fun next(): ConfirmedManualLendingCommitIds =
        ConfirmedManualLendingCommitIds(
            confirmationId = ConfirmationId(generator.next()),
            entryId = "entry-" + generator.next(),
            lendingIds =
                ManualLendingTransactionIds(
                    transactionId = TransactionId(generator.next()),
                    versionId = TransactionVersionId(generator.next()),
                    postingSetId = PostingSetId(generator.next()),
                    counterpartyPostingId = PostingId(generator.next()),
                    primaryAccountPostingId = PostingId(generator.next()),
                    interestPostingId = PostingId(generator.next()),
                ),
        )
}

/**
 * P7-02.C counterparty id source: one stable [CounterpartyId] plus the dedicated hidden
 * receivable account id minted together for a new object (L-1).
 */
class UuidV7CounterpartyIdSource(
    private val generator: UuidV7Generator,
) : CounterpartyIdSource {
    override fun next(): NewCounterpartyIds =
        NewCounterpartyIds(
            counterpartyId = CounterpartyId("counterparty-" + generator.next()),
            receivableAccountId = AccountId("receivable-" + generator.next()),
        )
}
