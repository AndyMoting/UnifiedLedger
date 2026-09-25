package com.unifiedledger.ui

import com.unifiedledger.application.CatalogSnapshotView
import com.unifiedledger.application.EntryExpressionCode
import com.unifiedledger.application.EntryPinTarget
import com.unifiedledger.application.EntryType
import com.unifiedledger.application.ExpenseDraft
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.TypedEntryDraft
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CounterpartyId
import com.unifiedledger.domain.TransactionId
import kotlin.time.Instant

/**
 * P5-03 shared UI state machine (spec sections 7.1-7.2). Twelve P5-03/P7-01/P7-02 states plus
 * the single P7-03.C read-only detail state.
 *
 * `Ready` is the platform-startup-concluded entry state that consumes the initial
 * authoritative load. `OverviewEmpty` carries the authoritative current state: an empty
 * ledger renders the empty state, a non-empty ledger renders the transaction list and
 * per-account balances. `Created`/`NoChange`/`Recovered` are transient result states
 * followed by an authoritative refresh back to `OverviewEmpty`. Startup retry ownership
 * belongs to the platform composition roots and their `P503StartupState`, not this machine.
 *
 * P7-03.C (D-145) extends `OverviewEmpty` with the shared month cursor and the unified
 * monthly payload, and adds `TransactionDetail` (read-only, reached only from a HOME flow
 * row; spec section 6.1). The new events never throw in any state (table 6.2a); every
 * pre-existing unlisted combination stays ISE (G-B).
 *
 * P7-04.C (D-146) adds the IMPORT tab's [OverviewEmpty.importReview] projection and the
 * [ImportCandidateDetail] state (reached only from an IMPORT list row). The import events are
 * absorbed in every state outside their designed effects and never throw; every pre-existing
 * unlisted combination — including `Exit` (spec section 6.3, P7-02 §6.2b) — stays unlisted.
 *
 * P7-04.D (D-146) adds the batch confirmation states: [ImportBatchConfirm] (the authorization
 * snapshot confirm page) and [ImportBatchSubmitting] (the per-item dispatch state, back
 * intercepted 沿 Submitting 语义). Their events follow the same table 6.2a discipline: effect in
 * the designed state, absorbed elsewhere, never an ISE; `Exit` stays unlisted on them too.
 *
 * P7-05 (D-156; spec sections 3.5/4.4) adds the correction/void/recycle-bin surfaces:
 * [TransactionEdit] (the independent correction form + difference preview + explicit confirm),
 * [VoidConfirm] (the reason + explicit confirm page) and [RecycleBin] (the voided-transaction
 * list plus the nested [RestoreConfirm] sub-state). The correction surface carries the CAS token
 * (`currentVersionId`) the design requires; the void/restore family carries none — it is guarded
 * by the void-fact sequence (DP-8). All three carry a per-operation submitting marker and never
 * invent a new transient result family — the `Submitting`/`UnknownCommit` discipline is reused
 * (a submitting surface never leaves, a submitting operation is never re-entered). The new
 * events only define P7-05 transitions; every pre-existing event x state combination is
 * unchanged and every unlisted combination stays `unhandled` (the G-B discipline).
 */
sealed interface P503AppState {
    data object Ready : P503AppState

