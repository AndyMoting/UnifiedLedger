package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.ConfirmedIncomeTransactionFactory
import com.unifiedledger.application.ConfirmedManualIncomeCommit
import com.unifiedledger.application.ConfirmedManualIncomeCommitIds
import com.unifiedledger.application.ConfirmedManualIncomeCommitPort
import com.unifiedledger.application.ConfirmedManualIncomeIdSource
import com.unifiedledger.application.ConfirmedManualIncomeResult
import com.unifiedledger.application.ExecuteConfirmedManualIncome
import com.unifiedledger.application.ExecuteManualIncomeSave
import com.unifiedledger.application.ManualIncomeSaveResult
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.Rg02CategoryRenameProjection
import com.unifiedledger.application.Rg02DecodedCaseMetadata
import com.unifiedledger.application.Rg02DecodedInvalidOperation
import com.unifiedledger.application.Rg02DecodedManualIncomeInput
import com.unifiedledger.application.Rg02DecodedOperation
import com.unifiedledger.application.Rg02JsonField
import com.unifiedledger.application.Rg02ManualIncomeAdaptResult
import com.unifiedledger.application.Rg02ManualIncomeContext
import com.unifiedledger.application.Rg02ManualIncomeContractErrorReason
import com.unifiedledger.application.Rg02RawJsonCase
import com.unifiedledger.application.Rg02RawJsonContractError
import com.unifiedledger.application.Rg02RawJsonContractErrorReason
import com.unifiedledger.application.Rg02RawJsonDecodeResult
import com.unifiedledger.application.adaptRg02ManualIncomeInput
import com.unifiedledger.application.decodeRg02RawJson
import com.unifiedledger.application.goldenV2MigrationId
import com.unifiedledger.application.goldenV2RootId
import com.unifiedledger.application.projectRg02CategoryRename
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AssetReceivedOrdinaryIncomeCommand
import com.unifiedledger.domain.AssetReceivedOrdinaryIncomeIds
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.OrdinaryIncomeViolation
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.createAssetReceivedOrdinaryIncome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Rg02RawJsonEndToEndTest {
    @Test fun `tracked raw fixture executes income create retry variants and rejects sparse invalid attempts`() {
        val source = Files.readString(repoFile("golden/rules/rg-02.json"))
        val decoded = assertIs<Rg02RawJsonDecodeResult.Success>(decodeRg02RawJson(source)).value
        val v1DrivenOutcomes =
            buildList {
                addAll(runAccepted(decoded.create, decoded.metadata, decoded.catalog, retryRequestId = decoded.retryRequestId))
                decoded.variants.forEach { addAll(runAccepted(it, decoded.metadata, decoded.catalog)) }
            }.toMutableList()
        assertEquals(8, decoded.invalidInputs.size)
        decoded.invalidInputs.forEach { invalid ->
            assertEquals(false, invalid.expected.accepted)
            v1DrivenOutcomes +=
                runInvalid(
                    invalid,
                    decoded.create.input.occurredAt
                        .required(),
                    decoded.metadata,
                    decoded.catalog,
                )
        }
        val unsupportedRename =
            assertIs<Rg02CategoryRenameProjection.Unsupported>(
                projectRg02CategoryRename(decoded.unsupportedCategoryRename),
            )
        assertEquals("income-category-salary", unsupportedRename.request.categoryId)
        // Approved v2 is an operation-scoped oracle only and is intentionally loaded last.
        val approved = ApprovedRg02Outcomes.decode(Files.readString(repoFile("docs/migrations/golden-v2/rg-02-expected.json")))
        v1DrivenOutcomes.forEach { actual -> assertEquals(actual, approved.byRequest(actual.requestId, actual.status)) }
    }

    @Test fun `raw catalog mutations are execution inputs and leave no committed residue when rejected`() {
        val source = Files.readString(repoFile("golden/rules/rg-02.json"))
        val mutations =
            listOf(
                Triple(
                    source.replace(
                        "\"posting_account_id\": \"income-account-salary\"",
                        "\"posting_account_id\": \"missing-income-account\"",
                    ),
                    Rg02RawJsonContractErrorReason.INVALID_VALUE,
                    null,
                ),
                Triple(
                    source.replace(
                        "\"id\": \"income-category-salary\", \"name\": \"工资\", \"kind\": \"income\"",
                        "\"id\": \"income-category-salary\", \"name\": \"工资\", \"kind\": \"expense\"",
                    ),
                    Rg02RawJsonContractErrorReason.INVALID_VALUE,
                    null,
                ),
                Triple(
                    source.replace(
                        "\"id\": \"income-category-salary\", \"name\": \"工资\", \"kind\": \"income\", \"parent_id\": \"income-category-work\", \"posting_account_id\": \"income-account-salary\", \"active\": true",
                        "\"id\": \"income-category-salary\", \"name\": \"工资\", \"kind\": \"income\", \"parent_id\": \"income-category-work\", \"posting_account_id\": \"income-account-salary\", \"active\": false",
                    ),
                    null,
                    OrdinaryIncomeViolation.CategoryInactive,
                ),
                Triple(
                    source.replace(
                        "\"id\": \"asset-bank-a\", \"name\": \"银行卡 A\", \"kind\": \"asset\", \"real_account\": true",
                        "\"id\": \"asset-bank-a\", \"name\": \"银行卡 A\", \"kind\": \"asset\", \"real_account\": false",
                    ),
                    null,
                    DomainViolation.InvalidOrdinaryIncome,
                ),
            )

        mutations.forEach { (raw, decodeReason, domainViolation) ->
            if (decodeReason != null) {
                val error = assertIs<Rg02RawJsonDecodeResult.Invalid>(decodeRg02RawJson(raw)).error
                assertEquals(decodeReason, error.reason)
            } else {
                val decoded = assertIs<Rg02RawJsonDecodeResult.Success>(decodeRg02RawJson(raw)).value
                runCatalogRejected(decoded, checkNotNull(domainViolation))
            }
        }
    }

    @Test fun `raw expected posting and balance mutations cannot pass accepted execution`() {
        val source = Files.readString(repoFile("golden/rules/rg-02.json"))
        val expectedBalance = "\"balances\": {\"asset-bank-a\": \"4000.00\"}"
        val mutations =
            listOf(
                source.replaceFirst(
                    "{\"id\": \"posting-bank-rg02\", \"account_id\": \"asset-bank-a\", \"amount\": \"3000.00\", \"currency\": \"CNY\"}",
                    "{\"id\": \"posting-bank-rg02\", \"account_id\": \"asset-bank-a\", \"amount\": \"999.00\", \"currency\": \"CNY\"}",
                ),
                source.replaceFirst(expectedBalance, "\"balances\": {\"asset-bank-a\": \"999.00\"}"),
                source.replaceFirst(expectedBalance, "\"balances\": {}"),
                source.replaceFirst(expectedBalance, "\"balances\": {\"asset-wallet-b\": \"4000.00\"}"),
            )

        mutations.forEach { raw ->
            val decoded = assertIs<Rg02RawJsonDecodeResult.Success>(decodeRg02RawJson(raw)).value
            assertFailsWith<AssertionError> {
                runAccepted(decoded.create, decoded.metadata, decoded.catalog)
            }
        }
    }

    @Test fun `raw accepted effective false still decodes but cannot pass accepted execution`() {
        val source = Files.readString(repoFile("golden/rules/rg-02.json"))
        val mutated =
            source.replaceFirst(
                Regex("\"occurred_at\": \"2026-01-16T09:00:00\\+08:00\",\\s*\"effective\": true"),
                "\"occurred_at\": \"2026-01-16T09:00:00+08:00\", \"effective\": false",
            )
        assertTrue(mutated != source)
        val decoded = assertIs<Rg02RawJsonDecodeResult.Success>(decodeRg02RawJson(mutated)).value

        assertFailsWith<AssertionError> {
            runAccepted(decoded.create, decoded.metadata, decoded.catalog)
        }
    }

    @Test fun `raw request mutations return typed contract errors without commit or residue`() {
        val source = Files.readString(repoFile("golden/rules/rg-02.json"))
        val mutations =
            listOf(
                source.replaceFirst(
                    Regex("\"currency\": \"CNY\"(?=,\\s*\"category_id\": \"income-category-salary\")"),
                    "\"currency\": \"USD\"",
                ) to Rg02ManualIncomeContractErrorReason.CURRENCY_MISMATCH,
                source.replaceFirst("\"amount\": \"3000.00\"", "\"amount\": \"invalid\"") to
                    Rg02ManualIncomeContractErrorReason.INVALID_DECIMAL,
                source.replaceFirst("\"amount\": \"3000.00\"", "\"amount\": \"1.001\"") to
                    Rg02ManualIncomeContractErrorReason.INVALID_DECIMAL,
                source.replaceFirst("\"amount\": \"3000.00\"", "\"amount\": \"92233720368547758.08\"") to
                    Rg02ManualIncomeContractErrorReason.INVALID_DECIMAL,
                source.replaceFirst(
                    "\"occurred_at\": \"2026-01-16T09:00:00+08:00\"",
                    "\"occurred_at\": \"2026-01-16T09:00:00\"",
                ) to Rg02ManualIncomeContractErrorReason.INVALID_TIMESTAMP,
                source
                    .replaceFirst(
                        "\"request_id\": \"request-rg02-create\"",
                        "\"request_id\": \"\"",
                    ).replaceFirst(
                        "\"repeated_request_id\": \"request-rg02-create\"",
                        "\"repeated_request_id\": \"\"",
                    ) to Rg02ManualIncomeContractErrorReason.INVALID_ID,
            )
        val frozenCaseMutations =
            listOf(
                source.replace("\"currency\": \"CNY\"", "\"currency\": \"USD\"") to Rg02RawJsonContractError("$.case.currency", Rg02RawJsonContractErrorReason.INVALID_VALUE),
                source.replace("\"precision\": 2", "\"precision\": 3") to Rg02RawJsonContractError("$.case.precision", Rg02RawJsonContractErrorReason.INVALID_VALUE),
                source.replace("\"timezone\": \"Asia/Shanghai\"", "\"timezone\": \"Etc/UTC\"") to Rg02RawJsonContractError("$.case.timezone", Rg02RawJsonContractErrorReason.INVALID_VALUE),
                source.replace("\"ledger_id\": \"ledger-a\"", "\"ledger_id\": \"ledger-b\"") to Rg02RawJsonContractError("$.case.ledger_id", Rg02RawJsonContractErrorReason.INVALID_VALUE),
            )

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, sqliteProperties())
        LedgerDatabase.Schema.create(driver)
        driver.use {
            val database = LedgerDatabase(driver)
            var commitCalls = 0
            val execute =
                ExecuteManualIncomeSave(
                    ExecuteConfirmedManualIncome(
                        ConfirmedManualIncomeCommitPort { _, _, _ ->
                            commitCalls++
                            error("contract-invalid mutation must not commit")
                        },
                        ConfirmedManualIncomeIdSource { error("contract-invalid mutation must not allocate") },
                        ConfirmedIncomeTransactionFactory { _, _ -> error("contract-invalid mutation must not create") },
                    ),
                )
            frozenCaseMutations.forEach { (raw, expectedError) ->
                val error = assertIs<Rg02RawJsonDecodeResult.Invalid>(decodeRg02RawJson(raw)).error
                assertEquals(expectedError, error)
            }
            mutations.forEach { (raw, expectedReason) ->
                val decoded = assertIs<Rg02RawJsonDecodeResult.Success>(decodeRg02RawJson(raw)).value
                val result = adaptRg02ManualIncomeInput(decoded.metadata.asIncomeContext(decoded.catalog), decoded.create.input)
                if (result is Rg02ManualIncomeAdaptResult.Success) execute.execute(result.value.saveInput)
                val error = assertIs<Rg02ManualIncomeAdaptResult.InvalidContract>(result).error
                assertEquals(expectedReason, error.reason)
            }
            assertEquals(0, commitCalls)
            assertEquals(0L, database.ledgerQueries.countManualIncomeRequests().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countIncomeReceipts().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countVersions().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countPostings().executeAsOne())
        }
    }

    private fun runAccepted(
        operation: Rg02DecodedOperation,
        metadata: Rg02DecodedCaseMetadata,
        catalog: LedgerCatalog,
        retryRequestId: String? = null,
    ): List<Rg02OperationProjection> {
        val request = operation.input
        val expected = operation.expected
        assertTrue(expected.accepted)
        assertEquals(true, expected.effective)
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, sqliteProperties())
        LedgerDatabase.Schema.create(driver)
        driver.use {
            val database = LedgerDatabase(driver)
            val port = SqlDelightConfirmedManualIncomeCommitPort(database, driver)
            val context = metadata.asIncomeContext(catalog)
            val currency = context.currency
            val parsed =
                assertIs<Rg02ManualIncomeAdaptResult.Success>(
                    adaptRg02ManualIncomeInput(context, request),
                ).value
            var factoryCalls = 0
            val execute =
                ExecuteConfirmedManualIncome(
                    port,
                    ConfirmedManualIncomeIdSource {
                        ConfirmedManualIncomeCommitIds(
                            ConfirmationId("confirmation-${request.requestId.required()}"),
                            AssetReceivedOrdinaryIncomeIds(
                                TransactionId(checkNotNull(expected.transactionId)),
                                TransactionVersionId(checkNotNull(expected.versionId)),
                                PostingSetId(checkNotNull(expected.postingSetId)),
                                PostingId(expected.postingIds[0]),
                                PostingId(expected.postingIds[1]),
                            ),
                        )
                    },
                    ConfirmedIncomeTransactionFactory { snapshot, ids ->
                        factoryCalls++
                        createAssetReceivedOrdinaryIncome(catalog, AssetReceivedOrdinaryIncomeCommand(snapshot.ledgerId, snapshot.amount, snapshot.categoryId, snapshot.receivingAccountId, TransactionTimes.collapsed(snapshot.occurredAt)), ids.incomeIds).let {
                            when (it) {
                                is DomainResult.Success -> DomainResult.Success(ConfirmedManualIncomeCommit(ids.confirmationId, it.value))
                                is DomainResult.Failure -> it
                            }
                        }
                    },
                )
            val first =
                assertIs<ManualIncomeSaveResult.Executed>(
                    ExecuteManualIncomeSave(execute).execute(parsed.saveInput),
                ).result.let { assertIs<ConfirmedManualIncomeResult.Created>(it) }
            assertEquals(expected.transactionId, first.receipt.transactionId.value)
            assertEquals(
                expected.transactionId,
                database.ledgerQueries
                    .selectPersistedTransaction()
                    .executeAsOne()
                    .transaction_id,
            )
            assertEquals(
                "INCOME",
                database.ledgerQueries
                    .selectPersistedTransaction()
                    .executeAsOne()
                    .kind,
            )
            val version =
                database.ledgerQueries
                    .selectPersistedVersions()
                    .executeAsList()
                    .single()
            assertEquals(expected.versionId, version.version_id)
            assertEquals(expected.postingSetId, version.posting_set_id)
            assertEquals(request.occurredAt.required(), expected.occurredAt)
            val canonicalOccurredAt = parsed.saveInput.occurredAt.toString()
            assertEquals(canonicalOccurredAt, version.occurred_at)
            assertEquals(canonicalOccurredAt, version.statistics_at)
            assertEquals(canonicalOccurredAt, version.effective_at)
            val postings = database.ledgerQueries.selectPersistedPostings().executeAsList()
            assertEquals(expected.postings.map { it.id }, postings.map { it.posting_id })
            assertEquals(expected.postings.map { it.accountId }, postings.map { it.account_id })
            assertEquals(
                expected.postings.map { posting ->
                    parseExpectedMinorUnits(context, request, posting.amount)
                },
                postings.map { it.amount_minor },
            )
            assertEquals(expected.postings.map { it.currency }, postings.map { it.currency_code })
            val receivingAccountId = checkNotNull(parsed.saveInput.receivingAccountId).value
            assertEquals(setOf(receivingAccountId), expected.balances.keys)
            expected.balances.forEach { (accountId, balanceText) ->
                val expectedBalance = parseExpectedMinorUnits(context, request, balanceText)
                val openingBalance =
                    if (operation.source == "$.create") {
                        metadata.openingBalances[accountId]?.let { openingText ->
                            parseExpectedMinorUnits(context, request, openingText)
                        } ?: 0L
                    } else {
                        0L
                    }
                val expectedDelta = Math.subtractExact(expectedBalance, openingBalance)
                val assetPostingDelta = postings.single { it.account_id == accountId }.amount_minor
                assertEquals(expectedDelta, assetPostingDelta)
            }
            assertEquals(1L, database.ledgerQueries.countManualIncomeRequests().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countIncomeReceipts().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(2L, database.ledgerQueries.countPostings().executeAsOne())
            assertEquals(1, factoryCalls)
            val projections = mutableListOf(Rg02OperationProjection(request.requestId.required(), "accepted", null, null, setOf(Rg02ReturnedId("transaction", first.receipt.transactionId.value))))
            if (retryRequestId != null) {
                val replayInput = parsed.saveInput.copy(requestId = RequestId(retryRequestId))
                val replay =
                    assertIs<ManualIncomeSaveResult.Executed>(
                        ExecuteManualIncomeSave(execute).execute(replayInput),
                    ).result.let { assertIs<ConfirmedManualIncomeResult.NoChange>(it) }
                assertEquals(1, factoryCalls)
                assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
                projections += Rg02OperationProjection(retryRequestId, "no_change", "idempotent_replay", null, setOf(Rg02ReturnedId("transaction", replay.receipt.transactionId.value)))
            }
            return projections
        }
    }

    private fun runInvalid(
        operation: Rg02DecodedInvalidOperation,
        fallbackOccurredAt: String,
        metadata: Rg02DecodedCaseMetadata,
        catalog: LedgerCatalog,
    ): Rg02OperationProjection {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, sqliteProperties())
        LedgerDatabase.Schema.create(driver)
        driver.use {
            val database = LedgerDatabase(driver)
            val context = metadata.asIncomeContext(catalog)
            val currency = context.currency
            val input = operation.input
            var commitPortCalls = 0
            var factoryCalls = 0
            val sqlPort = SqlDelightConfirmedManualIncomeCommitPort(database, driver)
            val executeConfirmed =
                ExecuteConfirmedManualIncome(
                    ConfirmedManualIncomeCommitPort { identity, snapshot, callback ->
                        commitPortCalls++
                        sqlPort.commitOnce(identity, snapshot, callback)
                    },
                    ConfirmedManualIncomeIdSource { invalidIds(operation.sourceId) },
                    ConfirmedIncomeTransactionFactory { snapshot, ids ->
                        factoryCalls++
                        createAssetReceivedOrdinaryIncome(checkNotNull(context.catalog), AssetReceivedOrdinaryIncomeCommand(snapshot.ledgerId, snapshot.amount, snapshot.categoryId, snapshot.receivingAccountId, TransactionTimes.collapsed(snapshot.occurredAt)), ids.incomeIds).let { created ->
                            when (created) {
                                is DomainResult.Success -> DomainResult.Success(ConfirmedManualIncomeCommit(ids.confirmationId, created.value))
                                is DomainResult.Failure -> created
                            }
                        }
                    },
                )
            val requestId = rg02InvalidRequestId(operation.sourceId)
            val parsed =
                assertIs<Rg02ManualIncomeAdaptResult.Success>(
                    adaptRg02ManualIncomeInput(
                        context,
                        input.copy(
                            requestId = Rg02JsonField.Value(requestId),
                            occurredAt = Rg02JsonField.Value(fallbackOccurredAt),
                            currency = Rg02JsonField.Value(metadata.currency),
                            note = Rg02JsonField.Value(""),
                            explicitConfirmation = Rg02JsonField.Value(true),
                        ),
                    ),
                ).value
            val result = ExecuteManualIncomeSave(executeConfirmed).execute(parsed.saveInput)
            // Sparse attempts stop at the typed application boundary; complete attempts reach domain validation.
            val projection =
                when (result) {
                    is ManualIncomeSaveResult.InvalidInput -> {
                        assertEquals("required", operation.expected.reasonCode)
                        assertEquals(0, commitPortCalls)
                        assertEquals(0, factoryCalls)
                        Rg02OperationProjection(requestId, "rejected", "required", operation.expected.fieldPath, emptySet())
                    }
                    is ManualIncomeSaveResult.Executed -> {
                        val rejected = assertIs<ConfirmedManualIncomeResult.Rejected>(result.result)
                        assertEquals(1, commitPortCalls)
                        assertEquals(1, factoryCalls)
                        val reason =
                            when (rejected.violation) {
                                OrdinaryIncomeViolation.AmountMustBePositive -> "must_be_positive"
                                OrdinaryIncomeViolation.SecondaryCategoryRequired -> "secondary_category_required"
                                OrdinaryIncomeViolation.CategoryInactive -> "category_inactive"
                                OrdinaryIncomeViolation.IncomeCategoryRequired -> "income_category_required"
                                else -> error("unexpected income violation ${rejected.violation}")
                            }
                        Rg02OperationProjection(requestId, "rejected", reason, operation.expected.fieldPath, emptySet())
                    }
                }
            assertEquals(0L, database.ledgerQueries.countManualIncomeRequests().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countIncomeReceipts().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countVersions().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countPostings().executeAsOne())
            return projection
        }
    }
}

