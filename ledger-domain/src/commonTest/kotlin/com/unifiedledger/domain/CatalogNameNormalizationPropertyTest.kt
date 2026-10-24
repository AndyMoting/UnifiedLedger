package com.unifiedledger.domain

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P7-08 08.A name-normalization property evidence (spec section 2.3; D-238 A02 gap (iii)).
 *
 * This is the anonymous property test of spec section 2.3, replacing a hand-written example set
 * with a generated corpus and a reference model. `ledger-domain` declares a JVM target and an
 * Android target with `withHostTest`, so the class runs in the module's `jvmTest` task and in
 * `testAndroidHostTest` on the Android host-test compilation face; that Android leg is executed
 * by CI (no device or instrumented leg is claimed by this class).
 *
 * It depends only on `kotlin.test` and the stdlib (no property-testing framework exists in this
 * repository) and is fully deterministic: the corpus comes from a fixed-seed [Random], never from
 * a time, system-entropy or unseeded source, so a failure is reproducible byte for byte.
 *
 * The reference helpers below are written in this file rather than reusing the production helpers
 * (the production `codePointCount` is private and must not be called here). They mirror the
 * production semantics deliberately; the discriminating power against a wrong implementation comes
 * from the boundary samples (at/over the 64-code-point limit, astral pairs, the control ranges),
 * not from the reference counting loop alone.
 * The properties checked are: determinism, normalization semantics, code-point counting, and the
 * surrogate-pair boundary.
 */
class CatalogNameNormalizationPropertyTest {
    private class Sample(
        val raw: String,
        val category: String,
    )

    @Test
    fun theCorpusIsNonVacuousAndDeterministic() {
        val first = corpus()
        val second = corpus()
        assertEquals(first.size, second.size)
        assertEquals(first.map { it.raw }, second.map { it.raw })
        assertTrue(first.size >= 200, "corpus size ${first.size}")
        // Non-vacuity: every generated class is really represented.
        listOf("bmp", "supplementary", "whitespace", "control", "boundary").forEach { category ->
            assertTrue(first.count { it.category == category } >= 1, "missing category $category")
        }
        assertTrue(first.count { it.raw.any(::isControlChar) } >= 1, "no control-code-point sample")
        assertTrue(
            first.count { referenceCodePointCount(referenceCollapse(it.raw)) > CATALOG_MAX_NAME_CODE_POINTS } >= 1,
            "no over-limit sample",
        )
        assertTrue(
            first.count { it.raw.any { character -> character.isHighSurrogate() || character.isLowSurrogate() } } >= 1,
            "no supplementary-plane sample",
        )
    }

    @Test
    fun normalizationMatchesTheIndependentReferenceOnEverySample() {
        val allowedTokens: List<DomainViolation> =
            listOf(
                CatalogViolation.CatalogNameEmpty,
                CatalogViolation.CatalogNameInvalid,
                CatalogViolation.CatalogNameTooLong,
            )
        val samples = corpus()
        var successes = 0
        samples.forEach { sample ->
            val actual = normalizeCatalogName(sample.raw)
            val expected = referenceOutcome(sample.raw)
            assertEquals(expected, actual, "category=${sample.category} rawLength=${sample.raw.length}")
            when (actual) {
                is DomainResult.Success -> {
                    // (b) the accepted value is the reference collapse and carries no control code point.
                    assertEquals(referenceCollapse(sample.raw), actual.value)
                    assertTrue(!actual.value.any(::isControlChar), "success kept a control code point")
                    // (d) no surrogate pair was split in half by the collapse.
                    assertTrue(!hasLoneSurrogate(actual.value), "success value holds a lone surrogate")
                    assertTrue(!actual.value.first().isLowSurrogate() && !actual.value.last().isHighSurrogate())
                    successes++
                }
                is DomainResult.Failure -> {
                    assertTrue(actual.violation in allowedTokens, "unexpected failure token ${actual.violation}")
                }
            }
        }
        // Non-vacuity of the branches: the corpus really exercises Success and the size limit.
        assertTrue(successes >= 1, "corpus produced no Success")
        val collapsedCounts = samples.map { referenceCodePointCount(referenceCollapse(it.raw)) }
        assertTrue(collapsedCounts.any { it == CATALOG_MAX_NAME_CODE_POINTS }, "no exactly-at-limit sample")
        assertTrue(collapsedCounts.any { it > CATALOG_MAX_NAME_CODE_POINTS }, "no over-limit sample")
    }

