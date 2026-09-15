package com.unifiedledger.ui

import com.unifiedledger.application.ConfirmImportCandidate
import com.unifiedledger.application.ImportCandidateConfirmRequest
import com.unifiedledger.application.ImportCandidateDecisionResult
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportConfirmDecisionFields
import com.unifiedledger.application.ImportRequestIdentity
import com.unifiedledger.application.ImportReviewRow
import com.unifiedledger.application.ImportReviewRowsResult
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId

// P7-04.D pure presentation decisions (D-146; spec sections 3.2.3/3.3.2/4.5.3/6.2 table 6.2a).
// The composables in P503ImportBatch.kt stay thin renderers; every load-bearing decision — the
// confirm-page item enumeration, the six-variant decision-fields mapping, the dispatch-time
// admission (派发前按最新行重校验), the sequential dispatch loop with its Unknown pause, the
// replay-check request rebuild, and the result-summary copy (半提交不误报， D04) — is a pure
// function here, assertable in the app-ui JVM tests without a Compose UI-test harness. No
// Compose or platform API is used in this file.

/** UI-owned typed skip codes (P704C-SPEC-07: never a `SPINE_` diagnostic namespace borrow). */
internal const val IMPORT_BATCH_REVALIDATION_UNAVAILABLE = "IMPORT_BATCH_REVALIDATION_UNAVAILABLE"
internal const val IMPORT_BATCH_ITEM_NOT_CONFIRMABLE = "IMPORT_BATCH_ITEM_NOT_CONFIRMABLE"
internal const val IMPORT_BATCH_CONFIRM_UNWIRED = "IMPORT_BATCH_CONFIRM_UNWIRED"
internal const val IMPORT_BATCH_DECISION_INCOMPLETE = "IMPORT_BATCH_DECISION_INCOMPLETE"

/**
 * P704D-SPEC-02: the per-run pre-phase (the review-list re-read or the per-kind use-case
 * factory) failed with an unexpected exception — a typed visible per-item result replaces a
 * stranded run (基础设施失败成为类型化/可见结果而非搁浅流程， spec section 3.3.2 posture): every
 * still-undispatched item records this skip, the batch determinately completes (all terminal →
 * the reducer auto-leaves with the typed summary) and the candidates stay `pending_confirmation`.
 */
internal const val IMPORT_BATCH_DISPATCH_UNAVAILABLE = "IMPORT_BATCH_DISPATCH_UNAVAILABLE"

/**
 * P704D-SPEC-03 (Q09.4): the authorization page's confirmation-time note — the recorded
 * confirmation time is the ONE LedgerClock sample of the authorization action (reused by every
 * item through `explicitConfirmedAt`); the mixed form's confirmation-time text is only the E13
 * completeness marker, the recorded value is always the authorization sample.
 */
internal val IMPORT_BATCH_CONFIRM_TIME_NOTE =
    "记录的确认时间为本次授权动作的时间（系统在授权时取一次时钟读数，逐项入账均复用该时间）；" +
        "混合支付表单中的确认时间文本仅用于补全决策校验，实际记录值仍为该授权时间。"

/** Resolves one row's currency for decision parsing; null when the row's currency facts are absent. */
private fun rowCurrency(row: ImportReviewRow): CurrencyUnit? =
    row.currencyCode?.let { code ->
        row.currencyPrecision?.let { precision -> CurrencyUnit(code, precision) }
    }

/**
 * One entry of the 授权快照确认页： the candidate, its current row (`null` when a refresh removed
 * the selected id from the list — the item will be typed-skipped at dispatch) and its in-session
 * decision draft (the summary the page presents).
 */
internal data class ImportBatchConfirmItem(
    val candidateId: ImportCandidateId,
    val row: ImportReviewRow?,
    val draft: ImportDecisionDraft,
)

/**
 * The confirm-page enumeration (pure): the selected candidates in list-row order, with any
 * selected ids that no longer appear in the rows appended after them (sorted by id for a
 * deterministic page). `null` view (projection not loaded) or an empty selection yield an empty
 * page.
 */
