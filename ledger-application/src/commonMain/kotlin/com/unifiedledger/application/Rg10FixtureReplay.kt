package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSet
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.StoredValueConfig
import com.unifiedledger.domain.StoredValueLot
import com.unifiedledger.domain.StoredValueLotId
import com.unifiedledger.domain.Transaction
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersion
import com.unifiedledger.domain.TransactionVersionId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

data class Rg10FixtureOperation(
    val id: String,
    val sourcePath: String,
    val expectedStatus: String,
    val operation: Rg10Operation,
    val baselineStateId: String? = null,
    val resultStateId: String? = null,
    val retryOf: String? = null,
    val expectedReason: String? = null,
)

data class Rg10FixtureReplaySummary(
    val operations: List<Rg10FixtureOperation>,
    val accepted: Int,
    val noChange: Int,
    val rejected: Int,
)

data class Rg10FixtureCase(
    val ledgerId: LedgerId,
    val catalog: LedgerCatalog,
    val openingTransactions: List<Rg10FormalTransactionRecord>,
    val operations: List<Rg10FixtureOperation>,
    val allOperations: List<Rg10FixtureOperation> = operations,
    /** Synthetic baseline runtimes keyed by canonical state id (branching operations only). */
    val baselines: Map<String, Rg10Runtime> = emptyMap(),
)

data class Rg10FixtureSource(
    val id: String,
    val sourceType: String,
    val observedAtText: String,
    val accountId: String? = null,
    val amountText: String? = null,
    val lotId: String? = null,
    val immutablePayloadDigest: String,
)

data class Rg10FixtureInputs(
    val ids: Map<String, Map<String, String?>>,
    val sources: Map<String, Rg10FixtureSource>,
    val times: Map<String, Map<String, String>>,
    val categories: Map<String, Map<String, String>>,
    val lotFacts: Map<String, Map<String, String>>,
)

fun parseRg10FixtureInputs(raw: String): Rg10FixtureInputs {
    val root = Json.parseToJsonElement(raw).jsonObject
    val ids = root.getValue("ids").jsonObject.mapValues { (_, value) ->
        value.jsonObject.mapValues { (_, field) ->
            field.takeUnless { it is JsonNull }?.jsonPrimitive?.content
        }
    }
    val sources = root.getValue("sources").jsonArray.associate { element ->
        val source = element.jsonObject
        val id = source.string("id")
        id to Rg10FixtureSource(
            id = id,
            sourceType = source.string("source_type"),
            observedAtText = source.string("observed_at"),
            accountId = source.optionalString("account_id"),
            amountText = source.optionalString("amount"),
            lotId = source.optionalString("lot_id"),
            immutablePayloadDigest = source.string("immutable_payload_digest"),
        )
    }
    val times = root.getValue("times").jsonObject.mapValues { (_, value) ->
        value.jsonObject.mapValues { (_, field) -> field.jsonPrimitive.content }
    }
    val categories = root.getValue("categories").jsonObject.mapValues { (_, value) ->
        value.jsonObject.mapValues { (_, field) -> field.jsonPrimitive.content }
    }
    val lotFacts = root.getValue("lot_facts").jsonObject.mapValues { (_, value) ->
        value.jsonObject.mapValues { (_, field) -> field.jsonPrimitive.content }
    }
    return Rg10FixtureInputs(ids, sources, times, categories, lotFacts)
}

fun adaptRg10Fixture(raw: String, inputs: Rg10FixtureInputs): Rg10FixtureCase {
    val fixture = Json.parseToJsonElement(raw).jsonObject
    val case = fixture.getValue("case").jsonObject
    val ledgerId = LedgerId(case.string("ledger_id"))
    val catalog = buildCatalog(fixture.getValue("catalog").jsonObject, ledgerId, inputs)
    val opening = openingTransaction(fixture.getValue("opening").jsonObject, ledgerId)
    val context = Rg10FixtureContext(inputs, ledgerId, listOf(opening))
    val operations = adaptMainPath(fixture.getValue("main_path").jsonObject, context)
    val nonRetryOperations = buildList<Rg10FixtureOperation> {
        addAll(operations)
        addAll(adaptReconciliationPath(fixture.getValue("reconciliation_path").jsonObject, context))
        addAll(adaptImportPath(fixture.getValue("import_path").jsonObject, context))
        addAll(adaptSecondaryCases(fixture.getValue("secondary_cases").jsonObject, context))
        addAll(adaptInvalidInputs(fixture.getValue("invalid_inputs").jsonArray, context))
    }
    val allOperations = nonRetryOperations + adaptRetries(
        fixture.getValue("idempotency").jsonObject,
        nonRetryOperations,
    )
    val baselines = buildMap {
        putAll(adaptMerchantAllocationBaseline(fixture.getValue("secondary_cases").jsonObject, catalog))
    }
    return Rg10FixtureCase(
        ledgerId = ledgerId,
        catalog = catalog,
        openingTransactions = listOf(opening),
        operations = operations,
        allOperations = allOperations,
        baselines = baselines,
    )
}

fun replayRg10Fixture(raw: String, inputs: Rg10FixtureInputs): Rg10FixtureReplaySummary {
    val case = adaptRg10Fixture(raw, inputs)
    return Rg10FixtureReplaySummary(
        operations = case.allOperations,
        accepted = case.allOperations.count { it.expectedStatus == "accepted" },
        noChange = case.allOperations.count { it.expectedStatus == "no_change" },
        rejected = case.allOperations.count { it.expectedStatus == "rejected" },
    )
}

private class Rg10FixtureContext(
    val inputs: Rg10FixtureInputs,
    val ledgerId: LedgerId,
    val openingTransactions: List<Rg10FormalTransactionRecord>,
) {
    fun id(identity: String, field: String): String =
        inputs.ids.getValue(identity).getValue(field)
            ?: error("RG-10 runtime input ID is null: $identity.$field")

    fun optionalId(identity: String, field: String): String? =
        inputs.ids[identity]?.get(field)

    fun source(sourceId: String): Rg10FixtureSource =
        inputs.sources.getValue(sourceId)

    fun time(requestId: String, field: String): String? =
        inputs.times[requestId]?.get(field)

    fun lotFact(lotId: String, field: String): String? =
        inputs.lotFacts[lotId]?.get(field)

    fun categoryParent(categoryId: String): String? =
        inputs.categories[categoryId]?.get("parent_id")

    fun currency(code: String = "CNY") = CurrencyUnit(code, 2)
}

