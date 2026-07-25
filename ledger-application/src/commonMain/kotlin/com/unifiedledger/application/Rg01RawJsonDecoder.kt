package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

enum class Rg01RawJsonContractErrorReason {
    MALFORMED_JSON,
    DUPLICATE_KEY,
    RESOURCE_LIMIT,
    UNKNOWN_FIELD,
    WRONG_TYPE,
    INVALID_VALUE,
}

const val RG01_RAW_JSON_MAX_UTF8_BYTES: Int = 1_048_576
const val RG01_RAW_JSON_MAX_NESTING_DEPTH: Int = 64

data class Rg01RawJsonContractError(
    val fieldPath: String,
    val reason: Rg01RawJsonContractErrorReason,
    val message: String? = null,
)

sealed interface Rg01RawJsonDecodeResult {
    data class Success(val value: Rg01RawJsonCase) : Rg01RawJsonDecodeResult
    data class Invalid(val error: Rg01RawJsonContractError) : Rg01RawJsonDecodeResult
}

enum class Rg01UnsupportedSection {
    NOTE_UPDATE,
    OPENING_STATE,
    FULL_STATE,
    REPORTS,
    RECONCILIATION,
    DELTAS,
}

data class Rg01DecodedSource(
    val locator: String,
    val sourceId: String? = null,
)

data class Rg01DecodedExpectedOutcome(
    val status: Rg01OutcomeStatus,
    val fieldPath: String? = null,
    val reasonCode: String? = null,
    val transactionId: String? = null,
    val versionId: String? = null,
)

data class Rg01DecodedOperation(
    val source: Rg01DecodedSource,
    val input: Rg01DecodedManualExpenseInput,
    val expected: Rg01DecodedExpectedOutcome,
)

data class Rg01DecodedInvalidOperation(
    val source: Rg01DecodedSource,
    val input: Rg01DecodedManualExpenseInput,
    val expected: Rg01DecodedExpectedOutcome,
)

data class Rg01DecodedTransactionNoteUpdateInput(
    val requestId: String,
    val transactionId: String,
    val note: String,
    val explicitConfirmation: Boolean,
)

data class Rg01DecodedNoteUpdateOperation(
    val source: Rg01DecodedSource,
    val input: Rg01DecodedTransactionNoteUpdateInput,
    val expected: Rg01DecodedExpectedOutcome,
)

data class Rg01RawJsonCase(
    val caseId: String,
    val context: Rg01ManualExpenseContext,
    val create: Rg01DecodedOperation,
    val noteUpdate: Rg01DecodedNoteUpdateOperation,
    val retry: Rg01DecodedOperation,
    val distinct: Rg01DecodedOperation,
    val invalidInputs: List<Rg01DecodedInvalidOperation>,
    val unsupportedSections: Set<Rg01UnsupportedSection>,
)

private val json = Json { ignoreUnknownKeys = false }

fun decodeRg01RawJson(raw: String): Rg01RawJsonDecodeResult {
    strictJsonPreflight(raw, duplicateKeyPath = false)?.let { issue ->
        return invalid(issue.path, when (issue.reason) {
            StrictJsonPreflightReason.RESOURCE_LIMIT -> Rg01RawJsonContractErrorReason.RESOURCE_LIMIT
            StrictJsonPreflightReason.DUPLICATE_KEY -> Rg01RawJsonContractErrorReason.DUPLICATE_KEY
            StrictJsonPreflightReason.MALFORMED_JSON -> Rg01RawJsonContractErrorReason.MALFORMED_JSON
            StrictJsonPreflightReason.OBJECT_ROOT_REQUIRED -> Rg01RawJsonContractErrorReason.WRONG_TYPE
        })
    }
    val element = try {
        json.parseToJsonElement(raw)
    } catch (_: Exception) {
        return invalid("$", Rg01RawJsonContractErrorReason.MALFORMED_JSON)
    }
    val root = element as? JsonObject
        ?: return invalid("$", Rg01RawJsonContractErrorReason.WRONG_TYPE)
    return try {
        decodeRoot(root)
    } catch (failure: MappingFailure) {
        invalid(failure.path, failure.reason)
    } catch (_: Exception) {
        invalid("$", Rg01RawJsonContractErrorReason.MALFORMED_JSON)
    }
}

