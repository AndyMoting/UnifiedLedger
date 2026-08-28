package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.Rg07AdaptResult
import com.unifiedledger.application.Rg07AdaptedCase
import com.unifiedledger.application.Rg07AllocateInput
import com.unifiedledger.application.Rg07ConfirmReceiptInput
import com.unifiedledger.application.Rg07DestinationEvidenceInput
import com.unifiedledger.application.Rg07DualRoleEvidenceInput
import com.unifiedledger.application.Rg07ExecutionResult
import com.unifiedledger.application.Rg07ImportConfirmationInput
import com.unifiedledger.application.Rg07ImportCreditInput
import com.unifiedledger.application.Rg07ManualExpenseInput
import com.unifiedledger.application.Rg07ManualReceiptInput
import com.unifiedledger.application.Rg07MirrorInput
import com.unifiedledger.application.Rg07Operation
import com.unifiedledger.application.Rg07OperationIdentity
import com.unifiedledger.application.Rg07OriginalPaymentEvidenceInput
import com.unifiedledger.application.Rg07ReturnedId
import com.unifiedledger.application.Rg07StatusInput
import com.unifiedledger.application.Rg07StatusSourceInput
import com.unifiedledger.application.Rg07StatusSourceState
import com.unifiedledger.application.Rg07ValidateInput
import com.unifiedledger.application.adaptRg07Allocate
import com.unifiedledger.application.adaptRg07ConfirmReceipt
import com.unifiedledger.application.adaptRg07DestinationEvidence
import com.unifiedledger.application.adaptRg07DualRoleEvidence
import com.unifiedledger.application.adaptRg07ImportConfirmation
import com.unifiedledger.application.adaptRg07ImportCredit
import com.unifiedledger.application.adaptRg07ManualExpense
import com.unifiedledger.application.adaptRg07ManualReceipt
import com.unifiedledger.application.adaptRg07Mirror
import com.unifiedledger.application.adaptRg07OriginalPaymentEvidence
import com.unifiedledger.application.adaptRg07Status
import com.unifiedledger.application.adaptRg07StatusSource
import com.unifiedledger.application.adaptRg07Validate
import com.unifiedledger.application.goldenV2MigrationId
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Transaction
import com.unifiedledger.domain.TransactionId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Executes every approved RG-07 operation through the real SQLDelight runtime and
 * compares the resulting outcome with the approved expected artifact. Each root is
 * an independent fixture case and runs on its own database, so cross-root identity
 * reuse (shared source ids) is valid.
 */
class Rg07RuntimeReplayTest {
    private fun loadExpected(): JsonObject {
        val path = repositoryFile("docs/migrations/golden-v2/rg-07-expected.json")
        return Json.parseToJsonElement(Files.readString(path)).jsonObject
    }

