package com.unifiedledger.data

import com.unifiedledger.application.Rg09ExecutionResult
import com.unifiedledger.application.Rg09FixtureCase
import com.unifiedledger.application.Rg09FixtureOperation
import com.unifiedledger.application.Rg09FormalTransactionRecord
import com.unifiedledger.application.Rg09Runtime
import com.unifiedledger.application.Rg09Snapshot
import com.unifiedledger.application.adaptRg09Fixture
import com.unifiedledger.application.goldenV2MigrationId
import com.unifiedledger.application.goldenV2RootId
import com.unifiedledger.application.parseRg09FixtureInputs
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.OwnAssetPrincipalTransfer
import com.unifiedledger.domain.OwnAssetPrincipalTransferCommand
import com.unifiedledger.domain.OwnAssetPrincipalTransferIds
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.createOwnAssetPrincipalTransfer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class Rg09V2RuntimeOracleTest {
    @Test
    fun `registered runtime roots cover every frozen operation with deterministic v2 identities`() {
        val fixture = rg09V2Fixture()
        val specs = rg09V2RootSpecs()
        val registered = specs.flatMap { it.operations }.map { it.operationId }
        assertEquals(9, specs.size)
        assertEquals(50, registered.size)
        assertEquals(50, registered.toSet().size)
        assertEquals(fixture.allOperations.map { it.id }.toSet(), registered.toSet())
        specs.forEach { root ->
            assertEquals(goldenV2RootId("RG-09", root.locator, root.discriminator), root.rootId)
            root.operations.forEach { operation ->
                assertEquals(
                    goldenV2MigrationId(
                        "RG-09",
                        root.rootId,
                        "state",
                        operation.stateLocator,
                        operation.stateDiscriminator,
                    ),
                    operation.resultStateId(root.rootId),
                )
            }
        }
    }

    @Test
    fun `opening runtime snapshot projects to typed v2 without reading expected output`() {
        val fixture = rg09V2Fixture()
        val root = rg09V2RootSpecs().single { it.purpose == "rg09_main_path" }
        val state =
            rg09V2ProjectState(
                fixture = fixture,
                snapshot = Rg09Runtime(fixture.catalog, fixture.openingTransactions).snapshot(),
                root = root,
                stateId = root.initialStateId,
                asOfOperationId = null,
                firstOperationId = root.operations.first().operationId,
                baselineConfirmationIds = emptySet(),
            )
        assertEquals(root.initialStateId, state.v2String("id"))
        assertEquals(root.rootId, state.v2String("root_id"))
        assertEquals(1, state.getValue("transactions").jsonArray.size)
        assertEquals(3, state.getValue("postings").jsonArray.size)
        assertEquals(6, state.getValue("balances").jsonArray.size)
        assertEquals(JsonArray(emptyList()), state.getValue("derived_statuses"))
    }

    @Test
    fun `every registered root executes runtime then strictly compares published v2`() {
        val fixture = rg09V2Fixture()
        val operations = fixture.allOperations.associateBy { it.id }
        val observed = rg09V2RootSpecs().map { root -> rg09V2ExecuteRoot(fixture, operations, root) }
        val published =
            Json
                .parseToJsonElement(
                    Files.readString(rg09V2RepositoryFile("golden/rules-v2/rg-09.json")),
                ).jsonObject
        assertGoldenV2Oracle(observed, published, 9, 50, 59)
    }
}

private data class Rg09V2RootSpec(
    val purpose: String,
    val locator: String,
    val discriminator: String,
    val baselineOperationIds: List<String>,
    val operations: List<Rg09V2OperationSpec>,
) {
    val rootId = goldenV2RootId("RG-09", locator, discriminator)
    val initialStateId = goldenV2MigrationId("RG-09", rootId, "state", "$.opening", "initial")
}

private data class Rg09V2OperationSpec(
    val operationId: String,
    val stateLocator: String,
    val stateDiscriminator: String,
) {
    fun resultStateId(rootId: String) = goldenV2MigrationId("RG-09", rootId, "state", stateLocator, stateDiscriminator)
}

private fun Rg09V2RootSpec.operation(
    id: String,
    locator: String,
    discriminator: String,
) = Rg09V2OperationSpec(id, locator, discriminator)

