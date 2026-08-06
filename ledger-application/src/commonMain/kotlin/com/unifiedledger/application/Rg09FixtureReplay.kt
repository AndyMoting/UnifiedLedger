package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.BALANCE_ADJUSTMENT_EQUITY_ROLE
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
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

data class Rg09FixtureOperation(
    val id: String,
    val sourcePath: String,
    val expectedStatus: String,
    val operation: Rg09Operation,
    val baselineStateId: String? = null,
    val resultStateId: String? = null,
    val retryOf: String? = null,
)

data class Rg09FixtureReplaySummary(
    val operations: List<Rg09FixtureOperation>,
    val accepted: Int,
    val noChange: Int,
    val rejected: Int,
)

data class Rg09FixtureCase(
    val ledgerId: LedgerId,
    val catalog: LedgerCatalog,
    val openingTransactions: List<Rg09FormalTransactionRecord>,
    val operations: List<Rg09FixtureOperation>,
    val allOperations: List<Rg09FixtureOperation> = operations,
)

data class Rg09FixtureSource(
    val id: String,
    val sourceType: String,
    val observedAtText: String,
    val accountId: String,
    val amountText: String,
    val immutablePayloadDigest: String,
    val counterAccountId: String? = null,
    val actualAtText: String? = null,
    val savedAtText: String? = null,
)

data class Rg09FixtureInputs(
    val ids: Map<String, Map<String, String?>>,
    val sources: Map<String, Rg09FixtureSource>,
    val sourceByRequest: Map<String, String>,
    val directionByRequest: Map<String, String>,
    val targetObservedAtByRequest: Map<String, String>,
)

fun parseRg09FixtureInputs(raw: String): Rg09FixtureInputs {
    val root = Json.parseToJsonElement(raw).jsonObject
    val ids = root.getValue("ids").jsonObject.mapValues { (_, value) ->
        value.jsonObject.mapValues { (_, field) ->
            field.takeUnless { it is JsonNull }?.jsonPrimitive?.content
        }
    }
    val sources = root.getValue("sources").jsonArray.associate { element ->
        val source = element.jsonObject
        val id = source.string("id")
        id to Rg09FixtureSource(
            id = id,
            sourceType = source.string("source_type"),
            observedAtText = source.string("observed_at"),
            accountId = source.string("account_id"),
            amountText = source.string("amount"),
            immutablePayloadDigest = source.string("immutable_payload_digest"),
            counterAccountId = source.optionalString("counter_account_id"),
            actualAtText = source.optionalString("actual_at"),
            savedAtText = source.optionalString("saved_at"),
        )
    }
    val sourceByRequest = root.getValue("source_by_request").jsonObject
        .mapValues { (_, value) -> value.jsonPrimitive.content }
    val directionByRequest = root.getValue("direction_by_request").jsonObject
        .mapValues { (_, value) -> value.jsonPrimitive.content }
    val targetObservedAtByRequest = root.getValue("target_observed_at_by_request").jsonObject
        .mapValues { (_, value) -> value.jsonPrimitive.content }
    return Rg09FixtureInputs(ids, sources, sourceByRequest, directionByRequest, targetObservedAtByRequest)
}

fun adaptRg09Fixture(raw: String, inputs: Rg09FixtureInputs): Rg09FixtureCase {
    val fixture = Json.parseToJsonElement(raw).jsonObject
    val case = fixture.getValue("case").jsonObject
    val ledgerId = LedgerId(case.string("ledger_id"))
    val catalog = buildCatalog(fixture.getValue("catalog").jsonObject, ledgerId)
    val opening = openingTransaction(fixture.getValue("opening").jsonObject, ledgerId)
    val context = Rg09FixtureContext(inputs, ledgerId, listOf(opening))
    val operations = adaptMainPath(fixture.getValue("main_path").jsonObject, context)
    val nonRetryOperations = buildList<Rg09FixtureOperation> {
        addAll(operations)
        add(adaptStalePreview(fixture.getValue("stale_preview").jsonObject, context))
        add(adaptZeroDelta(fixture.getValue("zero_delta").jsonObject, context))
        addAll(adaptImportPath(fixture.getValue("import_path").jsonObject, context))
        addAll(adaptInvalidInputs(fixture.getValue("invalid_inputs").jsonArray, context))
        addAll(adaptEvidencePath(fixture.getValue("evidence_path").jsonObject, context))
    }
    val allOperations = nonRetryOperations + adaptRetries(
        fixture.getValue("idempotency").jsonObject,
        nonRetryOperations,
    )
    return Rg09FixtureCase(
        ledgerId = ledgerId,
        catalog = catalog,
        openingTransactions = listOf(opening),
        operations = operations,
        allOperations = allOperations,
    )
}

