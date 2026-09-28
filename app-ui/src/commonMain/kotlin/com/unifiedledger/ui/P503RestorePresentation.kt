package com.unifiedledger.ui

import kotlin.time.Instant

// P7-06 06.D (D-182; spec sections 3/5.3/5.5/6) pure presentation decisions for the restore
// confirm & switch surface and the startup POINTER_MISSING recovery face. The composables stay
// thin renderers; every load-bearing decision — whether the overview offers the restore entry,
// which confirm gate is satisfied, how each typed outcome is worded, and how the recovery face
// presents its candidates — is a pure function here, assertable in app-ui's JVM tests without a
// Compose harness (the P503BackupExportPresentation precedent).
//
// Privacy discipline: the preview shows ONLY the seven ruled fields (digests, counts, identities,
// versions, a timestamp) — never a path, a password, a key or plaintext content — and no function
// in this file logs anything.

// ------------------------------------------------------------------ overview entry gate

/**
 * P7-06 06.D (spec section 6): whether the HOME overview offers the restore entry. The host passes
 * [restoreWired] = whether the composition root bound the restore use cases (the export entry's
 * null-callback convention): an unwired composition renders no affordance, so there is never a
 * dead button.
 */
internal fun backupRestoreEntryVisible(restoreWired: Boolean): Boolean = restoreWired

// ------------------------------------------------------------------ confirm gates

/**
 * Whether the preflight may start. The minimum is the frozen 8-Unicode-code-point rule (the SAME
 * [com.unifiedledger.application.backup.BACKUP_MIN_PASSWORD_CODE_POINTS] the export gate applies —
 * a container encrypted under the format's password rule can never be shorter), and a running
 * operation disables it (提交中不重入).
 */
internal fun restorePreflightConfirmEnabled(
    password: String,
    runningPreflight: Boolean,
    runningConfirm: Boolean,
): Boolean =
    !runningPreflight &&
        !runningConfirm &&
        countUnicodeCodePoints(password) >= com.unifiedledger.application.backup.BACKUP_MIN_PASSWORD_CODE_POINTS

/**
 * Whether the EXPLICIT replace confirmation may run (spec section 3: the confirmation must be
 * explicit, and the preview must be on screen when it is): the 7-field preview is bound, the opaque
 * token is bound, and no operation is running.
 */
internal fun restoreSwitchConfirmEnabled(state: P503AppState.BackupRestore): Boolean = state.preview != null && state.token != null && !state.runningPreflight && !state.runningConfirm

// ------------------------------------------------------------------ the 7-field preview (spec 5.5)

/** The ruled preview field list, one line per field, in the spec's order. No secret, no path. */
internal fun restorePreviewLines(summary: RestorePreflightSummary): List<String> =
    listOf(
        "容器格式：版本 ${summary.containerFormatVersion}（${summary.containerSize} 字节）",
        "账本结构版本：${summary.sourceSchemaVersion} → ${summary.migratedSchemaVersion}",
        "账本身份：${summary.sourceLedgerId}（目标：${summary.targetLedgerId}）",
        "工件摘要：${summary.authenticatedArtifactSha256Hex}",
        "校验：完整性 ${checkText(summary.integrityOk)}、外键 ${checkText(summary.foreignKeyOk)}、领域 ${checkText(summary.domainOk)}",
        "将替换为：${summary.accountsCount} 个账户、${summary.categoriesCount} 个分类、${summary.transactionsCount} 笔交易",
        "预检时间：${restoreTimestampText(summary.preflightEpochMillis)}",
    )

/** The UTC ISO-8601 display of the preflight timestamp (the host's clock captured it; no locale). */
internal fun restoreTimestampText(epochMillis: Long): String = Instant.fromEpochMilliseconds(epochMillis).toString()

private fun checkText(ok: Boolean): String = if (ok) "通过" else "未通过"

/** The frozen headline above the preview: the replace is explicit and destructive. */
internal const val RESTORE_REPLACE_WARNING: String = "恢复将替换当前账本中的全部数据；旧数据在切换成功前不会被删除。"

// ------------------------------------------------------------------ typed outcome banners

/** The preflight banner copy (`null` renders nothing). Never contains a secret or a path. */
internal fun restorePreflightOutcomeText(outcome: RestorePreflightResult?): String? =
    when (outcome) {
        null -> null
        is RestorePreflightResult.PreviewReady -> "预检通过：请核对以下信息后确认替换。"
        RestorePreflightResult.Cancelled -> "已取消，未读取备份容器。"
        RestorePreflightResult.RuntimeNotReady -> "账本未就绪，无法执行预检。"
        is RestorePreflightResult.Rejected -> "预检失败：${restorePreflightRejectionText(outcome.code)}"
    }

