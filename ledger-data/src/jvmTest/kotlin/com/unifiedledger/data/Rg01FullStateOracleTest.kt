package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.ConfirmedExpenseTransactionFactory
import com.unifiedledger.application.ConfirmedManualExpenseCommit
import com.unifiedledger.application.ConfirmedManualExpenseCommitIds
import com.unifiedledger.application.ConfirmedManualExpenseCommitPort
import com.unifiedledger.application.ConfirmedManualExpenseIdSource
import com.unifiedledger.application.ConfirmedTransactionNoteUpdateCommitPort
import com.unifiedledger.application.ConfirmedTransactionNoteUpdateIdSource
import com.unifiedledger.application.ConfirmedTransactionNoteUpdateIds
import com.unifiedledger.application.ExecuteConfirmedManualExpense
import com.unifiedledger.application.ExecuteConfirmedTransactionNoteUpdate
import com.unifiedledger.application.ExecuteManualExpenseSave
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.ExplicitlyConfirmedTransactionNoteUpdate
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.Rg01AttemptedExpenseResult
import com.unifiedledger.application.Rg01DecodedManualExpenseInput
import com.unifiedledger.application.Rg01JsonField
import com.unifiedledger.application.Rg01ManualExpenseContext
import com.unifiedledger.application.Rg01ManualExpenseParseResult
import com.unifiedledger.application.Rg01OutcomeProjection
import com.unifiedledger.application.Rg01OutcomeStatus
import com.unifiedledger.application.Rg01ProjectionResult
import com.unifiedledger.application.Rg01RawJsonCase
import com.unifiedledger.application.Rg01RawJsonDecodeResult
import com.unifiedledger.application.decodeRg01RawJson
import com.unifiedledger.application.evaluateRg01AttemptedManualExpense
import com.unifiedledger.application.goldenV2MigrationId
import com.unifiedledger.application.goldenV2RootId
import com.unifiedledger.application.parseRg01ManualExpenseInput
import com.unifiedledger.application.projectRg01ManualExpenseResult
import com.unifiedledger.application.projectRg01TransactionNoteUpdateResult
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseCommand
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseIds
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionNoteUpdateIds
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.createAssetPaidOrdinaryExpense
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
 * D-087 RG-01 full state/delta/status oracle: every frozen v1 root (main create /
 * note update / idempotent retry / distinct re-entry plus the seven rejected
 * attempts) is independently executed against the runtime and the resulting
 * complete canonical states, deltas, status changes, outcomes and returned ids
 * are compared exactly against the approved `rg-01-expected.json`
 * (8 roots / 11 operations / 19 states).
 *
 * Posting roles, reconciliation eligibility, posting reconciliations and the
 * transaction reconciliation summary are derived by [Rg01StateProjector] from
 * the database plus the frozen v1 catalog; the runtime itself is unchanged.
 */
class Rg01FullStateOracleTest {
    @Test
    fun `v1 independently executes every root before v2 validates all operations states and deltas`() {
        val caseId = "RG-01"
        val raw = Files.readString(rg01RepositoryFile("golden/rules/rg-01.json"))
        val v1 = Json.parseToJsonElement(raw).jsonObject
        val decoded = assertIs<Rg01RawJsonDecodeResult.Success>(decodeRg01RawJson(raw)).value
        val specs = rg01RootSpecs(caseId, v1)

        val observed = specs.map { executeRg01Root(caseId, it, decoded, v1) }

        val v2 =
            Json
                .parseToJsonElement(
                    Files.readString(rg01RepositoryFile("docs/migrations/golden-v2/rg-01-expected.json")),
                ).jsonObject
        assertGoldenV2Oracle(observed, v2, expectedRootCount = 8, expectedOperationCount = 11, expectedStateCount = 19)
    }
}