internal fun importBatchConfirmItems(view: ImportReviewView?): List<ImportBatchConfirmItem> {
    if (view == null) return emptyList()
    val selected = view.selectedCandidateIds
    if (selected.isEmpty()) return emptyList()
    val present = view.rows.filter { it.candidateId in selected }
    val absentIds = selected - present.map { it.candidateId }.toSet()
    return present.map { ImportBatchConfirmItem(it.candidateId, it, view.decisionDrafts[it.candidateId] ?: ImportDecisionDraft()) } +
        absentIds.sortedBy { it.value }.map { ImportBatchConfirmItem(it, null, view.decisionDrafts[it] ?: ImportDecisionDraft()) }
}

/**
 * The same deterministic candidate ordering the reducer uses for the authorization snapshot
 * items (rows order first, then selected-but-absent ids by value).
 */
internal fun importBatchSnapshotCandidateIds(view: ImportReviewView): List<ImportCandidateId> {
    val selected = view.selectedCandidateIds
    val present = view.rows.filter { it.candidateId in selected }.map { it.candidateId }
    val absent = (selected - present.toSet()).sortedBy { it.value }
    return present + absent
}

/** The title line of one confirm-page item. */
internal fun importBatchConfirmItemTitle(entry: ImportBatchConfirmItem): String =
    if (entry.row == null) {
        "候选 ${entry.candidateId.value}（已不在当前清单）"
    } else {
        "候选 ${entry.candidateId.value}：${importCandidateAmountText(entry.row)}"
    }

/**
 * The decision summary of one confirm-page item: the per-kind face requirements and whether the
 * current draft completes them (the dispatch-time admission re-checks the same facts; an
 * incomplete item is typed-skipped, never submitted — 派发前重校验).
 */
internal fun importBatchConfirmItemSummary(
    entry: ImportBatchConfirmItem,
    parseAmount: ParseManualExpenseAmount,
    defaultCurrency: CurrencyUnit,
): String {
    val row = entry.row ?: return "该项已不在当前清单，派发时将以类型化结果跳过，不会提交。"
    val face =
        importDecisionFormFace(row)
            ?: return "该候选无决策表单（来源事实不完整或不可确认），派发时将以类型化结果跳过。"
    val required =
        listOfNotNull(
            "分类".takeIf { face.requiresCategory },
            "资金账户".takeIf { face.requiresFundingAccount },
            "转出账户".takeIf { face.requiresFromAccount },
            "转入账户".takeIf { face.requiresToAccount },
            "信用账户".takeIf { face.requiresCreditAccount },
            "原交易".takeIf { face.requiresOriginalTransaction },
            "资产腿账户".takeIf { face.requiresAssetAccount },
            "两腿金额".takeIf { face.legAmountsEditable },
            "确认时间".takeIf { face.requiresConfirmedAt },
        )
    return if (isImportBatchDecisionComplete(row, entry.draft, parseAmount, rowCurrency(row) ?: defaultCurrency)) {
        "需补决策：${required.joinToString("、")}；当前已补全。"
    } else {
        "需补决策：${required.joinToString("、")}；当前未补全，派发时将以类型化结果跳过。"
    }
}

/** The progress copy of one submitting item. */
internal fun importBatchItemProgressText(itemState: ImportBatchSubmittingItem): String =
    when (val outcome = itemState.outcome) {
        null -> "待派发。"
        is ImportBatchItemOutcome.Confirmed -> "已入账。"
        is ImportBatchItemOutcome.Rejected -> "拒绝（诊断码 ${outcome.code}）；未写入。"
        is ImportBatchItemOutcome.CheckConflict -> "核对冲突（诊断码 ${outcome.code}）；未写入。"
        is ImportBatchItemOutcome.Skipped -> "已跳过，未提交（原因码 ${outcome.code}）。"
        ImportBatchItemOutcome.Unknown -> "结果未知，可核对。"
    }

/** A terminal per-item outcome: everything except `null` (awaiting dispatch) and [ImportBatchItemOutcome.Unknown]. */
internal fun isImportBatchOutcomeTerminal(outcome: ImportBatchItemOutcome?): Boolean = outcome != null && outcome !is ImportBatchItemOutcome.Unknown

