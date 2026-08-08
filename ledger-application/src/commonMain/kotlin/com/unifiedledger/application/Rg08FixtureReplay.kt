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
import com.unifiedledger.domain.LendingConfirmationGateField
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSet
import com.unifiedledger.domain.PostingSetId
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

/**
 * D-084 RG-08 fixture replay (shard 4): derives the 44-operation plan (32 v1 + 12 retry;
 * accepted 6 / rejected 25 / no_change 13) from the frozen golden/rules/rg-08.json, builds the
 * lending catalog, loads the runtime anchors from tests/fixtures/rg08-runtime-input.json and
 * parses the 14 frozen operation baselines. The oracle test (Rg08FullStateOracleTest) replays
 * every operation against a pure [Rg08Runtime] and compares field by field with the frozen
 * expected blocks (D-084 oracle contract).
 *
 * Registered fixture-to-runtime projections:
 *
 * 1. Explicit confirmation. The frozen requests express confirmation via `confirmation_mode`
 *    (`explicit_manual_save`) or the six `explicitly_confirmed_fields`; the runtime requires the
 *    explicit boolean, which is projected as true on those paths (RG08-GAP-03/04).
 *
 * 2. The imported candidate's proposed facts are projected from the frozen bank credit source
 *    record (amount -> proposed total, account -> proposed destination, value_at -> proposed
 *    actual receipt time); the agreement source is the second intake source (RG08-GAP-03).
 *
 * 3. The frozen catalog carries the post-rename display name (the fixture's final state); the
 *    runtime input file supplies the pre-rename name so the rename operation stays replayable.
 *
 * 4. The frozen `principal_cap.over_balance_attempt` and the invalid inputs carry no runtime
 *    commit ids; ids are synthesized deterministically and stay inert because those paths reject
 *    before any state mutation.
 */
data class Rg08FixtureOperation(
    val id: String,
    val sourcePath: String,
    val expectedStatus: String,
    val operation: Rg08Operation,
    val baselineStateId: String? = null,
    val resultStateId: String? = null,
    val retryOf: String? = null,
    val expectedReason: String? = null,
)

data class Rg08FixtureReplaySummary(
    val operations: List<Rg08FixtureOperation>,
    val accepted: Int,
    val noChange: Int,
    val rejected: Int,
)

data class Rg08FixtureSource(
    val id: String,
    val sourceRecordId: String,
    val kind: String,
    val observedAtText: String,
    val bookingAtText: String? = null,
    val valueAtText: String? = null,
    val accountId: String? = null,
    val counterpartyId: String? = null,
    val amountText: String? = null,
    val currency: String? = null,
    val originalSourcePayloadHash: String? = null,
    val immutablePayloadHash: String,
    val mirrorOfSourceId: String? = null,
) {
    val observedAt: Instant get() = Instant.parse(observedAtText)
    val bookingAt: Instant? get() = bookingAtText?.let(Instant::parse)
    val valueAt: Instant? get() = valueAtText?.let(Instant::parse)
}

data class Rg08FixtureInputs(
    val ids: Map<String, Map<String, String?>>,
    val sources: Map<String, Rg08FixtureSource>,
    val times: Map<String, Map<String, String>>,
    val counterparties: Map<String, Map<String, String>>,
)

data class Rg08FixtureCase(
    val ledgerId: LedgerId,
    val catalog: LedgerCatalog,
    val lendingCatalog: Rg08LendingCatalog,
    val openingTransactions: List<Rg08FormalTransactionRecord>,
    val operations: List<Rg08FixtureOperation>,
    val allOperations: List<Rg08FixtureOperation> = operations,
    /** The 14 frozen operation baselines keyed by state id (input-side anchors for replay). */
    val baselineStates: Map<String, JsonObject> = emptyMap(),
)

fun parseRg08FixtureInputs(raw: String): Rg08FixtureInputs {
    val root = Json.parseToJsonElement(raw).jsonObject
    val ids = root.getValue("ids").jsonObject.mapValues { (_, value) ->
        value.jsonObject.mapValues { (_, field) ->
            field.takeUnless { it is JsonNull }?.jsonPrimitive?.content
        }
    }
    val sources = root.getValue("sources").jsonArray.associate { element ->
        val source = element.jsonObject
        val id = source.string("id")
        id to Rg08FixtureSource(
            id = id,
            sourceRecordId = source.string("source_record_id"),
            kind = source.string("kind"),
            observedAtText = source.string("observed_at"),
            bookingAtText = source.optionalString("booking_at"),
            valueAtText = source.optionalString("value_at"),
            accountId = source.optionalString("account_id"),
            counterpartyId = source.optionalString("counterparty_id"),
            amountText = source.optionalString("amount"),
            currency = source.optionalString("currency"),
            originalSourcePayloadHash = source.optionalString("original_source_payload_hash"),
            immutablePayloadHash = source.string("immutable_payload_hash"),
            mirrorOfSourceId = source.optionalString("mirror_of_source_id"),
        )
    }
    val times = root.getValue("times").jsonObject.mapValues { (_, value) ->
        value.jsonObject.mapValues { (_, field) -> field.jsonPrimitive.content }
    }
    val counterparties = root.getValue("counterparties").jsonObject.mapValues { (_, value) ->
        value.jsonObject.mapValues { (_, field) -> field.jsonPrimitive.content }
    }
    return Rg08FixtureInputs(ids, sources, times, counterparties)
}