    /**
     * P7-01.D catalog management lives on the overview as optional fields (spec section 7):
     * when [selectedTab] is ACCOUNTS the shell renders the manageable accounts plus the shared
     * two-level category tree instead of the read-only balance list. [catalogSnapshot] is the
     * authoritative projection the host read from the facade (names replacing bare ids);
     * [catalogDialog] is the open form and [catalogNotice] the last outcome banner. Every
     * catalog transition stays in the pure reducer. The defaults keep all pre-P7-01 constructor
     * and copy sites compiling.
     *
     * P7-03.C (D-145): [selectedMonth] is the shared month cursor of the monthly presentation
     * (`null` = 本月， resolved from the injected [LedgerClock] at request time; R-Q06-2);
     * [selectableMonths] is the frozen SelectMonth domain `[first transaction statistics month,
     * 本月]` the host read from [com.unifiedledger.application.QueryMonthlyActivity.selectableMonths]
     * (P703SPEC-10; out-of-domain SelectMonth is absorbed, spec section 6.2); [monthlyActivity]
     * is the last successful unified monthly payload feeding the home month card, the category
     * drilldown and the trend (plan section 5.1: one result, three presentations).
     */
    data class OverviewEmpty(
        val state: LedgerCurrentState,
        val selectedTab: P503Tab = P503Tab.HOME,
        val catalogSnapshot: CatalogSnapshotView? = null,
        val catalogDialog: CatalogDialog = CatalogDialog.None,
        val catalogNotice: CatalogNotice? = null,
        /**
         * P7-02.A E-2 (G-C): the intent the host carries after one determinate success
         * (Created/NoChange/Recovered) and its authoritative refresh, so "record again" can
         * start a fresh editor from the retained fields. `null` on startup, initial entry and
         * ordinary refreshes; never persisted.
         */
        val retainedIntent: RetainedEntryIntent? = null,
        /**
         * P7-02.D E-4: the reducer's render copy of the persisted pin set. `TogglePin` sets it to
         * the store's authoritative membership on the overview only (ordering preference, zero
         * accounting effect); the host seeds it from the
         * [com.unifiedledger.application.EntryPreferenceStore] on load/refresh and persists each
         * toggle. Every transition that rebuilds an overview from a carried one keeps this set, so
         * the management rows can never offer a pin action whose direction contradicts the store
         * (A02PIN-002).
         */
        val pinnedTargets: Set<EntryPinTarget> = emptySet(),
        /** P7-03.C: the shared month cursor; `null` = 本月 (clock-resolved, R-Q06-2). */
        val selectedMonth: kotlinx.datetime.YearMonth? = null,
        /** P7-03.C: the frozen SelectMonth domain, old to new; empty = nothing selectable. */
        val selectableMonths: List<kotlinx.datetime.YearMonth> = emptyList(),
        /** P7-03.C: the last successful unified monthly payload for the effective month. */
        val monthlyActivity: com.unifiedledger.application.MonthlyActivity? = null,
        /**
         * P7-03.D (F2): the monthly payload is known to be absent for this overview because the
         * READ retry that recovered a monthly failure does not re-request it — `RetryRefresh` is
         * deliberately outside the frozen monthly re-request trigger set (spec 6.2 residual
         * boundary (a)). The retry still preserves the month cursor and the SelectMonth domain
         * read from the retained overview, and the monthly region then presents the explicit
         * 月度数据未加载——请重新选择月份 affordance whose re-select action dispatches
         * `SelectMonth` (trigger (b), which always re-requests). `false` on every other path,
         * including all pre-P7-03 flows.
         */
        val monthlyReloadRequired: Boolean = false,
        /**
         * P7-04.C (D-146; spec section 6.1): the IMPORT tab's authoritative projection (candidate
         * list through the section 3.3.1 classification matrix, selection set, in-session decision
         * drafts, the most recent intake session summary and the typed failure banners). `null`
         * before the first IMPORT load — the catalogSnapshot-style optional field keeps every
         * pre-P7-04 constructor site compiling untouched.
         */
        val importReview: ImportReviewView? = null,
    ) : P503AppState