fun replayRg09Fixture(raw: String, inputs: Rg09FixtureInputs): Rg09FixtureReplaySummary {
    val case = adaptRg09Fixture(raw, inputs)
    return Rg09FixtureReplaySummary(
        operations = case.allOperations,
        accepted = case.allOperations.count { it.expectedStatus == "accepted" },
        noChange = case.allOperations.count { it.expectedStatus == "no_change" },
        rejected = case.allOperations.count { it.expectedStatus == "rejected" },
    )
}

private class Rg09FixtureContext(
    val inputs: Rg09FixtureInputs,
    val ledgerId: LedgerId,
    val openingTransactions: List<Rg09FormalTransactionRecord>,
) {
    fun id(identity: String, field: String): String =
        inputs.ids.getValue(identity).getValue(field)
            ?: error("RG-09 runtime input ID is null: $identity.$field")

    fun optionalId(identity: String, field: String): String? =
        inputs.ids[identity]?.get(field)

    fun source(sourceId: String): Rg09FixtureSource =
        inputs.sources.getValue(sourceId)

    fun sourceForRequest(requestId: String): Rg09FixtureSource =
        source(inputs.sourceByRequest.getValue(requestId))

    fun directionForRequest(requestId: String): String =
        inputs.directionByRequest.getValue(requestId)

    fun targetObservedAtTextForRequest(requestId: String): String =
        inputs.targetObservedAtByRequest.getValue(requestId)

    fun currency(code: String = "CNY") = CurrencyUnit(code, 2)
}

private fun adaptMainPath(
    path: JsonObject,
    context: Rg09FixtureContext,
): List<Rg09FixtureOperation> {
    val names = listOf(
        "preview",
        "confirmation",
        "transfer_confirmation",
        "explanation_confirmation",
        "second_transfer_confirmation",
        "second_explanation_confirmation",
    )
    return names.map { name ->
        val operation = path.getValue(name).jsonObject
        val input = operation.getValue("input").jsonObject
        val typed = when (name) {
            "preview" -> adaptPreview(input, context)
            "confirmation" -> adaptAdjustmentConfirmation(input, context)
            "transfer_confirmation", "second_transfer_confirmation" -> adaptTransfer(input, context)
            "explanation_confirmation", "second_explanation_confirmation" -> adaptExplanation(input, context)
                else -> error("unsupported RG-09 main path operation $name")
        }
        fixtureOperation(operation, "$.main_path.$name", typed)
    }
}

private fun adaptStalePreview(
    raw: JsonObject,
    context: Rg09FixtureContext,
): Rg09FixtureOperation {
    val input = raw.getValue("input").jsonObject
    val targetObservedAt = Instant.parse("2026-01-31T23:59:59+08:00")
    // v1 stores a symbolic migration token here. The runtime boundary accepts only the
    // D-065 digest computed from the opening current-version postings.
    val previewFingerprint = Rg09LedgerFingerprint.digest(context.openingTransactions, targetObservedAt)
    val operation = Rg09Operation.ConfirmBalanceAdjustment(
        ledgerId = context.ledgerId,
        input = Rg09ConfirmBalanceAdjustmentInput(
            requestId = RequestId(raw.string("id")),
            candidateId = Rg09CandidateId(input.string("preview_id")),
            ledgerFingerprint = previewFingerprint,
            explicitConfirmation = input.boolean("explicit_confirmation"),
            confirmedAt = input.instant("preview_changed_at"),
            confirmedAtText = input.string("preview_changed_at"),
        ),
        ids = Rg09AdjustmentCommitIds(
            confirmationId = Rg09ConfirmationId("confirmation-stale-preview-rg09"),
            adjustmentId = Rg09AdjustmentId("adjustment-stale-preview-rg09"),
            transactionId = TransactionId("transaction-stale-preview-rg09"),
            versionId = TransactionVersionId("version-stale-preview-rg09-v1"),
            postingSetId = PostingSetId("posting-set-stale-preview-rg09"),
            targetPostingId = PostingId("posting-stale-preview-target-rg09"),
            equityPostingId = PostingId("posting-stale-preview-equity-rg09"),
            historyId = "history-stale-preview-rg09",
        ),
    )
    return fixtureOperation(raw, "$.stale_preview", operation)
}

