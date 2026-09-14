package com.unifiedledger.application.import

import com.unifiedledger.application.ExecuteImportIntake
import com.unifiedledger.application.IMPORT_INTAKE_MAX_ACCEPTED_RECORDS
import com.unifiedledger.application.ImportBatchParserDiagnostic
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportFileIntakeInput
import com.unifiedledger.application.ImportFileIntakeOutcome
import com.unifiedledger.application.ImportFileIntakePort
import com.unifiedledger.application.ImportFormatAvailability
import com.unifiedledger.application.ImportFormatCapabilities
import com.unifiedledger.application.ImportFormatUnavailableReason
import com.unifiedledger.application.ImportIntakeBatchFailure
import com.unifiedledger.application.ImportIntakeRecordDisposition
import com.unifiedledger.application.ImportIntakeRecordSummary
import com.unifiedledger.application.ImportIntakeRequest
import com.unifiedledger.application.ImportIntakeResult
import com.unifiedledger.application.ImportPaymentProfile
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportRequestIdentity
import com.unifiedledger.application.ImportSourceFacts
import com.unifiedledger.application.aggregateImportIntakeRecords
import com.unifiedledger.application.import.alipay.AlipayBatchResult
import com.unifiedledger.application.import.alipay.AlipayCsvParser
import com.unifiedledger.application.import.alipay.AlipayDiagnostic
import com.unifiedledger.application.import.alipay.AlipayRowResult
import com.unifiedledger.application.import.ccb.CcbBatchResult
import com.unifiedledger.application.import.ccb.CcbBillParser
import com.unifiedledger.application.import.ccb.CcbDiagnostic
import com.unifiedledger.application.import.ccb.CcbRowResult
import com.unifiedledger.application.import.cmb.CmbBatchResult
import com.unifiedledger.application.import.cmb.CmbBillParser
import com.unifiedledger.application.import.cmb.CmbDiagnostic
import com.unifiedledger.application.import.cmb.CmbRowResult
import com.unifiedledger.application.import.wechat.WechatBatchResult
import com.unifiedledger.application.import.wechat.WechatBillParser
import com.unifiedledger.application.import.wechat.WechatDiagnostic
import com.unifiedledger.application.import.wechat.WechatRowResult
import com.unifiedledger.domain.LedgerId
import java.nio.charset.Charset

/**
 * P7-04.B jvmMain intake orchestration (D-146, spec section 4.2.3, frozen flow (a)-(g)).
 *
 * (a) matrix gate: a format the matrix does not declare available on the platform is a typed
 * [ImportIntakeBatchFailure.FormatUnavailable] with reason
 * [ImportFormatUnavailableReason.PENDING_DEVICE_VERIFICATION] (CCB XLS on Android) — zero
 * read, zero parse. (b) GB18030 runtime probe (Alipay CSV only): an unsupported charset is a
 * typed [ImportFormatUnavailableReason.CHARSET_UNSUPPORTED]; the probe lives here in the
 * orchestration, the parser stays unchanged (spec R-11). (c) dispatch to the jvmMain parser
 * of the format. (d) L2 bound: more than [IMPORT_INTAKE_MAX_ACCEPTED_RECORDS] accepted
 * records is a typed [ImportIntakeBatchFailure.BatchExceedsLimit] carrying the actual
 * count — zero intake writes, zero truncation. (e)/(f) per accepted record one
 * [ImportIntakeRequest] with the session's shared opaque handle and the session-stable
 * per-record requestId, dispatched through [ExecuteImportIntake]; a retry within the session
 * reuses the same identities, so it rides the spine's request-level idempotency. (g)
 * aggregation per the frozen fold.
 *
 * Per-row parser rejections are carried in the summaries as safe-location digests only
 * (code/severity/scope/ordinal/field role — D-097/D06); raw row content, file names and
 * personal identifiers never reach the outcome.
 */