fun adaptRg08Fixture(raw: String, inputs: Rg08FixtureInputs): Rg08FixtureCase {
    val fixture = Json.parseToJsonElement(raw).jsonObject
    val ledgerId = LedgerId(fixture.getValue("case").jsonObject.string("ledger_id"))
    val catalogRaw = fixture.getValue("catalog").jsonObject
    val catalog = buildCatalog(catalogRaw, ledgerId)
    val lendingCatalog = buildLendingCatalog(catalogRaw, inputs)
    val opening = openingTransaction(fixture.getValue("opening").jsonObject, ledgerId)
    val context = Rg08FixtureContext(inputs, ledgerId)
    val operations = buildList {
        add(adaptLend(fixture.getValue("lend").jsonObject, context))
        add(adaptManualCollection(fixture.getValue("manual_collection").jsonObject, context))
        add(adaptCapMaximum(fixture.getValue("principal_cap").jsonObject, context))
        add(adaptOverBalance(fixture.getValue("principal_cap").jsonObject, context))
        add(adaptIntake(fixture.getValue("import_collection").jsonObject, context))
        fixture.getValue("import_collection").jsonObject
            .getValue("incomplete_confirmations").jsonArray.forEachIndexed { index, element ->
                add(adaptIncomplete(element.jsonObject, context, index))
            }
        add(adaptConfirmImport(fixture.getValue("import_collection").jsonObject, context))
        add(adaptMirror(fixture.getValue("import_collection").jsonObject, context))
        add(adaptRename(fixture.getValue("counterparty_identity").jsonObject, context))
        fixture.getValue("invalid_inputs").jsonArray.forEach { element ->
            add(adaptInvalid(element.jsonObject, context))
        }
    }
    val allOperations = operations + adaptRetries(
        fixture.getValue("idempotency").jsonObject,
        operations,
    )
    val baselineStates = fixture.getValue("operation_baselines").jsonObject.mapValues { (_, value) ->
        value.jsonObject
    }
    return Rg08FixtureCase(
        ledgerId = ledgerId,
        catalog = catalog,
        lendingCatalog = lendingCatalog,
        openingTransactions = listOf(opening),
        operations = operations,
        allOperations = allOperations,
        baselineStates = baselineStates,
    )
}

fun replayRg08Fixture(raw: String, inputs: Rg08FixtureInputs): Rg08FixtureReplaySummary {
    val case = adaptRg08Fixture(raw, inputs)
    return Rg08FixtureReplaySummary(
        operations = case.allOperations,
        accepted = case.allOperations.count { it.expectedStatus == "accepted" },
        noChange = case.allOperations.count { it.expectedStatus == "no_change" },
        rejected = case.allOperations.count { it.expectedStatus == "rejected" },
    )
}

private class Rg08FixtureContext(
    val inputs: Rg08FixtureInputs,
    val ledgerId: LedgerId,
) {
    fun id(identity: String, field: String): String =
        inputs.ids.getValue(identity).getValue(field)
            ?: error("RG-08 runtime input ID is null: $identity.$field")

    fun source(sourceId: String): Rg08FixtureSource =
        inputs.sources.getValue(sourceId)

    fun time(requestId: String, field: String): String? =
        inputs.times[requestId]?.get(field)

    fun currency(code: String = "CNY") = CurrencyUnit(code, 2)
}

private fun adaptLend(raw: JsonObject, context: Rg08FixtureContext): Rg08FixtureOperation {
    val input = raw.getValue("request").jsonObject
    val requestId = input.string("request_id")
    val currency = context.currency(input.string("currency"))
    val confirmedAtText = context.time(requestId, "confirmed_at")
        ?: error("RG-08 lend confirmed_at missing for $requestId")
    val source = context.source(context.id(requestId, "source_id"))
    val operation = Rg08Operation.ValidateLendingEvent(
        ledgerId = context.ledgerId,
        input = Rg08LendInput(
            requestId = RequestId(requestId),
            behaviorCode = input.string("behavior_code"),
            counterpartyId = input.string("counterparty_id"),
            fundingAccountId = AccountId(input.string("funding_account_id")),
            principalAmount = input.money("principal_amount", currency),
            currency = currency,
            actualAt = input.instant("actual_at"),
            actualAtText = input.string("actual_at"),
            confirmedAt = Instant.parse(confirmedAtText),
            confirmedAtText = confirmedAtText,
            // confirmation_mode = explicit_manual_save (projection rule 1).
            explicitConfirmation = true,
        ),
        ids = Rg08LendIds(
            transactionId = TransactionId(context.id(requestId, "transaction_id")),
            versionId = TransactionVersionId(context.id(requestId, "version_id")),
            postingSetId = PostingSetId(context.id(requestId, "posting_set_id")),
            receivablePostingId = PostingId(context.id(requestId, "receivable_posting_id")),
            fundingPostingId = PostingId(context.id(requestId, "funding_posting_id")),
            positionId = context.id(requestId, "position_id"),
            positionHistoryId = context.id(requestId, "position_history_id"),
            confirmationId = Rg08ConfirmationId(context.id(requestId, "confirmation_id")),
            sourceId = Rg08SourceRecordId(context.id(requestId, "source_id")),
            sourceRecordId = context.id(requestId, "source_record_id"),
            sourceObservedAt = source.observedAt,
            sourceObservedAtText = source.observedAtText,
            sourceAmountMinor = context.id(requestId, "source_amount_minor").toLong(),
            evidenceId = Rg08EvidenceId(context.id(requestId, "evidence_id")),
            evidenceLinkId = Rg08EvidenceLinkId(context.id(requestId, "evidence_link_id")),
        ),
    )
    return fixtureOperation(
        raw,
        "$.lend",
        operation,
        id = "lend",
        baseline = "state-rg08-opening",
        result = "baseline-rg08-lend-confirmed",
    )
}

