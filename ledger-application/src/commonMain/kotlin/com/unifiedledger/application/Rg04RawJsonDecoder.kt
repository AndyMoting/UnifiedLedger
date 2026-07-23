package com.unifiedledger.application

import com.unifiedledger.domain.*
import kotlinx.serialization.json.*

enum class Rg04RawJsonContractErrorReason { MALFORMED_JSON, DUPLICATE_KEY, RESOURCE_LIMIT, UNKNOWN_FIELD, WRONG_TYPE, INVALID_VALUE }
data class Rg04RawJsonContractError(val fieldPath: String, val reason: Rg04RawJsonContractErrorReason)
sealed interface Rg04RawJsonDecodeResult { data class Success(val value: Rg04RawJsonCase) : Rg04RawJsonDecodeResult; data class Invalid(val error: Rg04RawJsonContractError) : Rg04RawJsonDecodeResult }

private val rg04Json = Json { ignoreUnknownKeys = false }
private val rg04InvalidOperationIds = listOf(
    "missing-secondary-category",
    "funding-total-mismatch",
    "zero-total",
    "unknown-funding-account",
    "negative-total",
    "zero-funding-leg",
    "negative-funding-leg",
    "duplicate-funding-account",
    "known-nonfinancial-account",
    "known-non-owned-account",
    "primary-category",
    "inactive-secondary-category",
    "wrong-kind-income-category",
    "mixed-funding-currencies",
)
private val rg04RetryIds = listOf(
    "request-rg04-manual-purchase",
    "request-rg04-repayment",
    "source-record-rg04-complete",
    "request-rg04-confirm-candidate",
    "evidence-rg04-liability-mirror",
)

