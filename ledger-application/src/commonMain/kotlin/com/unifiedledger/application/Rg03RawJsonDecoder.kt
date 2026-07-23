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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

enum class Rg03RawJsonContractErrorReason {
    MALFORMED_JSON,
    DUPLICATE_KEY,
    RESOURCE_LIMIT,
    UNKNOWN_FIELD,
    WRONG_TYPE,
    INVALID_VALUE,
}

data class Rg03RawJsonContractError(
    val fieldPath: String,
    val reason: Rg03RawJsonContractErrorReason,
)

sealed interface Rg03RawJsonDecodeResult {
    data class Success(val value: Rg03RawJsonCase) : Rg03RawJsonDecodeResult
    data class Invalid(val error: Rg03RawJsonContractError) : Rg03RawJsonDecodeResult
}

sealed interface Rg03JsonField<out T> {
    data object Omitted : Rg03JsonField<Nothing>
    data object Null : Rg03JsonField<Nothing>
    data class Value<T>(val value: T) : Rg03JsonField<T>
}

enum class Rg03ActionType {
    MANUAL_ACCOUNT_TRANSFER,
    IMPORT_SOURCE_RECORD,
    EXPLICIT_CANDIDATE_CONFIRMATION,
    IMPORT_MIRROR_RECORD,
}

data class Rg03DecodedInput(
    val requestId: Rg03JsonField<String> = Rg03JsonField.Omitted,
    val occurredAt: Rg03JsonField<String> = Rg03JsonField.Omitted,
    val sourceAccountId: Rg03JsonField<String> = Rg03JsonField.Omitted,
    val destinationAccountId: Rg03JsonField<String> = Rg03JsonField.Omitted,
    val sourceDebitAmount: Rg03JsonField<String> = Rg03JsonField.Omitted,
    val destinationCreditAmount: Rg03JsonField<String> = Rg03JsonField.Omitted,
    val feeAmount: Rg03JsonField<String> = Rg03JsonField.Omitted,
    val currency: Rg03JsonField<String> = Rg03JsonField.Omitted,
    val sourceCurrency: Rg03JsonField<String> = Rg03JsonField.Omitted,
    val destinationCurrency: Rg03JsonField<String> = Rg03JsonField.Omitted,
    val feeCategoryId: Rg03JsonField<String> = Rg03JsonField.Omitted,
    val explicitConfirmation: Rg03JsonField<Boolean> = Rg03JsonField.Omitted,
    val candidateId: Rg03JsonField<String> = Rg03JsonField.Omitted,
    val confirmed: Rg03JsonField<Boolean> = Rg03JsonField.Omitted,
    val sourceId: Rg03JsonField<String> = Rg03JsonField.Omitted,
    val evidenceId: Rg03JsonField<String> = Rg03JsonField.Omitted,
    val observedAt: Rg03JsonField<String> = Rg03JsonField.Omitted,
    val accountId: Rg03JsonField<String> = Rg03JsonField.Omitted,
    val creditAmount: Rg03JsonField<String> = Rg03JsonField.Omitted,
    val completeness: Rg03JsonField<String> = Rg03JsonField.Omitted,
)

sealed interface Rg03ExpectedOutcome {
    data object Accepted : Rg03ExpectedOutcome
    data object NoChange : Rg03ExpectedOutcome
    data class Rejected(val field: String, val reason: String) : Rg03ExpectedOutcome
}

data class Rg03DecodedOperation(
    val actionType: Rg03ActionType,
    val input: Rg03DecodedInput,
    val expected: Rg03ExpectedOutcome,
    val replayOf: String?,
)

data class Rg03DecodedSourceInput(val completeness: String)

data class Rg03RawJsonCase(
    val ledgerId: LedgerId,
    val currency: CurrencyUnit,
    val timezone: String,
    val catalog: LedgerCatalog,
    val operations: List<Rg03DecodedOperation>,
    val sourceInputs: List<Rg03DecodedSourceInput>,
    val supportsCombinationTransfer: Boolean,
)

private const val RG03_MAX_BYTES = 1_048_576
private val rg03Json = Json { ignoreUnknownKeys = false }

