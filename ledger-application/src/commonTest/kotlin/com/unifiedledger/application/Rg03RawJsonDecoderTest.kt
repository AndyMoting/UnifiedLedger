package com.unifiedledger.application

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class Rg03RawJsonDecoderTest {
    @Test
    fun `v1 fixture shapes expand to the twenty approved executions`() {
        val decoded = assertIs<Rg03RawJsonDecodeResult.Success>(decodeRg03RawJson(validRg03Raw())).value

        assertEquals(20, decoded.operations.size)
        assertEquals(
            mapOf(
                Rg03ActionType.MANUAL_ACCOUNT_TRANSFER to 12,
                Rg03ActionType.IMPORT_SOURCE_RECORD to 4,
                Rg03ActionType.EXPLICIT_CANDIDATE_CONFIRMATION to 2,
                Rg03ActionType.IMPORT_MIRROR_RECORD to 2,
            ),
            decoded.operations.groupingBy { it.actionType }.eachCount(),
        )
        assertEquals(10, decoded.operations.count { it.expected is Rg03ExpectedOutcome.Rejected })
        assertEquals(5, decoded.operations.count { it.replayOf != null })
        assertEquals(setOf("complete", "missing_destination"), decoded.sourceInputs.map { it.completeness }.toSet())
        assertEquals(false, decoded.supportsCombinationTransfer)

        val sourceOperations = decoded.operations.filter { it.actionType == Rg03ActionType.IMPORT_SOURCE_RECORD }
        sourceOperations.filter { it.input.completeness == Rg03JsonField.Value("complete") }.forEach { operation ->
            assertEquals(Rg03JsonField.Value("60.00"), operation.input.sourceDebitAmount)
            assertEquals(Rg03JsonField.Value("asset-wallet-b"), operation.input.destinationAccountId)
            assertEquals(Rg03JsonField.Value("59.00"), operation.input.destinationCreditAmount)
            assertEquals(Rg03JsonField.Value("1.00"), operation.input.feeAmount)
        }
        sourceOperations.filter { it.input.completeness == Rg03JsonField.Value("missing_destination") }.forEach { operation ->
            assertEquals(Rg03JsonField.Value("40.00"), operation.input.sourceDebitAmount)
            assertEquals(Rg03JsonField.Null, operation.input.destinationAccountId)
            assertEquals(Rg03JsonField.Omitted, operation.input.destinationCreditAmount)
            assertEquals(Rg03JsonField.Omitted, operation.input.feeAmount)
        }
    }

    @Test
    fun `complete source rejects debit alias mixing and requires every complete fact`() {
        val sourcePath = "$.import_lifecycle.ordered_operations[0].input.source_record"
        val cases = listOf(
            mutateRg03Source(complete = true) { it["debit_amount"] = JsonPrimitive("60.00") } to
                Rg03RawJsonContractError("$sourcePath.debit_amount", Rg03RawJsonContractErrorReason.UNKNOWN_FIELD),
            mutateRg03Source(complete = true) {
                it.remove("source_debit_amount")
                it["debit_amount"] = JsonPrimitive("60.00")
            } to Rg03RawJsonContractError("$sourcePath.debit_amount", Rg03RawJsonContractErrorReason.UNKNOWN_FIELD),
            mutateRg03Source(complete = true) { it.remove("source_debit_amount") } to
                Rg03RawJsonContractError("$sourcePath.source_debit_amount", Rg03RawJsonContractErrorReason.WRONG_TYPE),
            mutateRg03Source(complete = true) { it.remove("destination_account_id") } to
                Rg03RawJsonContractError("$sourcePath.destination_account_id", Rg03RawJsonContractErrorReason.WRONG_TYPE),
            mutateRg03Source(complete = true) { it.remove("destination_credit_amount") } to
                Rg03RawJsonContractError("$sourcePath.destination_credit_amount", Rg03RawJsonContractErrorReason.WRONG_TYPE),
            mutateRg03Source(complete = true) { it.remove("fee_amount") } to
                Rg03RawJsonContractError("$sourcePath.fee_amount", Rg03RawJsonContractErrorReason.WRONG_TYPE),
        )

        cases.forEach { (raw, expected) ->
            assertEquals(expected, assertIs<Rg03RawJsonDecodeResult.Invalid>(decodeRg03RawJson(raw)).error)
        }
    }

    @Test
    fun `missing destination source rejects complete aliases and every ignored destination fact`() {
        val sourcePath = "$.unknown_one_sided_debit.input.source_record"
        val cases = listOf(
            mutateRg03Source(complete = false) { it["source_debit_amount"] = JsonPrimitive("40.00") } to
                Rg03RawJsonContractError("$sourcePath.source_debit_amount", Rg03RawJsonContractErrorReason.UNKNOWN_FIELD),
            mutateRg03Source(complete = false) {
                it.remove("debit_amount")
                it["source_debit_amount"] = JsonPrimitive("40.00")
            } to Rg03RawJsonContractError("$sourcePath.source_debit_amount", Rg03RawJsonContractErrorReason.UNKNOWN_FIELD),
            mutateRg03Source(complete = false) { it.remove("debit_amount") } to
                Rg03RawJsonContractError("$sourcePath.debit_amount", Rg03RawJsonContractErrorReason.WRONG_TYPE),
            mutateRg03Source(complete = false) { it["destination_account_id"] = JsonPrimitive("asset-wallet-b") } to
                Rg03RawJsonContractError("$sourcePath.destination_account_id", Rg03RawJsonContractErrorReason.INVALID_VALUE),
            mutateRg03Source(complete = false) { it["destination_credit_amount"] = JsonPrimitive("39.00") } to
                Rg03RawJsonContractError("$sourcePath.destination_credit_amount", Rg03RawJsonContractErrorReason.UNKNOWN_FIELD),
            mutateRg03Source(complete = false) { it["fee_amount"] = JsonPrimitive("1.00") } to
                Rg03RawJsonContractError("$sourcePath.fee_amount", Rg03RawJsonContractErrorReason.UNKNOWN_FIELD),
        )

        cases.forEach { (raw, expected) ->
            assertEquals(expected, assertIs<Rg03RawJsonDecodeResult.Invalid>(decodeRg03RawJson(raw)).error)
        }

        val absentDestination = mutateRg03Source(complete = false) { it.remove("destination_account_id") }
        val decoded = assertIs<Rg03RawJsonDecodeResult.Success>(decodeRg03RawJson(absentDestination)).value
        assertEquals(Rg03JsonField.Omitted, decoded.operations[4].input.destinationAccountId)
    }

    @Test
    fun `invalid manual overrides reject generic input facts that overlay would ignore`() {
        listOf(
            "request_id" to JsonPrimitive("replacement-request"),
            "kind" to JsonPrimitive("manual_account_transfer"),
            "occurred_at" to JsonPrimitive("2026-01-20T11:00:00+08:00"),
            "fee_category_id" to JsonPrimitive("expense-category-financial"),
        ).forEach { (field, value) ->
            val raw = mutateRg03InvalidManualOverride { it[field] = value }
            assertEquals(
                Rg03RawJsonContractError(
                    "$.invalid_manual_inputs[0].input.$field",
                    Rg03RawJsonContractErrorReason.UNKNOWN_FIELD,
                ),
                assertIs<Rg03RawJsonDecodeResult.Invalid>(decodeRg03RawJson(raw)).error,
            )
        }
    }

    @Test
    fun `out of scope and forbidden side effects are exact frozen values`() {
        listOf("combination_transfer", "fee_refund", "target_balance_adjustment").forEach { field ->
            val raw = mutateRg03Root { root ->
                val outOfScope = root.getValue("out_of_scope").jsonObject.toMutableMap()
                outOfScope[field] = JsonPrimitive("arbitrary")
                root["out_of_scope"] = JsonObject(outOfScope)
            }
            assertEquals(
                Rg03RawJsonContractError("$.out_of_scope.$field", Rg03RawJsonContractErrorReason.INVALID_VALUE),
                assertIs<Rg03RawJsonDecodeResult.Invalid>(decodeRg03RawJson(raw)).error,
            )
        }

        val frozen = rg03ForbiddenSideEffects.map(::JsonPrimitive)
        val reordered = mutateRg03Root { it["forbidden_side_effects"] = JsonArray(frozen.reversed()) }
        assertIs<Rg03RawJsonDecodeResult.Success>(decodeRg03RawJson(reordered))

        val invalidCases = listOf(
            emptyList<JsonElement>() to
                Rg03RawJsonContractError("$.forbidden_side_effects", Rg03RawJsonContractErrorReason.INVALID_VALUE),
            listOf<JsonElement>(JsonPrimitive("arbitrary")) to
                Rg03RawJsonContractError("$.forbidden_side_effects[0]", Rg03RawJsonContractErrorReason.INVALID_VALUE),
            frozen + frozen.first() to
                Rg03RawJsonContractError(
                    "$.forbidden_side_effects[${frozen.size}]",
                    Rg03RawJsonContractErrorReason.INVALID_VALUE,
                ),
        )
        invalidCases.forEach { (items, expected) ->
            val raw = mutateRg03Root { it["forbidden_side_effects"] = JsonArray(items) }
            assertEquals(expected, assertIs<Rg03RawJsonDecodeResult.Invalid>(decodeRg03RawJson(raw)).error)
        }
    }

    @Test
    fun `replay request ids must equal and replace their original operation request ids`() {
        val repeatedFields = listOf(
            "repeated_manual_request_id",
            "repeated_source_request_id",
            "repeated_confirmation_request_id",
            "repeated_mirror_request_id",
        )
        repeatedFields.forEach { field ->
            val raw = mutateRg03Root { root ->
                val idempotency = root.getValue("idempotency").jsonObject.toMutableMap()
                idempotency[field] = JsonPrimitive("different-request")
                root["idempotency"] = JsonObject(idempotency)
            }
            assertEquals(
                Rg03RawJsonContractError("$.idempotency.$field", Rg03RawJsonContractErrorReason.INVALID_VALUE),
                assertIs<Rg03RawJsonDecodeResult.Invalid>(decodeRg03RawJson(raw)).error,
            )
        }
        val incompleteRetry = mutateRg03Root { root ->
            val incomplete = root.getValue("unknown_one_sided_debit").jsonObject.toMutableMap()
            val retry = incomplete.getValue("retry").jsonObject.toMutableMap()
            retry["repeated_request_id"] = JsonPrimitive("different-request")
            incomplete["retry"] = JsonObject(retry)
            root["unknown_one_sided_debit"] = JsonObject(incomplete)
        }
        assertEquals(
            Rg03RawJsonContractError(
                "$.unknown_one_sided_debit.retry.repeated_request_id",
                Rg03RawJsonContractErrorReason.INVALID_VALUE,
            ),
            assertIs<Rg03RawJsonDecodeResult.Invalid>(decodeRg03RawJson(incompleteRetry)).error,
        )

        val decoded = assertIs<Rg03RawJsonDecodeResult.Success>(decodeRg03RawJson(validRg03Raw())).value
        decoded.operations.filter { it.replayOf != null }.forEach { replay ->
            assertEquals(Rg03JsonField.Value(replay.replayOf!!), replay.input.requestId)
        }
    }

    @Test
    fun `root baselines manual candidate and lifecycle operation ids are exact frozen values`() {
        listOf("manual_create", "import_lifecycle", "unknown_one_sided_debit").forEach { section ->
            val raw = mutateRg03Root { root ->
                val value = root.getValue(section).jsonObject.toMutableMap()
                value["independent_baseline"] = JsonPrimitive("other")
                root[section] = JsonObject(value)
            }
            assertEquals(
                Rg03RawJsonContractError("$.$section.independent_baseline", Rg03RawJsonContractErrorReason.INVALID_VALUE),
                assertIs<Rg03RawJsonDecodeResult.Invalid>(decodeRg03RawJson(raw)).error,
            )
        }

        val candidate = mutateRg03Root { root ->
            val manual = root.getValue("manual_create").jsonObject.toMutableMap()
            manual["candidate"] = JsonObject(emptyMap())
            root["manual_create"] = JsonObject(manual)
        }
        assertEquals(
            Rg03RawJsonContractError("$.manual_create.candidate", Rg03RawJsonContractErrorReason.INVALID_VALUE),
            assertIs<Rg03RawJsonDecodeResult.Invalid>(decodeRg03RawJson(candidate)).error,
        )

        listOf("import-complete-source", "confirm-import-candidate", "merge-mirror-evidence")
            .forEachIndexed { index, expectedId ->
                val raw = mutateRg03LifecycleOperation(index) { it["id"] = JsonPrimitive("$expectedId-changed") }
                assertEquals(
                    Rg03RawJsonContractError(
                        "$.import_lifecycle.ordered_operations[$index].id",
                        Rg03RawJsonContractErrorReason.INVALID_VALUE,
                    ),
                    assertIs<Rg03RawJsonDecodeResult.Invalid>(decodeRg03RawJson(raw)).error,
                )
            }
        val wrongType = mutateRg03LifecycleOperation(0) { it["id"] = JsonPrimitive(1) }
        assertEquals(
            Rg03RawJsonContractError(
                "$.import_lifecycle.ordered_operations[0].id",
                Rg03RawJsonContractErrorReason.WRONG_TYPE,
            ),
            assertIs<Rg03RawJsonDecodeResult.Invalid>(decodeRg03RawJson(wrongType)).error,
        )
    }

    @Test
    fun `catalog names and stable account identities have exact frozen semantics`() {
        val cases = listOf(
            mutateRg03CatalogItem("accounts", 0) { it["name"] = JsonPrimitive(1) } to
                Rg03RawJsonContractError("$.catalog.accounts[0].name", Rg03RawJsonContractErrorReason.WRONG_TYPE),
            mutateRg03CatalogItem("accounts", 1) { it["id"] = JsonPrimitive("asset-bank-a") } to
                Rg03RawJsonContractError("$.catalog.accounts[1].id", Rg03RawJsonContractErrorReason.INVALID_VALUE),
            mutateRg03CatalogItems("accounts") { items ->
                items += JsonObject(items.first().jsonObject.toMutableMap().apply { this["id"] = JsonPrimitive("account-extra") })
            } to Rg03RawJsonContractError("$.catalog.accounts[6].id", Rg03RawJsonContractErrorReason.INVALID_VALUE),
            mutateRg03CatalogItems("accounts") { it.removeAt(0) } to
                Rg03RawJsonContractError("$.catalog.accounts", Rg03RawJsonContractErrorReason.INVALID_VALUE),
            mutateRg03CatalogItem("accounts", 0) { it["kind"] = JsonPrimitive("expense") } to
                Rg03RawJsonContractError("$.catalog.accounts[0].kind", Rg03RawJsonContractErrorReason.INVALID_VALUE),
            mutateRg03CatalogItem("accounts", 0) { it["real_account"] = JsonPrimitive(false) } to
                Rg03RawJsonContractError("$.catalog.accounts[0].real_account", Rg03RawJsonContractErrorReason.INVALID_VALUE),
            mutateRg03CatalogItem("accounts", 0) { it["owned_by_user"] = JsonPrimitive(false) } to
                Rg03RawJsonContractError("$.catalog.accounts[0].owned_by_user", Rg03RawJsonContractErrorReason.INVALID_VALUE),
        )
        cases.forEach { (raw, expected) ->
            assertEquals(expected, assertIs<Rg03RawJsonDecodeResult.Invalid>(decodeRg03RawJson(raw)).error)
        }
    }

    @Test
    fun `catalog stable category identities have exact frozen semantics`() {
        val cases = listOf(
            mutateRg03CatalogItem("categories", 0) { it["name"] = JsonPrimitive(false) } to
                Rg03RawJsonContractError("$.catalog.categories[0].name", Rg03RawJsonContractErrorReason.WRONG_TYPE),
            mutateRg03CatalogItem("categories", 1) { it["id"] = JsonPrimitive("expense-category-financial") } to
                Rg03RawJsonContractError("$.catalog.categories[1].id", Rg03RawJsonContractErrorReason.INVALID_VALUE),
            mutateRg03CatalogItems("categories") { items ->
                items += JsonObject(items.first().jsonObject.toMutableMap().apply { this["id"] = JsonPrimitive("category-extra") })
            } to Rg03RawJsonContractError("$.catalog.categories[2].id", Rg03RawJsonContractErrorReason.INVALID_VALUE),
            mutateRg03CatalogItems("categories") { it.removeAt(0) } to
                Rg03RawJsonContractError("$.catalog.categories", Rg03RawJsonContractErrorReason.INVALID_VALUE),
            mutateRg03CatalogItem("categories", 0) { it["kind"] = JsonPrimitive("income") } to
                Rg03RawJsonContractError("$.catalog.categories[0].kind", Rg03RawJsonContractErrorReason.INVALID_VALUE),
            mutateRg03CatalogItem("categories", 1) { it["parent_id"] = JsonNull } to
                Rg03RawJsonContractError("$.catalog.categories[1].parent_id", Rg03RawJsonContractErrorReason.INVALID_VALUE),
            mutateRg03CatalogItem("categories", 1) { it["posting_account_id"] = JsonNull } to
                Rg03RawJsonContractError("$.catalog.categories[1].posting_account_id", Rg03RawJsonContractErrorReason.INVALID_VALUE),
            mutateRg03CatalogItem("categories", 0) { it["active"] = JsonPrimitive(false) } to
                Rg03RawJsonContractError("$.catalog.categories[0].active", Rg03RawJsonContractErrorReason.INVALID_VALUE),
        )
        cases.forEach { (raw, expected) ->
            assertEquals(expected, assertIs<Rg03RawJsonDecodeResult.Invalid>(decodeRg03RawJson(raw)).error)
        }
    }

    @Test
    fun `strict decoder rejects duplicate unknown wrong type malformed and resource violations`() {
        val cases = listOf(
            validRg03Raw().replaceFirst("\"schema_version\":1", "\"schema_version\":1,\"schema_version\":1") to Rg03RawJsonContractErrorReason.DUPLICATE_KEY,
            validRg03Raw().replace("\"scope\":\"one_to_one_same_currency_own_real_financial_account_transfer\"", "\"scope\":\"one_to_one_same_currency_own_real_financial_account_transfer\",\"extra\":true") to Rg03RawJsonContractErrorReason.UNKNOWN_FIELD,
            validRg03Raw().replaceFirst("\"precision\":2", "\"precision\":\"2\"") to Rg03RawJsonContractErrorReason.WRONG_TYPE,
            "{" to Rg03RawJsonContractErrorReason.MALFORMED_JSON,
            " ".repeat(1_048_577) to Rg03RawJsonContractErrorReason.RESOURCE_LIMIT,
            "[".repeat(65) + "]".repeat(65) to Rg03RawJsonContractErrorReason.RESOURCE_LIMIT,
        )

        cases.forEach { (raw, reason) ->
            assertEquals(reason, assertIs<Rg03RawJsonDecodeResult.Invalid>(decodeRg03RawJson(raw)).error.reason)
        }
    }
}