fun decodeRg04RawJson(raw: String): Rg04RawJsonDecodeResult {
    strictJsonPreflight(raw, duplicateKeyPath = true)?.let { issue ->
        return bad(issue.path, when (issue.reason) {
            StrictJsonPreflightReason.RESOURCE_LIMIT -> Rg04RawJsonContractErrorReason.RESOURCE_LIMIT
            StrictJsonPreflightReason.DUPLICATE_KEY -> Rg04RawJsonContractErrorReason.DUPLICATE_KEY
            StrictJsonPreflightReason.MALFORMED_JSON -> Rg04RawJsonContractErrorReason.MALFORMED_JSON
            StrictJsonPreflightReason.OBJECT_ROOT_REQUIRED -> Rg04RawJsonContractErrorReason.WRONG_TYPE
        })
    }
    val root = try { rg04Json.parseToJsonElement(raw).jsonObject } catch (_: Exception) { return bad("$", Rg04RawJsonContractErrorReason.MALFORMED_JSON) }
    return try {
        root.closed("$", setOf("schema_version", "case", "catalog", "opening", "manual_lifecycle", "import_lifecycle", "missing_funding_leg", "idempotency", "invalid_manual_inputs", "out_of_scope", "forbidden_side_effects"))
        if (root.integer("schema_version", "$.schema_version") != 1) fail("$.schema_version")
        val caseObject = root.obj("case", "$.case")
        caseObject.closed("$.case", setOf("id", "level", "rule_version", "timezone", "currency", "precision", "ledger_id", "scope"))
        if (caseObject.string("id", "$.case.id") != "RG-04") fail("$.case.id")
        if (caseObject.string("level", "$.case.level") != "core_required") fail("$.case.level")
        if (caseObject.integer("rule_version", "$.case.rule_version") != 1) fail("$.case.rule_version")
        val timezone = caseObject.string("timezone", "$.case.timezone")
        val currency = CurrencyUnit(caseObject.string("currency", "$.case.currency"), caseObject.integer("precision", "$.case.precision"))
        val ledgerId = LedgerId(caseObject.string("ledger_id", "$.case.ledger_id"))
        if (timezone != "Asia/Shanghai" || currency != CurrencyUnit("CNY", 2) || ledgerId != LedgerId("ledger-a")) fail("$.case")
        val catalog = catalog(root.obj("catalog", "$.catalog"), ledgerId, currency)

        val manualLifecycle = root.obj("manual_lifecycle", "$.manual_lifecycle")
        manualLifecycle.closed("$.manual_lifecycle", setOf("independent_baseline", "ordered_operations"))
        val manualArray = manualLifecycle.array("ordered_operations", "$.manual_lifecycle.ordered_operations")
        if (manualArray.size != 2) fail("$.manual_lifecycle.ordered_operations")
        val manualContainer = manualArray[0].objectAt("$.manual_lifecycle.ordered_operations[0]")
        val repaymentContainer = manualArray[1].objectAt("$.manual_lifecycle.ordered_operations[1]")
        val manualRawId = manualContainer.string("id", "$.manual_lifecycle.ordered_operations[0].id")
        val repaymentRawId = repaymentContainer.string("id", "$.manual_lifecycle.ordered_operations[1].id")
        if (manualRawId != "manual-mixed-purchase") fail("$.manual_lifecycle.ordered_operations[0].id")
        if (repaymentRawId != "repay-credit-principal") fail("$.manual_lifecycle.ordered_operations[1].id")
        val manualInput = manualOperation(manualContainer, "$.manual_lifecycle.ordered_operations[0]")
        val repaymentInput = repaymentOperation(repaymentContainer, "$.manual_lifecycle.ordered_operations[1]")
        val manualRequestId = manualInput.requestId.valueOrNull() ?: fail("$.manual_lifecycle.ordered_operations[0].input.request_id")
        val repaymentRequestId = repaymentInput.requestId.valueOrNull() ?: fail("$.manual_lifecycle.ordered_operations[1].input.request_id")
        val manualExpected = manualContainer.obj("expected", "$.manual_lifecycle.ordered_operations[0].expected")
        val repaymentExpected = repaymentContainer.obj("expected", "$.manual_lifecycle.ordered_operations[1].expected")
        val manualTx = manualExpected.obj("transaction", "$.manual_lifecycle.ordered_operations[0].expected.transaction")
        val repaymentTx = repaymentExpected.obj("transaction", "$.manual_lifecycle.ordered_operations[1].expected.transaction")
        val manualPostings = manualTx.array("postings", "$.manual_lifecycle.ordered_operations[0].expected.transaction.postings").mapIndexed { i, e -> e.objectAt("postings[$i]").string("id", "postings[$i].id") }
        val repaymentPostings = repaymentTx.array("postings", "$.manual_lifecycle.ordered_operations[1].expected.transaction.postings").mapIndexed { i, e -> e.objectAt("postings[$i]").string("id", "postings[$i].id") }
        val relation = manualExpected.obj("association_group", "$.manual_lifecycle.ordered_operations[0].expected.association_group")

        val invalidElements = root.array("invalid_manual_inputs", "$.invalid_manual_inputs")
        val invalidIds = invalidElements.mapIndexed { i, element ->
            element.objectAt("$.invalid_manual_inputs[$i]").string("id", "$.invalid_manual_inputs[$i].id")
        }
        if (invalidIds != rg04InvalidOperationIds) fail("$.invalid_manual_inputs")
        val invalid = invalidElements.mapIndexed { i, element ->
            invalidOperation(element.objectAt("$.invalid_manual_inputs[$i]"), "$.invalid_manual_inputs[$i]")
        }
        val deferred = decodeDeferredMetadata(root)
        val operations = buildList {
            add(Rg04DecodedOperation.Manual(manualInput, Rg04Expected.Accepted, Rg04OperationSource("$.manual_lifecycle.ordered_operations[*]", manualRequestId, manualRawId), Rg04OperationClass.CREATION))
            add(Rg04DecodedOperation.Manual(manualInput, Rg04Expected.NoChange, Rg04OperationSource("$.idempotency.retried_inputs[*]", manualRequestId, manualRequestId), Rg04OperationClass.CREATION))
            add(Rg04DecodedOperation.Repayment(repaymentInput, Rg04Expected.Accepted, Rg04OperationSource("$.manual_lifecycle.ordered_operations[*]", repaymentRequestId, repaymentRawId)))
            add(Rg04DecodedOperation.Repayment(repaymentInput, Rg04Expected.NoChange, Rg04OperationSource("$.idempotency.retried_inputs[*]", repaymentRequestId, repaymentRequestId)))
            addAll(invalid)
        }
        Rg04RawJsonDecodeResult.Success(
            Rg04RawJsonCase(
                ledgerId, currency, timezone, catalog, operations,
                deferred,
                MixedPaymentExpenseIds(TransactionId(manualTx.string("id", "transaction.id")), TransactionVersionId(manualTx.string("current_version_id", "transaction.current_version_id")), PostingSetId(manualTx.string("posting_set_id", "transaction.posting_set_id")), PostingId(manualPostings[0]), manualPostings.drop(1).map(::PostingId)),
                CreditPrincipalRepaymentIds(TransactionId(repaymentTx.string("id", "transaction.id")), TransactionVersionId(repaymentTx.string("current_version_id", "transaction.current_version_id")), PostingSetId(repaymentTx.string("posting_set_id", "transaction.posting_set_id")), PostingId(repaymentPostings[0]), PostingId(repaymentPostings[1])),
                relation.string("id", "association_group.id"), relation.string("display_name", "association_group.display_name"),
            ),
        )
    } catch (failure: Rg04DecodeFailure) { bad(failure.path, failure.reason) }
}

