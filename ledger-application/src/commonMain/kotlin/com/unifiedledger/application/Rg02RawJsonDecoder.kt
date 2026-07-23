package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import kotlinx.serialization.json.*

enum class Rg02RawJsonContractErrorReason { MALFORMED_JSON, DUPLICATE_KEY, RESOURCE_LIMIT, UNKNOWN_FIELD, WRONG_TYPE, INVALID_VALUE }
data class Rg02RawJsonContractError(val fieldPath: String, val reason: Rg02RawJsonContractErrorReason)
sealed interface Rg02RawJsonDecodeResult { data class Success(val value: Rg02RawJsonCase) : Rg02RawJsonDecodeResult; data class Invalid(val error: Rg02RawJsonContractError) : Rg02RawJsonDecodeResult }
sealed interface Rg02JsonField<out T> { data object Omitted : Rg02JsonField<Nothing>; data object Null : Rg02JsonField<Nothing>; data class Value<T>(val value: T) : Rg02JsonField<T> }
data class Rg02DecodedManualIncomeInput(val requestId: Rg02JsonField<String>, val occurredAt: Rg02JsonField<String>, val amount: Rg02JsonField<String>, val currency: Rg02JsonField<String>, val categoryId: Rg02JsonField<String>, val receivingAccountId: Rg02JsonField<String>, val note: Rg02JsonField<String>, val explicitConfirmation: Rg02JsonField<Boolean>)
data class Rg02ExpectedPosting(val id: String, val accountId: String, val amount: String, val currency: String)
data class Rg02ExpectedOutcome(val accepted: Boolean, val fieldPath: String? = null, val reasonCode: String? = null, val transactionId: String? = null, val versionId: String? = null, val postingSetId: String? = null, val occurredAt: String? = null, val postings: List<Rg02ExpectedPosting> = emptyList(), val balances: Map<String, String> = emptyMap(), val effective: Boolean? = null) {
    val postingIds: List<String> get() = postings.map { it.id }
}
data class Rg02DecodedOperation(val source: String, val input: Rg02DecodedManualIncomeInput, val expected: Rg02ExpectedOutcome)
data class Rg02DecodedInvalidOperation(val sourceId: String, val input: Rg02DecodedManualIncomeInput, val expected: Rg02ExpectedOutcome)
data class Rg02DecodedCategoryRename(val categoryId: String, val newName: String)
data class Rg02DecodedCaseMetadata(val ledgerId: String, val currency: String, val precision: Int, val timezone: String, val openingBalances: Map<String, String> = emptyMap())
data class Rg02RawJsonCase(val create: Rg02DecodedOperation, val retryRequestId: String, val variants: List<Rg02DecodedOperation>, val invalidInputs: List<Rg02DecodedInvalidOperation>, val unsupportedCategoryRename: Rg02DecodedCategoryRename, val metadata: Rg02DecodedCaseMetadata, val catalog: LedgerCatalog)

private const val RG02_MAX_BYTES = 1_048_576
private val rg02Json = Json { ignoreUnknownKeys = false }

