package com.unifiedledger.application

import com.unifiedledger.domain.*
import kotlinx.serialization.json.*
import kotlin.time.Instant

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
        val manualPostingArray = manualTx.array("postings", "$.manual_lifecycle.ordered_operations[0].expected.transaction.postings")
        val repaymentPostingArray = repaymentTx.array("postings", "$.manual_lifecycle.ordered_operations[1].expected.transaction.postings")
        if (manualPostingArray.size != 3) fail("$.manual_lifecycle.ordered_operations[0].expected.transaction.postings")
        if (repaymentPostingArray.size != 2) fail("$.manual_lifecycle.ordered_operations[1].expected.transaction.postings")
        val manualPostings = manualPostingArray.mapIndexed { i, e -> e.objectAt("postings[$i]").string("id", "postings[$i].id") }
        val repaymentPostings = repaymentPostingArray.mapIndexed { i, e -> e.objectAt("postings[$i]").string("id", "postings[$i].id") }
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
        val importOperations = decodeImportOperations(root, ledgerId, currency)
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
                importOperations,
            ),
        )
    } catch (failure: Rg04DecodeFailure) { bad(failure.path, failure.reason) }
      catch (_: IllegalArgumentException) { bad("$", Rg04RawJsonContractErrorReason.INVALID_VALUE) }
      catch (_: IndexOutOfBoundsException) { bad("$", Rg04RawJsonContractErrorReason.INVALID_VALUE) }
      catch (_: NoSuchElementException) { bad("$", Rg04RawJsonContractErrorReason.INVALID_VALUE) }
}

