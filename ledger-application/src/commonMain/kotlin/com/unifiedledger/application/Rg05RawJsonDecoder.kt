package com.unifiedledger.application

import com.unifiedledger.domain.*
import kotlinx.serialization.json.*
import kotlin.time.Instant

enum class Rg05RawJsonContractErrorReason { MALFORMED_JSON, DUPLICATE_KEY, RESOURCE_LIMIT, UNKNOWN_FIELD, WRONG_TYPE, INVALID_VALUE }
data class Rg05RawJsonContractError(val fieldPath: String, val reason: Rg05RawJsonContractErrorReason)
sealed interface Rg05RawJsonDecodeResult {
    data class Success(val value: Rg05RawJsonCase) : Rg05RawJsonDecodeResult
    data class Invalid(val error: Rg05RawJsonContractError) : Rg05RawJsonDecodeResult
}

private val rg05Json = Json { ignoreUnknownKeys = false }

fun decodeRg05RawJson(raw: String): Rg05RawJsonDecodeResult {
    strictJsonPreflight(raw, duplicateKeyPath = true)?.let { issue ->
        return Rg05RawJsonDecodeResult.Invalid(Rg05RawJsonContractError(issue.path, when (issue.reason) {
            StrictJsonPreflightReason.RESOURCE_LIMIT -> Rg05RawJsonContractErrorReason.RESOURCE_LIMIT
            StrictJsonPreflightReason.DUPLICATE_KEY -> Rg05RawJsonContractErrorReason.DUPLICATE_KEY
            StrictJsonPreflightReason.MALFORMED_JSON -> Rg05RawJsonContractErrorReason.MALFORMED_JSON
            StrictJsonPreflightReason.OBJECT_ROOT_REQUIRED -> Rg05RawJsonContractErrorReason.WRONG_TYPE
        }))
    }
    val root = try { rg05Json.parseToJsonElement(raw).jsonObject } catch (_: Exception) {
        return Rg05RawJsonDecodeResult.Invalid(Rg05RawJsonContractError("$", Rg05RawJsonContractErrorReason.MALFORMED_JSON))
    }
    return try {
        root.closed("$", setOf("schema_version", "case", "catalog", "opening", "manual_path", "import_path", "allocation_failures", "idempotency", "invalid_manual_inputs", "forbidden_side_effects", "out_of_scope"))
        root.int("schema_version", "$.schema_version").takeIf { it == 1 } ?: fail("$.schema_version")
        val case = root.obj("case", "$.case")
        case.closed("$.case", setOf("id", "level", "rule_version", "timezone", "currency", "precision", "ledger_id"))
        if (case.string("id", "$.case.id") != "RG-05" || case.string("level", "$.case.level") != "core_required" || case.int("rule_version", "$.case.rule_version") != 1) fail("$.case")
        val timezone = case.string("timezone", "$.case.timezone")
        val currency = CurrencyUnit(case.string("currency", "$.case.currency"), case.int("precision", "$.case.precision"))
        val ledgerId = LedgerId(case.string("ledger_id", "$.case.ledger_id"))
        if (timezone != "Asia/Shanghai" || currency != CurrencyUnit("CNY", 2) || ledgerId != LedgerId("ledger-a")) fail("$.case")
        val catalog = decodeCatalog(root.obj("catalog", "$.catalog"), ledgerId, currency)
        val manual = root.obj("manual_path", "$.manual_path")
        manual.closed("$.manual_path", setOf("confirmation", "candidate", "input", "expected"))
        val confirmation = manual.obj("confirmation", "$.manual_path.confirmation")
        confirmation.closed("$.manual_path.confirmation", setOf("mode", "confirmed"))
        if (confirmation.string("mode", "$.manual_path.confirmation.mode") != "explicit_manual_save") fail("$.manual_path.confirmation.mode")
        val input = manual.obj("input", "$.manual_path.input")
        input.closed("$.manual_path.input", setOf("request_id", "kind", "payment_at", "total_amount", "currency", "funding_account_id", "items"))
        if (input.string("kind", "$.manual_path.input.kind") != "merged_payment") fail("$.manual_path.input.kind")
        val items = input.array("items", "$.manual_path.input.items")
        if (items.size != 2) fail("$.manual_path.input.items")
        val manualInput = Rg05ManualInput(
            Rg05Field.Value(input.string("request_id", "$.manual_path.input.request_id")),
            Rg05Field.Value(input.string("payment_at", "$.manual_path.input.payment_at")),
            Rg05Field.Value(input.string("total_amount", "$.manual_path.input.total_amount")),
            Rg05Field.Value(input.string("currency", "$.manual_path.input.currency")),
            Rg05Field.Value(input.string("funding_account_id", "$.manual_path.input.funding_account_id")),
            items.mapIndexed { index, element ->
                val item = element.objAt("$.manual_path.input.items[$index]")
                item.closed("$.manual_path.input.items[$index]", setOf("id", "amount", "currency", "category_id", "details", "source_observed_at"))
                Rg05ItemInput(
                    Rg05Field.Value(item.string("id", "$.manual_path.input.items[$index].id")),
                    Rg05Field.Value(item.string("amount", "$.manual_path.input.items[$index].amount")),
                    Rg05Field.Value(item.string("currency", "$.manual_path.input.items[$index].currency")),
                    when (val category = item["category_id"]) {
                        null, JsonNull -> Rg05Field.Null
                        else -> Rg05Field.Value(category.stringAt("$.manual_path.input.items[$index].category_id"))
                    },
                    Rg05Field.Value(item.string("details", "$.manual_path.input.items[$index].details")),
                    Rg05Field.Value(item.string("source_observed_at", "$.manual_path.input.items[$index].source_observed_at")),
                )
            },
            Rg05Field.Value(confirmation.boolean("confirmed", "$.manual_path.confirmation.confirmed")),
        )
        val importOperations = decodeRg05ImportOperations(root["import_path"], ledgerId, currency)
        Rg05RawJsonDecodeResult.Success(Rg05RawJsonCase(ledgerId, currency, timezone, catalog, manualInput, importOperations))
    } catch (failure: Rg05DecodeFailure) {
        Rg05RawJsonDecodeResult.Invalid(Rg05RawJsonContractError(failure.path, failure.reason))
    }
}