private fun mutateRg03Source(
    complete: Boolean,
    mutation: (MutableMap<String, JsonElement>) -> Unit,
): String {
    val root = Json.parseToJsonElement(validRg03Raw()).jsonObject.toMutableMap()
    if (complete) {
        val lifecycle = root.getValue("import_lifecycle").jsonObject.toMutableMap()
        val operations = lifecycle.getValue("ordered_operations").jsonArray.toMutableList()
        val operation = operations[0].jsonObject.toMutableMap()
        val input = operation.getValue("input").jsonObject.toMutableMap()
        val source = input.getValue("source_record").jsonObject.toMutableMap()
        mutation(source)
        input["source_record"] = JsonObject(source)
        operation["input"] = JsonObject(input)
        operations[0] = JsonObject(operation)
        lifecycle["ordered_operations"] = JsonArray(operations)
        root["import_lifecycle"] = JsonObject(lifecycle)
    } else {
        val incomplete = root.getValue("unknown_one_sided_debit").jsonObject.toMutableMap()
        val input = incomplete.getValue("input").jsonObject.toMutableMap()
        val source = input.getValue("source_record").jsonObject.toMutableMap()
        mutation(source)
        input["source_record"] = JsonObject(source)
        incomplete["input"] = JsonObject(input)
        root["unknown_one_sided_debit"] = JsonObject(incomplete)
    }
    return JsonObject(root).toString()
}