fun decodeRg02RawJson(raw: String): Rg02RawJsonDecodeResult {
    strictJsonPreflight(raw, duplicateKeyPath = false)?.let { issue ->
        return rg02Bad(issue.path, when (issue.reason) {
            StrictJsonPreflightReason.RESOURCE_LIMIT -> Rg02RawJsonContractErrorReason.RESOURCE_LIMIT
            StrictJsonPreflightReason.DUPLICATE_KEY -> Rg02RawJsonContractErrorReason.DUPLICATE_KEY
            StrictJsonPreflightReason.MALFORMED_JSON -> Rg02RawJsonContractErrorReason.MALFORMED_JSON
            StrictJsonPreflightReason.OBJECT_ROOT_REQUIRED -> Rg02RawJsonContractErrorReason.WRONG_TYPE
        })
    }
    val root = try { rg02Json.parseToJsonElement(raw) as? JsonObject ?: return rg02Bad("$", Rg02RawJsonContractErrorReason.WRONG_TYPE) }
    catch (_: Exception) { return rg02Bad("$", Rg02RawJsonContractErrorReason.MALFORMED_JSON) }
    return try {
        closed(root, "$.", ROOT_FIELDS)
        requireInt(root, "schema_version", "$.schema_version").also { if (it != 1) fail("$.schema_version", Rg02RawJsonContractErrorReason.INVALID_VALUE) }
        val metadata = validateCase(requireObj(root, "case", "$.case"))
        val catalog = decodeCatalog(requireObj(root, "catalog", "$.catalog"), metadata)
        val openingBalances = validateOpening(requireObj(root, "opening", "$.opening"))
        validateForbidden(requireArr(root, "forbidden_side_effects", "$.forbidden_side_effects"))
        val create = operation(requireObj(root, "create", "$.create"), "$.create")
        val rename = validateRename(requireObj(root, "category_rename", "$.category_rename"))
        val idem = requireObj(root, "idempotency", "$.idempotency")
        closed(idem, "$.idempotency", setOf("repeated_request_id", "expected"))
        val retry = requireString(idem, "repeated_request_id", "$.idempotency.repeated_request_id")
        val retryTransactionId = validateIdempotencyExpected(requireObj(idem, "expected", "$.idempotency.expected"))
        if ((create.input.requestId as Rg02JsonField.Value).value != retry) fail("$.idempotency.repeated_request_id", Rg02RawJsonContractErrorReason.INVALID_VALUE)
        if (create.expected.transactionId != retryTransactionId) fail("$.idempotency.expected.returned_transaction_id", Rg02RawJsonContractErrorReason.INVALID_VALUE)
        val invalids = requireArr(root, "invalid_inputs", "$.invalid_inputs").mapIndexed { i, e -> rg02Invalid(e.rg02ObjectAt("$.invalid_inputs[$i]"), "$.invalid_inputs[$i]") }
        val variants = requireArr(root, "variants", "$.variants").mapIndexed { i, e -> operation(e.rg02ObjectAt("$.variants[$i]"), "$.variants[$i]") }
        Rg02RawJsonDecodeResult.Success(Rg02RawJsonCase(create, retry, variants, invalids, rename, metadata.copy(openingBalances = openingBalances), catalog))
    } catch (failure: DecodeFailure) { rg02Bad(failure.path, failure.reason) }
}

private val ROOT_FIELDS = setOf("schema_version", "case", "catalog", "opening", "create", "category_rename", "idempotency", "invalid_inputs", "variants", "forbidden_side_effects")

private fun validateCase(value: JsonObject): Rg02DecodedCaseMetadata {
    closed(value, "$.case", setOf("id", "level", "rule_version", "timezone", "currency", "precision", "ledger_id"))
    if (requireString(value, "id", "$.case.id") != "RG-02") fail("$.case.id", Rg02RawJsonContractErrorReason.INVALID_VALUE)
    if (requireString(value, "level", "$.case.level") != "core_required") fail("$.case.level", Rg02RawJsonContractErrorReason.INVALID_VALUE)
    if (requireInt(value, "rule_version", "$.case.rule_version") != 1) fail("$.case.rule_version", Rg02RawJsonContractErrorReason.INVALID_VALUE)
    val timezone = requireString(value, "timezone", "$.case.timezone"); val currency = requireString(value, "currency", "$.case.currency"); val precision = requireInt(value, "precision", "$.case.precision"); val ledger = requireString(value, "ledger_id", "$.case.ledger_id")
    if (timezone != "Asia/Shanghai") fail("$.case.timezone", Rg02RawJsonContractErrorReason.INVALID_VALUE)
    if (currency != "CNY") fail("$.case.currency", Rg02RawJsonContractErrorReason.INVALID_VALUE)
    if (precision != 2) fail("$.case.precision", Rg02RawJsonContractErrorReason.INVALID_VALUE)
    if (!ledger.isRg02StableId() || ledger != "ledger-a") fail("$.case.ledger_id", Rg02RawJsonContractErrorReason.INVALID_VALUE)
    return Rg02DecodedCaseMetadata(ledger, currency, precision, timezone)
}

