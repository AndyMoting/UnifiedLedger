package com.unifiedledger.android

import android.app.Activity
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.unifiedledger.application.CATALOG_MANAGED_CURRENCY
import com.unifiedledger.application.CatalogAdmissionExpenseTransactionFactory
import com.unifiedledger.application.CatalogAdmissionIncomeTransactionFactory
import com.unifiedledger.application.CatalogAdmissionTransferTransactionFactory
import com.unifiedledger.application.CatalogBootstrapFailedException
import com.unifiedledger.application.CatalogConsumerSession
import com.unifiedledger.application.CommitOnceInvocationTracker
import com.unifiedledger.application.CommitOnceInvocationTrackerIncome
import com.unifiedledger.application.CommitOnceInvocationTrackerLending
import com.unifiedledger.application.CommitOnceInvocationTrackerTransfer
import com.unifiedledger.application.ConfirmImportCandidate
import com.unifiedledger.application.ConfirmedExpenseTransactionFactory
import com.unifiedledger.application.ConfirmedIncomeTransactionFactory
import com.unifiedledger.application.ConfirmedLendingTransactionFactory
import com.unifiedledger.application.ConfirmedManualExpenseCommit
import com.unifiedledger.application.ConfirmedManualIncomeCommit
import com.unifiedledger.application.ConfirmedTransferTransactionFactory
import com.unifiedledger.application.CounterpartyCommands
import com.unifiedledger.application.CreditFlowFormalFactory
import com.unifiedledger.application.DEFAULT_EXPENSE_LEAF_ID
import com.unifiedledger.application.DEFAULT_MANAGEABLE_ACCOUNT_ID
import com.unifiedledger.application.ExecuteCatalogCommand
import com.unifiedledger.application.ExecuteConfirmedManualExpense
import com.unifiedledger.application.ExecuteConfirmedManualIncome
import com.unifiedledger.application.ExecuteConfirmedManualLending
import com.unifiedledger.application.ExecuteConfirmedManualTransfer
import com.unifiedledger.application.ExecuteCorrectTransactionVersion
import com.unifiedledger.application.ExecuteCreateCounterparty
import com.unifiedledger.application.ExecuteImportIntake
import com.unifiedledger.application.ExecuteLendingSubmission
import com.unifiedledger.application.ExecuteManualEntrySubmission
import com.unifiedledger.application.ExecuteManualExpenseSave
import com.unifiedledger.application.ExecuteManualExpenseSubmission
import com.unifiedledger.application.ExecuteManualIncomeSave
import com.unifiedledger.application.ExecuteManualIncomeSubmission
import com.unifiedledger.application.ExecuteManualLendingSave
import com.unifiedledger.application.ExecuteManualLendingSubmission
import com.unifiedledger.application.ExecuteManualTransferSave
import com.unifiedledger.application.ExecuteManualTransferSubmission
import com.unifiedledger.application.ExecuteRenameCounterparty
import com.unifiedledger.application.ExecuteRestoreTransaction
import com.unifiedledger.application.ExecuteSetCounterpartyActive
import com.unifiedledger.application.ExecuteVoidTransaction
import com.unifiedledger.application.ImportContentFingerprint
import com.unifiedledger.application.ImportDuplicateReviewId
import com.unifiedledger.application.ImportIntakeSessionIdentity
import com.unifiedledger.application.ImportPlatformKind
import com.unifiedledger.application.ImportRequestId
import com.unifiedledger.application.ImportStatusHistoryId
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.ManualLendingTransactionFactory
import com.unifiedledger.application.ManualTransferTransactionFactory
import com.unifiedledger.application.MixedPaymentFlowFormalFactory
import com.unifiedledger.application.OrdinaryFlowFormalFactory
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.ParseManualExpenseOccurredAt
import com.unifiedledger.application.QueryCatalogSnapshot
import com.unifiedledger.application.QueryImportCandidateDetail
import com.unifiedledger.application.QueryImportDuplicateReviews
import com.unifiedledger.application.QueryImportDuplicateReviewsForSession
import com.unifiedledger.application.QueryImportReviewRows
import com.unifiedledger.application.ResolveManualExpenseCommitStatus
import com.unifiedledger.application.ResolveManualIncomeCommitStatus
import com.unifiedledger.application.ResolveManualLendingCommitStatus
import com.unifiedledger.application.ResolveManualTransferCommitStatus
import com.unifiedledger.application.ResolveTransactionCorrectionCommitStatus
import com.unifiedledger.application.ResolveTransactionVoidCommitStatus
import com.unifiedledger.application.ReviewImportDuplicateCandidate
import com.unifiedledger.application.TransferFlowFormalFactory
import com.unifiedledger.application.UuidV7CatalogEntityIdSource
import com.unifiedledger.application.UuidV7CatalogManagementRequestIdSource
import com.unifiedledger.application.UuidV7ConfirmedManualExpenseIdSource
import com.unifiedledger.application.UuidV7ConfirmedManualIncomeIdSource
import com.unifiedledger.application.UuidV7ConfirmedManualLendingIdSource
import com.unifiedledger.application.UuidV7ConfirmedManualTransferIdSource
import com.unifiedledger.application.UuidV7CounterpartyIdSource
import com.unifiedledger.application.UuidV7Generator
import com.unifiedledger.application.UuidV7ImportIntakeIdSource
import com.unifiedledger.application.UuidV7ManualExpenseRequestIdSource
import com.unifiedledger.application.UuidV7ManualIncomeRequestIdSource
import com.unifiedledger.application.UuidV7ManualLendingRequestIdSource
import com.unifiedledger.application.UuidV7ManualTransferRequestIdSource
import com.unifiedledger.application.UuidV7TransactionCorrectionIdSource
import com.unifiedledger.application.UuidV7TransactionVoidFactIdSource
import com.unifiedledger.application.backup.BackupCryptoPrimitives
import com.unifiedledger.application.backup.JvmBackupCryptoPrimitives
import com.unifiedledger.application.import.JvmImportFileIntake
import com.unifiedledger.data.AndroidLedgerDatabaseHandle
import com.unifiedledger.data.CatalogBootstrapResult
import com.unifiedledger.data.SqlDelightImportReviewReadAdapter
import com.unifiedledger.data.SqlDelightLedgerCurrentStateReadAdapter
import com.unifiedledger.data.createAndroidLedgerDatabase
import com.unifiedledger.data.defaultCatalogSeed
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseCommand
import com.unifiedledger.domain.AssetReceivedOrdinaryIncomeCommand
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.createAssetPaidOrdinaryExpense
import com.unifiedledger.domain.createAssetReceivedOrdinaryIncome
import com.unifiedledger.ui.BackupExportUseCase
import com.unifiedledger.ui.BackupSnapshotPort
import com.unifiedledger.ui.BackupTargetPort
import com.unifiedledger.ui.CloseResult
import com.unifiedledger.ui.ImportConfirmUseCaseSet
import com.unifiedledger.ui.ImportDuplicateReviewIds
import com.unifiedledger.ui.ImportFilePickResultChannel
import com.unifiedledger.ui.LedgerFileSystem
import com.unifiedledger.ui.LedgerLeaseScope
import com.unifiedledger.ui.LedgerOpenTarget
import com.unifiedledger.ui.LedgerRuntimeOwner
import com.unifiedledger.ui.LedgerStartupResult
import com.unifiedledger.ui.LedgerStorageFailure
import com.unifiedledger.ui.LedgerStorageLayout
import com.unifiedledger.ui.LedgerStorageRejectedException
import com.unifiedledger.ui.P503App
import com.unifiedledger.ui.P503LedgerFacade
import com.unifiedledger.ui.P503StartupScreen
import com.unifiedledger.ui.P503StartupState
import com.unifiedledger.ui.UuidV7ImportCommitIdSource
import com.unifiedledger.ui.importCreditRefundOriginalExpenseProvider
import com.unifiedledger.ui.isUsableSqliteMainFile
import com.unifiedledger.ui.ledgerStorageLayout
import com.unifiedledger.ui.openStableStorageLedger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import kotlin.time.Clock

