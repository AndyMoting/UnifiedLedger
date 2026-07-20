package com.unifiedledger.application

import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CategoryKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class Rg02RawJsonDecoderTest {
    @Test
    fun `closed fixture decodes and category rename remains explicit unsupported input`() {
        val decoded = assertIs<Rg02RawJsonDecodeResult.Success>(decodeRg02RawJson(validRaw())).value
        assertEquals("income-child", decoded.unsupportedCategoryRename.categoryId)
        assertEquals("request-create", decoded.retryRequestId)
        assertEquals(Rg02JsonField.Value(true), decoded.create.input.explicitConfirmation)
        assertEquals(true, decoded.create.expected.effective)
        assertEquals(AccountKind.ASSET, decoded.catalog.accounts.single().kind)
        assertEquals(true, decoded.catalog.accounts.single().ownedByUser)
        assertEquals(CategoryKind.INCOME, decoded.catalog.categories.single().kind)
    }

    @Test
    fun `nested unknown fields and malformed primitive types return typed invalid`() {
        val unknown = validRaw().replace("\"real_account\":true", "\"real_account\":true,\"unexpected\":1")
        assertEquals(Rg02RawJsonContractError("$.catalog.accounts[0].unexpected", Rg02RawJsonContractErrorReason.UNKNOWN_FIELD), assertIs<Rg02RawJsonDecodeResult.Invalid>(decodeRg02RawJson(unknown)).error)

        val wrongType = validRaw().replace("\"confirmed\":true", "\"confirmed\":{}")
        assertEquals(Rg02RawJsonContractErrorReason.WRONG_TYPE, assertIs<Rg02RawJsonDecodeResult.Invalid>(decodeRg02RawJson(wrongType)).error.reason)

        val wrongRootType = validRaw().replace("\"case\":{", "\"case\":true,\"discarded\":{")
        assertIs<Rg02RawJsonDecodeResult.Invalid>(decodeRg02RawJson(wrongRootType))
    }

    @Test
    fun `confirmation candidate and operation expected shapes are enforced`() {
        val implicit = validRaw().replace("explicit_manual_save", "implicit")
        assertEquals(Rg02RawJsonContractErrorReason.INVALID_VALUE, assertIs<Rg02RawJsonDecodeResult.Invalid>(decodeRg02RawJson(implicit)).error.reason)

        val candidate = validRaw().replace("\"candidate\":null", "\"candidate\":{}")
        assertEquals(Rg02RawJsonContractError("$.create.candidate", Rg02RawJsonContractErrorReason.INVALID_VALUE), assertIs<Rg02RawJsonDecodeResult.Invalid>(decodeRg02RawJson(candidate)).error)

        val unconfirmed = validRaw().replace("\"confirmed\":true", "\"confirmed\":false")
        assertEquals(Rg02RawJsonContractErrorReason.INVALID_VALUE, assertIs<Rg02RawJsonDecodeResult.Invalid>(decodeRg02RawJson(unconfirmed)).error.reason)

        val postingType = validRaw().replace("\"amount\":\"1.00\",\"currency\":\"CNY\"}", "\"amount\":100,\"currency\":\"CNY\"}")
        assertEquals(Rg02RawJsonContractErrorReason.WRONG_TYPE, assertIs<Rg02RawJsonDecodeResult.Invalid>(decodeRg02RawJson(postingType)).error.reason)
    }

    @Test
    fun `duplicate keys and resource limits return typed invalid`() {
        val duplicate = validRaw().replaceFirst("\"schema_version\":1", "\"schema_version\":1,\"schema_version\":1")
        assertEquals(Rg02RawJsonContractErrorReason.DUPLICATE_KEY, assertIs<Rg02RawJsonDecodeResult.Invalid>(decodeRg02RawJson(duplicate)).error.reason)
        assertEquals(Rg02RawJsonContractErrorReason.RESOURCE_LIMIT, assertIs<Rg02RawJsonDecodeResult.Invalid>(decodeRg02RawJson(" ".repeat(1_048_577))).error.reason)
    }

    @Test
    fun `case metadata is frozen even when request and case values change together`() {
        val mutations = listOf(
            validRaw().replace("\"currency\":\"CNY\"", "\"currency\":\"USD\"") to "$.case.currency",
            validRaw().replace("\"precision\":2", "\"precision\":3") to "$.case.precision",
            validRaw().replace("\"timezone\":\"Asia/Shanghai\"", "\"timezone\":\"Etc/UTC\"") to "$.case.timezone",
            validRaw().replace("\"ledger_id\":\"ledger-a\"", "\"ledger_id\":\"ledger-b\"") to "$.case.ledger_id",
        )

        mutations.forEach { (raw, path) ->
            val error = assertIs<Rg02RawJsonDecodeResult.Invalid>(decodeRg02RawJson(raw)).error
            assertEquals(path, error.fieldPath)
            assertEquals(Rg02RawJsonContractErrorReason.INVALID_VALUE, error.reason)
        }
    }

    @Test
    fun `duplicate ids and invalid catalog references are typed invalid values`() {
        val duplicateAccount = validRaw().replace(
            "],\"categories\"",
            ",{\"id\":\"asset\",\"name\":\"Duplicate\",\"kind\":\"asset\",\"real_account\":true}],\"categories\"",
        )
        val invalidPostingReference = validRaw().replace(
            "\"posting_account_id\":null",
            "\"posting_account_id\":\"missing-income-account\"",
        )

        listOf(duplicateAccount, invalidPostingReference).forEach { raw ->
            val error = assertIs<Rg02RawJsonDecodeResult.Invalid>(decodeRg02RawJson(raw)).error
            assertEquals(Rg02RawJsonContractErrorReason.INVALID_VALUE, error.reason)
        }
    }
}

