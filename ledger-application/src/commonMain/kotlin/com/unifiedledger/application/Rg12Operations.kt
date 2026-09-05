package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CorrectTransactionVersionViolation
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.ExplicitOperationConfirmation
import com.unifiedledger.domain.ExplicitOperationConfirmationViolation
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.HistoryMutationInput
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingFacts
import com.unifiedledger.domain.PostingFactsCorrectionAttempt
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingReconciliation
import com.unifiedledger.domain.PostingReconciliationStatus
import com.unifiedledger.domain.PostingReconciliationViolation
import com.unifiedledger.domain.PostingReplacement
import com.unifiedledger.domain.PostingReplacementViolation
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.ReconciliationEffect
import com.unifiedledger.domain.ReconciliationMatch
import com.unifiedledger.domain.ReconciliationMatchReason
import com.unifiedledger.domain.ReconciliationMatchStatus
import com.unifiedledger.domain.ReconciliationMatchStatusEntry
import com.unifiedledger.domain.ReconciliationMatchViolation
import com.unifiedledger.domain.ReconciliationSummary
import com.unifiedledger.domain.ReplacementPostingInput
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionAppendIds
import com.unifiedledger.domain.TransactionVersionChange
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.appendVersion
import com.unifiedledger.domain.createExplicitOperationConfirmation
import com.unifiedledger.domain.createPostingReconciliation
import com.unifiedledger.domain.createPostingReplacement
import com.unifiedledger.domain.createReconciliationMatch
import com.unifiedledger.domain.deriveReconciliationSummary
import com.unifiedledger.domain.invalidateReconciliationMatch
import com.unifiedledger.domain.parseExactDecimal
import com.unifiedledger.domain.pow10Exact
import com.unifiedledger.domain.replacementPostingReconciliationStatus
import com.unifiedledger.domain.validatePostingFactsCorrection
import kotlin.time.Instant

/**
 * D-085 RG-12 reconciliation correction application layer (shard 2 of the RG-12 runtime).
 *
 * Executes the single closed action of the frozen direct-v2 contract
 * `golden/rules/rg-12.json` (contract_version 2.0.0): `correct_transaction_version` with
 * `correction_kind == "posting_facts"` (12 operations: root-correction 2 = accepted correct +
 * no_change replay; root-rejections 10). It mirrors the Rg11Operations.kt pattern: typed
 * operation boundary, deterministic fingerprints, idempotent replay receipts, zero-effect
 * rejections and a full [Rg12Snapshot] of the executable state.
 *
 * The whole execution is driven by the shard-1 domain chain ([validatePostingFactsCorrection],
 * [createReconciliationMatch] / [invalidateReconciliationMatch], [createPostingReplacement],
 * [createPostingReconciliation], [createExplicitOperationConfirmation]) and the shared
 * [FormalTransaction.appendVersion] primitive with the [TransactionVersionChange.Postings]
 * form (fresh posting set, full replacement postings). Rejection reason codes and
 * `attempted_input` field paths are the frozen ones carried by
 * [CorrectTransactionVersionViolation] (validator `_posting_facts_correction_failure`,
 * tools/python/golden_cases/v2.py, lines 427-520): every rejected path keeps the baseline
 * field by field and has zero formal effect.
 */
data class Rg12OperationIdentity(
    val ledgerId: LedgerId,
    val value: String,
)

/**
 * The closed action codes of the approved RG-12 registry. All twelve frozen operations use the
 * single `correct_transaction_version` action with `correction_kind = posting_facts`; replays
 * reuse the action of the replayed operation and are detected by identity fingerprint in
 * [Rg12Runtime.commit] (the frozen `root-correction-replay` is the same input as the accepted
 * `root-correction-correct`).
 */
enum class Rg12Action(
    val code: String,
) {
    CORRECT_TRANSACTION_VERSION("correct_transaction_version"),
}

/**
 * Per-rejection closed predicates of the frozen `operation-reject-*` fixtures. The primary
 * rejection path recomputes the frozen reason and exact `$.attempted_input.*` field path
 * through the domain [validatePostingFactsCorrection] chain; this fallback form carries the
 * raw `attempted_input` map (like Rg08InvalidInput / Rg11InvalidInput) for attempted inputs
 * that cannot be expressed as a typed [Rg12CorrectInput] and are mapped by predicate in
 * [Rg12Runtime.rejectInvalidInput].
 */
enum class Rg12InvalidPredicate {
    COMPLETE_REPLACEMENT_POSTINGS,
    REPLACEMENT_POSTINGS_BALANCE,
    DUPLICATE_SOURCE_POSTING_ID,
    KNOWN_ACCOUNT,
    OWNED_ACCOUNT,
    ACCOUNT_CURRENCY_MISMATCH,
    MATCHED_UNAFFECTED_POSTING_PRESERVED,
    EXPLICIT_CONFIRMATION,
    EXACT_DECIMAL_STRING,
    HISTORICAL_FACTS_IMMUTABLE,
}

data class Rg12InvalidInput(
    val requestId: RequestId,
    val action: Rg12Action,
    val predicate: Rg12InvalidPredicate,
    val attemptedInput: Map<String, String?>,
)

/**
 * D-085 RG-12 `correct_transaction_version` `posting_facts` input, matching the frozen
 * `root-correction-correct` / `root-correction-replay` and the `root-rejections-reject-*`
 * `attempted_input` shapes: target transaction, `correction_kind = posting_facts`,
 * `corrected_at`, the complete closed `replacement_postings` array (each item is
 * `source_posting_id` + [PostingFacts] `account_id` / `amount` / `currency` / `role` /
 * optional `category_id`), `explicit_confirmation` and the optional `history_mutation`.
 *
 * [rejectChangedMatchedAsset] mirrors the `reject_changed_asset` keyword of the golden
 * validator: `true` (the default, rejection semantics) rejects a changed matched asset leg
 * with `matched_unaffected_posting_must_be_preserved`; `false` (accepted-path semantics, used
 * by the fixture adapter for the correct/replay operations) lets the change through and
 * expresses it through the symmetric preserved/invalidated lineage instead (changed_asset_case
 * of tests/python/test_rg12_golden_v2.py).
 */
data class Rg12CorrectInput(
    val requestId: RequestId,
    val transactionId: TransactionId,
    val correctionKind: String,
    val correctedAt: Instant,
    val correctedAtText: String = correctedAt.toString(),
    val replacementPostings: List<ReplacementPostingInput>,
    val explicitConfirmation: Boolean,
    val historyMutation: HistoryMutationInput? = null,
    val rejectChangedMatchedAsset: Boolean = true,
)

/**
 * Deterministic ids of a posting-facts correction. Every list is aligned by index with
 * [Rg12CorrectInput.replacementPostings]; `null` entries mean "no entity of that kind for this
 * replacement". The fixture adapter derives them deterministically from the normalized input;
 * the runtime only consumes them. The frozen `root-correction-correct` anchors:
 *
 * - `version_id` / `posting_set_id` / `posting_ids` — the appended v2 version, its fresh
 *   posting set and one replacement posting per input item;
 * - `replacement_link_ids` — one `posting_replacement` audit link per input item;
 * - `confirmation_id` / `operation_id` — the explicit operation confirmation whose subject is
 *   the operation itself (`operation_id == operation["id"]`, confirmed at `corrected_at`);
 * - `invalidation_entry_ids` — the status-history entry id appended to the predecessor match
 *   of an invalidated real posting (one per input item, used only for invalidated legs);
 * - `new_match_ids` / `new_match_entry_ids` — the fresh inherited match of a preserved real
 *   posting (its history-1 entry id), or `null` when no successor match exists;
 * - `new_match_invalidation_entry_ids` — the history-2 entry id of a fresh match that is
 *   created and immediately invalidated (changed matched asset on the accepted path);
 * - `reconciliation_fact_ids` — the `posting_reconciliation` fact of the replacement posting
 *   (non-null exactly for eligible real postings);
 * - `consumption_record_id` — the new `consumption_record` domain entity of the expense leg.
 */
