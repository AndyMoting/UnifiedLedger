package com.unifiedledger.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.goldenV2MigrationId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.LedgerCatalog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private data class Rg07Tx(
    val id: String,
    val kind: String,
    val currentVersionId: String,
)

private data class Rg07Version(
    val id: String,
    val txId: String,
    val number: Long,
    val setId: String,
    val occurred: String,
    val statistics: String,
    val effective: String,
    val confirmationId: String?,
    val confirmedAt: String?,
    val note: String?,
)

private data class Rg07Posting(
    val id: String,
    val setId: String,
    val accountId: String,
    val minor: Long,
    val currency: String,
    val precision: Long,
    val role: String?,
    val categoryId: String?,
    val eligible: Boolean,
)

private data class Rg07Source(
    val id: String,
    val type: String,
    val recordId: String,
    val evidenceId: String,
    val hash: String,
    val observed: String,
    val amount: Long?,
    val currency: String?,
    val precision: Long?,
    val accountId: String?,
    val reported: String?,
    val proves: Boolean?,
    val processor: String?,
    val sourceObserved: String?,
    val booking: String?,
    val value: String?,
    val originalHash: String?,
    val mirrorOf: String?,
)

private data class Rg07Candidate(
    val id: String,
    val sourceId: String,
    val evidenceId: String,
    val confidence: String,
    val amount: Long,
    val currency: String,
    val precision: Long,
    val proposedOriginal: String?,
    val proposedCategory: String?,
    val proposedDestination: String?,
    val arrived: String?,
    val originalHash: String?,
    val ruleVersion: Long,
)

private data class Rg07CandidateStatus(
    val candidateId: String,
    val sequence: Long,
    val id: String,
    val status: String,
    val occurred: String,
    val formalEffects: Long,
)

private data class Rg07Confirmation(
    val id: String,
    val operationId: String,
    val kind: String,
    val subjectId: String,
    val confirmedAt: String?,
    val originalTx: String?,
)

private data class Rg07Evidence(
    val id: String,
    val sourceId: String,
    val type: String,
    val observed: String,
    val mirrorOf: String?,
    val mergedInto: String?,
)

private data class Rg07Link(
    val id: String,
    val evidenceId: String,
    val targetKind: String,
    val targetId: String,
    val role: String,
)

private data class Rg07Relation(
    val id: String,
    val type: String,
    val members: List<String>,
)

private data class Rg07Entity(
    val id: String,
    val relationId: String,
    val originalTx: String,
    val refundTx: String?,
    val categoryId: String,
    val requested: Long,
    val received: Long,
    val currency: String,
    val precision: Long,
    val destination: String?,
    val requestedAt: String?,
    val approvedAt: String?,
    val processorAt: String?,
    val sourceObservedAt: String?,
    val bookingAt: String?,
    val valueAt: String?,
    val confirmedAt: String?,
    val arrivedAt: String?,
)

private data class Rg07History(
    val entityId: String,
    val sequence: Long,
    val id: String,
    val operationIdentity: String,
    val state: String,
    val occurred: String,
    val txId: String?,
    val formalEffects: Long,
)

private data class Rg07Reconciliation(
    val id: String,
    val postingId: String,
    val status: String,
)