private fun adaptMainPath(
    path: JsonObject,
    context: Rg10FixtureContext,
): List<Rg10FixtureOperation> {
    val names = listOf("recharge", "spend", "expiry_reminder", "expiry_confirmation")
    return names.map { name ->
        val operation = path.getValue(name).jsonObject
        val input = operation.getValue("input").jsonObject
        val typed = when (name) {
            "recharge" -> adaptRecharge(input, context)
            "spend" -> adaptSpend(input, context)
            "expiry_reminder" -> adaptReminder(input, context)
            "expiry_confirmation" -> adaptExpiryLoss(input, context)
            else -> error("unsupported RG-10 main path operation $name")
        }
        fixtureOperation(operation, "$.main_path.$name", typed)
    }
}

private fun adaptRecharge(
    input: JsonObject,
    context: Rg10FixtureContext,
): Rg10Operation.ConfirmStoredValueRecharge {
    val requestId = input.string("request_id")
    val lotId = context.id(requestId, "lot_id")
    val expiresAtText = context.lotFact(lotId, "expires_at")
        ?: error("RG-10 recharge lot fact expires_at missing for $lotId")
    val currency = context.currency(input.string("currency"))
    return Rg10Operation.ConfirmStoredValueRecharge(
        ledgerId = context.ledgerId,
        input = Rg10ConfirmRechargeInput(
            requestId = RequestId(requestId),
            model = input.string("model"),
            paymentAccountId = AccountId(input.string("payment_account_id")),
            storedValueAccountId = AccountId(input.string("stored_value_account_id")),
            paidAmount = input.money("paid_amount", currency),
            creditedAmount = input.money("credited_amount", currency),
            bonusAmount = input.money("bonus_amount", currency),
            currency = currency,
            occurredAt = input.instant("occurred_at"),
            occurredAtText = input.string("occurred_at"),
            createdAt = input.instant("created_at"),
            createdAtText = input.string("created_at"),
            explicitConfirmation = input.boolean("explicit_confirmation"),
            confirmsModel = input.boolean("confirms_model"),
            confirmsPaymentAccount = input.boolean("confirms_payment_account"),
            confirmsStoredValueAccount = input.boolean("confirms_stored_value_account"),
            confirmsPaidAmount = input.boolean("confirms_paid_amount"),
            confirmsCreditedAmount = input.boolean("confirms_credited_amount"),
            confirmsBonusAmount = input.boolean("confirms_bonus_amount"),
            confirmsActualTime = input.boolean("confirms_actual_time"),
            confirmsLotFacts = input.boolean("confirms_lot_facts"),
            expiresAt = Instant.parse(expiresAtText),
            expiresAtText = expiresAtText,
            merchantId = context.lotFact(lotId, "merchant_id"),
            merchantCreditObservedAtText = context.time(requestId, "merchant_credit_observed_at"),
        ),
        ids = Rg10RechargeCommitIds(
            transactionId = TransactionId(context.id(requestId, "transaction_id")),
            versionId = TransactionVersionId(context.id(requestId, "version_id")),
            postingSetId = PostingSetId(context.id(requestId, "posting_set_id")),
            storedValuePostingId = PostingId(context.id(requestId, "stored_value_posting_id")),
            paymentPostingId = PostingId(context.id(requestId, "payment_posting_id")),
            bonusIncomePostingId = PostingId(context.id(requestId, "bonus_income_posting_id")),
            lotId = StoredValueLotId(lotId),
            lotHistoryId = context.id(requestId, "lot_history_id"),
            confirmationId = Rg10ConfirmationId(context.id(requestId, "confirmation_id")),
            bankSourceId = Rg10SourceRecordId(context.id(requestId, "bank_source_id")),
            merchantSourceId = Rg10SourceRecordId(context.id(requestId, "merchant_source_id")),
            bankEvidenceId = Rg10EvidenceId(context.id(requestId, "bank_evidence_id")),
            merchantEvidenceId = Rg10EvidenceId(context.id(requestId, "merchant_evidence_id")),
            bankLinkId = Rg10EvidenceLinkId(context.id(requestId, "bank_link_id")),
            merchantPostingLinkId = Rg10EvidenceLinkId(context.id(requestId, "merchant_posting_link_id")),
            merchantLotLinkId = Rg10EvidenceLinkId(context.id(requestId, "merchant_lot_link_id")),
            bonusLinkId = Rg10EvidenceLinkId(context.id(requestId, "bonus_link_id")),
        ),
    )
}

private fun adaptSpend(
    input: JsonObject,
    context: Rg10FixtureContext,
): Rg10Operation.ConfirmStoredValueSpend {
    val requestId = input.string("request_id")
    val currency = context.currency(input.string("currency"))
    return Rg10Operation.ConfirmStoredValueSpend(
        ledgerId = context.ledgerId,
        input = Rg10ConfirmSpendInput(
            requestId = RequestId(requestId),
            model = input.string("model"),
            behavior = input.string("behavior"),
            storedValueAccountId = AccountId(input.string("stored_value_account_id")),
            categoryId = CategoryId(input.string("category_id")),
            amount = input.money("amount", currency),
            currency = currency,
            occurredAt = input.instant("occurred_at"),
            occurredAtText = input.string("occurred_at"),
            createdAt = input.instant("created_at"),
            createdAtText = input.string("created_at"),
            explicitConfirmation = input.boolean("explicit_confirmation"),
            confirmsModel = input.boolean("confirms_model"),
            confirmsBehavior = input.boolean("confirms_behavior"),
            confirmsStoredValueAccount = input.boolean("confirms_stored_value_account"),
            confirmsAmount = input.boolean("confirms_amount"),
            confirmsActualTime = input.boolean("confirms_actual_time"),
            confirmsCategory = input.boolean("confirms_category"),
            merchantAllocationProvided = input.boolean("merchant_allocation_provided"),
            confirmsLotAllocation = input.boolean("confirms_lot_allocation"),
            allocations = input.allocations(currency),
        ),
        ids = Rg10SpendCommitIds(
            transactionId = TransactionId(context.id(requestId, "transaction_id")),
            versionId = TransactionVersionId(context.id(requestId, "version_id")),
            postingSetId = PostingSetId(context.id(requestId, "posting_set_id")),
            expensePostingId = PostingId(context.id(requestId, "expense_posting_id")),
            storedValuePostingId = PostingId(context.id(requestId, "stored_value_posting_id")),
            confirmationId = Rg10ConfirmationId(context.id(requestId, "confirmation_id")),
            consumptions = listOf(Rg10ConsumptionId(context.id(requestId, "consumption_id"))),
            lotHistoryIds = listOf(context.id(requestId, "lot_history_id")),
        ),
    )
}

