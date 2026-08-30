package com.unifiedledger.ui

/**
 * Exact minor-unit display formatting without binary floating point. The domain forbids
 * Long.MIN_VALUE postings (checked negation), so the display pipeline never sees it; the
 * guard is defensive only.
 */
internal fun formatMinorUnits(
    minorUnits: Long,
    precision: Int,
): String {
    require(precision in 0..18) { "currency precision must be in 0..18" }
    val sign = if (minorUnits < 0L) "-" else ""
    val magnitude =
        when {
            minorUnits == Long.MIN_VALUE -> Long.MAX_VALUE
            minorUnits < 0L -> -minorUnits
            else -> minorUnits
        }
    val digits = magnitude.toString()
    if (precision == 0) return sign + digits
    val padded = digits.padStart(precision + 1, '0')
    val whole = padded.substring(0, padded.length - precision)
    val fraction = padded.substring(padded.length - precision)
    return sign + whole + "." + fraction
}