internal class Rg07StateProjector(
    private val driver: JdbcSqliteDriver,
    private val ledgerId: String,
    private val rootId: String,
    private val purpose: String,
    private val catalog: LedgerCatalog,
    private val storeCreditAccountIds: Set<String>,
) {
    fun state(
        id: String,
        asOfOperationId: String?,
    ): JsonObject {
        val txs = transactions()
        val versions = versions()
        val postings = postings()
        val sets = postingSets()
        val sources = sources()
        val candidates = candidates()
        val statuses = candidateStatuses()
        val confirmations = confirmations()
        val evidence = evidence()
        val links = links()
        val relations = relations()
        val entities = entities()
        val histories = histories()
        val reconciliations = reconciliations()
        return obj(
            "id" to JsonPrimitive(id),
            "root_id" to JsonPrimitive(rootId),
            "as_of_operation_id" to (asOfOperationId?.let(::JsonPrimitive) ?: JsonNull),
            "catalog" to projectCatalog(),
            "transactions" to JsonArray(txs.map { obj("id" to JsonPrimitive(it.id), "type" to JsonPrimitive(it.kind), "current_version_id" to JsonPrimitive(it.currentVersionId)) }),
            "transaction_versions" to
                JsonArray(
                    versions.map { version ->
                        obj("id" to JsonPrimitive(version.id), "transaction_id" to JsonPrimitive(version.txId), "version_number" to JsonPrimitive(version.number), "posting_set_id" to JsonPrimitive(version.setId), "occurred_at" to JsonPrimitive(caseTime(version.occurred)), "statistics_at" to JsonPrimitive(caseTime(version.statistics)), "effective_at" to JsonPrimitive(caseTime(version.effective)), "created_at" to version.confirmedAt?.let { JsonPrimitive(caseTime(it)) }, "confirmation_id" to version.confirmationId?.let(::JsonPrimitive), "note" to version.note?.let(::JsonPrimitive))
                    },
                ),
            "posting_sets" to JsonArray(sets.map { (setId, ids) -> obj("id" to JsonPrimitive(setId), "posting_ids" to JsonArray(ids.map(::JsonPrimitive))) }),
            "postings" to JsonArray(postings.map { p -> obj("id" to JsonPrimitive(p.id), "posting_set_id" to JsonPrimitive(p.setId), "account_id" to JsonPrimitive(p.accountId), "category_id" to p.categoryId?.let(::JsonPrimitive), "amount" to JsonPrimitive(amount(p.minor, p.precision)), "currency" to JsonPrimitive(p.currency), "role" to p.role?.let(::JsonPrimitive), "reconciliation_eligible" to JsonPrimitive(p.eligible)) }),
            "sources" to JsonArray(sources.map(::projectSource)),
            "candidates" to JsonArray(candidates.map { projectCandidate(it, statuses.filter { status -> status.candidateId == it.id }) }),
            "confirmations" to JsonArray(confirmations.map(::projectConfirmation)),
            "evidence" to JsonArray(evidence.map(::projectEvidence)),
            "evidence_links" to JsonArray(links.map { l -> obj("id" to JsonPrimitive(l.id), "evidence_id" to JsonPrimitive(l.evidenceId), "target_kind" to JsonPrimitive(l.targetKind), "target_id" to JsonPrimitive(l.targetId), "role" to JsonPrimitive(l.role)) }),
            "relations" to JsonArray(relations.map { r -> obj("id" to JsonPrimitive(r.id), "type" to JsonPrimitive(r.type), "member_refs" to JsonArray(r.members.map { member -> obj("kind" to JsonPrimitive("transaction"), "id" to JsonPrimitive(member)) }), "payload" to obj()) }),
            "domain_entities" to JsonArray(entities.map { projectEntity(it, histories.filter { history -> history.entityId == it.id }) }),
            "audit_links" to JsonArray(emptyList()),
            "posting_reconciliations" to JsonArray(reconciliations.map { r -> obj("id" to JsonPrimitive(r.id), "posting_id" to JsonPrimitive(r.postingId), "status" to JsonPrimitive(r.status.lowercase())) }),
            "balances" to projectBalances(txs, versions, postings, sets),
            "reports" to projectReports(txs, versions, postings, sets),
            "derived_statuses" to projectStatuses(txs, versions, postings, entities, histories, statuses, reconciliations),
        )
    }

    private fun projectCatalog(): JsonObject =
        obj(
            "accounts" to JsonArray(catalog.accounts.map { account -> obj("id" to JsonPrimitive(account.id.value), "name" to JsonPrimitive(account.id.value), "kind" to JsonPrimitive(account.kind.name.lowercase()), "currency" to JsonPrimitive(account.currency.code), "owned_by_user" to JsonPrimitive(account.ownedByUser), "real_account" to JsonPrimitive(account.realAccount), "reconciliation_eligible" to JsonPrimitive(account.ownedByUser && account.realAccount && account.kind in setOf(AccountKind.ASSET, AccountKind.LIABILITY) && account.id.value !in storeCreditAccountIds)) }),
            "categories" to JsonArray(catalog.categories.map { category -> obj("id" to JsonPrimitive(category.id.value), "name" to JsonPrimitive(category.id.value), "parent_id" to (category.parentId?.value?.let(::JsonPrimitive) ?: JsonNull), "posting_account_id" to (category.postingAccountId?.value?.let(::JsonPrimitive) ?: JsonNull), "active" to JsonPrimitive(category.active)) }),
        )

    private fun projectSource(source: Rg07Source): JsonObject {
        val payload =
            linkedMapOf<String, JsonElement>(
                "source_record_id" to JsonPrimitive(source.recordId),
                "evidence_id" to JsonPrimitive(source.evidenceId),
                "immutable_payload_hash" to JsonPrimitive(source.hash),
                "kind" to JsonPrimitive(source.type),
                "observed_at" to JsonPrimitive(caseTime(source.observed)),
            )
        source.amount?.let { payload["amount"] = JsonPrimitive(amount(it, source.precision ?: 2)) }
        source.currency?.let { payload["currency"] = JsonPrimitive(it) }
        source.accountId?.let { payload["account_id"] = JsonPrimitive(it) }
        source.reported?.let { payload["reported_state"] = JsonPrimitive(it.lowercase()) }
        source.proves?.let { payload["proves_arrival"] = JsonPrimitive(it) }
        source.processor?.let { payload["processor_reported_at"] = JsonPrimitive(caseTime(it)) }
        // RG-07 distinguishes imported wallet-credit timing from the generic
        // observed timestamp.  The original-payment and manually recorded
        // sources do not expose a duplicated source_observed_at field.
        if (source.type == "wallet_credit" && source.processor != null) {
            source.sourceObserved?.let { payload["source_observed_at"] = JsonPrimitive(caseTime(it)) }
        }
        source.booking?.let { payload["booking_at"] = JsonPrimitive(caseTime(it)) }
        source.value?.let { payload["value_at"] = JsonPrimitive(caseTime(it)) }
        source.originalHash?.let { payload["original_source_payload_hash"] = JsonPrimitive(it) }
        source.mirrorOf?.let { payload["mirror_of_source_id"] = JsonPrimitive(it) }
        return obj("id" to JsonPrimitive(source.id), "type" to JsonPrimitive(source.type), "payload" to JsonObject(payload))
    }

    private fun projectCandidate(
        candidate: Rg07Candidate,
        history: List<Rg07CandidateStatus>,
    ): JsonObject =
        obj(
            "id" to JsonPrimitive(candidate.id),
            "type" to JsonPrimitive("refund_credit"),
            "source_ids" to JsonArray(listOf(JsonPrimitive(candidate.sourceId))),
            "confidence" to JsonPrimitive(candidate.confidence),
            "payload" to obj("proposed_amount" to JsonPrimitive(amount(candidate.amount, candidate.precision)), "currency" to JsonPrimitive(candidate.currency), "proposed_original_transaction_id" to (candidate.proposedOriginal?.let(::JsonPrimitive) ?: JsonNull), "proposed_category_id" to (candidate.proposedCategory?.let(::JsonPrimitive) ?: JsonNull), "proposed_destination_account_id" to (candidate.proposedDestination?.let(::JsonPrimitive) ?: JsonNull), "proposed_arrived_at" to (candidate.arrived?.let { JsonPrimitive(caseTime(it)) } ?: JsonNull), "requires_confirmation" to JsonArray(listOf("original_transaction_id", "category_id_and_allocation", "destination_account_id", "arrival").map(::JsonPrimitive)), "rule_version" to JsonPrimitive(candidate.ruleVersion), "original_source_payload_hash" to (candidate.originalHash?.let(::JsonPrimitive) ?: JsonNull)),
            "status_history" to JsonArray(history.sortedBy { it.sequence }.map { item -> obj("id" to JsonPrimitive(item.id), "status" to JsonPrimitive(item.status.lowercase()), "occurred_at" to JsonPrimitive(caseTime(item.occurred)), "formal_effect_count" to JsonPrimitive(item.formalEffects), "sequence" to JsonPrimitive(item.sequence)) }),
        )

    private fun projectConfirmation(item: Rg07Confirmation): JsonObject =
        obj(
            "id" to JsonPrimitive(item.id),
            "type" to JsonPrimitive(item.kind),
            "operation_id" to JsonPrimitive(item.operationId),
            "subject" to obj("kind" to JsonPrimitive(if (item.kind == "explicit_manual_save") "operation" else "relation"), "id" to JsonPrimitive(item.subjectId)),
            "confirmed_at" to (item.confirmedAt?.let { JsonPrimitive(caseTime(it)) } ?: JsonNull),
            "payload" to (if (item.kind == "explicit_manual_save") obj() else item.originalTx?.let { obj("original_transaction_id" to JsonPrimitive(it)) } ?: obj()),
        )

    private fun projectEvidence(item: Rg07Evidence): JsonObject =
        obj(
            "id" to JsonPrimitive(item.id),
            "type" to JsonPrimitive(item.type),
            "source_ids" to JsonArray(listOf(JsonPrimitive(item.sourceId))),
            "payload" to obj("observed_at" to JsonPrimitive(caseTime(item.observed)), "mirror_of_evidence_id" to item.mirrorOf?.let(::JsonPrimitive), "merged_into_evidence_link_id" to item.mergedInto?.let(::JsonPrimitive)),
        )

    private fun projectEntity(
        entity: Rg07Entity,
        history: List<Rg07History>,
    ): JsonObject =
        obj(
            "id" to JsonPrimitive(entity.id),
            "type" to JsonPrimitive("refund_relationship"),
            "payload" to obj("relation_id" to JsonPrimitive(entity.relationId), "original_transaction_id" to JsonPrimitive(entity.originalTx), "refund_transaction_id" to (entity.refundTx?.let(::JsonPrimitive) ?: JsonNull), "category_id" to JsonPrimitive(entity.categoryId), "requested_amount" to JsonPrimitive(amount(entity.requested, entity.precision)), "received_amount" to JsonPrimitive(amount(entity.received, entity.precision)), "currency" to JsonPrimitive(entity.currency), "destination_account_id" to (entity.destination?.let(::JsonPrimitive) ?: JsonNull), "times" to obj("requested_at" to entity.requestedAt?.let { JsonPrimitive(caseTime(it)) }, "approved_at" to entity.approvedAt?.let { JsonPrimitive(caseTime(it)) }, "processor_reported_at" to entity.processorAt?.let { JsonPrimitive(caseTime(it)) }, "source_observed_at" to entity.sourceObservedAt?.let { JsonPrimitive(caseTime(it)) }, "booking_at" to entity.bookingAt?.let { JsonPrimitive(caseTime(it)) }, "value_at" to entity.valueAt?.let { JsonPrimitive(caseTime(it)) }, "confirmed_at" to entity.confirmedAt?.let { JsonPrimitive(caseTime(it)) }, "arrived_at" to entity.arrivedAt?.let { JsonPrimitive(caseTime(it)) }), "state_history" to JsonArray(history.sortedBy { it.sequence }.map { h -> obj("id" to JsonPrimitive(h.id), "state" to JsonPrimitive(h.state.lowercase()), "occurred_at" to JsonPrimitive(caseTime(h.occurred)), "transaction_id" to (h.txId?.let(::JsonPrimitive) ?: JsonNull), "formal_effect_count" to JsonPrimitive(h.formalEffects), "sequence" to JsonPrimitive(h.sequence)) })),
        )

    private fun projectBalances(
        txs: List<Rg07Tx>,
        versions: List<Rg07Version>,
        postings: List<Rg07Posting>,
        sets: Map<String, List<String>>,
    ): JsonArray {
        val currentSets = txs.flatMap { tx -> versions.single { it.id == tx.currentVersionId }.setId.let { sets[it].orEmpty() } }.toSet()
        return JsonArray(catalog.accounts.map { account -> obj("account_id" to JsonPrimitive(account.id.value), "currency" to JsonPrimitive(account.currency.code), "amount" to JsonPrimitive(amount(postings.filter { it.id in currentSets && it.accountId == account.id.value }.sumOf { it.minor }, account.currency.precision.toLong()))) })
    }

    private fun projectReports(
        txs: List<Rg07Tx>,
        versions: List<Rg07Version>,
        postings: List<Rg07Posting>,
        sets: Map<String, List<String>>,
    ): JsonArray {
        val periods = listOf("day" to "2026-01-10", "day" to "2026-02-02", "day" to "2026-02-10", "month" to "2026-01", "month" to "2026-02", "cumulative" to "lifecycle")
        return JsonArray(
            periods.map { (type, period) ->
                val selectedSets =
                    txs
                        .filter { it.kind == "expense" || it.kind == "refund_receipt" }
                        .filter { tx ->
                            val date = versions.single { it.id == tx.currentVersionId }.statistics.substring(0, 10)
                            type == "cumulative" || date.startsWith(period)
                        }.map { versions.single { v -> v.id == it.currentVersionId }.setId }
                        .toSet()
                val selected = postings.filter { it.setId in selectedSets }
                val consumption = selected.filter { it.role == "expense" }.sumOf { it.minor }
                val inflow = selected.filter { it.role == "destination_asset" && it.minor > 0 }.sumOf { it.minor }
                val outflow = -selected.filter { it.role == "payment_asset" && it.minor < 0 }.sumOf { it.minor }
                obj("period_type" to JsonPrimitive(type), "period" to JsonPrimitive(period), "metrics" to JsonArray(listOf("cash_inflow" to inflow, "cash_outflow" to outflow, "consumption" to consumption, "income" to 0L, "net_worth_change" to -consumption).map { (metric, value) -> obj("metric" to JsonPrimitive(metric), "applicability" to JsonPrimitive("applicable"), "currency" to JsonPrimitive("CNY"), "amount" to JsonPrimitive(amount(value, 2))) }))
            },
        )
    }

    private fun projectStatuses(
        txs: List<Rg07Tx>,
        versions: List<Rg07Version>,
        postings: List<Rg07Posting>,
        entities: List<Rg07Entity>,
        histories: List<Rg07History>,
        statuses: List<Rg07CandidateStatus>,
        reconciliations: List<Rg07Reconciliation>,
    ): JsonArray {
        val result = mutableListOf<JsonObject>()
        entities.forEach { entity ->
            val latest = histories.filter { it.entityId == entity.id }.maxByOrNull { it.sequence } ?: return@forEach
            result += obj("id" to JsonPrimitive(goldenV2MigrationId("RG-07", rootId, "derived_status", "$.derived.refund_status", entity.id)), "target_kind" to JsonPrimitive("domain_entity"), "target_id" to JsonPrimitive(entity.id), "status_name" to JsonPrimitive("refund_status"), "value" to JsonPrimitive(latest.state.lowercase()))
        }
        txs.filter { it.kind == "expense" || it.kind == "refund_receipt" }.forEach { tx ->
            val version = versions.single { it.id == tx.currentVersionId }
            val eligible = postings.filter { it.setId == version.setId && it.eligible }
            if (eligible.isNotEmpty()) {
                val values = eligible.mapNotNull { p -> reconciliations.firstOrNull { it.postingId == p.id }?.status }
                val value =
                    when {
                        values.size == eligible.size && values.all { it == "MATCHED" } -> "matched"
                        values.any { it == "MATCHED" } -> "partial"
                        else -> "pending"
                    }
                result += obj("id" to JsonPrimitive(goldenV2MigrationId("RG-07", rootId, "derived_status", "$.derived.reconciliation_summary", tx.id)), "target_kind" to JsonPrimitive("transaction"), "target_id" to JsonPrimitive(tx.id), "status_name" to JsonPrimitive("reconciliation_summary"), "value" to JsonPrimitive(value))
            }
        }
        statuses.groupBy { it.candidateId }.forEach { (candidateId, history) ->
            result += obj("id" to JsonPrimitive(goldenV2MigrationId("RG-07", rootId, "derived_status", "$.derived.confirmation_status", candidateId)), "target_kind" to JsonPrimitive("candidate"), "target_id" to JsonPrimitive(candidateId), "status_name" to JsonPrimitive("confirmation_status"), "value" to JsonPrimitive(history.maxBy { it.sequence }.status.lowercase()))
        }
        return JsonArray(result)
    }

    private fun transactions(): List<Rg07Tx> =
        rows("SELECT tx.transaction_id, COALESCE(tx.canonical_kind, tx.kind), current.current_version_id FROM ledger_transaction tx JOIN ledger_transaction_current_version current ON current.ledger_id=tx.ledger_id AND current.transaction_id=tx.transaction_id WHERE tx.ledger_id=? ORDER BY tx.rowid", ledgerId) { cursor ->
            val kind =
                when (cursor.string(1)) {
                    "OPENING_BALANCE" -> "opening_balance"
                    "REFUND_RECEIPT" -> "refund_receipt"
                    else -> "expense"
                }
            Rg07Tx(cursor.string(0), kind, cursor.string(2))
        }

    private fun versions(): List<Rg07Version> = rows("SELECT v.version_id,v.transaction_id,v.version_number,v.posting_set_id,v.occurred_at,v.statistics_at,v.effective_at,metadata.confirmation_id,metadata.created_at,v.note FROM transaction_version v LEFT JOIN rg07_transaction_version_metadata metadata ON metadata.ledger_id=v.ledger_id AND metadata.version_id=v.version_id WHERE v.ledger_id=? ORDER BY v.rowid", ledgerId) { c -> Rg07Version(c.string(0), c.string(1), c.long(2), c.string(3), c.string(4), c.string(5), c.string(6), c.getString(7), c.getString(8), c.getString(9)) }

    private fun postingSets(): Map<String, List<String>> = rows("SELECT posting_set.posting_set_id, posting.posting_id FROM posting_set JOIN posting ON posting.ledger_id=posting_set.ledger_id AND posting.posting_set_id=posting_set.posting_set_id WHERE posting_set.ledger_id=? ORDER BY posting_set.rowid,posting.posting_index", ledgerId) { c -> c.string(0) to c.string(1) }.groupBy({ it.first }, { it.second })

    private fun postings(): List<Rg07Posting> = rows("SELECT p.posting_id,p.posting_set_id,p.account_id,p.amount_minor,p.currency_code,p.currency_precision,s.role,s.category_id,s.reconciliation_eligible FROM posting p LEFT JOIN rg07_posting_semantic s ON s.ledger_id=p.ledger_id AND s.posting_id=p.posting_id WHERE p.ledger_id=? ORDER BY p.rowid", ledgerId) { c -> Rg07Posting(c.string(0), c.string(1), c.string(2), c.long(3), c.string(4), c.long(5), c.getString(6), c.getString(7), c.getLong(8) == 1L) }

    private fun sources(): List<Rg07Source> = rows("SELECT source_id,source_kind,source_record_id,evidence_id,immutable_payload_hash,observed_at,amount_minor,currency_code,currency_precision,account_id,reported_state,proves_arrival,processor_reported_at,source_observed_at,booking_at,value_at,original_source_payload_hash,mirror_of_source_id FROM rg07_source WHERE ledger_id=? ORDER BY rowid", ledgerId) { c -> Rg07Source(c.string(0), sourceType(c.string(1)), c.string(2), c.string(3), c.string(4), c.string(5), c.getLong(6), c.getString(7), c.getLong(8), c.getString(9), c.getString(10), c.getLong(11)?.let { it == 1L }, c.getString(12), c.getString(13), c.getString(14), c.getString(15), c.getString(16), c.getString(17)) }

    private fun candidates(): List<Rg07Candidate> = rows("SELECT candidate_id,source_id,evidence_id,confidence,proposed_amount_minor,currency_code,currency_precision,proposed_original_transaction_id,proposed_category_id,proposed_destination_account_id,proposed_arrived_at,original_source_payload_hash,rule_version FROM rg07_candidate WHERE ledger_id=? ORDER BY rowid", ledgerId) { c -> Rg07Candidate(c.string(0), c.string(1), c.string(2), c.string(3), c.long(4), c.string(5), c.long(6), c.getString(7), c.getString(8), c.getString(9), c.getString(10), c.getString(11), c.long(12)) }

    private fun candidateStatuses(): List<Rg07CandidateStatus> = rows("SELECT candidate_id,status_sequence,status_id,status,occurred_at,formal_effect_count FROM rg07_candidate_status_history WHERE ledger_id=? ORDER BY candidate_id,status_sequence", ledgerId) { c -> Rg07CandidateStatus(c.string(0), c.long(1), c.string(2), c.string(3), c.string(4), c.long(5)) }

    private fun confirmations(): List<Rg07Confirmation> = rows("SELECT confirmation_id,operation_id,'explicit_manual_save',subject_id,confirmed_at,original_transaction_id FROM rg07_operation_confirmation WHERE ledger_id=? UNION ALL SELECT confirmation_id,operation_id,'refund_relationship_confirmation',subject_id,confirmed_at,original_transaction_id FROM rg07_confirmation WHERE ledger_id=? ORDER BY confirmation_id", ledgerId, ledgerId) { c -> Rg07Confirmation(c.string(0), c.string(1), c.string(2), c.string(3), c.getString(4), c.getString(5)) }.sortedBy { it.id }

    private fun evidence(): List<Rg07Evidence> = rows("SELECT evidence_id,source_id,evidence_kind,observed_at,mirror_of_evidence_id,merged_into_evidence_link_id FROM rg07_evidence WHERE ledger_id=? ORDER BY rowid", ledgerId) { c -> Rg07Evidence(c.string(0), c.string(1), evidenceType(c.string(2)), c.string(3), c.getString(4), c.getString(5)) }

    private fun links(): List<Rg07Link> = rows("SELECT link_id,evidence_id,target_kind,target_id,role FROM rg07_evidence_link WHERE ledger_id=? ORDER BY rowid", ledgerId) { c -> Rg07Link(c.string(0), c.string(1), c.string(2).lowercase(), c.string(3), c.string(4).lowercase()) }

    private fun relations(): List<Rg07Relation> = rows("SELECT relation_id,relation_type FROM rg07_relation WHERE ledger_id=? ORDER BY rowid", ledgerId) { c -> Rg07Relation(c.string(0), c.string(1).lowercase(), emptyList()) }.map { relation -> relation.copy(members = rows("SELECT member_id FROM rg07_relation_member WHERE ledger_id=? AND relation_id=? ORDER BY rowid", ledgerId, relation.id) { c -> c.string(0) }) }

    private fun entities(): List<Rg07Entity> = rows("SELECT entity_id,relation_id,original_transaction_id,refund_transaction_id,category_id,requested_amount_minor,received_amount_minor,currency_code,currency_precision,destination_account_id,requested_at,approved_at,processor_reported_at,source_observed_at,booking_at,value_at,confirmed_at,arrived_at FROM rg07_refund_relationship WHERE ledger_id=? ORDER BY rowid", ledgerId) { c -> Rg07Entity(c.string(0), c.string(1), c.string(2), c.getString(3), c.string(4), c.long(5), c.long(6), c.string(7), c.long(8), c.getString(9), c.getString(10), c.getString(11), c.getString(12), c.getString(13), c.getString(14), c.getString(15), c.getString(16), c.getString(17)) }

    private fun histories(): List<Rg07History> = rows("SELECT entity_id,history_sequence,history_id,operation_identity,refund_state,occurred_at,transaction_id,formal_effect_count FROM rg07_refund_relationship_history WHERE ledger_id=? ORDER BY rowid", ledgerId) { c -> Rg07History(c.string(0), c.long(1), c.string(2), c.string(3), c.string(4), c.string(5), c.getString(6), c.long(7)) }

    private fun reconciliations(): List<Rg07Reconciliation> = rows("SELECT r.reconciliation_id,r.posting_id,h.status FROM rg07_posting_reconciliation r JOIN rg07_reconciliation_history h ON h.ledger_id=r.ledger_id AND h.reconciliation_id=r.reconciliation_id WHERE r.ledger_id=? AND h.status_sequence=(SELECT max(h2.status_sequence) FROM rg07_reconciliation_history h2 WHERE h2.ledger_id=h.ledger_id AND h2.reconciliation_id=h.reconciliation_id) ORDER BY r.rowid", ledgerId) { c -> Rg07Reconciliation(c.string(0), c.string(1), c.string(2)) }

    private fun sourceType(type: String): String = type.lowercase()

    private fun evidenceType(type: String): String = type.lowercase()

    private fun <T> rows(
        sql: String,
        vararg params: String,
        mapper: (SqlCursor) -> T,
    ): List<T> =
        driver
            .executeQuery(null, sql, { cursor ->
                val result = mutableListOf<T>()
                while (cursor.next().value) result += mapper(cursor)
                QueryResult.Value(result)
            }, params.size) {
                params.forEachIndexed { index, value -> bindString(index, value) }
            }.value

    private fun obj(vararg fields: Pair<String, JsonElement?>): JsonObject = JsonObject(fields.mapNotNull { (key, value) -> value?.let { key to it } }.toMap())

    private fun amount(
        minor: Long,
        precision: Long,
    ): String = BigDecimal.valueOf(minor, precision.toInt()).setScale(precision.toInt()).toPlainString()

    private fun caseTime(value: String): String = OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.ofHours(8)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))

    private fun SqlCursor.string(index: Int): String = requireNotNull(getString(index))

    private fun SqlCursor.long(index: Int): Long = requireNotNull(getLong(index))
}
