package com.unifiedledger.ui

import com.unifiedledger.application.BudgetMonthViewResult
import com.unifiedledger.application.CatalogSnapshotView
import com.unifiedledger.application.EntryExpressionCode
import com.unifiedledger.application.EntryPinTarget
import com.unifiedledger.application.EntryType
import com.unifiedledger.application.ExpenseDraft
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.MerchantMonthlyAccumulationResult
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.TagMerchantFilterCondition
import com.unifiedledger.application.TagMerchantFilterCursor
import com.unifiedledger.application.TagMerchantFilterEntry
import com.unifiedledger.application.TagMerchantFilterFailureCode
import com.unifiedledger.application.TypedEntryDraft
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.BudgetScope
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CounterpartyId
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.TransactionId
import kotlinx.datetime.YearMonth
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
    /**
     * P5-03 platform-startup-concluded ENTRY state (the surface rendered between the startup gate
     * reaching Ready and the initial authoritative load landing on [OverviewEmpty]).
     *
     * P7-09 09.0 fix (P1-A; spec sections 2.2/3/4; D-225 Q19): the ENTRY surface carries the SAME
     * single pending inbound-navigation slot the routing-table surfaces carry. Before this fix
     * `Ready` was a field-less `data object`, so a cold-start share (buffered in the composition
     * root's channel and drained on subscribe, while the shared host is still on this entry
     * surface) was routed into the `else ->` branch of [decideExternalNavigation] and the slot
     * write was ABSORBED — the request was silently dropped, exactly the defect 09.0 exists to
     * remove. Because the spec FREEZES the slot in the reducer (not a host field), the entry
     * surface must be able to hold it: a cold-start share now lands a VISIBLE Busy slot here and
     * is retained until the surface advances. The `null` default keeps every pre-P7-09
     * construction and copy site compiling.
     */
    data class Ready(
        /**
         * P7-09 09.0 (spec sections 2.2/3): the single pending inbound-navigation slot; see
         * [OverviewEmpty.pendingExternalNavigation]. Non-null renders the visible Busy/待处理 result
         * on the entry surface, so a cold-start share is never silently absorbed.
         */
        val pendingExternalNavigation: PendingExternalNavigation? = null,
        /**
         * P7-09 09.0 / T4 (spec sections 1.7/2.2; P2-2 fix): the visible Busy/待处理 result of a
         * blocked host operation (a second file pick while one intake pipeline runs). Carried by
         * EVERY slot surface (not only [OverviewEmpty]), so a refused second pick is visible on the
         * very surface the user is on rather than dropped.
         */
        val externalNavigationBusy: ExternalNavigationBusy? = null,
    ) : P503AppState

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
        /**
         * P7-07 07.D (D-184; spec section 5): the last landed budget-month read for the
         * effective month (ONE bounded read folded into every configured scope). A typed
         * failure is carried as-is — the budget region renders the explicit failure copy and
         * never a zero; `null` before the first landing. The optional field keeps every
         * pre-P7-07 constructor site compiling.
         */
        val budgetView: BudgetMonthViewResult? = null,
        /**
         * P7-09 09.0 (spec sections 2.2/3; D-225): the single pending inbound-navigation slot
         * (at most one held request; no unbounded queue, no silent drop). Non-null renders the
         * visible Busy/待处理 result. Additive with a `null` default so every pre-P7-09
         * constructor and copy site keeps compiling; the slot carries no URI, file name or
         * amount.
         */
        val pendingExternalNavigation: PendingExternalNavigation? = null,
        /**
         * P7-09 09.0 / T4 (spec sections 1.7/2.2): the visible Busy/待处理 result of a blocked
         * host operation (the second file pick that arrived while one intake pipeline ran — the
         * former silent `-> Unit` drop). Additive with a `null` default; renders a strip with
         * zero formal effect.
         */
        val externalNavigationBusy: ExternalNavigationBusy? = null,
        /**
         * P8-00 shell (D-301; spec section 1.1): the state-internal import-surface route. When
         * true the overview renders the existing import projection ([importReview]) instead of
         * the selected tab's content — the route the drawer's 导入账单 entry and the launcher
         * shortcut's `OpenImportTab` leg both take ([P503UiEvent.OpenImportSurface]); a bottom
         * bar tap (`SelectTab`) leaves the surface and returns to the chosen tab. The import
         * events and projection semantics are unchanged (V-2). Additive with a `false` default,
         * so every pre-P8 constructor and copy site keeps compiling.
         */
        val importSurface: Boolean = false,
        /**
         * P8-00 b3 (V-5 返回保滚动位 mechanism): the ORIGIN list's scroll position captured when
         * the user left the overview for a secondary surface (HOME flow list → transaction
         * detail; ACCOUNTS account list → account flow), restored when the exact overview comes
         * back. The mechanism is state-internal (reducer-carried, the import-surface pattern):
         * the entry event (`SelectTransaction`/`SelectAccountFlow`) samples the position the
         * screen passes in, and the close transitions (`CloseTransactionDetail`/
         * `CloseAccountFlow`/`Back`) return it with the preserved overview. There is deliberately
         * NO async write-back hop — the reducer never carries a pending-scroll slot — so a
         * late/stale scroll write-back cannot exist; the one-shot consume
         * ([P503UiEvent.ListScrollRestored]) clears the field after the screen applied it, so a
         * later unrelated re-entry never replays a stale position. `null` = no position captured
         * (every pre-b3 path, no restore).
         */
        val originListScroll: ListScrollPosition? = null,
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
        /** P7-09 09.0 (spec sections 2.2/3): the single pending inbound-navigation slot; see [OverviewEmpty.pendingExternalNavigation]. */
        val pendingExternalNavigation: PendingExternalNavigation? = null,
        /** P7-09 09.0 / T4 (P2-2 fix): the visible Busy/待处理 result; see [OverviewEmpty.externalNavigationBusy]. */
        val externalNavigationBusy: ExternalNavigationBusy? = null,
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
        /** P7-09 09.0 (spec sections 2.2/3): the single pending inbound-navigation slot; see [OverviewEmpty.pendingExternalNavigation]. */
        val pendingExternalNavigation: PendingExternalNavigation? = null,
        /** P7-09 09.0 / T4 (P2-2 fix): the visible Busy/待处理 result; see [OverviewEmpty.externalNavigationBusy]. */
        val externalNavigationBusy: ExternalNavigationBusy? = null,
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
        /** P7-09 09.0 (spec sections 2.2/3): the single pending inbound-navigation slot; see [OverviewEmpty.pendingExternalNavigation]. */
        val pendingExternalNavigation: PendingExternalNavigation? = null,
        /** P7-09 09.0 / T4 (P2-2 fix): the visible Busy/待处理 result; see [OverviewEmpty.externalNavigationBusy]. */
        val externalNavigationBusy: ExternalNavigationBusy? = null,
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
        // P7-08 08.D (spec section 3.5): the read-only annotation history the host read under the
        // SAME lease as the detail payload. `null` = the read is unavailable (unwired port or
        // unreadable) and the detail renders no history block; an EMPTY list is the honest
        // "never annotated". Read-only display only — zero edit entries ride this field.
        val annotationHistory: List<com.unifiedledger.application.TransactionAnnotationHistoryEntry>? = null,
        /**
         * P8-00 b3 (V-6): the read-only financial version history the host read under the SAME
         * lease as the detail (the annotationHistory twin). `null` = the read is unavailable
         * (unwired port, unreadable, or the surface was never opened after the landing) and the
         * detail renders no version-history block; a Success carries the full chain. Read-only
         * display only — zero action entries ride this field (the D-223 precedent).
         */
        val versionHistory: com.unifiedledger.application.TransactionVersionHistoryResult? = null,
    ) : P503AppState

    /**
     * P8-00 b3 (V-5; D-301 spec sections 1.3/2.6): the read-only account-flow secondary surface,
     * reached only from an asset-screen account row (仅资产行可达). [overview] is carried so every
     * exit restores the exact tab/month/payload (the preserved-overview discipline, C03).
     *
     * The flow rows come from the EXISTING whole-ledger keyset page source
     * ([com.unifiedledger.application.QueryLedgerFlowPage]); the host filters each page by the
     * account's postings in memory (当前 200 行/页约定内， no data-layer change) and lands typed
     * pages through [P503UiEvent.AccountFlowMorePageLanded]. [loadedRows] accumulates the
     * filtered rows; [hasMoreWholeLedgerPages] mirrors the whole-ledger cursor (`null` = the
     * whole-ledger walk ended — the honest end, never a fabricated total). No transaction-count
     * claim is ever displayed (no per-account total exists); the loaded count renders as-is
     * (「已加载 N 笔」). [rowsLoading]/[rowsLoadFailed]/[pageRequestToken] are the single-flight,
     * fail-closed and late-write-guard triple, the [TagMerchantFilter] page-token precedent.
     *
     * [pageRequestToken] is the monotonic request identity of the more-page read: every
     * RequestAccountFlowMorePage dispatch advances it, the host presents it back on the landing,
     * and the reducer applies a landing only when its token is still current — a read superseded
     * by a newer request (or duplicated) can never append. Pure bookkeeping, never rendered.
     *
     * The surface's own scroll position rides [listScroll]: it is written by the reducer's
     * synchronous `SelectReconciliationQueue` transition (the sampled position from the event) and
     * consumed once by `ListScrollRestored`, exactly like the accounts list — so 返回 from the
     * reconciliation queue lands on the same row (V-10 返回保留滚动位).
     */
    data class AccountFlow(
        val overview: OverviewEmpty,
        val accountId: AccountId,
        /** The header副行 the host resolved at open time (「名称 · 类型 · 币种」). */
        val balanceLine: String,
        /** The account's exact display balance (minor units + currency) for the header大字. */
        val balanceMinorUnits: Long,
        val balanceCurrency: com.unifiedledger.domain.CurrencyUnit,
        /** Filtered rows accumulated so far, in display order (the host's frozen sort). */
        val loadedRows: List<com.unifiedledger.application.LedgerEntryRow>,
        /**
         * The whole-ledger walk's continuation cursor (`null` = the whole-ledger walk ended; with
         * [rowsLoadFailed] also true, `null` = the FIRST read failed and its retry re-runs the
         * first page — the F-Q1/F-Q2 fix round).
         */
        val nextWholeLedgerCursor: com.unifiedledger.application.LedgerCurrentStateReadPort.LedgerFlowCursor?,
        /** True while a more-page read is in flight (a duplicate request is absorbed, 一次一页). */
        val rowsLoading: Boolean = false,
        /**
         * The last flow-read failure, fail-closed: a more-page failure keeps the loaded rows
         * visible beside the retry, and the FIRST page's typed Unavailable lands here too
         * (P8-00 b3 fix round F-Q1) — an unavailable first read must never degrade into the
         * 「该账户暂无流水」 empty copy (R-Q06-4).
         */
        val rowsLoadFailed: Boolean = false,
        /** The monotonic request identity of the more-page read (see the state KDoc). */
        val pageRequestToken: Long = 0L,
        /**
         * P8-01a i3a (V-10 返回保留滚动位): the account-flow position sampled when the queue was
         * opened, consumed once by `ListScrollRestored` after the screen applied it.
         */
        val listScroll: ListScrollPosition? = null,
    ) : P503AppState {
        /** The honest loaded count: exactly the rows on screen, never a ledger-wide total. */
        val loadedCount: Int get() = loadedRows.size

        /** Whether more whole-ledger pages may still carry rows for this account. */
        val hasMoreWholeLedgerPages: Boolean get() = nextWholeLedgerCursor != null
    }

    /**
     * P8-01a i3a (D-308 items 2/6/8; spec sections 2.2/2.3): the account reconciliation queue — the
     * 「账户对账」 surface reached from the account-flow header (entry A).
     *
     * The queue enumerates the account's reconcilable legs from ONE keyset read per page
     * ([com.unifiedledger.application.P801_ACCOUNT_RECONCILIATION_PAGE_SIZE], the frozen
     * `occurred_at DESC, posting_id ASC` order) and shows the per-(currency, status) totals beside
     * them. TWO INDEPENDENT FAILURE FLAGS (D-308 / spec section 2.3, V-9): [rowsLoadFailed] keeps the
     * loaded rows visible with a retry, [summaryLoadFailed] shows the totals' own failure — a failed
     * aggregate never clears the queue and never degrades into a `0.00` total.
     *
     * [checkedExpanded] is the 已核对 partition's disclosure state: collapsed (false) is the
     * default, and the partition never mixes into the unchecked list above it (spec section 2.3).
     * [pageRequestToken] is the single-flight late-write gate of the more-page read (the
     * [AccountFlow.pageRequestToken] precedent); [listScroll] is this surface's own restore slot —
     * written by the detail's open event, consumed once by `ListScrollRestored`.
     */
    data class ReconciliationQueue(
        /**
         * The account flow the entry A action was taken from — the ONLY legal back target of the
         * queue (F13: the type no longer admits an unreachable destination).
         */
        val returnTo: AccountFlow,
        val accountId: AccountId,
        /** The header副行 the host resolved at open time (「名称 · 类型 · 币种」). */
        val accountLine: String,
        val rows: List<com.unifiedledger.application.P801AccountReconciliationQueueRow>,
        val nextCursor: com.unifiedledger.application.P801AccountReconciliationCursor?,
        val totals: List<com.unifiedledger.application.P801AccountReconciliationStatusTotal>,
        /** The page read failed (first page or a more-page); the loaded rows stay visible. */
        val rowsLoadFailed: Boolean = false,
        /** The summary read failed; the rows stay visible and no zero total is shown (V-9). */
        val summaryLoadFailed: Boolean = false,
        val rowsLoading: Boolean = false,
        val pageRequestToken: Long = 0L,
        val checkedExpanded: Boolean = false,
        val listScroll: ListScrollPosition? = null,
    ) : P503AppState {
        /** The unchecked partition (the queue's main list, spec section 2.3). */
        val uncheckedRows: List<com.unifiedledger.application.P801AccountReconciliationQueueRow>
            get() = rows.filter { row -> row.status != com.unifiedledger.application.P408ReconciliationStatus.CHECKED }

        /** The 已核对 partition, collapsed by default (spec section 2.3). */
        val checkedRows: List<com.unifiedledger.application.P801AccountReconciliationQueueRow>
            get() = rows.filter { row -> row.status == com.unifiedledger.application.P408ReconciliationStatus.CHECKED }
    }

    /**
     * P8-01a i3a (D-308 items 2/3/7/10; spec sections 2.2/2.4/2.5/2.6): the candidate/confirm/audit
     * surface of ONE anchor. Entry A anchors on a queue leg ([anchorPostingId]); entry B anchors on
     * an import candidate's evidence ([anchorEvidenceId], spec section 2.2 entry B), which has no leg
     * until the matcher proposes one.
     *
     * [proposals] is the frozen matcher's own result (never a re-implementation); [audit] is the
     * spec section 2.6 read-back of [anchorPostingId] (null in evidence-focused mode, where no leg is
     * chosen yet). [selectedCandidate] is the O-3 human decision: `null` until the user picks one —
     * the confirm action is unavailable while it is null, so an `AMBIGUOUS` result can never be
     * confirmed by accident, and a `PROPOSED_MATCH` still requires the explicit confirm step.
     *
     * [bindTarget] is the account the user declared this evidence belongs to (entry A: the anchor
     * leg's account; entry B: an explicit pick from [bindAccounts]). The bind action is unavailable
     * while it is null, and the irreversibility warning precedes it in the screen (spec section 2.4).
     * [binding]/[confirming] are the single-flight markers: while either is set the surface keeps
     * itself (提交中不得离开) and the corresponding request is absorbed.
     *
     * WHAT THE BIND STEP READS (spec section 2.4). Entry A carries the leg ([anchorLeg]) and the
     * evidence of that account/window that still needs a bind ([bindableEvidence]); [bindEvidenceId]
     * is the row the user picked there (the target stays the leg's account). Entry B carries the
     * anchor evidence row ([anchorEvidence]) so the bind step is decided by that row's projection
     * state — never by whether the proposal read happened to succeed — and the REJECTED code is on
     * the row itself. Browsing never writes: the bind runs only on the explicit action.
     */
    data class ReconciliationDetail(
        /** The surface back returns to — the queue (entry A) or the preserved overview (entry B). */
        val returnTo: ReconciliationBackTarget,
        val anchorPostingId: com.unifiedledger.domain.PostingId?,
        val anchorEvidenceId: String?,
        /** The account whose queue/audit this is; null until an evidence-focused anchor is bound. */
        val accountId: AccountId?,
        val bindTarget: AccountId?,
        val bindAccounts: List<ReconciliationAccountChoice>,
        val proposals: com.unifiedledger.application.P801CandidateProposalResult?,
        val audit: com.unifiedledger.application.P801ReconciledLegAuditResult?,
        val selectedCandidate: ReconciliationSelection? = null,
        val binding: Boolean = false,
        val confirming: Boolean = false,
        val notice: ReconciliationDetailNotice? = null,
        /** Entry A: the queue leg this surface anchors on (re-read on a bind's candidate refresh). */
        val anchorLeg: com.unifiedledger.application.P801AccountReconciliationQueueRow? = null,
        /** Entry A: the evidence of this account/window that still needs an explicit bind. */
        val bindableEvidence: com.unifiedledger.application.P801BindableEvidenceResult? = null,
        /** The evidence row the user chose to bind (entry A: a bindable row; entry B: the anchor). */
        val bindEvidenceId: String? = null,
        /** Entry B: the anchor evidence row (the projection state and rejection code live here). */
        val anchorEvidence: com.unifiedledger.application.P801ReconciliationCandidateEvidence? = null,
    ) : P503AppState

    /**
     * One account the bind step can target (spec section 2.4: the explicit "this import row belongs
     * to this account" decision). The host projects the catalog's reconcilable accounts; the name is
     * the display text, never a key.
     */
    data class ReconciliationAccountChoice(
        val accountId: AccountId,
        val name: String,
    )

    /**
     * P8-01a i3a (spec section 2.5): the queue + totals payload the host re-reads after a
     * `Linked`/`NoChange` confirmation, so the queue the detail returns to already reflects the
     * write instead of showing the pre-confirmation state (the queue and the summary are two
     * independent reads, so both ride the same refresh — a failed one keeps its own failure flag).
     */
    data class ReconciliationQueueRefresh(
        val page: com.unifiedledger.application.P801AccountReconciliationPageResult,
        val summary: com.unifiedledger.application.P801AccountReconciliationSummaryResult,
    )

    /**
     * The O-3 human decision an `AMBIGUOUS` result requires (spec section 2.4): which proposal's
     * which matcher candidate the user picked. Both indices address the payload the host read
     * ([ReconciliationDetail.proposals]); the reducer validates them, so a stale pick can never
     * confirm a candidate that is no longer offered.
     */
    data class ReconciliationSelection(
        val proposalIndex: Int,
        val candidateIndex: Int,
    )

    /**
     * The typed notice of the candidate/confirm/audit surface (spec sections 2.4/2.5). Every variant
     * renders frozen copy from the spec; [Rejected] and [BindRejected] keep the raw infrastructure
     * code token beside the readable label (the `p705NoticeText` precedent), and the two
     * `UnknownResult` variants are the spec section 2.5 「结果未知，重新进入核对」 case — the call
     * threw, so nothing is claimed about what was written and re-entry decides by authoritative
     * re-read (no result-unknown row is ever persisted; D-308 / i3a D2).
     */
    sealed interface ReconciliationDetailNotice {
        /**
         * `Accepted`: the link and its reconciliation rows were written. [queueRefreshed] follows
         * the ACTUAL return target (spec section 2.5 / F10): entry A re-reads the queue page and the
         * totals, entry B only has the audit to refresh, so the success copy never claims a refresh
         * that did not run.
         */
        data class Linked(
            val queueRefreshed: Boolean,
        ) : ReconciliationDetailNotice

        /** `NoChange`: identical replay — success, and nothing was written twice. */
        data object NoChange : ReconciliationDetailNotice

        data class Rejected(
            val code: String,
        ) : ReconciliationDetailNotice

        data class Refused(
            val reason: com.unifiedledger.application.P801ConfirmLinkRefusal,
            /** The REJECTED projection's own code, when the evidence row carried one (F2). */
            val rejectionCode: String? = null,
        ) : ReconciliationDetailNotice

        /** The confirm call threw: the outcome is unknown and no receipt exists. */
        data object UnknownResult : ReconciliationDetailNotice

        /**
         * The ledger runtime was not Ready when the write was attempted: nothing ran and nothing
         * was written (the owner's readiness gate refused BEFORE the use case), so this is a
         * definitive not-written notice — never the unknown-result one.
         */
        data object RuntimeNotReady : ReconciliationDetailNotice

        /** The bind wrote the `READY` projection (a derived write with zero accounting effect). */
        data object Bound : ReconciliationDetailNotice

        /** The bind replay hit `NoChange`; no second projection row exists. */
        data object BindNoChange : ReconciliationDetailNotice

        data class BindRejected(
            val code: String,
        ) : ReconciliationDetailNotice

        data class BindRefused(
            val reason: com.unifiedledger.application.P801BindEvidenceRefusal,
        ) : ReconciliationDetailNotice

        /** The bind call threw: the outcome is unknown. */
        data object BindUnknownResult : ReconciliationDetailNotice
    }

    /**
     * P7-04.C (D-146; spec sections 6.1/6.2): the import candidate detail state. Reached only
     * from an import-review list row (仅导入清单行可达 — P8-00: the review rides the
     * state-internal importSurface route, no IMPORT tab). Carries the overview to return to (so
     * CloseImportCandidateDetail/Back restore the exact overview — list payload, importSurface
     * route, selection set and — written back on close — the same candidate's decision draft,
     * spec section 6.2 表单字段保留), the requested candidate id, the host-resolved typed detail
     * and duplicate-comparison payloads, the pure decision-form draft, the in-flight
     * duplicate-review marker (期间禁重复提交) and the typed notice banner.
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
        /** P7-09 09.0 (spec sections 2.2/3): the single pending inbound-navigation slot; see [OverviewEmpty.pendingExternalNavigation]. */
        val pendingExternalNavigation: PendingExternalNavigation? = null,
        /** P7-09 09.0 / T4 (P2-2 fix): the visible Busy/待处理 result; see [OverviewEmpty.externalNavigationBusy]. */
        val externalNavigationBusy: ExternalNavigationBusy? = null,
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
     * P7-05 (D-228; spec sections 3/4.4 and 8.3): the transfer correction surface. Reached only from
     * the read-only transaction detail (DP-12) when the target's posting shape is a transfer one, so
     * it is the [TransactionEdit] sibling for a transfer-shaped transaction rather than a variant of
     * it: the frozen field set differs (seven fields, two accounts and a fee leg instead of
     * amount/category/funding) and the two faces must never share a draft.
     *
     * [origin] is the host-resolved old-value snapshot and carries the CAS token
     * (`currentVersionId`); [draft] is the pure seven-field form; [requestId] is minted by the host on
     * the explicit confirm (never by the reducer); [submitting] is the per-operation marker (提交中不重入)
     * and the `UnknownCommit` discipline's surface - a lost commit leaves the marker set with no
     * automatic retry, no requestId swap and no accepted leave. The overview is carried so every exit
     * restores the exact tab, month cursor and monthly payload.
     *
     * There is deliberately no `preview` field: [com.unifiedledger.ui.transferCorrectionPreview]
     * renders its seven lines in one pass, name tables included, and the surface calls it at render
     * time. The ordinary surface splits that work instead (the reducer stores raw texts, the
     * presentation injects names), so this is a deliberate fork: one implementation of the seven-field
     * rendering, at the cost of the preview not being assertable at reducer level. Both readings obey
     * the frozen rule that a preview is a display-only read and never commit permission.
     */
    data class TransferEdit(
        val overview: OverviewEmpty,
        val origin: TransferCorrectionOrigin,
        val draft: TransferCorrectionDraft = TransferCorrectionDraft(),
        val requestId: RequestId? = null,
        val submitting: Boolean = false,
        val notice: P705Notice? = null,
        /** P7-05 (V-19; D-173): see [TransactionEdit.checkOutcome]. */
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
     * P7-08 08.B-2 (D-221 §8 open item R-221-1; spec section 4.4): the annotation-edit surface for
     * an existing not-yet-voided transaction. Reached only from the read-only transaction detail
     * (the `OpenTransactionEdit` surface parallel). [overview] is carried so every exit restores
     * the exact tab/month/payload. [intent] carries the host-resolved CAS tokens
     * (`expectedAnnotationRevision` — sentinel 0 means no current annotation — and
     * `expectedCurrentVersionId`) plus the pending tags/merchant; it is the single source of the
     * commit payload, so the reducer never re-reads the ledger. [submitting] is the per-operation
     * marker (提交中不重入); [outcome] is the last surfaced four-state result — accepted / no-change
     * / rejected-with-code / conflict — and is never silently swallowed. The page is reachable only
     * when the facade wired the command AND the target is currently effective (spec section 3.2
     * conservative gate); the store re-checks and the voided case surfaces the typed rejection
     * rather than a faked success.
     */
    data class TransactionAnnotationEdit(
        val overview: OverviewEmpty,
        val intent: TransactionAnnotationEditIntent,
        val submitting: Boolean = false,
        val outcome: TransactionAnnotationEditOutcome? = null,
    ) : P503AppState

    /**
     * P7-06 06.B (D-177; spec sections 3/6): the backup-export surface. Reached from the HOME
     * overview entry affordance when the composition root wired the export use case. [overview] is
     * carried so every exit restores the exact tab, month cursor and monthly payload. [password] is
     * the in-memory-only password draft — it is NEVER persisted, logged or carried into any
     * diagnostic, and the reducer clears it when the surface leaves. [running] is the per-operation
     * marker (提交中不重入/不得离开, the P7-05 Submitting discipline); [outcome] is the last landed
     * typed result ([BackupExportResult]).
     *
     * P1-B fix (06.B review): [toString] is OVERRIDDEN to redact the password. The generated
     * data-class `toString()` would print the plaintext, and `unhandled` builds its ISE message
     * from `"$state"`; `Exit` on this state is a deliberate ISE, so without this override the
     * password could reach a platform log through the exception message — contradicting the
     * "never logged" discipline above and spec section 3.5. Only the password's presence is
     * reported (never its value or length).
     */
    data class BackupExport(
        val overview: OverviewEmpty,
        val password: String = "",
        val running: Boolean = false,
        val outcome: BackupExportResult? = null,
    ) : P503AppState {
        override fun toString(): String = "BackupExport(overview=$overview, password=${if (password.isEmpty()) "\"\"" else "<redacted>"}, running=$running, outcome=$outcome)"
    }

    /**
     * P7-06 06.D (D-182; spec sections 3/5.1/5.5/6): the restore CONFIRM & SWITCH surface. Reached
     * through the side drawer's 备份与恢复 carrier row — the row opens the backup/export carrier
     * face (P8-00 b2, 2026-11-08 user ruling), whose restore affordance issues
     * [P503UiEvent.OpenBackupRestore] to land here — when the composition root wired the restore
     * use cases; the direct-from-overview route on [OverviewEmpty] is kept for the family shape.
     * The former HOME overview entry affordance was removed in P8-00 b2 (the pure entry migration).
     * [overview] is carried so every exit restores the exact tab/month/payload (the export
     * surface's preserved-overview discipline). The password lives only in this in-memory draft —
     * NEVER persisted, logged or placed in a diagnostic; [toString] redacts it (and the token's
     * content) exactly like [BackupExport].
     *
     * Two phases share one surface: the PREFLIGHT (password in, typed [RestorePreflightResult]
     * out; a [RestorePreflightResult.PreviewReady] fills [preview] and [token]) and the CONFIRM
     * (the opaque token in, typed [BackupRestoreSwitchResult] out). [runningPreflight] and
     * [runningConfirm] are the per-operation markers (提交中不重入/不得离开, the P7-05 submitting
     * discipline); a confirm start clears the preflight banner and vice versa.
     */
    data class BackupRestore(
        val overview: OverviewEmpty,
        val password: String = "",
        val runningPreflight: Boolean = false,
        val preflightOutcome: RestorePreflightResult? = null,
        /** The 7-field preview summary (spec section 5.5); non-null exactly when a token is bound. */
        val preview: RestorePreflightSummary? = null,
        val token: RestorePreflightToken? = null,
        val runningConfirm: Boolean = false,
        val confirmOutcome: BackupRestoreSwitchResult? = null,
    ) : P503AppState {
        override fun toString(): String =
            "BackupRestore(overview=$overview, password=${if (password.isEmpty()) "\"\"" else "<redacted>"}," +
                " runningPreflight=$runningPreflight, preflightOutcome=$preflightOutcome," +
                " preview=${if (preview == null) "null" else "<summary>"}, token=${if (token == null) "null" else "<opaque>"}," +
                " runningConfirm=$runningConfirm, confirmOutcome=$confirmOutcome)"
    }

    /**
     * P7-06 06.D (D-182; spec section 3.8 with the composition-root session-terminal obligation):
     * the fail-closed face after a rollback that ALSO failed ([BackupRestoreSwitchResult.RecoveryRequired]).
     * SESSION-TERMINAL: the runtime owner is Closed or StartupError (the registered P3-2 finding —
     * a `RecoveryRequired` carries no runtime-outcome field, so the composition root must treat it
     * as session-terminal), every business surface is dead by construction, and this face offers no
     * continuation beyond the exit. The typed [cause] is the only content; no path, no secret.
     */
    data class RestoreSessionTerminal(
        val cause: BackupRestoreRecoveryCause,
    ) : P503AppState

    /**
     * P7-07 07.D (D-184; spec sections 3.4/4/5): the explicit budget-configuration surface.
     * Reached only from the analysis tab's budget region (the host resolves the scope's
     * current authority before opening). [overview] is carried so every exit restores the
     * exact tab/month/payload (the TransactionEdit/BackupExport preserved-overview discipline).
     * [submitting] is the per-operation marker (提交中不重入/不得离开, the P7-05 submitting
     * discipline); [outcome] is the last landed typed result. The request id is deliberately
     * NOT carried here: the 07.B claim-first protocol mints it inside the commit boundary
     * (an equivalent replay must return the ORIGINAL receipt), so unlike the correction flow
     * the host does not mint one.
     */
    data class BudgetConfig(
        val overview: OverviewEmpty,
        val scope: BudgetScope,
        val month: kotlinx.datetime.YearMonth,
        val revision: Long,
        val closed: Boolean,
        val limitMinorUnits: Long?,
        val limitText: String = "",
        val submitting: Boolean = false,
        val outcome: com.unifiedledger.application.BudgetCommandResult? = null,
        /**
         * P7-07 fix round (P3): the commit ran but its outcome could not be resolved (an
         * infrastructure escape; see [P503UiEvent.BudgetCommitUnknownLanded]). Distinct from
         * [outcome]: the surface shows the explicit 结果未知 banner and stays re-attemptable
         * (a landed first commit surfaces as the typed revision conflict on the retry).
         */
        val outcomeUnknown: Boolean = false,
    ) : P503AppState

    /**
     * P7-08 08.C (spec sections 5.1-5.5): the tag/merchant filter surface. Reached only from
     * the analysis tab's explicit entry. [overview] is carried so every exit restores the exact
     * tab/month/payload (the preserved-overview discipline). [condition] is the pure filter
     * condition (tags OR/AND + optional merchant); [accumulationMonth] is the merchant
     * accumulation's month (the analysis month it was opened from, shiftable).
     *
     * [loadedEntries] accumulates the landed keyset pages and is re-sorted by the frozen
     * display order on every landing; [hasMore] gates the 加载更多 affordance; [pageFailure] is
     * the last landed typed read failure (fail-closed — never an empty page rendered as "no
     * matches", spec section 5.3). [accumulation] is the last landed merchant accumulation for
     * [accumulationMonth]. The loading markers absorb duplicate request events (一次一页).
     *
     * A landing applies only when its condition fingerprint (and, for the accumulation, its
     * month) still matches this surface's live values AND its request token is still the
     * surface's current one — an old-condition or old-generation return can never overwrite the
     * page (spec section 5.3; the host gates the lease generation, the reducer gates the
     * fingerprint and the fix round's request token).
     */
    data class TagMerchantFilter(
        val overview: OverviewEmpty,
        val accumulationMonth: YearMonth,
        val condition: TagMerchantFilterCondition = TagMerchantFilterCondition(),
        val loadedEntries: List<TagMerchantFilterEntry> = emptyList(),
        val hasMore: Boolean = false,
        /** The last landed page's continuation cursor (null = no further page). */
        val nextCursor: TagMerchantFilterCursor? = null,
        val pageFailure: TagMerchantFilterFailureCode? = null,
        val pageLoading: Boolean = false,
        /**
         * Fix round (P3-1): the monotonic request identity of the page read. EVERY page-request
         * dispatch advances it (absorbed 一次一页 appends included), the host presents it back on
         * the landing, and the reducer applies a landing only when its token is still current — a
         * duplicate same-condition read or a read superseded by a newer request can never append
         * or merge. Pure bookkeeping: never rendered.
         */
        val pageRequestToken: Long = 0L,
        val accumulation: MerchantMonthlyAccumulationResult? = null,
        val accumulationLoading: Boolean = false,
        /** [pageRequestToken]'s accumulation twin (see [P503UiEvent.MerchantAccumulationLanded]). */
        val accumulationRequestToken: Long = 0L,
        /** The latest landed page's account catalog snapshot (the group fold's kind dispatch). */
        val entryCatalog: LedgerCatalog? = null,
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
        /** P7-09 09.0 (spec sections 2.2/3): the single pending inbound-navigation slot; see [OverviewEmpty.pendingExternalNavigation]. */
        val pendingExternalNavigation: PendingExternalNavigation? = null,
        /** P7-09 09.0 / T4 (P2-2 fix): the visible Busy/待处理 result; see [OverviewEmpty.externalNavigationBusy]. */
        val externalNavigationBusy: ExternalNavigationBusy? = null,
    ) : P503AppState

    data object Recovered : P503AppState
}

