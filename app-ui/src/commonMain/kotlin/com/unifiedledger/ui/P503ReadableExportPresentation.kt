package com.unifiedledger.ui

import com.unifiedledger.application.export.readable.ReadableLedgerExportFailure
import com.unifiedledger.application.export.readable.ReadableLedgerExportResult

/*
 * P8-05 批 b (D-331 spec sections 4 批 b / D-E8-D-E11 / V-7) pure presentation decisions for the
 * readable-export surface. The composable stays a thin renderer; every load-bearing decision —
 * whether the drawer offers the 导出账目 row, how the counting progress row reads, and how each
 * typed outcome is worded — is a pure function here, so it is assertable in app-ui's common tests
 * without a Compose UI-test harness (the P503BackupExportPresentation precedent).
 *
 * Frozen copy obligations carried here: U4's purpose-difference note (导出面首行), D-E9's counting
 * progress line 「正在导出… N / M 笔」, D-E10's empty-ledger 「已导出 0 笔」 success banner, §6.8
 * F-3's remnant hint 「可能留有不完整的文件，请删除」 on the artifact-carrying outcomes, and D-E11's
 * page-level-consistency declaration. No file name, path, id or URI ever appears in any text
 * produced here (D-E7; the copy never names the ledger's internal identities).
 */

// ------------------------------------------------------------------ entry gate

/**
 * P8-05 批 b (spec D-E8; U8): whether the drawer's 数据 group offers the 导出账目 row. The host
 * passes [readableExportWired] = whether the composition root bound the readable-export factory
 * (the `LedgerSurfaces.readableExport` probe): an unwired composition renders the honest disabled
 * 暂未开放 row, never a dead affordance (the shell batch's three-state wiring discipline).
 */
internal fun readableExportEntryVisible(readableExportWired: Boolean): Boolean = readableExportWired

// ------------------------------------------------------------------ frozen surface copy

/**
 * U4 (2026-11-15 user ruling; the export face's FIRST line): the purpose-difference note. The
 * readable export is for browsing and reconciliation only — restoring data is exclusively the
 * encrypted backup container's job. Frozen wording; pinned verbatim by the presentation test.
 */
internal const val READABLE_EXPORT_PURPOSE_NOTE: String = "导出的账目文件用于浏览与对账，不能用于恢复数据；整机备份请用导出备份"

/**
 * D-E11 (U10): the page-level-consistency declaration the export face must show. Concurrent
 * modifications (imports, corrections, void/restore, statistics_at fixes) can duplicate already
 * read rows and drop unread ones; the export never claims a point-in-time snapshot.
 */
internal const val READABLE_EXPORT_CONSISTENCY_NOTE: String =
    "导出过程中账本发生变动时，文件可能出现重复或缺失行；导出不承诺时点快照。"

/** §6.8 F-3: the remnant hint carried by every outcome that may have streamed rows to the target. */
internal const val READABLE_EXPORT_REMNANT_HINT: String = "可能留有不完整的文件，请删除。"

// ------------------------------------------------------------------ progress row (D-E9)

/**
 * D-E9: the counting progress row 「正在导出… N / M 笔」, refreshed per page. Before the first
 * progress landing (the exact denominator arrives with it) the row reads the plain 正在导出…
 * shape (the backup face's running-line precedent).
 */
internal fun readableExportProgressText(
    exportedRows: Long,
    totalRows: Int,
): String = if (totalRows > 0) "正在导出… $exportedRows / $totalRows 笔" else "正在导出…"

// ------------------------------------------------------------------ action gates

/** 提交中不重入: the explicit start is offered only while no export runs. */
internal fun readableExportStartEnabled(running: Boolean): Boolean = !running

/**
 * The 取消导出 affordance: reachable exactly while an export runs and the cooperative request has
 * not been recorded yet (a second tap is absorbed by the reducer; the outcome then lands as
 * [ReadableLedgerExportResult.Cancelled] at the next page boundary).
 */
internal fun readableExportCancelEnabled(
    running: Boolean,
    cancelRequested: Boolean,
): Boolean = running && !cancelRequested

// ------------------------------------------------------------------ typed outcome banners

/**
 * D-E10 (spec section 4 批 b; V-4): the typed outcome banner copy. Success is ONLY
 * [ReadableLedgerExportResult.Succeeded] — the empty ledger is the honest 已导出 0 笔 (D-E10).
 * Each failure reason names its own condition (never a bare failure, never an enum name). Every
 * outcome that streams rows through the sink — the successes AND the cancelled/failed walks after
 * the target opened — carries the §6.8 F-3 remnant hint per R8's 横幅明示 obligation; only
 * RUNTIME_NOT_READY honestly does not (refused before any read and any target open, so there is
 * no artifact). `null` (no outcome yet) renders nothing.
 */
internal fun readableExportOutcomeText(outcome: ReadableLedgerExportResult?): String? =
    when (outcome) {
        null -> null
        is ReadableLedgerExportResult.Succeeded -> "已导出 ${outcome.exportedRows} 笔。$READABLE_EXPORT_REMNANT_HINT"
        ReadableLedgerExportResult.Cancelled -> "已取消，导出未完成。$READABLE_EXPORT_REMNANT_HINT"
        // Every failure arm is already a complete sentence with its own frozen wording — a wrapper
        // prefix here would double-prefix the banner and break the §6.4/§6.8 frozen lines.
        is ReadableLedgerExportResult.Failed -> readableExportFailureText(outcome.reason)
    }

/**
 * Whether one landed outcome is an error (rendered with the error color / live region). A success
 * that streamed rows carries the F-3 remnant hint as the registered shared-boundary notice (§6.8
 * R8), so it is NOT flagged as an error.
 */
internal fun readableExportOutcomeIsError(outcome: ReadableLedgerExportResult?): Boolean = outcome is ReadableLedgerExportResult.Failed

/**
 * The frozen user-facing text of one typed failure reason (D-E10's new enum; the backup export's
 * family is NOT reused). Every reason is a distinct line; the artifact-carrying four append the
 * F-3 remnant hint (§6.8: 失败/取消不报告成功、横幅明示残留边界), RUNTIME_NOT_READY does not
 * (the lease refusal precedes every read and every target open — there is no artifact).
 */
internal fun readableExportFailureText(reason: ReadableLedgerExportFailure): String =
    when (reason) {
        ReadableLedgerExportFailure.RUNTIME_NOT_READY -> "账本未就绪，未开始导出。"
        ReadableLedgerExportFailure.READ_FAILED -> "读取账目失败，导出未完成。$READABLE_EXPORT_REMNANT_HINT"
        ReadableLedgerExportFailure.WRITE_FAILED -> "写入导出文件失败，导出未完成。$READABLE_EXPORT_REMNANT_HINT"
        ReadableLedgerExportFailure.NOTE_TOO_LONG -> "备注超长，导出未完成。$READABLE_EXPORT_REMNANT_HINT"
        ReadableLedgerExportFailure.PRESENTATION_UNRESOLVED -> "存在无法确定的账目呈现，导出未完成。$READABLE_EXPORT_REMNANT_HINT"
    }