private fun adaptZeroDelta(
    raw: JsonObject,
    context: Rg09FixtureContext,
): Rg09FixtureOperation {
    val input = raw.getValue("input").jsonObject
    val requestId = input.string("request_id")
    val operation = Rg09Operation.PreviewTargetBalance(
        ledgerId = context.ledgerId,
        input = Rg09PreviewTargetBalanceInput(
            requestId = RequestId(requestId),
            accountId = AccountId(input.string("account_id")),
            targetAmount = input.money("target_amount", context.currency(input.string("currency"))),
            targetObservedAt = input.instant("target_observed_at"),
            savedAt = Instant.parse(context.source(context.id(requestId, "source_record_id")).savedAtText ?: error("zero-delta saved_at missing")),
            currency = context.currency(input.string("currency")),
            explicitConfirmation = false,
            immutablePayloadDigest = context.source(context.id(requestId, "source_record_id")).immutablePayloadDigest,
            ledgerFingerprint = Rg09LedgerFingerprint.digest(context.openingTransactions, input.instant("target_observed_at")),
            targetObservedAtText = input.string("target_observed_at"),
            savedAtText = context.source(context.id(requestId, "source_record_id")).savedAtText
                ?: error("zero-delta saved_at missing"),
        ),
        ids = Rg09PreviewIds(
            observationId = Rg09ObservationId(context.id(requestId, "observation_id")),
            sourceRecordId = Rg09SourceRecordId(context.id(requestId, "source_record_id")),
            evidenceId = Rg09EvidenceId(context.id(requestId, "evidence_id")),
            evidenceLinkId = Rg09EvidenceLinkId(context.id(requestId, "evidence_link_id")),
            candidateId = null,
        ),
    )
    return fixtureOperation(raw, "$.zero_delta", operation)
}

private fun adaptImportPath(
    path: JsonObject,
    context: Rg09FixtureContext,
): List<Rg09FixtureOperation> = buildList {
    add(adaptImportedPending(path.getValue("pending").jsonObject, context))
    path.getValue("incomplete_confirmations").jsonArray.forEachIndexed { index, element ->
        add(adaptIncompleteImportConfirmation(element.jsonObject, context, index))
    }
    add(adaptImportedTransfer(path.getValue("transfer_confirmation").jsonObject, context))
    add(adaptImportedExplanation(path.getValue("explanation_confirmation").jsonObject, context))
}

private fun adaptImportedPending(
    raw: JsonObject,
    context: Rg09FixtureContext,
): Rg09FixtureOperation {
    val input = raw.getValue("input").jsonObject
    val source = context.source(input.string("source_id"))
    val sourceId = Rg09SourceRecordId(source.id)
    val operation = Rg09Operation.IngestImportedTransfer(
        ledgerId = context.ledgerId,
        input = Rg09IngestImportedTransferInput(
            requestId = RequestId(raw.string("id")),
            sourceId = sourceId,
            evidenceId = Rg09EvidenceId(context.id(source.id, "evidence_id")),
            candidateId = Rg09CandidateId(context.id(source.id, "candidate_id")),
            targetAccountId = AccountId(source.accountId),
            counterAccountId = AccountId(source.counterAccountId ?: error("import counter account missing")),
            amount = source.amountText.toMoney(context.currency()),
            actualOccurredAt = Instant.parse(source.actualAtText ?: error("import actual_at missing")),
            observedAt = Instant.parse(source.observedAtText),
            immutablePayloadDigest = source.immutablePayloadDigest,
            confidence = input.string("confidence"),
            explicitConfirmation = false,
            actualOccurredAtText = source.actualAtText,
            observedAtText = source.observedAtText,
        ),
        ids = Rg09ImportedTransferIds(
            sourceId = sourceId,
            evidenceId = Rg09EvidenceId(context.id(source.id, "evidence_id")),
            candidateId = Rg09CandidateId(context.id(source.id, "candidate_id")),
        ),
    )
    return fixtureOperation(raw, "$.import_path.pending", operation)
}