private fun adaptManualCollection(raw: JsonObject, context: Rg08FixtureContext): Rg08FixtureOperation {
    val input = raw.getValue("request").jsonObject
    val requestId = input.string("request_id")
    val currency = context.currency(input.string("currency"))
    val confirmationSource = context.source(context.id(requestId, "confirmation_source_id"))
    val creditSource = context.source(context.id(requestId, "credit_source_id"))
    val operation = Rg08Operation.ValidateLendingSettlement(
        ledgerId = context.ledgerId,
        input = Rg08SettlementInput(
            requestId = RequestId(requestId),
            behaviorCode = input.string("behavior_code"),
            counterpartyId = input.string("counterparty_id"),
            linkedPositionId = input.string("linked_position_id"),
            allocatedLendTransactionId = null,
            destinationAccountId = AccountId(input.string("destination_account_id")),
            totalReceived = input.money("total_received", currency),
            principalAmount = input.money("principal_amount", currency),
            interestAmount = input.money("interest_amount", currency),
            feeAmount = input.money("fee_amount", currency),
            interestCategoryId = CategoryId(input.string("interest_category_id")),
            currency = currency,
            actualReceiptAt = input.instant("actual_receipt_at"),
            actualReceiptAtText = input.string("actual_receipt_at"),
            confirmedAt = input.instant("confirmed_at"),
            confirmedAtText = input.string("confirmed_at"),
            // confirmation_mode = explicit_manual_save (projection rule 1).
            explicitConfirmation = true,
        ),
        ids = Rg08SettlementIds(
            transactionId = TransactionId(context.id(requestId, "transaction_id")),
            versionId = TransactionVersionId(context.id(requestId, "version_id")),
            postingSetId = PostingSetId(context.id(requestId, "posting_set_id")),
            destinationPostingId = PostingId(context.id(requestId, "destination_posting_id")),
            principalPostingId = PostingId(context.id(requestId, "principal_posting_id")),
            interestPostingId = PostingId(context.id(requestId, "interest_posting_id")),
            settlementId = context.id(requestId, "settlement_id"),
            settlementHistoryId = context.id(requestId, "settlement_history_id"),
            positionHistoryId = context.id(requestId, "position_history_id"),
            confirmationId = Rg08ConfirmationId(context.id(requestId, "confirmation_id")),
            principalComponentId = context.id(requestId, "principal_component_id"),
            interestComponentId = context.id(requestId, "interest_component_id"),
            feeComponentId = context.id(requestId, "fee_component_id"),
            confirmationSourceId = Rg08SourceRecordId(context.id(requestId, "confirmation_source_id")),
            confirmationSourceRecordId = context.id(requestId, "confirmation_source_record_id"),
            confirmationSourceObservedAt = confirmationSource.observedAt,
            confirmationSourceObservedAtText = confirmationSource.observedAtText,
            creditSourceId = Rg08SourceRecordId(context.id(requestId, "credit_source_id")),
            creditSourceRecordId = context.id(requestId, "credit_source_record_id"),
            creditSourceObservedAt = creditSource.observedAt,
            creditSourceObservedAtText = creditSource.observedAtText,
            creditSourceBookingAt = creditSource.bookingAt,
            creditSourceBookingAtText = creditSource.bookingAtText,
            creditSourceValueAt = creditSource.valueAt,
            creditSourceValueAtText = creditSource.valueAtText,
            creditEvidenceId = Rg08EvidenceId(context.id(requestId, "credit_evidence_id")),
            creditEvidenceLinkId = Rg08EvidenceLinkId(context.id(requestId, "credit_evidence_link_id")),
        ),
    )
    return fixtureOperation(
        raw,
        "$.manual_collection",
        operation,
        id = "manual-collection",
        baseline = "baseline-rg08-lend-confirmed",
        result = "state-rg08-manual-complete",
    )
}

private fun adaptCapMaximum(raw: JsonObject, context: Rg08FixtureContext): Rg08FixtureOperation {
    val operationRaw = raw.getValue("maximum_valid_collection").jsonObject
    val input = operationRaw.getValue("input").jsonObject
    val requestId = input.string("request_id")
    val currency = context.currency(input.string("currency"))
    val operation = Rg08Operation.AllocateLendingCollection(
        ledgerId = context.ledgerId,
        input = Rg08AllocateInput(
            requestId = RequestId(requestId),
            counterpartyId = input.string("counterparty_id"),
            destinationAccountId = AccountId(input.string("destination_account_id")),
            totalReceived = input.money("total_received", currency),
            principalAmount = input.money("principal_amount", currency),
            interestAmount = input.money("interest_amount", currency),
            feeAmount = input.money("fee_amount", currency),
            currency = currency,
            interestCategoryId = CategoryId(input.string("interest_category_id")),
            actualReceiptAt = input.instant("actual_receipt_at"),
            actualReceiptAtText = input.string("actual_receipt_at"),
            confirmedAt = input.instant("confirmed_at"),
            confirmedAtText = input.string("confirmed_at"),
            ids = allocateIds(requestId, context),
        ),
    )
    return fixtureOperation(
        operationRaw,
        "$.principal_cap.maximum_valid_collection",
        operation,
        id = "cap-maximum",
        baseline = "baseline-rg08-lend-confirmed",
        result = "state-rg08-position-closed",
    )
}

