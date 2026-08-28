package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.Rg06EvidenceId
import com.unifiedledger.application.Rg06ExecutionResult
import com.unifiedledger.application.Rg06ManualBankObservation
import com.unifiedledger.application.Rg06ManualObservationKey
import com.unifiedledger.application.Rg06ObservedAt
import com.unifiedledger.application.Rg06SourceId
import com.unifiedledger.application.Rg06TypedValueResult
import com.unifiedledger.application.replayRg06Fixture
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
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/** Replays every frozen RG-06 operation through the real SQLDelight adapter. */
class Rg06RuntimeReplayTest {
    @Test
    fun runtimeMatchesExpectedOutcomesStatesDeltasAndStatusChanges() {
        val expected =
            Json
                .parseToJsonElement(
                    Files.readString(repositoryFile("docs/migrations/golden-v2/rg-06-expected.json")),
                ).jsonObject
        val v1 = Files.readString(repositoryFile("golden/rules/rg-06.json"))
        val adapted = replayRg06Fixture(v1)
        val ledgerId = LedgerId(expected.getValue("case").jsonObject.string("ledger_id"))
        val catalog = buildCatalog(expected, ledgerId)
        val states =
            expected.getValue("states").jsonArray.associateBy(
                { it.jsonObject.string("id") },
                { it.jsonObject },
            )
        val operations = expected.getValue("operations").jsonArray.map { it.jsonObject }
        val adaptedById = adapted.operations.associateBy { it.id }
        val roots = expected.getValue("roots").jsonArray.map { it.jsonObject }
        val actualCounts = mutableMapOf("accepted" to 0, "no_change" to 0, "rejected" to 0)

        roots.forEach { root ->
            val rootId = root.string("id")
            val purpose = root.string("purpose")
            val rootOperations = operations.filter { it.string("root_id") == rootId }
            val rootAdapted =
                rootOperations.map { operation ->
                    adaptedById.getValue(operation.string("id"))
                }
            val operationIdsByIdentity = linkedMapOf<String, String>()
            rootAdapted.forEach { fixtureOperation ->
                operationIdsByIdentity.putIfAbsent(fixtureOperation.operation.identity.value, fixtureOperation.id)
            }

            val driver =
                JdbcSqliteDriver(
                    JdbcSqliteDriver.IN_MEMORY,
                    Properties().apply { setProperty("foreign_keys", "true") },
                )
            try {
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                seedBaseline(
                    database,
                    ledgerId,
                    states.getValue(root.string("initial_state_id")),
                    rootId,
                )
                val store =
                    SqlDelightRg06Store(
                        database,
                        driver,
                        catalog,
                        "+08:00",
                        manualObservations(expected),
                    )
                val projector =
                    Rg06StateProjector(
                        driver,
                        ledgerId.value,
                        rootId,
                        purpose,
                        states.getValue(root.string("initial_state_id")).getValue("catalog").jsonObject,
                        operationIdsByIdentity,
                    )
                assertState(
                    states.getValue(root.string("initial_state_id")),
                    projector.state(root.string("initial_state_id"), null),
                    "$rootId initial state",
                )

                rootOperations.forEach { expectedOperation ->
                    val operationId = expectedOperation.string("id")
                    val before = projector.state(expectedOperation.string("baseline_state_id"), null)
                    assertState(
                        states.getValue(expectedOperation.string("baseline_state_id")),
                        before,
                        "$operationId baseline state",
                    )
                    val result = store.commit(adaptedById.getValue(operationId).operation)
                    val status = outcome(result).string("status")
                    actualCounts[status] = (actualCounts[status] ?: 0) + 1
                    assertEquals(expectedOperation.getValue("outcome"), outcome(result), "$operationId outcome")
                    assertEquals(expectedOperation.getValue("returned_ids"), returnedIds(result), "$operationId returned IDs")

                    val after = projector.state(expectedOperation.string("result_state_id"), operationId)
                    assertState(
                        states.getValue(expectedOperation.string("result_state_id")),
                        after,
                        "$operationId result state",
                    )
                    assertEquals(expectedOperation.getValue("deltas"), goldenV2Deltas(before, after), "$operationId deltas")
                    assertEquals(expectedOperation.getValue("status_changes"), goldenV2StatusChanges(before, after), "$operationId status changes")
                    if (status != "accepted") {
                        assertEquals(goldenV2StatePayload(before), goldenV2StatePayload(after), "$operationId rejected/no-change residue")
                    }
                }
            } finally {
                driver.close()
            }
        }

        assertEquals(13, actualCounts["accepted"])
        assertEquals(10, actualCounts["no_change"])
        assertEquals(18, actualCounts["rejected"])
        assertEquals(41, adapted.operations.size)
        assertEquals(13, adapted.accepted)
        assertEquals(10, adapted.noChange)
        assertEquals(18, adapted.rejected)
    }