private fun adaptIncompleteImportConfirmation(
    raw: JsonObject,
    context: Rg09FixtureContext,
    index: Int,
): Rg09FixtureOperation {
    val input = raw.getValue("input").jsonObject
    val source = context.source("source-import-transfer-rg09")
    val missing = when (index) {
        0 -> "transaction_id"
        1 -> "target_account_id"
        2 -> "actual_at"
        3 -> "currency"
        4 -> "allocation_amount"
        else -> error("unexpected RG-09 incomplete import index $index")
    }
    val operation = Rg09Operation.IncompleteImportedTransferConfirmation(
        ledgerId = context.ledgerId,
        input = Rg09IncompleteImportedTransferConfirmationInput(
            requestId = RequestId(raw.string("id")),
            candidateId = Rg09CandidateId(context.id(source.id, "candidate_id")),
            transactionId = if (missing == "transaction_id") null else TransactionId("transaction-transfer-rg09-import"),
            targetAccountId = if (missing == "target_account_id") null else AccountId(source.accountId),
            actualOccurredAt = if (missing == "actual_at") null else Instant.parse(source.actualAtText ?: error("import actual_at missing")),
            currency = if (missing == "currency") null else context.currency(),
            explanationAmount = if (missing == "allocation_amount") null else source.amountText.toMoney(context.currency()),
            explicitConfirmation = true,
        ),
    )
    return fixtureOperation(raw, "$.import_path.incomplete_confirmations[$index]", operation)
}

private fun adaptImportedTransfer(
    raw: JsonObject,
    context: Rg09FixtureContext,
): Rg09FixtureOperation {
    val operation = adaptTransfer(raw.getValue("input").jsonObject, context, imported = true)
    return fixtureOperation(raw, "$.import_path.transfer_confirmation", operation)
}

private fun adaptImportedExplanation(
    raw: JsonObject,
    context: Rg09FixtureContext,
): Rg09FixtureOperation {
    val operation = adaptExplanation(raw.getValue("input").jsonObject, context)
    return fixtureOperation(raw, "$.import_path.explanation_confirmation", operation)
}

private fun adaptInvalidInputs(
    inputs: kotlinx.serialization.json.JsonArray,
    context: Rg09FixtureContext,
): List<Rg09FixtureOperation> = inputs.map { element ->
    val raw = element.jsonObject
    val input = raw.getValue("input").jsonObject
    val id = raw.string("id")
    val predicate = when (id) {
        "invalid-target-decimal" -> Rg09InvalidPredicate.EXACT_DECIMAL
        "invalid-target-time" -> Rg09InvalidPredicate.TIMEZONE_AWARE
        "wrong-target-timezone" -> Rg09InvalidPredicate.LEDGER_TIMEZONE
        "unknown-target-account" -> Rg09InvalidPredicate.KNOWN_ACCOUNT
        "unowned-target-account", "nonasset-target-account" -> Rg09InvalidPredicate.OWNED_REAL_ASSET
        "wrong-target-currency", "wrong-explanation-currency" -> Rg09InvalidPredicate.CURRENCY_CNY
        "wrong-adjustment-equity" -> Rg09InvalidPredicate.DEDICATED_EQUITY
        "wrong-explanation-direction" -> Rg09InvalidPredicate.SAME_DIRECTION
        "wrong-explanation-account" -> Rg09InvalidPredicate.SAME_TARGET_ACCOUNT
        "explanation-after-target" -> Rg09InvalidPredicate.BEFORE_TARGET
        "over-remaining-allocation" -> Rg09InvalidPredicate.REMAINING_CAP
        "guessed-link" -> Rg09InvalidPredicate.EXPLICIT_LINK
        "duplicate-conflicting-key" -> Rg09InvalidPredicate.IDEMPOTENCY_CONFLICT
        else -> error("unsupported RG-09 invalid input $id")
    }
    val attemptedInput = when (id) {
        "invalid-target-decimal" -> mapOf(Rg09FieldPath.ATTEMPTED_TARGET_AMOUNT.value to input.optionalString("target_amount"))
        "invalid-target-time", "wrong-target-timezone" -> mapOf(Rg09FieldPath.ATTEMPTED_TARGET_TIME.value to input.optionalString("target_observed_at"))
        "unknown-target-account", "unowned-target-account", "nonasset-target-account", "wrong-explanation-account" ->
            mapOf(Rg09FieldPath.ATTEMPTED_ACCOUNT.value to input.optionalString("account_id"))
        "wrong-target-currency", "wrong-explanation-currency" -> mapOf(Rg09FieldPath.ATTEMPTED_CURRENCY.value to input.optionalString("currency"))
        "wrong-adjustment-equity" -> mapOf(Rg09FieldPath.ATTEMPTED_EQUITY_ACCOUNT.value to input.optionalString("equity_account_id"))
        "wrong-explanation-direction" -> mapOf(Rg09FieldPath.ATTEMPTED_DIRECTION.value to input.optionalString("direction"))
        "explanation-after-target" -> mapOf(Rg09FieldPath.ATTEMPTED_ACTUAL_TIME.value to input.optionalString("actual_at"))
        "over-remaining-allocation" -> mapOf(
            Rg09FieldPath.ATTEMPTED_REQUESTED_AMOUNT.value to input.optionalString("requested_amount"),
        )
        "guessed-link" -> mapOf(Rg09FieldPath.ATTEMPTED_CONFIRMATION.value to input.optionalString("explicit_confirmation"))
        "duplicate-conflicting-key" -> mapOf(
            Rg09FieldPath.ATTEMPTED_REQUEST_ID.value to input.optionalString("request_id"),
            Rg09FieldPath.ATTEMPTED_TARGET_AMOUNT.value to input.optionalString("target_amount"),
        )
        else -> emptyMap()
    }
    fixtureOperation(
        raw,
        "$.invalid_inputs[$id]",
        Rg09Operation.InvalidInput(
            ledgerId = context.ledgerId,
            input = Rg09InvalidInput(
                requestId = RequestId(input.optionalString("request_id") ?: id),
                predicate = predicate,
                attemptedInput = attemptedInput,
            ),
        ),
    )
}