private fun decodeCatalog(value: JsonObject, metadata: Rg02DecodedCaseMetadata): LedgerCatalog {
    closed(value, "$.catalog", setOf("accounts", "categories"))
    val ledgerId = LedgerId(metadata.ledgerId)
    val currency = CurrencyUnit(metadata.currency, metadata.precision)
    val accounts = requireArr(value, "accounts", "$.catalog.accounts").mapIndexed { i, e ->
        val path = "$.catalog.accounts[$i]"; val account = e.rg02ObjectAt(path)
        closed(account, path, setOf("id", "name", "kind", "real_account"))
        val id = requireString(account, "id", "$path.id")
        if (!id.isRg02StableId()) fail("$path.id", Rg02RawJsonContractErrorReason.INVALID_VALUE)
        requireString(account, "name", "$path.name")
        val kind = when (requireString(account, "kind", "$path.kind")) {
            "asset" -> AccountKind.ASSET
            "liability" -> AccountKind.LIABILITY
            "equity" -> AccountKind.EQUITY
            "income" -> AccountKind.INCOME
            "expense" -> AccountKind.EXPENSE
            else -> fail("$path.kind", Rg02RawJsonContractErrorReason.INVALID_VALUE)
        }
        val realAccount = requireBoolean(account, "real_account", "$path.real_account")
        if (realAccount && kind !in setOf(AccountKind.ASSET, AccountKind.LIABILITY)) {
            fail("$path.real_account", Rg02RawJsonContractErrorReason.INVALID_VALUE)
        }
        Account(AccountId(id), ledgerId, kind, currency, realAccount && kind in setOf(AccountKind.ASSET, AccountKind.LIABILITY), realAccount)
    }
    val categories = requireArr(value, "categories", "$.catalog.categories").mapIndexed { i, e ->
        val path = "$.catalog.categories[$i]"; val category = e.rg02ObjectAt(path)
        closed(category, path, setOf("id", "name", "kind", "parent_id", "posting_account_id", "active"))
        val id = requireString(category, "id", "$path.id")
        if (!id.isRg02StableId()) fail("$path.id", Rg02RawJsonContractErrorReason.INVALID_VALUE)
        requireString(category, "name", "$path.name")
        val kind = when (requireString(category, "kind", "$path.kind")) {
            "income" -> CategoryKind.INCOME
            "expense" -> CategoryKind.EXPENSE
            else -> fail("$path.kind", Rg02RawJsonContractErrorReason.INVALID_VALUE)
        }
        Category(
            CategoryId(id), ledgerId,
            optionalString(category, "parent_id", "$path.parent_id")?.also { if (!it.isRg02StableId()) fail("$path.parent_id", Rg02RawJsonContractErrorReason.INVALID_VALUE) }?.let(::CategoryId),
            optionalString(category, "posting_account_id", "$path.posting_account_id")?.also { if (!it.isRg02StableId()) fail("$path.posting_account_id", Rg02RawJsonContractErrorReason.INVALID_VALUE) }?.let(::AccountId),
            requireBoolean(category, "active", "$path.active"),
            kind,
        )
    }
    val accountById = accounts.associateBy { it.id }
    val categoryById = categories.associateBy { it.id }
    categories.forEachIndexed { i, category ->
        val path = "$.catalog.categories[$i]"
        val parent = category.parentId?.let { categoryById[it] }
            ?: if (category.parentId == null) null else fail("$path.parent_id", Rg02RawJsonContractErrorReason.INVALID_VALUE)
        if (category.parentId != null && (parent!!.parentId != null || parent.kind != category.kind)) {
            fail("$path.parent_id", Rg02RawJsonContractErrorReason.INVALID_VALUE)
        }
        val postingAccount = category.postingAccountId?.let { accountById[it] }
            ?: if (category.postingAccountId == null) null else fail("$path.posting_account_id", Rg02RawJsonContractErrorReason.INVALID_VALUE)
        if (postingAccount != null && postingAccount.kind != when (category.kind) {
                CategoryKind.INCOME -> AccountKind.INCOME
                CategoryKind.EXPENSE -> AccountKind.EXPENSE
            }) {
            fail("$path.posting_account_id", Rg02RawJsonContractErrorReason.INVALID_VALUE)
        }
        if (category.parentId == null && category.postingAccountId != null) {
            fail("$path.posting_account_id", Rg02RawJsonContractErrorReason.INVALID_VALUE)
        }
    }
    return when (val result = LedgerCatalog.create(accounts, categories)) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> fail("$.catalog", Rg02RawJsonContractErrorReason.INVALID_VALUE)
    }
}

