package com.unifiedledger.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.CatalogCommandResult
import com.unifiedledger.application.ImportFileIntakeInput
import com.unifiedledger.application.ImportFileIntakeOutcome
import com.unifiedledger.application.ImportFormatCapabilities
import com.unifiedledger.application.ImportIntakeRecordDisposition
import com.unifiedledger.application.ImportPlatformKind
import com.unifiedledger.application.SelfEntryMatchBasis
import com.unifiedledger.application.import.selfentry.SelfEntryTemplateGenerator
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.LedgerCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * P8-04 i4 (D-18 supply seam; spec `docs/specs/2026-11-14-p8-04-self-entry-import-design.md`
 * §4 i4): the composition-root intake-catalog supplier, pinned on the REAL desktop root.
 *
 * The seam: `P503LedgerFacade.importIntakeCatalog` is the D-18 supply seam the i3a nullable
 * input left open. Null = matching simply absent (the honest pre-i4 face every startup test
 * and the instrumented graph keep compiling with — the nullable default's zero-ripple
 * property); non-null = the host invokes it INSIDE the intake lease, once per dispatch (the
 * confirm-factory discipline), and the returned snapshot is what the self-entry arm matches
 * against. The composition roots inject `{ session.authority.catalog }`, so the read is fresh
 * per dispatch: the session's authority is rebuilt by `refresh()`, and the supplier reads the
 * CURRENT one at call time — never a construction-time stale snapshot.
 *
 * The vectors drive the REAL `buildLedgerGraph` (in-memory SQLite, the desktop
 * composition-root test precedent) with the real jvmMain orchestrator and the real generated
 * template: the template's 支出 example row (分类 餐饮, 账户1 现金) is the probe — the seed
 * catalog already carries the active 餐饮 EXPENSE group, while 现金 exists only after a real
 * catalog command, so the funding slot discriminates the pre- and post-refresh snapshots.
 */
class DesktopImportIntakeCatalogSupplyTest {
    @Test
    fun theWiredRootSuppliesTheCatalogAndTheIntakeArmMatchesAgainstIt() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val graph = buildLedgerGraph(driver, createSchema = true)

            // The probe row's funding account must EXIST in the supplied snapshot: a real
            // catalog command over the production chain, then the session refresh.
            assertIs<CatalogCommandResult.Accepted>(
                graph.catalogCommands.createAccount(graph.ledgerId, "现金", AccountKind.ASSET, expectedCatalogVersion = 1L),
            )
            graph.catalogSession.refresh()

            // The wired surface is the composition-root supplier over the session authority.
            val supplier = checkNotNull(graph.facade.importIntakeCatalog)
            val supplied: LedgerCatalog = checkNotNull(supplier())
            assertEquals(
                listOf("现金", "默认支付账户"),
                supplied.accounts
                    .filter { it.active && it.ownedByUser && it.realAccount && it.kind == AccountKind.ASSET }
                    .map { it.name }
                    .sorted(),
            )