fun decodeRg03RawJson(raw: String): Rg03RawJsonDecodeResult {
    strictJsonPreflight(raw, duplicateKeyPath = true)?.let { issue ->
        return rg03Bad(issue.path, when (issue.reason) {
            StrictJsonPreflightReason.RESOURCE_LIMIT -> Rg03RawJsonContractErrorReason.RESOURCE_LIMIT
            StrictJsonPreflightReason.DUPLICATE_KEY -> Rg03RawJsonContractErrorReason.DUPLICATE_KEY
            StrictJsonPreflightReason.MALFORMED_JSON -> Rg03RawJsonContractErrorReason.MALFORMED_JSON
            StrictJsonPreflightReason.OBJECT_ROOT_REQUIRED -> Rg03RawJsonContractErrorReason.WRONG_TYPE
        })
    }
    val root = try {
        rg03Json.parseToJsonElement(raw) as? JsonObject
            ?: return rg03Bad("$", Rg03RawJsonContractErrorReason.WRONG_TYPE)
    } catch (_: Exception) {
        return rg03Bad("$", Rg03RawJsonContractErrorReason.MALFORMED_JSON)
    }
    return try {
        close(root, "$", ROOT_FIELDS)
        if (requiredInt(root, "schema_version", "$.schema_version") != 1) invalid("$.schema_version")
        val case = requiredObject(root, "case", "$.case")
        close(case, "$.case", CASE_FIELDS)
        if (requiredString(case, "id", "$.case.id") != "RG-03") invalid("$.case.id")
        if (requiredString(case, "level", "$.case.level") != "core_required") invalid("$.case.level")
        if (requiredInt(case, "rule_version", "$.case.rule_version") != 1) invalid("$.case.rule_version")
        val timezone = requiredString(case, "timezone", "$.case.timezone")
        val currencyCode = requiredString(case, "currency", "$.case.currency")
        val precision = requiredInt(case, "precision", "$.case.precision")
        val ledgerId = requiredString(case, "ledger_id", "$.case.ledger_id")
        val scope = requiredString(case, "scope", "$.case.scope")
        if (timezone != "Asia/Shanghai") invalid("$.case.timezone")
        if (currencyCode != "CNY") invalid("$.case.currency")
        if (precision != 2) invalid("$.case.precision")
        if (ledgerId != "ledger-a") invalid("$.case.ledger_id")
        if (scope != "one_to_one_same_currency_own_real_financial_account_transfer") invalid("$.case.scope")
        val currency = CurrencyUnit(currencyCode, precision)
        val catalog = decodeCatalog(requiredObject(root, "catalog", "$.catalog"), LedgerId(ledgerId), currency)
        validateOpeningOracle(requiredObject(root, "opening", "$.opening"), "$.opening")

        val manual = requiredObject(root, "manual_create", "$.manual_create")
        close(manual, "$.manual_create", MANUAL_FIELDS)
        requireFrozenString(manual, "independent_baseline", "$.manual_create.independent_baseline", "opening")
        if (manual["candidate"] !== JsonNull) invalid("$.manual_create.candidate")
        val confirmation = requiredObject(manual, "confirmation", "$.manual_create.confirmation")
        close(confirmation, "$.manual_create.confirmation", setOf("mode", "confirmed"))
        if (requiredString(confirmation, "mode", "$.manual_create.confirmation.mode") != "explicit_manual_save") {
            invalid("$.manual_create.confirmation.mode")
        }
        val manualRequest = requiredObject(manual, "request", "$.manual_create.request")
        if (requiredString(manualRequest, "kind", "$.manual_create.request.kind") != "manual_account_transfer") {
            invalid("$.manual_create.request.kind")
        }
        val manualInput = decodeInput(manualRequest, "$.manual_create.request")
            .copy(explicitConfirmation = booleanField(confirmation, "confirmed", "$.manual_create.confirmation.confirmed"))
        validateManualOracle(requiredObject(manual, "expected", "$.manual_create.expected"), "$.manual_create.expected")

        val lifecycle = requiredObject(root, "import_lifecycle", "$.import_lifecycle")
        close(lifecycle, "$.import_lifecycle", setOf("independent_baseline", "ordered_operations"))
        requireFrozenString(lifecycle, "independent_baseline", "$.import_lifecycle.independent_baseline", "opening")
        val lifecycleOperations = requiredArray(lifecycle, "ordered_operations", "$.import_lifecycle.ordered_operations")
        if (lifecycleOperations.size != 3) invalid("$.import_lifecycle.ordered_operations")
        val source = decodeLifecycleOperation(lifecycleOperations[0], 0, "import_source_record")
        val confirmationInput = decodeLifecycleOperation(
            lifecycleOperations[1],
            1,
            "explicit_candidate_confirmation",
        )
        val mirror = decodeLifecycleOperation(lifecycleOperations[2], 2, "import_mirror_record")

        val incomplete = requiredObject(root, "unknown_one_sided_debit", "$.unknown_one_sided_debit")
        close(incomplete, "$.unknown_one_sided_debit", setOf("independent_baseline", "input", "expected", "retry"))
        requireFrozenString(
            incomplete,
            "independent_baseline",
            "$.unknown_one_sided_debit.independent_baseline",
            "opening",
        )
        val incompleteInput = decodeSourceContainer(
            requiredObject(incomplete, "input", "$.unknown_one_sided_debit.input"),
            "$.unknown_one_sided_debit.input",
        )
        validateIncompleteOracle(
            requiredObject(incomplete, "expected", "$.unknown_one_sided_debit.expected"),
            "$.unknown_one_sided_debit.expected",
        )

        val operations = mutableListOf<Rg03DecodedOperation>()
        operations += Rg03DecodedOperation(Rg03ActionType.MANUAL_ACCOUNT_TRANSFER, manualInput, Rg03ExpectedOutcome.Accepted, null)
        operations += Rg03DecodedOperation(Rg03ActionType.IMPORT_SOURCE_RECORD, source, Rg03ExpectedOutcome.Accepted, null)
        operations += Rg03DecodedOperation(Rg03ActionType.EXPLICIT_CANDIDATE_CONFIRMATION, confirmationInput, Rg03ExpectedOutcome.Accepted, null)
        operations += Rg03DecodedOperation(Rg03ActionType.IMPORT_MIRROR_RECORD, mirror, Rg03ExpectedOutcome.Accepted, null)
        operations += Rg03DecodedOperation(Rg03ActionType.IMPORT_SOURCE_RECORD, incompleteInput, Rg03ExpectedOutcome.Accepted, null)

        val idempotency = requiredObject(root, "idempotency", "$.idempotency")
        close(idempotency, "$.idempotency", IDEMPOTENCY_FIELDS)
        operations += replay(
            Rg03ActionType.MANUAL_ACCOUNT_TRANSFER,
            manualInput,
            requiredString(idempotency, "repeated_manual_request_id", "$.idempotency.repeated_manual_request_id"),
            "$.idempotency.repeated_manual_request_id",
        )
        operations += replay(
            Rg03ActionType.IMPORT_SOURCE_RECORD,
            source,
            requiredString(idempotency, "repeated_source_request_id", "$.idempotency.repeated_source_request_id"),
            "$.idempotency.repeated_source_request_id",
        )
        operations += replay(
            Rg03ActionType.EXPLICIT_CANDIDATE_CONFIRMATION,
            confirmationInput,
            requiredString(idempotency, "repeated_confirmation_request_id", "$.idempotency.repeated_confirmation_request_id"),
            "$.idempotency.repeated_confirmation_request_id",
        )
        operations += replay(
            Rg03ActionType.IMPORT_MIRROR_RECORD,
            mirror,
            requiredString(idempotency, "repeated_mirror_request_id", "$.idempotency.repeated_mirror_request_id"),
            "$.idempotency.repeated_mirror_request_id",
        )
        val retry = requiredObject(incomplete, "retry", "$.unknown_one_sided_debit.retry")
        close(retry, "$.unknown_one_sided_debit.retry", setOf("repeated_request_id", "expected"))
        validateRetryOracle(
            requiredObject(retry, "expected", "$.unknown_one_sided_debit.retry.expected"),
            "$.unknown_one_sided_debit.retry.expected",
        )
        operations += replay(
            Rg03ActionType.IMPORT_SOURCE_RECORD,
            incompleteInput,
            requiredString(retry, "repeated_request_id", "$.unknown_one_sided_debit.retry.repeated_request_id"),
            "$.unknown_one_sided_debit.retry.repeated_request_id",
        )

        val invalidInputs = requiredArray(root, "invalid_manual_inputs", "$.invalid_manual_inputs")
        if (invalidInputs.size != 10) invalid("$.invalid_manual_inputs")
        invalidInputs.forEachIndexed { index, element ->
            val item = element as? JsonObject ?: wrong("$.invalid_manual_inputs[$index]")
            close(item, "$.invalid_manual_inputs[$index]", setOf("id", "input", "expected"))
            val id = requiredString(item, "id", "$.invalid_manual_inputs[$index].id")
            val overrides = decodeManualOverride(
                requiredObject(item, "input", "$.invalid_manual_inputs[$index].input"),
                "$.invalid_manual_inputs[$index].input",
            )
            val expected = requiredObject(item, "expected", "$.invalid_manual_inputs[$index].expected")
            validateInvalidOracle(expected, "$.invalid_manual_inputs[$index].expected")
            val rejected = Rg03ExpectedOutcome.Rejected(
                requiredString(expected, "field", "$.invalid_manual_inputs[$index].expected.field"),
                requiredString(expected, "reason", "$.invalid_manual_inputs[$index].expected.reason"),
            )
            operations += Rg03DecodedOperation(
                Rg03ActionType.MANUAL_ACCOUNT_TRANSFER,
                overlay(manualInput, overrides).copy(requestId = Rg03JsonField.Value(id)),
                rejected,
                null,
            )
        }
        if (operations.size != 20) invalid("$")

        val outOfScope = requiredObject(root, "out_of_scope", "$.out_of_scope")
        close(outOfScope, "$.out_of_scope", setOf("combination_transfer", "fee_refund", "target_balance_adjustment"))
        mapOf(
            "combination_transfer" to "future_draft",
            "fee_refund" to "RG-07",
            "target_balance_adjustment" to "RG-09",
        ).forEach { (field, expected) ->
            if (requiredString(outOfScope, field, "$.out_of_scope.$field") != expected) {
                invalid("$.out_of_scope.$field")
            }
        }
        validateIdempotencyOracle(
            requiredObject(idempotency, "expected", "$.idempotency.expected"),
            "$.idempotency.expected",
        )
        val forbidden = requiredArray(root, "forbidden_side_effects", "$.forbidden_side_effects")
        val seenForbidden = mutableSetOf<String>()
        forbidden.forEachIndexed { index, value ->
            if (value !is JsonPrimitive || !value.isString) wrong("$.forbidden_side_effects[$index]")
            if (value.content !in FORBIDDEN_SIDE_EFFECTS || !seenForbidden.add(value.content)) {
                invalid("$.forbidden_side_effects[$index]")
            }
        }
        if (seenForbidden != FORBIDDEN_SIDE_EFFECTS) invalid("$.forbidden_side_effects")

        Rg03RawJsonDecodeResult.Success(
            Rg03RawJsonCase(
                LedgerId(ledgerId),
                currency,
                timezone,
                catalog,
                operations,
                listOf(source, incompleteInput).map {
                    Rg03DecodedSourceInput((it.completeness as Rg03JsonField.Value).value)
                },
                supportsCombinationTransfer = false,
            ),
        )
    } catch (failure: Rg03DecodeFailure) {
        Rg03RawJsonDecodeResult.Invalid(failure.error)
    }
}

