package com.unifiedledger.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import java.nio.file.Files
import java.nio.file.Path
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
    openDatabase: () -> P503LedgerFacade,
    onExit: () -> Unit,
) {
    val controller = remember { DesktopStartupController(openDatabase).also { it.start() } }
    Window(onCloseRequest = onExit, title = "UnifiedLedger Desktop") {
        val facade = controller.facade
        when {
            controller.state == P503StartupState.Ready && facade != null ->
                P503App(facade, onExit = onExit)
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
 * Testable composition-root startup state (spec section 8). Exposes the shared
 * [P503StartupState] transitions and never exposes a business graph or database handle
 * after a failed start.
 */
internal class DesktopStartupController(
    private val openDatabase: () -> P503LedgerFacade,
) {
    var state by mutableStateOf<P503StartupState>(P503StartupState.Starting)
        private set

    var facade: P503LedgerFacade? by mutableStateOf(null)
        private set

    fun start() {
        state = P503StartupState.Starting
        facade = null
        state =
            try {
                facade = openDatabase()
                P503StartupState.Ready
            } catch (failure: Exception) {
                System.err.println("UnifiedLedger startup failed: " + failure)
                failure.printStackTrace()
                P503StartupState.StartupError
            }
    }
}

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

/** Opens (creating the current schema only when the file does not exist) and returns the facade. */
internal fun openDesktopLedger(databaseUrl: String): P503LedgerFacade {
    val driver = JdbcSqliteDriver(databaseUrl)
    val filePath = databaseUrl.removePrefix("jdbc:sqlite:")
    return buildLedgerGraph(driver, createSchema = !Files.exists(Path.of(filePath))).facade
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