private fun validateOpening(value: JsonObject): Map<String, String> {
    closed(value, "$.opening", setOf("transactions", "expected_balances")); val balances = stringMap(requireObj(value, "expected_balances", "$.opening.expected_balances"), "$.opening.expected_balances")
    requireArr(value, "transactions", "$.opening.transactions").forEachIndexed { i, e ->
        val path = "$.opening.transactions[$i]"; val tx = e.rg02ObjectAt(path)
        closed(tx, path, setOf("id", "occurred_at", "effective", "postings")); requireString(tx, "id", "$path.id"); requireString(tx, "occurred_at", "$path.occurred_at"); requireBoolean(tx, "effective", "$path.effective")
        requireArr(tx, "postings", "$path.postings").forEachIndexed { j, p ->
            val pp = "$path.postings[$j]"; val posting = p.rg02ObjectAt(pp); closed(posting, pp, setOf("id", "account_id", "amount", "currency")); requireString(posting, "id", "$pp.id"); requireString(posting, "account_id", "$pp.account_id"); requireString(posting, "amount", "$pp.amount"); requireString(posting, "currency", "$pp.currency")
        }
    }
    return balances
}

private fun validateForbidden(value: JsonArray) { value.forEachIndexed { i, e -> if ((e as? JsonPrimitive)?.isString != true) fail("$.forbidden_side_effects[$i]", Rg02RawJsonContractErrorReason.WRONG_TYPE) } }

private fun operation(value: JsonObject, path: String): Rg02DecodedOperation {
    closed(value, path, setOf("id", "confirmation", "candidate", "request", "expected"))
    if (value.containsKey("id")) requireString(value, "id", "$path.id")
    val confirmation = validateConfirmation(requireObj(value, "confirmation", "$path.confirmation"), "$path.confirmation")
    if (!value.containsKey("candidate") || value["candidate"] != JsonNull) fail("$path.candidate", Rg02RawJsonContractErrorReason.INVALID_VALUE)
    val request = requireObj(value, "request", "$path.request"); val input = input(request, "$path.request", false, Rg02JsonField.Value(confirmation))
    if (requireString(request, "kind", "$path.request.kind") != "manual_income") fail("$path.request.kind", Rg02RawJsonContractErrorReason.INVALID_VALUE)
    return Rg02DecodedOperation(path, input, outcome(requireObj(value, "expected", "$path.expected"), "$path.expected"))
}

private fun input(value: JsonObject, path: String, sparse: Boolean, explicitConfirmation: Rg02JsonField<Boolean>): Rg02DecodedManualIncomeInput {
    closed(value, path, setOf("request_id", "kind", "occurred_at", "amount", "currency", "category_id", "receiving_account_id", "note"))
    if (!sparse) listOf("request_id", "kind", "occurred_at", "amount", "currency", "category_id", "receiving_account_id").forEach { if (!value.containsKey(it) || value[it] == JsonNull) fail("$path.$it", Rg02RawJsonContractErrorReason.INVALID_VALUE) }
    return Rg02DecodedManualIncomeInput(value.field("request_id", path), value.field("occurred_at", path), value.field("amount", path), value.field("currency", path), value.field("category_id", path), value.field("receiving_account_id", path), value.field("note", path), explicitConfirmation)
}

private fun validateConfirmation(value: JsonObject, path: String): Boolean { closed(value, path, setOf("mode", "confirmed")); val confirmed = requireBoolean(value, "confirmed", "$path.confirmed"); if (requireString(value, "mode", "$path.mode") != "explicit_manual_save" || !confirmed) fail(path, Rg02RawJsonContractErrorReason.INVALID_VALUE); return confirmed }

