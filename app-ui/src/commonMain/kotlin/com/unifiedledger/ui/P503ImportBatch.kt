package com.unifiedledger.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unifiedledger.application.ConfirmImportCandidate
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCommitIds
import com.unifiedledger.application.ImportConfirmationId
import com.unifiedledger.application.ImportFormalIds
import com.unifiedledger.application.ImportIdSource
import com.unifiedledger.application.ImportReceipt
import com.unifiedledger.application.ImportRequestId
import com.unifiedledger.application.ImportStatusHistoryId
import com.unifiedledger.application.QueryTransactionDetail
import com.unifiedledger.application.TransactionDetailResult
import com.unifiedledger.application.UuidV7Generator
import com.unifiedledger.domain.CreditRefundOriginalExpense
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId

/**
 * P7-04.D batch confirmation and recovery shared value types and screens (D-146; spec sections
 * 3.2.3/3.3.2/6.1/6.2 table 6.2a).
 *
 * The batch is UI-session state only (R-Q09-3: 无批次表)： one explicit authorization over the
 * checked snapshot, per-item atomic spine confirms, visible partial success, no whole-batch
 * transaction promise (R-10). The authorization snapshot ([P503AppState.ImportBatchSubmitting])
 * carries the single LedgerClock sample (Q09.4, reused by every item through
 * `explicitConfirmedAt`) and the per-item requestId minted once per authorization intent; both
 * are reused verbatim by the resume continuation and the unknown-item replay — 不自动重试、不换
 * ID (R-Q10-2).
 *
 * All load-bearing decisions (item admission/revalidation, the six-variant decision-fields
 * mapping, the sequential dispatch loop with its Unknown pause, the replay request rebuild, the
 * result-summary copy) are pure functions in [P503ImportBatchPresentation]; this file only
 * renders state and forwards typed intents.
 *
 * [ImportBatchItem] is one item of the authorization snapshot: the candidate plus the requestId
 * minted for THIS authorization intent (P7-02 每个新意图分配新 requestId； the resume continuation
 * and the unknown-item check reuse it verbatim — the snapshot is the only id carrier, so
 * abandoning dissolves it and a later re-authorization mints fresh ids, Q09.3).
 */
data class ImportBatchItem(
    val candidateId: ImportCandidateId,
    val requestId: ImportRequestId,
)

/** The per-item dispatch outcome (spec section 3.3.2 / table 6.2a `ImportItemResult(item, outcome)`). */
sealed interface ImportBatchItemOutcome {
    /**
     * Confirmed: the spine Accepted the request, or an equivalent same-request replay returned
     * the original receipt (D04: 已成功项恰好一次 — the replay reports the same fact, never a
     * second transaction).
     */
    data class Confirmed(
        val receipt: ImportReceipt,
    ) : ImportBatchItemOutcome

    /** A typed spine rejection at submit time (stale fingerprint, not pending, domain gate); zero writes. */
    data class Rejected(
        val code: String,
    ) : ImportBatchItemOutcome

    /**
     * The replay check typed the request as conflicting (equivalence broken — a different
     * decision snapshot is persisted under this requestId, or the request id belongs to another
     * intent); zero writes for this attempt. Distinct from [Rejected] (an immediate submit-time
     * rejection).
     */
    data class CheckConflict(
        val code: String,
    ) : ImportBatchItemOutcome

    /**
     * The submission handed over but the result is indeterminate (session-only, spec section
     * 3.3.2): the loop paused, the item keeps its 核对入口 until the user replays it.
     */
    data object Unknown : ImportBatchItemOutcome

    /**
     * A UI-owned typed skip (P704C-SPEC-07 namespace discipline: never a `SPINE_` code): the
     * item was NOT submitted — at dispatch time the latest rows no longer admit it (stale
     * selection, no longer selectable) or its decision fields are incomplete (mixed 确认时间
     * empty, missing legs). The candidate stays `pending_confirmation`.
     */
    data class Skipped(
        val code: String,
    ) : ImportBatchItemOutcome
}

/** The replay-check outcome of one Unknown item (Q10.2: 原 receipt 判成功 / 冲突判冲突 / 仍未知). */
sealed interface ImportUnknownCheckOutcome {
    /** The replay returned the original receipt (or won the claim on a never-landed attempt): success. */
    data class Confirmed(
        val receipt: ImportReceipt,
    ) : ImportUnknownCheckOutcome