    private fun outcome(result: Rg06ExecutionResult): JsonObject =
        when (result) {
            is Rg06ExecutionResult.Accepted -> obj("status" to JsonPrimitive("accepted"))
            is Rg06ExecutionResult.NoChange ->
                obj(
                    "status" to JsonPrimitive("no_change"),
                    "reason_code" to JsonPrimitive("idempotent_replay"),
                )
            is Rg06ExecutionResult.Rejected ->
                obj(
                    "status" to JsonPrimitive("rejected"),
                    "reason_code" to JsonPrimitive(result.reason.code),
                    "field_path" to JsonPrimitive(result.fieldPath.value),
                )
            Rg06ExecutionResult.RequestIdentityConflict -> obj("status" to JsonPrimitive("conflict"))
        }

    private fun returnedIds(result: Rg06ExecutionResult): JsonArray {
        val ids =
            when (result) {
                is Rg06ExecutionResult.Accepted -> result.returnedIds
                is Rg06ExecutionResult.NoChange -> result.returnedIds
                is Rg06ExecutionResult.Rejected, Rg06ExecutionResult.RequestIdentityConflict -> emptyList()
            }
        return JsonArray(
            ids.map { returned ->
                when (returned) {
                    is com.unifiedledger.application.Rg06ReturnedId.Relation -> returnedJson("relation", returned.id.value)
                    is com.unifiedledger.application.Rg06ReturnedId.Lifecycle -> returnedJson("domain_entity", returned.id.value)
                    is com.unifiedledger.application.Rg06ReturnedId.Payment -> returnedJson("domain_entity", returned.id.value)
                    is com.unifiedledger.application.Rg06ReturnedId.Transaction -> returnedJson("transaction", returned.id.value)
                    is com.unifiedledger.application.Rg06ReturnedId.Source -> returnedJson("source", returned.id.value)
                    is com.unifiedledger.application.Rg06ReturnedId.Evidence -> returnedJson("evidence", returned.id.value)
                    is com.unifiedledger.application.Rg06ReturnedId.Candidate -> returnedJson("candidate", returned.id.value)
                    is com.unifiedledger.application.Rg06ReturnedId.Confirmation -> returnedJson("confirmation", returned.id.value)
                    is com.unifiedledger.application.Rg06ReturnedId.EvidenceLink -> returnedJson("evidence_link", returned.id.value)
                }
            },
        )
    }

    private fun returnedJson(
        kind: String,
        id: String,
    ): JsonObject =
        obj(
            "kind" to JsonPrimitive(kind),
            "id" to JsonPrimitive(id),
        )

    private fun assertState(
        expected: JsonObject,
        actual: JsonObject,
        label: String,
    ) {
        assertEquals(comparableState(expected), comparableState(actual), label)
    }

    private fun comparableState(state: JsonObject): JsonObject {
        val payload = state.filterKeys { it !in setOf("id", "root_id", "as_of_operation_id") }.toMutableMap()
        payload["derived_statuses"] =
            JsonArray(
                state.getValue("derived_statuses").jsonArray.sortedBy { it.jsonObject.string("id") },
            )
        return JsonObject(payload)
    }

