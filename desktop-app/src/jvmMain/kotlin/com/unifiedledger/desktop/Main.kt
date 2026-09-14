package com.unifiedledger.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
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
import com.unifiedledger.application.ConfirmedExpenseTransactionFactory
import com.unifiedledger.application.ConfirmedIncomeTransactionFactory
import com.unifiedledger.application.ConfirmedLendingTransactionFactory
import com.unifiedledger.application.ConfirmedManualExpenseCommit
import com.unifiedledger.application.ConfirmedManualIncomeCommit
import com.unifiedledger.application.ConfirmedTransferTransactionFactory
import com.unifiedledger.application.CounterpartyCommands
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
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.ParseManualExpenseOccurredAt
import com.unifiedledger.application.QueryCatalogSnapshot
import com.unifiedledger.application.QueryImportCandidateDetail
import com.unifiedledger.application.QueryImportDuplicateReviews
import com.unifiedledger.application.QueryImportReviewRows
import com.unifiedledger.application.ResolveManualExpenseCommitStatus
import com.unifiedledger.application.ResolveManualIncomeCommitStatus
import com.unifiedledger.application.ResolveManualLendingCommitStatus
import com.unifiedledger.application.ResolveManualTransferCommitStatus
import com.unifiedledger.application.ReviewImportDuplicateCandidate
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
import com.unifiedledger.data.CatalogBootstrapResult
import com.unifiedledger.data.SqlDelightCatalogStore
import com.unifiedledger.data.SqlDelightConfirmedManualExpenseCommitPort
import com.unifiedledger.data.SqlDelightConfirmedManualIncomeCommitPort
import com.unifiedledger.data.SqlDelightConfirmedManualLendingCommitPort
import com.unifiedledger.data.SqlDelightConfirmedManualTransferCommitPort
import com.unifiedledger.data.SqlDelightCounterpartyStore
import com.unifiedledger.data.SqlDelightEntryPreferenceStore
import com.unifiedledger.data.SqlDelightImportReviewReadAdapter
import com.unifiedledger.data.SqlDelightImportSpineStore
import com.unifiedledger.data.SqlDelightLedgerCurrentStateReadAdapter
import com.unifiedledger.data.db.LedgerDatabase
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
import com.unifiedledger.ui.ImportDuplicateReviewIds
import com.unifiedledger.ui.ImportFilePickResultChannel
import com.unifiedledger.ui.P503App
import com.unifiedledger.ui.P503LedgerFacade
import com.unifiedledger.ui.P503StartupScreen
import com.unifiedledger.ui.P503StartupState
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.io.FileInputStream
import java.nio.file.Files
import java.security.SecureRandom
import kotlin.io.path.absolutePathString
import kotlin.time.Clock

private const val LOCAL_TEST_LEDGER_FILE_NAME = "ledger-local-test.db"

/**
 * Desktop composition root (P5-03 spec sections 8/10.1). Assembles the full object graph
 * (driver, current-schema temp-file database, commit tracker, fixed catalog, product ID/clock
 * sources, read adapter and the shared facade) and runs the shared [P503App]. Startup is
 * fail-closed: driver/schema/create/open failures expose only Retry and Exit.
 */
fun main() {
    val databaseUrl = createDemoDatabaseUrl()
    application {
        DesktopRoot(
            openDatabase = { openDesktopLedger(databaseUrl) },
            onExit = ::exitApplication,
        )
    }
}

private fun createDemoDatabaseUrl(): String {
    val directory = Files.createTempDirectory("unifiedledger-demo-")
    return "jdbc:sqlite:${directory.resolve(LOCAL_TEST_LEDGER_FILE_NAME).absolutePathString()}"
}