private fun decodeDeferredMetadata(root: JsonObject): List<Rg04DeferredOperation> {
    val lifecycle = root.obj("import_lifecycle", "$.import_lifecycle")
    lifecycle.closed("$.import_lifecycle", setOf("independent_baseline", "ordered_operations"))
    val operations = lifecycle.array("ordered_operations", "$.import_lifecycle.ordered_operations")
    if (operations.size != 3) fail("$.import_lifecycle.ordered_operations")
    val expectedRawIds = listOf("import-complete-mixed-payment", "complete-and-confirm-candidate", "merge-liability-mirror-evidence")
    val expectedActions = listOf("ingest_mixed_payment_source", "confirm_mixed_payment_candidate", "merge_mixed_payment_mirror_evidence")
    val expectedClasses = listOf(Rg04OperationClass.CREATION, Rg04OperationClass.CREATION, Rg04OperationClass.RECONCILIATION)
    val accepted = operations.mapIndexed { index, element ->
        val operationPath = "$.import_lifecycle.ordered_operations[$index]"
        val container = element.objectAt(operationPath)
        val rawId = container.string("id", "$operationPath.id")
        if (rawId != expectedRawIds[index]) fail("$operationPath.id")
        val input = container.obj("input", "$operationPath.input")
        val action = when (input.string("kind", "$operationPath.input.kind")) {
            "import_source_record" -> "ingest_mixed_payment_source"
            "explicit_candidate_completion_and_confirmation" -> "confirm_mixed_payment_candidate"
            "import_mirror_evidence" -> "merge_mixed_payment_mirror_evidence"
            else -> fail("$operationPath.input.kind")
        }
        if (action != expectedActions[index]) fail("$operationPath.input.kind")
        Rg04DeferredOperation(action, expectedClasses[index], Rg04Expected.Accepted, Rg04OperationSource("$.import_lifecycle.ordered_operations[*]", rawId, rawId))
    }
    val missing = root.obj("missing_funding_leg", "$.missing_funding_leg")
    missing.closed("$.missing_funding_leg", setOf("independent_baseline", "input", "expected", "retry"))
    val missingInput = missing.obj("input", "$.missing_funding_leg.input")
    if (missingInput.string("kind", "$.missing_funding_leg.input.kind") != "import_source_record") fail("$.missing_funding_leg.input.kind")
    val missingSourceId = missingInput.obj("source_record", "$.missing_funding_leg.input.source_record").string("id", "$.missing_funding_leg.input.source_record.id")
    if (missingSourceId != "source-record-rg04-missing-leg") fail("$.missing_funding_leg.input.source_record.id")
    val missingRetry = missing.obj("retry", "$.missing_funding_leg.retry")
    if (missingRetry.string("repeated_source_record_id", "$.missing_funding_leg.retry.repeated_source_record_id") != missingSourceId) fail("$.missing_funding_leg.retry.repeated_source_record_id")
    val idempotency = root.obj("idempotency", "$.idempotency")
    idempotency.closed("$.idempotency", setOf("retried_inputs", "expected"))
    val retried = idempotency.array("retried_inputs", "$.idempotency.retried_inputs").mapIndexed { i, e -> (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail("$.idempotency.retried_inputs[$i]", Rg04RawJsonContractErrorReason.WRONG_TYPE) }
    if (retried != rg04RetryIds) fail("$.idempotency.retried_inputs")
    val retryDiscriminators = retried.drop(2)
    val result = buildList {
        accepted.forEachIndexed { index, operation ->
            add(operation)
            add(operation.copy(expected = Rg04Expected.NoChange, source = Rg04OperationSource("$.idempotency.retried_inputs[*]", retryDiscriminators[index], retryDiscriminators[index])))
        }
        add(Rg04DeferredOperation("ingest_mixed_payment_source", Rg04OperationClass.CREATION, Rg04Expected.Accepted, Rg04OperationSource("$.missing_funding_leg", missingSourceId, missingSourceId)))
        add(Rg04DeferredOperation("ingest_mixed_payment_source", Rg04OperationClass.CREATION, Rg04Expected.NoChange, Rg04OperationSource("$.missing_funding_leg.retry", missingSourceId, missingSourceId)))
    }
    if (result.size != 8) fail("$.import_lifecycle")
    return result
}

private fun catalog(value: JsonObject, ledgerId: LedgerId, currency: CurrencyUnit): LedgerCatalog {
    value.closed("$.catalog", setOf("accounts", "categories", "association_group_types"))
    val accounts = value.array("accounts", "$.catalog.accounts").mapIndexed { i, e ->
        val path = "$.catalog.accounts[$i]"; val item = e.objectAt(path)
        item.closed(path, setOf("id", "name", "kind", "real_account", "owned_by_user"))
        Account(AccountId(item.string("id", "$path.id")), ledgerId, AccountKind.valueOf(item.string("kind", "$path.kind").uppercase()), currency, item.boolean("owned_by_user", "$path.owned_by_user"), item.boolean("real_account", "$path.real_account"))
    }
    val categories = value.array("categories", "$.catalog.categories").mapIndexed { i, e ->
        val path = "$.catalog.categories[$i]"; val item = e.objectAt(path)
        item.closed(path, setOf("id", "name", "kind", "parent_id", "posting_account_id", "active"))
        Category(CategoryId(item.string("id", "$path.id")), ledgerId, item.nullableString("parent_id", "$path.parent_id")?.let(::CategoryId), item.nullableString("posting_account_id", "$path.posting_account_id")?.let(::AccountId), item.boolean("active", "$path.active"), CategoryKind.valueOf(item.string("kind", "$path.kind").uppercase()))
    }
    return when (val created = LedgerCatalog.create(accounts, categories)) { is DomainResult.Success -> created.value; is DomainResult.Failure -> fail("$.catalog") }
}

private fun manualOperation(container: JsonObject, path: String): Rg04ManualInput {
    container.closed(path, setOf("sequence", "id", "confirmation", "candidate", "input", "expected"))
    if (container.integer("sequence", "$path.sequence") != 1) fail("$path.sequence")
    if (container["candidate"] != JsonNull) fail("$path.candidate")
    val confirmation = container.obj("confirmation", "$path.confirmation")
    confirmation.closed("$path.confirmation", setOf("mode", "confirmed"))
    if (confirmation.string("mode", "$path.confirmation.mode") != "explicit_manual_save") fail("$path.confirmation.mode")
    val input = container.obj("input", "$path.input")
    input.closed("$path.input", setOf("request_id", "kind", "occurred_at", "total_amount", "currency", "category_id", "funding_components", "settlement_explanation"))
    if (input.string("kind", "$path.input.kind") != "manual_mixed_expense") fail("$path.input.kind")
    val funding = input.array("funding_components", "$path.input.funding_components").mapIndexed { i, e -> funding(e.objectAt("$path.input.funding_components[$i]"), "$path.input.funding_components[$i]") }
    val settlement = input.obj("settlement_explanation", "$path.input.settlement_explanation")
    settlement.closed("$path.input.settlement_explanation", setOf("original_amount", "discount_amount", "settled_amount"))
    return Rg04ManualInput(input.field("request_id", "$path.input.request_id"), input.field("occurred_at", "$path.input.occurred_at"), input.field("total_amount", "$path.input.total_amount"), input.field("currency", "$path.input.currency"), input.field("category_id", "$path.input.category_id"), funding, Rg04SettlementInput(settlement.string("original_amount", "settlement.original_amount"), settlement.string("discount_amount", "settlement.discount_amount"), settlement.string("settled_amount", "settlement.settled_amount")), Rg04Field.Value(confirmation.boolean("confirmed", "$path.confirmation.confirmed")))
}

private fun repaymentOperation(container: JsonObject, path: String): Rg04RepaymentInput {
    container.closed(path, setOf("sequence", "id", "confirmation", "candidate", "input", "expected"))
    if (container.integer("sequence", "$path.sequence") != 2) fail("$path.sequence")
    if (container["candidate"] != JsonNull) fail("$path.candidate")
    val confirmation = container.obj("confirmation", "$path.confirmation")
    confirmation.closed("$path.confirmation", setOf("mode", "confirmed"))
    if (confirmation.string("mode", "$path.confirmation.mode") != "explicit_manual_save") fail("$path.confirmation.mode")
    val input = container.obj("input", "$path.input")
    input.closed("$path.input", setOf("request_id", "kind", "occurred_at", "asset_account_id", "liability_account_id", "principal_amount", "currency"))
    if (input.string("kind", "$path.input.kind") != "credit_repayment") fail("$path.input.kind")
    return Rg04RepaymentInput(input.field("request_id", "$path.input.request_id"), input.field("occurred_at", "$path.input.occurred_at"), input.field("asset_account_id", "$path.input.asset_account_id"), input.field("liability_account_id", "$path.input.liability_account_id"), input.field("principal_amount", "$path.input.principal_amount"), input.field("currency", "$path.input.currency"), Rg04Field.Value(confirmation.boolean("confirmed", "$path.confirmation.confirmed")))
}

private fun invalidOperation(value: JsonObject, path: String): Rg04DecodedOperation.Manual {
    value.closed(path, setOf("id", "input", "expected"))
    val input = value.obj("input", "$path.input")
    input.closed("$path.input", setOf("total_amount", "currency", "category_id", "asset_account_id", "asset_funding_amount", "liability_account_id", "liability_funding_amount", "funding_components"))
    val funding = input["funding_components"]?.let { array -> array.arrayAt("$path.input.funding_components").mapIndexed { i, e -> funding(e.objectAt("$path.input.funding_components[$i]"), "$path.input.funding_components[$i]") } } ?: listOf(
        Rg04FundingInput(input.field("asset_account_id", "$path.input.asset_account_id"), input.field("asset_funding_amount", "$path.input.asset_funding_amount"), input.field("currency", "$path.input.currency")),
        Rg04FundingInput(input.field("liability_account_id", "$path.input.liability_account_id"), input.field("liability_funding_amount", "$path.input.liability_funding_amount"), input.field("currency", "$path.input.currency")),
    )
    val expected = value.obj("expected", "$path.expected")
    val reason = expected.string("reason", "$path.expected.reason"); val field = expected.string("field", "$path.expected.field")
    val rawId = value.string("id", "$path.id")
    return Rg04DecodedOperation.Manual(
        Rg04ManualInput(Rg04Field.Value(rg04RejectedRequestId(rawId)), Rg04Field.Value("2026-02-10T12:00:00+08:00"), input.field("total_amount", "$path.input.total_amount"), input.field("currency", "$path.input.currency"), input.field("category_id", "$path.input.category_id"), funding, Rg04SettlementInput("135.00", "15.00", (input["total_amount"] as? JsonPrimitive)?.content ?: "120.00"), Rg04Field.Value(true)),
        Rg04Expected.Rejected(reason, field),
        Rg04OperationSource("$.invalid_manual_inputs[*]", rawId, rawId),
        Rg04OperationClass.REJECTION,
    )
}

private fun funding(value: JsonObject, path: String): Rg04FundingInput { value.closed(path, setOf("account_id", "funding_amount", "currency", "evidence_available")); return Rg04FundingInput(value.field("account_id", "$path.account_id"), value.field("funding_amount", "$path.funding_amount"), value.field("currency", "$path.currency")) }
private fun JsonObject.field(name: String, path: String): Rg04Field<String> = when (val e = this[name]) { null -> Rg04Field.Omitted; JsonNull -> Rg04Field.Null; is JsonPrimitive -> if (e.isString) Rg04Field.Value(e.content) else fail(path, Rg04RawJsonContractErrorReason.WRONG_TYPE); else -> fail(path, Rg04RawJsonContractErrorReason.WRONG_TYPE) }
private fun JsonObject.closed(path: String, allowed: Set<String>) { keys.firstOrNull { it !in allowed }?.let { fail("$path.$it", Rg04RawJsonContractErrorReason.UNKNOWN_FIELD) } }
private fun JsonObject.obj(name: String, path: String) = this[name]?.objectAt(path) ?: fail(path, Rg04RawJsonContractErrorReason.WRONG_TYPE)
private fun JsonObject.array(name: String, path: String) = this[name]?.arrayAt(path) ?: fail(path, Rg04RawJsonContractErrorReason.WRONG_TYPE)
private fun JsonElement.objectAt(path: String) = this as? JsonObject ?: fail(path, Rg04RawJsonContractErrorReason.WRONG_TYPE)
private fun JsonElement.arrayAt(path: String) = this as? JsonArray ?: fail(path, Rg04RawJsonContractErrorReason.WRONG_TYPE)
private fun JsonObject.string(name: String, path: String) = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail(path, Rg04RawJsonContractErrorReason.WRONG_TYPE)
private fun JsonObject.nullableString(name: String, path: String) = when (val e = this[name]) { JsonNull -> null; is JsonPrimitive -> e.takeIf { it.isString }?.content ?: fail(path, Rg04RawJsonContractErrorReason.WRONG_TYPE); else -> fail(path, Rg04RawJsonContractErrorReason.WRONG_TYPE) }
private fun JsonObject.integer(name: String, path: String) = (this[name] as? JsonPrimitive)?.intOrNull ?: fail(path, Rg04RawJsonContractErrorReason.WRONG_TYPE)
private fun JsonObject.boolean(name: String, path: String) = (this[name] as? JsonPrimitive)?.booleanOrNull ?: fail(path, Rg04RawJsonContractErrorReason.WRONG_TYPE)
private fun <T> Rg04Field<T>.valueOrNull(): T? = (this as? Rg04Field.Value)?.value
private data class Rg04DecodeFailure(val path: String, val reason: Rg04RawJsonContractErrorReason) : RuntimeException()
private fun fail(path: String, reason: Rg04RawJsonContractErrorReason = Rg04RawJsonContractErrorReason.INVALID_VALUE): Nothing = throw Rg04DecodeFailure(path, reason)
private fun bad(path: String, reason: Rg04RawJsonContractErrorReason) = Rg04RawJsonDecodeResult.Invalid(Rg04RawJsonContractError(path, reason))

private fun rg04RejectedRequestId(rawId: String): String {
    val rootId = rg04UuidV5("RG-04\n@root\nroot\n$.invalid_manual_inputs[*]\noccurrence=$rawId")
    return rg04UuidV5("RG-04\n$rootId\nrequest\n$.invalid_manual_inputs[*].id\noccurrence=$rawId")
}

private val rg04UuidNamespace = "cfad3f84edb15838ae53aae49684cf1a".chunked(2)
    .map { it.toInt(16).toByte() }
    .toByteArray()

private fun rg04UuidV5(name: String): String {
    val bytes = rg04Sha1(rg04UuidNamespace + name.encodeToByteArray()).copyOf(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}

private fun rg04Sha1(input: ByteArray): ByteArray {
    val bitLength = input.size.toLong() * 8
    val paddedSize = ((input.size + 9 + 63) / 64) * 64
    val padded = ByteArray(paddedSize)
    input.copyInto(padded)
    padded[input.size] = 0x80.toByte()
    repeat(8) { index -> padded[padded.lastIndex - index] = (bitLength ushr (index * 8)).toByte() }

    var h0 = 0x67452301
    var h1 = 0xEFCDAB89.toInt()
    var h2 = 0x98BADCFE.toInt()
    var h3 = 0x10325476
    var h4 = 0xC3D2E1F0.toInt()
    val words = IntArray(80)
    for (offset in padded.indices step 64) {
        for (index in 0 until 16) {
            val start = offset + index * 4
            words[index] = ((padded[start].toInt() and 0xff) shl 24) or
                ((padded[start + 1].toInt() and 0xff) shl 16) or
                ((padded[start + 2].toInt() and 0xff) shl 8) or
                (padded[start + 3].toInt() and 0xff)
        }
        for (index in 16 until 80) words[index] = rg04RotateLeft(words[index - 3] xor words[index - 8] xor words[index - 14] xor words[index - 16], 1)
        var a = h0
        var b = h1
        var c = h2
        var d = h3
        var e = h4
        for (index in 0 until 80) {
            val f: Int
            val k: Int
            when (index) {
                in 0..19 -> { f = (b and c) or (b.inv() and d); k = 0x5A827999 }
                in 20..39 -> { f = b xor c xor d; k = 0x6ED9EBA1 }
                in 40..59 -> { f = (b and c) or (b and d) or (c and d); k = 0x8F1BBCDC.toInt() }
                else -> { f = b xor c xor d; k = 0xCA62C1D6.toInt() }
            }
            val next = rg04RotateLeft(a, 5) + f + e + k + words[index]
            e = d
            d = c
            c = rg04RotateLeft(b, 30)
            b = a
            a = next
        }
        h0 += a
        h1 += b
        h2 += c
        h3 += d
        h4 += e
    }
    return listOf(h0, h1, h2, h3, h4).flatMap { word ->
        listOf((word ushr 24).toByte(), (word ushr 16).toByte(), (word ushr 8).toByte(), word.toByte())
    }.toByteArray()
}

private fun rg04RotateLeft(value: Int, bits: Int): Int = (value shl bits) or (value ushr (32 - bits))
