package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseIds
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.InstallmentPaymentId
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.StagedPaymentCreationIds
import com.unifiedledger.domain.StagedPaymentFulfillment
import com.unifiedledger.domain.StagedPaymentHistoryId
import com.unifiedledger.domain.StagedPaymentInstallmentIds
import com.unifiedledger.domain.StagedPaymentLifecycleId
import com.unifiedledger.domain.StagedPaymentRelationId
import com.unifiedledger.domain.StagedPaymentRole
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

/** A typed operation adapted from one immutable v1 fixture occurrence. */
data class Rg06FixtureOperation(
    val id: String,
    val rootPurpose: String,
    val sourcePath: String,
    val expectedStatus: String,
    val operation: Rg06Operation,
)

data class Rg06FixtureReplaySummary(
    val operations: List<Rg06FixtureOperation>,
    val accepted: Int,
    val noChange: Int,
    val rejected: Int,
)

/**
 * Adapts the frozen v1 fixture into the strict application boundary.
 *
 * The v1 file is intentionally the only source for operation payloads. Generated IDs are
 * read from the v1 expected records where available, while retry occurrences reuse the exact
 * original typed operation. Candidate confirmation time is read from the frozen provenance
 * record; it is never inferred from payment or source time.
 */
fun replayRg06Fixture(raw: String): Rg06FixtureReplaySummary {
    val fixture = Json.parseToJsonElement(raw).jsonObject
    val ledgerId = LedgerId(fixture.getValue("case").jsonObject.string("ledger_id"))
    val operations =
        buildList {
            addAll(adaptImportPath(fixture.getValue("import_path").jsonObject, ledgerId))
            addAll(adaptManualPath(fixture.getValue("manual_path").jsonObject, ledgerId))
            addAll(adaptInvalidInputs(fixture.getValue("invalid_inputs").jsonArray, ledgerId))
        }
    return Rg06FixtureReplaySummary(
        operations = operations,
        accepted = operations.count { it.expectedStatus == "accepted" },
        noChange = operations.count { it.expectedStatus == "no_change" },
        rejected = operations.count { it.expectedStatus == "rejected" },
    )
}

private fun adaptImportPath(
    path: JsonObject,
    ledgerId: LedgerId,
): List<Rg06FixtureOperation> {
    val sourceOperations = path.getValue("ordered_operations").jsonArray.map { it.jsonObject }
    val provenance =
        path
            .getValue("canonical_final_state")
            .jsonObject
            .getValue("candidates")
            .jsonArray
            .map { it.jsonObject }
            .mapNotNull { candidate ->
                candidate["confirmation_provenance"]?.takeUnless { it is JsonNull }?.jsonObject?.let {
                    candidate.string("id") to fixtureInstant(it.string("confirmed_at"))
                }
            }.toMap()
    val sourcePaymentAt =
        path
            .getValue("canonical_final_state")
            .jsonObject
            .getValue("source_records")
            .jsonArray
            .map { it.jsonObject }
            .associate { source ->
                source.string("id") to source.string("source_payment_at")
            }
    val original =
        sourceOperations.mapIndexed { index, operation ->
            adaptImportOperation(operation, index, ledgerId, provenance, sourcePaymentAt)
        }
    val retries =
        listOf(
            "operation-rg06-import-retry-deposit-intake" to 0,
            "operation-rg06-import-retry-deposit-confirm" to 2,
            "operation-rg06-import-retry-final-intake" to 3,
            "operation-rg06-import-retry-final-confirm" to 4,
            "operation-rg06-import-retry-final-mirror" to 5,
        ).map { (id, index) ->
            val source = original[index]
            source.copy(id = id, expectedStatus = "no_change")
        }
    return original + retries
}

private fun adaptManualPath(
    path: JsonObject,
    ledgerId: LedgerId,
): List<Rg06FixtureOperation> {
    val sourceOperations = path.getValue("ordered_operations").jsonArray.map { it.jsonObject }
    val original =
        sourceOperations.mapIndexed { index, operation ->
            adaptManualOperation(operation, index, ledgerId)
        }
    val retries =
        listOf(
            "operation-rg06-manual-retry-create" to 0,
            "operation-rg06-manual-retry-deposit" to 1,
            "operation-rg06-manual-retry-fulfilled" to 2,
            "operation-rg06-manual-retry-final" to 3,
            "operation-rg06-manual-retry-completion" to 4,
        ).map { (id, index) ->
            val source = original[index]
            source.copy(id = id, expectedStatus = "no_change")
        }
    return original + retries
}

