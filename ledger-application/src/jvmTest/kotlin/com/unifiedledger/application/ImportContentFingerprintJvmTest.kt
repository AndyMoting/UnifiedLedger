package com.unifiedledger.application

import com.unifiedledger.domain.LedgerId
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * T-18: P4-02 content fingerprint determinism, member omission, escaping, and the
 * pinned fixture digests cross-checked against an independent JVM SHA-256 over the
 * hand-written canonical JSON (second-implementation check, spec section 9 T-18).
 */
class ImportContentFingerprintJvmTest {
    private val fingerprint = ImportContentFingerprint()

    private val r1 =
        ImportSourceFacts(
            amountMinor = 12850,
            currencyCode = "CNY",
            currencyPrecision = 2,
            occurredAt = "2026-08-01T12:30:00+08:00",
            directionToken = "out",
            statusToken = "settled",
            ImportFundingState.SETTLED,
            IMPORT_FUNDING_RULE_LEGACY_SETTLED,
            1,
        )
    private val r2 =
        ImportSourceFacts(
            amountMinor = 1000000,
            currencyCode = "CNY",
            currencyPrecision = 2,
            occurredAt = "2026-08-05T09:00:00+08:00",
            directionToken = "in",
            statusToken = "settled",
            ImportFundingState.SETTLED,
            IMPORT_FUNDING_RULE_LEGACY_SETTLED,
            1,
        )
    private val r3 =
        ImportSourceFacts(
            amountMinor = 4500,
            currencyCode = "CNY",
            currencyPrecision = 2,
            occurredAt = "2026-08-06T18:45:00+08:00",
            directionToken = "out",
            statusToken = null,
            ImportFundingState.SETTLED,
            IMPORT_FUNDING_RULE_LEGACY_SETTLED,
            1,
        )
    private val r1Prime =
        ImportSourceFacts(
            amountMinor = 12851,
            currencyCode = "CNY",
            currencyPrecision = 2,
            occurredAt = "2026-08-01T12:30:00+08:00",
            directionToken = "out",
            statusToken = "settled",
            ImportFundingState.SETTLED,
            IMPORT_FUNDING_RULE_LEGACY_SETTLED,
            1,
        )
    private val r5 =
        ImportSourceFacts(
            amountMinor = 888800,
            currencyCode = "CNY",
            currencyPrecision = 2,
            occurredAt = "2026-08-08T10:00:00+08:00",
            directionToken = "in",
            statusToken = "settled",
            ImportFundingState.SETTLED,
            IMPORT_FUNDING_RULE_LEGACY_SETTLED,
            1,
        )