/** Whether any snapshot item is still awaiting dispatch (null outcome). */
internal fun importBatchHasUndispatchedItems(state: P503AppState.ImportBatchSubmitting): Boolean = state.items.any { it.outcome == null }

/** Whether any snapshot item still carries an in-session Unknown outcome (Q10.2). */
internal fun importBatchHasUnknownItems(state: P503AppState.ImportBatchSubmitting): Boolean = state.items.any { it.outcome is ImportBatchItemOutcome.Unknown }

/**
 * P704D-SPEC-01: whether the dispatch screen must render the explicit Resume/Abandon exits.
 * Both render while the loop is paused (Unknown landed); they ALSO render in the residual
 * stopped sub-state — the loop finished a resumed run cleanly (no undispatched item) but an
 * earlier Unknown item remains (its check came back 仍未知)： the reducer cannot auto-leave (an
 * Unknown item is not terminal), so without these affordances the state would have no
 * in-session exit (system back is intercepted; a persistently failing check would deadlock the
 * UI until restart). An active run (undispatched items, not paused) renders no exits, and a
 * fully terminal state never persists (the reducer auto-leaves on the last terminal outcome).
 */
internal fun importBatchExitAvailable(state: P503AppState.ImportBatchSubmitting): Boolean =
    state.dispatchPaused ||
        (!importBatchHasUndispatchedItems(state) && importBatchHasUnknownItems(state))

/**
 * P704D-SPEC-01: the Resume affordance's copy follows its reducer effect — continuing the
 * remaining items while any exist, or ending the batch (leaving to the overview with the
 * still-Unknown items carried in the retained result summary, their check entries intact).
 */
internal fun importBatchResumeActionText(state: P503AppState.ImportBatchSubmitting): String = if (importBatchHasUndispatchedItems(state)) "继续派发剩余各项" else "结束本次批量并查看结果"

/** P704D-SPEC-01: the paused/residual banner copy (both render the explicit exits). */
internal fun importBatchExitBannerText(state: P503AppState.ImportBatchSubmitting): String =
    if (state.dispatchPaused) {
        "某项结果未知，已暂停后续各项；可核对该项后再继续，或放弃本次批量。"
    } else {
        "本次批量已派发完毕，仍有结果未知的项；可核对，或结束本次批量（未知项保留核对入口）。"
    }

/**
 * The per-kind confirm use case of one row (pure; the credit kinds share the credit use case —
 * the factory dispatches on the decision-fields type exactly like the core's confirm kind gate).
 * `null` for kinds without a decision form (they are not selectable to begin with) and for
 * unwired sets.
 */
internal fun importConfirmUseCaseFor(
    useCases: ImportConfirmUseCaseSet?,
    row: ImportReviewRow,
): ConfirmImportCandidate? =
    when (row.candidateKind) {
        "ordinary_flow" -> useCases?.ordinaryFlow
        "transfer_flow" -> useCases?.transferFlow
        "credit_expense" -> useCases?.creditExpense
        "credit_repayment" -> useCases?.creditRepayment
        "mixed_payment" -> useCases?.mixedPayment
        else -> null
    }

/**
 * The six-variant decision-fields mapping (spec section 4.5.3; `ImportConfirmDecisionFields`
 * shapes, zero new semantics). `null` when a required field is missing or a mixed leg amount is
 * blank/unparsable or the mixed confirmation-time marker is blank — incomplete decision data,
 * never fabricated (E13: the candidate stays pending; the dispatch admission skips it typed).
 */
