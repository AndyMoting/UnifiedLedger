package com.unifiedledger.application

/**
 * P8-01a i2 [P801ReconciliationIdSource] backed by RFC 9562 UUIDv7, mirroring
 * [UuidV7TransactionVoidFactIdSource].
 *
 * Every [next] call mints exactly three UUIDv7 identifiers: the reconciliation request id, the
 * evidence link id and the posting reconciliation id. Minting is lazy at the call site, so a
 * refusal raised before the write consumes no id (and a minted id needs no gap-free sequence).
 */
class UuidV7P801ReconciliationIdSource(
    private val generator: UuidV7Generator,
) : P801ReconciliationIdSource {
    override fun next(): P801ReconciliationIds =
        P801ReconciliationIds(
            requestId = generator.next(),
            linkId = generator.next(),
            reconciliationId = generator.next(),
        )
}