private fun decodeRg05ImportOperations(value: JsonElement?, ledgerId: LedgerId, currency: CurrencyUnit): List<Rg05PreparedOperation> {
    if (value == null) return emptyList()
    val importPath = value.objAt("$.import_path")
    importPath.closed("$.import_path", setOf("ordered_operations"))
    val operations = importPath.array("ordered_operations", "$.import_path.ordered_operations")
    if (operations.size != 3) fail("$.import_path.ordered_operations")

    val ingest = operations[0].objAt("$.import_path.ordered_operations[0]")
    ingest.closed("$.import_path.ordered_operations[0]", setOf("id", "input", "expected"))
    val ingestInput = ingest.obj("input", "$.import_path.ordered_operations[0].input")
    ingestInput.closed("$.import_path.ordered_operations[0].input", setOf("bank_fact", "item_facts"))
    val bank = ingestInput.obj("bank_fact", "$.import_path.ordered_operations[0].input.bank_fact")
    bank.closed("$.import_path.ordered_operations[0].input.bank_fact", setOf("source_id", "evidence_id", "observed_at", "details", "amount", "currency"))
    val bankCurrency = requireRg05Currency(bank.string("currency", "$.import_path.ordered_operations[0].input.bank_fact.currency"), currency, "$.import_path.ordered_operations[0].input.bank_fact.currency")
    val bankFact = Rg05BankFact(
        bank.string("source_id", "$.import_path.ordered_operations[0].input.bank_fact.source_id"),
        bank.string("evidence_id", "$.import_path.ordered_operations[0].input.bank_fact.evidence_id"),
        bank.instant("observed_at", "$.import_path.ordered_operations[0].input.bank_fact.observed_at"),
        bank.string("observed_at", "$.import_path.ordered_operations[0].input.bank_fact.observed_at"),
        bank.string("details", "$.import_path.ordered_operations[0].input.bank_fact.details"),
        bank.money("amount", bankCurrency, "$.import_path.ordered_operations[0].input.bank_fact.amount"),
    )
    val rawItems = ingestInput.array("item_facts", "$.import_path.ordered_operations[0].input.item_facts")
    if (rawItems.size != 2) fail("$.import_path.ordered_operations[0].input.item_facts")
    val itemFacts = rawItems.mapIndexed { index, element ->
        val path = "$.import_path.ordered_operations[0].input.item_facts[$index]"
        val item = element.objAt(path)
        item.closed(path, setOf("item_id", "source_id", "evidence_id", "evidence_kind", "observed_at", "details", "amount", "currency", "suggested_category_id"))
        val itemCurrency = requireRg05Currency(item.string("currency", "$path.currency"), currency, "$path.currency")
        val kind = when (item.string("evidence_kind", "$path.evidence_kind")) {
            "item_receipt" -> Rg05EvidenceKind.ITEM_RECEIPT
            "item_summary" -> Rg05EvidenceKind.ITEM_SUMMARY
            else -> fail("$path.evidence_kind")
        }
        Rg05ItemFact(item.string("item_id", "$path.item_id"), item.string("source_id", "$path.source_id"), item.string("evidence_id", "$path.evidence_id"), kind, item.instant("observed_at", "$path.observed_at"), item.string("observed_at", "$path.observed_at"), item.string("details", "$path.details"), item.money("amount", itemCurrency, "$path.amount"), CategoryId(item.string("suggested_category_id", "$path.suggested_category_id")))
    }
    val candidateId = ingest.obj("expected", "$.import_path.ordered_operations[0].expected").obj("candidate", "$.import_path.ordered_operations[0].expected.candidate").string("id", "$.import_path.ordered_operations[0].expected.candidate.id")
    val ingestOperation = Rg05PreparedOperation.Ingest(Rg05IngestSnapshot(ledgerId, RequestId(bankFact.sourceId), bankFact, itemFacts, candidateId, "pending-$candidateId"))

    val confirm = operations[1].objAt("$.import_path.ordered_operations[1]")
    confirm.closed("$.import_path.ordered_operations[1]", setOf("id", "input", "expected"))
    val confirmInput = confirm.obj("input", "$.import_path.ordered_operations[1].input")
    confirmInput.closed("$.import_path.ordered_operations[1].input", setOf("request_id", "candidate_id", "confirmed", "create_formal_transaction", "funding_account_id", "payment_at", "common_statistics_at", "items"))
    if (!confirmInput.boolean("confirmed", "$.import_path.ordered_operations[1].input.confirmed") || !confirmInput.boolean("create_formal_transaction", "$.import_path.ordered_operations[1].input.create_formal_transaction")) fail("$.import_path.ordered_operations[1].input.confirmed")
    val allocations = confirmInput.array("items", "$.import_path.ordered_operations[1].input.items").mapIndexed { index, element ->
        val path = "$.import_path.ordered_operations[1].input.items[$index]"
        val item = element.objAt(path)
        item.closed(path, setOf("item_id", "category_id", "allocation_amount", "currency"))
        val itemCurrency = requireRg05Currency(item.string("currency", "$path.currency"), currency, "$path.currency")
        Rg05ConfirmAllocation(item.string("item_id", "$path.item_id"), CategoryId(item.string("category_id", "$path.category_id")), item.money("allocation_amount", itemCurrency, "$path.allocation_amount"))
    }
    if (allocations.size != 2) fail("$.import_path.ordered_operations[1].input.items")
    val expected = confirm.obj("expected", "$.import_path.ordered_operations[1].expected")
    val transaction = expected.obj("transaction", "$.import_path.ordered_operations[1].expected.transaction")
    val postings = transaction.array("postings", "$.import_path.ordered_operations[1].expected.transaction.postings")
    if (postings.size != 3) fail("$.import_path.ordered_operations[1].expected.transaction.postings")
    val postingIds = postings.mapIndexed { index, element -> element.objAt("$.import_path.ordered_operations[1].expected.transaction.postings[$index]").string("id", "$.import_path.ordered_operations[1].expected.transaction.postings[$index].id") }
    val consumptionRows = expected.array("consumption_records", "$.import_path.ordered_operations[1].expected.consumption_records")
    val group = expected.obj("association_group", "$.import_path.ordered_operations[1].expected.association_group")
    val allocationRows = group.array("item_allocations", "$.import_path.ordered_operations[1].expected.association_group.item_allocations")
    if (consumptionRows.size != 2 || allocationRows.size != 2) fail("$.import_path.ordered_operations[1].expected.association_group")
    val consumptionIds = allocations.indices.associate { index -> allocations[index].itemId to consumptionRows[index].objAt("consumption[$index]").string("id", "consumption[$index].id") }
    val allocationIds = allocations.indices.associate { index -> allocations[index].itemId to allocationRows[index].objAt("allocation[$index]").string("id", "allocation[$index].id") }
    val bankLink = expected.array("financial_evidence_links", "$.import_path.ordered_operations[1].expected.financial_evidence_links").single().objAt("bank_link")
    val itemLinks = expected.array("item_evidence_links", "$.import_path.ordered_operations[1].expected.item_evidence_links")
    val itemLinkIds = itemLinks.associate { link ->
        val row = link.objAt("item_link")
        val allocationId = row.string("item_allocation_id", "item_link.item_allocation_id")
        allocationIds.entries.first { it.value == allocationId }.key to row.string("id", "item_link.id")
    }
    val requestId = confirmInput.string("request_id", "$.import_path.ordered_operations[1].input.request_id")
    val paymentText = confirmInput.string("payment_at", "$.import_path.ordered_operations[1].input.payment_at")
    val statisticsText = confirmInput.string("common_statistics_at", "$.import_path.ordered_operations[1].input.common_statistics_at")
    val confirmOperation = Rg05PreparedOperation.Confirm(
        Rg05ConfirmSnapshot(ledgerId, RequestId(requestId), confirmInput.string("candidate_id", "$.import_path.ordered_operations[1].input.candidate_id"), AccountId(confirmInput.string("funding_account_id", "$.import_path.ordered_operations[1].input.funding_account_id")), parseRg05Instant(paymentText, "$.import_path.ordered_operations[1].input.payment_at"), paymentText, parseRg05Instant(statisticsText, "$.import_path.ordered_operations[1].input.common_statistics_at"), statisticsText, allocations, true, "confirmed-$candidateId"),
        MergedPaymentExpenseIds(TransactionId(transaction.string("id", "transaction.id")), TransactionVersionId(transaction.string("current_version_id", "transaction.current_version_id")), PostingSetId(transaction.string("posting_set_id", "transaction.posting_set_id")), postingIds.take(2).map(::PostingId), PostingId(postingIds.last())),
        group.string("id", "association_group.id"), "confirmation-$requestId", "reconciliation-${postingIds.last()}", bankLink.string("id", "bank_link.id"), itemLinkIds, consumptionIds, allocationIds,
    )

    val receipt = operations[2].objAt("$.import_path.ordered_operations[2]")
    receipt.closed("$.import_path.ordered_operations[2]", setOf("id", "input", "expected"))
    val receiptInput = receipt.obj("input", "$.import_path.ordered_operations[2].input")
    receiptInput.closed("$.import_path.ordered_operations[2].input", setOf("source_id", "evidence_id", "observed_at", "details", "amount", "currency", "item_allocation_id"))
    val receiptCurrency = requireRg05Currency(receiptInput.string("currency", "receipt.input.currency"), currency, "receipt.input.currency")
    val receiptExpectedLinks = receipt.obj("expected", "receipt.expected").array("item_evidence_links", "receipt.expected.item_evidence_links")
    val receiptEvidenceId = receiptInput.string("evidence_id", "receipt.input.evidence_id")
    val receiptLink = receiptExpectedLinks.map { it.objAt("receipt.link") }.firstOrNull { it.string("evidence_id", "receipt.link.evidence_id") == receiptEvidenceId } ?: fail("receipt.expected.item_evidence_links")
    val observedText = receiptInput.string("observed_at", "receipt.input.observed_at")
    val receiptOperation = Rg05PreparedOperation.Receipt(Rg05ReceiptSnapshot(ledgerId, RequestId(receiptEvidenceId), receiptInput.string("source_id", "receipt.input.source_id"), receiptEvidenceId, receiptInput.string("item_allocation_id", "receipt.input.item_allocation_id"), receiptLink.string("id", "receipt.link.id"), parseRg05Instant(observedText, "receipt.input.observed_at"), observedText, receiptInput.string("details", "receipt.input.details"), receiptInput.money("amount", receiptCurrency, "receipt.input.amount")))
    return listOf(ingestOperation, confirmOperation, receiptOperation)
}

