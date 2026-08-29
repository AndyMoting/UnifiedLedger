package com.unifiedledger.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
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
import java.security.SecureRandom
import kotlin.time.Clock

/**
 * Android composition root (P5-02, IMP-11). The placeholder UI never writes through the
 * database handle directly; it only displays that the local test ledger is open.
 */
@Composable
fun app() {
    val context = LocalContext.current
    val graph =
        remember(context) {
            buildLedgerGraph(AndroidSqliteDriver(LedgerDatabase.Schema, context, "ledger.db"))
        }

    MaterialTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("UnifiedLedger 本地测试账本已打开（${graph.ledgerId.value}）")
            Button(onClick = {}) {
                Text("占位按钮")
            }
        }
    }
}

private data class LedgerGraph(
    val ledgerId: LedgerId,
    val useCase: ExecuteConfirmedManualExpense,
    val ledgerClock: LedgerClock,
)

private fun buildLedgerGraph(driver: AndroidSqliteDriver): LedgerGraph {
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

    return LedgerGraph(
        ledgerId = ledgerId,
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