    @Test
    fun runtimeOutcomesAndReturnedIdsMatchApprovedExpected() {
        val expected = loadExpected()
        val ledgerId =
            LedgerId(
                expected
                    .getValue("case")
                    .jsonObject
                    .getValue("ledger_id")
                    .jsonPrimitive.content,
            )
        val operations = expected.getValue("operations").jsonArray
        val catalog = buildCatalog(expected)
        val rootsById =
            expected.getValue("roots").jsonArray.associate {
                it.jsonObject
                    .getValue("id")
                    .jsonPrimitive.content to
                    it.jsonObject
                        .getValue("purpose")
                        .jsonPrimitive.content
            }
        val manualConfirmedAt = Instant.parse("2026-01-10T12:01:00+08:00")

        val byRoot =
            operations.groupBy {
                it.jsonObject
                    .getValue("root_id")
                    .jsonPrimitive.content
            }
        val statesById =
            expected.getValue("states").jsonArray.associate {
                it.jsonObject
                    .getValue("id")
                    .jsonPrimitive.content to it.jsonObject
            }
        val actual = mutableMapOf("accepted" to 0, "no_change" to 0, "rejected" to 0)
        byRoot.forEach { (rootId, rootOperations) ->
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedBaseline(
                database,
                ledgerId,
                statesById,
                rootOperations
                    .first()
                    .jsonObject
                    .getValue("baseline_state_id")
                    .jsonPrimitive.content,
            )
            val identity = Rg07ReplayIdentitySource(rootId, rootsById.getValue(rootId), manualConfirmedAt)
            val store =
                SqlDelightRg07Store(
                    database,
                    driver,
                    catalog.catalog,
                    catalog.storeCreditAccountIds,
                    identity,
                )
            val projector = Rg07StateProjector(driver, ledgerId.value, rootId, rootsById.getValue(rootId), catalog.catalog, catalog.storeCreditAccountIds.map { it.value }.toSet())
            rootOperations.forEach { element ->
                val operation = element.jsonObject
                val baselineStateId = operation.getValue("baseline_state_id").jsonPrimitive.content
                val before = projector.state(baselineStateId, null)
                assertEquals(
                    comparableRg07State(statesById.getValue(baselineStateId)),
                    comparableRg07State(before),
                    "${operation.getValue("id").jsonPrimitive.content} baseline state",
                )
                val adapted = adaptOperation(ledgerId, catalog, operation)
                val result = store.commit(adapted)
                val actualOutcome = outcome(result)
                val status = actualOutcome.getValue("status").jsonPrimitive.content
                actual[status] = (actual[status] ?: 0) + 1
                val operationId = operation.getValue("id").jsonPrimitive.content
                assertEquals(operation.getValue("outcome"), actualOutcome, "$operationId outcome")
                assertEquals(operation.getValue("returned_ids"), returnedIds(result), "$operationId returned_ids")
                val resultStateId = operation.getValue("result_state_id").jsonPrimitive.content
                val actualState = projector.state(resultStateId, operationId)
                val expectedPayload = comparableRg07State(statesById.getValue(resultStateId))
                val actualPayload = comparableRg07State(actualState)
                assertEquals(
                    expectedPayload,
                    actualPayload,
                    "$operationId state",
                )
                assertEquals(operation.getValue("deltas"), goldenV2Deltas(before, actualState), "$operationId deltas")
                assertEquals(
                    operation.getValue("status_changes"),
                    goldenV2StatusChanges(before, actualState),
                    "$operationId status_changes",
                )
                if (status != "accepted") {
                    assertEquals(goldenV2StatePayload(before), goldenV2StatePayload(actualState), "$operationId residue")
                }
            }
            driver.close()
        }
        assertEquals(16, actual["accepted"])
        assertEquals(12, actual["no_change"])
        assertEquals(21, actual["rejected"])
    }

    private fun outcome(result: Rg07ExecutionResult): JsonObject =
        when (result) {
            is Rg07ExecutionResult.Accepted -> jsonObjectOf("status" to JsonPrimitive("accepted"))
            is Rg07ExecutionResult.NoChange ->
                jsonObjectOf(
                    "status" to JsonPrimitive("no_change"),
                    "reason_code" to JsonPrimitive("idempotent_replay"),
                )
            is Rg07ExecutionResult.Rejected ->
                jsonObjectOf(
                    "status" to JsonPrimitive("rejected"),
                    "reason_code" to JsonPrimitive(result.reason.code),
                    "field_path" to JsonPrimitive(result.fieldPath.value),
                )
            is Rg07ExecutionResult.RequestIdentityConflict -> jsonObjectOf("status" to JsonPrimitive("conflict"))
        }