private fun decodeImportOperations(
    root: JsonObject,
    ledgerId: LedgerId,
    currency: CurrencyUnit,
): List<Rg04DecodedImportOperation> {
    val lifecycle = root.obj("import_lifecycle", "$.import_lifecycle")
    val ordered = lifecycle.array("ordered_operations", "$.import_lifecycle.ordered_operations")
    if (ordered.size != 3) fail("$.import_lifecycle.ordered_operations")
    val sourceContainer = ordered[0].objectAt("$.import_lifecycle.ordered_operations[0]")
    val confirmationContainer = ordered[1].objectAt("$.import_lifecycle.ordered_operations[1]")
    val mirrorContainer = ordered[2].objectAt("$.import_lifecycle.ordered_operations[2]")

    val sourceInput = sourceContainer.obj("input", "$.import_lifecycle.ordered_operations[0].input")
    sourceContainer.closed("$.import_lifecycle.ordered_operations[0]", setOf("sequence", "id", "input", "expected"))
    if (sourceContainer.integer("sequence", "$.import_lifecycle.ordered_operations[0].sequence") != 1) fail("$.import_lifecycle.ordered_operations[0].sequence")
    sourceContainer.obj("expected", "$.import_lifecycle.ordered_operations[0].expected").closed("$.import_lifecycle.ordered_operations[0].expected", setOf("candidate", "new_candidate_count", "formal_effects"))
    sourceContainer.obj("expected", "$.import_lifecycle.ordered_operations[0].expected").obj("candidate", "$.import_lifecycle.ordered_operations[0].expected.candidate")
        .closed("$.import_lifecycle.ordered_operations[0].expected.candidate", setOf("id", "status", "kind", "confidence", "source_refs", "evidence_refs", "provenance", "requires_confirmation"))
    sourceInput.closed("$.import_lifecycle.ordered_operations[0].input", setOf("request_id", "kind", "source_record"))
    val sourceRecord = sourceInput.obj("source_record", "$.import_lifecycle.ordered_operations[0].input.source_record")
    sourceRecord.closed("$.import_lifecycle.ordered_operations[0].input.source_record", setOf("id", "evidence_id", "observed_at", "total_amount", "currency", "suggested_category_id", "funding_components", "completeness"))
    if (sourceInput.string("kind", "$.import_lifecycle.ordered_operations[0].input.kind") != "import_source_record") fail("$.import_lifecycle.ordered_operations[0].input.kind")
    if (sourceRecord.string("completeness", "$.import_lifecycle.ordered_operations[0].input.source_record.completeness") != "complete") fail("$.import_lifecycle.ordered_operations[0].input.source_record.completeness")
    val sourceExpected = sourceContainer.obj("expected", "$.import_lifecycle.ordered_operations[0].expected")
    val candidateExpected = sourceExpected.obj("candidate", "$.import_lifecycle.ordered_operations[0].expected.candidate")
    val sourceCurrency = exactCurrency(sourceRecord.string("currency", "source.currency"), currency)
    val completeFundingArray = sourceRecord.array("funding_components", "source.funding_components")
    if (completeFundingArray.size != 2) fail("source.funding_components")
    val completeFunding = completeFundingArray.mapIndexed { index, element ->
        val item = element.objectAt("source.funding_components[$index]")
        item.closed("source.funding_components[$index]", setOf("account_id", "funding_amount", "currency", "evidence_available"))
        Rg04ImportFunding(
            AccountId(item.string("account_id", "source.funding_components[$index].account_id")),
            exactMoney(item.string("funding_amount", "source.funding_components[$index].funding_amount"), exactCurrency(item.string("currency", "source.funding_components[$index].currency"), currency), "source.funding_components[$index].funding_amount"),
            item.boolean("evidence_available", "source.funding_components[$index].evidence_available"),
        )
    }
    val completeSnapshot = Rg04ImportSourceSnapshot(
        ledgerId = ledgerId,
        requestId = RequestId(sourceInput.string("request_id", "source.request_id")),
        sourceId = Rg04SourceId(sourceRecord.string("id", "source.id")),
        evidenceId = Rg04EvidenceId(sourceRecord.string("evidence_id", "source.evidence_id")),
        observedAt = exactInstant(sourceRecord.string("observed_at", "source.observed_at"), "source.observed_at"),
        observedAtText = sourceRecord.string("observed_at", "source.observed_at"),
        total = exactMoney(sourceRecord.string("total_amount", "source.total_amount"), sourceCurrency, "source.total_amount"),
        suggestedCategoryId = CategoryId(sourceRecord.string("suggested_category_id", "source.suggested_category_id")),
        funding = completeFunding,
        completeness = Rg04ImportCompleteness.COMPLETE,
        confidence = candidateExpected.string("confidence", "candidate.confidence"),
        candidateKind = candidateExpected.string("kind", "candidate.kind"),
        candidateId = Rg04CandidateId(candidateExpected.string("id", "candidate.id")),
        candidateStatusId = rg04MigrationId(
            rg04RootId("$.import_lifecycle", sourceRecord.string("id", "source.id")),
            "candidate_status",
            "$.import_lifecycle.ordered_operations[*].expected.candidate.status",
            candidateExpected.string("id", "candidate.id"),
        ),
    )

    val confirmationInput = confirmationContainer.obj("input", "confirmation.input")
    confirmationInput.closed("confirmation.input", setOf("request_id", "kind", "candidate_id", "category_id", "confirmed_funding_components", "confirmed"))
    val confirmationExpected = confirmationContainer.obj("expected", "confirmation.expected")
    confirmationContainer.closed("$.import_lifecycle.ordered_operations[1]", setOf("sequence", "id", "input", "expected"))
    if (confirmationContainer.integer("sequence", "$.import_lifecycle.ordered_operations[1].sequence") != 2) fail("$.import_lifecycle.ordered_operations[1].sequence")
    confirmationExpected.closed("confirmation.expected", setOf("candidate_status", "transaction", "association_group", "association_group_count", "effective_transaction_count", "funding_effect_count", "expense_effect_count", "consumption_effect_count", "cash_flow_effect_count", "balances", "liability_display", "statistics", "reconciliation", "source_refs", "evidence_refs", "evidence_links"))
    val transaction = confirmationExpected.obj("transaction", "confirmation.expected.transaction")
    val postings = transaction.array("postings", "confirmation.expected.transaction.postings")
    if (postings.size != 3) fail("confirmation.expected.transaction.postings")
    val relation = confirmationExpected.obj("association_group", "confirmation.expected.association_group")
    val confirmationLinks = confirmationExpected.array("evidence_links", "confirmation.expected.evidence_links")
    if (confirmationLinks.size != 1) fail("confirmation.expected.evidence_links")
    val evidenceLink = confirmationLinks[0].objectAt("confirmation.expected.evidence_links[0]")
    if (confirmationInput.string("kind", "confirmation.input.kind") != "explicit_candidate_completion_and_confirmation") fail("confirmation.input.kind")
    val confirmationFundingArray = confirmationInput.array("confirmed_funding_components", "confirmation.input.confirmed_funding_components")
    if (confirmationFundingArray.size != 2) fail("confirmation.input.confirmed_funding_components")
    val confirmationFunding = confirmationFundingArray.mapIndexed { index, element ->
        val item = element.objectAt("confirmation.input.confirmed_funding_components[$index]")
        item.closed("confirmation.input.confirmed_funding_components[$index]", setOf("account_id", "funding_amount", "currency"))
        Rg04FundingSnapshot(
            AccountId(item.string("account_id", "confirmation.funding[$index].account_id")),
            exactMoney(item.string("funding_amount", "confirmation.funding[$index].funding_amount"), exactCurrency(item.string("currency", "confirmation.funding[$index].currency"), currency), "confirmation.funding[$index].funding_amount"),
        )
    }
    val transactionPostings = postings.mapIndexed { index, element -> element.objectAt("confirmation.expected.transaction.postings[$index]") }
    val importRootId = rg04RootId("$.import_lifecycle", completeSnapshot.sourceId.value)
    val confirmationRequestId = confirmationInput.string("request_id", "confirmation.request_id")
    val confirmationSnapshot = Rg04ImportConfirmationSnapshot(
        ledgerId = ledgerId,
        requestId = RequestId(confirmationRequestId),
        candidateId = Rg04CandidateId(confirmationInput.string("candidate_id", "confirmation.candidate_id")),
        categoryId = CategoryId(confirmationInput.string("category_id", "confirmation.category_id")),
        funding = confirmationFunding,
        confirmed = confirmationInput.boolean("confirmed", "confirmation.confirmed"),
        formalIds = MixedPaymentExpenseIds(
            TransactionId(transaction.string("id", "confirmation.transaction.id")),
            TransactionVersionId(transaction.string("current_version_id", "confirmation.transaction.current_version_id")),
            PostingSetId(transaction.string("posting_set_id", "confirmation.transaction.posting_set_id")),
            PostingId(transactionPostings[0].string("id", "confirmation.transaction.postings[0].id")),
            transactionPostings.drop(1).map { PostingId(it.string("id", "confirmation.transaction.postings.id")) },
        ),
        confirmationId = rg04MigrationId(
            importRootId,
            "confirmation",
            "$.import_lifecycle.ordered_operations[*].expected.candidate_status",
            confirmationRequestId,
        ),
        confirmedStatusId = rg04MigrationId(
            importRootId,
            "candidate_status",
            "$.import_lifecycle.ordered_operations[*].expected.candidate_status",
            confirmationRequestId,
        ),
        relationId = relation.string("id", "confirmation.association_group.id"),
        relationDisplayName = relation.string("display_name", "confirmation.association_group.display_name"),
        assetEvidenceLinkId = evidenceLink.string("id", "confirmation.evidence_links[0].id"),
        assetReconciliationId = rg04MigrationId(
            importRootId,
            "posting_reconciliation",
            "$.import_lifecycle.ordered_operations[*].expected.reconciliation",
            transactionPostings[1].string("id", "confirmation.transaction.postings[1].id"),
        ),
        liabilityReconciliationId = rg04MigrationId(
            importRootId,
            "posting_reconciliation",
            "$.import_lifecycle.ordered_operations[*].expected.reconciliation",
            transactionPostings[2].string("id", "confirmation.transaction.postings[2].id"),
        ),
    )

    val mirrorInput = mirrorContainer.obj("input", "mirror.input")
    mirrorContainer.closed("$.import_lifecycle.ordered_operations[2]", setOf("sequence", "id", "input", "expected"))
    if (mirrorContainer.integer("sequence", "$.import_lifecycle.ordered_operations[2].sequence") != 3) fail("$.import_lifecycle.ordered_operations[2].sequence")
    mirrorExpectedClosed(mirrorContainer)
    mirrorInput.closed("mirror.input", setOf("request_id", "kind", "source_record_id", "evidence_id", "account_id", "amount", "currency", "observed_at"))
    val mirrorExpected = mirrorContainer.obj("expected", "mirror.expected")
    if (mirrorInput.string("kind", "mirror.input.kind") != "import_mirror_evidence") fail("mirror.input.kind")
    val mirrorLinks = mirrorExpected.array("evidence_links", "mirror.expected.evidence_links")
    if (mirrorLinks.size != 2) fail("mirror.expected.evidence_links")
    val mirrorCurrency = exactCurrency(mirrorInput.string("currency", "mirror.currency"), currency)
    val mirrorSnapshot = Rg04ImportMirrorSnapshot(
        ledgerId = ledgerId,
        requestId = RequestId(mirrorInput.string("request_id", "mirror.request_id")),
        sourceId = Rg04SourceId(mirrorInput.string("source_record_id", "mirror.source_record_id")),
        evidenceId = Rg04EvidenceId(mirrorInput.string("evidence_id", "mirror.evidence_id")),
        observedAt = exactInstant(mirrorInput.string("observed_at", "mirror.observed_at"), "mirror.observed_at"),
        observedAtText = mirrorInput.string("observed_at", "mirror.observed_at"),
        accountId = AccountId(mirrorInput.string("account_id", "mirror.account_id")),
        amount = exactMoney(mirrorInput.string("amount", "mirror.amount"), mirrorCurrency, "mirror.amount"),
        evidenceLinkId = mirrorLinks[1].objectAt("mirror.expected.evidence_links[1]").string("id", "mirror.evidence_links[1].id"),
    )

    val missing = root.obj("missing_funding_leg", "$.missing_funding_leg")
    val missingInput = missing.obj("input", "$.missing_funding_leg.input")
    missingInput.closed("$.missing_funding_leg.input", setOf("request_id", "kind", "source_record"))
    val missingRecord = missingInput.obj("source_record", "$.missing_funding_leg.input.source_record")
    missingRecord.closed("$.missing_funding_leg.input.source_record", setOf("id", "evidence_id", "observed_at", "total_amount", "known_asset_funding_amount", "currency", "completeness"))
    val missingExpected = missing.obj("expected", "$.missing_funding_leg.expected").obj("candidate", "$.missing_funding_leg.expected.candidate")
    if (missingRecord.string("completeness", "$.missing_funding_leg.input.source_record.completeness") != "missing_funding_leg") fail("$.missing_funding_leg.input.source_record.completeness")
    val missingCurrency = exactCurrency(missingRecord.string("currency", "missing.currency"), currency)
    val known = exactMoney(missingRecord.string("known_asset_funding_amount", "missing.known_asset_funding_amount"), missingCurrency, "missing.known_asset_funding_amount")
    val total = exactMoney(missingRecord.string("total_amount", "missing.total_amount"), missingCurrency, "missing.total_amount")
    val missingSnapshot = Rg04ImportSourceSnapshot(
        ledgerId = ledgerId,
        requestId = RequestId(missingInput.string("request_id", "missing.request_id")),
        sourceId = Rg04SourceId(missingRecord.string("id", "missing.source_id")),
        evidenceId = Rg04EvidenceId(missingRecord.string("evidence_id", "missing.evidence_id")),
        observedAt = exactInstant(missingRecord.string("observed_at", "missing.observed_at"), "missing.observed_at"),
        observedAtText = missingRecord.string("observed_at", "missing.observed_at"),
        total = total,
        suggestedCategoryId = null,
        funding = listOf(
            Rg04ImportFunding(AccountId("asset-bank-a"), known, true),
            Rg04ImportFunding(null, Money.ofMinor(total.minorUnits - known.minorUnits, missingCurrency), false),
        ),
        completeness = Rg04ImportCompleteness.MISSING_FUNDING_LEG,
        confidence = missingExpected.string("confidence", "missing.candidate.confidence"),
        candidateKind = missingExpected.string("kind", "missing.candidate.kind"),
        candidateId = Rg04CandidateId(missingExpected.string("id", "missing.candidate.id")),
        candidateStatusId = rg04MigrationId(
            rg04RootId("$.missing_funding_leg", missingRecord.string("id", "missing.source_id")),
            "candidate_status",
            "$.missing_funding_leg.expected.candidate.status",
            missingExpected.string("id", "missing.candidate.id"),
        ),
    )

    return listOf(
        Rg04DecodedImportOperation.Source(completeSnapshot, Rg04Expected.Accepted),
        Rg04DecodedImportOperation.Source(completeSnapshot, Rg04Expected.NoChange),
        Rg04DecodedImportOperation.Confirm(confirmationSnapshot, Rg04Expected.Accepted),
        Rg04DecodedImportOperation.Confirm(confirmationSnapshot, Rg04Expected.NoChange),
        Rg04DecodedImportOperation.Mirror(mirrorSnapshot, Rg04Expected.Accepted),
        Rg04DecodedImportOperation.Mirror(mirrorSnapshot, Rg04Expected.NoChange),
        Rg04DecodedImportOperation.Source(missingSnapshot, Rg04Expected.Accepted),
        Rg04DecodedImportOperation.Source(missingSnapshot, Rg04Expected.NoChange),
    )
}

