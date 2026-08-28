package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.ConfirmedCategoryRenameResult
import com.unifiedledger.application.ConfirmedIncomeReceipt
import com.unifiedledger.application.ConfirmedIncomeTransactionFactory
import com.unifiedledger.application.ConfirmedManualIncomeCommit
import com.unifiedledger.application.ConfirmedManualIncomeCommitIds
import com.unifiedledger.application.ConfirmedManualIncomeIdSource
import com.unifiedledger.application.ConfirmedManualIncomeResult
import com.unifiedledger.application.ExecuteConfirmedCategoryRename
import com.unifiedledger.application.ExecuteConfirmedManualIncome
import com.unifiedledger.application.ExecuteManualIncomeSave
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.ExplicitlyConfirmedCategoryRename
import com.unifiedledger.application.ManualIncomeSaveResult
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.Rg02DecodedInvalidOperation
import com.unifiedledger.application.Rg02DecodedOperation
import com.unifiedledger.application.Rg02JsonField
import com.unifiedledger.application.Rg02ManualIncomeAdaptResult
import com.unifiedledger.application.Rg02ManualIncomeContext
import com.unifiedledger.application.Rg02RawJsonCase
import com.unifiedledger.application.Rg02RawJsonDecodeResult
import com.unifiedledger.application.adaptRg02ManualIncomeInput
import com.unifiedledger.application.decodeRg02RawJson
import com.unifiedledger.application.goldenV2MigrationId
import com.unifiedledger.application.goldenV2RootId
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.AssetReceivedOrdinaryIncomeCommand
import com.unifiedledger.domain.AssetReceivedOrdinaryIncomeIds
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.OrdinaryIncomeViolation
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.createAssetReceivedOrdinaryIncome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * D-087 RG-02 full state/delta/status oracle: the frozen v1 main path (manual
 * income create / `category_rename` / idempotent retry), both independent
 * variants and the eight zero-side-effect rejections are independently executed
 * and compared exactly against the approved `rg-02-expected.json`
 * (11 roots / 13 operations / 24 states).
 *
 * The rename minimal closed loop seeds version 1 category names from the frozen
 * v1 catalog; [Rg02StateProjector] rebuilds `catalog` names and
 * `category_name_history` from the append-only history table. Posting roles,
 * eligibility, reconciliations and the derived summary are derived as in RG-01.
 */
class Rg02FullStateOracleTest {
    @Test
    fun `v1 independently executes every root before v2 validates all operations states and deltas`() {
        val caseId = "RG-02"
        val raw = Files.readString(rg02RepositoryFile("golden/rules/rg-02.json"))
        val v1 = Json.parseToJsonElement(raw).jsonObject
        val decoded = assertIs<Rg02RawJsonDecodeResult.Success>(decodeRg02RawJson(raw)).value
        val specs = rg02RootSpecs(caseId, v1)

        val observed = specs.map { executeRg02Root(caseId, it, decoded, v1) }

        val v2 =
            Json
                .parseToJsonElement(
                    Files.readString(rg02RepositoryFile("docs/migrations/golden-v2/rg-02-expected.json")),
                ).jsonObject
        assertGoldenV2Oracle(observed, v2, expectedRootCount = 11, expectedOperationCount = 13, expectedStateCount = 24)
    }
}