private fun decodeRoot(root: JsonObject): Rg01RawJsonDecodeResult.Success {
    root.closed(
        "$",
        setOf("schema_version", "case", "catalog", "opening", "create", "note_update", "idempotency", "distinct_reentry", "invalid_inputs", "forbidden_side_effects"),
    )
    requireInt(root, "schema_version", "$.schema_version").also { if (it != 1) fail("$.schema_version", Rg01RawJsonContractErrorReason.INVALID_VALUE) }
    val case = requireObject(root, "case", "$.case")
    case.closed("$.case", setOf("id", "level", "rule_version", "timezone", "currency", "precision", "ledger_id"))
    val caseId = requireString(case, "id", "$.case.id")
    requireValue(caseId == "RG-01", "$.case.id")
    requireValue(requireString(case, "level", "$.case.level") == "core_required", "$.case.level")
    requireValue(requireInt(case, "rule_version", "$.case.rule_version") == 1, "$.case.rule_version")
    val timezone = requireString(case, "timezone", "$.case.timezone")
    requireValue(timezone == "Asia/Shanghai", "$.case.timezone")
    val currency = CurrencyUnit(
        requireString(case, "currency", "$.case.currency"),
        requireInt(case, "precision", "$.case.precision"),
    )
    requireValue(currency == CurrencyUnit("CNY", 2), "$.case.currency")
    val ledgerId = LedgerId(requireString(case, "ledger_id", "$.case.ledger_id"))
    requireValue(ledgerId == LedgerId("ledger-a"), "$.case.ledger_id")
    val catalog = decodeCatalog(requireObject(root, "catalog", "$.catalog"), ledgerId, currency)
    val context = Rg01ManualExpenseContext(ledgerId, currency, catalog, timezone, "+08:00")
    requireObject(root, "opening", "$.opening")
    requireArray(root, "forbidden_side_effects", "$.forbidden_side_effects")
    val create = decodeCreation(requireObject(root, "create", "$.create"), "$.create")
    val noteUpdate = decodeNoteUpdate(requireObject(root, "note_update", "$.note_update"))
    val idempotency = requireObject(root, "idempotency", "$.idempotency")
    idempotency.closed("$.idempotency", setOf("repeated_request_id", "expected"))
    val retryInput = create.input.copy(requestId = Rg01JsonField.Value(requireString(idempotency, "repeated_request_id", "$.idempotency.repeated_request_id")))
    if (retryInput.requestId != create.input.requestId) fail("$.idempotency.repeated_request_id", Rg01RawJsonContractErrorReason.INVALID_VALUE)
    val retryExpectedObject = requireObject(idempotency, "expected", "$.idempotency.expected")
    retryExpectedObject.closed("$.idempotency.expected", setOf("returned_transaction_id", "new_transaction_count", "new_posting_set_count", "new_version_count", "funding_effect_count", "balances", "statistics", "reconciliation"))
    val retry = Rg01DecodedOperation(
        Rg01DecodedSource("$.idempotency", "repeated_request_id"),
        retryInput,
        Rg01DecodedExpectedOutcome(Rg01OutcomeStatus.NO_CHANGE, reasonCode = "idempotent_replay", transactionId = requireString(retryExpectedObject, "returned_transaction_id", "$.idempotency.expected.returned_transaction_id")),
    )
    val distinct = decodeDistinct(requireObject(root, "distinct_reentry", "$.distinct_reentry"), "$.distinct_reentry", create.input.explicitConfirmation)
    val invalid = decodeInvalidInputs(requireArray(root, "invalid_inputs", "$.invalid_inputs"))
    return Rg01RawJsonDecodeResult.Success(
        Rg01RawJsonCase(
            caseId,
            context,
            create,
            noteUpdate,
            retry,
            distinct,
            invalid,
            setOf(Rg01UnsupportedSection.OPENING_STATE, Rg01UnsupportedSection.FULL_STATE, Rg01UnsupportedSection.REPORTS, Rg01UnsupportedSection.RECONCILIATION, Rg01UnsupportedSection.DELTAS),
        ),
    )
}

