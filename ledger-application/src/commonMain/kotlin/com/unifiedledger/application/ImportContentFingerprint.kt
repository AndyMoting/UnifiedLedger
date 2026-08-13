package com.unifiedledger.application

/**
 * P4-02 intake content fingerprint (spec section 6).
 *
 * Canonical form: a closed JSON object whose member names are ordered by ascending
 * UTF-16 code units (amount, currency_code, currency_precision, direction_token,
 * occurred_at, record_kind, status_token); only present facts are included and every
 * leaf value is a JSON string, so RFC 8785 number canonicalization is not part of this
 * contract. String escaping and the SHA-256 primitive are the shared Rg09Fingerprint
 * implementations (JcsSha256.kt); the digest is lowercase `sha256:<hex>`.
 *
 * The digest is computed exactly once at intake from the inbound facts and persisted;
 * it is an integrity cross-check only: it is not an identity and never participates in
 * dedup (spec section 6, D-098:1493).
 */
class ImportContentFingerprint {
    fun canonicalJson(facts: ImportSourceFacts): String = buildString {
        append("{\"amount\":").append(jcsString(formatDecimal(facts.amountMinor, facts.currencyPrecision)))
        append(",\"currency_code\":").append(jcsString(facts.currencyCode))
        append(",\"currency_precision\":").append(jcsString(facts.currencyPrecision.toString()))
        append(",\"direction_token\":").append(jcsString(facts.directionToken))
        append(",\"occurred_at\":").append(jcsString(facts.occurredAt))
        append(",\"record_kind\":").append(jcsString(RECORD_KIND))
        if (facts.statusToken != null) {
            append(",\"status_token\":").append(jcsString(facts.statusToken))
        }
        append('}')
    }

    fun digest(facts: ImportSourceFacts): String =
        "sha256:${Sha256.digestHex(canonicalJson(facts).encodeToByteArray())}"

    companion object {
        const val RECORD_KIND = "ordinary_flow_source"
    }
}

internal fun formatDecimal(minorUnits: Long, precision: Int): String {
    require(precision >= 0) { "P4-02 currency precision must not be negative" }
    if (precision == 0) return minorUnits.toString()
    val negative = minorUnits < 0L
    val magnitude = if (minorUnits == Long.MIN_VALUE) {
        "9223372036854775808"
    } else {
        kotlin.math.abs(minorUnits).toString()
    }
    val padded = magnitude.padStart(precision + 1, '0')
    val split = padded.length - precision
    return buildString {
        if (negative) append('-')
        append(padded.substring(0, split))
        append('.')
        append(padded.substring(split))
    }
}
