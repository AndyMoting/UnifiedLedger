package com.unifiedledger.application.export.readable

/**
 * P8-05 批 a (D-331 spec D-E12): the self-owned minimal byte sink of the readable export — the
 * same semantics as the backup `BackupTargetWriter` family the spec cites (`BackupExport.kt:134-145`)
 * with zero app-ui types: `write` streams the next byte range, `commit` publishes the delivered
 * content exactly once, `close` is idempotent and abandons an uncommitted target. `null` from an
 * open port = the user cancelled the picker. Batch b bridges THIS face to the platform target
 * ports (app-ui -> ledger-application, never the reverse); the JVM writer ([ReadableLedgerXlsxStreamWriter])
 * and the JVM fake sinks of the failure tests face only this contract.
 */
interface ReadableExportSink : AutoCloseable {
    fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    )

    /** Publishes the delivered content; must be called exactly once. */
    fun commit()
}
