package com.unifiedledger.ui

import com.unifiedledger.application.export.readable.ExportOperationLease
import com.unifiedledger.application.export.readable.LedgerRuntimeLeasePort
import com.unifiedledger.application.export.readable.LedgerRuntimeLeasePortResult
import com.unifiedledger.application.export.readable.ReadableExportCancellation
import com.unifiedledger.application.export.readable.ReadableExportSheetWriter
import com.unifiedledger.application.export.readable.ReadableLedgerExport
import com.unifiedledger.application.export.readable.ReadableLedgerExportProgressListener
import com.unifiedledger.domain.LedgerId

/*
 * P8-05 批 b (D-331 spec sections 4 批 b / D-E11/D-E12; V-7): the shared readable-export host
 * face — the composition-root binding the drawer row opens onto. commonMain cannot see the
 * ledger-application jvmMain `ReadableExportSink`/`ReadableLedgerXlsxStreamWriter` symbols (the
 * ImportTemplateTargetPort precedent: the ADAPTER generates), so the binding carries the USE-CASE
 * FACTORY as an injected closure (`newExport`) instead of a concrete type — each platform
 * composition root composes its graph-local factory lambda and its platform sheet-writer bridge
 * into that closure at bind time and passes it here. The lease bridge below faces only commonMain
 * types ([LedgerRuntimeOwner], [LeaseAcquireResult], [LedgerLease]) and the self-owned port; the
 * sink bridge (BackupTargetWriter -> ReadableExportSink) is platform-local.
 *
 * Dependency direction (D-E12): app-ui -> ledger-application, never the reverse.
 */

/**
 * P8-05 批 b (D-E12): the composition-root readable-export binding — ONE named shape for both
 * platforms (the R-222-4 one-named-shape discipline): the use-case factory closure the platform
 * root composed at bind time (graph-local page source/catalog/lineage reader/clock + the platform
 * sheet-writer bridge + the lease bridge). Re-bound on every graph open (a 06.D reopen must not
 * keep the previous graph's page source/catalog). The writer is opened over a FRESHLY picked
 * target per run: a null open (the user's picker cancellation) is the honest null writer the use
 * case maps to Cancelled. Everything runs inside the use case's own lease (D-E11) — nothing here
 * is invoked at wiring time.
 */
class ReadableExportFactoryBinding(
    /**
     * Builds ONE concrete use case per run over the run-scoped cancellation/progress faces (the
     * use case's constructor parameters) — never shared across concurrent runs.
     */
    val newExport: (cancellation: ReadableExportCancellation, progressListener: ReadableLedgerExportProgressListener?) -> ReadableLedgerExport,
)

/**
 * P8-05 批 b (D-E11/D-E12): the composition-root lease bridge from the runtime owner onto the
 * self-owned [LedgerRuntimeLeasePort] — one operation lease for the whole export walk, acquired
 * non-blocking and released in the use case's `finally` exactly once. The [LedgerLease] carries
 * its acquire-time generation but the port face ignores it (the export's landing discard rides
 * the host's own captured [ReadableExportLaunch.generation]); the release is the idempotent
 * [LedgerLease.close]. This is the ONLY place the owner's lease face meets the export use case:
 * the use case stays free of any app-ui type (D-E12's "no reverse edge").
 */
class LedgerRuntimeOwnerLeasePort(
    private val owner: LedgerRuntimeOwner<*>,
) : LedgerRuntimeLeasePort {
    override fun acquireLease(): LedgerRuntimeLeasePortResult =
        when (val acquired = owner.acquireLease()) {
            is LeaseAcquireResult.Acquired -> LedgerRuntimeLeasePortResult.Acquired(acquired.lease.asExportOperationLease())
            LeaseAcquireResult.RuntimeNotReady -> LedgerRuntimeLeasePortResult.RuntimeNotReady
        }
}

/** Adapts the owner's lease onto the self-owned one-shot close face (both idempotent). */
private fun LedgerLease.asExportOperationLease(): ExportOperationLease = ExportOperationLease { close() }

/** One readable-export request (D-E10/D-E11): the ledger is the facade's own, no other payload. */
class ReadableExportRequest(
    /** The ledger id the export walks (the facade's fixed single-ledger identity). */
    val ledgerId: LedgerId,
)

/**
 * The launch for the CURRENT active generation — the use-case factory plus the generation it was
 * resolved under (captured by [LedgerLeaseScope.readableExportLaunch]). The generation rides the
 * landing hop's discard rule ([LedgerLeaseScope.isCurrentGeneration]): the result family carries
 * no generation, so the export cannot rely on its own payload for the check. The use case's own
 * lease (acquired inside `ReadableLedgerExport.export`, D-E11) protects the walk from a
 * mid-export close/reopen; this capture is what makes a result that LANDS after a reopen
 * discarded rather than presented as the new graph's result (the backupExportLaunch precedent).
 */
class ReadableExportLaunch(
    val generation: Generation,
    val newExport: (cancellation: ReadableExportCancellation, progressListener: ReadableLedgerExportProgressListener?) -> ReadableLedgerExport,
) {
    fun requestFor(ledgerId: LedgerId): ReadableExportRequest = ReadableExportRequest(ledgerId)
}

/**
 * The GRAPH-LOCAL half of the readable-export factory: everything that lives on one open graph
 * (the session's bounded page source, the session's CURRENT authoritative catalog, the lineage
 * reader and the clock), completed per RUN by the composition-level pieces (the sheet-writer
 * bridge over the platform target and the lease bridge over the owner). One named shape for the
 * graph member, the binding and the tests (the R-222-4 one-named-shape discipline).
 */
typealias ReadableExportGraphFactory =
    (
        writerProvider: () -> ReadableExportSheetWriter?,
        leasePort: LedgerRuntimeLeasePort,
        cancellation: ReadableExportCancellation,
        progressListener: ReadableLedgerExportProgressListener?,
    ) -> ReadableLedgerExport