/**
 * Android composition root (P5-03 spec sections 8/10.2). Uses the application-private
 * `ledger.db` through the existing AndroidSqliteDriver handle pattern, assembles the same
 * object graph as the desktop root (only the driver and random source differ) and calls the
 * shared [P503App]. Startup is fail-closed: only Retry and Exit are offered on failure.
 */
@Composable
fun app() {
    val context = LocalContext.current
    val activity = context as? Activity
    // P7-04.A (D-146 R-Q08-1): the SAF OpenDocument launcher must be registered in
    // composition before the activity is RESUMED; its result callback reaches the pick port
    // through a plain holder because the port is created together with the ledger graph,
    // after the launcher.
    val importPickPortRef = remember { arrayOfNulls<AndroidImportFilePickPort<Uri>?>(1) }
    // P7-04.C (D-146; spec 4.1.1): the shared result channel — the SAF port's onResult delivers
    // here and the shared P503App host subscribes through the facade (the frozen port shape never
    // carries the result in its signature).
    val importPickChannel = remember { ImportFilePickResultChannel() }
    val importSafLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            importPickPortRef[0]?.onOpenDocumentResult(uri)
        }
    // P0 hotfix (defect 2): the composition-scoped main-dispatcher scope the startup controller
    // launches its non-blocking start on. Only the state writes resume here; the blocking open
    // itself runs on the controller's background dispatcher (Dispatchers.IO).
    val startupScope = rememberCoroutineScope()
    // P7-06 06.B (D-177; spec section 3.5 phase 2): the SAF CreateDocument launcher for the
    // backup export target. Registered in composition like the import picker; the result callback
    // hands the chosen document to the pending save closure.
    val backupSaveTargetRef = remember { arrayOfNulls<AndroidBackupTargetPort<Uri>?>(1) }
    val backupSaveLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            backupSaveTargetRef[0]?.onCreateDocumentResult(uri)
        }
    // P7-06 06.B (D-177; spec section 3.5 phase 2): the SAF CreateDocument launch must run on the
    // main thread, while the export use case runs on a background dispatcher. This poster is the
    // main-looper hop the target port uses; the handler is created once for the composition.
    val mainThreadPoster = remember { mainThreadPoster() }
    val controller =
        remember(context) {
            // The Android pick port with SAF launch/metadata/stream closures; one-shot
            // ContentResolver read, no persistable URI permission, no copy. P7-04.C wires the
            // results into the shared coordinator channel; the SAF callback runs on the main
            // thread, so the deliveries are UI-thread-safe.
            val importFilePickPort =
                AndroidImportFilePickPort<Uri>(
                    launchOpenDocument = importSafLauncher::launch,
                    resolveMetadata = { uri -> resolveSafFileMetadata(context.contentResolver, uri) },
                    openInputStream = { uri -> context.contentResolver.openInputStream(uri) },
                    onResult = importPickChannel::deliver,
                )
            importPickPortRef[0] = importFilePickPort
            // P7-06 06.B: the private staging host and the SAF save target.
            val databasePath = context.getDatabasePath(LEGACY_ANDROID_DATABASE_NAME)
            val (hostDirectory, _) = androidStableStoragePaths(databasePath)
            val fileSystem = AndroidLedgerFileSystem()
            val layout = ledgerStorageLayout(fileSystem, hostDirectory)
            val backupTargetPort =
                AndroidBackupTargetPort<Uri>(
                    // SAF launchers are main-thread only; the export runs on a background
                    // dispatcher, so the launch is posted onto the main looper here.
                    postToMainThread = mainThreadPoster,
                    launchCreateDocument = backupSaveLauncher::launch,
                    openOutputStream = { uri -> context.contentResolver.openOutputStream(uri) },
                )
            backupSaveTargetRef[0] = backupTargetPort
            AndroidStartupController(
                openDatabase = {
                    // P7-06 06.1 (D-176): the stable-storage sequence resolves the active
                    // generation, performs the non-destructive legacy upgrade on first start and
                    // guards the target before any create-on-open factory runs.
                    openAndroidStableStorageLedger(context, importFilePickPort, importPickChannel)
                },
                // P0 hotfix (defect 2): [startScope] is the composition-scoped main dispatcher
                // used only for the state writes; the blocking open runs on [backgroundDispatcher]
                // (the controller's Dispatchers.IO default).
                startScope = startupScope,
                backgroundDispatcher = Dispatchers.IO,
                // P7-06 06.B (D-177): the backup-export wiring. The target port posts its SAF
                // launch to the main thread (see AndroidBackupTargetPort), so the export use case
                // can run on a background dispatcher.
                backupWiring = AndroidBackupWiring(fileSystem, layout, backupTargetPort),
            )
        }
    // MUST FIX 2: the controller's open now outlives a single composition pass, so dispose it
    // when this composition leaves. dispose() ATTEMPTS to close the owner's active graph (best
    // effort — see the AndroidStartupController class note for the TransitionInProgress /
    // QuiesceBlocked paths where the close is declined and the graph is left open), and the
    // NonCancellable decision makes the teardown-after-open case reach that attempt instead of
    // silently dropping it.
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }
    LaunchedEffect(Unit) {
        controller.start()
    }

    val facade = controller.ledger
    // Enforced edge-to-edge draws content behind the status bar; a root-level statusBarsPadding
    // keeps every screen's top controls reachable without touching the shared UI (D-128).
    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        when {
            controller.state == P503StartupState.Ready && facade != null ->
                P503App(
                    facade,
                    onExit = { activity?.finish() },
                    backHandler = { enabled, onBack -> BackHandler(enabled, onBack) },
                )
            else ->
                P503StartupScreen(
                    state = controller.state,
                    onRetry = controller::start,
                    onExit = { activity?.finish() },
                )
        }
    }
}