data class Rg12CorrectIds(
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val postingIds: List<PostingId>,
    val replacementLinkIds: List<String>,
    val confirmationId: String,
    val operationId: String,
    val invalidationEntryIds: List<String>,
    val newMatchIds: List<String?>,
    val newMatchEntryIds: List<String?>,
    val newMatchInvalidationEntryIds: List<String?>,
    val reconciliationFactIds: List<String?>,
    val consumptionRecordId: String?,
)

/** Generic retry (RG-08/RG-11 pattern): replays by input anchor id with the replayed action. */
data class Rg12RetryInput(
    val inputId: String,
    val replayedAction: Rg12Action,
)

sealed interface Rg12Operation {
    val ledgerId: LedgerId
    val action: Rg12Action
    val identity: Rg12OperationIdentity

    data class CorrectTransactionVersion(
        override val ledgerId: LedgerId,
        val input: Rg12CorrectInput,
        val ids: Rg12CorrectIds,
    ) : Rg12Operation {
        override val action = Rg12Action.CORRECT_TRANSACTION_VERSION
        override val identity = Rg12OperationIdentity(ledgerId, input.requestId.value)
    }

    data class RetryIdempotentInput(
        override val ledgerId: LedgerId,
        val input: Rg12RetryInput,
    ) : Rg12Operation {
        override val action = input.replayedAction
        override val identity = Rg12OperationIdentity(ledgerId, input.inputId)
    }

    data class InvalidInput(
        override val ledgerId: LedgerId,
        val input: Rg12InvalidInput,
    ) : Rg12Operation {
        override val action = input.action
        override val identity = Rg12OperationIdentity(ledgerId, input.requestId.value)
    }
}

sealed interface Rg12ReturnedId {
    data class Transaction(
        val id: TransactionId,
    ) : Rg12ReturnedId

    data class Version(
        val id: TransactionVersionId,
    ) : Rg12ReturnedId

    data class PostingSet(
        val id: PostingSetId,
    ) : Rg12ReturnedId

    data class Posting(
        val id: PostingId,
    ) : Rg12ReturnedId

    data class Replacement(
        val id: String,
    ) : Rg12ReturnedId

    data class Confirmation(
        val id: String,
    ) : Rg12ReturnedId

    data class Match(
        val id: String,
    ) : Rg12ReturnedId

    data class Request(
        val id: String,
    ) : Rg12ReturnedId
}

/**
 * Closed rejection reasons of the frozen rg-12.json. The first ten entries are the frozen
 * `operation-reject-*` reason codes of `_posting_facts_correction_failure`; the last two are
 * application-level guards that no frozen fixture triggers.
 */