internal fun importBatchDecisionFields(
    row: ImportReviewRow,
    draft: ImportDecisionDraft,
    parseAmount: ParseManualExpenseAmount,
    currency: CurrencyUnit,
): ImportConfirmDecisionFields? =
    when (row.candidateKind) {
        "ordinary_flow" ->
            if (draft.categoryId == null || draft.fundingAccountId == null) {
                null
            } else {
                ImportConfirmDecisionFields.OrdinaryFlow(draft.categoryId, draft.fundingAccountId)
            }
        "transfer_flow" ->
            if (draft.fromAccountId == null || draft.toAccountId == null) {
                null
            } else {
                ImportConfirmDecisionFields.TransferFlow(draft.fromAccountId, draft.toAccountId)
            }
        "credit_expense" ->
            if (row.paymentProfileVariant == "credit_expense_refund") {
                if (draft.categoryId == null || draft.creditLiabilityAccountId == null || draft.originalTransactionId == null) {
                    null
                } else {
                    ImportConfirmDecisionFields.CreditExpenseRefundFlow(draft.categoryId, draft.creditLiabilityAccountId, draft.originalTransactionId)
                }
            } else {
                if (draft.categoryId == null || draft.creditLiabilityAccountId == null) {
                    null
                } else {
                    ImportConfirmDecisionFields.CreditExpenseFlow(draft.categoryId, draft.creditLiabilityAccountId)
                }
            }
        "credit_repayment" ->
            if (draft.assetAccountId == null || draft.creditLiabilityAccountId == null) {
                null
            } else {
                ImportConfirmDecisionFields.CreditRepaymentFlow(draft.assetAccountId, draft.creditLiabilityAccountId)
            }
        "mixed_payment" -> {
            if (draft.categoryId == null || draft.assetAccountId == null || draft.creditLiabilityAccountId == null) {
                null
            } else {
                val assetLeg = parseExactMinor(draft.assetLegAmountText, parseAmount, currency) ?: return null
                val creditLeg = parseExactMinor(draft.creditLegAmountText, parseAmount, currency) ?: return null
                // E13 completeness marker: a blank confirmation-time text is incomplete decision
                // data (the submitted value itself is the authorization clock sample, Q09.4 — the
                // C-batch form's own note: 「D 批提交时取值」).
                if (draft.confirmedAtText.isBlank()) {
                    null
                } else {
                    ImportConfirmDecisionFields.MixedPaymentFlow(draft.categoryId, draft.assetAccountId, draft.creditLiabilityAccountId, assetLeg, creditLeg)
                }
            }
        }
        else -> null
    }

private fun parseExactMinor(
    text: String,
    parseAmount: ParseManualExpenseAmount,
    currency: CurrencyUnit,
): Long? =
    if (text.isBlank()) {
        null
    } else {
        when (val parsed = parseAmount.parse(text, currency)) {
            is ParseManualExpenseAmount.Result.Valid -> parsed.minorUnits
            is ParseManualExpenseAmount.Result.Invalid -> null
        }
    }

/**
 * Whether the draft completes the row's decision face (the confirm-page summary and the
 * dispatch-time admission share it): every face-required field present, the mixed legs both
 * parse exactly, and the mixed confirmation-time marker non-blank.
 */
internal fun isImportBatchDecisionComplete(
    row: ImportReviewRow,
    draft: ImportDecisionDraft,
    parseAmount: ParseManualExpenseAmount,
    currency: CurrencyUnit,
): Boolean {
    val face = importDecisionFormFace(row) ?: return false
    val fieldsComplete =
        (!face.requiresCategory || draft.categoryId != null) &&
            (!face.requiresFundingAccount || draft.fundingAccountId != null) &&
            (!face.requiresFromAccount || draft.fromAccountId != null) &&
            (!face.requiresToAccount || draft.toAccountId != null) &&
            (!face.requiresCreditAccount || draft.creditLiabilityAccountId != null) &&
            (!face.requiresOriginalTransaction || draft.originalTransactionId != null) &&
            (!face.requiresAssetAccount || draft.assetAccountId != null) &&
            (!face.requiresConfirmedAt || draft.confirmedAtText.isNotBlank())
    if (!fieldsComplete) return false
    if (face.legAmountsEditable) {
        if (parseExactMinor(draft.assetLegAmountText, parseAmount, currency) == null) return false
        if (parseExactMinor(draft.creditLegAmountText, parseAmount, currency) == null) return false
    }
    return true
}

// ------------------------------------------------------------------ dispatch-time admission (派发前重校验)