private fun adaptReminder(
    input: JsonObject,
    context: Rg10FixtureContext,
): Rg10Operation.RecordExpiryReminder {
    val requestId = input.string("request_id")
    return Rg10Operation.RecordExpiryReminder(
        ledgerId = context.ledgerId,
        input = Rg10ExpiryReminderInput(
            requestId = RequestId(requestId),
            lotId = StoredValueLotId(input.string("lot_id")),
            reminderStatus = input.string("reminder_status"),
            explicitConfirmation = input.boolean("explicit_confirmation"),
        ),
    )
}

private fun adaptExpiryLoss(
    input: JsonObject,
    context: Rg10FixtureContext,
): Rg10Operation.ConfirmStoredValueExpiryLoss {
    val requestId = input.string("request_id")
    val occurredAt = input.instant("occurred_at")
    val confirmedAtText = context.time(requestId, "confirmed_at") ?: input.string("occurred_at")
    val currency = context.currency(input.string("currency"))
    return Rg10Operation.ConfirmStoredValueExpiryLoss(
        ledgerId = context.ledgerId,
        input = Rg10ConfirmExpiryLossInput(
            requestId = RequestId(requestId),
            lotId = StoredValueLotId(input.string("lot_id")),
            amount = input.money("amount", currency),
            currency = currency,
            occurredAt = occurredAt,
            occurredAtText = input.string("occurred_at"),
            confirmedAt = Instant.parse(confirmedAtText),
            confirmedAtText = confirmedAtText,
            explicitConfirmation = input.boolean("explicit_confirmation"),
            confirmsActualExpiry = input.boolean("confirms_actual_expiry"),
            confirmsLot = input.boolean("confirms_lot"),
            confirmsAmount = input.boolean("confirms_amount"),
        ),
        ids = Rg10ExpiryCommitIds(
            transactionId = TransactionId(context.id(requestId, "transaction_id")),
            versionId = TransactionVersionId(context.id(requestId, "version_id")),
            postingSetId = PostingSetId(context.id(requestId, "posting_set_id")),
            expiryLossPostingId = PostingId(context.id(requestId, "expiry_loss_posting_id")),
            storedValuePostingId = PostingId(context.id(requestId, "stored_value_posting_id")),
            confirmationId = Rg10ConfirmationId(context.id(requestId, "confirmation_id")),
            sourceId = Rg10SourceRecordId(context.id(requestId, "source_id")),
            evidenceId = Rg10EvidenceId(context.id(requestId, "evidence_id")),
            linkId = Rg10EvidenceLinkId(context.id(requestId, "link_id")),
            lotHistoryId = context.id(requestId, "lot_history_id"),
        ),
    )
}

private fun adaptReconciliationPath(
    path: JsonObject,
    context: Rg10FixtureContext,
): List<Rg10FixtureOperation> = path.entries.map { (name, element) ->
    val raw = element.jsonObject
    val input = raw.getValue("input").jsonObject
    val typed = when (name) {
        // The frozen v1 payload carries the mixed legacy role token; the runtime boundary
        // owns the v2 split role only (GOLDEN_SCHEMA 413), so the adapter translates it.
        "merchant_evidence" -> adaptReconcile(input, context, "stored_value_asset_posting", merchant = true)
        "bank_evidence" -> adaptReconcile(input, context, "bank_payment_posting", merchant = false)
        else -> error("unsupported RG-10 reconciliation path operation $name")
    }
    fixtureOperation(raw, "$.reconciliation_path.$name", typed)
}

private fun adaptReconcile(
    input: JsonObject,
    context: Rg10FixtureContext,
    role: String,
    merchant: Boolean,
): Rg10Operation {
    val sourceId = input.string("source_id")
    val reconcileInput = Rg10ReconcileInput(
        sourceId = Rg10SourceRecordId(sourceId),
        evidenceId = Rg10EvidenceId(input.string("evidence_id")),
        role = role,
        targetPostingId = PostingId(input.string("target_posting_id")),
        explicitConfirmation = input.boolean("explicit_confirmation"),
    )
    return if (merchant) {
        Rg10Operation.ReconcileMerchantCredit(context.ledgerId, reconcileInput)
    } else {
        Rg10Operation.ReconcileBankPayment(context.ledgerId, reconcileInput)
    }
}

private fun adaptImportPath(
    path: JsonObject,
    context: Rg10FixtureContext,
): List<Rg10FixtureOperation> = buildList {
    path.getValue("complete_unconfirmed").jsonArray.forEachIndexed { index, element ->
        add(adaptCompleteImport(element.jsonObject, context, index))
    }
    path.getValue("incomplete_confirmations").jsonArray.forEachIndexed { index, element ->
        add(adaptIncompleteImport(element.jsonObject, context, index))
    }
}

