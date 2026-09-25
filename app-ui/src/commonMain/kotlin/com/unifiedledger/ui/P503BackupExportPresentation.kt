package com.unifiedledger.ui

// P7-06 06.B (D-177; spec `2026-09-24-p7-06-backup-export-design.md` sections 3/5/6) pure
// presentation decisions for the backup-export surface. The composable stays a thin renderer; every
// load-bearing decision — whether the overview offers the export entry, whether the password draft
// may be confirmed, and how each typed outcome is worded — is a pure function here, so it is
// assertable in app-ui's JVM tests without a Compose UI-test harness (the P503CorrectionPresentation
// F6 precedent). No Compose or platform API is used in this file.
//
// Secret discipline (spec section 3.5): the password never appears in any text produced here, and
// no function in this file logs anything.

// ------------------------------------------------------------------ overview entry gate

/**
 * P7-06 06.B (D-177; spec section 3): whether the HOME overview offers the backup-export entry.
 * The host passes [exportWired] = whether the composition root bound the export use case (the
 * `onOpenRecycleBin` null-callback convention): an unwired composition renders no affordance, so
 * there is never a dead button. Unlike the recycle bin this entry needs no additional resolvable
 * payload at render time — the active generation is resolved at export time — so the flag alone
 * decides.
 */
internal fun backupExportEntryVisible(exportWired: Boolean): Boolean = exportWired

// ------------------------------------------------------------------ password gate

/**
 * P7-06 06.B (spec section 4.10; container-format spec section 4.10): whether the password draft
 * may be confirmed. The minimum is the frozen 8-Unicode-code-point rule
 * ([com.unifiedledger.application.backup.BACKUP_MIN_PASSWORD_CODE_POINTS]); the SAME counter the use
 * case applies ([countUnicodeCodePoints]) is reused here so the page and the use case can never
 * disagree about a password's admissibility. A running export also disables confirm (提交中不重入).
 */
internal fun backupExportConfirmEnabled(
    password: String,
    running: Boolean,
): Boolean = !running && countUnicodeCodePoints(password) >= com.unifiedledger.application.backup.BACKUP_MIN_PASSWORD_CODE_POINTS

// ------------------------------------------------------------------ typed outcome banners

/**
 * P7-06 06.B (spec sections 3.5/3.6): the typed outcome banner copy of the export surface. A
 * [BackupExportResult.Succeeded] names the authenticated container's byte size; each
 * [BackupExportFailure] maps to its own explicit line (never a bare "failed"), and
 * [BackupExportResult.Cancelled] is the distinct cancelled line. `null` (no outcome yet) renders
 * nothing. The copy never contains the password, a path or any other secret (spec section 3.5).
 */
internal fun backupExportOutcomeText(outcome: BackupExportResult?): String? =
    when (outcome) {
        null -> null
        is BackupExportResult.Succeeded -> "备份已保存（$BACKUP_TARGET_SUGGESTED_NAME，${outcome.containerBytes} 字节）。"
        BackupExportResult.Cancelled -> "已取消，未生成备份。"
        is BackupExportResult.Failed -> "备份失败：${backupExportFailureText(outcome.reason)}"
    }

/** Whether one landed outcome is an error (rendered with the error color / live region). */
internal fun backupExportOutcomeIsError(outcome: BackupExportResult?): Boolean = outcome is BackupExportResult.Failed

/**
 * The frozen user-facing text of one typed export failure reason. Every reason names what happened
 * without leaking a secret; the unstable enum name is never shown.
 */
internal fun backupExportFailureText(reason: BackupExportFailure): String =
    when (reason) {
        BackupExportFailure.RUNTIME_NOT_READY -> "账本未就绪，请稍后重试。"
        BackupExportFailure.PASSWORD_TOO_SHORT -> "密码至少需要 ${com.unifiedledger.application.backup.BACKUP_MIN_PASSWORD_CODE_POINTS} 个字符。"
        BackupExportFailure.INSUFFICIENT_SPACE -> "存储空间不足，未开始导出。"
        BackupExportFailure.PLAINTEXT_TOO_LARGE -> "账本超过单次备份上限（1 GiB），未生成备份。"
        BackupExportFailure.SNAPSHOT_FAILED -> "无法生成一致性快照，未生成备份。"
        BackupExportFailure.SNAPSHOT_SURFACE_UNAVAILABLE -> "当前账本未提供快照能力，无法导出。"
        BackupExportFailure.SNAPSHOT_INTEGRITY_FAILED -> "快照完整性校验未通过，未生成备份。"
        BackupExportFailure.CONTAINER_WRITE_FAILED -> "加密容器写入失败，未生成备份。"
        BackupExportFailure.TARGET_WRITE_FAILED -> "写入所选位置失败，未生成完整备份。"
    }

/** The export surface's password-field label (the minimum is stated, the password is never echoed). */
internal const val BACKUP_EXPORT_PASSWORD_LABEL: String = "备份密码（至少 8 个字符，不可找回）"

/**
 * P7-06 06.B (spec section 4.10): the explicit warning that the password is never persisted and
 * cannot be recovered. Shown on the surface so the user is told before exporting.
 */
internal const val BACKUP_EXPORT_PASSWORD_WARNING: String = "密码不会保存，也无法找回；请自行妥善保管。"