private fun adaptOverBalance(raw: JsonObject, context: Rg08FixtureContext): Rg08FixtureOperation {
    val operationRaw = raw.getValue("over_balance_attempt").jsonObject
    val input = operationRaw.getValue("input").jsonObject
    val currency = context.currency(input.string("currency"))
    val requestId = operationRaw.getValue("operation_context").jsonObject.string("operation_id")
    val operation = Rg08Operation.AllocateLendingCollection(
        ledgerId = context.ledgerId,
        input = Rg08AllocateInput(
            requestId = RequestId(requestId),
            counterpartyId = input.string("counterparty_id"),
            destinationAccountId = AccountId(input.string("destination_account_id")),
            totalReceived = input.money("total_received", currency),
            principalAmount = input.money("principal_amount", currency),
            interestAmount = input.money("interest_amount", currency),
            feeAmount = input.money("fee_amount", currency),
            currency = currency,
            // The frozen over-balance attempt omits the runtime-only economic times; the op
            // rejects at the principal cap before they are read (projection rule 4).
            interestCategoryId = null,
            actualReceiptAt = null,
            actualReceiptAtText = null,
            confirmedAt = null,
            confirmedAtText = null,
            ids = overBalanceIds(),
        ),
    )
    return fixtureOperation(
        operationRaw,
        "$.principal_cap.over_balance_attempt",
        operation,
        baseline = "baseline-rg08-lend-confirmed",
        expectedReason = "principal_exceeds_outstanding_position",
    )
}

private fun adaptIntake(raw: JsonObject, context: Rg08FixtureContext): Rg08FixtureOperation {
    val operationRaw = raw.getValue("candidate").jsonObject
    val creditSource = context.source("source-rg08-import-credit")
    val agreementSource = context.source(context.id("source-rg08-import-credit", "agreement_source_id"))
    val currency = context.currency(creditSource.currency ?: "CNY")
    val operation = Rg08Operation.IngestImportedCollectionCandidate(
        ledgerId = context.ledgerId,
        input = Rg08ImportIntakeInput(
            creditSourceId = Rg08SourceRecordId(creditSource.id),
            creditSourceRecordId = creditSource.sourceRecordId,
            creditObservedAt = creditSource.observedAt,
            creditObservedAtText = creditSource.observedAtText,
            creditBookingAt = creditSource.bookingAt ?: error("RG-08 intake credit booking_at missing"),
            creditBookingAtText = creditSource.bookingAtText ?: error("RG-08 intake credit booking_at missing"),
            creditValueAt = creditSource.valueAt ?: error("RG-08 intake credit value_at missing"),
            creditValueAtText = creditSource.valueAtText ?: error("RG-08 intake credit value_at missing"),
            creditAccountId = AccountId(creditSource.accountId ?: error("RG-08 intake credit account missing")),
            creditAmountMinor = creditSource.amountText?.toMinor() ?: error("RG-08 intake credit amount missing"),
            creditCurrency = currency,
            creditOriginalSourcePayloadHash = creditSource.originalSourcePayloadHash
                ?: error("RG-08 intake credit original hash missing"),
            creditImmutablePayloadHash = creditSource.immutablePayloadHash,
            agreementSourceId = Rg08SourceRecordId(agreementSource.id),
            agreementSourceRecordId = agreementSource.sourceRecordId,
            agreementObservedAt = agreementSource.observedAt,
            agreementObservedAtText = agreementSource.observedAtText,
            agreementCounterpartyId = agreementSource.counterpartyId
                ?: error("RG-08 intake agreement counterparty missing"),
            agreementCurrency = context.currency(agreementSource.currency ?: "CNY"),
            agreementImmutablePayloadHash = agreementSource.immutablePayloadHash,
            candidateId = Rg08CandidateId(context.id("source-rg08-import-credit", "candidate_id")),
            candidateType = context.id("source-rg08-import-credit", "candidate_type"),
            // Proposed facts projected from the frozen bank credit source (projection rule 2).
            proposedTotalReceivedMinor = creditSource.amountText?.toMinor()
                ?: error("RG-08 intake credit amount missing"),
            proposedDestinationAccountId = AccountId(creditSource.accountId ?: error("RG-08 intake credit account missing")),
            proposedActualReceiptAt = creditSource.valueAt ?: error("RG-08 intake credit value_at missing"),
            proposedActualReceiptAtText = creditSource.valueAtText ?: error("RG-08 intake credit value_at missing"),
            ruleVersion = context.id("source-rg08-import-credit", "rule_version").toInt(),
            confidence = context.id("source-rg08-import-credit", "confidence"),
        ),
        ids = Rg08ImportIntakeIds(
            creditEvidenceId = Rg08EvidenceId(context.id("source-rg08-import-credit", "credit_evidence_id")),
            agreementEvidenceId = Rg08EvidenceId(context.id("source-rg08-import-credit", "agreement_evidence_id")),
            agreementEvidenceLinkId = Rg08EvidenceLinkId(context.id("source-rg08-import-credit", "agreement_evidence_link_id")),
            candidateHistoryId = context.id("source-rg08-import-credit", "candidate_history_id"),
        ),
    )
    return fixtureOperation(
        operationRaw,
        "$.import_collection.candidate",
        operation,
        id = "import-candidate",
        baseline = "baseline-rg08-lend-confirmed",
        result = "baseline-rg08-import-pending",
    )
}

private fun adaptIncomplete(raw: JsonObject, context: Rg08FixtureContext, index: Int): Rg08FixtureOperation {
    val input = raw.getValue("input").jsonObject
    val missingField = LendingConfirmationGateField.entries.single {
        it.name == input.string("missing_field").uppercase()
    }
    val operation = Rg08Operation.RejectIncompleteImportedConfirmation(
        ledgerId = context.ledgerId,
        input = Rg08IncompleteConfirmationInput(
            requestId = RequestId(input.string("confirmation_request_id")),
            candidateId = Rg08CandidateId(context.id("source-rg08-import-credit", "candidate_id")),
            missingField = missingField,
        ),
    )
    return fixtureOperation(
        raw,
        "$.import_collection.incomplete_confirmations[$index]",
        operation,
        baseline = "baseline-rg08-import-pending",
    )
}