private fun mutateRg03InvalidManualOverride(
    mutation: (MutableMap<String, JsonElement>) -> Unit,
): String = mutateRg03Root { root ->
    val invalidInputs = root.getValue("invalid_manual_inputs").jsonArray.toMutableList()
    val item = invalidInputs[0].jsonObject.toMutableMap()
    val input = item.getValue("input").jsonObject.toMutableMap()
    mutation(input)
    item["input"] = JsonObject(input)
    invalidInputs[0] = JsonObject(item)
    root["invalid_manual_inputs"] = JsonArray(invalidInputs)
}

private fun mutateRg03Root(
    mutation: (MutableMap<String, JsonElement>) -> Unit,
): String {
    val root = Json.parseToJsonElement(validRg03Raw()).jsonObject.toMutableMap()
    mutation(root)
    return JsonObject(root).toString()
}

private fun mutateRg03LifecycleOperation(
    index: Int,
    mutation: (MutableMap<String, JsonElement>) -> Unit,
): String = mutateRg03Root { root ->
    val lifecycle = root.getValue("import_lifecycle").jsonObject.toMutableMap()
    val operations = lifecycle.getValue("ordered_operations").jsonArray.toMutableList()
    val operation = operations[index].jsonObject.toMutableMap()
    mutation(operation)
    operations[index] = JsonObject(operation)
    lifecycle["ordered_operations"] = JsonArray(operations)
    root["import_lifecycle"] = JsonObject(lifecycle)
}

