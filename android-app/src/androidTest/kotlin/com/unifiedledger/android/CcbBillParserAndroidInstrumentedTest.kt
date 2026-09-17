package com.unifiedledger.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unifiedledger.application.import.ccb.CcbBatchOutcome
import com.unifiedledger.application.import.ccb.CcbBillParser
import com.unifiedledger.application.import.ccb.CcbRowResult
import com.unifiedledger.application.import.ccb.CcbSourceTokens
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A-04.1 (spec docs/specs/2026-09-17-p7-04-ccb-android-parser-verification-design.md):
 * on-device execution evidence for the HSSF (Apache POI 5.5.1) CCB XLS parser.
 * The X-4 risk in docs/specs/2026-09-14-p7-04-import-draft-confirmation-design.md
 * holds that HSSF has no official runnable endorsement on Android; this test
 * executes the real production parser against the same anonymous synthetic
 * fixtures and rejection vectors as the JVM oracle, on the Android runtime.
 *
 * Isolated test entry: [CcbBillParser.parse] is called directly with the fixture
 * bytes; platform-gate semantics stay pinned by the existing JVM tests and this
 * batch touches no product code. The equivalence chain is: JVM parse == tracked
 * expected file byte-for-byte (CcbBillParserJvmTest, via the shared
 * CcbBatchResultJson serializer) AND Android parse == the same expected file
 * field-by-field (this class, asserted per row and per field) => same-byte
 * inputs parse equivalently on both runtimes. Assertions are full-granularity:
 * per-row recordKind, all nine facts fields, completeness, and every row
 * diagnostic including non-blocking notes on accepted rows; no weakened subset.
 *
 * The expected file and the fixture copies live in the androidTest assets
 * (fixtures/ccb/); the assets copies are SHA-256-pinned to tests/fixtures by the
 * JVM anti-drift test. Deserialization uses the platform org.json; product
 * types stay annotation-free (test-only batch). Run manually on the managed
 * emulator as gate evidence; CI keeps zero connectedAndroidTest.
 */
@RunWith(AndroidJUnit4::class)
class CcbBillParserAndroidInstrumentedTest {
    /** JUnit assertNotNull returns void; this returns the value for Kotlin expression use. */
    private fun <T> present(
        message: String,
        value: T?,
    ): T {
        assertNotNull(message, value)
        return value!!
    }

    private val fixtureRefs: Map<String, String> =
        mapOf(
            "batch-bp01-ccb-a.xls" to "batch-bp01-ccb-a",
            "batch-bp01-ccb-b1.xls" to "batch-bp01-ccb-b",
            "batch-bp01-ccb-b2.xls" to "batch-bp01-ccb-b",
            "batch-bp01-ccb-c.xls" to "batch-bp01-ccb-c",
            "batch-bp01-ccb-d1.xls" to "batch-bp01-ccb-d",
            "batch-bp01-ccb-d2.xls" to "batch-bp01-ccb-d",
            "batch-bp01-ccb-e.xls" to "batch-bp01-ccb-e",
        )

    private fun assetText(path: String): String =
        InstrumentationRegistry
            .getInstrumentation()
            .context
            .assets
            .open(path)
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun fixtureAssetBytes(name: String): ByteArray =
        InstrumentationRegistry
            .getInstrumentation()
            .context
            .assets
            .open("fixtures/ccb/$name")
            .use { it.readBytes() }

    /** Expected entry per fixture name, from the tracked expected file in the assets. */
    private fun expectedEntriesByName(): Map<String, JSONObject> {
        val fixtures = JSONObject(assetText("fixtures/ccb/expected-batch-bp01-ccb.json")).getJSONArray("fixtures")
        val byName = mutableMapOf<String, JSONObject>()
        for (index in 0 until fixtures.length()) {
            val entry = fixtures.getJSONObject(index)
            byName[entry.getString("fixture")] = entry
        }
        return byName
    }