private fun replay(
    type: Rg03ActionType,
    input: Rg03DecodedInput,
    identity: String,
    identityPath: String,
): Rg03DecodedOperation {
    if (input.requestId != Rg03JsonField.Value(identity)) invalid(identityPath)
    return Rg03DecodedOperation(
        type,
        input.copy(requestId = Rg03JsonField.Value(identity)),
        Rg03ExpectedOutcome.NoChange,
        identity,
    )
}

private fun decodeCatalog(root: JsonObject, ledgerId: LedgerId, currency: CurrencyUnit): LedgerCatalog {
    close(root, "$.catalog", setOf("accounts", "categories"))
    val seenAccountIds = mutableSetOf<String>()
    val accounts = requiredArray(root, "accounts", "$.catalog.accounts").mapIndexed { index, element ->
        val path = "$.catalog.accounts[$index]"
        val item = element as? JsonObject ?: wrong(path)
        close(item, path, setOf("id", "name", "kind", "real_account", "owned_by_user"))
        val id = requiredString(item, "id", "$path.id")
        requiredString(item, "name", "$path.name")
        val kind = when (requiredString(item, "kind", "$path.kind")) {
            "asset" -> AccountKind.ASSET
            "liability" -> AccountKind.LIABILITY
            "equity" -> AccountKind.EQUITY
            "income" -> AccountKind.INCOME
            "expense" -> AccountKind.EXPENSE
            else -> invalid("$path.kind")
        }
        val realAccount = requiredBoolean(item, "real_account", "$path.real_account")
        val ownedByUser = requiredBoolean(item, "owned_by_user", "$path.owned_by_user")
        val frozen = RG03_ACCOUNTS[id] ?: invalid("$path.id")
        if (!seenAccountIds.add(id)) invalid("$path.id")
        if (kind != frozen.kind) invalid("$path.kind")
        if (realAccount != frozen.realAccount) invalid("$path.real_account")
        if (ownedByUser != frozen.ownedByUser) invalid("$path.owned_by_user")
        Account(
            AccountId(id),
            ledgerId,
            kind,
            currency,
            ownedByUser,
            realAccount,
        )
    }
    if (seenAccountIds != RG03_ACCOUNTS.keys) invalid("$.catalog.accounts")

    val seenCategoryIds = mutableSetOf<String>()
    val categories = requiredArray(root, "categories", "$.catalog.categories").mapIndexed { index, element ->
        val path = "$.catalog.categories[$index]"
        val item = element as? JsonObject ?: wrong(path)
        close(item, path, setOf("id", "name", "kind", "parent_id", "posting_account_id", "active"))
        val id = requiredString(item, "id", "$path.id")
        requiredString(item, "name", "$path.name")
        val kind = requiredString(item, "kind", "$path.kind")
        val parentId = nullableString(item, "parent_id", "$path.parent_id")
        val postingAccountId = nullableString(item, "posting_account_id", "$path.posting_account_id")
        val active = requiredBoolean(item, "active", "$path.active")
        val frozen = RG03_CATEGORIES[id] ?: invalid("$path.id")
        if (!seenCategoryIds.add(id)) invalid("$path.id")
        if (kind != "expense") invalid("$path.kind")
        if (parentId != frozen.parentId) invalid("$path.parent_id")
        if (postingAccountId != frozen.postingAccountId) invalid("$path.posting_account_id")
        if (active != frozen.active) invalid("$path.active")
        Category(
            CategoryId(id),
            ledgerId,
            parentId?.let(::CategoryId),
            postingAccountId?.let(::AccountId),
            active,
            CategoryKind.EXPENSE,
        )
    }
    if (seenCategoryIds != RG03_CATEGORIES.keys) invalid("$.catalog.categories")
    return when (val result = LedgerCatalog.create(accounts, categories)) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> invalid("$.catalog")
    }
}