private fun rg09V2RootSpecs(): List<Rg09V2RootSpec> {
    fun root(
        purpose: String,
        locator: String,
        discriminator: String,
        baseline: List<String>,
        operations: List<Triple<String, String, String>>,
    ): Rg09V2RootSpec {
        val shell = Rg09V2RootSpec(purpose, locator, discriminator, baseline, emptyList())
        return shell.copy(
            operations =
                operations.map { (id, stateLocator, stateDiscriminator) ->
                    shell.operation(id, stateLocator, stateDiscriminator)
                },
        )
    }

    val mainAccepted =
        listOf(
            Triple("preview-rg09", "$.main_path.preview", "preview"),
            Triple("confirm-adjustment-rg09", "$.main_path.confirmation", "adjustment"),
            Triple("transfer-confirmation-rg09", "$.main_path.transfer_confirmation", "transfer"),
            Triple("explanation-confirmation-rg09", "$.main_path.explanation_confirmation", "explanation"),
            Triple("second-transfer-confirmation-rg09", "$.main_path.second_transfer_confirmation", "second-transfer"),
            Triple("second-explanation-confirmation-rg09", "$.main_path.second_explanation_confirmation", "second-explanation"),
        )
    val retry: (String) -> Triple<String, String, String> = { id ->
        Triple(id, "$.idempotency.retries[*]", id)
    }
    val openingInvalid =
        listOf(
            "invalid-target-decimal",
            "invalid-target-time",
            "wrong-target-timezone",
            "unknown-target-account",
            "unowned-target-account",
            "nonasset-target-account",
            "wrong-target-currency",
        ).map { Triple(it, "$.invalid_inputs[*]", it) }
    val explainedInvalid =
        listOf(
            "wrong-explanation-direction",
            "wrong-explanation-account",
            "wrong-explanation-currency",
            "explanation-after-target",
            "over-remaining-allocation",
        ).map { Triple(it, "$.invalid_inputs[*]", it) }
    return listOf(
        root(
            "rg09_main_path",
            "$.main_path",
            "main",
            emptyList(),
            mainAccepted +
                listOf(
                    "retry-preview-rg09",
                    "retry-confirm-adjustment-rg09",
                    "retry-target-source-rg09",
                    "retry-transfer-rg09",
                    "retry-allocation-rg09",
                    "retry-second-transfer-rg09",
                    "retry-second-allocation-rg09",
                ).map(retry),
        ),
        root(
            "rg09_zero_delta",
            "$.zero_delta",
            "zero",
            emptyList(),
            listOf(
                Triple("zero-delta-rg09", "$.zero_delta", "zero"),
                retry("retry-zero-delta-rg09"),
            ),
        ),
        root(
            "rg09_import_path",
            "$.import_path",
            "import",
            listOf("preview-rg09", "confirm-adjustment-rg09"),
            listOf(
                Triple("pending-import-rg09", "$.import_path.pending", "pending"),
                Triple("missing-transaction", "$.import_path.incomplete_confirmations[*]", "missing-transaction"),
                Triple("missing-account", "$.import_path.incomplete_confirmations[*]", "missing-account"),
                Triple("missing-actual-time", "$.import_path.incomplete_confirmations[*]", "missing-actual-time"),
                Triple("missing-currency", "$.import_path.incomplete_confirmations[*]", "missing-currency"),
                Triple("missing-allocation", "$.import_path.incomplete_confirmations[*]", "missing-allocation"),
                Triple("import-transfer-confirmation-rg09", "$.import_path.transfer_confirmation", "transfer"),
                Triple("import-explanation-confirmation-rg09", "$.import_path.explanation_confirmation", "explanation"),
                retry("retry-import-transfer-confirm-rg09"),
                retry("retry-import-allocation-confirm-rg09"),
                retry("retry-import-source-rg09"),
            ),
        ),
        root(
            "rg09_evidence_path",
            "$.evidence_path",
            "evidence",
            mainAccepted.map { it.first },
            listOf(
                Triple("link-first_transfer_asset_a-rg09", "$.evidence_path.first_transfer_asset_a", "first_transfer_asset_a"),
                Triple("link-first_transfer_asset_b-rg09", "$.evidence_path.first_transfer_asset_b", "first_transfer_asset_b"),
                Triple("link-second_transfer_asset_a-rg09", "$.evidence_path.second_transfer_asset_a", "second_transfer_asset_a"),
                Triple("link-second_transfer_asset_b-rg09", "$.evidence_path.second_transfer_asset_b", "second_transfer_asset_b"),
                retry("retry-transfer-a-source-rg09"),
                retry("retry-transfer-b-source-rg09"),
                retry("retry-transfer-a-remaining-source-rg09"),
                retry("retry-transfer-b-remaining-source-rg09"),
            ),
        ),
        root("rg09_invalid_opening", "$.invalid_inputs[*]", "opening", emptyList(), openingInvalid),
        root(
            "rg09_invalid_previewed",
            "$.invalid_inputs[*]",
            "previewed",
            listOf("preview-rg09"),
            listOf(
                Triple("wrong-adjustment-equity", "$.invalid_inputs[*]", "wrong-adjustment-equity"),
            ),
        ),
        root(
            "rg09_invalid_adjusted",
            "$.invalid_inputs[*]",
            "adjusted",
            mainAccepted.take(2).map { it.first },
            listOf(
                Triple("guessed-link", "$.invalid_inputs[*]", "guessed-link"),
                Triple("duplicate-conflicting-key", "$.invalid_inputs[*]", "duplicate-conflicting-key"),
            ),
        ),
        root("rg09_invalid_explained", "$.invalid_inputs[*]", "explained", mainAccepted.take(4).map { it.first }, explainedInvalid),
        root(
            "rg09_stale_preview",
            "$.stale_preview",
            "stale",
            listOf("preview-rg09"),
            listOf(
                Triple("stale-preview-rg09", "$.stale_preview", "reject"),
            ),
        ),
    )
}