private fun adaptCompleteImport(
    raw: JsonObject,
    context: Rg10FixtureContext,
    index: Int,
): Rg10FixtureOperation {
    val input = raw.getValue("input").jsonObject
    val requestId = input.string("request_id")
    val currency = context.currency(input.string("currency"))
    val operation = when (index) {
        0 -> Rg10Operation.IngestStoredValueRechargeCandidate(
            ledgerId = context.ledgerId,
            input = Rg10IngestRechargeCandidateInput(
                requestId = RequestId(requestId),
                model = input.string("model"),
                paymentAccountId = AccountId(input.string("payment_account_id")),
                storedValueAccountId = AccountId(input.string("stored_value_account_id")),
                paidAmount = input.money("paid_amount", currency),
                creditedAmount = input.money("credited_amount", currency),
                bonusAmount = input.money("bonus_amount", currency),
                currency = currency,
                occurredAt = input.instant("occurred_at"),
                occurredAtText = input.string("occurred_at"),
                lotId = input.optionalString("lot_id")?.let(::StoredValueLotId),
                allFactsComplete = input.boolean("all_facts_complete"),
                explicitConfirmation = input.boolean("explicit_confirmation"),
            ),
            ids = ingestIds(requestId, context),
        )
        1 -> Rg10Operation.IngestStoredValueSpendCandidate(
            ledgerId = context.ledgerId,
            input = Rg10IngestSpendCandidateInput(
                requestId = RequestId(requestId),
                model = input.string("model"),
                behavior = input.string("behavior"),
                storedValueAccountId = AccountId(input.string("stored_value_account_id")),
                categoryId = CategoryId(input.string("category_id")),
                amount = input.money("amount", currency),
                currency = currency,
                occurredAt = input.instant("occurred_at"),
                occurredAtText = input.string("occurred_at"),
                lotAllocations = input.allocations(currency),
                allFactsComplete = input.boolean("all_facts_complete"),
                explicitConfirmation = input.boolean("explicit_confirmation"),
            ),
            ids = ingestIds(requestId, context),
        )
        else -> error("unexpected RG-10 complete import index $index")
    }
    return fixtureOperation(raw, "$.import_path.complete_unconfirmed[$index]", operation)
}

private fun ingestIds(requestId: String, context: Rg10FixtureContext) = Rg10IngestIds(
    candidateId = Rg10CandidateId(context.id(requestId, "candidate_id")),
    sourceId = Rg10SourceRecordId(context.id(requestId, "source_id")),
    evidenceId = Rg10EvidenceId(context.id(requestId, "evidence_id")),
)

private fun adaptIncompleteImport(
    raw: JsonObject,
    context: Rg10FixtureContext,
    index: Int,
): Rg10FixtureOperation {
    val input = raw.getValue("input").jsonObject
    val requestId = input.string("request_id")
    val currency = context.currency()
    val operation = when (index) {
        0 -> Rg10Operation.ConfirmImportedStoredValueRecharge(
            ledgerId = context.ledgerId,
            input = Rg10ConfirmImportedRechargeInput(
                requestId = RequestId(requestId),
                merchantCreditAmount = input.optionalString("merchant_credit_amount")?.toMoney(currency),
                merchantSourceId = input.optionalString("merchant_source_id")?.let(::Rg10SourceRecordId),
                bankPaymentConfirmed = input.booleanOrNull("bank_payment_confirmed"),
                modelConfirmed = input.booleanOrNull("model_confirmed"),
                explicitConfirmation = input.booleanOrNull("explicit_confirmation"),
            ),
        )
        1 -> Rg10Operation.ConfirmImportedStoredValueSpend(
            ledgerId = context.ledgerId,
            input = Rg10ConfirmImportedSpendInput(
                requestId = RequestId(requestId),
                storedValueAccountId = input.optionalString("stored_value_account_id")?.let(::AccountId),
                amount = input.optionalString("amount")?.toMoney(currency),
                actualTime = input.optionalString("actual_time")?.let(Instant::parse),
                actualTimeText = input.optionalString("actual_time"),
                categoryConfirmed = input.booleanOrNull("category_confirmed"),
                lotAllocationConfirmed = input.booleanOrNull("lot_allocation_confirmed"),
                explicitConfirmation = input.booleanOrNull("explicit_confirmation"),
            ),
        )
        else -> error("unexpected RG-10 incomplete import index $index")
    }
    return fixtureOperation(raw, "$.import_path.incomplete_confirmations[$index]", operation)
}

private fun adaptSecondaryCases(
    path: JsonObject,
    context: Rg10FixtureContext,
): List<Rg10FixtureOperation> = buildList {
    add(adaptMultiLotAllocation(path.getValue("multi_lot_allocation").jsonObject, context))
    val merchantAllocation = path.getValue("merchant_evidenced_allocation").jsonObject
    add(adaptMerchantAllocation(merchantAllocation, context))
    add(adaptRename(path.getValue("rename_zero_effect").jsonObject, context))
    add(adaptActivation(path.getValue("activation_boundary").jsonObject, context))
}

private fun adaptMultiLotAllocation(
    raw: JsonObject,
    context: Rg10FixtureContext,
): Rg10FixtureOperation {
    val input = raw.getValue("input").jsonObject
    val base = raw.getValue("base").jsonObject
    val currency = context.currency()
    // This is the only operation-shaped case whose baseline is the inline `base` object
    // instead of a canonical state id, and it carries no stable commit ids of its own. The
    // spend facts it omits (request id, model/behavior/category/time, the confirmation
    // flags, and all commit ids) are synthesized deterministically: ids follow the
    // Rg10OperationsTest `*-multi-lot-rg10` convention, times mirror the main-path spend,
    // and the required acceptance flags are forced true so the fixture-asserted accepted
    // outcome (allocation order only) stays reachable. The consumption count is derived
    // from the inline base lots (one consumption per base lot) and never reads the
    // expected output back into runtime input (D-083: no reverse expected reading).
    val consumptionCount = base.getValue("lots").jsonArray.size
    val occurredAtText = "2026-01-20T12:00:00+08:00"
    val createdAtText = "2026-01-20T12:03:00+08:00"
    val operation = Rg10Operation.ConfirmStoredValueSpend(
        ledgerId = context.ledgerId,
        input = Rg10ConfirmSpendInput(
            requestId = RequestId("request-multi-lot-rg10"),
            model = "stored_value_asset",
            behavior = "stored_value_spend",
            storedValueAccountId = AccountId(base.string("stored_value_account_id")),
            categoryId = CategoryId("expense-category-meal-rg10"),
            amount = input.money("amount", currency),
            currency = currency,
            occurredAt = Instant.parse(occurredAtText),
            occurredAtText = occurredAtText,
            createdAt = Instant.parse(createdAtText),
            createdAtText = createdAtText,
            explicitConfirmation = input.boolean("explicit_confirmation"),
            confirmsModel = true,
            confirmsBehavior = true,
            confirmsStoredValueAccount = true,
            confirmsAmount = true,
            confirmsActualTime = true,
            confirmsCategory = true,
            merchantAllocationProvided = input.boolean("merchant_allocation_provided"),
            confirmsLotAllocation = true,
        ),
        ids = Rg10SpendCommitIds(
            transactionId = TransactionId("transaction-multi-lot-rg10"),
            versionId = TransactionVersionId("version-multi-lot-rg10-v1"),
            postingSetId = PostingSetId("posting-set-multi-lot-rg10"),
            expensePostingId = PostingId("posting-expense-multi-lot-rg10"),
            storedValuePostingId = PostingId("posting-stored-multi-lot-rg10"),
            confirmationId = Rg10ConfirmationId("confirmation-multi-lot-rg10"),
            consumptions = (1..consumptionCount).map { Rg10ConsumptionId("consumption-multi-lot-$it-rg10") },
            lotHistoryIds = (1..consumptionCount).map { "lot-history-multi-lot-$it-rg10" },
        ),
    )
    return fixtureOperation(raw, "$.secondary_cases.multi_lot_allocation", operation)
}