private fun rg01RootSpecs(
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
    ): GoldenV2RootSpec {
        val rootId = goldenV2RootId(caseId, rootLocator, rootDiscriminator)
        return GoldenV2RootSpec(
            purpose,
            rootId,
            goldenV2MigrationId(caseId, rootId, "state", initialLocator, initialDiscriminator),
            goldenV2MigrationId(caseId, rootId, "transaction_version", "$.opening.transactions[*]", "tx-opening-a"),
            goldenV2MigrationId(caseId, rootId, "posting_set", "$.opening.transactions[*]", "tx-opening-a"),
            operations,
        )
    }

    val specs = mutableListOf<GoldenV2RootSpec>()
    specs +=
        root(
            "rg01_main_path",
            "$.case.id",
            "RG-01",
            "$.opening",
            "tx-opening-a",
            listOf(
                GoldenV2OperationSpec(0, "$.create.request", "request-rg01-create"),
                GoldenV2OperationSpec(1, "$.note_update.request", "request-rg01-note-update"),
                GoldenV2OperationSpec(2, "$.idempotency.repeated_request_id", "request-rg01-create"),
                GoldenV2OperationSpec(3, "$.distinct_reentry.request", "request-rg01-distinct-create"),
            ),
        )
    v1.getValue("invalid_inputs").jsonArray.forEachIndexed { index, item ->
        val id = item.jsonObject.string("id")
        specs +=
            root(
                "rg01_rejected_${id.replace('-', '_')}",
                "$.invalid_inputs[*]",
                id,
                "$.opening",
                id,
                listOf(
                    GoldenV2OperationSpec(
                        4 + index,
                        "$.invalid_inputs[*]",
                        id,
                        "$.invalid_inputs[*].expected",
                    ),
                ),
            )
    }
    return specs.sortedBy { it.rootId }
}

private fun executeRg01Root(
    caseId: String,
    spec: GoldenV2RootSpec,
    decoded: Rg01RawJsonCase,
    v1: JsonObject,
): GoldenV2ObservedRoot {
    val opSpecsByRequest =
        buildMap {
            spec.operations.forEach { operationSpec ->
                rg01RequestId(operationSpec, decoded)?.let { requestId -> putIfAbsent(requestId, operationSpec) }
            }
        }
    return executeGoldenV2Root(
        caseId = caseId,
        spec = spec,
        ledgerId = decoded.context.ledgerId,
        v1 = v1,
        requestId = { operationSpec -> rg01RequestId(operationSpec, decoded) },
        createRuntime = { database, driver, operationIdsByRequest ->
            val harness = Rg01OracleHarness(decoded, database, driver, caseId, spec)
            GoldenV2RootRuntime(
                projectState = Rg01StateProjector(caseId, database, decoded, v1, spec, operationIdsByRequest, opSpecsByRequest)::state,
                executeOperation = { operationSpec ->
                    val projection = harness.execute(operationSpec)
                    GoldenV2OperationResult(rg01Outcome(projection), rg01ReturnedIds(projection))
                },
            )
        },
    )
}

private fun rg01RequestId(
    operationSpec: GoldenV2OperationSpec,
    decoded: Rg01RawJsonCase,
): String? =
    when (operationSpec.index) {
        0 ->
            decoded.create.input.requestId
                .required()
        1 -> decoded.noteUpdate.input.requestId
        2 ->
            decoded.retry.input.requestId
                .required()
        3 ->
            decoded.distinct.input.requestId
                .required()
        else -> {
            val invalid = decoded.invalidInputs[operationSpec.index - 4]
            rg01InvalidRequestId(requireNotNull(invalid.source.sourceId))
        }
    }