/**
 * P8-01a i3a F13: the two legal back targets of [P503AppState.ReconciliationDetail] — the queue it
 * was opened from (entry A) or the preserved overview (entry B). The previous `P503AppState` field
 * admitted every surface the reducer can be in, which is unreachable by construction; this sealed
 * target makes an illegal return destination unrepresentable and keeps the copy decisions (which
 * surface was actually refreshed) a property of the target, not of a cast.
 */
sealed interface ReconciliationBackTarget {
    data class Queue(
        val state: P503AppState.ReconciliationQueue,
    ) : ReconciliationBackTarget

    data class Overview(
        val state: P503AppState.OverviewEmpty,
    ) : ReconciliationBackTarget

    /** The carried state, for the ONE reducer transition that returns to it. */
    fun surface(): P503AppState =
        when (this) {
            is Queue -> state
            is Overview -> state
        }
}

/**
 * P8-00 b3 (V-5 返回保滚动位): one screen list's scroll position, sampled synchronously by the
 * screen at the moment the user leaves for a secondary surface and carried by the reducer state
 * (the import-surface state-internal pattern). [firstVisibleItemIndex] +
 * [firstVisibleItemScrollOffset] are the [androidx.compose.foundation.lazy.LazyListState] pair
 * exactly; a plain `verticalScroll` screen maps its [androidx.compose.foundation.ScrollState]
 * value onto (index = 0, offset = value) and restores with `scrollTo(offset)`. Pure value: no
 * state object, no coroutine — testable at reducer level without a Compose runtime.
 */