/** The admission verdict of one snapshot item against the latest review rows. */
internal sealed interface ImportBatchItemAdmission {
    /** The item is admissible: the built confirm request plus the per-kind use case to execute. */
    data class Submit(
        val useCase: ConfirmImportCandidate,
        val request: ImportCandidateConfirmRequest,
    ) : ImportBatchItemAdmission

    /** A typed UI-owned skip; the item is never submitted and stays `pending_confirmation`. */
    data class Skip(
        val code: String,
    ) : ImportBatchItemAdmission
}

/**
 * The dispatch-time revalidation (frozen review-forward hint: a refreshed selection may contain
 * ids the latest rows no longer carry). Against the THEN-LATEST rows each item must still be a
 * confirmable candidate with a complete decision form; anything else is a typed skip, never a
 * submit. The `expectedContentHash` is the latest row's hash (spec: `row.contentHash`); the
 * `explicitConfirmedAt` is the authorization clock sample for every kind (Q09.4 — explicit
 * provenance, required for mixed).
 */
internal fun admitImportBatchItem(
    item: ImportBatchItem,
    rows: ImportReviewRowsResult,
    drafts: Map<ImportCandidateId, ImportDecisionDraft>,
    confirmedAt: String,
    useCases: ImportConfirmUseCaseSet?,
    ledgerId: LedgerId,
    parseAmount: ParseManualExpenseAmount,
    defaultCurrency: CurrencyUnit,
): ImportBatchItemAdmission {
    if (rows !is ImportReviewRowsResult.Rows) return ImportBatchItemAdmission.Skip(IMPORT_BATCH_REVALIDATION_UNAVAILABLE)
    val row =
        rows.rows.firstOrNull { it.candidateId == item.candidateId }
            ?: return ImportBatchItemAdmission.Skip(IMPORT_BATCH_ITEM_NOT_CONFIRMABLE)
    if (!classifyImportCandidate(row).selectable) return ImportBatchItemAdmission.Skip(IMPORT_BATCH_ITEM_NOT_CONFIRMABLE)
    val useCase = importConfirmUseCaseFor(useCases, row) ?: return ImportBatchItemAdmission.Skip(IMPORT_BATCH_CONFIRM_UNWIRED)
    if (importDecisionFormFace(row) == null) return ImportBatchItemAdmission.Skip(IMPORT_BATCH_ITEM_NOT_CONFIRMABLE)
    val currency = rowCurrency(row) ?: defaultCurrency
    val decisionFields =
        importBatchDecisionFields(row, drafts[item.candidateId] ?: ImportDecisionDraft(), parseAmount, currency)
            ?: return ImportBatchItemAdmission.Skip(IMPORT_BATCH_DECISION_INCOMPLETE)
    val request =
        ImportCandidateConfirmRequest(
            identity = ImportRequestIdentity(ledgerId, item.requestId),
            candidateId = item.candidateId,
            expectedContentHash = row.contentHash,
            explicitConfirmedAt = confirmedAt,
            decisionFields = decisionFields,
        )
    return ImportBatchItemAdmission.Submit(useCase, request)
}

/** Maps one spine confirm result to the per-item outcome (an exception maps to Unknown at the caller). */
internal fun mapImportConfirmOutcome(result: ImportCandidateDecisionResult): ImportBatchItemOutcome =
    when (result) {
        is ImportCandidateDecisionResult.Accepted -> ImportBatchItemOutcome.Confirmed(result.receipt)
        is ImportCandidateDecisionResult.NoChange -> ImportBatchItemOutcome.Confirmed(result.receipt)
        is ImportCandidateDecisionResult.Rejected -> ImportBatchItemOutcome.Rejected(result.diagnostic.code)
    }

/** Maps one replay result to the check outcome (Q10.2: 原 receipt 判成功 / 冲突判冲突). */
internal fun mapImportUnknownCheckOutcome(result: ImportCandidateDecisionResult): ImportUnknownCheckOutcome =
    when (result) {
        is ImportCandidateDecisionResult.Accepted -> ImportUnknownCheckOutcome.Confirmed(result.receipt)
        is ImportCandidateDecisionResult.NoChange -> ImportUnknownCheckOutcome.Confirmed(result.receipt)
        is ImportCandidateDecisionResult.Rejected -> ImportUnknownCheckOutcome.Conflict(result.diagnostic.code)
    }

