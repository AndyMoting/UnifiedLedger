package com.unifiedledger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unifiedledger.application.ExpenseCategoryOption
import com.unifiedledger.application.ImportCandidateDetailResult
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportDuplicateCandidateId
import com.unifiedledger.application.ImportDuplicateReviewId
import com.unifiedledger.application.ImportDuplicateReviewsResult
import com.unifiedledger.application.ImportDuplicateStatus
import com.unifiedledger.application.ImportFileIntakeOutcome
import com.unifiedledger.application.ImportFormatId
import com.unifiedledger.application.ImportRequestId
import com.unifiedledger.application.ImportReviewRow
import com.unifiedledger.application.ImportReviewRowsResult
import com.unifiedledger.application.ImportStatusHistoryId
import com.unifiedledger.application.ManageableAccountView
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.TransactionId

/**
 * P7-04.C import review shared value types and screens (D-146; spec sections 3.3.1/4.5/6.1/6.2/6.4).
 *
 * The IMPORT overview projection ([ImportReviewView]) is pure reducer state: the authoritative
 * candidate rows the host read, the selection set, the in-session decision drafts (kept across a
 * detail close/re-enter of the same candidate, spec section 6.2 表单字段保留), the most recent
 * intake session summary and the typed failure banners. All classification, grouping, form-face
 * and copy decisions are pure functions in [P503ImportReviewPresentation] so they are assertable
 * in the app-ui JVM tests; this file only renders state and forwards typed intents.
 *
 * [ImportIntakePipelineOutcome] is the typed outcome of one full pick pipeline (spec 4.1.2/4.6):
 * the L0 bounded read first, then (only on [Bytes][com.unifiedledger.ui.BoundedFileRead.Bytes])
 * the intake orchestration.
 */
sealed interface ImportIntakePipelineOutcome {
    /** The bounded read produced bytes and the intake orchestration ran (Accepted/NoChangeAll/Rejected). */
    data class Intaken(
        val outcome: ImportFileIntakeOutcome,
    ) : ImportIntakePipelineOutcome

    /** L0: the pick exceeded the 16 MiB bound; carries the actual byte count (zero parse, zero intake). */
    data class ReadExceedsLimit(
        val actualBytes: Long,
    ) : ImportIntakePipelineOutcome

    /** L0: the typed stream failure (revoked permission, open/read/close failure); zero parse, zero intake. */
    data class ReadFailed(
        val reason: ImportPickReadFailure,
    ) : ImportIntakePipelineOutcome
}

/**
 * The most recent import session summary (spec section 6.1): the display name (D06: session
 * display/diagnostic only, never identity, never persisted) plus the pick session's opaque
 * UUIDv7 handle (R-Q09-1; the batch-disposition group parameter, P704SPEC-12) and the typed
 * per-record outcome.
 */
data class ImportIntakeSessionSummary(
    val displayName: String,
    val inputRef: String,
    val outcome: ImportIntakePipelineOutcome,
)

/** The explicit failure banners of the IMPORT overview (spec 6.2 读失败不篡改 + section 4.6). */
sealed interface ImportReviewNotice {
    /** A review-list read failed; the previous successful list stays on screen (F1, R-Q06-4). */
    data object ReviewReadFailed : ImportReviewNotice

    /** The latest pick pipeline failed typed; the previous list stays on screen (失败条 + 保留上一清单). */
    data class IntakeFailed(
        val outcome: ImportIntakePipelineOutcome,
    ) : ImportReviewNotice

    /** The core duplicate review came back typed-rejected; nothing was written (拒绝类型化呈现). */
    data class ReviewRejected(
        val code: String,
    ) : ImportReviewNotice

    /**
     * P704D-SPEC-02 (final delta): the duplicate-review submission failed at the infrastructure
     * level — the execution threw, the core never returned a verdict and the store's claim
     * transaction rolled back (zero writes, retryable after the in-flight marker clears).
     */
    data class ReviewSubmitFailed(
        val code: String,
    ) : ImportReviewNotice
}

/** One enumerated item of the batch duplicate disposition (P704SPEC-12; spec section 3.3.1). */
data class ImportDuplicateGroupDispositionItem(
    /** The import candidate whose subject source the duplicate belongs to (display/grouping). */
    val candidateId: ImportCandidateId,
    /** The core review target: the duplicate candidate id. */
    val duplicateCandidateId: ImportDuplicateCandidateId,
    /** The privacy-safe frozen comparison snapshot (P4-07 projection), read as persisted. */
    val comparisonSnapshot: String,
    /** The expected comparison fingerprint taken from the duplicate review row. */
    val expectedComparisonFingerprint: String,
)