private fun adaptEvidencePath(
    path: JsonObject,
    context: Rg09FixtureContext,
): List<Rg09FixtureOperation> = path.entries.map { (name, element) ->
    val raw = element.jsonObject
    val input = raw.getValue("input").jsonObject
    val source = context.source(input.string("source_id"))
    fixtureOperation(
        raw,
        "$.evidence_path.$name",
        Rg09Operation.LinkRealPostingEvidence(
            ledgerId = context.ledgerId,
            input = Rg09LinkRealPostingEvidenceInput(
                requestId = RequestId(raw.string("id")),
                sourceId = Rg09SourceRecordId(source.id),
                evidenceId = Rg09EvidenceId(input.string("evidence_id")),
                targetPostingId = PostingId(input.string("target_posting_id")),
                accountId = AccountId(input.string("account_id")),
                amount = input.money("amount", context.currency(input.string("currency"))),
                postingSide = input.string("posting_side"),
                observedAt = input.instant("observed_at"),
                bookingAt = Instant.parse(source.actualAtText ?: error("evidence booking_at missing")),
                immutablePayloadDigest = source.immutablePayloadDigest,
                explicitConfirmation = input.boolean("explicit_confirmation"),
                observedAtText = input.string("observed_at"),
                bookingAtText = source.actualAtText,
            ),
            ids = Rg09PostingEvidenceIds(
                evidenceLinkId = Rg09EvidenceLinkId(context.id(source.id, "evidence_link_id")),
            ),
        ),
    )
}

private fun adaptRetries(
    path: JsonObject,
    availableOperations: List<Rg09FixtureOperation>,
): List<Rg09FixtureOperation> = path.getValue("retries").jsonArray.map { element ->
    val raw = element.jsonObject
    val inputId = raw.string("input_id")
    val original = availableOperations.firstOrNull { operationMatches(it.operation, inputId) }
        ?: error("RG-09 retry input has no original operation: $inputId")
    fixtureOperation(
        raw,
        "$.idempotency.retries[${raw.string("id")} ]".replace(" ]", "]"),
        original.operation,
        retryOf = inputId,
    )
}

private fun operationMatches(operation: Rg09Operation, inputId: String): Boolean = when (operation) {
    is Rg09Operation.PreviewTargetBalance ->
        operation.identity.value == inputId || operation.ids.sourceRecordId.value == inputId
    is Rg09Operation.ConfirmBalanceAdjustment -> operation.identity.value == inputId
    is Rg09Operation.ConfirmRealTransfer ->
        operation.identity.value == inputId || operation.ids.sourceRecordId.value == inputId || operation.input.sourceId?.value == inputId
    is Rg09Operation.IngestImportedTransfer ->
        operation.identity.value == inputId || operation.ids.sourceId.value == inputId
    is Rg09Operation.ConfirmImportedTransfer ->
        operation.identity.value == inputId || operation.ids.sourceRecordId.value == inputId || operation.input.sourceId?.value == inputId
    is Rg09Operation.IncompleteImportedTransferConfirmation -> operation.identity.value == inputId
    is Rg09Operation.ConfirmExplanationAllocation -> operation.identity.value == inputId
    is Rg09Operation.LinkRealPostingEvidence -> operation.identity.value == inputId || operation.input.sourceId.value == inputId
    is Rg09Operation.InvalidInput -> operation.identity.value == inputId
}

