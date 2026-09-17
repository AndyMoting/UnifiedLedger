package com.unifiedledger.ui

import com.unifiedledger.application.CatalogSnapshotView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A-PERF (P7-04 read-governance batch, spec section 2.3 / 5): the cached catalog snapshot's
 * load-coordinator contract, JVM-tested without any Compose surface (the P503HostCoordinator
 * extraction precedent):
 *
 * - single-flight: concurrent requests merge into the running load — only the first request
 *   starts the background read (P704C-SPEC-01/QUAL-02 discipline);
 * - the slot re-arms once the load completes;
 * - S2-3 (晚到旧快照不得覆盖新状态): a successful load installs the fresh value, a
 *   failed/absent load keeps the previously loaded value, and the first-ever failure leaves the
 *   null loading window standing (never a faked empty catalog).
 *
 * The null window's UI contract (the honest 载入中 placeholder with the entry submit staying
 * blocked — 必填 null 不提交) is pinned by [ImportDecisionCatalogPlaceholderTest] through the pure
 * [importDecisionCatalogPlaceholder] presentation decision this batch extracts; this file pins
 * the load admission and the cache-value decision.
 */
class P503CatalogSnapshotLoadCoordinatorTest {
    private fun snapshot(version: Long): CatalogSnapshotView = CatalogSnapshotView(version, emptyList(), emptyList())

    @Test
    fun concurrentRequestsMergeIntoTheRunningLoad() {
        var starts = 0
        val coordinator = P503CatalogSnapshotLoadCoordinator()

        assertTrue(coordinator.startLoadOnce { starts += 1 })
        // A concurrent second request while the load is running is merged into it: the
        // callback does not start a second background read.
        assertFalse(coordinator.startLoadOnce { starts += 1 })
        assertFalse(coordinator.startLoadOnce { starts += 1 })
        assertEquals(1, starts)

        // Completing the load re-arms the slot: the next request starts a real load again.
        assertEquals(snapshot(2L), coordinator.loadCompleted(current = null, fresh = snapshot(2L)))
        assertTrue(coordinator.startLoadOnce { starts += 1 })
        assertEquals(2, starts)
    }

    @Test
    fun aFailedOrAbsentLoadKeepsThePreviouslyLoadedSnapshot() {
        val coordinator = P503CatalogSnapshotLoadCoordinator()
        assertTrue(coordinator.startLoadOnce { })

        // The load fails / returns absent: the previously loaded snapshot (旧值) is kept; a
        // late stale completion never overwrites the newer cached state (S2-3).
        val retained = snapshot(5L)
        assertEquals(retained, coordinator.loadCompleted(current = retained, fresh = null))

        // The first-ever failure leaves the null loading window standing — the honest 载入中,
        // never a fabricated empty catalog.
        assertEquals(null, coordinator.loadCompleted(current = null, fresh = null))
    }

    @Test
    fun aSuccessfulLoadInstallsTheFreshSnapshot() {
        val coordinator = P503CatalogSnapshotLoadCoordinator()
        assertTrue(coordinator.startLoadOnce { })

        // A fresh value replaces the cached one (the normal catalog-refresh cycle).
        assertEquals(snapshot(6L), coordinator.loadCompleted(current = snapshot(5L), fresh = snapshot(6L)))
    }
}