private fun requireRg05Currency(code: String, currency: CurrencyUnit, path: String): CurrencyUnit = currency.takeIf { it.code == code } ?: fail(path)
private fun JsonObject.money(name: String, currency: CurrencyUnit, path: String): Money = exactMoney(string(name, path), currency) ?: fail(path)
private fun JsonObject.instant(name: String, path: String): Instant = parseRg05Instant(string(name, path), path)
private fun parseRg05Instant(value: String, path: String): Instant = try { Instant.parse(value) } catch (_: IllegalArgumentException) { fail(path) }

private fun decodeCatalog(value: JsonObject, ledgerId: LedgerId, currency: CurrencyUnit): LedgerCatalog {
    value.closed("$.catalog", setOf("accounts", "categories"))
    val accounts = value.array("accounts", "$.catalog.accounts").mapIndexed { i, element ->
        val path = "$.catalog.accounts[$i]"; val item = element.objAt(path)
        item.closed(path, setOf("id", "name", "kind", "real_account", "owned_by_user"))
        Account(AccountId(item.string("id", "$path.id")), ledgerId, AccountKind.valueOf(item.string("kind", "$path.kind").uppercase()), currency, item.boolean("owned_by_user", "$path.owned_by_user"), item.boolean("real_account", "$path.real_account"))
    }
    val categories = value.array("categories", "$.catalog.categories").mapIndexed { i, element ->
        val path = "$.catalog.categories[$i]"; val item = element.objAt(path)
        item.closed(path, setOf("id", "name", "kind", "parent_id", "posting_account_id", "active"))
        Category(CategoryId(item.string("id", "$path.id")), ledgerId, item.nullableString("parent_id", "$path.parent_id")?.let(::CategoryId), item.nullableString("posting_account_id", "$path.posting_account_id")?.let(::AccountId), item.boolean("active", "$path.active"), CategoryKind.valueOf(item.string("kind", "$path.kind").uppercase()))
    }
    return when (val result = LedgerCatalog.create(accounts, categories)) { is DomainResult.Success -> result.value; is DomainResult.Failure -> fail("$.catalog") }
}