private fun decodeNoteUpdate(root: JsonObject): Rg01DecodedNoteUpdateOperation {
    root.closed("$.note_update", setOf("request", "expected"))
    val request = requireObject(root, "request", "$.note_update.request")
    request.closed("$.note_update.request", setOf("request_id", "transaction_id", "note"))
    val expected = requireObject(root, "expected", "$.note_update.expected")
    expected.closed("$.note_update.expected", setOf("transaction_id", "current_version_id", "versions", "effective_posting_set_id", "effective_transaction_count", "funding_effect_count", "balances", "statistics", "reconciliation", "evidence_refs"))
    val requestId = requireString(request, "request_id", "$.note_update.request.request_id")
    val transactionId = requireString(request, "transaction_id", "$.note_update.request.transaction_id")
    val note = requireString(request, "note", "$.note_update.request.note")
    requireValue(requireString(expected, "transaction_id", "$.note_update.expected.transaction_id") == transactionId, "$.note_update.expected.transaction_id")
    val currentVersionId = requireString(expected, "current_version_id", "$.note_update.expected.current_version_id")
    requireArray(expected, "versions", "$.note_update.expected.versions").forEachIndexed { index, element ->
        val versionPath = "$.note_update.expected.versions[$index]"
        val version = element.objectAt(versionPath)
        version.closed(versionPath, setOf("id", "status", "note"))
        requireString(version, "id", "$versionPath.id")
        requireString(version, "status", "$versionPath.status")
        requireString(version, "note", "$versionPath.note")
    }
    requireString(expected, "effective_posting_set_id", "$.note_update.expected.effective_posting_set_id")
    requireInt(expected, "effective_transaction_count", "$.note_update.expected.effective_transaction_count")
    requireInt(expected, "funding_effect_count", "$.note_update.expected.funding_effect_count")
    requireStringMap(requireObject(expected, "balances", "$.note_update.expected.balances"), "$.note_update.expected.balances")
    requireStringMap(requireObject(expected, "statistics", "$.note_update.expected.statistics"), "$.note_update.expected.statistics")
    requireStringMap(requireObject(expected, "reconciliation", "$.note_update.expected.reconciliation"), "$.note_update.expected.reconciliation")
    requireArray(expected, "evidence_refs", "$.note_update.expected.evidence_refs").forEachIndexed { index, element ->
        val evidence = element as? JsonPrimitive
            ?: fail("$.note_update.expected.evidence_refs[$index]", Rg01RawJsonContractErrorReason.WRONG_TYPE)
        if (!evidence.isString) {
            fail("$.note_update.expected.evidence_refs[$index]", Rg01RawJsonContractErrorReason.WRONG_TYPE)
        }
    }
    return Rg01DecodedNoteUpdateOperation(
        Rg01DecodedSource("$.note_update.request", requestId),
        Rg01DecodedTransactionNoteUpdateInput(requestId, transactionId, note, true),
        Rg01DecodedExpectedOutcome(Rg01OutcomeStatus.ACCEPTED, transactionId = transactionId, versionId = currentVersionId),
    )
}

