package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.HistoryMutationInput
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingFacts
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingReconciliationStatus
import com.unifiedledger.domain.PostingSet
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.ReconciliationMatch
import com.unifiedledger.domain.ReconciliationMatchReason
import com.unifiedledger.domain.ReconciliationMatchStatus
import com.unifiedledger.domain.ReconciliationMatchStatusEntry
import com.unifiedledger.domain.ReplacementPostingInput
import com.unifiedledger.domain.Transaction
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersion
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.createPostingReconciliation
import com.unifiedledger.domain.createReconciliationMatch
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * D-085 RG-12 fixture replay (shard 4 of the RG-12 runtime): derives the 12-operation plan
 * (accepted 1 / no_change 1 / rejected 10; root-correction 2, root-rejections 10) directly from
 * the frozen direct-v2 contract `golden/rules/rg-12.json`, builds the per-root catalogs and the
 * three initial-state baselines (the partial root carries the pending liability fact and the
 * rejection root carries the external and USD accounts), and loads the runtime anchors from
 * `tests/fixtures/rg12-runtime-input.json`. The oracle test (Rg12FullStateOracleTest) replays
 * every operation against a pure [Rg12Runtime] and compares field by field with the frozen
 * expected blocks (D-085 oracle contract).
 *
 * Registered fixture-to-runtime projections:
 *
 * 1. All entity ids of the frozen contract are explicit in the frozen states and deltas
 *    (`root-correction-transaction-v2`, `root-correction-set-v2`,
 *    `root-correction-expense-v2` / `-asset-v2` / `-liability-v2`,
 *    `root-correction-replacement-expense` / `-asset` / `-liability`,
 *    `root-correction-confirmation`, `root-correction-match-liability-v1-history-2`,
 *    `root-correction-match-asset-v2` (+ its history-1 entry),
 *    `root-correction-reconciliation-asset-v2` / `-liability-v2`,
 *    `root-correction-consumption-v2`, ...). The runtime consumes them through the per-request
 *    anchors of the runtime-input file (never reverse-read from the expected result states).
 *    The `replacement_postings`-aligned id lists (`posting_ids`, `replacement_link_ids`,
 *    `invalidation_entry_ids`, `new_match_ids`, `new_match_entry_ids`,
 *    `new_match_invalidation_entry_ids`, `reconciliation_fact_ids`) are anchored as JSON arrays
 *    with explicit nulls; null entries mean "no entity of that kind for this replacement".
 *
 * 2. The frozen rejection operations share one `request_id` (`root-rejections-request`). The
 *    runtime rejects a second commit with the same identity and a different fingerprint
 *    (`RequestIdentityConflict`), so the shared rejections are replayed under per-operation
 *    synthesized request ids (`<operation-id>-request`); the frozen `attempted_input.request_id`
 *    is not part of any frozen output state, so the projection is inert (same precedent as the
 *    RG-08 invalid inputs / RG-11 `reject-request`). The accepted `root-correction-correct` and
 *    its idempotent `root-correction-replay` keep the frozen shared request id
 *    `root-correction-request`, so the runtime returns the first-time ids on the replay.
 *
 * 3. Every frozen operation (accepted, replay and the ten rejections) is adapted to the typed
 *    [Rg12Operation.CorrectTransactionVersion] form. The ten rejections replay through the
 *    primary domain chain ([validatePostingFactsCorrection]) which recomputes the frozen reason
 *    code and the exact `$.attempted_input.replacement_postings[i].*` field path; the frozen
 *    numeric amount `110.0` of reject-9 is carried as its raw JSON text `"110.0"` (the domain
 *    exact-decimal check rejects it), the missing `explicit_confirmation` of reject-8 is
 *    carried as `false`, and `rejectChangedMatchedAsset` stays `true` (the frozen rejection
 *    semantics). The correct/replay operations pass `rejectChangedMatchedAsset=false` (the
 *    frozen accepted-path semantics; the fixture asset leg stays unchanged, so no
 *    changed-matched-asset lineage is produced). The rejected paths never consume the ids, so
 *    the adapter synthesizes deterministic per-operation placeholder ids for them.
 *
 * 4. The initial-state seeds (state-partial-00 / state-correction-00 / state-rejections-00)
 *    are rehydrated verbatim from the frozen baseline states: the formal expense transaction
 *    (with its per-version `note` and `created_at` texts), posting semantics (role /
 *    reconciliation_eligible / category_id from the frozen postings), the reconciliation
 *    matches and their `status_history` entries, the `posting_reconciliations` facts, the
 *    `consumption_record` domain entities, the per-record `statisticsAtText` (driving the
 *    report day periods) and the frozen report registry day periods (including the zero-value
 *    correction day `2026-04-20`). Balances, reports, reconciliation summary and derived
 *    statuses are recomputed by the runtime and never stored. The immutable `sources` /
 *    `evidence` / `evidence_links` collections of the initial states are carried as static
 *    seeds ([Rg12StaticSeeds]); no RG-12 action mutates them.
 *
 * 5. The runtime-time anchors (`corrected_at`) are part of the frozen inputs themselves; the
 *    explicit operation confirmation is created at `corrected_at` (frozen
 *    `confirmed_at == 2026-04-20T10:00:00+08:00`), so unlike RG-11 no separate confirmation
 *    time anchor is needed (the `times` section of the runtime input stays empty).
 */