    private fun returnedIds(result: Rg07ExecutionResult): JsonArray {
        val ids =
            when (result) {
                is Rg07ExecutionResult.Accepted -> result.returnedIds
                is Rg07ExecutionResult.NoChange -> result.returnedIds
                is Rg07ExecutionResult.Rejected, is Rg07ExecutionResult.RequestIdentityConflict -> emptyList()
            }
        return JsonArray(
            ids.map { returned ->
                val (kind, id) =
                    when (returned) {
                        is Rg07ReturnedId.Confirmation -> "confirmation" to returned.id
                        is Rg07ReturnedId.Transaction -> "transaction" to returned.id
                        is Rg07ReturnedId.Source -> "source" to returned.id
                        is Rg07ReturnedId.Evidence -> "evidence" to returned.id
                        is Rg07ReturnedId.EvidenceLink -> "evidence_link" to returned.id
                        is Rg07ReturnedId.Candidate -> "candidate" to returned.id
                        is Rg07ReturnedId.Relation -> "relation" to returned.id
                        is Rg07ReturnedId.DomainEntity -> "domain_entity" to returned.id
                    }
                jsonObjectOf("kind" to JsonPrimitive(kind), "id" to JsonPrimitive(id))
            },
        )
    }

    // ------------------------------------------------------------------
    // Baseline seeding
    // ------------------------------------------------------------------

    /**
     * Seeds the formal entities owned by the operation's baseline state into the
     * database before the root's operations run. Every non-manual root starts
     * from a baseline that already contains the opening transaction and the
     * original expense (transactions, versions, posting sets, postings, posting
     * semantics, and reconciliation records).
     */
    private fun seedBaseline(
        database: LedgerDatabase,
        ledgerId: LedgerId,
        statesById: Map<String, JsonObject>,
        baselineStateId: String,
    ) {
        val state = statesById[baselineStateId] ?: return
        // Transactions, versions, posting sets must be seeded in dependency order.
        state.getValue("transactions").jsonArray.forEach { tx ->
            val t = tx.jsonObject
            database.ledgerQueries.insertTransaction(
                t.getValue("id").jsonPrimitive.content,
                ledgerId.value,
                when (t.getValue("type").jsonPrimitive.content) {
                    "opening_balance" -> "OPENING_BALANCE"
                    "refund_receipt" -> "REFUND_RECEIPT"
                    else -> "EXPENSE"
                },
            )
        }
        state.getValue("posting_sets").jsonArray.forEach { ps ->
            val p = ps.jsonObject
            database.ledgerQueries.insertPostingSet(p.getValue("id").jsonPrimitive.content, ledgerId.value)
        }
        state.getValue("transaction_versions").jsonArray.forEach { v ->
            val version = v.jsonObject
            database.ledgerQueries.insertTransactionVersion(
                version.getValue("id").jsonPrimitive.content,
                version.getValue("transaction_id").jsonPrimitive.content,
                ledgerId.value,
                version
                    .getValue("version_number")
                    .jsonPrimitive.content
                    .toLong(),
                version.getValue("posting_set_id").jsonPrimitive.content,
                version.getValue("occurred_at").jsonPrimitive.content,
                version.getValue("statistics_at").jsonPrimitive.content,
                version.getValue("effective_at").jsonPrimitive.content,
                version["note"]?.jsonPrimitive?.content,
            )
            version["created_at"]?.jsonPrimitive?.content?.let { createdAt ->
                database.ledgerQueries.insertRg07TransactionVersionMetadata(
                    ledgerId.value,
                    version.getValue("id").jsonPrimitive.content,
                    createdAt,
                    version["confirmation_id"]?.jsonPrimitive?.content,
                )
            }
        }
        state.getValue("transactions").jsonArray.forEach { tx ->
            val t = tx.jsonObject
            val txId = t.getValue("id").jsonPrimitive.content
            val version =
                state.getValue("transaction_versions").jsonArray.firstOrNull {
                    it.jsonObject
                        .getValue("transaction_id")
                        .jsonPrimitive.content == txId
                } ?: return@forEach
            database.ledgerQueries.insertTransactionCurrentVersion(
                txId,
                ledgerId.value,
                version.jsonObject
                    .getValue("id")
                    .jsonPrimitive.content,
            )
        }
        state.getValue("postings").jsonArray.forEach { p ->
            val posting = p.jsonObject
            val index =
                state
                    .getValue("postings")
                    .jsonArray
                    .indexOfFirst { it == p }
                    .toLong()
            database.ledgerQueries.insertPosting(
                posting.getValue("id").jsonPrimitive.content,
                posting.getValue("posting_set_id").jsonPrimitive.content,
                ledgerId.value,
                index,
                posting.getValue("account_id").jsonPrimitive.content,
                parseMoney(posting.getValue("amount").jsonPrimitive.content, posting.getValue("currency").jsonPrimitive.content).minorUnits,
                posting.getValue("currency").jsonPrimitive.content,
                2L,
            )
            val role = posting["role"]?.jsonPrimitive?.content
            val categoryId = posting["category_id"]?.jsonPrimitive?.content
            if (role != null || categoryId != null) {
                database.ledgerQueries.insertRg07PostingSemantic(
                    ledgerId.value,
                    posting.getValue("id").jsonPrimitive.content,
                    role ?: "expense",
                    categoryId,
                    if (posting["reconciliation_eligible"]?.jsonPrimitive?.content?.toBoolean() == true) 1L else 0L,
                )
            }
        }
        state.getValue("posting_reconciliations").jsonArray.forEach { r ->
            val rec = r.jsonObject
            database.ledgerQueries.insertRg07Reconciliation(
                ledgerId.value,
                rec.getValue("id").jsonPrimitive.content,
                rec.getValue("posting_id").jsonPrimitive.content,
            )
            database.ledgerQueries.insertRg07ReconciliationHistory(
                ledgerId.value,
                rec.getValue("id").jsonPrimitive.content,
                1,
                rec
                    .getValue("status")
                    .jsonPrimitive.content
                    .uppercase(),
                null,
            )
        }
    }