private fun decodeDistinct(root: JsonObject, path: String, explicitConfirmation: Rg01JsonField<Boolean>): Rg01DecodedOperation {
    root.closed(path, setOf("request", "expected"))
    val input = decodeFullInput(requireObject(root, "request", "$path.request"), "$path.request", explicitConfirmation)
    val expected = requireObject(root, "expected", "$path.expected")
    expected.closed("$path.expected", setOf("accepted", "new_transaction_count", "effective_transaction_count", "transaction", "statistics", "balances"))
    val transaction = requireObject(expected, "transaction", "$path.expected.transaction")
    if (!requireBoolean(expected, "accepted", "$path.expected.accepted")) fail("$path.expected.accepted", Rg01RawJsonContractErrorReason.INVALID_VALUE)
    transaction.closed("$path.expected.transaction", setOf("id", "current_version_id", "posting_set_id", "occurred_at", "effective", "postings"))
    return Rg01DecodedOperation(Rg01DecodedSource(path, input.requestId.decodedValueOrNull()), input, Rg01DecodedExpectedOutcome(
        Rg01OutcomeStatus.ACCEPTED,
        transactionId = requireString(transaction, "id", "$path.expected.transaction.id"),
        versionId = requireString(transaction, "current_version_id", "$path.expected.transaction.current_version_id"),
    ))
}

private fun decodeCatalog(root: JsonObject, ledgerId: LedgerId, currency: CurrencyUnit): LedgerCatalog {
    root.closed("$.catalog", setOf("accounts", "categories"))
    val accounts = requireArray(root, "accounts", "$.catalog.accounts").mapIndexed { index, element ->
        val objectValue = element.objectAt("$.catalog.accounts[$index]")
        objectValue.closed("$.catalog.accounts[$index]", setOf("id", "name", "kind", "real_account"))
        val kind = when (requireString(objectValue, "kind", "$.catalog.accounts[$index].kind")) {
            "asset" -> AccountKind.ASSET
            "liability" -> AccountKind.LIABILITY
            "equity" -> AccountKind.EQUITY
            "income" -> AccountKind.INCOME
            "expense" -> AccountKind.EXPENSE
            else -> fail("$.catalog.accounts[$index].kind", Rg01RawJsonContractErrorReason.INVALID_VALUE)
        }
        val accountId = AccountId(requireString(objectValue, "id", "$.catalog.accounts[$index].id"))
        Account(accountId, ledgerId, kind, currency, accountId.value == "asset-bank-a", requireBoolean(objectValue, "real_account", "$.catalog.accounts[$index].real_account"))
    }
    val categories = requireArray(root, "categories", "$.catalog.categories").mapIndexed { index, element ->
        val path = "$.catalog.categories[$index]"
        val objectValue = element.objectAt(path)
        objectValue.closed(path, setOf("id", "name", "parent_id", "posting_account_id", "active"))
        Category(
            CategoryId(requireString(objectValue, "id", "$path.id")), ledgerId,
            optionalString(objectValue, "parent_id", "$path.parent_id")?.let(::CategoryId),
            optionalString(objectValue, "posting_account_id", "$path.posting_account_id")?.let(::AccountId),
            requireBoolean(objectValue, "active", "$path.active"),
        )
    }
    return when (val result = LedgerCatalog.create(accounts, categories)) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> fail("$.catalog", Rg01RawJsonContractErrorReason.INVALID_VALUE)
    }
}