    @Test
    fun allSevenFixturesParseOnAndroidExactlyAsTheTrackedExpectedFile() {
        val expectedByName = expectedEntriesByName()
        assertEquals(fixtureRefs.size, expectedByName.size)
        fixtureRefs.keys.forEach { name ->
            val expected = expectedByName.getValue(name)
            val result = CcbBillParser.parse(fixtureRefs.getValue(name), fixtureAssetBytes(name))
            assertBatchMatchesExpectedEntry(name, expected, result)
        }
    }

    @Test
    fun mainBatchAcceptedRowsAssertAllNineFactsAndNonBlockingNotes() {
        // Full-granularity walk over the main batch's expected entry: every accepted
        // row's nine facts fields, recordKind and completeness, every rejected row's
        // single diagnostic, and the presence of the non-blocking notes on accepted
        // rows (REQUIRED_FACT_UNRESOLVED at ordinal 7, SPINE_BANK_BALANCE_CONTINUITY
        // at ordinal 10) - the observable outlet of cross-platform differences.
        val expected = expectedEntriesByName().getValue("batch-bp01-ccb-a.xls")
        val result = CcbBillParser.parse(fixtureRefs.getValue("batch-bp01-ccb-a.xls"), fixtureAssetBytes("batch-bp01-ccb-a.xls"))
        assertEquals(CcbBatchOutcome.PARTIAL, result.outcome)
        assertNull(result.diagnostic)

        val rows = expected.getJSONArray("rows")
        assertEquals(18, result.rows.size)
        assertEquals(rows.length(), result.rows.size)

        val diagnosticCodes = mutableSetOf<String>()
        for (index in 0 until rows.length()) {
            val expectedRow = rows.getJSONObject(index)
            val actualRow = result.rows[index]
            assertEquals(expectedRow.getInt("recordOrdinal"), actualRow.recordOrdinal)
            assertEquals(expectedRow.getString("kind"), if (actualRow is CcbRowResult.Accepted) "accepted" else "rejected")
            assertDiagnostics(expectedRow.getJSONArray("diagnostics"), actualRow.diagnostics, diagnosticCodes)
            val accepted = actualRow as? CcbRowResult.Accepted ?: continue
            assertEquals(expectedRow.getString("recordKind"), accepted.recordKind.name)
            assertEquals(expectedRow.getString("completeness"), accepted.completeness.name)
            val facts = expectedRow.getJSONObject("facts")
            assertEquals(facts.getLong("amountMinor"), accepted.facts.amountMinor)
            assertEquals(facts.getString("currencyCode"), accepted.facts.currencyCode)
            assertEquals(facts.getInt("currencyPrecision"), accepted.facts.currencyPrecision)
            assertEquals(facts.getString("occurredAt"), accepted.facts.occurredAt)
            assertEquals(facts.getString("directionToken"), accepted.facts.directionToken)
            assertEquals(facts.optString("statusToken", null), accepted.facts.statusToken)
            assertEquals(facts.getString("fundingState"), accepted.facts.fundingState.name)
            assertEquals(facts.getString("fundingRuleId"), accepted.facts.fundingRuleId)
            assertEquals(facts.getInt("fundingRuleVersion"), accepted.facts.fundingRuleVersion)
        }
        // Non-blocking notes on accepted rows must survive the field walk.
        assertTrue(
            "accepted-row note REQUIRED_FACT_UNRESOLVED missing",
            diagnosticCodes.contains("REQUIRED_FACT_UNRESOLVED"),
        )
        assertTrue(
            "accepted-row note SPINE_BANK_BALANCE_CONTINUITY missing",
            diagnosticCodes.contains("SPINE_BANK_BALANCE_CONTINUITY"),
        )
    }

    @Test
    fun rejectedHeaderBatchesAssertBatchDiagnosticExplicitly() {
        val expectedByName = expectedEntriesByName()
        listOf("batch-bp01-ccb-b1.xls", "batch-bp01-ccb-b2.xls").forEach { name ->
            val expected = expectedByName.getValue(name)
            assertEquals("REJECTED", expected.getString("outcome"))
            val result = CcbBillParser.parse(fixtureRefs.getValue(name), fixtureAssetBytes(name))
            assertEquals(CcbBatchOutcome.REJECTED, result.outcome)
            assertEquals(0, result.rows.size)
            assertEquals(0, expected.getJSONArray("rows").length())
            assertDiagnostic(expected.getJSONObject("batchDiagnostic"), present("batch diagnostic", result.diagnostic))
        }
    }