private fun mutateRg03CatalogItem(
    collection: String,
    index: Int,
    mutation: (MutableMap<String, JsonElement>) -> Unit,
): String = mutateRg03CatalogItems(collection) { items ->
    val item = items[index].jsonObject.toMutableMap()
    mutation(item)
    items[index] = JsonObject(item)
}

private fun mutateRg03CatalogItems(
    collection: String,
    mutation: (MutableList<JsonElement>) -> Unit,
): String = mutateRg03Root { root ->
    val catalog = root.getValue("catalog").jsonObject.toMutableMap()
    val items = catalog.getValue(collection).jsonArray.toMutableList()
    mutation(items)
    catalog[collection] = JsonArray(items)
    root["catalog"] = JsonObject(catalog)
}

private val rg03FullStatistics = """{"day":"2026-01-20","month":"2026-01","day_consumption":"1.00","month_consumption":"1.00","day_cash_outflow":"1.00","month_cash_outflow":"1.00","day_income":"0.00","month_income":"0.00","principal_consumption":"0.00","principal_external_cash_flow":"0.00","net_worth_change":"-1.00","budget":"not_applicable"}"""
private val rg03ZeroStatistics = """{"consumption":"0.00","cash_outflow":"0.00","income":"0.00","net_worth_change":"0.00"}"""
private val rg03Balances = """{"asset-bank-a":"940.00","asset-wallet-b":"159.00"}"""
private val rg03Reconciliation = """{"posting":"pending","transaction":"pending"}"""
private val rg03ManualTransaction = """{"id":"tx-manual","current_version_id":"version-manual","posting_set_id":"posting-set-manual","occurred_at":"2026-01-20T10:00:00+08:00","effective":true,"provenance":{"kind":"manual_confirmation","confirmation_ref":"manual"},"postings":[]}"""
private val rg03ImportTransaction = """{"id":"tx-import","current_version_id":"version-import","posting_set_id":"posting-set-import","occurred_at":"2026-01-21T11:00:00+08:00","effective":true,"provenance":{"kind":"confirmed_import_candidate","candidate_id":"candidate-id","confirmation_ref":"confirm-request","source_refs":["source-id"]},"postings":[]}"""
private val rg03EvidenceLinks = "[]"
private val rg03RejectedResidue = """"state_unchanged":true,"new_transaction_count":0,"new_posting_count":0,"new_version_count":0,"reconciliation_change_count":0"""
private val rg03ManualState = """{"transaction_id":"tx-manual","current_version_id":"version-manual","posting_set_id":"posting-set-manual","balances":$rg03Balances,"statistics":$rg03FullStatistics,"reconciliation":$rg03Reconciliation,"source_refs":[],"evidence_refs":[],"evidence_links":$rg03EvidenceLinks}"""
private val rg03ImportState = """{"transaction_id":"tx-import","current_version_id":"version-import","posting_set_id":"posting-set-import","balances":$rg03Balances,"statistics":$rg03FullStatistics,"reconciliation":$rg03Reconciliation,"source_refs":[],"evidence_refs":[],"evidence_links":$rg03EvidenceLinks,"candidate_id":"candidate-id","candidate_status":"confirmed","posting_ids":[]}"""
private val rg03ForbiddenSideEffects = listOf(
    "auto_confirm_import_candidate",
    "create_duplicate_transfer",
    "create_duplicate_postings",
    "create_income_for_transfer_principal",
    "count_transfer_principal_as_consumption",
    "count_transfer_principal_as_external_cash_flow",
    "create_balancing_account",
    "create_suspense_posting",
    "overwrite_source_evidence",
    "create_fee_refund",
    "create_target_balance_adjustment",
    "invoke_network",
    "invoke_sync",
    "invoke_intelligent_suggestion",
)