data class ListScrollPosition(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
)

/**
 * P5-04.1 overview tabs. Tab selection is part of the shared reducer state, so an
 * authoritative refresh can always return the overview to the home tab.
 *
 * P8-00 shell (D-301; spec section 1.1): the D-146 four-tab contract collapses back to three
 * tabs (HOME/ACCOUNTS/ANALYSIS) and [IMPORT] leaves the bottom-bar enum. The import surface
 * remains reachable as a state-internal overview route: [OverviewEmpty.importSurface] renders
 * the existing import projection instead of the tab content, entered by
 * [P503UiEvent.OpenImportSurface] (the drawer's 导入账单 entry and the launcher shortcut's
 * `OpenImportTab` leg) and left by [P503UiEvent.SelectTab] (a bottom-bar tap returns to the
 * chosen tab) — the import projection, its events and its semantics are unchanged (V-2).
 */
enum class P503Tab {
    HOME,
    ACCOUNTS,
    ANALYSIS,
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
 * P1-2 fix round, review P3: THE single source of truth for the surfaces on which a budget
 * month landing ([P503UiEvent.BudgetMonthLanded], [P503UiEvent.MonthlyActivityResult]'s
 * carried-overview application) may be APPLIED — the live overview plus the five surfaces
 * that hold a preserved [OverviewEmpty] and render no competing monthly region of their own.
 *
 * HONEST SUBSET (not "every surface that carries an OverviewEmpty"):
 * [ImportCandidateDetail], [ImportBatchConfirm], [ImportBatchSubmitting], [TransactionEdit],
 * [TransferEdit], [VoidConfirm], [TransactionAnnotationEdit] and [TagMerchantFilter] also carry
 * one, but a landing there is ABSORBED —
 * their own event families own those surfaces, and the budget region never renders on
 * them. REGISTERED NARROW RACE: a budget landing dispatched while the user has navigated
 * INTO one of those eight surfaces is silently dropped (the coordinator's guard already
 * stamped the request); the next budget trigger (effective-month change, SelectMonth,
 * config commit, a later P7-05/import arm) re-requests, and the region never renders there,
 * so no wrong amount is ever shown — only a possibly-stale payload on RETURN, which the
 * existing staleness semantics of the preserved-overview surfaces already accept.
 */
internal fun P503AppState.carriedBudgetMonthOverview(): P503AppState.OverviewEmpty? =
    when (this) {
        is P503AppState.OverviewEmpty -> this
        is P503AppState.BudgetConfig -> overview
        is P503AppState.TransactionDetail -> overview
        is P503AppState.RecycleBin -> overview
        is P503AppState.BackupExport -> overview
        is P503AppState.BackupRestore -> overview
        else -> null
    }

/**
 * The expense draft's former name, kept as a source-compatible alias so pre-P7-02 constructor
 * call sites (tests, legacy reducers) keep compiling; [ExpenseDraft] is the sealed
 * [TypedEntryDraft] EXPENSE subclass and is value-compatible with the old shape.
 */
typealias ManualExpenseDraft = ExpenseDraft