/** Mirrors the pre-D-087 raw end-to-end harness, but with migration-derived identities. */
private class Rg01OracleHarness(
    private val decoded: Rg01RawJsonCase,
    database: LedgerDatabase,
    driver: JdbcSqliteDriver,
    private val caseId: String,
    private val spec: GoldenV2RootSpec,
) {
    private val context: Rg01ManualExpenseContext = decoded.context
    private var activeRequestId = ""
    private val transactionIds =
        mapOf(
            decoded.create.input.requestId
                .required() to requireNotNull(decoded.create.expected.transactionId),
            decoded.distinct.input.requestId
                .required() to requireNotNull(decoded.distinct.expected.transactionId),
        )
    private val expenseCommitPort =
        ConfirmedManualExpenseCommitPort { identity, snapshot, callback ->
            SqlDelightConfirmedManualExpenseCommitPort(database, driver).commitOnce(identity, snapshot, callback)
        }
    private val noteCommitPort =
        ConfirmedTransactionNoteUpdateCommitPort { identity, snapshot, callback ->
            SqlDelightConfirmedTransactionNoteUpdateCommitPort(database, driver).commitOnce(identity, snapshot, callback)
        }
    private val execute =
        ExecuteManualExpenseSave(
            ExecuteConfirmedManualExpense(
                expenseCommitPort,
                ConfirmedManualExpenseIdSource {
                    val transactionId = requireNotNull(transactionIds[activeRequestId])
                    val (locator, suffix) =
                        if (transactionId.endsWith("-distinct")) {
                            "$.distinct_reentry.request" to "rg01-distinct"
                        } else {
                            "$.create.request" to "rg01"
                        }
                    ConfirmedManualExpenseCommitIds(
                        ConfirmationId(goldenV2MigrationId(caseId, spec.rootId, "confirmation", locator, activeRequestId)),
                        AssetPaidOrdinaryExpenseIds(
                            TransactionId(transactionId),
                            TransactionVersionId("version-expense-$suffix-v1"),
                            PostingSetId("posting-set-expense-$suffix"),
                            PostingId("posting-expense-$suffix"),
                            PostingId("posting-bank-$suffix"),
                        ),
                    )
                },
                ConfirmedExpenseTransactionFactory { request, ids ->
                    when (
                        val created =
                            createAssetPaidOrdinaryExpense(
                                requireNotNull(context.catalog),
                                AssetPaidOrdinaryExpenseCommand(request.ledgerId, request.amount, request.categoryId, request.paymentAccountId, TransactionTimes.collapsed(request.occurredAt)),
                                ids.expenseIds,
                            )
                    ) {
                        is DomainResult.Failure -> created
                        is DomainResult.Success -> DomainResult.Success(ConfirmedManualExpenseCommit(ids.confirmationId, created.value))
                    }
                },
            ),
        )

    fun execute(operationSpec: GoldenV2OperationSpec): Rg01OutcomeProjection =
        when (operationSpec.index) {
            0 -> routeStrict(decoded.create.input)
            1 -> routeNoteUpdate()
            2 -> routeStrict(decoded.retry.input)
            3 -> routeStrict(decoded.distinct.input)
            else -> {
                val invalid = decoded.invalidInputs[operationSpec.index - 4]
                routeSparse(invalid.input, rg01InvalidRequestId(requireNotNull(invalid.source.sourceId)))
            }
        }

    private fun routeStrict(input: Rg01DecodedManualExpenseInput): Rg01OutcomeProjection {
        activeRequestId = input.requestId.required()
        val parsed = assertIs<Rg01ManualExpenseParseResult.Success>(parseRg01ManualExpenseInput(context, input)).value
        return assertIs<Rg01ProjectionResult.Mapped>(projectRg01ManualExpenseResult(execute.execute(parsed.saveInput))).projection
    }

    private fun routeSparse(
        input: Rg01DecodedManualExpenseInput,
        requestId: String,
    ): Rg01OutcomeProjection =
        assertIs<Rg01AttemptedExpenseResult.Mapped>(
            evaluateRg01AttemptedManualExpense(
                context,
                input.copy(requestId = Rg01JsonField.Value(requestId)),
            ),
        ).projection

    private fun routeNoteUpdate(): Rg01OutcomeProjection {
        val operation = decoded.noteUpdate
        val execute =
            ExecuteConfirmedTransactionNoteUpdate(
                noteCommitPort,
                ConfirmedTransactionNoteUpdateIdSource {
                    ConfirmedTransactionNoteUpdateIds(
                        ConfirmationId(goldenV2MigrationId(caseId, spec.rootId, "confirmation", "$.note_update.request", operation.input.requestId)),
                        TransactionNoteUpdateIds(TransactionVersionId(requireNotNull(operation.expected.versionId))),
                        TransactionVersionId(requireNotNull(decoded.create.expected.versionId)),
                    )
                },
            )
        return projectRg01TransactionNoteUpdateResult(
            execute.execute(
                ExplicitlyConfirmedTransactionNoteUpdate(
                    context.ledgerId,
                    RequestId(operation.input.requestId),
                    TransactionId(operation.input.transactionId),
                    operation.input.note,
                    ExplicitManualSave,
                ),
            ),
        )
    }
}

private fun rg01InvalidRequestId(sourceId: String): String =
    goldenV2MigrationId(
        "RG-01",
        goldenV2RootId("RG-01", "$.invalid_inputs[*]", sourceId),
        "request",
        "$.invalid_inputs[*].id",
        sourceId,
    )