private fun decodeLifecycleOperation(element: JsonElement, index: Int, kind: String): Rg03DecodedInput {
    val path = "$.import_lifecycle.ordered_operations[$index]"
    val operation = element as? JsonObject ?: wrong(path)
    close(operation, path, setOf("sequence", "id", "input", "expected"))
    if (requiredInt(operation, "sequence", "$path.sequence") != index + 1) invalid("$path.sequence")
    requireFrozenString(operation, "id", "$path.id", RG03_LIFECYCLE_OPERATION_IDS[index])
    val expected = requiredObject(operation, "expected", "$path.expected")
    when (index) {
        0 -> validateSourceLifecycleOracle(expected, "$path.expected")
        1 -> validateConfirmationLifecycleOracle(expected, "$path.expected")
        2 -> validateMirrorLifecycleOracle(expected, "$path.expected")
        else -> invalid(path)
    }
    val input = requiredObject(operation, "input", "$path.input")
    if (requiredString(input, "kind", "$path.input.kind") != kind) invalid("$path.input.kind")
    return when (kind) {
        "import_source_record" -> decodeSourceContainer(input, "$path.input")
        "explicit_candidate_confirmation" -> {
            close(input, "$path.input", setOf("request_id", "kind", "candidate_id", "confirmed"))
            Rg03DecodedInput(
                requestId = stringField(input, "request_id", "$path.input.request_id"),
                candidateId = stringField(input, "candidate_id", "$path.input.candidate_id"),
                confirmed = booleanField(input, "confirmed", "$path.input.confirmed"),
            )
        }
        "import_mirror_record" -> decodeMirrorContainer(input, "$path.input")
        else -> invalid("$path.input.kind")
    }
}

private fun validateOracleBranch(value: JsonElement, path: String, fieldName: String? = null) {
    when {
        fieldName in ORACLE_DYNAMIC_STRING_MAPS -> {
            val map = value as? JsonObject ?: wrong(path)
            map.forEach { (key, item) ->
                if (item !is JsonPrimitive || !item.isString) wrong("$path.$key")
            }
        }
        fieldName in ORACLE_OBJECT_FIELDS -> {
            val objectValue = value as? JsonObject ?: wrong(path)
            validateOracleObject(objectValue, path)
        }
        fieldName in ORACLE_ARRAY_FIELDS -> {
            val array = value as? JsonArray ?: wrong(path)
            array.forEachIndexed { index, item ->
                if (fieldName in ORACLE_STRING_ARRAY_FIELDS) {
                    if (item !is JsonPrimitive || !item.isString) wrong("$path[$index]")
                } else {
                    validateOracleBranch(item, "$path[$index]")
                }
            }
        }
        fieldName in ORACLE_BOOLEAN_FIELDS -> {
            val primitiveValue = value as? JsonPrimitive ?: wrong(path)
            if (primitiveValue.isString || primitiveValue.booleanOrNull == null) wrong(path)
        }
        fieldName in ORACLE_INTEGER_FIELDS -> {
            val primitiveValue = value as? JsonPrimitive ?: wrong(path)
            if (primitiveValue.isString || primitiveValue.intOrNull == null) wrong(path)
        }
        fieldName == "balancing_account_id" && value === JsonNull -> Unit
        fieldName != null -> {
            val primitiveValue = value as? JsonPrimitive ?: wrong(path)
            if (!primitiveValue.isString) wrong(path)
        }
        value is JsonObject -> validateOracleObject(value, path)
        value is JsonArray -> value.forEachIndexed { index, item -> validateOracleBranch(item, "$path[$index]") }
        else -> wrong(path)
    }
}

private fun validateOracleObject(value: JsonObject, path: String) {
    value.forEach { (key, item) ->
        if (key !in ORACLE_FIELDS) {
            throw Rg03DecodeFailure(
                Rg03RawJsonContractError("$path.$key", Rg03RawJsonContractErrorReason.UNKNOWN_FIELD),
            )
        }
        validateOracleBranch(item, "$path.$key", key)
    }
}

private fun validateOpeningOracle(value: JsonObject, path: String) {
    oracleObject(value, path, OPENING_EXPECTED_FIELDS)
    requiredArray(value, "transactions", "$path.transactions").forEachIndexed { index, item ->
        val transactionPath = "$path.transactions[$index]"
        val transaction = item as? JsonObject ?: wrong(transactionPath)
        oracleObject(transaction, transactionPath, OPENING_TRANSACTION_FIELDS)
        validatePostings(transaction, transactionPath)
    }
}

private fun validateManualOracle(value: JsonObject, path: String) {
    oracleObject(value, path, MANUAL_EXPECTED_FIELDS)
    validateTransferTransaction(requiredObject(value, "transaction", "$path.transaction"), "$path.transaction", manual = true)
    validateFullStatistics(requiredObject(value, "statistics", "$path.statistics"), "$path.statistics")
}

private fun validateSourceLifecycleOracle(value: JsonObject, path: String) {
    oracleObject(value, path, SOURCE_LIFECYCLE_EXPECTED_FIELDS)
    val candidatePath = "$path.candidate"
    val candidate = requiredObject(value, "candidate", candidatePath)
    oracleObject(candidate, candidatePath, COMPLETE_CANDIDATE_FIELDS)
    oracleObject(requiredObject(candidate, "provenance", "$candidatePath.provenance"), "$candidatePath.provenance", SOURCE_CANDIDATE_PROVENANCE_FIELDS)
    val effectsPath = "$path.formal_effects"
    val effects = requiredObject(value, "formal_effects", effectsPath)
    oracleObject(effects, effectsPath, FORMAL_EFFECTS_FIELDS)
    validateZeroStatistics(requiredObject(effects, "statistics", "$effectsPath.statistics"), "$effectsPath.statistics")
}

private fun validateConfirmationLifecycleOracle(value: JsonObject, path: String) {
    oracleObject(value, path, CONFIRMATION_LIFECYCLE_EXPECTED_FIELDS)
    validateTransferTransaction(requiredObject(value, "transaction", "$path.transaction"), "$path.transaction", manual = false)
    validateFullStatistics(requiredObject(value, "statistics", "$path.statistics"), "$path.statistics")
    validateEvidenceLinks(value, path)
}

private fun validateMirrorLifecycleOracle(value: JsonObject, path: String) {
    oracleObject(value, path, MIRROR_LIFECYCLE_EXPECTED_FIELDS)
    validateFullStatistics(requiredObject(value, "statistics", "$path.statistics"), "$path.statistics")
    validateEvidenceLinks(value, path)
}

private fun validateIncompleteOracle(value: JsonObject, path: String) {
    oracleObject(value, path, INCOMPLETE_EXPECTED_FIELDS)
    val candidatePath = "$path.candidate"
    oracleObject(
        requiredObject(value, "candidate", candidatePath),
        candidatePath,
        INCOMPLETE_CANDIDATE_FIELDS,
    )
    validateZeroStatistics(requiredObject(value, "statistics", "$path.statistics"), "$path.statistics")
}