    data class Editing(
        val draft: TypedEntryDraft,
        val requestId: RequestId?,
        // P5-04.2: overview snapshot + source tab captured when the editor flow started.
        val overview: LedgerCurrentState? = null,
        val originTab: P503Tab = P503Tab.HOME,
        /**
         * P7-02.D E-3 (P2-6): the current entry-expression evaluation result. The calculator
         * shows the exact result (or the typed rejection) first; only an explicit
         * `ApplyExpressionResult` with a valid preview rewrites the amount text — the reducer
         * never silently edits the amount.
         */
        val expressionPreview: ExpressionPreview? = null,
        /**
         * P702SPEC-03: the open counterparty create/rename form, if any. Editor-local dialog
         * state (mirrors the P7-01 catalog dialog pattern at much smaller scope); it is dropped
         * by every transition away from `Editing` and never blocks the draft.
         */
        val counterpartyDialog: CounterpartyDialog? = null,
        /**
         * A-02 FIX-PIN-2: the overview's pin set, carried through the flow so the overview rebuilt
         * by `Back` keeps the pin marks (and their action direction) instead of resetting them.
         * Every flow state that can reach a `Back` reconstruction carries it, so no close path can
         * drop a persisted pin.
         */
        val pinnedTargets: Set<EntryPinTarget> = emptySet(),
        /**
         * A-02 FIX-MONTH-2 (D-152): the pre-editor monthly snapshot, carried mechanically through
         * every in-flow transition (the FIX-PIN-2 precedent) so the `Back` rebuild of
         * [OverviewEmpty] restores the month card payload and the shared month cursor
         * (A02MONTH-001's editor-switch path). The flow is zero-write (Cancel/Back drop the
         * draft), so the payload cannot go stale while editing; `Back` restores
         * `monthlyReloadRequired = false` — a present payload never needs a reload, and an absent
         * one keeps the pre-fix AWAITING surface. Empty defaults keep every pre-D constructor and
         * copy site compiling; a determinate-success confirmation (`Created`) starts a fresh
         * refresh and never consumes the carried snapshot.
         */
        val selectedMonth: kotlinx.datetime.YearMonth? = null,
        /** A-02 FIX-MONTH-2: see [Editing.selectedMonth]. */
        val selectableMonths: List<kotlinx.datetime.YearMonth> = emptyList(),
        /** A-02 FIX-MONTH-2: see [Editing.selectedMonth]. */
        val monthlyActivity: com.unifiedledger.application.MonthlyActivity? = null,
    ) : P503AppState

    data class AwaitingConfirmation(
        val draft: TypedEntryDraft,
        val requestId: RequestId,
        val overview: LedgerCurrentState? = null,
        val originTab: P503Tab = P503Tab.HOME,
        // P5-04.3: display labels carried from Continue (the host resolves them from
        // ManualExpenseOptions); the reducer falls back to the draft id values, so the
        // empty default is defensive only.
        val paymentAccountLabel: String = "",
        val categoryLabel: String = "",
        /**
         * A-02 FIX-CONFIRM-1: the type-owned labels the confirmation page's transfer and lending
         * rows need. `null` for every type that owns no such row, and for a draft whose object
         * was never resolved.
         */
        val destinationAccountLabel: String? = null,
        val counterpartyLabel: String? = null,
        /** A-02 FIX-PIN-2: see [Editing.pinnedTargets]. */
        val pinnedTargets: Set<EntryPinTarget> = emptySet(),
        /** A-02 FIX-MONTH-2: see [Editing.selectedMonth]. */
        val selectedMonth: kotlinx.datetime.YearMonth? = null,
        /** A-02 FIX-MONTH-2: see [Editing.selectedMonth]. */
        val selectableMonths: List<kotlinx.datetime.YearMonth> = emptyList(),
        /** A-02 FIX-MONTH-2: see [Editing.selectedMonth]. */
        val monthlyActivity: com.unifiedledger.application.MonthlyActivity? = null,
    ) : P503AppState

    data class Submitting(
        val draft: TypedEntryDraft,
        val requestId: RequestId,
        val overview: LedgerCurrentState? = null,
        val originTab: P503Tab = P503Tab.HOME,
        /** A-02 FIX-PIN-2: see [Editing.pinnedTargets]. */
        val pinnedTargets: Set<EntryPinTarget> = emptySet(),
        /** A-02 FIX-MONTH-2: see [Editing.selectedMonth]. */
        val selectedMonth: kotlinx.datetime.YearMonth? = null,
        /** A-02 FIX-MONTH-2: see [Editing.selectedMonth]. */
        val selectableMonths: List<kotlinx.datetime.YearMonth> = emptyList(),
        /** A-02 FIX-MONTH-2: see [Editing.selectedMonth]. */
        val monthlyActivity: com.unifiedledger.application.MonthlyActivity? = null,
    ) : P503AppState

    data object Created : P503AppState

    data object NoChange : P503AppState

