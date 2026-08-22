package com.unifiedledger.application

/**
 * P4-02 intake content fingerprint (spec section 6), additively extended for P4-06
 * slice 1 (D-107 section 3.2).
 *
 * Canonical form: a closed JSON object whose member names are ordered by ascending
 * UTF-16 code units (amount, asset_leg_kind_token, credit_leg_kind_token,
 * currency_code, currency_precision, direction_token, occurred_at, payment_variant,
 * record_kind, status_token); only present facts are included and every leaf value is
 * a JSON string, so RFC 8785 number canonicalization is not part of this contract.
 * The three profile members appear only when a payment profile is present (v3 rows);
 * for a null profile (v1/v2) the output bytes are identical to the pre-P4-06 form, so
 * existing-row replay equivalence is unchanged. String escaping and the SHA-256
 * primitive are the shared Rg09Fingerprint implementations (JcsSha256.kt); the digest
 * is lowercase `sha256:<hex>`.
 *
 * The digest is computed exactly once at intake from the inbound facts and persisted;
 * it is an integrity cross-check only: it is not an identity and never participates in
 * dedup (spec section 6, D-098:1493).
 */
class ImportContentFingerprint {
    fun canonicalJson(
        recordKind: ImportRecordKind,
        facts: ImportSourceFacts,
        paymentProfile: ImportPaymentProfile? = null,
    ): String = buildString {
        append("{\"amount\":").append(jcsString(formatDecimal(facts.amountMinor, facts.currencyPrecision)))
        if (paymentProfile?.assetLegKindToken != null) {
            append(",\"asset_leg_kind_token\":").append(jcsString(paymentProfile.assetLegKindToken))
        }
        if (paymentProfile?.creditLegKindToken != null) {
            append(",\"credit_leg_kind_token\":").append(jcsString(paymentProfile.creditLegKindToken))
        }
        append(",\"currency_code\":").append(jcsString(facts.currencyCode))
        append(",\"currency_precision\":").append(jcsString(facts.currencyPrecision.toString()))
        append(",\"direction_token\":").append(jcsString(facts.directionToken))
        append(",\"occurred_at\":").append(jcsString(facts.occurredAt))
        if (paymentProfile != null) {
            append(",\"payment_variant\":").append(jcsString(paymentProfile.variant.storageValue))
        }
        append(",\"record_kind\":").append(jcsString(recordKind.storageValue))
        if (facts.statusToken != null) {
            append(",\"status_token\":").append(jcsString(facts.statusToken))
        }
        append('}')
    }

    fun digest(
        recordKind: ImportRecordKind,
        facts: ImportSourceFacts,
        paymentProfile: ImportPaymentProfile? = null,
    ): String =
        "sha256:${Sha256.digestHex(canonicalJson(recordKind, facts, paymentProfile).encodeToByteArray())}"
}

/** Exact, privacy-safe P4-07 comparison fingerprint. No provider payload is retained. */
class ImportDuplicateComparisonFingerprint {
    /**
     * Frozen P4-07 comparison projection (D-105 section 3): record kind/version,
     * amount/currency/precision, occurred-at, direction, status presence/value. Members
     * are ordered by ascending UTF-16 code units; every leaf is a JSON string.
     */
    fun canonicalJson(snapshot: ImportDuplicateComparisonSnapshot): String = buildString {
        append("{\"amount_minor\":").append(jcsString(snapshot.amountMinor.toString()))
        append(",\"contract_version\":").append(jcsString(snapshot.contractVersion.toString()))
        append(",\"currency_code\":").append(jcsString(snapshot.currencyCode))
        append(",\"currency_precision\":").append(jcsString(snapshot.currencyPrecision.toString()))
        append(",\"direction_token\":").append(jcsString(snapshot.directionToken))
        append(",\"occurred_at\":").append(jcsString(snapshot.occurredAt))
        append(",\"record_kind\":").append(jcsString(snapshot.recordKind.storageValue))
        append(",\"status_present\":").append(jcsString((snapshot.statusToken != null).toString()))
        if (snapshot.statusToken != null) append(",\"status_token\":").append(jcsString(snapshot.statusToken))
        append('}')
    }

    fun digest(snapshot: ImportDuplicateComparisonSnapshot): String =
        "sha256:${Sha256.digestHex(canonicalJson(snapshot).encodeToByteArray())}"
}

class ImportDuplicateReviewFingerprint {
    fun digest(request: ImportDuplicateReviewRequest): String =
        "sha256:${Sha256.digestHex(canonicalJson(request).encodeToByteArray())}"

    private fun canonicalJson(request: ImportDuplicateReviewRequest): String = buildString {
        append("{\"candidate_id\":").append(jcsString(request.candidateId.value))
        append(",\"decision\":").append(jcsString(request.decision.name))
        append(",\"expected_comparison_fingerprint\":").append(jcsString(request.expectedComparisonFingerprint))
        append(",\"generated_at\":").append(jcsString(request.generatedAt))
        append(",\"history_id\":").append(jcsString(request.historyId.value))
        append(",\"reason_token\":").append(jcsString(request.reasonToken))
        append(",\"review_id\":").append(jcsString(request.reviewId.value))
        append(",\"reviewed_at\":").append(jcsString(request.reviewedAt))
        append(",\"reviewer_reference\":").append(jcsString(request.reviewerReference))
        append('}')
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