    private fun buildCatalog(
        expected: JsonObject,
        ledgerId: LedgerId,
    ): LedgerCatalog {
        val catalog =
            expected
                .getValue("states")
                .jsonArray
                .first()
                .jsonObject
                .getValue("catalog")
                .jsonObject
        val accounts =
            catalog.getValue("accounts").jsonArray.map { element ->
                val account = element.jsonObject
                Account(
                    AccountId(account.string("id")),
                    ledgerId,
                    when (account.string("kind")) {
                        "asset" -> AccountKind.ASSET
                        "liability" -> AccountKind.LIABILITY
                        "equity" -> AccountKind.EQUITY
                        "income" -> AccountKind.INCOME
                        "expense" -> AccountKind.EXPENSE
                        else -> error("unsupported RG-06 catalog account kind")
                    },
                    CurrencyUnit(account.string("currency"), 2),
                    account
                        .getValue("owned_by_user")
                        .jsonPrimitive.content
                        .toBooleanStrict(),
                    account
                        .getValue("real_account")
                        .jsonPrimitive.content
                        .toBooleanStrict(),
                )
            }
        val categoryElements = catalog.getValue("categories").jsonArray.map { it.jsonObject }
        val accountKinds = accounts.associate { account -> account.id to account.kind }
        val categories =
            categoryElements.map { category ->
                val postingAccountId =
                    category["posting_account_id"]
                        ?.takeUnless { it is JsonNull }
                        ?.jsonPrimitive
                        ?.content
                        ?.let(::AccountId)
                val kindAccountId =
                    postingAccountId ?: categoryElements
                        .firstOrNull { child ->
                            child["parent_id"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content == category.string("id") &&
                                child["posting_account_id"]?.takeUnless { it is JsonNull } is JsonPrimitive
                        }?.getValue("posting_account_id")
                        ?.jsonPrimitive
                        ?.content
                        ?.let(::AccountId)
                Category(
                    CategoryId(category.string("id")),
                    ledgerId,
                    category["parent_id"]
                        ?.takeUnless { it is JsonNull }
                        ?.jsonPrimitive
                        ?.content
                        ?.let(::CategoryId),
                    postingAccountId,
                    category
                        .getValue("active")
                        .jsonPrimitive.content
                        .toBooleanStrict(),
                    when (accountKinds[kindAccountId]) {
                        AccountKind.INCOME -> com.unifiedledger.domain.CategoryKind.INCOME
                        else -> com.unifiedledger.domain.CategoryKind.EXPENSE
                    },
                )
            }
        return assertIs<DomainResult.Success<LedgerCatalog>>(LedgerCatalog.create(accounts, categories)).value
    }

    private fun manualObservations(expected: JsonObject): Rg06ManualObservationSource {
        val observations =
            buildMap<Rg06ManualObservationKey, Rg06ManualBankObservation> {
                expected.getValue("states").jsonArray.forEach { stateElement ->
                    stateElement.jsonObject.getValue("sources").jsonArray.forEach { sourceElement ->
                        val source = sourceElement.jsonObject
                        val payload = source.getValue("payload").jsonObject
                        val observedAt = payload["observed_at"]?.jsonPrimitive?.content ?: return@forEach
                        if (!source.string("id").contains("manual")) return@forEach
                        val amount = parseMoney(payload.string("amount"), payload.string("currency"))
                        val time =
                            assertIs<Rg06TypedValueResult.Success<Rg06ObservedAt>>(
                                Rg06ObservedAt.create(Instant.parse(observedAt), observedAt, "+08:00"),
                            ).value
                        val evidence = source.getValue("payload").jsonObject
                        val evidenceId =
                            stateElement.jsonObject
                                .getValue("evidence")
                                .jsonArray
                                .firstOrNull {
                                    it.jsonObject
                                        .getValue("source_ids")
                                        .jsonArray
                                        .any { sourceId -> sourceId.jsonPrimitive.content == source.string("id") }
                                }?.jsonObject
                                ?.string("id") ?: return@forEach
                        put(
                            Rg06ManualObservationKey(Rg06SourceId(source.string("id")), Rg06EvidenceId(evidenceId)),
                            Rg06ManualBankObservation(Money.ofMinor(-kotlin.math.abs(amount.minorUnits), amount.currency), time),
                        )
                    }
                }
            }
        return Rg06ManualObservationSource { sourceId, evidenceId ->
            observations[Rg06ManualObservationKey(sourceId, evidenceId)]
        }
    }

    private fun seedBaseline(
        database: LedgerDatabase,
        ledgerId: LedgerId,
        state: JsonObject,
        rootId: String,
    ) {
        val transactions = state.getValue("transactions").jsonArray
        val postingSets = state.getValue("posting_sets").jsonArray
        val versions = state.getValue("transaction_versions").jsonArray
        val postings = state.getValue("postings").jsonArray

        postingSets.forEach { postingSet ->
            database.ledgerQueries.insertPostingSet(postingSet.jsonObject.string("id"), ledgerId.value)
        }
        transactions.forEach { transaction ->
            val item = transaction.jsonObject
            database.ledgerQueries.insertTransaction(
                item.string("id"),
                ledgerId.value,
                if (item.string("type") == "opening_balance") "OPENING_BALANCE" else "EXPENSE",
            )
        }
        versions.forEach { versionElement ->
            val version = versionElement.jsonObject
            database.ledgerQueries.insertTransactionVersion(
                version.string("id"),
                version.string("transaction_id"),
                ledgerId.value,
                version
                    .getValue("version_number")
                    .jsonPrimitive.content
                    .toLong(),
                version.string("posting_set_id"),
                version.string("occurred_at"),
                version.string("statistics_at"),
                version.string("effective_at"),
                version["note"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
            )
        }
        transactions.forEach { transactionElement ->
            val transaction = transactionElement.jsonObject
            database.ledgerQueries.insertTransactionCurrentVersion(
                transaction.string("id"),
                ledgerId.value,
                transaction.string("current_version_id"),
            )
        }
        val postingIndex = mutableMapOf<String, Long>()
        postings.forEach { postingElement ->
            val posting = postingElement.jsonObject
            val setId = posting.string("posting_set_id")
            val index = postingIndex.getOrDefault(setId, 0L)
            postingIndex[setId] = index + 1
            val money = parseMoney(posting.string("amount"), posting.string("currency"))
            database.ledgerQueries.insertPosting(
                posting.string("id"),
                setId,
                ledgerId.value,
                index,
                posting.string("account_id"),
                money.minorUnits,
                money.currency.code,
                money.currency.precision.toLong(),
            )
        }

        val relations = state.getValue("relations").jsonArray
        val entities = state.getValue("domain_entities").jsonArray.associateBy { it.jsonObject.string("id") }
        relations.forEach { relationElement ->
            val relation = relationElement.jsonObject
            val relationId = relation.string("id")
            val lifecycleMember =
                relation.getValue("member_refs").jsonArray.firstOrNull { member ->
                    entities.getValue(member.jsonObject.string("id")).jsonObject.string("type") == "staged_payment_lifecycle"
                } ?: return@forEach
            val lifecycleEntity = entities.getValue(lifecycleMember.jsonObject.string("id")).jsonObject
            val payload = lifecycleEntity.getValue("payload").jsonObject
            val history = payload.getValue("state_history").jsonArray
            val baselineIdentity = "baseline-rg06-$rootId"
            database.ledgerQueries.claimRg06Operation(
                ledgerId.value,
                baselineIdentity,
                "create_staged_payment",
                baselineIdentity,
                null,
                null,
                null,
                null,
                null,
                null,
                payload.string("category_id"),
                null,
                null,
                null,
                null,
                null,
                parseMoney(payload.string("total_amount"), payload.string("currency")).minorUnits,
                payload.string("currency"),
                2L,
                history.first().jsonObject.string("occurred_at"),
                null,
                null,
            )
            database.ledgerQueries.insertRg06Relation(ledgerId.value, relationId)
            val total = parseMoney(payload.string("total_amount"), payload.string("currency"))
            val paid = parseMoney(payload.string("paid_amount"), payload.string("currency"))
            val due = parseMoney(payload.string("due_amount"), payload.string("currency"))
            database.ledgerQueries.insertRg06Lifecycle(
                ledgerId.value,
                lifecycleEntity.string("id"),
                relationId,
                total.minorUnits,
                paid.minorUnits,
                due.minorUnits,
                total.currency.code,
                total.currency.precision.toLong(),
                payload.string("category_id"),
                history.size.toLong(),
            )
            val members = relation.getValue("member_refs").jsonArray
            val paymentEntities = mutableListOf<JsonObject>()
            members.forEachIndexed { index, memberElement ->
                val member = memberElement.jsonObject
                val entity = entities.getValue(member.string("id")).jsonObject
                if (entity.string("type") == "staged_payment_lifecycle") {
                    database.ledgerQueries.insertRg06RelationMember(
                        ledgerId.value,
                        relationId,
                        index.toLong(),
                        "LIFECYCLE",
                        entity.string("id"),
                    )
                } else {
                    paymentEntities += entity
                }
            }
            history.forEach { historyElement ->
                val item = historyElement.jsonObject
                database.ledgerQueries.insertRg06History(
                    ledgerId.value,
                    lifecycleEntity.string("id"),
                    item
                        .getValue("sequence")
                        .jsonPrimitive.content
                        .toLong(),
                    item.string("id"),
                    baselineIdentity,
                    item.string("event").uppercase(),
                    item.string("occurred_at"),
                    parseMoney(item.string("total_amount"), payload.string("currency")).minorUnits,
                    parseMoney(item.string("paid_amount"), payload.string("currency")).minorUnits,
                    parseMoney(item.string("due_amount"), payload.string("currency")).minorUnits,
                    item["payment_id"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
                    item.string("payment_progress").uppercase(),
                    item.string("fulfillment_status").uppercase(),
                    item
                        .getValue("state_transition_effect_count")
                        .jsonPrimitive.content
                        .toLong(),
                )
            }
            paymentEntities.forEachIndexed { paymentIndex, paymentEntity ->
                val payment = paymentEntity.getValue("payload").jsonObject
                val amount = parseMoney(payment.string("amount"), payment.string("currency"))
                val sourcePaymentAt = payment["source_payment_at"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
                val actualPaymentAt = payment.string("actual_payment_at")
                val evidenceText = sourcePaymentAt
                database.ledgerQueries.insertRg06Installment(
                    ledgerId.value,
                    relationId,
                    paymentIndex.toLong(),
                    paymentEntity.string("id"),
                    payment.string("role").uppercase(),
                    amount.minorUnits,
                    amount.currency.code,
                    amount.currency.precision.toLong(),
                    payment.string("funding_account_id"),
                    payment.string("transaction_id"),
                    transactionVersionIdFor(payment.string("transaction_id"), versions),
                    postingSetIdFor(payment.string("transaction_id"), versions),
                    payment.string("expense_posting_id"),
                    payment.string("asset_posting_id"),
                    actualPaymentAt,
                    payment.string("statistics_at"),
                    sourcePaymentAt,
                    evidenceText,
                )
                database.ledgerQueries.insertRg06RelationMember(
                    ledgerId.value,
                    relationId,
                    (paymentIndex + 1L),
                    "INSTALLMENT",
                    paymentEntity.string("id"),
                )
                database.ledgerQueries.insertRg06PostingSemantic(
                    ledgerId.value,
                    payment.string("expense_posting_id"),
                    paymentEntity.string("id"),
                    "expense",
                    payload.string("category_id"),
                    0L,
                )
                database.ledgerQueries.insertRg06PostingSemantic(
                    ledgerId.value,
                    payment.string("asset_posting_id"),
                    paymentEntity.string("id"),
                    "payment_asset",
                    null,
                    1L,
                )
            }
            state.getValue("posting_reconciliations").jsonArray.forEach { reconciliationElement ->
                val reconciliation = reconciliationElement.jsonObject
                val postingId = reconciliation.string("posting_id")
                if (paymentEntities.any { it.getValue("payload").jsonObject.string("asset_posting_id") == postingId }) {
                    val status = reconciliation.string("status").uppercase()
                    database.ledgerQueries.insertRg06Reconciliation(
                        ledgerId.value,
                        reconciliation.string("id"),
                        postingId,
                        status,
                        1L,
                    )
                    database.ledgerQueries.insertRg06ReconciliationHistory(
                        ledgerId.value,
                        reconciliation.string("id"),
                        1L,
                        status,
                        null,
                    )
                }
            }
        }
    }

    private fun transactionVersionIdFor(
        transactionId: String,
        versions: JsonArray,
    ): String =
        versions
            .first {
                it.jsonObject.string("transaction_id") == transactionId
            }.jsonObject
            .string("id")

    private fun postingSetIdFor(
        transactionId: String,
        versions: JsonArray,
    ): String =
        versions
            .first {
                it.jsonObject.string("transaction_id") == transactionId
            }.jsonObject
            .string("posting_set_id")

    private fun parseMoney(
        amount: String,
        currency: String,
    ): Money {
        val value = BigDecimal(amount)
        val precision = value.scale().coerceAtLeast(2)
        val minor = value.movePointRight(2).longValueExact()
        return Money.ofMinor(minor, CurrencyUnit(currency, 2))
    }

    private fun repositoryFile(relative: String): Path {
        var candidate = Path.of(System.getProperty("user.dir"))
        repeat(8) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)
            candidate = candidate.parent ?: error("repository root not found")
        }
        error("repository root not found")
    }
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

private fun obj(vararg fields: Pair<String, JsonElement?>): JsonObject = JsonObject(fields.mapNotNull { (key, value) -> value?.let { key to it } }.toMap())