private fun validRaw() = """{
  "schema_version":1,
  "case":{"id":"RG-02","level":"core_required","rule_version":1,"timezone":"Asia/Shanghai","currency":"CNY","precision":2,"ledger_id":"ledger-a"},
  "catalog":{"accounts":[{"id":"asset","name":"Asset","kind":"asset","real_account":true}],"categories":[{"id":"income-child","name":"Income","kind":"income","parent_id":null,"posting_account_id":null,"active":true}]},
  "opening":{"transactions":[],"expected_balances":{}},
  "create":{"confirmation":{"mode":"explicit_manual_save","confirmed":true},"candidate":null,"request":{"request_id":"request-create","kind":"manual_income","occurred_at":"2026-01-01T00:00:00Z","amount":"1.00","currency":"CNY","category_id":"income-child","receiving_account_id":"asset","note":""},"expected":{"accepted":true,"transaction":{"id":"tx","current_version_id":"v","posting_set_id":"s","occurred_at":"2026-01-01T00:00:00Z","effective":true,"postings":[{"id":"p1","account_id":"asset","amount":"1.00","currency":"CNY"},{"id":"p2","account_id":"income","amount":"-1.00","currency":"CNY"}]},"balances":{},"statistics":{},"reconciliation":{},"evidence_refs":[]}},
  "category_rename":{"request":{"category_id":"income-child","new_name":"New"},"expected":{"category_id":"income-child","current_name":"New","display_path":"Income > New","name_versions":[{"version":1,"status":"superseded","name":"Income"},{"version":2,"status":"current","name":"New"}],"transaction_category_id":"income-child","posting_account_id":"income","transaction_version_count":1,"funding_effect_count":1,"balances":{},"statistics":{},"reconciliation":{},"evidence_refs":[]}},
  "idempotency":{"repeated_request_id":"request-create","expected":{"returned_transaction_id":"tx","new_transaction_count":0,"new_posting_set_count":0,"new_version_count":0,"funding_effect_count":1,"balances":{},"statistics":{},"reconciliation":{},"evidence_refs":[]}},
  "invalid_inputs":[],"variants":[],"forbidden_side_effects":["invoke_network"]
}"""