private fun JsonObject.closed(path: String, allowed: Set<String>) { keys.firstOrNull { it !in allowed }?.let { fail("$path.$it", Rg05RawJsonContractErrorReason.UNKNOWN_FIELD) } }
private fun JsonObject.obj(name: String, path: String) = this[name]?.objAt(path) ?: fail(path, Rg05RawJsonContractErrorReason.WRONG_TYPE)
private fun JsonObject.objAt(path: String) = this
private fun JsonElement.objAt(path: String) = this as? JsonObject ?: fail(path, Rg05RawJsonContractErrorReason.WRONG_TYPE)
private fun JsonObject.array(name: String, path: String) = this[name]?.let { it as? JsonArray ?: fail(path, Rg05RawJsonContractErrorReason.WRONG_TYPE) } ?: fail(path, Rg05RawJsonContractErrorReason.WRONG_TYPE)
private fun JsonObject.string(name: String, path: String) = this[name]?.stringAt(path) ?: fail(path, Rg05RawJsonContractErrorReason.WRONG_TYPE)
private fun JsonElement.stringAt(path: String) = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail(path, Rg05RawJsonContractErrorReason.WRONG_TYPE)
private fun JsonObject.nullableString(name: String, path: String) = when (val value = this[name]) { null -> fail(path, Rg05RawJsonContractErrorReason.WRONG_TYPE); JsonNull -> null; else -> value.stringAt(path) }
private fun JsonObject.boolean(name: String, path: String) = (this[name] as? JsonPrimitive)?.booleanOrNull ?: fail(path, Rg05RawJsonContractErrorReason.WRONG_TYPE)
private fun JsonObject.int(name: String, path: String) = (this[name] as? JsonPrimitive)?.intOrNull ?: fail(path, Rg05RawJsonContractErrorReason.WRONG_TYPE)
private data class Rg05DecodeFailure(val path: String, val reason: Rg05RawJsonContractErrorReason) : RuntimeException()
private fun fail(path: String, reason: Rg05RawJsonContractErrorReason = Rg05RawJsonContractErrorReason.INVALID_VALUE): Nothing = throw Rg05DecodeFailure(path, reason)
