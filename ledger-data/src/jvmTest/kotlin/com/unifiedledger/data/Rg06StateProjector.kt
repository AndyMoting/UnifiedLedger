package com.unifiedledger.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private data class Rg06Tx(val id: String, val type: String, val currentVersionId: String)

private data class Rg06Version(
    val id: String,
    val transactionId: String,
    val number: Long,
    val postingSetId: String,
    val occurredAt: String,
    val statisticsAt: String,
    val effectiveAt: String,
    val note: String?,
    val confirmationId: String?,
)

private data class Rg06Posting(
    val id: String,
    val postingSetId: String,
    val accountId: String,
    val minor: Long,
    val currency: String,
    val precision: Long,
    val role: String?,
    val categoryId: String?,
    val reconciliationEligible: Boolean,
)

private data class Rg06Source(
    val id: String,
    val kind: String,
    val amountMinor: Long,
    val currency: String,
    val precision: Long,
    val observedAt: String,
    val observedAtText: String,
    val timeVariant: String,
    val mirrorOfSourceId: String?,
)

private data class Rg06Evidence(
    val id: String,
    val sourceId: String,
    val kind: String,
    val observedAt: String,
    val timeVariant: String,
    val paymentId: String?,
    val mirrorOfEvidenceId: String?,
    val mergedIntoLinkId: String?,
)

private data class Rg06Candidate(
    val id: String,
    val sourceId: String,
    val evidenceId: String,
    val role: String?,
    val amountMinor: Long,
    val currency: String,
    val precision: Long,
    val sourcePaymentAt: String,
    val confidence: String,
)

private data class Rg06CandidateStatus(
    val candidateId: String,
    val sequence: Long,
    val id: String,
    val status: String,
)

private data class Rg06Confirmation(
    val id: String,
    val identityValue: String,
    val kind: String,
    val candidateId: String?,
    val confirmedAt: String?,
)

private data class Rg06EvidenceLink(
    val id: String,
    val evidenceId: String,
    val paymentId: String,
    val postingId: String,
    val kind: String,
)

private data class Rg06Relation(val id: String, val members: List<Pair<String, String>>)

private data class Rg06History(
    val lifecycleId: String,
    val sequence: Long,
    val id: String,
    val event: String,
    val occurredAt: String,
    val totalMinor: Long,
    val paidMinor: Long,
    val dueMinor: Long,
    val paymentId: String?,
    val paymentProgress: String,
    val fulfillmentStatus: String,
    val effectCount: Long,
)

private data class Rg06Lifecycle(
    val id: String,
    val relationId: String,
    val totalMinor: Long,
    val paidMinor: Long,
    val dueMinor: Long,
    val currency: String,
    val precision: Long,
    val categoryId: String,
)

private data class Rg06Installment(
    val id: String,
    val relationId: String,
    val role: String,
    val amountMinor: Long,
    val currency: String,
    val precision: Long,
    val fundingAccountId: String,
    val transactionId: String,
    val transactionVersionId: String,
    val expensePostingId: String,
    val assetPostingId: String,
    val actualPaymentAt: String,
    val statisticsAt: String,
    val sourcePaymentAt: String?,
)

private data class Rg06Reconciliation(
    val id: String,
    val postingId: String,
    val status: String,
)

