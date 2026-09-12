package com.unifiedledger.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * P7-02.A S-1: the income commit-id source mints six UUIDv7 ids per call, and the income
 * request-id source mints exactly one, from independent generators.
 */
class UuidV7ConfirmedManualIncomeIdSourceTest {
    private val uuidV7 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    private fun generator(seed: Int): UuidV7Generator {
        var counter = seed
        return UuidV7Generator(randomBytes = { count -> ByteArray(count) { index -> (counter++).toByte() } }, timestampMillis = { 1_700_000_000_000L })
    }

    @Test
    fun commitIdSourceMintsSixDistinctUuidV7Ids() {
        val ids = UuidV7ConfirmedManualIncomeIdSource(generator(1)).next()
        val all =
            listOf(
                ids.confirmationId.value,
                ids.incomeIds.transactionId.value,
                ids.incomeIds.versionId.value,
                ids.incomeIds.postingSetId.value,
                ids.incomeIds.receivingPostingId.value,
                ids.incomeIds.incomePostingId.value,
            )
        assertEquals(6, all.toSet().size)
        all.forEach { assertTrue(uuidV7.matches(it), "not a uuidv7: $it") }
    }

    @Test
    fun requestIdSourceMintsOneUuidV7AndIsIndependent() {
        val source = UuidV7ManualIncomeRequestIdSource(generator(7))
        val first = source.next()
        val second = source.next()
        assertTrue(uuidV7.matches(first.value))
        assertNotEquals(first, second)
    }
}
