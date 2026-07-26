package com.unifiedledger.application

/**
 * Deterministic identity generation for golden contract v2.
 *
 * This is the runtime counterpart of `deterministic_v2_root_id` and `deterministic_v2_migration_id`
 * in `tools/python/golden_cases/v2.py`, which produced the frozen expected outputs. The namespace,
 * the name layout and the component validation below must stay byte-identical to that generator:
 * the two sides have to agree or migrated expected output and runtime output name different
 * entities for the same fact. Per-scenario copies of this logic are exactly how that agreement was
 * broken before, so scenarios pass their own case id in rather than owning their own generator.
 */

/** Must equal `_UUID_NAMESPACE` in `tools/python/golden_cases/v2.py`. */
private val goldenV2UuidNamespace = "cfad3f84edb15838ae53aae49684cf1a".chunked(2)
    .map { it.toInt(16).toByte() }
    .toByteArray()

/** Mirrors `_MIGRATION_SOURCE_LOCATOR_PATTERN`: a normalized source path of `$`, `.key` and `[*]`. */
private val goldenV2SourceLocatorPattern = Regex("^\\$(?:\\.[^.\\[\\]/\\x00-\\x1f\\x7f]+|\\[\\*])*$")

class GoldenV2IdentityException(message: String) : IllegalArgumentException(message)

fun goldenV2RootId(caseId: String, sourceLocator: String, occurrenceDiscriminator: String): String {
    validateGoldenV2Component("case_id", caseId)
    return goldenV2UuidV5("$caseId\n@root\nroot\n${goldenV2SemanticKey(sourceLocator, occurrenceDiscriminator)}")
}

fun goldenV2MigrationId(
    caseId: String,
    rootId: String,
    entityKind: String,
    sourceLocator: String,
    occurrenceDiscriminator: String,
): String {
    validateGoldenV2Component("case_id", caseId)
    validateGoldenV2Component("root_id", rootId)
    validateGoldenV2Component("entity_kind", entityKind)
    return goldenV2UuidV5("$caseId\n$rootId\n$entityKind\n${goldenV2SemanticKey(sourceLocator, occurrenceDiscriminator)}")
}

private fun goldenV2SemanticKey(sourceLocator: String, occurrenceDiscriminator: String): String {
    if (!goldenV2SourceLocatorPattern.matches(sourceLocator)) {
        throw GoldenV2IdentityException("source locator must be a normalized source locator using $, .key and [*]")
    }
    validateGoldenV2Component("occurrence_discriminator", occurrenceDiscriminator)
    return "$sourceLocator\noccurrence=$occurrenceDiscriminator"
}

private fun validateGoldenV2Component(name: String, value: String) {
    if (value.isEmpty()) throw GoldenV2IdentityException("$name must be non-empty")
    if (value.any { it.code < 32 || it.code == 127 }) {
        throw GoldenV2IdentityException("$name must not contain control characters")
    }
}

/** RFC 4122 name-based version 5 UUID. Kept dependency-free so it runs on every target. */
fun goldenV2UuidV5(name: String): String {
    val bytes = goldenV2Sha1(goldenV2UuidNamespace + name.encodeToByteArray()).copyOf(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}

private fun goldenV2Sha1(input: ByteArray): ByteArray {
    val bitLength = input.size.toLong() * 8
    val paddedSize = ((input.size + 9 + 63) / 64) * 64
    val padded = ByteArray(paddedSize)
    input.copyInto(padded)
    padded[input.size] = 0x80.toByte()
    repeat(8) { index -> padded[padded.lastIndex - index] = (bitLength ushr (index * 8)).toByte() }

    var h0 = 0x67452301
    var h1 = 0xEFCDAB89.toInt()
    var h2 = 0x98BADCFE.toInt()
    var h3 = 0x10325476
    var h4 = 0xC3D2E1F0.toInt()
    val words = IntArray(80)
    for (offset in padded.indices step 64) {
        for (index in 0 until 16) {
            val start = offset + index * 4
            words[index] = ((padded[start].toInt() and 0xff) shl 24) or
                ((padded[start + 1].toInt() and 0xff) shl 16) or
                ((padded[start + 2].toInt() and 0xff) shl 8) or
                (padded[start + 3].toInt() and 0xff)
        }
        for (index in 16 until 80) words[index] = goldenV2RotateLeft(words[index - 3] xor words[index - 8] xor words[index - 14] xor words[index - 16], 1)
        var a = h0
        var b = h1
        var c = h2
        var d = h3
        var e = h4
        for (index in 0 until 80) {
            val f: Int
            val k: Int
            when (index) {
                in 0..19 -> { f = (b and c) or (b.inv() and d); k = 0x5A827999 }
                in 20..39 -> { f = b xor c xor d; k = 0x6ED9EBA1 }
                in 40..59 -> { f = (b and c) or (b and d) or (c and d); k = 0x8F1BBCDC.toInt() }
                else -> { f = b xor c xor d; k = 0xCA62C1D6.toInt() }
            }
            val next = goldenV2RotateLeft(a, 5) + f + e + k + words[index]
            e = d
            d = c
            c = goldenV2RotateLeft(b, 30)
            b = a
            a = next
        }
        h0 += a
        h1 += b
        h2 += c
        h3 += d
        h4 += e
    }
    return listOf(h0, h1, h2, h3, h4).flatMap { word ->
        listOf((word ushr 24).toByte(), (word ushr 16).toByte(), (word ushr 8).toByte(), word.toByte())
    }.toByteArray()
}

private fun goldenV2RotateLeft(value: Int, bits: Int): Int = (value shl bits) or (value ushr (32 - bits))
