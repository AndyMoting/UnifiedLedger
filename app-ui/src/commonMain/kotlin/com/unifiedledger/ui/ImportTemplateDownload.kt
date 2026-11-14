package com.unifiedledger.ui

/**
 * P8-04 i1 (D-11; spec `docs/specs/2026-11-14-p8-04-self-entry-import-design.md`): the
 * platform port behind the import surface's 「获取导入模板」 entry. One method, fire-and-forget
 * about the outcome — the typed result flows back through the composition root's
 * [ImportTemplateResultChannel], never through this signature (the `ImportFilePickPort.launch`
 * precedent; the channel's "absorbed with no listener" contract covers an unsubscribed window).
 *
 * WHY THE PORT GENERATES: the template bytes come from `SelfEntryTemplateGenerator`
 * (ledger-application jvmMain — D-11's runtime generation, the zero-asset-drift guarantee).
 * commonMain cannot see a jvmMain symbol, so the ADAPTER generates (Android and desktop both
 * compile ledger-application's jvmMain) and writes; commonMain declares value types only —
 * exactly the ImportFilePickPort shared-surface contract (no platform surface leaks into
 * commonMain, no InputStream-shaped signature here).
 *
 * D-14: the suggested file name is the adapter's business (`SelfEntryTemplateGenerator.TEMPLATE_FILE_NAME`
 * — the version lives with the template, the `AndroidBackupTargetPort.BACKUP_TARGET_NAME`
 * placement), never a commonMain copy.
 */
fun interface ImportTemplateTargetPort {
    /**
     * Launches the platform save (SAF `CreateDocument` / the desktop equivalent), generates the
     * template and writes it to the picked target. May block (the desktop modal chooser
     * precedent, AB-CLI-QUAL-05); the typed outcome is delivered through the channel.
     */
    fun writeTemplate()
}

/**
 * The typed outcome of one template download (D-11: 写出失败类型化 — the backup-export error
 * family shape). Success is reported ONLY for [Succeeded]: the last byte was written and the
 * stream closed cleanly; a mid-write failure or an unclean close never reports success.
 */
sealed interface ImportTemplateDownloadResult {
    /** The template bytes were generated, written and the target stream closed cleanly. */
    data class Succeeded(
        val bytes: Int,
    ) : ImportTemplateDownloadResult

    /** Opening the user target, writing, or closing the stream failed (typed; raw exceptions never leak, D06). */
    data object WriteFailed : ImportTemplateDownloadResult

    /** The user dismissed the target picker (or the launch could not even start — the `BackupTargetPort` null-open mapping); a non-failure. */
    data object Cancelled : ImportTemplateDownloadResult
}

/**
 * The composition-root result channel for template download outcomes (the
 * [ImportFilePickResultChannel] shape, P8-04 i1): the adapter delivers here and the shared host
 * subscribes via [subscribe]. Deliveries happen on the platform's SAF callback thread (the UI
 * thread on both ends).
 */
class ImportTemplateResultChannel {
    private var forward: ((ImportTemplateDownloadResult) -> Unit)? = null

    /** Delivers one typed download outcome to the subscribed listener (absorbed with no listener). */
    fun deliver(result: ImportTemplateDownloadResult) {
        forward?.invoke(result)
    }

    /** The shared host's subscription point; last subscription wins. */
    fun subscribe(listener: ((ImportTemplateDownloadResult) -> Unit)?) {
        forward = listener
    }
}