private fun adaptConfirmImport(raw: JsonObject, context: Rg08FixtureContext): Rg08FixtureOperation {
    val operationRaw = raw.getValue("confirmation").jsonObject
    val input = operationRaw.getValue("request").jsonObject
    val requestId = input.string("request_id")
    // The frozen request omits the currency; the case declares CNY (case.currency).
    val currency = context.currency(input.optionalString("currency") ?: "CNY")
    val operation = Rg08Operation.ConfirmImportedCollection(
        ledgerId = context.ledgerId,
        input = Rg08ConfirmImportedInput(
            requestId = RequestId(requestId),
            candidateId = Rg08CandidateId(input.string("candidate_id")),
            behaviorCode = input.string("behavior_code"),
            counterpartyId = input.string("counterparty_id"),
            destinationAccountId = AccountId(input.string("destination_account_id")),
            principalAmount = input.money("principal_amount", currency),
            interestAmount = input.money("interest_amount", currency),
            feeAmount = input.money("fee_amount", currency),
            interestCategoryId = CategoryId(input.string("interest_category_id")),
            currency = currency,
            actualReceiptAt = input.instant("actual_receipt_at"),
            actualReceiptAtText = input.string("actual_receipt_at"),
            confirmedAt = input.instant("confirmed_at"),
            confirmedAtText = input.string("confirmed_at"),
            // The six explicitly confirmed fields express the explicit confirmation (rule 1).
            explicitConfirmation = true,
            explicitlyConfirmedFields = input.getValue("explicitly_confirmed_fields").jsonArray
                .map { it.jsonPrimitive.content }.toSet(),
        ),
        ids = Rg08ConfirmImportedIds(
            transactionId = TransactionId(context.id(requestId, "transaction_id")),
            versionId = TransactionVersionId(context.id(requestId, "version_id")),
            postingSetId = PostingSetId(context.id(requestId, "posting_set_id")),
            destinationPostingId = PostingId(context.id(requestId, "destination_posting_id")),
            principalPostingId = PostingId(context.id(requestId, "principal_posting_id")),
            interestPostingId = PostingId(context.id(requestId, "interest_posting_id")),
            settlementId = context.id(requestId, "settlement_id"),
            settlementHistoryId = context.id(requestId, "settlement_history_id"),
            positionHistoryId = context.id(requestId, "position_history_id"),
            confirmationId = Rg08ConfirmationId(context.id(requestId, "confirmation_id")),
            principalComponentId = context.id(requestId, "principal_component_id"),
            interestComponentId = context.id(requestId, "interest_component_id"),
            feeComponentId = context.id(requestId, "fee_component_id"),
            candidateConfirmedHistoryId = context.id(requestId, "candidate_confirmed_history_id"),
            destinationEvidenceLinkId = Rg08EvidenceLinkId(context.id(requestId, "destination_evidence_link_id")),
        ),
    )
    return fixtureOperation(
        operationRaw,
        "$.import_collection.confirmation",
        operation,
        id = "confirm-import",
        baseline = "baseline-rg08-import-pending",
        result = "state-rg08-import-confirmed",
    )
}

private fun adaptMirror(raw: JsonObject, context: Rg08FixtureContext): Rg08FixtureOperation {
    val operationRaw = raw.getValue("mirror_evidence").jsonObject
    val input = operationRaw.getValue("request").jsonObject
    val requestId = input.string("request_id")
    val source = context.source(input.string("source_id"))
    val operation = Rg08Operation.MergeImportedEvidence(
        ledgerId = context.ledgerId,
        input = Rg08MergeInput(
            requestId = RequestId(requestId),
            sourceId = Rg08SourceRecordId(input.string("source_id")),
            sourceRecordId = context.id(requestId, "source_record_id"),
            observedAt = source.observedAt,
            observedAtText = source.observedAtText,
            amountMinor = source.amountText?.toMinor() ?: error("RG-08 mirror amount missing"),
            currency = context.currency(source.currency ?: "CNY"),
            mirrorOfSourceId = source.mirrorOfSourceId ?: error("RG-08 mirror origin missing"),
            immutablePayloadHash = source.immutablePayloadHash,
            evidenceId = Rg08EvidenceId(context.id(requestId, "evidence_id")),
            evidenceLinkId = Rg08EvidenceLinkId(context.id(requestId, "evidence_link_id")),
            targetPostingId = PostingId(context.id(requestId, "target_posting_id")),
            mirrorOfEvidenceId = context.id(requestId, "mirror_of_evidence_id"),
            mergedIntoEvidenceLinkId = context.id(requestId, "merged_into_evidence_link_id"),
        ),
    )
    return fixtureOperation(
        operationRaw,
        "$.import_collection.mirror_evidence",
        operation,
        id = "mirror-evidence",
        baseline = "state-rg08-import-confirmed",
        result = "state-rg08-import-mirror-complete",
    )
}

private fun adaptRename(raw: JsonObject, context: Rg08FixtureContext): Rg08FixtureOperation {
    val rename = raw.getValue("rename").jsonObject
    val requestId = rename.string("request_id")
    val operation = Rg08Operation.RenameCounterparty(
        ledgerId = context.ledgerId,
        input = Rg08RenameInput(
            requestId = RequestId(requestId),
            counterpartyId = rename.string("counterparty_id"),
            oldDisplayName = rename.string("old_display_name"),
            newDisplayName = rename.string("new_display_name"),
            nameHistoryId = context.id(requestId, "name_history_id"),
        ),
    )
    return fixtureOperation(
        rename,
        "$.counterparty_identity.rename",
        operation,
        id = "rename-counterparty",
        baseline = "baseline-rg08-lend-confirmed",
    )
}