    @Test
    fun successIsExactlyNoControlNonEmptyAndWithinTheCodePointLimit() {
        val samples = corpus()
        var tooLong = 0
        var invalid = 0
        var empty = 0
        samples.forEach { sample ->
            val actual = normalizeCatalogName(sample.raw)
            val collapsed = referenceCollapse(sample.raw)
            val noControl = !sample.raw.any(::isControlChar)
            // (c) Success ⟺ no control code point, non-empty after folding, and within the limit.
            val expectedSuccess =
                noControl &&
                    collapsed.isNotEmpty() &&
                    referenceCodePointCount(collapsed) <= CATALOG_MAX_NAME_CODE_POINTS
            assertEquals(expectedSuccess, actual is DomainResult.Success, "category=${sample.category}")
            // The three failure causes are classified by the reference predicate, not guessed.
            when {
                !noControl -> {
                    assertEquals(CatalogViolation.CatalogNameInvalid, failure(actual))
                    invalid++
                }
                collapsed.isEmpty() -> {
                    assertEquals(CatalogViolation.CatalogNameEmpty, failure(actual))
                    empty++
                }
                referenceCodePointCount(collapsed) > CATALOG_MAX_NAME_CODE_POINTS -> {
                    assertEquals(CatalogViolation.CatalogNameTooLong, failure(actual))
                    tooLong++
                }
            }
        }
        assertTrue(tooLong >= 1 && invalid >= 1 && empty >= 1, "tooLong=$tooLong invalid=$invalid empty=$empty")
    }

    @Test
    fun evaluationIsIdempotentOnEverySample() {
        // (a) determinism: repeated evaluation of the same input yields the identical result.
        corpus().forEach { sample ->
            val first = normalizeCatalogName(sample.raw)
            val second = normalizeCatalogName(sample.raw)
            val third = normalizeCatalogName(sample.raw)
            assertEquals(first, second)
            assertEquals(second, third)
        }
    }

    @Test
    fun supplementaryPlaneSamplesCountCodePointsNotUtf16Units() {
        val astral = corpus().filter { it.category == "supplementary" }
        assertTrue(astral.isNotEmpty())
        var successes = 0
        astral.forEach { sample ->
            val actual = normalizeCatalogName(sample.raw)
            val codePoints = referenceCodePointCount(referenceCollapse(sample.raw))
            if (actual is DomainResult.Success) {
                // A supplementary-plane-only name is two UTF-16 units per code point.
                if (codePoints > 0) {
                    assertEquals(codePoints * 2, actual.value.length, "astral sample length mismatch")
                }
                assertTrue(codePoints <= CATALOG_MAX_NAME_CODE_POINTS)
                successes++
            }
        }
        // Non-vacuity: the loop body above is the load-bearing assertion, so the corpus must really
        // produce at least one accepted supplementary-plane sample.
        assertTrue(successes >= 1, "no accepted supplementary-plane sample")
    }

    // ---------------------------------------------------------------------------------------
    // Deterministic corpus. Fixed seed: the same corpus on every run and on every host.
    // ---------------------------------------------------------------------------------------

    private fun corpus(): List<Sample> {
        val random = Random(20261022)
        val samples = mutableListOf<Sample>()
        repeat(48) {
            samples += Sample(randomBmp(random), "bmp")
        }
        repeat(48) {
            samples += Sample(randomSupplementary(random), "supplementary")
        }
        repeat(48) {
            samples += Sample(randomWhitespaceMix(random), "whitespace")
        }
        repeat(48) {
            samples += Sample(randomWithControl(random), "control")
        }
        samples += boundarySamples()
        return samples
    }

    private fun randomBmp(random: Random): String {
        val builder = StringBuilder()
        repeat(random.nextInt(0, 70)) {
            builder.append(bmpPool[random.nextInt(bmpPool.size)])
        }
        return builder.toString()
    }

    private fun randomSupplementary(random: Random): String {
        val builder = StringBuilder()
        repeat(random.nextInt(0, 70)) {
            builder.append(supplementaryCodePoint(astralPool[random.nextInt(astralPool.size)]))
        }
        return builder.toString()
    }

    private fun randomWhitespaceMix(random: Random): String {
        val builder = StringBuilder()
        repeat(random.nextInt(1, 40)) {
            builder.append(if (random.nextBoolean()) 'x' else whitespacePool[random.nextInt(whitespacePool.size)])
        }
        return builder.toString()
    }

    private fun randomWithControl(random: Random): String {
        val builder = StringBuilder()
        repeat(random.nextInt(0, 8)) { builder.append('a') }
        builder.append(controlPool[random.nextInt(controlPool.size)])
        repeat(random.nextInt(0, 8)) { builder.append('b') }
        return builder.toString()
    }

