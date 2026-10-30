package com.unifiedledger.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unifiedledger.application.BUDGET_CONFIGURED_CURRENCY
import com.unifiedledger.application.BudgetCommandResult
import com.unifiedledger.application.BudgetMonthViewResult
import com.unifiedledger.application.BudgetTarget
import com.unifiedledger.application.CatalogCommandResult
import com.unifiedledger.application.CatalogSnapshotView
import com.unifiedledger.application.CollectDraft
import com.unifiedledger.application.CorrectTransactionVersionResult
import com.unifiedledger.application.EntryPinOrdering
import com.unifiedledger.application.EntryPinResult
import com.unifiedledger.application.EntryPinTarget
import com.unifiedledger.application.ExpenseDraft
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.ExplicitlyConfirmedTransactionCorrection
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportDuplicateReviewRequest
import com.unifiedledger.application.ImportDuplicateReviewsForSessionResult
import com.unifiedledger.application.ImportDuplicateReviewsResult
import com.unifiedledger.application.ImportDuplicateStatus
import com.unifiedledger.application.ImportFileIntakeInput
import com.unifiedledger.application.ImportFormatCapabilities
import com.unifiedledger.application.ImportFormatId
import com.unifiedledger.application.ImportRequestIdentity
import com.unifiedledger.application.ImportReviewRowsResult
import com.unifiedledger.application.IncomeDraft
import com.unifiedledger.application.LedgerCurrentStateResult
import com.unifiedledger.application.LedgerFlowPageResult
import com.unifiedledger.application.LedgerFlowTotalResult
import com.unifiedledger.application.LendDraft
import com.unifiedledger.application.ManualCollectInputField
import com.unifiedledger.application.ManualCollectSaveInput
import com.unifiedledger.application.ManualCollectSaveResult
import com.unifiedledger.application.ManualCollectSubmissionResult
import com.unifiedledger.application.ManualEntryCommitResolution
import com.unifiedledger.application.ManualEntrySaveInput
import com.unifiedledger.application.ManualEntrySubmissionResult
import com.unifiedledger.application.ManualExpenseCommitResolution
import com.unifiedledger.application.ManualExpenseInputFailure
import com.unifiedledger.application.ManualExpenseInputField
import com.unifiedledger.application.ManualExpenseOptions
import com.unifiedledger.application.ManualExpenseRequestSnapshot
import com.unifiedledger.application.ManualExpenseSaveInput
import com.unifiedledger.application.ManualExpenseSaveResult
import com.unifiedledger.application.ManualExpenseSubmissionResult
import com.unifiedledger.application.ManualIncomeCommitResolution
import com.unifiedledger.application.ManualIncomeOptions
import com.unifiedledger.application.ManualIncomeRequestSnapshot
import com.unifiedledger.application.ManualIncomeSaveInput
import com.unifiedledger.application.ManualIncomeSaveResult
import com.unifiedledger.application.ManualIncomeSubmissionResult
import com.unifiedledger.application.ManualLendInputField
import com.unifiedledger.application.ManualLendSaveInput
import com.unifiedledger.application.ManualLendSaveResult
import com.unifiedledger.application.ManualLendSubmissionResult
import com.unifiedledger.application.ManualLendingBehavior
import com.unifiedledger.application.ManualLendingCommitResolution
import com.unifiedledger.application.ManualLendingOptions
import com.unifiedledger.application.ManualLendingRequestSnapshot
import com.unifiedledger.application.ManualTransferCommitResolution
import com.unifiedledger.application.ManualTransferInputField
import com.unifiedledger.application.ManualTransferOptions
import com.unifiedledger.application.ManualTransferRequestSnapshot
import com.unifiedledger.application.ManualTransferSaveInput
import com.unifiedledger.application.ManualTransferSaveResult
import com.unifiedledger.application.ManualTransferSubmissionResult
import com.unifiedledger.application.MerchantMonthlyAccumulationResult
import com.unifiedledger.application.MonthlyBuckets
import com.unifiedledger.application.MonthlyTrend
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.RecycleBinResult
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.SummarizeLedgerActivity
import com.unifiedledger.application.TagMerchantAuthority
import com.unifiedledger.application.TagMerchantCommandResult
import com.unifiedledger.application.TagMerchantFilterCursor
import com.unifiedledger.application.TagMerchantFilterFailureCode
import com.unifiedledger.application.TagMerchantFilterReadResult
import com.unifiedledger.application.TransactionCorrectionRequestIdentity
import com.unifiedledger.application.TransactionDetail
import com.unifiedledger.application.TransactionDetailResult
import com.unifiedledger.application.TransactionVoidRequestIdentity
import com.unifiedledger.application.TransferDraft
import com.unifiedledger.application.TypedEntryDraft
import com.unifiedledger.application.VoidTransactionRequest
import com.unifiedledger.application.VoidTransactionResult
import com.unifiedledger.application.backup.BackupPreflightRejection
import com.unifiedledger.application.budgetMonthKey
import com.unifiedledger.application.budgetScopeKey
import com.unifiedledger.application.tagMerchantFilterConditionFingerprint
import com.unifiedledger.domain.BudgetScope
import com.unifiedledger.domain.CatalogItemKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.MerchantId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.P705FailureCode
import com.unifiedledger.domain.TagId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVoidFactKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import com.unifiedledger.application.MonthlyActivityResult as ApplicationMonthlyActivityResult

/**
 * P7-09 09.0 (spec sections 2.2/3; D-225 Q19): the visible Busy/待处理 strip for a held inbound
 * navigation request or a blocked host operation. The copy names the routing-table row that held
 * the request; it NEVER carries an amount, a URI, a file name or any inbound payload (spec section
 * 2.2 可见 Busy + 硬禁明文金额 discipline). The strip offers the explicit retain/replace actions
 * only while a second request is waiting for the user's decision, plus the 保留当前/放弃后切换
 * choice of the Editing row.
 */
@Composable
internal fun ExternalNavigationBusyStrip(
    busy: ExternalNavigationBusy,
    onKeepCurrent: () -> Unit,
    onRetain: (() -> Unit)?,
    onReplace: (() -> Unit)?,
    onDiscard: (() -> Unit)?,
    // P7-09 09.A (spec section 2.3): while a held share awaits its format choice, the strip offers
    // the EXISTING capability-matrix formats. Defaults keep every other call site compiling.
    formatChoices: List<ImportFormatEntry> = emptyList(),
    onSelectFormat: ((ImportFormatId) -> Unit)? = null,
    // ACC-D-SHARE-02: a refusal strip has nothing to retain (there is no request), so its single
    // button must not claim "保留当前".
    dismissLabel: String = "保留当前",
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = externalNavigationBusyText(busy),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (onSelectFormat != null && formatChoices.isNotEmpty()) {
                // One choice per matrix format (不静默省略). A unit still pending device verification
                // is not offered as a live pick (R-Q08-3), matching the IMPORT tab's honest matrix.
                Row {
                    formatChoices.forEach { entry ->
                        if (entry.availability == com.unifiedledger.application.ImportFormatAvailability.AVAILABLE) {
                            TextButton(onClick = { onSelectFormat(entry.descriptor.identifier) }) {
                                Text(entry.descriptor.displayName)
                            }
                        }
                    }
                }
            }
            Row {
                if (onRetain != null) {
                    TextButton(onClick = onRetain) { Text("保留原请求") }
                }
                if (onReplace != null) {
                    TextButton(onClick = onReplace) { Text("替换为当前请求") }
                }
                if (onDiscard != null) {
                    TextButton(onClick = onDiscard) { Text("放弃后切换") }
                }
                TextButton(onClick = onKeepCurrent) { Text(dismissLabel) }
            }
        }
    }
}

/**
 * P7-09 09.0 (spec section 3): the display copy of one Busy/待处理 strip. Pure and JVM-assertable
 * (the `importReviewNoticeText` precedent); the copy is static per reason and leaks no payload.
 */
internal fun externalNavigationBusyText(busy: ExternalNavigationBusy): String {
    // ACC-D-SHARE-02: a refused share says WHICH validation refused it (the precise vector wins over
    // the carrier reason, which only exists so the strip has a slot to land on).
    val rejection = busy.shareRejection
    if (rejection != null) return shareRejectionText(rejection)
    return when (busy.reason) {
        ExternalNavigationHoldReason.STARTUP_NOT_READY -> "正在打开本地账本，已暂存外部请求…"
        ExternalNavigationHoldReason.DRAFT_IN_PROGRESS -> "当前有未保存的草稿，外部请求已暂存。"
        ExternalNavigationHoldReason.CONFIRMATION_PENDING -> "当前有一笔待确认的录入，外部请求已暂存。"
        ExternalNavigationHoldReason.COMMIT_IN_FLIGHT -> "当前提交尚未结束，外部请求已暂存。"
        ExternalNavigationHoldReason.IMPORT_BATCH_IN_FLIGHT -> "导入批次处理中，外部请求已暂存。"
        ExternalNavigationHoldReason.IMPORT_INTAKE_IN_FLIGHT -> "已有文件正在导入，请稍候。"
        ExternalNavigationHoldReason.RUNTIME_QUIESCED -> "本地账本正在切换，外部请求已暂缓。"
        ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED -> "已收到分享文件，请选择账单格式后导入。"
        ExternalNavigationHoldReason.IMPORT_SOURCE_UNAVAILABLE -> "分享的文件已无法读取（授权已过期或进程已重启），请重新分享后再导入。"
        // ACC-D-SHARE-02: reached only when a refusal arrives WITHOUT its detailed vector (the carrier
        // with no payload). The detailed copy is [shareRejectionText].
        ExternalNavigationHoldReason.IMPORT_REJECTED -> "无法接收这次分享，未做任何改动。"
        ExternalNavigationHoldReason.UNROUTABLE_TARGET -> "外部请求暂无法打开，已暂存。"
    }
}

/**
 * P7-09 09.A (spec section 2.3; ACC-D-SHARE-02): the display copy of one REFUSED inbound share.
 * Pure and JVM-assertable; every branch names only the validation that refused, never the inbound
 * payload (no URI, file name, size of the actual file, or exception text). The copy states the
 * zero-formal-effect fact so a refusal is never mistaken for a successful import.
 */
internal fun shareRejectionText(reason: ShareRejectionReason): String =
    when (reason) {
        ShareRejectionReason.UNSUPPORTED_ACTION -> "无法接收这次分享：请用“分享”发送单个文件。"
        ShareRejectionReason.MISSING_STREAM -> "无法接收这次分享：没有附带文件，未做任何改动。"
        // The frozen validator's action-first order reports ACTION_SEND_MULTIPLE as MULTIPLE_STREAMS even
        // when it carries no file, so the copy must read correctly for both (several files, none).
        ShareRejectionReason.MULTIPLE_STREAMS -> "无法接收这次分享：请只分享一个文件（这一条包含多个文件或没有文件）。"
        ShareRejectionReason.CONFLICTING_STREAM -> "无法接收这次分享：文件信息不一致，请重新分享。"
        ShareRejectionReason.NON_CONTENT_URI -> "无法接收这次分享：该来源不支持导入，请重新分享。"
        ShareRejectionReason.STREAM_NOT_READABLE -> "无法接收这次分享：没有读取权限，请重新分享后再导入。"
        ShareRejectionReason.MALFORMED_EXTRAS -> "无法接收这次分享：附件信息无法识别，请重新分享。"
        ShareRejectionReason.DECLARED_SIZE_EXCEEDS_LIMIT -> "无法接收这次分享：文件超过 16 MiB 上限。"
        ShareRejectionReason.NOT_A_SHARE -> "无法接收这次分享：没有可导入的内容。"
    }

/**
 * D-134 D2-D1 shared dual-theme wrapper: an explicit light/dark colorScheme following the
 * system mode plus a full-size background paint, so screens without their own background
 * (edit, startup, infrastructure failure) never expose the platform window default
 * (P6-ENTRY-THEME-002; spec section 4/D2-D1). Shared by Android and Desktop; the platform
 * root may wrap its whole content with this composable (MainActivity pre-Ready path) —
 * nesting is harmless, while a bare inner MaterialTheme would reset to the default light
 * scheme.
 */
@Composable
fun P503Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            content()
        }
    }
}

/**
 * P5-03 shared demo surface entry (spec sections 4.7/7/8). The composition root builds the
 * [P503LedgerFacade] and calls this composable once startup is ready. The host executes all
 * asynchronous work (authoritative queries, submission orchestration, result refresh) and
 * dispatches result events into the pure [P503Reducer].
 *
 * P7-02.A: the editor is typed (EXPENSE/INCOME); the host chooses the per-type save input and
 * snapshot, injects the retained intent on the determinate-success refresh (E-2/G-C), and
 * routes the income commit-status check to the income resolver.
 */