/** The per-item disposition outcome of one batch duplicate disposition run. */
sealed interface ImportDuplicateGroupItemResult {
    /** Accepted or NoChange: the review is recorded (an equivalent replay is the same verdict). */
    data class Reviewed(
        val outcome: ImportDuplicateStatus,
    ) : ImportDuplicateGroupItemResult

    /** A typed core rejection (stale fingerprint, not pending, kind mismatch); zero writes for the item. */
    data class Rejected(
        val code: String,
    ) : ImportDuplicateGroupItemResult
}

/** One per-item outcome, matched back to its duplicate candidate (可见部分成功, spec section 3.3.1). */
data class ImportDuplicateGroupItemOutcome(
    val duplicateCandidateId: ImportDuplicateCandidateId,
    val result: ImportDuplicateGroupItemResult,
)

/** The state of one item inside the open group disposition page. */
data class ImportDuplicateGroupItemState(
    val item: ImportDuplicateGroupDispositionItem,
    /** `null` = 待处置； a recorded outcome after the loop reported back. */
    val outcome: ImportDuplicateGroupItemResult? = null,
)

/**
 * The 整组确认页 reducer sub-state (P704SPEC-12 registered mechanism): the group's session handle
 * and the per-item enumeration. The confirm action authorizes the host's sequential per-item core
 * review loop; each item carries an independent requestId/reviewId (claim-gated, replayable), one
 * item's typed rejection never stops the others, and re-opening the action recomputes the group
 * so already-disposed (non-DEFERRED) items are never re-disposed.
 */
data class ImportDuplicateGroupDispositionPage(
    val inputRef: String,
    val items: List<ImportDuplicateGroupItemState>,
)

/**
 * The IMPORT tab's authoritative projection on the overview (spec section 6.1): the candidate list
 * (presented through the section 3.3.1 classification matrix, a pure projection), the selection
 * set (勾选集, gated by the same matrix), the in-session decision drafts, the most recent intake
 * session summary and the typed failure banner. `null` on the overview means the projection has
 * not been loaded yet (the host requests it on the first IMPORT entry).
 */
data class ImportReviewView(
    val rows: List<com.unifiedledger.application.ImportReviewRow> = emptyList(),
    val selectedCandidateIds: Set<ImportCandidateId> = emptySet(),
    val decisionDrafts: Map<ImportCandidateId, ImportDecisionDraft> = emptyMap(),
    val lastIntakeSession: ImportIntakeSessionSummary? = null,
    val notice: ImportReviewNotice? = null,
    val groupDisposition: ImportDuplicateGroupDispositionPage? = null,
    /**
     * P7-04.D (D-146; spec sections 6.1/6.2): the most recent batch's per-item result summary
     * (批量结果不设独立顶层态——逐项结果内联于 importReview; D04 半提交不误报 — the summary is
     * per-item, never a whole-batch 成功/失败 verdict). An Unknown item keeps its check entry
     * here (table 6.2a: 核对入口在 IMPORT 结果摘要内).
     */
    val batchResult: ImportBatchResultSummary? = null,
    /**
     * P7-05 enumeration performance batch: true while the host's session-level enumeration
     * behind the 整组确认页 is in flight (the single-flight read the coordinator guards). The
     * overview then renders the explicit 正在整理重复组…… progress line — a progress
     * presentation choice of this batch (registered for review), not a frozen spec surface.
     * Default `false` keeps every pre-P7-05 constructor site compiling.
     */
    val groupEnumerationInProgress: Boolean = false,
)

/** The host's post-review re-read payload of [P503UiEvent.ImportDuplicateReviewResult]. */
data class ImportDuplicateReviewRefresh(
    val rows: ImportReviewRowsResult,
    val detail: ImportCandidateDetailResult? = null,
    val duplicates: ImportDuplicateReviewsResult? = null,
)

/**
 * The pure-reducer decision draft of one import candidate (spec section 4.5.3): the six
 * `ImportConfirmDecisionFields` variants projected onto typed fields; leg amounts stay text
 * (parsed at submit time, D batch) and the mixed confirmation time is the E13-required field.
 * Session memory only (spec section 6.2 表单字段保留): kept across a detail close/re-enter of the
 * same candidate, never persisted, lost on restart.
 */