// ------------------------------------------------------------------ unknown-item replay check (Q10.2)

/** The rebuilt equivalent replay of one Unknown item: the per-kind use case plus the identical request. */
internal data class ImportBatchCheckContext(
    val useCase: ConfirmImportCandidate,
    val request: ImportCandidateConfirmRequest,
)

/**
 * Rebuilds the equivalent replay request for one Unknown item against the latest rows: the SAME
 * requestId (不换 ID)， the row's content hash (sources are immutable, so it equals the dispatched
 * hash), the unchanged in-session decision fields and the SAME authorization clock sample — the
 * request is equivalent to the original dispatch, so the spine's claim-gated `resolveConfirm`
 * returns the original receipt (判成功)， types the equivalence break (判冲突) or the attempt re-runs
 * cleanly on a never-landed claim. The selectability gate is deliberately NOT applied: an item
 * whose submission actually landed reads `confirmed` in the rows and must still replay to its
 * original receipt. `null` when the row is unreadable or the context cannot be rebuilt — the
 * caller keeps the item Unknown (仍未知， the check entry stays).
 */
internal fun importUnknownCheckContext(
    item: ImportBatchItem,
    rows: ImportReviewRowsResult,
    drafts: Map<ImportCandidateId, ImportDecisionDraft>,
    confirmedAt: String,
    useCases: ImportConfirmUseCaseSet?,
    ledgerId: LedgerId,
    parseAmount: ParseManualExpenseAmount,
    defaultCurrency: CurrencyUnit,
): ImportBatchCheckContext? {
    if (rows !is ImportReviewRowsResult.Rows) return null
    val row = rows.rows.firstOrNull { it.candidateId == item.candidateId } ?: return null
    val useCase = importConfirmUseCaseFor(useCases, row) ?: return null
    val currency = rowCurrency(row) ?: defaultCurrency
    val decisionFields =
        importBatchDecisionFields(row, drafts[item.candidateId] ?: ImportDecisionDraft(), parseAmount, currency)
            ?: return null
    return ImportBatchCheckContext(
        useCase,
        ImportCandidateConfirmRequest(
            identity = ImportRequestIdentity(ledgerId, item.requestId),
            candidateId = item.candidateId,
            expectedContentHash = row.contentHash,
            explicitConfirmedAt = confirmedAt,
            decisionFields = decisionFields,
        ),
    )
}

/**
 * Runs one replay check with the injected execute callback (the host supplies the real use-case
 * execution off the UI thread): a rebuilt context replays and maps the verdict; a null context
 * or a thrown execution stays Unknown (仍未知， the check entry stays; 不自动重试).
 */
internal fun runImportUnknownItemCheck(
    context: ImportBatchCheckContext?,
    execute: (ConfirmImportCandidate, ImportCandidateConfirmRequest) -> ImportCandidateDecisionResult,
): ImportUnknownCheckOutcome =
    if (context == null) {
        ImportUnknownCheckOutcome.StillUnknown
    } else {
        try {
            mapImportUnknownCheckOutcome(execute(context.useCase, context.request))
        } catch (failure: Exception) {
            ImportUnknownCheckOutcome.StillUnknown
        }
    }

/**
 * Resolves the check target for one candidate id from the current state: an Unknown item inside
 * the submitting snapshot, or an Unknown item inside the retained overview result summary
 * (table 6.2a: 核对入口在 IMPORT 结果摘要内)， paired with the authorization clock sample it must
 * replay with. `null` when no Unknown item matches.
 */
internal fun resolveImportUnknownCheckTarget(
    state: P503AppState,
    candidateId: ImportCandidateId,
): Pair<ImportBatchItem, String>? =
    when (state) {
        is P503AppState.ImportBatchSubmitting ->
            state.items
                .firstOrNull { it.item.candidateId == candidateId && it.outcome is ImportBatchItemOutcome.Unknown }
                ?.let { it.item to state.confirmedAt }
        is P503AppState.OverviewEmpty ->
            state.importReview
                ?.batchResult
                ?.items
                ?.firstOrNull { it.item.candidateId == candidateId && it.outcome is ImportBatchItemOutcome.Unknown }
                ?.let { summaryItem -> summaryItem.item to state.importReview!!.batchResult!!.confirmedAt }
        else -> null
    }

