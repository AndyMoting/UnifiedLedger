package com.unifiedledger.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class Rg01RawJsonDecoderTest {
    @Test
    fun decodesCreateRetryDistinctAndSevenSparseInvalidOperations() {
        val decoded = assertIs<Rg01RawJsonDecodeResult.Success>(decodeRg01RawJson(fixtureJson())).value

        assertEquals("RG-01", decoded.caseId)
        assertEquals("ledger-a", decoded.context.ledgerId.value)
        assertEquals("35.80", (decoded.create.input.amount as Rg01JsonField.Value).value)
        assertEquals("+08:00", decoded.context.validNumericOffset)
        assertEquals("request-rg01-create", decoded.retry.input.requestId.testValueOrNull())
        assertEquals("request-rg01-distinct-create", decoded.distinct.input.requestId.testValueOrNull())
        assertEquals(7, decoded.invalidInputs.size)
        assertEquals("missing-amount", decoded.invalidInputs.first().source.sourceId)
        assertIs<Rg01JsonField.Null>(decoded.invalidInputs.first().input.amount)
        kotlin.test.assertEquals("request-rg01-note-update", decoded.noteUpdate.input.requestId)
        kotlin.test.assertEquals("tx-expense-rg01", decoded.noteUpdate.input.transactionId)
        kotlin.test.assertEquals("早餐", decoded.noteUpdate.input.note)
        kotlin.test.assertTrue(Rg01UnsupportedSection.NOTE_UPDATE !in decoded.unsupportedSections)
    }

    @Test
    fun rejectsDuplicateKeysIncludingEscapedEquivalentNamesBeforeTreeMapping() {
        val result = decodeRg01RawJson("""{"schema_version":1,"schema_version":1,"case":{},"catalog":{},"opening":{},"create":{},"note_update":{},"idempotency":{},"distinct_reentry":{},"invalid_inputs":[],"forbidden_side_effects":[]}""")
        assertEquals(Rg01RawJsonContractErrorReason.DUPLICATE_KEY, assertIs<Rg01RawJsonDecodeResult.Invalid>(result).error.reason)

        val escaped = decodeRg01RawJson("""{"schema_version":1,"sch\u0065ma_version":1,"case":{},"catalog":{},"opening":{},"create":{},"note_update":{},"idempotency":{},"distinct_reentry":{},"invalid_inputs":[],"forbidden_side_effects":[]}""")
        assertEquals(Rg01RawJsonContractErrorReason.DUPLICATE_KEY, assertIs<Rg01RawJsonDecodeResult.Invalid>(escaped).error.reason)

        val nested = decodeRg01RawJson(fixtureJson().replace("\"timezone\":\"Asia/Shanghai\"", "\"timezone\":\"Asia/Shanghai\",\"time\\u007aone\":\"Asia/Shanghai\""))
        assertEquals(Rg01RawJsonContractErrorReason.DUPLICATE_KEY, assertIs<Rg01RawJsonDecodeResult.Invalid>(nested).error.reason)
    }

    @Test
    fun rejectsUnknownAndWrongTypedNoteUpdateFields() {
        val unknown = decodeRg01RawJson(
            fixtureJson().replace("\"note\":\"早餐\"", "\"note\":\"早餐\",\"extra\":true"),
        )
        assertEquals(
            Rg01RawJsonContractErrorReason.UNKNOWN_FIELD,
            assertIs<Rg01RawJsonDecodeResult.Invalid>(unknown).error.reason,
        )
        val wrongType = decodeRg01RawJson(
            fixtureJson().replace("\"transaction_id\":\"tx-expense-rg01\"", "\"transaction_id\":1"),
        )
        assertEquals(
            Rg01RawJsonContractErrorReason.WRONG_TYPE,
            assertIs<Rg01RawJsonDecodeResult.Invalid>(wrongType).error.reason,
        )

        val malformedExpected = listOf(
            fixtureJson().replace("\"expected\":{\"transaction_id\":\"tx-expense-rg01\"", "\"expected\":{\"transaction_id\":false"),
            fixtureJson().replace("\"current_version_id\":\"version-expense-rg01-v2\"", "\"current_version_id\":false"),
            fixtureJson().replace("\"versions\":[{\"id\":\"version-expense-rg01-v1\"", "\"versions\":[{\"id\":1"),
            fixtureJson().replace("\"effective_posting_set_id\":\"posting-set-expense-rg01\"", "\"effective_posting_set_id\":false"),
            fixtureJson().replace("\"effective_transaction_count\":1", "\"effective_transaction_count\":\"1\""),
            fixtureJson().replace("\"balances\":{\"asset-bank-a\":\"964.20\"}", "\"balances\":[]"),
            fixtureJson().replace("\"statistics\":{\"day\":\"2026-01-15\"}", "\"statistics\":{\"day\":1}"),
            fixtureJson().replace("\"reconciliation\":{\"transaction\":\"pending\"}", "\"reconciliation\":false"),
            fixtureJson().replace("\"evidence_refs\":[]", "\"evidence_refs\":{}"),
            fixtureJson().replace("\"evidence_refs\":[]", "\"evidence_refs\":[{}]"),
        )
        malformedExpected.forEach { raw ->
            assertEquals(
                Rg01RawJsonContractErrorReason.WRONG_TYPE,
                assertIs<Rg01RawJsonDecodeResult.Invalid>(decodeRg01RawJson(raw)).error.reason,
            )
        }
    }

    @Test
    fun rejectsUnknownKeysWrongTypesMalformedAndTrailingInputWithoutLibraryText() {
        val unknown = decodeRg01RawJson(fixtureJson().replace("\"schema_version\": 1", "\"schema_version\": 1, \"extra\": true"))
        assertEquals(Rg01RawJsonContractErrorReason.UNKNOWN_FIELD, assertIs<Rg01RawJsonDecodeResult.Invalid>(unknown).error.reason)

        val nestedUnknown = decodeRg01RawJson(fixtureJson().replace("\"kind\":\"manual_expense\"", "\"kind\":\"manual_expense\",\"extra\":true"))
        assertEquals(Rg01RawJsonContractErrorReason.UNKNOWN_FIELD, assertIs<Rg01RawJsonDecodeResult.Invalid>(nestedUnknown).error.reason)

        val wrongType = decodeRg01RawJson(fixtureJson().replace("\"schema_version\": 1", "\"schema_version\": \"1\""))
        assertEquals(Rg01RawJsonContractErrorReason.WRONG_TYPE, assertIs<Rg01RawJsonDecodeResult.Invalid>(wrongType).error.reason)

        val trailing = decodeRg01RawJson(fixtureJson() + " false")
        val trailingError = assertIs<Rg01RawJsonDecodeResult.Invalid>(trailing).error
        assertEquals(Rg01RawJsonContractErrorReason.MALFORMED_JSON, trailingError.reason)
        kotlin.test.assertFalse(trailingError.message.orEmpty().contains("Unexpected"))
    }

    @Test
    fun rejectsJsonNumberForAmountAndPreservesOmittedVsNull() {
        val number = decodeRg01RawJson(fixtureJson().replace("\"amount\":\"35.80\"", "\"amount\":35.80"))
        assertEquals(Rg01RawJsonContractErrorReason.WRONG_TYPE, assertIs<Rg01RawJsonDecodeResult.Invalid>(number).error.reason)
    }

    @Test
    fun rejectsDeepObjectsDeepArraysAndOversizedInputWithTypedResourceErrors() {
        val deepObject = "{\"a\":".repeat(RG01_RAW_JSON_MAX_NESTING_DEPTH + 1) +
            "null" + "}".repeat(RG01_RAW_JSON_MAX_NESTING_DEPTH + 1)
        val deepArray = "[".repeat(RG01_RAW_JSON_MAX_NESTING_DEPTH + 1) +
            "null" + "]".repeat(RG01_RAW_JSON_MAX_NESTING_DEPTH + 1)
        val oversized = " ".repeat(RG01_RAW_JSON_MAX_UTF8_BYTES + 1)

        listOf(deepObject, deepArray, oversized).forEach { raw ->
            val error = assertIs<Rg01RawJsonDecodeResult.Invalid>(decodeRg01RawJson(raw)).error
            assertEquals(Rg01RawJsonContractErrorReason.RESOURCE_LIMIT, error.reason)
        }
    }

    @Test
    fun countsContainerNestingAtEntryIncludingEmptyObjectAndArray() {
        listOf("{}", "[]").forEach { innermost ->
            val atLimit = nestedObjectContainers(RG01_RAW_JSON_MAX_NESTING_DEPTH, innermost)
            val atLimitError = assertIs<Rg01RawJsonDecodeResult.Invalid>(decodeRg01RawJson(atLimit)).error
            assertNotEquals(Rg01RawJsonContractErrorReason.RESOURCE_LIMIT, atLimitError.reason)

            val overLimit = nestedObjectContainers(RG01_RAW_JSON_MAX_NESTING_DEPTH + 1, innermost)
            val overLimitError = assertIs<Rg01RawJsonDecodeResult.Invalid>(decodeRg01RawJson(overLimit)).error
            assertEquals(Rg01RawJsonContractErrorReason.RESOURCE_LIMIT, overLimitError.reason)
        }
    }

    @Test
    fun classifiesLegalNonObjectRootsAsWrongTypeAndSyntaxErrorsAsMalformed() {
        listOf("[]", "null", "\"root\"", "1", "true").forEach { raw ->
            val error = assertIs<Rg01RawJsonDecodeResult.Invalid>(decodeRg01RawJson(raw)).error
            assertEquals(Rg01RawJsonContractErrorReason.WRONG_TYPE, error.reason)
        }

        val malformed = assertIs<Rg01RawJsonDecodeResult.Invalid>(decodeRg01RawJson("{\"unclosed\":"))
        assertEquals(Rg01RawJsonContractErrorReason.MALFORMED_JSON, malformed.error.reason)
    }

    @Test
    fun enforcesInputLimitInUtf8BytesAndKeepsOneMebibyteInclusive() {
        val multibyte = "\"${"\u754c".repeat(RG01_RAW_JSON_MAX_UTF8_BYTES / 2)}\""
        assertTrue(multibyte.length <= RG01_RAW_JSON_MAX_UTF8_BYTES)
        assertTrue(multibyte.encodeToByteArray().size > RG01_RAW_JSON_MAX_UTF8_BYTES)
        val multibyteError = assertIs<Rg01RawJsonDecodeResult.Invalid>(decodeRg01RawJson(multibyte)).error
        assertEquals(Rg01RawJsonContractErrorReason.RESOURCE_LIMIT, multibyteError.reason)

        val exactlyAtLimit = " ".repeat(RG01_RAW_JSON_MAX_UTF8_BYTES)
        val atLimitError = assertIs<Rg01RawJsonDecodeResult.Invalid>(decodeRg01RawJson(exactlyAtLimit)).error
        assertNotEquals(Rg01RawJsonContractErrorReason.RESOURCE_LIMIT, atLimitError.reason)
    }

    private fun nestedObjectContainers(containerCount: Int, innermost: String): String {
        require(containerCount >= 1)
        return "{\"a\":".repeat(containerCount - 1) +
            innermost +
            "}".repeat(containerCount - 1)
    }

    private fun fixtureJson(): String = """
        {
          "schema_version": 1,
          "case": {"id":"RG-01","level":"core_required","rule_version":1,"timezone":"Asia/Shanghai","currency":"CNY","precision":2,"ledger_id":"ledger-a"},
          "catalog": {"accounts":[{"id":"asset-bank-a","name":"bank","kind":"asset","real_account":true},{"id":"expense-account-breakfast","name":"breakfast","kind":"expense","real_account":false}],"categories":[{"id":"expense-category-food","name":"food","parent_id":null,"posting_account_id":null,"active":true},{"id":"expense-category-breakfast","name":"breakfast","parent_id":"expense-category-food","posting_account_id":"expense-account-breakfast","active":true}]},
          "opening": {"transactions":[],"expected_balances":{}},
          "create": {"confirmation":{"mode":"explicit_manual_save","confirmed":true},"candidate":null,"request":{"request_id":"request-rg01-create","kind":"manual_expense","occurred_at":"2026-01-15T08:30:00+08:00","amount":"35.80","currency":"CNY","category_id":"expense-category-breakfast","payment_account_id":"asset-bank-a","note":""},"expected":{"accepted":true,"transaction":{"id":"tx-expense-rg01","current_version_id":"version-expense-rg01-v1"}}},
          "note_update": {"request":{"request_id":"request-rg01-note-update","transaction_id":"tx-expense-rg01","note":"早餐"},"expected":{"transaction_id":"tx-expense-rg01","current_version_id":"version-expense-rg01-v2","versions":[{"id":"version-expense-rg01-v1","status":"superseded","note":""},{"id":"version-expense-rg01-v2","status":"current","note":"早餐"}],"effective_posting_set_id":"posting-set-expense-rg01","effective_transaction_count":1,"funding_effect_count":1,"balances":{"asset-bank-a":"964.20"},"statistics":{"day":"2026-01-15"},"reconciliation":{"transaction":"pending"},"evidence_refs":[]}},
          "idempotency": {"repeated_request_id":"request-rg01-create","expected":{"returned_transaction_id":"tx-expense-rg01"}},
          "distinct_reentry": {"request":{"request_id":"request-rg01-distinct-create","kind":"manual_expense","occurred_at":"2026-01-15T08:30:00+08:00","amount":"35.80","currency":"CNY","category_id":"expense-category-breakfast","payment_account_id":"asset-bank-a","note":""},"expected":{"accepted":true,"transaction":{"id":"tx-expense-rg01-distinct","current_version_id":"version-expense-rg01-distinct-v1"}}},
          "invalid_inputs": [{"id":"missing-amount","input":{"amount":null,"payment_account_id":"asset-bank-a","category_id":"expense-category-breakfast"},"expected":{"accepted":false,"field":"amount"}},{"id":"missing-payment-account","input":{"amount":"35.80","payment_account_id":null,"category_id":"expense-category-breakfast"},"expected":{"accepted":false,"field":"payment_account_id"}},{"id":"missing-secondary-category","input":{"amount":"35.80","payment_account_id":"asset-bank-a","category_id":null},"expected":{"accepted":false,"field":"category_id"}},{"id":"zero-amount","input":{"amount":"0.00","payment_account_id":"asset-bank-a","category_id":"expense-category-breakfast"},"expected":{"accepted":false,"field":"amount","reason":"must_be_positive"}},{"id":"negative-amount","input":{"amount":"-0.01","payment_account_id":"asset-bank-a","category_id":"expense-category-breakfast"},"expected":{"accepted":false,"field":"amount","reason":"must_be_positive"}},{"id":"primary-category","input":{"amount":"35.80","payment_account_id":"asset-bank-a","category_id":"expense-category-food"},"expected":{"accepted":false,"field":"category_id","reason":"secondary_category_required"}},{"id":"inactive-secondary-category","input":{"amount":"35.80","payment_account_id":"asset-bank-a","category_id":"expense-category-inactive"},"expected":{"accepted":false,"field":"category_id","reason":"category_inactive"}}],
          "forbidden_side_effects": ["invoke_network"]
        }
    """.trimIndent()
}

private fun <T> Rg01JsonField<T>.testValueOrNull(): T? =
    (this as? Rg01JsonField.Value<T>)?.value
