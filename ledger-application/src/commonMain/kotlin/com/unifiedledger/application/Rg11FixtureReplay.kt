package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PeriodicAllocationAnchor
import com.unifiedledger.domain.PeriodicAllocationCadence
import com.unifiedledger.domain.PeriodicAllocationInstallment
import com.unifiedledger.domain.PeriodicAllocationRevision
import com.unifiedledger.domain.PeriodicAllocationSchedule
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSet
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.Transaction
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersion
import com.unifiedledger.domain.TransactionVersionId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

/**
 * D-085 RG-11 fixture replay (shard 4 of the RG-11 runtime): derives the 22-operation plan
 * (accepted 11 / no_change 1 / rejected 10; root-main 6, root-revision 6, root-z-rejections 10)
 * directly from the frozen direct-v2 contract `golden/rules/rg-11.json`, builds the per-root
 * catalogs and the three initial-state baselines (the rejection root's baseline carries the
 * three seed transactions opening + purchase + recognition-01), and loads the runtime anchors
 * from `tests/fixtures/rg11-runtime-input.json`. The oracle test (Rg11FullStateOracleTest)
 * replays every operation against a pure [Rg11Runtime] and compares field by field with the
 * frozen expected blocks (D-085 oracle contract).
 *
 * Registered fixture-to-runtime projections:
 *
 * 1. All entity ids of the frozen contract are explicit in the frozen states and deltas
 *    (`main-purchase`, `main-purchase-v1`, `main-purchase-postings`, `main-purchase-cash`,
 *    `main-purchase-prepaid`, `schedule-main`, `main-revision-01`, `main-installment-01..03`,
 *    `main-recognition-link-01`, `main-correct-confirmation`, ...). The runtime consumes them
 *    through the per-request anchors of the runtime-input file (never reverse-read from the
 *    expected result states). `installment_ids` arrays are anchored as JSON arrays.
 *
 * 2. Reconciliation ids follow no uniform derivation rule in the frozen contract
 *    (`main-opening-reconciliation` drops the `-cash` suffix, `main-purchase-cash-reconciliation`
 *    keeps it), so the fixture case exposes a postingId -> reconciliationId map assembled from
 *    the initial states' `posting_reconciliations` (input-side seeds) plus the per-request
 *    `reconciliation_id` anchors of the runtime-input file (created postings).
 *
 * 3. The frozen `correct_transaction_version` confirmation owns the operation id itself
 *    (`operation_id == operation["id"]`, i.e. `main-correct`, not the request id) and the frozen
 *    input carries no confirmation time; the runtime-input `times` section supplies the
 *    `confirmation_created_at` anchor. The operation id is taken from the contract operation id
 *    (the runtime-input `operation_id` anchor mirrors it).
 *
 * 4. The six create-branch rejections of the frozen contract share one `request_id`
 *    (`reject-request`). The runtime rejects a second commit with the same identity and a
 *    different fingerprint (`RequestIdentityConflict`), so the shared rejections are replayed
 *    under per-operation synthesized request ids (`<operation-id>-request`); the frozen
 *    `attempted_input.request_id` is not part of any frozen output state, so the projection is
 *    inert (same precedent as the RG-08 invalid inputs). The four remaining rejections keep
 *    their frozen unique request ids.
 *
 * 5. The initial-state seeds (state-main-00 / state-revision-00 / state-rejection-00) are
 *    rehydrated verbatim from the frozen baseline states: formal transactions (opening /
 *    purchase / recognition kinds with their frozen times), schedules / revisions /
 *    installments, audit links, posting semantics (role / reconciliation_eligible /
 *    category_id from the frozen postings) and the per-record `statisticsAtText` (the frozen
 *    `statistics_at` text driving the report day periods). Balances, reports, reconciliation
 *    and derived statuses are recomputed by the runtime and never stored.
 */
data class Rg11FixtureOperation(
    val id: String,
    val rootId: String,
    val sourcePath: String,
    val expectedStatus: String,
    val operation: Rg11Operation,
    val baselineStateId: String? = null,
    val resultStateId: String? = null,
    val retryOf: String? = null,
    val expectedReason: String? = null,
)

data class Rg11FixtureReplaySummary(
    val operations: List<Rg11FixtureOperation>,
    val accepted: Int,
    val noChange: Int,
    val rejected: Int,
)