    // ------------------------------------------------------------------
    // Catalog
    // ------------------------------------------------------------------

    private data class ReplayCatalog(
        val catalog: LedgerCatalog,
        val storeCreditAccountIds: Set<AccountId>,
    )

    private fun buildCatalog(expected: JsonObject): ReplayCatalog {
        val ledgerId =
            LedgerId(
                expected
                    .getValue("case")
                    .jsonObject
                    .getValue("ledger_id")
                    .jsonPrimitive.content,
            )
        val catalog =
            expected
                .getValue("states")
                .jsonArray
                .first()
                .jsonObject
                .getValue("catalog")
                .jsonObject
        val accounts =
            catalog.getValue("accounts").jsonArray.map { account ->
                val a = account.jsonObject
                Account(
                    AccountId(a.getValue("id").jsonPrimitive.content),
                    ledgerId,
                    when (a.getValue("kind").jsonPrimitive.content) {
                        "asset" -> AccountKind.ASSET
                        "liability" -> AccountKind.LIABILITY
                        "equity" -> AccountKind.EQUITY
                        "income" -> AccountKind.INCOME
                        "expense" -> AccountKind.EXPENSE
                        else -> AccountKind.ASSET
                    },
                    CurrencyUnit(a.getValue("currency").jsonPrimitive.content, 2),
                    a
                        .getValue("owned_by_user")
                        .jsonPrimitive.content
                        .toBoolean(),
                    a
                        .getValue("real_account")
                        .jsonPrimitive.content
                        .toBoolean(),
                )
            }
        val categories =
            catalog.getValue("categories").jsonArray.map { category ->
                val c = category.jsonObject
                Category(
                    CategoryId(c.getValue("id").jsonPrimitive.content),
                    ledgerId,
                    c["parent_id"]?.takeUnless { it is JsonNull }?.let { CategoryId(it.jsonPrimitive.content) },
                    c["posting_account_id"]?.takeUnless { it is JsonNull }?.let { AccountId(it.jsonPrimitive.content) },
                    c
                        .getValue("active")
                        .jsonPrimitive.content
                        .toBoolean(),
                )
            }
        val created = LedgerCatalog.create(accounts, categories)
        val resolved = created as DomainResult.Success<LedgerCatalog>
        val storeCreditIds = accounts.filter { it.id.value.contains("store-credit") }.map { it.id }.toSet()
        return ReplayCatalog(resolved.value, storeCreditIds)
    }