private fun rg01Outcome(result: Rg01OutcomeProjection): JsonObject =
    when (result.status) {
        Rg01OutcomeStatus.ACCEPTED -> jsonObjectOf("status" to JsonPrimitive("accepted"))
        Rg01OutcomeStatus.NO_CHANGE ->
            jsonObjectOf(
                "status" to JsonPrimitive("no_change"),
                "reason_code" to JsonPrimitive("idempotent_replay"),
            )
        Rg01OutcomeStatus.REJECTED ->
            jsonObjectOf(
                "status" to JsonPrimitive("rejected"),
                "reason_code" to JsonPrimitive(requireNotNull(result.reasonCode)),
                "field_path" to JsonPrimitive(requireNotNull(result.fieldPath)),
            )
    }

private fun rg01ReturnedIds(result: Rg01OutcomeProjection): JsonArray =
    JsonArray(
        result.returnedIds.map { returned ->
            jsonObjectOf("kind" to JsonPrimitive(returned.kind), "id" to JsonPrimitive(returned.value))
        },
    )

private data class Rg01ProjectedTransaction(
    val id: String,
    val kind: String,
    val currentVersionId: String,
)

private data class Rg01ProjectedVersion(
    val id: String,
    val transactionId: String,
    val number: Long,
    val postingSetId: String,
    val occurredAt: String,
    val statisticsAt: String,
    val effectiveAt: String,
    val note: String?,
)

private data class Rg01ProjectedPosting(
    val id: String,
    val postingSetId: String,
    val accountId: String,
    val minor: Long,
    val currency: String,
    val precision: Long,
)

private data class Rg01ProjectedConfirmation(
    val id: String,
    val requestId: String,
    val transactionId: String,
    val versionId: String?,
)

/**
 * D-087 RG-01 projection: the runtime is untouched, so roles, eligibility,
 * reconciliations and the derived reconciliation summary are rebuilt from the
 * database plus the frozen v1 catalog. A note-update superseded version shares
 * its posting set with the current version, so every posting is classified
 * through the current version of its transaction.
 */