@Composable
internal fun DesktopRoot(
    openDatabase: () -> CloseableLedgerGraph,
    onExit: () -> Unit,
) {
    val controller = remember { DesktopStartupController(openDatabase).also { it.start() } }
    Window(onCloseRequest = onExit, title = "UnifiedLedger Desktop") {
        val facade = controller.facade
        when {
            controller.state == P503StartupState.Ready && facade != null ->
                P503App(
                    facade,
                    onExit = onExit,
                    backHandler = { enabled, onBack ->
                        DesktopEscBackHandler(enabled, onBack)
                    },
                )
            else ->
                P503StartupScreen(
                    state = controller.state,
                    onRetry = controller::start,
                    onExit = onExit,
                )
        }
    }
}

/**
 * P5-04.3 desktop back equivalence: consumes Escape while [enabled] and forwards to
 * [onBack] (the shared P503App back channel; Submitting already swallows there). Non-Escape
 * events pass through untouched and are never read or recorded. The dispatcher callback
 * runs on the AWT event-dispatch thread, which is also the Compose Desktop UI thread, so
 * [onBack] may touch Compose state directly.
 *
 * D-137: [enabled] is now the sole gate of the back channel. P503App disables it while a
 * picker dialog is open, so Escape reaches only the dialog layer and the edit page stays
 * open. The former AWT window-yield heuristic (yield Escape while any AWT Dialog of this
 * process is showing) is removed: gate evidence E-1 (G07/G08 + gate procedure note) shows
 * that compose picker dialogs do not map to a visible AWT Dialog HWND on the current
 * Compose Multiplatform stack, so the probe could not fire and only masked the defect.
 * Escape dismissal while focus is inside the dialog content is handled by
 * P503EditScreen's `dismissOnEscape`; the composition-root [onBack] channel only receives
 * Escape when the editing flow's back is Back-legal. Window closing is unchanged.
 */
@Composable
internal fun DesktopEscBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    val latestOnBack by rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        val dispatcher =
            KeyEventDispatcher { event ->
                if (enabled && event.id == KeyEvent.KEY_PRESSED && event.keyCode == KeyEvent.VK_ESCAPE) {
                    latestOnBack()
                    true
                } else {
                    false
                }
            }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
        onDispose { KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher) }
    }
}

/**
 * Testable composition-root startup state (spec section 8) with P5-04.4 fail-closed retry
 * resource-safety (spec sections 4/5). Exposes the shared [P503StartupState] transitions and
 * never exposes a business graph or database handle after a failed start. The ledger
 * connection is carried as a [CloseableLedgerGraph] so a retry can close the previous driver
 * and a mid-failure open is closed too.
 */
internal class DesktopStartupController(
    private val openDatabase: () -> CloseableLedgerGraph,
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
        // P5-04.4 reentrancy guard: a double "Retry" / Esc tap while already starting is
        // ignored so it neither rebuilds the graph nor double-closes a connection.
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
            System.err.println("UnifiedLedger startup failed: " + failure)
            failure.printStackTrace()
            state = P503StartupState.StartupError
        }
    }
}

/**
 * P5-04.4 S3: a freshly-built ledger graph wrapped with its close action so the composition
 * root can release the underlying driver connection on retry or mid-failure without leaking
 * it. `close` is idempotent for the underlying JdbcSqliteDriver.
 *
 * P7-01.C (D-143): [catalogSession] and [catalogCommands] are the host-layer entry P7-01.D
 * consumes: run a management command through [catalogCommands], then [CatalogConsumerSession.refresh]
 * to reload the authoritative catalog and rebuild the option/read/summary models.
 */
internal data class CloseableLedgerGraph(
    val facade: P503LedgerFacade,
    val close: () -> Unit,
    val catalogSession: CatalogConsumerSession? = null,
    val catalogCommands: ExecuteCatalogCommand? = null,
)

internal data class DesktopLedgerGraph(
    val database: LedgerDatabase,
    val ledgerId: LedgerId,
    val categoryId: CategoryId,
    val paymentAccountId: AccountId,
    val useCase: ExecuteConfirmedManualExpense,
    val ledgerClock: LedgerClock,
    val facade: P503LedgerFacade,
    val catalogSession: CatalogConsumerSession,
    val catalogCommands: ExecuteCatalogCommand,
)