private fun adaptManualOperation(
    operation: JsonObject,
    index: Int,
    ledgerId: LedgerId,
): Rg06FixtureOperation {
    val input = operation.getValue("input").jsonObject
    val expected = operation.getValue("expected").jsonObject
    val name =
        when (index) {
            0 -> "manual-create-group"
            1 -> "manual-save-deposit"
            2 -> "manual-mark-fulfilled"
            3 -> "manual-save-final"
            4 -> "manual-confirm-completion"
            5 -> "manual-deposit-evidence"
            6 -> "manual-final-evidence"
            else -> error("unsupported RG-06 manual operation index $index")
        }
    val typed =
        when (index) {
            0 ->
                Rg06Operation.CreateStagedPayment(
                    ledgerId = ledgerId,
                    input =
                        Rg06CreateStagedPaymentInput(
                            requestId = RequestId(input.string("request_id")),
                            totalAmount = input.money("total_amount"),
                            categoryId = input.optionalString("category_id")?.let(::CategoryId),
                            createdAt = input.instant("created_at"),
                        ),
                    ids =
                        StagedPaymentCreationIds(
                            relationId = StagedPaymentRelationId(expected.getValue("group").jsonObject.string("id")),
                            lifecycleId = StagedPaymentLifecycleId("lifecycle-rg06-manual"),
                            historyId = StagedPaymentHistoryId("history-rg06-manual-created"),
                        ),
                )
            1, 3 -> {
                val payment = expected.getValue("payment").jsonObject
                val role = StagedPaymentRole.valueOf(input.string("payment_role").uppercase())
                Rg06Operation.RecordStagedPaymentInstallment(
                    ledgerId = ledgerId,
                    input =
                        Rg06RecordStagedPaymentInstallmentInput(
                            requestId = RequestId(input.string("request_id")),
                            relationId = StagedPaymentRelationId(input.string("association_group_id")),
                            paymentRole = role,
                            paymentAmount = input.money("payment_amount"),
                            fundingAccountId = AccountId(input.string("funding_account_id")),
                            actualPaymentAt = input.instant("actual_payment_at"),
                        ),
                    ids =
                        Rg06ManualInstallmentCommitIds(
                            confirmationId = Rg06ConfirmationId("confirmation-${role.name.lowercase()}"),
                            paymentIds = paymentIds(payment, expected),
                            reconciliationId =
                                Rg06ReconciliationId(
                                    "reconciliation-rg06-manual-${role.name.lowercase()}",
                                ),
                        ),
                )
            }
            2 ->
                Rg06Operation.ChangeStagedPaymentFulfillment(
                    ledgerId = ledgerId,
                    input =
                        Rg06ChangeStagedPaymentFulfillmentInput(
                            requestId = RequestId(input.string("request_id")),
                            relationId = StagedPaymentRelationId(input.string("association_group_id")),
                            fulfillmentStatus = StagedPaymentFulfillment.valueOf(input.string("fulfillment_status").uppercase()),
                            occurredAt = input.instant("occurred_at"),
                        ),
                    historyId = lastHistoryId(expected),
                )
            4 ->
                Rg06Operation.ConfirmStagedPaymentCompletion(
                    ledgerId = ledgerId,
                    input =
                        Rg06ConfirmStagedPaymentCompletionInput(
                            requestId = RequestId(input.string("request_id")),
                            relationId = StagedPaymentRelationId(input.string("association_group_id")),
                            confirmed = input.bool("confirmed"),
                            occurredAt = input.instant("occurred_at"),
                        ),
                    historyId = lastHistoryId(expected),
                )
            5, 6 ->
                Rg06Operation.LinkStagedPaymentEvidence(
                    ledgerId = ledgerId,
                    input =
                        Rg06LinkStagedPaymentEvidenceInput(
                            sourceId = Rg06SourceId(input.string("source_id")),
                            evidenceId = Rg06EvidenceId(input.string("evidence_id")),
                            paymentId = InstallmentPaymentId(input.string("payment_id")),
                            postingId = PostingId(input.string("posting_id")),
                        ),
                    evidenceLinkId =
                        Rg06EvidenceLinkId(
                            "match-rg06-manual-${if (index == 5) "deposit" else "final"}",
                        ),
                )
            else -> error("unsupported RG-06 manual operation index $index")
        }
    return Rg06FixtureOperation(
        id = "operation-rg06-$name",
        rootPurpose = "rg06_manual_staged_payment_lifecycle",
        sourcePath = "$.manual_path.ordered_operations[$index]",
        expectedStatus = "accepted",
        operation = typed,
    )
}

