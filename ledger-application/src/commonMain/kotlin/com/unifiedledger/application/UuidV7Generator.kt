package com.unifiedledger.application

import kotlin.time.Clock

/**
 * RFC 9562 UUIDv7 generator (P5-02, IMP-7/IMP-8).
 *
 * Produces the canonical lowercase 8-4-4-4-12 text form: version nibble `7` at the start of
 * the third group and variant bits `10xx` at the start of the fourth group. The 48-bit Unix
 * epoch millisecond timestamp is read from [timestampMillis]; the remaining 74 random bits
 * (12-bit rand_a plus 62-bit rand_b) come from the injected [randomBytes] source. The random
 * source is the only platform-sensitive input, so each composition root injects its platform
 * secure-random provider while this pure bit-packing algorithm stays shared in commonMain.
 *
 * The default [timestampMillis] reads [Clock.System.now], which is stable in Kotlin 2.4.10
 * without opt-in. Tests inject a fixed timestamp and a fixed byte source to obtain a fixed
 * output.
 */
class UuidV7Generator(
    private val randomBytes: (count: Int) -> ByteArray,
    private val timestampMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    fun next(): String {
        val millis = timestampMillis() and TIMESTAMP_MASK
        val rand = randomBytes(RANDOM_BYTES_PER_UUID)
        require(rand.size == RANDOM_BYTES_PER_UUID) {
            "random byte source must return exactly $RANDOM_BYTES_PER_UUID bytes"
        }

        val bytes = ByteArray(16)
        bytes[0] = (millis ushr 40).toByte()
        bytes[1] = (millis ushr 32).toByte()
        bytes[2] = (millis ushr 24).toByte()
        bytes[3] = (millis ushr 16).toByte()
        bytes[4] = (millis ushr 8).toByte()
        bytes[5] = millis.toByte()
        bytes[6] = (VERSION_NIBBLE or ((rand[0].toInt() ushr 4) and 0x0F)).toByte()
        bytes[7] = rand[1]
        bytes[8] = (VARIANT_MASK or ((rand[2].toInt() ushr 2) and 0x3F)).toByte()
        bytes[9] = rand[3]
        bytes[10] = rand[4]
        bytes[11] = rand[5]
        bytes[12] = rand[6]
        bytes[13] = rand[7]
        bytes[14] = rand[8]
        bytes[15] = rand[9]

        return buildString(36) {
            appendHex(bytes[0])
            appendHex(bytes[1])
            appendHex(bytes[2])
            appendHex(bytes[3])
            append('-')
            appendHex(bytes[4])
            appendHex(bytes[5])
            append('-')
            appendHex(bytes[6])
            appendHex(bytes[7])
            append('-')
            appendHex(bytes[8])
            appendHex(bytes[9])
            append('-')
            appendHex(bytes[10])
            appendHex(bytes[11])
            appendHex(bytes[12])
            appendHex(bytes[13])
            appendHex(bytes[14])
            appendHex(bytes[15])
        }
    }

    private fun StringBuilder.appendHex(value: Byte) {
        val byte = value.toInt() and 0xFF
        append(HEX_DIGITS[byte ushr 4])
        append(HEX_DIGITS[byte and 0x0F])
    }

    private companion object {
        const val RANDOM_BYTES_PER_UUID = 10
        const val VERSION_NIBBLE = 0x70
        const val VARIANT_MASK = 0x80
        const val TIMESTAMP_MASK = 0xFFFFFFFFFFFFL
        const val HEX_DIGITS = "0123456789abcdef"
    }
}