data class Rg12FixtureOperation(
    val id: String,
    val rootId: String,
    val sourcePath: String,
    val expectedStatus: String,
    val operation: Rg12Operation,
    val baselineStateId: String? = null,
    val resultStateId: String? = null,
    val retryOf: String? = null,
    val expectedReason: String? = null,
)

data class Rg12FixtureReplaySummary(
    val operations: List<Rg12FixtureOperation>,
    val accepted: Int,
    val noChange: Int,
    val rejected: Int,
)

/**
 * Runtime anchors of the frozen rg-12 contract. `ids` keeps the per-request id map as raw JSON
 * so the null-padded `replacement_postings`-aligned arrays survive; `times` carries no anchors
 * for rg-12 (the confirmation time is the frozen `corrected_at` input itself).
 */
data class Rg12FixtureInputs(
    val ids: Map<String, JsonObject>,
    val times: Map<String, Map<String, String>>,
)

/**
 * Static immutable collections of a root's initial state that the runtime does not maintain
 * (no RG-12 action mutates them): `sources` / `evidence` / `evidence_links`. `candidates` and
 * `relations` are always empty in the frozen states and projected as empty arrays.
 */
data class Rg12StaticSeeds(
    val sources: JsonArray,
    val evidence: JsonArray,
    val evidenceLinks: JsonArray,
)

data class Rg12FixtureCase(
    val ledgerId: LedgerId,
    /** Root id -> catalog of the root's initial state. */
    val catalogs: Map<String, LedgerCatalog>,
    /** Root id -> rehydrated initial-state snapshot (seeds). */
    val baselines: Map<String, Rg12Snapshot>,
    /** Root id -> initial state id of the frozen contract. */
    val initialStateIds: Map<String, String>,
    val operations: List<Rg12FixtureOperation>,
    val allOperations: List<Rg12FixtureOperation> = operations,
    /** Posting id -> frozen reconciliation record id (seeds + runtime-input anchors). */
    val reconciliationIds: Map<String, String>,
    /** Root id -> immutable sources/evidence/evidence_links seeds of the initial state. */
    val staticSeeds: Map<String, Rg12StaticSeeds>,
)

fun parseRg12FixtureInputs(raw: String): Rg12FixtureInputs {
    val root = Json.parseToJsonElement(raw).jsonObject
    val ids = root.getValue("ids").jsonObject.mapValues { (_, value) -> value.jsonObject }
    val times = root["times"]?.jsonObject?.mapValues { (_, value) ->
        value.jsonObject.mapValues { (_, field) -> field.jsonPrimitive.content }
    } ?: emptyMap()
    return Rg12FixtureInputs(ids, times)
}