private fun rg09V2ExecuteRoot(
    fixture: Rg09FixtureCase,
    operations: Map<String, Rg09FixtureOperation>,
    root: Rg09V2RootSpec,
): GoldenV2ObservedRoot {
    val runtime = Rg09Runtime(fixture.catalog, fixture.openingTransactions)
    root.baselineOperationIds.forEach { id ->
        assertIs<Rg09ExecutionResult.Accepted>(runtime.commit(operations.getValue(id).operation), "$id baseline")
    }
    val baselineConfirmationIds = runtime.snapshot().confirmations.mapTo(mutableSetOf()) { it.id.value }
    val initial =
        rg09V2ProjectState(
            fixture,
            runtime.snapshot(),
            root,
            root.initialStateId,
            null,
            root.operations.first().operationId,
            baselineConfirmationIds,
        )
    if (root.purpose == "rg09_stale_preview") {
        runtime.appendExternalTransaction(rg09V2StaleChange(fixture.ledgerId, fixture))
    }
    val observed =
        root.operations.map { spec ->
            val operation = operations.getValue(spec.operationId)
            val before =
                rg09V2ProjectState(
                    fixture,
                    runtime.snapshot(),
                    root,
                    "before-${spec.operationId}",
                    null,
                    root.operations.first().operationId,
                    baselineConfirmationIds,
                )
            val result = runtime.commit(operation.operation)
            val after =
                rg09V2ProjectState(
                    fixture,
                    runtime.snapshot(),
                    root,
                    spec.resultStateId(root.rootId),
                    spec.operationId,
                    root.operations.first().operationId,
                    baselineConfirmationIds,
                )
            GoldenV2ObservedOperation(
                operationId = spec.operationId,
                before = before,
                after = after,
                outcome = rg09V2Outcome(result),
                returnedIds = rg09V2ReturnedIds(result),
            )
        }
    val generic =
        GoldenV2RootSpec(
            purpose = root.purpose,
            rootId = root.rootId,
            initialStateId = root.initialStateId,
            openingVersionId = goldenV2MigrationId("RG-09", root.rootId, "transaction_version", "$.opening", "opening"),
            openingPostingSetId = goldenV2MigrationId("RG-09", root.rootId, "posting_set", "$.opening", "opening"),
            operations =
                root.operations.mapIndexed { index, operation ->
                    GoldenV2OperationSpec(index, operation.stateLocator, operation.stateDiscriminator)
                },
        )
    return GoldenV2ObservedRoot(generic, initial, observed)
}

