package com.unifiedledger.application

/**
 * P5-03 request identity source (D-119 section 3.4; plan section 3.2).
 *
 * Each [next] mints exactly one canonical RFC 9562 UUIDv7 [RequestId]. This source is
 * independent from the six-id commit source ([UuidV7ConfirmedManualExpenseIdSource]): the
 * composition root constructs two separate [UuidV7Generator] instances so a request id and
 * the six formal commit ids never share a consumption count.
 */
fun interface ManualExpenseRequestIdSource {
    fun next(): RequestId
}

class UuidV7ManualExpenseRequestIdSource(
    private val generator: UuidV7Generator,
) : ManualExpenseRequestIdSource {
    override fun next(): RequestId = RequestId(generator.next())
}