data class ImportDecisionDraft(
    val categoryId: CategoryId? = null,
    val fundingAccountId: AccountId? = null,
    val fromAccountId: AccountId? = null,
    val toAccountId: AccountId? = null,
    val creditLiabilityAccountId: AccountId? = null,
    val originalTransactionId: TransactionId? = null,
    val assetAccountId: AccountId? = null,
    val assetLegAmountText: String = "",
    val creditLegAmountText: String = "",
    val confirmedAtText: String = "",
)

/** The typed `UpdateImportDecisionField(field, value)` payload (spec section 6.1). */
sealed interface ImportDecisionFieldUpdate {
    data class Category(
        val categoryId: CategoryId,
    ) : ImportDecisionFieldUpdate

    data class FundingAccount(
        val accountId: AccountId,
    ) : ImportDecisionFieldUpdate

    data class FromAccount(
        val accountId: AccountId,
    ) : ImportDecisionFieldUpdate

    data class ToAccount(
        val accountId: AccountId,
    ) : ImportDecisionFieldUpdate

    data class CreditAccount(
        val accountId: AccountId,
    ) : ImportDecisionFieldUpdate

    data class OriginalTransaction(
        val transactionId: TransactionId,
    ) : ImportDecisionFieldUpdate

    data class AssetAccount(
        val accountId: AccountId,
    ) : ImportDecisionFieldUpdate

    data class AssetLegAmountText(
        val text: String,
    ) : ImportDecisionFieldUpdate

    data class CreditLegAmountText(
        val text: String,
    ) : ImportDecisionFieldUpdate

    data class ConfirmedAtText(
        val text: String,
    ) : ImportDecisionFieldUpdate
}

/** Applies one typed field update; a field the current kind does not read is simply never read. */
internal fun ImportDecisionDraft.withImportDecisionUpdate(
    update: ImportDecisionFieldUpdate,
): ImportDecisionDraft =
    when (update) {
        is ImportDecisionFieldUpdate.Category -> copy(categoryId = update.categoryId)
        is ImportDecisionFieldUpdate.FundingAccount -> copy(fundingAccountId = update.accountId)
        is ImportDecisionFieldUpdate.FromAccount -> copy(fromAccountId = update.accountId)
        is ImportDecisionFieldUpdate.ToAccount -> copy(toAccountId = update.accountId)
        is ImportDecisionFieldUpdate.CreditAccount -> copy(creditLiabilityAccountId = update.accountId)
        is ImportDecisionFieldUpdate.OriginalTransaction -> copy(originalTransactionId = update.transactionId)
        is ImportDecisionFieldUpdate.AssetAccount -> copy(assetAccountId = update.accountId)
        is ImportDecisionFieldUpdate.AssetLegAmountText -> copy(assetLegAmountText = update.text)
        is ImportDecisionFieldUpdate.CreditLegAmountText -> copy(creditLegAmountText = update.text)
        is ImportDecisionFieldUpdate.ConfirmedAtText -> copy(confirmedAtText = update.text)
    }

/**
 * The frozen UI review decision set (P704SPEC-09): exactly three values; the core vocabulary's
 * `REJECTED` stays unreachable from the UI (保留 core 值域、UI 不可达).
 */
enum class ImportDuplicateReviewUiDecision(
    val coreStatus: ImportDuplicateStatus,
) {
    CONFIRMED_DUPLICATE(ImportDuplicateStatus.CONFIRMED_DUPLICATE),
    CONFIRMED_DISTINCT(ImportDuplicateStatus.CONFIRMED_DISTINCT),
    DISMISSED_LOOKALIKE(ImportDuplicateStatus.DISMISSED_LOOKALIKE),
}

/**
 * The composition-root result channel for platform pick results (spec section 4.1.1): the pick
 * port's `onResult` delivers here and the shared host subscribes via [subscribe]; the channel is a
 * plain app-ui value type so both composition roots wire it without touching the frozen port
 * signature. Deliveries happen on the platform's pick-callback thread (the UI thread on both
 * ends: SAF main-thread callback; the desktop modal chooser runs inside the UI event handler).
 */
class ImportFilePickResultChannel {
    private var forward: ((ImportFilePickResult) -> Unit)? = null

    /** Delivers one typed pick result to the subscribed listener (absorbed with no listener). */
    fun deliver(result: ImportFilePickResult) {
        forward?.invoke(result)
    }

    /**
     * The shared host's subscription point: [P503App] subscribes through the facade for the
     * composition's lifetime and unsubscribes (`null`) on dispose. Last subscription wins.
     */
    fun subscribe(listener: ((ImportFilePickResult) -> Unit)?) {
        forward = listener
    }
}