    private fun boundarySamples(): List<Sample> {
        val atLimit = "x".repeat(CATALOG_MAX_NAME_CODE_POINTS)
        val overLimit = "x".repeat(CATALOG_MAX_NAME_CODE_POINTS + 1)
        val astralAtLimit = "\uD83D\uDE00".repeat(CATALOG_MAX_NAME_CODE_POINTS)
        val astralOverLimit = "\uD83D\uDE00".repeat(CATALOG_MAX_NAME_CODE_POINTS + 1)
        return listOf(
            Sample("", "boundary"),
            Sample(" ", "boundary"),
            Sample("\u3000\u3000", "boundary"),
            Sample(" \u3000 ", "boundary"),
            Sample(atLimit, "boundary"),
            Sample(overLimit, "boundary"),
            Sample(astralAtLimit, "boundary"),
            Sample(astralOverLimit, "boundary"),
            // 64 code points with an interior U+3000 fold to 64 code points: still accepted.
            Sample("x".repeat(31) + "\u3000" + "x".repeat(32), "boundary"),
            // 65 code points with an interior U+3000 fold to 65 code points: rejected.
            Sample("x".repeat(32) + "\u3000" + "x".repeat(32), "boundary"),
            // U+3000 padding is trimmed before counting, so 74 raw code points land on the limit.
            Sample("\u3000".repeat(5) + atLimit + "\u3000".repeat(5), "boundary"),
            Sample("a\u0000b", "boundary"),
            Sample("a\u001Fb", "boundary"),
            Sample("a\u007Fb", "boundary"),
            Sample("a\u009Fb", "boundary"),
            // The boundaries just outside the rejected ranges: U+0020 folds, U+00A0 is kept.
            Sample("a\u0020b", "boundary"),
            Sample("a\u00A0b", "boundary"),
            Sample("a\uFFFDb", "boundary"),
            Sample("\uE000\uE001", "boundary"),
            Sample("\uF8FF", "boundary"),
            Sample("\uD83D\uDE00\u3000\uD83D\uDE00", "boundary"),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Reference model (written here; the production helpers are private and are not called).
    // ---------------------------------------------------------------------------------------

    private fun referenceOutcome(raw: String): DomainResult<String> {
        if (raw.any(::isControlChar)) return DomainResult.Failure(CatalogViolation.CatalogNameInvalid)
        val collapsed = referenceCollapse(raw)
        if (collapsed.isEmpty()) return DomainResult.Failure(CatalogViolation.CatalogNameEmpty)
        if (referenceCodePointCount(collapsed) > CATALOG_MAX_NAME_CODE_POINTS) {
            return DomainResult.Failure(CatalogViolation.CatalogNameTooLong)
        }
        return DomainResult.Success(collapsed)
    }

    /** Trim plus "fold each run of U+0020/U+3000 between kept characters into one U+0020". */
    private fun referenceCollapse(raw: String): String {
        val builder = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val character = raw[index]
            if (character == ' ' || character == '\u3000') {
                var next = index
                while (next < raw.length && (raw[next] == ' ' || raw[next] == '\u3000')) next++
                if (builder.isNotEmpty() && next < raw.length) builder.append(' ')
                index = next
            } else {
                builder.append(character)
                index++
            }
        }
        return builder.toString()
    }

    /** The same counting semantics as the production helper: a valid pair counts as one code point. */
    private fun referenceCodePointCount(value: String): Int {
        var count = 0
        var index = 0
        while (index < value.length) {
            val character = value[index]
            index += if (character.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate()) 2 else 1
            count += 1
        }
        return count
    }

    private fun isControlChar(character: Char): Boolean = character.code < 0x20 || character.code in 0x7F..0x9F

    private fun hasLoneSurrogate(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when {
                character.isHighSurrogate() -> {
                    if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return true
                    index += 2
                }
                character.isLowSurrogate() -> return true
                else -> index++
            }
        }
        return false
    }

    private fun supplementaryCodePoint(codePoint: Int): String {
        val offset = codePoint - 0x10000
        return charArrayOf(((offset shr 10) + 0xD800).toChar(), ((offset and 0x3FF) + 0xDC00).toChar()).concatToString()
    }

    private companion object {
        /** BMP, including the private-use area U+E000-U+F8FF and the replacement character U+FFFD. */
        val bmpPool: List<Char> =
            ('a'..'z').map { it } +
                listOf('\u4E00', '\u4E8C', '\u6C49', '\uFFFD', '\uE000', '\uE555', '\uF8FF', '\u00A0', '\u0020', '\u3000')

        /** Valid supplementary-plane code points, always emitted as a whole surrogate pair. */
        val astralPool: List<Int> = listOf(0x1F600, 0x1F601, 0x20000, 0x2A6D6, 0x1D400)

        val whitespacePool: List<Char> = listOf(' ', '\u3000')

        /** One code point from each rejected range. */
        val controlPool: List<Char> =
            listOf('\u0000', '\u0009', '\u000A', '\u000D', '\u001F', '\u007F', '\u0085', '\u009F')
    }
}
