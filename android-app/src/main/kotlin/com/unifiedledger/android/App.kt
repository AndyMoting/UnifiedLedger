package com.unifiedledger.android

import android.app.Activity
import android.util.Log
import androidx.activity.compose.BackHandler
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
import com.unifiedledger.application.CatalogBootstrapFailedException
import com.unifiedledger.application.CatalogConsumerSession
import com.unifiedledger.application.CommitOnceInvocationTracker
import com.unifiedledger.application.ConfirmedExpenseTransactionFactory
import com.unifiedledger.application.ConfirmedManualExpenseCommit
import com.unifiedledger.application.DEFAULT_EXPENSE_LEAF_ID
import com.unifiedledger.application.DEFAULT_MANAGEABLE_ACCOUNT_ID
import com.unifiedledger.application.ExecuteCatalogCommand
import com.unifiedledger.application.ExecuteConfirmedManualExpense
import com.unifiedledger.application.ExecuteManualExpenseSave
import com.unifiedledger.application.ExecuteManualExpenseSubmission
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.ParseManualExpenseOccurredAt
import com.unifiedledger.application.QueryCatalogSnapshot
import com.unifiedledger.application.ResolveManualExpenseCommitStatus
import com.unifiedledger.application.UuidV7CatalogEntityIdSource
import com.unifiedledger.application.UuidV7CatalogManagementRequestIdSource
import com.unifiedledger.application.UuidV7ConfirmedManualExpenseIdSource
import com.unifiedledger.application.UuidV7Generator
import com.unifiedledger.application.UuidV7ManualExpenseRequestIdSource
import com.unifiedledger.data.AndroidLedgerDatabaseHandle
import com.unifiedledger.data.CatalogBootstrapResult
import com.unifiedledger.data.SqlDelightLedgerCurrentStateReadAdapter
import com.unifiedledger.data.createAndroidLedgerDatabase
import com.unifiedledger.data.defaultCatalogSeed
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseCommand
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.createAssetPaidOrdinaryExpense
import com.unifiedledger.ui.P503App
import com.unifiedledger.ui.P503LedgerFacade
import com.unifiedledger.ui.P503StartupScreen
import com.unifiedledger.ui.P503StartupState
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
    val controller =
        remember(context) {
            AndroidStartupController(
                openDatabase = {
                    // P5-04.4 S3: a failure mid-open (after the handle exists) must not leak
                    // the driver, so the handle is closed before rethrowing; the controller
                    // additionally closes any graph it already holds in its catch block.
                    val handle = createAndroidLedgerDatabase(context, "ledger.db")
                    try {
                        buildLedgerGraph(handle)
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
 */
internal data class CloseableLedgerGraph(
    val facade: P503LedgerFacade,
    val close: () -> Unit,
    val catalogSession: CatalogConsumerSession? = null,
    val catalogCommands: ExecuteCatalogCommand? = null,
)

private const val LOG_TAG = "UnifiedLedger"

private fun buildLedgerGraph(handle: AndroidLedgerDatabaseHandle): CloseableLedgerGraph {
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
    val session =
        CatalogConsumerSession(
            reader = store,
            ledgerId = ledgerId,
            initialAuthority = authority,
            readPort = readAdapter,
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
    val ledgerClock = LedgerClock { Clock.System.now() }
    val executeConfirmed = ExecuteConfirmedManualExpense(tracker, idSource, factory)
    val executeSave = ExecuteManualExpenseSave(executeConfirmed)
    val resolver = ResolveManualExpenseCommitStatus(readAdapter)
    val submission = ExecuteManualExpenseSubmission(executeSave, tracker, resolver)
    val catalogCommands =
        ExecuteCatalogCommand(
            commitPort = store,
            requestIdSource = UuidV7CatalogManagementRequestIdSource(UuidV7Generator(::secureRandomBytes)),
            entityIdSource = UuidV7CatalogEntityIdSource(UuidV7Generator(::secureRandomBytes)),
            categoryReferenceProbe = store,
        )
    val catalogRequestIds = UuidV7CatalogManagementRequestIdSource(UuidV7Generator(::secureRandomBytes))
    val snapshotQuery = QueryCatalogSnapshot(store)
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
            // P7-01.D: management reads the same session and refreshes it after every command.
            catalogSnapshot = { snapshotQuery.query(ledgerId) },
            executeCatalogCommand = catalogCommands,
            newCatalogRequestId = { catalogRequestIds.next() },
            refreshCatalog = { session.refresh() },
            catalogSession = session,
        )
    return CloseableLedgerGraph(facade, handle::close, session, catalogCommands)
}

private val secureRandom = SecureRandom()

private fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also(secureRandom::nextBytes)
