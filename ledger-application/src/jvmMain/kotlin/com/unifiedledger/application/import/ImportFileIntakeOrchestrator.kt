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
import com.unifiedledger.application.SelfEntryPrefill
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
import com.unifiedledger.application.import.selfentry.SELF_ENTRY_CANDIDATE_RULE
import com.unifiedledger.application.import.selfentry.SelfEntryBatchResult
import com.unifiedledger.application.import.selfentry.SelfEntryDiagnostic
import com.unifiedledger.application.import.selfentry.SelfEntryMatchInputs
import com.unifiedledger.application.import.selfentry.SelfEntryMatchOutcome
import com.unifiedledger.application.import.selfentry.SelfEntryNameMatcher
import com.unifiedledger.application.import.selfentry.SelfEntryRowResult
import com.unifiedledger.application.import.selfentry.SelfEntryXlsxParser
import com.unifiedledger.application.import.selfentry.toMatchBasis
import com.unifiedledger.application.import.wechat.WechatBatchResult
import com.unifiedledger.application.import.wechat.WechatBillParser
import com.unifiedledger.application.import.wechat.WechatDiagnostic
import com.unifiedledger.application.import.wechat.WechatRowResult
import com.unifiedledger.domain.LedgerId
import java.nio.charset.Charset
import kotlin.coroutines.cancellation.CancellationException

