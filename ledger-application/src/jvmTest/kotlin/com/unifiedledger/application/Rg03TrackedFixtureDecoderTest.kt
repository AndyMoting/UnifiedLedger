package com.unifiedledger.application

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class Rg03TrackedFixtureDecoderTest {
    @Test
    fun `tracked v1 fixture decodes to all approved executions`() {
        val raw = trackedRg03Raw()
        val decoded = assertIs<Rg03RawJsonDecodeResult.Success>(decodeRg03RawJson(raw)).value

        assertEquals(20, decoded.operations.size)
        assertEquals(Rg03JsonField.Value("request-rg03-manual-create"), decoded.operations[0].input.requestId)
        assertEquals(Rg03JsonField.Value("source-record-rg03-debit"), decoded.operations[1].input.sourceId)
        assertEquals(Rg03JsonField.Value("candidate-transfer-rg03"), decoded.operations[2].input.candidateId)
        assertEquals(Rg03JsonField.Value("source-record-rg03-credit-mirror"), decoded.operations[3].input.sourceId)
    }

    @Test
    fun `manual request kind is exact`() {
        val raw = replaceRg03Value(
            listOf(Key("manual_create"), Key("request"), Key("kind")),
            JsonPrimitive("ordinary_expense"),
        )

        assertDecodeError(
            raw,
            "$.manual_create.request.kind",
            Rg03RawJsonContractErrorReason.INVALID_VALUE,
        )
    }

    @Test
    fun `expected oracle branches require their path specific state shape`() {
        val cases = listOf(
            listOf(Key("opening"), Key("transactions")) to "$.opening.transactions",
            listOf(Key("manual_create"), Key("expected"), Key("transaction"), Key("postings")) to
                "$.manual_create.expected.transaction.postings",
            listOf(Key("import_lifecycle"), Key("ordered_operations"), Index(0), Key("expected"), Key("candidate")) to
                "$.import_lifecycle.ordered_operations[0].expected.candidate",
            listOf(Key("import_lifecycle"), Key("ordered_operations"), Index(1), Key("expected"), Key("transaction")) to
                "$.import_lifecycle.ordered_operations[1].expected.transaction",
            listOf(Key("import_lifecycle"), Key("ordered_operations"), Index(2), Key("expected"), Key("posting_ids")) to
                "$.import_lifecycle.ordered_operations[2].expected.posting_ids",
            listOf(Key("unknown_one_sided_debit"), Key("expected"), Key("candidate")) to
                "$.unknown_one_sided_debit.expected.candidate",
            listOf(Key("unknown_one_sided_debit"), Key("retry"), Key("expected"), Key("returned_candidate_id")) to
                "$.unknown_one_sided_debit.retry.expected.returned_candidate_id",
            listOf(Key("idempotency"), Key("expected"), Key("manual_state")) to
                "$.idempotency.expected.manual_state",
            listOf(Key("invalid_manual_inputs"), Index(0), Key("expected"), Key("state_unchanged")) to
                "$.invalid_manual_inputs[0].expected.state_unchanged",
        )

        cases.forEach { (path, expectedErrorPath) ->
            assertDecodeError(
                removeRg03Value(path),
                expectedErrorPath,
                Rg03RawJsonContractErrorReason.WRONG_TYPE,
            )
        }
    }

    @Test
    fun `adapter rejects every present currency field that disagrees`() {
        val context = Rg03AdapterContext(
            ledgerId = com.unifiedledger.domain.LedgerId("ledger-a"),
            currency = com.unifiedledger.domain.CurrencyUnit("CNY", 2),
        )
        val cases = listOf(
            "source_currency" to Rg03ContractError(
                "$.input.source_currency",
                Rg03ContractErrorReason.SAME_CURRENCY_REQUIRED,
            ),
            "destination_currency" to Rg03ContractError(
                "$.input.destination_currency",
                Rg03ContractErrorReason.SAME_CURRENCY_REQUIRED,
            ),
        )

        cases.forEach { (field, expectedError) ->
            val raw = replaceRg03Value(
                listOf(Key("manual_create"), Key("request"), Key(field)),
                JsonPrimitive("USD"),
                insert = true,
            )
            val decoded = assertIs<Rg03RawJsonDecodeResult.Success>(decodeRg03RawJson(raw)).value

            assertEquals(
                Rg03AdaptResult.Invalid(expectedError),
                adaptRg03Operation(context, decoded.operations.first()),
            )
        }
    }
}

private sealed interface FixturePath
private data class Key(val value: String) : FixturePath
private data class Index(val value: Int) : FixturePath

private val fixtureJson = Json { prettyPrint = false }

private fun trackedRg03Raw(): String = Files.readString(repositoryFile("golden/rules/rg-03.json"))

private fun removeRg03Value(path: List<FixturePath>): String =
    mutateRg03(path) { null }

private fun replaceRg03Value(
    path: List<FixturePath>,
    replacement: JsonElement,
    insert: Boolean = false,
): String = mutateRg03(path, insert) { replacement }

private fun mutateRg03(
    path: List<FixturePath>,
    insert: Boolean = false,
    replacement: (JsonElement?) -> JsonElement?,
): String {
    val root = fixtureJson.parseToJsonElement(trackedRg03Raw())
    return mutateAt(root, path, insert, replacement).toString()
}

private fun mutateAt(
    element: JsonElement,
    path: List<FixturePath>,
    insert: Boolean,
    replacement: (JsonElement?) -> JsonElement?,
): JsonElement {
    require(path.isNotEmpty())
    val segment = path.first()
    val tail = path.drop(1)
    return when (segment) {
        is Key -> {
            val objectValue = element as JsonObject
            val current = objectValue[segment.value]
            require(insert || current != null) { "missing fixture path key ${segment.value}" }
            val updated = if (tail.isEmpty()) replacement(current) else mutateAt(requireNotNull(current), tail, insert, replacement)
            JsonObject(objectValue.toMutableMap().apply {
                if (updated == null) remove(segment.value) else put(segment.value, updated)
            })
        }
        is Index -> {
            val arrayValue = element as JsonArray
            val updated = if (tail.isEmpty()) {
                requireNotNull(replacement(arrayValue[segment.value]))
            } else {
                mutateAt(arrayValue[segment.value], tail, insert, replacement)
            }
            JsonArray(arrayValue.toMutableList().apply { this[segment.value] = updated })
        }
    }
}

private fun assertDecodeError(
    raw: String,
    path: String,
    reason: Rg03RawJsonContractErrorReason,
) {
    assertEquals(
        Rg03RawJsonContractError(path, reason),
        assertIs<Rg03RawJsonDecodeResult.Invalid>(decodeRg03RawJson(raw)).error,
    )
}

private fun repositoryFile(relative: String): Path {
    var candidate = Path.of(System.getProperty("user.dir"))
    repeat(6) {
        if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)
        candidate = candidate.parent ?: error("repository root not found")
    }
    error("repository root not found")
}