fun adaptRg12Fixture(raw: String, inputs: Rg12FixtureInputs): Rg12FixtureCase {
    val fixture = Json.parseToJsonElement(raw).jsonObject
    val case = fixture.getValue("case").jsonObject
    val ledgerId = LedgerId(case.string("ledger_id"))
    val context = Rg12FixtureContext(inputs, ledgerId)
    val states = fixture.getValue("states").jsonArray.map { it.jsonObject }.associateBy { it.string("id") }
    val roots = fixture.getValue("roots").jsonArray.map { it.jsonObject }
    val rawOperations = fixture.getValue("operations").jsonArray.map { it.jsonObject }

    val catalogs = buildMap {
        roots.forEach { root -> put(root.string("id"), buildCatalog(states.getValue(root.string("initial_state_id")), ledgerId)) }
    }
    val baselines = buildMap {
        roots.forEach { root -> put(root.string("id"), buildInitialSnapshot(states.getValue(root.string("initial_state_id")), ledgerId)) }
    }
    val initialStateIds = roots.associate { it.string("id") to it.string("initial_state_id") }
    val staticSeeds = buildMap {
        roots.forEach { root -> put(root.string("id"), buildStaticSeeds(states.getValue(root.string("initial_state_id")))) }
    }
    val reconciliationIds = buildReconciliationIds(states, inputs)

    val firstRequestOwners = mutableMapOf<String, String>()
    val operations = rawOperations.mapIndexed { index, op ->
        // Only accepted inputs own a request id; the shared `root-rejections-request` of the
        // attempted inputs is projected per-op inside [adaptCorrect] and never forms a retry
        // chain (the ten rejections are distinct operations).
        val requestId = op["input"]?.jsonObject?.optionalString("request_id")
        val retryOf = requestId?.let { firstRequestOwners[it] }
        if (requestId != null && retryOf == null) {
            firstRequestOwners[requestId] = op.string("id")
        }
        adaptOperation(op, "$.operations[$index]", context, retryOf)
    }
    return Rg12FixtureCase(
        ledgerId = ledgerId,
        catalogs = catalogs,
        baselines = baselines,
        initialStateIds = initialStateIds,
        operations = operations,
        allOperations = operations,
        reconciliationIds = reconciliationIds,
        staticSeeds = staticSeeds,
    )
}

fun replayRg12Fixture(raw: String, inputs: Rg12FixtureInputs): Rg12FixtureReplaySummary {
    val case = adaptRg12Fixture(raw, inputs)
    return Rg12FixtureReplaySummary(
        operations = case.allOperations,
        accepted = case.allOperations.count { it.expectedStatus == "accepted" },
        noChange = case.allOperations.count { it.expectedStatus == "no_change" },
        rejected = case.allOperations.count { it.expectedStatus == "rejected" },
    )
}

private class Rg12FixtureContext(
    val inputs: Rg12FixtureInputs,
    val ledgerId: LedgerId,
) {
    fun currency(code: String) = CurrencyUnit(code, 2)
}

// ------------------------------------------------------------------ operations

private fun adaptOperation(
    op: JsonObject,
    sourcePath: String,
    context: Rg12FixtureContext,
    retryOf: String?,
): Rg12FixtureOperation {
    val actionType = op.string("action_type")
    require(actionType == "correct_transaction_version") {
        "RG-12 operation $sourcePath has unsupported action type $actionType"
    }
    val input = op["input"]?.jsonObject
    val attempted = op["attempted_input"]?.jsonObject
    val operation: Rg12Operation = when {
        attempted != null -> adaptCorrect(
            attempted,
            context,
            rejectChangedMatchedAsset = true,
            opId = op.string("id"),
            projectedRequestId = rejectionRequestId(op.string("id"), attempted.string("request_id")),
        )
        input != null -> adaptCorrect(
            input,
            context,
            rejectChangedMatchedAsset = false,
            opId = op.string("id"),
            projectedRequestId = input.string("request_id"),
        )
        else -> error("RG-12 operation $sourcePath has neither input nor attempted_input")
    }
    val outcome = op.getValue("outcome").jsonObject
    return Rg12FixtureOperation(
        id = op.string("id"),
        rootId = op.string("root_id"),
        sourcePath = sourcePath,
        expectedStatus = outcome.string("status"),
        operation = operation,
        baselineStateId = op.string("baseline_state_id"),
        resultStateId = op.string("result_state_id"),
        retryOf = retryOf,
        expectedReason = outcome.optionalString("reason_code"),
    )
}