/**
 * Testable composition-root startup state (spec section 8) with P5-04.4 fail-closed retry
 * resource-safety (spec sections 4/5). Exposes the shared [P503StartupState] transitions and
 * never exposes a business graph after a failed start. The ledger connection is carried as a
 * [CloseableLedgerGraph] so a retry can close the previous connection and a mid-failure open
 * is closed too.
 *
 * P0 hotfix (defect 2): [start] stays non-suspend, but the blocking [LedgerRuntimeOwner.startup]
 * open now runs on [backgroundDispatcher] instead of the caller/main thread. On the
 * legacy-upgrade plan that open performs a full copy of the legacy database, which on a large
 * ledger exceeded the input-dispatch timeout and ANR'd the app. The state writes resume on
 * [startScope] (the composition main dispatcher), matching the established
 * `scope.launch(Dispatchers.Default) { ... }` precedent in `P503App.requestCatalogSnapshotLoad`.
 * The reentrancy guard is still a synchronous caller-thread decision taken before the launch.
 *
 * Lifecycle (MUST FIX 2 / MUST FIX C): because the open now outlives a single composition pass,
 * the controller has an explicit [dispose] that the composition root calls from `DisposableEffect`.
 * [dispose] and the post-open decision are serialized on one monitor, so a completed open reaches a
 * decision — the state is published (dispose not yet called) or [dispose]/the disposed branch
 * attempts to close the owner's graph. That close is BEST EFFORT, not an unconditional guarantee:
 * [LedgerRuntimeOwner.closeActiveGraph] returns the typed `TransitionInProgress` without closing
 * when another transition holds the owner's mutex, and `QuiesceBlocked(inFlight)` without closing
 * when a business lease is in flight. Both are logged here (never silently dropped), but the graph
 * is left open in those two cases — a residual leak class that is partly pre-existing (the pre-fix
 * controller had no dispose at all) and is disclosed rather than overclaimed. Separately, the
 * process-wide open lock (see [withAndroidStableStorageOpenLock]) serializes the OPEN SEQUENCES of
 * different owner instances; it does not itself cap live graphs, and the first owner's best-effort
 * close runs after that lock is released, so two owner instances can hold connections to the same
 * ledger concurrently for a short window. This removes the ANR trigger only: a process kill or power
 * loss mid-copy still leaves a pointerless generation that fail-closes with `POINTER_MISSING` on
 * the next start, and 06.1 has no recovery for that (registered as the 06.D residual in
 * [openStableStorageLedger]); this hotfix does not change that.
 */
internal class AndroidStartupController(
    private val openDatabase: () -> CloseableLedgerGraph,
    // P0 hotfix (defect 2): the scope the non-blocking start launches on. Deliberately has NO
    // default: this scope must be a main-dispatcher scope (the state writes resume on it), so the
    // caller is required to inject one explicitly rather than silently writing mutableStateOf
    // off the main thread.
    private val startScope: CoroutineScope,
    // P5-04.4 I-002: the default channel logs the full stack via the three-argument
    // Log.w overload; tests inject a counting/recording lambda (never android.util.Log).
    private val logFailure: (Exception) -> Unit = { failure -> Log.w(LOG_TAG, "startup failed", failure) },
// P0 hotfix (defect 2): the dispatcher the blocking open hops to. Dispatchers.IO is the
    // correct class for the blocking ~370 MB legacy file copy plus its fsyncs (it is bounded to
    // the I/O pool rather than the CPU pool); injectable so tests run deterministically.
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.IO,
    // P7-06 06.B (D-177): the optional backup-export wiring. Null keeps the surface absent (the
    // existing startup tests construct the controller with the two base parameters only).
    private val backupWiring: AndroidBackupWiring? = null,
) {
    var state by mutableStateOf<P503StartupState>(P503StartupState.Starting)
        private set

    /**
     * P7-06 06.1 (D-176; spec section 4): the single runtime owner of the active graph. The
     * controller no longer holds a graph itself — every open, close and generation increment goes
     * through the owner, so "at most one active graph" is the owner's invariant rather than the
     * controller's bookkeeping. [openDatabase] is the injected graph builder the tests already use.
     */
    private val owner =
        LedgerRuntimeOwner(
            openGeneration = {
                openDatabase().also { graph -> lastOpenedGraph = graph }
            },
            closeGraph = { graph -> graph.close() },
            facadeOf = { graph -> graph.facade },
        )

    /**
     * P7-06 06.B (D-177): the most recently opened graph, captured so the export use case can
     * resolve the CURRENT graph's controlled snapshot surface. A pure field read, never a lease.
     */
    private var lastOpenedGraph: CloseableLedgerGraph? = null

    /**
     * P7-06 06.B (D-177; spec section 3): the shared backup-export use case, built over this
     * controller's owner and the injected wiring. The snapshot surface is resolved from the live
     * graph at export time (the driver is replaced on a reopen); the layout comes from the wiring.
     */
    private val backupExportUseCase: BackupExportUseCase? =
        backupWiring?.let { wiring ->
            BackupExportUseCase(
                owner = owner,
                fileSystem = wiring.fileSystem,
                layout = wiring.layout,
                snapshotProvider = { lastOpenedGraph?.snapshotPort },
                target = wiring.targetPort,
                crypto = wiring.crypto,
                newToken = wiring.newToken,
            )
        }

    /**
     * P7-06 06.1 fix (review REJECT): the product surface handed to [P503App] is the LEASE-SCOPED
     * accessor, never the raw facade. [LedgerRuntimeOwner.facade] is `internal` (module-private),
     * so this composition root cannot obtain the raw projection at all; every `facade.*` business
     * call P503App makes therefore goes through [LedgerLeaseScope.withFacade]/`probe`, which
     * acquire and release an operation lease. The lease is non-vacuous by construction.
     */
    val ledger: LedgerLeaseScope?
        get() = if (state == P503StartupState.Ready) leaseScope else null

    private val leaseScope =
        LedgerLeaseScope(owner).also { scope ->
            scope.backupExport = backupExportUseCase
        }

    /**
     * True once [start] has been invoked. The state already starts as [P503StartupState.Starting]
     * so the guard needs this flag to distinguish the initial call (which must proceed) from a
     * reentrant call while an open is in flight (which is dropped).
     */
    private var startedOnce = false

    /**
     * MUST FIX 2 lifecycle state: serializes [dispose] against the post-open decision so a
     * completed open always reaches exactly one outcome (state published, or graph closed). See
     * the class note.
     */
    private val lifecycleLock = Any()

    private var disposed = false

    /**
     * P0 hotfix (defect 2): starts the ledger open without blocking the caller. The reentrancy
     * guard and the [P503StartupState.Starting] publish are SYNCHRONOUS caller-thread decisions
     * taken before the launch, so a second "Retry" tap while a start is in flight is still
     * dropped here and neither rebuilds nor double-closes. The blocking
     * [LedgerRuntimeOwner.startup] then runs on [backgroundDispatcher]; its result is applied on
     * [startScope].
     *
     * MUST FIX 2: the open and the result application run under [NonCancellable], so if
     * [startScope] is cancelled mid-open (composition teardown) the decision is still reached and
     * [applyStartupResult] ATTEMPTS to close the just-opened graph (best effort — the class note
     * discloses the paths where the close is declined and the graph is left open).
     */
    fun start() {
        // P5-04.4 reentrancy guard: a double "Retry" tap while already starting is ignored so
        // it neither rebuilds the graph nor double-closes a connection.
        if (startedOnce && state == P503StartupState.Starting) return
        startedOnce = true
        state = P503StartupState.Starting
        startScope.launch {
            // MUST FIX 2: NonCancellable is established BEFORE the blocking open, so cancelling
            // startScope mid-open cannot discard the result. The open runs on the background
            // dispatcher; control returns to this (state) dispatcher for the decision, which is
            // therefore still applied on the main thread.
            withContext(NonCancellable) {
                // The owner closes any connection left over from a previous Ready or an
                // interrupted mid-open before it opens the next one (the "close before open"
                // resource-safety rule). P7-06 06.1 fix: the owner applies the spec section 4.2
                // in-flight precondition, so a retry racing in-flight business work is refused
                // (typed Blocked) rather than closing the graph under a running call. This is the
                // blocking call that must not run on the main thread (defect 2).
                val result = withContext(backgroundDispatcher) { owner.startup() }
                applyStartupResult(result)
            }
        }
    }

    /**
     * MUST FIX 2 / MUST FIX C: releases the owner's active graph when the composition is torn
     * down. Serialized with [applyStartupResult] on [lifecycleLock]; a start whose scope was
     * cancelled mid-open still reaches [applyStartupResult] (NonCancellable) and attempts the close
     * there. BEST EFFORT, not an unconditional guarantee: [LedgerRuntimeOwner.closeActiveGraph]
     * can decline (mutex contention or an in-flight business lease); both outcomes are logged, and
     * the residual is disclosed on the class note. Idempotent.
     */
    fun dispose() {
        synchronized(lifecycleLock) {
            disposed = true
            closeActiveGraphBestEffort("dispose")
        }
    }

    /**
     * MUST FIX C: attempts to close the owner's active graph and logs every outcome. A `Closed`
     * result is silent; `TransitionInProgress` / `QuiesceBlocked` mean the graph was NOT closed
     * (best-effort close, residual disclosed on the class note). Never throws.
     */
    private fun closeActiveGraphBestEffort(origin: String) {
        when (val result = owner.closeActiveGraph()) {
            CloseResult.Closed -> Unit
            CloseResult.TransitionInProgress ->
                logFailure(IllegalStateException("$origin could not close the active graph: a runtime transition is in progress; graph left open"))
            is CloseResult.QuiesceBlocked ->
                logFailure(IllegalStateException("$origin could not close the active graph: ${result.inFlightLeases} in-flight lease(s); graph left open"))
        }
    }

    /** Applies the owner outcome to the observable startup state (runs on [startScope]). */
    private fun applyStartupResult(result: LedgerStartupResult) {
        synchronized(lifecycleLock) {
            if (disposed) {
                // The composition was torn down while the open was in flight. Do not publish state
                // (nobody observes it). MUST FIX D1: the outcome is still logged so a teardown race
                // never swallows the cause. The close is best effort (see [dispose]).
                logStartupResult(result)
                closeActiveGraphBestEffort("teardown-after-open")
                return
            }
            logStartupResult(result)
            when (result) {
                is LedgerStartupResult.Started -> {
                    state = P503StartupState.Ready
                }
                is LedgerStartupResult.Failed,
                is LedgerStartupResult.Blocked,
                LedgerStartupResult.TransitionInProgress,
                -> {
                    // A failed/blocked/contended start: the graph was NOT touched (Failed leaves no
                    // graph; Blocked/TransitionInProgress return before opening). Surface the
                    // fail-closed state so Retry stays reachable (the reentrancy guard drops a
                    // second tap while Starting).
                    state = P503StartupState.StartupError
                }
            }
        }
    }

    /**
     * MUST FIX D1: logs the failure-shaped outcomes of one startup attempt (Started is silent).
     * Extracted so both the published path and the disposed (teardown-race) path log identically
     * and never drop the cause.
     */
    private fun logStartupResult(result: LedgerStartupResult) {
        when (result) {
            is LedgerStartupResult.Started -> Unit
            is LedgerStartupResult.Failed -> {
                val cause = result.cause
                logFailure(if (cause is Exception) cause else RuntimeException(cause))
            }
            is LedgerStartupResult.Blocked ->
                logFailure(IllegalStateException("startup blocked by ${result.inFlightLeases} in-flight lease(s)"))
            LedgerStartupResult.TransitionInProgress ->
                logFailure(IllegalStateException("startup rejected: a runtime transition is in progress"))
        }
    }
}

