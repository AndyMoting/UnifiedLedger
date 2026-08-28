package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.CategoryRenameIdentity
import com.unifiedledger.application.ConfirmedCategoryRenameResult
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryNameVersion
import com.unifiedledger.domain.CategoryNameVersionStatus
import com.unifiedledger.domain.CategoryRenameChange
import com.unifiedledger.domain.CategoryRenameViolation
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerId
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * D-087 RG-02 `category_rename` minimal closed loop persistence: the append-only
 * `rg02_category_name_history` transition (supersede current + append next) is
 * atomic, and a rejected rename leaves no residue.
 */
class SqlDelightConfirmedCategoryRenameCommitPortTest {
    private val ledgerId = "ledger-a"
    private val categoryId = "income-category-salary"

    @Test
    fun `accepted rename supersedes version one and appends version two in one transition`() {
        renameHarness().use { harness ->
            harness.seed(categoryId, 1L, "工资")

            val result = harness.port.commitOnce(harness.identity(categoryId), "薪资", harness.renameCallback())

            assertIs<ConfirmedCategoryRenameResult.Accepted>(result)
            assertEquals(
                listOf(
                    listOf(categoryId, 1L, "工资", "SUPERSEDED"),
                    listOf(categoryId, 2L, "薪资", "CURRENT"),
                ),
                harness.history(),
            )
        }
    }

    @Test
    fun `second accepted rename appends version three`() {
        renameHarness().use { harness ->
            harness.seed(categoryId, 1L, "工资")
            harness.port.commitOnce(harness.identity(categoryId), "薪资", harness.renameCallback())
            harness.port.commitOnce(harness.identity(categoryId), "薪金", harness.renameCallback("薪金"))

            assertEquals(
                listOf(
                    listOf(categoryId, 1L, "工资", "SUPERSEDED"),
                    listOf(categoryId, 2L, "薪资", "SUPERSEDED"),
                    listOf(categoryId, 3L, "薪金", "CURRENT"),
                ),
                harness.history(),
            )
        }
    }

    @Test
    fun `rename without a seeded current version is rejected without residue`() {
        renameHarness().use { harness ->
            val result = harness.port.commitOnce(harness.identity(categoryId), "薪资", harness.renameCallback())

            assertEquals(
                CategoryRenameViolation.CurrentNameVersionMissing,
                assertIs<ConfirmedCategoryRenameResult.Rejected>(result).violation,
            )
            assertEquals(emptyList(), harness.history())
        }
    }

    @Test
    fun `blank name rejection leaves the seeded history untouched`() {
        renameHarness().use { harness ->
            harness.seed(categoryId, 1L, "工资")

            val result = harness.port.commitOnce(harness.identity(categoryId), "  ", harness.renameCallback("  "))

            assertEquals(
                CategoryRenameViolation.EmptyName,
                assertIs<ConfirmedCategoryRenameResult.Rejected>(result).violation,
            )
            assertEquals(
                listOf(listOf(categoryId, 1L, "工资", "CURRENT")),
                harness.history(),
            )
        }
    }
}

private class RenameHarness(
    val database: LedgerDatabase,
    val port: SqlDelightConfirmedCategoryRenameCommitPort,
    private val driver: JdbcSqliteDriver,
) : AutoCloseable {
    val ledgerId = "ledger-a"

    fun identity(categoryId: String) = CategoryRenameIdentity(LedgerId(ledgerId), CategoryId(categoryId))

    fun seed(
        categoryId: String,
        version: Long,
        name: String,
    ) {
        database.ledgerQueries.insertRg02CategoryNameHistory(ledgerId, categoryId, version, name, "CURRENT")
    }

    fun history(): List<List<Any?>> =
        database.ledgerQueries
            .selectRg02CategoryNameHistory(ledgerId) { categoryId, versionNumber, name, status ->
                listOf(categoryId, versionNumber, name, status)
            }.executeAsList()

    fun renameCallback(newName: String = "薪资"): (CategoryNameVersion?) -> DomainResult<CategoryRenameChange> =
        { current ->
            when {
                newName.isBlank() -> DomainResult.Failure(CategoryRenameViolation.EmptyName)
                current == null -> DomainResult.Failure(CategoryRenameViolation.CurrentNameVersionMissing)
                current.status != CategoryNameVersionStatus.CURRENT ->
                    DomainResult.Failure(CategoryRenameViolation.CurrentNameVersionMissing)
                else ->
                    DomainResult.Success(
                        CategoryRenameChange(
                            superseded = current.copy(status = CategoryNameVersionStatus.SUPERSEDED),
                            current =
                                CategoryNameVersion(
                                    CategoryId(current.categoryId.value),
                                    current.version + 1,
                                    newName,
                                    CategoryNameVersionStatus.CURRENT,
                                ),
                        ),
                    )
            }
        }

    override fun close() = driver.close()
}

private fun renameHarness(): RenameHarness {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { setProperty("foreign_keys", "true") })
    LedgerDatabase.Schema.create(driver)
    val database = LedgerDatabase(driver)
    return RenameHarness(database, SqlDelightConfirmedCategoryRenameCommitPort(database, driver), driver)
}