private fun adaptImportOperation(
    operation: JsonObject,
    index: Int,
    ledgerId: LedgerId,
    provenance: Map<String, Instant>,
    sourcePaymentAt: Map<String, String>,
): Rg06FixtureOperation {
    val input = operation.getValue("input").jsonObject
    val expected = operation.getValue("expected").jsonObject
    val name =
        when (index) {
            0 -> "import-intake-deposit"
            1 -> "import-intake-ambiguous"
            2 -> "import-confirm-deposit"
            3 -> "import-intake-final"
            4 -> "import-confirm-final"
            5 -> "import-merge-final-mirror"
            else -> error("unsupported RG-06 import operation index $index")
        }
    val typed =
        when (index) {
            0, 1, 3 ->
                Rg06Operation.IngestStagedPaymentBankFact(
                    ledgerId = ledgerId,
                    input =
                        Rg06IngestStagedPaymentBankFactInput(
                            sourceId = Rg06SourceId(input.string("source_id")),
                            evidenceId = Rg06EvidenceId(input.string("evidence_id")),
                            sourcePaymentAt = input.instant("source_payment_at"),
                            sourcePaymentAtText = input.string("source_payment_at"),
                            amount = input.money("amount"),
                            suggestedPaymentRole = input.optionalString("suggested_payment_role")?.let { StagedPaymentRole.valueOf(it.uppercase()) },
                        ),
                    ids =
                        Rg06IngestCommitIds(
                            candidateId = Rg06CandidateId(expected.getValue("candidate").jsonObject.string("id")),
                            pendingStatusId =
                                Rg06CandidateStatusId(
                                    pendingCandidateStatusId(expected.getValue("candidate").jsonObject.string("id")),
                                ),
                        ),
                )
            2, 4 -> {
                val candidate = expected.getValue("payment").jsonObject
                val candidateId = Rg06CandidateId(input.string("candidate_id"))
                val suffix = candidateId.value.removePrefix("candidate-")
                Rg06Operation.ConfirmStagedPaymentCandidate(
                    ledgerId = ledgerId,
                    input =
                        Rg06ConfirmStagedPaymentCandidateInput(
                            requestId = RequestId(input.string("request_id")),
                            candidateId = candidateId,
                            relationId = StagedPaymentRelationId(input.string("association_group_id")),
                            paymentRole = StagedPaymentRole.valueOf(input.string("payment_role").uppercase()),
                            categoryId = CategoryId(input.string("category_id")),
                            fundingAccountId = AccountId(input.string("funding_account_id")),
                            exactBindingConfirmed = input.bool("exact_binding_confirmed"),
                            confirmedAt = provenance[candidateId.value],
                        ),
                    ids =
                        Rg06CandidateConfirmationCommitIds(
                            confirmationId = Rg06ConfirmationId("confirmation-${candidateId.value}"),
                            paymentIds = paymentIds(candidate, expected),
                            evidenceLinkId = Rg06EvidenceLinkId("match-$suffix"),
                            confirmedStatusId = Rg06CandidateStatusId("candidate-status-${candidateId.value}-confirmed"),
                            reconciliationId = Rg06ReconciliationId("reconciliation-$suffix"),
                        ),
                )
            }
            5 ->
                Rg06Operation.MergeStagedPaymentMirrorEvidence(
                    ledgerId = ledgerId,
                    input =
                        Rg06MergeStagedPaymentMirrorEvidenceInput(
                            sourceId = Rg06SourceId(input.string("source_id")),
                            evidenceId = Rg06EvidenceId(input.string("evidence_id")),
                            paymentId = InstallmentPaymentId(input.string("payment_id")),
                            postingId = PostingId(input.string("posting_id")),
                            amount = input.money("amount"),
                            // The v1 merge input omits this fact; source_records owns its exact timestamp.
                            sourcePaymentAt = fixtureInstant(sourcePaymentAt.getValue(input.string("source_id"))),
                            sourcePaymentAtText = sourcePaymentAt.getValue(input.string("source_id")),
                        ),
                )
            else -> error("unsupported RG-06 import operation index $index")
        }
    return Rg06FixtureOperation(
        id = "operation-rg06-$name",
        rootPurpose = "rg06_import_staged_payment_lifecycle",
        sourcePath = "$.import_path.ordered_operations[$index]",
        expectedStatus = "accepted",
        operation = typed,
    )
}

