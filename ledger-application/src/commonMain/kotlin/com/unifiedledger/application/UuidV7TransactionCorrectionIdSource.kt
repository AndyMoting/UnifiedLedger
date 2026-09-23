package com.unifiedledger.application

import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionVersionId

/**
 * P7-05.B product [CorrectTransactionVersionIdSource] backed by RFC 9562 UUIDv7, mirroring
 * [UuidV7ConfirmedManualExpenseIdSource].
 *
 * Every [next] call mints exactly five UUIDv7 identifiers: the correction receipt's
 * [ConfirmationId], the replacement [TransactionVersionId], the fresh [PostingSetId] and the two
 * posting ids of the derived posting set.
 *
 * Lazy materialization is unchanged: [next] is invoked only inside the claim-winning callback of
 * the persistence `commitOnce` port, so exact replays and identity conflicts consume no ids. The
 * correction path, however, mints its ids before the catalog admission, the in-plan rejection and
 * the CAS, so a lost CAS or an in-plan rejection does discard ids already minted (harmless —
 * UUIDv7 ids need not be gapless).
 */
class UuidV7TransactionCorrectionIdSource(
    private val generator: UuidV7Generator,
) : CorrectTransactionVersionIdSource {
    override fun next(): CorrectTransactionVersionIds =
        CorrectTransactionVersionIds(
            confirmationId = ConfirmationId(generator.next()),
            versionId = TransactionVersionId(generator.next()),
            postingSetId = PostingSetId(generator.next()),
            categoryPostingId = PostingId(generator.next()),
            fundingPostingId = PostingId(generator.next()),
        )
}
