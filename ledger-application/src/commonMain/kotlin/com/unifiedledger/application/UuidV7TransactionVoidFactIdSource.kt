package com.unifiedledger.application

/**
 * P7-05.C product [TransactionVoidFactIdSource] backed by RFC 9562 UUIDv7, mirroring
 * [UuidV7ConfirmedManualExpenseIdSource].
 *
 * Every [next] call mints exactly two UUIDv7 identifiers: the void/restore receipt's
 * [ConfirmationId] and the immutable fact's `fact_id`.
 *
 * Lazy materialization is unchanged: [next] is invoked only inside the claim-winning callback of
 * the persistence `commitOnce` port, so exact replays and identity conflicts consume no ids. The
 * plain void path mints its ids only on a real commit, but the restore path mints before the
 * catalog admission and the revalidation, so a rejection there does discard ids already minted
 * (harmless — UUIDv7 ids need not be gapless).
 */
class UuidV7TransactionVoidFactIdSource(
    private val generator: UuidV7Generator,
) : TransactionVoidFactIdSource {
    override fun next(): TransactionVoidFactIds =
        TransactionVoidFactIds(
            confirmationId = ConfirmationId(generator.next()),
            factId = generator.next(),
        )
}