    data class RequestIdentityConflict(
        val draft: TypedEntryDraft,
        val requestId: RequestId,
        val overview: LedgerCurrentState? = null,
        val originTab: P503Tab = P503Tab.HOME,
        /** A-02 FIX-PIN-2: see [Editing.pinnedTargets]. */
        val pinnedTargets: Set<EntryPinTarget> = emptySet(),
        /** A-02 FIX-MONTH-2: see [Editing.selectedMonth]. */
        val selectedMonth: kotlinx.datetime.YearMonth? = null,
        /** A-02 FIX-MONTH-2: see [Editing.selectedMonth]. */
        val selectableMonths: List<kotlinx.datetime.YearMonth> = emptyList(),
        /** A-02 FIX-MONTH-2: see [Editing.selectedMonth]. */
        val monthlyActivity: com.unifiedledger.application.MonthlyActivity? = null,
    ) : P503AppState

    data class DomainRejected(
        val draft: TypedEntryDraft,
        val requestId: RequestId,
        val overview: LedgerCurrentState? = null,
        val originTab: P503Tab = P503Tab.HOME,
        /** A-02 FIX-PIN-2: see [Editing.pinnedTargets]. */
        val pinnedTargets: Set<EntryPinTarget> = emptySet(),
        /** A-02 FIX-MONTH-2: see [Editing.selectedMonth]. */
        val selectedMonth: kotlinx.datetime.YearMonth? = null,
        /** A-02 FIX-MONTH-2: see [Editing.selectedMonth]. */
        val selectableMonths: List<kotlinx.datetime.YearMonth> = emptyList(),
        /** A-02 FIX-MONTH-2: see [Editing.selectedMonth]. */
        val monthlyActivity: com.unifiedledger.application.MonthlyActivity? = null,
    ) : P503AppState

    data class InfrastructureFailure(
        val context: InfrastructureFailureContext,
        // context == SUBMISSION: draft/requestId are always present (same-intent retry/return);
        // context == READ: both are null (finding P503Q-014).
        val draft: TypedEntryDraft? = null,
        val requestId: RequestId? = null,
        // P5-04.2: overview snapshot + source tab (meaningful only for SUBMISSION; READ is null).
        val overview: LedgerCurrentState? = null,
        val originTab: P503Tab = P503Tab.HOME,
        /**
         * P7-03.C/D (D-145; spec section 6.2 matrix, 4.3, C04): the monthly read failure preserves
         * the last successful overview (tab, month cursor and monthly payload) so the read
         * failure keeps the previously rendered month visible next to the explicit failure
         * banner, never rendered as zeros or an empty month (R-Q06-4). The composition root renders
         * this retained overview read-only behind the failure banner whenever it is non-null
         * (F1); when it is `null` (every pre-P7-03 READ failure path) the bare recoverable failure
         * page is rendered with its unchanged retry. Read-only payload: the existing READ retry
         * semantics are unchanged (spec 6.3) and recovery from a monthly failure is a
         * re-dispatched SelectMonth (residual boundary (a) of section 6.2).
         */
        val monthlyOverview: OverviewEmpty? = null,
        /** A-02 FIX-PIN-2: see [Editing.pinnedTargets]. */
        val pinnedTargets: Set<EntryPinTarget> = emptySet(),
        /**
         * A-02 FIX-MONTH-2: see [Editing.selectedMonth]. Carried by the SUBMISSION context's flow
         * (the same snapshot the submitting flow held); the READ context never populates it (its
         * monthly retention is [monthlyOverview]'s own contract).
         */
        val selectedMonth: kotlinx.datetime.YearMonth? = null,
        /** A-02 FIX-MONTH-2: see [Editing.selectedMonth]. */
        val selectableMonths: List<kotlinx.datetime.YearMonth> = emptyList(),
        /** A-02 FIX-MONTH-2: see [Editing.selectedMonth]. */
        val monthlyActivity: com.unifiedledger.application.MonthlyActivity? = null,
    ) : P503AppState

