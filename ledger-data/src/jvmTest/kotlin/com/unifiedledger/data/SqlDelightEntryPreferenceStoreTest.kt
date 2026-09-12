package com.unifiedledger.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.EntryPinResult
import com.unifiedledger.application.EntryPinTarget
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.EntryFoundationViolation
import com.unifiedledger.domain.LedgerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * P7-02 E-4/B06 pin persistence evidence: a toggle flips one row, pins survive a reopen,
 * unknown/cross-ledger targets are typed zero-write rejections, and pinning never changes the
 * catalog rows (an inactive object stays inactive).
 */
class SqlDelightEntryPreferenceStoreTest {
    private val ledger = LedgerId("ledger-pin-e2e")
    private val otherLedger = LedgerId("ledger-pin-other")
    private val pinnedAt = Instant.parse("2026-09-12T00:00:00Z")

    @Test
    fun toggleFlipsMembershipAndPersistsAcrossReopen() {
        pinHarness().use { harness ->
            val target = EntryPinTarget.AccountTarget(ledger, AccountId("asset-a"))
            val toggled = assertIs<EntryPinResult.Toggled>(harness.store.togglePin(target, pinnedAt))
            assertEquals(true, toggled.pinned)
            assertEquals(setOf<EntryPinTarget>(target), harness.store.pinnedTargets(ledger))
            assertEquals(1L, harness.pinCount(ledger))

            // A fresh store over the same reopened database still sees the pin (app restart).
            val reopened = SqlDelightEntryPreferenceStore(LedgerDatabase(harness.driver))
            assertEquals(setOf<EntryPinTarget>(target), reopened.pinnedTargets(ledger))

            val untoggled = assertIs<EntryPinResult.Toggled>(reopened.togglePin(target, pinnedAt))
            assertEquals(false, untoggled.pinned)
            assertEquals(emptySet<EntryPinTarget>(), reopened.pinnedTargets(ledger))
            assertEquals(0L, harness.pinCount(ledger))
        }
    }

    @Test
    fun categoryTargetsToggleIndependentlyFromAccounts() {
        pinHarness().use { harness ->
            val account = EntryPinTarget.AccountTarget(ledger, AccountId("asset-a"))
            val category = EntryPinTarget.CategoryTarget(ledger, CategoryId("expense-leaf"))
            harness.store.togglePin(account, pinnedAt)
            harness.store.togglePin(category, pinnedAt)
            assertEquals(setOf<EntryPinTarget>(account, category), harness.store.pinnedTargets(ledger))
            assertEquals(2L, harness.pinCount(ledger))
        }
    }

    @Test
    fun unknownAndCrossLedgerTargetsAreTypedZeroWriteRejections() {
        pinHarness().use { harness ->
            val unknown = EntryPinTarget.AccountTarget(ledger, AccountId("asset-missing"))
            val rejected = assertIs<EntryPinResult.Rejected>(harness.store.togglePin(unknown, pinnedAt))
            assertEquals(EntryFoundationViolation.EntryPinTargetNotFound, rejected.violation)
            assertEquals(0L, harness.pinCount(ledger))

            // asset-b exists only in the other ledger: a same-id pin there is a cross-ledger miss.
            val crossLedger = EntryPinTarget.AccountTarget(ledger, AccountId("asset-b"))
            val crossRejected = assertIs<EntryPinResult.Rejected>(harness.store.togglePin(crossLedger, pinnedAt))
            assertEquals(EntryFoundationViolation.EntryPinTargetNotFound, crossRejected.violation)
            assertEquals(0L, harness.pinCount(ledger))

            val unknownCategory = EntryPinTarget.CategoryTarget(ledger, CategoryId("category-missing"))
            assertIs<EntryPinResult.Rejected>(harness.store.togglePin(unknownCategory, pinnedAt))
            assertEquals(0L, harness.pinCount(ledger))
        }
    }

    @Test
    fun pinningAnInactiveObjectNeverReactivatesIt() {
        pinHarness().use { harness ->
            val inactive = EntryPinTarget.AccountTarget(ledger, AccountId("asset-inactive"))
            val toggled = assertIs<EntryPinResult.Toggled>(harness.store.togglePin(inactive, pinnedAt))
            assertEquals(true, toggled.pinned)
            // Ordering preference only: the catalog row keeps its inactive flag.
            assertEquals(false, harness.accountActive(ledger, "asset-inactive"))
        }
    }

    @Test
    fun pinsAreLedgerScoped() {
        pinHarness().use { harness ->
            harness.store.togglePin(EntryPinTarget.AccountTarget(otherLedger, AccountId("asset-b")), pinnedAt)
            assertEquals(1L, harness.pinCount(otherLedger))
            assertEquals(0L, harness.pinCount(ledger))
            assertEquals(emptySet<EntryPinTarget>(), harness.store.pinnedTargets(ledger))
        }
    }

    private class PinHarness(
        val driver: JdbcSqliteDriver,
    ) : AutoCloseable {
        val store = SqlDelightEntryPreferenceStore(LedgerDatabase(driver))

        fun pinCount(ledgerId: LedgerId): Long =
            driver
                .executeQuery(
                    null,
                    "SELECT count(*) FROM entry_pin WHERE ledger_id = '${ledgerId.value}'",
                    { cursor ->
                        check(cursor.next().value)
                        QueryResult.Value(requireNotNull(cursor.getLong(0)))
                    },
                    0,
                ).value

        fun accountActive(
            ledgerId: LedgerId,
            accountId: String,
        ): Boolean =
            driver
                .executeQuery(
                    null,
                    "SELECT active FROM catalog_account WHERE ledger_id = '${ledgerId.value}' AND account_id = '$accountId'",
                    { cursor ->
                        check(cursor.next().value)
                        QueryResult.Value(requireNotNull(cursor.getLong(0)))
                    },
                    0,
                ).value == 1L

        override fun close() = driver.close()
    }

    private fun pinHarness(): PinHarness {
        val driver =
            JdbcSqliteDriver(
                JdbcSqliteDriver.IN_MEMORY,
                java.util.Properties().apply { setProperty("foreign_keys", "true") },
            )
        LedgerDatabase.Schema.create(driver)
        val seedStatements =
            listOf(
                "INSERT INTO catalog_account VALUES ('ledger-pin-e2e', 'asset-a', '资产甲', 'ASSET', 'CNY', 2, 1, 1, NULL, 0, 1)",
                "INSERT INTO catalog_account VALUES ('ledger-pin-e2e', 'asset-inactive', '资产停用', 'ASSET', 'CNY', 2, 1, 1, NULL, 0, 0)",
                "INSERT INTO catalog_account VALUES ('ledger-pin-e2e', 'expense-posting', '支出过账', 'EXPENSE', 'CNY', 2, 1, 0, NULL, 0, 1)",
                "INSERT INTO catalog_account VALUES ('ledger-pin-other', 'asset-b', '他账本资产', 'ASSET', 'CNY', 2, 1, 1, NULL, 0, 1)",
                "INSERT INTO catalog_category VALUES ('ledger-pin-e2e', 'expense-group', 'EXPENSE', NULL, NULL, 1)",
                "INSERT INTO catalog_category VALUES ('ledger-pin-e2e', 'expense-leaf', 'EXPENSE', 'expense-group', 'expense-posting', 1)",
            )
        seedStatements.forEach { statement -> driver.execute(null, statement, 0) }
        return PinHarness(driver)
    }
}
