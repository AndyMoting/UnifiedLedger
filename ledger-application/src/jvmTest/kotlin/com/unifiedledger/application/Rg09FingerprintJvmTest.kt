package com.unifiedledger.application

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class Rg09FingerprintJvmTest {
    @Test
    fun `D-065 JCS bytes and runtime digest match JVM SHA-256`() {
        val projection =
            Rg09LedgerFingerprintProjection(
                listOf(
                    Rg09FingerprintPosting(
                        transactionId = "transaction-1",
                        currentVersionId = "version-1",
                        effectiveAt = "2026-01-01T00:00:00+08:00",
                        postingId = "posting-1",
                        accountId = "asset-a",
                        currency = "CNY",
                        amount = "1.00",
                    ),
                ),
            )

        val expectedJcs = "{\"postings\":[{\"account_id\":\"asset-a\",\"amount\":\"1.00\",\"currency\":\"CNY\",\"current_version_id\":\"version-1\",\"effective_at\":\"2026-01-01T00:00:00+08:00\",\"posting_id\":\"posting-1\",\"transaction_id\":\"transaction-1\"}]}"
        val jcsBytes = projection.canonicalJson().encodeToByteArray()
        val jvmDigest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(jcsBytes)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        assertContentEquals(expectedJcs.encodeToByteArray(), jcsBytes)
        assertEquals("72c5a33f14227ba4c80d0730ea4a65b721fc28f18699bfd8aef24387cb78756e", jvmDigest)
        assertEquals("sha256:$jvmDigest", Rg09LedgerFingerprint.digest(projection))
    }
}