private fun validateRetryOracle(value: JsonObject, path: String) {
    oracleObject(value, path, RETRY_EXPECTED_FIELDS)
    validateZeroStatistics(requiredObject(value, "statistics", "$path.statistics"), "$path.statistics")
}

private fun validateIdempotencyOracle(value: JsonObject, path: String) {
    oracleObject(value, path, IDEMPOTENCY_EXPECTED_FIELDS)
    val manualPath = "$path.manual_state"
    val manual = requiredObject(value, "manual_state", manualPath)
    oracleObject(manual, manualPath, MANUAL_STATE_FIELDS)
    validateFullStatistics(requiredObject(manual, "statistics", "$manualPath.statistics"), "$manualPath.statistics")
    validateEvidenceLinks(manual, manualPath)

    val importPath = "$path.import_state"
    val imported = requiredObject(value, "import_state", importPath)
    oracleObject(imported, importPath, IMPORT_STATE_FIELDS)
    validateFullStatistics(requiredObject(imported, "statistics", "$importPath.statistics"), "$importPath.statistics")
    validateEvidenceLinks(imported, importPath)
}

private fun validateInvalidOracle(value: JsonObject, path: String) {
    oracleObject(value, path, INVALID_EXPECTED_FIELDS)
}

private fun validateTransferTransaction(value: JsonObject, path: String, manual: Boolean) {
    oracleObject(value, path, TRANSFER_TRANSACTION_FIELDS)
    val provenancePath = "$path.provenance"
    oracleObject(
        requiredObject(value, "provenance", provenancePath),
        provenancePath,
        if (manual) MANUAL_PROVENANCE_FIELDS else IMPORT_PROVENANCE_FIELDS,
    )
    validatePostings(value, path)
}

private fun validatePostings(transaction: JsonObject, path: String) {
    requiredArray(transaction, "postings", "$path.postings").forEachIndexed { index, item ->
        val postingPath = "$path.postings[$index]"
        val posting = item as? JsonObject ?: wrong(postingPath)
        oracleObject(posting, postingPath, POSTING_FIELDS, REQUIRED_POSTING_FIELDS)
    }
}

private fun validateEvidenceLinks(parent: JsonObject, path: String) {
    requiredArray(parent, "evidence_links", "$path.evidence_links").forEachIndexed { index, item ->
        val linkPath = "$path.evidence_links[$index]"
        val link = item as? JsonObject ?: wrong(linkPath)
        oracleObject(link, linkPath, EVIDENCE_LINK_FIELDS)
    }
}

private fun validateFullStatistics(value: JsonObject, path: String) {
    oracleObject(value, path, FULL_STATISTICS_FIELDS)
}

private fun validateZeroStatistics(value: JsonObject, path: String) {
    oracleObject(value, path, ZERO_STATISTICS_FIELDS)
}

private fun oracleObject(
    value: JsonObject,
    path: String,
    allowed: Set<String>,
    required: Set<String> = allowed,
) {
    close(value, path, allowed)
    validateOracleObject(value, path)
    required.firstOrNull { it !in value }?.let { wrong("$path.$it") }
}

private fun decodeSourceContainer(input: JsonObject, path: String): Rg03DecodedInput {
    close(input, path, setOf("request_id", "kind", "source_record"))
    if (requiredString(input, "kind", "$path.kind") != "import_source_record") invalid("$path.kind")
    val sourcePath = "$path.source_record"
    val source = requiredObject(input, "source_record", sourcePath)
    val completeness = requiredString(source, "completeness", "$sourcePath.completeness")
    val sourceDebitAmount: Rg03JsonField<String>
    val destinationAccountId: Rg03JsonField<String>
    val destinationCreditAmount: Rg03JsonField<String>
    val feeAmount: Rg03JsonField<String>
    when (completeness) {
        "complete" -> {
            close(source, sourcePath, COMPLETE_SOURCE_FIELDS)
            sourceDebitAmount = Rg03JsonField.Value(
                requiredString(source, "source_debit_amount", "$sourcePath.source_debit_amount"),
            )
            destinationAccountId = Rg03JsonField.Value(
                requiredString(source, "destination_account_id", "$sourcePath.destination_account_id"),
            )
            destinationCreditAmount = Rg03JsonField.Value(
                requiredString(source, "destination_credit_amount", "$sourcePath.destination_credit_amount"),
            )
            feeAmount = Rg03JsonField.Value(requiredString(source, "fee_amount", "$sourcePath.fee_amount"))
        }
        "missing_destination" -> {
            close(source, sourcePath, INCOMPLETE_SOURCE_FIELDS)
            sourceDebitAmount = Rg03JsonField.Value(
                requiredString(source, "debit_amount", "$sourcePath.debit_amount"),
            )
            destinationAccountId = when (source["destination_account_id"]) {
                null -> Rg03JsonField.Omitted
                JsonNull -> Rg03JsonField.Null
                else -> invalid("$sourcePath.destination_account_id")
            }
            destinationCreditAmount = Rg03JsonField.Omitted
            feeAmount = Rg03JsonField.Omitted
        }
        else -> invalid("$sourcePath.completeness")
    }
    return Rg03DecodedInput(
        requestId = stringField(input, "request_id", "$path.request_id"),
        sourceId = stringField(source, "id", "$sourcePath.id"),
        evidenceId = stringField(source, "evidence_id", "$sourcePath.evidence_id"),
        observedAt = stringField(source, "observed_at", "$sourcePath.observed_at"),
        sourceAccountId = stringField(source, "source_account_id", "$sourcePath.source_account_id"),
        destinationAccountId = destinationAccountId,
        sourceDebitAmount = sourceDebitAmount,
        destinationCreditAmount = destinationCreditAmount,
        feeAmount = feeAmount,
        currency = stringField(source, "currency", "$sourcePath.currency"),
        completeness = Rg03JsonField.Value(completeness),
    )
}

private fun decodeMirrorContainer(input: JsonObject, path: String): Rg03DecodedInput {
    close(input, path, setOf("request_id", "kind", "source_record"))
    val sourcePath = "$path.source_record"
    val source = requiredObject(input, "source_record", sourcePath)
    close(source, sourcePath, setOf("id", "evidence_id", "observed_at", "account_id", "credit_amount", "currency"))
    return Rg03DecodedInput(
        requestId = stringField(input, "request_id", "$path.request_id"),
        sourceId = stringField(source, "id", "$sourcePath.id"),
        evidenceId = stringField(source, "evidence_id", "$sourcePath.evidence_id"),
        observedAt = stringField(source, "observed_at", "$sourcePath.observed_at"),
        accountId = stringField(source, "account_id", "$sourcePath.account_id"),
        creditAmount = stringField(source, "credit_amount", "$sourcePath.credit_amount"),
        currency = stringField(source, "currency", "$sourcePath.currency"),
    )
}

