package com.unifiedledger.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val UUID_V7 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

class UuidV7ConfirmedManualExpenseIdSourceTest {
    @Test
    fun nextMintsExactlySixCanonicalUuidV7Identifiers() {
        val source = UuidV7ConfirmedManualExpenseIdSource(UuidV7Generator(randomBytes = IdSourceRandomBytes::next))

        val ids = source.next()

        val values = ids.values()
        assertEquals(6, values.size)
        values.forEach { value -> assertTrue(UUID_V7.matches(value), "expected canonical UUIDv7 text: $value") }
        assertEquals(6, values.toSet().size, "each next() must mint six distinct ids")
    }

    @Test
    fun idsAreUniqueAcrossManyBatches() {
        val source = UuidV7ConfirmedManualExpenseIdSource(UuidV7Generator(randomBytes = IdSourceRandomBytes::next))

        val ids = (1..30).flatMap { source.next().values() }

        assertEquals(180, ids.size)
        assertEquals(180, ids.toSet().size)
    }

    @Test
    fun deterministicGeneratorYieldsFixedIds() {
        val generator =
            UuidV7Generator(
                randomBytes = { byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A) },
                timestampMillis = { 0x0123456789AB },
            )
        val source = UuidV7ConfirmedManualExpenseIdSource(generator)

        val expected = "01234567-89ab-7002-8004-05060708090a"
        val ids = source.next()

        ids.values().forEach { value -> assertEquals(expected, value) }
    }
}

private fun ConfirmedManualExpenseCommitIds.values(): List<String> =
    listOf(
        confirmationId.value,
        expenseIds.transactionId.value,
        expenseIds.versionId.value,
        expenseIds.postingSetId.value,
        expenseIds.expensePostingId.value,
        expenseIds.paymentPostingId.value,
    )

/**
 * Deterministic common-safe random byte source for tests: a full-period 64-bit LCG that
 * produces a distinct byte sequence on every call.
 */
private object IdSourceRandomBytes {
    private const val LCG_MULTIPLIER = 6364136223846793005L
    private const val LCG_INCREMENT = 1442695040888963407L
    private var state = 0x9E3779B9L

    fun next(count: Int): ByteArray =
        ByteArray(count) {
            state = state * LCG_MULTIPLIER + LCG_INCREMENT
            ((state ushr 32) and 0xFF).toByte()
        }
}