private fun adaptInvalid(raw: JsonObject, context: Rg08FixtureContext): Rg08FixtureOperation {
    val id = raw.string("id")
    val input = raw.getValue("input").jsonObject
    val (actionAndPredicate, attempted) = when (id) {
        "floating-total" -> Rg08Action.VALIDATE_LENDING_SETTLEMENT to Rg08InvalidPredicate.EXACT_DECIMAL_TOTAL to mapOf(
            Rg08FieldPath.ATTEMPTED_TOTAL_RECEIVED.value to input.optionalString("binary_float_total"),
        )
        "zero-total" -> Rg08Action.VALIDATE_LENDING_SETTLEMENT to Rg08InvalidPredicate.TOTAL_POSITIVE to mapOf(
            Rg08FieldPath.ATTEMPTED_TOTAL_RECEIVED.value to input.optionalString("total_received"),
        )
        "negative-total" -> Rg08Action.VALIDATE_LENDING_SETTLEMENT to Rg08InvalidPredicate.TOTAL_POSITIVE to mapOf(
            Rg08FieldPath.ATTEMPTED_TOTAL_RECEIVED.value to input.optionalString("total_received"),
        )
        "component-sum-mismatch" -> Rg08Action.VALIDATE_LENDING_SETTLEMENT to Rg08InvalidPredicate.COMPONENTS_EQUAL_TOTAL to mapOf(
            Rg08FieldPath.ATTEMPTED_COMPONENTS.value to componentsText(input),
        )
        "negative-principal" -> Rg08Action.VALIDATE_LENDING_SETTLEMENT to Rg08InvalidPredicate.COMPONENT_NONNEGATIVE to mapOf(
            Rg08FieldPath.ATTEMPTED_PRINCIPAL_AMOUNT.value to input.optionalString("principal_amount"),
        )
        "negative-interest" -> Rg08Action.VALIDATE_LENDING_SETTLEMENT to Rg08InvalidPredicate.COMPONENT_NONNEGATIVE to mapOf(
            Rg08FieldPath.ATTEMPTED_INTEREST_AMOUNT.value to input.optionalString("interest_amount"),
        )
        "negative-fee" -> Rg08Action.VALIDATE_LENDING_SETTLEMENT to Rg08InvalidPredicate.FEE_ZERO to mapOf(
            Rg08FieldPath.ATTEMPTED_FEE_AMOUNT.value to input.optionalString("fee_amount"),
        )
        "positive-fee" -> Rg08Action.VALIDATE_LENDING_SETTLEMENT to Rg08InvalidPredicate.FEE_OUT_OF_SCOPE to mapOf(
            Rg08FieldPath.ATTEMPTED_FEE_AMOUNT.value to input.optionalString("fee_amount"),
        )
        "principal-over-balance" -> Rg08Action.VALIDATE_LENDING_SETTLEMENT to Rg08InvalidPredicate.PRINCIPAL_EXCEEDS_OUTSTANDING to mapOf(
            Rg08FieldPath.ATTEMPTED_PRINCIPAL_AMOUNT.value to input.optionalString("principal_amount"),
        )
        "unknown-destination" -> Rg08Action.VALIDATE_LENDING_SETTLEMENT to Rg08InvalidPredicate.UNKNOWN_DESTINATION to mapOf(
            Rg08FieldPath.ATTEMPTED_DESTINATION_ACCOUNT_ID.value to input.optionalString("destination_account_id"),
        )
        "unowned-destination" -> Rg08Action.VALIDATE_LENDING_SETTLEMENT to Rg08InvalidPredicate.UNOWNED_DESTINATION to mapOf(
            Rg08FieldPath.ATTEMPTED_DESTINATION_ACCOUNT_ID.value to input.optionalString("destination_account_id"),
        )
        "nonfinancial-destination" -> Rg08Action.VALIDATE_LENDING_SETTLEMENT to Rg08InvalidPredicate.NONFINANCIAL_DESTINATION to mapOf(
            Rg08FieldPath.ATTEMPTED_DESTINATION_ACCOUNT_ID.value to input.optionalString("destination_account_id"),
        )
        "unknown-funding-account" -> Rg08Action.VALIDATE_LENDING_EVENT to Rg08InvalidPredicate.UNKNOWN_FUNDING_ACCOUNT to mapOf(
            Rg08FieldPath.ATTEMPTED_FUNDING_ACCOUNT_ID.value to input.optionalString("funding_account_id"),
        )
        "unknown-counterparty" -> Rg08Action.VALIDATE_LENDING_SETTLEMENT to Rg08InvalidPredicate.UNKNOWN_COUNTERPARTY to mapOf(
            Rg08FieldPath.ATTEMPTED_COUNTERPARTY_ID.value to input.optionalString("counterparty_id"),
        )
        "invalid-behavior" -> Rg08Action.VALIDATE_LENDING_SETTLEMENT to Rg08InvalidPredicate.INVALID_BEHAVIOR to mapOf(
            Rg08FieldPath.ATTEMPTED_BEHAVIOR_CODE.value to input.optionalString("behavior_code"),
        )
        "guessed-split" -> Rg08Action.VALIDATE_LENDING_SETTLEMENT to Rg08InvalidPredicate.EXPLICIT_COMPONENT_SPLIT to mapOf(
            Rg08FieldPath.ATTEMPTED_COMPONENTS.value to null,
            Rg08FieldPath.ATTEMPTED_SPLIT_SOURCE.value to input.optionalString("split_source"),
        )
        "cross-currency" -> Rg08Action.VALIDATE_LENDING_SETTLEMENT to Rg08InvalidPredicate.SAME_CURRENCY to mapOf(
            Rg08FieldPath.ATTEMPTED_CURRENCY.value to input.optionalString("currency"),
        )
        "inactive-interest-category" -> Rg08Action.VALIDATE_LENDING_SETTLEMENT to Rg08InvalidPredicate.ACTIVE_EXACT_INTEREST_CATEGORY to mapOf(
            Rg08FieldPath.ATTEMPTED_INTEREST_CATEGORY_ID.value to input.optionalString("interest_category_id"),
        )
        else -> error("unsupported RG-08 invalid input $id")
    }
    val action = actionAndPredicate.first
    val predicate = actionAndPredicate.second
    val operation = Rg08Operation.InvalidInput(
        ledgerId = context.ledgerId,
        input = Rg08InvalidInput(
            requestId = RequestId(input.optionalString("request_id") ?: id),
            action = action,
            predicate = predicate,
            attemptedInput = attempted,
        ),
    )
    return fixtureOperation(raw, "$.invalid_inputs[$id]", operation, baseline = "baseline-rg08-lend-confirmed")
}

