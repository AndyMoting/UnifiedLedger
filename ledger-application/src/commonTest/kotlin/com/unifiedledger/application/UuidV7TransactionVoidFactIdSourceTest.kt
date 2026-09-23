package com.unifiedledger.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * P7-05.C: the void/restore fact id source mints exactly two UUIDv7 ids per call — the receipt
 * confirmation id and the immutable fact id.
 */
class UuidV7TransactionVoidFactIdSourceTest {
    // Duplicates the file-private UUID_V7 regex and generator(seed) of
    // UuidV7ConfirmedManualExpenseIdSourceTest.kt: both are `private` to that file, so reuse
    // would require widening its visibility. Kept local to avoid touching a frozen test file.
    private val uuidV7 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    private fun generator(seed: Int): UuidV7Generator {
        var counter = seed
        return UuidV7Generator(randomBytes = { count -> ByteArray(count) { index -> (counter++).toByte() } }, timestampMillis = { 1_700_000_000_000L })
    }

    @Test
    fun voidFactIdSourceMintsTwoDistinctUuidV7Ids() {
        val ids = UuidV7TransactionVoidFactIdSource(generator(3)).next()
        val all = listOf(ids.confirmationId.value, ids.factId)
        assertEquals(2, all.toSet().size)
        all.forEach { assertTrue(uuidV7.matches(it), "not a uuidv7: $it") }
    }

    @Test
    fun consecutiveCallsMintFreshIds() {
        val source = UuidV7TransactionVoidFactIdSource(generator(5))
        val first = source.next()
        val second = source.next()
        assertNotEquals(first.factId, second.factId)
        assertNotEquals(first.confirmationId, second.confirmationId)
    }
}
