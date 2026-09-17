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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.unifiedledger.application.ExecuteSetCounterpartyActive
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
import com.unifiedledger.ui.ImportConfirmUseCaseSet
import com.unifiedledger.ui.ImportDuplicateReviewIds
import com.unifiedledger.ui.ImportFilePickResultChannel
import com.unifiedledger.ui.P503App
import com.unifiedledger.ui.P503LedgerFacade
import com.unifiedledger.ui.P503StartupScreen
import com.unifiedledger.ui.P503StartupState
import com.unifiedledger.ui.UuidV7ImportCommitIdSource
import com.unifiedledger.ui.importCreditRefundOriginalExpenseProvider
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
            AndroidStartupController(
                openDatabase = {
                    // P5-04.4 S3: a failure mid-open (after the handle exists) must not leak
                    // the driver, so the handle is closed before rethrowing; the controller
                    // additionally closes any graph it already holds in its catch block.
                    val handle = createAndroidLedgerDatabase(context, "ledger.db")
                    try {
                        buildLedgerGraph(handle, importFilePickPort, importPickChannel)
                    } catch (failure: Exception) {
                        handle.close()
                        throw failure
                    }
                },
            )
        }
    LaunchedEffect(Unit) {
        controller.start()
    }

    val facade = controller.facade
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
 */
internal class AndroidStartupController(
    private val openDatabase: () -> CloseableLedgerGraph,
    // P5-04.4 I-002: the default channel logs the full stack via the three-argument
    // Log.w overload; tests inject a counting/recording lambda (never android.util.Log).
    private val logFailure: (Exception) -> Unit = { failure -> Log.w(LOG_TAG, "startup failed", failure) },
) {
    var state by mutableStateOf<P503StartupState>(P503StartupState.Starting)
        private set

    var facade: P503LedgerFacade? by mutableStateOf(null)
        private set

    /** The currently held (Ready or mid-open) ledger connection; closed before rebuild. */
    private var activeGraph: CloseableLedgerGraph? = null

    /**
     * True once [start] has been invoked. The state already starts as [P503StartupState.Starting]
     * so the guard needs this flag to distinguish the initial call (which must proceed) from a
     * reentrant call while an open is in flight (which is dropped).
     */
    private var startedOnce = false

    fun start() {
        // P5-04.4 reentrancy guard: a double "Retry" tap while already starting is ignored so
        // it neither rebuilds the graph nor double-closes a connection.
        if (startedOnce && state == P503StartupState.Starting) return
        startedOnce = true
        state = P503StartupState.Starting
        // Close any connection left over from a previous Ready or an interrupted mid-open.
        activeGraph?.close()
        activeGraph = null
        facade = null
        try {
            val graph = openDatabase()
            activeGraph = graph
            facade = graph.facade
            state = P503StartupState.Ready
        } catch (failure: Exception) {
            // A failure part-way through the open must not leak the half-opened driver.
            activeGraph?.close()
            activeGraph = null
            logFailure(failure)
            state = P503StartupState.StartupError
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
 * nothing).
 */
internal data class CloseableLedgerGraph(
    val facade: P503LedgerFacade,
    val close: () -> Unit,
    val catalogSession: CatalogConsumerSession? = null,
    val catalogCommands: ExecuteCatalogCommand? = null,
    val runQueryStatisticsOptimize: () -> Unit = {},
)

private const val LOG_TAG = "UnifiedLedger"

private fun buildLedgerGraph(
    handle: AndroidLedgerDatabaseHandle,
    importFilePickPort: AndroidImportFilePickPort<Uri>,
    importPickChannel: ImportFilePickResultChannel,
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
            importIntakeStatisticsRefresh = handle::runQueryStatisticsOptimize,
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
    )
}

private val secureRandom = SecureRandom()

private fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also(secureRandom::nextBytes)
