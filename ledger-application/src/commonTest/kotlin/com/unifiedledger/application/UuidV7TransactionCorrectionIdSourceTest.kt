package com.unifiedledger.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * P7-05.B: the correction id source mints five UUIDv7 ids per call — the receipt identity plus
 * the replacement version/posting-set/posting identities.
 */
class UuidV7TransactionCorrectionIdSourceTest {
    // Duplicates the file-private uuidV7 regex and generator(seed) of
    // UuidV7ConfirmedManualIncomeIdSourceTest.kt: both are `private` to that file, so reuse
    // would require widening its visibility. Kept local to avoid touching a frozen test file.
    private val uuidV7 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    private fun generator(seed: Int): UuidV7Generator {
        var counter = seed
        return UuidV7Generator(randomBytes = { count -> ByteArray(count) { index -> (counter++).toByte() } }, timestampMillis = { 1_700_000_000_000L })
    }

    @Test
    fun correctionIdSourceMintsFiveDistinctUuidV7Ids() {
        val ids = UuidV7TransactionCorrectionIdSource(generator(1)).next()
        val all =
            listOf(
                ids.confirmationId.value,
                ids.versionId.value,
                ids.postingSetId.value,
                ids.categoryPostingId.value,
                ids.fundingPostingId.value,
            )
        assertEquals(5, all.toSet().size)
        all.forEach { assertTrue(uuidV7.matches(it), "not a uuidv7: $it") }
    }

    @Test
    fun consecutiveCallsMintFreshIds() {
        val source = UuidV7TransactionCorrectionIdSource(generator(11))
        val first = source.next()
        val second = source.next()
        assertNotEquals(first.versionId, second.versionId)
        assertNotEquals(first.postingSetId, second.postingSetId)
    }
}