private fun adaptCorrect(
    input: JsonObject,
    context: Rg12FixtureContext,
    rejectChangedMatchedAsset: Boolean,
    opId: String,
    projectedRequestId: String,
): Rg12Operation.CorrectTransactionVersion {
    val correctedAtText = input.string("corrected_at")
    val replacements = input.getValue("replacement_postings").jsonArray.map { item ->
        adaptReplacement(item.jsonObject, context)
    }
    val ids = context.inputs.ids[projectedRequestId]?.let(::anchoredCorrectIds)
        ?: synthesizedCorrectIds(opId, replacements.size)
    return Rg12Operation.CorrectTransactionVersion(
        ledgerId = context.ledgerId,
        input = Rg12CorrectInput(
            requestId = RequestId(projectedRequestId),
            transactionId = TransactionId(input.string("transaction_id")),
            correctionKind = input.string("correction_kind"),
            correctedAt = Instant.parse(correctedAtText),
            correctedAtText = correctedAtText,
            replacementPostings = replacements,
            explicitConfirmation = input.optionalBoolean("explicit_confirmation") ?: false,
            historyMutation = input["history_mutation"]?.jsonObject?.let(::adaptHistoryMutation),
            rejectChangedMatchedAsset = rejectChangedMatchedAsset,
        ),
        ids = ids,
    )
}

private fun adaptReplacement(item: JsonObject, context: Rg12FixtureContext): ReplacementPostingInput {
    val currency = context.currency(item.string("currency"))
    return ReplacementPostingInput(
        sourcePostingId = PostingId(item.string("source_posting_id")),
        facts = PostingFacts(
            accountId = AccountId(item.string("account_id")),
            // The raw JSON text of the amount field: the frozen reject-9 amount is the numeric
            // literal 110.0, whose JSON text "110.0" the domain exact-decimal check rejects.
            amountText = item.string("amount"),
            currency = currency,
            role = item.string("role"),
            categoryId = item.optionalString("category_id")?.let(::CategoryId),
        ),
    )
}

private fun adaptHistoryMutation(input: JsonObject): HistoryMutationInput = HistoryMutationInput(
    matchId = input.string("match_id"),
    statusHistory = input.getValue("status_history").jsonArray.map { entry -> entry.jsonObject }.map { entry ->
        ReconciliationMatchStatusEntry(
            id = entry.string("id"),
            sequence = entry.int("sequence"),
            status = statusOf(entry.string("status")),
            at = Instant.parse(entry.string("at")),
            reason = reasonOf(entry.string("reason")),
        )
    },
)

/** Frozen request ids shared by more than one operation are projected onto per-op ids. */
private fun rejectionRequestId(opId: String, frozenRequestId: String): String =
    if (frozenRequestId == "root-rejections-request") "$opId-request" else frozenRequestId

private fun anchoredCorrectIds(fields: JsonObject): Rg12CorrectIds = Rg12CorrectIds(
    versionId = TransactionVersionId(fields.string("version_id")),
    postingSetId = PostingSetId(fields.string("posting_set_id")),
    postingIds = fields.jsonArray("posting_ids").map { PostingId(it.jsonPrimitive.content) },
    replacementLinkIds = fields.jsonArray("replacement_link_ids").map { it.jsonPrimitive.content },
    confirmationId = fields.string("confirmation_id"),
    operationId = fields.string("operation_id"),
    // `invalidation_entry_ids` is a non-null list in the typed boundary; the frozen nulls of the
    // legs that are never invalidated are projected to the empty-string sentinel. The runtime
    // only consumes the entry of an invalidated real leg, whose anchor is always present.
    invalidationEntryIds = fields.nullableArray("invalidation_entry_ids").map { it ?: "" },
    newMatchIds = fields.nullableArray("new_match_ids"),
    newMatchEntryIds = fields.nullableArray("new_match_entry_ids"),
    newMatchInvalidationEntryIds = fields.nullableArray("new_match_invalidation_entry_ids"),
    reconciliationFactIds = fields.nullableArray("reconciliation_fact_ids"),
    consumptionRecordId = fields.optionalString("consumption_record_id"),
)

/**
 * Deterministic placeholder ids for the rejected operations. The ten frozen rejections fail in
 * the domain chain before any id is consumed, so these ids are inert; they only satisfy the
 * typed boundary with `replacement_postings`-aligned list sizes.
 */
