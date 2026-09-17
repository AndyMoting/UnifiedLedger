package com.unifiedledger.ui

import com.unifiedledger.application.CatalogConsumerSession
import com.unifiedledger.application.CatalogSnapshotView
import com.unifiedledger.application.CounterpartyCommands
import com.unifiedledger.application.EntryPreferenceStore
import com.unifiedledger.application.ExecuteCatalogCommand
import com.unifiedledger.application.ExecuteManualEntrySubmission
import com.unifiedledger.application.ExecuteManualExpenseSubmission
import com.unifiedledger.application.ExecuteManualIncomeSubmission
import com.unifiedledger.application.ImportFileIntakePort
import com.unifiedledger.application.ImportIntakeSessionIdentity
import com.unifiedledger.application.ImportPlatformKind
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.LendingPositionReadPort
import com.unifiedledger.application.ManualExpenseOptionsProvider
import com.unifiedledger.application.ManualExpenseRequestIdSource
import com.unifiedledger.application.ManualIncomeOptions
import com.unifiedledger.application.ManualIncomeOptionsProvider
import com.unifiedledger.application.ManualIncomeRequestIdSource
import com.unifiedledger.application.ManualLendingOptions
import com.unifiedledger.application.ManualLendingOptionsProvider
import com.unifiedledger.application.ManualLendingRequestIdSource
import com.unifiedledger.application.ManualTransferOptions
import com.unifiedledger.application.ManualTransferOptionsProvider
import com.unifiedledger.application.ManualTransferRequestIdSource
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.ParseManualExpenseOccurredAt
import com.unifiedledger.application.QueryImportCandidateDetail
import com.unifiedledger.application.QueryImportDuplicateReviews
import com.unifiedledger.application.QueryImportDuplicateReviewsForSession
import com.unifiedledger.application.QueryImportReviewRows
import com.unifiedledger.application.QueryLedgerCurrentState
import com.unifiedledger.application.QueryLedgerEntryRows
import com.unifiedledger.application.QueryMonthlyActivity
import com.unifiedledger.application.QueryTransactionDetail
import com.unifiedledger.application.ResolveManualExpenseCommitStatus
import com.unifiedledger.application.ResolveManualIncomeCommitStatus
import com.unifiedledger.application.ResolveManualLendingCommitStatus
import com.unifiedledger.application.ResolveManualTransferCommitStatus
import com.unifiedledger.application.ReviewImportDuplicateCandidate
import com.unifiedledger.application.SummarizeLedgerActivity
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId

/**
 * P5-03 composition-root facade (spec section 4.7). Assembled by each platform composition
 * root and handed to the shared UI. The facade contains application-layer types only; the
 * UI never sees a driver, a database handle or SQL.
 *
 * P7-01.D (D-143) adds the catalog-management surface: [catalogSnapshot] is the current
 * authoritative projection (names replacing bare ids, including `catalogVersion`),
 * [executeCatalogCommand] is the nine-command entry point (it mints each command's request id
 * internally, so equivalent replay stays deterministic), and [refreshCatalog] reloads the
 * shared catalog session so options/reads/summaries continue from the same authoritative
 * version without a restart.
 *
 * P7-02.A extends the manual-entry surface: [submitEntry] is the typed EXPENSE/INCOME submit
 * entry point, [resolveIncomeCommitStatus] the income snapshot-aware resolver, and
 * [incomeOptionsProvider] the authoritative income option projection. The income request id
 * source [incomeRequestIdSource] stays independent from the expense source so no consumption
 * count is shared.
 *
 * When a [catalogSession] is injected (spec 6.2), [optionsProvider], [queryCurrentState] and
 * [summarizeActivity] are read through it, so `refreshCatalog()` reaches every consumer of the
 * authoritative catalog and not just the management projection. The `base*` parameters remain
 * as the construction-time fallback for pre-P7-01 composition sites (startup tests) that have
 * no session; product roots always inject the session.
 */