    /**
     * P7-03.C (D-145; spec section 6.1): the read-only transaction detail state. Reached only
     * from a HOME flow row (仅 HOME 行可达). Carries the overview to return to (so
     * CloseTransactionDetail/Back restore the exact tab, month cursor and monthly payload, C03),
     * the origin tab, the requested transaction id and the host-resolved typed detail payload
     * ([com.unifiedledger.application.TransactionDetailResult]: Success/NotFound/InvalidState/
     * Unavailable). Zero edit entries: the page renders read-only data only (本批不做编辑 UI).
     */
    data class TransactionDetail(
        val overview: OverviewEmpty,
        val originTab: P503Tab,
        val transactionId: TransactionId,
        val detail: com.unifiedledger.application.TransactionDetailResult,
    ) : P503AppState

    /**
     * P7-04.C (D-146; spec sections 6.1/6.2): the import candidate detail state. Reached only
     * from an IMPORT list row (仅 IMPORT 清单行可达). Carries the overview to return to (so
     * CloseImportCandidateDetail/Back restore the exact IMPORT tab with its list payload,
     * selection set and — written back on close — the same candidate's decision draft, spec
     * section 6.2 表单字段保留), the requested candidate id, the host-resolved typed detail and
     * duplicate-comparison payloads, the pure decision-form draft, the in-flight duplicate-review
     * marker (期间禁重复提交) and the typed notice banner.
     */
    data class ImportCandidateDetail(
        val overview: OverviewEmpty,
        val candidateId: com.unifiedledger.application.ImportCandidateId,
        val detail: com.unifiedledger.application.ImportCandidateDetailResult,
        val duplicates: com.unifiedledger.application.ImportDuplicateReviewsResult,
        val form: ImportDecisionDraft,
        /** True while the host's duplicate-review use case call is in flight (duplicate submits absorbed). */
        val reviewPending: Boolean = false,
        val notice: ImportReviewNotice? = null,
    ) : P503AppState

    /**
     * P7-04.D (D-146; spec sections 3.2.3/6.1): the 授权快照确认页. Reached from the IMPORT
     * overview (a non-empty selection) or from the candidate detail (携详情决策—— the detail's
     * decision draft is written back into the carried overview on entry, SPEC:283). Pure
     * presentation of the carried overview's checked items, their decision-field summaries and
     * the R-10 per-item submission semantics; the authorization action carries the host-sampled
     * LedgerClock instant and the per-item requestIds into [ImportBatchSubmitting]. Cancel/Back
     * return to the exact preserved overview (保留勾选集与清单).
     */
    data class ImportBatchConfirm(
        val overview: OverviewEmpty,
    ) : P503AppState

    /**
     * P7-04.D (D-146; spec sections 3.2.3/3.3.2/6.1): the per-item dispatch state. Carries the
     * authorization snapshot — [confirmedAt] is the ONE LedgerClock sample of the authorization
     * action (Q09.4; reused by every item through `explicitConfirmedAt`, by the resume
     * continuation and by the unknown-item replay) and [items] the per-item requestIds minted
     * once for this intent (不换 ID). An Unknown item outcome sets [dispatchPaused]; the only
     * exits are the explicit Resume/Abandon affordances (and the automatic leave once every
     * item is terminal — system back is intercepted, 沿既有 Submitting 语义).
     */
    data class ImportBatchSubmitting(
        val overview: OverviewEmpty,
        val confirmedAt: String,
        val items: List<ImportBatchSubmittingItem>,
        /** True once an Unknown outcome paused the loop (派发暂停； Resume clears it, Abandon leaves). */
        val dispatchPaused: Boolean = false,
    ) : P503AppState