private fun outcome(value: JsonObject, path: String): Rg02ExpectedOutcome {
    val accepted = requireBoolean(value, "accepted", "$path.accepted")
    if (!accepted) { closed(value, path, setOf("accepted", "field", "reason", "state_unchanged", "new_transaction_count", "new_posting_count", "new_version_count", "reconciliation_change_count")); val field = requireString(value, "field", "$path.field"); val reason = requireString(value, "reason", "$path.reason"); requireBoolean(value, "state_unchanged", "$path.state_unchanged"); requireInt(value, "new_transaction_count", "$path.new_transaction_count"); requireInt(value, "new_posting_count", "$path.new_posting_count"); requireInt(value, "new_version_count", "$path.new_version_count"); requireInt(value, "reconciliation_change_count", "$path.reconciliation_change_count"); return Rg02ExpectedOutcome(false, "$.attempted_input.$field", reason) }
    closed(value, path, setOf("accepted", "transaction", "balances", "statistics", "reconciliation", "evidence_refs")); validateResultState(value, path)
    val tx = requireObj(value, "transaction", "$path.transaction"); closed(tx, "$path.transaction", setOf("id", "current_version_id", "posting_set_id", "occurred_at", "effective", "postings")); requireString(tx, "occurred_at", "$path.transaction.occurred_at"); val effective = requireBoolean(tx, "effective", "$path.transaction.effective")
    val postings = requireArr(tx, "postings", "$path.transaction.postings").mapIndexed { i, e -> val pp = "$path.transaction.postings[$i]"; val p = e.rg02ObjectAt(pp); closed(p, pp, setOf("id", "account_id", "amount", "currency")); Rg02ExpectedPosting(requireString(p, "id", "$pp.id"), requireString(p, "account_id", "$pp.account_id"), requireString(p, "amount", "$pp.amount"), requireString(p, "currency", "$pp.currency")) }
    return Rg02ExpectedOutcome(true, transactionId = requireString(tx, "id", "$path.transaction.id"), versionId = requireString(tx, "current_version_id", "$path.transaction.current_version_id"), postingSetId = requireString(tx, "posting_set_id", "$path.transaction.posting_set_id"), occurredAt = requireString(tx, "occurred_at", "$path.transaction.occurred_at"), postings = postings, balances = stringMap(requireObj(value, "balances", "$path.balances"), "$path.balances"), effective = effective)
}

private fun validateResultState(value: JsonObject, path: String) { requireStringMap(requireObj(value, "balances", "$path.balances"), "$path.balances"); requireStringMap(requireObj(value, "statistics", "$path.statistics"), "$path.statistics"); requireStringMap(requireObj(value, "reconciliation", "$path.reconciliation"), "$path.reconciliation"); requireArr(value, "evidence_refs", "$path.evidence_refs").forEachIndexed { i, e -> if ((e as? JsonPrimitive)?.isString != true) fail("$path.evidence_refs[$i]", Rg02RawJsonContractErrorReason.WRONG_TYPE) } }

private fun validateRename(value: JsonObject): Rg02DecodedCategoryRename { closed(value, "$.category_rename", setOf("request", "expected")); val request = requireObj(value, "request", "$.category_rename.request"); closed(request, "$.category_rename.request", setOf("category_id", "new_name")); val categoryId = requireString(request, "category_id", "$.category_rename.request.category_id"); val newName = requireString(request, "new_name", "$.category_rename.request.new_name"); val expected = requireObj(value, "expected", "$.category_rename.expected"); closed(expected, "$.category_rename.expected", setOf("category_id", "current_name", "display_path", "name_versions", "transaction_category_id", "posting_account_id", "transaction_version_count", "funding_effect_count", "balances", "statistics", "reconciliation", "evidence_refs")); requireString(expected, "category_id", "$.category_rename.expected.category_id"); requireString(expected, "current_name", "$.category_rename.expected.current_name"); requireString(expected, "display_path", "$.category_rename.expected.display_path"); requireString(expected, "transaction_category_id", "$.category_rename.expected.transaction_category_id"); requireString(expected, "posting_account_id", "$.category_rename.expected.posting_account_id"); requireInt(expected, "transaction_version_count", "$.category_rename.expected.transaction_version_count"); requireInt(expected, "funding_effect_count", "$.category_rename.expected.funding_effect_count"); validateResultState(expected, "$.category_rename.expected"); requireArr(expected, "name_versions", "$.category_rename.expected.name_versions").forEachIndexed { i, e -> val p = "$.category_rename.expected.name_versions[$i]"; val v = e.rg02ObjectAt(p); closed(v, p, setOf("version", "status", "name")); requireInt(v, "version", "$p.version"); requireString(v, "status", "$p.status"); requireString(v, "name", "$p.name") }; return Rg02DecodedCategoryRename(categoryId, newName) }