/**
 * Runtime anchors of the frozen rg-11 contract. `ids` keeps the per-request id map as raw JSON
 * so the `installment_ids` arrays survive; `times` carries the confirmation-time anchors.
 */
data class Rg11FixtureInputs(
    val ids: Map<String, JsonObject>,
    val times: Map<String, Map<String, String>>,
)

data class Rg11FixtureCase(
    val ledgerId: LedgerId,
    /** Root id -> catalog of the root's initial state (main and rejection roots share it). */
    val catalogs: Map<String, LedgerCatalog>,
    /** Root id -> rehydrated initial-state snapshot (seeds). */
    val baselines: Map<String, Rg11Snapshot>,
    /** Root id -> initial state id of the frozen contract. */
    val initialStateIds: Map<String, String>,
    val operations: List<Rg11FixtureOperation>,
    val allOperations: List<Rg11FixtureOperation> = operations,
    /** Posting id -> frozen reconciliation record id (seeds + runtime-input anchors). */
    val reconciliationIds: Map<String, String>,
)

fun parseRg11FixtureInputs(raw: String): Rg11FixtureInputs {
    val root = Json.parseToJsonElement(raw).jsonObject
    val ids = root.getValue("ids").jsonObject.mapValues { (_, value) -> value.jsonObject }
    val times = root["times"]?.jsonObject?.mapValues { (_, value) ->
        value.jsonObject.mapValues { (_, field) -> field.jsonPrimitive.content }
    } ?: emptyMap()
    return Rg11FixtureInputs(ids, times)
}

fun adaptRg11Fixture(raw: String, inputs: Rg11FixtureInputs): Rg11FixtureCase {
    val fixture = Json.parseToJsonElement(raw).jsonObject
    val case = fixture.getValue("case").jsonObject
    val ledgerId = LedgerId(case.string("ledger_id"))
    val context = Rg11FixtureContext(inputs, ledgerId)
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
    val reconciliationIds = buildReconciliationIds(states, inputs)

    val firstRequestOwners = mutableMapOf<String, String>()
    val operations = rawOperations.mapIndexed { index, op ->
        val requestId = op["input"]?.jsonObject?.optionalString("request_id")
        val retryOf = requestId?.let { firstRequestOwners[it] }
        if (requestId != null && retryOf == null) {
            firstRequestOwners[requestId] = op.string("id")
        }
        adaptOperation(op, "$.operations[$index]", context, retryOf)
    }
    return Rg11FixtureCase(
        ledgerId = ledgerId,
        catalogs = catalogs,
        baselines = baselines,
        initialStateIds = initialStateIds,
        operations = operations,
        allOperations = operations,
        reconciliationIds = reconciliationIds,
    )
}

fun replayRg11Fixture(raw: String, inputs: Rg11FixtureInputs): Rg11FixtureReplaySummary {
    val case = adaptRg11Fixture(raw, inputs)
    return Rg11FixtureReplaySummary(
        operations = case.allOperations,
        accepted = case.allOperations.count { it.expectedStatus == "accepted" },
        noChange = case.allOperations.count { it.expectedStatus == "no_change" },
        rejected = case.allOperations.count { it.expectedStatus == "rejected" },
    )
}

private class Rg11FixtureContext(
    val inputs: Rg11FixtureInputs,
    val ledgerId: LedgerId,
) {
    fun id(requestId: String, field: String): String =
        inputs.ids.getValue(requestId)[field]?.jsonPrimitive?.content
            ?: error("RG-11 runtime input ID is null: $requestId.$field")

    fun idList(requestId: String, field: String): List<String> =
        inputs.ids.getValue(requestId)[field]?.jsonArray?.map { it.jsonPrimitive.content }
            ?: error("RG-11 runtime input ID list is null: $requestId.$field")

    fun time(requestId: String, field: String): String? =
        inputs.times[requestId]?.get(field)

    fun currency(code: String) = CurrencyUnit(code, 2)
}