    // ---- A-04.1 in-memory synthetic rejection vectors (same contract as the JVM twin) ----

    @Test
    fun emptyInputIsTypedRejectedAsInputDecodeFailed() {
        val result = CcbBillParser.parse("synthetic-ccb-empty", ByteArray(0))
        assertEquals(CcbBatchOutcome.REJECTED, result.outcome)
        assertEquals(0, result.rows.size)
        val diagnostic = present("batch diagnostic", result.diagnostic)
        assertEquals("INPUT_DECODE_FAILED", diagnostic.code)
        assertEquals("fatal", diagnostic.severity)
        assertEquals("input", diagnostic.scope)
        assertEquals("synthetic-ccb-empty", diagnostic.inputRef)
        assertNull(diagnostic.recordOrdinal)
        assertNull(diagnostic.fieldRole)
    }

    @Test
    fun overLimitInputIsBytePrecheckRejectedWithoutParsing() {
        val bytes = ByteArray(CcbSourceTokens.MAX_INPUT_BYTES + 1) { 0x41 }
        ole2Magic().copyInto(bytes)
        assertEquals(CcbSourceTokens.MAX_INPUT_BYTES + 1, bytes.size)
        val result = CcbBillParser.parse("synthetic-ccb-overlimit", bytes)
        assertEquals(CcbBatchOutcome.REJECTED, result.outcome)
        assertEquals(0, result.rows.size)
        val diagnostic = present("batch diagnostic", result.diagnostic)
        assertEquals("INPUT_UNSAFE_OR_OVER_LIMIT", diagnostic.code)
        assertEquals("fatal", diagnostic.severity)
        assertEquals("input", diagnostic.scope)
        assertEquals("synthetic-ccb-overlimit", diagnostic.inputRef)
        assertNull(diagnostic.recordOrdinal)
        assertNull(diagnostic.fieldRole)
    }

    @Test
    fun corruptOle2ContainerIsPoiDecodeRejectedWithZeroCandidates() {
        val bytes = ByteArray(4_096) { index -> (index % 256).toByte() }
        ole2Magic().copyInto(bytes)
        val result = CcbBillParser.parse("synthetic-ccb-corrupt", bytes)
        assertEquals(CcbBatchOutcome.REJECTED, result.outcome)
        assertEquals(0, result.rows.size)
        val diagnostic = present("batch diagnostic", result.diagnostic)
        assertEquals("INPUT_DECODE_FAILED", diagnostic.code)
        assertEquals("fatal", diagnostic.severity)
        assertEquals("input", diagnostic.scope)
        assertEquals("synthetic-ccb-corrupt", diagnostic.inputRef)
        assertNull(diagnostic.recordOrdinal)
        assertNull(diagnostic.fieldRole)
    }

    @Test
    fun nonOle2MagicInputIsTypedRejectedAsInputDecodeFailed() {
        val result = CcbBillParser.parse("synthetic-ccb-badmagic", ByteArray(4_096))
        assertEquals(CcbBatchOutcome.REJECTED, result.outcome)
        assertEquals(0, result.rows.size)
        val diagnostic = present("batch diagnostic", result.diagnostic)
        assertEquals("INPUT_DECODE_FAILED", diagnostic.code)
        assertEquals("fatal", diagnostic.severity)
        assertEquals("input", diagnostic.scope)
        assertEquals("synthetic-ccb-badmagic", diagnostic.inputRef)
        assertNull(diagnostic.recordOrdinal)
        assertNull(diagnostic.fieldRole)
    }

    // ---- expected-entry field walkers (the instrumented side of the equivalence chain) ----