private fun rg02RootSpecs(
    caseId: String,
    v1: JsonObject,
): List<GoldenV2RootSpec> {
    fun root(
        purpose: String,
        rootLocator: String,
        rootDiscriminator: String,
        initialLocator: String,
        initialDiscriminator: String,
        operations: List<GoldenV2OperationSpec>,
        seedOpening: Boolean = true,
    ): GoldenV2RootSpec {
        val rootId = goldenV2RootId(caseId, rootLocator, rootDiscriminator)
        return GoldenV2RootSpec(
            purpose,
            rootId,
            goldenV2MigrationId(caseId, rootId, "state", initialLocator, initialDiscriminator),
            goldenV2MigrationId(caseId, rootId, "transaction_version", "$.opening.transactions[*]", "tx-opening-rg02-a"),
            goldenV2MigrationId(caseId, rootId, "posting_set", "$.opening.transactions[*]", "tx-opening-rg02-a"),
            operations,
            seedOpening = seedOpening,
        )
    }

    val specs = mutableListOf<GoldenV2RootSpec>()
    specs +=
        root(
            "rg02_main_path",
            "$.case.id",
            "RG-02",
            "$.opening",
            "tx-opening-rg02-a",
            listOf(
                GoldenV2OperationSpec(0, "$.create.request", "request-rg02-create"),
                GoldenV2OperationSpec(1, "$.category_rename.request", "income-category-salary"),
                GoldenV2OperationSpec(2, "$.idempotency.repeated_request_id", "request-rg02-create"),
            ),
        )
    v1.getValue("variants").jsonArray.forEachIndexed { index, item ->
        val variant = item.jsonObject
        val id = variant.string("id")
        val requestId = variant.getValue("request").jsonObject.string("request_id")
        specs +=
            root(
                "rg02_variant_${id.replace('-', '_')}",
                "$.variants[*]",
                id,
                "$.variants[*]",
                id,
                listOf(GoldenV2OperationSpec(3 + index, "$.variants[*].request", requestId)),
                seedOpening = false,
            )
    }
    v1.getValue("invalid_inputs").jsonArray.forEachIndexed { index, item ->
        val id = item.jsonObject.string("id")
        specs +=
            root(
                "rg02_rejected_${id.replace('-', '_')}",
                "$.invalid_inputs[*]",
                id,
                "$.opening",
                id,
                listOf(GoldenV2OperationSpec(5 + index, "$.invalid_inputs[*]", id)),
            )
    }
    return specs.sortedBy { it.rootId }
}

private fun executeRg02Root(
    caseId: String,
    spec: GoldenV2RootSpec,
    decoded: Rg02RawJsonCase,
    v1: JsonObject,
): GoldenV2ObservedRoot {
    val opSpecsByRequest =
        buildMap {
            spec.operations.forEach { operationSpec ->
                rg02RequestId(operationSpec, decoded)?.let { requestId -> putIfAbsent(requestId, operationSpec) }
            }
        }
    return executeGoldenV2Root(
        caseId = caseId,
        spec = spec,
        ledgerId = LedgerId(decoded.metadata.ledgerId),
        v1 = v1,
        requestId = { operationSpec -> rg02RequestId(operationSpec, decoded) },
        createRuntime = { database, driver, operationIdsByRequest ->
            seedRg02CategoryNameHistory(database, decoded.metadata.ledgerId, v1)
            val runtime = Rg02OracleRuntime(decoded, database, driver, caseId, spec)
            GoldenV2RootRuntime(
                projectState = Rg02StateProjector(caseId, database, decoded, v1, spec, operationIdsByRequest, opSpecsByRequest)::state,
                executeOperation = { operationSpec -> runtime.execute(operationSpec) },
            )
        },
    )
}

private fun rg02RequestId(
    operationSpec: GoldenV2OperationSpec,
    decoded: Rg02RawJsonCase,
): String? =
    when (operationSpec.index) {
        0 ->
            decoded.create.input.requestId
                .required()
        1 -> null
        2 -> decoded.retryRequestId
        3, 4 ->
            decoded.variants[operationSpec.index - 3]
                .input.requestId
                .required()
        else -> {
            val invalid = decoded.invalidInputs[operationSpec.index - 5]
            rg02InvalidRequestId(invalid.sourceId)
        }
    }

private fun rg02InvalidRequestId(sourceId: String): String =
    goldenV2MigrationId(
        "RG-02",
        goldenV2RootId("RG-02", "$.invalid_inputs[*]", sourceId),
        "request",
        "$.invalid_inputs[*]",
        sourceId,
    )

/** Seeds version 1 `CURRENT` name history for every frozen v1 catalog category. */
private fun seedRg02CategoryNameHistory(
    database: LedgerDatabase,
    ledgerId: String,
    v1: JsonObject,
) {
    v1.getValue("catalog").jsonObject.getValue("categories").jsonArray.forEach { element ->
        val category = element.jsonObject
        database.ledgerQueries.insertRg02CategoryNameHistory(
            ledger_id = ledgerId,
            category_id = category.string("id"),
            version_number = 1L,
            name = category.string("name"),
            status = "CURRENT",
        )
    }
}