private fun validateIdempotencyExpected(value: JsonObject): String { closed(value, "$.idempotency.expected", setOf("returned_transaction_id", "new_transaction_count", "new_posting_set_count", "new_version_count", "funding_effect_count", "balances", "statistics", "reconciliation", "evidence_refs")); val transactionId = requireString(value, "returned_transaction_id", "$.idempotency.expected.returned_transaction_id"); listOf("new_transaction_count", "new_posting_set_count", "new_version_count", "funding_effect_count").forEach { requireInt(value, it, "$.idempotency.expected.$it") }; validateResultState(value, "$.idempotency.expected"); return transactionId }

private fun JsonObject.field(name: String, path: String): Rg02JsonField<String> = when { !containsKey(name) -> Rg02JsonField.Omitted; this[name] == JsonNull -> Rg02JsonField.Null; else -> Rg02JsonField.Value(requireString(this, name, "$path.$name")) }
private fun requireString(root: JsonObject, name: String, path: String): String { val value = root[name] as? JsonPrimitive ?: fail(path, Rg02RawJsonContractErrorReason.WRONG_TYPE); if (!value.isString) fail(path, Rg02RawJsonContractErrorReason.WRONG_TYPE); return value.content }
private fun optionalString(root: JsonObject, name: String, path: String): String? = if (!root.containsKey(name) || root[name] == JsonNull) null else requireString(root, name, path)
private fun requireBoolean(root: JsonObject, name: String, path: String): Boolean { val value = root[name] as? JsonPrimitive ?: fail(path, Rg02RawJsonContractErrorReason.WRONG_TYPE); if (value.isString) fail(path, Rg02RawJsonContractErrorReason.WRONG_TYPE); return value.booleanOrNull ?: fail(path, Rg02RawJsonContractErrorReason.WRONG_TYPE) }
private fun requireInt(root: JsonObject, name: String, path: String): Int { val value = root[name] as? JsonPrimitive ?: fail(path, Rg02RawJsonContractErrorReason.WRONG_TYPE); if (value.isString) fail(path, Rg02RawJsonContractErrorReason.WRONG_TYPE); return value.intOrNull ?: fail(path, Rg02RawJsonContractErrorReason.WRONG_TYPE) }
private fun requireObj(root: JsonObject, name: String, path: String): JsonObject = root[name]?.let { it as? JsonObject ?: fail(path, Rg02RawJsonContractErrorReason.WRONG_TYPE) } ?: fail(path, Rg02RawJsonContractErrorReason.WRONG_TYPE)
private fun requireArr(root: JsonObject, name: String, path: String): JsonArray = root[name]?.let { it as? JsonArray ?: fail(path, Rg02RawJsonContractErrorReason.WRONG_TYPE) } ?: fail(path, Rg02RawJsonContractErrorReason.WRONG_TYPE)
private fun requireStringMap(root: JsonObject, path: String) { root.forEach { (key, value) -> if ((value as? JsonPrimitive)?.isString != true) fail("$path.$key", Rg02RawJsonContractErrorReason.WRONG_TYPE) } }
private fun stringMap(root: JsonObject, path: String): Map<String, String> = root.mapValues { (key, value) -> val primitive = value as? JsonPrimitive ?: fail("$path.$key", Rg02RawJsonContractErrorReason.WRONG_TYPE); if (!primitive.isString) fail("$path.$key", Rg02RawJsonContractErrorReason.WRONG_TYPE); primitive.content }
private fun JsonElement.rg02ObjectAt(path: String): JsonObject = this as? JsonObject ?: fail(path, Rg02RawJsonContractErrorReason.WRONG_TYPE)
private fun String.isRg02StableId(): Boolean = isNotEmpty() && all { character -> character.code !in 0..31 && character.code != 127 }
private fun closed(value: JsonObject, path: String, allowed: Set<String>) { value.keys.firstOrNull { it !in allowed }?.let { fail(if (path.endsWith(".")) "$path$it" else "$path.$it", Rg02RawJsonContractErrorReason.UNKNOWN_FIELD) } }
private class DecodeFailure(val path: String, val reason: Rg02RawJsonContractErrorReason) : RuntimeException()
private fun fail(path: String, reason: Rg02RawJsonContractErrorReason): Nothing = throw DecodeFailure(path, reason)
private fun rg02Bad(path: String, reason: Rg02RawJsonContractErrorReason) = Rg02RawJsonDecodeResult.Invalid(Rg02RawJsonContractError(path, reason))