private fun adaptOperation(
    op: JsonObject,
    sourcePath: String,
    context: Rg11FixtureContext,
    retryOf: String?,
): Rg11FixtureOperation {
    val actionType = op.string("action_type")
    val input = op["input"]?.jsonObject
    val attempted = op["attempted_input"]?.jsonObject
    val operation: Rg11Operation = when {
        attempted != null -> adaptRejection(op, attempted, actionType, context)
        input == null -> error("RG-11 operation $sourcePath has neither input nor attempted_input")
        else -> when (actionType) {
            "create_periodic_allocation" -> adaptCreate(input, context)
            "recognize_periodic_allocation_installment" -> adaptRecognize(input, context)
            "revise_periodic_allocation" -> adaptRevise(input, context)
            "correct_transaction_version" -> adaptCorrect(input, context)
            else -> error("unsupported RG-11 action type $actionType at $sourcePath")
        }
    }
    val outcome = op.getValue("outcome").jsonObject
    return Rg11FixtureOperation(
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

private fun adaptCreate(input: JsonObject, context: Rg11FixtureContext): Rg11Operation.CreatePeriodicAllocation {
    val requestId = input.string("request_id")
    val currency = context.currency(input.string("currency"))
    return Rg11Operation.CreatePeriodicAllocation(
        ledgerId = context.ledgerId,
        input = Rg11CreateInput(
            requestId = RequestId(requestId),
            paymentAccountId = AccountId(input.string("payment_account_id")),
            prepaidAccountId = AccountId(input.string("prepaid_account_id")),
            categoryId = CategoryId(input.string("category_id")),
            amount = input.money("amount", currency),
            currency = currency,
            startAt = input.instant("start_at"),
            startAtText = input.string("start_at"),
            anchor = input.anchor(),
            cadence = PeriodicAllocationCadence.MONTHLY,
            explicitConfirmation = input.boolean("explicit_confirmation"),
            occurredAt = input.instant("occurred_at"),
            occurredAtText = input.string("occurred_at"),
            installmentCount = input.int("installment_count"),
        ),
        ids = Rg11CreateIds(
            transactionId = TransactionId(context.id(requestId, "transaction_id")),
            versionId = TransactionVersionId(context.id(requestId, "version_id")),
            postingSetId = PostingSetId(context.id(requestId, "posting_set_id")),
            paymentPostingId = PostingId(context.id(requestId, "payment_posting_id")),
            prepaidPostingId = PostingId(context.id(requestId, "prepaid_posting_id")),
            scheduleId = context.id(requestId, "schedule_id"),
            revisionId = context.id(requestId, "revision_id"),
            installmentIds = context.idList(requestId, "installment_ids"),
        ),
    )
}

private fun adaptRecognize(input: JsonObject, context: Rg11FixtureContext): Rg11Operation.RecognizePeriodicAllocationInstallment {
    val requestId = input.string("request_id")
    val currency = context.currency(input.string("currency"))
    return Rg11Operation.RecognizePeriodicAllocationInstallment(
        ledgerId = context.ledgerId,
        input = Rg11RecognizeInput(
            requestId = RequestId(requestId),
            scheduleId = input.string("schedule_id"),
            installmentId = input.string("installment_id"),
            amount = input.money("amount", currency),
            currency = currency,
            explicitConfirmation = input.boolean("explicit_confirmation"),
        ),
        ids = Rg11RecognizeIds(
            transactionId = TransactionId(context.id(requestId, "transaction_id")),
            versionId = TransactionVersionId(context.id(requestId, "version_id")),
            postingSetId = PostingSetId(context.id(requestId, "posting_set_id")),
            expensePostingId = PostingId(context.id(requestId, "expense_posting_id")),
            prepaidPostingId = PostingId(context.id(requestId, "prepaid_posting_id")),
            auditLinkId = context.id(requestId, "audit_link_id"),
        ),
    )
}

private fun adaptRevise(input: JsonObject, context: Rg11FixtureContext): Rg11Operation.RevisePeriodicAllocation {
    val requestId = input.string("request_id")
    val currency = context.currency(input.string("currency"))
    return Rg11Operation.RevisePeriodicAllocation(
        ledgerId = context.ledgerId,
        input = Rg11ReviseInput(
            requestId = RequestId(requestId),
            scheduleId = input.string("schedule_id"),
            recognizedThrough = input.string("recognized_through"),
            remainingAmount = input.money("remaining_amount", currency),
            currency = currency,
            explicitConfirmation = input.boolean("explicit_confirmation"),
            remainingInstallmentCount = input.int("remaining_installment_count"),
        ),
        ids = Rg11ReviseIds(
            revisionId = context.id(requestId, "revision_id"),
            installmentIds = context.idList(requestId, "installment_ids"),
        ),
    )
}

private fun adaptCorrect(input: JsonObject, context: Rg11FixtureContext): Rg11Operation.CorrectTransactionVersion {
    val requestId = input.string("request_id")
    val confirmationCreatedAtText = context.time(requestId, "confirmation_created_at")
        ?: error("RG-11 correct op $requestId has no confirmation_created_at anchor")
    return Rg11Operation.CorrectTransactionVersion(
        ledgerId = context.ledgerId,
        input = Rg11CorrectInput(
            requestId = RequestId(requestId),
            transactionId = TransactionId(input.string("transaction_id")),
            correctionKind = input.string("correction_kind"),
            statisticsAt = input.instant("statistics_at"),
            statisticsAtText = input.string("statistics_at"),
            explicitConfirmation = input.boolean("explicit_confirmation"),
        ),
        ids = Rg11CorrectIds(
            versionId = TransactionVersionId(context.id(requestId, "version_id")),
            confirmationId = context.id(requestId, "confirmation_id"),
            operationId = context.id(requestId, "operation_id"),
            confirmationCreatedAt = Instant.parse(confirmationCreatedAtText),
            confirmationCreatedAtText = confirmationCreatedAtText,
        ),
    )
}

private fun adaptRejection(
    op: JsonObject,
    attempted: JsonObject,
    actionType: String,
    context: Rg11FixtureContext,
): Rg11Operation.InvalidInput {
    val opId = op.string("id")
    return Rg11Operation.InvalidInput(
        ledgerId = context.ledgerId,
        input = Rg11InvalidInput(
            requestId = RequestId(rejectionRequestId(opId, attempted.string("request_id"))),
            action = when (actionType) {
                "create_periodic_allocation" -> Rg11Action.CREATE_PERIODIC_ALLOCATION
                "recognize_periodic_allocation_installment" -> Rg11Action.RECOGNIZE_PERIODIC_ALLOCATION_INSTALLMENT
                "revise_periodic_allocation" -> Rg11Action.REVISE_PERIODIC_ALLOCATION
                else -> error("unsupported RG-11 rejection action $actionType at ${op.string("id")}")
            },
            predicate = rejectionPredicate(opId),
            attemptedInput = attempted.mapValues { (_, value) ->
                when (value) {
                    is JsonNull -> null
                    is JsonObject -> value.toString()
                    else -> value.jsonPrimitive.content
                }
            },
        ),
    )
}

/** Frozen request ids shared by more than one rejection are projected onto per-op ids. */
private fun rejectionRequestId(opId: String, frozenRequestId: String): String =
    if (frozenRequestId == "reject-request") "$opId-request" else frozenRequestId

private fun rejectionPredicate(opId: String): Rg11InvalidPredicate = when (opId) {
    "operation-reject-malformed-amount" -> Rg11InvalidPredicate.EXACT_DECIMAL_AMOUNT
    "operation-reject-zero-amount" -> Rg11InvalidPredicate.ZERO_OR_NEGATIVE_AMOUNT
    "operation-reject-negative-amount" -> Rg11InvalidPredicate.ZERO_OR_NEGATIVE_AMOUNT
    "operation-reject-unsupported-currency" -> Rg11InvalidPredicate.UNSUPPORTED_CURRENCY
    "operation-reject-mismatched-currency" -> Rg11InvalidPredicate.CURRENCY_MISMATCH
    "operation-reject-invalid-anchor" -> Rg11InvalidPredicate.INVALID_ANCHOR
    "operation-reject-already-recognized" -> Rg11InvalidPredicate.INSTALLMENT_NOT_PENDING
    "operation-reject-exceeds-prepaid" -> Rg11InvalidPredicate.EXCEEDS_REMAINING_PREPAID
    "operation-reject-invalid-boundary" -> Rg11InvalidPredicate.INVALID_REVISION_BOUNDARY
    "operation-reject-invalid-count" -> Rg11InvalidPredicate.INVALID_REMAINING_INSTALLMENT_COUNT
    else -> error("unsupported RG-11 rejection op $opId")
}

// ------------------------------------------------------------------ initial states

private fun buildInitialSnapshot(state: JsonObject, ledgerId: LedgerId): Rg11Snapshot {
    val transactions = state.getValue("transactions").jsonArray.map { it.jsonObject }
    val versions = state.getValue("transaction_versions").jsonArray.map { it.jsonObject }
    val postingSets = state.getValue("posting_sets").jsonArray.map { it.jsonObject }
    val postings = state.getValue("postings").jsonArray.map { it.jsonObject }
    val domainEntities = state.getValue("domain_entities").jsonArray.map { it.jsonObject }
    val auditLinks = state.getValue("audit_links").jsonArray.map { it.jsonObject }

    val formalRecords = transactions.map { transaction ->
        val transactionId = transaction.string("id")
        val currentVersionId = transaction.string("current_version_id")
        val kind = when (transaction.string("type")) {
            "opening_balance" -> TransactionKind.OPENING_BALANCE
            "prepaid_purchase" -> TransactionKind.PREPAID_PURCHASE
            "prepaid_recognition" -> TransactionKind.PREPAID_RECOGNITION
            else -> error("unsupported RG-11 seed transaction type ${transaction.string("type")}")
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
                )
            }
        val referencedSetIds = transactionVersions.map { it.postingSetId }.toSet()
        val transactionPostingSets = postingSets
            .filter { PostingSetId(it.string("id")) in referencedSetIds }
            .map { postingSet ->
                val setPostings = postings
                    .filter { it.string("posting_set_id") == postingSet.string("id") }
                    .map { posting ->
                        val currency = currencyOf(posting.string("currency"))
                        Posting(
                            id = PostingId(posting.string("id")),
                            accountId = AccountId(posting.string("account_id")),
                            amount = posting.money("amount", currency),
                        )
                    }
                when (val result = PostingSet.create(PostingSetId(postingSet.string("id")), setPostings)) {
                    is DomainResult.Success -> result.value
                    is DomainResult.Failure -> error("invalid RG-11 seed posting set ${postingSet.string("id")}")
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
            is DomainResult.Failure -> error("invalid RG-11 seed transaction $transactionId")
        }
        val currentVersion = versions.first { it.string("id") == currentVersionId }
        Rg11FormalTransactionRecord(
            formal,
            createdAt = formal.versions.last().times.statisticsAt,
            statisticsAtText = currentVersion.string("statistics_at"),
        )
    }

    val schedules = buildList {
        domainEntities.filter { it.string("type") == "periodic_allocation_schedule" }.forEach { entity ->
            val payload = entity.getValue("payload").jsonObject
            val currency = currencyOf(payload.string("currency"))
            add(
                PeriodicAllocationSchedule(
                    id = entity.string("id"),
                    paymentTransactionId = TransactionId(payload.string("payment_transaction_id")),
                    prepaidAccountId = AccountId(payload.string("prepaid_account_id")),
                    categoryId = CategoryId(payload.string("category_id")),
                    totalAmountMinor = payload.money("total_amount", currency).minorUnits,
                    currency = currency,
                    cadence = PeriodicAllocationCadence.MONTHLY,
                    startAt = payload.instant("start_at"),
                    anchor = payload.anchor(),
                ),
            )
        }
    }
    val revisions = buildList {
        domainEntities.filter { it.string("type") == "periodic_allocation_revision" }.forEach { entity ->
            val payload = entity.getValue("payload").jsonObject
            val currency = currencyOf(payload.string("currency"))
            add(
                PeriodicAllocationRevision(
                    id = entity.string("id"),
                    scheduleId = payload.string("schedule_id"),
                    revisionNumber = payload.int("revision_number"),
                    recognizedThrough = payload.optionalString("recognized_through"),
                    remainingAmountMinor = payload.money("remaining_amount", currency).minorUnits,
                    currency = currency,
                    installmentIds = payload.getValue("installment_ids").jsonArray.map { it.jsonPrimitive.content },
                ),
            )
        }
    }
    val installments = buildList {
        domainEntities.filter { it.string("type") == "periodic_allocation_installment" }.forEach { entity ->
            val payload = entity.getValue("payload").jsonObject
            val currency = currencyOf(payload.string("currency"))
            add(
                PeriodicAllocationInstallment(
                    id = entity.string("id"),
                    scheduleId = payload.string("schedule_id"),
                    revisionId = payload.string("revision_id"),
                    sequence = payload.int("sequence"),
                    scheduledAt = payload.instant("scheduled_at"),
                    amountMinor = payload.money("amount", currency).minorUnits,
                    currency = currency,
                ),
            )
        }
    }
    val rehydratedAuditLinks = auditLinks.map { link ->
        val from = link.getValue("from").jsonObject
        val to = link.getValue("to").jsonObject
        Rg11AuditLink(
            id = link.string("id"),
            linkType = link.string("type"),
            fromKind = from.string("kind"),
            fromId = from.string("id"),
            toKind = to.string("kind"),
            toId = to.string("id"),
        )
    }
    val semantics = postings.associate { posting ->
        posting.string("id") to Rg11PostingSemantic(
            role = posting.optionalString("role"),
            reconciliationEligible = posting.boolean("reconciliation_eligible"),
            categoryId = posting.optionalString("category_id"),
        )
    }
    return Rg11Snapshot(
        formalTransactions = formalRecords,
        schedules = schedules,
        revisions = revisions,
        installments = installments,
        confirmations = emptyList(),
        auditLinks = rehydratedAuditLinks,
        postingSemantics = semantics,
        balances = emptyMap(),
        reports = emptyMap(),
        reconciliation = emptyMap(),
        derivedStatuses = emptyList(),
    )
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
                else -> error("unsupported RG-11 account kind ${account.string("kind")}")
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
        is DomainResult.Failure -> error("invalid RG-11 catalog")
    }
}

private fun buildReconciliationIds(
    states: Map<String, JsonObject>,
    inputs: Rg11FixtureInputs,
): Map<String, String> = buildMap {
    states.values.forEach { state ->
        state["posting_reconciliations"]?.jsonArray?.forEach { element ->
            val record = element.jsonObject
            put(record.string("posting_id"), record.string("id"))
        }
    }
    inputs.ids.forEach { (_, fields) ->
        val postingId = fields["payment_posting_id"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
        val reconciliationId = fields["reconciliation_id"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
        if (postingId != null && reconciliationId != null) {
            put(postingId, reconciliationId)
        }
    }
}

// ------------------------------------------------------------------ helpers

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.optionalString(key: String): String? =
    this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

private fun JsonObject.boolean(key: String): Boolean = getValue(key).jsonPrimitive.content.toBooleanStrict()

private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.content.toInt()

private fun JsonObject.instant(key: String): Instant = Instant.parse(string(key))

private fun JsonObject.money(key: String, currency: CurrencyUnit): Money = string(key).toExactMoney(currency)

private fun JsonObject.anchor(): PeriodicAllocationAnchor {
    val anchor = getValue("anchor").jsonObject
    return when (anchor.string("type")) {
        "month_end" -> PeriodicAllocationAnchor.MonthEnd
        "day_of_month" -> PeriodicAllocationAnchor.DayOfMonth(anchor.int("day"))
        else -> error("unsupported RG-11 anchor type ${anchor.string("type")}")
    }
}

private fun currencyOf(code: String): CurrencyUnit = CurrencyUnit(code, 2)

private fun String.toExactMoney(currency: CurrencyUnit): Money {
    require(matches(Regex("[+-]?\\d+\\.\\d{2}"))) { "RG-11 requires exact two-place decimal: $this" }
    val negative = startsWith("-")
    val unsigned = removePrefix("+").removePrefix("-")
    val parts = unsigned.split('.')
    val major = parts[0].toLongOrNull() ?: error("RG-11 amount exceeds minor-unit range")
    val fraction = parts[1].toLongOrNull() ?: error("RG-11 amount exceeds minor-unit range")
    val minor = checkedRg11Add(checkedRg11Multiply(major, 100L), fraction)
        ?: error("RG-11 amount exceeds minor-unit range")
    val signedMinor = if (negative) {
        checkedRg11Negate(minor) ?: error("RG-11 amount exceeds minor-unit range")
    } else {
        minor
    }
    return Money.ofMinor(signedMinor, currency)
}

private fun checkedRg11Multiply(left: Long, right: Long): Long? {
    if (left == 0L || right == 0L) return 0L
    if (left == Long.MIN_VALUE && right == -1L) return null
    if (right == Long.MIN_VALUE && left == -1L) return null
    val result = left * right
    return if (result / right == left) result else null
}

private fun checkedRg11Add(left: Long?, right: Long): Long? {
    left ?: return null
    if (right > 0L && left > Long.MAX_VALUE - right) return null
    if (right < 0L && left < Long.MIN_VALUE - right) return null
    return left + right
}

private fun checkedRg11Negate(value: Long): Long? = if (value == Long.MIN_VALUE) null else -value