    @Test
    fun `T-18 R1 canonical bytes and pinned digest match independent JVM SHA-256`() {
        val canonical =
            """{"amount":"128.50","currency_code":"CNY","currency_precision":"2","direction_token":"out","occurred_at":"2026-08-01T12:30:00+08:00","record_kind":"ordinary_flow_source","status_token":"settled"}"""
        assertEquals(canonical, fingerprint.canonicalJson(ImportRecordKind.ORDINARY_FLOW_SOURCE, r1))
        val expected =
            "sha256:afd8167ab6353423ef5632ae2a458f79bc4788f833f304c66b2fc8cf552a07e2"
        assertEquals(expected, fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, r1))
        assertEquals(expected, "sha256:" + jvmSha256(canonical.encodeToByteArray()))
    }

    @Test
    fun `T-18 R2 R3 R1-prime R5 pinned digests match independent JVM SHA-256`() {
        assertEquals(
            "sha256:5a5860ec8dd13eaa03b45627e5403c4ce62cd051c57e3a5a9d5c40f871245c89",
            fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, r2),
        )
        assertEquals(
            "sha256:5a5860ec8dd13eaa03b45627e5403c4ce62cd051c57e3a5a9d5c40f871245c89",
            "sha256:" + jvmSha256(fingerprint.canonicalJson(ImportRecordKind.ORDINARY_FLOW_SOURCE, r2).encodeToByteArray()),
        )

        // R3: absent status_token is omitted from the canonical document.
        val canonicalR3 = fingerprint.canonicalJson(ImportRecordKind.ORDINARY_FLOW_SOURCE, r3)
        assertEquals(
            """{"amount":"45.00","currency_code":"CNY","currency_precision":"2","direction_token":"out","occurred_at":"2026-08-06T18:45:00+08:00","record_kind":"ordinary_flow_source"}""",
            canonicalR3,
        )
        assertEquals(
            "sha256:911f0b27473a382752837ac1eaca05e9f7ab1d13fc944b8e5e349b30fb86fe35",
            fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, r3),
        )
        assertEquals(
            "sha256:911f0b27473a382752837ac1eaca05e9f7ab1d13fc944b8e5e349b30fb86fe35",
            "sha256:" + jvmSha256(canonicalR3.encodeToByteArray()),
        )

        val canonicalR1Prime = fingerprint.canonicalJson(ImportRecordKind.ORDINARY_FLOW_SOURCE, r1Prime)
        assertEquals(
            "sha256:bffe1da79bcb3411fab3b6226aa9f5696eee27b6361c811681f88eaabba6ecc4",
            fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, r1Prime),
        )
        assertEquals(
            "sha256:bffe1da79bcb3411fab3b6226aa9f5696eee27b6361c811681f88eaabba6ecc4",
            "sha256:" + jvmSha256(canonicalR1Prime.encodeToByteArray()),
        )
        assertEquals(
            "sha256:80b823a2a5a392a431c15e84b2ca1783337c57d53a2b940f414b53befeef1e47",
            fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, r5),
        )
        assertEquals(
            "sha256:80b823a2a5a392a431c15e84b2ca1783337c57d53a2b940f414b53befeef1e47",
            "sha256:" + jvmSha256(fingerprint.canonicalJson(ImportRecordKind.ORDINARY_FLOW_SOURCE, r5).encodeToByteArray()),
        )
    }

    @Test
    fun `T-18 repeated digests are deterministic and different facts differ`() {
        repeat(3) { assertEquals(fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, r1), fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, r1)) }
        assertEquals(
            "sha256:afd8167ab6353423ef5632ae2a458f79bc4788f833f304c66b2fc8cf552a07e2",
            fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, r1),
        )
        assertNotEqualBytes(fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, r1), fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, r1Prime))
        assertNotEqualBytes(fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, r1), fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, r3))
    }

    @Test
    fun `T-18 RFC 8785 named short escapes and u00XX escaping match the frozen emitter`() {
        val facts =
            ImportSourceFacts(
                amountMinor = 1,
                currencyCode = "CNY",
                currencyPrecision = 0,
                occurredAt = "quote\"back\\slash",
                directionToken = "ctrl\u0001\u001f",
                statusToken = "tab\tnewline\nform\u000Creturn\r",
                ImportFundingState.SETTLED,
                IMPORT_FUNDING_RULE_LEGACY_SETTLED,
                1,
            )
        val canonical = fingerprint.canonicalJson(ImportRecordKind.ORDINARY_FLOW_SOURCE, facts)
        assertEquals(
            """{"amount":"1","currency_code":"CNY","currency_precision":"0","direction_token":"ctrl\u0001\u001f","occurred_at":"quote\"back\\slash","record_kind":"ordinary_flow_source","status_token":"tab\tnewline\nform\freturn\r"}""",
            canonical,
        )
        assertEquals(fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, facts), "sha256:" + jvmSha256(canonical.encodeToByteArray()))
    }

    @Test
    fun `T-18 unpaired surrogates fail closed`() {
        val high = ImportSourceFacts(1, "CNY", 0, "\ud800", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
        assertFailsWith<IllegalArgumentException> { fingerprint.canonicalJson(ImportRecordKind.ORDINARY_FLOW_SOURCE, high) }
        val low = ImportSourceFacts(1, "CNY", 0, "\udc00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
        assertFailsWith<IllegalStateException> { fingerprint.canonicalJson(ImportRecordKind.ORDINARY_FLOW_SOURCE, low) }
    }

    @Test
    fun `T-18 decimal formatting pads to precision like the RG-09 emitter`() {
        assertEquals("128.50", fingerprint.canonicalJson(ImportRecordKind.ORDINARY_FLOW_SOURCE, r1).substringAfter("\"amount\":\"").substringBefore('"'))
        assertEquals("8888.00", fingerprint.canonicalJson(ImportRecordKind.ORDINARY_FLOW_SOURCE, r5).substringAfter("\"amount\":\"").substringBefore('"'))
        assertEquals("1", formatDecimal(1, 0))
        assertEquals("0.05", formatDecimal(5, 2))
        assertEquals("-128.50", formatDecimal(-12850, 2))
    }

    @Test
    fun `high source precision fingerprint stays exact and bounded`() {
        val zero =
            ImportSourceFacts(
                amountMinor = 0,
                currencyCode = "CNY",
                currencyPrecision = Int.MAX_VALUE,
                occurredAt = "2026-08-01T12:30:00+08:00",
                directionToken = "out",
                statusToken = "settled",
                fundingState = ImportFundingState.SETTLED,
                fundingRuleId = IMPORT_FUNDING_RULE_LEGACY_SETTLED,
                fundingRuleVersion = 1,
            )
        val nonZero = zero.copy(amountMinor = 1)
        val zeroCanonical = fingerprint.canonicalJson(ImportRecordKind.ORDINARY_FLOW_SOURCE, zero)
        assertTrue(zeroCanonical.length < 1024)
        assertEquals(
            "0e-${Int.MAX_VALUE}",
            zeroCanonical.substringAfter("\"amount\":\"").substringBefore('"'),
        )
        assertEquals(
            "1e-${Int.MAX_VALUE}",
            fingerprint
                .canonicalJson(ImportRecordKind.ORDINARY_FLOW_SOURCE, nonZero)
                .substringAfter("\"amount\":\"")
                .substringBefore('"'),
        )
        assertEquals(zeroCanonical, fingerprint.canonicalJson(ImportRecordKind.ORDINARY_FLOW_SOURCE, zero))
        assertNotEqualBytes(
            fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, zero),
            fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, nonZero),
        )
    }

    @Test
    fun `P4-07 review claim fingerprint is canonical and changes with every immutable input`() {
        val request =
            ImportDuplicateReviewRequest(
                ImportRequestIdentity(LedgerId("ledger-p407"), ImportRequestId("review-request")),
                ImportDuplicateCandidateId("duplicate-1"),
                "sha256:comparison",
                ImportDuplicateStatus.CONFIRMED_DUPLICATE,
                "confirmed",
                "2026-08-19T10:00:00+08:00",
                "reviewer",
                "2026-08-19T10:01:00+08:00",
                ImportDuplicateReviewId("review-1"),
                ImportStatusHistoryId("history-1"),
            )
        val digest = ImportDuplicateReviewFingerprint().digest(request)
        assertEquals(digest, ImportDuplicateReviewFingerprint().digest(request))
        kotlin.test.assertNotEquals(digest, ImportDuplicateReviewFingerprint().digest(request.copy(reasonToken = "different")))
    }

    @Test
    fun `P4-07 comparison fingerprint pins the frozen projection including contract_version`() {
        // SPEC-004 (D-105 sections 2/3): the frozen comparison projection is record
        // kind/version, amount/currency/precision, occurred-at, direction, status
        // presence/value, subject/possible-existing IDs. contract_version participates in
        // both the canonical bytes and the digest; versions of the same kind differ.
        val comparison = ImportDuplicateComparisonFingerprint()
        val v1 =
            ImportDuplicateComparisonSnapshot(
                subjectSourceId = ImportSourceId("source-subject"),
                possibleExistingSourceId = ImportSourceId("source-existing"),
                recordKind = ImportRecordKind.ORDINARY_FLOW_SOURCE,
                contractVersion = 1,
                amountMinor = 12850,
                currencyCode = "CNY",
                currencyPrecision = 2,
                occurredAt = "2026-08-01T12:30:00+08:00",
                directionToken = "out",
                statusToken = "settled",
            )
        val canonical =
            """{"amount_minor":"12850","contract_version":"1","currency_code":"CNY","currency_precision":"2","direction_token":"out","occurred_at":"2026-08-01T12:30:00+08:00","record_kind":"ordinary_flow_source","status_present":"true","status_token":"settled"}"""
        assertEquals(canonical, comparison.canonicalJson(v1))
        assertEquals("sha256:" + jvmSha256(canonical.encodeToByteArray()), comparison.digest(v1))

        // Null status keeps presence semantics; the omitted value member still differs.
        val noStatus = v1.copy(statusToken = null)
        assertEquals(
            """{"amount_minor":"12850","contract_version":"1","currency_code":"CNY","currency_precision":"2","direction_token":"out","occurred_at":"2026-08-01T12:30:00+08:00","record_kind":"ordinary_flow_source","status_present":"false"}""",
            comparison.canonicalJson(noStatus),
        )
        assertNotEqualBytes(comparison.digest(v1), comparison.digest(noStatus))
        assertNotEqualBytes(comparison.digest(v1), comparison.digest(v1.copy(contractVersion = 2)))
        // The digest covers only the business tuple: subject/possible-existing source IDs
        // are frozen into the persisted comparison_snapshot JSON (and the uniqueness
        // index), never into the fingerprint.
        assertEquals(comparison.digest(v1), comparison.digest(v1.copy(possibleExistingSourceId = null)))
    }

    private fun jvmSha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun assertNotEqualBytes(
        left: String,
        right: String,
    ) {
        kotlin.test.assertNotEquals(left, right)
    }
}