@Composable
fun P503App(
    ledger: LedgerLeaseScope,
    onExit: () -> Unit,
    backHandler: (@Composable (enabled: Boolean, onBack: () -> Unit) -> Unit)? = null,
    // P7-09 09.0 (spec sections 2.1/2.2; D-225 Q19): the composition root's inbound-navigation
    // channel. Null (the desktop root and existing tests) keeps the surface absent and renders no
    // dead affordance; a wired root delivers neutral requests the host routes.
    externalNavigationChannel: ExternalNavigationChannel? = null,
    // P7-09 09.A delivery order (spec section 2.2; ACC-D-SHARE-01): the platform host token this
    // composition binds to the channel. The channel hands a request only to the host it knows is
    // RESUMED and matches that host by IDENTITY (`===`), so the Android root passes its Activity
    // instance itself, never a value-equal stand-in; null (the desktop root and every existing test)
    // binds nothing and keeps the surface absent.
    inboundHostToken: Any? = null,
    // P7-09 09.C (spec section 6.4; D-225 Q20): the widget-invalidation seam of the FIRST refresh
    // leg ("已知正式写入成功/恢复成功的合并失效"). The Android root wires the process holder's
    // widget fan-out; the no-op default keeps the desktop root and every existing call site/tests
    // compiling unchanged (the R-222-4 default-value convention). It is read through a
    // `rememberUpdatedState` at its use site below, so a recomposition with a fresh observer cannot
    // leave the remembered coordinator holding a stale one.
    widgetInvalidation: WidgetInvalidationObserver = WidgetInvalidationObserver {},
) {
    val reducer = remember(ledger) { P503ReducerImpl(ledger.parseAmount, ledger.currency, ledger.ledgerClock) }
    val validation = remember(ledger) { P503DraftValidation(ledger.parseAmount, ledger.parseOccurredAt, ledger.ledgerClock) }
    val scope = rememberCoroutineScope()
    // A-PERF (P7-04 read-governance batch, spec section 2.3): the cached authoritative catalog
    // snapshot. The composition previously read `facade.catalogSnapshot()` directly on the main
    // thread at :145 (EVERY recomposition), at the detail screen and on the
    // pinnedCatalogSnapshot event path — with a background full-ledger read occupying the single
    // SQLite connection, those reads were the ANR's main-thread wait (the pre-fix baseline's
    // 30s "unable to grant a connection to thread main" trace). D-A ruling: a nullable cached
    // State + single-flight background loading + the last-loaded value kept until a fresh one
    // lands. `null` is the explicit loading window: the consumers below present the honest
    // 载入中 placeholder instead of presenting an empty set as the authoritative catalog (S2-2)
    // and the null-keyed option derivations stay empty until the first snapshot lands (提交入口
    // 以必填 null 不提交保持账务安全).
    var cachedCatalogSnapshot by remember(ledger) { mutableStateOf<CatalogSnapshotView?>(null) }
    // The single-flight admission and the stale-merge decision live in the pure, JVM-tested
    // [P503CatalogSnapshotLoadCoordinator] (the P503HostCoordinator extraction precedent).
    val catalogSnapshotLoadCoordinator = remember(ledger) { P503CatalogSnapshotLoadCoordinator() }
    // P7-08 08.B-2 (spec section 4.1): the selectable tag/merchant projection the editor renders.
    // Read OFF the main thread (the A-PERF layer-2 ruling: no direct facade catalog read during
    // recomposition) and refreshed with the same catalog-snapshot trigger that already reloads
    // `cachedCatalogSnapshot`, so a rename/disable/delete is reflected without a second refresh
    // path. `null` is the honest "not yet loaded / not wired" window: the editor then presents no
    // selector rather than an empty picker that would read as an authoritative empty catalog.
    var cachedTagMerchantSelection by
        remember(ledger) { mutableStateOf<TagMerchantSelectionOptions?>(null) }
    // P7-08 08.C (spec sections 5.1-5.5): the FULL tag/merchant authority (disabled and
    // tombstoned rows included — the audit surface) loaded in the SAME leased read as the
    // catalog snapshot. One authority load feeds the editor's selection options, the filter
    // surface's options (disabled included, tombstoned excluded — spec 5.1), the management
    // sections and the per-item CAS revisions. `null` = not yet loaded / unwired: consumers
    // present the honest 载入中 placeholder (S2-2), never a faked empty catalog.
    var cachedTagMerchantAuthority by remember(ledger) { mutableStateOf<TagMerchantAuthority?>(null) }
    // The 08.C wiring probes, learned once off-thread (the 08.B-2 annotationEditWired precedent
    // — no new LedgerSurfaces field, no main-thread facade read).
    var tagMerchantFilterWired by remember(ledger) { mutableStateOf(false) }
    var tagMerchantCatalogWired by remember(ledger) { mutableStateOf(false) }
    // T5 (spec section 5.5): whether a filter surface was opened this session, and the armed
    // re-request that follows an annotation/formal-surface change (the budget arm/consume twin,
    // hosted locally because the shared P503HostCoordinator is not part of this batch's scope).
    var tagMerchantFilterEverRequested by remember(ledger) { mutableStateOf(false) }
    var pendingTagMerchantFilterReRequestAfterRefresh by remember(ledger) { mutableStateOf(false) }
    // P7-08 08.B-2 (spec section 4.4): whether the annotation-edit entry can be offered. The
    // command, its id source and its read port live on the facade behind the lease, so the host
    // cannot test them on the main thread; the flag is learned once off-thread (the runtime is
    // built once per graph) and gates the detail's entry affordance. A null/unwired root leaves it
    // false, so the detail renders no dead button.
    var annotationEditWired by remember(ledger) { mutableStateOf(false) }
    // A-PERF (rework path 1a): the single-flight coalescing admission of the authoritative
    // current-state read behind refresh() and the initial load (pure, JVM-tested the same way).
    val currentStateLoadCoordinator = remember(ledger) { P503CurrentStateLoadCoordinator() }

    /**
     * A-PERF (spec section 2.3): requests the authoritative snapshot on the background
     * dispatcher, single-flight (concurrent requests merge into the running load), with the
     * result hopped back onto the composition's main dispatcher (P704C-SPEC-01/QUAL-02 — the
     * nested `scope.launch` hop keeps every cached-state write serial on the main thread). A
     * late stale completion never overwrites a newer cached state (S2-3): a failed/absent read
     * keeps the previously loaded snapshot (旧值保留); the first-ever failure leaves the null
     * loading window standing (an honest 载入中, never a faked empty catalog).
     */
    fun requestCatalogSnapshotLoad() {
        catalogSnapshotLoadCoordinator.startLoadOnce {
            // D-203 startup trace: the catalog snapshot read window begins (background).
            StartupTrace.emit("read.catalogSnapshot begin")
            scope.launch(Dispatchers.Default) {
                // P7-06 06.1 (D-176; spec 4.3): the read runs under an operation lease, and the
                // acquire-time generation travels to the landing hop (4.4) so a result captured
                // under a superseded graph is discarded instead of polluting the new one.
                val outcome = ledger.leased { facade, _ -> runCatching { facade.catalogSnapshot() }.getOrNull() }
                // P7-08 08.B-2: the selectable tag/merchant projection loads in the SAME leased
                // read as the catalog snapshot (one lease, one trigger — a rename/disable/delete
                // that changes the catalog snapshot also refreshes the selector's options). A null
                // read (unwired root, or a not-yet-created per-ledger authority) keeps the
                // previous value standing, never a faked empty catalog.
                val selectionOutcome =
                    ledger.leased { facade, _ -> runCatching { facade.tagMerchantSelectionOptions() }.getOrNull() }
                val annotationWiredOutcome =
                    ledger.leased { facade, _ -> facade.updateTransactionAnnotation != null && facade.annotationRequestIdSource != null && facade.transactionAnnotationReader != null }
                // P7-08 08.C: one lease covers the authority load and both wiring probes.
                val tagMerchantOutcome =
                    ledger.leased { facade, _ ->
                        Triple(
                            runCatching { facade.tagMerchantCatalogReader?.invoke()?.load(facade.ledgerId) }.getOrNull(),
                            facade.queryTagMerchantFilterPage != null &&
                                facade.queryMerchantAccumulation != null &&
                                facade.tagMerchantExpectedCatalogItemVersion() != null,
                            facade.executeTagMerchantCommand != null,
                        )
                    }
                scope.launch {
                    when (outcome) {
                        is LeaseOutcome.Completed ->
                            if (ledger.isCurrentGeneration(outcome.generation)) {
                                cachedCatalogSnapshot = catalogSnapshotLoadCoordinator.loadCompleted(cachedCatalogSnapshot, outcome.value)
                                // D-203 startup trace: the snapshot read landed.
                                StartupTrace.emit("read.catalogSnapshot end kind=completed")
                            } else {
                                // Stale generation (P706-A07): discard the payload, release the
                                // single-flight slot only.
                                catalogSnapshotLoadCoordinator.loadCompleted(cachedCatalogSnapshot, null)
                                // D-203 startup trace: the snapshot read landed on a stale generation.
                                StartupTrace.emit("read.catalogSnapshot end kind=stale")
                            }
                        LeaseOutcome.NotReady -> {
                            catalogSnapshotLoadCoordinator.loadCompleted(cachedCatalogSnapshot, null)
                            // D-203 startup trace: the runtime was not Ready for the snapshot read.
                            StartupTrace.emit("read.catalogSnapshot end kind=notReady")
                        }
                    }
                    (selectionOutcome as? LeaseOutcome.Completed)
                        ?.takeIf { ledger.isCurrentGeneration(it.generation) }
                        ?.let { if (it.value != null) cachedTagMerchantSelection = it.value }
                    (annotationWiredOutcome as? LeaseOutcome.Completed)
                        ?.takeIf { ledger.isCurrentGeneration(it.generation) }
                        ?.let { annotationEditWired = it.value }
                    (tagMerchantOutcome as? LeaseOutcome.Completed)
                        ?.takeIf { ledger.isCurrentGeneration(it.generation) }
                        ?.value
                        ?.let { (authority, filterWired, catalogWired) ->
                            // A null authority (unwired reader, or no per-ledger catalog yet)
                            // keeps the previous value standing (the selection-load discipline).
                            if (authority != null) cachedTagMerchantAuthority = authority
                            tagMerchantFilterWired = filterWired
                            tagMerchantCatalogWired = catalogWired
                        }
                }
            }
        }
    }

    // Initial load: the first composition starts the single background load once.
    LaunchedEffect(ledger) {
        requestCatalogSnapshotLoad()
    }

    // D-140-style authoritative options snapshot. Reading the version here makes the options
    // reload after a catalog refresh (spec 7.4), because the facade exposes the refreshed
    // session models; options/reads/summaries then all follow one authoritative catalog version.
    // A-PERF: the version now reads the CACHED snapshot (no main-thread catalog read per
    // recomposition); the remember blocks below re-derive only when the loaded version actually
    // changes, and the null loading window deliberately yields empty options (the honest 载入中
    // placeholder discipline above — an absent catalog is never presented as an empty but
    // authoritative catalog, S2-2).
    val catalogVersion = cachedCatalogSnapshot?.catalogVersion
    // P7-02.D E-4: the host's mirror of the persisted pin set. Seeded from the store at startup,
    // updated after every successful toggle, and injected into the overview through the load and
    // refresh events; the lists below derive their pinned-first order from it.
    var pinnedTargets by remember { mutableStateOf(emptySet<EntryPinTarget>()) }
    // P702SPEC-03: bumped after every successful counterparty create/rename so the option
    // projections (which read the directory fresh per query) re-derive.
    var counterpartyVersion by remember { mutableStateOf(0) }
    // A-PERF (S2-2): while the cached snapshot is still loading (catalogVersion == null) the
    // option projections stay the EMPTY placeholder set — the editor simply offers nothing to
    // pick (必填 null 不提交 keeps the entry submit blocked), and the real projections derive
    // once the loaded version lands. This is why these remember blocks never run a catalog read
    // keyed on a null version: the loading window must not trade its placeholder back for the
    // very main-thread catalog read the batch removes.
    val baseExpenseOptions =
        remember(ledger, catalogVersion) {
            if (catalogVersion == null) ManualExpenseOptions(emptyList(), emptyList()) else ledger.probe { it.optionsProvider.queryOptions() } ?: ManualExpenseOptions(emptyList(), emptyList())
        }
    val baseIncomeOptions =
        remember(ledger, catalogVersion) {
            if (catalogVersion == null) ManualIncomeOptions(emptyList(), emptyList()) else ledger.probe { it.incomeOptionsProvider.queryOptions() } ?: ManualIncomeOptions(emptyList(), emptyList())
        }
    val baseTransferOptions =
        remember(ledger, catalogVersion) {
            if (catalogVersion == null) ManualTransferOptions(emptyList(), emptyList()) else ledger.probe { it.transferOptionsProvider.queryOptions() } ?: ManualTransferOptions(emptyList(), emptyList())
        }
    // P702SPEC-13: the lending projection is the only one that reads the counterparty
    // directory, so it must re-query when a create/rename command succeeds.
    val baseLendingOptions =
        remember(ledger, catalogVersion, counterpartyVersion) {
            if (catalogVersion == null) ManualLendingOptions(emptyList(), emptyList(), emptyList()) else ledger.probe { it.lendingOptionsProvider.queryOptions() } ?: ManualLendingOptions(emptyList(), emptyList(), emptyList())
        }
    // E-4: pinned entries first, remaining entries keep the deterministic option order.
    val options =
        remember(baseExpenseOptions, pinnedTargets) {
            ManualExpenseOptions(
                EntryPinOrdering.sortPaymentAccounts(baseExpenseOptions.paymentAccounts, pinnedTargets),
                EntryPinOrdering.sortExpenseCategories(baseExpenseOptions.expenseCategories, pinnedTargets),
            )
        }
    val incomeOptions =
        remember(baseIncomeOptions, pinnedTargets) {
            ManualIncomeOptions(
                EntryPinOrdering.sortPaymentAccounts(baseIncomeOptions.receivingAccounts, pinnedTargets),
                EntryPinOrdering.sortIncomeCategories(baseIncomeOptions.incomeCategories, pinnedTargets),
            )
        }
    val transferOptions =
        remember(baseTransferOptions, pinnedTargets) {
            ManualTransferOptions(
                EntryPinOrdering.sortPaymentAccounts(baseTransferOptions.ownedAssetAccounts, pinnedTargets),
                EntryPinOrdering.sortExpenseCategories(baseTransferOptions.feeCategories, pinnedTargets),
            )
        }
    val lendingOptions =
        remember(baseLendingOptions, pinnedTargets, counterpartyVersion) {
            ManualLendingOptions(
                EntryPinOrdering.sortPaymentAccounts(baseLendingOptions.ownedAssetAccounts, pinnedTargets),
                EntryPinOrdering.sortIncomeCategories(baseLendingOptions.interestCategories, pinnedTargets),
                baseLendingOptions.counterparties,
            )
        }
    var state by remember { mutableStateOf<P503AppState>(P503AppState.Ready()) }
    val latestState = remember { mutableStateOf<P503AppState>(P503AppState.Ready()) }
    // P5-04.3 single-flight marker for the unknown-commit status check (read-only resolve).
    var statusCheckInFlight by remember { mutableStateOf(false) }
    var editDialogOpen by remember { mutableStateOf(false) }
    // D-140 (spec 2.1): 键入文本宿主级提升；null = 未初始化，显示按 draft 派生。
    var hoistedOccurredAtText by remember { mutableStateOf<String?>(null) }
    // P7-02.A E-2 (G-C): the host-held memory of one determinate-success intent for "record
    // again". Captured before submission, consumed by the authoritative refresh; never persisted.
    var retainedIntent by remember { mutableStateOf<RetainedEntryIntent?>(null) }
    // P7-05 (V-19; D-173): the request snapshot the current P7-05 surface committed, captured at its
    // explicit confirm (the `retainedIntent` precedent). The three surfaces keep their fields
    // editable while submitting, so a manual re-check MUST resolve against this retained snapshot
    // rather than a re-derivation from the live draft — otherwise a mutated draft would produce a
    // FALSE SnapshotConflict. Cleared when the surface leaves or a result lands.
    var retainedP705Request by remember { mutableStateOf<RetainedP705Request?>(null) }
    // P7-03.C/D (D-145): host mirrors of the ledger-view read surface. The trend and the sorted
    // flow rows are presentation data refreshed with the unified monthly cycle (frozen trigger
    // set, spec 6.2); the month payload and the SelectMonth domain live in the reducer state.
    var monthlyTrend by remember { mutableStateOf<MonthlyTrend?>(null) }
    // DEF-B (D-268): the HOME flow page state. Kept host-local (not in the reducer) so it survives
    // Back within one host instance; `null` renders the bounded current-state fallback (G1).
    var flowRows by remember { mutableStateOf<FlowRowsView?>(null) }
    var flowGeneration by remember { mutableStateOf(0) }
    var resolvedCurrentMonth by remember { mutableStateOf<YearMonth?>(null) }
    val ledgerViewWired = ledger.surfaces.ledgerView
    // P7-07 07.D (D-184; spec section 5): the budget surface wiring probe and the current-name
    // derivations the budget region needs (from the CACHED authoritative snapshot; an unloaded
    // snapshot degrades to the honest stable-id fallback, never an invented name).
    val budgetWired = ledger.surfaces.budget
    val budgetCategoryViews = remember(cachedCatalogSnapshot) { cachedCatalogSnapshot?.categories.orEmpty() }
    val budgetCategoryNames = budgetCategoryViews.associate { it.categoryId to it.name }
    val budgetCategoryParents = budgetCategoryViews.associate { it.categoryId to it.parentId }
    // The 新增分类预算 picker lists the ACTIVE expense categories of the authoritative catalog
    // (a new binding to a deactivated category is the use case's typed rejection, so an
    // inactive one is never offered).
    // P3 fix round: level-1 scopes FIRST, then their level-2 children, each group by name —
    // the same 一级在前 direction the catalog tree and the category region read in.
    val budgetPickerChoices =
        budgetCategoryViews
            .filter { it.kind == com.unifiedledger.domain.CategoryKind.EXPENSE && it.active }
            .sortedWith(compareBy({ it.parentId != null }, { it.name }))
            .map { view -> BudgetCategoryChoice(BudgetScope.Category(view.categoryId), view.name) }
    // P7-04.C: the matrix format of the pick currently in flight. PickedImportFile deliberately
    // carries no format (frozen shape, spec 4.1.1), so the host remembers the launched format and
    // consumes the slot when the picked result arrives (one pick at a time: SAF is single-shot and
    // the desktop chooser is modal).
    var pendingImportFormat by remember { mutableStateOf<ImportFormatId?>(null) }
    // P7-09 09.A fix round (B2; spec section 2.2 line 106 禁止静默丢弃, section 3 line 154 暂缓):
    // the HOST-level fallback hold for surfaces the reducer cannot carry the single pending slot on
    // (a read-only detail, the recycle bin, a restore-confirm quiesce, ...). Without it an arriving
    // request landing on such a surface was absorbed into `setPendingExternalNavigation`'s unchanged
    // return and vanished with no visible Busy. This never widens [P503AppState], so the ~25
    // unrelated state-construction sites stay untouched; the request is surfaced (and rendered) from
    // the host instead. See [hostFallbackHeldNavigation].
    var hostHeldNavigation by remember { mutableStateOf<PendingExternalNavigation?>(null) }
    // ACC-D-SHARE-02: the HOST-level refused-share notice. A refusal has no request and therefore no
    // slot to land in, and it must be visible on EVERY surface — including the read-only detail, the
    // recycle bin and the infrastructure-failure page, where the reducer cannot carry a Busy field
    // (the same reason [hostHeldNavigation] exists). It is transient by design: it dies with the
    // composition/process, and it carries only the display-safe vector.
    var hostShareRejection by remember { mutableStateOf<ShareRejectionReason?>(null) }
    // P7-04.C: the decision-form validation over the shared parse facade (P503DraftValidation
    // pattern; the detail screen renders its one-shot errors).
    val importDecisionValidation = remember(ledger) { P503ImportDecisionValidation(ledger.parseAmount) }

    /** The visible held request on the live surface: the reducer's slot, else the host fallback. */
    fun visibleHeldNavigation(): PendingExternalNavigation? = state.pendingExternalNavigationSlot() ?: hostHeldNavigation

    fun dispatch(event: P503UiEvent) {
        // D-140 (spec 2.2): 全新草稿流事件重置 hoisted 文本（枚举表：#1 唯一）。P7-02.D E-2:
        // 再记一笔开启的新编辑流与 StartNewExpense 同列，同样必须重置，否则旧键入文本会覆盖
        // 新的时钟 instant。
        if (event is P503UiEvent.StartNewExpense || event is P503UiEvent.SaveAndRecordAgain) hoistedOccurredAtText = null
        // P7-02.A E-2 lifecycle: the retained intent is cleared once a new intent starts
        // (StartNewExpense / record again / cancel or abandon back into Editing), so a stale
        // intent can never be injected into an unrelated later refresh.
        if (
            event is P503UiEvent.StartNewExpense ||
            event is P503UiEvent.SaveAndRecordAgain ||
            event is P503UiEvent.Cancel ||
            event is P503UiEvent.AbandonConflict
        ) {
            retainedIntent = null
        }
        // V-19 (D-173) lifecycle: the retained P7-05 request snapshot lives exactly while its
        // surface holds a lost commit (submitting with no result). Keyed on the LANDED state, not
        // the dispatched event: the reducer absorbs events its surface refuses (e.g. another bin
        // row tapped while a lost restore is in flight), and an event-based clear would drop the
        // snapshot of a still-lost commit. A determinate hit or a conflict clears `submitting` and
        // releases it; a LOST commit (Absent/Unavailable) keeps it for the manual re-check.
        //
        // P7-09 09.A fix round (B1/B2): the single-pass tail. `previousHeld`/`nextHeld` are the
        // effective held request BEFORE/AFTER this transition — the reducer's slot OR the host
        // fallback (a surface that cannot carry the slot). B1: the retained share source is released
        // whenever the awaiting-format share leaves the effective hold (keep-current, a consumed
        // format selection, a typed re-share, a replace) — the ONE release point. B2: the fallback is
        // dropped on an explicit clear / an applied retain-replace, and FLUSHED back into the reducer
        // once a routable surface is live with an empty slot (no nested dispatch: one fold pass).
        val previousHeld = visibleHeldNavigation()
        val previousFallback = hostHeldNavigation
        var landed = reducer.reduce(state, event)
        retainedP705Request = retainedP705RequestAfterLanding(retainedP705Request, landed)
        when {
            clearsExternalNavigation(event) -> hostHeldNavigation = null
            // The retain/replace actions are absorbed by the reducer on a non-slot surface; the host
            // applies them to its fallback so the §2.2 buttons are never a silent no-op there.
            event === P503UiEvent.ExternalNavigationRetained -> hostHeldNavigation = hostHeldNavigation?.let(::retainPendingExternalNavigation)
            event === P503UiEvent.ExternalNavigationReplaced -> hostHeldNavigation = hostHeldNavigation?.let(::replacePendingExternalNavigation)
        }
        val heldFallback = hostHeldNavigation
        if (heldFallback != null) {
            hostHeldNavigation =
                when {
                    // The reducer's own slot is the single slot: a duplicate fallback is dropped (its
                    // retained share, if different, is released by the seam below).
                    landed.pendingExternalNavigationSlot() != null -> null
                    landed.carriesExternalNavigationSlot() -> {
                        landed = reducer.reduce(landed, P503UiEvent.ExternalNavigationDecisionLanded(ExternalNavigationDecision.Held(heldFallback)))
                        null
                    }
                    else -> heldFallback
                }
        }
        // P7-09 09.B / T11 (spec section 3 line 150 "Retry 成功后重新过导航门"): the STARTUP-WAIT
        // slot's re-entry into the SAME navigation gate. A request held while the runtime was not
        // Ready is CARRIED onto the overview by the initial load but was never re-routed, so a
        // cold-start 记支出/导入 stopped at the Busy strip while the hot path went straight through.
        // The pure seam (startupHeldReRoutingDecision) re-applies the FROZEN table with
        // `existingPending = null`; the runtime facts come from the SAME lease-face observation
        // [onExternalNavigationRequested] uses, so both paths route identically. It returns null when
        // the slot is not a startup-wait slot (a share awaiting its format, or one awaiting the user's
        // retain/replace decision, is never re-routed) or when the conclusion changes nothing, so this
        // cannot churn. Self-terminating: the routed slot is consumed and any other landing leaves a
        // non-STARTUP_NOT_READY reason.
        startupHeldReRoutingDecision(
            state = landed,
            facts =
                ExternalNavigationRuntimeFacts.from(
                    activeGeneration = ledger.activeGenerationForLanding(),
                    switchingRuntime = (landed as? P503AppState.BackupRestore)?.runningConfirm == true,
                ),
            existingPending = landed.pendingExternalNavigationSlot(),
        )?.let { reRouted -> landed = reducer.reduce(landed, P503UiEvent.ExternalNavigationDecisionLanded(reRouted)) }
        // P7-09 09.B / T11 (spec section 3 line 151 "启动失败**不消费**请求"): a failed INITIAL read
        // advances the surface to InfrastructureFailure(READ), which carries NO pending slot, so the
        // `reduceReady` transition DROPS the startup-held request (and the RetryRefresh rebuild does not
        // restore it). This holds it in the EXISTING host fallback instead, so the request stays VISIBLE
        // and is never consumed by the failure; the ordinary flush below returns it to the reducer once a
        // slot surface is live again, where the re-entry above routes it. The seam returns null for every
        // other case (a surface that carries the slot, or a non-startup-wait slot), so it cannot clobber
        // an unrelated hold. `holdExplicitlyCleared` is the `clearsExternalNavigation(event)` decision
        // the `when` above just honoured, passed in so this seam can never UNDO an explicit user clear
        // (保留当前 / 放弃后切换): re-holding there would resurrect the Busy strip the user just
        // dismissed and contradict §2.2/§3 on every non-slot surface.
        startupHeldFallbackAfterLanding(landed, previousHeld, holdExplicitlyCleared = clearsExternalNavigation(event))
            ?.let { preserved -> hostHeldNavigation = preserved }
        val nextHeld = landed.pendingExternalNavigationSlot() ?: hostHeldNavigation
        // Release EVERY retained share source that was held before (the reducer slot OR the dropped
        // fallback) and is NO LONGER held (as `request` OR `displaced`) — the ONE point that
        // guarantees no retained grant outlives its use on any abandon path. NF1: the set difference
        // covers the DISPLACED share too (a second request held for the retain/replace decision).
        val releaseIds =
            releasedShareDeliveryIds(previousHeld, nextHeld) +
                releasedShareDeliveryIds(previousFallback, nextHeld)
        releaseIds.forEach { deliveryId ->
            ledger.shareImportSource?.release(deliveryId)
        }
        state = landed.also { latestState.value = it }
    }
    // ---------------------------------------------------------------- P7-08 08.C tag/merchant filter + catalog host surface

    /**
     * P7-08 08.C (spec sections 5.1/5.3): one bounded filter-page read, run OFF the UI thread
     * under ONE lease that also resolves the expected catalog generation (the D-186
     * compare-in-transaction gate consumes it inside the read's single snapshot). The landing
     * is fingerprint-gated by the reducer (an old-condition return never overwrites the page),
     * generation-gated here (a superseded lease never dispatches at all), and token-gated by
     * the reducer (fix round P3-1: the landing presents the request token THIS dispatch was
     * assigned; a read duplicated or superseded by a later dispatch can never append or merge).
     */
    fun requestTagMerchantFilterPage(
        state: P503AppState.TagMerchantFilter,
        replace: Boolean,
        cursor: TagMerchantFilterCursor?,
    ) {
        if (!tagMerchantFilterWired) return
        dispatch(P503UiEvent.RequestTagMerchantFilterPage(replace))
        // Fix round (P3-1): dispatch is synchronous, so the token read back here is the one the
        // reducer assigned to THIS request. A null means the event landed outside the filter
        // surface (absorbed, no token advanced) — fail closed, no read is launched.
        val requestToken = (latestState.value as? P503AppState.TagMerchantFilter)?.pageRequestToken ?: return
        val fingerprint = tagMerchantFilterConditionFingerprint(ledger.ledgerId, state.condition)
        val condition = state.condition
        scope.launch(Dispatchers.Default) {
            val outcome =
                ledger.leased { facade, _ ->
                    val expected =
                        facade.tagMerchantExpectedCatalogItemVersion()
                            ?: return@leased TagMerchantFilterReadResult.Failed(TagMerchantFilterFailureCode.FILTER_UNAVAILABLE)
                    val read = facade.queryTagMerchantFilterPage
                    if (read == null) {
                        TagMerchantFilterReadResult.Failed(TagMerchantFilterFailureCode.FILTER_UNAVAILABLE)
                    } else {
                        try {
                            read.query(ledger.ledgerId, condition, expected, TAG_MERCHANT_FILTER_PAGE_SIZE, cursor)
                        } catch (failure: kotlinx.coroutines.CancellationException) {
                            throw failure
                        } catch (failure: Exception) {
                            TagMerchantFilterReadResult.Failed(TagMerchantFilterFailureCode.FILTER_UNAVAILABLE)
                        }
                    }
                }
            val result =
                (outcome as? LeaseOutcome.Completed)
                    ?.takeIf { ledger.isCurrentGeneration(it.generation) }
                    ?.value
                    ?: TagMerchantFilterReadResult.Failed(TagMerchantFilterFailureCode.FILTER_UNAVAILABLE)
            scope.launch { dispatch(P503UiEvent.TagMerchantFilterPageLanded(fingerprint, requestToken, result)) }
        }
    }

    /**
     * P7-08 08.C (spec section 5.4): one bounded merchant accumulation read for the surface's
     * effective month; the landing is fingerprint-, month- AND request-token-gated by the
     * reducer (the token twin of [requestTagMerchantFilterPage], fix round P3-1).
     */
    fun requestMerchantAccumulation(state: P503AppState.TagMerchantFilter) {
        if (!tagMerchantFilterWired) return
        val merchantId = state.condition.merchantId ?: return
        dispatch(P503UiEvent.RequestMerchantAccumulation)
        val requestToken = (latestState.value as? P503AppState.TagMerchantFilter)?.accumulationRequestToken ?: return
        val fingerprint = tagMerchantFilterConditionFingerprint(ledger.ledgerId, state.condition)
        val month = state.accumulationMonth
        scope.launch(Dispatchers.Default) {
            val outcome =
                ledger.leased { facade, _ ->
                    val expected =
                        facade.tagMerchantExpectedCatalogItemVersion()
                            ?: return@leased MerchantMonthlyAccumulationResult.Failed(TagMerchantFilterFailureCode.FILTER_UNAVAILABLE)
                    val read = facade.queryMerchantAccumulation
                    if (read == null) {
                        MerchantMonthlyAccumulationResult.Failed(TagMerchantFilterFailureCode.FILTER_UNAVAILABLE)
                    } else {
                        try {
                            read.query(ledger.ledgerId, merchantId, month, expected)
                        } catch (failure: kotlinx.coroutines.CancellationException) {
                            throw failure
                        } catch (failure: Exception) {
                            MerchantMonthlyAccumulationResult.Failed(TagMerchantFilterFailureCode.FILTER_UNAVAILABLE)
                        }
                    }
                }
            val result =
                (outcome as? LeaseOutcome.Completed)
                    ?.takeIf { ledger.isCurrentGeneration(it.generation) }
                    ?.value
                    ?: MerchantMonthlyAccumulationResult.Failed(TagMerchantFilterFailureCode.FILTER_UNAVAILABLE)
            scope.launch { dispatch(P503UiEvent.MerchantAccumulationLanded(fingerprint, requestToken, month, result)) }
        }
    }

    /**
     * The filter surface's fresh request after OPEN or a condition/month change: one replace
     * page read (spec section 5.3: a new condition is a new generation request) plus the
     * accumulation when the condition names a merchant.
     */
    fun requestTagMerchantFilterFresh() {
        val current = latestState.value as? P503AppState.TagMerchantFilter ?: return
        requestTagMerchantFilterPage(current, replace = true, cursor = null)
        if (current.condition.merchantId != null) requestMerchantAccumulation(current)
    }

    /** Opens the filter surface from the analysis tab's entry (the effective month seeds the accumulation). */
    fun openTagMerchantFilter() {
        if (!tagMerchantFilterWired) return
        val month =
            (latestState.value as? P503AppState.OverviewEmpty)?.selectedMonth
                ?: resolvedCurrentMonth
                ?: MonthlyBuckets.currentMonth(ledger.ledgerClock)
        dispatch(P503UiEvent.OpenTagMerchantFilter(month))
        tagMerchantFilterEverRequested = true
        requestTagMerchantFilterFresh()
    }

    /**
     * T5 (spec section 5.5): a condition or month change is a NEW generation request — dispatch
     * the pure event (which resets the stale results) and immediately re-request.
     */
    fun dispatchTagMerchantFilterChange(event: P503UiEvent) {
        dispatch(event)
        requestTagMerchantFilterFresh()
    }

    /** T5: arms the post-refresh filter re-request (only once the surface has been opened). */
    fun armTagMerchantFilterReRequestIfLoaded() {
        if (tagMerchantFilterEverRequested) pendingTagMerchantFilterReRequestAfterRefresh = true
    }

    /** T5: completes the armed re-request after the authoritative refresh landed. */
    fun consumeTagMerchantFilterReRequestAfterRefresh() {
        if (!pendingTagMerchantFilterReRequestAfterRefresh) return
        pendingTagMerchantFilterReRequestAfterRefresh = false
        val current = latestState.value as? P503AppState.TagMerchantFilter ?: return
        if (current.loadedEntries.isEmpty() && current.accumulation == null) return
        requestTagMerchantFilterPage(current, replace = true, cursor = null)
        if (current.condition.merchantId != null) requestMerchantAccumulation(current)
    }

    /** T5: clears the armed re-request on a failed refresh landing (the budget drop's twin). */
    fun dropTagMerchantFilterReRequestAfterFailedRefresh() {
        pendingTagMerchantFilterReRequestAfterRefresh = false
    }

    // P5-04.2: system back only intercepts while the editor flow is on screen and carries the
    // overview snapshot needed to close back to the originating tab. Submitting swallows the
    // back to avoid exiting the process mid-submission; only non-Submitting states dispatch.
    // D-137: while a picker dialog is open, the back channel is additionally disabled, so
    // Esc / system back reaches only the dialog layer and the edit page stays open.
    val backEnabled = isBackEnabled(state)
    backHandler?.invoke(backEnabled && !editDialogOpen) {
        // P5-04.3 double-fire guard: re-check at dispatch time and only dispatch while the
        // state is still Back-legal, so a repeated back (fast double Esc / double system
        // back) is ignored instead of crashing on (OverviewEmpty, Back).
        if (isBackDispatchSafe(latestState.value)) dispatch(P503UiEvent.Back)
    }

    // The parse/display currency follows the selected primary account (spec section 4.1);
    // fall back to the facade currency only when no account is selected yet.
    fun resolvedCurrency(draft: TypedEntryDraft): CurrencyUnit =
        when (draft) {
            is IncomeDraft ->
                incomeOptions.receivingAccounts.firstOrNull { it.accountId == draft.receivingAccountId }?.currency ?: ledger.currency
            is ExpenseDraft ->
                options.paymentAccounts.firstOrNull { it.accountId == draft.paymentAccountId }?.currency ?: ledger.currency
            is TransferDraft ->
                transferOptions.ownedAssetAccounts.firstOrNull { it.accountId == draft.sourceAccountId }?.currency ?: ledger.currency
            is LendDraft ->
                lendingOptions.ownedAssetAccounts.firstOrNull { it.accountId == draft.fundingAccountId }?.currency ?: ledger.currency
            is CollectDraft ->
                lendingOptions.ownedAssetAccounts.firstOrNull { it.accountId == draft.destinationAccountId }?.currency ?: ledger.currency
        }

    // A-02 FIX-MONTH-1 (D-152): forward wiring slot for the host coordinator below. The
    // coordinator is constructed after this point (its callbacks need ::refresh/::submit/
    // ::checkCommitStatus, which are themselves declared around it), while refresh() must
    // complete trigger (e)'s armed monthly re-request on its landing hop — Kotlin forbids a
    // local function from capturing a local declared later, so the landing hop reads the
    // coordinator through this slot. Assignment happens immediately after the construction;
    // every refresh() actually runs post-wiring (LaunchedEffect / user callbacks), so the slot
    // is never observed null by a real refresh.
    var landingHopCoordinator: P503HostCoordinator? = null

    /**
     * Authoritative current-state refresh (the frozen layer-2 refresh chain, spec section 2.3).
     * A-PERF (rework path 1a): the read itself runs OFF the UI thread
     * (`Dispatchers.Default`) behind the single-flight [P503CurrentStateLoadCoordinator], and the
     * result hops back ON the composition's main dispatcher before dispatching
     * (P704C-SPEC-01/QUAL-02 — the nested `scope.launch` hop keeps every reducer event serial on
     * the main thread; `dispatch` is a non-atomic read-modify-write, so a Default-thread dispatch
     * interleaved with user events could drop a transition).
     *
     * Serialization: the coordinator admits one load at a time and every result lands before any
     * coalesced re-run starts, so a late stale result can never overwrite a newer state (S2-3);
     * requests arriving mid-run coalesce into exactly one deferred re-run that observes
     * everything committed in between (the reducer's Created/NoChange/Recovered auto-refresh
     * chain may legitimately trigger several refreshes in quick succession).
     */
    fun refresh() {
        currentStateLoadCoordinator.startLoadOnce {
            val intent = retainedIntent
            scope.launch(Dispatchers.Default) {
                // APQUAL-05: the read is guarded like every other background read of this batch
                // (runImportIntakeStatisticsRefresh/requestCatalogSnapshotLoad) — an unexpected
                // throw maps to the typed RefreshFailed instead of crashing the coroutine scope,
                // and the slot release below is guaranteed by running it in the main-dispatcher
                // hop regardless of the outcome.
                // P7-06 06.1 (D-176; spec 4.3/4.4): the read runs under an operation lease; the
                // acquire-time generation travels to the landing hop, which discards a result
                // captured under a superseded graph (P706-A07).
                val outcome = ledger.leased { facade, _ -> runCatching { facade.queryCurrentState.query() }.getOrNull() }
                // Back on the main dispatcher: consume the retained intent and dispatch serially
                // with every other main-thread event (the same consume-on-success semantics as
                // the previous synchronous body — a failed read keeps the intent for the retry).
                scope.launch {
                    if (outcome is LeaseOutcome.Completed && !ledger.isCurrentGeneration(outcome.generation)) {
                        // Stale generation: discard the payload entirely (no dispatch, no intent
                        // consumption), release the slot, and let a coalesced re-run proceed.
                        if (currentStateLoadCoordinator.loadCompleted()) refresh()
                        return@launch
                    }
                    when (val result = (outcome as? LeaseOutcome.Completed)?.value) {
                        is LedgerCurrentStateResult.Success -> {
                            // P7-02.A E-2: the intent is consumed only by a successful
                            // authoritative refresh; a failed read keeps it so the READ retry can
                            // still forward it (P3-3).
                            // APQUAL-02: consume only the intent THIS load captured at its
                            // admission point (the pure [consumeRetainedIntentAfterRefresh]
                            // decision) — an intent submitted while the read was in flight
                            // survives for its own refresh.
                            retainedIntent = consumeRetainedIntentAfterRefresh(retainedIntent, intent)
                            // P7-02.D E-4: every refresh that builds a fresh overview carries the
                            // pin mirror so the persisted pins survive success-result and
                            // READ-retry constructions.
                            dispatch(P503UiEvent.RefreshResult(result.state, intent, pinnedTargets))
                            // A-02 FIX-MONTH-1 (D-152): trigger (e)'s monthly re-request completes
                            // HERE, after the refreshed overview landed (the former synchronous
                            // request beside the refresh read the still-transient result state and
                            // its payload was absorbed, leaving the month card AWAITING). One
                            // unconditional request stamped on the landed month; a coalesced
                            // re-run lands later with the pending flag already consumed. The slot
                            // wiring note lives on [landingHopCoordinator] above.
                            landingHopCoordinator?.consumeMonthlyReRequestAfterRefresh(latestState.value)
                            // P7-07 07.D: the SAME landing hop completes the armed budget
                            // re-request (exactly one, stamped on the landed month).
                            landingHopCoordinator?.consumeBudgetMonthReRequestAfterRefresh(latestState.value)
                            // P7-08 08.C (T5): the SAME landing hop completes the armed
                            // tag/merchant filter re-request (exactly one, replace-mode).
                            consumeTagMerchantFilterReRequestAfterRefresh()
                        }
                        else -> {
                            dispatch(P503UiEvent.RefreshFailed)
                            // A-02 FIX-MONTH-1 (D-152): a failed landing requests nothing; the
                            // stale trigger must not be consumed by a later unrelated landing.
                            landingHopCoordinator?.dropMonthlyReRequestAfterFailedRefresh()
                            landingHopCoordinator?.dropBudgetMonthReRequestAfterFailedRefresh()
                            dropTagMerchantFilterReRequestAfterFailedRefresh()
                        }
                    }
                    if (currentStateLoadCoordinator.loadCompleted()) refresh()
                }
            }
        }
    }

    /**
     * P7-03.C/D: one unified monthly cycle for the effective overview month (selection or
     * clock-resolved 本月， R-Q06-2): month card payload, SelectMonth domain, trend and the
     * sorted flow rows, dispatched as one [P503UiEvent.MonthlyActivityResult]. The three reads
     * are folded by [foldMonthlyCycle] (F3): a shortfall in ANY of them surfaces as the same
     * typed failure (InvalidState/Unavailable) — never as a disabled selector or a bare
     * 暂无趋势数据 (R-Q06-4). The host mirrors (trend, flow rows) are replaced only by a fully
     * successful cycle, so the retained-overview failure surface keeps the last successful
     * payload on screen (spec 4.3/C04). Called only on the frozen trigger set (a)-(e) by the
     * coordinator.
     */
    fun requestMonthlyPayload() {
        val overview = latestState.value as? P503AppState.OverviewEmpty
        // D-203 startup trace: the monthly payload read window begins (main thread).
        StartupTrace.emit("read.monthlyPayload begin")
        try {
            val clockMonth = MonthlyBuckets.currentMonth(ledger.ledgerClock)
            resolvedCurrentMonth = clockMonth
            val effectiveMonth = overview?.selectedMonth ?: clockMonth
            // P7-06 06.1 (D-176; spec 4.3(b)): this synchronous main-thread cycle is one
            // operation-lease scope; a non-Ready runtime yields null and the typed Unavailable
            // landing below (never a blocked UI thread).
            val outcome =
                ledger.probe { facade ->
                    val monthlyQuery = facade.queryMonthlyActivity ?: return@probe null
                    val cycle =
                        foldMonthlyCycle(
                            monthResult = monthlyQuery.query(effectiveMonth),
                            selectableMonthsResult = monthlyQuery.selectableMonths(),
                            trendResult = monthlyQuery.trend(),
                        )
                    val page = if (cycle is MonthlyCycleOutcome.Ready) facade.queryLedgerFlowPage?.firstPage() else null
                    val total = if (page is LedgerFlowPageResult.Success) facade.queryLedgerFlowPage?.total() else null
                    Triple(cycle, page, total)
                } ?: run {
                    // D-203 startup trace: the runtime was not Ready for the monthly read.
                    StartupTrace.emit("read.monthlyPayload end kind=notReady")
                    return
                }
            when (val cycle = outcome.first) {
                is MonthlyCycleOutcome.Ready -> {
                    // D-203 startup trace: the monthly read completed ready.
                    StartupTrace.emit("read.monthlyPayload end kind=ready")
                    monthlyTrend = cycle.trend
                    // DEF-B (D-268): the first page replaces any previous cycle's pages. A failed
                    // first page falls back to the bounded current-state list (the same branch a
                    // not-loaded monthly cycle uses) instead of rendering a silently empty flow.
                    val page = outcome.second
                    flowRows =
                        if (page is LedgerFlowPageResult.Success) {
                            flowGeneration += 1
                            FlowRowsView(
                                rows = page.page.rows,
                                total = (outcome.third as? LedgerFlowTotalResult.Success)?.total ?: page.page.rows.size,
                                nextCursor = page.page.nextCursor,
                                generation = flowGeneration,
                            )
                        } else {
                            null
                        }
                    dispatch(P503UiEvent.MonthlyActivityResult(ApplicationMonthlyActivityResult.Success(cycle.activity), cycle.selectableMonths))
                }
                is MonthlyCycleOutcome.Failed -> {
                    // D-203 startup trace: the monthly read completed failed.
                    StartupTrace.emit("read.monthlyPayload end kind=failed")
                    dispatch(P503UiEvent.MonthlyActivityResult(cycle.result, emptyList()))
                }
            }
        } catch (failure: Exception) {
            // D-203 startup trace: the monthly read errored (cause class only).
            StartupTrace.emit("read.monthlyPayload end kind=error causeClass=${failure::class.simpleName}")
            dispatch(P503UiEvent.MonthlyActivityResult(ApplicationMonthlyActivityResult.Unavailable, emptyList()))
        }
    }

    /**
     * DEF-B (D-268): one more HOME flow page. Single-flight through [FlowRowsView.loadingMore];
     * a landing only applies when its generation still matches, so a monthly reload (which
     * replaces the whole list) discards an older page. A failed page keeps the loaded rows and
     * sets the footer's retry state; it never clears the list (R-Q06-4).
     */
    fun requestNextFlowPage() {
        val current = flowRows ?: return
        val cursor = current.nextCursor ?: return
        if (current.loadingMore) return
        val generation = current.generation
        flowRows = current.copy(loadingMore = true, loadFailed = false)
        val result =
            try {
                ledger.probe { facade -> facade.queryLedgerFlowPage?.pageAfter(cursor) }
            } catch (failure: Exception) {
                null
            }
        val latest = flowRows
        if (latest == null || latest.generation != generation) return
        flowRows =
            when (result) {
                is LedgerFlowPageResult.Success ->
                    latest.copy(
                        rows = (latest.rows + result.page.rows).distinctBy { it.transactionId },
                        nextCursor = result.page.nextCursor,
                        loadingMore = false,
                        loadFailed = false,
                    )
                else -> latest.copy(loadingMore = false, loadFailed = true)
            }
    }

    // ---------------------------------------------------------------- P7-07 07.D budget host surface

    /**
     * P7-07 07.D: one budget month request (the coordinator's budget trigger set calls this).
     * ONE bounded read under a lease, folded once per configured scope, dispatched as one
     * [P503UiEvent.BudgetMonthLanded]; the expected catalog version resolves from the session's
     * current authority, so a snapshot-generation mismatch fails typed (ruling B). An unwired
     * surface or a non-Ready runtime requests nothing (no region is rendered either way).
     */
    fun requestBudgetMonthPayload() {
        if (!budgetWired) return
        // P1-2 fix round: every state that CARRIES a preserved overview can receive the
        // landing (the reducer applies BudgetMonthLanded to the carried overview on each of
        // them), so the coordinator's stamped request is never silently dropped behind an
        // open surface. States with no overview to render the region keep the monthly
        // payload's own early-return semantics (mirrored, not changed for monthly).
        val current = latestState.value
        // P3 review round: the carried-overview set has ONE source
        // (P503AppState.carriedBudgetMonthOverview) shared with the reducer.
        val overview = current.carriedBudgetMonthOverview() ?: return
        try {
            val clockMonth = MonthlyBuckets.currentMonth(ledger.ledgerClock)
            resolvedCurrentMonth = clockMonth
            val effectiveMonth = overview.selectedMonth ?: clockMonth
            val landed =
                ledger.probe { facade ->
                    val query = facade.queryBudgetMonth ?: return@probe null
                    // P3 fix round: an unresolvable expected catalog version is an explicit
                    // typed Unavailable landing (fail-closed, visible), never a silent skip
                    // that would leave the coordinator's stamped request unanswered.
                    val expected = facade.budgetExpectedCatalogVersion() ?: return@probe BudgetMonthViewResult.Unavailable
                    query.queryView(ledger.ledgerId, effectiveMonth, expected)
                }
            if (landed != null) dispatch(P503UiEvent.BudgetMonthLanded(landed))
        } catch (failure: Exception) {
            dispatch(P503UiEvent.BudgetMonthLanded(BudgetMonthViewResult.Unavailable))
        }
    }

    /**
     * P7-07 07.D: opens the config surface for one scope of the effective month. The scope's
     * current authority is resolved HERE (under a lease, the single-target revision lookup),
     * so the event carries revision/closed/limit and the reducer never reads the facade.
     */
    fun openBudgetConfig(scope: BudgetScope) {
        if (!budgetWired) return
        val current = latestState.value as? P503AppState.OverviewEmpty ?: return
        val month = current.selectedMonth ?: resolvedCurrentMonth ?: return
        val target =
            BudgetTarget(
                ledgerId = ledger.ledgerId,
                monthKey = budgetMonthKey(month),
                currency = BUDGET_CONFIGURED_CURRENCY,
                scopeKey = budgetScopeKey(scope),
                scopeCategoryId = (scope as? BudgetScope.Category)?.categoryId,
            )
        val authority = ledger.probe { facade -> facade.budgetAuthorityReader?.load(target) }
        dispatch(
            P503UiEvent.OpenBudgetConfig(
                scope = scope,
                month = month,
                revision = authority?.revision ?: 0L,
                closed = authority != null && authority.revision > 0L && authority.limitMinorUnits == null,
                limitMinorUnits = authority?.limitMinorUnits,
            ),
        )
    }

    /**
     * P7-07 07.D: runs the 07.B commit off the UI thread inside ONE operation lease; the
     * captured generation gates the landing hop (the commitTransactionCorrection shape). The
     * store maps its own constraint/claim failures to typed results; an exception ESCAPING
     * that boundary is an infrastructure failure and lands as the explicit 结果未知 state
     * ([P503UiEvent.BudgetCommitUnknownLanded]) — never silence, never a fabricated typed
     * result, and a cancellation is RETHROWN (取消 is not 结果未知). A determinate success
     * changed the budget observation surface with zero transaction effect, so the coordinator
     * re-requests the budget month directly (no authoritative refresh rides it).
     */
    fun runBudgetCommit(plan: BudgetCommitPlan) {
        scope.launch(Dispatchers.Default) {
            // P3 fix round: an exception escaping the 07.B boundary lands as the explicit
            // 结果未知 state (see BudgetCommitUnknownLanded) — the store maps its own
            // constraint/claim failures to typed results, so an escape is an infrastructure
            // failure and must claim neither success nor a typed rejection. A re-attempt is
            // CAS-safe (a landed first commit returns the typed revision conflict).
            val outcome =
                ledger.leased { facade, _ ->
                    try {
                        val save = facade.saveBudgetConfiguration!!
                        if (plan.close) {
                            save.close(ledger.ledgerId, plan.month, plan.scope, plan.expectedRevision)
                        } else {
                            save.setLimit(ledger.ledgerId, plan.month, plan.scope, requireNotNull(plan.limitMinorUnits), plan.expectedRevision)
                        }
                    } catch (failure: kotlinx.coroutines.CancellationException) {
                        // P3 review round: a cancelled lease block is NOT an unknown outcome —
                        // rethrow so structured cancellation keeps its semantics.
                        throw failure
                    } catch (failure: Exception) {
                        null
                    }
                }
            scope.launch {
                if (outcome !is LeaseOutcome.Completed || !ledger.isCurrentGeneration(outcome.generation)) return@launch
                val result = outcome.value
                if (result == null) {
                    dispatch(P503UiEvent.BudgetCommitUnknownLanded)
                    return@launch
                }
                dispatch(P503UiEvent.BudgetConfigResultLanded(result))
                if (result is BudgetCommandResult.Accepted || result is BudgetCommandResult.NoChange) {
                    // The forward-wiring slot (the A-02 FIX-MONTH-1 precedent): the coordinator
                    // is constructed later in the composition, so the landing hop reads it here.
                    landingHopCoordinator?.onBudgetConfigCommitted(latestState.value)
                }
            }
        }
    }

    /**
     * P7-07 07.D: the explicit save confirm. The draft is gated BEFORE the reducer marks
     * submitting (the P704C-QUAL-04 call-site precedent): an invalid draft never strands the
     * surface — the confirm affordance is already disabled, and this host guard is the second
     * line. The whole decision runs inside [dispatchCurrentP503Action]'s instance guard, so a
     * stale render can neither commit nor disturb an in-flight one. The 07.B commit mints its
     * own request id (claim-first replay identity), so unlike the correction flow the host
     * does not mint one here.
     */
    fun confirmBudgetLimit(current: P503AppState.BudgetConfig) {
        if (!budgetWired) return
        if (budgetLimitDraft(current.limitText, ledger.currency) !is BudgetLimitDraft.Valid) return
        var plan: BudgetCommitPlan? = null
        dispatchCurrentP503Action(
            current,
            latestState.value,
            {
                val draft = budgetLimitDraft(current.limitText, ledger.currency)
                if (draft is BudgetLimitDraft.Valid) {
                    plan =
                        BudgetCommitPlan(
                            scope = current.scope,
                            month = current.month,
                            limitMinorUnits = draft.minorUnits,
                            expectedRevision = current.revision,
                            close = false,
                        )
                }
                P503UiEvent.ConfirmBudgetLimit
            },
            ::dispatch,
        ) {
            plan?.let(::runBudgetCommit)
        }
    }

    /** P7-07 07.D: the explicit close-monitoring confirm (the same run/landing discipline). */
    fun confirmBudgetClose(current: P503AppState.BudgetConfig) {
        if (!budgetWired) return
        var plan: BudgetCommitPlan? = null
        dispatchCurrentP503Action(
            current,
            latestState.value,
            {
                plan =
                    BudgetCommitPlan(
                        scope = current.scope,
                        month = current.month,
                        limitMinorUnits = null,
                        expectedRevision = current.revision,
                        close = true,
                    )
                P503UiEvent.ConfirmBudgetClose
            },
            ::dispatch,
        ) {
            plan?.let(::runBudgetCommit)
        }
    }

    /** P7-03.C: opens the read-only detail with the host-resolved typed payload (C03). */
    fun selectTransaction(transactionId: TransactionId) {
        // P7-06 06.1 (spec 4.3(b)): the synchronous detail read runs under an operation lease.
        // P7-08 08.D (spec section 3.5): the SAME lease reads the annotation history (the R-222-8
        // read-discipline — no facade read outside a lease); a null (unwired/unreadable) renders no
        // history block, an empty list is the honest "never annotated".
        val read =
            ledger.probe { facade ->
                facade.queryTransactionDetail?.query(transactionId)?.let { detail -> detail to facade.transactionAnnotationHistory(transactionId) }
            } ?: return
        val (detail, history) = read
        dispatch(P503UiEvent.SelectTransaction(transactionId, detail))
        if (detail is TransactionDetailResult.Success && history != null) {
            dispatch(P503UiEvent.TransactionAnnotationHistoryLanded(transactionId, history))
        }
    }

    // P5-04.3: shared input construction for the submission and the unknown-commit status
    // check so both build a field-identical per-type snapshot (the resolver compares field by
    // field). Returns null when the draft is incomplete.

    /**
     * P7-08 08.B-2 (spec section 4.2): the confirmation-time re-validation of the draft's optional
     * associations. The draft keeps its selections across a type switch, so between the user's
     * choice and the confirm a tag/merchant can have been deleted (tombstoned), disabled or become
     * cross-ledger; the commit's own in-transaction validation would then reject the whole request
     * for a stale reference the user never saw. Filtering here mirrors the account/category
     * clearing semantics and keeps the confirmation path honest: only still-selectable ids reach
     * the request, exactly like the frozen `RetainedIntentRevalidation` behavior for accounts and
     * categories. A null projection (unwired root) passes the selection through unchanged — the
     * pre-08.B-2 behavior — and the store's own authority check remains the final gate.
     */
    fun revalidatedAssociation(draft: TypedEntryDraft): Pair<Set<TagId>, MerchantId?> {
        val selection = cachedTagMerchantSelection ?: return draft.tagIds to draft.merchantId
        val selectableTags = selection.tags.mapTo(mutableSetOf()) { TagId(it.id) }
        val selectableMerchants = selection.merchants.mapTo(mutableSetOf()) { MerchantId(it.id) }
        return draft.tagIds.filterTo(mutableSetOf()) { it in selectableTags } to
            draft.merchantId?.takeIf { it in selectableMerchants }
    }

    fun expenseSaveInput(
        draft: ExpenseDraft,
        requestId: RequestId,
    ): ManualExpenseSaveInput? {
        val currency = resolvedCurrency(draft)
        val parsed = ledger.parseAmount.parse(draft.amountText, currency)
        val amount = (parsed as? ParseManualExpenseAmount.Result.Valid)?.let { Money.ofMinor(it.minorUnits, currency) }
        val categoryId = draft.categoryId
        val paymentAccountId = draft.paymentAccountId
        val occurredAt = draft.occurredAt
        if (amount == null || categoryId == null || paymentAccountId == null || occurredAt == null) {
            return null
        }
        val (tagIds, merchantId) = revalidatedAssociation(draft)
        return ManualExpenseSaveInput(
            ledgerId = ledger.ledgerId,
            requestId = requestId,
            amount = amount,
            categoryId = categoryId,
            paymentAccountId = paymentAccountId,
            occurredAt = occurredAt,
            note = draft.note,
            // P7-08 08.B-1 (D-221; R3): the confirming action samples the ledger clock ONCE.
            createdAt = ledger.ledgerClock.now(),
            tagIds = tagIds,
            merchantId = merchantId,
            confirmation = ExplicitManualSave,
        )
    }

    fun incomeSaveInput(
        draft: IncomeDraft,
        requestId: RequestId,
    ): ManualIncomeSaveInput? {
        val currency = resolvedCurrency(draft)
        val parsed = ledger.parseAmount.parse(draft.amountText, currency)
        val amount = (parsed as? ParseManualExpenseAmount.Result.Valid)?.let { Money.ofMinor(it.minorUnits, currency) }
        val categoryId = draft.categoryId
        val receivingAccountId = draft.receivingAccountId
        val occurredAt = draft.occurredAt
        if (amount == null || categoryId == null || receivingAccountId == null || occurredAt == null) {
            return null
        }
        val (tagIds, merchantId) = revalidatedAssociation(draft)
        return ManualIncomeSaveInput(
            ledgerId = ledger.ledgerId,
            requestId = requestId,
            amount = amount,
            categoryId = categoryId,
            receivingAccountId = receivingAccountId,
            occurredAt = occurredAt,
            note = draft.note,
            createdAt = ledger.ledgerClock.now(),
            tagIds = tagIds,
            merchantId = merchantId,
            confirmation = ExplicitManualSave,
        )
    }

    fun transferSaveInput(
        draft: TransferDraft,
        requestId: RequestId,
    ): ManualTransferSaveInput? {
        val currency = resolvedCurrency(draft)
        val creditParsed = ledger.parseAmount.parse(draft.destinationCredit, currency)
        val destinationCredit = (creditParsed as? ParseManualExpenseAmount.Result.Valid)?.let { Money.ofMinor(it.minorUnits, currency) }
        val fee =
            when {
                draft.fee.isBlank() -> Money.ofMinor(0L, currency)
                else -> (ledger.parseAmount.parse(draft.fee, currency) as? ParseManualExpenseAmount.Result.Valid)?.let { Money.ofMinor(it.minorUnits, currency) }
            }
        val sourceAccountId = draft.sourceAccountId
        val destinationAccountId = draft.destinationAccountId
        val occurredAt = draft.occurredAt
        if (destinationCredit == null || fee == null || sourceAccountId == null || destinationAccountId == null || occurredAt == null) {
            return null
        }
        val (tagIds, merchantId) = revalidatedAssociation(draft)
        return ManualTransferSaveInput(
            ledgerId = ledger.ledgerId,
            requestId = requestId,
            sourceAccountId = sourceAccountId,
            destinationAccountId = destinationAccountId,
            destinationCredit = destinationCredit,
            fee = fee,
            feeCategoryId = draft.feeCategoryId,
            occurredAt = occurredAt,
            note = draft.note,
            createdAt = ledger.ledgerClock.now(),
            tagIds = tagIds,
            merchantId = merchantId,
            confirmation = ExplicitManualSave,
        )
    }

    fun lendSaveInput(
        draft: LendDraft,
        requestId: RequestId,
    ): ManualLendSaveInput? {
        val currency = resolvedCurrency(draft)
        val amount = (ledger.parseAmount.parse(draft.amount, currency) as? ParseManualExpenseAmount.Result.Valid)?.let { Money.ofMinor(it.minorUnits, currency) }
        val counterpartyId = draft.counterpartyId
        val fundingAccountId = draft.fundingAccountId
        val occurredAt = draft.occurredAt
        if (amount == null || counterpartyId == null || fundingAccountId == null || occurredAt == null) return null
        val (tagIds, merchantId) = revalidatedAssociation(draft)
        return ManualLendSaveInput(
            ledgerId = ledger.ledgerId,
            requestId = requestId,
            counterpartyId = counterpartyId,
            fundingAccountId = fundingAccountId,
            amount = amount,
            occurredAt = occurredAt,
            note = draft.note,
            createdAt = ledger.ledgerClock.now(),
            tagIds = tagIds,
            merchantId = merchantId,
            confirmation = ExplicitManualSave,
        )
    }

    fun collectSaveInput(
        draft: CollectDraft,
        requestId: RequestId,
    ): ManualCollectSaveInput? {
        val currency = resolvedCurrency(draft)

        fun parsed(text: String): Money? = (ledger.parseAmount.parse(text, currency) as? ParseManualExpenseAmount.Result.Valid)?.let { Money.ofMinor(it.minorUnits, currency) }
        val totalReceived = parsed(draft.totalReceived)
        val principal = parsed(draft.principal)
        val interest = parsed(draft.interest)
        val counterpartyId = draft.counterpartyId
        val destinationAccountId = draft.destinationAccountId
        val interestCategoryId = draft.interestCategoryId
        val occurredAt = draft.occurredAt
        if (totalReceived == null || principal == null || interest == null || counterpartyId == null || destinationAccountId == null || interestCategoryId == null || occurredAt == null) return null
        val (tagIds, merchantId) = revalidatedAssociation(draft)
        return ManualCollectSaveInput(
            ledgerId = ledger.ledgerId,
            requestId = requestId,
            counterpartyId = counterpartyId,
            destinationAccountId = destinationAccountId,
            totalReceived = totalReceived,
            principal = principal,
            interest = interest,
            interestCategoryId = interestCategoryId,
            occurredAt = occurredAt,
            note = draft.note,
            createdAt = ledger.ledgerClock.now(),
            tagIds = tagIds,
            merchantId = merchantId,
            confirmation = ExplicitManualSave,
        )
    }

    fun submit(
        draft: TypedEntryDraft,
        requestId: RequestId,
    ) {
        // P7-02 E-2: capture the retained intent from the pre-submit draft before it leaves.
        retainedIntent = draft.toRetainedIntent(currentOriginTab(latestState.value))
        // P7-06 06.1 (D-176; spec 4.3(b)): the synchronous main-thread write runs under an
        // operation lease. A non-Ready runtime (typed RuntimeNotReady) never blocks the UI thread;
        // it surfaces the type's existing InfrastructureFailure result so the reducer keeps its
        // frozen SUBMISSION-failure semantics.
        val result =
            ledger.probe { facade ->
                when (draft) {
                    is ExpenseDraft -> {
                        val input = expenseSaveInput(draft, requestId)
                        if (input == null) {
                            ManualEntrySubmissionResult.Expense(
                                ManualExpenseSubmissionResult.Application(
                                    ManualExpenseSaveResult.InvalidInput(
                                        buildSet {
                                            add(ManualExpenseInputFailure.Missing(ManualExpenseInputField.AMOUNT))
                                            add(ManualExpenseInputFailure.Missing(ManualExpenseInputField.CATEGORY))
                                            add(ManualExpenseInputFailure.Missing(ManualExpenseInputField.PAYMENT_ACCOUNT))
                                        },
                                    ),
                                ),
                            )
                        } else {
                            facade.submitEntryOrExpense().submit(ManualEntrySaveInput.Expense(input))
                        }
                    }
                    is IncomeDraft -> {
                        val input = incomeSaveInput(draft, requestId)
                        val submission = facade.submitIncome
                        if (input == null || submission == null) {
                            ManualEntrySubmissionResult.Income(
                                ManualIncomeSubmissionResult.Application(
                                    ManualIncomeSaveResult.InvalidInput(
                                        buildSet {
                                            add(com.unifiedledger.application.ManualIncomeInputField.AMOUNT)
                                            add(com.unifiedledger.application.ManualIncomeInputField.CATEGORY)
                                            add(com.unifiedledger.application.ManualIncomeInputField.RECEIVING_ACCOUNT)
                                        },
                                    ),
                                ),
                            )
                        } else {
                            ManualEntrySubmissionResult.Income(submission.submit(input))
                        }
                    }
                    is TransferDraft -> {
                        val input = transferSaveInput(draft, requestId)
                        val submission = facade.submitEntry
                        if (input == null || submission == null) {
                            ManualEntrySubmissionResult.Transfer(
                                ManualTransferSubmissionResult.Application(
                                    ManualTransferSaveResult.InvalidInput(
                                        buildSet {
                                            add(ManualTransferInputField.SOURCE_ACCOUNT)
                                            add(ManualTransferInputField.DESTINATION_ACCOUNT)
                                            add(ManualTransferInputField.DESTINATION_CREDIT)
                                            // P702IMPL-07: the fee is part of the fallback field set.
                                            add(ManualTransferInputField.FEE)
                                        },
                                    ),
                                ),
                            )
                        } else {
                            submission.submit(ManualEntrySaveInput.Transfer(input))
                        }
                    }
                    is LendDraft -> {
                        val input = lendSaveInput(draft, requestId)
                        val submission = facade.submitEntry
                        if (input == null || submission == null) {
                            ManualEntrySubmissionResult.Lend(
                                ManualLendSubmissionResult.Application(
                                    ManualLendSaveResult.InvalidInput(
                                        buildSet {
                                            add(ManualLendInputField.COUNTERPARTY)
                                            add(ManualLendInputField.FUNDING_ACCOUNT)
                                            add(ManualLendInputField.AMOUNT)
                                        },
                                    ),
                                ),
                            )
                        } else {
                            submission.submit(ManualEntrySaveInput.Lend(input))
                        }
                    }
                    is CollectDraft -> {
                        val input = collectSaveInput(draft, requestId)
                        val submission = facade.submitEntry
                        if (input == null || submission == null) {
                            ManualEntrySubmissionResult.Collect(
                                ManualCollectSubmissionResult.Application(
                                    ManualCollectSaveResult.InvalidInput(
                                        buildSet {
                                            add(ManualCollectInputField.COUNTERPARTY)
                                            add(ManualCollectInputField.DESTINATION_ACCOUNT)
                                            add(ManualCollectInputField.TOTAL_RECEIVED)
                                            add(ManualCollectInputField.PRINCIPAL)
                                            add(ManualCollectInputField.INTEREST)
                                            add(ManualCollectInputField.INTEREST_CATEGORY)
                                        },
                                    ),
                                ),
                            )
                        } else {
                            submission.submit(ManualEntrySaveInput.Collect(input))
                        }
                    }
                }
            } ?: runtimeNotReadySubmission(draft)
        dispatch(P503UiEvent.SubmissionResult(result))
    }

    // P5-04.3: one read-only commit-status check for the unknown-commit flow, per entry type.
    // P7-06 06.1 (D-176; spec 4.3): the resolver call runs under an operation lease.
    fun checkCommitStatus(
        draft: TypedEntryDraft,
        requestId: RequestId,
    ) {
        if (statusCheckInFlight) return
        when (draft) {
            is ExpenseDraft -> {
                val input = expenseSaveInput(draft, requestId) ?: return
                val attempted =
                    ManualExpenseRequestSnapshot(
                        ledgerId = input.ledgerId,
                        amount = checkNotNull(input.amount),
                        categoryId = checkNotNull(input.categoryId),
                        paymentAccountId = checkNotNull(input.paymentAccountId),
                        occurredAt = input.occurredAt,
                        note = input.note,
                    )
                statusCheckInFlight = true
                scope.launch {
                    val resolution =
                        ledger.probe { facade ->
                            facade.resolveCommitStatus.resolve(ledger.ledgerId, requestId, attempted)
                        } ?: ManualExpenseCommitResolution.Unavailable
                    statusCheckInFlight = false
                    dispatch(P503UiEvent.CommitStatusResolved(ManualEntryCommitResolution.Expense(resolution)))
                }
            }
            is IncomeDraft -> {
                val input = incomeSaveInput(draft, requestId) ?: return
                // Base behavior (P3-4): an unwired resolver returns early (no dispatch); the typed
                // Unavailable path below is for a runtime refusal, not a missing surface.
                if (!ledger.surfaces.incomeCommitStatus) return
                val attempted =
                    ManualIncomeRequestSnapshot(
                        ledgerId = input.ledgerId,
                        amount = checkNotNull(input.amount),
                        categoryId = checkNotNull(input.categoryId),
                        receivingAccountId = checkNotNull(input.receivingAccountId),
                        occurredAt = input.occurredAt,
                        note = input.note,
                    )
                statusCheckInFlight = true
                scope.launch {
                    val resolution =
                        ledger.probe { facade ->
                            facade.resolveIncomeCommitStatus?.resolve(ledger.ledgerId, requestId, attempted)
                        } ?: ManualIncomeCommitResolution.Unavailable
                    statusCheckInFlight = false
                    dispatch(P503UiEvent.CommitStatusResolved(ManualEntryCommitResolution.Income(resolution)))
                }
            }
            is TransferDraft -> {
                val input = transferSaveInput(draft, requestId) ?: return
                // Base behavior (P3-4): an unwired resolver returns early (no dispatch).
                if (!ledger.surfaces.transferCommitStatus) return
                val attempted =
                    ManualTransferRequestSnapshot(
                        ledgerId = input.ledgerId,
                        sourceAccountId = checkNotNull(input.sourceAccountId),
                        destinationAccountId = checkNotNull(input.destinationAccountId),
                        destinationCredit = checkNotNull(input.destinationCredit),
                        fee = checkNotNull(input.fee),
                        feeCategoryId = input.feeCategoryId,
                        occurredAt = input.occurredAt,
                        note = input.note,
                    )
                statusCheckInFlight = true
                scope.launch {
                    val resolution =
                        ledger.probe { facade ->
                            facade.resolveTransferCommitStatus?.resolve(ledger.ledgerId, requestId, attempted)
                        } ?: ManualTransferCommitResolution.Unavailable
                    statusCheckInFlight = false
                    dispatch(P503UiEvent.CommitStatusResolved(ManualEntryCommitResolution.Transfer(resolution)))
                }
            }
            is LendDraft -> {
                val input = lendSaveInput(draft, requestId) ?: return
                // Base behavior (P3-4): an unwired resolver returns early (no dispatch).
                if (!ledger.surfaces.lendingCommitStatus) return
                val attempted =
                    ManualLendingRequestSnapshot(
                        ledgerId = input.ledgerId,
                        behavior = ManualLendingBehavior.LEND,
                        counterpartyId = checkNotNull(input.counterpartyId),
                        principalAccountId = checkNotNull(input.fundingAccountId),
                        amount = checkNotNull(input.amount),
                        interest = Money.ofMinor(0L, checkNotNull(input.amount).currency),
                        fee = Money.ofMinor(0L, checkNotNull(input.amount).currency),
                        totalReceived = null,
                        interestCategoryId = null,
                        occurredAt = input.occurredAt,
                        note = input.note,
                    )
                statusCheckInFlight = true
                scope.launch {
                    val resolution =
                        ledger.probe { facade ->
                            facade.resolveLendingCommitStatus?.resolve(ledger.ledgerId, requestId, attempted)
                        } ?: ManualLendingCommitResolution.Unavailable
                    statusCheckInFlight = false
                    dispatch(P503UiEvent.CommitStatusResolved(ManualEntryCommitResolution.Lend(resolution)))
                }
            }
            is CollectDraft -> {
                val input = collectSaveInput(draft, requestId) ?: return
                // Base behavior (P3-4): an unwired resolver returns early (no dispatch).
                if (!ledger.surfaces.lendingCommitStatus) return
                val attempted =
                    ManualLendingRequestSnapshot(
                        ledgerId = input.ledgerId,
                        behavior = ManualLendingBehavior.COLLECT,
                        counterpartyId = checkNotNull(input.counterpartyId),
                        principalAccountId = checkNotNull(input.destinationAccountId),
                        amount = checkNotNull(input.principal),
                        interest = checkNotNull(input.interest),
                        fee = Money.ofMinor(0L, checkNotNull(input.totalReceived).currency),
                        totalReceived = checkNotNull(input.totalReceived),
                        interestCategoryId = checkNotNull(input.interestCategoryId),
                        occurredAt = input.occurredAt,
                        note = input.note,
                    )
                statusCheckInFlight = true
                scope.launch {
                    val resolution =
                        ledger.probe { facade ->
                            facade.resolveLendingCommitStatus?.resolve(ledger.ledgerId, requestId, attempted)
                        } ?: ManualLendingCommitResolution.Unavailable
                    statusCheckInFlight = false
                    dispatch(P503UiEvent.CommitStatusResolved(ManualEntryCommitResolution.Collect(resolution)))
                }
            }
        }
    }

    // Initial authoritative load (the facade already implies startup completed).
    // A-PERF (rework path 1a): the same off-the-UI-thread read as refresh() behind the same
    // single-flight coordinator — the initial load and any refresh landing simultaneously admit
    // exactly one read, and the result hops back onto the main dispatcher before dispatching.
    // The pin seeding stays on the main thread (it reads the preference store after the result
    // lands; the store read is the entry-preference surface, not the current-state read this
    // batch governs).
    // D-215: the startup fires this read CONCURRENTLY with requestCatalogSnapshotLoad(), and the
    // runtime exposes a single global, non-blocking lease whose acquisition can refuse without
    // waiting. In run 37209978362 the read failed with `cause=leaseNotReady` 4ms after its begin
    // while the sibling catalog read completed in 55ms and `state=Ready` had already been emitted.
    // The cause is NOT proven: LeaseOutcome.NotReady has three sub-sources (facade unwired;
    // runtime not Ready / no activeGeneration; the non-blocking mutex.tryLock momentarily busy),
    // and the reviewer's counter-evidence refutes "the sibling query holds the lease" because
    // acquireLease releases the mutex before the block runs (see InitialReadLeaseRetry KDoc).
    // Fix A attributes the sub-source via `read.leaseNotReady reason=...` trace lines at each
    // origin; the retry deliberately covers ALL THREE (only the bare NotReady is observable here,
    // all three are plausibly transient at startup, the added window is bounded to at most
    // InitialReadLeaseRetry.MAX_ATTEMPTS attempts 100ms apart, and exhaustion still surfaces the
    // failure). The read is read-only, so re-attempting the SAME acquisition is safe; a Completed
    // outcome (success, stale generation, readThrew, or any readResult variant) is handled exactly
    // as before, and exhaustion preserves the D-214 fail-closed trace + InitialLoadFailed
    // dispatch unchanged. Trace honesty: `read.currentState begin` stays and the success path is
    // silent; each actual retry emits one bounded `read.currentState retry attempt=N` line.
    LaunchedEffect(Unit) {
        currentStateLoadCoordinator.startLoadOnce {
            // D-203 startup trace: the initial current-state read window begins (background).
            StartupTrace.emit("read.currentState begin")
            scope.launch(Dispatchers.Default) {
                // APQUAL-05: the same guarded read as refresh() — an unexpected throw maps to
                // the typed InitialLoadFailed and the slot release in the hop below is guaranteed.
                // P7-06 06.1 (D-176; spec 4.3/4.4): the read and the pin seeding run under one
                // operation lease; the captured generation gates the landing (stale => discard).
                fun readInitialState() =
                    ledger.leased { facade, _ ->
                        // D-207: the swallowed state-read failure is kept (runCatching behavior
                        // unchanged) so the D-203 trace can carry the exception class name.
                        val stateResult = runCatching { facade.queryCurrentState.query() }
                        val pins = runCatching { facade.entryPreferences?.pinnedTargets(ledger.ledgerId) }.getOrNull()
                        Triple(stateResult.getOrNull(), stateResult.exceptionOrNull(), pins)
                    }
                // D-215: retry ONLY a bare LeaseOutcome.NotReady, bounded by InitialReadLeaseRetry;
                // the same read-only acquisition is re-attempted. A Completed outcome — success,
                // stale generation, readThrew or any readResult variant — exits the loop
                // immediately and is handled unchanged.
                val outcome =
                    try {
                        var attemptsMade = 1
                        var attempt = readInitialState()
                        while (attempt is LeaseOutcome.NotReady && InitialReadLeaseRetry.shouldRetry(attemptsMade, InitialReadLeaseRetry.LEASE_NOT_READY_CAUSE)) {
                            // D-203 trace: one bounded line per actual retry (1..MAX_ATTEMPTS-1).
                            StartupTrace.emit("read.currentState retry attempt=$attemptsMade")
                            delay(InitialReadLeaseRetry.RETRY_DELAY_MS)
                            attempt = readInitialState()
                            attemptsMade += 1
                        }
                        attempt
                    } catch (failure: kotlinx.coroutines.CancellationException) {
                        // D-215 Fix C: `delay` is the first suspension point this initial-load
                        // path has ever had, so a cancellation landing during a retry would abort
                        // before the landing hop and leave the single-flight slot set. The only
                        // cancellation source is `scope` (cancelled when P503App leaves the
                        // composition), at which point this remember-scoped coordinator is
                        // discarded too — but release defensively anyway. NOT an unconditional
                        // finally: on the normal path the landing hop consumes the coalesced
                        // `deferredRequested` marker, and a Default-thread finally could consume
                        // it early; this catch only runs when the normal path did not start.
                        currentStateLoadCoordinator.loadCompleted()
                        throw failure
                    }
                // Back on the main dispatcher: seed the pin mirror, then dispatch serially.
                scope.launch {
                    if (outcome is LeaseOutcome.Completed && !ledger.isCurrentGeneration(outcome.generation)) {
                        // Stale generation: discard entirely (P706-A07), release the slot only.
                        // D-203 startup trace: the initial read landed on a stale generation.
                        StartupTrace.emit("read.currentState end kind=stale")
                        if (currentStateLoadCoordinator.loadCompleted()) refresh()
                        return@launch
                    }
                    when (val result = (outcome as? LeaseOutcome.Completed)?.value?.first) {
                        is LedgerCurrentStateResult.Success -> {
                            // D-203 startup trace: the initial read landed.
                            StartupTrace.emit("read.currentState end kind=success")
                            // P7-02.D E-4: seed the persisted pins so they survive an app restart.
                            (outcome as LeaseOutcome.Completed).value.third?.let { pins -> pinnedTargets = pins }
                            dispatch(P503UiEvent.InitialLoadResult(result.state, pinnedTargets))
                        }
                        else -> {
                            // D-203 startup trace: the initial read failed. D-207 kept the class
                            // name of the swallowed state-read failure; D-214 discriminates the
                            // three causes that D-207's "unknown" conflated, so the trace alone
                            // separates the non-blocking lease refusal from a swallowed read
                            // throw and from a non-Success read result — diagnostics only,
                            // dispatch and retry behavior unchanged.
                            val readFailure = (outcome as? LeaseOutcome.Completed)?.value?.second
                            val cause =
                                if (outcome !is LeaseOutcome.Completed) {
                                    // The lease itself refused (acquireLease's non-blocking
                                    // tryLock / RuntimeNotReady path). D-215 Fix B: the token is
                                    // the shared constant, never a duplicated literal.
                                    InitialReadLeaseRetry.LEASE_NOT_READY_CAUSE
                                } else if (readFailure != null) {
                                    "readThrew errorType=${readFailure.javaClass.simpleName}"
                                } else {
                                    val variant = result?.javaClass?.simpleName ?: "null"
                                    "readResult variant=$variant"
                                }
                            StartupTrace.emit("read.currentState end kind=failed cause=$cause")
                            dispatch(P503UiEvent.InitialLoadFailed)
                        }
                    }
                    if (currentStateLoadCoordinator.loadCompleted()) refresh()
                }
            }
        }
    }

    // P5-04.4: host-behavior decision skeleton (Created/NoChange/Recovered auto-refresh,
    // UnknownCommit auto-check, manual retry triggers) lives in a pure coordinator so it is
    // JVM-testable; the callbacks below are the actual IO/dispatch performed in this
    // composition root. The closures capture the stable state delegate and facade, so the
    // remembered coordinator stays current across recompositions.
    // P7-09 09.C (spec section 6.4): read through `rememberUpdatedState` so the remembered
    // coordinator below always calls the LATEST observer (the `P503EditScreen` precedent), instead of
    // pinning the first-recomposition instance inside a `remember {}` that outlives it.
    val currentWidgetInvalidation by rememberUpdatedState(widgetInvalidation)
    val coordinator =
        remember {
            P503HostCoordinator(
                onRefresh = ::refresh,
                onSubmit = { draft, requestId -> submit(draft, requestId) },
                onCheck = { draft, requestId -> checkCommitStatus(draft, requestId) },
                // P7-09 09.C (spec section 6.4; D-225 Q20): the widget-invalidation seam of the FIRST
                // refresh leg. Delegates to the latest observer so a fresh root wiring is honoured.
                widgetInvalidation = WidgetInvalidationObserver { currentWidgetInvalidation.invalidateAllWidgets() },
                // P7-03.C: the unified monthly cycle runs only on the frozen trigger set (a)-(e)
                // (spec 6.2, P703SPEC-04); 本月 resolves from the reporting clock (R-Q06-2).
                onMonthlyRequest = ::requestMonthlyPayload,
                // P7-07 07.D: the budget month request cycle (the budget trigger set; the
                // monthly trigger semantics are untouched).
                onBudgetMonthRequest = ::requestBudgetMonthPayload,
                currentMonth = {
                    try {
                        MonthlyBuckets.currentMonth(ledger.ledgerClock)
                    } catch (failure: Exception) {
                        null
                    }
                },
            )
        }
    // A-02 FIX-MONTH-1 (D-152): forward wiring — see [landingHopCoordinator] above refresh().
    landingHopCoordinator = coordinator

    // P7-03.C: month selection dispatches first, then re-requests the monthly payload
    // unconditionally (trigger (b), including the failure-recovery path: re-selecting the same
    // month must always re-read it).
    fun selectMonth(month: YearMonth) {
        dispatch(P503UiEvent.SelectMonth(month))
        coordinator.requestMonthlyNow(latestState.value)
        // P7-07 07.D: the budget region follows the shared month cursor (trigger (b) analogue).
        coordinator.requestBudgetMonthNow(latestState.value)
    }

    // G3: trigger (c) still says a month shift re-requests — but only a shift that actually moved
    // the shared cursor. An out-of-domain (absorbed) shift leaves the state instance untouched and
    // must not fire a wasted monthly read.
    fun analysisMonthShift(offset: Int) {
        val before = latestState.value
        dispatch(P503UiEvent.AnalysisMonthShift(offset))
        val reRequest = analysisMonthShiftReRequest(before, latestState.value)
        if (reRequest != null) {
            coordinator.requestMonthlyNow(reRequest)
            // P7-07 07.D: a moved cursor moves the budget region too (trigger (c) analogue).
            coordinator.requestBudgetMonthNow(reRequest)
        }
    }

    // Authoritative refresh after Created/NoChange/Recovered; never build the list from
    // the submission return value or accumulate balances in the UI. P7-03.C: the same
    // evaluation decides the monthly (a)/(d) re-requests.
    LaunchedEffect(state) {
        coordinator.decide(state)
        coordinator.decideMonthly(state)
        // P7-07 07.D: the budget (a)/(d) trigger runs beside the monthly one; the monthly
        // decision semantics above are unchanged.
        coordinator.decideBudgetMonth(state)
    }

    // ---------------------------------------------------------------- P7-04.C import host surface

    /** P7-04.C: dispatches the request intent, then launches the platform picker (不切态). */
    fun startImportFilePick(format: ImportFormatId) {
        dispatch(P503UiEvent.StartImportFilePick(format))
        val port = ledger.importFilePickPort ?: return
        val descriptor = ImportFormatCapabilities.byIdentifier(format)
        pendingImportFormat = format
        port.launch(ImportFilePickRequest(descriptor.identifier, descriptor.mimeFilters))
    }

    /**
     * P7-04.C (P704C-SPEC-01/QUAL-02): the pick→intake pipeline (spec 4.1.2): the L0 bounded
     * read, the intake and the list re-read run OFF the UI thread (`Dispatchers.Default` — the
     * only non-UI dispatcher commonMain can name; the desktop modal chooser itself already
     * blocked the UI thread inside `launch`, which is the registered frozen disclosure). The
     * result is then dispatched back ON the composition's main dispatcher (the nested
     * `scope.launch` hop): every existing async dispatch point is serial on the main dispatcher,
     * and `dispatch` is a non-atomic read-modify-write — a Default-thread dispatch interleaved
     * with main-thread user events could drop a transition. The session identity is minted per
     * pick (`ImportIntakeSessionIdentity.forFilePick`, R-Q09-1) and the pipeline result returns
     * as one `ImportFileIntakeResult` with the re-read review list.
     */
    fun runImportIntakePipeline(
        file: PickedImportFile,
        // P7-09 09.A (spec section 4; invariant 5): true when this run came from the SHARE closed
        // loop. A read-failure/over-limit outcome then ALSO surfaces the typed re-share prompt (the
        // source's short-lived grant is gone; the user must re-share, never a silent drop). The SAF
        // pick path leaves it `false` and keeps its existing typed banner only.
        sharePath: Boolean = false,
    ) {
        val format = pendingImportFormat
        val platform = ledger.importPlatformKind
        val session = ledger.importIntakeSessionFactory()
        val intakeWired = ledger.probe { it.importFileIntake } != null
        if (format == null || !intakeWired || platform == null || session == null) {
            // No pipeline can run (unwired surface or a lost format slot): release the coordinator's
            // single-flight slot so a later pick is not permanently blocked. On the share path this
            // is also the typed re-share case (nothing was read).
            if (sharePath) dispatch(P503UiEvent.ShareImportReShareLanded)
            coordinator.importIntakeCompleted()
            return
        }
        pendingImportFormat = null
        scope.launch(Dispatchers.Default) {
            // The L0 bounded read is platform I/O, not ledger work, so it runs outside the lease.
            val read = file.readBoundedBytes()
            // P7-06 06.1 (D-176; spec 4.3/4.4): the intake transaction, the statistics refresh and
            // the list re-read run under ONE operation lease; the captured generation gates the
            // landing hop below.
            val outcome =
                ledger.leased { facade, _ ->
                    val outcome =
                        when (read) {
                            is BoundedFileRead.Bytes ->
                                ImportIntakePipelineOutcome.Intaken(
                                    facade.importFileIntake!!.intake(ImportFileIntakeInput(format, platform, session, read.bytes)),
                                )
                            is BoundedFileRead.ExceedsLimit -> ImportIntakePipelineOutcome.ReadExceedsLimit(read.actualBytes)
                            is BoundedFileRead.ReadFailed -> ImportIntakePipelineOutcome.ReadFailed(read.reason)
                        }
                    // A-PERF (P7-04 read-governance batch, spec section 2.1): the intake-completion
                    // statistics refresh — SQLite's official semantics re-analyze after a ~10x row
                    // change, and one intake can multiply the duplicate-candidate table (the 20k-lib
                    // baseline ANR root cause). The hook is the root-injected controlled driver entry,
                    // still off the UI thread here; a refresh failure never degrades the intake result
                    // (statistics are a planner concern, the intake transaction is already committed).
                    runCatching { facade.importIntakeStatisticsRefresh() }
                    val rows = facade.queryImportReviewRows?.query(ledger.ledgerId) ?: ImportReviewRowsResult.Unavailable
                    outcome to rows
                }
            // Back on the composition's (main) dispatcher: the single-flight slot is released and
            // the event is dispatched serially with every other main-thread dispatch.
            scope.launch {
                coordinator.importIntakeCompleted()
                if (outcome is LeaseOutcome.Completed && !ledger.isCurrentGeneration(outcome.generation)) {
                    // Stale generation: discard the intake payload (P706-A07). The intake itself
                    // already committed on the old graph; nothing lands on the new one.
                    return@launch
                }
                if (outcome !is LeaseOutcome.Completed) {
                    // The lease could not be acquired: surface the typed read-failure outcome
                    // rather than a silently dropped pick (and on the share path, the re-share cue).
                    if (sharePath) dispatch(P503UiEvent.ShareImportReShareLanded)
                    dispatch(
                        P503UiEvent.ImportFileIntakeResult(
                            ImportIntakeSessionSummary(file.displayName, session.inputRef, ImportIntakePipelineOutcome.ReadFailed(ImportPickReadFailure.STREAM_READ_FAILED)),
                            ImportReviewRowsResult.Unavailable,
                        ),
                    )
                    return@launch
                }
                // P7-09 09.A: on the share path a read failure (expired/revoked grant, mid-read
                // failure, over-16 MiB) additionally lands the typed re-share prompt — zero formal
                // effect, never a silent drop.
                if (sharePath && shareImportReShareRequired(outcome.value.first)) {
                    dispatch(P503UiEvent.ShareImportReShareLanded)
                }
                dispatch(
                    P503UiEvent.ImportFileIntakeResult(
                        ImportIntakeSessionSummary(
                            displayName = file.displayName,
                            inputRef = session.inputRef,
                            outcome = outcome.value.first,
                        ),
                        outcome.value.second,
                    ),
                )
            }
        }
    }

    /**
     * P7-04.C: one platform pick result (table 6.2a — the channel events are reducer-absorbed; this
     * handler owns the host action). A picked file starts the single-flight bounded-read + intake
     * pipeline; a cancellation or a typed platform failure starts nothing.
     *
     * P7-09 09.0 / T4 (spec sections 1.7/2.2; D-225): a refused second pick is NO LONGER a silent
     * `-> Unit` drop — it lands the visible Busy result on the overview (zero formal effect).
     */
    fun handleImportPickResult(
        result: ImportFilePickResult,
        // P7-09 09.A (spec section 4; invariant 5): true when the pick came from the SHARE closed
        // loop, so a read failure/over-limit additionally surfaces the typed re-share prompt. The
        // SAF pick channel passes `false` (its default) and keeps its existing typed banner only.
        sharePath: Boolean = false,
    ) {
        when (result) {
            is ImportFilePickResult.Picked -> dispatch(P503UiEvent.ImportFilePicked(result.file))
            ImportFilePickResult.Cancelled -> dispatch(P503UiEvent.ImportFilePickCancelled)
            is ImportFilePickResult.Failed -> dispatch(P503UiEvent.ImportFilePickFailed(result.reason))
        }
        when (val decision = coordinator.handleImportPickResult(result)) {
            is ImportPickIntakeDecision.StartIntake -> runImportIntakePipeline(decision.file, sharePath = sharePath)
            // P7-09 09.0 / T4: the refused second pick lands its visible Busy event (the seam
            // returns exactly this event for AlreadyInFlight), never a silent `-> Unit` drop.
            // P7-09 09.A fix round (B5; spec section 2.2 lines 107-108): on the share path the
            // single-flight refusal is a VISIBLE Busy (the honest "已有文件正在导入" cause), NOT the
            // re-share prompt — the cause is a concurrent intake, not an unreadable source. The
            // retained source and the format slot are KEPT (admission is checked before the take in
            // [selectShareImportFormat], so this path is practically unreachable — the residual race
            // only — and a retry must still be able to read the same stream). NEVER clear
            // `pendingImportFormat` here.
            is ImportPickIntakeDecision.AlreadyInFlight -> importPickDecisionEvent(decision)?.let(::dispatch)
            ImportPickIntakeDecision.NoPipeline -> Unit
        }
    }

    /**
     * P7-09 09.0 (spec sections 2/3; D-225 Q19; P2-1 fix): one inbound neutral request from the
     * platform adapter (a share Intent, a launcher shortcut). The routing DECISION lives in the
     * JVM-assertable [externalNavigationDecisionEvent] seam; this method only dispatches the two
     * events the reducer consumes.
     *
     * P2-1 fix: the runtime facts are OBSERVED, not hard-coded. [ExternalNavigationRuntimeFacts.from]
     * maps the lease face's active generation (`LedgerLeaseScope.activeGenerationForLanding()` returns
     * null whenever the owner is not Ready) and the host's own in-progress quiesce/换库 observation (a
     * restore confirm is running). This makes the not-Ready and quiesce rows of the frozen table
     * production-reachable instead of dead code. The observed facts are injectable so a focused test
     * can pin the mapping without a Compose harness.
     */
    fun onExternalNavigationRequested(
        request: ExternalNavigationRequest,
        facts: ExternalNavigationRuntimeFacts =
            ExternalNavigationRuntimeFacts.from(
                activeGeneration = ledger.activeGenerationForLanding(),
                switchingRuntime = (latestState.value as? P503AppState.BackupRestore)?.runningConfirm == true,
            ),
    ) {
        dispatch(P503UiEvent.ExternalNavigationRequested(request))
        val decision = externalNavigationDecision(latestState.value, request, facts, existingPending = visibleHeldNavigation())
        dispatch(P503UiEvent.ExternalNavigationDecisionLanded(decision))
        // P7-09 09.A fix round (B2; spec section 2.2 line 106 禁止静默丢弃): a Held/Deferred request
        // landing on a surface the reducer cannot carry the slot on (a read-only detail, the recycle
        // bin, a restore-confirm quiesce, ...) would otherwise be absorbed and VANISH. The host holds
        // it in its fallback so it stays VISIBLE (never silently discarded). The pure seam returns
        // null whenever the landed state itself carries the slot (the reducer owns it) or the request
        // routed/absorbed with nothing to hold; it flushes back into the reducer once a routable
        // surface becomes live (see `dispatch`).
        hostFallbackHeldNavigation(latestState.value, decision)?.let { held -> hostHeldNavigation = held }
    }

    /**
     * P7-09 09.A share-import CLOSED LOOP (spec sections 2.3/4; D-225; batch invariant 3): the
     * format was chosen for the HELD share. Unlike [startImportFilePick] this does NOT launch a
     * fresh SAF picker — it reads the RETAINED shared stream through the existing pipeline. The
     * retained source is taken by the held request's `deliveryId`; a non-null source seeds the
     * EXISTING `pendingImportFormat` slot and runs [runImportIntakePipeline], a null source is the
     * typed re-share case. Either way the pending slot is cleared and no format-less request is ever
     * delivered into the old pick channel (spec section 2.3 line 126).
     */
    fun selectShareImportFormat(
        format: ExternalNavigationRequest.OpenImport,
        formatId: ImportFormatId,
    ) {
        // P7-09 09.A fix round (B5; spec section 2.2 lines 107-108): check the intake single flight
        // BEFORE consuming the retained source. A refused relay must NOT `take` the handle: the
        // source and the format slot stay valid and the attempt surfaces the visible Busy, so a
        // later retry can still read the same stream (the pre-fix order consumed first and left the
        // share unrecoverable without re-sharing).
        if (!shareImportRelayAllowed(intakeBusy = coordinator.importIntakeBusy())) {
            dispatch(P503UiEvent.ImportIntakeBusyLanded)
            return
        }
        val port = ledger.shareImportSource
        val decision = shareImportTakeDecision(port?.take(format.deliveryId))
        // The re-share case releases any retained entry (idempotent no-op when already gone) and
        // lands the typed prompt; the consumed case clears the slot in the reducer and, for a real
        // source, runs the pipeline. Zero formal effect on the re-share branch.
        if (decision is ShareImportTakeDecision.ReShareRequired) {
            port?.release(format.deliveryId)
        }
        dispatch(shareImportTakeEvent(decision))
        when (decision) {
            is ShareImportTakeDecision.StartIntake -> {
                // Seed the EXISTING format slot, then run the EXISTING pipeline through the SAME
                // host admission the SAF pick uses (single reader, single parser, single intake, the
                // existing `importIntakeInFlight` single-flight — invariant 3). A concurrent second
                // intake is refused with the visible Busy, never a second pipeline.
                pendingImportFormat = formatId
                handleImportPickResult(ImportFilePickResult.Picked(decision.file), sharePath = true)
            }
            ShareImportTakeDecision.ReShareRequired -> Unit
        }
    }

    /**
     * P7-04.C: re-reads the ledger-scoped review list and returns it as
     * [P503UiEvent.ImportReviewResult] (read off the UI thread, dispatched back on the main
     * dispatcher — P704C-SPEC-01/QUAL-02).
     */
    fun requestImportReview() {
        // Base behavior (P3-4): an unwired surface returns early and dispatches nothing; the
        // typed Unavailable path is for a runtime refusal, not for a surface the composition
        // never wired (a pure lease-free null check).
        if (!ledger.surfaces.importReviewRows) return
        dispatch(P503UiEvent.RefreshImportReview)
        scope.launch(Dispatchers.Default) {
            // P7-06 06.1 (D-176; spec 4.3/4.4): the read runs under a lease; the landing hop
            // discards a result captured under a superseded generation.
            val outcome = ledger.leased { facade, _ -> facade.queryImportReviewRows?.query(ledger.ledgerId) }
            scope.launch {
                if (outcome is LeaseOutcome.Completed && !ledger.isCurrentGeneration(outcome.generation)) return@launch
                val result = (outcome as? LeaseOutcome.Completed)?.value ?: ImportReviewRowsResult.Unavailable
                dispatch(P503UiEvent.ImportReviewResult(result))
            }
        }
    }

    /** P7-04.C: reads the candidate detail + duplicate comparison (off the UI thread), then opens the detail state (main dispatcher). */
    fun selectImportCandidate(candidateId: ImportCandidateId) {
        scope.launch(Dispatchers.Default) {
            // P7-06 06.1 (spec 4.3/4.4): one lease over the detail + duplicate reads.
            val outcome =
                ledger.leased { facade, _ ->
                    val detailQuery = facade.queryImportCandidateDetail ?: return@leased null
                    val duplicatesQuery = facade.queryImportDuplicateReviews ?: return@leased null
                    detailQuery.query(ledger.ledgerId, candidateId) to duplicatesQuery.query(ledger.ledgerId, candidateId)
                }
            scope.launch {
                if (outcome !is LeaseOutcome.Completed) return@launch
                if (!ledger.isCurrentGeneration(outcome.generation)) return@launch
                val payload = outcome.value ?: return@launch
                dispatch(P503UiEvent.SelectImportCandidate(candidateId, payload.first, payload.second))
            }
        }
    }

    /**
     * P7-04.C: submits one duplicate review to the core use case (spec sections 3.2.1/6.2). The
     * request shape follows the frozen core contract: candidate id + expected fingerprint from
     * the detail's first unreviewed comparison row, the frozen three-value decision set, the fixed
     * reason token/reviewer reference, `reviewedAt`/`generatedAt` sampled once from the injected
     * LedgerClock (Q09.4 — a review is a processing/audit time, never a source time), and a fresh
     * requestId/reviewId/historyId UUIDv7 triple per intent (claim-gated; replay/conflict paths
     * never consume ids). The result returns with the refreshed list/detail/duplicates.
     * P704D-SPEC-02 (final delta): a guarded run body — an execution throw becomes the typed
     * UI-owned failure event (review/refresh null + `IMPORT_REVIEW_SUBMIT_UNAVAILABLE`), the
     * store's claim transaction having rolled back (zero writes, retryable); the single-flight
     * slot always releases.
     */
    fun submitImportDuplicateReview(decision: ImportDuplicateReviewUiDecision) {
        val detailState = latestState.value as? P503AppState.ImportCandidateDetail ?: return
        val target = importDuplicateReviewTarget(detailState.duplicates) ?: return
        if (!ledger.surfaces.importDuplicateReview) return
        val ids = ledger.importDuplicateReviewIds() ?: return
        val now = ledger.ledgerClock.now().toString()
        val request =
            ImportDuplicateReviewRequest(
                identity = ImportRequestIdentity(ledger.ledgerId, ids.requestId),
                candidateId = target.duplicateCandidateId,
                expectedComparisonFingerprint = target.comparisonFingerprint,
                decision = decision.coreStatus,
                reasonToken = IMPORT_DUPLICATE_REVIEW_REASON_TOKEN,
                reviewedAt = now,
                reviewerReference = IMPORT_DUPLICATE_REVIEWER_REFERENCE,
                generatedAt = now,
                reviewId = ids.reviewId,
                historyId = ids.historyId,
            )
        // Single flight (期间禁重复提交): the reducer's reviewPending marker absorbs duplicate
        // events; the coordinator guard blocks a duplicate host submission.
        coordinator.submitImportDuplicateReviewOnce {
            scope.launch(Dispatchers.Default) {
                // P704D-SPEC-02 (final delta, the same-pattern prevention as the batch runs): the
                // guarded body keeps the review's single-flight slot always releasable — both
                // paths release it in their main-dispatcher hop. An execution throw becomes the
                // typed UI-owned failure event (the core never returned a verdict; the store's
                // claim transaction rolled back — zero writes), never a stranded reviewPending
                // marker or a leaked slot. The failure path passes no refresh payload (保留旧载荷, F1): the failed submission changed nothing, so a re-read adds nothing —
                // the banner explains and a retried success re-reads on its own (registered
                // choice: 保守不重读).
                // P7-06 06.1 (spec 4.3/4.4): the review write and its re-reads share one lease;
                // the captured generation gates the landing. An execution throw keeps the frozen
                // typed UI-owned failure (review/refresh null + IMPORT_REVIEW_SUBMIT_UNAVAILABLE).
                val outcome =
                    runCatching {
                        ledger.leased { facade, _ ->
                            val review = facade.importDuplicateReview!!.execute(request)
                            val rows = facade.queryImportReviewRows?.query(ledger.ledgerId) ?: ImportReviewRowsResult.Unavailable
                            val detail = facade.queryImportCandidateDetail?.query(ledger.ledgerId, detailState.candidateId)
                            val duplicates = facade.queryImportDuplicateReviews?.query(ledger.ledgerId, detailState.candidateId)
                            ImportDuplicateReviewRefresh(rows, detail, duplicates) to review
                        }
                    }.getOrElse { LeaseOutcome.NotReady }
                // Back on the main dispatcher: release the slot and dispatch serially (P704C-SPEC-01/QUAL-02).
                scope.launch {
                    coordinator.importDuplicateReviewCompleted()
                    when (outcome) {
                        is LeaseOutcome.Completed ->
                            if (ledger.isCurrentGeneration(outcome.generation)) {
                                dispatch(P503UiEvent.ImportDuplicateReviewResult(outcome.value.second, outcome.value.first))
                            }
                        LeaseOutcome.NotReady ->
                            dispatch(
                                P503UiEvent.ImportDuplicateReviewResult(
                                    review = null,
                                    refresh = null,
                                    uiFailureCode = IMPORT_REVIEW_SUBMIT_UNAVAILABLE,
                                ),
                            )
                    }
                }
            }
        }
    }

    /**
     * P7-04.C (P704SPEC-12): opens the 整组确认页 by enumerating the current pick session's
     * suspected-duplicate group. The enumeration decision itself is the pure, JVM-tested
     * [enumerateImportDuplicateGroupItems] (P704C-SPEC-05): per candidate every
     * EXACT_BUSINESS_TUPLE/DEFERRED row (subject handle = the session handle) becomes one item
     * carrying its privacy-safe comparison snapshot, and any typed read failure aborts the
     * enumeration with the typed list-failure banner (never a silently partial group). The
     * enumeration is single-flight; the outcome is dispatched back on the main dispatcher.
     *
     * P7-05 enumeration performance batch: when the composition provides the session-level
     * batch query ([P503LedgerFacade.queryImportDuplicateReviewsForSession]) the whole group
     * reads in ONE query (the new v30 covering index), the result is folded per candidate
     * client-side, and the folded map feeds the unchanged pure function — the N+1
     * per-candidate read loop (plus its full-list existence probe) is gone while the pure
     * function's signature and its JVM pins stay untouched. Legacy compositions without the
     * batch query keep the per-candidate path. The in-flight window renders the explicit
     * 正在整理重复组…… progress line (Started/Completed events around the single-flight run).
     */
    fun startImportDuplicateGroupDisposition() {
        val overviewState = latestState.value as? P503AppState.OverviewEmpty ?: return
        val view = overviewState.importReview ?: return
        val sessionInputRef = view.lastIntakeSession?.inputRef ?: return
        // P704C-SPEC-01/QUAL-02: the enumeration is single-flight — a double tap must not
        // interleave two enumerations (two Start events would race the page state).
        coordinator.startImportDuplicateGroupDispositionOnce {
            // P7-05: the progress marker lands on the main dispatcher before the enumeration
            // runs (the dispatch discipline of every other host event).
            dispatch(P503UiEvent.ImportGroupEnumerationStarted)
            scope.launch(Dispatchers.Default) {
                // P704D-SPEC-03 (C-batch leak-pattern prevention): the guarded body keeps the
                // enumeration's single-flight slot always releasable — the finally hop releases
                // it and dispatches the typed outcome on the main dispatcher regardless of how
                // the run ended. An unexpected throw in the enumeration phase maps to the same
                // typed list-failure path as a duplicate-review read failure (never a silently
                // partial group, never a stranded slot).
                var enumeration: ImportDuplicateGroupEnumeration = ImportDuplicateGroupEnumeration.ReadFailed
                // P7-06 06.1 (D-176; spec 4.3/4.4): the enumeration reads run under one lease; the
                // captured generation gates the landing below.
                var enumerationGeneration: Generation? = null
                try {
                    // P704C-SPEC-05: the pure, JVM-tested enumeration owns the abort decision —
                    // any typed duplicate-review read failure aborts wholesale (never a silent
                    // partial group) and the typed list-failure banner is surfaced instead.
                    // P704D-SPEC-03: the inner guard keeps an unexpected throw out of the outer
                    // coroutine — it maps to the same typed list-failure path as a read failure
                    // (never a silently partial group, never a stranded single-flight slot).
                    enumeration =
                        try {
                            when (
                                val leased =
                                    ledger.leased { facade, generation ->
                                        enumerationGeneration = generation
                                        val sessionQuery = facade.queryImportDuplicateReviewsForSession
                                        if (sessionQuery != null) {
                                            // P7-05 batch path: one session-level query replaces the
                                            // per-candidate loop. Each row carries its subject candidate, so
                                            // the batch folds per subject client-side and feeds the
                                            // unchanged pure function through its load callback (Reviews for
                                            // a folded member, an empty Reviews for a session candidate the
                                            // batch did not return — the batch join only yields rows whose
                                            // subject has a duplicate candidate).
                                            val sessionResult = sessionQuery.query(ledger.ledgerId, sessionInputRef)
                                            val sessionRows =
                                                (sessionResult as? ImportDuplicateReviewsForSessionResult.Reviews)?.reviews
                                            val reviewsByCandidate =
                                                sessionRows.orEmpty().groupBy({ it.subjectCandidateId }) { it.review }
                                            enumerateImportDuplicateGroupItems(sessionInputRef, view.rows) { candidateId ->
                                                when {
                                                    // A session-level read failure is the same wholesale
                                                    // abort as a per-candidate one (the pure function turns
                                                    // it into ReadFailed; never a partial group).
                                                    sessionResult is ImportDuplicateReviewsForSessionResult.Unavailable ->
                                                        ImportDuplicateReviewsResult.Unavailable
                                                    else ->
                                                        ImportDuplicateReviewsResult.Reviews(
                                                            reviewsByCandidate[candidateId] ?: emptyList(),
                                                        )
                                                }
                                            }
                                        } else {
                                            val reviewQuery = facade.queryImportDuplicateReviews
                                            if (reviewQuery == null) {
                                                ImportDuplicateGroupEnumeration.ReadFailed
                                            } else {
                                                enumerateImportDuplicateGroupItems(sessionInputRef, view.rows) { candidateId ->
                                                    reviewQuery.query(ledger.ledgerId, candidateId)
                                                }
                                            }
                                        }
                                    }
                            ) {
                                is LeaseOutcome.Completed -> leased.value
                                LeaseOutcome.NotReady -> ImportDuplicateGroupEnumeration.ReadFailed
                            }
                        } catch (failure: Exception) {
                            ImportDuplicateGroupEnumeration.ReadFailed
                        }
                } finally {
                    // Back on the main dispatcher: release the slot and dispatch serially.
                    scope.launch {
                        coordinator.importGroupEnumerationCompleted()
                        dispatch(P503UiEvent.ImportGroupEnumerationCompleted)
                        if (enumerationGeneration != null && !ledger.isCurrentGeneration(enumerationGeneration)) {
                            // Stale generation: discard the enumeration payload entirely.
                            return@launch
                        }
                        when (enumeration) {
                            ImportDuplicateGroupEnumeration.ReadFailed ->
                                dispatch(P503UiEvent.ImportReviewResult(ImportReviewRowsResult.Unavailable))
                            is ImportDuplicateGroupEnumeration.Ready ->
                                // An empty Ready is the defensive no-op path (the affordance only
                                // appears for a non-empty row-level group).
                                if (enumeration.items.isNotEmpty()) {
                                    dispatch(P503UiEvent.StartImportDuplicateGroupDisposition(sessionInputRef, enumeration.items))
                                }
                        }
                    }
                }
            }
        }
    }

    /**
     * P7-04.C (P704SPEC-12, spec section 3.3.1): the batch disposition loop — one independent core
     * review request per item (fresh ids per intent, claim-gated, replayable), strictly sequential,
     * one item's typed rejection never stops the others (可见部分成功). The loop is single-flight
     * (P704C-SPEC-01/QUAL-02: a double tap must not interleave two loops). Re-run idempotency
     * (P704C-QUAL-01/SPEC-03): a re-run never re-submits an item whose page verdict is already
     * [ImportDuplicateGroupItemResult.Reviewed] — re-submitting would draw the core's typed
     * candidateNotPending rejection and could relabel a succeeded item 失败； already-Rejected
     * items ARE re-attempted (their outcome updates with the fresh verdict). The Start affordance
     * is where the group itself is recomputed from the fresh rows (already-disposed candidates
     * left DEFERRED and no longer appear).
     */
    fun confirmImportDuplicateGroupDisposition() {
        val page =
            (latestState.value as? P503AppState.OverviewEmpty)
                ?.importReview
                ?.groupDisposition ?: return
        if (!ledger.surfaces.importDuplicateReview) return
        coordinator.confirmImportDuplicateGroupDispositionOnce {
            scope.launch(Dispatchers.Default) {
                // P704D-SPEC-03 (C-batch leak-pattern prevention): the guarded body keeps the
                // disposition loop's single-flight slot always releasable — the finally hop
                // dispatches the outcomes collected so far and releases the slot regardless of
                // how the run ended. An unexpected mid-loop throw therefore still lands the
                // already-disposed items' visible partial success (可见部分成功， the loop's own
                // semantics) instead of leaking the slot and dropping every outcome.
                val outcomes = mutableListOf<ImportDuplicateGroupItemOutcome>()
                // P7-06 06.1 (D-176; spec 4.3/4.4): the whole disposition loop runs under ONE
                // operation lease; the captured generation gates the landing below.
                var loopGeneration: Generation? = null
                try {
                    val run =
                        ledger.leased { facade, generation ->
                            loopGeneration = generation
                            page.items
                                // 已成功项幂等不重复处置：never re-submit a Reviewed item on a re-run.
                                .filter { it.outcome !is ImportDuplicateGroupItemResult.Reviewed }
                                // P704D-SPEC-03: forEach + immediate append — a mid-loop throw keeps the
                                // already-disposed items' outcomes in `outcomes` (the finally then lands
                                // the visible partial success); a map-then-addAll would drop them.
                                .forEach { itemState ->
                                    val item = itemState.item
                                    val ids = ledger.importDuplicateReviewIds()
                                    outcomes +=
                                        if (ids == null) {
                                            ImportDuplicateGroupItemOutcome(
                                                item.duplicateCandidateId,
                                                // P704C-SPEC-07: a UI-owned guard code never borrows the core
                                                // SPINE_ diagnostic namespace (the spine family must stay 原样).
                                                ImportDuplicateGroupItemResult.Rejected("IMPORT_REVIEW_IDS_UNAVAILABLE"),
                                            )
                                        } else {
                                            val now = ledger.ledgerClock.now().toString()
                                            val request =
                                                ImportDuplicateReviewRequest(
                                                    identity = ImportRequestIdentity(ledger.ledgerId, ids.requestId),
                                                    candidateId = item.duplicateCandidateId,
                                                    expectedComparisonFingerprint = item.expectedComparisonFingerprint,
                                                    decision = ImportDuplicateStatus.CONFIRMED_DUPLICATE,
                                                    reasonToken = IMPORT_DUPLICATE_REVIEW_REASON_TOKEN,
                                                    reviewedAt = now,
                                                    reviewerReference = IMPORT_DUPLICATE_REVIEWER_REFERENCE,
                                                    generatedAt = now,
                                                    reviewId = ids.reviewId,
                                                    historyId = ids.historyId,
                                                )
                                            when (val result = facade.importDuplicateReview!!.execute(request)) {
                                                is com.unifiedledger.application.ImportDuplicateReviewResult.Accepted ->
                                                    ImportDuplicateGroupItemOutcome(
                                                        item.duplicateCandidateId,
                                                        ImportDuplicateGroupItemResult.Reviewed(result.receipt.outcome),
                                                    )
                                                is com.unifiedledger.application.ImportDuplicateReviewResult.NoChange ->
                                                    ImportDuplicateGroupItemOutcome(
                                                        item.duplicateCandidateId,
                                                        ImportDuplicateGroupItemResult.Reviewed(result.receipt.outcome),
                                                    )
                                                is com.unifiedledger.application.ImportDuplicateReviewResult.Rejected ->
                                                    ImportDuplicateGroupItemOutcome(
                                                        item.duplicateCandidateId,
                                                        ImportDuplicateGroupItemResult.Rejected(result.diagnostic.code),
                                                    )
                                            }
                                        }
                                }
                        }
                    if (run is LeaseOutcome.NotReady) {
                        // The lease could not be acquired: leave the page's items untouched.
                        outcomes.clear()
                    }
                } finally {
                    // A-PERF: the landing rows re-read runs OFF the UI thread in this outer
                    // `Dispatchers.Default` block (the same off-thread discipline as the
                    // enumeration/intake landings) — a paged import-review read (up to the
                    // 10,000-candidate batch) on the main dispatcher is exactly the ANR-class
                    // pattern the P7-04 read-governance batch removed. Only the single-flight
                    // release and the serial dispatch hop to the main dispatcher.
                    // P704D-SPEC-03 (leak prevention): the read is failure-safe — a throw degrades
                    // to the same typed [ImportReviewRowsResult.Unavailable] the null path already
                    // produces, so the `finally` can never abort BEFORE the slot release below
                    // (a throwing read would otherwise strand the single-flight slot permanently).
                    val rows =
                        runCatching { ledger.probe { facade -> facade.queryImportReviewRows?.query(ledger.ledgerId) } }
                            .getOrNull()
                            ?: ImportReviewRowsResult.Unavailable
                    // Back on the main dispatcher: release the slot and dispatch serially.
                    scope.launch {
                        coordinator.importGroupDispositionCompleted()
                        if (loopGeneration != null && !ledger.isCurrentGeneration(loopGeneration)) {
                            // Stale generation: discard the disposition payload entirely.
                            return@launch
                        }
                        dispatch(P503UiEvent.ImportDuplicateGroupDispositionResult(outcomes.toList(), rows))
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- P7-04.D batch confirmation host surface

    /** P7-04.D: re-reads the review list off the UI thread and dispatches the typed result. */
    fun requestImportReviewRowsRead() {
        // Base behavior (P3-4): an unwired surface returns early; the typed Unavailable path is
        // for a runtime refusal, not for a surface the composition never wired.
        if (!ledger.surfaces.importReviewRows) return
        scope.launch(Dispatchers.Default) {
            // P7-06 06.1 (spec 4.3/4.4): read under a lease; the landing hop discards a stale result.
            val outcome = ledger.leased { facade, _ -> facade.queryImportReviewRows?.query(ledger.ledgerId) }
            scope.launch {
                if (outcome is LeaseOutcome.Completed && !ledger.isCurrentGeneration(outcome.generation)) return@launch
                dispatch(P503UiEvent.ImportReviewResult((outcome as? LeaseOutcome.Completed)?.value ?: ImportReviewRowsResult.Unavailable))
            }
        }
    }

    /** P7-04.D: opens the 授权快照确认页 (the reducer effect owns the transition; no host IO). */
    fun requestImportBatchConfirm() {
        dispatch(P503UiEvent.RequestImportBatchConfirm)
    }

    /**
     * P7-04.D: the sequential per-item dispatch loop (P704C-SPEC-01/QUAL-02 threading): the
     * latest-rows re-read and every spine confirm run OFF the UI thread (`Dispatchers.Default`),
     * each per-item result hops back ON the composition's main dispatcher (the nested
     * `scope.launch` hop — `dispatch` is a non-atomic read-modify-write, so every dispatch stays
     * serial on the main dispatcher), and the loop's single-flight slot releases in the final
     * hop AFTER the last per-item result hop (a paused or abandoned run always leaves the slot
     * free before the user can act again). A Completed run re-reads the list so confirmed items
     * read `confirmed` immediately (清单读即权威， D04). P704D-QUAL-01/SPEC-02: the guarded
     * pre-phase and the try/finally body guarantee the slot release and turn a pre-phase
     * failure into typed per-item results (never a stranded run).
     */
    fun runImportBatchDispatch() {
        val submitting = latestState.value as? P503AppState.ImportBatchSubmitting ?: return
        // Captured on the main thread before the background run: the undispatched items and the
        // session decision drafts (ImportBatchSubmitting absorbs every draft-changing event, so
        // these are the authorization-time values).
        val undispatched = submitting.items.filter { it.outcome == null }.map { it.item }
        val drafts = submitting.overview.importReview?.decisionDrafts ?: emptyMap()
        val confirmedAt = submitting.confirmedAt
        coordinator.startImportBatchDispatchOnce {
            scope.launch(Dispatchers.Default) {
                // P704D-QUAL-01/SPEC-02: the whole run body is guarded so the single-flight slot
                // ALWAYS releases — the finally hop is queued after the last per-item result hop
                // (main-dispatcher FIFO), whether the run completed, paused, or failed in the
                // pre-phase. A pre-phase failure becomes typed visible per-item results instead
                // of a stranded run (基础设施失败成为类型化/可见结果而非搁浅流程).
                var completed = false
                // P7-06 06.1 (D-176; spec 4.3/4.4): the whole run (pre-phase read + use-case
                // factory + the sequential per-item loop) runs under ONE operation lease; the
                // captured generation gates every per-item landing and the final hop below.
                var runGeneration: Generation? = null
                try {
                    ledger.leased { facade, generation ->
                        runGeneration = generation
                        val prePhase =
                            importBatchDispatchPrePhase(
                                loadRows = { facade.queryImportReviewRows?.query(ledger.ledgerId) ?: ImportReviewRowsResult.Unavailable },
                                loadUseCases = { facade.importConfirmUseCases?.invoke() },
                            )
                        when (prePhase) {
                            is ImportBatchDispatchPrePhase.Failed -> {
                                // The run-level typed skips land per still-undispatched item; every
                                // item is then terminal, so the reducer auto-leaves to the overview
                                // with the typed summary — no dead end, the candidates stay pending.
                                importBatchRunLevelFailureResults(undispatched, prePhase.code).forEach { result ->
                                    scope.launch {
                                        if (ledger.isCurrentGeneration(generation)) {
                                            dispatch(P503UiEvent.ImportItemResult(result.item, result.outcome))
                                        }
                                    }
                                }
                                completed = true
                            }
                            is ImportBatchDispatchPrePhase.Ready -> {
                                val loop =
                                    ImportBatchDispatchLoop(
                                        items = undispatched,
                                        rows = prePhase.rows,
                                        drafts = drafts,
                                        confirmedAt = confirmedAt,
                                        useCases = prePhase.useCases,
                                        ledgerId = ledger.ledgerId,
                                        parseAmount = ledger.parseAmount,
                                        defaultCurrency = ledger.currency,
                                    )
                                val run =
                                    loop.run(
                                        dispatch = { result ->
                                            scope.launch {
                                                if (ledger.isCurrentGeneration(generation)) {
                                                    dispatch(P503UiEvent.ImportItemResult(result.item, result.outcome))
                                                }
                                            }
                                        },
                                        execute = { useCase, request -> useCase.execute(request) },
                                    )
                                completed = run is ImportBatchDispatchRun.Completed
                            }
                        }
                    }
                } finally {
                    // Back on the main dispatcher: release the slot (queued after every per-item
                    // result hop) and, on a completed run, re-read the list so the rows reflect
                    // the confirmed items. Trigger (f) (P7-03 FIX-STALE-1, D-153) arms here ONLY
                    // through the shared all-terminal gate: a run completing while an Unknown
                    // item still blocks the auto-leave (state still ImportBatchSubmitting) does
                    // NOT arm — the later 核对 resolution that completes the run arms instead
                    // (exactly one arm per batch run).
                    scope.launch {
                        coordinator.importBatchDispatchCompleted()
                        if (runGeneration != null && !ledger.isCurrentGeneration(runGeneration)) {
                            // Stale generation: discard the whole run's landing (the per-item
                            // results were already gated above; nothing lands on the new graph).
                            return@launch
                        }
                        if (completed) {
                            // P7-08 08.D (A07): the arm landing runs through the JVM-assertable seam.
                            armAfterImportBatchConfirmation(latestState.value, coordinator) { armTagMerchantFilterReRequestIfLoaded() }
                            requestImportReviewRowsRead()
                        }
                    }
                }
            }
        }
    }

    /**
     * P7-04.D: the authorization action (Q09.3/Q09.4). The wiring guard runs BEFORE the dispatch
     * (the P704C-QUAL-04 call-site precedent: an unwired surface must never strand the page in a
     * state it cannot leave) — the LedgerClock is sampled exactly ONCE for the whole
     * authorization and one fresh requestId is minted per selected candidate (P7-02 每个新意图
     * 分配新 requestId； a resumed run and the unknown-item replay reuse these ids verbatim — 不换
     * ID). The reducer then enters the dispatch state and the sequential loop starts.
     */
    fun authorizeImportBatch() {
        val confirmState = latestState.value as? P503AppState.ImportBatchConfirm ?: return
        val selected = confirmState.overview.importReview?.selectedCandidateIds ?: emptySet()
        if (selected.isEmpty()) return
        val requestIdSource = ledger.importConfirmRequestIdSource ?: return
        // P7-06 06.1 fix: this wiring guard is NOT a re-check of the requestId source above — it
        // is a distinct check of the use-case FACTORY's wiring ([LedgerSurfaces.importBatchConfirm]
        // is a lease-free null check of `facade.importConfirmUseCases`, never its invocation,
        // which would read the catalog outside a lease). An unwired factory must never strand the
        // page in a dispatch state it cannot leave.
        if (!ledger.surfaces.importBatchConfirm) return
        // Q09.4: 授权时刻 LedgerClock 取样一次，经 explicitConfirmedAt 全项复用（mixed 必填）。
        val confirmedAt = ledger.ledgerClock.now().toString()
        val requestIds =
            selected.associateWith { candidateId ->
                com.unifiedledger.application.ImportRequestId(requestIdSource())
            }
        dispatch(P503UiEvent.AuthorizeImportBatch(confirmedAt, requestIds))
        runImportBatchDispatch()
    }

    /**
     * P7-04.D: the paused batch's explicit continue. The reducer decides stay-or-leave; with the
     * state still dispatching, the continuation loop starts (同授权快照内， 复用同次 LedgerClock 取样
     * 与既有 requestId — the state's snapshot is the single source); with the state having left to
     * the overview, the list re-read shows the confirmed items.
     */
    fun resumeImportBatchDispatch() {
        dispatch(P503UiEvent.ResumeImportBatchDispatch)
        if (latestState.value is P503AppState.ImportBatchSubmitting) {
            runImportBatchDispatch()
        } else {
            requestImportReviewRowsRead()
        }
    }

    /**
     * P7-04.D: the explicit abandon (义务③). The reducer dissolves the authorization snapshot in
     * one transition (the completed items keep their summary, the undispatched items are
     * ordinary pending rows again); the host then re-reads the list so the persisted
     * `pending_confirmation` state of the never-dispatched items reads as the ordinary 待确认
     * list (未派发项持久状态保持 pending_confirmation).
     */
    fun abandonImportBatch() {
        dispatch(P503UiEvent.AbandonImportBatch)
        requestImportReviewRowsRead()
    }

    /**
     * P7-04.D: one Unknown item's 核对 (Q10.2) — the equivalent replay with the SAME requestId and
     * the SAME authorization clock sample over the latest rows (sources are immutable, so the
     * rebuilt request is equivalent to the dispatched one). The spine's claim-gated
     * `resolveConfirm` returns the original receipt (判成功) or types the equivalence break (判冲
     * 突)； an unreadable replay stays Unknown (仍未知 — the entry stays, 不自动重试). A Confirmed
     * verdict also re-reads the list so the item reads `confirmed`. P704D-QUAL-01/SPEC-02: the
     * guarded body and the finally hop keep the check's single-flight slot always releasable; a
     * pre-phase failure is the StillUnknown verdict (retryable), never a stranded run. A verdict
     * that completes the run's terminal set also arms monthly trigger (f) (P7-03 FIX-STALE-1,
     * D-153) — see the second call site in the finally hop below.
     */
    fun checkImportUnknownItem(candidateId: ImportCandidateId) {
        dispatch(P503UiEvent.ImportUnknownItemCheck(candidateId))
        val target = resolveImportUnknownCheckTarget(latestState.value, candidateId) ?: return
        val (item, confirmedAt) = target
        // Captured on the main thread before the background run (the batch states absorb every
        // draft-changing event, so these are the authorization-time values).
        val drafts =
            when (val current = latestState.value) {
                is P503AppState.ImportBatchSubmitting -> current.overview.importReview?.decisionDrafts ?: emptyMap()
                is P503AppState.OverviewEmpty -> current.importReview?.decisionDrafts ?: emptyMap()
                else -> emptyMap()
            }
        coordinator.submitImportUnknownCheckOnce {
            scope.launch(Dispatchers.Default) {
                // P704D-QUAL-01/SPEC-02: the guarded body keeps the check's single-flight slot
                // always releasable — the finally hop releases and dispatches the verdict after
                // any earlier hops (main-dispatcher FIFO). A pre-phase failure (the list re-read
                // or the use-case factory threw) maps to the SAME null-context path as an
                // unreadable replay: the verdict is StillUnknown (仍未知 — the item keeps its
                // check entry, the user can retry; never a stranded run).
                var outcome: ImportUnknownCheckOutcome = ImportUnknownCheckOutcome.StillUnknown
                // P7-06 06.1 (D-176; spec 4.3/4.4): the check runs under one lease; the captured
                // generation gates the landing below.
                var checkGeneration: Generation? = null
                try {
                    ledger.leased { facade, generation ->
                        checkGeneration = generation
                        val prePhase =
                            importBatchDispatchPrePhase(
                                loadRows = { facade.queryImportReviewRows?.query(ledger.ledgerId) ?: ImportReviewRowsResult.Unavailable },
                                loadUseCases = { facade.importConfirmUseCases?.invoke() },
                            )
                        if (prePhase is ImportBatchDispatchPrePhase.Ready) {
                            val context =
                                importUnknownCheckContext(
                                    item,
                                    prePhase.rows,
                                    drafts,
                                    confirmedAt,
                                    prePhase.useCases,
                                    ledger.ledgerId,
                                    ledger.parseAmount,
                                    ledger.currency,
                                )
                            outcome = runImportUnknownItemCheck(context) { useCase, request -> useCase.execute(request) }
                        }
                    }
                } finally {
                    scope.launch {
                        coordinator.importUnknownCheckCompleted()
                        if (checkGeneration != null && !ledger.isCurrentGeneration(checkGeneration)) {
                            // Stale generation: discard the verdict entirely.
                            return@launch
                        }
                        dispatch(P503UiEvent.ImportUnknownItemCheckResult(item, outcome))
                        if (outcome is ImportUnknownCheckOutcome.Confirmed) {
                            requestImportReviewRowsRead()
                        }
                        // Trigger (f) second call site (P7-03 FIX-STALE-1, D-153): the SAME
                        // shared all-terminal gate as the dispatch loop's completed branch — a
                        // 核对 verdict that completes the run's terminal set arms here; a
                        // mid-batch resolution (state still ImportBatchSubmitting), a
                        // StillUnknown verdict, or a summary still holding another Unknown arms
                        // nothing (the run continues or stays incomplete — its completing site
                        // arms exactly once).
                        // P7-08 08.D (A07): the arm landing runs through the JVM-assertable seam.
                        armAfterImportBatchConfirmation(latestState.value, coordinator) { armTagMerchantFilterReRequestIfLoaded() }
                    }
                }
            }
        }
    }

    // The platform pick results flow back through the composition root's channel (spec 4.1.1: the
    // result never rides the port's signature). The delivery thread is the UI thread on both ends
    // (the SAF callback runs on main; the desktop modal chooser blocks inside the UI event
    // handler, AB-CLI-QUAL-05), so the dispatch and the coordinator decision are safe there.
    DisposableEffect(ledger) {
        ledger.importPickResultChannel?.subscribe(::handleImportPickResult)
        onDispose { ledger.importPickResultChannel?.subscribe(null) }
    }

    // P7-09 09.0 (spec sections 2.1/2.2; D-225 Q19): the inbound neutral navigation requests flow
    // back through the composition root's channel (the pick-channel precedent). The channel buffers
    // one undelivered cold-start request, so a share received before this subscription is not lost;
    // it drains here on subscribe and is routed through [onExternalNavigationRequested]. The
    // channel rides the composition root's P503App argument rather than the lease scope, so the
    // shared lease surface (`LedgerLeaseScope`) is untouched by this batch.
    DisposableEffect(ledger, externalNavigationChannel, inboundHostToken) {
        // ACC-D-SHARE-01: the binding is keyed by the host token so the channel can refuse to hand a
        // request to a host that is not the resumed one (a previous instance keeps its composition
        // alive in a live process).
        externalNavigationChannel?.subscribe(inboundHostToken) { request ->
            // ACC-D-SHARE-02: a NEW inbound result supersedes the previous refusal notice, so a stale
            // "无法接收这次分享" can never stay on screen together with the freshly held share's strip.
            hostShareRejection = null
            onExternalNavigationRequested(request)
        }
        // ACC-D-SHARE-02: a refused inbound share has no request to route, so it rides its own
        // binding on the same channel and lands as the visible refusal strip.
        externalNavigationChannel?.subscribeShareRejections(inboundHostToken) { reason ->
            hostShareRejection = reason
        }
        onDispose {
            externalNavigationChannel?.subscribe(inboundHostToken, null)
            externalNavigationChannel?.subscribeShareRejections(inboundHostToken, null)
        }
    }

    // P7-09 09.B (D-226; spec section 2.1 `OpenImportTab`): load the EXISTING IMPORT surface when
    // the overview sits on the IMPORT tab with no projection yet. An inbound `OpenImportTab` request
    // (and the shortcut leg generally) does NOT pass through the tab bar's `onSelectTab` lambda that
    // owns the first-load request, so the load is driven from the LANDED state here instead — the
    // same trigger the tab tap uses (`requestImportReview` on `importReview == null`), and the same
    // shape as the existing state-keyed effect below (`LaunchedEffect(state)`). A wired/unwired
    // surface is handled inside [requestImportReview] (it returns early and dispatches nothing when
    // the import-review rows surface is absent), so an unwired composition renders no dead load.
    // The read itself is leased and generation-gated; this effect only starts it.
    LaunchedEffect(state) {
        val overview = state as? P503AppState.OverviewEmpty ?: return@LaunchedEffect
        if (overview.selectedTab == P503Tab.IMPORT && overview.importReview == null) {
            requestImportReview()
        }
    }

    // P7-02.D E-4: the authoritative snapshot with the pinned-first sort derivation applied to
    // its account and category lists (ordering only; the rows themselves are untouched).
    // A-PERF (spec section 2.3): reads the CACHED snapshot — this function serves the
    // composition-time event payloads (SelectTab/detail) and must never hit the database on the
    // main thread. The cache refresh is [requestCatalogSnapshotLoad] (single-flight background);
    // a null cache is propagated as-is so every consumer applies its own honest loading
    // placeholder instead of an empty authoritative catalog (S2-2).
    fun pinnedCatalogSnapshot(): CatalogSnapshotView? {
        val snapshot = cachedCatalogSnapshot ?: return null
        return CatalogSnapshotView(
            snapshot.catalogVersion,
            EntryPinOrdering.sortAccounts(snapshot.manageableAccounts, pinnedTargets),
            EntryPinOrdering.sortCategories(snapshot.categories, pinnedTargets),
        )
    }

    /**
     * P7-01.D: run one catalog command, then refresh the shared session and read the fresh
     * authoritative snapshot. `refreshCatalog()` reloads the catalog so options/reads/summaries
     * continue from the same version without a restart; a typed conflict is surfaced as a
     * banner and never retried automatically.
     *
     * A-PERF (spec section 2.3): the event payload's fresh snapshot comes from the session the
     * command just refreshed (`facade.catalogSnapshot()` after `facade.refreshCatalog()`, the
     * same synchronous pair the catalog-command path already owned — this path's WRITE chain is
     * the spec's out-of-scope disclosure; only the snapshot's *consumer* copies stay cached).
     * The load also updates the cached snapshot so the composition reads stay cache-only; a
     * failed load keeps the previous cache (S2-3).
     *
     * APSPEC-01: the event payload applies the SAME pinned-first ordering the previous
     * `pinnedCatalogSnapshot()` carried (EntryPinOrdering, the P7-02.D E-4 display order), so a
     * command completion / refresh keeps the management lists visually identical to the
     * SelectTab entry. The CACHE deliberately stores the raw authoritative order (the persisted
     * sequence) and every cached read applies the ordering at read time
     * ([pinnedCatalogSnapshot] / the detail screen), so a pin toggle cannot leak a
     * pin-sorted copy into the cache.
     */
    fun dispatchCatalogCommandResult(result: CatalogCommandResult) {
        // P7-06 06.1 (D-176; spec 4.3(b)): the refresh + snapshot pair runs under one operation
        // lease (the synchronous main-thread pair).
        val refreshReadModel = shouldRefreshReadModelAfterCatalogCommand(result)
        val fresh =
            ledger.probe { facade ->
                if (refreshReadModel) {
                    facade.refreshCatalog()
                }
                runCatching { facade.catalogSnapshot() }.getOrNull()
            } ?: (latestState.value as? P503AppState.OverviewEmpty)?.catalogSnapshot
        if (refreshReadModel) {
            // R1 (spec 6.2/7.3, D-027): HOME's balances/transaction lines come from the read
            // model, which now reads through the refreshed session; re-query it via the existing
            // refresh channel so a rename/deactivate shows new names on HOME without a restart.
            // Fired BEFORE the degenerate-snapshot return below (the base order): an unavailable
            // snapshot must not drop the read-model refresh chain.
            refresh()
        }
        val snapshot = fresh ?: return
        cachedCatalogSnapshot = snapshot
        val payload =
            CatalogSnapshotView(
                snapshot.catalogVersion,
                EntryPinOrdering.sortAccounts(snapshot.manageableAccounts, pinnedTargets),
                EntryPinOrdering.sortCategories(snapshot.categories, pinnedTargets),
            )
        // F1 (N-5): refresh() may have failed into InfrastructureFailure(READ), which has no
        // transition for management events; publish the outcome only while still on the overview.
        dispatchCatalogOutcomeIfOverview(latestState.value, P503UiEvent.CatalogCommandCompleted(result, payload), ::dispatch)
    }

    fun runCatalogToggle(event: P503UiEvent) {
        if (!ledger.surfaces.catalogCommands) return
        val version = (latestState.value as? P503AppState.OverviewEmpty)?.catalogSnapshot?.catalogVersion ?: return
        scope.launch {
            // P7-06 06.1 (spec 4.3(c)): the bare scope.launch catalog command runs under a lease.
            val result =
                ledger.probe { facade ->
                    val command = facade.executeCatalogCommand ?: return@probe null
                    when (event) {
                        is P503UiEvent.ManageAccountActive ->
                            command.setAccountActive(ledger.ledgerId, event.accountId, event.active, version)
                        is P503UiEvent.ManageCategoryActive ->
                            command.setCategoryActive(ledger.ledgerId, event.categoryId, event.active, version)
                        is P503UiEvent.EnableCategoryGroup ->
                            command.enableCategoryGroup(ledger.ledgerId, event.parentId, version)
                        else -> null
                    }
                } ?: return@launch
            dispatchCatalogCommandResult(result)
        }
    }

    // P7-02.D E-4: persist the pin toggle first (typed zero-write rejection for a missing or
    // cross-ledger target), then install the store's authoritative membership into both the host
    // mirror and the reducer; a rejection leaves the pure state untouched.
    // A-02 FIX-PIN-3: the same success also re-derives the management projection from the updated
    // pin set (the cached snapshot is read, never the database) so the ACCOUNTS lists re-sort on
    // the spot — the event carries it instead of a CatalogSnapshotRefreshed dispatch, which would
    // clear the open notice/dialog.
    fun runPinToggle(target: EntryPinTarget) {
        scope.launch {
            // P7-06 06.1 (spec 4.3(c)): the pin store write runs under an operation lease.
            when (val result = ledger.probe { facade -> facade.entryPreferences?.togglePin(target, ledger.ledgerClock.now()) }) {
                is EntryPinResult.Toggled -> {
                    pinnedTargets = if (result.pinned) pinnedTargets + target else pinnedTargets - target
                    dispatch(P503UiEvent.TogglePin(target, result.pinned, pinnedCatalogSnapshot()))
                }
                is EntryPinResult.Rejected, null -> Unit
            }
        }
    }

    fun runCatalogForm(dialog: CatalogDialog) {
        if (!ledger.surfaces.catalogCommands) return
        val version = (latestState.value as? P503AppState.OverviewEmpty)?.catalogSnapshot?.catalogVersion ?: return
        scope.launch {
            // P7-06 06.1 (spec 4.3(c)): the bare scope.launch catalog command runs under a lease.
            val result =
                ledger.probe { facade ->
                    val command = facade.executeCatalogCommand ?: return@probe null
                    when (dialog) {
                        is CatalogDialog.CreateAccount ->
                            command.createAccount(ledger.ledgerId, dialog.nameText, dialog.kind, version)
                        is CatalogDialog.RenameAccount ->
                            command.renameAccount(ledger.ledgerId, dialog.accountId, dialog.nameText, version)
                        is CatalogDialog.CreateCategoryGroup ->
                            command.createCategoryGroup(ledger.ledgerId, dialog.kind, dialog.groupNameText, dialog.firstChildNameText, version)
                        is CatalogDialog.AppendCategoryChild ->
                            command.appendCategoryChild(ledger.ledgerId, dialog.parentId, dialog.nameText, version)
                        is CatalogDialog.RenameCategory ->
                            command.renameCategory(ledger.ledgerId, dialog.categoryId, dialog.nameText, version)
                        is CatalogDialog.ConfirmCategoryDelete ->
                            command.deleteCategory(ledger.ledgerId, dialog.categoryId, version)
                        // P7-08 08.C: the tag/merchant dialogs route to runTagMerchantForm, so a
                        // stray dispatch here performs nothing (null = no command result).
                        is CatalogDialog.CreateTagItem,
                        is CatalogDialog.RenameTagItem,
                        is CatalogDialog.ConfirmTagItemDelete,
                        is CatalogDialog.CreateMerchantItem,
                        is CatalogDialog.RenameMerchantItem,
                        is CatalogDialog.ConfirmMerchantItemDelete,
                        -> null
                        CatalogDialog.None -> null
                    }
                } ?: return@launch
            dispatchCatalogCommandResult(result)
        }
    }

    // Explicit refresh used by the management screen: reload the shared session and install the
    // reloaded projection (a no-op command success path). The read model is re-queried against the
    // reloaded session too, for the same HOME-freshness reason as the command success path.
    // A-PERF (spec section 2.3): the explicit refresh is the one user action that legitimately
    // reads the freshly refreshed session synchronously (its own button press, the same
    // synchronous pair the command path owns) — the result also updates the cached snapshot so
    // every composition-time consumer stays cache-only.
    // APSPEC-01: the event payload applies the same pinned-first ordering as every other
    // catalog-snapshot entry point (EntryPinOrdering; the cache keeps the raw authoritative
    // order, see [dispatchCatalogCommandResult]).
    fun refreshCatalogSnapshot() {
        // P7-06 06.1 (spec 4.3(b)): the synchronous refresh + snapshot pair runs under one lease.
        val fresh =
            ledger.probe { facade ->
                facade.refreshCatalog()
                runCatching { facade.catalogSnapshot() }.getOrNull()
            }
        // Fired BEFORE the degenerate-snapshot return (the base order): an unavailable snapshot
        // must not drop the read-model refresh chain.
        refresh()
        val snapshot = fresh ?: return
        cachedCatalogSnapshot = snapshot
        val payload =
            CatalogSnapshotView(
                snapshot.catalogVersion,
                EntryPinOrdering.sortAccounts(snapshot.manageableAccounts, pinnedTargets),
                EntryPinOrdering.sortCategories(snapshot.categories, pinnedTargets),
            )
        // F1 (N-5): same overview-only guard as the command success path.
        dispatchCatalogOutcomeIfOverview(latestState.value, P503UiEvent.CatalogSnapshotRefreshed(payload), ::dispatch)
    }

    // P702SPEC-03: run one counterparty directory command from the editor dialog. A successful
    // command closes the dialog and bumps the option refresh marker; a typed rejection is
    // absorbed safely — the dialog stays open with the typed text and nothing is dispatched.
    // A-02 FIX-LEND-1 (D-152): a successful command also refreshes the shared catalog session's
    // authority (the same existing convention as the catalog-command path in
    // [dispatchCatalogCommandResult] and [refreshCatalogSnapshot]) BEFORE the option projections
    // re-derive — the create wrote a directory row the session's authority did not know, so the
    // subsequent LEND/COLLECT commit's authoritative read failed its consistency gate into a
    // full-screen READ failure until a restart. Rename rewrites no posting reference, so the
    // refresh is harmless and keeps the convention uniform. The failure handling stays exactly
    // the catalog-command path's shape (no added failure surface).
    fun runCounterpartyForm(dialog: CounterpartyDialog) {
        if (!ledger.surfaces.counterpartyCommands) return
        scope.launch {
            // P7-06 06.1 (spec 4.3(c)): the counterparty command + refresh run under one lease.
            val refreshed =
                ledger.probe { facade ->
                    val commands = facade.counterpartyCommands ?: return@probe false
                    val result =
                        when (dialog) {
                            is CounterpartyDialog.Create -> commands.create.execute(ledger.ledgerId, dialog.nameText)
                            is CounterpartyDialog.Rename -> commands.rename.execute(ledger.ledgerId, dialog.counterpartyId, dialog.nameText)
                        }
                    if (shouldRefreshOptionsAfterCounterpartyCommand(result)) {
                        facade.refreshCatalog()
                        true
                    } else {
                        false
                    }
                } ?: false
            if (refreshed) {
                counterpartyVersion++
                dispatch(P503UiEvent.DismissCounterpartyDialog)
            }
        }
    }

    // P7-02.D E-2: the "record again" affordance and its host reset (hoisted occurred-at text)
    // live in the dispatch guard and the tab shell below; the reducer effect, the retainedIntent
    // payload and the injection channel were frozen and implemented in P7-02.A.
    fun retainedIntentRevalidation(): RetainedIntentRevalidation =
        RetainedIntentRevalidation(
            accountIds =
                buildSet {
                    options.paymentAccounts.forEach { add(it.accountId) }
                    incomeOptions.receivingAccounts.forEach { add(it.accountId) }
                    transferOptions.ownedAssetAccounts.forEach { add(it.accountId) }
                    lendingOptions.ownedAssetAccounts.forEach { add(it.accountId) }
                },
            expenseCategoryIds =
                buildSet {
                    options.expenseCategories.forEach { add(it.categoryId) }
                    transferOptions.feeCategories.forEach { add(it.categoryId) }
                },
            incomeCategoryIds =
                buildSet {
                    incomeOptions.incomeCategories.forEach { add(it.categoryId) }
                    lendingOptions.interestCategories.forEach { add(it.categoryId) }
                },
        )

    // P7-02.A: the account/category labels depend on the draft type; both are resolved from
    // the authoritative options and fall back to the draft id values in the reducer.
    fun accountLabel(draft: TypedEntryDraft): String =
        when (draft) {
            is IncomeDraft -> incomeOptions.receivingAccounts.firstOrNull { it.accountId == draft.receivingAccountId }?.label ?: ""
            is ExpenseDraft -> options.paymentAccounts.firstOrNull { it.accountId == draft.paymentAccountId }?.label ?: ""
            is TransferDraft -> transferOptions.ownedAssetAccounts.firstOrNull { it.accountId == draft.sourceAccountId }?.label ?: ""
            is LendDraft -> lendingOptions.ownedAssetAccounts.firstOrNull { it.accountId == draft.fundingAccountId }?.label ?: ""
            is CollectDraft -> lendingOptions.ownedAssetAccounts.firstOrNull { it.accountId == draft.destinationAccountId }?.label ?: ""
        }

    fun categoryLabel(draft: TypedEntryDraft): String =
        when (draft) {
            is IncomeDraft -> incomeOptions.incomeCategories.firstOrNull { it.categoryId == draft.categoryId }?.label ?: ""
            is ExpenseDraft -> options.expenseCategories.firstOrNull { it.categoryId == draft.categoryId }?.label ?: ""
            is TransferDraft -> transferOptions.feeCategories.firstOrNull { it.categoryId == draft.feeCategoryId }?.label ?: ""
            is LendDraft -> lendingOptions.counterparties.firstOrNull { it.counterpartyId == draft.counterpartyId }?.name ?: ""
            is CollectDraft -> lendingOptions.interestCategories.firstOrNull { it.categoryId == draft.interestCategoryId }?.label ?: ""
        }

    // A-02 FIX-CONFIRM-1: the two type-owned confirmation labels. A `null` (the type owns no such
    // row, or the object was never resolved) lets the reducer fall back to the draft id.
    fun destinationAccountLabel(draft: TypedEntryDraft): String? =
        when (draft) {
            is TransferDraft -> transferOptions.ownedAssetAccounts.firstOrNull { it.accountId == draft.destinationAccountId }?.label
            else -> null
        }

    fun counterpartyLabel(draft: TypedEntryDraft): String? =
        when (draft) {
            is LendDraft -> lendingOptions.counterparties.firstOrNull { it.counterpartyId == draft.counterpartyId }?.name
            is CollectDraft -> lendingOptions.counterparties.firstOrNull { it.counterpartyId == draft.counterpartyId }?.name
            else -> null
        }

    // ---------------------------------------------------------------- P7-05 correction/void/recycle-bin host surface
    // Slice 1b Piece 4 (D-156; spec sections 3.5/4.3/4.4): the host call sites that make the P7-05
    // surfaces reachable. Every commit runs OFF the UI thread (Dispatchers.Default) and dispatches
    // its result back ON the composition's main dispatcher (the P704C-SPEC-01/QUAL-02 hop — every
    // async dispatch stays serial with user events). A determinate success fires the authoritative
    // refresh + post-landing monthly re-request through the shared P7-05 trigger
    // (coordinator.onP705EffectiveSurfaceChanged via the pure `refreshAfterP705Commit` seam); a
    // rejection/stale/conflict refreshes nothing. The void/restore reason and note are only ever
    // carried into the domain type and never logged (V-21).
    //
    // V-19 (D-173): a lost P7-05 commit whose snapshot-aware resolution is Absent/Unavailable has no
    // result to dispatch, so the surface keeps `submitting = true` with NO automatic retry and NO
    // requestId swap. The P7-05 surfaces DO now offer an in-session MANUAL re-check
    // (recheckCorrectionCommitStatus / recheckVoidCommitStatus / recheckRestoreCommitStatus), which
    // mirrors the P7-02 UnknownCommit 「重新核对」 affordance (D-126 R4): it re-invokes the same
    // read-only resolver against the RETAINED snapshot captured at confirm time, so a mutated draft
    // can never produce a false SnapshotConflict. It remains session-scoped (the host slot is
    // `remember`, not `rememberSaveable`): after an app restart the surface and its marker are gone,
    // which is why D-173 keeps V-19 at 已实现待验收 rather than PASS. This is the same
    // no-auto-retry/no-requestId-swap rule the reducer pins (V-19).

    /**
     * P7-05.B (DP-12: 仅详情入口): resolves the old-value snapshot and opens the correction surface.
     * The shape decision and its rationale live in [transactionEditEntrance].
     */
    fun openTransactionEdit(detail: TransactionDetail) {
        if (!ledger.surfaces.correction) return
        val snapshot = cachedCatalogSnapshot ?: return
        // The shape decision is the host's pure seam, so the split is JVM-assertable.
        when (val entrance = transactionEditEntrance(detail, snapshot)) {
            is TransactionEditEntrance.Transfer -> dispatch(P503UiEvent.OpenTransferEdit(entrance.origin))
            is TransactionEditEntrance.Ordinary -> dispatch(P503UiEvent.OpenTransactionEdit(entrance.origin))
            null -> return
        }
    }

    /** P7-05.B: whether the detail page's edit entry can be resolved right now (facade + catalog + payload). */
    fun correctionOriginResolvable(detail: TransactionDetail): Boolean {
        val snapshot = cachedCatalogSnapshot ?: return false
        // The probe IS the open path's decision, so the page never renders an entry whose click
        // would do nothing.
        return ledger.surfaces.correction && transactionEditEntrance(detail, snapshot) != null
    }

    /** P7-05.C (DP-12): opens the void confirmation page from the detail. */
    fun openVoidConfirm(detail: TransactionDetail) {
        if (!ledger.surfaces.voidTransaction) return
        dispatch(P503UiEvent.OpenVoidConfirm(detail.transactionId))
    }

    /**
     * P7-08 08.B-2 (spec section 4.4): opens the annotation-edit surface from the read-only detail.
     * The CAS tokens and the current selection come from the pre-commit read
     * ([P503LedgerFacade.transactionAnnotationSnapshot]) — never from the detail projection, which
     * does not carry the annotation — and the read runs OFF the main thread under a lease (the
     * A-PERF read-governance ruling: no facade catalog/ledger read during recomposition). A null
     * snapshot (unwired read port, or an unresolvable transaction) opens nothing rather than a form
     * with fabricated tokens; the open is a main-thread dispatch after the read lands.
     */
    fun openTransactionAnnotationEdit(detail: TransactionDetail) {
        if (!annotationEditWired) return
        val transactionId = detail.transactionId
        scope.launch(Dispatchers.Default) {
            // P7-08 08.D (spec section 2.2 停用保留): the SAME lease also reads the authority so the
            // retained-but-inactive references carry their display names; an unreadable authority
            // degrades to raw-id labels, never to a fabricated name.
            val outcome =
                ledger.leased { facade, _ ->
                    runCatching {
                        val snapshot = facade.transactionAnnotationSnapshot(transactionId) ?: return@runCatching null
                        val authority = facade.tagMerchantCatalogReader?.invoke()?.load(facade.ledgerId)
                        snapshot to authority
                    }.getOrNull()
                }
            scope.launch {
                val resolved =
                    (outcome as? LeaseOutcome.Completed)
                        ?.takeIf { ledger.isCurrentGeneration(it.generation) }
                        ?.value ?: return@launch
                val (snapshot, authority) = resolved
                val inactiveTags =
                    snapshot.tagIds.mapNotNull { tagId ->
                        authority?.tags?.firstOrNull { it.id == tagId.value }?.takeIf { !it.active || it.tombstoned }
                    }
                val inactiveMerchant =
                    snapshot.merchantId?.let { merchantId ->
                        authority?.merchants?.firstOrNull { it.id == merchantId.value }?.takeIf { !it.active || it.tombstoned }
                    }
                dispatch(
                    P503UiEvent.OpenTransactionAnnotationEdit(
                        TransactionAnnotationEditIntent(
                            transactionId = snapshot.transactionId,
                            expectedAnnotationRevision = snapshot.currentAnnotationRevision,
                            expectedCurrentVersionId = snapshot.currentVersionId,
                            tagIds = snapshot.tagIds.toSet(),
                            merchantId = snapshot.merchantId,
                            retainedTagIds = snapshot.tagIds.toSet(),
                            retainedMerchantId = snapshot.merchantId,
                            inactiveTagLabels = inactiveTags.associate { it.id to it.name },
                            inactiveMerchantLabel = inactiveMerchant?.name,
                        ),
                    ),
                )
            }
        }
    }

    /**
     * P7-08 08.B-2: whether the detail's annotation-edit entry can be offered. Gated on the wired
     * trio only (learned once off-thread); the per-transaction resolvability is settled by the read
     * [openTransactionAnnotationEdit] performs, so a target it cannot read opens nothing rather than
     * a form with fabricated CAS tokens.
     */
    fun annotationEditResolvable(detail: TransactionDetail): Boolean = annotationEditWired

    /**
     * P7-05.B: runs the correction commit off the UI thread. A lost commit is resolved
     * snapshot-aware (spec section 4.2): a matching receipt is the original success, a mismatched
     * snapshot is the stable identity conflict, and an absent/unreadable row stays unknown — the
     * surface keeps its submitting marker with no automatic retry and no request-id swap (the
     * `UnknownCommit` discipline; the reducer owns that marker).
     * P7-06 06.1 (D-176; spec 4.3/4.4): the commit (and its lost-commit resolution) runs under one
     * lease; the captured generation gates the landing.
     */
    fun commitTransactionCorrection(request: ExplicitlyConfirmedTransactionCorrection) {
        if (!ledger.surfaces.correction) return
        scope.launch(Dispatchers.Default) {
            val outcome =
                ledger.leased { facade, _ ->
                    try {
                        facade.correctTransactionVersion!!.execute(request)
                    } catch (failure: Exception) {
                        val resolver = facade.resolveCorrectionCommitStatus ?: return@leased null
                        val identity = TransactionCorrectionRequestIdentity(request.ledgerId, request.requestId)
                        val resolution = resolver.resolve(request.ledgerId, request.requestId, request.toRequestSnapshot())
                        correctResultFromResolution(identity, resolution)
                    }
                }
            scope.launch {
                if (outcome !is LeaseOutcome.Completed || !ledger.isCurrentGeneration(outcome.generation)) return@launch
                val result = outcome.value ?: return@launch
                dispatch(P503UiEvent.TransactionEditResult(result))
                // P7-08 08.D (A07): the refresh-and-arm landing runs through the JVM-assertable
                // seam (the D-156 precedent) so the trigger matrix stays pinned.
                refreshAndArmAfterP705CommitLanding(result, coordinator) { armTagMerchantFilterReRequestIfLoaded() }
            }
        }
    }

    /**
     * P7-05.B: the explicit correction confirm. The wiring/field guard runs BEFORE the reducer marks
     * the surface submitting (the P704C-QUAL-04 call-site precedent): an unwired facade or an
     * inadmissible draft never strands the page. The whole decision runs inside
     * [dispatchCurrentP503Action]'s instance guard, so a stale render (the surface already
     * submitting, or already left) can neither mint an intent nor disturb an in-flight commit. The
     * request id is host-minted for this one intent (the reducer is IO-free and randomness-free);
     * the same id feeds the commit and its resolver.
     */
    fun confirmTransactionCorrection(current: P503AppState.TransactionEdit) {
        if (!ledger.surfaces.correction) return
        var request: ExplicitlyConfirmedTransactionCorrection? = null
        dispatchCurrentP503Action(
            current,
            latestState.value,
            {
                val requestId = ledger.requestIdSource.next()
                val built =
                    transactionCorrectionRequest(
                        ledgerId = ledger.ledgerId,
                        requestId = requestId,
                        origin = current.origin,
                        draft = current.draft,
                        parseAmount = ledger.parseAmount,
                        parseOccurredAt = ledger.parseOccurredAt,
                        ledgerClock = ledger.ledgerClock,
                        fallbackCurrency = ledger.currency,
                    )
                if (built == null) {
                    P503UiEvent.TransactionEditResult(CORRECTION_FIELD_REJECTION)
                } else {
                    request = built
                    P503UiEvent.ConfirmTransactionEdit(requestId)
                }
            },
            ::dispatch,
        ) {
            request?.let { built ->
                // V-19 (D-173): retain the committed snapshot for a later in-session manual
                // re-check (the draft stays editable, so it must never be re-derived).
                retainedP705Request = built.toRetainedRequest()
                commitTransactionCorrection(built)
            }
        }
    }

    /**
     * P7-05 (D-228): the explicit confirm of a transfer correction. The same shape as
     * [confirmTransactionCorrection] - the parse guard runs BEFORE the submitting marker (an
     * unreadable edited statistics time becomes the surface's existing typed field rejection,
     * never a silent revert to the stored instant), the request id is minted for this one intent,
     * and the retained snapshot feeds the in-session manual re-check. The commit itself is the
     * shared correction path: the domain plans the transfer's posting set from the same request.
     */
    fun confirmTransferCorrection(current: P503AppState.TransferEdit) {
        if (!ledger.surfaces.correction) return
        var request: ExplicitlyConfirmedTransactionCorrection? = null
        dispatchCurrentP503Action(
            current,
            latestState.value,
            {
                val requestId = ledger.requestIdSource.next()
                val statisticsAt =
                    transferCorrectionStatisticsInstant(
                        origin = current.origin,
                        draft = current.draft,
                        parseOccurredAt = ledger.parseOccurredAt,
                        ledgerClock = ledger.ledgerClock,
                    )
                val built =
                    statisticsAt?.let { instant ->
                        transferCorrectionRequest(
                            ledgerId = ledger.ledgerId,
                            requestId = requestId,
                            origin = current.origin,
                            draft = current.draft,
                            statisticsAt = instant,
                        )
                    }
                if (built == null) {
                    P503UiEvent.TransactionEditResult(CORRECTION_FIELD_REJECTION)
                } else {
                    request = built
                    P503UiEvent.ConfirmTransactionEdit(requestId)
                }
            },
            ::dispatch,
        ) {
            request?.let { built ->
                retainedP705Request = built.toRetainedRequest()
                commitTransactionCorrection(built)
            }
        }
    }

    /**
     * P7-08 08.B-2 (spec section 4.4): the explicit confirm of an annotation edit runs off the UI
     * thread through the caller's existing lease (the correction path's discipline). The CAS tokens
     * ride the state's intent — the host captured them from the detail read inside the same lease,
     * so the reducer never re-reads the ledger. The four-state result is mapped onto the surface
     * verbatim; a determinate success also triggers the authoritative refresh so the detail and the
     * month projection re-read the new revision (spec section 3.2/presentation refresh discipline).
     */
    fun confirmTransactionAnnotationEdit(current: P503AppState.TransactionAnnotationEdit) {
        if (!annotationEditWired) return
        dispatch(P503UiEvent.ConfirmTransactionAnnotationEdit)
        scope.launch(Dispatchers.Default) {
            val leased =
                ledger.leased { facade, _ ->
                    val command = facade.updateTransactionAnnotation
                    val requestId = facade.annotationRequestIdSource?.next()
                    if (command == null || requestId == null) {
                        // The command was unwired between the render gate and the confirm: an
                        // honest contract rejection, never a faked success (the enum's real token).
                        TransactionAnnotationEditOutcome.Rejected(
                            com.unifiedledger.application.AnnotationFailureCode.ANNOTATION_CONSTRAINT_VIOLATION.code,
                        )
                    } else {
                        try {
                            command
                                .update(
                                    ledgerId = facade.ledgerId,
                                    transactionId = current.intent.transactionId,
                                    tagIds = current.intent.tagIds.toList(),
                                    merchantId = current.intent.merchantId,
                                    expectedAnnotationRevision = current.intent.expectedAnnotationRevision,
                                    expectedCurrentVersionId = current.intent.expectedCurrentVersionId,
                                    // P7-08 08.D (spec section 2.2 停用保留): the association captured at
                                    // open — an inactive reference the user kept stays allowed, while any
                                    // newly introduced inactive one is still the typed rejection.
                                    retainedTagIds = current.intent.retainedTagIds,
                                    retainedMerchantId = current.intent.retainedMerchantId,
                                ).toEditOutcome()
                        } catch (failure: kotlinx.coroutines.CancellationException) {
                            // A cancelled lease block is NOT an unknown outcome — rethrow so
                            // structured cancellation keeps its semantics (the budget precedent).
                            throw failure
                        } catch (failure: Exception) {
                            // A failed call is surfaced as a rejection, never swallowed (the P705
                            // resolver discipline); the message is not echoed to avoid leaking detail.
                            TransactionAnnotationEditOutcome.Rejected(
                                com.unifiedledger.application.AnnotationFailureCode.ANNOTATION_CONSTRAINT_VIOLATION.code,
                            )
                        }
                    }
                }
            val outcome =
                (leased as? LeaseOutcome.Completed)
                    ?.takeIf { ledger.isCurrentGeneration(it.generation) }
                    ?.value
                    // A non-completed or stale-generation lease has NO result to land; the surface
                    // must not stay 提交中 forever (提交中不得离开 would trap the user), so it lands a
                    // typed rejection that clears the marker — never a silent hang, never a fake success.
                    ?: TransactionAnnotationEditOutcome.Rejected(
                        com.unifiedledger.application.AnnotationFailureCode.ANNOTATION_CONSTRAINT_VIOLATION.code,
                    )
            scope.launch { dispatch(P503UiEvent.TransactionAnnotationEditResult(outcome)) }
            // Determinate success: the detail and the month projection re-read the new revision
            // through the same effective-surface refresh the P7-05 commits use (spec section
            // 3.2; a rejection/stale/conflict refreshes nothing — the annotation is unchanged).
            // P7-08 08.D (A07): the refresh-and-arm landing runs through the JVM-assertable seam.
            refreshAndArmAfterAnnotationEdit(outcome, coordinator) { armTagMerchantFilterReRequestIfLoaded() }
        }
    }

    /**
     * T5 (spec section 5.5 row 1): a catalog command changed the tag/merchant surface with ZERO
     * transaction effect — the authoritative session refresh (options/reads follow one catalog
     * version), the tag/merchant authority reload, and the loaded filter/accumulation
     * re-request all follow.
     */
    fun refreshTagMerchantSurfacesAfterCommand() {
        refreshCatalogSnapshot()
        scope.launch(Dispatchers.Default) {
            val outcome =
                ledger.leased { facade, _ -> runCatching { facade.tagMerchantCatalogReader?.invoke()?.load(facade.ledgerId) }.getOrNull() }
            scope.launch {
                (outcome as? LeaseOutcome.Completed)
                    ?.takeIf { ledger.isCurrentGeneration(it.generation) }
                    ?.let { if (it.value != null) cachedTagMerchantAuthority = it.value }
                val current = latestState.value as? P503AppState.TagMerchantFilter ?: return@launch
                if (current.loadedEntries.isEmpty() && current.accumulation == null) return@launch
                requestTagMerchantFilterPage(current, replace = true, cursor = null)
                if (current.condition.merchantId != null) requestMerchantAccumulation(current)
            }
        }
    }

    /** The per-item CAS revision for one authority item (create uses the 0 sentinel). */
    fun tagMerchantRevisionOf(
        authority: TagMerchantAuthority,
        kind: CatalogItemKind,
        itemId: String,
    ): Long? =
        (if (kind == CatalogItemKind.TAG) authority.tags else authority.merchants)
            .firstOrNull { it.id == itemId }
            ?.revision

    /** Runs one tag/merchant catalog-item command from a management dialog (off the UI thread). */
    fun runTagMerchantForm(dialog: CatalogDialog) {
        if (!tagMerchantCatalogWired) return
        val authority = cachedTagMerchantAuthority ?: return
        scope.launch {
            val result =
                ledger.probe { facade ->
                    val command = facade.executeTagMerchantCommand ?: return@probe null
                    when (dialog) {
                        is CatalogDialog.CreateTagItem -> command.createItem(ledger.ledgerId, CatalogItemKind.TAG, dialog.nameText, expectedRevision = 0L)
                        is CatalogDialog.RenameTagItem ->
                            tagMerchantRevisionOf(authority, CatalogItemKind.TAG, dialog.itemId)
                                ?.let { revision -> command.renameItem(ledger.ledgerId, CatalogItemKind.TAG, dialog.itemId, dialog.nameText, revision) }
                        is CatalogDialog.ConfirmTagItemDelete ->
                            tagMerchantRevisionOf(authority, CatalogItemKind.TAG, dialog.itemId)
                                ?.let { revision -> command.deleteItem(ledger.ledgerId, CatalogItemKind.TAG, dialog.itemId, revision) }
                        is CatalogDialog.CreateMerchantItem -> command.createItem(ledger.ledgerId, CatalogItemKind.MERCHANT, dialog.nameText, expectedRevision = 0L)
                        is CatalogDialog.RenameMerchantItem ->
                            tagMerchantRevisionOf(authority, CatalogItemKind.MERCHANT, dialog.itemId)
                                ?.let { revision -> command.renameItem(ledger.ledgerId, CatalogItemKind.MERCHANT, dialog.itemId, dialog.nameText, revision) }
                        is CatalogDialog.ConfirmMerchantItemDelete ->
                            tagMerchantRevisionOf(authority, CatalogItemKind.MERCHANT, dialog.itemId)
                                ?.let { revision -> command.deleteItem(ledger.ledgerId, CatalogItemKind.MERCHANT, dialog.itemId, revision) }
                        else -> null
                    }
                } ?: return@launch
            dispatch(P503UiEvent.TagMerchantCommandLanded(result))
            if (result is TagMerchantCommandResult.Accepted ||
                result is TagMerchantCommandResult.NoChange
            ) {
                refreshTagMerchantSurfacesAfterCommand()
            }
        }
    }

    /** Runs one enable/disable toggle (the ManageAccountActive host-handled precedent). */
    fun runTagMerchantToggle(
        kind: CatalogItemKind,
        itemId: String,
        active: Boolean,
    ) {
        if (!tagMerchantCatalogWired) return
        val authority = cachedTagMerchantAuthority ?: return
        val revision = tagMerchantRevisionOf(authority, kind, itemId) ?: return
        scope.launch {
            val result =
                ledger.probe { facade -> facade.executeTagMerchantCommand?.setItemActive(ledger.ledgerId, kind, itemId, active, revision) }
                    ?: return@launch
            dispatch(P503UiEvent.TagMerchantCommandLanded(result))
            if (result is TagMerchantCommandResult.Accepted ||
                result is TagMerchantCommandResult.NoChange
            ) {
                refreshTagMerchantSurfacesAfterCommand()
            }
        }
    }

    /** P7-05.C: runs the void commit off the UI thread (the correction path's resolver discipline). */
    fun commitTransactionVoid(request: VoidTransactionRequest) {
        if (!ledger.surfaces.voidTransaction) return
        scope.launch(Dispatchers.Default) {
            val outcome =
                ledger.leased { facade, _ ->
                    try {
                        facade.voidTransaction!!.execute(request)
                    } catch (failure: Exception) {
                        val resolver = facade.resolveVoidCommitStatus ?: return@leased null
                        val snapshot = request.toRequestSnapshot(TransactionVoidFactKind.VOID) ?: return@leased null
                        val identity = TransactionVoidRequestIdentity(request.ledgerId, request.requestId)
                        voidResultFromResolution(identity, resolver.resolve(request.ledgerId, request.requestId, snapshot))
                    }
                }
            scope.launch {
                if (outcome !is LeaseOutcome.Completed || !ledger.isCurrentGeneration(outcome.generation)) return@launch
                val result = outcome.value ?: return@launch
                dispatch(P503UiEvent.TransactionVoidResult(result))
                // P7-08 08.D (A07): the refresh-and-arm landing runs through the JVM-assertable seam.
                refreshAndArmAfterVoidRestoreCommit(result, coordinator) { armTagMerchantFilterReRequestIfLoaded() }
            }
        }
    }

    /**
     * P7-05.C: the explicit void confirm; the mandatory reason is re-checked against the domain rule
     * before submitting. The whole decision runs inside [dispatchCurrentP503Action]'s instance
     * guard, so a stale render can neither mint an intent nor disturb an in-flight commit.
     */
    fun confirmVoid(current: P503AppState.VoidConfirm) {
        if (!ledger.surfaces.voidTransaction) return
        var request: VoidTransactionRequest? = null
        dispatchCurrentP503Action(
            current,
            latestState.value,
            {
                val rejection = voidReasonDraftRejection(current.reason)
                val reason = voidReasonFromDraft(current.reason)
                if (rejection != null || reason == null) {
                    // The mandatory-reason rejection is the domain's own code (never restated here).
                    P503UiEvent.TransactionVoidResult(
                        VoidTransactionResult.Rejected(rejection ?: P705FailureCode.P705_VOID_REASON_REQUIRED),
                    )
                } else {
                    val requestId = ledger.requestIdSource.next()
                    request = VoidTransactionRequest(ledger.ledgerId, requestId, current.transactionId, reason, ExplicitManualSave)
                    P503UiEvent.ConfirmVoid(requestId)
                }
            },
            ::dispatch,
        ) {
            request?.let { built ->
                // V-19 (D-173): retain the committed void snapshot (factKind = VOID) for re-check.
                retainedP705Request = built.toRetainedRequest(TransactionVoidFactKind.VOID)
                commitTransactionVoid(built)
            }
        }
    }

    /** P7-05.C: runs the restore commit off the UI thread (the void path's resolver discipline, `factKind = restore`). */
    fun commitRestore(request: VoidTransactionRequest) {
        if (!ledger.surfaces.restore) return
        scope.launch(Dispatchers.Default) {
            val outcome =
                ledger.leased { facade, _ ->
                    try {
                        facade.restoreTransaction!!.execute(request)
                    } catch (failure: Exception) {
                        val resolver = facade.resolveVoidCommitStatus ?: return@leased null
                        val snapshot = request.toRequestSnapshot(TransactionVoidFactKind.RESTORE) ?: return@leased null
                        val identity = TransactionVoidRequestIdentity(request.ledgerId, request.requestId)
                        voidResultFromResolution(identity, resolver.resolve(request.ledgerId, request.requestId, snapshot))
                    }
                }
            scope.launch {
                if (outcome !is LeaseOutcome.Completed || !ledger.isCurrentGeneration(outcome.generation)) return@launch
                val result = outcome.value ?: return@launch
                dispatch(P503UiEvent.TransactionRestoreResult(result))
                // P7-08 08.D (A07): the refresh-and-arm landing runs through the JVM-assertable seam.
                refreshAndArmAfterVoidRestoreCommit(result, coordinator) { armTagMerchantFilterReRequestIfLoaded() }
            }
        }
    }

    /**
     * P7-05.C: the explicit restore confirm from the nested bin sub-state (the reason is mandatory
     * for a restore too). The whole decision runs inside [dispatchCurrentP503Action]'s instance
     * guard, so a stale render can neither mint an intent nor disturb an in-flight commit.
     */
    fun confirmRestore(current: P503AppState.RecycleBin) {
        val restore = current.restore ?: return
        if (!ledger.surfaces.restore) return
        var request: VoidTransactionRequest? = null
        dispatchCurrentP503Action(
            current,
            latestState.value,
            {
                val rejection = voidReasonDraftRejection(restore.reason)
                val reason = voidReasonFromDraft(restore.reason)
                if (rejection != null || reason == null) {
                    P503UiEvent.TransactionRestoreResult(
                        VoidTransactionResult.Rejected(rejection ?: P705FailureCode.P705_VOID_REASON_REQUIRED),
                    )
                } else {
                    val requestId = ledger.requestIdSource.next()
                    request = VoidTransactionRequest(ledger.ledgerId, requestId, restore.transactionId, reason, ExplicitManualSave)
                    P503UiEvent.ConfirmRestore(requestId)
                }
            },
            ::dispatch,
        ) {
            request?.let { built ->
                // V-19 (D-173): retain the committed restore snapshot (factKind = RESTORE).
                retainedP705Request = built.toRetainedRequest(TransactionVoidFactKind.RESTORE)
                commitRestore(built)
            }
        }
    }

    /**
     * P7-05.C: reads the recycle bin off the UI thread and dispatches the fresh projection. The bin
     * is always read fresh on open (and re-read in place when it is already the open surface), so a
     * restore's effect is visible on the next open without any cached second source of truth.
     */
    fun requestRecycleBin() {
        if (!ledger.surfaces.recycleBin) return
        val alreadyOpen = latestState.value is P503AppState.RecycleBin
        scope.launch(Dispatchers.Default) {
            // P7-06 06.1 (D-176; spec 4.3/4.4): read under a lease; the landing hop discards a
            // result captured under a superseded generation.
            val outcome = ledger.leased { facade, _ -> facade.queryRecycleBin?.query() }
            scope.launch {
                if (outcome !is LeaseOutcome.Completed || !ledger.isCurrentGeneration(outcome.generation)) return@launch
                val result = outcome.value ?: return@launch
                dispatch(recycleBinReadEvent(alreadyOpen, result))
            }
        }
    }

    // ---------------------------------------------------------------- P7-06 06.B backup export host surface
    // P7-06 06.B (D-177; spec sections 3/5): the host export call site. The export runs OFF the UI
    // thread (Dispatchers.Default — spec section 5 forbids the UI thread for the snapshot, KDF,
    // streaming crypto and delivery) and its typed result hops back ON the composition's main
    // dispatcher (the P704C-SPEC-01/QUAL-02 hop, so every async dispatch stays serial with user
    // events). The use case acquires and releases its OWN operation lease internally, so this call
    // is deliberately NOT wrapped in `ledger.leased` — a second lease would be redundant and the
    // 06.1 scope exposes the use case lease-free for exactly this reason.
    //
    // Generation guard (spec section 1.1): [BackupExportResult] carries no generation, so the host
    // captures the active generation at launch through [LedgerLeaseScope.backupExportLaunch] and
    // discards a result that lands after the graph was replaced (a reopen increments the
    // generation). The captured value is compared with the SAME [LedgerLeaseScope.isCurrentGeneration]
    // every other generation-bound landing uses.
    //
    // Secret discipline (spec section 3.5): the password lives only in the reducer's in-memory
    // draft and the request; it is never logged and never placed in a UI text.

    /**
     * P7-06 06.B (D-177): runs the export off the UI thread. The wiring/field guard runs BEFORE the
     * reducer marks the surface running (the P704C-QUAL-04 precedent): an unwired use case, a
     * missing active generation or a too-short password never strands the surface. The request is
     * resolved for the CURRENT active generation and its generation captured for the landing
     * discard.
     *
     * CANCEL SCOPE (P1-C, 06.B review; ruled by the main agent): the 06.B cancel deliverable is
     * satisfied at the USE-CASE level. [BackupExportUseCase.export] accepts a
     * `BackupCancellationSignal` and checks it per 64 KiB chunk (spec section 5), and its bounded
     * cancel behavior is tested; that is the spec's cancel requirement. A USER-FACING cancel control
     * is NOT a 06.B deliverable and is DEFERRED: the only product call site passes no signal (the
     * default `{ false }` applies), and wiring a cancel affordance that is legal while running would
     * interact with this surface's frozen "提交中不得离开" discipline and the SAF latch, so it needs
     * product authority. Consequence of the deferral, registered here: the surface absorbs Close/Back
     * while running, and the Android SAF port blocks the export thread on a latch of up to 10 minutes
     * (AndroidBackupTargetPort.ANDROID_BACKUP_TARGET_WAIT_MILLIS) if the user never answers the
     * picker, so an unanswered picker holds the operation lease and leaves the surface `running` with
     * no user cancel and no exit for up to that long; the export does complete when the picker is
     * answered or the latch times out (a timeout is a cancelled choice, not a crash). This is a
     * deferred UI affordance, not a missing spec obligation.
     */
    fun confirmBackupExport(current: P503AppState.BackupExport) {
        if (!ledger.surfaces.backupExport) return
        if (!backupExportConfirmEnabled(current.password, current.running)) return
        val launch = ledger.backupExportLaunch(current.password) ?: return
        val useCase = ledger.backupExport ?: return
        // Mark the surface running through the reducer (the single-flight marker) before the work
        // starts, so a second confirm is absorbed by the reducer even before the coroutine lands.
        if (latestState.value !== current) return
        dispatch(P503UiEvent.ConfirmBackupExport)
        // P2-E/P2-G fix (06.B review): the whole run/land pipeline is the testable
        // [runBackupExport] (off-thread export, main-thread landing hop, generation discard and the
        // throw -> typed-Failed mapping). This call site only supplies the production collaborators.
        runBackupExport(
            scope = scope,
            generation = launch.generation,
            request = launch.request,
            export = useCase::export,
            isCurrentGeneration = ledger::isCurrentGeneration,
            land = { result -> dispatch(P503UiEvent.BackupExportResultLanded(result)) },
        )
    }

    // ---------------------------------------------------------------- P7-06 06.D restore confirm & switch host surface
    // P7-06 06.D (D-182; spec sections 3/5.5/6): the host call sites of the restore flow, mirroring
    // the export surface's decisions: the work runs OFF the UI thread (container-format spec
    // section 4.8 — the preflight's KDF/streaming crypto and the confirm's quiesce/staging must
    // never touch the UI thread), the typed result hops back ON the composition's main dispatcher
    // (the P704C-SPEC-01/QUAL-02 serial-write rule), and a result that lands after the graph was
    // replaced is DISCARDED. The use cases acquire/release their own leases (the confirm holds
    // none by design), so neither call is wrapped in `ledger.leased`.
    //
    // Secret discipline: the password lives only in the reducer's in-memory draft and the request;
    // it is never logged and never placed in a UI text. The token is opaque; the host never opens
    // it (its internals are module-internal).

    /**
     * P7-06 06.D (spec section 3.1/3.2): runs the preflight for the typed request resolved for the
     * CURRENT active generation. The wiring/field guard runs BEFORE the reducer marks the surface
     * running (the export precedent): an unwired use case, a missing active generation or a
     * too-short password never strands the surface.
     */
    fun confirmRestorePreflight(current: P503AppState.BackupRestore) {
        if (!ledger.surfaces.backupRestore) return
        if (!restorePreflightConfirmEnabled(current.password, current.runningPreflight, current.runningConfirm)) return
        val launch = ledger.restorePreflightLaunch(current.password) ?: return
        val useCase = ledger.restoreWiring?.preflight ?: return
        if (latestState.value !== current) return
        dispatch(P503UiEvent.ConfirmRestorePreflight)
        runRestorePreflight(
            scope = scope,
            generation = launch.generation,
            request = launch.request,
            preflight = useCase::preflight,
            isCurrentGeneration = ledger::isCurrentGeneration,
            land = { result -> dispatch(P503UiEvent.RestorePreflightLanded(result)) },
        )
    }

    /**
     * P7-06 06.D (spec section 3): THE explicit replace confirmation — calling [confirm] IS the
     * user's decision, so this gate requires the bound preview (the 7-field checkable summary) and
     * the opaque token from a landed [RestorePreflightResult.PreviewReady]. Nothing runs without it.
     */
    fun confirmRestoreSwitch(current: P503AppState.BackupRestore) {
        if (!ledger.surfaces.backupRestore) return
        if (!restoreSwitchConfirmEnabled(current)) return
        val confirm = ledger.restoreWiring?.confirm ?: return
        val token = current.token ?: return
        // Readiness precondition only: the switch needs a Ready owner (no active generation means
        // there is nothing to switch from). The landing guard no longer uses this capture — every
        // result now carries its own post-operation generation (F-1).
        ledger.activeGenerationForLanding() ?: return
        if (latestState.value !== current) return
        dispatch(P503UiEvent.ConfirmBackupRestoreSwitch)
        runRestoreConfirm(
            scope = scope,
            token = token,
            confirm = confirm::confirm,
            isCurrentGeneration = ledger::isCurrentGeneration,
            land = { result ->
                dispatch(P503UiEvent.RestoreSwitchResultLanded(result))
                // Committed closeout (spec section 3.9): the switch is durable and the new graph is
                // open and read back. The reducer already closed the surface to the preserved
                // overview; this triggers the authoritative refresh chain so the overview reloads
                // from the NEW generation (the old cached read models are gone with the old graph).
                if (result is BackupRestoreSwitchResult.Committed) {
                    refresh()
                    // P7-09 09.C (spec section 6.4; D-225 Q20): 换库 (the backup-restore switch) is
                    // one of the frozen merge-invalidation causes, so every widget is invalidated
                    // here too — the widget would otherwise keep the OLD ledger's amount until the
                    // next platform period or a manual refresh. Best-effort (the fan-out is
                    // non-authoritative; the generation guard already prevents a stale read from
                    // landing), and it never blocks the refresh above (QUAL-001).
                    invalidateWidgetsBestEffort(currentWidgetInvalidation)
                }
            },
        )
    }

    // ---------------------------------------------------------------- P7-05 lost-commit manual re-check
    // V-19 (D-173; the P7-02 D-126 R4 precedent): the in-session manual re-check of a lost
    // correction/void/restore commit. Each entry re-invokes the SAME snapshot-aware resolver the
    // lost commit used, but against the RETAINED snapshot captured at confirm time — the surfaces
    // keep their fields editable while submitting, so re-deriving from the live draft could produce
    // a different snapshot and a FALSE SnapshotConflict. The resolve runs OFF the UI thread, hops
    // back ON the main dispatcher and dispatches the existing result event on a determinate hit
    // (then the shared refresh chain) or the still-unknown marker event otherwise. It never
    // auto-retries and never mints a new requestId; the coordinator's single-flight guard drops a
    // concurrent double-fire.

    // Each of the three re-checks below releases the coordinator's single-flight marker on EVERY
    // path, exactly once: the marker is cleared in the LANDING hop's `finally` (after the landing
    // event is dispatched, so a rapid second tap cannot overlap two read-only resolves of the same
    // surface), and in the outer coroutine's `catch` when the resolve itself throws or the hop is
    // cancelled before the landing hop could start. Clearing in the outer `finally` alone would be
    // WRONG: the landing `scope.launch` is a fire-and-forget child of the composition scope, so the
    // outer body completes (and its `finally` would run) before the landing hop dispatches. Without
    // the outer `catch`, a throwing/cancelled resolve would leave `p705RecheckInFlight` stuck `true`
    // for the rest of the session, silently dropping every later 重新核对 on all three surfaces.

    /** V-19 (D-173): the manual re-check of a lost correction commit (the retained snapshot). */
    fun recheckCorrectionCommitStatus() {
        val retained = retainedP705Request as? RetainedP705Request.Correction ?: return
        if (!ledger.surfaces.correction) return
        coordinator.recheckP705CommitOnce {
            scope.launch(Dispatchers.Default) {
                // P7-06 06.1 (D-176; spec 4.3/4.4): the read-only resolve runs under a lease; the
                // captured generation gates the landing. The outer catch keeps the V-19
                // single-flight marker releasable if the resolve itself throws.
                val outcome =
                    try {
                        ledger.leased { facade, _ ->
                            val resolver = facade.resolveCorrectionCommitStatus ?: return@leased null
                            correctionRecheckEvent(retained) { ledgerId, requestId, snapshot ->
                                resolver.resolve(ledgerId, requestId, snapshot)
                            }
                        }
                    } catch (failure: Exception) {
                        coordinator.p705RecheckCompleted()
                        throw failure
                    }
                scope.launch {
                    try {
                        if (outcome !is LeaseOutcome.Completed || !ledger.isCurrentGeneration(outcome.generation)) return@launch
                        val event = outcome.value ?: return@launch
                        dispatch(event)
                        refreshAfterP705Recheck(event, coordinator)
                    } finally {
                        coordinator.p705RecheckCompleted()
                    }
                }
            }
        }
    }

    /** V-19 (D-173): the manual re-check of a lost void commit (the retained snapshot). */
    fun recheckVoidCommitStatus() {
        val retained = retainedP705Request as? RetainedP705Request.VoidOrRestore ?: return
        // V-19 (D-173): the symmetric twin of recheckRestoreCommitStatus's guard — this entry is the
        // VOID one, so it is safe independent of its call site (which already checks the factKind).
        if (retained.snapshot.factKind != TransactionVoidFactKind.VOID) return
        if (!ledger.surfaces.voidTransaction) return
        coordinator.recheckP705CommitOnce {
            scope.launch(Dispatchers.Default) {
                val outcome =
                    try {
                        ledger.leased { facade, _ ->
                            val resolver = facade.resolveVoidCommitStatus ?: return@leased null
                            voidRestoreRecheckEvent(retained) { ledgerId, requestId, snapshot ->
                                resolver.resolve(ledgerId, requestId, snapshot)
                            }
                        }
                    } catch (failure: Exception) {
                        coordinator.p705RecheckCompleted()
                        throw failure
                    }
                scope.launch {
                    try {
                        if (outcome !is LeaseOutcome.Completed || !ledger.isCurrentGeneration(outcome.generation)) return@launch
                        val event = outcome.value ?: return@launch
                        dispatch(event)
                        refreshAfterP705Recheck(event, coordinator)
                    } finally {
                        coordinator.p705RecheckCompleted()
                    }
                }
            }
        }
    }

    /** V-19 (D-173): the manual re-check of a lost restore commit (the retained snapshot). */
    fun recheckRestoreCommitStatus() {
        val retained = retainedP705Request as? RetainedP705Request.VoidOrRestore ?: return
        if (retained.snapshot.factKind != TransactionVoidFactKind.RESTORE) return
        if (!ledger.surfaces.restore) return
        coordinator.recheckP705CommitOnce {
            scope.launch(Dispatchers.Default) {
                val outcome =
                    try {
                        ledger.leased { facade, _ ->
                            val resolver = facade.resolveVoidCommitStatus ?: return@leased null
                            voidRestoreRecheckEvent(retained) { ledgerId, requestId, snapshot ->
                                resolver.resolve(ledgerId, requestId, snapshot)
                            }
                        }
                    } catch (failure: Exception) {
                        coordinator.p705RecheckCompleted()
                        throw failure
                    }
                scope.launch {
                    try {
                        if (outcome !is LeaseOutcome.Completed || !ledger.isCurrentGeneration(outcome.generation)) return@launch
                        val event = outcome.value ?: return@launch
                        dispatch(event)
                        refreshAfterP705Recheck(event, coordinator)
                    } finally {
                        coordinator.p705RecheckCompleted()
                    }
                }
            }
        }
    }

    P503Theme {
        // P7-09 09.0 (spec sections 2.2/3; D-225 Q19): the visible Busy/待处理 strip for a held
        // inbound request and for a blocked host operation. It renders ABOVE the whole surface
        // stack (not only the overview), so a request held while the user is Editing / Awaiting
        // confirmation / Submitting / in an import batch stays VISIBLE on that very surface (the
        // frozen 可见 Busy rule). The copy carries no amount, URI or file name, and the strip never
        // blocks the underlying surface (a held request is a routing decision, not a modal).
        Column(Modifier.fillMaxSize()) {
            // P7-09 09.A fix round (B2; spec section 2.2 line 106): render the EFFECTIVE held request
            // — the reducer's slot when the surface carries one, else the host fallback hold for a
            // surface the reducer cannot represent the slot on (a read-only detail, the recycle bin, a
            // restore-confirm quiesce). Either way an arriving request stays VISIBLE, never a silent
            // discard.
            visibleHeldNavigation()?.let { pending ->
                // P7-09 09.A closed loop (spec section 2.3): while the held request is a share
                // awaiting its format, the strip offers the EXISTING capability-matrix formats. The
                // selection runs the closed loop (take the retained stream → the existing pipeline);
                // any other held request renders no format choices.
                val awaitingShare = pending.awaitsShareImportFormat()
                val share = pending.request as? ExternalNavigationRequest.OpenImport
                val sharePlatform = ledger.importPlatformKind
                ExternalNavigationBusyStrip(
                    busy = ExternalNavigationBusy(pending.holdReason),
                    onKeepCurrent = { dispatch(P503UiEvent.ExternalNavigationKeptCurrent) },
                    onRetain = if (pending.awaitingUserDecision) ({ dispatch(P503UiEvent.ExternalNavigationRetained) }) else null,
                    onReplace = if (pending.awaitingUserDecision) ({ dispatch(P503UiEvent.ExternalNavigationReplaced) }) else null,
                    onDiscard = { dispatch(P503UiEvent.ExternalNavigationDiscarded) },
                    formatChoices = if (awaitingShare && sharePlatform != null) importFormatEntries(sharePlatform) else emptyList(),
                    onSelectFormat = if (awaitingShare && share != null) ({ format -> selectShareImportFormat(share, format) }) else null,
                )
            }
            // P2-2 fix: the T4 Busy strip renders on EVERY slot surface (not only the overview), so
            // a refused second pick is visible wherever the user is — including the Ready entry
            // surface, where a cold-start share's Busy must not be silently absorbed.
            state.externalNavigationBusyOrNull()?.let { busy ->
                ExternalNavigationBusyStrip(
                    busy = busy,
                    onKeepCurrent = { dispatch(P503UiEvent.DismissExternalNavigationBusy) },
                    onRetain = null,
                    onReplace = null,
                    onDiscard = null,
                )
            }
            // ACC-D-SHARE-02 (spec section 2.3): the refused-share notice renders as a SIBLING of the
            // surface content, so it is visible on every state — including the ones whose class carries
            // no Busy field. Dismissing it drops the notice; nothing else is touched.
            hostShareRejection?.let { reason ->
                ExternalNavigationBusyStrip(
                    busy = ExternalNavigationBusy(ExternalNavigationHoldReason.IMPORT_REJECTED, reason),
                    onKeepCurrent = { hostShareRejection = null },
                    onRetain = null,
                    onReplace = null,
                    onDiscard = null,
                    dismissLabel = "知道了",
                )
            }
            Box(Modifier.weight(1f)) {
                when (val current = state) {
                    is P503AppState.Ready -> P503StartupScreen(P503StartupState.Starting, onRetry = {}, onExit = onExit)
                    is P503AppState.OverviewEmpty ->
                        P503TabShell(
                            selectedTab = current.selectedTab,
                            onSelectTab = { tab ->
                                if (tab == P503Tab.ACCOUNTS) {
                                    dispatch(P503UiEvent.SelectTab(tab, pinnedCatalogSnapshot()))
                                } else {
                                    dispatch(P503UiEvent.SelectTab(tab))
                                }
                                // P7-04.C: the first IMPORT entry requests the review list (the
                                // projection starts null; the refresh button re-requests later loads).
                                if (tab == P503Tab.IMPORT && (latestState.value as? P503AppState.OverviewEmpty)?.importReview == null) {
                                    requestImportReview()
                                }
                            },
                            onStartNewExpense = { dispatch(P503UiEvent.StartNewExpense) },
                            // P7-02.D E-2: the "record again" affordance is available on the success home
                            // page; the revalidation view is snapshotted from the current authoritative
                            // options at click time.
                            onSaveAndRecordAgain =
                                if (current.selectedTab == P503Tab.HOME && current.retainedIntent != null) {
                                    { dispatch(P503UiEvent.SaveAndRecordAgain(retainedIntentRevalidation())) }
                                } else {
                                    null
                                },
                        ) {
                            when (current.selectedTab) {
                                P503Tab.HOME ->
                                    P503OverviewScreen(
                                        state = current.state,
                                        showMonthlyRegion = ledgerViewWired,
                                        selectedMonth = current.selectedMonth,
                                        resolvedCurrentMonth = resolvedCurrentMonth,
                                        selectableMonths = current.selectableMonths,
                                        monthlyActivity = current.monthlyActivity,
                                        monthlyReloadRequired = current.monthlyReloadRequired,
                                        flowRows = flowRows,
                                        onLoadMore = ::requestNextFlowPage,
                                        onRetryLoadMore = ::requestNextFlowPage,
                                        onSelectTransaction = ::selectTransaction,
                                        onSelectMonth = ::selectMonth,
                                        // P7-05.C (D-156; slice 1b Piece 4): the ledger-wide recycle-bin
                                        // entry, offered only when the read surface is wired (an unwired
                                        // facade renders no dead button). Opening always reads the bin
                                        // fresh off the UI thread; the read dispatches OpenRecycleBin (a
                                        // re-open in place refreshes the projection instead).
                                        onOpenRecycleBin = if (ledger.surfaces.recycleBin) ::requestRecycleBin else null,
                                        // P7-06 06.B (D-177; spec section 3): the ledger-wide backup-export
                                        // entry, offered only when the composition root wired the export
                                        // use case (an unwired composition renders no dead button). The
                                        // open performs no IO and no secret handling; the password is
                                        // entered on the surface and stays in memory.
                                        onOpenBackupExport = if (backupExportEntryVisible(ledger.surfaces.backupExport)) ({ dispatch(P503UiEvent.OpenBackupExport) }) else null,
                                        // P7-06 06.D (D-182): the restore entry, offered only when the
                                        // restore use cases are wired (no dead affordance).
                                        onOpenBackupRestore =
                                            if (backupRestoreEntryVisible(ledger.surfaces.backupRestore)) {
                                                ({ dispatch(P503UiEvent.OpenBackupRestore) })
                                            } else {
                                                null
                                            },
                                    )
                                P503Tab.ACCOUNTS ->
                                    P503CatalogManagementScreen(
                                        state = current,
                                        // P7-08 08.C: the same cached authority feeds the two new
                                        // sections (null = loading/unwired: honest 载入中 placeholder).
                                        tagMerchantAuthority = cachedTagMerchantAuthority,
                                        onEvent = { event ->
                                            when (event) {
                                                is P503UiEvent.ManageAccountActive,
                                                is P503UiEvent.ManageCategoryActive,
                                                is P503UiEvent.EnableCategoryGroup,
                                                -> runCatalogToggle(event)
                                                // P7-08 08.C: the tag/merchant toggles are host-handled
                                                // too (command + authority reload + filter re-request).
                                                is P503UiEvent.ManageTagActive ->
                                                    runTagMerchantToggle(com.unifiedledger.domain.CatalogItemKind.TAG, event.itemId, event.active)
                                                is P503UiEvent.ManageMerchantActive ->
                                                    runTagMerchantToggle(com.unifiedledger.domain.CatalogItemKind.MERCHANT, event.itemId, event.active)
                                                // P7-02.D E-4: persist the toggle first, then dispatch.
                                                is P503UiEvent.TogglePin -> runPinToggle(event.target)
                                                else -> dispatch(event)
                                            }
                                        },
                                        onSubmit = { dialog ->
                                            when (dialog) {
                                                is CatalogDialog.CreateTagItem,
                                                is CatalogDialog.RenameTagItem,
                                                is CatalogDialog.ConfirmTagItemDelete,
                                                is CatalogDialog.CreateMerchantItem,
                                                is CatalogDialog.RenameMerchantItem,
                                                is CatalogDialog.ConfirmMerchantItemDelete,
                                                -> runTagMerchantForm(dialog)
                                                else -> runCatalogForm(dialog)
                                            }
                                        },
                                        onRefresh = { refreshCatalogSnapshot() },
                                    )
                                P503Tab.ANALYSIS -> {
                                    // P7-07 07.D: the budget region slot — non-null exactly when the
                                    // budget surface is wired (no dead region). It owns its own loading/
                                    // failure copy and its config affordances; the retained read-failure
                                    // render below deliberately passes none (read-only, the F1 rule).
                                    // P7-08 08.C: the filter entry rides the analysis tab's existing
                                    // composable-slot seam (the budget region slot) — the entry button
                                    // precedes the budget region inside the slot, and the slot is
                                    // non-null when EITHER surface is wired (no dead affordance).
                                    val budgetRegionSlot: (@Composable () -> Unit)? =
                                        if (budgetWired || tagMerchantFilterWired) {
                                            {
                                                if (tagMerchantFilterWired) {
                                                    TextButton(onClick = ::openTagMerchantFilter) {
                                                        Text("标签与商家筛选")
                                                    }
                                                }
                                                if (budgetWired) {
                                                    P503BudgetRegion(
                                                        view = current.budgetView,
                                                        categoryName = { id -> budgetCategoryNames[id] },
                                                        parentIdOf = { id -> budgetCategoryParents[id] },
                                                        pickerCategories = budgetPickerChoices,
                                                        onOpenBudgetConfig = ::openBudgetConfig,
                                                    )
                                                }
                                            }
                                        } else {
                                            null
                                        }
                                    P503AnalysisScreen(
                                        state = current.state,
                                        summarizeActivity = ledger.summarizeActivity,
                                        showMonthlyRegion = ledgerViewWired,
                                        selectedMonth = current.selectedMonth,
                                        resolvedCurrentMonth = resolvedCurrentMonth,
                                        selectableMonths = current.selectableMonths,
                                        monthlyActivity = current.monthlyActivity,
                                        monthlyReloadRequired = current.monthlyReloadRequired,
                                        trend = monthlyTrend,
                                        onSelectMonth = ::selectMonth,
                                        onAnalysisMonthShift = ::analysisMonthShift,
                                        budgetRegion = budgetRegionSlot,
                                    )
                                }
                                // P7-04.C: the IMPORT tab content — the review projection, the matrix
                                // format entries, the session summary and the group disposition surface.
                                // P7-04.D: the batch confirmation entry and the retained result summary
                                // (with the Unknown items' check entries) render on the same projection.
                                P503Tab.IMPORT ->
                                    P503ImportScreen(
                                        view = current.importReview,
                                        platform = ledger.importPlatformKind,
                                        onRefresh = ::requestImportReview,
                                        onStartFilePick = { format -> startImportFilePick(format) },
                                        onSelectCandidate = ::selectImportCandidate,
                                        onToggleSelection = { candidateId ->
                                            dispatch(P503UiEvent.ToggleImportCandidateSelection(candidateId))
                                        },
                                        onGroupDisposition = ::startImportDuplicateGroupDisposition,
                                        onGroupConfirm = ::confirmImportDuplicateGroupDisposition,
                                        onGroupClose = { dispatch(P503UiEvent.CloseImportDuplicateGroupDisposition) },
                                        onRequestBatchConfirm = ::requestImportBatchConfirm,
                                        onCheckUnknownItem = ::checkImportUnknownItem,
                                    )
                            }
                        }
                    is P503AppState.TransactionDetail -> {
                        val detail = current.detail
                        P503TransactionDetailScreen(
                            detail = detail,
                            // Back = CloseTransactionDetail semantics (tab/month preserved, C03).
                            onClose = { if (isBackDispatchSafe(latestState.value)) dispatch(P503UiEvent.Back) },
                            // P7-05 (D-156; spec section 3.5): the correction/void entries. The screen's
                            // affordance gate treats a non-null callback as "the host resolved this entry",
                            // so the host passes the callback ONLY when it can actually resolve the target
                            // now — the detail is a Success AND the correction origin resolves against the
                            // current catalog (an unwired facade, a still-loading catalog or an
                            // unresolvable amount leg passes `null`, so the page renders no dead button,
                            // the F1 precedent). The void entry needs only the wired use case.
                            onEditTransaction =
                                if (detail is TransactionDetailResult.Success && correctionOriginResolvable(detail.detail)) {
                                    { target -> openTransactionEdit(target) }
                                } else {
                                    null
                                },
                            onVoidTransaction = if (ledger.surfaces.voidTransaction) ({ target -> openVoidConfirm(target) }) else null,
                            // P7-08 08.B-2 (spec section 4.4): the annotation-edit entry, passed only when
                            // the host can resolve the target's CAS tokens right now (wired command + id
                            // source + read port AND a readable transaction); otherwise `null` renders no
                            // button rather than a form with fabricated tokens.
                            onEditAnnotation =
                                if (detail is TransactionDetailResult.Success && annotationEditResolvable(detail.detail)) {
                                    { target -> openTransactionAnnotationEdit(target) }
                                } else {
                                    null
                                },
                            // P7-08 08.D (spec section 3.5): the read-only annotation history read under the
                            // same lease as the detail, plus the current-name resolver for the stable tag ids
                            // (spec section 2.2: rename keeps the id and shows the current name).
                            annotationHistory = current.annotationHistory,
                            tagNameOf = { id ->
                                cachedTagMerchantAuthority?.tags?.firstOrNull { it.id == id }?.name ?: id
                            },
                        )
                    }
                    // P7-04.C: the import candidate detail (spec sections 6.1/6.2). The catalog options
                    // are the same authoritative projections the entry flow consumes (D-143 同源).
                    is P503AppState.ImportCandidateDetail ->
                        P503ImportCandidateDetailScreen(
                            state = current,
                            defaultCurrency = ledger.currency,
                            validation = importDecisionValidation,
                            // A-PERF (spec section 2.3): the detail screen's catalog accounts read the
                            // CACHED snapshot (the previous direct facade read ran on the main thread
                            // during every detail recomposition); `catalogLoading` carries the honest
                            // null window so the decision form presents the placeholder instead of an
                            // empty authoritative catalog (S2-2).
                            catalogAccounts = cachedCatalogSnapshot?.manageableAccounts ?: emptyList(),
                            catalogLoading = cachedCatalogSnapshot == null,
                            expenseCategories = options.expenseCategories,
                            // FIX-INCOME-FACE-1 (D-154): the income-side options for the decision face's
                            // direction-matched 分类 block (in-direction candidates); same authoritative
                            // projection the entry income flow consumes, pin-sorted like the expense list.
                            // The direction itself lives on the candidate detail row the screen already
                            // holds, so only the options cross this call site.
                            incomeCategories = incomeOptions.incomeCategories,
                            onUpdateDecisionField = { update -> dispatch(P503UiEvent.UpdateImportDecisionField(update)) },
                            onToggleSelection = { candidateId ->
                                dispatch(P503UiEvent.ToggleImportCandidateSelection(candidateId))
                            },
                            onSubmitDuplicateReview = { decision ->
                                // P704C-QUAL-04 call-site guard: the wiring/target precondition runs
                                // BEFORE the reducer marks the detail reviewPending — with an unwired
                                // facade the event must never be dispatched, or the page would be
                                // stranded in 审核提交中 forever. The guard mints one id triple that is
                                // then discarded (fresh per intent, nothing persisted); the submit path
                                // mints its own.
                                val current = latestState.value as? P503AppState.ImportCandidateDetail
                                val reviewable = current != null && importDuplicateReviewTarget(current.duplicates) != null
                                val wired = current != null && ledger.surfaces.importDuplicateReview && ledger.importDuplicateReviewIds() != null
                                if (reviewable && wired) {
                                    dispatchCurrentP503Action(
                                        current,
                                        latestState.value,
                                        { P503UiEvent.SubmitImportDuplicateReview(decision, IMPORT_DUPLICATE_REVIEW_REASON_TOKEN) },
                                        ::dispatch,
                                    ) {
                                        submitImportDuplicateReview(decision)
                                    }
                                }
                            },
                            // P7-04.D: the detail's 批量确认 entry (afforded only for a non-empty
                            // selection; the reducer effect carries the detail's draft into the page).
                            onRequestBatchConfirm = ::requestImportBatchConfirm,
                            // Back = close semantics (SPEC:281: 返回 OverviewEmpty(IMPORT) 保留清单/勾选集).
                            onClose = { if (isBackDispatchSafe(latestState.value)) dispatch(P503UiEvent.Back) },
                        )
                    // P7-04.D: the 授权快照确认页. The wiring guard runs inside the authorize action, so
                    // an unwired surface never strands the page in a dispatch state it cannot leave.
                    is P503AppState.ImportBatchConfirm ->
                        P503ImportBatchConfirmScreen(
                            state = current,
                            parseAmount = ledger.parseAmount,
                            defaultCurrency = ledger.currency,
                            onAuthorize = ::authorizeImportBatch,
                            // Back = cancel semantics (→ 原 OverviewEmpty， 保留勾选集与清单).
                            onCancel = { if (isBackDispatchSafe(latestState.value)) dispatch(P503UiEvent.Back) },
                        )
                    // P7-04.D: the per-item dispatch state. System back is intercepted and swallowed
                    // (沿 Submitting 语义)； the only exits are the explicit Resume/Abandon affordances
                    // (rendered in the paused sub-state AND the residual stopped sub-state — a cleanly
                    // finished resumed run with an Unknown item remaining, [importBatchExitAvailable],
                    // P704D-SPEC-01), plus the Unknown items' check actions.
                    is P503AppState.ImportBatchSubmitting ->
                        P503ImportBatchSubmittingScreen(
                            state = current,
                            onResume = ::resumeImportBatchDispatch,
                            onAbandon = ::abandonImportBatch,
                            onCheckUnknownItem = ::checkImportUnknownItem,
                        )
                    // P7-05 (D-156; slice 1b Piece 3): the correction/void/recycle-bin screens. The screens take
                    // callbacks; this piece wires only the pure navigation/draft events the state machine
                    // already owns (form writes, the preview request, opening the nested restore page, the
                    // back channel). The host call sites that run the use cases (the correction/void/restore
                    // commits and the recycle-bin read) are Piece 4, so the commit callbacks stay `null` and
                    // the confirm affordances render disabled rather than dead.
                    is P503AppState.TransactionEdit ->
                        P503TransactionEditScreen(
                            state = current,
                            // P7-05.B (slice 1b Piece 4): the preview and the commit amount parse at the
                            // transaction's OWN currency (the Piece 3 residual is retired); the ledger
                            // default remains only the fallback for a legacy origin without one.
                            currency = current.origin.currency ?: ledger.currency,
                            categoryNames = cachedCatalogSnapshot?.correctionCategoryNames() ?: emptyMap(),
                            accountNames = cachedCatalogSnapshot?.correctionAccountNames() ?: emptyMap(),
                            categoryOptions =
                                cachedCatalogSnapshot?.let { correctionCategoryOptions(it, current.origin.categoryId) } ?: emptyList(),
                            accountOptions = cachedCatalogSnapshot?.let { correctionAccountOptions(it) } ?: emptyList(),
                            onUpdateField = { update -> dispatch(P503UiEvent.UpdateTransactionCorrectionField(update)) },
                            onPreview = { dispatch(P503UiEvent.PreviewTransactionEdit) },
                            onConfirm = { confirmTransactionCorrection(current) },
                            onCancel = { if (isBackDispatchSafe(latestState.value)) dispatch(P503UiEvent.Back) },
                            // V-19 (D-173): the manual re-check is offered only while a commit is in flight
                            // AND the host still holds the retained snapshot for this surface; a `null`
                            // callback renders no button (the file's "no dead affordance" pattern).
                            onRecheck =
                                if (current.submitting && retainedP705Request is RetainedP705Request.Correction) {
                                    { recheckCorrectionCommitStatus() }
                                } else {
                                    null
                                },
                        )
                    // P7-05 (D-228; transfer spec section 3): the transfer correction surface. Its
                    // preview is a render-time pure read over the catalog's display names (the reducer
                    // holds no catalog), so the screen receives the same projections as the ordinary
                    // one plus the fee-category options a positive fee requires.
                    is P503AppState.TransferEdit ->
                        P503TransferEditScreen(
                            state = current,
                            categoryNames = cachedCatalogSnapshot?.correctionCategoryNames() ?: emptyMap(),
                            accountNames = cachedCatalogSnapshot?.correctionAccountNames() ?: emptyMap(),
                            accountOptions = cachedCatalogSnapshot?.let { correctionAccountOptions(it) } ?: emptyList(),
                            feeCategoryOptions = cachedCatalogSnapshot?.let { transferFeeCategoryOptions(it) } ?: emptyList(),
                            onUpdateField = { update -> dispatch(P503UiEvent.UpdateTransferCorrectionField(update)) },
                            onConfirm = { confirmTransferCorrection(current) },
                            onCancel = { if (isBackDispatchSafe(latestState.value)) dispatch(P503UiEvent.Back) },
                            onRecheck =
                                if (current.submitting && retainedP705Request is RetainedP705Request.Correction) {
                                    { recheckCorrectionCommitStatus() }
                                } else {
                                    null
                                },
                        )
                    // P7-08 08.B-2 (spec section 4.4): the annotation-edit surface. The selectable options
                    // come from the facade's already-filtered projection. A null projection (not yet loaded,
                    // or an unwired root) is passed through as null so the screen presents an honest
                    // 载入中 placeholder instead of an authoritative-looking empty catalog (the S2-2
                    // discipline); the confirm callback is null until the command is wired.
                    is P503AppState.TransactionAnnotationEdit -> {
                        val selection = cachedTagMerchantSelection
                        P503TransactionAnnotationEditScreen(
                            state = current,
                            tagOptions = selection?.tags,
                            merchantOptions = selection?.merchants,
                            onToggleTag = { tagId ->
                                val next = current.intent.tagIds.toMutableSet()
                                if (!next.add(tagId)) next.remove(tagId)
                                dispatch(P503UiEvent.UpdateAnnotationTagSelection(next))
                            },
                            onSelectMerchant = { merchantId -> dispatch(P503UiEvent.UpdateAnnotationMerchant(merchantId)) },
                            onConfirm =
                                if (annotationEditWired) {
                                    { confirmTransactionAnnotationEdit(current) }
                                } else {
                                    null
                                },
                            onCancel = { if (isBackDispatchSafe(latestState.value)) dispatch(P503UiEvent.Back) },
                        )
                    }
                    is P503AppState.VoidConfirm ->
                        P503VoidConfirmScreen(
                            state = current,
                            onUpdateReason = { update -> dispatch(P503UiEvent.UpdateVoidReasonField(update)) },
                            onConfirm = { confirmVoid(current) },
                            onCancel = { if (isBackDispatchSafe(latestState.value)) dispatch(P503UiEvent.Back) },
                            // V-19 (D-173): see the correction surface's re-check wiring.
                            onRecheck =
                                if (current.submitting && (retainedP705Request as? RetainedP705Request.VoidOrRestore)?.snapshot?.factKind == TransactionVoidFactKind.VOID) {
                                    { recheckVoidCommitStatus() }
                                } else {
                                    null
                                },
                        )
                    is P503AppState.RecycleBin -> {
                        val restore = current.restore
                        if (restore == null) {
                            P503RecycleBinScreen(
                                state = current,
                                onOpenRestore = { transactionId -> dispatch(P503UiEvent.OpenRestoreConfirm(transactionId)) },
                                onClose = { if (isBackDispatchSafe(latestState.value)) dispatch(P503UiEvent.Back) },
                            )
                        } else {
                            // The nested restore page shows the catalog revalidation result the bin row
                            // already carries (spec section 4.4); the rows are the query's frozen order.
                            val row = (current.rows as? RecycleBinResult.Success)?.rows?.firstOrNull { it.voided.transactionId == restore.transactionId }
                            P503RestoreConfirmScreen(
                                restore = restore,
                                row = row,
                                onUpdateReason = { update -> dispatch(P503UiEvent.UpdateRestoreReasonField(update)) },
                                onConfirm = { confirmRestore(current) },
                                onClose = { if (isBackDispatchSafe(latestState.value)) dispatch(P503UiEvent.CloseRestoreConfirm) },
                                // V-19 (D-173): see the correction surface's re-check wiring.
                                onRecheck =
                                    if (restore.submitting && (retainedP705Request as? RetainedP705Request.VoidOrRestore)?.snapshot?.factKind == TransactionVoidFactKind.RESTORE) {
                                        { recheckRestoreCommitStatus() }
                                    } else {
                                        null
                                    },
                            )
                        }
                    }
                    is P503AppState.BudgetConfig ->
                        P503BudgetConfigScreen(
                            current = current,
                            categoryName = { id -> budgetCategoryNames[id] },
                            currency = ledger.currency,
                            onLimitTextChange = { text -> dispatch(P503UiEvent.UpdateBudgetLimitText(text)) },
                            onConfirmLimit = { confirmBudgetLimit(current) },
                            onCloseMonitoring = { confirmBudgetClose(current) },
                            onDismiss = { dispatch(P503UiEvent.CloseBudgetConfig) },
                        )
                    // P7-08 08.C (spec sections 5.1/5.4): the filter surface. The option projections
                    // come from the cached authority — the filter's own rule (DISABLED included,
                    // tombstoned excluded, spec 5.1) applies HERE, on the same single authority load
                    // that feeds the editor's selection options and the management sections.
                    is P503AppState.TagMerchantFilter -> {
                        val authority = cachedTagMerchantAuthority
                        P503TagMerchantFilterScreen(
                            state = current,
                            tagOptions = authority?.tags?.let(::tagMerchantFilterTagOptions),
                            merchantOptions = authority?.merchants?.let(::tagMerchantFilterMerchantOptions),
                            onToggleTag = { tagId -> dispatchTagMerchantFilterChange(P503UiEvent.ToggleFilterTag(tagId)) },
                            onSetTagMatch = { mode -> dispatchTagMerchantFilterChange(P503UiEvent.SetFilterTagMatch(mode)) },
                            onSelectMerchant = { merchantId -> dispatchTagMerchantFilterChange(P503UiEvent.SetFilterMerchant(merchantId)) },
                            onLoadMore = {
                                val currentFilter = latestState.value as? P503AppState.TagMerchantFilter ?: return@P503TagMerchantFilterScreen
                                requestTagMerchantFilterPage(currentFilter, replace = false, cursor = currentFilter.nextCursor)
                            },
                            onShiftMonth = { delta ->
                                val currentFilter = latestState.value as? P503AppState.TagMerchantFilter ?: return@P503TagMerchantFilterScreen
                                val next =
                                    if (delta < 0) {
                                        currentFilter.accumulationMonth.minus(1, DateTimeUnit.MONTH)
                                    } else {
                                        currentFilter.accumulationMonth.plus(1, DateTimeUnit.MONTH)
                                    }
                                dispatchTagMerchantFilterChange(P503UiEvent.SetFilterAccumulationMonth(next))
                            },
                            onBack = { if (isBackDispatchSafe(latestState.value)) dispatch(P503UiEvent.Back) },
                        )
                    }
                    is P503AppState.BackupExport ->
                        P503BackupExportScreen(
                            state = current,
                            onUpdatePassword = { dispatch(P503UiEvent.UpdateBackupPassword(it)) },
                            // The host export call site is passed only when the use case is wired; an
                            // unwired composition renders the disabled confirm, never a dead button.
                            onConfirm = if (ledger.surfaces.backupExport) ({ confirmBackupExport(current) }) else null,
                            onCancel = { if (isBackDispatchSafe(latestState.value)) dispatch(P503UiEvent.CloseBackupExport) },
                        )
                    is P503AppState.BackupRestore ->
                        P503RestoreScreen(
                            state = current,
                            onUpdatePassword = { dispatch(P503UiEvent.UpdateRestorePassword(it)) },
                            // The host restore call sites are passed only when the use cases are wired; an
                            // unwired composition renders disabled confirms, never dead buttons.
                            onPreflight = if (ledger.surfaces.backupRestore) ({ confirmRestorePreflight(current) }) else null,
                            onConfirmSwitch = if (ledger.surfaces.backupRestore) ({ confirmRestoreSwitch(current) }) else null,
                            onCancel = { if (isBackDispatchSafe(latestState.value)) dispatch(P503UiEvent.CloseBackupRestore) },
                        )
                    is P503AppState.RestoreSessionTerminal ->
                        // P7-06 06.D: the session-terminal RecoveryRequired face - no business affordance,
                        // only the typed cause and the platform exit (wired directly, outside the reducer).
                        P503RestoreSessionTerminalScreen(current, onExit)
                    is P503AppState.Editing ->
                        P503EditScreen(
                            draft = current.draft,
                            options = options,
                            incomeOptions = incomeOptions,
                            transferOptions = transferOptions,
                            lendingOptions = lendingOptions,
                            onUpdateTransferSourceAccount = { dispatch(P503UiEvent.UpdateTransferSourceAccount(it)) },
                            onUpdateTransferDestinationAccount = { dispatch(P503UiEvent.UpdateTransferDestinationAccount(it)) },
                            onUpdateTransferDestinationCredit = { dispatch(P503UiEvent.UpdateTransferDestinationCredit(it)) },
                            onUpdateTransferFee = { dispatch(P503UiEvent.UpdateTransferFee(it)) },
                            onUpdateTransferFeeCategory = { dispatch(P503UiEvent.UpdateTransferFeeCategory(it)) },
                            onUpdateLendCounterparty = { dispatch(P503UiEvent.UpdateLendCounterparty(it)) },
                            onUpdateLendFundingAccount = { dispatch(P503UiEvent.UpdateLendFundingAccount(it)) },
                            onUpdateLendAmount = { dispatch(P503UiEvent.UpdateLendAmount(it)) },
                            onUpdateCollectCounterparty = { dispatch(P503UiEvent.UpdateCollectCounterparty(it)) },
                            onUpdateCollectDestinationAccount = { dispatch(P503UiEvent.UpdateCollectDestinationAccount(it)) },
                            onUpdateCollectTotal = { dispatch(P503UiEvent.UpdateCollectTotal(it)) },
                            onUpdateCollectPrincipal = { dispatch(P503UiEvent.UpdateCollectPrincipal(it)) },
                            onUpdateCollectInterest = { dispatch(P503UiEvent.UpdateCollectInterest(it)) },
                            onUpdateCollectInterestCategory = { dispatch(P503UiEvent.UpdateCollectInterestCategory(it)) },
                            validation = validation,
                            currency = resolvedCurrency(current.draft),
                            ledgerClock = ledger.ledgerClock,
                            parseOccurredAt = ledger.parseOccurredAt,
                            onUpdateAmount = { dispatch(P503UiEvent.UpdateAmount(it)) },
                            onUpdatePaymentAccount = { dispatch(P503UiEvent.UpdatePaymentAccount(it)) },
                            onUpdateCategory = { dispatch(P503UiEvent.UpdateCategory(it)) },
                            onUpdateOccurredAt = { dispatch(P503UiEvent.UpdateOccurredAt(it)) },
                            onSelectEntryType = { dispatch(P503UiEvent.SelectEntryType(it)) },
                            onUpdateNote = { dispatch(P503UiEvent.UpdateNote(it)) },
                            // P7-08 08.B-2 (spec section 4.1): the optional tag/merchant association.
                            // The options come from the cached projection (never a main-thread facade read).
                            tagMerchantOptions = cachedTagMerchantSelection,
                            onToggleTag = { tagId ->
                                val next = current.draft.tagIds.toMutableSet()
                                if (!next.add(tagId)) next.remove(tagId)
                                dispatch(P503UiEvent.UpdateTagSelection(next))
                            },
                            onSelectMerchant = { merchantId -> dispatch(P503UiEvent.UpdateMerchant(merchantId)) },
                            onUpdateReceivingAccount = { dispatch(P503UiEvent.UpdateReceivingAccount(it)) },
                            onUpdateIncomeCategory = { dispatch(P503UiEvent.UpdateIncomeCategory(it)) },
                            // P7-02.D E-3: the calculator lives on the editable screen only.
                            expressionPreview = current.expressionPreview,
                            onEvaluateExpression = { dispatch(P503UiEvent.EvaluateEntryExpression(it)) },
                            onApplyExpression = { dispatch(P503UiEvent.ApplyExpressionResult) },
                            // P702SPEC-03: the counterparty create/rename affordance.
                            counterpartyDialog = current.counterpartyDialog,
                            onCounterpartyEvent = { dispatch(it) },
                            onCounterpartyFormSubmit = { runCounterpartyForm(it) },
                            occurredAtText = hoistedOccurredAtText ?: (current.draft.occurredAt?.toString() ?: ""),
                            onOccurredAtTextChange = { hoistedOccurredAtText = it },
                            onContinue = {
                                dispatchCurrentP503Action(current, latestState.value, {
                                    // requestId single rule (spec 7.4): allocate unconditionally when the
                                    // draft has none, reuse it otherwise; never reallocate mid-intent.
                                    val requestId = current.requestId ?: ledger.requestIdSource.next()
                                    P503UiEvent.Continue(
                                        requestId,
                                        // P5-04.3: display labels resolved from the options; absent
                                        // options fall back to the draft id values in the reducer.
                                        paymentAccountLabel = accountLabel(current.draft),
                                        categoryLabel = categoryLabel(current.draft),
                                        // A-02 FIX-CONFIRM-1: the type-owned confirmation labels.
                                        destinationAccountLabel = destinationAccountLabel(current.draft),
                                        counterpartyLabel = counterpartyLabel(current.draft),
                                    )
                                }, ::dispatch)
                            },
                            onClose =
                                if (current.overview != null) {
                                    { if (isBackDispatchSafe(latestState.value)) dispatch(P503UiEvent.Back) }
                                } else {
                                    null
                                },
                            onDialogVisibilityChanged = { editDialogOpen = it },
                        )
                    is P503AppState.AwaitingConfirmation ->
                        P503ConfirmationScreen(
                            draft = current.draft,
                            currencyCode = resolvedCurrency(current.draft).code,
                            currencyPrecision = resolvedCurrency(current.draft).precision,
                            labels =
                                ConfirmationLabels(
                                    paymentAccount = current.paymentAccountLabel,
                                    category = current.categoryLabel,
                                    destinationAccount = current.destinationAccountLabel,
                                    counterparty = current.counterpartyLabel,
                                ),
                            onCancel = { dispatchCurrentP503Action(current, latestState.value, { P503UiEvent.Cancel }, ::dispatch) },
                            confirmEnabled = latestState.value === current,
                            cancelEnabled = latestState.value === current,
                            onConfirm = {
                                dispatchCurrentP503Action(current, latestState.value, { P503UiEvent.Confirm }, ::dispatch) {
                                    submit(current.draft, current.requestId)
                                }
                            },
                        )
                    is P503AppState.Submitting -> P503SubmittingScreen()
                    P503AppState.Created,
                    P503AppState.NoChange,
                    P503AppState.Recovered,
                    -> P503ResultScreen(current)
                    is P503AppState.UnknownCommit ->
                        P503UnknownCommitScreen(current) {
                            // P5-04.3: manual re-check mirrors the RetrySubmission pattern — dispatch
                            // the state-preserving event and run the read-only check directly.
                            dispatchCurrentP503Action(current, latestState.value, { P503UiEvent.RetryCommitStatusCheck }, ::dispatch) {
                                coordinator.retryCommitStatusCheck(current)
                            }
                        }
                    is P503AppState.RequestIdentityConflict ->
                        P503EditScreen(
                            draft = current.draft,
                            options = options,
                            incomeOptions = incomeOptions,
                            transferOptions = transferOptions,
                            lendingOptions = lendingOptions,
                            onUpdateTransferSourceAccount = { dispatch(P503UiEvent.UpdateTransferSourceAccount(it)) },
                            onUpdateTransferDestinationAccount = { dispatch(P503UiEvent.UpdateTransferDestinationAccount(it)) },
                            onUpdateTransferDestinationCredit = { dispatch(P503UiEvent.UpdateTransferDestinationCredit(it)) },
                            onUpdateTransferFee = { dispatch(P503UiEvent.UpdateTransferFee(it)) },
                            onUpdateTransferFeeCategory = { dispatch(P503UiEvent.UpdateTransferFeeCategory(it)) },
                            onUpdateLendCounterparty = { dispatch(P503UiEvent.UpdateLendCounterparty(it)) },
                            onUpdateLendFundingAccount = { dispatch(P503UiEvent.UpdateLendFundingAccount(it)) },
                            onUpdateLendAmount = { dispatch(P503UiEvent.UpdateLendAmount(it)) },
                            onUpdateCollectCounterparty = { dispatch(P503UiEvent.UpdateCollectCounterparty(it)) },
                            onUpdateCollectDestinationAccount = { dispatch(P503UiEvent.UpdateCollectDestinationAccount(it)) },
                            onUpdateCollectTotal = { dispatch(P503UiEvent.UpdateCollectTotal(it)) },
                            onUpdateCollectPrincipal = { dispatch(P503UiEvent.UpdateCollectPrincipal(it)) },
                            onUpdateCollectInterest = { dispatch(P503UiEvent.UpdateCollectInterest(it)) },
                            onUpdateCollectInterestCategory = { dispatch(P503UiEvent.UpdateCollectInterestCategory(it)) },
                            validation = validation,
                            currency = resolvedCurrency(current.draft),
                            ledgerClock = ledger.ledgerClock,
                            parseOccurredAt = ledger.parseOccurredAt,
                            onUpdateAmount = { dispatch(P503UiEvent.UpdateAmount(it)) },
                            onUpdatePaymentAccount = { dispatch(P503UiEvent.UpdatePaymentAccount(it)) },
                            onUpdateCategory = { dispatch(P503UiEvent.UpdateCategory(it)) },
                            onUpdateOccurredAt = { dispatch(P503UiEvent.UpdateOccurredAt(it)) },
                            onSelectEntryType = { dispatch(P503UiEvent.SelectEntryType(it)) },
                            onUpdateNote = { dispatch(P503UiEvent.UpdateNote(it)) },
                            // P7-08 08.B-2 (spec section 4.1): the optional tag/merchant association.
                            // The options come from the cached projection (never a main-thread facade read).
                            tagMerchantOptions = cachedTagMerchantSelection,
                            onToggleTag = { tagId ->
                                val next = current.draft.tagIds.toMutableSet()
                                if (!next.add(tagId)) next.remove(tagId)
                                dispatch(P503UiEvent.UpdateTagSelection(next))
                            },
                            onSelectMerchant = { merchantId -> dispatch(P503UiEvent.UpdateMerchant(merchantId)) },
                            onUpdateReceivingAccount = { dispatch(P503UiEvent.UpdateReceivingAccount(it)) },
                            onUpdateIncomeCategory = { dispatch(P503UiEvent.UpdateIncomeCategory(it)) },
                            occurredAtText = hoistedOccurredAtText ?: (current.draft.occurredAt?.toString() ?: ""),
                            onOccurredAtTextChange = { hoistedOccurredAtText = it },
                            onContinue = null,
                            banner = {
                                P503ConflictBanner(
                                    onAbandonConflict = { dispatch(P503UiEvent.AbandonConflict) },
                                )
                            },
                            onClose =
                                if (current.overview != null) {
                                    { if (isBackDispatchSafe(latestState.value)) dispatch(P503UiEvent.Back) }
                                } else {
                                    null
                                },
                            onDialogVisibilityChanged = { editDialogOpen = it },
                        )
                    is P503AppState.DomainRejected ->
                        P503EditScreen(
                            draft = current.draft,
                            options = options,
                            incomeOptions = incomeOptions,
                            transferOptions = transferOptions,
                            lendingOptions = lendingOptions,
                            onUpdateTransferSourceAccount = { dispatch(P503UiEvent.UpdateTransferSourceAccount(it)) },
                            onUpdateTransferDestinationAccount = { dispatch(P503UiEvent.UpdateTransferDestinationAccount(it)) },
                            onUpdateTransferDestinationCredit = { dispatch(P503UiEvent.UpdateTransferDestinationCredit(it)) },
                            onUpdateTransferFee = { dispatch(P503UiEvent.UpdateTransferFee(it)) },
                            onUpdateTransferFeeCategory = { dispatch(P503UiEvent.UpdateTransferFeeCategory(it)) },
                            onUpdateLendCounterparty = { dispatch(P503UiEvent.UpdateLendCounterparty(it)) },
                            onUpdateLendFundingAccount = { dispatch(P503UiEvent.UpdateLendFundingAccount(it)) },
                            onUpdateLendAmount = { dispatch(P503UiEvent.UpdateLendAmount(it)) },
                            onUpdateCollectCounterparty = { dispatch(P503UiEvent.UpdateCollectCounterparty(it)) },
                            onUpdateCollectDestinationAccount = { dispatch(P503UiEvent.UpdateCollectDestinationAccount(it)) },
                            onUpdateCollectTotal = { dispatch(P503UiEvent.UpdateCollectTotal(it)) },
                            onUpdateCollectPrincipal = { dispatch(P503UiEvent.UpdateCollectPrincipal(it)) },
                            onUpdateCollectInterest = { dispatch(P503UiEvent.UpdateCollectInterest(it)) },
                            onUpdateCollectInterestCategory = { dispatch(P503UiEvent.UpdateCollectInterestCategory(it)) },
                            validation = validation,
                            currency = resolvedCurrency(current.draft),
                            ledgerClock = ledger.ledgerClock,
                            parseOccurredAt = ledger.parseOccurredAt,
                            onUpdateAmount = { dispatch(P503UiEvent.UpdateAmount(it)) },
                            onUpdatePaymentAccount = { dispatch(P503UiEvent.UpdatePaymentAccount(it)) },
                            onUpdateCategory = { dispatch(P503UiEvent.UpdateCategory(it)) },
                            onUpdateOccurredAt = { dispatch(P503UiEvent.UpdateOccurredAt(it)) },
                            onSelectEntryType = { dispatch(P503UiEvent.SelectEntryType(it)) },
                            onUpdateNote = { dispatch(P503UiEvent.UpdateNote(it)) },
                            // P7-08 08.B-2 (spec section 4.1): the optional tag/merchant association.
                            // The options come from the cached projection (never a main-thread facade read).
                            tagMerchantOptions = cachedTagMerchantSelection,
                            onToggleTag = { tagId ->
                                val next = current.draft.tagIds.toMutableSet()
                                if (!next.add(tagId)) next.remove(tagId)
                                dispatch(P503UiEvent.UpdateTagSelection(next))
                            },
                            onSelectMerchant = { merchantId -> dispatch(P503UiEvent.UpdateMerchant(merchantId)) },
                            onUpdateReceivingAccount = { dispatch(P503UiEvent.UpdateReceivingAccount(it)) },
                            onUpdateIncomeCategory = { dispatch(P503UiEvent.UpdateIncomeCategory(it)) },
                            occurredAtText = hoistedOccurredAtText ?: (current.draft.occurredAt?.toString() ?: ""),
                            onOccurredAtTextChange = { hoistedOccurredAtText = it },
                            onContinue = null,
                            banner = { P503RejectedBanner() },
                            onClose =
                                if (current.overview != null) {
                                    { if (isBackDispatchSafe(latestState.value)) dispatch(P503UiEvent.Back) }
                                } else {
                                    null
                                },
                            onDialogVisibilityChanged = { editDialogOpen = it },
                        )
                    is P503AppState.InfrastructureFailure ->
                        when (current.context) {
                            InfrastructureFailureContext.READ -> {
                                val retryRefresh = {
                                    dispatchCurrentP503Action(current, latestState.value, { P503UiEvent.RetryRefresh }, ::dispatch) {
                                        coordinator.retryRefresh(current)
                                    }
                                }
                                // F1 (spec 4.3/C04, table 6.2a): a monthly read failure keeps the already
                                // rendered month visible next to the explicit failure banner instead of
                                // replacing the whole HOME/ANALYSIS surface with the bare failure page.
                                // The retained surface is deliberately read-only: while the READ failure
                                // stands the reducer absorbs every interaction except the retry, so no
                                // affordance is left dead. `monthlyOverview == null` (every pre-P7-03 READ
                                // failure) keeps the bare recoverable page and its exact retry semantics.
                                val retained = retainedReadFailureOverview(current)
                                if (retained != null) {
                                    P503RetainedOverviewFailureScreen(
                                        overview = retained,
                                        showMonthlyRegion = ledgerViewWired,
                                        resolvedCurrentMonth = resolvedCurrentMonth,
                                        monthlyTrend = monthlyTrend,
                                        flowRows = flowRows,
                                        summarizeActivity = ledger.summarizeActivity,
                                        onRetryRefresh = retryRefresh,
                                    )
                                } else {
                                    P503InfrastructureReadScreen(onRetryRefresh = retryRefresh)
                                }
                            }
                            InfrastructureFailureContext.SUBMISSION ->
                                P503InfrastructureSubmissionScreen(
                                    onRetry = {
                                        // Defensive (P5-04.4 I-001): verify draft/requestId before
                                        // dispatching RetrySubmission, matching the baseline guard.
                                        // The reducer's SUBMISSION branch uses checkNotNull, so the
                                        // retry entry must not dispatch a degraded event.
                                        if (current.draft == null || current.requestId == null) {
                                            return@P503InfrastructureSubmissionScreen
                                        }
                                        dispatchCurrentP503Action(current, latestState.value, { P503UiEvent.RetrySubmission }, ::dispatch) {
                                            coordinator.retrySubmission(current)
                                        }
                                    },
                                    onCancel = { dispatchCurrentP503Action(current, latestState.value, { P503UiEvent.Cancel }, ::dispatch) },
                                )
                        }
                }
            }
        }
    }
}

/** P5-04.2: the editor flow intercepts system back only with an overview to close back to. */
private fun isBackEnabled(state: P503AppState): Boolean =
    when (state) {
        // P7-01.D: on the ACCOUNTS tab back closes an open catalog dialog, or leaves for HOME.
        is P503AppState.OverviewEmpty -> state.selectedTab == P503Tab.ACCOUNTS
        // P7-03.C: the read-only detail returns to the preserved overview (C03).
        is P503AppState.TransactionDetail -> true
        // P7-04.C (SPEC:281): the import candidate detail returns to the preserved IMPORT
        // overview (清单/勾选集保留； the draft is written back by the reducer on close).
        is P503AppState.ImportCandidateDetail -> true
        // P7-04.D (SPEC 6.2 back bullet): the confirm page returns to the preserved overview
        // (保留勾选集与清单).
        is P503AppState.ImportBatchConfirm -> true
        // P7-04.D: the dispatch state intercepts the back channel so the system gesture never
        // exits mid-batch — the enabled handler swallows it (沿既有 Submitting 语义) while
        // [isBackDispatchSafe] keeps `Back` from ever reaching the reducer here.
        is P503AppState.ImportBatchSubmitting -> true
        // P7-05 (D-156): the correction/void/recycle-bin surfaces return to their preserved
        // overview; while a commit is in flight the surface keeps its marker and must not leave
        // (提交中不得离开), so the back channel is intercepted and swallowed exactly like
        // [P503AppState.Submitting] (the enabled handler swallows it while [isBackDispatchSafe]
        // keeps `Back` from ever reaching the reducer).
        is P503AppState.TransactionEdit -> true
        is P503AppState.TransferEdit -> true
        is P503AppState.VoidConfirm -> true
        is P503AppState.RecycleBin -> true
        // P7-07 07.D (D-184): the budget config surface returns to its preserved overview; while
        // a commit is in flight it keeps its marker and must not leave (提交中不得离开).
        is P503AppState.BudgetConfig -> true
        // P7-08 08.C: the filter surface returns to its preserved overview (its reads are
        // absorbable, so there is no submitting marker to intercept).
        is P503AppState.TagMerchantFilter -> true
        // P7-06 06.B (D-177): the export surface returns to its preserved overview; while an export
        // is running it keeps its marker and must not leave (提交中不得离开), so the back channel is
        // intercepted and swallowed like the P7-05 submitting surfaces.
        is P503AppState.BackupExport -> true
        // P7-06 06.D (D-182): the restore surface behaves like the export surface. The
        // session-terminal RecoveryRequired face is deliberately NOT back-able: there is no
        // continuation to return to (the runtime is Closed/StartupError).
        is P503AppState.BackupRestore -> true
        is P503AppState.Editing -> state.overview != null
        is P503AppState.AwaitingConfirmation -> state.overview != null
        is P503AppState.Submitting -> state.overview != null
        is P503AppState.RequestIdentityConflict -> state.overview != null
        is P503AppState.DomainRejected -> state.overview != null
        is P503AppState.InfrastructureFailure ->
            state.context == InfrastructureFailureContext.SUBMISSION && state.overview != null
        else -> false
    }

/** P7-08 08.C: the frozen filter page size (the bounded read's UI-facing bound). */
private const val TAG_MERCHANT_FILTER_PAGE_SIZE = 50

/**
 * P5-04.3: dispatching Back is only legal from the Back-able states; Submitting swallows the
 * back and every other state (including the HOME overview root) must not dispatch one.
 * P7-04.D: the import batch dispatch state joins Submitting in swallowing the back — the only
 * exits are the explicit Resume/Abandon affordances (显式退出只经 AbandonImportBatch).
 */
private fun isBackDispatchSafe(state: P503AppState): Boolean =
    isBackEnabled(state) &&
        state !is P503AppState.Submitting &&
        state !is P503AppState.ImportBatchSubmitting &&
        // P7-05: a commit in flight keeps the surface (提交中不得离开); a non-submitting P7-05
        // surface dispatches `Back` (its reducer maps it to the preserved overview).
        !(state is P503AppState.TransactionEdit && state.submitting) &&
        !(state is P503AppState.TransferEdit && state.submitting) &&
        // P7-08 08.B-2: the same rule for the annotation-edit surface.
        !(state is P503AppState.TransactionAnnotationEdit && state.submitting) &&
        !(state is P503AppState.VoidConfirm && state.submitting) &&
        !(state is P503AppState.RecycleBin && state.restore?.submitting == true) &&
        // P7-07 07.D: a committing budget config keeps the surface (提交中不得离开).
        !(state is P503AppState.BudgetConfig && state.submitting) &&
        // P7-06 06.B: a running export keeps the surface (提交中不得离开).
        !(state is P503AppState.BackupExport && state.running) &&
        // P7-06 06.D: a running restore preflight or confirm keeps the surface (提交中不得离开).
        !(state is P503AppState.BackupRestore && (state.runningPreflight || state.runningConfirm))

/** P7-02.A E-2: origin tab of the in-flight entry flow, for the retained intent. */
private fun currentOriginTab(state: P503AppState): P503Tab =
    when (state) {
        is P503AppState.Submitting -> state.originTab
        is P503AppState.AwaitingConfirmation -> state.originTab
        is P503AppState.Editing -> state.originTab
        is P503AppState.RequestIdentityConflict -> state.originTab
        is P503AppState.DomainRejected -> state.originTab
        is P503AppState.InfrastructureFailure -> state.originTab
        is P503AppState.UnknownCommit -> state.originTab
        else -> P503Tab.HOME
    }

/** P7-02.A E-2: captures the reusable fields of one determinate-success draft. */
private fun TypedEntryDraft.toRetainedIntent(originTab: P503Tab): RetainedEntryIntent =
    RetainedEntryIntent(
        type = entryType,
        amountText = amountText,
        paymentAccountId = primaryAccountId,
        categoryId = categoryId,
        note = note,
        occurredAt = occurredAt,
        originTab = originTab,
    )

/** P7-02.A: the typed submit entry point, falling back to the expense-only path for legacy roots. */
private fun P503LedgerFacade.submitEntryOrExpense(): com.unifiedledger.application.ExecuteManualEntrySubmission = submitEntry ?: throw IllegalStateException("facade is missing the typed entry submission")

/**
 * P7-06 06.1 (D-176; spec 4.3(b)): the typed `RuntimeNotReady` landing for a submit that could not
 * acquire a lease. The type's existing [ManualExpenseSubmissionResult.InfrastructureFailure] is
 * reused so the reducer keeps its frozen SUBMISSION-failure semantics (never a silently dropped
 * submission and never a blocked UI thread). `internal` so the host-decision test can assert the
 * per-type mapping (the `P503App` composable itself has no JVM harness).
 */
internal fun runtimeNotReadySubmission(draft: TypedEntryDraft): ManualEntrySubmissionResult =
    when (draft) {
        is ExpenseDraft -> ManualEntrySubmissionResult.Expense(ManualExpenseSubmissionResult.InfrastructureFailure)
        is IncomeDraft -> ManualEntrySubmissionResult.Income(ManualIncomeSubmissionResult.InfrastructureFailure)
        is TransferDraft -> ManualEntrySubmissionResult.Transfer(ManualTransferSubmissionResult.InfrastructureFailure)
        is LendDraft -> ManualEntrySubmissionResult.Lend(ManualLendSubmissionResult.InfrastructureFailure)
        is CollectDraft -> ManualEntrySubmissionResult.Collect(ManualCollectSubmissionResult.InfrastructureFailure)
    }

/**
 * P2-E/P2-G fix (06.B review): the backup-export run/land pipeline, extracted from the
 * `P503App` composable so it is JVM-testable (the `runtimeNotReadySubmission` host-decision
 * precedent; the composable itself has no JVM harness). It is the only product path that runs the
 * export, so its four load-bearing decisions are all pinned by
 * `P503BackupExportHostPipelineTest`:
 *
 * 1. the export runs on [Dispatchers.Default] (off the UI thread; spec section 5);
 * 2. the typed result hops back onto the composition's main dispatcher ([scope]'s context) before
 *    the landing (the P704C-SPEC-01/QUAL-02 serial-write rule);
 * 3. a result captured under a superseded generation is DISCARDED ([isCurrentGeneration]);
 * 4. a throwing `export` still lands a typed [BackupExportResult.Failed] instead of stranding the
 *    surface `running` (P2-G): a fail-loud `LedgerFileSystem` adapter or any unexpected throw is
 *    mapped to [BackupExportFailure.CONTAINER_WRITE_FAILED].
 */
internal fun runBackupExport(
    scope: CoroutineScope,
    generation: Generation,
    request: BackupExportRequest,
    export: (BackupExportRequest) -> BackupExportResult,
    isCurrentGeneration: (Generation) -> Boolean,
    land: (BackupExportResult) -> Unit,
) {
    scope.launch(Dispatchers.Default) {
        val result =
            try {
                export(request)
            } catch (failure: Throwable) {
                BackupExportResult.Failed(BackupExportFailure.CONTAINER_WRITE_FAILED)
            }
        scope.launch {
            if (!isCurrentGeneration(generation)) return@launch
            land(result)
        }
    }
}

/**
 * P7-06 06.D (D-182; spec sections 3.1/3.2/6): the restore-preflight run/land pipeline, extracted
 * from the `P503App` composable so it is JVM-testable (the [runBackupExport] precedent). Its
 * load-bearing decisions are pinned by `P503RestoreHostPipelineTest`:
 *
 * 1. the preflight runs on [Dispatchers.Default] (off the UI thread; container-format spec
 *    section 4.8 — the KDF, streaming decryption and validation are the heaviest work in the app);
 * 2. the typed result hops back onto the composition's main dispatcher ([scope]'s context) before
 *    the landing (the P704C-SPEC-01/QUAL-02 serial-write rule);
 * 3. a result captured under a superseded generation is DISCARDED ([isCurrentGeneration]) — the
 *    preflight holds a whole-duration lease, so the generation can only change through a reopen
 *    that waited for it;
 * 4. a throwing preflight still lands a typed rejection instead of stranding the surface running
 *    (the runBackupExport decision): the preflight already converts every failure it can classify,
 *    so an escaped throw is reported as the generic typed read failure, never a fake success.
 */
internal fun runRestorePreflight(
    scope: CoroutineScope,
    generation: Generation,
    request: RestorePreflightRequest,
    preflight: (RestorePreflightRequest) -> RestorePreflightResult,
    isCurrentGeneration: (Generation) -> Boolean,
    land: (RestorePreflightResult) -> Unit,
) {
    scope.launch(Dispatchers.Default) {
        val result =
            try {
                preflight(request)
            } catch (failure: Throwable) {
                RestorePreflightResult.Rejected(BackupPreflightRejection.P706_SOURCE_READ_FAILED)
            }
        scope.launch {
            if (!isCurrentGeneration(generation)) return@launch
            land(result)
        }
    }
}

/**
 * P7-06 06.D (D-182; spec sections 3.9/3.10/6): the confirm & switch run/land pipeline (the same
 * extraction discipline). Its decisions are the load-bearing ones for the frozen landing rule:
 *
 * 1. the confirm runs on [Dispatchers.Default] (the quiesce, staging and reopen must never touch
 *    the UI thread);
 * 2. the typed result hops back onto the composition's main dispatcher before the landing;
 * 3. the landing guard binds the result's OWN [BackupRestoreSwitchResult.landingGeneration] — the
 *    POST-reopen generation for Committed/RolledBack, and the POST-restoration generation for the
 *    abort results (Stale/Postponed/AbortedBeforePublish). A switch ADVANCES the generation, and
 *    so does the section 3.10 restoration reopen on every post-quiesce abort, so guarding against
 *    the pre-confirm capture would discard the success AND every abort, wedging the surface (F-1).
 *    A result whose owner generation is null (the fail-closed RecoveryRequired, or a restoration
 *    that ended in StartupError) is delivered UNGUARDED — there is no current generation to
 *    compare against and discarding it would strand the surface forever;
 * 4. [confirm]'s contract is to return typed results or RETHROW any escape from the guarded
 *    region — a CancellationException, an Error from the fail-loud ports, or an escaped
 *    RuntimeException (P2-1/F-7 broadened the catch to every Throwable) — after the NonCancellable
 *    repair has already restored the runtime. An escaped RuntimeException is NOT converted into a
 *    typed result: the fail-loud file-system port contract permits it, and the commonTest
 *    `anEscapedRuntimeExceptionAfterTheQuiesceRestoresTheRuntimeBeforePropagating` pins that it
 *    propagates. Most Throwables are converted to typed results INSIDE the guarded region; this is
 *    the last-resort net for one that is not.
 */
internal fun runRestoreConfirm(
    scope: CoroutineScope,
    token: RestorePreflightToken,
    confirm: suspend (RestorePreflightToken) -> BackupRestoreSwitchResult,
    isCurrentGeneration: (Generation) -> Boolean,
    land: (BackupRestoreSwitchResult) -> Unit,
) {
    scope.launch(Dispatchers.Default) {
        val result = confirm(token)
        scope.launch {
            val resultGeneration = result.landingGeneration
            // Null: the owner is fail-closed (no active generation to guard against) — land it,
            // or the session-terminal/recovery face could never be reached (F-1).
            if (resultGeneration != null && !isCurrentGeneration(resultGeneration)) return@launch
            land(result)
        }
    }
}

/**
 * P7-03.D (F1; spec section 4.3, table 6.2a, C04): the retained monthly overview behind the
 * explicit read-failure banner. The month that was already on screen stays visible — month card,
 * month label, category region, trend and the display-ordered flow list of the retained tab —
 * and is never replaced by zeros or an empty month (R-Q06-4). Only the existing retry is live:
 * while the READ failure stands the reducer absorbs SelectMonth/SelectTransaction, so the same
 * regions are rendered without their interactive affordances rather than with dead ones. G2: the
 * banner copy follows the retained overview's own month-region state, so it never promises a
 * previously loaded month when the failed cycle had no successful payload (initial trigger (a)).
 */
@Composable
private fun P503RetainedOverviewFailureScreen(
    overview: P503AppState.OverviewEmpty,
    showMonthlyRegion: Boolean,
    resolvedCurrentMonth: YearMonth?,
    monthlyTrend: MonthlyTrend?,
    flowRows: FlowRowsView?,
    summarizeActivity: SummarizeLedgerActivity,
    onRetryRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
    ) {
        Text(
            // G2: the banner must not promise a loaded month when the retained overview has no
            // successful monthly payload (an initial trigger-(a) failure).
            retainedReadFailureBannerText(monthlyRegionState(overview.monthlyActivity, reloadRequired = false)),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            // C04 (spec section 6.4; the P503ResultScreen failure-banner precedent): the read
            // failure is announced to TalkBack instead of silently replacing the retained month.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onRetryRefresh) {
            Text("重试")
        }
        Spacer(Modifier.height(8.dp))
        when (overview.selectedTab) {
            P503Tab.HOME ->
                P503OverviewScreen(
                    state = overview.state,
                    showMonthlyRegion = showMonthlyRegion,
                    selectedMonth = overview.selectedMonth,
                    resolvedCurrentMonth = resolvedCurrentMonth,
                    // Empty domain: the steppers stay disabled (SelectMonth is absorbed here).
                    selectableMonths = emptyList(),
                    monthlyActivity = overview.monthlyActivity,
                    flowRows = flowRows,
                    onSelectTransaction = {},
                    onSelectMonth = {},
                    interactionsEnabled = false,
                )
            P503Tab.ANALYSIS ->
                P503AnalysisScreen(
                    state = overview.state,
                    summarizeActivity = summarizeActivity,
                    showMonthlyRegion = showMonthlyRegion,
                    selectedMonth = overview.selectedMonth,
                    resolvedCurrentMonth = resolvedCurrentMonth,
                    selectableMonths = emptyList(),
                    monthlyActivity = overview.monthlyActivity,
                    trend = monthlyTrend,
                    onSelectMonth = {},
                    onAnalysisMonthShift = {},
                    interactionsEnabled = false,
                )
            // The management surface is command-driven and has no transition while the READ
            // failure stands; keep an honest note instead of a dead management form.
            P503Tab.ACCOUNTS ->
                Text("账户管理需在读取恢复后使用。", style = MaterialTheme.typography.bodyMedium)
            // P7-04.C: the import surface is read-driven the same way; the honest note replaces a
            // dead review form while the READ failure stands (the retained list would read as
            // current even though interactions are absorbed).
            P503Tab.IMPORT ->
                Text("导入审核需在读取恢复后使用。", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun P503InfrastructureReadScreen(onRetryRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "无法读取账本数据（本地数据库不可用）",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetryRefresh) {
            Text("重试")
        }
    }
}

@Composable
private fun P503InfrastructureSubmissionScreen(
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "提交失败（本地数据库不可用），可重试或返回修改。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel) {
                Text("返回修改")
            }
            Button(onClick = onRetry) {
                Text("重试提交")
            }
        }
    }
}

/**
 * P7-07 07.D: one resolved budget commit plan, built at the explicit confirm (the host-side
 * analogue of the correction flow's built request) and executed off the UI thread. The 07.B
 * commit mints its own request id, so no id travels here (see the ConfirmBudgetLimit event note).
 */
private data class BudgetCommitPlan(
    val scope: BudgetScope,
    val month: YearMonth,
    val limitMinorUnits: Long?,
    val expectedRevision: Long,
    val close: Boolean,
)

// -------------------------------------------------------------------------------------------
// P7-08 08.D (P708-A07): the JVM-assertable seams of the filter re-request arm call sites. The
// `@Composable` `P503App` cannot be exercised by a plain JVM test (the P7-03/P7-04/D-156
// precedent), so each production landing hop delegates to one of these functions and the
// coordinator tests pin the branch directly. The P503App -> seam hop itself remains uncovered:
// no Compose harness exists for `P503App`, and `app-ui` has an Android target, so `commonTest`
// cannot source-scan it (the same registered disclosure as `refreshAfterP705Commit`).
// -------------------------------------------------------------------------------------------

/**
 * The correction commit's landing: a determinate success ([CorrectTransactionVersionResult.Created]/
 * [CorrectTransactionVersionResult.NoChange]) fires the authoritative refresh AND arms the loaded
 * filter re-request (spec section 5.5 row 2); every other outcome refreshes and arms nothing.
 */
internal fun refreshAndArmAfterP705CommitLanding(
    result: CorrectTransactionVersionResult,
    coordinator: P503HostCoordinator,
    arm: () -> Unit,
) {
    refreshAfterP705Commit(result, coordinator)
    if (shouldRefreshAfterP705Commit(result)) arm()
}

/**
 * The void/restore commit's landing — the merged result family's shared seam (the two host call
 * sites cannot diverge, the `refreshAfterP705Commit` discipline).
 */
internal fun refreshAndArmAfterVoidRestoreCommit(
    result: VoidTransactionResult,
    coordinator: P503HostCoordinator,
    arm: () -> Unit,
) {
    refreshAfterP705Commit(result, coordinator)
    if (shouldRefreshAfterP705Commit(result)) arm()
}

/**
 * The annotation edit's landing: accepted/no-change record the new revision through the effective
 * refresh and arm the filter re-request; a rejection/stale/conflict refreshes and arms nothing.
 */
internal fun refreshAndArmAfterAnnotationEdit(
    outcome: TransactionAnnotationEditOutcome,
    coordinator: P503HostCoordinator,
    arm: () -> Unit,
) {
    if (outcome is TransactionAnnotationEditOutcome.Accepted || outcome is TransactionAnnotationEditOutcome.NoChange) {
        coordinator.onP705EffectiveSurfaceChanged()
        arm()
    }
}

/**
 * The completed import batch confirmation's arm: the SAME all-terminal gate
 * ([shouldArmImportBatchConfirmed]) fires the coordinator's batch-confirmed hook AND the filter
 * arm exactly once per run; a landing without the all-terminal state does neither.
 */
internal fun armAfterImportBatchConfirmation(
    landedState: P503AppState,
    coordinator: P503HostCoordinator,
    arm: () -> Unit,
) {
    if (shouldArmImportBatchConfirmed(landedState)) {
        coordinator.onImportBatchConfirmed()
        arm()
    }
}