class P503LedgerFacade(
    val ledgerId: LedgerId,
    val currency: CurrencyUnit,
    val catalog: LedgerCatalog,
    val parseAmount: ParseManualExpenseAmount,
    // D-138: lenient occurred-at parser, injected alongside parseAmount (spec 3). The
    // default exists only so the Android startup test keeps constructing the facade
    // unchanged; product roots always inject the real instance.
    val parseOccurredAt: ParseManualExpenseOccurredAt = ParseManualExpenseOccurredAt(),
    baseOptionsProvider: ManualExpenseOptionsProvider,
    baseQueryCurrentState: QueryLedgerCurrentState,
    val resolveCommitStatus: ResolveManualExpenseCommitStatus,
    val submitExpense: ExecuteManualExpenseSubmission,
    val requestIdSource: ManualExpenseRequestIdSource,
    val ledgerClock: LedgerClock,
    baseSummarizeActivity: SummarizeLedgerActivity,
    // P7-02.A typed-entry surface; null/empty defaults keep legacy constructions valid.
    val submitEntry: ExecuteManualEntrySubmission? = null,
    val submitIncome: ExecuteManualIncomeSubmission? = null,
    val resolveIncomeCommitStatus: ResolveManualIncomeCommitStatus? = null,
    val incomeRequestIdSource: ManualIncomeRequestIdSource? = null,
    baseIncomeOptionsProvider: ManualIncomeOptionsProvider? = null,
    // P7-02.B transfer surface.
    val resolveTransferCommitStatus: ResolveManualTransferCommitStatus? = null,
    val transferRequestIdSource: ManualTransferRequestIdSource? = null,
    baseTransferOptionsProvider: ManualTransferOptionsProvider? = null,
    // P7-02.C lending surface.
    val resolveLendingCommitStatus: ResolveManualLendingCommitStatus? = null,
    val lendingRequestIdSource: ManualLendingRequestIdSource? = null,
    baseLendingOptionsProvider: ManualLendingOptionsProvider? = null,
    val lendingPositions: LendingPositionReadPort? = null,
    val counterpartyCommands: CounterpartyCommands? = null,
    // P7-02.D E-4 manual pin surface; null keeps legacy constructions valid (no pin UI).
    val entryPreferences: EntryPreferenceStore? = null,
    // P7-01.D catalog management surface; null/empty defaults keep legacy constructions valid.
    val catalogSnapshot: () -> CatalogSnapshotView? = { null },
    val executeCatalogCommand: ExecuteCatalogCommand? = null,
    val refreshCatalog: () -> Unit = {},
    // P7-03.C/D ledger-view read surface (D-145); null defaults keep legacy constructions valid
    // (no month card, no flow list, no detail). The product roots wire the session-built models,
    // so every query follows refreshCatalog onto the same authoritative catalog version.
    baseQueryLedgerEntryRows: QueryLedgerEntryRows? = null,
    baseQueryMonthlyActivity: QueryMonthlyActivity? = null,
    baseQueryTransactionDetail: QueryTransactionDetail? = null,
    catalogSession: CatalogConsumerSession? = null,
    // P7-04.A/B import surface (D-146; spec sections 4.1/4.2); null defaults keep legacy
    // constructions valid (startup tests). The product roots inject the platform pick port,
    // the jvmMain intake orchestration, the platform kind, and a per-pick session factory
    // (one fresh opaque UUIDv7 handle per file pick, R-Q09-1 — never a shared session across
    // concurrent dispatches). Unlike the P7-03 read surface there is no catalog-session
    // following here: the import surface is not catalog-versioned, so these stay plain
    // nullable values; the P7-04.C host consumes them to launch picks and build the typed
    // intake input (format + platform + session + bounded bytes).
    val importFilePickPort: ImportFilePickPort? = null,
    val importFileIntake: ImportFileIntakePort? = null,
    val importPlatformKind: ImportPlatformKind? = null,
    val importIntakeSessionFactory: () -> ImportIntakeSessionIdentity? = { null },
    // P7-04.C (D-146; spec sections 4.5/6.1): the import review read surface (the three read use
    // cases over the fail-loud read port), the core duplicate-review use case, its per-intent id
    // mint (requestId/reviewId/historyId, fresh UUIDv7 per review intent, R-Q09-2), and the
    // platform pick-result channel the composition root wires into its pick port's onResult. All
    // nullable with plain defaults so legacy constructions (startup tests) keep compiling; like
    // the P7-04.A/B surface there is no catalog-session following here (the import surface is not
    // catalog-versioned).
    baseQueryImportReviewRows: QueryImportReviewRows? = null,
    baseQueryImportCandidateDetail: QueryImportCandidateDetail? = null,
    baseQueryImportDuplicateReviews: QueryImportDuplicateReviews? = null,
    // P7-05 enumeration performance batch: the session-level duplicate-review batch query (the
    // 整组确认页 enumeration's one-read replacement of the per-candidate N+1 loop). Plain
    // nullable with a default so legacy constructions (startup tests) keep compiling; like the
    // rest of the import surface there is no catalog-session following.
    baseQueryImportDuplicateReviewsForSession: QueryImportDuplicateReviewsForSession? = null,
    val importDuplicateReview: ReviewImportDuplicateCandidate? = null,
    val importDuplicateReviewIds: () -> ImportDuplicateReviewIds? = { null },
    val importPickResultChannel: ImportFilePickResultChannel? = null,
    // A-PERF (P7-04 read-governance batch, spec section 2.1): the shared statistics-refresh hook
    // the host runs on the intake pipeline's background thread right after the intake transaction
    // completes (the ~10x row-growth trigger point of SQLite's PRAGMA optimize semantics). The
    // composition roots inject their platform's controlled execution entry (the Android handle's
    // runQueryStatisticsOptimize / the desktop graph's driver-backed method); null keeps legacy
    // constructions (startup tests) compiling with the hook simply absent — statistics refresh is
    // a maintenance concern, never a product behavior input.
    val importIntakeStatisticsRefresh: () -> Unit = {},
    // P7-04.D (D-146; spec sections 3.2.3/3.3.2/6.1): the batch confirmation surface. The
    // composition root owns the per-kind ConfirmImportCandidate wiring (commitPort = the spine
    // store, an ImportCommitIds mint with the kind's frozen posting count, the existing
    // per-kind formal factories, the catalog) and hands it over as a FACTORY so every dispatch
    // run constructs its set from the CURRENT catalog (the manual-flow V-2 admission precedent:
    // fresh admission data per dispatch run, within the frozen use-case's construction-time
    // catalog parameter). The per-item requestId mint is a plain nullable function (a fresh
    // UUIDv7 per item, minted once per authorization intent by the host; claim-gated, replay
    // paths never consume). Plain nullable defaults keep legacy constructions (startup tests)
    // compiling.
    val importConfirmUseCases: () -> ImportConfirmUseCaseSet? = { null },
    val importConfirmRequestIdSource: (() -> String)? = null,
) {
    private val session = catalogSession
    private val fallbackOptionsProvider = baseOptionsProvider
    private val fallbackQueryCurrentState = baseQueryCurrentState
    private val fallbackSummarizeActivity = baseSummarizeActivity
    private val fallbackQueryLedgerEntryRows = baseQueryLedgerEntryRows
    private val fallbackQueryMonthlyActivity = baseQueryMonthlyActivity
    private val fallbackQueryTransactionDetail = baseQueryTransactionDetail
    private val fallbackQueryImportReviewRows = baseQueryImportReviewRows
    private val fallbackQueryImportCandidateDetail = baseQueryImportCandidateDetail
    private val fallbackQueryImportDuplicateReviews = baseQueryImportDuplicateReviews
    private val fallbackQueryImportDuplicateReviewsForSession = baseQueryImportDuplicateReviewsForSession
    private val fallbackIncomeOptionsProvider =
        baseIncomeOptionsProvider ?: ManualIncomeOptionsProvider { ManualIncomeOptions(emptyList(), emptyList()) }
    private val fallbackTransferOptionsProvider =
        baseTransferOptionsProvider ?: ManualTransferOptionsProvider { ManualTransferOptions(emptyList(), emptyList()) }
    private val fallbackLendingOptionsProvider =
        baseLendingOptionsProvider ?: ManualLendingOptionsProvider { ManualLendingOptions(emptyList(), emptyList()) }

    /** Current authoritative options provider; follows [refreshCatalog] when a session is injected. */
    val optionsProvider: ManualExpenseOptionsProvider
        get() = session?.optionsProvider ?: fallbackOptionsProvider

    /** P7-02.A: current authoritative income options projection; follows [refreshCatalog]. */
    val incomeOptionsProvider: ManualIncomeOptionsProvider
        get() = session?.incomeOptionsProvider ?: fallbackIncomeOptionsProvider

    /** P7-02.B: current authoritative transfer options projection; follows [refreshCatalog]. */
    val transferOptionsProvider: ManualTransferOptionsProvider
        get() = session?.transferOptionsProvider ?: fallbackTransferOptionsProvider

    /** P7-02.C: current authoritative lending options projection; follows [refreshCatalog]. */
    val lendingOptionsProvider: ManualLendingOptionsProvider
        get() = session?.lendingOptionsProvider ?: fallbackLendingOptionsProvider

    /** Current authoritative read model; follows [refreshCatalog] when a session is injected. */
    val queryCurrentState: QueryLedgerCurrentState
        get() = session?.queryCurrentState ?: fallbackQueryCurrentState

    /** Current authoritative summary model; follows [refreshCatalog] when a session is injected. */
    val summarizeActivity: SummarizeLedgerActivity
        get() = session?.summarizeActivity ?: fallbackSummarizeActivity

    /**
     * P7-03.C: display-ordered ledger entry rows for the HOME flow list (spec section 4.2.5);
     * follows [refreshCatalog] when a session is injected. `null` when neither the session nor
     * the construction site provides the P7-03 read surface (legacy facades).
     */
    val queryLedgerEntryRows: QueryLedgerEntryRows?
        get() = session?.queryLedgerEntryRows ?: fallbackQueryLedgerEntryRows

    /** P7-03.B/C: the unified monthly projection; follows [refreshCatalog] when a session is injected. */
    val queryMonthlyActivity: QueryMonthlyActivity?
        get() = session?.queryMonthlyActivity ?: fallbackQueryMonthlyActivity

    /** P7-03.C/D: the read-only transaction detail projection; follows [refreshCatalog] when a session is injected. */
    val queryTransactionDetail: QueryTransactionDetail?
        get() = session?.queryTransactionDetail ?: fallbackQueryTransactionDetail

    /** P7-04.C: the ledger-scoped import review list projection (plain nullable, no session following). */
    val queryImportReviewRows: QueryImportReviewRows?
        get() = fallbackQueryImportReviewRows

    /** P7-04.C: the single-candidate detail projection (plain nullable, no session following). */
    val queryImportCandidateDetail: QueryImportCandidateDetail?
        get() = fallbackQueryImportCandidateDetail

    /** P7-04.C: the candidate's duplicate comparison projection (plain nullable, no session following). */
    val queryImportDuplicateReviews: QueryImportDuplicateReviews?
        get() = fallbackQueryImportDuplicateReviews

    /**
     * P7-05 enumeration performance batch: the session-level duplicate-review batch projection
     * (plain nullable, no session following). Consumed by the 整组确认页 enumeration; null keeps
     * legacy constructions on the per-candidate read path.
     */
    val queryImportDuplicateReviewsForSession: QueryImportDuplicateReviewsForSession?
        get() = fallbackQueryImportDuplicateReviewsForSession
}