/**
 * The per-intent fresh id triple of one duplicate review (R-Q09-2/P7-04.B wiring): a new
 * requestId/reviewId/historyId (UUIDv7, minted by the composition root's id source) for every
 * review intent; replay/conflict paths never persist and never consume ids.
 */
class ImportDuplicateReviewIds(
    val requestId: ImportRequestId,
    val reviewId: ImportDuplicateReviewId,
    val historyId: ImportStatusHistoryId,
)

/** The fixed reviewer reference of UI-originated duplicate reviews (no personal identifier, D06). */
internal const val IMPORT_DUPLICATE_REVIEWER_REFERENCE = "p704-import-review-ui"

/** The fixed reason token of UI-originated duplicate reviews (spec leaves the token choice open). */
internal const val IMPORT_DUPLICATE_REVIEW_REASON_TOKEN = "user-reviewed"

/**
 * P704D-SPEC-02 (final delta): the UI-owned infrastructure-failure code of a duplicate-review
 * submission whose execution threw (the core never returned a verdict; the store's claim
 * transaction rolled back — zero writes). Never a fabricated `SPINE_` diagnostic
 * (the P704C-SPEC-07 namespace discipline).
 */
internal const val IMPORT_REVIEW_SUBMIT_UNAVAILABLE = "IMPORT_REVIEW_SUBMIT_UNAVAILABLE"

/**
 * The IMPORT tab content (spec sections 6.1/6.4): format entries from the capability matrix (a
 * pending-device-verification entry is shown but never labeled 可用 — CCB XLS on Android),
 * the most recent session summary, the candidate list grouped by the section 3.3.1 classification
 * matrix with the selection checkboxes (先审后勾门: non-selectable classes render no live
 * checkbox), and the batch duplicate disposition affordance of the current pick session.
 *
 * FOUND-P704-D01-01: the overview is a LazyColumn over the pure [importReviewRenderItems] model so
 * the cap-scale candidate list (the registered 10,000 intake cap) composes only the visible window;
 * the previous eager `Column + verticalScroll` composed every row and OOM'd on device. The section
 * copy, order, conditionals and semantics are unchanged — they are projected exactly by the model
 * ([P503ImportReviewRenderItemsTest] pins the mapping, the key contract and the cap-scale shape).
 */
@Composable
internal fun P503ImportScreen(
    view: ImportReviewView?,
    platform: com.unifiedledger.application.ImportPlatformKind?,
    onRefresh: () -> Unit,
    onStartFilePick: (ImportFormatId) -> Unit,
    onSelectCandidate: (ImportCandidateId) -> Unit,
    onToggleSelection: (ImportCandidateId) -> Unit,
    onGroupDisposition: () -> Unit,
    onGroupConfirm: () -> Unit,
    onGroupClose: () -> Unit,
    onRequestBatchConfirm: () -> Unit,
    onCheckUnknownItem: (ImportCandidateId) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
    ) {
        items(
            items = importReviewRenderItems(view, platform),
            key = { it.stableKey },
            contentType = { it.contentType },
        ) { item ->
            ImportReviewOverviewItem(
                item = item,
                onRefresh = onRefresh,
                onStartFilePick = onStartFilePick,
                onSelectCandidate = onSelectCandidate,
                onToggleSelection = onToggleSelection,
                onGroupDisposition = onGroupDisposition,
                onGroupConfirm = onGroupConfirm,
                onGroupClose = onGroupClose,
                onRequestBatchConfirm = onRequestBatchConfirm,
                onCheckUnknownItem = onCheckUnknownItem,
            )
        }
    }
}

/**
 * Renders one flattened [ImportReviewRenderItem] of the IMPORT overview. Every branch keeps the
 * copy, order, semantics labels and accessibility descriptions the pre-lazy screen had (the
 * conditional inclusion itself already happened inside [importReviewRenderItems]), with two
 * main-agent-approved intentional deltas on the open disposition card header: the disclosure
 * copy gains the「共 N 条。」item-count suffix, and a new「本次会话：<inputRef>」line surfaces the
 * opaque pick session handle (the P704SPEC-12/R-Q09-1 sanctioned surface).
 */