internal fun restorePreflightOutcomeIsError(outcome: RestorePreflightResult?): Boolean = outcome is RestorePreflightResult.Rejected

/**
 * The user-facing copy of one typed preflight rejection. The frozen `P706_*` codes and the proposed
 * names (06.C section 10 item 3) each map to their own explicit line; the unstable enum name is
 * never shown and no copy carries a secret or a path.
 */
internal fun restorePreflightRejectionText(code: com.unifiedledger.application.backup.BackupPreflightRejection): String =
    when (code) {
        com.unifiedledger.application.backup.BackupPreflightRejection.P706_CONTAINER_FORMAT_UNSUPPORTED -> "不是本产品的备份容器格式。"
        com.unifiedledger.application.backup.BackupPreflightRejection.P706_CONTAINER_VERSION_UNSUPPORTED -> "备份容器版本不受支持。"
        com.unifiedledger.application.backup.BackupPreflightRejection.P706_KDF_UNSUPPORTED -> "备份的密钥派生算法不受支持。"
        com.unifiedledger.application.backup.BackupPreflightRejection.P706_AEAD_UNSUPPORTED -> "备份的加密算法不受支持。"
        com.unifiedledger.application.backup.BackupPreflightRejection.P706_KDF_PARAMETERS_UNSUPPORTED -> "备份的密钥派生参数不受支持。"
        com.unifiedledger.application.backup.BackupPreflightRejection.P706_AEAD_PARAMETERS_UNSUPPORTED -> "备份的加密参数不受支持。"
        com.unifiedledger.application.backup.BackupPreflightRejection.P706_CONTAINER_TOO_LARGE -> "备份容器超过大小上限。"
        com.unifiedledger.application.backup.BackupPreflightRejection.P706_CONTAINER_AUTHENTICATION_FAILED -> "密码错误，或备份已损坏/被篡改。"
        com.unifiedledger.application.backup.BackupPreflightRejection.P706_PAYLOAD_INTEGRITY_FAILED -> "备份内容完整性校验未通过。"
        com.unifiedledger.application.backup.BackupPreflightRejection.P706_SCHEMA_VERSION_UNSUPPORTED -> "备份的账本结构版本不受支持。"
        com.unifiedledger.application.backup.BackupPreflightRejection.P706_CONTAINER_TRUNCATED -> "备份容器不完整（被截断）。"
        com.unifiedledger.application.backup.BackupPreflightRejection.P706_PLAINTEXT_TOO_LARGE -> "备份内容超过大小上限。"
        com.unifiedledger.application.backup.BackupPreflightRejection.P706_CONTAINER_SIZE_MISMATCH -> "备份容器大小与声明不一致。"
        com.unifiedledger.application.backup.BackupPreflightRejection.P706_LEDGER_IDENTITY_UNSUPPORTED -> "该备份不属于当前账本。"
        com.unifiedledger.application.backup.BackupPreflightRejection.P706_SOURCE_READ_FAILED -> "读取备份或暂存数据失败。"
        com.unifiedledger.application.backup.BackupPreflightRejection.P706_MIGRATION_FAILED -> "备份的结构迁移失败。"
        com.unifiedledger.application.backup.BackupPreflightRejection.P706_DOMAIN_VALIDATION_FAILED -> "备份内容未通过领域校验。"
        com.unifiedledger.application.backup.BackupPreflightRejection.P706_INSUFFICIENT_SPACE -> "存储空间不足。"
    }

/** The switch banner copy (`null` renders nothing). A `RecoveryRequired` never renders here. */
internal fun restoreSwitchOutcomeText(outcome: BackupRestoreSwitchResult?): String? =
    when (outcome) {
        null -> null
        is BackupRestoreSwitchResult.Committed -> "恢复已完成，新账本已就绪。"
        is BackupRestoreSwitchResult.RolledBack -> "切换失败，已完整回退到原账本，数据无损；可重新执行预检后再试。"
        is BackupRestoreSwitchResult.Stale -> "恢复被拒绝：${restoreStaleText(outcome.reason)}${runtimeSuffix(outcome.runtime)}"
        is BackupRestoreSwitchResult.Postponed -> "切换已推迟：${restorePostponedText(outcome.reason)}${runtimeSuffix(outcome.runtime)}"
        is BackupRestoreSwitchResult.AbortedBeforePublish -> "未做任何切换：${restoreAbortText(outcome.reason)}${runtimeSuffix(outcome.runtime)}"
        is BackupRestoreSwitchResult.RecoveryRequired -> restoreRecoveryText(outcome.cause)
    }

