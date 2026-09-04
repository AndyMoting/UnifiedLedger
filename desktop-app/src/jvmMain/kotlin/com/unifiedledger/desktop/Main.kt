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
import com.unifiedledger.data.SqlDelightConfirmedManualExpenseCommitPort
import com.unifiedledger.data.SqlDelightLedgerCurrentStateReadAdapter
import com.unifiedledger.data.db.LedgerDatabase
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
import java.awt.Dialog
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.io.path.absolutePathString
import kotlin.time.Clock
import java.awt.Window as AwtWindow

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
 * [onBack] may touch Compose state directly. While any dialog of this process is showing,
 * Escape is yielded unconditionally (regardless of which window holds focus) so the picker
 * dialog absorbs it and the edit page stays open (D-131 spec 3.5); once the dialog closes,
 * the existing edit-page Escape semantics resume. Window closing is unchanged.
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
                    if (AwtWindow.getWindows().any { it is Dialog && it.isShowing }) {
                        false
                    } else {
                        latestOnBack()
                        true
                    }
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
 */
internal data class CloseableLedgerGraph(
    val facade: P503LedgerFacade,
    val close: () -> Unit,
)

internal data class DesktopLedgerGraph(
    val database: LedgerDatabase,
    val ledgerId: LedgerId,
    val categoryId: CategoryId,
    val paymentAccountId: AccountId,
    val useCase: ExecuteConfirmedManualExpense,
    val ledgerClock: LedgerClock,
    val facade: P503LedgerFacade,
)

/**
 * Desktop composition-root graph (IMP-10 wiring, P5-03 spec 10.1). The `database` handle
 * is exposed only for test read-back; the UI and the write path never write through it
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

    val port = SqlDelightConfirmedManualExpenseCommitPort(database, driver)
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
    val facade =
        P503LedgerFacade(
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

    return DesktopLedgerGraph(
        database = database,
        ledgerId = ledgerId,
        categoryId = categoryId,
        paymentAccountId = paymentAccountId,
        useCase = executeConfirmed,
        ledgerClock = ledgerClock,
        facade = facade,
    )
}

/**
 * Opens (creating the current schema only when the file does not exist) and returns the
 * facade wrapped as a [CloseableLedgerGraph] so the composition root can close the driver on
 * retry or shutdown (P5-04.4 S3).
 */
internal fun openDesktopLedger(databaseUrl: String): CloseableLedgerGraph {
    val driver = JdbcSqliteDriver(databaseUrl)
    val filePath = databaseUrl.removePrefix("jdbc:sqlite:")
    return try {
        // P5-04.4 S3: keep the driver reference so the retry/shutdown path can close it.
        val graph = buildLedgerGraph(driver, createSchema = !Files.exists(Path.of(filePath)))
        CloseableLedgerGraph(graph.facade) { driver.close() }
    } catch (failure: Exception) {
        // A failure mid-open (schema create/open) must not leak the half-opened driver.
        driver.close()
        throw failure
    }
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
