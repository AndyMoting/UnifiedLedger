package com.unifiedledger.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class Rg03ClosedSchemaTest {
    @Test
    fun `all accepted oracle and opening branches are closed to unknown fields`() {
        val raw = validRg03Raw()
        val mutations = listOf(
            raw.replaceFirst("\"expected\":{\"accepted\":true,", "\"expected\":{\"accepted\":true,\"unexpected\":1,") to
                "$.manual_create.expected.unexpected",
            raw.replaceFirst("\"candidate\":{\"id\":\"candidate-id\",", "\"candidate\":{\"id\":\"candidate-id\",\"unexpected\":1,") to
                "$.import_lifecycle.ordered_operations[0].expected.candidate.unexpected",
            raw.replaceFirst("\"candidate\":{\"id\":\"incomplete-candidate\",", "\"candidate\":{\"id\":\"incomplete-candidate\",\"unexpected\":1,") to
                "$.unknown_one_sided_debit.expected.candidate.unexpected",
            raw.replaceFirst("\"expected\":{\"returned_candidate_id\":\"incomplete-candidate\",", "\"expected\":{\"returned_candidate_id\":\"incomplete-candidate\",\"unexpected\":1,") to
                "$.unknown_one_sided_debit.retry.expected.unexpected",
            raw.replaceFirst("\"expected\":{\"accepted\":false,\"field\":\"source_account_id\",\"reason\":\"required\",", "\"expected\":{\"accepted\":false,\"field\":\"source_account_id\",\"reason\":\"required\",\"unexpected\":1,") to
                "$.invalid_manual_inputs[0].expected.unexpected",
            raw.replaceFirst("\"idempotency\":{", "\"idempotency\":{\"unexpected\":1,") to
                "$.idempotency.unexpected",
            raw.replaceFirst("\"opening\":{\"transactions\":[]", "\"opening\":{\"unexpected\":1,\"transactions\":[]") to
                "$.opening.unexpected",
            raw.replaceFirst("\"out_of_scope\":{", "\"out_of_scope\":{\"unexpected\":1,") to
                "$.out_of_scope.unexpected",
        )

        mutations.forEach { (candidate, path) ->
            assertNotEquals(raw, candidate, "mutation for $path must change the fixture")
            val error = assertIs<Rg03RawJsonDecodeResult.Invalid>(decodeRg03RawJson(candidate)).error
            assertEquals(Rg03RawJsonContractErrorReason.UNKNOWN_FIELD, error.reason)
            assertEquals(path, error.fieldPath)
        }
    }

    @Test
    fun `forbidden side effects must remain a string array`() {
        val wrong = validRg03Raw().replaceFirst("\"auto_confirm_import_candidate\"", "{}")
        val error = assertIs<Rg03RawJsonDecodeResult.Invalid>(decodeRg03RawJson(wrong)).error
        assertEquals(Rg03RawJsonContractErrorReason.WRONG_TYPE, error.reason)
        assertEquals("$.forbidden_side_effects[0]", error.fieldPath)
    }
}