private fun pendingCandidateStatusId(candidateId: String): String =
    when (candidateId) {
        "candidate-rg06-import-deposit" -> "candidate-status-source-rg06-import-deposit-pending"
        "candidate-rg06-import-ambiguous" -> "candidate-status-source-rg06-import-ambiguous-pending"
        "candidate-rg06-import-final" -> "candidate-status-source-rg06-import-final-pending"
        else -> error("unsupported RG-06 imported candidate status identity $candidateId")
    }

private fun adaptInvalidInputs(
    inputs: JsonArray,
    ledgerId: LedgerId,
): List<Rg06FixtureOperation> =
    inputs.map { element ->
        val invalid = element.jsonObject
        val id = invalid.string("id")
        val input = invalid.getValue("input").jsonObject
        val context = invalid.string("operation_context")
        val typed =
            when (context) {
                "group_creation" -> invalidCreate(id, input, ledgerId)
                "payment_creation" -> invalidInstallment(id, input, ledgerId)
                "payment_progress_transition" -> invalidCompletion(id, input, ledgerId)
                else -> error("unsupported RG-06 invalid operation context $context")
            }
        Rg06FixtureOperation(
            id = "operation-rg06-rejection-$id",
            rootPurpose = "rg06_rejected_$id",
            sourcePath = "$.invalid_inputs[*]#$id",
            expectedStatus = "rejected",
            operation = typed,
        )
    }

private fun invalidCreate(
    id: String,
    input: JsonObject,
    ledgerId: LedgerId,
): Rg06Operation.CreateStagedPayment {
    val amount = input.optionalString("total_amount") ?: "300.00"
    val category =
        input.optionalString("category_id")?.let(::CategoryId)
            ?: if (input.containsKey("category_id")) null else CategoryId("expense-category-service")
    return Rg06Operation.CreateStagedPayment(
        ledgerId = ledgerId,
        input =
            Rg06CreateStagedPaymentInput(
                requestId = RequestId("request-rg06-rejection-$id"),
                totalAmount = amount.toMoney("CNY"),
                categoryId = category,
                createdAt = Instant.parse("2026-04-20T09:00:00+08:00"),
            ),
        ids =
            StagedPaymentCreationIds(
                relationId = StagedPaymentRelationId("association-group-rg06-rejected-$id"),
                lifecycleId = StagedPaymentLifecycleId("lifecycle-rg06-rejected-$id"),
                historyId = StagedPaymentHistoryId("history-rg06-rejected-$id"),
            ),
    )
}

private fun invalidInstallment(
    id: String,
    input: JsonObject,
    ledgerId: LedgerId,
): Rg06Operation.RecordStagedPaymentInstallment {
    val role =
        input.optionalString("payment_role")?.let { StagedPaymentRole.valueOf(it.uppercase()) }
            ?: if (id.contains("final")) StagedPaymentRole.FINAL else StagedPaymentRole.DEPOSIT
    val currency = input.optionalString("payment_currency") ?: "CNY"
    val amount = input.optionalString("payment_amount") ?: if (role == StagedPaymentRole.FINAL) "220.00" else "80.00"
    val funding = input.optionalString("funding_account_id") ?: "asset-bank-a"
    return Rg06Operation.RecordStagedPaymentInstallment(
        ledgerId = ledgerId,
        input =
            Rg06RecordStagedPaymentInstallmentInput(
                requestId = RequestId("request-rg06-rejection-$id"),
                relationId = StagedPaymentRelationId("association-group-rg06-invalid"),
                paymentRole = role,
                paymentAmount = amount.toMoney(currency),
                fundingAccountId = AccountId(funding),
                actualPaymentAt = Instant.parse(if (role == StagedPaymentRole.FINAL) "2026-05-03T16:30:00+08:00" else "2026-04-28T10:00:00+08:00"),
            ),
        ids =
            Rg06ManualInstallmentCommitIds(
                confirmationId = Rg06ConfirmationId("confirmation-rg06-rejected-$id"),
                paymentIds = paymentIdsForSuffix("rejected-$id", role),
                reconciliationId = Rg06ReconciliationId("reconciliation-rg06-rejected-$id"),
            ),
    )
}