            // One dispatch with the supplier's fresh snapshot: the generated template's 支出
            // row resolves 餐饮 and 现金 — the supplied catalog reached the arm.
            val outcome =
                graph.facade.importFileIntake!!.intake(
                    ImportFileIntakeInput(
                        format = ImportFormatCapabilities.SELF_ENTRY_XLSX.identifier,
                        platform = ImportPlatformKind.DESKTOP,
                        session = graph.facade.importIntakeSessionFactory()!!,
                        bytes = SelfEntryTemplateGenerator.generateTemplate(),
                        catalog = supplier(),
                    ),
                )
            val intaken = assertIs<ImportFileIntakeOutcome.Accepted>(outcome)
            val basis = assertIs<SelfEntryMatchBasis>(intaken.records[0].matchBasis)
            assertEquals("餐饮", basis.categorySourceText)
            assertEquals("餐饮", basis.categoryTargetName)
            assertEquals("现金", basis.fundingAccountSourceText)
            assertEquals("现金", basis.fundingAccountTargetName)
            assertNull(basis.fundingAccountUnmatchedReason)
        } finally {
            driver.close()
        }
    }

    @Test
    fun theSupplierReadsTheCurrentAuthoritySoARefreshIsVisibleToTheNextDispatch() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val graph = buildLedgerGraph(driver, createSchema = true)
            val supplier = checkNotNull(graph.facade.importIntakeCatalog)

            // Dispatch 1 (before the account exists): the category resolves against the seed
            // catalog, the funding slot is typed-unmatched — the supplier handed the arm the
            // THEN-current authority.
            val before =
                graph.facade.importFileIntake!!.intake(
                    ImportFileIntakeInput(
                        format = ImportFormatCapabilities.SELF_ENTRY_XLSX.identifier,
                        platform = ImportPlatformKind.DESKTOP,
                        session = graph.facade.importIntakeSessionFactory()!!,
                        bytes = SelfEntryTemplateGenerator.generateTemplate(),
                        catalog = supplier(),
                    ),
                )
            val beforeBasis =
                assertIs<SelfEntryMatchBasis>(assertIs<ImportFileIntakeOutcome.Accepted>(before).records[0].matchBasis)
            // The Unmatched basis: the category slot ALREADY resolves against the seed (the
            // active 餐饮 group uniquely matches the bare-leaf path), while the funding slot is
            // typed-unmatched — the supplier handed the arm the THEN-current authority, whose
            // funding truth (no 现金 yet) is exactly what this dispatch's basis discloses.
            assertEquals("餐饮", beforeBasis.categoryTargetName)
            assertNull(beforeBasis.categoryUnmatchedReason)
            assertNull(beforeBasis.fundingAccountTargetName)
            assertEquals("ACCOUNT_NOT_FOUND", beforeBasis.fundingAccountUnmatchedReason)

            // A real catalog command + the session refresh: the supplier's NEXT read sees the
            // post-refresh authority without any graph rebuild.
            assertIs<CatalogCommandResult.Accepted>(
                graph.catalogCommands.createAccount(graph.ledgerId, "现金", AccountKind.ASSET, expectedCatalogVersion = 1L),
            )
            graph.catalogSession.refresh()

            // Dispatch 2 (a fresh pick session): the same template row now resolves 现金 —
            // the fresh-read property the lease-discipline note on the facade claims.
            val after =
                graph.facade.importFileIntake!!.intake(
                    ImportFileIntakeInput(
                        format = ImportFormatCapabilities.SELF_ENTRY_XLSX.identifier,
                        platform = ImportPlatformKind.DESKTOP,
                        session = graph.facade.importIntakeSessionFactory()!!,
                        bytes = SelfEntryTemplateGenerator.generateTemplate(),
                        catalog = supplier(),
                    ),
                )
            val afterBasis =
                assertIs<SelfEntryMatchBasis>(assertIs<ImportFileIntakeOutcome.Accepted>(after).records[0].matchBasis)
            assertEquals("现金", afterBasis.fundingAccountTargetName)
            assertNull(afterBasis.fundingAccountUnmatchedReason)
        } finally {
            driver.close()
        }
    }

    @Test
    fun anUnwiredSupplierKeepsMatchingSimplyAbsent() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val graph = buildLedgerGraph(driver, createSchema = true)
            assertNull(graph.facade.importIntakeCatalog, "the unwired root must leave the supplier null")

            // catalog = null (the pre-i4 four-argument shape): the row still lands as a
            // candidate, the basis is absent — matching is simply absent, never a fabricated
            // all-unmatched face; the parsed completeness is untouched by the arm.
            val outcome =
                graph.facade.importFileIntake!!.intake(
                    ImportFileIntakeInput(
                        format = ImportFormatCapabilities.SELF_ENTRY_XLSX.identifier,
                        platform = ImportPlatformKind.DESKTOP,
                        session = graph.facade.importIntakeSessionFactory()!!,
                        bytes = SelfEntryTemplateGenerator.generateTemplate(),
                    ),
                )
            val intaken = assertIs<ImportFileIntakeOutcome.Accepted>(outcome)
            val record = intaken.records[0]
            assertNull(record.matchBasis)
            assertNull(record.selfEntryPrefill)
            // The candidate still lands (matching absence never blocks the intake gate — the
            // matrix is what gates, and the fifth entry is available on every platform now).
            assertEquals(ImportIntakeRecordDisposition.INTAKE_ACCEPTED, record.disposition)
        } finally {
            driver.close()
        }
    }
}