private fun fixtureOperation(
    raw: JsonObject,
    sourcePath: String,
    operation: Rg09Operation,
    retryOf: String? = null,
): Rg09FixtureOperation {
    val expected = raw.getValue("expected").jsonObject
    val expectedStatus = when {
        expected.boolean("no_change", false) -> "no_change"
        raw["operation_context"]?.jsonObject?.string("operation_type") == "idempotent_retry" -> "no_change"
        expected.boolean("accepted", false) -> "accepted"
        else -> "rejected"
    }
    return Rg09FixtureOperation(
        id = raw.string("id"),
        sourcePath = sourcePath,
        expectedStatus = expectedStatus,
        operation = operation,
        baselineStateId = raw["pre_operation_baseline"]?.jsonObject?.string("id"),
        resultStateId = expected["resulting_state"]?.jsonObject?.string("id"),
        retryOf = retryOf,
    )
}

private fun adaptPreview(
    input: JsonObject,
    context: Rg09FixtureContext,
): Rg09Operation.PreviewTargetBalance {
    val requestId = input.string("request_id")
    val source = context.source(context.id(requestId, "source_record_id"))
    val savedAtText = source.savedAtText ?: error("fixture observation saved_at missing")
    val currency = context.currency(input.string("currency"))
    val targetObservedAt = input.instant("target_observed_at")
    val candidateId = context.optionalId(requestId, "candidate_id")
    return Rg09Operation.PreviewTargetBalance(
        ledgerId = context.ledgerId,
        input = Rg09PreviewTargetBalanceInput(
            requestId = RequestId(requestId),
            accountId = AccountId(input.string("account_id")),
            targetAmount = input.money("target_amount", currency),
            targetObservedAt = targetObservedAt,
            savedAt = Instant.parse(savedAtText),
            currency = currency,
            explicitConfirmation = input.boolean("explicit_confirmation", false),
            immutablePayloadDigest = source.immutablePayloadDigest,
            ledgerFingerprint = Rg09LedgerFingerprint.digest(context.openingTransactions, targetObservedAt),
            targetObservedAtText = input.string("target_observed_at"),
            savedAtText = savedAtText,
        ),
        ids = Rg09PreviewIds(
            observationId = Rg09ObservationId(context.id(requestId, "observation_id")),
            sourceRecordId = Rg09SourceRecordId(source.id),
            evidenceId = Rg09EvidenceId(context.id(requestId, "evidence_id")),
            evidenceLinkId = Rg09EvidenceLinkId(context.id(requestId, "evidence_link_id")),
            candidateId = candidateId?.let(::Rg09CandidateId),
        ),
    )
}

private fun adaptAdjustmentConfirmation(
    input: JsonObject,
    context: Rg09FixtureContext,
): Rg09Operation.ConfirmBalanceAdjustment {
    val requestId = input.string("request_id")
    val transactionId = context.id(requestId, "transaction_id")
    return Rg09Operation.ConfirmBalanceAdjustment(
        ledgerId = context.ledgerId,
        input = Rg09ConfirmBalanceAdjustmentInput(
            requestId = RequestId(requestId),
            candidateId = Rg09CandidateId(input.string("candidate_id")),
            ledgerFingerprint = Rg09LedgerFingerprint.digest(
                context.openingTransactions,
                Instant.parse(context.targetObservedAtTextForRequest(requestId)),
            ),
            explicitConfirmation = input.boolean("explicit_confirmation"),
            confirmedAt = input.instant("confirmed_at"),
            confirmedAtText = input.string("confirmed_at"),
        ),
        ids = Rg09AdjustmentCommitIds(
            confirmationId = Rg09ConfirmationId(context.id(requestId, "confirmation_id")),
            adjustmentId = Rg09AdjustmentId(context.id(requestId, "adjustment_id")),
            transactionId = TransactionId(transactionId),
            versionId = TransactionVersionId(context.id(requestId, "version_id")),
            postingSetId = PostingSetId(context.id(requestId, "posting_set_id")),
            targetPostingId = PostingId(context.id(requestId, "target_posting_id")),
            equityPostingId = PostingId(context.id(requestId, "equity_posting_id")),
            historyId = context.id(requestId, "history_id"),
        ),
    )
}