private fun synthesizedCorrectIds(opId: String, count: Int): Rg12CorrectIds = Rg12CorrectIds(
    versionId = TransactionVersionId("$opId-v2"),
    postingSetId = PostingSetId("$opId-set-v2"),
    postingIds = List(count) { index -> PostingId("$opId-replacement-$index") },
    replacementLinkIds = List(count) { index -> "$opId-replacement-link-$index" },
    confirmationId = "$opId-confirmation",
    operationId = opId,
    invalidationEntryIds = List(count) { "" },
    newMatchIds = List(count) { null },
    newMatchEntryIds = List(count) { null },
    newMatchInvalidationEntryIds = List(count) { null },
    reconciliationFactIds = List(count) { null },
    consumptionRecordId = "$opId-consumption-v2",
)

// ------------------------------------------------------------------ initial states

private fun buildInitialSnapshot(state: JsonObject, ledgerId: LedgerId): Rg12Snapshot {
    val transactions = state.getValue("transactions").jsonArray.map { it.jsonObject }
    val versions = state.getValue("transaction_versions").jsonArray.map { it.jsonObject }
    val postingSets = state.getValue("posting_sets").jsonArray.map { it.jsonObject }
    val postings = state.getValue("postings").jsonArray.map { it.jsonObject }
    val domainEntities = state.getValue("domain_entities").jsonArray.map { it.jsonObject }

    val formalRecords = transactions.map { transaction ->
        val transactionId = transaction.string("id")
        val currentVersionId = transaction.string("current_version_id")
        val kind = when (transaction.string("type")) {
            "expense" -> TransactionKind.EXPENSE
            else -> error("unsupported RG-12 seed transaction type ${transaction.string("type")}")
        }
        val transactionVersions = versions
            .filter { it.string("transaction_id") == transactionId }
            .map { version ->
                TransactionVersion(
                    id = TransactionVersionId(version.string("id")),
                    transactionId = TransactionId(transactionId),
                    versionNumber = version.int("version_number"),
                    postingSetId = PostingSetId(version.string("posting_set_id")),
                    times = TransactionTimes(
                        occurredAt = version.instant("occurred_at"),
                        statisticsAt = version.instant("statistics_at"),
                        effectiveAt = version.instant("effective_at"),
                    ),
                    note = version.optionalString("note"),
                )
            }
        val referencedSetIds = transactionVersions.map { it.postingSetId }.toSet()
        val transactionPostingSets = postingSets
            .filter { PostingSetId(it.string("id")) in referencedSetIds }
            .map { postingSet ->
                val setPostings = postings
                    .filter { it.string("posting_set_id") == postingSet.string("id") }
                    .map { posting ->
                        Posting(
                            id = PostingId(posting.string("id")),
                            accountId = AccountId(posting.string("account_id")),
                            amount = posting.money("amount", posting.currencyUnit()),
                        )
                    }
                when (val result = PostingSet.create(PostingSetId(postingSet.string("id")), setPostings)) {
                    is DomainResult.Success -> result.value
                    is DomainResult.Failure -> error("invalid RG-12 seed posting set ${postingSet.string("id")}")
                }
            }
        val formal = when (
            val result = FormalTransaction.create(
                transaction = Transaction(
                    id = TransactionId(transactionId),
                    ledgerId = ledgerId,
                    kind = kind,
                    currentVersionId = TransactionVersionId(currentVersionId),
                ),
                versions = transactionVersions,
                postingSets = transactionPostingSets,
            )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> error("invalid RG-12 seed transaction $transactionId")
        }
        val currentVersion = versions.first { it.string("id") == currentVersionId }
        Rg12FormalTransactionRecord(
            formalTransaction = formal,
            createdAt = Instant.parse(currentVersion.string("created_at")),
            createdAtText = currentVersion.string("created_at"),
            statisticsAtText = currentVersion.string("statistics_at"),
            versionCreatedAtTexts = transactionVersions.associate { version ->
                version.id to versions.first { it.string("id") == version.id.value }.string("created_at")
            },
            versionConfirmationIds = emptyMap(),
        )
    }

    val semantics = postings.associate { posting ->
        posting.string("id") to Rg12PostingSemantic(
            role = posting.optionalString("role"),
            reconciliationEligible = posting.boolean("reconciliation_eligible"),
            categoryId = posting.optionalString("category_id")?.let(::CategoryId),
        )
    }
    val matches = domainEntities
        .filter { it.string("type") == "reconciliation_match" }
        .map { entity -> rehydrateMatch(entity) }
    val consumptionRecords = domainEntities
        .filter { it.string("type") == "consumption_record" }
        .map { entity ->
            val payload = entity.getValue("payload").jsonObject
            Rg12ConsumptionRecord(
                id = entity.string("id"),
                expensePostingId = PostingId(payload.string("expense_posting_id")),
                categoryId = payload.optionalString("category_id")?.let(::CategoryId),
                amountText = payload.string("amount"),
                currency = payload.currencyUnit(),
                statisticsAtText = payload.string("statistics_at"),
            )
        }
    val reconciliations = state["posting_reconciliations"]?.jsonArray?.map { element ->
        val record = element.jsonObject
        when (
            val result = createPostingReconciliation(
                id = record.string("id"),
                postingId = PostingId(record.string("posting_id")),
                status = when (record.string("status")) {
                    "matched" -> PostingReconciliationStatus.MATCHED
                    "pending" -> PostingReconciliationStatus.PENDING
                    else -> error("unsupported RG-12 seed reconciliation status ${record.string("status")}")
                },
            )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> error("invalid RG-12 seed reconciliation record ${record.string("id")}")
        }
    } ?: emptyList()
    val reportPeriods = state.getValue("reports").jsonArray.map { report ->
        report.jsonObject.string("period")
    }.distinct()

    return Rg12Snapshot(
        formalTransactions = formalRecords,
        postingSemantics = semantics,
        reconciliationMatches = matches,
        postingReconciliations = reconciliations,
        postingReplacements = emptyList(),
        confirmations = emptyList(),
        consumptionRecords = consumptionRecords,
        domainEntities = emptyList(),
        reconciliationSummary = emptyMap(),
        balances = emptyMap(),
        reports = emptyMap(),
        reportPeriods = reportPeriods,
    )
}