private fun adaptMerchantAllocation(
    raw: JsonObject,
    context: Rg10FixtureContext,
): Rg10FixtureOperation {
    val input = raw.getValue("input").jsonObject
    val requestId = input.string("request_id")
    val currency = context.currency()
    val operation = Rg10Operation.ApplyMerchantLotAllocation(
        ledgerId = context.ledgerId,
        input = Rg10MerchantAllocationInput(
            requestId = RequestId(requestId),
            amount = input.money("amount", currency),
            merchantAllocationProvided = input.boolean("merchant_allocation_provided"),
            merchantEvidenceId = Rg10EvidenceId(input.string("merchant_evidence_id")),
            allocations = input.allocations(currency),
            explicitConfirmation = input.boolean("explicit_confirmation"),
        ),
        ids = Rg10AllocationCommitIds(
            allocationId = Rg10AllocationId(context.id(requestId, "allocation_id")),
            consumptionId = Rg10ConsumptionId(context.id(requestId, "consumption_id")),
        ),
    )
    return fixtureOperation(raw, "$.secondary_cases.merchant_evidenced_allocation", operation)
}

private fun adaptRename(
    raw: JsonObject,
    context: Rg10FixtureContext,
): Rg10FixtureOperation {
    val input = raw.getValue("input").jsonObject
    val operation = Rg10Operation.RenameStoredValueLabels(
        ledgerId = context.ledgerId,
        input = Rg10RenameLabelsInput(
            accountId = AccountId(input.string("account_id")),
            newAccountName = input.string("new_account_name"),
            lotId = StoredValueLotId(input.string("lot_id")),
            newLotLabel = input.string("new_lot_label"),
        ),
    )
    return fixtureOperation(raw, "$.secondary_cases.rename_zero_effect", operation)
}

private fun adaptActivation(
    raw: JsonObject,
    context: Rg10FixtureContext,
): Rg10FixtureOperation {
    val input = raw.getValue("input").jsonObject
    val requestId = input.string("request_id")
    val currency = context.currency(input.string("currency"))
    val operation = Rg10Operation.ConfirmStoredValueActivationBalance(
        ledgerId = context.ledgerId,
        input = Rg10ActivationBalanceInput(
            requestId = RequestId(requestId),
            storedValueAccountId = AccountId(input.string("stored_value_account_id")),
            existingBalance = input.money("existing_balance", currency),
            currency = currency,
            activationAt = input.instant("activation_at"),
            activationAtText = input.string("activation_at"),
            createdAt = input.instant("created_at"),
            createdAtText = input.string("created_at"),
            explicitConfirmation = input.boolean("explicit_confirmation"),
            compositionConfirmed = input.boolean("composition_confirmed"),
        ),
        ids = Rg10ActivationCommitIds(
            transactionId = TransactionId(context.id(requestId, "transaction_id")),
            versionId = TransactionVersionId(context.id(requestId, "version_id")),
            postingSetId = PostingSetId(context.id(requestId, "posting_set_id")),
            storedValuePostingId = PostingId(context.id(requestId, "stored_value_posting_id")),
            equityPostingId = PostingId(context.id(requestId, "equity_posting_id")),
            confirmationId = Rg10ConfirmationId(context.id(requestId, "confirmation_id")),
            adjustmentId = Rg10ActivationAdjustmentId(context.id(requestId, "adjustment_id")),
            adjustmentHistoryId = context.id(requestId, "adjustment_history_id"),
            reconstructionId = Rg10ReconstructionId(context.id(requestId, "reconstruction_id")),
            replacementGroupId = context.id(requestId, "replacement_group_id"),
            sourceId = Rg10SourceRecordId(context.id(requestId, "source_id")),
            evidenceId = Rg10EvidenceId(context.id(requestId, "evidence_id")),
            linkId = Rg10EvidenceLinkId(context.id(requestId, "link_id")),
            auditLinkId = Rg10AuditLinkId(context.id(requestId, "audit_link_id")),
        ),
    )
    return fixtureOperation(raw, "$.secondary_cases.activation_boundary", operation)
}