internal fun validRg03Raw(): String = """{
  "schema_version":1,
  "case":{"id":"RG-03","level":"core_required","rule_version":1,"timezone":"Asia/Shanghai","currency":"CNY","precision":2,"ledger_id":"ledger-a","scope":"one_to_one_same_currency_own_real_financial_account_transfer"},
  "catalog":{"accounts":[
    {"id":"asset-bank-a","name":"Bank","kind":"asset","real_account":true,"owned_by_user":true},
    {"id":"asset-wallet-b","name":"Wallet","kind":"asset","real_account":true,"owned_by_user":true},
    {"id":"liability-credit-c","name":"Credit","kind":"liability","real_account":true,"owned_by_user":true},
    {"id":"asset-external-x","name":"External","kind":"asset","real_account":true,"owned_by_user":false},
    {"id":"equity-opening-a","name":"Opening","kind":"equity","real_account":false,"owned_by_user":false},
    {"id":"expense-account-transfer-fee","name":"Fee","kind":"expense","real_account":false,"owned_by_user":false}
  ],"categories":[
    {"id":"expense-category-financial","name":"Financial","kind":"expense","parent_id":null,"posting_account_id":null,"active":true},
    {"id":"expense-category-transfer-fee","name":"Fee","kind":"expense","parent_id":"expense-category-financial","posting_account_id":"expense-account-transfer-fee","active":true}
  ]},
  "opening":{"transactions":[],"expected_balances":{}},
  "manual_create":{"independent_baseline":"opening","confirmation":{"mode":"explicit_manual_save","confirmed":true},"candidate":null,"request":{"request_id":"manual","kind":"manual_account_transfer","occurred_at":"2026-01-20T10:00:00+08:00","source_account_id":"asset-bank-a","destination_account_id":"asset-wallet-b","source_debit_amount":"60.00","destination_credit_amount":"59.00","fee_amount":"1.00","currency":"CNY","fee_category_id":"expense-category-transfer-fee"},"expected":{"accepted":true,"transaction":$rg03ManualTransaction,"balances":$rg03Balances,"statistics":$rg03FullStatistics,"reconciliation":$rg03Reconciliation,"evidence_refs":[]}},
  "import_lifecycle":{"independent_baseline":"opening","ordered_operations":[
    {"sequence":1,"id":"import-complete-source","input":{"request_id":"source-request","kind":"import_source_record","source_record":{"id":"source-id","evidence_id":"evidence-id","observed_at":"2026-01-21T11:00:00+08:00","source_account_id":"asset-bank-a","destination_account_id":"asset-wallet-b","source_debit_amount":"60.00","destination_credit_amount":"59.00","fee_amount":"1.00","currency":"CNY","completeness":"complete"}},"expected":{"candidate":{"id":"candidate-id","status":"pending_confirmation","kind":"account_transfer_with_fee","confidence":"1.00","source_refs":["source-id"],"evidence_refs":["evidence-id"],"provenance":{"rule":"complete_transfer_source","rule_version":1},"requires_confirmation":["formal_transaction_creation"]},"new_candidate_count":1,"formal_effects":{"new_transaction_count":0,"new_posting_count":0,"funding_effect_count":0,"balance_changes":{},"balances":$rg03Balances,"statistics":$rg03ZeroStatistics,"reconciliation_change_count":0}}},
    {"sequence":2,"id":"confirm-import-candidate","input":{"request_id":"confirm-request","kind":"explicit_candidate_confirmation","candidate_id":"candidate-id","confirmed":true},"expected":{"candidate_status":"confirmed","transaction":$rg03ImportTransaction,"balances":$rg03Balances,"statistics":$rg03FullStatistics,"reconciliation":$rg03Reconciliation,"evidence_refs":["evidence-id"],"evidence_links":$rg03EvidenceLinks,"effective_transaction_count":1,"funding_effect_count":1}},
    {"sequence":3,"id":"merge-mirror-evidence","input":{"request_id":"mirror-request","kind":"import_mirror_record","source_record":{"id":"mirror-source","evidence_id":"mirror-evidence","observed_at":"2026-01-21T11:01:00+08:00","account_id":"asset-wallet-b","credit_amount":"59.00","currency":"CNY"}},"expected":{"merged_into_transaction_id":"tx-import","current_version_id":"version-import","effective_posting_set_id":"posting-set-import","posting_ids":[],"candidate_id":"candidate-id","new_transaction_count":0,"new_posting_count":0,"new_version_count":0,"effective_transaction_count":1,"funding_effect_count":1,"balances":$rg03Balances,"statistics":$rg03FullStatistics,"reconciliation":$rg03Reconciliation,"evidence_refs":[],"source_refs":[],"evidence_links":$rg03EvidenceLinks,"duplicate_income_count":0,"duplicate_statistics_effect_count":0}}
  ]},
  "unknown_one_sided_debit":{"independent_baseline":"opening","input":{"request_id":"incomplete-request","kind":"import_source_record","source_record":{"id":"incomplete-source","evidence_id":"incomplete-evidence","observed_at":"2026-01-22T09:00:00+08:00","source_account_id":"asset-bank-a","debit_amount":"40.00","destination_account_id":null,"currency":"CNY","completeness":"missing_destination"}},"expected":{"candidate":{"id":"incomplete-candidate","status":"pending_confirmation","source_refs":[],"evidence_refs":[],"requires_confirmation":[]},"new_transaction_count":0,"new_posting_count":0,"new_version_count":0,"balance_changes":{},"balances":$rg03Balances,"statistics":$rg03ZeroStatistics,"reconciliation_change_count":0,"balancing_account_id":null,"suspense_posting_count":0},"retry":{"repeated_request_id":"incomplete-request","expected":{"returned_candidate_id":"incomplete-candidate","candidate_status":"pending_confirmation","new_candidate_count":0,"new_transaction_count":0,"new_posting_count":0,"new_version_count":0,"reconciliation_change_count":0,"balance_changes":{},"balances":$rg03Balances,"statistics":$rg03ZeroStatistics,"source_refs":[],"evidence_refs":[]}}},
  "invalid_manual_inputs":[
    {"id":"missing-source","input":{"source_account_id":null},"expected":{"accepted":false,"field":"source_account_id","reason":"required",$rg03RejectedResidue}},
    {"id":"missing-destination","input":{"destination_account_id":null},"expected":{"accepted":false,"field":"destination_account_id","reason":"required",$rg03RejectedResidue}},
    {"id":"same-account","input":{},"expected":{"accepted":false,"field":"destination_account_id","reason":"distinct_own_real_financial_accounts_required",$rg03RejectedResidue}},
    {"id":"unknown-account","input":{},"expected":{"accepted":false,"field":"source_account_id","reason":"known_account_required",$rg03RejectedResidue}},
    {"id":"non-owned-account","input":{},"expected":{"accepted":false,"field":"destination_account_id","reason":"own_account_required",$rg03RejectedResidue}},
    {"id":"non-financial-account","input":{},"expected":{"accepted":false,"field":"source_account_id","reason":"real_financial_account_required",$rg03RejectedResidue}},
    {"id":"zero-principal","input":{},"expected":{"accepted":false,"field":"destination_credit_amount","reason":"must_be_positive",$rg03RejectedResidue}},
    {"id":"negative-principal","input":{},"expected":{"accepted":false,"field":"destination_credit_amount","reason":"must_be_positive",$rg03RejectedResidue}},
    {"id":"unbalanced-fee","input":{},"expected":{"accepted":false,"field":"fee_amount","reason":"amounts_must_balance",$rg03RejectedResidue}},
    {"id":"cross-currency","input":{},"expected":{"accepted":false,"field":"destination_currency","reason":"same_currency_required",$rg03RejectedResidue}}
  ],
  "idempotency":{"repeated_manual_request_id":"manual","repeated_source_request_id":"source-request","repeated_confirmation_request_id":"confirm-request","repeated_mirror_request_id":"mirror-request","expected":{"manual_returned_transaction_id":"tx-manual","import_returned_candidate_id":"candidate-id","confirmation_returned_transaction_id":"tx-import","mirror_merged_transaction_id":"tx-import","new_candidate_count":0,"new_transaction_count":0,"new_posting_count":0,"new_version_count":0,"new_evidence_link_count":0,"funding_effect_count_per_flow":1,"manual_state":$rg03ManualState,"import_state":$rg03ImportState}},
  "forbidden_side_effects":["auto_confirm_import_candidate","create_duplicate_transfer","create_duplicate_postings","create_income_for_transfer_principal","count_transfer_principal_as_consumption","count_transfer_principal_as_external_cash_flow","create_balancing_account","create_suspense_posting","overwrite_source_evidence","create_fee_refund","create_target_balance_adjustment","invoke_network","invoke_sync","invoke_intelligent_suggestion"],
  "out_of_scope":{"combination_transfer":"future_draft","fee_refund":"RG-07","target_balance_adjustment":"RG-09"}
}"""