private fun <T> Rg02JsonField<T>.required(): T = assertIs<Rg02JsonField.Value<T>>(this).value

private fun parseExpectedMinorUnits(
    context: Rg02ManualIncomeContext,
    template: Rg02DecodedManualIncomeInput,
    amountText: String,
): Long =
    checkNotNull(
        assertIs<Rg02ManualIncomeAdaptResult.Success>(
            adaptRg02ManualIncomeInput(
                context,
                template.copy(amount = Rg02JsonField.Value(amountText)),
            ),
        ).value.saveInput.amount,
    ).minorUnits

private fun Rg02DecodedCaseMetadata.asIncomeContext(catalog: LedgerCatalog): Rg02ManualIncomeContext =
    Rg02ManualIncomeContext(
        ledgerId = LedgerId(ledgerId),
        currency = CurrencyUnit(currency, precision),
        caseTimeZone = timezone,
        catalog = catalog,
    )

private fun invalidIds(sourceId: String) = ConfirmedManualIncomeCommitIds(ConfirmationId("confirmation-$sourceId"), AssetReceivedOrdinaryIncomeIds(TransactionId("tx-$sourceId"), TransactionVersionId("version-$sourceId"), PostingSetId("set-$sourceId"), PostingId("receiving-$sourceId"), PostingId("income-$sourceId")))