// ------------------------------------------------------------------ per-run pre-phase guard (P704D-QUAL-01/SPEC-02)

/**
 * P704D-QUAL-01/SPEC-02: the guarded pre-decision of one dispatch run. The list re-read and
 * the per-kind use-case factory run BEFORE the loop's execute-catch boundary; an unexpected
 * throw there must not kill the run's coroutine (the single-flight slot would leak and the
 * state would strand in the dispatch page). The loaders are injected so the guard is testable;
 * a throwing list read maps to the existing revalidation code and a throwing factory maps to
 * the run-level [IMPORT_BATCH_DISPATCH_UNAVAILABLE] code.
 */
internal sealed interface ImportBatchDispatchPrePhase {
    data class Ready(
        val rows: ImportReviewRowsResult,
        val useCases: ImportConfirmUseCaseSet?,
    ) : ImportBatchDispatchPrePhase

    data class Failed(
        val code: String,
    ) : ImportBatchDispatchPrePhase
}

internal fun importBatchDispatchPrePhase(
    loadRows: () -> ImportReviewRowsResult,
    loadUseCases: () -> ImportConfirmUseCaseSet?,
): ImportBatchDispatchPrePhase {
    val rows =
        try {
            loadRows()
        } catch (failure: Exception) {
            return ImportBatchDispatchPrePhase.Failed(IMPORT_BATCH_REVALIDATION_UNAVAILABLE)
        }
    val useCases =
        try {
            loadUseCases()
        } catch (failure: Exception) {
            return ImportBatchDispatchPrePhase.Failed(IMPORT_BATCH_DISPATCH_UNAVAILABLE)
        }
    return ImportBatchDispatchPrePhase.Ready(rows, useCases)
}

/**
 * P704D-QUAL-01/SPEC-02: the typed visible results of a failed pre-phase — one
 * [ImportBatchItemOutcome.Skipped] per still-undispatched item, dispatched through the
 * ordinary per-item channel so the batch determinately reaches the all-terminal leave (no
 * dead end; the candidates stay `pending_confirmation` and can be re-authorized).
 */
internal fun importBatchRunLevelFailureResults(
    items: List<ImportBatchItem>,
    code: String,
): List<ImportBatchDispatchResult> = items.map { ImportBatchDispatchResult(it, ImportBatchItemOutcome.Skipped(code)) }

// ------------------------------------------------------------------ sequential dispatch loop (逐项原子派发)

/** One per-item result the loop dispatches back to the host (and the reducer event's payload). */
internal data class ImportBatchDispatchResult(
    val item: ImportBatchItem,
    val outcome: ImportBatchItemOutcome,
)

/** The loop run's terminal verdict. */
internal sealed interface ImportBatchDispatchRun {
    /** Every item was dispatched and got a determinate outcome; the host re-reads the list. */
    data object Completed : ImportBatchDispatchRun

    /** An Unknown outcome paused the loop at [unknownItem]; remaining items keep null outcomes. */
    data class PausedAtUnknown(
        val unknownItem: ImportBatchItem,
    ) : ImportBatchDispatchRun
}

/**
 * The sequential per-item dispatch loop (spec sections 3.2.3/3.3.2): strictly ordered, one
 * independent spine confirm per item (claim-first, atomic), visible partial success — a typed
 * rejection or skip never stops the later items; an Unknown outcome PAUSES the loop (后续派发暂停，
 * remaining items keep awaiting). The loop only ever receives the items still lacking an outcome
 * (已核对项/已终态项不重复派发)； the `rows` are the latest list read at the run's start (during a
 * run every row-changing reducer event is absorbed, so the loop itself is the only in-session
 * writer and the read is current for every remaining item).
 */