    // ------------------------------------------------------------------
    // Operation adaptation (strict typed, no default-value synthesis)
    // ------------------------------------------------------------------

    private fun adaptOperation(
        ledgerId: LedgerId,
        catalog: ReplayCatalog,
        operation: JsonObject,
    ): Rg07Operation {
        val action = operation.getValue("action_type").jsonPrimitive.content
        val operationId = operation.getValue("id").jsonPrimitive.content
        val input = operation["input"]?.jsonObject ?: operation["attempted_input"]?.jsonObject ?: JsonObject(emptyMap())
        val case = Rg07AdaptedCase(ledgerId, CurrencyUnit("CNY", 2), catalog.catalog)
        return when (action) {
            "manual_expense" ->
                (
                    adaptRg07ManualExpense(
                        case,
                        Rg07ManualExpenseInput(
                            RequestId(input.require("request_id")),
                            input.requireMoney(),
                            CategoryId(input.require("category_id")),
                            AccountId(input.require("payment_account_id")),
                            input.requireInstant("occurred_at"),
                            input.require("note"),
                            input.requireBool("explicit_confirmation"),
                        ),
                    ) as Rg07AdaptResult.Success
                ).operation

            "record_refund_request_status" ->
                (
                    adaptRg07Status(
                        case,
                        Rg07StatusInput(
                            RequestId(input.require("request_id")),
                            TransactionId(input.require("original_transaction_id")),
                            input.requireMoney("requested_amount"),
                            input.requireInstant("requested_at"),
                            input.requireInstant("approved_at"),
                            input.requireInstant("processor_reported_at"),
                        ),
                    ) as Rg07AdaptResult.Success
                ).operation

            "ingest_refund_status_source" ->
                (
                    adaptRg07StatusSource(
                        case,
                        Rg07StatusSourceInput(
                            input.require("source_id"),
                            input.require("refund_relation_id"),
                            input.requireInstant("observed_at"),
                            Rg07StatusSourceState.valueOf(input.require("reported_state").uppercase()),
                            input.requireBool("proves_arrival"),
                        ),
                    ) as Rg07AdaptResult.Success
                ).operation

            "confirm_manual_refund_receipt" ->
                (
                    adaptRg07ManualReceipt(
                        case,
                        Rg07ManualReceiptInput(
                            RequestId(input.require("request_id")),
                            input.str("refund_relation_id"),
                            input.str("original_transaction_id")?.let(::TransactionId),
                            input.money("amount"),
                            input.str("category_id")?.let(::CategoryId),
                            input.str("destination_account_id")?.let(::AccountId),
                            input.instant("source_observed_at"),
                            input.instant("booking_at"),
                            input.instant("value_at"),
                            input.instant("arrived_at"),
                            input.instant("confirmed_at"),
                            input.str("confirmation_mode"),
                            input.str("observation_mode"),
                            input.requireBool("arrival_confirmed"),
                        ),
                    ) as Rg07AdaptResult.Success
                ).operation

            "attach_original_payment_evidence" ->
                (
                    adaptRg07OriginalPaymentEvidence(
                        case,
                        Rg07OriginalPaymentEvidenceInput(
                            input.require("source_id"),
                            input.require("evidence_id"),
                            input.require("payment_asset_posting_id"),
                            input.requireMoney(),
                            input.requireInstant("observed_at"),
                            input.requireInstant("booking_at"),
                            input.requireInstant("value_at"),
                            input.require("immutable_payload_hash"),
                        ),
                    ) as Rg07AdaptResult.Success
                ).operation

            "attach_refund_destination_evidence" ->
                (
                    adaptRg07DestinationEvidence(
                        case,
                        Rg07DestinationEvidenceInput(
                            input.require("source_id"),
                            input.require("evidence_id"),
                            input.require("refund_relation_id"),
                            input.require("destination_asset_posting_id"),
                            AccountId(input.require("account_id")),
                            input.requireMoney(),
                            input.requireInstant("booking_at"),
                            input.requireInstant("value_at"),
                        ),
                    ) as Rg07AdaptResult.Success
                ).operation

            "attach_refund_dual_role_evidence" ->
                (
                    adaptRg07DualRoleEvidence(
                        case,
                        Rg07DualRoleEvidenceInput(
                            input.require("source_id"),
                            input.require("evidence_id"),
                            input.require("refund_relation_id"),
                            input.require("destination_asset_posting_id"),
                            input.requireInstant("observed_at"),
                            (input["roles"] as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content },
                        ),
                    ) as Rg07AdaptResult.Success
                ).operation

            "confirm_refund_receipt" ->
                (
                    adaptRg07ConfirmReceipt(
                        case,
                        Rg07ConfirmReceiptInput(
                            RequestId(input.require("request_id")),
                            input.str("original_transaction_id")?.let(::TransactionId),
                            input.money("amount"),
                            input.str("category_id")?.let(::CategoryId),
                            input.str("destination_account_id")?.let(::AccountId),
                            input.instant("arrived_at"),
                            input.instant("confirmed_at"),
                            input.requireBool("arrival_confirmed"),
                        ),
                    ) as Rg07AdaptResult.Success
                ).operation

            "allocate_refund_receipt" ->
                (
                    adaptRg07Allocate(
                        case,
                        Rg07AllocateInput(
                            input.require("candidate_id"),
                            input.requireMoney("requested_allocation", currencyFallback = "CNY"),
                            input.requireMoney("available_allocation", currencyFallback = "CNY"),
                        ),
                    ) as Rg07AdaptResult.Success
                ).operation

            "ingest_refund_credit_source" ->
                (
                    adaptRg07ImportCredit(
                        case,
                        Rg07ImportCreditInput(
                            input.require("source_id"),
                            input.require("source_record_id"),
                            AccountId(input.require("account_id")),
                            input.requireMoney(),
                            input.requireInstant("processor_reported_at"),
                            input.requireInstant("source_observed_at"),
                            input.requireInstant("booking_at"),
                            input.requireInstant("value_at"),
                            input.require("original_source_payload_hash"),
                        ),
                    ) as Rg07AdaptResult.Success
                ).operation

            "confirm_imported_refund" ->
                (
                    adaptRg07ImportConfirmation(
                        case,
                        Rg07ImportConfirmationInput(
                            input.str("request_id"),
                            input.require("candidate_id"),
                            input.str("original_transaction_id")?.let(::TransactionId),
                            input.str("category_id")?.let(::CategoryId),
                            input.money("allocated_amount", currencyFallback = "CNY"),
                            input.str("destination_account_id")?.let(::AccountId),
                            input.instant("arrived_at"),
                            input.instant("confirmed_at"),
                            input.requireBool("arrival_confirmed"),
                        ),
                        // The accepted and its no-change replay share the request identity;
                        // rejected attempts are distinct identities.
                        Rg07OperationIdentity(ledgerId, input.str("request_id") ?: operationId),
                    ) as Rg07AdaptResult.Success
                ).operation

            "merge_refund_mirror_evidence" ->
                (
                    adaptRg07Mirror(
                        case,
                        Rg07MirrorInput(
                            input.require("source_id"),
                            input.require("evidence_id"),
                            RequestId(input.require("request_id")),
                            input.requireInstant("observed_at"),
                            input.requireMoney(),
                        ),
                    ) as Rg07AdaptResult.Success
                ).operation

            "validate_refund_receipt" ->
                (
                    adaptRg07Validate(
                        case,
                        Rg07ValidateInput(
                            input.require("attempt_id"),
                            input.str("original_transaction_id")?.let(::TransactionId),
                            input.requireMoney(),
                            input.str("category_id")?.let(::CategoryId),
                            input.str("destination_account_id")?.let(::AccountId),
                            input.requireBool("destination_confirmed"),
                            input.requireMoney("remaining_refundable"),
                        ),
                    ) as Rg07AdaptResult.Success
                ).operation

            else -> throw IllegalArgumentException("unsupported action $action")
        }
    }