private fun decodeCreation(root: JsonObject, path: String): Rg01DecodedOperation {
    root.closed(path, setOf("confirmation", "candidate", "request", "expected"))
    val confirmation = requireObject(root, "confirmation", "$path.confirmation")
    confirmation.closed("$path.confirmation", setOf("mode", "confirmed"))
    if (requireString(confirmation, "mode", "$path.confirmation.mode") != "explicit_manual_save" || !requireBoolean(confirmation, "confirmed", "$path.confirmation.confirmed")) {
        fail("$path.confirmation", Rg01RawJsonContractErrorReason.INVALID_VALUE)
    }
    if (root["candidate"] != JsonNull) fail("$path.candidate", Rg01RawJsonContractErrorReason.INVALID_VALUE)
    val request = requireObject(root, "request", "$path.request")
    val input = decodeFullInput(request, "$path.request", Rg01JsonField.Value(true))
    val expected = requireObject(root, "expected", "$path.expected")
    expected.closed("$path.expected", setOf("accepted", "transaction", "balances", "statistics", "reconciliation", "evidence_refs"))
    val transaction = requireObject(expected, "transaction", "$path.expected.transaction")
    if (!requireBoolean(expected, "accepted", "$path.expected.accepted")) fail("$path.expected.accepted", Rg01RawJsonContractErrorReason.INVALID_VALUE)
    transaction.closed("$path.expected.transaction", setOf("id", "current_version_id", "posting_set_id", "occurred_at", "effective", "postings"))
    return Rg01DecodedOperation(Rg01DecodedSource(path, input.requestId.decodedValueOrNull()), input, Rg01DecodedExpectedOutcome(
        Rg01OutcomeStatus.ACCEPTED,
        transactionId = requireString(transaction, "id", "$path.expected.transaction.id"),
        versionId = requireString(transaction, "current_version_id", "$path.expected.transaction.current_version_id"),
    ))
}

private fun decodeFullInput(root: JsonObject, path: String, explicitConfirmation: Rg01JsonField<Boolean>): Rg01DecodedManualExpenseInput {
    root.closed(path, setOf("request_id", "kind", "occurred_at", "amount", "currency", "category_id", "payment_account_id", "note"))
    if (requireString(root, "kind", "$path.kind") != "manual_expense") fail("$path.kind", Rg01RawJsonContractErrorReason.INVALID_VALUE)
    return Rg01DecodedManualExpenseInput(
        Rg01JsonField.Value(requireString(root, "request_id", "$path.request_id")),
        stringField(root, "amount", "$path.amount"), stringField(root, "currency", "$path.currency"),
        stringField(root, "category_id", "$path.category_id"), stringField(root, "payment_account_id", "$path.payment_account_id"),
        stringField(root, "occurred_at", "$path.occurred_at"), stringField(root, "note", "$path.note"), explicitConfirmation,
    )
}

private fun decodeInvalidInputs(array: JsonArray): List<Rg01DecodedInvalidOperation> = array.mapIndexed { index, element ->
    val path = "$.invalid_inputs[$index]"
    val root = element.objectAt(path)
    root.closed(path, setOf("id", "input", "expected"))
    val sourceId = requireString(root, "id", "$path.id")
    val inputObject = requireObject(root, "input", "$path.input")
    inputObject.closed("$path.input", setOf("amount", "payment_account_id", "category_id"))
    val expected = requireObject(root, "expected", "$path.expected")
    expected.closed("$path.expected", setOf("accepted", "field", "reason", "new_transaction_count", "new_posting_count", "state_changes"))
    val field = requireString(expected, "field", "$path.expected.field")
    if (requireBoolean(expected, "accepted", "$path.expected.accepted")) fail("$path.expected.accepted", Rg01RawJsonContractErrorReason.INVALID_VALUE)
    val reason = optionalString(expected, "reason", "$path.expected.reason") ?: "missing_required_field"
    val input = Rg01DecodedManualExpenseInput(
        Rg01JsonField.Omitted, stringField(inputObject, "amount", "$path.input.amount"), stringField(inputObject, "currency", "$path.input.currency"),
        stringField(inputObject, "category_id", "$path.input.category_id"), stringField(inputObject, "payment_account_id", "$path.input.payment_account_id"),
        Rg01JsonField.Omitted, Rg01JsonField.Omitted, Rg01JsonField.Omitted,
    )
    Rg01DecodedInvalidOperation(Rg01DecodedSource(path, sourceId), input, Rg01DecodedExpectedOutcome(Rg01OutcomeStatus.REJECTED, "$.attempted_input.$field", reason))
}