class JvmImportFileIntake(
    private val ledgerId: LedgerId,
    private val executeIntake: ExecuteImportIntake,
    private val candidateGeneratedAt: () -> String,
    private val charsetSupported: (name: String) -> Boolean = Charset::isSupported,
) : ImportFileIntakePort {
    override fun intake(input: ImportFileIntakeInput): ImportFileIntakeOutcome {
        val descriptor = ImportFormatCapabilities.byIdentifier(input.format)
        val session = input.session
        val inputRef = session.inputRef

        // (a) Format capability matrix gate.
        if (descriptor.availabilityOn(input.platform) != ImportFormatAvailability.AVAILABLE) {
            return ImportFileIntakeOutcome.Rejected(
                ImportIntakeBatchFailure.FormatUnavailable(
                    formatId = descriptor.identifier,
                    formatDisplayName = descriptor.displayName,
                    reason = ImportFormatUnavailableReason.PENDING_DEVICE_VERIFICATION,
                ),
            )
        }

        // (b) GB18030 runtime probe: the Alipay parser falls back to GB18030 and eagerly
        // resolves the charset on first use, so the probe must run before the dispatch.
        if (descriptor.identifier == ImportFormatCapabilities.ALIPAY_CSV.identifier &&
            !charsetSupported(ALIPAY_FALLBACK_CHARSET)
        ) {
            return ImportFileIntakeOutcome.Rejected(
                ImportIntakeBatchFailure.FormatUnavailable(
                    formatId = descriptor.identifier,
                    formatDisplayName = descriptor.displayName,
                    reason = ImportFormatUnavailableReason.CHARSET_UNSUPPORTED,
                ),
            )
        }

        // (c) Parser dispatch; a whole-batch typed rejection stays typed here (zero intake).
        val parsed =
            when (descriptor.identifier) {
                ImportFormatCapabilities.WECHAT_XLSX.identifier -> parseWechat(inputRef, input.bytes)
                ImportFormatCapabilities.ALIPAY_CSV.identifier -> parseAlipay(inputRef, input.bytes)
                ImportFormatCapabilities.CMB_CSV.identifier -> parseCmb(inputRef, input.bytes)
                ImportFormatCapabilities.CCB_XLS.identifier -> parseCcb(inputRef, input.bytes)
                else -> throw IllegalArgumentException("no parser for format ${descriptor.identifier.value}")
            }
        if (parsed is ParsedBatch.WholeBatchRejection) {
            return ImportFileIntakeOutcome.Rejected(ImportIntakeBatchFailure.ParserRejected(parsed.diagnostic))
        }
        val records = (parsed as ParsedBatch.Records).records

        // (d) L2 accepted-record bound: accepted records only (parser-rejected rows do not
        // count); over the bound = typed batch failure with the actual count, zero intake
        // writes, zero truncation.
        val acceptedCount = records.count { it is ParsedRecord.Accepted }
        if (acceptedCount > IMPORT_INTAKE_MAX_ACCEPTED_RECORDS) {
            return ImportFileIntakeOutcome.Rejected(
                ImportIntakeBatchFailure.BatchExceedsLimit(actualAcceptedRecords = acceptedCount),
            )
        }

        // (e) Audit-time source for this dispatch; identities come from the session (one
        // shared handle, per-record memoized requestIds).
        val generatedAt = candidateGeneratedAt()

        // (f) Per-record dispatch through the spine use case.
        val summaries = mutableListOf<ImportIntakeRecordSummary>()
        val newCandidateIds = mutableListOf<ImportCandidateId>()
        for (record in records) {
            when (record) {
                is ParsedRecord.Rejected -> {
                    summaries +=
                        ImportIntakeRecordSummary(
                            recordOrdinal = record.ordinal,
                            disposition = ImportIntakeRecordDisposition.PARSER_REJECTED,
                            diagnosticCode = record.diagnostic.code,
                            diagnosticSeverity = record.diagnostic.severity,
                            diagnosticScope = record.diagnostic.scope,
                            diagnosticFieldRole = record.diagnostic.fieldRole,
                        )
                }
                is ParsedRecord.Accepted -> {
                    val request =
                        ImportIntakeRequest(
                            identity = ImportRequestIdentity(ledgerId, session.requestIdFor(record.ordinal)),
                            inputRef = inputRef,
                            recordOrdinal = record.ordinal,
                            recordKind = record.recordKind,
                            facts = record.facts,
                            completeness = record.completeness,
                            candidateGeneratedAt = generatedAt,
                            paymentProfile = record.paymentProfile,
                        )
                    when (val result = executeIntake.execute(request)) {
                        is ImportIntakeResult.Accepted -> {
                            summaries +=
                                ImportIntakeRecordSummary(
                                    recordOrdinal = record.ordinal,
                                    disposition = ImportIntakeRecordDisposition.INTAKE_ACCEPTED,
                                )
                            newCandidateIds += result.receipt.candidateId
                        }
                        is ImportIntakeResult.NoChange ->
                            summaries +=
                                ImportIntakeRecordSummary(
                                    recordOrdinal = record.ordinal,
                                    disposition = ImportIntakeRecordDisposition.INTAKE_NO_CHANGE,
                                )
                        is ImportIntakeResult.Rejected ->
                            summaries +=
                                ImportIntakeRecordSummary(
                                    recordOrdinal = record.ordinal,
                                    disposition = ImportIntakeRecordDisposition.INTAKE_REJECTED,
                                    diagnosticCode = result.diagnostic.code,
                                    diagnosticSeverity = result.diagnostic.severity,
                                    diagnosticScope = result.diagnostic.scope,
                                )
                    }
                }
            }
        }

        // (g) Frozen aggregation fold.
        return aggregateImportIntakeRecords(summaries, newCandidateIds)
    }

    // ------------------------------------------------------------------ parser adapters

    private sealed interface ParsedBatch {
        class WholeBatchRejection(
            val diagnostic: ImportBatchParserDiagnostic,
        ) : ParsedBatch

        class Records(
            val records: List<ParsedRecord>,
        ) : ParsedBatch
    }

    private sealed interface ParsedRecord {
        val ordinal: Int

        class Accepted(
            override val ordinal: Int,
            val recordKind: ImportRecordKind,
            val facts: ImportSourceFacts,
            val completeness: ImportCompleteness,
            val paymentProfile: ImportPaymentProfile?,
        ) : ParsedRecord

        class Rejected(
            override val ordinal: Int,
            val diagnostic: ImportBatchParserDiagnostic,
        ) : ParsedRecord
    }

    private fun parseWechat(
        inputRef: String,
        bytes: ByteArray,
    ): ParsedBatch =
        toParsedBatch(WechatBillParser.parse(inputRef, bytes)) { row ->
            when (row) {
                is WechatRowResult.Accepted ->
                    ParsedRecord.Accepted(row.recordOrdinal, row.recordKind, row.facts, row.completeness, null)
                is WechatRowResult.Rejected -> ParsedRecord.Rejected(row.recordOrdinal, row.diagnostics.first().toDigest())
            }
        }

    private fun parseAlipay(
        inputRef: String,
        bytes: ByteArray,
    ): ParsedBatch =
        toParsedBatch(AlipayCsvParser.parse(inputRef, bytes)) { row ->
            when (row) {
                is AlipayRowResult.Accepted ->
                    ParsedRecord.Accepted(row.recordOrdinal, row.recordKind, row.facts, row.completeness, row.paymentProfile)
                is AlipayRowResult.Rejected -> ParsedRecord.Rejected(row.recordOrdinal, row.diagnostics.first().toDigest())
            }
        }

    private fun parseCmb(
        inputRef: String,
        bytes: ByteArray,
    ): ParsedBatch =
        toParsedBatch(CmbBillParser.parse(inputRef, bytes)) { row ->
            when (row) {
                is CmbRowResult.Accepted ->
                    ParsedRecord.Accepted(row.recordOrdinal, row.recordKind, row.facts, row.completeness, null)
                is CmbRowResult.Rejected -> ParsedRecord.Rejected(row.recordOrdinal, row.diagnostics.first().toDigest())
            }
        }

    private fun parseCcb(
        inputRef: String,
        bytes: ByteArray,
    ): ParsedBatch =
        toParsedBatch(CcbBillParser.parse(inputRef, bytes)) { row ->
            when (row) {
                is CcbRowResult.Accepted ->
                    ParsedRecord.Accepted(row.recordOrdinal, row.recordKind, row.facts, row.completeness, null)
                is CcbRowResult.Rejected -> ParsedRecord.Rejected(row.recordOrdinal, row.diagnostics.first().toDigest())
            }
        }

    private companion object {
        const val ALIPAY_FALLBACK_CHARSET = "GB18030"

        fun toParsedBatch(
            batch: WechatBatchResult,
            row: (WechatRowResult) -> ParsedRecord,
        ): ParsedBatch = batch.diagnostic?.toDigest()?.let { ParsedBatch.WholeBatchRejection(it) } ?: ParsedBatch.Records(batch.rows.map(row))

        fun toParsedBatch(
            batch: AlipayBatchResult,
            row: (AlipayRowResult) -> ParsedRecord,
        ): ParsedBatch = batch.diagnostic?.toDigest()?.let { ParsedBatch.WholeBatchRejection(it) } ?: ParsedBatch.Records(batch.rows.map(row))

        fun toParsedBatch(
            batch: CmbBatchResult,
            row: (CmbRowResult) -> ParsedRecord,
        ): ParsedBatch = batch.diagnostic?.toDigest()?.let { ParsedBatch.WholeBatchRejection(it) } ?: ParsedBatch.Records(batch.rows.map(row))

        fun toParsedBatch(
            batch: CcbBatchResult,
            row: (CcbRowResult) -> ParsedRecord,
        ): ParsedBatch = batch.diagnostic?.toDigest()?.let { ParsedBatch.WholeBatchRejection(it) } ?: ParsedBatch.Records(batch.rows.map(row))
    }
}

/** Parser diagnostics project onto the D-097 safe-location digest (D06: no raw content). */
internal fun WechatDiagnostic.toDigest(): ImportBatchParserDiagnostic = ImportBatchParserDiagnostic(code, severity, scope, inputRef, recordOrdinal, fieldRole)

internal fun AlipayDiagnostic.toDigest(): ImportBatchParserDiagnostic = ImportBatchParserDiagnostic(code, severity, scope, inputRef, recordOrdinal, fieldRole)

internal fun CmbDiagnostic.toDigest(): ImportBatchParserDiagnostic = ImportBatchParserDiagnostic(code, severity, scope, inputRef, recordOrdinal, fieldRole)

internal fun CcbDiagnostic.toDigest(): ImportBatchParserDiagnostic = ImportBatchParserDiagnostic(code, severity, scope, inputRef, recordOrdinal, fieldRole)