/**
 * P5-04.4 S3: a freshly-built ledger graph wrapped with its close action so the composition
 * root can release the underlying driver connection on retry or mid-failure without leaking
 * it. `close` is idempotent for the underlying drivers (SQLDelight / JDBC).
 *
 * P7-01.C (D-143): [catalogSession] and [catalogCommands] are the host-layer entry P7-01.D
 * consumes: run a management command through [catalogCommands], then
 * [CatalogConsumerSession.refresh] to reload the authoritative catalog and rebuild the
 * option/read/summary models without a restart.
 *
 * A-PERF (P7-04 read-governance batch, spec section 2.1): [runQueryStatisticsOptimize] is the
 * controlled statistics-refresh entry of the freshly built graph (the Android handle's method
 * over its private driver). The composition root runs it exactly once, off the UI thread, at
 * the bootstrap-completion trigger point below; tests may keep it unset (the default runs
 * nothing). [runFullAnalyze] (rework 3) is the intake-completion trigger's entry — the
 * explicit full-schema ANALYZE behind the shared facade hook.
 */
internal data class CloseableLedgerGraph(
    val facade: P503LedgerFacade,
    val close: () -> Unit,
    val catalogSession: CatalogConsumerSession? = null,
    val catalogCommands: ExecuteCatalogCommand? = null,
    val runQueryStatisticsOptimize: () -> Unit = {},
    val runFullAnalyze: () -> Unit = {},
    // P7-06 06.B (D-177; spec sections 3.3/3.4): the controlled snapshot surface over the graph's
    // private driver. Null when the composition root wired none (tests, legacy graphs).
    val snapshotPort: BackupSnapshotPort? = null,
)

private const val LOG_TAG = "UnifiedLedger"

/**
 * MUST FIX B: the process-wide lock that serializes the whole stable-storage open (plan
 * resolution + legacy copy + driver open + pointer publish). Without it, a rotation during the
 * multi-second legacy copy runs the OLD composition's NonCancellable open concurrently with the
 * recreated composition's open: both resolve `UpgradeLegacy`/`FreshInstall` (the pointer is not
 * yet published, the legacy file is still present), both copy into the SAME
 * `databases/ledger-generations/gen-1/ledger.db` and both open a driver — two concurrent writers
 * to one ledger file (the spec section 6 / P706-A06 "at most one active graph" vector). Holding
 * this lock across the open makes the second open WAIT for the first to finish. What the second
 * then resolves depends on how the first ended: if the first published the pointer, the second
 * resolves `OpenGeneration` on the now-complete file; if the first aborted (its copy/open threw),
 * the second re-resolves the original plan and fail-closes the same way rather than racing it.
 * The lock serializes the open SEQUENCES; it does not by itself cap the number of live graphs
 * (the first owner's best-effort close runs after this lock is released).
 *
 * The lock is held only on the IO dispatcher (the open always runs under
 * [AndroidStartupController]'s background dispatcher), so the current wiring never blocks the main
 * thread. It is process-wide (one instance per process) because the two opens belong to different
 * controller and owner instances, whose own mutexes cannot see each other. It is a plain monitor
 * with no timeout, so a hung copy (e.g. a stuck filesystem) would stall a recreated composition's
 * startup at `Starting` until the first open returns.
 */
private val ANDROID_STABLE_STORAGE_OPEN_LOCK = Any()

/**
 * MUST FIX B: runs [open] while holding [ANDROID_STABLE_STORAGE_OPEN_LOCK]. Extracted so the
 * mutual-exclusion mechanism is JVM-testable without an Android Context (the production
 * [openAndroidStableStorageLedger] calls this around its whole sequence, so the wiring is one
 * call and the mechanism is what the test pins).
 */
internal fun <T> withAndroidStableStorageOpenLock(open: () -> T): T = synchronized(ANDROID_STABLE_STORAGE_OPEN_LOCK) { open() }