private data class Rg02ReturnedId(
    val kind: String,
    val value: String,
)

private data class Rg02OperationProjection(
    val requestId: String,
    val status: String,
    val reasonCode: String?,
    val fieldPath: String?,
    val returnedIds: Set<Rg02ReturnedId>,
)

private class ApprovedRg02Outcomes private constructor(
    private val values: List<Rg02OperationProjection>,
) {
    fun byRequest(
        requestId: String,
        status: String,
    ): Rg02OperationProjection = values.single { it.requestId == requestId && it.status == status }

    companion object {
        fun decode(raw: String): ApprovedRg02Outcomes {
            val root = Json.parseToJsonElement(raw).jsonObject
            assertEquals(
                "approved",
                root
                    .getValue("case")
                    .jsonObject
                    .getValue("approval_status")
                    .jsonPrimitive.content,
            )
            val values =
                root.getValue("operations").jsonArray.mapNotNull { element ->
                    val operation = element.jsonObject
                    if (operation.getValue("action_type").jsonPrimitive.content != "manual_income") return@mapNotNull null
                    val attempted = (operation["input"] ?: operation["attempted_input"])!!.jsonObject
                    val outcome = operation.getValue("outcome").jsonObject
                    Rg02OperationProjection(
                        attempted.getValue("request_id").jsonPrimitive.content,
                        outcome.getValue("status").jsonPrimitive.content,
                        outcome["reason_code"]?.jsonPrimitive?.contentOrNull,
                        outcome["field_path"]?.jsonPrimitive?.contentOrNull,
                        operation
                            .getValue("returned_ids")
                            .jsonArray
                            .map { returned ->
                                val value = returned.jsonObject
                                Rg02ReturnedId(value.getValue("kind").jsonPrimitive.content, value.getValue("id").jsonPrimitive.content)
                            }.toSet(),
                    )
                }
            return ApprovedRg02Outcomes(values)
        }
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

private fun runCatalogRejected(
    decoded: Rg02RawJsonCase,
    expectedViolation: DomainViolation,
) {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, sqliteProperties())
    LedgerDatabase.Schema.create(driver)
    driver.use {
        val database = LedgerDatabase(driver)
        var successfulCallbacks = 0
        val context = decoded.metadata.asIncomeContext(decoded.catalog)
        val parsed =
            assertIs<Rg02ManualIncomeAdaptResult.Success>(
                adaptRg02ManualIncomeInput(context, decoded.create.input),
            ).value
        val execute =
            ExecuteManualIncomeSave(
                ExecuteConfirmedManualIncome(
                    SqlDelightConfirmedManualIncomeCommitPort(database, driver),
                    ConfirmedManualIncomeIdSource {
                        ConfirmedManualIncomeCommitIds(
                            ConfirmationId("confirmation-catalog-mutation"),
                            AssetReceivedOrdinaryIncomeIds(
                                TransactionId("tx-catalog-mutation"),
                                TransactionVersionId("version-catalog-mutation"),
                                PostingSetId("set-catalog-mutation"),
                                PostingId("receiving-catalog-mutation"),
                                PostingId("income-catalog-mutation"),
                            ),
                        )
                    },
                    ConfirmedIncomeTransactionFactory { snapshot, ids ->
                        createAssetReceivedOrdinaryIncome(
                            checkNotNull(context.catalog),
                            AssetReceivedOrdinaryIncomeCommand(snapshot.ledgerId, snapshot.amount, snapshot.categoryId, snapshot.receivingAccountId, TransactionTimes.collapsed(snapshot.occurredAt)),
                            ids.incomeIds,
                        ).let { created ->
                            when (created) {
                                is DomainResult.Failure -> created
                                is DomainResult.Success -> {
                                    successfulCallbacks++
                                    DomainResult.Success(ConfirmedManualIncomeCommit(ids.confirmationId, created.value))
                                }
                            }
                        }
                    },
                ),
            )
        val result = assertIs<ManualIncomeSaveResult.Executed>(execute.execute(parsed.saveInput)).result
        val rejected = assertIs<ConfirmedManualIncomeResult.Rejected>(result)
        assertEquals(expectedViolation, rejected.violation)
        assertEquals(0, successfulCallbacks)
        assertEquals(0L, database.ledgerQueries.countManualIncomeRequests().executeAsOne())
        assertEquals(0L, database.ledgerQueries.countIncomeReceipts().executeAsOne())
        assertEquals(0L, database.ledgerQueries.countTransactions().executeAsOne())
        assertEquals(0L, database.ledgerQueries.countVersions().executeAsOne())
        assertEquals(0L, database.ledgerQueries.countPostings().executeAsOne())
    }
}

private fun repoFile(relative: String): Path {
    var candidate = Path.of(System.getProperty("user.dir"))
    repeat(6) {
        if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)
        candidate = candidate.parent ?: error("repository root not found")
    }
    error("repository root not found")
}

private fun sqliteProperties() = Properties().apply { setProperty("foreign_keys", "true") }