    private fun JsonObject.str(key: String): String? =
        this[key]
            ?.takeUnless { it is JsonNull }
            ?.takeIf { it.jsonPrimitive.isString }
            ?.jsonPrimitive
            ?.content

    private fun JsonObject.require(key: String): String = str(key) ?: throw IllegalArgumentException("missing required field $key in $this")

    private fun JsonObject.bool(key: String): Boolean? =
        this[key]
            ?.takeUnless { it is JsonNull }
            ?.jsonPrimitive
            ?.content
            ?.toBooleanStrictOrNull()

    private fun JsonObject.requireBool(key: String): Boolean = bool(key) ?: throw IllegalArgumentException("missing required boolean $key in $this")

    private fun JsonObject.instant(key: String): Instant? = str(key)?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun JsonObject.requireInstant(key: String): Instant = instant(key) ?: throw IllegalArgumentException("missing required instant $key in $this")

    private fun JsonObject.money(
        key: String = "amount",
        currencyFallback: String? = null,
    ): Money? {
        val amount = str(key) ?: return null
        val currency = str("currency") ?: currencyFallback ?: return null
        return runCatching { parseMoney(amount, currency) }.getOrNull()
    }

    private fun JsonObject.requireMoney(
        key: String = "amount",
        currencyFallback: String? = null,
    ): Money = money(key, currencyFallback) ?: throw IllegalArgumentException("missing money $key in $this")