/**
 * P7-06 06.B (D-177; spec section 3): the composition-root backup-export wiring handed to
 * [AndroidStartupController]. Kept as one value so the controller's constructor stays backward
 * compatible (the existing startup tests pass only [openDatabase] and the log channel).
 */
internal class AndroidBackupWiring(
    val fileSystem: LedgerFileSystem,
    val layout: LedgerStorageLayout,
    val targetPort: BackupTargetPort,
    val crypto: BackupCryptoPrimitives = JvmBackupCryptoPrimitives(),
    val newToken: () -> String = { randomUuidText() },
)

/** P7-06 06.B: one random token text (kept out of the data-class default so ktlint's chain rule is satisfied). */
private fun randomUuidText(): String {
    val uuid = java.util.UUID.randomUUID()
    return uuid.toString()
}

/**
 * P7-06 06.1 (D-176; spec sections 3.2/4.5/5.1): the Android stable-storage open. The host
 * directory and the legacy `databases/ledger.db` path are resolved from the platform API here (the
 * handle deliberately does not expose a path, so this resolution cannot come from the handle).
 *
 * The shared [openStableStorageLedger] sequence resolves the plan, runs the non-destructive legacy
 * upgrade when needed, and only then builds the graph. The generation directory is created by the
 * sequence before the open, and the pre-open guard rejects a zero-length/invalid main file, so the
 * create-on-open driver only ever runs on the genuine fresh-install path (section 4.5).
 *
 * MUST FIX B: the whole sequence runs under [withAndroidStableStorageOpenLock], so two opens
 * (e.g. a rotation racing the NonCancellable open) cannot concurrently copy into and open the same
 * generation. See that lock's note.
 *
 * [openDriver] is the injectable driver-open seam: production passes the default
 * ([createAndroidLedgerDatabase] with the resolved name), and the instrumented regression suite
 * injects a recording lambda to observe the EXACT name the production sequence hands to the
 * driver for both generation plans (the defect-1 guard). The default preserves production
 * behaviour unchanged.
 */
internal fun openAndroidStableStorageLedger(
    context: android.content.Context,
    importFilePickPort: AndroidImportFilePickPort<Uri>,
    importPickChannel: ImportFilePickResultChannel,
    openDriver: (String) -> AndroidLedgerDatabaseHandle = { name -> createAndroidLedgerDatabase(context, name) },
): CloseableLedgerGraph =
    withAndroidStableStorageOpenLock {
        openAndroidStableStorageLedgerLocked(context, importFilePickPort, importPickChannel, openDriver)
    }

/** The body of [openAndroidStableStorageLedger]; the caller holds the process-wide open lock. */
private fun openAndroidStableStorageLedgerLocked(
    context: android.content.Context,
    importFilePickPort: AndroidImportFilePickPort<Uri>,
    importPickChannel: ImportFilePickResultChannel,
    openDriver: (String) -> AndroidLedgerDatabaseHandle,
): CloseableLedgerGraph {
    val databasePath = context.getDatabasePath(LEGACY_ANDROID_DATABASE_NAME)
    val (hostDirectory, legacyMainFile) = androidStableStoragePaths(databasePath)
    // P7-06 06.B: the file-system adapter and layout are resolved HERE (the single place that
    // derives the host directory) so the graph build can wire the private staging host and the
    // snapshot port to the same host the startup sequence used.
    val fileSystem = AndroidLedgerFileSystem()
    val layout = ledgerStorageLayout(fileSystem, hostDirectory)
    return openStableStorageLedger(
        fileSystem = fileSystem,
        layout = layout,
        legacyMainFile = legacyMainFile,
        closeGraph = { graph -> graph.close() },
    ) { target ->
        // P7-06 06.1 fix (review Fix 7; spec section 4.5): the AndroidSqliteDriver creates on
        // open, so a NON-fresh target must be re-guarded here exactly like the desktop root's JDBC
        // guard — otherwise a missing/zero-length/invalid main file would be silently created as an
        // empty ledger instead of failing closed (the silent-empty-DB prohibition).
        requireUsableNonFreshTarget(fileSystem, target)
        // P5-04.4 S3: a failure mid-open (after the handle exists) must not leak the driver, so
        // the handle is closed before rethrowing; the controller additionally closes any graph it
        // already holds in its catch block.
        //
        // P0 hotfix (defect 1): the driver name is the ABSOLUTE generation main file. A relative
        // name containing a path separator (e.g. "ledger-generations/gen-1/ledger.db") is rejected
        // by the framework: androidx FrameworkSQLiteOpenHelper passes the raw name to
        // Context.getDatabasePath, whose non-separator-prefixed branch calls makeFilename, which
        // throws IllegalArgumentException("File " + name + " contains a path separator"). The
        // absolute branch resolves the parent directory itself and works. target.mainFile is
        // already absolute (built by fileSystem.join over the absolute host directory), so
        // androidGenerationDriverName returns it unchanged and openDriver receives an absolute path.
        val handle = openDriver(androidGenerationDriverName(target.mainFile))
        try {
            buildLedgerGraph(handle, importFilePickPort, importPickChannel, AndroidBackupSnapshotPort(handle))
        } catch (failure: Exception) {
            handle.close()
            throw failure
        }
    }
}

/**
 * P0 hotfix (defect 1): the driver name handed to [createAndroidLedgerDatabase] is the ABSOLUTE
 * generation main file. It is passed through unchanged (the shared sequence already builds it via
 * `fileSystem.join` over the absolute host directory), and the absolute precondition is asserted
 * here so a future regression that reintroduces a relative name fails loudly at this seam instead
 * of being silently accepted. A relative name containing a path separator is rejected deeper by
 * the framework: androidx FrameworkSQLiteOpenHelper passes the raw name to
 * `Context.getDatabasePath`, whose non-separator-prefixed branch calls `ContextImpl.makeFilename`,
 * which throws `IllegalArgumentException("File " + name + " contains a path separator")`; the
 * absolute branch resolves the parent directory itself and works.
 */
internal fun androidGenerationDriverName(mainFile: String): String {
    require(File(mainFile).isAbsolute) { "the Android generation driver name must be absolute: $mainFile" }
    return mainFile
}

/**
 * P7-06 06.1 (D-176; spec section 4.5): the shared non-fresh create-on-open guard both composition
 * roots apply immediately before their platform driver construction (the AndroidSqliteDriver and
 * the desktop JDBC driver both create on open). A non-fresh target whose main file is missing,
 * zero-length or not a valid SQLite database must fail closed instead of being silently created as
 * an empty ledger. Extracted so the Android guard is unit-testable without a Context.
 */
internal fun requireUsableNonFreshTarget(
    fileSystem: LedgerFileSystem,
    target: LedgerOpenTarget,
) {
    if (!target.allowCreateOnOpen && !isUsableSqliteMainFile(fileSystem, target.mainFile)) {
        throw LedgerStorageRejectedException(LedgerStorageFailure.ACTIVE_GENERATION_UNUSABLE)
    }
}

/** The legacy product database name (spec section 3.2; the pre-06.1 fixed location). */
private const val LEGACY_ANDROID_DATABASE_NAME = "ledger.db"