    /** A typed replay conflict; zero writes for the item. */
    data class Conflict(
        val code: String,
    ) : ImportUnknownCheckOutcome

    /** The replay itself could not resolve (infrastructure); the item keeps its check entry. */
    data object StillUnknown : ImportUnknownCheckOutcome
}

/** One item's outcome inside the retained result summary (保留结果摘要， spec section 6.2). */
data class ImportBatchResultItem(
    val item: ImportBatchItem,
    val outcome: ImportBatchItemOutcome,
)

/**
 * The most recent batch's result summary, inlined in [ImportReviewView] (spec section 6.1: 批量
 * 结果不设独立顶层态——逐项结果内联于 importReview; D04: 半提交不被误报 — the summary is per-item,
 * never a whole-batch 成功/失败 verdict). An [ImportBatchItemOutcome.Unknown] item keeps its
 * check entry here (table 6.2a: 核对入口在 IMPORT 结果摘要内).
 */
data class ImportBatchResultSummary(
    val confirmedAt: String,
    val items: List<ImportBatchResultItem>,
)

/**
 * The per-candidate-kind confirm use cases the composition root wires (spec section 9 P7-04.D
 * row: 两端构造 ConfirmImportCandidate with commitPort = the spine store, an id source, the
 * existing per-kind formal factories and the catalog). The refund variant of `credit_expense`
 * is NOT a separate use case: the credit factory dispatches on the decision-fields type
 * (CreditFlowFormalFactory), exactly like the core's confirm kind gate.
 */
class ImportConfirmUseCaseSet(
    val ordinaryFlow: ConfirmImportCandidate?,
    val transferFlow: ConfirmImportCandidate?,
    val creditExpense: ConfirmImportCandidate?,
    val creditRepayment: ConfirmImportCandidate?,
    val mixedPayment: ConfirmImportCandidate?,
)

/**
 * Production `ImportCommitIds` mint for the import confirm path (R-Q09-2 wiring family): fresh
 * UUIDv7 per winning claim callback only. The posting count is a construction parameter because
 * the frozen [ImportIdSource] port is kind-blind while the spine shape-gates the count (3 for
 * mixed_payment, 2 otherwise) — each per-kind use case carries its own mint with the kind's
 * frozen count.
 */
class UuidV7ImportCommitIdSource(
    private val generator: UuidV7Generator,
    private val postingCount: Int,
) : ImportIdSource {
    override fun next(): ImportCommitIds =
        ImportCommitIds(
            confirmationId = ImportConfirmationId(generator.next()),
            statusHistoryId = ImportStatusHistoryId(generator.next()),
            formalIds =
                ImportFormalIds(
                    transactionId = TransactionId(generator.next()),
                    versionId = TransactionVersionId(generator.next()),
                    postingSetId = PostingSetId(generator.next()),
                    postingIds = List(postingCount) { PostingId(generator.next()) },
                ),
        )
}

/**
 * The credit-refund original-expense reader both composition roots wire into
 * [com.unifiedledger.application.CreditFlowFormalFactory]: resolved through the P7-03 read model
 * ([QueryTransactionDetail]) over the CURRENT transaction state — kind EXPENSE, exactly one
 * positive (expense) posting, its account and currency (the P406 store-reader semantics over
 * the existing read port, zero new queries). `null` when the id does not exist in this ledger
 * (the domain then fails closed before any write).
 */
fun importCreditRefundOriginalExpenseProvider(
    detailQuery: QueryTransactionDetail,
): (TransactionId) -> CreditRefundOriginalExpense? =
    { transactionId ->
        val detail = (detailQuery.query(transactionId) as? TransactionDetailResult.Success)?.detail
        if (detail == null || detail.kind != TransactionKind.EXPENSE) {
            null
        } else {
            val positiveLegs = detail.legs.filter { it.amount.minorUnits > 0L }
            if (positiveLegs.size != 1) {
                null
            } else {
                val leg = positiveLegs.single()
                CreditRefundOriginalExpense(
                    transactionId = detail.transactionId,
                    ledgerId = detail.ledgerId,
                    kind = detail.kind,
                    currencyCode = leg.amount.currency.code,
                    currentExpensePostingAccountId = leg.accountId,
                )
            }
        }
    }

/**
 * One authorization-snapshot item with its dispatch outcome (P7-04.D). `null` outcome = still
 * awaiting dispatch; [ImportBatchItem] is the immutable identity+requestId pair.
 */