internal fun restoreSwitchOutcomeIsError(outcome: BackupRestoreSwitchResult?): Boolean = outcome !is BackupRestoreSwitchResult.Committed

private fun runtimeSuffix(runtime: BackupRestoreRuntimeOutcome): String =
    when (runtime) {
        BackupRestoreRuntimeOutcome.RestoredReady -> ""
        BackupRestoreRuntimeOutcome.FailClosed -> "（账本未能重新打开：请重启应用）"
        BackupRestoreRuntimeOutcome.NotRestored -> "（运行时未能恢复：请重启应用）"
    }

private fun restoreStaleText(reason: BackupRestoreStaleReason): String =
    when (reason) {
        BackupRestoreStaleReason.StagingArtifactMissing -> "暂存的备份工件已不存在（可能已被清理），请重新执行预检。"
        BackupRestoreStaleReason.ArtifactUnreadable -> "暂存的备份工件无法读取，请重新执行预检。"
        BackupRestoreStaleReason.DigestMismatch -> "暂存工件与预检结果不一致，请重新执行预检。"
        BackupRestoreStaleReason.GenerationSuperseded -> "账本状态已变化，请重新执行预检。"
        BackupRestoreStaleReason.TargetLedgerMismatch -> "该备份不属于当前账本。"
    }

private fun restorePostponedText(reason: BackupRestorePostponeReason): String =
    when (reason) {
        BackupRestorePostponeReason.QuiesceBlocked -> "账本仍有进行中的操作，请稍后重试。"
        BackupRestorePostponeReason.CloseBlocked -> "账本正在切换状态，请稍后重试。"
        BackupRestorePostponeReason.ConfirmAlreadyRunning -> "已有一个恢复流程在进行中，未做任何操作。"
    }

private fun restoreAbortText(reason: BackupRestoreAbortReason): String =
    when (reason) {
        BackupRestoreAbortReason.InsufficientDiskSpace -> "存储空间不足。"
        BackupRestoreAbortReason.UnknownDiskSpace -> "无法确认可用存储空间，为安全起见放弃切换。"
        BackupRestoreAbortReason.ActivePointerUnreadable -> "无法读取账本指针。"
        BackupRestoreAbortReason.StagingFailed -> "新账本装配未通过一致性检查。"
        BackupRestoreAbortReason.JournalWriteFailed -> "写入切换日志失败。"
    }

/** The session-terminal face's text (the RecoveryRequired shape; the surface never renders it). */
internal fun restoreRecoveryText(cause: BackupRestoreRecoveryCause): String {
    val detail =
        when (cause) {
            BackupRestoreRecoveryCause.RollbackPublishFailed -> "恢复旧指针失败"
            BackupRestoreRecoveryCause.RollbackJournalRemoveFailed -> "移除切换日志失败"
            BackupRestoreRecoveryCause.RollbackReopenFailed -> "重新打开原账本失败"
        }
    return "恢复切换失败且自动回退未完成（$detail）。应用已进入保护状态，请退出并重启，按启动界面的指引处理。"
}

// ------------------------------------------------------------------ the startup recovery face (spec 5.3)

/**
 * The startup-failure face's candidate lines (spec section 5.3): what was found in the generations
 * directory and whether it can be adopted. A wrong-version candidate is named as the upgrade-window
 * copy it is — routed to the discard-and-re-upgrade branch, never adopted.
 */
internal fun restoreRecoveryCandidateLines(state: PointerMissingRecoveryState): List<String> =
    state.candidates.map { candidate ->
        val verdict =
            when (candidate.verdict) {
                PointerRecoveryCandidateVerdict.Adoptable -> "可采纳"
                PointerRecoveryCandidateVerdict.WrongVersion -> "版本过旧（不可采纳，可废弃后重跑旧库升级）"
                PointerRecoveryCandidateVerdict.Unusable -> "无法验证"
            }
        "代目录 gen-${candidate.generation}：$verdict"
    }

/** Whether the user-confirmed adoption affordance is offered (a candidate verified complete-current). */
internal fun restoreRecoveryAdoptVisible(state: PointerMissingRecoveryState): Boolean = state.adoptableGeneration != null

/**
 * Whether the user-confirmed discard-and-re-upgrade affordance is offered: only when the legacy
 * original is available (the desktop shape keeps the state fail-closed with no discard branch) and
 * no candidate is adoptable.
 */
internal fun restoreRecoveryDiscardVisible(state: PointerMissingRecoveryState): Boolean = state.legacyUpgradeAvailable && state.adoptableGeneration == null
