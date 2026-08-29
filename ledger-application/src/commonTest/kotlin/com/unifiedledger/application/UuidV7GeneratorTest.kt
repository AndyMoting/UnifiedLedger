package com.unifiedledger.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

private val UUID_V7 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

class UuidV7GeneratorTest {
    @Test
    fun generatedIdsUseCanonicalUuidV7FormatWithVersionAndVariantBits() {
        val generator = UuidV7Generator(randomBytes = GeneratorRandomBytes::next)

        repeat(100) {
            val uuid = generator.next()

            assertTrue(UUID_V7.matches(uuid), "expected canonical UUIDv7 text: $uuid")
            assertEquals('7', uuid[14])
            assertTrue(uuid[19] in "89ab")
        }
    }

    @Test
    fun idsAreUniqueAcrossManyBatches() {
        val generator = UuidV7Generator(randomBytes = GeneratorRandomBytes::next)

        val ids = (1..200).map { generator.next() }

        assertEquals(200, ids.size)
        assertEquals(200, ids.toSet().size)
    }

    @Test
    fun fixedRandomBytesAndFixedTimestampProduceFixedOutput() {
        val generator =
            UuidV7Generator(
                randomBytes = { byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A) },
                timestampMillis = { 0x0123456789AB },
            )

        assertEquals("01234567-89ab-7002-8004-05060708090a", generator.next())
    }
}

/**
 * IMP-5 identity assertion for [FixedLedgerClock]. Kept in this file to stay within the
 * P5-02 commonTest file set (FixedLedgerClock.kt is a fixture only).
 */
class FixedLedgerClockTest {
    @Test
    fun fixedClockAlwaysReturnsTheInjectedInstant() {
        val instant = Instant.parse("2026-08-29T12:00:00Z")
        assertEquals(instant, FixedLedgerClock(instant).now())
    }
}

/**
 * Deterministic common-safe random byte source for tests: a full-period 64-bit LCG that
 * produces a distinct byte sequence on every call.
 */
private object GeneratorRandomBytes {
    private const val LCG_MULTIPLIER = 6364136223846793005L
    private const val LCG_INCREMENT = 1442695040888963407L
    private var state = 0x9E3779B9L

    fun next(count: Int): ByteArray =
        ByteArray(count) {
            state = state * LCG_MULTIPLIER + LCG_INCREMENT
            ((state ushr 32) and 0xFF).toByte()
        }
}
