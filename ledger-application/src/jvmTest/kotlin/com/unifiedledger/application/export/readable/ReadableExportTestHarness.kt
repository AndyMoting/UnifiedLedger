package com.unifiedledger.application.export.readable

import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.LedgerEntryRow
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.application.QueryLedgerFlowPage
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.TransactionId
import java.io.ByteArrayOutputStream

/**
 * P8-05 批 a test harness (jvmTest): the recording sheet writer, the byte-counting sink, the
 * lease-port fake and the assembled use case shared by the writer/round-trip, failure-family and
 * bounded-memory tests. All fakes face the commonMain contracts only.
 */
class RecordingSheetWriter : ReadableExportSheetWriter {
    val rows = mutableListOf<ReadableExportRowView>()
    val rowCount: Int
        get() = rows.size
    var headerCount = 0
        private set
    var commitCount = 0
        private set
    var closeCount = 0
        private set

    override fun writeHeader() {
        headerCount++
    }

    override fun writeRow(row: ReadableExportRowView) {
        rows.add(row)
    }

    override fun commit() {
        commitCount++
    }

    override fun close() {
        closeCount++
    }
}

/** The lease-port fake with a settable outcome (the RUNTIME_NOT_READY vector). */
class FakeLeasePort(
    var nextOutcome: LedgerRuntimeLeasePortOutcome = LedgerRuntimeLeasePortOutcome.Ready,
) : LedgerRuntimeLeasePort {
    var closedCount = 0
        private set

    override fun acquireLease(): LedgerRuntimeLeasePortResult =
        when (nextOutcome) {
            LedgerRuntimeLeasePortOutcome.Ready ->
                LedgerRuntimeLeasePortResult.Acquired {
                    closedCount++
                }
            LedgerRuntimeLeasePortOutcome.NotReady -> LedgerRuntimeLeasePortResult.RuntimeNotReady
        }
}

enum class LedgerRuntimeLeasePortOutcome {
    Ready,
    NotReady,
}

/** The byte-counting sink fake behind the ENOSPC/commit-order vectors. */
class RecordingSink(
    private val failAfterBytes: Int = Int.MAX_VALUE,
    private val failure: Throwable? = null,
) : ReadableExportSink {
    val buffer = ByteArrayOutputStream()
    var writeCount = 0
        private set
    var commitCount = 0
        private set
    var closeCount = 0
        private set

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (failure != null && buffer.size() >= failAfterBytes) throw failure
        buffer.write(bytes, offset, length)
        writeCount++
    }

    override fun commit() {
        commitCount++
    }

    override fun close() {
        closeCount++
    }
}

/** The batched lineage fake; the labels must follow the D-E6 precedence. */
class FakeCreationEntryBatchReader(
    private val importIds: Set<String> = emptySet(),
    private val manualIds: Set<String> = emptySet(),
) : CreationEntryBatchReader {
    override fun loadCreationEntries(ledgerId: LedgerId): CreationEntryBatch =
        CreationEntryBatch(
            importTransactionIds = importIds.map(::TransactionId).toSet(),
            manualTransactionIds = manualIds.map(::TransactionId).toSet(),
        )
}

/** One assembled export over the fake page source; every collaborator stays reachable for asserts. */
class ExportHarness private constructor(
    val source: FakeFlowPageSource,
    val leasePort: FakeLeasePort,
    val sink: RecordingSink,
    val flowPage: QueryLedgerFlowPage,
    private val pageSize: Int,
    private val clock: LedgerClock,
    private val creationEntries: CreationEntryBatchReader?,
    private val catalogProvider: () -> LedgerCatalog?,
) {
    var export: ReadableLedgerExport = assemble { null }
        private set

    val openedWriter: RecordingSheetWriter?
        get() = lastDefaultWriter

    private var lastDefaultWriter: RecordingSheetWriter? = null

    /** Reassembles the use case with the given writer provider and returns it. */
    fun exportWithWriterProvider(provider: () -> ReadableExportSheetWriter?): ReadableLedgerExport {
        export = assemble(provider)
        return export
    }

    /** Installs a fresh recording writer as the default provider and returns it. */
    fun defaultWriter(): RecordingSheetWriter {
        val writer = RecordingSheetWriter()
        lastDefaultWriter = writer
        export = assemble { writer }
        return writer
    }

    private fun assemble(provider: () -> ReadableExportSheetWriter?): ReadableLedgerExport =
        ReadableLedgerExport(
            leasePort = leasePort,
            flowPage = flowPage,
            creationEntries = creationEntries,
            catalogProvider = catalogProvider,
            writerProvider = provider,
            clock = clock,
            pageSize = pageSize,
        )

    companion object {
        fun build(
            rows: List<LedgerEntryRow>,
            pageSize: Int,
            clock: LedgerClock,
            creationEntries: CreationEntryBatchReader? = null,
            catalogProvider: () -> LedgerCatalog? = { null },
            consumedRowsProvider: () -> Int = { 0 },
        ): ExportHarness {
            val source = FakeFlowPageSource(rows, pageSize, consumedRowsProvider)
            return ExportHarness(
                source = source,
                leasePort = FakeLeasePort(),
                sink = RecordingSink(),
                flowPage = source.flowPage(),
                pageSize = pageSize,
                clock = clock,
                creationEntries = creationEntries,
                catalogProvider = catalogProvider,
            )
        }
    }
}

/** The shared never-cancelled cancellation face for tests that need an explicit one. */
val neverCancelled: ReadableExportCancellation = ReadableExportCancellation { false }