private fun adaptTransfer(
    input: JsonObject,
    context: Rg09FixtureContext,
    imported: Boolean = false,
): Rg09Operation {
    val requestId = input.string("request_id")
    val sourceId = input.optionalString("source_id") ?: context.inputs.sourceByRequest.getValue(requestId)
    val source = context.source(sourceId)
    val transactionId = context.id(requestId, "transaction_id")
    val targetAccountId = AccountId(input.string("target_account_id"))
    val counterAccountId = AccountId(input.string("counter_account_id"))
    val operationInput = Rg09ConfirmRealTransferInput(
            requestId = RequestId(requestId),
            targetAccountId = targetAccountId,
            counterAccountId = counterAccountId,
            amount = input.money("amount", context.currency(input.string("currency"))),
            actualOccurredAt = input.instant("actual_occurred_at"),
            discoveredAt = Instant.parse(source.observedAtText),
            confirmedAt = input.instant("confirmed_at"),
            immutablePayloadDigest = source.immutablePayloadDigest,
            explicitConfirmation = input.boolean("explicit_confirmation"),
            confirmsTargetAccount = input.boolean("confirms_target_account"),
            confirmsCounterAccount = input.boolean("confirms_counter_account"),
            confirmsActualOccurredAt = input.boolean("confirms_actual_occurred_at"),
            confirmsCurrency = input.boolean("confirms_currency"),
            confirmsAmount = input.boolean("confirms_amount"),
            confirmsExplanationAllocation = input.boolean("confirms_explanation_allocation"),
            targetAccountDirection = context.directionForRequest(requestId),
            actualOccurredAtText = input.string("actual_occurred_at"),
            discoveredAtText = source.observedAtText,
            confirmedAtText = input.string("confirmed_at"),
            sourceId = input.optionalString("source_id")?.let(::Rg09SourceRecordId),
            candidateId = sourceId.takeIf { input.optionalString("source_id") != null }
                ?.let { context.optionalId(it, "candidate_id") }
                ?.let(::Rg09CandidateId),
        )
    val operationIds = Rg09TransferCommitIds(
            confirmationId = Rg09ConfirmationId(context.id(requestId, "confirmation_id")),
            sourceRecordId = Rg09SourceRecordId(source.id),
            transactionId = TransactionId(transactionId),
            versionId = TransactionVersionId(context.id(requestId, "version_id")),
            postingSetId = PostingSetId(context.id(requestId, "posting_set_id")),
            sourcePostingId = PostingId(context.id(requestId, "source_posting_id")),
            destinationPostingId = PostingId(context.id(requestId, "destination_posting_id")),
        )
    return if (imported) {
        Rg09Operation.ConfirmImportedTransfer(context.ledgerId, operationInput, operationIds)
    } else {
        Rg09Operation.ConfirmRealTransfer(context.ledgerId, operationInput, operationIds)
    }
}

