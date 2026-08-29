package com.unifiedledger.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ConfirmedExpenseTransactionFactory
import com.unifiedledger.application.ConfirmedManualExpenseCommit
import com.unifiedledger.application.ExecuteConfirmedManualExpense
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.UuidV7ConfirmedManualExpenseIdSource
import com.unifiedledger.application.UuidV7Generator
import com.unifiedledger.data.SqlDelightConfirmedManualExpenseCommitPort
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
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.time.Clock

private const val LOCAL_TEST_LEDGER_FILE = "build/ledger-local-test.db"
private const val LOCAL_TEST_LEDGER_URL = "jdbc:sqlite:$LOCAL_TEST_LEDGER_FILE"

/**
 * Desktop placeholder entry point (P5-02, IMP-1/IMP-10/IMP-12). Opens a local test ledger
 * (empty bootstrap on first run) and shows a minimal Compose placeholder window.
 */
fun main() {
    val driver = JdbcSqliteDriver(LOCAL_TEST_LEDGER_URL)
    val graph = buildLedgerGraph(driver, createSchema = !Files.exists(Path.of(LOCAL_TEST_LEDGER_FILE)))

    application {
        Window(onCloseRequest = ::exitApplication, title = "UnifiedLedger Desktop") {
            MaterialTheme {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("UnifiedLedger 本地测试账本已打开（${graph.ledgerId.value}）")
                    Button(onClick = {}) {
                        Text("占位按钮")
                    }
                }
            }
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
)

/**
 * Desktop composition root (IMP-10). The `database` handle is exposed only for test
 * read-back; the placeholder UI and composition root never write through it directly.
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
    val ledgerClock = LedgerClock { Clock.System.now() }
    val useCase = ExecuteConfirmedManualExpense(port, idSource, factory)

    return DesktopLedgerGraph(
        database = database,
        ledgerId = ledgerId,
        categoryId = categoryId,
        paymentAccountId = paymentAccountId,
        useCase = useCase,
        ledgerClock = ledgerClock,
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