private fun decodeInput(input: JsonObject, path: String): Rg03DecodedInput {
    close(input, path, INPUT_FIELDS)
    return Rg03DecodedInput(
        requestId = stringField(input, "request_id", "$path.request_id"),
        occurredAt = stringField(input, "occurred_at", "$path.occurred_at"),
        sourceAccountId = stringField(input, "source_account_id", "$path.source_account_id"),
        destinationAccountId = stringField(input, "destination_account_id", "$path.destination_account_id"),
        sourceDebitAmount = stringField(input, "source_debit_amount", "$path.source_debit_amount"),
        destinationCreditAmount = stringField(input, "destination_credit_amount", "$path.destination_credit_amount"),
        feeAmount = stringField(input, "fee_amount", "$path.fee_amount"),
        currency = stringField(input, "currency", "$path.currency"),
        sourceCurrency = stringField(input, "source_currency", "$path.source_currency"),
        destinationCurrency = stringField(input, "destination_currency", "$path.destination_currency"),
        feeCategoryId = stringField(input, "fee_category_id", "$path.fee_category_id"),
    )
}

private fun decodeManualOverride(input: JsonObject, path: String): Rg03DecodedInput {
    close(input, path, MANUAL_OVERRIDE_FIELDS)
    return Rg03DecodedInput(
        sourceAccountId = stringField(input, "source_account_id", "$path.source_account_id"),
        destinationAccountId = stringField(input, "destination_account_id", "$path.destination_account_id"),
        sourceDebitAmount = stringField(input, "source_debit_amount", "$path.source_debit_amount"),
        destinationCreditAmount = stringField(input, "destination_credit_amount", "$path.destination_credit_amount"),
        feeAmount = stringField(input, "fee_amount", "$path.fee_amount"),
        currency = stringField(input, "currency", "$path.currency"),
        sourceCurrency = stringField(input, "source_currency", "$path.source_currency"),
        destinationCurrency = stringField(input, "destination_currency", "$path.destination_currency"),
    )
}

private fun overlay(base: Rg03DecodedInput, override: Rg03DecodedInput) = base.copy(
    sourceAccountId = override.sourceAccountId.unlessOmitted(base.sourceAccountId),
    destinationAccountId = override.destinationAccountId.unlessOmitted(base.destinationAccountId),
    sourceDebitAmount = override.sourceDebitAmount.unlessOmitted(base.sourceDebitAmount),
    destinationCreditAmount = override.destinationCreditAmount.unlessOmitted(base.destinationCreditAmount),
    feeAmount = override.feeAmount.unlessOmitted(base.feeAmount),
    currency = override.currency.unlessOmitted(base.currency),
    sourceCurrency = override.sourceCurrency,
    destinationCurrency = override.destinationCurrency,
)

private fun <T> Rg03JsonField<T>.unlessOmitted(fallback: Rg03JsonField<T>): Rg03JsonField<T> =
    if (this == Rg03JsonField.Omitted) fallback else this

private fun close(value: JsonObject, path: String, allowed: Set<String>) {
    value.keys.firstOrNull { it !in allowed }?.let {
        throw Rg03DecodeFailure(Rg03RawJsonContractError("$path.$it", Rg03RawJsonContractErrorReason.UNKNOWN_FIELD))
    }
}

private fun requiredObject(parent: JsonObject, key: String, path: String): JsonObject =
    parent[key] as? JsonObject ?: wrong(path)
private fun requiredArray(parent: JsonObject, key: String, path: String): JsonArray =
    parent[key] as? JsonArray ?: wrong(path)
private fun requiredString(parent: JsonObject, key: String, path: String): String =
    primitive(parent[key], path).takeUnless { it.isString.not() }?.content ?: wrong(path)
private fun requireFrozenString(parent: JsonObject, key: String, path: String, expected: String) {
    if (requiredString(parent, key, path) != expected) invalid(path)
}
private fun requiredInt(parent: JsonObject, key: String, path: String): Int =
    primitive(parent[key], path).takeUnless(JsonPrimitive::isString)?.intOrNull ?: wrong(path)
private fun requiredBoolean(parent: JsonObject, key: String, path: String): Boolean =
    primitive(parent[key], path).takeUnless(JsonPrimitive::isString)?.booleanOrNull ?: wrong(path)