private fun adaptInvalidInputs(
    inputs: kotlinx.serialization.json.JsonArray,
    context: Rg10FixtureContext,
): List<Rg10FixtureOperation> = inputs.map { element ->
    val raw = element.jsonObject
    val input = raw.getValue("input").jsonObject
    val id = raw.string("id")
    val (predicate, attempted) = when (id) {
        "float-amount" -> Rg10InvalidPredicate.EXACT_DECIMAL_PAID to mapOf(
            Rg10FieldPath.ATTEMPTED_PAID_AMOUNT.value to input.optionalString("paid_amount"),
        )
        "numeric-credited-amount" -> Rg10InvalidPredicate.EXACT_DECIMAL_CREDITED to mapOf(
            Rg10FieldPath.ATTEMPTED_CREDITED_AMOUNT.value to input.optionalString("credited_amount"),
        )
        "numeric-bonus-amount" -> Rg10InvalidPredicate.EXACT_DECIMAL_BONUS to mapOf(
            Rg10FieldPath.ATTEMPTED_BONUS_AMOUNT.value to input.optionalString("bonus_amount"),
        )
        "nonpositive-amount" -> Rg10InvalidPredicate.PAID_POSITIVE to mapOf(
            Rg10FieldPath.ATTEMPTED_PAID_AMOUNT.value to input.optionalString("paid_amount"),
        )
        "nonpositive-credited-amount" -> Rg10InvalidPredicate.CREDITED_POSITIVE to mapOf(
            Rg10FieldPath.ATTEMPTED_CREDITED_AMOUNT.value to input.optionalString("credited_amount"),
        )
        "negative-bonus-amount" -> Rg10InvalidPredicate.BONUS_NON_NEGATIVE to mapOf(
            Rg10FieldPath.ATTEMPTED_PAID_AMOUNT.value to input.optionalString("paid_amount"),
            Rg10FieldPath.ATTEMPTED_CREDITED_AMOUNT.value to input.optionalString("credited_amount"),
            Rg10FieldPath.ATTEMPTED_BONUS_AMOUNT.value to input.optionalString("bonus_amount"),
        )
        "credited-less-than-paid" -> Rg10InvalidPredicate.CREDITED_EQUALS_PAID_PLUS_BONUS to mapOf(
            Rg10FieldPath.ATTEMPTED_PAID_AMOUNT.value to input.optionalString("paid_amount"),
            Rg10FieldPath.ATTEMPTED_CREDITED_AMOUNT.value to input.optionalString("credited_amount"),
            Rg10FieldPath.ATTEMPTED_BONUS_AMOUNT.value to input.optionalString("bonus_amount"),
        )
        "component-mismatch" -> Rg10InvalidPredicate.COMPONENT_SUM_MATCH to mapOf(
            Rg10FieldPath.ATTEMPTED_PAID_AMOUNT.value to input.optionalString("paid_amount"),
            Rg10FieldPath.ATTEMPTED_CREDITED_AMOUNT.value to input.optionalString("credited_amount"),
            Rg10FieldPath.ATTEMPTED_BONUS_AMOUNT.value to input.optionalString("bonus_amount"),
        )
        "disabled-stored-account" -> Rg10InvalidPredicate.STORED_ACCOUNT_ENABLED to mapOf(
            Rg10FieldPath.ATTEMPTED_STORED_VALUE_ACCOUNT.value to input.optionalString("stored_value_account_id"),
        )
        "model-overlap" -> Rg10InvalidPredicate.STORED_MODEL_ISOLATION to mapOf(
            Rg10FieldPath.ATTEMPTED_MODEL.value to input.optionalString("model"),
            Rg10FieldPath.ATTEMPTED_STORED_VALUE_ACCOUNT.value to input.optionalString("stored_value_account_id"),
        )
        "spend-over-balance" -> Rg10InvalidPredicate.EFFECTIVE_BALANCE_CAP to mapOf(
            Rg10FieldPath.ATTEMPTED_STORED_VALUE_ACCOUNT.value to input.optionalString("stored_value_account_id"),
            Rg10FieldPath.ATTEMPTED_AMOUNT.value to input.optionalString("amount"),
        )
        "invalid-lot-allocation" -> Rg10InvalidPredicate.LOT_ALLOCATION_CAP to mapOf(
            Rg10FieldPath.ATTEMPTED_LOT.value to input.optionalString("lot_id"),
            Rg10FieldPath.ATTEMPTED_AMOUNT.value to input.optionalString("amount"),
        )
        "unconfirmed-expiry" -> Rg10InvalidPredicate.EXPIRY_EXPLICIT_CONFIRMATION to mapOf(
            Rg10FieldPath.ATTEMPTED_LOT.value to input.optionalString("lot_id"),
            Rg10FieldPath.ATTEMPTED_AMOUNT.value to input.optionalString("amount"),
            Rg10FieldPath.ATTEMPTED_EXPLICIT_CONFIRMATION.value to input.optionalString("explicit_confirmation"),
        )
        "guessed-composition" -> Rg10InvalidPredicate.COMPOSITION_EVIDENCED to mapOf(
            Rg10FieldPath.ATTEMPTED_LOT.value to input.optionalString("lot_id"),
            Rg10FieldPath.ATTEMPTED_COMPOSITION.value to input.optionalString("paid_bonus_composition"),
        )
        "unknown-category" -> Rg10InvalidPredicate.ACTIVE_SECONDARY_CATEGORY to mapOf(
            Rg10FieldPath.ATTEMPTED_CATEGORY.value to input.optionalString("category_id"),
            Rg10FieldPath.ATTEMPTED_AMOUNT.value to input.optionalString("amount"),
        )
        "unknown-payment-account" -> Rg10InvalidPredicate.KNOWN_PAYMENT_ACCOUNT to mapOf(
            Rg10FieldPath.ATTEMPTED_PAYMENT_ACCOUNT.value to input.optionalString("payment_account_id"),
        )
        "unowned-payment-account", "wrong-payment-account-kind" -> Rg10InvalidPredicate.OWNED_PAYMENT_ASSET to mapOf(
            Rg10FieldPath.ATTEMPTED_PAYMENT_ACCOUNT.value to input.optionalString("payment_account_id"),
        )
        "wrong-stored-account-kind" -> Rg10InvalidPredicate.ENABLED_STORED_VALUE_ASSET to mapOf(
            Rg10FieldPath.ATTEMPTED_STORED_VALUE_ACCOUNT.value to input.optionalString("stored_value_account_id"),
        )
        "wrong-currency" -> Rg10InvalidPredicate.SAME_CNY_CURRENCY to mapOf(
            Rg10FieldPath.ATTEMPTED_CURRENCY.value to input.optionalString("currency"),
            Rg10FieldPath.ATTEMPTED_PAID_AMOUNT.value to input.optionalString("paid_amount"),
        )
        else -> error("unsupported RG-10 invalid input $id")
    }
    fixtureOperation(
        raw,
        "$.invalid_inputs[$id]",
        Rg10Operation.InvalidInput(
            ledgerId = context.ledgerId,
            input = Rg10InvalidInput(
                requestId = RequestId(input.optionalString("request_id") ?: id),
                action = invalidInputAction(id),
                predicate = predicate,
                attemptedInput = attempted,
            ),
        ),
    )
}

