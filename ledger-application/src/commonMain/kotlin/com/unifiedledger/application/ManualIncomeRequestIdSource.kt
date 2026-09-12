package com.unifiedledger.application

/**
 * P7-02.A S-1 income request identity source, the income analogue of
 * [ManualExpenseRequestIdSource].
 *
 * Each [next] mints exactly one canonical RFC 9562 UUIDv7 [RequestId]. This source is
 * independent from the income six-id commit source ([UuidV7ConfirmedManualIncomeIdSource]) and
 * from the expense sources: the composition roots construct a separate [UuidV7Generator]
 * instance so no request id shares a consumption count with any commit id source.
 */
fun interface ManualIncomeRequestIdSource {
    fun next(): RequestId
}

class UuidV7ManualIncomeRequestIdSource(
    private val generator: UuidV7Generator,
) : ManualIncomeRequestIdSource {
    override fun next(): RequestId = RequestId(generator.next())
}