private fun invalidCompletion(
    id: String,
    input: JsonObject,
    ledgerId: LedgerId,
): Rg06Operation.ConfirmStagedPaymentCompletion =
    Rg06Operation.ConfirmStagedPaymentCompletion(
        ledgerId = ledgerId,
        input =
            Rg06ConfirmStagedPaymentCompletionInput(
                requestId = RequestId("request-rg06-rejection-$id"),
                relationId = StagedPaymentRelationId("association-group-rg06-invalid"),
                confirmed = true,
                occurredAt = Instant.parse("2026-05-04T09:00:00+08:00"),
            ),
        historyId = StagedPaymentHistoryId("history-rg06-rejected-$id"),
    )

private fun paymentIds(
    payment: JsonObject,
    expected: JsonObject,
): StagedPaymentInstallmentIds {
    val paymentId = payment.string("id")
    val transactionId = payment.string("transaction_id")
    val role = payment.string("role")
    val suffix = paymentId.removePrefix("payment-")
    val historyId =
        expected
            .getValue("group")
            .jsonObject
            .getValue("state_history")
            .jsonArray
            .last()
            .jsonObject
            .string("id")
    return StagedPaymentInstallmentIds(
        paymentId = InstallmentPaymentId(paymentId),
        historyId = StagedPaymentHistoryId(historyId),
        expenseIds =
            AssetPaidOrdinaryExpenseIds(
                transactionId = TransactionId(transactionId),
                versionId = TransactionVersionId("version-$suffix-v1"),
                postingSetId = PostingSetId("posting-set-$suffix"),
                expensePostingId = PostingId("posting-expense-$suffix"),
                paymentPostingId = PostingId("posting-asset-$suffix"),
            ),
    )
}

private fun paymentIdsForSuffix(
    suffix: String,
    role: StagedPaymentRole,
): StagedPaymentInstallmentIds =
    StagedPaymentInstallmentIds(
        paymentId = InstallmentPaymentId("payment-$suffix-${role.name.lowercase()}"),
        historyId = StagedPaymentHistoryId("history-$suffix"),
        expenseIds =
            AssetPaidOrdinaryExpenseIds(
                transactionId = TransactionId("tx-$suffix"),
                versionId = TransactionVersionId("version-$suffix-v1"),
                postingSetId = PostingSetId("posting-set-$suffix"),
                expensePostingId = PostingId("posting-expense-$suffix"),
                paymentPostingId = PostingId("posting-asset-$suffix"),
            ),
    )

private fun lastHistoryId(expected: JsonObject): StagedPaymentHistoryId =
    StagedPaymentHistoryId(
        expected
            .getValue("group")
            .jsonObject
            .getValue("state_history")
            .jsonArray
            .last()
            .jsonObject
            .string("id"),
    )

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.optionalString(key: String): String? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

private fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.content.toBooleanStrict()

private fun JsonObject.instant(key: String): Instant = fixtureInstant(string(key))

private fun JsonObject.money(key: String): Money = string(key).toMoney(string("currency"))

private fun String.toMoney(currency: String): Money {
    val negative = startsWith("-")
    val unsigned = removePrefix("+").removePrefix("-")
    val parts = unsigned.split('.')
    require(parts.size <= 2) { "invalid fixture amount $this" }
    val fraction = parts.getOrElse(1) { "" }
    require(fraction.length <= 2) { "fixture precision exceeds CNY scale: $this" }
    val minor = parts[0].toLong() * 100L + fraction.padEnd(2, '0').ifEmpty { "0" }.toLong()
    return Money.ofMinor(if (negative) -minor else minor, CurrencyUnit(currency, 2))
}

private fun fixtureInstant(text: String): Instant {
    if ('/' !in text) return Instant.parse(text)
    val match =
        Regex("^(\\d{4})/(\\d{1,2})/(\\d{1,2}) (\\d{1,2}):(\\d{2}):(\\d{2})$").matchEntire(text)
            ?: error("invalid frozen v1 confirmation time $text")
    val year = match.groupValues[1]
    val month = match.groupValues[2]
    val day = match.groupValues[3]
    val hour = match.groupValues[4]
    val minute = match.groupValues[5]
    val second = match.groupValues[6]
    return Instant.parse("$year-${month.padStart(2, '0')}-${day.padStart(2, '0')}T${hour.padStart(2, '0')}:$minute:$second+08:00")
}