private fun rehydrateMatch(entity: JsonObject): ReconciliationMatch {
    val payload = entity.getValue("payload").jsonObject
    val history = payload.getValue("status_history").jsonArray.map { element ->
        val entry = element.jsonObject
        ReconciliationMatchStatusEntry(
            id = entry.string("id"),
            sequence = entry.int("sequence"),
            status = statusOf(entry.string("status")),
            at = Instant.parse(entry.string("at")),
            reason = reasonOf(entry.string("reason")),
        )
    }
    return when (
        val result = createReconciliationMatch(
            id = entity.string("id"),
            postingId = PostingId(payload.string("posting_id")),
            evidenceId = payload.string("evidence_id"),
            statusHistory = history,
        )
    ) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> error("invalid RG-12 seed reconciliation match ${entity.string("id")}")
    }
}

private fun buildCatalog(state: JsonObject, ledgerId: LedgerId): LedgerCatalog {
    val catalog = state.getValue("catalog").jsonObject
    val accounts = catalog.getValue("accounts").jsonArray.map { element ->
        val account = element.jsonObject
        Account(
            id = AccountId(account.string("id")),
            ledgerId = ledgerId,
            kind = when (account.string("kind")) {
                "asset" -> AccountKind.ASSET
                "liability" -> AccountKind.LIABILITY
                "equity" -> AccountKind.EQUITY
                "income" -> AccountKind.INCOME
                "expense" -> AccountKind.EXPENSE
                else -> error("unsupported RG-12 account kind ${account.string("kind")}")
            },
            currency = CurrencyUnit(account.string("currency"), 2),
            ownedByUser = account.boolean("owned_by_user"),
            realAccount = account.boolean("real_account"),
        )
    }
    val categories = catalog.getValue("categories").jsonArray.map { element ->
        val category = element.jsonObject
        Category(
            id = CategoryId(category.string("id")),
            ledgerId = ledgerId,
            parentId = category.optionalString("parent_id")?.let(::CategoryId),
            postingAccountId = category.optionalString("posting_account_id")?.let(::AccountId),
            active = category.boolean("active"),
        )
    }
    return when (val result = LedgerCatalog.create(accounts, categories)) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> error("invalid RG-12 catalog")
    }
}