private class Rg01StateProjector(
    private val caseId: String,
    private val database: LedgerDatabase,
    private val decoded: Rg01RawJsonCase,
    private val v1: JsonObject,
    private val spec: GoldenV2RootSpec,
    private val operationIdsByRequest: Map<String, String>,
    private val opSpecsByRequest: Map<String, GoldenV2OperationSpec>,
) {
    private val ledger = decoded.context.ledgerId.value
    private val catalog = requireNotNull(decoded.context.catalog)
    private val accountKindById = catalog.accounts.associate { it.id.value to it.kind }
    private val accountEligibleById = catalog.accounts.associate { it.id.value to (it.ownedByUser && it.realAccount) }
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
        mapOf(
            decoded.create.input.requestId
                .required() to
                decoded.create.input.occurredAt
                    .required(),
            decoded.distinct.input.requestId
                .required() to
                decoded.distinct.input.occurredAt
                    .required(),
            decoded.noteUpdate.input.requestId to
                decoded.create.input.occurredAt
                    .required(),
        )

    fun state(
        id: String,
        asOfOperationId: String?,
    ): JsonObject {
        val q = database.ledgerQueries
        val transactions =
            q
                .selectRg01AllTransactions(ledger) { transactionId, kind, currentVersionId ->
                    Rg01ProjectedTransaction(transactionId, kind, currentVersionId)
                }.executeAsList()
        val versions =
            q
                .selectRg01AllVersions(ledger) { versionId, transactionId, versionNumber, postingSetId, occurredAt, statisticsAt, effectiveAt, note ->
                    Rg01ProjectedVersion(versionId, transactionId, versionNumber, postingSetId, occurredAt, statisticsAt, effectiveAt, note)
                }.executeAsList()
        val postingSets = q.selectRg01AllPostingSets(ledger) { postingSetId, postingId -> postingSetId to postingId }.executeAsList()
        val postings =
            q
                .selectRg01AllPostings(ledger) { postingId, postingSetId, accountId, amountMinor, currencyCode, currencyPrecision ->
                    Rg01ProjectedPosting(postingId, postingSetId, accountId, amountMinor, currencyCode, currencyPrecision)
                }.executeAsList()
        val expenseConfirmations =
            q
                .selectRg01AllExpenseConfirmations(ledger) { confirmationId, requestId, transactionId ->
                    Rg01ProjectedConfirmation(confirmationId, requestId, transactionId, null)
                }.executeAsList()
        val noteConfirmations =
            q
                .selectRg01AllNoteUpdateReceipts(ledger) { confirmationId, requestId, transactionId, versionId ->
                    Rg01ProjectedConfirmation(confirmationId, requestId, transactionId, versionId)
                }.executeAsList()
        val confirmations = expenseConfirmations + noteConfirmations

        val kindByPostingSet =
            versions
                .filter { version -> transactions.any { it.currentVersionId == version.id } }
                .associate { it.postingSetId to transactions.single { t -> t.currentVersionId == it.id }.kind }
        val noteByVersion = noteConfirmations.associate { requireNotNull(it.versionId) to it }
        val expenseByTransaction = expenseConfirmations.associate { it.transactionId to it }
        val balances = projectBalances(postings)
        val reports = projectReports(transactions, versions, postings, kindByPostingSet)
        return jsonObjectOf(
            "id" to JsonPrimitive(id),
            "root_id" to JsonPrimitive(spec.rootId),
            "as_of_operation_id" to (asOfOperationId?.let(::JsonPrimitive) ?: JsonNull),
            "catalog" to projectCatalog(),
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
                        val confirmation = confirmationFor(version, noteByVersion, expenseByTransaction)
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
                            "amount" to JsonPrimitive(rg01Amount(posting.minor, posting.precision)),
                            "currency" to JsonPrimitive(posting.currency),
                            "role" to rg01Role(posting, kindByPostingSet)?.let(::JsonPrimitive),
                            "reconciliation_eligible" to JsonPrimitive(rg01Eligible(posting, kindByPostingSet)),
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
            "posting_reconciliations" to JsonArray(projectReconciliations(transactions, versions, postings, expenseConfirmations, kindByPostingSet)),
            "balances" to balances,
            "reports" to reports,
            "derived_statuses" to JsonArray(projectDerivedStatuses(transactions, versions, expenseConfirmations)),
        )
    }

    private fun projectCatalog(): JsonObject {
        val rawCatalog = v1.getValue("catalog").jsonObject
        val accountNames = rawCatalog.getValue("accounts").jsonArray.associate { it.jsonObject.string("id") to it.jsonObject.string("name") }
        val categoryNames = rawCatalog.getValue("categories").jsonArray.associate { it.jsonObject.string("id") to it.jsonObject.string("name") }
        return jsonObjectOf(
            "accounts" to
                JsonArray(
                    catalog.accounts.sortedBy { it.id.value }.map { account ->
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
                        jsonObjectOf(
                            "id" to JsonPrimitive(category.string("id")),
                            "name" to JsonPrimitive(categoryNames.getValue(category.string("id"))),
                            "parent_id" to category["parent_id"],
                            "posting_account_id" to category["posting_account_id"],
                            "active" to JsonPrimitive(category.getValue("active").jsonPrimitive.boolean),
                        )
                    },
                ),
        )
    }

    /**
     * The version note is emitted exactly when the frozen v1 input that created
     * the version declared a `note` member (RG-01 always does); the value is
     * the persisted note text.
     */
    private fun projectVersionNote(
        version: Rg01ProjectedVersion,
        confirmation: Rg01ProjectedConfirmation?,
    ): JsonElement? {
        if (version.note?.isNotEmpty() == true) return JsonPrimitive(version.note)
        if (confirmation == null) return null
        return if (confirmation.requestId in RG01_NOTE_DECLARED_REQUESTS) JsonPrimitive("") else null
    }

    /**
     * A version's confirmation is the note-update receipt that appended it when
     * one exists, otherwise the creation receipt of its transaction's first
     * version. Opening versions have no confirmation.
     */
    private fun confirmationFor(
        version: Rg01ProjectedVersion,
        noteByVersion: Map<String, Rg01ProjectedConfirmation>,
        expenseByTransaction: Map<String, Rg01ProjectedConfirmation>,
    ): Rg01ProjectedConfirmation? = noteByVersion[version.id] ?: if (version.number == 1L) expenseByTransaction[version.transactionId] else null

    private fun rg01Role(
        posting: Rg01ProjectedPosting,
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

    private fun rg01Eligible(
        posting: Rg01ProjectedPosting,
        kindByPostingSet: Map<String, String>,
    ): Boolean {
        val kind = kindByPostingSet[posting.postingSetId] ?: return false
        if (kind == "OPENING_BALANCE") return false
        return accountEligibleById[posting.accountId] == true
    }

    private fun projectReconciliations(
        transactions: List<Rg01ProjectedTransaction>,
        versions: List<Rg01ProjectedVersion>,
        postings: List<Rg01ProjectedPosting>,
        creationConfirmations: List<Rg01ProjectedConfirmation>,
        kindByPostingSet: Map<String, String>,
    ): List<JsonObject> =
        transactions.filter { it.kind != "OPENING_BALANCE" }.flatMap { transaction ->
            val confirmation = creationConfirmations.single { it.transactionId == transaction.id }
            val opSpec = opSpecsByRequest.getValue(confirmation.requestId)
            val currentVersion = versions.single { it.id == transaction.currentVersionId }
            postings
                .filter { it.postingSetId == currentVersion.postingSetId && rg01Eligible(it, kindByPostingSet) }
                .map { posting ->
                    jsonObjectOf(
                        "id" to JsonPrimitive(goldenV2MigrationId(caseId, spec.rootId, "posting_reconciliation", opSpec.locator, opSpec.discriminator)),
                        "posting_id" to JsonPrimitive(posting.id),
                        "status" to JsonPrimitive("pending"),
                    )
                }
        }

    private fun projectDerivedStatuses(
        transactions: List<Rg01ProjectedTransaction>,
        versions: List<Rg01ProjectedVersion>,
        creationConfirmations: List<Rg01ProjectedConfirmation>,
    ): List<JsonObject> =
        transactions.filter { it.kind != "OPENING_BALANCE" }.map { transaction ->
            val confirmation = creationConfirmations.single { it.transactionId == transaction.id }
            val opSpec = opSpecsByRequest.getValue(confirmation.requestId)
            jsonObjectOf(
                "id" to JsonPrimitive(goldenV2MigrationId(caseId, spec.rootId, "derived_status", opSpec.locator, opSpec.discriminator)),
                "target_kind" to JsonPrimitive("transaction"),
                "target_id" to JsonPrimitive(transaction.id),
                "status_name" to JsonPrimitive("reconciliation_summary"),
                "value" to JsonPrimitive("pending"),
            )
        }

    private fun projectBalances(postings: List<Rg01ProjectedPosting>): JsonArray =
        JsonArray(
            catalog.accounts.sortedBy { it.id.value }.map { account ->
                val amount = postings.filter { it.accountId == account.id.value }.sumOf { it.minor }
                jsonObjectOf(
                    "account_id" to JsonPrimitive(account.id.value),
                    "currency" to JsonPrimitive(account.currency.code),
                    "amount" to JsonPrimitive(rg01Amount(amount, account.currency.precision.toLong())),
                )
            },
        )

    private fun projectReports(
        transactions: List<Rg01ProjectedTransaction>,
        versions: List<Rg01ProjectedVersion>,
        postings: List<Rg01ProjectedPosting>,
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
                val consumption = selected.filter { rg01Role(it, kindByPostingSet) == "expense" }.sumOf { it.minor }
                val cashOutflow = -selected.filter { rg01Role(it, kindByPostingSet) == "payment_asset" }.sumOf { it.minor }
                val income = -selected.filter { rg01Role(it, kindByPostingSet) == "income_classification" }.sumOf { it.minor }
                val values =
                    mapOf(
                        "budget" to null,
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
                                        "amount" to JsonPrimitive(rg01Amount(amount, 2)),
                                    )
                                }
                            },
                        ),
                )
            },
        )
    }
}

private val RG01_NOTE_DECLARED_REQUESTS =
    setOf(
        "request-rg01-create",
        "request-rg01-note-update",
        "request-rg01-distinct-create",
    )

private fun rg01Amount(
    minor: Long,
    precision: Long,
): String = BigDecimal.valueOf(minor, precision.toInt()).setScale(precision.toInt()).toPlainString()

private fun jsonObjectOf(vararg fields: Pair<String, JsonElement?>): JsonObject = JsonObject(fields.mapNotNull { (key, value) -> value?.let { key to it } }.toMap())

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

private fun <T> Rg01JsonField<T>.required(): T = assertIs<Rg01JsonField.Value<T>>(this).value

private fun rg01RepositoryFile(relative: String): Path {
    var candidate = Path.of(System.getProperty("user.dir"))
    repeat(6) {
        if (Files.isRegularFile(candidate.resolve("settings.gradle.kts")) && Files.isDirectory(candidate.resolve("golden"))) {
            return candidate.resolve(relative).also { require(Files.isRegularFile(it)) }
        }
        candidate = candidate.parent ?: error("repository root not found")
    }
    error("repository root not found")
}