enum class Rg12RejectionReason(
    val code: String,
) {
    COMPLETE_REPLACEMENT_POSTINGS_REQUIRED("complete_replacement_postings_required"),
    REPLACEMENT_POSTINGS_MUST_BALANCE("replacement_postings_must_balance"),
    DUPLICATE_SOURCE_POSTING_ID("duplicate_source_posting_id"),
    KNOWN_ACCOUNT_REQUIRED("known_account_required"),
    OWNED_ACCOUNT_REQUIRED("owned_account_required"),
    ACCOUNT_CURRENCY_MISMATCH("account_currency_mismatch"),
    MATCHED_UNAFFECTED_POSTING_MUST_BE_PRESERVED("matched_unaffected_posting_must_be_preserved"),
    EXPLICIT_CONFIRMATION_REQUIRED("explicit_confirmation_required"),
    EXACT_DECIMAL_STRING_REQUIRED("exact_decimal_string_required"),
    HISTORICAL_FACTS_IMMUTABLE("historical_facts_immutable"),
    INVALID_RG12_INPUT("invalid_rg12_input"),
    DOMAIN_REJECTED("domain_rejected"),
    ;

    companion object {
        /** Frozen-code lookup used by the domain violation mapping. */
        fun byCode(code: String): Rg12RejectionReason? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Frozen `field_path` values. Unlike the fixed RG-11 paths, most RG-12 paths carry an
 * `replacement_postings[i]` index, so the path is the exact string the domain violation
 * carries (data class) plus named application-level constants. Rejection paths always use the
 * `$.attempted_input.*` form.
 */
data class Rg12FieldPath(
    val value: String,
) {
    companion object {
        val INPUT_CORRECTION_KIND = Rg12FieldPath("$.input.correction_kind")
        val INPUT_REPLACEMENT_POSTINGS = Rg12FieldPath("$.input.replacement_postings")
        val ATTEMPTED_REQUEST_ID = Rg12FieldPath("$.attempted_input.request_id")
        val ATTEMPTED_TRANSACTION_ID = Rg12FieldPath("$.attempted_input.transaction_id")
        val ATTEMPTED_REPLACEMENT_POSTINGS = Rg12FieldPath("$.attempted_input.replacement_postings")
        val ATTEMPTED_EXPLICIT_CONFIRMATION = Rg12FieldPath("$.attempted_input.explicit_confirmation")
        val ATTEMPTED_HISTORY_MUTATION = Rg12FieldPath("$.attempted_input.history_mutation")
    }
}

sealed interface Rg12ExecutionResult {
    class Accepted(
        returnedIds: List<Rg12ReturnedId>,
    ) : Rg12ExecutionResult {
        private val snapshot = returnedIds.toList()
        val returnedIds: List<Rg12ReturnedId> get() = snapshot.toList()

        override fun equals(other: Any?) = other is Accepted && snapshot == other.snapshot

        override fun hashCode(): Int = snapshot.hashCode()

        override fun toString(): String = "Accepted(returnedIds=$snapshot)"
    }

    class NoChange(
        returnedIds: List<Rg12ReturnedId>,
    ) : Rg12ExecutionResult {
        private val snapshot = returnedIds.toList()
        val returnedIds: List<Rg12ReturnedId> get() = snapshot.toList()

        override fun equals(other: Any?) = other is NoChange && snapshot == other.snapshot

        override fun hashCode(): Int = snapshot.hashCode()

        override fun toString(): String = "NoChange(returnedIds=$snapshot)"
    }

    data class Rejected(
        val reason: Rg12RejectionReason,
        val fieldPath: Rg12FieldPath,
    ) : Rg12ExecutionResult

    data object RequestIdentityConflict : Rg12ExecutionResult
}

fun interface Rg12CommitPort {
    fun commit(operation: Rg12Operation): Rg12ExecutionResult
}

/**
 * A formal transaction as appended by the runtime: the formal chain plus the booking time
 * texts used by report periods, the per-version `created_at` texts (the frozen v2 version is
 * created at `corrected_at`) and the per-version explicit-operation-confirmation ownership of
 * `correct_transaction_version` (the frozen v2 version carries `confirmation_id`).
 */
data class Rg12FormalTransactionRecord(
    val formalTransaction: FormalTransaction,
    val createdAt: Instant,
    val createdAtText: String? = null,
    val statisticsAtText: String? = null,
    val versionCreatedAtTexts: Map<TransactionVersionId, String> = emptyMap(),
    val versionConfirmationIds: Map<TransactionVersionId, String> = emptyMap(),
)

/**
 * Per-posting projection facts the golden postings need (`role`, `category_id`) plus the
 * reconciliation eligibility flag (the domain [Posting] carries neither; eligibility follows
 * the frozen rule "eligible owned real account posting"). The fixture adapter seeds one entry
 * for every posting of the baseline; the runtime adds one entry for every appended posting.
 */
data class Rg12PostingSemantic(
    val role: String?,
    val reconciliationEligible: Boolean,
    val categoryId: CategoryId? = null,
)

/**
 * RG-12 `consumption_record` domain entity, owned by the application (shard 1 delivered no
 * consumption record domain type). One record exists per expense posting of the current
 * version; a posting-facts correction appends a fresh record for the replacement expense leg
 * (frozen `root-correction-consumption-v2`).
 */
data class Rg12ConsumptionRecord(
    val id: String,
    val expensePostingId: PostingId,
    val categoryId: CategoryId?,
    val amountText: String,
    val currency: CurrencyUnit,
    val statisticsAtText: String,
)

/**
 * Generic domain-entity projection of the frozen `domain_entities` collection. The runtime
 * keeps typed state ([ReconciliationMatch], [Rg12ConsumptionRecord]) and projects the two
 * entity types deterministically (consumption records first, then matches; consumers compare
 * by id like the golden deltas). Payload values are rendered as frozen JSON texts.
 */
data class Rg12DomainEntity(
    val id: String,
    val type: String,
    val payload: Map<String, String>,
)

/** Day-period report values of the frozen rg-12 report registry. */
data class Rg12Report(
    val cashOutflowMinor: Long = 0L,
    val consumptionMinor: Long = 0L,
    val categoryConsumptionMinor: Long = 0L,
    val netWorthChangeMinor: Long = 0L,
)

/**
 * Full executable state of the RG-12 runtime. `versions`, `posting_sets` and `postings` are
 * reachable through [Rg12FormalTransactionRecord.formalTransaction]; balances, reports and the
 * reconciliation summary are recomputed from the immutable facts, never stored. The day-period
 * report registry (including the frozen zero period of the correction day) is seeded through
 * [Rg12Snapshot.reportPeriods]; report values are always recomputed.
 */
data class Rg12Snapshot(
    val formalTransactions: List<Rg12FormalTransactionRecord>,
    val postingSemantics: Map<String, Rg12PostingSemantic>,
    val reconciliationMatches: List<ReconciliationMatch>,
    val postingReconciliations: List<PostingReconciliation>,
    val postingReplacements: List<PostingReplacement>,
    val confirmations: List<ExplicitOperationConfirmation>,
    val consumptionRecords: List<Rg12ConsumptionRecord>,
    val domainEntities: List<Rg12DomainEntity>,
    val reconciliationSummary: Map<TransactionId, ReconciliationSummary>,
    val balances: Map<AccountId, Money>,
    val reports: Map<String, Rg12Report>,
    val reportPeriods: List<String>,
)

/**
 * Deterministic runtime for the approved RG-12 action registry (D-085). Business transitions
 * stay independent from a database driver; persistence integration preserves this typed
 * operation boundary. Rejected/incomplete/no-change paths have zero formal effect and keep
 * the baseline state field by field.
 *
 * Execution of an accepted posting-facts correction (frozen `root-correction-correct`):
 *
 * 1. the domain chain [validatePostingFactsCorrection] runs first with the frozen
 *    first-failure order (unknown transaction / count / balance / duplicate source / account
 *    checks / changed matched asset / confirmation / exact decimal / history mutation);
 * 2. the accepted-path input guards of the golden validator run (category ownership of each
 *    replacement, mixed-expense posting shape);
 * 3. [FormalTransaction.appendVersion] with [TransactionVersionChange.Postings] and a fresh
 *    posting set id appends version 2 (economic times preserved, `created_at = corrected_at`);
 * 4. per replacement item the [ReconciliationEffect] is derived (not_applicable for postings
 *    without an old reconciliation fact, preserved for unchanged eligible real postings,
 *    invalidated for changed ones) and a `posting_replacement` audit link is created;
 * 5. preserved legs inherit an active match for the same evidence and a `matched` fact;
 *    invalidated legs append exactly one `posting_replaced` invalidation entry to the
 *    predecessor match at `corrected_at` and receive a `pending` fact; on the accepted path a
 *    changed matched asset additionally creates a fresh inherited match that is immediately
 *    invalidated (changed_asset_case symmetry);
 * 6. a fresh `consumption_record` is appended for the expense leg and the explicit operation
 *    confirmation is created at `corrected_at`.
 */
class Rg12Runtime(
    private val catalog: LedgerCatalog,
    openingTransactions: List<Rg12FormalTransactionRecord>,
    postingSemantics: Map<String, Rg12PostingSemantic> = emptyMap(),
    reconciliationMatches: List<ReconciliationMatch> = emptyList(),
    postingReconciliations: List<PostingReconciliation> = emptyList(),
    postingReplacements: List<PostingReplacement> = emptyList(),
    confirmations: List<ExplicitOperationConfirmation> = emptyList(),
    consumptionRecords: List<Rg12ConsumptionRecord> = emptyList(),
    reportPeriods: List<String> = emptyList(),
    private val utcOffsetSeconds: Int = DEFAULT_UTC_OFFSET_SECONDS,
) : Rg12CommitPort {
    constructor(catalog: LedgerCatalog, snapshot: Rg12Snapshot) : this(
        catalog,
        snapshot.formalTransactions,
        snapshot.postingSemantics,
        snapshot.reconciliationMatches,
        snapshot.postingReconciliations,
        snapshot.postingReplacements,
        snapshot.confirmations,
        snapshot.consumptionRecords,
        snapshot.reportPeriods,
    )

    private val formalTransactions = openingTransactions.toMutableList()
    private val postingSemantics = postingSemantics.toMutableMap()
    private val matches = reconciliationMatches.toMutableList()
    private val postingReconciliations = postingReconciliations.toMutableList()
    private val postingReplacements = postingReplacements.toMutableList()
    private val confirmations = confirmations.toMutableList()
    private val consumptionRecords = consumptionRecords.toMutableList()
    private val reportPeriods = reportPeriods.toList()
    private val receipts = mutableMapOf<Rg12OperationIdentity, Receipt>()

    private data class Receipt(
        val fingerprint: String,
        val result: Rg12ExecutionResult,
    )

    override fun commit(operation: Rg12Operation): Rg12ExecutionResult {
        if (operation is Rg12Operation.RetryIdempotentInput) {
            return replayRetry(operation)
        }
        val fingerprint = canonicalInput(operation)
        receipts[operation.identity]?.let { receipt ->
            return if (receipt.fingerprint == fingerprint) {
                when (val result = receipt.result) {
                    is Rg12ExecutionResult.Accepted -> Rg12ExecutionResult.NoChange(result.returnedIds)
                    else -> result
                }
            } else {
                Rg12ExecutionResult.RequestIdentityConflict
            }
        }
        val result =
            when (operation) {
                is Rg12Operation.CorrectTransactionVersion -> correctTransactionVersion(operation)
                is Rg12Operation.RetryIdempotentInput -> replayRetry(operation)
                is Rg12Operation.InvalidInput -> rejectInvalidInput(operation)
            }
        if (result is Rg12ExecutionResult.Accepted || result is Rg12ExecutionResult.Rejected) {
            receipts[operation.identity] = Receipt(fingerprint, result)
        }
        return result
    }

    fun snapshot(): Rg12Snapshot =
        Rg12Snapshot(
            formalTransactions = formalTransactions.toList(),
            postingSemantics = postingSemantics.toMap(),
            reconciliationMatches = matches.toList(),
            postingReconciliations = postingReconciliations.toList(),
            postingReplacements = postingReplacements.toList(),
            confirmations = confirmations.toList(),
            consumptionRecords = consumptionRecords.toList(),
            domainEntities = domainEntities(),
            reconciliationSummary = reconciliationSummary(),
            balances = replayBalances(),
            reports = reports(),
            reportPeriods = reportPeriods,
        )

    fun operationFingerprint(operation: Rg12Operation): String = canonicalInput(operation)

    // ------------------------------------------------------------------ correct

    private fun correctTransactionVersion(operation: Rg12Operation.CorrectTransactionVersion): Rg12ExecutionResult {
        val input = operation.input
        val ids = operation.ids
        if (input.correctionKind != CORRECTION_KIND_POSTING_FACTS) {
            return rejected(Rg12RejectionReason.INVALID_RG12_INPUT, Rg12FieldPath.INPUT_CORRECTION_KIND)
        }
        val index = formalTransactions.indexOfFirst { it.formalTransaction.transaction.id == input.transactionId }
        val record = formalTransactions.getOrNull(index)
        val oldPostings = record?.formalTransaction?.currentPostings().orEmpty()
        val oldFactsByPosting = oldPostings.associate { posting -> posting.id to factsOf(posting) }
        val reconciliationsByPosting = postingReconciliations.associate { it.postingId to it.status }

        // Frozen first-failure order of the domain chain (`_posting_facts_correction_failure`).
        when (
            val validation =
                validatePostingFactsCorrection(
                    attempt =
                        PostingFactsCorrectionAttempt(
                            transaction = record?.formalTransaction,
                            replacementPostings = input.replacementPostings,
                            explicitConfirmation = input.explicitConfirmation,
                            historyMutation = input.historyMutation,
                        ),
                    accounts = accountsById(),
                    oldFactsByPosting = oldFactsByPosting,
                    reconciliationsByPosting = reconciliationsByPosting,
                    rejectChangedMatchedAsset = input.rejectChangedMatchedAsset,
                )
        ) {
            is DomainResult.Success -> Unit
            is DomainResult.Failure -> return violationRejected(validation.violation)
        }
        // The domain chain rejected an unknown transaction with
        // `complete_replacement_postings_required`, so this guard is unreachable on accepted
        // paths; it only satisfies the nullability of the lookup above.
        val correctedRecord =
            record
                ?: return rejected(Rg12RejectionReason.DOMAIN_REJECTED, Rg12FieldPath.ATTEMPTED_REQUEST_ID)

        if (!idsAligned(input.replacementPostings.size, ids)) {
            return rejected(Rg12RejectionReason.INVALID_RG12_INPUT, Rg12FieldPath.ATTEMPTED_REQUEST_ID)
        }

        // Accepted-path input guard of the golden validator (v2.py lines 7338-7345): every
        // `category_id` must reference an active category owned by the replacement account.
        input.replacementPostings.forEachIndexed { itemIndex, item ->
            val category =
                item.facts.categoryId?.let { categoryId ->
                    catalog.categories.firstOrNull { candidate -> candidate.id == categoryId }
                }
            val account = catalogAccount(item.facts.accountId)
            if (
                item.facts.categoryId != null &&
                (category == null || !category.active || category.postingAccountId != account?.id)
            ) {
                return rejected(
                    Rg12RejectionReason.DOMAIN_REJECTED,
                    Rg12FieldPath("$.input.replacement_postings[$itemIndex].category_id"),
                )
            }
        }

        // Build the replacement postings; the domain chain guaranteed exact decimal texts.
        val newPostings =
            input.replacementPostings.mapIndexed { itemIndex, item ->
                val minor =
                    parseExactDecimal(item.facts.amountText, item.facts.currency.precision)
                        ?: return rejected(Rg12RejectionReason.DOMAIN_REJECTED, Rg12FieldPath.ATTEMPTED_REQUEST_ID)
                Posting(ids.postingIds[itemIndex], item.facts.accountId, Money.ofMinor(minor, item.facts.currency))
            }

        // Accepted-path shape guard of the golden validator (`_validate_mixed_expense_postings`):
        // the correction must preserve an expense with exactly two mixed-payment funding roles.
        if (!mixedExpenseShapeValid(input.replacementPostings, newPostings)) {
            return rejected(Rg12RejectionReason.DOMAIN_REJECTED, Rg12FieldPath.ATTEMPTED_REPLACEMENT_POSTINGS)
        }

        val appended =
            when (
                val result =
                    correctedRecord.formalTransaction.appendVersion(
                        change = TransactionVersionChange.Postings(newPostings),
                        ids = TransactionVersionAppendIds(versionId = ids.versionId),
                        newPostingSetId = ids.postingSetId,
                    )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return domainRejected(result.violation)
            }
        val appendedVersion = appended.versions.single { it.id == appended.transaction.currentVersionId }
        val currentVersion =
            correctedRecord.formalTransaction.versions
                .single { it.id == correctedRecord.formalTransaction.transaction.currentVersionId }
        val confirmation =
            when (
                val result =
                    createExplicitOperationConfirmation(
                        id = ids.confirmationId,
                        operationId = ids.operationId,
                        createdAt = input.correctedAt,
                    )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return domainRejected(result.violation)
            }

        // Per-item lineage: effect, predecessor invalidation, successor match, reconciliation
        // fact and replacement link, in the frozen accepted-path order.
        val updatedMatches = mutableMapOf<String, ReconciliationMatch>()
        val newMatches = mutableListOf<ReconciliationMatch>()
        val newFacts = mutableListOf<PostingReconciliation>()
        val newLinks = mutableListOf<PostingReplacement>()
        var newConsumption: Rg12ConsumptionRecord? = null
        input.replacementPostings.forEachIndexed { itemIndex, item ->
            val oldPosting = oldPostings.first { it.id == item.sourcePostingId }
            val oldAccount =
                catalogAccount(oldPosting.accountId)
                    ?: return rejected(Rg12RejectionReason.DOMAIN_REJECTED, Rg12FieldPath.ATTEMPTED_REQUEST_ID)
            val oldFacts = oldFactsByPosting.getValue(oldPosting.id)
            val oldReconciliation = reconciliationsByPosting[oldPosting.id]
            val isReal = oldAccount.ownedByUser && oldAccount.realAccount
            val eligibleReal = isReal && oldReconciliation != null
            val effect =
                when {
                    !eligibleReal -> ReconciliationEffect.NOT_APPLICABLE
                    oldFacts.sameAs(item.facts) -> ReconciliationEffect.PRESERVED
                    else -> ReconciliationEffect.INVALIDATED
                }
            val newPosting = newPostings[itemIndex]
            if (eligibleReal) {
                val oldMatch =
                    matches.firstOrNull { it.postingId == oldPosting.id && it.currentStatus == ReconciliationMatchStatus.MATCHED }
                        ?: return rejected(Rg12RejectionReason.DOMAIN_REJECTED, Rg12FieldPath.ATTEMPTED_REQUEST_ID)
                val reconciliationFactId =
                    ids.reconciliationFactIds[itemIndex]
                        ?: return rejected(Rg12RejectionReason.DOMAIN_REJECTED, Rg12FieldPath.ATTEMPTED_REQUEST_ID)
                if (effect == ReconciliationEffect.INVALIDATED) {
                    val invalidated =
                        when (
                            val result =
                                invalidateReconciliationMatch(
                                    oldMatch,
                                    ids.invalidationEntryIds[itemIndex],
                                    input.correctedAt,
                                )
                        ) {
                            is DomainResult.Success -> result.value
                            is DomainResult.Failure -> return domainRejected(result.violation)
                        }
                    updatedMatches[oldMatch.id] = invalidated
                    val newMatchId = ids.newMatchIds[itemIndex]
                    if (newMatchId != null) {
                        // Symmetric changed-matched-asset lineage (accepted path,
                        // `reject_changed_asset=False`): the fresh inherited match is created
                        // and immediately invalidated, exactly like the changed_asset_case.
                        val newMatchEntryId =
                            ids.newMatchEntryIds[itemIndex]
                                ?: return rejected(Rg12RejectionReason.DOMAIN_REJECTED, Rg12FieldPath.ATTEMPTED_REQUEST_ID)
                        val newMatchInvalidationEntryId =
                            ids.newMatchInvalidationEntryIds[itemIndex]
                                ?: return rejected(Rg12RejectionReason.DOMAIN_REJECTED, Rg12FieldPath.ATTEMPTED_REQUEST_ID)
                        val inheritedMatch =
                            when (
                                val result =
                                    createReconciliationMatch(
                                        id = newMatchId,
                                        postingId = newPosting.id,
                                        evidenceId = oldMatch.evidenceId,
                                        statusHistory =
                                            listOf(
                                                ReconciliationMatchStatusEntry(
                                                    id = newMatchEntryId,
                                                    sequence = 1,
                                                    status = ReconciliationMatchStatus.MATCHED,
                                                    at = lastMatchedAt(oldMatch),
                                                    reason = ReconciliationMatchReason.EXACT_EVIDENCE,
                                                ),
                                            ),
                                    )
                            ) {
                                is DomainResult.Success -> result.value
                                is DomainResult.Failure -> return domainRejected(result.violation)
                            }
                        val invalidatedNewMatch =
                            when (
                                val result =
                                    invalidateReconciliationMatch(
                                        inheritedMatch,
                                        newMatchInvalidationEntryId,
                                        input.correctedAt,
                                    )
                            ) {
                                is DomainResult.Success -> result.value
                                is DomainResult.Failure -> return domainRejected(result.violation)
                            }
                        newMatches += invalidatedNewMatch
                    }
                } else {
                    val newMatchId =
                        ids.newMatchIds[itemIndex]
                            ?: return rejected(Rg12RejectionReason.DOMAIN_REJECTED, Rg12FieldPath.ATTEMPTED_REQUEST_ID)
                    val newMatchEntryId =
                        ids.newMatchEntryIds[itemIndex]
                            ?: return rejected(Rg12RejectionReason.DOMAIN_REJECTED, Rg12FieldPath.ATTEMPTED_REQUEST_ID)
                    val inheritedMatch =
                        when (
                            val result =
                                createReconciliationMatch(
                                    id = newMatchId,
                                    postingId = newPosting.id,
                                    evidenceId = oldMatch.evidenceId,
                                    statusHistory =
                                        listOf(
                                            ReconciliationMatchStatusEntry(
                                                id = newMatchEntryId,
                                                sequence = 1,
                                                status = ReconciliationMatchStatus.MATCHED,
                                                at = lastMatchedAt(oldMatch),
                                                reason = ReconciliationMatchReason.EXACT_EVIDENCE,
                                            ),
                                        ),
                                )
                        ) {
                            is DomainResult.Success -> result.value
                            is DomainResult.Failure -> return domainRejected(result.violation)
                        }
                    newMatches += inheritedMatch
                }
                val fact =
                    when (
                        val result =
                            createPostingReconciliation(
                                id = reconciliationFactId,
                                postingId = newPosting.id,
                                status = replacementPostingReconciliationStatus(effect)!!,
                            )
                    ) {
                        is DomainResult.Success -> result.value
                        is DomainResult.Failure -> return domainRejected(result.violation)
                    }
                newFacts += fact
            } else {
                if (ids.newMatchIds[itemIndex] != null || ids.reconciliationFactIds[itemIndex] != null) {
                    return rejected(Rg12RejectionReason.DOMAIN_REJECTED, Rg12FieldPath.ATTEMPTED_REQUEST_ID)
                }
            }
            val link =
                when (
                    val result =
                        createPostingReplacement(
                            id = ids.replacementLinkIds[itemIndex],
                            fromPostingId = oldPosting.id,
                            toPostingId = newPosting.id,
                            fromVersion = currentVersion,
                            toVersion = appendedVersion,
                            fromFacts = oldFacts,
                            toFacts = item.facts,
                            fromAccount = oldAccount,
                            activeMatchesByPosting = activeMatches(updatedMatches, newMatches),
                            reconciliationEffect = effect,
                        )
                ) {
                    is DomainResult.Success -> result.value
                    is DomainResult.Failure -> return domainRejected(result.violation)
                }
            newLinks += link
            if (item.facts.role == ROLE_EXPENSE) {
                if (newConsumption != null || ids.consumptionRecordId == null) {
                    return rejected(Rg12RejectionReason.DOMAIN_REJECTED, Rg12FieldPath.ATTEMPTED_REQUEST_ID)
                }
                newConsumption =
                    Rg12ConsumptionRecord(
                        id = checkNotNull(ids.consumptionRecordId),
                        expensePostingId = newPosting.id,
                        categoryId = item.facts.categoryId,
                        amountText = item.facts.amountText,
                        currency = item.facts.currency,
                        statisticsAtText =
                            correctedRecord.statisticsAtText
                                ?: statisticsAtTextOf(correctedRecord.formalTransaction),
                    )
            }
        }
        if (newConsumption == null) {
            return rejected(Rg12RejectionReason.DOMAIN_REJECTED, Rg12FieldPath.ATTEMPTED_REQUEST_ID)
        }
        val newConsumptionRecord = newConsumption

        // Idempotence and integrity guards over the whole collection before any mutation.
        if (
            appendedIdCollision(appended, record.formalTransaction) ||
            confirmations.any { it.id == ids.confirmationId } ||
            postingReplacements.any { it.id in ids.replacementLinkIds } ||
            matches.any { match -> ids.newMatchIds.any { it == match.id } } ||
            postingReconciliations.any { it.id in ids.reconciliationFactIds } ||
            consumptionRecords.any { it.id == newConsumptionRecord.id } ||
            newMatches.any { new -> matches.any { existing -> existing.id == new.id } } ||
            newFacts.any { new -> postingReconciliations.any { existing -> existing.id == new.id } }
        ) {
            return rejected(Rg12RejectionReason.DOMAIN_REJECTED, Rg12FieldPath.ATTEMPTED_REQUEST_ID)
        }

        formalTransactions[index] =
            correctedRecord.copy(
                formalTransaction = appended,
                versionCreatedAtTexts = correctedRecord.versionCreatedAtTexts + (ids.versionId to input.correctedAtText),
                versionConfirmationIds = correctedRecord.versionConfirmationIds + (ids.versionId to ids.confirmationId),
            )
        confirmations += confirmation
        newPostings.forEachIndexed { itemIndex, posting ->
            val account = catalogAccount(posting.accountId)
            postingSemantics[posting.id.value] =
                Rg12PostingSemantic(
                    role = input.replacementPostings[itemIndex].facts.role,
                    reconciliationEligible = account?.let { it.ownedByUser && it.realAccount } ?: false,
                    categoryId = input.replacementPostings[itemIndex].facts.categoryId,
                )
        }
        updatedMatches.values.forEach { updated ->
            val matchIndex = matches.indexOfFirst { it.id == updated.id }
            if (matchIndex >= 0) matches[matchIndex] = updated
        }
        matches += newMatches
        postingReconciliations += newFacts
        postingReplacements += newLinks
        consumptionRecords += newConsumptionRecord
        return accepted(listOf(Rg12ReturnedId.Version(ids.versionId)))
    }

    // ------------------------------------------------------------------ retry / invalid

    private fun replayRetry(operation: Rg12Operation.RetryIdempotentInput): Rg12ExecutionResult {
        val receipt =
            receipts[operation.identity]
                ?: return Rg12ExecutionResult.RequestIdentityConflict
        return when (val result = receipt.result) {
            is Rg12ExecutionResult.Accepted -> Rg12ExecutionResult.NoChange(result.returnedIds)
            else -> result
        }
    }

    /**
     * Fallback rejection mapping for attempted inputs that cannot be expressed as a typed
     * [Rg12CorrectInput]. The primary path recomputes the exact frozen field path (including
     * the `replacement_postings[i]` index) through the domain chain; this fallback uses the
     * base path of the frozen field (`$.attempted_input.replacement_postings`, ...).
     */
    private fun rejectInvalidInput(operation: Rg12Operation.InvalidInput): Rg12ExecutionResult {
        val (reason, fieldPath) =
            when (operation.input.predicate) {
                Rg12InvalidPredicate.COMPLETE_REPLACEMENT_POSTINGS ->
                    Rg12RejectionReason.COMPLETE_REPLACEMENT_POSTINGS_REQUIRED to Rg12FieldPath.ATTEMPTED_REPLACEMENT_POSTINGS
                Rg12InvalidPredicate.REPLACEMENT_POSTINGS_BALANCE ->
                    Rg12RejectionReason.REPLACEMENT_POSTINGS_MUST_BALANCE to Rg12FieldPath.ATTEMPTED_REPLACEMENT_POSTINGS
                Rg12InvalidPredicate.DUPLICATE_SOURCE_POSTING_ID ->
                    Rg12RejectionReason.DUPLICATE_SOURCE_POSTING_ID to Rg12FieldPath.ATTEMPTED_REPLACEMENT_POSTINGS
                Rg12InvalidPredicate.KNOWN_ACCOUNT ->
                    Rg12RejectionReason.KNOWN_ACCOUNT_REQUIRED to Rg12FieldPath.ATTEMPTED_REPLACEMENT_POSTINGS
                Rg12InvalidPredicate.OWNED_ACCOUNT ->
                    Rg12RejectionReason.OWNED_ACCOUNT_REQUIRED to Rg12FieldPath.ATTEMPTED_REPLACEMENT_POSTINGS
                Rg12InvalidPredicate.ACCOUNT_CURRENCY_MISMATCH ->
                    Rg12RejectionReason.ACCOUNT_CURRENCY_MISMATCH to Rg12FieldPath.ATTEMPTED_REPLACEMENT_POSTINGS
                Rg12InvalidPredicate.MATCHED_UNAFFECTED_POSTING_PRESERVED ->
                    Rg12RejectionReason.MATCHED_UNAFFECTED_POSTING_MUST_BE_PRESERVED to Rg12FieldPath.ATTEMPTED_REPLACEMENT_POSTINGS
                Rg12InvalidPredicate.EXPLICIT_CONFIRMATION ->
                    Rg12RejectionReason.EXPLICIT_CONFIRMATION_REQUIRED to Rg12FieldPath.ATTEMPTED_EXPLICIT_CONFIRMATION
                Rg12InvalidPredicate.EXACT_DECIMAL_STRING ->
                    Rg12RejectionReason.EXACT_DECIMAL_STRING_REQUIRED to Rg12FieldPath.ATTEMPTED_REPLACEMENT_POSTINGS
                Rg12InvalidPredicate.HISTORICAL_FACTS_IMMUTABLE ->
                    Rg12RejectionReason.HISTORICAL_FACTS_IMMUTABLE to Rg12FieldPath.ATTEMPTED_HISTORY_MUTATION
            }
        return rejected(reason, fieldPath)
    }

    // ------------------------------------------------------------------ derived state

    private fun domainEntities(): List<Rg12DomainEntity> =
        buildList {
            consumptionRecords.forEach { record ->
                val payload =
                    buildMap {
                        put("expense_posting_id", record.expensePostingId.value)
                        record.categoryId?.let { put("category_id", it.value) }
                        put("amount", record.amountText)
                        put("currency", record.currency.code)
                        put("statistics_at", record.statisticsAtText)
                    }
                add(Rg12DomainEntity(record.id, DOMAIN_ENTITY_CONSUMPTION_RECORD, payload))
            }
            matches.forEach { match ->
                add(
                    Rg12DomainEntity(
                        match.id,
                        DOMAIN_ENTITY_RECONCILIATION_MATCH,
                        mapOf(
                            "posting_id" to match.postingId.value,
                            "evidence_id" to match.evidenceId,
                            "status_history" to renderStatusHistory(match.statusHistory),
                        ),
                    ),
                )
            }
        }

    private fun reconciliationSummary(): Map<TransactionId, ReconciliationSummary> =
        buildMap {
            formalTransactions.forEach { record ->
                val statuses =
                    record.formalTransaction
                        .currentPostings()
                        .filter { postingSemantics[it.id.value]?.reconciliationEligible == true }
                        .map { posting ->
                            postingReconciliations.firstOrNull { it.postingId == posting.id }?.status
                                ?: PostingReconciliationStatus.PENDING
                        }
                if (statuses.isNotEmpty()) {
                    put(record.formalTransaction.transaction.id, deriveReconciliationSummary(statuses))
                }
            }
        }

    private fun reports(): Map<String, Rg12Report> {
        val periods = linkedMapOf<String, Rg12Report>()
        reportPeriods.forEach { period -> periods.putIfAbsent(period, Rg12Report()) }
        formalTransactions.forEach { record ->
            val report =
                when (record.formalTransaction.transaction.kind) {
                    TransactionKind.EXPENSE -> {
                        val postings = record.formalTransaction.currentPostings()
                        var expense = 0L
                        var assetFunding = 0L
                        postings.forEach { posting ->
                            when (postingSemantics[posting.id.value]?.role) {
                                ROLE_EXPENSE -> expense = checkedAdd(expense, posting.amount.minorUnits) ?: return@forEach
                                ROLE_MIXED_EXPENSE_ASSET_FUNDING ->
                                    assetFunding = checkedAdd(assetFunding, posting.amount.minorUnits) ?: return@forEach
                            }
                        }
                        val cashOutflow =
                            if (assetFunding != 0L) {
                                checkedNegate(assetFunding) ?: return@forEach
                            } else {
                                var fallback = 0L
                                postings.forEach { posting ->
                                    val account = catalogAccount(posting.accountId)
                                    if (
                                        account != null &&
                                        account.realAccount &&
                                        account.kind == AccountKind.ASSET &&
                                        posting.amount.minorUnits < 0L
                                    ) {
                                        fallback = checkedAdd(fallback, checkedNegate(posting.amount.minorUnits) ?: return@forEach) ?: return@forEach
                                    }
                                }
                                fallback
                            }
                        Rg12Report(
                            cashOutflowMinor = cashOutflow,
                            consumptionMinor = expense,
                            categoryConsumptionMinor = expense,
                            netWorthChangeMinor = checkedNegate(expense) ?: return@forEach,
                        )
                    }
                    else -> Rg12Report()
                }
            val period = (record.statisticsAtText ?: statisticsAtTextOf(record.formalTransaction)).substring(0, 10)
            val current = periods[period] ?: Rg12Report()
            periods[period] = mergeReports(current, report)
        }
        return periods
    }

    private fun mergeReports(
        left: Rg12Report,
        right: Rg12Report,
    ): Rg12Report =
        Rg12Report(
            cashOutflowMinor = checkedAdd(left.cashOutflowMinor, right.cashOutflowMinor)!!,
            consumptionMinor = checkedAdd(left.consumptionMinor, right.consumptionMinor)!!,
            categoryConsumptionMinor = checkedAdd(left.categoryConsumptionMinor, right.categoryConsumptionMinor)!!,
            netWorthChangeMinor = checkedAdd(left.netWorthChangeMinor, right.netWorthChangeMinor)!!,
        )

    // ------------------------------------------------------------------ helpers

    private fun factsOf(posting: Posting): PostingFacts {
        val semantic = postingSemantics[posting.id.value]
        return PostingFacts(
            accountId = posting.accountId,
            amountText = minorToExactText(posting.amount.minorUnits, posting.amount.currency.precision),
            currency = posting.amount.currency,
            role = semantic?.role.orEmpty(),
            categoryId = semantic?.categoryId,
        )
    }

    /**
     * Active (currently `matched`) matches by posting id, for the replacement link checks.
     * The view applies the corrections of the running correction: [updatedMatches] are the
     * invalidated predecessor matches (excluded once invalidated) and [newMatches] are the
     * fresh successor matches of preserved legs (included while still active).
     */
    private fun activeMatches(
        updatedMatches: Map<String, ReconciliationMatch>,
        newMatches: List<ReconciliationMatch>,
    ): Map<PostingId, ReconciliationMatch> =
        buildMap {
            matches.forEach { match ->
                val current = updatedMatches[match.id] ?: match
                if (current.currentStatus == ReconciliationMatchStatus.MATCHED) {
                    put(current.postingId, current)
                }
            }
            newMatches.forEach { match ->
                if (match.currentStatus == ReconciliationMatchStatus.MATCHED) {
                    put(match.postingId, match)
                }
            }
        }

    private fun lastMatchedAt(match: ReconciliationMatch): Instant = match.statusHistory.last { it.status == ReconciliationMatchStatus.MATCHED }.at

    private fun idsAligned(
        replacementCount: Int,
        ids: Rg12CorrectIds,
    ): Boolean =
        ids.postingIds.size == replacementCount &&
            ids.replacementLinkIds.size == replacementCount &&
            ids.invalidationEntryIds.size == replacementCount &&
            ids.newMatchIds.size == replacementCount &&
            ids.newMatchEntryIds.size == replacementCount &&
            ids.newMatchInvalidationEntryIds.size == replacementCount &&
            ids.reconciliationFactIds.size == replacementCount

    /**
     * Accepted-path shape guard mirroring `_validate_mixed_expense_postings` of the golden
     * validator: exactly three replacement postings with the closed role set, one currency,
     * an expense leg on a non-owned non-real expense account bound to an active second-level
     * category, and two owned real funding legs (asset / liability) without category. The
     * per-currency balance itself is enforced by [PostingSet.create] through `appendVersion`.
     */
    private fun mixedExpenseShapeValid(
        replacements: List<ReplacementPostingInput>,
        newPostings: List<Posting>,
    ): Boolean {
        if (replacements.size != 3) return false
        val roles = replacements.map { it.facts.role }.toSet()
        if (roles != setOf(ROLE_EXPENSE, ROLE_MIXED_EXPENSE_ASSET_FUNDING, ROLE_MIXED_EXPENSE_CREDIT_FUNDING)) return false
        if (replacements.map { it.facts.currency.code }.toSet().size != 1) return false
        val byRole = replacements.associateBy { it.facts.role }
        val expense = byRole.getValue(ROLE_EXPENSE)
        val expenseAccount = catalogAccount(expense.facts.accountId)
        val category = expense.facts.categoryId?.let { id -> catalog.categories.firstOrNull { it.id == id } }
        if (
            expenseAccount == null ||
            expenseAccount.kind != AccountKind.EXPENSE ||
            expenseAccount.ownedByUser ||
            expenseAccount.realAccount ||
            category == null ||
            !category.active ||
            category.parentId == null ||
            category.postingAccountId != expenseAccount.id
        ) {
            return false
        }
        val fundingRoles =
            listOf(
                ROLE_MIXED_EXPENSE_ASSET_FUNDING to AccountKind.ASSET,
                ROLE_MIXED_EXPENSE_CREDIT_FUNDING to AccountKind.LIABILITY,
            )
        for ((role, kind) in fundingRoles) {
            val item = byRole.getValue(role)
            if (item.facts.categoryId != null) return false
            val account = catalogAccount(item.facts.accountId)
            if (account == null || account.kind != kind || !account.ownedByUser || !account.realAccount) return false
            if (account.currency.code != item.facts.currency.code) return false
        }
        replacements.forEachIndexed { itemIndex, item ->
            val positive = newPostings[itemIndex].amount.minorUnits > 0L
            if (positive != (item.facts.role == ROLE_EXPENSE)) return false
        }
        return true
    }

    /**
     * Narrow collision check for `correct_transaction_version` only. [appended] is the
     * corrected record with a new version appended; its transaction id, previous versions,
     * posting sets and postings are the corrected record's own legal references and must not
     * be judged as collisions. Only ids the append newly introduces are checked against the
     * whole collection (same semantics as Rg11Operations.kt).
     */
    private fun appendedIdCollision(
        appended: FormalTransaction,
        baseline: FormalTransaction,
    ): Boolean {
        val baselineVersionIds = baseline.versions.mapTo(mutableSetOf()) { it.id }
        val baselinePostingSetIds = baseline.postingSets.mapTo(mutableSetOf()) { it.id }
        val baselinePostingIds =
            baseline.postingSets.flatMapTo(mutableSetOf()) { postingSet ->
                postingSet.postings.map { it.id }
            }
        val newTransactionIds =
            if (appended.transaction.id == baseline.transaction.id) {
                emptySet()
            } else {
                setOf(appended.transaction.id)
            }
        val newVersionIds =
            appended.versions
                .mapTo(mutableSetOf()) { it.id }
                .apply { removeAll(baselineVersionIds) }
        val newPostingSetIds =
            appended.postingSets
                .mapTo(mutableSetOf()) { it.id }
                .apply { removeAll(baselinePostingSetIds) }
        val newPostingIds =
            appended.postingSets
                .flatMapTo(mutableSetOf()) { postingSet ->
                    postingSet.postings.map { it.id }
                }.apply { removeAll(baselinePostingIds) }
        val existingTransactionIds = formalTransactions.mapTo(mutableSetOf()) { it.formalTransaction.transaction.id }
        val existingVersionIds =
            formalTransactions.flatMapTo(mutableSetOf()) { record ->
                record.formalTransaction.versions.map { it.id }
            }
        val existingPostingSetIds =
            formalTransactions.flatMapTo(mutableSetOf()) { record ->
                record.formalTransaction.postingSets.map { it.id }
            }
        val existingPostingIds =
            formalTransactions.flatMapTo(mutableSetOf()) { record ->
                record.formalTransaction.postingSets.flatMap { postingSet -> postingSet.postings.map { it.id } }
            }
        return newTransactionIds.any { it in existingTransactionIds } ||
            newVersionIds.any { it in existingVersionIds } ||
            newPostingSetIds.any { it in existingPostingSetIds } ||
            newPostingIds.any { it in existingPostingIds }
    }

    private fun replayBalances(): Map<AccountId, Money> =
        buildMap {
            catalog.accounts.forEach { account ->
                var total = 0L
                formalTransactions
                    .filter { it.formalTransaction.transaction.ledgerId == account.ledgerId }
                    .forEach { record ->
                        record.formalTransaction
                            .currentPostings()
                            .filter { it.accountId == account.id }
                            .forEach { posting ->
                                check(posting.amount.currency == account.currency) { "RG-12 posting currency mismatch" }
                                total = checkedAdd(total, posting.amount.minorUnits) ?: error("RG-12 balance overflow")
                            }
                    }
                put(account.id, Money.ofMinor(total, account.currency))
            }
        }

    private fun statisticsAtTextOf(formal: FormalTransaction): String =
        formal.versions
            .first { it.id == formal.transaction.currentVersionId }
            .times.statisticsAt
            .toString()

    private fun accountsById(): Map<AccountId, Account> = catalog.accounts.associateBy { it.id }

    private fun catalogAccount(id: AccountId): Account? = catalog.accounts.firstOrNull { it.id == id }

    private fun violationRejected(violation: DomainViolation): Rg12ExecutionResult =
        when (violation) {
            is CorrectTransactionVersionViolation -> {
                val reason = violation.reasonCode?.let { Rg12RejectionReason.byCode(it) }
                if (reason != null) {
                    rejected(reason, Rg12FieldPath(violation.fieldPath))
                } else {
                    rejected(Rg12RejectionReason.DOMAIN_REJECTED, Rg12FieldPath.ATTEMPTED_REQUEST_ID)
                }
            }
            is ReconciliationMatchViolation,
            is PostingReplacementViolation,
            is PostingReconciliationViolation,
            is ExplicitOperationConfirmationViolation,
            -> rejected(Rg12RejectionReason.DOMAIN_REJECTED, Rg12FieldPath.ATTEMPTED_REQUEST_ID)
            else -> rejected(Rg12RejectionReason.DOMAIN_REJECTED, Rg12FieldPath.ATTEMPTED_REQUEST_ID)
        }

    private fun domainRejected(violation: DomainViolation): Rg12ExecutionResult = violationRejected(violation)

    // ------------------------------------------------------------------ fingerprints

    private fun canonicalInput(operation: Rg12Operation): String =
        when (operation) {
            is Rg12Operation.CorrectTransactionVersion ->
                canonicalFields(
                    operation.ledgerId.value,
                    operation.action.code,
                    operation.input.requestId.value,
                    operation.input.transactionId.value,
                    operation.input.correctionKind,
                    operation.input.correctedAt.toString(),
                    operation.input.correctedAtText,
                    operation.input.explicitConfirmation.toString(),
                    operation.input.rejectChangedMatchedAsset.toString(),
                    canonicalHistoryMutation(operation.input.historyMutation),
                    operation.input.replacementPostings.joinToString("|") { item -> canonicalReplacement(item) },
                    canonicalCorrectIds(operation.ids),
                )
            is Rg12Operation.RetryIdempotentInput ->
                canonicalFields(
                    operation.ledgerId.value,
                    operation.action.code,
                    operation.input.inputId,
                )
            is Rg12Operation.InvalidInput ->
                canonicalFields(
                    operation.ledgerId.value,
                    operation.action.code,
                    operation.input.requestId.value,
                    operation.input.predicate.name,
                    operation.input.attemptedInput.entries.sortedBy { it.key }.joinToString("|") { (key, value) ->
                        "$key=${value ?: "<null>"}"
                    },
                )
        }

    private fun canonicalReplacement(item: ReplacementPostingInput): String =
        canonicalFields(
            item.sourcePostingId.value,
            item.facts.accountId.value,
            item.facts.amountText,
            canonicalCurrency(item.facts.currency),
            item.facts.role,
            item.facts.categoryId?.value,
        )

    private fun canonicalHistoryMutation(mutation: HistoryMutationInput?): String =
        if (mutation == null) {
            "N"
        } else {
            canonicalFields(
                mutation.matchId,
                mutation.statusHistory.joinToString("|") { entry ->
                    canonicalFields(entry.id, entry.sequence.toString(), entry.status.name, entry.at.toString(), entry.reason.name)
                },
            )
        }

    private fun canonicalCorrectIds(ids: Rg12CorrectIds): String =
        canonicalFields(
            ids.versionId.value,
            ids.postingSetId.value,
            ids.postingIds.joinToString("|") { it.value },
            ids.replacementLinkIds.joinToString("|"),
            ids.confirmationId,
            ids.operationId,
            ids.invalidationEntryIds.joinToString("|"),
            ids.newMatchIds.joinToString("|") { it ?: "<null>" },
            ids.newMatchEntryIds.joinToString("|") { it ?: "<null>" },
            ids.newMatchInvalidationEntryIds.joinToString("|") { it ?: "<null>" },
            ids.reconciliationFactIds.joinToString("|") { it ?: "<null>" },
            ids.consumptionRecordId,
        )

    private fun canonicalCurrency(currency: CurrencyUnit): String = "${currency.code}:${currency.precision}"

    private fun canonicalFields(vararg values: String?): String =
        buildString {
            values.forEach { value ->
                if (value == null) {
                    append("N;")
                } else {
                    append("V")
                        .append(value.length)
                        .append(':')
                        .append(value)
                        .append(';')
                }
            }
        }

    private fun renderStatusHistory(entries: List<ReconciliationMatchStatusEntry>): String =
        entries.joinToString(",", "[", "]") { entry ->
            """{"id": "${entry.id}", "sequence": ${entry.sequence}, "status": "${statusJsonName(entry.status)}", "at": "${localDateTimeText(entry.at, utcOffsetSeconds)}", "reason": "${entry.reason.jsonName}"}"""
        }

    private fun statusJsonName(status: ReconciliationMatchStatus): String =
        when (status) {
            ReconciliationMatchStatus.MATCHED -> "matched"
            ReconciliationMatchStatus.INVALIDATED -> "invalidated"
        }

    private fun accepted(ids: List<Rg12ReturnedId>) = Rg12ExecutionResult.Accepted(ids)

    private fun rejected(
        reason: Rg12RejectionReason,
        fieldPath: Rg12FieldPath,
    ) = Rg12ExecutionResult.Rejected(reason, fieldPath)

    private companion object {
        const val CORRECTION_KIND_POSTING_FACTS = "posting_facts"
        const val ROLE_EXPENSE = "expense"
        const val ROLE_MIXED_EXPENSE_ASSET_FUNDING = "mixed_expense_asset_funding"
        const val ROLE_MIXED_EXPENSE_CREDIT_FUNDING = "mixed_expense_credit_funding"
        const val DOMAIN_ENTITY_CONSUMPTION_RECORD = "consumption_record"
        const val DOMAIN_ENTITY_RECONCILIATION_MATCH = "reconciliation_match"

        /** Case timezone `Asia/Shanghai` of the frozen contract. */
        const val DEFAULT_UTC_OFFSET_SECONDS = 28_800
    }
}

private fun checkedAdd(
    left: Long,
    right: Long,
): Long? {
    if (right > 0L && left > Long.MAX_VALUE - right) return null
    if (right < 0L && left < Long.MIN_VALUE - right) return null
    return left + right
}

private fun checkedNegate(value: Long): Long? = if (value == Long.MIN_VALUE) null else -value

/**
 * Renders exact minor units as the frozen decimal amount text (e.g. `-7000` / CNY precision 2
 * -> `-70.00`), the reverse of [parseExactDecimal]. Mirrors the golden `_amount` rendering so
 * old posting facts compare byte-identically with the frozen amount texts.
 */
private fun minorToExactText(
    minor: Long,
    precision: Int,
): String {
    val negative = minor < 0L
    val magnitude = if (negative) checkedNegate(minor) ?: error("RG-12 minor overflow") else minor
    val scale = pow10Exact(precision) ?: error("RG-12 precision overflow")
    val whole = magnitude / scale
    val fraction = magnitude % scale
    return buildString {
        if (negative) append('-')
        append(whole)
        if (precision > 0) {
            append('.')
            append(fraction.toString().padStart(precision, '0'))
        }
    }
}

private const val SECONDS_PER_DAY = 86_400L

/**
 * Renders [instant] in the fixed local calendar of [utcOffsetSeconds] as the frozen
 * `YYYY-MM-DDTHH:MM:SS+HH:MM` text (case timezone `Asia/Shanghai` = `+08:00`), byte-identical
 * to the `statistics_at` / `corrected_at` / status-history `at` texts of the frozen rg-12.json.
 * `kotlin.time.Instant.toString()` renders UTC, which would shift day periods and history
 * timestamps by one day for evening instants of the case timezone (same helper as
 * Rg11Operations.kt).
 */
private fun localDateTimeText(
    instant: Instant,
    utcOffsetSeconds: Int,
): String {
    val localSeconds = instant.epochSeconds + utcOffsetSeconds
    val days = localSeconds.floorDiv(SECONDS_PER_DAY)
    val secondsOfDay = localSeconds - days * SECONDS_PER_DAY
    val (year, month, day) = civilFromDays(days)
    val hour = secondsOfDay / 3_600L
    val minute = (secondsOfDay % 3_600L) / 60L
    val second = secondsOfDay % 60L
    val offsetHours = utcOffsetSeconds / 3_600
    val offsetMinutes = kotlin.math.abs(utcOffsetSeconds % 3_600) / 60
    val sign = if (utcOffsetSeconds < 0) "-" else "+"
    return buildString {
        append(padded(year.toLong(), 4)).append('-')
        append(padded(month.toLong(), 2)).append('-')
        append(padded(day.toLong(), 2)).append('T')
        append(padded(hour, 2)).append(':')
        append(padded(minute, 2)).append(':')
        append(padded(second, 2)).append(sign)
        append(padded(kotlin.math.abs(offsetHours).toLong(), 2)).append(':')
        append(padded(offsetMinutes.toLong(), 2))
    }
}

private fun padded(
    value: Long,
    width: Int,
): String = value.toString().padStart(width, '0')

/** Fixed-offset civil-from-days (Howard Hinnant's public-domain algorithm), same arithmetic as
 * the domain's `PeriodicAllocation.kt` local calendar so both layers agree on case dates. */
private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
    val z = days + 719_468L
    val era = (if (z >= 0) z else z - 146_096L) / 146_097L
    val dayOfEra = z - era * 146_097L
    val yearOfEra = (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
    val year = yearOfEra + era * 400L
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val monthPrime = (5L * dayOfYear + 2L) / 153L
    val day = (dayOfYear - (153L * monthPrime + 2L) / 5L + 1L).toInt()
    val month = (if (monthPrime < 10L) monthPrime + 3L else monthPrime - 9L).toInt()
    val outYear = (if (month <= 2) year + 1L else year).toInt()
    return Triple(outYear, month, day)
}