    private fun assertBatchMatchesExpectedEntry(
        name: String,
        expected: JSONObject,
        result: com.unifiedledger.application.import.ccb.CcbBatchResult,
    ) {
        assertEquals("outcome mismatch for $name", expected.getString("outcome"), result.outcome.name)
        assertEquals("row count mismatch for $name", expected.getJSONArray("rows").length(), result.rows.size)
        if (expected.isNull("batchDiagnostic")) {
            assertNull("batch diagnostic must be null for $name", result.diagnostic)
        } else {
            assertDiagnostic(expected.getJSONObject("batchDiagnostic"), present("batch diagnostic", result.diagnostic))
        }
        val rows = expected.getJSONArray("rows")
        for (index in 0 until rows.length()) {
            val expectedRow = rows.getJSONObject(index)
            val actualRow = result.rows[index]
            assertEquals("ordinal mismatch in $name row $index", expectedRow.getInt("recordOrdinal"), actualRow.recordOrdinal)
            val expectedKind = expectedRow.getString("kind")
            when (expectedKind) {
                "accepted" -> {
                    val accepted =
                        present(
                            "row $index of $name must be accepted",
                            actualRow as? CcbRowResult.Accepted,
                        )
                    assertEquals(expectedRow.getString("recordKind"), accepted.recordKind.name)
                    assertEquals(expectedRow.getString("completeness"), accepted.completeness.name)
                    val facts = expectedRow.getJSONObject("facts")
                    assertEquals(facts.getLong("amountMinor"), accepted.facts.amountMinor)
                    assertEquals(facts.getString("currencyCode"), accepted.facts.currencyCode)
                    assertEquals(facts.getInt("currencyPrecision"), accepted.facts.currencyPrecision)
                    assertEquals(facts.getString("occurredAt"), accepted.facts.occurredAt)
                    assertEquals(facts.getString("directionToken"), accepted.facts.directionToken)
                    assertEquals(facts.optString("statusToken", null), accepted.facts.statusToken)
                    assertEquals(facts.getString("fundingState"), accepted.facts.fundingState.name)
                    assertEquals(facts.getString("fundingRuleId"), accepted.facts.fundingRuleId)
                    assertEquals(facts.getInt("fundingRuleVersion"), accepted.facts.fundingRuleVersion)
                }
                "rejected" -> {
                    assertTrue(
                        "row $index of $name must be rejected",
                        actualRow is CcbRowResult.Rejected,
                    )
                }
                else -> error("unknown expected row kind in $name: $expectedKind")
            }
            assertDiagnostics(expectedRow.getJSONArray("diagnostics"), actualRow.diagnostics, null)
        }
    }

    private fun assertDiagnostics(
        expected: org.json.JSONArray,
        actual: List<com.unifiedledger.application.import.ccb.CcbDiagnostic>,
        codeSink: MutableSet<String>?,
    ) {
        assertEquals(expected.length(), actual.size)
        for (index in 0 until expected.length()) {
            val actualDiagnostic = actual[index]
            assertDiagnostic(expected.getJSONObject(index), actualDiagnostic)
            if (codeSink != null) {
                codeSink.add(actualDiagnostic.code)
            }
        }
    }

    private fun assertDiagnostic(
        expected: JSONObject,
        actual: com.unifiedledger.application.import.ccb.CcbDiagnostic,
    ) {
        assertEquals(expected.getString("code"), actual.code)
        assertEquals(expected.getString("severity"), actual.severity)
        assertEquals(expected.getString("scope"), actual.scope)
        assertEquals(expected.getString("inputRef"), actual.inputRef)
        assertEquals(
            "recordOrdinal",
            if (expected.isNull("recordOrdinal")) null else expected.getInt("recordOrdinal"),
            actual.recordOrdinal,
        )
        assertEquals(
            "fieldRole",
            if (expected.isNull("fieldRole")) null else expected.optString("fieldRole", null),
            actual.fieldRole,
        )
    }

    private fun ole2Magic(): ByteArray =
        byteArrayOf(
            0xD0.toByte(),
            0xCF.toByte(),
            0x11.toByte(),
            0xE0.toByte(),
            0xA1.toByte(),
            0xB1.toByte(),
            0x1A.toByte(),
            0xE1.toByte(),
        )
}
