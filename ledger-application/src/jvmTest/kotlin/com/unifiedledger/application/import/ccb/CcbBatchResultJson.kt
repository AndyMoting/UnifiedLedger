package com.unifiedledger.application.import.ccb

import com.unifiedledger.application.ImportSourceFacts

/**
 * A-04.1 shared-expectation serializer (test-only; frozen spec
 * docs/specs/2026-09-17-p7-04-ccb-android-parser-verification-design.md section 2.2).
 *
 * Deterministic hand-written JSON serialization of the [CcbBatchResult] parse
 * outcomes for the seven BP-01 CCB fixtures. This is the single wire shape of
 * the equivalence chain: the JVM synchronization test asserts that parsing the
 * fixtures and serializing through this class reproduces the tracked expected
 * file byte-for-byte, while the instrumented twin parses the same fixture bytes
 * on device and asserts field-by-field equality against the same file's
 * deserialized form. Full-object fidelity: per-row recordKind, all nine
 * [com.unifiedledger.application.ImportSourceFacts] fields, completeness, and
 * every row-level diagnostic (including non-blocking notes on accepted rows)
 * are serialized; no field is summarized away.
 *
 * Hand-written on purpose: product types stay annotation-free (the A-04.1 batch
 * is test-only), the shape stays identical on both platforms, and there are no
 * floats anywhere (all numbers are exact integers or quoted strings).
 */
object CcbBatchResultJson {
    /** Serializes the per-fixture parse outcomes in the given (stable, file-order) key order. */
    fun serialize(
        fixtureRefs: Map<String, String>,
        fixtureBytesInOrder: List<ByteArray>,
    ): String {
        require(fixtureRefs.size == fixtureBytesInOrder.size) { "fixture ref count must match byte-stream count" }
        val entries = fixtureRefs.keys.zip(fixtureBytesInOrder)
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"fixtures\": [\n")
        entries.forEachIndexed { fixtureIndex, (name, bytes) ->
            val result = CcbBillParser.parse(fixtureRefs.getValue(name), bytes)
            sb.append("    {\n")
            sb.append("      \"fixture\": ").append(string(name)).append(",\n")
            sb.append("      \"inputRef\": ").append(string(fixtureRefs.getValue(name))).append(",\n")
            sb.append("      \"outcome\": ").append(string(result.outcome.name)).append(",\n")
            sb.append("      \"batchDiagnostic\": ").append(diagnostic(result.diagnostic)).append(",\n")
            if (result.rows.isEmpty()) {
                sb.append("      \"rows\": []\n")
            } else {
                sb.append("      \"rows\": [\n")
                result.rows.forEachIndexed { rowIndex, row ->
                    sb.append("        ").append(row(row))
                    if (rowIndex != result.rows.lastIndex) sb.append(',')
                    sb.append('\n')
                }
                sb.append("      ]\n")
            }
            sb.append("    }")
            if (fixtureIndex != entries.lastIndex) sb.append(',')
            sb.append('\n')
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun row(row: CcbRowResult): String =
        when (row) {
            is CcbRowResult.Accepted ->
                StringBuilder()
                    .append("{\n")
                    .append("          \"kind\": \"accepted\",\n")
                    .append("          \"recordOrdinal\": ")
                    .append(row.recordOrdinal)
                    .append(",\n")
                    .append("          \"recordKind\": ")
                    .append(string(row.recordKind.name))
                    .append(",\n")
                    .append("          \"facts\": ")
                    .append(facts(row.facts))
                    .append(",\n")
                    .append("          \"completeness\": ")
                    .append(string(row.completeness.name))
                    .append(",\n")
                    .append("          \"diagnostics\": [")
                    .append(row.diagnostics.joinToString(", ") { diagnostic(it) })
                    .append("]\n")
                    .append("        }")
                    .toString()
            is CcbRowResult.Rejected ->
                StringBuilder()
                    .append("{\n")
                    .append("          \"kind\": \"rejected\",\n")
                    .append("          \"recordOrdinal\": ")
                    .append(row.recordOrdinal)
                    .append(",\n")
                    .append("          \"diagnostics\": [")
                    .append(row.diagnostics.joinToString(", ") { diagnostic(it) })
                    .append("]\n")
                    .append("        }")
                    .toString()
        }

    private fun facts(facts: ImportSourceFacts): String =
        StringBuilder()
            .append("{\n")
            .append("            \"amountMinor\": ")
            .append(facts.amountMinor)
            .append(",\n")
            .append("            \"currencyCode\": ")
            .append(string(facts.currencyCode))
            .append(",\n")
            .append("            \"currencyPrecision\": ")
            .append(facts.currencyPrecision)
            .append(",\n")
            .append("            \"occurredAt\": ")
            .append(string(facts.occurredAt))
            .append(",\n")
            .append("            \"directionToken\": ")
            .append(string(facts.directionToken))
            .append(",\n")
            .append("            \"statusToken\": ")
            .append(string(facts.statusToken))
            .append(",\n")
            .append("            \"fundingState\": ")
            .append(string(facts.fundingState.name))
            .append(",\n")
            .append("            \"fundingRuleId\": ")
            .append(string(facts.fundingRuleId))
            .append(",\n")
            .append("            \"fundingRuleVersion\": ")
            .append(facts.fundingRuleVersion)
            .append("\n")
            .append("          }")
            .toString()

    private fun diagnostic(diagnostic: CcbDiagnostic?): String {
        if (diagnostic == null) return "null"
        return StringBuilder()
            .append("{")
            .append("\"code\": ")
            .append(string(diagnostic.code))
            .append(", \"severity\": ")
            .append(string(diagnostic.severity))
            .append(", \"scope\": ")
            .append(string(diagnostic.scope))
            .append(", \"inputRef\": ")
            .append(string(diagnostic.inputRef))
            .append(", \"recordOrdinal\": ")
            .append(diagnostic.recordOrdinal)
            .append(", \"fieldRole\": ")
            .append(string(diagnostic.fieldRole))
            .append("}")
            .toString()
    }

    private fun string(raw: String?): String =
        if (raw == null) {
            "null"
        } else {
            val escaped =
                raw
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t")
            "\"$escaped\""
        }
}