private fun invalidInputAction(id: String): Rg10Action = when (id) {
    "float-amount",
    "numeric-credited-amount",
    "numeric-bonus-amount",
    "nonpositive-amount",
    "nonpositive-credited-amount",
    "negative-bonus-amount",
    "credited-less-than-paid",
    "component-mismatch",
    "disabled-stored-account",
    "model-overlap",
    "unknown-payment-account",
    "unowned-payment-account",
    "wrong-payment-account-kind",
    "wrong-stored-account-kind",
    "wrong-currency",
    -> Rg10Action.CONFIRM_STORED_VALUE_RECHARGE
    "spend-over-balance",
    "unknown-category",
    -> Rg10Action.CONFIRM_STORED_VALUE_SPEND
    "invalid-lot-allocation" -> Rg10Action.APPLY_MERCHANT_LOT_ALLOCATION
    "unconfirmed-expiry" -> Rg10Action.CONFIRM_STORED_VALUE_EXPIRY_LOSS
    "guessed-composition" -> Rg10Action.CONFIRM_STORED_VALUE_SPEND
    else -> error("unsupported RG-10 invalid input $id")
}

private fun adaptRetries(
    path: JsonObject,
    availableOperations: List<Rg10FixtureOperation>,
): List<Rg10FixtureOperation> = path.entries.map { (name, element) ->
    val raw = element.jsonObject
    val inputId = raw.string("input_id")
    val original = availableOperations.firstOrNull { operationMatches(it.operation, inputId) }
        ?: error("RG-10 retry input has no original operation: $inputId")
    fixtureOperation(
        raw,
        "$.idempotency.$name",
        original.operation,
        retryOf = inputId,
    )
}

private fun operationMatches(operation: Rg10Operation, inputId: String): Boolean =
    operation.identity.value == inputId ||
        (operation is Rg10Operation.ReconcileMerchantCredit && operation.input.sourceId.value == inputId) ||
        (operation is Rg10Operation.ReconcileBankPayment && operation.input.sourceId.value == inputId)

private fun fixtureOperation(
    raw: JsonObject,
    sourcePath: String,
    operation: Rg10Operation,
    retryOf: String? = null,
): Rg10FixtureOperation {
    val expected = raw.getValue("expected").jsonObject
    val expectedStatus = when {
        retryOf != null -> "no_change"
        expected.boolean("no_change", false) -> "no_change"
        expected.boolean("accepted", false) -> "accepted"
        expected.containsKey("reason") -> "rejected"
        expected.containsKey("resulting_state_id") -> "accepted"
        else -> "rejected"
    }
    val operationContextId = raw["operation_context"]?.jsonObject?.optionalString("operation_id")
    return Rg10FixtureOperation(
        id = raw.optionalString("id") ?: operationContextId ?: sourcePath.substringAfterLast('.'),
        sourcePath = sourcePath,
        expectedStatus = expectedStatus,
        operation = operation,
        baselineStateId = raw.optionalString("pre_operation_baseline_id"),
        resultStateId = expected.optionalString("resulting_state_id"),
        retryOf = retryOf,
        expectedReason = expected.optionalString("reason"),
    )
}

private fun adaptMerchantAllocationBaseline(
    secondary: JsonObject,
    catalog: LedgerCatalog,
): Map<String, Rg10Runtime> {
    val raw = secondary.getValue("merchant_evidenced_allocation").jsonObject
        .getValue("states").jsonObject.getValue("baseline").jsonObject
    val stateId = raw.string("id")
    val currency = CurrencyUnit("CNY", 2)
    val lots = raw.getValue("lots").jsonArray.map { element ->
        val lot = element.jsonObject
        StoredValueLot(
            id = StoredValueLotId(lot.string("id")),
            rechargeTransactionId = null,
            loadedAt = lot.instant("loaded_at"),
            expiresAt = lot.instant("expires_at"),
            faceValue = lot.money("face_value", currency),
            remainingFaceValue = lot.money("remaining_face_value", currency),
            paidAmount = null,
            bonusAmount = null,
            remainingPaidAmount = null,
            remainingBonusAmount = null,
            compositionStatus = "unknown",
            history = emptyList(),
            merchantId = null,
            loadedAtText = lot.string("loaded_at"),
            expiresAtText = lot.string("expires_at"),
        )
    }
    val sourceRecords = raw.getValue("source_records").jsonArray.map { element ->
        val source = element.jsonObject
        Rg10SourceRecord(
            id = Rg10SourceRecordId(source.string("id")),
            sourceType = source.string("source_type"),
            observedAt = source.instant("observed_at"),
            observedAtText = source.string("observed_at"),
            accountId = source.optionalString("account_id")?.let(::AccountId),
            amount = source.optionalString("amount")?.toMoney(currency),
            immutablePayloadDigest = source.string("immutable_payload_digest"),
        )
    }
    val evidence = raw.getValue("evidence").jsonArray.map { element ->
        val item = element.jsonObject
        Rg10Evidence(
            id = Rg10EvidenceId(item.string("id")),
            sourceId = Rg10SourceRecordId(item.string("source_id")),
            evidenceType = item.string("evidence_type"),
            observedAt = item.instant("observed_at"),
            observedAtText = item.string("observed_at"),
        )
    }
    val snapshot = Rg10Snapshot(
        formalTransactions = emptyList(),
        lots = lots,
        consumptions = emptyList(),
        allocations = emptyList(),
        adjustments = emptyList(),
        reconstructions = emptyList(),
        candidates = emptyList(),
        confirmations = emptyList(),
        sourceRecords = sourceRecords,
        evidence = evidence,
        evidenceLinks = emptyList(),
        auditLinks = emptyList(),
        postingSemantics = emptyMap(),
        balances = emptyMap(),
        reports = emptyMap(),
        reconciliation = emptyMap(),
    )
    return mapOf(stateId to Rg10Runtime(catalog, snapshot))
}