/**
 * Desktop composition-root graph (IMP-10 wiring, P5-03 spec 10.1; P7-01.C catalog consumption).
 * The catalog, options provider, current-state query and activity summary all come from one
 * [CatalogConsumerSession] over the persisted product catalog. The manual-expense write path is
 * wrapped by [CatalogAdmissionExpenseTransactionFactory] so it revalidates its account/category
 * references against the current catalog inside the write transaction (V-2). The `database`
 * handle is exposed only for test read-back; the UI and the write path never write through it
 * directly.
 */
internal fun buildLedgerGraph(
    driver: SqlDriver,
    createSchema: Boolean = true,
): DesktopLedgerGraph {
    if (createSchema) {
        LedgerDatabase.Schema.create(driver)
    }
    val database = LedgerDatabase(driver)

    val ledgerId = LedgerId("ledger-local-test")
    val currency = CATALOG_MANAGED_CURRENCY
    val paymentAccountId = AccountId(DEFAULT_MANAGEABLE_ACCOUNT_ID)
    val categoryId = CategoryId(DEFAULT_EXPENSE_LEAF_ID)

    val store = SqlDelightCatalogStore(database, driver)
    val authority = bootstrapAuthority(store, ledgerId)
    val readAdapter = SqlDelightLedgerCurrentStateReadAdapter(database)
    val counterpartyStore = SqlDelightCounterpartyStore(database, driver)
    val entryPreferenceStore = SqlDelightEntryPreferenceStore(database)
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

    val port = SqlDelightConfirmedManualExpenseCommitPort(database, driver)
    val tracker = CommitOnceInvocationTracker(port)
    val delegate =
        ConfirmedExpenseTransactionFactory { request, ids ->
            // The delegate and the admission wrapper both read the current catalog inside the
            // same write transaction, so the transaction built here uses the version that was
            // revalidated.
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
    val queryCurrentState = session.queryCurrentState
    val resolver = ResolveManualExpenseCommitStatus(readAdapter)
    val submission = ExecuteManualExpenseSubmission(executeSave, tracker, resolver)

    // P7-02.A income product chain, symmetric to the expense chain: its own port/tracker/id
    // sources, its own V-2 admission wrapper and snapshot-aware resolver.
    val incomePort = SqlDelightConfirmedManualIncomeCommitPort(database, driver)
    val incomeTracker = CommitOnceInvocationTrackerIncome(incomePort)
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

    // P7-02.B transfer product chain: its own port/tracker/id sources, its own V-2 admission
    // wrapper (which resolves the delegate against the current catalog inside the write
    // transaction) and snapshot-aware resolver.
    val transferPort = SqlDelightConfirmedManualTransferCommitPort(database, driver)
    val transferTracker = CommitOnceInvocationTrackerTransfer(transferPort)
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
    // P7-02.C manual lending product chain: its own port/tracker/id sources, its own V-2 +
    // counterparty + position admission wrapper and snapshot-aware resolver. The store is also
    // the counterparty directory and the per-object position read model.
    val lendingPort = SqlDelightConfirmedManualLendingCommitPort(database, driver)
    val lendingTracker = CommitOnceInvocationTrackerLending(lendingPort)
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

    // P7-04.A/B (D-146): the import spine store on the desktop's own JDBC driver (the driver
    // is in hand here, so the public constructor applies the JDBC connection configuration);
    // the intake id source is the production UUIDv7 source (R-Q09-2: ids are minted only
    // inside the store's winning claim transaction); the candidate audit-time source is the
    // injected LedgerClock (processing times only, never source times). The session factory
    // mints one fresh opaque UUIDv7 handle per file pick (R-Q09-1) — a new session per pick,
    // never shared across concurrent dispatches.
    val importSpineStore = SqlDelightImportSpineStore(database, driver)
    val importIntakeGenerator = UuidV7Generator(::secureRandomBytes)
    val executeImportIntake =
        ExecuteImportIntake(
            commitPort = importSpineStore,
            idSource = UuidV7ImportIntakeIdSource(UuidV7Generator(::secureRandomBytes)),
            fingerprint = ImportContentFingerprint(),
        )
    val importFileIntake =
        JvmImportFileIntake(
            ledgerId = ledgerId,
            executeIntake = executeImportIntake,
            candidateGeneratedAt = { ledgerClock.now().toString() },
        )
    // The desktop pick port: the real Swing chooser dialog plus FileInputStream. P7-04.C wires the
    // results into the shared channel; the modal chooser blocks the calling (UI event handler)
    // thread until closed, so onResult delivers on the UI thread (the frozen disclosure).
    val importPickChannel = ImportFilePickResultChannel()
    val importFilePickPort =
        DesktopImportFilePickPort(
            onResult = importPickChannel::deliver,
            showOpenFileChooser = ::showSwingOpenFileChooser,
            openInputStream = { file -> FileInputStream(file) },
        )
    // P7-04.C (D-146; spec sections 4.5/6.1): the import review read surface over the same
    // database — the adapter, the three read use cases, and the core duplicate-review use case
    // (commit port = the spine store) with a per-intent UUIDv7 id mint: a fresh
    // requestId/reviewId/historyId triple for every review intent (R-Q09-2; claim-gated,
    // replay/conflict paths never consume ids).
    val importReviewReadAdapter = SqlDelightImportReviewReadAdapter(database)
    val importReviewIdGenerator = UuidV7Generator(::secureRandomBytes)
    val importDuplicateReview = ReviewImportDuplicateCandidate(commitPort = importSpineStore)
    val facade =
        P503LedgerFacade(
            ledgerId = ledgerId,
            currency = currency,
            catalog = authority.catalog,
            parseAmount = ParseManualExpenseAmount(),
            parseOccurredAt = ParseManualExpenseOccurredAt(),
            baseOptionsProvider = session.optionsProvider,
            baseQueryCurrentState = queryCurrentState,
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
            // P7-01.D: the management surface reads the same authoritative session and refreshes
            // it after every command, so options/reads/summaries stay on one catalog version.
            catalogSnapshot = { snapshotQuery.query(ledgerId) },
            executeCatalogCommand = catalogCommands,
            refreshCatalog = { session.refresh() },
            // P7-03.C/D: the ledger-view read surface on the same authoritative session.
            baseQueryLedgerEntryRows = session.queryLedgerEntryRows,
            baseQueryMonthlyActivity = session.queryMonthlyActivity,
            baseQueryTransactionDetail = session.queryTransactionDetail,
            catalogSession = session,
            // P7-04.A/B (D-146): the import surface — the Swing pick port and the jvmMain
            // intake orchestration on the same ledger; the P7-04.C host consumes both
            // through the facade to build the typed intake input.
            importFilePickPort = importFilePickPort,
            importFileIntake = importFileIntake,
            importPlatformKind = ImportPlatformKind.DESKTOP,
            importIntakeSessionFactory = { ImportIntakeSessionIdentity.forFilePick(importIntakeGenerator) },
            // P7-04.C: the review read surface + the duplicate-review use case + its id mint +
            // the shared pick-result channel (the Swing onResult above delivers into it).
            baseQueryImportReviewRows = QueryImportReviewRows(importReviewReadAdapter),
            baseQueryImportCandidateDetail = QueryImportCandidateDetail(importReviewReadAdapter),
            baseQueryImportDuplicateReviews = QueryImportDuplicateReviews(importReviewReadAdapter),
            importDuplicateReview = importDuplicateReview,
            importDuplicateReviewIds = {
                ImportDuplicateReviewIds(
                    ImportRequestId(importReviewIdGenerator.next()),
                    ImportDuplicateReviewId(importReviewIdGenerator.next()),
                    ImportStatusHistoryId(importReviewIdGenerator.next()),
                )
            },
            importPickResultChannel = importPickChannel,
        )

    return DesktopLedgerGraph(
        database = database,
        ledgerId = ledgerId,
        categoryId = categoryId,
        paymentAccountId = paymentAccountId,
        useCase = executeConfirmed,
        ledgerClock = ledgerClock,
        facade = facade,
        catalogSession = session,
        catalogCommands = catalogCommands,
    )
}

/**
 * Idempotent bootstrap (spec 5.3): seed the default catalog only for an empty ledger, load the
 * current authority otherwise. An unresolved existing reference fails closed and is surfaced to
 * the startup controller as [P503StartupState.StartupError] (Retry/Exit) - never a static
 * catalog fallback.
 */
private fun bootstrapAuthority(
    store: SqlDelightCatalogStore,
    ledgerId: LedgerId,
) = when (val bootstrapped = store.bootstrap(ledgerId, defaultCatalogSeed())) {
    is CatalogBootstrapResult.Seeded -> bootstrapped.authority
    is CatalogBootstrapResult.AlreadyInitialized -> bootstrapped.authority
    CatalogBootstrapResult.UnknownReference -> throw CatalogBootstrapFailedException(ledgerId)
}

/**
 * Opens the desktop ledger with version detection and conditional migration (P7-01 spec
 * section 5.4, D-143). The existing file is never deleted or overwritten:
 *
 * 1. read `PRAGMA user_version` as `from` (an empty/new file reports 0);
 * 2. empty/new file -> `Schema.create`;
 * 3. `from == current` -> open directly;
 * 4. `from < current` -> `Schema.migrate(driver, from, to)` inside one outer transaction;
 * 5. `from > current` -> fail closed (rejected, never downgraded).
 *
 * Any step failure propagates so [DesktopStartupController] maps it to
 * [P503StartupState.StartupError] with only Retry/Exit.
 */
internal fun openDesktopLedger(databaseUrl: String): CloseableLedgerGraph {
    val driver = JdbcSqliteDriver(databaseUrl)
    return try {
        migrateToCurrentSchema(driver)
        val graph = buildLedgerGraph(driver, createSchema = false)
        CloseableLedgerGraph(graph.facade, { driver.close() }, graph.catalogSession, graph.catalogCommands)
    } catch (failure: Exception) {
        // A failure mid-open (schema create/open/migrate/bootstrap) must not leak the
        // half-opened driver; the startup controller maps it to StartupError.
        driver.close()
        throw failure
    }
}

/**
 * Reads the SQLite user_version and conditionally creates, opens or migrates the schema.
 * Kept separate from [openDesktopLedger] so the fail-closed version path is directly testable.
 *
 * SQLDelight's generated `Schema.create`/`Schema.migrate` perform no `user_version` bookkeeping,
 * so this function stamps the version it just materialized. Without that stamp every subsequent
 * open would re-read 0 and re-run `Schema.create` on an already-populated file. The stamp after a
 * migration runs inside the same outer transaction as `Schema.migrate`, so a failed migration
 * rolls back both the schema changes and the version bump.
 */
internal fun migrateToCurrentSchema(driver: SqlDriver) {
    val currentVersion = LedgerDatabase.Schema.version.toLong()
    val from = driver.readUserVersion()
    when {
        // A3 (review): `user_version == 0` alone does not mean "new file". A populated ledger
        // written before the version stamp existed must never be handed to `Schema.create`
        // (that fails with "table ... already exists" and becomes a permanent StartupError).
        // Probe the actual schema instead and only create when the file is genuinely empty.
        from == 0L && driver.hasTable("catalog_version") -> {
            // Already the current product schema, only the version stamp was missing.
            driver.writeUserVersion(currentVersion)
        }
        from == 0L && driver.hasTable("ledger_transaction") && driver.hasV27StructuralSentinel() -> {
            // Legacy pre-catalog ledger without a version stamp, verified to actually carry the
            // v27 surface (R2: the sentinel guards the 26.sqm rebuild objects, so a real v26 or
            // older file cannot reach here and silently skip 26 -> 27). Treat it as v27 (the last
            // version before the additive catalog tables) and run the structure-only migration in
            // one transaction. A failure rolls back and surfaces as StartupError; the file is
            // never deleted or overwritten.
            val database = LedgerDatabase(driver)
            database.transaction {
                LedgerDatabase.Schema.migrate(driver, LEGACY_UNTAGGED_VERSION, currentVersion)
                driver.writeUserVersion(currentVersion)
            }
        }
        from == 0L && !driver.hasAnyUserTable() -> {
            LedgerDatabase.Schema.create(driver)
            driver.writeUserVersion(currentVersion)
        }
        from == 0L -> {
            // Unknown populated schema without a version stamp (R2: populated but not verifiably
            // v27, e.g. a real v26 file): never create over it and never guess a migration; stamp
            // the current version so opening is deterministic. Downstream catalog bootstrap fails
            // closed visibly if the schema does not match, instead of a partial schema silently
            // acquired by skipping a structural migration.
            driver.writeUserVersion(currentVersion)
        }
        from == currentVersion -> Unit
        from < currentVersion -> {
            val database = LedgerDatabase(driver)
            database.transaction {
                LedgerDatabase.Schema.migrate(driver, from, currentVersion)
                driver.writeUserVersion(currentVersion)
            }
        }
        else -> throw IllegalStateException(
            "Library schema version $from is newer than the supported version $currentVersion; refusing to open",
        )
    }
}

/** Last schema version before the additive P7-01 `catalog_*` tables (spec 5.2). */
private const val LEGACY_UNTAGGED_VERSION = 27L

/**
 * R2: structural sentinels used to confirm an untagged populated file really is the v27 surface
 * before running the v27 -> v28 migration. `evidence_projection_guard_update` and
 * `evidence_projection_guard_delete` were created by `25.sqm` (v25 -> v26); the
 * `evidence_projection_current_by_evidence` unique index and `reconciliation_correction_snapshot`
 * were created by `26.sqm` step 2/3 (v26 -> v27). Because the conjunction (`.all`) requires the
 * v27-only pair, a file at exactly v26 (guards present, index/snapshot absent) and any older file
 * both fail the sentinel and fall through to the stamp-only fail-closed branch, never a guessed
 * migrate; only a real v27+ surface passes.
 */
private val V27_STRUCTURAL_SENTINELS =
    listOf(
        "evidence_projection_current_by_evidence",
        "evidence_projection_guard_update",
        "evidence_projection_guard_delete",
        "reconciliation_correction_snapshot",
    )

private fun SqlDriver.hasV27StructuralSentinel(): Boolean = V27_STRUCTURAL_SENTINELS.all { hasSchemaObject(it) }

private fun SqlDriver.hasSchemaObject(name: String): Boolean {
    var found = false
    executeQuery(
        null,
        "SELECT count(*) FROM sqlite_master WHERE name = ?",
        { cursor ->
            if (cursor.next().value) found = (cursor.getLong(0) ?: 0L) > 0L
            app.cash.sqldelight.db.QueryResult.Unit
        },
        1,
        { bindString(0, name) },
    )
    return found
}

private fun SqlDriver.hasTable(name: String): Boolean {
    var found = false
    executeQuery(
        null,
        "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
        { cursor ->
            if (cursor.next().value) found = (cursor.getLong(0) ?: 0L) > 0L
            app.cash.sqldelight.db.QueryResult.Unit
        },
        1,
        { bindString(0, name) },
    )
    return found
}

private fun SqlDriver.hasAnyUserTable(): Boolean {
    var found = false
    executeQuery(
        null,
        "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
        { cursor ->
            if (cursor.next().value) found = (cursor.getLong(0) ?: 0L) > 0L
            app.cash.sqldelight.db.QueryResult.Unit
        },
        0,
    )
    return found
}

private fun SqlDriver.writeUserVersion(version: Long) {
    execute(null, "PRAGMA user_version = $version", 0)
}

private fun SqlDriver.readUserVersion(): Long {
    var version = 0L
    executeQuery(
        null,
        "PRAGMA user_version",
        { cursor ->
            if (cursor.next().value) version = cursor.getLong(0) ?: 0L
            app.cash.sqldelight.db.QueryResult.Unit
        },
        0,
    )
    return version
}

private val secureRandom = SecureRandom()

private fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also(secureRandom::nextBytes)