private fun buildLedgerGraph(
    handle: AndroidLedgerDatabaseHandle,
    importFilePickPort: AndroidImportFilePickPort<Uri>,
    importPickChannel: ImportFilePickResultChannel,
    snapshotPort: BackupSnapshotPort? = null,
): CloseableLedgerGraph {
    val database = handle.database
    val store = handle.catalogStore

    val ledgerId = LedgerId("ledger-local-test")
    val currency = CATALOG_MANAGED_CURRENCY
    val paymentAccountId = AccountId(DEFAULT_MANAGEABLE_ACCOUNT_ID)
    val categoryId = CategoryId(DEFAULT_EXPENSE_LEAF_ID)

    val authority =
        when (val bootstrapped = store.bootstrap(ledgerId, defaultCatalogSeed())) {
            is CatalogBootstrapResult.Seeded -> bootstrapped.authority
            is CatalogBootstrapResult.AlreadyInitialized -> bootstrapped.authority
            CatalogBootstrapResult.UnknownReference -> throw CatalogBootstrapFailedException(ledgerId)
        }
    val readAdapter = SqlDelightLedgerCurrentStateReadAdapter(database)
    val counterpartyStore = handle.counterpartyStore
    val entryPreferenceStore = handle.entryPreferenceStore
    // P7-03.C/D: the reporting clock is injected into the session so the unified monthly
    // projection resolves 本月 (R-Q06-2) on the same authoritative catalog version.
    val ledgerClock = LedgerClock { Clock.System.now() }
    val session =
        CatalogConsumerSession(
            reader = store,
            ledgerId = ledgerId,
            initialAuthority = authority,
            readPort = readAdapter,
            counterpartyReader = counterpartyStore,
            clock = ledgerClock,
        )

    val tracker = CommitOnceInvocationTracker(handle.commitPort)
    val delegate =
        ConfirmedExpenseTransactionFactory { request, ids ->
            val catalog =
                store.loadCurrent(request.ledgerId)
                    ?: return@ConfirmedExpenseTransactionFactory DomainResult.Failure(DomainViolation.InvalidCatalog)
            when (
                val result =
                    createAssetPaidOrdinaryExpense(
                        catalog = catalog,
                        command =
                            AssetPaidOrdinaryExpenseCommand(
                                ledgerId = request.ledgerId,
                                amount = request.amount,
                                categoryId = request.categoryId,
                                paymentAccountId = request.paymentAccountId,
                                // P7-02.A S-4 (P702IMPL-01): the draft note must reach the formal
                                // version exactly like the income delegate does.
                                note = request.note,
                                times = TransactionTimes.collapsed(request.occurredAt),
                            ),
                        ids = ids.expenseIds,
                    )
            ) {
                is DomainResult.Success ->
                    DomainResult.Success(
                        ConfirmedManualExpenseCommit(
                            confirmationId = ids.confirmationId,
                            transaction = result.value,
                        ),
                    )
                is DomainResult.Failure -> result
            }
        }
    // V-2 (spec 3.3/6.2): the production commit factory revalidates account/category admission
    // against the persisted catalog before any formal write.
    val factory = CatalogAdmissionExpenseTransactionFactory(admissionReader = store, delegate = delegate)
    val idSource = UuidV7ConfirmedManualExpenseIdSource(UuidV7Generator(::secureRandomBytes))
    val requestIdSource = UuidV7ManualExpenseRequestIdSource(UuidV7Generator(::secureRandomBytes))
    val executeConfirmed = ExecuteConfirmedManualExpense(tracker, idSource, factory)
    val executeSave = ExecuteManualExpenseSave(executeConfirmed)
    val resolver = ResolveManualExpenseCommitStatus(readAdapter)
    val submission = ExecuteManualExpenseSubmission(executeSave, tracker, resolver)

    // P7-02.A income product chain, symmetric to the expense chain: its own tracker/port/id
    // sources (no shared consumption count), an income admission wrapper (V-2) and its own
    // snapshot-aware resolver.
    val incomeTracker = CommitOnceInvocationTrackerIncome(handle.incomeCommitPort)
    val incomeDelegate =
        ConfirmedIncomeTransactionFactory { request, ids ->
            val catalog =
                store.loadCurrent(request.ledgerId)
                    ?: return@ConfirmedIncomeTransactionFactory DomainResult.Failure(DomainViolation.InvalidCatalog)
            when (
                val result =
                    createAssetReceivedOrdinaryIncome(
                        catalog = catalog,
                        command =
                            AssetReceivedOrdinaryIncomeCommand(
                                ledgerId = request.ledgerId,
                                amount = request.amount,
                                categoryId = request.categoryId,
                                receivingAccountId = request.receivingAccountId,
                                times = TransactionTimes.collapsed(request.occurredAt),
                                note = request.note,
                            ),
                        ids = ids.incomeIds,
                    )
            ) {
                is DomainResult.Success ->
                    DomainResult.Success(
                        ConfirmedManualIncomeCommit(
                            confirmationId = ids.confirmationId,
                            transaction = result.value,
                        ),
                    )
                is DomainResult.Failure -> result
            }
        }
    val incomeFactory = CatalogAdmissionIncomeTransactionFactory(admissionReader = store, delegate = incomeDelegate)
    val incomeIdSource = UuidV7ConfirmedManualIncomeIdSource(UuidV7Generator(::secureRandomBytes))
    val incomeRequestIdSource = UuidV7ManualIncomeRequestIdSource(UuidV7Generator(::secureRandomBytes))
    val executeConfirmedIncome = ExecuteConfirmedManualIncome(incomeTracker, incomeIdSource, incomeFactory)
    val executeIncomeSave = ExecuteManualIncomeSave(executeConfirmedIncome)
    val incomeResolver = ResolveManualIncomeCommitStatus(readAdapter)
    val incomeSubmission = ExecuteManualIncomeSubmission(executeIncomeSave, incomeTracker, incomeResolver)

    // P7-02.B transfer product chain: its own tracker/port/id sources (no shared consumption),
    // a transfer admission wrapper (V-2) and its own snapshot-aware resolver. The delegate reads
    // the current catalog inside the write transaction, exactly like the expense/income delegates.
    val transferTracker = CommitOnceInvocationTrackerTransfer(handle.transferCommitPort)
    val transferDelegate =
        ConfirmedTransferTransactionFactory { request, ids ->
            val catalog =
                store.loadCurrent(request.ledgerId)
                    ?: return@ConfirmedTransferTransactionFactory DomainResult.Failure(DomainViolation.InvalidCatalog)
            ManualTransferTransactionFactory(catalog).create(request, ids)
        }
    val transferFactory = CatalogAdmissionTransferTransactionFactory(admissionReader = store, delegate = transferDelegate)
    val transferIdSource = UuidV7ConfirmedManualTransferIdSource(UuidV7Generator(::secureRandomBytes))
    val transferRequestIdSource = UuidV7ManualTransferRequestIdSource(UuidV7Generator(::secureRandomBytes))
    val executeConfirmedTransfer = ExecuteConfirmedManualTransfer(transferTracker, transferIdSource, transferFactory)
    val executeTransferSave = ExecuteManualTransferSave(executeConfirmedTransfer)
    val transferResolver = ResolveManualTransferCommitStatus(readAdapter)
    val transferSubmission = ExecuteManualTransferSubmission(executeTransferSave, transferTracker, transferResolver)
    // P7-02.C manual lending product chain: its own tracker/port/id sources, a V-2 +
    // counterparty + position admission wrapper and its own snapshot-aware resolver. The store
    // is also the counterparty directory and the per-object position read model.
    val lendingTracker = CommitOnceInvocationTrackerLending(handle.lendingCommitPort)
    val lendingDelegate =
        ConfirmedLendingTransactionFactory { request, ids ->
            ManualLendingTransactionFactory(
                admissionReader = store,
                counterpartyReader = counterpartyStore,
                positionReader = counterpartyStore,
            ).create(request, ids)
        }
    val lendingIdSource = UuidV7ConfirmedManualLendingIdSource(UuidV7Generator(::secureRandomBytes))
    val lendingRequestIdSource = UuidV7ManualLendingRequestIdSource(UuidV7Generator(::secureRandomBytes))
    val executeConfirmedLending = ExecuteConfirmedManualLending(lendingTracker, lendingIdSource, lendingDelegate)
    val executeLendingSave = ExecuteManualLendingSave(executeConfirmedLending)
    val lendingResolver = ResolveManualLendingCommitStatus(readAdapter)
    val lendingSubmission = ExecuteManualLendingSubmission(executeLendingSave, lendingTracker, lendingResolver)
    val counterpartyCommands =
        CounterpartyCommands(
            create = ExecuteCreateCounterparty(counterpartyStore, UuidV7CounterpartyIdSource(UuidV7Generator(::secureRandomBytes)), currency),
            rename = ExecuteRenameCounterparty(counterpartyStore),
            setActive = ExecuteSetCounterpartyActive(counterpartyStore),
            reader = counterpartyStore,
        )
    val entrySubmission = ExecuteManualEntrySubmission(submission, incomeSubmission, transferSubmission, ExecuteLendingSubmission(lendingSubmission))

    val catalogCommands =
        ExecuteCatalogCommand(
            commitPort = store,
            requestIdSource = UuidV7CatalogManagementRequestIdSource(UuidV7Generator(::secureRandomBytes)),
            entityIdSource = UuidV7CatalogEntityIdSource(UuidV7Generator(::secureRandomBytes)),
            categoryReferenceProbe = store,
        )
    val snapshotQuery = QueryCatalogSnapshot(store)

    // P7-04.A/B (D-146): the import spine store comes from the handle's platform-configured
    // connection (the driver is private to the data-assembly handle); the intake id source
    // is the production UUIDv7 id source (R-Q09-2: ids are minted only inside the store's
    // winning claim transaction); the candidate audit-time source is the injected LedgerClock
    // (the processing clock supplies processing times only, never source times). The session
    // factory mints one fresh opaque UUIDv7 handle per file pick (R-Q09-1) — a new session
    // per pick, never shared across concurrent dispatches.
    val importIntakeGenerator = UuidV7Generator(::secureRandomBytes)
    val executeImportIntake =
        ExecuteImportIntake(
            commitPort = handle.importSpineStore,
            idSource = UuidV7ImportIntakeIdSource(UuidV7Generator(::secureRandomBytes)),
            fingerprint = ImportContentFingerprint(),
        )
    val importFileIntake =
        JvmImportFileIntake(
            ledgerId = ledgerId,
            executeIntake = executeImportIntake,
            candidateGeneratedAt = { ledgerClock.now().toString() },
        )
    // P7-04.C (D-146; spec sections 4.5/6.1): the import review read surface over the same
    // database — the adapter over the handle's platform-configured connection, the three read
    // use cases, and the core duplicate-review use case (commit port = the spine store) with a
    // per-intent UUIDv7 id mint: a fresh requestId/reviewId/historyId triple for every review
    // intent (R-Q09-2; claim-gated, replay/conflict paths never consume ids).
    val importReviewReadAdapter = SqlDelightImportReviewReadAdapter(database)
    val importReviewIdGenerator = UuidV7Generator(::secureRandomBytes)
    val importDuplicateReview = ReviewImportDuplicateCandidate(commitPort = handle.importSpineStore)
    // P7-04.D (D-146; spec section 9 P7-04.D row): the per-kind ConfirmImportCandidate wiring
    // over the handle's platform-configured spine store — commitPort = the spine store, an
    // ImportCommitIds mint with the kind's frozen posting count (the shape-gated 3/2 split),
    // the existing per-kind formal factories and the catalog. The set is built FRESH on every
    // dispatch run (the facade calls this factory per run) so the frozen use case's
    // construction-time catalog parameter is always the CURRENT catalog — the manual-flow V-2
    // admission precedent (fresh admission data per write attempt). The credit kinds share one
    // CreditFlowFormalFactory (the direct/refund/repayment variants dispatch on the
    // decision-fields type, exactly like the core's confirm kind gate); its refund
    // original-expense reader resolves through the P7-03 read model. The transfer direction
    // gate observes the ledger's seed real asset account (the demo composition's single payment
    // account; the wallet/bank factory variants share the identical predicate and differ only
    // in the observed account identity).
    val importCommitIdGenerator = UuidV7Generator(::secureRandomBytes)
    val importConfirmRequestIdGenerator = UuidV7Generator(::secureRandomBytes)
    val importConfirmUseCasesFactory: () -> ImportConfirmUseCaseSet = {
        val currentCatalog = store.loadCurrent(ledgerId) ?: authority.catalog
        val creditFormalFactory =
            CreditFlowFormalFactory(currentCatalog, importCreditRefundOriginalExpenseProvider(session.queryTransactionDetail))
        val twoPostingIds = UuidV7ImportCommitIdSource(importCommitIdGenerator, postingCount = 2)
        val threePostingIds = UuidV7ImportCommitIdSource(importCommitIdGenerator, postingCount = 3)
        ImportConfirmUseCaseSet(
            ordinaryFlow = ConfirmImportCandidate(handle.importSpineStore, twoPostingIds, OrdinaryFlowFormalFactory(currentCatalog), currentCatalog),
            transferFlow =
                ConfirmImportCandidate(
                    handle.importSpineStore,
                    twoPostingIds,
                    TransferFlowFormalFactory(currentCatalog, AccountId(DEFAULT_MANAGEABLE_ACCOUNT_ID)),
                    currentCatalog,
                ),
            creditExpense = ConfirmImportCandidate(handle.importSpineStore, twoPostingIds, creditFormalFactory, currentCatalog),
            creditRepayment = ConfirmImportCandidate(handle.importSpineStore, twoPostingIds, creditFormalFactory, currentCatalog),
            mixedPayment = ConfirmImportCandidate(handle.importSpineStore, threePostingIds, MixedPaymentFlowFormalFactory(currentCatalog), currentCatalog),
        )
    }
    // P7-05.B/C (D-156/D-158 slice 1b): the correction and void/restore product surface. The
    // commit ports come from the handle's platform-configured connection (the driver stays
    // private to the data-assembly handle), the id sources are the production UUIDv7 mints
    // (claim-gated: replays and identity conflicts consume no ids, though the correction/restore
    // path may discard ids minted before a lost CAS or an in-plan rejection), and the catalog
    // admission reader is the same store the manual flows revalidate against. The two
    // snapshot-aware resolvers read the same read adapter as the P7-03/P7-04 read surface.
    val executeCorrectTransactionVersion =
        ExecuteCorrectTransactionVersion(
            commitPort = handle.correctionCommitPort,
            idSource = UuidV7TransactionCorrectionIdSource(UuidV7Generator(::secureRandomBytes)),
            admissionReader = store,
        )
    val voidFactIdSource = UuidV7TransactionVoidFactIdSource(UuidV7Generator(::secureRandomBytes))
    val executeVoidTransaction =
        ExecuteVoidTransaction(
            commitPort = handle.voidCommitPort,
            idSource = voidFactIdSource,
            clock = ledgerClock,
        )
    val executeRestoreTransaction =
        ExecuteRestoreTransaction(
            commitPort = handle.voidCommitPort,
            idSource = voidFactIdSource,
            clock = ledgerClock,
            admissionReader = store,
        )
    val facade =
        P503LedgerFacade(
            ledgerId = ledgerId,
            currency = currency,
            catalog = authority.catalog,
            parseAmount = ParseManualExpenseAmount(),
            parseOccurredAt = ParseManualExpenseOccurredAt(),
            baseOptionsProvider = session.optionsProvider,
            baseQueryCurrentState = session.queryCurrentState,
            resolveCommitStatus = resolver,
            submitExpense = submission,
            requestIdSource = requestIdSource,
            ledgerClock = ledgerClock,
            baseSummarizeActivity = session.summarizeActivity,
            // P7-02.A typed entry surface.
            submitEntry = entrySubmission,
            submitIncome = incomeSubmission,
            resolveIncomeCommitStatus = incomeResolver,
            incomeRequestIdSource = incomeRequestIdSource,
            baseIncomeOptionsProvider = session.incomeOptionsProvider,
            resolveTransferCommitStatus = transferResolver,
            transferRequestIdSource = transferRequestIdSource,
            baseTransferOptionsProvider = session.transferOptionsProvider,
            resolveLendingCommitStatus = lendingResolver,
            lendingRequestIdSource = lendingRequestIdSource,
            baseLendingOptionsProvider = session.lendingOptionsProvider,
            lendingPositions = counterpartyStore,
            counterpartyCommands = counterpartyCommands,
            // P7-02.D: the manual pin preference surface.
            entryPreferences = entryPreferenceStore,
            // P7-01.D: management reads the same session and refreshes it after every command.
            catalogSnapshot = { snapshotQuery.query(ledgerId) },
            executeCatalogCommand = catalogCommands,
            refreshCatalog = { session.refresh() },
            // P7-03.C/D: the ledger-view read surface on the same authoritative session.
            baseQueryLedgerEntryRows = session.queryLedgerEntryRows,
            baseQueryMonthlyActivity = session.queryMonthlyActivity,
            baseQueryTransactionDetail = session.queryTransactionDetail,
            catalogSession = session,
            // P7-04.A/B (D-146): the import surface — the SAF pick port and the jvmMain
            // intake orchestration on the same ledger; the P7-04.C host consumes both
            // through the facade to build the typed intake input.
            importFilePickPort = importFilePickPort,
            importFileIntake = importFileIntake,
            importPlatformKind = ImportPlatformKind.ANDROID,
            importIntakeSessionFactory = { ImportIntakeSessionIdentity.forFilePick(importIntakeGenerator) },
            // P7-04.C: the review read surface + the duplicate-review use case + its id mint +
            // the shared pick-result channel (the SAF onResult above delivers into it).
            baseQueryImportReviewRows = QueryImportReviewRows(importReviewReadAdapter),
            baseQueryImportCandidateDetail = QueryImportCandidateDetail(importReviewReadAdapter),
            baseQueryImportDuplicateReviews = QueryImportDuplicateReviews(importReviewReadAdapter),
            // P7-05: the session-level batch read behind the 整组确认页 enumeration.
            baseQueryImportDuplicateReviewsForSession = QueryImportDuplicateReviewsForSession(importReviewReadAdapter),
            importDuplicateReview = importDuplicateReview,
            importDuplicateReviewIds = {
                ImportDuplicateReviewIds(
                    ImportRequestId(importReviewIdGenerator.next()),
                    ImportDuplicateReviewId(importReviewIdGenerator.next()),
                    ImportStatusHistoryId(importReviewIdGenerator.next()),
                )
            },
            importPickResultChannel = importPickChannel,
            // P7-04.D: the batch confirmation surface — the per-kind confirm use case factory
            // (fresh catalog per dispatch run) and the per-item requestId mint (a fresh UUIDv7
            // per item, minted once per authorization intent by the host).
            importConfirmUseCases = importConfirmUseCasesFactory,
            importConfirmRequestIdSource = { importConfirmRequestIdGenerator.next() },
            // A-PERF (P7-04 read-governance batch, spec section 2.1): the shared
            // intake-completion statistics refresh — the handle's controlled driver entry,
            // invoked by the shared P503App host pipeline off the UI thread right after the
            // intake transaction (the ~10x row-growth trigger point).
            // A-PERF rework 3: the intake-completion trigger runs the explicit full-schema
            // ANALYZE (PRAGMA optimize does not grant first-time analysis to tables without a
            // stat1 planning history — the device-evidenced stuck list).
            importIntakeStatisticsRefresh = handle::runFullAnalyze,
            // P7-05.B/C (D-156/D-158 slice 1b): the correction/void/restore surface. The
            // recycle-bin read follows the catalog session (it is rebuilt by refreshCatalog like
            // queryTransactionDetail), so restore revalidation and dependency names stay on the
            // current authoritative version.
            correctTransactionVersion = executeCorrectTransactionVersion,
            voidTransaction = executeVoidTransaction,
            restoreTransaction = executeRestoreTransaction,
            resolveCorrectionCommitStatus = ResolveTransactionCorrectionCommitStatus(readAdapter),
            resolveVoidCommitStatus = ResolveTransactionVoidCommitStatus(readAdapter),
            baseQueryRecycleBin = session.queryRecycleBin,
        )
    // A-PERF (spec section 2.1): the Android bootstrap-completion trigger point — the graph is
    // built (catalog bootstrap done), so SQLite's official open-time pattern runs once here, in
    // the background (the graph build itself is on the UI thread inside the startup controller;
    // PRAGMA optimize must not add its cost to first frame). A daemon one-shot thread is the
    // minimal background surface at this composition-root layer; a refresh failure never
    // surfaces to the user (statistics are a planner concern, zero product semantics).
    kotlin.concurrent.thread(isDaemon = true, name = "ul-query-statistics-optimize") {
        runCatching { handle.runQueryStatisticsOptimize() }
    }
    return CloseableLedgerGraph(
        facade,
        handle::close,
        session,
        catalogCommands,
        handle::runQueryStatisticsOptimize,
        handle::runFullAnalyze,
        snapshotPort = snapshotPort,
    )
}

private val secureRandom = SecureRandom()

private fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also(secureRandom::nextBytes)

/**
 * P7-06 06.B (D-177; spec section 3.5 phase 2): posts [block] onto the process main looper. SAF
 * `ActivityResultLauncher.launch` is main-thread only, while the export use case runs on a
 * background dispatcher; this is the hop that makes the launch legal. When already on the main
 * thread the block runs inline (the desktop chooser's own `EventQueue.isDispatchThread` precedent).
 * A dead looper surfaces as a thrown `RuntimeException`, which the target port treats as a
 * cancelled choice rather than a crash.
 */
internal fun mainThreadPoster(): (() -> Unit) -> Unit {
    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    return { block ->
        if (android.os.Looper.myLooper() === android.os.Looper.getMainLooper()) {
            block()
        } else {
            handler.post(block)
        }
    }
}