private fun buildStaticSeeds(state: JsonObject): Rg12StaticSeeds = Rg12StaticSeeds(
    sources = state.getValue("sources").jsonArray,
    evidence = state.getValue("evidence").jsonArray,
    evidenceLinks = state.getValue("evidence_links").jsonArray,
)

private fun buildReconciliationIds(
    states: Map<String, JsonObject>,
    inputs: Rg12FixtureInputs,
): Map<String, String> = buildMap {
    states.values.forEach { state ->
        state["posting_reconciliations"]?.jsonArray?.forEach { element ->
            val record = element.jsonObject
            put(record.string("posting_id"), record.string("id"))
        }
    }
    inputs.ids.forEach { (_, fields) ->
        val postingIds = fields["posting_ids"]?.jsonArray ?: return@forEach
        val factIds = fields["reconciliation_fact_ids"]?.jsonArray ?: return@forEach
        postingIds.zip(factIds).forEach { (postingId, factId) ->
            val fact = factId.takeUnless { it is JsonNull }?.jsonPrimitive?.content ?: return@forEach
            put(postingId.jsonPrimitive.content, fact)
        }
    }
}

// ------------------------------------------------------------------ helpers

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.optionalString(key: String): String? =
    this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

private fun JsonObject.optionalBoolean(key: String): Boolean? =
    this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.toBooleanStrictOrNull()

private fun JsonObject.boolean(key: String): Boolean = getValue(key).jsonPrimitive.content.toBooleanStrict()

private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.content.toInt()

private fun JsonObject.instant(key: String): Instant = Instant.parse(string(key))

private fun JsonObject.money(key: String, currency: CurrencyUnit): com.unifiedledger.domain.Money {
    val text = string(key)
    require(text.matches(Regex("[+-]?\\d+\\.\\d{2}"))) { "RG-12 requires exact two-place decimal: $text" }
    val negative = text.startsWith("-")
    val unsigned = text.removePrefix("+").removePrefix("-")
    val parts = unsigned.split('.')
    val major = parts[0].toLongOrNull() ?: error("RG-12 amount exceeds minor-unit range")
    val fraction = parts[1].toLongOrNull() ?: error("RG-12 amount exceeds minor-unit range")
    val minor = checkedRg12Add(checkedRg12Multiply(major, 100L), fraction)
        ?: error("RG-12 amount exceeds minor-unit range")
    val signedMinor = if (negative) checkedRg12Negate(minor) ?: error("RG-12 amount exceeds minor-unit range") else minor
    return com.unifiedledger.domain.Money.ofMinor(signedMinor, currency)
}

private fun JsonObject.currencyUnit(): CurrencyUnit = CurrencyUnit(string("currency"), 2)

private fun JsonObject.jsonArray(key: String): JsonArray = getValue(key).jsonArray

private fun JsonObject.nullableArray(key: String): List<String?> =
    jsonArray(key).map { element ->
        element.takeUnless { it is JsonNull }?.jsonPrimitive?.content
    }

private fun statusOf(value: String): ReconciliationMatchStatus = when (value) {
    "matched" -> ReconciliationMatchStatus.MATCHED
    "invalidated" -> ReconciliationMatchStatus.INVALIDATED
    else -> error("unsupported RG-12 seed match status $value")
}

private fun reasonOf(value: String): ReconciliationMatchReason = when (value) {
    "exact_evidence" -> ReconciliationMatchReason.EXACT_EVIDENCE
    "posting_replaced" -> ReconciliationMatchReason.POSTING_REPLACED
    else -> error("unsupported RG-12 seed match reason $value")
}

private fun checkedRg12Multiply(left: Long, right: Long): Long? {
    if (left == 0L || right == 0L) return 0L
    if (left == Long.MIN_VALUE && right == -1L) return null
    if (right == Long.MIN_VALUE && left == -1L) return null
    val result = left * right
    return if (result / right == left) result else null
}

private fun checkedRg12Add(left: Long?, right: Long): Long? {
    left ?: return null
    if (right > 0L && left > Long.MAX_VALUE - right) return null
    if (right < 0L && left < Long.MIN_VALUE - right) return null
    return left + right
}

private fun checkedRg12Negate(value: Long): Long? = if (value == Long.MIN_VALUE) null else -value