/** Mirrors the pre-D-087 raw end-to-end harness, but with migration-derived identities. */
private class Rg02OracleRuntime(
    private val decoded: Rg02RawJsonCase,
    database: LedgerDatabase,
    driver: JdbcSqliteDriver,
    private val caseId: String,
    private val spec: GoldenV2RootSpec,
) {
    private val metadata = decoded.metadata
    private val catalog = decoded.catalog
    private val context =
        Rg02ManualIncomeContext(
            ledgerId = LedgerId(metadata.ledgerId),
            currency = CurrencyUnit(metadata.currency, metadata.precision),
            caseTimeZone = metadata.timezone,
            catalog = catalog,
        )
    private val incomePort = SqlDelightConfirmedManualIncomeCommitPort(database, driver)
    private val renamePort = SqlDelightConfirmedCategoryRenameCommitPort(database, driver)
    private val executeIncome =
        ExecuteManualIncomeSave(
            ExecuteConfirmedManualIncome(
                incomePort,
                ConfirmedManualIncomeIdSource {
                    activeInvalid?.let { invalidIds(it) } ?: incomeIds(requireNotNull(activeOperation))
                },
                ConfirmedIncomeTransactionFactory { snapshot, ids ->
                    createAssetReceivedOrdinaryIncome(
                        catalog,
                        AssetReceivedOrdinaryIncomeCommand(
                            snapshot.ledgerId,
                            snapshot.amount,
                            snapshot.categoryId,
                            snapshot.receivingAccountId,
                            TransactionTimes.collapsed(snapshot.occurredAt),
                        ),
                        ids.incomeIds,
                    ).let { created ->
                        when (created) {
                            is DomainResult.Failure -> created
                            is DomainResult.Success -> DomainResult.Success(ConfirmedManualIncomeCommit(ids.confirmationId, created.value))
                        }
                    }
                },
            ),
        )
    private var activeOperation: Rg02DecodedOperation? = null
    private var activeInvalid: Rg02DecodedInvalidOperation? = null

    fun execute(operationSpec: GoldenV2OperationSpec): GoldenV2OperationResult =
        when (operationSpec.index) {
            0 -> runAccepted(decoded.create)
            1 -> runRename()
            2 -> runRetry(decoded.create)
            3, 4 -> runAccepted(decoded.variants[operationSpec.index - 3])
            else -> runInvalid(operationSpec, decoded.invalidInputs[operationSpec.index - 5])
        }

    private fun incomeIds(operation: Rg02DecodedOperation): ConfirmedManualIncomeCommitIds {
        val requestId = operation.input.requestId.required()
        val locator = if (operation.source == "$.create") "$.create.request" else "$.variants[*].request"
        val expected = operation.expected
        return ConfirmedManualIncomeCommitIds(
            ConfirmationId(goldenV2MigrationId(caseId, spec.rootId, "confirmation", locator, requestId)),
            AssetReceivedOrdinaryIncomeIds(
                TransactionId(requireNotNull(expected.transactionId)),
                TransactionVersionId(requireNotNull(expected.versionId)),
                PostingSetId(requireNotNull(expected.postingSetId)),
                PostingId(expected.postings[0].id),
                PostingId(expected.postings[1].id),
            ),
        )
    }

    private fun invalidIds(invalid: Rg02DecodedInvalidOperation): ConfirmedManualIncomeCommitIds =
        ConfirmedManualIncomeCommitIds(
            ConfirmationId("confirmation-${invalid.sourceId}"),
            AssetReceivedOrdinaryIncomeIds(
                TransactionId("tx-${invalid.sourceId}"),
                TransactionVersionId("version-${invalid.sourceId}"),
                PostingSetId("set-${invalid.sourceId}"),
                PostingId("receiving-${invalid.sourceId}"),
                PostingId("income-${invalid.sourceId}"),
            ),
        )

    private fun runAccepted(operation: Rg02DecodedOperation): GoldenV2OperationResult {
        activeOperation = operation
        activeInvalid = null
        val parsed = assertIs<Rg02ManualIncomeAdaptResult.Success>(adaptRg02ManualIncomeInput(context, operation.input)).value
        val executed = assertIs<ManualIncomeSaveResult.Executed>(executeIncome.execute(parsed.saveInput)).result
        val created = assertIs<ConfirmedManualIncomeResult.Created>(executed)
        return acceptedIncomeResult(created.receipt)
    }

    private fun runRetry(operation: Rg02DecodedOperation): GoldenV2OperationResult {
        activeOperation = operation
        activeInvalid = null
        val parsed = assertIs<Rg02ManualIncomeAdaptResult.Success>(adaptRg02ManualIncomeInput(context, operation.input)).value
        val executed =
            assertIs<ManualIncomeSaveResult.Executed>(
                executeIncome.execute(parsed.saveInput.copy(requestId = RequestId(decoded.retryRequestId))),
            ).result
        val noChange = assertIs<ConfirmedManualIncomeResult.NoChange>(executed)
        return noChangeIncomeResult(noChange.receipt)
    }

    private fun runRename(): GoldenV2OperationResult {
        val request = decoded.unsupportedCategoryRename
        val result =
            ExecuteConfirmedCategoryRename(renamePort, catalog).execute(
                ExplicitlyConfirmedCategoryRename(
                    LedgerId(metadata.ledgerId),
                    CategoryId(request.categoryId),
                    request.newName,
                    ExplicitManualSave,
                ),
            )
        return when (result) {
            is ConfirmedCategoryRenameResult.Accepted ->
                GoldenV2OperationResult(jsonObjectOf("status" to JsonPrimitive("accepted")), JsonArray(emptyList()))
            is ConfirmedCategoryRenameResult.Rejected -> error("unexpected rename rejection ${result.violation}")
        }
    }

    private fun runInvalid(
        operationSpec: GoldenV2OperationSpec,
        invalid: Rg02DecodedInvalidOperation,
    ): GoldenV2OperationResult {
        activeOperation = null
        activeInvalid = invalid
        val requestId = rg02InvalidRequestId(invalid.sourceId)
        val parsed =
            assertIs<Rg02ManualIncomeAdaptResult.Success>(
                adaptRg02ManualIncomeInput(
                    context,
                    invalid.input.copy(
                        requestId = Rg02JsonField.Value(requestId),
                        occurredAt =
                            Rg02JsonField.Value(
                                decoded.create.input.occurredAt
                                    .required(),
                            ),
                        currency = Rg02JsonField.Value(metadata.currency),
                        note = Rg02JsonField.Value(""),
                        explicitConfirmation = Rg02JsonField.Value(true),
                    ),
                ),
            ).value
        val result = executeIncome.execute(parsed.saveInput)
        val fieldPath = requireNotNull(invalid.expected.fieldPath)
        return when (result) {
            is ManualIncomeSaveResult.InvalidInput -> rejectedIncomeResult("required", fieldPath)
            is ManualIncomeSaveResult.Executed -> {
                val rejected = assertIs<ConfirmedManualIncomeResult.Rejected>(result.result)
                val reason =
                    when (rejected.violation) {
                        OrdinaryIncomeViolation.AmountMustBePositive -> "must_be_positive"
                        OrdinaryIncomeViolation.SecondaryCategoryRequired -> "secondary_category_required"
                        OrdinaryIncomeViolation.CategoryInactive -> "category_inactive"
                        OrdinaryIncomeViolation.IncomeCategoryRequired -> "income_category_required"
                        else -> error("unexpected income violation ${rejected.violation}")
                    }
                rejectedIncomeResult(reason, fieldPath)
            }
        }
    }

    private fun acceptedIncomeResult(receipt: ConfirmedIncomeReceipt): GoldenV2OperationResult =
        GoldenV2OperationResult(
            jsonObjectOf("status" to JsonPrimitive("accepted")),
            JsonArray(listOf(jsonObjectOf("kind" to JsonPrimitive("transaction"), "id" to JsonPrimitive(receipt.transactionId.value)))),
        )

    private fun noChangeIncomeResult(receipt: ConfirmedIncomeReceipt): GoldenV2OperationResult =
        GoldenV2OperationResult(
            jsonObjectOf("status" to JsonPrimitive("no_change"), "reason_code" to JsonPrimitive("idempotent_replay")),
            JsonArray(listOf(jsonObjectOf("kind" to JsonPrimitive("transaction"), "id" to JsonPrimitive(receipt.transactionId.value)))),
        )

    private fun rejectedIncomeResult(
        reasonCode: String,
        fieldPath: String,
    ): GoldenV2OperationResult =
        GoldenV2OperationResult(
            jsonObjectOf(
                "status" to JsonPrimitive("rejected"),
                "reason_code" to JsonPrimitive(reasonCode),
                "field_path" to JsonPrimitive(fieldPath),
            ),
            JsonArray(emptyList()),
        )
}