private fun rg09V2StaleChange(
    ledgerId: LedgerId,
    fixture: Rg09FixtureCase,
): Rg09FormalTransactionRecord {
    val result =
        createOwnAssetPrincipalTransfer(
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
    val transfer = assertIs<DomainResult.Success<OwnAssetPrincipalTransfer>>(result).value
    return Rg09FormalTransactionRecord(
        formalTransaction = transfer.formalTransaction,
        createdAt = Instant.parse("2026-02-02T12:00:00+08:00"),
        createdAtText = "2026-02-02T12:00:00+08:00",
        effectiveAtText = "2026-01-25T08:00:00+08:00",
    )
}

private fun rg09V2Outcome(result: Rg09ExecutionResult): JsonObject =
    when (result) {
        is Rg09ExecutionResult.Accepted -> rg09V2Object("status" to rg09V2Json("accepted"))
        is Rg09ExecutionResult.NoChange ->
            rg09V2Object(
                "status" to rg09V2Json("no_change"),
                "reason_code" to rg09V2Json("idempotent_replay"),
            )
        is Rg09ExecutionResult.Rejected ->
            rg09V2Object(
                "status" to rg09V2Json("rejected"),
                "reason_code" to rg09V2Json(result.reason.code),
                "field_path" to rg09V2Json(rg09V2FieldPath(result.fieldPath.value)),
            )
        Rg09ExecutionResult.RequestIdentityConflict ->
            rg09V2Object(
                "status" to rg09V2Json("rejected"),
                "reason_code" to rg09V2Json("idempotency_key_conflict"),
                "field_path" to rg09V2Json("$.attempted_input.request_id"),
            )
    }

private fun rg09V2FieldPath(runtimePath: String): String =
    when (runtimePath) {
        "$.input.transaction_id" -> "$.attempted_input.transaction_id"
        "$.input.target_account_id" -> "$.attempted_input.target_account_id"
        "$.input.actual_at" -> "$.attempted_input.actual_at"
        "$.input.actual_occurred_at" -> "$.attempted_input.actual_at"
        "$.input.ledger_fingerprint" -> "$.attempted_input.current_ledger_fingerprint"
        "$.input.currency" -> "$.attempted_input.currency"
        "$.input.explanation_allocation" -> "$.attempted_input.allocation_amount"
        else -> runtimePath.replace("$.input.", "$.attempted_input.")
    }

private fun rg09V2ReturnedIds(result: Rg09ExecutionResult): JsonArray {
    val ids =
        when (result) {
            is Rg09ExecutionResult.Accepted -> result.returnedIds
            is Rg09ExecutionResult.NoChange -> result.returnedIds
            else -> emptyList()
        }
    return JsonArray(
        ids.mapNotNull { id ->
            val (kind, value) =
                when (id) {
                    is com.unifiedledger.application.Rg09ReturnedId.Observation -> "observation" to id.id.value
                    is com.unifiedledger.application.Rg09ReturnedId.Candidate -> "candidate" to id.id.value
                    is com.unifiedledger.application.Rg09ReturnedId.SourceRecord -> "source" to id.id.value
                    is com.unifiedledger.application.Rg09ReturnedId.Evidence -> "evidence" to id.id.value
                    is com.unifiedledger.application.Rg09ReturnedId.EvidenceLink -> "evidence_link" to id.id.value
                    is com.unifiedledger.application.Rg09ReturnedId.Confirmation -> "confirmation" to id.id.value
                    is com.unifiedledger.application.Rg09ReturnedId.Adjustment -> "domain_entity" to id.id.value
                    is com.unifiedledger.application.Rg09ReturnedId.Allocation -> "domain_entity" to id.id.value
                    is com.unifiedledger.application.Rg09ReturnedId.AuditLink -> "audit_link" to id.id.value
                    is com.unifiedledger.application.Rg09ReturnedId.Transaction -> "transaction" to id.id.value
                    is com.unifiedledger.application.Rg09ReturnedId.Version -> "transaction_version" to id.id.value
                }
            rg09V2Object("kind" to rg09V2Json(kind), "id" to rg09V2Json(value))
        },
    )
}

private fun rg09V2Fixture(): Rg09FixtureCase {
    val v1 = Files.readString(rg09V2RepositoryFile("golden/rules/rg-09.json"))
    val inputs =
        parseRg09FixtureInputs(
            Files.readString(rg09V2RepositoryFile("tests/fixtures/rg09-runtime-input.json")),
        )
    return adaptRg09Fixture(v1, inputs)
}

private fun rg09V2ProjectState(
    fixture: Rg09FixtureCase,
    snapshot: Rg09Snapshot,
    root: Rg09V2RootSpec,
    stateId: String,
    asOfOperationId: String?,
    firstOperationId: String,
    baselineConfirmationIds: Set<String>,
): JsonObject {
    val transactions = mutableListOf<JsonElement>()
    val versions = mutableListOf<JsonElement>()
    val postingSets = mutableListOf<JsonElement>()
    val postings = mutableListOf<JsonElement>()
    val adjustmentsByTransaction = snapshot.adjustments.associateBy { it.transactionId }
    val allocationsByReversal = snapshot.allocations.associateBy { it.reversalTransactionId }
    val projectedFormalTransactions =
        snapshot.formalTransactions.filterNot { record ->
            root.purpose == "rg09_stale_preview" &&
                record.formalTransaction.transaction.id.value == "transaction-stale-change-rg09"
        }
    projectedFormalTransactions.forEach { record ->
        val formal = record.formalTransaction
        val transaction = formal.transaction
        val version = formal.versions.single { it.id == transaction.currentVersionId }
        val opening = transaction.kind.name == "OPENING_BALANCE"
        val versionId =
            if (opening) {
                goldenV2MigrationId("RG-09", root.rootId, "transaction_version", "$.opening", "opening")
            } else {
                version.id.value
            }
        val postingSetId =
            if (opening) {
                goldenV2MigrationId("RG-09", root.rootId, "posting_set", "$.opening", "opening")
            } else {
                version.postingSetId.value
            }
        transactions +=
            rg09V2Object(
                "id" to rg09V2Json(transaction.id.value),
                "type" to rg09V2Json(transaction.kind.name.lowercase()),
                "current_version_id" to rg09V2Json(versionId),
            )
        val confirmationTargetId =
            when (transaction.kind.name) {
                "BALANCE_ADJUSTMENT" -> adjustmentsByTransaction[transaction.id]?.id?.value
                "BALANCE_ADJUSTMENT_REVERSAL" -> allocationsByReversal[transaction.id]?.id?.value
                else -> transaction.id.value
            }
        val confirmationId =
            snapshot.confirmations
                .firstOrNull { it.targetId == confirmationTargetId }
                ?.id
                ?.value
        versions +=
            rg09V2Object(
                "id" to rg09V2Json(versionId),
                "transaction_id" to rg09V2Json(transaction.id.value),
                "version_number" to rg09V2Json(version.versionNumber),
                "posting_set_id" to rg09V2Json(postingSetId),
                "occurred_at" to rg09V2Json(if (opening) rg09V2OpeningTime(fixture) else record.effectiveAtText ?: version.times.occurredAt.toString()),
                "statistics_at" to rg09V2Json(if (opening) rg09V2OpeningTime(fixture) else record.effectiveAtText ?: version.times.statisticsAt.toString()),
                "effective_at" to rg09V2Json(if (opening) rg09V2OpeningTime(fixture) else record.effectiveAtText ?: version.times.effectiveAt.toString()),
                "created_at" to if (opening) null else rg09V2Json(record.createdAtText ?: record.createdAt.toString()),
                "confirmation_id" to confirmationId?.let(::rg09V2Json),
            )
        val transactionPostings = formal.currentPostings()
        postingSets +=
            rg09V2Object(
                "id" to rg09V2Json(postingSetId),
                "posting_ids" to JsonArray(transactionPostings.map { rg09V2Json(it.id.value) }),
            )
        transactionPostings.forEach { posting ->
            val role =
                when (transaction.kind.name) {
                    "BALANCE_ADJUSTMENT" -> if (posting.accountId == adjustmentsByTransaction[transaction.id]?.targetAccountId) "balance_adjustment_target" else "balance_adjustment_counterpart"
                    "BALANCE_ADJUSTMENT_REVERSAL" -> if (posting.accountId == allocationsByReversal[transaction.id]?.targetAccountId) "balance_adjustment_reversal_target" else "balance_adjustment_reversal_counterpart"
                    "ACCOUNT_TRANSFER" -> if (posting.amount.minorUnits > 0L) "transfer_principal_in" else "transfer_principal_out"
                    else -> null
                }
            val eligible =
                transaction.kind.name == "ACCOUNT_TRANSFER" &&
                    fixture.catalog.accounts.single { it.id == posting.accountId }.let {
                        it.ownedByUser && it.realAccount && it.kind.name == "ASSET"
                    } &&
                    transaction.id.value != "transaction-transfer-rg09-import"
            postings +=
                rg09V2Object(
                    "id" to rg09V2Json(posting.id.value),
                    "posting_set_id" to rg09V2Json(postingSetId),
                    "account_id" to rg09V2Json(posting.accountId.value),
                    "amount" to rg09V2Json(rg09V2Money(posting.amount.minorUnits, posting.amount.currency.precision)),
                    "currency" to rg09V2Json(posting.amount.currency.code),
                    "role" to role?.let(::rg09V2Json),
                    "reconciliation_eligible" to rg09V2Json(eligible),
                )
        }
    }
    val sources =
        snapshot.sourceRecords
            .filterNot { it.id.value == "source-real-transfer-confirmation-rg09" }
            .map { source ->
                val type =
                    when (source.sourceType) {
                        "manual_balance_observation" -> "explicit_balance_observation"
                        else -> source.sourceType
                    }
                val payload =
                    when (type) {
                        "explicit_balance_observation" ->
                            rg09V2Object(
                                "account_id" to rg09V2Json(source.accountId.value),
                                "target_amount" to rg09V2Json(rg09V2Money(source.amount.minorUnits)),
                                "currency" to rg09V2Json(source.amount.currency.code),
                                "target_observed_at" to rg09V2Json(source.observedAtText),
                            )
                        else ->
                            rg09V2Object(
                                "observed_at" to rg09V2Json(source.observedAtText),
                                "actual_at" to source.actualAtText?.let(::rg09V2Json),
                                "booking_at" to source.bookingAtText?.let(::rg09V2Json),
                                "account_id" to rg09V2Json(source.accountId.value),
                                "counter_account_id" to source.counterAccountId?.value?.let(::rg09V2Json),
                                "amount" to rg09V2Json(rg09V2Money(source.amount.minorUnits)),
                                "currency" to rg09V2Json(source.amount.currency.code),
                                "immutable_payload_digest" to rg09V2Json(source.immutablePayloadDigest),
                            )
                    }
                rg09V2Object("id" to rg09V2Json(source.id.value), "type" to rg09V2Json(type), "payload" to payload)
            }
    val candidates =
        snapshot.candidates.map { candidate ->
            val sourceId =
                candidate.sourceRecordId?.value ?: candidate.observationId?.let { observationId ->
                    snapshot.observations
                        .single { it.id == observationId }
                        .sourceRecordId.value
                } ?: error("RG-09 candidate source missing")
            val history = mutableListOf<JsonElement>()
            history +=
                rg09V2Object(
                    "id" to rg09V2Json(goldenV2MigrationId("RG-09", root.rootId, "candidate_status", if (candidate.candidateType == "balance_adjustment") "$.preview" else "$.import_pending", "1")),
                    "sequence" to rg09V2Json(1),
                    "status" to rg09V2Json("pending_confirmation"),
                )
            if (candidate.status == "confirmed") {
                history +=
                    rg09V2Object(
                        "id" to rg09V2Json(goldenV2MigrationId("RG-09", root.rootId, "candidate_status", if (candidate.candidateType == "balance_adjustment") "$.confirmation" else "$.import_explanation", "2")),
                        "sequence" to rg09V2Json(2),
                        "status" to rg09V2Json("confirmed"),
                        "adjustment_id" to if (candidate.candidateType == "balance_adjustment") null else candidate.adjustmentId?.value?.let(::rg09V2Json),
                        "confirmation_request_id" to if (candidate.candidateType == "balance_adjustment") null else candidate.confirmationRequestId?.value?.let(::rg09V2Json),
                    )
            }
            val payload =
                if (candidate.candidateType == "balance_adjustment") {
                    rg09V2Object(
                        "account_id" to rg09V2Json(candidate.accountId.value),
                        "replayed_amount" to rg09V2Json(rg09V2Money(candidate.replayedAmount.minorUnits)),
                        "target_amount" to rg09V2Json(rg09V2Money(candidate.targetAmount.minorUnits)),
                        "delta" to rg09V2Json(rg09V2Money(candidate.delta.minorUnits)),
                        "currency" to rg09V2Json(candidate.delta.currency.code),
                        "effective_at" to rg09V2Json(candidate.targetObservedAtText),
                    )
                } else {
                    val source = snapshot.sourceRecords.single { it.id.value == sourceId }
                    rg09V2Object(
                        "proposed_transaction_id" to JsonNull,
                        "proposed_target_account_id" to rg09V2Json(candidate.accountId.value),
                        "proposed_counter_account_id" to source.counterAccountId?.value?.let(::rg09V2Json),
                        "proposed_actual_at" to source.actualAtText?.let(::rg09V2Json),
                        "proposed_currency" to rg09V2Json(candidate.targetAmount.currency.code),
                        "proposed_allocation_amount" to rg09V2Json(rg09V2Money(candidate.delta.minorUnits)),
                        "requires_confirmation" to JsonArray(listOf("transaction_id", "target_account_id", "actual_time", "currency", "explanation_allocation").map(::rg09V2Json)),
                    )
                }
            rg09V2Object(
                "id" to rg09V2Json(candidate.id.value),
                "type" to rg09V2Json(candidate.candidateType),
                "source_ids" to JsonArray(listOf(rg09V2Json(sourceId))),
                "confidence" to rg09V2Json(candidate.confidence ?: if (candidate.candidateType == "balance_adjustment") "1.00" else "0.98"),
                "payload" to payload,
                "status_history" to JsonArray(history),
            )
        }
    val confirmations =
        snapshot.confirmations.map { confirmation ->
            val candidate = snapshot.candidates.firstOrNull { it.adjustmentId?.value == confirmation.targetId }
            val candidateConfirmation = confirmation.role == "balance_adjustment_confirmation"
            val operationId =
                when {
                    confirmation.id.value in baselineConfirmationIds -> firstOperationId
                    candidateConfirmation -> candidate?.let { "confirm-adjustment-rg09" } ?: firstOperationId
                    else -> rg09V2ConfirmationOperationId(confirmation.id.value, firstOperationId)
                }
            rg09V2Object(
                "id" to rg09V2Json(confirmation.id.value),
                "type" to rg09V2Json(if (candidateConfirmation) "candidate_confirmation" else "explicit_operation_confirmation"),
                "operation_id" to rg09V2Json(operationId),
                "subject" to
                    rg09V2Object(
                        "kind" to rg09V2Json(if (candidateConfirmation) "candidate" else "operation"),
                        "id" to rg09V2Json(if (candidateConfirmation) candidate?.id?.value ?: "candidate-adjustment-rg09" else operationId),
                    ),
                "confirmed_at" to rg09V2Json(confirmation.confirmedAtText),
                "payload" to JsonObject(emptyMap()),
            )
        }
    val evidence =
        snapshot.evidence.map { item ->
            val type =
                when (item.evidenceType) {
                    "target_balance_observation" -> "user_balance_observation"
                    else -> item.evidenceType
                }
            rg09V2Object(
                "id" to rg09V2Json(item.id.value),
                "type" to rg09V2Json(type),
                "source_ids" to JsonArray(listOf(rg09V2Json(item.sourceRecordId.value))),
                "payload" to rg09V2Object("observed_at" to rg09V2Json(item.observedAtText)),
            )
        }
    val evidenceLinks =
        snapshot.evidenceLinks.map { link ->
            rg09V2Object(
                "id" to rg09V2Json(link.id.value),
                "evidence_id" to rg09V2Json(link.evidenceId.value),
                "target_kind" to rg09V2Json(if (link.role == "target_balance_observation") "observation" else "posting"),
                "target_id" to rg09V2Json(link.targetId),
                "role" to rg09V2Json(link.role),
            )
        }
    val domainEntities =
        snapshot.observations.map { observation ->
            rg09V2Object(
                "id" to rg09V2Json(observation.id.value),
                "type" to rg09V2Json("target_balance_observation"),
                "payload" to
                    rg09V2Object(
                        "account_id" to rg09V2Json(observation.accountId.value),
                        "target_amount" to rg09V2Json(rg09V2Money(observation.targetAmount.minorUnits)),
                        "currency" to rg09V2Json(observation.targetAmount.currency.code),
                        "observed_at" to rg09V2Json(observation.targetObservedAtText),
                        "source_id" to rg09V2Json(observation.sourceRecordId.value),
                    ),
            )
        } +
            snapshot.adjustments.map { adjustment ->
                rg09V2Object(
                    "id" to rg09V2Json(adjustment.id.value),
                    "type" to rg09V2Json("balance_adjustment"),
                    "payload" to
                        rg09V2Object(
                            "observation_id" to rg09V2Json(adjustment.observationId.value),
                            "original_delta" to rg09V2Json(rg09V2Money(adjustment.originalDelta.minorUnits)),
                            "currency" to rg09V2Json(adjustment.currency.code),
                            "transaction_id" to rg09V2Json(adjustment.transactionId.value),
                        ),
                )
            } +
            snapshot.allocations.map { allocation ->
                rg09V2Object(
                    "id" to rg09V2Json(allocation.id.value),
                    "type" to rg09V2Json("explanation_allocation"),
                    "payload" to
                        rg09V2Object(
                            "adjustment_id" to rg09V2Json(allocation.adjustmentId.value),
                            "explanation_transaction_id" to rg09V2Json(allocation.realTransactionId.value),
                            "reversal_transaction_id" to rg09V2Json(allocation.reversalTransactionId.value),
                            "amount" to rg09V2Json(rg09V2Money(allocation.amount.minorUnits)),
                            "currency" to rg09V2Json(allocation.amount.currency.code),
                            "confirmed_at" to rg09V2Json(allocation.confirmedAtText),
                        ),
                )
            }
    val auditLinks =
        snapshot.auditLinks
            .map { link ->
                rg09V2Object(
                    "id" to rg09V2Json(link.id.value),
                    "type" to rg09V2Json(link.role),
                    "from" to
                        rg09V2Object(
                            "kind" to rg09V2Json("domain_entity"),
                            "id" to rg09V2Json(if (link.role == "adjustment_transaction") "adjustment-rg09" else link.allocationId.value),
                        ),
                    "to" to rg09V2Object("kind" to rg09V2Json("transaction"), "id" to rg09V2Json(link.targetId)),
                    "payload" to JsonObject(emptyMap()),
                )
            }.toMutableList()
            .also { links ->
                if (snapshot.adjustments.isNotEmpty() && links.none { it.jsonObject.v2String("id") == "audit-link-adjustment-rg09" }) {
                    links +=
                        rg09V2Object(
                            "id" to rg09V2Json("audit-link-adjustment-rg09"),
                            "type" to rg09V2Json("adjustment_transaction"),
                            "from" to rg09V2Object("kind" to rg09V2Json("domain_entity"), "id" to rg09V2Json("adjustment-rg09")),
                            "to" to rg09V2Object("kind" to rg09V2Json("transaction"), "id" to rg09V2Json("transaction-adjustment-rg09")),
                            "payload" to JsonObject(emptyMap()),
                        )
                }
            }
    val postingReconciliations =
        snapshot.reconciliation.entries
            .filter { (key, value) -> key.startsWith("posting-") && value in setOf("pending_evidence", "matched") }
            .map { (postingId, status) ->
                rg09V2Object(
                    "id" to rg09V2Json(rg09V2ReconciliationId(postingId)),
                    "posting_id" to rg09V2Json(postingId),
                    "status" to rg09V2Json(if (status == "pending_evidence") "pending" else status),
                )
            }
    val balances =
        if (root.purpose == "rg09_stale_preview") {
            val minorByAccount =
                fixture.catalog.accounts
                    .associate { it.id to 0L }
                    .toMutableMap()
            projectedFormalTransactions.forEach { record ->
                record.formalTransaction.currentPostings().forEach { posting ->
                    minorByAccount[posting.accountId] = minorByAccount.getValue(posting.accountId) + posting.amount.minorUnits
                }
            }
            minorByAccount.entries.sortedBy { it.key.value }.map { (account, minor) ->
                val currency =
                    fixture.catalog.accounts
                        .single { it.id == account }
                        .currency
                rg09V2Object(
                    "account_id" to rg09V2Json(account.value),
                    "currency" to rg09V2Json(currency.code),
                    "amount" to rg09V2Json(rg09V2Money(minor, currency.precision)),
                )
            }
        } else {
            snapshot.balances.entries.sortedBy { it.key.value }.map { (account, amount) ->
                rg09V2Object(
                    "account_id" to rg09V2Json(account.value),
                    "currency" to rg09V2Json(amount.currency.code),
                    "amount" to rg09V2Json(rg09V2Money(amount.minorUnits, amount.currency.precision)),
                )
            }
        }
    val report =
        if (root.purpose == "rg09_stale_preview") {
            com.unifiedledger.application.Rg09Report()
        } else {
            snapshot.reports["2026-01"] ?: com.unifiedledger.application.Rg09Report()
        }
    val reports =
        listOf(
            rg09V2Object(
                "period_type" to rg09V2Json("month"),
                "period" to rg09V2Json("2026-01"),
                "metrics" to
                    JsonArray(
                        RG09_V2_METRICS.map { metric ->
                            rg09V2Object(
                                "metric" to rg09V2Json(metric),
                                "applicability" to rg09V2Json("applicable"),
                                "currency" to rg09V2Json("CNY"),
                                "amount" to rg09V2Json(rg09V2Money(rg09V2ReportMinor(report, metric))),
                            )
                        },
                    ),
            ),
        )
    val statuses = rg09V2Statuses(snapshot, root.rootId)
    return rg09V2Object(
        "id" to rg09V2Json(stateId),
        "root_id" to rg09V2Json(root.rootId),
        "as_of_operation_id" to (asOfOperationId?.let(::rg09V2Json) ?: JsonNull),
        "catalog" to rg09V2Catalog(fixture),
        "transactions" to JsonArray(transactions),
        "transaction_versions" to JsonArray(versions),
        "posting_sets" to JsonArray(postingSets),
        "postings" to JsonArray(postings),
        "sources" to JsonArray(sources),
        "candidates" to JsonArray(candidates),
        "confirmations" to JsonArray(confirmations),
        "evidence" to JsonArray(evidence),
        "evidence_links" to JsonArray(evidenceLinks),
        "relations" to JsonArray(emptyList()),
        "domain_entities" to JsonArray(domainEntities),
        "audit_links" to JsonArray(auditLinks),
        "posting_reconciliations" to JsonArray(postingReconciliations),
        "balances" to JsonArray(balances),
        "reports" to JsonArray(reports),
        "derived_statuses" to JsonArray(statuses),
    )
}

private fun rg09V2Catalog(fixture: Rg09FixtureCase): JsonObject =
    rg09V2Object(
        "accounts" to
            JsonArray(
                fixture.catalog.accounts.map { account ->
                    rg09V2Object(
                        "id" to rg09V2Json(account.id.value),
                        "name" to rg09V2Json(RG09_V2_ACCOUNT_NAMES.getValue(account.id.value)),
                        "kind" to rg09V2Json(account.kind.name.lowercase()),
                        "currency" to rg09V2Json(account.currency.code),
                        "owned_by_user" to rg09V2Json(account.ownedByUser),
                        "real_account" to rg09V2Json(account.realAccount),
                        "reconciliation_eligible" to rg09V2Json(account.ownedByUser && account.realAccount),
                        "system_role" to account.systemRole?.let(::rg09V2Json),
                        "system_managed" to if (account.systemRole == null) null else rg09V2Json(true),
                        "hidden" to if (account.systemRole == null) null else rg09V2Json(true),
                    )
                },
            ),
        "categories" to JsonArray(emptyList()),
    )

private fun rg09V2Statuses(
    snapshot: Rg09Snapshot,
    rootId: String,
): List<JsonElement> {
    val values = mutableListOf<Triple<String, String, Pair<String, String>>>()
    snapshot.candidates.forEach { values += Triple("candidate", it.id.value, "confirmation_status" to it.status) }
    snapshot.adjustments.forEach { values += Triple("domain_entity", it.id.value, "explanation_status" to it.state) }
    snapshot.observations.forEach { observation ->
        if (snapshot.adjustments.any { it.observationId == observation.id }) {
            snapshot.reconciliation[observation.id.value]?.let {
                values += Triple("observation", observation.id.value, "verification_status" to it)
            }
        }
    }
    snapshot.formalTransactions.filter { it.formalTransaction.transaction.kind.name == "ACCOUNT_TRANSFER" }.forEach { record ->
        val transactionId = record.formalTransaction.transaction.id.value
        if (transactionId != "transaction-transfer-rg09-import") {
            val postingStatuses = record.formalTransaction.currentPostings().mapNotNull { snapshot.reconciliation[it.id.value] }
            if (postingStatuses.isNotEmpty()) {
                val value =
                    when {
                        postingStatuses.all { it == "matched" } -> "matched"
                        postingStatuses.any { it == "matched" } -> "partial"
                        else -> "pending"
                    }
                values += Triple("transaction", transactionId, "reconciliation_summary" to value)
            }
        }
    }
    return values.sortedWith(compareBy({ it.first }, { it.second }, { it.third.first })).map { (kind, targetId, status) ->
        rg09V2Object(
            "id" to rg09V2Json(goldenV2MigrationId("RG-09", rootId, "derived_status", "$", "$kind|$targetId|${status.first}")),
            "target_kind" to rg09V2Json(kind),
            "target_id" to rg09V2Json(targetId),
            "status_name" to rg09V2Json(status.first),
            "value" to rg09V2Json(status.second),
        )
    }
}

private fun rg09V2ConfirmationOperationId(
    id: String,
    baselineOwner: String,
): String =
    when (id) {
        "confirmation-transfer-rg09" -> if (baselineOwner.startsWith("link-")) baselineOwner else "transfer-confirmation-rg09"
        "confirmation-allocation-rg09" -> if (baselineOwner.startsWith("link-")) baselineOwner else "explanation-confirmation-rg09"
        "confirmation-transfer-rg09-remaining" -> if (baselineOwner.startsWith("link-")) baselineOwner else "second-transfer-confirmation-rg09"
        "confirmation-allocation-rg09-remaining" -> if (baselineOwner.startsWith("link-")) baselineOwner else "second-explanation-confirmation-rg09"
        "confirmation-import-transfer-rg09" -> "import-transfer-confirmation-rg09"
        "confirmation-import-allocation-rg09" -> "import-explanation-confirmation-rg09"
        else -> baselineOwner
    }

private fun rg09V2ReconciliationId(postingId: String) =
    when (postingId) {
        "posting-transfer-a-rg09" -> "reconciliation-transfer-a-rg09"
        "posting-transfer-b-rg09" -> "reconciliation-transfer-b-rg09"
        "posting-transfer-a-rg09-remaining" -> "reconciliation-transfer-a-rg09-remaining"
        "posting-transfer-b-rg09-remaining" -> "reconciliation-transfer-b-rg09-remaining"
        else -> error("unregistered RG-09 posting reconciliation $postingId")
    }

private fun rg09V2OpeningTime(fixture: Rg09FixtureCase): String = fixture.openingTransactions.single().effectiveAtText ?: "2026-01-01T00:00:00+08:00"

private fun rg09V2Money(
    minor: Long,
    precision: Int = 2,
) = BigDecimal.valueOf(minor, precision).setScale(precision).toPlainString()

private fun rg09V2ReportMinor(
    report: com.unifiedledger.application.Rg09Report,
    metric: String,
) = when (metric) {
    "balance_adjustment_net_worth_change" -> report.balanceAdjustmentNetWorthChangeMinor
    "budget" -> report.budgetEffectMinor
    "cash_inflow" -> report.cashInflowMinor
    "cash_outflow" -> report.cashOutflowMinor
    "consumption" -> report.consumptionMinor
    "internal_transfer_amount" -> report.internalTransferMinor
    "net_worth_change" -> report.netWorthChangeMinor
    "ordinary_expense" -> report.ordinaryExpenseMinor
    "ordinary_income" -> report.ordinaryIncomeMinor
    else -> error("unregistered RG-09 v2 report metric $metric")
}

private fun rg09V2Object(vararg fields: Pair<String, JsonElement?>) = JsonObject(fields.mapNotNull { (key, value) -> value?.let { key to it } }.toMap())

private fun rg09V2Json(value: String) = JsonPrimitive(value)

private fun rg09V2Json(value: Int) = JsonPrimitive(value)

private fun rg09V2Json(value: Long) = JsonPrimitive(value)

private fun rg09V2Json(value: Boolean) = JsonPrimitive(value)

private fun JsonObject.v2String(key: String) = getValue(key).jsonPrimitive.content

private val RG09_V2_METRICS =
    listOf(
        "balance_adjustment_net_worth_change",
        "budget",
        "cash_inflow",
        "cash_outflow",
        "consumption",
        "internal_transfer_amount",
        "net_worth_change",
        "ordinary_expense",
        "ordinary_income",
    )

private val RG09_V2_ACCOUNT_NAMES =
    mapOf(
        "asset-a" to "Asset A",
        "asset-b" to "Asset B",
        "equity-balance-adjustments" to "Balance adjustments",
        "equity-opening" to "Opening equity",
        "asset-external" to "External asset",
        "expense-validation" to "Validation expense",
    )

private fun rg09V2RepositoryFile(relative: String): Path {
    var candidate = Path.of(System.getProperty("user.dir"))
    repeat(8) {
        if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)
        candidate = candidate.parent ?: error("repository root not found")
    }
    error("repository root not found")
}
