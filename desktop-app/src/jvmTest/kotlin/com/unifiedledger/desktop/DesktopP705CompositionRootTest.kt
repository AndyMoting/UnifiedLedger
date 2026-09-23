package com.unifiedledger.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * P7-05.B/C composition-root wiring (D-156/D-158 slice 1b): the correction/void/restore use
 * cases, the two snapshot-aware unknown-commit resolvers and the recycle-bin query are reachable
 * through the wired desktop facade. This is the wiring smoke test of the frozen slice; the use
 * cases' behavior is covered by their own suites.
 */
class DesktopP705CompositionRootTest {
    @Test
    fun facadeExposesTheCorrectionVoidRestoreSurface() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val graph = buildLedgerGraph(driver, createSchema = true)
            assertTrue(graph.facade.correctTransactionVersion != null)
            assertTrue(graph.facade.voidTransaction != null)
            assertTrue(graph.facade.restoreTransaction != null)
            assertTrue(graph.facade.resolveCorrectionCommitStatus != null)
            assertTrue(graph.facade.resolveVoidCommitStatus != null)
            assertTrue(graph.facade.queryRecycleBin != null)
        } finally {
            driver.close()
        }
    }
}