/**
 * P7-04.B jvmMain intake orchestration (D-146, spec section 4.2.3, frozen flow (a)-(g)).
 *
 * (a) matrix gate: a format the matrix does not declare available on the platform is a typed
 * [ImportIntakeBatchFailure.FormatUnavailable] with reason
 * [ImportFormatUnavailableReason.PENDING_DEVICE_VERIFICATION] — zero read, zero parse. After
 * the A-04.2 flip every frozen matrix format is AVAILABLE on both platforms, so this branch
 * is currently unreachable; it is retained as preserved code so a future format entering the
 * matrix in the pending state fails typed instead of silently dispatching. (b) GB18030
 * runtime probe (Alipay CSV only): an unsupported charset is a typed
 * [ImportFormatUnavailableReason.CHARSET_UNSUPPORTED]; the probe lives here in the
 * orchestration, the parser stays unchanged (spec R-11). (c) dispatch to the jvmMain parser
 * of the format. (d) L2 bound: more than [IMPORT_INTAKE_MAX_ACCEPTED_RECORDS] accepted
 * records is a typed [ImportIntakeBatchFailure.BatchExceedsLimit] carrying the actual
 * count — zero intake writes, zero truncation. (e)/(f) per accepted record one
 * [ImportIntakeRequest] with the session's shared opaque handle and the session-stable
 * per-record requestId, dispatched through [ExecuteImportIntake]; a retry within the session
 * reuses the same identities, so it rides the spine's request-level idempotency. A record
 * whose execution raises (DEF-IMPORT-ENOSPC-CRASH-01) stops the dispatch: the remaining
 * records never reach the spine, the call returns the typed
 * [ImportIntakeBatchFailure.StorageUnavailable], and records already committed keep their
 * outcomes. (g) aggregation per the frozen fold.
 *
 * Per-row parser rejections are carried in the summaries as safe-location digests only
 * (code/severity/scope/ordinal/field role — D-097/D06); raw row content, file names and
 * personal identifiers never reach the outcome.
 *
 * P8-04 i3a (D-18): the SELF_ENTRY arm name-matches every accepted row against the input's
 * catalog snapshot — one matcher per dispatch, indexes assembled once. The match outcome is
 * a prefill-draft and display carrier only (D-017: matching never decides; D-016: the match
 * inputs are session data, never part of the content fingerprint). A [SelfEntryMatchOutcome.Matched]
 * row keeps its parsed completeness and rides the rule override `self_entry_v1`; an
 * [SelfEntryMatchOutcome.Unmatched] row (including every ambiguous face — D-18 counts
 * ambiguity as no match) has its completeness DEMOTED from VALID_COMPLETE to
 * VALID_INCOMPLETE before the request is built — exactly the D-6 failure semantics, so the
 * demoted row lands on the low `confidenceFor` value and never reaches the
 * VALID_COMPLETE+SETTLED duplicate-family gate — and its summary carries the typed
 * [SelfEntryMatchBasis] (D-19 display data, consumed by i3b). An input without a catalog
 * (every construction predating i3a) leaves the rows' completeness untouched and carries no
 * basis — matching is simply absent.
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
                ImportFormatCapabilities.SELF_ENTRY_XLSX.identifier -> parseSelfEntry(inputRef, input.bytes)
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

        // P8-04 i3a (D-18): one matcher per dispatch over the input's catalog snapshot; the
        // name indexes are assembled once here and reused for every row. A null catalog
        // (every pre-i3a construction) disables the SELF_ENTRY arm's matching entirely.
        val selfEntryMatcher = input.catalog?.let { SelfEntryNameMatcher(it) }

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
                    // P8-04 i3a (D-18): the self-entry arm matches against the catalog
                    // snapshot first; the outcome shapes the request (completeness demotion)
                    // and the summary (the D-19 basis). The rule override rides the ARM
                    // itself (every self-entry candidate is `self_entry_v1`, with or without
                    // a catalog); matching, the basis and the demotion need the catalog. All
                    // other formats keep the exact pre-i3a shape.
                    val matchInputs = record.matchInputs
                    val matchOutcome = matchInputs?.let { inputs -> selfEntryMatcher?.match(inputs, record.recordKind, record.facts.directionToken) }
                    // Hoisted once per record: the basis exists exactly when both the verbatim
                    // inputs and the matched outcome exist (null matchInputs => null basis).
                    val matchBasis = matchInputs?.let { inputs -> matchOutcome?.toMatchBasis(inputs) }
                    // P8-04 i3b (D-18/D-017): the prefill exists exactly when the match fully
                    // resolved — the matched ids seed the session decision drafts as INITIAL
                    // VALUES only (blank slots stay null; a Unmatched row carries no prefill,
                    // its basis reason tokens carry the display instead).
                    val selfEntryPrefill =
                        (matchOutcome as? SelfEntryMatchOutcome.Matched)?.let { matched ->
                            SelfEntryPrefill(
                                categoryId = matched.categoryId,
                                fundingAccountId = matched.fundingAccountId,
                                fromAccountId = matched.fromAccountId,
                                toAccountId = matched.toAccountId,
                            )
                        }
                    val completeness =
                        if (matchOutcome is SelfEntryMatchOutcome.Unmatched && record.completeness == ImportCompleteness.VALID_COMPLETE) {
                            ImportCompleteness.VALID_INCOMPLETE
                        } else {
                            record.completeness
                        }
                    val request =
                        ImportIntakeRequest(
                            identity = ImportRequestIdentity(ledgerId, session.requestIdFor(record.ordinal)),
                            inputRef = inputRef,
                            recordOrdinal = record.ordinal,
                            recordKind = record.recordKind,
                            facts = record.facts,
                            completeness = completeness,
                            candidateGeneratedAt = generatedAt,
                            paymentProfile = record.paymentProfile,
                            candidateRuleOverride = if (matchInputs != null) SELF_ENTRY_CANDIDATE_RULE else null,
                        )
                    // DEF-IMPORT-ENOSPC-CRASH-01 (D-276 item 2): the catch wraps this dispatch
                    // call only — never the parser above, never the aggregation below. An
                    // exception raised while executing one record means the attempt produced no
                    // verdict, so the dispatch stops here (the remaining records never reach the
                    // spine) and the whole call returns the typed batch failure. The failure is
                    // deliberately NOT classified against the underlying exception — no message,
                    // cause, or class is carried (D06) — and records committed before it keep
                    // their outcomes, so the outcome never claims a zero-write batch.
                    // Two things are NOT storage verdicts and stay untouched: a fatal `Error`
                    // (never caught — `Exception` only) and coroutine cancellation (rethrown
                    // immediately, so a cancelled dispatch can never surface as a retryable
                    // "import aborted").
                    val result =
                        try {
                            executeIntake.execute(request)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            return ImportFileIntakeOutcome.Rejected(ImportIntakeBatchFailure.StorageUnavailable)
                        }
                    when (result) {
                        is ImportIntakeResult.Accepted -> {
                            summaries +=
                                ImportIntakeRecordSummary(
                                    recordOrdinal = record.ordinal,
                                    disposition = ImportIntakeRecordDisposition.INTAKE_ACCEPTED,
                                    matchBasis = matchBasis,
                                    // P8-04 i3b: the candidate id rides ONLY the accepted branch
                                    // (shared by every format, so the UI can key the D-19/D-7/D-017
                                    // session display data to its candidate rows uniformly); the
                                    // self-entry arm additionally carries its note and prefill.
                                    candidateId = result.receipt.candidateId,
                                    noteText = record.noteText,
                                    selfEntryPrefill = selfEntryPrefill,
                                )
                            newCandidateIds += result.receipt.candidateId
                        }
                        is ImportIntakeResult.NoChange ->
                            summaries +=
                                ImportIntakeRecordSummary(
                                    recordOrdinal = record.ordinal,
                                    disposition = ImportIntakeRecordDisposition.INTAKE_NO_CHANGE,
                                    matchBasis = matchBasis,
                                )
                        is ImportIntakeResult.Rejected ->
                            summaries +=
                                ImportIntakeRecordSummary(
                                    recordOrdinal = record.ordinal,
                                    disposition = ImportIntakeRecordDisposition.INTAKE_REJECTED,
                                    diagnosticCode = result.diagnostic.code,
                                    diagnosticSeverity = result.diagnostic.severity,
                                    diagnosticScope = result.diagnostic.scope,
                                    matchBasis = matchBasis,
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
            /**
             * P8-04 i3a (D-18): the self-entry row's verbatim name inputs, folded into the
             * dispatch arm (not dropped at the boundary anymore); null on every other arm
             * keeps the four frozen arms byte-identical.
             */
            val matchInputs: SelfEntryMatchInputs? = null,
            /**
             * P8-04 i3b (D-7): the self-entry row's 备注 cell text, display-only (carried to
             * the summary's [ImportIntakeRecordSummary.noteText]; import_source_record has no
             * note column — D-016, zero persistence); null on every other arm.
             */
            val noteText: String? = null,
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

    // P8-04 i2 arm (D-13): the same dispatch/error-mapping shape as the four frozen arms —
    // the parser's row results carry no payment profile (v1/v2 kinds only), so the profile
    // slot stays null exactly like the WeChat/CMB/CCB arms. P8-04 i3a (D-18) folds the
    // verbatim name inputs into the arm (the i2 boundary dropped them), so the loop's
    // matcher can consume them. P8-04 i3b (D-7) folds the display-only note text through
    // the same way.
    private fun parseSelfEntry(
        inputRef: String,
        bytes: ByteArray,
    ): ParsedBatch =
        toParsedBatch(SelfEntryXlsxParser.parse(inputRef, bytes)) { row ->
            when (row) {
                is SelfEntryRowResult.Accepted ->
                    ParsedRecord.Accepted(row.recordOrdinal, row.recordKind, row.facts, row.completeness, null, row.matchInputs, row.noteText)
                is SelfEntryRowResult.Rejected -> ParsedRecord.Rejected(row.recordOrdinal, row.diagnostics.first().toDigest())
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

        fun toParsedBatch(
            batch: SelfEntryBatchResult,
            row: (SelfEntryRowResult) -> ParsedRecord,
        ): ParsedBatch = batch.diagnostic?.toDigest()?.let { ParsedBatch.WholeBatchRejection(it) } ?: ParsedBatch.Records(batch.rows.map(row))
    }
}

/** Parser diagnostics project onto the D-097 safe-location digest (D06: no raw content). */
internal fun WechatDiagnostic.toDigest(): ImportBatchParserDiagnostic = ImportBatchParserDiagnostic(code, severity, scope, inputRef, recordOrdinal, fieldRole)

internal fun AlipayDiagnostic.toDigest(): ImportBatchParserDiagnostic = ImportBatchParserDiagnostic(code, severity, scope, inputRef, recordOrdinal, fieldRole)

internal fun CmbDiagnostic.toDigest(): ImportBatchParserDiagnostic = ImportBatchParserDiagnostic(code, severity, scope, inputRef, recordOrdinal, fieldRole)

internal fun CcbDiagnostic.toDigest(): ImportBatchParserDiagnostic = ImportBatchParserDiagnostic(code, severity, scope, inputRef, recordOrdinal, fieldRole)

internal fun SelfEntryDiagnostic.toDigest(): ImportBatchParserDiagnostic = ImportBatchParserDiagnostic(code, severity, scope, inputRef, recordOrdinal, fieldRole)