private fun exactCurrency(code: String, expected: CurrencyUnit): CurrencyUnit {
    if (code != expected.code) fail("$.currency")
    return CurrencyUnit(code, expected.precision)
}

private fun exactMoney(text: String, currency: CurrencyUnit, path: String): Money =
    parseImportMoney(text, currency) ?: fail(path)

private fun exactInstant(text: String, path: String) = try {
    Instant.parse(text)
} catch (_: IllegalArgumentException) {
    fail(path)
}

private fun parseImportMoney(text: String, currency: CurrencyUnit): Money? {
    val match = Regex("^-?(0|[1-9][0-9]*)\\.([0-9]{${currency.precision}})$").matchEntire(text) ?: return null
    val negative = text.startsWith('-')
    val minor = (if (negative) "-${match.groupValues[1]}${match.groupValues[2]}" else "${match.groupValues[1]}${match.groupValues[2]}").toLongOrNull()
        ?: return null
    return Money.ofMinor(minor, currency)
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
        val fundingArray = input.array("funding_components", "$path.input.funding_components")
        if (fundingArray.size != 2) fail("$path.input.funding_components")
        val funding = fundingArray.mapIndexed { i, e -> funding(e.objectAt("$path.input.funding_components[$i]"), "$path.input.funding_components[$i]") }
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
    val funding = input["funding_components"]?.let { array ->
        val values = array.arrayAt("$path.input.funding_components")
        if (values.size != 2) fail("$path.input.funding_components")
        values.mapIndexed { i, e -> funding(e.objectAt("$path.input.funding_components[$i]"), "$path.input.funding_components[$i]") }
    } ?: listOf(
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
private fun mirrorExpectedClosed(container: JsonObject) {
    container.obj("expected", "mirror.expected").closed("mirror.expected", setOf("merged_into_transaction_id", "current_version_id", "effective_posting_set_id", "posting_ids", "new_transaction_count", "new_posting_count", "new_version_count", "new_association_group_count", "association_group_count", "association_group", "effective_transaction_count", "funding_effect_count", "expense_effect_count", "consumption_effect_count", "cash_flow_effect_count", "balances", "statistics", "reconciliation", "source_refs", "evidence_refs", "evidence_links"))
}
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
    val rootId = rg04RootId("$.invalid_manual_inputs[*]", rawId)
    return rg04MigrationId(rootId, "request", "$.invalid_manual_inputs[*].id", rawId)
}

private fun rg04RootId(locator: String, occurrence: String): String =
    rg04UuidV5("RG-04\n@root\nroot\n$locator\noccurrence=$occurrence")

private fun rg04MigrationId(rootId: String, kind: String, locator: String, occurrence: String): String =
    rg04UuidV5("RG-04\n$rootId\n$kind\n$locator\noccurrence=$occurrence")

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
