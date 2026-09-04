package com.unifiedledger.domain

/**
 * D-085 shared strict exact-decimal parser (RG-12): parses an amount text against
 * [precision] into exact minor units, mirroring `_attempted_decimal_value` of the golden
 * validator (tools/python/golden_cases/v2.py): optional minus, no leading zeros (except the
 * literal `0`), exactly [precision] fraction digits, and an exact minor-unit conversion
 * without overflow. `null` means the text is not a valid exact decimal string. This helper
 * is the single implementation shared by the domain validation chain
 * ([validatePostingFactsCorrection]) and the RG-12 application layer ([com.unifiedledger.application.Rg12Operations]),
 * which previously carried byte-identical private copies.
 */
fun parseExactDecimal(
    text: String,
    precision: Int,
): Long? {
    if (precision < 0 || precision > 18) return null
    val pattern =
        if (precision == 0) {
            Regex("^-?(?:0|[1-9][0-9]*)$")
        } else {
            Regex("^-?(?:0|[1-9][0-9]*)\\.[0-9]{$precision}$")
        }
    if (!pattern.matches(text)) return null
    val negative = text.startsWith("-")
    val unsigned = if (negative) text.substring(1) else text
    val wholeText = if (precision == 0) unsigned else unsigned.substringBefore('.')
    val fractionText = if (precision == 0) null else unsigned.substringAfter('.')
    val whole = wholeText.toLongOrNull() ?: return null
    val fraction = fractionText?.toLongOrNull() ?: 0L
    val scale = pow10Exact(precision) ?: return null
    val maxWhole = (Long.MAX_VALUE - (scale - 1L)) / scale
    if (whole > maxWhole) return null
    val magnitude = whole * scale + fraction
    return if (negative) -magnitude else magnitude
}

/**
 * D-131 R1 lenient exact-decimal parser for manual expense entry (spec 2.1/2.4): same
 * grammar family as [parseExactDecimal] minus the fixed fraction length —
 * `-?(0|[1-9][0-9]*)(\.[0-9]+)?` — plus two exactness-preserving relaxations. Fraction
 * digits short of [precision] are right-padded with zeros; digits beyond [precision] are
 * accepted only when every excess digit is `'0'` (exact division, never rounding), in
 * which case the first [precision] digits participate. Overflow protection, precision
 * bounds (0..18) and negative-zero handling are identical to [parseExactDecimal]. `null`
 * means the text is not a valid lenient decimal string. [parseExactDecimal] itself is
 * unchanged and remains the strict parser for corrections and RG-12.
 */
fun parseExactDecimalLenient(
    text: String,
    precision: Int,
): Long? {
    if (precision < 0 || precision > 18) return null
    val pattern = Regex("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$")
    if (!pattern.matches(text)) return null
    val negative = text.startsWith("-")
    val unsigned = if (negative) text.substring(1) else text
    val wholeText = unsigned.substringBefore('.')
    val fractionText = unsigned.substringAfter('.', missingDelimiterValue = "")
    if (fractionText.length > precision && fractionText.substring(precision).any { it != '0' }) {
        return null
    }
    val whole = wholeText.toLongOrNull() ?: return null
    val scale = pow10Exact(precision) ?: return null
    val maxWhole = (Long.MAX_VALUE - (scale - 1L)) / scale
    if (whole > maxWhole) return null
    val significantFraction = fractionText.take(precision).padEnd(precision, '0')
    val fraction = if (significantFraction.isEmpty()) 0L else significantFraction.toLongOrNull() ?: return null
    val magnitude = whole * scale + fraction
    return if (negative) -magnitude else magnitude
}

/** Exact power of ten within the Long minor-unit range, or `null` on overflow. */
fun pow10Exact(power: Int): Long? {
    if (power < 0 || power > 18) return null
    var result = 1L
    repeat(power) { result *= 10L }
    return result
}