@Composable
private fun ImportReviewOverviewItem(
    item: ImportReviewRenderItem,
    onRefresh: () -> Unit,
    onStartFilePick: (ImportFormatId) -> Unit,
    onSelectCandidate: (ImportCandidateId) -> Unit,
    onToggleSelection: (ImportCandidateId) -> Unit,
    onGroupDisposition: () -> Unit,
    onGroupConfirm: () -> Unit,
    onGroupClose: () -> Unit,
    onRequestBatchConfirm: () -> Unit,
    onCheckUnknownItem: (ImportCandidateId) -> Unit,
) {
    when (item) {
        ImportReviewRenderItem.TitleBar ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("导入", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onRefresh) { Text("刷新清单") }
            }
        is ImportReviewRenderItem.NoticeBanner -> {
            Text(
                importReviewNoticeText(item.notice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
        }
        is ImportReviewRenderItem.SectionDivider ->
            when (item.slot) {
                ImportReviewDividerSlot.FORMATS -> {
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                }
                ImportReviewDividerSlot.PENDING,
                ImportReviewDividerSlot.BATCH_RESULT,
                -> {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                }
            }
        ImportReviewRenderItem.FormatSectionHeader ->
            Text("选择账单文件", style = MaterialTheme.typography.titleMedium)
        is ImportReviewRenderItem.FormatEntry ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.entry.descriptor.displayName, style = MaterialTheme.typography.bodyMedium)
                    if (item.entry.availability != com.unifiedledger.application.ImportFormatAvailability.AVAILABLE) {
                        // R-Q08-3: honest 待设备运行验证 — never presented as 可用, never silently omitted.
                        Text(
                            "待设备运行验证，暂不可用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Button(onClick = { onStartFilePick(item.entry.descriptor.identifier) }) {
                    Text("选择文件")
                }
            }
        ImportReviewRenderItem.IntakeSessionHeader -> {
            Spacer(Modifier.height(8.dp))
            Text("最近导入", style = MaterialTheme.typography.titleMedium)
        }
        is ImportReviewRenderItem.IntakeSessionLine ->
            Text(item.line, style = MaterialTheme.typography.bodyMedium)
        is ImportReviewRenderItem.IntakeRecordLine ->
            Text(item.line, style = MaterialTheme.typography.bodySmall)
        ImportReviewRenderItem.PendingSectionHeader ->
            Text("待确认草稿", style = MaterialTheme.typography.titleMedium)
        ImportReviewRenderItem.UnloadedEmptyState ->
            Text("导入清单尚未加载。", style = MaterialTheme.typography.bodyMedium)
        ImportReviewRenderItem.NoCandidatesEmptyState ->
            Text("暂无导入候选。", style = MaterialTheme.typography.bodyMedium)
        is ImportReviewRenderItem.GroupHeader -> {
            Spacer(Modifier.height(8.dp))
            Text(
                "${importCandidateClassLabel(item.classToken)}（${item.rowCount}）",
                style = MaterialTheme.typography.titleSmall,
            )
        }
        is ImportReviewRenderItem.CandidateItem ->
            ImportCandidateRow(
                row = item.row,
                selected = item.selected,
                onToggleSelection = onToggleSelection,
                onSelectCandidate = onSelectCandidate,
            )
        ImportReviewRenderItem.GroupDispositionButton -> {
            Spacer(Modifier.height(8.dp))
            Button(onClick = onGroupDisposition, modifier = Modifier.semantics { contentDescription = "整组标记为重复" }) {
                Text("整组标记为重复")
            }
        }
        ImportReviewRenderItem.GroupEnumerationInProgress ->
            Text("正在整理重复组……", style = MaterialTheme.typography.bodySmall)
        is ImportReviewRenderItem.GroupDispositionCardHeader -> {
            Spacer(Modifier.height(8.dp))
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(groupDispositionCardHeaderTitle(), style = MaterialTheme.typography.titleMedium)
                Text(
                    groupDispositionCardHeaderDisclosure(item.itemCount),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    groupDispositionCardHeaderSessionText(item.inputRef),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        is ImportReviewRenderItem.GroupDispositionItem -> {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    groupDispositionItemCandidateText(item.state),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    groupDispositionItemComparisonText(item.state),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    groupDispositionItemOutcomeText(item.state),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        is ImportReviewRenderItem.GroupDispositionCardFooter -> {
            Spacer(Modifier.height(4.dp))
            Row {
                Button(onClick = onGroupConfirm) { Text(groupDispositionCardFooterConfirmText()) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onGroupClose) { Text(groupDispositionCardFooterCloseText()) }
            }
        }
        is ImportReviewRenderItem.BatchConfirmButton -> {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onRequestBatchConfirm,
                modifier = Modifier.semantics { contentDescription = "进入批量确认" },
            ) {
                Text("进入批量确认（${item.selectionCount} 项）")
            }
        }
        ImportReviewRenderItem.BatchResultHeader ->
            Text("批量结果", style = MaterialTheme.typography.titleMedium)
        is ImportReviewRenderItem.BatchResultLine ->
            Text(item.line, style = MaterialTheme.typography.bodySmall)
        is ImportReviewRenderItem.UnknownCheckItem ->
            Button(
                onClick = { onCheckUnknownItem(item.candidateId) },
                modifier = Modifier.semantics { contentDescription = "核对未知项" },
            ) {
                Text("核对候选 ${item.candidateId.value}")
            }
    }
}

@Composable
private fun ImportCandidateRow(
    row: com.unifiedledger.application.ImportReviewRow,
    selected: Boolean,
    onToggleSelection: (ImportCandidateId) -> Unit,
    onSelectCandidate: (ImportCandidateId) -> Unit,
) {
    val rowClass = classifyImportCandidate(row)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "查看候选详情") { onSelectCandidate(row.candidateId) }
                .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (rowClass.selectable) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelection(row.candidateId) },
                    modifier = Modifier.semantics { contentDescription = "勾选候选" },
                )
            } else {
                Spacer(Modifier.width(48.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    importCandidateAmountText(row),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    importCandidateMetaText(row),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (rowClass == ImportCandidateClass.INCOMPLETE_SOURCE_FACTS) {
                    // R-Q10-3: 说明缺什么来源事实，不以通用错误掩盖；无编辑入口。
                    Text(
                        importIncompleteSourceFactsExplanation(row),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * The import candidate detail (spec sections 3.3.1/4.5.3/6.1): the candidate's class banner and
 * source facts, the decision form derived from the candidate kind (the six
 * `ImportConfirmDecisionFields` variants, D-143 same-source catalog options), the duplicate
 * comparison set with the three-value review actions (审核期间禁重复提交), and the same
 * selection gate as the list. The form is a pure reducer draft; P7-04.D adds the 批量确认 entry
 * (table 6.2a: 携详情决策进入确认页).
 */
@Composable
internal fun P503ImportCandidateDetailScreen(
    state: P503AppState.ImportCandidateDetail,
    defaultCurrency: com.unifiedledger.domain.CurrencyUnit,
    validation: P503ImportDecisionValidation,
    catalogAccounts: List<ManageableAccountView>,
    expenseCategories: List<ExpenseCategoryOption>,
    onUpdateDecisionField: (ImportDecisionFieldUpdate) -> Unit,
    onToggleSelection: (ImportCandidateId) -> Unit,
    onSubmitDuplicateReview: (ImportDuplicateReviewUiDecision) -> Unit,
    onRequestBatchConfirm: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("候选详情", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onClose) { Text("返回") }
        }
        state.notice?.let { notice ->
            Text(
                importReviewNoticeText(notice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
        }
        val row = (state.detail as? ImportCandidateDetailResult.Found)?.detail?.row
        when {
            state.detail is ImportCandidateDetailResult.Unavailable ->
                Text("无法读取候选详情（本地数据库不可用）。", color = MaterialTheme.colorScheme.error)
            state.detail is ImportCandidateDetailResult.Absent ->
                Text("该候选不存在或不在当前账本。", color = MaterialTheme.colorScheme.error)
            row != null -> {
                val rowClass = classifyImportCandidate(row)
                Text(
                    importCandidateClassLabel(rowClass),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (rowClass.selectable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(4.dp))
                Text(importCandidateAmountText(row), style = MaterialTheme.typography.bodyMedium)
                Text(importCandidateMetaText(row), style = MaterialTheme.typography.bodySmall)
                if (rowClass == ImportCandidateClass.INCOMPLETE_SOURCE_FACTS) {
                    Text(
                        importIncompleteSourceFactsExplanation(row),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
                ImportDetailSelectionRow(rowClass, row, state, onToggleSelection)
                Spacer(Modifier.height(8.dp))
                val face = importDecisionFormFace(row)
                if (face != null && rowClass.selectable) {
                    ImportDecisionFormSection(
                        form = state.form,
                        face = face,
                        catalogAccounts = catalogAccounts,
                        expenseCategories = expenseCategories,
                        onUpdate = onUpdateDecisionField,
                    )
                    // P503DraftValidation pattern: the errors are evaluated once per render and the
                    // missing-field summary is read from them (mixed 确认时间缺失 = 不可提交, E13).
                    val currency =
                        row.currencyCode?.let { code ->
                            com.unifiedledger.domain.CurrencyUnit(code, row.currencyPrecision ?: 0)
                        } ?: defaultCurrency
                    val errors = validation.errors(state.form, face, currency)
                    if (errors.hasErrors) {
                        Text(
                            "决策未补全，尚不可提交确认${if (errors.missingConfirmedAt) "（须填写确认时间）" else ""}。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text("决策已补全。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(8.dp))
                ImportDuplicateReviewSection(
                    state = state,
                    onSubmitDuplicateReview = onSubmitDuplicateReview,
                )
                // P7-04.D (table 6.2a: ImportCandidateDetail effect 携详情决策进入确认页)： the
                // entry is afforded only for a non-empty selection (the reducer effect itself is
                // unconditional — the SelectTransaction precedent).
                if (
                    state.overview.importReview
                        ?.selectedCandidateIds
                        ?.isNotEmpty() == true
                ) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onRequestBatchConfirm,
                        modifier = Modifier.semantics { contentDescription = "进入批量确认" },
                    ) {
                        Text("进入批量确认")
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportDetailSelectionRow(
    rowClass: ImportCandidateClass,
    row: com.unifiedledger.application.ImportReviewRow,
    state: P503AppState.ImportCandidateDetail,
    onToggleSelection: (ImportCandidateId) -> Unit,
) {
    val selected =
        state.overview.importReview
            ?.selectedCandidateIds
            ?.contains(row.candidateId) == true
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (rowClass.selectable) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelection(row.candidateId) },
                modifier = Modifier.semantics { contentDescription = "勾选候选" },
            )
            Text("勾选进入批量确认（须先补齐决策）", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("该候选当前不可勾选${if (rowClass == ImportCandidateClass.SUSPECTED_DUPLICATE_PENDING_REVIEW) "（疑似重复须先完成人工处置）" else ""}。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ImportDecisionFormSection(
    form: ImportDecisionDraft,
    face: ImportDecisionFormFace,
    catalogAccounts: List<ManageableAccountView>,
    expenseCategories: List<ExpenseCategoryOption>,
    onUpdate: (ImportDecisionFieldUpdate) -> Unit,
) {
    Text("补齐决策", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    val assetAccounts = catalogAccounts.filter { it.kind == com.unifiedledger.domain.AccountKind.ASSET && it.active }
    val creditAccounts = catalogAccounts.filter { it.kind == com.unifiedledger.domain.AccountKind.LIABILITY && it.active }
    if (face.requiresCategory) {
        Text("分类", style = MaterialTheme.typography.titleSmall)
        expenseCategories.forEach { option ->
            TextButton(onClick = { onUpdate(ImportDecisionFieldUpdate.Category(option.categoryId)) }) {
                Text(if (option.categoryId == form.categoryId) "● ${option.label}" else "○ ${option.label}")
            }
        }
    }
    if (face.requiresFundingAccount) {
        Text("资金账户", style = MaterialTheme.typography.titleSmall)
        assetAccounts.forEach { option ->
            TextButton(onClick = { onUpdate(ImportDecisionFieldUpdate.FundingAccount(option.accountId)) }) {
                Text(if (option.accountId == form.fundingAccountId) "● ${option.name}" else "○ ${option.name}")
            }
        }
    }
    if (face.requiresFromAccount) {
        Text("转出账户", style = MaterialTheme.typography.titleSmall)
        assetAccounts.forEach { option ->
            TextButton(onClick = { onUpdate(ImportDecisionFieldUpdate.FromAccount(option.accountId)) }) {
                Text(if (option.accountId == form.fromAccountId) "● ${option.name}" else "○ ${option.name}")
            }
        }
    }
    if (face.requiresToAccount) {
        Text("转入账户", style = MaterialTheme.typography.titleSmall)
        assetAccounts.forEach { option ->
            TextButton(onClick = { onUpdate(ImportDecisionFieldUpdate.ToAccount(option.accountId)) }) {
                Text(if (option.accountId == form.toAccountId) "● ${option.name}" else "○ ${option.name}")
            }
        }
    }
    if (face.requiresAssetAccount) {
        Text("资产腿账户", style = MaterialTheme.typography.titleSmall)
        assetAccounts.forEach { option ->
            TextButton(onClick = { onUpdate(ImportDecisionFieldUpdate.AssetAccount(option.accountId)) }) {
                Text(if (option.accountId == form.assetAccountId) "● ${option.name}" else "○ ${option.name}")
            }
        }
    }
    if (face.requiresCreditAccount) {
        Text("信用账户", style = MaterialTheme.typography.titleSmall)
        creditAccounts.forEach { option ->
            TextButton(onClick = { onUpdate(ImportDecisionFieldUpdate.CreditAccount(option.accountId)) }) {
                Text(if (option.accountId == form.creditLiabilityAccountId) "● ${option.name}" else "○ ${option.name}")
            }
        }
    }
    if (face.requiresOriginalTransaction) {
        OutlinedTextField(
            value = form.originalTransactionId?.value ?: "",
            onValueChange = { text -> onUpdate(ImportDecisionFieldUpdate.OriginalTransaction(TransactionId(text))) },
            label = { Text("原交易标识") },
            singleLine = true,
        )
    }
    if (face.legAmountsEditable) {
        // 两腿金额可空：留空 = 决策数据未补全（候选保持待确认，E13 门在提交时拒绝）。
        OutlinedTextField(
            value = form.assetLegAmountText,
            onValueChange = { text -> onUpdate(ImportDecisionFieldUpdate.AssetLegAmountText(text)) },
            label = { Text("资产腿金额（可空）") },
            singleLine = true,
        )
        OutlinedTextField(
            value = form.creditLegAmountText,
            onValueChange = { text -> onUpdate(ImportDecisionFieldUpdate.CreditLegAmountText(text)) },
            label = { Text("信用腿金额（可空）") },
            singleLine = true,
        )
    }
    if (face.requiresConfirmedAt) {
        // E13 门：mixed 候选的确认时间必填（用户确认动作时间语义；D 批提交时取值）。
        OutlinedTextField(
            value = form.confirmedAtText,
            onValueChange = { text -> onUpdate(ImportDecisionFieldUpdate.ConfirmedAtText(text)) },
            label = { Text("确认时间（必填）") },
            isError = form.confirmedAtText.isBlank(),
            supportingText = { if (form.confirmedAtText.isBlank()) Text("mixed 候选必须填写确认时间") },
            singleLine = true,
        )
    }
}

@Composable
private fun ImportDuplicateReviewSection(
    state: P503AppState.ImportCandidateDetail,
    onSubmitDuplicateReview: (ImportDuplicateReviewUiDecision) -> Unit,
) {
    when (val duplicates = state.duplicates) {
        ImportDuplicateReviewsResult.NoDuplicates -> Text("无重复候选。", style = MaterialTheme.typography.bodyMedium)
        ImportDuplicateReviewsResult.Absent -> Text("该候选不存在或不在当前账本。", color = MaterialTheme.colorScheme.error)
        ImportDuplicateReviewsResult.Unavailable ->
            Text("无法读取重复比较信息（本地数据库不可用）。", color = MaterialTheme.colorScheme.error)
        is ImportDuplicateReviewsResult.Reviews -> {
            Text("重复比较", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            duplicates.reviews.forEach { reviewRow ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("类型：${reviewRow.kind}", style = MaterialTheme.typography.bodySmall)
                    Text("最新状态：${reviewRow.latestStatus.name}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "比较信息：${reviewRow.comparisonSnapshot}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // P704C-SPEC-04 (spec 4.5.4): the possibly-existing source facts projection —
                    // exact amounts, privacy-safe tokens, and the explicit no-target copy for a
                    // CLOSED_OR_FAILED_NO_FUNDS row.
                    Text(
                        importPossibleExistingSourceText(reviewRow.possibleExistingSource),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    reviewRow.reviewDecision?.let { decision ->
                        Text(
                            "已处置：$decision（理由 token：${reviewRow.reviewReasonToken.orEmpty()}）",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            val target = importDuplicateReviewTarget(state.duplicates)
            if (target != null) {
                if (state.reviewPending) {
                    Text("审核提交中……", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("人工处置（三值）", style = MaterialTheme.typography.titleSmall)
                    Row {
                        Button(onClick = { onSubmitDuplicateReview(ImportDuplicateReviewUiDecision.CONFIRMED_DUPLICATE) }) {
                            Text("确认重复")
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { onSubmitDuplicateReview(ImportDuplicateReviewUiDecision.CONFIRMED_DISTINCT) }) {
                            Text("不是重复")
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { onSubmitDuplicateReview(ImportDuplicateReviewUiDecision.DISMISSED_LOOKALIKE) }) {
                            Text("略过相似")
                        }
                    }
                }
            }
        }
    }
}