internal class ImportBatchDispatchLoop(
    private val items: List<ImportBatchItem>,
    private val rows: ImportReviewRowsResult,
    private val drafts: Map<ImportCandidateId, ImportDecisionDraft>,
    private val confirmedAt: String,
    private val useCases: ImportConfirmUseCaseSet?,
    private val ledgerId: LedgerId,
    private val parseAmount: ParseManualExpenseAmount,
    private val defaultCurrency: CurrencyUnit,
) {
    /**
     * Runs the loop. [dispatch] receives every per-item result as it is produced (the host hops
     * each one back to the main dispatcher); [execute] runs one spine confirm (the host supplies
     * the real use-case call; an exception it throws maps to the Unknown pause). The return
     * verdict tells the host whether to re-read the list (Completed) or leave it paused.
     */
    fun run(
        dispatch: (ImportBatchDispatchResult) -> Unit,
        execute: (ConfirmImportCandidate, ImportCandidateConfirmRequest) -> ImportCandidateDecisionResult,
    ): ImportBatchDispatchRun {
        for (item in items) {
            val outcome =
                when (val admission = admitImportBatchItem(item, rows, drafts, confirmedAt, useCases, ledgerId, parseAmount, defaultCurrency)) {
                    is ImportBatchItemAdmission.Skip -> ImportBatchItemOutcome.Skipped(admission.code)
                    is ImportBatchItemAdmission.Submit ->
                        try {
                            mapImportConfirmOutcome(execute(admission.useCase, admission.request))
                        } catch (failure: Exception) {
                            // 逐项提交未知：会话内 Unknown（提交交接后结果不可判）—— pause, never auto-retry.
                            ImportBatchItemOutcome.Unknown
                        }
                }
            dispatch(ImportBatchDispatchResult(item, outcome))
            if (outcome is ImportBatchItemOutcome.Unknown) {
                return ImportBatchDispatchRun.PausedAtUnknown(item)
            }
        }
        return ImportBatchDispatchRun.Completed
    }
}

// ------------------------------------------------------------------ result summary copy (D04: 半提交不误报)

/**
 * The copy lines of the retained batch result summary: a per-item breakdown only — the summary
 * never claims the whole batch 成功 or 失败 (R-10/D04: 可见部分成功). Unknown items keep their check
 * affordance note (核对入口在结果摘要内).
 */
internal fun importBatchResultLines(summary: ImportBatchResultSummary): List<String> {
    val confirmed = summary.items.count { it.outcome is ImportBatchItemOutcome.Confirmed }
    val rejected = summary.items.count { it.outcome is ImportBatchItemOutcome.Rejected }
    val conflicts = summary.items.count { it.outcome is ImportBatchItemOutcome.CheckConflict }
    val skipped = summary.items.count { it.outcome is ImportBatchItemOutcome.Skipped }
    val unknown = summary.items.count { it.outcome is ImportBatchItemOutcome.Unknown }
    val lines = mutableListOf<String>()
    if (summary.items.isEmpty()) {
        return listOf("最近批量结果：本次批量没有已提交的项。")
    }
    lines +=
        "最近批量结果（授权时间 ${summary.confirmedAt}）：已入账 $confirmed 项，拒绝 $rejected 项，核对冲突 $conflicts 项，跳过 $skipped 项，未知 $unknown 项。"
    summary.items.forEach { entry ->
        lines +=
            when (val outcome = entry.outcome) {
                is ImportBatchItemOutcome.Confirmed ->
                    "候选 ${entry.item.candidateId.value}：已入账。"
                is ImportBatchItemOutcome.Rejected ->
                    "候选 ${entry.item.candidateId.value}：拒绝（诊断码 ${outcome.code}），未写入。"
                is ImportBatchItemOutcome.CheckConflict ->
                    "候选 ${entry.item.candidateId.value}：核对冲突（诊断码 ${outcome.code}），未写入。"
                is ImportBatchItemOutcome.Skipped ->
                    "候选 ${entry.item.candidateId.value}：已跳过，未提交（原因码 ${outcome.code}）。"
                ImportBatchItemOutcome.Unknown ->
                    "候选 ${entry.item.candidateId.value}：结果未知，可核对。"
            }
    }
    return lines
}