private fun nullableString(parent: JsonObject, key: String, path: String): String? = when (val value = parent[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> if (value.isString) value.content else wrong(path)
    else -> wrong(path)
}
private fun stringField(parent: JsonObject, key: String, path: String): Rg03JsonField<String> = when (val value = parent[key]) {
    null -> Rg03JsonField.Omitted
    JsonNull -> Rg03JsonField.Null
    is JsonPrimitive -> if (value.isString) Rg03JsonField.Value(value.content) else wrong(path)
    else -> wrong(path)
}
private fun booleanField(parent: JsonObject, key: String, path: String): Rg03JsonField<Boolean> = when (val value = parent[key]) {
    null -> Rg03JsonField.Omitted
    JsonNull -> Rg03JsonField.Null
    is JsonPrimitive -> value.takeUnless(JsonPrimitive::isString)?.booleanOrNull?.let { Rg03JsonField.Value(it) } ?: wrong(path)
    else -> wrong(path)
}
private fun primitive(value: JsonElement?, path: String): JsonPrimitive =
    value as? JsonPrimitive ?: wrong(path)
private fun wrong(path: String): Nothing = throw Rg03DecodeFailure(
    Rg03RawJsonContractError(path, Rg03RawJsonContractErrorReason.WRONG_TYPE),
)
private fun invalid(path: String): Nothing = throw Rg03DecodeFailure(
    Rg03RawJsonContractError(path, Rg03RawJsonContractErrorReason.INVALID_VALUE),
)
private fun rg03Bad(path: String, reason: Rg03RawJsonContractErrorReason) =
    Rg03RawJsonDecodeResult.Invalid(Rg03RawJsonContractError(path, reason))
private class Rg03DecodeFailure(val error: Rg03RawJsonContractError) : RuntimeException()

private data class Rg03FrozenAccount(
    val kind: AccountKind,
    val ownedByUser: Boolean,
    val realAccount: Boolean,
)

private data class Rg03FrozenCategory(
    val parentId: String?,
    val postingAccountId: String?,
    val active: Boolean,
)

private val RG03_ACCOUNTS = mapOf(
    "asset-bank-a" to Rg03FrozenAccount(AccountKind.ASSET, ownedByUser = true, realAccount = true),
    "asset-wallet-b" to Rg03FrozenAccount(AccountKind.ASSET, ownedByUser = true, realAccount = true),
    "liability-credit-c" to Rg03FrozenAccount(AccountKind.LIABILITY, ownedByUser = true, realAccount = true),
    "asset-external-x" to Rg03FrozenAccount(AccountKind.ASSET, ownedByUser = false, realAccount = true),
    "equity-opening-a" to Rg03FrozenAccount(AccountKind.EQUITY, ownedByUser = false, realAccount = false),
    "expense-account-transfer-fee" to Rg03FrozenAccount(AccountKind.EXPENSE, ownedByUser = false, realAccount = false),
)

private val RG03_CATEGORIES = mapOf(
    "expense-category-financial" to Rg03FrozenCategory(parentId = null, postingAccountId = null, active = true),
    "expense-category-transfer-fee" to Rg03FrozenCategory(
        parentId = "expense-category-financial",
        postingAccountId = "expense-account-transfer-fee",
        active = true,
    ),
)

private val RG03_LIFECYCLE_OPERATION_IDS = listOf(
    "import-complete-source",
    "confirm-import-candidate",
    "merge-mirror-evidence",
)

private val ROOT_FIELDS = setOf("schema_version", "case", "catalog", "opening", "manual_create", "import_lifecycle", "unknown_one_sided_debit", "invalid_manual_inputs", "idempotency", "forbidden_side_effects", "out_of_scope")
private val CASE_FIELDS = setOf("id", "level", "rule_version", "timezone", "currency", "precision", "ledger_id", "scope")
private val MANUAL_FIELDS = setOf("independent_baseline", "confirmation", "candidate", "request", "expected")
private val INPUT_FIELDS = setOf("request_id", "kind", "occurred_at", "source_account_id", "destination_account_id", "source_debit_amount", "destination_credit_amount", "fee_amount", "currency", "source_currency", "destination_currency", "fee_category_id")
private val MANUAL_OVERRIDE_FIELDS = setOf(
    "source_account_id", "destination_account_id", "source_debit_amount", "destination_credit_amount",
    "fee_amount", "currency", "source_currency", "destination_currency",
)
private val SOURCE_COMMON_FIELDS = setOf(
    "id", "evidence_id", "observed_at", "source_account_id", "currency", "completeness",
)
private val COMPLETE_SOURCE_FIELDS = SOURCE_COMMON_FIELDS + setOf(
    "destination_account_id", "source_debit_amount", "destination_credit_amount", "fee_amount",
)
private val INCOMPLETE_SOURCE_FIELDS = SOURCE_COMMON_FIELDS + setOf("debit_amount", "destination_account_id")
private val IDEMPOTENCY_FIELDS = setOf("repeated_manual_request_id", "repeated_source_request_id", "repeated_confirmation_request_id", "repeated_mirror_request_id", "expected")
private val FORBIDDEN_SIDE_EFFECTS = setOf(
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

private val OPENING_EXPECTED_FIELDS = setOf("transactions", "expected_balances")
private val OPENING_TRANSACTION_FIELDS = setOf("id", "occurred_at", "effective", "postings")
private val REQUIRED_POSTING_FIELDS = setOf("id", "account_id", "amount", "currency")
private val POSTING_FIELDS = REQUIRED_POSTING_FIELDS + "category_id"
private val TRANSFER_TRANSACTION_FIELDS = setOf(
    "id", "current_version_id", "posting_set_id", "occurred_at", "effective", "provenance", "postings",
)
private val MANUAL_PROVENANCE_FIELDS = setOf("kind", "confirmation_ref")
private val IMPORT_PROVENANCE_FIELDS = setOf("kind", "candidate_id", "confirmation_ref", "source_refs")
private val SOURCE_CANDIDATE_PROVENANCE_FIELDS = setOf("rule", "rule_version")
private val FULL_STATISTICS_FIELDS = setOf(
    "day", "month", "day_consumption", "month_consumption", "day_cash_outflow", "month_cash_outflow",
    "day_income", "month_income", "principal_consumption", "principal_external_cash_flow",
    "net_worth_change", "budget",
)
private val ZERO_STATISTICS_FIELDS = setOf("consumption", "cash_outflow", "income", "net_worth_change")
private val EVIDENCE_LINK_FIELDS = setOf("id", "evidence_id", "posting_id", "status")
private val MANUAL_EXPECTED_FIELDS = setOf(
    "accepted", "transaction", "balances", "statistics", "reconciliation", "evidence_refs",
)
private val SOURCE_LIFECYCLE_EXPECTED_FIELDS = setOf("candidate", "new_candidate_count", "formal_effects")
private val COMPLETE_CANDIDATE_FIELDS = setOf(
    "id", "status", "kind", "confidence", "source_refs", "evidence_refs", "provenance", "requires_confirmation",
)
private val FORMAL_EFFECTS_FIELDS = setOf(
    "new_transaction_count", "new_posting_count", "funding_effect_count", "balance_changes", "balances",
    "statistics", "reconciliation_change_count",
)
private val CONFIRMATION_LIFECYCLE_EXPECTED_FIELDS = setOf(
    "candidate_status", "transaction", "balances", "statistics", "reconciliation", "evidence_refs",
    "evidence_links", "effective_transaction_count", "funding_effect_count",
)
private val MIRROR_LIFECYCLE_EXPECTED_FIELDS = setOf(
    "merged_into_transaction_id", "current_version_id", "effective_posting_set_id", "posting_ids", "candidate_id",
    "new_transaction_count", "new_posting_count", "new_version_count", "effective_transaction_count",
    "funding_effect_count", "balances", "statistics", "reconciliation", "evidence_refs", "source_refs",
    "evidence_links", "duplicate_income_count", "duplicate_statistics_effect_count",
)
private val INCOMPLETE_EXPECTED_FIELDS = setOf(
    "candidate", "new_transaction_count", "new_posting_count", "new_version_count", "balance_changes", "balances",
    "statistics", "reconciliation_change_count", "balancing_account_id", "suspense_posting_count",
)
private val INCOMPLETE_CANDIDATE_FIELDS = setOf(
    "id", "status", "source_refs", "evidence_refs", "requires_confirmation",
)
private val RETRY_EXPECTED_FIELDS = setOf(
    "returned_candidate_id", "candidate_status", "new_candidate_count", "new_transaction_count",
    "new_posting_count", "new_version_count", "reconciliation_change_count", "balance_changes", "balances",
    "statistics", "source_refs", "evidence_refs",
)
private val IDEMPOTENCY_EXPECTED_FIELDS = setOf(
    "manual_returned_transaction_id", "import_returned_candidate_id", "confirmation_returned_transaction_id",
    "mirror_merged_transaction_id", "new_candidate_count", "new_transaction_count", "new_posting_count",
    "new_version_count", "new_evidence_link_count", "funding_effect_count_per_flow", "manual_state", "import_state",
)
private val MANUAL_STATE_FIELDS = setOf(
    "transaction_id", "current_version_id", "posting_set_id", "balances", "statistics", "reconciliation",
    "source_refs", "evidence_refs", "evidence_links",
)
private val IMPORT_STATE_FIELDS = MANUAL_STATE_FIELDS + setOf("candidate_id", "candidate_status", "posting_ids")
private val INVALID_EXPECTED_FIELDS = setOf(
    "accepted", "field", "reason", "state_unchanged", "new_transaction_count", "new_posting_count",
    "new_version_count", "reconciliation_change_count",
)

private val ORACLE_FIELDS = setOf(
    "accepted", "account_id", "amount", "balance_changes", "balances", "balancing_account_id",
    "budget", "candidate", "candidate_id", "candidate_status", "cash_outflow", "category_id",
    "confidence", "confirmation_ref", "confirmation_returned_transaction_id", "consumption", "currency",
    "current_version_id", "day", "day_cash_outflow", "day_consumption", "day_income",
    "duplicate_income_count", "duplicate_statistics_effect_count", "effective", "effective_posting_set_id",
    "effective_transaction_count", "evidence_id", "evidence_links", "evidence_refs", "expected_balances",
    "field", "formal_effects", "funding_effect_count", "funding_effect_count_per_flow", "id",
    "import_returned_candidate_id", "import_state", "income", "kind", "manual_returned_transaction_id",
    "manual_state", "merged_into_transaction_id", "mirror_merged_transaction_id", "month",
    "month_cash_outflow", "month_consumption", "month_income", "net_worth_change", "new_candidate_count",
    "new_evidence_link_count", "new_posting_count", "new_transaction_count", "new_version_count",
    "occurred_at", "posting_id", "posting_ids", "posting_set_id", "postings", "principal_consumption",
    "principal_external_cash_flow", "provenance", "reason", "reconciliation", "reconciliation_change_count",
    "requires_confirmation", "returned_candidate_id", "rule", "rule_version", "source_refs", "state_unchanged",
    "statistics", "status", "suspense_posting_count", "transaction", "transaction_id", "transactions",
)
private val ORACLE_OBJECT_FIELDS = setOf(
    "candidate", "formal_effects", "import_state", "manual_state", "provenance", "statistics", "transaction",
)
private val ORACLE_DYNAMIC_STRING_MAPS = setOf(
    "balance_changes", "balances", "expected_balances", "reconciliation",
)
private val ORACLE_ARRAY_FIELDS = setOf(
    "evidence_links", "evidence_refs", "posting_ids", "postings", "requires_confirmation", "source_refs", "transactions",
)
private val ORACLE_STRING_ARRAY_FIELDS = setOf(
    "evidence_refs", "posting_ids", "requires_confirmation", "source_refs",
)
private val ORACLE_BOOLEAN_FIELDS = setOf("accepted", "effective", "state_unchanged")
private val ORACLE_INTEGER_FIELDS = setOf(
    "duplicate_income_count", "duplicate_statistics_effect_count", "effective_transaction_count",
    "funding_effect_count", "funding_effect_count_per_flow", "new_candidate_count", "new_evidence_link_count",
    "new_posting_count", "new_transaction_count", "new_version_count", "reconciliation_change_count",
    "rule_version", "suspense_posting_count",
)

private sealed interface Rg03JsonScanIssue {
    data class DuplicateKey(val path: String) : Rg03JsonScanIssue
    data class ResourceLimit(val path: String) : Rg03JsonScanIssue
}

private class Rg03DuplicateKeyScanner(private val text: String) {
    private var index = 0
    fun scan(): Rg03JsonScanIssue? = try {
        skipWhitespace(); value("$", 0); skipWhitespace()
        if (index != text.length) throw IllegalArgumentException()
        null
    } catch (failure: DuplicateKey) {
        Rg03JsonScanIssue.DuplicateKey(failure.path)
    } catch (failure: ResourceLimit) {
        Rg03JsonScanIssue.ResourceLimit(failure.path)
    } catch (_: Exception) {
        null
    }
    private fun value(path: String, parentDepth: Int) {
        skipWhitespace()
        when (text.getOrNull(index)) {
            '{' -> obj(path, parentDepth + 1)
            '[' -> arr(path, parentDepth + 1)
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            '-', in '0'..'9' -> number()
            else -> throw IllegalArgumentException()
        }
    }
    private fun obj(path: String, depth: Int) {
        checkDepth(path, depth); index++
        val keys = mutableSetOf<String>(); skipWhitespace()
        if (take('}')) return
        while (true) {
            skipWhitespace(); val key = string()
            if (!keys.add(key)) throw DuplicateKey("$path.$key")
            skipWhitespace(); expect(':'); value("$path.$key", depth); skipWhitespace()
            if (take('}')) return
            expect(',')
        }
    }
    private fun arr(path: String, depth: Int) {
        checkDepth(path, depth); index++; skipWhitespace()
        if (take(']')) return
        var item = 0
        while (true) {
            value("$path[$item]", depth); item++; skipWhitespace()
            if (take(']')) return
            expect(',')
        }
    }
    private fun checkDepth(path: String, depth: Int) { if (depth > 64) throw ResourceLimit(path) }
    private fun string(): String {
        expect('"'); val start = index; var escaped = false
        while (index < text.length) {
            val character = text[index++]
            if (character == '"' && !escaped) {
                return try {
                    Json.parseToJsonElement("\"${text.substring(start, index - 1)}\"").jsonPrimitive.content
                } catch (_: Exception) { throw IllegalArgumentException() }
            }
            escaped = character == '\\' && !escaped
        }
        throw IllegalArgumentException()
    }
    private fun literal(value: String) { if (!text.startsWith(value, index)) throw IllegalArgumentException(); index += value.length }
    private fun number() {
        if (text[index] == '-') index++
        while (text.getOrNull(index)?.isDigit() == true) index++
        if (take('.')) while (text.getOrNull(index)?.isDigit() == true) index++
        if (text.getOrNull(index) in listOf('e', 'E')) {
            index++
            if (text.getOrNull(index) in listOf('+', '-')) index++
            while (text.getOrNull(index)?.isDigit() == true) index++
        }
    }
    private fun skipWhitespace() { while (text.getOrNull(index)?.isWhitespace() == true) index++ }
    private fun expect(character: Char) { if (!take(character)) throw IllegalArgumentException() }
    private fun take(character: Char): Boolean = if (text.getOrNull(index) == character) { index++; true } else false
    private class DuplicateKey(val path: String) : RuntimeException()
    private class ResourceLimit(val path: String) : RuntimeException()
}