/** Projects the real RG-06 SQLDelight tables into the approved expected-state shape. */
internal class Rg06StateProjector(
    private val driver: JdbcSqliteDriver,
    private val ledgerId: String,
    private val rootId: String,
    private val purpose: String,
    private val catalog: JsonObject,
    private val operationIdsByIdentity: Map<String, String>,
) {
    fun state(id: String, asOfOperationId: String?): JsonObject {
        val transactions = transactions()
        val versions = versions()
        val postingSets = postingSets()
        val postings = postings()
        val sources = sources()
        val evidence = evidence()
        val candidates = candidates()
        val candidateStatuses = candidateStatuses()
        val confirmations = confirmations()
        val links = evidenceLinks()
        val relations = relations()
        val histories = histories()
        val lifecycles = lifecycles()
        val installments = installments()
        val reconciliations = reconciliations()

        return obj(
            "id" to JsonPrimitive(id),
            "root_id" to JsonPrimitive(rootId),
            "as_of_operation_id" to (asOfOperationId?.let(::JsonPrimitive) ?: JsonNull),
            "catalog" to catalog,
            "transactions" to JsonArray(transactions.map { tx ->
                obj(
                    "id" to JsonPrimitive(tx.id),
                    "type" to JsonPrimitive(tx.type),
                    "current_version_id" to JsonPrimitive(tx.currentVersionId),
                )
            }),
            "transaction_versions" to JsonArray(versions.map { version ->
                obj(
                    "id" to JsonPrimitive(version.id),
                    "transaction_id" to JsonPrimitive(version.transactionId),
                    "version_number" to JsonPrimitive(version.number),
                    "posting_set_id" to JsonPrimitive(version.postingSetId),
                    "occurred_at" to JsonPrimitive(caseTime(version.occurredAt)),
                    "statistics_at" to JsonPrimitive(caseTime(version.statisticsAt)),
                    "effective_at" to JsonPrimitive(caseTime(version.effectiveAt)),
                    "note" to version.note?.let(::JsonPrimitive),
                    "confirmation_id" to version.confirmationId?.let(::JsonPrimitive),
                )
            }),
            "posting_sets" to JsonArray(postingSets.map { (id, postingIds) ->
                obj(
                    "id" to JsonPrimitive(id),
                    "posting_ids" to JsonArray(postingIds.map(::JsonPrimitive)),
                )
            }),
            "postings" to JsonArray(postings.map { posting ->
                obj(
                    "id" to JsonPrimitive(posting.id),
                    "posting_set_id" to JsonPrimitive(posting.postingSetId),
                    "account_id" to JsonPrimitive(posting.accountId),
                    "category_id" to posting.categoryId?.let(::JsonPrimitive),
                    "amount" to JsonPrimitive(amount(posting.minor, posting.precision)),
                    "currency" to JsonPrimitive(posting.currency),
                    "role" to posting.role?.let(::JsonPrimitive),
                    "reconciliation_eligible" to JsonPrimitive(posting.reconciliationEligible),
                )
            }),
            "sources" to JsonArray(sources.map(::projectSource)),
            "candidates" to JsonArray(candidates.map { candidate ->
                projectCandidate(candidate, candidateStatuses.filter { it.candidateId == candidate.id })
            }),
            "confirmations" to JsonArray(confirmations.map(::projectConfirmation)),
            "evidence" to JsonArray(evidence.map(::projectEvidence)),
            "evidence_links" to JsonArray(links.map { link ->
                obj(
                    "id" to JsonPrimitive(link.id),
                    "evidence_id" to JsonPrimitive(link.evidenceId),
                    "target_kind" to JsonPrimitive("posting"),
                    "target_id" to JsonPrimitive(link.postingId),
                    "role" to JsonPrimitive("payment_asset_posting"),
                )
            }),
            "relations" to JsonArray(relations.map { relation ->
                obj(
                    "id" to JsonPrimitive(relation.id),
                    "type" to JsonPrimitive("staged_payment"),
                    "member_refs" to JsonArray(relation.members.map { (_, memberId) ->
                        obj("kind" to JsonPrimitive("domain_entity"), "id" to JsonPrimitive(memberId))
                    }),
                    "payload" to obj(),
                )
            }),
            "domain_entities" to JsonArray(
                projectDomainEntities(relations, lifecycles, installments, histories),
            ),
            "audit_links" to JsonArray(emptyList()),
            "posting_reconciliations" to JsonArray(reconciliations.map { reconciliation ->
                obj(
                    "id" to JsonPrimitive(reconciliation.id),
                    "posting_id" to JsonPrimitive(reconciliation.postingId),
                    "status" to JsonPrimitive(reconciliation.status.lowercase()),
                )
            }),
            "balances" to projectBalances(transactions, versions, postingSets, postings),
            "reports" to projectReports(transactions, versions, postings),
            "derived_statuses" to projectStatuses(
                transactions,
                lifecycles,
                installments,
                histories,
                candidateStatuses,
                reconciliations,
            ),
        )
    }

    private fun projectSource(source: Rg06Source): JsonObject {
        val payload = linkedMapOf<String, JsonElement>(
            "amount" to JsonPrimitive(
                amount(
                    if (source.kind == "MIRROR") source.amountMinor else magnitude(source.amountMinor),
                    source.precision,
                ),
            ),
            "currency" to JsonPrimitive(source.currency),
        )
        if (source.timeVariant == "OBSERVED_AT") {
            payload["observed_at"] = JsonPrimitive(caseTime(source.observedAt))
        } else {
            payload["source_payment_at"] = JsonPrimitive(caseTime(source.observedAt))
        }
        source.mirrorOfSourceId?.let { payload["mirror_of_source_id"] = JsonPrimitive(it) }
        return obj(
            "id" to JsonPrimitive(source.id),
            "type" to JsonPrimitive("staged_payment_bank_fact"),
            "payload" to JsonObject(payload),
        )
    }

    private fun projectEvidence(evidence: Rg06Evidence): JsonObject {
        val payload = linkedMapOf<String, JsonElement>()
        if (evidence.timeVariant == "OBSERVED_AT") {
            payload["observed_at"] = JsonPrimitive(caseTime(evidence.observedAt))
        } else {
            payload["source_payment_at"] = JsonPrimitive(caseTime(evidence.observedAt))
        }
        evidence.paymentId?.let { payload["payment_id"] = JsonPrimitive(it) }
        evidence.mirrorOfEvidenceId?.let { payload["mirror_of_evidence_id"] = JsonPrimitive(it) }
        evidence.mergedIntoLinkId?.let { payload["merged_into_evidence_link_id"] = JsonPrimitive(it) }
        return obj(
            "id" to JsonPrimitive(evidence.id),
            "type" to JsonPrimitive("staged_payment_bank_payment"),
            "source_ids" to JsonArray(listOf(JsonPrimitive(evidence.sourceId))),
            "payload" to JsonObject(payload),
        )
    }

    private fun projectCandidate(candidate: Rg06Candidate, statuses: List<Rg06CandidateStatus>): JsonObject {
        val payload = linkedMapOf<String, JsonElement>(
            "payment_role" to candidate.role?.lowercase().let { it?.let(::JsonPrimitive) ?: JsonNull },
            "amount" to JsonPrimitive(amount(candidate.amountMinor, candidate.precision)),
            "currency" to JsonPrimitive(candidate.currency),
            "source_payment_at" to JsonPrimitive(caseTime(candidate.sourcePaymentAt)),
            "evidence_ref" to JsonPrimitive(candidate.evidenceId),
            "provenance" to obj(
                "rule" to JsonPrimitive("staged_payment_bank_fact"),
                "rule_version" to JsonPrimitive(1),
            ),
            "requires_confirmation" to JsonArray(
                listOf("relation_id", "payment_role", "category_id", "funding_account_id").map(::JsonPrimitive),
            ),
        )
        if (candidate.role == null) payload["guessed_payment_role"] = JsonNull
        return obj(
            "id" to JsonPrimitive(candidate.id),
            "type" to JsonPrimitive("staged_payment"),
            "source_ids" to JsonArray(listOf(JsonPrimitive(candidate.sourceId))),
            "confidence" to JsonPrimitive(candidate.confidence),
            "payload" to JsonObject(payload),
            "status_history" to JsonArray(statuses.sortedBy { it.sequence }.map { status ->
                obj(
                    "id" to JsonPrimitive(status.id),
                    "sequence" to JsonPrimitive(status.sequence),
                    "status" to JsonPrimitive(status.status.lowercase()),
                )
            }),
        )
    }

    private fun projectConfirmation(confirmation: Rg06Confirmation): JsonObject = obj(
        "id" to JsonPrimitive(confirmation.id),
        "type" to JsonPrimitive(
            if (confirmation.kind == "MANUAL_INSTALLMENT") "explicit_manual_save" else "candidate_confirmation",
        ),
        "operation_id" to JsonPrimitive(
            operationIdsByIdentity[confirmation.identityValue] ?: confirmation.identityValue,
        ),
        "subject" to obj(
            "kind" to JsonPrimitive(if (confirmation.kind == "MANUAL_INSTALLMENT") "operation" else "candidate"),
            "id" to JsonPrimitive(
                if (confirmation.kind == "MANUAL_INSTALLMENT") {
                    operationIdsByIdentity[confirmation.identityValue] ?: confirmation.identityValue
                } else {
                    checkNotNull(confirmation.candidateId)
                },
            ),
        ),
        "confirmed_at" to confirmation.confirmedAt?.let { JsonPrimitive(caseTime(it)) },
        "payload" to obj(),
    )

    private fun projectDomainEntities(
        relations: List<Rg06Relation>,
        lifecycles: List<Rg06Lifecycle>,
        installments: List<Rg06Installment>,
        histories: List<Rg06History>,
    ): List<JsonObject> = buildList {
        relations.forEach { relation ->
            relation.members.forEach { (kind, memberId) ->
                when (kind) {
                    "LIFECYCLE" -> lifecycles.single { it.id == memberId }.let { lifecycle ->
                        add(projectLifecycle(lifecycle, histories.filter { it.lifecycleId == lifecycle.id }))
                    }
                    "INSTALLMENT" -> installments.single { it.id == memberId }.let(::projectInstallment).also(::add)
                }
            }
        }
    }

    private fun projectLifecycle(lifecycle: Rg06Lifecycle, histories: List<Rg06History>): JsonObject = obj(
        "id" to JsonPrimitive(lifecycle.id),
        "type" to JsonPrimitive("staged_payment_lifecycle"),
        "payload" to obj(
            "total_amount" to JsonPrimitive(amount(lifecycle.totalMinor, lifecycle.precision)),
            "paid_amount" to JsonPrimitive(amount(lifecycle.paidMinor, lifecycle.precision)),
            "due_amount" to JsonPrimitive(amount(lifecycle.dueMinor, lifecycle.precision)),
            "currency" to JsonPrimitive(lifecycle.currency),
            "category_id" to JsonPrimitive(lifecycle.categoryId),
            "display_name" to JsonPrimitive("Synthetic staged payment"),
            "system_managed" to JsonPrimitive(true),
            "generic_order_lifecycle" to JsonPrimitive(false),
            "state_history" to JsonArray(histories.sortedBy { it.sequence }.map { history ->
                obj(
                    "id" to JsonPrimitive(history.id),
                    "sequence" to JsonPrimitive(history.sequence),
                    "event" to JsonPrimitive(history.event.lowercase()),
                    "occurred_at" to JsonPrimitive(caseTime(history.occurredAt)),
                    "total_amount" to JsonPrimitive(amount(history.totalMinor, lifecycle.precision)),
                    "paid_amount" to JsonPrimitive(amount(history.paidMinor, lifecycle.precision)),
                    "due_amount" to JsonPrimitive(amount(history.dueMinor, lifecycle.precision)),
                    "payment_id" to (history.paymentId?.let(::JsonPrimitive) ?: JsonNull),
                    "payment_progress" to JsonPrimitive(history.paymentProgress.lowercase()),
                    "fulfillment_status" to JsonPrimitive(history.fulfillmentStatus.lowercase()),
                    "state_transition_effect_count" to JsonPrimitive(history.effectCount),
                )
            }),
        ),
    )

    private fun projectInstallment(installment: Rg06Installment): JsonObject = obj(
        "id" to JsonPrimitive(installment.id),
        "type" to JsonPrimitive("installment_payment"),
        "payload" to obj(
            "role" to JsonPrimitive(installment.role.lowercase()),
            "amount" to JsonPrimitive(amount(installment.amountMinor, installment.precision)),
            "currency" to JsonPrimitive(installment.currency),
            "funding_account_id" to JsonPrimitive(installment.fundingAccountId),
            "transaction_id" to JsonPrimitive(installment.transactionId),
            "expense_posting_id" to JsonPrimitive(installment.expensePostingId),
            "asset_posting_id" to JsonPrimitive(installment.assetPostingId),
            "actual_payment_at" to JsonPrimitive(caseTime(installment.actualPaymentAt)),
            "statistics_at" to JsonPrimitive(caseTime(installment.statisticsAt)),
            "source_payment_at" to installment.sourcePaymentAt?.let { JsonPrimitive(caseTime(it)) },
        ),
    )

    private fun projectBalances(
        transactions: List<Rg06Tx>,
        versions: List<Rg06Version>,
        postingSets: Map<String, List<String>>,
        postings: List<Rg06Posting>,
    ): JsonArray {
        val currentPostingIds = transactions.flatMap { transaction ->
            postingSets[versions.single { it.id == transaction.currentVersionId }.postingSetId].orEmpty()
        }.toSet()
        val accounts = catalog.getValue("accounts").jsonArray
        return JsonArray(accounts.map { accountElement ->
            val account = accountElement.jsonObject
            val accountId = account.getValue("id").jsonPrimitive.content
            val currency = account.getValue("currency").jsonPrimitive.content
            val minor = postings.filter { it.id in currentPostingIds && it.accountId == accountId }.sumOf { it.minor }
            obj(
                "account_id" to JsonPrimitive(accountId),
                "currency" to JsonPrimitive(currency),
                "amount" to JsonPrimitive(amount(minor, 2)),
            )
        })
    }

    private fun projectReports(
        transactions: List<Rg06Tx>,
        versions: List<Rg06Version>,
        postings: List<Rg06Posting>,
    ): JsonArray {
        val currentVersionIds = transactions.filter { it.type == "expense" }.map { it.currentVersionId }.toSet()
        val currentSetIds = versions.filter { it.id in currentVersionIds }.map { it.postingSetId }.toSet()
        val selected = postings.filter { it.postingSetId in currentSetIds }
        val consumption = selected.filter { it.role == "expense" }.sumOf { it.minor }
        val cashOutflow = -selected.filter { it.role == "payment_asset" && it.minor < 0 }.sumOf { it.minor }
        val metrics = listOf(
            "balance_adjustment_net_worth_change" to 0L,
            "budget" to 0L,
            "cash_inflow" to 0L,
            "cash_outflow" to cashOutflow,
            "consumption" to consumption,
            "income" to 0L,
            "internal_transfer_amount" to 0L,
            "net_worth_change" to -consumption,
            "ordinary_expense" to consumption,
            "ordinary_income" to 0L,
        )
        return JsonArray(listOf(obj(
            "period_type" to JsonPrimitive("cumulative"),
            "period" to JsonPrimitive("lifecycle"),
            "metrics" to JsonArray(metrics.map { (metric, minor) ->
                obj(
                    "metric" to JsonPrimitive(metric),
                    "applicability" to JsonPrimitive("applicable"),
                    "currency" to JsonPrimitive("CNY"),
                    "amount" to JsonPrimitive(amount(minor, 2)),
                )
            }),
        )))
    }

    private fun projectStatuses(
        transactions: List<Rg06Tx>,
        lifecycles: List<Rg06Lifecycle>,
        installments: List<Rg06Installment>,
        histories: List<Rg06History>,
        candidateStatuses: List<Rg06CandidateStatus>,
        reconciliations: List<Rg06Reconciliation>,
    ): JsonArray {
        val statuses = mutableListOf<JsonObject>()
        candidateStatuses.groupBy { it.candidateId }.toSortedMap().forEach { (candidateId, entries) ->
            statuses += derivedStatus(
                "candidate",
                candidateId,
                "confirmation_status",
                entries.maxBy { it.sequence }.status.lowercase(),
            )
        }
        lifecycles.forEach { lifecycle ->
            val latest = histories.filter { it.lifecycleId == lifecycle.id }.maxByOrNull { it.sequence }
                ?: return@forEach
            statuses += derivedStatus("domain_entity", lifecycle.id, "fulfillment_status", latest.fulfillmentStatus.lowercase())
            statuses += derivedStatus("domain_entity", lifecycle.id, "payment_progress", latest.paymentProgress.lowercase())
            val relationPayments = installments.filter { it.relationId == lifecycle.relationId }
            val paymentStatuses = relationPayments.mapNotNull { payment ->
                reconciliations.firstOrNull { it.postingId == payment.assetPostingId }?.status
            }
            val reconciliation = when {
                paymentStatuses.isEmpty() -> "pending"
                paymentStatuses.size == relationPayments.size && paymentStatuses.all { it == "MATCHED" } -> "complete"
                paymentStatuses.any { it == "MATCHED" } -> "partial"
                else -> "pending"
            }
            statuses += derivedStatus("domain_entity", lifecycle.id, "reconciliation", reconciliation)
        }
        transactions.filter { it.type == "expense" }.forEach { transaction ->
            val version = versions().single { it.id == transaction.currentVersionId }
            val eligiblePostingIds = postings().filter {
                it.postingSetId == version.postingSetId && it.reconciliationEligible
            }.map { it.id }
            if (eligiblePostingIds.isNotEmpty()) {
                val values = eligiblePostingIds.mapNotNull { postingId ->
                    reconciliations.firstOrNull { it.postingId == postingId }?.status
                }
                val value = when {
                    values.size == eligiblePostingIds.size && values.all { it == "MATCHED" } -> "matched"
                    values.any { it == "MATCHED" } -> "partial"
                    else -> "pending"
                }
                statuses += derivedStatus("transaction", transaction.id, "reconciliation_summary", value)
            }
        }
        return JsonArray(statuses)
    }

    private fun derivedStatus(kind: String, targetId: String, name: String, value: String): JsonObject = obj(
        "id" to JsonPrimitive("status-$kind-$targetId-$name"),
        "target_kind" to JsonPrimitive(kind),
        "target_id" to JsonPrimitive(targetId),
        "status_name" to JsonPrimitive(name),
        "value" to JsonPrimitive(value),
    )

    private fun transactions(): List<Rg06Tx> = rows(
        """
        SELECT tx.transaction_id, tx.kind, current.current_version_id
        FROM ledger_transaction tx
        JOIN ledger_transaction_current_version current
          ON current.ledger_id = tx.ledger_id AND current.transaction_id = tx.transaction_id
        WHERE tx.ledger_id = ?
        ORDER BY tx.rowid
        """.trimIndent(),
        ledgerId,
    ) { cursor ->
        Rg06Tx(
            cursor.string(0),
            when (cursor.string(1)) {
                "OPENING_BALANCE" -> "opening_balance"
                else -> "expense"
            },
            cursor.string(2),
        )
    }

    private fun versions(): List<Rg06Version> = rows(
        """
        SELECT v.version_id, v.transaction_id, v.version_number, v.posting_set_id,
               v.occurred_at, v.statistics_at, v.effective_at, v.note,
               (SELECT confirmation.confirmation_id
                FROM rg06_confirmation confirmation
                JOIN rg06_installment payment
                  ON payment.ledger_id = confirmation.ledger_id
                 AND payment.payment_id = confirmation.payment_id
                WHERE payment.ledger_id = v.ledger_id
                  AND payment.transaction_version_id = v.version_id
                LIMIT 1)
        FROM transaction_version v
        WHERE v.ledger_id = ?
        ORDER BY v.rowid
        """.trimIndent(),
        ledgerId,
    ) { cursor ->
        Rg06Version(
            cursor.string(0), cursor.string(1), cursor.long(2), cursor.string(3),
            cursor.string(4), cursor.string(5), cursor.string(6), cursor.getString(7), cursor.getString(8),
        )
    }

    private fun postingSets(): Map<String, List<String>> = rows(
        """
        SELECT set_row.posting_set_id, posting.posting_id
        FROM posting_set set_row
        JOIN posting ON posting.ledger_id = set_row.ledger_id
                    AND posting.posting_set_id = set_row.posting_set_id
        WHERE set_row.ledger_id = ?
        ORDER BY set_row.rowid, posting.posting_index
        """.trimIndent(),
        ledgerId,
    ) { cursor -> cursor.string(0) to cursor.string(1) }.groupBy({ it.first }, { it.second })

    private fun postings(): List<Rg06Posting> = rows(
        """
        SELECT posting.posting_id, posting.posting_set_id, posting.account_id,
               posting.amount_minor, posting.currency_code, posting.currency_precision,
               semantic.role, semantic.category_id, semantic.reconciliation_eligible
        FROM posting
        LEFT JOIN rg06_posting_semantic semantic
          ON semantic.ledger_id = posting.ledger_id AND semantic.posting_id = posting.posting_id
        WHERE posting.ledger_id = ?
        ORDER BY posting.rowid
        """.trimIndent(),
        ledgerId,
    ) { cursor ->
        Rg06Posting(
            cursor.string(0), cursor.string(1), cursor.string(2), cursor.long(3), cursor.string(4), cursor.long(5),
            cursor.getString(6), cursor.getString(7), cursor.getLong(8) == 1L,
        )
    }

    private fun sources(): List<Rg06Source> = rows(
        "SELECT source_id, source_kind, amount_minor, currency_code, currency_precision, observed_at, observed_at_text, time_variant, mirror_of_source_id FROM rg06_source WHERE ledger_id = ? ORDER BY rowid",
        ledgerId,
    ) { cursor ->
        Rg06Source(
            cursor.string(0), cursor.string(1), cursor.long(2), cursor.string(3), cursor.long(4),
            cursor.string(5), cursor.string(6), cursor.string(7), cursor.getString(8),
        )
    }

    private fun evidence(): List<Rg06Evidence> = rows(
        "SELECT evidence_id, source_id, evidence_kind, observed_at, time_variant, payment_id, mirror_of_evidence_id, merged_into_link_id FROM rg06_evidence WHERE ledger_id = ? ORDER BY rowid",
        ledgerId,
    ) { cursor ->
        Rg06Evidence(
            cursor.string(0), cursor.string(1), cursor.string(2), cursor.string(3), cursor.string(4),
            cursor.getString(5), cursor.getString(6), cursor.getString(7),
        )
    }

    private fun candidates(): List<Rg06Candidate> = rows(
        "SELECT candidate_id, source_id, evidence_id, role_fact, amount_minor, currency_code, currency_precision, source_payment_at, confidence FROM rg06_candidate WHERE ledger_id = ? ORDER BY rowid",
        ledgerId,
    ) { cursor ->
        Rg06Candidate(
            cursor.string(0), cursor.string(1), cursor.string(2), cursor.getString(3), cursor.long(4),
            cursor.string(5), cursor.long(6), cursor.string(7), cursor.string(8),
        )
    }

    private fun candidateStatuses(): List<Rg06CandidateStatus> = rows(
        "SELECT candidate_id, status_sequence, status_id, status FROM rg06_candidate_status_history WHERE ledger_id = ? ORDER BY candidate_id, status_sequence",
        ledgerId,
    ) { cursor -> Rg06CandidateStatus(cursor.string(0), cursor.long(1), cursor.string(2), cursor.string(3)) }

    private fun confirmations(): List<Rg06Confirmation> = rows(
        "SELECT confirmation_id, identity_value, confirmation_kind, candidate_id, confirmed_at FROM rg06_confirmation WHERE ledger_id = ? ORDER BY rowid",
        ledgerId,
    ) { cursor ->
        Rg06Confirmation(cursor.string(0), cursor.string(1), cursor.string(2), cursor.getString(3), cursor.getString(4))
    }

    private fun evidenceLinks(): List<Rg06EvidenceLink> = rows(
        "SELECT link_id, evidence_id, payment_id, posting_id, link_kind FROM rg06_evidence_link WHERE ledger_id = ? ORDER BY rowid",
        ledgerId,
    ) { cursor -> Rg06EvidenceLink(cursor.string(0), cursor.string(1), cursor.string(2), cursor.string(3), cursor.string(4)) }

    private fun relations(): List<Rg06Relation> = rows(
        "SELECT relation_id FROM rg06_relation WHERE ledger_id = ? ORDER BY rowid",
        ledgerId,
    ) { cursor -> cursor.string(0) }.map { relationId ->
        Rg06Relation(
            relationId,
            rows(
                "SELECT member_kind, member_id FROM rg06_relation_member WHERE ledger_id = ? AND relation_id = ? ORDER BY member_index",
                ledgerId,
                relationId,
            ) { cursor -> cursor.string(0) to cursor.string(1) },
        )
    }

    private fun histories(): List<Rg06History> = rows(
        "SELECT lifecycle_id, history_sequence, history_id, event_type, occurred_at, total_minor, paid_minor, due_minor, payment_id, payment_progress, fulfillment_status, state_transition_effect_count FROM rg06_lifecycle_history WHERE ledger_id = ? ORDER BY rowid",
        ledgerId,
    ) { cursor ->
        Rg06History(
            cursor.string(0), cursor.long(1), cursor.string(2), cursor.string(3), cursor.string(4),
            cursor.long(5), cursor.long(6), cursor.long(7), cursor.getString(8), cursor.string(9),
            cursor.string(10), cursor.long(11),
        )
    }

    private fun lifecycles(): List<Rg06Lifecycle> = rows(
        "SELECT lifecycle_id, relation_id, total_minor, paid_minor, due_minor, currency_code, currency_precision, category_id FROM rg06_lifecycle WHERE ledger_id = ? ORDER BY rowid",
        ledgerId,
    ) { cursor ->
        Rg06Lifecycle(
            cursor.string(0), cursor.string(1), cursor.long(2), cursor.long(3), cursor.long(4),
            cursor.string(5), cursor.long(6), cursor.string(7),
        )
    }

    private fun installments(): List<Rg06Installment> = rows(
        "SELECT payment_id, relation_id, payment_role, amount_minor, currency_code, currency_precision, funding_account_id, transaction_id, transaction_version_id, expense_posting_id, asset_posting_id, actual_payment_at, statistics_at, source_payment_at FROM rg06_installment WHERE ledger_id = ? ORDER BY rowid",
        ledgerId,
    ) { cursor ->
        Rg06Installment(
            cursor.string(0), cursor.string(1), cursor.string(2), cursor.long(3), cursor.string(4), cursor.long(5),
            cursor.string(6), cursor.string(7), cursor.string(8), cursor.string(9), cursor.string(10),
            cursor.string(11), cursor.string(12), cursor.getString(13),
        )
    }

    private fun reconciliations(): List<Rg06Reconciliation> = rows(
        """
        SELECT reconciliation.reconciliation_id, reconciliation.posting_id, history.status
        FROM rg06_posting_reconciliation reconciliation
        JOIN rg06_reconciliation_history history
          ON history.ledger_id = reconciliation.ledger_id
         AND history.reconciliation_id = reconciliation.reconciliation_id
         AND history.status_sequence = reconciliation.latest_sequence
        WHERE reconciliation.ledger_id = ?
        ORDER BY reconciliation.rowid
        """.trimIndent(),
        ledgerId,
    ) { cursor -> Rg06Reconciliation(cursor.string(0), cursor.string(1), cursor.string(2)) }

    private fun <T> rows(sql: String, vararg params: String, mapper: (SqlCursor) -> T): List<T> =
        driver.executeQuery(
            null,
            sql,
            { cursor ->
                val result = mutableListOf<T>()
                while (cursor.next().value) result += mapper(cursor)
                QueryResult.Value(result)
            },
            params.size,
        ) {
            params.forEachIndexed { index, value -> bindString(index, value) }
        }.value

    private fun obj(vararg fields: Pair<String, JsonElement?>): JsonObject =
        JsonObject(fields.mapNotNull { (key, value) -> value?.let { key to it } }.toMap())

    private fun amount(minor: Long, precision: Long): String =
        BigDecimal.valueOf(minor, precision.toInt()).setScale(precision.toInt()).toPlainString()

    private fun magnitude(value: Long): Long = when (value) {
        Long.MIN_VALUE -> error("RG-06 source amount overflow")
        else -> kotlin.math.abs(value)
    }

    private fun caseTime(value: String): String =
        OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.ofHours(8))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))

    private fun SqlCursor.string(index: Int): String = requireNotNull(getString(index))
    private fun SqlCursor.long(index: Int): Long = requireNotNull(getLong(index))
}
