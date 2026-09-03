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
import com.unifiedledger.application.CommitOnceInvocationTracker
import com.unifiedledger.application.ConfirmedExpenseTransactionFactory
import com.unifiedledger.application.ConfirmedManualExpenseCommit
import com.unifiedledger.application.ExecuteConfirmedManualExpense
import com.unifiedledger.application.ExecuteManualExpenseSave
import com.unifiedledger.application.ExecuteManualExpenseSubmission
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.QueryLedgerCurrentState
import com.unifiedledger.application.QueryManualExpenseOptions
import com.unifiedledger.application.ResolveManualExpenseCommitStatus
import com.unifiedledger.application.SummarizeLedgerActivity
import com.unifiedledger.application.UuidV7ConfirmedManualExpenseIdSource
import com.unifiedledger.application.UuidV7Generator
import com.unifiedledger.application.UuidV7ManualExpenseRequestIdSource
import com.unifiedledger.data.AndroidLedgerDatabaseHandle
import com.unifiedledger.data.SqlDelightLedgerCurrentStateReadAdapter
import com.unifiedledger.data.createAndroidLedgerDatabase
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseCommand
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
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
                        CloseableLedgerGraph(buildLedgerFacade(handle)) { handle.close() }
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
 */
internal data class CloseableLedgerGraph(
    val facade: P503LedgerFacade,
    val close: () -> Unit,
)

private const val LOG_TAG = "UnifiedLedger"

private fun buildLedgerFacade(handle: AndroidLedgerDatabaseHandle): P503LedgerFacade {
    val database = handle.database

    val ledgerId = LedgerId("ledger-local-test")
    val currency = CurrencyUnit("CNY", 2)
    val paymentAccountId = AccountId("asset-payment-local")
    val expenseAccountId = AccountId("expense-account-local")
    val parentCategoryId = CategoryId("expense-category-food")
    val categoryId = CategoryId("expense-category-breakfast")

    val catalog =
        syntheticCatalog(
            ledgerId = ledgerId,
            currency = currency,
            paymentAccountId = paymentAccountId,
            expenseAccountId = expenseAccountId,
            parentCategoryId = parentCategoryId,
            categoryId = categoryId,
        )

    val port = handle.commitPort
    val tracker = CommitOnceInvocationTracker(port)
    val factory =
        ConfirmedExpenseTransactionFactory { request, ids ->
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
    val idSource = UuidV7ConfirmedManualExpenseIdSource(UuidV7Generator(::secureRandomBytes))
    val requestIdSource = UuidV7ManualExpenseRequestIdSource(UuidV7Generator(::secureRandomBytes))
    val ledgerClock = LedgerClock { Clock.System.now() }
    val executeConfirmed = ExecuteConfirmedManualExpense(tracker, idSource, factory)
    val executeSave = ExecuteManualExpenseSave(executeConfirmed)
    val readAdapter = SqlDelightLedgerCurrentStateReadAdapter(database)
    val queryCurrentState = QueryLedgerCurrentState(readAdapter, ledgerId, catalog)
    val resolver = ResolveManualExpenseCommitStatus(readAdapter)
    val submission = ExecuteManualExpenseSubmission(executeSave, tracker, resolver)

    return P503LedgerFacade(
        ledgerId = ledgerId,
        currency = currency,
        catalog = catalog,
        parseAmount = ParseManualExpenseAmount(),
        optionsProvider = QueryManualExpenseOptions(ledgerId, catalog),
        queryCurrentState = queryCurrentState,
        resolveCommitStatus = resolver,
        submitExpense = submission,
        requestIdSource = requestIdSource,
        ledgerClock = ledgerClock,
        summarizeActivity = SummarizeLedgerActivity(catalog),
    )
}

private fun syntheticCatalog(
    ledgerId: LedgerId,
    currency: CurrencyUnit,
    paymentAccountId: AccountId,
    expenseAccountId: AccountId,
    parentCategoryId: CategoryId,
    categoryId: CategoryId,
): LedgerCatalog =
    when (
        val result =
            LedgerCatalog.create(
                accounts =
                    listOf(
                        Account(
                            id = paymentAccountId,
                            ledgerId = ledgerId,
                            kind = AccountKind.ASSET,
                            currency = currency,
                            ownedByUser = true,
                            realAccount = true,
                        ),
                        Account(
                            id = expenseAccountId,
                            ledgerId = ledgerId,
                            kind = AccountKind.EXPENSE,
                            currency = currency,
                            ownedByUser = false,
                            realAccount = false,
                        ),
                    ),
                categories =
                    listOf(
                        Category(
                            id = parentCategoryId,
                            ledgerId = ledgerId,
                            parentId = null,
                            postingAccountId = null,
                            active = true,
                        ),
                        Category(
                            id = categoryId,
                            ledgerId = ledgerId,
                            parentId = parentCategoryId,
                            postingAccountId = expenseAccountId,
                            active = true,
                        ),
                    ),
            )
    ) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> error("synthetic local-test catalog must be valid")
    }

private val secureRandom = SecureRandom()

private fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also(secureRandom::nextBytes)