private fun adaptExplanation(
    input: JsonObject,
    context: Rg09FixtureContext,
): Rg09Operation.ConfirmExplanationAllocation {
    val requestId = input.string("request_id")
    val allocationId = context.id(requestId, "allocation_id")
    val reversalTransactionId = context.id(requestId, "transaction_id")
    val currency = context.currency(input.string("currency"))
    val targetObservedAtText = input.optionalString("target_observed_at")
        ?: context.targetObservedAtTextForRequest(requestId)
    // The import confirmation has no separate discovery field, so its confirmed
    // allocation retains the immutable imported source as its provenance.
    val discoveredSourceId = context.inputs.sourceByRequest.getValue(requestId)
    val discoveredAtText = context.source(discoveredSourceId).observedAtText
    return Rg09Operation.ConfirmExplanationAllocation(
        ledgerId = context.ledgerId,
        input = Rg09ConfirmExplanationAllocationInput(
            requestId = RequestId(requestId),
            adjustmentId = Rg09AdjustmentId(context.id(requestId, "adjustment_id")),
            transactionId = TransactionId(input.string("transaction_id")),
            targetAccountId = AccountId(input.string("target_account_id")),
            actualOccurredAt = input.instant("actual_occurred_at"),
            realTransactionAmount = input.money("real_transaction_amount", "amount", currency),
            targetObservedAt = Instant.parse(targetObservedAtText),
            explanationAmount = input.money("explanation_amount", "explanation_allocation", currency),
            confirmedAt = input.instant("confirmed_at"),
            explicitConfirmation = input.boolean("explicit_confirmation"),
            confirmsTargetAccount = input.boolean("confirms_target_account"),
            confirmsActualOccurredAt = input.boolean("confirms_actual_occurred_at"),
            confirmsRealTransactionAmount = input.boolean("confirms_real_transaction_amount", input.boolean("confirms_amount")),
            confirmsCurrency = input.boolean("confirms_currency"),
            confirmsTargetObservedAt = input.boolean("confirms_target_observed_at", true),
            confirmsAllocationDirection = input.boolean("confirms_allocation_direction", input.boolean("confirms_explanation_allocation")),
            confirmsExplanationAmount = input.boolean("confirms_explanation_amount", input.boolean("confirms_explanation_allocation")),
            actualOccurredAtText = input.string("actual_occurred_at"),
            targetObservedAtText = targetObservedAtText,
            confirmedAtText = input.string("confirmed_at"),
            discoveredAt = Instant.parse(discoveredAtText),
            discoveredAtText = discoveredAtText,
        ),
        ids = Rg09AllocationCommitIds(
            confirmationId = Rg09ConfirmationId(context.id(requestId, "confirmation_id")),
            allocationId = Rg09AllocationId(allocationId),
            reversalTransactionId = TransactionId(reversalTransactionId),
            reversalVersionId = TransactionVersionId(context.id(requestId, "version_id")),
            reversalPostingSetId = PostingSetId(context.id(requestId, "reversal_posting_set_id")),
            reversalTargetPostingId = PostingId(context.id(requestId, "reversal_target_posting_id")),
            reversalEquityPostingId = PostingId(context.id(requestId, "reversal_equity_posting_id")),
            adjustmentAuditLinkId = Rg09AuditLinkId(context.id(requestId, "adjustment_audit_link_id")),
            explanationAuditLinkId = Rg09AuditLinkId(context.id(requestId, "explanation_audit_link_id")),
            reversalAuditLinkId = Rg09AuditLinkId(context.id(requestId, "reversal_audit_link_id")),
            historyId = context.id(requestId, "history_id"),
        ),
    )
}

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
                else -> error("unsupported RG-09 account type")
            },
            currency = CurrencyUnit(account.string("currency"), account.int("precision", 2)),
            ownedByUser = account.boolean("owned_by_user"),
            realAccount = account.boolean("financial"),
            systemRole = account.optionalString("system_role"),
        )
    }
    val categories = raw.getValue("categories").jsonArray.map { element ->
        val category = element.jsonObject
        Category(
            id = CategoryId(category.string("id")),
            ledgerId = ledgerId,
            parentId = category.optionalString("parent_id")?.let(::CategoryId),
            postingAccountId = category.optionalString("posting_account_id")?.let(::AccountId),
            active = category.boolean("active"),
        )
    }
    return when (val result = LedgerCatalog.create(accounts, categories)) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> error("invalid RG-09 catalog")
    }
}

private fun openingTransaction(raw: JsonObject, ledgerId: LedgerId): Rg09FormalTransactionRecord {
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
        is DomainResult.Failure -> error("invalid RG-09 opening posting set")
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
        is DomainResult.Failure -> error("invalid RG-09 opening transaction")
    }
    return Rg09FormalTransactionRecord(
        formal,
        transaction.instant("created_at"),
        createdAtText = transaction.string("created_at"),
        effectiveAtText = transaction.string("effective_at"),
    )
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.string(key: String, fallback: String): String = this[key]?.jsonPrimitive?.content ?: fallback

private fun JsonObject.optionalString(key: String): String? =
    this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

private fun JsonObject.boolean(key: String): Boolean = getValue(key).jsonPrimitive.content.toBooleanStrict()

private fun JsonObject.boolean(key: String, fallback: Boolean): Boolean =
    this[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: fallback

private fun JsonObject.booleanOrNull(key: String): Boolean? = this[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull()

private fun JsonObject.int(key: String, fallback: Int): Int = this[key]?.jsonPrimitive?.content?.toIntOrNull() ?: fallback

private fun JsonObject.instant(key: String): Instant = Instant.parse(string(key))

private fun JsonObject.money(key: String, currency: CurrencyUnit): Money = string(key).toMoney(currency)

private fun JsonObject.money(key: String, fallbackKey: String, currency: CurrencyUnit): Money =
    (optionalString(key) ?: string(fallbackKey)).toMoney(currency)

private fun String.toMoney(currency: CurrencyUnit): Money {
    require(matches(Regex("[+-]?\\d+\\.\\d{2}"))) { "RG-09 requires exact two-place decimal" }
    val negative = startsWith("-")
    val unsigned = removePrefix("+").removePrefix("-")
    val parts = unsigned.split('.')
    val major = parts[0].toLongOrNull()
        ?: error("RG-09 amount exceeds minor-unit range")
    val fraction = parts[1].toLongOrNull()
        ?: error("RG-09 amount exceeds minor-unit range")
    val minor = checkedAdd(checkedMultiply(major, 100L), fraction)
        ?: error("RG-09 amount exceeds minor-unit range")
    val signedMinor = if (negative) {
        checkedNegate(minor) ?: error("RG-09 amount exceeds minor-unit range")
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