private fun adaptRetries(
    path: JsonObject,
    availableOperations: List<Rg08FixtureOperation>,
): List<Rg08FixtureOperation> = path.getValue("retries").jsonArray.mapIndexed { index, element ->
    val raw = element.jsonObject
    val inputId = raw.string("input_id")
    val original = availableOperations.firstOrNull { operationMatches(it.operation, inputId) }
        ?: error("RG-08 retry input has no original operation: $inputId")
    val baseline = raw.getValue("operation_context").jsonObject.string("baseline_id")
    fixtureOperation(
        raw,
        "$.idempotency.retries[$index]",
        Rg08Operation.RetryIdempotentInput(
            ledgerId = original.operation.ledgerId,
            input = Rg08RetryInput(inputId = inputId),
        ),
        retryOf = inputId,
        baseline = baseline,
    )
}

private fun operationMatches(operation: Rg08Operation, inputId: String): Boolean =
    operation.identity.value == inputId ||
        (operation is Rg08Operation.ValidateLendingSettlement &&
            (operation.ids.confirmationSourceId?.value == inputId || operation.ids.creditSourceId?.value == inputId)) ||
        (operation is Rg08Operation.ValidateLendingEvent && operation.ids.sourceId.value == inputId) ||
        (operation is Rg08Operation.IngestImportedCollectionCandidate &&
            operation.input.agreementSourceId.value == inputId) ||
        (operation is Rg08Operation.MergeImportedEvidence && operation.input.requestId.value == inputId)

private fun fixtureOperation(
    raw: JsonObject,
    sourcePath: String,
    operation: Rg08Operation,
    id: String? = null,
    retryOf: String? = null,
    baseline: String? = null,
    result: String? = null,
    expectedReason: String? = null,
): Rg08FixtureOperation {
    val expected = raw.getValue("expected").jsonObject
    val expectedStatus = when {
        retryOf != null -> "no_change"
        expected.booleanOrNull("accepted") == true -> "accepted"
        expected.booleanOrNull("accepted") == false -> "rejected"
        // The intake/confirm/mirror expected blocks carry a resulting_state but no
        // effect_counts; the rename (the single no-change v1 op) carries effect_counts with
        // a same-baseline resulting state (D-084: no_change 13 = rename + 12 retries).
        expected.containsKey("resulting_state") && !expected.containsKey("effect_counts") -> "accepted"
        else -> "no_change"
    }
    val operationId = id ?: raw.optionalString("id")
        ?: sourcePath.substringAfterLast('.').substringBefore('[')
    return Rg08FixtureOperation(
        id = operationId,
        sourcePath = sourcePath,
        expectedStatus = expectedStatus,
        operation = operation,
        baselineStateId = baseline,
        resultStateId = result,
        retryOf = retryOf,
        expectedReason = expectedReason ?: expected.optionalString("reason"),
    )
}

private fun allocateIds(requestId: String, context: Rg08FixtureContext): Rg08SettlementIds = Rg08SettlementIds(
    transactionId = TransactionId(context.id(requestId, "transaction_id")),
    versionId = TransactionVersionId(context.id(requestId, "version_id")),
    postingSetId = PostingSetId(context.id(requestId, "posting_set_id")),
    destinationPostingId = PostingId(context.id(requestId, "destination_posting_id")),
    principalPostingId = PostingId(context.id(requestId, "principal_posting_id")),
    interestPostingId = PostingId(context.id(requestId, "interest_posting_id")),
    settlementId = context.id(requestId, "settlement_id"),
    settlementHistoryId = context.id(requestId, "settlement_history_id"),
    positionHistoryId = context.id(requestId, "position_history_id"),
    confirmationId = Rg08ConfirmationId(context.id(requestId, "confirmation_id")),
    principalComponentId = context.id(requestId, "principal_component_id"),
    interestComponentId = context.id(requestId, "interest_component_id"),
    feeComponentId = context.id(requestId, "fee_component_id"),
)

