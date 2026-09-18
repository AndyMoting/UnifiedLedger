package com.unifiedledger.application

/**
 * P7-04.B intake orchestration contract (D-146 Q09, spec sections 3.2/4.2).
 *
 * One intake session corresponds to one file pick: the session identity mints exactly one
 * fresh opaque UUIDv7 [inputRef][ImportIntakeSessionIdentity.inputRef] handle for the whole
 * pick (R-Q09-1: the handle contains no URI, file name, content, or any personal
 * identifier; raw identity stays `(inputRef, recordOrdinal)`, D-098 domain 1.1) and mints one
 * requestId per accepted record. A retry inside the same intake session reuses the same
 * handle and the same per-record requestIds, so it rides the spine's existing request-level
 * idempotency (equivalent replay -> NoChange; non-equivalent -> hard identity-collision
 * reject) instead of writing anything new.
 *
 * The port is implemented by the jvmMain orchestration (`JvmImportFileIntake`); parsers and
 * the injected spine/id sources stay behind that implementation, so this commonMain contract
 * carries no platform or java.io surface.
 */
class ImportIntakeSessionIdentity private constructor(
    val inputRef: String,
    private val generator: UuidV7Generator,
) {
    private val requestIdsByOrdinal = HashMap<Int, ImportRequestId>()

    /**
     * The requestId for one accepted record, minted at most once per ordinal within the
     * session (P7-02 discipline: one fresh requestId per new intent; retries inside the
     * session reuse it, spec sections 3.2.1/4.2.3(e)).
     *
     * Single-threaded session contract (AB-BE-QUAL-02): no JVM-only `synchronized` primitive
     * here — this is commonMain and the frozen semantics stay put (same ordinal -> same
     * memoized id, new ordinal -> fresh UUIDv7, per session). One intake session is
     * dispatched sequentially by its orchestrator (the jvmMain implementation walks the
     * per-record dispatch inside a single `intake` call), so lock-free `getOrPut`
     * memoization is safe; callers must not share one session across concurrent dispatches.
     */
    internal fun requestIdFor(recordOrdinal: Int): ImportRequestId = requestIdsByOrdinal.getOrPut(recordOrdinal) { ImportRequestId(generator.next()) }

    companion object {
        /** One fresh opaque handle per file pick (R-Q09-1; composition root creates this). */
        fun forFilePick(generator: UuidV7Generator): ImportIntakeSessionIdentity =
            ImportIntakeSessionIdentity(
                inputRef = generator.next(),
                generator = generator,
            )
    }
}

/**
 * L2 intake-orchestration bound (R-Q08-2, spec section 3.1.2): at most 10,000 accepted
 * records per single file intake. Over the bound is a typed batch failure carrying the
 * actual count — never a silent truncation and never a partial write.
 */
const val IMPORT_INTAKE_MAX_ACCEPTED_RECORDS: Int = 10_000

/** Typed reasons a format cannot be intaken on the current platform (spec section 4.6). */
enum class ImportFormatUnavailableReason {
    /**
     * The matrix marked the format pending device runtime verification on this platform. The
     * CCB XLS on Android case flipped to available after A-04.1's instrumented verification;
     * the reason stays in the value set so the intake (a) gate keeps its typed pending
     * branch for any future format that re-enters the pending state.
     */
    PENDING_DEVICE_VERIFICATION,

    /** A runtime-required charset (GB18030 for Alipay CSV) is not supported by the runtime. */
    CHARSET_UNSUPPORTED,
}

/** Parser-level batch rejection digest: D-097 safe-location fields only (D06: no raw content). */
data class ImportBatchParserDiagnostic(
    val code: String,
    val severity: String,
    val scope: String,
    val inputRef: String,
    val recordOrdinal: Int?,
    val fieldRole: String?,
)

/** Typed batch failures of one file intake (spec section 4.6 failure family). */
sealed interface ImportIntakeBatchFailure {
    /** Format/platform or runtime-charset gate (spec 4.2.3 (a)/(b)); zero read, zero parse. */
    data class FormatUnavailable(
        val formatId: ImportFormatId,
        val formatDisplayName: String,
        val reason: ImportFormatUnavailableReason,
    ) : ImportIntakeBatchFailure

    /**
     * L2 accepted-record bound exceeded (spec 4.2.3 (d)). Carries the actual accepted-record
     * count; zero intake writes, zero truncation.
     */
    data class BatchExceedsLimit(
        val actualAcceptedRecords: Int,
    ) : ImportIntakeBatchFailure

    /** Whole-batch typed parser rejection (frozen `unsafeOrOverLimit`/`unsupportedInput` family). */
    data class ParserRejected(
        val diagnostic: ImportBatchParserDiagnostic,
    ) : ImportIntakeBatchFailure
}

