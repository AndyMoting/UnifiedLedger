package com.unifiedledger.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unifiedledger.application.CatalogCommandResult
import com.unifiedledger.application.CatalogSnapshotView
import com.unifiedledger.application.CollectDraft
import com.unifiedledger.application.EntryPinOrdering
import com.unifiedledger.application.EntryPinResult
import com.unifiedledger.application.EntryPinTarget
import com.unifiedledger.application.ExpenseDraft
import com.unifiedledger.application.ExplicitManualSave
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
import com.unifiedledger.application.LedgerEntryRow
import com.unifiedledger.application.LendDraft
import com.unifiedledger.application.ManualCollectInputField
import com.unifiedledger.application.ManualCollectSaveInput
import com.unifiedledger.application.ManualCollectSaveResult
import com.unifiedledger.application.ManualCollectSubmissionResult
import com.unifiedledger.application.ManualEntryCommitResolution
import com.unifiedledger.application.ManualEntrySaveInput
import com.unifiedledger.application.ManualEntrySubmissionResult
import com.unifiedledger.application.ManualExpenseInputFailure
import com.unifiedledger.application.ManualExpenseInputField
import com.unifiedledger.application.ManualExpenseOptions
import com.unifiedledger.application.ManualExpenseRequestSnapshot
import com.unifiedledger.application.ManualExpenseSaveInput
import com.unifiedledger.application.ManualExpenseSaveResult
import com.unifiedledger.application.ManualExpenseSubmissionResult
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
import com.unifiedledger.application.ManualLendingOptions
import com.unifiedledger.application.ManualLendingRequestSnapshot
import com.unifiedledger.application.ManualTransferInputField
import com.unifiedledger.application.ManualTransferOptions
import com.unifiedledger.application.ManualTransferRequestSnapshot
import com.unifiedledger.application.ManualTransferSaveInput
import com.unifiedledger.application.ManualTransferSaveResult
import com.unifiedledger.application.ManualTransferSubmissionResult
import com.unifiedledger.application.MonthlyBuckets
import com.unifiedledger.application.MonthlyTrend
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.SummarizeLedgerActivity
import com.unifiedledger.application.TransferDraft
import com.unifiedledger.application.TypedEntryDraft
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.TransactionId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.datetime.YearMonth
import com.unifiedledger.application.MonthlyActivityResult as ApplicationMonthlyActivityResult

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
    facade: P503LedgerFacade,
    onExit: () -> Unit,
    backHandler: (@Composable (enabled: Boolean, onBack: () -> Unit) -> Unit)? = null,
) {
    val reducer = remember(facade) { P503ReducerImpl(facade.parseAmount, facade.currency, facade.ledgerClock) }
    val validation = remember(facade) { P503DraftValidation(facade.parseAmount, facade.parseOccurredAt, facade.ledgerClock) }
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
    var cachedCatalogSnapshot by remember(facade) { mutableStateOf<CatalogSnapshotView?>(null) }
    // The single-flight admission and the stale-merge decision live in the pure, JVM-tested
    // [P503CatalogSnapshotLoadCoordinator] (the P503HostCoordinator extraction precedent).
    val catalogSnapshotLoadCoordinator = remember(facade) { P503CatalogSnapshotLoadCoordinator() }
    // A-PERF (rework path 1a): the single-flight coalescing admission of the authoritative
    // current-state read behind refresh() and the initial load (pure, JVM-tested the same way).
    val currentStateLoadCoordinator = remember(facade) { P503CurrentStateLoadCoordinator() }

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
            scope.launch(Dispatchers.Default) {
                val fresh = runCatching { facade.catalogSnapshot() }.getOrNull()
                scope.launch {
                    cachedCatalogSnapshot = catalogSnapshotLoadCoordinator.loadCompleted(cachedCatalogSnapshot, fresh)
                }
            }
        }
    }

    // Initial load: the first composition starts the single background load once.
    LaunchedEffect(facade) {
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
        remember(facade, catalogVersion) {
            if (catalogVersion == null) ManualExpenseOptions(emptyList(), emptyList()) else facade.optionsProvider.queryOptions()
        }
    val baseIncomeOptions =
        remember(facade, catalogVersion) {
            if (catalogVersion == null) ManualIncomeOptions(emptyList(), emptyList()) else facade.incomeOptionsProvider.queryOptions()
        }
    val baseTransferOptions =
        remember(facade, catalogVersion) {
            if (catalogVersion == null) ManualTransferOptions(emptyList(), emptyList()) else facade.transferOptionsProvider.queryOptions()
        }
    // P702SPEC-13: the lending projection is the only one that reads the counterparty
    // directory, so it must re-query when a create/rename command succeeds.
    val baseLendingOptions =
        remember(facade, catalogVersion, counterpartyVersion) {
            if (catalogVersion == null) ManualLendingOptions(emptyList(), emptyList(), emptyList()) else facade.lendingOptionsProvider.queryOptions()
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
    var state by remember { mutableStateOf<P503AppState>(P503AppState.Ready) }
    val latestState = remember { mutableStateOf<P503AppState>(P503AppState.Ready) }
    // P5-04.3 single-flight marker for the unknown-commit status check (read-only resolve).
    var statusCheckInFlight by remember { mutableStateOf(false) }
    var editDialogOpen by remember { mutableStateOf(false) }
    // D-140 (spec 2.1): 键入文本宿主级提升；null = 未初始化，显示按 draft 派生。
    var hoistedOccurredAtText by remember { mutableStateOf<String?>(null) }
    // P7-02.A E-2 (G-C): the host-held memory of one determinate-success intent for "record
    // again". Captured before submission, consumed by the authoritative refresh; never persisted.
    var retainedIntent by remember { mutableStateOf<RetainedEntryIntent?>(null) }
    // P7-03.C/D (D-145): host mirrors of the ledger-view read surface. The trend and the sorted
    // flow rows are presentation data refreshed with the unified monthly cycle (frozen trigger
    // set, spec 6.2); the month payload and the SelectMonth domain live in the reducer state.
    var monthlyTrend by remember { mutableStateOf<MonthlyTrend?>(null) }
    var ledgerEntryRows by remember { mutableStateOf<List<LedgerEntryRow>?>(null) }
    var resolvedCurrentMonth by remember { mutableStateOf<YearMonth?>(null) }
    val ledgerViewWired = facade.queryMonthlyActivity != null || facade.queryLedgerEntryRows != null
    // P7-04.C: the matrix format of the pick currently in flight. PickedImportFile deliberately
    // carries no format (frozen shape, spec 4.1.1), so the host remembers the launched format and
    // consumes the slot when the picked result arrives (one pick at a time: SAF is single-shot and
    // the desktop chooser is modal).
    var pendingImportFormat by remember { mutableStateOf<ImportFormatId?>(null) }
    // P7-04.C: the decision-form validation over the shared parse facade (P503DraftValidation
    // pattern; the detail screen renders its one-shot errors).
    val importDecisionValidation = remember(facade) { P503ImportDecisionValidation(facade.parseAmount) }

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
        state = reducer.reduce(state, event).also { latestState.value = it }
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
                incomeOptions.receivingAccounts.firstOrNull { it.accountId == draft.receivingAccountId }?.currency ?: facade.currency
            is ExpenseDraft ->
                options.paymentAccounts.firstOrNull { it.accountId == draft.paymentAccountId }?.currency ?: facade.currency
            is TransferDraft ->
                transferOptions.ownedAssetAccounts.firstOrNull { it.accountId == draft.sourceAccountId }?.currency ?: facade.currency
            is LendDraft ->
                lendingOptions.ownedAssetAccounts.firstOrNull { it.accountId == draft.fundingAccountId }?.currency ?: facade.currency
            is CollectDraft ->
                lendingOptions.ownedAssetAccounts.firstOrNull { it.accountId == draft.destinationAccountId }?.currency ?: facade.currency
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
                val result = runCatching { facade.queryCurrentState.query() }.getOrNull()
                // Back on the main dispatcher: consume the retained intent and dispatch serially
                // with every other main-thread event (the same consume-on-success semantics as
                // the previous synchronous body — a failed read keeps the intent for the retry).
                scope.launch {
                    when (result) {
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
                        }
                        else -> {
                            dispatch(P503UiEvent.RefreshFailed)
                            // A-02 FIX-MONTH-1 (D-152): a failed landing requests nothing; the
                            // stale trigger must not be consumed by a later unrelated landing.
                            landingHopCoordinator?.dropMonthlyReRequestAfterFailedRefresh()
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
        val monthlyQuery = facade.queryMonthlyActivity ?: return
        val overview = latestState.value as? P503AppState.OverviewEmpty
        try {
            val clockMonth = MonthlyBuckets.currentMonth(facade.ledgerClock)
            resolvedCurrentMonth = clockMonth
            val effectiveMonth = overview?.selectedMonth ?: clockMonth
            val outcome =
                foldMonthlyCycle(
                    monthResult = monthlyQuery.query(effectiveMonth),
                    selectableMonthsResult = monthlyQuery.selectableMonths(),
                    trendResult = monthlyQuery.trend(),
                )
            when (outcome) {
                is MonthlyCycleOutcome.Ready -> {
                    val rows = facade.queryLedgerEntryRows?.query()
                    monthlyTrend = outcome.trend
                    ledgerEntryRows = rows
                    dispatch(P503UiEvent.MonthlyActivityResult(ApplicationMonthlyActivityResult.Success(outcome.activity), outcome.selectableMonths))
                }
                is MonthlyCycleOutcome.Failed ->
                    dispatch(P503UiEvent.MonthlyActivityResult(outcome.result, emptyList()))
            }
        } catch (failure: Exception) {
            dispatch(P503UiEvent.MonthlyActivityResult(ApplicationMonthlyActivityResult.Unavailable, emptyList()))
        }
    }

    /** P7-03.C: opens the read-only detail with the host-resolved typed payload (C03). */
    fun selectTransaction(transactionId: TransactionId) {
        val detailQuery = facade.queryTransactionDetail ?: return
        dispatch(P503UiEvent.SelectTransaction(transactionId, detailQuery.query(transactionId)))
    }

    // P5-04.3: shared input construction for the submission and the unknown-commit status
    // check so both build a field-identical per-type snapshot (the resolver compares field by
    // field). Returns null when the draft is incomplete.
    fun expenseSaveInput(
        draft: ExpenseDraft,
        requestId: RequestId,
    ): ManualExpenseSaveInput? {
        val currency = resolvedCurrency(draft)
        val parsed = facade.parseAmount.parse(draft.amountText, currency)
        val amount = (parsed as? ParseManualExpenseAmount.Result.Valid)?.let { Money.ofMinor(it.minorUnits, currency) }
        val categoryId = draft.categoryId
        val paymentAccountId = draft.paymentAccountId
        val occurredAt = draft.occurredAt
        if (amount == null || categoryId == null || paymentAccountId == null || occurredAt == null) {
            return null
        }
        return ManualExpenseSaveInput(
            ledgerId = facade.ledgerId,
            requestId = requestId,
            amount = amount,
            categoryId = categoryId,
            paymentAccountId = paymentAccountId,
            occurredAt = occurredAt,
            note = draft.note,
            confirmation = ExplicitManualSave,
        )
    }

    fun incomeSaveInput(
        draft: IncomeDraft,
        requestId: RequestId,
    ): ManualIncomeSaveInput? {
        val currency = resolvedCurrency(draft)
        val parsed = facade.parseAmount.parse(draft.amountText, currency)
        val amount = (parsed as? ParseManualExpenseAmount.Result.Valid)?.let { Money.ofMinor(it.minorUnits, currency) }
        val categoryId = draft.categoryId
        val receivingAccountId = draft.receivingAccountId
        val occurredAt = draft.occurredAt
        if (amount == null || categoryId == null || receivingAccountId == null || occurredAt == null) {
            return null
        }
        return ManualIncomeSaveInput(
            ledgerId = facade.ledgerId,
            requestId = requestId,
            amount = amount,
            categoryId = categoryId,
            receivingAccountId = receivingAccountId,
            occurredAt = occurredAt,
            note = draft.note,
            confirmation = ExplicitManualSave,
        )
    }

    fun transferSaveInput(
        draft: TransferDraft,
        requestId: RequestId,
    ): ManualTransferSaveInput? {
        val currency = resolvedCurrency(draft)
        val creditParsed = facade.parseAmount.parse(draft.destinationCredit, currency)
        val destinationCredit = (creditParsed as? ParseManualExpenseAmount.Result.Valid)?.let { Money.ofMinor(it.minorUnits, currency) }
        val fee =
            when {
                draft.fee.isBlank() -> Money.ofMinor(0L, currency)
                else -> (facade.parseAmount.parse(draft.fee, currency) as? ParseManualExpenseAmount.Result.Valid)?.let { Money.ofMinor(it.minorUnits, currency) }
            }
        val sourceAccountId = draft.sourceAccountId
        val destinationAccountId = draft.destinationAccountId
        val occurredAt = draft.occurredAt
        if (destinationCredit == null || fee == null || sourceAccountId == null || destinationAccountId == null || occurredAt == null) {
            return null
        }
        return ManualTransferSaveInput(
            ledgerId = facade.ledgerId,
            requestId = requestId,
            sourceAccountId = sourceAccountId,
            destinationAccountId = destinationAccountId,
            destinationCredit = destinationCredit,
            fee = fee,
            feeCategoryId = draft.feeCategoryId,
            occurredAt = occurredAt,
            note = draft.note,
            confirmation = ExplicitManualSave,
        )
    }

    fun lendSaveInput(
        draft: LendDraft,
        requestId: RequestId,
    ): ManualLendSaveInput? {
        val currency = resolvedCurrency(draft)
        val amount = (facade.parseAmount.parse(draft.amount, currency) as? ParseManualExpenseAmount.Result.Valid)?.let { Money.ofMinor(it.minorUnits, currency) }
        val counterpartyId = draft.counterpartyId
        val fundingAccountId = draft.fundingAccountId
        val occurredAt = draft.occurredAt
        if (amount == null || counterpartyId == null || fundingAccountId == null || occurredAt == null) return null
        return ManualLendSaveInput(
            ledgerId = facade.ledgerId,
            requestId = requestId,
            counterpartyId = counterpartyId,
            fundingAccountId = fundingAccountId,
            amount = amount,
            occurredAt = occurredAt,
            note = draft.note,
            confirmation = ExplicitManualSave,
        )
    }

    fun collectSaveInput(
        draft: CollectDraft,
        requestId: RequestId,
    ): ManualCollectSaveInput? {
        val currency = resolvedCurrency(draft)

        fun parsed(text: String): Money? = (facade.parseAmount.parse(text, currency) as? ParseManualExpenseAmount.Result.Valid)?.let { Money.ofMinor(it.minorUnits, currency) }
        val totalReceived = parsed(draft.totalReceived)
        val principal = parsed(draft.principal)
        val interest = parsed(draft.interest)
        val counterpartyId = draft.counterpartyId
        val destinationAccountId = draft.destinationAccountId
        val interestCategoryId = draft.interestCategoryId
        val occurredAt = draft.occurredAt
        if (totalReceived == null || principal == null || interest == null || counterpartyId == null || destinationAccountId == null || interestCategoryId == null || occurredAt == null) return null
        return ManualCollectSaveInput(
            ledgerId = facade.ledgerId,
            requestId = requestId,
            counterpartyId = counterpartyId,
            destinationAccountId = destinationAccountId,
            totalReceived = totalReceived,
            principal = principal,
            interest = interest,
            interestCategoryId = interestCategoryId,
            occurredAt = occurredAt,
            note = draft.note,
            confirmation = ExplicitManualSave,
        )
    }

    fun submit(
        draft: TypedEntryDraft,
        requestId: RequestId,
    ) {
        // P7-02 E-2: capture the retained intent from the pre-submit draft before it leaves.
        retainedIntent = draft.toRetainedIntent(currentOriginTab(latestState.value))
        val result =
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
        dispatch(P503UiEvent.SubmissionResult(result))
    }

    // P5-04.3: one read-only commit-status check for the unknown-commit flow, per entry type.
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
                    val resolution = facade.resolveCommitStatus.resolve(facade.ledgerId, requestId, attempted)
                    statusCheckInFlight = false
                    dispatch(P503UiEvent.CommitStatusResolved(ManualEntryCommitResolution.Expense(resolution)))
                }
            }
            is IncomeDraft -> {
                val input = incomeSaveInput(draft, requestId) ?: return
                val resolver = facade.resolveIncomeCommitStatus ?: return
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
                    val resolution = resolver.resolve(facade.ledgerId, requestId, attempted)
                    statusCheckInFlight = false
                    dispatch(P503UiEvent.CommitStatusResolved(ManualEntryCommitResolution.Income(resolution)))
                }
            }
            is TransferDraft -> {
                val input = transferSaveInput(draft, requestId) ?: return
                val resolver = facade.resolveTransferCommitStatus ?: return
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
                    val resolution = resolver.resolve(facade.ledgerId, requestId, attempted)
                    statusCheckInFlight = false
                    dispatch(P503UiEvent.CommitStatusResolved(ManualEntryCommitResolution.Transfer(resolution)))
                }
            }
            is LendDraft -> {
                val input = lendSaveInput(draft, requestId) ?: return
                val resolver = facade.resolveLendingCommitStatus ?: return
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
                    val resolution = resolver.resolve(facade.ledgerId, requestId, attempted)
                    statusCheckInFlight = false
                    dispatch(P503UiEvent.CommitStatusResolved(ManualEntryCommitResolution.Lend(resolution)))
                }
            }
            is CollectDraft -> {
                val input = collectSaveInput(draft, requestId) ?: return
                val resolver = facade.resolveLendingCommitStatus ?: return
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
                    val resolution = resolver.resolve(facade.ledgerId, requestId, attempted)
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
    LaunchedEffect(Unit) {
        currentStateLoadCoordinator.startLoadOnce {
            scope.launch(Dispatchers.Default) {
                // APQUAL-05: the same guarded read as refresh() — an unexpected throw maps to
                // the typed InitialLoadFailed and the slot release in the hop below is guaranteed.
                val result = runCatching { facade.queryCurrentState.query() }.getOrNull()
                // Back on the main dispatcher: seed the pin mirror, then dispatch serially.
                scope.launch {
                    when (result) {
                        is LedgerCurrentStateResult.Success -> {
                            // P7-02.D E-4: seed the persisted pins so they survive an app restart.
                            facade.entryPreferences?.let { store -> pinnedTargets = store.pinnedTargets(facade.ledgerId) }
                            dispatch(P503UiEvent.InitialLoadResult(result.state, pinnedTargets))
                        }
                        else -> dispatch(P503UiEvent.InitialLoadFailed)
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
    val coordinator =
        remember {
            P503HostCoordinator(
                onRefresh = ::refresh,
                onSubmit = { draft, requestId -> submit(draft, requestId) },
                onCheck = { draft, requestId -> checkCommitStatus(draft, requestId) },
                // P7-03.C: the unified monthly cycle runs only on the frozen trigger set (a)-(e)
                // (spec 6.2, P703SPEC-04); 本月 resolves from the reporting clock (R-Q06-2).
                onMonthlyRequest = ::requestMonthlyPayload,
                currentMonth = {
                    try {
                        MonthlyBuckets.currentMonth(facade.ledgerClock)
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
    }

    // G3: trigger (c) still says a month shift re-requests — but only a shift that actually moved
    // the shared cursor. An out-of-domain (absorbed) shift leaves the state instance untouched and
    // must not fire a wasted monthly read.
    fun analysisMonthShift(offset: Int) {
        val before = latestState.value
        dispatch(P503UiEvent.AnalysisMonthShift(offset))
        val reRequest = analysisMonthShiftReRequest(before, latestState.value)
        if (reRequest != null) coordinator.requestMonthlyNow(reRequest)
    }

    // Authoritative refresh after Created/NoChange/Recovered; never build the list from
    // the submission return value or accumulate balances in the UI. P7-03.C: the same
    // evaluation decides the monthly (a)/(d) re-requests.
    LaunchedEffect(state) {
        coordinator.decide(state)
        coordinator.decideMonthly(state)
    }

    // ---------------------------------------------------------------- P7-04.C import host surface

    /** P7-04.C: dispatches the request intent, then launches the platform picker (不切态). */
    fun startImportFilePick(format: ImportFormatId) {
        dispatch(P503UiEvent.StartImportFilePick(format))
        val port = facade.importFilePickPort ?: return
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
    fun runImportIntakePipeline(file: PickedImportFile) {
        val format = pendingImportFormat
        val intake = facade.importFileIntake
        val platform = facade.importPlatformKind
        val session = facade.importIntakeSessionFactory()
        if (format == null || intake == null || platform == null || session == null) {
            // No pipeline can run (unwired surface or a lost format slot): release the coordinator's
            // single-flight slot so a later pick is not permanently blocked.
            coordinator.importIntakeCompleted()
            return
        }
        pendingImportFormat = null
        scope.launch(Dispatchers.Default) {
            val outcome =
                when (val read = file.readBoundedBytes()) {
                    is BoundedFileRead.Bytes ->
                        ImportIntakePipelineOutcome.Intaken(
                            intake.intake(ImportFileIntakeInput(format, platform, session, read.bytes)),
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
            val rows = facade.queryImportReviewRows?.query(facade.ledgerId) ?: ImportReviewRowsResult.Unavailable
            // Back on the composition's (main) dispatcher: the single-flight slot is released and
            // the event is dispatched serially with every other main-thread dispatch.
            scope.launch {
                coordinator.importIntakeCompleted()
                dispatch(
                    P503UiEvent.ImportFileIntakeResult(
                        ImportIntakeSessionSummary(
                            displayName = file.displayName,
                            inputRef = session.inputRef,
                            outcome = outcome,
                        ),
                        rows,
                    ),
                )
            }
        }
    }

    /**
     * P7-04.C: one platform pick result (table 6.2a — the channel events are reducer-absorbed; this
     * handler owns the host action). A picked file starts the single-flight bounded-read + intake
     * pipeline; a cancellation or a typed platform failure starts nothing.
     */
    fun handleImportPickResult(result: ImportFilePickResult) {
        when (result) {
            is ImportFilePickResult.Picked -> dispatch(P503UiEvent.ImportFilePicked(result.file))
            ImportFilePickResult.Cancelled -> dispatch(P503UiEvent.ImportFilePickCancelled)
            is ImportFilePickResult.Failed -> dispatch(P503UiEvent.ImportFilePickFailed(result.reason))
        }
        when (val decision = coordinator.handleImportPickResult(result)) {
            is ImportPickIntakeDecision.StartIntake -> runImportIntakePipeline(decision.file)
            ImportPickIntakeDecision.AlreadyInFlight,
            ImportPickIntakeDecision.NoPipeline,
            -> Unit
        }
    }

    /**
     * P7-04.C: re-reads the ledger-scoped review list and returns it as
     * [P503UiEvent.ImportReviewResult] (read off the UI thread, dispatched back on the main
     * dispatcher — P704C-SPEC-01/QUAL-02).
     */
    fun requestImportReview() {
        dispatch(P503UiEvent.RefreshImportReview)
        val query = facade.queryImportReviewRows ?: return
        scope.launch(Dispatchers.Default) {
            val result = query.query(facade.ledgerId)
            scope.launch { dispatch(P503UiEvent.ImportReviewResult(result)) }
        }
    }

    /** P7-04.C: reads the candidate detail + duplicate comparison (off the UI thread), then opens the detail state (main dispatcher). */
    fun selectImportCandidate(candidateId: ImportCandidateId) {
        val detailQuery = facade.queryImportCandidateDetail ?: return
        val duplicatesQuery = facade.queryImportDuplicateReviews ?: return
        scope.launch(Dispatchers.Default) {
            val detail = detailQuery.query(facade.ledgerId, candidateId)
            val duplicates = duplicatesQuery.query(facade.ledgerId, candidateId)
            scope.launch { dispatch(P503UiEvent.SelectImportCandidate(candidateId, detail, duplicates)) }
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
        val reviewUseCase = facade.importDuplicateReview ?: return
        val ids = facade.importDuplicateReviewIds() ?: return
        val now = facade.ledgerClock.now().toString()
        val request =
            ImportDuplicateReviewRequest(
                identity = ImportRequestIdentity(facade.ledgerId, ids.requestId),
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
                try {
                    val review = reviewUseCase.execute(request)
                    val rows = facade.queryImportReviewRows?.query(facade.ledgerId) ?: ImportReviewRowsResult.Unavailable
                    val detail = facade.queryImportCandidateDetail?.query(facade.ledgerId, detailState.candidateId)
                    val duplicates = facade.queryImportDuplicateReviews?.query(facade.ledgerId, detailState.candidateId)
                    // Back on the main dispatcher: release the slot and dispatch serially (P704C-SPEC-01/QUAL-02).
                    scope.launch {
                        coordinator.importDuplicateReviewCompleted()
                        dispatch(P503UiEvent.ImportDuplicateReviewResult(review, ImportDuplicateReviewRefresh(rows, detail, duplicates)))
                    }
                } catch (failure: Exception) {
                    scope.launch {
                        coordinator.importDuplicateReviewCompleted()
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
                try {
                    // P704C-SPEC-05: the pure, JVM-tested enumeration owns the abort decision —
                    // any typed duplicate-review read failure aborts wholesale (never a silent
                    // partial group) and the typed list-failure banner is surfaced instead.
                    enumeration =
                        try {
                            val sessionQuery = facade.queryImportDuplicateReviewsForSession
                            if (sessionQuery != null) {
                                // P7-05 batch path: one session-level query replaces the
                                // per-candidate loop. Each row carries its subject candidate, so
                                // the batch folds per subject client-side and feeds the
                                // unchanged pure function through its load callback (Reviews for
                                // a folded member, an empty Reviews for a session candidate the
                                // batch did not return — the batch join only yields rows whose
                                // subject has a duplicate candidate).
                                val sessionResult = sessionQuery.query(facade.ledgerId, sessionInputRef)
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
                                val reviewQuery =
                                    facade.queryImportDuplicateReviews
                                        ?: return@launch
                                enumerateImportDuplicateGroupItems(sessionInputRef, view.rows) { candidateId ->
                                    reviewQuery.query(facade.ledgerId, candidateId)
                                }
                            }
                        } catch (failure: Exception) {
                            ImportDuplicateGroupEnumeration.ReadFailed
                        }
                } finally {
                    // Back on the main dispatcher: release the slot and dispatch serially.
                    scope.launch {
                        coordinator.importGroupEnumerationCompleted()
                        dispatch(P503UiEvent.ImportGroupEnumerationCompleted)
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
        val reviewUseCase = facade.importDuplicateReview ?: return
        coordinator.confirmImportDuplicateGroupDispositionOnce {
            scope.launch(Dispatchers.Default) {
                // P704D-SPEC-03 (C-batch leak-pattern prevention): the guarded body keeps the
                // disposition loop's single-flight slot always releasable — the finally hop
                // dispatches the outcomes collected so far and releases the slot regardless of
                // how the run ended. An unexpected mid-loop throw therefore still lands the
                // already-disposed items' visible partial success (可见部分成功， the loop's own
                // semantics) instead of leaking the slot and dropping every outcome.
                val outcomes = mutableListOf<ImportDuplicateGroupItemOutcome>()
                try {
                    page.items
                        // 已成功项幂等不重复处置：never re-submit a Reviewed item on a re-run.
                        .filter { it.outcome !is ImportDuplicateGroupItemResult.Reviewed }
                        // P704D-SPEC-03: forEach + immediate append — a mid-loop throw keeps the
                        // already-disposed items' outcomes in `outcomes` (the finally then lands
                        // the visible partial success); a map-then-addAll would drop them.
                        .forEach { itemState ->
                            val item = itemState.item
                            val ids = facade.importDuplicateReviewIds()
                            outcomes +=
                                if (ids == null) {
                                    ImportDuplicateGroupItemOutcome(
                                        item.duplicateCandidateId,
                                        // P704C-SPEC-07: a UI-owned guard code never borrows the core
                                        // SPINE_ diagnostic namespace (the spine family must stay 原样).
                                        ImportDuplicateGroupItemResult.Rejected("IMPORT_REVIEW_IDS_UNAVAILABLE"),
                                    )
                                } else {
                                    val now = facade.ledgerClock.now().toString()
                                    val request =
                                        ImportDuplicateReviewRequest(
                                            identity = ImportRequestIdentity(facade.ledgerId, ids.requestId),
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
                                    when (val result = reviewUseCase.execute(request)) {
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
                } finally {
                    val rows = facade.queryImportReviewRows?.query(facade.ledgerId) ?: ImportReviewRowsResult.Unavailable
                    // Back on the main dispatcher: release the slot and dispatch serially.
                    scope.launch {
                        coordinator.importGroupDispositionCompleted()
                        dispatch(P503UiEvent.ImportDuplicateGroupDispositionResult(outcomes.toList(), rows))
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- P7-04.D batch confirmation host surface

    /** P7-04.D: re-reads the review list off the UI thread and dispatches the typed result. */
    fun requestImportReviewRowsRead() {
        val query = facade.queryImportReviewRows ?: return
        scope.launch(Dispatchers.Default) {
            val rows = query.query(facade.ledgerId)
            scope.launch { dispatch(P503UiEvent.ImportReviewResult(rows)) }
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
                try {
                    val prePhase =
                        importBatchDispatchPrePhase(
                            loadRows = { facade.queryImportReviewRows?.query(facade.ledgerId) ?: ImportReviewRowsResult.Unavailable },
                            loadUseCases = { facade.importConfirmUseCases() },
                        )
                    when (prePhase) {
                        is ImportBatchDispatchPrePhase.Failed -> {
                            // The run-level typed skips land per still-undispatched item; every
                            // item is then terminal, so the reducer auto-leaves to the overview
                            // with the typed summary — no dead end, the candidates stay pending.
                            importBatchRunLevelFailureResults(undispatched, prePhase.code).forEach { result ->
                                scope.launch { dispatch(P503UiEvent.ImportItemResult(result.item, result.outcome)) }
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
                                    ledgerId = facade.ledgerId,
                                    parseAmount = facade.parseAmount,
                                    defaultCurrency = facade.currency,
                                )
                            val run =
                                loop.run(
                                    dispatch = { result ->
                                        scope.launch { dispatch(P503UiEvent.ImportItemResult(result.item, result.outcome)) }
                                    },
                                    execute = { useCase, request -> useCase.execute(request) },
                                )
                            completed = run is ImportBatchDispatchRun.Completed
                        }
                    }
                } finally {
                    // Back on the main dispatcher: release the slot (queued after every per-item
                    // result hop) and, on a completed run, re-read the list so the rows reflect
                    // the confirmed items. A completed run is monthly trigger (f) (P7-03
                    // FIX-STALE-1, D-153): the authoritative refresh + the armed post-landing
                    // monthly re-request make the home reflect the confirmed batch this session
                    // (a paused run arms nothing; its resumed completion reaches here completed).
                    scope.launch {
                        coordinator.importBatchDispatchCompleted()
                        if (completed) {
                            coordinator.onImportBatchConfirmed()
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
        val requestIdSource = facade.importConfirmRequestIdSource ?: return
        if (facade.importConfirmUseCases() == null) return
        // Q09.4: 授权时刻 LedgerClock 取样一次，经 explicitConfirmedAt 全项复用（mixed 必填）。
        val confirmedAt = facade.ledgerClock.now().toString()
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
     * pre-phase failure is the StillUnknown verdict (retryable), never a stranded run.
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
                try {
                    val prePhase =
                        importBatchDispatchPrePhase(
                            loadRows = { facade.queryImportReviewRows?.query(facade.ledgerId) ?: ImportReviewRowsResult.Unavailable },
                            loadUseCases = { facade.importConfirmUseCases() },
                        )
                    if (prePhase is ImportBatchDispatchPrePhase.Ready) {
                        val context =
                            importUnknownCheckContext(
                                item,
                                prePhase.rows,
                                drafts,
                                confirmedAt,
                                prePhase.useCases,
                                facade.ledgerId,
                                facade.parseAmount,
                                facade.currency,
                            )
                        outcome = runImportUnknownItemCheck(context) { useCase, request -> useCase.execute(request) }
                    }
                } finally {
                    scope.launch {
                        coordinator.importUnknownCheckCompleted()
                        dispatch(P503UiEvent.ImportUnknownItemCheckResult(item, outcome))
                        if (outcome is ImportUnknownCheckOutcome.Confirmed) {
                            requestImportReviewRowsRead()
                        }
                    }
                }
            }
        }
    }

    // The platform pick results flow back through the composition root's channel (spec 4.1.1: the
    // result never rides the port's signature). The delivery thread is the UI thread on both ends
    // (the SAF callback runs on main; the desktop modal chooser blocks inside the UI event
    // handler, AB-CLI-QUAL-05), so the dispatch and the coordinator decision are safe there.
    DisposableEffect(facade) {
        facade.importPickResultChannel?.subscribe(::handleImportPickResult)
        onDispose { facade.importPickResultChannel?.subscribe(null) }
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
        if (shouldRefreshReadModelAfterCatalogCommand(result)) {
            facade.refreshCatalog()
            // R1 (spec 6.2/7.3, D-027): HOME's balances/transaction lines come from the read
            // model, which now reads through the refreshed session; re-query it via the existing
            // refresh channel so a rename/deactivate shows new names on HOME without a restart.
            refresh()
        }
        val fresh =
            runCatching { facade.catalogSnapshot() }.getOrNull()
                ?: (latestState.value as? P503AppState.OverviewEmpty)?.catalogSnapshot
                ?: return
        cachedCatalogSnapshot = fresh
        val payload =
            CatalogSnapshotView(
                fresh.catalogVersion,
                EntryPinOrdering.sortAccounts(fresh.manageableAccounts, pinnedTargets),
                EntryPinOrdering.sortCategories(fresh.categories, pinnedTargets),
            )
        // F1 (N-5): refresh() may have failed into InfrastructureFailure(READ), which has no
        // transition for management events; publish the outcome only while still on the overview.
        dispatchCatalogOutcomeIfOverview(latestState.value, P503UiEvent.CatalogCommandCompleted(result, payload), ::dispatch)
    }

    fun runCatalogToggle(event: P503UiEvent) {
        val command = facade.executeCatalogCommand ?: return
        val version = (latestState.value as? P503AppState.OverviewEmpty)?.catalogSnapshot?.catalogVersion ?: return
        scope.launch {
            val result =
                when (event) {
                    is P503UiEvent.ManageAccountActive ->
                        command.setAccountActive(facade.ledgerId, event.accountId, event.active, version)
                    is P503UiEvent.ManageCategoryActive ->
                        command.setCategoryActive(facade.ledgerId, event.categoryId, event.active, version)
                    is P503UiEvent.EnableCategoryGroup ->
                        command.enableCategoryGroup(facade.ledgerId, event.parentId, version)
                    else -> return@launch
                }
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
        val store = facade.entryPreferences ?: return
        scope.launch {
            when (val result = store.togglePin(target, facade.ledgerClock.now())) {
                is EntryPinResult.Toggled -> {
                    pinnedTargets = if (result.pinned) pinnedTargets + target else pinnedTargets - target
                    dispatch(P503UiEvent.TogglePin(target, result.pinned, pinnedCatalogSnapshot()))
                }
                is EntryPinResult.Rejected -> Unit
            }
        }
    }

    fun runCatalogForm(dialog: CatalogDialog) {
        val command = facade.executeCatalogCommand ?: return
        val version = (latestState.value as? P503AppState.OverviewEmpty)?.catalogSnapshot?.catalogVersion ?: return
        scope.launch {
            val result =
                when (dialog) {
                    is CatalogDialog.CreateAccount ->
                        command.createAccount(facade.ledgerId, dialog.nameText, dialog.kind, version)
                    is CatalogDialog.RenameAccount ->
                        command.renameAccount(facade.ledgerId, dialog.accountId, dialog.nameText, version)
                    is CatalogDialog.CreateCategoryGroup ->
                        command.createCategoryGroup(facade.ledgerId, dialog.kind, dialog.groupNameText, dialog.firstChildNameText, version)
                    is CatalogDialog.AppendCategoryChild ->
                        command.appendCategoryChild(facade.ledgerId, dialog.parentId, dialog.nameText, version)
                    is CatalogDialog.RenameCategory ->
                        command.renameCategory(facade.ledgerId, dialog.categoryId, dialog.nameText, version)
                    is CatalogDialog.ConfirmCategoryDelete ->
                        command.deleteCategory(facade.ledgerId, dialog.categoryId, version)
                    CatalogDialog.None -> return@launch
                }
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
        facade.refreshCatalog()
        refresh()
        val fresh = runCatching { facade.catalogSnapshot() }.getOrNull() ?: return
        cachedCatalogSnapshot = fresh
        val payload =
            CatalogSnapshotView(
                fresh.catalogVersion,
                EntryPinOrdering.sortAccounts(fresh.manageableAccounts, pinnedTargets),
                EntryPinOrdering.sortCategories(fresh.categories, pinnedTargets),
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
        val commands = facade.counterpartyCommands ?: return
        scope.launch {
            val result =
                when (dialog) {
                    is CounterpartyDialog.Create -> commands.create.execute(facade.ledgerId, dialog.nameText)
                    is CounterpartyDialog.Rename -> commands.rename.execute(facade.ledgerId, dialog.counterpartyId, dialog.nameText)
                }
            if (shouldRefreshOptionsAfterCounterpartyCommand(result)) {
                facade.refreshCatalog()
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

    P503Theme {
        when (val current = state) {
            P503AppState.Ready -> P503StartupScreen(P503StartupState.Starting, onRetry = {}, onExit = onExit)
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
                                entryRows = ledgerEntryRows,
                                onSelectTransaction = ::selectTransaction,
                                onSelectMonth = ::selectMonth,
                            )
                        P503Tab.ACCOUNTS ->
                            P503CatalogManagementScreen(
                                state = current,
                                onEvent = { event ->
                                    when (event) {
                                        is P503UiEvent.ManageAccountActive,
                                        is P503UiEvent.ManageCategoryActive,
                                        is P503UiEvent.EnableCategoryGroup,
                                        -> runCatalogToggle(event)
                                        // P7-02.D E-4: persist the toggle first, then dispatch.
                                        is P503UiEvent.TogglePin -> runPinToggle(event.target)
                                        else -> dispatch(event)
                                    }
                                },
                                onSubmit = { dialog -> runCatalogForm(dialog) },
                                onRefresh = { refreshCatalogSnapshot() },
                            )
                        P503Tab.ANALYSIS ->
                            P503AnalysisScreen(
                                state = current.state,
                                summarizeActivity = facade.summarizeActivity,
                                showMonthlyRegion = ledgerViewWired,
                                selectedMonth = current.selectedMonth,
                                resolvedCurrentMonth = resolvedCurrentMonth,
                                selectableMonths = current.selectableMonths,
                                monthlyActivity = current.monthlyActivity,
                                monthlyReloadRequired = current.monthlyReloadRequired,
                                trend = monthlyTrend,
                                onSelectMonth = ::selectMonth,
                                onAnalysisMonthShift = ::analysisMonthShift,
                            )
                        // P7-04.C: the IMPORT tab content — the review projection, the matrix
                        // format entries, the session summary and the group disposition surface.
                        // P7-04.D: the batch confirmation entry and the retained result summary
                        // (with the Unknown items' check entries) render on the same projection.
                        P503Tab.IMPORT ->
                            P503ImportScreen(
                                view = current.importReview,
                                platform = facade.importPlatformKind,
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
            is P503AppState.TransactionDetail ->
                P503TransactionDetailScreen(
                    detail = current.detail,
                    // Back = CloseTransactionDetail semantics (tab/month preserved, C03).
                    onClose = { if (isBackDispatchSafe(latestState.value)) dispatch(P503UiEvent.Back) },
                )
            // P7-04.C: the import candidate detail (spec sections 6.1/6.2). The catalog options
            // are the same authoritative projections the entry flow consumes (D-143 同源).
            is P503AppState.ImportCandidateDetail ->
                P503ImportCandidateDetailScreen(
                    state = current,
                    defaultCurrency = facade.currency,
                    validation = importDecisionValidation,
                    // A-PERF (spec section 2.3): the detail screen's catalog accounts read the
                    // CACHED snapshot (the previous direct facade read ran on the main thread
                    // during every detail recomposition); `catalogLoading` carries the honest
                    // null window so the decision form presents the placeholder instead of an
                    // empty authoritative catalog (S2-2).
                    catalogAccounts = cachedCatalogSnapshot?.manageableAccounts ?: emptyList(),
                    catalogLoading = cachedCatalogSnapshot == null,
                    expenseCategories = options.expenseCategories,
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
                        val wired = current != null && facade.importDuplicateReview != null && facade.importDuplicateReviewIds() != null
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
                    parseAmount = facade.parseAmount,
                    defaultCurrency = facade.currency,
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
                    ledgerClock = facade.ledgerClock,
                    parseOccurredAt = facade.parseOccurredAt,
                    onUpdateAmount = { dispatch(P503UiEvent.UpdateAmount(it)) },
                    onUpdatePaymentAccount = { dispatch(P503UiEvent.UpdatePaymentAccount(it)) },
                    onUpdateCategory = { dispatch(P503UiEvent.UpdateCategory(it)) },
                    onUpdateOccurredAt = { dispatch(P503UiEvent.UpdateOccurredAt(it)) },
                    onSelectEntryType = { dispatch(P503UiEvent.SelectEntryType(it)) },
                    onUpdateNote = { dispatch(P503UiEvent.UpdateNote(it)) },
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
                            val requestId = current.requestId ?: facade.requestIdSource.next()
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
                    ledgerClock = facade.ledgerClock,
                    parseOccurredAt = facade.parseOccurredAt,
                    onUpdateAmount = { dispatch(P503UiEvent.UpdateAmount(it)) },
                    onUpdatePaymentAccount = { dispatch(P503UiEvent.UpdatePaymentAccount(it)) },
                    onUpdateCategory = { dispatch(P503UiEvent.UpdateCategory(it)) },
                    onUpdateOccurredAt = { dispatch(P503UiEvent.UpdateOccurredAt(it)) },
                    onSelectEntryType = { dispatch(P503UiEvent.SelectEntryType(it)) },
                    onUpdateNote = { dispatch(P503UiEvent.UpdateNote(it)) },
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
                    ledgerClock = facade.ledgerClock,
                    parseOccurredAt = facade.parseOccurredAt,
                    onUpdateAmount = { dispatch(P503UiEvent.UpdateAmount(it)) },
                    onUpdatePaymentAccount = { dispatch(P503UiEvent.UpdatePaymentAccount(it)) },
                    onUpdateCategory = { dispatch(P503UiEvent.UpdateCategory(it)) },
                    onUpdateOccurredAt = { dispatch(P503UiEvent.UpdateOccurredAt(it)) },
                    onSelectEntryType = { dispatch(P503UiEvent.SelectEntryType(it)) },
                    onUpdateNote = { dispatch(P503UiEvent.UpdateNote(it)) },
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
                                entryRows = ledgerEntryRows,
                                summarizeActivity = facade.summarizeActivity,
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
        is P503AppState.Editing -> state.overview != null
        is P503AppState.AwaitingConfirmation -> state.overview != null
        is P503AppState.Submitting -> state.overview != null
        is P503AppState.RequestIdentityConflict -> state.overview != null
        is P503AppState.DomainRejected -> state.overview != null
        is P503AppState.InfrastructureFailure ->
            state.context == InfrastructureFailureContext.SUBMISSION && state.overview != null
        else -> false
    }

/**
 * P5-04.3: dispatching Back is only legal from the Back-able states; Submitting swallows the
 * back and every other state (including the HOME overview root) must not dispatch one.
 * P7-04.D: the import batch dispatch state joins Submitting in swallowing the back — the only
 * exits are the explicit Resume/Abandon affordances (显式退出只经 AbandonImportBatch).
 */
private fun isBackDispatchSafe(state: P503AppState): Boolean = isBackEnabled(state) && state !is P503AppState.Submitting && state !is P503AppState.ImportBatchSubmitting

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
    entryRows: List<LedgerEntryRow>?,
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
                    entryRows = entryRows,
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