    private fun parseMoney(
        amount: String,
        currency: String,
    ): Money {
        val (whole, fraction) = amount.split(".").let { it[0] to (it.getOrNull(1) ?: "") }
        val precision = fraction.length
        val minor = whole.toLong() * pow10(precision) + (fraction.padEnd(precision, '0').takeIf { it.isNotEmpty() }?.toLong() ?: 0L)
        return Money.ofMinor(minor, CurrencyUnit(currency, precision))
    }

    private fun pow10(n: Int): Long = (1..n).fold(1L) { acc, _ -> acc * 10 }
}

private class Rg07ReplayIdentitySource(
    private val rootId: String,
    private val purpose: String,
    private val manualConfirmedAt: Instant,
) : Rg07IdentitySource {
    override fun operationId(operation: Rg07Operation): String =
        when (operation) {
            is Rg07Operation.ManualExpense -> migrationId("operation", "$.original", operation.input.requestId.value)
            is Rg07Operation.ManualReceipt -> migrationId("operation", "$.manual_receipt", operation.input.requestId.value)
            is Rg07Operation.ImportConfirm -> migrationId("operation", "$.import_path.confirmation", operation.input.requestId ?: operation.identity.value)
            is Rg07Operation.ConfirmReceipt -> operation.identity.value
            else -> operation.identity.value
        }

    override fun manual(operation: Rg07Operation.ManualExpense): Rg07ManualCommitFacts =
        Rg07ManualCommitFacts(
            confirmationId = migrationId("confirmation", "$.original.confirmation", "transaction-original-rg07"),
            reconciliationId = migrationId("posting_reconciliation", "$.original.reconciliation", "posting-original-asset-rg07"),
            confirmedAt = manualConfirmedAt,
        )

    override fun relation(operation: Rg07Operation.Status): String =
        when (purpose) {
            "rg07_manual_refund_lifecycle" -> "refund-relation-rg07-manual"
            else -> error("unsupported RG-07 status root $purpose")
        }

    override fun domainEntity(
        operation: Rg07Operation,
        relationId: String,
    ): String = migrationId("domain_entity", "$.entity_registry.relations[*]", relationId)

    override fun formal(operation: Rg07Operation): Rg07FormalIds {
        if (operation is Rg07Operation.ManualExpense) {
            return Rg07FormalIds(
                "transaction-original-rg07",
                "version-original-rg07-v1",
                "posting-set-original-rg07",
                "posting-original-expense-rg07",
                "posting-original-asset-rg07",
            )
        }
        val suffix =
            when (operation) {
                is Rg07Operation.ManualReceipt -> "rg07-manual"
                is Rg07Operation.ImportConfirm -> "rg07-import"
                is Rg07Operation.ConfirmReceipt -> operation.identity.value.removePrefix("request-")
                else -> error("unsupported RG-07 receipt operation ${operation::class.simpleName}")
            }
        val transactionId = "transaction-refund-$suffix"
        val versionId = "version-refund-$suffix-v1"
        val postingSetId = "posting-set-refund-$suffix"
        return if (suffix == "rg07-cap-maximum") {
            Rg07FormalIds(transactionId, versionId, postingSetId, "posting-refund-cap-asset-rg07", "posting-refund-cap-expense-rg07")
        } else {
            Rg07FormalIds(transactionId, versionId, postingSetId, "posting-refund-asset-$suffix", "posting-refund-expense-$suffix")
        }
    }

    override fun receipt(
        operation: Rg07Operation,
        relationId: String,
        assetPostingId: String,
    ): Rg07ReceiptCommitIds {
        val locator =
            when (purpose) {
                "rg07_manual_refund_lifecycle" -> "$.manual_receipt.expected.reconciliation"
                "rg07_import_refund_lifecycle" -> "$.import_path.confirmation.expected.reconciliation"
                "rg07_refund_cap" ->
                    when (operation.identity.value) {
                        "request-rg07-cap-first" -> "$.refund_cap.existing_refund"
                        "request-rg07-cap-maximum" -> "$.refund_cap.maximum_valid_allocation"
                        else -> error("unsupported RG-07 cap receipt ${operation.identity.value}")
                    }
                else -> error("unsupported RG-07 receipt root $purpose")
            }
        val suffix = relationId.removePrefix("refund-relation-")
        return Rg07ReceiptCommitIds(
            confirmationId = "confirmation-link-$suffix-relationship",
            reconciliationId = migrationId("posting_reconciliation", locator, assetPostingId),
            domainEntityId = domainEntity(operation, relationId),
        )
    }

    private fun migrationId(
        kind: String,
        locator: String,
        occurrence: String,
    ): String = goldenV2MigrationId("RG-07", rootId, kind, locator, occurrence)
}

private fun jsonObjectOf(vararg fields: Pair<String, JsonElement?>): JsonObject = JsonObject(fields.mapNotNull { (key, value) -> value?.let { key to it } }.toMap())

private fun comparableRg07State(state: JsonObject): JsonObject {
    val payload = state.filterKeys { it !in setOf("id", "root_id", "as_of_operation_id") }.toMutableMap()
    payload["derived_statuses"] =
        JsonArray(
            state.getValue("derived_statuses").jsonArray.sortedBy {
                it.jsonObject
                    .getValue("id")
                    .jsonPrimitive.content
            },
        )
    return JsonObject(payload)
}

private fun repositoryFile(relative: String): Path {
    var candidate = Path.of(System.getProperty("user.dir"))
    repeat(8) {
        val settings = candidate.resolve("settings.gradle.kts")
        if (Files.isRegularFile(settings)) return candidate.resolve(relative)
        candidate = candidate.parent ?: error("repository root not found")
    }
    error("repository root not found")
}