private fun buildCatalog(
    raw: JsonObject,
    ledgerId: LedgerId,
    inputs: Rg10FixtureInputs,
): LedgerCatalog {
    val accounts = raw.getValue("accounts").jsonArray.map { element ->
        val account = element.jsonObject
        val storedValue = if (account.boolean("stored_value", false)) {
            StoredValueConfig(
                enabled = account.boolean("enabled"),
                merchantRestricted = account.boolean("restricted"),
                merchantId = account.optionalString("merchant_id"),
            )
        } else {
            null
        }
        Account(
            id = AccountId(account.string("id")),
            ledgerId = ledgerId,
            kind = when (account.string("type")) {
                "asset" -> AccountKind.ASSET
                "liability" -> AccountKind.LIABILITY
                "equity" -> AccountKind.EQUITY
                "income" -> AccountKind.INCOME
                "expense" -> AccountKind.EXPENSE
                else -> error("unsupported RG-10 account type")
            },
            currency = CurrencyUnit(account.string("currency"), account.int("precision", 2)),
            ownedByUser = account.boolean("owned_by_user"),
            realAccount = account.boolean("financial"),
            systemRole = account.optionalString("system_role"),
            storedValue = storedValue,
        )
    }
    val categories = raw.getValue("categories").jsonArray.map { element ->
        val category = element.jsonObject
        val id = category.string("id")
        // GAP-05: numeric level is never inferred. The runtime inputs supply the explicit
        // sanitized parent identity; without it the category stays first-level (fail-closed).
        Category(
            id = CategoryId(id),
            ledgerId = ledgerId,
            parentId = inputs.categories[id]?.get("parent_id")?.let(::CategoryId),
            postingAccountId = category.optionalString("account_id")?.let(::AccountId),
            active = category.boolean("active"),
            kind = when (category.string("kind")) {
                "expense" -> CategoryKind.EXPENSE
                "income" -> CategoryKind.INCOME
                else -> error("unsupported RG-10 category kind")
            },
        )
    }
    return when (val result = LedgerCatalog.create(accounts, categories)) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> error("invalid RG-10 catalog")
    }
}

private fun openingTransaction(raw: JsonObject, ledgerId: LedgerId): Rg10FormalTransactionRecord {
    val transaction = raw.getValue("transactions").jsonArray.first().jsonObject
    val currency = CurrencyUnit(transaction.getValue("postings").jsonArray.first().jsonObject.string("currency"), 2)
    val postings = transaction.getValue("postings").jsonArray.map { element ->
        val posting = element.jsonObject
        Posting(
            id = PostingId(posting.string("id")),
            accountId = AccountId(posting.string("account_id")),
            amount = posting.money("amount", currency),
        )
    }
    val postingSetId = PostingSetId(transaction.string("posting_set_id"))
    val postingSet = when (val result = PostingSet.create(postingSetId, postings)) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> error("invalid RG-10 opening posting set")
    }
    val versionId = TransactionVersionId(transaction.string("current_version_id"))
    val formal = when (
        val result = FormalTransaction.create(
            transaction = Transaction(
                id = TransactionId(transaction.string("id")),
                ledgerId = ledgerId,
                kind = TransactionKind.OPENING_BALANCE,
                currentVersionId = versionId,
            ),
            versions = listOf(
                TransactionVersion(
                    id = versionId,
                    transactionId = TransactionId(transaction.string("id")),
                    versionNumber = 1,
                    postingSetId = postingSetId,
                    times = TransactionTimes(
                        occurredAt = transaction.instant("occurred_at"),
                        statisticsAt = transaction.instant("statistics_at"),
                        effectiveAt = transaction.instant("effective_at"),
                    ),
                ),
            ),
            postingSets = listOf(postingSet),
        )
    ) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> error("invalid RG-10 opening transaction")
    }
    return Rg10FormalTransactionRecord(
        formal,
        transaction.instant("created_at"),
        createdAtText = transaction.string("created_at"),
        effectiveAtText = transaction.string("effective_at"),
        statisticsAtText = transaction.string("effective_at"),
    )
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.optionalString(key: String): String? =
    this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

private fun JsonObject.boolean(key: String): Boolean = getValue(key).jsonPrimitive.content.toBooleanStrict()

private fun JsonObject.boolean(key: String, fallback: Boolean): Boolean =
    this[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: fallback

private fun JsonObject.booleanOrNull(key: String): Boolean? = this[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull()

private fun JsonObject.int(key: String, fallback: Int): Int = this[key]?.jsonPrimitive?.content?.toIntOrNull() ?: fallback

private fun JsonObject.instant(key: String): Instant = Instant.parse(string(key))

private fun JsonObject.money(key: String, currency: CurrencyUnit): Money = string(key).toMoney(currency)

private fun JsonObject.allocations(currency: CurrencyUnit): List<Rg10LotAllocationInput> =
    (this["allocations"] ?: this["lot_allocations"])?.jsonArray?.map { element ->
        val allocation = element.jsonObject
        Rg10LotAllocationInput(
            lotId = StoredValueLotId(allocation.string("lot_id")),
            amount = allocation.money("amount", currency),
        )
    } ?: emptyList()

private fun String.toMoney(currency: CurrencyUnit): Money {
    require(matches(Regex("[+-]?\\d+\\.\\d{2}"))) { "RG-10 requires exact two-place decimal" }
    val negative = startsWith("-")
    val unsigned = removePrefix("+").removePrefix("-")
    val parts = unsigned.split('.')
    val major = parts[0].toLongOrNull()
        ?: error("RG-10 amount exceeds minor-unit range")
    val fraction = parts[1].toLongOrNull()
        ?: error("RG-10 amount exceeds minor-unit range")
    val minor = checkedAdd(checkedMultiply(major, 100L), fraction)
        ?: error("RG-10 amount exceeds minor-unit range")
    val signedMinor = if (negative) {
        checkedNegate(minor) ?: error("RG-10 amount exceeds minor-unit range")
    } else {
        minor
    }
    return Money.ofMinor(signedMinor, currency)
}

private fun checkedMultiply(left: Long, right: Long): Long? {
    if (left == 0L || right == 0L) return 0L
    if (left == Long.MIN_VALUE && right == -1L) return null
    if (right == Long.MIN_VALUE && left == -1L) return null
    val result = left * right
    return if (result / right == left) result else null
}

private fun checkedAdd(left: Long?, right: Long): Long? {
    left ?: return null
    if (right > 0L && left > Long.MAX_VALUE - right) return null
    if (right < 0L && left < Long.MIN_VALUE - right) return null
    return left + right
}

private fun checkedNegate(value: Long): Long? = if (value == Long.MIN_VALUE) null else -value