    /**
     * P7-05.B (D-156; spec sections 3.5/4.4): the independent correction surface. Reached only
     * from the read-only transaction detail (DP-12: 首切片仅详情入口, list rows keep navigating
     * only). [origin] is the host-resolved old-value snapshot and carries the CAS token
     * (`currentVersionId`) the commit must send; [draft] is the pure correction form (the frozen
     * first-slice field set); [preview] is the pure difference preview (a preview is never commit
     * permission, spec section 3.2); [requestId] is minted by the host on the explicit confirm
     * (never by the reducer); [submitting] is the per-operation marker (提交中不重入) and is also
     * the `UnknownCommit` discipline's surface — a lost commit leaves the marker set with no
     * automatic retry, no requestId swap and no accepted leave. The overview is carried so every
     * exit restores the exact tab, month cursor and monthly payload.
     */
    data class TransactionEdit(
        val overview: OverviewEmpty,
        val origin: TransactionEditOrigin,
        val draft: TransactionCorrectionDraft = TransactionCorrectionDraft(),
        val preview: TransactionEditPreview? = null,
        val requestId: RequestId? = null,
        val submitting: Boolean = false,
        val notice: P705Notice? = null,
        /**
         * P7-05 (V-19; D-173): the last manual re-check outcome of a lost commit. Default [NONE]
         * keeps every pre-existing construction site source-compatible; [P705CommitCheckOutcome]
         * documents the frozen meaning (NONE = in flight / not yet checked, ABSENT and UNAVAILABLE
         * stay re-checkable).
         */
        val checkOutcome: P705CommitCheckOutcome = P705CommitCheckOutcome.NONE,
    ) : P503AppState

    /**
     * P7-05.C (D-156; spec sections 3.3/4.4): the void confirmation page. Reached only from the
     * read-only transaction detail (DP-12). [reason] is the mandatory typed reason form (DP-11);
     * [requestId] is host-minted on the explicit confirm; [submitting] is the per-operation
     * marker and the `UnknownCommit` discipline's surface. The page presents the impact statement
     * (该交易将从月度与流水中移除、可在回收站恢复).
     */
    data class VoidConfirm(
        val overview: OverviewEmpty,
        val transactionId: TransactionId,
        val reason: VoidReasonDraft = VoidReasonDraft(),
        val requestId: RequestId? = null,
        val submitting: Boolean = false,
        val notice: P705Notice? = null,
        /** P7-05 (V-19; D-173): see [TransactionEdit.checkOutcome]. */
        val checkOutcome: P705CommitCheckOutcome = P705CommitCheckOutcome.NONE,
    ) : P503AppState

    /**
     * P7-05.C (D-156; spec sections 3.4/4.4): the recycle-bin list plus its nested restore
     * confirmation. [rows] is the host-resolved authoritative read projection
     * ([com.unifiedledger.application.RecycleBinResult]); [restore] is the nested
     * [RestoreConfirm] sub-state (`null` when the bin is showing its list). The bin is reached
     * from the effective surfaces (its entry affordance) and returns to the preserved overview;
     * the list order is the query's frozen `(void time DESC, transaction_id ASC)` total order,
     * so the reducer never re-sorts.
     */
    data class RecycleBin(
        val overview: OverviewEmpty,
        val rows: com.unifiedledger.application.RecycleBinResult,
        val restore: RestoreConfirm? = null,
    ) : P503AppState

    /**
     * P7-06 06.B (D-177; spec sections 3/6): the backup-export surface. Reached from the HOME
     * overview entry affordance when the composition root wired the export use case. [overview] is
     * carried so every exit restores the exact tab, month cursor and monthly payload. [password] is
     * the in-memory-only password draft — it is NEVER persisted, logged or carried into any
     * diagnostic, and the reducer clears it when the surface leaves. [running] is the per-operation
     * marker (提交中不重入/不得离开, the P7-05 Submitting discipline); [outcome] is the last landed
     * typed result ([BackupExportResult]).
     */
    data class BackupExport(
        val overview: OverviewEmpty,
        val password: String = "",
        val running: Boolean = false,
        val outcome: BackupExportResult? = null,
    ) : P503AppState

    /**
     * P5-04.3: carries the flow context so the host can run a read-only commit-status
     * check and the flow can leave via Recovered/RequestIdentityConflict; nullable fields
     * follow the InfrastructureFailure SUBMISSION precedent.
     */
    data class UnknownCommit(
        val draft: TypedEntryDraft? = null,
        val requestId: RequestId? = null,
        val overview: LedgerCurrentState? = null,
        val originTab: P503Tab = P503Tab.HOME,
        val lastCheckOutcome: UnknownCommitCheckOutcome = UnknownCommitCheckOutcome.NONE,
        /** A-02 FIX-PIN-2: see [Editing.pinnedTargets]. */
        val pinnedTargets: Set<EntryPinTarget> = emptySet(),
        /** A-02 FIX-MONTH-2: see [Editing.selectedMonth]. */
        val selectedMonth: kotlinx.datetime.YearMonth? = null,
        /** A-02 FIX-MONTH-2: see [Editing.selectedMonth]. */
        val selectableMonths: List<kotlinx.datetime.YearMonth> = emptyList(),
        /** A-02 FIX-MONTH-2: see [Editing.selectedMonth]. */
        val monthlyActivity: com.unifiedledger.application.MonthlyActivity? = null,
    ) : P503AppState

