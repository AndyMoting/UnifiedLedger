package com.unifiedledger.application.import.alipay

import com.unifiedledger.application.IMPORT_FUNDING_RULE_LEGACY_SETTLED
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportSourceFacts
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Same bytes as the Python generator's tiny profile; not a second CSV builder. */
class AndroidScaleFixtureParserJvmTest {
    private fun fixture(name: String): ByteArray {
        val root =
            generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
                .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        return Files.readAllBytes(root.resolve("tests/fixtures/android-scale/$name"))
    }

    private fun expected(amount: Long) =
        ImportSourceFacts(
            amount,
            "CNY",
            2,
            "2026-01-15T08:00:00+08:00",
            "out",
            "settled",
            ImportFundingState.SETTLED,
            IMPORT_FUNDING_RULE_LEGACY_SETTLED,
            1,
        )

    private fun parsed(
        name: String,
        inputRef: String,
        amounts: List<Long>,
    ): List<ImportSourceFacts> {
        val result = AlipayCsvParser.parse(inputRef, fixture(name))
        assertEquals(AlipayBatchOutcome.COMPLETE, result.outcome)
        assertNull(result.diagnostic)
        assertEquals(amounts.size, result.rows.size)
        return result.rows.mapIndexed { ordinal, row ->
            val accepted = assertIs<AlipayRowResult.Accepted>(row)
            assertEquals(ordinal, accepted.recordOrdinal)
            assertEquals(ImportRecordKind.ORDINARY_FLOW_SOURCE, accepted.recordKind)
            assertEquals(ImportCompleteness.VALID_COMPLETE, accepted.completeness)
            assertTrue(accepted.diagnostics.isEmpty())
            assertNull(accepted.paymentProfile)
            assertEquals(expected(amounts[ordinal]), accepted.facts)
            accepted.facts
        }
    }

    @Test
    fun tinyGeneratorBytesProduceExactOrdinarySourceFactsAcrossSixSessions() {
        val sessions =
            (1..6).map { session ->
                parsed("shared-small.csv", "scale-session-$session", listOf(197199L, 197200L, 197201L))
            }
        val unique = parsed("unique-small.csv", "scale-unique", listOf(197202L, 197203L))
        sessions.forEach { assertEquals(sessions.first(), it) }
        assertTrue(unique.none { it in sessions.first() })
        assertEquals(3, sessions.first().toSet().size)
        // Enumerate actual parsed equal pairs independently of the generator's formula.
        fun pairs(sources: List<ImportSourceFacts>): Int =
            sources.indices.sumOf { right -> (0 until right).count { left -> sources[left] == sources[right] } }
        val prepared = sessions.take(5).flatten() + unique
        assertEquals(17, prepared.size)
        assertEquals(30, pairs(prepared))
        val all = prepared + sessions.last()
        assertEquals(20, all.size)
        assertEquals(45, pairs(all))
        assertEquals(15, pairs(all) - pairs(prepared))
    }

    @Test
    fun changedHeaderIsRejectedWithoutScanningForAnotherHeader() {
        val original = fixture("shared-small.csv").toString(Charsets.UTF_8)
        val result = AlipayCsvParser.parse("invalid-header", original.replace("交易时间", "时间").toByteArray())
        assertEquals(AlipayBatchOutcome.REJECTED, result.outcome)
        assertTrue(result.rows.isEmpty())
        assertEquals("STRUCTURE_MISMATCH", result.diagnostic?.code)
    }

    @Test
    fun lostTabAndInvalidAmountRejectOnlyTheAffectedRow() {
        val original = fixture("shared-small.csv").toString(Charsets.UTF_8)
        val variants =
            listOf(
                original.replaceFirst("SYN-ORDER-00000\t", "SYN-ORDER-00000") to "STRUCTURE_MISMATCH",
                original.replaceFirst("1971.99", "1971.9") to "FIELD_AMOUNT_INVALID",
            )
        variants.forEach { (text, code) ->
            val result = AlipayCsvParser.parse("invalid-record", text.toByteArray())
            assertEquals(AlipayBatchOutcome.PARTIAL, result.outcome)
            assertEquals(3, result.rows.size)
            val rejected = assertIs<AlipayRowResult.Rejected>(result.rows[0])
            assertEquals(0, rejected.recordOrdinal)
            assertEquals(listOf(code), rejected.diagnostics.map { it.code })
            assertEquals(expected(197200), assertIs<AlipayRowResult.Accepted>(result.rows[1]).facts)
            assertEquals(expected(197201), assertIs<AlipayRowResult.Accepted>(result.rows[2]).facts)
        }
    }

    @Test
    fun crLfCheckoutWouldFailTheFrozenProductionHeader() {
        val original = fixture("shared-small.csv").toString(Charsets.UTF_8)
        val result = AlipayCsvParser.parse("invalid-crlf", original.replace("\n", "\r\n").toByteArray())
        assertEquals(AlipayBatchOutcome.REJECTED, result.outcome)
        assertEquals("STRUCTURE_MISMATCH", result.diagnostic?.code)
    }
}