private fun rg02Invalid(value: JsonObject, path: String): Rg02DecodedInvalidOperation {
    closed(value, path, setOf("id", "input", "expected")); val sourceId = requireString(value, "id", "$path.id")
    val inputObject = requireObj(value, "input", "$path.input"); closed(inputObject, "$path.input", setOf("amount", "receiving_account_id", "category_id"))
    val input = Rg02DecodedManualIncomeInput(Rg02JsonField.Omitted, Rg02JsonField.Omitted, inputObject.field("amount", "$path.input"), Rg02JsonField.Omitted, inputObject.field("category_id", "$path.input"), inputObject.field("receiving_account_id", "$path.input"), Rg02JsonField.Omitted, Rg02JsonField.Omitted)
    return Rg02DecodedInvalidOperation(sourceId, input, outcome(requireObj(value, "expected", "$path.expected"), "$path.expected"))
}

private sealed interface Rg02JsonScanIssue { data class DuplicateKey(val path: String) : Rg02JsonScanIssue; data class ResourceLimit(val path: String) : Rg02JsonScanIssue }
private class Rg02DuplicateKeyScanner(private val text: String) {
    private var index = 0
    fun scan(): Rg02JsonScanIssue? = try { skipWhitespace(); value("$", 0); skipWhitespace(); if (index != text.length) throw IllegalArgumentException(); null } catch (duplicate: DuplicateKey) { Rg02JsonScanIssue.DuplicateKey(duplicate.path) } catch (limit: ResourceLimit) { Rg02JsonScanIssue.ResourceLimit(limit.path) } catch (_: Exception) { null }
    private fun value(path: String, parentDepth: Int) { skipWhitespace(); when (text.getOrNull(index)) { '{' -> obj(path, parentDepth + 1); '[' -> arr(path, parentDepth + 1); '"' -> string(); 't' -> literal("true"); 'f' -> literal("false"); 'n' -> literal("null"); '-', in '0'..'9' -> number(); else -> throw IllegalArgumentException() } }
    private fun obj(path: String, depth: Int) { checkDepth(path, depth); index++; val keys = mutableSetOf<String>(); skipWhitespace(); if (take('}')) return; while (true) { skipWhitespace(); val key = string(); if (!keys.add(key)) throw DuplicateKey(path); skipWhitespace(); expect(':'); value("$path.$key", depth); skipWhitespace(); if (take('}')) return; expect(',') } }
    private fun arr(path: String, depth: Int) { checkDepth(path, depth); index++; skipWhitespace(); if (take(']')) return; var i = 0; while (true) { value("$path[$i]", depth); i++; skipWhitespace(); if (take(']')) return; expect(',') } }
    private fun checkDepth(path: String, depth: Int) { if (depth > 64) throw ResourceLimit(path) }
    private fun string(): String { expect('"'); val start = index; var escaped = false; while (index < text.length) { val c = text[index++]; if (c == '"' && !escaped) { val token = "\"${text.substring(start, index - 1)}\""; return try { Json.parseToJsonElement(token).jsonPrimitive.content } catch (_: Exception) { throw IllegalArgumentException() } }; escaped = c == '\\' && !escaped }; throw IllegalArgumentException() }
    private fun literal(value: String) { if (!text.startsWith(value, index)) throw IllegalArgumentException(); index += value.length }
    private fun number() { if (text[index] == '-') index++; while (text.getOrNull(index)?.isDigit() == true) index++; if (take('.')) while (text.getOrNull(index)?.isDigit() == true) index++; if (text.getOrNull(index) in listOf('e', 'E')) { index++; if (text.getOrNull(index) in listOf('+', '-')) index++; while (text.getOrNull(index)?.isDigit() == true) index++ } }
    private fun skipWhitespace() { while (text.getOrNull(index)?.isWhitespace() == true) index++ }
    private fun expect(c: Char) { if (!take(c)) throw IllegalArgumentException() }
    private fun take(c: Char): Boolean = if (text.getOrNull(index) == c) { index++; true } else false
    private class DuplicateKey(val path: String) : RuntimeException(); private class ResourceLimit(val path: String) : RuntimeException()
}