/** Per-record outcome kinds of one intake dispatch (spine results plus parser row rejections). */
enum class ImportIntakeRecordDisposition {
    /** The record was newly intaken (spine `Accepted`). */
    INTAKE_ACCEPTED,

    /** Equivalent replay inside the session returned the original result with zero new writes. */
    INTAKE_NO_CHANGE,

    /** Typed spine rejection (safe-location diagnostic fields only). */
    INTAKE_REJECTED,

    /** The parser rejected this row; it never reached the spine (zero write, no renumbering). */
    PARSER_REJECTED,
}

/**
 * One record's intake summary (spec 4.2.2: "逐记录结果摘要"). Diagnostic fields are only
 * populated for [ImportIntakeRecordDisposition.PARSER_REJECTED] and
 * [ImportIntakeRecordDisposition.INTAKE_REJECTED], and only ever carry the D-097
 * safe-location shape — never raw row content, file names, or personal identifiers (D06).
 */
data class ImportIntakeRecordSummary(
    val recordOrdinal: Int,
    val disposition: ImportIntakeRecordDisposition,
    val diagnosticCode: String? = null,
    val diagnosticSeverity: String? = null,
    val diagnosticScope: String? = null,
    val diagnosticFieldRole: String? = null,
)

/** Aggregated single-file intake outcome (spec section 4.2.3 (g)). */
sealed interface ImportFileIntakeOutcome {
    /**
     * At least one record produced a new intake or a typed per-record spine rejection; the
     * summaries list every dispatched record plus every parser-rejected row. A zero-record
     * dispatch also routes here with both lists empty (the empty-set addendum of
     * [aggregateImportIntakeRecords]: an empty first pick is an empty `Accepted`, never a
     * `NoChangeAll` re-dispatch). Re-dispatching a session whose records are all
     * `INTAKE_NO_CHANGE` yields [NoChangeAll] instead.
     */
    data class Accepted(
        val records: List<ImportIntakeRecordSummary>,
        val newCandidateIds: List<ImportCandidateId>,
    ) : ImportFileIntakeOutcome

    /**
     * Session-level equivalent re-dispatch: every dispatched record came back NoChange. A
     * zero-record list never lands here (see [aggregateImportIntakeRecords]'s empty-set
     * addendum) — an empty pick is an empty [Accepted], not a re-dispatch.
     */
    data class NoChangeAll(
        val records: List<ImportIntakeRecordSummary>,
    ) : ImportFileIntakeOutcome

    /** Typed batch failure; zero intake writes happened for this call. */
    data class Rejected(
        val failure: ImportIntakeBatchFailure,
    ) : ImportFileIntakeOutcome
}

data class ImportFileIntakeInput(
    val format: ImportFormatId,
    val platform: ImportPlatformKind,
    val session: ImportIntakeSessionIdentity,
    val bytes: ByteArray,
)

/**
 * Intake orchestration port (spec section 4.2.2). The implementation owns the injected
 * dependencies (spine use case, intake id source, fingerprint, ledger scope, audit-time
 * source); the input carries the format identifier, the platform kind, the pick-session
 * identity and the bounded bytes.
 */
interface ImportFileIntakePort {
    fun intake(input: ImportFileIntakeInput): ImportFileIntakeOutcome
}

/**
 * Pure aggregation helper (spec 4.2.3 (g), frozen): all dispatched records NoChange ->
 * [ImportFileIntakeOutcome.NoChangeAll]; otherwise (any newly accepted record, or any typed
 * per-record spine rejection) -> [ImportFileIntakeOutcome.Accepted] carrying the new
 * candidate ids collected from the spine's accepted receipts. Parser-level whole-batch
 * rejection never reaches this fold (it is a typed [ImportFileIntakeOutcome.Rejected]).
 *
 * Empty-set addendum (AB-BE-QUAL-06, registered deviation over the literal `all {}`
 * vacuous-truth reading of the frozen fold, which did not cover the zero-record branch):
 * a record list with zero dispatched records (e.g. a header-only file pick, where nothing
 * reached the spine) routes to [ImportFileIntakeOutcome.Accepted] with both lists empty —
 * never to [ImportFileIntakeOutcome.NoChangeAll], because an empty first pick is not a
 * "session-level equivalent re-dispatch".
 */
fun aggregateImportIntakeRecords(
    records: List<ImportIntakeRecordSummary>,
    newCandidateIds: List<ImportCandidateId>,
): ImportFileIntakeOutcome =
    if (records.isNotEmpty() && records.all { it.disposition == ImportIntakeRecordDisposition.INTAKE_NO_CHANGE }) {
        ImportFileIntakeOutcome.NoChangeAll(records)
    } else {
        ImportFileIntakeOutcome.Accepted(
            records = records,
            newCandidateIds = newCandidateIds,
        )
    }