data class ImportBatchSubmittingItem(
    val item: ImportBatchItem,
    val outcome: ImportBatchItemOutcome? = null,
)

/**
 * P7-04.D: the 授权快照确认页 (spec sections 3.2.3/6.1). Presents the checked item list with each
 * item's decision-field summary, the explicit per-item submission semantics (R-10: 授权确认页
 * 明示逐项提交语义) and the authorization action. Pure presentation — the item enumeration and
 * every copy line come from [P503ImportBatchPresentation].
 */
@Composable
internal fun P503ImportBatchConfirmScreen(
    state: P503AppState.ImportBatchConfirm,
    parseAmount: com.unifiedledger.application.ParseManualExpenseAmount,
    defaultCurrency: com.unifiedledger.domain.CurrencyUnit,
    onAuthorize: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("批量确认", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onCancel) { Text("返回") }
        }
        Spacer(Modifier.height(8.dp))
        // R-10: the authorization page states the per-item semantics up front.
        Text(
            "系统将逐项入账：每项独立成功或失败，可见部分成功的结果，不承诺整批同时成功。",
            style = MaterialTheme.typography.bodyMedium,
        )
        // P704D-SPEC-03 (Q09.4): the confirmation-time note — one authorization-time sample for
        // the whole batch; the mixed form's time text is only the completeness marker.
        Text(
            IMPORT_BATCH_CONFIRM_TIME_NOTE,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        val items = importBatchConfirmItems(state.overview.importReview)
        if (items.isEmpty()) {
            Text("尚未勾选任何候选。", style = MaterialTheme.typography.bodyMedium)
        } else {
            items.forEach { entry ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(importBatchConfirmItemTitle(entry), style = MaterialTheme.typography.bodyMedium)
                    Text(importBatchConfirmItemSummary(entry, parseAmount, defaultCurrency), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onAuthorize,
            enabled = items.isNotEmpty(),
            modifier = Modifier.semantics { contentDescription = "授权逐项入账" },
        ) {
            Text("确认入账（${items.size} 项）")
        }
    }
}

/**
 * P7-04.D: the per-item dispatch screen (spec sections 3.3.2/6.1, table 6.2a). Shows the
 * per-item progress (待派发/已入账/拒绝/跳过/未知+核对入口). While the run is active there is no
 * exit (沿既有 Submitting 语义， system back intercepted); the explicit 继续派发/结束批量
 * (`ResumeImportBatchDispatch`) and 放弃本次批量 (`AbandonImportBatch`) exits render whenever the
 * loop has stopped without being able to auto-leave — while paused (an Unknown landed) and in
 * the residual stopped sub-state (a resumed run finished cleanly but an Unknown item remains —
 * [importBatchExitAvailable], P704D-SPEC-01).
 */
@Composable
internal fun P503ImportBatchSubmittingScreen(
    state: P503AppState.ImportBatchSubmitting,
    onResume: () -> Unit,
    onAbandon: () -> Unit,
    onCheckUnknownItem: (ImportCandidateId) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Text("逐项入账中", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("每项独立成功或失败；某项结果未知时暂停后续各项，请核对后再继续或放弃。", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        state.items.forEach { itemState ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("候选 ${itemState.item.candidateId.value}", style = MaterialTheme.typography.bodyMedium)
                Text(importBatchItemProgressText(itemState), style = MaterialTheme.typography.bodySmall)
                if (itemState.outcome is ImportBatchItemOutcome.Unknown) {
                    Button(
                        onClick = { onCheckUnknownItem(itemState.item.candidateId) },
                        modifier = Modifier.semantics { contentDescription = "核对未知项" },
                    ) {
                        Text("核对该项")
                    }
                }
            }
        }
        if (importBatchExitAvailable(state)) {
            Spacer(Modifier.height(12.dp))
            // P704D-SPEC-01: the exits render both while paused and in the residual stopped
            // sub-state (the resumed run finished cleanly but an Unknown item remains — the
            // reducer cannot auto-leave an Unknown, so these affordances are the only
            // in-session exit besides the check actions).
            Text(
                importBatchExitBannerText(state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Row {
                Button(
                    onClick = onResume,
                    modifier = Modifier.semantics { contentDescription = importBatchResumeActionText(state) },
                ) {
                    Text(importBatchResumeActionText(state))
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onAbandon,
                    modifier = Modifier.semantics { contentDescription = "放弃本次批量" },
                ) {
                    Text("放弃本次批量")
                }
            }
        }
    }
}