    data object Recovered : P503AppState
}

/**
 * P5-04.1 overview tabs. Tab selection is part of the shared reducer state, so an
 * authoritative refresh can always return the overview to the home tab.
 *
 * P7-04.C (D-146, R-13): the frozen three-tab contract (D-122) is extended by [IMPORT] as the
 * fourth tab; the bottom-bar layout semantics are unchanged, only the tab item is added.
 */
enum class P503Tab {
    HOME,
    ACCOUNTS,
    ANALYSIS,
    IMPORT,
}

enum class InfrastructureFailureContext {
    READ,
    SUBMISSION,
}

/**
 * P5-04.3 unknown-commit check outcome: NONE means a check is in flight; ABSENT and
 * UNAVAILABLE stay actionable with a manual re-check and never claim success or failure.
 */
enum class UnknownCommitCheckOutcome {
    NONE,
    ABSENT,
    UNAVAILABLE,
}

/**
 * P7-05 (V-19; D-173): the outcome of a manual re-check of a lost correction/void/restore commit.
 * Mirrors [UnknownCommitCheckOutcome]: [NONE] means no check has resolved yet (a check may be in
 * flight, and the surface is re-checkable); [ABSENT] and [UNAVAILABLE] mean the read-only resolve
 * found no row / could not read, so the surface keeps its submitting marker, stays unknown and
 * remains re-checkable. A determinate hit never stores a marker: it lands as the existing result
 * event and leaves the surface (D-173's gap is the missing in-session re-check affordance, not a
 * second result family).
 */
enum class P705CommitCheckOutcome {
    NONE,
    ABSENT,
    UNAVAILABLE,
}

/**
 * P7-02.A E-2: retained intent for "record again". The host holds this in memory across
 * Submitting/UnknownCommit/Recovered; it is injected into [P503AppState.OverviewEmpty] through
 * [P503UiEvent.RefreshResult.retainedIntent] after a determinate success and is never persisted
 * across Exit or sessions.
 */
data class RetainedEntryIntent(
    val type: EntryType,
    val amountText: String,
    val paymentAccountId: AccountId?,
    val categoryId: CategoryId?,
    val note: String,
    val occurredAt: Instant?,
    val originTab: P503Tab,
)

/**
 * P702SPEC-03: the minimal counterparty directory form (create; rename one existing object).
 * No delete and no directory management screen — this only completes the L-1 invocation path
 * from the LEND/COLLECT editors.
 */
sealed interface CounterpartyDialog {
    data class Create(
        val nameText: String = "",
    ) : CounterpartyDialog

    data class Rename(
        val counterpartyId: CounterpartyId,
        val currentName: String,
        val nameText: String,
    ) : CounterpartyDialog
}

/**
 * P7-02.D E-3: the exact expression evaluation result shown by the calculator. A valid preview
 * carries the exact minor units and the display text at the currency precision; an invalid one
 * carries the typed rejection code of the section 5.3 table.
 */
sealed interface ExpressionPreview {
    data class Valid(
        val minorUnits: Long,
        val displayText: String,
    ) : ExpressionPreview

    data class Invalid(
        val code: EntryExpressionCode,
    ) : ExpressionPreview
}

/**
 * The expense draft's former name, kept as a source-compatible alias so pre-P7-02 constructor
 * call sites (tests, legacy reducers) keep compiling; [ExpenseDraft] is the sealed
 * [TypedEntryDraft] EXPENSE subclass and is value-compatible with the old shape.
 */
typealias ManualExpenseDraft = ExpenseDraft
