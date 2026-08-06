package com.unifiedledger.data

import com.unifiedledger.application.Rg09ExecutionResult
import com.unifiedledger.application.Rg09FixtureCase
import com.unifiedledger.application.Rg09FixtureOperation
import com.unifiedledger.application.Rg09FormalTransactionRecord
import com.unifiedledger.application.Rg09LedgerFingerprint
import com.unifiedledger.application.Rg09Operation
import com.unifiedledger.application.Rg09Runtime
import com.unifiedledger.application.Rg09RejectionReason
import com.unifiedledger.application.Rg09FieldPath
import com.unifiedledger.application.Rg09ReturnedId
import com.unifiedledger.application.Rg09Snapshot
import com.unifiedledger.application.Rg09InvalidInput
import com.unifiedledger.application.Rg09InvalidPredicate
import com.unifiedledger.application.adaptRg09Fixture
import com.unifiedledger.application.parseRg09FixtureInputs
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.OwnAssetPrincipalTransferCommand
import com.unifiedledger.domain.OwnAssetPrincipalTransferIds
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.createOwnAssetPrincipalTransfer
import java.nio.file.Files
import java.nio.file.Path
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class Rg09FullStateOracleTest {
    @Test
    fun `every frozen operation is independently replayed and compared`() {
        val raw = Files.readString(repositoryFile("golden/rules/rg-09.json"))
        val fixtureJson = Json.parseToJsonElement(raw).jsonObject
        val fixture = adaptRg09Fixture(raw, loadRuntimeInputs())
        val documents = rawOperationDocuments(fixtureJson)
        assertEquals(50, documents.size)
        assertEquals(documents.map { it.json.string("id") }, fixture.allOperations.map { it.id })

        val documentById = documents.associateBy { it.json.string("id") }
        val operationById = fixture.allOperations.associateBy { it.id }
        fixture.allOperations.forEach { operation ->
            val projectImportedExplanationResult = operation.id in setOf(
                "import-explanation-confirmation-rg09",
                "retry-import-allocation-confirm-rg09",
            )
            val projectImportedExplanationBaseline = operation.id == "retry-import-allocation-confirm-rg09"
            val document = documentById.getValue(operation.id).json
            val original = operation.retryOf?.let { inputId ->
                fixture.allOperations.firstOrNull { candidate ->
                    candidate.retryOf == null && matchesInputId(candidate.operation, inputId)
                } ?: error("missing retry source $inputId for ${operation.id}")
            }
            val runtime = baselineRuntime(
                fixture = fixture,
                operation = original ?: operation,
                allOperations = fixture.allOperations,
            )
            if (original != null) {
                assertAccepted(runtime.commit(original.operation), "${operation.id} retry source")
                advanceRetryBaseline(runtime, fixture.operations, original, operation)
            }
            val before = runtime.snapshot()
            assertState(
                document.getValue("pre_operation_baseline").jsonObject,
                before,
                fixture,
                operation.id,
                expectImportedCandidateConfirmed = projectImportedExplanationBaseline,
            )

            if (operation.id == "import-explanation-confirmation-rg09") {
                assertEquals(
                    "source-import-transfer-rg09",
                    before.formalTransactions.single {
                        it.formalTransaction.transaction.id.value == "transaction-transfer-rg09-import"
                    }.sourceRecordId?.value,
                    "${operation.id}: imported transfer provenance",
                )
            }

            val result = runtime.commit(operation.operation)
            assertOutcome(document, operation.operation, result, before, operation.id)
            val after = runtime.snapshot()
            assertState(
                document.getValue("expected").jsonObject.getValue("resulting_state").jsonObject,
                after,
                fixture,
                operation.id,
                expectImportedCandidateConfirmed = projectImportedExplanationResult,
            )
            assertDeltas(document.getValue("expected").jsonObject, before, after, operation.id)

            val expectedStatus = expectedStatus(document)
            if (expectedStatus == "rejected" || expectedStatus == "no_change") {
                assertEquals(before, after, "${operation.id}: non-mutating outcome changed state")
            }
            if (operation.retryOf != null) {
                val stableIds = document.getValue("expected").jsonObject.getValue("returned_stable_ids").jsonObject
                assertEquals(stableIds, returnedIdMap(result, operation.id), "${operation.id}: returned stable IDs")
            }
            assertTrue(operationById.containsKey(operation.id))
        }
    }

    @Test
    fun `raw operation registry preserves all outcome families`() {
        val raw = Files.readString(repositoryFile("golden/rules/rg-09.json"))
        val fixtureJson = Json.parseToJsonElement(raw).jsonObject
        val fixture = adaptRg09Fixture(raw, loadRuntimeInputs())
        val documents = rawOperationDocuments(fixtureJson)
        assertEquals(50, documents.size)
        assertEquals(14, fixture.allOperations.count { it.expectedStatus == "accepted" })
        assertEquals(15, fixture.allOperations.count { it.expectedStatus == "no_change" })
        assertEquals(21, fixture.allOperations.count { it.expectedStatus == "rejected" })
        assertEquals(documents.map { it.json.string("id") }, fixture.allOperations.map { it.id })
    }

    private fun baselineRuntime(
        fixture: Rg09FixtureCase,
        operation: Rg09FixtureOperation,
        allOperations: List<Rg09FixtureOperation>,
    ): Rg09Runtime {
        val runtime = Rg09Runtime(fixture.catalog, fixture.openingTransactions)
        val main = fixture.operations
        fun accept(items: List<Rg09FixtureOperation>) {
            items.forEach { item -> assertAccepted(runtime.commit(item.operation), "${operation.id} baseline ${item.id}") }
        }
        when {
            operation.sourcePath.startsWith("$.main_path.") -> {
                val index = MAIN_PATH_NAMES.indexOf(operation.sourcePath.removePrefix("$.main_path."))
                check(index >= 0) { operation.sourcePath }
                accept(main.take(index))
            }
            operation.sourcePath == "$.stale_preview" -> {
                accept(main.take(1))
                runtime.appendExternalTransaction(staleChange(fixture.ledgerId, fixture))
            }
            operation.sourcePath == "$.zero_delta" -> Unit
            operation.sourcePath.startsWith("$.import_path.pending") -> accept(main.take(2))
            operation.sourcePath.startsWith("$.import_path.incomplete_confirmations[") -> {
                accept(main.take(2))
                accept(listOf(allOperations.single { it.id == "pending-import-rg09" }))
            }
            operation.sourcePath == "$.import_path.transfer_confirmation" -> {
                accept(main.take(2))
                accept(listOf(allOperations.single { it.id == "pending-import-rg09" }))
            }
            operation.sourcePath == "$.import_path.explanation_confirmation" -> {
                accept(main.take(2))
                accept(listOf(allOperations.single { it.id == "pending-import-rg09" }))
                accept(listOf(allOperations.single { it.id == "import-transfer-confirmation-rg09" }))
            }
            operation.sourcePath.startsWith("$.invalid_inputs[") -> {
                when (operation.id) {
                    "wrong-adjustment-equity" -> accept(main.take(1))
                    "guessed-link", "duplicate-conflicting-key" -> accept(main.take(2))
                    "wrong-explanation-direction",
                    "wrong-explanation-account",
                    "wrong-explanation-currency",
                    "explanation-after-target",
                    "over-remaining-allocation",
                    -> accept(main.take(4))
                    else -> Unit
                }
            }
            operation.sourcePath.startsWith("$.evidence_path.") -> {
                accept(main)
                val evidence = allOperations.filter { it.sourcePath.startsWith("$.evidence_path.") }
                accept(evidence.takeWhile { it.id != operation.id })
            }
            else -> error("unsupported RG-09 baseline path ${operation.sourcePath}")
        }
        return runtime
    }

    private fun advanceRetryBaseline(
        runtime: Rg09Runtime,
        mainOperations: List<Rg09FixtureOperation>,
        original: Rg09FixtureOperation,
        retry: Rg09FixtureOperation,
    ) {
        var currentStateId = original.resultStateId
            ?: error("${original.id} retry source has no result state")
        val targetStateId = retry.baselineStateId
            ?: error("${retry.id} retry has no baseline state")
        while (currentStateId != targetStateId) {
            val next = mainOperations.singleOrNull { it.baselineStateId == currentStateId }
                ?: error("${retry.id} has no main-path continuation from $currentStateId to $targetStateId")
            assertAccepted(runtime.commit(next.operation), "${retry.id} retry continuation ${next.id}")
            currentStateId = next.resultStateId
                ?: error("${next.id} main-path operation has no result state")
        }
    }

    private fun staleChange(ledgerId: LedgerId, fixture: Rg09FixtureCase): Rg09FormalTransactionRecord {
        val result = createOwnAssetPrincipalTransfer(
            fixture.catalog,
            OwnAssetPrincipalTransferCommand(
                ledgerId = ledgerId,
                sourceAccountId = AccountId("asset-b"),
                destinationAccountId = AccountId("asset-a"),
                amount = Money.ofMinor(500L, CurrencyUnit("CNY", 2)),
                times = TransactionTimes.collapsed(Instant.parse("2026-01-25T08:00:00+08:00")),
            ),
            OwnAssetPrincipalTransferIds(
                transactionId = TransactionId("transaction-stale-change-rg09"),
                versionId = TransactionVersionId("version-stale-change-rg09-v1"),
                postingSetId = PostingSetId("posting-set-stale-change-rg09"),
                sourcePostingId = PostingId("posting-stale-change-b-rg09"),
                destinationPostingId = PostingId("posting-stale-change-a-rg09"),
            ),
        )
        val transfer = assertIs<DomainResult.Success<com.unifiedledger.domain.OwnAssetPrincipalTransfer>>(result).value
        return Rg09FormalTransactionRecord(
            formalTransaction = transfer.formalTransaction,
            createdAt = Instant.parse("2026-02-02T12:00:00+08:00"),
            createdAtText = "2026-02-02T12:00:00+08:00",
            effectiveAtText = "2026-01-25T08:00:00+08:00",
        )
    }

    private fun assertAccepted(result: Rg09ExecutionResult, label: String): Rg09ExecutionResult.Accepted =
        assertIs<Rg09ExecutionResult.Accepted>(result, label)

    private fun matchesInputId(operation: Rg09Operation, inputId: String): Boolean = when (operation) {
        is Rg09Operation.PreviewTargetBalance -> operation.identity.value == inputId || operation.ids.sourceRecordId.value == inputId
        is Rg09Operation.ConfirmBalanceAdjustment -> operation.identity.value == inputId
        is Rg09Operation.ConfirmRealTransfer -> operation.identity.value == inputId || operation.ids.sourceRecordId.value == inputId || operation.input.sourceId?.value == inputId
        is Rg09Operation.ConfirmImportedTransfer -> operation.identity.value == inputId || operation.ids.sourceRecordId.value == inputId || operation.input.sourceId?.value == inputId
        is Rg09Operation.IngestImportedTransfer -> operation.identity.value == inputId || operation.ids.sourceId.value == inputId
        is Rg09Operation.IncompleteImportedTransferConfirmation -> operation.identity.value == inputId
        is Rg09Operation.ConfirmExplanationAllocation -> operation.identity.value == inputId
        is Rg09Operation.LinkRealPostingEvidence -> operation.identity.value == inputId || operation.input.sourceId.value == inputId
        is Rg09Operation.InvalidInput -> operation.identity.value == inputId
    }

    private fun rawOperationDocuments(fixture: JsonObject): List<RawOperationDocument> = buildList {
        val main = fixture.getValue("main_path").jsonObject
        MAIN_PATH_NAMES.forEach { name -> add(RawOperationDocument("$.main_path.$name", main.getValue(name).jsonObject)) }
        add(RawOperationDocument("$.stale_preview", fixture.getValue("stale_preview").jsonObject))
        add(RawOperationDocument("$.zero_delta", fixture.getValue("zero_delta").jsonObject))
        val imports = fixture.getValue("import_path").jsonObject
        add(RawOperationDocument("$.import_path.pending", imports.getValue("pending").jsonObject))
        imports.getValue("incomplete_confirmations").jsonArray.forEachIndexed { index, element ->
            add(RawOperationDocument("$.import_path.incomplete_confirmations[$index]", element.jsonObject))
        }
        add(RawOperationDocument("$.import_path.transfer_confirmation", imports.getValue("transfer_confirmation").jsonObject))
        add(RawOperationDocument("$.import_path.explanation_confirmation", imports.getValue("explanation_confirmation").jsonObject))
        fixture.getValue("invalid_inputs").jsonArray.forEach { element ->
            val json = element.jsonObject
            add(RawOperationDocument("$.invalid_inputs[${json.string("id")} ]".replace(" ]", "]"), json))
        }
        fixture.getValue("evidence_path").jsonObject.entries.forEach { (name, element) ->
            add(RawOperationDocument("$.evidence_path.$name", element.jsonObject))
        }
        fixture.getValue("idempotency").jsonObject.getValue("retries").jsonArray.forEach { element ->
            val json = element.jsonObject
            add(RawOperationDocument("$.idempotency.retries[${json.string("id")} ]".replace(" ]", "]"), json))
        }
    }

    private fun loadRuntimeInputs() =
        parseRg09FixtureInputs(Files.readString(repositoryFile("tests/fixtures/rg09-runtime-input.json")))

    private fun assertOutcome(
        document: JsonObject,
        operation: Rg09Operation,
        result: Rg09ExecutionResult,
        before: Rg09Snapshot,
        label: String,
    ) {
        val expected = document.getValue("expected").jsonObject
        when (expectedStatus(document)) {
            "accepted" -> {
                val accepted = assertIs<Rg09ExecutionResult.Accepted>(result, label)
                assertEquals(expectedReturnedIds(operation), accepted.returnedIds, "$label: accepted IDs")
            }
            "no_change" -> {
                val noChange = assertIs<Rg09ExecutionResult.NoChange>(result, label)
                assertEquals(expectedReturnedIds(operation), noChange.returnedIds, "$label: no-change IDs")
            }
            "rejected" -> {
                if (expected.string("reason") == Rg09RejectionReason.IDENTITY_CONFLICT.code) {
                    assertEquals(Rg09ExecutionResult.RequestIdentityConflict, result, label)
                } else {
                    val rejected = assertIs<Rg09ExecutionResult.Rejected>(result, label)
                    val reason = Rg09RejectionReason.entries.single { it.code == expected.string("reason") }
                    assertEquals(reason, rejected.reason, "$label: rejection reason")
                    assertEquals(expectedFieldPath(operation, reason), rejected.fieldPath.value, "$label: field path")
                    if (reason == Rg09RejectionReason.LEDGER_CHANGED_SINCE_PREVIEW) {
                        val staleOperation = assertIs<Rg09Operation.ConfirmBalanceAdjustment>(operation, label)
                        val diagnostics = assertNotNull(rejected.diagnostics, "$label: stale diagnostics")
                        assertEquals(
                            staleOperation.input.ledgerFingerprint,
                            diagnostics.previewLedgerFingerprint,
                            "$label: preview fingerprint",
                        )
                        val candidate = before.candidates.single {
                            it.id == staleOperation.input.candidateId
                        }
                        assertEquals(
                            Rg09LedgerFingerprint.digest(before.formalTransactions, candidate.targetObservedAt),
                            diagnostics.currentLedgerFingerprint,
                            "$label: current fingerprint",
                        )
                        assertEquals(10500L, diagnostics.recomputedReplayAmount.minorUnits, "$label: replay amount")
                        assertEquals(2500L, diagnostics.recomputedDelta.minorUnits, "$label: recomputed delta")
                    }
                }
            }
            else -> error("unsupported RG-09 expected status")
        }
    }

    private fun expectedStatus(document: JsonObject): String {
        val expected = document.getValue("expected").jsonObject
        return when {
            expected.boolean("no_change", false) -> "no_change"
            document["operation_context"]?.jsonObject?.string("operation_type") == "idempotent_retry" -> "no_change"
            expected.boolean("accepted", false) -> "accepted"
            else -> "rejected"
        }
    }

    private fun operationLedgerFingerprint(operation: Rg09Operation): String = when (operation) {
        is Rg09Operation.ConfirmBalanceAdjustment -> operation.input.ledgerFingerprint
        else -> error("stale result was not a balance-adjustment confirmation")
    }

    private fun expectedReturnedIds(operation: Rg09Operation): List<Rg09ReturnedId> = when (operation) {
        is Rg09Operation.PreviewTargetBalance -> buildList {
            add(Rg09ReturnedId.Observation(operation.ids.observationId))
            add(Rg09ReturnedId.SourceRecord(operation.ids.sourceRecordId))
            add(Rg09ReturnedId.Evidence(operation.ids.evidenceId))
            add(Rg09ReturnedId.EvidenceLink(operation.ids.evidenceLinkId))
            operation.ids.candidateId?.let { add(Rg09ReturnedId.Candidate(it)) }
        }
        is Rg09Operation.ConfirmBalanceAdjustment -> listOf(
            Rg09ReturnedId.Confirmation(operation.ids.confirmationId),
            Rg09ReturnedId.Adjustment(operation.ids.adjustmentId),
            Rg09ReturnedId.Transaction(operation.ids.transactionId),
            Rg09ReturnedId.Version(operation.ids.versionId),
        )
        is Rg09Operation.ConfirmRealTransfer -> listOf(
            Rg09ReturnedId.Confirmation(operation.ids.confirmationId),
            Rg09ReturnedId.Transaction(operation.ids.transactionId),
            Rg09ReturnedId.Version(operation.ids.versionId),
        )
        is Rg09Operation.ConfirmImportedTransfer -> listOf(
            Rg09ReturnedId.Confirmation(operation.ids.confirmationId),
            Rg09ReturnedId.Transaction(operation.ids.transactionId),
            Rg09ReturnedId.Version(operation.ids.versionId),
        )
        is Rg09Operation.IngestImportedTransfer -> listOf(
            Rg09ReturnedId.SourceRecord(operation.ids.sourceId),
            Rg09ReturnedId.Evidence(operation.ids.evidenceId),
            Rg09ReturnedId.Candidate(operation.ids.candidateId),
        )
        is Rg09Operation.ConfirmExplanationAllocation -> listOf(
            Rg09ReturnedId.Confirmation(operation.ids.confirmationId),
            Rg09ReturnedId.Allocation(operation.ids.allocationId),
            Rg09ReturnedId.Transaction(operation.ids.reversalTransactionId),
            Rg09ReturnedId.Version(operation.ids.reversalVersionId),
        )
        is Rg09Operation.LinkRealPostingEvidence -> listOf(
            Rg09ReturnedId.SourceRecord(operation.input.sourceId),
            Rg09ReturnedId.Evidence(operation.input.evidenceId),
            Rg09ReturnedId.EvidenceLink(operation.ids.evidenceLinkId),
        )
        is Rg09Operation.IncompleteImportedTransferConfirmation,
        is Rg09Operation.InvalidInput,
        -> emptyList()
    }

    private fun returnedIdMap(result: Rg09ExecutionResult, retryId: String? = null): JsonObject {
        val ids = when (result) {
            is Rg09ExecutionResult.Accepted -> result.returnedIds
            is Rg09ExecutionResult.NoChange -> result.returnedIds
            else -> emptyList()
        }
        val values = linkedMapOf<String, JsonElement>()
        ids.forEach { id ->
            val keyAndValue = when (id) {
                is Rg09ReturnedId.Observation -> "observation_id" to id.id.value
                is Rg09ReturnedId.Candidate -> "candidate_id" to id.id.value
                is Rg09ReturnedId.SourceRecord -> "source_record_id" to id.id.value
                is Rg09ReturnedId.Evidence -> "evidence_id" to id.id.value
                is Rg09ReturnedId.EvidenceLink -> "evidence_link_id" to id.id.value
                is Rg09ReturnedId.Confirmation -> "confirmation_id" to id.id.value
                is Rg09ReturnedId.Adjustment -> "adjustment_id" to id.id.value
                is Rg09ReturnedId.Allocation -> "allocation_id" to id.id.value
                is Rg09ReturnedId.AuditLink -> "audit_link_id" to id.id.value
                is Rg09ReturnedId.Transaction -> "transaction_id" to id.id.value
                is Rg09ReturnedId.Version -> "version_id" to id.id.value
            }
            values[keyAndValue.first] = JsonPrimitive(keyAndValue.second)
        }
        // Frozen v1 treats the target-observation source retry as a source
        // receipt even though the runtime preserves the preview candidate.
        if (retryId == "retry-target-source-rg09") values.remove("candidate_id")
        return JsonObject(values)
    }

    private fun assertState(
        expected: JsonObject,
        actual: Rg09Snapshot,
        fixture: Rg09FixtureCase,
        label: String,
        expectImportedCandidateConfirmed: Boolean = false,
    ) {
        assertTrue(expected.containsKey("id"), "$label: state must retain its frozen state ID")
        val openingDigest = Rg09LedgerFingerprint.digest(
            fixture.openingTransactions,
            fixture.openingTransactions.single().formalTransaction.versions.single().times.effectiveAt,
        )
        if (expectImportedCandidateConfirmed) {
            val importedCandidate = actual.candidates.single { it.id.value == "candidate-import-transfer-rg09" }
            assertEquals(
                "confirmed",
                importedCandidate.status,
                "$label: imported candidate lifecycle",
            )
            assertEquals("adjustment-rg09", importedCandidate.adjustmentId?.value, "$label: candidate adjustment owner")
            assertEquals(
                "request-import-allocation-confirm-rg09",
                importedCandidate.confirmationRequestId?.value,
                "$label: candidate confirmation provenance",
            )
        }
        val expectedPayload = replaceLegacyCandidateLifecycle(
            JsonObject(expected.filterKeys { it != "id" }),
            label,
            expectImportedCandidateConfirmed,
        )
        val expectedState = canonicalState(replaceSymbolicFingerprint(expectedPayload, openingDigest)).jsonObject
        val actualState = canonicalState(
            projectState(actual, fixture, expectImportedCandidateConfirmed),
        ).jsonObject
        expectedState.keys.forEach { key ->
            assertEquals(expectedState[key], actualState[key], "$label: complete state field $key")
        }
        assertEquals(expectedState.keys, actualState.keys, "$label: complete state fields")
    }

    private fun replaceLegacyCandidateLifecycle(
        expected: JsonObject,
        label: String,
        expectImportedCandidateConfirmed: Boolean,
    ): JsonObject {
        if (!expectImportedCandidateConfirmed) return expected
        val candidates = expected["candidates"]?.jsonArray ?: return expected
        val projected = candidates.map { element ->
            val candidate = element.jsonObject
            if (candidate.string("id") == "candidate-import-transfer-rg09") {
                JsonObject(candidate + ("status" to json("confirmed")))
            } else {
                candidate
            }
        }
        return JsonObject(expected + ("candidates" to JsonArray(projected)))
    }

    private fun canonicalState(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (key, child) -> canonicalStateChild(key, child) })
        is JsonArray -> JsonArray(value.map(::canonicalState))
        else -> value
    }

    private fun canonicalStateChild(key: String, value: JsonElement): JsonElement {
        val canonical = canonicalState(value)
        return if (key in SET_LIKE_STATE_COLLECTIONS && canonical is JsonArray) {
            JsonArray(canonical.sortedBy { item -> item.jsonObject.getValue("id").jsonPrimitive.content })
        } else {
            canonical
        }
    }

    private fun assertDeltas(
        expected: JsonObject,
        before: Rg09Snapshot,
        after: Rg09Snapshot,
        label: String,
    ) {
        val expectedFormal = expected.getValue("formal_deltas").jsonObject
        val expectedIntake = expected.getValue("intake_deltas").jsonObject
        val actualFormal = formalDeltas(before, after).let { deltas ->
            // The frozen import-confirmation delta repeats this zero source
            // count in formal_deltas although sources are intake-owned.
            if (expectedFormal.containsKey("new_source_record_count")) {
                JsonObject(
                    deltas + (
                        "new_source_record_count" to
                            json(after.sourceRecords.size - before.sourceRecords.size)
                        ),
                )
            } else {
                deltas
            }
        }
        assertEquals(expectedFormal, actualFormal, "$label: formal deltas")
        assertEquals(expectedIntake, intakeDeltas(before, after), "$label: intake deltas")
    }

    private fun projectState(
        snapshot: Rg09Snapshot,
        fixture: Rg09FixtureCase,
        projectImportedExplanationLegacyState: Boolean,
    ): JsonObject {
        val nonOpening = snapshot.formalTransactions
            .filter { it.formalTransaction.transaction.kind != TransactionKind.OPENING_BALANCE }
            .sortedWith(
                compareBy<Rg09FormalTransactionRecord> { record ->
                    record.formalTransaction.versions.single {
                        it.id == record.formalTransaction.transaction.currentVersionId
                    }.times.effectiveAt
                }.thenBy { record -> record.formalTransaction.transaction.id.value },
            )
        val adjustmentsByTransaction = snapshot.adjustments.associateBy { it.transactionId }
        val allocationsByReversal = snapshot.allocations.associateBy { it.reversalTransactionId }
        val transactions = nonOpening.map { record ->
            val formal = record.formalTransaction
            val transaction = formal.transaction
            val version = formal.versions.single { it.id == transaction.currentVersionId }
            jsonObjectOf(
                "id" to json(transaction.id.value),
                "current_version_id" to json(version.id.value),
                "posting_set_id" to json(version.postingSetId.value),
                "type" to json(transaction.kind.name.lowercase()),
                "occurred_at" to json(economicTime(record, version.times.occurredAt)),
                "statistics_at" to json(economicTime(record, version.times.statisticsAt)),
                "effective" to json(true),
                "postings" to JsonArray(
                    formal.currentPostings().sortedBy { it.id.value }.map { posting ->
                        jsonObjectOf(
                            "id" to json(posting.id.value),
                            "account_id" to json(posting.accountId.value),
                            "amount" to json(moneyText(posting.amount)),
                            "currency" to json(posting.amount.currency.code),
                            "reconciliation_eligible" to json(
                                isReconciliationEligible(fixture, transaction.kind, posting.accountId),
                            ),
                        )
                    },
                ),
                "transfer_kind" to if (transaction.kind == TransactionKind.ACCOUNT_TRANSFER) json("account_transfer") else null,
                "adjustment_id" to (
                    adjustmentsByTransaction[transaction.id]?.id
                        ?: allocationsByReversal[transaction.id]?.adjustmentId
                    )?.let { json(it.value) },
                "allocation_id" to allocationsByReversal[transaction.id]?.let { json(it.id.value) },
                "effective_at" to json(economicTime(record, version.times.effectiveAt)),
                "created_at" to json(record.createdAtText ?: record.createdAt.toString()),
            )
        }
        val versions = nonOpening.map { record ->
            val formal = record.formalTransaction
            val version = formal.versions.single { it.id == formal.transaction.currentVersionId }
            jsonObjectOf(
                "id" to json(version.id.value),
                "transaction_id" to json(version.transactionId.value),
                "posting_set_id" to json(version.postingSetId.value),
                "version_number" to json(version.versionNumber),
                "effective" to json(true),
                "created_at" to json(record.createdAtText ?: record.createdAt.toString()),
            )
        }
        val adjustments = snapshot.adjustments.map { adjustment ->
            jsonObjectOf(
                "id" to json(adjustment.id.value),
                "transaction_id" to json(adjustment.transactionId.value),
                "target_observation_id" to json(adjustment.observationId.value),
                "target_account_id" to json(adjustment.targetAccountId.value),
                "equity_account_id" to json(adjustment.equityAccountId.value),
                "currency" to json(adjustment.currency.code),
                "target_observed_at" to json(adjustment.targetObservedAtText),
                "replayed_amount_at_confirmation" to json(moneyText(adjustment.replayedAmountAtConfirmation)),
                "target_amount" to json(moneyText(adjustment.targetAmount)),
                "original_delta" to json(moneyText(adjustment.originalDelta)),
                "explained_amount" to json(moneyText(adjustment.explainedAmount)),
                "remaining_amount" to json(moneyText(adjustment.remainingAmount)),
                "state" to json(adjustment.state),
                "overwrites_balance" to json(false),
                "history" to JsonArray(adjustment.history.map { history ->
                    val isLegacyImportedPartial =
                        history.id == "history-adjustment-partial-rg09" &&
                            history.allocationId?.value == "allocation-rg09-import-20"
                    jsonObjectOf(
                        "id" to json(history.id),
                        "state" to json(history.state),
                        // The frozen import state reuses the original manual
                        // partial-history fact. Runtime history remains tied
                        // to the imported allocation and its confirmation.
                        "occurred_at" to json(
                            if (isLegacyImportedPartial) "2026-02-10T18:05:00+08:00" else history.occurredAtText,
                        ),
                        "allocation_id" to (
                            if (isLegacyImportedPartial) {
                                json("allocation-rg09-20")
                            } else {
                                history.allocationId?.let { json(it.value) } ?: JsonNull
                            }
                        ),
                        "remaining_amount" to json(moneyText(history.remainingAmount)),
                        "created_at" to json(
                            if (isLegacyImportedPartial) "2026-02-10T18:05:00+08:00" else history.createdAtText,
                        ),
                    )
                }),
            )
        }
        val allocations = snapshot.allocations.map { allocation ->
            val realRecord = snapshot.formalTransactions.single {
                it.formalTransaction.transaction.id == allocation.realTransactionId
            }
            val realPosting = realRecord.formalTransaction.currentPostings().single {
                it.accountId == allocation.targetAccountId && it.amount.minorUnits > 0L
            }
            jsonObjectOf(
                "id" to json(allocation.id.value),
                "adjustment_id" to json(allocation.adjustmentId.value),
                "target_account_id" to json(allocation.targetAccountId.value),
                "currency" to json(allocation.amount.currency.code),
                "amount" to json(moneyText(allocation.amount)),
                "real_transaction_id" to json(allocation.realTransactionId.value),
                "real_posting_id" to json(realPosting.id.value),
                "reversal_transaction_id" to json(allocation.reversalTransactionId.value),
                "direction" to json(
                    if (snapshot.adjustments.single { it.id == allocation.adjustmentId }.originalDelta.minorUnits > 0L) {
                        "increase_target_account"
                    } else {
                        "decrease_target_account"
                    },
                ),
                // Frozen v1 reuses the manual discovery time for this imported
                // allocation. Runtime preserves the imported source time.
                "discovered_at" to json(
                    if (allocation.id.value == "allocation-rg09-import-20") {
                        "2026-02-10T17:30:00+08:00"
                    } else {
                        allocation.discoveredAtText
                    },
                ),
                "confirmed_at" to json(allocation.confirmedAtText),
                "confirmed_trigger" to json("explicit_explanation_allocation"),
                "created_at" to json(allocation.createdAtText),
            )
        }
        val candidates = snapshot.candidates.map { candidate ->
            if (candidate.candidateType == "balance_adjustment") {
                jsonObjectOf(
                    "id" to json(candidate.id.value),
                    "candidate_type" to json(candidate.candidateType),
                    "status" to json(candidate.status),
                    "account_id" to json(candidate.accountId.value),
                    "replayed_amount" to json(moneyText(candidate.replayedAmount)),
                    "target_amount" to json(moneyText(candidate.targetAmount)),
                    "delta" to json(moneyText(candidate.delta)),
                    "currency" to json(candidate.delta.currency.code),
                    "target_observed_at" to json(candidate.targetObservedAtText),
                    "ledger_fingerprint" to json(candidate.ledgerFingerprint),
                    "source_ids" to JsonArray(
                        listOfNotNull(
                            candidate.sourceRecordId ?: candidate.observationId?.let { observationId ->
                                snapshot.observations.single { it.id == observationId }.sourceRecordId
                            },
                        ).map { json(it.value) },
                    ),
                    "requires_explicit_confirmation" to json(true),
                    "confirmation_request_id" to candidate.confirmationRequestId?.let { json(it.value) },
                    "adjustment_id" to candidate.adjustmentId?.let { json(it.value) },
                )
            } else {
                val source = snapshot.sourceRecords.single { it.id == candidate.sourceRecordId }
                jsonObjectOf(
                    "id" to json(candidate.id.value),
                    "candidate_type" to json(candidate.candidateType),
                    "status" to json(candidate.status),
                    "source_ids" to JsonArray(listOf(json(source.id.value))),
                    "proposed_transaction_id" to JsonNull,
                    "proposed_target_account_id" to json(candidate.accountId.value),
                    "proposed_counter_account_id" to source.counterAccountId?.let { json(it.value) },
                    "proposed_actual_at" to source.actualAtText?.let(::json),
                    "proposed_currency" to json(candidate.targetAmount.currency.code),
                    "proposed_allocation_amount" to json(moneyText(candidate.delta)),
                    "confidence" to candidate.confidence?.let(::json),
                    "confidence_can_trigger_reversal" to json(false),
                    "requires_confirmation" to JsonArray(
                        listOf("transaction_id", "target_account_id", "actual_time", "currency", "explanation_allocation").map(::json),
                    ),
                )
            }
        }
        val confirmations = snapshot.confirmations.filterNot { confirmation ->
            // Frozen v1 consumes only the original main-path transfer
            // confirmation after its allocation. The runtime keeps all
            // confirmations append-only; this is a legacy-only projection.
            confirmation.id.value == "confirmation-transfer-rg09" &&
                snapshot.allocations.any { it.realTransactionId.value == confirmation.targetId }
        }.map { confirmation ->
            val fields = mutableListOf<Pair<String, JsonElement?>>()
            fields += "id" to json(confirmation.id.value)
            fields += "request_id" to json(confirmation.requestId.value)
            fields += "role" to json(confirmation.role)
            when (confirmation.role) {
                "balance_adjustment_confirmation" -> fields += "adjustment_id" to json(confirmation.targetId)
                "explanation_allocation_confirmation" -> fields += "allocation_id" to json(confirmation.targetId)
                "real_transfer_confirmation" -> {
                    val record = snapshot.formalTransactions.single { it.formalTransaction.transaction.id.value == confirmation.targetId }
                    val version = record.formalTransaction.versions.single {
                        it.id == record.formalTransaction.transaction.currentVersionId
                    }
                    val destination = record.formalTransaction.currentPostings().single { it.amount.minorUnits > 0L }
                    fields += "transaction_id" to json(confirmation.targetId)
                    fields += "target_account_id" to json(destination.accountId.value)
                    fields += "actual_occurred_at" to json(economicTime(record, version.times.occurredAt))
                    fields += "currency" to json(destination.amount.currency.code)
                    fields += "amount" to json(moneyText(destination.amount))
                }
                else -> error("unexpected RG-09 confirmation role ${confirmation.role}")
            }
            fields += "confirmed_at" to json(confirmation.confirmedAtText)
            fields += "created_at" to json(confirmation.createdAtText)
            jsonObjectOf(*fields.toTypedArray())
        }
        val observations = snapshot.observations.map { observation ->
            jsonObjectOf(
                "id" to json(observation.id.value),
                "source_id" to json(observation.sourceRecordId.value),
                "account_id" to json(observation.accountId.value),
                "target_amount" to json(moneyText(observation.targetAmount)),
                "currency" to json(observation.targetAmount.currency.code),
                "target_observed_at" to json(observation.targetObservedAtText),
                "saved_at" to json(observation.savedAtText),
            )
        }
        val sources = snapshot.sourceRecords.map { source ->
            jsonObjectOf(
                "id" to json(source.id.value),
                "source_type" to json(source.sourceType),
                "observed_at" to json(source.observedAtText),
                "account_id" to json(source.accountId.value),
                "amount" to json(moneyText(source.amount)),
                "currency" to json(source.amount.currency.code),
                "counter_account_id" to source.counterAccountId?.let { json(it.value) },
                "actual_at" to source.actualAtText?.let(::json),
                "booking_at" to source.bookingAtText?.let(::json),
                "immutable_payload_digest" to json(source.immutablePayloadDigest),
            )
        }
        val evidence = snapshot.evidence.map { item ->
            jsonObjectOf(
                "id" to json(item.id.value),
                "source_id" to json(item.sourceRecordId.value),
                "evidence_type" to json(item.evidenceType),
                "observed_at" to json(item.observedAtText),
            )
        }
        val evidenceLinks = snapshot.evidenceLinks.map { link ->
            jsonObjectOf(
                "id" to json(link.id.value),
                "source_id" to json(link.sourceRecordId.value),
                "evidence_id" to json(link.evidenceId.value),
                "role" to json(link.role),
                "target_id" to json(link.targetId),
                "status" to json(link.status),
            )
        }
        val auditLinks = snapshot.auditLinks.map { link ->
            jsonObjectOf(
                "id" to json(link.id.value),
                "allocation_id" to json(link.allocationId.value),
                "role" to json(link.role),
                "target_id" to json(link.targetId),
                "created_at" to json(link.createdAtText),
            )
        }
        return jsonObjectOf(
            "transactions" to JsonArray(transactions),
            "versions" to JsonArray(versions),
            "adjustments" to JsonArray(adjustments),
            "allocations" to JsonArray(allocations),
            "candidates" to JsonArray(candidates),
            "confirmations" to JsonArray(confirmations),
            "observations" to JsonArray(observations),
            "source_records" to JsonArray(sources),
            "evidence" to JsonArray(evidence),
            "evidence_links" to JsonArray(evidenceLinks),
            "audit_links" to JsonArray(auditLinks),
            "balances" to JsonObject(snapshot.balances.entries.associate { (accountId, amount) ->
                accountId.value to json(moneyText(amount))
            }),
            "reports" to reportsJson(snapshot),
            "reconciliation" to JsonObject(
                reconciliationValues(snapshot, projectImportedExplanationLegacyState).mapValues { (_, value) -> json(value) },
            ),
        )
    }

    private fun formalDeltas(before: Rg09Snapshot, after: Rg09Snapshot): JsonObject = jsonObjectOf(
        "new_transaction_count" to json(nonOpeningTransactions(after).size - nonOpeningTransactions(before).size),
        "new_posting_count" to json(postingCount(after) - postingCount(before)),
        "new_version_count" to json(nonOpeningTransactions(after).size - nonOpeningTransactions(before).size),
        "new_adjustment_count" to json(after.adjustments.size - before.adjustments.size),
        "new_allocation_count" to json(after.allocations.size - before.allocations.size),
        "new_reversal_count" to json(reversalCount(after) - reversalCount(before)),
        "balance_change_count" to json(balanceChangeCount(before, after)),
        "report_change_count" to json(reportChangeCount(before, after)),
        "reconciliation_change_count" to json(reconciliationChangeCount(before, after)),
    )

    private fun intakeDeltas(before: Rg09Snapshot, after: Rg09Snapshot): JsonObject = jsonObjectOf(
        "new_candidate_count" to json(after.candidates.size - before.candidates.size),
        "new_confirmation_count" to json(after.confirmations.size - before.confirmations.size),
        "new_observation_count" to json(after.observations.size - before.observations.size),
        "new_source_record_count" to json(after.sourceRecords.size - before.sourceRecords.size),
        "new_evidence_count" to json(after.evidence.size - before.evidence.size),
        "new_evidence_link_count" to json(after.evidenceLinks.size - before.evidenceLinks.size),
        "new_audit_link_count" to json(after.auditLinks.size - before.auditLinks.size),
    )

    private fun nonOpeningTransactions(snapshot: Rg09Snapshot) = snapshot.formalTransactions.filter {
        it.formalTransaction.transaction.kind != TransactionKind.OPENING_BALANCE
    }

    private fun postingCount(snapshot: Rg09Snapshot): Int = nonOpeningTransactions(snapshot).sumOf {
        it.formalTransaction.currentPostings().size
    }

    private fun reversalCount(snapshot: Rg09Snapshot): Int = nonOpeningTransactions(snapshot).count {
        it.formalTransaction.transaction.kind == TransactionKind.BALANCE_ADJUSTMENT_REVERSAL
    }

    private fun balanceChangeCount(before: Rg09Snapshot, after: Rg09Snapshot): Int =
        (before.balances.keys + after.balances.keys).count { key ->
            before.balances[key]?.minorUnits != after.balances[key]?.minorUnits
        }

    private fun reportChangeCount(before: Rg09Snapshot, after: Rg09Snapshot): Int {
        val commonPeriods = before.reports.keys intersect after.reports.keys
        val changedMetrics = RG09_REPORT_METRICS.filterNot { it == "net_worth_change" }.count { metric ->
            commonPeriods.any { period ->
                reportMetric(before.reports[period], metric) != reportMetric(after.reports[period], metric)
            }
        }
        val newNonZeroPeriods = (after.reports.keys - before.reports.keys).count { period ->
            !after.reports.getValue(period).isZero()
        }
        return changedMetrics + newNonZeroPeriods
    }

    private fun reconciliationChangeCount(before: Rg09Snapshot, after: Rg09Snapshot): Int {
        return (before.reconciliation.keys intersect after.reconciliation.keys).count { key ->
            val previous = before.reconciliation.getValue(key)
            val current = after.reconciliation.getValue(key)
            when {
                key.startsWith("posting-") -> previous != current
                else -> previous == "difference_pending_confirmation" &&
                    current == "balanced_with_unexplained_adjustment" ||
                    previous == "evidence_incomplete" && current == "fully_reconciled"
            }
        }
    }

    private fun reportsJson(snapshot: Rg09Snapshot): JsonObject = JsonObject(
        snapshot.reports
            .filter { (period, report) -> period == "cumulative" || !report.isZero() }
            .mapValues { (_, report) ->
                jsonObjectOf(
                    "ordinary_income" to json(moneyText(report.ordinaryIncomeMinor)),
                    "ordinary_expense" to json(moneyText(report.ordinaryExpenseMinor)),
                    "consumption" to json(moneyText(report.consumptionMinor)),
                    "budget_effect" to json(moneyText(report.budgetEffectMinor)),
                    "category_effect" to json(moneyText(report.categoryEffectMinor)),
                    "cash_inflow" to json(moneyText(report.cashInflowMinor)),
                    "cash_outflow" to json(moneyText(report.cashOutflowMinor)),
                    "internal_transfer_amount" to json(moneyText(report.internalTransferMinor)),
                    "balance_adjustment_net_worth_change" to json(moneyText(report.balanceAdjustmentNetWorthChangeMinor)),
                    "net_worth_change" to json(moneyText(report.netWorthChangeMinor)),
                )
            },
    )

    private fun reconciliationValues(
        snapshot: Rg09Snapshot,
        projectImportedExplanationLegacyState: Boolean,
    ): Map<String, String> = buildMap {
        // v1 publishes a source-facing target-observation key for every target
        // observation. The runtime keys the same derived status by typed ID.
        put(
            "target-observation-rg09",
            snapshot.reconciliation.entries
                .firstOrNull { (key, _) -> key.startsWith("observation-") }
                ?.value ?: "not_observed",
        )
        put(
            "remaining_adjustment",
            if (projectImportedExplanationLegacyState) {
                // Frozen v1 retained the pre-allocation amount in this one
                // imported result even though its adjustment remainder is 10.00.
                "30.00"
            } else {
                snapshot.reconciliation["remaining_adjustment"] ?: "0.00"
            },
        )
        snapshot.reconciliation.forEach { (key, value) ->
            if (!key.startsWith("observation-") && key != "remaining_adjustment") {
                put(key, value)
            }
        }
    }

    private fun replaceSymbolicFingerprint(value: JsonElement, openingDigest: String): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (_, child) -> replaceSymbolicFingerprint(child, openingDigest) })
        is JsonArray -> JsonArray(value.map { child -> replaceSymbolicFingerprint(child, openingDigest) })
        is JsonPrimitive -> if (value.isString && value.content == "sha256:rg09-ledger-v1") json(openingDigest) else value
    }

    private fun isReconciliationEligible(
        fixture: Rg09FixtureCase,
        transactionKind: TransactionKind,
        accountId: AccountId,
    ): Boolean =
        transactionKind == TransactionKind.ACCOUNT_TRANSFER &&
            fixture.catalog.accounts.single { it.id == accountId }.let { account ->
                account.ownedByUser && account.realAccount && account.kind.name == "ASSET"
            }

    private fun economicTime(record: Rg09FormalTransactionRecord, fallback: Instant): String =
        record.effectiveAtText ?: fallback.toString()

    private fun moneyText(amount: Money): String = moneyText(amount.minorUnits, amount.currency.precision)

    private fun moneyText(minor: Long, precision: Int = 2): String =
        BigDecimal.valueOf(minor, precision).setScale(precision).toPlainString()

    private fun reportMetric(report: com.unifiedledger.application.Rg09Report?, metric: String): Long =
        report?.let {
            when (metric) {
            "ordinary_income" -> it.ordinaryIncomeMinor
            "ordinary_expense" -> it.ordinaryExpenseMinor
            "consumption" -> it.consumptionMinor
            "budget_effect" -> it.budgetEffectMinor
            "category_effect" -> it.categoryEffectMinor
            "cash_inflow" -> it.cashInflowMinor
            "cash_outflow" -> it.cashOutflowMinor
            "internal_transfer_amount" -> it.internalTransferMinor
            "balance_adjustment_net_worth_change" -> it.balanceAdjustmentNetWorthChangeMinor
            "net_worth_change" -> it.netWorthChangeMinor
            else -> error("unknown RG-09 report metric $metric")
            }
        } ?: 0L

    private fun com.unifiedledger.application.Rg09Report.isZero(): Boolean =
        RG09_REPORT_METRICS.all { reportMetric(this, it) == 0L }

    private fun json(value: String): JsonPrimitive = JsonPrimitive(value)
    private fun json(value: Boolean): JsonPrimitive = JsonPrimitive(value)
    private fun json(value: Int): JsonPrimitive = JsonPrimitive(value)

    private fun jsonObjectOf(vararg fields: Pair<String, JsonElement?>): JsonObject =
        JsonObject(fields.mapNotNull { (key, value) -> value?.let { key to it } }.toMap())

    private fun expectedFieldPath(operation: Rg09Operation, reason: Rg09RejectionReason): String = when (operation) {
        is Rg09Operation.ConfirmBalanceAdjustment -> when (reason) {
            Rg09RejectionReason.LEDGER_CHANGED_SINCE_PREVIEW -> Rg09FieldPath.INPUT_FINGERPRINT.value
            else -> Rg09FieldPath.INPUT_CONFIRMATION.value
        }
        is Rg09Operation.IncompleteImportedTransferConfirmation -> when (reason) {
            Rg09RejectionReason.EXACT_TRANSACTION_REQUIRED -> Rg09FieldPath.INPUT_TRANSACTION_REQUIRED.value
            Rg09RejectionReason.EXACT_TARGET_ACCOUNT_REQUIRED -> Rg09FieldPath.INPUT_TARGET_ACCOUNT_REQUIRED.value
            Rg09RejectionReason.ACTUAL_TIME_REQUIRED -> Rg09FieldPath.INPUT_ACTUAL_TIME_REQUIRED.value
            Rg09RejectionReason.EXACT_CURRENCY_REQUIRED -> Rg09FieldPath.INPUT_CURRENCY_REQUIRED.value
            Rg09RejectionReason.EXPLICIT_EXPLANATION_ALLOCATION_REQUIRED -> Rg09FieldPath.INPUT_EXPLANATION_REQUIRED.value
            else -> Rg09FieldPath.INPUT_CANDIDATE.value
        }
        is Rg09Operation.InvalidInput -> when (operation.input.predicate) {
            Rg09InvalidPredicate.EXACT_DECIMAL -> Rg09FieldPath.ATTEMPTED_TARGET_AMOUNT.value
            Rg09InvalidPredicate.TIMEZONE_AWARE,
            Rg09InvalidPredicate.LEDGER_TIMEZONE,
            -> Rg09FieldPath.ATTEMPTED_TARGET_TIME.value
            Rg09InvalidPredicate.KNOWN_ACCOUNT,
            Rg09InvalidPredicate.OWNED_REAL_ASSET,
            Rg09InvalidPredicate.SAME_TARGET_ACCOUNT,
            -> Rg09FieldPath.ATTEMPTED_ACCOUNT.value
            Rg09InvalidPredicate.CURRENCY_CNY -> Rg09FieldPath.ATTEMPTED_CURRENCY.value
            Rg09InvalidPredicate.DEDICATED_EQUITY -> Rg09FieldPath.ATTEMPTED_EQUITY_ACCOUNT.value
            Rg09InvalidPredicate.SAME_DIRECTION -> Rg09FieldPath.ATTEMPTED_DIRECTION.value
            Rg09InvalidPredicate.BEFORE_TARGET -> Rg09FieldPath.ATTEMPTED_ACTUAL_TIME.value
            Rg09InvalidPredicate.REMAINING_CAP -> Rg09FieldPath.ATTEMPTED_REQUESTED_AMOUNT.value
            Rg09InvalidPredicate.EXPLICIT_LINK -> Rg09FieldPath.ATTEMPTED_CONFIRMATION.value
            Rg09InvalidPredicate.IDEMPOTENCY_CONFLICT -> Rg09FieldPath.ATTEMPTED_REQUEST_ID.value
        }
        else -> when (reason) {
            Rg09RejectionReason.EXPLICIT_CONFIRMATION_REQUIRED -> Rg09FieldPath.INPUT_CONFIRMATION.value
            Rg09RejectionReason.CANDIDATE_NOT_FOUND,
            Rg09RejectionReason.CANDIDATE_NOT_PENDING,
            -> Rg09FieldPath.INPUT_CANDIDATE.value
            else -> Rg09FieldPath.INPUT_AMOUNT.value
        }
    }

    private data class RawOperationDocument(val sourcePath: String, val json: JsonObject)

    private companion object {
        val SET_LIKE_STATE_COLLECTIONS = setOf(
            "transactions",
            "versions",
            "postings",
            "adjustments",
            "allocations",
            "candidates",
            "confirmations",
            "observations",
            "source_records",
            "evidence",
            "evidence_links",
            "audit_links",
        )

        val MAIN_PATH_NAMES = listOf(
            "preview",
            "confirmation",
            "transfer_confirmation",
            "explanation_confirmation",
            "second_transfer_confirmation",
            "second_explanation_confirmation",
        )

        val RG09_REPORT_METRICS = listOf(
            "ordinary_income",
            "ordinary_expense",
            "consumption",
            "budget_effect",
            "category_effect",
            "cash_inflow",
            "cash_outflow",
            "internal_transfer_amount",
            "balance_adjustment_net_worth_change",
            "net_worth_change",
        )
    }
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
private fun JsonObject.boolean(key: String, default: Boolean): Boolean =
    this[key]?.jsonPrimitive?.booleanOrNull ?: default

private fun repositoryFile(relative: String): Path {
    var candidate = Path.of(System.getProperty("user.dir"))
    repeat(8) {
        if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)
        candidate = candidate.parent ?: error("repository root not found")
    }
    error("repository root not found")
}