/** Synthetic inert ids for the frozen over-balance rejection (projection rule 4). */
private fun overBalanceIds(): Rg08SettlementIds = Rg08SettlementIds(
    transactionId = TransactionId("transaction-over-balance-rg08"),
    versionId = TransactionVersionId("version-over-balance-rg08-v1"),
    postingSetId = PostingSetId("posting-set-over-balance-rg08"),
    destinationPostingId = PostingId("posting-destination-over-balance-rg08"),
    principalPostingId = PostingId("posting-principal-over-balance-rg08"),
    interestPostingId = PostingId("posting-interest-over-balance-rg08"),
    settlementId = "settlement-over-balance-rg08",
    settlementHistoryId = "history-settlement-over-balance-rg08",
    positionHistoryId = "history-position-over-balance-rg08",
    confirmationId = Rg08ConfirmationId("confirmation-over-balance-rg08"),
    principalComponentId = "component-over-balance-principal-rg08",
    interestComponentId = "component-over-balance-interest-rg08",
    feeComponentId = "component-over-balance-fee-rg08",
)

private fun buildCatalog(raw: JsonObject, ledgerId: LedgerId): LedgerCatalog {
    val accounts = raw.getValue("accounts").jsonArray.map { element ->
        val account = element.jsonObject
        Account(
            id = AccountId(account.string("id")),
            ledgerId = ledgerId,
            kind = when (account.string("type")) {
                "asset" -> AccountKind.ASSET
                "liability" -> AccountKind.LIABILITY
                "equity" -> AccountKind.EQUITY
                "income" -> AccountKind.INCOME
                "expense" -> AccountKind.EXPENSE
                else -> error("unsupported RG-08 account type")
            },
            currency = CurrencyUnit(account.string("currency"), account.int("precision", 2)),
            ownedByUser = account.boolean("owned_by_user"),
            realAccount = account.boolean("financial"),
            systemRole = account.optionalString("system_role"),
            storedValue = null,
        )
    }
    val categories = raw.getValue("interest_categories").jsonArray.map { element ->
        val category = element.jsonObject
        Category(
            id = CategoryId(category.string("id")),
            ledgerId = ledgerId,
            parentId = category.optionalString("parent_id")?.let(::CategoryId),
            postingAccountId = category.optionalString("account_id")?.let(::AccountId),
            active = category.boolean("active"),
            kind = CategoryKind.INCOME,
        )
    }
    return when (val result = LedgerCatalog.create(accounts, categories)) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> error("invalid RG-08 catalog")
    }
}

private fun buildLendingCatalog(raw: JsonObject, inputs: Rg08FixtureInputs): Rg08LendingCatalog {
    // The frozen catalog carries the post-rename display name; the runtime input supplies the
    // pre-rename name so the rename operation is replayable (projection rule 3).
    val counterparties = raw.getValue("counterparties").jsonArray.map { element ->
        val counterparty = element.jsonObject
        val id = counterparty.string("id")
        Rg08Counterparty(
            id = id,
            displayName = inputs.counterparties[id]?.get("display_name")
                ?: counterparty.string("display_name"),
            active = counterparty.boolean("active"),
            identityKind = counterparty.string("identity_kind"),
        )
    }
    val receivableAccounts = raw.getValue("accounts").jsonArray.mapNotNull { element ->
        val account = element.jsonObject
        if (account.optionalString("position_kind") == "lending_receivable") {
            Rg08ReceivableAccount(
                accountId = AccountId(account.string("id")),
                counterpartyId = account.string("counterparty_id"),
            )
        } else {
            null
        }
    }
    return Rg08LendingCatalog(counterparties, receivableAccounts)
}

private fun openingTransaction(raw: JsonObject, ledgerId: LedgerId): Rg08FormalTransactionRecord {
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
        is DomainResult.Failure -> error("invalid RG-08 opening posting set")
    }
    val versionId = TransactionVersionId(transaction.string("current_version_id"))
    val occurredAt = transaction.instant("occurred_at")
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
                    times = TransactionTimes(occurredAt, occurredAt, occurredAt),
                ),
            ),
            postingSets = listOf(postingSet),
        )
    ) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> error("invalid RG-08 opening transaction")
    }
    return Rg08FormalTransactionRecord(
        formal,
        occurredAt,
        createdAtText = null,
        statisticsAtText = null,
    )
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.optionalString(key: String): String? =
    this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

private fun JsonObject.boolean(key: String): Boolean = getValue(key).jsonPrimitive.content.toBooleanStrict()

private fun JsonObject.booleanOrNull(key: String): Boolean? =
    this[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull()

private fun JsonObject.int(key: String, fallback: Int): Int =
    this[key]?.jsonPrimitive?.content?.toIntOrNull() ?: fallback

private fun JsonObject.instant(key: String): Instant = Instant.parse(string(key))

private fun JsonObject.money(key: String, currency: CurrencyUnit): Money = string(key).toMoney(currency)

private fun componentsText(input: JsonObject): String = listOf(
    input.optionalString("principal_amount"),
    input.optionalString("interest_amount"),
    input.optionalString("fee_amount"),
).joinToString("+") { it ?: "<null>" }

private fun String.toMinor(): Long {
    val negative = startsWith("-")
    val unsigned = removePrefix("-").removePrefix("+")
    val parts = unsigned.split('.')
    if (parts.size > 2) {
        error("RG-08 amount has multiple decimal separators: $this")
    }
    val major = parts[0].toLongOrNull() ?: error("RG-08 amount exceeds minor-unit range: $this")
    // RG08-QA-05: decimal places must be exactly 2; fail loud instead of silently shifting
    // (e.g. "1.5" or "1.500" must never become 105 / 1500 minor units).
    val fractionText = parts.getOrNull(1) ?: ""
    if (fractionText.length != 2 || fractionText.toLongOrNull() == null) {
        error("RG-08 amount must have exactly 2 decimal places: $this")
    }
    val minor = major * 100L + fractionText.toLong()
    return if (negative) -minor else minor
}

private fun String.toMoney(currency: CurrencyUnit): Money =
    Money.ofMinor(toMinor(), currency)