private fun stringField(root: JsonObject, key: String, path: String): Rg01JsonField<String> = when {
    !root.containsKey(key) -> Rg01JsonField.Omitted
    root[key] is JsonPrimitive && root[key]!!.jsonPrimitive.contentOrNull == null -> Rg01JsonField.Null
    root[key] is JsonPrimitive && root[key]!!.jsonPrimitive.isString -> Rg01JsonField.Value(root[key]!!.jsonPrimitive.content)
    else -> fail(path, Rg01RawJsonContractErrorReason.WRONG_TYPE)
}

private fun requireString(root: JsonObject, key: String, path: String): String = when (val value = root[key]) {
    is JsonPrimitive -> if (value.isString) value.content else fail(path, Rg01RawJsonContractErrorReason.WRONG_TYPE)
    null -> fail(path, Rg01RawJsonContractErrorReason.WRONG_TYPE)
    else -> fail(path, Rg01RawJsonContractErrorReason.WRONG_TYPE)
}
private fun optionalString(root: JsonObject, key: String, path: String): String? {
    if (!root.containsKey(key) || root[key] == JsonNull) return null
    return requireString(root, key, path)
}
private fun requireBoolean(root: JsonObject, key: String, path: String): Boolean {
    val value = root[key] as? JsonPrimitive ?: fail(path, Rg01RawJsonContractErrorReason.WRONG_TYPE)
    if (value.isString) fail(path, Rg01RawJsonContractErrorReason.WRONG_TYPE)
    return value.booleanOrNull ?: fail(path, Rg01RawJsonContractErrorReason.WRONG_TYPE)
}
private fun requireInt(root: JsonObject, key: String, path: String): Int {
    val value = root[key] as? JsonPrimitive ?: fail(path, Rg01RawJsonContractErrorReason.WRONG_TYPE)
    if (value.isString) fail(path, Rg01RawJsonContractErrorReason.WRONG_TYPE)
    return value.intOrNull ?: fail(path, Rg01RawJsonContractErrorReason.WRONG_TYPE)
}
private fun requireObject(root: JsonObject, key: String, path: String): JsonObject = root[key]?.let { it.objectAt(path) } ?: fail(path, Rg01RawJsonContractErrorReason.WRONG_TYPE)
private fun requireArray(root: JsonObject, key: String, path: String): JsonArray = root[key]?.let { it.arrayAt(path) } ?: fail(path, Rg01RawJsonContractErrorReason.WRONG_TYPE)
private fun requireStringMap(root: JsonObject, path: String) {
    root.forEach { (key, value) ->
        if (value !is JsonPrimitive || !value.isString) fail("$path.$key", Rg01RawJsonContractErrorReason.WRONG_TYPE)
    }
}
private fun JsonElement.objectAt(path: String): JsonObject = this as? JsonObject ?: fail(path, Rg01RawJsonContractErrorReason.WRONG_TYPE)
private fun JsonElement.arrayAt(path: String): JsonArray = this as? JsonArray ?: fail(path, Rg01RawJsonContractErrorReason.WRONG_TYPE)
private fun JsonObject.closed(path: String, allowed: Set<String>) { keys.firstOrNull { it !in allowed }?.let { fail("$path.$it", Rg01RawJsonContractErrorReason.UNKNOWN_FIELD) } }
private fun invalid(path: String, reason: Rg01RawJsonContractErrorReason) = Rg01RawJsonDecodeResult.Invalid(Rg01RawJsonContractError(path, reason))
private fun fail(path: String, reason: Rg01RawJsonContractErrorReason): Nothing = throw MappingFailure(path, reason)
private fun requireValue(condition: Boolean, path: String) {
    if (!condition) fail(path, Rg01RawJsonContractErrorReason.INVALID_VALUE)
}
private class MappingFailure(val path: String, val reason: Rg01RawJsonContractErrorReason) : RuntimeException()
private fun <T> Rg01JsonField<T>.decodedValueOrNull(): T? = (this as? Rg01JsonField.Value<T>)?.value