private data class Rg02ProjectedTransaction(
    val id: String,
    val kind: String,
    val currentVersionId: String,
)

private data class Rg02ProjectedVersion(
    val id: String,
    val transactionId: String,
    val number: Long,
    val postingSetId: String,
    val occurredAt: String,
    val statisticsAt: String,
    val effectiveAt: String,
    val note: String?,
)

private data class Rg02ProjectedPosting(
    val id: String,
    val postingSetId: String,
    val accountId: String,
    val minor: Long,
    val currency: String,
    val precision: Long,
)

private data class Rg02ProjectedConfirmation(
    val id: String,
    val requestId: String,
    val transactionId: String,
)

private data class Rg02ProjectedNameVersion(
    val categoryId: String,
    val version: Long,
    val name: String,
    val status: String,
)

/**
 * D-087 RG-02 projection: category display names and `category_name_history`
 * are rebuilt from the append-only `rg02_category_name_history` table; the
 * remaining derived facts follow the same rules as RG-01.
 */
private class Rg02StateProjector(
    private val caseId: String,
    private val database: LedgerDatabase,
    private val decoded: Rg02RawJsonCase,
    private val v1: JsonObject,
    private val spec: GoldenV2RootSpec,
    private val operationIdsByRequest: Map<String, String>,
    private val opSpecsByRequest: Map<String, GoldenV2OperationSpec>,
) {
    private val ledger = decoded.metadata.ledgerId
    private val accountKindById = decoded.catalog.accounts.associate { it.id.value to it.kind }
    private val accountEligibleById = decoded.catalog.accounts.associate { it.id.value to (it.ownedByUser && it.realAccount) }
    private val fallbackReportDate =
        v1
            .getValue("create")
            .jsonObject
            .getValue("request")
            .jsonObject
            .string("occurred_at")
            .substring(0, 10)

    /** D-087: economic times keep the frozen v1 original text (`+08:00`), never the runtime-normalized UTC form. */
    private val originalTimesByRequest: Map<String, String> =
        buildMap {
            put(
                decoded.create.input.requestId
                    .required(),
                decoded.create.input.occurredAt
                    .required(),
            )
            decoded.variants.forEach { variant -> put(variant.input.requestId.required(), variant.input.occurredAt.required()) }
        }

    fun state(
        id: String,
        asOfOperationId: String?,
    ): JsonObject {
        val q = database.ledgerQueries
        val transactions =
            q
                .selectRg02AllTransactions(ledger) { transactionId, kind, currentVersionId ->
                    Rg02ProjectedTransaction(transactionId, kind, currentVersionId)
                }.executeAsList()
        val versions =
            q
                .selectRg02AllVersions(ledger) { versionId, transactionId, versionNumber, postingSetId, occurredAt, statisticsAt, effectiveAt, note ->
                    Rg02ProjectedVersion(versionId, transactionId, versionNumber, postingSetId, occurredAt, statisticsAt, effectiveAt, note)
                }.executeAsList()
        val postingSets = q.selectRg02AllPostingSets(ledger) { postingSetId, postingId -> postingSetId to postingId }.executeAsList()
        val postings =
            q
                .selectRg02AllPostings(ledger) { postingId, postingSetId, accountId, amountMinor, currencyCode, currencyPrecision ->
                    Rg02ProjectedPosting(postingId, postingSetId, accountId, amountMinor, currencyCode, currencyPrecision)
                }.executeAsList()
        val confirmations =
            q
                .selectRg02AllIncomeConfirmations(ledger) { confirmationId, requestId, transactionId ->
                    Rg02ProjectedConfirmation(confirmationId, requestId, transactionId)
                }.executeAsList()
        val nameHistory =
            q
                .selectRg02CategoryNameHistory(ledger) { categoryId, versionNumber, name, status ->
                    Rg02ProjectedNameVersion(categoryId, versionNumber, name, status)
                }.executeAsList()

        val kindByPostingSet =
            versions
                .filter { version -> transactions.any { it.currentVersionId == version.id } }
                .associate { it.postingSetId to transactions.single { t -> t.currentVersionId == it.id }.kind }
        val confirmationByVersion = confirmations.associate { it.transactionId to it }
        val balances = projectBalances(postings)
        val reports = projectReports(transactions, versions, postings, kindByPostingSet)
        return jsonObjectOf(
            "id" to JsonPrimitive(id),
            "root_id" to JsonPrimitive(spec.rootId),
            "as_of_operation_id" to (asOfOperationId?.let(::JsonPrimitive) ?: JsonNull),
            "catalog" to projectCatalog(nameHistory),
            "transactions" to
                JsonArray(
                    transactions.map { transaction ->
                        jsonObjectOf(
                            "id" to JsonPrimitive(transaction.id),
                            "type" to JsonPrimitive(transaction.kind.lowercase()),
                            "current_version_id" to JsonPrimitive(transaction.currentVersionId),
                        )
                    },
                ),
            "transaction_versions" to
                JsonArray(
                    versions.map { version ->
                        val confirmation = confirmationByVersion[version.transactionId]
                        val originalTimes = confirmation?.let { originalTimesByRequest[it.requestId] }
                        jsonObjectOf(
                            "id" to JsonPrimitive(version.id),
                            "transaction_id" to JsonPrimitive(version.transactionId),
                            "version_number" to JsonPrimitive(version.number),
                            "posting_set_id" to JsonPrimitive(version.postingSetId),
                            "occurred_at" to JsonPrimitive(originalTimes ?: version.occurredAt),
                            "statistics_at" to JsonPrimitive(originalTimes ?: version.statisticsAt),
                            "effective_at" to JsonPrimitive(originalTimes ?: version.effectiveAt),
                            "confirmation_id" to confirmation?.id?.let(::JsonPrimitive),
                            "note" to projectVersionNote(version, confirmation),
                        )
                    },
                ),
            "posting_sets" to
                JsonArray(
                    postingSets.groupBy({ it.first }, { it.second }).map { (setId, ids) ->
                        jsonObjectOf("id" to JsonPrimitive(setId), "posting_ids" to JsonArray(ids.map(::JsonPrimitive)))
                    },
                ),
            "postings" to
                JsonArray(
                    postings.map { posting ->
                        jsonObjectOf(
                            "id" to JsonPrimitive(posting.id),
                            "posting_set_id" to JsonPrimitive(posting.postingSetId),
                            "account_id" to JsonPrimitive(posting.accountId),
                            "amount" to JsonPrimitive(rg02Amount(posting.minor, posting.precision)),
                            "currency" to JsonPrimitive(posting.currency),
                            "role" to rg02Role(posting, kindByPostingSet)?.let(::JsonPrimitive),
                            "reconciliation_eligible" to JsonPrimitive(rg02Eligible(posting, kindByPostingSet)),
                        )
                    },
                ),
            "sources" to JsonArray(emptyList()),
            "candidates" to JsonArray(emptyList()),
            "confirmations" to
                JsonArray(
                    confirmations.map { confirmation ->
                        jsonObjectOf(
                            "id" to JsonPrimitive(confirmation.id),
                            "type" to JsonPrimitive("explicit_manual_save"),
                            "operation_id" to JsonPrimitive(operationIdsByRequest.getValue(confirmation.requestId)),
                            "subject" to
                                jsonObjectOf(
                                    "kind" to JsonPrimitive("operation"),
                                    "id" to JsonPrimitive(operationIdsByRequest.getValue(confirmation.requestId)),
                                ),
                            "payload" to JsonObject(emptyMap()),
                        )
                    },
                ),
            "evidence" to JsonArray(emptyList()),
            "evidence_links" to JsonArray(emptyList()),
            "relations" to JsonArray(emptyList()),
            "domain_entities" to JsonArray(emptyList()),
            "audit_links" to JsonArray(emptyList()),
            "posting_reconciliations" to JsonArray(projectReconciliations(transactions, versions, postings, confirmations, kindByPostingSet)),
            "balances" to balances,
            "reports" to reports,
            "derived_statuses" to JsonArray(projectDerivedStatuses(transactions, versions, confirmations)),
        )
    }

    private fun projectCatalog(nameHistory: List<Rg02ProjectedNameVersion>): JsonObject {
        val rawCatalog = v1.getValue("catalog").jsonObject
        val accountNames = rawCatalog.getValue("accounts").jsonArray.associate { it.jsonObject.string("id") to it.jsonObject.string("name") }
        val categoryNames = rawCatalog.getValue("categories").jsonArray.associate { it.jsonObject.string("id") to it.jsonObject.string("name") }
        val currentNames = nameHistory.filter { it.status == "CURRENT" }.associateBy { it.categoryId }
        return jsonObjectOf(
            "accounts" to
                JsonArray(
                    decoded.catalog.accounts.sortedBy { it.id.value }.map { account ->
                        jsonObjectOf(
                            "id" to JsonPrimitive(account.id.value),
                            "name" to JsonPrimitive(accountNames.getValue(account.id.value)),
                            "kind" to JsonPrimitive(account.kind.name.lowercase()),
                            "currency" to JsonPrimitive(account.currency.code),
                            "owned_by_user" to JsonPrimitive(account.ownedByUser),
                            "real_account" to JsonPrimitive(account.realAccount),
                            "reconciliation_eligible" to JsonPrimitive(account.ownedByUser && account.realAccount),
                        )
                    },
                ),
            "categories" to
                JsonArray(
                    rawCatalog.getValue("categories").jsonArray.sortedBy { it.jsonObject.string("id") }.map { element ->
                        val category = element.jsonObject
                        val id = category.string("id")
                        jsonObjectOf(
                            "id" to JsonPrimitive(id),
                            "name" to JsonPrimitive(currentNames[id]?.name ?: categoryNames.getValue(id)),
                            "parent_id" to category["parent_id"],
                            "posting_account_id" to category["posting_account_id"],
                            "active" to JsonPrimitive(category.getValue("active").jsonPrimitive.boolean),
                        )
                    },
                ),
            "category_name_history" to
                JsonArray(
                    nameHistory.sortedWith(compareBy({ it.categoryId }, { it.version })).map { entry ->
                        jsonObjectOf(
                            "category_id" to JsonPrimitive(entry.categoryId),
                            "name" to JsonPrimitive(entry.name),
                            "version" to JsonPrimitive(entry.version),
                            "status" to JsonPrimitive(entry.status.lowercase()),
                        )
                    },
                ),
        )
    }

    /**
     * The version note is emitted exactly when the frozen v1 input that created
     * the version declared a `note` member (main create declares it, the
     * variants do not); the value is the persisted note text.
     */
    private fun projectVersionNote(
        version: Rg02ProjectedVersion,
        confirmation: Rg02ProjectedConfirmation?,
    ): JsonElement? {
        if (version.note?.isNotEmpty() == true) return JsonPrimitive(version.note)
        if (confirmation == null) return null
        return if (confirmation.requestId in RG02_NOTE_DECLARED_REQUESTS) JsonPrimitive("") else null
    }

    private fun rg02Role(
        posting: Rg02ProjectedPosting,
        kindByPostingSet: Map<String, String>,
    ): String? {
        val kind = kindByPostingSet[posting.postingSetId] ?: return null
        if (kind == "OPENING_BALANCE") return null
        return when (accountKindById[posting.accountId]) {
            AccountKind.EXPENSE -> "expense"
            AccountKind.INCOME -> "income_classification"
            AccountKind.ASSET -> if (kind == "INCOME") "receiving_asset" else "payment_asset"
            else -> null
        }
    }

    private fun rg02Eligible(
        posting: Rg02ProjectedPosting,
        kindByPostingSet: Map<String, String>,
    ): Boolean {
        val kind = kindByPostingSet[posting.postingSetId] ?: return false
        if (kind == "OPENING_BALANCE") return false
        return accountEligibleById[posting.accountId] == true
    }

    private fun projectReconciliations(
        transactions: List<Rg02ProjectedTransaction>,
        versions: List<Rg02ProjectedVersion>,
        postings: List<Rg02ProjectedPosting>,
        confirmations: List<Rg02ProjectedConfirmation>,
        kindByPostingSet: Map<String, String>,
    ): List<JsonObject> =
        transactions.filter { it.kind != "OPENING_BALANCE" }.flatMap { transaction ->
            val confirmation = confirmations.single { it.transactionId == transaction.id }
            val opSpec = opSpecsByRequest.getValue(confirmation.requestId)
            val currentVersion = versions.single { it.id == transaction.currentVersionId }
            postings
                .filter { it.postingSetId == currentVersion.postingSetId && rg02Eligible(it, kindByPostingSet) }
                .map { posting ->
                    jsonObjectOf(
                        "id" to JsonPrimitive(goldenV2MigrationId(caseId, spec.rootId, "posting_reconciliation", opSpec.locator, opSpec.discriminator)),
                        "posting_id" to JsonPrimitive(posting.id),
                        "status" to JsonPrimitive("pending"),
                    )
                }
        }

    private fun projectDerivedStatuses(
        transactions: List<Rg02ProjectedTransaction>,
        versions: List<Rg02ProjectedVersion>,
        confirmations: List<Rg02ProjectedConfirmation>,
    ): List<JsonObject> =
        transactions.filter { it.kind != "OPENING_BALANCE" }.map { transaction ->
            val confirmation = confirmations.single { it.transactionId == transaction.id }
            val opSpec = opSpecsByRequest.getValue(confirmation.requestId)
            jsonObjectOf(
                "id" to JsonPrimitive(goldenV2MigrationId(caseId, spec.rootId, "derived_status", opSpec.locator, opSpec.discriminator)),
                "target_kind" to JsonPrimitive("transaction"),
                "target_id" to JsonPrimitive(transaction.id),
                "status_name" to JsonPrimitive("reconciliation_summary"),
                "value" to JsonPrimitive("pending"),
            )
        }

    private fun projectBalances(postings: List<Rg02ProjectedPosting>): JsonArray =
        JsonArray(
            decoded.catalog.accounts.sortedBy { it.id.value }.map { account ->
                val amount = postings.filter { it.accountId == account.id.value }.sumOf { it.minor }
                jsonObjectOf(
                    "account_id" to JsonPrimitive(account.id.value),
                    "currency" to JsonPrimitive(account.currency.code),
                    "amount" to JsonPrimitive(rg02Amount(amount, account.currency.precision.toLong())),
                )
            },
        )

    private fun projectReports(
        transactions: List<Rg02ProjectedTransaction>,
        versions: List<Rg02ProjectedVersion>,
        postings: List<Rg02ProjectedPosting>,
        kindByPostingSet: Map<String, String>,
    ): JsonArray {
        val formalDates =
            transactions.filter { it.kind != "OPENING_BALANCE" }.map { transaction ->
                versions.single { it.id == transaction.currentVersionId }.occurredAt.substring(0, 10)
            }
        val days = (if (formalDates.isEmpty()) listOf(fallbackReportDate) else formalDates).distinct().sorted()
        val periods = days.map { "day" to it } + listOf("month" to days.first().substring(0, 7))
        return JsonArray(
            periods.map { (type, period) ->
                val matchingVersions =
                    versions.filter { version ->
                        transactions.any { it.kind != "OPENING_BALANCE" && it.currentVersionId == version.id } &&
                            version.occurredAt.startsWith(period)
                    }
                val setIds = matchingVersions.map { it.postingSetId }.toSet()
                val selected = postings.filter { it.postingSetId in setIds }
                val consumption = selected.filter { rg02Role(it, kindByPostingSet) == "expense" }.sumOf { it.minor }
                val cashOutflow = -selected.filter { rg02Role(it, kindByPostingSet) == "payment_asset" }.sumOf { it.minor }
                val cashInflow = selected.filter { rg02Role(it, kindByPostingSet) == "receiving_asset" }.sumOf { it.minor }
                val income = -selected.filter { rg02Role(it, kindByPostingSet) == "income_classification" }.sumOf { it.minor }
                val values =
                    mapOf(
                        "budget" to null,
                        "cash_inflow" to cashInflow,
                        "cash_outflow" to cashOutflow,
                        "consumption" to consumption,
                        "income" to income,
                        "net_worth_change" to (income - consumption),
                    )
                jsonObjectOf(
                    "period_type" to JsonPrimitive(type),
                    "period" to JsonPrimitive(period),
                    "metrics" to
                        JsonArray(
                            values.toSortedMap().map { (metric, amount) ->
                                if (amount == null) {
                                    jsonObjectOf("metric" to JsonPrimitive(metric), "applicability" to JsonPrimitive("not_applicable"))
                                } else {
                                    jsonObjectOf(
                                        "metric" to JsonPrimitive(metric),
                                        "applicability" to JsonPrimitive("applicable"),
                                        "currency" to JsonPrimitive("CNY"),
                                        "amount" to JsonPrimitive(rg02Amount(amount, 2)),
                                    )
                                }
                            },
                        ),
                )
            },
        )
    }
}

private val RG02_NOTE_DECLARED_REQUESTS = setOf("request-rg02-create")

private fun rg02Amount(
    minor: Long,
    precision: Long,
): String = BigDecimal.valueOf(minor, precision.toInt()).setScale(precision.toInt()).toPlainString()

private fun jsonObjectOf(vararg fields: Pair<String, JsonElement?>): JsonObject = JsonObject(fields.mapNotNull { (key, value) -> value?.let { key to it } }.toMap())

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

private fun <T> Rg02JsonField<T>.required(): T = assertIs<Rg02JsonField.Value<T>>(this).value

private fun rg02RepositoryFile(relative: String): Path {
    var candidate = Path.of(System.getProperty("user.dir"))
    repeat(6) {
        if (Files.isRegularFile(candidate.resolve("settings.gradle.kts")) && Files.isDirectory(candidate.resolve("golden"))) {
            return candidate.resolve(relative).also { require(Files.isRegularFile(it)) }
        }
        candidate = candidate.parent ?: error("repository root not found")
    }
    error("repository root not found")
}
