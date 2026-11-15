package com.unifiedledger.application.export.readable

import com.unifiedledger.application.CreationEntry
import com.unifiedledger.application.HOME_FLOW_PAGE_SIZE
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.LedgerCurrentStateReadPort
import com.unifiedledger.application.LedgerEntryRow
import com.unifiedledger.application.LedgerFlowPageResult
import com.unifiedledger.application.LedgerFlowTotalResult
import com.unifiedledger.application.MonthlyBuckets
import com.unifiedledger.application.QueryLedgerFlowPage
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * P8-05 批 a export use case (D-331 spec §4 批 a; D-E10/D-E11/D-E12). Walks the frozen page
 * source exactly once (U1: `firstPage`/`pageAfter` until `nextCursor == null`), builds the
 * ten-column row views over the D-E5 presentation table, and streams them through the injected
 * sheet writer. Success is reported ONLY for [ReadableLedgerExportResult.Succeeded] (the
 * `BackupExport` structural discipline, `BackupExport.kt:58`/`:75`); a failure never leaves a
 * committed artifact.
 *
 * D-E11: one operation lease for the whole walk, released in `finally`; page-level consistency
 * only. D-E12: the use case faces only the self-owned sink/cancellation/lease faces — zero
 * app-ui types (批 b bridges the app-ui target ports onto these).
 *
 * 有界内存 (V-3): the loop consumes every row of a page before requesting the next one and holds
 * no cross-page aggregate except counters, so at any instant at most one delivered page is
 * unconsumed; "next page 请求数 = 页数（每页恰一次、零预取零重读）" is observable on the page
 * source.
 *
 * Open for exactly one reason: the run-pipeline test doubles override [export] with canned
 * results (批 b's `P503ReadableExportHostPipelineTest`). Everything else stays closed.
 */
open class ReadableLedgerExport(
    private val leasePort: LedgerRuntimeLeasePort,
    private val flowPage: QueryLedgerFlowPage,
    private val creationEntries: CreationEntryBatchReader?,
    private val catalogProvider: () -> LedgerCatalog?,
    private val writerProvider: () -> ReadableExportSheetWriter?,
    private val clock: LedgerClock,
    private val cancellation: ReadableExportCancellation = ReadableExportCancellation { false },
    private val pageSize: Int = HOME_FLOW_PAGE_SIZE,
    private val progressListener: ReadableLedgerExportProgressListener? = null,
) {
    init {
        require(pageSize > 0) { "page size must be positive" }
    }

    open fun export(ledgerId: LedgerId): ReadableLedgerExportResult {
        // Step 1: the operation lease (D-E11), released on every path.
        val lease =
            when (val acquired = leasePort.acquireLease()) {
                is LedgerRuntimeLeasePortResult.Acquired -> acquired.lease
                LedgerRuntimeLeasePortResult.RuntimeNotReady ->
                    return ReadableLedgerExportResult.Failed(ReadableLedgerExportFailure.RUNTIME_NOT_READY)
            }
        try {
            return runExport(ledgerId)
        } finally {
            runCatching { lease.close() }
        }
    }

    private fun runExport(ledgerId: LedgerId): ReadableLedgerExportResult {
        // Step 2: the exact total is the progress denominator (D-E9); a read failure is typed.
        val total =
            when (val counted = flowPage.total()) {
                is LedgerFlowTotalResult.Success -> counted.total
                LedgerFlowTotalResult.Unavailable ->
                    return ReadableLedgerExportResult.Failed(ReadableLedgerExportFailure.READ_FAILED)
            }
        progressListener?.onProgress(exportedRows = 0L, totalRows = total)

        // Step 3: open the target (null = the user cancelled the picker). R6 前置检出 leg: the
        // first page is read BEFORE the target opens, so an over-bound note costs zero artifacts
        // and zero picker dialogs.
        val firstPage =
            when (val result = flowPage.firstPage(pageSize)) {
                is LedgerFlowPageResult.Success -> result.page
                LedgerFlowPageResult.Unavailable ->
                    return ReadableLedgerExportResult.Failed(ReadableLedgerExportFailure.READ_FAILED)
            }
        if (firstPage.rows.any { !readableExportNoteWithinBound(it.note) }) {
            return ReadableLedgerExportResult.Failed(ReadableLedgerExportFailure.NOTE_TOO_LONG)
        }
        val writer = writerProvider() ?: return ReadableLedgerExportResult.Cancelled

        var exportedRows = 0L
        var unresolvedRows = 0
        try {
            writer.writeHeader()
            // D-E6: one batched lineage read for the whole export (null reader = every row is
            // honestly unmarked; the reader is data-owned and optional at the contract level).
            val entryBatch = creationEntries?.loadCreationEntries(ledgerId)
            val catalog = catalogProvider()
            var cursor: LedgerCurrentStateReadPort.LedgerFlowCursor? = firstPage.nextCursor
            for (row in firstPage.rows) {
                if (cancellation.isCancelled()) {
                    return ReadableLedgerExportResult.Cancelled
                }
                if (!buildAndStreamRow(writer, row, catalog, entryBatch)) unresolvedRows++
                exportedRows++
            }
            progressListener?.onProgress(exportedRows, total)
            while (cursor != null) {
                if (cancellation.isCancelled()) {
                    return ReadableLedgerExportResult.Cancelled
                }
                val page =
                    when (val result = flowPage.pageAfter(cursor, pageSize)) {
                        is LedgerFlowPageResult.Success -> result.page
                        LedgerFlowPageResult.Unavailable ->
                            return ReadableLedgerExportResult.Failed(ReadableLedgerExportFailure.READ_FAILED)
                    }
                for (row in page.rows) {
                    if (cancellation.isCancelled()) {
                        return ReadableLedgerExportResult.Cancelled
                    }
                    // R6 中途检出: the bound crossed after the first page — stop without commit.
                    if (!readableExportNoteWithinBound(row.note)) {
                        return ReadableLedgerExportResult.Failed(ReadableLedgerExportFailure.NOTE_TOO_LONG)
                    }
                    if (!buildAndStreamRow(writer, row, catalog, entryBatch)) unresolvedRows++
                    exportedRows++
                }
                progressListener?.onProgress(exportedRows, total)
                cursor = page.nextCursor
            }
            if (unresolvedRows > 0) {
                // D-E10: PRESENTATION_UNRESOLVED is a data-condition stop like NOTE_TOO_LONG —
                // rows were already streamed, so the outcome is never Succeeded and never
                // committed (no "committed half-product" semantics, V-4).
                return ReadableLedgerExportResult.Failed(ReadableLedgerExportFailure.PRESENTATION_UNRESOLVED)
            }
            writer.commit()
            return ReadableLedgerExportResult.Succeeded(exportedRows, total, suggestedFileName(clock.now()))
        } catch (failure: Throwable) {
            // Any sink write/commit failure (and any other escape) is the typed WRITE_FAILED;
            // a READ or data failure has already returned above, so nothing reports success.
            return ReadableLedgerExportResult.Failed(ReadableLedgerExportFailure.WRITE_FAILED)
        } finally {
            runCatching { writer.close() }
        }
    }

    /** Builds and streams one row; returns whether the D-E5 presentation resolved. */
    private fun buildAndStreamRow(
        writer: ReadableExportSheetWriter,
        row: LedgerEntryRow,
        catalog: LedgerCatalog?,
        entryBatch: CreationEntryBatch?,
    ): Boolean {
        val legFacts =
            row.postings.map { posting ->
                val account = catalog?.accounts?.firstOrNull { it.id == posting.accountId }
                ReadableExportLegFacts(
                    posting = posting,
                    accountKind = account?.kind,
                    categoryId = catalog?.categories?.firstOrNull { it.postingAccountId == posting.accountId }?.id,
                )
            }
        val presentation = resolveReadableExportPresentation(row.kind, legFacts)
        val amountText =
            if (presentation.resolved) {
                readableExportAmountText(
                    checkNotNull(presentation.amountMinor) { "resolved presentation without an amount" },
                    checkNotNull(presentation.currency) { "resolved presentation without a currency" }.precision,
                )
            } else {
                READABLE_EXPORT_PRESENTATION_UNRESOLVED_TEXT
            }
        writer.writeRow(
            ReadableExportRowView(
                occurredAtText = readableExportWallClockText(row.occurredAt),
                statisticsAtText = readableExportWallClockText(row.statisticsAt),
                kindLabel = readableExportKindLabel(row.kind),
                amountText = amountText,
                presentationResolved = presentation.resolved,
                currencyCode = presentation.currency?.code ?: "",
                account1Name = readableExportAccountName(catalog, presentation.account1) ?: "",
                account2Name = readableExportAccountName(catalog, presentation.account2) ?: "",
                categoryPathText = readableExportCategoryPath(catalog, presentation.categoryAnchorAccountId) ?: "",
                noteText = row.note ?: "",
                sourceLabel = readableExportSourceLabel(entryBatch?.entryFor(row.transactionId) ?: CreationEntry.UNMARKED),
            ),
        )
        return presentation.resolved
    }
}

/** The D-E9 counting progress callback: exported so far, and the exact total. */
fun interface ReadableLedgerExportProgressListener {
    fun onProgress(
        exportedRows: Long,
        totalRows: Int,
    )
}

/** D-E10: the typed outcome family; success is ONLY [Succeeded]. */
sealed interface ReadableLedgerExportResult {
    data class Succeeded(
        val exportedRows: Long,
        val totalRows: Int,
        val suggestedFileName: String,
    ) : ReadableLedgerExportResult

    data class Failed(
        val reason: ReadableLedgerExportFailure,
    ) : ReadableLedgerExportResult

    /** The user dismissed the target picker (null writer), or the signal fired mid-walk. */
    data object Cancelled : ReadableLedgerExportResult
}

/** D-E10 failure reasons (a new enum; the backup export's family is not reused). */
enum class ReadableLedgerExportFailure {
    RUNTIME_NOT_READY,
    READ_FAILED,
    WRITE_FAILED,
    NOTE_TOO_LONG,
    PRESENTATION_UNRESOLVED,
}

/** The D-E7 display file name: no ledger id, the Shanghai wall-clock date of the injected clock. */
fun suggestedFileName(now: Instant): String {
    val local = now.toLocalDateTime(MonthlyBuckets.REPORT_TIME_ZONE)
    val year = local.year.toString().padStart(4, '0')
    val month = local.monthNumber.toString().padStart(2, '0')
    val day = local.dayOfMonth.toString().padStart(2, '0')
    return "$READABLE_EXPORT_SUGGESTED_NAME_PREFIX$year$month$day$READABLE_EXPORT_SUGGESTED_NAME_SUFFIX"
}
